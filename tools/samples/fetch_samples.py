#!/usr/bin/env python3
"""Fetches the six sample photos (design content.inventory.samplePhotos: street corner, desk, window, cat, food, night)
from Wikimedia Commons, checks each is still CC0, centre-crops to 4:3 and writes 1024 x 768 JPEGs to
app/src/main/assets/samples/. Sources and authors: tools/samples/sources.json and assets/samples/CREDITS.md
(QA #1 q1-4: real photos instead of drawn ones).

    python tools/samples/fetch_samples.py
"""
import io
import json
import os
import urllib.parse
import urllib.request

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "samples")
UA = {"User-Agent": "FilterCamFactory/0.1 (sample photo fetch)"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA)) as r:
        return r.read()


def main():
    sources = json.load(open(os.path.join(HERE, "sources.json"), encoding="utf-8"))
    for name, src in sources.items():
        q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": src["title"], "prop": "imageinfo", "iiprop": "url|extmetadata", "iiurlwidth": 2048})
        info = next(iter(json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"].values()))["imageinfo"][0]
        lic = info["extmetadata"]["LicenseShortName"]["value"]
        if lic != "CC0":
            raise SystemExit(f"{name}: licence is now {lic}, pick another photo")
        im = Image.open(io.BytesIO(get(info["thumburl"]))).convert("RGB")
        w, h = im.size
        tw = min(w, h * 4 // 3)
        th = tw * 3 // 4
        left, top = (w - tw) // 2, (h - th) // 2
        im.crop((left, top, left + tw, top + th)).resize((1024, 768), Image.LANCZOS).save(os.path.join(OUT, name + ".jpg"), quality=88)
        print(name, lic, src["artistName"])


if __name__ == "__main__":
    main()
