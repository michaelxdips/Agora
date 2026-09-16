# CLEANUP_SCAN.md — Hermes pre-push cleanup, §1 full scan

**Generated:** 2026-09-16 (local, Asia/Jakarta)
**Repo:** `C:\Users\Michael\Documents\Chatapp\Agora`
**HEAD at scan:** `44d07698` — `hermes: stop the autopilot database from being able to erase its own undo history`
**Branch:** `main` (in sync with `origin/main` — 0 unpushed commits)
**Mandate:** `MEGA_PROMPT_HERMES_CLEANUP_WEAR.md` §1. **No file was moved or deleted to produce this document.**

---

## 1. INVENTORY

Commands run (full output, not sampled):

```
git ls-files                                   > tracked.txt      # 1493 lines
git status --porcelain                         > status.txt       #    4 lines
git ls-files --others --ignored --exclude-standard > ignored.txt  # 59744 lines
git ls-files --others --exclude-standard       > untracked.txt    #   11 lines
```

### 1.1 Tracked — 1493 files, by top-level directory

| Dir | Files | Dir | Files |
|---|---:|---|---:|
| `app` | 1267 | `assets` | 4 |
| `docs` | 64 | `personas` | 3 |
| `wear` | 27 | `gradle` | 3 |
| `evidence` | 27 | `.github` | 3 |
| `development` | 19 | `scripts` | 7 |
| `fastlane` | 18 | `build-logic` | 8 |
| `server` | 10 | `thirdparty` | 6 |
| root `*.md` | 16 | misc (`LICENSE`, `gradlew`, `mkdocs.yml`, …) | 8 |

**Tracked test code:** 412 files under `app/src/test/**`, `app/src/androidTest/**`, `wear/src/test/**` (§0 protected).
**Tracked Hermes-only production:** 72 files under `autopilot/` + `wear/`.

### 1.2 Untracked, not ignored — 11 paths (the entire dirty set)

```
V2_BACKLOG.md
audit-evidence/gate0/assemble_debug.log
audit-evidence/gate0/assemble_release.log
audit-evidence/gate0/guard.log
audit-evidence/gate0/lint_vital.log
audit-evidence/gate0/sync_dryrun.log
audit-evidence/gate0/unit_tests.log
audit-evidence/t3/lint_all.log
audit-evidence/tools/wear_driver.py
scripts/audit_gate0.sh
scripts/audit_test_counts.py
```

No modified tracked files. The two sibling agent windows that were mid-edit at 21:07 have since
committed (`9085772a` … `44d07698`, 7 commits) and their trees are clean.

### 1.3 Ignored — 59744 paths, by top-level directory

| Path | Count | Path | Count |
|---|---:|---|---:|
| `app/build/` | 56979 | `build-logic/build/` | 131 |
| `wear/build/` | 1711 | `.gradle/` | 17 |
| `app/.cxx/` | 898 | `local.properties` | 1 |
| | | `app/release/` (dir) | 1 |

Every ignored path is a build output, a Gradle cache, or the local secret file. Nothing ignored
is unaccounted for.

---

## 2. CLASSIFICATION TABLE

Legend: **P** production · **T** test-code · **A** test-artifact · **E** evidence · **J** junk ·
**S** secret-suspect · **U** unknown.

