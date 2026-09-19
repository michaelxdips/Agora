#!/usr/bin/env bash
# verify_release_provenance.sh — M1: make the release claims structural instead of asserted.
#
# Every published release makes four claims. Each one has been wrong at least once in this fork's
# history, and each is checkable from outside the repo:
#
#   1. the two APKs are signed by the SAME certificate (the phone and watch must share an identity, or
#      the Data Layer refuses to pair them and a watch cannot be updated by the phone's release);
#   2. the assets match the published SHA256SUMS (a partial upload is a download that fails to install);
#   3. the tag points at a commit that CI actually built (a tag on an unbuilt commit is a release nobody
#      verified);
#   4. both test suites ran green on that commit (the fork's own rule: no proof = not done).
#
# Usage:  bash scripts/verify_release_provenance.sh <tag> [repo]
#         bash scripts/verify_release_provenance.sh v3.0.3
# Exit 0 = all four claims hold. Exit 1 = at least one does not, with the reason printed.
#
# Requires: gh (authenticated), unzip, sha256sum. `apksigner` is taken from the Android SDK build-tools
# when present; without it claim 1 is reported as SKIPPED rather than PASSED — an unverifiable claim is
# not a satisfied one.
set -uo pipefail

TAG="${1:-}"
REPO="${2:-michaelxdips/Agora}"
if [ -z "$TAG" ]; then
    echo "usage: bash scripts/verify_release_provenance.sh <tag> [owner/repo]" >&2
    exit 2
fi

WORK="$(mktemp -d)"
# HERMES: `gh` is a native Windows binary, so it cannot write to an MSYS path like `/tmp/…` — the
# download silently produced an empty directory and the script reported "no assets". The path is
# translated to its native form when the platform offers one, so the script works from git-bash.
if command -v cygpath >/dev/null 2>&1; then
    WORK="$(cygpath -w "$WORK")"
fi
trap 'rm -rf "$WORK"' EXIT
fail=0
note() { printf '%s\n' "$*"; }

note "== release provenance: $TAG in $REPO =="

# ── claim 3 first: the tag must point at a commit CI built ────────────────────────────────
TAG_SHA="$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq '.object.sha' 2>/dev/null)"
if [ -z "$TAG_SHA" ]; then
    note "FAIL  claim 3: tag '$TAG' does not exist in $REPO"
    exit 1
fi
# An annotated tag points at a tag object, which points at the commit. Dereference when needed.
TAG_TYPE="$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq '.object.type' 2>/dev/null)"
if [ "$TAG_TYPE" = "tag" ]; then
    TAG_SHA="$(gh api "repos/$REPO/git/tags/$TAG_SHA" --jq '.object.sha' 2>/dev/null)"
fi
note "tag $TAG -> commit $TAG_SHA"

CI_STATE="$(gh api "repos/$REPO/commits/$TAG_SHA/check-runs" \
    --jq '[.check_runs[] | select(.conclusion != null) | "\(.name)=\(.conclusion)"] | join(" ")' 2>/dev/null)"
if [ -z "$CI_STATE" ]; then
    note "FAIL  claim 3: no completed check runs for $TAG_SHA — the tagged commit was never built"
    fail=1
else
    note "checks on the tagged commit: $CI_STATE"
    if printf '%s' "$CI_STATE" | grep -qE '=failure|=(cancelled|timed_out|action_required)'; then
        note "FAIL  claim 3: a check on the tagged commit did not succeed"
        fail=1
    else
        note "ok    claim 3: the tag's commit has only successful checks"
    fi
fi

# ── claims 1 and 2: the published assets ──────────────────────────────────────────────────
gh release download "$TAG" -R "$REPO" -D "$WORK" >/dev/null 2>&1
if [ -z "$(ls -A "$WORK" 2>/dev/null)" ]; then
    note "FAIL  claims 1/2: no assets could be downloaded for $TAG"
    exit 1
fi
note "downloaded: $(cd "$WORK" && ls | tr '\n' ' ')"

if [ -f "$WORK/SHA256SUMS" ]; then
    if (cd "$WORK" && sha256sum -c SHA256SUMS >/dev/null 2>&1); then
        note "ok    claim 2: every asset matches SHA256SUMS"
    else
        note "FAIL  claim 2: an asset does not match SHA256SUMS"
        (cd "$WORK" && sha256sum -c SHA256SUMS 2>&1 | grep -v ': OK$' | head -5)
        fail=1
    fi
else
    note "FAIL  claim 2: no SHA256SUMS published with $TAG"
    fail=1
fi

# apksigner from the SDK, if this machine has one. On Windows the SDK ships `apksigner.bat` and no
# extensionless binary, so both names are tried — the first version looked only for the POSIX name and
# reported SKIP on this machine, which would have been a silent hole in the gate.
APKSIGNER=""
for candidate in "${ANDROID_HOME:-}/build-tools"/*/apksigner "${ANDROID_HOME:-}/build-tools"/*/apksigner.bat \
                 "${LOCALAPPDATA:-}/Android/Sdk/build-tools"/*/apksigner \
                 "${LOCALAPPDATA:-}/Android/Sdk/build-tools"/*/apksigner.bat; do
    [ -f "$candidate" ] && APKSIGNER="$candidate"
done
APKS="$(find "$WORK" -name '*.apk' | sort)"
APK_COUNT="$(printf '%s\n' "$APKS" | grep -c . || true)"
if [ "$APK_COUNT" -lt 2 ]; then
    note "FAIL  claim 1: expected both APKs (phone + wear), found $APK_COUNT"
    fail=1
elif [ -z "$APKSIGNER" ]; then
    note "SKIP  claim 1: apksigner not found (set ANDROID_HOME) — the certificate claim is NOT verified"
    fail=1
else
    DIGESTS=""
    while read -r apk; do
        [ -z "$apk" ] && continue
        digest="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null |
            grep -i 'certificate SHA-256 digest' | head -1 | awk '{print $NF}')"
        note "  $(basename "$apk"): ${digest:-<no signature found>}"
        DIGESTS="$DIGESTS $digest"
    done <<< "$APKS"
    UNIQUE="$(printf '%s' "$DIGESTS" | tr ' ' '\n' | grep -v '^$' | sort -u | wc -l)"
    if [ -z "$(printf '%s' "$DIGESTS" | tr -d ' ')" ]; then
        note "FAIL  claim 1: no APK carries a signature"
        fail=1
    elif [ "$UNIQUE" -ne 1 ]; then
        note "FAIL  claim 1: the APKs are signed by $UNIQUE different certificates"
        fail=1
    else
        note "ok    claim 1: both APKs share one signing certificate"
    fi
fi

# ── claim 4: the suites ran on the tagged commit ──────────────────────────────────────────
# The evidence is a CI check named for the test job; without it there is no proof the suites passed
# on this commit (and a local run proves nothing about the artifact that was published).
if printf '%s' "$CI_STATE" | grep -qiE 'test|unit'; then
    note "ok    claim 4: a test job ran on the tagged commit"
else
    note "FAIL  claim 4: no test-job check on the tagged commit — the suites were not proved to pass"
    fail=1
fi

if [ "$fail" -eq 0 ]; then
    note "== PASS: all four release claims hold for $TAG =="
else
    note "== FAIL: at least one release claim does not hold for $TAG =="
fi
exit "$fail"
