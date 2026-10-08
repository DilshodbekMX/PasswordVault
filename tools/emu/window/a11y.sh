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
# One step per secret. An observer reads a secret when it is in one of its nodes (text, description
# and the other texts of a node, and the node with the input focus asked for directly) or in one of
# its events.
#   - A field drawn as dots: no observer at all, on any version of Android, may read even one
#     character of it: the service that is not a tool, the tool service (a screen reader) and
#     uiautomator alike. Its node's text, in the dumps and in its text events (changed, selection
#     changed, traversed), must be mask characters only, and the node must still say what the
#     platform's dots say: a password field with as many characters as it holds. PASS or FAIL
#     (VaultSecureField's maskedForAccessibility; architecture.md §8.4, lock.md §13.5).
#   - A secret on the screen for the user to read: "the service that is not a tool reads nothing of
#     it". On Android 14 and newer (isSensitiveData and the window's setAccessibilityDataSensitive
#     take effect) PASS or FAIL. Before Android 14 nothing keeps an accessibility service out, which
#     lock.md §2.3 accepts: printed as KNOWN, which is not a failure (PV_A11Y_BEFORE_34=fail makes it
#     one). What the tool service and uiautomator read of it is printed and fails nothing.
# On the lock screen the tool service then does what a screen reader does with the masked field:
# the accessibility focus offered, a double tap, the cursor moved back by character, a letter typed
# and deleted at the cursor through the keyboard, the cursor put at the end, the text set; each
# answer is checked (the field reports mask characters and the cursor where it is), no observer
# reads any of it meanwhile, and the master password then opens the vault, so every edit landed
# where it should.
# The controls are the helper app's own platform fields: the service that is not a tool reads the
# text typed into a platform text field, as a node and in its events, so it is connected and
# reading; and what each observer reads of a platform password field (Android's own dots, with
# "Show passwords" on as it is by default, then off before Android 17), and of its hide after a
# show, is printed for comparison.
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
HIDDEN_PLAIN=0
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

# seen [--masked] PACKAGE WHAT ID SECRET...: takes every observer's record of the screen as it is and
# of the events since the mark, prints what each reads of the SECRETs (a11y_seen.py), and sets
# SEEN_PLAIN, SEEN_TOOL and SEEN_UI to seen or clean, and SEEN_SHAPE to what the field says of itself
# (ok, wrong, or none without --masked: ID is a field drawn as dots that holds the first SECRET).
seen() {
    local base out s args=() package what id
    if [ "$1" = --masked ]; then args+=(--masked); shift; fi
    package=$1 what=$2 id=$3
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
    SEEN_UI=$(printf '%s\n' "$out" | sed -n 's/^RESULT .* ui=\([a-z]*\) .*/\1/p')
    SEEN_SHAPE=$(printf '%s\n' "$out" | sed -n 's/^RESULT .* shape=\([a-z]*\)$/\1/p')
    [ -n "$SEEN_PLAIN" ] && [ -n "$SEEN_TOOL" ] && [ -n "$SEEN_UI" ] && [ -n "$SEEN_SHAPE" ]
}

