#!/usr/bin/env bash
# tools/emu/session/autolock.sh - build plan 3.2, [script]: the auto-lock on an emulator. With the
# lock delay that setup writes (30 seconds, lock.md §10.1): away for 31 s locks, away for 10 s does
# not, screen-off locks at once, a rotation does not.
#
#   tools/emu/session/autolock.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh. The APK defaults to this worktree's
#       app/build/outputs/apk/debug/app-debug.apk (tools/gradle.sh :app:assembleDebug); a staging
#       APK works too, and its steps that need run-as print SKIPPED.
#
# It installs the APK afresh and sets up a vault through the app's own screens, found by their
# resource ids so that the language does not matter: the shell's placeholders (pv.open.<Route>,
# pv.action.createVault, pv.action.unlock) or the real setup and lock screens of build plan 3.3 and
# 3.4 (pv.setup.typeOwn, pv.setup.own, pv.setup.useOwn, pv.setup.reuse.no, pv.setup.again,
# pv.lock.password, pv.lock.unlock), whichever the APK has. With the placeholders the vault's master
# password is empty; with the real screens it is $PASSWORD. The checks then read the route the app
# shows (pv.route.Passwords or pv.route.Locked):
#   - setup wrote the 30-second default into pv_settings (run-as; SKIPPED on staging);
#   - a rotation recreates the activity (dumpsys shows another activity object) and does not lock;
#   - away for 10 s (Home, then the launcher's intent) does not lock;
#   - away for 31 s locks; the master password opens it again;
#   - the screen off for 3 s locks at once (dumpsys power proves it was off);
#   - the screen off for 3 s while the app is away (8 s away in all) locks: only the screen-off
#     receiver sees it, since no ON_STOP comes with it;
#   - with the lock delay set to "Immediately" in pv_settings (run-as and a restart of the app;
#     SKIPPED on staging), a rotation still does not lock, while leaving does, at once;
#   - the app did not crash.
# The lock is DefaultLockManager since build plan 4.1 (the master-password gate of 3.2, which this
# script was written against, is gone): after setup and after each master-password unlock its
# "Choose a PIN" opens 4.3's PIN steps over the list, and "Make a backup now?" may follow; they are
# answered "Not now" and "Later" (tools/emu/app.sh, pva_settle), so the PIN stays off and the lock
# screen stays the master-password screen. Run on 2026-10-07 against the gate, merged with 3.1, 3.3
# and 3.4: 16 of 16 on API 24 and 37 (debug), 12 passed and 4 skipped (staging).
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for
# a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

pv_use "$SERIAL"
pva_init
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
# Letters and digits only (adb's "input text" takes them as they are); 18 random characters pass
# the master-password rules of ux.md §5.3 (12 code points, zxcvbn score 4).
PASSWORD=${PV_MASTER_PASSWORD:-Kv7Qx2mZp9Lw4Rt8Hb}
PREFS=shared_prefs/pv_settings.xml
echo "autolock.sh on $SERIAL (API $API): $APK"

# ---- helpers (as in shell/walk.sh) ------------------------------------------------------------
re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }

# on ROUTE [SECONDS]: the screen of ROUTE is shown.
on() {
    pv_ui_wait "[|]pv\.route\.$1\$" "${2:-20}" || { pv_log "not on $1; ids: $(ids)"; return 1; }
}

ids() { pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }'; }

present() { pv_ui_wait "[|]$(re "$1")\$" "${2:-5}"; }

# need ID [SECONDS]: like present, but says what is shown when ID is not.
need() { present "$@" || { pv_log "no $1; ids: $(ids)"; return 1; }; }

absent() { ! pv_ui_nodes | pv_ui_match "[|]$(re "$1")\$" | grep -q .; }

# The screen's height, and the bottom band where the system's navigation bar is (about 48 dp, 126 px
# at 420 dpi): a node whose centre is there is scrolled up before it is tapped, as shell/walk.sh
# does. The screens tapped here have no bottom bar of the app's own, so the band is narrower.
SCREEN_H=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
SCREEN_H=${SCREEN_H:-1920}
SAFE_BOTTOM=$((SCREEN_H - 160))

scroll_down() { pv_shell input swipe 540 $((SCREEN_H * 6 / 10)) 540 $((SCREEN_H * 3 / 10)) 400 >/dev/null; pv_sleep 1; }

# reveal ID: ID is on the screen, scrolling down for it (on a short screen it may be below the fold).
reveal() {
    local i
    for i in 1 2 3 4; do
        present "$1" 2 && return 0
        scroll_down
    done
    present "$1" 2
}

