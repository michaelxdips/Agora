# MEGA_PLAN — audit reconciliation, fix plan, optimisation plan

Written 2026-09-18 against `main` @ `2c2c10f1` → `396a8971` and `upstream/master` @ `360ae4f8`.
Every number below is a command I actually ran; anything I did **not** reproduce is marked
**UNVERIFIED** rather than asserted. Evidence logs: `evidence/reaudit/`.

---

## 0. The rule this document is written under

The previous audit (the "old agent" findings) is a *hypothesis*, not a fact. So is this document.
Each row carries the exact command whose output decided it. Where a claim could not be reproduced, it
says so — a false PASS is worse than an honest "not proven". Two subagent audits were also run
(14 raw findings); only the ones whose claim I re-read **in the source myself** are listed, and the
ones that turned out to be artefacts of a truncated transcript are dropped rather than repeated.

---

## 1. Reconciliation — right, wrong, missed

### 1.1 REFUTED — the suite is not red

| Claim | Old audit | What actually happened |
|---|---|---|
| App unit tests | "2562 tests completed, **29 failed**" across 7 classes | `./gradlew :app:testFdroidDebugUnitTest … --rerun-tasks` → **BUILD SUCCESSFUL**; XML aggregate → `fdroid 2562 tests, 0 failures, 0 errors, 3 skipped`. Run **twice** (plain and `--rerun-tasks`). |

Command: `python -` over `app/build/test-results/testFdroidDebugUnitTest/*.xml`.
Supporting fact: `git log --oneline 914e7c8d..HEAD -- <the 7 test files>` is **empty** — the fork never
touched those classes. That is *consistent* with an infra cause but does **not** identify one.
**Root cause: UNVERIFIED.** Do not write "environment" in STATUS.md; write "not reproduced, cause not
isolated".

**A real infra failure did show up in this session** — but my first explanation for it was **wrong**, and
the correction matters more than the original claim:

```
* What went wrong:
Execution failed for task ':app:testFdroidDebugUnitTest'.
> java.nio.file.NoSuchFileException: …\app\build\test-results\testFdroidDebugUnitTest\binary\in-progress-results-generic.bin
```

I first attributed this to two Gradle invocations sharing `app/build/test-results/`. **Tested, and it
did not hold:** the two runs that overlapped (one plain, one `--rerun-tasks`, same log file) both
completed `BUILD SUCCESSFUL`, `EXIT=0`, with `grep -c NoSuchFileException` → **0**. So concurrency is
*not* sufficient to reproduce it. The one time it happened, the preceding command was a `git stash -u`
+ `git checkout -b` + `git merge` **while a background Gradle run was in flight**, i.e. the build tree
was being rewritten underneath a running build — a different mechanism, and still only **one**
observation. **Cause: UNVERIFIED.** What *is* established is the consequence: that failure aborts the
test task while the previous run's XML reports stay on disk, so a reader counting XML sees stale
numbers — which is exactly the shape of "2562 tests, 29 failed". Recorded as a hypothesis about the old
agent's run, not as the answer.

Consequence: the P0 "29 failures / do not call v3.0.3 verified-green" verdict is **withdrawn**. CI run
`35140681063` on `2c2c10f1` is `success`.

### 1.2 CONFIRMED — real, still open

