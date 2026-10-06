"""Compares LibreOffice's CSV conversion of each .xlsx with what the writer meant (manifest.json for the
attack files, expected-xlsx-cells.json for the prototype's own export). Usage: lo_compare.py MANIFEST_DIR LO_CSV_DIR [OUT_DIR]"""
import csv, io, json, struct, sys

mdir, lodir = sys.argv[1], sys.argv[2]
outdir = sys.argv[3] if len(sys.argv) > 3 else None
man = json.load(open(f"{mdir}/manifest.json"))


def s(units):
    return struct.pack(f"<{len(units)}H", *units).decode("utf-16-le", errors="surrogatepass")


def compare(label, want, path):
    raw = open(path, "rb").read()
    try:
        text = raw.decode("utf-8")
        note = ""
    except UnicodeDecodeError as e:
        text = raw.decode("utf-8", errors="surrogateescape")
        note = f" (LibreOffice wrote invalid UTF-8: {e.reason} at byte {e.start})"
    got = list(csv.reader(io.StringIO(text, newline="")))
    bad = []
    for r, wrow in enumerate(want):
        grow = got[r] if r < len(got) else []
        for c, wv in enumerate(wrow):
            gv = grow[c] if c < len(grow) else ""
            ok = (gv.startswith(wv[10:]) and len(gv) <= 32767) if wv.startswith("truncated:") else (
                (gv.startswith(wv[:1000]) and len(gv) <= 32767) if len(wv) > 32767 else gv == wv)
            if not ok:
                bad.append((r + 1, c + 1, gv, wv))
    print(f"{label}: LibreOffice {len(got)} rows (want {len(want)}), {sum(map(len, want))} cells, {len(bad)} differ{note}")
    for r, c, gv, wv in bad[:8]:
        print(f"    R{r}C{c}: got {gv[:70]!r} want {wv[:70]!r}")


for name in [k for k in man if k.endswith(".xlsx")]:
    compare(name, [[s(c) for c in r] for r in man[name]], f"{lodir}/{name[:-5]}.csv")
if outdir:
    exp = json.load(open(f"{outdir}/expected-xlsx-cells.json", encoding="utf-8"))
    compare("export.xlsx (prototype's own 5,016 rows)", exp, f"{lodir}/export.csv")
