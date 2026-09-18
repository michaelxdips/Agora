#!/usr/bin/env bash
# persona_update.sh — Phase 6 P2 update flow.
#
# Fetch the latest upstream release/tag for each persona → diff against the vendored copy → run the
# persona regression tests → ONLY THEN update personas/upstream.lock + the vendored copies.
#
# The order is the feature. An update that lands before the tests pass is how a persona silently
# stops being a persona, and nothing downstream would ever tell you.
#
# Usage:
#   bash scripts/persona_update.sh              # check + report only (no writes)
#   bash scripts/persona_update.sh --apply      # write the update after the tests pass
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

LOCK="personas/upstream.lock"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/hermes-persona-XXXXXX")"
# Native tools record the MSYS form as C:/tmp/...; normalise so both agree (same bug class the sync
# script hit in Phase 6 — an unresolved path silently reads as "nothing to do").
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$WORK")"; fi
trap 'rm -rf "$WORK"' EXIT

fail() { echo "persona_update: FAIL $*"; exit 1; }

command -v curl >/dev/null 2>&1 || fail "curl is required"

# ── parse the lock (flat records, one persona each) ──────────────────────────
ids=$(grep -o '"id": *"[^"]*"' "$LOCK" | sed 's/.*: *"//; s/"$//')
[ -n "$ids" ] || fail "no personas in $LOCK"

echo "persona_update: checking $(echo "$ids" | wc -l | tr -d ' ') persona(s)"

UPDATES=""
for id in $ids; do
    block=$(awk -v id="\"$id\"" '
        $0 ~ "\"id\": *" id { f=1 }
        f { print }
        f && /^    }/ { exit }
    ' "$LOCK")
    field() { printf '%s\n' "$block" | grep -o "\"$1\": *\"[^\"]*\"" | head -1 | sed 's/.*: *"//; s/"$//'; }

    repo=$(field repo); ref=$(field ref); src=$(field source_path)
    vendored="personas/$id/SKILL.md"
    [ -f "$vendored" ] || fail "vendored copy missing: $vendored"

    # Latest tag, falling back to the pinned ref when the tags API is unreachable.
    latest=$(curl -sSL --max-time 20 "https://api.github.com/repos/${repo#https://github.com/}/tags" 2>/dev/null \
        | grep -o '"name": *"[^"]*"' | head -1 | sed 's/.*: *"//; s/"$//')
    latest=${latest:-$ref}

    if [ "$latest" = "$ref" ]; then
        echo "persona_update: $id up to date ($ref)"
        continue
    fi

    url="https://raw.githubusercontent.com/${repo#https://github.com/}/$latest/$src"
    curl -sSL --max-time 30 -o "$WORK/$id.SKILL.md" "$url" 2>/dev/null
    [ -s "$WORK/$id.SKILL.md" ] || fail "$id: could not fetch $url"

    if diff -q "$vendored" "$WORK/$id.SKILL.md" >/dev/null 2>&1; then
        echo "persona_update: $id tag moved to $latest but the skill text is identical"
    else
        echo "persona_update: $id has an update ($ref -> $latest), $(diff "$vendored" "$WORK/$id.SKILL.md" | grep -c '^[<>]') changed lines"
    fi
    UPDATES="$UPDATES $id"
    echo "$latest" > "$WORK/$id.ref"
done

if [ -z "$UPDATES" ]; then
    echo "persona_update: nothing to update"
    exit 0
fi

# ── regression gate: the persona tests must pass BEFORE any write ────────────
echo "persona_update: running the persona regression tests"
# HERMES INTEGRATION POINT: these two lines used to point at a machine that no longer exists
# (`Documents/Chatapp/_tools/...`), so the regression gate below ran with a JAVA_HOME that has no
# `java` in it and an ANDROID_HOME with no SDK — the gate could only fail for the wrong reason.
# Resolved the same way `audit_gate0.sh` does it: env var first, then the toolchain this checkout
# actually has (`java` on PATH, and `sdk.dir` in the machine-local local.properties).
if [ -z "${JAVA_HOME:-}" ]; then
    java_bin="$(command -v java 2>/dev/null)"
    [ -n "$java_bin" ] && export JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
    sdk_raw="$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)"
    sdk_raw="${sdk_raw//\\\\/\\}"
    sdk_raw="${sdk_raw//\\:/:}"
    export ANDROID_HOME="${sdk_raw//\\//}"
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
export PATH="${JAVA_HOME:-}/bin:$PATH"
if ! ./gradlew :app:testFdroidDebugUnitTest \
    --tests "com.newoether.agora.autopilot.PersonaStoreTest" \
    --tests "com.newoether.agora.autopilot.PersonaApplierTest" \
    --tests "com.newoether.agora.autopilot.PersonaIsolationTest" \
    --console=plain > "$WORK/tests.log" 2>&1; then
    echo "persona_update: regression tests FAILED — the vendored copy is NOT updated."
    tail -25 "$WORK/tests.log"
    exit 1
fi
echo "persona_update: regression tests green"

if [ "$APPLY" -ne 1 ]; then
    echo "persona_update: dry run (pass --apply to write). Would update:$UPDATES"
    exit 0
fi

# ── write: vendored copy, in-APK asset copy, lock (sha + ref), then commit ───
for id in $UPDATES; do
    newref=$(cat "$WORK/$id.ref")
    cp "$WORK/$id.SKILL.md" "personas/$id/SKILL.md"
    mkdir -p "app/src/main/assets/personas/$id"
    cp "$WORK/$id.SKILL.md" "app/src/main/assets/personas/$id/SKILL.md"
    sha=$(sha256sum "personas/$id/SKILL.md" | cut -d' ' -f1)
    python - "$id" "$newref" "$sha" <<'PY'
import re, sys
pid, ref, sha = sys.argv[1], sys.argv[2], sys.argv[3]
path = "personas/upstream.lock"
text = open(path, encoding="utf-8").read()
block = re.compile(r'(\{\s*"id": "%s".*?\})' % re.escape(pid), re.S)
def fix(m):
    b = m.group(1)
    b = re.sub(r'"ref": "[^"]*"', f'"ref": "{ref}"', b)
    b = re.sub(r'"sha256": "[^"]*"', f'"sha256": "{sha}"', b)
    b = re.sub(r'"fetched": "[^"]*"', '"fetched": "%s"' % __import__("datetime").date.today().isoformat(), b)
    return b
open(path, "w", encoding="utf-8").write(block.sub(fix, text))
PY
    cp personas/upstream.lock app/src/main/assets/personas/upstream.lock
    git add "personas/$id/SKILL.md" "app/src/main/assets/personas/$id/SKILL.md" personas/upstream.lock app/src/main/assets/personas/upstream.lock
    git commit -q -m "hermes: personas: bump $id $newref"
    echo "persona_update: $id -> $newref ($sha)"
done

echo "persona_update: done. Rebuild to pick up the new asset copy."
