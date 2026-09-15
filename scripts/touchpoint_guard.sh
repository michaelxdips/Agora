#!/usr/bin/env bash
# touchpoint_guard.sh — fail if the fork edits upstream files outside the registry,
# or if any registered touchpoint exceeds its declared line budget.
#
# Usage: bash scripts/touchpoint_guard.sh [upstream-ref]      (default: origin/master)
# Exit 0 = clean. Exit 1 = violation (details on stdout).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

REGISTRY="UPSTREAM_TOUCHPOINTS.md"
UPSTREAM_REF="${1:-origin/master}"

# Hermes-only paths: additions here are always allowed.
# Root-level Markdown is allowed as a CLASS, not one name at a time: the guard lists each doc by name
# and a new one (e.g. a mega-prompt handed to the next session) then fails the guard for the crime of
# existing. Docs are Hermes-owned by definition — upstream's root .md files are already covered by the
# registered-touchpoint check, so widening this cannot hide an upstream edit.
ALLOWED_RE='^([A-Z][A-Z0-9_]*\.md|AGENTS\.md|ROADMAP\.md|NOTICE\.md|evidence/|personas/|scripts/|\.github/workflows/upstream-sync\.yml|app/src/fdroid/res/|app/src/play/res/|app/src/main/assets/personas/|app/src/main/java/com/newoether/agora/autopilot/|app/src/test/java/com/newoether/agora/autopilot/|app/src/androidTest/java/com/newoether/agora/autopilot/|wear/)'

fail=0
note() { printf '%s\n' "$*"; }

if ! git rev-parse --verify --quiet "$UPSTREAM_REF" >/dev/null; then
    note "touchpoint_guard: WARN upstream ref '$UPSTREAM_REF' not present; run 'git fetch upstream'."
    note "touchpoint_guard: falling back to comparing working tree against HEAD."
    DIFF_ARGS="HEAD"
else
    MERGE_BASE="$(git merge-base "$UPSTREAM_REF" HEAD 2>/dev/null)"
    DIFF_ARGS="${MERGE_BASE:-$UPSTREAM_REF}"
fi

# ── 1. collect registered paths + budgets from the GUARD:DATA block ──────────
declare -A BUDGET
while read -r line; do
    case "$line" in
        \#*|'') continue ;;
    esac
    path="${line%%::*}"
    max="${line##*max=}"
    path="$(printf '%s' "$path" | tr -d '[:space:]')"
    max="$(printf '%s' "$max" | tr -d '[:space:]')"
    [ -n "$path" ] && BUDGET["$path"]="$max"
done < <(awk '/GUARD:DATA:START/{f=1;next} /GUARD:DATA:END/{f=0} f' "$REGISTRY")

# ── 2. diff upstream -> HEAD, per file ──────────────────────────────────────
NUMSTAT="$(git diff --numstat "$DIFF_ARGS" -- 2>/dev/null)"

if [ -z "$NUMSTAT" ]; then
    note "touchpoint_guard: no diff against $DIFF_ARGS — nothing to check."
fi

while IFS=$'\t' read -r added removed path; do
    [ -z "${path:-}" ] && continue
    if [[ "$path" =~ $ALLOWED_RE ]]; then
        continue
    fi
    if [ -n "${BUDGET[$path]:-}" ]; then
        changed=$(( ${added//-/0} + ${removed//-/0} ))
        max="${BUDGET[$path]}"
        if [ "$changed" -gt "$max" ]; then
            note "touchpoint_guard: FAIL $path changed $changed lines (budget $max)."
            fail=1
        else
            note "touchpoint_guard: ok   $path ($changed/$max lines)"
        fi
        # marker check: registered files must declare the integration point
        if ! grep -q 'HERMES INTEGRATION POINT' "$path" 2>/dev/null; then
            note "touchpoint_guard: FAIL $path is registered but has no 'HERMES INTEGRATION POINT' marker."
            fail=1
        fi
        continue
    fi
    note "touchpoint_guard: FAIL unregistered upstream file modified: $path"
    fail=1
done <<< "$NUMSTAT"

# ── 3. secret hygiene: local-only files must never be tracked ───────────────
for f in local.properties; do
    if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
        note "touchpoint_guard: FAIL '$f' is tracked — it must stay in .git/info/exclude."
        fail=1
    fi
done
if git ls-files | grep -qiE '\.(jks|keystore)$'; then
    note "touchpoint_guard: FAIL keystore/jks file is tracked in git."
    fail=1
fi
if git log --all --diff-filter=A --name-only --pretty=format: 2>/dev/null | grep -qE '(^|/)(local\.properties|.*\.jks|.*\.keystore)$'; then
    note "touchpoint_guard: FAIL a secret-ish file exists somewhere in git history."
    fail=1
fi

# ── 4. upstream contract files that Hermes depends on still exist ───────────
for f in app/src/main/java/com/newoether/agora/data/SkillManager.kt \
         app/src/main/AndroidManifest.xml \
         app/build.gradle.kts \
         thirdparty/llama.cpp \
         thirdparty/proot; do
    if [ ! -e "$f" ]; then
        note "touchpoint_guard: FAIL expected upstream contract path missing: $f"
        fail=1
    fi
done

if [ "$fail" -eq 0 ]; then
    note "touchpoint_guard: PASS ($DIFF_ARGS)"
else
    note "touchpoint_guard: FAILED"
fi
exit "$fail"
