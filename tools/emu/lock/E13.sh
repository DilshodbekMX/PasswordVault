#!/usr/bin/env bash
# tools/emu/lock/E13.sh - lock.md §15.3 check E13 (Android 17; the second part Android 9): "Device
# locked". `hold-before-hmac`, `input keyevent KEYCODE_SLEEP`, `release`: on Android 17, where the PIN
# key needs an unlocked phone, the Keystore says "Device locked": TEMPORARY, refunded, nothing removed
# and nothing opened; after the phone is unlocked the PIN works. On Android 9, where the PIN key has no
# such requirement, the same steps with the right PIN: the PIN counts as right, and the app is still
# locked when the screen comes back on (I11).
#
#   tools/emu/lock/E13.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. API 35 and newer
#       expect the first part (the unlocked-device requirement of lock.md §4.2), older levels the
#       second.
#
# One wrong PIN comes first (row 1, total 1), so that the two outcomes differ in `status`: a refund
# leaves row 1 and total 1; a right PIN gives row 0 (total 0 or 1: the visit that held the wrong PIN
# ended with the screen-off); a wrong one would give row 2.
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - Lock now; a wrong PIN: "Wrong PIN. 4 tries left."; `hold-before-hmac`; the right PIN: held
#     before the Keystore;
#   - the screen off (KEYCODE_SLEEP): the phone locks; `release`;
#   - API 35 and newer: pin.rec charged false, row 1, total 1 (refunded, not counted), its slot and its
#     key still there, the app still locked; the Keystore's "Device locked" is in the log (noted);
#     API 28 to 34: row 0 (the right PIN counted as right; total is noted), the slot and key there, the
#     app still locked (the check's visit ended with the screen-off: nothing opens);
#   - the screen on and the phone unlocked: the app's lock screen, not the list;
#   - the PIN opens the app.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E13 "$@"

set_up() { pvl_set_up "$PVL_PIN" - ; }

one_wrong_then_held() {
    pvl_lock_now && pvl_pad 20 || return 1
    pva_pin "${PVL_WRONG[0]}" && pvl_wait_answer || return 1
    pvl_text_is pv.lock.status plural lock_pad_wrong_pin 4 || return 1
    pvl_expect_pin row=1 total=1 || return 1
    local out deadline=$((SECONDS + 15))
    out=$(pva_debug hold-before-hmac) || return 1
    case "$out" in "hold-before-hmac: armed"*) ;; *) pv_log "answer: $out"; return 1 ;; esac
    pva_pin "$PVL_PIN" || return 1
    while :; do
        pvl_status && [ "$(pvl_item hold:)" = "hold: before-hmac (reached)" ] && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "not held: $(pvl_item hold:)"; return 1; }
        pv_sleep 0.5
    done
}

sleep_and_release() {
    local out
    pvl_log_mark
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep 2
    out=$(pva_debug release) || return 1
    case "$out" in "release: released before-hmac (reached)") ;; *) pv_log "answer: $out"; return 1 ;; esac
    pv_sleep 3
}

# The check's outcome, read with the screen still off.
outcome() {
    local dl
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: "*) ;; *) pv_log "the app is '$(pvl_lock_state)' with the screen off"; return 1 ;; esac
    [ "$(pvl_alias_count pin)" = 1 ] || { pv_log "the PIN key is gone: $(pvl_item keystore:)"; return 1; }
    dl=$(pvl_log_since keystore2:V KeystoreOperation:V 2>/dev/null | grep -c -i 'device locked\|DEVICE_LOCKED\|KEY_USER_NOT_AUTHENTICATED')
    if [ "$API" -ge 35 ]; then
        pvl_note "E13 on API $API: the Keystore's log has $dl lines about a locked device since the screen-off"
        pvl_expect_pin charged=false row=1 total=1 slot=set
    else
        # Whether the wrong PIN before it is forgiven depends on whether the screen-off cleared the
        # visit's list before the check read it; lock.md says only "the counts are as for any right
        # PIN" (§6.3 E.6). Noted, not judged.
        pvl_note "E13 on API $API: after the right PIN that came too late: $(pvl_vault_item pin.rec)"
        pvl_expect_pin charged=false row=0 slot=set
    fi
}

screen_on_still_locked() {
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_sleep 1
    pv_unlock || return 1
    pv_sleep 1
    pva_in_front || pva_relaunch || return 1
    pvl_wait_screen 'pad|master-password' 20 || return 1
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: "*) ;; *) pv_log "the app is '$(pvl_lock_state)'"; return 1 ;; esac
}

pin_works() { pvl_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30 && pvl_expect_pin row=0 charged=false; }

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "Lock now; one wrong PIN; hold-before-hmac; the right PIN held before the Keystore" -- one_wrong_then_held || pvl_end
pv_step "the screen off (the phone locks), then release" -- sleep_and_release || pvl_end
if [ "$API" -ge 35 ]; then
    pv_step "\"Device locked\": TEMPORARY, refunded, nothing removed, nothing opened" -- outcome
else
    pv_step "no unlocked-device requirement on API $API: the right PIN counted as right, nothing opened" -- outcome
fi
pv_step "the screen on, the phone unlocked: the app is still locked" -- screen_on_still_locked || pvl_end
pv_step "the PIN opens the app" -- pin_works
pvl_end
