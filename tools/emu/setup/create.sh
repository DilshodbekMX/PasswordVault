#!/usr/bin/env bash
# tools/emu/setup/create.sh - build plan 3.3, [script]: a vault can be created through the setup
# screens with the suggested words and with an own password (ux.md §5.1, §5.3 to §5.5; lock.md F1
# steps 1 to 3).
#
#   tools/emu/setup/create.sh SERIAL [--apk FILE] [--vault-file]
#       SERIAL comes from tools/emu/boot.sh. The APK defaults to this worktree's
#       app/build/outputs/apk/debug/app-debug.apk (tools/gradle.sh :app:assembleDebug); a staging
#       APK works too.
#       --vault-file also checks, after each setup, that files/vaults/live holds
#       vault-0000000001.pvault (run-as: SKIPPED on staging). Use it once the app has its real lock
#       and repository (build plan 3.1 and 3.2); while LockModule returns FakeLockManager, setup ends
#       unlocked and no file is written.
#
# It finds the controls by the resource ids of the setup screens (ui/setup/SetupNav.kt, SetupTags:
# pv.route.<Route>, pv.open.<Route>, pv.setup.*, pv.action.createVault), so it does not depend on
# the language. It installs the APK afresh, then:
#   1. With the suggested words: Welcome -> Start -> six words of the EFF short list, "Show other
#      words" gives six others -> "Use these words" -> "Write it down on paper" shows the same words
#      -> Next -> "Type your master password again" has the focus: the words are typed there -> Next
#      -> the app is unlocked (the Passwords route is shown).
#   2. With an own password, after `pm clear`: Welcome -> Start -> "Type my own instead" -> a weak
#      password ("Samarqand2024") is refused with a message and no question -> a strong one ->
#      "Use this password" -> "Do you use this password for any other account?" -> "No, it's new"
#      -> "Write it down on paper" shows it -> Next -> a different typing gives the mismatch message
#      -> the same password, sent with the keyboard's Done key -> the app is unlocked.
# The app must not crash. Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step
# failed, 1 otherwise, 2 for a usage error, 3 for a refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
VAULT_FILE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --vault-file) VAULT_FILE=1 ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

pv_use "$SERIAL"
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
echo "create.sh on $SERIAL (API $API): $APK"

WORDLIST=$REPO/core/src/main/resources/wordlists/eff_short_wordlist_1.txt
OWN_WEAK=Samarqand2024
OWN=Kq7vLp9Zx2Wm4Tn8
OWN_WRONG=Kq7vLp9Zx2Wm4Tn9

# ---- helpers ----------------------------------------------------------------------------------
re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }

SCREEN_H=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
SCREEN_H=${SCREEN_H:-1920}
SAFE_BOTTOM=$((SCREEN_H - 160)) # the gesture or button bar; setup has no bottom bar of its own

# on ROUTE: the screen of ROUTE is shown (fresh dumps, see fresh_nodes).
on() {
    present "pv.route.$1" 20 || { pv_log "not on $1; ids: $(fresh_nodes | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }')"; return 1; }
}

# the text of the node whose resource id is ID (scrolls down to it if needed).
text_of() {
    local line
    line=$(find_node "$1") || return 1
    echo "$line" | cut -d'|' -f1
}

