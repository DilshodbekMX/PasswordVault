#!/usr/bin/env bash
# tools/emu/shell/walk.sh - build plan 2.5, [script]: the debug app starts and walks the
# placeholder routes of the app's shell.
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
# The walk finds screens by the resource ids the shell gives them (architecture.md §3.6):
# pv.route.<Route> on each screen, pv.open.<Route> on a placeholder's buttons, pv.action.* and
# pv.answer.* for the few that do something else, pv.tab.* on the bottom bar. So it does not
# depend on the language, and it works while the module stubs return the fakes of APP/fake/: the
# fake lock starts with no vault, makes one at "Create the vault", locks at "Lock now" and opens
# with "Unlock". It installs the APK afresh, then:
#   setup (Welcome, Language, Restore, the three steps, the vault made) -> the notice "Choose a
#   PIN" -> the PIN setup steps -> Passwords and below it -> Security and below it -> Settings and
#   every route it opens -> Lock now (no bottom bar, Restore over the lock) -> Unlock (the same
#   route comes back) -> a rotation keeps the route -> Back leaves the app.
# Every route key of nav/Route.kt except Starting must have been shown, and the app must not have
# crashed. Prints PASS or FAIL per step and a summary; exit 0 when no step failed, 1 otherwise,
# 2 for a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

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

# tap ID: taps the node whose resource id is ID, after scrolling it above the bottom band.
tap() {
    local id_re="[|]$(re "$1")\$" nodes line y i
    for i in 1 2 3 4 5; do
        nodes=$(pv_ui_nodes)
        line=$(printf '%s\n' "$nodes" | pv_ui_match "$id_re")
        [ -n "$line" ] || { pv_log "no node with id $1"; return 1; }
        y=$(echo "$line" | cut -d'|' -f7)
        case "$1" in pv.tab.*) y=0 ;; esac
        if [ "$y" -lt "$SAFE_BOTTOM" ]; then
            pv_ui_tap "$id_re" "$nodes"
            return
        fi
        pv_shell input swipe 540 $((SCREEN_H * 6 / 10)) 540 $((SCREEN_H * 3 / 10)) 400 >/dev/null
        pv_sleep 1
    done
    pv_log "the node $1 stays in the bottom band (y $y of $SCREEN_H)"
    return 1
}

# open ROUTE [TEXT-REGEX]: taps "Open ROUTE" and waits for its screen.
open() { tap "pv.open.$1" && on "$@"; }

# back ROUTE: system Back, then ROUTE is shown.
back() { pv_shell input keyevent KEYCODE_BACK >/dev/null && on "$1"; }

absent() { # ID: no node has this resource id
    ! pv_ui_nodes | pv_ui_match "[|]$(re "$1")\$" | grep -q .
}

present() { # ID: a node has this resource id
    pv_ui_wait "[|]$(re "$1")\$" 10
}

# ---- the walk -----------------------------------------------------------------------------------
install_fresh() {
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assembleDebug"; return 1; }
    pv_adb uninstall "$PV_PACKAGE" >/dev/null 2>&1
    local out
    out=$(pv_adb install -r "$APK" 2>&1)
    echo "$out" | grep -q '^Success' || { pv_log "adb install: $out"; return 1; }
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

start() {
    pv_unlock >/dev/null 2>&1
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
}

setup_routes() {
    on Welcome && absent pv.tab.Passwords \
        && open Language && back Welcome \
        && open Restore && back Welcome \
        && open CreateMasterPassword && open WriteDown && open TypeAgain \
        && back WriteDown && open TypeAgain \
        && tap pv.action.createVault && on Passwords
}

pin_setup_routes() {
    present pv.notice && tap pv.answer.CHOOSE_PIN && on CreatePin \
        && open ConfirmPin && open FingerprintOffer && open BackupsExplained \
        && tap pv.action.finish && on Passwords && absent pv.notice
}

passwords_routes() {
    present pv.tab.Passwords \
        && open RecentlyDeleted && back Passwords \
        && open Edit && back Passwords \
        && open Entry && open BigLetters && back Entry \
        && open Edit 'Edit\(id=' && back Entry \
        && back Passwords
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

lock_and_unlock() {
    tap pv.action.lockNow && on Locked && absent pv.tab.Settings \
        && open Restore && back Locked \
        && tap pv.action.unlock && on Settings && present pv.tab.Settings
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

pv_step "install the debug app afresh" -- install_fresh || { pv_summary; exit 1; }
pv_step "start the app" -- start || { pv_summary; exit 1; }
if [ -n "$LOCALE" ]; then
    if [ "$API" -lt 33 ]; then
        pv_report SKIPPED "set the app's language to $LOCALE on Welcome" "cmd locale needs Android 13 or newer"
        LOCALE=""
    else
        pv_step "set the app's language to $LOCALE on Welcome: the same route after the recreation" -- set_locale
    fi
fi
pv_step "setup: Welcome, Language, Restore, the three steps, the vault made" -- setup_routes
pv_step "after setup: the notice and the PIN setup steps" -- pin_setup_routes
if [ -n "$LOCALE" ]; then pv_step "the bottom bar's labels are in $LOCALE, not English" -- shell_texts_localized; fi
pv_step "Passwords and the routes below it" -- passwords_routes
pv_step "Security and the routes below it" -- security_routes
pv_step "Settings and every route it opens" -- settings_routes
pv_step "Lock now shows the lock route without the bottom bar; Unlock brings Settings back" -- lock_and_unlock
pv_step "a rotation keeps the route" -- rotate_keeps_route
pv_step "Back goes to Passwords, then leaves the app" -- back_leaves_the_app
pv_step "every route key was shown" -- every_route_shown
pv_step "no crash" -- no_crash
pv_summary
