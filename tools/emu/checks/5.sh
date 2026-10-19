#!/usr/bin/env bash
# tools/emu/checks/5.sh - the device check of round R4: waves 4, 5 and 7 (build-plan.md section 12, "The
# integration in two steps", step 2, and its block "From the merge of R4 to round R5", "What the device
# check of waves 4, 5 and 7 runs"; the integration checks of wave 4 in section 6, of wave 5 in section 7 and
# of wave 7 in section 9). The merge ran tools/check.sh all before it tagged wave-5-merged. Round R4 brought
# the lock's emulator scripts (4.5), the change and replacement of the master password (5.5), the language
# switch (6.1), the Security tab (7.1), the guided change (7.2), help, licences and the first-copy tip
# (7.3), and the carry jobs w5-carry and w5-carry2.
#
#   tools/emu/checks/5.sh [PART...] [--levels 24,28,37] [--shots DIR] [--keep] [-- GRADLE-ARGS...]
#
# The parts run in this order, all of them when none is named, on the integrator's devices (5584 is
# API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used as
# it is, the others are booted together (boot.sh --integrator). --levels limits every part to those API
# levels (a part runs on the levels it has among them), so that two parts can share the three devices
# one after another in either order. The debug and staging APKs are built first (tools/gradle.sh). The
# devices are stopped at the end unless --keep is given; the part "stop" does nothing else.
#   merged      the heads that round R4 merged are in HEAD: w5-carry, w5-carry2, w4B-4.5, w5-5.5,
#               w6A-6.1, w7-7.1, w7-7.2 and w7-7.3.
#   connected   adb.sh --connected on the devices, in touch mode (the keyboard-mode step of checks/4A.sh
#               is gone since the merge of R4: FieldKeyboard brings the keyboard back after a rotation;
#               each device gets a tap on its launcher first, which ends keyboard mode on API 24 and 28,
#               and the tests that rotate set touch mode themselves, as on API 37 a tap of the shell's
#               leaves the display's mode as it is): every connected test, and every serial has a
#               passing report (BUILD SUCCESSFUL alone does not count). A class that failed is run once
#               more, alone, on that device (one run per class: of a comma-separated class list the
#               runner took only the first); it counts as passed when it passes alone, and the step names
#               it as a flake. Then every device-test class ran on each device with no failure and at
#               least the tests it has there: 563 on API 24, 565 on 28, 575 on 37 at wave-5-merged
#               (counted from the sources with their @SdkSuppress filters).
#   lock        wave 4 (section 6): tools/emu/lock/all.sh on the devices, the debug build: E1 to E3, E6,
#               E7, E10 and E11 on API 24, 28 and 37; E4, E5, E8, E9, E14 and E17 on 37; E12, E13 and E15
#               on 28 and 37; E16 on 24 (lock/README.md). E4's pending wait and "replaced" notice, E5
#               and E11's language step, which printed SKIPPED on 4.5's branch, must run and pass on the
#               merged app: the part fails when a log still says "waits for build plan". E16 passes
#               since the owner's decision 22.
#   lockstaging the staging repeat: lock/all.sh --staging --only E1,E6 --levels 24,37, and E16 on API 24
#               with the staging build (decision 22 was shown with both builds).
#   wave5       wave 5 (section 7, lines 1 to 8, with U 12 check 15), debug build on API 37 and 24, each
#               line as numbered steps on one device (the one-device checks below, run by dev_run).
#               Written in the next commit.
#   wave7       wave 7 (section 9), written in the next commit.
#   i18n        6.1's [script] line: tools/emu/i18n/all.sh on the devices (i18n.md 5's matrix and lock.md
#               E11's language step through Settings; about 25 minutes a device; on API 37 the phone's
#               language page never marks Ўзбекча, a KNOWN line).
#   extras      the scripts the merge of R4 changed and no device had run: shell/walk.sh (debug on the
#               devices, staging on API 37), smoke.sh debug (its copy stage closes the first-copy tip),
#               checks/3B.sh clip (the same), clipboard/clear.sh (the same; about 16 minutes a device),
#               app.sh sourced a second time (the screen size and the watched message kept) and boot.sh's
#               airplane mode on API 37 (on, no default network; API 24 and 28 untouched). change/guided.sh
#               runs in the part wave7, lock/newmp.sh in the parts lock (E4, E5) and wave5 (check 15).
#   skeleton    checks/3A.sh skeleton: the debug build on API 24, 28 and 37, the staging build on 24
#               and 37, each from a fresh install.
#
# After a part that drives the app, a NOTE names every Run that Android Studio started on one of the
# devices meanwhile (its idea.log; tools/emu/README.md, "Device facts"): such a Run stops, reinstalls
# and starts the app under whatever step is running.
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/5/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error, 3 for a refused serial. Written by the device check of
# round R4 (build-plan.md section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SHOTS=/home/asus/pvwork/shots-5
LOGS_ROOT=$REPO/build/emu-checks/5
DEBUG_APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
STAGING_APK=$REPO/app/build/outputs/apk/staging/app-staging.apk
TEST_APK=$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug
RUNNER_ARG=-Pandroid.testInstrumentationRunnerArguments

# ---- merged ------------------------------------------------------------------------------------------
# BRANCH:HEAD as round R4 merged them (the merge commits 1d3b692, 0399ffb, 2c0e7fe, ea5a6b5, a56b75c,
# aabd060, 105b013 and 18ea726 have them as their second parents).
MERGED=(w5-carry:a71c7d9 w5-carry2:99ff85d w4B-4.5:ba97b8b w5-5.5:f456359 w6A-6.1:f424702 w7-7.1:0b31fb3
    w7-7.2:47c3a2d w7-7.3:1843d1e)

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

# has_level API: the level is among --levels.
has_level() { [[ " ${APIS[*]} " == *" $1 "* ]]; }

# levels_of API...: those of the given levels that are among --levels.
levels_of() {
    local api out=()
    for api in "$@"; do has_level "$api" && out+=("$api"); done
    echo "${out[*]}"
}

# serials_of API...: the serials of those levels (among --levels).
serials_of() {
    local api out=()
    for api in $(levels_of "$@"); do out+=("${SER[$api]}"); done
    echo "${out[*]}"
}

# studio_runs SINCE: a NOTE for each Run that Android Studio started on one of the devices since SINCE
# (date +%s), as its log has them ("... AndroidRunConfigurationExecutor - Launching on device
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

# in_parallel NAME COMMAND-WORDS... -- API...: COMMAND SERIAL on each device of those levels that is
# among --levels, at once, its output in LOGS/NAME-API.log; prints each device's step lines, and the
# Runs Android Studio made meanwhile. Nothing to run (no such level) is a pass with a line that says so.
in_parallel() {
    local name=$1 api rc=0 status cmd=() began levels
    shift
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do cmd+=("$1"); shift; done
    shift
    levels=$(levels_of "$@")
    [ -n "$levels" ] || { echo "   $name: none of API $* is among --levels"; return 0; }
    began=$(date +%s)
    declare -A pids=()
    for api in $levels; do
        "${cmd[@]}" "${SER[$api]}" > "$LOGS/$name-$api.log" 2>&1 &
        pids[$api]=$!
    done
    for api in $levels; do
        wait "${pids[$api]}"
        status=$?
        echo "   $name on API $api (${SER[$api]}): exit $status"
        grep -E '^(PASS|FAIL|SKIPPED|KNOWN|SUMMARY|NOTE|   )' "$LOGS/$name-$api.log" | sed 's/^/     /'
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
        fi
    done
    return $rc
}

# ---- connected -------------------------------------------------------------------------------------------
CONN_DIR=""

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

