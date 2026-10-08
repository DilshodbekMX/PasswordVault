#!/usr/bin/env bash
# tools/emu/window/a11y-old.sh - build plan 3.12, beside a11y.sh: what accessibility reads of the
# masked fields on Android 7 (API 24 and 25), where the helper app (minSdk 26) and its services do
# not run (architecture.md §8.4; lock.md §13.5). It uses what Android 7 has without it: the shell's
# UiAutomation, an accessibility connection that, like any accessibility service before Android 14,
# reads every window: `uiautomator dump` for a field's node (its text and its password flag), and
# `uiautomator events` for the events recorded while the field is typed into (one UiAutomation at a
# time: the recorder runs only then).
#
#   tools/emu/window/a11y-old.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (it runs on any API level; a11y.sh is the check for
#       API 26 and newer). The APK defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk.
#
# The walk: setup with a password of one's own typed into its masked field, "Show password" and
# "Hide password", the password typed again, the vault made, "Lock now", the master password typed on
# the lock screen, and it opens the vault. For each masked field one step: its node is a password
# field whose text is as many mask characters as it holds, and no event recorded meanwhile carries a
# character of it. Shown, the field's text is readable: printed as KNOWN, which lock.md §2.3 accepts
# before Android 14.
# What the recorder cannot show: Compose sends its text events (changed, selection changed,
# traversed) only while an accessibility service is enabled, and leaves UiAutomation out; the API 24
# image has no accessibility service and the helper app does not run there, so the recorder gets the
# platform's window events of the app only (it must get some: the control). Those text events come
# from the same node text in Compose's code on every API level; a11y.sh checks them on API 28 and 37.
# Prints PASS, FAIL or KNOWN per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial. The recorded events are kept under build/window/SERIAL/.
WIN_NO_HELPER=1
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin a11y-old.sh "$@"
win_undo "pkill -x uiautomator"

RECORDER_PID=""
STATION=0

# record_start: `uiautomator events` runs, its output going to the next station's file, and it is
# listening. When an accessibility client connects, the window in front sends its state and its focus
# (the two events of the app that every station records: the control), so the recorder listens once
# it has printed something. That takes 0.3 to 0.4 s, and it used to be given three seconds; at the
# device check of round R2, on a machine with a load of 40, one station's recorder had printed
# nothing six seconds after its start, and the control failed. Now the typing waits for the first
# line, for up to 30 s.
record_start() {
    local i
    STATION=$((STATION + 1))
    EVENTS=$WIN_OUT/a11y-old-$VARIANT-$(printf '%02d' "$STATION")-events.txt
    PV_ADB_TIMEOUT=300 pv_adb shell uiautomator events >"$EVENTS" 2>&1 &
    RECORDER_PID=$!
    for i in $(seq 1 60); do
        [ -s "$EVENTS" ] && break
        pv_sleep 0.5
    done
    pv_sleep 1
}

# record_stop: the recorder ends, on the host and on the device.
record_stop() {
    pv_sleep 2
    [ -z "$RECORDER_PID" ] || kill "$RECORDER_PID" 2>/dev/null
    wait "$RECORDER_PID" 2>/dev/null
    RECORDER_PID=""
    # The device's uiautomator outlives its adb client and would keep UiAutomation from the next dump.
    pv_shell 'pkill -x uiautomator' >/dev/null 2>&1
    pv_sleep 1
}

# typed ID TEXT: the field ID tapped, then TEXT typed while the recorder runs.
typed() {
    pva_tap "$1" || return 1
    pv_sleep 1
    record_start
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    pv_shell input text "$2" >/dev/null
    record_stop
}

# tap_described STRING-NAME: the button described as the app's text STRING-NAME tapped while the
# recorder runs.
tap_described() {
    local label line
    label=$(win_app_string "$1") || { pv_log "the APK has no string $1"; return 1; }
    pva_hide_keyboard
    line=$(pv_ui_nodes | awk -F'|' -v d="$label" '$2 == d { print; exit }')
    [ -n "$line" ] || { pv_log "no button described as '$label'; ids: $(pva_ids)"; return 1; }
    record_start
    pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$(printf '%s\n' "$line" | cut -d'|' -f7)" >/dev/null
    record_stop
}

# events_of SECRET: what the events recorded at this station carry, as "N M K": the app's events, those
# of a password field, and those with a character of SECRET (a password field's text that is not mask
# characters only, or SECRET anywhere).
events_of() {
    python3 - "$EVENTS" "$PV_PACKAGE" "$1" <<'EOF'
import re, sys
path, pkg, secret = sys.argv[1:4]
app = password = leaks = 0
for line in open(path, encoding="utf-8", errors="replace"):
    if "PackageName: " + pkg + ";" not in line:
        continue
    app += 1
    texts = re.findall(r"Text: \[(.*?)\];", line) + re.findall(r"BeforeText: (.*?);", line)
    if "IsPassword: true" in line:
        password += 1
        if any(t not in ("", "null") and any(c != "•" for c in t) for t in texts):
            leaks += 1
            continue
    if secret in line:
        leaks += 1
print(app, password, leaks)
EOF
}