# judge KIND WHERE ID SECRET...: one secret's step. KIND is hidden (a field drawn as dots that holds
# the first SECRET) or shown (on the screen for the user to read).
judge() {
    local kind=$1 where=$2 id=$3 name
    shift 3
    if [ "$kind" = hidden ]; then
        name="a11y: $where, $id, drawn as dots: no accessibility service reads any character of it (not a tool, a tool, uiautomator), and it reads as a password of ${#1} mask characters"
        if ! seen --masked "$PV_PACKAGE" "$id" "$id" "$@"; then
            pv_report FAIL "$name" "the observers could not be read"
            return 1
        fi
        HIDDEN_FIELDS=$((HIDDEN_FIELDS + 1))
        [ "$SEEN_PLAIN" = clean ] || HIDDEN_PLAIN=$((HIDDEN_PLAIN + 1))
        [ "$SEEN_TOOL" = clean ] || HIDDEN_TOOL=$((HIDDEN_TOOL + 1))
        [ "$SEEN_UI" = clean ] || HIDDEN_UI=$((HIDDEN_UI + 1))
        if [ "$SEEN_PLAIN" = clean ] && [ "$SEEN_TOOL" = clean ] && [ "$SEEN_UI" = clean ] && [ "$SEEN_SHAPE" = ok ]; then
            pv_report PASS "$name"
            return 0
        fi
        pv_report FAIL "$name" "the service that is not a tool: $SEEN_PLAIN, the tool service: $SEEN_TOOL, uiautomator: $SEEN_UI; the field's node: $SEEN_SHAPE"
        return 1
    fi
    name="a11y: $where, $id, shown on the screen: the service that is not a tool reads nothing of it"
    if ! seen "$PV_PACKAGE" "$id" "$id" "$@"; then
        pv_report FAIL "$name" "the observers could not be read"
        return 1
    fi
    if [ "$SEEN_PLAIN" = clean ]; then
        pv_report PASS "$name"
    elif [ "$API" -ge 34 ] || [ "$BEFORE_34" = fail ]; then
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
    # For comparison: what a platform password field gives each observer, with Android's "Show
    # passwords" as the device has it (on by default), once it no longer shows the character typed last.
    watch_type control_password "$CONTROL_SECRET" || return 1
    pv_sleep 2
    seen --masked "$WIN_HELPER" "control: a platform password field" control_password "$CONTROL_SECRET" || return 1
    PLATFORM_PASSWORD="the service that is not a tool: $SEEN_PLAIN, the tool service: $SEEN_TOOL, uiautomator: $SEEN_UI"
    # Its own show and hide (a change of the transformation method): what the hide sends.
    pva_hide_keyboard
    pva_tap control_reveal || return 1
    pv_sleep 1
    win_a11y_wait 10 || return 1
    mark
    pva_tap control_reveal || return 1
    pv_sleep 1
    seen --masked "$WIN_HELPER" "control: a platform password field hidden again after a show" control_password "$CONTROL_SECRET" || return 1
    PLATFORM_HIDE="the service that is not a tool: $SEEN_PLAIN, the tool service: $SEEN_TOOL, uiautomator: $SEEN_UI"
    # And with "Show passwords" off: typed again into the emptied field. Android 17 keeps that switch
    # elsewhere (android.text.ShowSecretsSetting, for touch and for physical keys), not in
    # TEXT_SHOW_PASSWORD, so it is varied only before.
    if [ "$API" -ge 37 ]; then
        PLATFORM_NO_SHOW="not varied on Android 17"
        return 0
    fi
    control_clear control_password ${#CONTROL_SECRET} || return 1
    control_show_passwords 0 || return 1
    watch_type control_password "$CONTROL_SECRET" || return 1
    pv_sleep 2
    seen --masked "$WIN_HELPER" "control: a platform password field, \"Show passwords\" off" control_password "$CONTROL_SECRET" || return 1
    PLATFORM_NO_SHOW="the service that is not a tool: $SEEN_PLAIN, the tool service: $SEEN_TOOL, uiautomator: $SEEN_UI"
    control_show_passwords "$SHOW_PASSWORDS"
}

# control_clear ID COUNT: deletes COUNT characters at the end of the platform field ID.
control_clear() {
    local keys="" i
    pva_tap "$1" || return 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    for ((i = 0; i < $2 + 2; i++)); do keys="$keys 67"; done
    pv_shell input keyevent $keys >/dev/null
    pv_sleep 1
}

# control_show_passwords 0|1: Android's "Show passwords" (Settings.System.TEXT_SHOW_PASSWORD), which
# the platform's password fields read; put back by win_cleanup in any case.
SHOW_PASSWORDS=""
control_show_passwords() {
    if [ -z "$SHOW_PASSWORDS" ]; then
        SHOW_PASSWORDS=$(pv_shell settings get system show_password 2>/dev/null | tr -d '\r')
        case "$SHOW_PASSWORDS" in 0 | 1) ;; *) SHOW_PASSWORDS=1 ;; esac
        win_setting_set system show_password "$1"
    else
        pv_shell settings put system show_password "$1" >/dev/null
    fi
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
# text common_show_password ("Show password") or common_hide_password ("Hide password"). The mark
# is set just before the tap.
tap_described() {
    local label line
    label=$(win_app_string "$1") || { pv_log "the APK has no string $1"; return 1; }
    pva_hide_keyboard
    line=$(pv_ui_nodes | awk -F'|' -v d="$label" '$2 == d { print; exit }')
    [ -n "$line" ] || { pv_log "no button described as '$label'; ids: $(pva_ids)"; return 1; }
    win_a11y_wait 10 || return 1
    mark
    pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$(printf '%s\n' "$line" | cut -d'|' -f7)" >/dev/null
    pv_sleep 1
}
show_password() { tap_described common_show_password; }
hide_password() { tap_described common_hide_password; }

