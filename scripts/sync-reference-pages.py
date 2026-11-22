"""Refresh the offline OpenGL reference directly from the published Khronos GL 4.x directory."""
from argparse import ArgumentParser
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
import re
import tarfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
from xml.parsers import expat

BASE = "https://registry.khronos.org/OpenGL-Refpages/gl4/html/"
ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build/downloads/khronos-gl4"
PAGES = ROOT / "intellij-plugin/src/main/resources/reference_pages"
DOWNLOADS = CACHE.parent
SPEC = "https://registry.khronos.org/OpenGL/specs/gl/"
ASSETS = {
    "specs/glspec46.core.pdf": (SPEC + "glspec46.core.pdf", "a6f65e58cd8294188dc4d5cf9d2d581468f8f2e2282101149e14083d75ea9bee"),
    "specs/GLSLangSpec.4.60.pdf": (SPEC + "GLSLangSpec.4.60.pdf", "b22b0c0967a1254c8a3487394d8a3bf72cd8a35a872a43c8a01e03bab3eeb468"),
    "specs/ARB_clip_control.txt": ("https://registry.khronos.org/OpenGL/extensions/ARB/ARB_clip_control.txt", "c2d393311ba8552e86e0491a8286f7967ce5200950859b6bce32fde710889dde"),
}
LICENSES = {
    "LicenseRef-FreeB.txt": ("https://registry.khronos.org/OpenGL-Refpages/LICENSES/LicenseRef-FreeB.txt", "bb8c9ab75a9d4f13b7f71717d938b842789a272df9d614bbf883c475cc3e3872"),
    "OPUBL-1.0.txt": ("https://raw.githubusercontent.com/spdx/license-list-data/main/text/OPUBL-1.0.txt", "82e4552fc0641224de50b710b8cfe78a6e75c9b31d72e494203e10f67e58bf61"),
}
MATHJAX = "https://registry.npmjs.org/mathjax/-/mathjax-2.7.9.tgz"
MATHJAX_SHA256 = "7131e739848edc14aa661a5516995866b81a477fab8b039d7cc324930e71f786"
SCRIPT = "mathjax/MathJax.js?config=TeX-AMS-MML_SVG"
CSP = (b'<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; '
       b"script-src 'self' file: 'unsafe-inline' 'unsafe-eval'; style-src 'self' file: 'unsafe-inline'; "
       b"font-src 'self' file: data:; img-src 'self' file: data:; connect-src 'self' file:; "
       b"base-uri 'none'; object-src 'none'"
       b'"/>')


def write_if_changed(path, data):
    if not path.exists() or path.read_bytes() != data:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def pinned(url, digest, name, cache_only):
    cached = DOWNLOADS / name
    if not cached.exists() or sha256(cached.read_bytes()).hexdigest() != digest:
        if cache_only:
            raise ValueError("Verified asset unavailable in the offline cache: " + name)
        with urlopen(url, timeout=90) as response:
            data = response.read()
        if sha256(data).hexdigest() != digest:
            raise ValueError("Unexpected SHA-256 for " + url)
        write_if_changed(cached, data)
    return cached


def bundle_runtime(cache_only):
    archive = pinned(MATHJAX, MATHJAX_SHA256, "mathjax-2.7.9.tgz", cache_only)
    result = {}
    with tarfile.open(archive) as package:
        for entry in package:
            path = PurePosixPath(entry.name)
            if not entry.isfile() or path.parts[0] != "package" or ".." in path.parts:
                continue
            name = path.relative_to("package").as_posix()
            if name == "LICENSE":
                write_if_changed(ROOT / "licenses/mathjax.txt", package.extractfile(entry).read())
            elif name == "MathJax.js" or name.startswith(("jax/", "extensions/", "config/", "localization/", "fonts/")):
                data = package.extractfile(entry).read()
                filename = "mathjax/" + name
                write_if_changed(PAGES / filename, data)
                result[filename] = sha256(data).hexdigest()
    for name, (url, digest) in ASSETS.items():
        write_if_changed(PAGES / name, pinned(url, digest, Path(name).name, cache_only).read_bytes())
        result[name] = digest
    for name, (url, digest) in LICENSES.items():
        write_if_changed(ROOT / "licenses" / name, pinned(url, digest, name, cache_only).read_bytes())
    return result


