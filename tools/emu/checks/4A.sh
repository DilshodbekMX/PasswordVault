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
#              477 on 37 at wave-4A-merged. (Until the merge of R4 the devices were put in keyboard mode
#              first, because on API 37 in touch mode the keyboard did not come back after a rotation;
#              w5-carry step 3's FieldKeyboard fixed that, so the tests now run in whatever mode the
#              other parts left.)
#   lock       the plan's line for 4A (build-plan.md section 6), debug build, on API 24, 28 and 37 at
#              once: setup with a PIN and fingerprint unlock (the offer's "Use", finger 1 on the
#              prompt); the status has both slots and their keys; Lock now: the prompt opens by itself
#              and finger 1 opens the app; Lock now, "Use PIN": the PIN opens it; four wrong PINs, each
#              with its line, and a fifth: the PIN is off (no slot, its key gone), fingerprint stays;
#              the fingerprint-only screen's "Use master password"; the master password; "Choose a new
#              PIN" follows (a notice of the wrong PINs before it answered OK); a new PIN; it opens the
#              app, the account is still there, and finger 1 still opens it.
#   e          lock.md 15.3 E1, E2, E3, E6 and E11 without its language step, with the debug build on
#              API 24, 28 and 37 at once, then with the staging build (R8, not debuggable): E1 the PIN
#              through a real Keystore key, the time from the sixth digit logged, five wrong PINs (slot
#              and alias gone, the master password opens); E2 hold-after-charge and am force-stop, then
#              hold-before-hmac and kill -9 (run-as, or su on API 24 and 28; on API 37 staging am
#              force-stop, whose SIGKILL comes from the system): each counted, "interrupted" shown; E3
#              a reboot with a charge held: counted; E6 the prompt by itself, finger 5 leaves it open,
#              finger 1 opens with no tap, "Use PIN" (on API 24 the library's dialog in the activity)
#              returns to the pad and no second prompt comes; E11 three digits and the charge across two
#              rotations, the prompt open across a rotation (the result reaches the new activity), the
#              master-password field across rotations. E11's "nothing in the saved-state bundle" is
#              window/state.sh's (part window, API 28 and 37; the helper app cannot run on API 24).
#   update     lock.md F14 after an update: the debug and staging APKs of wave-3C-merged (the gate of
#              3.2, no lock records; built once from the tag in a throwaway worktree and kept in the
#              logs) set up a vault with an account; today's build installs over it (adb install -r):
#              "Welcome back", the master password opens it, "Choose a PIN" follows, the account is
#              there, the lock directory with state.rec and pin.rec is made, and the PIN opens the app.
#              Debug over debug on API 24, 28 and 37, staging over staging on API 24 and 37. The user's
#              own emulator holds such a vault.
#   skeleton   checks/3A.sh skeleton (steps 1 to 9, with "Choose a PIN" after setup answered "Not
#              now"), debug on API 24, 28 and 37, staging on API 24 and 37.
#   smoke      smoke.sh staging on API 24 and 37: with its stage of 4A, the PIN and the fingerprint (on
#              API 24 androidx.biometric's own dialog, the path R8 broke before build check R-16).
#   window     window/all.sh on the three devices with the debug and the staging APK: keyboard,
#              autofill, overlay, state and a11y on API 28 and 37, a11y-old on API 24; since this check
#              keyboard.sh and state.sh also look at the PIN steps' master password ("Set a PIN") and
#              keyboard.sh at the backup's, and state.sh at digits typed on the PIN steps' pad.
#   back       API 37, debug: Back with the keyboard up (5.2 found that from Android 13 on Back can
#              reach the app first) closes the keyboard and nothing else, on the new-account form, the
#              edit form, the lock screen's master password and the master password that "Set a PIN"
#              asks for.
#   looks      API 37 and 24, debug: each of the three styles in light and dark, and the phone's colours
#              on API 37 (not offered below Android 12), chosen in Settings > Appearance: Settings has
#              the scheme at once (the same process), the list, an account and the lock screen's pad
#              have it, and after a new process the lock screen still has it; a screen has a scheme
#              when most of its pixels are the scheme's background (ui/theme/Schemes.kt). The API 37
#              screenshots go to --shots DIR (default /home/asus/pvwork/shots-look) as
#              4A-api37-<style>-<light|dark>-<pad|list|account|settings>.png, for the user.
#   extras     the packages' scripts that item 4 of the merge of R3 changed for the real lock and that
#              no device had run: shell/walk.sh, setup/create.sh --vault-file and session/autolock.sh
#              with the debug build on API 24, 28 and 37, and walk.sh with the staging build on API 37.
#              (clipboard/clear.sh, which item 4 did not change, is left out: about 16 minutes a device.)
#   wave5      only when named, not part of the check (wave 5 has its own after 5.5): API 37, debug, a
#              first walk of the merged wave-5 screens through the system's picker: a backup into
#              Download, each export, an import of a small CSV, a restore from the lock screen that
#              keeps the lock delay (decision 13), Old passwords with the vault the restore put aside.
#   one SERIAL VARIANT [--apk FILE] [--base FILE] CHECK...  the checks plan, e1, e2, e3, e6, e11, f14,
#              back, looks or wave5 on one device of this worktree (no part, no devices booted).
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

