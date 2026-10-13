#!/usr/bin/env bash
# tools/emu/i18n/all.sh - build plan 6.1's [script] lines on several private emulators at once: on each
# device tools/emu/i18n/matrix.sh (the device matrix of i18n.md §5 for its API level), then
# tools/emu/i18n/e11.sh (lock.md E11's language step). The devices run at the same time, the two
# scripts on one device one after the other: about 25 minutes per device.
#
#   tools/emu/i18n/all.sh [--apk FILE] SERIAL...
#
# The APK defaults to app/build/outputs/apk/debug/app-debug.apk. Prints each script's PASS, FAIL,
# SKIPPED and KNOWN lines per device, then one line per script and device and `SUMMARY i18n checks`;
# exit 0 when every script passed on every device. Logs under build/emu-i18n/all/.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
SERIALS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        -*) pv_usage "${BASH_SOURCE[0]}" ;;
        *) SERIALS+=("$1") ;;
    esac
    shift
done
[ ${#SERIALS[@]} -gt 0 ] || pv_usage "${BASH_SOURCE[0]}"
LOGS=$REPO/build/emu-i18n/all
mkdir -p "$LOGS"

for serial in "${SERIALS[@]}"; do
    pv_use "$serial" # refuses a serial of another worktree (exit 3) before anything starts
done

for serial in "${SERIALS[@]}"; do
    (
        "$EMU/i18n/matrix.sh" "$serial" --apk "$APK" > "$LOGS/$serial-matrix.log" 2>&1
        echo $? > "$LOGS/$serial-matrix.rc"
        "$EMU/i18n/e11.sh" "$serial" --apk "$APK" > "$LOGS/$serial-e11.log" 2>&1
        echo $? > "$LOGS/$serial-e11.rc"
    ) &
done
wait

failed=0
for serial in "${SERIALS[@]}"; do
    for script in matrix e11; do
        echo "== $script.sh $serial"
        grep -E '^(PASS|FAIL|SKIPPED|KNOWN|SUMMARY) ' "$LOGS/$serial-$script.log" | sed 's/^/   /'
    done
done
for serial in "${SERIALS[@]}"; do
    for script in matrix e11; do
        rc=$(cat "$LOGS/$serial-$script.rc" 2>/dev/null || echo 1)
        if [ "$rc" = 0 ]; then echo "PASS $script.sh $serial"; else echo "FAIL $script.sh $serial (exit $rc; $LOGS/$serial-$script.log)"; failed=1; fi
    done
done
echo "SUMMARY i18n checks: $([ $failed -eq 0 ] && echo passed || echo failed) on ${SERIALS[*]}"
exit $failed
