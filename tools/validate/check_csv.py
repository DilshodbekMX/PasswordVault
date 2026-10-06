"""Checks one CSV export (a single file, or its parts in order) against formats.md §4.

1. The bytes are exactly the layout of formats.md §4.4: UTF-8 without a BOM, the header
   name,url,username,password,note, CRLF after every row, a value quoted when it contains , " CR or LF or
   starts or ends with a space or tab, inner quotes doubled, nothing else quoted.
2. Python's csv module and a line-by-line transcription of Chromium's importer (ConsumeCSVLine,
   CSVFieldParser, CSVPassword, CSVPasswordSequence and the length and URL checks of password_importer.cc,
   read at HEAD on 2026-10-06) read the same url, username, password and note from every row. The rows
   Chrome would refuse are listed by reason; that is information for the summary card, not a failure.
3. Every part stays within Chrome's limits (3,000 rows, 1,000 KB) and no entry's rows straddle two parts.
4. With expected-csv-rows.json (a list of rows, header excluded, all parts together), the rows equal it.

Ported from prototypes/formats/validate/check_csv.py.
Usage: check_csv.py [--expected expected-csv-rows.json] [--show-values] FILE.csv [PART2.csv ...]
"""
import argparse
import csv
import io
import json
import os
import sys
from urllib.parse import urlsplit

from common import Report

HEADER = ["name", "url", "username", "password", "note"]
MAX_ROWS, MAX_BYTES = 3000, 1000 * 1024


def quoted(v):
    if v and (any(c in v for c in ',"\r\n') or v[0] in " \t" or v[-1] in " \t"):
        return '"' + v.replace('"', '""') + '"'
    return v


def layout(rows):
    return "".join(",".join(quoted(v) for v in r) + "\r\n" for r in rows)


# ---- Chromium's reader, transcribed (components/password_manager/core/browser/import/)
def consume_csv_line(s, pos):
    inside_quotes = False
    last_cr = False
    i = pos
    while i < len(s):
        c = s[i]
        if c == "\n" and not inside_quotes:
            end = i - 1 if last_cr else i
            return s[pos:end], i + 1
        if c == '"':
            inside_quotes = not inside_quotes
        last_cr = c == "\r"
        i += 1
    return s[pos:], len(s)


def parse_fields(row):
    """CSVFieldParser: states init, plain, quoted, after; returns the fields, or None on a syntax error."""
    fields = []
    pos = 0
    n = len(row)
    while True:
        state = "init"
        start = pos
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
          "username": "username", "user": "username", "login": "username", "account": "username",
          "login_username": "username", "password": "password", "login_password": "password",
          "note": "note", "notes": "note", "comment": "note", "comments": "note"}


def chromium_read(raw):
    """Returns (records, statuses, syntax_errors): what Chrome's importer extracts from each row."""
    s = raw.decode("utf-8", errors="surrogateescape")  # Chromium works on bytes; lengths are UTF-8 bytes
    header, pos = consume_csv_line(s, 0)
    cols = parse_fields(header) or []
    colmap = {}
    for i, name in enumerate(cols):
        lab = LABELS.get(name.strip(" \t\r\n").lower())
        if lab:
            colmap[i] = lab
    records, statuses, syntax = [], {}, 0
    while pos < len(s):
        line, pos = consume_csv_line(s, pos)
        line = line.lstrip("\r \t")  # TRIM_LEADING of "\r \t"
        if not line:
            continue
        f = parse_fields(line)
        if f is None:
            syntax += 1
            continue
        rec = {colmap[i]: v for i, v in enumerate(f) if i in colmap}
        records.append(rec)
        pw, user, note, url = (rec.get(k, "") for k in ("password", "username", "note", "origin"))
        st = "OK"
        if not pw:
            st = "MISSING_PASSWORD"
        elif len(pw.encode("utf-8", "surrogateescape")) > 1000:
            st = "LONG_PASSWORD"
        elif len(user.encode("utf-8", "surrogateescape")) > 1000:
            st = "LONG_USERNAME"
        elif len(note.encode("utf-8", "surrogateescape")) > 1000:
            st = "LONG_NOTE"
        elif not url:
            st = "MISSING_URL"
        else:
            scheme = urlsplit(url).scheme
            if scheme not in ("http", "https", "android"):
                st = "INVALID_URL"
            elif len(url) > 2048:
                st = "LONG_URL"
        statuses[st] = statuses.get(st, 0) + 1
    return sorted(colmap.values()), records, statuses, syntax


