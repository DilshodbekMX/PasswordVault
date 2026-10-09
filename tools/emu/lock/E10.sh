#!/usr/bin/env bash
# tools/emu/lock/E10.sh - lock.md §15.3 check E10 (Android 7, 9, 17): `ks-fault` for every row of
# §14.3 through the real adapter, each giving the listed class; TEMPORARY faults remove nothing and
# store nothing, however often they come. `drop-keys`: at the next visit each shortcut is GONE with the
# "stopped working" text, not "restart your phone" (§14.2). `adb shell pm uninstall -k`, then the APK
# installed again: whether the keys survived is noted, and the master password opens the vault either
# way (architecture.md D-21).
#
#   tools/emu/lock/E10.sh SERIAL [--apk FILE] [--only PART[,PART...]]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build (the faults exist in
#       every build that carries the debug commands: debug, staging, backupCheck). PART is one of
#       pin, fp, prompt, create, drop, reinstall (default: all, in that order; about 25 minutes).
#
# The rows are the names `ks-fault` lists (KeystoreFaults.rows of build plan 3.9, and
# prompt-error-<code>); the script reads the list from the app and fails on a row it does not know, so
# a row added later is not silently left out. Each row is armed with `ks-fault ROW` just before the
# Keystore call it belongs to, and `status` must say "ks-fault: none" afterwards (the fault struck).
# The classes (lock.md §14.1) are read from `status` and from the screen:
#   GONE       the slot is removed (off "key_gone" or "bad_wrap"), its alias deleted at once, and the
#              screen says it stopped working: for the PIN "Your PIN stopped working on this phone. …",
#              for fingerprint "Fingerprint unlock was turned off because … You can turn it on again
#              in Settings."; never the TEMPORARY text "Restarting the phone usually fixes this";
#   TEMPORARY  nothing is removed or stored (the record and the alias are as before, the counts
#              unchanged: a PIN check is refunded), and the shortcut is not offered again on this
#              visit: the PIN with "Your PIN can't be checked right now. … Restarting the phone usually
#              fixes this. …", fingerprint with its line of §13.3; three times in a row for every such
#              row; the next visit offers it again;
#   CLOSED     the prompt's codes 10, 13 and 5: the pad, nothing said, nothing changed;
#   NOT NOW    making a key fails: the half-made alias is gone, the record unchanged, and the reason
#              is said ("Add a fingerprint in your phone's settings first.", "could not be turned on",
#              "can't keep a fingerprint key in protected hardware").
# The parts:
#   pin        the PIN key (no fingerprint): keystore-load, the getkey-* rows (getkey-null-once only
#              passes a null by, and the PIN opens), the init-* rows and mac-dofinal, at the sixth digit;
#   fp         the fingerprint key's probe before the prompt (F3 step 2), at the start of a visit:
#              keystore-load, getkey-*, init-*;
#   prompt     the prompt: prompt-no-cipher, fp-dofinal-bad-tag, fp-dofinal-wrong-size,
#              fp-dofinal-other (with finger 1), and prompt-error-10, -13, -5 (CLOSED), -7 and -9
#              (locked), -1 (not for apps), -11 and -12 (can't be checked, the key hidden);
#   create     making a fingerprint key in Settings › Fingerprint unlock: create-none-enrolled,
#              create-other, create-software; then `delete` while turning it off: the alias stays,
#              and the sweep at the next process start deletes it (lock.md §5.7);
#   drop       `drop-keys` with PIN and fingerprint on: the fingerprint is GONE at the next visit's
#              probe (the canary made again first, §14.2), the PIN at its check; the master password
#              opens;
#   reinstall  PIN and fingerprint on, `pm uninstall -k`, `adb install`: the keys that survived are
#              noted; each shortcut that lost its key says it stopped working, and the master password
#              opens the vault either way.
# A GONE shortcut is set up again before the next row (the master password, then "Choose a new PIN",
# or Settings › Fingerprint unlock).
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
ONLY=pin,fp,prompt,create,drop,reinstall
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --only) [ $# -ge 2 ] || { echo "E10: --only needs a list" >&2; exit 2; }; ONLY=$2; shift ;;
        *) ARGS+=("$1") ;;
    esac
    shift
