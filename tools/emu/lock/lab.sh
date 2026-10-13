#!/usr/bin/env bash
# tools/emu/lock/lab.sh - one debug command of the lock (lock.md §15.3, build plan 4.1), and the log
# lines it caused.
#
#   tools/emu/lock/lab.sh SERIAL CMD [WORDS...]       e.g. lab.sh emulator-5556 ks-fault getkey-null
#
# Adapted from docs/lab/biometric-artifacts/lab.sh (which took a console port, sent the lab app's
# broadcast and printed its PVBIO lines): the broadcast is the app's
# (io.github.dilshodbekmx.passwordvault.debug.CMD to the package, `--es cmd 'CMD WORDS'`), every adb
# call goes through tools/emu/adb.sh, and the lines printed are the lock's (tag PVLOCK) and the
# receiver's (PVDEBUG) since the command was sent. The answer itself (the broadcast's data) comes
# first. Commands: status, hold-after-charge, hold-before-hmac, release, fail-next-write,
# ks-fault [ROW|none], drop-keys (debug, staging and backupCheck builds; release has no receiver).
# Environment: WAIT seconds to wait before the log is read (default 1.5).
# Exit 0 when the receiver answered "done" (result 1), 1 otherwise, 2 for a usage error, 3 for a
# refused serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

[ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"
pv_use "$1"
shift
since=$(pv_shell date '+%m-%d %H:%M:%S.000' 2>/dev/null | tr -d '\r')
answer=$(pva_debug "$@")
rc=$?
echo "answer: $answer"
pv_sleep "${WAIT:-1.5}"
pv_shell logcat -d -v time -T "'$since'" -s PVLOCK:V PVDEBUG:V 2>/dev/null | tr -d '\r' | grep -v '^--------- beginning'
exit $rc
