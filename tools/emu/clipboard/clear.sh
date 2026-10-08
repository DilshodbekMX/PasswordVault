#!/usr/bin/env bash
# tools/emu/clipboard/clear.sh - build plan 3.8, [script]: the copy and its timed clear on an
# emulator (architecture.md D-14, D-25, §8.4; ux.md §6, §12 checks 1 and 2). The clipboard lab's
# cases (docs/lab/clipboard.md, clipexp.py) against the app itself.
#
#   tools/emu/clipboard/clear.sh SERIAL [--apk FILE] [--only CASE[,CASE...]]
#       SERIAL comes from tools/emu/boot.sh (API 24, 28 or 37). The APK defaults to this worktree's
#       app/build/outputs/apk/debug/app-debug.apk (tools/gradle.sh :app:assembleDebug). A staging
#       APK works too: its steps that need run-as print SKIPPED, and it keeps the 1-minute delay
#       that setup writes, because only run-as can change a setting before the Settings screen
#       exists. --only runs the named cases after the preparation; the names are in the list below.
#
# It installs the APK afresh, sets up a vault through the app's own screens (tools/emu/app.sh), adds
# one account and, on a debug build, sets "Clear copied text after" to 30 seconds in pv_settings.
# Every case then copies the account's password with the list row's copy button and watches the
# clipboard from outside the app, as the shell user (lib.sh and PvClip.java in this directory), so
# that looking neither brings the app to the front nor sends it to the background. A clip counts as
# the app's while its description carries the app's token; "cleared" means no clip at all (an empty
# clip without a label on Android 7 and 8, which have no other way to clear), never just another
# clip in its place. Times are the device's own clock; with run-as the planned clear is read from
# pv_settings, without it the copy's time plus the delay is taken.
#
#   prepare      install, set up, one account; setup wrote the 1-minute delay; the delay set to 30 s;
#                the toast watch sees a toast of the shell's (the control for "no toast")
#   copy         a copy shows no toast; the clip carries the sensitive flag and a 32-digit token and
#                has an empty label and the password as its text; the status row is shown; the token
#                and the deadline are in pv_settings; a clear alarm is set (exact below API 31)
#   background   Home: the clip is there shortly before its time and gone when it has come
#   kill         Home, then the process ends: `am kill` (API 28 and later), kill -9 through run-as
#                (API 24, where `am kill` is refused; debug only); gone on time all the same
#   swipe        Home, then the task is swiped away in Recents; gone on time
#   screenoff    the screen goes off, so the app locks at once: the lock does not clear the clip;
#                and with the screen off and the phone's lock screen up the clear comes on time (the
#                app's plan goes then: nobody can read the clipboard of a locked phone)
#   forcestop    Home, then `am force-stop`, which removes the alarm: the clip is still there after
#                its time, and bringing the app to the front clears it
#   other        Home, then another app copies: on API 24 and 28 that later text survives the time
#                of the clear; from Android 10 on the app cannot look from the background, and the
#                later text is cleared with it (the first-copy tip says so)
#   infront      another app copies while the app stays in front: the later text survives on every
#                version, and the row goes
#   return       another app copies, and the app is brought back before the time: the row goes, the
#                plan ends, and the later text is still there after the time
#   recopy       a second copy 10 s after the first: nothing is cleared at the first time, and the
#                clip is gone at the second
#   clearnow     "Clear now" in the status row clears at once and removes the plan
#   locknow      "Lock now" clears at once
#   entry        on an account's screen, which has no bar of the app's below it, the row stands
#                clear of the system's navigation bar
#   restricted   (API 28 and later; the last case, because it changes the delay) with battery use
#                "Restricted" and a 2-minute delay, the clip is cleared on time or stays until the
#                app is brought to the front, which clears it; the step says which
# Each case ends with the app's process free of crashes. Prints PASS, FAIL or SKIPPED per step and a
# summary; exit 0 when no step failed, 1 otherwise, 2 for a usage error, 3 for a refused serial.
# A run takes about 16 minutes on a debug build (12 on API 24).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"
source "$EMU/clipboard/lib.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
ALL_CASES="copy background kill swipe screenoff forcestop other infront return recopy clearnow locknow entry restricted"
CASES=$ALL_CASES
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --only) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; CASES=$(echo "$2" | tr ',' ' '); shift ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
for c in $CASES; do
    case " $ALL_CASES " in *" $c "*) ;; *) echo "clear.sh: no case '$c'; cases: $ALL_CASES" >&2; exit 2 ;; esac
done

pv_use "$SERIAL"
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
pva_init
API=$(pv_api)
PREFS=shared_prefs/pv_settings.xml
# Letters and digits only: adb's "input text" types them as they are.
ACCOUNT=Gmail
SECRET=Pv7Secret9Clip4Test
OTHER=LaterCopyOfAnotherApp
DELAY_S=60       # what setup writes (D-7); set_delay changes it
DEBUGGABLE=0
COPY_ID=""
TOKEN=""         # the token of the clip the last copy made
DEADLINE=0       # its planned clear on the device's wall clock, ms
DEADLINE_KNOWN=0 # 1: read from pv_settings; 0: the time of the copy plus the delay
echo "clear.sh on $SERIAL (API $API): $APK"

