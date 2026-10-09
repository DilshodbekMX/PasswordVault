# tools/emu/lock/phone.sh - the phone's own settings that the lock's checks change (lock.md §15.3, E7):
# fingers enrolled and deleted, the screen lock removed and set. A library, sourced after
# tools/emu/adb.sh and pv_use SERIAL:
#
#   source tools/emu/adb.sh; source tools/emu/lock/phone.sh; pv_use SERIAL
#   pvp_enroll FINGER            one emulator finger enrolled through Settings (API 24, 28, 37)
#   pvp_delete_all_fingers       every fingerprint deleted through Settings (API 37's Pixel screens)
#   pvp_lock_clear               the screen lock removed (locksettings on API 28 and 37, Settings on 24)
#   pvp_lock_set                 the screen lock set again to the device PIN 1111
#   pvp_fingers                  how many fingerprints are enrolled (dumpsys), -1 if unknown
#   pvp_has_lock                 a screen lock is set
#   pvp_restore_base             screen lock 1111 and fingers 1 and 2, as boot.sh --provision left the base
#
# Adapted from docs/lab/biometric-artifacts/enroll_fingerprint.sh and delete_all_fingerprints.sh (which
# take a console port, call adb themselves and know only the API 37 screens) and from the enrolment of
# tools/emu/boot.sh --provision (which knows the screens of API 24, 28 and 37): here every adb call
# goes through adb.sh's pv_adb, and screens are found by the focused activity and the UI tree
# (pv_ui_nodes, pv_ui_tap), never by fixed coordinates. Each function returns 0 on success and logs
# (pv_log) why it failed.

# pvp_fingers: the number of enrolled fingerprints, -1 if dumpsys does not say.
pvp_fingers() {
    pv_shell dumpsys fingerprint 2>/dev/null | tr -d '\r' | python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if line.startswith("{") and "\"prints\"" in line:
        print(sum(p.get("count", 0) for p in json.loads(line)["prints"]))
        break
else:
    print(-1)'
}

# pvp_has_lock: a secure screen lock is set. API 26 and newer: `locksettings verify` without the old
# PIN succeeds only when there is none. API 24 (userdebug, su): the lock type in locksettings.db
# (131072 is a numeric PIN; 0 or nothing is none).
pvp_has_lock() {
    if [ "$(pv_api)" -ge 26 ] 2>/dev/null; then
        ! pv_shell locksettings verify 2>&1 | tr -d '\r' | grep -q 'verified successfully'
    else
        local t
        t=$(pv_shell "su 0 sqlite3 /data/system/locksettings.db \"select value from locksettings where name='lockscreen.password_type' and user=0\"" 2>/dev/null | tr -d '\r')
        [ -n "$t" ] && [ "$t" != 0 ]
    fi
}

pvp_wait_focus() { # REGEX SECONDS
    local deadline=$((SECONDS + $2))
    until pv_focus | grep -q -E "$1"; do
        [ $SECONDS -lt $deadline ] || { pv_log "waited $2 s for $1; focus is $(pv_focus)"; return 1; }
        pv_sleep 1
    done
}

pvp_touch() { pv_adb emu finger touch "$1" >/dev/null; pv_adb emu finger remove >/dev/null; }

