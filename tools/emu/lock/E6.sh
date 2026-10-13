#!/usr/bin/env bash
# tools/emu/lock/E6.sh - lock.md §15.3 check E6 (Android 7, 9, 17): fingerprint with
# androidx.biometric 1.4.0-alpha07, setConfirmationRequired left at its default as the app ships it:
# turned on, an unlock with one touch of an enrolled finger and no further tap, an unknown finger
# leaves the prompt open, and on Android 7 the library's own dialog inside the activity, whose "Use
# PIN" returns to the pad.
#
#   tools/emu/lock/E6.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. Run it with the
#       staging APK too (R8: build check R-16 keeps the library's fingerprint callbacks, which Android
#       7 to 8.1 reach; lock.md §9.1 item 7).
#
# The bases have emulator fingers 1 and 2 enrolled (tools/emu/README.md, boot.sh --provision): the
# "enrol" of lock.md E6 is theirs, checked here with dumpsys. Finger 5 is not enrolled, so it plays
# the part of the lab's "touch 2" (a finger the sensor does not recognise).
#
# Steps, after the app is installed afresh:
#   - the phone has enrolled fingers (dumpsys fingerprint);
#   - setup with the PIN, and "Use fingerprint" on the offer: the prompt "Turn on fingerprint unlock",
#     finger 1: fingerprint.rec has a slot and the Keystore a pv_fp_ key;
#   - Lock now: the prompt "Open PasswordVault" opens by itself (lock.md §9.2 rule 7): on API 24 to 27
#     the library's dialog inside the app's activity, from API 28 the system's prompt in a window of
#     its own;
#   - finger 5: not recognised, the prompt stays open and the app stays locked;
#   - finger 1, and no further tap: the list (no "Confirm" button with a fingerprint); the PIN's
#     counts did not change (a fingerprint unlock resets nothing, lock.md §6.4);
#   - Lock now, the prompt's "Use PIN": the pad, and no prompt opens again by itself on this visit;
#   - the pad's fingerprint key opens the prompt again; finger 1 opens the app.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E6 "$@"

fingers_enrolled() {
    local n
    n=$(pvl_enrolled_fingers)
    [ "$n" -ge 1 ] 2>/dev/null || { pv_log "enrolled fingerprints: $n"; return 1; }
    pvl_note "E6 on API $API: $n fingerprints enrolled on the base (fingers 1 and 2)"
}

set_up() {
    pvl_set_up "$PVL_PIN" fingerprint || return 1
    pvl_expect_fp read=OK slot=set off=none || return 1
    [ "$(pvl_alias_count fp)" = 1 ] || { pv_log "no pv_fp_ key: $(pvl_item keystore:)"; return 1; }
}

# The prompt that opens by itself after Lock now, and where it is drawn.
prompt_opens_itself() {
    local focus
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_prompt_up 20 || return 1
    focus=$(pv_focus)
    if [ "$API" -lt 28 ]; then
        case "$focus" in
            *"$PV_PACKAGE"/*) ;;
            *) pv_log "on API $API the library's dialog should be in the app's activity; focus: $focus"; return 1 ;;
        esac
        pva_need fingerprint_icon 5 || return 1
    else
        case "$focus" in
            *"$PV_PACKAGE"/*) pv_log "the prompt is not the system's window; focus: $focus"; return 1 ;;
        esac
    fi
    pvl_status || return 1
    case "$(pvl_item prompt:)" in "prompt: UNLOCK, launch "*) ;; *) pv_log "$(pvl_item prompt:)"; return 1 ;; esac
    pvl_note "E6 on API $API ($VARIANT): the unlock prompt is drawn in '$focus'"
}

# The prompt must show that it got the finger ("Not recognized" on its line), so that a touch lost
# before the prompt was drawn does not pass as "the prompt stayed open".
unknown_finger_leaves_it_open() {
    local before line deadline
    pv_sleep 2
    before=$(pvl_prompt_message)
    pvl_finger "$PVL_UNKNOWN_FINGER" || return 1
    deadline=$((SECONDS + 6))
    while :; do
        line=$(pvl_prompt_message)
        printf '%s\n' "$line" | grep -q -i 'not recogni' && break
        [ $SECONDS -lt $deadline ] || { pv_log "the prompt never said that finger $PVL_UNKNOWN_FINGER was not recognized (its line: '${line:-none}', before the touch: '${before:-none}')"; return 1; }
        pv_sleep 0.3
    done
    pvl_note "E6 on API $API: after finger $PVL_UNKNOWN_FINGER the prompt's line went from '${before:-none}' to '$line'"
    pv_sleep 2
    pvl_prompt_shown || { pv_log "the prompt closed after an unknown finger"; return 1; }
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: "*) ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
}

finger_opens() {
    pvl_finger "$PVL_FINGER" || return 1
    pvl_prompt_down 15 || return 1
    pvl_open_list 30 || return 1
    pvl_expect_pin row=0 total=0 charged=false slot=set
}

use_pin_returns_to_pad() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_prompt_up 20 || return 1
    pv_sleep 1
    pvl_prompt_negative || return 1
    pvl_prompt_down 10 || return 1
    pvl_wait_screen pad 10 || return 1
    pv_sleep 3
    pvl_no_prompt || { pv_log "a prompt opened again by itself on the same visit"; return 1; }
    pvl_wait_screen pad 5
}

fingerprint_key_opens_prompt() {
    pva_tap pv.pin.fingerprint || return 1
    pvl_touch_prompt "$PVL_FINGER" 15 || return 1
    pvl_prompt_down 15 || return 1
    pvl_open_list 30
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "the phone has enrolled fingers" -- fingers_enrolled
pv_step "set up with the PIN and fingerprint unlock (finger 1): slot and key exist" -- set_up || pvl_end
if [ "$API" -lt 28 ]; then
    pv_step "Lock now: the prompt opens by itself, the library's dialog inside the activity" -- prompt_opens_itself || pvl_end
else
    pv_step "Lock now: the prompt opens by itself, the system's prompt" -- prompt_opens_itself || pvl_end
fi
pv_step "an unknown finger (5): the prompt stays open, the app locked" -- unknown_finger_leaves_it_open
pv_step "finger 1 and no further tap: the list; no count changed" -- finger_opens || pvl_end
pv_step "Lock now, \"Use PIN\" on the prompt: the pad, no prompt again on this visit" -- use_pin_returns_to_pad || pvl_end
pv_step "the pad's fingerprint key opens the prompt; finger 1 opens the app" -- fingerprint_key_opens_prompt
pvl_end
