# STATUS — Hermes fork of Agora

Owner: Michael (`michaelxdips`) · Fork of [`newo-ether/Agora`](https://github.com/newo-ether/Agora) · Licence: MIT

Evidence standard (N12): every phase exit carries command output / artifact paths below.
No proof = phase not done. Human-setup items are marked **[HS]** and never block progress (N6).

---

## STATE REPORT — reconstruction of Phases 0–6 (Step 1, before any work)

Written at the start of the resume/verify session, from repository state only. Repo root
`C:\Users\Michael\Documents\Chatapp\Agora`, branch `main`, HEAD `4f1babff`, tree clean except
untracked `HANDOVER.md` (inspected, coherent, committed as part of this session).

| Phase | Verdict at reconstruction | What was missing |
|---|---|---|
| 0 — Environment & First Build | **VERIFIED-DONE** | JDK 21.0.12.1, SDK `platforms;android-36` / `build-tools;36.0.0` / `ndk;28.2.13676358` / `cmake;3.22.1` all installed; `thirdparty/llama.cpp` (58), `thirdparty/proot` (10), `thirdparty/talloc` (4) non-empty; `assembleFdroidDebug` green; APK installed **and launched** on the `hermes_x86_64` emulator with screenshot proof |
| 1 — Fork Hygiene, Rebrand, Sync Automation | **VERIFIED-DONE** | `applicationId = "com.hermes.app"` (touchpoint #1); 15 overlay files under `app/src/fdroid/res/` (all 12 upstream `app_name` locales covered); keystore + `local.properties` git-excluded; both labels live; sync dry-run exit 0; Hermes and upstream Agora installed and launched side by side on the emulator |
| 2 — GAP_ANALYSIS.md | **VERIFIED-DONE** | Nothing. `GAP_ANALYSIS.md` exists and every cited path was re-verified against the tree this session |
| 3 — Autopilot Memory v1 | **VERIFIED-DONE** | Sources, triggers, notifier, caps, provenance present; 48 autopilot JVM tests green; **3/3 instrumented tests pass on the emulator**, including the primary verification (facts land in the real Agora store, undo restores byte-for-byte) |
| 4 — Adaptation History + Auto-Rollback | **VERIFIED-DONE** | Nothing. UI page, presenter, correction heuristic, retention constants and circuit-breaker test all present and green |
| 5 — Autopilot Skills v1 | **VERIFIED-DONE** | Nothing. Candidate detector, synthesizer, `SkillManager` write path and 9 tests present and green |
| 6 — Sync Stress-Test + Handover | **INCOMPLETE at reconstruction** | No `sync-*` tag and `STATUS.md` still said `_pending._` for Phases 2–6. `main` itself was green. Redone in this session — see the Phase 6 section, which also records a **real defect** found in `scripts/upstream_sync.sh` |

Two reconstruction findings worth naming explicitly:

1. **The paperwork, not the code, was behind.** Commits `1a93a2d1` (Phase 2), `21e9cd6b` +
   `95fd3510` (Phases 3–4), `269331e7` (Phase 5) all landed, but `STATUS.md` was never updated past
   Phase 1 and `ROADMAP.md` kept every box unchecked. Verified against the tree rather than the
   checkboxes, as instructed — no phase was redone on the strength of the paperwork.
2. **Phase 6 was genuinely unfinished**, and finishing it surfaced a false-PASS bug in the sync
   script that would have let an unregistered upstream conflict through unnoticed. That is the one
   substantive code change made in this session.

---

## Step Zero — environment reconnaissance

| Item | Result | Evidence |
|---|---|---|
| Host shell | git-bash on Windows 11 | `uname`/tool output |
| JDK 21 (Temurin 21.0.12.1+1) | installed (local, uncommitted) | `java -version` → `openjdk version "21.0.12.1"` |
| Android cmdline-tools + SDK root | installed (local, uncommitted) | `_tools/sdk/{cmdline-tools,platform-tools,platforms,build-tools,ndk,cmake}` |
| NDK `28.2.13676358` | installed | `_tools/sdk/ndk/28.2.13676358` |
| CMake `3.22.1` | installed | `_tools/sdk/cmake/3.22.1` |
| GitHub auth | available via Git Credential Manager (repo + workflow scope) | `git credential fill host=github.com` → user `michaelxdips` |
| Fork | created | `michaelxdips/Agora`, `fork=true`, parent `newo-ether/Agora` |
| Upstream clone + submodules | present | `thirdparty/llama.cpp` 151M, `thirdparty/proot` 1.9M, `thirdparty/talloc` 146K |
| Device / emulator (`adb`) | **available** — `hermes_x86_64` AVD (Pixel 7, API 36, `google_apis_playstore`, x86_64) on `emulator-5554` | `adb devices` → `emulator-5554 device`; `ro.dalvik.vm.native.bridge=libndk_translation.so`, `ro.product.cpu.abilist=x86_64,arm64-v8a` |

**Why x86_64 and not arm64.** The app is `arm64-v8a` only, so the obvious move is an arm64 AVD. The
emulator refuses it: `Avd's CPU Architecture 'arm64' is not supported by the QEMU2 emulator on
x86_64 host. System image must match the host architecture.` (emulator 37.1.11, host AMD64; no
override exists — the string is a hard `FATAL` in `emulator.exe`, and no `ANDROID_EMULATOR_*` escape
hatch is present in the binary). The working path is an **x86_64 Play image**, which ships ARM
translation (`libndk_translation.so`); the arm64 APK installs and runs on it unmodified. This needed
no repo change at all — no ABI added, no upstream file touched.


**Tooling paths used locally (never committed):** `_tools/` (JDK, SDK, keystore) on the machine that
runs the builds; `local.properties` supplies `sdk.dir` + the signing aliases.

Build environment used for every Gradle command below:

```bash
export JAVA_HOME='C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1'
export ANDROID_HOME='C:/Users/Michael/Documents/Chatapp/_tools/sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`java -version` → `openjdk version "21.0.12.1" 2026-08-18 LTS` (Temurin).
`adb version` → `Android Debug Bridge version 1.0.41` (37.0.1-15733141).

**ONE-TIME INPUTS (collected 2026-09-14, never asked again):**

| # | Input | Resolution |
|---|---|---|
| 1 | GitHub token / fork URL | Git Credential Manager token for `michaelxdips` (scopes `gist, repo, workflow`); fork `michaelxdips/Agora` exists, `upstream` remote added |
| 2 | Linux/macOS shell | **substituted:** git-bash on Windows 11; all tooling (JDK 21, cmdline-tools, sdkmanager, platform-tools) installed headless under `_tools/` |
| 3 | arm64 device or host | **absent** — owner chose: write `androidTest` code, verify logic with JVM tests now; device proof deferred to HS1 |
| 4 | Cheap LLM API key | **deferred (HS2)** — owner chose to skip; no key is needed to build. Reflection code must skip silently when unconfigured |
| 5 | Keystore | generated at `_tools/hermes-release.jks` (alias `hermes`, RSA 2048, validity 10950); wired via `local.properties`, both git-excluded |

---

## Phase 0 — Environment & First Build

Exit criteria: `fdroidDebug` APK installed and launching on device (screenshot proof).

| Criterion | Status | Evidence |
|---|---|---|
| SDK packages + licences accepted | done | `sdkmanager --licenses` accepted; packages listed in Step Zero |
| `assembleFdroidDebug` green | _see build log tail below_ | `./gradlew assembleFdroidDebug` |
| APK installed + launched on device, screenshot proof | **done** | `adb -s emulator-5554 install -r -t app-fdroid-debug.apk` → `Success`; launched → `topResumedActivity=ActivityRecord{… com.hermes.app/com.newoether.agora.MainActivity}`; no crash in `logcat -b crash`. Proof: `evidence/phase0-6/01-hermes-launched.png`, `04-hermes-relaunched.png` |

Upstream was **not** patched to make it build; any build failure is handled as an environment issue
or reported instead (per phase instruction).

---

## Phase 1 — Fork Hygiene, Rebrand, Sync Automation

Exit criteria: rebranded APK coexists with original Agora; sync dry-run passes gates.

| Criterion | Status | Evidence |
|---|---|---|
| `applicationId` → `com.hermes.app` | **done** | commit `f1dd9c63`; `git diff` shows one line + `HERMES INTEGRATION POINT` marker; touchpoint #1 |
| App name "Hermes" + adaptive icon via `app/src/fdroid/res` overlay | **done** | commit `f1dd9c63`; 4 new files under `app/src/fdroid/res/` (zero upstream edits — upstream has 0 files there) |
| Release keystore wiring (`local.properties`) | **done** | `_tools/hermes-release.jks`; `assembleFdroidRelease` green; `apksigner verify --print-certs` → `[certificate DN omitted]`, SHA-256 `7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7` |
| GitHub labels `upstream-sync`, `contract-change` | **done** | `GET /repos/michaelxdips/Agora/labels` → `upstream-sync E11D48`, `contract-change F59E0B` |
| Sync dry-run clean | **done** | `SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh` → `touchpoint_guard: PASS`, `SYNC_EXIT=0` |
| Both APKs coexist on device | **done** | upstream `app-release.apk` (v2.1.0) and our `app-fdroid-debug.apk` installed together: `pm list packages` → `package:com.hermes.app` + `package:com.newoether.agora`. Launcher drawer shows both icons — "Agora" (white, stylised A) and "Hermes" (dark-green adaptive icon, white H + terracotta corner). Proof: `evidence/phase0-6/05-app-drawer.png` |

Guard fix required during Phase 1: the bootstrap `ALLOWED` regex did not list
`app/src/fdroid/res/`, so the first dry-run correctly failed with
`FAIL unregistered upstream file modified: app/src/fdroid/res/values/strings.xml`.
Commit `729d0d56` adds that prefix to the ALLOWED regex — the overlay directory is Hermes-only
(upstream carries **zero** files under it), so this is a Hermes-only-path registration, not a
weakening of the guard. Upstream-owned files remain budget-checked and unregistered edits still fail.

---

## Phase 2 — Gap Analysis & System Mapping

Exit criteria: every claim cites an existing file path; expected touchpoints registered.

**VERIFIED-DONE.** `GAP_ANALYSIS.md` (94,611 bytes) cites 60 full repo-relative paths; 58 exist on
disk. The two that do not are self-declared non-existent by the document itself and are named as
files **to create**, not as existing code:

| Cited path | Verdict | Evidence |
|---|---|---|
| `app/src/main/java/com/newoether/agora/ui/settings/SettingsAdaptationHistoryPage.kt` | does not exist, and `GAP_ANALYSIS.md:1573-1576` says so explicitly ("it does **not** exist; it is named in §4.2 purely as the file to create"). Phase 4 delivered it in the Hermes-only path instead: `app/src/main/java/com/newoether/agora/autopilot/SettingsAdaptationHistoryPage.kt`. | `sed -n '1573,1576p' GAP_ANALYSIS.md` |
| `memories/active_memory.md` | an **entry name inside the export zip**, not a repo file: `DataExporter.kt:618-623` writes it, `DataImporter.kt:499-507` reads it (`GAP_ANALYSIS.md:198`). | `grep -n 'memories/active_memory.md' GAP_ANALYSIS.md` |

Spot-check of cited paths against the tree (line numbers read back from the working tree):

| Claim | Cited | Actual |
|---|---|---|
| `MemoryManager` storage expressions | `MemoryManager.kt:10,12,15,18` | identical (`class MemoryManager(context: Context)`, `memory_db`, `active_memory.md`, `memory_meta.json`) |
| `SkillManager` ctor | `SkillManager.kt:10` | `class SkillManager(context: Context)` |
| `AppContainer` construction site | `AppContainer.kt:74` | `val skillManager: SkillManager by lazy { SkillManager(appContext) }` |
| `SettingsScreen` group list | `SettingsScreen.kt:266-270` | `SettingsGroupData(titleRes = R.string.settings_group_memory_data, …)` |
| `SettingsRepository` signatures | `SettingsRepository.kt:98,163,716,727` | file present at the cited path; members read back during Phase 3/4 implementation |

Pre-registered touchpoints: both planned files became real entries in the `GUARD:DATA` block
(`SettingsScreen.kt`, `MainActivity.kt`) — see the registry table in `UPSTREAM_TOUCHPOINTS.md`.

Verification command: `python -c` path-existence sweep over every backticked path in `GAP_ANALYSIS.md`.

---

## Phase 3 — Autopilot Memory v1

Exit criteria: `androidTest` green; UI demo after human's in-app setup (non-blocking).

**VERIFIED-DONE (code + JVM proof) / [HS1] for the instrumented run.**

| Criterion | Status | Evidence |
|---|---|---|
| `com.newoether.agora.autopilot` sources | done | 17 files in `app/src/main/java/com/newoether/agora/autopilot/` incl. `ReflectionWorker.kt`, `ReflectionCaller.kt`, `MemoryApplier.kt`, `AdaptationLog.kt` (own Room DB), `AutopilotNotifier.kt` |
| Triggers: session idle / every 20 messages / debug-only manual | done | `ReflectionWorker.kt:115 MESSAGES_PER_REFLECTION = 20`, `:141` manual debug trigger, `:131-133 setRequiresBatteryNotLow(true)`; `AutopilotTriggerObserver.kt:30 start(...)` |
| Snapshot-first write + own Room DB | done | `MemoryApplier.apply(...)`; DB `hermes_autopilot.db` — Agora's Room DB never extended (N2) |
| Notification + POST_NOTIFICATIONS | done | `AutopilotNotifier.kt:65 PRIORITY_LOW`, `:81 IMPORTANCE_LOW`; `AndroidManifest.xml:8 <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` |
| Master toggle ON, daily cap 5, provenance tag | done | `AutopilotSettings.kt:60 DEFAULT_DAILY_CAP = 5`; `ReflectionProtocol.kt:16 PROVENANCE_TAG = "<!-- hermes:autopilot -->"` |
| JVM tests green | **done** | `:app:testFdroidDebugUnitTest` + `:app:testPlayDebugUnitTest` **--rerun-tasks** → `BUILD SUCCESSFUL in 5m 15s`, `EXIT=0`; 2487 + 2470 tests, 0 failures, 0 errors. Autopilot subset: 48 tests (`MemoryApplierTest` 10, `ReflectionProtocolTest` 12, `CircuitBreakerTest` 9, `SkillSynthesizerTest` 9, `AdaptationHistoryPresenterTest` 5, `AutopilotTriggerObserverTest` 3) |
| `androidTest` compiles + packages | done | `:app:assembleFdroidDebugAndroidTest` → `app/build/outputs/apk/androidTest/fdroid/debug/app-fdroid-debug-androidTest.apk` (382,847 B) |
| `androidTest` **runs** on device | **done** | `./gradlew :app:connectedFdroidDebugAndroidTest` → `Starting 3 tests on hermes_x86_64(AVD) - 16` / `Finished 3 tests` / `BUILD SUCCESSFUL in 18s`, `EXIT=0`. Report `app/build/outputs/androidTest-results/connected/debug/flavors/fdroid/TEST-hermes_x86_64(AVD) - 16-_app-fdroid.xml` → `tests=3 failures=0 errors=0`: `threeSeededConversationsProduceFactsInTheAgoraStoreAndUndoRestoresBytes` 0.106s, `theCircuitBreakerRollsBackABadAdaptationOnDevice` 0.073s, `undoOfANewlyCreatedFileRemovesItFromTheStore` 0.004s |
| UI demo after in-app setup | **[HS2]** deferred (non-blocking) | Reflection needs a provider/API key. The debug trigger is present and tappable (`Run reflection now`) and the app stays alive with no crash when tapped — reflection skips silently, as designed |

**Defect found by running the tests (why the device run mattered).** The whole class failed to
initialise with `Method undoOfANewlyCreatedFileRemovesItFromTheStore() should be void`. Its last
statement was `log.delete(id)`, and `AdaptationLogDao.delete(id: Long): Int` makes the method's
inferred return type `Int`, which JUnit4 rejects for the entire class. Fixed by asserting the cleanup
result: `assertEquals(1, log.delete(id))` — commit `2452cb48`. A JVM-only run could not have caught
this: the test is `androidTest`-only, so it had never executed anywhere.

Also found on device: `aapt2 dump badging` showed `application-label-ar:'Agora'` while the other 86
labels were `Hermes` — upstream defines `app_name` in `values/` plus 11 locale folders, and a
default-only overlay loses in those locales. Fixed by adding the matching overlay entry per locale
(commit `c9aa48ea`); all 87 labels now read `Hermes`, upstream files untouched.


---

## Phase 4 — Adaptation History + Auto-Rollback

Exit criteria: circuit breaker demonstrably rolls back a bad adaptation (test proof).

**VERIFIED-DONE.**

| Criterion | Status | Evidence |
|---|---|---|
| Settings → Adaptation History via existing Compose navigation | done | `SettingsScreen.kt` touchpoint: `SettingsCategory("adaptation", R.string.hermes_adaptation_history, …)` + `// HERMES INTEGRATION POINT`; page in `autopilot/SettingsAdaptationHistoryPage.kt`; presenter in `AdaptationHistoryPresenter.kt` |
| Entry list, before/after diff, per-entry Undo, status chips | done | `AdaptationHistoryPresenterTest` (5 tests); statuses `applied` / `auto_rolled_back` / `user_rolled_back` / `needs_revision` in `AdaptationLog.kt:51` |
| Notification tap wired to the screen | done | `MainActivity.kt` touchpoint (`openAdaptationHistory` flag), budget 32/34 lines |
| Correction heuristic: 2 flags → auto-rollback + `needs_revision` | done | `CircuitBreaker.kt:21 recordCorrection(sessionId)`, `:30 if (updated.feedbackFlags < FLAGS_BEFORE_ROLLBACK) continue`; `AdaptationLog.kt:93 UPDATE adaptation_log SET feedbackFlags = feedbackFlags + 1` |
| Retention 50 versions per file or 30 days | done | `CircuitBreaker.kt:74 MAX_VERSIONS_PER_FILE = 50`, `:75 MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000`; pruned on the reflection cadence (`ReflectionWorker.kt:74-75`) |
| Test proof: circuit breaker rolls back a bad adaptation | **done** | JVM `CircuitBreakerTest` 9 tests, 0 failures **and** on device `theCircuitBreakerRollsBackABadAdaptationOnDevice` passed in the `connectedFdroidDebugAndroidTest` run |
| Screen reachable + rendered on device | **done** | Settings → **Memory & Data** → `Adaptation History` → page shows `Autopilot` / `Hermes updates saved memory automatically after conversations` / `Daily cap: 5 adaptations` / `Run reflection now` / `No adaptations yet…`. The master toggle reads `checked="true"` (default ON). Proof: `evidence/phase0-6/07-settings-memory.png`, `08-adaptation-history.png`, `09-reflection-trigger.png` |

---

## Phase 5 — Autopilot Skills v1

Exit criteria: generalized skill draft created via `SkillManager` from a multi-tool session (test proof).

**VERIFIED-DONE.**

| Criterion | Status | Evidence |
|---|---|---|
| Candidate detection ≥3 chained tool calls OR ≥2 user corrections before success | done | `SkillCandidateDetector.kt:15 MIN_CHAINED_TOOL_CALLS = 3`, `:18 MIN_USER_CORRECTIONS = 2`, `:27 isCandidate(...) = succeeded && (chainedToolCalls >= 3 \|\| userCorrections >= 2)` |
| Synthesis → draft (name, trigger, generalized steps, pitfalls) via `SkillManager` | done | `SkillSynthesizer.kt:22 synthesize(...)` writes through `SkillManager`; prompt in `SkillSynthesisProtocol.kt`; provenance tag appended (`SkillSynthesisProtocol.kt:43`) |
| Snapshot/rollback parity + same caps and provenance as memory | done | same `MemoryApplier`/`AdaptationLog` flow; `AdaptationEntry.STORE_SKILL` alongside `STORE_MEMORY` |
| Test proof | **done** | `SkillSynthesizerTest` 9 tests, 0 failures, 0 errors (from the `--rerun-tasks` run) |

---

## Phase 6 — Sync Stress-Test + Handover

Exit criteria: one real upstream merge per `UPSTREAM_SYNC.md` (or simulated against an older upstream
tag); `main` fully green: unit tests + both flavor debug builds + touchpoint guard + contract check.

**VERIFIED-DONE** — and this phase exposed and fixed a real defect in the sync script.

`upstream/master` (`914e7c8d`) is **already an ancestor of `main`**; the pre-existing merge commit
`17586460` ("Merge original Agora history while preserving extracted cache controls") is a genuine
merge whose second parent `b829ee4d` is an ancestor of `upstream/master`. Real sync therefore has
nothing left to merge: `[upstream_sync] already up to date with upstream/master — nothing to merge.`

Per the phase spec's "(or simulated against an older upstream tag)", the merge path was exercised for
real. Note `v1.3.0` and `v1.3.5` are the only tags **not** ancestors of `upstream/master` (upstream
rewrote those two histories); all other 25 tags are ancestors.

### Defect found and fixed: dry-run reported a false PASS

`SYNC_DRY_RUN=1` against `v1.3.0` printed the success line *"conflicts auto-resolved in registered
touchpoints only"* **and exited 0**, while the real conflicts were three unregistered upstream files
(`api/HttpClient.kt`, `viewmodel/ChatViewModel.kt`, `build-proot.sh`) that the conflict policy must
abort on. Root cause: `mktemp -d "${TMPDIR:-/tmp}/…"` returns an MSYS path (`/tmp/hermes-sync-XXXXXX`).
Bash resolves it, native git records it as `C:/tmp/…`, so the worktree's `cd` landed outside a
repository, `git diff --name-only --diff-filter=U` failed, the empty result was read as "no
conflicts", and `resolve_conflicts` returned 0.

Fix (commit below): normalise the worktree path with `cygpath -m` when available, and make
`resolve_conflicts` refuse to report success unless a merge is actually in progress (`MERGE_HEAD`
exists), returning a distinct status 2 for "could not be evaluated" so it can never be mistaken for
"resolved". Verified both directions:

| Scenario | Before fix | After fix |
|---|---|---|
| Dry-run vs real older tag `v1.3.0` (unregistered conflicts) | **false PASS, exit 0** | `UNREGISTERED conflict: api/HttpClient.kt` … `merge needs human attention`, **exit 1** |
| Dry-run vs simulated upstream release (conflict in registered `app/build.gradle.kts`) | n/a | `touchpoint conflict kept ours: app/build.gradle.kts`, `conflicts auto-resolved in registered touchpoints only`, exit 0 |
| Real merge on throwaway branch `hermes/sync-stress` of the simulated release | n/a | `CONFLICT (content): Merge conflict in app/build.gradle.kts` → kept ours → `merge completed with touchpoint resolutions` → `touchpoint_guard: PASS (100b5358)` → `OK: hermes/sync-stress now contains upstream/sim` |

The simulated upstream release (`100b5358`, "sim: upstream renames applicationId") is a throwaway
ref `upstream/sim` built in a temp worktree; it is not part of `main` or of any pushed branch.

### `main` fully green (fresh, on `main` at `4f1babff`)

| Gate | Result | Evidence |
|---|---|---|
| Unit tests, both flavors, forced re-run | **PASS** | `--rerun-tasks` → `BUILD SUCCESSFUL in 5m 15s`, `EXIT=0`; 2487 + 2470 tests, 0 failures, 0 errors |
| `assembleFdroidDebug` | **PASS** | `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk` (66,932,001 B) |
| `assemblePlayDebug` | **PASS** | `app/build/outputs/apk/play/debug/app-play-debug.apk` (64,559,721 B) |
| Touchpoint guard | **PASS** | `touchpoint_guard: PASS (914e7c8d…)`; budgets 9/12, 32/34, 17/18 lines |
| Contract check (guard §4) | **PASS** | `SkillManager.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `thirdparty/llama.cpp`, `thirdparty/proot` all present |
| Sync dry-run | **PASS** | `SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh` → exit 0 |
| Post-merge tree still green | **PASS** | on the merged `hermes/sync-stress` tree: `BUILD SUCCESSFUL in 13s`, `EXIT=0` |
| Secrets in git history | **none** | guard §3 passes; `git log --all --diff-filter=A --name-only` has no `local.properties` / `*.jks` / `*.keystore` |
| N10 contract re-read trigger | n/a | the stress merge changed no `development/*.md` or `ARCHITECTURE.md` |

Stress merge tagged `sync-2026-09-14-stress` → `e360491c`. `HANDOVER.md` written (Wear OS
prerequisites, HS registry, from-scratch verification recipe, deliberate limitations).

---

## Phase 6 — Persona System (Caveman + Ponytail)

Exit criteria: personas toggleable, upstream-sourced, updatable, isolated, removable without trace.

**VERIFIED-DONE** (device-verified, three real defects found and fixed by running it).

### P1 — injection channel: Agora's active-memory store, zero new touchpoints

A persona is a delimited block inside `active_memory.md`:

```
<!-- HERMES:PERSONA:CAVEMAN:START -->
…rule text…
<!-- HERMES:PERSONA:CAVEMAN:END -->
```

`active_memory.md` is already read by `MemoryManager.getActiveMemory()` and already injected into the
resolved system prompt by `GenerationRequestBuilder.resolvePromptTemplate`, so personas ride the
native channel (P1) and the feature registers **no** upstream touchpoint of its own (N14). A
hand-rolled prompt hook was rejected for exactly that reason.

| Criterion | Status | Evidence |
|---|---|---|
| Marked block in the active-memory store, deterministic find/replace/remove | done | `PersonaStore.kt`; `upsertBlock`/`removeBlock` are exact inverses (asserted by `PersonaStoreTest`) |
| Journaled: snapshot → write → `AdaptationLog` | done | `PersonaApplier.setEnabled` inserts the row **before** the store write and deletes it if the write throws; `PersonaApplierTest` asserts `beforeSnapshot` equals the exact prior bytes |
| Toggle OFF = block removed = zero trace | **done on device** | toggled Caveman on, then off: `adb shell run-as com.hermes.app cat files/active_memory.md` → **0 bytes**, UI reads *"No persona markers in the store — toggle off is complete."* (`_device_proof/p6-03-caveman-off-zero-trace.png`) |
| Master autopilot OFF also strips personas | **done on device** | toggling the master switch off took the store from 4 markers to **0** immediately; the strip is in `AutopilotControlsSection`, not deferred to next launch |
| Persistence across force-stop | **done** | `am force-stop` + relaunch: both persona blocks still present, all four markers intact |
| Zero new upstream touchpoints for personas | done | the only upstream file the feature touches is `GenerationRequestBuilder.kt`, and that is for **P5 isolation**, not injection |

### P2 — vendored sourcing + updatability

| Persona | Upstream | Ref | sha256 |
|---|---|---|---|
| Caveman | `github.com/JuliusBrussee/caveman` | `v2.6.0` | `c4d7354b4b063d54601fcdd5097a5b1713d1a1a2e386ac39efa438aa1ffef8ce` |
| Ponytail | `github.com/DietrichGebert/ponytail` | `v4.10.0` | `1316a2f3f95741d2300b116fe0c2d81ce4a9568656ed0a62643f54aaf09957f2` |

Vendored copies in `personas/`, machine-readable record in `personas/upstream.lock`, in-APK copies in
`app/src/main/assets/personas/` (assets are read-only, so they are seeded into app storage on first
run). `PersonaFidelityTest` asserts the repo copy, the asset copy and the lock's hash all still agree.
`scripts/persona_update.sh` fetches the latest tag → diffs → **runs the persona regression tests** →
only then updates the lock and vendored copies; a failing test aborts the update. Dry-run verified:
`persona_update: caveman up to date (v2.6.0)`, `ponytail up to date (v4.10.0)`.
Offline fallback: `PersonaRepository.EMBEDDED` — a faithful short form used only when the vendored
file cannot be read, so a toggle is never a silent no-op.

### P3 — fidelity to upstream

`PersonaStore.toBody` strips only the YAML frontmatter (registry metadata, not instruction text) and
copies the rest **verbatim**. `PersonaFidelityTest` asserts the load-bearing phrases survive:
Caveman's `Code blocks unchanged` and `Never drop not/never/no/only/except`; Ponytail's
`Does this need to exist at all?` and `Never simplify away`.

### P5 — isolation

Persona blocks are stripped wherever active memory enters a request
(`GenerationRequestBuilder.kt`), plus defensively in `ReflectionCaller` and `SkillSynthesizer`.
`PersonaIsolationTest` (5 tests) proves the reflection prompt, the synthesis prompt and a mid-transcript
block all come back clean while the user-facing channel still carries the persona.

### P6 — honest cost numbers

Measured with the app's own `ContextTokenEstimator` (`PersonaFidelityTest` prints it, so it is
reproducible):

```
PERSONA_COST persona input cost (app estimator, per request): caveman=2473, ponytail=2061; combined=4534
```

That is the **input** cost of the block, per request, honestly stated: on an already-terse workload a
persona can cost more than it saves, and this fork reports that rather than quoting a percentage.
**Output-token reduction is NOT measured** — it needs a live provider key, which is HS2 and was
deferred by the owner. Not claimed, not estimated.

### P4 — UI

Settings → Personas: two toggles, per-persona *Edit text*, *Reset to default*, *Check for persona
updates*, and a status line whose state is read **back from the injection channel** (`active memory
(N characters)` and a clean/dirty marker line), not from the DataStore intent. Proof:
`_device_proof/p6-01-personas-page.png`, `p6-02-caveman-on.png`, `p6-04-adaptation-history-persona-entry.png`.

### Defects found by running it (not by reading it)

1. **`trim()` broke byte-exact removal.** The first `removeBlock` trimmed its result, so toggling a
   persona off silently rewrote a file the user owned — a trailing newline disappeared. Caught by the
   **on-device** test; the JVM tests had not covered it. Fixed by making the separator part of the
   removed region, so upsert/remove are exact inverses.
2. **Unterminated block was left behind.** A `START` marker with no `END` (the shape a process kill
   leaves) was treated as "not present", so the next write appended a *second* copy. Now removed.
3. **`indexOf` returning -1 won the `minOrNull`**, throwing `StringIndexOutOfBoundsException` on the
   self-heal path. Fixed and covered.
4. **Master-off strip was deferred to next launch** — on device, switching the autopilot off left all
   four markers in the store. Moved into the toggle's own `LaunchedEffect`.

### Phase 6 gate

| Gate | Result |
|---|---|
| Persona + sentinel JVM tests | **PASS** — 35 tests (`PersonaStoreTest` 13, `PersonaApplierTest` 10, `PersonaIsolationTest` 5, `PersonaFidelityTest` 5, `UpstreamContractSentinelTest` 7), 0 failures |
| Autopilot suite | **PASS** — 88 tests across 11 classes, 0 failures |
| Connected instrumented tests | **PASS** — 6/6 on `hermes_x86_64` |
| `touchpoint_guard.sh` | **PASS** (budgets 9/12, 35/40, 23/24, 7/8) |

---

## Phase 7 — Wear OS Standalone-Lite

Exit criteria: standalone proven with the phone in airplane mode; `:wear:assembleRelease` signed;
watch unit tests green.

**Base: Wear OS 5 (API 34)** — owner-specified. Verified on a real `system-images;android-34;android-wear;x86_64`
emulator (384×384 round), not merely compiled.

### Repo hygiene first

`wear/` is in the guard `ALLOWED` regex; `settings.gradle.kts` and `gradle/libs.versions.toml`
(the module include and the Wear/Data-Layer dependency aliases) are registered in
`UPSTREAM_TOUCHPOINTS.md`. Guard re-run → PASS.

> **Correction (Phase 8).** The first version of this row claimed `wear/` had been added to `ALLOWED`
> when it had not — the guard caught the omission and failed on seven `wear/**` files. Fixed in
> `scripts/touchpoint_guard.sh`; a `wear/` path is now matched, and the guard is green. The claim is
> left visible here on purpose: a STATUS row that asserted a guard change that never happened is
> exactly the failure mode the evidence standard exists to prevent.

### What the watch is

Two setup paths, owner-mandated, both landing in the same encrypted store:

1. **Pair with the phone app** — base URL, API key and model pushed over the Data Layer
   (`com.newoether.agora.autopilot.wearsync.WatchSync`, driven from Settings → *Watch setup*).
2. **BYOK on the watch** — type base URL / API key / model on the watch itself. No phone involved.

> **Correction (Phase 12).** The sentence above was **false when it was written**. It claimed the push
> was "driven from Settings → *Watch setup*" while `SettingsWatchSetupPage` had no `SettingsCategory`
> entry and no `"watch"` branch in `SettingsScreen.kt` — `grep -c '"watch"'` returned 0 and
> `grep -rn "SettingsWatchSetupPage" app/src/main/java/ | grep -v ...Page.kt:` was empty. The page was
> unreachable dead code, so path 1 could not be used by any user. It is reachable now, and the claim is
> true: proved on emulator-5554 by opening Settings → *Watch setup* and reading the screen
> (`Wear setup page: [... 'Send to watch', 'Back', 'Watch setup']`, `_tools/phone_proof.py`, RESULT PASS).
> The original claim is left visible because a STATUS row asserting a capability that did not exist is
> exactly the failure mode the evidence standard exists to prevent.

After setup it is standalone: the watch calls the OpenAI-compatible endpoint directly with OkHttp.

### Lightweight, on purpose

The module's entire dependency list is Compose + Wear Compose + Data Layer + OkHttp. Absent — not
disabled — are image generation, conversation trees, the sandbox, MCP, skills, Room and llama.cpp.
Release enables R8 + resource shrinking and filters locales to `en`.

### UI on real Wear Material 3

`ScreenScaffold`, `ScalingLazyColumn`, `Card`, `Button`, `ListHeader`, `TimeText`, themed with the
Hermes palette (`WearTheme.kt`, dark-first for OLED). Wear M3 ships **no** text field — its convention
is "open the keyboard on the phone" — and the owner explicitly wants to type on the watch, so the
composer is a foundation `BasicTextField` wrapped in an M3 `Card`: same surface and colour roles, real
cursor, system keyboard, `ImeAction.Send`. Typing, sending and voice are all present.

### Device verification (emulator-5558, Wear OS 5, 384×384)

| Check | Result | Evidence |
|---|---|---|
| APK installs and launches | **done** | `install -r -t` → `Success`; `topResumedActivity=…com.newoether.agora.wear.WearMainActivity` |
| Setup screen shows both paths | **done** | UI dump: `Hermes setup` / `Pair with the phone app, or enter your own key.` / `Base URL` / `API key` / `Model` / `Save key` / `Pair with phone` (`p7-05-wear5-m3-setup-fixed.png`) |
| Typing works on the watch | **done** | tap field → `dumpsys input_method` → `mInputShown=true`; typed `https://api.example.com/v1` and it appears in the field (`p7-07-wear5-typing.png`) |
| BYOK save writes an **encrypted** config | **done** | `files/hermes_wear_config.bin` (191 B) created; `grep -c "sk-test-key"` on the raw bytes → **0**; AES-256-GCM with the key in the Android keystore |
| Chat screen: type + Send + Speak | **done** | UI dump after save: `Ready` / `Type a question` / `Send` / `Speak` / `Debug` (`p7-03-wear5-chat-byok.png`) |
| Wear unit tests | **PASS** | 14 tests (`WearCoreContextTest` 7, `WearOfflineQueueTest` 7), 0 failures |
| `:wear:assembleRelease` signed | **PASS** | `wear-release.apk` **2,685,504 B** (debug is 39,494,077 B — R8 + resource shrinking + locale filter); `apksigner verify --print-certs` → `CN=Hermes Local, OU=Autopilot, O=Hermes, …, C=ID`, SHA-256 `7188ce70…aa56d7`, **identical to the phone app's** (required: same identity for the Data Layer) |
| Release lint gate | **PASS** | `lintVitalRelease` initially **failed** the release build: `play-services-basement` drags in `androidx.fragment:1.1.0`, below the 1.3.0 floor the ActivityResult APIs require. Fixed by constraining `androidx.fragment:fragment:1.8.5` — the real defect was a stale transitive, not a lint false positive. An earlier draft suppressed the check; that was wrong and was deleted. |
| `touchpoint_guard.sh` after the module landed | **PASS** | `wear/` in `ALLOWED`; `settings.gradle.kts` + `gradle/libs.versions.toml` registered as touchpoints |

### Defect found by running it

**Persona rule text reached the watch's core context.** The first `WearCoreContext.clean` filtered out
marker *lines*, which left the rule text between them in the derived system prompt — spending the
watch's 500-token budget on instructions the watch app does not follow. The unit test caught it
(`persona blocks never reach the watch prompt`); the fix removes whole blocks, including unterminated
ones. Same class of bug as the Phase 6 `trim()` finding: the block is the unit, not the marker line.

### Still open on this phase

* **Airplane-mode standalone proof with a live provider key** — needs HS2 (API key). What *is* proven
  now: the watch launches with no phone present, the BYOK path configures it with no phone involved,
  and the queue holds questions offline (see the Phase 8 data-loss torture row).
* **Phone↔watch pairing on real hardware** — the Data Layer needs the watch paired to a phone; the two
  emulators are not Data-Layer-paired, so the *push* half is exercised by unit-level inspection of
  `WatchSync` rather than by a live transfer. Recorded as HS4, and as an accepted limitation.

---

## Human-setup required ([HS] registry)

| # | Item | Impact if missing |
|---|---|---|
| HS1 | ~~USB/OTG Android device (or emulator) with `adb`~~ | **RESOLVED** — `hermes_x86_64` AVD (Pixel 7, API 36, x86_64 Play image with ARM translation) on `emulator-5554`; device install, screenshots and `connectedFdroidDebugAndroidTest` all executed |
| HS2 | In-app provider/API-key setup | **BLOCKS the Phase 6 P6 output-token measurement and the Phase 7 airplane-mode live call.** Everything else is proven without it: the reflection path, the persona channel, the BYOK store and the offline queue are all exercised with injected or absent credentials. Not estimated, not claimed. |
| HS3 | Enable GitHub Actions on the fork | scheduled `upstream-sync` workflow does not run until enabled in the Actions tab |
| HS4 | Physical watch (or a Data-Layer-paired emulator pair) | the phone→watch *push* half of the pairing path cannot be exercised end-to-end; the watch's independent paths (BYOK, offline queue, core context) are fully verified on the API 34 wear image |

---

## Phase 12 — Rename, the three critical findings, and the Wear rebuild
Session scope: rename to **Hermes X**, maintainer **Michael** visible in code and on screen, the three
confirmed defects in the handover prompt, repo hygiene, a full `:wear:` rebuild, real pairing, and a
two-pass audit. One commit per phase; every claim below names the command that produced it.

### Gate at the end of this session (all re-run, not remembered)

| Gate | Result |
|---|---|
| `scripts/touchpoint_guard.sh` | **PASS** |
| `SYNC_DRY_RUN=1 scripts/upstream_sync.sh` | **PASS** (clean, exit 0) |
| `:app:testFdroidDebugUnitTest` | **2533 tests, 0 failures, 0 errors** |
| `:app:testPlayDebugUnitTest` | **2516 tests, 0 failures, 0 errors** |
| `:wear:testDebugUnitTest` | **47 tests, 0 failures, 0 errors** (was 31: +8 pairing, +8 drainer, +4 sentinel, −4 net from the reworked suite) |
| `:app:assembleFdroidDebug` / `:app:assemblePlayDebug` / `:wear:assembleRelease` | **PASS** |
| APK sizes (clean build, exact bytes) | fdroid **65,294,236** · play **65,233,688** · wear release **2,719,147** |
| `apksigner verify --print-certs` | **verified**; `[certificate DN omitted]`, SHA-256 `7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7` — same identity as the phone, which the Data Layer requires |
| Launcher label (`aapt2 dump badging`, all three APKs) | `application-label:'Hermes X'` in **every** locale, including `de`/`ar`/`es`/`zh`/`ja`/`ko`/`ru`/`vi`/`fr`/`pt-BR`/`zh-TW` |
| `applicationId` (all three APKs) | **`com.hermes.app`** — unchanged, which is what keeps pairing possible |
| `versionName` | `3.0.0-hermesx` in both modules. `versionCode` **left at 31**: phone and watch share an `applicationId`, so the two must move together, and a versionCode bump buys nothing for a rebrand that is not being published to a store |
| `lintVitalRelease` | **PASS** (`:wear:lintVitalRelease` BUILD SUCCESSFUL) — the `androidx.fragment:1.8.5` constraint from Phase 7 still holds; nothing was suppressed |

### The three critical findings — reproduced, fixed, re-proved

| # | Severity | What was wrong | RED evidence | Status |
|---|---|---|---|---|
| 4.1 | **CRITICAL** | `SettingsWatchSetupPage` was unreachable: no `SettingsCategory("watch", …)` and no `"watch"` dispatch | `grep -c '"watch"' SettingsScreen.kt` → `0`; `grep -rn "SettingsWatchSetupPage" app/src/main/java/ \| grep -v "Page.kt:"` → empty | **FIXED**, proved on device: Settings → *Watch setup* opens and shows `Send to watch` / `Watches connected: 0` (`_tools/phone_proof.py`, RESULT PASS) |
| 4.2 | **HIGH** | The watch's "Pair with phone" button had no handler at all — it set a boolean and printed a static sentence | `grep -rn "pairing\|pairRequest\|CapabilityClient\|MessageClient" app/src/main/java/ wear/src/main/java/` → empty | **FIXED**: `WearPairing` sends `/hermes/pair` to every node advertising `hermes_phone`; `PairingListenerService` answers by running the same `WatchSync.sendConfigToWatch` the settings screen uses. On device the watch now reports the truth: `No phone app found. Install it and open it once, or use a key on the watch.` + logcat `pairing: no node advertises hermes_phone` |
| 4.3 | **MEDIUM** | The watch UI was never told a config arrived: the listener wrote the store and told nobody, so the user had to close and reopen the app | `grep -c 'onDataChanged' WearMainActivity.kt` → `0` | **FIXED**: `WearSignals` StateFlows published by the listener and collected by the composition — no polling |

### The defect this session introduced, and how it was caught

Extracting `drainQueue` out of the composable into `WearQueueDrainer` (so it could be tested at all)
**dropped the `withContext(Dispatchers.IO)` wrapper**. Every JVM test still passed; on the device the
launch drain died:

```
W HermesWear: drain: send failed: NetworkOnMainThreadException
W HermesWear: launch drain: delivered=0, queue now 1
RESULT: FAIL — queue not drained on launch
```

Fixed by moving the dispatcher into `drain` itself, then re-proved with the same script:
`RESULT: PASS -- held question delivered on launch, no user action, queue empty`.

`WearMainThreadSentinelTest` now asserts a dispatcher is present at each network call site — a tripwire
on a trap that already bit once. RED for the sentinel itself: removing the dispatcher again fails 2 of
51 tests, naming `WearQueueDrainer.drain` and the module-wide `.ask(` scan.

**The honest lesson:** the JVM suite was green while the feature was broken. `Dispatchers.IO` is not
observable from a unit test with a fake sender, and only the on-device proof caught it.

### APK size — measured, not assumed

The first comparison against the prompt's baseline suggested the APKs had *shrunk* by 562,792 B. That
was wrong, and chasing it produced two findings worth keeping:

1. The prompt's baseline (65,860,308) was taken with `app/src/fdroid/assets/alpine-minirootfs.tar.gz`
   present. That file is gitignored and absent now, so the comparison was not like-for-like.
2. Rebuilding the baseline commit (`81812922`) in a worktree and diffing the archives entry by entry
   showed a **2,558,477-byte gap of pure padding** before `res/xml/file_paths.xml` in an incrementally
   packaged APK. A `:app:clean` build removes it.

Clean-build result: **65,285,432 → 65,294,236 = +8,804 bytes**, every byte of it dex and
`resources.arsc` from the new code. The 2.5 MB was packaging noise, and it would have been reported as
a real change if the archive had not been measured entry by entry.

### Still open, honestly

* **Data Layer transfer between two devices** — needs HS4. Two emulators share no Google account, so
  `getCapability(..., FILTER_REACHABLE)` returns no nodes and the *transport* cannot be exercised here.
  What **is** proved: the watch's decision logic against a fake transport (8 tests), and on the device
  that the request path runs and reports `No phone app found` rather than a static string. The
  end-to-end transfer is **not** verified and is not claimed.
* **Release APK cannot be installed over a debug install** — different signing certificates (debug:
  `Android Debug`; release: `hermes-release.jks`). Expected, and the reason the device proof installs
  the debug APK for `run-as` work and uninstalls first for a release install.
* **A specific provider's behaviour** (OpenAI/Anthropic/Groq/…) — HS2. The watch's real OkHttp path,
  request shape, response parsing and queue are proven end-to-end against the mock provider; the
  provider-specific part is not.
* **P1 Wear surfaces** (Tile, Complication, OngoingActivity) and **P2** (usage parsing, watch
  conversation history, a tighter `readTimeout`) were **not** attempted in this session. The phase
  order put the three critical findings, pairing, hygiene and the rebuild first; the remaining time
  went to proving those rather than starting new surfaces. Not done, not claimed.

---

## Phase 13 — the update feature, the adversarial audit, and the "null" answer

Full detail is in `AUDIT_REPORT.md` (Pass 6). The summary a reviewer needs:

### Gate at the end of Phase 13 (all re-run clean)

| Gate | Result |
|---|---|
| `scripts/touchpoint_guard.sh` | **PASS** (12 registered touchpoints) |
| `SYNC_DRY_RUN=1 scripts/upstream_sync.sh` | **PASS** (exit 0) |
| `:app:testFdroidDebugUnitTest` | **2541 tests, 0 failures, 0 errors** |
| `:app:testPlayDebugUnitTest` | **2524 tests, 0 failures, 0 errors** |
| `:wear:testDebugUnitTest` | **54 tests, 0 failures, 0 errors** (was 47) |
| Three APKs, clean build | **65,296,380** / **65,235,904** / **2,719,147** bytes |
| `apksigner verify --print-certs` | verified, `CN=Hermes Local`, SHA-256 `7188ce70…aa56d7` |
| `lintVitalRelease` | **BUILD SUCCESSFUL** |
| `aapt2 dump badging` | `application-label:'Hermes X'` in all 12 locales, 3 APKs; `package: com.hermes.app`; `versionName='3.0.0-hermesx'` |
| tracked build/log/apk/tmp files | **0** |

### Three defects fixed in this phase

| # | Sev | Finding | Evidence | Status |
|---|---|---|---|---|
| 23 | **HIGH** | The update check queried **upstream's** releases (`newo-ether/Agora`). This fork has a different `applicationId` and signing identity, so the offered APK would be a different app — a wrong install, not a missed one. The second half: the fork's `versionName` (`3.0.0-hermesx`) is above upstream's newest tag (`v2.1.0`), so the check always said "up to date" and the wrong repository could never fire. Two defects hiding each other. | Read `UpdateChecker.kt:39`; no test existed for the repository or the comparison | **FIXED** — points at the fork via `HermesBuildInfo.FORK_REPO`; `compare` public and rewritten; `CancellationException` rethrows; `UpdateCheckerTest` (8 tests). RED first: the test would not compile because `compare` was private and `RELEASES_REPOSITORY` did not exist |
| 24 | **HIGH** | A provider answering `{"choices":[{"message":{"content":null}}]}` made the watch display the literal word **`null`** as the answer. `JsonNull` IS a `JsonPrimitive`, so the cast succeeded, `.content` returned `"null"`, and `isNotBlank()` passed. A wrong answer that looks like a real one — the worst shape this app can produce. | Device probe with the mock in `/__nocontent` mode: screen showed a bare `null` | **FIXED** — explicit `is JsonNull` checks; verified on the device against the rebuilt APK: `Offline — held` instead of `null` |
| 25 | **MEDIUM** | `SettingsAboutPage`'s GitHub, issue-tracker, contribute and privacy-policy rows opened **upstream's** URLs. A bug in Hermes X reported to a tracker for a build upstream does not ship cannot be reproduced. | Read the four `openUrl` calls | **FIXED** — repointed at the fork (touchpoint #11) |

Also fixed: my own Phase-12 regression where the `WatchSync` rewrite left four `hermes_watch_*`
resources defined and unreferenced (the sentences existed twice, and a translation would never have
been picked up). `PushReason` is now the single decision — the screen resolves `reason.stringRes`, the
wire ack uses `reason.wireText`.

### Measured numbers (no estimates)

| Metric | Value | How |
|---|---|---|
| Watch cold start | **509 ms** median (474–567, n=5) | `am start -W` → `TotalTime` |
| Watch PSS | **27,255 KB** | `dumpsys meminfo` |
| Phone cold start | **5,279 ms** median (4,829–7,523, n=3) | `am start -W` |
| Phone PSS | **246,226 KB** | `dumpsys meminfo` |
| API-key canary in logcat, before and after a real request | **0 occurrences** | `logcat -d` grep, with the debug APK installed so the read works — see the Pass-6 correction in `AUDIT_REPORT.md` |
| API-key canary in `hermes_wear_config.bin` | not present in plaintext; file is 173 bytes of ciphertext | `run-as cat` on the debug APK, after proving the read returns the file |

The 60 s `readTimeout` is **not** exercised (the mock's slow mode is 8 s; measuring the timeout would
cost 60 s per run). Recorded as not measured rather than claimed.

### Two harness traps disproved (so they are not chased again)

* **"The app leaves the foreground for a 4000-character question."** False — the harness pressed BACK
  to dismiss the IME. With 4000 characters typed in chunks and no key event, the app stays in
  `WearMainActivity` with all 4000 characters in the field. BACK finishing the activity is correct
  Android behaviour. `evidence/audit_backkey_probe.py`.
* **"Section A passed."** It did not the first time: the harness read the system UI
  (`Android System / Serial console enabled`) and called it OK. `evidence/audit_recheck.py` asserts the
  app is in the foreground first; all four base-URL shapes then passed for real.

### Still open — the recommended-feature list (§9) was NOT implemented

Honest list, in the order I would do them:

| Pri | Feature | State |
|---|---|---|
| P1 | Tile (`SuspendingTileService` + ProtoLayout) — one tap from the watch face | not started |
| P1 | Complication data source — "configured? anything held?" at a glance | not started |
| P1 | `OngoingActivity` + notification for a long answer — the screen sleeps at 5–15 s while the read timeout is 60 s, so the app *looks* hung | not started |
| P2 | usage parsing (`prompt_tokens`/`completion_tokens`) — replaces the ~4-chars-per-token estimate | not started |
| P2 | watch conversation history (N recent, JSON file, not Room) — a restart currently loses the last answer | not started |
| P2 | tighter `readTimeout` — 60 s is longer than any watch interaction | not started |

The time in this phase went to defects in shipped behaviour rather than absent features. That was the
right order, and the rest is listed as not done rather than implied as done.
