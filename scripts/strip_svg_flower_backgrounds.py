"""
Remove near-white background <path> blocks and enable-background from flower SVGs.
Processes drawings/ and app/src/main/assets/drawings/ (same 11 files).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIRS = [
    ROOT / "drawings",
    ROOT / "app" / "src" / "main" / "assets" / "drawings",
]

# Explicit fills that are page backgrounds in traced exports
EXACT_WHITE_FILLS = {
    "#FEFEFD",
    "#FEFEFE",
    "#FFFFFF",
    "#FDFDFD",
    "#FCFCFC",
    "#FFFEFD",
    "#FFFFFE",
    "#FDFDFC",
    "#FEFDFC",
    "#FFF",
    "#FFFFFF",
    "WHITE",
}


def hex_to_rgb(h: str) -> tuple[int, int, int] | None:
    h = h.strip().lstrip("#")
    if len(h) == 6:
        try:
            return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
        except ValueError:
            return None
    return None


def is_background_fill(fill_val: str) -> bool:
    v = fill_val.strip().upper().replace(" ", "")
    if v in EXACT_WHITE_FILLS:
        return True
    if v.startswith("#") and len(v) == 7:
        rgb = hex_to_rgb(v)
        if rgb and all(c >= 248 for c in rgb):
            return True
    return False


def extract_fill(block: str) -> str | None:
    m = re.search(r'fill\s*=\s*["\']([^"\']+)["\']', block, re.I)
    return m.group(1) if m else None


def strip_svg(text: str) -> str:
    # Remove enable-background on <svg ...>
    text = re.sub(
        r'\s+enable-background\s*=\s*["\'][^"\']*["\']',
        "",
        text,
        flags=re.I,
    )

    paths = list(re.finditer(r"<path\b[\s\S]*?/>", text))
    to_remove: list[tuple[int, int]] = []
    for m in paths:
        block = m.group(0)
        fill = extract_fill(block)
        if fill and is_background_fill(fill):
            to_remove.append((m.start(), m.end()))

    if not to_remove:
        return text

    # Remove from end to start so indices stay valid
    out = text
    for start, end in sorted(to_remove, reverse=True):
        out = out[:start] + out[end:]
    return out


def process_file(path: Path) -> tuple[int, int]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    before = len(raw)
    new = strip_svg(raw)
    after = len(new)
    if new != raw:
        path.write_text(new, encoding="utf-8")
    return before, after


def main() -> int:
    nfiles = 0
    for d in DIRS:
        if not d.is_dir():
            print("skip missing:", d)
            continue
        for p in sorted(d.glob("*.svg")):
            b, a = process_file(p)
            print(f"{p.name}: {b} -> {a} bytes")
            nfiles += 1
    print("Processed", nfiles, "files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
