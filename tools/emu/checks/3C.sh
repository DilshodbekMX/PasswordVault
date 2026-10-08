#!/usr/bin/env bash
# tools/emu/checks/3C.sh - the device check of round R2: sub-wave 3C and package 5.1 (build-plan.md
# section 12, "The integration in two steps", step 2, and the row R2 of its table: "the skeleton check
# again, and 5.1's device tests"). The merge ran tools/check.sh all before it tagged wave-3C-merged.
# Packages 3.13 to 3.15 are :core code with JVM tests only, and the app's lock is the gate of 3.2 until
# 4.1, so what a device sees of this round is 5.1: the file picker's plumbing, which MainActivity's
# hook now starts with every activity, and its device tests.
#
#   tools/emu/checks/3C.sh [PART...] [--shots DIR] [--keep] [-- GRADLE-ARGS...]
#
# The parts run in this order, all of them when none is named, on the integrator's devices (5584 is
# API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used as
# it is, the others are booted together (boot.sh --integrator). The debug and staging APKs are built
# first (tools/gradle.sh). The three devices are stopped at the end unless --keep is given; the part
# "stop" does nothing else.
#   merged     the heads that round R2 merged (w3C-3.13, w3C-3.14, w3C-3.15, w5-5.1) are in HEAD; the
#              heads of w4A-4.2 and w4A-4.3, which wait for the lock manager of 4.1, are not (once the
#              tag wave-4A-merged is in HEAD that line is no longer looked at).
#   files      package 5.1 on API 28, where its tests had not run (the package checked API 24 and 37):
#              - formats.md 2 rule 4: what the platform's MIME table holds, read with MimeTypeMap on
#                each of the three devices (PvMimeTable.java, as the shell user), is what the rule
#                says: .csv has the type text/comma-separated-values everywhere; on Android 17 text/csv
#                maps back to .csv and json and application/json map to each other; on Android 7 and 9
#                text/csv, .json and application/json have no mapping; xlsx and its type map to each
#                other everywhere; application/octet-stream has no extension before Android 10 and
#                .bin on Android 17. And with that table a provider keeps every suggested name
#                (FileUtils.splitFileName's rule, as DocumentNamesTest states it);
#              - adb.sh --connected on the API 28 device with the runner's package argument: the
#                seven classes of APP-AT/files/ and nothing else, 50 tests, none failed.
#   connected  adb.sh --connected on the three devices: every connected test, and every serial has a
#              passing report (BUILD SUCCESSFUL alone does not count). A class that failed is run once
#              more, alone, on that device (one run per class: of a comma-separated class list the
#              runner took only the first); it counts as passed when it passes alone, and the step
#              names it as a flake. Then every device-test class of packages 2.5, 2.7 to 2.9, 3.3 to
#              3.9, w3B-carry and 5.1 ran on each device with no failure and at least the tests it has
#              there (@SdkSuppress leaves some out on API 24 and 28): 358 on API 24, 359 on 28, 363 on
#              37 at wave-3C-merged.
#   skeleton   the skeleton steps 1 to 9 of checks/3A.sh (its skeleton mode) with the debug build on API
#              24, 28 and 37 at once, then with the staging build on API 24 and 37 at once, on the
#              devices that ran the connected tests: each begins with a fresh install, and the test
#              APK (it exports a documents provider, a picker and a receiver) is removed first should a
#              run have left it. Since 5.1 every start of the activity registers the picker's two
#              launchers and makes the process's FileTaskStore: step 5 (force-stop and start) and step
#              9's kill in the background and return run that path, under R8 in staging. On API 37 the
#              screenshots of steps 1, 2 and 4 (debug) go to --shots DIR (default
#              /home/asus/pvwork/shots-3C; only step*.png there is written).
#   smoke      smoke.sh staging on API 24 and 37 at once (20 steps).
#
# After a part that drives the app, a NOTE names every Run that Android Studio started on one of the
# three devices meanwhile (its idea.log; tools/emu/README.md, "Device facts"): such a Run stops,
# reinstalls and starts the app under whatever step is running.
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/3C/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error, 3 for a refused serial. Written by the device check of
# round R2 (build-plan.md section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SHOTS=/home/asus/pvwork/shots-3C
LOGS_ROOT=$REPO/build/emu-checks/3C
DEBUG_APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
STAGING_APK=$REPO/app/build/outputs/apk/staging/app-staging.apk
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug
RUNNER_ARG=-Pandroid.testInstrumentationRunnerArguments

