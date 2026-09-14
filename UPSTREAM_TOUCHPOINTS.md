# UPSTREAM_TOUCHPOINTS.md

Registry of every file that exists **upstream** and that this fork edits. `scripts/touchpoint_guard.sh`
parses the block below as data: one entry per line, `path :: max=N`, where `N` is the maximum
number of changed lines (added + removed) the fork is allowed to carry in that file. Exceeding the
budget, or editing an unregistered upstream file, fails the guard.

Rules for entries: keep the diff minimal, mark the edit site with `HERMES INTEGRATION POINT`, and
never remove upstream behaviour — add, don't rewrite.

<!-- GUARD:DATA:START -->
# path :: max=<changed lines allowed>
app/build.gradle.kts :: max=8
app/src/main/AndroidManifest.xml :: max=6
app/src/main/res/values/strings.xml :: max=4
<!-- GUARD:DATA:END -->

## Registry

| # | Path | Why | Phase |
|---|------|-----|-------|
| 1 | `app/build.gradle.kts` | `applicationId` → `com.hermes.app`; Hermes version suffix | 1 |
| 2 | `app/src/main/AndroidManifest.xml` | only if the fdroid overlay cannot carry a manifest change | 1 |
| 3 | `app/src/main/res/values/strings.xml` | only if the fdroid resource overlay cannot carry `app_name` | 1 |

### Not edited, on purpose

* Everything else under `app/src/main/java/com/newoether/agora/` — Phase 2 maps the contracts
  (memory store, `SkillManager`, embedding pipeline, provider call path, navigation) and the
  autopilot code integrates from `.../autopilot/` instead of patching upstream classes.
* `LICENSE`, `thirdparty/**`, `gradle/**`, `build-logic/**`.

### Planned touchpoints (Phase 2 output, filled with real paths)

Populated by the gap analysis; entries move into the data block above only when actually edited.