done
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E10 "${ARGS[@]}"

want() { case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

FP_ON=0

# ---- the rows ----------------------------------------------------------------------------------------
# The class of each row on each path (lock.md §14.3; KeystoreFaults.Row's KDoc).
PIN_ROWS="keystore-load:TEMPORARY getkey-null:GONE getkey-null-once:OK getkey-nothing-answers:TEMPORARY getkey-unrecoverable:GONE getkey-unrecoverable-cause:TEMPORARY getkey-other:TEMPORARY init-invalidated:GONE init-not-authenticated:TEMPORARY init-invalid-key:TEMPORARY init-other:TEMPORARY mac-dofinal:TEMPORARY"
FP_ROWS="keystore-load:TEMPORARY getkey-null:GONE getkey-null-once:OK getkey-nothing-answers:TEMPORARY getkey-unrecoverable:GONE getkey-unrecoverable-cause:TEMPORARY getkey-other:TEMPORARY init-invalidated:GONE init-not-authenticated:TEMPORARY init-invalid-key:TEMPORARY init-other:TEMPORARY"
# prompt rows: ROW:CLASS:LINE (the pad's status line resource, "-" for none)
PROMPT_ROWS="prompt-no-cipher:TEMPORARY:lock_fingerprint_did_not_open_pin fp-dofinal-bad-tag:GONE:lock_fingerprint_turned_off_pin fp-dofinal-wrong-size:GONE:lock_fingerprint_turned_off_pin fp-dofinal-other:TEMPORARY:lock_fingerprint_did_not_open_pin prompt-error-10:CLOSED:- prompt-error-13:CLOSED:- prompt-error-5:CLOSED:- prompt-error-7:TEMPORARY:lock_fingerprint_locked_pin prompt-error-9:TEMPORARY:lock_fingerprint_locked_pin prompt-error-1:TEMPORARY:lock_fingerprint_not_for_apps_pin prompt-error-11:TEMPORARY:lock_fingerprint_cannot_check_pin prompt-error-12:TEMPORARY:lock_fingerprint_cannot_check_pin"
CREATE_ROWS="create-none-enrolled:pin_fingerprint_add_first create-other:settings_fingerprint_failed,pin_fingerprint_failed create-software:pin_fingerprint_no_secure_hardware,settings_no_secure_hardware"
OTHER_ROWS="delete"

# Every row `ks-fault` names is one of the rows above: a row added to the app must be added here.
rows_known() {
    local answer rows row known
    answer=$(pva_debug ks-fault) || return 1
    rows=$(printf '%s\n' "$answer" | sed -n 's/.*; rows: //p')
    [ -n "$rows" ] || { pv_log "ks-fault lists no rows: $answer"; return 1; }
    known=" $(for r in $PIN_ROWS $FP_ROWS $PROMPT_ROWS $CREATE_ROWS; do printf '%s ' "${r%%:*}"; done) $OTHER_ROWS prompt-error-<code> none "
    for row in $rows; do
        case "$known" in *" $row "*) ;; *) pv_log "ks-fault has a row this check does not test: $row"; return 1 ;; esac
    done
    pvl_note "E10: ks-fault rows: $rows"
}

arm() {
    local out
    out=$(pva_debug "ks-fault $1") || return 1
    [ "$out" = "ks-fault: $1" ] || { pv_log "ks-fault $1 answered '$out'"; return 1; }
}

# spent: the fault armed last has struck ("ks-fault: none"); otherwise it is disarmed and the row
# fails: the call it belongs to did not meet it.
spent() {
    pvl_status || return 1
    [ "$(pvl_item ks-fault:)" = "ks-fault: none" ] && return 0
    pv_log "the fault did not strike: $(pvl_item ks-fault:)"
    pva_debug "ks-fault none" >/dev/null
    return 1
}

