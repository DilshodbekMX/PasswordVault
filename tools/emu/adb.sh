#!/usr/bin/env bash
# tools/emu/adb.sh - the one way the project talks to a private emulator.
#
# As a command:
#   tools/emu/adb.sh -s SERIAL ADB-ARGS...      adb ADB-ARGS on SERIAL
#   tools/emu/adb.sh -s SERIAL --wait           wait (60 s) until SERIAL answers again
#   tools/emu/adb.sh -s SERIAL --reboot [S]     adb reboot, then wait for the new boot (S seconds,
#                                               default 300), unlock, launcher in front
#   tools/emu/adb.sh -s SERIAL --wait-boot [S]  the waiting part alone
#   tools/emu/adb.sh -s SERIAL --unlock         dismiss the keyguard with the device PIN (1111)
#   tools/emu/adb.sh -s SERIAL --step NAME [needs-debuggable] [needs-root] -- COMMAND...
#                                               run COMMAND as a step: prints PASS, FAIL or SKIPPED
#   tools/emu/adb.sh --connected SERIAL... [-- GRADLE-ARGS...]
#                                               :app:connectedDebugAndroidTest on exactly these
#                                               devices (ANDROID_SERIAL), then --connected-results
#   tools/emu/adb.sh --connected-results DIR SERIAL...
#                                               after connectedDebugAndroidTest: did every SERIAL
#                                               run tests, all passing, and no other device?
# As a library (bash):
#   source tools/emu/adb.sh
#   pv_use SERIAL                 choose the device; refuses (exit 3) a serial boot.sh did not
#                                 start for this worktree, and always refuses emulator-5554
#   pv_adb ADB-ARGS...            adb -s "$PV_SERIAL" ADB-ARGS..., waiting up to 60 s for the
#                                 device to come back if adb lost it (an adb server restart)
#   pv_shell CMD...               pv_adb shell CMD...
#   pv_step NAME [needs-debuggable] [needs-root] -- COMMAND...
#                                 run COMMAND, print "PASS NAME" or "FAIL NAME (exit N)"; with a
#                                 marker that cannot be met on this device or build, print
#                                 "SKIPPED NAME (reason)" and do not run it
#   pv_summary                    print the counts; returns 1 if any step failed
#   pv_reboot [SECONDS], pv_wait_booted [SECONDS], pv_unlock, pv_keyguard_showing, pv_focus, pv_ui_nodes,
#   pv_ui_tap REGEX, pv_ui_wait REGEX SECONDS, pv_is_debuggable [PACKAGE], pv_has_root, pv_api
#
# Every adb call names its device with -s. This file never runs "adb kill-server" and never
# talks to emulator-5554 (the user's own device). See tools/emu/README.md.

# ---- configuration -------------------------------------------------------------------------
PV_LAB=${PV_LAB:-/home/asus/pvlab}
PV_SDK=${PV_SDK:-${ANDROID_HOME:-/home/asus/Android/Sdk}}
PV_ADB=${PV_ADB:-$PV_SDK/platform-tools/adb}
PV_PACKAGE=${PV_PACKAGE:-io.github.dilshodbekmx.passwordvault}
PV_DEVICE_PIN=1111
PV_ADB_WAIT_S=${PV_ADB_WAIT_S:-60}
PV_RUN=$PV_LAB/run
PV_REG=$PV_RUN/devices
PV_LOCKFILE=$PV_RUN/ports.lock
PV_USER_PORT=5554
PV_POOL_FIRST=5556
PV_POOL_LAST=5578
PV_MAX_PACKAGE_DEVICES=6
PV_EMU_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

pv_integrator_port() { # API -> the integrator's fixed console port
    case "$1" in 37) echo 5580 ;; 28) echo 5582 ;; 24) echo 5584 ;; *) return 1 ;; esac
}

# The owner of a device is the worktree whose tools/emu started it (or PV_EMU_OWNER if set).
pv_owner() {
    if [ -n "${PV_EMU_OWNER:-}" ]; then echo "$PV_EMU_OWNER"; return; fi
    git -C "$PV_EMU_DIR" rev-parse --show-toplevel 2>/dev/null || (cd "$PV_EMU_DIR/../.." && pwd)
}