# ---- a screen reader's actions on the lock screen's masked field ----------------------------------
# act ACTION [EXTRAS...]: the tool service does ACTION to the field (LabA11yService.act); ACT gets the
# field as it is afterwards.
act() {
    local action=$1
    shift
    ACT=$(win_cmd a11y-act --es kind tool --es id pv.lock.password --es action "$action" "$@") || return 1
    case " $ACT " in *" found=true "*) ;; *) pv_log "a11y-act $action: the field is not found"; return 1 ;; esac
}
act_value() { printf '%s\n' "${ACT% text=*}" | tr ' ' '\n' | sed -n "s/^$1=//p"; }
act_text() { case "$ACT" in *" text="*) printf '%s' "${ACT#* text=}" ;; esac; }
masks() { local n=$1 out=""; while [ "$n" -gt 0 ]; do out="$out•"; n=$((n - 1)); done; printf '%s' "$out"; }

# act_expect WHAT [result=B] [sel=S,E] [count=N] [a11yfocused=B] [focused=B]: the last answer holds
# these; count=N means its text is N mask characters. Says what differs.
act_expect() {
    local what=$1 want key value got text bad=""
    shift
    text=$(act_text)
    for want in "$@"; do
        key=${want%%=*}; value=${want#*=}
        case "$key" in
            count) [ "$text" = "$(masks "$value")" ] || bad="$bad; the text is not $value mask characters ($(printf '%s' "$text" | python3 -c 'import sys; t = sys.stdin.read(); print("%d characters, %d of them mask characters" % (len(t), sum(c == "\u2022" for c in t)))'))" ;;
            *) got=$(act_value "$key"); [ "$got" = "$value" ] || bad="$bad; $key=$got, not $value" ;;
        esac
    done
    [ -z "$bad" ] || { pv_log "$what:${bad#;}"; return 1; }
}

