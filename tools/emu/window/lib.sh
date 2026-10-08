# tools/emu/window/lib.sh - shared by the scripts of tools/emu/window (build plan 3.12; architecture.md
# §8.4 "Windows and input"): the helper app with its commands and records, and the walk through
# PasswordVault's real setup, lock and edit screens. A script sources it and calls win_begin:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#   win_begin NAME "$@"      # SERIAL [--apk FILE]: the device, the helper app built and installed
#   pv_step "..." -- ...     # adb.sh's steps: PASS, FAIL or SKIPPED per step
#   win_end                  # the app did not crash, the summary, the exit status
#
# After it the script has adb.sh's pv_* functions, app.sh's pva_* functions, and:
#   SERIAL, APK, API, VARIANT (debug or staging, read from the installed app), WIN_OUT (a directory
#   for what the script saw, under this worktree's build/window/, never in git)
#   win_cmd NAME [EXTRAS...]     a command of the helper's LabCommandReceiver; prints its answer
#   win_file NAME, win_file_rm NAME...   a record in the helper's files directory
#   win_setting_set NAMESPACE KEY VALUE  a device setting, put back when the script ends
#   win_undo COMMAND             a shell command for the device, run when the script ends (after the
#                                settings are put back, the last one first)
#   win_app_fresh, win_to_own, win_use_own, win_reuse_no, win_to_again, win_create_vault,
#   win_set_up, win_open_form, win_lock, win_unlock, win_field_dialog, ...   the walk (below)
#
# The helper app (tools/emu/window/helper, package io.github.dilshodbekmx.pvlab.window) is built
# through tools/gradle.sh when its APK is missing or older than its sources.
set -u
WIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMU="$(cd "$WIN_DIR/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

WIN_HELPER=io.github.dilshodbekmx.pvlab.window
WIN_HELPER_DIR=$WIN_DIR/helper
WIN_HELPER_APK=${PV_WINDOW_HELPER_APK:-$WIN_HELPER_DIR/build/outputs/apk/debug/pv-window-helper-debug.apk}

# What the scripts type. Letters and digits only, which adb's "input text" takes as they are; the
# master password passes the rules of ux.md §5.3 (12 code points, hard to guess). A text for a field
# that starts sentences with a capital begins with one, so that the keyboard leaves it as typed.
WIN_MASTER=${PV_MASTER_PASSWORD:-Kv7Qx2mZp9Lw4Rt8Hb}
WIN_TITLE=WinTitleAAA
WIN_USER=winUserBBB
WIN_PASS=WinEntryPwCCC9
WIN_SITE=winsiteDDD.example
WIN_NOTE=WinNoteEEE
WIN_LABEL=WinLabelFFF
WIN_VALUE=WinValueGGG7
WIN_QUERY=WinQueryHHH

win_usage() { pv_usage "$WIN_SCRIPT"; }

# win_begin NAME ARGS...: ARGS are the script's own, SERIAL [--apk FILE].
win_begin() {
    WIN_NAME=$1
    WIN_SCRIPT=${BASH_SOURCE[1]}
    shift
    SERIAL=${1:-}
    [ -n "$SERIAL" ] || win_usage
    shift
    APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || win_usage; APK=$2; shift ;;
            *) win_usage ;;
        esac
        shift
    done
    pv_use "$SERIAL"
    pva_init
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
    API=$(pv_api)
    VARIANT=?
    WIN_OUT=$REPO/build/window/$SERIAL
    mkdir -p "$WIN_OUT"
    WIN_RESTORE=()
    WIN_UNDO=()
    WIN_OVERLAY=0
    WIN_INSTRUMENTED=0
    echo "$WIN_NAME on $SERIAL (API $API): $APK"
    if [ "${API:-0}" -lt 26 ] 2>/dev/null; then
        echo "$WIN_NAME: the helper app needs Android 8.0 (API 26); $SERIAL has API $API" >&2
        exit 2
    fi
    trap win_cleanup EXIT
    trap 'exit 130' INT TERM
    pv_step "the helper app is built" -- win_helper_build || win_end
    pv_step "the helper app is installed" -- win_helper_install || win_end
}

# win_end: the last steps of every script, then its exit status.
win_end() {
    if pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'; then
        pv_step "the app did not crash" -- pva_no_crash
    fi
    pv_summary
    exit $?
}

