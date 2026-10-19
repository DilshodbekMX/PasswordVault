#!/usr/bin/env bash
# tools/emu/layout/walk.sh - build plan 6.2, [script]: every screen of the app walked in the
# pseudo-locales en-XA and ar-XB on a phone set to 320 dp wide and 200 % font size, with four checks
# per screen: no clipped button, no truncated text (a warning or any other), no text left in plain
# English, no control under 48 dp.
#
#   tools/emu/layout/walk.sh SERIAL [--apk FILE] [--test-apk FILE] [--locales TAG,TAG] [--parts PART,PART]
#                            [--shots DIR] [--width DP] [--font SCALE]
#
# SERIAL comes from tools/emu/boot.sh; the APKs default to this worktree's debug build and its test APK
# (tools/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest); a debug build is needed (the
# pseudo-locales, screenshots, run-as). --locales defaults to en-XA,ar-XB; --width to 320 and --font to
# 2.0. --parts: app (the real app, LayoutWalkTest, once per locale) and states (the states the real app
# reaches only with time or a damaged file: each area's pseudo-locale device test draws them with fakes
# in en-XA and ar-XB and calls LayoutHook at each; run once); default both.
#
# The script sets the screen's density so that the phone is DP wide (`wm density`, the setting
# "Display size") and the font scale (`settings put system font_scale`), and puts both back as they were
# when it ends, however it ends. For each locale it installs both APKs afresh, clears the app's data
# and runs the walk with `am instrument` (`-e pvLayout <run> -e pvLayoutLocale <tag>`): the walk sets
# the app's language itself and looks at each screen in the app's own process with LayoutProbe, which
# reads what Compose laid out: a text cut with an ellipsis or overflowing its box (except one marked
# mayTruncate, whose full text is reachable elsewhere), clipped by a parent that does not scroll,
# spilling out of its parent, past the edge of a window, a whole word of up to 16 characters split
# across two lines, a text covered by the floating Add button where no scroll moves it out; a control
# whose touch area is under 48 dp; a text or label without a pseudo-localized character (except the
# user's own texts, marked userText, and the app's untranslatable strings). Each screen is looked at as
# it opens and after each stretch of every list or column that scrolls. It prints PASS or FAIL per
# screen and check, FAIL for a step of the walk that could not be done and for a device test of the part
# states that failed; the pictures of the screens go to DIR (default build/emu-layout/SERIAL/<run>/),
# the reports and logs to build/emu-layout/SERIAL/; tools/emu/layout/screens.py makes docs/screens/
# from them. About 15 minutes on API 37.
#
# Exit 0 when every check of every screen passed, 1 otherwise, 2 for a usage error, 3 for a refused
# serial.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
LOCALES=en-XA,ar-XB
PARTS=app,states
SHOTS=""
WIDTH_DP=320
FONT=2.0
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --test-apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; TEST_APK=$2; shift ;;
        --locales) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LOCALES=$2; shift ;;
        --parts) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; PARTS=$2; shift ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --width) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; WIDTH_DP=$2; shift ;;
        --font) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; FONT=$2; shift ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
case "$WIDTH_DP" in '' | *[!0-9]*) pv_usage "${BASH_SOURCE[0]}" ;; esac
case "$FONT" in '' | *[!0-9.]*) pv_usage "${BASH_SOURCE[0]}" ;; esac

pv_use "$SERIAL"
pva_init
API=$(pv_api)
LOGS=$REPO/build/emu-layout/$SERIAL
SHOTS=${SHOTS:-$LOGS}
mkdir -p "$LOGS" "$SHOTS"
TEST_PACKAGE=$PV_PACKAGE.test
RUNNER=$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner
echo "layout walk on $SERIAL (API $API): ${WIDTH_DP} dp wide, font ${FONT}; locales $LOCALES; parts $PARTS"

