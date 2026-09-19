"""Adversarial device audit of the watch (Pass B). Attacks the real app on emulator-5556.

Each case names what it is trying to break, and reports what the app actually did — a crash, a hang,
a silently wrong answer, or a clean refusal. Nothing here is inferred from reading code.
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

results = []


def adb(*args, timeout=90):
    r = subprocess.run([ADB, *DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


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


def crashed():
    out = adb("shell", "dumpsys", "activity", "activities")
    return "com.hermes.app" not in out


def alive():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=[^ ]+ [^ ]+ ([^ ]+)", out)
    return bool(m) and m.group(1).startswith(PKG)


def mock(path):
    try:
        return urllib.request.urlopen(MOCK + path, timeout=5).status
    except Exception as e:
        return f"ERR {e}"


def record(case, verdict, detail):
    results.append((case, verdict, detail))
    print(f"  [{verdict}] {case}: {detail}")


def qsize():
    out = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_queue.json").strip()
    if not out or "No such file" in out or "Permission denied" in out:
        return 0
    try:
        return len(json.loads(out))
    except Exception:
        return -1


def config_file():
    out = adb("shell", "run-as", PKG, "ls", "-la", "files/").strip()
    return out


print("=== setup: mock up, reverse, clean install state ===")
print("  mock /__ok ->", mock("/__ok"))
adb("reverse", "tcp:8077", "tcp:8077")
adb("shell", "pm", "clear", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(8)

# ── A. base URL shapes ────────────────────────────────────────────────────────
print("\n=== A. base URL shapes ===")
cases = [
    ("https://x.com/v1/", "trailing slash"),
    ("https://x.com/chat/completions", "already a completions path"),
    ("http://127.0.0.1:11434", "no /v1, localhost"),
    ("x.com/v1", "no scheme"),
    ("", "empty"),
]
for value, label in cases:
    adb("shell", "pm", "clear", PKG)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(7)
    f = seek("Tap to type")
    if not f:
        record(f"base url {label}", "SKIP", "no empty field found")
        continue
    tap(f)
    adb("shell", "input", "text", value if value else " ")
    time.sleep(1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    # fill key + model so only the URL can be the reason for a rejection
    for label2, val in (("API key", "sk-mock"), ("Model", "m")):
        f2 = seek(label2)
        if f2:
            fields = [n for n in dump() if n["cy"] > f2["cy"]]
            if fields:
                tap(min(fields, key=lambda n: n["cy"]))
                adb("shell", "input", "text", val)
                time.sleep(1)
                adb("shell", "input", "keyevent", "4")
                time.sleep(1)
    save = seek("Save key")
    if save:
        tap(save)
        time.sleep(3)
    shown = texts()
    on_chat = any("Type a question" in t for t in shown)
    rejected = any("Need an https" in t for t in shown)
    record(f"base url {label}", "OK" if not crashed() else "CRASH",
           f"value={value!r} -> {'accepted (chat screen)' if on_chat else ('rejected with a message' if rejected else shown[:3])}")

# ── B. provider responses ─────────────────────────────────────────────────────
print("\n=== B. provider responses (mock toggles) ===")
adb("shell", "pm", "clear", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(7)
for label, val in (("Base URL", "http://127.0.0.1:8077/v1"), ("API key", "sk-mock"), ("Model", "mock-model")):
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
record("BYOK configure", "OK" if alive() else "CRASH", f"screen={texts()[:3]}")

for path, label, expect in [
    ("/__fail", "503 Service Unavailable", "held offline"),
    ("/__ok", "200 with a normal answer", "answer shown"),
    ("/__html", "HTML body instead of JSON", "held or error, never a crash"),
    ("/__broken", "malformed JSON", "held or error, never a crash"),
    ("/__empty", "empty choices array", "held or error, never a crash"),
    ("/__slow", "a slow response", "either an answer or a timeout, no hang"),
]:
    mock(path)
    q_before = qsize()
    ask = seek("Type a question")
    if ask:
        tap(ask)
        adb("shell", "input", "text", f"q-{label.split()[0]}")
        time.sleep(1)
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    send = seek("Send")
    if send:
        tap(send)
    time.sleep(10)
    shown = texts()
    q_after = qsize()
    verdict = "OK" if alive() else "CRASH"
    record(f"provider {label}", verdict,
           f"queue {q_before}->{q_after}, screen={[t for t in shown if t not in ('Send','Speak','Debug')][:3]}")
mock("/__reset")
mock("/__ok")

# ── C. hostile inputs ────────────────────────────────────────────────────────
print("\n=== C. hostile inputs ===")
long_q = "L" * 4000
ask = seek("Type a question")
if ask:
    tap(ask)
    adb("shell", "input", "text", long_q)
    time.sleep(2)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
shown = texts()
echoed = [t for t in shown if t.startswith("LLL")]
record("4000-character question", "OK" if alive() else "CRASH",
       f"field shows {len(echoed[0]) if echoed else 0} chars, screen={[t[:40] for t in shown][:3]}")

adb("shell", "pm", "clear", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(7)
record("no config at all", "OK" if alive() else "CRASH", f"screen={texts()[:3]} (must ask for setup)")

# ── D. config corruption ─────────────────────────────────────────────────────
print("\n=== D. config corruption ===")
adb("shell", "run-as", PKG, "sh", "-c", "printf 'garbage' > files/hermes_wear_config.bin")
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(8)
record("corrupted config file", "OK" if alive() else "CRASH",
       f"screen={texts()[:3]} (must return to setup, not crash)")

adb("shell", "run-as", PKG, "sh", "-c", "printf '' > files/hermes_wear_queue.json")
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(8)
record("empty queue file", "OK" if alive() else "CRASH", f"screen={texts()[:3]}, queue={qsize()}")

adb("shell", "run-as", PKG, "sh", "-c", "printf 'not json' > files/hermes_wear_queue.json")
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(8)
record("garbage queue file", "OK" if alive() else "CRASH", f"screen={texts()[:3]}, queue={qsize()}")

# ── E. credential leakage ────────────────────────────────────────────────────
print("\n=== E. credential leakage ===")
adb("shell", "pm", "clear", PKG)
adb("shell", "am", "start", "-n", ACT)
time.sleep(7)
for label, val in (("Base URL", "http://127.0.0.1:8077/v1"), ("API key", "sk-SECRET-CANARY-12345"), ("Model", "mock-model")):
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

raw = adb("shell", "run-as", PKG, "cat", "files/hermes_wear_config.bin")
in_file = "sk-SECRET-CANARY-12345" in raw
log = adb("logcat", "-d")
in_log = "sk-SECRET-CANARY-12345" in log
record("canary in the config file", "FAIL" if in_file else "OK",
       "plaintext key present" if in_file else f"encrypted ({len(raw)} bytes, no plaintext)")
record("canary in logcat", "FAIL" if in_log else "OK",
       "key leaked to the log" if in_log else "no occurrence in the whole log buffer")

# a real question, then re-check the log for the key
ask = seek("Type a question")
if ask:
    tap(ask)
    adb("shell", "input", "text", "leak-check")
    time.sleep(1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
send = seek("Send")
if send:
    tap(send)
time.sleep(10)
log2 = adb("logcat", "-d")
record("canary in logcat after a real request", "FAIL" if "sk-SECRET-CANARY-12345" in log2 else "OK",
       "leaked during a request" if "sk-SECRET-CANARY-12345" in log2 else "still absent")

print("\n=== SUMMARY ===")
bad = [r for r in results if r[1] in ("FAIL", "CRASH")]
for case, verdict, detail in results:
    print(f"  {verdict:6} {case}: {detail}")
print(f"\n{len(results)} cases, {len(bad)} failing")
sys.exit(1 if bad else 0)
