"""Reads export.xlsx with openpyxl and compares every cell with what the exporter meant to show.
Also checks the sheet setup (frozen header, bold header, widths, text format, print titles) and
lists the zip parts. openpyxl decodes _xHHHH_ escapes in shared strings (openpyxl/reader/strings.py)."""
import json, sys, zipfile, datetime
import openpyxl

out = sys.argv[1]
path = f"{out}/{sys.argv[2] if len(sys.argv) > 2 else 'export.xlsx'}"
exp = json.load(open(f"{out}/expected-xlsx-cells.json", encoding="utf-8"))
z = zipfile.ZipFile(path)
print("parts:", [i.filename for i in z.infolist()])
wb = openpyxl.load_workbook(path)
ws = wb.active
print("sheet:", ws.title, "dims:", ws.dimensions, "freeze:", ws.freeze_panes, "print titles:", ws.print_title_rows,
      "autofilter:", ws.auto_filter.ref)
print("header bold:", all(c.font.b for c in ws[1]), "widths:", [ws.column_dimensions[c].width for c in "ABCDEFGH"])
print("data number format B2:", ws["B2"].number_format, "date format H2:", ws["H14"].number_format)
bad = 0; checked = 0; formulas = 0
tz = datetime.timezone(datetime.timedelta(hours=5))  # Asia/Tashkent, no DST
for r, erow in enumerate(exp, start=1):
    for c, ev in enumerate(erow, start=1):
        cell = ws.cell(row=r, column=c)
        v = cell.value
        if cell.data_type == "f":
            formulas += 1
        if ev == "":
            ok = v is None
        elif ev.startswith("date:"):
            want = datetime.datetime.fromisoformat(ev[5:].replace("Z", "+00:00")).astimezone(tz).replace(tzinfo=None)
            ok = isinstance(v, datetime.datetime) and abs((v - want).total_seconds()) < 1
        elif ev.startswith("truncated:"):
            ok = isinstance(v, str) and len(v) <= 32767 and v.startswith(ev[10:]) and "more characters" in v[-120:]
        else:
            ok = v == ev
        checked += 1
        if not ok:
            bad += 1
            if bad <= 5:
                print(f"  R{r}C{c}: got {v!r:.120} expected {ev!r:.120}")
print(f"openpyxl: {checked} cells checked, {bad} differ, {formulas} formula cells")
