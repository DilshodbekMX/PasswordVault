# tools/emu/lock/newmp.sh - the steps of a new master password (lock.md F11) that E4 and E5 need, a
# library sourced after lib.sh. They drive the screens of build plan 5.5 (Route.NewMasterPassword,
# its MasterTags pv.master.*, setup's choice steps pv.setup.* and the PIN pad pv.pin.key.N), which
# are built in the same round as this package: the ids below are 5.5's as its branch had them on
# 2026-10-09 (MasterTags pv.master.*, and setup's own CreateMasterPassword, WriteDown and TypeAgain
# screens, which 5.5 reuses for choosing: pv.setup.passphrase, pv.open.WriteDown, pv.open.TypeAgain,
# pv.setup.again, pv.action.createVault), and every step that uses them runs through pvl_later 5.5, so
# it prints SKIPPED until 5.5 is merged. None of them could be run here. Should 5.5 have renamed one,
# the step fails and says which id it did not find.
#
#   pvn_request             from the list: Settings › Change master password › "I don't remember it",
#                           the explanation's "Continue", the PIN, a new master password chosen with
#                           the suggested words (PVN_NEW_PASSWORD), written down, typed again: the wait;
#                           back on the list. state.rec has a pending password.
#   pvn_pending_json        (debug builds) the pending_mp object of the current vault's state.rec
#   pvn_credited_ms         (debug builds) its credited_ms
#   pvn_make_ready          (debug builds) credited_ms set to 24 hours in state.rec, the app stopped
#                           first: a test shortcut for the 24 credited hours an emulator cannot wait
#   pvn_finish              "Finish now" on the ready notice or banner, the new master password typed
#                           on 5.5's finishing step: the replacement made (lock.md F11 steps 14 to 18)
PVN_NEW_PASSWORD=""

pvn_request() {
    local deadline
    pva_on Passwords 20 || return 1
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.open.ChangeMasterPassword || return 1
    pva_need pv.open.NewMasterPassword 20 && pva_tap pv.open.NewMasterPassword || return 1
    pva_need pv.master.explain 20 && pva_tap pv.master.continue || return 1
    # The PIN typed now (F11 step 3: an app that is already open is not enough).
    pva_need pv.pin.key.0 20 && pva_pin "$PVL_PIN" || return 1
    pva_reveal pv.setup.passphrase 2>/dev/null || pva_need pv.setup.passphrase 30 || return 1
    PVN_NEW_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$PVN_NEW_PASSWORD" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' || { pv_log "not six words: '$PVN_NEW_PASSWORD'"; return 1; }
    pva_tap pv.open.WriteDown && pva_need pv.open.TypeAgain 20 && pva_tap pv.open.TypeAgain || return 1
    pva_type pv.setup.again "$PVN_NEW_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.action.createVault || return 1
    deadline=$((SECONDS + 60))
    until pva_present pv.master.done 2 || pva_present pv.master.wait 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "no wait screen after the request; ids: $(pva_ids)"; return 1; }
    done
    pva_present pv.master.done 5 && pva_tap pv.master.done
    # The route closes onto what opened it (Change master password, Settings): back to the list.
    local i
    for i in 1 2 3 4 5; do
        pva_present pv.route.Passwords 2 && break
        if pva_present pv.tab.Passwords 1; then pva_tap pv.tab.Passwords; else pv_shell input keyevent KEYCODE_BACK >/dev/null; fi
        pv_sleep 1.5
    done
    pva_settle Passwords 60 || return 1
    pvl_status || return 1
    [ "$(pvl_state pending)" = yes ] || { pv_log "no pending password: $(pvl_vault_item state.rec)"; return 1; }
}

pvn_pending_json() {
    pvl_status || return 1
    pvl_record "$(pvl_vault_id)" state.rec | python3 -c '
import json, sys
print(json.dumps(json.load(sys.stdin).get("pending_mp")))'
}

pvn_credited_ms() { pvn_pending_json | python3 -c 'import json, sys; p = json.load(sys.stdin); print(p["credited_ms"] if p else "")'; }

# pvn_seen_ms: the pending wait's seen_elapsed_ms, the phone's elapsedRealtime() when the credit was
# last observed (lock.md §6.9).
pvn_seen_ms() { pvn_pending_json | python3 -c 'import json, sys; p = json.load(sys.stdin); print(p["seen_elapsed_ms"] if p else "")'; }

# pvn_uptime_ms: the phone's time since boot in milliseconds (/proc/uptime: CLOCK_BOOTTIME, the clock of
# elapsedRealtime()).
pvn_uptime_ms() { pv_shell cat /proc/uptime 2>/dev/null | tr -d '\r' | awk '{ printf "%d\n", $1 * 1000 }'; }

# pvn_credit_bound C0 SEEN0: true when the credit now is at most C0 plus the real time since SEEN0 (the
# phone's elapsedRealtime when C0 was credited) and 5 s; prints the numbers.
pvn_credit_bound() {
    local c now
    c=$(pvn_credited_ms); now=$(pvn_uptime_ms)
    [ -n "$c" ] && [ -n "$now" ] || { pv_log "no credit or no uptime: '$c' '$now'"; return 1; }
    printf 'credited_ms %s, %s ms more than %s; real time since that credit was observed: %s ms\n' "$c" "$((c - $1))" "$1" "$((now - $2))"
    [ $((c - $1)) -le $((now - $2 + 5000)) ]
}

pvn_make_ready() {
    local id json
    pvl_status || return 1
    id=$(pvl_vault_id)
    pvl_force_stop || return 1
    json=$(pvl_record "$id" state.rec) || return 1
    json=$(printf '%s' "$json" | python3 -c '
import json, sys
r = json.load(sys.stdin)
r["pending_mp"]["credited_ms"] = 24 * 3600 * 1000 + 60000
print(json.dumps(r, separators=(",", ":")))') || return 1
    pvl_record_put "$id" state.rec "$json"
}

pvn_finish() {
    local deadline
    pva_need pv.master.finish.field 30 || return 1
    pva_type pv.master.finish.field "$PVN_NEW_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.master.finish || return 1
    deadline=$((SECONDS + 120))
    until pva_present pv.route.CreatePin 2 || pva_present pv.notice 2 || pva_present pv.master.later 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "the finishing did not end; ids: $(pva_ids)"; return 1; }
    done
    # F11 step 19: where the phone can hold keys, "Choose a PIN" for the new vault opens over 5.5's
    # screen: the PIN, the fingerprint offer "Not now", and the steps close onto that screen without
    # setup's "Make backups" (PinFlow.AFTER_NEW_MASTER_PASSWORD, merge of R4). Then 5.5's backup step,
    # answered "Later", which closes the route onto the list the notice was answered on.
    # (The backup step may show for a moment before the lock's notice opens the PIN steps over it.)
    if pva_present pv.route.CreatePin 5 || pva_present pv.notice 1; then
        pvl_pin_steps "$PVL_PIN" - NewMasterPassword || return 1
    fi
    pva_need pv.master.later 20 && pva_tap pv.master.later || return 1
    pva_on Passwords 30 || return 1
    PVA_PASSWORD=$PVN_NEW_PASSWORD
}
