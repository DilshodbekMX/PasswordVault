#!/usr/bin/env bash
# tools/emu/lock/E12.sh - lock.md §15.3 check E12 (Android 17; also run on Android 9): the pad with
# isSensitiveData, for a screen reader (TalkBack), Switch Access and a hardware keyboard, and the
# accessibility service of the window lab that is not an accessibility tool (LabA11yService) sees no key
# press. TalkBack itself is architecture.md §8.6 item 8, by hand; here an accessibility tool of the
# window helper does what TalkBack and Switch Access do to a node (focus, click).
#
#   tools/emu/lock/E12.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh, API 26 or newer (the window helper's services need API
#       26; lock.md lists Android 17, and build plan §12 asks for Android 9 too). The APK defaults to
#       the debug build. The accessibility settings are put back when the script ends.
#
# The pad (ux.md §5.9, lock.md §13.5; build plan 4.2's report): each key is one focusable, clickable
# node marked sensitive (isSensitiveData), with test tag pv.pin.key.N; its label ("0" to "9", "Delete
# last digit", "Unlock with fingerprint") sits on a child that takes no focus and is not itself marked
# sensitive. The dots (pv.pin.dots) are one node marked sensitive whose description reads "PIN, 3 of 6
# digits entered". On Android 14 and newer (API 34) isSensitiveData and the window's
# setAccessibilityDataSensitive keep a service that is not an accessibility tool out; before that,
# lock.md §2.3 accepts that accessibility services the user turned on read what is shown, so there a
# finding of the non-tool service is printed as KNOWN, not FAIL.
#
# Steps, after the app is installed afresh, set up with the PIN (fingerprint "Not now"), and the
# helper's two services (the plain one, not a tool, and the tool) turned on:
#   - Lock now: the pad. The tool's view: every key 0 to 9 and delete is a clickable node with its
#     label on it or under it, and the dots read "PIN, 0 of 6 digits entered";
#   - the tool presses the first 3 digits of the PIN by ACTION_CLICK on their nodes, as TalkBack's
#     double tap and Switch Access's select do: the dots read "PIN, 3 of 6 digits entered", and no node
#     holds the digits typed (no run of 2 or more of them, anywhere);
#   - what the service that is not a tool saw meanwhile: no event of the app that names a key (its
#     label or its id), and in its view of the windows no key and no label of the pad. Android 14 and
#     newer: PASS or FAIL. Before: KNOWN when it saw them;
#   - delete by the tool (ACTION_CLICK on pv.pin.delete) three times: 0 of 6;
#   - the tool presses all six digits: the app opens (a screen reader can unlock);
#   - Lock now, the six digits as hardware key events (KEYCODE_0 to KEYCODE_9, which the pad takes
#     when it has the focus): the app opens.
# Prints PASS, FAIL, SKIPPED or KNOWN per step and a summary; exit 0 when no step failed, 1 otherwise, 2
# for a usage error, 3 for a refused serial. The services' records are kept under build/emu-lock/.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E12 "$@"
if [ "$API" -lt 26 ]; then
    echo "E12: the window helper's accessibility services need API 26; $SERIAL has API $API" >&2
    trap - EXIT
    exit 2
fi
# The window helper: its build, install, commands and accessibility services (tools/emu/window/lib.sh).
source "$EMU/window/lib.sh"
KNOWN_COUNT=0

known() { echo "KNOWN $1 ($2)"; KNOWN_COUNT=$((KNOWN_COUNT + 1)); }

set_up() { pvl_set_up "$PVL_PIN" - ; }

helper() { win_helper_build && win_helper_install; }

services_on() {
    pvl_setting_set secure enabled_accessibility_services "$WIN_HELPER/.LabA11yService:$WIN_HELPER/.LabA11yToolService" || return 1
    pvl_setting_set secure accessibility_enabled 1 || return 1
    WIN_A11Y_KINDS=(plain tool)
    win_a11y_wait 30
}

