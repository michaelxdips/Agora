# CODE_MAP.md — every file this fork owns

Written for the owner's file-by-file review (§12A.2). One row per file. `Lines` is the count at the
end of the Phase-12 session; a line count is a hint, not a contract.

**Status** is one of:

* `new` — created in the Phase-12 session
* `changed` — modified in the Phase-12 session
* `unchanged` — untouched in this session (not necessarily untouched since the fork began)
* `upstream` — exists upstream as well, so it carries a line budget in `UPSTREAM_TOUCHPOINTS.md`

**Review?** — `yes` means *I* think this file is worth a close read, with the reason. Honesty in this
column is worth more than a tidy table: a file I am unsure about is flagged even when the tests pass.

---

## 1. `wear/` — the watch app (fork-only, no upstream ancestor)

| File | Lines | Role | Status | Review? |
|---|---|---|---|---|
| `wear/build.gradle.kts` | 109 | module config, signing, the deliberately minimal dependency list | changed | yes — the `androidx.fragment:1.8.5` constraint is load-bearing for `lintVitalRelease`; removing it fails the release build |
| `wear/src/main/AndroidManifest.xml` | 74 | watch manifest: launcher activity + three listener services | changed | yes — `PairingAckListenerService` is new; a wrong `intent-filter` fails silently (no crash, no pairing) |
| `wear/src/main/res/xml/network_security_config.xml` | — | cleartext exception for `127.0.0.1`/`localhost` | unchanged | yes — `WearConfig.isValid()` accepts a localhost URL that this file must also permit, or the request dies as `UnknownServiceException` |
| `wear/src/main/res/values/strings.xml` | 5 | `app_name`, `wear_speak_prompt` | changed | no |
| `wear/src/main/java/.../wear/WearBuildInfo.kt` | 30 | product name, maintainer, URLs — one source for the watch | new | no |
| `wear/src/main/java/.../wear/WearChatClient.kt` | 152 | the watch's own OkHttp call to the OpenAI-compatible endpoint | unchanged | yes — the shared `OkHttpClient` and the 60 s read timeout are both deliberate; the timeout is the one thing I would still tighten (P2, not done) |
| `wear/src/main/java/.../wear/WearConfig.kt` | 86 | config model + encrypted store | unchanged | yes — `isValid()` is the single validation gate; the localhost escape hatch is what makes BYOK testable |
| `wear/src/main/java/.../wear/WearCoreContext.kt` | 92 | derives the system prompt from the memory snapshot, strips persona blocks | unchanged | yes — this is where the "the block is the unit, not the marker line" bug lived |
| `wear/src/main/java/.../wear/WearCrypto.kt` | 88 | AES-256-GCM at rest, key in the Android keystore | unchanged | yes — security-relevant; the `ponytail:` note states the ceiling (no rotation, no biometric binding) |
| `wear/src/main/java/.../wear/WearListeners.kt` | 147 | Data Layer receivers: config, memory, pairing ack | changed | yes — the config listener now also publishes to `WearSignals`; that publish is the entire 4.3 fix |
| `wear/src/main/java/.../wear/WearMainActivity.kt` | 522 | the chat screen: composer, voice, queue, held-question UI, debug panel | changed | yes — the largest file in the module. The held-question cards (Send now / Discard) and the drop notice are new; the drain itself moved out to `WearQueueDrainer` |
| `wear/src/main/java/.../wear/WearOfflineQueue.kt` | 105 | persisted queue, exactly-once on reconnect | unchanged | yes — write-then-rename is the crash-safety mechanism; `MAX_ATTEMPTS` is the give-up rule |
| `wear/src/main/java/.../wear/WearPairing.kt` | 167 | pairing: transport interface, real Data Layer transport, the decision logic | new | yes — the seam exists because the transport cannot be proved on this machine; read the interface first, then the tests |
| `wear/src/main/java/.../wear/WearQueueDrainer.kt` | 80 | the drain pass, extracted from the composable so it can be tested | new | yes — **this file caused the session's own regression** (a dropped dispatcher). The `withContext(Dispatchers.IO)` on `drain` is the fix; the sentinel test guards it |
| `wear/src/main/java/.../wear/WearSetupScreen.kt` | 282 | the two-way setup screen: BYOK fields + real pairing button | changed | yes — the pairing button now launches a request and reports the real outcome; the old version printed a fixed string |
| `wear/src/main/java/.../wear/WearSignals.kt` | 31 | process-wide StateFlows the listeners publish and the UI collects | new | no — small and single-purpose |
| `wear/src/main/java/.../wear/WearTheme.kt` | 46 | `WearHermesTheme`, dark-first for OLED | unchanged | no — reported as an "orphan" by the scan; it is used through the function name, verified |
| `wear/src/test/.../WearChatClientTest.kt` | 242 | request shape, parsing, localhost acceptance | unchanged | no |
| `wear/src/test/.../WearCoreContextTest.kt` | 85 | core-context derivation incl. persona-block stripping | unchanged | no |
| `wear/src/test/.../WearOfflineQueueTest.kt` | 106 | exactly-once contract | unchanged | no |
| `wear/src/test/.../WearPairingTest.kt` | 122 | every pairing branch against a fake transport | new | yes — read together with `WearPairing.kt`; this is the pairing proof that exists |
| `wear/src/test/.../WearQueueDrainerTest.kt` | 168 | drain order, give-up rule, showResult rule, drop reporting | new | yes — the launch-drain regression lives here |
| `wear/src/test/.../WearMainThreadSentinelTest.kt` | 100 | source tripwire: a dispatcher must wrap each network call | new | yes — it is a *source* assertion, which is unusual. The reason is in the file's KDoc |