# pvp_enroll FINGER: enrols emulator finger FINGER through Settings; true when the number of enrolled
# fingerprints went up. API 37: FINGERPRINT_ENROLL with MORE and I AGREE; API 28: FINGERPRINT_ENROLL
# with NEXT; API 24: Security › Fingerprint, "Continue", NEXT on "Find the sensor". The device PIN is
# typed where it is asked for.
pvp_enroll() {
    local finger=$1 api before deadline focus nodes last_pin=-100 touches=0 i count
    api=$(pv_api)
    before=$(pvp_fingers)
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_unlock >/dev/null 2>&1
    if [ "$api" -lt 26 ]; then
        pv_shell am start -a android.settings.SECURITY_SETTINGS >/dev/null
        pv_ui_wait '^Fingerprint[|]' 20 && pv_ui_tap '^Fingerprint[|]' >/dev/null || { pv_log "no Fingerprint entry in Security"; return 1; }
    else
        pv_shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
    fi
    pv_sleep 2
    deadline=$((SECONDS + 240))
    while [ $SECONDS -lt $deadline ]; do
        focus=$(pv_focus)
        nodes=$(pv_ui_nodes)
        if printf '%s\n' "$nodes" | grep -q '|password_entry|'; then
            if [ $((SECONDS - last_pin)) -ge 6 ]; then
                pv_shell input text "$PV_DEVICE_PIN" >/dev/null
                pv_shell input keyevent 66 >/dev/null
                last_pin=$SECONDS
            fi
            pv_sleep 2
            continue
        fi
        case "$focus" in
            *FingerprintEnrollFinish*)
                for i in 1 2 3 4 5 6; do
                    pv_ui_tap '^(DONE|Done)[|]' "$nodes" >/dev/null && break
                    pv_sleep 1
                    nodes=$(pv_ui_nodes)
                done
                pv_sleep 2
                pv_shell input keyevent KEYCODE_HOME >/dev/null
                count=$(pvp_fingers)
                [ "$count" -gt "$before" ] 2>/dev/null && return 0
                pv_log "enrolment of finger $finger finished, but the count went from $before to $count"
                return 1 ;;
            *FingerprintEnrollEnrolling*)
                pvp_touch "$finger"; touches=$((touches + 1)); pv_sleep 1.2 ;;
            *FingerprintEnrollFindSensor*)
                if ! pv_ui_tap '^(NEXT|Next)[|]' "$nodes" >/dev/null; then
                    pvp_touch "$finger"; touches=$((touches + 1))
                fi
                pv_sleep 1.2 ;;
            *Keyguard* | *NotificationShade* | *StatusBar*)
                pv_unlock >/dev/null 2>&1 ;;
            *FingerprintSettings*)
                # API 24: the list of fingerprints; "Add fingerprint" starts the enrolment.
                pv_ui_tap '^(Add fingerprint|ADD FINGERPRINT)[|]' "$nodes" >/dev/null || pv_sleep 1
                pv_sleep 1.5 ;;
            *)
                pv_ui_tap '^(I AGREE|I agree|AGREE|Agree|MORE|More|NEXT|Next|CONTINUE|Continue)[|]' "$nodes" >/dev/null || true
                pv_sleep 1.5 ;;
        esac
        [ $touches -le 40 ] || { pv_log "too many touches without finishing"; return 1; }
    done
    pv_log "the enrolment of finger $finger did not finish (focus $(pv_focus))"
    return 1
}

# pvp_delete_all_fingers: Settings › fingerprints, each one deleted (API 37: the "Delete" icon of each
# finger, "Delete" in its dialog; API 24 and 28: each finger's entry, then "DELETE"); true when none
# is left.
pvp_delete_all_fingers() {
    local api i nodes
    api=$(pv_api)
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_unlock >/dev/null 2>&1
    pv_shell am start -a android.settings.FINGERPRINT_SETTINGS >/dev/null
    pv_sleep 2.5
    for i in $(seq 1 12); do
        [ "$(pvp_fingers)" -le 0 ] 2>/dev/null && break
        nodes=$(pv_ui_nodes)
        if printf '%s\n' "$nodes" | grep -q '|password_entry|'; then
            pv_shell input text "$PV_DEVICE_PIN" >/dev/null
            pv_shell input keyevent 66 >/dev/null
            pv_sleep 2.5
            continue
        fi
        if pv_ui_tap '^(Delete|DELETE|Yes, delete|YES, DELETE|Remove|REMOVE)[|]' "$nodes" >/dev/null; then
            pv_sleep 2
            continue
        fi
        if [ "$api" -ge 30 ]; then
            pv_ui_tap '^[|]Delete Finger|^Delete Finger' "$nodes" >/dev/null || { pv_log "no 'Delete Finger' on the screen (focus $(pv_focus))"; return 1; }
        else
            pv_ui_tap '^Finger [0-9]+[|]' "$nodes" >/dev/null || { pv_log "no 'Finger N' entry on the screen (focus $(pv_focus))"; return 1; }
        fi
        pv_sleep 1.5
    done
    pv_shell input keyevent KEYCODE_HOME >/dev/null
    [ "$(pvp_fingers)" = 0 ] || { pv_log "fingerprints left: $(pvp_fingers)"; return 1; }
}

