#!/usr/bin/env bash
# Simulate a device with a smaller Java heap, then run the Bouncy Castle 64 MiB probe.
# Usage: heapsim.sh PORT GROWTHLIMIT HEAPSIZE   e.g. heapsim.sh 5566 128m 256m
# Needs a userdebug image (adb root). The props are not persistent: a reboot restores the defaults.
source "$(dirname "$0")/labenv.sh"
port=$1 growth=$2 heap=$3
s=emulator-$port
$ADB -s $s root >/dev/null 2>&1; $ADB -s $s wait-for-device
$ADB -s $s shell "setprop dalvik.vm.heapgrowthlimit $growth; setprop dalvik.vm.heapsize $heap; stop; start"
python3 -c "import time; time.sleep(8)"
for i in $(seq 1 90); do
  out=$($ADB -s $s shell 'service check package; pidof com.android.systemui' 2>/dev/null)
  if echo "$out" | grep -q "found" && [ "$(echo "$out" | wc -l)" -ge 2 ]; then break; fi
  python3 -c "import time; time.sleep(2)"
done
python3 -c "import time; time.sleep(5)"
echo "### $s heapgrowthlimit=$($ADB -s $s shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r') heapsize=$($ADB -s $s shell getprop dalvik.vm.heapsize | tr -d '\r')"
for v in A-bio110-core1191-heapfalse B-bio110-core1191-heaptrue; do
  lab_install $port $LAB/apks/lab-$v.apk >/dev/null
  echo "--- $v"
  for act in bc64 bc64x2; do
    lab_fresh $port $act 12 | grep -E "bc64|FATAL|OutOfMemory" | sed 's/^[0-9-]* //;s/^.*\] //'
  done
done
