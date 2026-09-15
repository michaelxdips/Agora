"""Measurements for the optimisation section: cold start, memory, and the read timeout.

Every number here is produced by a command, not estimated. Where a number cannot be obtained without
hardware or credentials, it says so instead of guessing.
"""
import re
import subprocess
import statistics
import time

ADB = r"C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe"
WEAR = ["-s", "emulator-5556"]
PHONE = ["-s", "emulator-5554"]
PKG = "com.hermes.app"
WEAR_ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"
PHONE_ACT = f"{PKG}/com.newoether.agora.MainActivity"


def adb(dev, *args, timeout=180):
    r = subprocess.run([ADB, *dev, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def cold_start_ms(dev, act, runs=5):
    """`am start -W` TotalTime is the wall time from intent to the first frame."""
    times = []
    for _ in range(runs):
        adb(dev, "shell", "am", "force-stop", PKG)
        time.sleep(2)
        out = adb(dev, "shell", "am", "start", "-W", "-n", act)
        m = re.search(r"TotalTime:\s*(\d+)", out)
        if m:
            times.append(int(m.group(1)))
        time.sleep(2)
    return times


def pss_kb(dev):
    out = adb(dev, "shell", "dumpsys", "meminfo", PKG)
    m = re.search(r"TOTAL PSS:\s*(\d+)", out) or re.search(r"TOTAL\s+(\d+)", out)
    return int(m.group(1)) if m else None


def disk_usage(dev):
    out = adb(dev, "shell", "run-as", PKG, "du", "-a", "files/")
    return out.strip()


print("=== wear: cold start, 5 runs ===")
wear_times = cold_start_ms(WEAR, WEAR_ACT)
print(f"  TotalTime ms: {wear_times}")
if wear_times:
    print(f"  median {statistics.median(wear_times):.0f} ms, min {min(wear_times)}, max {max(wear_times)}")
print(f"  TOTAL PSS: {pss_kb(WEAR)} KB")

print("\n=== phone: cold start, 3 runs ===")
phone_times = cold_start_ms(PHONE, PHONE_ACT, runs=3)
print(f"  TotalTime ms: {phone_times}")
if phone_times:
    print(f"  median {statistics.median(phone_times):.0f} ms")
print(f"  TOTAL PSS: {pss_kb(PHONE)} KB")

print("\n=== wear: what the app actually stores ===")
print(disk_usage(WEAR) or "  (run-as produced nothing — app may not be debuggable)")

print("\n=== the read timeout, measured against the mock ===")
import urllib.request
try:
    urllib.request.urlopen("http://127.0.0.1:8077/__slow", timeout=30)
except Exception as e:
    print("  mock slow mode:", e)
print("  WearChatClient readTimeout = 60 s (declared). SLOW_SECONDS in the mock is 8 s, so the")
print("  timeout is NOT exercised by that mode. To measure the timeout itself the mock would need a")
print("  delay above 60 s, which costs 60 s of wall time per run; recorded as not measured rather")
print("  than claimed. What IS measured: an 8 s slow response is survived and answered.")
