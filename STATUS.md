# STATUS — Hermes fork of Agora

Owner: Michael (`michaelxdips`) · Fork of [`newo-ether/Agora`](https://github.com/newo-ether/Agora) · Licence: MIT

Evidence standard (N12): every phase exit carries command output / artifact paths below.
No proof = phase not done. Human-setup items are marked **[HS]** and never block progress (N6).

---

## CURRENT STATE — 2026-09-19 (read this first; everything below is dated history)

Every number here was produced by a command run on this date. A phase section further down describes
the repo **as it was when that phase closed** and may legitimately contradict this block.

| | Value | Command |
|---|---|---|
| `main` HEAD | `e46fb037` | `git rev-parse --short main` |
| Versions | `3.0.4-hermesx` / `versionCode` 35, both modules | `app/build.gradle.kts`, `wear/build.gradle.kts` |
| Releases | `v3.0.4` (latest), `v3.0.3`, `v3.0.2`, `v3.0.1` (pre-release) | `gh release list -R michaelxdips/Agora` |
| App unit tests | **2658 tests, 0 failures, 0 errors, 3 skipped** (406 XML files) | `./gradlew :app:testFdroidDebugUnitTest` |
| Wear unit tests | **159 tests, 0 failures, 0 errors** (15 XML files) | `./gradlew :wear:testDebugUnitTest` |
| Play flavor tests | 2641 tests, 0 failures, 0 errors, 3 skipped | `:app:testPlayDebugUnitTest` |
| Kotlin size gate | 1055 files, maximum 800 lines, 0 baseline entries | `verifyKotlinFileSize` |
| Upstream position | `main` is **113 ahead** of the fork point `914e7c8d`; upstream is **0 ahead** | `git rev-list --left-right --count upstream/master...main` |
| Release certificate | `7188ce70…aa56d7` on **both** published APKs | `bash scripts/verify_release_provenance.sh v3.0.4` → **PASS, all four claims** |
| Published asset digests | match `SHA256SUMS` byte-for-byte | same script, claim 2 |
| Release signing | **fail-closed**: `assembleFdroidRelease` with no keystore now exits 1 instead of signing with the debug key | `./gradlew assembleFdroidRelease` on a clean `local.properties` |
| Branch protection | **off** — `gh api …/branches/main/protection` → `404 Branch not protected` | same |
| Wear instrumentation | **RAN on 2026-09-19** — 12 tests on both AVDs, 0 failures; it caught one real test defect on first execution (see Session 4) | `./gradlew :wear:connectedDebugAndroidTest` |
| App instrumentation | **RAN on 2026-09-19** — 12 tests on both AVDs, 0 failures; caught the stale grounding quote on first execution | `./gradlew :app:connectedFdroidDebugAndroidTest` |
| R8 phone release | **ON** (`isMinifyEnabled = true`, `isShrinkResources = true`); APK 49,631,099 → **29,497,445 bytes** (−40.6%) | `./gradlew :app:assembleFdroidRelease` |
| R8 keep verification | 13/13 dex assertions present (JNI types, `NativeChatCallback`, workers, manifest components, `FdroidSandboxManagerFactory`) | `grep -l <class> classes*.dex` on the release APK |
| R8 device smoke | **PASS** — release APK installed on `emulator-5554`, launched, no crash; `UpdateCheckWorker`, `AutoBackupWorker`, `MemorySnapshotPushWorker` all `Worker result SUCCESS` in logcat | `adb install` + `dumpsys jobscheduler` + `WM-WorkerWrapper` log |
| Wear in CI | `:wear:testDebugUnitTest` in the `test` job, `:wear:assembleRelease` + `:wear:lintVitalRelease` + APK upload in the `build` job | `.github/workflows/build.yml` |
| CI release verification | `apksigner verify --print-certs` on both APKs + a hard fail when their certificates differ + `SHA256SUMS` generated in CI | `.github/workflows/build.yml` |
| `CODE_MAP.md` | generated: `bash scripts/gen_code_map.sh --check` → up to date | same |

**Session 4 opened the two items this file had carried forward honestly, and closed both with the
missing evidence.** (1) *The phone release is no longer unminified*: R8 is on, the keep rules are
derived from the C++ `FindClass`/`GetMethodID` call sites and the manifests, and the whole gate the
plan demanded — build, full suite, device smoke — ran green, including a release-APK install on the
emulator where the R8-sensitive workers reported `SUCCESS`. (2) *The watch instrumented suite has now
been executed*, on both AVDs, and its first execution immediately failed one test — `MATCH_DEFAULT_ONLY`
on a launcher query returns an empty list for a correct manifest — which is exactly the class of bug
that only a real device run surfaces; fixed and re-run green. The phone→watch **Data Layer transport**
itself remains unproven end to end (`Accounts: 0` on both emulators; both apps installed and launched,
`adb devices` non-empty), and is still recorded as HS4 rather than implied by the contract test that
now guards the wire format.

### Session 5 — 2026-09-19 (wear audit: 10 verified findings, 9 fixed, 1 disproved)

A read-only audit of `wear/src/main/java/` produced ten findings; each was verified in the source
before any change, and one was **disproved** by reading the Play Services bytecode rather than being
"fixed".

**Certificate locality-field purge (git history rewrite).** The locality field of the release
certificate's DN was still present in the repository's *history* (12 commits across `README.md`,
`STATUS.md`, `MEGA_PLAN.md`, `CLEANUP_REPORT.md`, `app/src/fdroid/res/values/colors.xml`), in 7 tag
targets, in the release notes of v3.0.2–v3.0.4, and — worst — in the **public CI log** of run
35441407423, where the verification step echoed `apksigner --print-certs` output verbatim. All four
surfaces were cleaned:

| Surface | Action | Verified by |
|---|---|---|
| Release notes v3.0.2/3/4 | literal DN replaced with a digest pointer | `gh release view … --jq .body` → 0 hits |
| CI log run 35441407423 | run deleted | `gh run view` → HTTP 404; 60 remaining runs scanned → 0 hits |
| Workflow | `tee` removed; only the SHA-256 digest is printed | `build.yml` L203-206 |
| Git history + 7 tags | `git-filter-repo --replace-text --replace-message`, scoped to `360ae4f8..main` and the seven fork-side tags so upstream objects (which carry `gpgsig` headers) keep their hashes | fresh clone from GitHub: 0 hits in messages, trees, tag messages, and all 17k reachable blobs; `merge-base upstream/master main` still `360ae4f8` |

**Honest limitation, carried forward:** GitHub keeps unreferenced objects until its own GC runs. The
pre-rewrite SHAs still answer `HTTP 200` on the REST API and render in the web UI when requested
directly (checked: `de38b199`), although nothing reachable links to them any more. Removing them
requires GitHub Support; time-based GC may reclaim them. This is stated here rather than implied to
be gone.

**A latent CI bug this surfaced (found, reproduced, fixed).** After the rewrite, CI failed with 10
"unregistered upstream file modified" for files under `app/src/.../remote/` — files *upstream* had
changed (`360ae4f8..b168f266`) and this fork had never touched. Root cause: `actions/checkout@v4`
clones shallow, `git fetch --depth=1 upstream` adds no history, so `git merge-base` fails and the
guard's fallback (`DIFF_ARGS=${MERGE_BASE:-$UPSTREAM_REF}`) compared HEAD against the **upstream
tip** — turning every post-fork-point upstream commit into a fork edit. Fixed in
`scripts/touchpoint_guard.sh` (merge-base failure is now a FAIL that names the fix) and
`build.yml` (`fetch-depth: 0`; upstream fetched as a full graph, `--filter=blob:none` where
supported). Both shapes reproduced locally: the new shape PASSes, the old shape now fails with the
named fix and zero false positives.

| # | Finding (verified at `file:line`) | Verdict |
|---|---|---|
| 1 | `WearListeners.kt:59` deleted the credential item even when `WearConfigStore.write` failed — on a full disk the key then existed nowhere | **FIXED**: `write` returns `Boolean`; the item stays for the next push |
| 2 | `WearChatClient.kt:111` built the request *outside* the `try`; a key with a newline (which `isBlank()`/`isValid()` accept) made OkHttp throw and broke the "never throws" contract | **FIXED**: build inside the `try`, key trimmed |
| 3 | `WearAtomicFile.kt:39` used a fixed temp name — concurrent writers raced, the loser's `copyTo` threw and the write reported `false` silently | **FIXED**: unique temp + per-path lock; mutation-proved (test red 2/3 without the lock, green 5/5 with it) |
| 4 | `WearAtomicFile.kt:45` copy fallback truncated the destination in place and leaked the temp on throw | **FIXED**: delete-then-rename fallback, `finally` cleanup |
| 5 | `WearSetupScreen.kt:94` late seed overwrote in-progress typing when the keystore read finished after the first keystroke | **FIXED**: seed bails when any field is non-empty |
| 6 | `WearSetupScreen.kt:89` kept the API key in `rememberSaveable`, i.e. plaintext in the saved-instance-state Bundle | **FIXED**: key is plain `remember`, re-seeded from the encrypted store |
| 7 | `WearConfig.kt:29` `isValid()` accepted `"https://"` (no host) and rejected `HTTPS://…` / `" https://…"` | **FIXED**: host parsed and required, scheme case-insensitive, value trimmed |
| 8 | `WearPairing.kt:255` clearing the ack slot with `.value = null` could destroy a live answer that arrived between read and clear | **FIXED**: `compareAndSet` |
| 9 | `WearPairing.kt:199` `FILTER_REACHABLE` conflated "app not installed" with "phone out of range" | **FIXED**: second `FILTER_ALL` query; new `PHONE_UNREACHABLE` state with its own sentence |
| 10 | Claim: `onDataChanged` runs on the main thread, so the blocking delete causes ANR | **DISPROVED** — `WearableListenerService` (19.0.0) creates its own `HandlerThread` and posts callbacks to it (`zzs.post`), verified in the bytecode; no change made |

Tests: `WearSession5FixTest` (9 tests, new) plus updates to `WearChatClientTest`,
`WearFieldSurvivalSourceContractTest`. Wear suite **149 → 159, 0 failures**; full suite
`2658 / 2641 / 159`. The `#3` race test is honest about being probabilistic: it fails 2 of 3 runs
against the mutated (unlocked) code and passes 5 of 5 against the fixed code.

### Session 4 — 2026-09-19 (R8 gate, instrumented suites executed, self-update channel, audit sweep)

Opened against `main` @ `4f816a56`, `upstream/master` @ `360ae4f8`. (Session 5 rewrote history to
purge the certificate's locality field; that commit is `cf45a410` today — the v3.0.4 tag target.)
Two emulator AVDs were created
(`hermes_phone` API 36 x86_64, `hermes_wear` API 34 wear x86_64) and both stayed attached for the
whole session — `adb devices` → `emulator-5554 device`, `emulator-5556 device`.

**Automated checks that now exist, and what each one proves**

| Check | Command | Proves |
|---|---|---|
| Phone↔watch wire contract | `./gradlew :app:testFdroidDebugUnitTest --tests "*WatchProtocolContractTest"` | every DataMap path, key and version the phone writes is the one the watch reads (config, memory, pairing handshake, capability, no credential in the request). 9 tests; the two sides live in different modules, so this is the only automatic guard against a silent rename |
| Self-update channel | `--tests "*UpdateChannelTest"` | the download URL is derived from the offered version and pins the fdroid asset, the bus delivers an offer, the byte cap is sane |
| Wear queue throttle | `--tests "*WearQueueDrainerTest"` | a `Retry-After` is persisted on the entry as a future deadline and the next pass refuses to retry; past the deadline it sends again; hostile headers are capped on disk. Mutation-proved: dropping the persistence turns 2 tests red |
| Send-now contract | `--tests "*WearSendNowContractTest"` | the pre-fix shape double-sends (kept as an executable description), the fixed shape leaves nothing for the drain, a failed send-now keeps the question held |
| Wear platform behaviour | `:wear:connectedDebugAndroidTest` (2 AVDs) | keystore round-trip, config not plaintext, truncated config rejected, all three listeners resolve from the manifest, launcher declared and exported, standalone metadata, memory cache clearing |
| Phone autopilot behaviour | `:app:connectedFdroidDebugAndroidTest` (2 AVDs) | facts land in the real Agora store and undo restores bytes, the circuit breaker rolls back, a new file is removed on undo |

**Two real defects were found by running the instrumented suites for the first time**

* `WearPlatformInstrumentedTest.theLauncherActivityIsDeclaredAndExported` queried with
  `MATCH_DEFAULT_ONLY`, which matches filters that declare `CATEGORY_DEFAULT`; a launcher activity
  declares `MAIN` + `LAUNCHER` only and must not declare `DEFAULT`. The query therefore returned an
  empty list on a correct manifest — the assertion, not the manifest, was wrong. Confirmed against
  the device before changing anything: `cmd package query-activities` on the same emulator found
  `com.hermes.app/com.newoether.agora.wear.WearMainActivity` (`match=0x108000`). Fixed, plus the
  `exported` claim the test's own name makes.
* `AutopilotMemoryInstrumentedTest` quoted `"quote N"` against a transcript that never contained it.
  The Session-3 grounding check (`ReflectionProtocol.grounds`) refuses an op whose quote is absent
  from the transcript — by design — so the test applied 0 of 3 ops. The test predated the check and
  had never run on hardware. Fixed by quoting text that is actually in the transcript; the test now
  measures the apply path it is named for.

**Self-update channel (`app/src/main/java/com/newoether/agora/autopilot/update/`)**

Seven fork-only files, no upstream file touched except the two one-line integration points already
registered (#5 `MainActivity`, #17 `AgoraApplication`) and one manifest receiver (#2).

* `UpdateCheckWorker` — daily periodic check, WorkManager, honours the existing `autoUpdateCheck`
  toggle; only the fdroid flavor (`bool/hermes_self_update_enabled` in `src/fdroid` vs `src/play`).
* `UpdateCheckStore` — the worker's finding is **persisted**, because the bus is a rendezvous and the
  worker's whole use case is "the app is closed"; without it an offer found in the background was
  dropped the instant the worker exited.
* `UpdateCheckBus` / `UpdateChannelUi` — the offer reaches the same dialog the startup check uses,
  re-validated against the live repository first, with a catch-up pass for an offer stored while the
  UI was down.
* `UpdateDownloadAction` — the "Download & install" tap (notification action; the dialog itself is
  upstream-owned and cannot grow a fork button).
* `UpdateDownloadWorker` / `UpdateInstaller` — download with a 200 MB cap into the app cache, then a
  `PackageInstaller` session. **No `REQUEST_INSTALL_PACKAGES`**: a session is the platform's own
  install path and still shows the system confirmation.

**R8 for the phone release — the gate, in order**

| Step | Result |
|---|---|
| `app/proguard-rules.pro` keeps, derived from `llama_chat_template.cpp:32-38,117-271` and `llama_chat_callbacks.cpp:67-81` | `LlamaChatTemplateResult`, `ChatTemplateGrammarTrigger`, `ChatTemplateMessage`, `ChatTemplateTool`, `ChatTemplateToolCall`, `LlamaChatTemplateRequest`, `NativeChatCallback` (+ implementers), `native <methods>`, all manifest components, every `ListenableWorker`, both `SandboxManagerFactory` classes |
| `./gradlew :app:assembleFdroidRelease` | **BUILD SUCCESSFUL**; APK **29,497,445 B** vs 49,631,099 B unminified |
| dex keep assertions | **13/13 KEPT** (the list above, checked by `grep` over `classes*.dex`) |
| Install on `emulator-5554` + launch | process alive, crash buffer empty, `topResumedActivity=com.hermes.app/...MainActivity` |
| Worker smoke in the minified APK | `WM-WorkerWrapper: Starting work for …UpdateCheckWorker` → `Worker result SUCCESS`; same for `AutoBackupWorker` and `MemorySnapshotPushWorker` |
| Full unit suite (fdroid + play + wear) | `2658 / 2641 / 159 tests, 0 failures, 0 errors` → `audit_test_counts.py` exit 0 |

**Audit sweep (10 read-only subagents + 2 re-dispatches)**

Covered: update gap, watch transport, R8, certificate-locality hygiene, GitHub releases, app bugs, wear bugs,
refactor/perf, feature proposals, tests/guards. Reports are read-only inputs; the ones acted on above
are the two instrumented-suite defects, the send-now double-send, the Retry-After throttle, the CI
release-verification gap, and the `.gitignore`/guard secret-hygiene gap. The remaining findings are
listed in this file rather than silently dropped — see **§Session 4 open findings** below.

**Certificate locality-field wipe**

Every copy of the certificate's locality field outside `thirdparty/` is gone: the six doc copies (README ×2, STATUS ×3,
MEGA_PLAN ×1) were replaced with the SHA-256 digest plus a pointer to `apksigner verify --print-certs`
as the source of truth for the DN. The live certificate still carries the old locality field — it
cannot be re-cut without re-signing every published APK, which would break update continuity and Wear
pairing — and that is now stated in the docs instead of repeated as a literal.

**Secret hygiene hardening**

`.gitignore` gains `*.jks`, `*.keystore`, `*.p12`, `*.pfx` (they were only in `.git/info/exclude`,
which is not cloned), and `touchpoint_guard.sh` §3 now also checks `signing.properties`, the
tracked-file scan covers `.p12/.pfx/.pem/.key`, and the history scan covers `signing.properties`.
Guard still **PASS**.

**GitHub release audit — what is clean, what was fixed**

Clean: notes for all four releases carry no tokens, keys, absolute paths or IPs; `SHA256SUMS` matches
the published bytes; both APKs of both recent releases report the same certificate; `permissions:
contents: read`; keystore and `local.properties` never tracked. Fixed in CI: the release job now runs
`apksigner verify --print-certs` on both APKs, fails when their digests differ, and generates +
uploads `SHA256SUMS`; both APK uploads gained `if-no-files-found: error` and `retention-days: 14`.
Left alone deliberately: the tag object exposes the tagger's e-mail (rewriting a published tag is
worse than the disclosure), and the signing key is not rotated (rotation breaks updates and pairing).

**Everything in `MEGA_PLAN.md`'s fix/modify/optimise plan is closed or explicitly recorded as not
done in that document.** The one item that is *verified but unexercised* is the wear instrumented
suite: it compiles and the source set exists, but this machine has no watch image, so no test in it
has been executed. That is stated here rather than counted as a pass.

### Session 2 evidence — the 2026-09-19 audit (see `MEGA_PLAN.md` §Session 2)

| | Value | Command |
|---|---|---|
| Audit findings reconciled | 49 **CONFIRMED**, 2 **REFUTED**, 1 reverted after the suite caught it | verification harness, 52 checks against the live tree |
| Refuted | `ksp="2.3.9"` and `playServicesWearable="19.0.0"` both resolve; the audit's "wrong scheme / likely fails" is false | `~/.gradle/caches/…/com.google.devtools.ksp/…/2.3.9`, `…/play-services-wearable/19.0.0`, `:app:compileFdroidDebugKotlin` + `:wear:compileDebugKotlin` **BUILD SUCCESSFUL** |
| Refuted by the suite | the audit's `ChatSearchDao` "`CROSS JOIN` cartesian" claim. SQLite documents `CROSS JOIN` as a planner hint that pins loop order, not a cartesian product, and upstream commit `0071bd1c` introduced it deliberately. My change made `2624 tests completed, 1 failed`; reverted with `git checkout --` | `SemanticSearchBoundedSourceContractTest.kt:36-41` pins the string |
| Pairing fix, mutation-proved | replacing `ack.serves(requestId)` with the old `ack.answers(requestId)`, **and** forcing `PairingAck.ok = true`, turns **2 of the new tests red** | `./gradlew :wear:testDebugUnitTest --tests "*WearPairingTest*"` → `22 tests completed, 2 failed`; restored → green |
| Full suite after the work | fdroid **2624** / play **2607** / wear **141** — 0 failures, 0 errors | `python scripts/audit_test_counts.py` → exit 0 |
| Kotlin size gate | 1044 files, maximum 800 lines, 0 baseline entries | `verifyKotlinFileSize` |
| `touchpoint_guard.sh` | **PASS** — 28 entries in the guard's data block (26 with a live diff against upstream), none over budget | `bash scripts/touchpoint_guard.sh` → exit 0 |
| `gen_code_map.sh --check` | up to date | exit 0 |
| CI now runs the gates | `touchpoint_guard.sh`, `gen_code_map.sh --check`, `audit_test_counts.py` added to the `test` job — before this, `grep` for those names in `build.yml` was **0** | `.github/workflows/build.yml` |
| Still open, with reasons | R8 for the phone release, `WearMainActivity` split, the sandbox security model, the plaintext-secret migrations, the destructive migrations, the exported listener's permission, and six HIGH behaviour items | `MEGA_PLAN.md` §S2.5 |

---

## STATE REPORT — reconstruction of Phases 0–6 (Step 1, before any work)

Written at the start of the resume/verify session, from repository state only. Repo root
`C:\Users\Michael\Documents\Chatapp\Agora`, branch `main`, HEAD `4f1babff`, tree clean except
untracked `HANDOVER.md` (inspected, coherent, committed in that session; the document has since
been moved out of the repository).

| Phase | Verdict at reconstruction | What was missing |
|---|---|---|
| 0 — Environment & First Build | **VERIFIED-DONE** | JDK 21.0.12.1, SDK `platforms;android-36` / `build-tools;36.0.0` / `ndk;28.2.13676358` / `cmake;3.22.1` all installed; `thirdparty/llama.cpp` (58), `thirdparty/proot` (10), `thirdparty/talloc` (4) non-empty; `assembleFdroidDebug` green; APK installed **and launched** on the `hermes_x86_64` emulator with screenshot proof |
| 1 — Fork Hygiene, Rebrand, Sync Automation | **VERIFIED-DONE** | `applicationId = "com.hermes.app"` (touchpoint #1); 15 overlay files under `app/src/fdroid/res/` (all 12 upstream `app_name` locales covered); keystore + `local.properties` git-excluded; both labels live; sync dry-run exit 0; Hermes and upstream Agora installed and launched side by side on the emulator |
| 2 — GAP_ANALYSIS.md | **VERIFIED-DONE** | Nothing. `GAP_ANALYSIS.md` existed and every cited path was re-verified against the tree in that session (it has since been moved out of the repository) |
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

Build environment used for every Gradle command below (paths generalized; the real ones are local and
never committed):

```bash
export JAVA_HOME="$JDK21_HOME"        # Temurin 21, path is machine-local
export ANDROID_HOME="$ANDROID_SDK"    # Android SDK, path is machine-local
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
| Release keystore wiring (`local.properties`) | **done** | `_tools/hermes-release.jks`; `assembleFdroidRelease` green; `apksigner verify --print-certs` → the fork's release DN, SHA-256 `7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7` |
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

**VERIFIED-DONE.** `GAP_ANALYSIS.md` (94,611 bytes; moved out of the repository since) cites 60 full repo-relative paths; 58 exist on
disk. The two that do not are self-declared non-existent by the document itself and are named as
files **to create**, not as existing code:

| Cited path | Verdict | Evidence |
|---|---|---|
| `app/src/main/java/com/newoether/agora/ui/settings/SettingsAdaptationHistoryPage.kt` | does not exist, and `GAP_ANALYSIS.md:1573-1576`, moved out of the repository since, says so explicitly ("it does **not** exist; it is named in §4.2 purely as the file to create"). Phase 4 delivered it in the Hermes-only path instead: `app/src/main/java/com/newoether/agora/autopilot/SettingsAdaptationHistoryPage.kt`. | `sed -n '1573,1576p' GAP_ANALYSIS.md` |
| `memories/active_memory.md` | an **entry name inside the export zip**, not a repo file: `DataExporter.kt:618-623` writes it, `DataImporter.kt:499-507` reads it (`GAP_ANALYSIS.md:198`; moved out of the repository since). | `grep -n 'memories/active_memory.md' GAP_ANALYSIS.md` |

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

Verification command: `python -c` path-existence sweep over every backticked path in `GAP_ANALYSIS.md` (run before that document left the repository).

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

Stress merge tagged `sync-2026-09-14-stress` → `2b736213` (pre-rewrite `e360491c`). `HANDOVER.md` written, since moved out of the repository (Wear OS
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
(N characters)` and a clean/dirty marker line), not from the DataStore intent.

> **Correction (audit v2.1, A-016).** This row cited `_device_proof/p6-01-personas-page.png`,
> `p6-02-caveman-on.png` and `p6-04-adaptation-history-persona-entry.png`. **None of those files, and
> no `_device_proof/` directory, exists anywhere in the working tree or in git history**
> (`find . -name 'p6-*.png'` → nothing; `git log --all --name-only -- _device_proof` → nothing). The
> three screenshot citations are therefore withdrawn, and the three `p7-*.png` citations below with
> them. What still stands on its own is the command output quoted alongside each claim — the
> `run-as … cat files/active_memory.md` reading of 0 bytes for toggle-off, which is reproducible from
> the repo. Anything that was only ever "proved" by a missing image is now marked UNVERIFIED.

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

### Device verification (Wear OS 5, 384×384)

> **Correction (audit v2.1, A-017).** This heading said `emulator-5558`. No such device exists or
> ever existed on this machine (`adb devices` reports only `emulator-5554` and `emulator-5556`), and
> the same phantom serial is hardcoded in `_tools/wear_byok_drive.py`. The serial is removed from the
> heading rather than guessed at. The four `p7-*.png` citations in the table below are withdrawn for
> the same reason as A-016: the files are not in the tree. The `adb`/`apksigner`/`gradle` outputs
> quoted in the same rows are unaffected — they are commands, not images.

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
| `apksigner verify --print-certs` | **verified**; the fork's release DN, SHA-256 `7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7` — same identity as the phone, which the Data Layer requires |
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

Full detail is in `AUDIT_REPORT.md` (Pass 6; moved out of the repository since). The summary a reviewer needs:

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
| API-key canary in logcat, before and after a real request | **0 occurrences** | `logcat -d` grep, with the debug APK installed so the read works — see the Pass-6 correction in `AUDIT_REPORT.md`, moved out of the repository since |
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

---

## Post-push verification — the update channel against a real release

Run after the README/CI push (`93052a75`), on a clean tree. Question: does the in-app update check
behave correctly when this fork's `applicationId`, signing key and release channel all differ from
upstream's — and what does it do with the fact that upstream has shipped releases and this fork has
none?

### The experiment

`UpdateChecker.check()` was called against the **live GitHub API** from the real compiled class (a
throwaway JVM probe in `app/src/test/.../TempLiveUpdateProbe.kt`, deleted before the commit that
followed; the permanent coverage is `UpdateCheckerTest`'s 8 offline tests). The only variable changed
between runs was the existence of a release on the fork.

| Run | Fork releases | `UpdateChecker.check("3.0.0-hermesx")` returned |
|---|---|---|
| A | none (real state) | `null` — no update offered |
| B | one throwaway release `v3.0.1` | `UpdateInfo(version=3.0.1, url=https://github.com/michaelxdips/Agora/releases/tag/v3.0.1, body=…)` |

Raw, run A:

```
PROBE repo=michaelxdips/Agora url=https://github.com/michaelxdips/Agora
PROBE live check() result=null
PROBE compare(2.1.0, 3.0.0-hermesx)=-1
PROBE compare(3.0.1, 3.0.0-hermesx)=1
```

Raw, run B (`app/build/test-results/testFdroidDebugUnitTest/TEST-com.newoether.agora.autopilot.TempLiveUpdateProbe.xml`):

```
PROBE live check() result=UpdateInfo(version=3.0.1, url=https://github.com/michaelxdips/Agora/releases/tag/v3.0.1, body=## Throwaway release
```

While the throwaway release existed, `GET /repos/newo-ether/Agora/releases/latest` still returned
`v2.1.0` with `assets: [('app-release.apk', 48553937)]` — i.e. upstream **did** have a downloadable
release at the same moment, and the fork's check ignored it. That is the property under test:
upstream's release is not this build's update.

### Proved on the device (not only in a JVM)

Release-signed APK (`app-fdroid-release.apk`) installed on `hermes_x86_64` (`emulator-5556`),
first-launch onboarding completed, then the release created:

```
text="Update available: v3.0.1"
text="A new version of Hermes X is available on GitHub."
text="Throwaway release"
text="Created only to prove the in-app update check end to end, then deleted."
text="•  probe marker"
text="View Release"
text="Later"
```

Raw dump and screenshot: `../_workbench/docs/evidence-updater/positive-dialog-uiautomator.xml`,
`…/positive-dialog.png`. The title renders the fork's own version string, and the body is the
release's, markdown-lite rendered by the existing dialog.

After deleting the release and force-stopping the app, the same build relaunched into the normal
chat screen (`Ask Hermes X anything...` / `No model selected` / `Welcome to Hermes X.`), no update
dialog, `logcat -b crash` empty. Run B → A on one binary, no rebuild.

### Package name, signing key and coexistence — measured

| Claim | Command | Output |
|---|---|---|
| Fork package | `aapt2 dump badging app-fdroid-release.apk` | `package: name='com.hermes.app' versionCode='31' versionName='3.0.0-hermesx'` |
| Upstream package | `aapt2 dump badging upstream-v2.1.0.apk` | `package: name='com.newoether.agora' versionCode='31' versionName='2.1.0'` |
| Different signing identity | `apksigner verify --print-certs` | fork DN (see README; SHA-256 `7188ce70…aa56d7`), upstream `CN=Newo Ether`, SHA-256 `5de26f26…be1aa29` |
| Both installed at once | `adb shell pm list packages \| grep -iE 'hermes\|newoether'` | `package:com.hermes.app` **and** `package:com.newoether.agora` |
| Both report their own version | `adb shell dumpsys package <pkg> \| grep versionName` | `3.0.0-hermesx` / `2.1.0` |
| The check's target is baked into the APK | `grep -aoE 'api\.github\.com/repos/[^"]+/releases/latest' classes*.dex` | upstream APK → `api.github.com/repos/newo-ether/Agora/releases/latest`. Fork APK → no match for that literal, because the fork builds the URL from `HermesBuildInfo.FORK_REPO`; the same probe for `michaelxdips/Agora` matches the fork APK and not the upstream one |
| Cross-signature install is refused | `adb install -r -d app-fdroid-release.apk` over the debug build | `INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.hermes.app signatures do not match newer version` |

The last row is the reason the update check must point at the fork: the two builds are different
signing identities, so an APK offered across that boundary cannot be installed over this app.

### Not claimed

* The check never downloads or installs anything — it opens the release page in a browser. No silent
  APK install path exists to test.
* The 24-hour `UPDATE_INTERVAL_MS` throttle was not exercised (it would need a day of wall-clock, or
  a clock the test cannot move without root). The manual "check for updates" path in Settings → About
  bypasses it and is what the device run above used.
* The fork's releases are live (`v3.0.3` latest, `v3.0.2`, `v3.0.1` pre-release — `gh release list`),
  so this bullet's original claim ("0 releases") was true only of the phase that wrote it and is kept
  here as dated history. See the CURRENT STATE block at the top of this file for the live numbers.

### Environment repaired during this session

`hermes_x86_64` could not boot at the start (missing `system-images;android-36;google_apis_playstore;
x86_64` — only a half-finished installer directory, `132M` of orphaned download in `$ANDROID_HOME/.temp`).
Removed the orphan and installed the image with `sdkmanager`; the AVD then booted and reported
`sys.boot_completed=1`. `ANDROID_SDK_ROOT` must be set alongside `ANDROID_HOME` or the emulator
refuses to start.

---

## Phase 16 — watch render correctness, rip-off audit, layout matrix, release 3.0.3

Session scope: five missions (readable text on the watch, remove what the watch does not need, layout
for a Xiaomi Watch 2, pair-from-phone, release + README). Every number below comes from a command run
in this session; the raw dumps are in [`evidence/wear-render-2026-09-16.md`](evidence/wear-render-2026-09-16.md).

### Gate at the end of Phase 16 (all re-run, not remembered)

| Command | Result |
|---|---|
| `git status --porcelain` | clean at the end of the phase |
| `bash scripts/touchpoint_guard.sh` | `PASS` (14 touchpoints, unchanged budgets) |
| `./gradlew :wear:testDebugUnitTest :app:testFdroidDebugUnitTest verifyKotlinFileSize` | `BUILD SUCCESSFUL` |
| `:wear:testDebugUnitTest` | **82 tests, 0 failures** (was 61) |
| `:app:testFdroidDebugUnitTest` | **2,561 tests, 0 failures** (3 skipped) |
| `SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh` | exit 0 |
| `git ls-files \| grep -cE '\.(jks\|keystore)$\|^local\.properties$'` | `0` |
| `adb shell wm size` / `wm density` | `Physical size: 384x384` / `Physical density: 320` (reset) |

### Mission 1 — text and symbols readable on the watch

**What was wrong.** `WearChatClient.parseContent` returned the provider's `content` verbatim, so a
model answering in Markdown or LaTeX put the markup itself on a 384 px screen that has no renderer for
either. Written as a failing test first (`WearAnswerLeakRedTest`, renamed to `WearAnswerLeakTest` after
the fix):

```
latexFractionBecomesReadable       FAILED  raw LaTeX reached the watch: $\frac{1}{2}$ dari 80 = 40
markdownBoldNeverReachesTheScreen  FAILED  raw bold markers reached the watch: **Jawaban:** 14
headingMarkerAndFenceAreStripped   FAILED  a code fence reached the watch: ``` | # Hasil | ...
superscriptLatexBecomesARealSuperscript  FAILED  raw LaTeX reached the watch: $x^{2}$
4 tests completed, 4 failed
```

**What fixes it.** `WearAnswerText.render(raw)` — one pure-Kotlin decision point, no new dependency
(the watch module stays minimal): explicit LaTeX→Unicode and Markdown→text mapping tables, honest
fallbacks for codepoints this image has no font for, whitespace/control normalisation, and a visible
truncation marker. `parseContent` now returns the rendered text, so the fresh ask, the queue drain and
the offline retry all show the same thing.

**Glyph coverage is measured, not assumed.** The five fonts were pulled off the emulator and their
cmaps read with fontTools:

| Font | Codepoints |
|---|---|
| `DroidSans.ttf` | 2,797 |
| `DroidSansMono.ttf` | 873 |
| `NotoSansSymbols-Regular-Subsetted.ttf` | 4,616 |
| `NotoSansSymbols-Regular-Subsetted2.ttf` | 124 |
| `NotoColorEmoji.ttf` | 1,449 |
| **union** | **8,755 (165 ranges)** |

`NotoSansMath-Regular.ttf` is **not on this image** (`ls /system/fonts | grep -i math` empty,
`grep -ci math /system/etc/fonts.xml` → `0`). Blocks: math operators 256/256, arrows 112/112,
box drawing 128/128, geometric shapes 96/96, Latin-1 96/96, Greek 121/144, superscripts 14/16,
subscripts 28/32, misc technical 232/256. The holes a model can actually emit are handled by the
fallback table: `⁲⁳₏₝₞₟ ⏻⏼ ⎾⎿…`.

`WearFontCoverage.kt` is **generated** from those cmaps, and `WearAnswerTextCoverageTest` fails if the
renderer ever emits a codepoint outside them — it caught `U+2072` passing straight through, which is
exactly the class of bug the table exists for.

**Device proof** (mock provider returning the raw markup, watch at 384×384 @320):

```
raw provider text : '**Jawaban:** 14\n$\frac{1}{2}$ dari 80 = 40\n...'
rendered on screen: 'Jawaban: 14 | 1/2 dari 80 = 40 | x² + y² = r² | Hasil | • poin satu | ...'
```

### Defect found by running it, not by reading it

**Every answer crashed the app.** The answer `Text` sat inside `Modifier.verticalScroll(...)` *and*
inside a `ScalingLazyColumn` item — an `IllegalStateException` from Compose's own constraint check:

```
E AndroidRuntime: FATAL EXCEPTION: main   Process: com.hermes.app
java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity
maximum height constraints, which is disallowed...
    at androidx.compose.foundation.ScrollNode.measure-3p2s80s(Scroll.kt:440)
```

The symptom a user saw was not an error message — the answer simply never appeared. Introduced in
`86de5749` ("A ceiling plus its own scroll keeps the answer readable"); removed, and the list's own
scrolling plus `MAX_ANSWER_LINES` + ellipsis does the job. This is the single most valuable finding of
the session, and it was invisible to every test in the repository.

**Second defect: the voice fallback never rendered.** `voiceUnavailable` was collected into
`voiceProblem` and then rendered nowhere, so on an image with no recognizer (the wear emulator, and a
bare watch) tapping Speak did nothing visible. Same tap after the fix:

```
[69,336][315,384] 'Voice input is not available on this watch. Type instead.'
logcat: W HermesWear: no speech recognizer on this watch
```

### Mission 2 — what the watch does not need, removed

| Candidate | Evidence it was dead | Action |
|---|---|---|
| `androidx.wear.compose:compose-material:1.6.2` | zero imports (`grep 'androidx.wear.compose.material\.'` → empty) | **removed** |
| `androidx.compose.material3:material3` | zero imports (`grep 'androidx.compose.material3'` → empty) | **removed** |
| `WearCrypto.encode` | no caller in `wear/src` or `app/src` (the phone base64-encodes its side) | **removed** |
| `WearChatScreen(ttsReady)` parameter | passed in, never read (its only reader was the unrendered `ttsAvailable`) | **removed** |

Each removal has the three required pieces of evidence: no reference, not a manifest/reflection/R8
entry point, and green build + tests after. `compose.foundation` is now declared explicitly rather than
arriving transitively through the removed artifacts.

Measured: **wear release APK 2,768,539 → 2,763,831 bytes (−4,708, −0.17 %)**; wear tests 82/0 before
and after.

**Kept, with the reason on the record:**

| Kept | Why |
|---|---|
| Core context (`/hermes/memory`, `WearMemoryCache`, `WearCoreContext`, `MemoryListenerService`) | it is the only thing that makes the watch's answers about *this* user. Measured cost: a 1,625-byte snapshot → a **1,624-char / 406 est.-token** `system` message on the wire, absent when no snapshot exists. Whether the *answers* get better is not measurable without a real provider key (HS2) — a local mock has no knowledge — so it stays, and the measurement that would justify removing it is recorded as unproven rather than guessed. |
| `WearQueueDrainer` / `WearOfflineQueue` | exactly-once delivery; a question must not be lost |
| `/hermes/config` path | it is the pair-from-phone path |
| Debug panel | it is the only surface that shows what the watch actually holds, including the core-context token count |

### Mission 3 — layout for a Xiaomi Watch 2 (466×466 @326 = 228.7 dp)

Measured with `wm size` / `wm density` overrides, coordinate space verified via
`dumpsys window displays | grep init=` before every dump:

| Profile | Screen | Text column (composer field) | % of width |
|---|---|---|---|
| small round (AVD) | 384 px @320 = 192.0 dp | 264 px = 132.0 dp | 68.8 % |
| large round | 454 px @320 = 227.0 dp | 326 px = 163.0 dp | 71.8 % |
| **Xiaomi Watch 2** | 466 px @326 = **228.7 dp** | **338 px = 165.9 dp** | **72.5 %** |
| 480 round | 480 px @360 = 213.3 dp | 336 px = 149.3 dp | 70.0 % |
| rectangular | 400 px @320 = 200.0 dp | 276 px = 138.0 dp | 69.0 % |

Every row was re-measured with a freshness check, because the naive version produced a wrong number
twice: a dump can return the **previous** profile's layout if the configuration change has not been
laid out yet. `_workbench/measure_profile.py` now polls `dumpsys window displays` until `cur=`/`app=`
match the target, then requires the measured bbox to **differ** from the previous profile's value, then
requires two consecutive dumps to agree — and refuses to report a number otherwise. All five rows above
passed all three conditions.

The column grows with the screen and its share of the width grows too, so the extra 36.7 dp of a
Xiaomi Watch 2 is spent on text rather than margin.

**A hypothesis that died, and how.** The first pass reported 454 as *identical* to 384 (264×96 px,
x 60..324). That is the signature of a dump taken before the relayout. Re-measured with the coordinate
space printed before the dump and two dumps per profile: 454 is really 326×96 px = 163.0 dp. The
"fixed 8 dp inset wastes the width" finding was an artifact, and no layout change was made on the
strength of it. (Recorded because the discarded hypothesis is part of the evidence.)

Circle fit is computed against the **physical** mask (r = 192 px), not the override: `wm size` moves
the coordinate space but not the round mask. A `ScalingLazyColumn` dump reports the *clipped* bbox of
an item crossing the viewport edge, so raw dumps contain rows that look like text outside the circle.
Brought fully into view and re-measured, **no label has a corner outside the mask at 384, 454 or 466**
(`outside=- by=0.0 px` for `Change key`, `Debug`, `Speak` and `Send` on all three), and label heights
match the 384 baseline within 1 px — the larger screens do not stretch or shrink the text.

Touch targets, measured on the clickable node (not its label) with a freshness gate, and each
`FAIL` classified by whether its bounding box is **clipped by the viewport** or genuinely short:

| Profile | Element | Size | Verdict |
|---|---|---|---|
| 384 | `Send` | 300x100 px = 150.0x50.0 dp | pass |
| 384 | `Debug` | 312x104 px = 156.0x52.0 dp | pass |
| 384 | `Change key` | 278x87 px = 139.0x43.5 dp | **clipped** (y1 = 0: item entering the viewport) |
| 454 | `Send` / `Change key` / `Debug` | 51.5 / 48.5 / 52.0 dp | pass |
| 454 | `Speak` | 276x36 px = 138.0x18.0 dp | **clipped** (y2 = 454 = viewport height) |
| 466 | `Send` / `Change key` / `Debug` | 51.5 / 48.6 / 52.0 dp | pass |
| 466 | `Speak` | 286x39 px = 140.4x19.1 dp | **clipped** (y2 = 466 = viewport height) |
| 466 | composer `EditText` | 338x97 px = 165.9x47.6 dp | **0.39 dp short** — the only real sub-48 measurement |
| 480 | `Send` / `Debug` | 51.1 / 52.0 dp | pass |
| 480 | `Change key` | 358x107 px = 159.1x47.6 dp | **clipped** (y1 = 11) |
| 400 | `Send` / `Debug` | 50.0 / 52.0 dp | pass |
| 400 | `Change key` | 294x94 px = 147.0x47.0 dp | **clipped** (y1 = 2) |

Two honest corrections to what an earlier pass of this document claimed:

* **"every Button is ≥ 48 dp on all five profiles" was wrong.** It was written from a run whose
  400/480 rows were a stale frame — their bounding boxes were byte-identical to 384's
  (`312x128 pos=(36,128)`, `264x96 pos=(60,122)`, `300x100 pos=(42,268)`, …), which is not a
  measurement. Re-measured with the freshness gate, 400 and 480 differ from 384 in every row.
* **Most `FAIL` rows are not small controls.** A `ScalingLazyColumn` clips the item that is entering
  or leaving the viewport, so the dumped bbox is a *partial* item: `Change key` at y1 = 0/2/11 and
  `Speak` at y2 = viewport height. The element itself is a normal Wear `Button`; only the visible
  slice was measured.

The single real shortfall is the composer field at 466×466: 97 px against a 48 dp target of 97.8 px,
i.e. **0.39 dp under**, at 326 dpi. It is the framework's own field height inside the Wear `Card`,
not a value this app's layout sets, and the enclosing `Button` measures 63.8 dp. Left as-is,
recorded rather than rounded up.

`buttons.py` also counted the `ScalingLazyColumn`'s scrollbar (a 2–4 dp tall clickable `View` with no
text inside) as a button; `targets2.py` names each clickable by the label inside it and reports the
scrollbar separately.

**`font_scale 1.3`** (accessibility): buttons scale ~1.3× linearly (at 384: `Change key` 165×36 →
205×45 px, `Debug` 92×36 → 121×48 px). Answer to the phase question: at 466×466 with `font_scale 1.3`
there is **no** room for another button without scrolling — the list already needs a scroll to reach
`Change key`/`Debug`, and `Speak` sits at the viewport edge (y = 478 of 466).

### Mission 4 — pair-from-phone: what is proved, and what is not

`Accounts: 0` on **both** emulators, and the phone APK is not installed on the phone emulator in this
session, so the Data Layer has no reachable node. The watch reports that honestly:

```
W HermesWear: pairing: no node advertises hermes_phone
UI: 'No phone app found. Install it and open it once, or use a key on the watch.'
```

Options were evaluated rather than assumed. **A** (add the same Google account to both emulators) was
not attempted: it needs GUI sign-in into a Google account inside two sandboxes, with the owner's
account. **B** (physical devices) is out of reach. **C** (fake transport + listener branches) is
already in place and green — `WearPairingTest`, 8 tests: no phone, send failed, timeout, ack, blank
ack, hostile ack, no credential in the payload, every status actionable. **D** (mock and claim it
works) was refused.

So: the watch's *decision logic* and the honest UI state are proved; the Data Layer *transport* is
not. **HS4 stays open, unchanged** — "physical watch (or a Data-Layer-paired emulator pair)".

### Mission 5 — release

`versionCode 34`, `versionName 3.0.3-hermesx` in both modules. The touchpoint budget for
`app/build.gradle.kts` did **not** need raising: the two version lines were already inside the
registered diff, so the guard still reports 18/20.

| Artifact | Size (bytes) | SHA-256 |
|---|---|---|
| `wear-release.apk` (local `:wear:assembleRelease`) | 2,763,831 | `3efd6ae56aca457bf357ce4f73445d9e1e60c6afc8380edccd327e42cefc70c2` |
| `app-fdroid-release.apk` (CI, PRoot runtime + release-signed) | measured below, from the published asset | measured below |

CI `Restore signing key` = **success** (not skipped), so the phone APK is release-signed, not
debug-signed — the failure that made `v3.0.1` unusable for pairing.

Both signed `CN=Hermes Local, …`, SHA-256 `7188ce70…aa56d7` — the identity the Data Layer requires to
match on both APKs.

---

## Session 3 — 2026-09-19 (A6 the fabricated fact, one measured optimisation, release 3.0.4)

Handoff: `../HANDOFF-2026-09-19.md`. Opened against `main` @ `ffb14ebf`, `upstream/master` @
`360ae4f8`, `git status --porcelain` empty, `git rev-list --left-right --count upstream/master...main`
→ `0	103`. Every claim below is a command that ran.

### A6 — `ReflectionProtocol` accepted a fabricated fact (FIXED, mutation-proved)

**The defect, as it was in the tree.** `ReflectionProtocol.kt:32` read
`confidence >= MIN_CONFIDENCE && sourceQuote.isNotBlank()`. The quote was never checked against the
transcript, so a model that invented a fact invented its evidence in the same breath and every gate
passed: the schema was strict about *shape* and silent about *truth*. `ReflectionProtocol.kt:77-78`
appended the raw transcript straight after the rules with no delimiter, so text inside it sat in the
same block as the instructions.

**The fix.** `ReflectionOp.isValid(transcript)` requires the quote to **occur** in the transcript
(whitespace runs collapsed on both sides, so an honest re-wrapped quote still grounds and a
paraphrase does not). `ReflectionProtocol.transcriptForPrompt()` is the single preparation used for
both the prompt and the grounding check, so "the string an op is verified against" is by
construction "the string the model was shown" — the two cannot drift. `TRANSCRIPT_BEGIN`/`_END`
delimit the data and rule 7 tells the model that text inside it is data, never instructions;
occurrences of the markers are stripped from the transcript so a message cannot close its own block.
`ReflectionEngine` prepares once per pass and passes it to both the caller and the parser.

**Mutation proof** — the pre-fix behaviour restored in `groundedIn` (with the fix's own tests left
untouched), then:

```
$ ./gradlew :app:testFdroidDebugUnitTest --tests "…ReflectionProtocolTest" --tests "…PersonaIsolationTest"
ReflectionProtocolTest > aQuoteThatOnlyApproximatesTheTranscriptIsRefused FAILED
ReflectionProtocolTest > anInventedFactIsRefusedBecauseItsQuoteIsNotInTheTranscript FAILED
ReflectionProtocolTest > engineDropsAnUngroundedOpAndWritesOnlyTheGroundedOne FAILED
24 tests completed, 3 failed
BUILD FAILED in 8s
```

Restored, same command → `BUILD SUCCESSFUL`. So the tests are red without the fix and green with it;
they assert the behaviour rather than printing a verdict.

**Not claimed:** no provider was called and no model was asked to hallucinate. What is proved is that
an op whose quote is absent from the transcript cannot reach `MemoryApplier` — the check is on the
path, not on a model's willingness to lie.

### Optimisation — the circuit breaker's per-row query (MEASURED, not asserted)

`CircuitBreaker.recordCorrection` called `log.injectionsFor(entry.id)` **inside** a filter over
`log.all()`, so one correction pass cost one DAO query per row of the entire adaptation history.
Reflection runs it on every session that reaches 20 messages.

**Before, measured** — the counting harness with a 50-row journal (the retention cap per file):

```
CircuitBreakerQueryCountTest > one correction pass does not query once per journal row FAILED
java.lang.AssertionError: injectionsFor was called 50 times for 50 journal rows — the per-row query is back
```

**After** — `injectionsForAll()` reads the injection table once and the pass groups it by
`adaptationId`:

```
$ ./gradlew :app:testFdroidDebugUnitTest --tests "com.newoether.agora.autopilot.*"
BUILD SUCCESSFUL in 14s
```

`CircuitBreakerQueryCountTest` pins both numbers: the single `injectionsForAll` per pass and the one
constant-cost `injectionsFor` (the ledger sentinel). The journal scan stays per correction key, and
that is deliberate: a key's rollback mutates rows, so caching the list would re-flag an entry the
previous key already rolled back — which `CircuitBreakerTest.anAlreadyRolledBackEntryIsNeverFlaggedAgain`
pins.

The counter is a **query count, not a wall-clock time**: a millisecond figure would depend on the
machine and on Room's cache, while the number of queries is what the code controls.

### Release metadata (F)

* `fastlane/.../changelogs/32.txt`, `33.txt`, `34.txt` did not exist while `versionCode 34` shipped.
  Added, each written from the matching GitHub release body rather than invented.
* `NOTICE.md` claimed three submodules; `.gitmodules` has two (`thirdparty/talloc` is tracked
  directly — `git ls-files thirdparty/` → `talloc/config.h`, `talloc/replace.h`, `talloc/talloc.c`,
  `talloc/talloc.h`). Corrected.
* `mkdocs.yml` `site_url`/`repo_url`/`repo_name` pointed at `newo-ether/Agora` while the fork runs
  its own Deploy MkDocs workflow. Repointed at the fork after confirming the real Pages URL
  (`gh api repos/michaelxdips/Agora/pages` → `https://michaelxdips.github.io/Agora/`; `curl` → `200`).
  New touchpoint entry #24, budget 16.

### Version bump

`versionCode 34 → 35`, `versionName 3.0.3-hermesx → 3.0.4-hermesx`, both modules.
`ReleaseVersionOrderingTest` gains the same two-direction case the earlier releases carry, so a bump
that forgets the suffix rule fails here instead of on a device.

### Gates, all run after the work

| Gate | Result |
|---|---|
| `./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest verifyKotlinFileSize --rerun-tasks` | **BUILD SUCCESSFUL in 5m 37s**, 91 tasks executed |
| `python scripts/audit_test_counts.py` | fdroid **2635** / play **2618** / wear **141**, 0 failures, 0 errors → exit 0 |
| `bash scripts/touchpoint_guard.sh` | **PASS** (360ae4f8fe73ed035cbbfa838d9fc265ef68779a) |
| `bash scripts/gen_code_map.sh --check` | `CODE_MAP.md is up to date` → exit 0 |

Counts read from the XML (`404` fdroid / `400` play / `13` wear report files), not from the console.

### Still open, unchanged, and why

A1–A5 (credential fan-out, the watch send race, queue idempotency, the global work name, the breaker's
missing open state), B1–B4 (sandbox trust, shared-storage bind, TOCTOU, the exported listener), C1–C3
(migrations, blocked-DB delete), D1–D4 (plaintext secrets), E1–E3 (R8 without a device, the WSL-only
PRoot path, the font table whose named generator `_workbench/gen_coverage.py` is not in the repo).
Each is a design decision or needs hardware/a migration, exactly as the handoff says; none was
drive-by patched. `adb devices` is still empty, so E1's device smoke pass cannot run here.
