#!/usr/bin/env bash
# tools/emu/window/capture.sh - build plan 3.12, beside its [script] lines: content capture (Android
# 10, API 29, and newer), the other way besides accessibility in which the texts of a screen leave
# the app (architecture.md §8.4). What a content capture service gets of the masked master password,
# with a build without FLAG_SECURE (debug) and one with it (staging), as far as these devices let a
# script see it.
#
#   tools/emu/window/capture.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh. On API 28 every step is SKIPPED: content capture starts
#       with Android 10. The APK defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk.
#
# The helper's LabCaptureService is made the device's temporary content capture service (cmd
# content_capture set temporary-service, two minutes at a time, the longest Android allows), with
# what it needs: hidden API checks relaxed for its system API calls (settings put global
# hidden_api_policy 1) and the service explicitly enabled (device_config content_capture
# service_explicitly_enabled true). All three are put back at the end. Connected, it allows content
# capture for PasswordVault and for the helper, and writes down everything it is sent.
#   - Control: the helper's own control screen sends it the text typed into a platform text field,
#     so it is connected and capturing. With FLAG_SECURE on that screen, as the app's staging and
#     release builds have, nothing typed reaches it: Android disables the activity's session.
#   - The app, installed afresh, the own master password typed into the setup's masked field: nothing
#     of the app may reach the service, in no event a character of the password. On these images
#     Android gives a temporary service no other package than its own ("Ignoring package ... while
#     using temporary service ...", printed when the log has it), so nothing of the app arrives at all.
#   - The device's own service again (the temporary one reset), the app started again and the
#     password typed again: whether the app's activity has a content capture session at all
#     (dumpsys activity top, its ContentCaptureManager) is printed, not judged: the device's service
#     decides which apps it takes.
# What Compose (ui 1.12) sends when a service does take the app is known from its source
# (AndroidContentCaptureManager): a node marked as a password gives no view structure at all, so a
# VaultSecureField, masked or shown, is never sent, and text changes are sent only for plain Text
# nodes; a secret shown as Text (the chosen password on "Write it down on paper", the generator's
# suggestion) would be sent, but not from a window with FLAG_SECURE.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for
# a usage error, 3 for a refused serial. The service's record is kept under build/window/SERIAL/.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin capture.sh "$@"

SERVICE=$WIN_HELPER/.LabCaptureService
CONTROL_TEXT=capturedCtrlRRR
CONTROL_SECURE_TEXT=capturedSecureSSS

if [ "$API" -lt 29 ]; then
    for name in "the helper's capture service is the device's content capture service" \
        "control: the text typed into a platform field reaches it" \
        "control: with FLAG_SECURE nothing typed reaches it" \
        "the app: nothing of the masked master password reaches it"; do
        pv_report SKIPPED "capture: $name" "content capture starts with Android 10 (API 29); $SERIAL has API $API"
    done
    win_end
fi

# wait_for SECONDS COMMAND...: COMMAND becomes true within SECONDS.
wait_for() {
    local deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        [ $SECONDS -lt $deadline ] || return 1
        pv_sleep 0.5
    done
}

record() { win_file capture.log; }

# temporary: the lab service becomes the temporary service afresh, for two minutes, and is connected
# (it has allowed PasswordVault and the helper). The record starts empty.
temporary() {
    pv_shell cmd content_capture set temporary-service 0 >/dev/null 2>&1
    win_file_rm capture.log
    pv_shell cmd content_capture set temporary-service 0 "$SERVICE" 120000 2>&1 | grep -q 'temporarily set' \
        || { pv_log "cmd content_capture set temporary-service refused $SERVICE"; return 1; }
    wait_for 15 eval 'record | grep -q "^CONNECTED"' || { pv_log "the lab service did not connect: $(record | head -n 3)"; return 1; }
}

capture_on() {
    local old
    win_setting_set global hidden_api_policy 1 || return 1
    old=$(pv_shell device_config get content_capture service_explicitly_enabled 2>/dev/null | tr -d '\r')
    pv_shell device_config put content_capture service_explicitly_enabled true >/dev/null || return 1
    case "$old" in
        "" | null) win_undo "device_config delete content_capture service_explicitly_enabled" ;;
        *) win_undo "device_config put content_capture service_explicitly_enabled $old" ;;
    esac
    win_undo "cmd content_capture set temporary-service 0"
    temporary
}

# typed_into ID TEXT: TEXT typed into the field ID of the screen in front.
typed_into() {
    pva_tap "$1" || return 1
    pv_sleep 1
    pv_shell input text "$2" >/dev/null
}

control() {
    win_control_start -f 0x10008000 || return 1
    typed_into control_text "$CONTROL_TEXT" || return 1
    wait_for 15 eval 'record | grep -q "text=$CONTROL_TEXT\$"' \
        || { pv_log "the typed text did not reach the lab service: $(record | cut -c1-120 | tail -n 5 | tr '\n' ';')"; return 1; }
}

