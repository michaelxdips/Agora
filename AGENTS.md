# AGENTS.md — Hermes fork of Agora

This repository is a **governed fork** of [`newo-ether/Agora`](https://github.com/newo-ether/Agora).
Read this file before touching anything.

## Ground rules

1. **`main` must always build.** Work on feature branches, merge only after verification. (N7)
2. **Upstream files are read-mostly.** Any edit to a file that also exists upstream MUST be
   registered in `UPSTREAM_TOUCHPOINTS.md` (the guard parses that file as data) and the edited
   site marked with a `HERMES INTEGRATION POINT` comment. (N8)
3. **New code lives in Hermes-only paths**, currently:
   * `app/src/main/java/com/newoether/agora/autopilot/` (feature code)
   * `app/src/test/java/com/newoether/agora/autopilot/` (unit tests)
   * `app/src/androidTest/java/com/newoether/agora/autopilot/` (instrumented tests)
   * `scripts/touchpoint_guard.sh`, `scripts/upstream_sync.sh`,
     `.github/workflows/upstream-sync.yml`, `*.md` at repo root.
4. **Small conventional commits** prefixed `hermes:`. Never commit secrets. (N9)
5. **Never disable tests/guards, force-push, or delete data to pass a gate.** (N11)
6. **Evidence standard:** every phase exit attaches proof to `STATUS.md` — command output tails,
   test report paths, APK hashes. No proof = phase not done. (N12)
7. **Compliance:** never modify upstream `LICENSE`; attribution lives in `NOTICE.md`. (N13)
8. If an upstream sync changes `development/*.md` or `ARCHITECTURE.md`, re-read those documents
   BEFORE further feature work and note it in `STATUS.md`. (N10)

## Local (never committed) files

`local.properties`, `*.jks`, `*.keystore` are ignored via `.git/info/exclude`
(`hermes-local-secrets` block) — they hold the SDK path and the signing key. They must never
appear in git history; `scripts/touchpoint_guard.sh` asserts this.

## Sync protocol

See `UPSTREAM_SYNC.md`. Run `bash scripts/upstream_sync.sh` (dry-run is the default when
`SYNC_DRY_RUN=1`), then `bash scripts/touchpoint_guard.sh` before pushing.

## Failure policy

* Transient failures: retry 3x with backoff.
* Blocked > 30 minutes: write `BLOCKED.md` (what, why, options), take the most conservative safe
  option, else stop with `main` green.
* A stopped agent with a green `main` is a success. A "finished" agent with a broken `main` is a
  failure.