# tap ID: taps the node whose resource id is ID, after scrolling it onto the screen and above the
# bottom band.
tap() {
    local id_re="[|]$(re "$1")\$" nodes line y i
    reveal "$1" || { pv_log "no node with id $1; ids: $(ids)"; return 1; }
    for i in 1 2 3 4 5; do
        nodes=$(pv_ui_nodes)
        line=$(printf '%s\n' "$nodes" | pv_ui_match "$id_re")
        [ -n "$line" ] || { pv_log "no node with id $1; ids: $(ids)"; return 1; }
        y=$(echo "$line" | cut -d'|' -f7)
        if [ "$y" -lt "$SAFE_BOTTOM" ]; then
            pv_ui_tap "$id_re" "$nodes"
            return
        fi
        scroll_down
    done
    pv_log "the node $1 stays in the bottom band (y $y of $SCREEN_H)"
    return 1
}

# type_into ID TEXT: focuses the field ID and types TEXT.
type_into() {
    tap "$1" || return 1
    pv_sleep 1
    pv_shell input text "$2" >/dev/null
}

# submit BUTTON-ID THEN-ID [SECONDS]: the keyboard's Enter, the fields' action (ImeAction.Done); if
# THEN-ID has not appeared, the button itself, in case the keyboard took the Enter.
submit() {
    pv_shell input keyevent KEYCODE_ENTER >/dev/null
    present "$2" "${3:-15}" && return 0
    pv_log "Enter did not reach $2; tapping $1"
    tap "$1" && need "$2" "${3:-15}"
}

# submit_open BUTTON-ID: as submit, then the app is open on the list once what the lock puts over it
# after setup or a master password ("Choose a PIN" and its steps, a notice) is answered (app.sh).
submit_open() {
    pv_shell input keyevent KEYCODE_ENTER >/dev/null
    if ! pva_unlocked Passwords 15; then
        pv_log "Enter did not open the app; tapping $1"
        tap "$1" && pva_unlocked Passwords 60 || return 1
    fi
    pva_settle Passwords
}

relaunch() {
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
}

# The identity of the app's activity object ("Local Activity <hash>" in its part of dumpsys): another
# one after a recreation.
activity_object() {
    pv_shell dumpsys activity top 2>/dev/null | tr -d '\r' \
        | awk -v a="ACTIVITY $PV_PACKAGE/" 'index($0, a) { f = 1 } f && /Local Activity/ { print $3; exit }'
}

