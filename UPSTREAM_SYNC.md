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

## The other two gates

Two scripts answer questions the sync itself cannot, and both are safe to run at any time:

```bash
bash scripts/verify_release_provenance.sh v3.0.3   # before publishing, or to audit a published tag
bash scripts/gen_code_map.sh --check               # CODE_MAP.md is generated; this fails when stale
```

`verify_release_provenance.sh` checks the four claims a release makes — both APKs signed by the same
certificate, assets matching `SHA256SUMS`, the tag's commit built green, and a test job run on that
commit. It needs `gh` (authenticated) and `apksigner` (from the SDK; set `ANDROID_HOME`). An
unverifiable claim is reported as `SKIP` **and counted as a failure**, so a machine without
`apksigner` cannot produce a false PASS.

`gen_code_map.sh` rewrites everything below `<!-- CODE_MAP:GENERATED -->` in `CODE_MAP.md` from the
tree and from this repo's guard registry. The narrative above the marker is hand-written and is never
touched. `--check` exits 1 when the generated section is stale, which is what makes the file
trustworthy rather than aspirational.

## Signing

Release builds are **fail-closed**: `assembleFdroidRelease` / `:wear:assembleRelease` with no keystore
in `local.properties` fail at configuration time with a sentence naming the file. They used to fall
back to the debug key, which produced an artifact that looked like a release, installed over a debug
build, and could not be updated by the real one.
