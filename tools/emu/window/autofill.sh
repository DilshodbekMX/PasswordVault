#!/usr/bin/env bash
# tools/emu/window/autofill.sh - build plan 3.12, [script]: no fill request and no save request for
# any text field of the real setup, lock and edit screens (architecture.md §8.4; lock.md §13.5;
# ux.md §3; [V-P4]). The method is the window lab's af_scenario.py (docs/lab/window-input.md,
# Experiment 2): the helper app's logging autofill service is made the device's autofill service; it
# answers every fill request with a SaveInfo over all text fields, as a password manager's service
# does, so that Android offers to save what was typed.
#
#   tools/emu/window/autofill.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37; the helper app needs API 26). The APK
#       defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk; the staging APK is
#       checked the same way.
#
# The walk types into every field and leaves every screen: setup (the own master password, the
# question in its dialog, the password again), the list's search field, the form (name, username,
# password, website, notes, and the label and the secret value of the "Add field" dialog), the lock
# screen, and Home. After each part, for the app:
#   - Android started no autofill session (`dumpsys autofill`, "Requests history");
#   - the service got no fill request and no save request (the helper's files/autofill.log);
#   - Android showed no "Save ... to LabFill?" prompt.
# Then the text toolbar, with something on the clipboard so that it has "Paste" to show: a field of
# the activity's window, a password field, and both fields of the dialog (a window of its own) offer no
# "Autofill" item, which would send a manual request even for a field that opted out.
# The controls are the helper app's own platform fields, on the same device: focusing one starts a
# session and a fill request; after typing and leaving, Android offers to save, and Save sends the
# service the typed text; the toolbar's "Autofill" item sends a manual request.
# Prints PASS or FAIL per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a usage
# error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin autofill.sh "$@"

SERVICE=$WIN_HELPER/.LabAutofillService
CONTROL_USER=ctrlUserJJJ
CONTROL_SECRET=CtrlSecretKKK5

# requests KIND PACKAGE: how many fill requests (FILL) or save requests (SAVE) the service got from PACKAGE.
requests() { win_file "autofill.log" | grep -c "^$1 [0-9]* package=$2 "; }

# sessions PACKAGE: how many autofill sessions Android started for an activity of PACKAGE.
sessions() {
    pv_shell dumpsys autofill 2>/dev/null | tr -d '\r' \
        | awk '/^Requests history:/ { f = 1; next } f && /history:$/ { f = 0 } f' | grep -c "a={$1/"
}

save_prompt() { pv_ui_nodes | pv_ui_match '[|]autofill_save_yes$' | grep -q .; }

# wait_for SECONDS COMMAND...: COMMAND becomes true within SECONDS.
wait_for() {
    local deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        [ $SECONDS -lt $deadline ] || return 1
        pv_sleep 0.5
    done
}

set_service() {
    win_setting_set secure autofill_service "$SERVICE" || return 1
    [ "$(pv_shell settings get secure autofill_service | tr -d '\r')" = "$SERVICE" ] || return 1
    pv_shell dumpsys autofill 2>/dev/null | grep -q "LabAutofillService" || { pv_log "dumpsys autofill does not name the lab's service"; return 1; }
}

# ---- the controls: the helper's own platform fields ---------------------------------------------
control_fill() {
    win_file_rm autofill.log
    win_control_start || return 1
    pva_tap control_text || return 1
    wait_for 15 eval '[ "$(requests FILL "$WIN_HELPER")" -ge 1 ]' || { pv_log "no fill request for the control: $(win_file autofill.log | head -n 3)"; return 1; }
    [ "$(sessions "$WIN_HELPER")" -ge 1 ] || { pv_log "dumpsys autofill shows no session for the control"; return 1; }
}

control_save() {
    pv_shell input text "$CONTROL_USER" >/dev/null
    pva_tap control_password || return 1
    pv_sleep 1
    pv_shell input text "$CONTROL_SECRET" >/dev/null
    pv_sleep 1
    pva_hide_keyboard
    pv_shell input keyevent KEYCODE_BACK >/dev/null # the control screen ends
    wait_for 15 save_prompt || { pv_log "no save prompt after the control screen"; return 1; }
    pv_ui_tap '[|]autofill_save_yes$' >/dev/null || return 1
    wait_for 15 eval '[ "$(requests SAVE "$WIN_HELPER")" -ge 1 ]' || { pv_log "no save request for the control"; return 1; }
    win_file autofill.log | grep -q "^NODE SAVE .* value=\"$CONTROL_SECRET\"" \
        || { pv_log "the save request does not hold the control's password: $(win_file autofill.log | grep '^NODE SAVE' | head -n 4)"; return 1; }
}

# The label of the toolbar's Autofill item: the platform's own word in the device's language, and
# the English word, which Compose's own item has on these devices.
AUTOFILL_LABEL=""
is_autofill_item() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        autofill | "$(printf '%s' "$AUTOFILL_LABEL" | tr '[:upper:]' '[:lower:]')") return 0 ;;
    esac
    return 1
}

