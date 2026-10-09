#!/usr/bin/env bash
# tools/emu/lock/heapsim.sh - a phone with a smaller Java heap, for lock.md check E16: the framework
# restarted with dalvik.vm.heapgrowthlimit and dalvik.vm.heapsize set.
#
#   tools/emu/lock/heapsim.sh SERIAL GROWTHLIMIT HEAPSIZE     e.g. heapsim.sh emulator-5560 128m 256m
#   tools/emu/lock/heapsim.sh SERIAL --restore                the values the image had (saved at the
#                                                             first change in build/emu-lock/SERIAL/)
#
# Adapted from docs/lab/old-android-artifacts/heapsim.sh (which named a console port and the lab's
# devices under /tmp): the device is one of this worktree's tools/emu/boot.sh devices, every adb call
# goes through tools/emu/adb.sh, and the lab's probe of Bouncy Castle afterwards is left to E16. Needs
# a userdebug image (su: API 24 and 28 here). The properties are not persistent: a reboot, or stopping
# the read-only device, restores the image's values too. A largeHeap app (PasswordVault is one) gets
# HEAPSIZE, any other app GROWTHLIMIT.
# Exit 0 when the framework is back with the new values, 1 otherwise, 2 for a usage error, 3 for a
# refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

[ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"
pv_use "$1"
SAVED=$REPO/build/emu-lock/$1/heapsim-original.txt

pvh_get() { pv_getprop "$1"; }

# pvh_set GROWTH HEAP: sets both and restarts the framework (stop; start), then waits for it.
pvh_set() {
    pv_has_root || { pv_log "the image of $PV_SERIAL has no root (needs a userdebug image)"; return 1; }
    mkdir -p "$(dirname "$SAVED")"
    [ -f "$SAVED" ] || printf '%s %s\n' "$(pvh_get dalvik.vm.heapgrowthlimit)" "$(pvh_get dalvik.vm.heapsize)" > "$SAVED"
    pv_shell "su 0 sh -c 'setprop dalvik.vm.heapgrowthlimit $1; setprop dalvik.vm.heapsize $2; stop; start'" >/dev/null 2>&1
    pv_sleep 8
    pv_wait_booted 300 || return 1
    [ "$(pvh_get dalvik.vm.heapgrowthlimit)" = "$1" ] && [ "$(pvh_get dalvik.vm.heapsize)" = "$2" ] \
        || { pv_log "the properties are $(pvh_get dalvik.vm.heapgrowthlimit) and $(pvh_get dalvik.vm.heapsize)"; return 1; }
    echo "### $PV_SERIAL heapgrowthlimit=$(pvh_get dalvik.vm.heapgrowthlimit) heapsize=$(pvh_get dalvik.vm.heapsize)"
}

if [ "$2" = --restore ]; then
    [ -f "$SAVED" ] || { echo "heapsim.sh: nothing saved for $1; the image's values are in force" >&2; exit 0; }
    read -r growth heap < "$SAVED"
    pvh_set "$growth" "$heap" || exit 1
    rm -f "$SAVED"
    exit 0
fi
[ $# -eq 3 ] || pv_usage "${BASH_SOURCE[0]}"
pvh_set "$2" "$3"