note() { echo "     $*"; }

# ---- the clipboard, read from outside the app ---------------------------------------------------
# clip_read: one reading of the description into CLIP_NOW (the device's clock, ms), CLIP_HAS,
# CLIP_TOKEN, CLIP_LABEL and CLIP_SENSITIVE (empty when the description has no such extra).
clip_read() {
    local d
    d=$(pvc_describe) || { pv_log "the clipboard could not be read"; return 1; }
    CLIP_NOW=$(printf '%s\n' "$d" | pvc_field now)
    CLIP_HAS=$(printf '%s\n' "$d" | pvc_field has)
    CLIP_TOKEN=$(printf '%s\n' "$d" | pvc_field token)
    CLIP_LABEL=$(printf '%s\n' "$d" | pvc_field label)
    CLIP_SENSITIVE=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ -n "$CLIP_NOW" ] && [ -n "$CLIP_HAS" ]
}

clip_says() { echo "has=$CLIP_HAS token=${CLIP_TOKEN:-none} label='$CLIP_LABEL'"; }

# clip_text: the text on the clipboard, read as another app reads it (empty when there is no clip).
clip_text() {
    local d
    d=$(pvc_get) || return 1
    printf '%s\n' "$d" | pvc_field text
}

# cleared: nothing is on the clipboard: no clip at all from API 28 on; below it, where a clear can
# only put an empty clip, a clip without a label, a token or a text.
cleared() {
    local text
    clip_read || return 1
    if [ "$API" -ge 28 ]; then
        [ "$CLIP_HAS" = false ] || { pv_log "the clipboard is not empty: $(clip_says)"; return 1; }
        return 0
    fi
    text=$(clip_text) || return 1
    [ -z "$CLIP_TOKEN" ] && [ -z "$CLIP_LABEL" ] && [ -z "$text" ] || { pv_log "the clipboard is not empty: $(clip_says), ${#text} characters of text"; return 1; }
}

# until_device MS: returns when the device's clock reads MS.
until_device() {
    local left
    clip_read || return 1
    left=$(($1 - CLIP_NOW))
    [ "$left" -le 0 ] || pv_sleep "$((left / 1000)).$(printf '%03d' $((left % 1000)))"
}

# ---- pv_settings (debug builds) -----------------------------------------------------------------
prefs() { pv_shell run-as "$PV_PACKAGE" cat "$PREFS" 2>/dev/null | tr -d '\r'; }
prefs_token() { prefs | sed -n 's/.*<string name="pending_clip_token">\([^<]*\)<.*/\1/p'; }
prefs_deadline() { prefs | sed -n 's/.*<long name="pending_clip_deadline_ms" value="\([0-9]*\)".*/\1/p'; }

# no_plan: nothing of a clip is left in pv_settings (debug builds) and no clear alarm is set.
no_plan() {
    local i token="" alarm=""
    for i in 1 2 3 4 5 6 7 8 9 10; do
        token=""
        [ $DEBUGGABLE = 0 ] || token=$(prefs_token)
        alarm=$(pvc_alarm | head -n 1)
        [ -z "$token" ] && [ -z "$alarm" ] && return 0
        pv_sleep 0.5
    done
    pv_log "a plan is left: pending_clip_token='$token' alarm='$alarm'"
    return 1
}

# ---- the app ------------------------------------------------------------------------------------
has_id() { printf '%s\n' "$1" | awk -F'|' -v id="$2" '$3 == id { found = 1 } END { exit !found }'; }

go_home() {
    local i
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do pva_in_front || return 0; pv_sleep 0.3; done
    pv_log "the app is still in front after Home: $(pv_focus)"
    return 1
}

# to_list: the app in front, unlocked, on the list. After more than the lock delay away the app
# locks a moment after it is shown again, so a list that was just brought back is looked at twice.
to_list() {
    local i nodes back=0
    pv_unlock >/dev/null 2>&1
    if ! pva_in_front; then
        pva_relaunch || return 1
        back=1
        pv_sleep 2
    fi
    for i in 1 2 3 4 5 6 7 8 9 10; do
        nodes=$(pv_ui_nodes)
        if has_id "$nodes" pv.route.Locked; then
            pva_unlock Passwords || return 1
            back=0
            continue
        fi
        if has_id "$nodes" pv.route.Passwords; then
            if [ $back = 1 ]; then back=0; pv_sleep 1; continue; fi
            pva_hide_keyboard
            return 0
        fi
        if has_id "$nodes" pv.route.Entry; then pv_shell input keyevent KEYCODE_BACK >/dev/null; fi
        pv_sleep 1
    done
    pv_log "the list is not shown; ids: $(pva_ids)"
    return 1
}

