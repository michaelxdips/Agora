# UPSTREAM_TOUCHPOINTS.md

Registry of every file that exists **upstream** and that this fork edits. `scripts/touchpoint_guard.sh`
parses the block below as data: one entry per line, `path :: max=N`, where `N` is the maximum
number of changed lines (added + removed) the fork is allowed to carry in that file. Exceeding the
budget, or editing an unregistered upstream file, fails the guard.

Rules for entries: keep the diff minimal, mark the edit site with `HERMES INTEGRATION POINT`, and
never remove upstream behaviour — add, don't rewrite.

<!-- GUARD:DATA:START -->
# path :: max=<changed lines allowed>
app/build.gradle.kts :: max=16
settings.gradle.kts :: max=4
gradle/libs.versions.toml :: max=10
.gitignore :: max=9
app/src/main/AndroidManifest.xml :: max=6
app/src/main/res/values/strings.xml :: max=4
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt :: max=32
app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt :: max=8
app/src/main/java/com/newoether/agora/MainActivity.kt :: max=40
app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt :: max=8
<!-- GUARD:DATA:END -->

## Registry

| # | Path | Why | Phase |
|---|------|-----|-------|
| 1 | `app/build.gradle.kts` | `applicationId` → `com.hermes.app`; `testInstrumentationRunner`; androidTest deps for the instrumented verification | 1, 3 |
| 2 | `app/src/main/AndroidManifest.xml` | only if the fdroid overlay cannot carry a manifest change | 1 |
| 3 | `app/src/main/res/values/strings.xml` | only if the fdroid resource overlay cannot carry `app_name` | 1 |
| 4 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` | Adaptation History entry + `"adaptation"` dispatch + `initialCategory`; Phase 6 persona entry + `"personas"` dispatch |
| 5 | `app/src/main/java/com/newoether/agora/MainActivity.kt` | notification tap → Settings/Adaptation History (`openAdaptationHistory` flag); Phase 6 persona startup (`PersonaStartup.run`) |
| 6 | `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt` | Phase 6 P5 isolation: persona blocks are stripped where active memory enters a request (reflection/synthesis must never see them) |
| 7 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` (raised 24 → 32) | Phase 12: Watch setup entry + `"watch"` dispatch. Budget raised because the page existed but no user could open it (dead code); 6 lines for the row, 4 for the dispatch, 2 for the marker comments |
| 8 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt` | Phase 12: fork identity row on About (product name, maintainer, upstream + fork URLs) — the owner asked for the maintainer to be visible in the app, not only in comments |
| 9 | `.gitignore` (raised 6 → 9) | Phase 12: `_*.log` — the gate/build logs an agent session writes at the repo root. The previous session committed a build tree once because a pattern was missing; this closes the same hole for scratch logs before it happens |

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

Phase 2 (`GAP_ANALYSIS.md`) pre-registered the two files above as the only expected integration
sites. Both are now real entries in the data block. The embedding pipeline and `SkillManager` are
consumed read/write-only through their existing public API from the autopilot package, so they need
no entry.
