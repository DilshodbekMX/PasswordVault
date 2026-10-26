#!/usr/bin/env bash
# tools/emu/checks/8.sh - the integration check of wave 8, the last check before the owner puts the app on
# their phone (build-plan.md section 10, "Integration check for wave 8", and section 12, "From the merge of
# R7", "What the integration check of wave 8 runs"). The integrator runs tools/check.sh all beside it.
# Round R7 brought 8.1, the accessibility and acceptance pass (every screen of APP/ui/ and the strings of
# the four languages); 8.2 to 8.4 came with R5 and R6.
#
#   tools/emu/checks/8.sh [PART...] [--levels 24,28,37] [--device API=SERIAL]... [--shots DIR] [--keep]
#                         [--no-build] [--release-apk FILE] [--release-x86-apk FILE] [-- GRADLE-ARGS...]
#
# The parts run in this order, all but release when none is named, on the integrator's devices (5584 is
# API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used as
# it is, the others are booted together (boot.sh --integrator). --device API=SERIAL uses another device of
# this worktree for that level instead (one that tools/emu/boot.sh started from here). --levels limits
# every part to those API levels (a part runs on the levels it has among them; a part run on some levels
# logs to PART-api-L-L/), so that parts can share the three devices in any order from several runs at
# once. The debug, staging and test APKs are built first (tools/gradle.sh; not with --no-build) and
# copied to LOGS/apk/, from where every part installs them: Android Studio, which has this worktree open,
# rebuilds app/build/outputs/apk/debug/ when its Run is pressed. The devices are stopped at the end unless
# --keep is given; the part "stop" does nothing else.
#   merged      the head that round R7 merged is in HEAD: w8B-8.1 e07fbc9.
#   connected   adb.sh --connected on the devices, in touch mode (a tap on the launcher; on API 37, where
#               the shell's tap leaves the display in keyboard mode, an instrumentation sets touch mode
#               first): every connected test, and every serial has a passing report. A class that failed
#               is run once more, alone, on that device (one run per class); it counts as passed when it
#               passes alone, and the step names it as a flake. Then every device-test class ran on each
#               device with no failure and at least the tests it has there: 614 on API 24, 616 on 28, 627
#               on 37, counted from the sources with their @SdkSuppress filters at wave-8-merged (the
#               merge of R7's connected-counts-wave-8-merged.txt; 8.1 added ten classes and four tests
#               to EntryScreenTest and PasswordsScreenTest).
#   smoke       smoke.sh staging on API 24, 28 and 37, with its stages of 3A, 3B, 4A, wave 5 (backup,
#               export, import, a restore from the lock screen) and the app's language (Русский, then
#               Ўзбекча, then System language): no step failed or was skipped.
#   staging     section 10's "the staging build runs every script under tools/emu/ on API 24, 28 and 37",
#               each script on the levels it supports, with the staging APK: checks/3A.sh skeleton,
#               session/autolock.sh, setup/create.sh, clipboard/clear.sh and shell/walk.sh on 24, 28 and
#               37; tools/emu/i18n/ as far as it needs no debuggable build: matrix.sh's parts phone,
#               survives and formats on 24, 28 and 37 and page and mixing on 37 (switch and prompt need
#               the debug build's pseudo-locales, and switch below API 33 run-as), and e11.sh on 24, 28
#               and 37. smoke.sh, lock/, window/ and change/ are the parts smoke, lock, window and wave7.
#               Every SKIPPED step must need run-as or root: the part debug runs those scripts again.
#   debug       the steps of the part staging that need a debuggable build, with the debug APK
#               (section 10: "steps that need a debuggable build are run on debug"): checks/3A.sh
#               skeleton (step 3 reads the vault files with run-as; pictures of steps 1, 2 and 4 on API
#               37), session/autolock.sh (the lock delay in pv_settings, "Immediately"),
#               setup/create.sh --vault-file and clipboard/clear.sh (the delay set to 30 s with run-as,
#               the kill on API 24) on 24, 28 and 37.
#   lock        lock.md E1 to E17 (tools/emu/lock/all.sh) with the staging APK on the levels lock.md gives
#               each check (E15 builds its own backupCheck variant); then each check that skipped a step
#               on a level (run-as, or root on API 37) again with the debug APK there. The part fails
#               when a check failed with either APK.
#   window      tools/emu/window/all.sh (architecture.md 8.4: at every integration check) with the debug
#               and the staging APK: keyboard, autofill, overlay, state, a11y and capture on API 28 and
#               37 (capture's steps are SKIPPED on 28, as content capture starts with Android 10),
#               a11y-old on API 24: 26 scripts. all.sh clears its own log folder when it starts, so two
#               runs must not overlap: this part runs it once for every level it has.
#   i18n        6.1's [script] lines with the debug build (the pseudo-locales and run-as need it):
#               tools/emu/i18n/all.sh (matrix.sh with the parts of the level, then e11.sh) on 24, 28 and
#               37. KNOWN on API 37: Android 17's App language page never marks Ўзбекча.
#   walk        6.2's walk with the debug build and its test APK, each device alone: both parts in en-XA
#               and ar-XB at 320 dp and 200 % on 24, 28 and 37, 1718 checks passed and none failed or
#               skipped on each level; then on API 37 the part app in the four real languages (en, uz,
#               uz-Cyrl, ru: the app's own tags), each finding sorted: Russian's automatic hyphenation,
#               which the probe counts as "a word split across two lines" although the hyphen is drawn,
#               is KNOWN (8.1's interface request for LayoutProbe, build plan 12); anything else fails
#               the step, in en, uz and uz-Cyrl as in ru (8.1's VaultSnackbar removed the Ўзбекча
#               snackbar that was KNOWN at the device check of waves 6 and 8A).
#   a11y        8.1's accessibility walk on API 37 (ui.shell.AccessibilityWalkTest with A11yWalker, -e
#               pvA11y RUN; without it the test returns at once): every control of every screen pressed
#               through the accessibility tree alone, as Switch Access does, and written down as TalkBack
#               would read it; once at the phone's own size and once at 320 dp and 200 % with the layout
#               probe at each screen (-e pvA11yLayout true). Each: 64 screens passed, none failed, no
#               broken step, the test passed; KNOWN only Material 3's nameless tooltip anchor around a
#               sheet's drag handle. Reports in LOGS/ and in SHOTS/../a11y-walk/.
#   wave5       the wave-5 part of checks/5.sh on API 37 and 24, the debug build (its checks read
#               pv_settings and the vault files with run-as): build-plan 7's lines 1 to 7, U 12 check 12,
#               check 15 and the owner's decision 21 (d21, whose ViewModels 8.1 changed); its line 8 is
#               smoke.sh staging, the part smoke.
#   wave7       the wave-7 part of checks/5.sh on API 37, the debug build: the fifty accounts (the
#               Security tab's dot, its cards, a guided change, decision 23) and 7.3's walk; then 7.2's
#               change/guided.sh (its accounts are made by the test APK's GuidedChangeSeed, which runs
#               the debug build's own classes: about 35 minutes). Its smoke.sh staging is the part smoke.
#   release     only when named: the release APK that docs/release step 2 builds, signed with a key made
#               as step 1 makes it (for a check, a throwaway key outside the project, never the owner's),
#               on API 24 and 37. The release build has no debug receiver (no `status`, no `locale`, no
#               holds), is not debuggable (no run-as), sets FLAG_SECURE, refuses software-backed keys
#               (lock.md 4.5: on an emulator it never offers a PIN or a fingerprint, F18) and carries
#               only ARM code (A 7.2): where a device runs no ARM code (the API 24 image), the APK itself
#               is refused with INSTALL_FAILED_NO_MATCHING_ABIS, as docs/release step 2.13 says, and
#               --release-x86-apk is installed instead: the same APK with the x86_64 library of the same
#               argon2kt added and signed with the same key (its code and resources are the release
#               build's). Steps: checks/3B.sh secure (FLAG_SECURE: screencap black, the emulator's own
#               screenshot not); then afresh: the version and no run-as, no debug receiver; Welcome;
#               setup with the suggested words, the list without "Choose a PIN"; Settings' PIN row off
#               with "This phone can’t keep a PIN or fingerprint key in protected hardware."; Version
#               without the "Test build" line; an account added; its password copied (flagged
#               sensitive, with a token) and cleared at its time with the app in the background; back
#               in front the master-password screen without a pad and without the TEST BUILD line; the
#               master password opens the list; Lock now and the master password again; a backup into
#               Download through the system's picker; adb uninstall; installed again, Welcome's "Restore
#               from a backup file" with that backup: the account is back.
#
# After a part that drives the app, a NOTE names every Run that Android Studio started on one of the
# devices meanwhile (its idea.log; tools/emu/README.md, "Device facts"): such a Run stops, reinstalls
# and starts the app under whatever step is running.
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/8/, pictures in DIR
# (default /home/asus/pvwork/shots-8/integration-check). Exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial. Written by the integration check of wave 8 (build-plan.md
# section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"
source "$EMU/clipboard/lib.sh"

