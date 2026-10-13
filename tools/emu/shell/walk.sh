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
# pv.route.<Route> on each screen, pv.open.<Route> on the buttons and rows that open a route (a
# placeholder's, the setup screens', Settings'), pv.action.* for the few that do something else,
# pv.tab.* on the bottom bar, and the ids of the real screens (pv.setup.*, pv.lock.*, pv.pin.*,
# pv.list.*, pv.edit.*, pv.settings.*, through tools/emu/app.sh). So it does not depend on the
# language. Since the merge of round R3 the lock is DefaultLockManager (build plan 4.1) with the lock
# screens of 4.2, the PIN steps and Settings are 4.3's, and Backup, Restore, Export and Import are
# 5.2 to 5.4's; the routes of later packages are still placeholders. It installs the APK afresh, then:
#   a setup step left by a new process (Home, am kill, or kill -9 through run-as or su; relaunch:
#   the words are not kept, so setup starts again at step 1 with "Setup was interrupted", ux.md §5.5;
#   SKIPPED where nothing ends the process) ->
#   setup (Welcome, Language, Restore, the three steps with the suggested words typed again, the
#   vault made) -> "Choose a PIN", which the lock opens after setup (lock.md F1 step 6): a PIN, the
#   same PIN again, the fingerprint offer "Not now", "Make backups" Finish -> Passwords: an account
#   added through the form, its screen, big letters, its form; a second account, the first deleted
#   through its row's menu, Recently deleted -> Security and below it -> Settings and every route its
#   rows open (the dialog "Appearance" with two of its colours, the Help pages) -> Lock now (the pad,
#   no bottom bar) -> the PIN (the same route comes back) -> a rotation keeps the route -> Back leaves
#   the app.
# Every route key of nav/Route.kt must have been shown, except Starting and three that no walk can
# reach without more set-up: AppLock (no screen opens it; the fingerprint notice's "Turn on" does),
# OldPasswords (its Settings row is there only while a vault is put aside) and ColumnMapping (it
# opens for a picked CSV file without a password column, build plan 5.4); and the app must not have
# crashed. PV_WALK_PIN sets the PIN (default 582913, which lock.md §7.2 allows). Prints PASS or FAIL
# per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a usage error, 3 for a
# refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
PIN=${PV_WALK_PIN:-582913}
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
# The route keys the walk cannot reach (see the top of this file).
UNREACHED="Starting AppLock OldPasswords ColumnMapping"
SEEN=""

# ---- helpers ----------------------------------------------------------------------------------
re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }

# on ROUTE [TEXT-REGEX]: the screen of ROUTE is shown (and, with TEXT-REGEX, a text matching it).
on() {
    pv_ui_wait "[|]pv\.route\.$1\$" 20 || { pv_log "not on $1; nodes: $(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }')"; return 1; }
    if [ -n "${2:-}" ]; then pv_ui_wait "$2" 10 || { pv_log "no text matching $2 on $1"; return 1; }; fi
    seen "$1"
}

seen() { case " $SEEN " in *" $1 "*) ;; *) SEEN="$SEEN $1" ;; esac; }

# The screen's height in pixels, and the bottom band where the bottom bar and the system's
# navigation bar are: a node of a screen whose centre is there is scrolled up before a tap. With the
# app's bottom bar shown the band begins BAR_HALF pixels above the centre of its tabs (half of the bar's
# 80 dp at 420 dpi is 105 px, and a margin): a fixed 420 px band, as before, held the last row of
# Settings ("Version") on the 2400-pixel screen of API 37, where that row cannot scroll any higher
# (device check of 4A).
SCREEN_H=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
SCREEN_H=${SCREEN_H:-1920}
BAR_HALF=140

# tap ID: taps the node whose resource id is ID, after scrolling it onto the screen (a real screen's
# button can be below the fold: "Use these words" on a 1920-pixel screen) and above the bottom band.
tap() {
    local id_re="[|]$(re "$1")\$" nodes line="" y=0 i bottom tab_y
    for i in 1 2 3 4 5 6; do
        nodes=$(pv_ui_nodes)
        line=$(printf '%s\n' "$nodes" | pv_ui_match "$id_re")
        if [ -n "$line" ]; then
            y=$(echo "$line" | cut -d'|' -f7)
            case "$1" in pv.tab.*) y=0 ;; esac
            # Without the app's bottom bar (setup, the lock) only the system's navigation bar is there.
            bottom=$((SCREEN_H - 160))
            tab_y=$(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]tab[.]/ { print $7; exit }')
            [ -z "$tab_y" ] || bottom=$((tab_y - BAR_HALF))
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

# to_top: the list on the screen scrolled back to its top (tap only scrolls down).
to_top() {
    local i
    for i in 1 2 3 4 5; do
        pv_shell input swipe 540 $((SCREEN_H * 3 / 10)) 540 $((SCREEN_H * 7 / 10)) 300 >/dev/null
    done
    pv_sleep 1
}

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
        && pva_submit pv.action.createVault pv.route.CreatePin 120 && on CreatePin
}

