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

## Pass 3 — full scan for bugs, optimisation and missing features (post-Phase-8)

Ran after the owner asked for a harder scan. Six more defects, **five of them in code this fork
wrote**, plus the finding that matters most: **two of the existing test suites were structurally
incapable of catching the bug they were supposed to cover.**

| # | Sev | Finding | How found | Status |
|---|---|---|---|---|
| 16 | **CRITICAL** | **"Check for persona updates" could never work.** `PersonaUpdater.fetch` shelled out to `ProcessBuilder("curl", …)`. Android ships no curl — `adb shell curl` → `inaccessible or not found` — so every call threw `IOException`, `runCatching` swallowed it, and the UI reported **"fetch failed (offline?)"** on a device with perfect connectivity, forever. | `adb shell which curl` on the API-34 image, then comparing with the code path | **FIXED** — OkHttp (already a 5.3.2 dependency; no new dep). Verified on device: 6/6 instrumented tests including the exact-path assertion |
| 17 | **HIGH** | **The JVM test for #16 did not catch #16.** With the buggy `ProcessBuilder` version deliberately restored, `PersonaUpdaterTest` still passed **6/6** — because the developer *host* ships curl, so the subprocess succeeded on the test machine and failed only on the device. This is precisely why the bug survived a phase that had claimed "fetch verified". | restored the bug, re-ran, watched it pass anyway | **FIXED** — the load-bearing coverage moved to an **instrumented** test that asserts the request reaches a loopback socket and that the device has no curl. Proven RED on the buggy code (2 failures on both devices), GREEN after |
| 18 | **HIGH** | **`WearChatClient` had zero tests** — the watch's URL building, response parsing and every failure path were asserted by nothing. Writing the suite immediately found #19 and #20. | `ls wear/src/test/` → 2 files, neither touching the client | **FIXED** — 17 new tests. Wear suite 14 → **31** |
| 19 | **HIGH** | **`ask()` violated its own "never throws" contract.** The catch block **re-threw** `WearChatException`, so a transport failure escaped the `Result` and crashed past the caller's `result.fold(...)` — meaning the **offline queue never received the question**. Offline is a normal watch state; the feature built for it was unreachable. | the new tests asserted `result.isFailure` and got a thrown exception instead | **FIXED** — returns `Result.failure`. The queue path is now the one that runs |
| 20 | **MEDIUM** | **Failure messages were useless.** A bare `catch (Throwable)` caught the deliberately-thrown `WearChatException` and replaced its message with the class name, so every watch error read **"WearChatException"**. The user saw nothing actionable. | the new tests asserted `"HTTP 401"` and got `"WearChatException"` | **FIXED** — typed catch first, specific message preserved; unknown throwables still summarised by class name so a URL or key cannot leak |
| 21 | **MEDIUM** | **`127.0.0.1` was rejected while `localhost` was accepted.** The HTTPS-only allowlist listed `localhost` and `10.0.2.2` but not the loopback literal, so BYOK pointed at `http://127.0.0.1:11434` (the common way to write it) failed validation with "Need an https base URL, key and model" and no hint why. | the new validation test | **FIXED** — `127.0.0.1` accepted, rationale documented |
| 22 | **HIGH** | **Cancellation swallowed in 8 places.** `catch (Exception)` / `runCatching` / `recoverCatching` catch `CancellationException` too, so a cancelled reflection pass kept writing memory, a cancelled undo reported "undo failed", a cancelled watch push reported "no watch reachable", and a cancelled persona reconcile reported a broken persona. The phone's `ReflectionCaller`/`ReflectionWorker` already rethrew correctly — the rest of the codebase did not. | grep sweep for catch blocks with no `CancellationException` branch | **FIXED** — 8 sites (ReflectionEngine, MemoryApplier, SkillSynthesizer, PersonaReconcileWorker, WatchSync ×3, WearChatClient) now rethrow. `PersonaApplier` was already correct |

### The pattern worth naming

Both CRITICAL-class findings in this pass were **"works on the dev machine, impossible on the
device"**: a subprocess binary that exists on Windows and not on Android, and a test suite whose
platform made the bug invisible. Neither could be found by reading the code, and neither was found
by the tests that existed. Both were found by running the thing on the real target.

### Optimisation (one real, two measured-and-rejected)

