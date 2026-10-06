#!/bin/bash
# Re-runs the prototype tests and every external check. Needs: JDK 25, LibreOffice 24.2 with python3-uno,
# the venv (openpyxl, msoffcrypto-tool) and the POI jars in ../poi.
set -u
cd "$(dirname "$0")/.."
D=$PWD
export JAVA_HOME=/home/asus/.jdks/jdk-25
echo "== Kotlin prototype tests"; kt/build.sh && $JAVA_HOME/bin/java -cp "$(cat kt/classpath.txt)" FormatsTestKt out | tail -16
echo "== CSV: Python csv + Chromium transcription"; venv/bin/python validate/check_csv.py out
echo "== JSON structure"; venv/bin/python validate/check_json.py out src/proton/bitwarden-mock.json | head -5
echo "== XLSX: openpyxl"; venv/bin/python validate/check_xlsx.py out
echo "== XLSX: LibreOffice --convert-to csv"; rm -f lo/export.csv
timeout 300 soffice -env:UserInstallation=file://$D/lo-profile --headless --norestore --convert-to 'csv:Text - txt - csv (StarCalc):44,34,76,1' --outdir lo out/export.xlsx >/dev/null 2>&1
venv/bin/python validate/compare_cells.py out csv lo/export.csv
echo "== Protected XLSX: msoffcrypto-tool + olefile"; venv/bin/python validate/check_protected.py out src/msoffcrypto/msoffcrypto-tool-HEAD/tests/inputs/example_password.xlsx
echo "== Protected XLSX: LibreOffice (UNO, password)"
PIPE=pvformats_check_$$
soffice -env:UserInstallation=file://$D/lo-profile --headless --norestore --nologo --nodefault --accept="pipe,name=$PIPE;urp;" >/dev/null 2>&1 &
/usr/bin/python3 validate/lo_read.py $PIPE $D/out/export-protected.xlsx lo/protected-right.json "tulip-arena-swirl-cobalt-mango"
/usr/bin/python3 validate/lo_read.py $PIPE $D/out/export-protected.xlsx lo/protected-wrong.json "wrong password"
/usr/bin/python3 validate/lo_read.py $PIPE $D/out/special-protected-unicode-pw.xlsx lo/unicode-pw.json "Parol‘ Пароль 2026"
/usr/bin/python3 validate/lo_read.py $PIPE $D/out/empty-protected.xlsx lo/empty-protected.json "tulip-arena-swirl-cobalt-mango"
/usr/bin/python3 validate/lo_read.py $PIPE $D/out/tampered-protected.xlsx lo/tampered.json "tulip-arena-swirl-cobalt-mango"
venv/bin/python validate/compare_cells.py out uno lo/protected-right.json
for pid in $(pgrep -f "[s]office.bin.*$PIPE"); do kill $pid; done; sleep 1
echo "== Protected XLSX: Apache POI 5.5.1"
CP=$(ls $D/poi/*.jar | tr "\n" ":")
(cd poi && $JAVA_HOME/bin/java -cp "$CP:." PoiCheck ../out/export-protected.xlsx poi-protected.json tulip-arena-swirl-cobalt-mango 2>&1 | grep POI
 $JAVA_HOME/bin/java -cp "$CP:." PoiCheck ../out/export-protected.xlsx poi-wrong.json "wrong password" 2>&1 | grep "POI: password")
venv/bin/python validate/compare_cells.py out uno poi/poi-protected.json | tail -1
