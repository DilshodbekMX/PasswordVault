#!/usr/bin/env bash
# tools/emu/i18n/e11.sh - build plan 6.1, [script]: the language step of lock.md E11 on one private
# emulator, with the debug build: a language change in Settings with the lock delay "Immediately"
# keeps the app open on the same screen, and the list re-sorts.
#
#   tools/emu/i18n/e11.sh SERIAL [--apk FILE] [--shots DIR]
#
# The APK defaults to app/build/outputs/apk/debug/app-debug.apk (the debug command `entry add` makes
# the accounts, since adb cannot type Cyrillic). The app is driven by its resource ids
# (tools/emu/app.sh, i18n/lib.sh):
#   1. A fresh install, setup with the suggested words (the PIN left off), and four accounts: Bankomat,
#      Почта, Shahar and Zebra. In English the list is Bankomat, Shahar, Zebra, Почта.
#   2. Settings › "Lock the app after leaving it" › Immediately.
#   3. Settings › Language › Русский: the same process; Settings is shown again, not the lock screen,
#      with the language row where it was; the row now says Русский and the dialog opened again is
#      Russian. Passwords: Почта comes before Bankomat (Russian puts Cyrillic before Latin).
#   4. Settings › Language › Oʻzbekcha: from API 28 Zebra comes before Shahar (Uzbek sorts Sh after Z;
#      Android 7's ICU 56 has no Uzbek order and keeps Shahar first, which is printed).
#   5. Settings › Language › English: Bankomat before Почта, Shahar before Zebra again.
#   6. Settings › Language › System language.
#   7. The lock delay was in force all along: Home and back gives the lock screen at once; the master
#      password opens the app on Settings, where it was (ux.md 4), and the list has the accounts.
# and the app did not crash. Prints PASS, FAIL or SKIPPED per step and a summary; logs in
# build/emu-i18n/. Exit 0 when no step failed, 1 otherwise, 2 for a usage error, 3 for a refused
# serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"
source "$EMU/i18n/lib.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
SHOTS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

pv_use "$SERIAL"
pva_init
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
LOGS=$REPO/build/emu-i18n/$SERIAL
SHOTS=${SHOTS:-$LOGS/shots}
mkdir -p "$LOGS" "$SHOTS"
echo "lock.md E11, the language step, on $SERIAL (API $API): $APK"
pvi_quiet_network
PID=""

shot() { pva_screenshot "$SHOTS/$1.png" && pv_log "screenshot $SHOTS/$1.png"; }

# order_is A B: the list shows A above B (waiting up to 15 s for a new sort).
order_is() {
    local deadline=$((SECONDS + 15)) rows
    while :; do
        rows=$(pva_row_titles)
        printf '%s\n' "$rows" | awk -v a="$1" -v b="$2" '$0 == a { ia = NR } $0 == b { ib = NR } END { exit !(ia && ib && ia < ib) }' && {
            pv_log "rows: $(printf '%s' "$rows" | tr '\n' ';')"
            return 0
        }
        [ $SECONDS -lt $deadline ] || { pv_log "'$1' is not above '$2'; rows: $(printf '%s' "$rows" | tr '\n' ';')"; return 1; }
        pv_sleep 1
    done
}

set_up() {
    local t
    pva_install "$APK" && pva_start && pva_set_up || return 1
    for t in Bankomat Почта Shahar Zebra; do
        pva_debug entry add "$t" >/dev/null || return 1
    done
    pva_row_with Zebra 15 && PID=$(pva_pid) && [ -n "$PID" ] || return 1
    order_is Bankomat Shahar && order_is Shahar Zebra && order_is Zebra Почта
}

lock_immediately() {
    pva_tap pv.tab.Settings && pva_on Settings || return 1
    pva_tap pv.settings.lockDelay && pva_need pv.settings.lockDelay.IMMEDIATELY 10 || return 1
    pva_tap pv.settings.lockDelay.IMMEDIATELY || return 1
    pva_on Settings 10 && pva_absent pv.settings.lockDelay.IMMEDIATELY || { pv_log "the dialog did not close"; return 1; }
    if pv_is_debuggable; then
        pv_shell run-as "$PV_PACKAGE" cat shared_prefs/pv_settings.xml 2>/dev/null | tr -d '\r' | grep -q '>immediately<' \
            || { pv_log "pv_settings has no lock delay 'immediately'"; return 1; }
        pv_log "pv_settings: the lock delay is immediately"
    fi
}

