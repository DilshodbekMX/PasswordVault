#!/usr/bin/env bash
# tools/emu/checks/6.sh - the device check of waves 6 and 8A, round R6 (build-plan.md section 12, "The
# integration in two steps", step 2, and its block "From the merge of R6 to round R7", "What the device
# check of waves 6 and 8A runs"). The merge ran tools/check.sh all before it tagged wave-6-merged. Round
# R6 brought the translations (6.3a and 6.3b: Uzbek in Latin and Cyrillic letters, 6.4a and 6.4b:
# Russian, each with its review) and the build hardening (8.2, the last package of 8A, whose 8.3 and 8.4
# came with R5).
#
#   tools/emu/checks/6.sh [PART...] [--levels 24,28,37] [--shots DIR] [--keep] [--no-build] [-- GRADLE-ARGS...]
#
# The parts run in this order, all but pictures when none is named, on the integrator's devices (5584
# is API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used
# as it is, the others are booted together (boot.sh --integrator). --levels limits every part to those
# API levels (a part runs on the levels it has among them; a part run on some levels logs to
# PART-api-L-L/), so that parts can share the three devices in any order from several runs at once.
# The debug, staging and backupCheck APKs and the test APK are built first (tools/gradle.sh; not with
# --no-build) and copied to LOGS/apk/, from where every part installs them: Android Studio, which has
# this worktree open, rebuilds app/build/outputs/apk/debug/ when its Run is pressed. The devices are
# stopped at the end unless --keep is given; the part "stop" does nothing else.
#   merged      the heads that round R6 merged are in HEAD: w6B-6.3a, w6B-6.3b, w6B-6.4a, w6B-6.4b and
#               w8A-8.2.
#   connected   adb.sh --connected on the devices, in touch mode (each device gets a tap on its launcher
#               first, as in checks/6A.sh): every connected test, and every serial has a passing report.
#               A class that failed is run once more, alone, on that device (one run per class); it
#               counts as passed when it passes alone, and the step names it as a flake. Then every
#               device-test class ran on each device with no failure and at least the tests it has
#               there: 583 on API 24, 585 on 28, 596 on 37, as at wave-6A-merged (R6 added no device
#               test: git diff wave-6A-merged -- app/src/androidTest is iface-165's one line). The devices
#               run in English, where the translations change no text.
#   i18n        6.1's [script] lines on API 24 and 37: tools/emu/i18n/all.sh (matrix.sh with the parts of
#               the level, then e11.sh), the debug build. The merge of R6 changed matrix.sh's step
#               "mixing" (API 33 and later), which runs here first: with the phone set to uz-Cyrl-UZ then
#               ru-RU the bottom sheet's drag handle is the APK's (b+uz+Cyrl) text "Суриш дастаги"
#               (6.3b's override). KNOWN on API 37: Android 17's App language page never marks Ўзбекча.
#   smoke       smoke.sh staging on API 24 and 37, with its stages of 3A, 3B, 4A and wave 5 and, since
#               this check, its language stage (Settings › Language › Русский, then Ўзбекча, each with
#               the same process and screen, the tabs in that language, an account added and opened,
#               Lock now and the PIN; then System language and English again).
#   staging     8A, 8.2's line "the staging build passes the whole of tools/emu/" in the scope of round
#               R6, with the merged staging APK: checks/3A.sh skeleton staging on API 24 and 37 (step 3
#               needs run-as: SKIPPED); lock/all.sh with the staging APK on the levels 24 and 37 (every
#               check of those levels; a SKIPPED step needs run-as or root); session/autolock.sh,
#               setup/create.sh and clipboard/clear.sh on API 24; shell/walk.sh on API 37. smoke.sh
#               staging and window/all.sh with staging are the parts smoke and window.
#   window      tools/emu/window/all.sh (architecture.md 8.4: at every integration check): keyboard,
#               autofill, overlay, state and a11y with the debug and the staging APK on API 28 and 37,
#               a11y-old with both on API 24 (22 scripts). all.sh clears its own log folder when it
#               starts, so this part runs it once for every level it has.
#   walk        6.2's walk in the four real languages (i18n.md 4.5): tools/emu/layout/walk.sh on API 37,
#               its part app in en, uz, uz-Cyrl and ru (the app's own tags, AppLanguages) at 320 dp and
#               200 %, each language from a fresh install. The walk leaves out its English check in a
#               real language. Every finding is sorted: Russian's automatic hyphenation, which the probe
#               counts as "a word split across two lines" although the hyphen is drawn, and in Ўзбекча
#               the snackbar "Olx ўчириб ташланди" beside "Қайтариш" (Entry-deleted) are KNOWN (build
#               plan 12, the open problems of R6, 8.1's); anything else fails the step.
#   pictures    only when named: the first picture set for the owner (build plan 12; A 8.6 item 8):
#               tools/emu/checks/6-pictures.sh on API 37 with the debug build, a picture of every screen
#               in en, uz-Latn, uz-Cyrl and ru, gathered under DIR/<language>/ with an index (see that
#               script); on API 24 androidx.biometric's own fingerprint dialog in each language.
#
# After a part that drives the app, a NOTE names every Run that Android Studio started on one of the
# devices meanwhile (its idea.log; tools/emu/README.md, "Device facts"): such a Run stops, reinstalls
# and starts the app under whatever step is running.
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/6/, the picture set in
# DIR (default /home/asus/pvwork/shots-langs/final). Exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial. Written by the device check of waves 6 and 8A (build-plan.md
# section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SHOTS=/home/asus/pvwork/shots-langs/final
LOGS_ROOT=$REPO/build/emu-checks/6
APK_DIR=$LOGS_ROOT/apk
DEBUG_APK=$APK_DIR/app-debug.apk
STAGING_APK=$APK_DIR/app-staging.apk
TEST_APK=$APK_DIR/app-debug-androidTest.apk
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug
RUNNER_ARG=-Pandroid.testInstrumentationRunnerArguments

