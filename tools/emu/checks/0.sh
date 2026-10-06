#!/usr/bin/env bash
# tools/emu/checks/0.sh - the integration check of wave 0 (build-plan.md section 2): the
# acceptance of packages 0.1 and 0.2, run again on the integrator's worktree after the merge.
#
#   tools/emu/checks/0.sh [jvm] [devices] [-- GRADLE-ARGS...]
#
#   jvm      package 0.1 [JVM]: tools/check.sh all; the source-scan audit over prototypes/ and
#            docs/lab/*-artifacts/ (-Ppv.scanAudit); no tracked build output; the release APK's
#            lib/ holds exactly arm64-v8a and armeabi-v7a and the staging APK also x86_64; R-9 on
#            the merged manifests of debug, staging, release and backupCheck.
#   devices  package 0.2 [script]: the integrator's devices API 24, 28 and 37 (boot.sh
#            --integrator: read-only, ports 5584, 5582, 5580); the debug app installs on each;
#            one more private emulator runs meanwhile; the connected tests pass on exactly the
#            three (adb.sh --connected: ANDROID_SERIAL and a passing report for every serial) and
#            nothing reached the other emulator; smoke.sh staging on API 24 and 37, after which
#            the staging app's debug receiver answers `adb shell am broadcast`; every device
#            stopped; the bases unchanged (boot.sh --verify-base); in the user's AVD directory
#            ~/.android/avd/Medium_Phone.avd nothing changed but what the user's own running
#            emulator writes, and no emulator started here names that AVD in its log.
#   (none)   both.
# Arguments after -- go to every Gradle call of the jvm part (for example --no-build-cache).
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/0/. Exit 0 when no
# step failed, 1 otherwise, 2 for a usage error. Written by the integrator of wave 0; later
# integrators may run it again (the connected tests are then whatever app/src/androidTest holds).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

parts=()
gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        jvm | devices) parts+=("$1") ;;
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=(jvm devices)

LOGS="$REPO/build/emu-checks/0"
rm -rf "$LOGS"
mkdir -p "$LOGS"
APP_ID=io.github.dilshodbekmx.passwordvault
USER_AVD="$HOME/.android/avd/Medium_Phone.avd"
echo "wave-0 integration check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')); logs in ${LOGS#"$REPO"/}"

# ---- jvm: package 0.1 ----------------------------------------------------------------------------

gradle() { "$REPO/tools/gradle.sh" "$@" ${gradle_args[@]+"${gradle_args[@]}"}; }

full_check() {
    "$REPO/tools/check.sh" all ${gradle_args[@]+-- "${gradle_args[@]}"} > "$LOGS/check-all.log" 2>&1
    local rc=$?
    sed -n '/^== Summary/,$p' "$LOGS/check-all.log"
    return $rc
}