| # | Defect | Evidence I read |
|---|---|---|
| C1 | `audit_gate0.sh` cd's into a machine that does not exist | real run → `cd: /c/Users/Michael/Documents/Chatapp/Agora: No such file or directory`, `REAL_EXIT=1`. Dead on arrival for anyone else. |
| C2 | `audit_gate0.sh` could print FAIL and still exit 0 | `grep -n exit scripts/audit_gate0.sh` → only the cd's `exit 1`; no `fail` accumulator anywhere |
| C3 | Signature section printed the expected digest, never compared it | lines 96-104: `echo "  expected: … 7188ce70…aa56d7"` with no comparison |
| C4 | `audit_test_counts.py` treats a failing suite as success | `sys.exit(0 if total > 0 else 1)`; 10 tests / 10 failures / 10 errors → exit 0 |
| C5 | Release signing fails open to debug | `app/build.gradle.kts:64-69`, `wear/build.gradle.kts:45-50` — `if (hasKeystore) release else debug` |
| C6 | CI keystore restore is conditional | `.github/workflows/build.yml:81` `if: ${{ env.KEYSTORE_BASE64 != '' }}` (the same line exists upstream — not fork-introduced) |
| C7 | CI never exercises the Wear module | `grep -c "wear:" .github/workflows/build.yml` → 0; no `:wear:testDebugUnitTest`, no `:wear:assembleRelease`, no `:wear:lintVitalRelease` |
| C8 | `upstream_sync.sh` N10 detector compares the wrong side | **proved live**: the current form prints nothing while `git diff … upstream/master -- development` prints `semantic-search.md` + `settings-ui-ux.md` |
| C9 | `touchpoint_guard.sh` defaults to `origin/master` | `git rev-parse origin/master` → `914e7c8d` = the **fork point**, so upstream's later edits were invisible to the default run |
| C10 | `touchpoint_guard.sh` falls back to `HEAD` when the ref is missing | committed violations then vanish from the comparison |
| C11 | Branch protection off | `gh api repos/michaelxdips/Agora/branches/main/protection` → `404 Branch not protected` |
| C12 | The 5 upstream commits were not in `main` | `git rev-list --left-right --count upstream/master...HEAD` → `5  83` (now `0  87`) |
| C14 | The wear/watch defect set | re-verified by reading: `Card(onClick = {})` at `WearMainActivity.kt:453, 494, 555`; `catch (error: Throwable)` `WearChatClient.kt:121`; no `callTimeout` (`:49-52`); `endpoint()` string-concatenates (`:155-161`); body read before the status check; `remember(existing)` in `WearSetupScreen.kt:81-83`; prefix-only https validation; `WearCrypto` unused `context`; **no `wear/src/androidTest`**; `WearMainThreadSentinelTest` is a source scan. **All still true.** |

### 1.3 NEW findings from this session (each re-read in the source before being listed)

**N1 (P0) — upstream's own tip fails its own line-budget gate.**
`git show upstream/master:…SettingsModelsPage.kt | wc -l` → **801** against
`KOTLIN_SOURCE_MAX_LINES = 800`. `gh run view 35171806965 -R newo-ether/Agora --log-failed` shows the
identical failure on upstream `master` at `360ae4f8`:

```
> Kotlin source file size verification failed:
   - app/src/main/java/com/newoether/agora/ui/settings/SettingsModelsPage.kt: 801 lines (allowed 800; new_oversized_source)
```
Merging it unchanged would break AGENTS.md rule 1. **Fixed** — see §2.

**N2 (P1) — the sync's conflict policy silently discarded upstream's CI fix.**
`resolve_conflicts()` does `git checkout --ours` for any registered touchpoint, so
`.github/workflows/build.yml` kept `setup-android@v3` + our `packages:` comment and **dropped
upstream's `@v4` bump and its `platform-tools ndk;…` change**. Proven by the real dry run:
`[upstream_sync] touchpoint conflict kept ours: .github/workflows/build.yml (re-apply the Hermes edit if
upstream moved it)`. The message is honest; nothing enforces the "re-apply" it asks for.

**N3 (P1) — the scheduled sync has been failing for two days with no notification.**
`gh run list -R michaelxdips/Agora -w upstream-sync` → `2026-09-18 08:19 failure`,
`2026-09-17 08:43 failure`. Log: `Automatic merge failed` → `touchpoint_guard: FAIL
.github/workflows/build.yml changed 15 lines (budget 12)` → `GUARD FAILED after merge`. Root cause is
N2 plus a budget the take-ours resolution blows through. A nightly job that fails silently is worse
than no job.

**N4 (P1, real product bug) — `getEffectiveBaseUrl` returned null for every built-in provider with no
explicitly configured base URL.** `ProviderRegistry.kt:190`
`…?.takeIf { !isBuiltIn(providerName) }?.defaultBaseUrl`.
Chat still worked because the provider classes fall back to their own defaults
(`BaseOpenAiProvider.kt:98`, `AnthropicProvider.kt:252`, `GeminiProvider.kt:238`) — but every consumer
that asks the *registry* got null, and `PairingListenerService.kt:86` / `SettingsWatchSetupPage.kt:78`
are two of them. So a user happily chatting with OpenAI or Anthropic was told **"No base URL or model
selected. Configure a provider first."** when they pressed *Send to watch*. **Fixed** + a regression
test that is red on the old line and green on the new.