SHOTS=/home/asus/pvwork/shots-8/integration-check
LOGS_ROOT=$REPO/build/emu-checks/8
APK_DIR=$LOGS_ROOT/apk
DEBUG_APK=$APK_DIR/app-debug.apk
STAGING_APK=$APK_DIR/app-staging.apk
TEST_APK=$APK_DIR/app-debug-androidTest.apk
RELEASE_APK=""
RELEASE_X86_APK=""
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug
RUNNER_ARG=-Pandroid.testInstrumentationRunnerArguments

# ---- merged ------------------------------------------------------------------------------------------
# BRANCH:HEAD as round R7 merged it (the merge commit 1379db6 has it as its second parent).
MERGED=(w8B-8.1:e07fbc9)

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
declare -A OVERRIDE=()

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

# The integrator's devices: one of this worktree that runs on its port is used, the others booted; a
# level given with --device uses that device of this worktree instead.
devices() {
    local api port entry rc=0 status
    declare -A pids=()
    for api in "${APIS[@]}"; do
        if [ -n "${OVERRIDE[$api]:-}" ]; then
            entry=$PV_REG/${OVERRIDE[$api]}
            if [ -f "$entry" ] && [ "$(pv_entry_get "$entry" owner)" = "$(pv_owner)" ] && pv_entry_alive "$entry" \
                && PV_SERIAL=${OVERRIDE[$api]} pv_wait_device "${OVERRIDE[$api]}" 30 \
                && [ "$(PV_SERIAL=${OVERRIDE[$api]} pv_api)" = "$api" ]; then
                SER[$api]=${OVERRIDE[$api]}
                echo "   API $api: ${OVERRIDE[$api]} (--device), running"
            else
                echo "   API $api: ${OVERRIDE[$api]} is not a running API $api device of this worktree"
                rc=1
            fi
            continue
        fi
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
        if [ -f "$PV_REG/${SER[$api]:-emulator-$(pv_integrator_port "$api")}" ]; then
            echo "   ${SER[$api]:-emulator-$(pv_integrator_port "$api")} is still registered"
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

# api_of SERIAL: the API level of one of the serials in use.
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

# summary_clean NAME API...: the logs LOGS/NAME-API.log end with a summary of 0 failed and 0 skipped.
summary_clean() {
    local name=$1 api rc=0 line
    shift
    for api in $(levels_of "$@"); do
        line=$(grep -E '^SUMMARY ' "$LOGS/$name-$api.log" | tail -n 1)
        echo "   $name on API $api: ${line:-no summary}"
        [[ "$line" == *", 0 failed, 0 skipped" ]] || rc=1
    done
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
# of the focused window): there an instrumentation puts it in touch mode, FieldKeyboardTest's one test,
# which calls Instrumentation.setInTouchMode(true) first (adb.sh --connected removes both APKs again
# before its run).
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
        touch_mode "$s" > /dev/null < /dev/null
        "$EMU/adb.sh" --connected "$s" -- "$RUNNER_ARG.class=$cls" ${gradle_args[@]+"${gradle_args[@]}"} > "$dir/again/$s/$cls.log" 2>&1 < /dev/null || again=1
        cp -r "$RESULTS/." "$dir/again/$s/$cls/" 2> /dev/null
        grep -E '^(PASS|FAIL) connected' "$dir/again/$s/$cls.log" | sed 's/^/     alone: /'
    done < "$dir/failed"
    [ $again -eq 0 ] || return 1
    echo "   FLAKY: each class that failed in the full run passed alone: $(awk '{ printf "%s on %s; ", $2, $1 }' "$dir/failed")"
}

# Each device-test class with the package that brought it and the tests it has on API 24, 28 and 37 at
# wave-8-merged (614, 616 and 627, counted from the sources with their @SdkSuppress filters by the merge
# of R7: /home/asus/pvwork/kept/merge-R7/connected-counts-wave-8-merged.txt). A class run again alone
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
    "ui.list.PasswordsScreenTest": ("3.5", 13, 13, 13),
    "ui.edit.EditKeyboardRotationTest": ("3.6", 3, 4, 4),
    "ui.edit.EditScreenTest": ("3.6", 4, 4, 4),
    "ui.entry.BigLettersScreenTest": ("3.7", 10, 10, 10),
    "ui.entry.EntryPseudoLocaleTest": ("3.7", 6, 6, 6),
    "ui.entry.EntrySavedStateTest": ("3.7", 2, 2, 2),
    "ui.entry.EntryScreenTest": ("3.7", 19, 19, 19),
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
    "ui.common.LongWordsTest": ("8.1", 2, 2, 2),
    "ui.common.RussianPluralsTest": ("8.1", 3, 3, 3),
    "ui.common.SecretTextLinesTest": ("8.1", 2, 2, 2),
    "ui.common.SecureFieldNameTest": ("8.1", 3, 3, 3),
    "ui.common.SnackbarLayoutTest": ("8.1", 2, 2, 2),
    "ui.common.TextFieldLinesTest": ("8.1", 1, 1, 1),
    "ui.common.TextFieldNameTest": ("8.1", 2, 2, 2),
    "ui.edit.GeneratorSheetTest": ("8.1", 9, 9, 9),
    "ui.setup.MasterPasswordFieldsRotationTest": ("8.1", 2, 2, 2),
    "ui.shell.AccessibilityWalkTest": ("8.1", 1, 1, 1),
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

# ---- smoke, staging, debug -------------------------------------------------------------------------------
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1" --apk "$STAGING_APK"; }
skeleton_debug() {
    local shots=()
    [ "$(api_of "$1")" = 37 ] && shots=(--shots "$SHOTS/skeleton")
    "$EMU/checks/3A.sh" skeleton debug "$1" --apk "$DEBUG_APK" ${shots[@]+"${shots[@]}"}
}
autolock_staging() { "$EMU/session/autolock.sh" "$1" --apk "$STAGING_APK"; }
autolock_debug() { "$EMU/session/autolock.sh" "$1" --apk "$DEBUG_APK"; }
create_staging() { "$EMU/setup/create.sh" "$1" --apk "$STAGING_APK"; }
create_debug() { "$EMU/setup/create.sh" "$1" --apk "$DEBUG_APK" --vault-file; }
clear_staging() { "$EMU/clipboard/clear.sh" "$1" --apk "$STAGING_APK"; }
clear_debug() { "$EMU/clipboard/clear.sh" "$1" --apk "$DEBUG_APK"; }
walk_staging() { "$EMU/shell/walk.sh" "$1" --apk "$STAGING_APK"; }

# i18n_staging SERIAL: tools/emu/i18n/ with the staging APK, as far as it needs no debuggable build:
# matrix.sh's parts phone, survives and formats (and page and mixing from API 33 on), then e11.sh.
i18n_staging() {
    local parts=(phone survives formats) rc=0
    [ "$(PV_SERIAL=$1 pv_api)" -lt 33 ] || parts=(phone survives page mixing formats)
    "$EMU/i18n/matrix.sh" "$1" --apk "$STAGING_APK" --shots "$LOGS/i18n-shots-$1" "${parts[@]}" || rc=1
    "$EMU/i18n/e11.sh" "$1" --apk "$STAGING_APK" --shots "$LOGS/i18n-shots-$1" || rc=1
    return $rc
}

# skipped_need_debug NAME... -- API...: every SKIPPED line in LOGS/NAME-API.log names a debuggable build
# (run-as) or root as its reason; prints them, counted.
skipped_need_debug() {
    local names=() api name rc=0 lines
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do names+=("$1"); shift; done
    shift
    for name in "${names[@]}"; do
        for api in $(levels_of "$@"); do
            [ -f "$LOGS/$name-$api.log" ] || continue
            lines=$(grep -E '^SKIPPED ' "$LOGS/$name-$api.log")
            [ -n "$lines" ] || continue
            printf '%s\n' "$lines" | sed "s/^/   $name on API $api: /"
            if printf '%s\n' "$lines" | grep -viqE 'needs-debuggable|needs-root|run-as|debuggable|root|not debuggable|staging'; then
                echo "   $name on API $api: a step was skipped for another reason"
                rc=1
            fi
        done
    done
    return $rc
}

# debug_skipped_nothing: the summaries of the part debug; no SKIPPED line of its logs names a debuggable
# build or run-as (a step that needs root on API 37's user build may still skip, and says so).
debug_skipped_nothing() {
    local n a rc=0
    for n in skeleton-debug autolock-debug create-debug clear-debug; do
        for a in $(levels_of 24 28 37); do
            grep -E '^SUMMARY ' "$LOGS/$n-$a.log" 2> /dev/null | tail -n 1 | sed "s/^/   $n on API $a: /"
            grep -E '^SKIPPED ' "$LOGS/$n-$a.log" 2> /dev/null | sed "s/^/     /"
            ! grep -E '^SKIPPED ' "$LOGS/$n-$a.log" 2> /dev/null | grep -qiE 'debuggable|run-as' || rc=1
        done
    done
    return $rc
}

# ---- lock --------------------------------------------------------------------------------------------------
LOCK_OUT=$REPO/build/emu-lock/all

# lock_run APK DIR LEVEL-SERIAL-PAIRS...: tools/emu/lock/all.sh with APK on each given level's device,
# checks of that level (LEVEL:SERIAL:CHECKS, CHECKS comma-separated or "all"), each level a run of its own
# at the same time; the per-check logs are copied to LOGS/DIR/. lock/all.sh's own results.txt is shared by
# its runs, so the table is made from the per-check logs.
lock_run() {
    local apk=$1 dir=$2 pair level serial checks rc=0 status
    shift 2
    mkdir -p "$LOGS/$dir"
    declare -A pids=()
    for pair in "$@"; do
        IFS=: read -r level serial checks <<< "$pair"
        rm -f "$LOCK_OUT/$serial"-E*.log
        if [ "$checks" = all ]; then
            "$EMU/lock/all.sh" --apk "$apk" --levels "$level" "$serial" > "$LOGS/$dir/all-$level.log" 2>&1 &
        else
            "$EMU/lock/all.sh" --apk "$apk" --levels "$level" --only "$checks" "$serial" > "$LOGS/$dir/all-$level.log" 2>&1 &
        fi
        pids[$level]=$!
    done
    for pair in "$@"; do
        IFS=: read -r level serial checks <<< "$pair"
        wait "${pids[$level]}"
        status=$?
        [ $status -eq 0 ] || rc=1
        cp "$LOCK_OUT/$serial"-E*.log "$LOGS/$dir/" 2> /dev/null
        echo "   lock/all.sh $(basename "$apk") on API $level ($serial), checks $checks: exit $status"
    done
    return $rc
}

# lock_table DIR: one line per check and level from LOGS/DIR/SERIAL-EN.log: its summary, or that it has
# none. Fails when a check failed or has no summary.
lock_table() {
    local f rc=0 c s line api
    for f in "$LOGS/$1"/emulator-*-E*.log; do
        [ -f "$f" ] || continue
        s=$(basename "$f" .log)
        c=${s##*-}
        s=${s%-*}
        api=$(api_of "$s")
        line=$(grep -m 1 '^SUMMARY' "$f" | sed 's/^SUMMARY [^:]*: //')
        printf '   %s on API %s: %s\n' "$c" "$api" "${line:-no summary}"
        [[ "$line" == *", 0 failed,"* ]] || rc=1
    done | sort -V
    # The table is printed by a pipe's subshell: read the logs once more for the result.
    for f in "$LOGS/$1"/emulator-*-E*.log; do
        [ -f "$f" ] || continue
        grep -q '^SUMMARY .*, 0 failed,' "$f" || rc=1
    done
    return $rc
}

# lock_staging: lock/all.sh with the staging APK on every level among --levels.
lock_staging() {
    local api pairs=()
    for api in $(levels_of 24 28 37); do pairs+=("$api:${SER[$api]}:all"); done
    lock_run "$STAGING_APK" staging "${pairs[@]}"
    lock_table staging
}

# lock_debug_skipped: each check that skipped a step with the staging APK on a level, again with the debug
# APK on that level (its SKIPPED lines need run-as, or root on API 37's user build).
lock_debug_skipped() {
    local api s f c checks pairs=()
    for api in $(levels_of 24 28 37); do
        s=${SER[$api]}
        checks=""
        for f in "$LOGS/staging/$s"-E*.log; do
            [ -f "$f" ] || continue
            grep -q '^SKIPPED ' "$f" || continue
            c=$(basename "$f" .log)
            c=${c##*-}
            checks="${checks:+$checks,}$c"
            grep '^SKIPPED ' "$f" | sed "s/^/   $c on API $api with staging: /"
        done
        [ -n "$checks" ] && pairs+=("$api:$s:$checks")
    done
    [ ${#pairs[@]} -gt 0 ] || { echo "   no check skipped a step with the staging APK"; return 0; }
    lock_run "$DEBUG_APK" debug "${pairs[@]}"
    lock_table debug
}

# ---- window ------------------------------------------------------------------------------------------------
# window/all.sh once on the devices of --levels: keyboard, autofill, overlay, state, a11y and capture on
# API 28 and 37, a11y-old on API 24, each with the debug and the staging APK (architecture.md 8.4). Its
# logs (build/window/all/, cleared by each all.sh) are copied to LOGS/all/.
window_checks() {
    local began rc
    began=$(date +%s)
    # shellcheck disable=SC2046 # the serials are words
    "$EMU/window/all.sh" --apk "$DEBUG_APK" --apk "$STAGING_APK" --only keyboard,autofill,overlay,state,a11y,capture \
        $(serials_of 24 28 37) > "$LOGS/all.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/all"
    cp -r "$REPO/build/window/all/." "$LOGS/all/" 2> /dev/null
    grep -E '^(PASS|FAIL) .* \(exit|^SUMMARY window' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^KNOWN ' "$LOGS/all.log" | sort | uniq -c | sed 's/^/     /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- i18n: 6.1's [script] lines -----------------------------------------------------------------------------
I18N_OUT=$REPO/build/emu-i18n/all

# i18n/all.sh with the debug APK on the levels; the logs of these serials in its folder (build/emu-i18n/all/,
# which every run shares and nobody clears) are removed first and copied to LOGS/all/ afterwards.
i18n_all() {
    local rc began serials s
    serials=$(serials_of 24 28 37)
    for s in $serials; do rm -f "$I18N_OUT/$s"-*; done
    began=$(date +%s)
    # shellcheck disable=SC2086 # the serials are words
    "$EMU/i18n/all.sh" --apk "$DEBUG_APK" $serials > "$LOGS/all.log" 2>&1
    rc=$?
    mkdir -p "$LOGS/all"
    for s in $serials; do cp "$I18N_OUT/$s"-* "$LOGS/all/" 2> /dev/null; done
    grep -E '^(PASS|FAIL) [a-z0-9]+\.sh|^SUMMARY' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^   (FAIL|KNOWN) ' "$LOGS/all.log" | sed 's/^/  /'
    studio_runs "$began" | sed 's/^/   /'
    return $rc
}

# ---- walk: 6.2's walk --------------------------------------------------------------------------------------
# The checks a full walk (both parts, en-XA and ar-XB, 320 dp, font 2.0) passes on each level: the step that
# sets the size, 268 per locale in the part app and 1181 in the part states.
declare -A WALK_CHECKS=([24]=1718 [28]=1718 [37]=1718)

# walk_on SERIAL WANT NAME WALK-ARGS...: tools/emu/layout/walk.sh on that device with the debug build and
# its test APK, its output in LOGS/NAME.log, of which it prints the lines that did not pass and the
# summary; build/emu-layout/SERIAL (the walk's logs, reports and pictures) is then copied to LOGS/NAME/.
# Passes when the walk passed and its SUMMARY reads WANT passed, 0 failed, 0 skipped.
walk_on() {
    local s=$1 want=$2 name=$3 rc summary out=$REPO/build/emu-layout/$1
    shift 3
    rm -rf "$out"
    "$EMU/layout/walk.sh" "$s" --apk "$DEBUG_APK" --test-apk "$TEST_APK" "$@" > "$LOGS/$name.log" 2>&1
    rc=$?
    grep -E '^(FAIL|SKIPPED|NOTE|SUMMARY)' "$LOGS/$name.log"
    rm -rf "${LOGS:?}/$name"
    mkdir -p "$LOGS/$name"
    cp -r "$out/." "$LOGS/$name/" 2> /dev/null
    summary=$(grep -E "^SUMMARY $s: " "$LOGS/$name.log" | tail -n 1)
    echo "   walk.sh${*:+ $*} on $s: exit $rc; ${summary:-no summary}; expected $want passed, 0 failed, 0 skipped"
    [ $rc -eq 0 ] && [ "$summary" = "SUMMARY $s: $want passed, 0 failed, 0 skipped" ]
}

# layout_walk SERIAL: the full walk in the pseudo-locales (6.2's acceptance line), LOGS/walk-API.log.
layout_walk() {
    local api
    api=$(api_of "$1")
    walk_on "$1" "${WALK_CHECKS[$api]:-1718}" "walk-$api"
}

# The app's own tags (AppLanguages): English, Uzbek in Latin letters, Uzbek in Cyrillic letters, Russian.
WALK_LOCALES=en,uz,uz-Cyrl,ru

# real_walk SERIAL: the part app in the four languages at 320 dp and 200 %, LOGS/walk-real.log; the walk's
# folder is copied to LOGS/walk-real/. Then every FAIL line is sorted (walk_findings).
real_walk() {
    local s=$1 rc out=$REPO/build/emu-layout/$1
    rm -rf "$out"
    "$EMU/layout/walk.sh" "$s" --apk "$DEBUG_APK" --test-apk "$TEST_APK" --parts app --locales "$WALK_LOCALES" > "$LOGS/walk-real.log" 2>&1
    rc=$?
    rm -rf "${LOGS:?}/walk-real"
    mkdir -p "$LOGS/walk-real"
    cp -r "$out/." "$LOGS/walk-real/" 2> /dev/null
    grep -E '^SUMMARY' "$LOGS/walk-real.log" | sed 's/^/   /'
    echo "   walk.sh exit $rc"
    walk_findings "$LOGS/walk-real.log"
}

# walk_findings LOG: each finding of each FAIL line of a walk in real languages, sorted: KNOWN (Russian's
# automatic hyphenation: "a word split across two lines" in ru) or a DEFECT; a FAIL line that is a broken
# walk step or a test that failed is a DEFECT too. Prints the counts per language and every DEFECT; fails
# when there is one, or when a language did not end its walk.
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
        else:
            defects.append(f"{locale} {screen}: {check}: {finding}")
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

# ---- a11y: 8.1's accessibility walk ---------------------------------------------------------------------------
A11Y_SCREENS=64

# a11y_walk SERIAL RUN [--layout]: ui.shell.AccessibilityWalkTest with -e pvA11y RUN on a fresh install of
# the debug APK and its test APK, the app's data cleared before and after; with --layout the phone is set
# to 320 dp wide and a font scale of 2 first (as layout/walk.sh sets it; put back after) and the walk runs
# with -e pvA11yLayout true. The report (the status lines) goes to LOGS/RUN.txt, the raw output to
# LOGS/RUN.raw. Passes when the test passed, the report ends "END 64 screens|0 failed", 64 screens passed,
# none failed, no step broke and every KNOWN line is Material's tooltip anchor of a drag handle.
a11y_walk() {
    local s=$1 run=$2 layout=${3:-} old_density old_font width rc=0 extra=() passed failed broken
    (
        pv_use "$s"
        pva_install "$DEBUG_APK" || exit 1
        pv_adb install -r -t "$TEST_APK" > /dev/null 2>&1 || exit 1
        pv_shell pm clear "$PV_PACKAGE" > /dev/null
        if [ "$layout" = --layout ]; then
            old_density=$(pv_shell wm density 2> /dev/null | tr -d '\r' | sed -n 's/^Override density: *\([0-9]*\)$/\1/p')
            old_font=$(pv_shell settings get system font_scale 2> /dev/null | tr -d '\r')
            width=$(pv_shell wm size 2> /dev/null | tr -d '\r' | sed -n 's/^Physical size: *\([0-9]*\)x[0-9]*$/\1/p')
            pv_shell wm density $((width * 160 / 320)) > /dev/null
            pv_shell settings put system font_scale 2.0 > /dev/null
            echo "   layout: density $((width * 160 / 320)) (320 dp wide), font scale 2.0 (were ${old_density:-physical} and ${old_font:-unset})"
            extra=(-e pvA11yLayout true)
        fi
        unset PV_ADB_TIMEOUT
        pv_adb shell am instrument -w -r -e pvA11y "$run" ${extra[@]+"${extra[@]}"} -e class "$PV_PACKAGE.ui.shell.AccessibilityWalkTest" \
            "$PV_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" > "$LOGS/$run.raw" 2>&1
        if [ "$layout" = --layout ]; then
            if [ -n "$old_density" ]; then pv_shell wm density "$old_density" > /dev/null; else pv_shell wm density reset > /dev/null; fi
            case "$old_font" in '' | null) pv_shell settings delete system font_scale > /dev/null ;; *) pv_shell settings put system font_scale "$old_font" > /dev/null ;; esac
        fi
        pv_shell pm clear "$PV_PACKAGE" > /dev/null
        pv_adb uninstall "$PV_PACKAGE.test" > /dev/null 2>&1
        exit 0
    ) || return 1
    grep -a '^INSTRUMENTATION_STATUS: pva11y=' "$LOGS/$run.raw" | sed 's/^INSTRUMENTATION_STATUS: pva11y=//' > "$LOGS/$run.txt"
    passed=$(grep -c '^SCREEN .*|PASS' "$LOGS/$run.txt")
    failed=$(grep -c '^SCREEN .*|FAIL' "$LOGS/$run.txt")
    broken=$(grep -c '^BROKEN' "$LOGS/$run.txt")
    echo "   $run on $s: $passed screens passed, $failed failed, $broken broken steps, $(grep -c '^NODE' "$LOGS/$run.txt") controls written down"
    grep -E '^(SCREEN .*\|FAIL|BROKEN|CHECK|END)' "$LOGS/$run.txt" | sed 's/^/     /'
    grep -E '^KNOWN ' "$LOGS/$run.txt" | cut -d'|' -f2 | sort | uniq -c | sed 's/^/     KNOWN /'
    if grep -E '^KNOWN ' "$LOGS/$run.txt" | grep -qv 'tooltip anchor'; then
        echo "     a KNOWN line that is not the drag handle's tooltip anchor"
        rc=1
    fi
    grep -aqE '^OK \(1 test\)' "$LOGS/$run.raw" || { echo "     the test did not pass: $(grep -aE '^(FAILURES|Tests run|INSTRUMENTATION_RESULT|INSTRUMENTATION_CODE)' "$LOGS/$run.raw" | tr '\n' ' ')"; rc=1; }
    [ "$passed" = "$A11Y_SCREENS" ] && [ "$failed" = 0 ] && [ "$broken" = 0 ] || rc=1
    grep -q "^END $A11Y_SCREENS screens|0 failed" "$LOGS/$run.txt" || rc=1
    mkdir -p "$SHOTS/../a11y-walk"
    cp "$LOGS/$run.txt" "$SHOTS/../a11y-walk/8-$run-$s.txt" 2> /dev/null
    return $rc
}

# ---- wave5, wave7: the parts of checks/5.sh ----------------------------------------------------------------
w5_debug() { "$EMU/checks/5.sh" one "$1" debug --apk "$DEBUG_APK" --shots "$SHOTS/wave5" line1 line2 line3 line4 line5 line6 line7 check12 check15 d21; }
w7_debug() { "$EMU/checks/5.sh" one "$1" debug --apk "$DEBUG_APK" --shots "$SHOTS/wave7" fifty walk73; }
guided() { "$EMU/change/guided.sh" "$1" --apk "$DEBUG_APK" --test-apk "$TEST_APK" --shots "$SHOTS/wave7"; }

# ---- release ----------------------------------------------------------------------------------------------
REL_ACCOUNT=Releasebank
REL_PASSWORD=Rq5Wm8Kz3Nv7Xt2
REL_DELAY_MS=60000 # "Clear copied text after", as setup writes it (D-7)
REL_TOKEN=""
REL_NOW=0
REL_DEADLINE=0
REL_BACKUP=""

# rel_arm: the device runs ARM code (its ABI list names arm64-v8a or armeabi-v7a; the Google Play image of
# API 37 translates it).
rel_arm() { pv_shell getprop ro.product.cpu.abilist 2> /dev/null | tr -d '\r' | grep -qE 'arm64-v8a|armeabi-v7a'; }

# rel_string NAME: the English text of a string of the app (values/), XML entities and escapes resolved.
rel_string() {
    python3 - "$REPO/app/src/main/res/values" "$1" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
for f in glob.glob(sys.argv[1] + "/strings*.xml"):
    for e in ET.parse(f).getroot():
        if e.get("name") == sys.argv[2]:
            print("".join(e.itertext()).replace("\\'", "'").replace('\\"', '"'))
            sys.exit(0)
sys.exit(1)
EOF
}

# rel_texts: every text and description of the screen, one per line.
rel_texts() { pv_ui_nodes | awk -F'|' '{ if ($1 != "") print $1; if ($2 != "") print $2 }'; }

rel_refused_without_arm() {
    local out
    out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$RELEASE_APK" 2>&1)
    pv_log "adb install -r of the release APK: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-200)"
    printf '%s' "$out" | grep -q 'INSTALL_FAILED_NO_MATCHING_ABIS'
}

rel_installed() {
    local dump code name
    dump=$(pv_shell dumpsys package "$PV_PACKAGE" 2> /dev/null | tr -d '\r')
    code=$(printf '%s\n' "$dump" | grep -m 1 -o 'versionCode=[0-9]*' | cut -d= -f2)
    name=$(printf '%s\n' "$dump" | grep -m 1 -o 'versionName=[^ ]*' | cut -d= -f2)
    pv_log "installed: versionName $name, versionCode $code; flags: $(printf '%s\n' "$dump" | grep -m 1 -o 'pkgFlags=\[[^]]*\]')"
    [ "$code" = "$REL_CODE" ] && [ "$name" = "$REL_NAME" ] || { pv_log "the APK has $REL_NAME ($REL_CODE)"; return 1; }
    ! printf '%s\n' "$dump" | grep -m 1 'pkgFlags=' | grep -q 'DEBUGGABLE' || { pv_log "the app is debuggable"; return 1; }
    ! pv_is_debuggable || { pv_log "run-as works for the app"; return 1; }
}

rel_no_receiver() {
    local out
    ! pv_shell dumpsys package "$PV_PACKAGE" 2> /dev/null | tr -d '\r' | grep -q 'DebugCommandReceiver' || { pv_log "dumpsys package lists a DebugCommandReceiver"; return 1; }
    out=$(pv_shell am broadcast -a "$PV_PACKAGE.debug.CMD" -p "$PV_PACKAGE" --es cmd status 2>&1 | tr -d '\r' | grep 'result=')
    pv_log "the debug command status: ${out:-no answer}"
    case "$out" in *'result=0'*) [[ "$out" != *'data='* ]] ;; *) return 1 ;; esac
}

# rel_set_up: setup with the suggested words; the list then opens with nothing over it: no "Choose a
# PIN", no "Make backups" (lock.md F1 step 5, F18).
rel_set_up() {
    local nodes deadline
    pva_on Welcome 30 || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$PVA_PASSWORD" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' || { pv_log "not six words: '$PVA_PASSWORD'"; return 1; }
    pva_tap pv.open.WriteDown && pva_on WriteDown || return 1
    pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.action.createVault || return 1
    deadline=$((SECONDS + 120))
    while :; do
        nodes=$(pv_ui_nodes)
        if pva_in_nodes "[|]pv\\.route\\.($PVA_PIN_STEPS)\$" "$nodes"; then
            pv_log "the PIN steps opened after setup: $(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]route[.]/ { print $3 }')"
            return 1
        fi
        pva_in_nodes '[|]pv\.route\.Passwords$' "$nodes" && break
        [ $SECONDS -lt $deadline ] || { pv_log "no list after setup; ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
    # Shown alone for 5 s: nothing came over it.
    pv_sleep 5
    nodes=$(pv_ui_nodes)
    pva_in_nodes '[|]pv\.route\.Passwords$' "$nodes" && ! pva_in_nodes "[|]pv\\.(notice|route\\.($PVA_PIN_STEPS))\$" "$nodes" \
        || { pv_log "something came over the list: $(pva_ids)"; return 1; }
}

rel_settings() {
    local i
    for i in 1 2 3 4; do pva_present pv.tab.Settings 2 && break; pv_shell input keyevent KEYCODE_BACK > /dev/null; pv_sleep 1; done
    pva_tap pv.tab.Settings && pva_on Settings 20
}

# rel_no_pin_row: Settings' PIN row is not enabled and says why (lock.md F18 step 2).
rel_no_pin_row() {
    local want line
    want=$(rel_string settings_no_secure_hardware) || return 1
    rel_settings && pva_reveal pv.open.ChangePin || return 1
    line=$(pv_ui_nodes | grep -m 1 '|pv\.open\.ChangePin|')
    pv_log "the PIN row: $(printf '%s' "$line" | cut -d'|' -f1-5); texts: $(rel_texts | tr '\n' ';' | cut -c1-400)"
    rel_texts | grep -qF -- "$want" || { pv_log "no line '$want'"; return 1; }
    [ "$(printf '%s' "$line" | cut -d'|' -f5)" != true ] || { pv_log "the PIN row is enabled"; return 1; }
}

# rel_version: Settings › Version reads "Version NAME (CODE)" and has no "Test build" line.
rel_version() {
    local test_build
    test_build=$(rel_string help_version_test_build) || return 1
    rel_settings && pva_reveal pv.open.Help.VERSION && pva_tap pv.open.Help.VERSION && pva_on Help 20 || return 1
    pv_sleep 1
    pv_log "the Version page: $(rel_texts | tr '\n' ';' | cut -c1-300)"
    rel_texts | grep -qxF -- "Version $REL_NAME ($REL_CODE)" || return 1
    ! rel_texts | grep -qF -- "$test_build" || { pv_log "the page says: $test_build"; return 1; }
    pv_shell input keyevent KEYCODE_BACK > /dev/null
    pva_on Settings 20
}

rel_add_account() {
    pva_tap pv.tab.Passwords && pva_on Passwords 20 || return 1
    pva_add_account "$REL_ACCOUNT" release.user@example.uz "$REL_PASSWORD" https://release.example.uz "Release note"
}

# One reading of the clipboard from outside the app: REL_NOW (the device's clock, ms), REL_HAS, REL_CLIP
# (the app's token; empty when the clip is not the app's) and REL_SENSITIVE.
rel_clip() {
    local d
    d=$(pvc_describe) || { pv_log "the clipboard could not be read"; return 1; }
    REL_NOW=$(printf '%s\n' "$d" | pvc_field now)
    REL_HAS=$(printf '%s\n' "$d" | pvc_field has)
    REL_CLIP=$(printf '%s\n' "$d" | pvc_field token)
    REL_SENSITIVE=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ -n "$REL_NOW" ]
}

# rel_copy: on the account's screen, the password's copy button: a clip of the app's (a 32-digit token),
# flagged sensitive, that holds the password; the first-copy tip closed; the status row shown.
rel_copy() {
    local i text
    pva_on Entry 20 || return 1
    pvc_clear || return 1
    pva_tap pv.entry.password.copy || return 1
    for i in $(seq 1 20); do
        rel_clip || return 1
        [ -n "$REL_CLIP" ] && break
        pv_sleep 0.2
    done
    [ -n "$REL_CLIP" ] || { pv_log "no clip of the app's after Copy (has=$REL_HAS)"; return 1; }
    printf '%s' "$REL_CLIP" | grep -Eq '^[0-9a-f]{32}$' || { pv_log "the token is not 32 hex digits: '$REL_CLIP'"; return 1; }
    [ "$REL_SENSITIVE" = true ] || { pv_log "the sensitive flag is '${REL_SENSITIVE:-absent}'"; return 1; }
    REL_TOKEN=$REL_CLIP
    REL_DEADLINE=$((REL_NOW + REL_DELAY_MS))
    pva_tip_ok
    pva_need pv.clip.row 5 || return 1
    text=$(pvc_get | pvc_field text) || return 1
    [ "$text" = "$REL_PASSWORD" ] || { pv_log "the clip does not hold the password (${#text} characters)"; return 1; }
}

# rel_until MS: returns when the device's clock reads MS.
rel_until() {
    local left
    rel_clip || return 1
    left=$(($1 - REL_NOW))
    [ "$left" -le 0 ] || pv_sleep "$((left / 1000)).$(printf '%03d' $((left % 1000)))"
}

# rel_clear: Home; 4 s before its time the clip is still there, 4 s after it at the latest it is gone and
# nothing is left (from API 28 on no clip; on Android 7 an empty one).
rel_clear() {
    local text
    pv_shell input keyevent KEYCODE_HOME > /dev/null || return 1
    rel_until $((REL_DEADLINE - 4000)) && rel_clip || return 1
    [ "$REL_CLIP" = "$REL_TOKEN" ] || { pv_log "$((REL_DEADLINE - REL_NOW)) ms before its time the clip is gone already"; return 1; }
    while :; do
        rel_clip || return 1
        [ "$REL_CLIP" = "$REL_TOKEN" ] || break
        [ "$REL_NOW" -lt $((REL_DEADLINE + 4000)) ] || { pv_log "the clip is still there $((REL_NOW - REL_DEADLINE)) ms after its time"; return 1; }
        pv_sleep 0.2
    done
    pv_log "the clip was gone $((REL_NOW - REL_DEADLINE + REL_DELAY_MS)) ms after the copy was first seen"
    if [ "$API" -ge 28 ]; then
        [ "$REL_HAS" = false ] || { pv_log "the clipboard is not empty"; return 1; }
    else
        text=$(pvc_get | pvc_field text) || return 1
        [ -z "$REL_CLIP" ] && [ -z "$text" ] || { pv_log "the clipboard is not empty"; return 1; }
    fi
}

# rel_master_password_screen: the lock screen with the master-password field, no pad, no TEST BUILD line;
# its reason line is logged (lock.md F18 step 2 says it once).
rel_master_password_screen() {
    pva_on Locked 30 && pva_need pv.lock.password 20 || return 1
    pv_log "the lock screen: $(rel_texts | tr '\n' ';' | cut -c1-400)"
    pva_absent pv.lock.pad || { pv_log "the lock screen shows a pad"; return 1; }
    pva_absent pv.lock.testBuild || { pv_log "the lock screen shows the TEST BUILD line"; return 1; }
    ! rel_texts | grep -qF -- "$(rel_string lock_test_build)" || { pv_log "the lock screen says TEST BUILD"; return 1; }
}

rel_back_locked() { pva_relaunch && rel_master_password_screen; }
rel_unlock() { pva_unlock Passwords && pva_row_with "$REL_ACCOUNT" 15; }

rel_lock_now() {
    local i
    for i in 1 2 3 4; do pva_present pv.tab.Passwords 2 && break; pv_shell input keyevent KEYCODE_BACK > /dev/null; pv_sleep 1; done
    pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_tap pv.list.lockNow && rel_master_password_screen
}

# rel_backup: Settings › Backup, the master password, the picker's Save into Download: "Backup saved", the
# file in Download.
rel_backup() {
    pv_shell "rm -f $PVA_DOWNLOAD/PasswordVault-backup-*" > /dev/null 2>&1
    [ "$API" -lt 30 ] || pv_shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
    rel_settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    if pva_present pv.backup.continue 3; then pva_tap pv.backup.continue || return 1; fi
    pva_need pv.backup.password 20 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_picker_save && pva_need pv.backup.done 60 || return 1
    pv_log "$(rel_texts | tr '\n' ';' | cut -c1-300)"
    REL_BACKUP=$(pva_in_download '^PasswordVault-backup-.*\.pvault$' | tail -n 1)
    [ -n "$REL_BACKUP" ] || { pv_log "no backup in Download"; return 1; }
    pv_log "the backup: $REL_BACKUP"
    pva_tap pv.backup.done && pva_on Settings 20
}

rel_uninstall() {
    pv_adb uninstall "$PV_PACKAGE" | grep -q '^Success' && pva_in_download "^$(printf '%s' "$REL_BACKUP" | sed 's/[.]/[.]/g')\$" > /dev/null
}

rel_reinstalled() {
    local out
    out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$REL_APK_USED" 2>&1)
    printf '%s' "$out" | grep -q '^Success' || { pv_log "adb install -r: $out"; return 1; }
    pva_start && pva_on Welcome 60
}

# rel_restore: Welcome's "Restore from a backup file", the file in the picker, its master password, the
# summary, Restore: the list holds the account, with nothing over it (a phone without secure hardware is
# offered no PIN after a restore either).
rel_restore() {
    pva_tap pv.open.Restore && pva_on Restore 20 || return 1
    pva_need pv.backup.choose 20 && pva_tap pv.backup.choose || return 1
    pva_picker_open "$REL_BACKUP" || return 1
    pva_need pv.backup.password 30 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.restore 60 || return 1
    pv_log "the restore's summary: $(pva_text pv.backup.summary 2> /dev/null)"
    pva_tap pv.backup.restore && pva_unlocked Passwords 120 && pva_settle Passwords 90 && pva_row_with "$REL_ACCOUNT" 15
}

# rel_run SERIAL: the release steps on one device; its own summary (the part runs it in a subshell of this
# script, whose counts it must not take).
rel_run() {
    PV_PASSED=0
    PV_FAILED=0
    PV_SKIPPED=0
    pv_use "$1"
    pva_init
    API=$(pv_api)
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-180}
    local badging
    badging=$("$PV_ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$RELEASE_APK" 2> /dev/null | head -n 1)
    REL_CODE=$(printf '%s' "$badging" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
    REL_NAME=$(printf '%s' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
    if rel_arm; then REL_APK_USED=$RELEASE_APK; else REL_APK_USED=$RELEASE_X86_APK; fi
    echo "release: $PV_SERIAL (API $API, ABIs $(pv_shell getprop ro.product.cpu.abilist | tr -d '\r')): $REL_APK_USED, $REL_NAME ($REL_CODE)"
    if ! rel_arm; then
        pv_step "the release APK itself is refused here: INSTALL_FAILED_NO_MATCHING_ABIS (no ARM code on this image; docs/release step 2.13)" -- rel_refused_without_arm
        [ -n "$RELEASE_X86_APK" ] && [ -f "$RELEASE_X86_APK" ] || { pv_report FAIL "an APK for this device" "no --release-x86-apk"; pv_summary; return 1; }
    fi
    pv_step "checks/3B.sh secure with this APK: FLAG_SECURE, screencap black, the emulator's own screenshot not" -- eval '"$EMU/checks/3B.sh" secure "$PV_SERIAL" --apk "$REL_APK_USED" > "$LOGS/secure-$API.log" 2>&1; rc=$?; grep -E "^(PASS|FAIL|SKIPPED|SUMMARY|NOTE)" "$LOGS/secure-$API.log" | sed "s/^/     /"; [ $rc = 0 ]'
    pv_step "a fresh install (adb install -r: Success)" -- pva_install "$REL_APK_USED" || { pv_summary; return 1; }
    pv_step "the installed app is $REL_NAME ($REL_CODE) and not debuggable (no run-as)" -- rel_installed
    pv_step "no debug receiver: dumpsys package names none, and the debug command status gets no answer" -- rel_no_receiver
    pv_step "the app starts on Welcome" -- eval 'pva_start && pva_on Welcome 60' || { pv_summary; return 1; }
    pv_step "setup with the suggested words: the list opens, with no \"Choose a PIN\" and no \"Make backups\" (keys in software are refused: lock.md 4.5, F18)" -- rel_set_up || { pv_summary; return 1; }
    pv_step "Settings: the PIN row is off and says \"This phone can’t keep a PIN or fingerprint key in protected hardware.\"" -- rel_no_pin_row
    pv_step "Settings › Version: \"Version $REL_NAME ($REL_CODE)\", no \"Test build\" line" -- rel_version
    pv_step "an account added through the form; its screen" -- rel_add_account || { pv_summary; return 1; }
    pv_step "Copy: the clip is flagged sensitive, has a token and holds the password; the status row is shown" -- rel_copy
    pv_step "with the app in the background the clip is gone at its time (setup's 1-minute delay)" -- rel_clear
    pv_step "back in front after more than 30 s: the master-password screen, no pad, no TEST BUILD line" -- rel_back_locked
    pv_step "the master password opens the list with the account" -- rel_unlock || { pv_summary; return 1; }
    pv_step "Lock now: the master-password screen again; the master password opens the list" -- eval 'rel_lock_now && rel_unlock'
    pv_step "a backup into Download through the system's picker: \"Backup saved\"" -- rel_backup || { pv_summary; return 1; }
    pv_step "adb uninstall: the app and its data go, the backup stays in Download" -- rel_uninstall || { pv_summary; return 1; }
    pv_step "installed again (adb install -r): Welcome" -- rel_reinstalled || { pv_summary; return 1; }
    pv_step "Welcome's \"Restore from a backup file\" with that backup and its master password: the account is back" -- rel_restore
    pv_step "the master-password screen after Lock now; the master password opens the restored list" -- eval 'rel_lock_now && rel_unlock'
    pv_step "no crash" -- pva_no_crash
    pv_shell "rm -f $PVA_DOWNLOAD/PasswordVault-backup-*" > /dev/null 2>&1
    pva_rotate 0
    pv_summary
}

release_part() {
    [ -n "$RELEASE_APK" ] && [ -f "$RELEASE_APK" ] || { echo "   no --release-apk"; return 1; }
    in_parallel release rel_run -- 24 37
}

# ---- arguments ------------------------------------------------------------------------------------------
ALL_PARTS=(merged connected smoke staging debug lock window i18n walk a11y wave5 wave7)
DEVICE_PARTS=(connected smoke staging debug lock window i18n walk a11y wave5 wave7 release)
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
        --device)
            [ $# -ge 2 ] && [[ "$2" =~ ^(24|28|37)=emulator-[0-9]+$ ]] || pv_usage "${BASH_SOURCE[0]}"
            OVERRIDE[${2%%=*}]=${2#*=}
            shift ;;
        --release-apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; RELEASE_APK=$(realpath "$2"); shift ;;
        --release-x86-apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; RELEASE_X86_APK=$(realpath "$2"); shift ;;
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
PV_ANDROID_HOME=${PV_ANDROID_HOME:-/home/asus/Android/Sdk}

# A part run on some of the levels keeps its logs apart (LOGS_ROOT/PART-api-28-37), so that the same part
# run on the other levels does not remove them.
LEVELS_SUFFIX=""
[ "${APIS[*]}" = "24 28 37" ] || LEVELS_SUFFIX=-api-$(IFS=-; echo "${APIS[*]}")

mkdir -p "$LOGS_ROOT" "$SHOTS"
echo "wave 8 integration check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]} on API ${APIS[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R7"
    pv_step "the head of w8B-8.1 is in HEAD" -- heads_merged
fi
if needs_devices; then
    echo
    echo "== devices: API ${APIS[*]}"
    if ! pv_step "$([ $no_build = 1 ] && echo "the APKs built before" || echo "build the debug, staging, backupCheck and test APKs"), copied to ${APK_DIR#"$REPO"/}" -- build_apks \
        || ! pv_step "the devices run (boot.sh --integrator, or --device)" -- devices; then
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
            pv_step "every device-test class passed on each device with at least its tests (614, 616, 627)" -- device_classes
            studio_runs "$began" ;;
        smoke)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "smoke.sh staging with its stages of 3A, 3B, 4A, wave 5 and the app's language" -- in_parallel smoke-staging smoke_staging -- 24 28 37
            pv_step "smoke.sh: no step failed or was skipped" -- summary_clean smoke-staging 24 28 37 ;;
        staging)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "checks/3A.sh skeleton, staging build (step 3 needs run-as)" -- in_parallel skeleton-staging skeleton_staging -- 24 28 37
            pv_step "session/autolock.sh, staging build" -- in_parallel autolock-staging autolock_staging -- 24 28 37
            pv_step "setup/create.sh, staging build" -- in_parallel create-staging create_staging -- 24 28 37
            pv_step "clipboard/clear.sh, staging build" -- in_parallel --quiet clear-staging clear_staging -- 24 28 37
            pv_step "shell/walk.sh, staging build" -- in_parallel walk-staging walk_staging -- 24 28 37
            pv_step "i18n/matrix.sh's parts without pseudo-locales or run-as, and i18n/e11.sh, staging build" -- in_parallel --quiet i18n-staging i18n_staging -- 24 28 37
            pv_step "every SKIPPED step needs a debuggable build or root (the part debug runs them)" -- skipped_need_debug skeleton-staging autolock-staging create-staging clear-staging walk-staging i18n-staging -- 24 28 37 ;;
        debug)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "checks/3A.sh skeleton, debug build (step 3: the vault files with run-as)" -- in_parallel skeleton-debug skeleton_debug -- 24 28 37
            pv_step "session/autolock.sh, debug build (the delay in pv_settings, Immediately)" -- in_parallel autolock-debug autolock_debug -- 24 28 37
            pv_step "setup/create.sh --vault-file, debug build" -- in_parallel create-debug create_debug -- 24 28 37
            pv_step "clipboard/clear.sh, debug build (the delay set with run-as; the kill on API 24)" -- in_parallel --quiet clear-debug clear_debug -- 24 28 37
            pv_step "the debug runs skipped no step for want of a debuggable build" -- debug_skipped_nothing ;;
        lock)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "lock.md E1 to E17 on their levels (lock/all.sh), staging build: no check failed" -- lock_staging
            pv_step "each check that skipped a step with staging, again with the debug build on that level: no check failed" -- lock_debug_skipped
            studio_runs "$began" | sed 's/^/   /' ;;
        window)
            pv_step "tools/emu/window/all.sh: keyboard, autofill, overlay, state, a11y and capture on API 28 and 37, a11y-old on API 24, debug and staging" -- window_checks ;;
        i18n)
            pv_step "6.1: tools/emu/i18n/all.sh (the matrix of i18n.md 5 and E11's language step), debug build" -- i18n_all ;;
        walk)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "6.2: layout/walk.sh, both parts in en-XA and ar-XB at 320 dp and 200 %: 1718 checks passed on each level, none failed or skipped" -- in_parallel --quiet pseudo layout_walk -- 24 28 37
            if has_level 37; then
                pv_step "6.2's walk in en, uz, uz-Cyrl and ru on API 37 at 320 dp and 200 %: no finding but Russian's hyphenation" -- real_walk "${SER[37]}"
            fi
            pv_step "the walk removed its test APK" -- no_test_apk ;;
        a11y)
            if has_level 37; then
                pv_step "the test APK is not on the devices" -- no_test_apk
                pv_step "8.1's accessibility walk on API 37 at the phone's own size: $A11Y_SCREENS screens, none failed, nothing broken" -- a11y_walk "${SER[37]}" a11y-plain
                pv_step "8.1's accessibility walk on API 37 at 320 dp and 200 % with the layout probe: $A11Y_SCREENS screens, none failed, nothing broken" -- a11y_walk "${SER[37]}" a11y-layout --layout
                pv_step "the walk removed its test APK" -- no_test_apk
            else
                echo "   API 37 is not among --levels"
            fi ;;
        wave5)
            pv_step "the test APK is not on the devices" -- no_test_apk
            pv_step "checks/5.sh's wave5: build-plan 7 lines 1 to 7, U 12 checks 12 and 15, decision 21, debug build, API 37 and 24" -- in_parallel wave5 w5_debug -- 37 24 ;;
        wave7)
            if has_level 37; then
                pv_step "checks/5.sh's wave7: the fifty accounts (U 5.16, a guided change, decision 23) and 7.3's walk, debug build, API 37" -- in_parallel wave7 w7_debug -- 37
                pv_step "7.2: change/guided.sh, debug build, API 37" -- in_parallel guided guided -- 37
                pv_step "the test APK is not on the devices" -- no_test_apk
            else
                echo "   API 37 is not among --levels"
            fi ;;
        release)
            pv_step "the release build on API 24 and 37: install, no debug receiver, setup without a PIN, an account, copy and clear, lock and the master password, a backup and a restore" -- release_part ;;
    esac
done
if needs_devices || wants stop; then
    if [ $keep = 1 ]; then
        echo
        echo "== the devices keep running (--keep): ${SER[*]:-}"
    else
        [ ${#SER[@]} -gt 0 ] || for api in "${APIS[@]}"; do SER[$api]=${OVERRIDE[$api]:-emulator-$(pv_integrator_port "$api")}; done
        echo
        pv_step "the devices are stopped" -- stop_devices
    fi
fi
echo
PV_SERIAL="" pv_summary
