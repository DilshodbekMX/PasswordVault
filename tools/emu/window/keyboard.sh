#!/usr/bin/env bash
# tools/emu/window/keyboard.sh - build plan 3.12, [script]: what the keyboard is told about every
# text field of the real setup, lock and edit screens (architecture.md §8.4; lock.md §13.5; ux.md §3).
# The method is the window lab's ime_probe.py (docs/lab/window-input.md, Experiment 3): each field is
# given the focus, and the EditorInfo the keyboard's own process received is read from
# `dumpsys input_method` (mInputEditorInfo).
#
#   tools/emu/window/keyboard.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37; the helper app needs API 26). The APK
#       defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk; the staging APK is
#       checked the same way.
#
# For every field: IME_FLAG_NO_PERSONALIZED_LEARNING is set (the keyboard is asked not to learn from
# what is typed). For a password, a secret value, a username and a website also: no auto-correct. A
# password or secret value is a password field (TYPE_TEXT_VARIATION_PASSWORD), a username an e-mail
# field, a website a URI field.
# The control is the helper app's own screen, made of platform fields: its text field has no such
# flag and its password field is a password field, so the reading is of the field that has the focus.
# Prints one line with the values and PASS or FAIL per field, and a summary; exit 0 when no step
# failed, 1 otherwise, 2 for a usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin keyboard.sh "$@"

NO_LEARNING=$((0x1000000))  # EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
AUTO_CORRECT=$((0x8000))    # InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
LAST_CONNECTION=""

# editor: "INPUT-TYPE IME-OPTIONS PACKAGE CONNECTION", the keyboard process's own copy of the
# EditorInfo of the field it is bound to, and the input connection it was started with.
editor() {
    pv_shell dumpsys input_method 2>/dev/null | tr -d '\r' | awk '
        /mStartedInputConnection=/ { conn = $0; sub(/.*mStartedInputConnection=/, "", conn); gsub(/ /, "_", conn) }
        /^ *mInputEditorInfo:/ { f = 1; next }
        f && /inputType=0x/ { for (i = 1; i <= NF; i++) { if ($i ~ /^inputType=/) it = substr($i, 11); if ($i ~ /^imeOptions=/) io = substr($i, 12) } }
        f && /packageName=/ { for (i = 1; i <= NF; i++) if ($i ~ /^packageName=/) pkg = substr($i, 13); f = 0 }
        END { print (it == "" ? "?" : it), (io == "" ? "?" : io), (pkg == "" ? "?" : pkg), (conn == "" ? "?" : conn) }'
}

describe() { # INPUT-TYPE IME-OPTIONS
    local it=$(($1)) io=$(($2)) variation
    case $((it & 0xff0)) in
        $((0x00))) variation=normal ;;
        $((0x10))) variation=URI ;;
        $((0x20))) variation=EMAIL_ADDRESS ;;
        $((0x80))) variation=PASSWORD ;;
        $((0x90))) variation=VISIBLE_PASSWORD ;;
        *) variation=$(printf '0x%x' $((it & 0xff0))) ;;
    esac
    printf 'class %d, variation %s, auto-correct %s, no personalised learning %s' \
        $((it & 0xf)) "$variation" "$([ $((it & AUTO_CORRECT)) -ne 0 ] && echo on || echo off)" \
        "$([ $((io & NO_LEARNING)) -ne 0 ] && echo yes || echo NO)"
}

# focused ID PACKAGE: the field ID gets the focus; then EDITOR holds the reading of a new input
# connection to PACKAGE.
focused() {
    local i conn pkg
    pva_tap "$1" || return 1
    for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
        pv_sleep 0.5
        EDITOR=$(editor)
        pkg=$(printf '%s' "$EDITOR" | cut -d' ' -f3); conn=$(printf '%s' "$EDITOR" | cut -d' ' -f4)
        if [ "$pkg" = "$2" ] && [ "$conn" != "?" ] && [ "$conn" != "$LAST_CONNECTION" ] && pva_keyboard_shown; then
            LAST_CONNECTION=$conn
            return 0
        fi
    done
    pv_log "$1: no new input connection to $2 (reading: $EDITOR; the one before: $LAST_CONNECTION)"
    return 1
}

