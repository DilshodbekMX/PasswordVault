"""The LibreOffice conversion: reads a workbook with a headless LibreOffice and writes its first sheet as CSV
(UTF-8, comma, double quotes), which compare_cells.py and compare_formats.py read.

Every run uses a new private profile in a temporary directory, so it neither touches nor waits for a
LibreOffice the user has open, and two runs at once do not share a profile.
Usage: lo_convert.py FILE.xlsx OUTDIR   (prints the path of the CSV it wrote)
"""
import os
import shutil
import subprocess
import sys
import tempfile

FILTER = "csv:Text - txt - csv (StarCalc):44,34,76,1"  # separator ',', text delimiter '"', UTF-8, from line 1


def convert(xlsx, outdir, timeout=300):
    soffice = shutil.which("soffice") or shutil.which("libreoffice")
    if not soffice:
        raise RuntimeError("LibreOffice (soffice) is not installed")
    profile = tempfile.mkdtemp(prefix="pv-validate-lo-")
    try:
        cmd = [soffice, f"-env:UserInstallation=file://{profile}", "--headless", "--norestore", "--nologo",
               "--convert-to", FILTER, "--outdir", outdir, os.path.abspath(xlsx)]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        out = os.path.join(outdir, os.path.splitext(os.path.basename(xlsx))[0] + ".csv")
        if not os.path.exists(out):
            raise RuntimeError(f"LibreOffice wrote no CSV (exit {p.returncode}): {(p.stderr or p.stdout).strip()[:300]}")
        return out
    finally:
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print(convert(sys.argv[1], sys.argv[2]))