# pvp_lock_clear: the screen lock removed. API 26 and newer: locksettings clear --old 1111. API 24 has
# no locksettings command: Settings › Screen lock, the PIN, "None", "Yes, remove" (which also removes
# the fingerprints).
pvp_lock_clear() {
    local out nodes i
    if [ "$(pv_api)" -ge 26 ]; then
        out=$(pv_shell locksettings clear --old "$PV_DEVICE_PIN" 2>&1 | tr -d '\r')
        pv_log "locksettings clear: $out"
        printf '%s\n' "$out" | grep -q -i 'cleared' && return 0
        return 1
    fi
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_unlock >/dev/null 2>&1
    pv_shell am start -a android.app.action.SET_NEW_PASSWORD >/dev/null
    pv_sleep 2
    for i in $(seq 1 12); do
        nodes=$(pv_ui_nodes)
        if printf '%s\n' "$nodes" | grep -q '|password_entry|'; then
            pv_shell input text "$PV_DEVICE_PIN" >/dev/null
            pv_shell input keyevent 66 >/dev/null
            pv_sleep 2
        elif pv_ui_tap '[|]lock_none$' "$nodes" >/dev/null; then
            pv_sleep 1.5
        elif pv_ui_tap '^(YES, REMOVE|Yes, remove|OK)[|]' "$nodes" >/dev/null; then
            pv_sleep 2
            pv_shell input keyevent KEYCODE_HOME >/dev/null
            pvp_has_lock && { pv_log "a screen lock is still set"; return 1; }
            return 0
        else
            pv_sleep 1
        fi
    done
    pv_log "the screen lock was not removed (focus $(pv_focus))"
    return 1
}

# pvp_lock_set: the screen lock set to the device PIN 1111 (locksettings set-pin on API 26 and newer;
# Settings on API 24, as boot.sh --provision does).
pvp_lock_set() {
    local out
    if [ "$(pv_api)" -ge 26 ]; then
        out=$(pv_shell locksettings set-pin "$PV_DEVICE_PIN" 2>&1 | tr -d '\r')
        pv_log "locksettings set-pin: $out"
        printf '%s\n' "$out" | grep -q 'set to' && return 0
        return 1
    fi
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_shell am start -a android.app.action.SET_NEW_PASSWORD >/dev/null
    pvp_wait_focus 'ChooseLockGeneric' 20 || return 1
    pv_ui_wait '[|]lock_pin$' 10 && pv_ui_tap '[|]lock_pin$' >/dev/null || { pv_log "no PIN choice on the screen-lock screen"; return 1; }
    pv_sleep 1.5
    # "Secure start-up" (require the PIN to start the phone) may be offered first: "No".
    pv_ui_tap '^(NO THANKS|No thanks|NO|No)[|]' >/dev/null && pv_sleep 1.5
    pv_ui_tap '^(CONTINUE|Continue)[|]' >/dev/null && pv_sleep 1.5
    pvp_wait_focus 'ChooseLockPassword' 20 && pv_ui_wait '[|]password_entry$' 10 || return 1
    pv_shell input text "$PV_DEVICE_PIN" >/dev/null
    pv_ui_wait '^CONTINUE[|]' 10 && pv_ui_tap '^CONTINUE[|]' >/dev/null || { pv_log "no CONTINUE"; return 1; }
    pv_ui_wait '^Confirm your PIN[|]' 15 || return 1
    pv_shell input text "$PV_DEVICE_PIN" >/dev/null
    pv_ui_wait '^OK[|]' 10 && pv_ui_tap '^OK[|]' >/dev/null || { pv_log "no OK"; return 1; }
    pvp_wait_focus 'RedactionInterstitial' 20 || return 1
    pv_ui_tap '^DONE[|]' >/dev/null || return 1
    pv_sleep 1.5
    pv_shell input keyevent KEYCODE_HOME >/dev/null
}

# pvp_restore_base: the screen lock 1111 and fingers 1 and 2 enrolled, as the base has them. Fingers
# that are enrolled already are kept; only what is missing is added.
pvp_restore_base() {
    pvp_has_lock || pvp_lock_set || return 1
    local n
    n=$(pvp_fingers)
    [ "$n" -ge 1 ] 2>/dev/null || pvp_enroll 1 || return 1
    n=$(pvp_fingers)
    [ "$n" -ge 2 ] 2>/dev/null || pvp_enroll 2 || return 1
}