tries_left() { if [ "$1" = 1 ]; then echo "1 try left"; else echo "$1 tries left"; fi; }

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
        pv_step "4A: wrong PIN $n: the pad stays and says \"Wrong PIN\" with $(tries_left $((5 - n)))" -- pad_says "Wrong PIN.*$((5 - n))"
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
        pv_step "E1: wrong PIN $n: the pad stays and says \"Wrong PIN\" with $(tries_left $((5 - n)))" -- pad_says "Wrong PIN.*$((5 - n))"
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

# ---- Back with the keyboard open (API 37) ----
# 5.2 found that on Android 13 and newer Back can reach the app's own callbacks before the keyboard's
# (BackupScaffold's KeyboardFirstOnBack). On each screen below a text is typed, so the keyboard is up;
# one Back must close the keyboard and do nothing else: the same screen, no dialog, the text kept.

# masks N: N mask characters, as a VaultSecureField drawn as dots reports its text.
masks() { printf '%*s' "$1" '' | sed 's/ /•/g'; }

# back_closes_keyboard ROUTE FIELD EXPECTED: with the keyboard shown, one Back: the keyboard closes,
# ROUTE is still shown with no dialog of the form, and FIELD's text is EXPECTED.
back_closes_keyboard() {
    local text
    pva_wait_keyboard 10 || { pv_log "the keyboard is not shown before Back"; return 1; }
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pv_sleep 2
    if pva_keyboard_shown; then pv_log "the keyboard is still shown after one Back"; return 1; fi
    pva_on "$1" 5 || return 1
    pva_absent pv.edit.dialog.discard && pva_absent pv.edit.dialog.save && pva_absent pv.edit.dialog.keep \
        || { pv_log "a dialog of the form opened; ids: $(pva_ids)"; return 1; }
    text=$(pva_text "$2") || return 1
    [ "$text" = "$3" ] || { pv_log "$2 reads '$text', not '$3'"; return 1; }
}

back_new_form() {
    pva_on Passwords 20 && pva_tap pv.list.add && pva_on Edit 20 || return 1
    pva_type pv.edit.name Backcheck || return 1
    back_closes_keyboard Edit pv.edit.name Backcheck || return 1
    # The second Back leaves the form: "Discard?" (something was typed), Discard.
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_need pv.edit.dialog.discard 10 && pva_tap pv.edit.dialog.discard && pva_on Passwords 20
}

back_edit_form() {
    local row
    pva_on Passwords 20 || return 1
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
    [ -n "$row" ] || { pv_log "no row in the list"; return 1; }
    pva_tap "$row" && pva_on Entry 20 && pva_tap pv.entry.edit && pva_on Edit 20 || return 1
    pva_type pv.edit.username .more || return 1
    back_closes_keyboard Edit pv.edit.username "$BACK_LOGIN.more" || return 1
    # The second Back asks "Save changes?"; its Discard goes back to the account.
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_need pv.edit.dialog.discard 10 && pva_tap pv.edit.dialog.discard && pva_on Entry 20 || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20
}

# The lock screen's master-password field: Back closes the keyboard; the lock screen stays in front.
back_lock_screen() {
    local half=${PVA_PASSWORD:0:7}
    pva_lock_now && pva_need pv.lock.password 20 || return 1
    pva_type pv.lock.password "$half" || return 1
    back_closes_keyboard Locked pv.lock.password "$(masks ${#half})" || return 1
    pva_in_front || { pv_log "the app left the front: $(pv_focus)"; return 1; }
    pva_type pv.lock.password "${PVA_PASSWORD:7}" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && settle_open
}

# A master-password prompt of the open app: Settings > "Set a PIN" with no PIN asks for the master
# password (4.3's MasterPasswordPage), once the proof of the last unlock is gone (ON_STOP drops it:
# Home, and back within the lock delay).
back_mp_prompt() {
    local typed=${PVA_PASSWORD:0:9}
    pva_away 3 && pva_opened 20 && settle_open || return 1
    pva_tap pv.tab.Settings && pva_on Settings 20 && pva_tap pv.open.ChangePin || return 1
    pva_need pv.pin.mp.field 20 || return 1
    pva_type pv.pin.mp.field "$typed" || return 1
    back_closes_keyboard ChangePin pv.pin.mp.field "$(masks ${#typed})" || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Settings 20 && pva_tap pv.tab.Passwords && pva_on Passwords 20
}