# copy: taps the row's copy button. Sets TOKEN, DEADLINE and DEADLINE_KNOWN.
copy() {
    local before i try deadline=""
    for try in 1 2; do
        to_list || return 1
        clip_read || return 1
        before=$CLIP_TOKEN
        pva_tap "$COPY_ID" || return 1
        for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
            clip_read || return 1
            [ -n "$CLIP_TOKEN" ] && [ "$CLIP_TOKEN" != "$before" ] && break
            pv_sleep 0.2
        done
        [ -n "$CLIP_TOKEN" ] && [ "$CLIP_TOKEN" != "$before" ] && break
        pv_log "no new clip of the app's after the tap (try $try): $(clip_says); ids: $(pva_ids)"
        [ "$try" -lt 2 ] || return 1
    done
    TOKEN=$CLIP_TOKEN
    DEADLINE=$((CLIP_NOW + DELAY_S * 1000))
    DEADLINE_KNOWN=0
    [ $DEBUGGABLE = 1 ] || return 0
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if [ "$(prefs_token)" = "$TOKEN" ]; then deadline=$(prefs_deadline); [ -z "$deadline" ] || break; fi
        pv_sleep 0.3
    done
    [ -n "$deadline" ] || { pv_log "pv_settings does not hold the clip's token and deadline: $(prefs | grep pending_clip | tr -d '\n')"; return 1; }
    DEADLINE=$deadline
    DEADLINE_KNOWN=1
}

# clear_on_time [LATE_MS]: the app's clip is still there shortly before its planned clear (2.5 s, or
# 4 s when that time is only known from the copy), and the clipboard is empty at most LATE_MS
# (default 3000) after it.
clear_on_time() {
    local late=${1:-3000} early=2500
    [ $DEADLINE_KNOWN = 1 ] || early=4000
    until_device $((DEADLINE - early)) || return 1
    clip_read || return 1
    [ "$CLIP_NOW" -lt $((DEADLINE - 500)) ] || { pv_log "too late to look before the clear: the device's clock is $((CLIP_NOW - DEADLINE)) ms past the clip's time"; return 1; }
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "$((DEADLINE - CLIP_NOW)) ms before its time the app's clip is no longer there: $(clip_says)"; return 1; }
    while :; do
        clip_read || return 1
        [ "$CLIP_TOKEN" = "$TOKEN" ] || break
        [ "$CLIP_NOW" -lt $((DEADLINE + late)) ] || { pv_log "the app's clip is still there $((CLIP_NOW - DEADLINE)) ms after its time"; return 1; }
        pv_sleep 0.1
    done
    if [ $DEADLINE_KNOWN = 1 ]; then
        note "the clip was gone at the reading $((CLIP_NOW - DEADLINE)) ms after its planned clear"
    else
        # Without run-as the planned clear is the first reading that saw the clip plus the delay: a
        # few hundred ms after the app's own, so a clear on time can read as slightly early here.
        note "the clip was gone at the reading $((CLIP_NOW - DEADLINE)) ms after its planned clear (estimated from the first reading of the clip)"
    fi
    cleared
}

# still_there_after MS: MS after its planned clear the app's clip is still on the clipboard.
still_there_after() {
    until_device $((DEADLINE + $1)) || return 1
    clip_read || return 1
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "the app's clip is gone: $(clip_says)"; return 1; }
}

row_shown() { pva_need pv.clip.row "${1:-5}"; }

row_gone() { # [SECONDS]
    local deadline=$((SECONDS + ${1:-5}))
    while :; do
        pva_absent pv.clip.row && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "the status row is still shown"; return 1; }
        pv_sleep 0.5
    done
}

# The row's button: the first node that can be tapped after the row's own, before the next pv.* id.
row_button() {
    pv_ui_nodes | awk -F'|' '
        $3 == "pv.clip.row" { on = 1; next }
        on && $3 ~ /^pv[.]/ { exit }
        on && $4 == "true" && $5 == "true" { print; exit }'
}

# The height of the system's navigation bar at the bottom of the screen, in pixels.
nav_inset() {
    local out bottom
    out=$(pv_shell dumpsys window 2>/dev/null | tr -d '\r')
    bottom=$(printf '%s\n' "$out" | sed -n 's/.*type=navigationBars frame=\[[0-9]*,\([0-9]*\)\]\[[0-9]*,[0-9]*\].*/\1/p' | head -n 1)
    [ -n "$bottom" ] || bottom=$(printf '%s\n' "$out" | sed -n 's/^ *mStable=[[(][0-9]*,[0-9]*[])]-\{0,1\}[[(][0-9]*,\([0-9]*\)[])].*/\1/p' | head -n 1)
    [ -n "$bottom" ] && [ "$bottom" -le "$PVA_SCREEN_H" ] && [ "$bottom" -gt $((PVA_SCREEN_H / 2)) ] || { pv_log "cannot read the navigation bar's place from dumpsys window"; return 1; }
    echo $((PVA_SCREEN_H - bottom))
}

