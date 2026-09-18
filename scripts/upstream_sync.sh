#!/usr/bin/env bash
# upstream_sync.sh — fetch upstream Agora and merge it into the current branch
# following the conflict policy in UPSTREAM_SYNC.md, then run the touchpoint guard.
#
#   SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh   # resolve in a throwaway worktree, report only
#   bash scripts/upstream_sync.sh                  # real merge on the current branch
#
# Exit 0 = merged/clean (or dry-run clean). Exit 1 = aborted, tree untouched.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/newo-ether/Agora.git}"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
DRY_RUN="${SYNC_DRY_RUN:-0}"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"

log() { printf '[upstream_sync] %s\n' "$*"; }

if ! git remote | grep -qx "$UPSTREAM_REMOTE"; then
    git remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL" || exit 1
    log "added remote '$UPSTREAM_REMOTE' -> $UPSTREAM_URL"
fi

log "fetching $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
git fetch --tags "$UPSTREAM_REMOTE" || { log "FETCH FAILED"; exit 1; }

TARGET="$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
if git merge-base --is-ancestor "$TARGET" HEAD; then
    log "already up to date with $TARGET — nothing to merge."
    bash scripts/touchpoint_guard.sh "$TARGET" || exit 1
    # Gate 4 still runs: an already-merged upstream can have moved the contracts in an earlier sync.
    # HERMES INTEGRATION POINT: this compared the *HEAD* side, so an upstream edit to a contract file
    # was invisible (HEAD is our own branch — it never contains the upstream change until after the
    # merge, and by then this check has already run). AGENTS.md rule 8 (N10) asks whether *upstream*
    # moved a contract we build on, so the comparison is against $TARGET.
    CONTRACT_FILES="$(git diff --name-only "$(git merge-base "$TARGET" HEAD)" "$TARGET" -- development ARCHITECTURE.md 2>/dev/null)"
    if [ -n "$CONTRACT_FILES" ]; then
        log "CONTRACT CHANGE since the merge base: $CONTRACT_FILES"
        log "N10 requires re-reading these before further feature work; record it in STATUS.md."
    fi
    exit 0
fi

# registered touchpoints keep ours on conflict; everything else aborts
TOUCHPOINTS="$(awk '/GUARD:DATA:START/{f=1;next} /GUARD:DATA:END/{f=0} f' UPSTREAM_TOUCHPOINTS.md \
    | sed -n 's/^\([^#][^:]*\) *:: *max=.*/\1/p' | tr -d ' 	')"

run_merge() {
    git merge --no-ff --no-edit "$TARGET"
}

resolve_conflicts() {
    local conflicted any_unregistered=0
    # A merge that failed for a non-conflict reason (bad ref, unusable worktree, dirty index) leaves
    # NO unmerged paths at all. Without this guard the caller reads "no conflicts" as "resolved" and
    # reports a false PASS. MERGE_HEAD only exists while a merge is genuinely in progress.
    if [ ! -e "$(git rev-parse --git-dir 2>/dev/null)/MERGE_HEAD" ]; then
        log "no merge in progress — refusing to report the failure as resolved"
        return 2
    fi
    conflicted="$(git diff --name-only --diff-filter=U)" || return 2
    [ -z "$conflicted" ] && return 0
    while read -r f; do
        [ -z "$f" ] && continue
        if printf '%s\n' "$TOUCHPOINTS" | grep -qxF "$f"; then
            git checkout --ours -- "$f" && git add -- "$f"
            log "touchpoint conflict kept ours: $f (re-apply the Hermes edit if upstream moved it)"
        else
            log "UNREGISTERED conflict: $f — aborting per conflict policy"
            any_unregistered=1
        fi
    done <<< "$conflicted"
    return "$any_unregistered"
}

