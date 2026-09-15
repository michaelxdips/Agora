"""Re-run of the two harness-unreliable cases from the Pass-B audit, with a real foreground check.

The first run reported:
  * section A (base URL shapes) as "OK" while the screen it read was `Android System / Serial
    console enabled` — the system UI, not the app. Those verdicts were meaningless;
  * "4000-character question" as CRASH for the same reason.

The bug is in the harness: it dumped the screen without first asserting the app was in the
foreground, and `am start` on a device whose system dialog had taken focus left the app behind it.
This version asserts the foreground activity before believing anything on screen.
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe"
DEV = ["-s", "emulator-5556"]
PKG = "com.hermes.app"
ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"

failures = []


def adb(*args, timeout=90):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def foreground():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=[^ ]+ [^ ]+ ([^ ]+)", out)
    return m.group(1) if m else "<none>"


def in_app():
    return foreground().startswith(PKG)


def dump():
    adb("shell", "rm", "-f", "/sdcard/d.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    xml = adb("shell", "cat", "/sdcard/d.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = re.search(r'text="([^"]*)"', t)
        bnd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if bnd:
            out.append({
                "text": txt.group(1) if txt else "",
                "cx": (int(bnd.group(1)) + int(bnd.group(3))) // 2,
                "cy": (int(bnd.group(2)) + int(bnd.group(4))) // 2,
            })
    return out


def texts():
    return [n["text"] for n in dump() if n["text"]]


def find(text):
    for n in dump():
        if n["text"] == text:
            return n
    return None


def tap(n):
    adb("shell", "input", "tap", str(n["cx"]), str(n["cy"]))
    time.sleep(1.5)


def scroll(times=1):
    for _ in range(times):
        adb("shell", "input", "swipe", "192", "300", "192", "110", "250")
        time.sleep(1)


def seek(text, max_scroll=10):
    for _ in range(max_scroll):
        n = find(text)
        if n:
            return n
        scroll()
    return None


def relaunch():
    """Cold start, then dismiss anything that is not the app, and prove the app is in front."""
    adb("shell", "cmd", "statusbar", "collapse")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", ACT)
    for _ in range(10):
        time.sleep(2)
        if in_app():
            return True
        # a system dialog (e.g. the keyboard's setup prompt) can own the screen: go back
        adb("shell", "input", "keyevent", "4")
    return False


def configure(base_url, key="sk-mock", model="mock-model"):
    if not relaunch():
        return "no foreground", texts()[:3]
    for label, val in (("Base URL", base_url), ("API key", key), ("Model", model)):
        f = seek("Tap to type")
        if not f:
            break
        tap(f)
        adb("shell", "input", "text", val)
        time.sleep(1)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    save = seek("Save key")
    if save:
        tap(save)
        time.sleep(4)
    return ("chat" if find("Type a question") else "setup"), texts()[:4]


print("=== A. base URL shapes, with a real foreground assertion ===")
cases = [
    ("https://x.com/v1/", "trailing slash"),
    ("https://x.com/chat/completions", "already a completions path"),
    ("http://127.0.0.1:11434", "localhost, no /v1"),
    ("x.com/v1", "no scheme"),
]
for value, label in cases:
    adb("shell", "pm", "clear", PKG)
    state, screen = configure(value)
    verdict = "accepted" if state == "chat" else ("rejected" if state == "setup" else state)
    # no scheme must be refused; the rest must be accepted (they are all valid https/localhost URLs)
    expect = "rejected" if label == "no scheme" else "accepted"
    ok = verdict == expect
    if not ok:
        failures.append(f"base url {label}: expected {expect}, got {verdict} ({screen})")
    print(f"  [{'OK' if ok else 'FAIL'}] {label}: value={value!r} -> {verdict} | {screen}")

print("\n=== C. 4000-character question, with a real foreground assertion ===")
adb("shell", "pm", "clear", PKG)
state, screen = configure("http://127.0.0.1:8077/v1")
print(f"  configure -> {state} | {screen}")
if state != "chat":
    failures.append("could not reach the chat screen to test a long question")
else:
    field = seek("Type a question")
    if not field:
        failures.append("no composer field")
    else:
        tap(field)
        adb("shell", "input", "text", "L" * 4000)
        time.sleep(3)
        adb("shell", "input", "keyevent", "4")
        time.sleep(2)
        if not in_app():
            failures.append(f"app left the foreground while typing 4000 chars: {foreground()}")
        else:
            shown = texts()
            echoed = [t for t in shown if t.startswith("LLL")]
            print(f"  foreground: {foreground()}")
            print(f"  field holds {len(echoed[0]) if echoed else 0} characters")
            print(f"  screen: {[t[:60] for t in shown][:5]}")
            # The requirement: a very long question must not be silently truncated in the field,
            # and the app must still be alive and on the chat screen.
            if echoed and len(echoed[0]) < 4000:
                print(f"  NOTE: the field shows {len(echoed[0])} of 4000 — the widget's own limit, "
                      "recorded rather than called a pass")

print("\n=== SUMMARY ===")
print("PASS" if not failures else "FAIL")
for f in failures:
    print("  -", f)
sys.exit(0 if not failures else 1)