task_in_recents() { pv_shell dumpsys activity recents 2>/dev/null | tr -d '\r' | grep -q "Recent #.*$PV_PACKAGE/"; }

# swipe_away: Home, Recents, and the app's card flicked off the screen: upwards where the cards
# stand side by side (Android 9 and later), sideways in Android 7's stack (the card's "title").
# Android 17's card has a "snapshot" node that carries the app's name. Android 9's Recents names no
# card at all (no text, no description on the card, its snapshot or its icon); there the card in
# the middle is taken, which after Home is the newest task, the app's. Up to three tries; the app's
# task must be gone, so a wrong card is never taken for the app's.
swipe_away() {
    local try nodes line x y
    for try in 1 2 3; do
        pv_shell input keyevent KEYCODE_HOME >/dev/null
        pv_sleep 1
        pv_shell input keyevent KEYCODE_APP_SWITCH >/dev/null
        pv_sleep 2
        nodes=$(pv_ui_nodes)
        line=$(printf '%s\n' "$nodes" | awk -F'|' '$3 == "snapshot" && $2 == "PasswordVault" { print; exit }')
        if [ -z "$line" ] && ! printf '%s\n' "$nodes" | awk -F'|' '$3 == "snapshot" && $2 != "" { named = 1 } END { exit !named }'; then
            line=$(printf '%s\n' "$nodes" | awk -F'|' -v mid=$((PVA_SCREEN_W / 2)) '
                $3 == "snapshot" { d = $6 - mid; if (d < 0) d = -d; if (best == "" || d < bestd) { best = $0; bestd = d } }
                END { if (best != "") print best }')
        fi
        if [ -n "$line" ]; then
            x=$(printf '%s\n' "$line" | cut -d'|' -f6); y=$(printf '%s\n' "$line" | cut -d'|' -f7)
            pv_shell input swipe "$x" "$y" "$x" $((y * 35 / 100)) 100 >/dev/null
        else
            line=$(printf '%s\n' "$nodes" | awk -F'|' '$3 == "title" && $1 == "PasswordVault" { print; exit }')
            [ -n "$line" ] || { pv_log "no card of the app in Recents (try $try); nodes: $(printf '%s\n' "$nodes" | awk -F'|' '$3 != "" { printf "%s ", $3 }' | cut -c1-300)"; continue; }
            x=$(printf '%s\n' "$line" | cut -d'|' -f6); y=$(printf '%s\n' "$line" | cut -d'|' -f7)
            pv_shell input swipe $((x - 240)) "$y" $((PVA_SCREEN_W - 20)) "$y" 120 >/dev/null
        fi
        pv_sleep 2
        if ! task_in_recents; then
            pv_shell input keyevent KEYCODE_HOME >/dev/null
            return 0
        fi
        pv_log "the swipe did not take the task away (try $try)"
    done
    pv_shell input keyevent KEYCODE_HOME >/dev/null
    return 1
}

screen_is_off() {
    case "$(pv_shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m 1 -o 'mWakefulness=[A-Za-z]*')" in
        mWakefulness=Asleep | mWakefulness=Dozing) return 0 ;;
    esac
    return 1
}

# ---- preparation ----------------------------------------------------------------------------------
install_fresh() {
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assembleDebug"; return 1; }
    pva_install "$APK" || return 1
    pvc_clear
}

set_up() {
    pva_start && pva_set_up || return 1
    pva_add_account "$ACCOUNT" aziz.example "$SECRET" example.org first || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20 || return 1
    COPY_ID=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]copy[.]/ { print $3; exit }')
    [ -n "$COPY_ID" ] || { pv_log "no copy button in the list; ids: $(pva_ids)"; return 1; }
}

setup_wrote_one_minute() {
    prefs | grep -q '<string name="clip_delay">1m</string>' || { pv_log "$PREFS: $(prefs | tr -d '\n')"; return 1; }
}

# set_delay STORED SECONDS: "Clear copied text after" through the settings file (the Settings screen
# comes with build plan 4.3). The app reads the file once per process, so it is stopped, edited and
# started again.
set_delay() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    # One string, so that the device's shell gets the single quotes around the expression.
    pv_shell "run-as $PV_PACKAGE sed -i 's#<string name=\"clip_delay\">[^<]*</string>#<string name=\"clip_delay\">$1</string>#' $PREFS" || return 1
    prefs | grep -q "<string name=\"clip_delay\">$1</string>" || { pv_log "$PREFS: $(prefs | tr -d '\n')"; return 1; }
    DELAY_S=$2
    to_list
}

# The watch that "a copy shows no toast" relies on does see a toast when there is one.
toast_watch_works() {
    local seen job
    pvc_toast "PV toast control" &
    job=$!
    seen=$(pvc_watch_toasts 3)
    wait "$job" || { pv_log "the shell's toast could not be shown"; return 1; }
    printf '%s\n' "$seen" | grep -q 'com\.android\.shell' || { pv_log "the watch did not see the shell's toast: '${seen:-nothing}'"; return 1; }
    pv_sleep 1
}

