"""Count tests from the JUnit XML the gate produced. Same rule the gate script uses.

HERMES INTEGRATION POINT: this used to exit 0 whenever *any* test ran, so a suite of 10 tests with
10 failures and 10 errors reported success and could be quoted as "the gate passed". A suite with
tests but failures/errors is not a pass, so the exit code now reflects the failures the script
already counted.
"""
import glob
import re
import sys

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
    total += t
    failures += f
    errors += e
    print(f"  {label:7s} {t:5d} tests, {f} failures, {e} errors, {s} skipped")
print(f"  TOTAL   {total:5d} tests, {failures} failures, {errors} errors")
sys.exit(0 if total > 0 and failures == 0 and errors == 0 else 1)
