#!/usr/bin/env bash
# GATE 0 build gates for the v2.1 audit — every gate that does NOT need a device.
#
# Deliberately separate from the device tracks: the build gates are the ones that can be re-run
# cheaply at any time, and mixing them with adb steps made the previous gate scripts fail on a
# missing emulator for reasons that had nothing to do with the build.
#
# Every section prints its own verdict, and a section that cannot run says so instead of printing
# nothing (the failure mode that hid a certificate check for a whole session).
#
# HERMES INTEGRATION POINT: two defects fixed here.
#  1. The script used to `cd` into a hardcoded path belonging to a different machine
#     (`/c/Users/Michael/Documents/Chatapp/Agora`) and export a hardcoded JAVA_HOME/ANDROID_HOME that
#     no longer exist, so it died on line 18 with exit 1 for anyone running it in this checkout. Paths
#     are now discovered: the repo root is derived from $BASH_SOURCE, JAVA_HOME from an explicit env
#     var or the `java` on PATH, ANDROID_HOME from an env var or this checkout's own `local.properties`.
#  2. `verdict()` only *printed* the result and the script ended with `echo`, so a run full of FAIL
#     lines still exited 0. The signature section likewise printed the expected digest and never
#     compared it. Every section now accumulates into $fail, the signature is compared, and the exit
#     code is the verdict.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_BIN="$(command -v java 2>/dev/null)"
    if [ -n "$JAVA_BIN" ]; then
        JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
        export JAVA_HOME
    fi
fi
export PATH="${JAVA_HOME:-}/bin:$PATH"

if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
    # java.util.Properties escaping: `\\` is a literal backslash and `\:` a literal colon, so the
    # value has to be decoded before it is a path. Bash parameter expansion is used instead of sed
    # because sed cannot express "unescape exactly once" without mangling the drive-letter colon.
    sdk_raw="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
    sdk_raw="${sdk_raw//\\\\/\\}"
    sdk_raw="${sdk_raw//\\:/:}"
    ANDROID_HOME="${sdk_raw//\\//}"
    export ANDROID_HOME
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
export GRADLE_OPTS="-Xmx3g"

OUT="audit-evidence/gate0"
mkdir -p "$OUT"
echo "HEAD: $(git rev-parse HEAD)"
echo "branch: $(git rev-parse --abbrev-ref HEAD)"
echo "date: $(date -Iseconds)"
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
echo

fail=0

verdict() {  # verdict <name> <exit-code> <log>
    if [ "$2" -eq 0 ]; then
        printf '%-42s PASS (exit 0)   %s\n' "$1" "$3"
    else
        printf '%-42s FAIL (exit %s)  %s\n' "$1" "$2" "$3"
        fail=1
    fi
}

echo "===== [1] touchpoint guard ====="
bash scripts/touchpoint_guard.sh >"$OUT/guard.log" 2>&1
verdict "touchpoint_guard.sh" "$?" "$OUT/guard.log"
tail -1 "$OUT/guard.log"

echo
echo "===== [2] sync dry-run ====="
SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh >"$OUT/sync_dryrun.log" 2>&1
verdict "SYNC_DRY_RUN=1 upstream_sync.sh" "$?" "$OUT/sync_dryrun.log"
tail -2 "$OUT/sync_dryrun.log"

echo
echo "===== [3] unit tests, all three suites (--rerun-tasks) ====="
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest \
    --rerun-tasks --console=plain >"$OUT/unit_tests.log" 2>&1
verdict "unit tests x3 (--rerun-tasks)" "$?" "$OUT/unit_tests.log"
grep -E "^BUILD" "$OUT/unit_tests.log" | tail -1
python - <<'PY'
import glob, re, sys
total = failures = 0
for label, pat in [
    ('fdroid', 'app/build/test-results/testFdroidDebugUnitTest/*.xml'),
    ('play',   'app/build/test-results/testPlayDebugUnitTest/*.xml'),
    ('wear',   'wear/build/test-results/testDebugUnitTest/*.xml'),
]:
    t = f = e = s = 0
    for x in glob.glob(pat):
        head = open(x, encoding='utf-8', errors='replace').read(4000)
        m = re.search(r'tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"', head)
        if m:
            t += int(m.group(1)); s += int(m.group(2)); f += int(m.group(3)); e += int(m.group(4))
    total += t
    failures += f + e
    print(f'  {label:7s} {t:5d} tests, {f} failures, {e} errors, {s} skipped')
print(f'  TOTAL   {total:5d} tests')
# A suite with tests but failures is NOT a pass: the old form returned 0 for 10 tests / 10 failures.
sys.exit(0 if total > 0 and failures == 0 else 1)
PY
verdict "test counts (0 failures, 0 errors)" "$?" "$OUT/unit_tests.log"

echo
echo "===== [4] debug APKs + signed release APKs ====="
./gradlew :app:assembleFdroidDebug :app:assemblePlayDebug :wear:assembleDebug \
    --console=plain >"$OUT/assemble_debug.log" 2>&1
