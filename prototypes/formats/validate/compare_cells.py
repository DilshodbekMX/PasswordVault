"""Compares cells read by another program (LibreOffice CSV conversion, or the UNO dump) with
expected-xlsx-cells.json. Usage: compare_cells.py OUT csv FILE.csv | compare_cells.py OUT uno FILE.json"""
import csv, json, sys, io

out, kind, path = sys.argv[1], sys.argv[2], sys.argv[3]
exp = json.load(open(f"{out}/expected-xlsx-cells.json", encoding="utf-8"))
if kind == "csv":
    got = list(csv.reader(io.open(path, encoding="utf-8", newline="")))
else:
    d = json.load(open(path, encoding="utf-8"))
    if not d["loaded"]:
        print("NOT LOADED:", d.get("error")); sys.exit(0)
    print(f"UNO: sheet {d['sheet']!r}, load {d['seconds']} s")
    got = d["rows"]
print(f"rows: got {len(got)}, expected {len(exp)}")
bad = 0; checked = 0; formulas = 0; dates = 0
for r, erow in enumerate(exp):
    grow = got[r] if r < len(got) else []
    for c, ev in enumerate(erow):
        gv = grow[c] if c < len(grow) else ""
        if isinstance(gv, str) and gv.startswith("formula:"):
            formulas += 1
        if ev.startswith("date:"):
            dates += 1
            ok = gv not in ("", None)  # shown as a formatted date (csv) or a serial number (uno)
        elif ev.startswith("truncated:"):
            ok = gv.startswith(ev[10:]) and len(gv) <= 32767 and "more characters" in gv[-120:]
        else:
            ok = gv == ev
        checked += 1
        if not ok:
            bad += 1
            if bad <= 6:
                print(f"  R{r+1}C{c+1}: got {gv!r:.110} expected {ev!r:.110}")
print(f"{kind}: {checked} cells checked, {bad} differ, {formulas} formulas, {dates} date cells present")
if kind == "csv":
    print("sample date as LibreOffice shows it:", got[13][7] if len(got) > 13 else None)
