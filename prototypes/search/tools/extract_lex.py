"""Extract numbered text elements from a lex.uz document page.

lex.uz marks every paragraph of a law as <div name="ID" id="ID">text</div>.
The Cyrillic page uses positive IDs and the Latin page (/docs/-N) the same IDs
negated, so paragraphs pair up by abs(ID).
Usage: extract_lex.py page.html out.tsv
"""
import html
import re
import sys

src = open(sys.argv[1], encoding="utf-8").read()
out = open(sys.argv[2], "w", encoding="utf-8")
n = 0
for m in re.finditer(r'<div name="(-?\d+)" id="(-?\d+)">(.*?)</div>', src, flags=re.S):
    name, ident, body = m.group(1), m.group(2), m.group(3)
    if name != ident:
        continue
    text = re.sub(r"<[^>]+>", " ", body)
    text = html.unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        continue
    out.write(f"{abs(int(ident))}\t{text}\n")
    n += 1
print(sys.argv[1], n, "elements")