# Both audit tests ran (not skipped by their assumption) and passed.
scan_audit() {
    gradle :core:testDebugUnitTest --tests '*SourceRulesAuditTest' \
        :app:testDebugUnitTest --tests '*SourceRulesAuditTest' -Ppv.scanAudit > "$LOGS/scan-audit.log" 2>&1 || {
        grep -E 'FAILED|ComparisonFailure' "$LOGS/scan-audit.log" | head -n 5
        return 1
    }
    python3 - "$REPO" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
repo = sys.argv[1]
bad = 0
for module, name in (("core", "core.arch.CoreSourceRulesAuditTest"), ("app", "arch.AppSourceRulesAuditTest")):
    files = glob.glob(f"{repo}/{module}/build/test-results/testDebugUnitTest/TEST-io.github.dilshodbekmx.passwordvault.{name}.xml")
    if not files:
        print(f"   {module}: no report of {name}"); bad = 1; continue
    r = ET.parse(files[0]).getroot()
    tests, failures, errors, skipped = (int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    lines = len([l for l in (r.findtext("system-out") or "").splitlines() if " | " in l])
    print(f"   {module}: {tests} test, {failures} failures, {errors} errors, {skipped} skipped; {lines} file/rule lines, as expected")
    bad |= not (tests == 1 and failures == 0 and errors == 0 and skipped == 0)
sys.exit(bad)
EOF
}

no_tracked_build_output() {
    local n
    n=$(git -C "$REPO" ls-files | grep -c -E '(^|/)build/')
    echo "   git ls-files | grep -c '/build/': $n"
    [ "$n" -eq 0 ]
}

apk_of() { # VARIANT -> the APK that AGP's output-metadata.json names
    local dir="$REPO/app/build/outputs/apk/$1" name
    name=$(sed -n 's/.*"outputFile": *"\([^"]*\)".*/\1/p' "$dir/output-metadata.json" 2>/dev/null | head -n 1)
    [ -n "$name" ] && [ -f "$dir/$name" ] && echo "$dir/$name"
}

abis_of() { # APK -> its lib/ ABIs, space-separated, sorted
    unzip -Z1 "$1" 'lib/*' 2>/dev/null | awk -F/ 'NF >= 3 { print $2 }' | sort -u | paste -sd' ' -
}

apk_abis() {
    local release staging r s
    release=$(apk_of release); staging=$(apk_of staging)
    [ -n "$release" ] && [ -n "$staging" ] || { echo "   no release or staging APK; tools/check.sh build makes them"; return 1; }
    r=$(abis_of "$release"); s=$(abis_of "$staging")
    echo "   $(basename "$release"): $r"
    echo "   $(basename "$staging"): $s"
    [ "$r" = "arm64-v8a armeabi-v7a" ] && [ "$s" = "arm64-v8a armeabi-v7a x86_64" ]
}

manifests_r9() {
    gradle :app:buildCheckDebugManifest :app:buildCheckStagingManifest \
        :app:buildCheckReleaseManifest :app:buildCheckBackupCheckManifest > "$LOGS/r9.log" 2>&1 || {
        grep -E 'R-[0-9]+|FAILED' "$LOGS/r9.log" | head -n 8
        return 1
    }
    local v
    for v in debug staging release backupCheck; do
        echo "   $v: $(grep -E '^(permissions|exported)' "$REPO/app/build/reports/build-checks/$v/manifest.txt" | tr '\n' ' ')"
    done
}

part_jvm() {
    echo
    echo "== jvm: package 0.1"
    pv_step "tools/check.sh all" -- full_check
    pv_step "source-scan audit over prototypes/ and docs/lab/*-artifacts/" -- scan_audit
    pv_step "no build output tracked by git" -- no_tracked_build_output
    pv_step "release lib/ is arm64-v8a and armeabi-v7a; staging also x86_64" -- apk_abis
    pv_step "R-9 on the merged manifests of the four variants" -- manifests_r9
}

# ---- devices: package 0.2 ------------------------------------------------------------------------

S24="" S28="" S37="" SX=""

# Lists the user's AVD directory: path, size and modification time of every entry.
avd_listing() { find "$USER_AVD" -printf '%P %s %T@\n' 2>/dev/null | sort; }

# The files of the user's AVD that its own running emulator holds open (it rewrites them itself).
user_emulator_files() {
    local p
    for p in $(pgrep -f 'qemu-system.*-avd Medium_Phone' 2>/dev/null); do
        find "/proc/$p/fd" -maxdepth 1 -type l -printf '%l\n' 2>/dev/null
    done | grep -F "$USER_AVD/" | sed "s|^$USER_AVD/||" | sort -u
}

boot_devices() {
    local api
    for api in 24 28 37; do
        "$EMU/boot.sh" --integrator "$api" > "$LOGS/serial-$api" 2> "$LOGS/boot-$api.log" &
    done
    "$EMU/boot.sh" 37 > "$LOGS/serial-other" 2> "$LOGS/boot-other.log" &
    wait
    S24=$(cat "$LOGS/serial-24"); S28=$(cat "$LOGS/serial-28"); S37=$(cat "$LOGS/serial-37"); SX=$(cat "$LOGS/serial-other")
    echo "   API 24 $S24, API 28 $S28, API 37 $S37; the other emulator $SX"
    [ "$S24" = emulator-5584 ] && [ "$S28" = emulator-5582 ] && [ "$S37" = emulator-5580 ] && [ -n "$SX" ] || return 1
    "$EMU/boot.sh" --list 2>&1 | sed 's/^/   /'
    local s pid
    for s in "$S24" "$S28" "$S37" "$SX"; do
        pid=$(pv_entry_get "$PV_REG/$s" pid)
        tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q -- ' -read-only ' \
            || { echo "   $s (pid $pid) is not running -read-only"; return 1; }
    done
}

install_debug() {
    local apk s out rc=0
    apk=$(apk_of debug)
    [ -n "$apk" ] || { echo "   no debug APK; tools/gradle.sh :app:assembleDebug makes it"; return 1; }
    for s in "$S24" "$S28" "$S37"; do
        out=$("$EMU/adb.sh" -s "$s" install -r "$apk" 2>&1 | tr -d '\r' | tail -n 1)
        echo "   $s: $out; $("$EMU/adb.sh" -s "$s" shell pm path "$APP_ID" 2>/dev/null | tr -d '\r' | head -n 1)"
        [ "$out" = Success ] || rc=1
    done
    return $rc
}

connected_tests() {
    "$EMU/adb.sh" --connected "$S24" "$S28" "$S37" > "$LOGS/connected.log" 2>&1
    local rc=$?
    grep -E 'ANDROID_SERIAL|Running tests on|Starting [0-9]+ tests|BUILD|connected tests|AndroidTestRunner failed' "$LOGS/connected.log" | sed 's/^/   /'
    return $rc
}

nothing_on_other() {
    local n
    n=$("$EMU/adb.sh" -s "$SX" shell pm list packages 2>/dev/null | tr -d '\r' | grep -c passwordvault)
    echo "   $SX: $n packages named passwordvault"
    [ "$n" -eq 0 ]
}

smoke_staging() { # SERIAL
    "$EMU/smoke.sh" staging "$1" > "$LOGS/smoke-$1.log" 2>&1
    local rc=$?
    grep -E '^(PASS|FAIL|SKIPPED|SUMMARY)' "$LOGS/smoke-$1.log" | sed 's/^/   /'
    return $rc
}

# The debug receiver of the staging app (exported behind DUMP, architecture.md §7.2) is reached by
# `adb shell am broadcast` and answers an unknown command with result 2 (DebugCommandReceiver).
debug_receiver() { # SERIAL
    local out
    out=$("$EMU/adb.sh" -s "$1" shell am broadcast -f 32 -a "$APP_ID.debug.CMD" -p "$APP_ID" --es cmd wave0-ping 2>&1 | tr -d '\r')
    echo "   $1: $(echo "$out" | grep -m 1 'Broadcast completed')"
    echo "$out" | grep -q "result=2, data=\"unknown command 'wave0-ping'"
}

stop_devices() {
    local s rc=0
    for s in "$S24" "$S28" "$S37" "$SX"; do
        [ -n "$s" ] && [ -f "$PV_REG/$s" ] || continue
        "$EMU/stop.sh" "$s" >> "$LOGS/stop.log" 2>&1 || rc=1
    done
    return $rc
}

bases_unchanged() {
    "$EMU/boot.sh" --verify-base > "$LOGS/verify-base.log" 2>&1
    local rc=$?
    sed 's/^/   /' "$LOGS/verify-base.log" | tail -n 6
    return $rc
}

user_avd_untouched() {
    avd_listing > "$LOGS/user-avd-after.txt"
    local changed allowed path bad=0
    changed=$(diff "$LOGS/user-avd-before.txt" "$LOGS/user-avd-after.txt" | sed -n 's/^[<>] \(.*\) [0-9]* [0-9.]*$/\1/p' | sort -u)
    allowed=$(user_emulator_files)
    for path in $changed; do
        case "$path" in tmpAdbCmds | tmpAdbCmds/*) continue ;; esac
        if printf '%s\n' "$allowed" | grep -qxF -- "$path"; then continue; fi
        echo "   changed, and not by the user's own emulator: $path"
        bad=1
    done
    echo "   changed: $(echo $changed | tr ' ' ','); held open by the user's emulator: $(echo $allowed | tr ' ' ',')"
    # The emulator logs of this run (boot.sh writes one per boot): each must have found its image
    # under /home/asus/pvlab and never name the user's AVD.
    local logs named
    logs=$(find "$PV_RUN/logs" -name 'emulator-*.log' -newer "$LOGS/user-avd-before.txt" 2>/dev/null)
    named=$(grep -l -E 'Medium_Phone|\.android/avd' "$LOGS"/boot-*.log $logs 2>/dev/null)
    echo "   $(echo $logs | wc -w) emulator logs of this run; naming the user's AVD: ${named:-none}"
    [ -z "$named" ] || bad=1
    return $bad
}

part_devices() {
    echo
    echo "== devices: package 0.2"
    avd_listing > "$LOGS/user-avd-before.txt"
    if [ -z "$(apk_of debug)" ] || [ -z "$(apk_of staging)" ]; then
        pv_step "build the debug and staging APKs" -- gradle :app:assembleDebug :app:assembleStaging
    fi
    if pv_step "boot.sh starts API 24, 28 and 37 read-only on the integrator's ports, and another emulator" -- boot_devices; then
        pv_step "the debug app installs on API 24, 28 and 37" -- install_debug
        pv_step "connected tests pass on exactly API 24, 28 and 37 (ANDROID_SERIAL)" -- connected_tests
        pv_step "nothing was installed on the other emulator" -- nothing_on_other
        pv_step "smoke.sh staging on API 24" -- smoke_staging "$S24"
        pv_step "smoke.sh staging on API 37" -- smoke_staging "$S37"
        pv_step "the staging app's debug receiver answers adb on API 24" -- debug_receiver "$S24"
        pv_step "the staging app's debug receiver answers adb on API 37" -- debug_receiver "$S37"
    fi
    pv_step "the devices are stopped" -- stop_devices
    pv_step "the bases are unchanged" -- bases_unchanged
    pv_step "the user's AVD was not touched" -- user_avd_untouched
}

trap 'stop_devices >/dev/null 2>&1' EXIT
for part in "${parts[@]}"; do "part_$part"; done
echo
PV_SERIAL="" pv_summary
