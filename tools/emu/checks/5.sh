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

# lock_all NAME ALL.SH-ARGS...: tools/emu/lock/all.sh with those arguments and the serials of --levels;
# its table and logs are copied to LOGS/NAME (all.sh keeps only its last run).
lock_all() {
    local name=$1 rc began
    shift
    began=$(date +%s)
    # shellcheck disable=SC2046 # the serials are words
    "$EMU/lock/all.sh" "$@" $(serials_of 24 28 37) > "$LOGS/$name.log" 2>&1
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

# ---- arguments ------------------------------------------------------------------------------------------
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
for part in "${DEVICE_PARTS[@]}"; do
    wants "$part" || continue
    LOGS=$LOGS_ROOT/$part
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
            pv_step "lock.md E1 to E17 on their levels (tools/emu/lock/all.sh), debug build, API ${APIS[*]}" -- lock_all all-debug
            pv_step "E4, E5 and E11 ran their steps of 5.5 and 6.1 on the merged app (none waits for a package)" -- lock_later_ran "$LOGS/all-debug" ;;
        lockstaging)
            pv_step "the staging repeat: E1 and E6 with the staging build on API 24 and 37 (lock/all.sh --staging)" -- lock_all all-staging --staging --only E1,E6 --levels 24,37
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