def check(files, report, expected=None):
    """Checks one export; returns its data rows (header excluded, all parts together), or None."""
    names = ", ".join(os.path.basename(f) for f in files)
    all_rows, part_rows = [], []
    for f in files:
        name = os.path.basename(f)
        raw = open(f, "rb").read()
        if not report.check(not raw.startswith(b"\xef\xbb\xbf"), f"{name}: no byte order mark"):
            raw = raw[3:]
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as e:
            report.fail(f"{name}: valid UTF-8", [f"invalid byte at offset {e.start}"])
            return None
        rows = list(csv.reader(io.StringIO(text, newline="")))
        if not report.check(bool(rows) and rows[0] == HEADER, f"{name}: header is {','.join(HEADER)}",
                            [f"got {rows[0] if rows else 'an empty file'}"]):
            return None
        bad = [i for i, r in enumerate(rows[1:], start=1) if len(r) != 5]
        report.check(not bad, f"{name}: every row has 5 values", [f"row {i}: {len(rows[i])} values" for i in bad])
        if text == layout(rows):
            report.ok(f"{name}: bytes are exactly the layout of formats.md §4.4 (quoting, CRLF, no BOM)  [{len(rows) - 1} rows]")
        else:
            off, where = 0, None
            for i, r in enumerate(rows):
                line = layout([r])
                if text[off:off + len(line)] != line:
                    where = i
                    break
                off += len(line)
            report.fail(f"{name}: bytes are exactly the layout of formats.md §4.4 (quoting, CRLF, no BOM)",
                        [f"first difference in row {where} (0 is the header), at character {off} of the file"
                         if where is not None else "the file has bytes after its last row"])
        cols, recs, statuses, syntax = chromium_read(raw)
        report.check(cols == ["note", "origin", "password", "username"],
                     f"{name}: Chromium finds the url, username, password and note columns", [f"found {cols}"])
        report.check(syntax == 0, f"{name}: Chromium's reader parses every row", [f"{syntax} rows refused as syntax errors"])
        mism = []
        for i, (rec, r) in enumerate(zip(recs, rows[1:]), start=1):
            for key, col in (("origin", 1), ("username", 2), ("password", 3), ("note", 4)):
                if rec.get(key, "") != r[col]:
                    mism.append(f"row {i}, {HEADER[col]}: {report.differ(rec.get(key, ''), r[col])}")
        if len(recs) != len(rows) - 1:
            mism.insert(0, f"Chromium read {len(recs)} rows, Python's csv {len(rows) - 1}")
        report.check(not mism, f"{name}: Python's csv and Chromium's reader read the same url, username, password and note", mism)
        refused = {k: v for k, v in statuses.items() if k != "OK"}
        report.info(f"{name}: Chrome would import {statuses.get('OK', 0)} rows"
                    + (f" and refuse {sum(refused.values())}: {refused}" if refused else ""))
        report.check(len(rows) - 1 <= MAX_ROWS and len(raw) <= MAX_BYTES,
                     f"{name}: within Chrome's limits of 3,000 rows and 1,000 KB",
                     [f"{len(rows) - 1} rows, {len(raw)} bytes; the export must be split into parts (formats.md §4.4)"])
        part_rows.append(rows[1:])
        all_rows += rows[1:]
    if len(files) > 1:
        straddle = [f"between {os.path.basename(files[k])} and {os.path.basename(files[k + 1])}"
                    for k in range(len(part_rows) - 1)
                    if part_rows[k] and part_rows[k + 1]
                    and [part_rows[k][-1][i] for i in (0, 2, 3, 4)] == [part_rows[k + 1][0][i] for i in (0, 2, 3, 4)]]
        report.check(not straddle, f"{names}: no entry's rows straddle two parts", straddle)
    if expected:
        exp = json.load(open(expected, encoding="utf-8"))
        diffs = [f"row {i}: {report.differ(','.join(g), ','.join(e))}"
                 for i, (g, e) in enumerate(zip(all_rows, exp), start=1) if g != e]
        if len(all_rows) != len(exp):
            diffs.insert(0, f"{len(all_rows)} rows, expected {len(exp)}")
        report.check(not diffs, f"{names}: rows equal {os.path.basename(expected)}", diffs)
    return all_rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="+")
    ap.add_argument("--expected")
    ap.add_argument("--show-values", action="store_true")
    a = ap.parse_args()
    r = Report(a.show_values)
    check(a.files, r, a.expected)
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
