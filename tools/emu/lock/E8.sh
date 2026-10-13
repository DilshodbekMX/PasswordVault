#!/usr/bin/env bash
# tools/emu/lock/E8.sh - lock.md §15.3 check E8 (Android 17): the lockout trap. Wrong fingers until
# the system's prompt asks for the phone's own PIN; the phone's PIN typed there, then a touch: "Fingerprint
# did not open the app this time. Enter your app PIN.", and nothing removed. Five wrong fingers lock
# the phone: on return the pad is shown, the late code-10 callback is dropped and no prompt loop
# starts; after a cold boot the fingerprint key opens a working prompt (lock.md §9.2 rule 8, §9.3).
#
#   tools/emu/lock/E8.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh and runs API 37 (the lab's findings are the API 37 image's;
#       on another API level the script says so and stops). The APK defaults to the debug build.
#
# E8 leaves the emulator's fingerprint sensor locked out until a reboot (docs/lab/biometric.md case 4:
# the emulator HAL's resetLockout always fails), so it ends with a reboot of the guest: run it last on
# a device, or boot a new one after it.
#
# The emulator's sensor locks out after a number of failures that it never resets, and Android's
# adaptive authentication locks the phone after five failures in a row; which of the two comes first
# depends on what the sensor counted before. So the script touches the unknown finger 5 one touch at a
# time, and handles each of the two as it comes, until both have been seen:
#   - the prompt turns into the system's "Enter your PIN" (PIN is required to recover biometrics):
#     the phone's PIN 1111 typed there, then finger 1: the pad says "Fingerprint did not open the app
#     this time. Enter your app PIN." (KEY_USER_NOT_AUTHENTICATED at doFinal: TEMPORARY), and nothing
#     is removed: fingerprint.rec keeps its slot, the Keystore its pv_fp_ key, pin.rec its counts;
#   - the phone locks (its keyguard): unlocked with 1111, the app shows its lock screen of a new
#     visit: the pad, with at most one prompt launched by itself and still open 6 s later (a code 10
#     of the old prompt taken for the new one would close it; a loop would launch again and again),
#     and nothing removed.
# Then the guest is rebooted (the sensor's lockout ends only so), and the pad's fingerprint key opens
# a prompt in which finger 1 opens the app.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E8 "$@"
if [ "$API" != 37 ]; then
    echo "E8: lock.md lists E8 for Android 17 (API 37); $SERIAL has API $API" >&2
    trap - EXIT
    exit 2
fi

TRAP_SEEN=0
PHONE_LOCK_SEEN=0
FP_ALIAS=""

set_up() {
    pvl_set_up "$PVL_PIN" fingerprint || return 1
    pvl_expect_fp slot=set || return 1
    FP_ALIAS=$(pvl_item keystore: | tr ' ' '\n' | grep '^pv_fp_')
    [ -n "$FP_ALIAS" ] || { pv_log "no pv_fp_ key"; return 1; }
}

lock_with_prompt() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_prompt_up 20
}

# The system prompt's own PIN screen (lockout): its PIN pad or its title.
credential_screen() { pv_ui_nodes | grep -q -E '[|](cred_pin_pad|compose_credential_view|lockPassword)[|]'; }

nothing_removed() {
    pvl_status || return 1
    [ "$(pvl_fp slot)" = set ] || { pv_log "fingerprint.rec lost its slot: $(pvl_show_status)"; return 1; }
    pvl_item keystore: | tr ' ' '\n' | grep -qx "$FP_ALIAS" || { pv_log "the key $FP_ALIAS is gone: $(pvl_item keystore:)"; return 1; }
    [ "$(pvl_pin slot)" = set ] && [ "$(pvl_pin row)" = 0 ] && [ "$(pvl_pin total)" = 0 ] || { pv_log "pin.rec changed: $(pvl_show_status)"; return 1; }
}

# The lockout trap, with the system's PIN screen on the prompt now.
trap_part() {
    pv_shell input text "$PV_DEVICE_PIN" >/dev/null
    pv_shell input keyevent KEYCODE_ENTER >/dev/null
    pv_sleep 3
    credential_screen && { pv_log "the system's PIN screen is still there"; return 1; }
    pvl_prompt_shown || { pv_log "no prompt after the phone's PIN (focus $(pv_focus))"; return 1; }
    pv_sleep 1
    pvl_finger "$PVL_FINGER" || return 1
    pvl_prompt_down 15 || return 1
    pvl_wait_screen pad 15 || return 1
    pvl_text_is pv.lock.status string lock_fingerprint_did_not_open_pin || return 1
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: pad") ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
    nothing_removed
}

prompt_launch() { pvl_item prompt: | sed -n 's/^prompt: [A-Z_]*, launch \([0-9]*\).*/\1/p'; }

