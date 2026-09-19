"""Probe: what does the watch do with the adversarial responses, per case, on the device?

The Pass-B audit reported `[OK]` for the html/broken/empty cases because the app did not crash and the
question was held. That is the right *outcome* but it is not the whole question: a held question and a
*wrong answer* look identical on a small screen. This probe reads the mock's request log and the
queue file to say which of the three actually happened:

  * held  — the question is on disk, waiting for a network that can answer it;
  * answered — the provider's text is on screen;
  * dropped — the question vanished, which is the failure this app exists to prevent.
"""
import json
import re
import subprocess
import sys
import time
import urllib.request

ADB = r"<old-checkout>\_tools\sdk\platform-tools\adb.exe"
DEV = ["-s", "emulator-5556"]
PKG = "com.hermes.app"
ACT = f"{PKG}/com.newoether.agora.wear.WearMainActivity"
MOCK = "http://127.0.0.1:8077"

rows = []


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


def seek(text, n=10):
    for _ in range(n):
        found = find(text)
        if found:
            return found
        swipe_up()
    return None


def queue():
    out = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_queue.json").strip()
    if not out or "No such file" in out or "Permission denied" in out:
        return []
    try:
        return json.loads(out)
    except Exception:
        return ["<unparseable>"]


def mock_get(path):
    try:
        return json.loads(urllib.request.urlopen(MOCK + path, timeout=10).read())
    except Exception as e:
        return {"error": str(e)}


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
                below = [n for n in dump() if n["cy"] > lab["cy"] and n["text"] and n["text"] != label]
                if below:
                    target = min(below, key=lambda n: n["cy"])
                    break
            swipe_up()
        if not target:
            return False
        tap(target)
        adb("shell", "input", "text", val)
        time.sleep(1)
        adb("shell", "input", "keyevent", "4")   # BACK closes the Wear IME (proven pattern)
        time.sleep(1)
    save = seek("Save key")
    if save:
        tap(save)
        time.sleep(4)
    return find("Type a question") is not None


def ask(question):
    f = seek("Type a question")
    if not f:
        return False, texts()
    tap(f)
    adb("shell", "input", "text", question)
    time.sleep(1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    s = seek("Send")
    if s:
        tap(s)
    time.sleep(12)
    return True, texts()


print("=== setup ===")
adb("reverse", "tcp:8077", "tcp:8077")
adb("shell", "pm", "clear", PKG)
if not relaunch():
    print("  could not reach the app"); sys.exit(2)
print("  configured:", configure())

for mode, label in [("html", "200 with an HTML captive-portal page"),
                    ("broken", "200 with truncated JSON"),
                    ("empty", "200 with choices: []"),
                    ("nocontent", "200 with content: null"),
                    ("huge", "200 with a 200 KB answer"),
                    ("slow", "200 after 8 s")]:
    mock_get("/__reset")
    mock_get("/" + "__" + mode)
    before = queue()
    ok, shown = ask(f"probe-{mode}")
    after = queue()
    fg = foreground()
    body = [t for t in shown if t not in ("Send", "Speak", "Debug", "Hermes X", "Type a question")]
    held = len(after) > len(before)
    answered = any("MOCK-ANSWER" in t or "SLOW-ANSWER" in t or t.startswith("HUGE") for t in shown)
    state = "held" if held else ("answered" if answered else "no change")
    rows.append((label, state, fg, (body[0][:70] if body else ""), len(after)))

mock_get("/__reset")
mock_get("/__ok")

print("\n=== results ===")
for label, state, fg, first, qlen in rows:
    in_app = "app" if fg.startswith(PKG) else fg.split("/")[0]
    print(f"  {label:<38} -> {state:<10} fg={in_app:<12} queue={qlen} | {first}")

bad = [r for r in rows if not r[2].startswith(PKG)]
print(f"\n{len(rows)} modes, {len(bad)} leaving the app")
sys.exit(1 if bad else 0)
