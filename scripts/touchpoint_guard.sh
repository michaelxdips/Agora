#!/usr/bin/env bash
# touchpoint_guard.sh — fail if the fork edits upstream files outside the registry,
# or if any registered touchpoint exceeds its declared line budget.
#
# Usage: bash scripts/touchpoint_guard.sh [upstream-ref]      (default: upstream/master)
# Exit 0 = clean. Exit 1 = violation (details on stdout).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

REGISTRY="UPSTREAM_TOUCHPOINTS.md"
# HERMES INTEGRATION POINT: the default used to be `origin/master`, which in this fork is the *fork
# point* (proved: `git rev-parse origin/master` → 914e7c8d, the merge base), not current upstream. A
# real upstream edit therefore never reached the comparison unless the caller passed a ref. The
# default is now the real upstream ref, resolved with a fallback chain so a clone that has not added
# the remote yet still gets a sensible answer instead of a silently weaker check.
UPSTREAM_REF="${1:-}"
if [ -z "$UPSTREAM_REF" ]; then
    for candidate in upstream/master upstream/main origin/upstream origin/master; do
        if git rev-parse --verify --quiet "$candidate" >/dev/null; then
            UPSTREAM_REF="$candidate"
            break
        fi
    done
fi
UPSTREAM_REF="${UPSTREAM_REF:-upstream/master}"

# Hermes-only paths: additions here are always allowed.
# Root-level Markdown is allowed as a CLASS, not one name at a time: the guard lists each doc by name
# and a new one (e.g. a mega-prompt handed to the next session) then fails the guard for the crime of
# existing. Docs are Hermes-owned by definition — upstream's root .md files are already covered by the
# registered-touchpoint check, so widening this cannot hide an upstream edit.
ALLOWED_RE='^([A-Z][A-Za-z0-9_.-]*\.md|AGENTS\.md|ROADMAP\.md|NOTICE\.md|evidence/|personas/|scripts/|\.github/workflows/upstream-sync\.yml|app/src/fdroid/res/|app/src/play/res/|app/src/main/assets/personas/|app/src/main/java/com/newoether/agora/autopilot/|app/src/test/java/com/newoether/agora/autopilot/|app/src/androidTest/java/com/newoether/agora/autopilot/|wear/)'

fail=0
note() { printf '%s\n' "$*"; }

if ! git rev-parse --verify --quiet "$UPSTREAM_REF" >/dev/null; then
    # HERMES INTEGRATION POINT: this used to fall back to `git diff HEAD`, i.e. the working tree
    # against HEAD — which cannot see a violation that was already *committed*, so a missing upstream
    # ref turned the guard into a false PASS. A guard that cannot run is a FAIL, not a warning.
    # GUARD_ALLOW_MISSING_REF=1 is the documented escape for a clone that has not added the remote.
    if [ "${GUARD_ALLOW_MISSING_REF:-0}" = "1" ]; then
        note "touchpoint_guard: WARN upstream ref '$UPSTREAM_REF' not present; GUARD_ALLOW_MISSING_REF=1 so the working tree is compared against HEAD (weaker check)."
        DIFF_ARGS="HEAD"
    else
        note "touchpoint_guard: FAIL upstream ref '$UPSTREAM_REF' not present — cannot verify upstream hygiene."
        note "touchpoint_guard: run 'git fetch upstream' (or pass a ref: bash scripts/touchpoint_guard.sh <ref>)."
        note "touchpoint_guard: GUARD_ALLOW_MISSING_REF=1 downgrades this to a working-tree check."
        exit 1
    fi
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

# HERMES INTEGRATION POINT: "does this path exist in upstream?" — the question the marker rule needs.
# The diff is taken against the merge base, so it contains BOTH files upstream owns and files the fork
# adds inside upstream-owned directories. Those two are not the same thing: the marker exists to point
# at the edited site *inside an upstream file*, and a file upstream has never had has no such site to
# mark. Requiring it there forced a marker into `fastlane/.../changelogs/*.txt`, which is text a store
# listing shows to users. Registration is still required either way — this only decides whether a
# marker is meaningful.
exists_upstream() {
    git cat-file -e "$UPSTREAM_REF:$1" 2>/dev/null
}

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
        elif exists_upstream "$path"; then
            note "touchpoint_guard: ok   $path ($changed/$max lines)"
        else
            note "touchpoint_guard: ok   $path ($changed/$max lines, fork-added file)"
        fi
        # marker check: a registered file that EXISTS upstream must declare the integration point.
        # A fork-added file is skipped — see exists_upstream above for why.
        if exists_upstream "$path" && ! grep -q 'HERMES INTEGRATION POINT' "$path" 2>/dev/null; then
            note "touchpoint_guard: FAIL $path is registered but has no 'HERMES INTEGRATION POINT' marker."
            fail=1
        fi
        continue
    fi
    if exists_upstream "$path"; then
        note "touchpoint_guard: FAIL unregistered upstream file modified: $path"
    else
        note "touchpoint_guard: FAIL undeclared new file in an upstream-owned path: $path"
    fi
    fail=1
done <<< "$NUMSTAT"

# ── 3. secret hygiene: local-only files must never be tracked ───────────────
# Session 4: `signing.properties` was in `.gitignore` but not checked here, and the tracked-file
# scan covered `*.jks`/`*.keystore` only — a differently-named key (`.p12`, `.pem`, `.key`) or the
# properties file itself could have been committed and the guard would still have passed. The
# history scan already covered `local.properties`; this makes the tracked scan agree with it.
for f in local.properties signing.properties; do
    if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
        note "touchpoint_guard: FAIL '$f' is tracked — it must stay out of git."
        fail=1
    fi
done
if git ls-files | grep -qiE '\.(jks|keystore|p12|pfx|pem|key)$'; then
    note "touchpoint_guard: FAIL a keystore/key file is tracked in git."
    fail=1
fi
if git log --all --diff-filter=A --name-only --pretty=format: 2>/dev/null | grep -qE '(^|/)(local\.properties|signing\.properties|.*\.(jks|keystore|p12|pfx))$'; then
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