pv_log() { echo "[pv-emu $(date +%T)] $*" >&2; }
pv_die() { pv_log "ERROR: $1"; exit "${2:-1}"; }
pv_sleep() { sleep "$1" 2>/dev/null || python3 -c "import time; time.sleep($1)"; }

# ---- the device registry (written by boot.sh, read by every script) ------------------------
# One file per device, $PV_REG/emulator-PORT, with key=value lines:
#   serial port api avd owner mode(package|integrator|provision) pid (the qemu process, 0 while
#   starting) booter (the boot.sh process) epoch started log tmpfiles (its overlays in /tmp)

pv_entry_get() { # FILE KEY
    sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1
}

pv_is_emulator_pid() { # PID PORT -> 0 if PID is the emulator (qemu) serving console PORT
    local cmd
    [ -n "$1" ] && [ "$1" != 0 ] || return 1
    cmd=$( { tr '\0' ' ' < "/proc/$1/cmdline"; } 2>/dev/null ) || return 1
    case "$cmd" in
        *qemu-system*" -port $2 "*) return 0 ;;
    esac
    return 1
}

pv_find_emulator_pid() { # PORT -> prints the pid of the emulator on that console port
    local p
    for p in $(pgrep -f 'qemu-system' 2>/dev/null); do
        if pv_is_emulator_pid "$p" "$1"; then echo "$p"; return 0; fi
    done
    return 1
}

pv_entry_alive() { # FILE -> 0 if its emulator runs, or boot.sh is still starting it
    local f=$1 pid port booter epoch cmd
    [ -f "$f" ] || return 1
    pid=$(pv_entry_get "$f" pid); port=$(pv_entry_get "$f" port)
    pv_is_emulator_pid "$pid" "$port" && return 0
    if [ -z "$pid" ] || [ "$pid" = 0 ]; then
        # Reserved, emulator not started yet: alive while its boot.sh runs, at most 15 minutes
        # (a pid reused after a reboot must not keep the port forever).
        booter=$(pv_entry_get "$f" booter); epoch=$(pv_entry_get "$f" epoch)
        [ -n "$booter" ] && [ -n "$epoch" ] && [ $(( $(date +%s) - epoch )) -lt 900 ] || return 1
        cmd=$( { tr '\0' ' ' < "/proc/$booter/cmdline"; } 2>/dev/null ) || return 1
        case "$cmd" in *boot.sh*) return 0 ;; esac
    fi
    return 1
}

pv_locked() { # FUNCTION ARGS... -> runs it in a subshell holding the machine-wide device lock
    mkdir -p "$PV_REG" "$PV_RUN/logs" || return 1
    ( flock -w 300 9 || { pv_log "could not take the lock $PV_LOCKFILE"; exit 99; }; "$@" ) 9>>"$PV_LOCKFILE"
}

pv_emulator_tmpfiles() { # PID -> the read-only overlays it has open, comma-separated
    [ -n "$1" ] && [ "$1" != 0 ] || return 0
    find "/proc/$1/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null \
        | grep -E '^/tmp/android-[^/]+/emulator-[^/]+\.qcow2$' | sort -u | paste -sd, -
}

pv_file_in_use() { # PATH -> 0 if a running emulator has it open
    local p
    for p in $(pgrep -f 'qemu-system' 2>/dev/null); do
        find "/proc/$p/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null | grep -qxF -- "$1" && return 0
    done
    return 1
}

pv_remove_tmpfiles() { # COMMA-LIST -> deletes overlays an emulator left behind (killed hard)
    local t
    for t in $(echo "$1" | tr ',' ' '); do
        case "$t" in
            /tmp/android-*/emulator-*.qcow2)
                if [ -f "$t" ] && ! pv_file_in_use "$t"; then rm -f "$t" && pv_log "removed leftover $t"; fi ;;
        esac
    done
}

