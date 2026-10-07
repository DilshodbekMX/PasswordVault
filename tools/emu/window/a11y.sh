#!/usr/bin/env bash
# tools/emu/window/a11y.sh - build plan 3.12, [script]: what accessibility services read of the secrets
# on the real setup, edit and lock screens (architecture.md §8.4; lock.md §2.3, §13.5; ux.md §3;
# [V-P5]). The method is the window lab's (docs/lab/window-input.md, Experiment 4, "Accessibility
# data sensitivity"): the helper app's LabA11yService, which does not declare itself an accessibility
# tool, is turned on and asked for every node of every window, and writes down every event it gets.
#
#   tools/emu/window/a11y.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37; the helper app needs API 26). The APK
#       defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk; the staging APK is
#       checked the same way.
#
# Three observers are compared, and for each secret the script prints what each of them reads:
#   - the service that is not an accessibility tool (isAccessibilityTool false): the one
#     architecture.md §8.4 says must see no secret text;
#   - the same service declared as an accessibility tool, as a screen reader is;
#   - uiautomator (the shell's UiAutomation, read as tools/emu/adb.sh's pv_ui_nodes reads it).
# The secrets: the six suggested words and the password they make, also after "Other words"; the own
# master password in its field, drawn as dots, and after "Show password"; the password shown on "Write
# it down on paper"; the master password typed again; in the form the account's password, a secret
# value in the "Add field" dialog (a window of its own) and in the form; the generator's suggestion (a
# bottom sheet, a window of its own); the master password on the lock screen.
#
# One step per secret: "the service that is not a tool reads nothing of it". It reads nothing when the
# secret is in none of its nodes (text, description and the other texts of a node, and the node with
# the input focus asked for directly) and in none of its events.
#   - On Android 14 and newer (isSensitiveData and the window's setAccessibilityDataSensitive take
#     effect) that must hold for every secret: PASS or FAIL.
#   - Before Android 14 nothing keeps an accessibility service out, which lock.md §2.3 accepts. A
#     field drawn as dots must still not give its text away (a platform password field gives mask
#     characters): PASS or FAIL. A secret that is on the screen for the user to read is readable by
#     the service too: printed as KNOWN, which is not a failure (PV_A11Y_BEFORE_34=fail makes it one).
# What the tool service and uiautomator read is printed and summed up at the end; it fails no step,
# because architecture.md §8.4 and lock.md §13.5 speak of the service that is not a tool.
# The control is the helper app's own screen: the service that is not a tool reads the text typed
# into a platform text field, as a node and in its events, so it is connected and reading.
# While the app is used, the screen is read through the helper's tool service instead of
# UiAutomation, which would disconnect both services at every reading and lose their events.
# Prints PASS, FAIL or KNOWN per secret and a summary; exit 0 when no step failed, 1 otherwise, 2 for
# a usage error, 3 for a refused serial. Every observer's record is kept under build/window/SERIAL/.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin a11y.sh "$@"

BEFORE_34=${PV_A11Y_BEFORE_34:-known}
CONTROL_TEXT=ctrlSeenNNN
CONTROL_SECRET=CtrlSeenPPP6
KNOWN=0
STATION=0
MARK_PLAIN=0
MARK_TOOL=0
HIDDEN_FIELDS=0
HIDDEN_TOOL=0
HIDDEN_UI=0

# ---- the screen read through the tool service ------------------------------------------------------
# uiautomator's own reading is kept under another name; it is used once per secret, as an observer.
eval "$(declare -f pv_ui_nodes | sed '1s/^pv_ui_nodes/pv_ui_nodes_uiautomator/')"