note() { echo "     $*"; }

# ---- merged ------------------------------------------------------------------------------------------
# BRANCH:HEAD as round R2 merged it (the merge commit fe2e9ad names them), and the two branches of R2
# that stay out until the merge of R3.
MERGED=(w3C-3.13:4900d13 w3C-3.14:ad6eb59 w3C-3.15:974bd21 w5-5.1:917f6cb)
HELD=(w4A-4.2 w4A-4.3)

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

heads_held() {
    local branch bad=0
    if git -C "$REPO" rev-parse --verify -q refs/tags/wave-4A-merged > /dev/null \
        && git -C "$REPO" merge-base --is-ancestor refs/tags/wave-4A-merged HEAD; then
        echo "   wave-4A-merged is in HEAD: round R3 has merged ${HELD[*]} since"
        return 0
    fi
    for branch in "${HELD[@]}"; do
        if ! git -C "$REPO" rev-parse --verify -q "refs/heads/$branch" > /dev/null; then
            echo "   $branch: no such branch"
            bad=1
        elif git -C "$REPO" merge-base --is-ancestor "$branch" HEAD; then
            echo "   $branch: $(git -C "$REPO" rev-parse --short "$branch") is in HEAD, before the lock manager of 4.1"
            bad=1
        else
            echo "   $branch: $(git -C "$REPO" rev-parse --short "$branch") is not in HEAD"
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

# ---- files: 5.1 on API 28 first -------------------------------------------------------------------------
PACKAGE=io.github.dilshodbekmx.passwordvault
MIME_SRC=$EMU/checks/PvMimeTable.java
MIME_JAR=$REPO/build/emu/pv-mime.jar
MIME_ON_DEVICE=/data/local/tmp/pv-mime.jar
XLSX_TYPE=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet

# Builds PvMimeTable's jar (javac of the JDK, d8 of the SDK's build tools) when it is missing or older
# than its source, as adb.sh builds its node dumper.
mime_build() {
    [ -f "$MIME_JAR" ] && [ ! "$MIME_SRC" -nt "$MIME_JAR" ] && return 0
    local java_home=${PV_JAVA_HOME:-/home/asus/.jdks/jdk-25} d8 android_jar tmp
    d8=$(ls -d "$PV_SDK"/build-tools/*/d8 2> /dev/null | sort -V | tail -n 1)
    android_jar=$(ls -d "$PV_SDK"/platforms/android-*/android.jar 2> /dev/null | sort -V | tail -n 1)
    [ -x "$java_home/bin/javac" ] && [ -n "$d8" ] && [ -n "$android_jar" ] || { pv_log "no javac, d8 or android.jar to build ${MIME_JAR##*/}"; return 1; }
    mkdir -p "$(dirname "$MIME_JAR")" || return 1
    tmp=$(mktemp -d "$(dirname "$MIME_JAR")/mime.XXXXXX") || return 1
    if "$java_home/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 -cp "$android_jar" -d "$tmp" "$MIME_SRC" > "$tmp/javac.log" 2>&1 \
        && JAVA_HOME=$java_home PATH=$java_home/bin:$PATH "$d8" --min-api 24 --output "$tmp/pv-mime.jar" "$tmp/PvMimeTable.class" > "$tmp/d8.log" 2>&1; then
        mv -f "$tmp/pv-mime.jar" "$MIME_JAR"
        rm -rf "$tmp"
        return 0
    fi
    pv_log "could not build ${MIME_JAR##*/}: $(cat "$tmp/javac.log" "$tmp/d8.log" 2> /dev/null | grep -v '^Note:' | tail -n 3)"
    rm -rf "$tmp"
    return 1
}

# mime_table SERIAL: the table's answers for the app's extensions and types, one line each.
mime_table() {
    local out
    PV_SERIAL=$1 pv_adb push "$MIME_JAR" "$MIME_ON_DEVICE" > /dev/null 2>&1 || { pv_log "could not push ${MIME_JAR##*/} to $1"; return 1; }
    out=$(PV_SERIAL=$1 PV_ADB_TIMEOUT=60 pv_adb shell "CLASSPATH=$MIME_ON_DEVICE app_process /system/bin PvMimeTable pvault csv json xlsx application/octet-stream text/csv text/comma-separated-values application/json $XLSX_TYPE" 2> /dev/null | tr -d '\r')
    [ "$(printf '%s\n' "$out" | tail -n 1)" = "PVMIME OK" ] || { pv_log "PvMimeTable on $1: ${out:-no answer}"; return 1; }
    printf '%s\n' "$out" | sed '$d'
}

# The table of each device against formats.md 2 rule 4, and the rule's conclusion drawn from it: a
# provider keeps a suggested name when its type is the one the table gives the name's extension (a name
# whose extension the table does not know counts as application/octet-stream), when the table's
# extension for the type is the name's own, or when the type has no extension to append
# (application/octet-stream never has: FileUtils.splitFileName, Android 7 to 17).
mime_tables() {
    local api bad=0
    mime_build || return 1
    for api in "${APIS[@]}"; do
        mime_table "${SER[$api]}" > "$LOGS/mime-$api.txt" || { bad=1; continue; }
        python3 - "$api" "$LOGS/mime-$api.txt" "$XLSX_TYPE" <<'EOF' || bad=1
import sys
api, path, xlsx = int(sys.argv[1]), sys.argv[2], sys.argv[3]
ext, typ = {}, {}
for line in open(path, encoding="utf-8"):
    kind, key, _, value = line.split()
    (ext if kind == "ext" else typ)[key] = None if value == "none" else value
OCTET, CSV, CSV_OLD, JSON = "application/octet-stream", "text/csv", "text/comma-separated-values", "application/json"
# What formats.md 2 rule 4 says the table holds: Android 17 against Android 7.0 ("as on API 24 to 28").
if api >= 29:
    rule = {"ext .csv": CSV_OLD, "type " + CSV: ".csv", "ext .json": JSON, "type " + JSON: ".json",
            "ext .xlsx": xlsx, "type " + xlsx: ".xlsx", "type " + OCTET: ".bin"}
else:
    rule = {"ext .csv": CSV_OLD, "type " + CSV: None, "ext .json": None, "type " + JSON: None}
measured = {**{"ext " + k: v for k, v in ext.items()}, **{"type " + k: v for k, v in typ.items()}}
bad = 0
print(f"   API {api}: " + "; ".join(f"{k[4:] if k.startswith('ext ') else k[5:]} -> {v or 'none'}" for k, v in measured.items()))
for key, want in rule.items():
    if measured.get(key, "missing") != want:
        print(f"   API {api}: formats.md 2 rule 4 says {key} -> {want or 'none'}; the device says {measured.get(key, 'nothing') or 'none'}")
        bad = 1
# The rule's conclusion: the names of rule 5 with the types of DocumentTypes.
names = {"PasswordVault-backup-2026-10-08.pvault": OCTET, "passwords-2026-10-08.csv": CSV,
         "passwords-2026-10-08-part2.csv": CSV, "passwords-2026-10-08.json": JSON, "passwords-2026-10-08.xlsx": xlsx}
for name, mime in names.items():
    extension = name.rsplit(".", 1)[1]
    type_of_extension = ext.get("." + extension) or OCTET
    extension_of_type = None if mime == OCTET else typ.get(mime)
    kept = mime == type_of_extension or "." + extension == extension_of_type or extension_of_type is None
    if not kept:
        print(f"   API {api}: a provider would save {name} ({mime}) as {name}{extension_of_type}")
        bad = 1
if not bad:
    print(f"   API {api}: as the rule says; a provider keeps the five suggested names")
sys.exit(bad)
EOF
    done
    return $bad
}

files_on_28() {
    rm -rf "$LOGS/results"
    mkdir -p "$LOGS/results"
    "$EMU/adb.sh" --connected "${SER[28]}" -- "$RUNNER_ARG.package=$PACKAGE.files" ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/files-28.log" 2>&1
    local rc=$?
    cp -r "$RESULTS/." "$LOGS/results/" 2> /dev/null
    grep -E '^(PASS|FAIL) connected|^BUILD' "$LOGS/files-28.log" | sed 's/^/   /'
    return $rc
}

# The classes of APP-AT/files/ with their tests: the same on every API level (no @SdkSuppress).
FILES_CLASSES="files.BackupSharingTest=3 files.DocumentNamesTest=3 files.FileTaskStoreTest=15 files.PickerContractsTest=4 files.PickerProcessTest=8 files.PlacesTest=5 files.SafDocumentIoTest=12"

files_classes() {
    # shellcheck disable=SC2086
    python3 - "$LOGS/results" "${SER[28]}" $FILES_CLASSES <<'EOF'
import collections, glob, sys, xml.etree.ElementTree as ET
d, serial = sys.argv[1:3]
want = dict((k, int(v)) for k, v in (a.split("=") for a in sys.argv[3:]))
prefix = "io.github.dilshodbekmx.passwordvault."
ran, failed = collections.Counter(), []
for f in sorted(glob.glob(d + "/TEST-*.xml")):
    for suite in ET.parse(f).getroot().iter("testsuite"):
        if serial not in [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]:
            continue
        for case in suite.iter("testcase"):
            cls = case.get("classname", "")
            cls = cls[len(prefix):] if cls.startswith(prefix) else cls
            if case.find("skipped") is not None:
                continue
            ran[cls] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{cls}.{case.get('name')}")
bad = 0
for cls, n in want.items():
    if ran[cls] != n:
        print(f"   {cls}: {ran[cls]} tests ran, not {n}")
        bad = 1
for cls in sorted(set(ran) - set(want)):
    print(f"   {cls}: {ran[cls]} tests of another package ran")
    bad = 1
for name in failed:
    print(f"   failed: {name}")
    bad = 1
print(f"   API 28 ({serial}): {sum(ran.values())} tests ran (5.1 has {sum(want.values())}), {len(failed)} failed; "
      + ", ".join(f"{c.split('.')[-1]} {ran[c]}" for c in want))
sys.exit(bad)
EOF
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

# Each device-test class, by package, with the tests it has on API 24, 28 and 37 at wave-3C-merged; a
# class run again alone (connected_tests) counts with that run's result.
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
    "ui.lock.LockPseudoLocaleTest": ("3.4", 2, 2, 2),
    "ui.lock.MasterPasswordRotationTest": ("3.4", 1, 1, 1),
    "ui.lock.MasterPasswordScreenTest": ("3.4", 6, 6, 6),
    "ui.list.ListPerformanceTest": ("3.5", 1, 1, 2),
    "ui.list.ListPseudoLocaleTest": ("3.5", 5, 5, 5),
    "ui.list.PasswordsScreenTest": ("3.5", 9, 9, 9),
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
    "files.BackupSharingTest": ("5.1", 3, 3, 3),
    "files.DocumentNamesTest": ("5.1", 3, 3, 3),
    "files.FileTaskStoreTest": ("5.1", 15, 15, 15),
    "files.PickerContractsTest": ("5.1", 4, 4, 4),
    "files.PickerProcessTest": ("5.1", 8, 8, 8),
    "files.PlacesTest": ("5.1", 5, 5, 5),
    "files.SafDocumentIoTest": ("5.1", 12, 12, 12),
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

# ---- skeleton and smoke -----------------------------------------------------------------------------------
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

skeleton_debug() {
    local shots=()
    [ "$1" != "${SER[37]}" ] || shots=(--shots "$SHOTS")
    "$EMU/checks/3A.sh" skeleton debug "$1" --apk "$DEBUG_APK" ${shots[@]+"${shots[@]}"}
}
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1" --apk "$STAGING_APK"; }
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }

# ---- arguments ------------------------------------------------------------------------------------------
ALL_PARTS=(merged files connected skeleton smoke)
parts=()
gradle_args=()
keep=0
while [ $# -gt 0 ]; do
    case "$1" in
        merged | files | connected | skeleton | smoke | stop) parts+=("$1") ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --keep) keep=1 ;;
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=("${ALL_PARTS[@]}")
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }
needs_devices() { local p; for p in files connected skeleton smoke; do wants "$p" && return 0; done; return 1; }