def offline(raw):
    raw = raw.replace(b"https://cdnjs.cloudflare.com/ajax/libs/mathjax/2.7.1/MathJax.js?config=TeX-AMS-MML_HTMLorMML", SCRIPT.encode())
    raw = raw.replace(b"MathJax.Hub.Config({\n", b"MathJax.Hub.Config({\n                showMathMenu: false,\n")
    # Khronos puts TeX subscripts inside code elements, including GL_COLOR_ATTACHMENT$m$.
    raw = raw.replace(b"tex2jax: {\n", b'tex2jax: {\n                    skipTags: ["script","noscript","style","textarea","pre","annotation","annotation-xml"],\n')
    raw = raw.replace(b'href="css/opengl-man.css"', b'href="opengl-man.css"')
    for url, path in ((SPEC + "glspec46.core.pdf", "specs/glspec46.core.pdf"),
                      (SPEC + "GLSLangSpec.4.60.html", "specs/GLSLangSpec.4.60.pdf")):
        raw = raw.replace(url.encode(), path.encode())
    raw = raw.replace(b'http://www.opengl.org/registry/" target="_top">http://www.opengl.org/registry/',
                      b'specs/ARB_clip_control.txt">GL_ARB_clip_control (offline)')
    if CSP not in raw:
        raw = raw.replace(b"<head>", b"<head>" + CSP, 1)
    return raw


def verify_local_links(name, raw):
    for element in ET.fromstring(raw).iter():
        for attr in ("src", "href", "data", "poster"):
            value = element.get(attr)
            if not value:
                continue
            url = urlsplit(value)
            path = PurePosixPath(url.path)
            if url.scheme or url.netloc or path.is_absolute() or ".." in path.parts:
                raise ValueError(f"{name}: unmapped reference {value}")
            if element.tag.rsplit("}", 1)[-1] == "script" and value != SCRIPT:
                raise ValueError(f"{name}: unverified script {value}")


def fetch(name, refresh=False, optional=False, cache_only=False):
    path = CACHE / name
    if path.is_file() and not refresh:
        return path.read_bytes()
    if cache_only:
        if optional:
            return None
        raise FileNotFoundError(path)
    for attempt in range(4):
        try:
            request = Request(BASE + ("" if name == "directory.html" else name),
                              headers={"User-Agent": "GLSL-Support-reference-sync/1.0"})
            with urlopen(request, timeout=35) as response:
                data = response.read()
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            return data
        except HTTPError as error:
            if optional and error.code == 404:
                return None
            if attempt == 3 or error.code not in (429, 500, 502, 503, 504):
                raise
        except (URLError, TimeoutError):
            if attempt == 3:
                raise
        time.sleep(1 + attempt)


class Index(HTMLParser):
    def __init__(self):
        super().__init__()
        self.names = {}
        self.link = None

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            link = dict(attrs).get("href", "")
            self.link = link[:-6] if re.fullmatch(r"\w+\.xhtml", link) else None

    def handle_data(self, text):
        if self.link and re.fullmatch(r"\w+", text.strip()):
            self.names[text.strip()] = self.link

    def handle_endtag(self, tag):
        if tag == "a":
            self.link = None


def prepare(raw):
    """Remove complete sections at byte offsets. Keep remaining XHTML, MathML and links intact."""
    root = ET.fromstring(raw)
    sections, notices, assets = [], [], set()
    tags = []
    parser = expat.ParserCreate()
    heading = []

    def start(tag, attrs):
        tags.append([tag, parser.CurrentByteIndex, attrs, ""])
        if tag == "h2":
            heading.clear()

    def data(text):
        if any(item[0] == "h2" for item in tags):
            heading.append(text)

    def end(tag):
        node = tags.pop()
        if tag == "h2":
            title = " ".join("".join(heading).lower().split())
            if title in ("version support", "copyright", "copyright and license"):
                for parent in reversed(tags):
                    if parent[0] in ("div", "section"):
                        parent[3] = title
                        break
        identifier = node[2].get("id", "").lower()
        section = node[3] or (identifier if identifier in ("versions", "copyright") else "")
        if section and tag in ("div", "section"):
            last = raw.index(b">", parser.CurrentByteIndex) + 1
            sections.append((node[1], last))
            if "copyright" in section:
                part = ET.fromstring(raw[node[1]:last])
                body = " ".join("".join("".join(e.itertext()) for e in part if e.tag.rsplit("}", 1)[-1] != "h2").split())
                urls = sorted({e.get("href") for e in part.iter() if e.get("href")})
                notices.append(body + "\n" + "\n".join(urls))

    parser.StartElementHandler, parser.EndElementHandler = start, end
    parser.CharacterDataHandler = data
    parser.Parse(raw, True)
    for element in root.iter():
        tag = element.tag.rsplit("}", 1)[-1]
        resource = element.get("src") if tag == "img" else element.get("href") if tag == "link" else None
        if resource and not urlsplit(resource).scheme:
            path = PurePosixPath(resource)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unexpected relative asset " + resource)
            assets.add(path.as_posix())
    for first, last in sorted(sections, reverse=True):
        raw = raw[:first] + raw[last:]
    return raw, notices, assets


