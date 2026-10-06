#!/usr/bin/env bash
# Makes the Python venv of tools/validate under /home/asus/pvlab/validate/venv (outside the repository and
# outside /tmp) and installs requirements.txt into it with hash checking. Safe to run again, and by several
# agents at once (they take turns on a lock file). Set PV_VALIDATE_HOME to use another directory.
#
# This machine's Python has no ensurepip, so the venv is made without pip and the system's pip installs into
# it (pip --python, pip 22.3 or newer). The venv does not see the system's site-packages.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${PV_VALIDATE_HOME:-/home/asus/pvlab/validate}"
VENV="$BASE/venv"
MARK="$VENV/pv-requirements.txt"

mkdir -p "$BASE"
exec 9>"$BASE/.setup.lock"
flock 9

if [ -x "$VENV/bin/python" ] && cmp -s "$HERE/requirements.txt" "$MARK"; then
  echo "venv up to date: $VENV"
else
  python3 -m pip --version >/dev/null 2>&1 || { echo "setup.sh: python3 -m pip is not available" >&2; exit 2; }
  echo "making the venv: $VENV"
  rm -rf "$VENV"
  python3 -m venv --without-pip "$VENV"
  python3 -m pip --python "$VENV/bin/python" install --quiet --no-cache-dir --disable-pip-version-check \
    --require-hashes --only-binary=:all: -r "$HERE/requirements.txt"
  cp "$HERE/requirements.txt" "$MARK"   # written last: a run cut short is made again next time
fi
"$VENV/bin/python" -c 'import sys, openpyxl; print("python", sys.version.split()[0], "| openpyxl", openpyxl.__version__)'
if command -v soffice >/dev/null 2>&1; then
  soffice --version 2>/dev/null | head -n 1
else
  echo "setup.sh: LibreOffice (soffice) is not installed; run.sh needs it for the Excel checks" >&2
  exit 2
fi
