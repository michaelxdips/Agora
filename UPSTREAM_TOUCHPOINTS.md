# UPSTREAM_TOUCHPOINTS.md

Registry of every file that exists **upstream** and that this fork edits. `scripts/touchpoint_guard.sh`
parses the block below as data: one entry per line, `path :: max=N`, where `N` is the maximum
number of changed lines (added + removed) the fork is allowed to carry in that file. Exceeding the
budget, or editing an unregistered upstream file, fails the guard.

Rules for entries: keep the diff minimal, mark the edit site with `HERMES INTEGRATION POINT`, and
never remove upstream behaviour — add, don't rewrite.

<!-- GUARD:DATA:START -->
# path :: max=<changed lines allowed>
app/build.gradle.kts :: max=68
settings.gradle.kts :: max=4
gradle/libs.versions.toml :: max=10
.github/workflows/build.yml :: max=140
.github/workflows/mkdocs.yml :: max=4
mkdocs.yml :: max=16
fastlane/metadata/android/en-US/changelogs/32.txt :: max=8
fastlane/metadata/android/en-US/changelogs/33.txt :: max=8
fastlane/metadata/android/en-US/changelogs/34.txt :: max=8
fastlane/metadata/android/en-US/changelogs/35.txt :: max=8
.gitignore :: max=24
app/src/main/AndroidManifest.xml :: max=16
app/src/main/res/values/strings.xml :: max=4
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt :: max=32
app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt :: max=16
app/src/main/java/com/newoether/agora/util/UpdateChecker.kt :: max=130
app/src/test/java/com/newoether/agora/util/UpdateCheckerTest.kt :: max=100
app/src/main/java/com/newoether/agora/MainActivity.kt :: max=48
app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt :: max=8
app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt :: max=20
app/src/main/java/com/newoether/agora/viewmodel/ProviderRegistry.kt :: max=24
app/src/main/java/com/newoether/agora/api/DuckDuckGoScraper.kt :: max=12
app/src/test/java/com/newoether/agora/ui/components/LatexRendererTest.kt :: max=22
app/src/test/java/com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdownTest.kt :: max=40
app/src/test/java/com/newoether/agora/api/DuckDuckGoScraperTest.kt :: max=30
app/src/main/java/com/newoether/agora/AgoraApplication.kt :: max=12
app/src/main/java/com/newoether/agora/api/HttpClient.kt :: max=40
app/src/main/java/com/newoether/agora/api/anthropic/AnthropicProvider.kt :: max=16
app/src/main/java/com/newoether/agora/api/ollama/OllamaProvider.kt :: max=16
app/src/main/java/com/newoether/agora/util/CrashReporter.kt :: max=16
app/src/main/java/com/newoether/agora/remote/RemoteImageCache.kt :: max=32
app/src/main/java/com/newoether/agora/util/SecretCrypto.kt :: max=32
app/proguard-rules.pro :: max=180
app/src/test/java/com/newoether/agora/api/util/ProviderWireFormatRejectionTest.kt :: max=190
<!-- GUARD:DATA:END -->

## Registry

