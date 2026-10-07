#!/usr/bin/env bash
# tools/emu/shell/walk.sh - build plan 2.5, [script]: the app starts and walks every route of the
# app's shell.
#
#   tools/emu/shell/walk.sh SERIAL [--apk FILE] [--locale TAG]
#       SERIAL comes from tools/emu/boot.sh. The APK defaults to this worktree's
#       app/build/outputs/apk/debug/app-debug.apk (tools/gradle.sh :app:assembleDebug).
#       --locale TAG sets the app's language once the app is open on Welcome (for example en-XA,
#       the pseudo-locale of build plan rule 5): the activity is recreated on the same route, and
#       the shell's own texts must then not be English. It needs Android 13 or newer (cmd locale)
#       and is SKIPPED below. (Set before the first start, the language is reset: AppCompat copies
#       its own empty record into the system once, at the first start, i18n.md §1.4.3.)
#
# The walk finds screens by the resource ids the app gives them (architecture.md §3.6):
# pv.route.<Route> on each screen, pv.open.<Route> on the buttons that open a route (a placeholder's,
# and the setup screens'), pv.action.* for the few that do something else, pv.tab.* on the bottom
# bar, and the ids of the real screens of sub-wave 3A (pv.setup.*, pv.lock.*, pv.list.*, pv.edit.*,
# through tools/emu/app.sh). So it does not depend on the language. Since sub-wave 3A the setup
# screens, the master-password lock screen, the list and the form are real and the lock is the
# master-password gate over the real vault; the other routes are still placeholders. It installs the
# APK afresh, then:
#   a setup step left by a new process (Home, am kill, or kill -9 through run-as or su; relaunch:
#   the words are not kept, so setup starts again at step 1 with "Setup was interrupted", ux.md §5.5;
#   SKIPPED where nothing ends the process) ->
#   setup (Welcome, Language, Restore, the three steps with the suggested words typed again, the
#   vault made) -> Passwords: an account added through the form, its screen, big letters, its form;
#   a second account, the first deleted through its row's menu, Recently deleted -> Security and
#   below it -> Settings and every route it opens -> the PIN setup steps, opened from App lock ->
#   Lock now (no bottom bar) -> the master password (the same route comes back) -> a rotation keeps
#   the route -> Back leaves the app.
# Every route key of nav/Route.kt except Starting must have been shown, and the app must not have
# crashed. Prints PASS or FAIL per step and a summary; exit 0 when no step failed, 1 otherwise,
# 2 for a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
LOCALE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --locale) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LOCALE=$2; shift ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

pv_use "$SERIAL"
pva_init
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
echo "walk.sh on $SERIAL (API $API)${LOCALE:+, locale $LOCALE}: $APK"

# Every route key of nav/Route.kt (data objects and data classes nested in Route).
ROUTES=$(sed -n 's/^ *data \(object\|class\) \([A-Za-z]*\)\b.*: Route.*/\2/p' "$REPO/app/src/main/java/io/github/dilshodbekmx/passwordvault/nav/Route.kt" | sort -u)
SEEN=""

# ---- helpers ----------------------------------------------------------------------------------
re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }

# on ROUTE [TEXT-REGEX]: the screen of ROUTE is shown (and, with TEXT-REGEX, a text matching it).
on() {
    pv_ui_wait "[|]pv\.route\.$1\$" 20 || { pv_log "not on $1; nodes: $(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }')"; return 1; }
    if [ -n "${2:-}" ]; then pv_ui_wait "$2" 10 || { pv_log "no text matching $2 on $1"; return 1; }; fi
    case " $SEEN " in *" $1 "*) ;; *) SEEN="$SEEN $1" ;; esac
}

# The screen's height in pixels, and the bottom band where the bottom bar and the system's
# navigation bar are: a node of a screen whose centre is there is scrolled up before a tap.
SCREEN_H=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
SCREEN_H=${SCREEN_H:-1920}
SAFE_BOTTOM=$((SCREEN_H - 420))

# tap ID: taps the node whose resource id is ID, after scrolling it onto the screen (a real screen's
# button can be below the fold: "Use these words" on a 1920-pixel screen) and above the bottom band.
tap() {
    local id_re="[|]$(re "$1")\$" nodes line="" y=0 i bottom
    for i in 1 2 3 4 5 6; do
        nodes=$(pv_ui_nodes)
        line=$(printf '%s\n' "$nodes" | pv_ui_match "$id_re")
        if [ -n "$line" ]; then
            y=$(echo "$line" | cut -d'|' -f7)
            case "$1" in pv.tab.*) y=0 ;; esac
            # Without the app's bottom bar (setup, the lock) only the system's navigation bar is there.
            bottom=$((SCREEN_H - 160))
            printf '%s\n' "$nodes" | grep -q '[|]pv[.]tab[.]' && bottom=$SAFE_BOTTOM
            if [ "$y" -lt "$bottom" ]; then
                pv_ui_tap "$id_re" "$nodes"
                return
            fi
        fi
        [ "$i" -lt 6 ] || break
        pv_shell input swipe 540 $((SCREEN_H * 6 / 10)) 540 $((SCREEN_H * 3 / 10)) 400 >/dev/null
        pv_sleep 1
    done
    if [ -n "$line" ]; then
        pv_log "the node $1 stays in the bottom band (y $y of $SCREEN_H)"
    else
        pv_log "no node with id $1"
    fi
    return 1
}