# The current vault's pin.rec and the PIN's alias, and the same for fingerprint, as one line.
snapshot() {
    pvl_status || return 1
    printf '%s | %s | %s\n' "$(pvl_vault_item pin.rec)" "$(pvl_vault_item fingerprint.rec)" \
        "$(pvl_item keystore: | tr ' ' '\n' | grep -E '^pv_(pin|fp)_' | sort | tr '\n' ' ')"
}

# unchanged BEFORE: nothing of the records or the aliases changed (a TEMPORARY fault stores nothing).
unchanged() {
    local now
    now=$(snapshot) || return 1
    [ "$now" = "$1" ] || { pv_log "changed: before '$1', now '$now'"; return 1; }
}

# ---- getting into the app and out ----------------------------------------------------------------
pin_unlock() { pvl_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30; }

lock_to_pad() { pvl_lock_now && pvl_pad 20; }

# A new visit of the lock screen: the screen off and on again.
new_visit() { pva_screen_off 2 && pva_on Locked 20; }

# After a GONE PIN: the master password, then "Choose a new PIN" (the PIN chosen again), and the list.
mp_and_new_pin() {
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    pvl_pin_steps "$PVL_PIN" - || return 1
    pvl_expect_pin slot=set row=0 total=0
}

# Settings › Fingerprint unlock, off -> on: the master password in its dialog, "Turn on", the prompt,
# finger 1. Back on the list.
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

# Settings › Fingerprint unlock, on -> off. Back on the list.
fp_turn_off() {
    pva_on Passwords 20 || return 1
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.settings.fingerprint || return 1
    pv_sleep 2
    pvl_expect_fp slot=none off=turned_off || return 1
    pva_tap pv.tab.Passwords && pva_on Passwords 20
}

# ---- part pin ------------------------------------------------------------------------------------
# pin_row ROW CLASS: on the pad of a fresh visit.
pin_row() {
    local row=$1 class=$2 before i
    before=$(snapshot) || return 1
    case "$class" in
        OK)
            arm "$row" && pva_pin "$PVL_PIN" || return 1
            pvl_open_list 30 || return 1
            spent || return 1
            pvl_expect_pin slot=set row=0 total=0 charged=false || return 1
            lock_to_pad ;;
        TEMPORARY)
            for i in 1 2 3; do
                arm "$row" && pva_pin "$PVL_PIN" || return 1
                pvl_wait_screen master-password 30 || return 1
                pvl_text_is pv.lock.reason string lock_mp_reason_pin_cannot_check || return 1
                pva_absent pv.lock.usePin || { pv_log "the PIN is offered again on this visit"; return 1; }
                spent || return 1
                [ "$(pvl_policy pin)" = CANNOT_CHECK ] || { pv_log "policy: $(pvl_item policy:)"; return 1; }
                unchanged "$before" || return 1
                new_visit && pvl_pad 20 || return 1
            done ;;
        GONE)
            arm "$row" && pva_pin "$PVL_PIN" || return 1
            pvl_wait_screen master-password 30 || return 1
            pvl_text_is pv.lock.reason string lock_mp_reason_pin_stopped_working || return 1
            spent || return 1
            pvl_expect_pin slot=none off=key_gone row=0 total=0 charged=false || return 1
            [ "$(pvl_alias_count pin)" = 0 ] || { pv_log "the PIN's alias is still there: $(pvl_item keystore:)"; return 1; }
            pvl_has_canary || { pv_log "pv_canary is gone"; return 1; }
            # Set up again for the next row.
            mp_and_new_pin && lock_to_pad || return 1 ;;
    esac
}

