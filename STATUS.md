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
| Device / emulator (`adb`) | **absent** | `adb` not on PATH, no `platform-tools` device |

**Tooling paths used locally (never committed):** `_tools/` (JDK, SDK, keystore) on the machine that
runs the builds; `local.properties` supplies `sdk.dir` + the signing aliases.

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
| `applicationId` → `com.hermes.app` | _pending_ | touchpoint #1 in `UPSTREAM_TOUCHPOINTS.md` |
| App name "Hermes" + adaptive icon via `app/src/fdroid/res` overlay | _pending_ | zero upstream file edits |
| Release keystore wiring (`local.properties`) | done | `_tools/hermes-release.jks` generated; `assembleFdroidRelease` signing check pending |
| GitHub labels `upstream-sync`, `contract-change` | _pending_ | created via API with the local token |
| Sync dry-run clean | _pending_ | `SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh` |
| Both APKs coexist on device | **[HS]** blocked: no device | — |

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
