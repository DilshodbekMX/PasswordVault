#!/usr/bin/env bash
# Sets a device PIN and enrols one simulated fingerprint on an Android emulator, unattended.
#
#   enroll_fingerprint.sh PORT [PIN] [FINGER_ID]
#
#   PORT       console port of the emulator (adb serial emulator-PORT), e.g. 5564
#   PIN        device PIN to set or, if one is already set, to confirm with (default 1111)
#   FINGER_ID  emulator finger id to enrol (default 1); "adb emu finger touch ID" later
#              authenticates with it, any other id is "not recognized"
#
# Exit codes: 0 enrolled (fingerprint count went up), 1 enrolment not confirmed,
#             2 device not reachable or not booted, 3 PIN could not be set or confirmed.
#
# Checked on the Medium_Phone_API_37.0 image (Android 17, API 37, Google Play, Pixel
# "Pixel Imprint" Settings flow). Screens are found through the UI tree and the focused
# activity, never by fixed coordinates. Needs python3 on the host.
# Each simulated touch is "finger touch ID" followed by "finger remove": without the
# remove, touches sent in quick succession are merged into one by the emulator.
set -u

PORT="${1:?usage: $0 PORT [PIN] [FINGER_ID]}"
PIN="${2:-1111}"
FINGER="${3:-1}"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
SERIAL="emulator-$PORT"
TIMEOUT_S="${ENROLL_TIMEOUT_S:-150}"

a() { "$ADB" -s "$SERIAL" "$@"; }
log() { echo "[enroll $SERIAL $(date +%T)] $*" >&2; }
pause() { python3 -c "import time; time.sleep($1)"; }

# Prints the total number of enrolled fingerprints (all sensors), or -1 if unknown.
count_prints() {
    a shell dumpsys fingerprint 2>/dev/null | python3 -c '
import sys, json
for line in sys.stdin:
    line = line.strip()
    if line.startswith("{") and "\"prints\"" in line:
        print(sum(p.get("count", 0) for p in json.loads(line)["prints"]))
        break
else:
    print(-1)'
}

focused() { a shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | tr -d '\r'; }

# Dumps the UI tree once; prints "KIND|label|x|y" lines for interesting nodes.
ui_nodes() {
    a shell uiautomator dump /sdcard/pv_enroll_ui.xml >/dev/null 2>&1
    a shell cat /sdcard/pv_enroll_ui.xml 2>/dev/null | python3 -c '
import sys, re, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    a = n.attrib
    if a.get("enabled") != "true":
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", a.get("bounds", "[0,0][0,0]")))
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    rid = a.get("resource-id", "").split("/")[-1]
    text = a.get("text", "")
    if rid == "password_entry":
        print(f"PIN|{rid}|{cx}|{cy}")
    elif a.get("clickable") == "true" and text:
        print(f"BUTTON|{text}|{cx}|{cy}")
    elif rid == "suc_layout_title" and text:
        print(f"TITLE|{text}|{cx}|{cy}")
'
}

tap_button() {  # tap_button REGEX NODES -> 0 if tapped
    local line
    line=$(printf '%s\n' "$2" | grep -E "^BUTTON\|($1)\|" | head -1)
    [ -n "$line" ] || return 1
    local x y
    x=$(echo "$line" | cut -d'|' -f3); y=$(echo "$line" | cut -d'|' -f4)
    log "tap '$(echo "$line" | cut -d'|' -f2)' at $x,$y"
    a shell input tap "$x" "$y"
}

touch_sensor() {
    a emu finger touch "$FINGER" >/dev/null
    a emu finger remove >/dev/null
}

# --- 0. device ready ----------------------------------------------------------------
if [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
    log "device $SERIAL not reachable or not booted"; exit 2
fi
a shell svc power stayon true >/dev/null 2>&1
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard >/dev/null 2>&1

# --- 1. PIN ---------------------------------------------------------------------------
out=$(a shell locksettings set-pin "$PIN" 2>&1 | tr -d '\r')
if echo "$out" | grep -q "set to"; then
    log "$out"
elif a shell locksettings verify --old "$PIN" 2>&1 | grep -q "verified successfully"; then
    log "a PIN is already set and $PIN matches it"
else
    log "could not set or confirm PIN: $out"; exit 3
fi

before=$(count_prints)
log "fingerprints enrolled before: $before"

# --- 2. enrolment flow ------------------------------------------------------------------
a shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
pause 2
deadline=$((SECONDS + TIMEOUT_S))
touches=0
result=1
while [ $SECONDS -lt $deadline ]; do
    focus=$(focused)
    nodes=$(ui_nodes)
    title=$(printf '%s\n' "$nodes" | grep '^TITLE|' | head -1 | cut -d'|' -f2)
    if printf '%s\n' "$nodes" | grep -q '^PIN|'; then
        log "PIN screen ($title): entering PIN"
        a shell input text "$PIN"
        a shell input keyevent KEYCODE_ENTER
        pause 2
    elif [[ "$focus" == *Keyguard* || "$focus" == *NotificationShade* ]]; then
        log "keyguard is showing ($focus): dismissing"
        a shell wm dismiss-keyguard >/dev/null 2>&1
        pause 1.5
    elif [[ "$focus" == *FingerprintEnrollFinish* ]] || printf '%s\n' "$nodes" | grep -q '^BUTTON|ADD ANOTHER|'; then
        log "finish screen ($title) after $touches touches"
        tap_button 'DONE|Done' "$nodes"
        result=0
        break
    elif [[ "$focus" == *FingerprintEnrollFindSensor* || "$focus" == *FingerprintEnrollEnrolling* ]]; then
        touches=$((touches + 1))
        log "sensor screen ($title): touch #$touches with finger $FINGER"
        touch_sensor
        pause 1.2
    elif tap_button 'I AGREE|I agree|AGREE|Agree' "$nodes"; then
        pause 2
    elif tap_button 'MORE|More' "$nodes"; then
        pause 1
    else
        log "waiting (focus: ${focus##* } title: ${title:-none})"
        pause 1
    fi
    if [ "$touches" -gt 25 ]; then log "too many touches without finishing"; break; fi
done
pause 1.5

# --- 3. verify ----------------------------------------------------------------------------
after=$(count_prints)
log "fingerprints enrolled after: $after"
if [ "$result" -eq 0 ] && [ "$after" -gt "$before" ] 2>/dev/null; then
    log "OK: finger $FINGER enrolled ($before -> $after)"
    exit 0
fi
log "FAILED: enrolment not confirmed (flow result $result, count $before -> $after)"
exit 1
