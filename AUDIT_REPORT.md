# AUDIT_REPORT — Hermes fork of Agora (Phase 8)

Two passes, per the mandate's two-pass rule. Everything below is a defect **reproduced by running
something**, not a code-reading opinion. Nothing here is inherited from a previous session's claims:
if it is marked fixed, the fix was re-run today.

Severity legend: **CRITICAL** = breaks the build, loses user data, or leaks a credential.
**HIGH** = a feature silently does not do what it promises. **MEDIUM** = wrong behaviour in a real
but narrower case. **LOW** = polish, dead weight, or a documented limitation.

---

## Pass 1 — findings

| # | Severity | Finding | How it was found | Status |
|---|---|---|---|---|
| 1 | **CRITICAL** | `main` did not compile. The Phase 7 phone-side `WatchSync.kt` used `Task.await()` without `kotlinx-coroutines-play-services` on the classpath, and `SettingsWatchSetupPage.kt` assigned a nullable `getEffectiveBaseUrl(...)` to a non-null `String`. `:app:compileFdroidDebugKotlin` failed. | `./gradlew :app:testFdroidDebugUnitTest --rerun-tasks` (5× stability run) — the first run failed to compile | **FIXED** — added `libs.coroutines.play.services`; `.orEmpty()` with a comment explaining that an invalid URL must be rejected by `WearConfig.isValid()` rather than silently becoming the string `"null"` |
| 2 | **CRITICAL** | `assemblePlayDebug` was broken. Phases 6 and 7 added 30 `hermes_*` strings to the **fdroid** overlay only; the `play` flavor referenced the same keys from shared `autopilot` code and had no definitions. | Full gate: `:app:compilePlayDebugKotlin FAILED` with `Unresolved reference 'hermes_personas'` | **FIXED** — both flavors now carry one identical Hermes string set |
| 3 | **HIGH** | First fix for #2 was itself wrong: the strings were moved to `app/src/main/res/values/`. Upstream's own `SettingsResourceContractTest.localizedStringKeysMatchDefaultResources` then failed, because it asserts every locale directory under `main/res` mirrors every key in `main/res/values` — and the 11 upstream locale folders were never going to carry English Hermes UI. | `:app:testFdroidDebugUnitTest` after the move | **FIXED** — strings live in the **flavor overlays** (outside that contract, and upstream-empty paths). Verified the locale dirs still hold exactly their 1 `app_name` key each, so the contract stays satisfied on its own terms |
| 4 | **HIGH** | `PersonaRepository.customText` used `runBlocking` to read DataStore, and its caller is a Compose click handler — a blocking read on the UI thread. | grep sweep for `runBlocking` in Hermes-owned code | **FIXED** — made `customText`/`effectiveText` suspending; the caller now `scope.launch`es |
| 5 | **HIGH** | An unused `kotlinx.coroutines.runBlocking` import in `WearListeners.kt`. | same sweep | **FIXED** — removed |
| 6 | **MEDIUM** | `wear/build/` (800+ artifacts) was committed to git in Phase 7. Upstream's `.gitignore` uses `/build` (root-anchored) plus `app/.gitignore`, so a **new** Gradle module's build tree is matched by neither. | `git status` after the Phase 7 commit showed `A wear/build/...` | **FIXED** — `git rm -r --cached wear/build`, plus a module-wide `build/` rule. `git check-ignore -v wear/build` → `.gitignore:43:build/` |
| 7 | **HIGH** | `:wear:assembleRelease` failed. `lintVitalRelease` reported `InvalidFragmentVersionForActivityResult`: `play-services-basement:18.4.0` (via `play-services-wearable`) drags in `androidx.fragment:1.1.0`, below the 1.3.0 floor the ActivityResult APIs require. | `./gradlew :wear:assembleRelease` | **FIXED** — constrained `androidx.fragment:fragment:1.8.5`. See "A dismissed mistake" below |
| 8 | **LOW** | `WearCoreContext` normalised the core context but the watch's `Debug` panel read the **raw** snapshot length, so the two numbers described different things. | reading the debug path against its own comment | open, cosmetic — the panel is a debug surface and the numbers are both labelled |

| 15 | **HIGH** | The primary Phase 3 instrumented verification was **non-hermetic**: it used the app's real `adaptation_log`, and the engine's daily cap counts every row written today. Rows it wrote persisted, so each run ate real budget: `RUN 1 OK` → `expected:<3> but was:<2>` → `<0>` → `<0>`. A device where the app had actually been used failed on the first run, and the suite only failed once the log had accumulated — an isolated run passed. | re-ran the same test 4× with `am instrument` on an unchanged device (no wipe, no reinstall) | **FIXED** — baseline the day's row count, measure only this test's rows, delete them in cleanup and assert the count returns to baseline. Re-verified **5/5 OK** on the same device data |

### Phase 6 defects (found and fixed inside that phase, listed here for the audit trail)

