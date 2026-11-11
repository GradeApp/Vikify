import re
from collections import Counter
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "drawings" / "hibiscus1.svg"
t = p.read_text(encoding="utf-8", errors="replace")
fills: list[str] = []
for m in re.finditer(r"<(?:\w+:)?path\b[\s\S]*?/>", t):
    blk = m.group(0)
    fm = re.search(r'fill="([^"]+)"', blk)
    if fm:
        fills.append(fm.group(1).upper())
print("unique", len(set(fills)))
for c, n in Counter(fills).most_common(30):
    print(n, c)
