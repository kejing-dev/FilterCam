#!/usr/bin/env python3
"""Renders the launcher icon (res/drawable/ic_launcher_foreground.xml on #C9CED6) to a 512 px PNG for the factory
(apps/filter-cam/icon.png). Usage: python tools/make_icon.py <out.png>"""
import sys
from PIL import Image, ImageDraw
S = 4 * 512
k = S / 108.0
im = Image.new("RGB", (S, S), (0xC9, 0xCE, 0xD6))
d = ImageDraw.Draw(im)
ink = (0x1B, 0x1F, 0x26)
w = int(6 * k)
def L(pts):
    d.line([(x * k, y * k) for x, y in pts], fill=ink, width=w, joint="curve")
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x * k - w / 2, y * k - w / 2, x * k + w / 2, y * k + w / 2], fill=ink)
L([(30, 42), (30, 30), (42, 30)]); L([(66, 30), (78, 30), (78, 42)]); L([(78, 66), (78, 78), (66, 78)]); L([(42, 78), (30, 78), (30, 66)])
d.ellipse([42 * k, 42 * k, 66 * k, 66 * k], fill=ink)
d.ellipse([66.5 * k, 66.5 * k, 75.5 * k, 75.5 * k], fill=(0xFF, 0x7A, 0x1A))
# the launcher shows the middle 72 of 108 dp
c = int(18 * k)
im.crop((c, c, S - c, S - c)).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
