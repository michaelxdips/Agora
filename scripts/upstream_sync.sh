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
    exit 0
fi

# registered touchpoints keep ours on conflict; everything else aborts
TOUCHPOINTS="$(awk '/GUARD:DATA:START/{f=1;next} /GUARD:DATA:END/{f=0} f' UPSTREAM_TOUCHPOINTS.md \
    | sed -n 's/^\([^#][^:]*\) *:: *max=.*/\1/p' | tr -d '[:space:]')"

run_merge() {
    git merge --no-ff --no-edit "$TARGET"
}

resolve_conflicts() {
    local conflicted any_unregistered=0
    conflicted="$(git diff --name-only --diff-filter=U)"
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
    WORKTREE="$(mktemp -d "${TMPDIR:-/tmp}/hermes-sync-XXXXXX")"
    trap 'git worktree remove --force "$WORKTREE" >/dev/null 2>&1; rm -rf "$WORKTREE"' EXIT
    git worktree add --detach "$WORKTREE" HEAD >/dev/null || exit 1
    if ( cd "$WORKTREE" && git merge --no-ff --no-edit "$TARGET" >/dev/null 2>&1 ); then
        log "DRY-RUN: merge is clean. Branch unchanged."
        exit 0
    fi
    ( cd "$WORKTREE" && resolve_conflicts ) && {
        log "DRY-RUN: conflicts auto-resolved in registered touchpoints only. Branch unchanged."
        exit 0
    }
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
log "OK: $BRANCH now contains $TARGET."