# touch_mode SERIAL...: Home and a tap on an empty part of the launcher (the middle of the screen): out
# of keyboard mode, as a phone in the hand is; prints each device's mode as dumpsys reads it.
touch_mode() {
    local s h
    for s in "$@"; do
        h=$(PV_SERIAL=$s pv_shell wm size 2> /dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
        PV_SERIAL=$s pv_shell input keyevent KEYCODE_HOME > /dev/null 2>&1
        sleep 1
        PV_SERIAL=$s pv_shell input tap 540 $((${h:-1920} * 45 / 100)) > /dev/null 2>&1
        sleep 1
        echo "   $s: $(PV_SERIAL=$s pv_shell dumpsys window 2> /dev/null | tr -d '\r' | grep -m 1 -o 'mInTouchMode=[a-z]*')$(PV_SERIAL=$s pv_shell dumpsys input 2> /dev/null | tr -d '\r' | grep -m 1 -o 'Display: 0 TouchMode: [0-9]*' | sed 's/^/, input: /')"
    done
}

connected_tests() {
    local dir=$CONN_DIR rc s cls again=0 serials
    serials=$(serials_of 24 28 37)
    rm -rf "$dir"
    mkdir -p "$dir/full"
    touch_mode $serials
    # shellcheck disable=SC2086 # the serials are words
    "$EMU/adb.sh" --connected $serials ${gradle_args[@]+-- "${gradle_args[@]}"} > "$dir/full.log" 2>&1
    rc=$?
    cp -r "$RESULTS/." "$dir/full/" 2> /dev/null
    grep -E '^(PASS|FAIL) connected|^BUILD' "$dir/full.log" | sed 's/^/   /'
    [ $rc -ne 0 ] || return 0
    failed_classes "$dir/full" > "$dir/failed" 2> "$dir/failed-tests"
    cat "$dir/failed-tests"
    # A device without a failing test failed otherwise (no report: an install, a lost device).
    for s in $serials; do
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
        touch_mode "$s" > /dev/null
        "$EMU/adb.sh" --connected "$s" -- "$RUNNER_ARG.class=$cls" ${gradle_args[@]+"${gradle_args[@]}"} > "$dir/again/$s/$cls.log" 2>&1 < /dev/null || again=1
        cp -r "$RESULTS/." "$dir/again/$s/$cls/" 2> /dev/null
        grep -E '^(PASS|FAIL) connected' "$dir/again/$s/$cls.log" | sed 's/^/     alone: /'
    done < "$dir/failed"
    [ $again -eq 0 ] || return 1
    echo "   FLAKY: each class that failed in the full run passed alone: $(awk '{ printf "%s on %s; ", $2, $1 }' "$dir/failed")"
}

# Each device-test class with its package and the tests it has on API 24, 28 and 37 at wave-5-merged
# (counted from the sources with their @SdkSuppress filters: 563, 565 and 575; the same count gives 469,
# 470 and 477 at wave-4A-merged, what the device check of 4A ran); a class run again alone
# (connected_tests) counts with that run's result. Only the levels among --levels are judged.
device_classes() {
    python3 - "$CONN_DIR" "24=${SER[24]:-}" "28=${SER[28]:-}" "37=${SER[37]:-}" "${APIS[@]}" <<'EOF'
import collections, glob, os, sys, xml.etree.ElementTree as ET
d = sys.argv[1]
serial_of = dict(a.split("=", 1) for a in sys.argv[2:5])
levels = [int(a) for a in sys.argv[5:]]
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
    "i18n.FormattersTest": ("2.9", 8, 8, 8),
    "i18n.LocaleCommandTest": ("2.9", 2, 2, 2),
    "i18n.SortKeysTest": ("2.9", 8, 8, 8),
    "ui.setup.TypeAgainRotationTest": ("3.3", 1, 1, 1),
    "ui.lock.MasterPasswordRotationTest": ("3.4", 1, 1, 1),
    "ui.lock.MasterPasswordScreenTest": ("3.4", 7, 7, 7),
    "ui.list.ListPerformanceTest": ("3.5", 1, 1, 2),
    "ui.list.ListPseudoLocaleTest": ("3.5", 5, 5, 5),
    "ui.list.PasswordsScreenTest": ("3.5", 11, 11, 11),
    "ui.edit.EditKeyboardRotationTest": ("3.6", 3, 4, 4),
    "ui.edit.EditScreenTest": ("3.6", 4, 4, 4),
    "ui.entry.BigLettersScreenTest": ("3.7", 10, 10, 10),
    "ui.entry.EntryPseudoLocaleTest": ("3.7", 6, 6, 6),
    "ui.entry.EntrySavedStateTest": ("3.7", 2, 2, 2),
    "ui.entry.EntryScreenTest": ("3.7", 17, 17, 17),
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
    "ui.lock.LockScreensTest": ("4.2", 10, 10, 10),
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
    "ui.transfer.backup.BackupScreensTest": ("5.2", 8, 8, 8),
    "ui.transfer.export.ExportPseudoLocaleTest": ("5.3", 3, 3, 3),
    "ui.transfer.export.ExportScreenTest": ("5.3", 8, 8, 8),
    "ui.transfer.export.ExportScreenshotsTest": ("5.3", 1, 1, 1),
    "ui.transfer.import.ImportPseudoLocaleTest": ("5.4", 3, 3, 3),
    "ui.transfer.import.ImportScreenTest": ("5.4", 7, 7, 7),
    "ui.transfer.import.ImportScreenshotsTest": ("5.4", 0, 0, 1),
    "i18n.RememberFormattersTest": ("w5-carry", 1, 1, 1),
    "ui.common.FieldKeyboardTest": ("w5-carry", 1, 1, 1),
    "ui.common.KeyboardFirstOnBackTest": ("w5-carry", 0, 0, 2),
    "ui.theme.LookDetailsTest": ("w5-carry", 6, 6, 6),
    "ui.theme.LookPseudoLocaleTest": ("w5-carry", 1, 1, 1),
    "window.ObscuredTouchNoticeLookTest": ("w5-carry", 0, 1, 1),
    "ui.settings.master.MasterPasswordRotationTest": ("5.5", 3, 3, 3),
    "ui.settings.master.MasterPasswordScreensTest": ("5.5", 14, 14, 14),
    "ui.settings.master.MasterPseudoLocaleTest": ("5.5", 2, 2, 2),
    "ui.settings.master.MasterScreenshotsTest": ("5.5", 2, 2, 2),
    "ui.settings.LanguageDialogTest": ("6.1", 11, 11, 11),
    "ui.settings.LanguageSwitchTest": ("6.1", 1, 1, 1),
    "ui.security.SecurityCheckDeviceTest": ("7.1", 1, 1, 1),
    "ui.security.SecurityPseudoLocaleTest": ("7.1", 5, 5, 5),
    "ui.security.SecurityScreenTest": ("7.1", 13, 13, 13),
    "ui.security.SecurityScreenshotsTest": ("7.1", 0, 0, 1),
    "ui.security.change.GuidedChangePseudoLocaleTest": ("7.2", 6, 6, 6),
    "ui.security.change.GuidedChangeScreenTest": ("7.2", 8, 8, 8),
    "ui.security.change.GuidedChangeSeed": ("7.2", 1, 1, 1),
    "ui.settings.help.HelpPseudoLocaleTest": ("7.3", 2, 2, 2),
    "ui.settings.help.HelpScreensTest": ("7.3", 4, 4, 4),
    "ui.shell.FirstCopyTipScreenTest": ("7.3", 3, 3, 3),
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
# The report names a device by its AVD and version ("pvlab_api37(AVD) - 17"), not by its serial.
avd = {24: "pvlab_api24(AVD) - 7.0", 28: "pvlab_api28(AVD) - 9", 37: "pvlab_api37(AVD) - 17"}
def device_key(api):
    s = serial_of[str(api)]
    devs = {dev for (dev, _) in seen}
    return s if s in devs else avd[api]
bad = 0
for api, column in ((24, 1), (28, 2), (37, 3)):
    if api not in levels:
        continue
    key = device_key(api)
    by_package = collections.defaultdict(lambda: [0, 0])
    for cls, spec in expected.items():
        package, want = spec[0], spec[column]
        ran, failed, skipped = seen.get((key, cls), (0, 0, 0))
        by_package[package][0] += ran
        by_package[package][1] += want
        if failed or ran < want:
            print(f"   API {api} ({key}): {cls}: {ran} ran, {failed} failed, {skipped} skipped; expected at least {want}")
            bad = 1
    total = sum(v[0] for (dev, _), v in seen.items() if dev == key)
    failed = sum(v[1] for (dev, _), v in seen.items() if dev == key)
    want_total = sum(spec[column] for spec in expected.values())
    if failed or total < want_total:
        bad = 1
    others = sorted(cls for (dev, cls) in seen if dev == key and cls not in expected)
    rerun = sorted(cls for (dev, cls) in again if dev == key)
    print(f"   API {api} ({key}): {total} tests ran (the table: {want_total}), {failed} failed; "
          + ", ".join(f"{p} {r}/{w}" for p, (r, w) in sorted(by_package.items()))
          + (f"; run again alone: {', '.join(rerun)}" if rerun else "")
          + (f"; other classes: {', '.join(others)}" if others else ""))
sys.exit(bad)
EOF
}

# ---- lock: wave 4 ----------------------------------------------------------------------------------------
LOCK_OUT=$REPO/build/emu-lock/all

# lock_all NAME "LEVEL..." ALL.SH-ARGS...: tools/emu/lock/all.sh with those arguments on those of the
# levels that are among --levels (all.sh's --levels, and their serials), so that it runs the checks of
# exactly those levels; its table and logs are copied to LOGS/NAME (all.sh keeps only its last run).
lock_all() {
    local name=$1 levels rc began
    levels=$(levels_of $2)
    shift 2
    [ -n "$levels" ] || { echo "   none of the levels is among --levels"; return 0; }
    began=$(date +%s)
    # shellcheck disable=SC2046,SC2086 # the levels and serials are words
    "$EMU/lock/all.sh" "$@" --levels "${levels// /,}" $(serials_of $levels) > "$LOGS/$name.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/$name"
    cp -r "$LOCK_OUT/." "$LOGS/$name/" 2> /dev/null
    sed -n '/^SUMMARY lock checks/,$p' "$LOGS/$name.log" | sed 's/^/   /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# lock_later_ran DIR: E4, E5 and E11's logs in DIR (all.sh's SERIAL-EN.log) have no step left that waits
# for 5.5 or 6.1: the steps of the merged packages ran. Each such step is listed with its result.
lock_later_ran() {
    local f bad=0 n
    for f in "$1"/*-E4.log "$1"/*-E5.log "$1"/*-E11.log; do
        [ -f "$f" ] || continue
        n=$(grep -c 'waits for build plan' "$f")
        echo "   ${f##*/}: $(grep -m 1 '^SUMMARY' "$f" | sed 's/^SUMMARY //'); steps that wait for 5.5 or 6.1: $n"
        grep -E '^(PASS|FAIL|SKIPPED) .*(new master password|pending|replaced|language|Language|boot_count|24 hours)' "$f" | sed 's/^/     /'
        [ "$n" = 0 ] || bad=1
    done
    return $bad
}

e16_staging() { "$EMU/lock/E16.sh" "$1" --apk "$STAGING_APK"; }

# ---- i18n: 6.1's [script] line -------------------------------------------------------------------------
I18N_OUT=$REPO/build/emu-i18n/all

i18n_all() {
    local rc began
    began=$(date +%s)
    # shellcheck disable=SC2046 # the serials are words
    "$EMU/i18n/all.sh" --apk "$DEBUG_APK" $(serials_of 24 28 37) > "$LOGS/all.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/all"
    cp -r "$I18N_OUT/." "$LOGS/all/" 2> /dev/null
    grep -E '^(PASS|FAIL) [a-z0-9]+\.sh|^SUMMARY' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^   (FAIL|KNOWN) ' "$LOGS/all.log" | sed 's/^/  /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- skeleton --------------------------------------------------------------------------------------------
skeleton_debug() { "$EMU/checks/3A.sh" skeleton debug "$1" --apk "$DEBUG_APK"; }
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1" --apk "$STAGING_APK"; }

# ---- extras: the scripts the merge of R4 changed ---------------------------------------------------------
walk_debug() { "$EMU/shell/walk.sh" "$1" --apk "$DEBUG_APK"; }
walk_staging() { "$EMU/shell/walk.sh" "$1" --apk "$STAGING_APK"; }
smoke_debug() { "$EMU/smoke.sh" debug "$1" --apk "$DEBUG_APK"; }
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }
clip_3b() { "$EMU/checks/3B.sh" clip "$1" --apk "$DEBUG_APK"; }
clear_debug() { "$EMU/clipboard/clear.sh" "$1" --apk "$DEBUG_APK"; }

# sourced_twice SERIAL: app.sh sourced again after pva_init (as window/lib.sh does, and lock/lib.sh's
# pvl_window_lib) keeps the screen size pva_init read (2400 pixels high on API 37) and the message
# pva_settle kept (iface-137); in a subshell of its own, with the debug app on Welcome.
sourced_twice() {
    (
        PV_PASSED=0; PV_FAILED=0; PV_SKIPPED=0
        pv_use "$1"
        pva_init
        local h=$PVA_SCREEN_H w=$PVA_SCREEN_W
        PVA_WATCHED="Your unsaved changes were kept."
        # shellcheck source=../app.sh
        source "$EMU/app.sh"
        pv_step "app.sh sourced again: the screen size $w x $h is kept" -- eval '[ "$PVA_SCREEN_H" = "$h" ] && [ "$PVA_SCREEN_W" = "$w" ]'
        pv_step "app.sh sourced again: the watched message is kept" -- [ "$PVA_WATCHED" = "Your unsaved changes were kept." ]
        # shellcheck source=../window/lib.sh
        source "$EMU/window/lib.sh"
        pv_step "window/lib.sh sourced (it sources adb.sh and app.sh again): the screen size $w x $h is kept" -- eval '[ "$PVA_SCREEN_H" = "$h" ] && [ "$PVA_SCREEN_W" = "$w" ]'
        pv_step "a tap after the second sourcing reaches a node at the bottom of the screen: Welcome's Start" -- eval 'pva_install "$DEBUG_APK" && pva_start && pva_on Welcome 60 && pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword 20'
        pv_summary
    )
}

# airplane SERIAL: boot.sh put an API 37 device in airplane mode (iface-142) and left the others as the
# base has them.
airplane() {
    local s=$1 api mode net
    api=$(PV_SERIAL=$s pv_api)
    mode=$(PV_SERIAL=$s pv_shell settings get global airplane_mode_on 2> /dev/null | tr -d '\r')
    net=$(PV_SERIAL=$s pv_shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -m 1 'Active default network' | sed 's/^ *//')
    echo "   API $api ($s): airplane_mode_on=$mode; ${net:-no line about the default network}"
    if [ "$api" = 37 ]; then
        [ "$mode" = 1 ] && [ "$net" = "Active default network: none" ]
    else
        [ "$mode" = 0 ]
    fi
}

airplane_all() {
    local api rc=0
    for api in "${APIS[@]}"; do airplane "${SER[$api]}" || rc=1; done
    return $rc
}

extras_clip() { clip_3b "$1"; }

# ---- one device: the checks of waves 5 and 7 ------------------------------------------------------------
# The checks below run on one device (dev_run), each as numbered steps that print PASS or FAIL, with the
# debug build: the lines of build-plan.md section 7's integration check (line1 to line7; line 8 is
# smoke.sh's stage of wave 5, which the part wave5 runs with the staging build), U 12 check 15 (check15),
# the owner's decision 21 (d21), and wave 7's import of the fifty accounts (fifty) and 7.3's walk
# (walk73). They drive the app's screens by resource id (app.sh), the system's picker by its texts
# (app.sh, pva_picker_*), read the lock's state through the debug command status (pva_status, and
# lock/lib.sh's pvl_* for the records), and the app's files with run-as. Each starts from a fresh install.
PIN=582913
T=""
DEV_APK=""
DEV_VARIANT=""
DEV_OUT=""
API=0

back_to_list() { pv_shell input keyevent KEYCODE_BACK >/dev/null; pva_on Passwords 20; }

# rows_seen TEXT...: each TEXT is a row of the list, scrolled to when it is below the fold; the list is
# scrolled back to its top afterwards.
rows_seen() {
    local text i found rc=0 down
    for text in "$@"; do
        found=0
        down=0
        for i in 1 2 3 4 5 6; do
            if pva_row_titles | grep -qF -- "$text"; then found=1; break; fi
            pva_scroll_down
            down=$((down + 1))
        done
        [ $found = 1 ] || { pv_log "no row with '$text'; rows: $(pva_row_titles | tr '\n' ';')"; rc=1; }
        for ((i = 0; i < down; i++)); do pva_scroll_up; done
    done
    return $rc
}

# add_two: two accounts through the form (adb types ASCII only).
add_two() {
    pva_add_account Gmail aziz.karimov@gmail.com Tr0ub4dor3x https://mail.google.com "Shaxsiy pochta" && back_to_list \
        && pva_add_account Payme +998901234567 Samarqand2024x https://payme.uz Karta && back_to_list
}

# add_old_two: the same two accounts imported from a JSON file of this app's layout (formats.md 5.2)
# whose dates are three days old: imported accounts keep their dates (build-plan 12, decision 7), so the
# list's backup banner is due at once (no backup, the first account added a day or more ago; ux.md 5.11)
# instead of a day after setup. Settings stays shown.
OLD_JSON=pv-5-old-accounts.json
add_old_two() {
    python3 - "$DEV_OUT/$OLD_JSON" <<'PYEOF' || return 1
import datetime, json, sys, uuid
when = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=3)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
def item(name, url, user, password, notes):
    return {"id": str(uuid.uuid4()), "organizationId": None, "folderId": None, "type": 1, "reprompt": 0,
            "name": name, "notes": notes, "favorite": False, "fields": [],
            "login": {"fido2Credentials": [], "uris": [{"match": None, "uri": url}], "username": user, "password": password, "totp": None},
            "passwordHistory": None, "creationDate": when, "revisionDate": when, "deletedDate": None, "collectionIds": None,
            "passwordVault": {"passwordChangedAt": when, "changePending": False, "checkIgnored": False, "pendingOldPassword": None, "urls": [url]}}
data = {"encrypted": False,
        "passwordVault": {"format": "passwordvault-export", "version": 1, "exportedAt": when, "generator": "PasswordVault 1.0"},
        "folders": [],
        "items": [item("Gmail", "https://mail.google.com", "aziz.karimov@gmail.com", "Tr0ub4dor3x", "Shaxsiy pochta"),
                  item("Payme", "https://payme.uz", "+998901234567", "Samarqand2024x", "Karta")]}
open(sys.argv[1], "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PYEOF
    pva_push_download "$DEV_OUT/$OLD_JSON" && w5_import json "$OLD_JSON" pushed && pva_tap pv.tab.Passwords && pva_on Passwords 20
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

# fresh mp|pin|pin+fp: a new install and setup: the PIN left off (mp), or a PIN with fingerprint unlock
# not turned on (pin) or turned on with finger 1 (pin+fp); the list.
fresh() {
    pva_install "$DEV_APK" && pva_start || return 1
    case "$1" in
        mp) pva_set_up ;;
        pin) set_up_to_pin && pva_choose_pin "$PIN" && pva_after_pin notNow ;;
        pin+fp) set_up_to_pin && pva_choose_pin "$PIN" && pva_after_pin use ;;
    esac
}

