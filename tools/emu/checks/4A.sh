#!/usr/bin/env bash
# tools/emu/checks/4A.sh - the device check of sub-wave 4A, round R3 (build-plan.md section 12, "The
# integration in two steps", step 2; the "Integration check for 4A" of section 6). The merge ran
# tools/check.sh all before it tagged wave-4A-merged. Round R3 brought the real lock to the devices:
# DefaultLockManager (4.1) over the lock engine (4.4), the lock screens (4.2), the PIN steps, Settings
# and the looks (4.3, 4.3b), and the screens of backup, restore, old passwords, export and import (5.2
# to 5.4).
#
#   tools/emu/checks/4A.sh [PART...] [--shots DIR] [--keep] [-- GRADLE-ARGS...]
#
# The parts run in this order, all of them when none is named, on the integrator's devices (5584 is
# API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used as
# it is, the others are booted together (boot.sh --integrator). The debug and staging APKs are built
# first (tools/gradle.sh). The three devices are stopped at the end unless --keep is given; the part
# "stop" does nothing else.
#   merged     the heads that round R3 merged are in HEAD: w4A-4.1 to w4A-4.4, w5-5.2 to w5-5.4 and the
#              two review branches w5-rev-3.15 and w5-rev-5.1.
#   connected  adb.sh --connected on the three devices: every connected test, and every serial has a
#              passing report (BUILD SUCCESSFUL alone does not count). A class that failed is run once
#              more, alone, on that device; it counts as passed when it passes alone, and the step names
#              it as a flake. Then every device-test class ran on each device with no failure and at
#              least the tests it has there (@SdkSuppress leaves some out): 469 on API 24, 470 on 28,
#              477 on 37 at wave-4A-merged.
# Each part prints PASS, FAIL or SKIPPED per step. After a part that drives the app, a NOTE names every
# Run that Android Studio started on one of the three devices meanwhile (its idea.log;
# tools/emu/README.md, "Device facts"): such a Run stops, reinstalls and starts the app under whatever
# step is running.
#
# Prints a summary; logs in build/emu-checks/4A/. Exit 0 when no step failed, 1 otherwise, 2 for a usage
# error, 3 for a refused serial. Written by the device check of round R3 (build-plan.md section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SHOTS=/home/asus/pvwork/shots-look
LOGS_ROOT=$REPO/build/emu-checks/4A
DEBUG_APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
STAGING_APK=$REPO/app/build/outputs/apk/staging/app-staging.apk
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug
RUNNER_ARG=-Pandroid.testInstrumentationRunnerArguments
PACKAGE=io.github.dilshodbekmx.passwordvault

note() { echo "     $*"; }

# ---- merged ------------------------------------------------------------------------------------------
# BRANCH:HEAD as round R3 merged them (the merge commits 58dae3e, 23e5cd7, ce90927, 3320aad, 09d1db2,
# db89841 and c897807 name them; the two review branches were merged by the job "main: reviews in").
MERGED=(w4A-4.1:0aeed9b w4A-4.2:a54c421 w4A-4.3:7893849 w4A-4.4:fad2e5c w5-5.2:b950fec w5-5.3:cfca2bc
    w5-5.4:3f40885 w5-rev-3.15:c38c93a w5-rev-5.1:e002a41)

