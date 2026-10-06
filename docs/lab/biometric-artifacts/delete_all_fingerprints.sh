#!/usr/bin/env bash
# delete_all_fingerprints.sh PORT [PIN]  - deletes every enrolled fingerprint through Settings (Pixel UI on API 37)
PORT="${1:?port}"; PIN="${2:-1111}"
ADB="${ADB:-/home/asus/Android/Sdk/platform-tools/adb}"; DIR="$(cd "$(dirname "$0")" && pwd)"
a() { "$ADB" -s "emulator-$PORT" "$@"; }
count() { a shell dumpsys fingerprint | python3 -c 'import sys,json
for l in sys.stdin:
    l=l.strip()
    if l.startswith("{") and "\"prints\"" in l: print(sum(p.get("count",0) for p in json.loads(l)["prints"])); break
else: print(-1)'; }
a shell am start -a android.settings.FINGERPRINT_SETTINGS >/dev/null; sleep 2.5
a shell input text "$PIN"; a shell input keyevent KEYCODE_ENTER; sleep 2.5
for i in 1 2 3 4 5 6; do
  [ "$(count)" -le 0 ] && break
  python3 "$DIR/uitap.py" "$PORT" '^Delete Finger' >/dev/null || break
  sleep 1.5
  python3 "$DIR/uitap.py" "$PORT" '^Delete$' >/dev/null; sleep 2
done
a shell input keyevent KEYCODE_BACK
echo "fingerprints left: $(count)"; [ "$(count)" -eq 0 ]
