"""Prepare the reference catalog, offline equation renderer and bundled supplements."""
import argparse
import hashlib
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
PAGES = ROOT / "intellij-plugin/src/main/resources/reference_pages"
def digest(path):
    with path.open("rb") as src:
        return hashlib.file_digest(src, "sha256").hexdigest()


def used_by_viewer(path):
    name = path.relative_to(PAGES).as_posix()
    if not name.startswith("mathjax/"):
        return True
    if name.startswith("mathjax/jax/output/SVG/fonts/"):
        return name.startswith("mathjax/jax/output/SVG/fonts/TeX/")
    return (name in {"mathjax/MathJax.js", "mathjax/config/TeX-AMS-MML_SVG.js"} or
            name.startswith(("mathjax/extensions/", "mathjax/jax/input/", "mathjax/jax/element/",
                             "mathjax/jax/output/SVG/", "mathjax/localization/")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    args = parser.parse_args()
    target = args.catalog / "reference"
    target.mkdir(parents=True, exist_ok=True)
    snapshot = {name: (original, formatted) for name, original, formatted in
                (row.split("\t") for row in (ROOT / "test-data/reference/khronos-snapshot.tsv").read_text().splitlines())}
    official = sorted(PAGES / name for name in snapshot if name.endswith(".xhtml"))
    files = sorted(p for p in PAGES.rglob("*") if p.is_file() and used_by_viewer(p))
    math = [p for p in files if p.is_relative_to(PAGES / "mathjax")]
    catalog = []
    for path in files:
        name = path.relative_to(PAGES).as_posix()
        sha = digest(path)
        if name in snapshot and sha != snapshot[name][1]:
            raise ValueError("Reference differs from its recorded edition: " + name)
        kind = ("upstream" if path in official else "math" if name.startswith("mathjax/")
                else "spec" if name.startswith("specs/") else "bundled")
        if kind in ("bundled", "math"):
            output = target / name
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, output)
        catalog.append("\t".join([name, kind, str(path.stat().st_size), sha,
                                 snapshot[name][0] if kind == "upstream" else "-"]))
    (target / "files.tsv").write_bytes(("\n".join(catalog) + "\n").encode())
    print(f"{len(official)} Khronos pages, {len(list(PAGES.glob('*.xhtml')))-len(official)} bundled supplements, "
          f"{len(math)} shared MathJax resources")


if __name__ == "__main__":
    main()
