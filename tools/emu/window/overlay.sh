#!/usr/bin/env bash
# tools/emu/window/overlay.sh - build plan 3.12, [script]: a tap that arrives through another app's
# window does nothing in the real setup, edit and lock screens, in the activity's window, in a dialog
# and in a bottom sheet (architecture.md §8.4; lock.md §13.5; ux.md §3 "Overlays"). The method is the
# window lab's e4_overlay.py (docs/lab/window-input.md, Experiment 4): the helper app, another uid,
# draws a TYPE_APPLICATION_OVERLAY window with FLAG_NOT_TOUCHABLE and alpha 0.5 over the whole screen,
# as a screen filter does, so that every tap passes through it (the classic tapjacking set-up).
#
#   tools/emu/window/overlay.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37; the helper app needs API 26). The APK
#       defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk; the staging APK is
#       checked the same way.
#
# What the app does depends on the Android version (lock.md §13.5), and so does what is checked:
#   - Android 12 and newer (API 31+): the app's windows hide other apps' overlays
#     (setHideOverlayWindows). With the overlay added, it is not on the screen while a window of the
#     app shows (`dumpsys window`: isVisible=false), every visible window of the app, the dialog's and
#     the sheet's too, has HIDE_NON_SYSTEM_OVERLAY_WINDOWS, and the app is used as always. With the
#     app away the overlay is back, so it was there all the time.
#   - Android 8 to 11: the overlay stays on the screen, and the tap through it is dropped
#     (filterTouchesWhenObscured): on "Start" (Welcome, the activity's window; the app then says that
#     another app is drawing over the screen), on "No, it's new" (setup's dialog), on "Use" (the
#     generator's sheet) and on "Unlock" (the lock screen). Each time the same tap works once the
#     overlay is gone, so the tap was at the right place.
# The control is the helper app's own screen: there the overlay is on the screen and the tap through
# it presses the button.
# Prints PASS or FAIL per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a usage
# error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin overlay.sh "$@"

overlay_on() {
    win_cmd overlay-add >/dev/null || return 1
    WIN_OVERLAY=1
    pv_sleep 1
    keep_helper_unfrozen
}

# keep_helper_unfrozen: the helper adds its overlay from a broadcast receiver, so once the app's
# windows hide the overlay (Android 12 and newer) nothing makes the helper's process important. It
# becomes a cached process, at once or about a minute after its control screen was in front, and ten
# seconds later the system freezes it (`dumpsys activity`: use_freezer=true,
# freeze_debounce_timeout=10000; `am isfrozen`). A frozen process answers no input event, and its
# hidden overlay window still gets one whenever the touch mode changes, as it does at the key MOVE_END
# that pva_type sends before it types: five seconds later the system ends the helper as not responding
# (`dumpsys activity exit-info`: "bg anr: Input dispatching timed out (... PvWinOverlay is not
# responding. Waited 5000ms for TouchModeEvent(inTouchMode=false))"), and the overlay goes with its
# process. The last step then finds no overlay, though the app removed nothing. Seen at the device
# check of round R2 on API 37 with the debug build, whose steps took longer than that minute on a
# loaded machine, and reproduced by hand there. `am unfreeze --sticky` (Android 14 and newer) keeps
# the process unfrozen for its lifetime; a device without the command answers with an error, and
# nothing is lost.
keep_helper_unfrozen() {
    [ "$API" -ge 31 ] || return 0
    pv_shell am unfreeze --sticky "$WIN_HELPER" >/dev/null 2>&1
    return 0
}

# helper_end: how the helper's process last ended (Android 11 and newer), for a message.
helper_end() {
    pv_shell dumpsys activity exit-info "$WIN_HELPER" 2>/dev/null | tr -d '\r' | grep -m 2 -E 'reason=|description=' | sed 's/^ *//' | tr '\n' ' ' | cut -c1-300
}

overlay_off() {
    win_cmd overlay-remove >/dev/null
    pv_sleep 1
}