| # | Path | Why | Phase |
|---|------|-----|-------|
| 1 | `app/build.gradle.kts` | `applicationId` → `com.hermes.app`; `testInstrumentationRunner`; androidTest deps for the instrumented verification. **Budget raised 16 → 20 (Phase 15):** `versionCode 31 → 32` and `versionName 3.0.0-hermesx → 3.0.1-hermesx` — the fork's first published release. The two version lines are the only addition; without the raise the guard would have failed on a change that ships the release. **Raised 60 → 64 (Session 3):** `versionCode 34 → 35` and `versionName 3.0.3-hermesx → 3.0.4-hermesx`, the two lines the next release needs | 1, 3, 15, 18 |
| 2 | `app/src/main/AndroidManifest.xml` (raised 6 → 16) | only if the fdroid overlay cannot carry a manifest change — it cannot for a receiver whose class lives in `main` (a flavor overlay cannot register a `main`-source receiver without duplicating it). Pairing listener service + self-update download receiver | 1 |
| 3 | `app/src/main/res/values/strings.xml` | only if the fdroid resource overlay cannot carry `app_name` | 1 |
| 4 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` | Adaptation History entry + `"adaptation"` dispatch + `initialCategory`; Phase 6 persona entry + `"personas"` dispatch |
| 5 | `app/src/main/java/com/newoether/agora/MainActivity.kt` (raised 44 → 48) | notification tap → Settings/Adaptation History (`openAdaptationHistory` flag); Phase 6 persona startup (`PersonaStartup.run`); self-update channel (`UpdateChannelUi.collectOffers` — worker offers collected into the same update dialog the startup check uses) |
| 6 | `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt` | Phase 6 P5 isolation: persona blocks are stripped where active memory enters a request (reflection/synthesis must never see them) |
| 7 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` (raised 24 → 32) | Phase 12: Watch setup entry + `"watch"` dispatch. Budget raised because the page existed but no user could open it (dead code); 6 lines for the row, 4 for the dispatch, 2 for the marker comments |
| 8 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt` | Phase 12: fork identity row on About (product name, maintainer, upstream + fork URLs) — the owner asked for the maintainer to be visible in the app, not only in comments |
| 9 | `.gitignore` (raised 6 → 9 → 24) | Phase 12: `_*.log` — the gate/build logs an agent session writes at the repo root. The previous session committed a build tree once because a pattern was missing; this closes the same hole for scratch logs before it happens. **Session 4:** the signing-key classes (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`) join it. They were covered only by `.git/info/exclude`, which is not cloned — a fresh checkout had no protection until the guard ran, and a key file was one `git add -A` from a public commit |
| 10 | `app/src/main/java/com/newoether/agora/util/UpdateChecker.kt` (max=90 → 130) | Phase 13: the check queried **upstream's** releases (`newo-ether/Agora`), so a fork build could only ever be offered the upstream APK — a wrong install, since the two have different `applicationId`s, signing identities and feature sets. Repointed at the fork, `compare` made public and rewritten so a non-numeric segment is ordered instead of collapsing to 0, and a `CancellationException` branch added. **Raised 90 → 130 (Phase 15):** publishing the first release exposed a second defect in the same file — the fork's own `versionName` carries a suffix (`3.0.1-hermesx`) while a release tag does not (`v3.0.1`), so `compare(tag, current) > 0` was **true for the version already installed** and every device on a release would be offered that same release forever. `isNewer` compares base versions and treats a suffix-only difference as not-an-update. The 40 extra lines cover that method and its KDoc |
| 11 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt` (raised 8 → 16) | Phase 13: the GitHub, issue-tracker, contribute and privacy-policy rows all opened **upstream's** URLs. A bug in Hermes X reported to a tracker for a build upstream does not ship is a bug that cannot be reproduced. Repointed at the fork (11 lines: 4 URLs + the marker) |
| 12 | `app/src/test/java/com/newoether/agora/util/UpdateCheckerTest.kt` (max=100) | Phase 13: a test for the upstream file above. It lives next to its subject, not under `autopilot/`, because the fork-only test directories do not cover `util/`. It is the test whose absence let the upstream-releases defect ship |
| 13 | `.github/workflows/build.yml`, `.github/workflows/mkdocs.yml` | Phase 14 (audit A-015): both workflows triggered on `[master]` only. Upstream's default branch is `master`, but this fork works on `main`, so with `main` never listed **no CI job had ever run on a fork commit**. `main` is added alongside `master` (add, don't rewrite) and both files are registered here because the guard's ALLOWED class covers `upstream-sync.yml` alone. Budgets: 12 changed lines for build.yml (the two branch lists plus the marker comment, raised from 8 in the pre-publication pass to cover the `setup-android` package-list fix below), 4 for mkdocs.yml |
| 14 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt` (max=20) | Upstream commit `360ae4f8` (OpenCode Go provider) pushed this file to 801 physical lines against `build-logic`'s 800-line cap, and upstream's own CI is red for it (run `35171806965`). Ten imports in the file are referenced by nothing in its body, so they are removed rather than the file being split; no behaviour changes. Budget 20 covers the 10 removed import lines plus the marker comment |
| 15 | `app/src/main/java/com/newoether/agora/viewmodel/ProviderRegistry.kt` (max=24) | `getEffectiveBaseUrl` returned **null** for any built-in provider with no explicitly configured base URL (`takeIf { !isBuiltIn(providerName) }`). Chat still worked because the provider classes fall back to their own `defaultBaseUrl`, but every consumer that asks the *registry* got null — and the phone→watch push is one of them, so a user chatting with OpenAI or Anthropic was told "No base URL or model selected. Configure a provider first." The fix returns the provider's own `defaultBaseUrl` when nothing is configured. Regression test: `app/src/test/java/com/newoether/agora/autopilot/ProviderBaseUrlResolutionTest.kt`, which failed (`expected:<https://api.openai.com/v1> but was:<null>`) on the pre-fix line and passes after it |
| 16 | `app/src/test/java/com/newoether/agora/ui/components/LatexRendererTest.kt` (max=20) | `testAllDollarCases` and `testDollarAmountNotLatex` computed a `PASS`/`FAIL` string, printed it, and asserted nothing, so a real regression could not fail them. Proved empirically: with `parseInlineDollarMath` genuinely disabled in the parser, the suite went red on 7 tests that do assert while these two stayed green. They now collect failures and `assertTrue`, and the same mutation makes them red |
| 17 | `app/src/main/java/com/newoether/agora/AgoraApplication.kt` (raised 8 → 12) | F8: the watch's memory snapshot was pushed only from `MainActivity`'s **composition** scope, so memory written while the phone UI was closed (a `ReflectionWorker` run, an autopilot adaptation) never reached the watch and the watch answered from a frozen snapshot. One line starts the process-scoped observer (`MemoryPushStartup`, fork-only) in `onCreate`; self-update channel adds one more line (`UpdateChannelStartup`, fork-only); no upstream behaviour is touched |
| 18 | `app/src/main/java/com/newoether/agora/MainActivity.kt` (raised 40 → 44) | F8: the composition-scoped `MemorySnapshotPusher` start moved into `MemoryPushStartup`'s territory and its comment was updated; the raised budget covers the three-line change plus the marker |
| 19 | `app/src/test/java/com/newoether/agora/ui/chat/message/IncrementalStreamingMarkdownTest.kt` (max=24) | F11: `tracker_serializesWorkerAndInteractionUpdates` ran two threads and **asserted nothing** — `shutdownNow` only hid a hang. It now asserts the tracker's postcondition under concurrency (a `nowMs` going backwards is impossible, and every glyph's birth time is ≤ the last observed time), so a lock removed from `StreamingTailFadeTracker.update` fails it |
| 20 | `app/src/test/java/com/newoether/agora/api/DuckDuckGoScraperTest.kt` (max=30) | F11: the CAPTCHA / vqd / offset tests re-declared the production regexes inline, so they tested their own copies — a change to `DuckDuckGoScraper`'s constants left them green. The three constants are now `internal` and the tests use them, so the test measures the shipped pattern |
| 21 | `app/src/main/java/com/newoether/agora/api/DuckDuckGoScraper.kt` (max=8) | F11, same fix: `CAPTCHA_REGEX` / `VQD_REGEX` / `OFFSET_REGEX` go from `private val` to `internal val` so the tests can assert on the real pattern instead of a copy. Visibility only; no behaviour change |
| 22 | `app/build.gradle.kts` (raised 20 → 60) | M3: release signing is **fail-closed**. `releaseSigning = if (hasKeystore) release else debug` produced an APK signed with the *debug* key on any machine without a keystore — same versionName, same output path, distinguishable only by `apksigner verify`, and un-updatable by the real release. A release build with no keystore now fails at configuration time with a sentence naming `local.properties`. The same change is in `wear/build.gradle.kts` (fork-only, no entry needed) |
| 23 | `.github/workflows/build.yml` (raised 12 → 40) | M2: the Wear module was never exercised by CI (`grep -c "wear:" build.yml` → 0). `:wear:testDebugUnitTest` joins the `test` job, and `:wear:assembleRelease` + `:wear:lintVitalRelease` + the APK upload join the `build` job, where the keystore is restored — without it the new fail-closed signing rule would (correctly) refuse the release build |
| 24 | `mkdocs.yml` (max=16) | Session 3: `site_url`, `repo_url` and `repo_name` pointed at `newo-ether/Agora`. The fork runs its own Deploy MkDocs workflow and serves its own Pages site (`gh api repos/michaelxdips/Agora/pages` → `https://michaelxdips.github.io/Agora/`), so the built site's canonical links and sitemap named a domain this fork does not own — the same class of defect as the Phase 13 in-app link fix (entry 11), in the docs pipeline instead of the app. Budget 16 covers the 3 changed values plus the 6-line marker comment |
| 25 | `fastlane/metadata/android/en-US/changelogs/{32,33,34,35}.txt` (max=8 each) | Session 3: no changelog existed for `versionCode` 32–34 while those versions shipped, and none for 35. Added, each written from the matching GitHub release body. These are **fork-added files inside an upstream-owned directory** — upstream has no `32.txt`, so there is no upstream line to mark and no `HERMES INTEGRATION POINT` comment (a marker in a store listing's changelog text would be user-visible). Registration is still required; `touchpoint_guard.sh` now distinguishes "edited an upstream file" from "added a file upstream never had", which previously forced a marker into user-facing text |
| 26 | `app/proguard-rules.pro` (max=180) | Session 4: the phone release had shipped **unminified** since the fork began (`isMinifyEnabled = false`), so none of these rules was ever exercised. Turning R8 on required the keeps the shrinker actually needs, each derived by reading the C++ call sites: `llama_chat_template.cpp` resolves `LlamaChatTemplateResult` / `ChatTemplateGrammarTrigger` / `ChatTemplateMessage` / `ChatTemplateTool` / `ChatTemplateToolCall` / `LlamaChatTemplateRequest` by literal name through `FindClass`/`GetFieldID`, `llama_chat_callbacks.cpp` fetches the `NativeChatCallback` methods by literal name, `external` functions are bound by their mangled `Java_com_newoether_agora_api_*` symbols, and the manifest's components plus every `ListenableWorker` subclass are instantiated by name. Without these the release APK would fail at runtime with `UnsatisfiedLinkError` / `ClassNotFoundException` / jobs that silently never run — none of which the unit suite can see, which is why the R8 gate includes a device pass |
| 27 | `app/build.gradle.kts` (raised 64 → 68) | Session 4: `isMinifyEnabled = true` + `isShrinkResources = true` on the release build type, with the gate evidence recorded at the site. The four changed lines are the two flags and the two comment lines that say what the gate was |
| 28 | `.github/workflows/build.yml` (raised 100 → 140) | Session 4: the release job now **verifies** what it publishes — `apksigner verify --print-certs` on both APKs, a hard fail when the phone and watch certificates differ (the Data Layer refuses to pair them otherwise), and `SHA256SUMS` generated in CI and uploaded beside the APKs. Before this, both checks lived in a release-notes checklist a human ran after upload, and the audit confirmed no CI step produced the digest file. The two APK uploads also gain `if-no-files-found: error` and `retention-days: 14` |
| 29 | `app/src/test/java/com/newoether/agora/api/util/ProviderWireFormatRejectionTest.kt` (max=190) | Session 4: a **fork-added** file inside an upstream-owned test directory, next to the upstream validator it exercises. The audit found `ProviderContinuationRequestValidatorTest` (upstream, untouched) calls `requireValidWireFormat` five times and asserts nothing, so deleting the throw left it green — it guards the happy path by accident. This file adds the negative half, one refusal per provider family; with the throw removed, 7 of its 8 tests go red. Registration is required because the path is upstream-owned, but no `HERMES INTEGRATION POINT` marker is needed: upstream never had this file, and the guard distinguishes the two cases. 183 lines measured (`git diff --numstat` vs the merge base) |

Each edit site is marked `// HERMES INTEGRATION POINT`; the guard enforces both the budget and the
marker. Upstream behaviour is added to, never removed.

### Not edited, on purpose

* Everything else under `app/src/main/java/com/newoether/agora/` — Phase 2 mapped the contracts
  (memory store, `SkillManager`, embedding pipeline, provider call path, navigation) and the
  autopilot code integrates from `.../autopilot/` instead of patching upstream classes. In
  particular the idle trigger reads the already-public `ChatViewModel.generatingConversationIds`,
  so no generation-lifecycle file needed touching.
* `LICENSE`, `thirdparty/**`, `gradle/**`, `build-logic/**`.

### Planned touchpoints (Phase 2 output, filled with real paths)

Phase 2 (gap analysis) pre-registered the two files above as the only expected integration
sites. Both are now real entries in the data block. The embedding pipeline and `SkillManager` are
consumed read/write-only through their existing public API from the autopilot package, so they need
no entry.
