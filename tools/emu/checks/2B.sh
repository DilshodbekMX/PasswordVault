#!/usr/bin/env bash
# tools/emu/checks/2B.sh - the integration check of sub-wave 2B, which closes wave 2 (build-plan.md
# section 4, "Integration check for wave 2"): the six package branches merged; tools/check.sh all;
# every connected test on the integrator's API 24, 28 and 37 devices; the debug app starting on all
# three; smoke.sh staging on API 24 and 37.
#
#   tools/emu/checks/2B.sh [jvm] [device] [-- GRADLE-ARGS...]
#
#   jvm     - w2B-2.7 to w2B-2.12 are merged into HEAD (each branch is an ancestor of it);
#           - tools/check.sh all;
#           - every JVM test class of packages 2.7 and 2.9 to 2.12 has a report from that check
#             with no failure, no error and no skipped test, and each package ran at least the
#             tests it reported (2.8 has device tests only). :core's classes are read from its last
#             run (fa-IR); :app's from a run of its own, because check.sh's offline part reruns
#             :app's test task filtered to one class, which replaces the reports of the unfiltered
#             run.
#   device  the integrator's devices, booted together (boot.sh --integrator: 5584 is API 24, 5582
#           API 28, 5580 API 37), with this worktree's debug and staging APKs:
#           - tools/emu/adb.sh --connected on the three: :app:connectedDebugAndroidTest with
#             ANDROID_SERIAL set to them, and every serial has a passing report (BUILD SUCCESSFUL
#             alone does not count);
#           - every device-test class of packages 2.5, 2.7, 2.8 and 2.9 ran on each device with no
#             failure and at least as many tests as on its package's branch (two of 2.7's tests
#             run only from API 30 and 37, by @SdkSuppress);
#           - the debug app starts on each of the three (smoke.sh debug: install, start, in front
#             and running after 5 s);
#           - smoke.sh staging on API 24 and 37 (install and start);
#           and, beyond the plan's list, on the debug build: the `locale` debug command (iface-41)
#           answers through am broadcast on API 37 and switches the language without restarting
#           the app; package 2.5's shell/walk.sh walks the shell's routes on API 24 and 37, now
#           under 2.8's window policy and theme. The devices are stopped at the end.
# With no part named, both run. Arguments after -- go to every Gradle call (for example
# --no-build-cache).
#
# Prints PASS or FAIL per step and a summary; logs in build/emu-checks/2B/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error. Written by the integrator of sub-wave 2B; later
# integrators may run it again while the six branches exist.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

parts=()
gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        jvm | device) parts+=("$1") ;;
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=(jvm device)
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }

LOGS="$REPO/build/emu-checks/2B"
rm -rf "$LOGS"
mkdir -p "$LOGS"
BRANCHES=(w2B-2.7 w2B-2.8 w2B-2.9 w2B-2.10 w2B-2.11 w2B-2.12)
APIS=(24 28 37)
declare -A SER=()
echo "sub-wave 2B integration check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')); logs in ${LOGS#"$REPO"/}"

# ---- jvm ----------------------------------------------------------------------------------------
branches_merged() {
    local b bad=0
    for b in "${BRANCHES[@]}"; do
        if ! git -C "$REPO" rev-parse --verify -q "refs/heads/$b" > /dev/null; then
            echo "   $b: no such branch"
            bad=1
        elif git -C "$REPO" merge-base --is-ancestor "$b" HEAD; then
            echo "   $b: $(git -C "$REPO" rev-parse --short "$b") is in HEAD"
        else
            echo "   $b: $(git -C "$REPO" rev-parse --short "$b") is not merged"
            bad=1
        fi
    done
    return $bad
}

full_check() {
    "$REPO/tools/check.sh" all ${gradle_args[@]+-- "${gradle_args[@]}"} > "$LOGS/check-all.log" 2>&1
    local rc=$?
    sed -n '/^== Summary/,$p' "$LOGS/check-all.log"
    return $rc
}

# check.sh's offline part runs :app's test task again, filtered to OfflineSourceScanTest, and that
# run's reports replace those of its unit part. So :app's whole test task runs once more here, on
# the same inputs as the unit part (the build cache usually hands back that run's own results).
app_reports() {
    "$REPO/tools/gradle.sh" :app:testDebugUnitTest ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/app-tests.log" 2>&1
    local rc=$?
    grep -E '^BUILD|actionable tasks' "$LOGS/app-tests.log" | sed 's/^/   /'
    return $rc
}

