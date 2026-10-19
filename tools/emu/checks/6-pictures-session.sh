#!/usr/bin/env bash
# tools/emu/checks/6-pictures-session.sh - the real app in one language, driven through the states that
# the layout walk does not reach, with a picture of each: part of the owner's picture set of the device
# check of waves 6 and 8A (checks/6-pictures.sh; build plan 12, "A picture of every screen in each of the
# four languages"). Made from the scripts of R6's packages: 6.4a's ru-a-lock.sh (the lock's states),
# 6.3a's session.sh (the pad's lines) and 6.3b's bstates.sh (part b's screens behind the system's picker),
# for any of the four languages, with the same picture names in each.
#
#   tools/emu/checks/6-pictures-session.sh SERIAL --apk FILE --lang TAG --out DIR [--only PART,PART]
#
# SERIAL is an API 37 device of this worktree (the system's own prompt, cmd locale, cmd alarm set-time),
# or for the parts setup and pad an API 24 device (androidx.biometric's own dialog in the activity; the
# language set with the debug command `locale`); FILE a debug APK (screenshots, run-as). TAG is the app's
# own language tag (en, uz, uz-Cyrl, ru), set with cmd locale set-app-locales before the app first starts. The pictures go to DIR as
# NNN-name.png; the numbers are fixed, so that a picture of one state has the same name in every
# language. A picture of a screen that scrolls is taken once per swipe while the screen changes
# (NNN-name-1, -2, ...).
#
# The parts, in order (--only runs some of them after the set-up; each starts from the list or comes back
# to it, and a part that fails is reported and the next one starts):
#   setup      a fresh install; setup with a PIN refused, then chosen; fingerprint unlock turned on
#              through the system's prompt; an account through the form.
#   pad        Lock now: the prompt opens by itself; "Use PIN"; wrong PINs: the pad's lines (tries left,
#              the same PIN again, the last try), the fifth: the PIN off, the fingerprint-only screen;
#              "Use master password", a wrong one, the right one: the notices and "Choose a new PIN".
#   newmp      a new master password (lock.md F11): "I don't remember it", the explanation, the PIN, the
#              choice, the wait, the list's banner; the next unlock; made ready (newmp.sh's debug
#              shortcut): the notice, "Finish now", the finishing step, the backup step; then the old
#              master password typed on the lock screen.
#   files      the import (sources, Google's help, a crafted CSV's preview with its counts and result,
#              the fifty accounts), the Security tab with them (cards, a group, a row's menu, "Leave out",
#              a guided change left for later), the account with a change waiting, the backup, the
#              exports (the warnings, "Delete it now", one CSV kept: the list's banners), Settings' dialogs,
#              Recently deleted's "Delete forever".
#   time       the clock moved on (cmd alarm set-time; put back at the end): the practice screen within
#              its grace and past it, "I can't remember it" and the list's overdue banner, the backup
#              banner and "Make a backup now?".
#   keys       fingerprint and PIN keys gone (the debug command drop-keys): the lock screen's lines, the
#              master password, "Turn fingerprint unlock on again?".
#   empty      "Forgot master password?" > "Start with an empty app": the question, a new vault, Settings'
#              "Old passwords" and its dialogs.
#   restore    a restore from Welcome with the backup of the part files: the intro, the master password,
#              the summary.
# Exit 0 when no step failed.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lock/lib.sh"
source "$EMU/lock/newmp.sh"

LANG_TAG=""
OUT=""
ONLY=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --lang) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LANG_TAG=$2; shift ;;
        --out) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; OUT=$2; shift ;;
        --only) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; ONLY=$2; shift ;;
        *) ARGS+=("$1") ;;
    esac
    shift
done
[ -n "$LANG_TAG" ] && [ -n "$OUT" ] || pv_usage "${BASH_SOURCE[0]}"
pvl_begin pictures-session "${ARGS[@]}"
mkdir -p "$OUT"
PIN1=$PVL_PIN
PIN2=274816
FIFTY=$REPO/app/src/androidTest/assets/fixtures/fifty-accounts.csv
PREVIEW_CSV=pv-pictures-preview.csv
FIFTY_CSV=pv-pictures-fifty.csv
BACKUP=""
BACKUP_PASSWORD=""
FIRST_PASSWORD=""
CLOCK_MOVED=0

want() { [ -z "$ONLY" ] || [[ ",$ONLY," == *",$1,"* ]]; }

# shot N NAME [WAIT]: the screen as OUT/N-NAME.png after WAIT seconds (default 1).
shot() {
    pv_sleep "${3:-1}"
    pva_screenshot "$OUT/$1-$2.png" >/dev/null 2>&1 && echo "SHOT $1-$2"
    return 0
}