## 2. `app/.../autopilot/` — the phone-side feature set (fork-only)

| File | Lines | Role | Status | Review? |
|---|---|---|---|---|
| `AdaptationHistoryPresenter.kt` | 40 | history rows for the UI | unchanged | no |
| `AdaptationLog.kt` | 151 | the audit log every memory write goes through | changed | yes — the maintainer header was added; the undo contract is the important part |
| `AutopilotControlsSection.kt` | 85 | settings controls | unchanged | no |
| `AutopilotNotifier.kt` | 90 | "N memories updated" notification | changed | no — one string (`Hermes X`) |
| `AutopilotSettings.kt` | 75 | cap + toggles | unchanged | no |
| `AutopilotTriggerObserver.kt` | 53 | session-idle trigger | unchanged | no |
| `CircuitBreaker.kt` | 79 | stops repeated reflection failures | unchanged | no |
| `HermesBuildInfo.kt` | 30 | product name, maintainer, URLs — one source for the phone | new | no |
| `MemoryApplier.kt` | 151 | snapshot-first writes with rollback | changed | yes — the write/rollback ordering is the data-safety core |
| `PersonaApplier.kt` | 140 | injects/removes persona blocks, reads state back | changed | yes |
| `PersonaCostReport.kt` | 57 | honest token-cost numbers | unchanged | no |
| `PersonaReconcileWorker.kt` | 70 | periodic drift repair | unchanged | no |
| `PersonaRepository.kt` | 175 | vendored rule texts | unchanged | no |
| `PersonaStartup.kt` | 23 | one-line startup hook | unchanged | no |
| `PersonaStore.kt` | 131 | block delimiters + `stripAll` | changed | yes — `stripAll` is called on the watch-sync path; if it ever under-strips, persona text reaches the watch's 500-token budget |
| `PersonaUpdater.kt` | 115 | checks the vendored refs | unchanged | no |
| `ReflectionCaller.kt` | 105 | one reflection request through the normal provider stack | unchanged | no |
| `ReflectionEngine.kt` | 87 | the reflection pass | changed | no |
| `ReflectionProtocol.kt` | 115 | extraction prompt + JSON parsing | changed | yes — the two `catch (_: Exception)` blocks are annotated as to why they have no cancellation branch |
| `ReflectionWorker.kt` | 144 | WorkManager entry | unchanged | no |
| `SettingsAdaptationHistoryPage.kt` | 261 | Adaptation History screen | unchanged | no |
| `SettingsPersonasPage.kt` | 231 | Personas screen | unchanged | no |
| `SkillCandidateDetector.kt` | 35 | finds reusable candidates | unchanged | no |
| `SkillSynthesisProtocol.kt` | 97 | synthesis prompt + parsing | changed | yes — same annotation as `ReflectionProtocol` |
| `SkillSynthesizer.kt` | 70 | writes a skill through `SkillManager` | unchanged | no |
| `wearsync/PairingListenerService.kt` | 100 | phone half of pairing | new | yes — read with `WearPairing.kt`. It runs the same push code as the settings screen on purpose |
| `wearsync/SettingsWatchSetupPage.kt` | 137 | the Watch setup screen (now reachable) | changed | yes — 4.1's fix is `SettingsScreen.kt`; this file changed only to resolve its own dependencies from `AppContainer` |
| `wearsync/WatchSync.kt` | 181 | the two pushes + `sendConfigToWatch` | changed | yes — `sendConfigToWatch` is shared by the button and the pairing listener so they cannot disagree |

## 3. `app/.../autopilot/` tests

`AdaptationHistoryPresenterTest` (93), `AutopilotTriggerObserverTest` (102), `CircuitBreakerTest` (236),
`FakeAdaptationLogDao` (78), `MemoryApplierTest` (254), `PersonaApplierTest` (218),
`PersonaFidelityTest` (97), `PersonaIsolationTest` (94), `PersonaStoreTest` (157), `PersonaUpdaterTest`
(117), `ReflectionProtocolTest` (243), `SkillSynthesizerTest` (180),
`UpstreamContractSentinelTest` (158) — all **unchanged** this session. `UpstreamContractSentinelTest` is
the one worth knowing about: it reads upstream files from disk and asserts the exact symbols the fork
depends on, so an upstream release that moves one fails the sync loudly.

