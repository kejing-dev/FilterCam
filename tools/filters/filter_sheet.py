#!/usr/bin/env python3
"""6 samples x 8 filters sheet (design verification.pixelChecks), rendered by the app's own engine through the JVM test
FilterSheetTest. Usage: python tools/filters/filter_sheet.py prep   (then gradlew testDebugUnitTest)
                        python tools/filters/filter_sheet.py sheet <out.png>"""
import glob, os, sys
from PIL import Image, ImageDraw
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app")
SAMPLES = ["street", "desk", "window", "cat", "food", "night"]
FILTERS = ["F01", "F02", "F04", "F05", "F06", "F07", "F08", "F09"]
if sys.argv[1] == "prep":
    os.makedirs(os.path.join(ROOT, "build", "sheet-in"), exist_ok=True)
    for s in SAMPLES:
        im = Image.open(os.path.join(ROOT, "src", "main", "assets", "samples", s + ".jpg")).convert("RGB").resize((640, 480), Image.BILINEAR)
        open(os.path.join(ROOT, "build", "sheet-in", s + ".rgb"), "wb").write(im.tobytes())
else:
    cw, ch = 320, 240
    sheet = Image.new("RGB", (cw * 9, ch * 6 + 30), (16, 16, 16))
    d = ImageDraw.Draw(sheet)
    for c, f in enumerate(["source"] + FILTERS):
        d.text((c * cw + 8, 8), f, fill=(240, 240, 240))
    for r, s in enumerate(SAMPLES):
        sheet.paste(Image.open(os.path.join(ROOT, "src", "main", "assets", "samples", s + ".jpg")).resize((cw, ch)), (0, 30 + r * ch))
        for c, f in enumerate(FILTERS):
            p = glob.glob(os.path.join(ROOT, "build", "sheet-out", f"{s}_{f}_*.rgb"))[0]
            w, h = map(int, p.rsplit("_", 1)[1][:-4].split("x"))
            im = Image.frombytes("RGB", (w, h), open(p, "rb").read())
            im = im.resize((ch, ch)) if w == h else im.resize((cw, ch))
            sheet.paste(im, ((c + 1) * cw + (cw - im.width) // 2, 30 + r * ch))
    sheet.save(sys.argv[2])
    print("wrote", sys.argv[2])