# fresh_nodes: the screen's nodes as they are now, in pv_ui_nodes's form. `uiautomator dump` fails
# with "ERROR: could not get idle state." (and exit status 0) while the window keeps changing; on API 28
# a focused Compose text field does that, its blinking cursor sending TYPE_WINDOW_CONTENT_CHANGED every
# 500 ms, and the old dump file is then still there to be read (pv_ui_nodes reads it: the positions and
# even the screen of an earlier dump, seen on API 28). So the old file is removed first, and when the
# dump fails the focus is moved on (Tab, which never clicks) until no field has it. A field that lost
# the focus is tapped again before typing; the tap that follows a dump puts the device back into touch
# mode.
UI_XML=/sdcard/pv_setup_ui.xml
fresh_nodes() {
    local i out xml
    for i in 1 2 3 4 5 6 7 8; do
        pv_shell rm -f "$UI_XML" >/dev/null 2>&1
        out=$(pv_shell uiautomator dump "$UI_XML" 2>&1 | tr -d '\r')
        case "$out" in
            *"dumped to"*)
                xml=$(pv_shell cat "$UI_XML" 2>/dev/null)
                case "$xml" in *"<hierarchy"*) printf '%s' "$xml" | parse_nodes; return 0 ;; esac ;;
        esac
        pv_shell input keyevent KEYCODE_TAB >/dev/null
        pv_sleep 1
    done
    pv_log "uiautomator dump keeps failing: $out"
    return 1
}