# shots N NAME [MAX]: a screen that scrolls: N-NAME-1, then one per swipe while the screen changes (at
# most MAX, default 6); scrolled back to the top after.
shots() {
    local n=$1 name=$2 max=${3:-6} i before after
    pva_hide_keyboard
    shot "$n" "$name-1"
    for ((i = 2; i <= max; i++)); do
        before=$(pv_ui_nodes | md5sum)
        pva_scroll_down
        after=$(pv_ui_nodes | md5sum)
        [ "$before" != "$after" ] || break
        shot "$n" "$name-$i"
    done
    for ((i = 1; i < max; i++)); do pva_scroll_up; done
}

# first_id PREFIX: the first resource id on the screen that starts with PREFIX.
first_id() { pv_ui_nodes | awk -F'|' -v p="$1" 'index($3, p) == 1 { print $3; exit }'; }

back() { pv_shell input keyevent KEYCODE_BACK >/dev/null; pv_sleep 1; }

# notice_answer: a notice's "OK", else "Later", else its first answer.
notice_answer() {
    local answer id
    for answer in OK LATER NOT_NOW; do
        pva_present "pv.answer.$answer" 1 && { pva_tap "pv.answer.$answer"; return; }
    done
    id=$(first_id pv.answer.)
    [ -n "$id" ] || { pv_log "a notice without an answer; ids: $(pva_ids)"; return 1; }
    pva_tap "$id"
}

# settle ROUTE PIN [N]: after an unlock, until ROUTE is shown alone: a picture of each notice (N-notice-K,
# answered "OK" or "Later"), of "Choose a PIN" (N-create-pin, then PIN twice), the fingerprint offer
# "Not now", "Make backups" "Finish", a prompt closed with its negative button.
NOTICES=0
settle() {
    local route=$1 pin=$2 n=${3:-0} deadline=$((SECONDS + 180)) nodes
    while :; do
        nodes=$(pv_ui_nodes)
        if pvl_prompt_in "$nodes"; then
            pvl_prompt_negative
        elif pva_in_nodes '[|]pv\.notice$' "$nodes"; then
            NOTICES=$((NOTICES + 1))
            [ "$n" = 0 ] || shot "$n" "notice-$NOTICES"
            notice_answer || return 1
        elif pva_in_nodes '[|]pv\.route\.CreatePin$' "$nodes"; then
            [ "$n" = 0 ] || shot "$n" create-pin
            pva_pin "$pin" || return 1
            pv_sleep 1
        elif pva_in_nodes '[|]pv\.route\.ConfirmPin$' "$nodes"; then
            pva_pin "$pin" || return 1
            pv_sleep 2
        elif pva_in_nodes '[|]pv\.route\.FingerprintOffer$' "$nodes"; then
            pva_tap pv.fingerprintOffer.notNow || return 1
        elif pva_in_nodes '[|]pv\.route\.BackupsExplained$' "$nodes"; then
            pva_tap pv.action.finish || return 1
        elif pva_in_nodes "[|]pv\\.route\\.$route\$" "$nodes"; then
            pv_sleep 3
            nodes=$(pv_ui_nodes)
            if pva_in_nodes "[|]pv\\.route\\.$route\$" "$nodes" \
                && ! pva_in_nodes "[|]pv\\.(notice|route\\.($PVA_PIN_STEPS))\$" "$nodes" && ! pvl_prompt_in "$nodes"; then
                return 0
            fi
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "not settled on $route; ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
}

# to_list: the list with nothing over it, from wherever a part ended: Back up to four times, the lock
# screen answered with the PIN or the master password.
to_list() {
    local i now
    for i in 1 2 3 4 5 6; do
        pva_in_front || pva_relaunch
        now=$(pvl_screen)
        case "$now" in
            list) pva_present pv.tab.Passwords 2 && return 0 ;;
            pad) pva_pin "$PIN_NOW" && pvl_wait_answer 20; settle Passwords "$PIN_NOW" 0 && return 0 ;;
            prompt) pvl_prompt_negative ;;
            master-password)
                pva_type pv.lock.password "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock && pv_sleep 3
                settle Passwords "$PIN_NOW" 0 && return 0 ;;
            fingerprint-only) pva_tap pv.lock.useMasterPassword ;;
            older) if pva_present pv.lock.olderTryAgain 1; then pva_tap pv.lock.olderTryAgain; else pva_tap pv.lock.olderOk; fi ;;
            notice | pin-steps) settle Passwords "$PIN_NOW" 0 && return 0 ;;
            *) if pva_present pv.tab.Passwords 1; then pva_tap pv.tab.Passwords; else back; fi ;;
        esac
        pv_sleep 1
    done
    pva_on Passwords 10
}
PIN_NOW=$PIN1