mkdir -p "$LOGS_ROOT"
echo "round R2 (sub-wave 3C and 5.1) device check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R2"
    pv_step "the heads of 3.13, 3.14, 3.15 and 5.1 that round R2 merged are in HEAD" -- heads_merged
    pv_step "the heads of w4A-4.2 and w4A-4.3 are not in HEAD: they wait for the merge of R3" -- heads_held
fi
if needs_devices; then
    echo
    echo "== devices: the integrator's API 24, 28 and 37"
    if ! pv_step "build the debug and staging APKs" -- build_apks || ! pv_step "the integrator's devices run (boot.sh --integrator)" -- devices; then
        parts=(stop)
    fi
fi
for part in files connected skeleton smoke; do
    wants "$part" || continue
    LOGS=$LOGS_ROOT/$part
    rm -rf "$LOGS"
    mkdir -p "$LOGS"
    echo
    echo "== $part ($(date +%T))"
    began=$(date +%s)
    case $part in
        files)
            pv_step "formats.md 2 rule 4: the platform's MIME table on API 24, 28 and 37 is what the rule says, and a provider keeps the suggested names" -- mime_tables
            pv_step "5.1's device tests alone on API 28 (adb.sh --connected, the runner's package argument)" -- files_on_28
            pv_step "the seven classes of APP-AT/files/ ran on API 28 with their 50 tests, none failed, nothing else ran" -- files_classes
            studio_runs "$began" ;;
        connected)
            pv_step "connectedDebugAndroidTest on API 24, 28 and 37 (adb.sh --connected; a failed class once more alone)" -- connected_tests
            pv_step "every device-test class of 2.5, 2.7 to 2.9, 3.3 to 3.9, w3B-carry and 5.1 passed on each device" -- device_classes
            studio_runs "$began" ;;
        skeleton)
            pv_step "the test APK with its documents provider is not on the devices" -- no_test_apk
            pv_step "the skeleton steps 1 to 9, debug build, on API 24, 28 and 37" -- in_parallel skeleton-debug skeleton_debug -- 24 28 37
            if ls "$SHOTS"/step*.png > /dev/null 2>&1; then
                echo "   screenshots (API 37, debug) in $SHOTS: $(cd "$SHOTS" && ls step*.png | tr '\n' ' ')"
            fi
            pv_step "the skeleton steps 1 to 9, staging build, on API 24 and 37" -- in_parallel skeleton-staging skeleton_staging -- 24 37 ;;
        smoke)
            pv_step "smoke.sh staging on API 24 and 37" -- in_parallel smoke smoke_staging -- 24 37 ;;
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
