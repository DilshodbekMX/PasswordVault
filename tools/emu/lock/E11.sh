#!/usr/bin/env bash
# tools/emu/lock/E11.sh - lock.md §15.3 check E11 (Android 7, 9, 17): recreation never locks and never
# loses what was typed (lock.md §11; acceptance A8). A rotation with 3 digits typed keeps the dots and
# the charge; a rotation with the fingerprint prompt open delivers its result to the new activity; a
# language change in Settings with the lock delay "Immediately" leaves the app open on the same screen;
# the master-password field keeps its text across a rotation, and nothing of it is in the saved-state
# Bundle (the method of docs/lab/window-input-artifacts/e5_state.py).
#
#   tools/emu/lock/E11.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh; the APK defaults to the debug build.
#
# Steps, after the app is installed afresh and set up with the PIN and fingerprint unlock (finger 1):
#   - Lock now, the prompt's "Use PIN"; 3 digits: the dots read "PIN, 3 of 6 digits entered" and
#     pin.rec is charged; turned to landscape: the activity is another object, the dots still read 3
#     of 6, the charge is the same (still charged, no count changed); the other 3 digits typed in
#     landscape open the app, with row 0 and total 0 (one entry, not two); back to portrait;
#   - Lock now: the prompt opens by itself; turned to landscape with it open: the activity is
#     recreated and the prompt stays (or comes back); finger 1: the result reaches the new activity
#     and the app opens; back to portrait;
#   - Settings › "Lock the app after leaving it" › Immediately; then
#       the language changed with the debug command `locale` (ru, then back to the phone's languages):
#       the activity is recreated in the new language and the app stays open on Settings; this is
#       the lock's part of A8 with the language switch's own code (AppLanguages.setTags), and runs on
#       every build that carries the debug commands;
#       the language changed in Settings › Language (Русский, then System language): the same, through
#       the dialog of build plan 6.1 (pvl_later: SKIPPED until 6.1 is merged);
#   - the app stopped and started again under the window helper's saved-state probe (StateProbe of
#     tools/emu/window/helper, API 26 and newer: SKIPPED on Android 7); the master password typed on the
#     lock screen; turned to landscape and back: the field still holds as many characters as were
#     typed (its mask), and no saved Bundle holds the password, in UTF-8 or UTF-16, while each names
#     the route Locked (so it is the screen's whole state); Unlock opens the app with what the field
#     kept.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E11 "$@"
# The window helper's build and install (tools/emu/window/lib.sh: win_helper_build, win_helper_install).
source "$EMU/window/lib.sh"

# The identity of the app's activity object ("Local Activity <hash>" in dumpsys activity top): another
# one after a recreation.
activity_object() {
    pv_shell dumpsys activity top 2>/dev/null | tr -d '\r' \
        | awk -v a="ACTIVITY $PV_PACKAGE/" 'index($0, a) { f = 1 } f && /Local Activity/ { print $3; exit }'
}

dots() { pva_node pv.pin.dots | cut -d'|' -f2; }

set_up() { pvl_set_up "$PVL_PIN" fingerprint && pvl_expect_fp slot=set; }

ACT=""

three_digits() {
    pvl_lock_now || return 1
    pvl_pad 20 || return 1
    pva_pin "${PVL_PIN:0:3}" || return 1
    pv_sleep 1
    pvl_expect "$(dots)" plural common_pin_dots 3 || return 1
    pvl_expect_pin charged=true row=0 total=0 || return 1
    ACT=$(activity_object)
    [ -n "$ACT" ] || { pv_log "no activity object"; return 1; }
}

rotate_keeps_digits() {
    local after
    pva_rotate 1
    after=$(activity_object)
    [ -n "$after" ] && [ "$after" != "$ACT" ] || { pv_log "the activity was not recreated: $ACT -> $after"; return 1; }
    pvl_wait_screen pad 10 || return 1
    pvl_expect "$(dots)" plural common_pin_dots 3 || return 1
    pvl_status || return 1
    case "$(pvl_lock_state)" in "locked: pad") ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
    pvl_expect_pin charged=true row=0 total=0
}

rest_opens() {
    pva_pin "${PVL_PIN:3:3}" || return 1
    pvl_open_list 30 || return 1
    pvl_expect_pin charged=false row=0 total=0 unseen=0 || return 1
    pva_rotate 0
    pva_on Passwords 10
}

PROMPT_ACT=""

prompt_open() {
    pva_on Passwords 20 && pva_tap pv.list.lockNow || return 1
    pvl_prompt_up 20 || return 1
    PROMPT_ACT=$(activity_object)
    [ -n "$PROMPT_ACT" ] || { pv_log "no activity object"; return 1; }
}

