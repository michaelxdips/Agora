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
| 0 — Environment & First Build | **VERIFIED-DONE**, except the device half | JDK 21.0.12.1, SDK `platforms;android-36` / `build-tools;36.0.0` / `ndk;28.2.13676358` / `cmake;3.22.1` all installed and listed by `sdkmanager --list_installed`; `thirdparty/llama.cpp` (58 entries), `thirdparty/proot` (10), `thirdparty/talloc` (4) non-empty; `assembleFdroidDebug` green. APK-install-on-device proof: **[HS1]**, no device on this host |
| 1 — Fork Hygiene, Rebrand, Sync Automation | **VERIFIED-DONE**, except the coexistence half | `applicationId = "com.hermes.app"` (`app/build.gradle.kts:29`, touchpoint #1); 4 overlay files under `app/src/fdroid/res/`; keystore + `local.properties` present and git-excluded; both GitHub labels live (`upstream-sync E11D48`, `contract-change F59E0B`); sync dry-run exit 0. Side-by-side install: **[HS1]** |
| 2 — GAP_ANALYSIS.md | **VERIFIED-DONE** | Nothing. `GAP_ANALYSIS.md` exists and every cited path was re-verified against the tree this session |
| 3 — Autopilot Memory v1 | **VERIFIED-DONE** for code + JVM tests; **INCOMPLETE** for the on-device half | Sources, worker triggers, notifier, caps, provenance all present; 48 autopilot JVM tests green; `androidTest` compiles and packages. `connectedFdroidDebugAndroidTest` cannot run: **[HS1]** |
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
| Device / emulator (`adb`) | **absent** | `adb devices` → `List of devices attached` (empty); host is `x86_64`, app is arm64-v8a only, so no local emulator |

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
| APK installed + launching on device | **[HS]** blocked: no device/emulator on this machine | — |

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
| Both APKs coexist on device | **[HS1]** blocked: no device | — |

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
| `androidTest` **runs** on device | **[HS1]** blocked | `adb devices -l` → `List of devices attached` (empty); host is `x86_64`, app is arm64-v8a only, so no local emulator can run it. Test code is written and packaged: `AutopilotMemoryInstrumentedTest.kt` (3 tests: seeded facts land in the real store; undo restores byte-for-byte; circuit breaker rolls back on device) |

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
| Test proof: circuit breaker rolls back a bad adaptation | **done** | `CircuitBreakerTest` 9 tests, 0 failures (JVM); the same scenario also exists as an on-device test `theCircuitBreakerRollsBackABadAdaptationOnDevice` |

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

## Human-setup required ([HS] registry)

| # | Item | Impact if missing |
|---|---|---|
| HS1 | USB/OTG Android device (or emulator) with `adb` | device install, screenshots, androidTest execution |
| HS2 | Android Studio / on-device demo session | manual UI demo (secondary; tests are primary proof) |