# ---- the cases ------------------------------------------------------------------------------------
# The watch runs from before the tap until 4 s after the clip was there: a short toast lasts 2 s, and
# a watch that began only once `copy` had seen the clip and read pv_settings could miss it.
copy_shows_no_toast() {
    local dir watch seen rc=0
    dir=$(mktemp -d) || return 1
    pvc_watch_toasts 90 "$dir/stop" > "$dir/seen" &
    watch=$!
    copy || rc=1
    [ $rc != 0 ] || pv_sleep 4
    touch "$dir/stop"
    wait "$watch"
    seen=$(cat "$dir/seen")
    rm -rf "$dir"
    [ $rc = 0 ] || return 1
    [ -z "$seen" ] || { pv_log "a toast was shown during the copy or in the 4 s after it: $seen"; return 1; }
}

copy_is_marked() {
    local text
    clip_read || return 1
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "the app's clip is gone: $(clip_says)"; return 1; }
    printf '%s' "$TOKEN" | grep -Eq '^[0-9a-f]{32}$' || { pv_log "the token is not 32 hex digits: '$TOKEN'"; return 1; }
    [ "$CLIP_SENSITIVE" = true ] || { pv_log "the sensitive flag is '${CLIP_SENSITIVE:-absent}'"; return 1; }
    [ -z "$CLIP_LABEL" ] || { pv_log "the clip's label is '$CLIP_LABEL', not empty"; return 1; }
    text=$(clip_text) || return 1
    [ "$text" = "$SECRET" ] || { pv_log "the clip's text is not the password (${#text} characters)"; return 1; }
}

copy_shows_the_row() {
    local button
    row_shown 5 || return 1
    button=$(row_button)
    [ -n "$button" ] || { pv_log "the status row has no button; ids: $(pva_ids)"; return 1; }
}

copy_is_in_settings() {
    local token deadline now
    token=$(prefs_token); deadline=$(prefs_deadline)
    [ "$token" = "$TOKEN" ] || { pv_log "pending_clip_token is '$token', the clip's is '$TOKEN'"; return 1; }
    clip_read || return 1
    now=$CLIP_NOW
    [ -n "$deadline" ] && [ "$deadline" -gt "$now" ] && [ "$deadline" -le $((now + DELAY_S * 1000)) ] \
        || { pv_log "pending_clip_deadline_ms is '$deadline'; the device's clock reads $now and the delay is $DELAY_S s"; return 1; }
}

copy_sets_an_alarm() {
    local alarm
    alarm=$(pvc_alarm)
    [ -n "$alarm" ] || { pv_log "dumpsys alarm lists no alarm for $PVC_CLEAR_ACTION"; return 1; }
    note "$(printf '%s\n' "$alarm" | grep -m 1 -E 'window=' | sed 's/^ *//' | cut -c1-110)"
    # Below API 31 an exact alarm needs no permission (D-14): its window is 0.
    [ "$API" -ge 31 ] || printf '%s\n' "$alarm" | grep -q 'window=0' || { pv_log "the alarm is not exact: $alarm"; return 1; }
}

background_clears() { copy && go_home && clear_on_time; }

# The process ends in the background: `am kill` where Android honours it, kill -9 as the app's own
# user on API 24, where the clipboard lab saw `am kill` refused.
killed_clears() {
    local pid how
    copy && go_home || return 1
    pv_sleep 6
    pid=$(pva_pid)
    [ -n "$pid" ] || { pv_log "the app has no process to end"; return 1; }
    if [ "$API" -ge 28 ]; then
        how="am kill"
        pv_shell am kill "$PV_PACKAGE" >/dev/null 2>&1
    else
        how="run-as kill -9"
        pv_shell run-as "$PV_PACKAGE" kill -9 "$pid" >/dev/null 2>&1
    fi
    pv_sleep 1
    [ "$(pva_pid)" != "$pid" ] || { pv_log "$how left the process $pid running"; return 1; }
    note "$how ended the process $pid; the alarm: $(pvc_alarm | head -n 1 | sed 's/^ *//' | cut -c1-90)"
    clear_on_time 4000
}

swiped_clears() {
    local pid
    copy || return 1
    pid=$(pva_pid)
    swipe_away || return 1
    note "the task is gone from Recents; the process was $pid and is now '$(pva_pid)'"
    clear_on_time 4000
}

# Screen off: the app locks at once (lock.md §10.2), which must not clear the clip (D-9c). While the
# phone itself is locked Android 9 and later answer every read with "nothing there", so the clip is
# read once the phone is unlocked again.
lock_does_not_clear() {
    copy || return 1
    pv_sleep 1
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 3
    screen_is_off || { pv_log "the screen did not go off"; pv_shell input keyevent KEYCODE_WAKEUP >/dev/null; return 1; }
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_sleep 1
    pv_unlock || return 1
    pva_in_front || pva_relaunch || return 1
    pva_on Locked 20 || return 1
    clip_read || return 1
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "after the app locked its clip is no longer there: $(clip_says)"; return 1; }
    [ "$CLIP_NOW" -lt "$DEADLINE" ] || { pv_log "the clip's time has passed meanwhile; nothing is shown by its being there"; return 1; }
}

