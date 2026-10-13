#!/usr/bin/env bash
# tools/emu/lock/delete_all_fingerprints.sh - deletes every enrolled fingerprint through the phone's
# Settings, unattended.
#
#   tools/emu/lock/delete_all_fingerprints.sh SERIAL
#
# Adapted from docs/lab/biometric-artifacts/delete_all_fingerprints.sh (a console port, uitap.py):
# tools/emu/lock/phone.sh's pvp_delete_all_fingers, through tools/emu/adb.sh (its pv_ui_tap in place of
# uitap.py). Made for the API 37 image's Pixel Settings, where E7 (a) runs; the Android 7 and 9 screens
# ("Finger N", then "DELETE") are tried as well. The device PIN 1111 is typed where it is asked for.
# Exit 0 when no fingerprint is left, 1 otherwise, 2 for a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/lock/phone.sh"

[ $# -ge 1 ] || pv_usage "${BASH_SOURCE[0]}"
pv_use "$1"
if pvp_delete_all_fingers; then
    echo "fingerprints left on $1: $(pvp_fingers)"
    exit 0
fi
echo "fingerprints left on $1: $(pvp_fingers)" >&2
exit 1
