"""Independent checks of export.csv:
1. Python's csv module (RFC 4180 dialect) must read back exactly the rows the exporter meant to write.
2. A line-by-line Python transcription of Chromium's importer (ConsumeCSVLine, CSVFieldParser,
   CSVPassword, CSVPasswordSequence and the length/URL checks in password_importer.cc, read at HEAD
   2026-10-06) must parse every row and report which rows Chrome would refuse and why.
"""
import csv, io, json, sys
from urllib.parse import urlsplit

out = sys.argv[1]
raw = open(f"{out}/export.csv", "rb").read()
text = raw.decode("utf-8")
expected = json.load(open(f"{out}/expected-csv-rows.json", encoding="utf-8"))

# 1. Python csv
rows = list(csv.reader(io.StringIO(text, newline="")))
assert rows[0] == ["name", "url", "username", "password", "note"], rows[0]
assert rows[1:] == expected, "python csv disagrees"
print(f"python csv: {len(rows) - 1} rows identical to the intended values")

# 2. Chromium transcription
def consume_csv_line(s, pos):
    inside_quotes = False; last_cr = False; i = pos
    while i < len(s):
        c = s[i]
        if c == "\n" and not inside_quotes:
            end = i - 1 if last_cr else i
            return s[pos:end], i + 1
        if c == '"':
            inside_quotes = not inside_quotes
        last_cr = (c == "\r"); i += 1
    return s[pos:], len(s)

def parse_fields(row):
    """CSVFieldParser: states kInit, kPlain, kQuoted, kAfter; returns list or None on error."""
    fields = []; pos = 0; n = len(row); max_fields = 0x7FFFFFFF
    while True:
        state = "init"; start = pos
        while True:
            if pos > n:
                return None  # unmatched quote
            ch = row[pos] if pos < n else ","
            pos += 1
            if state == "init":
                state = "init_done" if ch == "," else ("quoted" if ch == '"' else "plain")
                if state == "init_done":
                    break
            elif state == "plain":
                if ch == ",":
                    break
            elif state == "quoted":
                if ch == '"':
                    state = "after"
            elif state == "after":
                if ch == ",":
                    break
                elif ch == '"':
                    state = "quoted"
                else:
                    return None
        f = row[start:pos - 1]
        if f.startswith('"'):
            f = f[1:-1]
        fields.append(f.replace('""', '"'))
        if pos > n:
            return fields

LABELS = {"url": "origin", "website": "origin", "origin": "origin", "hostname": "origin", "login_uri": "origin",
          "username": "username", "user": "username", "login": "username", "account": "username", "login_username": "username",
          "password": "password", "login_password": "password",
          "note": "note", "notes": "note", "comment": "note", "comments": "note"}

b = raw  # Chromium works on bytes (std::string); lengths below are UTF-8 byte counts
s = b.decode("utf-8", errors="surrogateescape")
header, pos = consume_csv_line(s, 0)
cols = parse_fields(header)
colmap = {}
for i, name in enumerate(cols):
    lab = LABELS.get(name.strip(" \t\r\n").lower())
    if lab:
        colmap[i] = lab
assert sorted(colmap.values()) == ["note", "origin", "password", "username"], colmap
statuses = {}
parsed_rows = []
while pos < len(s):
    line, pos = consume_csv_line(s, pos)
    line = line.lstrip("\r \t")  # TRIM_LEADING of "\r \t"
    if not line:
        continue
    f = parse_fields(line)
    if f is None:
        statuses["SYNTAX_ERROR"] = statuses.get("SYNTAX_ERROR", 0) + 1; continue
    rec = {colmap[i]: v for i, v in enumerate(f) if i in colmap}
    parsed_rows.append(rec)
    pw, user, note, url = rec.get("password", ""), rec.get("username", ""), rec.get("note", ""), rec.get("origin", "")
    st = "OK"
    if not pw: st = "MISSING_PASSWORD"
    elif len(pw.encode()) > 1000: st = "LONG_PASSWORD"
    elif len(user.encode()) > 1000: st = "LONG_USERNAME"
    elif len(note.encode()) > 1000: st = "LONG_NOTE"
    elif not url: st = "MISSING_URL"
    else:
        sc = urlsplit(url).scheme
        if sc not in ("http", "https", "android"): st = "INVALID_URL"
        elif len(url) > 2048: st = "LONG_URL"
    statuses[st] = statuses.get(st, 0) + 1
print("chromium transcription statuses:", statuses)
# The fields Chromium extracts must equal ours (url, username, password, note), row for row.
mismatch = 0
for rec, exp in zip(parsed_rows, expected):
    if (rec.get("origin"), rec.get("username"), rec.get("password"), rec.get("note")) != (exp[1], exp[2], exp[3], exp[4]):
        mismatch += 1
        if mismatch < 4: print("  differs:", repr(rec)[:200], "vs", repr(exp)[:200])
print(f"chromium transcription: {len(parsed_rows)} rows parsed, {mismatch} field mismatches")
print(f"file: {len(raw)} bytes; Chrome limits: 3000 rows, 1,024,000 bytes -> rows over limit: {len(parsed_rows) > 3000}")