part_pin() {
    local r
    pv_step "pin: lock now: the pad" -- lock_to_pad || return 1
    for r in $PIN_ROWS; do
        pv_step "pin: ks-fault ${r%%:*} at the PIN check: ${r#*:}" -- pin_row "${r%%:*}" "${r#*:}" || {
            # Back to a known place: the list, then the pad.
            pva_debug "ks-fault none" >/dev/null
            if pva_present pv.route.Locked 3; then mp_and_new_pin || return 1; fi
            lock_to_pad || return 1
        }
    done
    pv_step "pin: after every fault the PIN still opens" -- pin_unlock
}

# ---- part fp -------------------------------------------------------------------------------------
# fp_row ROW CLASS: from the list; armed, then Lock now: the probe meets it.
fp_row() {
    local row=$1 class=$2 before i
    before=$(snapshot) || return 1
    case "$class" in
        OK)
            arm "$row" || return 1
            pva_tap pv.list.lockNow || return 1
            pvl_touch_prompt "$PVL_FINGER" 20 || return 1
            pvl_prompt_down 15 && pvl_open_list 30 || return 1
            spent || return 1
            pvl_expect_fp slot=set ;;
        TEMPORARY)
            for i in 1 2 3; do
                arm "$row" || return 1
                pva_tap pv.list.lockNow || return 1
                pvl_wait_screen pad 20 || return 1
                pv_sleep 3
                ! pvl_prompt_shown || { pv_log "a prompt opened after a TEMPORARY probe"; return 1; }
                pva_absent pv.pin.fingerprint || { pv_log "the fingerprint key is still on the pad"; return 1; }
                pvl_text_is pv.lock.status string lock_fingerprint_cannot_check_pin || return 1
                spent || return 1
                unchanged "$before" || return 1
                pin_unlock || return 1
            done ;;
        GONE)
            arm "$row" || return 1
            pva_tap pv.list.lockNow || return 1
            pvl_wait_screen pad 20 || return 1
            pv_sleep 2
            ! pvl_prompt_shown || { pv_log "a prompt opened after a GONE probe"; return 1; }
            pvl_text_is pv.lock.status string lock_fingerprint_turned_off_pin || return 1
            spent || return 1
            pvl_expect_fp slot=none off=key_gone || return 1
            [ "$(pvl_alias_count fp)" = 0 ] || { pv_log "the fingerprint's alias is still there: $(pvl_item keystore:)"; return 1; }
            [ "$(pvl_pin slot)" = set ] || { pv_log "the PIN changed: $(pvl_show_status)"; return 1; }
            pin_unlock && fp_turn_on ;;
    esac
}

ensure_fp_on() {
    pvl_status || return 1
    [ "$(pvl_fp slot)" = set ] && return 0
    fp_turn_on
}

part_fp() {
    local r
    pv_step "fp: fingerprint unlock on" -- ensure_fp_on || return 1
    for r in $FP_ROWS; do
        pv_step "fp: ks-fault ${r%%:*} at the fingerprint probe: ${r#*:}" -- fp_row "${r%%:*}" "${r#*:}" || {
            pva_debug "ks-fault none" >/dev/null
            to_list_with_fp || return 1
        }
    done
}

# Back to the list with fingerprint on, from wherever a failed row left the app.
to_list_with_fp() {
    local now
    now=$(pvl_screen)
    case "$now" in
        prompt) pvl_prompt_negative; pv_sleep 1 ;;
    esac
    if pva_present pv.route.Locked 3 || pvl_prompt_shown; then
        pvl_pad 20 && pin_unlock || { mp_and_new_pin || return 1; }
    fi
    ensure_fp_on
}

