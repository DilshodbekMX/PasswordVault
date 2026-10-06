#!/usr/bin/env bash
# tools/gradle.sh [gradle arguments...]
#
# The only way to call Gradle in this project (build plan §1, rule 7). It
#   - sets JAVA_HOME and ANDROID_HOME, because a new worktree has no local.properties;
#   - runs the wrapper from the project root, whatever the current directory (so a path given to
#     -p is relative to the project root);
#   - takes one of two machine-wide build slots, and waits while both are taken;
#   - runs the build once more if it died with "daemon disappeared" or left an hs_err_pid file,
#     which happens on this machine under heavy load.
# The exit status is Gradle's.
#
# Environment (all optional):
#   PV_JAVA_HOME          the JDK (default /home/asus/.jdks/jdk-25)
#   PV_ANDROID_HOME       the Android SDK (default /home/asus/Android/Sdk)
#   PV_GRADLE_SLOTS       the directory of the slot lock files (default ~/.cache/passwordvault/gradle-slots)
#   PV_GRADLE_SLOT_COUNT  the number of slots (default 2)
set -u

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export JAVA_HOME="${PV_JAVA_HOME:-/home/asus/.jdks/jdk-25}"
export ANDROID_HOME="${PV_ANDROID_HOME:-/home/asus/Android/Sdk}"
unset ANDROID_SDK_ROOT # AGP refuses two different SDK locations
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "gradle.sh: no JDK at $JAVA_HOME (set PV_JAVA_HOME)" >&2
    exit 2
fi
if [[ ! -d "$ANDROID_HOME/platforms" ]]; then
    echo "gradle.sh: no Android SDK at $ANDROID_HOME (set PV_ANDROID_HOME)" >&2
    exit 2
fi

slots="${PV_GRADLE_SLOTS:-$HOME/.cache/passwordvault/gradle-slots}"
count="${PV_GRADLE_SLOT_COUNT:-2}"
mkdir -p "$slots"

# Take a slot: an exclusive flock on slot-<n>.lock, held by this shell until it exits. The lock is
# released by the kernel when this process ends, however it ends, so a slot is never left taken.
slot=""
lockfd=""
waited=0
while [[ -z "$slot" ]]; do
    for ((n = 1; n <= count; n++)); do
        exec {fd}>>"$slots/slot-$n.lock"
        if flock -n "$fd"; then
            slot=$n
            lockfd=$fd
            break
        fi
        exec {fd}>&-
    done
    if [[ -z "$slot" ]]; then
        if ((waited % 60 == 0)); then
            echo "gradle.sh: all $count build slots are taken; waiting. Holders:" >&2
            cat "$slots"/slot-*.owner 2>/dev/null | sed 's/^/    /' >&2
        fi
        sleep 5
        waited=$((waited + 5))
    fi
done
echo "pid $$ since $(date '+%F %T') in $root: gradlew $*" >"$slots/slot-$slot.owner"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/pv-gradle.XXXXXX")"
trap 'rm -rf "$tmp"; rm -f "$slots/slot-$slot.owner"' EXIT

# Places where a crashed JVM of this build leaves hs_err_pid<n>.log: the project and its modules
# (test workers), and the working directories of the Gradle and Kotlin daemons.
crash_logs_since() {
    find "$root" -maxdepth 2 -name 'hs_err_pid*.log' -newer "$1" 2>/dev/null
    find "$HOME/.gradle/daemon" "$HOME/.local/share/kotlin/daemon" "$HOME/.kotlin/daemon" \
        -maxdepth 2 -name 'hs_err_pid*.log' -newer "$1" 2>/dev/null
}

run_once() { # $1: log file; the rest: gradle arguments
    local log="$1"
    shift
    # The children do not get the slot's descriptor, so a daemon started by this build cannot hold
    # the slot after the build ends.
    (cd "$root" && exec ./gradlew "$@") {lockfd}>&- 2>&1 | tee "$log" {lockfd}>&-
    return "${PIPESTATUS[0]}"
}

touch "$tmp/start"
run_once "$tmp/first.log" "$@"
status=$?
if ((status != 0)); then
    reason=""
    if grep -qi 'daemon disappeared' "$tmp/first.log"; then
        reason="the Gradle daemon disappeared"
    elif [[ -n "$(crash_logs_since "$tmp/start")" ]]; then
        reason="a JVM crashed ($(crash_logs_since "$tmp/start" | head -n 3 | tr '\n' ' '))"
    fi
    if [[ -n "$reason" ]]; then
        echo "gradle.sh: $reason; running the build once more" >&2
        run_once "$tmp/second.log" "$@"
        status=$?
    fi
fi
exit "$status"
