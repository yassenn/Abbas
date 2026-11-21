#!/usr/bin/env python3
"""
Reusable static security audit for Android projects (stdlib only, no installs).

    python3 android_static_audit.py <repo-root> [--module app] [--out report.md]
                                    [--fail-on high|medium|low|none]

Checks the manifest (merged one if a build exists), resource configs and source
(Kotlin/Java) for common mobile weaknesses: exported components, backup and
device-transfer exposure, cleartext traffic, debuggable builds, hard-coded
secrets, insecure TLS/crypto/random, logging of sensitive data, world-readable
files, WebView bridges, SQL concatenation, and risky permissions.

Exit code: 0 when no finding at or above --fail-on, else 1. Designed to be
re-run by people or agents after each change (cheap, deterministic, no network).
"""
import argparse, os, re, sys, xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
SEV_ORDER = {"high": 3, "medium": 2, "low": 1, "info": 0}

findings = []

def add(sev, rule, where, detail):
    findings.append((sev, rule, where, detail))

def attr(el, name):
    return el.get(ANDROID + name)

# ── Manifest ─────────────────────────────────────────────────────────────

def find_manifest(root: Path, module: str):
    build = root / module / "build" / "intermediates"
    merged = sorted(build.glob("merged_manifest*/**/AndroidManifest.xml"), key=lambda p: p.stat().st_mtime, reverse=True) if build.exists() else []
    src = root / module / "src" / "main" / "AndroidManifest.xml"
    return (merged[0], True) if merged else (src, False)

DANGEROUS_PERMS = {
    "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CONTACTS", "WRITE_CONTACTS",
    "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "RECORD_AUDIO", "CAMERA", "READ_CALENDAR", "WRITE_CALENDAR",
    "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "PACKAGE_USAGE_STATS", "SYSTEM_ALERT_WINDOW",
    "REQUEST_INSTALL_PACKAGES", "QUERY_ALL_PACKAGES", "READ_PHONE_STATE", "BODY_SENSORS",
}

def audit_manifest(path: Path, merged: bool, root: Path, module: str):
    tree = ET.parse(path)
    m = tree.getroot()
    rel = str(path.relative_to(root)) if path.is_relative_to(root) else str(path)
    app = m.find("application")
    if app is None:
        add("info", "manifest", rel, "no <application> element")
        return
    pkg = m.get("package", "")

    if attr(app, "debuggable") == "true":
        add("high" if not merged or "release" in rel else "info", "debuggable", rel,
            "android:debuggable=true (expected only in debug builds)")
    if attr(app, "allowBackup") != "false":
        add("high", "backup", rel, "allowBackup is not false: app data can be extracted with adb backup / cloud backup")
    if attr(app, "dataExtractionRules") is None:
        add("medium", "device-transfer", rel,
            "no dataExtractionRules: on Android 12+ allowBackup=false does NOT stop device-to-device transfer of app data")
    if attr(app, "usesCleartextTraffic") == "true":
        add("high", "cleartext", rel, "usesCleartextTraffic=true")
    nsc = attr(app, "networkSecurityConfig")
    if nsc is None:
        add("low", "network-config", rel, "no networkSecurityConfig: relies on platform defaults (no explicit cleartext ban / CA policy)")
    else:
        audit_nsc(root / module / "src" / "main" / "res" / "xml" / (nsc.split("/")[-1] + ".xml"), root)

    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.iter(tag):
            name = attr(c, "name") or "?"
            exported = attr(c, "exported")
            has_filter = c.find("intent-filter") is not None
            perm = attr(c, "permission") or (attr(c, "readPermission") if tag == "provider" else None)
            is_exported = exported == "true" or (exported is None and has_filter)
            if not is_exported:
                continue
            actions = [attr(a, "name") for f in c.findall("intent-filter") for a in f.findall("action")]
            launcher = "android.intent.action.MAIN" in actions
            if perm:
                lvl = "info"
                detail = f"exported {tag}, protected by {perm}"
            elif launcher:
                lvl, detail = "info", f"exported launcher {tag}"
            else:
                lvl = "medium" if tag in ("provider", "service", "receiver") else "low"
                detail = f"exported {tag} without permission; actions={actions or 'explicit only'}: validate every input, require user confirmation for side effects"
            add(lvl, "exported-component", rel, f"{name}: {detail}")
            for f in c.findall("intent-filter"):
                for d in f.findall("data"):
                    scheme = attr(d, "scheme")
                    if scheme and scheme not in ("http", "https", "content", "file"):
                        add("low", "custom-scheme", rel, f"{name}: custom URI scheme '{scheme}' can be invoked by any app or web page")

    for p in m.iter("uses-permission"):
        n = (attr(p, "name") or "").split(".")[-1]
        if n in DANGEROUS_PERMS:
            add("info", "sensitive-permission", rel, f"{n}: make sure it is requested only when the feature is turned on, and covered by the privacy policy")

