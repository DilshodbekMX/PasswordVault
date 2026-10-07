# tools/emu/clipboard/lib.sh - the clipboard of a private emulator as another app sees and uses it,
# for tools/emu/clipboard/clear.sh and any other script of tools/emu/ that copies in the app (the
# integration checks, smoke.sh). Source it after adb.sh, then name the device:
#
#   source tools/emu/adb.sh; source tools/emu/clipboard/lib.sh
#   pv_use "$SERIAL"
#
# It runs PvClip.java (this directory) on the device as the shell user: see that file for why the
# shell may read the clipboard on every Android version and what each command prints. The jar is
# built once per worktree into build/emu/pv-clip.jar (the JDK's javac, the SDK's d8) and pushed to
# /data/local/tmp/ on a device when needed, as adb.sh does for its node dumper.
#
#   pvc_describe            the clip's description as key=value lines (now, elapsed, has, label, mime,
#                           sensitive, token, extras); reads no content, shows no system notice
#   pvc_get                 the same and items, text; on Android 12+ the system then shows "Shell
#                           pasted from your clipboard" once per clip
#   pvc_field KEY           the value of KEY in such lines on stdin; nothing when the key is not there
#   pvc_set TEXT            copies TEXT as another app would (letters, digits and . _ - only)
#   pvc_clear               empties the clipboard
#   pvc_toast TEXT          shows a toast of the shell's for about 4 s (the control of a toast watch)
#   pvc_toasts              the toasts shown or waiting now, one line each (dumpsys notification's
#                           "Toast Queue", which names each toast's package, on API 24 to 37)
#   pvc_watch_toasts S      every toast seen during S seconds, each once
#   pvc_alarm               the app's clear alarm as dumpsys alarm lists it among the alarms that
#                           are set (its tag line and the lines after it); nothing when none is set
# Every function returns 0 on success; pvc_describe, pvc_get, pvc_set, pvc_clear and pvc_toast fail
# when the tool did not end with "PVCLIP OK".

PVC_SRC=$PV_EMU_DIR/clipboard/PvClip.java
PVC_JAR=${PVC_JAR:-$PV_EMU_DIR/../../build/emu/pv-clip.jar}
PVC_ON_DEVICE=/data/local/tmp/pv-clip.jar
PVC_CLEAR_ACTION=${PV_PACKAGE}.action.CLEAR_CLIPBOARD

