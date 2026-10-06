#!/usr/bin/env bash
# lab.sh PORT CMD [am extras...]   send a command to the lab app and print the PVBIO log lines it produced
# env: WAIT seconds to wait before reading the log (default 1.5), PKG target package, TO target activity
PORT="${1:?port}"; shift; CMD="${1:?cmd}"; shift
ADB="${ADB:-/home/asus/Android/Sdk/platform-tools/adb}"
a() { "$ADB" -s "emulator-$PORT" "$@"; }
since=$(a shell "date +%m-%d\ %H:%M:%S" | tr -d '\r').000
a shell am broadcast -a com.example.pvlab.bio.CMD -p "${PKG:-com.example.pvlab.bio}" --es cmd "$CMD" --es to "${TO:-MainActivity}" "$@" >/dev/null
python3 -c "import time; time.sleep(${WAIT:-1.5})"
a logcat -d -v time -s PVBIO -T "$since" | grep -v '^--------- beginning'