# ---- the screen's size and the font, and putting them back -----------------------------------------
# What was set before: an override of the density ("Override density: N", none when only the physical
# one is printed) and the font scale (null when never set).
OLD_DENSITY=$(pv_shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Override density: *\([0-9]*\)$/\1/p')
OLD_FONT=$(pv_shell settings get system font_scale 2>/dev/null | tr -d '\r')
RESTORED=0
restore() {
    [ "$RESTORED" = 1 ] && return
    RESTORED=1
    if [ -n "$OLD_DENSITY" ]; then pv_shell wm density "$OLD_DENSITY" >/dev/null 2>&1; else pv_shell wm density reset >/dev/null 2>&1; fi
    case "$OLD_FONT" in
        '' | null) pv_shell settings delete system font_scale >/dev/null 2>&1 ;;
        *) pv_shell settings put system font_scale "$OLD_FONT" >/dev/null 2>&1 ;;
    esac
    pv_log "the density is ${OLD_DENSITY:-the physical one} again and the font scale ${OLD_FONT:-unset}"
}
trap restore EXIT
trap 'exit 130' INT TERM

set_size() {
    local physical width density
    physical=$(pv_shell wm density 2>/dev/null | tr -d '\r' | sed -n 's/^Physical density: *\([0-9]*\)$/\1/p')
    width=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *\([0-9]*\)x[0-9]*$/\1/p')
    [ -n "$physical" ] && [ -n "$width" ] || { pv_log "no physical size or density"; return 1; }
    # The width in dp is width * 160 / density: the density that makes it WIDTH_DP.
    density=$(( (width * 160 + WIDTH_DP / 2) / WIDTH_DP ))
    pv_shell wm density "$density" >/dev/null || return 1
    pv_shell settings put system font_scale "$FONT" >/dev/null || return 1
    pv_sleep 2
    pv_log "density $density (was ${OLD_DENSITY:-$physical}): $width px are $(( width * 160 / density )) dp; font scale $(pv_shell settings get system font_scale | tr -d '\r')"
}

# ---- one run ----------------------------------------------------------------------------------------
# The walk's report lines: RUN, SCREEN name|CHECK|PASS|FAIL[|findings], BROKEN step|problem, NOTE, END.
check_title() {
    case "$1" in
        BUTTONS) echo "no clipped button" ;;
        TEXTS) echo "no truncated or clipped text" ;;
        ENGLISH) echo "no text left in plain English" ;;
        TARGETS) echo "no control under 48 dp" ;;
        *) echo "$1" ;;
    esac
}

print_report() { # LOCALE REPORT-FILE
    local locale=$1 kind rest screen check result detail
    while IFS= read -r line; do
        kind=${line%% *}
        rest=${line#* }
        case "$kind" in
            SCREEN)
                IFS='|' read -r screen check result detail <<< "$rest"
                pv_report "$result" "$locale $screen: $(check_title "$check")" ${detail:+"$detail"}
                ;;
            BROKEN)
                pv_report FAIL "$locale walk step ${rest%%|*} could not be done" "${rest#*|}"
                ;;
            NOTE) echo "NOTE $locale: $rest" ;;
        esac
    done < "$2"
}

# The device tests whose states the part `states` looks at: each area's pseudo-locale test, which draws
# its screens with fakes in en-XA and ar-XB and calls LayoutHook at each state it keeps a picture of.
STATE_TESTS="ui.lock.LockPseudoLocaleTest ui.settings.master.MasterPseudoLocaleTest ui.entry.EntryPseudoLocaleTest \
ui.list.ListPseudoLocaleTest ui.security.SecurityPseudoLocaleTest ui.security.change.GuidedChangePseudoLocaleTest \
ui.transfer.backup.BackupPseudoLocaleTest ui.transfer.export.ExportPseudoLocaleTest ui.transfer.import.ImportPseudoLocaleTest"

