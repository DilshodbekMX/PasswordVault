#!/usr/bin/env bash
# tools/emu/lock/E17.sh - lock.md §15.3 check E17 (Android 17; the last item Android 7): the session.
# Away for 31 s: locked; "Immediately"; the screen off: locked at once; 90 s in the file picker with
# "Immediately": still open; 6 minutes in the file picker: still open; the picker and the screen off:
# locked, and the task resumes after the unlock; taps through an overlay dropped (the method of
# docs/lab/window-input-artifacts/e4_overlay.py). On Android 7 the app starts and shows the pad: the
# autofill call of lock.md §13.5 is skipped there.
#
#   tools/emu/lock/E17.sh SERIAL [--apk FILE] [--short]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. On API 26 and newer
#       every item runs (about 14 minutes: the 6 minutes in the picker); --short leaves the 6 minutes out.
#       On Android 7 (API 24 and 25) only the last item runs, and the others print SKIPPED (lock.md
#       lists them for Android 17).
#
# The file picker is the system's (DocumentsUI), opened the way the app opens it for an import:
# Settings › Import › "Another password app or a spreadsheet saved as CSV" (OpenDocument; the app calls
# session.beginExternal() just before, lock.md §10.3). The file the resumed task reads is a small CSV
# pushed to /sdcard/Download/ first (removed at the end).
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - away 31 s (Home, then the launcher's intent): the lock screen (the delay that setup wrote, 30 s);
#     the PIN opens;
#   - Settings › "Lock the app after leaving it" › Immediately; away 2 s: the lock screen; the PIN;
#   - the screen off for 3 s: the lock screen; the PIN;
#   - Settings › Import, the CSV source: the picker in front; 90 s; Back: the app is open (not locked)
#     on the import's screen;
#   - the picker again for 6 minutes (360 s; not with --short); Back: still open;
#   - the picker again; the screen off for 3 s; on again and the phone unlocked: the picker; the file
#     picked: the app shows its lock screen; the PIN: the import goes on with that file (its screen
#     shows the file's name or its preview): the task resumed;
#   - an overlay of the window helper (another app, FLAG_NOT_TOUCHABLE, alpha 0.5, the whole screen)
#     over the pad: from Android 12 the app's window hides it (it is not visible while the pad is, and
#     the window has HIDE_NON_SYSTEM_OVERLAY_WINDOWS) and the PIN works; on Android 8 to 11 the tap on
#     a key through it is dropped (the dots stay empty) and works once the overlay is gone.
# Android 7: setup with the PIN, Lock now: the pad, and no crash.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
SHORT=0
ARGS=()
for a in "$@"; do
    case "$a" in --short) SHORT=1 ;; *) ARGS+=("$a") ;; esac
done
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E17 "${ARGS[@]}"

CSV_NAME=pvl-e17-import.csv
CSV_DEVICE=/sdcard/Download/$CSV_NAME
PICKER_RE='documentsui|DocumentsActivity|PickActivity|com.android.documentsui|com.google.android.documentsui'

set_up() { pvl_set_up "$PVL_PIN" - ; }

pin_opens() { pvl_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30; }

locked_after_away() { pva_away "$1" && pva_on Locked 20; }

immediately() {
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.settings.lockDelay && pva_need pv.settings.lockDelay.IMMEDIATELY 10 || return 1
    pva_tap pv.settings.lockDelay.IMMEDIATELY || return 1
    pva_on Settings 10 && pva_tap pv.tab.Passwords && pva_on Passwords 10
}

screen_off_locks() { pva_screen_off 3 && pva_on Locked 20; }

picker_in_front() { pv_focus | grep -q -E "$PICKER_RE"; }

# open_picker: Settings › Import › the CSV source: the system's picker in front.
open_picker() {
    local deadline
    if ! pva_present pv.import.sources 3; then
        to_list || return 1
        pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
        pva_tap pv.open.Import || return 1
        pva_need pv.import.sources 20 || return 1
    fi
    pva_tap pv.import.source.csv || return 1
    deadline=$((SECONDS + 20))
    until picker_in_front; do
        [ $SECONDS -lt $deadline ] || { pv_log "no picker in front: $(pv_focus); ids: $(pva_ids)"; return 1; }
        pva_present pv.import.chooseFile 1 && pva_tap pv.import.chooseFile
        pv_sleep 1
    done
}