settings() { to_list && pva_tap pv.tab.Settings && pva_on Settings 20; }

# ---- setup ----------------------------------------------------------------------------------------------
start_in_language() {
    pva_install "$APK" || return 1
    if [ "$API" -lt 33 ]; then
        pva_start && pva_on Welcome 30 && pva_debug "locale $LANG_TAG" >/dev/null && pv_sleep 3 && pva_on Welcome 30
        return
    fi
    pv_shell cmd locale set-app-locales "$PV_PACKAGE" --locales "$LANG_TAG" >/dev/null || return 1
    pv_shell cmd locale get-app-locales "$PV_PACKAGE" | tr -d '\r' | grep -q "\[$LANG_TAG\]" \
        || { pv_log "the app's locales: $(pv_shell cmd locale get-app-locales "$PV_PACKAGE" | tr -d '\r')"; return 1; }
    pva_start && pva_on Welcome 30
}

set_up() {
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    FIRST_PASSWORD=$PVA_PASSWORD
    pva_tap pv.open.WriteDown && pva_on WriteDown || return 1
    pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.action.createVault || return 1
    pva_on CreatePin 120 || return 1
    pv_sleep 1
    # A PIN the blocklist refuses (a run of digits): its line.
    pva_pin 123456 || return 1
    shot 101 pin-refused 1.5
    pva_on CreatePin 10 && pva_pin "$PIN1" || return 1
    pva_on ConfirmPin 20 && pv_sleep 1 && pva_pin "$PIN1" || return 1
    pva_on FingerprintOffer 30 && pva_tap pv.fingerprintOffer.use || return 1
    pvl_prompt_up 15 || return 1
    shot 102 prompt-turn-on 2
    pvl_finger "$PVL_FINGER"
    pvl_prompt_down 15 || return 1
    pva_on BackupsExplained 30 && pva_tap pv.action.finish || return 1
    settle Passwords "$PIN1" 103
}

part_setup() {
    pv_step "a fresh install in $LANG_TAG; Welcome" -- start_in_language || return 1
    pv_step "setup: a refused PIN, a PIN, fingerprint unlock through the system's prompt" -- set_up || return 1
    pv_step "an account through the form" -- pva_add_account Gmail aziz.karimov@gmail.com Toshkent2024 mail.google.com 'Recovery codes in the safe'
    pv_step "the list" -- to_list
    want deleted && pv_step "an account deleted; Recently deleted, Delete forever" -- delete_forever
}

# ---- pad: the lock screen's PIN pad and its lines ----------------------------------------------------------
lock_with_prompt() {
    to_list && pva_tap pv.list.lockNow || return 1
    pvl_prompt_up 20 || return 1
    shot 110 prompt-unlock 2
    pvl_prompt_negative && pvl_pad 20
}

fifth_pin() {
    pvl_pad 20 && pva_pin "${PVL_WRONG[4]}" || return 1
    pv_sleep 3
    if pvl_prompt_shown; then
        shot 116 fingerprint-only-prompt 1
        back
        pv_sleep 2
    fi
    pvl_wait_screen fingerprint-only 20 && shot 117 fingerprint-only
}

master_after_pin_off() {
    pva_tap pv.lock.useMasterPassword && pva_need pv.lock.password 15 || return 1
    pva_hide_keyboard
    shot 118 master-password-pin-off
    pva_type pv.lock.password "not-the-password" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pva_need pv.lock.line.0 60 && shot 119 master-password-wrong
    pva_need pv.lock.password 10 || return 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    local i
    for i in $(seq 1 20); do pv_shell input keyevent KEYCODE_DEL >/dev/null; done
    pva_type pv.lock.password "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    settle Passwords "$PIN2" 120 && PIN_NOW=$PIN2
}

part_pad() {
    pv_step "Lock now: the prompt opens by itself; Use PIN: the pad" -- lock_with_prompt || return 1
    shot 111 pad
    pv_step "a wrong PIN" -- pvl_wrong_pins 1 0 && shot 112 pad-wrong-1
    pv_step "the same wrong PIN again" -- eval 'pvl_pad 20 && pva_pin "${PVL_WRONG[0]}" && pvl_wait_answer' && shot 113 pad-same-again
    pv_step "two more wrong PINs" -- pvl_wrong_pins 2 1 && shot 114 pad-wrong-3
    pv_step "a fourth wrong PIN: the last try" -- pvl_wrong_pins 1 3 && shot 115 pad-last-try
    pv_step "the fifth wrong PIN turns the PIN off: the fingerprint-only screen" -- fifth_pin
    pv_step "Use master password; a wrong one; the right one: the notices, \"Choose a new PIN\"" -- master_after_pin_off
}

