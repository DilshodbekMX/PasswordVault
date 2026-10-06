"""Validates every XML part of .xlsx files against the ECMA-376 5th edition schemas:
Transitional SpreadsheetML (Part 4, sml.xsd) for workbook, worksheet, styles and sharedStrings,
and the OPC schemas (Part 2) for [Content_Types].xml and the .rels parts.
Also checks OPC rules the schemas cannot express (every Override names an existing part, every
relationship target exists, every part has a content type).
Usage: xsd_validate.py FILE.xlsx [...]"""
import sys, zipfile, posixpath
from lxml import etree

ECMA = sys.path[0] + "/../src/ecma"
_cache = {}


def schema(path):
    if path not in _cache:
        _cache[path] = etree.XMLSchema(etree.parse(path))
    return _cache[path]


SML = f"{ECMA}/xsd-t/sml.xsd"
CT = f"{ECMA}/opc/opc-contentTypes.xsd"
RELS = f"{ECMA}/opc/opc-relationships.xsd"


def validate(path):
    z = zipfile.ZipFile(path)
    names = z.namelist()
    problems = []
    for n in names:
        data = z.read(n)
        if n == "[Content_Types].xml":
            xsd = CT
        elif n.endswith(".rels"):
            xsd = RELS
        elif n.endswith(".xml"):
            xsd = SML
        else:
            continue
        doc = etree.fromstring(data)
        s = schema(xsd)
        if not s.validate(doc):
            for e in s.error_log:
                msg = f"{n}:{e.line}: {e.message}"
                # xml:space on <t> is written by Excel itself and allowed by ECMA-376 Part 1 §22.9.2.19 /
                # the XML namespace; XSD 1.0 validators flag it because ST_Xstring is a simple type.
                if "{http://www.w3.org/XML/1998/namespace}space" in e.message:
                    continue
                problems.append(msg)
    # OPC consistency checks
    ct = etree.fromstring(z.read("[Content_Types].xml"))
    ns = {"c": "http://schemas.openxmlformats.org/package/2006/content-types"}
    defaults = {d.get("Extension").lower(): d.get("ContentType") for d in ct.findall("c:Default", ns)}
    overrides = {o.get("PartName"): o.get("ContentType") for o in ct.findall("c:Override", ns)}
    for pn in overrides:
        if pn.lstrip("/") not in names:
            problems.append(f"[Content_Types].xml: Override for missing part {pn}")
    for n in names:
        if n == "[Content_Types].xml" or n.endswith("/"):
            continue
        if "/" + n not in overrides and n.rsplit(".", 1)[-1].lower() not in defaults:
            problems.append(f"{n}: no content type")
    for n in names:
        if not n.endswith(".rels"):
            continue
        base = posixpath.dirname(posixpath.dirname(n))  # _rels/x.rels -> source dir
        rels = etree.fromstring(z.read(n))
        for r in rels:
            if r.get("TargetMode") == "External":
                continue
            t = r.get("Target")
            full = posixpath.normpath(posixpath.join(base, t)).lstrip("/") if not t.startswith("/") else t.lstrip("/")
            if full not in names:
                problems.append(f"{n}: relationship {r.get('Id')} target {t} -> {full} missing")
    return names, problems


for p in sys.argv[1:]:
    if not zipfile.is_zipfile(p):
        continue  # the locked workbooks are compound files; their decrypted package is the plain .xlsx
    names, problems = validate(p)
    print(f"{p.rsplit('/', 1)[-1]}: {len(names)} parts, {len(problems)} schema/OPC problems")
    for m in problems[:15]:
        print("   ", m)
