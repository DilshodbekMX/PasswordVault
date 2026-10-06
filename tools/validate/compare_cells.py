"""Compares the cells LibreOffice read from a workbook (lo_convert.py's CSV) with what the writer meant
(expected-xlsx-cells.json, when the writer's test wrote one) and with what openpyxl read.

expected-xlsx-cells.json is a list of rows, the header row first, each a list of strings; a cell that the
writer had to cut at Excel's 32,767 characters is "truncated:" followed by the first characters it kept.

LibreOffice decodes the _xHHHH_ escapes; openpyxl does not, and deletes "x005F_" (formats-review P17.5).
So LibreOffice must match exactly, and openpyxl may differ only in cells that hold such a character or
the text "_x"; those are counted, not failed.

Ported from prototypes/formats/validate/compare_cells.py.
Usage: compare_cells.py [--expected expected-xlsx-cells.json] [--show-values] LIBREOFFICE.csv FILE.xlsx
"""
import argparse
import csv
import io
import json
import os
import sys

from common import XML_FORBIDDEN, Report

EXCEL_CELL = 32767


def read_lo(path):
    return list(csv.reader(io.open(path, encoding="utf-8", newline="")))


def cell(rows, r, c):
    if r < len(rows) and c < len(rows[r]):
        v = rows[r][c]
        return "" if v is None else v if isinstance(v, str) else str(v)
    return ""


def escape_sensitive(v):
    return "_x" in v or any(ord(ch) in XML_FORBIDDEN for ch in v)


LONG_CELL = 16000  # UTF-16 units; LibreOffice 24.2 breaks longer text with a line feed at a space near 16,384


def lo_breaks(lo, ref):
    """If lo is ref with line feeds inserted, how many; otherwise -1. LibreOffice 24.2 read a 32,759-unit
    cell of the prototype's workbook with a line feed added at the space before unit 16,384, where openpyxl
    read the text as written; so in long cells such line feeds are counted, not failed."""
    if len(lo.encode("utf-16-le")) // 2 <= LONG_CELL:
        return -1
    i = j = n = 0
    while i < len(lo) and j < len(ref):
        if lo[i] == ref[j]:
            i += 1
            j += 1
        elif lo[i] == "\n":
            i += 1
            n += 1
        else:
            return -1
    while i < len(lo) and lo[i] == "\n":
        i += 1
        n += 1
    return n if i == len(lo) and j == len(ref) else -1


def same(got, want):
    if want.startswith("truncated:"):
        return got.startswith(want[10:]) and len(got) <= EXCEL_CELL and "more characters" in got[-120:]
    return got == want


def compare(lo_rows, openpyxl_rows, report, expected=None, name="workbook"):
    width = max([len(r) for r in lo_rows] + [len(r) for r in openpyxl_rows or []] + [0])
    breaks = 0
    if expected:
        exp = json.load(open(expected, encoding="utf-8"))
        width = max([width] + [len(r) for r in exp])
        diffs = [f"{len(lo_rows)} rows, expected {len(exp)}"] if len(lo_rows) != len(exp) else []
        for r in range(len(exp)):
            for c in range(width):
                got, want = cell(lo_rows, r, c), cell(exp, r, c)
                if same(got, want):
                    continue
                if lo_breaks(got, want) > 0:
                    breaks += 1
                else:
                    diffs.append(f"R{r + 1}C{c + 1}: {report.differ(got, want)}")
        report.check(not diffs, f"{name}: LibreOffice reads every cell as {os.path.basename(expected)} says"
                     f"  [{len(exp)} rows × {width} columns]", diffs)
        reference, ref_name = exp, os.path.basename(expected)
    else:
        reference, ref_name = lo_rows, "LibreOffice"
    if openpyxl_rows is None:
        return
    escapes, diffs = 0, []
    for r in range(max(len(reference), len(openpyxl_rows))):
        for c in range(width):
            want, got = cell(reference, r, c), cell(openpyxl_rows, r, c)
            if same(got, want):
                continue
            if escape_sensitive(want) or (want.startswith("truncated:") and escape_sensitive(want[10:])):
                escapes += 1
            elif reference is lo_rows and lo_breaks(want, got) > 0:
                breaks += 1
            else:
                diffs.append(f"R{r + 1}C{c + 1}: {report.differ(got, want)}")
    report.check(not diffs, f"{name}: openpyxl reads the same cells as {ref_name}"
                 + (f" except {escapes} cells with _xHHHH_ escapes, which openpyxl does not decode" if escapes else ""), diffs)
    if breaks:
        report.info(f"{name}: in {breaks} comparison(s) of a cell over {LONG_CELL} UTF-16 units, LibreOffice's reading "
                    "had line feeds added and was otherwise the same; that is LibreOffice's doing, not the file's")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("lo_csv")
    ap.add_argument("xlsx")
    ap.add_argument("--expected")
    ap.add_argument("--show-values", action="store_true")
    a = ap.parse_args()
    import openpyxl
    ws = openpyxl.load_workbook(a.xlsx).active
    op = [[c.value for c in row] for row in ws.iter_rows()]
    r = Report(a.show_values)
    compare(read_lo(a.lo_csv), op, r, a.expected, os.path.basename(a.xlsx))
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
