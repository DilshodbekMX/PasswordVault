#!/usr/bin/env bash
# tools/emu/smoke.sh - the smoke test of one build on one private emulator.
#
#   tools/emu/smoke.sh VARIANT SERIAL [--apk FILE]
#       VARIANT is debug or staging (a release APK holds arm code only and cannot run on these
#       x86_64 emulators). SERIAL comes from tools/emu/boot.sh. The APK defaults to this
#       worktree's app/build/outputs/apk/VARIANT/app-VARIANT.apk: build it first with
#       tools/gradle.sh :app:assembleDebug or :app:assembleStaging.
#
# The stages grow with the waves (architecture.md 8.4); each wave adds a stage function below.
#   wave 0: install and start.
# Prints PASS, FAIL or SKIPPED per step and a summary line. Exit codes: 0 every step passed or
# was skipped; 1 a step failed; 2 usage; 3 SERIAL refused.
set -u
source "$(dirname "${BASH_SOURCE[0]}")/adb.sh"

usage() { pv_usage "$0"; }

VARIANT=${1:-}
SERIAL=${2:-}
APK=""
[ $# -ge 2 ] || usage
shift 2
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || usage; APK=$2; shift ;;
        *) usage ;;
    esac
    shift
done
case "$VARIANT" in
    debug|staging) ;;
    release) echo "smoke.sh: release APKs hold arm code only; use staging (R8, not debuggable) on the emulators" >&2; exit 2 ;;
    *) usage ;;
esac
REPO=$(cd "$PV_EMU_DIR/../.." && pwd)
[ -n "$APK" ] || APK=$REPO/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
AAPT2=$(ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)

pv_use "$SERIAL"
API=$(pv_api)
echo "smoke.sh $VARIANT on $SERIAL (API $API): $APK"

# ---- stage: install and start (wave 0) ---------------------------------------------------------
check_apk() {
    local badging
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assemble${VARIANT^}"; return 1; }
    badging=$("$AAPT2" dump badging "$APK" 2>/dev/null) || { pv_log "aapt2 cannot read $APK"; return 1; }
    echo "$badging" | grep -q "^package: name='$PV_PACKAGE'" \
        || { pv_log "the APK's package is not $PV_PACKAGE"; return 1; }
    if echo "$badging" | grep -q '^application-debuggable'; then
        [ "$VARIANT" = debug ] || { pv_log "a $VARIANT APK must not be debuggable"; return 1; }
    else
        [ "$VARIANT" != debug ] || { pv_log "a debug APK must be debuggable"; return 1; }
    fi
    if echo "$badging" | grep -q '^native-code:'; then
        echo "$badging" | grep '^native-code:' | grep -q "'x86_64'" \
            || { pv_log "the APK has no x86_64 code: $(echo "$badging" | grep '^native-code:')"; return 1; }
    fi
}

remove_old_copy() {
    if pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'; then
        pv_adb uninstall "$PV_PACKAGE" >/dev/null || return 1
    fi
    ! pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'
}

install_apk() {
    local out
    out=$(pv_adb install -r "$APK" 2>&1)
    echo "$out" | grep -q '^Success' || { pv_log "adb install: $out"; return 1; }
}

installed_matches_variant() {
    if [ "$VARIANT" = debug ]; then pv_is_debuggable; else ! pv_is_debuggable; fi \
        || { pv_log "the installed app's debuggable flag does not match $VARIANT"; return 1; }
}

launcher_activity() {
    local a
    a=$(pv_shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PV_PACKAGE" 2>/dev/null \
        | tr -d '\r' | grep "^$PV_PACKAGE/" | tail -n 1)
    echo "${a:-$PV_PACKAGE/.MainActivity}"
}

app_pid() {
    local p
    p=$(pv_shell pidof "$PV_PACKAGE" 2>/dev/null | tr -d '\r')
    [ -n "$p" ] || p=$( { pv_shell ps -A 2>/dev/null || pv_shell ps; } | tr -d '\r' | awk -v p="$PV_PACKAGE" '$NF == p { print $2; exit }')
    echo "$p"
}

start_app() {
    local activity out
    pv_unlock >/dev/null 2>&1
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    activity=$(launcher_activity)
    out=$(pv_shell am start -W -n "$activity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start -W -n $activity: $out"; return 1; }
    pv_log "started $activity: $(echo "$out" | grep -E '^(TotalTime|WaitTime)' | tr '\n' ' ')"
}

app_in_front() {
    local i
    for i in $(seq 1 15); do
        case "$(pv_focus)" in *"$PV_PACKAGE"/*) return 0 ;; esac
        pv_sleep 1
    done
    pv_log "the app is not in front; focus is $(pv_focus)"
    return 1
}

app_still_running() {
    local crash pid
    pv_sleep 5
    pid=$(app_pid)
    [ -n "$pid" ] || { pv_log "the app's process is gone"; return 1; }
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
    case "$(pv_focus)" in *"$PV_PACKAGE"/*) ;; *) pv_log "the app left the front; focus is $(pv_focus)"; return 1 ;; esac
    pv_log "the app runs as pid $pid"
}

stage_install_and_start() {
    pv_step "apk is a $VARIANT build of $PV_PACKAGE" -- check_apk || return 1
    pv_step "remove an installed copy" -- remove_old_copy || return 1
    pv_step "install" -- install_apk || return 1
    pv_step "installed app is $VARIANT (debuggable flag)" -- installed_matches_variant
    pv_step "start the launcher activity" -- start_app || return 1
    pv_step "app is in front" -- app_in_front || return 1
    pv_step "app still running after 5 s, no crash" -- app_still_running
}

stage_install_and_start
pv_summary