pv_kill_emulator() { # PORT [PID] -> stops that emulator: console "kill", then TERM, then KILL
    local port=$1 pid=${2:-} tmp i
    if [ -z "$pid" ] || [ "$pid" = 0 ] || ! pv_is_emulator_pid "$pid" "$port"; then
        pid=$(pv_find_emulator_pid "$port") || return 0
    fi
    tmp=$(pv_emulator_tmpfiles "$pid")
    "$PV_ADB" -s "emulator-$port" emu kill >/dev/null 2>&1
    for i in $(seq 1 30); do pv_is_emulator_pid "$pid" "$port" || break; pv_sleep 1; done
    if pv_is_emulator_pid "$pid" "$port"; then
        pv_log "emulator-$port did not exit after 'emu kill'; sending TERM to $pid"
        kill -TERM "$pid" 2>/dev/null
        for i in $(seq 1 20); do pv_is_emulator_pid "$pid" "$port" || break; pv_sleep 1; done
    fi
    if pv_is_emulator_pid "$pid" "$port"; then
        pv_log "emulator-$port still running; sending KILL to $pid"
        kill -KILL "$pid" 2>/dev/null
        pv_sleep 2
    fi
    pv_remove_tmpfiles "$tmp"
    ! pv_is_emulator_pid "$pid" "$port"
}

pv_forget_stale() { # (under pv_locked) drops entries whose emulator and boot.sh are both gone
    local f
    for f in "$PV_REG"/emulator-*; do
        [ -f "$f" ] || continue
        pv_entry_alive "$f" && continue
        pv_log "forgetting $(basename "$f") (started for $(pv_entry_get "$f" owner)): its emulator is gone"
        pv_remove_tmpfiles "$(pv_entry_get "$f" tmpfiles)"
        rm -f "$f"
    done
}

pv_entry_set() { # FILE KEY VALUE -> rewrites one key atomically
    local tmp
    tmp=$(mktemp "$(dirname "$1")/.entry.XXXXXX") || return 1
    { grep -v "^$2=" "$1" 2>/dev/null; echo "$2=$3"; } > "$tmp" && mv -f "$tmp" "$1"
}

pv_refuse() { echo "pv-emu: refusing $1: $2" >&2; exit 3; }

pv_require_serial() { # SERIAL -> returns only if this worktree may drive it
    local s=$1 f port owner me
    case "$s" in
        emulator-[0-9]*) ;;
        *) pv_refuse "$s" "only private emulators (emulator-PORT) started by tools/emu/boot.sh are allowed" ;;
    esac
    port=${s#emulator-}
    [ "$port" != "$PV_USER_PORT" ] || pv_refuse "$s" "port $PV_USER_PORT is the user's own emulator; nobody touches it"
    f=$PV_REG/$s
    [ -f "$f" ] || pv_refuse "$s" "tools/emu/boot.sh did not start it (no $f)"
    owner=$(pv_entry_get "$f" owner); me=$(pv_owner)
    [ "$owner" = "$me" ] || pv_refuse "$s" "it was started for $owner, not for $me"
    pv_entry_alive "$f" || pv_refuse "$s" "its emulator is not running any more (tools/emu/stop.sh $s forgets it)"
}

pv_use() { # SERIAL
    pv_require_serial "$1"
    PV_SERIAL=$1
    export PV_SERIAL
}

# ---- adb -------------------------------------------------------------------------------------
# adb errors that mean the command never reached the device: safe to wait and try again.
pv_transport_error() { # FILE-WITH-STDERR
    grep -q -i -E "device '[^']*' not found|device offline|no devices/emulators found|cannot connect to daemon|daemon not running|failed to check server version|device still (connecting|authorizing)|device unauthorized|failed to get feature set" "$1"
}

pv_wait_device() { # SERIAL [SECONDS] -> 0 once "adb get-state" says device
    local s=$1 deadline state offline_since="" kicked=0
    deadline=$((SECONDS + ${2:-$PV_ADB_WAIT_S}))
    while :; do
        state=$("$PV_ADB" -s "$s" get-state 2>/dev/null | tr -d '\r')
        [ "$state" = device ] && return 0
        if ! pv_entry_alive "$PV_REG/$s"; then pv_log "$s: its emulator is not running"; return 1; fi
        if [ "$state" = offline ]; then
            [ -n "$offline_since" ] || offline_since=$SECONDS
            # A device can stay offline after another program restarted the adb server.
            # Kick only this device's connection from the host side; never restart the server.
            if [ $kicked = 0 ] && [ $((SECONDS - offline_since)) -ge 10 ]; then
                pv_log "$s stays offline; adb -s $s reconnect"
                "$PV_ADB" -s "$s" reconnect >/dev/null 2>&1
                kicked=1
            fi
        else
            offline_since=""
        fi
        [ $SECONDS -lt "$deadline" ] || return 1
        pv_sleep 2
    done
}