# check ID KIND: KIND is secret, username, website or text.
check() {
    local id=$1 kind=$2 it io bad=""
    focused "$id" "$PV_PACKAGE" || return 1
    it=$(printf '%s' "$EDITOR" | cut -d' ' -f1); io=$(printf '%s' "$EDITOR" | cut -d' ' -f2)
    echo "  $id: inputType=$it imeOptions=$io ($(describe "$it" "$io"))"
    [ $((io & NO_LEARNING)) -ne 0 ] || bad="$bad no IME_FLAG_NO_PERSONALIZED_LEARNING;"
    [ $((it & 0xf)) -eq 1 ] || bad="$bad not a text field;"
    case "$kind" in
        secret) [ $((it & 0xff0)) -eq $((0x80)) ] || bad="$bad not a password field;" ;;
        username) [ $((it & 0xff0)) -eq $((0x20)) ] || bad="$bad not an e-mail field;" ;;
        website) [ $((it & 0xff0)) -eq $((0x10)) ] || bad="$bad not a URI field;" ;;
    esac
    case "$kind" in
        secret | username | website) [ $((it & AUTO_CORRECT)) -eq 0 ] || bad="$bad auto-correct is on;" ;;
    esac
    [ -z "$bad" ] || { pv_log "$id:$bad"; return 1; }
}

field() { # ID KIND WHAT
    pv_step "keyboard: $1, $3: $(expectation "$2")" -- check "$1" "$2"
}

expectation() {
    case "$1" in
        secret) echo "no learning, a password field, no auto-correct" ;;
        username) echo "no learning, an e-mail field, no auto-correct" ;;
        website) echo "no learning, a URI field, no auto-correct" ;;
        *) echo "no learning" ;;
    esac
}

# The control: a platform text field without the flag, and a platform password field.
control() {
    local it io
    win_control_start || return 1
    focused control_text "$WIN_HELPER" || return 1
    it=$(printf '%s' "$EDITOR" | cut -d' ' -f1); io=$(printf '%s' "$EDITOR" | cut -d' ' -f2)
    echo "  control_text: inputType=$it imeOptions=$io ($(describe "$it" "$io"))"
    [ $((io & NO_LEARNING)) -eq 0 ] || { pv_log "the control's text field has the flag: the reading proves nothing"; return 1; }
    focused control_password "$WIN_HELPER" || return 1
    it=$(printf '%s' "$EDITOR" | cut -d' ' -f1); io=$(printf '%s' "$EDITOR" | cut -d' ' -f2)
    echo "  control_password: inputType=$it imeOptions=$io ($(describe "$it" "$io"))"
    [ $((it & 0xff0)) -eq $((0x80)) ] && [ $((io & NO_LEARNING)) -eq 0 ] \
        || { pv_log "the control's password field is not read as one without the flag"; return 1; }
}

pv_step "control: a platform field has no such flag, and the focused field is the one read" -- control || win_end
pva_hide_keyboard
pv_step "the app installed afresh: Welcome" -- win_app_fresh || win_end
echo "keyboard.sh: the $VARIANT build"

# ---- setup -------------------------------------------------------------------------------------
pv_step "setup: a password of one's own" -- win_to_own || win_end
field pv.setup.own secret "setup, the own master password"
pv_step "setup: typed, used, not used elsewhere, written down" -- eval 'pva_type pv.setup.own "$WIN_MASTER" && win_use_own && win_reuse_no && win_to_again' || win_end
field pv.setup.again secret "setup, the master password again"
pv_step "setup: the vault is made" -- eval 'pva_type pv.setup.again "$WIN_MASTER" && win_create_vault' || win_end

# ---- the list and the form ---------------------------------------------------------------------
field pv.list.search text "the list's search field"
pv_step "the form opens" -- win_open_form || win_end
field pv.edit.name text "the form's name"
field pv.edit.username username "the form's username"
field pv.edit.password secret "the form's password"
field pv.edit.website.0 website "the form's website"
field pv.edit.notes text "the form's notes"
if pv_step "the form's \"Add field\" dialog" -- win_field_dialog; then
    field pv.edit.addField.label text "the dialog's label"
    field pv.edit.addField.value secret "the dialog's secret value"
    if pv_step "a secret field is added to the form" -- eval 'pva_type pv.edit.addField.label "$WIN_LABEL" && pva_type pv.edit.addField.value "$WIN_VALUE" && win_field_add'; then
        field pv.edit.field.0 secret "the form's secret field"
    fi
fi

# ---- the lock screen ---------------------------------------------------------------------------
if pv_step "the form is closed and the app locked" -- eval 'win_close_form && win_lock'; then
    field pv.lock.password secret "the lock screen's master password"
fi
win_end