def main():
    options = ArgumentParser(description=__doc__)
    source = options.add_mutually_exclusive_group()
    source.add_argument("--refresh", action="store_true", help="replace cached upstream pages")
    source.add_argument("--offline", action="store_true", help="rebuild from the previous download cache")
    args = options.parse_args()
    index = Index()
    index.feed(fetch("directory.html", args.refresh, cache_only=args.offline).decode("utf-8"))
    if "glPolygonOffsetClamp" not in index.names:
        raise RuntimeError("The reference index did not contain the expected GL 4.6 pages")
    canonical = set(index.names.values())
    extra = set((ROOT / "test-data/reference/gl46-registry.txt").read_text(encoding="utf-8").splitlines())
    for line in (ROOT / "language-core/src/main/resources/glsl/core46.tsv").read_text(encoding="utf-8").splitlines():
        extra.add(line.split("\t")[1])
    extra.add("GL_GEOMETRY_SHADER_INVOCATIONS")
    snapshot, attribution, resources, pages = {}, defaultdict(list), set(), {}

    def download(name):
        raw = fetch(name + ".xhtml", args.refresh, name not in canonical, args.offline)
        if raw is None:
            return None
        content, notice, assets = prepare(raw)
        content = offline(content)
        source = f'<meta name="dcterms.source" content="{BASE}{name}.xhtml"/>'.encode()
        content = content.replace(b"</head>", source + b"</head>", 1)
        credit = (b'<footer><p><small>Khronos reference, offline edition by GLSL Support, '
                  b'2026-11-22. <a href="sources.txt">Source and edition notes</a>.</small></p></footer>')
        content = content.replace(b"<footer/>", credit)
        return name, raw, content, notice, assets

    with ThreadPoolExecutor(max_workers=6) as workers:
        for page in workers.map(download, sorted(canonical | extra)):
            if page is None:
                continue
            name, raw, content, notice, assets = page
            filename = name + ".xhtml"
            pages[filename] = content
            snapshot[filename] = (sha256(raw).hexdigest(), sha256(content).hexdigest())
            resources.update(assets)
            index.names.setdefault(name, name)
            for text in notice:
                attribution[text].append(filename)

    for name in sorted(resources):
        raw = fetch(name, args.refresh, cache_only=args.offline)
        pages[name] = raw
        snapshot[name] = (sha256(raw).hexdigest(), sha256(raw).hexdigest())
    for name, content in pages.items():
        if name.endswith(".xhtml"):
            verify_local_links(name, content)
        write_if_changed(PAGES / name, content)
    for name in (extra | {"OpenGL46"}) - set(index.names):
        path = PAGES / (name + ".xhtml")
        content = offline(path.read_bytes())
        if name != "OpenGL46":
            credit = b'<p>GLSL Support supplement based on the <a href="specs/'
            content = content.replace(b'<p><a href="specs/', credit)
        verify_local_links(name, content)
        write_if_changed(path, content)
    runtime = bundle_runtime(args.offline)
    records = ["\t".join((name, digest)) for name, digest in sorted(runtime.items())]
    write_if_changed(ROOT / "test-data/reference/offline-assets.tsv", ("\n".join(records) + "\n").encode())
    names = set(index.names) | extra | {"OpenGL46"}
    (PAGES / "index.txt").write_bytes(("\n".join(sorted(names)) + "\n").encode())
    aliases = [name + "\t" + target for name, target in sorted(index.names.items()) if name != target]
    (PAGES / "aliases.tsv").write_bytes(("\n".join(aliases) + "\n").encode())
    manifest = ["\t".join((name, *hashes)) for name, hashes in sorted(snapshot.items())]
    (ROOT / "test-data/reference/khronos-snapshot.tsv").write_bytes(("\n".join(manifest) + "\n").encode())
    notices = ["Khronos OpenGL 4.x Reference Pages", BASE,
               "The offline edition omits the Version Support and Copyright sections from the document bodies.",
               "Offline edition prepared by GLSL Support on 2026-11-22. Links and math rendering were adapted for offline use.",
               "The notices below identify the source pages to which each upstream notice applies.",
               "License texts accompany this notice in LicenseRef-FreeB.txt and OPUBL-1.0.txt."]
    for text, filenames in sorted(attribution.items()):
        notices.extend(["", text, "Pages: " + ", ".join(sorted(filenames))])
    notice = ("\n".join(notices) + "\n").encode()
    write_if_changed(ROOT / "licenses/opengl-reference-pages.txt", notice)
    write_if_changed(PAGES / "sources.txt", notice)
    print(f"{len(canonical)} indexed official pages, {len(aliases)} aliases, {len(names)} searchable names.")
    print("Published 4.6 entries: " + ", ".join(sorted(extra.intersection(index.names))))


if __name__ == "__main__":
    main()
