"""Check the user-installable ZIP, including its nested plugin JAR."""
import io
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
package = Path(sys.argv[1])
with zipfile.ZipFile(package) as outer:
    names = {n.replace("\\", "/"): n for n in outer.namelist()}
    assert all(n.startswith("GLSLSupport/") for n in names), "Unexpected distribution root"
    assert all(n.startswith("GLSLSupport/licenses/") or n in {
        "GLSLSupport/LICENSE", "GLSLSupport/lib/glsl-support.jar",
        "GLSLSupport/lib/native/win-x64/glsl_support.dll"} for n in names), "Unexpected distribution entry"
    assert outer.read(names["GLSLSupport/LICENSE"]) == (root / "LICENSE").read_bytes()
    for file in (root / "licenses").glob("*.txt"):
        assert outer.read(names["GLSLSupport/licenses/" + file.name]) == file.read_bytes()
    dll = outer.read(names["GLSLSupport/lib/native/win-x64/glsl_support.dll"])
    pe = int.from_bytes(dll[60:64], "little")
    assert dll[:2] == b"MZ" and dll[pe:pe + 6] == b"PE\0\0\x64\x86", "Expected Windows x64 DLL"
    jar = zipfile.ZipFile(io.BytesIO(outer.read(names["GLSLSupport/lib/glsl-support.jar"])))
    entries = set(jar.namelist())
    plugin = ET.fromstring(jar.read("META-INF/plugin.xml"))
    assert plugin.findtext("id") == "com.glslsupport.language"
    assert plugin.findtext("name") == "GLSL Support (OpenGL/Vulkan)"
    assert plugin.find("vendor").get("email") and plugin.find("vendor").get("url", "").startswith("https://")
    assert plugin.get("url") == "https://github.com/Taimoor-Joyyah/glsl-support-jetbrains"
    assert plugin.find("idea-version").get("since-build") == "261"
    assert {d.get("id") for d in plugin.findall("dependencies/plugin")} >= {"com.intellij.modules.os.windows", "com.intellij.modules.arch.x86_64"}
    icon = ET.fromstring(jar.read("META-INF/pluginIcon.svg"))
    assert icon.get("width") == icon.get("height") == "40"
    manifest = [line.split("\t") for line in jar.read("reference/files.tsv").decode().splitlines()]
    expected = {"reference/" + row[0] for row in manifest if row[1] in {"bundled","math"}}
    assert {n for n in entries if n.startswith("reference/") and not n.endswith("/") and n != "reference/files.tsv"} == expected
    assert sum(n.endswith(".xhtml") for n in entries) == 45
    assert sum(row[1] == "upstream" for row in manifest) == 499
    for file in ("com/glslsupport/reference/ReferenceLibrary.class", "com/glslsupport/ide/ReferenceAccess.class"):
        assert b"Taimoor-Joyyah" not in jar.read(file) and b"glsl.reference.repository" not in jar.read(file), "Personal repository lookup present"
    assert not any(n.endswith((".pdf", ".zip")) or n.startswith("com/glslsupport/tests/") for n in entries)
print(f"Verified marketplace metadata, Windows x64 native code, licenses and 45 bundled supplements in {package.name} ({package.stat().st_size:,} bytes).")