BACK_LOGIN=back.check@example.uz
back() {
    T=back
    pv_step "back: the $DEV_VARIANT build installs (fresh); setup leaves the PIN off; an account" -- eval 'pva_install "$DEV_APK" && pva_start && pva_set_up && pva_add_account Backbank "$BACK_LOGIN" Zr8Kq2Vx6Pn4 https://back.example.uz note && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords' || return 1
    STATE=none
    pv_step "back: the new-account form: Back with the keyboard up closes the keyboard; the form and the name stay" -- back_new_form
    pv_step "back: the edit form: Back with the keyboard up closes the keyboard; no \"Save changes?\", the login as typed" -- back_edit_form
    pv_step "back: the lock screen's master password: Back closes the keyboard; the lock screen and what was typed stay" -- back_lock_screen
    pv_step "back: \"Set a PIN\" asks for the master password: Back closes the keyboard; the prompt and what was typed stay" -- back_mp_prompt
    pv_step "back: no crash" -- pva_no_crash
}

# ---- the looks (4.3b; API 37 and 24, debug) ----
# Each of the three styles in light and in dark, and "Phone's colours" from Android 12, chosen in
# Settings > Appearance: the style reaches the screen at once (the same process; the dialog, then
# Settings), and the lock screen with the pad, the list and an account have it; after a new process
# the lock screen still has it. A screen "has" a scheme when the colour most of its pixels have is the
# scheme's background (ui/theme/Schemes.kt); for the phone's colours, when it is none of the three
# styles' backgrounds and is as light or dark as asked. The screenshots are kept: on API 37 for the user
# in SHOTS (4A-api37-<style>-<light|dark>-<screen>.png), on API 24 in the part's logs.
LOOK_PIN=582913
declare -A LOOK_BG=([BANK_BLUE.LIGHT]=f8f9ff [BANK_BLUE.DARK]=111318 [EMERALD.LIGHT]=f4f8f6 [EMERALD.DARK]=0f1413
    [NIGHT_VAULT.LIGHT]=f6fafc [NIGHT_VAULT.DARK]=0e1216)
LOOK_DIR=""

