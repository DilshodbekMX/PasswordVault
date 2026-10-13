#!/usr/bin/env bash
# tools/emu/lock/enroll_fingerprint.sh - enrols one emulator finger through the phone's Settings,
# unattended, on API 24, 28 or 37.
#
#   tools/emu/lock/enroll_fingerprint.sh SERIAL [FINGER]       FINGER: an emulator finger id (default 1)
#
# Adapted from docs/lab/biometric-artifacts/enroll_fingerprint.sh (a console port, the API 37 screens
# only, the device PIN set there too) with what tools/emu/boot.sh --provision knows of the Android 7 and
# 9 screens: tools/emu/lock/phone.sh's pvp_enroll, through tools/emu/adb.sh. The device PIN (1111) must
# be set already (the bases have it; phone.sh's pvp_lock_set sets it again). "adb emu finger touch ID"
# later authenticates with the enrolled id; any other id is "not recognised".
# Exit 0 when the number of enrolled fingerprints went up, 1 when the enrolment was not confirmed, 2 for
# a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/lock/phone.sh"

[ $# -ge 1 ] || pv_usage "${BASH_SOURCE[0]}"
pv_use "$1"
before=$(pvp_fingers)
if pvp_enroll "${2:-1}"; then
    echo "finger ${2:-1} enrolled on $1: $before -> $(pvp_fingers) fingerprints"
    exit 0
fi
echo "the enrolment of finger ${2:-1} on $1 was not confirmed ($before -> $(pvp_fingers) fingerprints)" >&2
exit 1