| Change | Verdict |
|---|---|
| **`OkHttpClient` was rebuilt per question** — `send()` constructs a `WearChatClient` each call, and each held its own connection pool and dispatcher threads. On a 2 GB watch that is a leak that surfaces as latency. Now one shared client per process, so a follow-up question reuses the TLS session. | **DONE** |
| Watch release APK 2.65 MB vs 39 MB debug — R8 + resource shrinking + locale filter already working; the watch module has no images, no Room, no llama.cpp. | already optimal, left alone |
| `WearOfflineQueue` is file-backed JSON rather than Room — correct at "a few dozen short strings". | already right, documented as `ponytail:` |

### Gate after Pass 3

| Gate | Result |
|---|---|
| `:app:testFdroidDebugUnitTest` | **PASS** 2,533 tests, 0 failures |
| `:app:testPlayDebugUnitTest` | **PASS** 2,516 tests, 0 failures |
| `:wear:testDebugUnitTest` | **PASS** 31 tests, 0 failures (was 14) |
| `:app:connectedFdroidDebugAndroidTest` | **PASS** 12/12 on BOTH `hermes_x86_64` and `hermes_wear5` (was 6) |
| `:app:assembleFdroidDebug` / `:app:assemblePlayDebug` / `:wear:assembleRelease` | **PASS** — 65,860,308 B / 65,799,760 B / 2,652,736 B |
| `touchpoint_guard.sh` | **PASS** |

### Verified on the minimum supported device — API 30 / Wear OS 3

The `hermes_wear` AVD is **API 30 (Wear OS 3, Android 11)**, which is exactly the module's `minSdk`.
Nothing had ever been run there; every wear check until now used API 34. Ran the signed **release**
APK on it:

| Check | Result |
|---|---|
| `:wear:assembleRelease` APK installs on API 30 | **Success** |
| App launches | `am start` OK, **no crash** — `logcat` clean of `FATAL EXCEPTION`, `NoClassDefFoundError`, `NoSuchMethodError`, `VerifyError` |
| Wear M3 layout renders | `Hermes setup` → `Base URL` → `Tap to type`, same bounds as API 34 |
| BYOK driven end-to-end through the real UI | **3/3 fields OK** → Save key → chat screen (`Hermes`, `Type a question`, `Send`) |
| Chat controls reachable | scroll → `Send`, `Speak`, `Debug` |
| Design review | "Proper Material Wear OS style. Uses Material 3 Wear OS design tokens" |

One false alarm worth recording, because it recurred: the screenshot review claimed the `Send`
button was "clipped by the circular screen edge". It is not — `Send` occupies `[69,301][139,335]`,
whose furthest corner is **189 px** from the centre against a **192 px** radius, i.e. inside the
round area, and scrolling reaches `Speak` and `Debug` below it. **Screenshot review has now twice
reported a scroll fold as clipping.** Trust `uiautomator` bounds over the vision pass for layout
questions; the vision pass is useful for colour, surface and typography, not for geometry.

The first BYOK run on this AVD reported `Base URL: NOT LANDED` and a field containing
`By by by by…`. That was **timing**, not a bug: the script typed while the freshly booted emulator
was still settling. Manual typing of `abc`, then `:/.-`, then the full URL each landed exactly, and
re-running the script gave 3/3.

### Coverage limitation found while adding API 30 (documented, not hidden)

`:app:connectedFdroidDebugAndroidTest` **cannot run on the API-30 wear AVD.** The app module pins
`abiFilters += listOf("arm64-v8a")` (it carries the llama.cpp and proot native libs), while
`hermes_wear` is a 32-bit `sdk_gwear_x86` image, so the instrumented APK is filtered out and Gradle
silently reports only the other two devices. The **wear** module has no `abiFilters` and no native
code, which is why the release APK installs there fine.

Consequence, stated plainly: the connected suite covers **API 34 (wear) and API 36 (phone)**, and
API 30 is covered by the manual release-APK run above — not by the automated gate. An arm64 wear
image for API 30 does exist on the host but has not been exercised; on an arm64 host it would close
this gap.

