"""Rebuild the offline catalog from the glsl_analyzer specification database."""
import json
import pathlib
import sys

source, destination = map(pathlib.Path, sys.argv[1:])
data = json.loads(source.read_text(encoding="utf-8"))
rows = set()
for kind in ("keywords", "types", "variables", "functions", "extensions"):
    for entry in data[kind]:
        if isinstance(entry, str):
            entry = {"name": entry}
        params = ", ".join(" ".join(filter(None, (p.get("modifiers"), p.get("type"), p.get("name")))) for p in entry.get("parameters", ()))
        description = " ".join(entry.get("description") or ()).replace("\t", " ").replace("\n", " ")
        rows.add("\t".join((kind,entry["name"],entry.get("return_type", entry.get("type","")),params,",".join(entry.get("extensions",())),description)))
destination.parent.mkdir(parents=True,exist_ok=True)
destination.write_text("\n".join(sorted(rows))+"\n",encoding="utf-8")