| Path / glob | Class | Verdict |
|---|---|---|
| `AGENTS.md`, `ROADMAP.md`, `STATUS.md`, `UPSTREAM_SYNC.md`, `UPSTREAM_TOUCHPOINTS.md`, `NOTICE.md`, `GAP_ANALYSIS.md`, `AUDIT_REPORT.md`, `AUDIT_PLAN.md`, `CODE_MAP.md`, `ARCHITECTURE.md`, `HANDOVER.md`, `PRIVACY.md`, `README.md`, `LICENSE`, `mkdocs.yml` | P (governance/upstream) | §0 protected — **keep tracked** |
| `MEGA_PROMPT_HERMES_CLEANUP_WEAR.md`, `MEGA_PROMPT_HERMES_X_v2.md` | P (governance) | §0 protected (`*.md` at repo root is a declared Hermes-only path) — **keep tracked** |
| `app/src/test/**`, `app/src/androidTest/**`, `wear/src/test/**`, any `*Test.kt` (412 files) | T | §0 protected — **keep tracked** |
| `app/src/main/java/com/newoether/agora/autopilot/**`, `wear/src/**` (72 files) | P | Hermes-only feature code — **keep tracked** |
| `scripts/touchpoint_guard.sh`, `scripts/upstream_sync.sh`, `scripts/persona_update.sh`, `scripts/round_icon.py`, `scripts/native-tests/**` | P | §0 protected — **keep tracked** |
| `.github/workflows/build.yml`, `mkdocs.yml`, `upstream-sync.yml` | P | §0 protected — **keep tracked** |
| `personas/caveman/SKILL.md`, `personas/ponytail/SKILL.md`, `personas/upstream.lock` | P | §0 protected (vendored sources + lock) — **keep tracked** |
| `thirdparty/llama.cpp`, `thirdparty/proot` (submodules), `thirdparty/talloc` | P (upstream) | §0 protected — **keep** |
| `V2_BACKLOG.md` | P (governance) | §0 names it explicitly — **commit it** |
| `scripts/audit_gate0.sh`, `scripts/audit_test_counts.py` | P (governance) | §0 protects `scripts/**` — **commit them** |
| `audit-evidence/**` (8 files, 149 K, untracked) | E | §3.2 — **archive out of the repo** |
| `evidence/**` (27 files, 2.7 M, **tracked**) | E | §0 silent, §3.2 silent; `STATUS.md` cites these PNGs as phase-exit proof — **keep tracked**, see Open Q6 |
| `app/build/**` (56979 files, 1.9 G) | A | Gradle output — **delete** |
| `app/.cxx/**` (898 files, 202 M) | A | NDK/CMake output — **delete** |
| `wear/build/**` (1711 files, 182 M) | A | Gradle output — **delete** |
| `build-logic/build/**` (131 files) | A | Gradle output — **delete** |
| `.gradle/**` (17 entries, 113 M) | A | Gradle project cache — **delete** |
| `.kotlin/sessions/**` (0 B) | A | Kotlin session dir — **delete** |
| `app/release/*.apk` on disk | A | gitignored, regenerable — **delete** |
| `app/build/outputs/apk/**/*.apk` (5 APKs on disk) | A | gitignored, regenerable — **delete** |
| `app/build/test-results/**`, `app/build/reports/lint-results*` | A | regenerable — **delete with the build dirs** |
| `local.properties` (201 B, ignored) | S (local) | Holds `sdk.dir` + dev keystore passwords. **Keep on disk, never tracked** — verified `git check-ignore` hit `.gitignore:10` |
| `_tools/hermes-release.jks` (outside repo) | S (local) | Not in the repo tree at all — no action |
| `_baseline_wear.log` (repo root) | J | Ignored via `_*.log`; scratch log — **delete** |
| `*.iml`, `.idea/`, `.DS_Store`, `nohup.out`, `*.orig`, `*.rej`, `tmp*` | J | **None present** (`find` returned empty) |
| `captures/` | A | **Does not exist** |
| UNKNOWN | — | **None.** Every path above resolved to a class; nothing was deleted to resolve an unknown. |

---

## 3. SIZE REPORT

### 3.1 Working tree — 3.2 G total

| Path | Size | Class |
|---|---:|---|
| `app/build/` | 1.9 G | A — delete |
| `app/.cxx/` | 202 M | A — delete |
| `wear/build/` | 182 M | A — delete |
| `thirdparty/llama.cpp` (submodule) | 151 M | P — keep |
| `.gradle/` | 113 M | A — delete |
| `evidence/` | 2.7 M | E — keep |
| `audit-evidence/` | 149 K | E — archive |
| **Reclaimable** | **≈2.4 G** | |

### 3.2 `.git` — 584 M

| Component | Size | Note |
|---|---:|---|
| `.git/modules/thirdparty` | 440 M | submodule history; **not pushed** — a clone re-fetches it from upstream |
| `.git/objects` | 145 M | 2 packs (142.8 MiB), 35718 objects in-pack |

### 3.3 Largest blobs in history — 11311 blobs, 512.5 MB of content

```
39,494,077  wear/build/outputs/apk/debug/wear-debug.apk          history-only
20,235,120  app/src/main/res/font/mioutfit_variable.ttf         ** TRACKED AT HEAD (live font) **
19,047,704  wear/build/intermediates/dex/debug/mergeExtDexDebug/classes.dex    history-only
15,515,329  app/release/app-release.apk                          history-only
15,235,462  app/release/app-release.apk   ×3                     history-only
15,219,078  app/release/app-release.apk                          history-only
14,348,336  wear/build/intermediates/dex/debug/mergeExtDexDebug/classes2.dex   history-only
 8,134,160  app/src/main/res/font/mioutfit_extralight.ttf       history-only (upstream 91530c8c)
 8,086,468  app/src/main/res/font/mioutfit_light.ttf            history-only (upstream 91530c8c)
 8,032,644  app/src/main/res/font/mioutfit_regular.ttf          history-only (upstream 91530c8c)
 8,012,600  app/src/main/res/font/mioutfit_medium.ttf           history-only (upstream 91530c8c)
 7,920,548  app/src/main/res/font/mioutfit_bold.ttf             history-only (upstream 91530c8c)
 4,043,766  app/src/fdroid/assets/alpine-minirootfs.tar.gz      history-only
```