# to_list: the list, with nothing over it: Back until the list or the lock screen shows (the import's
# screens, Settings), the PIN when locked, then the Passwords tab.
to_list() {
    local i
    for i in 1 2 3 4 5; do
        pva_present pv.route.Passwords 2 && return 0
        if pva_present pv.route.Locked 1 || pvl_prompt_shown; then pin_opens; return; fi
        if pva_present pv.tab.Passwords 1; then pva_tap pv.tab.Passwords; else pv_shell input keyevent KEYCODE_BACK >/dev/null; fi
        pv_sleep 1.5
        pva_in_front || pva_relaunch
    done
    pva_on Passwords 5
}

# in_picker SECONDS: the picker stays in front for SECONDS; then Back: the app, open.
in_picker() {
    local t=$1 waited=0
    while [ $waited -lt "$t" ]; do
        pv_sleep 10
        waited=$((waited + 10))
        picker_in_front || { pv_log "the picker left the front after $waited s: $(pv_focus)"; return 1; }
    done
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pv_sleep 2
    pva_in_front || { pv_log "the app is not in front after Back: $(pv_focus)"; return 1; }
    pva_absent pv.route.Locked || { pv_log "the app locked"; return 1; }
    pvl_status || return 1
    [ "$(pvl_lock_state)" = unlocked ] || { pv_log "the lock is '$(pvl_lock_state)'"; return 1; }
}

push_csv() {
    local f=$PVL_OUT/$CSV_NAME
    printf 'name,url,username,password\nE17 Bank,https://e17.example,e17user,E17pw-Secret9\n' > "$f"
    pv_adb push "$f" "$CSV_DEVICE" >/dev/null 2>&1 || return 1
    pvl_undo "rm -f $CSV_DEVICE"
    # So that DocumentsUI's Downloads lists it (API 30 and newer; tools/emu/README.md, the device check
    # of 4A); older levels list the folder as it is.
    [ "$API" -lt 30 ] || pv_shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
    return 0
}

# pick_file: in the picker, the pushed CSV (by its name; the Downloads root when it is not listed).
pick_file() {
    local i nodes
    for i in 1 2 3 4 5 6 7 8; do
        nodes=$(pv_ui_nodes)
        if pv_ui_tap "^$CSV_NAME[|]" "$nodes" >/dev/null; then
            pv_sleep 2
            picker_in_front || return 0
            continue
        fi
        # Not listed here: the roots (the drawer), then Downloads.
        if ! pv_ui_tap '^(Downloads|Download)[|]' "$nodes" >/dev/null; then
            pv_ui_tap '[|](Show roots|Open navigation drawer)[|]|^[|](Show roots|Open navigation drawer)' "$nodes" >/dev/null \
                || pv_ui_tap '[|]Show roots$|Show roots' "$nodes" >/dev/null
        fi
        pv_sleep 1.5
    done
    pv_log "the file $CSV_NAME could not be picked; focus $(pv_focus)"
    return 1
}

picker_screen_off_resume() {
    open_picker || return 1
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 3
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_sleep 1
    pv_unlock || return 1
    pv_sleep 2
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: "*) ;; *) pv_log "the app did not lock at the screen-off: '$(pvl_lock_state)'"; return 1 ;; esac
    picker_in_front || { pv_log "the picker is not in front after the screen came back: $(pv_focus)"; return 1; }
    pick_file || return 1
    pvl_wait_screen 'pad|prompt' 20 || return 1
    pva_pin "$PVL_PIN" || return 1
    # The task resumed: the import's screen with the file (its name, or its preview), not the list.
    pv_ui_wait '[|]pv\.import\.(fileName|preview|found|reading|paused|import|columns)$' 30 \
        || { pv_log "the import did not go on after the unlock; ids: $(pva_ids)"; return 1; }
}

# ---- the overlay -----------------------------------------------------------------------------------
# The window helper (tools/emu/window/lib.sh): its build, install and the command overlay-add.
OVERLAY=0
overlay_on() {
    source "$EMU/window/lib.sh"
    win_helper_build && win_helper_install || return 1
    pv_shell appops set "$WIN_HELPER" SYSTEM_ALERT_WINDOW allow >/dev/null || return 1
    win_cmd overlay-add >/dev/null || return 1
    OVERLAY=1
    pvl_undo "am broadcast -n $WIN_HELPER/.LabCommandReceiver --es cmd overlay-remove"
    [ "$API" -lt 34 ] || pv_shell am unfreeze --sticky "$WIN_HELPER" >/dev/null 2>&1
    pv_sleep 1
}

overlay_off() { win_cmd overlay-remove >/dev/null; OVERLAY=0; pv_sleep 1; }