# Builds the jar when it is missing or older than its source; into a directory of its own first, so
# that scripts running at once do not collide.
pvc_build() {
    [ -f "$PVC_JAR" ] && [ ! "$PVC_SRC" -nt "$PVC_JAR" ] && return 0
    local java_home=${PV_JAVA_HOME:-/home/asus/.jdks/jdk-25} d8 android_jar tmp
    d8=$(ls -d "$PV_SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -n 1)
    android_jar=$(ls -d "$PV_SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)
    [ -x "$java_home/bin/javac" ] && [ -n "$d8" ] && [ -n "$android_jar" ] || { pv_log "no javac, d8 or android.jar to build ${PVC_JAR##*/}"; return 1; }
    mkdir -p "$(dirname "$PVC_JAR")" || return 1
    tmp=$(mktemp -d "$(dirname "$PVC_JAR")/clip.XXXXXX") || return 1
    if "$java_home/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 -cp "$android_jar" -d "$tmp" "$PVC_SRC" > "$tmp/javac.log" 2>&1 \
        && JAVA_HOME=$java_home PATH=$java_home/bin:$PATH "$d8" --min-api 24 --output "$tmp/pv-clip.jar" "$tmp"/PvClip*.class > "$tmp/d8.log" 2>&1; then
        mv -f "$tmp/pv-clip.jar" "$PVC_JAR"
        rm -rf "$tmp"
        return 0
    fi
    pv_log "could not build ${PVC_JAR##*/}: $(cat "$tmp/javac.log" "$tmp/d8.log" 2>/dev/null | grep -v '^Note:' | tail -n 3)"
    rm -rf "$tmp"
    return 1
}

# One run of the tool on the device: its lines without the last, and 0 when the last was "PVCLIP OK".
pvc_once() { # COMMAND [TEXT]
    local out arg=""
    [ $# -lt 2 ] || arg=" '$2'"
    out=$(pv_adb shell "CLASSPATH=$PVC_ON_DEVICE app_process /system/bin PvClip $1$arg" 2>/dev/null | tr -d '\r')
    PVC_LAST=$(printf '%s\n' "$out" | tail -n 1)
    [ "$PVC_LAST" = "PVCLIP OK" ] || return 1
    printf '%s\n' "$out" | sed '$d'
}

# The tool, built when its source changed and pushed when the device has not got this build of it (a
# marker per serial under $PV_RUN/clip/; a device restarted since has no jar, and the failed run
# pushes it again).
pvc_run() { # COMMAND [TEXT]
    local marker=$PV_RUN/clip/$PV_SERIAL
    case "${2:-}" in *[!A-Za-z0-9._\ -]*) pv_log "pvc: '$2' holds more than letters, digits, spaces and . _ -"; return 2 ;; esac
    if [ -f "$PVC_JAR" ] && [ ! "$PVC_SRC" -nt "$PVC_JAR" ] && [ -f "$marker" ] && [ ! "$PVC_JAR" -nt "$marker" ]; then
        pvc_once "$@" && return 0
        case "$PVC_LAST" in "PVCLIP ERROR"*) pv_log "pvc $1: $PVC_LAST"; return 1 ;; esac
    fi
    pvc_build || return 1
    pv_adb push "$PVC_JAR" "$PVC_ON_DEVICE" > /dev/null 2>&1 || { pv_log "could not push ${PVC_JAR##*/} to $PV_SERIAL"; return 1; }
    mkdir -p "$PV_RUN/clip" && touch "$marker"
    pvc_once "$@" || { pv_log "pvc $1: ${PVC_LAST:-no answer}"; return 1; }
}

pvc_describe() { pvc_run describe; }
pvc_get() { pvc_run get; }
pvc_set() { pvc_run set "$1" > /dev/null; }
pvc_clear() { pvc_run clear > /dev/null; }
pvc_toast() { pvc_run toast "$1" > /dev/null; }

pvc_field() { # KEY < LINES
    sed -n "s/^$1=//p" | head -n 1
}

# The toasts the notification service holds now, shown or waiting: one line each, with the package
# ("pkg=NAME" on Android 7 to 10, "PID:NAME/UID" later).
pvc_toasts() {
    pv_adb shell dumpsys notification 2>/dev/null | tr -d '\r' \
        | awk '/^ *Toast Queue:/ { on = 1; next } on && /ToastRecord/ { sub(/^ */, ""); print; next } on { exit }'
}

# Every toast seen during SECONDS, each once, without the parts that differ between readings.
pvc_watch_toasts() { # SECONDS
    local deadline=$((SECONDS + $1)) seen="" now line
    while :; do
        now=$(pvc_toasts | sed -E 's/ToastRecord\{[0-9a-f]+ /ToastRecord{/; s/ token=[^ ]*//; s/ callback=[^ ]*//')
        while IFS= read -r line; do
            [ -n "$line" ] || continue
            case "$seen" in *"$line"*) ;; *) seen="$seen$line"$'\n' ;; esac
        done <<< "$now"
        [ $SECONDS -lt $deadline ] || break
        pv_sleep 0.2
    done
    printf '%s' "$seen"
}

# An alarm that is set has a line of its own that reads "tag=*walarm*:ACTION". The statistics name
# the action without "tag=", and the history of removed alarms (Android 12 and later) has the tag in
# the middle of a line, so only a whole line counts.
pvc_alarm() {
    pv_adb shell dumpsys alarm 2>/dev/null | tr -d '\r' | awk -v tag="tag=*walarm*:$PVC_CLEAR_ACTION" '
        { line = $0; sub(/^ +/, "", line); sub(/ +$/, "", line) }
        line == tag { left = 5 }
        left > 0 { print; left-- }'
}