# settings: the Settings tab, from any screen of the open app (Back until the tab bar shows, so that a
# step that failed half-way does not leave the next one on a screen without it).
settings() {
    local i
    for i in 1 2 3 4 5; do
        pva_present pv.tab.Settings 2 && break
        pva_in_front || { pva_relaunch; continue; }
        pv_shell input keyevent KEYCODE_BACK >/dev/null
        pv_sleep 1
    done
    pva_tap pv.tab.Settings && pva_on Settings 20
}

# run_as COMMAND: a shell command in the app's own directory (debug builds).
run_as() { pv_shell run-as "$PV_PACKAGE" sh -c "'$1'" 2>/dev/null | tr -d '\r'; }

prefs_value() { run_as 'cat shared_prefs/pv_settings.xml' | sed -n "s/.*name=\"$1\"[^>]*value=\"\\([^\"]*\\)\".*/\\1/p; s/.*<string name=\"$1\">\\([^<]*\\)<.*/\\1/p" | head -n 1; }

clean_download() {
    pv_shell "rm -f $PVA_DOWNLOAD/PasswordVault-backup-* $PVA_DOWNLOAD/passwords-* $PVA_DOWNLOAD/pv-5-*" >/dev/null 2>&1
    [ "$API" -lt 30 ] || pv_shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
    return 0
}

# download_names: the names in Download now, one per line.
download_names() { pv_shell ls "$PVA_DOWNLOAD" 2>/dev/null | tr -d '\r' | sort; }

# in_download_exact NAME: the file NAME is in Download (compared as a whole, not as a pattern).
in_download_exact() { download_names | grep -Fxq -- "$1"; }

# new_in_download BEFORE REGEX: a name matching REGEX that was not in BEFORE (download_names' output).
new_in_download() { comm -13 <(printf '%s\n' "$1") <(download_names) | grep -E -- "$2" | head -n 1; }

# pull NAME: the file NAME from Download into DEV_OUT; prints the local path.
pull() {
    mkdir -p "$DEV_OUT"
    pv_adb pull "$PVA_DOWNLOAD/$1" "$DEV_OUT/$1" >/dev/null 2>&1 && [ -s "$DEV_OUT/$1" ] || { pv_log "could not pull $1"; return 1; }
    echo "$DEV_OUT/$1"
}

# set_lock_delay DELAY: Settings > "Lock the app after leaving it", the choice DELAY (LockDelay).
set_lock_delay() {
    settings && pva_reveal pv.settings.lockDelay && pva_tap pv.settings.lockDelay || return 1
    pva_need "pv.settings.lockDelay.$1" 10 && pva_tap "pv.settings.lockDelay.$1" || return 1
    pv_sleep 1
    if pva_present pv.settings.dialog.done 2; then pva_tap pv.settings.dialog.done; fi
    pva_on Settings 10
}

# w5_backup [asked]: Settings > Backup: its master password (unless a flow handed over its proof), the
# picker's Save into Download, "Backup saved"; back on Settings. With "asked" the screen is open already.
w5_backup() {
    if [ "${1:-}" != asked ]; then
        settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    fi
    if pva_present pv.backup.continue 3; then pva_tap pv.backup.continue || return 1; fi
    if pva_present pv.backup.password 5; then
        pva_type pv.backup.password "$PVA_PASSWORD" || return 1
        pva_hide_keyboard
        pva_tap pv.backup.submit || return 1
    fi
    pva_picker_save || return 1
    pva_need pv.backup.done 60 || return 1
    pv_log "the backup's last screen: $(pva_text pv.backup.line.0 2>/dev/null) $(pva_text pv.backup.line.1 2>/dev/null)"
    pva_tap pv.backup.done
}

# w5_export csv|json|xlsx: Settings > Export, the format, the warning's "I understand", the master
# password, the picker's Save into Download: the saved screen, Done.
w5_export() {
    settings && pva_reveal pv.open.Export && pva_tap pv.open.Export && pva_on Export 20 || return 1
    pva_need "pv.export.choice.$1" 15 && pva_tap "pv.export.choice.$1" && pva_tap pv.export.continue || return 1
    if pva_present pv.export.understand 10; then pva_tap pv.export.understand || return 1; fi
    if pva_present pv.export.warning.continue 5; then pva_tap pv.export.warning.continue || return 1; fi
    pva_need pv.export.password 20 && pva_type pv.export.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.export.submit || return 1
    pva_picker_save || return 1
    pva_need pv.export.saved 60 || return 1
    pv_log "the export's last screen: $(pva_text pv.export.saved 2>/dev/null)"
    pva_tap pv.export.done && pva_on Settings 20
}

# w5_import SOURCE NAME [pushed]: Settings > Import, the source (backup, google, csv, json), the file NAME
# in the picker (pushed with adb, or saved there by the picker); the preview: Import; done; the file
# deleted when the screen offers it; Finish: Settings.
w5_import() {
    local deadline
    settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20 || return 1
    pva_need "pv.import.source.$1" 20 && pva_tap "pv.import.source.$1" || return 1
    deadline=$((SECONDS + 20))
    until pva_picker_in_front; do
        [ $SECONDS -lt $deadline ] || { pv_log "no picker after the source; ids: $(pva_ids)"; return 1; }
        pva_present pv.import.chooseFile 1 && pva_tap pv.import.chooseFile
        pv_sleep 1
    done
    pva_picker_open "$2" "${3:-}" || return 1
    pva_need pv.import.import 60 || { pv_log "no Import button; ids: $(pva_ids)"; return 1; }
    pv_log "the preview: $(pva_text pv.import.found 2>/dev/null) / $(pva_text pv.import.encoding 2>/dev/null) / $(pva_text pv.import.leftOut 2>/dev/null)"
    pva_tap pv.import.import && pva_need pv.import.done 60 || return 1
    pv_log "the import: $(pva_text pv.import.done 2>/dev/null)"
    if pva_present pv.import.deleteFile 5; then pva_tap pv.import.deleteFile && pva_need pv.import.fileOutcome 20 || return 1; fi
    pva_tap pv.import.finish && pva_on Settings 20
}

# export_holds FILE WORD...: the export FILE (CSV, JSON or an Excel workbook) opens as its format and
# holds each WORD.
export_holds() {
    python3 - "$@" <<'PYEOF'
import csv, io, json, sys, zipfile
path, want = sys.argv[1], sys.argv[2:]
if path.endswith(".csv"):
    rows = list(csv.reader(io.open(path, encoding="utf-8", newline="")))
    text, kind = "\n".join(",".join(r) for r in rows), f"CSV, header {rows[0][:5]}, {len(rows) - 1} rows"
elif path.endswith(".json"):
    text, kind = json.dumps(json.load(open(path, encoding="utf-8")), ensure_ascii=False), "JSON"
else:
    z = zipfile.ZipFile(path)
    text = "".join(z.read(n).decode("utf-8", "replace") for n in z.namelist() if n.endswith(".xml"))
    kind = f"Excel workbook ({len(z.namelist())} parts)"
missing = [w for w in want if w not in text]
print(f"    | {path.rsplit('/', 1)[1]}: {kind}; " + ("holds " + ", ".join(want) if not missing else f"lacks {missing}"))
sys.exit(1 if missing else 0)
PYEOF
}

# same_accounts A.json B.json: two JSON exports hold the same accounts: title, login, password, websites,
# notes and extra fields (what a CSV keeps, formats.md 4.5), compared as sets; prints the differing titles.
same_accounts() {
    python3 - "$1" "$2" <<'PYEOF'
import json, sys
def accounts(path):
    out = {}
    for it in json.load(open(path, encoding="utf-8"))["items"]:
        login = it.get("login") or {}
        urls = (it.get("passwordVault") or {}).get("urls") or [u.get("uri") for u in (login.get("uris") or [])]
        fields = tuple((f.get("name"), f.get("value"), f.get("type")) for f in (it.get("fields") or []))
        out.setdefault(it.get("name"), []).append((login.get("username"), login.get("password"), tuple(urls), it.get("notes"), fields))
    return out
a, b = accounts(sys.argv[1]), accounts(sys.argv[2])
bad = [t for t in sorted(set(a) | set(b), key=str) if sorted(a.get(t, []), key=repr) != sorted(b.get(t, []), key=repr)]
print(f"    | {sys.argv[1].rsplit('/', 1)[1]}: {sum(map(len, a.values()))} accounts; {sys.argv[2].rsplit('/', 1)[1]}: {sum(map(len, b.values()))}; "
      + ("the same" if not bad else f"they differ in: {bad}"))
sys.exit(1 if bad else 0)
PYEOF
}

