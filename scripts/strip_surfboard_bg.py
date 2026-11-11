"""Remove black frame and cream backdrop paths from surfboard.svg (assets copy)."""
import re
from pathlib import Path

p = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "surfboard.svg"
t = p.read_text(encoding="utf-8", errors="replace")
# Drop full-canvas black rect and large cream fill (typical traced backgrounds)
for pat in (
    r'<path[^>]*fill="#000000"[^/]*/>',
    r'<path[^>]*fill="#F8EAD6"[^/]*/>',
):
    t2, n = re.subn(pat, "", t, count=1, flags=re.I)
    t = t2
t = re.sub(r'\s+enable-background\s*=\s*["\'][^"\']*["\']', "", t, flags=re.I)
p.write_text(t, encoding="utf-8")
print("stripped", p)