# node_of ID: the field's node as `uiautomator dump` writes it: NODE_PASSWORD (true or false) and
# NODE_TEXT; both empty when the dump fails or has no such node.
node_of() {
    local out
    out=$(pv_shell 'rm -f /sdcard/pv-a11y-old.xml; uiautomator dump /sdcard/pv-a11y-old.xml >/dev/null 2>&1; cat /sdcard/pv-a11y-old.xml 2>/dev/null; rm -f /sdcard/pv-a11y-old.xml' \
        | python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.buffer.read().replace(b"\r", b""))
except ET.ParseError:
    sys.exit(0)
for node in root.iter("node"):
    if node.get("resource-id") == sys.argv[1]:
        print(node.get("password", "") + "\t" + node.get("text", ""))
        break
' "$1")
    NODE_PASSWORD=${out%%$'\t'*}
    NODE_TEXT=${out#*$'\t'}
    [ -n "$out" ] || { NODE_PASSWORD=""; NODE_TEXT=""; }
}

# judge_masked WHERE ID SECRET: the node and the recorded events of a field drawn as dots.
judge_masked() {
    local where=$1 id=$2 secret=$3 name app password leaks bad=""
    name="a11y (Android 7): $where, $id, drawn as dots: uiautomator reads a password field of ${#secret} mask characters, and no recorded event carries a character of it"
    node_of "$id"
    [ -n "$NODE_PASSWORD" ] || { pv_report FAIL "$name" "no node $id in uiautomator's dump"; return 1; }
    [ "$NODE_PASSWORD" = true ] || bad="the node is not marked as a password"
    [ "$NODE_TEXT" = "$(printf '%*s' ${#secret} '' | sed 's/ /•/g')" ] || bad="${bad:+$bad; }the node does not read ${#secret} mask characters"
    read -r app password leaks <<<"$(events_of "$secret")"
    echo "  $id: the node reads $(printf '%s' "$NODE_TEXT" | python3 -c 'import sys; t = sys.stdin.read(); print("%d characters, %d of them mask characters" % (len(t), sum(c == "\u2022" for c in t)))'), password=$NODE_PASSWORD; $app events of the app recorded, $password of a password field, $leaks with a character of the secret"
    [ "${app:-0}" -gt 0 ] || bad="${bad:+$bad; }the recorder got no event of the app"
    [ "${leaks:-1}" -eq 0 ] || bad="${bad:+$bad; }$leaks events carry characters of it"
    if [ -z "$bad" ]; then pv_report PASS "$name"; else pv_report FAIL "$name" "$bad"; return 1; fi
}

pv_step "the app installed afresh: Welcome" -- win_app_fresh || win_end
echo "a11y-old.sh: the $VARIANT build"
pv_step "setup: a password of one's own typed" -- eval 'win_to_own && typed pv.setup.own "$WIN_MASTER"' || win_end
judge_masked "setup, the own master password" pv.setup.own "$WIN_MASTER"
if pv_step "setup: \"Show password\" tapped" -- tap_described common_show_password; then
    node_of pv.setup.own
    if [ "$NODE_TEXT" = "$WIN_MASTER" ]; then
        echo "KNOWN a11y (Android 7): setup, the own master password after \"Show password\", pv.setup.own, shown on the screen: uiautomator reads it; before Android 14 an accessibility service that the user turned on is not kept out (lock.md §2.3)"
    fi
    pv_step "setup: \"Hide password\" tapped" -- tap_described common_hide_password \
        && judge_masked "setup, the own master password hidden again" pv.setup.own "$WIN_MASTER"
fi
pv_step "setup: the password used, the question answered, \"Type it again\"" -- eval 'win_use_own && win_reuse_no && win_to_again' || win_end
pv_step "setup: the master password typed again" -- typed pv.setup.again "$WIN_MASTER" || win_end
judge_masked "setup, the master password again" pv.setup.again "$WIN_MASTER"
pv_step "setup: the vault is made" -- win_create_vault || win_end
pv_step "the app locked, the master password typed on the lock screen" -- eval 'win_lock && typed pv.lock.password "$WIN_MASTER"' || win_end
judge_masked "the lock screen, the master password" pv.lock.password "$WIN_MASTER"
pv_step "the lock screen: the master password opens the vault" -- pva_submit pv.lock.unlock pv.route.Passwords 90
win_end