# dominant FILE: the colour most pixels of the screenshot have, between the status bar and the
# navigation bar, as rrggbb.
dominant() {
    python3 - "$1" <<'EOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
box = im.crop((0, int(h * 0.06), w, int(h * 0.92))).resize((w // 4, int(h * 0.86) // 4), Image.NEAREST)
count, rgb = max(box.getcolors(maxcolors=1 << 20))
print("%02x%02x%02x" % rgb)
EOF
}

# look_has STYLE THEME FILE: the screenshot FILE has the scheme of STYLE in THEME (above).
look_has() {
    local got want
    got=$(dominant "$3") || return 1
    if [ "$1" = PHONE_COLOURS ]; then
        python3 - "$got" "$2" "${LOOK_BG[BANK_BLUE.$2]}" "${LOOK_BG[EMERALD.$2]}" "${LOOK_BG[NIGHT_VAULT.$2]}" <<'EOF' || { pv_log "${3##*/}: the phone's colours in $2 should not be $got"; return 1; }
import sys
got, theme, *styles = sys.argv[1:]
r, g, b = (int(got[i:i + 2], 16) / 255 for i in (0, 2, 4))
luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
ok = got not in styles and (luma < 0.25 if theme == "DARK" else luma > 0.75)
print(f"   {theme} phone's colours: {got} (luma {luma:.2f})")
sys.exit(0 if ok else 1)
EOF
        return
    fi
    want=${LOOK_BG[$1.$2]}
    [ "$got" = "$want" ] || { pv_log "${3##*/}: most pixels are $got, not $want ($1, $2)"; return 1; }
}

# look_file STYLE THEME SCREEN: where the screenshot of SCREEN goes.
look_file() {
    local style
    style=$(printf '%s' "$1" | tr 'A-Z_' 'a-z-')
    printf '%s/4A-api%s-%s-%s-%s.png' "$LOOK_DIR" "$API" "$style" "$(printf '%s' "$2" | tr 'A-Z' 'a-z')" "$3"
}

# look_screen STYLE THEME SCREEN: the screen as it is now is shot and has the scheme.
look_screen() {
    local file
    file=$(look_file "$1" "$2" "$3")
    pv_sleep 1
    pva_screenshot "$file" && look_has "$1" "$2" "$file"
}

# choose_look STYLE THEME: Settings > Appearance, the style and light or dark, Done; Settings has the
# scheme at once, in the same process.
choose_look() {
    local pid
    pid=$(pva_pid)
    pva_tap pv.tab.Settings && pva_on Settings 20 || return 1
    pva_reveal pv.open.Theme && pva_tap pv.open.Theme && pva_need "pv.settings.style.$1" 10 || return 1
    pva_tap "pv.settings.style.$1" && pv_sleep 1 && pva_tap "pv.settings.theme.$2" && pv_sleep 1 || return 1
    pva_tap pv.settings.dialog.done && pva_on Settings 10 || return 1
    [ "$(pva_pid)" = "$pid" ] || { pv_log "the process changed: $pid -> $(pva_pid)"; return 1; }
    look_screen "$1" "$2" settings
}

# look_walk STYLE THEME: the list, an account and the pad have the scheme; the PIN opens the list.
look_walk() {
    local row
    pva_tap pv.tab.Passwords && pva_on Passwords 20 && look_screen "$1" "$2" list || return 1
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
    pva_tap "$row" && pva_on Entry 20 && look_screen "$1" "$2" account || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    lock_to_pad && look_screen "$1" "$2" pad || return 1
    pin_opens "$LOOK_PIN" && settle_open
}

# look_new_process STYLE THEME: am force-stop and start: the lock screen has the scheme; the PIN opens.
look_new_process() {
    local file
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pva_start && pva_on Locked 30 && pva_need pv.lock.pad 20 || return 1
    file=$(look_file "$1" "$2" pad-new-process)
    pv_sleep 1
    pva_screenshot "$file" && look_has "$1" "$2" "$file" || return 1
    rm -f "$file"
    pin_opens "$LOOK_PIN" && settle_open
}

look_reset() {
    pva_tap pv.tab.Settings && pva_on Settings 20 && pva_reveal pv.open.Theme && pva_tap pv.open.Theme || return 1
    pva_tap pv.settings.style.BANK_BLUE && pv_sleep 1 && pva_tap pv.settings.theme.SYSTEM && pv_sleep 1 && pva_tap pv.settings.dialog.done && pva_on Settings 10
}

looks() {
    T=looks
    local style theme styles=(BANK_BLUE EMERALD NIGHT_VAULT)
    [ "$API" -ge 31 ] && styles+=(PHONE_COLOURS)
    if [ "$API" = 37 ]; then LOOK_DIR=$SHOTS; else LOOK_DIR=$LOGS_ROOT/looks-api$API; fi
    mkdir -p "$LOOK_DIR"
    pv_step "looks: the $DEV_VARIANT build installs (fresh); setup with a PIN, no fingerprint" -- eval 'pva_install "$DEV_APK" && pva_start && set_up_to_pin && pva_choose_pin "$LOOK_PIN" && pva_after_pin notNow' || return 1
    STATE=pin
    pv_step "looks: three accounts" -- eval 'pva_add_account Gmail aziz.karimov@gmail.com Tr0ub4dor3x https://mail.google.com "Shaxsiy pochta" && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_add_account Payme +998901234567 Samarqand2024x https://payme.uz Karta && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_add_account Uzcard dilshod.m Kv7Lp3Qw9Zt2 https://uzcard.uz "Bank karta" && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords'
    pv_step "looks: a new install starts in Bank blue (light, as the device is)" -- eval 'pva_screenshot "$LOGS_ROOT/looks-default-api$API.png" && look_has BANK_BLUE LIGHT "$LOGS_ROOT/looks-default-api$API.png"'
    if [ "$API" -lt 31 ]; then
        pv_step "looks: \"Phone's colours\" is not offered below Android 12" -- eval 'pva_tap pv.tab.Settings && pva_on Settings 20 && pva_reveal pv.open.Theme && pva_tap pv.open.Theme && pva_need pv.settings.style.BANK_BLUE 10 && pva_absent pv.settings.style.PHONE_COLOURS && pva_tap pv.settings.dialog.done && pva_on Settings 10'
    fi
    for style in "${styles[@]}"; do
        for theme in LIGHT DARK; do
            pv_step "looks: $style, $theme: chosen in Settings > Appearance, Settings has it at once (the same process)" -- choose_look "$style" "$theme"
            pv_step "looks: $style, $theme: the list, an account and the lock screen's pad have it" -- look_walk "$style" "$theme"
        done
        pv_step "looks: $style, DARK: after a new process the lock screen still has it" -- look_new_process "$style" DARK
    done
    pv_step "looks: back to Bank blue, light or dark as the phone is" -- look_reset
    pv_step "looks: no crash" -- pva_no_crash
    ls "$LOOK_DIR" | grep "^4A-api$API-" | sed 's/^/    | /' | head -60
}

# ---- a first walk of the wave-5 screens that are merged (API 37, debug) ----
# Not part of the 4A check (wave 5 has its own after 5.5): the part wave5 runs only when named, and what
# fails here is reported, not a reason to hold the tag. Through the system's own picker (DocumentsUI):
# a backup into Download, each export (CSV, JSON, Excel), an import of a small CSV, a restore from the
# lock screen ("Forgot master password?" > "Restore from a backup file"), which keeps the lock delay
# (the owner's decision 13), and Old passwords with the vault that the restore put aside.
W5_DIR=/sdcard/Download
W5_CSV=pv-4A-import.csv

# picker_tap REGEX [SECONDS]: taps the first enabled node of the system's picker whose text matches
# REGEX (anchored by the caller), once it is there.
picker_tap() {
    local deadline=$((SECONDS + ${2:-30})) nodes
    while :; do
        nodes=$(pv_ui_nodes_all 2>/dev/null)
        if [ -n "$(printf '%s\n' "$nodes" | pv_ui_match "$1")" ]; then
            pv_ui_tap "$1" "$nodes" >/dev/null && return 0
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "no /$1/ in the picker; focus $(pv_focus); texts: $(printf '%s\n' "$nodes" | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-500)"; return 1; }
        pv_sleep 1
    done
}

# picker_save: the picker that "Save as" opened: Download as the place, then SAVE.
picker_save() {
    pv_ui_wait '^(SAVE|Save)[|]' 30 >/dev/null 2>&1 || picker_tap '^(SAVE|Save)[|]' 1 >/dev/null 2>&1
    pv_sleep 3
    picker_tap '^(SAVE|Save)[|]' 30
}

# picker_open NAME: the picker that "Open" opened: the file NAME (from Recent, or from Downloads).
picker_open() {
    pv_sleep 3
    picker_tap "^$(pva_re "$1")[|]" 10 && return 0
    picker_tap '^(Show roots|Downloads)[|]' 10 && pv_sleep 2
    picker_tap '^Downloads[|]' 5 >/dev/null 2>&1
    pv_sleep 2
    picker_tap "^$(pva_re "$1")[|]" 20
}

in_download() { pv_shell ls "$W5_DIR" 2>/dev/null | tr -d '\r' | grep -E -- "$1"; }

# export_holds REGEX: the export in Download whose name matches REGEX, pulled, opens as its format
# (CSV with a header, JSON, an Excel workbook) and holds both accounts of the walk with their logins.
export_holds() {
    local name local_file
    name=$(in_download "$1" | head -n 1)
    [ -n "$name" ] || { pv_log "no file /$1/ in Download"; return 1; }
    local_file=$LOGS_ROOT/wave5-$name
    pv_adb pull "$W5_DIR/$name" "$local_file" >/dev/null 2>&1 || { pv_log "could not pull $name"; return 1; }
    python3 - "$local_file" <<'PYEOF'
import csv, io, json, sys, zipfile
path = sys.argv[1]
want = ["Gmail", "aziz.karimov@gmail.com", "Payme", "+998901234567"]
if path.endswith(".csv"):
    rows = list(csv.reader(io.open(path, encoding="utf-8-sig", newline="")))
    text = "\n".join(",".join(r) for r in rows)
    kind = f"CSV, header {rows[0][:5]}, {len(rows) - 1} rows"
elif path.endswith(".json"):
    text = json.dumps(json.load(open(path, encoding="utf-8")), ensure_ascii=False)
    kind = "JSON"
else:
    z = zipfile.ZipFile(path)
    text = "".join(z.read(n).decode("utf-8", "replace") for n in z.namelist() if n.endswith(".xml"))
    kind = f"Excel workbook ({len(z.namelist())} parts)"
missing = [w for w in want if w not in text]
print(f"    | {path.rsplit('/', 1)[1]}: {kind}; " + ("holds both accounts" if not missing else f"lacks {missing}"))
sys.exit(1 if missing else 0)
PYEOF
}

w5_settings() { pva_tap pv.tab.Settings && pva_on Settings 20; }

# set_lock_delay DELAY: Settings > "Lock after", the dialog's choice DELAY (LockDelay), Done.
set_lock_delay() {
    w5_settings && pva_reveal pv.settings.lockDelay && pva_tap pv.settings.lockDelay || return 1
    pva_need "pv.settings.lockDelay.$1" 10 && pva_tap "pv.settings.lockDelay.$1" || return 1
    pv_sleep 1
    if pva_present pv.settings.dialog.done 2; then pva_tap pv.settings.dialog.done; fi
    pva_on Settings 10
}

lock_delay_stored() { pv_shell run-as "$PV_PACKAGE" cat shared_prefs/pv_settings.xml 2>/dev/null | tr -d '\r' | grep -q "<string name=\"lock_delay\">$1</string>"; }

w5_backup() {
    w5_settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    if pva_present pv.backup.continue 3; then pva_tap pv.backup.continue || return 1; fi
    pva_need pv.backup.password 20 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit || return 1
    picker_save || return 1
    pva_need pv.backup.done 60 || return 1
    pv_log "the backup's last screen: $(pva_text pv.backup.line.0 2>/dev/null)"
    pva_tap pv.backup.done && pva_on Settings 20
}

# w5_export csv|json|xlsx
w5_export() {
    w5_settings && pva_reveal pv.open.Export && pva_tap pv.open.Export && pva_on Export 20 || return 1
    pva_need "pv.export.choice.$1" 15 && pva_tap "pv.export.choice.$1" && pva_tap pv.export.continue || return 1
    if pva_present pv.export.understand 10; then pva_tap pv.export.understand || return 1; fi
    if pva_present pv.export.warning.continue 5; then pva_tap pv.export.warning.continue || return 1; fi
    pva_need pv.export.password 20 && pva_type pv.export.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.export.submit || return 1
    picker_save || return 1
    pva_need pv.export.saved 60 || return 1
    pv_log "the export's last screen: $(pva_text pv.export.saved 2>/dev/null)"
    pva_tap pv.export.done && pva_on Settings 20
}

w5_import() {
    local before
    printf 'name,url,username,password,note\r\nTashkent Metro,https://metro.uz,dilshod.m,Qx7mVb2Lp9Ws,yo%sl kartasi\r\nKapitalbank,https://kapitalbank.uz,+998901112233,Hn4Rt8Ws2Kz6,\r\n' 'ʻ' > "$LOGS_ROOT/$W5_CSV"
    pv_adb push "$LOGS_ROOT/$W5_CSV" "$W5_DIR/$W5_CSV" >/dev/null || return 1
    pv_shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1
    before=$(pva_row_titles | wc -l)
    w5_settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20 || return 1
    pva_need pv.import.source.csv 20 && pva_tap pv.import.source.csv || return 1
    picker_open "$W5_CSV" || return 1
    pva_need pv.import.import 60 || { pv_log "no Import button; ids: $(pva_ids)"; return 1; }
    pv_log "the preview: $(pva_text pv.import.found 2>/dev/null) $(pva_text pv.import.encoding 2>/dev/null)"
    pva_tap pv.import.import && pva_need pv.import.done 60 || return 1
    pv_log "the import: $(pva_text pv.import.done 2>/dev/null)"
    # "Delete it now?" about the file that holds the passwords unprotected: Delete file.
    if pva_present pv.import.deleteFile 5; then pva_tap pv.import.deleteFile && pva_need pv.import.fileOutcome 20 || return 1; fi
    pva_tap pv.import.finish && pva_on Settings 20 || return 1
    [ -z "$(in_download "^$(pva_re "$W5_CSV")\$")" ] || { pv_log "the imported file is still in Download"; return 1; }
    pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_row_with "Tashkent Metro" 15 && pva_row_with Kapitalbank 15
}

# w5_restore NAME: from the list: Lock now; the master password screen's "Forgot master password?",
# "Restore from a backup file", the backup NAME, its master password, Restore; the PIN steps that follow
# are answered "Not now".
w5_restore() {
    pva_lock_now && pva_need pv.lock.password 20 || return 1
    pva_tap pv.lock.forgot && pva_need pv.lock.sheet 10 && pva_tap pv.lock.sheet.restore || return 1
    pva_on Restore 20 && pva_need pv.backup.choose 20 && pva_tap pv.backup.choose || return 1
    picker_open "$1" || return 1
    pva_need pv.backup.password 30 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.restore 60 || return 1
    pv_log "the restore's summary: $(pva_text pv.backup.summary 2>/dev/null) / $(pva_text pv.backup.stays 2>/dev/null)"
    pva_tap pv.backup.restore && pva_unlocked Passwords 120 && pva_settle Passwords 60
}

# w5_old: Settings > "Old passwords" lists the vault the restore put aside; its "Open" with that
# vault's master password adds its accounts (the two imported ones) to the current vault.
w5_old() {
    local open
    w5_settings && pva_reveal pv.open.OldPasswords && pva_tap pv.open.OldPasswords && pva_on OldPasswords 20 || return 1
    pva_need pv.backup.old.list 15 || return 1
    open=$(pv_ui_nodes | grep -o 'pv\.backup\.old\.open\.[0-9a-f-]*' | head -n 1)
    [ -n "$open" ] || { pv_log "no vault in Old passwords; ids: $(pva_ids)"; return 1; }
    pv_log "Old passwords: $(pv_ui_nodes | grep -A 1 '|pv\.backup\.old\.item\.' | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-200)"
    pva_tap "$open" && pva_need pv.backup.password 20 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.old.done 60 || return 1
    pv_log "$(pva_text pv.backup.old.done)"
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Settings 20 && pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_row_with "Tashkent Metro" 15 && pva_row_with Kapitalbank 15
}

wave5() {
    T=wave5
    local backup
    pv_step "wave5: the $DEV_VARIANT build installs (fresh); setup leaves the PIN off; two accounts" -- eval 'pva_install "$DEV_APK" && pva_start && pva_set_up && pva_add_account Gmail aziz.karimov@gmail.com Tr0ub4dor3x https://mail.google.com "Shaxsiy pochta" && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_add_account Payme +998901234567 Samarqand2024x https://payme.uz Karta && pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords' || return 1
    STATE=none
    pv_shell "rm -f $W5_DIR/PasswordVault-backup-*.pvault $W5_DIR/passwords-*.csv $W5_DIR/passwords-*.json $W5_DIR/passwords-*.xlsx $W5_DIR/$W5_CSV" >/dev/null 2>&1
    pv_step "wave5: the lock delay is set to 5 minutes in Settings" -- eval 'set_lock_delay MINUTES_5 && lock_delay_stored 5m'
    pv_step "wave5: a backup into Download (its master password, the system's Save)" -- w5_backup
    backup=$(in_download '^PasswordVault-backup-.*\.pvault$' | head -n 1)
    pv_step "wave5: the backup's file is in Download: ${backup:-none}" -- [ -n "$backup" ]
    pv_step "wave5: export CSV into Download; the file holds both accounts" -- eval 'w5_export csv && export_holds "^passwords-.*\.csv$"'
    pv_step "wave5: export JSON into Download; the file holds both accounts" -- eval 'w5_export json && export_holds "^passwords-.*\.json$"'
    pv_step "wave5: export Excel into Download; the workbook holds both accounts" -- eval 'w5_export xlsx && export_holds "^passwords-.*\.xlsx$"'
    pv_shell ls -l "$W5_DIR" 2>/dev/null | tr -d '\r' | sed 's/^/    | /'
    pv_step "wave5: import of a small CSV (two accounts) from Download" -- w5_import
    pv_step "wave5: a restore from the lock screen with the backup" -- w5_restore "$backup"
    pv_step "wave5: the restore kept the lock delay (decision 13): 5 minutes in pv_settings" -- lock_delay_stored 5m
    pv_step "wave5: after the restore the list has the backup's accounts only" -- eval 'pva_on Passwords 20 && pva_row_with Gmail 10 && [ -z "$(pva_row_titles | grep -x "Tashkent Metro")" ]'
    pv_step "wave5: Old passwords lists the vault the restore put aside; Open adds its accounts" -- w5_old
    pv_step "wave5: no crash" -- pva_no_crash
}

# dev_run SERIAL VARIANT APK BASE CHECK...: the checks on one device, then its summary. The parts run
# it in a subshell of this script, which would inherit this script's own counts: they start from 0.
dev_run() {
    local check
    PV_PASSED=0
    PV_FAILED=0
    PV_SKIPPED=0
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
            plan | e1 | e2 | e3 | e6 | e11 | f14 | back | looks | wave5) "$check" ;;
            *) pv_die "unknown check $check" 2 ;;
        esac
    done
    pva_rotate 0
    pv_summary
}