# ---- newmp: a new master password (lock.md F11) ------------------------------------------------------------
request_new() {
    settings || return 1
    pva_tap pv.open.ChangeMasterPassword || return 1
    pva_need pv.open.NewMasterPassword 20 && pva_tap pv.open.NewMasterPassword || return 1
    pva_need pv.master.explain 20 || return 1
    shots 130 f11-explain 4
    pva_reveal pv.master.continue && pva_tap pv.master.continue || return 1
    pva_need pv.pin.key.0 20 || return 1
    shot 131 f11-pin-proof
    pva_pin "$PIN_NOW" || return 1
    pva_reveal pv.setup.passphrase 2>/dev/null || pva_need pv.setup.passphrase 30 || return 1
    shots 132 f11-choose 4
    PVN_NEW_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    pva_tap pv.open.WriteDown && pva_need pv.setup.wrote 20 || return 1
    shots 133 f11-write-down 3
    pva_reveal pv.open.TypeAgain && pva_tap pv.open.TypeAgain || return 1
    pva_type pv.setup.again "$PVN_NEW_PASSWORD" || return 1
    pva_hide_keyboard
    shot 134 f11-type-again
    pva_tap pv.action.createVault || return 1
    local deadline=$((SECONDS + 60)) i
    until pva_present pv.master.done 2 || pva_present pv.master.wait 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "no wait screen; ids: $(pva_ids)"; return 1; }
    done
    shots 135 f11-wait 3
    pva_present pv.master.done 5 && pva_tap pv.master.done
    for i in 1 2 3 4 5; do
        pva_present pv.route.Passwords 2 && break
        if pva_present pv.tab.Passwords 1; then pva_tap pv.tab.Passwords; else back; fi
    done
    pva_on Passwords 20 && shot 136 list-pending-banner 2
}

unlock_while_pending() {
    to_list && pva_tap pv.list.lockNow || return 1
    pv_sleep 2
    pvl_pad 20 || return 1
    shot 137 pad-pending
    pva_pin "$PIN_NOW" && pvl_wait_answer 20
    settle Passwords "$PIN_NOW" 138
}

ready_and_finish() {
    pvn_make_ready || return 1
    pva_start || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30 || return 1
    pvl_pad 20 && pva_pin "$PIN_NOW" || return 1
    local deadline=$((SECONDS + 60))
    until pva_present pv.answer.FINISH_NOW 2; do
        if pva_present pv.notice 1 && ! pva_present pv.answer.FINISH_NOW 1; then
            NOTICES=$((NOTICES + 1))
            shot 139 "notice-$NOTICES"
            notice_answer
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "no Finish now; ids: $(pva_ids)"; return 1; }
    done
    shot 140 notice-ready 1
    pva_tap pv.answer.FINISH_NOW || return 1
    pva_need pv.master.finish.field 30 || return 1
    pva_hide_keyboard
    shot 141 f11-finish
    pva_type pv.master.finish.field "$PVN_NEW_PASSWORD" && pva_hide_keyboard && pva_tap pv.master.finish || return 1
    deadline=$((SECONDS + 120))
    until pva_present pv.route.CreatePin 2 || pva_present pv.notice 2 || pva_present pv.master.later 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "the finishing did not end; ids: $(pva_ids)"; return 1; }
    done
    if pva_present pv.route.CreatePin 5 || pva_present pv.notice 1; then
        settle NewMasterPassword "$PIN1" 142 || true
    fi
    pva_need pv.master.later 30 || return 1
    shots 143 f11-backup-step 3
    pva_tap pv.master.later || return 1
    PVA_PASSWORD=$PVN_NEW_PASSWORD
    PIN_NOW=$PIN1
    pva_on Passwords 30 && shot 144 list-after-finish 2
}

# The master password from before the change, typed on the lock screen.
old_password() {
    to_list && pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && pvl_prompt_negative
    if pvl_wait_screen pad 10; then pva_tap pv.lock.forgotPin || return 1; fi
    pva_need pv.lock.password 20 || return 1
    pva_type pv.lock.password "$FIRST_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pv_sleep 4
    pva_hide_keyboard
    shot 145 master-password-older
    if pva_present pv.lock.olderOk 3; then pva_tap pv.lock.olderOk; fi
    to_list
}

part_newmp() {
    pv_step "a new master password asked for with the PIN (F11): the explanation, the PIN, the choice, the wait, the banner" -- request_new || return 1
    pv_step "Lock now and the PIN while it waits" -- unlock_while_pending
    pv_step "made ready: the notice, Finish now, the finishing step, the backup step" -- ready_and_finish
    pv_step "the master password from before the change, on the lock screen" -- old_password
}