if [ "$DRY_RUN" = "1" ]; then
    # git worktree records the path it is given verbatim. An MSYS path like /tmp/x is a valid path to
    # bash but not to native git, which would create the worktree under C:/tmp and then fail every
    # command run from the (nonexistent) /tmp/x. Normalise to a native path when cygpath is present.
    WORKTREE="$(mktemp -d "${TMPDIR:-/tmp}/hermes-sync-XXXXXX")"
    if command -v cygpath >/dev/null 2>&1; then
        WORKTREE="$(cygpath -m "$WORKTREE")"
    fi
    trap 'git worktree remove --force "$WORKTREE" >/dev/null 2>&1; rm -rf "$WORKTREE"' EXIT
    git worktree add --detach "$WORKTREE" HEAD >/dev/null || exit 1
    if ( cd "$WORKTREE" && git merge --no-ff --no-edit "$TARGET" >/dev/null 2>&1 ); then
        log "DRY-RUN: merge is clean. Branch unchanged."
        exit 0
    fi
    ( cd "$WORKTREE" && resolve_conflicts )
    resolved=$?
    if [ "$resolved" -eq 0 ]; then
        log "DRY-RUN: conflicts auto-resolved in registered touchpoints only. Branch unchanged."
        exit 0
    fi
    if [ "$resolved" -eq 2 ]; then
        log "DRY-RUN: merge could not be evaluated (not a conflict-resolution failure). Branch unchanged."
        exit 1
    fi
    log "DRY-RUN: merge needs human attention (conflict outside registered touchpoints). Branch unchanged."
    exit 1
fi

if run_merge; then
    log "merge clean."
else
    if ! resolve_conflicts; then
        git merge --abort
        log "ABORTED: unregistered conflict. '$BRANCH' left untouched."
        exit 1
    fi
    if ! git commit --no-edit >/dev/null 2>&1; then
        log "ABORTED: could not finalise the merge commit."
        git merge --abort
        exit 1
    fi
    log "merge completed with touchpoint resolutions."
fi

log "running touchpoint guard"
bash scripts/touchpoint_guard.sh "$TARGET" || { log "GUARD FAILED after merge"; exit 1; }

# ── gate 3: unit tests + fdroidDebug build ───────────────────────────────────
# A merge that compiles but breaks the autopilot contracts must not reach main. These are the same
# gates the mandate names, run in the merged tree so the result is evidence about what is about to be
# promoted — not about the branch that existed before the merge.
if [ "${SYNC_SKIP_BUILD:-0}" != "1" ]; then
    log "running unit tests + fdroidDebug build"
    # `testFdroidDebugUnitTest` includes UpstreamContractSentinelTest, which asserts the upstream
    # memory/skill APIs, the active-memory injection site and the settings attach points by symbol.
    # That is what makes upstream API drift fail loudly here instead of silently at runtime.
    # HERMES INTEGRATION POINT: these two lines used to point at a machine that no longer exists
    # (`Documents/Chatapp/_tools/...`), so the post-merge gate below ran with a JAVA_HOME that has no
    # `java` in it and an ANDROID_HOME with no SDK — every sync would have reported "TESTS/BUILD
    # FAILED" for a toolchain reason and aborted a merge that was actually fine. Resolved the same way
    # `audit_gate0.sh` does it: env var first, then the toolchain this checkout actually has.
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
    if ! ./gradlew :app:testFdroidDebugUnitTest :app:assembleFdroidDebug --console=plain \
        > "${SYNC_BUILD_LOG:-/tmp/hermes-sync-build.log}" 2>&1; then
        log "TESTS/BUILD FAILED after merge — main untouched. See ${SYNC_BUILD_LOG:-/tmp/hermes-sync-build.log}"
        exit 1
    fi
    log "tests + build green"
else
    log "SYNC_SKIP_BUILD=1 — skipping the test/build gate (documented dry-run only)"
fi

# ── gate 4: contract-change detector ────────────────────────────────────────
# N10: an upstream change to the contracts Hermes builds on must be re-read before further feature
# work. Detected, not silently absorbed.
# HERMES INTEGRATION POINT: same fix as the early-exit check above — compare the *upstream* side
# ($TARGET), not HEAD, or an upstream contract change never trips N10.
CONTRACT_FILES="$(git diff --name-only "$(git merge-base "$TARGET" HEAD)" "$TARGET" -- development ARCHITECTURE.md 2>/dev/null)"
if [ -n "$CONTRACT_FILES" ]; then
    log "CONTRACT CHANGE: $CONTRACT_FILES"
    log "N10 requires re-reading these before further feature work; record it in STATUS.md."
    if command -v gh >/dev/null 2>&1; then
        gh issue create --label contract-change \
            --title "Upstream contract change in $UPSTREAM_BRANCH" \
            --body "Files: $CONTRACT_FILES" >/dev/null 2>&1 \
            && log "contract-change issue opened" \
            || log "contract-change issue could not be opened — recorded here instead"
    fi
fi

log "OK: $BRANCH now contains $TARGET."