**Blobs matching `*.apk` / `/build/` / `*.dex`: 689 blobs, 157.8 MB** — 30.8 % of all history bytes.

**The five static `mioutfit_*.ttf` faces and the one `mioutfit_variable.ttf` must not be lumped
together.** `mioutfit_variable.ttf` **is** tracked at HEAD (`git rev-parse HEAD:…` →
`33765fb589f9af6b18ed13d78f0ca43a755daca2`, 20,235,120 B) — it is the live font. The five static
faces (40,278,708 B = 38.4 MB) are history-only: upstream's `91530c8c "refactor: consolidate MiOutfit
font resources"` deleted them in favour of the variable font, and that commit is an ancestor of
`44d07698`. So of the 58.2 MB of `res/font` blobs in history, **19.7 MB is live product content**
(variable font + 4 JetBrains Mono) and **38.4 MB is history-only dead weight left by upstream**.

**Of the 689 build-output blobs, none is tracked at HEAD.** Measured:

```
git ls-files | grep -E '\.apk$|/build/|\.dex$'                                  → EMPTY
git ls-files | grep -cE 'build/|app/release|\.apk$|\.cxx|\.gradle/|captures/'   → 0
git ls-files app/src/main/jniLibs app/src/fdroid/jniLibs                        → 0
git ls-files app/src/fdroid/assets/alpine-minirootfs.tar.gz                     → 0
```

They are **history-only**, introduced by upstream commits (`28b474dc`, `a32289aa`, …) plus the
Phase-7 `wear/build` commit that `2f8d080e hermes: stop tracking module build output` removed.
The mandate authorises a history rewrite **only** for secrets (§2). See Open Q4.

---

## 4. GITIGNORE AUDIT

### 4.1 Diff against upstream

`diff <(git show upstream/master:.gitignore) .gitignore` → exactly one added hunk, lines 40–48,
the registered `HERMES INTEGRATION POINT` block:

```
# HERMES INTEGRATION POINT
# Module build output. …
build/
# Work logs at the repo root …
_*.log
```

Upstream `.gitignore` line 39 lists `AGENTS.md`; the file is **tracked**, so the rule is inert and
§0's protection of `AGENTS.md` is unaffected. No edit needed.

### 4.2 `git check-ignore -v --no-index` proofs

| Probe | Result | Source |
|---|---|---|
| `local.properties` | **ignored** | `.gitignore:10:local.properties` |
| `x.jks` | **ignored** | `.git/info/exclude:9:*.jks` |
| `x.keystore` | **ignored** | `.git/info/exclude:10:*.keystore` |
| `.gradle/x` | **ignored** | `.gitignore:2:.gradle` |
| `build/x` | **ignored** | `.gitignore:44:build/` |
| `app/build/x` | **ignored** | `app/.gitignore:1:/build` |
| `wear/build/x` | **ignored** | `.gitignore:44:build/` |
| `build-logic/build/x` | **ignored** | `build-logic/.gitignore:1:/build/` |
| `app/release/x` | **ignored** | `.gitignore:17:app/release/` |
| `.cxx/x` | **ignored** | `.gitignore:9:.cxx` |
| `.kotlin/x` | **ignored** | `.gitignore:13:.kotlin/` |
| `_x.log` | **ignored** | `.gitignore:48:_*.log` |
| **`x.log`** | **NOT IGNORED** | ← **the only gap** |

### 4.3 Gaps

1. **`*.log` is not covered.** The repo's own ignore rules only catch `_*.log`, `build_*.log`,
   `logs/`. A log written under any other name at the repo root, or under any subdirectory, is
   committable. Mandate §1.4 requires this to be covered by `.gitignore` **or** `.git/info/exclude`.
2. Nothing else. `local.properties`, `*.jks`, `*.keystore`, `.gradle/`, `build/`, `app/release/`,
   `.cxx`, `.kotlin` are all covered — no `.gitignore` edit is required by the mandate.

**Planned fix (§3.4, local-only):** append `*.log` to `.git/info/exclude`, then re-run the three
probes above. This keeps the upstream `.gitignore` untouched (its `max=9` budget stays consumed by
the registered `_*.log` hunk) and therefore needs no `UPSTREAM_TOUCHPOINTS.md` entry.

---

## 5. SECRETS SWEEP — summary (full detail lands in `CLEANUP_REPORT.md` §2)