rotate_with_prompt() {
    local after
    pva_rotate 1
    after=$(activity_object)
    [ -n "$after" ] && [ "$after" != "$PROMPT_ACT" ] || { pv_log "the activity was not recreated: $PROMPT_ACT -> $after"; return 1; }
    pvl_prompt_up 10 || return 1
    pvl_status || return 1
    case "$(pvl_item prompt:)" in "prompt: UNLOCK, launch "*) ;; *) pv_log "$(pvl_item prompt:)"; return 1 ;; esac
    pv_sleep 2
    pvl_finger "$PVL_FINGER" || return 1
    pvl_prompt_down 15 || return 1
    pvl_open_list 30 || return 1
    pva_rotate 0
    pva_on Passwords 10
}

immediately() {
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_tap pv.settings.lockDelay && pva_need pv.settings.lockDelay.IMMEDIATELY 10 || return 1
    pva_tap pv.settings.lockDelay.IMMEDIATELY || return 1
    pva_on Settings 10 || return 1
    pv_sleep 1
    pva_absent pv.settings.lockDelay.IMMEDIATELY || { pv_log "the dialog did not close"; return 1; }
}

# still_open ROUTE PID ACT: ROUTE shown, not locked, the same process, another activity object.
still_open() {
    local route=$1 pid=$2 act=$3 now
    pva_on "$route" 20 || return 1
    pv_sleep 3
    pva_absent pv.route.Locked || { pv_log "the app locked"; return 1; }
    pva_on "$route" 5 || return 1
    pvl_status || return 1
    [ "$(pvl_lock_state)" = unlocked ] || { pv_log "the lock is '$(pvl_lock_state)'"; return 1; }
    [ "$(pva_pid)" = "$pid" ] || { pv_log "the process changed: $pid -> $(pva_pid)"; return 1; }
    now=$(activity_object)
    [ -n "$now" ] && [ "$now" != "$act" ] || { pv_log "the activity was not recreated: $act -> $now"; return 1; }
}

locale_command() {
    local pid act out
    pva_on Settings 10 || return 1
    pid=$(pva_pid); act=$(activity_object)
    out=$(pva_debug locale ru) || return 1
    pvl_note "E11: $out"
    still_open Settings "$pid" "$act" || return 1
    act=$(activity_object)
    out=$(pva_debug locale system) || return 1
    still_open Settings "$pid" "$act"
}

# Settings › Language (build plan 6.1's dialog: pv.open.Language, pv.language.dialog,
# pv.language.<CHOICE>), the choice tapped; then the same checks.
language_in_settings() {
    local pid act choice
    pva_on Settings 10 || return 1
    for choice in RU SYSTEM; do
        pid=$(pva_pid); act=$(activity_object)
        pva_reveal pv.open.Language && pva_tap pv.open.Language || return 1
        pva_need pv.language.dialog 15 || return 1
        pva_tap "pv.language.$choice" || return 1
        pv_sleep 2
        still_open Settings "$pid" "$act" || return 1
    done
}

# ---- the master-password field under the saved-state probe ---------------------------------------
PROBE_PID=""

probe_start() {
    local out i pid
    pvl_force_stop || return 1
    win_helper_build && win_helper_install || return 1
    pv_shell logcat -c >/dev/null 2>&1
    out=$(pv_shell am instrument "$WIN_HELPER/.StateProbe" 2>&1 | tr -d '\r')
    case "$out" in *FAILED* | *Error* | *Exception*) pv_log "am instrument: $out"; return 1 ;; esac
    for i in $(seq 1 30); do
        pid=$(pva_pid)
        if [ -n "$pid" ] && pv_shell logcat -d -s PvWinState:I 2>/dev/null | tr -d '\r' | grep -q "READY pid=$pid target=$PV_PACKAGE\$"; then
            PROBE_PID=$pid
            pva_relaunch || return 1
            pvl_wait_screen 'pad|prompt' 30 || return 1
            [ "$(pva_pid)" = "$PROBE_PID" ] || { pv_log "the app is not in the probe's process"; return 1; }
            return 0
        fi
        pv_sleep 0.5
    done
    pv_log "the probe did not start: ${out:-no output}"
    return 1
}

mp_typed() {
    local text mask
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    text=$(pva_text pv.lock.password) || return 1
    mask=$(printf '%*s' "${#PVA_PASSWORD}" '' | sed 's/ /•/g')
    [ "$text" = "$mask" ] || { pv_log "the field reads ${#text} characters, ${#PVA_PASSWORD} were typed"; return 1; }
}

