#!/usr/bin/env bash
# tools/emu/lock/E14.sh - lock.md §15.3 check E14 (Android 17): storage full. /data filled until the
# charge cannot be written: the "storage is full" text, nothing counted; the master password still
# opens (lock.md §6.3 C.3, §13.3).
#
#   tools/emu/lock/E14.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build. The filling runs as
#       the shell user, under /data/local/tmp, and is removed again when the script ends (also when it
#       fails). It is made for API 37 (lock.md lists Android 17); on another level it runs the same way.
#
# Steps, after the app is installed afresh and set up with the PIN (fingerprint "Not now"):
#   - Lock now: the pad;
#   - /data filled: `fallocate` of all but a few MiB of what the shell may use, then the rest with `dd`
#     in 4 KiB blocks until the file system refuses (the app, another ordinary uid, gets no more than
#     the shell);
#   - the first digit: the charge cannot be written: the dots are empty again, the status line says
#     "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your
#     master password.", and `status` shows pin.rec uncharged with row 0 and total 0;
#   - "Forgot PIN? Use master password" and the master password, with /data still full: the list;
#   - the space freed; Lock now: the PIN opens the app;
#   - the same refusal through the debug command `fail-next-write` (the next record write throws with
#     nothing written), which needs no filling: the first digit gives the same line and counts nothing;
#     the PIN then opens the app.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E14 "$@"

FILL=/data/local/tmp/pvl-e14-fill
pvl_undo "rm -f $FILL $FILL.rest"

set_up() { pvl_set_up "$PVL_PIN" - ; }

to_pad() { pvl_lock_now && pvl_pad 20; }

available_kib() { pv_shell df /data 2>/dev/null | tr -d '\r' | awk 'NR == 2 { print $4 }'; }

fill() {
    local avail out
    avail=$(available_kib)
    [ -n "$avail" ] || { pv_log "df /data says nothing"; return 1; }
    pvl_note "E14: /data has $avail KiB available before the filling"
    if [ "$avail" -gt 8192 ]; then
        out=$(pv_shell fallocate -l "$((avail - 4096))K" "$FILL" 2>&1 | tr -d '\r')
        [ -z "$out" ] || pv_log "fallocate: $out"
    fi
    # The rest in small blocks, until the file system refuses (a few minutes at most).
    PV_ADB_TIMEOUT=600 pv_shell "dd if=/dev/zero of=$FILL.rest bs=4096 2>/dev/null; true" >/dev/null 2>&1
    avail=$(available_kib)
    pvl_note "E14: /data has $avail KiB available after the filling"
    [ "${avail:-1}" -le 64 ] 2>/dev/null || { pv_log "/data still has $avail KiB available"; return 1; }
}

charge_refused() {
    local line deadline=$((SECONDS + 20))
    pva_pin "${PVL_PIN:0:1}" || return 1
    pv_sleep 2
    while :; do
        line=$(pvl_status_line)
        [ -n "$line" ] && break
        [ $SECONDS -lt $deadline ] || { pv_log "no status line; ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
    pvl_expect "$line" string lock_pad_storage_full || return 1
    pvl_expect "$(pva_node pv.pin.dots | cut -d'|' -f2)" plural common_pin_dots 0 || return 1
    pvl_expect_pin charged=false row=0 total=0 slot=set
}

mp_opens_while_full() {
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_submit_open pv.lock.unlock Passwords 120 || return 1
    pvl_note "E14: /data has $(available_kib) KiB available while the list is shown"
}

free_space() {
    pv_shell rm -f "$FILL" "$FILL.rest" >/dev/null 2>&1
    pv_sleep 2
    pvl_note "E14: /data has $(available_kib) KiB available after the files were removed"
}

pin_opens() { to_pad && pva_pin "$PVL_PIN" && pvl_open_list 30 && pvl_expect_pin charged=false row=0 total=0; }

fail_next_write() {
    local out
    to_pad || return 1
    out=$(pva_debug fail-next-write) || return 1
    case "$out" in "fail-next-write: armed"*) ;; *) pv_log "answer: $out"; return 1 ;; esac
    charge_refused || return 1
    pvl_status || return 1
    [ "$(pvl_item fail-next-write:)" = "fail-next-write: no" ] || { pv_log "$(pvl_item fail-next-write:)"; return 1; }
}

pin_after() { pvl_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30 && pvl_expect_pin charged=false row=0 total=0; }

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "Lock now: the pad" -- to_pad || pvl_end
pv_step "/data filled until the file system refuses" -- fill || { free_space; pvl_end; }
pv_step "the first digit: the charge cannot be written: \"storage is full\", nothing counted" -- charge_refused
pv_step "the master password opens the app while /data is full" -- mp_opens_while_full
pv_step "the space freed" -- free_space
pv_step "Lock now: the PIN opens the app" -- pin_opens
pv_step "fail-next-write, the first digit: the same line, nothing counted" -- fail_next_write
pv_step "the PIN then opens the app" -- pin_after
pvl_end