| Item | Why |
|---|---|
| Live provider call (reflection round-trip, watch voice loop, P6 output-token measurement) | needs a real API key (HS2, owner-deferred). No number in any report is estimated |
| Phone↔watch Data Layer transfer end-to-end | both sides implemented and the watch's independent paths verified on a real API-34 image, but the two emulators are not Data-Layer-paired (HS4) |

---

## Recommended features — evidence-based, ranked

Every item below is stated with **what exists today** (verified by grep, above) and **why the gap
matters**. Nothing here duplicates upstream: conversation fork/regenerate/edit-message, Room,
`SkillManager`, MCP and auto-backup already exist in the phone module and are not proposed again.

### P0 — the watch is unusable offline, which is the state it is designed for

| Feature | Evidence of the gap | Why it matters |
|---|---|---|
| **Auto-drain the offline queue on launch and on connectivity regain** | `drainQueue` has exactly **one** call site: the success branch of `send()`. Opening the app does not send anything. A question held offline waits for the user to ask a *second* question that *also* succeeds — and if it succeeds, the queued answer overwrites the visible one. | The watch's entire premise is "it loses connectivity constantly". A held question that is never auto-sent is a silently dropped question. This is the highest-value small change in the fork. |
| **Surface the queued questions in the UI, and offer retry/discard** | the badge exists (`"$queued held offline"`) but is read-only; the queue auto-drops an entry after 3 failed attempts with only a log line. | The user cannot tell what is held or force a send. Silent loss of a question the user believes was asked is worse than a visible failure. |

### P1 — the watch surface that Wear OS 5/7 expects and this app does not provide

| Feature | Evidence of the gap | Why it matters |
|---|---|---|
| **Tile** (`SuspendingTileService` + ProtoLayout) | grep for `TileService`/`ProtoLayout`/`Glance` across `wear/src` → **zero hits**. Manifest has only the activity and two `DATA_CHANGED` listeners. | A Tile is the documented Wear OS way to make an action one swipe from the watch face. For a question-answering app the obvious Tile is a tap-to-talk button — it removes the app-launch step entirely, which on a 384 px screen is most of the interaction cost. |
| **Complication data source** | same grep → zero hits | A complication is the documented surface for "one highly glanceable unit of information" — here: whether the watch is configured and whether anything is held offline. Also the mechanism that makes the app reachable from any watch face. |
| **Ongoing notification + `OngoingActivity` for a long answer** | `WearChatClient` uses `readTimeout(60s)`; a watch screen times out in roughly 5–15 s. | A slow provider means the screen sleeps before the answer arrives. Wear's documented pattern for "background task visible to the user" is an ongoing notification paired with an ongoing activity; without it the app looks like it hung. |

### P2 — honest numbers and continuity

