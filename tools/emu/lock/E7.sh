#!/usr/bin/env bash
# tools/emu/lock/E7.sh - lock.md §15.3 check E7: the phone's settings against the keys.
#   (a) Fingers (Android 17). Another finger enrolled: at the next visit fingerprint is GONE with its
#       text, and the PIN still works. Fingerprint turned on again and every finger deleted: the pad
#       says that no fingerprint is saved, nothing is probed and the slot stays; a finger enrolled
#       again: GONE with its text.
#   (b) The screen lock (Android 7, 9, 17). Removed (`locksettings clear --old 1111`; through Settings
#       on Android 7): K_pin and the canary still load and the PIN opens the app [J-R D3-8]; the pad
#       shows the line for canAuthenticate 11. A screen lock and a finger set again: fingerprint is
#       GONE through §14.2. lock.md: "Part (b) was never tried on the Android 7 and 9 images: record
#       what happens there": there the script prints what it saw as NOTE lines, and only the PIN's part
#       passes or fails.
#
#   tools/emu/lock/E7.sh SERIAL [--apk FILE] [--only a|b]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. Part (a) runs on API 37
#       only (the Android 7 and 9 images never change their sensor's id, so a new finger invalidates no
#       key there: lock.md §15.3, "What the emulators cannot show"); part (b) on every level. Default:
#       both that apply.
#
# The phone's settings are changed through its own Settings screens and locksettings
# (tools/emu/lock/phone.sh, adapted from docs/lab/biometric-artifacts/enroll_fingerprint.sh and
# delete_all_fingerprints.sh). The app is left (Home) while they change, and its next visit is the
# launcher's intent. At the end the phone is put back as the base has it: the screen lock 1111 and two
# enrolled fingers (emulator fingers 1 and 2), so that later checks on the same device find what they
# expect.
#
# Steps, after the app is installed afresh and set up with the PIN and fingerprint unlock (finger 1):
#   (a) finger 3 enrolled: the next visit opens no prompt, the pad says "Fingerprint unlock was turned
#       off because this phone's fingerprints or screen lock changed. …", fingerprint.rec has no slot
#       (off key_gone) and its key is gone; the PIN opens the app;
#       fingerprint turned on again in Settings; every finger deleted: the next visit says "No
#       fingerprint is saved on this phone now. Use your PIN. …", shows no fingerprint key and opens no
#       prompt, `status` says canAuthenticate 11, and fingerprint.rec keeps its slot and the Keystore its
#       key (not probed); the PIN opens the app;
#       finger 1 enrolled again: the next visit finds the key invalidated: GONE with its text; the PIN
#       opens the app;
#   (b) fingerprint on; the screen lock removed: the next visit's pad says that no fingerprint is saved
#       (canAuthenticate 11), pv_pin_ and pv_canary are still listed, and the PIN opens the app; the
#       screen lock and finger 1 set again: the next visit finds the fingerprint key GONE (§14.2: null,
#       the canary loads) with its text; the PIN opens the app.
# Prints PASS, FAIL or SKIPPED per step, NOTE lines for what is recorded, and a summary; exit 0 when no
# step failed, 1 otherwise, 2 for a usage error, 3 for a refused serial.
ONLY=a,b
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --only) [ $# -ge 2 ] || { echo "E7: --only needs a or b" >&2; exit 2; }; ONLY=$2; shift ;;
        *) ARGS+=("$1") ;;
    esac
    shift
done
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$LOCK_DIR/phone.sh"
pvl_begin E7 "${ARGS[@]}"

want() { case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# The phone is put back as the base has it, whatever happened.
pvl_undo_restore() { pvp_restore_base >/dev/null 2>&1; }
trap 'pvl_undo_restore; pvl_cleanup' EXIT

set_up() { pvl_set_up "$PVL_PIN" fingerprint && pvl_expect_fp slot=set; }

# Locked, then away: Home. The phone's settings change while the app is not visible.
lock_and_leave() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pv_sleep 2
    pv_shell input keyevent KEYCODE_HOME >/dev/null
    pv_sleep 2
}

# The app's next visit, through the launcher's intent: the lock screen (a prompt may open over it).
next_visit() {
    pv_unlock >/dev/null 2>&1
    pva_relaunch || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30
}

