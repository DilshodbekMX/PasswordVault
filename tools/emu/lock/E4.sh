#!/usr/bin/env bash
# tools/emu/lock/E4.sh - lock.md §15.3 check E4 (Android 17): the clock. Automatic time off, `adb
# shell cmd alarm set-time` a year ahead and back: the tries left are unchanged; a pending 24-hour wait
# is unchanged; the "replaced" notice is still shown; practice is due after the clock went back.
#
#   tools/emu/lock/E4.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 37: `cmd alarm set-time` exists from API 28; lock.md
#       lists Android 17). The APK defaults to the debug build. The phone's automatic time is put back
#       and the clock set back to the host's time when the script ends.
#
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - automatic time off; Lock now; three wrong PINs (row 3, total 3); a new visit: "2 tries left
#     before the PIN turns off.";
#   - the clock a year ahead; a new visit: `status` still has row 3 and total 3 (no limit uses the
#     date, lock.md §6.7), and practice is past its grace: the master-password screen with the
#     practice text and no "Not now" (lock.md §8.3);
#   - the clock back to now; a new visit: the pad again with "2 tries left before the PIN turns off.",
#     row 3 and total 3;
#   - the clock a year ahead again; the master password (practice done then, a year from now); the
#     clock back to now; a new visit: practice is due at once, because the clock is more than an hour
#     before the last right master password (§8.3 "Clock"): the practice text with "Not now: use PIN",
#     which leads to the pad, where the PIN opens;
#   - waits for build plan 5.5 (pvl_later), debug builds: a new master password asked for (F11): its
#     wait's credit (pending_mp.credited_ms, read with run-as) is unchanged by the clock a year ahead
#     and back, beyond the real seconds that passed, and the lock screen still says "starts working in
#     about 24 hours" (or 23);
#   - waits for build plan 5.5, debug builds: the wait made ready (credited_ms set to 24 hours in
#     state.rec, a test shortcut: an emulator cannot wait 24 credited hours), "Finish now", the new
#     master password: the replacement made; the next unlock shows "Your master password was replaced
#     on … using the PIN."; the clock a year ahead: the next unlock still shows it (7 days are credited
#     by elapsedRealtime, never by the date, lock.md §6.9).
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$LOCK_DIR/newmp.sh"
pvl_begin E4 "$@"
if [ "$API" -lt 28 ]; then
    echo "E4: cmd alarm set-time needs API 28; $SERIAL has API $API (lock.md lists E4 for Android 17)" >&2
    trap - EXIT
    exit 2
fi

YEAR_MS=$((366 * 24 * 3600 * 1000))

# The phone's clock: set to the host's time plus OFFSET ms (the emulator's clock runs with the host's).
set_clock() {
    local target=$(( $(date +%s%3N) + $1 )) out
    out=$(pv_shell cmd alarm set-time "$target" 2>&1 | tr -d '\r')
    pv_sleep 2
    local now
    now=$(pv_shell date +%s 2>/dev/null | tr -d '\r')
    [ -n "$now" ] && [ $(( now - target / 1000 )) -lt 30 ] && [ $(( target / 1000 - now )) -lt 30 ] \
        || { pv_log "the phone's clock is $now, wanted $((target / 1000)) ($out)"; return 1; }
}

clock_back_at_end() {
    pv_shell cmd alarm set-time "$(date +%s%3N)" >/dev/null 2>&1
}

automatic_time_off() { pvl_setting_set global auto_time 0; }

set_up() { pvl_set_up "$PVL_PIN" - ; }

new_visit() { pva_screen_off 2 && pvl_wait_screen 'pad|master-password|fingerprint-only' 20; }

three_wrong() {
    pvl_lock_now && pvl_pad 20 || return 1
    pvl_wrong_pins 3 || return 1
    pvl_expect_pin row=3 total=3 || return 1
    new_visit && pvl_wait_screen pad 10 || return 1
    pvl_text_is pv.lock.status plural lock_pad_tries_left 2
}

# not_now_shown: "Not now: use PIN" is on the master-password screen, below the field and the keyboard
# perhaps: the keyboard closed and the screen scrolled to it.
not_now_shown() { pva_hide_keyboard; pva_reveal pv.lock.notNow 2>/dev/null; }

year_ahead_counts_kept() {
    set_clock "$YEAR_MS" || return 1
    new_visit || return 1
    pvl_expect_pin row=3 total=3 charged=false slot=set || return 1
    [ "$(pvl_policy row)" = PRACTICE_REQUIRED ] || { pv_log "$(pvl_item policy:)"; return 1; }
    pvl_wait_screen master-password 10 || return 1
    pvl_text_is pv.lock.reason string lock_mp_reason_practice || return 1
    ! not_now_shown || { pv_log "\"Not now\" is offered although practice is past its grace"; return 1; }
}

back_counts_kept() {
    set_clock 0 || return 1
    new_visit && pvl_wait_screen pad 10 || return 1
    pvl_text_is pv.lock.status plural lock_pad_tries_left 2 || return 1
    pvl_expect_pin row=3 total=3 charged=false slot=set
}

