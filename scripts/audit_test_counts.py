"""Count tests from the JUnit XML the gate produced, and check them against STATUS.md.

HERMES INTEGRATION POINT: this used to exit 0 whenever *any* test ran, so a suite of 10 tests with
10 failures and 10 errors reported success and could be quoted as "the gate passed". A suite with
tests but failures/errors is not a pass, so the exit code now reflects the failures the script
already counted.

HERMES INTEGRATION POINT (Session 5 audit): the CI step that runs this is named "Test counts match
the documented numbers", and it matched nothing — the script had no reference to the counts in
STATUS.md, so a suite shrinking by 99% passed the step. The documented numbers are now parsed from
the STATUS.md table and compared. A missing or unparseable table is a FAIL, not a skip: the step's
whole point is that the documentation and the suites agree.

Usage:
    python scripts/audit_test_counts.py            # compare against STATUS.md
    python scripts/audit_test_counts.py --no-doc-check   # only count (for a quick local look)
"""
import glob
import re
import sys

CHECK_DOC = "--no-doc-check" not in sys.argv
STATUS = "STATUS.md"

counts = {}
total = 0
failures = 0
errors = 0
for label, pat in [
    ("fdroid", "app/build/test-results/testFdroidDebugUnitTest/*.xml"),
    ("play", "app/build/test-results/testPlayDebugUnitTest/*.xml"),
    ("wear", "wear/build/test-results/testDebugUnitTest/*.xml"),
]:
    t = f = e = s = 0
    for x in glob.glob(pat):
        head = open(x, encoding="utf-8", errors="replace").read(4000)
        m = re.search(
            r'tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"', head
        )
        if m:
            t += int(m.group(1))
            s += int(m.group(2))
            f += int(m.group(3))
            e += int(m.group(4))
    counts[label] = t
    total += t
    failures += f
    errors += e
    print(f"  {label:7s} {t:5d} tests, {f} failures, {e} errors, {s} skipped")
print(f"  TOTAL   {total:5d} tests, {failures} failures, {errors} errors")

if failures != 0 or errors != 0 or total == 0:
    sys.exit(1)

if not CHECK_DOC:
    sys.exit(0)

# ── the documented numbers ────────────────────────────────────────────────────────────────
# STATUS.md carries one row per suite, e.g.
#   | App unit tests | **2665 tests, 0 failures, 0 errors, 3 skipped** (407 XML files) | ... |
#   | Wear unit tests | **168 tests, 0 failures, 0 errors** (16 XML files) | ... |
#   | Play flavor tests | 2648 tests, 0 failures, 0 errors, 3 skipped | ... |
# The first number in each row is the count this script compares against.
try:
    status_text = open(STATUS, encoding="utf-8", errors="replace").read()
except OSError:
    print(f"FAIL  {STATUS} is not readable — cannot check the documented counts")
    sys.exit(1)

patterns = {
    "fdroid": r"\|\s*App unit tests\s*\|\s*\**\s*(\d+)\s*tests",
    "play": r"\|\s*Play flavor tests\s*\|\s*\**\s*(\d+)\s*tests",
    "wear": r"\|\s*Wear unit tests\s*\|\s*\**\s*(\d+)\s*tests",
}
documented = {}
for label, pattern in patterns.items():
    m = re.search(pattern, status_text)
    if not m:
        print(f"FAIL  no documented {label} count found in {STATUS} (row renamed or removed?)")
        sys.exit(1)
    documented[label] = int(m.group(1))

mismatch = False
for label in ("fdroid", "play", "wear"):
    if counts[label] != documented[label]:
        print(
            f"FAIL  {label}: {counts[label]} tests ran but {STATUS} documents {documented[label]}"
        )
        mismatch = True
if mismatch:
    print("      update the STATUS.md table (or the suites) so they agree")
    sys.exit(1)

print(
    "ok    documented counts match: "
    + ", ".join(f"{label} {documented[label]}" for label in ("fdroid", "play", "wear"))
)
sys.exit(0)