| # | Severity | Finding | How it was found | Status |
|---|---|---|---|---|
| 9 | **HIGH** | `PersonaStore.removeBlock` `trim()`-ed its result, so switching a persona off silently rewrote a file the user owned — a trailing newline vanished. Not byte-exact removal. | the **on-device** instrumented test; the JVM tests had not covered a trailing newline | **FIXED** — the separator is now part of the removed region, so `upsertBlock`/`removeBlock` are exact inverses; three regression tests added |
| 10 | **MEDIUM** | An **unterminated** persona block (START with no END — the shape a process kill leaves) was treated as absent, so the next write appended a duplicate copy into the prompt. | `PersonaApplierTest` "reconcile heals a store whose block lost its end marker" | **FIXED** — unterminated blocks are removed to the next persona marker or the end of the text |
| 11 | **MEDIUM** | `indexOf` returning `-1` won a `minOrNull`, throwing `StringIndexOutOfBoundsException` on the self-heal path. | the same test, with the stack trace naming `PersonaStore.removeBlock:69` | **FIXED** — negative indices filtered |
| 12 | **HIGH** | Master autopilot OFF did **not** strip personas. The strip only ran at next app start, so a user who switched the autopilot off still got persona-shaped replies. | on-device: master off → `cat files/active_memory.md` still held 4 markers | **FIXED** — the strip moved into the toggle's own `LaunchedEffect`. Re-verified on device: 4 markers → 0 |
| 13 | **HIGH** | `WearCoreContext.clean` filtered persona marker **lines**, leaving the rule text between them in the watch's derived system prompt — spending the watch's 500-token budget on instructions the watch app does not follow. | `WearCoreContextTest` "persona blocks never reach the watch prompt" | **FIXED** — whole blocks are removed, unterminated ones included |
| 14 | **HIGH** | Master-off strip was additionally not wired at all on the persona page (only reconcile-at-startup), so the state could disagree between screens. | the same device run | **FIXED** — one write path (`PersonaApplier.reconcile`) now serves toggle, master-off and startup |

### A dismissed mistake worth recording

The first fix attempted for #7 was `wear/lint.xml` suppressing
`InvalidFragmentVersionForActivityResult`, with a comment asserting the module had no Fragment
classpath at all. **That assertion was false.** `./gradlew :wear:dependencies --configuration
releaseRuntimeClasspath | grep androidx.fragment` printed `androidx.fragment:fragment:1.1.0 -> 1.1.0`,
pulled by `play-services-basement`. The suppression was deleted and the real version floor pinned.
Recorded because "suppress and write a confident comment" is exactly how a real defect becomes
permanent — the comment was more convincing than the check, and only re-running the command caught it.

---

## Pass 2 — adversarial re-read, as a new auditor who distrusts pass 1

Pass 2 was run as a separate sweep rather than a re-run of the same commands. What it targeted and
what it found:

