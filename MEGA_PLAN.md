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
`app/src/fdroid/res/values/colors.xml:2` carried a comment naming an unrelated project and its colour.
Nothing to do with Agora, Hermes X, or the colour `#123E25`. Removed. (The release certificate's DN
also embedded it; that is a real field echoed by `apksigner` on published APKs. The **copies** of that
DN in this repo's docs were removed in Session 4 — the certificate itself cannot be re-cut without
re-signing every release, so the live signature keeps the old field and the docs now say so instead of
repeating it.)

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

**F5 — classify the phone's cleartext policy (N8). DONE.**
The policy is upstream's, so it was classified rather than switched off (turning
`usesCleartextTraffic` off would break the documented self-hosted Ollama setup). What made the global
flag safe is `HttpClient.guardCleartextCredentials`, and that guard had **no test**. Gate met:
`app/src/test/java/com/newoether/agora/autopilot/HttpClientCleartextGuardTest.kt` — a credential over
`http://` to `evil.example` throws, to `127.0.0.1:11434` does not, to a LAN/Tailscale host does not,
to `localhost.evil.com` does, and a malformed URL fails closed. **Mutation-proved:** replacing the
guard's header check with `if (false)` turns 4 of those tests red.

**F6 — watch request correctness. DONE.**
One coordinator owns duplicate suppression (`WearSendCoordinator`: same question cannot run twice,
a different question is never blocked, a cancelled send frees the guard); `callTimeout(90s)` added on
top of connect/read; the body cap lowered 1 MiB → 256 KiB; the status is read **before** the body; the
endpoint is built through `HttpUrl` (so a base URL with a query string no longer becomes
`…/v1?k=v/chat/completions`); the parser accepts content-part arrays; `Exception` replaces `Throwable`
so an `OutOfMemoryError` is not reported as "question held offline"; failures are typed
(`WearChatException.kind` + `retryable` + `retryAfterMs`); the queue has a 50-entry cap, a bounded
dead-letter file instead of deletion at attempt 3, and the drainer honours a `Retry-After` up to 30 s.
Gate met: duplicate-tap red→green (`WearSendCoordinatorTest`, 7 tests); 401 no-retry
(`WearQueueDrainerTest`, and `WearChatClientTest.a401IsNotRetryableAndA429Is`); 429 honours
`Retry-After` (`a Retry-After is honoured before the pass gives up`). **Mutation-proved** on both the
permanent-failure path and the duplicate guard (4 tests red under mutation).

**F7 — phone↔watch protocol. DONE (one item deliberately not done).**
`requestId` + `schemaVersion` on the request (`PairingRequestSchema`, phone and watch sides), a
**typed** ack (`PairingAck`) so a protocol mismatch or a late answer stops rendering as "configuration
received" — the new `PairingStatus.StaleAck` says so instead; the credential item deletion is
`Tasks.await()`ed rather than fired and forgotten; the watch's `awaitAck` discards an ack that names a
different request and keeps waiting. Gate met: `an answer for a different request is refused, not
reported as connected` and `an ack with no request id is not this request's answer` — both red when
the id check is replaced with `ack != null`.
**Not done: "targeted credential delivery".** The config still travels on the Data Layer
(`/hermes/config`), which is the channel the design chose *because* it works whether or not the phone's
listener service is alive — a Data Layer *message* is best-effort, and a dropped message would be a
dropped credential with no retry. Narrowing the delivery to the requesting node is a real change to
that trade-off, not a small one, so it is recorded as open rather than done.

**F8 — memory sync ownership. DONE.**
`MemorySnapshotPusher` lived on the composition scope, so memory written while the phone UI was
**closed** never reached the watch. `MemoryPushStartup` observes `activeMemoryRevision` from
`AgoraApplication`'s process scope and hands the transfer to `MemorySnapshotPushWorker`, whose work
survives the process. Gate met: `CoreContextDerivationTest` covers the derivation and the payload
measurement; `WearMemoryRulesTest` covers "an empty snapshot clears the watch cache" (the rule was
extracted out of the `WearableListenerService` precisely so it could be tested at all). The
**background-write push test** the gate also asked for is the one item here that is *not* a unit test:
the worker needs WorkManager, so its trigger is asserted structurally (the process-scoped observer
calls `MemoryPushScheduler.schedule`) rather than by a JVM test that would have to fake WorkManager.

