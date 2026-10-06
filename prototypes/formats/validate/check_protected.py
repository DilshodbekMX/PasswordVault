"""Checks the password-protected .xlsx files with msoffcrypto-tool (decrypt + HMAC integrity check)
and olefile (container structure), and compares the fixed DataSpaces streams with an Excel-written
file (msoffcrypto-tool tests/inputs/example_password.xlsx)."""
import io, sys, olefile, msoffcrypto, struct

out = sys.argv[1]
sample = sys.argv[2]
cases = [("export-protected.xlsx", "export.xlsx", "tulip-arena-swirl-cobalt-mango"),
         ("special-protected-unicode-pw.xlsx", "special-small.xlsx", "Parol‘ Пароль 2026"),
         ("empty-protected.xlsx", "empty.xlsx", "tulip-arena-swirl-cobalt-mango")]
for enc, plain, pw in cases:
    f = msoffcrypto.OfficeFile(open(f"{out}/{enc}", "rb"))
    f.load_key(password=pw, verify_password=True)
    buf = io.BytesIO()
    f.decrypt(buf, verify_integrity=True)
    same = buf.getvalue() == open(f"{out}/{plain}", "rb").read()
    print(f"msoffcrypto {enc}: password ok, integrity ok, decrypted == {plain}: {same}")
    try:
        g = msoffcrypto.OfficeFile(open(f"{out}/{enc}", "rb")); g.load_key(password=pw + "x", verify_password=True)
        print("  wrong password accepted?!")
    except Exception as e:
        print(f"  wrong password refused: {type(e).__name__}")

# Tamper with one byte of the encrypted package: the integrity check must fail.
data = bytearray(open(f"{out}/export-protected.xlsx", "rb").read())
ole = olefile.OleFileIO(bytes(data))
ep_start = ole.direntries[[e.name if e else None for e in ole.direntries].index("EncryptedPackage")].isectStart
off = 512 + ep_start * 512 + 100
data[off] ^= 1
g = msoffcrypto.OfficeFile(io.BytesIO(bytes(data))); g.load_key(password="tulip-arena-swirl-cobalt-mango")
try:
    g.decrypt(io.BytesIO(), verify_integrity=True); print("tampered file: integrity NOT detected")
except Exception as e:
    print(f"tampered file: integrity failure detected ({type(e).__name__})")
open(f"{out}/tampered-protected.xlsx", "wb").write(bytes(data))

# Structure versus Excel's sample
def tree(path):
    o = olefile.OleFileIO(path)
    names = sorted("/".join(p).encode("unicode_escape").decode() for p in o.listdir(streams=True, storages=True))
    return o, names
o1, n1 = tree(f"{out}/export-protected.xlsx")
o2, n2 = tree(sample)
print("our entries:  ", n1)
print("same entry names as Excel's file:", n1 == n2)
for s in ["\x06DataSpaces/Version", "\x06DataSpaces/DataSpaceMap",
          "\x06DataSpaces/DataSpaceInfo/StrongEncryptionDataSpace",
          "\x06DataSpaces/TransformInfo/StrongEncryptionTransform/\x06Primary"]:
    print(f"  {s.encode('unicode_escape').decode():70} identical: {o1.openstream(s).read() == o2.openstream(s).read()}")
ei = o1.openstream("EncryptionInfo").read()
print("EncryptionInfo version/flags:", struct.unpack("<HHI", ei[:8]), "xml bytes:", len(ei) - 8)
print("sector size", o1.sector_size, "mini cutoff", o1.minisectorcutoff, "dll version", o1.dll_version)
for e in o1.direntries:
    if e is not None:
        print(f"  {e.name.encode('unicode_escape').decode():28} type {e.entry_type} L {e.sid_left:>10} R {e.sid_right:>10} C {e.sid_child:>10} size {e.size}")
o3 = olefile.OleFileIO(f"{out}/empty-protected.xlsx")
ep = [e for e in o3.direntries if e and e.name == "EncryptedPackage"][0]
print("empty vault: EncryptedPackage size", ep.size, "(mini stream)" if ep.size < 4096 else "")