# plan_there: the app still plans to clear the clip TOKEN. On a debug build: its token is in
# pv_settings, which the app empties right after the clear. Without run-as only the alarm can be
# seen, and that is not the whole plan: an alarm that is not exact (API 31 and later) may come a few
# seconds early, and the receiver then waits for the time with no alarm listed.
plan_there() {
    if [ $DEBUGGABLE = 1 ]; then
        [ "$(prefs_token)" = "$TOKEN" ]
    else
        [ -n "$(pvc_alarm | head -n 1)" ]
    fi
}

# The clear with the screen off and the phone locked. Nobody can read the clipboard then, so the time
# of the clear is the moment the app's plan goes (plan_there); without run-as, an alarm gone before
# the clip's time is taken for a receiver waiting for it. The clipboard itself is read after the
# phone is unlocked.
screen_off_clears() {
    local rc=0 gone_at=""
    copy || return 1
    pv_sleep 1
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 2
    if ! screen_is_off; then
        pv_log "the screen did not go off"
        rc=1
    elif until_device $((DEADLINE - 2500)); then
        if [ $DEBUGGABLE = 1 ] && ! plan_there; then
            pv_log "2.5 s before the clip's time its plan is gone: pending_clip_token '$(prefs_token)', alarm '$(pvc_alarm | head -n 1)'"
            rc=1
        fi
        while [ $rc = 0 ]; do
            if ! plan_there; then
                clip_read || { rc=1; break; }
                if [ $DEBUGGABLE = 1 ] || [ "$CLIP_NOW" -ge "$DEADLINE" ]; then
                    gone_at=$CLIP_NOW
                    break
                fi
            else
                clip_read || { rc=1; break; }
            fi
            [ "$CLIP_NOW" -lt $((DEADLINE + 4000)) ] || { pv_log "the plan is still there $((CLIP_NOW - DEADLINE)) ms after the clip's time: the clear has not run"; rc=1; }
        done
        [ -z "$gone_at" ] || note "the plan was seen gone by $((gone_at - DEADLINE)) ms after the planned clear, the screen off"
    else
        rc=1
    fi
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_sleep 1
    pv_unlock || return 1
    [ $rc = 0 ] || return 1
    cleared && no_plan
}

force_stop_keeps_the_clip() {
    copy && go_home || return 1
    pv_sleep 4
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null || return 1
    pv_sleep 1
    [ -z "$(pva_pid)" ] || { pv_log "the app still has a process after the force-stop"; return 1; }
    [ -z "$(pvc_alarm)" ] || note "the alarm is still listed after the force-stop: $(pvc_alarm | head -n 1)"
    still_there_after 4000
}

front_after_force_stop_clears() {
    local i
    pva_relaunch || return 1
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        clip_read || return 1
        [ "$CLIP_TOKEN" = "$TOKEN" ] || break
        pv_sleep 0.3
    done
    [ "$CLIP_TOKEN" != "$TOKEN" ] || { pv_log "the app's clip is still there with the app in front"; return 1; }
    cleared && no_plan
}

# Another app copies while the app is in the background. Android 7 to 9 let the app see that the
# clip is no longer its own, and it leaves the later text alone; from Android 10 on it cannot look,
# and clears (ux.md §6 item 2).
later_copy_in_the_background() {
    local text
    copy && go_home || return 1
    pv_sleep 4
    pvc_set "$OTHER" || return 1
    until_device $((DEADLINE + 4000)) || return 1
    if [ "$API" -ge 29 ]; then
        if ! cleared; then
            # What the plan looks like now, and the clipboard 10 s later, tell a late clear (the plan
            # still there) from a clip the app took for another app's (no plan) and from a force-stop
            # by someone else (the token still in pv_settings, no alarm, no process). The one failure
            # seen, in the 3B device check on API 37, was the last: Android Studio's Run had landed on
            # the device in the middle of the case (tools/emu/README.md, "Device facts").
            note "at the failure: pending_clip_token '$([ $DEBUGGABLE = 0 ] || prefs_token)' (the copy's: $TOKEN); alarm '$(pvc_alarm | head -n 1 | sed 's/^ *//')'; the app's process '$(pva_pid)'"
            pv_sleep 10
            clip_read && note "10 s later: $(clip_says)"
            return 1
        fi
        note "API $API: the app cannot read the clipboard from the background, so the later text was cleared too"
    else
        text=$(clip_text) || return 1
        [ "$text" = "$OTHER" ] || { clip_read; pv_log "the later text did not survive: $(clip_says)"; return 1; }
    fi
    no_plan
}