pv_adb() { # ADB-ARGS... on $PV_SERIAL
    [ -n "${PV_SERIAL:-}" ] || pv_die "pv_adb: no device chosen; call pv_use SERIAL first" 2
    local errf rc deadline
    errf=$(mktemp "${TMPDIR:-/tmp}/pv-adb.XXXXXX")
    deadline=$((SECONDS + PV_ADB_WAIT_S))
    while :; do
        "$PV_ADB" -s "$PV_SERIAL" "$@" 2>"$errf"
        rc=$?
        if [ $rc -ne 0 ] && pv_transport_error "$errf" && [ $SECONDS -lt $deadline ]; then
            pv_log "adb lost $PV_SERIAL ($(tr '\n' ' ' < "$errf" | cut -c1-160)); waiting for it"
            pv_wait_device "$PV_SERIAL" $((deadline - SECONDS)) && continue
        fi
        break
    done
    cat "$errf" >&2
    rm -f "$errf"
    return $rc
}

pv_shell() { pv_adb shell "$@"; }

pv_getprop() { pv_adb shell getprop "$1" 2>/dev/null | tr -d '\r'; }

pv_api() { pv_getprop ro.build.version.sdk; }

# ---- what a step needs --------------------------------------------------------------------
# needs-debuggable: the installed app is debuggable (run-as works): the debug build, not staging.
pv_is_debuggable() { # [PACKAGE]
    local pkg=${1:-$PV_PACKAGE}
    pv_adb shell run-as "$pkg" id 2>/dev/null | grep -q 'uid='
}

# needs-root: "adb root" can work, which needs a userdebug or eng image (API 24 and 28 here;
# the API 37 image is a user build without root).
pv_has_root() {
    [ "$(pv_getprop ro.debuggable)" = 1 ]
}

# ---- steps and their results --------------------------------------------------------------
PV_PASSED=${PV_PASSED:-0}
PV_FAILED=${PV_FAILED:-0}
PV_SKIPPED=${PV_SKIPPED:-0}

pv_report() { # PASS|FAIL|SKIPPED NAME [DETAIL]
    local line="$1 $2"
    [ -z "${3:-}" ] || line="$line ($3)"
    echo "$line"
    case "$1" in
        PASS) PV_PASSED=$((PV_PASSED + 1)) ;;
        FAIL) PV_FAILED=$((PV_FAILED + 1)) ;;
        SKIPPED) PV_SKIPPED=$((PV_SKIPPED + 1)) ;;
    esac
}

pv_step() { # NAME [needs-debuggable] [needs-root] -- COMMAND...
    local name=$1 marker rc
    shift
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do
        marker=$1; shift
        case "$marker" in
            needs-debuggable)
                if ! pv_is_debuggable; then
                    pv_report SKIPPED "$name" "needs-debuggable: $PV_PACKAGE on $PV_SERIAL is not debuggable or not installed"
                    return 0
                fi ;;
            needs-root)
                if ! pv_has_root; then
                    pv_report SKIPPED "$name" "needs-root: the image of $PV_SERIAL has no root"
                    return 0
                fi ;;
            *) pv_die "pv_step: unknown marker '$marker' (needs-debuggable, needs-root)" 2 ;;
        esac
    done
    [ "${1:-}" = "--" ] && shift
    [ $# -gt 0 ] || pv_die "pv_step $name: no command after --" 2
    "$@"
    rc=$?
    if [ $rc -eq 0 ]; then pv_report PASS "$name"; else pv_report FAIL "$name" "exit $rc"; fi
    return $rc
}

