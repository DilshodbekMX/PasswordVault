#!/usr/bin/env bash
# Biometric round trip: status, new key, encrypt (prompt + finger), decrypt (prompt + finger).
# Usage: biotest.sh PORT APK_VARIANT ACTIVITY TAG   e.g. biotest.sh 5566 C-bio140a07-core1191-heapfalse MainActivity api24-C-appcompat
source "$(dirname "$0")/labenv.sh"
port=$1 variant=$2 act=$3 tag=$4
s=emulator-$port
clean() { grep -E "PVLAB|AndroidRuntime|FATAL" | grep -vE "^-|beginning of" | sed 's/^[0-9-]* //;s/ I\/PVLAB *([ 0-9]*): / /'; }
lab_install $port $LAB/apks/lab-$variant.apk >/dev/null
$ADB -s $s shell am force-stop $PKG
echo "##### $tag ($variant, $act)"
lab_run $port bio-status 4 $act | clean | grep -E "canAuthenticate\(BIOMETRIC_STRONG\)|FingerprintManager|KeyguardManager|FATAL|AndroidRuntime"
lab_run $port bio-keygen 3 $act | clean | grep -E "bio-keygen|FATAL|AndroidRuntime"
for step in enc dec; do
  $ADB -s $s logcat -c
  $ADB -s $s shell am start -n $PKG/.$act --es run bio-$step >/dev/null 2>&1
  python3 -c "import time; time.sleep(3)"
  lab_shot $port "$tag-$step-prompt" >/dev/null
  echo "-- on screen while waiting for finger ($step):"
  python3 $LAB/ui.py $s list | grep -E "text='[^']+'" | grep -vE "id=''.*text='(extra|crypto|storage|kdf|clip|info|bio|run=|onCreate|API |Screen: |Open detail|Master password)" | sed 's/^ *//' | cut -c1-200 | head -8
  $ADB -s $s shell dumpsys window windows | grep -E "mCurrentFocus" | head -1
  lab_finger $port 1
  python3 -c "import time; time.sleep(2.5)"
  lab_shot $port "$tag-$step-after" >/dev/null
  $ADB -s $s logcat -d -v time -s PVLAB:I AndroidRuntime:E | clean
done