# The services' event records: how many lines each has now (the next findings come after them).
MARK_PLAIN=0
MARK_TOOL=0
mark() {
    MARK_PLAIN=$(win_file a11y-plain-events.log | wc -l)
    MARK_TOOL=$(win_file a11y-tool-events.log | wc -l)
}

events_since() { win_file "a11y-$1-events.log" | tail -n "+$(( $( [ "$1" = plain ] && echo "$MARK_PLAIN" || echo "$MARK_TOOL") + 1 ))"; }

# tool_view: the tool service's view as "depth|id|text|desc|clickable" lines (every window).
tool_view() {
    win_a11y_dump "$1" | awk -F'\t' "$WIN_AWK_VAL"'
        $1 == "NODE" { id = $5; sub(/.*\//, "", id); if (id == "null") id = ""; print $2 "|" id "|" val("text") "|" val("desc") "|" val("clickable") }'
}

# labelled VIEW ID LABEL: the node ID is clickable, and it or a node below it has LABEL as its text or
# description.
labelled() {
    printf '%s\n' "$1" | awk -F'|' -v id="$2" -v label="$3" '
        found && $1 <= depth { exit }
        found && ($3 == label || $4 == label) { ok = 1; exit }
        !found && $2 == id { found = 1; depth = $1; if ($5 != "true") { bad = 1; exit } if ($3 == label || $4 == label) { ok = 1; exit } }
        END { exit (ok && !bad) ? 0 : 1 }'
}

DELETE_LABEL=""
pad_reachable() {
    local view n bad=0
    pvl_lock_now && pvl_pad 20 || return 1
    win_a11y_wait 15 || return 1
    view=$(tool_view tool) || return 1
    printf '%s\n' "$view" > "$PVL_OUT/tool-view-pad.txt"
    for n in 0 1 2 3 4 5 6 7 8 9; do
        labelled "$view" "pv.pin.key.$n" "$n" || { pv_log "key $n is not a clickable node labelled \"$n\" for the tool"; bad=1; }
    done
    DELETE_LABEL=$(pvl_res string common_pin_delete)
    labelled "$view" pv.pin.delete "$DELETE_LABEL" || { pv_log "delete is not a clickable node labelled \"$DELETE_LABEL\""; bad=1; }
    pvl_expect "$(printf '%s\n' "$view" | awk -F'|' '$2 == "pv.pin.dots" { print $4; exit }')" plural common_pin_dots 0 || bad=1
    return $bad
}

# act ID ACTION: the tool service does ACTION to the node ID; true when it was done.
act() {
    local out
    out=$(win_cmd a11y-act --es kind tool --es id "$1" --es action "$2") || return 1
    case "$out" in *"found=true result=true"*) return 0 ;; esac
    pv_log "a11y-act $2 on $1: $out"
    return 1
}

dots_read() {
    local view
    view=$(tool_view tool) || return 1
    pvl_expect "$(printf '%s\n' "$view" | awk -F'|' '$2 == "pv.pin.dots" { print $4; exit }')" plural common_pin_dots "$1"
}

tool_types_three() {
    local i view
    mark
    for ((i = 0; i < 3; i++)); do
        act "pv.pin.key.${PVL_PIN:$i:1}" focus >/dev/null 2>&1
        act "pv.pin.key.${PVL_PIN:$i:1}" click || return 1
        pv_sleep 0.5
    done
    dots_read 3 || return 1
    # No node anywhere holds a run of the digits typed.
    view=$(tool_view tool) || return 1
    printf '%s\n' "$view" > "$PVL_OUT/tool-view-3.txt"
    ! printf '%s\n' "$view" | awk -F'|' '{ print $3; print $4 }' | grep -q -F -e "${PVL_PIN:0:2}" -e "${PVL_PIN:1:2}" \
        || { pv_log "a node holds digits that were typed"; return 1; }
    ! pv_ui_nodes | cut -d'|' -f1,2 | grep -q -F -e "${PVL_PIN:0:2}" -e "${PVL_PIN:1:2}" \
        || { pv_log "uiautomator reads digits that were typed"; return 1; }
}

# What the plain service (not a tool) learned while the tool typed: events of the app that name a key,
# and keys in its view.
plain_learned() {
    local events view found=""
    events=$(events_since plain | grep -F "$PV_PACKAGE" )
    printf '%s\n' "$events" > "$PVL_OUT/plain-events-while-typing.txt"
    view=$(tool_view plain) || return 1
    printf '%s\n' "$view" > "$PVL_OUT/plain-view-pad.txt"
    if printf '%s\n' "$events" | grep -q -E 'pv\.pin\.(key\.[0-9]|delete)|	text=[0-9]	|	desc=[0-9]	'; then
        found="its events name the keys pressed ($(printf '%s\n' "$events" | grep -c -E 'pv\.pin\.key\.[0-9]|text=[0-9]	') events)"
    fi
    if printf '%s\n' "$view" | awk -F'|' '$2 ~ /^pv[.]pin[.]key[.][0-9]$/ || $3 ~ /^[0-9]$/ { f = 1 } END { exit !f }'; then
        found="${found:+$found; }its view of the windows has the pad's keys and labels"
    fi
    pvl_note "E12 on API $API: the plain service got $(printf '%s\n' "$events" | grep -c . ) events of the app while 3 keys were pressed; its view has $(printf '%s\n' "$view" | grep -c 'pv\.') nodes with a pv.* id"
    PLAIN_FOUND=$found
    [ -z "$found" ]
}

PLAIN_FOUND=""
plain_step() {
    local name="the service that is not an accessibility tool sees no key press and no key"
    if plain_learned; then
        pv_report PASS "$name"
    elif [ "$API" -ge 34 ]; then
        pv_report FAIL "$name" "$PLAIN_FOUND"
    else
        known "$name" "$PLAIN_FOUND; before Android 14 isSensitiveData has no effect, which lock.md §2.3 accepts"
    fi
}

tool_deletes() {
    local i
    for i in 1 2 3; do
        act pv.pin.delete click || return 1
        pv_sleep 0.4
    done
    dots_read 0
}

tool_unlocks() {
    local i
    for ((i = 0; i < 6; i++)); do
        act "pv.pin.key.${PVL_PIN:$i:1}" click || return 1
        pv_sleep 0.5
    done
    pvl_open_list 30 && pvl_expect_pin row=0 total=0 charged=false
}

keyboard_unlocks() {
    local i
    pvl_lock_now && pvl_pad 20 || return 1
    pv_sleep 1
    for ((i = 0; i < 6; i++)); do
        pv_shell input keyevent "KEYCODE_${PVL_PIN:$i:1}" >/dev/null
        pv_sleep 0.4
    done
    pvl_open_list 30 && pvl_expect_pin row=0 total=0 charged=false
}

pv_step "the window helper built and installed" -- helper || pvl_end
pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- set_up || pvl_end
pv_step "the helper's accessibility services on (not a tool, and a tool)" -- services_on || pvl_end
pv_step "Lock now: every key reachable and labelled for a screen reader; \"PIN, 0 of 6 digits entered\"" -- pad_reachable || pvl_end
pv_step "a screen reader's click types 3 digits: \"PIN, 3 of 6 digits entered\"; no node holds a digit typed" -- tool_types_three
plain_step
pv_step "a screen reader's click on delete, three times: 0 of 6" -- tool_deletes
pv_step "a screen reader's clicks type the PIN: the app opens" -- tool_unlocks
pv_step "a hardware keyboard's digits on the pad: the app opens" -- keyboard_unlocks
[ "$KNOWN_COUNT" = 0 ] || pvl_note "E12: $KNOWN_COUNT KNOWN (lock.md §2.3)"
pvl_end
