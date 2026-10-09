#!/usr/bin/env bash
# tools/emu/lock/E3.sh - lock.md §15.3 check E3 (Android 7, 9, 17): `adb reboot` with a charge held: the
# try is counted after the reboot (lock.md §6.3 S, §6.7 "Reboot: as a kill").
#
#   tools/emu/lock/E3.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. The reboot is
#       tools/emu/adb.sh's pv_reboot (adb reboot, then the new boot, the device PIN, the launcher); a
#       read-only device keeps the app and its files across a reboot of the guest.
#
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - Lock now; `hold-after-charge`; the first digit: the charge is on disk, the entry held;
#   - the phone rebooted while it is held;
#   - the app started: "Your last PIN entry was interrupted and counted as a try. 4 tries left.";
#     pin.rec: charged false, row 1, total 1, unseen 1; the PIN key and the slot are still there
#     (a reboot changes no key, lock.md §8.2 "After a reboot: No");
#   - the PIN opens the app, and the notice says that one entry was interrupted;
#   - the same with `hold-before-hmac` and all six digits of the right PIN: counted after the reboot
#     (row 1, total 2).
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E3 "$@"

set_up() { pvl_set_up "$PVL_PIN" - ; }

relock() { pvl_lock_now && pvl_pad 20; }

hold_reached() {
    local deadline=$((SECONDS + 15))
    while :; do
        pvl_status && [ "$(pvl_item hold:)" = "hold: $1 (reached)" ] && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "not held at $1: $(pvl_item hold:)"; return 1; }
        pv_sleep 0.5
    done
}

# held POINT DIGITS: the hold armed, DIGITS typed, the entry held at POINT.
held() {
    local out
    out=$(pva_debug "hold-$1") || return 1
    case "$out" in "hold-$1: armed"*) ;; *) pv_log "answer: $out"; return 1 ;; esac
    pva_pin "$2" || return 1
    hold_reached "$3"
}

reboot() {
    local t0=$SECONDS
    pv_reboot 400 || return 1
    pvl_note "E3 on API $API: the reboot took $((SECONDS - t0)) s"
}

counted_after_reboot() {
    local row=$1 total=$2 left
    pva_start || return 1
    pva_on Locked 60 || return 1
    pvl_pad 30 || return 1
    left=$((5 - row))
    [ $((10 - total)) -lt $left ] && left=$((10 - total))
    pva_need pv.lock.status 10 || return 1
    pvl_text_is pv.lock.status plural lock_pad_interrupted "$left" || return 1
    pvl_expect_pin charged=false row="$row" total="$total" unseen=1 slot=set || return 1
    [ "$(pvl_alias_count pin)" = 1 ] || { pv_log "the PIN key is not there: $(pvl_item keystore:)"; return 1; }
}

opens_with_notice() {
    pva_pin "$PVL_PIN" || return 1
    pva_unlocked Passwords 30 || return 1
    pvl_notice_has string lock_notice_interrupted || return 1
    pvl_answer OK || return 1
    pva_settle Passwords || return 1
    pvl_expect_pin unseen=0 row=0 total="$1" charged=false
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "Lock now: the pad" -- relock || pvl_end
pv_step "hold-after-charge, the first digit: the charge is on disk, the entry held" -- held after-charge "${PVL_PIN:0:1}" after-charge
pv_step "adb reboot while it is held" -- reboot || pvl_end
pv_step "after the reboot: one try counted, \"interrupted\" shown, the key kept" -- counted_after_reboot 1 1
pv_step "the PIN opens; the notice says one entry was interrupted" -- opens_with_notice 1
pv_step "Lock now: the pad" -- relock || pvl_end
pv_step "hold-before-hmac, the right PIN: the check held before the Keystore" -- held before-hmac "$PVL_PIN" before-hmac
pv_step "adb reboot while it is held" -- reboot || pvl_end
pv_step "after the reboot: the right PIN cost one try, \"interrupted\" shown" -- counted_after_reboot 1 2
pv_step "the PIN opens; the notice says one entry was interrupted" -- opens_with_notice 2
pvl_end