Instrumented (`app/src/androidTest/.../autopilot/`): `AutopilotMemoryInstrumentedTest` (183),
`PersonaInstrumentedTest` (115), `PersonaUpdaterInstrumentedTest` (154) — **unchanged**.

## 4. Upstream files this fork edits

Every one of these carries a line budget in `UPSTREAM_TOUCHPOINTS.md` and a `HERMES INTEGRATION POINT`
marker. **The guard enforces both**; run `bash scripts/touchpoint_guard.sh` before reviewing.

| File | Budget | Changed this session? | Why the fork touches it |
|---|---|---|---|
| `app/build.gradle.kts` | 16 | yes | `applicationId = com.hermes.app`; instrumentation runner; the watch-sync dependency |
| `settings.gradle.kts` | 4 | no | includes the `wear` module |
| `gradle/libs.versions.toml` | 10 | no | Wear/Data-Layer dependency aliases |
| `.gitignore` | 9 | yes (6 → 9) | module `build/` trees and `_*.log` scratch logs |
| `app/src/main/AndroidManifest.xml` | 6 | yes | `PairingListenerService` registration |
| `app/src/main/res/values/strings.xml` | 4 | no | untouched — the rebrand rides on flavor overlays |
| `.../ui/settings/SettingsScreen.kt` | 32 | yes (24 → 32) | Adaptation History + Personas + **Watch setup** entries and dispatches |
| `.../ui/settings/SettingsAboutPage.kt` | 8 | yes | the fork-identity row |
| `.../MainActivity.kt` | 40 | no | notification → Adaptation History; persona startup |
| `.../viewmodel/GenerationRequestBuilder.kt` | 8 | no | persona blocks stripped where active memory enters a request |

## 5. Flavor overlays — `app/src/fdroid/res/` and `app/src/play/res/`

Both flavors now carry an **identical** set: `values/strings.xml` (41 strings) plus eleven locale
overlays (`values-ar` … `values-zh-rTW`), and a `values/wear.xml` declaring the `hermes_phone`
capability. The play flavor had **no** locale overlays before this session, which is why a German user
saw an app called "Agora".

These paths are upstream-**empty**, so they are additions rather than edits and the guard allows them
by prefix. That is deliberate: `main/res/values/strings.xml` must not be edited, because
`SettingsResourceContractTest` asserts every locale under `main/res` carries every key defined in
`main/res/values`, and the fork's strings are English-only.

## 6. `scripts/` and `.github/`

| File | Role | Status |
|---|---|---|
| `scripts/touchpoint_guard.sh` | parses `UPSTREAM_TOUCHPOINTS.md` as data; fails on an unregistered upstream edit, an over-budget file, a missing marker, or a tracked secret | unchanged |
| `scripts/upstream_sync.sh` | syncs upstream behind the guard (`SYNC_DRY_RUN=1` for a dry run) | unchanged |
| `scripts/persona_update.sh` | re-vendors the persona rule texts | unchanged |
| `scripts/native-tests/`, `round_icon.py`, `test-native-utf8.py` | native/UTF-8 helpers | unchanged |
| `.github/workflows/upstream-sync.yml` | scheduled sync (needs HS3 to run) | unchanged |

## 7. `_tools/` (outside the repo) — the proof scripts

| File | What it proves |
|---|---|
| `mock_provider.py` | an OpenAI-compatible endpoint on the host, with `/__fail` and `/__ok` toggles, reachable from the emulator over `adb reverse` |
| `p0_autodrain_proof.py` | **the auto-drain end-to-end**: configure BYOK → 503 holds the question → 200 + relaunch delivers it with no user action. This is the script that caught the `NetworkOnMainThreadException` regression |
| `wear_byok_drive.py` | drives the watch's BYOK UI by real bounds |
| `maintainer_proof.py` | reads the watch's Debug panel and the phone's About screen; asserts the maintainer and both URLs are on screen |
| `phone_proof.py` | opens Settings → *Watch setup* (the 4.1 fix) and About on the phone, from real bounds |

## 8. Root documentation

`AGENTS.md`, `ARCHITECTURE.md`, `CODE_MAP.md` (this file), `NOTICE.md`, `PRIVACY.md`, `README.md`,
`ROADMAP.md`, `STATUS.md`, `UPSTREAM_SYNC.md`, `UPSTREAM_TOUCHPOINTS.md`, `V2_BACKLOG.md`. Root
Markdown is allowed as a *class* by the guard, so a new document does not need a registry entry — see
the comment in `scripts/touchpoint_guard.sh` for why that is safe.

The audit and mandate documents this fork accumulated (`GAP_ANALYSIS.md`, `AUDIT_PLAN.md`,
`AUDIT_REPORT.md`, `CLEANUP_SCAN.md`, `CLEANUP_REPORT.md`, `HANDOVER.md`, `MEGA_PROMPT_*.md`) were
moved out of the repository before it was published. `STATUS.md` flags each place where one of them
is cited as the source of a recorded result.
