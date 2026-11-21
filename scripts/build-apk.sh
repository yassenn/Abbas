#!/usr/bin/env bash
# Build, test and package the Alal debug APK, recovering from common failures.
#
# Usage: scripts/build-apk.sh [--release] [--skip-tests] [--allow-test-failures]
#                             [--no-install] [--clean] [--split-mb N]
#
# Stages: preflight → compile → unit tests → assemble → package → install.
# Every run writes dist/build-report.txt (status per stage + compiler errors),
# so a person or an agent can see exactly what failed without re-running.
#
# Exit codes: 0 ok · 10 preflight · 20 compile · 30 tests · 40 assemble · 50 package
#             (install problems never fail the build; they are reported).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VARIANT="Debug"; RUN_TESTS=1; ALLOW_TEST_FAIL=0; INSTALL=1; CLEAN=0; SPLIT_MB=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) VARIANT="Release" ;;
    --skip-tests) RUN_TESTS=0 ;;
    --allow-test-failures) ALLOW_TEST_FAIL=1 ;;
    --no-install) INSTALL=0 ;;
    --clean) CLEAN=1 ;;
    --split-mb) SPLIT_MB="$2"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
variant_lc="$(echo "$VARIANT" | tr '[:upper:]' '[:lower:]')"

DIST="$ROOT/dist"; LOGS="$DIST/logs"; REPORT="$DIST/build-report.txt"
mkdir -p "$LOGS"
: > "$REPORT"