# The texts of the language row: its title and the language it names.
language_row() {
    pv_ui_nodes | awk -F'|' '$3 == "pv.open.Language" { f = 1; next } f && $3 ~ /^pv[.]/ { exit } f && $1 != "" { print $1 }'
}

# change_in_settings CHOICE: Settings › Language › CHOICE; the same process; Settings, not the lock
# screen, with the language row still on the screen and naming CHOICE; the dialog again in CHOICE.
change_in_settings() {
    local choice=$1 row_y after texts
    pva_tap pv.tab.Settings && pva_on Settings || return 1
    pva_reveal pv.open.Language || return 1
    row_y=$(pva_node pv.open.Language | cut -d'|' -f7)
    pvi_open_language && pvi_choose "$choice" Settings || return 1
    pva_absent pv.route.Locked || { pv_log "the lock screen is shown"; return 1; }
    after=$(pva_pid)
    [ "$after" = "$PID" ] || { pv_log "the process changed: $PID -> $after"; return 1; }
    pva_present pv.open.Language 3 || { pv_log "the language row is no longer on the screen"; return 1; }
    pv_log "the language row at y $row_y before, $(pva_node pv.open.Language | cut -d'|' -f7) after"
    texts=$(language_row)
    pv_log "the language row: $(printf '%s' "$texts" | tr '\n' ';')"
    case "$choice" in
        SYSTEM) printf '%s\n' "$texts" | grep -qx 'System language' || { pv_log "the row does not say System language"; return 1; } ;;
        *) printf '%s\n' "$texts" | grep -qxF "$(pvi_own_name "$choice")" || { pv_log "the row does not name $(pvi_own_name "$choice")"; return 1; } ;;
    esac
    shot "e11-settings-$choice"
    pvi_open_language || return 1
    pvi_dialog_is "$choice"
    local rc=$?
    pvi_cancel Settings || rc=1
    return $rc
}

list_shows() { # NAME A B [C D]: Passwords shows A above B (and C above D); a screenshot NAME
    local name=$1
    shift
    pva_tap pv.tab.Passwords && pva_on Passwords || return 1
    order_is "$1" "$2" || return 1
    [ $# -lt 4 ] || order_is "$3" "$4" || return 1
    shot "e11-list-$name"
}

uzbek_order() {
    pva_tap pv.tab.Passwords && pva_on Passwords || return 1
    if [ "$API" -ge 28 ]; then
        order_is Zebra Shahar && order_is Bankomat Почта
    else
        order_is Bankomat Почта || return 1
        pv_log "API $API (ICU 56) has no Uzbek order: $(pva_row_titles | tr '\n' ';')"
    fi
    shot "e11-list-UZ_LATN"
}

# The unlock brings back the screen the app was on, Settings (ux.md 4, "What survives a lock"; since the
# merge of R4 no "Make backups" follows an unlock, decision 24), and the list then has the accounts.
still_in_force() {
    pva_away 2 && pva_on Locked 20 || { pv_log "not locked after leaving with Immediately"; return 1; }
    pva_unlock Settings && pva_tap pv.tab.Passwords && pva_on Passwords && pva_row_with Bankomat 10
}

pv_step "1. fresh install, setup, four accounts; in English: Bankomat, Shahar, Zebra, Почта" -- set_up || { pv_summary; exit 1; }
pv_step "2. Settings: lock the app after leaving it: Immediately" -- lock_immediately
pv_step "3. Settings › Language › Русский: still open, the same process and screen, in Russian" -- change_in_settings RU
pv_step "3. the list re-sorts: Почта before Bankomat" -- list_shows RU Почта Bankomat
pv_step "4. Settings › Language › Oʻzbekcha: still open, the same process and screen" -- change_in_settings UZ_LATN
pv_step "4. the list re-sorts in Uzbek (Sh after Z from API 28)" -- uzbek_order
pv_step "5. Settings › Language › English: still open, the same process and screen" -- change_in_settings EN
pv_step "5. the list re-sorts: Bankomat before Почта, Shahar before Zebra" -- list_shows EN Bankomat Почта Shahar Zebra
pv_step "6. Settings › Language › System language" -- change_in_settings SYSTEM
pv_step "7. Immediately was in force: Home and back gives the lock screen; the master password opens the app on Settings, where it was; the list has the accounts" -- still_in_force
pv_step "the app did not crash" -- pva_no_crash
pv_summary
