#!/usr/bin/env bash
# tools/emu/adb.sh - the one way the project talks to a private emulator.
#
# As a command:
#   tools/emu/adb.sh -s SERIAL ADB-ARGS...      adb ADB-ARGS on SERIAL
#   tools/emu/adb.sh -s SERIAL --wait           wait (60 s) until SERIAL answers again
#   tools/emu/adb.sh -s SERIAL --unlock         dismiss the keyguard with the device PIN (1111)
#   tools/emu/adb.sh -s SERIAL --step NAME [needs-debuggable] [needs-root] -- COMMAND...
#                                               run COMMAND as a step: prints PASS, FAIL or SKIPPED
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
#   pv_unlock, pv_keyguard_showing, pv_focus, pv_ui_nodes, pv_ui_tap REGEX, pv_ui_wait REGEX SECONDS,
#   pv_is_debuggable [PACKAGE], pv_has_root, pv_api
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
#   serial port api owner mode(package|integrator|provision) pid booter started log tmpfiles

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
    local f=$1 pid port booter
    [ -f "$f" ] || return 1
    pid=$(pv_entry_get "$f" pid); port=$(pv_entry_get "$f" port); booter=$(pv_entry_get "$f" booter)
    pv_is_emulator_pid "$pid" "$port" && return 0
    if [ -z "$pid" ] || [ "$pid" = 0 ]; then
        [ -n "$booter" ] && kill -0 "$booter" 2>/dev/null && return 0
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

# ---- command mode -----------------------------------------------------------------------------
pv_usage() { # FILE -> prints the file's header comment and exits 2
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$1" >&2
    exit 2
}

pv_adb_usage() { pv_usage "${BASH_SOURCE[0]}"; }

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    [ "${1:-}" = "-s" ] && [ $# -ge 3 ] || pv_adb_usage
    pv_use "$2"
    shift 2
    case "$1" in
        --wait) pv_wait_device "$PV_SERIAL" "$PV_ADB_WAIT_S"; exit $? ;;
        --unlock) pv_unlock; exit $? ;;
        --step)
            [ $# -ge 4 ] || pv_adb_usage
            shift
            pv_step "$@"
            exit $? ;;
        *) pv_adb "$@"; exit $? ;;
    esac
fi