package_tests() {
    python3 - "$REPO/core/build/test-results/testDebugUnitTest" "$REPO/app/build/test-results/testDebugUnitTest" <<'EOF'
import os, sys, xml.etree.ElementTree as ET
core_results, app_results = sys.argv[1:3]
prefix = "io.github.dilshodbekmx.passwordvault."

def report(module, name):
    path = os.path.join(core_results if module == "core" else app_results, f"TEST-{prefix}{name}.xml")
    if not os.path.isfile(path):
        return None
    r = ET.parse(path).getroot()
    return {k: int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")}

# Each package's JVM test classes and the number of tests its agent reported; a package must run at
# least that many. 2.11's directory also holds CreditTest and PracticeTest, which are 1.3's; 2.12's
# wordlist.WordListBuilder is a program run only with -Ppv.strengthList, skipped otherwise.
packages = {
    "2.7": ("core", ["core.integration.VaultRoundTripTest"], 1),
    "2.9": ("app", ["settings.PrefsAppSettingsTest", "i18n.AppLanguagesTest", "i18n.SortTextTest",
                    "i18n.ResourcesTest", "i18n.ResourceCheckTest"], 39),
    "2.10": ("core", ["core.lock.record.RecordCodecTest", "core.lock.record.FileRecordStoreTest",
                      "core.lock.record.FakeRecordFilesTest", "core.lock.policy.EntryPolicyTest",
                      "core.lock.policy.KeystoreErrorsTest"], 105),
    "2.11": ("core", ["core.lock.guard.PinWrapTest", "core.lock.guard.PinGuardTest", "core.lock.guard.PinGuardRandomTest",
                      "core.lock.guard.GuardModelTest", "core.lock.guard.StartUpTest", "core.lock.guard.SamePinTest",
                      "core.lock.guard.ForgivingTest", "core.lock.guard.MasterPasswordGuardTest",
                      "core.lock.guard.OwnerSimulationTest", "core.lock.guard.FakeRecordStoreTest",
                      "core.lock.guard.FakeKeysTest"], 115),
    "2.12": ("core", ["core.strength.StrengthMeterTest", "core.strength.MasterPasswordRulesTest",
                      "core.strength.SecurityCheckTest", "core.strength.WordListTest",
                      "core.strength.StrengthPerformanceTest"], 52),
}
bad = 0
for package, (module, classes, at_least) in packages.items():
    ran = 0
    wrong = 0
    for name in classes:
        r = report(module, name)
        if r is None:
            print(f"   {package}: no report of {name}")
            wrong = 1
            continue
        if r["failures"] or r["errors"] or r["skipped"] or r["tests"] == 0:
            print(f"   {package}: {name}: {r}")
            wrong = 1
        ran += r["tests"] - r["skipped"]
    if ran < at_least:
        print(f"   {package}: {ran} tests ran, fewer than the {at_least} the package reported")
        wrong = 1
    print(f"   {package}: {len(classes)} classes, {ran} tests ran (reported: {at_least})" + ("" if wrong else ", none failed or skipped"))
    bad |= wrong
sys.exit(bad)
EOF
}

# ---- device -------------------------------------------------------------------------------------
build_apks() {
    "$REPO/tools/gradle.sh" :app:assembleDebug :app:assembleStaging ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/assemble.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS/assemble.log" | sed 's/^/   /'
    return $rc
}

boot_devices() {
    local api rc=0 pid
    declare -A pids=()
    for api in "${APIS[@]}"; do
        "$EMU/boot.sh" --integrator "$api" > "$LOGS/serial-$api" 2> "$LOGS/boot-$api.log" &
        pids[$api]=$!
    done
    for api in "${APIS[@]}"; do
        wait "${pids[$api]}"
        pid=$?
        SER[$api]=$(cat "$LOGS/serial-$api" 2> /dev/null)
        echo "   API $api: ${SER[$api]:-no device} (boot.sh exit $pid)"
        [ $pid -eq 0 ] && [ "${SER[$api]}" = "emulator-$(pv_integrator_port "$api")" ] || rc=1
    done
    return $rc
}

connected_tests() {
    "$EMU/adb.sh" --connected "${SER[24]}" "${SER[28]}" "${SER[37]}" ${gradle_args[@]+-- "${gradle_args[@]}"} > "$LOGS/connected.log" 2>&1
    local rc=$?
    grep -E '^(PASS|FAIL) connected|^BUILD|tests completed' "$LOGS/connected.log" | sed 's/^/   /'
    return $rc
}

# Each device-test class, by package, with the tests it ran on the package's branch on API 24, 28
# and 37. MemoryLimiterTest runs on API 37 only and one test of NativeArgon2idTest from API 30
# (@SdkSuppress).
device_classes() {
    python3 - "$REPO/app/build/outputs/androidTest-results/connected/debug" "${SER[24]}" "${SER[28]}" "${SER[37]}" <<'EOF'
import collections, glob, sys, xml.etree.ElementTree as ET
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
    "ui.common.FieldPolicyTest": ("2.8", 3, 3, 3),
    "ui.common.MaskedValueNodesTest": ("2.8", 2, 2, 2),
    "ui.common.PinPadNodesTest": ("2.8", 5, 5, 5),
    "ui.common.SecureFieldSavedStateTest": ("2.8", 1, 1, 1),
    "i18n.FormattersTest": ("2.9", 6, 6, 6),
    "i18n.LocaleCommandTest": ("2.9", 2, 2, 2),
    "i18n.SortKeysTest": ("2.9", 8, 8, 8),
}
# (device, class) -> [ran, failed, skipped]
seen = collections.defaultdict(lambda: [0, 0, 0])
for f in sorted(glob.glob(d + "/TEST-*.xml")):
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
    others = sorted(cls for (dev, cls) in seen if dev == serial and cls not in expected)
    print(f"   API {api} ({serial}): {total} tests; " + ", ".join(f"{p} {r}/{w}" for p, (r, w) in sorted(by_package.items()))
          + (f"; other classes: {', '.join(others)}" if others else ""))
sys.exit(bad)
EOF
}

