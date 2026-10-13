#!/usr/bin/env bash
# tools/emu/lock/E2.sh - lock.md §15.3 check E2 (Android 7, 9, 17): a kill during a PIN entry costs one
# try. With `hold-after-charge`, `am force-stop` after the first digit; with `hold-before-hmac`,
# `kill -9` after the sixth: after a restart one try is counted and "interrupted" is shown (lock.md
# §6.3 S, §6.10).
#
#   tools/emu/lock/E2.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. kill -9 goes through
#       run-as (debug) or su (the userdebug images, API 24 and 28); on staging on API 37 neither
#       exists and it is `am force-stop` (build plan §12, "4.5").
#
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - Lock now; `hold-after-charge`; the first digit: the charge is on disk and the entry is held
#     (`status`: "hold: after-charge (reached)", pin.rec charged true); `am force-stop`;
#   - the app started again: the pad says "Your last PIN entry was interrupted and counted as a try.
#     4 tries left."; pin.rec: charged false, row 1, total 1, unseen 1;
#   - the PIN opens the app; the notice reports the wrong try and "One entry was interrupted and
#     counted as a try."; after its OK unseen is 0; row 0 and total 1 (an interrupted try is never
#     forgiven, lock.md §6.3 E.6);
#   - Lock now; `hold-before-hmac`; all six digits of the right PIN: the check is held before the
#     Keystore is asked ("hold: before-hmac (reached)"); kill -9;
#   - the app started again: "Your last PIN entry was interrupted … 4 tries left." (row 1, total 2);
#     the right PIN was never checked, and it cost a try all the same;
#   - the PIN opens the app; the notice says the entry was interrupted.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E2 "$@"

set_up() { pvl_set_up "$PVL_PIN" - ; }

relock() { pvl_lock_now && pvl_pad 20; }

# hold_reached POINT: `status` says the entry is held at POINT.
hold_reached() {
    local deadline=$((SECONDS + 15))
    while :; do
        pvl_status && [ "$(pvl_item hold:)" = "hold: $1 (reached)" ] && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "not held at $1: $(pvl_item hold:) / $(pvl_items | head -n 1)"; return 1; }
        pv_sleep 0.5
    done
}

hold_after_first_digit() {
    local out
    out=$(pva_debug hold-after-charge) || return 1
    case "$out" in "hold-after-charge: armed"*) ;; *) pv_log "answer: $out"; return 1 ;; esac
    pva_pin "${PVL_PIN:0:1}" || return 1
    hold_reached after-charge
}

hold_after_sixth_digit() {
    local out
    out=$(pva_debug hold-before-hmac) || return 1
    case "$out" in "hold-before-hmac: armed"*) ;; *) pv_log "answer: $out"; return 1 ;; esac
    pva_pin "$PVL_PIN" || return 1
    hold_reached before-hmac
}

force_stop() { pvl_force_stop; }

kill9() {
    local how
    how=$(pvl_kill9) || return 1
    pvl_note "E2 on API $API ($VARIANT): the held check ended with $how"
}

# restarted_counted ROW TOTAL: the app again, the pad, the interrupted line, and the counts.
restarted_counted() {
    local row=$1 total=$2 left
    pvl_restart || return 1
    pvl_pad 30 || return 1
    left=$((5 - row))
    [ $((10 - total)) -lt $left ] && left=$((10 - total))
    pva_need pv.lock.status 10 || return 1
    pvl_text_is pv.lock.status plural lock_pad_interrupted "$left" || return 1
    pvl_expect_pin charged=false row="$row" total="$total" slot=set
}

# opens_with_notice TOTAL: the PIN opens the app; the notice says an entry was interrupted; after OK
# nothing is unseen, row is 0, total TOTAL.
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
pv_step "hold-after-charge, the first digit: the charge is on disk, the entry held" -- hold_after_first_digit
pv_step "am force-stop while it is held" -- force_stop
pv_step "started again: one try counted, \"interrupted\" shown, 4 tries left" -- restarted_counted 1 1
pv_step "the PIN opens; the notice says one entry was interrupted" -- opens_with_notice 1
pv_step "Lock now: the pad" -- relock || pvl_end
pv_step "hold-before-hmac, the right PIN: the check held before the Keystore" -- hold_after_sixth_digit
pv_step "kill -9 while it is held" -- kill9
pv_step "started again: the right PIN cost one try, \"interrupted\" shown" -- restarted_counted 1 2
pv_step "the PIN opens; the notice says one entry was interrupted" -- opens_with_notice 2
pvl_end
