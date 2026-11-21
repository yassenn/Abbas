#!/usr/bin/env python3
"""
Export a Kotlin `listOf(ModelConfig(...), ...)` catalogue to JSON for over-the-air
updates. Reusable: point it at any Kotlin file + list name.

    python3 export_catalog.py <ModelConfig.kt> <out.json> [--list builtInModels] [--version N]

Version defaults to YYYYMMDDHH (UTC) so newer exports always sort higher; the app
refuses any catalogue whose version isn't newer than what it already has.
"""
import argparse, json, re, datetime
from pathlib import Path

FIELDS_STR = ["id", "name", "ggufFile", "baseUrl", "tier", "note", "sha256"]
FIELDS_INT = ["estimatedRamBytes", "contextSize"]

def parse(text, list_name):
    start = text.index(f"val {list_name} = listOf(")
    depth, i = 0, text.index("(", start)
    for j in range(i, len(text)):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                body = text[i + 1:j]
                break
    models = []
    for block in re.findall(r"ModelConfig\((.*?)\n\s*\)", body, re.S):
        m = {}
        for f in FIELDS_STR:
            v = re.search(rf'\b{f}\s*=\s*"((?:[^"\\]|\\.)*)"', block)
            if v:
                m[f] = v.group(1)
        for f in FIELDS_INT:
            v = re.search(rf"\b{f}\s*=\s*([0-9_]+)L?", block)
            if v:
                m[f] = int(v.group(1).replace("_", ""))
        s = re.search(r"strengths\s*=\s*listOf\(([^)]*)\)", block)
        if s:
            m["strengths"] = re.findall(r'"([^"]+)"', s.group(1))
        models.append(m)
    return models

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kotlin")
    ap.add_argument("out")
    ap.add_argument("--list", default="builtInModels")
    ap.add_argument("--version", type=int)
    a = ap.parse_args()
    models = parse(Path(a.kotlin).read_text(), a.list)
    missing = [m.get("id") for m in models if not m.get("sha256")]
    if missing:
        raise SystemExit(f"refusing to export: no sha256 for {missing}")
    version = a.version or int(datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H"))
    doc = {"version": version, "models": models}
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")
    print(f"exported {len(models)} models, version {version} -> {a.out}")

if __name__ == "__main__":
    main()
