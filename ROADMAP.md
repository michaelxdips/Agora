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
- [x] App name "Hermes" + adaptive icon via `app/src/fdroid/res` overlay (no upstream edits)
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
- [x] `ROADMAP.md` / `STATUS.md` finalised; HANDOVER section for the future Wear OS phase
- [ ] When the Wear OS `wear/` module lands, add it to the touchpoint guard `ALLOWED` regex