# ---- part prompt ---------------------------------------------------------------------------------
# prompt_row ROW CLASS LINE: from the list with fingerprint on; armed, Lock now; the prompt (finger 1
# for the rows that strike at its result).
prompt_row() {
    local row=$1 class=$2 line=$3 before
    before=$(snapshot) || return 1
    arm "$row" || return 1
    pva_tap pv.list.lockNow || return 1
    case "$row" in
        prompt-error-*)
            # It ends at once, unseen; give it a moment.
            pv_sleep 3 ;;
        *)
            pvl_touch_prompt "$PVL_FINGER" 20 || return 1 ;;
    esac
    pvl_prompt_down 15 || return 1
    pvl_wait_screen pad 20 || return 1
    spent || return 1
    case "$class" in
        CLOSED)
            [ -z "$(pvl_status_line)" ] || { pv_log "a line after a closed prompt: $(pvl_status_line)"; return 1; }
            pva_present pv.pin.fingerprint 3 || { pv_log "no fingerprint key after a closed prompt"; return 1; }
            unchanged "$before" || return 1 ;;
        TEMPORARY)
            pvl_text_is pv.lock.status string "$line" || return 1
            case "$line" in
                lock_fingerprint_cannot_check_pin) pva_absent pv.pin.fingerprint || { pv_log "the key is not hidden"; return 1; } ;;
                *) pva_present pv.pin.fingerprint 3 || { pv_log "the fingerprint key is hidden"; return 1; } ;;
            esac
            unchanged "$before" || return 1 ;;
        GONE)
            pvl_text_is pv.lock.status string "$line" || return 1
            pvl_expect_fp slot=none off=bad_wrap || return 1
            [ "$(pvl_alias_count fp)" = 0 ] || { pv_log "the fingerprint's alias is still there: $(pvl_item keystore:)"; return 1; }
            pin_unlock && fp_turn_on
            return ;;
    esac
    case "$(pvl_lock_state)" in "locked: pad") ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
    pin_unlock
}

part_prompt() {
    local r row class line
    pv_step "prompt: fingerprint unlock on" -- ensure_fp_on || return 1
    for r in $PROMPT_ROWS; do
        IFS=: read -r row class line <<<"$r"
        pv_step "prompt: ks-fault $row: $class" -- prompt_row "$row" "$class" "$line" || {
            pva_debug "ks-fault none" >/dev/null
            to_list_with_fp || return 1
        }
    done
}

# ---- part create ---------------------------------------------------------------------------------
# create_row ROW TEXTS: Settings › Fingerprint unlock (off), the master password, "Turn on": the key
# cannot be made; one of TEXTS (comma-separated string names) is shown; no alias is left; nothing
# changed.
create_row() {
    local row=$1 texts=$2 before name ok=0 deadline
    before=$(snapshot) || return 1
    pva_on Passwords 20 || return 1
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.settings.fingerprint || return 1
    pva_need pv.pin.mp.field 15 || return 1
    pva_type pv.pin.mp.field "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    arm "$row" || return 1
    pva_tap pv.settings.fingerprint.turnOn || return 1
    deadline=$((SECONDS + 20))
    while [ $ok = 0 ] && [ $SECONDS -lt $deadline ]; do
        for name in ${texts//,/ }; do
            pvl_text_shown string "$name" 1 2>/dev/null && { ok=1; break; }
        done
        pvl_prompt_shown && { pv_log "a prompt opened although the key could not be made"; pvl_prompt_negative; return 1; }
    done
    [ $ok = 1 ] || { pv_log "none of $texts is shown; ids: $(pva_ids)"; return 1; }
    spent || return 1
    unchanged "$before" || return 1
    # Close what is left open (the dialog), back to the list.
    pva_present pv.settings.dialog.cancel 2 && pva_tap pv.settings.dialog.cancel
    pv_sleep 1
    pva_tap pv.tab.Passwords && pva_on Passwords 20
}

# delete while turning fingerprint off: the record says off, the alias stays; the sweep at the next
# process start deletes it.
delete_row() {
    local alias
    ensure_fp_on || return 1
    pvl_status || return 1
    alias=$(pvl_item keystore: | tr ' ' '\n' | grep '^pv_fp_')
    arm delete || return 1
    fp_turn_off || return 1
    spent || return 1
    pvl_item keystore: | tr ' ' '\n' | grep -qx "$alias" || { pv_log "the alias $alias went at once although its delete failed"; return 1; }
    pvl_force_stop && pvl_restart || return 1
    pvl_status || return 1
    ! pvl_item keystore: | tr ' ' '\n' | grep -qx "$alias" || { pv_log "the sweep did not delete $alias: $(pvl_item keystore:)"; return 1; }
    pin_unlock
}

part_create() {
    local r
    pv_step "create: fingerprint unlock off" -- eval 'pvl_status && { [ "$(pvl_fp slot)" != set ] || fp_turn_off; }' || return 1
    for r in $CREATE_ROWS; do
        pv_step "create: ks-fault ${r%%:*} when the fingerprint key is made: NOT NOW, no alias left" -- create_row "${r%%:*}" "${r#*:}" || {
            pva_debug "ks-fault none" >/dev/null
            pva_present pv.settings.dialog.cancel 2 && pva_tap pv.settings.dialog.cancel
            pva_tap pv.tab.Passwords
        }
    done
    pv_step "create: ks-fault delete while fingerprint is turned off: the alias stays; the sweep deletes it at the next start" -- delete_row
}

# ---- part drop -----------------------------------------------------------------------------------
drop_keys() {
    local out
    out=$(pva_debug drop-keys) || return 1
    case "$out" in "drop-keys: "*" aliases deleted") ;; *) pv_log "drop-keys answered '$out'"; return 1 ;; esac
    pvl_status || return 1
    [ "$(pvl_item keystore:)" = "keystore: no alias listed" ] || { pv_log "$(pvl_item keystore:)"; return 1; }
}