| Check | Command | Result |
|---|---|---|
| Tracked secret files | `git ls-files \| grep -E "local.properties\|\.jks$\|\.keystore$"` | **EMPTY — PASS** |
| Never-tracked secret files | `git log --all --diff-filter=A --name-only \| grep -E 'local\.properties\|\.jks$\|\.keystore$\|signing\.properties'` | **EMPTY — PASS** |
| Tracked-tree key regex | `git grep -n -I -E 'sk-…\|AIza…\|gsk_…\|sk-ant-…\|hf_…\|BEGIN …PRIVATE KEY'` | 1 hit: `DiagnosticRedactorTest.kt:93` — a literal test fixture (`partial-secret`), not a key |
| Full-history blob scan | 35421 objects via `git cat-file --batch`, 8 key patterns | No literal API key. `api_key` hits are identifiers; `storePassword`/`keyPassword` hits are property **reads** in `app/build.gradle.kts`, `wear/build.gradle.kts`, `.github/workflows/build.yml` (CI uses `secrets.*`) |
| Stash | `git stash list` | **empty** |
| Local dev password in history | `git log --all -S '<redacted-local-dev-password>' --oneline` (pattern quoted from `local.properties`, not reproduced here) | **EMPTY — PASS** |
| New commits since the first scan | `git log -p 8d8b5b5a..HEAD \| grep -E 'sk-…\|storePassword=\|keyPassword='` | **EMPTY — PASS** |

**§2 outcome: zero findings → proceed to §3.** No blocking human moment.

---

## 6. DELETION MANIFEST (planned — not yet executed)

| # | Path | Why it is safe |
|---|---|---|
| 1 | `app/build/` | gitignored (`app/.gitignore:1`), Gradle regenerates |
| 2 | `wear/build/` | gitignored (`.gitignore:44`), Gradle regenerates |
| 3 | `build-logic/build/` | gitignored (`build-logic/.gitignore:1`) |
| 4 | `build/` | gitignored (`.gitignore:44`) — absent today |
| 5 | `.gradle/` | gitignored (`.gitignore:2`) |
| 6 | `.kotlin/` | gitignored (`.gitignore:13`), 0 B of content |
| 7 | `app/.cxx/` | gitignored (`.gitignore:9`) |
| 8 | `app/release/` (on-disk APK) | gitignored (`.gitignore:17`) |
| 9 | `_baseline_wear.log` | gitignored (`.gitignore:48`), scratch |
| — | `audit-evidence/` | **not deleted** — moved to `../hermes-evidence-archive/audit-evidence/` |

**Safety proof to run after deletion:** `git status --porcelain` must be **byte-identical** to the
pre-deletion run (4 lines). Any difference means something non-ignored was removed.

**Not deleted, explicitly:** every §0-protected path, all 412 test-code files, `evidence/**`,
`AUDIT_REPORT.md`, all tags, all production code, all upstream files, `LICENSE`.

---

## 7. OPEN QUESTIONS FOR THE OWNER

1. **586 M `.git` carrying 157.8 MB of APK/dex blobs** (history-only, from upstream + the one
   Phase-7 `wear/build` commit). The mandate authorises no size rewrite. Rewriting would
   invalidate all 30 tags and break the upstream-sync story. **Recommendation: report, do not
   rewrite.** Flagged for a human decision.
2. **`app/release/*.apk` blobs come from upstream history** — a second reason not to rewrite.
3. **`evidence/` (tracked, 27 files, 2.7 M)** is named neither in §0 nor in §3.2, but `STATUS.md`
   cites it as phase-exit proof. **Recommendation: keep tracked.** If the owner wants a lean repo,
   it is the one defensible candidate for archival — but it is not mandated.
4. **`docs-hermes/`** named in §0 does not exist in this repo. Harmless.
5. **`origin/HEAD` → `master`**, while the mandate pushes `main`. The default branch should be set
   to `main` explicitly (§5) or the divergence documented.
6. **`gh` is not installed.** Install in Phase 0 or use the REST fallback (§5.1b).

---

## 8. VERIFICATION COMMANDS (this scan)

```bash
cd /c/Users/Michael/Documents/Chatapp/Agora
git rev-parse HEAD                                   # 44d07698…
git status --porcelain | wc -l                       # 4
git ls-files | wc -l                                 # 1493
git ls-files --others --exclude-standard | wc -l     # 11
git ls-files --others --ignored --exclude-standard | wc -l   # 59744
git check-ignore -v --no-index x.log                 # NOT ignored  ← gap §4.3
git ls-files | grep -E "local.properties|\.jks$|\.keystore$" # EMPTY
```