# toolbar: reads the text toolbar that is on the screen, through the helper's accessibility service
# (the toolbar is a window of its own). TOOLBAR_ITEMS gets its labels, one per line, those behind its
# "More options" button too; TOOLBAR_AUTOFILL the screen position "X Y" of an Autofill item, or nothing.
toolbar() {
    local dump box x1 y1 x2 y2 round label
    TOOLBAR_ITEMS=""
    TOOLBAR_AUTOFILL=""
    for round in 1 2; do
        dump=$(win_a11y_dump tool) || return 1
        while IFS=$'\t' read -r label box; do
            [ -n "$label" ] || continue
            TOOLBAR_ITEMS="$TOOLBAR_ITEMS$label"$'\n'
            if is_autofill_item "$label"; then
                read -r x1 y1 x2 y2 <<<"$(printf '%s' "$box" | sed 's/[][,]/ /g')"
                TOOLBAR_AUTOFILL="$(((x1 + x2) / 2)) $(((y1 + y2) / 2))"
            fi
        done < <(printf '%s\n' "$dump" | awk -F'\t' "$WIN_AWK_VAL"' $5 == "android:id/floating_toolbar_menu_item_text" { print val("text") "\t" val("bounds") }')
        box=$(printf '%s\n' "$dump" | awk -F'\t' "$WIN_AWK_VAL"' $5 == "android:id/overflow" { print val("bounds"); exit }')
        [ "$round" = 1 ] && [ -n "$box" ] || break
        read -r x1 y1 x2 y2 <<<"$(printf '%s' "$box" | sed 's/[][,]/ /g')"
        pv_shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2)) >/dev/null
        pv_sleep 1.5
    done
    TOOLBAR_ITEMS=$(printf '%s' "$TOOLBAR_ITEMS" | sort -u)
}

control_manual() {
    local before
    AUTOFILL_LABEL=$(win_cmd string --es name autofill) || return 1
    win_control_start --es clip pvWindowClip || return 1
    # The focus itself starts a session with a fill request of its own; the manual one comes after it.
    pva_tap control_text && pv_sleep 2 && win_long_press control_text || return 1
    toolbar || return 1
    echo "  the control field's toolbar: $(printf '%s' "$TOOLBAR_ITEMS" | paste -sd, -)"
    [ -n "$TOOLBAR_AUTOFILL" ] || { pv_log "the control field's toolbar has no Autofill item (label '$AUTOFILL_LABEL')"; return 1; }
    before=$(requests FILL "$WIN_HELPER")
    pv_shell input tap $TOOLBAR_AUTOFILL >/dev/null
    wait_for 15 manual_request_after "$before" \
        || { pv_log "the Autofill item sent no manual fill request: $(win_file autofill.log | grep '^FILL' | tail -n 2 | tr '\n' ';')"; return 1; }
}

# manual_request_after COUNT: a fill request of the control after the first COUNT is a manual one.
manual_request_after() {
    win_file autofill.log | grep "^FILL [0-9]* package=$WIN_HELPER " | tail -n +$(($1 + 1)) | grep -q MANUAL_REQUEST
}

# ---- the app -----------------------------------------------------------------------------------
# nothing_sent: for the app, no session, no fill request, no save request, and no save prompt now.
# When Android does offer to save, Save is tapped, so that the message shows what the service got.
nothing_sent() {
    local s f v rc=0
    pv_sleep 1
    if save_prompt; then
        pv_log "Android offers to save what was typed"
        pv_ui_tap '[|]autofill_save_yes$' >/dev/null
        pv_sleep 3
        rc=1
    fi
    s=$(sessions "$PV_PACKAGE"); f=$(requests FILL "$PV_PACKAGE"); v=$(requests SAVE "$PV_PACKAGE")
    if [ "$s" != 0 ] || [ "$f" != 0 ] || [ "$v" != 0 ]; then
        pv_log "for the app: $s sessions, $f fill requests, $v save requests; the service was sent: $(win_file autofill.log | grep -A 40 "package=$PV_PACKAGE " | grep -E '^(FILL|SAVE)|type=TEXT' | cut -c1-240 | head -n 12 | tr '\n' ';')"
        rc=1
    fi
    return $rc
}

# no_autofill_item ID: with the field ID focused and long-pressed, its toolbar shows (it has Paste)
# and has no Autofill item, and the service gets no request.
no_autofill_item() {
    pva_tap "$1" && pv_sleep 1 && win_long_press "$1" || return 1
    toolbar || return 1
    echo "  $1: the toolbar has: $(printf '%s' "$TOOLBAR_ITEMS" | paste -sd, -)"
    [ -n "$TOOLBAR_ITEMS" ] || { pv_log "$1: no toolbar was shown, so nothing was checked"; return 1; }
    [ -z "$TOOLBAR_AUTOFILL" ] || { pv_log "$1: the toolbar offers Autofill"; pv_shell input tap $TOOLBAR_AUTOFILL >/dev/null; pv_sleep 2; nothing_sent; return 1; }
    pva_tap "$1" # a tap in the field closes its toolbar
    nothing_sent
}