practice_done_a_year_ahead() {
    set_clock "$YEAR_MS" || return 1
    new_visit || return 1
    pvl_wait_screen master-password 10 || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_submit_open pv.lock.unlock Passwords 90 || return 1
    pvl_expect_pin row=0 total=0 || return 1
    pvl_lock_now
}

practice_due_after_back() {
    set_clock 0 || return 1
    new_visit || return 1
    pvl_status || return 1
    [ "$(pvl_policy row)" = PRACTICE_IN_GRACE ] || { pv_log "$(pvl_item policy:); $(pvl_vault_item state.rec)"; return 1; }
    pvl_wait_screen master-password 10 || return 1
    pvl_text_is pv.lock.reason string lock_mp_reason_practice || return 1
    not_now_shown || { pv_log "no \"Not now: use PIN\""; return 1; }
    pva_tap pv.lock.notNow && pvl_pad 15 && pva_pin "$PVL_PIN" && pvl_open_list 30
}

# ---- waits for 5.5 ---------------------------------------------------------------------------------
pending_unchanged() {
    local c0 c1 c2 t0 t1 t2 line
    pv_is_debuggable || { pv_log "needs run-as (a debug build) to read the credit"; return 1; }
    pvn_request || return 1
    c0=$(pvn_credited_ms); t0=$(date +%s%3N)
    [ -n "$c0" ] || { pv_log "no pending_mp in state.rec"; return 1; }
    set_clock "$YEAR_MS" || return 1
    pvl_lock_now && pvl_wait_screen 'pad|master-password' 20 || return 1
    pva_away 3 && pvl_wait_screen 'pad|master-password' 20 || return 1
    c1=$(pvn_credited_ms); t1=$(date +%s%3N)
    set_clock 0 || return 1
    pva_away 3 && pvl_wait_screen 'pad|master-password' 20 || return 1
    c2=$(pvn_credited_ms); t2=$(date +%s%3N)
    pvl_note "E4: credited_ms $c0 -> $c1 (a year ahead, $((t1 - t0)) ms later) -> $c2 (back, $((t2 - t0)) ms after the first)"
    [ $((c1 - c0)) -le $((t1 - t0 + 5000)) ] && [ $((c2 - c0)) -le $((t2 - t0 + 5000)) ] \
        || { pv_log "the credit grew by more than the time that passed"; return 1; }
    pvl_to_master_password || return 1
    line=$(pva_text pv.lock.pending) || return 1
    pvl_expect "$line" plural lock_mp_pending_hours 24 2>/dev/null || pvl_expect "$line" plural lock_mp_pending_hours 23 \
        || return 1
    pvl_to_pad 15 && pva_pin "$PVL_PIN" && pvl_open_list 30
}

replaced_notice_kept() {
    pv_is_debuggable || { pv_log "needs run-as (a debug build) to make the wait ready"; return 1; }
    pvl_status || return 1
    [ "$(pvl_state pending)" = yes ] || pvn_request || return 1
    pvn_make_ready || return 1
    pva_start && pvl_wait_screen 'pad|master-password' 30 || return 1
    pvl_pad 10 && pva_pin "$PVL_PIN" || return 1
    pva_unlocked Passwords 30 || return 1
    pva_need pv.answer.FINISH_NOW 20 && pva_tap pv.answer.FINISH_NOW || return 1
    pvn_finish || return 1
    # The next unlock: the notice.
    pvl_lock_now && pvl_pad 20 && pva_pin "$PVL_PIN" || return 1
    pva_unlocked Passwords 30 || return 1
    pvl_notice_has string lock_notice_replaced_pin || return 1
    pvl_answer OK && pva_settle Passwords || return 1
    # A year ahead: still shown.
    set_clock "$YEAR_MS" || return 1
    pvl_lock_now && pvl_wait_screen 'pad|master-password' 20 || return 1
    pvl_unlock_mp 2>/dev/null || { pvl_pad 10 && pva_pin "$PVL_PIN" && pva_unlocked Passwords 30; } || return 1
    pvl_notice_has string lock_notice_replaced_pin || return 1
    pvl_answer OK && pva_settle Passwords
}

trap 'clock_back_at_end; pvl_cleanup' EXIT
pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "automatic time off" -- automatic_time_off || pvl_end
pv_step "three wrong PINs; a new visit: \"2 tries left before the PIN turns off.\"" -- three_wrong || pvl_end
pv_step "the clock a year ahead: row 3 and total 3 kept; practice past its grace, no \"Not now\"" -- year_ahead_counts_kept
pv_step "the clock back: the pad, \"2 tries left\", row 3 and total 3 kept" -- back_counts_kept
pv_step "a year ahead again, the master password typed (practice done a year from now)" -- practice_done_a_year_ahead
pv_step "the clock back: practice due at once, with \"Not now: use PIN\"; the PIN opens" -- practice_due_after_back
pvl_later 5.5 "a pending 24-hour wait: its credit and its hours unchanged by a year ahead and back" needs-debuggable -- pending_unchanged
pvl_later 5.5 "the \"replaced\" notice: shown, and still shown with the clock a year ahead" needs-debuggable -- replaced_notice_kept
pvl_end