# open ROUTE [TEXT-REGEX]: taps "Open ROUTE" and waits for its screen.
open() { tap "pv.open.$1" && on "$@"; }

# back ROUTE: system Back, then ROUTE is shown. An open keyboard is closed first: it would take the
# Back.
back() { pva_hide_keyboard; pv_shell input keyevent KEYCODE_BACK >/dev/null && on "$1"; }

absent() { # ID: no node has this resource id
    ! pv_ui_nodes | pv_ui_match "[|]$(re "$1")\$" | grep -q .
}

present() { # ID: a node has this resource id
    pv_ui_wait "[|]$(re "$1")\$" 10
}

# ---- the walk -----------------------------------------------------------------------------------
install_fresh() {
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assembleDebug"; return 1; }
    pva_install "$APK"
}

set_locale() {
    on Welcome || return 1
    pv_shell cmd locale set-app-locales "$PV_PACKAGE" --locales "$LOCALE" >/dev/null || return 1
    pv_sleep 3
    pv_shell cmd locale get-app-locales "$PV_PACKAGE" | tr -d '\r' | grep -q "\[$LOCALE\]" \
        || { pv_log "the app's locales are not [$LOCALE]: $(pv_shell cmd locale get-app-locales "$PV_PACKAGE")"; return 1; }
    on Welcome
}

# The shell's own texts (the bottom bar's labels) are not English under --locale.
shell_texts_localized() {
    local english
    english=$(pv_ui_nodes | awk -F'|' '$1 == "Passwords" || $1 == "Security" || $1 == "Settings"')
    [ -z "$english" ] || { pv_log "English labels under $LOCALE: $english"; return 1; }
    present pv.tab.Passwords
}

app_pid() {
    local p
    p=$(pv_shell pidof "$PV_PACKAGE" 2>/dev/null | tr -d '\r')
    [ -n "$p" ] || p=$( { pv_shell ps -A 2>/dev/null || pv_shell ps; } | tr -d '\r' | awk -v p="$PV_PACKAGE" '$NF == p { print $2; exit }')
    echo "$p"
}

# Home, then the app's process ends (tools/emu/app.sh: am kill, or kill -9 through run-as or su where
# am kill is refused, architecture.md §8.4): true when the process is gone.
kill_in_background() { pva_kill_in_background; }

# The app is started again from the launcher's intent: the task's activity comes back.
relaunch_on() {
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
    on "$1"
}

start() {
    pv_unlock >/dev/null 2>&1
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
}

# The suggested words, read on step 1 and typed again on step 3: the master password from then on.
setup_routes() {
    on Welcome && absent pv.tab.Passwords \
        && open Language && back Welcome \
        && open Restore && back Welcome \
        && open CreateMasterPassword && pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    open WriteDown && open TypeAgain \
        && back WriteDown && open TypeAgain \
        && pva_type pv.setup.again "$PVA_PASSWORD" \
        && pva_submit pv.action.createVault pv.route.Passwords 120 && on Passwords
}

# The list is real: an account is added through the form (Save shows its screen, a placeholder until
# build plan 3.7, from which big letters and the account's form open); a second account comes from
# the debug command `entry add`, so that the list is not empty after the first is deleted through its
# row's menu (long press; "Delete" is the menu's last item), and "Recently deleted" is its last row.
passwords_routes() {
    local row nodes last
    present pv.tab.Passwords && pva_tap pv.list.emptyAdd && on Edit \
        && pva_type pv.edit.name Walk && pva_hide_keyboard && pva_tap pv.edit.save && on Entry \
        && open BigLetters && back Entry \
        && open Edit && back Entry \
        && back Passwords || return 1
    pva_debug entry add Second >/dev/null && pva_row_with Second 10 || return 1
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { f = 1; x = $6; y = $7; next } f && $1 == "Walk" { print x, y; exit } f && $1 != "" { f = 0 }')
    [ -n "$row" ] || { pv_log "no row of the account Walk; rows: $(pva_row_titles | tr '\n' ';')"; return 1; }
    pv_shell input swipe $row $row 800 >/dev/null
    pv_sleep 1
    nodes=$(pv_ui_nodes)
    last=$(printf '%s\n' "$nodes" | awk -F'|' '$4 == "true" && $5 == "true" { x = $6; y = $7 } END { if (x) print x, y }')
    printf '%s\n' "$nodes" | grep -q '[|]pv[.]route[.]' && { pv_log "the row's menu did not open"; return 1; }
    [ -n "$last" ] || { pv_log "the row's menu has no item"; return 1; }
    pv_shell input tap $last >/dev/null
    present pv.list.recentlyDeleted && tap pv.list.recentlyDeleted && on RecentlyDeleted && back Passwords
}

security_routes() {
    tap pv.tab.Security && on Security && present pv.tab.Settings \
        && open Group && open GuidedChange && back Group && back Security
}

