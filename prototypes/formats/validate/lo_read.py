"""Opens a spreadsheet in a headless LibreOffice through UNO (optionally with a password) and dumps
the first sheet's used range as JSON: text cells as their string, number cells as "num:<value>",
formula cells as "formula:<formula>". Usage: lo_read.py PIPE FILE OUT.json [PASSWORD]"""
import json, sys, time
import uno
from com.sun.star.beans import PropertyValue

pipe, path, out = sys.argv[1], sys.argv[2], sys.argv[3]
password = sys.argv[4] if len(sys.argv) > 4 else None

def prop(n, v):
    p = PropertyValue(); p.Name = n; p.Value = v; return p

local = uno.getComponentContext()
resolver = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
for _ in range(60):
    try:
        ctx = resolver.resolve(f"uno:pipe,name={pipe};urp;StarOffice.ComponentContext"); break
    except Exception:
        time.sleep(1)
desktop = ctx.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
props = [prop("Hidden", True), prop("ReadOnly", True)]
if password is not None:
    props.append(prop("Password", password))
t = time.time()
try:
    doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(path), "_blank", 0, tuple(props))
except Exception as e:
    doc = None; err = repr(e)
if doc is None:
    json.dump({"loaded": False, "error": locals().get("err", "loadComponentFromURL returned None")}, open(out, "w"))
    print("not loaded"); sys.exit(0)
sheet = doc.Sheets.getByIndex(0)
cur = sheet.createCursor(); cur.gotoEndOfUsedArea(False)
rows, cols = cur.RangeAddress.EndRow + 1, cur.RangeAddress.EndColumn + 1
data = sheet.getCellRangeByPosition(0, 0, cols - 1, rows - 1)
formulas = data.getFormulaArray()
values = data.getDataArray()
res = []
for r in range(rows):
    row = []
    for c in range(cols):
        f = formulas[r][c]; v = values[r][c]
        if isinstance(f, str) and f.startswith("=") and not isinstance(v, str):
            row.append("formula:" + f)
        elif isinstance(v, float):
            row.append("num:" + repr(v))
        else:
            row.append(v)
    res.append(row)
json.dump({"loaded": True, "seconds": round(time.time() - t, 2), "sheet": sheet.Name, "rows": res}, open(out, "w"), ensure_ascii=False)
doc.close(True)
print(f"loaded {rows}x{cols} in {time.time() - t:.1f}s")
