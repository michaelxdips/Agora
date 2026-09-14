# STATUS — Hermes fork of Agora

Owner: Michael (`michaelxdips`) · Fork of [`newo-ether/Agora`](https://github.com/newo-ether/Agora) · Licence: MIT

Evidence standard (N12): every phase exit carries command output / artifact paths below.
No proof = phase not done. Human-setup items are marked **[HS]** and never block progress (N6).

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
| Release keystore wiring (`local.properties`) | **done** | `_tools/hermes-release.jks`; `assembleFdroidRelease` green; `apksigner verify --print-certs` → `CN=Hermes Local, OU=Autopilot, O=Hermes, L=Loning, ST=Jawa Tengah, C=ID`, SHA-256 `7188ce700b7407485e4a588cc1ef779fba4bd47c635338b05f61d5fc90aa56d7` |
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

_pending — see `PHASE_2` section below once the mapping is written._

---

## Phase 3 … Phase 6

_pending._

---

## Human-setup required ([HS] registry)

| # | Item | Impact if missing |
|---|---|---|
| HS1 | USB/OTG Android device (or emulator) with `adb` | device install, screenshots, androidTest execution |
| HS2 | Android Studio / on-device demo session | manual UI demo (secondary; tests are primary proof) |
