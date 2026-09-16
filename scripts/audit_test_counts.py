"""Count tests from the JUnit XML the gate produced. Same rule the gate script uses."""
import glob
import re
import sys

total = 0
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
    print(f"  {label:7s} {t:5d} tests, {f} failures, {e} errors, {s} skipped")
print(f"  TOTAL   {total:5d} tests")
sys.exit(0 if total > 0 else 1)