| Area | Adversarial case | Result |
|---|---|---|
| Persona removal completeness | toggle idempotency, kill mid-write (unterminated block), stale block, removal byte-exactness, removal leaves user bytes intact | covered by 13 `PersonaStoreTest` + 10 `PersonaApplierTest` assertions; **2 real defects found** (#9, #10) and fixed before pass 2 |
| P5 isolation | persona text in the reflection prompt, the synthesis prompt, and mid-transcript | `PersonaIsolationTest` (5 tests) — clean in all three; the user-facing channel still carries the persona (asserted, so isolation is not achieved by never injecting) |
| Persona cost honesty | is the reported number measured or quoted? | measured with the app's own `ContextTokenEstimator`, printed by `PersonaFidelityTest`. **Output-token reduction is deliberately NOT reported** — it needs HS2 |
| Watch offline queue | corrupt file, unknown id, attempt ceiling, reopen after restart, complete-twice | 7 `WearOfflineQueueTest` tests, 0 failures; corrupt file reads as empty and stays usable |
| Watch core context | 400-line snapshot (truncation), whole-line guarantee, CRLF, blank runs, persona leakage | 7 `WearCoreContextTest` tests, 0 failures |
| Upstream contract drift | rename a symbol upstream depends on, see whether anything fails | `UpstreamContractSentinelTest` proven to **fail** on a real drift: renaming `active_memory.md` in `MemoryManager` produced `active memory still lives at filesDir slash active_memory_dot_md FAILED`; the file was restored byte-exact (`git diff --stat` empty) |
| Guard adversarial | sneak an unregistered upstream edit and a missing marker past the guard | the guard caught **both, against the author**: `FAIL … SettingsScreen.kt changed 23 lines (budget 18)` and `FAIL … GenerationRequestBuilder.kt is registered but has no 'HERMES INTEGRATION POINT' marker`. Both fixed, guard green |
| Secret hygiene | is the watch API key readable at rest? | `grep -c "sk-test-key"` over `files/hermes_wear_config.bin` → **0**; AES-256-GCM, key in the Android keystore, IV prefixed per blob |
| Secret hygiene (repo) | any secret in git history? | guard §3 passes; `git log --all --diff-filter=A --name-only` has no `local.properties` / `*.jks` / `*.keystore` |
| Build stability | full suite 5× consecutive, one flake = one finding | see the 5× row below |
| Flavor parity | do both flavors compile the shared Hermes code? | **failed on the first attempt** (#2, #3) and passes after the fix |

### Two things pass 2 could not test, stated as limitations rather than assumed away

1. **Live provider calls.** The reflection round-trip, the watch's end-to-end voice loop, and the
   Phase 6 P6 output-token measurement all need a real API key (HS2, deferred by the owner). Every
   surrounding path is exercised with injected or absent credentials; the model call itself is not.
   No number in this report is estimated.
2. **Phone↔watch Data Layer transfer.** Both sides are implemented and the watch's independent paths
   are verified on a real API 34 image, but the two emulators are not Data-Layer-paired, so the actual
   `putDataItem` delivery is not proven end-to-end (HS4).

---

## Gate results (Phase 8, all re-run today on this machine)

| Gate | Result | Evidence |
|---|---|---|
| `:app:testFdroidDebugUnitTest` | **PASS** 2,527 tests, 0 failures, 0 errors (389 classes; 3 skipped are upstream's own) | `app/build/test-results/testFdroidDebugUnitTest/*.xml` |
| `:app:testPlayDebugUnitTest` | **PASS** 2,510 tests, 0 failures, 0 errors (385 classes) | flavor parity: the play tree compiles and runs the shared Hermes code |
| `:wear:testDebugUnitTest` | **PASS** 14 tests, 0 failures | `wear/build/test-results/testDebugUnitTest/*.xml` |
| `:app:connectedFdroidDebugAndroidTest` | **PASS** 6/6 tests on `hermes_x86_64(AVD) - 16` (real device, real `filesDir`, real Room DB) | `Starting 6 tests … Finished 6 tests … BUILD SUCCESSFUL` |
| Instrumented stability | **PASS** 5/5 consecutive runs on the SAME device data | was `OK → 2 → 0 → 0` before the fix; see finding #15 |
| Unit-test stability | **PASS** 5/5 consecutive `--rerun-tasks` runs, no flake | 5m57s / 4m28s / 4m10s / 4m13s / 5m10s, all `BUILD SUCCESSFUL` |
| `:app:assembleFdroidDebug` | **PASS** | `app-fdroid-debug.apk` 65,285,032 B |
| `:app:assemblePlayDebug` | **PASS** | `app-play-debug.apk` 65,224,484 B |
| `:wear:assembleRelease` signed | **PASS** | `wear-release.apk` 2,685,504 B; `apksigner verify --print-certs` → `CN=Hermes Local` |
| `touchpoint_guard.sh` | **PASS** | 7/7 registered touchpoints within budget; `PASS (914e7c8…)` |
| `upstream_sync.sh --dry-run` | **PASS** | `already up to date with upstream/master — nothing to merge` |
| Secrets in git history | **PASS** | guard §3; watch config blob holds no plaintext key |

### Finding #15 — the instrumented test was non-hermetic (found by repeat-running it)

`AutopilotMemoryInstrumentedTest` used the app's **real** `adaptation_log`, and the engine's daily cap
counts every row written today, globally. Rows the test wrote persisted, so each run permanently ate
budget. The sequence, on one unchanged device: `RUN 1 OK (1 test)` → `expected:<3> but was:<2>` →
`expected:<3> but was:<0>` → `<0>`. Isolated runs passed; the suite only failed once the log had
accumulated.

This is a **test** defect, not a product one — the cap behaved exactly as designed — but it meant the
primary Phase 3 verification could not be trusted on any device where the app had actually been used.
The fix: take the day's row count as a baseline before the pass, have the stub measure only the rows
it caused, and delete those rows in cleanup with `assertEquals(baseline, countSince(...))` asserting
the log is left as found. Re-verified: **5/5 OK with no wipe and no reinstall between runs**.

| Requirement | State |
|---|---|
| All phase exits proven in `STATUS.md` | Phases 0–7 recorded with command output and artifact paths; Phase 8 is this file |
| Personas toggleable, upstream-sourced, updatable, isolated, removable without trace | proven, and the removability was proven **on device** (0 bytes left) |
| Wear standalone proven with the phone in airplane mode | **not fully**: the watch is proven to launch and configure with no phone, but the live call needs HS2 |
| `main` fully green (all builds + all tests + sentinel in sync gates + guard + sync dry-run) | see the gate table below |
| Zero open CRITICAL/HIGH | after fixes: 0 open CRITICAL, 0 open HIGH. #8 is LOW and open by choice |
| Zero secrets in git history | verified |
| Tags pushed | `v1.0-hermes` pushed; `v1.1-wear` / `v1.2-hardened` / `v2.0-hermes` pending the gate |

---

## Honest summary

The code in Phases 3–5 arrived in good shape. Phases 6 and 7 — the ones built in this session — did
**not**: eight of the fourteen findings are mine, including two CRITICALs that left `main` unable to
build and the play flavor unable to compile at all. Every one of them was found by *running* the
thing, and three of them (`trim()`, the unterminated block, the watch core-context leak) were found
only by a test that the earlier reading-pass had considered unnecessary.

The pattern is consistent and worth naming: **the block is the unit, not the marker line.** Marker
handling that treats markers as lines produced two independent bugs in two different modules.