in_front() { case "$(pv_focus)" in *"$PV_PACKAGE"/*) return 0 ;; *) return 1 ;; esac; }

rotate() { # 0 portrait, 1 landscape
    pv_shell settings put system accelerometer_rotation 0 >/dev/null
    pv_shell settings put system user_rotation "$1" >/dev/null
    pv_sleep 3
}

# still_open: Passwords is shown, the lock route is not, and three seconds later that still holds.
still_open() {
    on Passwords 10 || return 1
    pv_sleep 3
    absent pv.route.Locked || { pv_log "the app locked"; return 1; }
    on Passwords 5
}

# ---- the app ----------------------------------------------------------------------------------
install_fresh() {
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assembleDebug"; return 1; }
    pv_adb uninstall "$PV_PACKAGE" >/dev/null 2>&1
    local out
    out=$(pv_adb install -r "$APK" 2>&1)
    echo "$out" | grep -q '^Success' || { pv_log "adb install: $out"; return 1; }
}

start() {
    pv_unlock >/dev/null 2>&1
    rotate 0
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    relaunch && on Welcome 30
}

# Setup (lock.md F1 steps 1 to 3) through whichever screens the APK has.
set_up() {
    tap pv.open.CreateMasterPassword && on CreateMasterPassword || return 1
    if reveal pv.setup.typeOwn; then
        tap pv.setup.typeOwn && need pv.setup.own && type_into pv.setup.own "$PASSWORD" || return 1
        submit pv.setup.useOwn pv.setup.reuse.no && tap pv.setup.reuse.no || return 1 # "No, it's new"
    else
        tap pv.open.WriteDown || return 1
    fi
    on WriteDown && tap pv.open.TypeAgain && on TypeAgain || return 1
    if reveal pv.setup.again; then
        type_into pv.setup.again "$PASSWORD" && submit_open pv.action.createVault
    else
        tap pv.action.createVault && on Passwords 60
    fi
}

# The master password on the lock screen.
unlock() {
    on Locked || return 1
    if reveal pv.lock.password; then
        type_into pv.lock.password "$PASSWORD" && submit_open pv.lock.unlock
    else
        tap pv.action.unlock && on Passwords 60
    fi
}

default_written() {
    local xml
    xml=$(pv_shell run-as "$PV_PACKAGE" cat "$PREFS" 2>/dev/null | tr -d '\r')
    echo "$xml" | grep -q '<string name="lock_delay">30s</string>' || { pv_log "$PREFS: $xml"; return 1; }
}

rotation_does_not_lock() {
    local before after rc=0
    before=$(activity_object)
    rotate 1
    after=$(activity_object)
    [ -n "$before" ] && [ "$before" != "$after" ] || { pv_log "the activity was not recreated: $before -> $after"; rc=1; }
    still_open || rc=1
    rotate 0
    still_open || rc=1
    return $rc
}

# away SECONDS: Home; SECONDS from the moment the app is no longer in front; then the launcher's
# intent brings the task back. The app counts from its ON_STOP, 0.7 s after it was left (lock.md
# §10.2), to its ON_START after the relaunch: away 31 s is at least 30.3 s for it, 10 s at most
# about 10 s.
away() {
    local i
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do in_front || break; pv_sleep 0.5; done
    in_front && { pv_log "the app is still in front after Home: $(pv_focus)"; return 1; }
    pv_sleep "$1"
    relaunch
}

away_10_s_does_not_lock() { away 10 && still_open; }

away_31_s_locks() { away 31 && on Locked; }

# The screen off for 3 s, far less than the 30-second delay: only the screen-off rule can lock.
screen_off_locks_at_once() {
    local power
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 3
    power=$(pv_shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m 1 -o 'mWakefulness=[A-Za-z]*')
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    case "$power" in mWakefulness=Asleep | mWakefulness=Dozing) ;; *) pv_log "the screen did not go off: $power"; return 1 ;; esac
    pv_sleep 1
    pv_unlock || return 1
    in_front || relaunch || return 1
    on Locked
}

# The screen off for 3 s while the app is already away, within the delay: no ON_STOP comes with it,
# so only the ACTION_SCREEN_OFF receiver can lock (lock.md §10.2 rule 2). From Android 14 the
# broadcast may be held while the app is cached and arrive when it comes back; it locks then.
screen_off_while_away_locks() {
    local i power
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do in_front || break; pv_sleep 0.5; done
    pv_sleep 3
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 3
    power=$(pv_shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m 1 -o 'mWakefulness=[A-Za-z]*')
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    case "$power" in mWakefulness=Asleep | mWakefulness=Dozing) ;; *) pv_log "the screen did not go off: $power"; return 1 ;; esac
    pv_sleep 1
    pv_unlock || return 1
    relaunch && on Locked
}

# "Immediately" through the settings file (the Settings screen comes with build plan 4.3): the app
# reads it once per process, so it is stopped, edited, started and unlocked again.
set_immediately() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    # One string, so that the device's shell gets the single quotes around the expression.
    pv_shell "run-as $PV_PACKAGE sed -i 's#<string name=\"lock_delay\">[^<]*</string>#<string name=\"lock_delay\">immediately</string>#' $PREFS" || return 1
    pv_shell run-as "$PV_PACKAGE" cat "$PREFS" 2>/dev/null | tr -d '\r' | grep -q '<string name="lock_delay">immediately</string>' || return 1
    relaunch && unlock
}

immediately_rotation_does_not_lock() { rotation_does_not_lock; }

immediately_leaving_locks() { away 2 && on Locked; }

no_crash() {
    local crash
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
}

pv_step "install the app afresh" -- install_fresh || { pv_summary; exit 1; }
pv_step "start the app: Welcome" -- start || { pv_summary; exit 1; }
pv_step "set up a vault: the list is shown" -- set_up || { pv_summary; exit 1; }
pv_step "setup wrote the 30-second lock delay" needs-debuggable -- default_written
pv_step "a rotation recreates the activity and does not lock" -- rotation_does_not_lock
pv_step "away for 10 s does not lock" -- away_10_s_does_not_lock
pv_step "away for 31 s locks" -- away_31_s_locks
pv_step "the master password opens it again" -- unlock
pv_step "the screen off for 3 s locks at once" -- screen_off_locks_at_once
pv_step "the master password opens it again" -- unlock
pv_step "the screen off while the app is away (8 s in all) locks" -- screen_off_while_away_locks
pv_step "the master password opens it again" -- unlock
if ! pv_is_debuggable; then
    for name in "the lock delay set to Immediately (pv_settings, a new process, unlocked)" \
        "with Immediately, a rotation still does not lock" "with Immediately, leaving locks at once"; do
        pv_report SKIPPED "$name" "needs-debuggable: $PV_PACKAGE on $PV_SERIAL is not debuggable"
    done
elif pv_step "the lock delay set to Immediately (pv_settings, a new process, unlocked)" -- set_immediately; then
    pv_step "with Immediately, a rotation still does not lock" -- immediately_rotation_does_not_lock
    pv_step "with Immediately, leaving locks at once" -- immediately_leaving_locks
fi
rotate 0
pv_step "no crash" -- no_crash
pv_summary
