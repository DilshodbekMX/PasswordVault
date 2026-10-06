"""Compares the three exports of one vault with each other: they must hold the same accounts.

Each format is reduced to (title, username, password) tuples, empty and null read as "":
- JSON: one tuple per item, from name, login.username and login.password;
- Excel: one tuple per row under the header, from the first three columns (formats.md §6.2: Name,
  Username, Password), as LibreOffice read them;
- CSV: one tuple per row, from name, username and password. The CSV has one row per website
  (formats.md §4.4), so it is compared with the JSON items repeated once per website, or once when an
  item has none.
The tuples must be equal as multisets. Websites, notes and fields are not compared here, because each
format writes them differently (formats.md §4.4, §5.2, §6.2); the per-format checks cover them.

Run it only on files exported together from one vault. Usage: through run.sh, or
compare_formats.py [--show-values] [--csv PART.csv ...] [--json FILE.json] [--lo LIBREOFFICE.csv]
"""
import argparse
import csv
import io
import json
import sys
from collections import Counter

from common import Report


def from_json(items):
    out, per_site = [], []
    for it in items:
        lg = it.get("login") or {}
        t = (it.get("name") or "", lg.get("username") or "", lg.get("password") or "")
        out.append(t)
        per_site += [t] * max(1, len(lg.get("uris") or []))
    return out, per_site


def from_rows(rows, cols=(0, 1, 2)):
    return [tuple((r[c] if c < len(r) else "") or "" for c in cols) for r in rows]


def compare_pair(a, b, a_name, b_name, report):
    ca, cb = Counter(a), Counter(b)
    only_a, only_b = ca - cb, cb - ca
    details = []
    if len(a) != len(b):
        details.append(f"{len(a)} in {a_name}, {len(b)} in {b_name}")
    for label, extra in ((a_name, only_a), (b_name, only_b)):
        for t, n in list(extra.items())[:4]:
            shown = repr(t)[:150] if report.show_values else f"title of {len(t[0])}, username of {len(t[1])}, password of {len(t[2])} characters"
            details.append(f"only in {label} ({n}×): {shown}")
    report.check(not only_a and not only_b, f"{a_name} and {b_name} hold the same titles, usernames and passwords  [{len(a)}]", details)


def compare(report, csv_rows=None, json_items=None, lo_rows=None):
    """csv_rows: data rows of one CSV export (all parts); json_items: the JSON items; lo_rows: the
    workbook as LibreOffice read it, header included."""
    j, j_sites = from_json(json_items) if json_items is not None else (None, None)
    x = from_rows(lo_rows[1:]) if lo_rows is not None else None
    c = from_rows(csv_rows, (0, 2, 3)) if csv_rows is not None else None
    done = 0
    if j is not None and x is not None:
        compare_pair(j, x, "the JSON", "the Excel file", report)
        done += 1
    if j is not None and c is not None:
        compare_pair(j_sites, c, "the JSON (once per website)", "the CSV", report)
        done += 1
    if j is None and x is not None and c is not None:
        report.info("no JSON: the CSV and the Excel file are compared by title, username and password only, "
                    "ignoring how often each occurs")
        compare_pair(sorted(set(c)), sorted(set(x)), "the CSV", "the Excel file", report)
        done += 1
    if not done:
        report.skip("fewer than two formats: nothing to compare")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", nargs="*")
    ap.add_argument("--json")
    ap.add_argument("--lo")
    ap.add_argument("--show-values", action="store_true")
    a = ap.parse_args()
    rows = None
    if a.csv:
        rows = []
        for f in a.csv:
            rows += list(csv.reader(io.open(f, encoding="utf-8-sig", newline="")))[1:]
    items = json.load(open(a.json, encoding="utf-8"))["items"] if a.json else None
    lo = list(csv.reader(io.open(a.lo, encoding="utf-8", newline=""))) if a.lo else None
    r = Report(a.show_values)
    compare(r, rows, items, lo)
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
