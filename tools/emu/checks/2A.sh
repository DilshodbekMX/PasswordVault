#!/usr/bin/env bash
# tools/emu/checks/2A.sh - the integration check of sub-wave 2A (build-plan.md section 4): the six
# package branches merged, tools/check.sh all with VaultRoundTripTest running, and the debug app
# starting on API 37.
#
#   tools/emu/checks/2A.sh [jvm] [device] [-- GRADLE-ARGS...]
#
#   jvm     - w2A-2.1 to w2A-2.6 are merged into HEAD (each branch is an ancestor of it);
#           - tools/check.sh all;
#           - CORE-T/integration/VaultRoundTripTest ran in that check's last :core run, on the
#             real factories of 2.1, 2.2 and 2.3, and passed. It is skipped only while a factory
#             throws NotImplementedError, which no test locale changes, so here it must not be;
#           - every test class of packages 2.1 to 2.6 has a report from that check with no
#             failure, no error and no skipped test, and each package ran at least the tests it
#             reported: a class that stopped being compiled or found cannot hide behind a passing
#             check. :core's classes are read from its last run (fa-IR); 2.5's NavStateTest from
#             :app's test task, run once more on its own because check.sh's offline part reruns it
#             filtered to one class, which replaces the reports of the unfiltered run.
#   device  the integrator's API 37 device (boot.sh --integrator 37, port 5580): the debug APK is
#           built, smoke.sh debug installs it, starts it and finds it in front and running after
#           5 s, then package 2.5's shell/walk.sh walks every placeholder route of the shell (a new
#           process, setup, the tabs, lock and unlock, a rotation). The device is stopped at the end.
# With no part named, both run. Arguments after -- go to every Gradle call (for example
# --no-build-cache).
#
# Prints PASS or FAIL per step and a summary; logs in build/emu-checks/2A/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error. Written by the integrator of sub-wave 2A; later
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

LOGS="$REPO/build/emu-checks/2A"
rm -rf "$LOGS"
mkdir -p "$LOGS"
BRANCHES=(w2A-2.1 w2A-2.2 w2A-2.3 w2A-2.4 w2A-2.5 w2A-2.6)
S37=""
echo "sub-wave 2A integration check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')); logs in ${LOGS#"$REPO"/}"

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

# The reports of the check's last runs: :core (check.sh runs it under tr-TR, then fa-IR) and :app.
package_tests() { # round-trip | packages
    python3 - "$1" "$REPO/core/build/test-results/testDebugUnitTest" "$REPO/app/build/test-results/testDebugUnitTest" <<'EOF'
import os, sys, xml.etree.ElementTree as ET
what, core_results, app_results = sys.argv[1:4]
prefix = "io.github.dilshodbekmx.passwordvault."

def report(module, name):
    path = os.path.join(core_results if module == "core" else app_results, f"TEST-{prefix}{name}.xml")
    if not os.path.isfile(path):
        return None
    r = ET.parse(path).getroot()
    return {k: int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")}

if what == "round-trip":
    r = report("core", "core.integration.VaultRoundTripTest")
    print(f"   VaultRoundTripTest: {r if r else 'no report'}")
    sys.exit(0 if r and r["tests"] >= 1 and not (r["failures"] or r["errors"] or r["skipped"]) else 1)

# Each package's test classes (its directory of tests, helpers left out) and the number of tests
# its agent reported; a package must run at least that many.
packages = {
    "2.1": ("core", ["crypto.Argon2KnownAnswerTest", "crypto.JcaAesGcmTest", "crypto.DerivedKeysTest",
                     "crypto.GuardedArgon2idTest", "crypto.GatedMasterKdfTest", "crypto.BouncyCastleArgon2idTest"], 52),
    "2.2": ("core", ["vaultfile.VaultVectorsTest", "vaultfile.HeaderRulesTest", "vaultfile.BodyRulesTest",
                     "vaultfile.CanonicalFormTest", "vaultfile.CodecContractTest", "vaultfile.VaultFuzzTest"], 65),
    "2.3": ("core", ["storage.ScanTest", "storage.OpenTest", "storage.PasswordTest", "storage.CommitTest",
                     "storage.StageTest", "storage.CrashTest", "storage.HousekeepingTest", "storage.RetireTest",
                     "storage.AsideTest", "storage.NamesTest", "storage.FakeFilesTest",
                     "integration.VaultRoundTripTest"], 123),
    "2.4": ("core", ["model.EntryLimitsTest", "model.EntryRulesTest", "model.TitleTest", "model.AddAndEditTest",
                     "model.GuidedChangeTest", "model.RecentlyDeletedTest", "model.EditSequencesTest",
                     "model.ImportAndMergeTest", "model.VaultDataTest"], 71),
    "2.5": ("app", ["nav.NavStateTest"], 12),
    "2.6": ("core", ["generator.WordlistTest", "generator.PassphraseTest", "generator.PasswordGeneratorTest",
                     "generator.UnbiasedChoiceTest"], 33),
}
bad = 0
for package, (module, classes, at_least) in packages.items():
    ran = 0
    wrong = 0
    for name in classes:
        full = ("core." if module == "core" else "") + name
        r = report(module, full)
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
build_debug() {
    "$REPO/tools/gradle.sh" :app:assembleDebug ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/assemble-debug.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS/assemble-debug.log" | sed 's/^/   /'
    return $rc
}

boot_device() {
    S37=$("$EMU/boot.sh" --integrator 37 2> "$LOGS/boot-37.log")
    local rc=$?
    echo "   API 37: ${S37:-no device} (boot.sh exit $rc)"
    [ $rc -eq 0 ] && [ "$S37" = emulator-5580 ]
}

smoke_debug() {
    "$EMU/smoke.sh" debug "$S37" > "$LOGS/smoke-debug.log" 2>&1
    local rc=$?
    grep -E '^(PASS|FAIL|SKIPPED|SUMMARY)' "$LOGS/smoke-debug.log" | sed 's/^/   /'
    return $rc
}

walk_shell() {
    "$EMU/shell/walk.sh" "$S37" > "$LOGS/walk.log" 2>&1
    local rc=$?
    grep -E '^(FAIL|SKIPPED|SUMMARY)|shown: ' "$LOGS/walk.log" | sed 's/^/   /'
    return $rc
}

stop_device() {
    [ -n "$S37" ] && [ -f "$PV_REG/$S37" ] || return 0
    "$EMU/stop.sh" "$S37" > "$LOGS/stop.log" 2>&1
}

if wants jvm; then
    echo
    echo "== jvm: packages 2.1 to 2.6"
    pv_step "the six branches are merged" -- branches_merged
    pv_step "tools/check.sh all" -- full_check
    pv_step "VaultRoundTripTest ran on the real factories and passed (not skipped)" -- package_tests round-trip
    pv_step ":app's tests again, for their reports" -- app_reports
    pv_step "every test class of the six packages ran and passed" -- package_tests packages
fi
if wants device; then
    echo
    echo "== device: the debug app on API 37"
    if pv_step "build the debug APK" -- build_debug && pv_step "boot.sh --integrator 37" -- boot_device; then
        pv_step "the debug app starts on API 37 (smoke.sh debug)" -- smoke_debug
        pv_step "the shell's placeholder routes walk on API 37 (shell/walk.sh)" -- walk_shell
    fi
    pv_step "the device is stopped" -- stop_device
fi
echo
PV_SERIAL="" pv_summary