later_copy_in_front() {
    local text
    copy || return 1
    pv_sleep 4
    pvc_set "$OTHER" || return 1
    until_device $((DEADLINE + 3000)) || return 1
    pva_in_front || { pv_log "the app is no longer in front: $(pv_focus)"; return 1; }
    text=$(clip_text) || return 1
    [ "$text" = "$OTHER" ] || { clip_read; pv_log "the later text did not survive: $(clip_says)"; return 1; }
    row_gone 5 && no_plan
}

# The user copies elsewhere and comes back before the time: the app sees the other clip when its
# window has the focus, ends its plan and hides the row, on every version.
return_ends_the_plan() {
    local text
    copy && go_home || return 1
    pv_sleep 2
    pvc_set "$OTHER" || return 1
    pv_sleep 4
    pva_relaunch && pva_on Passwords 20 || return 1
    row_gone 6 && no_plan || return 1
    go_home || return 1
    until_device $((DEADLINE + 4000)) || return 1
    text=$(clip_text) || return 1
    [ "$text" = "$OTHER" ] || { clip_read; pv_log "the later text did not survive: $(clip_says)"; return 1; }
}

recopy_moves_the_clear() {
    local first_token first_deadline
    copy || return 1
    first_token=$TOKEN; first_deadline=$DEADLINE
    pv_sleep 8
    copy || return 1
    [ "$TOKEN" != "$first_token" ] || { pv_log "the second copy has the first one's token"; return 1; }
    [ "$DEADLINE" -ge $((first_deadline + 5000)) ] || { pv_log "the second copy's time ($DEADLINE) is not later than the first's ($first_deadline)"; return 1; }
    go_home || return 1
    until_device $((first_deadline + 1500)) || return 1
    clip_read || return 1
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "at the first copy's time the second clip is gone: $(clip_says)"; return 1; }
    clear_on_time
}

clear_now_clears() {
    local button i
    copy || return 1
    row_shown 5 || return 1
    button=$(row_button)
    [ -n "$button" ] || { pv_log "the status row has no button"; return 1; }
    pv_shell input tap "$(printf '%s\n' "$button" | cut -d'|' -f6)" "$(printf '%s\n' "$button" | cut -d'|' -f7)" >/dev/null
    for i in 1 2 3 4 5 6 7 8 9 10; do
        clip_read || return 1
        [ "$CLIP_TOKEN" = "$TOKEN" ] || break
        pv_sleep 0.2
    done
    [ "$CLIP_TOKEN" != "$TOKEN" ] || { pv_log "the app's clip is still there 2 s after \"Clear now\""; return 1; }
    note "gone $((DEADLINE - CLIP_NOW)) ms before its planned clear"
    cleared && no_plan && row_gone 6
}

lock_now_clears() {
    local i
    copy || return 1
    pva_tap pv.list.lockNow || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do
        clip_read || return 1
        [ "$CLIP_TOKEN" = "$TOKEN" ] || break
        pv_sleep 0.2
    done
    [ "$CLIP_TOKEN" != "$TOKEN" ] || { pv_log "the app's clip is still there 2 s after \"Lock now\""; return 1; }
    cleared && no_plan && pva_on Locked 20
}

# An account's screen has no bar of the app's under the row: the row must end above the system's
# navigation bar, with its button's centre at least 60 px above it (half the row is about 80 px).
row_clears_the_system_bar() {
    local row_id inset button y rc=0
    copy || return 1
    row_id=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
    [ -n "$row_id" ] || { pv_log "no row in the list"; return 1; }
    pva_tap "$row_id" && pva_on Entry 20 || return 1
    inset=$(nav_inset) || rc=1
    if [ $rc = 0 ] && row_shown 5; then
        button=$(row_button)
        y=$(printf '%s\n' "$button" | cut -d'|' -f7)
        if [ -n "$y" ] && [ "$y" -le $((PVA_SCREEN_H - inset - 60)) ]; then
            note "the button's centre is at y $y of $PVA_SCREEN_H; the system's bar is $inset px high"
        else
            pv_log "the row's button is at y '${y:-none}' of $PVA_SCREEN_H, in or too near the system's bar ($inset px)"
            rc=1
        fi
    else
        rc=1
    fi
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20 || rc=1
    return $rc
}

