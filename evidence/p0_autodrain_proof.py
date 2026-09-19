"""P0 end-to-end proof: a question held offline is delivered when the app is next opened.

The defect being proved fixed: `drainQueue` had exactly ONE call site — the success branch of
`send()` — so a held question was only delivered if the user asked a SECOND question that ALSO
succeeded, and that drained answer then overwrote the fresh one. A held question nobody re-asks was
silently dropped. For a device whose entire premise is losing connectivity, that is the worst
failure available.

Flow, on the real signed release APK against the mock provider:
  1. configure BYOK once, pointing at the mock (reachable as 10.0.2.2 from the emulator)
  2. flip the mock to 503 so the same config now fails, and ask a question -> must be HELD
  3. flip the mock back to 200, force-stop the app, relaunch, and touch NOTHING
  4. assert the held question reached the provider and its answer is on screen

The config is never cleared, so the queue file survives the whole test — which is what makes the
drain observable. Step 3 is the entire point: relaunching is what used to do nothing.
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
# The emulator reaches the host loopback through 10.0.2.2.
MOCK_FROM_DEVICE = "http://127.0.0.1:8077/v1"   # via `adb reverse tcp:8077 tcp:8077`

failures = []


def adb(*args, timeout=90):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def mock_get(path):
    with urllib.request.urlopen(MOCK + path, timeout=10) as r:
        return json.loads(r.read().decode())


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/d.xml")
    xml = adb("shell", "cat", "/sdcard/d.xml")
    nodes = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = re.search(r'text="([^"]*)"', t)
        bnd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        cls = re.search(r'class="([^"]*)"', t)
        if bnd:
            nodes.append({
                "text": txt.group(1) if txt else "",
                "cls": cls.group(1) if cls else "",
                "cx": (int(bnd.group(1)) + int(bnd.group(3))) // 2,
                "cy": (int(bnd.group(2)) + int(bnd.group(4))) // 2,
                "top": int(bnd.group(2)),
            })
    return nodes


def texts():
    return [n["text"] for n in dump() if n["text"]]


def find(text, cls=None):
    for n in dump():
        if n["text"] == text and (cls is None or cls in n["cls"]):
            return n
    return None


def scroll_down(times=1):
    for _ in range(times):
        adb("shell", "input", "swipe", "192", "300", "192", "110", "250")
        time.sleep(1)


def type_into_field(label, value):
    for _ in range(8):
        lab = find(label)
        if lab and 90 < lab["cy"] < 300:
            fields = [n for n in dump() if "EditText" in n["cls"] and n["top"] > lab["top"]]
            if fields:
                t = min(fields, key=lambda n: n["top"])
                adb("shell", "input", "tap", str(t["cx"]), str(t["cy"]))
                time.sleep(1.5)
                adb("shell", "input", "text", value)
                time.sleep(1.2)
                adb("shell", "input", "keyevent", "4")      # BACK closes the IME
                time.sleep(1.2)
                return any(n["text"] == value for n in dump())
        scroll_down()
    return False


def configure(base_url, model="mock-model"):
    adb("shell", "pm", "clear", PKG)
    time.sleep(2)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(8)
    ok = [type_into_field("Base URL", base_url),
          type_into_field("API key", "sk-mock-not-real"),
          type_into_field("Model", model)]
    for _ in range(8):
        b = find("Save key")
        if b:
            adb("shell", "input", "tap", str(b["cx"]), str(b["cy"]))
            break
        scroll_down()
    time.sleep(5)
    return all(ok)


def ask(question):
    for _ in range(8):
        f = find("Type a question")
        if f:
            adb("shell", "input", "tap", str(f["cx"]), str(f["cy"]))
            time.sleep(1.5)
            adb("shell", "input", "text", question)
            time.sleep(1.2)
            adb("shell", "input", "keyevent", "4")
            time.sleep(1.2)
            break
        scroll_down()
    for _ in range(8):
        b = find("Send")
        if b:
            adb("shell", "input", "tap", str(b["cx"]), str(b["cy"]))
            break
        scroll_down()
    time.sleep(7)
    return texts()


def qsize():
    out = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_queue.json").strip()
    if not out or "No such file" in out or "Permission denied" in out:
        return 0
    try:
        return len(json.loads(out))
    except Exception:
        return 0


print("STEP 0: reset mock")
mock_get("/__reset")
mock_get("/__ok")

print("STEP 1: configure BYOK against the mock provider")
if not configure(MOCK_FROM_DEVICE):
    print("  FAILED: could not configure")
    sys.exit(1)
print("  configured ->", texts()[:4])

print("STEP 2: mock returns 503, ask a question -> must be HELD")
mock_get("/__fail")
after = ask("held-question-alpha")
held = qsize()
print("  screen:", after[:6])
print("  queue size:", held)
if held != 1:
    failures.append(f"expected 1 held question, queue has {held}")
if not any("Offline" in t or "held" in t.lower() for t in after):
    failures.append("no offline/held indication on screen")

print("STEP 3: mock back to 200, force-stop, relaunch, touch nothing")
mock_get("/__reset")
mock_get("/__ok")
adb("shell", "am", "force-stop", PKG)
time.sleep(3)
adb("shell", "am", "start", "-n", ACT)
time.sleep(14)                                  # launch drain needs time to complete
seen = texts()
after_q = qsize()
reqs = mock_get("/__requests")
print("  screen after relaunch:", seen[:6])
print("  queue after relaunch:", after_q)
print("  mock received:", [r["user"] for r in reqs])

if after_q != 0:
    failures.append(f"queue not drained on launch: still {after_q} held")
if not any(r["user"] == "held-question-alpha" for r in reqs):
    failures.append("the held question never reached the provider")
if not any("MOCK-ANSWER" in t for t in seen):
    failures.append("the drained answer is not visible on screen")

print()
if failures:
    print("RESULT: FAIL")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("RESULT: PASS -- held question delivered on launch, no user action, queue empty")