# Puts the device back as the script found it: the settings it changed, no overlay, portrait, and
# no process of the app left running under the saved-state probe.
win_cleanup() {
    local i ns key value
    trap - EXIT
    [ -n "${PV_SERIAL:-}" ] || return 0
    PV_ADB_TIMEOUT=30
    [ "$WIN_OVERLAY" = 0 ] || win_cmd overlay-remove >/dev/null 2>&1
    for ((i = ${#WIN_RESTORE[@]} - 1; i >= 0; i--)); do
        IFS='|' read -r ns key value <<<"${WIN_RESTORE[$i]}"
        if [ "$value" = null ]; then
            pv_shell settings delete "$ns" "$key" >/dev/null 2>&1
        else
            pv_shell settings put "$ns" "$key" "$value" >/dev/null 2>&1
        fi
    done
    for ((i = ${#WIN_UNDO[@]} - 1; i >= 0; i--)); do
        pv_shell "${WIN_UNDO[$i]}" >/dev/null 2>&1
    done
    [ "$WIN_INSTRUMENTED" = 0 ] || pv_shell am force-stop "$PV_PACKAGE" >/dev/null 2>&1
    pv_shell am force-stop "$WIN_HELPER" >/dev/null 2>&1
    case "$(pv_shell settings get system user_rotation 2>/dev/null | tr -d '\r')" in
        0 | null | "") ;;
        *) pv_shell settings put system user_rotation 0 >/dev/null 2>&1 ;;
    esac
}

# ---- the helper app ----------------------------------------------------------------------------
# Builds the helper's APK when it is missing or older than one of its sources. With
# PV_WINDOW_HELPER_APK set, that APK is used as it is.
win_helper_build() {
    if [ -n "${PV_WINDOW_HELPER_APK:-}" ]; then
        [ -f "$WIN_HELPER_APK" ] || { pv_log "no helper APK at $WIN_HELPER_APK"; return 1; }
        return 0
    fi
    if [ -f "$WIN_HELPER_APK" ] && [ -z "$(find "$WIN_HELPER_DIR" -path "$WIN_HELPER_DIR/build" -prune -o -path "$WIN_HELPER_DIR/.gradle" -prune -o -type f -newer "$WIN_HELPER_APK" -print | head -n 1)" ]; then
        return 0
    fi
    local log=$REPO/build/window/helper-build.log
    mkdir -p "$(dirname "$log")"
    "$REPO/tools/gradle.sh" -p tools/emu/window/helper assembleDebug >"$log" 2>&1 \
        || { pv_log "the helper app did not build (tools/gradle.sh -p tools/emu/window/helper assembleDebug): $(tail -n 15 "$log")"; return 1; }
    [ -f "$WIN_HELPER_APK" ] || { pv_log "the build left no APK at $WIN_HELPER_APK"; return 1; }
}

win_helper_install() {
    local out i
    for i in 1 2 3; do
        out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$WIN_HELPER_APK" 2>&1)
        echo "$out" | grep -q '^Success' && break
        pv_log "adb install of the helper, try $i: ${out:-no answer within 90 s}"
        [ "$i" -lt 3 ] || return 1
        pv_sleep 3
    done
    pv_shell run-as "$WIN_HELPER" sh -c 'rm -f files/*' >/dev/null 2>&1
    pv_shell run-as "$WIN_HELPER" id 2>/dev/null | grep -q 'uid=' || { pv_log "run-as does not work for the helper app"; return 1; }
}

# win_cmd NAME [EXTRAS...]: a command of the helper's LabCommandReceiver (its header lists them);
# prints the answer and is true when the command was done.
win_cmd() {
    local out
    out=$(pv_shell am broadcast --include-stopped-packages -n "$WIN_HELPER/.LabCommandReceiver" --es cmd "$@" 2>&1 | tr -d '\r' | grep 'result=')
    printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)"$/\1/p'
    case "$out" in *'result=1,'*) return 0 ;; *) pv_log "helper command '$1': ${out:-no answer}"; return 1 ;; esac
}

# A record of the helper (LabFiles.java), and removing records.
win_file() { pv_shell run-as "$WIN_HELPER" cat "files/$1" 2>/dev/null | tr -d '\r'; }

win_file_rm() {
    local name
    for name in "$@"; do pv_shell run-as "$WIN_HELPER" rm -f "files/$name" >/dev/null 2>&1; done
}

# win_setting_set NAMESPACE KEY VALUE: sets it and remembers what it was, for win_cleanup.
win_setting_set() {
    local old
    old=$(pv_shell settings get "$1" "$2" 2>/dev/null | tr -d '\r')
    WIN_RESTORE+=("$1|$2|${old:-null}")
    pv_shell settings put "$1" "$2" "$3" >/dev/null || return 1
}

# win_undo COMMAND: COMMAND (one string, for the device's shell) runs when the script ends.
win_undo() { WIN_UNDO+=("$1"); }

# The helper's control screen (ControlActivity) in front.
win_control_start() {
    local out
    out=$(pv_shell am start -W -n "$WIN_HELPER/.ControlActivity" "$@" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start of the control screen: $out"; return 1; }
    pva_need control_button 15
}

# ---- the app's own screens (ids: SetupTags, LockTags, ListTags, EditTags, GenTags) ---------------
# The app installed afresh and started: Welcome. VARIANT becomes debug or staging.
win_app_fresh() {
    pva_install "$APK" || return 1
    if pv_is_debuggable; then VARIANT=debug; else VARIANT=staging; fi
    pva_start && pva_on Welcome 30
}

# Welcome -> "Choose your master password" -> "Type my own password": the field pv.setup.own.
win_to_own() {
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.typeOwn && pva_tap pv.setup.typeOwn && pva_need pv.setup.own
}

# "Use this password": the question "Do you use this password for any other account?" (a dialog).
win_use_own() {
    pva_hide_keyboard
    pva_tap pv.setup.useOwn && pva_need pv.setup.reuse.no 15
}

# "No, it's new": "Write it down on paper", which shows the password (pv.setup.chosen).
win_reuse_no() { pva_tap pv.setup.reuse.no && pva_on WriteDown && pva_need pv.setup.chosen; }

# Next: "Type your master password again", the field pv.setup.again.
win_to_again() { pva_tap pv.open.TypeAgain && pva_on TypeAgain && pva_need pv.setup.again; }

# Next on "Type again": the vault is made and the list is shown.
win_create_vault() { pva_submit pv.action.createVault pv.route.Passwords 120; }

# The whole setup with a password of one's own, from Welcome to the list.
win_set_up() {
    win_to_own && pva_type pv.setup.own "$WIN_MASTER" && win_use_own && win_reuse_no \
        && win_to_again && pva_type pv.setup.again "$WIN_MASTER" && win_create_vault
}

# From the list: Add, the form.
win_open_form() {
    pva_on Passwords || return 1
    if pva_present pv.list.emptyAdd 2; then pva_tap pv.list.emptyAdd; else pva_tap pv.list.add; fi || return 1
    pva_on Edit && pva_need pv.edit.name
}

# In the form: "Add field", the chip "Other…": the dialog with its label and value fields.
win_field_dialog() {
    pva_hide_keyboard
    pva_tap pv.edit.addField && pva_need pv.edit.addField.add 15 || return 1
    pva_tap pv.edit.addField.chip.OTHER && pva_need pv.edit.addField.label 10 && pva_need pv.edit.addField.value 10
}

# "Add" in the dialog: the field is the form's first extra field (pv.edit.field.0).
win_field_add() {
    pva_hide_keyboard
    pva_tap pv.edit.addField.add && pva_reveal pv.edit.field.0
}

# In the form: the generator's sheet (pv.gen.preview at its top, pv.gen.use at its bottom, which a
# short screen shows only after scrolling: pva_tap does that).
win_generator() {
    pva_hide_keyboard
    pva_tap pv.edit.generate && pva_need pv.gen.preview 15
}

# Save: the account's own screen.
win_save_form() {
    pva_hide_keyboard
    pva_tap pv.edit.save && pva_on Entry 30
}

# From the list: "Lock now", the master-password lock screen with its field pv.lock.password.
win_lock() {
    pva_on Passwords || return 1
    pva_hide_keyboard
    pva_tap pv.list.lockNow && pva_on Locked 20 && pva_need pv.lock.password
}

win_unlock() { pva_type pv.lock.password "$WIN_MASTER" && pva_submit pv.lock.unlock pv.route.Passwords 90; }

# win_long_press ID: a long press on the node, which opens a text field's toolbar.
win_long_press() {
    local line x y
    line=$(pva_node "$1")
    [ -n "$line" ] || { pv_log "no node $1 to press; ids: $(pva_ids)"; return 1; }
    x=$(printf '%s\n' "$line" | cut -d'|' -f6); y=$(printf '%s\n' "$line" | cut -d'|' -f7)
    pv_shell input swipe "$x" "$y" "$x" "$y" 900 >/dev/null
    pv_sleep 1
}

# Out of the form without saving: Close, and "Discard" when the form asks about unsaved changes.
win_close_form() {
    pva_hide_keyboard
    pva_tap pv.edit.close || return 1
    if pva_present pv.edit.dialog.discard 3; then pva_tap pv.edit.dialog.discard || return 1; fi
    pva_on Passwords
}

# ---- the helper's accessibility services ---------------------------------------------------------
# win_a11y_enable KIND...: turns on the helper's accessibility services (plain: not an accessibility
# tool; tool: declared as one) and waits until they are connected. The settings are put back at the
# end (win_cleanup).
win_a11y_enable() {
    local kind list=""
    WIN_A11Y_KINDS=("$@")
    for kind in "$@"; do
        case "$kind" in
            plain) list="$list:$WIN_HELPER/.LabA11yService" ;;
            tool) list="$list:$WIN_HELPER/.LabA11yToolService" ;;
        esac
    done
    win_setting_set secure enabled_accessibility_services "${list#:}" || return 1
    win_setting_set secure accessibility_enabled 1 || return 1
    win_a11y_wait 20
}

