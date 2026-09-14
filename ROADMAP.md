# ROADMAP — Hermes fork of Agora

Status legend: `[x]` done (evidence in `STATUS.md`), `[~]` blocked on human setup, `[ ]` todo.

## Phase 0 — Environment & First Build
- [ ] JDK 21, cmdline-tools, `platforms;android-36`, `build-tools;36.0.0`,
      `ndk;28.2.13676358`, `cmake;3.22.1`, licenses accepted
- [ ] Fork + clone `--recurse-submodules`; `upstream` remote; `thirdparty/llama.cpp`,
      `thirdparty/proot` non-empty
- [ ] `./gradlew assembleFdroidDebug` green (environment fixes only)
- [~] APK installed + launched on device, screenshot proof (needs **HS1**: physical device)

## Phase 1 — Fork Hygiene, Rebrand, Sync Automation
- [ ] `applicationId` → `com.hermes.app` (touchpoint #1)
- [ ] App name "Hermes" + adaptive icon via `app/src/fdroid/res` overlay (no upstream edits)
- [ ] Release keystore wired through `local.properties`; `assembleFdroidRelease` signs
- [ ] `upstream-sync` + `contract-change` labels exist on the fork (N14)
- [ ] Local sync dry-run clean (`scripts/upstream_sync.sh`)
- [~] Both Agora-original and Hermes APKs installed side by side (needs **HS1**)

## Phase 2 — Gap Analysis & System Mapping
- [ ] Every claim cites an existing file path + signature
- [ ] Expected touchpoints pre-registered in `UPSTREAM_TOUCHPOINTS.md`
- [ ] Autopilot integration design consistent with upstream contracts

## Phase 3 — Autopilot Memory v1
- [ ] `ReflectionWorker` (WorkManager: session idle, every 20 messages, debug trigger, battery aware)
- [ ] `ReflectionCaller` reusing Agora provider stack, cheapest configured model, silent skip on failure
- [ ] Conservative extraction prompt, strict JSON ops schema (`content`, `category`, `confidence`, `source_quote`)
- [ ] `MemoryApplier`: snapshot → write → insert into `AdaptationLog`
- [ ] Notifier ("N memories updated") + POST_NOTIFICATIONS request
- [ ] Controls: master toggle (default ON), daily cap 5, provenance tag in facts
- [ ] `androidTest` green: 3 seeded conversations → facts present; undo restores file byte-for-byte

## Phase 4 — Adaptation History + Auto-Rollback
- [ ] Settings → Adaptation History (list, before/after diff, per-entry Undo, status chips)
- [ ] Phase 3 notification tap wired to the screen
- [ ] Correction heuristic v1: 2 `feedbackFlags` → auto-rollback + `needs_revision`
- [ ] Retention: 50 versions per file or 30 days
- [ ] Test proof: circuit breaker rolls back a bad adaptation

## Phase 5 — Autopilot Skills v1
- [ ] Candidate detection: ≥3 chained tool calls OR ≥2 user corrections before success
- [ ] Synthesis call → skill draft (name, trigger, generalized steps, pitfalls) via `SkillManager`
- [ ] Snapshot/rollback parity + same caps and provenance as memory

## Phase 6 — Sync Stress-Test + Handover
- [ ] One real upstream merge completed per protocol
- [ ] `main` fully green: unit tests + both flavor debug builds + touchpoint guard + contract check
- [ ] `ROADMAP.md` / `STATUS.md` finalised; HANDOVER section for the future Wear OS phase
- [ ] When the Wear OS `wear/` module lands, add it to the touchpoint guard `ALLOWED` regex