# windows: `dumpsys window windows` cut down to its list of windows, one line per window:
# "TITLE|PACKAGE|TYPE|visible or hidden|hides or shows" (the last: whether the window has the private
# flag HIDE_NON_SYSTEM_OVERLAY_WINDOWS). The sections after the list repeat some windows and are left out.
windows() {
    pv_shell dumpsys window windows 2>/dev/null | tr -d '\r' | awk '
        function flush() {
            if (inblock) print title "|" pkg "|" type "|" (visible ? "visible" : "hidden") "|" (hides ? "hides" : "shows")
            inblock = 0
        }
        /^  [^ ]/ {
            flush()
            if ($0 ~ /^  Window #[0-9]+ Window\{/) {
                inblock = 1; visible = 0; hides = 0; type = "?"; pkg = "?"
                title = $0; sub(/^  Window #[0-9]+ Window\{[0-9a-f]+ u[0-9]+ /, "", title); sub(/\}:$/, "", title)
            }
            next
        }
        !inblock { next }
        / package=/ && pkg == "?" { pkg = $0; sub(/.* package=/, "", pkg); sub(/ .*/, "", pkg) }
        / ty=[A-Z_]+/ && type == "?" { type = $0; sub(/.* ty=/, "", type); sub(/[ }].*/, "", type) }
        /HIDE_NON_SYSTEM_OVERLAY_WINDOWS/ { hides = 1 }
        /isVisible=true/ { visible = 1 }
        END { flush() }'
}

# overlay_visible: the helper's overlay window is on the screen. overlay_exists: it was added.
overlay_visible() { windows | grep -q '^PvWinOverlay|.*|visible|'; }

overlay_exists() { windows | grep -q '^PvWinOverlay|'; }

# app_windows: one line per visible window of the app: "TYPE hides" or "TYPE shows".
app_windows() { windows | awk -F'|' -v pkg="$PV_PACKAGE" '$2 == pkg && $4 == "visible" { print $3, $5 }'; }

grant() {
    pv_shell appops set "$WIN_HELPER" SYSTEM_ALERT_WINDOW allow >/dev/null || return 1
    pv_shell appops get "$WIN_HELPER" SYSTEM_ALERT_WINDOW 2>/dev/null | grep -q 'allow'
}

clicks() { win_cmd control | sed -n 's/.*clicks=\([0-9]*\).*/\1/p'; }

control() {
    local before after
    win_control_start || return 1
    before=$(clicks)
    overlay_on || return 1
    overlay_visible || { pv_log "the overlay is not on the screen over the control"; overlay_off; return 1; }
    pva_tap control_button || { overlay_off; return 1; }
    pv_sleep 1
    after=$(clicks)
    echo "  the control screen under the overlay: $(win_cmd control) (before the tap: clicks=$before)"
    overlay_off
    [ "$after" = $((before + 1)) ] || { pv_log "the tap through the overlay did not press the control's button ($before -> $after)"; return 1; }
}

# ---- Android 12 and newer: the app's windows hide the overlay --------------------------------------
# hidden WINDOWS: the overlay exists and is not on the screen, and the app has WINDOWS visible windows,
# each with HIDE_NON_SYSTEM_OVERLAY_WINDOWS.
hidden() {
    local windows
    overlay_exists || { pv_log "the overlay window is gone; the helper's process ended so: $(helper_end)"; return 1; }
    ! overlay_visible || { pv_log "the overlay is on the screen over the app"; return 1; }
    windows=$(app_windows)
    echo "  the app's visible windows: $(printf '%s' "$windows" | paste -sd, -)"
    [ "$(printf '%s\n' "$windows" | grep -c .)" -eq "$1" ] || { pv_log "not $1 visible windows of the app"; return 1; }
    ! printf '%s\n' "$windows" | grep -q ' shows$' || { pv_log "a window of the app does not hide overlays"; return 1; }
}

# back_when_away: with the app away the overlay is on the screen again. When it is not, says whether
# its window is still there, and how the helper's process ended when it is gone: then nothing shows
# that the overlay was there all the time (keep_helper_unfrozen).
back_when_away() {
    overlay_visible && return 0
    if overlay_exists; then
        pv_log "the overlay is still hidden with the app away; the focus: $(pv_focus)"
    else
        pv_log "the overlay window is gone; the helper's process ended so: $(helper_end)"
    fi
    return 1
}

# ---- Android 8 to 11: the tap through the overlay is dropped ---------------------------------------
# dropped BUTTON-ID STAYS-ID: with the overlay on the screen, a tap on BUTTON-ID changes nothing:
# STAYS-ID is still shown two seconds later. The button is brought onto the screen before the overlay
# is added: a swipe would be dropped as the tap is.
dropped() {
    local line x y i
    for i in 1 2 3 4 5 6; do
        pva_hide_keyboard
        line=$(pva_node "$1")
        y=$(printf '%s\n' "$line" | cut -d'|' -f7)
        [ -n "$line" ] && [ "$y" -gt 0 ] && [ "$y" -lt $((PVA_SCREEN_H - 160)) ] && break
        line=""
        pva_scroll_down
    done
    [ -n "$line" ] || { pv_log "no $1 on the screen to tap; ids: $(pva_ids)"; return 1; }
    x=$(printf '%s\n' "$line" | cut -d'|' -f6)
    overlay_on || return 1
    overlay_visible || { pv_log "the overlay is not on the screen over the app"; overlay_off; return 1; }
    pv_shell input tap "$x" "$y" >/dev/null
    pv_sleep 2
    if [ -z "$(pva_node "$2")" ]; then
        pv_log "the tap on $1 through the overlay went through: $2 is gone; ids: $(pva_ids)"
        overlay_off
        return 1
    fi
}

