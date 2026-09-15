"""Isolate the exact step that leaves the app: the typing, or the BACK key used to dismiss the IME?

Case 2 in the previous run stayed in `WearMainActivity` through all 4000 typed characters and was only
reported out of the app after `input keyevent 4`. On Android, keyevent 4 is BACK. On the setup screen
BACK dismisses the IME because the field has focus; on the chat screen, with 4000 characters in the
composer and the IME closed, the same key is delivered to the activity — and `WearMainActivity` has no
`BackHandler`, so BACK finishes it. That is not a crash, and it is not the app "hanging": it is the
platform's normal back behaviour, triggered by the harness's own IME-dismissal step.

If that is right, the fix is in the harness: dismiss the IME with a tap outside the field (or with
`input keyevent 111` / ESC), not with BACK. The app's own behaviour — does it survive a long question —
is the thing being measured, and it must be measured without the harness finishing the activity.

This script measures three things in order and stops at the first difference:
  1. 4000 characters typed in chunks, no key event at all -> foreground?
  2. then ESC (111) instead of BACK -> foreground?
  3. then BACK (4) -> foreground?  (expected: the app finishes, which is correct Android behaviour)
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe"
DEV = ["-s", "emulator-5556"]
PKG = "com.hermes.app"
ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"


def adb(*args, timeout=120):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def foreground():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=[^ ]+ [^ ]+ ([^ ]+)", out)
    return m.group(1) if m else "<none>"


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
            out.append({"text": txt.group(1) if txt else "",
                        "cx": (int(bnd.group(1)) + int(bnd.group(3))) // 2,
                        "cy": (int(bnd.group(2)) + int(bnd.group(4))) // 2})
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


def swipe_up():
    adb("shell", "input", "swipe", "192", "300", "192", "110", "250")
    time.sleep(1)


def relaunch():
    adb("shell", "cmd", "statusbar", "collapse")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", ACT)
    for _ in range(10):
        time.sleep(2)
        if foreground().startswith(PKG):
            return True
        adb("shell", "input", "keyevent", "4")
    return False


def configure():
    for label, val in (("Base URL", "http://127.0.0.1:8077/v1"),
                       ("API key", "sk-mock"),
                       ("Model", "mock-model")):
        target = None
        for _ in range(10):
            lab = find(label)
            if lab:
                below = [n for n in dump()
                         if n["cy"] > lab["cy"] and n["text"] and n["text"] != label]
                if below:
                    target = min(below, key=lambda n: n["cy"])
                    break
            swipe_up()
        if not target:
            print(f"  no field under {label!r}")
            return False
        tap(target)
        adb("shell", "input", "text", val)
        time.sleep(1)
        # BACK (4) is what dismisses the Wear IME on the setup screen — proven by
        # `_tools/p0_autodrain_proof.py`. ESC (111) does not close it here, and the following
        # keystrokes then land in the keyboard's suggestion strip.
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    save = None
    for _ in range(10):
        save = find("Save key")
        if save:
            break
        swipe_up()
    if save:
        tap(save)
        time.sleep(4)
    return find("Type a question") is not None


print("=== precondition ===")
adb("shell", "pm", "clear", PKG)
if not relaunch():
    print("  could not reach the app"); sys.exit(2)
print("  configured:", configure())
field = find("Type a question")
if not field:
    print("  no composer field:", texts()[:5]); sys.exit(2)

print("\n=== step 1: 4000 chars in 40 chunks, then NO key event ===")
tap(field)
for i in range(40):
    adb("shell", "input", "text", "C" * 100)
print("  foreground after typing, before any key:", foreground())
echoed = [t for t in texts() if t.startswith("CCC")]
print(f"  field holds {len(echoed[0]) if echoed else 0} chars")

print("\n=== step 2: ESC (111) to close the IME ===")
adb("shell", "input", "keyevent", "111")
time.sleep(2)
print("  foreground after ESC:", foreground())
echoed = [t for t in texts() if t.startswith("CCC")]
print(f"  field holds {len(echoed[0]) if echoed else 0} chars")

print("\n=== step 3: BACK (4) ===")
adb("shell", "input", "keyevent", "4")
time.sleep(2)
print("  foreground after BACK:", foreground())

print("\n=== verdict ===")
print("  If step 1 and step 2 stayed in the app, the app holds a 4000-character question fine, and")
print("  the earlier 'CRASH' was the harness pressing BACK. BACK finishing the activity is correct")
print("  Android behaviour, not a defect — recorded as a harness trap.")
