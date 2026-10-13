#!/usr/bin/env bash
# tools/emu/lock/E5.sh - lock.md §15.3 check E5 (Android 17): `adb shell settings put global boot_count
# 99999`, three times, bringing the app forward each time: the 24-hour wait gains only the real minutes
# (lock.md §6.9: BOOT_COUNT is not consulted; it can be rewritten through adb without a reboot
# [J-S §2.1]).
#
#   tools/emu/lock/E5.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (lock.md lists Android 17). The APK defaults to the debug
#       build: the credit is read from state.rec with run-as (SKIPPED on staging). boot_count is put
#       back when the script ends.
#
# The whole check waits for build plan 5.5 (the screens that ask for a new master password, F11), and
# prints SKIPPED until it is merged (pvl_later). Steps then, after the app is installed afresh and set
# up with the PIN:
#   - a new master password asked for from Settings (Change master password › "I don't remember it",
#     the PIN, the suggested words): state.rec has pending_mp; its credited_ms is read;
#   - three times: boot_count set to 99999 (then 99999 again, as lock.md says), Home, 20 s, the app
#     brought forward with the launcher's intent (process-level ON_START observes the credit, §6.9):
#     credited_ms grew by no more than the real time since the credit read first was observed (its
#     seen_elapsed_ms against the phone's /proc/uptime), plus 5 s;
#   - the lock screen's master-password field still says "starts working in about 24 hours" (or 23).
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$LOCK_DIR/newmp.sh"
pvl_begin E5 "$@"

C0=""
SEEN0=""

requested() {
    pvn_request || return 1
    C0=$(pvn_credited_ms); SEEN0=$(pvn_seen_ms)
    [ -n "$C0" ] && [ -n "$SEEN0" ] || { pv_log "no pending_mp in state.rec"; return 1; }
    pvl_note "E5: credited_ms $C0, observed at elapsedRealtime $SEEN0, right after the request"
}

# The credit may grow by the real time since it was observed (seen_elapsed_ms), never more, whatever
# boot_count says.
boot_count_edits() {
    local i out
    pvl_setting_set global boot_count 99999 || return 1
    for i in 1 2 3; do
        pv_shell settings put global boot_count 99999 >/dev/null || return 1
        pva_away 20 || return 1
        pvl_wait_screen 'list|pad|master-password' 20 || return 1
        out=$(pvn_credit_bound "$C0" "$SEEN0")
        local rc=$?
        pvl_note "E5: after boot_count edit $i: $out"
        [ $rc = 0 ] || { pv_log "the wait gained more than the real time"; return 1; }
    done
}

hours_unchanged() {
    local line
    pvl_status || return 1
    case "$(pvl_lock_state)" in unlocked) pvl_lock_now || return 1 ;; esac
    pvl_to_master_password || return 1
    line=$(pva_text pv.lock.pending) || return 1
    pvl_expect "$line" plural lock_mp_pending_hours 24 2>/dev/null || pvl_expect "$line" plural lock_mp_pending_hours 23
}

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- pvl_set_up "$PVL_PIN" - || pvl_end
if ! pv_is_debuggable; then
    for item in "a new master password asked for; its credit read" "boot_count 99999 three times: the wait gains only the real minutes" "the lock screen still says about 24 hours"; do
        pvl_skip "$item" "needs-debuggable: the credit is read from state.rec with run-as"
    done
    pvl_end
fi
pvl_later 5.5 "a new master password asked for (F11); its credit read" -- requested
if pvl_has_strings master_; then
    pv_step "boot_count 99999 three times, the app brought forward each time: the wait gains only the real minutes" -- boot_count_edits
    pv_step "the lock screen still says the new master password starts working in about 24 hours" -- hours_unchanged
else
    pvl_later 5.5 "boot_count 99999 three times, the app brought forward each time: the wait gains only the real minutes" -- false
    pvl_later 5.5 "the lock screen still says the new master password starts working in about 24 hours" -- false
fi
pvl_end
