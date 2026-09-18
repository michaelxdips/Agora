# MEGA_PLAN.md — audit reconciliation, fix plan, optimisation plan

Written 2026-09-18 against `main` @ `2c2c10f1` and `upstream/master` @ `360ae4f8`.
Every number below is a command I actually ran in this session; anything I did **not** reproduce is
marked **UNVERIFIED** rather than asserted. Evidence logs: `evidence/reaudit/`.

---

## 0. The rule this document is written under

The previous audit (the "old agent" findings) is a *hypothesis*, not a fact. So is this document.
Each row below carries the exact command whose output decided it. Where a claim could not be
reproduced, it says so — a false PASS is worse than an honest "not proven".

---

## 1. Reconciliation — what the old audit got right, wrong, and what it missed

### 1.1 REFUTED — the suite is not red

| Claim | Old audit | What actually happened |
|---|---|---|
| App unit tests | "2562 tests completed, **29 failed**" across 7 classes | `./gradlew :app:testFdroidDebugUnitTest … --rerun-tasks` → **BUILD SUCCESSFUL**; XML aggregate → `fdroid 2562 tests, 0 failures, 0 errors, 3 skipped`. Re-run twice: plain and `--rerun-tasks`. |

Command: `python - <<'PY' … app/build/test-results/testFdroidDebugUnitTest/*.xml …`
Output: `fdroid files=394 tests=2562 failures=0 errors=0 skipped=3`
Also `play files=390 tests=2545 failures=0 errors=0 skipped=3`, `wear files=9 tests=82 failures=0 errors=0 skipped=0`.

Supporting fact: `git log --oneline 914e7c8d..HEAD -- <the 7 test files>` is **empty** — the fork never
touched those classes. That is *consistent* with an environment/infra cause, but it does **not**
identify one. **Root cause: UNVERIFIED.** Do not write "environment" in STATUS.md; write "not
reproduced on this machine, cause not isolated".

Consequence: the P0 "do not call v3.0.3 verified-green" verdict is **withdrawn** — the 82 wear tests
and 2562 app tests are green here, and CI run `35140681063` on `2c2c10f1` is `success`.

### 1.2 CONFIRMED — real, still open

| # | Defect | Evidence I read |
|---|---|---|
| C1 | `audit_gate0.sh` cd's into a machine that does not exist | `cd /c/Users/Michael/Documents/Chatapp/Agora` → real run printed `No such file or directory`, `REAL_EXIT=1`. The script was dead on arrival for anyone else. |
| C2 | `audit_gate0.sh` could print FAIL and still exit 0 | `grep -n exit scripts/audit_gate0.sh` → only `exit 1` on the cd. No `fail` accumulator anywhere. |
| C3 | Signature section printed the expected digest, never compared it | lines 96-104: `echo "  expected: … 7188ce70…aa56d7"` with no comparison. |
| C4 | `audit_test_counts.py` treats a failing suite as success | `sys.exit(0 if total > 0 else 1)` — 10 tests / 10 failures → exit 0. |
| C5 | Release signing fails open to debug | `app/build.gradle.kts:64-69`, `wear/build.gradle.kts:45-50` — `if (hasKeystore) release else debug`. |
| C6 | CI keystore restore is conditional | `.github/workflows/build.yml:81` `if: ${{ env.KEYSTORE_BASE64 != '' }}` (same line exists upstream — this is not a fork-introduced defect). |
| C7 | CI never exercises the Wear module | build.yml runs only `:app:test*UnitTest` + `verifyKotlinFileSize`; no `:wear:testDebugUnitTest`, no `:wear:assembleRelease`, no `:wear:lintVitalRelease`. |
| C8 | `upstream_sync.sh` N10 detector compares the wrong side | line ~163: `git diff --name-only "$(git merge-base "$TARGET" HEAD)" HEAD -- development ARCHITECTURE.md`. For *upstream* contract changes it must be `… TARGET`. Verified: `git diff --stat 914e7c8d upstream/master -- development/` shows `semantic-search.md` +80/-28 and `settings-ui-ux.md` +23/-8 changed upstream, and N10 would not have forced a re-read. |
| C9 | `touchpoint_guard.sh` defaults to `origin/master` | line 13. `git branch -r` → `origin/master` exists but is the **fork's stale mirror of the fork point**, not current upstream. A real upstream edit made after the fork point is invisible to the default run. |
| C10 | `touchpoint_guard.sh` falls back to `HEAD` when the ref is missing | lines 25-31 — committed violations then vanish from the comparison. |
| C11 | Branch protection off | `gh api repos/michaelxdips/Agora/branches/main/protection` → `404 Branch not protected`. |
| C12 | The 5 upstream commits are not in `main` | `git rev-list --left-right --count upstream/master...HEAD` → `5  83`. |
| C13 | Doc drift | see §1.4 — enumerated with the command that proves each. |
| C14 | The whole wear/watch defect set | Re-verified by reading: `Card(onClick = {})` at `WearMainActivity.kt:453, 494, 555`; `catch (error: Throwable)` at `WearChatClient.kt:121`; no `callTimeout` in the OkHttp builder (`WearChatClient.kt:49-52`); `endpoint()` string-concatenates the path (`WearChatClient.kt:155-161`); `readBounded` reads the body before the HTTP status is checked; `remember(existing)` instead of `rememberSaveable` in `WearSetupScreen.kt:81-83`; `WearConfig.isValid()` prefix-only HTTPS check; `WearCrypto.encrypt/decrypt(context, …)` never use `context`; no `wear/src/androidTest` directory at all; `WearMainThreadSentinelTest` is a source-text scan. **All still true.** |

