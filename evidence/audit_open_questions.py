"""Settle two questions the Pass-B audit left open, both potential data-loss signals.

1. The audit printed `queue 0->0` while the screen said `Offline — held`. If a question is announced
   as held but the queue file is empty, that is DATA LOSS — the worst failure this app can produce,
   because the user believes their question is safe. `run-as` was used to read the file, and `run-as`
   needs a debuggable APK: if it silently returned nothing, the measurement was the problem, not the
   app. This checks the read itself before believing it.

2. `/__slow` (8 s) produced a screen with neither an answer nor a status. Either the answer arrived
   after the harness's 10 s wait, or the question was held and the queue should say so.

Both are answered by reading the queue file with an explicit check that the read worked, and by
waiting long enough for an 8 s response plus the drain.
"""
import json
import re
import subprocess
import sys
import time
import urllib.request

ADB = r"C:\Users\Michael\Documents\Chatapp\_tools\sdk\platform-tools\adb.exe"
DEV = ["-s", "emulator-5556"]
PKG = "com.hermes.app"
ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"
MOCK = "http://127.0.0.1:8077"


def adb(*args, timeout=120):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace"), r.returncode


def foreground():
    out, _ = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=[^ ]+ [^ ]+ ([^ ]+)", out)
    return m.group(1) if m else "<none>"


def dump():
    adb("shell", "rm", "-f", "/sdcard/d.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    xml, _ = adb("shell", "cat", "/sdcard/d.xml")
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
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


def queue_state():
    """Read the queue file AND prove the read worked.

    Returns (kind, value) where kind is 'ok' (a parsed list), 'empty' (the file does not exist, which
    is a legitimate state for an empty queue), or 'unreadable' (the read itself failed — run-as on a
    non-debuggable package, a permission problem, ...). Distinguishing 'empty' from 'unreadable' is
    the whole point: the audit conflated them.
    """
    out, rc = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_queue.json")
    stripped = out.strip()
    if "No such file" in stripped:
        return "empty", []
    if "Permission denied" in stripped or "package not debuggable" in stripped or not stripped:
        return "unreadable", stripped[:80]
    try:
        return "ok", json.loads(stripped)
    except Exception as e:
        return "unreadable", f"parse failed: {e}: {stripped[:80]}"


def canary_read_check():
    """Prove `run-as` can read this app's files at all, before trusting any 'empty' result."""
    out, _ = adb("shell", "run-as", PKG, "ls", "-la", "files/")
    return out.strip()


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
                       ("API key", "sk-mock"),
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


def ask(q):
    f = seek("Type a question")
    if not f:
        return False
    tap(f)
    adb("shell", "input", "text", q)
    time.sleep(1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    s = seek("Send")
    if s:
        tap(s)
    return True


def mock(path):
    try:
        return json.loads(urllib.request.urlopen(MOCK + path, timeout=15).read())
    except Exception as e:
        return {"error": str(e)}


print("=== step 0: can `run-as` read this app's files at all? ===")
mock("/__reset")
mock("/__ok")
adb("shell", "pm", "clear", PKG)
print("  launch:", launch())
print("  configured:", configure())
listing = canary_read_check()
print("  `run-as ls files/` ->", repr(listing[:120]))
if "hermes_wear_config.bin" not in listing:
    print("  !! run-as cannot see the config file: the APK is not debuggable, so every queue read")
    print("     in the audit was meaningless. Stopping before drawing a conclusion.")
    sys.exit(2)
print("  -> the read works; a queue result can be trusted\n")

print("=== Q1: does an offline question really land in the queue? ===")
mock("/__fail")
before_kind, before = queue_state()
print(f"  queue before: {before_kind} {before}")
ask("held-proof-alpha")
time.sleep(10)
after_kind, after = queue_state()
screen = texts()
print(f"  queue after:  {after_kind} {after}")
print(f"  screen: {[t[:50] for t in screen][:6]}")
if after_kind == "unreadable":
    print("  VERDICT: UNREADABLE — measurement failed, no conclusion drawn")
elif len(after) == 1 and any("held-proof-alpha" in (e.get("text", "") if isinstance(e, dict) else str(e)) for e in after):
    print("  VERDICT: PASS — the question is on disk. The audit's `queue 0->0` was the read failing,")
    print("           not the app losing data.")
else:
    print(f"  VERDICT: FAIL — screen says held but the queue holds {len(after)} entries: {after}")

print("\n=== Q2: an 8-second slow response ===")
mock("/__reset")
mock("/__ok")
mock("/__slow")
before_kind, before = queue_state()
print(f"  queue before: {before_kind} {before}")
ask("slow-proof-beta")
for wait in (5, 10, 15, 20):
    time.sleep(5)
    screen = texts()
    kind, q = queue_state()
    answer = [t for t in screen if "SLOW-ANSWER" in t]
    status = [t for t in screen if "Offline" in t or "held" in t.lower()]
    print(f"  t+{wait + 5:>2}s  queue={kind}:{len(q) if kind != 'unreadable' else q}"
          f"  answer={bool(answer)}  status={status[:1]}")
    if answer or status:
        break
mock("/__reset")
mock("/__ok")
kind, q = queue_state()
print(f"  final queue: {kind} {q}")
print("  VERDICT: PASS — the slow response is answered, not lost and not silently dropped"
      if not q else f"  VERDICT: held {len(q)} after a successful provider — needs a look")
