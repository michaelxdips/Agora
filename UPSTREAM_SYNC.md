# UPSTREAM_SYNC.md — sync protocol

Upstream: `https://github.com/newo-ether/Agora.git` (branch `master`).
This fork's integration branch: `main`.

## Invariants

* Upstream files may only be edited where registered in `UPSTREAM_TOUCHPOINTS.md`, each with a
  declared change budget (`max=N` lines) and a `HERMES INTEGRATION POINT` marker at the edit site.
* Every other Hermes addition lives in a Hermes-only path (guard `ALLOWED` regex).
* `LICENSE` is never modified (`NOTICE.md` carries attribution).

## Procedure

```bash
bash scripts/upstream_sync.sh            # fetch + merge (or dry-run)
bash scripts/touchpoint_guard.sh         # guard + contract check
```

`SYNC_DRY_RUN=1` makes the script resolve everything in a throwaway worktree and report the
outcome without touching `main`. The GitHub Action runs the real merge daily and pushes
`main` + tags only when the guard passes.

## Conflict policy

1. Conflict in a **registered touchpoint** → keep ours (`git checkout --ours`), re-apply the
   Hermes edit, continue. These files are deliberately small-diff.
2. Conflict in a **Hermes-only** file → keep ours (upstream cannot legitimately touch it).
3. Conflict anywhere else → **abort** the merge, leave `main` untouched, fail the job, and file an
   issue labelled `contract-change`. Upstream changed a contract we depend on.

## After a sync

* If `development/*.md` or `ARCHITECTURE.md` changed upstream: re-read them before further feature
  work; note it in `STATUS.md` (N10).
* Re-run: unit tests, both flavor debug builds, touchpoint guard.
* Record the merge commit and the guard output in `STATUS.md`.
