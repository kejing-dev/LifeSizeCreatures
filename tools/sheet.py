"""Contact sheet of tour shots: python -I tools/sheet.py OUT.png A.png B.png ... (3 per row, labelled)."""
import os
import sys

from PIL import Image, ImageDraw

out, files = sys.argv[1], sys.argv[2:]
w = 640
ims = []
for f in files:
    im = Image.open(f).convert("RGB")
    im = im.resize((w, int(im.height * w / im.width)))
    ImageDraw.Draw(im).text((8, 8), os.path.basename(f), fill=(255, 0, 0))
    ims.append(im)
cols = 3
rows = (len(ims) + cols - 1) // cols
h = ims[0].height
sheet = Image.new("RGB", (cols * w, rows * h), (0, 0, 0))
for i, im in enumerate(ims):
    sheet.paste(im, ((i % cols) * w, (i // cols) * h))
sheet.save(out)