def audit_nsc(path: Path, root: Path):
    if not path.exists():
        add("low", "network-config", str(path), "networkSecurityConfig referenced but file not found")
        return
    t = path.read_text(errors="ignore")
    rel = str(path.relative_to(root))
    if re.search(r'cleartextTrafficPermitted\s*=\s*"true"', t):
        add("high", "cleartext", rel, "cleartextTrafficPermitted=true")
    if re.search(r'<certificates[^>]*src\s*=\s*"user"', t) and "debug-overrides" not in t:
        add("medium", "user-ca", rel, "trusts user-installed CAs: enables TLS interception")

# ── Source ───────────────────────────────────────────────────────────────

SOURCE_RULES = [
    ("high", "hardcoded-secret", re.compile(r'(?i)(api[_-]?key|secret|passwd|password|token|private[_-]?key)\s*[:=]\s*"[A-Za-z0-9_\-+/=]{16,}"'),
     "possible hard-coded credential"),
    ("high", "hardcoded-secret", re.compile(r'-----BEGIN (RSA |EC )?PRIVATE KEY-----|sk_live_[0-9a-zA-Z]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\-]{30,}|ghp_[0-9A-Za-z]{30,}'),
     "private key or live secret pattern"),
    ("high", "tls-trust-all", re.compile(r'X509TrustManager|checkServerTrusted\s*\([^)]*\)\s*\{\s*\}|ALLOW_ALL_HOSTNAME_VERIFIER|hostnameVerifier\s*\{\s*_,\s*_\s*->\s*true'),
     "custom trust manager / hostname verifier: confirm it does not accept all certificates"),
    ("medium", "cleartext-url", re.compile(r'"http://(?!localhost|127\.0\.0\.1|schemas\.android\.com|www\.w3\.org|ns\.adobe\.com|purl\.org|xmlpull)[^"]+'),
     "plain http:// URL"),
    ("medium", "weak-crypto", re.compile(r'Cipher\.getInstance\("(AES|DES|DESede)(/ECB[^"]*)?"\)|"MD5"|"SHA-?1"|MessageDigest\.getInstance\("MD5"\)'),
     "weak cipher mode or hash"),
    ("medium", "insecure-random", re.compile(r'\bjava\.util\.Random\(|\bMath\.random\(\)|\bkotlin\.random\.Random\b'),
     "non-cryptographic random: fine for UI, never for keys/tokens/passphrases"),
    ("medium", "world-readable", re.compile(r'MODE_WORLD_(READABLE|WRITEABLE)|setReadable\(true,\s*false\)'),
     "world-readable/writable file"),
    ("medium", "webview-bridge", re.compile(r'addJavascriptInterface|setJavaScriptEnabled\(true\)|setAllowFileAccess\(true\)|setAllowUniversalAccessFromFileURLs'),
     "WebView JavaScript bridge / file access"),
    ("medium", "sql-concat", re.compile(r'(rawQuery|execSQL)\(\s*"[^"]*"\s*\+|(rawQuery|execSQL)\(\s*"[^"]*\$\{?[a-zA-Z]'),
     "SQL built by string concatenation/interpolation"),
    ("low", "external-storage-write", re.compile(r'getExternalStorageDirectory\(\)|Environment\.getExternalStoragePublicDirectory'),
     "shared external storage: anything written here is readable by other apps"),
    ("low", "clipboard", re.compile(r'setPrimaryClip|clipboardManager\.setText|LocalClipboardManager'),
     "clipboard use: other apps / keyboards may read copied content; mark sensitive clips (EXTRA_IS_SENSITIVE)"),
    ("low", "implicit-intent-data", re.compile(r'sendBroadcast\(Intent\("[^"]+"\)|sendBroadcast\(\s*intent\s*\)'),
     "broadcast without permission/package: may leak data to other apps"),
]