pv_summary() {
    echo "SUMMARY ${PV_SERIAL:-}: $PV_PASSED passed, $PV_FAILED failed, $PV_SKIPPED skipped"
    [ "$PV_FAILED" -eq 0 ]
}

# ---- screens ------------------------------------------------------------------------------
pv_focus() { # prints the focused window, e.g. "com.android.settings/com.android.settings.Foo"
    pv_adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m 1 'mCurrentFocus' | sed 's/.* u[0-9]* //; s/}$//'
}

# One line per node: text|content-desc|resource-id|clickable|enabled|x|y (x, y = centre).
pv_ui_nodes() {
    local i xml
    for i in 1 2 3; do
        pv_adb shell uiautomator dump /sdcard/pv_ui.xml >/dev/null 2>&1
        xml=$(pv_adb shell cat /sdcard/pv_ui.xml 2>/dev/null)
        case "$xml" in *"<hierarchy"*) break ;; esac
        pv_sleep 1
    done
    printf '%s' "$xml" | python3 -c '
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    a = n.attrib
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", a.get("bounds", "[0,0][0,0]")))
    text = a.get("text", "").replace("\n", " ").replace("|", "/")
    desc = a.get("content-desc", "").replace("\n", " ").replace("|", "/")
    rid = a.get("resource-id", "").split("/")[-1]
    print("|".join([text, desc, rid, a.get("clickable", ""), a.get("enabled", ""),
                    str((x1 + x2) // 2), str((y1 + y2) // 2)]))
'
}

# REGEX is an extended regular expression matched against "text|content-desc|resource-id";
# write a literal | as [|], e.g. '^NEXT[|]' (text NEXT) or '[|]lock_pin$' (id lock_pin).
pv_ui_match() { # REGEX < NODES -> prints the first enabled matching node
    PV_RE=$1 awk -F'|' '$5 == "true" && ($1 "|" $2 "|" $3) ~ ENVIRON["PV_RE"] { print; exit }'
}

pv_ui_tap() { # REGEX [NODES] -> taps the centre of the first enabled matching node
    local nodes=${2:-} line x y
    [ -n "$nodes" ] || nodes=$(pv_ui_nodes)
    line=$(printf '%s\n' "$nodes" | pv_ui_match "$1")
    [ -n "$line" ] || return 1
    x=$(echo "$line" | cut -d'|' -f6); y=$(echo "$line" | cut -d'|' -f7)
    pv_adb shell input tap "$x" "$y"
}

pv_ui_wait() { # REGEX SECONDS -> 0 once an enabled node matches
    local deadline=$((SECONDS + ${2:-10}))
    while :; do
        [ -z "$(pv_ui_nodes | pv_ui_match "$1")" ] || return 0
        [ $SECONDS -lt $deadline ] || return 1
        pv_sleep 1
    done
}

pv_keyguard_showing() { # 0 if the keyguard (lock screen) is showing
    pv_adb shell dumpsys window policy 2>/dev/null | tr -d '\r' \
        | sed -n '/KeyguardServiceDelegate/,$p' | grep -m 1 -E '^ *showing=' | grep -q 'showing=true'
}

pv_unlock() { # [PIN] -> dismisses the keyguard with the device PIN
    local pin=${1:-$PV_DEVICE_PIN} i
    for i in 1 2 3 4; do
        pv_keyguard_showing || return 0
        pv_adb shell input keyevent KEYCODE_WAKEUP
        pv_adb shell input keyevent 82   # MENU: shows the PIN bouncer
        pv_sleep 1
        pv_adb shell input text "$pin"
        pv_adb shell input keyevent 66   # ENTER
        pv_sleep 2
    done
    ! pv_keyguard_showing
}

# "adb reboot", then waits until the device runs a new boot (another boot_id) and is ready. A
# read-only device keeps its changes across a reboot of the guest; they are dropped only when
# the emulator exits.
pv_reboot() { # [SECONDS] (default 300) on $PV_SERIAL
    local deadline=$((SECONDS + ${1:-300})) before now
    before=$(pv_shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')
    [ -n "$before" ] || { pv_log "cannot read the boot id of $PV_SERIAL"; return 1; }
    pv_adb reboot || return 1
    while :; do
        now=$("$PV_ADB" -s "$PV_SERIAL" shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')
        [ -n "$now" ] && [ "$now" != "$before" ] && break
        [ $SECONDS -lt $deadline ] || { pv_log "$PV_SERIAL did not come back from the reboot"; return 1; }
        pv_sleep 2
    done
    pv_wait_booted $((deadline - SECONDS))
}

# After a boot (boot.sh) or a reboot (pv_reboot): waits for sys.boot_completed and the
# package manager, dismisses the lock screen with the PIN, and waits until the launcher has been
# in front three times in a row, 2 s apart.
pv_wait_booted() { # [SECONDS] (default 300) on $PV_SERIAL
    local deadline=$((SECONDS + ${1:-300})) v focus stable=0
    while :; do
        pv_entry_alive "$PV_REG/$PV_SERIAL" || { pv_log "the emulator of $PV_SERIAL is not running"; return 1; }
        v=$("$PV_ADB" -s "$PV_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
        [ "$v" = 1 ] && break
        [ $SECONDS -lt $deadline ] || { pv_log "no sys.boot_completed on $PV_SERIAL in time"; return 1; }
        pv_sleep 2
    done
    # Full-disk encryption asking for a start-up PIN (API 28 if the PIN had been set without
    # "require_password_to_decrypt" 0, which provisioning sets first): enter the PIN.
    if [ "$(pv_getprop vold.decrypt)" = trigger_restart_min_framework ]; then
        pv_log "$PV_SERIAL asks for the start-up PIN; entering it"
        pv_shell input text "$PV_DEVICE_PIN"
        pv_shell input keyevent 66
        pv_sleep 5
        until [ "$(pv_getprop vold.decrypt)" = trigger_restart_framework ] && [ "$(pv_getprop sys.boot_completed)" = 1 ]; do
            [ $SECONDS -lt $deadline ] || { pv_log "$PV_SERIAL did not decrypt"; return 1; }
            pv_sleep 2
        done
    fi
    until pv_shell pm path android 2>/dev/null | grep -q '^package:'; do
        [ $SECONDS -lt $deadline ] || { pv_log "the package manager of $PV_SERIAL does not answer"; return 1; }
        pv_sleep 2
    done
    while :; do
        if pv_keyguard_showing; then
            stable=0
            pv_unlock || pv_log "the PIN did not dismiss the lock screen yet"
        else
            focus=$(pv_focus)
            case "$focus" in
                *[Ll]auncher*) stable=$((stable + 1)); [ $stable -ge 3 ] && return 0 ;;
                *"Not Responding"*|*"isn't responding"*)
                    stable=0
                    pv_log "a system dialog is open ($focus); choosing Wait"
                    pv_ui_tap '^(Wait|WAIT)[|]' || pv_shell input keyevent KEYCODE_BACK ;;
                *)
                    stable=0
                    pv_shell input keyevent KEYCODE_HOME ;;
            esac
        fi
        if [ $SECONDS -ge $deadline ]; then
            if pv_keyguard_showing; then pv_log "the lock screen of $PV_SERIAL is still showing"; return 1; fi
            pv_log "warning: the launcher never came to the front on $PV_SERIAL (focus: $(pv_focus)); going on"
            return 0
        fi
        pv_sleep 2
    done
}

# ---- connected-test results -------------------------------------------------------------------
# AGP 9.4.1's test runner prints "AndroidTestRunner failed on emulator-N" and still ends with
# BUILD SUCCESSFUL (exit code 0) when it cannot install the APK on one of the devices, for
# example on API 24 when the app is already installed (it installs without -r there). So after
# "ANDROID_SERIAL=... tools/gradle.sh :app:connectedDebugAndroidTest", check every serial:
#   pv_connected_results app/build/outputs/androidTest-results/connected/debug SERIAL...
# 0 if each SERIAL has a JUnit report with at least one test and no failure or error.
pv_connected_results() { # DIR SERIAL...
    local dir=$1
    shift
    python3 - "$dir" "$@" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
d, serials = sys.argv[1], sys.argv[2:]
seen = {}
for f in sorted(glob.glob(d + "/TEST-*.xml")):
    for suite in ET.parse(f).getroot().iter("testsuite"):
        devs = [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]
        for dev in devs:
            t, fl, er = seen.get(dev, (0, 0, 0))
            seen[dev] = (t + int(suite.get("tests", 0)), fl + int(suite.get("failures", 0)), er + int(suite.get("errors", 0)))
bad = 0
for s in serials:
    t, fl, er = seen.get(s, (0, 0, 0))
    ok = s in seen and t > 0 and fl == 0 and er == 0
    bad |= not ok
    print(("PASS" if ok else "FAIL") + f" connected tests on {s} ({t} tests, {fl} failures, {er} errors"
          + ("" if s in seen else ", no report: the tests did not run there") + ")")
for dev in sorted(set(seen) - set(serials)):
    print(f"FAIL connected tests also ran on {dev}, which is not among the serials given")
    bad = 1
sys.exit(bad)
EOF
}

pv_gradle() { # GRADLE-ARGS... -> through tools/gradle.sh once it exists (package 0.1)
    local repo
    repo=$(cd "$PV_EMU_DIR/../.." && pwd)
    if [ -x "$repo/tools/gradle.sh" ]; then
        "$repo/tools/gradle.sh" "$@"
    else
        (cd "$repo" && JAVA_HOME=/home/asus/.jdks/jdk-25 ANDROID_HOME=$PV_SDK ./gradlew --no-daemon "$@")
    fi
}

# Runs :app:connectedDebugAndroidTest on exactly the given devices of this worktree and checks
# that each of them reported passing tests.
pv_connected() { # SERIAL... [-- GRADLE-ARGS...]
    local serials=() s repo results rc
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do serials+=("$1"); shift; done
    [ "${1:-}" = "--" ] && shift
    [ ${#serials[@]} -gt 0 ] || pv_die "--connected: no serial given" 2
    for s in "${serials[@]}"; do pv_require_serial "$s"; done
    # AGP 9.4.1 installs without -r on API 24 and then skips a device where the app exists.
    for s in "${serials[@]}"; do
        PV_SERIAL=$s pv_adb uninstall "$PV_PACKAGE.test" >/dev/null 2>&1
        PV_SERIAL=$s pv_adb uninstall "$PV_PACKAGE" >/dev/null 2>&1
    done
    repo=$(cd "$PV_EMU_DIR/../.." && pwd)
    results=$repo/app/build/outputs/androidTest-results/connected/debug
    rm -rf "$results"
    pv_log "connectedDebugAndroidTest with ANDROID_SERIAL=$(IFS=,; echo "${serials[*]}")"
    ANDROID_SERIAL=$(IFS=,; echo "${serials[*]}") pv_gradle :app:connectedDebugAndroidTest "$@"
    rc=$?
    pv_connected_results "$results" "${serials[@]}" || rc=1
    return $rc
}

# ---- command mode -----------------------------------------------------------------------------
pv_usage() { # FILE -> prints the file's header comment and exits 2
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$1" >&2
    exit 2
}

pv_adb_usage() { pv_usage "${BASH_SOURCE[0]}"; }

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    case "${1:-}" in
        --connected-results)
            [ $# -ge 3 ] || pv_adb_usage
            shift
            pv_connected_results "$@"
            exit $? ;;
        --connected)
            [ $# -ge 2 ] || pv_adb_usage
            shift
            pv_connected "$@"
            exit $? ;;
    esac
    [ "${1:-}" = "-s" ] && [ $# -ge 3 ] || pv_adb_usage
    pv_use "$2"
    shift 2
    case "$1" in
        --wait) pv_wait_device "$PV_SERIAL" "$PV_ADB_WAIT_S"; exit $? ;;
        --wait-boot) pv_wait_booted "${2:-300}"; exit $? ;;
        --reboot) pv_reboot "${2:-300}"; exit $? ;;
        --unlock) pv_unlock; exit $? ;;
        --step)
            [ $# -ge 4 ] || pv_adb_usage
            shift
            pv_step "$@"
            exit $? ;;
        *) pv_adb "$@"; exit $? ;;
    esac
fi