# ---- files: import, Security, backup, export, Settings ------------------------------------------------------
push_files() {
    local f
    f=$(mktemp "${TMPDIR:-/tmp}/pv-pictures-preview.XXXXXX") || return 1
    # Related websites (Gmail and YouTube: one login), a repeated row, a row without a password, a
    # password with a tab, a row with one value too many, an empty row: the preview's counts.
    printf '%s\r\n' 'name,url,username,password,note' \
        'accounts.google.com,https://accounts.google.com,dilshod.m@gmail.com,Qx7mVb2Lp9Ws,' \
        'youtube.com,https://www.youtube.com,dilshod.m@gmail.com,Qx7mVb2Lp9Ws,' \
        'Kapitalbank,https://kapitalbank.uz,+998901112233,Hn4Rt8Ws2Kz6,' \
        'Kapitalbank,https://kapitalbank.uz,+998901112233,Hn4Rt8Ws2Kz6,' \
        'Payme,https://payme.uz,+998901112233,,' \
        "Click,https://click.uz,+998901112233,\"Tb	9Lp2Wq7Zx\"," \
        'Pochta,https://pochta.uz,dilshod,Pw4Tq8Lm2Xz,eslatma,ortiqcha' \
        ',,,,' \
        'Telegram,https://web.telegram.org,+998901112233,Tg9Lp2Wq7Zx4,' > "$f"
    pv_shell "rm -f $PVA_DOWNLOAD/PasswordVault-backup-* $PVA_DOWNLOAD/passwords-* $PVA_DOWNLOAD/pv-pictures-*" >/dev/null 2>&1
    pva_push_download "$f" "$PREVIEW_CSV" && pva_push_download "$FIFTY" "$FIFTY_CSV"
    local rc=$?
    rm -f "$f"
    return $rc
}

open_import() { settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20; }

pick_csv() { # NAME
    local deadline=$((SECONDS + 20))
    pva_need pv.import.source.csv 20 && pva_tap pv.import.source.csv || return 1
    until pva_picker_in_front; do
        [ $SECONDS -lt $deadline ] || { pv_log "no picker"; return 1; }
        pva_present pv.import.chooseFile 1 && pva_tap pv.import.chooseFile
        pv_sleep 1
    done
    pva_picker_open "$1" pushed && pva_need pv.import.import 60
}

import_screens() {
    open_import || return 1
    pva_tap pv.import.source.google && pva_need pv.import.help 20 || return 1
    pva_tap pv.import.help.cantFind && pv_sleep 1
    shots 201 import-google-help-cant-find 4
    back
    if pva_picker_in_front; then back; fi
    pva_on Import 20 || open_import || return 1
    pick_csv "$PREVIEW_CSV" || return 1
    shots 202 import-preview 8
    if pva_present pv.import.keepSeparate 2; then pva_tap pv.import.keepSeparate && shot 203 import-kept-separate; fi
    pva_tap pv.import.import && pva_need pv.import.done 60 || return 1
    shots 204 import-result 4
    if pva_present pv.import.keepFile 5; then pva_tap pv.import.keepFile && pv_sleep 1 && shot 205 import-file-kept; fi
    pva_tap pv.import.finish && pva_on Settings 20
}

import_fifty() {
    open_import && pick_csv "$FIFTY_CSV" || return 1
    shots 206 import-fifty-preview 3
    pva_tap pv.import.import && pva_need pv.import.done 60 || return 1
    shots 207 import-fifty-result 3
    if pva_present pv.import.deleteFile 5; then
        pva_tap pv.import.deleteFile && pva_need pv.import.fileOutcome 20 && shot 208 import-file-deleted
    fi
    pva_tap pv.import.finish && pva_on Settings 20
}

security() {
    local row action item
    to_list && pva_tap pv.tab.Security && pva_on Security 20 || return 1
    pv_sleep 4
    shots 210 security 8
    pva_reveal pv.security.card.reused.open && pva_tap pv.security.card.reused.open && pva_on Group 20 || return 1
    shots 211 security-group 4
    row=$(first_id pv.group.more.)
    if [ -n "$row" ]; then
        pva_tap "$row" && pv_sleep 1 && shot 212 security-row-menu
        item=$(pv_ui_nodes | awk -F'|' '$1 != "" { print $6, $7; exit }')
        if [ -n "$item" ]; then pv_shell input tap $item >/dev/null && pv_sleep 1 && shot 213 security-left-out-message; fi
        pv_sleep 4
    fi
    action=$(first_id pv.group.action.)
    [ -n "$action" ] || { pv_log "no row action; ids: $(pva_ids)"; return 1; }
    pva_tap "$action" && pva_on GuidedChange 20 || return 1
    shots 214 change-steps 6
    pva_reveal pv.change.create && pva_tap pv.change.create && pva_need pv.change.saved 20 || return 1
    shots 215 change-created 6
    pva_reveal pv.change.later && pva_tap pv.change.later || return 1
    pv_sleep 2
    shot 216 change-later
    back
    to_list && pva_tap pv.tab.Security && pva_on Security 20 && pv_sleep 3 && shots 217 security-after 8
}

