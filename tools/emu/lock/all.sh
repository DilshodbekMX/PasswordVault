#!/usr/bin/env bash
# tools/emu/lock/all.sh - runs the lock's emulator checks E1 to E17 (lock.md §15.3) on the Android
# versions lock.md lists for each, on private emulators, the devices at the same time and the checks of
# one device one after another.
#
#   tools/emu/lock/all.sh [--staging | --apk FILE] [--only E1,E6,...] [--levels 24,28,37] [--keep] [SERIAL...]
#       --staging  the staging build (app/build/outputs/apk/staging/app-staging.apk; R8, not
#                  debuggable): its steps that need run-as or root print SKIPPED. Default: the debug
#                  build. A missing APK is built with tools/gradle.sh first.
#       --apk      any other APK (E15 always uses the backupCheck variant, which it builds itself).
#       --only     these checks (default: all of E1 to E17).
#       --levels   only these API levels of each check's own (default: 24, 28 and 37); a check none of
#                  whose levels is among them is not run.
#       --keep     leave the devices this script booted running (default: stopped at the end).
#       SERIAL     devices of this worktree (tools/emu/boot.sh) to use, each for the checks of its API
#                  level; without any, one device per API level that the chosen checks need is booted
#                  (24, 28, 37) and stopped at the end.
#
# The levels, from lock.md §15.3 (Android 7 = API 24, 9 = 28, 17 = 37), with build plan §12's E12 on 28:
#   E1 E2 E3 E6 E7 E10 E11   24 28 37      E4 E5 E8 E9 E14   37
#   E12 E13 E15              28 37         E16               24
#   E17                      24 37 (on 24 only its last item)
# On each device E8 runs last: it leaves the emulator's fingerprint sensor locked out until a reboot,
# which it then makes itself. E7 and E16 put the phone's fingers, screen lock and heap back as the base
# has them.
# Prints each check's steps into build/emu-lock/all/SERIAL-EN.log, one line per check and device as it
# ends, and a table at the end. Exit 0 when every check passed, 1 otherwise, 2 for a usage error, 3 for
# a refused serial.
set -u
LOCK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMU="$(cd "$LOCK_DIR/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
TASK=:app:assembleDebug
ONLY=""
LEVELS="24 28 37"
KEEP=0
SERIALS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --staging) APK=$REPO/app/build/outputs/apk/staging/app-staging.apk; TASK=:app:assembleStaging ;;
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; TASK=""; shift ;;
        --only) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; ONLY=$2; shift ;;
        --levels) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LEVELS=${2//,/ }; shift ;;
        --keep) KEEP=1 ;;
        emulator-*) SERIALS+=("$1") ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

# levels_of CHECK: the API levels lock.md lists for CHECK, among LEVELS.
levels_of() {
    local l
    for l in $(all_levels_of "$1"); do
        case " $LEVELS " in *" $l "*) printf '%s ' "$l" ;; esac
    done
}

all_levels_of() {
    case "$1" in
        E1 | E2 | E3 | E6 | E7 | E10 | E11) echo "24 28 37" ;;
        E4 | E5 | E8 | E9 | E14) echo 37 ;;
        E12 | E13 | E15) echo "28 37" ;;
        E16) echo 24 ;;
        E17) echo "24 37" ;;
    esac
}

CHECKS=(E1 E2 E3 E4 E5 E6 E7 E9 E10 E11 E12 E13 E14 E15 E16 E17 E8)
if [ -n "$ONLY" ]; then
    wanted=()
    for c in "${CHECKS[@]}"; do case ",$ONLY," in *",$c,"*) wanted+=("$c") ;; esac; done
    for c in ${ONLY//,/ }; do [ -n "$(all_levels_of "$c")" ] || { echo "all.sh: no check $c" >&2; exit 2; }; done
    CHECKS=("${wanted[@]}")
fi

OUT=$REPO/build/emu-lock/all
mkdir -p "$OUT"
if [ ! -f "$APK" ]; then
    [ -n "$TASK" ] || { echo "all.sh: no APK at $APK" >&2; exit 2; }
    echo "all.sh: building $TASK"
    "$REPO/tools/gradle.sh" "$TASK" > "$OUT/build.log" 2>&1 || { echo "all.sh: $TASK failed (see $OUT/build.log)" >&2; exit 1; }
fi

# The devices: given, or booted for the levels needed.
BOOTED=()
declare -A DEVICE=()
for s in "${SERIALS[@]}"; do
    pv_require_serial "$s"
    api=$(PV_SERIAL=$s pv_api)
    DEVICE[$api]=$s
done
if [ ${#SERIALS[@]} -eq 0 ]; then
    needed=""
    for c in "${CHECKS[@]}"; do needed="$needed $(levels_of "$c")"; done
    for api in 24 28 37; do
        case " $needed " in *" $api "*) ;; *) continue ;; esac
        echo "all.sh: booting API $api"
        s=$("$EMU/boot.sh" "$api") || { echo "all.sh: boot.sh $api failed" >&2; continue; }
        DEVICE[$api]=$s
        BOOTED+=("$s")
    done
fi
stop_booted() {
    [ $KEEP = 0 ] && [ ${#BOOTED[@]} -gt 0 ] && "$EMU/stop.sh" "${BOOTED[@]}" >/dev/null 2>&1
}
trap stop_booted EXIT

# run_device API SERIAL: the chosen checks of that level, one after another.
run_device() {
    local api=$1 s=$2 c rc summary
    for c in "${CHECKS[@]}"; do
        case " $(levels_of "$c") " in *" $api "*) ;; *) continue ;; esac
        if [ "$c" = E15 ]; then
            "$LOCK_DIR/$c.sh" "$s" > "$OUT/$s-$c.log" 2>&1
        else
            "$LOCK_DIR/$c.sh" "$s" --apk "$APK" > "$OUT/$s-$c.log" 2>&1
        fi
        rc=$?
        summary=$(grep -m 1 '^SUMMARY' "$OUT/$s-$c.log" | sed 's/^SUMMARY [^:]*: //')
        printf '%s %s (API %s): %s\n' "$c" "$s" "$api" "${summary:-no summary, exit $rc}" | tee -a "$OUT/results.txt"
        echo "$c $s $api $rc" >> "$OUT/status.txt"
    done
}

: > "$OUT/results.txt"
: > "$OUT/status.txt"
echo "all.sh: $APK; checks ${CHECKS[*]}"
pids=()
for api in 24 28 37; do
    [ -n "${DEVICE[$api]:-}" ] || continue
    run_device "$api" "${DEVICE[$api]}" &
    pids+=($!)
done
for p in "${pids[@]}"; do wait "$p"; done

echo "SUMMARY lock checks ($(basename "$APK")):"
sort -V "$OUT/results.txt"
missing=0
for c in "${CHECKS[@]}"; do
    for api in $(levels_of "$c"); do
        [ -n "${DEVICE[$api]:-}" ] || { echo "NOT RUN $c on API $api: no device"; missing=1; }
    done
done
! awk '$4 != 0 { bad = 1 } END { exit !bad }' "$OUT/status.txt" && [ $missing = 0 ]
