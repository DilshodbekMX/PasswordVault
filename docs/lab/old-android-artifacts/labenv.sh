# source this file: helpers for driving the lab app on the old-Android emulators
LAB=/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/lab/old-android
ADB=/home/asus/Android/Sdk/platform-tools/adb
PKG=com.example.pvlab.old
export ANDROID_AVD_HOME=$LAB/avd

# lab_run PORT ACTION [WAIT_SECONDS] [ACTIVITY] -> runs an action and prints the PVLAB log lines it produced
lab_run() {
  local port=$1 action=$2 wait=${3:-5} act=${4:-MainActivity}
  local s="emulator-$port"
  $ADB -s $s logcat -c
  $ADB -s $s shell am start -n $PKG/.$act --es run "$action" >/dev/null
  python3 -c "import time; time.sleep($wait)"
  $ADB -s $s logcat -d -v time -s PVLAB:I AndroidRuntime:E
}

# lab_fresh PORT ACTION [WAIT] [ACTIVITY] -> same, but in a freshly started process
lab_fresh() {
  $ADB -s emulator-$1 shell am force-stop $PKG
  lab_run "$@"
}

lab_install() { # PORT APK  (adb install hung once on API 24; retry with a timeout)
  for attempt in 1 2 3; do
    if timeout 120 $ADB -s emulator-$1 install -r "$2" | tail -1 | grep -q Success; then echo Success; return 0; fi
    echo "install attempt $attempt failed or timed out" >&2
  done
  return 1
}

lab_shot() { # PORT NAME
  $ADB -s emulator-$1 exec-out screencap -p > "$LAB/shots/$2.png"; echo "$LAB/shots/$2.png"
}

lab_finger() { # PORT [FINGER_ID]
  $ADB -s emulator-$1 emu finger touch "${2:-1}" >/dev/null
  python3 -c "import time; time.sleep(0.4)"
  $ADB -s emulator-$1 emu finger remove >/dev/null 2>&1
}