# ---- the parts that run the checks above on several devices --------------------------------------------
plan_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" plan; }
e_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" e1 e2 e3 e6 e11; }
e_staging() { dev_run "$1" staging "$STAGING_APK" "$BASE_STAGING" e1 e2 e3 e6 e11; }
update_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" f14; }
update_staging() { dev_run "$1" staging "$STAGING_APK" "$BASE_STAGING" f14; }
back_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" back; }
looks_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" looks; }
wave5_debug() { dev_run "$1" debug "$DEBUG_APK" "$BASE_DEBUG" wave5; }

# base_apks: the debug and staging APKs of wave-3C-merged (the master-password gate of 3.2, no lock
# records), built once from the tag in a throwaway worktree and kept in LOGS_ROOT.
base_apks() {
    [ -s "$BASE_DEBUG" ] && [ -s "$BASE_STAGING" ] && { echo "   kept from an earlier run: ${BASE_DEBUG##*/}, ${BASE_STAGING##*/}"; return 0; }
    local tree rc
    tree=$(mktemp -d "${TMPDIR:-/tmp}/pv-4A-base.XXXXXX") && rmdir "$tree" || return 1
    git -C "$REPO" worktree add --detach "$tree" wave-3C-merged > "$LOGS/base-worktree.log" 2>&1 || { cat "$LOGS/base-worktree.log"; return 1; }
    (cd "$tree" && tools/gradle.sh :app:assembleDebug :app:assembleStaging) > "$LOGS/base-build.log" 2>&1
    rc=$?
    grep -E '^BUILD' "$LOGS/base-build.log" | sed 's/^/   wave-3C-merged: /'
    if [ $rc -eq 0 ]; then
        cp "$tree/app/build/outputs/apk/debug/app-debug.apk" "$BASE_DEBUG" && cp "$tree/app/build/outputs/apk/staging/app-staging.apk" "$BASE_STAGING" || rc=1
    fi
    git -C "$REPO" worktree remove --force "$tree" >> "$LOGS/base-worktree.log" 2>&1
    return $rc
}