# The account whose change waits: its screen, from the list's banner or its row.
change_waiting() {
    local row
    to_list || return 1
    shots 218 list-change-waiting 3
    if pva_present pv.list.banner.changes.answer 2; then
        pva_tap pv.list.banner.changes.answer && pv_sleep 2
    else
        row=$(first_id pv.list.row.)
        [ -n "$row" ] && pva_tap "$row" && pv_sleep 2
    fi
    shots 219 entry-change-waiting 4
    to_list
}

backup() {
    settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    if pva_present pv.backup.continue 3; then shots 220 backup-explain 3; pva_tap pv.backup.continue || return 1; fi
    pva_need pv.backup.password 20 && shot 221 backup-password || return 1
    pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_picker_save && pva_need pv.backup.done 60 || return 1
    shots 222 backup-saved 3
    BACKUP=$(pva_in_download '^PasswordVault-backup-.*\.pvault$' | tail -n 1)
    BACKUP_PASSWORD=$PVA_PASSWORD
    pva_tap pv.backup.done && pva_on Settings 20
}

export_csv() { # N-PREFIX DELETE(yes|no)
    local n=$1 delete=$2
    settings && pva_reveal pv.open.Export && pva_tap pv.open.Export && pva_on Export 20 || return 1
    pva_need pv.export.choice.csv 15 || return 1
    if [ "$delete" = yes ]; then
        shots "$n" export-choose 3
        pva_tap pv.export.choice.xlsx && pva_tap pv.export.continue && pva_need pv.export.warning 15 && shots $((n + 1)) export-warning-xlsx 5
        back
        pva_need pv.export.choice.csv 15 || { settings && pva_tap pv.open.Export && pva_need pv.export.choice.csv 15; } || return 1
        pva_tap pv.export.choice.json && pva_tap pv.export.continue && pva_need pv.export.warning 15 && shots $((n + 2)) export-warning-json 5
        back
        pva_need pv.export.choice.csv 15 || { settings && pva_tap pv.open.Export && pva_need pv.export.choice.csv 15; } || return 1
    fi
    pva_tap pv.export.choice.csv && pva_tap pv.export.continue && pva_need pv.export.warning 15 || return 1
    if [ "$delete" = yes ]; then
        shots $((n + 3)) export-warning-csv 6
        if pva_reveal pv.export.csv.seeWhich; then pva_tap pv.export.csv.seeWhich && pv_sleep 1 && shots $((n + 4)) export-warning-csv-which 6; fi
    fi
    pva_reveal pv.export.understand && pva_tap pv.export.understand || return 1
    pva_reveal pv.export.warning.continue && pva_tap pv.export.warning.continue || return 1
    pva_need pv.export.password 20 || return 1
    [ "$delete" = yes ] && shot $((n + 5)) export-password
    pva_type pv.export.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.export.submit && pva_picker_save && pva_need pv.export.saved 60 || return 1
    if [ "$delete" = yes ]; then
        shots $((n + 6)) export-saved 3
        if pva_present pv.export.delete 3; then pva_tap pv.export.delete && pv_sleep 2 && shots $((n + 7)) export-deleted 3; fi
    else
        shots "$n" export-saved-kept 3
    fi
    pva_tap pv.export.done && pva_on Settings 20
}

list_banners() { to_list && shots 240 list-banners 3; }

settings_dialogs() {
    settings || return 1
    pva_reveal pv.settings.fingerprint && pva_tap pv.settings.fingerprint && pv_sleep 2 && shot 241 settings-fingerprint-switch
    pva_hide_keyboard
    if pva_present pv.settings.dialog.cancel 2; then pva_tap pv.settings.dialog.cancel; else back; fi
    to_list
}

