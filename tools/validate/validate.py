"""Checks the plain exports of one vault: the CSV (one file or its parts), the JSON and the Excel file.
Called by run.sh, which supplies the venv; see README.md.

Arguments: one directory, or the files themselves. Files are told apart by name and content:
- *.csv: the CSV export; NAME-part1.csv, NAME-part2.csv, … are the parts of one export, in that order;
- *.json: the JSON export; expected-csv-rows.json and expected-xlsx-cells.json are not exports but the
  values a writer's test meant to write, and are compared when present;
- *.xlsx: the Excel export when it is a zip package; a locked workbook (a compound file) is not checked here
  (architecture.md D-16 keeps it out of version 1; prototypes/formats/validate/ has its checks).
A directory must hold one export of each format; several CSV names, JSON files or workbooks are refused as
ambiguous, so that two exports are never mixed up.

Exit status: 0 every check passed; 1 a check failed; 2 the files could not be checked (missing,
ambiguous, LibreOffice or openpyxl not available).
"""
import argparse
import os
import re
import sys
import tempfile

import check_csv
import check_json
import check_xlsx
import compare_cells
import compare_formats
import lo_convert
from common import PROTON_FIXTURE, Report

EXPECTED = ("expected-csv-rows.json", "expected-xlsx-cells.json")


def classify(paths):
    found = {"csv": {}, "json": [], "xlsx": [], "locked": [], "expected": {}, "other": []}
    for p in paths:
        name = os.path.basename(p)
        low = name.lower()
        if name in EXPECTED:
            found["expected"][name] = p
        elif low.endswith(".csv"):
            m = re.match(r"(.*)-part(\d+)\.csv$", name, re.I)
            base, part = (m.group(1), int(m.group(2))) if m else (name[:-4], 0)
            found["csv"].setdefault(base, []).append((part, p))
        elif low.endswith(".json"):
            found["json"].append(p)
        elif low.endswith(".xlsx"):
            with open(p, "rb") as f:
                head = f.read(4)
            (found["xlsx"] if head == b"PK\x03\x04" else found["locked"]).append(p)
        else:
            found["other"].append(p)
    return found


def csv_exports(groups):
    """Each CSV name gives one export of parts (in part order) and, if present, one whole file."""
    exports, problems = [], []
    for base, files in sorted(groups.items()):
        whole = [p for n, p in files if n == 0]
        parts = sorted((n, p) for n, p in files if n > 0)
        if parts:
            numbers = [n for n, _ in parts]
            if numbers != list(range(1, len(parts) + 1)):
                problems.append(f"the parts of {base} are numbered {numbers}, not 1 to {len(parts)}")
            exports.append([p for _, p in parts])
        exports += [[p] for p in whole]
    return exports, problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="a directory, or the export files")
    ap.add_argument("--allow-missing", action="store_true", help="check what is there when a format is missing")
    ap.add_argument("--no-cross", action="store_true", help="do not compare the formats with each other")
    ap.add_argument("--show-values", action="store_true", help="print the values that differ (they may be passwords)")
    ap.add_argument("--fixture", default=PROTON_FIXTURE, help="Proton's Bitwarden fixture for the key comparison")
    a = ap.parse_args()

    if len(a.paths) == 1 and os.path.isdir(a.paths[0]):
        d = a.paths[0]
        paths = [os.path.join(d, n) for n in sorted(os.listdir(d)) if os.path.isfile(os.path.join(d, n))]
    else:
        paths = a.paths
        missing = [p for p in paths if not os.path.isfile(p)]
        if missing:
            sys.exit(f"not a file: {missing[0]}")
    found = classify(paths)
    exports, problems = csv_exports(found["csv"])
    if len(found["csv"]) > 1:
        problems.append(f"CSV exports of several names: {sorted(found['csv'])}")
    if len(found["json"]) > 1:
        problems.append(f"several JSON files: {[os.path.basename(p) for p in found['json']]}")
    if len(found["xlsx"]) > 1:
        problems.append(f"several workbooks: {[os.path.basename(p) for p in found['xlsx']]}")
    missing = [f for f, have in (("CSV", exports), ("JSON", found["json"]), ("Excel", found["xlsx"])) if not have]
    if missing and not a.allow_missing:
        problems.append(f"no {', no '.join(missing)} export (use --allow-missing to check the others)")
    if problems:
        print("Cannot check these files:\n  " + "\n  ".join(problems), file=sys.stderr)
        print("Give one directory per export, or name the files.", file=sys.stderr)
        sys.exit(2)

    r = Report(a.show_values)
    for p in found["locked"]:
        r.info(f"{os.path.basename(p)} is a locked workbook: not checked here (architecture.md D-16)")
    for p in found["other"]:
        r.info(f"{os.path.basename(p)} ignored")
    for f in missing:
        r.skip(f"no {f} export")

    csv_rows = []
    for files in exports:
        r.section("CSV: " + ", ".join(os.path.basename(f) for f in files))
        csv_rows.append(check_csv.check(files, r, found["expected"].get("expected-csv-rows.json")))
    items = None
    if found["json"]:
        r.section("JSON: " + os.path.basename(found["json"][0]))
        items = check_json.check(found["json"][0], r, a.fixture)
    lo_rows = None
    if found["xlsx"]:
        x = found["xlsx"][0]
        r.section("Excel: " + os.path.basename(x))
        op_rows = check_xlsx.check(x, r)
        with tempfile.TemporaryDirectory(prefix="pv-validate-") as tmp:
            try:
                lo_rows = compare_cells.read_lo(lo_convert.convert(x, tmp))
                r.ok(f"{os.path.basename(x)}: LibreOffice opens it  [{len(lo_rows)} rows]")
            except Exception as e:  # LibreOffice missing, timed out or refused the file
                r.fail(f"{os.path.basename(x)}: LibreOffice opens it", [str(e)[:300]])
        if lo_rows is not None:
            compare_cells.compare(lo_rows, op_rows, r, found["expected"].get("expected-xlsx-cells.json"), os.path.basename(x))

    r.section("The formats against each other")
    if a.no_cross:
        r.skip("not compared (--no-cross)")
    else:
        for rows in csv_rows or [None]:
            compare_formats.compare(r, rows, items, lo_rows)

    print(f"\nRESULT: {'FAIL' if r.failed else 'PASS'}  ({r.passed} passed, {r.failed} failed, {r.skipped} skipped)")
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