log()    { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$REPORT"; }
stage()  { log "== $* =="; }
fail()   { local code="$1"; shift; log "FAILED: $*"; log "Report: $REPORT"; exit "$code"; }

# Run a Gradle task, saving its full log. Returns Gradle's exit code.
gradle() {
  local name="$1"; shift
  ./gradlew "$@" --console=plain >"$LOGS/$name.log" 2>&1
}

# Pull compiler errors out of a log into the report.
report_errors() {
  local file="$1"
  if grep -qE '^e: ' "$file"; then
    log "Compiler errors:"
    grep -E '^e: ' "$file" | sed 's#file://'"$ROOT"'/##' | head -50 | tee -a "$REPORT"
  else
    log "Last lines of $(basename "$file"):"
    tail -25 "$file" | tee -a "$REPORT"
  fi
}

# Known, safe automatic recoveries. Sets RECOVERY to the action taken; returns 1 if none applies.
# Runs in the current shell (not $(...)) so EXTRA_ARGS / GRADLE_OPTS changes stick.
recover() {
  local file="$1"
  if grep -qE 'OutOfMemoryError|Java heap space|Metaspace|Gradle build daemon disappeared|daemon.*crash' "$file"; then
    RECOVERY="memory: stopping daemons and retrying with fewer workers"
    ./gradlew --stop >/dev/null 2>&1
    export GRADLE_OPTS="-Xmx4g -Dorg.gradle.workers.max=2 ${GRADLE_OPTS:-}"
    EXTRA_ARGS+=(--max-workers=2 -Dkotlin.daemon.jvm.options=-Xmx3g)
    return 0
  fi
  if grep -qE 'Could not resolve|Could not GET|Read timed out|Connection reset|Could not download' "$file"; then
    RECOVERY="network/dependency: retrying with --refresh-dependencies"
    sleep 5
    EXTRA_ARGS+=(--refresh-dependencies)
    return 0
  fi
  if grep -qE 'Timeout waiting to lock|lock.*held by|\.lock.*(exists|File exists)' "$file"; then
    RECOVERY="stale lock: stopping daemons and retrying"
    ./gradlew --stop >/dev/null 2>&1
    sleep 3
    return 0
  fi
  if grep -qE 'Could not read workspace metadata|Corrupted|IncrementalCompilation|Unresolved reference.*_Impl|kapt.*Error while annotation processing' "$file"; then
    RECOVERY="stale caches: cleaning build outputs and retrying"
    ./gradlew clean >/dev/null 2>&1
    rm -rf "$ROOT/app/build/kotlin" "$ROOT/app/build/tmp/kapt3" 2>/dev/null
    return 0
  fi
  if grep -qE 'This (material|foundation) API is experimental' "$file" && ! grep -qE '^e: .*(Unresolved|Type mismatch|None of the following)' "$file"; then
    # Only opt-in errors: add a file-level opt-in to each affected file (reversible, no behaviour change).
    local f
    for f in $(grep -E '^e: .*API is experimental' "$file" | sed -E 's#^e: file://([^:]+):.*#\1#' | sort -u); do
      if [[ -f "$f" ]] && ! grep -q 'ExperimentalMaterial3Api' "$f"; then
        sed -i '1s/^/@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)\n\n/' "$f"
        RECOVERY="experimental API: added opt-in to ${f#$ROOT/}"
      fi
    done
    return 0
  fi
  return 1
}

# Run a Gradle stage with up to 3 attempts, applying a known recovery between attempts.
run_stage() {
  local name="$1" exit_code="$2"; shift 2
  local attempt=1
  while (( attempt <= 3 )); do
    log "$name: attempt $attempt"
    if gradle "$name-$attempt" "$@" "${EXTRA_ARGS[@]}"; then
      log "$name: OK"
      return 0
    fi
    RECOVERY=""
    if ! recover "$LOGS/$name-$attempt.log"; then
      report_errors "$LOGS/$name-$attempt.log"
      fail "$exit_code" "$name (no automatic fix applies)"
    fi
    log "$name: recovered → $RECOVERY"
    attempt=$((attempt + 1))
  done
  report_errors "$LOGS/$name-3.log"
  fail "$exit_code" "$name failed after 3 attempts"
}

EXTRA_ARGS=()

# ── 1. Preflight ─────────────────────────────────────────────────────────
stage "Preflight"
java_major() { local v; v="$("$1" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"; echo "${v:-0}"; }
if [[ -z "${JAVA_HOME:-}" || "$(java_major "$JAVA_HOME/bin/java")" -lt 17 ]] 2>/dev/null; then
  for candidate in "$HOME"/android-studio*/jbr /opt/android-studio*/jbr /snap/android-studio/current/jbr \
                   /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/*; do
    if [[ -x "$candidate/bin/java" ]] && (( $(java_major "$candidate/bin/java") >= 17 )); then
      export JAVA_HOME="$candidate"; break
    fi
  done
fi
[[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] || fail 10 "No JDK 17+ found. Install one or set JAVA_HOME."
log "JDK: $JAVA_HOME ($(java_major "$JAVA_HOME/bin/java"))"

if [[ ! -f local.properties ]]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  [[ -d "$sdk" ]] || fail 10 "No local.properties and no Android SDK at $sdk"
  echo "sdk.dir=$sdk" > local.properties
  log "Created local.properties (sdk.dir=$sdk)"
fi
SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties)"
[[ -d "$SDK_DIR" ]] || fail 10 "sdk.dir in local.properties does not exist: $SDK_DIR"

# Leftover git locks break later commits; only remove them when no git process is running.
if ! pgrep -x git >/dev/null 2>&1; then
  for l in .git/index.lock .git/HEAD.lock; do [[ -e "$l" ]] && rm -f "$l" && log "Removed stale $l"; done
fi

free_gb=$(df -Pk "$ROOT" | awk 'NR==2 {print int($4/1048576)}')
(( free_gb >= 5 )) || fail 10 "Only ${free_gb} GB free; the build needs about 5 GB"
log "Free disk: ${free_gb} GB · branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null) @ $(git rev-parse --short HEAD 2>/dev/null)"

[[ "$CLEAN" == 1 ]] && { log "Cleaning"; ./gradlew clean >/dev/null 2>&1; }

# ── 2. Compile ───────────────────────────────────────────────────────────
stage "Compile"
run_stage "compile" 20 ":app:compile${VARIANT}Kotlin"

# ── 3. Unit tests ────────────────────────────────────────────────────────
if [[ "$RUN_TESTS" == 1 ]]; then
  stage "Unit tests"
  if gradle "tests" ":app:test${VARIANT}UnitTest" "${EXTRA_ARGS[@]}"; then
    log "tests: OK"
  else
    grep -E 'FAILED|tests completed' "$LOGS/tests.log" | head -20 | tee -a "$REPORT"
    log "Full results: app/build/reports/tests/test${VARIANT}UnitTest/index.html"
    [[ "$ALLOW_TEST_FAIL" == 1 ]] && log "tests: FAILED (continuing, --allow-test-failures)" || fail 30 "unit tests"
  fi
fi

# ── 4. Assemble ──────────────────────────────────────────────────────────
stage "Assemble"
run_stage "assemble" 40 ":app:assemble${VARIANT}"

# ── 5. Package ───────────────────────────────────────────────────────────
stage "Package"
APK="$(ls -t app/build/outputs/apk/"$variant_lc"/*.apk 2>/dev/null | head -1)"
[[ -f "$APK" ]] || fail 50 "No APK found in app/build/outputs/apk/$variant_lc"
version="$(sed -nE 's/.*"versionName": *"([^"]+)".*/\1/p' "$(dirname "$APK")/output-metadata.json" | head -1)"
OUT="$DIST/alal-${version:-dev}-${variant_lc}.apk"
cp "$APK" "$OUT" || fail 50 "copy to $OUT"
rm -f "$DIST"/alal-part-*
( cd "$DIST" && split -b "${SPLIT_MB}M" -d "$(basename "$OUT")" alal-part- )
sha="$(sha256sum "$OUT" | cut -c1-64)"
echo "$sha  $(basename "$OUT")" > "$OUT.sha256"
log "APK: ${OUT#$ROOT/} ($(du -h "$OUT" | cut -f1)) sha256 $sha"
log "Parts for upload: $(ls "$DIST"/alal-part-* | wc -l) × ${SPLIT_MB} MB"

# ── 6. Install (best effort) ─────────────────────────────────────────────
if [[ "$INSTALL" == 1 ]]; then
  stage "Install"
  ADB="$(command -v adb || echo "$SDK_DIR/platform-tools/adb")"
  if [[ ! -x "$ADB" ]]; then
    log "install: skipped (adb not found)"
  elif ! "$ADB" get-state >/dev/null 2>&1; then
    "$ADB" kill-server >/dev/null 2>&1; "$ADB" start-server >/dev/null 2>&1; sleep 2
    "$ADB" get-state >/dev/null 2>&1 || log "install: skipped (no device; unlock the phone and accept USB debugging)"
  fi
  if [[ -x "$ADB" ]] && "$ADB" get-state >/dev/null 2>&1; then
    out="$("$ADB" install -r "$OUT" 2>&1)"
    case "$out" in
      *Success*) log "install: OK on $("$ADB" shell getprop ro.product.model | tr -d '\r')" ;;
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
        # Different signing key. Uninstalling would wipe the user's chats, so never do it automatically.
        log "install: signature differs from the installed app. Not uninstalling (that deletes your data). Uninstall manually to proceed." ;;
      *INSTALL_FAILED_VERSION_DOWNGRADE*) log "install: retrying as downgrade"; "$ADB" install -r -d "$OUT" >/dev/null 2>&1 && log "install: OK (downgrade)" || log "install: downgrade refused" ;;
      *INSUFFICIENT_STORAGE*) log "install: phone storage is full" ;;
      *) log "install: failed: $(echo "$out" | tail -1)" ;;
    esac
  fi
fi

log "DONE"
exit 0