# win_a11y_wait [SECONDS]: the services of win_a11y_enable are connected. Each reading of the screen
# by pv_ui_nodes (UiAutomation) disconnects every accessibility service for a moment.
win_a11y_wait() {
    local deadline=$((SECONDS + ${1:-10})) status kind ok
    while :; do
        status=$(win_cmd a11y-status 2>/dev/null)
        ok=1
        for kind in "${WIN_A11Y_KINDS[@]}"; do
            case " $status " in *" $kind=true "*) ;; *) ok=0 ;; esac
        done
        [ $ok -eq 0 ] || return 0
        [ $SECONDS -lt $deadline ] || { pv_log "the accessibility services are not connected: ${status:-no answer}"; return 1; }
        pv_sleep 0.5
    done
}

# win_a11y_dump KIND: what the service KIND can reach now (LabA11yService.dump's lines). One adb call:
# the command, then the record it wrote, which must carry this call's number.
win_a11y_dump() {
    local nonce out try
    for try in 1 2 3; do
        nonce="n$RANDOM$RANDOM"
        out=$(pv_shell "am broadcast -n $WIN_HELPER/.LabCommandReceiver --es cmd a11y-dump --es kind $1 --es nonce $nonce >/dev/null 2>&1; run-as $WIN_HELPER cat files/a11y-$1-dump.txt" 2>/dev/null | tr -d '\r')
        case "$(printf '%s\n' "$out" | head -n 1)|$(printf '%s\n' "$out" | tail -n 1)" in
            "DUMP $nonce|END $nonce "*) printf '%s\n' "$out"; return 0 ;;
        esac
        win_a11y_wait 10 || return 1
    done
    pv_log "no node dump from the accessibility service '$1'"
    return 1
}