# Recently deleted: an account deleted from its screen's menu (its message), the list of deleted
# accounts, a row's menu and "Delete forever?".
delete_forever() {
    local more item
    to_list && pva_add_account Olx aziz olx-Samarqand1999 olx.uz 'Old account' || return 1
    pva_tap pv.entry.more && pva_need pv.entry.menu.delete 10 && pva_tap pv.entry.menu.delete || return 1
    pva_need pv.entry.deleted 10 && shot 105 entry-deleted
    to_list && pva_reveal pv.list.recentlyDeleted && pva_tap pv.list.recentlyDeleted && pva_on RecentlyDeleted 20 || return 1
    shot 106 recently-deleted
    more=$(first_id pv.deleted.more.)
    [ -n "$more" ] || { pv_log "no row menu; ids: $(pva_ids)"; return 1; }
    pva_tap "$more" && pv_sleep 1 && shot 107 recently-deleted-menu
    item=$(pv_ui_nodes | awk -F'|' '$1 != "" { print $6, $7; exit }')
    [ -n "$item" ] && pv_shell input tap $item >/dev/null && pva_need pv.list.dialogConfirm 10 || return 1
    shot 108 recently-deleted-forever-question
    back
    to_list
}

part_files() {
    pv_step "the import's files pushed to Download" -- push_files || return 1
    pv_step "import: Google's help, a CSV's preview and its result" -- import_screens
    pv_step "import: the fifty accounts" -- import_fifty
    pv_step "Security: the cards, a group, a row's menu, Leave out, a guided change left for later" -- security
    pv_step "the list and the account whose change waits" -- change_waiting
    pv_step "Backup: the explanation, the master password, saved" -- backup
    pv_step "Export: the formats, the warnings, the master password, saved, deleted" -- export_csv 225 yes
    pv_step "Export: a CSV kept" -- export_csv 235 no
    pv_step "the list's banners (a change waiting, a CSV kept)" -- list_banners
    pv_step "Settings: the fingerprint switch" -- settings_dialogs
}

# ---- time: the practice schedule and the backup reminder (the wall clock moved on) ---------------------------
clock_back() {
    [ "$CLOCK_MOVED" = 1 ] || return 0
    pv_shell cmd alarm set-time "$(date +%s%3N)" >/dev/null 2>&1
    CLOCK_MOVED=0
}
trap 'clock_back; pvl_cleanup' EXIT

clock_ahead() { # HOURS
    local now target
    now=$(date +%s%3N)
    target=$((now + $1 * 3600 * 1000))
    pv_shell cmd alarm set-time "$target" >/dev/null 2>&1 || return 1
    CLOCK_MOVED=1
    pv_sleep 2
    pv_log "the phone's clock: $(pv_shell date | tr -d '\r')"
}

practice_in_grace() {
    to_list || return 1
    clock_ahead 26 || return 1
    pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password|notice' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && pvl_prompt_negative
    pva_need pv.lock.password 20 || return 1
    pva_hide_keyboard
    shots 250 practice-in-grace 3
    if pva_present pv.lock.notNow 3; then
        pva_tap pv.lock.notNow && pv_sleep 2 && shot 251 practice-not-now
    fi
    to_list
}

practice_required() {
    to_list || return 1
    clock_ahead $((24 * 9)) || return 1
    pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password|notice' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && pvl_prompt_negative
    pva_need pv.lock.password 20 || return 1
    pva_hide_keyboard
    shots 252 practice-required 3
    pva_tap pv.lock.cantRemember || return 1
    pv_sleep 3
    shots 253 practice-cant-remember 4
    # Leave the new master password's steps: Cancel or Back until the list.
    if pva_present pv.master.cancel 3; then pva_tap pv.master.cancel; else back; fi
    pv_sleep 2
    shots 254 list-practice-overdue 3
    if pva_present pv.list.banner.practice.typeItNow 3; then
        pva_tap pv.list.banner.practice.typeItNow && pva_need pv.list.practiceField 10 && pva_hide_keyboard && shot 257 practice-dialog
        pva_type pv.list.practiceField "not-the-password" && pva_hide_keyboard && pva_tap pv.list.practiceCheck && pv_sleep 4 && shot 258 practice-dialog-wrong
        back
    fi
    to_list
}

backup_offer() {
    # The master password typed on the lock screen with the backup due: "Make a backup now?".
    to_list && pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && pvl_prompt_negative
    if pvl_wait_screen pad 5; then pva_tap pv.lock.forgotPin || return 1; fi
    pva_need pv.lock.password 20 && pva_type pv.lock.password "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    settle Passwords "$PIN_NOW" 255
    shots 256 list-backup-due 3
}

part_time() {
    pv_step "the clock a day on: the practice screen within its grace, Not now" -- practice_in_grace
    pv_step "the clock ten days on: practice required; I can't remember it; the list's overdue banner" -- practice_required
    pv_step "the master password with a backup due: Make a backup now?, the backup banner" -- backup_offer
    pv_step "the clock back" -- clock_back
}