# ---- line 1: a backup into Download; uninstall; install; restore from Welcome; a PIN ----
restore_from_welcome() {
    pva_on Welcome 60 && pva_tap pv.open.Restore && pva_on Restore 20 || return 1
    pva_need pv.backup.choose 20 && pva_tap pv.backup.choose || return 1
    pva_picker_open "$1" || return 1
    pva_need pv.backup.password 30 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.restore 60 || return 1
    pv_log "the restore's summary: $(pva_text pv.backup.summary 2>/dev/null)"
}

line1() {
    T="line 1"
    local backup
    clean_download
    pv_step "$T: a fresh install, setup (the PIN left off), two accounts" -- eval 'fresh mp && add_two' || return 1
    pv_step "$T: a backup into Download through the system's picker" -- w5_backup
    backup=$(pva_in_download '^PasswordVault-backup-.*\.pvault$' | tail -n 1)
    pv_step "$T: the backup is in Download: ${backup:-none}" -- [ -n "$backup" ] || return 1
    pv_step "$T: adb uninstall; the backup stays in Download" -- eval 'pv_adb uninstall "$PV_PACKAGE" | grep -q "^Success" && in_download_exact "$backup"'
    pv_step "$T: installed again: Welcome" -- eval 'pva_install "$DEV_APK" && pva_start && pva_on Welcome 60' || return 1
    pv_step "$T: Welcome's \"Restore from a backup file\": the file in the picker, its master password, the summary" -- restore_from_welcome "$backup" || return 1
    pv_step "$T: Restore: the app opens and \"Choose a PIN\" follows" -- eval 'pva_tap pv.backup.restore && pva_unlocked Passwords 120 && pva_to_create_pin'
    pv_step "$T: a PIN can be set: chosen and typed again; the fingerprint offer \"Not now\", \"Make backups\" Finish" -- eval 'pva_choose_pin "$PIN" && pva_after_pin notNow'
    pv_step "$T: the accounts are back: Gmail and Payme" -- eval 'pva_row_with Gmail 15 && pva_row_with Payme 10'
    pv_step "$T: Lock now shows the pad; the PIN opens the app" -- eval 'pva_lock_now && pva_need pv.lock.pad 20 && pva_pin "$PIN" && pva_opened 30 && pva_settle Passwords 60'
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- line 2: "Immediately": backup, each export and an import finish; the picker; a kill in it ----
PICK_CSV=pv-5-small.csv

small_csv() {
    printf 'name,url,username,password,note\r\nTashkent Metro,https://metro.uz,dilshod.m,Qx7mVb2Lp9Ws,yo%sl kartasi\r\nKapitalbank,https://kapitalbank.uz,+998901112233,Hn4Rt8Ws2Kz6,\r\n' 'ʻ' > "$DEV_OUT/$PICK_CSV"
    pva_push_download "$DEV_OUT/$PICK_CSV"
}

# kill_app: the app's process ends while it is not in front (the picker is): am kill, or kill -9 through
# run-as (debug) or su (API 24 and 28) where am kill is refused.
kill_app() {
    local pid
    pid=$(pva_pid)
    [ -n "$pid" ] || { pv_log "no process"; return 1; }
    pv_shell am kill "$PV_PACKAGE" >/dev/null 2>&1
    pv_sleep 2
    [ -z "$(pva_pid)" ] || pv_shell run-as "$PV_PACKAGE" kill -9 "$(pva_pid)" >/dev/null 2>&1
    pv_sleep 1
    [ -z "$(pva_pid)" ] || ! pv_has_root || pv_shell su 0 kill -9 "$(pva_pid)" >/dev/null 2>&1
    pv_sleep 1
    [ -z "$(pva_pid)" ] || { pv_log "process $(pva_pid) is still there"; return 1; }
    pv_log "process $pid ended while the picker was in front"
}

# pvault_ok FILE: FILE is a whole backup: the magic PVAULT01 and the SHA-256 trailer over everything
# before it (architecture.md 4, reader rule R1).
pvault_ok() {
    python3 - "$1" <<'PYEOF'
import hashlib, sys
data = open(sys.argv[1], "rb").read()
ok = len(data) > 40 and data[:8] == b"PVAULT01" and hashlib.sha256(data[:-32]).digest() == data[-32:]
print(f"    | {sys.argv[1].rsplit('/', 1)[1]}: {len(data)} bytes, " + ("PVAULT01 with a good trailer" if ok else "not a whole backup"))
sys.exit(0 if ok else 1)
PYEOF
}

screen_texts() { pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-400; }

# kill_in_picker cancel|complete: U 12 check 13 (ux.md 4, "Unfinished file tasks", item 4). Settings >
# Backup, its master password; with the picker in front the app's process is killed; SAVE: the picker
# made its (empty) file and hands the location to a new process, which is locked; the PIN: the backup's
# screen at its step with "This was paused when the app locked." and the master password asked again
# (the sealed backup went with the process; ux.md 4 item 2). Then either Cancel: "No backup was made.
# The empty file was removed." and the file is gone; or the master password: the backup completes into
# that same file, with no second picker, and the file is a whole backup.
kill_in_picker() {
    local before name size
    before=$(download_names)
    settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    if pva_present pv.backup.continue 3; then pva_tap pv.backup.continue || return 1; fi
    pva_need pv.backup.password 20 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_picker_wait 30 || return 1
    pv_sleep 2
    kill_app || return 1
    pva_picker_save || return 1
    pva_on Locked 60 && pva_need pv.lock.pad 30 && pva_pin "$PIN" || return 1
    pva_on Backup 60 && pva_need pv.backup.paused 20 && pva_need pv.backup.password 10 \
        || { pv_log "the backup's step did not come back after the unlock: $(screen_texts)"; return 1; }
    pv_log "after the unlock: $(screen_texts)"
    name=$(new_in_download "$before" '^PasswordVault-backup-.*\.pvault$')
    size=$(pv_shell stat -c %s "'$PVA_DOWNLOAD/$name'" 2>/dev/null | tr -d '\r')
    pv_log "the picker's file: ${name:-none}, ${size:-?} bytes"
    [ -n "$name" ] && [ "$size" = 0 ] || { pv_log "no empty file of the picker's in Download"; return 1; }
    if [ "$1" = cancel ]; then
        pva_hide_keyboard
        pva_tap pv.backup.cancel || return 1
        pv_ui_wait '^No backup was made\. The empty file was removed\.' 20 || { pv_log "no \"No backup was made. The empty file was removed.\": $(screen_texts)"; return 1; }
        pv_sleep 2
        ! in_download_exact "$name" || { pv_log "the empty file $name is still in Download"; return 1; }
        pva_tap pv.backup.done && pva_on Settings 20
    else
        pva_type pv.backup.password "$PVA_PASSWORD" || return 1
        pva_hide_keyboard
        pva_tap pv.backup.submit || return 1
        pva_need pv.backup.done 60 || { pv_log "the backup did not complete: $(screen_texts); focus $(pv_focus)"; return 1; }
        pv_log "the backup completed into the picker's file: $(pva_text pv.backup.line.0 2>/dev/null)"
        pvault_ok "$(pull "$name")" || return 1
        pva_tap pv.backup.done
    fi
}

# picker_stays SECONDS: Settings > Import, CSV: the picker; SECONDS in it; Back: the app is open, not
# locked (E17's item, which lock/E17.sh runs on API 37 only).
picker_stays() {
    local deadline
    settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20 || return 1
    pva_tap pv.import.source.csv && pva_picker_wait 20 || return 1
    pv_sleep "$1"
    pva_picker_in_front || { pv_log "the picker left the front: $(pv_focus)"; return 1; }
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    deadline=$((SECONDS + 15))
    until pva_in_front; do
        [ $SECONDS -lt $deadline ] || { pv_log "the app is not in front after Back: $(pv_focus)"; return 1; }
        pv_shell input keyevent KEYCODE_BACK >/dev/null
        pv_sleep 1
    done
    pva_status_has '^state: unlocked' && pva_absent pv.route.Locked
}

# picker_screen_off: Settings > Import, CSV: the picker; the screen off for 3 s and on: the app locked
# under the picker; the file picked; the PIN: the import goes on with that file (E17's item).
picker_screen_off() {
    settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20 || return 1
    pva_tap pv.import.source.csv && pva_picker_wait 20 || return 1
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null
    pv_sleep 3
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    pv_sleep 1
    pv_unlock || return 1
    pva_status_soon '^state: locked' 10 || return 1
    pva_picker_in_front || { pv_log "the picker is not in front after the screen came back: $(pv_focus)"; return 1; }
    pva_picker_open "$PICK_CSV" pushed || return 1
    pva_on Locked 30 && pva_need pv.lock.pad 20 && pva_pin "$PIN" || return 1
    pv_ui_wait '[|]pv\.import\.(fileName|preview|found|reading|paused|import|columns)$' 30 \
        || { pv_log "the import did not go on after the unlock; ids: $(pva_ids)"; return 1; }
    # Leave the import without importing: Back to Settings, then the list.
    local i
    for i in 1 2 3 4; do pva_present pv.route.Settings 2 && break; pv_shell input keyevent KEYCODE_BACK >/dev/null; pv_sleep 1; done
    pva_on Settings 10
}

# check13: U 12 check 13 alone (line 2 has it too): a fresh install with a PIN, then both ways out.
check13() {
    T="check 13"
    pv_step "$T: a fresh install, setup with a PIN, two accounts" -- eval 'fresh pin && add_two' || return 1
    pv_step "$T: a kill while the picker is open; after the unlock its step comes back paused; Cancel: \"No backup was made. The empty file was removed.\"" -- kill_in_picker cancel
    pv_step "$T: a kill while the picker is open again; after the unlock the master password: the backup completes into the picker's file" -- kill_in_picker complete
    pv_step "$T: no crash" -- pva_no_crash
}

line2() {
    T="line 2"
    local before
    clean_download
    pv_step "$T: a fresh install, setup with a PIN, two accounts" -- eval 'fresh pin && add_two' || return 1
    pv_step "$T: Settings: lock the app after leaving it: Immediately" -- set_lock_delay IMMEDIATELY || return 1
    pv_step "$T: with Immediately a backup finishes (U 12 check 14)" -- eval 'w5_backup && [ -n "$(pva_in_download "^PasswordVault-backup-.*\.pvault$")" ]'
    pv_step "$T: with Immediately the CSV export finishes" -- eval 'w5_export csv && [ -n "$(pva_in_download "^passwords-.*\.csv$")" ]'
    pv_step "$T: with Immediately the JSON export finishes" -- eval 'w5_export json && [ -n "$(pva_in_download "^passwords-.*\.json$")" ]'
    pv_step "$T: with Immediately the Excel export finishes" -- eval 'w5_export xlsx && [ -n "$(pva_in_download "^passwords-.*\.xlsx$")" ]'
    pv_step "$T: a small CSV pushed to Download" -- small_csv
    pv_step "$T: with Immediately an import of it finishes: Tashkent Metro and Kapitalbank in the list" -- eval 'w5_import csv "$PICK_CSV" pushed && pva_tap pv.tab.Passwords && pva_on Passwords 20 && rows_seen "Tashkent Metro" Kapitalbank'
    if [ "$API" -lt 30 ]; then
        # lock/E17.sh runs these on API 37 (the part lock); lock.md lists E17 for Android 17 only, and
        # build-plan 7 line 2 asks for them on API 24 too.
        pv_step "$T: a small CSV pushed again" -- small_csv
        pv_step "$T: 90 s in the file picker with Immediately: still open (E17's item)" -- picker_stays 90
        pv_step "$T: the picker and the screen off: locked; the file picked; after the PIN the import goes on (E17's item)" -- picker_screen_off
    fi
    pv_step "$T: a kill while the picker is open (U 12 check 13); after the unlock its step comes back paused; Cancel: \"No backup was made. The empty file was removed.\"" -- kill_in_picker cancel
    pv_step "$T: a kill while the picker is open again; after the unlock the master password: the backup completes into the picker's file" -- kill_in_picker complete
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- line 3: CSV, JSON and Excel of a vault with Uzbek, Cyrillic, +998, "=" and a multi-line note ----
SPECIAL=pv-5-special.csv

special_csv() {
    python3 - "$DEV_OUT/$SPECIAL" <<'PYEOF'
import sys
rows = [
    ["name", "url", "username", "password", "note"],
    ["O\u02bbzbekiston pochtasi", "https://pochta.uz", "aziz.o\u02bbg\u02bbli", "G\u02bbalaba-2024!", "Birinchi qator\nIkkinchi qator: o\u02bb g\u02bb sh\u02bc"],
    ["Почта Ташкент", "https://mail.uz", "азиз", "Парол-Ёз-2026", "Изоҳ ўзбекча: қўл"],
    ["Payme", "https://payme.uz", "+998901234567", "=SUM(1+1)", "=HYPERLINK(\"http://x.uz\")"],
    ["Click", "https://click.uz", "+998 90 111 22 33", "-Minus1+", "+plus note\nsecond line"],
]
def q(v):
    return '"' + v.replace('"', '""') + '"' if any(c in v for c in ',"\r\n') or v[:1] in " \t" or v[-1:] in " \t" else v
with open(sys.argv[1], "w", encoding="utf-8", newline="") as f:
    for r in rows:
        f.write(",".join(q(v) for v in r) + "\r\n")
PYEOF
    pva_push_download "$DEV_OUT/$SPECIAL"
}

# empty_vault: pm clear (the app's data gone, the install kept), started again: Welcome; setup with the
# PIN left off.
empty_vault() { pv_shell pm clear "$PV_PACKAGE" | grep -q Success && pva_start && pva_set_up; }

# export_new FORMAT: an export in FORMAT whose file is new in Download; prints its name.
export_new() {
    local before name
    before=$(download_names)
    w5_export "$1" >&2 || return 1
    name=$(new_in_download "$before" "^passwords-.*\\.$1\$")
    [ -n "$name" ] || { pv_log "no new .$1 file in Download"; return 1; }
    echo "$name"
}

line3() {
    T="line 3"
    local csv json xlsx dir json2 json3
    dir=$DEV_OUT/line3-export
    rm -rf "$dir" && mkdir -p "$dir"
    clean_download
    pv_step "$T: a fresh install, setup (the PIN left off)" -- fresh mp || return 1
    pv_step "$T: four accounts with Uzbek (oʻ, gʻ), Cyrillic, a +998 number, values that start with = + -, multi-line notes: a CSV imported" -- eval 'special_csv && w5_import csv "$SPECIAL" pushed && pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_row_with Payme 15 && pva_row_with Click 10' || return 1
    pv_step "$T: CSV exported" -- eval 'csv=$(export_new csv) && pull "$csv" > /dev/null && cp "$DEV_OUT/$csv" "$dir/"' || return 1
    pv_step "$T: JSON exported" -- eval 'json=$(export_new json) && pull "$json" > /dev/null && cp "$DEV_OUT/$json" "$dir/"' || return 1
    pv_step "$T: Excel exported" -- eval 'xlsx=$(export_new xlsx) && pull "$xlsx" > /dev/null && cp "$DEV_OUT/$xlsx" "$dir/"' || return 1
    ls -l "$dir" | sed 's/^/    | /'
    pv_step "$T: tools/validate/run.sh on the three files" -- eval '"$REPO/tools/validate/run.sh" "$dir" > "$DEV_OUT/validate.log" 2>&1; rc=$?; grep -E "^(FAIL|RESULT)" "$DEV_OUT/validate.log" | sed "s/^/    | /"; [ $rc = 0 ]'
    pv_step "$T: the exports hold the values" -- eval 'export_holds "$dir/$json" "Oʻzbekiston pochtasi" "Почта Ташкент" "+998901234567" "=SUM(1+1)" "Ikkinchi qator" && export_holds "$dir/$csv" "Парол-Ёз-2026" "=HYPERLINK" && export_holds "$dir/$xlsx" "азиз" "Gʻalaba-2024!"'
    # The exports leave Download (Android 7's picker saves over a file of the same name), and the two to
    # import come back under names of their own.
    clean_download
    pv_step "$T: an empty vault (pm clear, setup); the CSV export imported" -- eval 'empty_vault && pva_push_download "$dir/$csv" pv-5-export.csv && w5_import csv pv-5-export.csv pushed' || return 1
    pv_step "$T: exported again as JSON: the same accounts as the first JSON (title, login, password, websites, notes, fields)" -- eval 'json2=$(export_new json) && pull "$json2" > /dev/null && cp "$DEV_OUT/$json2" "$dir/after-the-csv-import.json" && same_accounts "$dir/$json" "$dir/after-the-csv-import.json"'
    clean_download
    pv_step "$T: an empty vault again; the JSON export imported" -- eval 'empty_vault && pva_push_download "$dir/$json" pv-5-export.json && w5_import json pv-5-export.json pushed' || return 1
    pv_step "$T: exported again as JSON: the same accounts as the first JSON" -- eval 'json3=$(export_new json) && pull "$json3" > /dev/null && cp "$DEV_OUT/$json3" "$dir/after-the-json-import.json" && same_accounts "$dir/$json" "$dir/after-the-json-import.json"'
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- line 4: the Chrome fixture and the Uzbek Cyrillic header file of FR P6 ----
CHROME=pv-5-chrome.csv
UZCYRL=pv-5-uzcyrl.csv

# imported_as_in FIXTURE JSON: every row of the CSV FIXTURE that has a password is an account of the JSON
# export with that password, login and notes (the fixture's own header names, Uzbek Cyrillic or Chrome's).
imported_as_in() {
    python3 - "$1" "$2" <<'PYEOF'
import csv, json, sys
rows = list(csv.reader(open(sys.argv[1], encoding="utf-8", newline="")))
head, rows = rows[0], rows[1:]
items = json.load(open(sys.argv[2], encoding="utf-8"))["items"]
have = {((it.get("login") or {}).get("username"), (it.get("login") or {}).get("password")): it for it in items}
bad = []
for r in rows:
    user, password, note = r[2], r[3], r[4] if len(r) > 4 else ""
    if not password:
        continue
    it = have.get((user or None, password))
    if it is None:
        bad.append(f"{r[0]}: no account with its login and password")
    elif (it.get("notes") or "") .split("\n\n--- PasswordVault")[0] != note:
        bad.append(f"{r[0]}: the notes differ")
print(f"    | {sys.argv[1].rsplit('/', 1)[1]} (header {head}): {len(rows)} rows; " + ("each with a password is an account with it" if not bad else "; ".join(bad)))
sys.exit(1 if bad else 0)
PYEOF
}

line4() {
    T="line 4"
    local json
    clean_download
    pv_step "$T: a fresh install, setup (the PIN left off)" -- fresh mp || return 1
    cp "$REPO/core/src/test/resources/formats/proton/chrome.csv" "$DEV_OUT/$CHROME"
    cp "$REPO/core/src/test/resources/formats/uzbek-cyrillic-headers.csv" "$DEV_OUT/$UZCYRL"
    pv_step "$T: the Chrome fixture (core/src/test/resources/formats/proton/chrome.csv) imported as \"Google Password Manager or Chrome\"" -- eval 'pva_push_download "$DEV_OUT/$CHROME" && w5_import google "$CHROME" pushed'
    pv_step "$T: the Uzbek Cyrillic header file of FR P6 imported as CSV, with no column mapping asked" -- eval 'pva_push_download "$DEV_OUT/$UZCYRL" && w5_import csv "$UZCYRL" pushed'
    pv_step "$T: the list has proton.me's and the Uzbek file's accounts" -- eval 'pva_tap pv.tab.Passwords && pva_on Passwords 20 && rows_seen proton.me Почта Банк "Davlat xizmatlari"'
    pv_step "$T: a JSON export: every row with a password came in with its login, password and notes" -- eval 'json=$(export_new json) && pull "$json" > /dev/null && imported_as_in "$DEV_OUT/$UZCYRL" "$DEV_OUT/$json" && imported_as_in "$DEV_OUT/$CHROME" "$DEV_OUT/$json"'
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- line 5: change the master password with a PIN and a fingerprint set ----
NEW_MP=""

# choose_words: on the choice of a new master password (setup's own screens), the suggested words, written
# down, typed again, Next; NEW_MP holds them.
choose_words() {
    pva_need pv.setup.passphrase 30 && pva_reveal pv.setup.passphrase || return 1
    NEW_MP=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$NEW_MP" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' || { pv_log "not six words: '$NEW_MP'"; return 1; }
    pva_tap pv.open.WriteDown && pva_need pv.setup.wrote 20 && pva_reveal pv.open.TypeAgain && pva_tap pv.open.TypeAgain || return 1
    pva_type pv.setup.again "$NEW_MP" || return 1
    pva_hide_keyboard
    pva_tap pv.action.createVault
}

# change_to_pin_step: Settings > Change master password: the current one, Next, the new one chosen; the
# step that keeps the PIN.
change_to_pin_step() {
    settings && pva_reveal pv.open.ChangeMasterPassword && pva_tap pv.open.ChangeMasterPassword && pva_on ChangeMasterPassword 20 || return 1
    pva_need pv.master.current 20 && pva_type pv.master.current "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.master.current.next || return 1
    choose_words || return 1
    pva_need pv.pin.key.0 60
}

# mp_opens PASSWORD: Lock now; to the master-password field (past the prompt and the pad); PASSWORD
# opens the app.
mp_opens() {
    local i
    pva_lock_now || return 1
    for i in 1 2 3 4 5 6 7 8; do
        pva_present pv.lock.password 2 && break
        if pva_status | grep -q '^prompt: UNLOCK'; then pva_prompt_use_pin; fi
        if pva_present pv.lock.forgotPin 1; then pva_tap pv.lock.forgotPin; fi
        pv_sleep 1
    done
    pva_need pv.lock.password 10 && pva_type pv.lock.password "$1" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && pva_settle Passwords 60
}

finger_opens() { pva_lock_now && pva_prompt_open UNLOCK 20 && pva_finger 1 && pva_opened 20 && pva_settle Passwords 60; }

# old_mp_refused OLD NEW: Lock now; on the master-password field OLD shows "This is an older master
# password" (lock.md F16b) and opens nothing; its "Try again" (or OK) leads back to the field, where NEW
# opens the vault.
old_mp_refused() {
    local i
    pva_lock_now || return 1
    for i in 1 2 3 4 5 6 7 8; do
        pva_present pv.lock.password 2 && break
        if pva_status | grep -q '^prompt: UNLOCK'; then pva_prompt_use_pin; fi
        if pva_present pv.lock.forgotPin 1; then pva_tap pv.lock.forgotPin; fi
        pv_sleep 1
    done
    pva_need pv.lock.password 10 && pva_type pv.lock.password "$1" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_need pv.lock.older 90 || return 1
    pv_log "the older password's screen: $(screen_texts)"
    pva_status_has '^state: locked' || return 1
    if pva_present pv.lock.olderTryAgain 3; then pva_tap pv.lock.olderTryAgain; else pva_tap pv.lock.olderOk; fi || return 1
    pva_need pv.lock.password 20 && pva_type pv.lock.password "$2" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && pva_settle Passwords 60
}

pin_opens() { pva_lock_now && { ! pva_status | grep -q '^prompt: UNLOCK' || pva_prompt_use_pin; } && pva_need pv.lock.pad 20 && pva_pin "$PIN" && pva_opened 30 && pva_settle Passwords 60; }

line5() {
    T="line 5"
    local old
    pv_step "$T: a fresh install, setup with a PIN and fingerprint unlock (finger 1), an account" -- eval 'fresh pin+fp && pva_add_account Gmail aziz.karimov@gmail.com Tr0ub4dor3x https://mail.google.com note && back_to_list' || return 1
    pv_step "$T: status: a PIN slot and a fingerprint slot" -- eval 'pva_status_has "^pin.rec: OK, slot set" && pva_status_has "^fingerprint.rec: OK, slot set"'
    old=$PVA_PASSWORD
    pv_step "$T: Settings > Change master password: the current one, the new one chosen; the PIN kept" -- eval 'change_to_pin_step && pva_pin "$PIN"' || return 1
    pv_step "$T: \"Touch the sensor to keep fingerprint unlock on.\": finger 1" -- eval 'pva_prompt_open KEEP_AFTER_CHANGE 60 && pva_finger 1'
    pv_step "$T: \"Your master password was changed.\" with the backup step; Later" -- eval 'pva_need pv.master.backUpNow 90 && pva_tap pv.master.later && pva_on Settings 30 && pva_tap pv.tab.Passwords && pva_on Passwords 20'
    PVA_PASSWORD=$NEW_MP
    pv_step "$T: status: the PIN and the fingerprint kept (both slots set)" -- eval 'pva_status_has "^pin.rec: OK, slot set" && pva_status_has "^fingerprint.rec: OK, slot set"'
    pv_step "$T: the fingerprint opens the vault" -- finger_opens
    pv_step "$T: the PIN opens the vault" -- pin_opens
    pv_step "$T: the new master password opens the vault" -- mp_opens "$NEW_MP"
    pv_step "$T: the old master password opens nothing: \"This is an older master password\"; the new one then opens" -- old_mp_refused "$old" "$NEW_MP"
    pv_step "$T: the account is still there" -- pva_row_with Gmail 10
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- lock/lib.sh in a one-device check: the records, the PIN steps, F11 (newmp.sh) ----
# lock_lib: sources tools/emu/lock/lib.sh and newmp.sh (functions pvl_* and pvn_*, which keep this
# script's variables) and gives them what pvl_begin would.
lock_lib() {
    [ -z "${LOCK_DIR:-}" ] || return 0
    # shellcheck source=../lock/lib.sh
    source "$EMU/lock/lib.sh"
    # shellcheck source=../lock/newmp.sh
    source "$EMU/lock/newmp.sh"
    SERIAL=$PV_SERIAL
    APK=$DEV_APK
    VARIANT=$DEV_VARIANT
    PVL_OUT=$DEV_OUT/lock
    mkdir -p "$PVL_OUT"
    PVL_RESOURCES=$PVL_OUT/resources.txt
    PVL_RESTORE=()
    PVL_UNDO=()
    PVL_PIN=$PIN
}

# ---- line 6: a new master password asked for (F11); the wait; no backup banner meanwhile ----
# On API 37 lock/E4.sh (a clock a year ahead and back) and E5.sh (boot_count) run in the part lock. On a
# level below 28, which has no `cmd alarm set-time`, the clock is set with su's date.
set_clock_24() {
    local target=$(( $(date +%s) + $1 )) now
    pv_shell su 0 date -u "$(date -u -d "@$target" +%m%d%H%M%Y.%S)" >/dev/null 2>&1
    pv_sleep 2
    now=$(pv_shell date +%s 2>/dev/null | tr -d '\r')
    [ -n "$now" ] && [ $((now - target)) -lt 30 ] && [ $((target - now)) -lt 30 ] || { pv_log "the phone's clock is $now, wanted $target"; return 1; }
}

# credit_bounded C0 SEEN0: the wait's credit grew by no more than the real time since SEEN0 (newmp.sh).
credit_bounded() { local out; out=$(pvn_credit_bound "$1" "$2"); local rc=$?; pv_log "$out"; return $rc; }

clock_and_boot_count() {
    local c0 seen0 i
    c0=$(pvn_credited_ms); seen0=$(pvn_seen_ms)
    [ -n "$c0" ] && [ -n "$seen0" ] || { pv_log "no pending_mp in state.rec"; return 1; }
    # The credit is observed whenever the app comes to the front (process-level ON_START, lock.md 6.9),
    # locked or not; the delay of 30 s is not reached by these visits.
    pvl_setting_set global auto_time 0 || return 1
    set_clock_24 $((366 * 86400)) || return 1
    pva_away 3 || { set_clock_24 0; return 1; }
    credit_bounded "$c0" "$seen0" || { set_clock_24 0; return 1; }
    set_clock_24 0 || return 1
    pva_away 3 || return 1
    credit_bounded "$c0" "$seen0" || return 1
    pvl_setting_set global boot_count 99999 || return 1
    for i in 1 2 3; do
        pv_shell settings put global boot_count 99999 >/dev/null || return 1
        pva_away 20 || return 1
        credit_bounded "$c0" "$seen0" || return 1
    done
    pvl_status || return 1
    case "$(pvl_lock_state)" in unlocked) pvl_lock_now || return 1 ;; esac
    pvl_to_master_password || return 1
    local line
    line=$(pva_text pv.lock.pending) || return 1
    pv_log "the lock screen: $line"
    pvl_expect "$line" plural lock_mp_pending_hours 24 2>/dev/null || pvl_expect "$line" plural lock_mp_pending_hours 23 || return 1
    pvl_to_pad 15 && pva_pin "$PIN" && pva_opened 30 && pva_settle Passwords 60
}

line6() {
    T="line 6"
    lock_lib
    pv_step "$T: a fresh install, setup with a PIN, two accounts imported with dates three days old" -- eval 'fresh pin && add_old_two' || return 1
    pv_step "$T: the list shows its backup banner (accounts and no backup)" -- pva_need pv.list.banner.backup 20
    pv_step "$T: a new master password asked for (F11: Settings > Change master password > \"I don't remember it\", the PIN, the words): pending" -- pvn_request || return 1
    pv_step "$T: while it is pending the list shows the pending banner and no backup banner (lock.md F11 step 10)" -- eval 'pva_on Passwords 20 && pva_need pv.list.banner.pending 20 && pva_absent pv.list.banner.backup'
    if [ "$API" -lt 28 ]; then
        pv_step "$T: the wait's credit unchanged by the clock a year ahead and back (su date) and by boot_count 99999 three times; the lock screen still says about 24 hours" -- clock_and_boot_count
        pv_shell su 0 date -u "$(date -u +%m%d%H%M%Y.%S)" >/dev/null 2>&1
        pvl_cleanup
    else
        echo "NOTE $T: the clock (E4) and boot_count (E5) on API 37 are the part lock's"
    fi
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- line 7: "Start with an empty app"; the old passwords from Welcome with their PIN, from Settings ----
# "Start with an empty app" is offered only while no PIN, fingerprint or practice can be used now (lock.md
# F17 step 1 item 4; LockViewModel.sheetUi): with a PIN that is so only while its record cannot be read
# right now (PinStatus.CannotCheck, lock.md F15c: nothing is removed), the state of a phone whose storage
# failed for a moment. The check makes the vault's pin.rec unreadable with run-as (chmod 000, debug
# builds) for a new process, starts with an empty app, then makes it readable again (its mode as it was)
# and starts a new process: the vault put aside opens from Welcome with its own PIN (lock.md invariant I9).
vault_files() { run_as 'cd files && find . -type f -name "*.pvault" | while read f; do echo "$(basename "$f") $(wc -c < "$f")"; done' | sort; }

LINE7_VAULT=""
LINE7_MODE=""

# pin_rec_mode MODE: chmod MODE on pin.rec of the vault LINE7_VAULT (run-as).
pin_rec_mode() { pv_shell "run-as $PV_PACKAGE chmod $1 no_backup/lock/$LINE7_VAULT/pin.rec" >/dev/null 2>&1; }

# to_start_empty: the current vault's pin.rec unreadable; a new process: the master-password screen (the
# PIN can't be checked); "Forgot master password?" > "Start with an empty app" > confirmed: Welcome; then
# pin.rec readable again, and a new process: Welcome.
to_start_empty() {
    pvl_status || return 1
    LINE7_VAULT=$(pvl_vault_id)
    [ -n "$LINE7_VAULT" ] || { pv_log "no current vault in the status"; return 1; }
    LINE7_MODE=$(pv_shell "run-as $PV_PACKAGE stat -c %a no_backup/lock/$LINE7_VAULT/pin.rec" 2>/dev/null | tr -d '\r')
    [ -n "$LINE7_MODE" ] || { pv_log "no pin.rec for $LINE7_VAULT"; return 1; }
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pin_rec_mode 000 || return 1
    pva_start && pva_on Locked 30 && pva_need pv.lock.password 30 || { pin_rec_mode "$LINE7_MODE"; return 1; }
    pv_log "the lock screen with pin.rec unreadable: $(pva_text pv.lock.reason 2>/dev/null); $(pva_status | grep -E '^(policy|pin.rec)' | tr '\n' ';')"
    pva_hide_keyboard
    pva_tap pv.lock.forgot && pva_need pv.lock.sheet 10 || { pin_rec_mode "$LINE7_MODE"; return 1; }
    pv_log "the sheet: $(screen_texts)"
    { pva_reveal pv.lock.sheet.empty && pva_tap pv.lock.sheet.empty && pva_need pv.lock.confirmYes 10 && pva_tap pv.lock.confirmYes && pva_on Welcome 60; } \
        || { pin_rec_mode "$LINE7_MODE"; return 1; }
    pin_rec_mode "$LINE7_MODE" || return 1
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pva_start && pva_on Welcome 60
}

line7() {
    T="line 7"
    local before after aside
    lock_lib
    pv_step "$T: a fresh install, setup with a PIN, two accounts" -- eval 'fresh pin && add_two' || return 1
    OLD_MP=$PVA_PASSWORD
    before=$(vault_files)
    echo "    | the vault's files: $(printf '%s' "$before" | tr '\n' ';')"
    pv_step "$T: its PIN record unreadable for a new process: \"Forgot master password?\" > \"Start with an empty app\": Welcome; the record readable again" -- to_start_empty || return 1
    aside=$(pv_ui_nodes | grep -o 'pv\.setup\.aside\.[0-9a-f-]*' | head -n 1)
    pv_step "$T: Welcome offers \"Open my previous passwords\"" -- [ -n "$aside" ] || return 1
    pv_step "$T: their own lock screen (the pad); their PIN opens them" -- eval 'pva_tap "$aside" && pva_need pv.lock.pad 30 && pva_pin "$PIN" && pva_opened 60 && pva_settle Passwords 60'
    pv_step "$T: Gmail and Payme are back" -- eval 'pva_row_with Gmail 15 && pva_row_with Payme 10'
    after=$(vault_files)
    pv_step "$T: no vault file was deleted (U 12 check 16): the same generations, sizes alike" -- eval '[ "$before" = "$(printf "%s\n" "$after" | grep -F -x -f <(printf "%s\n" "$before"))" ]'
    pv_step "$T: \"Start with an empty app\" again: Welcome; a new vault set up" -- eval 'to_start_empty && pva_set_up' || return 1
    pv_step "$T: Settings > Old passwords: the vault put aside; Open with its master password adds its accounts" -- old_passwords_open "$OLD_MP"
    pv_step "$T: no crash" -- pva_no_crash
}
OLD_MP=""

# old_passwords_open PASSWORD: Settings > Old passwords; the first vault's Open; PASSWORD; its accounts
# are added to the current vault (Gmail and Payme in the list).
old_passwords_open() {
    local open
    settings && pva_reveal pv.open.OldPasswords && pva_tap pv.open.OldPasswords && pva_on OldPasswords 20 || return 1
    pva_need pv.backup.old.list 15 || return 1
    open=$(pv_ui_nodes | grep -o 'pv\.backup\.old\.open\.[0-9a-f-]*' | head -n 1)
    [ -n "$open" ] || { pv_log "no vault in Old passwords; ids: $(pva_ids)"; return 1; }
    pva_tap "$open" && pva_need pv.backup.password 20 && pva_type pv.backup.password "$1" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.old.done 90 || return 1
    pv_log "$(pva_text pv.backup.old.done)"
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Settings 20 && pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_row_with Gmail 15 && pva_row_with Payme 10
}

# ---- U 12 check 15: a forgotten master password replaced with the PIN, finished, a backup offered ----
# from_lock_screen: Lock now; "Forgot PIN? Use master password"; "Forgot master password?" > "Use my
# PIN"; the PIN: the app opens on Route.NewMasterPassword, its explanation (iface-126).
from_lock_screen() {
    pva_lock_now && pva_need pv.lock.pad 20 && pva_tap pv.lock.forgotPin || return 1
    pva_need pv.lock.password 20 && pva_hide_keyboard
    pva_tap pv.lock.forgot && pva_need pv.lock.sheet.usePin 20 && pva_tap pv.lock.sheet.usePin || return 1
    pva_need pv.pin.key.0 20 && pva_pin "$PIN" || return 1
    pva_on NewMasterPassword 60 && pva_need pv.master.explain 20
}

# finish_ended: after "Finish" the replacement ends within 120 s: the PIN steps, a notice or 5.5's backup step.
finish_ended() {
    local deadline=$((SECONDS + 120))
    until pva_present pv.route.CreatePin 2 || pva_present pv.notice 2 || pva_present pv.master.later 2; do
        [ $SECONDS -lt $deadline ] || { pv_log "the finishing did not end; ids: $(pva_ids)"; return 1; }
    done
}

check15() {
    T="check 15"
    lock_lib
    local old
    pv_step "$T: a fresh install, setup with a PIN, two accounts imported with dates three days old: the backup banner" -- eval 'fresh pin && add_old_two && pva_need pv.list.banner.backup 20' || return 1
    old=$PVA_PASSWORD
    pv_step "$T: from the lock screen (\"Forgot master password?\" > \"Use my PIN\", the PIN): the explanation of a new master password" -- from_lock_screen || return 1
    # (FieldKeyboard brings back a keyboard closed within a second of the field's first request: wait it out.)
    pv_step "$T: Cancel; the next request asks for the PIN again (the proof ended with its flow)" -- eval 'pva_tap pv.master.cancel && pva_unlocked Passwords 30 && pva_settle Passwords 30 && settings && pva_tap pv.open.ChangeMasterPassword && pva_on ChangeMasterPassword 20 && pv_sleep 2 && pva_hide_keyboard && pva_tap pv.open.NewMasterPassword && pva_need pv.master.continue 20 && pva_tap pv.master.continue && pva_need pv.pin.key.0 30' || return 1
    pv_step "$T: the PIN; a new master password chosen: the wait (\"starts working in about 24 hours\")" -- eval 'pva_pin "$PIN" && choose_words && pva_need pv.master.wait 60' || return 1
    pv_log "the wait: $(pva_text pv.master.wait 2>/dev/null)"
    PVN_NEW_PASSWORD=$NEW_MP
    pv_step "$T: Done; the list shows the pending banner and no backup banner" -- eval 'pva_tap pv.master.done; for i in 1 2 3 4; do pva_present pv.route.Passwords 2 && break; pva_present pv.tab.Passwords 1 && pva_tap pv.tab.Passwords || pv_shell input keyevent KEYCODE_BACK >/dev/null; pv_sleep 1; done; pva_settle Passwords 60 && pva_need pv.list.banner.pending 20 && pva_absent pv.list.banner.backup'
    pv_step "$T: the 24 credited hours made to pass (state.rec's credited_ms, debug builds: an emulator cannot wait them)" -- pvn_make_ready || return 1
    pv_step "$T: the app started again; the PIN; \"Finish now\" offered" -- eval 'pva_start && pva_on Locked 30 && pva_need pv.lock.pad 20 && pva_pin "$PIN" && pva_unlocked Passwords 30 && pva_need pv.answer.FINISH_NOW 30 && pva_tap pv.answer.FINISH_NOW' || return 1
    pv_step "$T: finished by typing it once more: the replacement made (F11 steps 14 to 18)" -- eval 'pva_need pv.master.finish.field 30 && pva_type pv.master.finish.field "$NEW_MP" && pva_hide_keyboard && pva_tap pv.master.finish && finish_ended' || return 1
    pv_step "$T: \"Choose a PIN\" for the new vault (F11 step 19): a PIN chosen; the fingerprint offer Not now; back on the route" -- eval 'pvl_pin_steps "$PIN" - NewMasterPassword'
    pv_step "$T: then a new backup is offered" -- pva_need pv.master.backUpNow 60
    pv_step "$T: \"Back up now\": the backup, with no master password asked again, into Download" -- eval 'pva_tap pv.master.backUpNow && pva_on Backup 30 && ! pva_present pv.backup.password 5 && w5_backup asked'
    PVA_PASSWORD=$NEW_MP
    pv_step "$T: the new master password opens the vault" -- eval 'pva_tap pv.tab.Passwords; pva_on Passwords 20; mp_opens "$NEW_MP"'
    pv_step "$T: the accounts are there" -- eval 'pva_row_with Gmail 10 && pva_row_with Payme 10'
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- the owner's decision 21: a change that ended under the lock is shown after the unlock ----
d21() {
    T="decision 21"
    local until now
    pv_step "$T: a fresh install, setup with a PIN and fingerprint unlock, two accounts imported with dates three days old: the list's backup banner" -- eval 'fresh pin+fp && add_old_two && pva_need pv.list.banner.backup 20' || return 1
    pv_step "$T: Change master password: the current one, a new one, the PIN kept" -- eval 'change_to_pin_step && pva_pin "$PIN"' || return 1
    # The prompt is the window in front (API 28 and later): the app's line under it is read from every window.
    pv_step "$T: \"Touch the sensor to keep fingerprint unlock on.\" waits (the lock's prompt for KEEP_AFTER_CHANGE is open)" -- eval 'pva_prompt_open KEEP_AFTER_CHANGE 60 && pv_log "the windows: $(pv_ui_nodes_all 2>/dev/null | cut -d"|" -f1 | grep -v "^$" | tr "\n" ";" | cut -c1-300)"' || return 1
    pv_step "$T: the screen off for 3 s meanwhile: locked" -- eval 'pva_screen_off 3 && pva_status_soon "^state: locked" 20'
    pv_step "$T: the unlock (the PIN, kept): the route shows \"Your master password was changed.\" with \"Make a new backup now. …\"" -- eval 'pva_on Locked 30 && pva_need pv.lock.pad 20 && pva_pin "$PIN" && pva_on ChangeMasterPassword 60 && pva_need pv.master.later 30 && pv_ui_nodes | cut -d"|" -f1 | grep -qx "Your master password was changed." && pv_ui_nodes | cut -d"|" -f1 | grep -q "^Make a new backup now. "'
    now=$(($(pv_shell date +%s | tr -d '\r') * 1000))
    pv_step "$T: \"Later\": the backup reminder is snoozed for 3 days; the list shows no backup banner" -- eval 'pva_tap pv.master.later && pv_sleep 2 && until=$(prefs_value backup_reminder_snoozed_until_ms) && pv_log "snoozed until $until, now $now" && [ -n "$until" ] && [ $((until - now)) -gt $((3 * 86400000 - 120000)) ] && [ $((until - now)) -le $((3 * 86400000 + 120000)) ] && pva_tap pv.tab.Passwords && pva_on Passwords 20 && pv_sleep 3 && pva_absent pv.list.banner.backup'
    PVA_PASSWORD=$NEW_MP
    pv_step "$T: the new master password opens the vault" -- mp_opens "$NEW_MP"
    pv_step "$T: no crash" -- pva_no_crash
}

# ---- wave 7 on one device: the fifty accounts (section 9), and 7.3's walk ----
# is_flagged_dot FILE: the Security tab in the screenshot FILE has its dot (Material's small Badge in the
# theme's error colour) at the top right of its icon; read beside the tab's bounds (pv.tab.Security).
security_dot() {
    local line file=$DEV_OUT/security-tab-$1.png
    line=$(pv_ui_nodes_all 2>/dev/null | awk -F'|' '$3 == "pv.tab.Security" { print; exit }')
    [ -n "$line" ] || { pv_log "no Security tab"; return 2; }
    pva_screenshot "$file" || return 2
    python3 - "$file" "$line" <<'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
f = sys.argv[2].split("|")
left, top, right, bottom = map(int, f[7:11])
cx = (left + right) // 2
# The icon is in the upper half of the item; its badge sits right of the icon's centre.
box = (cx, top, min(right, cx + (right - left) // 3), top + (bottom - top) // 2)
red = sum(1 for p in im.crop(box).getdata() if p[0] > 150 and p[1] < 90 and p[2] < 90)
print(f"    | the Security tab's icon area: {red} red pixels")
sys.exit(0 if red >= 20 else 1)
PYEOF
}

FIFTY=pv-5-fifty.csv

# guided_change: the reuse card's group list, Gmail's Change: create a new password, "Yes, it's changed":
# "Gmail now has its own password."
guided_change() {
    pva_tap pv.tab.Security && pva_on Security 30 || return 1
    pva_reveal pv.security.card.reused.open && pva_tap pv.security.card.reused.open && pva_on Group 20 || return 1
    local action
    action=$(pv_ui_nodes | grep -o 'pv\.group\.action\.[0-9a-f-]*' | head -n 1)
    [ -n "$action" ] || { pv_log "no Change in the group; ids: $(pva_ids)"; return 1; }
    pv_log "the group's first row: $(pv_ui_nodes | grep -B 3 "$action" | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';')"
    pva_tap "$action" && pva_on GuidedChange 20 || return 1
    pva_reveal pv.change.create && pva_tap pv.change.create || return 1
    pva_reveal pv.change.yes && pva_tap pv.change.yes || return 1
    pva_need pv.change.done 20 || pva_need pv.change.next 5 || return 1
    pv_ui_nodes | cut -d'|' -f1 | grep -qx 'Gmail now has its own password.' || { pv_log "texts: $(pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-400)"; return 1; }
}

# master_password_on PAYME: Payme's password made equal to the master password through its edit form.
master_password_on() {
    pva_tap pv.tab.Passwords && pva_on Passwords 20 || return 1
    local row
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { r = $3 } $1 == "Payme" && r != "" { print r; exit }')
    [ -n "$row" ] || { pv_log "no Payme row; rows: $(pva_row_titles | tr '\n' ';')"; return 1; }
    pva_tap "$row" && pva_on Entry 20 && pva_tap pv.entry.edit && pva_on Edit 20 || return 1
    pva_tap pv.edit.password && pv_shell input keyevent KEYCODE_MOVE_END >/dev/null || return 1
    local i
    for i in $(seq 1 40); do pv_shell input keyevent KEYCODE_DEL >/dev/null; done
    pv_shell input text "$PVA_PASSWORD" >/dev/null
    pva_hide_keyboard
    pva_tap pv.edit.save && pva_on Entry 30 && back_to_list
}

fifty() {
    T="wave 7"
    pv_step "$T: a fresh install, setup (the PIN left off)" -- fresh mp || return 1
    cp "$REPO/app/src/androidTest/assets/fixtures/fifty-accounts.csv" "$DEV_OUT/$FIFTY"
    pv_step "$T: the fifty accounts of app/src/androidTest/assets/fixtures/fifty-accounts.csv imported (17 share one password)" -- eval 'clean_download && pva_push_download "$DEV_OUT/$FIFTY" && w5_import csv "$FIFTY" pushed'
    pv_step "$T: the Security tab's dot is shown (new results)" -- eval 'pva_tap pv.tab.Passwords && pva_on Passwords 20 && pv_sleep 5 && security_dot before'
    pv_step "$T: the Security tab: \"Why this matters\" open; the cards of U 5.16: 17 accounts use the same password, easy to guess" -- eval 'pva_tap pv.tab.Security && pva_on Security 30 && pva_need pv.security.why.text 30 && pva_need pv.security.card.reused 30 && pv_ui_nodes | cut -d"|" -f1 | grep -q "^17 accounts use the same password" && pva_reveal pv.security.card.weak'
    pv_log "the cards: $(pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-600)"
    pv_step "$T: the dot is gone once the results were seen" -- eval 'pva_tap pv.tab.Passwords && pva_on Passwords 20 && ! security_dot seen'
    pv_step "$T: one guided change runs to \"Gmail now has its own password.\"" -- guided_change
    pv_step "$T: Payme's password made the master password (its form)" -- eval 'pva_tap pv.tab.Passwords; master_password_on'
    pv_step "$T: the next master-password entry (Lock now, the master password)" -- mp_opens "$PVA_PASSWORD"
    pv_step "$T: the dot is back (decision 23)" -- eval 'pv_sleep 5 && security_dot after'
    pv_step "$T: the Security tab flags it: \"Master password used elsewhere\" (Payme); \"Why this matters\" open again" -- eval 'pva_tap pv.tab.Security && pva_on Security 30 && pva_need pv.security.card.master 30 && pva_need pv.security.why.text 10 && pv_ui_nodes | cut -d"|" -f1 | grep -q "Payme"'
    pv_step "$T: no crash" -- pva_no_crash
}

# walk73: package 7.3's walk of the real app (/home/asus/pvwork/kept/w7-7.3/walk73.sh, adapted to main):
# the first-copy tip after the first copy, across a rotation, not after the second; every help page and
# the licences page from Settings; screenshots for the user into SHOTS on API 37.
text_is() {
    local i t=""
    for i in 1 2 3 4 5 6 7 8 9 10; do
        t=$(pva_text "$1" 2>/dev/null)
        [ "$t" = "$2" ] && return 0
        pv_sleep 1
    done
    pv_log "$1 shows '$t', not '$2'"
    return 1
}

shot73() { [ "$API" = 37 ] || return 0; pva_screenshot "$SHOTS/7.3-$1.png"; }

help_page() {
    pva_reveal "pv.open.Help.$1" && pva_tap "pv.open.Help.$1" && pva_on Help && pv_ui_wait "[|]pv\\.help\\.$1\$" 10 || return 1
    shot73 "$2"
    pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Settings
}

open_licences() { pva_reveal pv.open.Licences && pva_tap pv.open.Licences && pva_on Licences && pva_need pv.licences.list; }

licences() {
    open_licences || return 1
    shot73 licences
    pva_tap pv.licences.zxcvbn4j.licence && pva_reveal pv.licences.zxcvbn4j.text && text_is pv.licences.zxcvbn4j.text "The MIT License (MIT)" || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null; pva_on Settings || return 1
    open_licences && pva_tap pv.licences.details.strength && pva_reveal pv.licences.details.strength.text \
        && text_is pv.licences.details.strength.text "Strength word list: sources, licences and counts" || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null; pva_on Settings || return 1
    open_licences && { pva_reveal pv.licences.materialicons || pva_reveal pv.licences.materialicons; } || return 1
    shot73 licences-icons
    pv_shell input keyevent KEYCODE_BACK >/dev/null; pva_on Settings
}

walk73() {
    T="7.3"
    pv_step "$T: a fresh install, setup (the PIN left off), an account" -- eval 'fresh mp && pva_add_account Gmail me.example tR0ub4dor-3xyz mail.google.com note' || return 1
    pv_step "$T: its password copied: the first-copy tip opens" -- eval 'pva_tap pv.entry.password.copy && pva_present pv.tip.ok 5'
    shot73 first-copy-tip
    pv_step "$T: the tip stays open across a rotation and back" -- eval 'pva_rotate 1 && pva_present pv.tip.ok 5 && pva_rotate 0 && pva_present pv.tip.ok 5'
    pv_step "$T: OK closes it; the status row is there" -- eval 'pva_tap pv.tip.ok && pv_sleep 1 && pva_absent pv.tip.ok && pva_need pv.clip.row 5'
    pv_step "$T: a second copy: no tip" -- eval 'pva_tap pv.entry.password.copy && pv_sleep 3 && pva_absent pv.tip.ok'
    pv_step "$T: Settings" -- eval 'back_to_list && settings'
    pv_step "$T: How your passwords are protected" -- help_page PROTECTION help-protection
    pv_step "$T: If you lose your phone" -- help_page LOST_PHONE help-lost-phone
    pv_step "$T: If you forget your master password" -- help_page FORGOT_MASTER_PASSWORD help-forgot-master-password
    pv_step "$T: If you forget your PIN" -- help_page FORGOT_PIN help-forgot-pin
    pv_step "$T: Open-source licences: a licence text, the strength list's sources, the icons" -- licences
    pv_step "$T: Version, and from it the licences" -- eval 'help_page VERSION help-version && pva_reveal pv.open.Help.VERSION && pva_tap pv.open.Help.VERSION && pva_on Help && pva_tap pv.open.Licences && pva_on Licences && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Help && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Settings'
    pv_step "$T: no crash" -- pva_no_crash
}

# dev_run SERIAL VARIANT APK CHECK...: the checks on one device, then its summary. The parts run it in a
# subshell of this script, which would inherit this script's own counts: they start from 0.
dev_run() {
    local check
    PV_PASSED=0
    PV_FAILED=0
    PV_SKIPPED=0
    pv_use "$1"
    pva_init
    DEV_VARIANT=$2
    DEV_APK=$3
    shift 3
    API=$(pv_api)
    DEV_OUT=$LOGS_ROOT/files/$PV_SERIAL
    mkdir -p "$DEV_OUT" "$SHOTS"
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-180}
    echo "5 checks $*, $DEV_VARIANT on $PV_SERIAL (API $API): $DEV_APK"
    for check in "$@"; do
        case "$check" in
            line1 | line2 | line3 | line4 | line5 | line6 | line7 | check13 | check15 | d21 | fifty | walk73) "$check" ;;
            *) pv_die "unknown check $check" 2 ;;
        esac
    done
    pva_rotate 0
    pv_summary
}

w5_debug() { dev_run "$1" debug "$DEBUG_APK" line1 line2 line3 line4 line5 line6 line7 check15 d21; }
w7_debug() { dev_run "$1" debug "$DEBUG_APK" fifty walk73; }
guided() { "$EMU/change/guided.sh" "$1" --apk "$DEBUG_APK" --test-apk "$TEST_APK" --shots "$SHOTS"; }

build_test_apk() {
    "$REPO/tools/gradle.sh" :app:assembleDebugAndroidTest > "$LOGS/assemble-test.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS/assemble-test.log" | sed 's/^/   /'
    [ $rc = 0 ] && [ -f "$TEST_APK" ]
}

# ---- arguments ------------------------------------------------------------------------------------------
# "one SERIAL VARIANT [--apk FILE] CHECK...": checks on one device of this worktree.
if [ "${1:-}" = one ]; then
    [ $# -ge 4 ] || pv_usage "${BASH_SOURCE[0]}"
    one_serial=$2
    one_variant=$3
    shift 3
    case "$one_variant" in debug | staging) ;; *) pv_usage "${BASH_SOURCE[0]}" ;; esac
    one_apk=$REPO/app/build/outputs/apk/$one_variant/app-$one_variant.apk
    one_checks=()
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; one_apk=$2; shift ;;
            --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
            *) one_checks+=("$1") ;;
        esac
        shift
    done
    [ ${#one_checks[@]} -gt 0 ] || pv_usage "${BASH_SOURCE[0]}"
    mkdir -p "$LOGS_ROOT"
    dev_run "$one_serial" "$one_variant" "$one_apk" "${one_checks[@]}"
    exit $?
fi

ALL_PARTS=(merged connected lock lockstaging i18n extras skeleton)
DEVICE_PARTS=(connected lock lockstaging i18n extras skeleton)
parts=()
gradle_args=()
keep=0
while [ $# -gt 0 ]; do
    case "$1" in
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --levels)
            [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"
            read -r -a APIS <<< "${2//,/ }"
            for api in "${APIS[@]}"; do case "$api" in 24 | 28 | 37) ;; *) pv_usage "${BASH_SOURCE[0]}" ;; esac; done
            shift ;;
        --keep) keep=1 ;;
        --) shift; gradle_args=("$@"); break ;;
        stop) parts+=("$1") ;;
        *)
            [[ " ${DEVICE_PARTS[*]} merged " == *" $1 "* ]] || pv_usage "${BASH_SOURCE[0]}"
            parts+=("$1") ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=("${ALL_PARTS[@]}")
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }
needs_devices() { local p; for p in "${DEVICE_PARTS[@]}"; do wants "$p" && return 0; done; return 1; }
CONN_DIR=$LOGS_ROOT/connected/api-$(IFS=-; echo "${APIS[*]}")

