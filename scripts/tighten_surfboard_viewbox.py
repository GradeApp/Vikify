"""
Tighten SVG viewBox to union bbox of all path geometry (surfboard asset).
Wraps paths in <g transform="translate(-minx,-miny)"> and sets viewBox="0 0 w h".
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from svgpathtools import parse_path

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "app" / "src" / "main" / "assets" / "surfboard.svg"


def bbox_path(d: str) -> tuple[float, float, float, float] | None:
    d = re.sub(r"\s+", " ", d.strip())
    if not d:
        return None
    try:
        p = parse_path(d)
    except Exception:
        return None
    if len(p) == 0:
        return None
    xmin, xmax, ymin, ymax = p.bbox()
    return (xmin, xmax, ymin, ymax)


def main() -> None:
    text = SVG.read_text(encoding="utf-8", errors="replace")
    # Handle default namespace
    text = re.sub(r"\sxmlns=\"[^\"]+\"", "", text, count=1)
    root = ET.fromstring(text)
    ET.register_namespace("", "http://www.w3.org/2000/svg")

    min_x, min_y = float("inf"), float("inf")
    max_x, max_y = float("-inf"), float("-inf")

    for el in root.iter():
        if el.tag.endswith("path") or el.tag == "path":
            d = el.get("d")
            if not d:
                continue
            bb = bbox_path(d)
            if bb is None:
                continue
            x0, x1, y0, y1 = bb
            min_x = min(min_x, x0)
            max_x = max(max_x, x1)
            min_y = min(min_y, y0)
            max_y = max(max_y, y1)

    pad = 2.0
    min_x -= pad
    min_y -= pad
    max_x += pad
    max_y += pad
    w = max_x - min_x
    h = max_y - min_y
    if not (w > 0 and h > 0):
        raise SystemExit("could not compute bbox")

    # Re-parse to build output with group
    tree = ET.parse(str(SVG))
    r = tree.getroot()
    ns = {"svg": "http://www.w3.org/2000/svg"}
    if r.tag.startswith("{"):
        uri = r.tag[1 : r.tag.index("}")]
        ET.register_namespace("", uri)

    children = list(r)
    for c in children:
        r.remove(c)
    g = ET.Element("{http://www.w3.org/2000/svg}g")
    g.set("transform", f"translate({-min_x:.6f},{-min_y:.6f})")
    for c in children:
        g.append(c)
    r.insert(0, g)

    r.set("viewBox", f"0 0 {w:.6f} {h:.6f}")
    if "width" in r.attrib:
        del r.attrib["width"]
    r.set("preserveAspectRatio", "xMidYMid meet")

    out = ET.tostring(r, encoding="unicode", default_namespace=None)
    # Pretty declaration
    decl = '<?xml version="1.0" encoding="UTF-8"?>\n'
    SVG.write_text(decl + out, encoding="utf-8")
    print("viewBox 0 0", w, h, "from", min_x, min_y)


if __name__ == "__main__":
    main()