**N5 (P2) — dead toolchain paths in three scripts.**
`grep -n Chatapp scripts/*.sh` → `audit_gate0.sh:12,13,18`, `persona_update.sh:81,82`,
`upstream_sync.sh:131,132`; `ls /c/Users/Michael/Documents/Chatapp` → **No such file or directory**.
For `upstream_sync.sh` that meant the post-merge gate would run with a `JAVA_HOME` containing no `java`
and abort a good merge with "TESTS/BUILD FAILED".

**N6 (P2) — a project-unrelated fingerprint was committed into a shipped resource.**
`app/src/fdroid/res/values/colors.xml:2` read `fdroid-flavor icon background (forest green)`.
Nothing to do with Agora, Hermes X, or the colour `#123E25`. Removed. (The keystore DN also contains
it; that is a real field echoed by `apksigner` on published APKs, documented not invented, and it cannot
be edited without re-signing every release.)

**N7 (P2) — the phone release is not minified; the wear release is.**
`app/build.gradle.kts:70` `isMinifyEnabled = false` vs `wear/build.gradle.kts:51-52` `true`/`true`.
I verified the wear keep-rules actually work: reading `classes.dex` out of the published
`wear-release.apk` shows `ConfigListenerService`, `MemoryListenerService`, `PairingAckListenerService`,
`WearMainActivity` all **PRESENT** (renamed-away internals such as `WearChatClient`/`WearCrypto` are
absent, as expected).

**N8 (P2) — the phone's global `usesCleartextTraffic="true"` is upstream's line, not the fork's.**
`git show 914e7c8d:app/src/main/AndroidManifest.xml` already had it; the fork's diff adds only the
`PairingListenerService` block. `api/HttpClient.kt:127-137` already refuses a credential over cleartext
to a non-local host. Also upstream's design: `ProviderDefaults.kt:32` ships `http://localhost:11434/v1`
for Ollama. Classify, do not "fix" blindly (F5).

**N9 (P3) — README verification table carried wrong counts.**
`ls wear/build/test-results/testDebugUnitTest/*.xml | wc -l` → **9**, README said 8;
`git rev-list --left-right --count upstream/master...main` → **5 83**, README said 65/4. Both fixed.
The rest of the table re-derived correct: 394 app XML reports, 14 registered touchpoints with 13
carrying a diff, both published APKs `7188ce70…aa56d7`, `sha256sum -c SHA256SUMS` OK.

**N10 (P3) — STATUS.md contradicted the live repo from an unmarked historical section.**
`STATUS.md:744` "0 releases" while `gh release list` shows three; `STATUS.md:515` claims
`3.0.0-hermesx` / code 31 while the build files say `3.0.3-hermesx` / 34. A dated **CURRENT STATE**
block now heads the file and the "0 releases" bullet is corrected in place as history.

**N11 (P3) — `UPSTREAM_TOUCHPOINTS.md`'s machine-parsed block was closed one row early.**
`<!-- GUARD:DATA:END -->` sat at line 30, *above* the human table's last row, so that row was invisible
to the guard's own parser. Rows 13/14/15 now sit outside the data block where they belong.

### 1.4 Subagent findings I re-read in the source and accept

| Severity | Defect | Source I read |
|---|---|---|
| P1 | A cancelled/timed-out pairing leaves `WearSignals.pairing` on `Sending`/`Sent`, and the setup screen disables the Pair button on exactly those states (`enabled = !waitingForPhone`) — a dead end until process kill | `WearSetupScreen.kt:89, 201`; only two writers of `WearSignals.pairing` (`WearListeners.kt:61`, `WearPairing.kt:153`) and neither resets on cancellation |
| P2 | `WearChatClientTest.theApiKeyTravelsAsABearerHeaderAndTheModelInTheBody` never asserts the header it is named for — the test server captures only the request line and body, so an `Authorization` regression passes. A sibling test does use `headers` for the ack case, so the capture is capable of it | `WearChatClientTest.kt:118-126` (test), `:44-64` (capture) |
| P2 | `LatexRendererTest.testAllDollarCases` and `testDollarAmountNotLatex` compute `PASS`/`FAIL`, print it, and **never assert** — they cannot fail | `LatexRendererTest.kt:40-65`, `:86-100` |
| P2 | `tracker_serializesWorkerAndInteractionUpdates` has no assertion at all; `shutdownNow` merely hides a hang | `IncrementalStreamingMarkdownTest.kt:490-510` |
| P3 | `DuckDuckGoScraperTest` re-declares the production regexes inline, so a regex change in `DuckDuckGoScraper.kt` leaves the test green — it tests its own copy (production constants confirmed at `CAPTCHA_REGEX:83`, `VQD_REGEX:86`, `OFFSET_REGEX:89`) | `DuckDuckGoScraperTest.kt:248,255,271,280,292` |
| P3 | `WearAnswerTextCoverageTest` asserts `WearFontCoverage.size > 8000`, where `size` is computed from the same hand-written range list under test (`WearFontCoverage.kt:70`) — a tautology | both files read |
| P3 | `AutopilotSettings.underDailyCap` (the real daily-cap rule) has **no JVM test**; the only exercise is an androidTest that reimplements the rule instead of calling it | `AutopilotSettings.kt:45-52`; `AutopilotMemoryInstrumentedTest.kt:178-181` |
| P3 | `WearFontCoverage` is generated by an untracked `_workbench/gen_coverage.py` (`ls _workbench` → does not exist), so the table has no provenance and cannot be re-derived | `WearFontCoverage.kt:6-17` |
| P3 | `WatchSync.PHONE_CAPABILITY` and `WatchSync.UNREACHABLE` have **no call sites** (grep) — the live copies are the flavors' `wear.xml` and `PairingListenerService.UNREACHABLE` | grep over `app/src/main/java` |

