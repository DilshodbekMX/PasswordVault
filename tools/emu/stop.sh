#!/usr/bin/env bash
# tools/emu/stop.sh - stop private emulators that tools/emu/boot.sh started for this worktree.
#
#   tools/emu/stop.sh SERIAL...    stop these devices; refuses (exit 3) any other serial
#   tools/emu/stop.sh --mine       stop every device boot.sh started for this worktree
#   tools/emu/stop.sh --stale      forget entries whose emulator is gone (any worktree)
#
# A device runs read-only, so stopping it throws away everything done on it. Never touches
# emulator-5554 and never runs "adb kill-server".
set -u
source "$(dirname "${BASH_SOURCE[0]}")/adb.sh"

usage() { pv_usage "$0"; }

# FILE STARTED BOOTER -> (under pv_locked) marks the entry, if it is still that one, as stopped by
# this stop.sh, so that it keeps its port while this stop.sh runs (pv_entry_held). Returns 2 when
# the entry is gone, 1 when another boot.sh has registered the port meanwhile.
pv_mark_stopping() {
    [ -f "$1" ] || return 2
    [ "$(pv_entry_get "$1" started)" = "$2" ] && [ "$(pv_entry_get "$1" booter)" = "$3" ] || return 1
    pv_entry_set "$1" stopepoch "$(date +%s)" && pv_entry_set "$1" stopper "$$"
}

stop_one() { # SERIAL -> 0 when stopped and forgotten
    local s=$1 f owner me port pid started booter
    case "$s" in emulator-[0-9]*) ;; *) pv_refuse "$s" "not a private emulator serial" ;; esac
    port=${s#emulator-}
    [ "$port" != "$PV_USER_PORT" ] || pv_refuse "$s" "port $PV_USER_PORT is the user's own emulator"
    f=$PV_REG/$s
    [ -f "$f" ] || pv_refuse "$s" "tools/emu/boot.sh did not start it (no $f)"
    owner=$(pv_entry_get "$f" owner); me=$(pv_owner)
    [ "$owner" = "$me" ] || pv_refuse "$s" "it was started for $owner, not for $me"
    [ "$(pv_entry_get "$f" mode)" != provision ] || pv_refuse "$s" "it is being provisioned; stop that boot.sh instead"
    pid=$(pv_entry_get "$f" pid)
    started=$(pv_entry_get "$f" started); booter=$(pv_entry_get "$f" booter)
    # From here until the entry is forgotten, no other boot.sh may take this port (pv_entry_held).
    pv_locked pv_mark_stopping "$f" "$started" "$booter"
    case $? in
        0) ;;
        2) pv_log "$s is not registered any more"; return 0 ;;
        99) return 1 ;;
        *) pv_log "$s was registered again meanwhile (for $(pv_entry_get "$f" owner)); not stopping it"; return 1 ;;
    esac
    if pv_entry_alive "$f"; then
        pv_log "stopping $s (API $(pv_entry_get "$f" api), pid $pid)"
        if ! pv_kill_emulator "$port" "$pid"; then
            pv_log "could not stop $s"
            return 1
        fi
    else
        pv_log "$s is not running any more"
        pv_remove_tmpfiles "$(pv_entry_get "$f" tmpfiles)"
    fi
    pv_locked pv_entry_remove_if "$f" "$started" "$booter"
    pv_log "$s stopped"
}

[ $# -ge 1 ] || usage
rc=0
case "$1" in
    --stale)
        pv_locked pv_forget_stale
        exit $? ;;
    --mine)
        me=$(pv_owner)
        for f in "$PV_REG"/emulator-*; do
            [ -f "$f" ] || continue
            [ "$(pv_entry_get "$f" owner)" = "$me" ] || continue
            [ "$(pv_entry_get "$f" mode)" != provision ] || continue
            stop_one "$(basename "$f")" || rc=1
        done ;;
    -*) usage ;;
    *)
        for s in "$@"; do stop_one "$s" || rc=1; done ;;
esac
exit $rc