pv_step "the lab's logging service is the device's autofill service" -- set_service || win_end
pv_step "control: a platform field starts a session and a fill request" -- control_fill || win_end
pv_step "control: after typing and leaving, Save sends the service the typed password" -- control_save || win_end

pv_step "the app installed afresh: Welcome" -- win_app_fresh || win_end
echo "autofill.sh: the $VARIANT build"

# ---- setup -------------------------------------------------------------------------------------
pv_step "setup: the own master password typed, used, the question answered" -- eval 'win_to_own && pva_type pv.setup.own "$WIN_MASTER" && win_use_own && win_reuse_no' || win_end
pv_step "autofill: setup, pv.setup.own left: no session, no fill or save request, no save prompt" -- nothing_sent
pv_step "setup: the master password typed again, the vault made" -- eval 'win_to_again && pva_type pv.setup.again "$WIN_MASTER" && win_create_vault' || win_end
pv_step "autofill: setup, pv.setup.again left: no session, no fill or save request, no save prompt" -- nothing_sent

# ---- the list and the form ---------------------------------------------------------------------
pv_step "the list: a search typed, the form opened" -- eval 'pva_type pv.list.search "$WIN_QUERY" && pva_tap pv.list.searchClear && win_open_form' || win_end
pv_step "autofill: the list, pv.list.search left: no session, no fill or save request, no save prompt" -- nothing_sent
pv_step "the form: name, username, password, website and notes typed" -- eval 'pva_type pv.edit.name "$WIN_TITLE" && pva_type pv.edit.username "$WIN_USER" && pva_type pv.edit.password "$WIN_PASS" && pva_type pv.edit.website.0 "$WIN_SITE" && pva_type pv.edit.notes "$WIN_NOTE"' || win_end
pv_step "the form's dialog: a label and a secret value typed, the field added" -- eval 'win_field_dialog && pva_type pv.edit.addField.label "$WIN_LABEL" && pva_type pv.edit.addField.value "$WIN_VALUE" && win_field_add' || win_end
pv_step "autofill: the dialog, pv.edit.addField.label and .value left: no session, no fill or save request, no save prompt" -- nothing_sent
pv_step "the form saved: the account's screen" -- win_save_form || win_end
pv_step "autofill: the form, its seven fields left: no session, no fill or save request, no save prompt" -- nothing_sent

# ---- the lock screen ---------------------------------------------------------------------------
pv_step "back to the list, locked, the master password typed, unlocked" -- eval 'pv_shell input keyevent KEYCODE_BACK >/dev/null; win_lock && win_unlock' || win_end
pv_step "autofill: the lock screen, pv.lock.password left: no session, no fill or save request, no save prompt" -- nothing_sent
pv_shell input keyevent KEYCODE_HOME >/dev/null
pv_sleep 2
pv_step "autofill: the app left with Home: no session, no fill or save request, no save prompt" -- nothing_sent

# ---- the text toolbar --------------------------------------------------------------------------
# Only from here on is an accessibility service running: the toolbar is read through it.
pv_step "the helper's accessibility service (a tool) is on, to read the text toolbar" -- win_a11y_enable tool || win_end
pv_step "control: the toolbar's Autofill item of a platform field sends a manual request" -- control_manual
pv_step "the app is in front again and the form open" -- eval 'win_front && win_open_form' || win_end
pv_step "autofill: the toolbar of pv.edit.name (the activity's window) has Paste and no Autofill item" -- no_autofill_item pv.edit.name
pv_step "autofill: the toolbar of pv.edit.password (a password field) has Paste and no Autofill item" -- no_autofill_item pv.edit.password
if pv_step "the form's dialog is open" -- win_field_dialog; then
    pv_step "autofill: the toolbar of pv.edit.addField.label (the dialog's window) has Paste and no Autofill item" -- no_autofill_item pv.edit.addField.label
    pv_step "autofill: the toolbar of pv.edit.addField.value (the dialog's window) has Paste and no Autofill item" -- no_autofill_item pv.edit.addField.value
fi

win_file autofill.log > "$WIN_OUT/autofill-$VARIANT.log"
echo "  the service's record: $(grep -c '^FILL' "$WIN_OUT/autofill-$VARIANT.log") fill and $(grep -c '^SAVE' "$WIN_OUT/autofill-$VARIANT.log") save requests in all, $(grep -c -E "^(FILL|SAVE) [0-9]* package=$PV_PACKAGE " "$WIN_OUT/autofill-$VARIANT.log") of them from the app ($WIN_OUT/autofill-$VARIANT.log)"
win_end