# gone_with_text: the visit found the fingerprint key GONE.
gone_with_text() {
    pv_sleep 2
    pvl_no_prompt || { pvl_prompt_shown && pvl_prompt_negative; return 1; }
    pvl_wait_screen pad 15 || return 1
    pvl_text_is pv.lock.status string lock_fingerprint_turned_off_pin || return 1
    pvl_expect_fp slot=none off=key_gone || return 1
    [ "$(pvl_alias_count fp)" = 0 ] || { pv_log "the fingerprint's alias is still there: $(pvl_item keystore:)"; return 1; }
}

pin_opens() { pvl_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30; }

fp_turn_on() {
    pva_on Passwords 20 || return 1
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.settings.fingerprint || return 1
    pva_need pv.pin.mp.field 15 || return 1
    pva_type pv.pin.mp.field "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.settings.fingerprint.turnOn || return 1
    pvl_touch_prompt "$PVL_FINGER" 30 || return 1
    pvl_prompt_down 15 || return 1
    pv_sleep 2
    pvl_expect_fp slot=set || return 1
    pva_tap pv.tab.Passwords && pva_on Passwords 20
}

# The pad after fingerprint became unusable on the phone (canAuthenticate 11): its line, no key, no
# prompt; the slot and the key are left alone.
none_saved_on_pad() {
    local i
    pvl_wait_screen pad 20 || return 1
    for i in 1 2 3; do
        pv_sleep 1
        pvl_no_prompt || return 1
    done
    pvl_pad_lacks pv.pin.fingerprint || return 1
    pvl_text_is pv.lock.status string lock_fingerprint_none_saved_pin || return 1
    pvl_status || return 1
    case "$(pvl_item canAuthenticate:)" in "canAuthenticate: 11,"*) ;; *) pv_log "$(pvl_item canAuthenticate:)"; return 1 ;; esac
    [ "$(pvl_fp slot)" = set ] || { pv_log "the slot was removed: $(pvl_show_status)"; return 1; }
    [ "$(pvl_alias_count fp)" = 1 ] || { pv_log "the fingerprint's alias is gone although nothing was probed: $(pvl_item keystore:)"; return 1; }
}

# ---- part (a): fingers, API 37 ------------------------------------------------------------------------
another_finger() { lock_and_leave && pvp_enroll 3 && pvl_note "E7 (a): fingerprints enrolled now: $(pvp_fingers)"; }

delete_all() { lock_and_leave && pvp_delete_all_fingers; }

enrol_again() { lock_and_leave && pvp_enroll "$PVL_FINGER"; }

part_a() {
    pv_step "(a) another finger (3) enrolled in Settings" -- another_finger || return 1
    pv_step "(a) the next visit: fingerprint GONE with its text" -- eval 'next_visit && gone_with_text'
    pv_step "(a) the PIN still works" -- pin_opens || return 1
    pv_step "(a) fingerprint unlock turned on again" -- fp_turn_on || return 1
    pv_step "(a) every finger deleted in Settings" -- delete_all || return 1
    pv_step "(a) the next visit: \"No fingerprint is saved\", no key, no prompt, slot and key left alone" -- eval 'next_visit && none_saved_on_pad'
    pv_step "(a) the PIN opens the app" -- pin_opens || return 1
    pv_step "(a) a finger (1) enrolled again" -- enrol_again || return 1
    pv_step "(a) the next visit: fingerprint GONE with its text" -- eval 'next_visit && gone_with_text'
    pv_step "(a) the PIN opens the app" -- pin_opens
}

# ---- part (b): the screen lock -----------------------------------------------------------------------
ensure_fp_on() {
    pvl_status || return 1
    [ "$(pvl_fp slot)" = set ] && return 0
    fp_turn_on
}

clear_lock() {
    lock_and_leave || return 1
    pvp_lock_clear || return 1
    pvl_note "E7 (b) on API $API: the screen lock removed; fingerprints enrolled now: $(pvp_fingers)"
}

