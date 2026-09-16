# ROADMAP — Hermes fork of Agora

Status legend: `[x]` done (evidence in `STATUS.md`), `[~]` blocked on human setup, `[ ]` todo.

Device verification runs on the `hermes_x86_64` AVD (Pixel 7, API 36, x86_64 Play image with ARM
translation) — the arm64-v8a APK installs and runs there unmodified; see `STATUS.md` for why an
arm64 AVD is impossible on this x86_64 host.

Every `[x]` below was re-verified against the working tree during the resume/verify session — none
was ticked on the strength of a previous claim. `STATUS.md` carries the STATE REPORT and the
evidence table for each phase.

## Phase 0 — Environment & First Build
- [x] JDK 21, cmdline-tools, `platforms;android-36`, `build-tools;36.0.0`,
      `ndk;28.2.13676358`, `cmake;3.22.1`, licenses accepted
      (`sdkmanager --list_installed` lists all four; `java -version` → Temurin 21.0.12.1)
- [x] Fork + clone `--recurse-submodules`; `upstream` remote; `thirdparty/llama.cpp`,
      `thirdparty/proot` non-empty
- [x] `./gradlew assembleFdroidDebug` green (environment fixes only)
- [x] APK installed + launched on device, screenshot proof (`evidence/phase0-6/01-hermes-launched.png`)

