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
#   sub-wave 3A (the skeleton): set up with the suggested words; add an account, whose screen is
#     shown after Save; a rotation there neither locks nor changes the screen; the app killed in the
#     background (am kill; kill -9 through run-as on debug builds or su on the userdebug images where
#     am kill is refused, API 24; SKIPPED when nothing can end it) and started again: locked, and the
#     master password brings the same account's screen back (the saved back stack, under R8 in
#     staging); Back to the list, Lock now: locked; the master password: the list with the account.
#     Driven by resource id through tools/emu/app.sh. Since the merge of round R3 the lock's "Choose
#     a PIN" after setup and after each master-password unlock is answered "Not now", and "Make a
#     backup now?" "Later" (app.sh, pva_settle): the PIN and fingerprint stay off here.
#   sub-wave 3B (the account's screen and the clipboard): the account opened from the list, its
#     password drawn as dots and in no node; "Show password" shows it; Copy: the clip, read from
#     outside the app (clipboard/lib.sh), carries the sensitive flag and a 32-digit token and holds
#     the password, and the status row is shown; Home: the clip is still there 4 s before its time and
#     gone 4 s after it at the latest, with the delay setup wrote (1 minute; the time is the first
#     reading of the clip plus the delay, as clipboard/clear.sh estimates it without run-as).
#   sub-wave 4A (the PIN and the fingerprint): back in front the app is locked; the master password
#     opens it and "Choose a PIN" follows: a PIN, typed again; the fingerprint offer "Use", finger 1 on
#     the prompt; the steps close onto the account's screen, where the app was (ux.md 4, "What survives
#     a lock"); the lock's status (the debug command, also in staging) has both slots and their
#     Keystore keys; Lock now: the prompt opens by itself (on API 24 androidx.biometric's own dialog in
#     the activity, the path R8 broke before build check R-16) and finger 1 opens the list; Lock now,
#     "Use PIN" on the prompt: the pad, and the PIN opens the list with the account.
# Prints PASS, FAIL or SKIPPED per step and a summary line. Exit codes: 0 every step passed or
# was skipped; 1 a step failed; 2 usage; 3 SERIAL refused.
set -u
source "$(dirname "${BASH_SOURCE[0]}")/adb.sh"
source "$(dirname "${BASH_SOURCE[0]}")/app.sh"
source "$(dirname "${BASH_SOURCE[0]}")/clipboard/lib.sh"

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
# A wedged device must not hang the smoke test: every adb call gets at most 180 s.
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-180}
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

