import json
import os
import re

base = r"<home>\AppData\Local\hermes\cache\delegation\live\deleg_2a56a355"

TITLE = re.compile(r'"title"\s*:\s*"((?:[^"\\]|\\.){5,200})"')
FILE_LINE = re.compile(r'"file_line"\s*:\s*"((?:[^"\\]|\\.){3,300})"')
SEVERITY = re.compile(r'"severity"\s*:\s*"(\w+)"')
FIX = re.compile(r'"suggested_fix"\s*:\s*"((?:[^"\\]|\\.){5,500})"')
IMPACT = re.compile(r'"impact"\s*:\s*"((?:[^"\\]|\\.){5,600})"')

rows = []
for i in range(4):
    path = os.path.join(base, f"task-{i}.log")
    text = open(path, encoding="utf-8", errors="replace").read()
    for m in TITLE.finditer(text):
        chunk = text[m.start(): m.start() + 1800]
        fl = FILE_LINE.search(chunk)
        sv = SEVERITY.search(chunk)
        fx = FIX.search(chunk)
        im = IMPACT.search(chunk)
        rows.append(
            {
                "task": i,
                "title": m.group(1).encode().decode("unicode_escape", errors="replace"),
                "file_line": (fl.group(1) if fl else "?").encode().decode("unicode_escape", errors="replace"),
                "severity": sv.group(1) if sv else "?",
                "impact": (im.group(1) if im else "?").encode().decode("unicode_escape", errors="replace"),
                "fix": (fx.group(1) if fx else "?").encode().decode("unicode_escape", errors="replace"),
            }
        )

seen = set()
dedup = []
for r in rows:
    key = r["title"][:70]
    if key in seen:
        continue
    seen.add(key)
    dedup.append(r)

print(f"TOTAL {len(dedup)} distinct findings")
out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "findings.json")
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(dedup, fh, indent=2, ensure_ascii=False)
print("written:", out_path)
for r in dedup:
    print(f"\n[task-{r['task']}][{r['severity']}] {r['title']}")
    print(f"   at : {r['file_line']}")
    print(f"   why: {r['impact'][:180]}")
    print(f"   fix: {r['fix'][:180]}")