# "Choose a PIN", which the lock opens over the list after setup (lock.md F1 step 6; build plan 4.3):
# the PIN, the same PIN again, the fingerprint offer (shown where the phone has a fingerprint, as these
# emulators do) "Not now", "Make backups" Finish: the list. The PIN is the lock screen's from then on.
pin_steps() {
    on CreatePin && pva_pin "$PIN" && on ConfirmPin && pva_pin "$PIN" || return 1
    if pv_ui_wait '[|]pv\.route\.FingerprintOffer$' 20; then
        on FingerprintOffer && tap pv.fingerprintOffer.notNow || return 1
    else
        pv_log "no fingerprint offer (canAuthenticate is not 0 here?); its route will be missing"
    fi
    on BackupsExplained && tap pv.action.finish && on Passwords
}

# The list is real: an account with a name and a password is added through the form (Save shows its
# screen, build plan 3.7: "Show in big letters", under the password, and the top bar's Edit open from
# it; an account with only a name has no password row and so no big letters); a second account comes
# from the debug command `entry add`, so that the list is not empty after the first is deleted through
# its row's menu (long press; "Delete" is the menu's last item), and "Recently deleted" is its last row.
passwords_routes() {
    local row nodes last
    present pv.tab.Passwords && pva_tap pv.list.emptyAdd && on Edit \
        && pva_type pv.edit.name Walk && pva_type pv.edit.password Wk7Pq2Nz9Rt4Lm8 && pva_hide_keyboard \
        && pva_tap pv.edit.save && on Entry \
        && tap pv.entry.password.big && on BigLetters && back Entry \
        && tap pv.entry.edit && on Edit && back Entry \
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

# Settings (build plan 4.3): the App lock rows are in the list itself, so no row opens AppLock or
# CreatePin. Its rows open ChangePin ("Change PIN": a PIN is set), ChangeMasterPassword (5.5's
# screen; its "I don't remember it" carries pv.open.NewMasterPassword), Backup, Export and Import
# (5.2 to 5.4), Language (6.1's placeholder), the dialog "Appearance" (Route.Theme), the Help pages,
# Licences and Version. tap only scrolls down, so the rows are taken from the top down.
settings_routes() {
    tap pv.tab.Settings && on Settings && to_top \
        && open ChangePin && back Settings \
        && open ChangeMasterPassword && open NewMasterPassword && back ChangeMasterPassword && back Settings \
        && open Backup && back Settings \
        && open Export && back Settings \
        && open Import && back Settings \
        && open Language && back Settings \
        && appearance \
        && help_pages
}

# Route.Theme draws the dialog "Appearance" (4.3b), a window of its own, so its route's node is not in
# the active window's dump: its choices show it. A colour applies at once and the dialog stays (two
# are tried: Emerald, then Bank blue again); Back closes it.
appearance() {
    tap pv.open.Theme && present pv.settings.theme.SYSTEM && present pv.settings.style.BANK_BLUE || return 1
    seen Theme
    tap pv.settings.style.EMERALD && present pv.settings.style.EMERALD \
        && tap pv.settings.style.BANK_BLUE && present pv.settings.theme.SYSTEM \
        && back Settings
}

# The Help rows (the help package's placeholders until build plan 7.3): each page, Licences, Version.
help_pages() {
    help_page PROTECTION && help_page LOST_PHONE && help_page FORGOT_MASTER_PASSWORD && help_page FORGOT_PIN \
        && open Licences && back Settings \
        && help_page VERSION
}

help_page() { tap "pv.open.Help.$1" && on Help "Help\\(page=$1\\)" && back Settings; }

# Lock now (an App lock row, at the top of Settings): the lock screen with the pad, without the bottom
# bar; the PIN chosen after setup opens the same route again.
lock_and_unlock() {
    to_top && tap pv.action.lockNow && on Locked && absent pv.tab.Settings \
        && pva_pin_unlock Settings "$PIN" && on Settings && present pv.tab.Settings
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
        case " $UNREACHED " in *" $r "*) continue ;; esac
        case " $SEEN " in *" $r "*) ;; *) missing="$missing $r" ;; esac
    done
    [ -n "$ROUTES" ] || { pv_log "no routes read from Route.kt"; return 1; }
    [ -z "$missing" ] || { pv_log "never shown:$missing"; return 1; }
    pv_log "shown: $(echo $SEEN | wc -w) routes:$SEEN; not reachable by this walk: $UNREACHED"
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
pv_step "setup: Welcome, Language, Restore, the three steps with the words typed again, the vault made: \"Choose a PIN\"" -- setup_routes
pv_step "the PIN steps after setup: a PIN, the same again, the fingerprint offer \"Not now\", \"Make backups\" Finish: the list" -- pin_steps
if [ -n "$LOCALE" ]; then pv_step "the bottom bar's labels are in $LOCALE, not English" -- shell_texts_localized; fi
pv_step "Passwords: an account added, its screen, big letters, its form; one deleted, Recently deleted" -- passwords_routes
pv_step "Security and the routes below it" -- security_routes
pv_step "Settings and every route its rows open, the dialog \"Appearance\" and the Help pages" -- settings_routes
pv_step "Lock now shows the lock route (the pad) without the bottom bar; the PIN brings Settings back" -- lock_and_unlock
pv_step "a rotation keeps the route" -- rotate_keeps_route
pv_step "Back goes to Passwords, then leaves the app" -- back_leaves_the_app
pv_step "every route key was shown" -- every_route_shown
pv_step "no crash" -- no_crash
pv_summary