settings_routes() {
    tap pv.tab.Settings && on Settings \
        && open AppLock && open ChangePin && back AppLock \
        && open CreatePin && back AppLock \
        && open ChangeMasterPassword && open NewMasterPassword && back ChangeMasterPassword && back AppLock \
        && back Settings \
        && open Backup && back Settings \
        && open Export && back Settings \
        && open Import && open ColumnMapping && back Import && back Settings \
        && open OldPasswords && back Settings \
        && open Language && back Settings \
        && open Theme && back Settings \
        && help_pages && back Settings
}

help_pages() {
    open Help 'Help\(page=PROTECTION\)' \
        && open Help 'Help\(page=LOST_PHONE\)' \
        && open Help 'Help\(page=FORGOT_MASTER_PASSWORD\)' \
        && open Help 'Help\(page=FORGOT_PIN\)' \
        && open Help 'Help\(page=VERSION\)' \
        && open Licences \
        && back Help && back Help && back Help && back Help && back Help && back Settings \
        && open Licences
}

# The master-password gate of sub-wave 3A shows no "Choose a PIN" notice after setup (PIN setup is
# wave 4), so the PIN setup steps are opened from Settings, App lock; "Finish setup" goes to the list.
pin_setup_routes() {
    on Settings && open AppLock && open CreatePin \
        && open ConfirmPin && open FingerprintOffer && open BackupsExplained \
        && tap pv.action.finish && on Passwords \
        && tap pv.tab.Settings && on Settings
}

# Lock now: the master-password screen of build plan 3.4, without the bottom bar; the master password
# set up above opens the same route again.
lock_and_unlock() {
    tap pv.action.lockNow && on Locked && absent pv.tab.Settings \
        && pva_unlock Settings && on Settings && present pv.tab.Settings
}

rotate_keeps_route() {
    local rc=0
    pv_shell settings put system accelerometer_rotation 0 >/dev/null
    pv_shell settings put system user_rotation 1 >/dev/null
    pv_sleep 2
    on Settings || rc=1
    pv_shell settings put system user_rotation 0 >/dev/null
    pv_sleep 2
    on Settings || rc=1
    return $rc
}

back_leaves_the_app() {
    back Passwords || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        case "$(pv_focus)" in *"$PV_PACKAGE"/*) pv_sleep 1 ;; *) return 0 ;; esac
    done
    pv_log "the app is still in front: $(pv_focus)"
    return 1
}

every_route_shown() {
    local missing="" r
    for r in $ROUTES; do
        [ "$r" = Starting ] && continue
        case " $SEEN " in *" $r "*) ;; *) missing="$missing $r" ;; esac
    done
    [ -n "$ROUTES" ] || { pv_log "no routes read from Route.kt"; return 1; }
    [ -z "$missing" ] || { pv_log "never shown:$missing"; return 1; }
    pv_log "shown: $(echo $SEEN | wc -w) routes:$SEEN"
}

no_crash() {
    local crash
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
}

pv_step "install the app afresh" -- install_fresh || { pv_summary; exit 1; }
pv_step "start the app" -- start || { pv_summary; exit 1; }
if pv_step "open a setup step" -- eval 'on Welcome && open CreateMasterPassword && open WriteDown'; then
    if kill_in_background; then
        pv_step "a new process after the app was killed in the background starts setup again: step 1, \"Setup was interrupted\"" -- \
            eval 'relaunch_on CreateMasterPassword && present pv.setup.interrupted && back Welcome'
    else
        pv_report SKIPPED "a new process after the app was killed in the background starts setup again: step 1, \"Setup was interrupted\"" \
            "nothing here ended the app's process on API $API"
        pv_step "back to Welcome" -- eval 'relaunch_on WriteDown && back CreateMasterPassword && back Welcome'
    fi
fi
if [ -n "$LOCALE" ]; then
    if [ "$API" -lt 33 ]; then
        pv_report SKIPPED "set the app's language to $LOCALE on Welcome" "cmd locale needs Android 13 or newer"
        LOCALE=""
    else
        pv_step "set the app's language to $LOCALE on Welcome: the same route after the recreation" -- set_locale
    fi
fi
pv_step "setup: Welcome, Language, Restore, the three steps with the words typed again, the vault made" -- setup_routes
if [ -n "$LOCALE" ]; then pv_step "the bottom bar's labels are in $LOCALE, not English" -- shell_texts_localized; fi
pv_step "Passwords: an account added, its screen, big letters, its form; one deleted, Recently deleted" -- passwords_routes
pv_step "Security and the routes below it" -- security_routes
pv_step "Settings and every route it opens" -- settings_routes
pv_step "the PIN setup steps, opened from App lock, and Finish" -- pin_setup_routes
pv_step "Lock now shows the lock route without the bottom bar; the master password brings Settings back" -- lock_and_unlock
pv_step "a rotation keeps the route" -- rotate_keeps_route
pv_step "Back goes to Passwords, then leaves the app" -- back_leaves_the_app
pv_step "every route key was shown" -- every_route_shown
pv_step "no crash" -- no_crash
pv_summary
