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
# SERIAL is an API 37 device of this worktree (the system's own prompt, cmd alarm set-time),
# or for the parts setup and pad an API 24 device (androidx.biometric's own dialog in the activity; the
# language set with the debug command `locale`); FILE a debug APK (screenshots, run-as). TAG is the app's
# own language tag (en, uz, uz-Cyrl, ru), set with the debug command `locale` once the app runs on Welcome
# (set_language says why not before the first start). The pictures go to DIR as
# NNN-name.png; the numbers are fixed, so that a picture of one state has the same name in every
# language. A picture of a screen that scrolls is taken once per swipe while the screen changes
# (NNN-name-1, -2, ...).
#
# The parts, in order (--only runs some of them after the set-up; each starts from the list or comes back
# to it, and a part that fails is reported and the next one starts):
#   setup      a fresh install; setup with a PIN refused, then chosen; fingerprint unlock turned on
#              through the system's prompt; an account through the form.
#   deleted    an account deleted from its screen; Recently deleted, a row's menu, "Delete forever?".
#   pad        Lock now: the prompt opens by itself; "Use PIN"; wrong PINs: the pad's lines (tries left,
#              the same PIN again, the last try), the fifth: the PIN off, the fingerprint-only screen;
#              "Use master password", a wrong one, the right one: the notices and "Choose a new PIN".
#   newmp      a new master password (lock.md F11): "I don't remember it", the explanation, the PIN, the
#              choice, the wait, the list's banner; the next unlock; made ready (newmp.sh's debug
#              shortcut): the notice, "Finish now", the finishing step, the backup step; then the old
#              master password typed on the lock screen.
#   files      the import (sources, Google's help, a crafted CSV's preview with its counts and result,
#              the fifty accounts), the Security tab with them (cards, a group, a row's menu, "Leave out",
#              a guided change to its end, the next one left for later), the account with a change
#              waiting, the backup, the
#              exports (the warnings, "Delete it now", one CSV kept: the list's banners), Settings' dialogs,
#              Recently deleted's "Delete forever".
#   time       the clock moved on (cmd alarm set-time; put back at the end): the practice screen within
#              its grace and past it, "I can't remember it" (the PIN, the explanation, Cancel), the list's
#              overdue banner and its practice dialog, the backup banner and "Make a backup now?".
#   keys       fingerprint and PIN keys gone (the debug command drop-keys): the lock screen's lines, the
#              master password, the notices ("Turn fingerprint unlock on again?", "Choose a new PIN").
#   empty      a fresh install set up without a PIN: "Forgot master password?" with "Start with an empty
#              app", the question, Welcome with "Open my previous passwords", a new vault, Settings' "Old
#              passwords" and its dialogs.
#   restore    a restore from Welcome with the backup of the part files: the intro, the master password,
#              the summary.
#   faults     only when named (--only faults; right after the set-up, while fingerprint unlock is on):
#              the PIN pad's lines when the fingerprint fails (the debug command ks-fault: locked for now,
#              not usable in apps, can't be checked, did not open the app, turned off), each then the PIN.
#   extra      only when named (--only extra; after the set-up, or after faults): an account restored in
#              Recently deleted, then deleted again and "Empty" confirmed; Settings > Change PIN with a
#              date refused, the current PIN refused, a save that fails (the debug command fail-next-write)
#              and Retry, then "Your PIN was changed.".
#   more       the PIN's record unreadable for a new process: the lock screen and the "Forgot master
#              password?" sheet; then a fresh install's setup: the suggested words, a password of one's own
#              (too short, a keyboard row, what is missing, the reuse question answered "Yes"), the words in
#              big letters, "Setup was interrupted", a mismatch on "Type it again" with "Show my password
#              again" (setup left unfinished).
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