# The next visit after drop-keys: the fingerprint probe finds its key gone (the canary is made again
# first, so the answer is GONE and not "the Keystore is not answering"); the PIN, typed, is GONE too.
drop_next_visit() {
    pva_tap pv.list.lockNow || return 1
    pvl_wait_screen pad 20 || return 1
    pv_sleep 2
    ! pvl_prompt_shown || { pv_log "a prompt opened"; return 1; }
    pvl_text_is pv.lock.status string lock_fingerprint_turned_off_pin || return 1
    pvl_expect_fp slot=none off=key_gone || return 1
    pvl_has_canary || { pv_log "the canary was not made again: $(pvl_item keystore:)"; return 1; }
    pva_pin "$PVL_PIN" || return 1
    pvl_wait_screen master-password 30 || return 1
    pvl_text_is pv.lock.reason string lock_mp_reason_pin_stopped_working || return 1
    pvl_expect_pin slot=none off=key_gone row=0 total=0
}

drop_mp_opens() {
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    # "Choose a new PIN" and the fingerprint offer: both set up again for the next part.
    pvl_pin_steps "$PVL_PIN" fingerprint || return 1
    pvl_expect_pin slot=set && pvl_expect_fp slot=set
}

part_drop() {
    pv_step "drop: PIN and fingerprint on" -- ensure_fp_on || return 1
    pv_step "drop: drop-keys deletes every alias, the canary too" -- drop_keys || return 1
    pv_step "drop: the next visit: each shortcut GONE, \"stopped working\", not \"restart your phone\"" -- drop_next_visit
    pv_step "drop: the master password opens; PIN and fingerprint set up again" -- drop_mp_opens
}

# ---- part reinstall ------------------------------------------------------------------------------
SURVIVED=""

reinstall_keep_data() {
    local out
    pvl_status || return 1
    pvl_item keystore: > "$PVL_OUT/keystore-before-reinstall.txt"
    out=$(pv_shell pm uninstall -k "$PV_PACKAGE" 2>&1 | tr -d '\r')
    case "$out" in *Success*) ;; *) pv_log "pm uninstall -k: $out"; return 1 ;; esac
    out=$(PV_ADB_TIMEOUT=120 pv_adb install -r "$APK" 2>&1)
    printf '%s\n' "$out" | grep -q '^Success' || { pv_log "adb install: $out"; return 1; }
    pva_start || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 60
}