LOG_CALL = re.compile(r'\b(Log\.[vdiwe]|android\.util\.Log\.[vdiwe]|println|printStackTrace)\s*\(')
SENSITIVE_WORDS = re.compile(r'(?i)(password|passphrase|token|secret|otp|body|message|text|content|query|prompt|email|sms|location|latitude|context)')

def audit_sources(root: Path, module: str):
    src_root = root / module / "src" / "main"
    for path in list(src_root.rglob("*.kt")) + list(src_root.rglob("*.java")):
        rel = str(path.relative_to(root))
        lines = path.read_text(errors="ignore").splitlines()
        for i, line in enumerate(lines, 1):
            s = line.strip()
            if s.startswith("//") or s.startswith("*"):
                continue
            for sev, rule, rx, detail in SOURCE_RULES:
                if rx.search(line):
                    add(sev, rule, f"{rel}:{i}", f"{detail}: {s[:140]}")
            if LOG_CALL.search(line):
                window = " ".join(lines[max(0, i - 3):i])
                guarded = "BuildConfig.DEBUG" in window
                args = line[line.find("("):]
                if SENSITIVE_WORDS.search(args) and not guarded:
                    add("medium", "sensitive-log", f"{rel}:{i}", f"unguarded log may include user data: {s[:140]}")
                elif "printStackTrace" in line and not guarded:
                    add("low", "stacktrace-log", f"{rel}:{i}", "printStackTrace in release code")

def audit_gradle(root: Path, module: str):
    g = root / module / "build.gradle.kts"
    if not g.exists():
        g = root / module / "build.gradle"
    if not g.exists():
        return
    t = g.read_text(errors="ignore")
    rel = str(g.relative_to(root))
    rel_block = re.search(r'release\s*\{(.*?)\n\s*\}', t, re.S)
    if rel_block and "isMinifyEnabled = true" not in rel_block.group(1) and "minifyEnabled true" not in rel_block.group(1):
        add("low", "no-minify", rel, "release build not minified/obfuscated")
    for m in re.finditer(r'"([a-z0-9_.\-]+):([a-z0-9_.\-]+):([0-9][^"]*(alpha|beta|rc)[^"]*)"', t, re.I):
        add("low", "prerelease-dependency", rel, f"{m.group(1)}:{m.group(2)}:{m.group(3)}")
    if re.search(r'security-crypto', t):
        add("info", "deprecated-crypto-lib", rel, "androidx.security:security-crypto is deprecated upstream; plan a move to Keystore + Tink/Jetpack DataStore")

# ── Report ───────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--module", default="app")
    ap.add_argument("--out")
    ap.add_argument("--fail-on", default="high", choices=["high", "medium", "low", "none"])
    a = ap.parse_args()
    root = Path(a.root).resolve()
    manifest, merged = find_manifest(root, a.module)
    audit_manifest(manifest, merged, root, a.module)
    audit_sources(root, a.module)
    audit_gradle(root, a.module)

    findings.sort(key=lambda f: (-SEV_ORDER[f[0]], f[1], f[2]))
    counts = {s: sum(1 for f in findings if f[0] == s) for s in SEV_ORDER}
    out = [f"# Static security audit — {root.name}", "",
           f"Manifest: `{manifest.relative_to(root) if manifest.is_relative_to(root) else manifest}` ({'merged' if merged else 'source'})", "",
           "| Severity | Count |", "|---|---|"] + [f"| {s} | {counts[s]} |" for s in SEV_ORDER] + [""]
    for sev in SEV_ORDER:
        rows = [f for f in findings if f[0] == sev]
        if not rows:
            continue
        out.append(f"## {sev.upper()}")
        for _, rule, where, detail in rows:
            out.append(f"- **{rule}** `{where}` — {detail}")
        out.append("")
    text = "\n".join(out)
    if a.out:
        Path(a.out).write_text(text)
    print(text)
    if a.fail_on != "none" and any(SEV_ORDER[f[0]] >= SEV_ORDER[a.fail_on] for f in findings):
        sys.exit(1)

if __name__ == "__main__":
    main()