# window_line TITLE-REGEX: "visible|hides" for the first window whose title matches, from `dumpsys
# window windows` (adapted from tools/emu/window/overlay.sh's windows()).
window_line() {
    pv_shell dumpsys window windows 2>/dev/null | tr -d '\r' | awk -v re="$1" '
        function flush() { if (inblock && title ~ re) { print (visible ? "visible" : "hidden") "|" (hides ? "hides" : "shows"); done = 1 } inblock = 0 }
        done { next }
        /^  [^ ]/ { flush(); if ($0 ~ /^  Window #[0-9]+ Window\{/) { inblock = 1; visible = 0; hides = 0; title = $0 } next }
        !inblock { next }
        /HIDE_NON_SYSTEM_OVERLAY_WINDOWS/ { hides = 1 }
        /isVisible=true/ { visible = 1 }
        END { flush() }'
}

overlay_over_pad() {
    local over app
    pvl_lock_now && pvl_pad 20 || return 1
    overlay_on || return 1
    pvl_pad 10 || return 1
    if [ "$API" -ge 31 ]; then
        over=$(window_line 'PvWinOverlay')
        app=$(window_line "$PV_PACKAGE/$PV_PACKAGE.MainActivity")
        pvl_note "E17 on API $API: the overlay window: '$over'; the app's window: '$app'"
        case "$over" in hidden*) ;; *) pv_log "the overlay is '$over' over the pad"; overlay_off; return 1 ;; esac
        case "$app" in visible\|hides) ;; *) pv_log "the app's window is '$app'"; overlay_off; return 1 ;; esac
        pva_pin "$PVL_PIN" && pvl_open_list 30 || { overlay_off; return 1; }
    else
        local line x y
        line=$(pva_node "pv.pin.key.${PVL_PIN:0:1}")
        x=$(printf '%s\n' "$line" | cut -d'|' -f6); y=$(printf '%s\n' "$line" | cut -d'|' -f7)
        pv_shell input tap "$x" "$y" >/dev/null
        pv_sleep 1.5
        pvl_expect "$(pva_node pv.pin.dots | cut -d'|' -f2)" plural common_pin_dots 0 || { overlay_off; pv_log "the tap through the overlay typed a digit"; return 1; }
        overlay_off
        pv_shell input tap "$x" "$y" >/dev/null
        pv_sleep 1.5
        pvl_expect "$(pva_node pv.pin.dots | cut -d'|' -f2)" plural common_pin_dots 1 || { pv_log "the same tap without the overlay typed nothing"; return 1; }
        pva_pin "${PVL_PIN:1}" && pvl_open_list 30 || return 1
    fi
    [ $OVERLAY = 0 ] || overlay_off
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
if [ "$API" -lt 26 ]; then
    pv_step "Android 7: Lock now: the pad (the autofill call is skipped)" -- eval 'pvl_lock_now && pvl_pad 20'
    pv_step "the PIN opens the app" -- pin_opens
    for item in "away 31 s: locked" "Immediately: away 2 s: locked" "the screen off: locked at once" \
        "90 s in the file picker with Immediately: still open" "6 minutes in the file picker: still open" \
        "the picker and the screen off: locked; the task resumes after the unlock" "taps through an overlay dropped"; do
        pvl_skip "$item" "lock.md lists it for Android 17; on Android 7 E17 checks only the pad"
    done
    pvl_end
fi
pv_step "away 31 s: locked" -- locked_after_away 31
pv_step "the PIN opens the app" -- pin_opens || pvl_end
pv_step "Settings: lock the app after leaving it: Immediately" -- immediately || pvl_end
pv_step "with Immediately, away 2 s: locked" -- locked_after_away 2
pv_step "the PIN opens the app" -- pin_opens || pvl_end
pv_step "the screen off for 3 s: locked at once" -- screen_off_locks
pv_step "the PIN opens the app" -- pin_opens || pvl_end
pv_step "Settings › Import, CSV: the file picker in front" -- open_picker || pvl_end
pv_step "90 s in the file picker with Immediately: still open" -- in_picker 90
if [ $SHORT = 1 ]; then
    pvl_skip "6 minutes in the file picker: still open" "--short"
else
    pv_step "the file picker again" -- open_picker || pvl_end
    pv_step "6 minutes in the file picker: still open" -- in_picker 360
fi
pv_step "a CSV pushed to Download" -- push_csv || pvl_end
pv_step "the picker and the screen off: locked; the file picked; after the PIN the import goes on" -- picker_screen_off_resume
pv_step "back to the list" -- to_list
pv_step "an overlay over the pad: hidden by the app (Android 12+) or its taps dropped" -- overlay_over_pad
pvl_end