# shot N NAME [WAIT]: the screen as OUT/N-NAME.png after WAIT seconds (default 1). The system's
# fingerprint prompt (API 28 and later) is a secure window, which screencap gives black: a picture that
# is black throughout is taken again with the emulator's own screenshot (adb emu screenrecord
# screenshot DIR, which writes on this computer; tools/emu/README.md, "Device facts"). The texts of the
# window in front (pv_ui_nodes: text|description|id|...) go beside it as OUT/N-NAME.nodes.txt, read
# right after the picture, so that the set's check can tell which letters each picture shows
# (6-pictures.sh: no Cyrillic in Oʻzbekcha, no Latin words in Ўзбекча beyond names).
shot() {
    local f=$OUT/$1-$2.png dir newest
    pv_sleep "${3:-1}"
    pva_screenshot "$f" >/dev/null 2>&1 || return 0
    if python3 -c 'import sys
from PIL import Image
sys.exit(0 if Image.open(sys.argv[1]).convert("L").getextrema()[1] < 8 else 1)' "$f" 2>/dev/null; then
        dir=$(mktemp -d "${TMPDIR:-/tmp}/pv-emushot.XXXXXX") || return 0
        pv_adb emu screenrecord screenshot "$dir" >/dev/null 2>&1
        newest=$(ls -t "$dir"/*.png 2>/dev/null | head -n 1)
        if [ -n "$newest" ]; then mv -f "$newest" "$f"; echo "SHOT $1-$2 (the emulator's screenshot: a secure window)"; fi
        rm -rf "$dir"
        pv_ui_nodes > "$OUT/$1-$2.nodes.txt" 2>/dev/null
        return 0
    fi
    pv_ui_nodes > "$OUT/$1-$2.nodes.txt" 2>/dev/null
    echo "SHOT $1-$2"
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
# answered "OK" or "Later"), of "Choose a PIN" (N-create-pin, then PIN twice; "Not now" when PIN is "-"
# or "none"), the fingerprint offer "Not now", "Make backups" "Finish", a prompt closed with its negative
# button.
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
            case "$pin" in
                - | none) pva_tap pv.pin.notNow || return 1 ;;
                *) pva_pin "$pin" || return 1 ;;
            esac
            pv_sleep 1
        elif pva_in_nodes '[|]pv\.route\.ConfirmPin$' "$nodes"; then
            case "$pin" in
                - | none) back ;;
                *) pva_pin "$pin" || return 1 ;;
            esac
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
# set_language: the app's language set as the language dialog sets it (the debug command `locale`,
# AppLanguages.setTags) on Welcome, once the app runs, and checked: the command answers which language
# the screens show. Set from outside before the first start of a fresh install (cmd locale
# set-app-locales), the language was lost: at that start AppCompat moves its own stored language into
# Android's (autoStoreLocales), and a fresh install has none, so the first uz-Latn session came out in
# English.
set_language() {
    local answer want
    case "$LANG_TAG" in en) want=EN ;; uz) want=UZ_LATN ;; uz-Cyrl) want=UZ_CYRL ;; ru) want=RU ;; *) want="" ;; esac
    pva_on Welcome 30 || return 1
    answer=$(pva_debug "locale $LANG_TAG") || return 1
    pv_log "$answer"
    [ -z "$want" ] || [[ "$answer" == *"shown: $want"* ]] || { pv_log "the app does not show $want"; return 1; }
    pv_sleep 3
    pva_on Welcome 30
}

start_in_language() {
    pva_install "$APK" && pva_start && set_language
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
}

part_deleted() { pv_step "an account deleted; Recently deleted, Delete forever, Empty" -- delete_forever; }

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
    # "Choose a new PIN" answered "Not now" first, so that Settings' PIN row says why the PIN is off (R6's
    # open problem: not seen on a device in Uzbek); then Lock now, the master password from the
    # fingerprint-only screen, and the new PIN when "Choose a PIN" comes again.
    settle Passwords - 120 || return 1
    settings && shots 121 settings-pin-off 3
    to_list && pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30 || return 1
    if [ "$(pvl_screen)" = prompt ]; then pvl_prompt_negative; pv_sleep 1; fi
    if pva_present pv.lock.useMasterPassword 3; then pva_tap pv.lock.useMasterPassword; fi
    pva_need pv.lock.password 20 && pva_type pv.lock.password "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.lock.unlock || return 1
    pva_unlocked Passwords 90 || return 1
    settle Passwords "$PIN2" 122 && PIN_NOW=$PIN2
}

part_pad() {
    pv_step "Lock now: the prompt opens by itself; Use PIN: the pad" -- lock_with_prompt || return 1
    shot 111 pad
    pv_step "a wrong PIN" -- pvl_wrong_pins 1 0 && shot 112 pad-wrong-1
    pv_step "the same wrong PIN again" -- eval 'pvl_pad 20 && pva_pin "${PVL_WRONG[0]}" && pvl_wait_answer' && shot 113 pad-same-again
    pv_step "two more wrong PINs" -- pvl_wrong_pins 2 1 && shot 114 pad-wrong-3
    pv_step "a fourth wrong PIN: the last try" -- pvl_wrong_pins 1 3 && shot 115 pad-last-try
    pv_step "the fifth wrong PIN turns the PIN off: the fingerprint-only screen" -- fifth_pin
    pv_step "Use master password; a wrong one; the right one: the notices, \"Choose a new PIN\" Not now, Settings' PIN row; the new PIN at the next unlock" -- master_after_pin_off
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
    # Step 5's "Yes, it's changed": the end of the change and its "Next"; the next account's change is
    # created and left for later (the list's and the account's banners).
    pva_reveal pv.change.yes && pva_tap pv.change.yes || return 1
    pv_sleep 2
    shots 216 change-end 4
    pva_reveal pv.change.next && pva_tap pv.change.next || return 1
    pva_on GuidedChange 20 && pva_reveal pv.change.create && pva_tap pv.change.create && pva_need pv.change.saved 20 || return 1
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
# accounts, a row's menu, "Delete forever?" and "Empty" with its question. A dialog's buttons show no
# resource id (its window is a root of its own without testTagsAsResourceId), so its picture is taken
# once a dialog's window is in front.
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
    [ -n "$item" ] && pv_shell input tap $item >/dev/null || return 1
    shot 108 recently-deleted-forever-question 1.5
    back
    pva_on RecentlyDeleted 10 && pva_tap pv.deleted.empty || return 1
    shot 109 recently-deleted-empty-question 1.5
    back
    to_list
}

part_files() {
    pv_step "the import's files pushed to Download" -- push_files || return 1
    pv_step "import: Google's help, a CSV's preview and its result" -- import_screens
    pv_step "import: the fifty accounts" -- import_fifty
    pv_step "Security: the cards, a group, a row's menu, Leave out, a guided change to its end, the next one left for later" -- security
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
    # A new master password needs the PIN first (lock.md F11 step 3): the pad, then the explanation.
    if pvl_wait_screen pad 10; then
        shot 253 practice-cant-remember-pin
        pva_pin "$PIN_NOW" && pvl_wait_answer 20
    fi
    local deadline=$((SECONDS + 60))
    until pva_present pv.master.explain 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "no explanation of a new master password; ids: $(pva_ids)"; return 1; }
        if pva_present pv.notice 1; then NOTICES=$((NOTICES + 1)); shot 253 "notice-$NOTICES"; notice_answer; fi
    done
    shots 253 practice-cant-remember-explain 4
    # Leave the new master password's steps: Cancel, then the list with the overdue banner.
    if pva_present pv.master.cancel 3; then pva_tap pv.master.cancel; else back; fi
    pv_sleep 2
    local i
    for i in 1 2 3 4; do
        pva_present pv.route.Passwords 2 && break
        if pva_present pv.tab.Passwords 1; then pva_tap pv.tab.Passwords; else back; fi
    done
    pva_on Passwords 20 || return 1
    # The list shows its banners by importance: the replaced master password's and the kept export's
    # come before practice's, so they are answered first ("OK", "I deleted it myself").
    if pva_present pv.list.banner.replaced.ok 2; then pva_tap pv.list.banner.replaced.ok; pv_sleep 1; fi
    if pva_present pv.list.banner.export.deletedMyself 2; then pva_tap pv.list.banner.export.deletedMyself; pv_sleep 1; fi
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
    # "Choose a PIN" answered "Not now": with no PIN and no fingerprint the "Forgot master password?"
    # sheet offers "Start with an empty app" (the part empty).
    settle Passwords "$PIN_NOW" 263
}

part_keys() { pv_step "the Keystore keys dropped (debug command): the lock screen, the master password, the notices" -- keys_gone; }

# ---- empty: Start with an empty app, Old passwords -----------------------------------------------------------
start_empty() {
    local open del
    # A vault without a PIN and without fingerprint unlock: only then does the sheet offer an empty app.
    pv_shell pm clear "$PV_PACKAGE" >/dev/null 2>&1
    pva_start && set_language || return 1
    pva_set_up && pva_settle Passwords 120 || return 1
    PIN_NOW=none
    pva_add_account Olx aziz olx-Samarqand1999 olx.uz 'Old account' && to_list || return 1
    pva_tap pv.list.lockNow && pva_need pv.lock.password 30 || return 1
    pva_hide_keyboard
    shot 269 master-password-no-pin
    pva_tap pv.lock.forgot && pva_need pv.lock.sheet 10 || return 1
    # One picture: a swipe would drag the sheet down and close it.
    shot 270 forgot-sheet-no-shortcut
    pva_need pv.lock.sheet.empty 10 || return 1
    pva_tap pv.lock.sheet.empty && pva_need pv.lock.confirmYes 10 || return 1
    shots 271 start-empty-question 3
    pva_tap pv.lock.confirmYes || return 1
    # Welcome again, now with "Open my previous passwords" for the passwords put aside.
    pva_on Welcome 60 && pv_sleep 1 && shot 271 welcome-previous-passwords
    pva_set_up && pva_settle Passwords 120 || return 1
    PIN_NOW=none
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
}

part_empty() { pv_step "Forgot master password? > Start with an empty app; Old passwords" -- start_empty; }

# ---- restore: from Welcome -----------------------------------------------------------------------------------
restore() {
    [ -n "$BACKUP" ] || { pv_log "no backup made (the part files)"; return 1; }
    pv_shell pm clear "$PV_PACKAGE" >/dev/null 2>&1
    pva_start && set_language || return 1
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
    settle Passwords "$PIN1" 284 && PIN_NOW=$PIN1
}

part_restore() { pv_step "a restore from Welcome: the intro, the master password, the summary" -- restore; }

# ---- faults: the fingerprint's lines on the PIN pad (only when named: --only faults) -----------------------
# fault_line N NAME ROW TOUCH: from the list, with fingerprint unlock on, the debug command `ks-fault ROW`
# armed, Lock now: the prompt ends at once with the error code ROW names, or (TOUCH yes) the finger is
# accepted and the fault strikes at its result; the pad's line is pictured; then the PIN (lock.md §14.3; the
# rows and their lines as lock/E10.sh checks them).
fault_line() {
    local n=$1 name=$2 row=$3 touch=$4
    to_list || return 1
    pva_debug "ks-fault $row" >/dev/null || return 1
    pva_tap pv.list.lockNow || return 1
    if [ "$touch" = yes ]; then
        pvl_touch_prompt "$PVL_FINGER" 20 || return 1
    else
        pv_sleep 3
    fi
    pvl_prompt_down 15
    pvl_wait_screen pad 20 || return 1
    pv_sleep 1
    shot "$n" "$name"
    pva_debug "ks-fault none" >/dev/null
    pva_pin "$PIN_NOW" && pvl_wait_answer 20
    settle Passwords "$PIN_NOW" "$n"
}

part_faults() {
    pv_step "fingerprint locked for now (prompt error 7): the pad's line" -- fault_line 290 pad-fingerprint-locked prompt-error-7 no
    pv_step "fingerprint can't be used in apps (prompt error 1): the pad's line" -- fault_line 291 pad-fingerprint-not-for-apps prompt-error-1 no
    pv_step "fingerprint can't be checked (prompt error 11): the pad's line" -- fault_line 292 pad-fingerprint-cannot-check prompt-error-11 no
    pv_step "the finger accepted, the app not opened (prompt-no-cipher): the pad's line" -- fault_line 293 pad-fingerprint-did-not-open prompt-no-cipher yes
    pv_step "fingerprint unlock turned off, its key no longer works (fp-dofinal-bad-tag): the pad's line" -- fault_line 294 pad-fingerprint-turned-off fp-dofinal-bad-tag yes
}

# ---- extra: Recently deleted's restore and its empty list; Change PIN's lines (only when named) ------------
# dialog_confirm: the confirming button of the dialog in front (its buttons have no resource id): the
# clickable node lowest and furthest right.
dialog_confirm() {
    local xy
    xy=$(pv_ui_nodes | awk -F'|' '$4 == "true" { print $6, $7 }' | sort -k2,2n -k1,1n | tail -n 1)
    [ -n "$xy" ] || return 1
    pv_shell input tap $xy >/dev/null
    pv_sleep 1
}

# An account deleted and restored ("Olx restored"), deleted again; "Empty" confirmed: nothing is there.
deleted_more() {
    local id
    to_list && pva_add_account Olx aziz olx-Samarqand1999 olx.uz 'Old account' || return 1
    pva_tap pv.entry.more && pva_need pv.entry.menu.delete 10 && pva_tap pv.entry.menu.delete || return 1
    to_list && pva_reveal pv.list.recentlyDeleted && pva_tap pv.list.recentlyDeleted && pva_on RecentlyDeleted 20 || return 1
    id=$(first_id pv.deleted.restore.)
    [ -n "$id" ] || { pv_log "no Restore; ids: $(pva_ids)"; return 1; }
    pva_tap "$id" && pv_sleep 1 && shot 320 recently-deleted-restored
    to_list || return 1
    # The row whose first text is Olx (pva_row_titles reads the rows so).
    id=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { id = $3; pending = 1; next } pending && $3 ~ /^pv[.]/ { pending = 0 } pending && $1 != "" { if ($1 == "Olx") { print id; exit } pending = 0 }')
    [ -n "$id" ] || { pv_log "no row of Olx; ids: $(pva_ids)"; return 1; }
    pva_tap "$id" && pva_need pv.entry.more 10 && pva_tap pv.entry.more && pva_need pv.entry.menu.delete 10 && pva_tap pv.entry.menu.delete || return 1
    to_list && pva_reveal pv.list.recentlyDeleted && pva_tap pv.list.recentlyDeleted && pva_on RecentlyDeleted 20 || return 1
    pva_tap pv.deleted.empty && pv_sleep 1.5 && dialog_confirm || return 1
    pva_need pv.deleted.nothing 10 && shot 321 recently-deleted-nothing
    to_list
}

# Settings > Change PIN: the current PIN; a new PIN that looks like a date; the current one again; a new
# PIN whose saving fails (the debug command fail-next-write): "Couldn't save the new PIN", Retry; then
# the new PIN twice: Settings says "Your PIN was changed.".
change_pin_more() {
    local new=581930
    settings && pva_reveal pv.open.ChangePin && pva_tap pv.open.ChangePin && pva_on ChangePin 20 || return 1
    pva_need pv.pin.key.0 20 && pva_pin "$PIN_NOW" || return 1
    pv_sleep 2
    pva_pin 150387 && pv_sleep 1.5 && shot 325 change-pin-date
    pva_pin "$PIN_NOW" && pv_sleep 1.5 && shot 326 change-pin-same-as-current
    pva_pin "$new" && pv_sleep 1.5 || return 1
    pva_debug fail-next-write >/dev/null || return 1
    pva_pin "$new" && pv_sleep 3 || return 1
    if pva_present pv.pin.retry 5; then
        shot 327 change-pin-not-saved
        pva_tap pv.pin.retry && pv_sleep 2
        pva_need pv.pin.key.0 10 && pva_pin "$new" && pv_sleep 1.5 && pva_pin "$new" && pv_sleep 3 || return 1
    else
        pv_log "no Retry after the failed write; ids: $(pva_ids)"
    fi
    PIN_NOW=$new
    pva_on Settings 20 && shot 328 settings-pin-changed 0.5
    return 0
}

part_extra() {
    pv_step "Recently deleted: an account restored, then deleted again and Empty confirmed" -- deleted_more
    pv_step "Change PIN: a date, the current PIN, a failed save and Retry, the PIN changed" -- change_pin_more
}

# ---- more: the PIN's record unreadable; setup's own password and its questions; setup interrupted ----------
# retype ID TEXT: the field's text replaced with TEXT.
retype() {
    local i
    pva_tap "$1" || return 1
    pv_sleep 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    for i in $(seq 1 45); do pv_shell input keyevent KEYCODE_DEL >/dev/null; done
    pv_shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" >/dev/null
    pv_sleep 1
}

# The PIN's record unreadable for a new process (run-as chmod 000, as checks/5.sh line 7 does; the state
# of a phone whose storage failed for a moment, lock.md F15c): the lock screen's line, and the "Forgot
# master password?" sheet with 8.1's sentence that the PIN can't be checked now. Then the record is
# readable again and the PIN opens the app.
pin_cannot_check() {
    local vault mode rc=0
    to_list && pvl_status || return 1
    vault=$(pvl_vault_id)
    [ -n "$vault" ] || { pv_log "no current vault in the status"; return 1; }
    mode=$(pv_shell "run-as $PV_PACKAGE stat -c %a no_backup/lock/$vault/pin.rec" 2>/dev/null | tr -d '\r')
    [ -n "$mode" ] || { pv_log "no pin.rec for $vault"; return 1; }
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pv_shell "run-as $PV_PACKAGE chmod 000 no_backup/lock/$vault/pin.rec" >/dev/null 2>&1 || return 1
    if pva_start && pvl_wait_screen 'pad|prompt|fingerprint-only|master-password' 30; then
        if [ "$(pvl_screen)" = prompt ]; then pvl_prompt_negative; pv_sleep 2; fi
        if pva_present pv.lock.useMasterPassword 2; then pva_tap pv.lock.useMasterPassword; fi
        if pva_need pv.lock.password 20; then
            pva_hide_keyboard
            shot 300 pin-cannot-check-lock
            if pva_tap pv.lock.forgot && pva_need pv.lock.sheet 10; then shot 301 pin-cannot-check-sheet; else rc=1; fi
            back
        else
            rc=1
        fi
    else
        rc=1
    fi
    pv_shell "run-as $PV_PACKAGE chmod $mode no_backup/lock/$vault/pin.rec" >/dev/null 2>&1
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pva_start && to_list || rc=1
    return $rc
}

# Setup on a fresh install, the states the walk passes by: every part of the suggested words; a password
# of one's own too short, a keyboard row, what is missing when "Use this password" is pressed, the
# question whether it is used elsewhere and the answer "Yes"; the words in big letters on "Write it down";
# the app's process ended in the background there and the app opened again ("Setup was interrupted"); a
# mismatch on "Type it again" and "Show my password again". Setup is left unfinished.
setup_more() {
    pv_shell pm clear "$PV_PACKAGE" >/dev/null 2>&1
    PIN_NOW=none
    pva_start && set_language || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword 20 || return 1
    pv_sleep 1
    shots 310 setup-words 3
    pva_reveal pv.setup.typeOwn && pva_tap pv.setup.typeOwn && pva_need pv.setup.own 10 || return 1
    pva_type pv.setup.own "abc" && pva_hide_keyboard && shots 311 setup-own-short 3
    retype pv.setup.own "qwertyuiopasdfgh" && pva_hide_keyboard && shots 312 setup-own-keyboard-row 3
    pva_reveal pv.setup.useOwn && pva_tap pv.setup.useOwn && pv_sleep 1 && pva_hide_keyboard && shots 313 setup-own-not-yet 3
    retype pv.setup.own "Lochin-Daryo-Bahor-Kitob-Gilam-Yulduz" && pva_hide_keyboard || return 1
    pva_reveal pv.setup.useOwn && pva_tap pv.setup.useOwn || return 1
    pva_need pv.setup.reuse.no 10 || { pv_log "no reuse question; ids: $(pva_ids)"; return 1; }
    shot 314 setup-own-reuse-question
    pva_tap pv.setup.reuse.yes && pv_sleep 1 && pva_hide_keyboard && shots 315 setup-own-reuse-refused 3
    pva_reveal pv.setup.showWords && pva_tap pv.setup.showWords && pva_need pv.open.WriteDown 10 || return 1
    pva_tap pv.open.WriteDown && pva_on WriteDown 20 || return 1
    if pva_reveal pv.setup.bigLetters && pva_tap pv.setup.bigLetters; then pv_sleep 1; shot 316 setup-write-down-big-letters; back; fi
    # The process ended in the background (Home, then killed) and the app opened again from Recents: the
    # words are not kept, so setup starts again at step 1 (ux.md §5.5; shell/walk.sh's first step).
    pva_kill_in_background && pva_relaunch && pva_on CreateMasterPassword 30 && pva_need pv.setup.interrupted 10 || return 1
    shots 317 setup-interrupted 3
    pva_tap pv.open.WriteDown && pva_on WriteDown 20 && pva_tap pv.open.TypeAgain && pva_on TypeAgain 20 || return 1
    pva_type pv.setup.again "not-the-words" && pva_hide_keyboard && pva_tap pv.action.createVault || return 1
    pv_sleep 2
    pva_hide_keyboard
    shots 318 setup-again-mismatch 3
    if pva_reveal pv.setup.showAgain && pva_tap pv.setup.showAgain; then pv_sleep 1; pva_hide_keyboard; shots 319 setup-again-shown 3; fi
    return 0
}

part_more() {
    pv_step "the PIN's record unreadable for a new process: the lock screen and the sheet say the PIN can't be checked" -- pin_cannot_check
    pv_step "setup again: the words, an own password (too short, a keyboard row, what is missing, the reuse question), big letters, setup interrupted, a mismatch" -- setup_more
}

echo "pictures of the real app in $LANG_TAG on $SERIAL, into $OUT"
part_setup || pvl_end
[[ ",$ONLY," == *",faults,"* ]] && part_faults
[[ ",$ONLY," == *",extra,"* ]] && part_extra
want deleted && part_deleted
want pad && part_pad
want newmp && part_newmp
want files && part_files
want keys && part_keys
want time && part_time
want empty && part_empty
want restore && part_restore
want more && part_more
clock_back
pv_shell cmd locale set-app-locales "$PV_PACKAGE" >/dev/null 2>&1
pvl_end