**F9 — the pairing dead end. DONE.**
`WearPairing.request` had no cancellation path, so a cancelled request (wrist-down, config change,
process death — the coroutine scope is the composition's) left the process-wide
`WearSignals.pairing` on `Sending`/`Sent`. `WearSetupScreen.kt:89` computes `waitingForPhone` from
exactly those two states and `:201` disables the Pair button on it (the credential fields go read-only
too), so the user could not retry and could not type a key — a dead end until the app was killed.
Fixed by catching `CancellationException`, setting `Idle`, and re-throwing so cancellation still
propagates. Gate met: `:wear:testDebugUnitTest` green (**137 tests**) **and** the test fails against
the pre-fix code (verified by the mutation pass on the same file).

**F10 — doc truth. DONE for the numbers** (README counts, sync position, STATUS current-state block),
and `CODE_MAP.md` is now generated (`scripts/gen_code_map.sh`, `--check` for CI) instead of typed by
hand — the hand-written version cited an `_tools/` directory that does not exist here.

**F11 — the vacuous tests (§1.4). DONE.**
Fixed and **proved by mutation**:
* `LatexRendererTest.testAllDollarCases` / `testDollarAmountNotLatex` now assert (touchpoint #16);
* `WearChatClientTest.theApiKeyTravelsAsABearerHeaderAndTheModelInTheBody` asserts the
  `Authorization: Bearer …` header it is named for;
* `IncrementalStreamingMarkdownTest.tracker_serializesWorkerAndInteractionUpdates` asserted **nothing**
  — it now asserts the tracker's state contract (bounded deque, monotonic birth times, usable
  afterwards) and any exception from a lane surfaces through `futures.get()`. The first version of this
  assertion was **wrong** (it required each sample's glyphs to be born at or before that call's
  `nowMs`, which two independent clocks do not guarantee) and the suite caught it: `2624 tests, 2
  failed`. Corrected, then green.
* `DuckDuckGoScraperTest` no longer re-declares the production regexes: the three constants are
  `internal` and the tests use them (touchpoints #20/#21).
* `WearAnswerTextCoverageTest`'s `size > 8000` tautology is replaced by a pinned 8755 (the union the
  generated file's header documents) plus a new `WearFontCoverageTest` for the table's structural
  invariants (sorted, disjoint, in-range, and genuinely false for uncovered code points).
* `AutopilotSettings.underDailyCap` had **no JVM test**; `AutopilotDailyCapTest` now drives the
  production rule against a fake DAO (cap boundary, `cap <= 0`, the persona-store exclusion, the
  window timestamp).

**F12 (new, corrected mid-execution) — I wrote here that `audit_gate0.sh` was missing
`:wear:testDebugUnitTest`. It is not: `git log --oneline -1 -- scripts/audit_gate0.sh` → `82430fbd`,
and the file's section [3] already runs all three suites. The claim was wrong when I made it and the
file is unmodified. Left here as a correction rather than deleted, because a plan that silently drops
its own bad guesses is the failure mode this document was written to avoid.

**F13 (new) — `WearFontCoverage`'s missing generator.** The header names
`_workbench/gen_coverage.py`, which does not exist in this repo. Rather than re-derive 8755 code points
from fonts that are not on this machine, the table is now **pinned** by `WearFontCoverageTest`
(size + structure + the ASCII set the renderer needs), so a bad regeneration fails loudly. The missing
generator is recorded as an open provenance gap, not claimed as fixed.

---

## 4. Plan B — MODIFY (make the claims structural)

**M1 — one release-provenance gate. DONE.** `scripts/verify_release_provenance.sh <tag>` downloads
both APKs, checks `SHA256SUMS`, asserts the two certificates are equal, asserts the tag's commit has
only successful checks, and asserts a test job ran on it. **Run against the real release:**
`bash scripts/verify_release_provenance.sh v3.0.3` → `PASS: all four release claims hold`, with both
APKs printing `7188ce70…aa56d7`. Two defects in the script itself were found by running it (a native
`gh` cannot write to an MSYS `mktemp` path; Windows ships `apksigner.bat` only) and fixed.
**M2 — Wear in CI. DONE.** `:wear:testDebugUnitTest` in the `test` job; `:wear:assembleRelease`,
`:wear:lintVitalRelease` and the wear APK upload in the `build` job.
**M3 — signing fail-closed. DONE.** Both modules: a release build with no keystore fails at
configuration time instead of signing with the debug key. **Proved:** `./gradlew assembleFdroidRelease`
with the keystore removed → exit 1, message naming `local.properties`.
**M4 — guard hardening. DONE** (default ref + missing-ref failure).
**M5 — N10 detector. DONE.**
**M6 — a `wear/src/androidTest` suite. DONE (compiles; not yet executed).**
Two classes, 11 tests: the keystore round-trip, the config file not being plaintext, a truncated
config being rejected, all three listener services resolving from the manifest, the launcher activity,
the standalone metadata, the memory cache clearing on empty, and the setup fields' save/restore path.
`:wear:compileDebugAndroidTestKotlin` is green. **Not run:** this machine has no device or emulator
(`adb devices` is empty), so no test in the suite has been executed — recorded, not claimed.
**M7 — generate `CODE_MAP.md`. DONE.** `scripts/gen_code_map.sh` regenerates everything below a
marker from the tree and the guard registry; `--check` fails when it is stale. The hand-written
`_tools/` section is gone with it.

---

## 5. Plan C — OPTIMISE

**O1 — R8 for the phone release (N7). DONE (Session 4).** The gate was "build with R8, run the full
suite plus a device smoke pass, keep only if both are green", and it stayed open because the device
half could not run — no emulator, no device. Session 4 created the emulator pair and ran the whole
gate: `assembleFdroidRelease` green with `isMinifyEnabled = true` + `isShrinkResources = true`, the
APK **49,631,099 → 29,497,445 bytes (−40.6%)**, 13/13 dex keep assertions present, the release APK
installed and launched on `emulator-5554` with no crash, and the R8-sensitive workers
(`UpdateCheckWorker`, `AutoBackupWorker`, `MemorySnapshotPushWorker`) each reporting
`Worker result SUCCESS` in the minified build. The keep rules that made it safe are derived from the
C++ call sites (`llama_chat_template.cpp` resolves Kotlin classes by literal name through
`FindClass`/`GetFieldID`; `llama_chat_callbacks.cpp` fetches `NativeChatCallback` methods by name),
plus every manifest component, every `ListenableWorker` and both `SandboxManagerFactory` classes.
Full evidence in `STATUS.md` §Session 4.
**O2 — atomic writes. DONE.** `WearAtomicFile` (temp → fsync → rename, with a copy fallback that is the
only in-place path) now backs all three watch state files: the offline queue, the config store and the
memory cache. `WearAtomicFileTest` covers the round trip, the absence of a leftover temp file, a failed
write leaving the previous contents intact, and the queue end-to-end.
**O3 — payload size. DONE, measured.** The phone now derives the core context before pushing
(`CoreContextDerivation`), and the watch's rule is idempotent for a payload from an older phone.
`CoreContextDerivationTest` asserts the number: an 80 KB+ snapshot's payload falls by more than 10× and
stays under 4 KB including base64 overhead.
**O4 — watch call budget. DONE** (`callTimeout(90s)`, 256 KiB body cap, status read before the body —
same change as F6).
**O5 — strings/locale. DONE.** Every user-visible sentence in the watch module moved into
`res/values/strings.xml` (24 in `WearMainActivity`, 12 in `WearSetupScreen`); `Text("…")` literals in
those files are now 0. `:wear:lintDebug` reports no `UnusedResources`. The `localeFilters = ["en"]`
filter is **kept deliberately** and the reason is written at the site: one locale is the right size for
a 2.8 MB watch APK, and adding one is now a `values-<lang>/strings.xml` plus an entry, which is what
moving the strings bought.
**O6 — split `WearMainActivity` (748 lines). NOT DONE.** The plan's own sequencing says "after F6/F7,
so it is a mechanical move of tested logic" — and it is 748 lines, under the 800-line cap. Doing it now
would be a large diff with no gate of its own, at the end of a session that has already changed this
file heavily. Recorded as open with the sequencing reason intact.

---

## 6. Order of work, with the gate that closes each step

| Step | Work | Gate | State |
|---|---|---|---|
| 1 | F0 land all 5 upstream commits | merged tree green + guard PASS | **DONE** |
| 2 | F1 strict gate exit codes | seeded failure → nonzero | **DONE** |
| 3 | F2 the 801-line file | `verifyKotlinFileSize` green with `360ae4f8` merged | **DONE** |
| 4 | F3 sync conflict + failure notification | dry-run keeps upstream's `@v4`; forced failure opens an issue | **DONE** (notification step + `sync-failure` label; a real forced-failure run is still not exercised) |
| 5 | F4 dead paths | `bash -n` on all three; sync reaches gradle | **DONE** |
| 6 | N4 built-in base URL (new) | regression test red→green | **DONE** |
| 7 | F9 pairing dead end | cancelled request leaves the screen usable | **DONE** |
| 8 | F6 + F7 watch correctness and protocol | duplicate-tap red→green; 401 no-retry; typed ack | **DONE** |
| 9 | F8 memory sync ownership | background-write push test; empty snapshot clears the cache | **DONE** (the empty-cache half is a JVM test; the background trigger is asserted structurally — see F8) |
| 10 | F11 the vacuous tests | each one fails when the behaviour it names is broken | **DONE** (all six, mutation-proved) |
| 11 | M1–M3, M6, M7 | provenance job green on a tag; wear in CI; release fails without a keystore | **DONE** (M1 run against the real `v3.0.3`; M6 compiles but is unrun — no device) |
| 12 | F5 cleartext classification | the guard test above | **DONE** |
| 13 | O1–O6 | R8 phone build green on the full suite; payload bytes measured | **O2–O5 DONE; O1 and O6 OPEN with the reasons in §5** |

**Stop rule (AGENTS.md):** if a step cannot be closed in 30 minutes, write `BLOCKED.md` with the options
and take the most conservative safe one. A stopped agent with a green `main` is a success.

---

# Session 2 — the 2026-09-19 audit (171 lines, 37 lanes)

Written 2026-09-19 against `main` @ `dab42ba1` and `upstream/master` @ `360ae4f8`. The section above is
the previous session's record and is left intact; this one covers the new audit.

Verification harness: 52 checks against the live tree. First full run:
**49 CONFIRMED, 2 REFUTED, 1 CHECK.** Nothing below is claimed from the audit's word alone.

## S2.1 REFUTED — two findings did not survive contact with the tree

| # | Audit claim | What is actually true |
|---|---|---|
| R1 | `gradle/libs.versions.toml:13 ksp="2.3.9"` is the "wrong scheme", "breaks `app/build.gradle.kts:5`", resolution "likely fails" | **False.** KSP 2.x uses plain `MAJOR.MINOR.PATCH`; the `KOTLIN-KSP` joined form is the 1.x scheme. `2.3.9` resolves — `~/.gradle/caches/modules-2/files-2.1/com.google.devtools.ksp/` holds `com.google.devtools.ksp.gradle.plugin/2.3.9` and `symbol-processing-api/2.3.9` — and `:app:compileFdroidDebugKotlin` is **BUILD SUCCESSFUL**. No change. |
| R2 | `libs.versions.toml:27 playServicesWearable="19.0.0"` "unattested", resolution "likely fails" | **False.** `~/.gradle/…/com.google.android.gms/play-services-wearable/` holds `19.0.0`; `:wear:compileDebugKotlin` is **BUILD SUCCESSFUL**. No change. |

## S2.2 One finding I mis-scored first, and the correction

`wear/proguard-rules.pro` "has no `$$serializer` keeps". My first harness matched the literal string
`serializer` and reported REFUTED — it had matched the file's *comment*. Reading the rules: the wear
file uses `-keepclassmembers` on the model classes, which keeps the *members* of `WearConfig`, not the
generated `WearConfig$$serializer` class kotlinx.serialization resolves by reflection. The phone's file
has the rules that matter. **CONFIRMED**, fixed below.

## S2.3 CONFIRMED, with the line that decided each

Full evidence table (49 rows, file:line + the command) is in §3 of the audit reconciliation written to
`STATUS.md`. The load-bearing ones, and what the line actually said:

| # | Finding | The line I read |
|---|---|---|
| P1.1 | wear proguard misses `PairingAckListenerService` | 3 `-keep` lines, that name absent; manifest declares it `exported="true"` |
| P1.3 | `install*Release` escapes the fail-closed filter | `name.startsWith("assemble") \|\| … "bundle" … \|\| … "package"` |
| P1.4 | keystore `if:` reads an env its own step defines | `:83` `if: ${{ env.KEYSTORE_BASE64 != '' }}` above its own `env:` block |
| P1.14 | unbounded wire reads | `:328` `else input.readUtf8Line()`; `grep -rn maxLineBytes …/api/` → **only HttpClient.kt itself**, so the bound was never passed |
| P1.19 | `SELECT * FROM embeddings` | `:662`; callsites = **the declaration only** — latent, not live |
| P1.25 | CI wires no gate script | `grep touchpoint_guard\|gen_code_map\|audit_gate0\|verify_release` → **no matches** |
| P1.26 | `audit_gate0.sh` §7 never fails | `:167-183` printed the label counts, compared nothing |
| H1 | every ack → `Connected` | `:314` `ack.answers(requestId) -> PairingStatus.Connected` |
| H6 | observer gives up forever | `:47` `awaitContainer() ?: return@launch` |
| H7 | `fetchModels` ignores its `apiKey` | `:500` `HttpClient.fetchModelsResponse("$effectiveBaseUrl/api/tags")` — no headers param; `Authorization` exists only at `:254` (generate) |
| H8 | Anthropic URL has no version segment | `:419` `"$baseUrl/messages"`; `defaultBaseUrl` = `…/v1` at `:244` |
| P2.3 | `WearFontCoverage` has no CJK/ja/ko/ar ranges | `0x4E00`, `0x3040`, `0x0600` all absent from the 77-line file; its named generator `_workbench/gen_coverage.py` absent |
| G1 | sync position | `git rev-list --left-right --count upstream/master...main` → `0	89` |

## S2.4 Fixed in this session

All edits marked `HERMES INTEGRATION POINT`, per `AGENTS.md` rule 2.

**Release / security.** `wear/proguard-rules.pro` gains the `PairingAckListenerService` keep and the two
serializer rules (P1.1, P1.2 — a service the platform resolves by name was strippable in release, and
debug builds do not minify, which is why no test caught it). `app/build.gradle.kts`'s fail-closed filter
gains `install…` (P1.3). The four CI secrets move to **job-level** `env` so the step's own `if:` can see
them (P1.4). `SecretCrypto`'s plaintext fallback is marked (`plain:v1:`) and detectable rather than
silent (P1.9). `CrashReporter` redacts the trace through `DiagnosticRedactor` before persisting it
(P1.12). `HttpClient`'s stream caps default to 1 MiB / 64 KiB and both public overloads forward them
(P1.14). `audit_gate0.sh` §7 compares and fails (P1.26). `build.yml` runs `touchpoint_guard.sh`,
`gen_code_map.sh --check` and `audit_test_counts.py` in the `test` job (P1.25). `OllamaProvider` sends
the bearer on `fetchModels` (H7). The Anthropic URL gains `/v1` when absent (H8).

**The pairing protocol (H1 + H2) — the most consequential one.** The ack carried `requestId` and a
sentence, and the watch treated "an answer naming my request" as success. But `NO_KEY`, `NO_ENDPOINT`,
`PUSH_FAILED`, `STARTING_UP` and the mismatch refusal *all* echo the id — so a watch with no key, or a
phone that never pushed, or a version mismatch, all rendered *"Phone replied — configuration
received."* over a config that was never installed. The id says **which** request was answered, not
whether it was **served**. `PairingRequest.ackBody` now carries `ok` (default `true`, so an older
phone's ack keeps its meaning); the watch gains `PairingAck.ok` / `serves(requestId)` and the new
terminal state `PairingStatus.Refused`; `PushReason` gains `ok` so the outcome the phone computed is
what travels; `PairingListenerService` replies with the `PushOutcome` instead of flattening it to a
string, and sends `ok = false` for `PROTOCOL_MISMATCH` and the failure fallback.

**Memory push.** `MemoryPushStartup` no longer gives up permanently when the container is not ready —
`awaitContainer()` answers null while the startup gate is `Blocked`, a state the *user* resolves, so the
old `?: return@launch` left the watch with no push path for the life of that process; it now waits (5 s
poll, no deadline, H6). `drop(1)` removed: it discarded the value at subscribe time, so a write landing
before the subscription was never pushed and the watch kept the older snapshot indefinitely —
collecting the current value **is** the missing initial sync. `distinctUntilChanged()` went with it (the
source is a `StateFlow`; the operator is a deprecation *error* on one, and the compiler caught it).
`MemorySnapshotPusher` no longer pushes directly — both observers are live on the same
`activeMemoryRevision` when the UI is open, so every write while the user watched went over Bluetooth
**twice**; both now request through `MemoryPushScheduler`, and `DEBOUNCE_MS` is defined once.
`pushNow` deleted (zero callers; a second way to push is how the double-push arose).
`MemorySnapshotPushWorker` gains a 60 s timeout and returns `Result.retry()` (capped at 2) instead of
`Result.success()` for every outcome, including a failure to produce the payload at all.

**Watch client / queue.** `endpoint()` trims trailing slashes before the `endsWith` check (P2.1);
`awaitAck` uses `SystemClock.elapsedRealtime()` (P2.2 — a wall-clock deadline broke on an NTP
correction or DST shift during the 20 s wait).

**Data / performance.** `RemoteImageCache`'s eviction loop moved out of `synchronized(lock)` —
`length()`, `delete()` and `listFiles()` are blocking syscalls that every concurrent `load` was waiting
on (P2.6). **`ChatSearchDao` was reverted** — see §S2.7.

## S2.7 A finding the test suite refuted, and my own wrong fix

The audit's `ChatSearchDao` finding ("`CROSS JOIN messages CROSS JOIN conversations` cartesian before
predicate, should be `INNER JOIN`") is **wrong**, and I acted on it before checking. Two independent
reasons, both verified:

* **SQLite documents `CROSS JOIN` as a planner directive.** "Programmers can force SQLite to use a
  particular loop nesting order for a join by using the CROSS JOIN operator instead of just JOIN, INNER
  JOIN, … SQLite will not reorder the tables of a CROSS JOIN." It is a hint, not a cartesian product —
  the join predicates were already in the `WHERE`.
* **It was deliberate upstream.** `git log` on the file: `0071bd1c perf: speed up large semantic
  searches` introduced it, and `SemanticSearchBoundedSourceContractTest.kt:36-41` pins the exact string
  so a future edit cannot silently undo the hint.

My change made the suite go red — `2624 tests completed, 1 failed` /
`semanticSearchHotPathUsesKeysetPagesInsteadOfAFullEmbeddingList` — which is the test doing its job.
Reverted with `git checkout --`, file confirmed byte-clean against upstream.

This is the one place where I acted on the audit without reproducing it first, and the suite caught it.
Recorded here rather than quietly dropped, because the audit's framing ("cartesian before predicate")
sounds correct to anyone who has not read SQLite's join documentation.

**Registry.** Four touchpoints added (`HttpClient.kt`, `AnthropicProvider.kt`, `OllamaProvider.kt`,
`CrashReporter.kt`); `build.yml` budget 40 → 80 for the three gate steps. `touchpoint_guard.sh` →
**PASS**. The guard caught each unregistered edit as it happened, which is the correct behaviour.

## S2.5 Still open, with the reason

| # | Item | Why |
|---|---|---|
| O1 | ~~R8 for the phone release (P1.27)~~ | **CLOSED (Session 4).** Emulator pair created; gate run end to end — R8 build green, 2658/2631/149 tests 0 failures, dex keeps 13/13, release APK installed and launched, workers SUCCESS. APK 49.6 MB → 29.5 MB. |
| O6 | Split `WearMainActivity` | 748 lines, under the cap, heavily changed this session. |
| P1.20–P1.22 | Sandbox `--allow-untrusted`, shared-storage bind, TOCTOU | Each is a deliberate design decision in the PRoot integration; the fixes change the sandbox's security *model*, which wants the maintainer's call, not a drive-by patch. |
| P1.8, P1.10, P1.11, P1.13 | Plaintext secrets in DataStore / export / backup / proxy password | Each needs a **migration** — existing installs hold plaintext that must keep working. P1.9's marked fallback is the prerequisite and is now in place. |
| P1.15–P1.18 | Destructive migrations, blocked-database delete | Editing a shipped migration rewrites history for installs that already ran it; these need a forward migration. |
| P1.6, P1.7 | Global cleartext, exported listener without a permission | P1.6 is upstream's line and disabling it breaks the documented self-hosted Ollama setup; the guard that makes it safe now has a test. P1.7's fix (a `signature` permission) changes the pairing contract between two apps. |
| H3, H4, H5, H9, H10, H11 | Targeted credential delivery, the send race, idempotency keys, work-name collision, the breaker's missing open state, transcript-grounded reflection | Each is a behaviour change with its own trade-off, several documented as deliberate in the code's own KDoc. Evidence is in the audit table so the next session starts from file:line. |
| P3.1–P3.4 | Fastlane changelogs, `NOTICE` submodule count, `mkdocs` URLs, docs locales | Metadata/doc drift; no code path affected. |

## S2.6 Push plan — and its status

1. **DONE.** Full suite green: fdroid **2624** / play **2607** / wear **141**, 0 failures, 0 errors —
   `python scripts/audit_test_counts.py` → exit 0.
2. **DONE.** `bash scripts/touchpoint_guard.sh` → **PASS**, 28 entries, none over budget.
3. **DONE.** `bash scripts/gen_code_map.sh` regenerated (193 lines); `--check` → exit 0.
4. **DONE.** `STATUS.md` carries the Session 2 evidence block, counts read from the XML.
5. **DONE.** Eight `hermes:` commits, one concern each (see the list below).
6. **DONE.** `git push origin main` → `665a20a1`; `origin/main` == `main`, 0 ahead / 0 behind.
7. **DONE.** `gh run watch` — run **`35423403564` completed `success`**. The three new gate steps
   executed for the first time and all three passed, as did the release-signed F-Droid build, the wear
   release build and the APK uploads. "Restore signing key" ran, which it never had before (P1.4).

The first two pushes were **red**, and both failures were real rather than noise — they are the
reason the last three commits exist:

| Run | Result | What it caught |
|---|---|---|
| `35421339206` | failure | `touchpoint_guard.sh` → `FAIL upstream ref 'upstream/master' not present`. `actions/checkout@v4` fetches one branch and creates no remote tracking refs, so inside CI there was no ref to compare against — and the guard fails closed, which is the behaviour the previous session deliberately built in. The workflow now adds the upstream remote first. |
| `35422153801` | failure | The guard ran and found upstream, then failed with `FAIL unregistered upstream file modified: gradlew`. `gradlew` is committed mode `100644` and the tests step begins `chmod +x ./gradlew`; on Linux (`core.fileMode` true) that is a mode change to an upstream file. The guard step now runs **before** the tests step, so it sees the tree as committed. |
| `35422954778` | failure | The guard passed, `CODE_MAP is up to date` failed on **ordering alone** — same rows, same counts, different sequence. Bare `sort` uses host locale collation, so Windows/MSYS and the Linux runner disagree. Seven calls are now `LC_ALL=C sort`. |
| `35423403564` | **success** | Everything green. |

Three of those four are the same shape: a check that was correct locally but could not be satisfied on
the host it was meant to run on. Two of them (`HEAD` in the generated file, CRLF) were found by
testing in a real shallow clone rather than by reasoning about it.

### The commits

```
665a20a1  sort the code map by bytes so it cannot depend on the host's locale
14427a4e  run the guard before the chmod, and give build.yml the budget it needs
88659ac7  make the two new CI gates pass in the environment CI actually has
d82002a0  reconcile the 2026-09-19 audit, and stop two doc numbers drifting
44a9be2b  CODE_MAP can now be up to date
3dc9df59  watch setup entry, base-URL resolution, and the tests that could not fail
0d132fc8  the core-context payload, the cleartext guard's test, and two gate scripts
760f004c  watch correctness — atomic writes, duplicate suppression, typed failures
59eec3a2  one owner for the memory push, and the initial sync that was missing
c26be0c8  provider URLs and auth, and the image cache off the lock
2eb27173  bound the wire reads, redact the crash trace, close the release gate
d5575b1a  the ack says whether the phone served the request, not just which one
1c7918a8  keep the pairing ack listener and the wear serializers in R8
```

No force-push, no history rewrite, no tag: `AGENTS.md` rule 5 forbids disabling a guard to pass a gate,
and nothing above does. `main` is 0 behind / 102 ahead of `upstream/master`, tree clean.

### What "done" does and does not mean here

Fixed and proved: 14 of the audit's findings, including the one with real user impact (the pairing ack
that reported success for every refusal). Two findings refuted with evidence, one more refuted by the
test suite after I had acted on it — and that revert is recorded in §S2.7 rather than dropped.

Not fixed, each with its reason in §S2.5: the sandbox's security model, the plaintext-secret
migrations, the destructive migrations, the exported listener's permission, the phone's R8 build, and
six HIGH behaviour items whose fixes are design trade-offs rather than bug fixes. Those are listed with
their `file:line` so the next session starts from the evidence, not from this summary.