mkdir -p "$LOGS_ROOT"
echo "round R4 (waves 4, 5 and 7) device check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]} on API ${APIS[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R4"
    pv_step "the heads of w5-carry, w5-carry2, 4.5, 5.5, 6.1, 7.1, 7.2 and 7.3 are in HEAD" -- heads_merged
fi
if needs_devices; then
    echo
    echo "== devices: the integrator's API ${APIS[*]}"
    if ! pv_step "build the debug and staging APKs" -- build_apks || ! pv_step "the integrator's devices run (boot.sh --integrator)" -- devices; then
        parts=(stop)
    fi
fi
# A part run on some of the levels keeps its logs apart (LOGS_ROOT/PART-api-28-37), so that the same part
# run on the other levels does not remove them.
LEVELS_SUFFIX=""
[ "${APIS[*]}" = "24 28 37" ] || LEVELS_SUFFIX=-api-$(IFS=-; echo "${APIS[*]}")
for part in "${DEVICE_PARTS[@]}"; do
    wants "$part" || continue
    LOGS=$LOGS_ROOT/$part$LEVELS_SUFFIX
    [ "$part" = connected ] || rm -rf "$LOGS"
    mkdir -p "$LOGS"
    echo
    echo "== $part on API ${APIS[*]} ($(date +%T))"
    began=$(date +%s)
    case $part in
        connected)
            pv_step "connectedDebugAndroidTest on API ${APIS[*]} in touch mode (adb.sh --connected; a failed class once more alone)" -- connected_tests
            pv_step "every device-test class passed on each device with at least its tests (563, 565, 575)" -- device_classes
            studio_runs "$began" ;;
        lock)
            pv_step "the test APK with its documents provider is not on the devices" -- no_test_apk
            pv_step "lock.md E1 to E17 on their levels (tools/emu/lock/all.sh), debug build, API ${APIS[*]}" -- lock_all all-debug "24 28 37"
            pv_step "E4, E5 and E11 ran their steps of 5.5 and 6.1 on the merged app (none waits for a package)" -- lock_later_ran "$LOGS/all-debug" ;;
        lockstaging)
            pv_step "the staging repeat: E1 and E6 with the staging build on API 24 and 37 (lock/all.sh --staging)" -- lock_all all-staging "24 37" --staging --only E1,E6
            pv_step "E16 with the staging build on API 24 (decision 22: the Java Argon2 fallback on a 256 MiB heap)" -- in_parallel e16-staging e16_staging -- 24 ;;
        i18n)
            pv_step "6.1: tools/emu/i18n/all.sh (the matrix of i18n.md 5 and E11's language step), debug build, API ${APIS[*]}" -- i18n_all ;;
        extras)
            pv_step "boot.sh put API 37 in airplane mode (no default network) and left API 24 and 28 as they were" -- airplane_all
            pv_step "app.sh sourced a second time keeps the screen size and the watched message (API 37's 2400 pixels)" -- in_parallel sourced-twice sourced_twice -- 37
            pv_step "shell/walk.sh with the debug build" -- in_parallel walk-debug walk_debug -- 24 28 37
            pv_step "shell/walk.sh with the staging build on API 37" -- in_parallel walk-staging walk_staging -- 37
            pv_step "smoke.sh debug (its copy stage closes the first-copy tip)" -- in_parallel smoke-debug smoke_debug -- 24 28 37
            pv_step "checks/3B.sh clip, debug build (its copy closes the first-copy tip)" -- in_parallel clip-3B extras_clip -- 24 28 37
            pv_step "clipboard/clear.sh, debug build (its first copy closes the first-copy tip)" -- in_parallel clear clear_debug -- 24 28 37 ;;
        skeleton)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "the skeleton steps 1 to 9 of checks/3A.sh, debug build" -- in_parallel skeleton-debug skeleton_debug -- 24 28 37
            pv_step "the skeleton steps 1 to 9 of checks/3A.sh, staging build, on API 24 and 37" -- in_parallel skeleton-staging skeleton_staging -- 24 37 ;;
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
