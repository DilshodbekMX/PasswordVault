"""Opens each locked workbook in a running headless LibreOffice (UNO pipe) with a password, dumps all cells
as UTF-16 code units, and also tries a wrong password. Usage: uno_open.py PIPE PASSWORD OUTDIR FILE..."""
import json, sys, time
import uno
from com.sun.star.beans import PropertyValue

pipe, pw, outdir, files = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
def prop(n, v):
    p = PropertyValue(); p.Name = n; p.Value = v; return p
local = uno.getComponentContext()
res = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
for _ in range(90):
    try:
        ctx = res.resolve(f"uno:pipe,name={pipe};urp;StarOffice.ComponentContext"); break
    except Exception:
        time.sleep(1)
desktop = ctx.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
def load(path, password):
    try:
        return desktop.loadComponentFromURL(uno.systemPathToFileUrl(path), "_blank", 0,
            (prop("Hidden", True), prop("ReadOnly", True), prop("Password", password)))
    except Exception as e:
        print("   load error:", repr(e)[:300]); return None
for f in files:
    t = time.time(); doc = load(f, pw)
    if doc is None:
        print(f"{f.rsplit('/',1)[-1]}: NOT LOADED with the right password"); continue
    sh = doc.Sheets.getByIndex(0); cur = sh.createCursor(); cur.gotoEndOfUsedArea(False)
    rows, cols = cur.RangeAddress.EndRow + 1, cur.RangeAddress.EndColumn + 1
    rng = sh.getCellRangeByPosition(0, 0, cols - 1, rows - 1)
    data, forms = rng.getDataArray(), rng.getFormulaArray()
    nform = sum(1 for r in range(rows) for c in range(cols) if isinstance(forms[r][c], str) and forms[r][c].startswith("=") and not isinstance(data[r][c], str))
    cells = [[[ord(ch) for ch in (v if isinstance(v, str) else "#" + repr(v)).encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")] for v in row] for row in data]
    # store as UTF-16 code units
    def units(v):
        b = (v if isinstance(v, str) else "#" + repr(v)).encode("utf-16-le", "surrogatepass")
        return [b[i] | (b[i + 1] << 8) for i in range(0, len(b), 2)]
    json.dump([[units(v) for v in row] for row in data], open(f"{outdir}/{f.rsplit('/',1)[-1]}.json", "w"))
    doc.close(True)
    secs = time.time() - t
    wrong = load(f, pw + "x")
    if wrong is not None: wrong.close(True)
    print(f"{f.rsplit('/',1)[-1]}: loaded {rows}x{cols} in {secs:.1f}s, formula cells {nform}, wrong password {'ACCEPTED' if wrong is not None else 'refused'}")