# The Bundles the probe wrote: each must name the route Locked and hold no character run of the
# master password (UTF-8 or UTF-16); at least one.
mp_rotated_saved_state() {
    local text mask out
    pva_rotate 1
    pva_rotate 0
    pva_hide_keyboard
    text=$(pva_text pv.lock.password) || return 1
    mask=$(printf '%*s' "${#PVA_PASSWORD}" '' | sed 's/ /•/g')
    [ "$text" = "$mask" ] || { pv_log "after the rotation the field reads ${#text} characters, ${#PVA_PASSWORD} were typed"; return 1; }
    out=$(pv_shell logcat -d -v threadtime -s PvWinState:I 2>/dev/null | tr -d '\r' | python3 -c '
import base64, re, sys
pid, secret, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
recs = {}
for line in sys.stdin:
    m = re.match(r"\S+ \S+ +(\d+) +\d+ I PvWinState: (SAVE|L64|B64|END) (\d+) ?(.*)$", line.rstrip("\n"))
    if not m or m.group(1) != pid:
        continue
    kind, seq, rest = m.group(2), int(m.group(3)), m.group(4)
    r = recs.setdefault(seq, {"L64": [], "B64": [], "SAVE": None, "END": False})
    if kind in ("L64", "B64"):
        r[kind].append(rest.split(" ", 1)[1])
    elif kind == "SAVE":
        r["SAVE"] = rest
    else:
        r["END"] = True
bad = 0
n = 0
for seq in sorted(recs):
    r = recs[seq]
    if not r["END"] or r["SAVE"] is None:
        continue
    n += 1
    leaves = base64.b64decode("".join(r["L64"])).decode("utf-8", "replace")
    data = base64.b64decode("".join(r["B64"]))
    open("%s-%d.txt" % (prefix, seq), "w").write(leaves)
    named = "nav.Route.Locked" in leaves or "nav.Route.Locked".encode("utf-16-le") in data or b"nav.Route.Locked" in data
    held = secret in leaves or secret.encode("utf-16-le") in data or secret.encode("utf-8") in data
    print("saved state %d: %d leaves, %d bytes, names Locked: %s, holds the master password: %s" % (seq, leaves.count("\n"), len(data), named, held))
    bad |= held or not named
print("bundles=%d" % n)
sys.exit(1 if bad or n == 0 else 0)
' "$PROBE_PID" "$PVA_PASSWORD" "$PVL_OUT/state")
    local rc=$?
    printf '%s\n' "$out" | sed 's/^/  /'
    return $rc
}

mp_unlocks() { pva_submit_open pv.lock.unlock Passwords 90; }

pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN and fingerprint unlock (finger 1)" -- set_up || pvl_end
pv_step "Lock now, \"Use PIN\", 3 digits: 3 dots, the charge on disk" -- three_digits || pvl_end
pv_step "turned to landscape: recreated, still 3 dots, the same charge, still locked" -- rotate_keeps_digits
pv_step "the other 3 digits in landscape open the app as one entry" -- rest_opens || pvl_end
pv_step "Lock now: the prompt opens by itself" -- prompt_open || pvl_end
pv_step "turned with the prompt open: recreated; finger 1's result reaches the new activity and opens" -- rotate_with_prompt
pva_rotate 0
pva_on Passwords 10 || pvl_unlock_pin >/dev/null 2>&1
pv_step "Settings: lock the app after leaving it: Immediately" -- immediately || pvl_end
pv_step "the language changed (debug command locale, ru and back) with Immediately: still open on Settings" -- locale_command
pvl_later 6.1 "the language changed in Settings › Language (Русский, then System language) with Immediately: still open on Settings" -- language_in_settings
if [ "$API" -ge 26 ]; then
    pv_step "the app started again under the saved-state probe: the lock screen" -- probe_start || pvl_end
    pv_step "the master password typed on the lock screen (its mask has every character)" -- mp_typed || pvl_end
    pv_step "turned to landscape and back: the field keeps its text; no saved Bundle holds it" -- mp_rotated_saved_state
    pv_step "Unlock opens the app with what the field kept" -- mp_unlocks
else
    pv_step "the app started again: the lock screen" -- eval 'pvl_force_stop && pva_relaunch && pvl_wait_screen "pad|prompt" 30'
    pv_step "the master password typed on the lock screen (its mask has every character)" -- mp_typed || pvl_end
    pv_step "turned to landscape and back: the field keeps its text" -- eval 'pva_rotate 1; pva_rotate 0; pva_hide_keyboard; [ "$(pva_text pv.lock.password)" = "$(printf "%*s" "${#PVA_PASSWORD}" "" | sed "s/ /•/g")" ]'
    pvl_skip "no saved Bundle holds the master password" "the window helper's saved-state probe needs API 26 (tools/emu/window/state.sh); $SERIAL has API $API"
    pv_step "Unlock opens the app with what the field kept" -- mp_unlocks
fi
pv_shell am force-stop "$PV_PACKAGE" >/dev/null 2>&1
pvl_end