skeleton_debug() { "$EMU/checks/3A.sh" skeleton debug "$1" --apk "$DEBUG_APK"; }
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1" --apk "$STAGING_APK"; }
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }

# The scripts of the packages that item 4 of the merge of R3 changed for the real lock and never ran on
# a device, one after another on one device with the debug build: shell/walk.sh (2.5; the PIN steps
# after setup, Settings' rows and the dialog "Appearance", the PIN after Lock now), setup/create.sh
# --vault-file (3.3) and session/autolock.sh (3.2); on API 37 walk.sh with the staging build too.
extras_one() {
    local s=$1 bad=0 api script
    api=$(PV_SERIAL=$s pv_api)
    "$EMU/shell/walk.sh" "$s" --apk "$DEBUG_APK" > "$LOGS/walk-$s.log" 2>&1 || bad=1
    "$EMU/setup/create.sh" "$s" --apk "$DEBUG_APK" --vault-file > "$LOGS/create-$s.log" 2>&1 || bad=1
    "$EMU/session/autolock.sh" "$s" --apk "$DEBUG_APK" > "$LOGS/autolock-$s.log" 2>&1 || bad=1
    if [ "$api" = 37 ]; then
        "$EMU/shell/walk.sh" "$s" --apk "$STAGING_APK" > "$LOGS/walk-staging-$s.log" 2>&1 || bad=1
    fi
    for script in walk create autolock walk-staging; do
        [ -f "$LOGS/$script-$s.log" ] || continue
        echo "   $script.sh: $(grep -E '^SUMMARY' "$LOGS/$script-$s.log" | tail -n 1)"
        grep -E '^(FAIL|SKIPPED)' "$LOGS/$script-$s.log" | sed 's/^/     /'
    done
    return $bad
}

