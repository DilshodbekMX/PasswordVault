#!/usr/bin/env python3
"""Make a labelled contact sheet: sheet.py OUT.png COLS name1 name2 ... (names are shots/NAME.png)"""
import sys, os
from PIL import Image, ImageDraw
out, cols, names = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
W, H, LH = 300, 667, 28
rows = (len(names) + cols - 1) // cols
sheet = Image.new("RGB", (cols * (W + 8), rows * (H + LH + 8)), (128, 128, 128))
d = ImageDraw.Draw(sheet)
for i, n in enumerate(names):
    im = Image.open(os.path.join("shots", n + ".png")).convert("RGB").resize((W, H))
    x, y = (i % cols) * (W + 8), (i // cols) * (H + LH + 8)
    d.rectangle([x, y, x + W, y + LH], fill=(255, 255, 255))
    d.text((x + 4, y + 6), n, fill=(0, 0, 0))
    sheet.paste(im, (x, y + LH))
sheet.save(out)
print(out, sheet.size)