# failed_tests LOG: "class#method: first line" for each test that failed in an `am instrument -r` output.
failed_tests() {
    python3 - "$1" <<'PY'
import sys
block, out, key = {}, [], None
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = raw.rstrip("\n")
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
        block[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = line.split(": ", 1)[1].strip()
        if code in ("-1", "-2") and "test" in block:
            stack = [l for l in block.get("stack", "").splitlines() if l.strip()]
            out.append(f"{block.get('class', '?').rsplit('.', 1)[-1]}#{block['test']}: {(stack[0] if stack else '').strip()[:300]}")
        block, key = {}, None
    elif key == "stack":
        block["stack"] += "\n" + line
for o in out:
    print(o)
PY
}

# run_part LOCALE CLASSES NAME KIND: installs, clears the app's data, runs CLASSES (comma-separated) as
# the run NAME, prints its report and takes its pictures. KIND app: the walk must have ended; KIND
# states: each test that failed is a failure too.
run_part() {
    local locale=$1 class=$2 name=$3 kind=$4 log=$LOGS/$3.log report=$LOGS/$3.txt
    pva_install "$APK" || { pv_report FAIL "$locale $name: install the app"; return 1; }
    pv_adb install -r -t "$TEST_APK" > "$LOGS/$name.install-test.log" 2>&1 \
        || { pv_report FAIL "$locale $name: install the test APK" "$(tail -n 1 "$LOGS/$name.install-test.log")"; return 1; }
    pv_shell pm clear "$PV_PACKAGE" >/dev/null 2>&1
    pv_shell rm -rf "/data/local/tmp/pv-layout/$name" >/dev/null 2>&1
    pv_unlock >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    pv_log "run $name: $class${locale:+ in $locale}"
    pv_adb shell am instrument -w -r -e class "$class" -e pvLayout "$name" ${locale:+-e pvLayoutLocale "$locale"} "$RUNNER" > "$log" 2>&1
    pv_adb exec-out run-as "$PV_PACKAGE" cat "cache/pv-layout/$name.txt" > "$report" 2>/dev/null
    if ! grep -q '^RUN ' "$report"; then
        pv_report FAIL "${locale:-states} $name: the walk ran" "no report; $(grep -m 3 -E 'INSTRUMENTATION_RESULT|shortMsg|Error' "$log" | tr '\n' ' ')"
        return 1
    fi
    if [ "$kind" = app ]; then
        grep -q '^END ' "$report" || pv_report FAIL "$locale $name: the walk ended" "it stopped after: $(tail -n 1 "$report")"
    fi
    print_report "${locale:-$name}" "$report"
    if [ "$kind" = states ]; then
        local failed
        failed=$(failed_tests "$log")
        if [ -n "$failed" ]; then
            while IFS= read -r test; do pv_report FAIL "$name: the device test $test"; done <<< "$failed"
        else
            grep -q '^OK (' "$log" && pv_report PASS "$name: the device tests that draw the states ($(grep -m 1 '^OK (' "$log"))" \
                || pv_report FAIL "$name: the device tests ended" "$(grep -m 2 -E 'INSTRUMENTATION_RESULT|Tests run' "$log" | tr '\n' ' ')"
        fi
    fi
    rm -rf "${SHOTS:?}/$name"
    mkdir -p "$SHOTS/$name"
    pv_adb pull "/data/local/tmp/pv-layout/$name/." "$SHOTS/$name/" > /dev/null 2>&1 \
        && pv_log "pictures in $SHOTS/$name/ ($(ls "$SHOTS/$name" | wc -l))"
    pva_no_crash || pv_report FAIL "${locale:-states} $name: no crash"
}

[ -f "$APK" ] || { pv_log "no APK at $APK (tools/gradle.sh :app:assembleDebug)"; exit 1; }
[ -f "$TEST_APK" ] || { pv_log "no test APK at $TEST_APK (tools/gradle.sh :app:assembleDebugAndroidTest)"; exit 1; }
pv_step "the phone is ${WIDTH_DP} dp wide with font scale $FONT" -- set_size || { pv_summary; exit 1; }
for part in ${PARTS//,/ }; do
    case "$part" in
        app)
            for locale in ${LOCALES//,/ }; do
                run_part "$locale" io.github.dilshodbekmx.passwordvault.ui.layout.LayoutWalkTest "app-$locale" app
            done ;;
        states)
            classes=""
            for test in $STATE_TESTS; do classes="$classes${classes:+,}io.github.dilshodbekmx.passwordvault.$test"; done
            run_part "" "$classes" states states ;;
        *) pv_log "unknown part $part"; exit 2 ;;
    esac
done
pv_shell pm uninstall "$TEST_PACKAGE" >/dev/null 2>&1
restore
pv_summary