heads_merged() {
    local pair branch head bad=0
    for pair in "${MERGED[@]}"; do
        branch=${pair%%:*}
        head=${pair##*:}
        if ! git -C "$REPO" cat-file -e "$head^{commit}" 2> /dev/null; then
            echo "   $branch: no commit $head"
            bad=1
        elif git -C "$REPO" merge-base --is-ancestor "$head" HEAD; then
            echo "   $branch: $head is in HEAD"
        else
            echo "   $branch: $head is not merged"
            bad=1
        fi
    done
    return $bad
}

# ---- the devices ---------------------------------------------------------------------------------------
APIS=(24 28 37)
declare -A SER=()

build_apks() {
    "$REPO/tools/gradle.sh" :app:assembleDebug :app:assembleStaging ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS_ROOT/assemble.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS_ROOT/assemble.log" | sed 's/^/   /'
    return $rc
}

# The integrator's devices: one of this worktree that runs on its port is used, the others booted.
devices() {
    local api port entry rc=0 status
    declare -A pids=()
    for api in "${APIS[@]}"; do
        port=$(pv_integrator_port "$api")
        entry=$PV_REG/emulator-$port
        if [ -f "$entry" ] && [ "$(pv_entry_get "$entry" owner)" = "$(pv_owner)" ] && pv_entry_alive "$entry" \
            && PV_SERIAL=emulator-$port pv_wait_device "emulator-$port" 30; then
            SER[$api]=emulator-$port
            echo "   API $api: emulator-$port, running already"
            continue
        fi
        "$EMU/boot.sh" --integrator "$api" > "$LOGS_ROOT/serial-$api" 2> "$LOGS_ROOT/boot-$api.log" &
        pids[$api]=$!
    done
    for api in "${!pids[@]}"; do
        wait "${pids[$api]}"
        status=$?
        SER[$api]=$(cat "$LOGS_ROOT/serial-$api" 2> /dev/null)
        echo "   API $api: ${SER[$api]:-no device} (boot.sh exit $status)"
        [ $status -eq 0 ] && [ "${SER[$api]}" = "emulator-$(pv_integrator_port "$api")" ] || rc=1
    done
    for api in "${APIS[@]}"; do [ -n "${SER[$api]:-}" ] || rc=1; done
    return $rc
}

stop_devices() {
    local api rc=0
    for api in "${APIS[@]}"; do
        [ -n "${SER[$api]:-}" ] && [ -f "$PV_REG/${SER[$api]}" ] || continue
        "$EMU/stop.sh" "${SER[$api]}" >> "$LOGS_ROOT/stop.log" 2>&1 || rc=1
    done
    for api in "${APIS[@]}"; do
        if [ -f "$PV_REG/emulator-$(pv_integrator_port "$api")" ]; then
            echo "   emulator-$(pv_integrator_port "$api") is still registered"
            rc=1
        fi
    done
    return $rc
}

# studio_runs SINCE: a NOTE for each Run that Android Studio started on one of the three devices since
# SINCE (date +%s), as its log has them ("... AndroidRunConfigurationExecutor - Launching on device
# pvlab_api37 [emulator-5580]"). Nothing when Studio keeps no log here.
studio_runs() {
    local log
    for log in "$HOME"/.cache/Google/AndroidStudio*/log/idea.log; do
        [ -f "$log" ] || continue
        python3 - "$log" "$1" "${SER[@]}" <<'EOF'
import re, sys, time
log, since, serials = sys.argv[1], int(sys.argv[2]), set(sys.argv[3:])
line_re = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),\d+ .*AndroidRunConfigurationExecutor - Launching on device .*\[(emulator-\d+)\]")
with open(log, encoding="utf-8", errors="replace") as f:
    for line in f:
        m = line_re.match(line)
        if not m or m.group(2) not in serials:
            continue
        if time.mktime(time.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")) >= since:
            print(f"NOTE Android Studio's Run landed on {m.group(2)} at {m.group(1)[11:]}: it stopped, reinstalled and started the app there")
EOF
    done
}

# in_parallel NAME COMMAND-WORDS... -- API...: COMMAND SERIAL on each device at once, its output in
# LOGS/NAME-API.log; prints each device's step lines, and the Runs Android Studio made meanwhile.
in_parallel() {
    local name=$1 api rc=0 status cmd=() began
    shift
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do cmd+=("$1"); shift; done
    shift
    began=$(date +%s)
    declare -A pids=()
    for api in "$@"; do
        "${cmd[@]}" "${SER[$api]}" > "$LOGS/$name-$api.log" 2>&1 &
        pids[$api]=$!
    done
    for api in "$@"; do
        wait "${pids[$api]}"
        status=$?
        echo "   $name on API $api (${SER[$api]}): exit $status"
        grep -E '^(PASS|FAIL|SKIPPED|SUMMARY|NOTE|   )' "$LOGS/$name-$api.log" | sed 's/^/     /'
        [ $status -eq 0 ] || rc=1
    done
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# The test APK exports a documents provider, a picker and a receiver while it is installed; AGP removes
# it after a run, and a run that was cut off may not have.
no_test_apk() {
    local api s rc=0
    for api in "${APIS[@]}"; do
        s=${SER[$api]}
        if PV_SERIAL=$s pv_shell pm path "$PV_PACKAGE.test" 2> /dev/null | grep -q '^package:'; then
            echo "   API $api ($s): the test APK was still installed; removed"
            PV_SERIAL=$s pv_adb uninstall "$PV_PACKAGE.test" > /dev/null 2>&1
            ! PV_SERIAL=$s pv_shell pm path "$PV_PACKAGE.test" 2> /dev/null | grep -q '^package:' || rc=1
        else
            echo "   API $api ($s): no test APK"
        fi
    done
    return $rc
}

# ---- connected -------------------------------------------------------------------------------------------
# failed_classes DIR: "SERIAL CLASS" for each class with a failure or error in DIR's JUnit reports.
failed_classes() {
    python3 - "$1" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
out = set()
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    for suite in ET.parse(f).getroot().iter("testsuite"):
        devs = [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                for dev in devs:
                    out.add((dev, case.get("classname", "")))
                    print(f"   failed on {dev}: {case.get('classname', '')}.{case.get('name')}", file=sys.stderr)
for dev, cls in sorted(out):
    print(dev, cls)
EOF
}

connected_tests() {
    local dir=$LOGS_ROOT/connected rc s cls again=0
    rm -rf "$dir"
    mkdir -p "$dir/full"
    "$EMU/adb.sh" --connected "${SER[24]}" "${SER[28]}" "${SER[37]}" ${gradle_args[@]+-- "${gradle_args[@]}"} > "$dir/full.log" 2>&1
    rc=$?
    cp -r "$RESULTS/." "$dir/full/" 2> /dev/null
    grep -E '^(PASS|FAIL) connected|^BUILD' "$dir/full.log" | sed 's/^/   /'
    [ $rc -ne 0 ] || return 0
    failed_classes "$dir/full" > "$dir/failed" 2> "$dir/failed-tests"
    cat "$dir/failed-tests"
    # A device without a failing test failed otherwise (no report: an install, a lost device).
    for s in "${SER[24]}" "${SER[28]}" "${SER[37]}"; do
        if grep -q "^FAIL connected tests on $s " "$dir/full.log" && ! grep -q "^$s " "$dir/failed"; then
            echo "   $s: no failing test to run again; the run itself failed there"
            return 1
        fi
    done
    [ -s "$dir/failed" ] || return 1
    # One run per class: with class=A,B the runner ran A only (seen on API 28 on 2026-10-08).
    while read -r s cls; do
        echo "   $s: $cls failed in the full run; run again alone there"
        mkdir -p "$dir/again/$s/$cls"
        "$EMU/adb.sh" --connected "$s" -- "$RUNNER_ARG.class=$cls" ${gradle_args[@]+"${gradle_args[@]}"} > "$dir/again/$s/$cls.log" 2>&1 < /dev/null || again=1
        cp -r "$RESULTS/." "$dir/again/$s/$cls/" 2> /dev/null
        grep -E '^(PASS|FAIL) connected' "$dir/again/$s/$cls.log" | sed 's/^/     alone: /'
    done < "$dir/failed"
    [ $again -eq 0 ] || return 1
    echo "   FLAKY: each class that failed in the full run passed alone: $(awk '{ printf "%s on %s; ", $2, $1 }' "$dir/failed")"
}

# Each device-test class with its package and the tests it has on API 24, 28 and 37 at wave-4A-merged
# (counted from the sources with their @SdkSuppress filters; the same count gives 358, 359 and 363 at
# wave-3C-ok, what the device check of 3C ran); a class run again alone (connected_tests) counts with
# that run's result.
device_classes() {
    python3 - "$LOGS_ROOT/connected" "${SER[24]}" "${SER[28]}" "${SER[37]}" <<'EOF'
import collections, glob, os, sys, xml.etree.ElementTree as ET
d, s24, s28, s37 = sys.argv[1:5]
prefix = "io.github.dilshodbekmx.passwordvault."
expected = {
    "nav.NavLockTest": ("2.5", 1, 1, 1),
    "nav.NavRecreationTest": ("2.5", 1, 1, 1),
    "platform.AndroidClockTest": ("2.7", 5, 5, 5),
    "platform.MemoryLimiterTest": ("2.7", 0, 0, 1),
    "platform.NativeArgon2idTest": ("2.7", 10, 10, 11),
    "platform.OsFilesTest": ("2.7", 19, 19, 19),
    "platform.OsRecordFilesTest": ("2.7", 11, 11, 11),
    "platform.PlatformModuleTest": ("2.7", 7, 7, 7),
    "platform.SystemRandomTest": ("2.7", 3, 3, 3),
    "platform.VaultRoundTripDeviceTest": ("2.7", 1, 1, 1),
    "vectors.LockVectorsDeviceTest": ("2.7", 3, 3, 3),
    "vectors.MasterPasswordBytesDeviceTest": ("2.7", 5, 5, 5),
    "vectors.VaultVectorsDeviceTest": ("2.7", 9, 9, 9),
    "window.ObscuredTouchNoticeTest": ("2.8", 2, 2, 2),
    "window.SecureWindowTest": ("2.8", 2, 2, 2),
    "window.WindowPolicyTest": ("2.8", 2, 2, 2),
    "ui.common.CommonStringsPseudoLocaleTest": ("2.8", 2, 2, 2),
    "ui.common.DialogSheetPolicyTest": ("2.8", 4, 4, 4),
    "ui.common.FieldPolicyTest": ("2.8", 4, 4, 4),
    "ui.common.MaskedValueNodesTest": ("2.8", 2, 2, 2),
    "ui.common.PinPadNodesTest": ("2.8", 5, 5, 5),
    "ui.common.SecureFieldSavedStateTest": ("2.8", 1, 1, 1),
    "i18n.FormattersTest": ("2.9", 6, 6, 6),
    "i18n.LocaleCommandTest": ("2.9", 2, 2, 2),
    "i18n.SortKeysTest": ("2.9", 8, 8, 8),
    "ui.setup.TypeAgainRotationTest": ("3.3", 1, 1, 1),
    "ui.lock.MasterPasswordRotationTest": ("3.4", 1, 1, 1),
    "ui.lock.MasterPasswordScreenTest": ("3.4", 6, 6, 6),
    "ui.list.ListPerformanceTest": ("3.5", 1, 1, 2),
    "ui.list.ListPseudoLocaleTest": ("3.5", 5, 5, 5),
    "ui.list.PasswordsScreenTest": ("3.5", 10, 10, 10),
    "ui.edit.EditKeyboardRotationTest": ("3.6", 3, 4, 4),
    "ui.edit.EditScreenTest": ("3.6", 4, 4, 4),
    "ui.entry.BigLettersScreenTest": ("3.7", 10, 10, 10),
    "ui.entry.EntryPseudoLocaleTest": ("3.7", 6, 6, 6),
    "ui.entry.EntrySavedStateTest": ("3.7", 2, 2, 2),
    "ui.entry.EntryScreenTest": ("3.7", 16, 16, 16),
    "clipboard.AndroidSecretClipboardTest": ("3.8", 34, 34, 34),
    "clipboard.AndroidSystemClipTest": ("3.8", 6, 6, 7),
    "clipboard.ClearAlarmDeviceTest": ("3.8", 4, 4, 4),
    "clipboard.ClipStatusRowTest": ("3.8", 11, 11, 11),
    "clipboard.ClipboardPseudoLocaleTest": ("3.8", 2, 2, 2),
    "clipboard.SecretClipboardBridgeTest": ("3.8", 8, 8, 8),
    "keystore.FakeKeysParityTest": ("3.9", 1, 1, 1),
    "keystore.KeystoreAccessTest": ("3.9", 12, 12, 12),
    "keystore.KeystoreErrorRowsTest": ("3.9", 11, 11, 11),
    "keystore.KeystoreFailureAdapterTest": ("3.9", 5, 5, 5),
    "keystore.KeystoreFingerprintKeyTest": ("3.9", 17, 17, 17),
    "keystore.KeystoreModuleTest": ("3.9", 5, 5, 5),
    "keystore.KeystorePinKeyTest": ("3.9", 8, 8, 8),
    "ui.list.ListFocusTest": ("w3B-carry", 2, 2, 2),
    "data.StoredTextsTest": ("w3B-carry", 1, 1, 1),
    "files.BackupSharingTest": ("5.1", 4, 4, 4),
    "files.DocumentNamesTest": ("5.1", 3, 3, 3),
    "files.FileTaskStoreTest": ("5.1", 21, 21, 21),
    "files.PickerContractsTest": ("5.1", 4, 4, 4),
    "files.PickerProcessTest": ("5.1", 9, 9, 9),
    "files.PlacesTest": ("5.1", 6, 6, 6),
    "files.SafDocumentIoTest": ("5.1", 18, 18, 18),
    "files.ShownNameTest": ("5.1", 6, 6, 6),
    "ui.lock.ForgotSheetScreenTest": ("4.2", 4, 4, 4),
    "ui.lock.LockPseudoLocaleTest": ("4.2", 5, 5, 5),
    "ui.lock.LockScreensTest": ("4.2", 8, 8, 8),
    "ui.lock.NoticesScreenTest": ("4.2", 4, 4, 4),
    "ui.lock.PadScreenTest": ("4.2", 9, 9, 9),
    "ui.settings.PinStepsScreenTest": ("4.3", 8, 8, 8),
    "ui.settings.SettingsPseudoLocaleTest": ("4.3", 2, 2, 2),
    "ui.settings.SettingsScreenTest": ("4.3", 6, 6, 6),
    "ui.settings.SettingsScreenshotsTest": ("4.3", 0, 0, 1),
    "ui.theme.AppearanceDialogTest": ("4.3b", 4, 4, 4),
    "ui.theme.LauncherIconTest": ("4.3b", 3, 3, 4),
    "ui.theme.LockScreenLookTest": ("4.3b", 2, 2, 2),
    "ui.theme.LookScreenshotsTest": ("4.3b", 1, 1, 1),
    "ui.theme.StyleReachesWindowsTest": ("4.3b", 3, 3, 3),
    "ui.transfer.backup.BackupPseudoLocaleTest": ("5.2", 4, 4, 4),
    "ui.transfer.backup.BackupScreensTest": ("5.2", 7, 7, 7),
    "ui.transfer.export.ExportPseudoLocaleTest": ("5.3", 3, 3, 3),
    "ui.transfer.export.ExportScreenTest": ("5.3", 8, 8, 8),
    "ui.transfer.export.ExportScreenshotsTest": ("5.3", 1, 1, 1),
    "ui.transfer.import.ImportPseudoLocaleTest": ("5.4", 3, 3, 3),
    "ui.transfer.import.ImportScreenTest": ("5.4", 6, 6, 6),
    "ui.transfer.import.ImportScreenshotsTest": ("5.4", 0, 0, 1),
}

def read(path):
    seen = collections.defaultdict(lambda: [0, 0, 0])  # (device, class) -> [ran, failed, skipped]
    for f in sorted(glob.glob(path + "/TEST-*.xml")):
        for suite in ET.parse(f).getroot().iter("testsuite"):
            devs = [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]
            for case in suite.iter("testcase"):
                cls = case.get("classname", "")
                cls = cls[len(prefix):] if cls.startswith(prefix) else cls
                for dev in devs:
                    entry = seen[(dev, cls)]
                    if case.find("skipped") is not None:
                        entry[2] += 1
                        continue
                    entry[0] += 1
                    if case.find("failure") is not None or case.find("error") is not None:
                        entry[1] += 1
    return seen

seen = read(d + "/full")
again = set()
for path in sorted(glob.glob(d + "/again/*/*/")):
    serial = os.path.basename(os.path.dirname(os.path.dirname(path)))
    for (dev, cls), entry in read(path.rstrip("/")).items():
        if dev == serial:
            seen[(dev, cls)] = entry
            again.add((dev, cls))
bad = 0
for api, serial, column in ((24, s24, 1), (28, s28, 2), (37, s37, 3)):
    by_package = collections.defaultdict(lambda: [0, 0])
    for cls, spec in expected.items():
        package, want = spec[0], spec[column]
        ran, failed, skipped = seen.get((serial, cls), (0, 0, 0))
        by_package[package][0] += ran
        by_package[package][1] += want
        if failed or ran < want:
            print(f"   API {api} ({serial}): {cls}: {ran} ran, {failed} failed, {skipped} skipped; expected at least {want}")
            bad = 1
    total = sum(v[0] for (dev, _), v in seen.items() if dev == serial)
    failed = sum(v[1] for (dev, _), v in seen.items() if dev == serial)
    want_total = sum(spec[column] for spec in expected.values())
    if failed or total < want_total:
        bad = 1
    others = sorted(cls for (dev, cls) in seen if dev == serial and cls not in expected)
    rerun = sorted(cls for (dev, cls) in again if dev == serial)
    print(f"   API {api} ({serial}): {total} tests ran (the table: {want_total}), {failed} failed; "
          + ", ".join(f"{p} {r}/{w}" for p, (r, w) in sorted(by_package.items()))
          + (f"; run again alone: {', '.join(rerun)}" if rerun else "")
          + (f"; other classes: {', '.join(others)}" if others else ""))
sys.exit(bad)
EOF
}

# ---- one device: the lock --------------------------------------------------------------------------------
# The checks below run on one device (dev_run), each as numbered steps that print PASS or FAIL. They are
# the plan's line for 4A (plan), lock.md E1, E2, E3, E6 and E11 without its language step (e1 ... e11)
# and F14 after an update (f14). They share what the vault on the device has (STATE): a check that needs
# a PIN, or a PIN and fingerprint, sets up a new vault only when the one before it left something else.
# The lock is driven through its screens by resource id, and read through the debug command `status`
# (debug and staging builds; app.sh, pva_status).
PIN=582913
NEW_PIN=274816
WRONG_PINS=(135790 246801 975310 864209 314159)
STATE=none # none | pin | pin+fp
T=""
DEV_APK=""
DEV_BASE=""
DEV_VARIANT=""
API=0

show_status() { pva_status | grep -E -- "${1:-.}" | sed 's/^/    | /'; }

# dots_are N: the pad's dots say that N of 6 digits are entered (their description).
dots_are() {
    local d
    d=$(pva_node pv.pin.dots | cut -d'|' -f2)
    case "$d" in *", $1 of 6 "*) return 0 ;; *) pv_log "dots: '$d'"; return 1 ;; esac
}

# pad_says REGEX: the pad's status line matches REGEX.
pad_says() {
    local t
    t=$(pva_text pv.lock.status 2>/dev/null)
    printf '%s' "$t" | grep -qE -- "$1" || { pv_log "the pad's line: '$t'"; return 1; }
}

lock_to_pad() { pva_lock_now && pva_need pv.lock.pad 20; }

# lock_to_pad_past_prompt: Lock now while fingerprint is on: the prompt opens by itself; "Use PIN" on it
# leaves the pad.
lock_to_pad_past_prompt() { pva_lock_now && pva_prompt_open UNLOCK 20 && pva_prompt_use_pin && pva_no_prompt && pva_need pv.lock.pad 20; }

pin_opens() { pva_pin "$1" && pva_opened 30; }

settle_open() { pva_settle Passwords 60 >/dev/null 2>&1; }

# kill9: the app's process ends with SIGKILL while it is in front: kill -9 through run-as (debug) or su
# (the userdebug images, API 24 and 28); where neither works (staging on API 37) am force-stop, whose
# SIGKILL comes from the system.
kill9() {
    local pid
    pid=$(pva_pid)
    [ -n "$pid" ] || { pv_log "no process"; return 1; }
    if pv_is_debuggable; then
        pv_shell run-as "$PV_PACKAGE" kill -9 "$pid" >/dev/null 2>&1
    elif pv_has_root; then
        pv_shell su 0 kill -9 "$pid" >/dev/null 2>&1
    else
        pv_log "neither run-as nor root here: am force-stop instead (SIGKILL from the system)"
        pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    fi
    pv_sleep 2
    [ "$(pva_pid)" != "$pid" ] || { pv_log "process $pid is still there"; return 1; }
}

# set_up_to_pin: setup with the suggested words (PVA_PASSWORD), until "Choose a PIN" opens over the list.
set_up_to_pin() {
    pva_on Welcome 60 || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$PVA_PASSWORD" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' || { pv_log "not six words: '$PVA_PASSWORD'"; return 1; }
    pva_tap pv.open.WriteDown && pva_on WriteDown || return 1
    pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" || return 1
    pva_submit pv.action.createVault pv.route.CreatePin 120
}

# fresh use|notNow: a new install and setup with the PIN, and fingerprint unlock turned on or not.
fresh() {
    STATE=none
    pv_step "$T: the $DEV_VARIANT build installs (fresh) and starts" -- eval 'pva_install "$DEV_APK" && pva_start' || return 1
    pv_step "$T: setup with the suggested words, to \"Choose a PIN\"" -- set_up_to_pin || return 1
    pv_step "$T: a PIN is chosen and typed again" -- pva_choose_pin "$PIN" || return 1
    pv_step "$T: fingerprint unlock: $1; setup ends on the list" -- pva_after_pin "$1" || return 1
    if [ "$1" = use ]; then STATE=pin+fp; else STATE=pin; fi
}

need() { # pin | pin+fp
    [ "$STATE" = "$1" ] && return 0
    if [ "$1" = pin+fp ]; then fresh use; else fresh notNow; fi
}

# to_master_password: from the fingerprint-only screen, or a prompt over it, to the master-password
# field ("Use master password" on the prompt leads back to that screen, the owner's question 4).
to_master_password() {
    local i
    for i in 1 2 3 4 5 6 7 8; do
        pva_present pv.lock.password 2 && return 0
        if pva_status | grep -q '^prompt: UNLOCK'; then
            pv_ui_tap '^(Use master password|USE MASTER PASSWORD)[|]' "$(pv_ui_nodes_all 2>/dev/null)" >/dev/null
        elif [ -n "$(pva_node pv.lock.useMasterPassword)" ]; then
            pva_tap pv.lock.useMasterPassword
        fi
        pv_sleep 1
    done
    pva_need pv.lock.password 10
}

# mp_opens: the master password on the lock screen's field opens the app (the list, the PIN steps or a
# notice over it).
mp_opens() {
    pva_need pv.lock.password 20 || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90
}

# ---- the plan's line for 4A (build-plan.md section 6) ----
plan() {
    T=4A
    local n=0 wrong
    fresh use || return 1
    pv_step "4A: status: a PIN slot and a fingerprint slot, with their Keystore keys" -- eval 'pva_status_has "^pin.rec: OK, slot set" && pva_status_has "^fingerprint.rec: OK, slot set" && pva_status_has "^keystore: .*pv_pin_" && pva_status_has "^keystore: .*pv_fp_"'
    show_status '^(pin.rec|fingerprint.rec|keystore)'
    pv_step "4A: an account is added" -- eval 'pva_add_account Pochta dilshod.m Qwerty-12345 https://pochta.example.uz eslatma && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords'
    pv_step "4A: Lock now: the fingerprint prompt opens by itself" -- eval 'pva_lock_now && pva_prompt_open UNLOCK 20'
    pv_step "4A: finger 1 opens the app" -- eval 'pva_finger 1 && pva_opened 20'
    settle_open
    pv_step "4A: Lock now; \"Use PIN\" on the prompt: the pad" -- lock_to_pad_past_prompt
    pv_step "4A: the PIN opens the app" -- pin_opens "$PIN"
    settle_open
    pv_step "4A: Lock now; \"Use PIN\": the pad" -- lock_to_pad_past_prompt
    for wrong in "${WRONG_PINS[@]:0:4}"; do
        n=$((n + 1))
        pva_pin "$wrong"; pv_sleep 3
        pv_step "4A: wrong PIN $n: the pad stays and says \"Wrong PIN\" with $((5 - n)) tries left" -- pad_says "Wrong PIN.*$((5 - n))"
    done
    pva_pin "${WRONG_PINS[4]}"; pv_sleep 3
    pv_step "4A: the fifth wrong PIN: the PIN is off (pin.rec: no slot, off limit; its key gone), fingerprint stays" -- eval 'pva_status_has "^pin.rec: OK, slot none, off limit" && pva_status_lacks "^keystore: .*pv_pin_" && pva_status_has "^fingerprint.rec: OK, slot set"'
    show_status '^(state|policy|pin.rec|fingerprint.rec)'
    pv_step "4A: the lock screen leads to the master-password field (the fingerprint-only screen's \"Use master password\")" -- to_master_password
    pv_step "4A: the master password opens the app" -- mp_opens
    pv_step "4A: \"Choose a new PIN\" follows (lock.md F5 step 4)" -- pva_to_create_pin
    pv_step "4A: a new PIN is chosen and typed again" -- pva_choose_pin "$NEW_PIN"
    pv_step "4A: the PIN steps end on the list" -- pva_after_pin notNow
    pv_step "4A: status: a new PIN slot with its key, the counts at 0" -- eval 'pva_status_has "^pin.rec: OK, slot set, .*row 0, total 0" && pva_status_has "^keystore: .*pv_pin_"'
    show_status '^(pin.rec|fingerprint.rec)'
    pv_step "4A: Lock now; \"Use PIN\": the pad" -- lock_to_pad_past_prompt
    pv_step "4A: the new PIN opens the app" -- pin_opens "$NEW_PIN"
    settle_open
    pv_step "4A: the account is still there" -- pva_row_with Pochta 10
    pv_step "4A: Lock now: the prompt opens by itself; finger 1 opens the app" -- eval 'pva_lock_now && pva_prompt_open UNLOCK 20 && pva_finger 1 && pva_opened 20'
    settle_open
    pv_step "4A: no crash" -- pva_no_crash
}

# ---- E1 ----
e1() {
    T=E1
    local n=0 wrong
    fresh notNow || return 1
    pv_step "E1: status: the PIN is usable and has its Keystore key" -- eval 'pva_status_has "^pin.rec: OK, slot set" && pva_status_has "^keystore: .*pv_pin_"'
    pv_step "E1: Lock now shows the pad" -- lock_to_pad
    pv_step "E1: status: the lock screen is the pad, the PIN usable" -- pva_status_has '^policy: .*pin USABLE'
    pv_step "E1: the PIN opens the app (a real Keystore HMAC key)" -- pin_opens "$PIN"
    pv_step "E1: the time from the sixth digit to the unlocked state is logged" -- pva_status_has '^last PIN unlock: [0-9]+ ms'
    show_status '^last PIN'
    settle_open
    pv_step "E1: Lock now shows the pad again" -- lock_to_pad
    for wrong in "${WRONG_PINS[@]:0:4}"; do
        n=$((n + 1))
        pva_pin "$wrong"; pv_sleep 3
        pv_step "E1: wrong PIN $n: the pad stays and says \"Wrong PIN\" with $((5 - n)) tries left" -- pad_says "Wrong PIN.*$((5 - n))"
    done
    pva_pin "${WRONG_PINS[4]}"; pv_sleep 3
    pv_step "E1: the fifth wrong PIN: the master-password screen is shown" -- pva_need pv.lock.password 20
    echo "    | title: $(pva_text pv.lock.title 2>/dev/null) / reason: $(pva_text pv.lock.reason 2>/dev/null)"
    pv_step "E1: status: the slot is gone (pin.rec: slot none, off limit)" -- pva_status_has '^pin.rec: OK, slot none, off limit'
    pv_step "E1: status: the PIN's alias is gone" -- pva_status_lacks '^keystore: .*pv_pin_'
    show_status '^(state|policy|pin.rec|keystore)'
    pv_step "E1: the master password still opens" -- mp_opens
    settle_open
    pv_step "E1: no crash" -- pva_no_crash
    STATE=none
}

# ---- E2 ----
e2() {
    T=E2
    need pin || return 1
    settle_open
    pv_step "E2: Lock now shows the pad" -- lock_to_pad
    pv_step "E2: hold-after-charge is armed" -- pva_debug hold-after-charge
    pva_pin 5; pv_sleep 2
    pv_step "E2: the first digit is held with its charge on disk" -- pva_status_has '^hold: after-charge \(reached\)'
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null; pv_sleep 1
    pv_step "E2: after am force-stop the app starts again" -- pva_start
    pv_step "E2: the pad is shown" -- pva_need pv.lock.pad 30
    pv_step "E2: it says the entry was interrupted and counted, 4 tries left" -- pad_says 'interrupted and counted as a try. 4'
    show_status '^(pin.rec|hold)'
    pv_step "E2: hold-before-hmac is armed" -- pva_debug hold-before-hmac
    pva_pin "$PIN"; pv_sleep 3
    pv_step "E2: the check of the right PIN is held before the Keystore is asked" -- pva_status_has '^hold: before-hmac \(reached\)'
    pv_step "E2: kill -9" -- kill9
    pv_step "E2: the app starts again" -- pva_start
    pv_step "E2: the pad is shown" -- pva_need pv.lock.pad 30
    pv_step "E2: it says the entry was interrupted and counted, 3 tries left" -- pad_says 'interrupted and counted as a try. 3'
    show_status '^(pin.rec|hold)'
    pv_step "E2: the PIN opens the app" -- pin_opens "$PIN"
    settle_open
    pv_step "E2: no crash" -- pva_no_crash
}

# ---- E3 ----
e3() {
    T=E3
    need pin || return 1
    settle_open
    pv_step "E3: Lock now shows the pad" -- lock_to_pad
    pv_step "E3: hold-after-charge is armed" -- pva_debug hold-after-charge
    pva_pin 5; pv_sleep 2
    pv_step "E3: the first digit is held with its charge on disk" -- pva_status_has '^hold: after-charge \(reached\)'
    pv_step "E3: adb reboot" -- pv_reboot 420
    pv_step "E3: the app starts" -- pva_start
    pv_step "E3: the pad is shown" -- pva_need pv.lock.pad 60
    pv_step "E3: the charge is counted after the reboot: \"interrupted and counted as a try\"" -- pad_says 'interrupted and counted as a try'
    show_status '^pin.rec'
    pv_step "E3: the PIN opens the app" -- pin_opens "$PIN"
    settle_open
    pv_step "E3: no crash" -- pva_no_crash
}

# ---- E6 ----
e6() {
    T=E6
    local launch
    need pin+fp || return 1
    settle_open
    pv_step "E6: status: fingerprint unlock is on (fingerprint.rec with a slot, its key)" -- eval 'pva_status_has "^fingerprint.rec: OK, slot set" && pva_status_has "^keystore: .*pv_fp_"'
    show_status '^(canAuthenticate|fingerprint.rec|keystore)'
    pv_step "E6: Lock now: the fingerprint prompt opens by itself (lock.md 9.2 rule 7)" -- eval 'pva_lock_now && pva_prompt_open UNLOCK 20'
    launch=$(pva_prompt_launch)
    show_status '^prompt'
    pva_finger 5; pv_sleep 2
    pv_step "E6: a finger that is not enrolled leaves the prompt open (the same launch)" -- pva_status_has "^prompt: UNLOCK, launch $launch,"
    pva_finger 1
    pv_step "E6: finger 1 opens the app with no further tap" -- pva_opened 20
    pv_step "E6: no prompt is left" -- pva_no_prompt
    settle_open
    pv_step "E6: Lock now: the prompt opens by itself again" -- eval 'pva_lock_now && pva_prompt_open UNLOCK 20'
    if [ "$API" -lt 28 ]; then
        echo "    | Android 7, the library's own dialog in the activity: $(pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-300)"
        pv_step "E6: Android 7: the library's dialog is inside the app's activity" -- eval 'pva_in_front && pv_ui_nodes | grep -q "^USE PIN|"'
    fi
    pv_step "E6: \"Use PIN\" on the prompt returns to the pad" -- eval 'pva_prompt_use_pin && pva_no_prompt && pva_need pv.lock.pad 20'
    pv_sleep 3
    pv_step "E6: no prompt opens by itself a second time in this visit" -- pva_status_has '^prompt: none'
    pv_step "E6: the PIN opens the app" -- pin_opens "$PIN"
    settle_open
    pv_step "E6: no crash" -- pva_no_crash
}

# ---- E11, without its language step ----
e11() {
    T=E11
    local before after launch half rest
    need pin+fp || return 1
    settle_open
    pv_step "E11: Lock now; \"Use PIN\": the pad" -- lock_to_pad_past_prompt
    pva_pin 582; pv_sleep 1
    pv_step "E11: three digits typed: three dots" -- dots_are 3
    before=$(pva_status | grep -E '^(visible|pin.rec)' | tr '\n' ';')
    echo "    | before: $before"
    pva_rotate 1
    pv_step "E11: landscape: still three dots" -- dots_are 3
    after=$(pva_status | grep -E '^(visible|pin.rec)' | tr '\n' ';')
    pv_step "E11: landscape: the same visit and the same charge" -- [ "$before" = "$after" ]
    pva_rotate 0
    pv_step "E11: portrait again: still three dots" -- dots_are 3
    after=$(pva_status | grep -E '^(visible|pin.rec)' | tr '\n' ';')
    pv_step "E11: portrait again: the same visit and the same charge" -- [ "$before" = "$after" ]
    pva_pin 913
    pv_step "E11: the other three digits open the app (the six digits were one entry)" -- pva_opened 30
    settle_open
    pv_step "E11: Lock now: the prompt opens by itself" -- eval 'pva_lock_now && pva_prompt_open UNLOCK 20'
    launch=$(pva_prompt_launch)
    pva_rotate 1
    pv_step "E11: rotated with the prompt open: the same launch is still open" -- pva_status_has "^prompt: UNLOCK, launch $launch,"
    pva_finger 1
    pv_step "E11: the result reaches the new activity: the app opens" -- pva_opened 20
    pva_rotate 0
    pv_step "E11: portrait again: still open" -- pva_opened 20
    settle_open
    pv_step "E11: Lock now; \"Use PIN\"; \"Forgot PIN? Use master password\": the field" -- eval 'lock_to_pad_past_prompt && pva_tap pv.lock.forgotPin && pva_need pv.lock.password 20'
    half=${PVA_PASSWORD:0:$((${#PVA_PASSWORD} / 2))}
    rest=${PVA_PASSWORD:$((${#PVA_PASSWORD} / 2))}
    pva_type pv.lock.password "$half"
    pva_rotate 1
    pv_step "E11: landscape: the field keeps what was typed (as many mask characters)" -- eval '[ "$(pva_text pv.lock.password | wc -m)" -ge ${#half} ]'
    pva_rotate 0
    pva_type pv.lock.password "$rest"
    pv_step "E11: after two rotations the field holds what was typed before and after: Unlock opens the app" -- eval 'pva_hide_keyboard; pva_tap pv.lock.unlock && pva_opened 90'
    settle_open
    pv_step "E11: no crash" -- pva_no_crash
}

# ---- F14 after an update ----
# A vault made by the build of wave-3C-merged (the master-password gate of 3.2: no lock directory) is
# opened by this build after an update over it, as on the user's own emulator (lock.md F14).
f14() {
    T=F14
    STATE=none
    [ -f "$DEV_BASE" ] || { pv_report FAIL "F14: the older APK" "no file $DEV_BASE"; return 1; }
    pv_step "F14: the build of wave-3C-merged installs (fresh) and starts" -- eval 'pva_install "$DEV_BASE" && pva_start' || return 1
    pv_step "F14: setup with that build ends on the list" -- pva_set_up || return 1
    pv_step "F14: an account is added" -- eval 'pva_add_account Pochta dilshod.m Qwerty-12345 https://pochta.example.uz eslatma && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords'
    pv_step "F14: that build kept no lock directory" needs-debuggable -- eval '! pv_shell run-as "$PV_PACKAGE" ls no_backup 2>/dev/null | tr -d "\r" | grep -qx lock'
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pv_step "F14: today's build installs over it (adb install -r, the data kept)" -- eval 'PV_ADB_TIMEOUT=120 pv_adb install -r "$DEV_APK" | grep -q "^Success"'
    pv_step "F14: today's build starts" -- pva_start
    pv_step "F14: the lock screen is the master-password screen" -- eval 'pva_on Locked 60 && pva_need pv.lock.password 30'
    echo "    | title: $(pva_text pv.lock.title 2>/dev/null) / reason: $(pva_text pv.lock.reason 2>/dev/null)"
    pv_step "F14: its title is \"Welcome back\" (ux.md 5.9)" -- eval '[ "$(pva_text pv.lock.title 2>/dev/null)" = "Welcome back" ]'
    pv_step "F14: the master password opens the vault" -- mp_opens
    pv_step "F14: \"Choose a PIN\" follows (lock.md F14 step 2, then F1 step 5)" -- pva_to_create_pin
    pv_step "F14: a PIN is chosen and typed again" -- pva_choose_pin "$PIN"
    pv_step "F14: the PIN steps end on the list (\"Make a backup now?\" answered Later)" -- pva_after_pin notNow
    pv_step "F14: the account is in the list" -- pva_row_with Pochta 10
    pv_step "F14: status: state.rec and pin.rec were made" -- eval 'pva_status_has "^state.rec: OK" && pva_status_has "^pin.rec: OK, slot set"'
    show_status '^(lock |state.rec|pin.rec)'
    pv_step "F14: the lock directory with state.rec is under no_backup" needs-debuggable -- eval 'pv_shell run-as "$PV_PACKAGE" ls -R no_backup 2>/dev/null | tr -d "\r" | grep -qx state.rec'
    pv_step "F14: Lock now shows the pad; the PIN opens the app" -- eval 'lock_to_pad && pin_opens "$PIN"'
    settle_open
    pv_step "F14: no crash" -- pva_no_crash
}

# dev_run SERIAL VARIANT APK BASE CHECK...: the checks on one device, then its summary.
dev_run() {
    local check
    pv_use "$1"
    pva_init
    DEV_VARIANT=$2
    DEV_APK=$3
    DEV_BASE=$4
    shift 4
    API=$(pv_api)
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-180}
    echo "4A checks $*, $DEV_VARIANT on $PV_SERIAL (API $API): $DEV_APK"
    for check in "$@"; do
        case "$check" in
            plan | e1 | e2 | e3 | e6 | e11 | f14) "$check" ;;
            *) pv_die "unknown check $check" 2 ;;
        esac
    done
    pva_rotate 0
    pv_summary
}

# ---- arguments ------------------------------------------------------------------------------------------
BASE_DEBUG=$LOGS_ROOT/base-3C-merged-debug.apk
BASE_STAGING=$LOGS_ROOT/base-3C-merged-staging.apk

# "one SERIAL VARIANT [--apk FILE] [--base FILE] CHECK...": checks on one device of this worktree.
if [ "${1:-}" = one ]; then
    [ $# -ge 4 ] || pv_usage "${BASH_SOURCE[0]}"
    one_serial=$2
    one_variant=$3
    shift 3
    case "$one_variant" in debug | staging) ;; *) pv_usage "${BASH_SOURCE[0]}" ;; esac
    one_apk=$REPO/app/build/outputs/apk/$one_variant/app-$one_variant.apk
    one_base=$LOGS_ROOT/base-3C-merged-$one_variant.apk
    one_checks=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; one_apk=$2; shift ;;
            --base) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; one_base=$2; shift ;;
            *) one_checks+=("$1") ;;
        esac
        shift
    done
    [ ${#one_checks[@]} -gt 0 ] || pv_usage "${BASH_SOURCE[0]}"
    mkdir -p "$LOGS_ROOT"
    dev_run "$one_serial" "$one_variant" "$one_apk" "$one_base" "${one_checks[@]}"
    exit $?
fi

ALL_PARTS=(merged connected)
DEVICE_PARTS=(connected)
parts=()
gradle_args=()
keep=0
while [ $# -gt 0 ]; do
    case "$1" in
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --keep) keep=1 ;;
        --) shift; gradle_args=("$@"); break ;;
        stop) parts+=("$1") ;;
        *)
            [[ " ${ALL_PARTS[*]} " == *" $1 "* ]] || pv_usage "${BASH_SOURCE[0]}"
            parts+=("$1") ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=("${ALL_PARTS[@]}")
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }
needs_devices() { local p; for p in "${DEVICE_PARTS[@]}"; do wants "$p" && return 0; done; return 1; }

mkdir -p "$LOGS_ROOT"
echo "round R3 (sub-wave 4A) device check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R3"
    pv_step "the heads of 4.1 to 4.4, 5.2 to 5.4 and the reviews of 3.15 and 5.1 are in HEAD" -- heads_merged
fi
if needs_devices; then
    echo
    echo "== devices: the integrator's API 24, 28 and 37"
    if ! pv_step "build the debug and staging APKs" -- build_apks || ! pv_step "the integrator's devices run (boot.sh --integrator)" -- devices; then
        parts=(stop)
    fi
fi
for part in "${DEVICE_PARTS[@]}"; do
    wants "$part" || continue
    LOGS=$LOGS_ROOT/$part
    [ "$part" = connected ] || rm -rf "$LOGS"
    mkdir -p "$LOGS"
    echo
    echo "== $part ($(date +%T))"
    began=$(date +%s)
    case $part in
        connected)
            pv_step "connectedDebugAndroidTest on API 24, 28 and 37 (adb.sh --connected; a failed class once more alone)" -- connected_tests
            pv_step "every device-test class passed on each device with at least its tests (469, 470, 477)" -- device_classes
            studio_runs "$began" ;;
    esac
done
if needs_devices || wants stop; then
    if [ $keep = 1 ]; then
        echo
        echo "== the devices keep running (--keep): ${SER[*]:-}"
    else
        [ ${#SER[@]} -gt 0 ] || for api in "${APIS[@]}"; do SER[$api]=emulator-$(pv_integrator_port "$api"); done
        echo
        pv_step "the devices are stopped" -- stop_devices
    fi
fi
echo
PV_SERIAL="" pv_summary