# parse_nodes < XML: one line per node, text|desc|id|clickable|enabled|x|y (as pv_ui_nodes prints).
parse_nodes() {
    python3 -c '
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    a = n.attrib
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", a.get("bounds", "[0,0][0,0]")))
    text = a.get("text", "").replace("\n", " ").replace("|", "/")
    desc = a.get("content-desc", "").replace("\n", " ").replace("|", "/")
    rid = a.get("resource-id", "").split("/")[-1]
    print("|".join([text, desc, rid, a.get("clickable", ""), a.get("enabled", ""),
                    str((x1 + x2) // 2), str((y1 + y2) // 2)]))
'
}

# find_node ID: prints the node with that resource id, scrolling the screen up until it is above the
# bottom band (where the system's navigation bar and the keyboard's edge are).
find_node() {
    local id_re="[|]$(re "$1")\$" line y i
    for i in 1 2 3 4 5 6; do
        line=$(fresh_nodes | pv_ui_match "$id_re")
        if [ -n "$line" ]; then
            y=$(echo "$line" | cut -d'|' -f7)
            if [ "$y" -lt "$SAFE_BOTTOM" ]; then
                echo "$line"
                return 0
            fi
        fi
        # Within the upper part of the screen, which the keyboard never covers in portrait.
        pv_shell input swipe 540 $((SCREEN_H * 45 / 100)) 540 $((SCREEN_H * 15 / 100)) 400 >/dev/null
        pv_sleep 1
    done
    pv_log "no node with id $1 above y $SAFE_BOTTOM"
    return 1
}

keyboard_shown() { pv_shell dumpsys input_method | tr -d '\r' | grep -q 'mInputShown=true'; }

# hide_keyboard: closes the keyboard (Back, which the keyboard takes) and waits until it is gone.
hide_keyboard() {
    keyboard_shown || return 0
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    local i
    for i in 1 2 3 4 5; do
        pv_sleep 1
        keyboard_shown || return 0
    done
    pv_log "the keyboard stays open"
    return 1
}

# tap ID: taps the node with that resource id. A button is tapped with the keyboard closed: on
# Android 7 to 10 the app's window is not resized for the keyboard (MainActivity has no
# windowSoftInputMode="adjustResize"), so a button under a field can be behind it.
tap() {
    local line x y
    case "$1" in pv.setup.own | pv.setup.again) ;; *) hide_keyboard || return 1 ;; esac
    line=$(find_node "$1") || return 1
    x=$(echo "$line" | cut -d'|' -f6); y=$(echo "$line" | cut -d'|' -f7)
    pv_shell input tap "$x" "$y" >/dev/null
}

# present ID [SECONDS]: a node with that resource id appears (fresh dumps, see fresh_nodes).
present() {
    local deadline=$((SECONDS + ${2:-10}))
    while :; do
        fresh_nodes | pv_ui_match "[|]$(re "$1")\$" | grep -q . && return 0
        [ $SECONDS -lt $deadline ] || return 1
        pv_sleep 1
    done
}
absent() { ! fresh_nodes | pv_ui_match "[|]$(re "$1")\$" | grep -q .; }

# type_into ID TEXT: taps the field and types TEXT (letters, digits and hyphens only).
type_into() {
    tap "$1" || return 1
    pv_sleep 1
    pv_shell input text "$2" >/dev/null
    pv_sleep 1
}

# clear_field ID LENGTH: deletes LENGTH characters at the end of the field.
clear_field() {
    local keys="" i
    tap "$1" || return 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    for i in $(seq 1 "$2"); do keys="$keys 67"; done
    pv_shell input keyevent $keys >/dev/null
    pv_sleep 1
}

install_fresh() {
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assembleDebug"; return 1; }
    pv_adb uninstall "$PV_PACKAGE" >/dev/null 2>&1
    local out
    out=$(pv_adb install -r "$APK" 2>&1)
    echo "$out" | grep -q '^Success' || { pv_log "adb install: $out"; return 1; }
}

start() {
    pv_unlock >/dev/null 2>&1
    pv_shell settings put system accelerometer_rotation 0 >/dev/null
    pv_shell settings put system user_rotation 0 >/dev/null
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
    on Welcome
}

clear_data() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pv_shell pm clear "$PV_PACKAGE" | tr -d '\r' | grep -q '^Success'
}

# Six lower-case words of the EFF short list joined by hyphens.
WORDS=""
are_six_words() { # PASSPHRASE
    printf '%s\n' "$1" | grep -Eq '^[a-z]{3,5}(-[a-z]{3,5}){5}$' || { pv_log "not six words: '$1'"; return 1; }
    local w
    for w in $(printf '%s' "$1" | tr '-' ' '); do
        grep -qx "[1-6]\{4\}$(printf '\t')$w" "$WORDLIST" || { pv_log "'$w' is not in the word list"; return 1; }
    done
}

suggested_words_shown() {
    tap pv.open.CreateMasterPassword && on CreateMasterPassword || return 1
    present pv.setup.words 20 || return 1
    WORDS=$(text_of pv.setup.passphrase) || return 1
    are_six_words "$WORDS"
}

other_words() {
    local before=$WORDS
    tap pv.setup.otherWords || return 1
    pv_sleep 2
    WORDS=$(text_of pv.setup.passphrase) || return 1
    are_six_words "$WORDS" || return 1
    [ "$WORDS" != "$before" ] || { pv_log "the same words again"; return 1; }
}

write_down_shows_the_words() {
    tap pv.open.WriteDown && on WriteDown || return 1
    local shown
    shown=$(text_of pv.setup.passphrase) || return 1
    [ "$shown" = "$WORDS" ] || { pv_log "WriteDown shows '$shown', step 1 showed '$WORDS'"; return 1; }
}

# keyboard_comes [SECONDS]: the keyboard is shown within SECONDS (dumpsys, not uiautomator).
keyboard_comes() {
    local deadline=$((SECONDS + ${1:-10}))
    until keyboard_shown; do
        [ $SECONDS -lt $deadline ] || return 1
        pv_sleep 1
    done
}

type_again_has_the_focus() {
    tap pv.open.TypeAgain || return 1
    # The field takes the focus and asks for the keyboard when the screen appears (ux.md §5.5).
    # "Write it down on paper" has no field, so a keyboard means TypeAgain's. Checked before any
    # dump, whose Tab (fresh_nodes) would move the focus.
    keyboard_comes 10 || { pv_log "no keyboard after Next"; return 1; }
    # Typed without tapping the field: the screen gave it the focus.
    pv_shell input text "$WORDS" >/dev/null
    pv_sleep 1
    on TypeAgain || return 1
    local typed
    typed=$(text_of pv.setup.again) || return 1
    [ "$typed" = "$WORDS" ] || { pv_log "the field holds '$typed'"; return 1; }
}

unlocked_after() { # how: button | done
    if [ "$1" = done ]; then
        pv_shell input keyevent KEYCODE_ENTER >/dev/null
    else
        tap pv.action.createVault || return 1
    fi
    on Passwords || return 1
    absent pv.route.TypeAgain
}

vault_file() {
    local out
    out=$(pv_shell run-as "$PV_PACKAGE" ls files/vaults/live 2>&1 | tr -d '\r')
    printf '%s\n' "$out" | grep -q '^vault-0000000001\.pvault$' || { pv_log "files/vaults/live: $out"; return 1; }
}

weak_own_password_refused() {
    tap pv.open.CreateMasterPassword && on CreateMasterPassword || return 1
    tap pv.setup.typeOwn && present pv.setup.own || return 1
    type_into pv.setup.own "$OWN_WEAK" || return 1
    tap pv.setup.useOwn || return 1
    present pv.setup.ownMessage 10 || { pv_log "no message for $OWN_WEAK"; return 1; }
    absent pv.setup.reuse.no || { pv_log "the reuse question came for a weak password"; return 1; }
}

strong_own_password_asks_about_reuse() {
    clear_field pv.setup.own ${#OWN_WEAK} || return 1
    type_into pv.setup.own "$OWN" || return 1
    tap pv.setup.useOwn || return 1
    present pv.setup.reuse.no 10 || { pv_log "no reuse question for the strong password"; return 1; }
    tap pv.setup.reuse.no || return 1
    on WriteDown || return 1
    local shown
    shown=$(text_of pv.setup.chosen) || return 1
    [ "$shown" = "$OWN" ] || { pv_log "WriteDown shows '$shown'"; return 1; }
}

own_mismatch_then_match() {
    tap pv.open.TypeAgain && on TypeAgain || return 1
    type_into pv.setup.again "$OWN_WRONG" || return 1
    tap pv.action.createVault || return 1
    present pv.setup.againMessage 10 || { pv_log "no mismatch message"; return 1; }
    on TypeAgain || return 1
    type_into pv.setup.again "$OWN"
}

no_crash() {
    local crash
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
}

# ---- the run ------------------------------------------------------------------------------------
pv_shell logcat -b crash -c >/dev/null 2>&1
pv_shell logcat -c >/dev/null 2>&1
pv_step "install the app afresh" -- install_fresh || { pv_summary; exit 1; }

echo "-- 1. with the suggested words"
if pv_step "Welcome, then Start: six words of the EFF short list" -- eval 'start && suggested_words_shown'; then
    pv_step "\"Show other words\" gives six other words" -- other_words
    pv_step "\"Use these words\": \"Write it down on paper\" shows the same words" -- write_down_shows_the_words \
        && pv_step "Next: \"Type your master password again\" has the field and the keyboard; the words are typed" -- type_again_has_the_focus \
        && pv_step "Next makes the vault: the app is unlocked" -- unlocked_after button \
        && if [ "$VAULT_FILE" = 1 ]; then pv_step "files/vaults/live holds vault-0000000001.pvault" needs-debuggable -- vault_file; fi
fi

echo "-- 2. with an own password"
if pv_step "pm clear, Welcome" -- eval 'clear_data && start'; then
    pv_step "\"Type my own instead\": a weak password gets a message and no question" -- weak_own_password_refused \
        && pv_step "a strong one: the reuse question, \"No, it's new\", \"Write it down on paper\" shows it" -- strong_own_password_asks_about_reuse \
        && pv_step "Next: a different typing gives the mismatch message; the same one is typed" -- own_mismatch_then_match \
        && pv_step "the keyboard's Done makes the vault: the app is unlocked" -- unlocked_after done \
        && if [ "$VAULT_FILE" = 1 ]; then pv_step "files/vaults/live holds vault-0000000001.pvault" needs-debuggable -- vault_file; fi
fi

pv_step "no crash" -- no_crash
pv_summary