### 1.3 MISSED by the old audit — new findings from this session

**N1 (P0, blocks the sync) — upstream's own tip fails its own line-budget gate.**
`git show upstream/master:app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt | wc -l` → **801**.
`build-logic` caps handwritten Kotlin at `KOTLIN_SOURCE_MAX_LINES = 800` with an empty baseline, so a
merged tree fails `:verifyKotlinFileSize`:

```
* What went wrong:
> Kotlin source file size verification failed:
   - app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt: 801 lines (allowed 800; new_oversized_source)
```
This is not our bug — upstream's own CI is red for it: `gh run view 35171806965 -R newo-ether/Agora
--log-failed` shows the identical failure on `master` at `360ae4f8`. **Merging the full tip violates
AGENTS.md rule 1 (`main` must always build).** Verified safe alternative: `a37759f9` (the 4th of the
5) is exactly 800 lines.

**N2 (P1) — the sync conflict policy silently discards upstream improvements.**
Both sides edited the same block of `.github/workflows/build.yml`. Our `resolve_conflicts()` does
`git checkout --ours` for any registered touchpoint, so the merge keeps our `setup-android@v3` +
`packages:` block and **drops upstream's `@v4` bump and its `platform-tools ndk;…` change**. Proven
by the real dry run:

```
[upstream_sync] touchpoint conflict kept ours: .github/workflows/build.yml (re-apply the Hermes edit if upstream moved it)
```
The message is honest, but nothing enforces the "re-apply" step — the run reports success.
This will recur on **every** sync, because the two sides permanently disagree about that file.

**N3 (P1) — the sync job has been failing on a schedule for two days and nobody is told.**
`gh run list -R michaelxdips/Agora -w upstream-sync` → `2026-09-18 08:19 failure`, `2026-09-17 08:43
failure`. Log: `Automatic merge failed`, then
`touchpoint_guard: FAIL .github/workflows/build.yml changed 15 lines (budget 12).` → `GUARD FAILED
after merge`. Root cause is N2 + a budget that the merge resolution blows through. A scheduled job
that fails every night with no notification is worse than no job.

**N4 (P1) — dead toolchain paths in two more scripts.**
`grep -n "Chatapp" scripts/*.sh` → `audit_gate0.sh:12,13,18`, `persona_update.sh:81,82`,
`upstream_sync.sh:131,132`. `ls /c/Users/Michael/Documents/Chatapp` → **No such file or directory**.
Consequence for `upstream_sync.sh`: the post-merge gate would have run with a `JAVA_HOME` containing
no `java`, so every real sync would abort with "TESTS/BUILD FAILED" for a toolchain reason and blame
the merge.

**N5 (P2) — a project-unrelated fingerprint was committed into a shipped resource.**
`app/src/fdroid/res/values/colors.xml:2` read `fdroid-flavor icon background (forest green)`.
The locality field has nothing to do with Agora, Hermes X, or the icon colour `#123E25`. Removed.

**N6 (P2) — the Wear release is R8-minified and the phone release is not.**
`wear/build.gradle.kts:51-52` `isMinifyEnabled = true`, `isShrinkResources = true`;
`app/build.gradle.kts:70` `isMinifyEnabled = false`. The phone ships **49.6 MB** unshrunk. I did
verify the wear keep-rules work — reading `classes.dex` out of the published `wear-release.apk` shows
`ConfigListenerService`, `MemoryListenerService`, `PairingAckListenerService`, `WearMainActivity` all
**PRESENT** (renamed-away internals such as `WearChatClient`/`WearCrypto` are absent, as expected).

**N7 (P2) — `app`'s global `usesCleartextTraffic="true"` is upstream's line, not the fork's.**
`git show 914e7c8d:app/src/main/AndroidManifest.xml | grep usesCleartextTraffic` → present **before**
the fork's first commit; `git diff 914e7c8d HEAD -- app/src/main/AndroidManifest.xml` shows the fork
added only the `PairingListenerService` block. The fork's own `api/HttpClient.kt` already has a
fail-closed guard (`guardCleartextCredentials` throws for a non-local http host carrying a credential
header). So this is a **real risk but an upstream design decision with product impact** (Ollama at
`http://localhost:11434/v1` is a shipped default in `ProviderDefaults.kt:32`). It must be classified,
not "fixed" blindly — see F5.

**N8 (P3) — README verification table carries two wrong counts and one stale sync number.**
| README says | Command output | Verdict |
|---|---|---|
| wear `82 tests … (8 XML reports)` | `ls wear/build/test-results/testDebugUnitTest/*.xml \| wc -l` → **9** | wrong |
| `main` is **65 ahead**, upstream **4 ahead** | `git rev-list --left-right --count upstream/master...HEAD` → **5 83** | stale |
| `14 registered upstream touchpoints, 13 currently carrying a diff` | `awk … UPSTREAM_TOUCHPOINTS.md \| grep "::" \| grep -vc "^#"` → **14**; guard printed **13** `ok` lines | correct |
| APK sizes / SHA-256 in the release table | downloaded `v3.0.3` assets: `sha256sum` matched `SHA256SUMS` byte-for-byte; cert `7188ce70…aa56d7` on **both** APKs | correct |

**N9 (P3) — STATUS.md carries contradictory historical claims that are not marked archival.**
`STATUS.md:744` "The fork still has **0 releases**" while `gh release list` shows three
(`v3.0.3` latest, `v3.0.2`, `v3.0.1` pre-release). `STATUS.md:515` says `versionName 3.0.0-hermesx`,
`versionCode` left at 31, while `app/build.gradle.kts` says `3.0.3-hermesx` / 34. Both are true of a
past phase and false of the repo — the file needs one current-state block and a clearly dated
archive section, or it will keep lying by accident.

**N10 (P3) — `SettingsModelsPage.kt` has 10 unused imports** (verified by parsing the file and
searching the body for each imported symbol): `AnimatedVisibility`, `expandVertically`, `fadeIn`,
`fadeOut`, `shrinkVertically`, `onGloballyPositioned`, `Cloud`, `Edit`, `KeyboardArrowDown`,
`KeyboardArrowUp`. That is upstream's own debt; it is also the cheapest way to make N1 go away
**without** editing upstream's file… except removing imports *is* editing it, so it still needs a
registered touchpoint (see F2).

### 1.4 What is NOT a defect, and should stop being repeated

* "Exactly-once" in `WearOfflineQueue`'s KDoc: the class-level text is honest about the crash case,
  but the claim as written ("exactly-once on reconnect, at-least-once on crash") is still wrong for
  *external delivery* — the provider can be billed twice. The old audit was right; the correction
  belongs in the KDoc and README, not in new machinery.
* `putDataItem().await()` → "watch installed": correct finding, unchanged.
* The Wear release certificate matching the phone's: **verified true** on the published assets.

---

## 2. What the 5 upstream commits actually are, and what we can pull

`git log upstream/master --oneline -5` and `git diff --stat 914e7c8d upstream/master` (46 files,
+1863/−495):

| Commit | Subject | Pullable? |
|---|---|---|
| `66c323e9` | Stream provider image request bodies | yes — new `StreamingJsonRequestBody.kt` (+199) + `ProviderStreamingImageRequestTest.kt` (+283); no overlap with our touchpoints |
| `9f2908aa` | Fix Android SDK setup in CI | **conflicts** with our `build.yml` edit — same block (N2) |
| `63145a04` | Optimize RAG reconciliation and cache status | yes — `RagManager.kt` (−217/+…), `EmbeddingCacheWorker.kt`, `SemanticIndexLedger.kt`; no overlap |
| `a37759f9` | Test RAG count refresh and scheduling concurrency | yes — `RagManagerTest.kt` (+423) |
| `360ae4f8` | Add official OpenCode Go provider support (#116) | **carries N1**: it is the commit that pushes `SettingsModelsPage.kt` to 801 lines and turns upstream's own CI red |

Mergeability, measured with `git merge-tree --write-tree HEAD upstream/master`:
**exactly one conflict** — `.github/workflows/build.yml`. Everything else auto-merges, including
`README.md` (upstream's provider-count edit and our fork README coexist; `git diff HEAD -- README.md`
after the trial merge shows only the expected `Nine`→`Ten` provider line).

**Answer to "what we can really pull":** all five. `360ae4f8` was held for exactly one round because
upstream's own commit breaks the 800-line cap; that is **solvable without waiting for upstream**:
`SettingsModelsPage.kt` carries **10 imports that nothing in its body references** (verified by parsing
the file and searching each imported symbol), and the policy rejects any baseline entry at or under
the cap (`recordedLines <= maximumLines` → `INVALID_BASELINE`, and `KOTLIN_SOURCE_BASELINE_CAPS` is
empty). Removing the dead imports takes the file from **801 → 791** lines with no behaviour change.

Proved in a throwaway clone first (`git clone` + `git merge upstream/master` + the edit):

```
$ ./gradlew :app:compileFdroidDebugKotlin verifyKotlinFileSize
Kotlin source size verified: 1027 files, maximum 800 lines at …ConversationSelectionControllerTest.kt
BUILD SUCCESSFUL in 1m 32s
```

then applied to the real branch: `SettingsModelsPage.kt` 796 lines (791 + the 5-line marker comment),
guard `ok … (15/20 lines)`, `touchpoint_guard: PASS (360ae4f8…)`. New touchpoint registered:
`app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt :: max=20`.

---

## 3. Plan A — FIX (correctness, in dependency order)

Each item states its exit gate. Nothing is "done" without the command.

**F0 — Land the upstream sync at `a37759f9`.**
Branch `hermes/upstream-sync-a37759f9` already carries the merge commit with `build.yml` resolved to
`setup-android@v4` + our `packages:` comment (both sides' intent kept, not "ours wins").
Gate: `./gradlew :app:testFdroidDebugUnitTest :wear:testDebugUnitTest verifyKotlinFileSize` green in
the merged tree **and** `bash scripts/touchpoint_guard.sh upstream/master` → PASS.

**F1 — Make the gate scripts mean what they print.**
`audit_gate0.sh`: accumulate every section into `$fail`, compare the signature instead of echoing the
expected value, discover `JAVA_HOME`/`ANDROID_HOME` instead of hardcoding a dead machine, `exit $fail`.
`audit_test_counts.py`: `sys.exit(0 if total > 0 and failures == 0 and errors == 0 else 1)`.
Gate: `bash scripts/audit_gate0.sh; echo $?` → nonzero when a section fails, and the run must reach
the end (today it dies on line 18). **Done for `audit_gate0.sh`; `audit_test_counts.py` still open.**

**F2 — Decide `SettingsModelsPage.kt` (N1), in this order of preference.**
(a) Wait for upstream — costs nothing, but leaves the OpenCode Go provider unpullable.
(b) Register a `max=11` touchpoint and delete the 10 unused imports (N10) → 801 → 791 lines, budget
gate green, and the file gets *better* rather than being blank-trimmed. Requires a
`HERMES INTEGRATION POINT` marker and a row in `UPSTREAM_TOUCHPOINTS.md`.
Gate: `:verifyKotlinFileSize` green **and** guard PASS with the new entry.

**F3 — Stop the nightly sync from failing silently (N2, N3).**
Replace "keep ours on a registered touchpoint" with a **three-way** resolution for `build.yml`:
re-apply our marker comment onto upstream's block instead of taking ours wholesale. Add a
`if: failure()` notification step to `.github/workflows/upstream-sync.yml` (issue comment or a
`gh issue create --label sync-failure`), so a red nightly run is visible.
Gate: `SYNC_DRY_RUN=1 bash scripts/upstream_sync.sh` → exit 0, and the log shows upstream's `@v4`
retained; a forced failure creates an issue.

**F4 — Finish the dead-path cleanup (N4).**
`persona_update.sh` and `upstream_sync.sh` now resolve the toolchain the same way `audit_gate0.sh`
does. `STATUS.md` still quotes `_tools/…` paths inside clearly-dated historical sections — leave
those, they are evidence, not instructions.
Gate: `bash -n` on all three scripts; `bash scripts/upstream_sync.sh` reaches its gradle step.

**F5 — Classify the phone's cleartext policy (N7) instead of "fixing" it.**
It is upstream's line and it is what makes the shipped `http://localhost:11434/v1` Ollama default
work. The honest change is to narrow it: keep `usesCleartextTraffic="true"` (upstream behaviour) and
rely on `HttpClient.guardCleartextCredentials`, then *document* in README that a credential to a
non-local http host is refused at runtime. If a narrower manifest is wanted, it needs a registered
touchpoint (`app/src/main/AndroidManifest.xml`, budget 6, currently 5/6 used) and a
`network_security_config` that still permits loopback + LAN — a product decision, not a cleanup.
Gate: a unit test that `guardCleartextCredentials` throws for `http://evil.example/v1` with an
`Authorization` header and does not throw for `http://127.0.0.1:11434/v1`.

**F6 — Watch request correctness (the old audit's Phase 2, still the right scope).**
One `WearRequestCoordinator` owning fresh-send, drain, Send-now and retry; `callTimeout` on the
OkHttp client; `Exception` instead of `Throwable`; a real URI builder for `endpoint()`; a parser that
accepts content-part arrays; typed failure classes (401/404/parse ≠ "Offline"); queue limits and a
dead-letter state instead of silent deletion at attempt 3.
Gate: a duplicate-tap test that is red before the coordinator and green after; `401` does not retry;
`429` honours `Retry-After`.

**F7 — Phone↔watch protocol (the old audit's Phase 3).**
`requestId` + `schemaVersion` + `configRevision` in the payload and the ack; a typed ack
(`INSTALLED`/`REJECTED`/`MISMATCH`) so a protocol mismatch stops rendering as "configuration
received"; targeted credential delivery instead of a broadcast `DataItem`; `await()` the
`deleteDataItems` task.
Gate: `WearPairingTest` covers a stale ack not satisfying a new request, and a mismatch ack producing
a non-Connected state.

**F8 — Memory sync ownership (the old audit's Phase 4).**
`MemorySnapshotPusher` currently lives on `rememberCoroutineScope()` (`MainActivity.kt:343-352`), so
memory written while the phone UI is closed never reaches the watch, and
`MemoryManager.activeMemoryRevision` is instance-local (`MemoryManager.kt:22-23`). Move ownership to
the application/WorkManager, persist revision+hash, push an initial snapshot, and let an **empty**
snapshot mean CLEAR rather than "ignore".
Gate: a background-write test (different `MemoryManager` instance) produces a push; clearing memory on
the phone clears the watch cache.

**F9 — Doc truth (N8, N9).**
Fix the two wrong counts and the stale sync number in README; add a single "current state" block at
the top of STATUS.md and mark everything below it as dated archive; delete the "0 releases" line.
Gate: every number in the README verification table re-derived by a command in the same commit; a
`scripts/audit_doc_counts.py` that greps the table and fails on drift would be the durable fix.

---

## 4. Plan B — MODIFY (make the claims structural, not repeated)

**M1 — One release-provenance gate.** Today nothing forces "phone and watch built from the same
commit, same certificate, tests green". Add a `release-provenance` job: download both APKs from the
tag, `apksigner verify --print-certs`, assert equal digests, assert the tag's commit is the CI head
SHA, assert both test suites ran on it. That is the single highest-value new gate because it is what
turns "v3.0.3 is verified" from a README sentence into a machine check.

**M2 — Wear in CI (C7).** `:wear:testDebugUnitTest`, `:wear:assembleRelease`,
`:wear:lintVitalRelease`, and a wear APK artifact. Cheap: the module's whole dependency list is
Compose + Data Layer + OkHttp.

**M3 — Signing fail-closed (C5, C6).** A `release` build with no keystore must **fail**, not silently
sign with debug. Implement as a `doFirst` check on the release assemble task, and make the CI keystore
step unconditional for tagged builds.

**M4 — Guard hardening (C9, C10).** Default `upstream/master`, and make a missing ref a **failure**
rather than a fallback to `HEAD` (with an explicit `--allow-missing-ref` escape for a fresh clone).

**M5 — N10 detector (C8).** Compare `merge-base..TARGET` so an upstream contract change actually
forces the re-read AGENTS.md rule 8 demands. This session proved the bug is live: upstream changed
`development/semantic-search.md` and `settings-ui-ux.md` in exactly the commits we are merging.

**M6 — Wear instrumentation suite.** `wear/src/androidTest` does not exist. Minimum viable set:
Keystore encrypt/decrypt round-trip, both listener services resolve from the manifest, setup screen
survives recreation with `rememberSaveable`, and a Compose test that the answer card renders inside
the round mask. JVM-green is not device-green — the old audit said this and it is still true.

**M7 — `CODE_MAP.md` generated, not hand-maintained.** It already drifted (it references an
`_tools/` section that does not exist in this checkout). Generate it from the tree.

---

## 5. Plan C — OPTIMISE (after correctness, not before)

**O1 — Shrink the phone release (N6).** `isMinifyEnabled = false` on a 49.6 MB APK. Turning R8 on is
a real risk (upstream may rely on reflection the rules do not cover) — so do it behind a measured
comparison: build with R8, run the full 2562-test suite plus a device smoke pass, and only keep it if
both are green. Expected saving is large; the cost of getting it wrong is a crash on a user's device,
which is why it is an *optimisation*, not a fix.

**O2 — Queue/memory storage.** `WearOfflineQueue.writeAll` rewrites the whole JSON array per
operation and its rename fallback (`file.writeText(temp.readText())`) is non-atomic; `WearConfigStore.write`
and `WearMemoryCache.write` write straight to the final path with no fsync. Replace all three with a
temp-write + fsync + atomic rename helper, and never copy a temp file over the live file.

**O3 — Payload size.** The phone base64-encodes the **full** active-memory snapshot (Data Layer limit
≈100 KB, base64 +33%) and the watch then truncates it to 500 estimated tokens
(`WearCoreContext.MAX_TOKENS`). Send the derived core context instead of the full snapshot: smaller,
faster, and no silent truncation. Gate: measured byte size before/after for a 1,625-byte snapshot.

**O4 — Call budget on the watch.** `readTimeout = 60s` with no total deadline lets a drip-feeding
server hold a watch connection indefinitely; add `callTimeout`, cap the body far below 1 MiB, and read
the status **before** the body so a 413 does not cost a megabyte of allocation.

**O5 — Strings and locale.** Move the remaining hardcoded watch UI strings into `strings.xml`
(`Send`, `Speak`, `Change key`, `Debug`, `Thinking…`, `Offline — held`, `No setup yet`) and revisit
`localeFilters += listOf("en")` — the phone ships 12 locales; a watch-only `en` is a choice, but it
should be a stated one.

**O6 — Split `WearMainActivity` (706 lines).** Extract TTS/speech, the setup gate, and the queue
coordinator. Do this **after** F6/F7, so the split is a mechanical move of already-tested logic rather
than a simultaneous rewrite of behaviour and structure.

---

## 6. Order of work, with the gate that closes each step

| Step | Work | Gate |
|---|---|---|
| 1 | F0 land `a37759f9` | merged tree: app+wear tests green, `verifyKotlinFileSize` green, guard PASS |
| 2 | F1 strict gate exit codes | `audit_gate0.sh` reaches the end and exits nonzero on a seeded failure |
| 3 | F2 decide the 801-line file | `verifyKotlinFileSize` green with the OpenCode Go commit merged |
| 4 | F3 sync conflict + failure notification | dry-run exit 0 with upstream's `@v4` retained; a forced failure opens an issue |
| 5 | F4 dead paths | `bash -n` on all three scripts; sync reaches gradle |
| 6 | F6 + F7 watch correctness and protocol | duplicate-tap red→green; 401 no-retry; typed ack |
| 7 | F8 memory sync ownership | background-write push test; empty snapshot clears the watch cache |
| 8 | M1–M5 gates | provenance job green on a tag; wear in CI; release build fails without a keystore |
| 9 | F9 doc truth | every README number re-derived in the same commit |
| 10 | O1–O6 optimisation | R8 phone build passes 2562 tests + device smoke; payload bytes measured |

**Stop rule (AGENTS.md):** if any step cannot be closed in 30 minutes, write `BLOCKED.md` with the
options and take the most conservative safe one. A stopped agent with a green `main` is a success.