# ---- keys: the Keystore keys gone ----------------------------------------------------------------------------
keys_gone() {
    to_list || return 1
    pva_debug drop-keys >/dev/null || return 1
    pva_tap pv.list.lockNow || return 1
    pv_sleep 3
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password|notice' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && { shot 260 keys-prompt; pvl_prompt_negative; pv_sleep 2; }
    pva_hide_keyboard
    shots 261 keys-lock-screen 3
    if [ "$(pvl_screen)" = pad ]; then
        pva_pin "$PIN_NOW" && pv_sleep 3 && pva_hide_keyboard && shots 262 keys-after-pin 3
    fi
    if pva_present pv.lock.useMasterPassword 2; then pva_tap pv.lock.useMasterPassword; fi
    if pva_present pv.lock.forgotPin 2; then pva_tap pv.lock.forgotPin; fi
    pva_need pv.lock.password 20 && pva_type pv.lock.password "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    settle Passwords "$PIN_NOW" 263
}

part_keys() { pv_step "the Keystore keys dropped (debug command): the lock screen, the master password, the notices" -- keys_gone; }

# ---- empty: Start with an empty app, Old passwords -----------------------------------------------------------
start_empty() {
    local open del
    to_list && pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30 || return 1
    [ "$(pvl_screen)" = prompt ] && pvl_prompt_negative
    if pvl_wait_screen pad 5; then pva_tap pv.lock.forgotPin; fi
    pva_need pv.lock.password 20 && pva_hide_keyboard && pva_tap pv.lock.forgot && pva_need pv.lock.sheet 10 || return 1
    shot 270 forgot-sheet
    pva_need pv.lock.sheet.empty 10 || return 1
    pva_tap pv.lock.sheet.empty && pva_need pv.lock.confirmYes 10 || return 1
    shots 271 start-empty-question 3
    pva_tap pv.lock.confirmYes || return 1
    pva_set_up && pva_settle Passwords 120 || return 1
    settings && pva_reveal pv.open.OldPasswords && shot 272 settings-old-passwords-row || return 1
    pva_tap pv.open.OldPasswords && pva_on OldPasswords 20 || return 1
    shots 273 old-passwords 3
    open=$(first_id pv.backup.old.open.)
    if [ -n "$open" ]; then
        pva_tap "$open" && pv_sleep 2 && shot 274 old-passwords-open
        if pva_present pv.backup.cancel 2; then pva_tap pv.backup.cancel; else back; fi
        pv_sleep 1
    fi
    del=$(first_id pv.backup.old.delete.)
    if [ -n "$del" ]; then
        pva_tap "$del" && pv_sleep 2 && shot 275 old-passwords-delete
        if pva_present pv.backup.cancel 2; then pva_tap pv.backup.cancel; else back; fi
    fi
    PIN_NOW=none
}

part_empty() { pv_step "Forgot master password? > Start with an empty app; Old passwords" -- start_empty; }

# ---- restore: from Welcome -----------------------------------------------------------------------------------
restore() {
    [ -n "$BACKUP" ] || { pv_log "no backup made (the part files)"; return 1; }
    pv_shell pm clear "$PV_PACKAGE" >/dev/null 2>&1
    pv_shell cmd locale set-app-locales "$PV_PACKAGE" --locales "$LANG_TAG" >/dev/null 2>&1
    pva_start && pva_on Welcome 60 || return 1
    pva_tap pv.open.Restore && pva_on Restore 20 || return 1
    shots 280 restore-intro 3
    pva_need pv.backup.choose 20 && pva_tap pv.backup.choose && pva_picker_open "$BACKUP" || return 1
    pva_need pv.backup.password 30 && shot 281 restore-password || return 1
    pva_type pv.backup.password "not-the-password" && pva_hide_keyboard && pva_tap pv.backup.submit || return 1
    pv_sleep 4
    pva_hide_keyboard
    shot 282 restore-password-wrong
    pva_need pv.backup.password 10 || return 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    local i
    for i in $(seq 1 30); do pv_shell input keyevent KEYCODE_DEL >/dev/null; done
    pva_type pv.backup.password "$BACKUP_PASSWORD" && pva_hide_keyboard && pva_tap pv.backup.submit && pva_need pv.backup.restore 60 || return 1
    PVA_PASSWORD=$BACKUP_PASSWORD
    shots 283 restore-summary 3
    pva_tap pv.backup.restore && pva_unlocked Passwords 120 || return 1
    settle Passwords "$PIN1" 284
}

part_restore() { pv_step "a restore from Welcome: the intro, the master password, the summary" -- restore; }

echo "pictures of the real app in $LANG_TAG on $SERIAL, into $OUT"
part_setup || pvl_end
want pad && part_pad
want newmp && part_newmp
want files && part_files
want time && part_time
want keys && part_keys
want empty && part_empty
want restore && part_restore
clock_back
pv_shell cmd locale set-app-locales "$PV_PACKAGE" >/dev/null 2>&1
pvl_end