# The field holds the master password (N characters) and the keyboard; the tool service is a screen
# reader's stand-in. Every answer must show mask characters and the cursor where it should be.
screen_reader() {
    local n=${#WIN_MASTER} actions granularities
    act info && act_expect "the field as a screen reader finds it" password=true editable=true count="$n" sel="$n,$n" || return 1
    actions=",$(act_value actions),"
    granularities=$(act_value granularities)
    for want in 64:ACTION_ACCESSIBILITY_FOCUS 16:ACTION_CLICK 2097152:ACTION_SET_TEXT 131072:ACTION_SET_SELECTION 256:ACTION_NEXT_AT_MOVEMENT_GRANULARITY 512:ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY; do
        case "$actions" in *",${want%%:*},"*) ;; *) pv_log "the field does not offer ${want#*:} (actions $actions)"; return 1 ;; esac
    done
    [ $((granularities & 1)) -ne 0 ] || { pv_log "the field cannot be read by character (granularities $granularities)"; return 1; }
    echo "  a screen reader finds a password field of $n mask characters, the cursor at the end, the actions to focus it, set the text, put the cursor and move it, granularities $granularities"
    # Compose gives the accessibility focus itself only while touch exploration is on, which a screen
    # reader turns on and the helper's service does not (the script's taps must stay taps): the
    # action is offered above. A double tap is a click: the field takes the input focus.
    act click && act_expect "a double tap" result=true focused=true || return 1
    act prev --ei granularity 1 && act_expect "the cursor back by one character" result=true count="$n" sel="$((n - 1)),$((n - 1))" || return 1
    act prev --ei granularity 1 && act_expect "the cursor back by another character" result=true count="$n" sel="$((n - 2)),$((n - 2))" || return 1
    echo "  the cursor moved back by character twice: at $((n - 2)) of $n"
    pv_shell input text Z >/dev/null
    pv_sleep 1
    act info && act_expect "a letter typed through the keyboard" count="$((n + 1))" sel="$((n - 1)),$((n - 1))" || return 1
    pv_shell input keyevent KEYCODE_DEL >/dev/null
    pv_sleep 1
    act info && act_expect "the keyboard's delete" count="$n" sel="$((n - 2)),$((n - 2))" || return 1
    echo "  a letter typed at the cursor and deleted again through the keyboard"
    act select --ei start "$n" --ei end "$n" && act_expect "the cursor put at the end" result=true count="$n" sel="$n,$n" || return 1
    act settext --es text Wrongpass && act_expect "the text set to another one" result=true count=9 || return 1
    act settext --es text "$WIN_MASTER" && act_expect "the text set to the master password" result=true count="$n" || return 1
    echo "  the cursor put at the end; the text set twice, as a screen reader's paste does"
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
PLATFORM_HIDE=""
PLATFORM_NO_SHOW=""
pv_step "control: the service that is not a tool reads a platform field's typed text, as a node and in its events" -- control || win_end
echo "  for comparison, a platform password field (seen: some character of it read): $PLATFORM_PASSWORD; hidden again after a show: $PLATFORM_HIDE; with \"Show passwords\" off: $PLATFORM_NO_SHOW"
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
    # The hide itself, and the field drawn as dots again.
    if pv_step "setup: \"Hide password\" tapped" -- hide_password; then
        judge hidden "setup, the own master password hidden again" pv.setup.own "$WIN_MASTER"
    fi
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
pv_step "the lock screen: a screen reader focuses the masked field, moves its cursor by character, types and deletes at the cursor, puts the cursor and sets the text" -- eval 'win_a11y_wait 10 && mark && screen_reader' || win_end
judge hidden "the lock screen, the master password, while a screen reader moves the cursor and edits" pv.lock.password "$WIN_MASTER"
pv_step "the lock screen: after the screen reader's edits the field holds exactly the master password: it opens the vault" -- pva_submit pv.lock.unlock pv.route.Passwords 90

# ---- what else was seen ------------------------------------------------------------------------
win_file a11y-plain-events.log >"$WIN_OUT/a11y-$VARIANT-plain-events.log"
win_file a11y-tool-events.log >"$WIN_OUT/a11y-$VARIANT-tool-events.log"
echo "  of the $HIDDEN_FIELDS readings of fields drawn as dots, characters of the secret were read by the service that is not a tool at $HIDDEN_PLAIN, the service declared as a tool at $HIDDEN_TOOL, uiautomator at $HIDDEN_UI"
echo "  for comparison, a platform password field: $PLATFORM_PASSWORD; hidden again after a show: $PLATFORM_HIDE; with \"Show passwords\" off: $PLATFORM_NO_SHOW (seen: some character of it read)"
PLAIN_EVENTS=$(grep -c "^EVENT	[A-Z_]*	$PV_PACKAGE	" "$WIN_OUT/a11y-$VARIANT-plain-events.log")
if [ "$API" -ge 34 ]; then
    # Events of nodes that are not marked sensitive still reach it: screen titles and the like.
    echo "  from the app, the service that is not a tool got $PLAIN_EVENTS events with a text in the whole run; their texts: $(awk -F'\t' -v pkg="$PV_PACKAGE" "$WIN_AWK_VAL"' $1 == "EVENT" && $3 == pkg { t = val("text"); if (t == "") t = val("desc"); if (t != "" && !(t in seen)) { seen[t] = 1; n++; if (n <= 12) printf "%s\"%s\"", (n > 1 ? ", " : ""), substr(t, 1, 60) } } END { if (n > 12) printf " and %d more", n - 12; if (n == 0) printf "none" }' "$WIN_OUT/a11y-$VARIANT-plain-events.log")"
else
    echo "  from the app, the service that is not a tool got $PLAIN_EVENTS events with a text in the whole run ($WIN_OUT/a11y-$VARIANT-plain-events.log)"
fi
[ "$KNOWN" -eq 0 ] || echo "  $KNOWN secrets shown on the screen were readable by the service that is not a tool: KNOWN, before Android 14 (lock.md §2.3)"
win_end