Dropped as transcript artefacts (not reproducible as stated): a "`.corrupt` fixed name" claim whose
quoted fix belonged to an unrelated test, and a severity/title pair that was cross-contaminated in the
log. Not repeated.

### 1.5 What is NOT a defect, and should stop being repeated

* "Exactly-once" in `WearOfflineQueue`'s KDoc: the class text is honest about the crash case, but the
  claim as written ("exactly-once on reconnect, at-least-once on crash") is still wrong for *external
  delivery* — the provider can be billed twice. The old audit was right; the correction belongs in the
  KDoc and README, not in new machinery.
* `putDataItem().await()` → "watch installed": correct finding, unchanged.
* The Wear release certificate matching the phone's: **verified true** on the published assets.

---

## 2. What the 5 upstream commits are, and what was pullable

`git diff --stat 914e7c8d upstream/master` → 46 files, +1863/−495.

| Commit | Subject | Outcome |
|---|---|---|
| `66c323e9` | Stream provider image request bodies | pulled (new `StreamingJsonRequestBody.kt` + its test) |
| `9f2908aa` | Fix Android SDK setup in CI | pulled — kept upstream's `setup-android@v4` **and** the fork's `packages:` comment instead of take-ours |
| `63145a04` | Optimize RAG reconciliation and cache status | pulled (`RagManager`, `EmbeddingCacheWorker`, `SemanticIndexLedger`) |
| `a37759f9` | Test RAG count refresh and scheduling concurrency | pulled (`RagManagerTest.kt` +423) |
| `360ae4f8` | OpenCode Go provider (#116) | **pulled** after fixing N1 |

Mergeability: `git merge-tree --write-tree HEAD upstream/master` → **exactly one conflict**,
`.github/workflows/build.yml`; `README.md` auto-merged (upstream's provider-count line + our fork
README). `main` is now **0 behind / 87 ahead**.

**Answer to "what can we really pull": all five.** `360ae4f8` was blocked by *upstream's* broken
budget, and that is solvable without waiting: `SettingsModelsPage.kt` carries **10 imports nothing in
its body references**, and the size policy rejects any baseline entry at or under the cap
(`KOTLIN_SOURCE_BASELINE_CAPS` is empty; `recordedLines <= maximumLines` → `INVALID_BASELINE`).
Removing them takes the file **801 → 791**. Proved in a throwaway clone first:

```
$ git clone … && git merge upstream/master && <remove the 10 imports>
$ ./gradlew :app:compileFdroidDebugKotlin verifyKotlinFileSize
Kotlin source size verified: 1027 files, maximum 800 lines at …ConversationSelectionControllerTest.kt
BUILD SUCCESSFUL in 1m 32s
```

then here, on the real branch (full tip merged):

```
$ ./gradlew :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest :wear:testDebugUnitTest verifyKotlinFileSize
app fdroid 2597 tests / play 2580 / wear 82 — 0 failures, 0 errors, 3 skipped
Kotlin source size verified: 1027 files, maximum 800 lines
BUILD SUCCESSFUL in 4m 56s
$ bash scripts/touchpoint_guard.sh   →  PASS (360ae4f8…)
```

---

## 3. Plan A — FIX

**F0 — land the full upstream tip. DONE.** `main` = `396a8971`, 87 ahead / 0 behind.

**F1 — make the gate scripts mean what they print. DONE for all four.**
`audit_gate0.sh` (paths discovered; every section accumulates into `$fail`; signature compared, not
echoed; `exit $fail`), `audit_test_counts.py` (failing suite → exit 1, proved with a seeded XML:
`TOTAL 10 tests, 10 failures, 10 errors` → `EXIT=1`), `touchpoint_guard.sh` (defaults to
`upstream/master` via a fallback chain; a missing ref now **FAILS** — proved: missing ref → exit 1,
default → exit 0, `GUARD_ALLOW_MISSING_REF=1` → exit 0 with the weaker check), `upstream_sync.sh` (N10
compares `$TARGET`; toolchain resolved).

**F2 — the 801-line file. DONE** (791 lines; touchpoint #14, 15/20 used).

**F3 — stop the nightly sync failing silently. DONE.**
The take-ours policy no longer discards upstream's CI fix (the `build.yml` conflict is resolved by
keeping both intents), and `.github/workflows/upstream-sync.yml` now has an `if: failure()` step that
opens — or comments on — an issue labelled `sync-failure` (label created on the repo; verified present
with `gh label list`). GitHub does not notify on scheduled-workflow failure by default, which is why
two days of red runs went unnoticed. Gate met for the notification half: the workflow's step list
parses (`yaml.safe_load` → 6 steps) and the label exists. A *forced* failure creating an issue has not
been exercised yet (it needs a real red run) — that is the honest state.

**F4 — dead paths. DONE** (`audit_gate0.sh`, `persona_update.sh`, `upstream_sync.sh`).

**F5 — classify the phone's cleartext policy (N8). OPEN.** Gate: a test that
`guardCleartextCredentials` throws for `http://evil.example/v1` with an `Authorization` header and does
not throw for `http://127.0.0.1:11434/v1`.

**F6 — watch request correctness. OPEN** — one coordinator owning fresh-send/drain/Send-now/retry;
`callTimeout`; `Exception` not `Throwable`; a real URI builder for `endpoint()`; a parser that accepts
content-part arrays; typed failure classes (401/404/parse ≠ "Offline"); queue limits + dead-letter
instead of deletion at attempt 3. Gate: duplicate-tap red→green; 401 no-retry; 429 honours `Retry-After`.

**F7 — phone↔watch protocol. OPEN** — `requestId` + `schemaVersion` + `configRevision`; a typed ack so a
protocol mismatch stops rendering as "configuration received"; targeted credential delivery; `await()`
the `deleteDataItems` task. Gate: a **stale** ack must not satisfy a new request.

**F8 — memory sync ownership. OPEN** — `MemorySnapshotPusher` lives on `rememberCoroutineScope()`
(`MainActivity.kt:343-352`) so memory written while the phone UI is closed never reaches the watch, and
`MemoryManager.activeMemoryRevision` is instance-local (`MemoryManager.kt:22-23`). Gate: background-write
push test; empty snapshot clears the watch cache.

**F9 — the pairing dead end. DONE (fix + regression test written; final suite run pending).**
`WearPairing.request` had no cancellation path, so a cancelled request (wrist-down, config change,
process death — the coroutine scope is the composition's) left the process-wide
`WearSignals.pairing` on `Sending`/`Sent`. `WearSetupScreen.kt:89` computes `waitingForPhone` from
exactly those two states and `:201` disables the Pair button on it (the credential fields go read-only
too), so the user could not retry and could not type a key — a dead end until the app was killed.
Fixed by catching `CancellationException`, setting `Idle`, and re-throwing so cancellation still
propagates. New test `a cancelled request leaves no in-flight status behind` in `WearPairingTest`
drives a transport whose `awaitAck` throws `CancellationException` and asserts the last status is
`Idle` — not `Sending`/`Sent`. Gate: `:wear:testDebugUnitTest` green (83 tests) **and** the test fails
against the pre-fix code.

**F10 — doc truth. DONE for the numbers** (README counts, sync position, STATUS current-state block).

**F11 — the vacuous tests (§1.4). PARTLY DONE.**
Fixed and **proved by mutation**: `LatexRendererTest.testAllDollarCases` and `testDollarAmountNotLatex`
now collect failures and assert (touchpoint #16). The proof is unusually strong — with
`parseInlineDollarMath` genuinely disabled in the parser, the suite goes red on **7** tests that do
assert while those two stayed green, and after the fix the same mutation turns them red too. Also
fixed: `WearChatClientTest.theApiKeyTravelsAsABearerHeaderAndTheModelInTheBody` now asserts the
`Authorization: Bearer …` header it is named for (the test server captured only the request line and
body). Proved by mutation: deleting `.addHeader("Authorization", …)` from `WearChatClient.kt` makes that
test FAIL, and restoring it makes it pass (22/22).
**Still open:** `IncrementalStreamingMarkdownTest.tracker_serializesWorkerAndInteractionUpdates` has no
assertion at all, and `DuckDuckGoScraperTest` still re-declares the production regexes inline.

---

## 4. Plan B — MODIFY (make the claims structural)

**M1 — one release-provenance gate**: download both APKs for a tag, `apksigner verify --print-certs`,
assert equal digests, assert the tag commit is the CI head SHA, assert both suites ran on it.
**M2 — Wear in CI** (`:wear:testDebugUnitTest`, `:wear:assembleRelease`, `:wear:lintVitalRelease`, a wear
artifact).
**M3 — signing fail-closed**: a release build with no keystore must fail, not sign with debug.
**M4 — guard hardening. DONE** (default ref + missing-ref failure).
**M5 — N10 detector. DONE.**
**M6 — a `wear/src/androidTest` suite**: Keystore round-trip, both listener services resolving from the
manifest, `rememberSaveable` on the setup fields, the answer card inside the round mask.
**M7 — generate `CODE_MAP.md`** instead of hand-maintaining it (it still cites an `_tools/` section that
does not exist here).

---

## 5. Plan C — OPTIMISE

**O1 — R8 for the phone release (N7).** `isMinifyEnabled = false` on a 49.6 MB APK. Behind a measured
comparison: build with R8, run the full 2597-test suite plus a device smoke pass, keep only if both are
green.
**O2 — atomic writes**: `WearOfflineQueue.writeAll` rewrites the whole array per op and its rename
fallback does `file.writeText(temp.readText())`; `WearConfigStore.write` and `WearMemoryCache.write` write
straight to the final path. One temp+fsync+rename helper.
**O3 — payload size**: the phone base64s the **full** memory snapshot (Data Layer ≈100 KB, +33%) and the
watch then truncates to 500 tokens. Send the derived core context instead.
**O4 — watch call budget**: `callTimeout`, a body cap far below 1 MiB, status read before the body.
**O5 — strings/locale**: move the remaining hardcoded watch strings into `strings.xml`; revisit
`localeFilters += listOf("en")`.
**O6 — split `WearMainActivity` (706 lines)** *after* F6/F7, so it is a mechanical move of tested logic.

---

## 6. Order of work, with the gate that closes each step

| Step | Work | Gate | State |
|---|---|---|---|
| 1 | F0 land all 5 upstream commits | merged tree green + guard PASS | **DONE** |
| 2 | F1 strict gate exit codes | seeded failure → nonzero | **DONE** |
| 3 | F2 the 801-line file | `verifyKotlinFileSize` green with `360ae4f8` merged | **DONE** |
| 4 | F3 sync conflict + failure notification | dry-run keeps upstream's `@v4`; forced failure opens an issue | **DONE** (notification step + `sync-failure` label; a real forced-failure run is not yet exercised) |
| 5 | F4 dead paths | `bash -n` on all three; sync reaches gradle | **DONE** |
| 6 | N4 built-in base URL (new) | regression test red→green | **DONE** |
| 7 | F9 pairing dead end | cancelled request leaves the screen usable | **DONE** (fix + regression test; suite re-run pending) |
| 8 | F6 + F7 watch correctness and protocol | duplicate-tap red→green; 401 no-retry; typed ack | OPEN |
| 9 | F8 memory sync ownership | background-write push test; empty snapshot clears the cache | OPEN |
| 10 | F11 the vacuous tests | each one fails when the behaviour it names is broken | **PARTLY DONE** (2 of 4 fixed and mutation-proved; concurrency + scraper tests OPEN) |
| 11 | M1–M3, M6, M7 | provenance job green on a tag; wear in CI; release fails without a keystore | OPEN |
| 12 | F5 cleartext classification | the guard test above | OPEN |
| 13 | O1–O6 | R8 phone build green on the full suite; payload bytes measured | OPEN |

**Stop rule (AGENTS.md):** if a step cannot be closed in 30 minutes, write `BLOCKED.md` with the options
and take the most conservative safe one. A stopped agent with a green `main` is a success.