# Without streaming (pv_adb); 90 s per try and up to three tries, should an install still not answer.
install_apk() {
    local out i
    for i in 1 2 3; do
        out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$APK" 2>&1)
        echo "$out" | grep -q '^Success' && return 0
        pv_log "adb install, try $i: ${out:-no answer within 90 s}"
        pv_sleep 5
    done
    return 1
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

# ---- stage: the skeleton (sub-wave 3A) ---------------------------------------------------------
SMOKE_ENTRY=""

smoke_set_up() { pva_set_up && pva_need pv.list.empty 10; }

# An account; Save shows its screen, whose first line is the account's name (pv.entry.title, build plan 3.7).
smoke_add() {
    pva_add_account Smoketest smoke.user@example.uz Hv4Nc8Rt2Wq6Yz3 https://smoke.example/login "Smoke note" || return 1
    SMOKE_ENTRY=$(pva_text pv.entry.title) && [ -n "$SMOKE_ENTRY" ] || return 1
    pv_log "the account's screen: $SMOKE_ENTRY"
}

same_entry() {
    local title
    pva_on Entry 20 || return 1
    title=$(pva_text pv.entry.title) || return 1
    [ "$title" = "$SMOKE_ENTRY" ] || { pv_log "the screen shows '$title', not '$SMOKE_ENTRY'"; return 1; }
}

smoke_rotate() {
    local o
    for o in 1 0; do
        pva_rotate "$o"
        same_entry && pva_absent pv.route.Locked || return 1
    done
}

smoke_back_after_kill() { pva_relaunch && pva_unlock Entry && same_entry; }

smoke_lock_now() {
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords && pva_tap pv.list.lockNow && pva_on Locked
}

smoke_unlock() { pva_unlock Passwords && pva_row_with Smoketest 10; }

stage_skeleton() {
    local same="a new process: locked; the master password brings the same screen back"
    pva_init
    pva_rotate 0
    pv_step "set up with the suggested words: the list, empty" -- smoke_set_up || return 1
    pv_step "add an account: its screen after Save" -- smoke_add || return 1
    pv_step "a rotation keeps the account's screen and does not lock" -- smoke_rotate
    if pva_kill_in_background; then
        pv_report PASS "the app is killed in the background"
        pv_step "$same" -- smoke_back_after_kill
    else
        pv_report SKIPPED "the app is killed in the background" "nothing here could end the app's process on API $API"
        pv_report SKIPPED "$same" "the process was not killed"
        pv_step "back in front: the same screen" -- eval 'pva_relaunch && same_entry'
    fi
    pv_step "Back to the list, Lock now: locked" -- smoke_lock_now
    pv_step "the master password opens the list with the account" -- smoke_unlock
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: the account's screen and the clipboard (sub-wave 3B) --------------------------------
SMOKE_PASSWORD=Hv4Nc8Rt2Wq6Yz3
SMOKE_DELAY_MS=60000 # "Clear copied text after", as setup writes it (D-7)
SMOKE_TOKEN=""
SMOKE_DEADLINE=0

# One reading of the clipboard from outside the app: SMOKE_NOW (the device's clock, ms), SMOKE_HAS,
# SMOKE_CLIP (the app's token; empty when the clip is not the app's) and SMOKE_SENSITIVE.
smoke_clip() {
    local d
    d=$(pvc_describe) || { pv_log "the clipboard could not be read"; return 1; }
    SMOKE_NOW=$(printf '%s\n' "$d" | pvc_field now)
    SMOKE_HAS=$(printf '%s\n' "$d" | pvc_field has)
    SMOKE_CLIP=$(printf '%s\n' "$d" | pvc_field token)
    SMOKE_SENSITIVE=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ -n "$SMOKE_NOW" ]
}

smoke_open_account() {
    local row
    pva_on Passwords 20 || return 1
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
    [ -n "$row" ] || { pv_log "no row in the list; ids: $(pva_ids)"; return 1; }
    pva_tap "$row" && same_entry || return 1
    pva_need pv.entry.password.value 10 || return 1
    ! pv_ui_nodes | grep -qF -- "$SMOKE_PASSWORD" || { pv_log "a node holds the password while it is hidden"; return 1; }
}

smoke_show_password() {
    local deadline=$((SECONDS + 10)) text=""
    pva_tap pv.entry.password.show || return 1
    until [ "$text" = "$SMOKE_PASSWORD" ]; do
        [ $SECONDS -lt $deadline ] || { pv_log "pv.entry.password.value reads '$text'"; return 1; }
        pv_sleep 0.5
        text=$(pva_text pv.entry.password.value)
    done
}

smoke_copy() {
    local i text
    pvc_clear || return 1
    pva_tap pv.entry.password.copy || return 1
    for i in $(seq 1 20); do
        smoke_clip || return 1
        [ -n "$SMOKE_CLIP" ] && break
        pv_sleep 0.2
    done
    [ -n "$SMOKE_CLIP" ] || { pv_log "no clip of the app's after Copy (has=$SMOKE_HAS)"; return 1; }
    printf '%s' "$SMOKE_CLIP" | grep -Eq '^[0-9a-f]{32}$' || { pv_log "the token is not 32 hex digits: '$SMOKE_CLIP'"; return 1; }
    [ "$SMOKE_SENSITIVE" = true ] || { pv_log "the sensitive flag is '${SMOKE_SENSITIVE:-absent}'"; return 1; }
    SMOKE_TOKEN=$SMOKE_CLIP
    SMOKE_DEADLINE=$((SMOKE_NOW + SMOKE_DELAY_MS))
    pva_need pv.clip.row 5 || return 1
    text=$(pvc_get | pvc_field text) || return 1
    [ "$text" = "$SMOKE_PASSWORD" ] || { pv_log "the clip does not hold the password (${#text} characters)"; return 1; }
    pv_log "the clip is flagged sensitive, has the token $SMOKE_TOKEN and holds the password; the status row is shown"
}

# smoke_until MS: returns when the device's clock reads MS.
smoke_until() {
    local left
    smoke_clip || return 1
    left=$(($1 - SMOKE_NOW))
    [ "$left" -le 0 ] || pv_sleep "$((left / 1000)).$(printf '%03d' $((left % 1000)))"
}

smoke_clear_in_background() {
    local text
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    smoke_until $((SMOKE_DEADLINE - 4000)) && smoke_clip || return 1
    [ "$SMOKE_CLIP" = "$SMOKE_TOKEN" ] || { pv_log "$((SMOKE_DEADLINE - SMOKE_NOW)) ms before its time the clip is gone already"; return 1; }
    while :; do
        smoke_clip || return 1
        [ "$SMOKE_CLIP" = "$SMOKE_TOKEN" ] || break
        [ "$SMOKE_NOW" -lt $((SMOKE_DEADLINE + 4000)) ] || { pv_log "the clip is still there $((SMOKE_NOW - SMOKE_DEADLINE)) ms after its time"; return 1; }
        pv_sleep 0.2
    done
    pv_log "the clip was gone $((SMOKE_NOW - SMOKE_DEADLINE + SMOKE_DELAY_MS)) ms after the copy was first seen"
    # Nothing is left: no clip from API 28 on; on Android 7, which cannot clear otherwise, an empty clip.
    if [ "$API" -ge 28 ]; then
        [ "$SMOKE_HAS" = false ] || { pv_log "the clipboard is not empty"; return 1; }
    else
        text=$(pvc_get | pvc_field text) || return 1
        [ -z "$SMOKE_CLIP" ] && [ -z "$text" ] || { pv_log "the clipboard is not empty"; return 1; }
    fi
}

stage_clipboard() {
    pv_step "the account from the list: its password drawn as dots, in no node" -- smoke_open_account || return 1
    pv_step "Show password shows it" -- smoke_show_password
    pv_step "Copy: the clip is flagged sensitive, has a token and holds the password; the status row is shown" -- smoke_copy || return 1
    pv_step "with the app in the background the clip is gone at its time (setup's 1-minute delay)" -- smoke_clear_in_background
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: the PIN and the fingerprint (sub-wave 4A) ---------------------------------------------
SMOKE_PIN=582913

# Back in front after the clipboard stage, the app is locked (setup's 30 s delay); the master password
# opens it, and with no PIN "Choose a PIN" opens the PIN steps (lock.md F1 step 6, F7).
smoke_unlock_to_pin_steps() {
    pva_relaunch && pva_on Locked 30 && pva_need pv.lock.password 20 || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && pva_to_create_pin
}

smoke_shortcuts_on() {
    pva_status_has '^pin.rec: OK, slot set' && pva_status_has '^fingerprint.rec: OK, slot set' \
        && pva_status_has '^keystore: .*pv_pin_' && pva_status_has '^keystore: .*pv_fp_'
}

smoke_finger_opens() {
    pva_lock_now && pva_prompt_open UNLOCK 20 || return 1
    if [ "$API" -lt 28 ]; then
        # Android 7: androidx.biometric draws its own dialog inside the activity (lock.md 9.1, E6).
        pv_ui_nodes | grep -q '^USE PIN|' || { pv_log "not the library's dialog: $(pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-300)"; return 1; }
    fi
    pva_finger 1 && pva_opened 20 && pva_no_prompt && pva_settle Passwords 60
}

smoke_pin_opens() {
    pva_lock_now && pva_prompt_open UNLOCK 20 && pva_prompt_use_pin && pva_no_prompt && pva_need pv.lock.pad 20 || return 1
    pva_pin "$SMOKE_PIN" && pva_opened 30 && pva_settle Passwords 60 && pva_row_with Smoketest 10
}

stage_pin_and_fingerprint() {
    pv_step "back in front: locked; the master password opens it, and \"Choose a PIN\" follows" -- smoke_unlock_to_pin_steps || return 1
    pv_step "a PIN is chosen and typed again" -- pva_choose_pin "$SMOKE_PIN" || return 1
    pv_step "the fingerprint offer: Use, finger 1 on the prompt; then the account's screen again, where the app was" -- eval 'pva_after_pin use Entry && same_entry' || return 1
    pv_step "status: a PIN slot and a fingerprint slot with their Keystore keys" -- smoke_shortcuts_on
    pv_step "Back to the list" -- eval 'pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords 20' || return 1
    pv_step "Lock now: the prompt opens by itself$([ "$API" -lt 28 ] && echo " (the library's own dialog)"); finger 1 opens the list" -- smoke_finger_opens
    pv_step "Lock now: \"Use PIN\" on the prompt, the pad; the PIN opens the list with the account" -- smoke_pin_opens
    pv_step "no crash" -- pva_no_crash
}

stage_install_and_start && stage_skeleton && stage_clipboard && stage_pin_and_fingerprint
pv_summary
