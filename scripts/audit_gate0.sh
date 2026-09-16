#!/usr/bin/env bash
# GATE 0 build gates for the v2.1 audit — every gate that does NOT need a device.
#
# Deliberately separate from the device tracks: the build gates are the ones that can be re-run
# cheaply at any time, and mixing them with adb steps made the previous gate scripts fail on a
# missing emulator for reasons that had nothing to do with the build.
#
# Every section prints its own verdict, and a section that cannot run says so instead of printing
# nothing (the failure mode that hid a certificate check for a whole session).
set -uo pipefail

export JAVA_HOME='C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1'
export ANDROID_HOME='C:/Users/Michael/Documents/Chatapp/_tools/sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Xmx3g"

cd /c/Users/Michael/Documents/Chatapp/Agora || exit 1

OUT="audit-evidence/gate0"
mkdir -p "$OUT"
echo "HEAD: $(git rev-parse HEAD)"
echo "branch: $(git rev-parse --abbrev-ref HEAD)"
echo "date: $(date -Iseconds)"
echo

verdict() {  # verdict <name> <exit-code> <log>
    if [ "$2" -eq 0 ]; then
        printf '%-42s PASS (exit 0)   %s\n' "$1" "$3"
    else
        printf '%-42s FAIL (exit %s)  %s\n' "$1" "$2" "$3"
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
import glob, re
total = 0
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
    print(f'  {label:7s} {t:5d} tests, {f} failures, {e} errors, {s} skipped')
print(f'  TOTAL   {total:5d} tests')
PY

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
for apk in app/build/outputs/apk/fdroid/release/app-fdroid-release.apk \
           wear/build/outputs/apk/release/wear-release.apk; do
    printf '  %-46s ' "$(basename "$apk")"
    digest=$("$SIGNER" verify --print-certs "$apk" 2>/dev/null | grep 'SHA-256 digest' | head -1 | sed 's/.*digest: //')
    if [ -n "$digest" ]; then echo "$digest"; else echo "NO DIGEST — treat as UNVERIFIED"; fi
done
echo "  expected: both release APKs share 7188ce70...aa56d7 (the Data Layer needs one identity)"

echo
echo "===== [6] lintVitalRelease (both modules) ====="
./gradlew :app:lintVitalFdroidRelease :wear:lintVitalRelease --console=plain >"$OUT/lint_vital.log" 2>&1
verdict "lintVital (fdroid release + wear release)" "$?" "$OUT/lint_vital.log"

echo
echo "===== [7] launcher label + applicationId, all APKs ====="
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2.exe"
for apk in app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk \
           app/build/outputs/apk/play/debug/app-play-debug.apk \
           app/build/outputs/apk/fdroid/release/app-fdroid-release.apk \
           wear/build/outputs/apk/release/wear-release.apk; do
    [ -f "$apk" ] || continue
    echo "  --- $(basename "$apk")"
    "$AAPT2" dump badging "$apk" 2>/dev/null | grep "^package:" | sed 's/^/    /'
    "$AAPT2" dump badging "$apk" 2>/dev/null | grep -c "application-label:'Hermes X'" | sed 's/^/    labels reading "Hermes X": /'
    "$AAPT2" dump badging "$apk" 2>/dev/null | grep -c "application-label:'Agora'" | sed 's/^/    labels still reading "Agora": /'
done

echo
echo "===== GATE 0 build gates complete ====="
echo "logs: $OUT/"