control_secure() {
    local sessions
    win_control_start -f 0x10008000 --ez secure true || return 1
    typed_into control_text "$CONTROL_SECURE_TEXT" || return 1
    # Content capture sends at the latest after five idle seconds (idle_flush_frequency).
    pv_sleep 8
    sessions=$(record | grep -c "^SESSION")
    echo "  control with FLAG_SECURE: the service has $sessions sessions in all; $(pv_shell dumpsys activity top 2>/dev/null | tr -d '\r' | awk '/ContentCaptureManager$/ { f = 1 } f && /mDisabled:|isContentCaptureEnabled|state:/ { gsub(/^ +/, ""); printf "%s; ", $0; n++ } n >= 3 { exit }')"
    ! record | grep -q "$CONTROL_SECURE_TEXT" || { pv_log "the text typed with FLAG_SECURE reached the lab service"; return 1; }
}

# app_sent: what the lab service got from the app since the record was emptied, judged: no session of
# the app may carry a character of the typed password.
app_sent() {
    local out
    out=$(record | python3 -c '
import sys
pkg, field, secret = sys.argv[1], sys.argv[2], sys.argv[3]
sessions, events, leaks, field_events = set(), 0, 0, 0
for line in sys.stdin:
    parts = line.rstrip("\n").split("\t")
    if parts[0] == "SESSION" and len(parts) > 2 and ("act=" + pkg + "/") in parts[2]:
        sessions.add(parts[1])
    elif parts[0] == "EVENT" and len(parts) > 1 and parts[1] in sessions:
        events += 1
        values = dict(p.split("=", 1) for p in parts[3:] if "=" in p)
        texts = [values.get(k, "") for k in ("text", "hint", "desc")]
        if values.get("id") == field:
            field_events += 1
            if any(c != "•" for c in values.get("text", "")):
                leaks += 1
                continue
        if any(secret in t for t in texts):
            leaks += 1
print("%d %d %d %d" % (len(sessions), events, field_events, leaks))
' "$PV_PACKAGE" pv.setup.own "$WIN_MASTER")
    read -r APP_SESSIONS APP_EVENTS APP_FIELD APP_LEAKS <<<"$out"
    echo "  the app: $APP_SESSIONS sessions with the lab service, $APP_EVENTS events, $APP_FIELD about pv.setup.own, $APP_LEAKS with a character of the password"
    [ "${APP_LEAKS:-1}" -eq 0 ]
}

app_typed() {
    local since
    since=$(pv_shell date +%m-%d\\ %H:%M:%S.000 2>/dev/null | tr -d '\r')
    temporary || return 1
    win_app_fresh && win_to_own && typed_into pv.setup.own "$WIN_MASTER" || return 1
    pv_sleep 8
    record >"$WIN_OUT/capture-$VARIANT.log"
    app_sent || return 1
    if pv_shell logcat -d -T "'$since'" -s ContentCaptureManagerService:W 2>/dev/null | tr -d '\r' | grep -q "Ignoring package $PV_PACKAGE while using temporary service"; then
        echo "  Android: \"Ignoring package $PV_PACKAGE while using temporary service $WIN_HELPER\" (only the temporary service's own package is captured)"
    fi
}

# The app with the device's own service: its activity's content capture state, printed.
app_default() {
    local state
    pv_shell cmd content_capture set temporary-service 0 >/dev/null 2>&1
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pva_start || return 1
    pv_ui_wait '[|]pv\.(route\.Welcome|open\.CreateMasterPassword)$' 20 || return 1
    win_to_own && typed_into pv.setup.own "$WIN_MASTER" || return 1
    pv_sleep 3
    state=$(pv_shell dumpsys activity top 2>/dev/null | tr -d '\r' | awk -v act="ACTIVITY $PV_PACKAGE/" '
        index($0, act) { f = 1; next }
        f && /^  ACTIVITY / { exit }
        f && /ContentCaptureManager$/ { c = 1 }
        c && /isContentCaptureEnabled|mDisabled:|state:/ { gsub(/^ +/, ""); printf "%s; ", $0 }')
    echo "  with the device's own content capture service ($(pv_shell dumpsys content_capture 2>/dev/null | tr -d '\r' | sed -n 's/.*defaultName=\([^ ]*\).*/\1/p' | head -n 1)), the app's activity: ${state:-no content capture session at all}"
}

pv_step "capture: the helper's capture service is the device's content capture service" -- capture_on || win_end
pv_step "capture: control: the text typed into a platform field reaches it" -- control || win_end
pv_step "capture: control: with FLAG_SECURE nothing typed reaches it" -- control_secure
pv_step "capture: the app: nothing of the masked master password reaches it" -- app_typed || win_end
echo "capture.sh: the $VARIANT build"
pv_step "capture: the app with the device's own service: its content capture state read" -- app_default
win_end
