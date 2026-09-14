# HANDOVER — Hermes fork of Agora

Written at the end of the Phases 0–6 mandate. Everything below is verified against the working
tree at the commit named in `STATUS.md`; nothing here is aspirational.

## 1. What exists now

| Layer | Where | Notes |
|---|---|---|
| Autopilot feature code | `app/src/main/java/com/newoether/agora/autopilot/` | Hermes-only path; zero upstream classes patched for behaviour |
| Autopilot unit tests | `app/src/test/java/com/newoether/agora/autopilot/` | 48 tests, JVM, no device required |
| Autopilot instrumented tests | `app/src/androidTest/java/com/newoether/agora/autopilot/` | Needs an arm64 device (**HS1**) |
| Autopilot database | `hermes_autopilot.db` (version 1, own Room DB) | N2: Agora's Room DB is never extended |
| Governance kit | `AGENTS.md`, `ROADMAP.md`, `STATUS.md`, `NOTICE.md`, `UPSTREAM_SYNC.md`, `UPSTREAM_TOUCHPOINTS.md`, `scripts/*.sh`, `.github/workflows/upstream-sync.yml` | Binding; read `AGENTS.md` first |

## 2. The next phase: Wear OS (`wear/` module) — OUT OF SCOPE for this mandate

When the Wear OS phase starts, these are the hard prerequisites, in order:

1. **Add the `wear/` module to the touchpoint guard `ALLOWED` regex.**
   `scripts/touchpoint_guard.sh`, the `ALLOWED_RE` variable (line ~16). Until that prefix is added,
   every file under `wear/` fails the guard as an unregistered upstream modification. The guard is
   data-driven on purpose — this is the one deliberate edit it expects.
2. **Re-read the contracts before writing Wear code.** `development/README.md` (§4 lazy loading and
   §5 abstraction gates) plus `development/application-ui.md` and `development/settings-ui-ux.md`.
   N10 applies: if a sync changed `development/*.md` or `ARCHITECTURE.md`, re-read before work.
3. **Do not fork the autopilot data layer.** The Wear surface must consume the same
   `AdaptationLogDao` / `MemoryApplier` API. A second store, or a second adaptation journal, is a
   contract violation (N4, and `development/README.md` §5).
4. **Keep the 800-line source-size gate in mind.** `ChatViewModel.kt` already sits at 798 lines and
   the `verifyKotlinFileSize` task fails the build at 801. This is why the autopilot trigger is
   started from `MainActivity` and not from the ViewModel — do not "tidy" it back.

## 3. Open human-setup items

| # | Item | Why it blocks | What to do |
|---|---|---|---|
| HS1 | Physical arm64 device (or arm64 host) + `adb` | androidTest cannot run; `x86` emulators cannot run this app (arm64-v8a only) | Connect the phone, accept the RSA dialog, then `./gradlew :app:connectedFdroidDebugAndroidTest` |
| HS2 | In-app first setup: API key, provider/model, notification permission | The on-device demo and the live reflection model call | 3-minute manual setup; then the debug trigger ("Run reflection now") appears in Settings → Adaptation History |
| HS3 | Enable GitHub Actions on the fork | Forks ship with scheduled workflows disabled | One click in the Actions tab |

## 4. How to verify the whole thing from scratch

```bash
export JAVA_HOME='C:/Users/Michael/Documents/Chatapp/_tools/jdk21/jdk-21.0.12.1+1'
export ANDROID_HOME='C:/Users/Michael/Documents/Chatapp/_tools/sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

bash scripts/touchpoint_guard.sh                          # expect PASS
SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh              # expect exit 0
./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:assembleFdroidDebug :app:assemblePlayDebug
./gradlew :app:connectedFdroidDebugAndroidTest            # needs HS1
```

## 5. Known deliberate limitations (not bugs)

* **`active_memory.md` is not an adaptation target.** `MemoryManager` exposes no create/delete
  primitive for it, so adapting it would need a second write path. v1 targets saved memory files and
  skills, both of which have full create/read/edit/delete.
* **Reflection is off until a provider is configured (HS2).** `ReflectionCaller` skips silently and
  logs; it never blocks the user and never surfaces an error.
* **Retention prunes the journal, never the live file.** "50 versions per file or 30 days" is applied
  to `adaptation_log` rows only; the memory/skill content is never touched by pruning.