smoke() { # VARIANT API
    "$EMU/smoke.sh" "$1" "${SER[$2]}" > "$LOGS/smoke-$1-$2.log" 2>&1
    local rc=$?
    grep -E '^(FAIL|SUMMARY)' "$LOGS/smoke-$1-$2.log" | sed 's/^/   /'
    return $rc
}

# The language command of package 2.9, listed in DebugCommands by iface-41: `locale ru-rRU`, then
# `locale system`, through am broadcast, on the debug app that smoke.sh debug left running.
locale_command() {
    local s=${SER[37]} before after ru back
    before=$(PV_SERIAL=$s pv_shell pidof "$PV_PACKAGE" 2> /dev/null | tr -d '\r')
    [ -n "$before" ] || { echo "   the app is not running on $s"; return 1; }
    ru=$(PV_SERIAL=$s pv_shell am broadcast -a "$PV_PACKAGE.debug.CMD" -p "$PV_PACKAGE" --es cmd "'locale ru-rRU'" 2>&1 | tr -d '\r' | grep 'result=')
    back=$(PV_SERIAL=$s pv_shell am broadcast -a "$PV_PACKAGE.debug.CMD" -p "$PV_PACKAGE" --es cmd "'locale system'" 2>&1 | tr -d '\r' | grep 'result=')
    after=$(PV_SERIAL=$s pv_shell pidof "$PV_PACKAGE" 2> /dev/null | tr -d '\r')
    echo "   locale ru-rRU: $ru"
    echo "   locale system: $back"
    echo "   the app's pid before and after: $before, $after"
    [[ "$ru" == *'result=1'*'shown: RU'* ]] && [[ "$back" == *'result=1'*'shown: EN'* ]] && [ "$before" = "$after" ]
}

walk() { # API
    "$EMU/shell/walk.sh" "${SER[$1]}" > "$LOGS/walk-$1.log" 2>&1
    local rc=$?
    grep -E '^(FAIL|SKIPPED|SUMMARY)' "$LOGS/walk-$1.log" | sed 's/^/   /'
    return $rc
}

stop_devices() {
    local api rc=0
    for api in "${APIS[@]}"; do
        [ -n "${SER[$api]:-}" ] && [ -f "$PV_REG/${SER[$api]}" ] || continue
        "$EMU/stop.sh" "${SER[$api]}" >> "$LOGS/stop.log" 2>&1 || rc=1
    done
    for api in "${APIS[@]}"; do
        if [ -f "$PV_REG/emulator-$(pv_integrator_port "$api")" ]; then
            echo "   emulator-$(pv_integrator_port "$api") is still registered"
            rc=1
        fi
    done
    return $rc
}

if wants jvm; then
    echo
    echo "== jvm: packages 2.7 to 2.12"
    pv_step "the six branches are merged" -- branches_merged
    pv_step "tools/check.sh all" -- full_check
    pv_step ":app's tests again, for their reports" -- app_reports
    pv_step "every JVM test class of 2.7 and 2.9 to 2.12 ran and passed" -- package_tests
fi
if wants device; then
    echo
    echo "== device: the integrator's API 24, 28 and 37 devices"
    if pv_step "build the debug and staging APKs" -- build_apks && pv_step "boot.sh --integrator 24, 28 and 37" -- boot_devices; then
        pv_step "connectedDebugAndroidTest on API 24, 28 and 37 (adb.sh --connected)" -- connected_tests
        pv_step "every device-test class of 2.5, 2.7, 2.8 and 2.9 passed on each device" -- device_classes
        for api in "${APIS[@]}"; do
            pv_step "the debug app starts on API $api (smoke.sh debug)" -- smoke debug "$api"
        done
        pv_step "extra: the locale command switches the language without a restart on API 37" -- locale_command
        pv_step "extra: the shell's routes walk on API 37 (shell/walk.sh)" -- walk 37
        pv_step "extra: the shell's routes walk on API 24 (shell/walk.sh)" -- walk 24
        pv_step "smoke.sh staging on API 24 (install and start)" -- smoke staging 24
        pv_step "smoke.sh staging on API 37 (install and start)" -- smoke staging 37
    fi
    pv_step "the devices are stopped" -- stop_devices
fi
echo
PV_SERIAL="" pv_summary