## Phase 1 — Fork Hygiene, Rebrand, Sync Automation
- [x] `applicationId` → `com.hermes.app` (touchpoint #1)
- [x] App name "Hermes X" (Phase 12 rename) + adaptive icon via `app/src/fdroid/res` overlay (no upstream edits)
- [x] Release keystore wired through `local.properties`; `assembleFdroidRelease` signs
- [x] `upstream-sync` + `contract-change` labels exist on the fork (N14)
- [x] Local sync dry-run clean (`scripts/upstream_sync.sh`)
- [x] Both Agora-original and Hermes APKs installed side by side (`evidence/phase0-6/05-app-drawer.png`)

## Phase 2 — Gap Analysis & System Mapping
- [x] Every claim cites an existing file path + signature
- [x] Expected touchpoints pre-registered in `UPSTREAM_TOUCHPOINTS.md`
- [x] Autopilot integration design consistent with upstream contracts

## Phase 3 — Autopilot Memory v1
- [x] `ReflectionWorker` (WorkManager: session idle, every 20 messages, debug trigger, battery aware)
- [x] `ReflectionCaller` reusing Agora provider stack, cheapest configured model, silent skip on failure
- [x] Conservative extraction prompt, strict JSON ops schema (`content`, `category`, `confidence`, `source_quote`)
- [x] `MemoryApplier`: snapshot → write → insert into `AdaptationLog`
- [x] Notifier ("N memories updated") + POST_NOTIFICATIONS request
- [x] Controls: master toggle (default ON), daily cap 5, provenance tag in facts
- [x] `androidTest` green: 3 seeded conversations → facts present; undo restores file byte-for-byte
      (3/3 pass on the emulator; `connectedFdroidDebugAndroidTest` exit 0)

## Phase 4 — Adaptation History + Auto-Rollback
- [x] Settings → Adaptation History (list, before/after diff, per-entry Undo, status chips)
- [x] Phase 3 notification tap wired to the screen
- [x] Correction heuristic v1: 2 `feedbackFlags` → auto-rollback + `needs_revision`
- [x] Retention: 50 versions per file or 30 days
- [x] Test proof: circuit breaker rolls back a bad adaptation

## Phase 5 — Autopilot Skills v1
- [x] Candidate detection: ≥3 chained tool calls OR ≥2 user corrections before success
- [x] Synthesis call → skill draft (name, trigger, generalized steps, pitfalls) via `SkillManager`
- [x] Snapshot/rollback parity + same caps and provenance as memory

## Phase 6 — Sync Stress-Test + Handover
- [x] One real upstream merge completed per protocol (stress merge tagged
      `sync-2026-09-14-stress` → `e360491c`; `upstream/master` itself was already an ancestor of `main`)
- [x] `main` fully green: unit tests + both flavor debug builds + touchpoint guard + contract check
- [x] `ROADMAP.md` / `STATUS.md` finalised; handover section written for the future Wear OS phase
      (the separate `HANDOVER.md` was later moved out of the repository)
## Phase 6b — Persona System (Caveman + Ponytail)

> The "Phase 6" label is used twice in this repo's history — this section and the Sync/Handover one
> above were both written as Phase 6 by different sessions. Renamed here to 6b so the numbering is
> unambiguous; the code comments that say "Phase 6 P1/P2/P4/P5/P6" refer to *this* section.

### P2 — vendored sourcing (pinned)

| Persona | Upstream | Ref | Vendored | sha256 |
|---|---|---|---|---|
| Caveman | `github.com/JuliusBrussee/caveman` | `v2.6.0` | `personas/caveman/SKILL.md` | `c4d7354b4b063d54601fcdd5097a5b1713d1a1a2e386ac39efa438aa1ffef8ce` |
| Ponytail | `github.com/DietrichGebert/ponytail` | `v4.10.0` | `personas/ponytail/SKILL.md` | `1316a2f3f95741d2300b116fe0c2d81ce4a9568656ed0a62643f54aaf09957f2` |

Both are MIT at the vendored path (`skills/`; Caveman's `engine/`, `proxy/`, `rewriter/`, `browse/`,
`mcp/`, `shrink/`, `shared/platform/` are BSL-1.1 per its `LICENSING.md` and are **not** vendored).
Machine-readable record: `personas/upstream.lock`. Update only through `scripts/persona_update.sh`,
which refuses to write unless the persona regression tests pass.

## Phase 7 — Wear OS Standalone-Lite

- [x] `wear/` module: `applicationId = "com.hermes.app"` (identical to the phone app), same keystore,
      `minSdk = 30`, Compose for Wear OS, voice-first Q&A + read-only memory snapshot
      (`wear/build.gradle.kts`; `wear/src/main/java/com/newoether/agora/wear/`)
- [~] One-time credential transfer over the Data Layer; standalone thereafter. The transfer and the
      offline queue are implemented and unit-tested; the **live airplane-mode API call needs a real
      provider key** (HS2 registry) and two Data-Layer-paired devices (HS4), neither of which exists
      here. Not claimed.
- [x] Repo hygiene: `wear/` in the guard `ALLOWED`; `settings.gradle.kts` +
      `gradle/libs.versions.toml` registered in `UPSTREAM_TOUCHPOINTS.md`
      (`scripts/touchpoint_guard.sh` → PASS)

## Phase 8–11 — (not used; numbering jumps to Phase 12 in `STATUS.md`)

## Phase 12 — Rename, the three critical findings, and the Wear rebuild
- [x] Launcher label "Hermes X" in every locale; maintainer visible on About (phone) and Debug (watch)
- [x] `SettingsWatchSetupPage` reachable from Settings; real pairing request + ack
- [x] Watch UI told about an incoming config via `WearSignals` (no polling)
- [x] Drain-regression tripwire (`WearMainThreadSentinelTest`)
- See `STATUS.md` §Phase 12 for the evidence table.

## Phase 13 — Update checker, adversarial device audit, and the `content: null` answer
- [x] Update check repointed at the fork (`michaelxdips/Agora`), numeric-segment ordering
- [x] `content: null` no longer rendered as the word "null"
- [x] About links point at the fork's tracker, not upstream's
- See `STATUS.md` §Phase 13 for the evidence table.

## Open (audit v2.1 — see `V2_BACKLOG.md`; the audit documents themselves were moved out of the
repository before publication)

- Persona updates: the lock file is shipped in assets and is now seeded into `filesDir`
  (`PersonaRepository.seedLockFromAssets`) so the in-app check can read it.
- Wire the circuit breaker's `recordInjection`/`recordCorrection` and `SkillSynthesizer` into the
  production path, or move them out of the shipped feature list — they currently have no caller.
- Push the memory snapshot when it changes, not only at setup (`WatchSync.pushMemorySnapshot`).
