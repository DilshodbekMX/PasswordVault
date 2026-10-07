#!/usr/bin/env bash
# tools/emu/checks/1.sh - the integration check of wave 1 (build-plan.md section 3): the four
# package branches merged into the integrator's worktree, then the full check.
#
#   tools/emu/checks/1.sh [-- GRADLE-ARGS...]
#
#   - w1-1.1, w1-1.2, w1-1.3 and w1-1.4 are merged into HEAD (each branch is an ancestor of it);
#   - tools/check.sh all;
#   - every test class of packages 1.1 to 1.4 has a report from that check's last :core run with
#     no failure and no error, and ran tests: a class that stopped being compiled or found cannot
#     hide behind a passing check. Only 1.2's three programs (SearchBenchmark, ConverterEval,
#     UzbekCyrillicStrings), which run when their pv.* property is given, are skipped as a whole.
#     The nine classes moved from prototypes/search hold at least the 527 tests of build plan 1.2.
# Wave 1 needs no device. Arguments after -- go to every Gradle call (for example
# --no-build-cache).
#
# Prints PASS or FAIL per step and a summary; logs in build/emu-checks/1/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error. Written by the integrator of wave 1; later integrators
# may run it again while the four branches exist.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"

gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
done

LOGS="$REPO/build/emu-checks/1"
rm -rf "$LOGS"
mkdir -p "$LOGS"
BRANCHES=(w1-1.1 w1-1.2 w1-1.3 w1-1.4)
echo "wave-1 integration check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')); logs in ${LOGS#"$REPO"/}"

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

# The reports of :core's last test run (check.sh runs :core under tr-TR, then under fa-IR).
package_tests() {
    python3 - "$REPO/core/build/test-results/testDebugUnitTest" <<'EOF'
import os, sys, xml.etree.ElementTree as ET
results = sys.argv[1]
prefix = "io.github.dilshodbekmx.passwordvault.core."
moved = ["search.SearchKeyTest", "text.OfficialPairsTest", "text.LatinToCyrillicTest", "search.FindsEntryTest",
         "text.CyrillicToLatinTest", "search.SearchIndexTest", "search.WebAddressTest", "search.MatchedRangesTest",
         "tools.UzbekCyrillicStringsTest"]
packages = {
    "1.1": ["ContractsTest", "util.BytesTest", "util.B64Test", "util.UuidTest"],
    "1.2": moved + ["search.AccentFreeTierTest", "search.KeyboardLayoutTest", "search.LayoutRetryTest",
                    "search.SearchRobustnessTest"],
    "1.3": ["crypto.MasterPasswordBytesTest", "lock.guard.CreditTest", "lock.guard.PracticeTest",
            "lock.pin.PinBlocklistTest", "lock.policy.SessionPolicyTest"],
    "1.4": ["util.StrictJsonTest", "util.TextHygieneTest", "formats.primitives.CsvTest", "formats.primitives.IsoTimeTest",
            "formats.primitives.ReviewFindingsTest", "formats.primitives.TextDecodingTest", "formats.primitives.XlsxTest"],
}
programs = ["tools.SearchBenchmark", "tools.ConverterEval", "tools.UzbekCyrillicStrings"]

def report(name):
    path = os.path.join(results, f"TEST-{prefix}{name}.xml")
    if not os.path.isfile(path):
        return None
    r = ET.parse(path).getroot()
    return {k: int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")}

bad = 0
counts = {}
for package, classes in packages.items():
    ran = 0
    wrong = 0
    for name in classes:
        r = report(name)
        if r is None:
            print(f"   {package}: no report of {name}")
            wrong = 1
            continue
        counts[name] = r["tests"] - r["skipped"]
        if r["failures"] or r["errors"] or counts[name] == 0:
            print(f"   {package}: {name}: {r}")
            wrong = 1
        ran += counts[name]
    print(f"   {package}: {len(classes)} classes, {ran} tests ran" + ("" if wrong else ", none failed"))
    bad |= wrong
for name in programs:
    r = report(name)
    if r is None or r["failures"] or r["errors"]:
        print(f"   1.2: {name}: {r if r else 'no report'}")
        bad = 1
total_moved = sum(counts.get(name, 0) for name in moved)
print(f"   1.2: the nine moved classes ran {total_moved} tests (build plan: 527)")
if total_moved < 527:
    bad = 1
sys.exit(bad)
EOF
}

echo
echo "== jvm: packages 1.1 to 1.4"
pv_step "the four branches are merged" -- branches_merged
pv_step "tools/check.sh all" -- full_check
pv_step "every test class of the four packages ran and passed" -- package_tests
echo
PV_SERIAL="" pv_summary