# The phone locked itself after five wrong fingers: unlocked again, the app's new visit. Its prompt
# opens by itself once (lock.md §9.2 rule 7) and must stay open: the old prompt's code 10, which
# arrives only now, belongs to a launch that the lock ended, and is dropped (rule 8). Taken for the new
# prompt's, it would close it (code 10 is CLOSED); a loop would launch again and again. The sensor may
# also refuse the new prompt at once with code 7 (lockout): then "Fingerprint is locked for now."
phone_lock_part() {
    local before after later line
    pvl_status || return 1
    before=$(prompt_launch)
    pv_unlock || return 1
    pv_sleep 2
    # The app in front, or the prompt of its new visit over it.
    pva_in_front || pvl_prompt_shown || { pv_log "the app is not in front after the phone was unlocked: $(pv_focus)"; return 1; }
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: pad") ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
    pv_sleep 6
    pvl_status || return 1
    after=$(prompt_launch)
    pvl_note "E8 on return: the prompt before the phone locked: launch ${before:-none}; now: $(pvl_item prompt:); $(pvl_item visible:)"
    if [ -n "$after" ]; then
        pvl_prompt_shown || { pv_log "the status names a prompt that is not on the screen"; return 1; }
        [ -z "$before" ] || [ "$after" -le $((before + 1)) ] || { pv_log "launches went from $before to $after: a loop"; return 1; }
        pv_sleep 4
        pvl_status || return 1
        later=$(prompt_launch)
        [ "$later" = "$after" ] || { pv_log "the prompt changed: launch $after, then '${later:-none}'"; return 1; }
        pvl_prompt_shown || { pv_log "the new visit's prompt closed by itself"; return 1; }
    else
        line=$(pvl_status_line)
        pvl_expect "$line" string lock_fingerprint_locked_pin \
            || { pv_log "no prompt is open on the new visit and the pad does not say the sensor is locked: a stale code 10 may have closed it"; return 1; }
    fi
    nothing_removed
}

# wrong_fingers: one unknown finger at a time, each part handled as it comes.
wrong_fingers() {
    local touches=0
    while [ $TRAP_SEEN = 0 ] || [ $PHONE_LOCK_SEEN = 0 ]; do
        [ $touches -lt 30 ] || { pv_log "after $touches wrong fingers: trap seen $TRAP_SEEN, phone lock seen $PHONE_LOCK_SEEN"; return 1; }
        if ! pvl_prompt_shown; then
            if pv_keyguard_showing; then :; elif pva_in_front; then
                # No prompt (closed after a part): open one with the pad's fingerprint key.
                pvl_pad 10 || return 1
                pva_tap pv.pin.fingerprint || return 1
                pvl_prompt_up 15 || return 1
            fi
        fi
        if ! pv_keyguard_showing; then
            pv_sleep 1.5
            pvl_finger "$PVL_UNKNOWN_FINGER" || return 1
            touches=$((touches + 1))
            pv_sleep 2.5
        fi
        if pv_keyguard_showing; then
            pvl_note "E8: the phone locked itself after $touches wrong fingers in all"
            if [ $PHONE_LOCK_SEEN = 0 ]; then
                pv_step "five wrong fingers lock the phone: on return the pad, the late callback dropped, no prompt loop, nothing removed" -- phone_lock_part
                PHONE_LOCK_SEEN=1
            else
                pv_unlock || return 1
                pv_sleep 2
            fi
        elif credential_screen; then
            pvl_note "E8: the prompt asks for the phone's PIN after $touches wrong fingers in all"
            if [ $TRAP_SEEN = 0 ]; then
                pv_step "the lockout trap: the phone's PIN, then a finger: \"did not open the app\", nothing removed" -- trap_part
                TRAP_SEEN=1
            else
                pv_shell input keyevent KEYCODE_BACK >/dev/null
                pv_sleep 2
            fi
        fi
    done
    return 0
}

cold_boot() { pv_reboot 400; }

fingerprint_key_works() {
    pva_start || return 1
    pvl_wait_screen 'pad|prompt' 60 || return 1
    if pvl_prompt_up 10; then
        pv_sleep 1
        pvl_prompt_negative || return 1
        pvl_prompt_down 10 || return 1
    fi
    pvl_pad 15 || return 1
    pva_tap pv.pin.fingerprint || return 1
    pvl_touch_prompt "$PVL_FINGER" 15 || return 1
    pvl_prompt_down 15 || return 1
    pvl_open_list 30
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN and fingerprint unlock (finger 1)" -- set_up || pvl_end
pv_step "Lock now: the prompt opens by itself" -- lock_with_prompt || pvl_end
wrong_fingers || pv_report FAIL "wrong fingers until the trap and the phone lock were both seen"
pv_step "a cold boot (adb reboot) ends the sensor's lockout" -- cold_boot || pvl_end
pv_step "after the boot the pad's fingerprint key opens a working prompt: finger 1 opens the app" -- fingerprint_key_works
pvl_end
