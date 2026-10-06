#!/usr/bin/env bash
# Builds the lab APK variants into ./apks. Usage: ./build-variants.sh [A] [B] [C] [D]  (default: all)
#   A = biometric 1.1.0,         androidx.core 1.19.1 (catalog), largeHeap=false
#   B = biometric 1.1.0,         androidx.core 1.19.1 (catalog), largeHeap=true
#   C = biometric 1.4.0-alpha07, androidx.core 1.19.1 (catalog), largeHeap=false
#   D = biometric 1.1.0,         androidx.core forced to 1.17.0,  largeHeap=false
#       (D does not compile: activity 1.13.0's ComponentActivity implements
#        androidx.core.app.PictureInPictureProvider, which first exists in core 1.18.0)
set -u
LAB="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/home/asus/.jdks/jdk-25}"
mkdir -p "$LAB/apks" "$LAB/logs"
cd "$LAB/project"

build() { # name, gradle props...
  local name="$1"; shift
  local log="$LAB/logs/build-$name.log"
  for attempt in 1 2; do
    ./gradlew --no-daemon assembleDebug "$@" >"$log" 2>&1 && break
    echo "build $name attempt $attempt failed (see $log)"; tail -5 "$log"
  done
  if grep -q "BUILD SUCCESSFUL" "$log"; then
    cp app/build/outputs/apk/debug/app-debug.apk "$LAB/apks/lab-$name.apk"
    echo "built $LAB/apks/lab-$name.apk"
  else
    echo "FAILED $name"; return 1
  fi
}

want="${*:-A B C D}"
for v in $want; do
  case "$v" in
    A) build A-bio110-core1191-heapfalse -PbiometricVersion=1.1.0 -PlargeHeap=false ;;
    B) build B-bio110-core1191-heaptrue  -PbiometricVersion=1.1.0 -PlargeHeap=true ;;
    C) build C-bio140a07-core1191-heapfalse -PbiometricVersion=1.4.0-alpha07 -PlargeHeap=false ;;
    D) build D-bio110-core1170-heapfalse -PbiometricVersion=1.1.0 -PlargeHeap=false -PcoreVersion=1.17.0 ;;
  esac
done
