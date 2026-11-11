"""
Remove remaining background paths from hibiscus SVGs (namespaced path tags).
Keeps petal/stamen colors; drops black, white, gray, cream UI fills.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRS = [
    ROOT / "drawings",
    ROOT / "app" / "src" / "main" / "assets" / "drawings",
]

DROP_FILLS = {
    "#000000",
    "#FFFFFF",
    "#FEFEFD",
    "#FEFEFE",
    "#FDFDFD",
    "#FCFCFC",
    "#F8F8F6",
    "#F4F4F2",
    "#F8EAD6",
    "#C1C1C0",
    "#C7C8BF",
    "#ACACA7",
    "#9D9D9B",
    "#9D9D9C",
    "#BDBDBD",
    "#D0D0CE",
    "#E0E0E0",
    "#CCCCCC",
    "#FFF",
    "WHITE",
    "BLACK",
}


def hex_to_rgb(h: str):
    h = h.strip().lstrip("#")
    if len(h) != 6:
        return None
    try:
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    except ValueError:
        return None


def should_drop_fill(fill: str) -> bool:
    v = fill.strip().upper().replace(" ", "")
    if v in DROP_FILLS:
        return True
    if v.startswith("#") and len(v) == 7:
        rgb = hex_to_rgb(v)
        if not rgb:
            return False
        r, g, b = rgb
        if all(c >= 248 for c in rgb):
            return True
        if min(r, g, b) >= 232 and max(r, g, b) - min(r, g, b) <= 22:
            return True
        mx, mn = max(r, g, b), min(r, g, b)
        if mx - mn <= 28 and 145 <= mn <= 215 and mx <= 220:
            return True
    return False


def extract_fill(block: str) -> str | None:
    m = re.search(r'fill\s*=\s*["\']([^"\']+)["\']', block, re.I)
    return m.group(1) if m else None


def strip_svg(text: str) -> str:
    text = re.sub(
        r'\s+enable-background\s*=\s*["\'][^"\']*["\']',
        "",
        text,
        flags=re.I,
    )
    # Long d="..." may contain '>'; match until self-close />
    paths = list(re.finditer(r"<(?:\w+:)?path\b[\s\S]*?/>", text))
    out = text
    for m in sorted(paths, key=lambda x: x.start(), reverse=True):
        block = m.group(0)
        fill = extract_fill(block)
        if fill and should_drop_fill(fill):
            out = out[: m.start()] + out[m.end() :]
    return out


def main() -> int:
    for d in DIRS:
        if not d.is_dir():
            continue
        for f in sorted(d.glob("*.svg")):
            n = f.name.lower()
            if not (n.startswith("hibiscus") and n.endswith(".svg")):
                continue
            raw = f.read_text(encoding="utf-8", errors="replace")
            new = strip_svg(raw)
            if new != raw:
                f.write_text(new, encoding="utf-8")
                print(f"{f.name}: {len(raw)} -> {len(new)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
