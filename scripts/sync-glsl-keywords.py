"""Fill gaps in the editor catalog from the scanner and type grammar of the pinned glslang build."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
src = root / "build/compiler/_deps/glslang_source-src/glslang/MachineIndependent"
data = root / "language-core/src/main/resources/glsl"
grammar = (src / "glslang.y").read_text(encoding="utf-8")
types = grammar.split("\ntype_specifier_nonarray\n", 1)[1].split("\n    ;", 1)[0]
type_tokens = set(re.findall(r"^\s*[:|] ([A-Z][A-Z0-9_]*)\b", types, re.MULTILINE))
scanner = (src / "Scan.cpp").read_text(encoding="utf-8").split("KeywordMap {", 1)[1].split("};", 1)[0]
known = set()
for name in ("catalog.tsv", "core46.tsv"):
    for line in (data / name).read_text(encoding="utf-8").splitlines():
        kind, spelling, *_ = line.split("\t")
        known.add((kind, spelling))
rows = []
for spelling, token in re.findall(r'\{"(\w+)",\s*(\w+)\}', scanner):
    kind = "types" if token in type_tokens else "keywords"
    if (kind, spelling) not in known:
        rows.append(f"{kind}\t{spelling}")
(data / "compiler-keywords.tsv").write_text("\n".join(sorted(set(rows))) + "\n", encoding="utf-8", newline="\n")
