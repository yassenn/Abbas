#!/usr/bin/env python3
"""
Reusable model-link verifier for apps that download GGUF (or any) files from
Hugging Face. Stdlib only.

    python3 verify_models.py <catalog.kt|catalog.json> [--out results.json] [--timeout 20]

Reads entries with a base URL + file name (Kotlin `ModelConfig(... ggufFile = "...",
baseUrl = "https://huggingface.co/<repo>/resolve/main/" ...)` blocks, or a JSON list
of {"id", "ggufFile", "baseUrl"}), then for each:
  - asks the HF API for the file's size and LFS sha256 (no full download),
  - HEADs the resolve URL to confirm it is reachable (follows redirects),
  - reports gated / missing / renamed files with close-match suggestions.

Exit 0 when every entry resolves, 1 otherwise. Output JSON is ready to paste
sha256/size into the catalogue.
"""
import argparse, json, re, sys, urllib.request, urllib.error
from pathlib import Path

UA = {"User-Agent": "model-verifier/1.0"}

def load(path: Path):
    text = path.read_text()
    if path.suffix == ".json":
        return json.loads(text)
    out = []
    for block in re.findall(r'ModelConfig\((.*?)\n\s*\)', text, re.S):
        get = lambda k: (re.search(rf'{k}\s*=\s*"([^"]+)"', block) or [None, None])[1]
        if get("id") and get("ggufFile") and get("baseUrl"):
            out.append({"id": get("id"), "ggufFile": get("ggufFile"), "baseUrl": get("baseUrl")})
    return out

def http_json(url, timeout):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return json.loads(r.read().decode())

def head(url, timeout):
    req = urllib.request.Request(url, method="HEAD", headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code

def check(entry, timeout):
    m = re.match(r'https://huggingface\.co/([^/]+/[^/]+)/resolve/([^/]+)/(.*)$', entry["baseUrl"].rstrip("/") + "/")
    res = {"id": entry["id"], "file": entry["ggufFile"], "ok": False}
    if not m:
        res["error"] = "not a huggingface resolve URL"
        return res
    repo, rev, sub = m.group(1), m.group(2), m.group(3)
    res["repo"] = repo
    path = (sub + entry["ggufFile"]).lstrip("/")
    try:
        tree = http_json(f"https://huggingface.co/api/models/{repo}/tree/{rev}/{sub}".rstrip("/"), timeout)
    except urllib.error.HTTPError as e:
        res["error"] = {401: "gated: needs a token/licence", 403: "gated: needs a token/licence", 404: "repo not found"}.get(e.code, f"HTTP {e.code}")
        return res
    except Exception as e:
        res["error"] = f"network: {e}"
        return res
    files = {f["path"]: f for f in tree if f.get("type") == "file"}
    f = files.get(path)
    if not f:
        ggufs = [p for p in files if p.lower().endswith(".gguf")]
        want = entry["ggufFile"].lower()
        close = [p for p in ggufs if "q4_k_m" in p.lower()] or ggufs
        res["error"] = "file not in repo"
        res["suggestions"] = sorted(close, key=lambda p: -len(set(p.lower()) & set(want)))[:5]
        return res
    lfs = f.get("lfs") or {}
    res["size"] = lfs.get("size") or f.get("size")
    res["sha256"] = lfs.get("oid")
    status = head(entry["baseUrl"].rstrip("/") + "/" + entry["ggufFile"], timeout)
    res["http"] = status
    res["ok"] = status in (200, 302) and bool(res["sha256"])
    if not res["ok"]:
        res["error"] = f"download URL returned HTTP {status}"
    return res

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("catalog")
    ap.add_argument("--out")
    ap.add_argument("--timeout", type=int, default=20)
    a = ap.parse_args()
    entries = load(Path(a.catalog))
    results = [check(e, a.timeout) for e in entries]
    for r in results:
        mark = "OK  " if r["ok"] else "FAIL"
        extra = f'{r.get("size", 0) / 1e9:.2f} GB sha256={r.get("sha256", "")[:12]}…' if r["ok"] else r.get("error", "") + (f' → try {r["suggestions"]}' if r.get("suggestions") else "")
        print(f"{mark} {r['id']}: {extra}")
    if a.out:
        Path(a.out).write_text(json.dumps(results, indent=2))
    bad = [r for r in results if not r["ok"]]
    print(f"\n{len(results) - len(bad)}/{len(results)} verified")
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main()
