"""Reads every CSV, JSON and XLSX the attack harness wrote with tools other than the prototype and
compares with manifest.json (intended values as UTF-16 code units):
  CSV  : Python csv (RFC 4180) and a transcription of Chromium's importer written from its source
         (csv_password_iterator.cc ConsumeCSVLine + TrimString(" \r\t" leading), csv_field_parser.cc,
         csv_password.cc ConvertUTF8, password_importer.cc length checks: bytes of UTF-8);
  JSON : Python json (strict) - titles, usernames, passwords, notes;
  XLSX : openpyxl 3.1.5 and python-calamine (Rust calamine).
Usage: read_plain.py DIR"""
import csv, io, json, sys, zipfile
import openpyxl
from python_calamine import CalamineWorkbook

d = sys.argv[1]
man = json.load(open(f"{d}/manifest.json"))


def s(units):
    """UTF-16 code units -> str with pairs joined into code points; lone surrogates stay lone."""
    import struct
    return struct.pack(f"<{len(units)}H", *units).decode("utf-16-le", errors="surrogatepass")


def vis(x, n=70):
    r = repr(x)
    return r if len(r) <= n else r[:n] + "…"


def as_utf8_written(text):
    """What the app's UTF-8 encoder writes for a str that may hold lone surrogates (Java/Android: '?')."""
    return text.encode("utf-8", errors="replace").decode("utf-8")


# ---------------------------------------------------------------- Chromium transcription
def consume_csv_line(b, pos):
    inside = False; last_cr = False; i = pos
    while i < len(b):
        c = b[i]
        if c == "\n" and not inside:
            return b[pos:(i - 1 if last_cr else i)], i + 1
        if c == '"':
            inside = not inside
        last_cr = c == "\r"; i += 1
    return b[pos:], len(b)


def chrome_fields(row):
    """CSVFieldParser: kInit/kPlain/kQuoted/kAfter; None on syntax error."""
    out = []; pos = 0; n = len(row)
    while True:
        state = "init"; start = pos
        while True:
            if pos > n:
                return None
            ch = row[pos] if pos < n else ","
            pos += 1
            if state == "init":
                if ch == ",": break
                state = "quoted" if ch == '"' else "plain"
            elif state == "plain":
                if ch == ",": break
            elif state == "quoted":
                if ch == '"': state = "after"
            elif state == "after":
                if ch == ",": break
                elif ch == '"': state = "quoted"
                else: return None
        f = row[start:pos - 1]
        if f.startswith('"'):
            f = f[1:-1]
        out.append(f.replace('""', '"'))
        if pos > n:
            return out


def chrome_read(text):
    header, pos = consume_csv_line(text, 0)
    rows = []
    while pos < len(text):
        line, pos = consume_csv_line(text, pos)
        line = line.lstrip("\r \t")
        if not line:
            continue
        rows.append(chrome_fields(line))
    return chrome_fields(header), rows


# ---------------------------------------------------------------- CSV
for name in [k for k in man if k.endswith(".csv")]:
    raw = open(f"{d}/{name}", "rb").read()
    text = raw.decode("utf-8")  # strict: the file must be valid UTF-8
    want = [[as_utf8_written(s(c)) for c in r] for r in man[name]]
    got = list(csv.reader(io.StringIO(text, newline="")))
    diffs = [(i, a, b) for i, (a, b) in enumerate(zip(got[1:], want)) if a != b]
    extra = len(got) - 1 - len(want)
    _, crows = chrome_read(text)
    cdiff = [(i, a, b) for i, (a, b) in enumerate(zip(crows, want)) if a is None or a[1:5] != b[1:5]]
    print(f"{name}: python csv {len(got) - 1} rows, {len(diffs)} differ, {extra:+d} rows; chrome transcription {len(crows)} rows, {len(cdiff)} differ in url/username/password/note")
    for i, a, b in (diffs[:3] + cdiff[:3]):
        print(f"    row {i}: got {vis(a)}\n            want {vis(b)}")

# ---------------------------------------------------------------- JSON
for name in [k for k in man if k.endswith(".json")]:
    raw = open(f"{d}/{name}", "rb").read()
    doc = json.loads(raw.decode("utf-8"))
    want = [[as_utf8_written(s(c)) for c in r] for r in man[name]]
    got = [[it["name"] or "", it["login"]["username"] or "", it["login"]["password"] or "", it["notes"] or ""] for it in doc["items"]]
    diffs = [(i, a, b) for i, (a, b) in enumerate(zip(got, want)) if a != b]
    lossy = sum(1 for r in man[name] for c in r if as_utf8_written(s(c)) != s(c))
    print(f"{name}: python json {len(got)} items, {len(diffs)} differ from the UTF-8 the app writes; {lossy} values not representable (lone surrogates -> '?')")

# ---------------------------------------------------------------- XLSX
def cells_openpyxl(path):
    ws = openpyxl.load_workbook(path, read_only=False).active
    return [[("" if c.value is None else str(c.value)) for c in row] for row in ws.iter_rows()]


def cells_calamine(path):
    wb = CalamineWorkbook.from_path(path)
    return [[("" if v is None else str(v)) for v in row] for row in wb.get_sheet_by_index(0).to_python(skip_empty_area=False)]


for name in [k for k in man if k.endswith(".xlsx")]:
    want = [[s(c) for c in r] for r in man[name]]
    for reader, fn in (("openpyxl", cells_openpyxl), ("calamine", cells_calamine)):
        try:
            got = fn(f"{d}/{name}")
        except Exception as e:
            print(f"{name}: {reader} FAILED {type(e).__name__}: {e}"); continue
        bad = []
        for r, wrow in enumerate(want):
            grow = got[r] if r < len(got) else []
            for c, wv in enumerate(wrow):
                gv = grow[c] if c < len(grow) else ""
                if len(wv) > 32767:
                    ok = gv.startswith(wv[:1000]) and len(gv) <= 32767
                else:
                    ok = gv == wv
                if not ok:
                    bad.append((r + 1, c + 1, gv, wv))
        print(f"{name}: {reader} {sum(len(r) for r in want)} cells, {len(bad)} differ")
        for r, c, gv, wv in (bad if name == "classes.xlsx" else bad[:4]):
            print(f"    R{r}C{c}: got {vis(gv)} want {vis(wv)}")
