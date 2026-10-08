#!/usr/bin/env bash
# tools/emu/window/all.sh - build plan 3.12, [script]: the five window and input checks of
# architecture.md §8.4 "Windows and input", one after another on each device, for the debug and the
# staging build. This is what an integration check calls.
#
#   tools/emu/window/all.sh [--apk FILE]... [--only NAME[,NAME...]] SERIAL...
#       SERIAL...  private emulators of this worktree from tools/emu/boot.sh, API 28 and API 37 (the
#                  helper app needs API 26, so not the API 24 device). The devices run at the same
#                  time; on one device the scripts run one after another.
#       --apk FILE one APK of the app; may be given more than once. Without it: this worktree's
#                  app/build/outputs/apk/debug/app-debug.apk and .../staging/app-staging.apk
#                  (tools/gradle.sh :app:assembleDebug :app:assembleStaging).
#       --only     some of: keyboard, autofill, overlay, state, a11y (the default: all, in that order).
#
# The scripts, each of which can be run alone as SCRIPT SERIAL [--apk FILE] and says in its header
# what it checks and how: keyboard.sh (the keyboard's flags for every field), autofill.sh (no fill
# and no save request), overlay.sh (taps through another app's window), state.sh (nothing typed in
# the saved-instance state), a11y.sh (what accessibility services read of the secrets). The helper
# app (tools/emu/window/helper) is built once, through tools/gradle.sh, when it is missing or older
# than its sources. One device and one APK take about 11 minutes.
#
# Prints every script's output as one block when the script ends, then one line per script with its
# counts, and "SUMMARY window checks". Exit 0 when every script passed, 1 otherwise, 2 for a usage
# error, 3 for a refused serial. The logs stay under build/window/all/.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() { pv_usage "${BASH_SOURCE[0]}"; }

APKS=()
SERIALS=()
ONLY=keyboard,autofill,overlay,state,a11y
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || usage; APKS+=("$2"); shift ;;
        --only) [ $# -ge 2 ] || usage; ONLY=$2; shift ;;
        -*) usage ;;
        *) SERIALS+=("$1") ;;
    esac
    shift
done
[ ${#SERIALS[@]} -gt 0 ] || usage
[ ${#APKS[@]} -gt 0 ] || APKS=("$REPO/app/build/outputs/apk/debug/app-debug.apk" "$REPO/app/build/outputs/apk/staging/app-staging.apk")
SCRIPTS=()
for name in $(printf '%s' "$ONLY" | tr ',' ' '); do
    case "$name" in
        keyboard | autofill | overlay | state | a11y) SCRIPTS+=("$name") ;;
        *) echo "all.sh: no such check: $name" >&2; usage ;;
    esac
done
[ ${#SCRIPTS[@]} -gt 0 ] || usage
for serial in "${SERIALS[@]}"; do pv_require_serial "$serial"; done
for apk in "${APKS[@]}"; do
    [ -f "$apk" ] || { echo "all.sh: no APK at $apk (tools/gradle.sh :app:assembleDebug :app:assembleStaging)" >&2; exit 2; }
done

win_helper_build || { echo "FAIL the helper app is built"; exit 1; }
export PV_WINDOW_HELPER_APK=$WIN_HELPER_APK

OUT=$REPO/build/window/all
mkdir -p "$OUT"
rm -f "$OUT"/*.log "$OUT"/*.rc

# One device: every APK, every script, one after another; each script's output printed as a block.
run_device() {
    local serial=$1 apk script log rc
    for apk in "${APKS[@]}"; do
        for script in "${SCRIPTS[@]}"; do
            log=$OUT/$serial-$(basename "$apk" .apk)-$script.log
            "$WIN_DIR/$script.sh" "$serial" --apk "$apk" >"$log" 2>&1
            rc=$?
            echo "$rc" >"$log.rc"
            {
                flock 9
                echo "==== $script.sh on $serial with $(basename "$apk"): exit $rc"
                cat "$log"
                echo
            } 9>>"$OUT/.print.lock"
        done
    done
}

echo "all.sh: ${SCRIPTS[*]} on ${SERIALS[*]} with $(for apk in "${APKS[@]}"; do printf '%s ' "$(basename "$apk")"; done)"
for serial in "${SERIALS[@]}"; do run_device "$serial" & done
wait

failed=0
total=0
for serial in "${SERIALS[@]}"; do
    for apk in "${APKS[@]}"; do
        for script in "${SCRIPTS[@]}"; do
            log=$OUT/$serial-$(basename "$apk" .apk)-$script.log
            rc=$(cat "$log.rc" 2>/dev/null || echo "?")
            total=$((total + 1))
            [ "$rc" = 0 ] || failed=$((failed + 1))
            printf '%s %s on %s (API %s) with %s: %s passed, %s failed, %s skipped, %s known (exit %s)\n' \
                "$([ "$rc" = 0 ] && echo PASS || echo FAIL)" "$script.sh" "$serial" \
                "$(sed -n 's/^.* on [^ ]* (API \([0-9]*\)).*/\1/p' "$log" | head -n 1)" "$(basename "$apk")" \
                "$(grep -c '^PASS ' "$log")" "$(grep -c '^FAIL ' "$log")" "$(grep -c '^SKIPPED ' "$log")" "$(grep -c '^KNOWN ' "$log")" "$rc"
        done
    done
done
echo "SUMMARY window checks: $((total - failed)) of $total scripts passed, $failed failed"
[ "$failed" -eq 0 ]