window_checks() {
    local began rc
    began=$(date +%s)
    "$EMU/window/all.sh" "${SER[24]}" "${SER[28]}" "${SER[37]}" > "$LOGS/all.log" 2>&1
    rc=$?
    grep -E '^(PASS|FAIL) .* \(exit|^SUMMARY window' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^KNOWN ' "$LOGS/all.log" | sort | uniq -c | sed 's/^/     /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
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

ALL_PARTS=(merged connected lock e update skeleton smoke window back looks extras)
DEVICE_PARTS=(connected lock e update skeleton smoke window back looks extras wave5)
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
            [[ " ${DEVICE_PARTS[*]} merged " == *" $1 "* ]] || pv_usage "${BASH_SOURCE[0]}"
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
        lock)
            pv_step "the test APK with its documents provider is not on the devices" -- no_test_apk
            pv_step "the plan's line for 4A, debug build, on API 24, 28 and 37: set up with a PIN and a fingerprint, unlock with each, five wrong PINs, then the master password and a new PIN" -- in_parallel plan plan_debug -- 24 28 37 ;;
        e)
            pv_step "lock.md E1, E2, E3, E6 and E11 (without its language step), debug build, on API 24, 28 and 37" -- in_parallel e-debug e_debug -- 24 28 37
            pv_step "lock.md E1, E2, E3, E6 and E11 (without its language step), staging build, on API 24, 28 and 37" -- in_parallel e-staging e_staging -- 24 28 37 ;;
        update)
            pv_step "the debug and staging APKs of wave-3C-merged (the gate of 3.2, no lock records)" -- base_apks
            pv_step "lock.md F14 after an update from wave-3C-merged, debug over debug, on API 24, 28 and 37" -- in_parallel update-debug update_debug -- 24 28 37
            pv_step "lock.md F14 after an update from wave-3C-merged, staging over staging, on API 24 and 37" -- in_parallel update-staging update_staging -- 24 37 ;;
        skeleton)
            pv_step "the skeleton steps 1 to 9 of checks/3A.sh, debug build, on API 24, 28 and 37" -- in_parallel skeleton-debug skeleton_debug -- 24 28 37
            pv_step "the skeleton steps 1 to 9 of checks/3A.sh, staging build, on API 24 and 37" -- in_parallel skeleton-staging skeleton_staging -- 24 37 ;;
        smoke)
            pv_step "smoke.sh staging on API 24 and 37, with the PIN and the fingerprint" -- in_parallel smoke smoke_staging -- 24 37 ;;
        window)
            pv_step "tools/emu/window/all.sh: debug and staging on API 28 and 37, a11y-old on API 24" -- window_checks ;;
        back)
            pv_step "Back with the keyboard up closes the keyboard and loses nothing, API 37, debug build" -- in_parallel back back_debug -- 37 ;;
        looks)
            pv_step "the looks: three styles in light and dark (and the phone's colours on API 37), at once and after a new process, debug build, on API 37 and 24" -- in_parallel looks looks_debug -- 37 24
            if ls "$SHOTS"/4A-api37-*.png > /dev/null 2>&1; then
                echo "   screenshots (API 37, debug) in $SHOTS: $(cd "$SHOTS" && ls 4A-api37-*.png | wc -l) files 4A-api37-*.png"
            fi ;;
        extras)
            pv_step "the scripts item 4 changed: walk.sh, create.sh --vault-file and autolock.sh, debug, API 24, 28 and 37; walk.sh staging on API 37" -- in_parallel extras extras_one -- 24 28 37 ;;
        wave5)
            pv_step "a first walk of the wave-5 screens, API 37, debug build (not part of the 4A check)" -- in_parallel wave5 wave5_debug -- 37 ;;
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