# For awk over a dump's NODE lines (tab-separated): val("text") is the field that starts with "text=".
WIN_AWK_VAL='function val(k,   i) { for (i = 6; i <= NF; i++) if (index($i, k "=") == 1) return substr($i, length(k) + 2); return "" }'

# win_front: the app in front again and on the list: after a while away it has locked itself (the
# lock delay, 30 s), and the master password opens it.
win_front() {
    pva_relaunch || return 1
    pv_ui_wait '[|]pv\.route\.(Locked|Passwords)$' 20 || { pv_log "neither the list nor the lock screen; ids: $(pva_ids)"; return 1; }
    if pva_absent pv.route.Locked; then pva_on Passwords; else win_unlock; fi
}

# win_app_string NAME: the app's own text string/NAME as the APK has it for the default language
# (English, which the emulators are set to): for the few nodes that have no test tag.
win_app_string() {
    local aapt2
    aapt2=$(ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
    [ -n "$aapt2" ] || return 1
    "$aapt2" dump resources "$APK" 2>/dev/null | awk -v name="string/$1" '
        $NF == name { f = 1; next }
        f && /^ *\(\) "/ { sub(/^ *\(\) "/, ""); sub(/"$/, ""); print; found = 1; exit }
        f && /resource 0x/ { exit }
        END { exit found ? 0 : 1 }'
}