| Feature | Evidence of the gap | Why it matters |
|---|---|---|
| **Real token/usage reporting from the provider response** | the watch never parses `usage`; `WearCoreContext.estimateTokens` is a documented ~4-chars-per-token estimate. Upstream already ships `ContextTokenEstimator`, and `PersonaCostReport` deliberately reports input cost only. | The persona work leans on an HONEST-NUMBERS doctrine. A provider's reported usage is the only way to replace the estimate with a measurement — and it is the missing half of the P6 output-token measurement blocked on HS2. |
| **Conversation history on the watch** | `WearMainActivity` holds one `answer` string in memory; nothing persists. | "Ask on the watch, continue on the phone" is what both Apple's and Google's 2026 assistants do (verified in this pass's research). Today a watch answer cannot even be re-read after a restart. |
| **Tighter default `readTimeout`** | 60 s is longer than any watch interaction; the screen is gone long before. | Either the timeout should match the interaction window, or the ongoing-notification path above must exist. A 60 s block with no visible progress is indistinguishable from a freeze. |

### Explicitly NOT recommended

| Idea | Why not |
|---|---|
| Streaming responses on the watch | already reasoned and documented in `WearChatClient`: small screen, short answers, and a streaming connection to babysit across wrist-down events for no visible benefit. Adding it would contradict a decision that is still right. |
| Room, llama.cpp, conversation trees, MCP, image generation on the watch | owner's standing constraint; the module is 2.65 MB release precisely because these are absent. |
| Rotating the wear API key | `WearCrypto` documents rotation as additive once the threat model grows past "lost watch". Not justified by anything found in this scan. |

---

## Pass 4 — P0 implemented, and the bug that only the implementation could find

The owner asked for the recommended features to be added, not just listed. P0 (the watch's offline
queue) is now **implemented and proven on the device**. Doing it surfaced a defect that no amount of
reading had caught.

### The P0 defect, restated precisely

`drainQueue` had exactly **one** call site: the success branch of `send()`. So a question held while
offline was delivered only if the user asked a *second* question that *also* succeeded — and the
drained answer then **overwrote** the fresh one. A held question nobody re-asks was silently
dropped, on a device whose entire premise is losing connectivity.

### Three fixes, each found by running the thing

| # | Fix | How it was actually found |
|---|---|---|
| 23 | `drainQueue` takes `showResult`. `send()` passes **false** (the queue holds the oldest questions, so a drained answer must not replace the answer the user just asked for); launch passes **true**. | reading the call site while wiring the launch drain |
| 24 | **Launch drains at all.** The first implementation wrote `if (ready)`, reading the Compose state variable it had *just assigned* in the same `LaunchedEffect` — a read that is not visible in that composition pass, so the branch was **always false** and the drain never ran. Now reads a local val. | the device proof: `launch drain check: configured=true queue=1` logged, then nothing happened at all |
| 25 | **The localhost escape hatch never worked.** `WearConfig.isValid()` accepts `http://127.0.0.1` / `localhost` / `10.0.2.2`, but the module declared **no `networkSecurityConfig`**, so Android's cleartext default blocked every one of them and OkHttp surfaced a bare `UnknownServiceException` — the *platform* refusing, not the server, with nothing to tell the user which. Fixed with a network security config permitting cleartext for **loopback and 10.0.2.2 only**; every other host stays TLS-required, so a mistyped real URL cannot send the bearer key in clear. Deliberately **not** `usesCleartextTraffic="true"`, which the phone module sets globally. | the drain failed with `UnknownServiceException`; the phone manifest was checked and has `usesCleartextTraffic="true"` while the wear manifest had nothing |

Finding 25 is the significant one: it means the dev escape hatch validated in #21 was **unusable on
device**, and no test — JVM or instrumented — covered it, because nothing had ever pointed the watch
at a real loopback server. That took a mock provider and a live emulator to expose.

### The proof (re-runnable, not a one-off)

`_tools/mock_provider.py` — a mock OpenAI-compatible endpoint on the host with a `/__fail` toggle,
reached from the emulator through `adb reverse`. `_tools/p0_autodrain_proof.py` drives the real app:

```
STEP 1: configure BYOK against the mock provider      -> configured
STEP 2: mock returns 503, ask a question              -> 'Offline — held', queue size 1
STEP 3: mock back to 200, force-stop, relaunch, touch nothing
  screen after relaunch: ['Hermes', 'MOCK-ANSWER to: held-question-alpha', 'Type a question']
  queue after relaunch: 0
  mock received: ['held-question-alpha']
RESULT: PASS — held question delivered on launch, no user action, queue empty
```

Device log, same run:
```
W HermesWear: launch drain check: configured=true queue=1
W HermesWear: launch drain: delivered=1, queue now 0
```

This also closes **part** of the HS2 gap honestly: the watch's real OkHttp path, request shape,
response parsing and offline queue now run against a live server end-to-end. What it still does not
prove is any *specific provider's* behaviour — that remains HS2.

### A test-harness trap worth recording

The first run of the proof reported `queue size: 0` on a release APK and looked like a second bug.
It was the harness: `run-as` cannot read a **release** APK's data directory
(`package not debuggable`), so every queue read returned 0. The proof now runs against the **debug**
APK, where `run-as` works. Worth noting because "the measurement returned zero" and "the thing is
broken" look identical, and only checking *why* the measurement failed told them apart.

---

## Honest summary

The code in Phases 3–5 arrived in good shape. Phases 6 and 7 — the ones built in this session — did
**not**: eight of the fourteen findings are mine, including two CRITICALs that left `main` unable to
build and the play flavor unable to compile at all. Every one of them was found by *running* the
thing, and three of them (`trim()`, the unterminated block, the watch core-context leak) were found
only by a test that the earlier reading-pass had considered unnecessary.

The pattern is consistent and worth naming: **the block is the unit, not the marker line.** Marker
handling that treats markers as lines produced two independent bugs in two different modules.