# One reading of the active window from the tool service's dump, in pv_ui_nodes's form: only nodes
# shown to the user (and nothing below a node that is not), their bounds cut to the window.
tool_nodes() {
    win_a11y_dump tool | awk -F'\t' "$WIN_AWK_VAL"'
        function max(a, b) { return a > b ? a : b }
        function min(a, b) { return a < b ? a : b }
        $1 == "WINDOW" { active = 0; for (i = 1; i <= NF; i++) if ($i == "active=true") active = 1; first = 1; skip = -1; next }
        $1 != "NODE" || !active { next }
        {
            depth = $2 + 0
            if (skip >= 0 && depth > skip) next
            skip = -1
            if (!first && val("visible") != "true") { skip = depth; next }
            box = val("bounds"); gsub(/[][,]/, " ", box); split(box, b, " ")
            if (first) { wl = b[1]; wt = b[2]; wr = b[3]; wb = b[4]; first = 0 }
            l = max(b[1], wl); t = max(b[2], wt); r = min(b[3], wr); bt = min(b[4], wb)
            if (l >= r || t >= bt) { x = 0; y = 0 } else { x = int((l + r) / 2); y = int((t + bt) / 2) }
            id = $5; sub(/.*\//, "", id); if (id == "null") id = ""
            text = val("text"); gsub(/\\n/, " ", text); gsub(/\|/, "/", text)
            desc = val("desc"); gsub(/\\n/, " ", desc); gsub(/\|/, "/", desc)
            print text "|" desc "|" id "|" val("clickable") "|" val("enabled") "|" x "|" y
        }'
}

# pv_ui_nodes, for every pva_* function of app.sh: read again until two readings agree, as adb.sh does.
pv_ui_nodes() {
    local a b i
    a=$(tool_nodes) || return 1
    for i in 1 2 3 4 5 6; do
        pv_sleep 0.3
        b=$(tool_nodes) || break
        [ "$a" != "$b" ] || break
        a=$b
    done
    printf '%s\n' "$a"
}

# ---- the observers ---------------------------------------------------------------------------------
# mark: from here on the services' events belong to the next secret.
mark() {
    local counts
    counts=$(pv_shell "run-as $WIN_HELPER sh -c 'cat files/a11y-plain-events.log 2>/dev/null | wc -l; cat files/a11y-tool-events.log 2>/dev/null | wc -l'" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
    MARK_PLAIN=$(printf '%s' "$counts" | awk '{ print $1 + 0 }')
    MARK_TOOL=$(printf '%s' "$counts" | awk '{ print $2 + 0 }')
}

# watch_type ID TEXT: TEXT typed into the field ID, with both services connected and the mark set
# just before the first key.
watch_type() {
    pva_tap "$1" || return 1
    pv_sleep 1
    win_a11y_wait 10 || return 1
    mark
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    pv_shell input text "$2" >/dev/null
    pv_sleep 1
}

# seen PACKAGE WHAT ID SECRET...: takes every observer's record of the screen as it is and of the
# events since the mark, prints what each reads of the SECRETs (a11y_seen.py), and sets SEEN_PLAIN,
# SEEN_TOOL and SEEN_UI to seen or clean.
seen() {
    local package=$1 what=$2 id=$3 base out s args=()
    shift 3
    STATION=$((STATION + 1))
    base=$WIN_OUT/a11y-$VARIANT-$(printf '%02d' "$STATION")
    pv_sleep 1
    win_a11y_wait 10 || return 1
    win_a11y_dump plain >"$base-plain-dump.txt" || return 1
    win_a11y_dump tool >"$base-tool-dump.txt" || return 1
    win_file a11y-plain-events.log | tail -n +$((MARK_PLAIN + 1)) >"$base-plain-events.txt"
    win_file a11y-tool-events.log | tail -n +$((MARK_TOOL + 1)) >"$base-tool-events.txt"
    pv_ui_nodes_uiautomator >"$base-ui.txt" # last: it disconnects both services for a moment
    win_a11y_wait 15 || return 1
    for s in "$@"; do args+=(--secret "$s"); done
    out=$(python3 "$WIN_DIR/a11y_seen.py" --package "$package" --what "$what" --id "$id" "${args[@]}" \
        --plain-dump "$base-plain-dump.txt" --plain-events "$base-plain-events.txt" \
        --tool-dump "$base-tool-dump.txt" --tool-events "$base-tool-events.txt" --ui "$base-ui.txt") || return 1
    printf '%s\n' "$out" | grep -v '^RESULT '
    SEEN_PLAIN=$(printf '%s\n' "$out" | sed -n 's/^RESULT plain=\([a-z]*\) .*/\1/p')
    SEEN_TOOL=$(printf '%s\n' "$out" | sed -n 's/^RESULT .* tool=\([a-z]*\) .*/\1/p')
    SEEN_UI=$(printf '%s\n' "$out" | sed -n 's/^RESULT .* ui=\([a-z]*\)$/\1/p')
    [ -n "$SEEN_PLAIN" ] && [ -n "$SEEN_TOOL" ] && [ -n "$SEEN_UI" ]
}

# judge KIND WHERE ID SECRET...: one secret's step. KIND is hidden (a field drawn as dots) or shown
# (on the screen for the user to read).
judge() {
    local kind=$1 where=$2 id=$3 name
    shift 3
    case "$kind" in
        hidden) name="a11y: $where, $id, drawn as dots: the service that is not a tool reads nothing of it" ;;
        *) name="a11y: $where, $id, shown on the screen: the service that is not a tool reads nothing of it" ;;
    esac
    if ! seen "$PV_PACKAGE" "$id" "$id" "$@"; then
        pv_report FAIL "$name" "the observers could not be read"
        return 1
    fi
    if [ "$kind" = hidden ]; then
        HIDDEN_FIELDS=$((HIDDEN_FIELDS + 1))
        [ "$SEEN_TOOL" = clean ] || HIDDEN_TOOL=$((HIDDEN_TOOL + 1))
        [ "$SEEN_UI" = clean ] || HIDDEN_UI=$((HIDDEN_UI + 1))
    fi
    if [ "$SEEN_PLAIN" = clean ]; then
        pv_report PASS "$name"
    elif [ "$API" -ge 34 ] || [ "$kind" = hidden ] || [ "$BEFORE_34" = fail ]; then
        pv_report FAIL "$name" "it reads the secret"
        return 1
    else
        KNOWN=$((KNOWN + 1))
        echo "KNOWN a11y: $where, $id, shown on the screen: the service that is not a tool reads it; before Android 14 an accessibility service that the user turned on is not kept out (lock.md §2.3)"
    fi
}

