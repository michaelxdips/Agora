# UPSTREAM_TOUCHPOINTS.md

Registry of every file that exists **upstream** and that this fork edits. `scripts/touchpoint_guard.sh`
parses the block below as data: one entry per line, `path :: max=N`, where `N` is the maximum
number of changed lines (added + removed) the fork is allowed to carry in that file. Exceeding the
budget, or editing an unregistered upstream file, fails the guard.

Rules for entries: keep the diff minimal, mark the edit site with `HERMES INTEGRATION POINT`, and
never remove upstream behaviour — add, don't rewrite.

<!-- GUARD:DATA:START -->
# path :: max=<changed lines allowed>
app/build.gradle.kts :: max=12
app/src/main/AndroidManifest.xml :: max=6
app/src/main/res/values/strings.xml :: max=4
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt :: max=18
app/src/main/java/com/newoether/agora/MainActivity.kt :: max=34
<!-- GUARD:DATA:END -->

## Registry

| # | Path | Why | Phase |
|---|------|-----|-------|
| 1 | `app/build.gradle.kts` | `applicationId` → `com.hermes.app`; `testInstrumentationRunner`; androidTest deps for the instrumented verification | 1, 3 |
| 2 | `app/src/main/AndroidManifest.xml` | only if the fdroid overlay cannot carry a manifest change | 1 |
| 3 | `app/src/main/res/values/strings.xml` | only if the fdroid resource overlay cannot carry `app_name` | 1 |
| 4 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` | Adaptation History entry + `"adaptation"` dispatch + `initialCategory` | 4 |
| 5 | `app/src/main/java/com/newoether/agora/MainActivity.kt` | notification tap → Settings/Adaptation History (`openAdaptationHistory` flag) | 4 |

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