# Battery use "Restricted" (Settings > Apps > Battery; `RUN_ANY_IN_BACKGROUND ignore`): once the app
# has been in the background for about a minute Android runs neither its alarm nor its timer (D-14,
# measured on Android 17 with a 2-minute delay), so the copy stays until the app is opened again,
# which then clears it. With a shorter delay the timer may still be in time. Either way nothing is
# left once the app is in front.
restricted_clears_in_front_at_the_latest() {
    local rc=0 i
    copy && go_home || return 1
    pv_shell cmd appops set "$PV_PACKAGE" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1
    if ! pv_shell cmd appops get "$PV_PACKAGE" RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -q 'ignore'; then
        pv_log "battery use could not be set to Restricted: $(pv_shell cmd appops get "$PV_PACKAGE" RUN_ANY_IN_BACKGROUND 2>&1 | tr -d '\r' | head -n 1)"
        return 1
    fi
    until_device $((DEADLINE + 5000)) && clip_read || rc=1
    if [ $rc = 0 ] && [ "$CLIP_TOKEN" = "$TOKEN" ]; then
        note "$((CLIP_NOW - DEADLINE)) ms after its time the clip is still there, as the first-copy tip says; the app is brought to the front"
        pva_relaunch || rc=1
        for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
            clip_read || { rc=1; break; }
            [ "$CLIP_TOKEN" = "$TOKEN" ] || break
            pv_sleep 0.3
        done
        [ "$CLIP_TOKEN" != "$TOKEN" ] || { pv_log "the app's clip is still there with the app in front"; rc=1; }
    elif [ $rc = 0 ]; then
        note "the clear ran in the background all the same, by $((CLIP_NOW - DEADLINE)) ms after its time"
    fi
    [ $rc != 0 ] || cleared || rc=1
    [ $rc != 0 ] || no_plan || rc=1
    pv_shell cmd appops set "$PV_PACKAGE" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    return $rc
}

run_case() {
    case "$1" in
        copy)
            pv_step "copy: no toast is shown" -- copy_shows_no_toast || return
            pv_step "copy: the clip carries the sensitive flag and a token; its label is empty, its text the password" -- copy_is_marked
            pv_step "copy: the status row is shown, with its button" -- copy_shows_the_row
            pv_step "copy: the token and the deadline are in pv_settings" needs-debuggable -- copy_is_in_settings
            pv_step "copy: a clear alarm is set (exact below API 31)" -- copy_sets_an_alarm ;;
        background) pv_step "background: gone $DELAY_S s after the copy, not before" -- background_clears ;;
        kill)
            if [ "$API" -ge 28 ]; then
                pv_step "kill: gone on time after am kill" -- killed_clears
            else
                pv_step "kill: gone on time after run-as kill -9 (am kill is refused on API 24)" needs-debuggable -- killed_clears
            fi ;;
        swipe) pv_step "swipe: gone on time after the task was swiped from Recents" -- swiped_clears ;;
        screenoff)
            pv_step "screenoff: the app's lock at screen-off does not clear the clip" -- lock_does_not_clear
            pv_step "screenoff: gone on time with the screen off and the phone locked" -- screen_off_clears ;;
        forcestop)
            if pv_step "forcestop: after am force-stop the clip is still there 4 s after its time" -- force_stop_keeps_the_clip; then
                pv_step "forcestop: bringing the app to the front clears it" -- front_after_force_stop_clears
            fi ;;
        other)
            if [ "$API" -ge 29 ]; then
                pv_step "other: a text another app copied afterwards is cleared too (Android 10 and later cannot look)" -- later_copy_in_the_background
            else
                pv_step "other: a text another app copied afterwards survives" -- later_copy_in_the_background
            fi ;;
        infront) pv_step "infront: with the app in front, a text another app copied afterwards survives; the row goes" -- later_copy_in_front ;;
        return) pv_step "return: back in the app with another clip there, the row and the plan go; the later text survives" -- return_ends_the_plan ;;
        recopy) pv_step "recopy: a second copy moves the clear to its own time" -- recopy_moves_the_clear ;;
        clearnow) pv_step "clearnow: \"Clear now\" clears at once and removes the plan" -- clear_now_clears ;;
        locknow) pv_step "locknow: \"Lock now\" clears at once" -- lock_now_clears ;;
        entry) pv_step "entry: on an account's screen the row stands clear of the system's navigation bar" -- row_clears_the_system_bar ;;
        restricted)
            if [ "$API" -lt 28 ]; then
                pv_report SKIPPED "restricted: with battery use Restricted the clip is gone at the latest when the app is in front" "Android $API has no such setting"
            else
                # Two minutes, so that Android has stopped the app's alarm and timer before the clip's time.
                pv_step "restricted: the clipboard delay set to 2 minutes" needs-debuggable -- set_delay 2m 120
                pv_step "restricted: with battery use Restricted the clip is gone at the latest when the app is in front" -- restricted_clears_in_front_at_the_latest
            fi ;;
    esac
}

pv_step "install the app afresh" -- install_fresh || { pv_summary; exit 1; }
pv_step "set up a vault and add an account" -- set_up || { pv_summary; exit 1; }
pv_is_debuggable && DEBUGGABLE=1
pv_step "setup wrote the 1-minute clipboard delay" needs-debuggable -- setup_wrote_one_minute
pv_step "the clipboard delay set to 30 seconds (pv_settings, a new process, unlocked)" needs-debuggable -- set_delay 30s 30
[ $DELAY_S = 30 ] || note "the delay is $DELAY_S s in every case below"
pv_step "the toast watch sees a toast (the shell's own)" -- toast_watch_works
for c in $CASES; do
    run_case "$c"
    pv_step "$c: no crash" -- pva_no_crash
done
pv_unlock >/dev/null 2>&1
pv_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
pv_summary
