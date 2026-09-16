# UPSTREAM_TOUCHPOINTS.md

Registry of every file that exists **upstream** and that this fork edits. `scripts/touchpoint_guard.sh`
parses the block below as data: one entry per line, `path :: max=N`, where `N` is the maximum
number of changed lines (added + removed) the fork is allowed to carry in that file. Exceeding the
budget, or editing an unregistered upstream file, fails the guard.

Rules for entries: keep the diff minimal, mark the edit site with `HERMES INTEGRATION POINT`, and
never remove upstream behaviour — add, don't rewrite.

<!-- GUARD:DATA:START -->
# path :: max=<changed lines allowed>
app/build.gradle.kts :: max=20
settings.gradle.kts :: max=4
gradle/libs.versions.toml :: max=10
.github/workflows/build.yml :: max=12
.github/workflows/mkdocs.yml :: max=4
.gitignore :: max=9
app/src/main/AndroidManifest.xml :: max=6
app/src/main/res/values/strings.xml :: max=4
app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt :: max=32
app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt :: max=16
app/src/main/java/com/newoether/agora/util/UpdateChecker.kt :: max=130
app/src/test/java/com/newoether/agora/util/UpdateCheckerTest.kt :: max=100
app/src/main/java/com/newoether/agora/MainActivity.kt :: max=40
app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt :: max=8

| 13 | `.github/workflows/build.yml`, `.github/workflows/mkdocs.yml` | Phase 14 (audit A-015): both workflows triggered on `[master]` only. Upstream's default branch is `master`, but this fork works on `main`, so with `main` never listed **no CI job had ever run on a fork commit**. `main` is added alongside `master` (add, don't rewrite) and both files are registered here because the guard's ALLOWED class covers `upstream-sync.yml` alone. Budgets: 12 changed lines for build.yml (the two branch lists plus the marker comment, raised from 8 in the pre-publication pass to cover the `setup-android` package-list fix below), 4 for mkdocs.yml |
<!-- GUARD:DATA:END -->

## Registry

| # | Path | Why | Phase |
|---|------|-----|-------|
| 1 | `app/build.gradle.kts` | `applicationId` → `com.hermes.app`; `testInstrumentationRunner`; androidTest deps for the instrumented verification. **Budget raised 16 → 20 (Phase 15):** `versionCode 31 → 32` and `versionName 3.0.0-hermesx → 3.0.1-hermesx` — the fork's first published release. The two version lines are the only addition; without the raise the guard would have failed on a change that ships the release | 1, 3, 15 |
| 2 | `app/src/main/AndroidManifest.xml` | only if the fdroid overlay cannot carry a manifest change | 1 |
| 3 | `app/src/main/res/values/strings.xml` | only if the fdroid resource overlay cannot carry `app_name` | 1 |
| 4 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` | Adaptation History entry + `"adaptation"` dispatch + `initialCategory`; Phase 6 persona entry + `"personas"` dispatch |
| 5 | `app/src/main/java/com/newoether/agora/MainActivity.kt` | notification tap → Settings/Adaptation History (`openAdaptationHistory` flag); Phase 6 persona startup (`PersonaStartup.run`) |
| 6 | `app/src/main/java/com/newoether/agora/viewmodel/GenerationRequestBuilder.kt` | Phase 6 P5 isolation: persona blocks are stripped where active memory enters a request (reflection/synthesis must never see them) |
| 7 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt` (raised 24 → 32) | Phase 12: Watch setup entry + `"watch"` dispatch. Budget raised because the page existed but no user could open it (dead code); 6 lines for the row, 4 for the dispatch, 2 for the marker comments |
| 8 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt` | Phase 12: fork identity row on About (product name, maintainer, upstream + fork URLs) — the owner asked for the maintainer to be visible in the app, not only in comments |
| 9 | `.gitignore` (raised 6 → 9) | Phase 12: `_*.log` — the gate/build logs an agent session writes at the repo root. The previous session committed a build tree once because a pattern was missing; this closes the same hole for scratch logs before it happens |
| 10 | `app/src/main/java/com/newoether/agora/util/UpdateChecker.kt` (max=90 → 130) | Phase 13: the check queried **upstream's** releases (`newo-ether/Agora`), so a fork build could only ever be offered the upstream APK — a wrong install, since the two have different `applicationId`s, signing identities and feature sets. Repointed at the fork, `compare` made public and rewritten so a non-numeric segment is ordered instead of collapsing to 0, and a `CancellationException` branch added. **Raised 90 → 130 (Phase 15):** publishing the first release exposed a second defect in the same file — the fork's own `versionName` carries a suffix (`3.0.1-hermesx`) while a release tag does not (`v3.0.1`), so `compare(tag, current) > 0` was **true for the version already installed** and every device on a release would be offered that same release forever. `isNewer` compares base versions and treats a suffix-only difference as not-an-update. The 40 extra lines cover that method and its KDoc |
| 11 | `app/src/main/java/com/newoether/agora/ui/settings/SettingsAboutPage.kt` (raised 8 → 16) | Phase 13: the GitHub, issue-tracker, contribute and privacy-policy rows all opened **upstream's** URLs. A bug in Hermes X reported to a tracker for a build upstream does not ship is a bug that cannot be reproduced. Repointed at the fork (11 lines: 4 URLs + the marker) |
| 12 | `app/src/test/java/com/newoether/agora/util/UpdateCheckerTest.kt` (max=100) | Phase 13: a test for the upstream file above. It lives next to its subject, not under `autopilot/`, because the fork-only test directories do not cover `util/`. It is the test whose absence let the upstream-releases defect ship |

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