keys_after_reinstall() {
    local before now pin=no fp=no
    pvl_status || return 1
    before=$(cat "$PVL_OUT/keystore-before-reinstall.txt")
    now=$(pvl_item keystore:)
    pvl_items | grep -q '^lock ' || { pv_log "the lock directory is gone: $(pvl_show_status)"; return 1; }
    printf '%s\n' "$before" | tr ' ' '\n' | grep '^pv_pin_' | while read -r a; do printf '%s\n' "$now" | tr ' ' '\n' | grep -qx "$a" && echo pin; done | grep -q pin && pin=yes
    printf '%s\n' "$before" | tr ' ' '\n' | grep '^pv_fp_' | while read -r a; do printf '%s\n' "$now" | tr ' ' '\n' | grep -qx "$a" && echo fp; done | grep -q fp && fp=yes
    SURVIVED="pin=$pin fp=$fp"
    pvl_note "E10 on API $API ($VARIANT): after pm uninstall -k and a new install the PIN key survived: $pin, the fingerprint key survived: $fp (before: $before; after: $now)"
}

# Each shortcut that lost its key says that it stopped working; one that kept it works.
shortcuts_after_reinstall() {
    local now
    now=$(pvl_screen)
    [ "$now" = prompt ] && { pvl_prompt_negative; pv_sleep 1; now=$(pvl_screen); }
    case "$SURVIVED" in
        *"fp=no"*)
            pvl_status || return 1
            [ "$(pvl_fp slot)" = none ] || { pv_log "the fingerprint slot stays without its key: $(pvl_show_status)"; return 1; }
            [ "$now" = pad ] && { pvl_text_is pv.lock.status string lock_fingerprint_turned_off_pin || return 1; } ;;
    esac
    case "$SURVIVED" in
        *"pin=no"*)
            [ "$now" = pad ] || { pv_log "no pad (the screen is '$now')"; return 1; }
            pva_pin "$PVL_PIN" || return 1
            pvl_wait_screen master-password 30 || return 1
            pvl_text_is pv.lock.reason string lock_mp_reason_pin_stopped_working || return 1 ;;
        *"pin=yes"*)
            [ "$now" = pad ] || { pv_log "no pad although the PIN key survived (the screen is '$now')"; return 1; }
            pva_pin "$PVL_PIN" && pvl_open_list 30 || return 1
            lock_to_pad || return 1 ;;
    esac
}

reinstall_mp_opens() {
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_submit_open pv.lock.unlock Passwords 90
}

part_reinstall() {
    pv_step "reinstall: PIN and fingerprint on" -- ensure_fp_on || return 1
    pv_step "reinstall: pm uninstall -k, then the APK installed again: the lock screen" -- reinstall_keep_data || return 1
    pv_step "reinstall: the keys that survived are noted; the lock directory is kept" -- keys_after_reinstall || return 1
    pv_step "reinstall: a shortcut without its key says it stopped working; one with it works" -- shortcuts_after_reinstall
    pv_step "reinstall: the master password opens the vault" -- reinstall_mp_opens
}

# ---- the run -----------------------------------------------------------------------------------------
pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "every row ks-fault names is one this check tests" -- rows_known
pv_step "set up with the PIN (fingerprint not now): the list" -- pvl_set_up "$PVL_PIN" - || pvl_end
want pin && part_pin
if want fp || want prompt || want create || want drop || want reinstall; then
    pva_on Passwords 5 || { pvl_status; case "$(pvl_lock_state)" in locked*) pin_unlock || mp_and_new_pin ;; esac; }
fi
want fp && part_fp
want prompt && part_prompt
want create && part_create
want drop && part_drop
want reinstall && part_reinstall
pvl_end
