#!/usr/bin/env bash
# Checks the plain exports of one vault: the CSV (one file or its parts), the JSON and the Excel file.
#
#   tools/validate/run.sh [--allow-missing] [--no-cross] [--show-values] DIR
#   tools/validate/run.sh [options] FILE...
#
# DIR holds the files of one export; or name the files. Prints PASS, FAIL, INFO or SKIP per check and a
# RESULT line. Exit status: 0 all passed, 1 a check failed, 2 the files could not be checked.
# Values are never printed unless --show-values is given (exports hold passwords). See README.md.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${PV_VALIDATE_HOME:-/home/asus/pvlab/validate}"
if [ ! -x "$BASE/venv/bin/python" ] || ! cmp -s "$HERE/requirements.txt" "$BASE/venv/pv-requirements.txt"; then
  "$HERE/setup.sh" >&2 || exit 2
fi
export PYTHONDONTWRITEBYTECODE=1 PYTHONIOENCODING=utf-8
exec "$BASE/venv/bin/python" "$HERE/validate.py" "$@"