# ---- merged ------------------------------------------------------------------------------------------
# BRANCH:HEAD as round R6 merged them (the merge commits 8ba5cd4, 15bd178, 86ea5f3, 427efdc and 0670af3
# have them as their second parents).
MERGED=(w6B-6.3a:f4ef21a w6B-6.3b:4f95cdf w6B-6.4a:bc69f0b w6B-6.4b:681a65d w8A-8.2:e664ade)

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

# The APKs, built (unless --no-build) and copied to APK_DIR. One log per run's levels: runs of other
# levels may start at the same time, and only one of them should build (--no-build for the others).
build_apks() {
    local rc=0 f
    if [ "$no_build" = 0 ]; then
        "$REPO/tools/gradle.sh" :app:assembleDebug :app:assembleStaging :app:assembleBackupCheck :app:assembleDebugAndroidTest \
            ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS_ROOT/assemble$LEVELS_SUFFIX.log" 2>&1
        rc=$?
        grep -aE '^BUILD' "$LOGS_ROOT/assemble$LEVELS_SUFFIX.log" | sed 's/^/   /'
        [ $rc -eq 0 ] || return $rc
        mkdir -p "$APK_DIR"
        cp -f "$REPO/app/build/outputs/apk/debug/app-debug.apk" "$DEBUG_APK" \
            && cp -f "$REPO/app/build/outputs/apk/staging/app-staging.apk" "$STAGING_APK" \
            && cp -f "$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$TEST_APK" || return 1
        git -C "$REPO" rev-parse --short HEAD > "$APK_DIR/commit"
    fi
    for f in "$DEBUG_APK" "$STAGING_APK" "$TEST_APK"; do
        [ -f "$f" ] || { echo "   no $f (run once without --no-build)"; return 1; }
    done
    echo "   the APKs in ${APK_DIR#"$REPO"/}, built at $(cat "$APK_DIR/commit" 2> /dev/null || echo '?') (HEAD is $(git -C "$REPO" rev-parse --short HEAD))"
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

# api_of SERIAL: the API level of one of the integrator's serials.
api_of() {
    local api
    for api in "${!SER[@]}"; do [ "${SER[$api]}" = "$1" ] && { echo "$api"; return 0; }; done
    PV_SERIAL=$1 pv_api
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

# in_parallel [--quiet] NAME COMMAND-WORDS... -- API...: COMMAND SERIAL on each device of those levels
# that is among --levels, at once, its output in LOGS/NAME-API.log; prints each device's step lines (with
# --quiet only those that did not pass, the summary and the lines the command indents), and the Runs
# Android Studio made meanwhile. Nothing to run (no such level) is a pass with a line that says so.
in_parallel() {
    local quiet=0 name api rc=0 status cmd=() began levels pattern
    [ "$1" = --quiet ] && { quiet=1; shift; }
    name=$1
    shift
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do cmd+=("$1"); shift; done
    shift
    levels=$(levels_of "$@")
    [ -n "$levels" ] || { echo "   $name: none of API $* is among --levels"; return 0; }
    pattern='^(PASS|FAIL|SKIPPED|KNOWN|SUMMARY|NOTE|   )'
    [ $quiet = 0 ] || pattern='^(FAIL|SKIPPED|KNOWN|SUMMARY|NOTE|   )'
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
        grep -E "$pattern" "$LOGS/$name-$api.log" | sed 's/^/     /'
        [ $status -eq 0 ] || rc=1
    done
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# The test APK exports a documents provider, a picker and a receiver while it is installed; AGP removes
# it after a run, the walk after its own, and a run that was cut off may not have.
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
# of keyboard mode, as a phone in the hand is; prints each device's mode as dumpsys reads it. Android 14
# and later leave the display in keyboard mode after the shell's tap (a tap changes it only for the owner
# of the focused window; the device checks of R4 and 6A saw a freshly booted API 37 so): there an
# instrumentation puts it in touch mode, FieldKeyboardTest's one test, which calls
# Instrumentation.setInTouchMode(true) first (adb.sh --connected removes both APKs again before its run).
touch_mode() {
    local s h mode
    for s in "$@"; do
        h=$(PV_SERIAL=$s pv_shell wm size 2> /dev/null | tr -d '\r' | sed -n 's/^Physical size: *[0-9]*x\([0-9]*\)$/\1/p' | head -n 1)
        PV_SERIAL=$s pv_shell input keyevent KEYCODE_HOME > /dev/null 2>&1
        sleep 1
        PV_SERIAL=$s pv_shell input tap 540 $((${h:-1920} * 45 / 100)) > /dev/null 2>&1
        sleep 1
        mode=$(PV_SERIAL=$s pv_shell dumpsys window 2> /dev/null | tr -d '\r' | grep -m 1 -o 'mInTouchMode=[a-z]*')
        if [ "$mode" = mInTouchMode=false ] && [ -f "$DEBUG_APK" ] && [ -f "$TEST_APK" ]; then
            (
                pv_use "$s"
                pva_install "$DEBUG_APK" > /dev/null 2>&1 && pv_adb install -r -t "$TEST_APK" > /dev/null 2>&1 \
                    && pv_adb shell am instrument -w -e class "$PV_PACKAGE.ui.common.FieldKeyboardTest" "$PV_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > /dev/null 2>&1
            )
            echo "   $s: keyboard mode after the shell's tap; FieldKeyboardTest run to set touch mode"
        fi
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

# Each device-test class with its package and the tests it has on API 24, 28 and 37: checks/6A.sh's
# table at wave-6A-merged (583, 585 and 596, counted from the sources with their @SdkSuppress filters),
# which R6 left as it was (it added no device test; iface-165 changed one line of SecurityScreenshotsTest).
# A class run again alone (connected_tests) counts with that run's result. Only the levels among
# --levels are judged.
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
    "ui.lock.LockPseudoLocaleTest": ("4.2", 9, 9, 9),
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
    "ui.transfer.import.ImportScreenTest": ("5.4", 8, 8, 8),
    "ui.transfer.import.ImportScreenshotsTest": ("5.4", 0, 0, 1),
    "i18n.RememberFormattersTest": ("w5-carry", 1, 1, 1),
    "ui.common.FieldKeyboardTest": ("w5-carry", 1, 1, 1),
    "ui.common.KeyboardFirstOnBackTest": ("w5-carry", 0, 0, 2),
    "ui.theme.LookDetailsTest": ("w5-carry", 6, 6, 6),
    "ui.theme.LookPseudoLocaleTest": ("w5-carry", 1, 1, 1),
    "window.ObscuredTouchNoticeLookTest": ("w5-carry", 0, 1, 1),
    "ui.settings.master.MasterPasswordRotationTest": ("5.5", 3, 3, 3),
    "ui.settings.master.MasterPasswordScreensTest": ("5.5", 14, 14, 14),
    "ui.settings.master.MasterPseudoLocaleTest": ("5.5", 3, 3, 3),
    "ui.settings.master.MasterScreenshotsTest": ("5.5", 2, 2, 2),
    "ui.settings.LanguageDialogTest": ("6.1", 11, 11, 11),
    "ui.settings.LanguageSwitchTest": ("6.1", 1, 1, 1),
    "ui.common.LongTextLayoutTest": ("6.2", 13, 13, 13),
    "ui.layout.LayoutWalkTest": ("6.2", 1, 1, 1),
    "ui.settings.master.MasterPageKeyboardTest": ("6.2", 0, 0, 1),
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

# ---- i18n: 6.1's [script] lines ----------------------------------------------------------------------------
I18N_OUT=$REPO/build/emu-i18n/all

# i18n/all.sh on API 24 and 37 (those among --levels); its logs (build/emu-i18n/all/, which every run
# shares) copied to LOGS/all/.
i18n_all() {
    local rc began serials
    serials=$(serials_of 24 37)
    [ -n "$serials" ] || { echo "   none of API 24 and 37 is among --levels"; return 0; }
    began=$(date +%s)
    # shellcheck disable=SC2086 # the serials are words
    "$EMU/i18n/all.sh" --apk "$DEBUG_APK" $serials > "$LOGS/all.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/all"
    cp -r "$I18N_OUT/." "$LOGS/all/" 2> /dev/null
    grep -E '^(PASS|FAIL) [a-z0-9]+\.sh|^SUMMARY' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^   (FAIL|KNOWN) ' "$LOGS/all.log" | sed 's/^/  /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# mixing_ran: on API 37 (when among --levels) matrix.sh's step that the merge of R6 changed ran and
# passed: the drag handle is the app's own Ўзбекча text.
mixing_ran() {
    local log
    has_level 37 || { echo "   API 37 is not among --levels"; return 0; }
    log=$LOGS/all/${SER[37]}-matrix.log
    grep -E '^(PASS|FAIL|SKIPPED) mixing' "$log" | sed 's/^/   /'
    grep -E 'the drag handle says' "$log" | sed 's/^.*the drag handle says/   the drag handle says/'
    grep -q '^PASS mixing: a library text without Uzbek Cyrillic is the app.s own Ўзбекча, not Russian' "$log"
}

# ---- smoke, staging ----------------------------------------------------------------------------------------
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1" --apk "$STAGING_APK"; }
autolock_staging() { "$EMU/session/autolock.sh" "$1" --apk "$STAGING_APK"; }
create_staging() { "$EMU/setup/create.sh" "$1" --apk "$STAGING_APK"; }
clear_staging() { "$EMU/clipboard/clear.sh" "$1" --apk "$STAGING_APK"; }
walk_staging() { "$EMU/shell/walk.sh" "$1" --apk "$STAGING_APK"; }

# summary_without_failures NAME API...: the logs LOGS/NAME-API.log end with a summary of 0 failed.
summary_without_failures() {
    local name=$1 api rc=0 line
    shift
    for api in $(levels_of "$@"); do
        line=$(grep -E '^SUMMARY ' "$LOGS/$name-$api.log" | tail -n 1)
        echo "   $name on API $api: ${line:-no summary}"
        [[ "$line" == *", 0 failed,"* ]] || rc=1
    done
    return $rc
}

LOCK_OUT=$REPO/build/emu-lock/all

# lock_staging: lock/all.sh with the staging APK on the levels 24 and 37 (those among --levels): every
# check of those levels (lock.md 15.3); E15 builds and uses its backupCheck variant. Its table and logs
# are copied to LOGS/lock/ (all.sh keeps only its last run). A SKIPPED step needs run-as or root, which
# staging and the API 37 image do not give: the step lists them.
lock_staging() {
    local levels rc began
    levels=$(levels_of 24 37)
    [ -n "$levels" ] || { echo "   none of API 24 and 37 is among --levels"; return 0; }
    began=$(date +%s)
    # shellcheck disable=SC2046,SC2086 # the levels and serials are words
    "$EMU/lock/all.sh" --apk "$STAGING_APK" --levels "${levels// /,}" $(serials_of $levels) > "$LOGS/lock.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/lock"
    cp -r "$LOCK_OUT/." "$LOGS/lock/" 2> /dev/null
    sed -n '/^SUMMARY lock checks/,$p' "$LOGS/lock.log" | sed 's/^/   /'
    grep -h '^SKIPPED' "$LOGS"/lock/emulator-*-E*.log 2> /dev/null | sed 's/ (.*//' | sort | uniq -c | sed 's/^/     /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- window ------------------------------------------------------------------------------------------------
# window/all.sh once on the devices of --levels: on API 28 and 37 keyboard, autofill, overlay, state and
# a11y with the debug and the staging APK, on API 24 a11y-old with both (architecture.md 8.4). Its logs
# (build/window/all/, cleared by each all.sh) are copied to LOGS/all/.
window_checks() {
    local began rc
    began=$(date +%s)
    # shellcheck disable=SC2046 # the serials are words
    "$EMU/window/all.sh" --apk "$DEBUG_APK" --apk "$STAGING_APK" $(serials_of 24 28 37) > "$LOGS/all.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/all"
    cp -r "$REPO/build/window/all/." "$LOGS/all/" 2> /dev/null
    grep -E '^(PASS|FAIL) .* \(exit|^SUMMARY window' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^KNOWN ' "$LOGS/all.log" | sort | uniq -c | sed 's/^/     /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- walk: 6.2's walk in the four real languages -------------------------------------------------------------
# The app's own tags (AppLanguages): English, Uzbek in Latin letters, Uzbek in Cyrillic letters, Russian.
WALK_LOCALES=en,uz,uz-Cyrl,ru

# real_walk SERIAL: tools/emu/layout/walk.sh, the part app in the four languages at 320 dp and 200 %, its
# output in LOGS/walk.log; build/emu-layout/SERIAL (the walk's logs, reports and pictures) is copied to
# LOGS/walk/. Then every FAIL line is sorted (walk_findings).
real_walk() {
    local s=$1 rc out=$REPO/build/emu-layout/$1
    rm -rf "$out"
    "$EMU/layout/walk.sh" "$s" --apk "$DEBUG_APK" --test-apk "$TEST_APK" --parts app --locales "$WALK_LOCALES" > "$LOGS/walk.log" 2>&1
    rc=$?
    rm -rf "${LOGS:?}/walk"
    mkdir -p "$LOGS/walk"
    cp -r "$out/." "$LOGS/walk/" 2> /dev/null
    grep -E '^SUMMARY' "$LOGS/walk.log" | sed 's/^/   /'
    echo "   walk.sh exit $rc"
    walk_findings "$LOGS/walk.log"
}

# walk_findings LOG: each finding of each FAIL line of a walk in real languages, sorted: KNOWN (Russian's
# automatic hyphenation: "a word split across two lines" in ru; in uz-Cyrl the snackbar on Entry-deleted
# beside "Қайтариш") or a DEFECT; a FAIL line that is a broken walk step or a test that failed is a
# DEFECT too. Prints the counts per language and every DEFECT; fails when there is one, or when a
# language did not end its walk.
walk_findings() {
    python3 - "$1" "$WALK_LOCALES" <<'EOF'
import collections, re, sys
log, locales = sys.argv[1], sys.argv[2].split(",")
lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
known = collections.Counter()
checks = collections.Counter()
defects = []

def split_findings(body):
    # "(f1; f2; ...)" at the end of a FAIL line: findings are separated by "; " outside quotes and brackets.
    out, depth, quote, cur = [], 0, False, ""
    for ch in body:
        if ch == '"':
            quote = not quote
        elif not quote and ch in "([":
            depth += 1
        elif not quote and ch in ")]":
            depth -= 1
        if ch == ";" and depth == 0 and not quote:
            out.append(cur.strip())
            cur = ""
            continue
        cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out

for line in lines:
    m = re.match(r"^(PASS|FAIL) (\S+) (.*)$", line)
    if not m:
        continue
    result, locale, rest = m.groups()
    if locale not in locales:
        if result == "FAIL":
            defects.append(line)
        continue
    checks[(locale, result)] += 1
    if result == "PASS":
        continue
    fm = re.match(r"^(.*?): (no clipped button|no truncated or clipped text|no text left in plain English|no control under 48 dp) \((.*)\)$", rest)
    if not fm:
        defects.append(line)  # a broken step, an install, a crash
        continue
    screen, check, body = fm.groups()
    for finding in split_findings(body):
        if locale == "ru" and finding.startswith("a word split across two lines"):
            known[(locale, "Russian hyphenation (a word split, the hyphen drawn)")] += 1
        elif locale == "uz-Cyrl" and screen == "Entry-deleted" and "ўчириб" in finding and "pv.entry.message" in finding:
            known[(locale, "the snackbar 'Olx ўчириб ташланди' beside 'Қайтариш' (Entry-deleted)")] += 1
        else:
            defects.append(f"{locale} {screen}: {check}: {finding}")
ended = {l: any(re.match(rf"^NOTE {re.escape(l)}: |^(PASS|FAIL) {re.escape(l)} ", x) for x in lines) for l in locales}
for l in locales:
    print(f"   {l}: {checks[(l, 'PASS')]} checks passed, {checks[(l, 'FAIL')]} failed"
          + "".join(f"; KNOWN {n} x {what}" for (loc, what), n in sorted(known.items()) if loc == l))
for d in defects:
    print(f"   DEFECT {d}")
bad = bool(defects)
for l in locales:
    if checks[(l, "PASS")] + checks[(l, "FAIL")] == 0:
        print(f"   {l}: no check ran")
        bad = True
sys.exit(1 if bad else 0)
EOF
}

# ---- pictures --------------------------------------------------------------------------------------------
pictures() {
    local began rc
    began=$(date +%s)
    "$EMU/checks/6-pictures.sh" --out "$SHOTS" --apk "$DEBUG_APK" --test-apk "$TEST_APK" --logs "$LOGS" \
        ${SER[37]:+--s37 "${SER[37]}"} ${SER[24]:+--s24 "${SER[24]}"} > "$LOGS/pictures.log" 2>&1
    rc=$?
    grep -E '^(PASS|FAIL|SKIPPED|SUMMARY|NOTE|   )' "$LOGS/pictures.log" | sed 's/^/   /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- arguments ------------------------------------------------------------------------------------------
ALL_PARTS=(merged connected i18n smoke staging window walk)
DEVICE_PARTS=(connected i18n smoke staging window walk pictures)
parts=()
gradle_args=()
keep=0
no_build=0
while [ $# -gt 0 ]; do
    case "$1" in
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --levels)
            [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"
            read -r -a APIS <<< "${2//,/ }"
            for api in "${APIS[@]}"; do case "$api" in 24 | 28 | 37) ;; *) pv_usage "${BASH_SOURCE[0]}" ;; esac; done
            shift ;;
        --keep) keep=1 ;;
        --no-build) no_build=1 ;;
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

# A part run on some of the levels keeps its logs apart (LOGS_ROOT/PART-api-28-37), so that the same part
# run on the other levels does not remove them.
LEVELS_SUFFIX=""
[ "${APIS[*]}" = "24 28 37" ] || LEVELS_SUFFIX=-api-$(IFS=-; echo "${APIS[*]}")

mkdir -p "$LOGS_ROOT"
echo "waves 6 and 8A (round R6) device check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]} on API ${APIS[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R6"
    pv_step "the heads of w6B-6.3a, w6B-6.3b, w6B-6.4a, w6B-6.4b and w8A-8.2 are in HEAD" -- heads_merged
fi
if needs_devices; then
    echo
    echo "== devices: the integrator's API ${APIS[*]}"
    if ! pv_step "$([ $no_build = 1 ] && echo "the APKs built before" || echo "build the debug, staging, backupCheck and test APKs"), copied to ${APK_DIR#"$REPO"/}" -- build_apks \
        || ! pv_step "the integrator's devices run (boot.sh --integrator)" -- devices; then
        parts=(stop)
    fi
fi
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
            pv_step "every device-test class passed on each device with at least its tests (583, 585, 596)" -- device_classes
            studio_runs "$began" ;;
        i18n)
            pv_step "6.1: tools/emu/i18n/all.sh (the matrix of i18n.md 5 and E11's language step), debug build, API 24 and 37" -- i18n_all
            pv_step "the step the merge of R6 changed ran on API 37: the drag handle is the app's own Ўзбекча text" -- mixing_ran ;;
        smoke)
            pv_step "smoke.sh staging with its language stage on API 24 and 37" -- in_parallel smoke-staging smoke_staging -- 24 37
            pv_step "smoke.sh: no step failed or was skipped on API 24 and 37" -- summary_without_failures smoke-staging 24 37 ;;
        staging)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "8A: the skeleton steps 1 to 9 of checks/3A.sh, staging build, on API 24 and 37" -- in_parallel skeleton-staging skeleton_staging -- 24 37
            pv_step "8A: lock/all.sh with the staging build on the levels 24 and 37 (a SKIPPED step needs run-as or root)" -- lock_staging
            pv_step "8A: session/autolock.sh with the staging build on API 24" -- in_parallel autolock-staging autolock_staging -- 24
            pv_step "8A: setup/create.sh with the staging build on API 24" -- in_parallel create-staging create_staging -- 24
            pv_step "8A: clipboard/clear.sh with the staging build on API 24" -- in_parallel --quiet clear-staging clear_staging -- 24
            pv_step "8A: shell/walk.sh with the staging build on API 37" -- in_parallel walk-staging walk_staging -- 37 ;;
        window)
            pv_step "tools/emu/window/all.sh: debug and staging on API 28 and 37, a11y-old on API 24" -- window_checks ;;
        walk)
            if has_level 37; then
                pv_step "the test APK is not on the devices" -- no_test_apk
                pv_step "6.2's walk in en, uz, uz-Cyrl and ru on API 37 at 320 dp and 200 %: no finding but the KNOWN ones" -- real_walk "${SER[37]}"
                pv_step "the walk removed its test APK" -- no_test_apk
            else
                echo "   API 37 is not among --levels"
            fi ;;
        pictures)
            pv_step "the picture set for the owner: every screen in en, uz-Latn, uz-Cyrl and ru (API 37, debug), in ${SHOTS}" -- pictures ;;
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