verdict "assembleFdroidDebug + PlayDebug + wearDebug" "$?" "$OUT/assemble_debug.log"

./gradlew :app:assembleFdroidRelease :wear:assembleRelease \
    --console=plain >"$OUT/assemble_release.log" 2>&1
verdict "assembleFdroidRelease + wearRelease (signed)" "$?" "$OUT/assemble_release.log"

echo
echo "  artifacts:"
for apk in app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk \
           app/build/outputs/apk/play/debug/app-play-debug.apk \
           app/build/outputs/apk/fdroid/release/app-fdroid-release.apk \
           wear/build/outputs/apk/debug/wear-debug.apk \
           wear/build/outputs/apk/release/wear-release.apk; do
    if [ -f "$apk" ]; then
        printf '    %12d  %s\n' "$(stat -c %s "$apk")" "$apk"
    else
        printf '    %12s  %s  (MISSING)\n' "-" "$apk"
    fi
done

echo
echo "===== [5] signature check (both release APKs) ====="
SIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner.bat"
[ -x "$SIGNER" ] || SIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
EXPECTED="7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7"
phone_digest=""
wear_digest=""
for apk in app/build/outputs/apk/fdroid/release/app-fdroid-release.apk \
           wear/build/outputs/apk/release/wear-release.apk; do
    printf '  %-46s ' "$(basename "$apk")"
    digest=$("$SIGNER" verify --print-certs "$apk" 2>/dev/null | grep 'SHA-256 digest' | head -1 | sed 's/.*digest: //')
    if [ -n "$digest" ]; then echo "$digest"; else echo "NO DIGEST — treat as UNVERIFIED"; fi
    case "$apk" in
        *app-fdroid-release.apk) phone_digest="$digest" ;;
        *wear-release.apk)       wear_digest="$digest" ;;
    esac
done
echo "  expected: both release APKs share 7188ce70...aa56d7 (the Data Layer needs one identity)"
# Compared, not printed: this section used to print the expected value and never look at the actual.
if [ -z "$phone_digest" ] || [ -z "$wear_digest" ]; then
    echo "  signature: FAIL — a release APK could not be verified (missing or unsigned)"
    fail=1
elif [ "$phone_digest" != "$EXPECTED" ] || [ "$wear_digest" != "$EXPECTED" ]; then
    echo "  signature: FAIL — phone=$phone_digest wear=$wear_digest do not both match the expected identity"
    fail=1
else
    echo "  signature: PASS — phone and watch share the expected release identity"
fi

echo
echo "===== [6] lintVitalRelease (both modules) ====="
./gradlew :app:lintVitalFdroidRelease :wear:lintVitalRelease --console=plain >"$OUT/lint_vital.log" 2>&1
verdict "lintVital (fdroid release + wear release)" "$?" "$OUT/lint_vital.log"

echo
echo "===== [7] launcher label + applicationId, all APKs ====="
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2.exe"
[ -x "$AAPT2" ] || AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
# HERMES INTEGRATION POINT: this section used to grep the labels and print the counts, then fall
# through to the end of the script without touching `$fail`. `grep -c` exits 1 when it counts zero,
# and that status was discarded — so an APK still labelled "Agora" (the wrong product identity for
# this fork) printed `labels still reading "Agora": 1` and the gate still exited 0. The counts are now
# compared and a mismatch sets `fail`.
for apk in app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk \
           app/build/outputs/apk/play/debug/app-play-debug.apk \
           app/build/outputs/apk/fdroid/release/app-fdroid-release.apk \
           wear/build/outputs/apk/release/wear-release.apk; do
    [ -f "$apk" ] || continue
    echo "  --- $(basename "$apk")"
    badging="$("$AAPT2" dump badging "$apk" 2>/dev/null)"
    echo "$badging" | grep "^package:" | sed 's/^/    /'
    hermes_labels="$(printf '%s\n' "$badging" | grep -c "application-label:'Hermes X'" || true)"
    agora_labels="$(printf '%s\n' "$badging" | grep -c "application-label:'Agora'" || true)"
    echo "    labels reading \"Hermes X\": $hermes_labels"
    echo "    labels still reading \"Agora\": $agora_labels"
    if [ "$hermes_labels" -lt 1 ]; then
        echo "  label: FAIL — $(basename "$apk") does not carry the fork's product name"
        fail=1
    fi
    if [ "$agora_labels" -gt 0 ]; then
        echo "  label: FAIL — $(basename "$apk") still carries the upstream product name"
        fail=1
    fi
done

echo
echo "===== GATE 0 build gates complete ====="
echo "logs: $OUT/"
if [ "$fail" -eq 0 ]; then
    echo "GATE 0: PASS"
else
    echo "GATE 0: FAIL — at least one gate above did not pass"
fi
exit "$fail"
