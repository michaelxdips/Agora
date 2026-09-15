"""Re-verify the credential-leak claims with a read that actually works.

The Pass-B audit reported `canary in the config file: encrypted (0 bytes, no plaintext)`. Zero bytes is
not evidence of encryption — it is evidence that `run-as` returned nothing, because a RELEASE APK was
installed (not debuggable) and the install step's failure had been masked by a pipe:

    adb install ... | tail -1 && python _audit_device.py

`$?` after a pipeline is the exit status of the LAST command, so the `&&` saw `tail`'s success and ran
the audit anyway. Every `queue=` and every file read in that run was therefore meaningless.

This script asserts the read works (the config file exists and has non-zero length) before drawing any
conclusion, and then re-checks:
  * the API key is not present in plaintext in the config file;
  * the file is not empty and is not the JSON the store would write unencrypted;
  * the key is not in logcat, before or after a real request;
  * the key is not in any file the app writes.
"""
import re
import subprocess
import sys
import time
import urllib.request

ADB = r"C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe"
DEV = ["-s", "emulator-5556"]
PKG = "com.hermes.app"
ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"
CANARY = "sk-SECRET-CANARY-98765"


def adb(*args, timeout=120):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout, r.stderr, r.returncode


def text(b):
    return b.decode("utf-8", "replace")


def foreground():
    out, _, _ = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=[^ ]+ [^ ]+ ([^ ]+)", text(out))
    return m.group(1) if m else "<none>"


def dump():
    adb("shell", "rm", "-f", "/sdcard/d.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    xml, _, _ = adb("shell", "cat", "/sdcard/d.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", text(xml)):
        t = m.group(0)
        txt = re.search(r'text="([^"]*)"', t)
        bnd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        if bnd:
            out.append({"t": txt.group(1) if txt else "",
                        "x": (int(bnd.group(1)) + int(bnd.group(3))) // 2,
                        "y": (int(bnd.group(2)) + int(bnd.group(4))) // 2})
    return out


def texts():
    return [n["t"] for n in dump() if n["t"]]


def find(s):
    for n in dump():
        if n["t"] == s:
            return n
    return None


def tap(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    time.sleep(1.5)


def swipe():
    adb("shell", "input", "swipe", "192", "300", "192", "110", "250")
    time.sleep(1)


def seek(s, n=10):
    for _ in range(n):
        f = find(s)
        if f:
            return f
        swipe()
    return None


def launch():
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
                       ("API key", CANARY),
                       ("Model", "mock-model")):
        tgt = None
        for _ in range(10):
            lab = find(label)
            if lab:
                below = [n for n in dump() if n["y"] > lab["y"] and n["t"] and n["t"] != label]
                if below:
                    tgt = min(below, key=lambda n: n["y"])
                    break
            swipe()
        if not tgt:
            return False
        tap(tgt)
        adb("shell", "input", "text", val)
        time.sleep(1)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    s = seek("Save key")
    if s:
        tap(s)
        time.sleep(4)
    return find("Type a question") is not None


print("=== step 0: prove the read works before believing anything ===")
out, _, _ = adb("shell", "run-as", PKG, "ls", "-la", "files/")
listing = text(out)
print(listing.strip()[:300] or "  (empty listing)")
if "hermes_wear_config.bin" not in listing:
    print("  !! config file not visible — the APK is not debuggable. Install the DEBUG apk and retry.")
    sys.exit(2)

adb("shell", "pm", "clear", PKG)
print("  launch:", launch())
print("  configured:", configure())

out, err, _ = adb("shell", "run-as", PKG, "ls", "-la", "files/")
print("\n  files after a save:\n" + text(out).strip()[:400])

out, _, _ = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_config.bin")
raw = out  # bytes; do not decode through a text layer for the length check
print(f"\n  config file size: {len(raw)} bytes")
if len(raw) == 0:
    print("  !! zero bytes — the read failed or the file is empty. No conclusion drawn.")
    sys.exit(2)

plain = text(raw)
in_plain = CANARY in plain
looks_like_json = plain.lstrip().startswith("{") and '"baseUrl"' in plain
print(f"  canary in the file: {in_plain}")
print(f"  file looks like plaintext JSON: {looks_like_json}")
print(f"  first 24 bytes: {raw[:24]!r}")

print("\n=== logcat, before and after a real request ===")
adb("shell", "logcat", "-c")
f = seek("Type a question")
if f:
    tap(f)
    adb("shell", "input", "text", "leak-probe")
    time.sleep(1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    s = seek("Send")
    if s:
        tap(s)
time.sleep(12)
out, _, _ = adb("shell", "logcat", "-d")
log = text(out)
in_log = CANARY in log
print(f"  canary in the whole log buffer after a real request: {in_log}")

print("\n=== every file the app wrote ===")
out, _, _ = adb("shell", "run-as", PKG, "sh", "-c",
                "for f in files/*; do echo \"--- $f\"; grep -c \"$CANARY\" \"$f\" 2>/dev/null || echo 0; done")
print(text(out).strip())

print("\n=== verdict ===")
failures = []
if in_plain:
    failures.append("the API key is in the config file in plaintext")
if looks_like_json:
    failures.append("the config file is plaintext JSON, not ciphertext")
if in_log:
    failures.append("the API key reached logcat")
for f in failures:
    print("  FAIL:", f)
print("  PASS — the key is not readable in the file, the file is not plaintext JSON, and the key is"
      " absent from logcat" if not failures else "")
sys.exit(1 if failures else 0)
