#!/usr/bin/env bash
# Reusable on-device security checks for an Android app (needs adb + a connected
# phone; data-at-rest checks need a debuggable build for run-as).
#
#   tools/security/android_device_audit.sh <package> [report.md] [sensitive-regex]
#
# Checks: data at rest (SQLite/plaintext files, prefs encryption), backup flags,
# exported components fuzzing (crash / unexpected side effects), logcat leaks,
# FLAG_SECURE on visible windows, granted dangerous permissions.
set -uo pipefail
PKG="${1:?package name}"; OUT="${2:-device-audit.md}"
SENSITIVE="${3:-password|passphrase|token|secret|otp|BEGIN PRIVATE|sk_live|@gmail\.com|IMAP|finalContext}"
ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")}"

pass=0; fail=0; warn=0
r() { echo "$*" >> "$OUT"; }
ok()   { pass=$((pass+1)); r "- ✅ $*"; }
bad()  { fail=$((fail+1)); r "- ❌ $*"; }
note() { warn=$((warn+1)); r "- ⚠️ $*"; }

: > "$OUT"
r "# Device security audit — $PKG"; r ""; r "Date: $(date -u +%FT%TZ)"
if ! "$ADB" get-state >/dev/null 2>&1; then
  r ""; r "No device connected. Plug in the phone, unlock it and accept USB debugging, then re-run."
  cat "$OUT"; exit 3
fi
r "Device: $("$ADB" shell getprop ro.product.model | tr -d '\r') · Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
"$ADB" shell pm path "$PKG" >/dev/null 2>&1 || { r "App not installed."; cat "$OUT"; exit 4; }
DUMP="$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
r "Version: $(echo "$DUMP" | sed -n 's/.*versionName=//p' | head -1)"; r ""

r "## Data at rest"
if "$ADB" shell run-as "$PKG" true 2>/dev/null; then
  files="$("$ADB" shell run-as "$PKG" find . -type f 2>/dev/null | tr -d '\r')"
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    head="$("$ADB" shell run-as "$PKG" head -c 16 "$f" 2>/dev/null | tr -d '\0\r')"
    case "$f" in
      *-wal|*-shm|*-journal) [[ "$head" == "SQLite format 3"* ]] && bad "plaintext SQLite journal: $f" ;;
      *.db|*/databases/*) if [[ "$head" == "SQLite format 3"* ]]; then
          case "$f" in *androidx.work*|*com.google.android.datatransport*) note "plaintext library DB (no user content expected): $f" ;; *) bad "plaintext SQLite database: $f" ;; esac
        else ok "encrypted database: $f"; fi ;;
      *shared_prefs/*.xml)
        if "$ADB" shell run-as "$PKG" grep -qE "__androidx_security_crypto_encrypted_prefs" "$f" 2>/dev/null; then ok "encrypted preferences: $f"
        elif "$ADB" shell run-as "$PKG" grep -qiE "$SENSITIVE" "$f" 2>/dev/null; then bad "sensitive-looking value in plain preferences: $f"
        else note "plain preferences (check contents): $f"; fi ;;
      *.gguf|*.verified|*/code_cache/*|*/cache/*) ;;
      *) if "$ADB" shell run-as "$PKG" grep -qiE "$SENSITIVE" "$f" 2>/dev/null; then bad "sensitive-looking text in plain file: $f"; fi ;;
    esac
  done <<< "$files"
else
  note "run-as not available (release build): data-at-rest checks skipped; install a debug build to run them"
fi
ext="$("$ADB" shell ls "/sdcard/Android/data/$PKG/files" 2>/dev/null | tr -d '\r')"
[[ -n "$ext" ]] && note "external app files (readable with adb / other root tools): $(echo $ext | tr '\n' ' ')"

r ""; r "## Backup / transfer"
if echo "$DUMP" | grep -q "ALLOW_BACKUP"; then bad "ALLOW_BACKUP flag set"; else ok "backup disabled"; fi

r ""; r "## Exported components (fuzz)"
pidof_app() { "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
"$ADB" logcat -c
for comp in $(echo "$DUMP" | grep -oE "$PKG/[A-Za-z0-9_.$]+" | sort -u); do
  for intent in \
    "-a android.intent.action.SEND -t text/plain --eu android.intent.extra.STREAM file:///etc/hosts" \
    "-a android.intent.action.SEND_MULTIPLE -t */* --es android.intent.extra.TEXT '<script>x</script>'" \
    "-a android.intent.action.VIEW -d ai.alal.app://paypalpay?token=../../x" \
    "--es x $(printf 'A%.0s' {1..2000})"; do
    "$ADB" shell am start -W -n "$comp" $intent >/dev/null 2>&1
    sleep 1
  done
done
crashes="$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "$PKG" || true)"
[[ "$crashes" == "0" ]] && ok "no crashes from malformed intents to exported components" || bad "$crashes crash(es) from malformed intents (see: adb logcat -b crash)"
"$ADB" shell am force-stop "$PKG"

r ""; r "## Logcat leakage"
"$ADB" logcat -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
pid="$(pidof_app)"
if [[ -n "$pid" ]]; then
  leaks="$("$ADB" logcat -d --pid="$pid" 2>/dev/null | grep -iE "$SENSITIVE" | head -5)"
  [[ -z "$leaks" ]] && ok "no sensitive strings in app logs during start-up" || { bad "sensitive strings in logcat:"; r '```'; r "$leaks"; r '```'; }
fi

r ""; r "## Screen capture"
win="$("$ADB" shell dumpsys window windows 2>/dev/null | tr -d '\r' | grep -A3 "$PKG" | grep -m1 -oE "fl=[^ ]+|flags=[^ ]+")"
if echo "$win" | grep -q "SECURE"; then ok "FLAG_SECURE on the app window"; else note "FLAG_SECURE not detected on the visible window ($win)"; fi

r ""; r "## Granted runtime permissions"
echo "$DUMP" | grep -E "android.permission.[A-Z_]+: granted=true" | sed -E 's/^ +/- /' | sort -u >> "$OUT"

r ""; r "**Summary:** $pass passed · $fail failed · $warn to review"
cat "$OUT"
[[ "$fail" == 0 ]]