# After the screen lock is gone: what the lock shows and has, recorded; the PIN's part asserted.
after_clear() {
    local line can keys
    next_visit || return 1
    pv_sleep 3
    if pvl_prompt_shown; then
        pvl_note "E7 (b) on API $API: a fingerprint prompt opened without a screen lock"
        pvl_prompt_negative
        pv_sleep 1
    fi
    pvl_wait_screen pad 15 || return 1
    pvl_status || return 1
    line=$(pvl_status_line)
    can=$(pvl_item canAuthenticate:)
    keys=$(pvl_item keystore:)
    pvl_note "E7 (b) on API $API without a screen lock: $can; the pad's line: '${line:-none}'; $keys; fingerprint.rec: $(pvl_vault_item fingerprint.rec); fingerprint key on the pad: $(pva_absent pv.pin.fingerprint && echo no || echo yes)"
    printf '%s\n' "$keys" | tr ' ' '\n' | grep -q '^pv_pin_' || { pv_log "K_pin is gone"; return 1; }
    printf '%s\n' "$keys" | tr ' ' '\n' | grep -qx pv_canary || { pv_log "the canary is gone"; return 1; }
    if [ "$API" = 37 ]; then
        case "$can" in "canAuthenticate: 11,"*) ;; *) pv_log "$can"; return 1 ;; esac
        pvl_expect "$line" string lock_fingerprint_none_saved_pin || return 1
    fi
}

set_lock_and_finger() {
    lock_and_leave || return 1
    pvp_lock_set || return 1
    [ "$(pvp_fingers)" -ge 1 ] 2>/dev/null || pvp_enroll "$PVL_FINGER" || return 1
    pvl_note "E7 (b) on API $API: the screen lock set again; fingerprints enrolled now: $(pvp_fingers)"
}

after_set() {
    local line
    next_visit || return 1
    pv_sleep 3
    if [ "$API" = 37 ]; then
        gone_with_text
        return
    fi
    # Android 7 and 9: recorded.
    if pvl_prompt_shown; then
        pvl_note "E7 (b) on API $API: after the screen lock and a finger were set again, a prompt opened; finger 1 touched"
        pvl_finger "$PVL_FINGER"
        pv_sleep 3
    fi
    pvl_status || return 1
    line=$(pvl_status_line 2>/dev/null)
    pvl_note "E7 (b) on API $API after the screen lock and a finger were set again: state '$(pvl_lock_state)'; the pad's line: '${line:-none}'; $(pvl_item canAuthenticate:); fingerprint.rec: $(pvl_vault_item fingerprint.rec); $(pvl_item keystore:)"
    case "$(pvl_lock_state)" in unlocked) pvl_open_list 30 && pva_tap pv.list.lockNow && pvl_pad 20 ;; esac
    # An alias that a removed slot left behind goes at the next process start (the sweep, lock.md §5.7).
    if [ "$(pvl_fp slot)" = none ] && [ "$(pvl_alias_count fp)" != 0 ]; then
        pvl_force_stop && pvl_restart >/dev/null 2>&1
        pvl_status
        pvl_note "E7 (b) on API $API: the removed fingerprint slot's alias was still listed; after a new process start the Keystore lists: $(pvl_item keystore:)"
        pvl_pad 20 >/dev/null 2>&1 || pvl_to_pad 20 >/dev/null 2>&1
    fi
    return 0
}

part_b() {
    pv_step "(b) fingerprint unlock on" -- ensure_fp_on || return 1
    if [ "$API" -ge 26 ]; then
        pv_step "(b) the screen lock removed (locksettings clear --old $PV_DEVICE_PIN)" -- clear_lock || return 1
    else
        pv_step "(b) the screen lock removed (through Settings)" -- clear_lock || return 1
    fi
    if [ "$API" = 37 ]; then
        pv_step "(b) the next visit: K_pin and the canary still there; the line for canAuthenticate 11" -- after_clear
    else
        pv_step "(b) the next visit: K_pin and the canary still there (the rest recorded)" -- after_clear
    fi
    pv_step "(b) the PIN opens the app without a screen lock" -- pin_opens || return 1
    pv_step "(b) a screen lock and a finger set again" -- set_lock_and_finger || return 1
    if [ "$API" = 37 ]; then
        pv_step "(b) the next visit: fingerprint GONE through §14.2, with its text" -- after_set
    else
        pv_step "(b) the next visit after the screen lock came back (recorded)" -- after_set
    fi
    pv_step "(b) the PIN opens the app" -- pin_opens
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "the phone as the base has it: a screen lock and enrolled fingers" -- pvp_restore_base || pvl_end
pv_step "set up with the PIN and fingerprint unlock (finger 1)" -- set_up || pvl_end
if want a; then
    if [ "$API" = 37 ]; then part_a; else pvl_not_here "(a) fingers" "lock.md lists (a) for Android 17: the Android 7 and 9 images never change their sensor's id"; fi
fi
want b && part_b
pv_step "the phone put back as the base has it: the screen lock 1111, fingers 1 and 2" -- pvp_restore_base
pvl_end
