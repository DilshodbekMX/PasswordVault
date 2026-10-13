#!/usr/bin/env bash
# tools/emu/lock/E9.sh - lock.md §15.3 check E9 (Android 17): "Use fingerprint to verify it's you in
# apps" switched off (`settings put secure fingerptint_app_enabled 0`, the setting's name has this
# typo [L-bio finding d]): the fingerprint key is hidden, no prompt opens, and the pad shows the line
# for canAuthenticate 1; switched on again: fingerprint works with the same key.
#
#   tools/emu/lock/E9.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh and runs API 37 (the setting is the Pixel Settings' of the
#       API 37 image); on another API level the script says so and stops. The APK defaults to the
#       debug build. The setting is put back as it was when the script ends.
#
# Steps, after the app is installed afresh and set up with the PIN and fingerprint unlock (finger 1):
#   - the setting off; Lock now: the pad, no prompt opens (watched for 5 s), no fingerprint key on
#     the pad, and the status line "Fingerprint can't be used in apps right now. Use your PIN. …";
#     `status`: canAuthenticate 1, fingerprint.rec keeps its slot, the Keystore its pv_fp_ key
#     (lock.md §9.3: the key is not invalidated, nothing is probed);
#   - the PIN opens the app;
#   - the setting on again; Lock now: the prompt opens by itself and finger 1 opens the app; the
#     pv_fp_ key is the same one as before.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E9 "$@"
if [ "$API" != 37 ]; then
    echo "E9: lock.md lists E9 for Android 17 (API 37); $SERIAL has API $API" >&2
    trap - EXIT
    exit 2
fi

SETTING=fingerptint_app_enabled
FP_ALIAS=""

set_up() {
    pvl_set_up "$PVL_PIN" fingerprint || return 1
    pvl_expect_fp slot=set || return 1
    FP_ALIAS=$(pvl_item keystore: | tr ' ' '\n' | grep '^pv_fp_')
    [ -n "$FP_ALIAS" ] || { pv_log "no pv_fp_ key"; return 1; }
}

switch_off() { pvl_setting_set secure "$SETTING" 0; }

switch_on() { pv_shell settings put secure "$SETTING" 1 >/dev/null; }

hidden_with_its_line() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_wait_screen pad 20 || return 1
    local i
    for i in 1 2 3 4 5; do
        pv_sleep 1
        ! pvl_prompt_shown || { pv_log "a prompt opened"; return 1; }
    done
    pvl_pad_lacks pv.pin.fingerprint || return 1
    pvl_text_is pv.lock.status string lock_fingerprint_not_for_apps_pin || return 1
    pvl_status || return 1
    case "$(pvl_item canAuthenticate:)" in "canAuthenticate: 1,"*) ;; *) pv_log "$(pvl_item canAuthenticate:)"; return 1 ;; esac
    [ "$(pvl_fp slot)" = set ] || { pv_log "fingerprint.rec lost its slot: $(pvl_show_status)"; return 1; }
    pvl_item keystore: | tr ' ' '\n' | grep -qx "$FP_ALIAS" || { pv_log "the key $FP_ALIAS is gone: $(pvl_item keystore:)"; return 1; }
    [ "$(pvl_item prompt:)" = "prompt: none, may open by itself: false" ] || [ "$(pvl_item prompt: | cut -d, -f1)" = "prompt: none" ] \
        || { pv_log "$(pvl_item prompt:)"; return 1; }
}

pin_opens() { pva_pin "$PVL_PIN" && pvl_open_list 30; }

works_again() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_touch_prompt "$PVL_FINGER" 20 || return 1
    pvl_prompt_down 15 || return 1
    pvl_open_list 30 || return 1
    pvl_status || return 1
    pvl_item keystore: | tr ' ' '\n' | grep -qx "$FP_ALIAS" || { pv_log "not the same key: $(pvl_item keystore:), was $FP_ALIAS"; return 1; }
    [ "$(pvl_alias_count fp)" = 1 ] || { pv_log "$(pvl_item keystore:)"; return 1; }
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN and fingerprint unlock (finger 1)" -- set_up || pvl_end
pv_step "\"verify it's you in apps\" switched off ($SETTING 0)" -- switch_off || pvl_end
pv_step "Lock now: no prompt, no fingerprint key, the line for canAuthenticate 1; slot and key kept" -- hidden_with_its_line
pv_step "the PIN opens the app" -- pin_opens || pvl_end
pv_step "switched on again ($SETTING 1)" -- switch_on
pv_step "Lock now: the prompt opens by itself, finger 1 opens the app, with the same key" -- works_again
pvl_end
