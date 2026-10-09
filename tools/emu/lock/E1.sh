#!/usr/bin/env bash
# tools/emu/lock/E1.sh - lock.md §15.3 check E1 (Android 7, 9, 17): set up, unlock with the PIN through
# a real Keystore HMAC key; five wrong PINs: the slot is gone and its alias is gone (`status`), and the
# master password still opens. The time from the sixth digit to the list is logged.
#
#   tools/emu/lock/E1.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh. The APK defaults to this worktree's
#       app/build/outputs/apk/debug/app-debug.apk; the staging APK (R8, not debuggable) runs the same
#       steps (lock.md §15.3 "Two builds").
#
# Steps, after the app is installed afresh:
#   - setup with the suggested words and the PIN 582913, fingerprint "Not now": the list;
#   - the PIN's slot and its Keystore key exist (pin.rec has a slot, the Keystore lists pv_pin_<id>
#     and pv_canary), and the lock screen shows "TEST BUILD: PIN keys are not protected" (a build
#     that allows keys in software, architecture.md §7.2);
#   - Lock now, the PIN (one key every 0.4 s, so the charge is a second old at the sixth digit): the
#     list; the app's own time from the sixth digit to the unlocked state (`status`, "last PIN
#     unlock") is logged, with the host's time from the sixth tap to the list in a fresh dump;
#   - the PIN typed fast (all six taps in one shell command): the list; both times logged again
#     (acceptance A1 measures on real phones, check D1; here they are only written down);
#   - Lock now, then five wrong PINs, each with its line (lock.md §13.3): "Wrong PIN. 4 tries left.",
#     3, 2, "Wrong PIN. 1 try left. After that you'll need your master password.", and at the fifth the
#     pad is gone: the master-password screen with "Your PIN was entered wrong too many times, so it
#     was turned off. …"; `status` shows pin.rec without a slot, off "limit", row 5, total 5, and no
#     pv_pin_ alias in the Keystore (lock.md §6.5: the key deleted at once);
#   - the master password opens the list ("Choose a new PIN" and the notice are answered "Not now"
#     and "OK"); the counts are back to 0 (lock.md §6.4) and the PIN stays off.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E1 "$@"

set_up() { pvl_set_up "$PVL_PIN" - ; }

key_exists() {
    pvl_status || return 1
    [ "$(pvl_pin slot)" = set ] || { pv_log "pin.rec has no slot: $(pvl_show_status)"; return 1; }
    [ "$(pvl_alias_count pin)" = 1 ] || { pv_log "the Keystore does not list one pv_pin_ alias: $(pvl_item keystore:)"; return 1; }
    pvl_has_canary || { pv_log "no pv_canary: $(pvl_item keystore:)"; return 1; }
}

lock_shows_pad() {
    pvl_lock_now && pvl_pad 20 || return 1
    pva_present pv.lock.testBuild 5 || { pv_log "no TEST BUILD line on the pad"; return 1; }
}

# timed_unlock fast|slow: the PIN, then the list; prints both times.
timed_unlock() {
    local nodes line n key=() t0 t1 app host cmd=""
    pvl_pad || return 1
    nodes=$(pv_ui_nodes)
    for n in 0 1 2 3 4 5 6 7 8 9; do
        line=$(printf '%s\n' "$nodes" | pv_ui_match "[|]pv\.pin\.key\.$n\$")
        [ -n "$line" ] || { pv_log "no key $n"; return 1; }
        key[$n]="$(printf '%s\n' "$line" | cut -d'|' -f6) $(printf '%s\n' "$line" | cut -d'|' -f7)"
    done
    if [ "$1" = fast ]; then
        for ((n = 0; n < 6; n++)); do cmd="$cmd input tap ${key[${PVL_PIN:$n:1}]};"; done
        pv_shell "$cmd" >/dev/null
        t0=$(date +%s%N)
    else
        for ((n = 0; n < 6; n++)); do
            pv_shell input tap ${key[${PVL_PIN:$n:1}]} >/dev/null
            [ "$n" = 5 ] && t0=$(date +%s%N)
            [ "$n" = 5 ] || pv_sleep 0.4
        done
    fi
    pva_unlocked Passwords 30 || return 1
    t1=$(date +%s%N)
    pva_settle Passwords || return 1
    pvl_status || return 1
    app=$(pvl_item "last PIN unlock:" | sed 's/^last PIN unlock: //')
    host=$(((t1 - t0) / 1000000))
    pvl_note "E1 $1 entry on API $API ($VARIANT): the app's time from the sixth digit to the unlocked state: $app; the host's time from the sixth tap to a dump showing the list: $host ms"
    case "$app" in *" ms from the sixth digit") ;; *) pv_log "no time in the status: '$app'"; return 1 ;; esac
}

relock() { pvl_lock_now && pvl_pad 20; }

# wrong N EXPECT...: the N-th wrong PIN (from 1), then the status line is the text EXPECT.
wrong() {
    local i=$1
    shift
    pva_pin "${PVL_WRONG[$((i - 1))]}" || return 1
    pvl_wait_answer || return 1
    pvl_text_is pv.lock.status "$@" || return 1
    pvl_expect_pin row="$i" total="$i" charged=false slot=set
}

fifth_wrong_turns_off() {
    pva_pin "${PVL_WRONG[4]}" || return 1
    pvl_wait_screen master-password 30 || return 1
    pvl_text_is pv.lock.reason string lock_mp_reason_pin_limit || return 1
    pvl_expect_pin slot=none off=limit row=5 total=5 charged=false || return 1
    [ "$(pvl_policy pin)" = LIMIT ] || { pv_log "policy: $(pvl_item policy:)"; return 1; }
    [ "$(pvl_alias_count pin)" = 0 ] || { pv_log "a pv_pin_ alias is still there: $(pvl_item keystore:)"; return 1; }
    pvl_has_canary || { pv_log "pv_canary is gone: $(pvl_item keystore:)"; return 1; }
}

master_password_opens() {
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_submit_open pv.lock.unlock Passwords 90 || return 1
    pvl_expect_pin slot=none row=0 total=0 charged=false
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "the PIN's slot and its Keystore key exist, with pv_canary" -- key_exists
pv_step "Lock now: the pad, with the TEST BUILD line" -- lock_shows_pad || pvl_end
pv_step "the PIN opens the list (time logged)" -- timed_unlock slow || pvl_end
pv_step "Lock now, the PIN typed fast opens the list (time logged)" -- eval 'relock && timed_unlock fast' || pvl_end
pv_step "Lock now: the pad" -- relock || pvl_end
pv_step "wrong PIN 1: \"Wrong PIN. 4 tries left.\", counted" -- wrong 1 plural lock_pad_wrong_pin 4
pv_step "wrong PIN 2: \"Wrong PIN. 3 tries left.\", counted" -- wrong 2 plural lock_pad_wrong_pin 3
pv_step "wrong PIN 3: \"Wrong PIN. 2 tries left.\", counted" -- wrong 3 plural lock_pad_wrong_pin 2
pv_step "wrong PIN 4: \"Wrong PIN. 1 try left. After that you'll need your master password.\"" -- wrong 4 string lock_pad_last_try
pv_step "wrong PIN 5: the PIN turns off: slot gone, alias gone, the master-password screen says why" -- fifth_wrong_turns_off
pv_step "the master password still opens; the counts are 0 and the PIN stays off" -- master_password_opens
pvl_end