# The app's sentence about the overlay (ux.md §3) is on the screen: found by its tag, or, as long as
# the tag is not given out as a resource id, by its text, read from the APK (the devices are set to
# English, the language of the app's default texts).
notice_shown() {
    local text nodes
    nodes=$(pv_ui_nodes)
    [ -z "$(pva_node pv.window.obscuredNotice "$nodes")" ] || return 0
    text=$(win_app_string common_obscured_touch) || { pv_log "the APK has no string common_obscured_touch"; return 1; }
    printf '%s\n' "$nodes" | cut -d'|' -f1 | grep -qxF -- "$text" || { pv_log "neither pv.window.obscuredNotice nor its text is on the screen"; return 1; }
    echo "  the notice is shown, found by its text: its tag pv.window.obscuredNotice is not a resource id"
}

pv_step "the helper app may draw over other apps" -- grant || win_end
pv_step "control: over the helper's own screen the overlay shows and a tap through it presses the button" -- control || win_end
pv_step "the app installed afresh: Welcome" -- win_app_fresh || win_end
echo "overlay.sh: the $VARIANT build"

if [ "$API" -ge 31 ]; then
    pv_step "the overlay is added while the app is in front" -- overlay_on || win_end
    pv_step "overlay: Welcome (the activity's window): the overlay is hidden, the window hides overlays" -- hidden 1
    pv_step "setup: the own master password typed and used: the question (a dialog)" -- eval 'win_to_own && pva_type pv.setup.own "$WIN_MASTER" && win_use_own' || win_end
    pv_step "overlay: setup's dialog: the overlay is hidden, both windows hide overlays" -- hidden 2
    pv_step "setup finished, the form open, the generator (a bottom sheet)" -- eval 'win_reuse_no && win_to_again && pva_type pv.setup.again "$WIN_MASTER" && win_create_vault && win_open_form && win_generator' || win_end
    pv_step "overlay: the generator's sheet: the overlay is hidden, both windows hide overlays" -- hidden 2
    pv_step "the generated password used, the form closed, the app locked" -- eval 'pva_tap pv.gen.use && win_close_form && win_lock' || win_end
    pv_step "overlay: the lock screen: the overlay is hidden, the window hides overlays" -- hidden 1
    pv_step "the master password opens the app with the overlay still added" -- win_unlock
    pv_shell input keyevent KEYCODE_HOME >/dev/null
    pv_sleep 2
    pv_step "overlay: with the app away the overlay is on the screen again (it was there all the time)" -- back_when_away
    overlay_off
else
    pv_step "overlay: Welcome (the activity's window): the tap on Start through the overlay is dropped" -- dropped pv.open.CreateMasterPassword pv.route.Welcome
    pv_step "overlay: the app says that another app is drawing over the screen" -- notice_shown
    overlay_off
    pv_step "without the overlay: the same tap, a password of one's own typed and used: the question (a dialog)" -- eval 'win_to_own && pva_type pv.setup.own "$WIN_MASTER" && win_use_own' || win_end
    pv_step "overlay: setup's dialog: the tap on \"No, it's new\" through the overlay is dropped" -- dropped pv.setup.reuse.no pv.setup.reuse.no
    overlay_off
    pv_step "without the overlay: the same tap, setup finished, the form open, the generator (a bottom sheet)" -- eval 'win_reuse_no && win_to_again && pva_type pv.setup.again "$WIN_MASTER" && win_create_vault && win_open_form && win_generator' || win_end
    pv_step "overlay: the generator's sheet: the tap on Use through the overlay is dropped" -- dropped pv.gen.use pv.gen.use
    overlay_off
    pv_step "without the overlay: the same tap closes the sheet; the form closed, the app locked, the master password typed" -- eval 'pva_tap pv.gen.use && pv_sleep 1 && pva_absent pv.gen.use && win_close_form && win_lock && pva_type pv.lock.password "$WIN_MASTER" && pva_hide_keyboard' || win_end
    pv_step "overlay: the lock screen: the tap on Unlock through the overlay is dropped" -- dropped pv.lock.unlock pv.route.Locked
    overlay_off
    pv_step "without the overlay: the same tap unlocks" -- pva_submit_open pv.lock.unlock Passwords 90
fi
win_end