# ---- the control ---------------------------------------------------------------------------------
control() {
    win_control_start || return 1
    watch_type control_text "$CONTROL_TEXT" || return 1
    seen "$WIN_HELPER" "control: a platform text field" control_text "$CONTROL_TEXT" || return 1
    [ "$SEEN_PLAIN" = seen ] || { pv_log "the service that is not a tool does not read the control's typed text"; return 1; }
    grep -q "^EVENT.*text=.*$CONTROL_TEXT" "$WIN_OUT/a11y-$VARIANT-$(printf '%02d' "$STATION")-plain-events.txt" \
        || { pv_log "the service that is not a tool got no event with the control's typed text"; return 1; }
    # For comparison: what a platform password field gives each observer, once it no longer shows the
    # character typed last.
    watch_type control_password "$CONTROL_SECRET" || return 1
    pv_sleep 2
    seen "$WIN_HELPER" "control: a platform password field" control_password "$CONTROL_SECRET" || return 1
    PLATFORM_PASSWORD="the service that is not a tool: $SEEN_PLAIN, the tool service: $SEEN_TOOL, uiautomator: $SEEN_UI"
}

# ---- the app's secrets -----------------------------------------------------------------------------
# words: PASSPHRASE becomes the suggested password as it is shown, ROWS the lines of the numbered
# list that are on the screen ("Word 1 of 6: ..."), as uiautomator reads them.
words() {
    local line
    pva_reveal pv.setup.passphrase || return 1
    PASSPHRASE=$(pva_text pv.setup.passphrase) || return 1
    ROWS=()
    while IFS= read -r line; do ROWS+=("$line"); done < <(pv_ui_nodes | awk -F'|' '
        $3 == "pv.setup.words" { f = 1; next }
        f && $3 ~ /^pv[.]/ { exit }
        f && $2 != "" { print $2 }')
    printf '%s\n' "$PASSPHRASE" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' || { pv_log "not six words in pv.setup.passphrase"; return 1; }
    [ ${#ROWS[@]} -ge 1 ] || { pv_log "no line of the numbered list is on the screen"; return 1; }
}

other_words() {
    local before=$PASSPHRASE
    win_a11y_wait 10 || return 1
    mark
    pva_tap pv.setup.otherWords || return 1
    pv_sleep 2
    words || return 1
    [ "$PASSPHRASE" != "$before" ] || { pv_log "\"Other words\" left the same words"; return 1; }
}

# The show/hide button of a password field has no test tag: found by its description, the app's own
# text common_show_password.
show_password() {
    local label line
    label=$(win_app_string common_show_password) || { pv_log "the APK has no string common_show_password"; return 1; }
    pva_hide_keyboard
    line=$(pv_ui_nodes | awk -F'|' -v d="$label" '$2 == d { print; exit }')
    [ -n "$line" ] || { pv_log "no button described as '$label'; ids: $(pva_ids)"; return 1; }
    win_a11y_wait 10 || return 1
    mark
    pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$(printf '%s\n' "$line" | cut -d'|' -f7)" >/dev/null
    pv_sleep 1
}

# generated: GENERATED becomes the generator's suggestion, one without "/" (the reading of the
# screen writes a "|" as "/").
generated() {
    local i
    for i in 1 2 3 4 5 6; do
        GENERATED=$(pva_text pv.gen.preview) || return 1
        case "$GENERATED" in
            */* | "") pva_tap pv.gen.new || return 1; pv_sleep 1 ;;
            *) [ ${#GENERATED} -ge 8 ] && return 0 ;;
        esac
    done
    pv_log "no usable suggestion in the generator's sheet"
    return 1
}

pv_step "both accessibility services of the helper are on" -- win_a11y_enable plain tool || win_end
VARIANT=control
PLATFORM_PASSWORD=""
pv_step "control: the service that is not a tool reads a platform field's typed text, as a node and in its events" -- control || win_end
echo "  for comparison, the typed text of a platform password field: $PLATFORM_PASSWORD"
pv_step "the app installed afresh: Welcome" -- win_app_fresh || win_end
echo "a11y.sh: the $VARIANT build"

# ---- setup -------------------------------------------------------------------------------------
PASSPHRASE=""
ROWS=()
pv_step "setup: the six suggested words are shown" -- eval 'win_a11y_wait 10 && mark && pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword && words' || win_end
judge shown "setup, the suggested words and the password they make" pv.setup.passphrase "$PASSPHRASE" "${ROWS[@]}"
if pv_step "setup: \"Other words\" changes them on the screen" -- other_words; then
    judge shown "setup, the other words, changed on the screen" pv.setup.passphrase "$PASSPHRASE" "${ROWS[@]}"
fi
pv_step "setup: a password of one's own typed" -- eval 'pva_hide_keyboard; pva_reveal pv.setup.typeOwn && pva_tap pv.setup.typeOwn && pva_need pv.setup.own && watch_type pv.setup.own "$WIN_MASTER"' || win_end
judge hidden "setup, the own master password" pv.setup.own "$WIN_MASTER"
if pv_step "setup: \"Show password\" tapped" -- show_password; then
    judge shown "setup, the own master password after \"Show password\"" pv.setup.own "$WIN_MASTER"
fi
pv_step "setup: the password used: \"Write it down on paper\" shows it" -- eval 'win_a11y_wait 10 && mark && win_use_own && win_reuse_no' || win_end
judge shown "setup, the password on \"Write it down on paper\"" pv.setup.chosen "$WIN_MASTER"
pv_step "setup: the master password typed again" -- eval 'win_to_again && watch_type pv.setup.again "$WIN_MASTER"' || win_end
judge hidden "setup, the master password again" pv.setup.again "$WIN_MASTER"
pv_step "setup: the vault is made" -- win_create_vault || win_end

# ---- the form ----------------------------------------------------------------------------------
pv_step "the form: the account's password typed" -- eval 'win_open_form && pva_type pv.edit.name "$WIN_TITLE" && watch_type pv.edit.password "$WIN_PASS"' || win_end
judge hidden "the form, the account's password" pv.edit.password "$WIN_PASS"
pv_step "the form's dialog (a window of its own): a secret value typed" -- eval 'win_field_dialog && pva_type pv.edit.addField.label "$WIN_LABEL" && watch_type pv.edit.addField.value "$WIN_VALUE"' || win_end
judge hidden "the form's dialog, the secret value" pv.edit.addField.value "$WIN_VALUE"
pv_step "the secret field is added to the form" -- eval 'win_a11y_wait 10 && mark && win_field_add' || win_end
judge hidden "the form, the added secret field" pv.edit.field.0 "$WIN_VALUE"
GENERATED=""
pv_step "the generator's sheet (a window of its own) shows a password" -- eval 'win_a11y_wait 10 && mark && win_generator && generated' || win_end
judge shown "the generator's sheet, the suggested password" pv.gen.preview "$GENERATED"

# ---- the lock screen ---------------------------------------------------------------------------
pv_step "the sheet and the form closed, the app locked, the master password typed" -- eval 'pv_shell input keyevent KEYCODE_BACK >/dev/null; pv_sleep 1; win_close_form && win_lock && watch_type pv.lock.password "$WIN_MASTER"' || win_end
judge hidden "the lock screen, the master password" pv.lock.password "$WIN_MASTER"

# ---- what else was seen ------------------------------------------------------------------------
win_file a11y-plain-events.log >"$WIN_OUT/a11y-$VARIANT-plain-events.log"
win_file a11y-tool-events.log >"$WIN_OUT/a11y-$VARIANT-tool-events.log"
echo "  of the $HIDDEN_FIELDS fields drawn as dots, the service declared as a tool read the text in plain at $HIDDEN_TOOL and uiautomator at $HIDDEN_UI (a platform password field: $PLATFORM_PASSWORD)"
PLAIN_EVENTS=$(grep -c "^EVENT	[A-Z_]*	$PV_PACKAGE	" "$WIN_OUT/a11y-$VARIANT-plain-events.log")
if [ "$API" -ge 34 ]; then
    # Events of nodes that are not marked sensitive still reach it: screen titles and the like.
    echo "  from the app, the service that is not a tool got $PLAIN_EVENTS events with a text in the whole run; their texts: $(awk -F'\t' -v pkg="$PV_PACKAGE" "$WIN_AWK_VAL"' $1 == "EVENT" && $3 == pkg { t = val("text"); if (t == "") t = val("desc"); if (t != "" && !(t in seen)) { seen[t] = 1; n++; if (n <= 12) printf "%s\"%s\"", (n > 1 ? ", " : ""), substr(t, 1, 60) } } END { if (n > 12) printf " and %d more", n - 12; if (n == 0) printf "none" }' "$WIN_OUT/a11y-$VARIANT-plain-events.log")"
else
    echo "  from the app, the service that is not a tool got $PLAIN_EVENTS events with a text in the whole run ($WIN_OUT/a11y-$VARIANT-plain-events.log)"
fi
[ "$KNOWN" -eq 0 ] || echo "  $KNOWN secrets shown on the screen were readable by the service that is not a tool: KNOWN, before Android 14 (lock.md §2.3)"
win_end
