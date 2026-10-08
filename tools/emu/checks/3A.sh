#!/usr/bin/env bash
# tools/emu/checks/3A.sh - the skeleton check, the integration check of sub-wave 3A (build-plan.md
# section 5, "The skeleton check"): the six package branches merged; tools/check.sh all; then steps 1
# to 9 on API 24, 28 and 37 with the debug build and on API 24 and 37 with the staging build.
#
#   tools/emu/checks/3A.sh [jvm] [device] [--shots DIR] [-- GRADLE-ARGS...]
#   tools/emu/checks/3A.sh skeleton VARIANT SERIAL [--apk FILE] [--shots DIR]
#
#   jvm       - w3A-3.1 to w3A-3.6 are merged into HEAD (each branch is an ancestor of it);
#             - tools/check.sh all;
#             - :app's tests again for their reports (check.sh's offline part reruns :app's test
#               task filtered to one class, which replaces the reports of the unfiltered run), and
#               every JVM test class of packages 3.1 to 3.6 passed with at least the tests each
#               package reported.
#   device    the integrator's devices, booted together (boot.sh --integrator: 5584 is API 24, 5582
#             API 28, 5580 API 37), with this worktree's debug and staging APKs:
#             - adb.sh --connected on the three: every connected test, and every serial has a
#               passing report (rule 6: the integration checks run every connected test on all
#               three API levels);
#             - every device-test class of packages 2.5, 2.7, 2.8, 2.9 and 3.3 to 3.6 ran on each
#               device with no failure and at least as many tests as on its branch;
#             - the skeleton steps (below) with the debug build on API 24, 28 and 37 (the three at
#               once), then with the staging build on API 24 and 37 (the two at once); the
#               screenshots of steps 1, 2 and 4 on API 37 with the debug build go to --shots DIR
#               (default /home/asus/pvwork/skeleton-shots);
#             - beyond the plan's list, with the debug build on each of the three devices (at
#               once): the [script] lines of packages 3.2 and 3.3, which need the merged app
#               (session/autolock.sh; setup/create.sh --vault-file), and package 2.5's
#               shell/walk.sh over the real screens;
#             - the devices are stopped.
#   skeleton  the steps on one device of this worktree, with the VARIANT build (default APK
#             app/build/outputs/apk/VARIANT/app-VARIANT.apk); --shots DIR saves the screenshots of
#             steps 1, 2 and 4 (debug builds only: staging's secure window captures black).
#
# The steps, each found by the screens' resource ids (tools/emu/app.sh), so the language does not
# matter. Since the merge of round R3 the lock is DefaultLockManager: after setup and after each
# master-password unlock its "Choose a PIN" opens the PIN steps over the app, and "Make a backup
# now?" may follow; app.sh answers them "Not now" and "Later" (pva_settle), so the PIN stays off:
#   1. Install. Welcome, Start, the suggested words, "Use these words", "Write it down on paper",
#      Next, the words typed again, Next: the list is shown, and it is empty.
#   2. Add an account with a name, a login, a password, a website and a note: its screen, then Back:
#      it is in the list.
#   3. (run-as: SKIPPED on staging) files/vaults/live holds vault-0000000001.pvault and a second
#      generation; the newest, copied out with run-as cat, starts with PVAULT01 and holds none of
#      the typed texts (as UTF-8 or UTF-16LE).
#   4. Screen off for 3 s: locked. A wrong master password gives the message (pv.lock.line.0) and
#      the lock stays; the right one opens the list with the account.
#   5. am force-stop and start again: locked; the master password opens the list with the account.
#   6. Away for 31 s: locked. On the lock screen half the master password is typed, the screen is
#      turned to landscape and back: the lock route stays and the keyboard comes back each time; the
#      other half is typed and opens the app, so nothing typed was lost. On a new account's form a
#      name is typed and the screen turned the same way: the form stays, nothing locks, the name is
#      kept and the keyboard comes back; then Back and Discard.
#   7. U §12 check 4: a new form, "Create a strong password", "Use this password"; the screen off
#      for 3 s: locked; the master password: the form is shown again with the generated password and
#      "Your unsaved changes were kept" (pv.edit.message); then Back and Discard.
#   8. An account titled "Почта" is added with the debug command `entry add` (adb cannot type
#      Cyrillic); in English it is listed after the step-2 account. The debug command `locale ru`:
#      the app keeps its process and the list, and "Почта" comes first (Russian puts Cyrillic before
#      Latin); `locale system`: the same process and list, and the English order again.
#   9. smoke.sh VARIANT SERIAL: set up, add, rotate, kill in the background and come back to the same
#      screen, lock, unlock.
# and the app did not crash.
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/3A/. Exit 0 when no
# step failed, 1 otherwise, 2 for a usage error, 3 for a refused serial. Written by the integrator of
# sub-wave 3A; later integrators may run it again while the six branches exist.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

SHOTS=/home/asus/pvwork/skeleton-shots

# ---- the typed texts of step 2 ---------------------------------------------------------------
NAME=Skeletonbank
LOGIN=aziz.karimov.3a@example.uz
PASSWORD_TYPED=Zr8Kq2Vx6Pn4Tw9
WEBSITE=https://bank.skeleton.example/login
NOTE="Branch behind the bazaar"
CYRILLIC=Почта
KEPT=$(sed -n 's:.*<string name="edit_kept">\(.*\)</string>.*:\1:p' "$REPO/app/src/main/res/values/strings_edit.xml")
SHOW_PASSWORD=$(sed -n 's:.*<string name="common_show_password">\(.*\)</string>.*:\1:p' "$REPO/app/src/main/res/values/strings_common.xml")
HIDE_PASSWORD=$(sed -n 's:.*<string name="common_hide_password">\(.*\)</string>.*:\1:p' "$REPO/app/src/main/res/values/strings_common.xml")

# ---- one device: the skeleton steps ---------------------------------------------------------
step1() {
    pva_install "$APK" && pva_start && pva_set_up || return 1
    pva_need pv.list.empty 10 && [ -z "$(pva_row_titles)" ] || { pv_log "the list is not empty: $(pva_row_titles | tr '\n' ';')"; return 1; }
    shot step1-empty-list
}

step2() {
    pva_add_account "$NAME" "$LOGIN" "$PASSWORD_TYPED" "$WEBSITE" "$NOTE" || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords && pva_row_with "$NAME" 10 || return 1
    pv_ui_nodes | awk -F'|' -v l="$LOGIN" '$1 == l' | grep -q . || { pv_log "the login is not shown in the list"; return 1; }
    shot step2-account-in-list
}

step3() {
    local live newest file="$LOGS/$PV_SERIAL-$VARIANT-newest.pvault" t enc
    live=$(pv_shell run-as "$PV_PACKAGE" ls files/vaults/live 2>&1 | tr -d '\r')
    pv_log "files/vaults/live: $(echo $live)"
    printf '%s\n' "$live" | grep -qx 'vault-0000000001\.pvault' || { pv_log "no vault-0000000001.pvault"; return 1; }
    [ "$(printf '%s\n' "$live" | grep -cE '^vault-[0-9]{10}\.pvault$')" -ge 2 ] || { pv_log "no second generation"; return 1; }
    newest=$(printf '%s\n' "$live" | grep -E '^vault-[0-9]{10}\.pvault$' | sort | tail -n 1)
    pv_adb exec-out run-as "$PV_PACKAGE" cat "files/vaults/live/$newest" > "$file" 2>/dev/null
    [ -s "$file" ] || { pv_log "could not copy $newest out"; return 1; }
    [ "$(head -c 8 "$file")" = PVAULT01 ] || { pv_log "$newest starts with $(head -c 8 "$file" | xxd -p)"; return 1; }
    for t in "$NAME" "$LOGIN" "$PASSWORD_TYPED" "$WEBSITE" "$NOTE" karimov skeleton bazaar; do
        for enc in UTF-8 UTF-16LE; do
            if python3 -c 'import sys; sys.exit(0 if sys.argv[1].encode(sys.argv[2].lower().replace("-", "_")) in open(sys.argv[3], "rb").read() else 1)' "$t" "$enc" "$file"; then
                pv_log "'$t' ($enc) is in $newest"
                return 1
            fi
        done
    done
    pv_log "$newest: $(stat -c %s "$file") bytes, starts with PVAULT01, none of the typed texts"
}

step4() {
    pva_screen_off 3 && pva_on Locked 20 && pva_need pv.lock.password || return 1
    pva_type pv.lock.password Notthemasterpassword1 || return 1
    pva_submit pv.lock.unlock pv.lock.line.0 30 || return 1
    pv_log "the message: $(pva_text pv.lock.line.0)"
    pva_on Locked 5 || return 1
    shot step4-wrong-master-password
    pva_unlock Passwords && pva_row_with "$NAME" 10 || return 1
    shot step4-unlocked
}

step5() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    [ -z "$(pva_pid)" ] || { pv_log "the process is still there after force-stop"; return 1; }
    pva_relaunch && pva_unlock Passwords && pva_row_with "$NAME" 10
}

step6_away() { pva_away 31 && pva_on Locked 20; }

# focused FIELD: the field has the focus and the keyboard is shown, before a rotation. A dump may have
# moved the focus on (pv_ui_nodes on API 28, adb.sh), so the field is tapped again when the keyboard is
# not shown.
focused() {
    pva_keyboard_shown && return 0
    pva_tap "$1" && pva_wait_keyboard 10
}

# The lock screen: half typed, landscape and portrait, the other half typed: it opens.
step6_lock_screen() {
    local half=${PVA_PASSWORD:0:$((${#PVA_PASSWORD} / 2))} rest=${PVA_PASSWORD:$((${#PVA_PASSWORD} / 2))} o
    pva_need pv.lock.password && pva_type pv.lock.password "$half" || return 1
    for o in 1 0; do
        focused pv.lock.password || return 1
        pva_rotate "$o"
        pva_wait_keyboard 15 || { pv_log "no keyboard after turning to $o"; return 1; }
        pva_on Locked 10 || return 1
    done
    pva_type pv.lock.password "$rest" && pva_submit_open pv.lock.unlock Passwords 90 || { pv_log "the two halves did not open it"; return 1; }
}

# The edit form: a name typed, landscape and portrait: the form, the name and the keyboard stay.
step6_edit_form() {
    local o name
    pva_tap pv.list.add && pva_on Edit && pva_type pv.edit.name Rotationcheck || return 1
    for o in 1 0; do
        focused pv.edit.name || return 1
        pva_rotate "$o"
        pva_wait_keyboard 15 || { pv_log "no keyboard after turning to $o"; return 1; }
        pva_on Edit 10 && pva_absent pv.route.Locked || return 1
        name=$(pva_text pv.edit.name) || return 1
        [ "$name" = Rotationcheck ] || { pv_log "the name is '$name' after turning to $o"; return 1; }
    done
    discard_form
}

discard_form() {
    pva_hide_keyboard
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_need pv.edit.dialog.discard 10 && pva_tap pv.edit.dialog.discard && pva_on Passwords
}

# form_password: the form's password as "Show password" shows it; then it is hidden again. Drawn as
# dots, a VaultSecureField reports one mask character per character to uiautomator, never its text
# (architecture.md §8.4); shown, its text is on the screen and readable.
form_password() {
    local masked text
    masked=$(pva_text pv.edit.password) || return 1
    pva_hide_keyboard
    pv_ui_tap "[|]$(pva_re "$SHOW_PASSWORD")[|]" >/dev/null || { pv_log "no button '$SHOW_PASSWORD'"; return 1; }
    pv_sleep 1
    text=$(pva_text pv.edit.password) || return 1
    pv_ui_tap "[|]$(pva_re "$HIDE_PASSWORD")[|]" >/dev/null || { pv_log "no button '$HIDE_PASSWORD'"; return 1; }
    pv_sleep 1
    [ "$masked" = "$(printf '%*s' ${#text} '' | sed 's/ /•/g')" ] \
        || { pv_log "pv.edit.password drawn as dots does not report ${#text} mask characters, one for each character"; return 1; }
    printf '%s\n' "$text"
}

step7() {
    local generated shown message deadline
    pva_tap pv.list.add && pva_on Edit && pva_tap pv.edit.generateButton && pva_need pv.gen.preview || return 1
    generated=$(pva_text pv.gen.preview) || return 1
    # "Use this password" is below the fold of the sheet on a 1920-pixel screen: pva_tap scrolls to it.
    pva_tap pv.gen.use && pva_need pv.edit.strength || return 1
    shown=$(form_password) || return 1
    [ -n "$generated" ] && [ "$shown" = "$generated" ] || { pv_log "the form's password is not the generated one"; return 1; }
    pva_screen_off 3 && pva_unlock Edit || return 1
    # The message is a snackbar that goes after a few seconds: read it from the first dump that has it.
    deadline=$((SECONDS + 15))
    message=""
    while [ -z "$message" ] && [ $SECONDS -lt $deadline ]; do
        message=$(pv_ui_nodes | awk -F'|' '$3 == "pv.edit.message" { f = 1 } f && $1 != "" { print $1; exit }')
    done
    [ -n "$message" ] || { pv_log "no message pv.edit.message after the unlock"; return 1; }
    pv_log "the message: $message"
    # The device's language is English here (step 8 changes it later): strings_edit.xml's edit_kept.
    [ "$message" = "$KEPT" ] || { pv_log "the message is not '$KEPT'"; return 1; }
    shown=$(form_password) || return 1
    [ "$shown" = "$generated" ] || { pv_log "the reopened form does not hold the generated password"; return 1; }
    discard_form
}

step8() {
    local answer pid before after rows
    answer=$(pva_debug entry add "$CYRILLIC") || return 1
    pv_log "entry add: $answer"
    pva_row_with "$CYRILLIC" 10 || return 1
    order_is "$NAME" "$CYRILLIC" || return 1
    before=$(pva_pid)
    answer=$(pva_debug locale ru) || return 1
    pv_log "$answer"
    case "$answer" in *"shown: RU"*) ;; *) pv_log "the app's texts are not Russian"; return 1 ;; esac
    pv_sleep 3
    pva_on Passwords 20 && pva_absent pv.route.Locked || return 1
    wait_order "$CYRILLIC" "$NAME" || return 1
    answer=$(pva_debug locale system) || return 1
    pv_log "$answer"
    case "$answer" in *"shown: EN"*) ;; *) pv_log "the app's texts are not English again"; return 1 ;; esac
    pv_sleep 3
    pva_on Passwords 20 && pva_absent pv.route.Locked || return 1
    wait_order "$NAME" "$CYRILLIC" || return 1
    after=$(pva_pid)
    [ -n "$before" ] && [ "$before" = "$after" ] || { pv_log "the process changed: $before -> $after"; return 1; }
    pv_log "the same process $before throughout"
}

# order_is FIRST SECOND: the list shows FIRST above SECOND.
order_is() {
    local rows
    rows=$(pva_row_titles)
    pv_log "rows: $(printf '%s' "$rows" | tr '\n' ';')"
    printf '%s\n' "$rows" | awk -v a="$1" -v b="$2" '$0 == a { ia = NR } $0 == b { ib = NR } END { exit !(ia && ib && ia < ib) }'
}

wait_order() {
    local deadline=$((SECONDS + 15))
    until order_is "$1" "$2"; do
        [ $SECONDS -lt $deadline ] || { pv_log "'$1' is not above '$2'"; return 1; }
        pv_sleep 1
    done
}

step9() {
    "$EMU/smoke.sh" "$VARIANT" "$PV_SERIAL" --apk "$APK" > "$LOGS/smoke-$PV_SERIAL-$VARIANT.log" 2>&1
    local rc=$?
    grep -E '^(FAIL|SKIPPED|SUMMARY)' "$LOGS/smoke-$PV_SERIAL-$VARIANT.log" | sed 's/^/   /'
    return $rc
}

shot() {
    [ -n "$SHOT_DIR" ] || return 0
    pva_screenshot "$SHOT_DIR/$1.png" && pv_log "screenshot $SHOT_DIR/$1.png"
}

run_skeleton() {
    pv_use "$1"
    pva_init
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
    echo "skeleton steps, $VARIANT on $PV_SERIAL (API $(pv_api)): $APK"
    pv_step "1. install; Welcome, Start, the words, write down, type again: the list, empty" -- step1 || { pv_summary; return 1; }
    pv_step "2. an account with a name, a login, a password, a website and a note is in the list" -- step2 || { pv_summary; return 1; }
    pv_step "3. files/vaults/live: two generations; the newest starts with PVAULT01 and holds no typed text" needs-debuggable -- step3
    pv_step "4. screen off: locked; a wrong master password gives the message; the right one opens the list" -- step4
    pv_step "5. am force-stop and start: locked; unlock; the account is there" -- step5
    pv_step "6. away for 31 s: locked" -- step6_away
    pv_step "6. rotations on the lock screen: nothing lost, the keyboard comes back, the halves open it" -- step6_lock_screen
    pv_step "6. rotations on the edit form: nothing lost, nothing locks, the keyboard comes back" -- step6_edit_form
    pv_step "7. U 12 check 4: a generated password in a new form survives screen off as the kept draft" -- step7
    pv_step "8. locale ru and back: the same process and screen, and the list re-sorts" -- step8
    pva_rotate 0
    pv_step "the app did not crash in steps 1 to 8" -- pva_no_crash
    pv_step "9. smoke.sh $VARIANT: set up, add, rotate, kill and come back, lock, unlock" -- step9
    pv_summary
}

# ---- the whole check --------------------------------------------------------------------------
BRANCHES=(w3A-3.1 w3A-3.2 w3A-3.3 w3A-3.4 w3A-3.5 w3A-3.6)
APIS=(24 28 37)
declare -A SER=()

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

app_reports() {
    "$REPO/tools/gradle.sh" :app:testDebugUnitTest ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/app-tests.log" 2>&1
    local rc=$?
    grep -E '^BUILD|actionable tasks' "$LOGS/app-tests.log" | sed 's/^/   /'
    return $rc
}

package_tests() {
    python3 - "$REPO/app/build/test-results/testDebugUnitTest" <<'EOF'
import os, sys, xml.etree.ElementTree as ET
results = sys.argv[1]
prefix = "io.github.dilshodbekmx.passwordvault."

def report(name):
    path = os.path.join(results, f"TEST-{prefix}{name}.xml")
    if not os.path.isfile(path):
        return None
    r = ET.parse(path).getroot()
    return {k: int(r.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")}

# Each package's JVM test classes and the number of tests its agent reported (3.1's repository
# tests run once on FakeFiles and once on a temporary directory).
packages = {
    "3.1": (["data.BackupAndRestoreTest", "data.ConcurrencyTest", "data.CreateAndOpenTest", "data.DraftTest",
             "data.EditTest", "data.CreatedTimeTest", "data.ListModelProviderTest", "data.LockTest",
             "data.OlderCopyTest", "data.PurgeTest", "data.VaultsTest"], 124),
    "3.2": (["lock.gate.MasterPasswordGateTest", "session.AutoLockTest", "session.LockOrderTest"], 66),
    "3.3": (["ui.setup.SetupViewModelTest"], 25),
    "3.4": (["ui.lock.LockViewModelTest"], 25),
    "3.5": (["ui.list.PasswordsViewModelTest", "ui.list.ListBannersTest", "ui.list.RecentlyDeletedViewModelTest",
             "ui.list.EntryRowTest"], 39),
    "3.6": (["ui.edit.EditViewModelTest", "ui.edit.GeneratorModelTest", "ui.edit.FieldInputTest",
             "ui.edit.NewAccountNameTest"], 50),
}
bad = 0
for package, (classes, at_least) in packages.items():
    ran = 0
    wrong = 0
    for name in classes:
        r = report(name)
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

build_apks() {
    "$REPO/tools/gradle.sh" :app:assembleDebug :app:assembleStaging ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS/assemble.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS/assemble.log" | sed 's/^/   /'
    return $rc
}

boot_devices() {
    local api rc=0 status
    declare -A pids=()
    for api in "${APIS[@]}"; do
        "$EMU/boot.sh" --integrator "$api" > "$LOGS/serial-$api" 2> "$LOGS/boot-$api.log" &
        pids[$api]=$!
    done
    for api in "${APIS[@]}"; do
        wait "${pids[$api]}"
        status=$?
        SER[$api]=$(cat "$LOGS/serial-$api" 2> /dev/null)
        echo "   API $api: ${SER[$api]:-no device} (boot.sh exit $status)"
        [ $status -eq 0 ] && [ "${SER[$api]}" = "emulator-$(pv_integrator_port "$api")" ] || rc=1
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
# and 37 (@SdkSuppress leaves some out on the older ones).
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
    "ui.setup.TypeAgainRotationTest": ("3.3", 1, 1, 1),
    "ui.lock.LockPseudoLocaleTest": ("3.4", 2, 2, 2),
    "ui.lock.MasterPasswordRotationTest": ("3.4", 1, 1, 1),
    "ui.lock.MasterPasswordScreenTest": ("3.4", 6, 6, 6),
    "ui.list.ListPerformanceTest": ("3.5", 1, 1, 2),
    "ui.list.ListPseudoLocaleTest": ("3.5", 5, 5, 5),
    "ui.list.PasswordsScreenTest": ("3.5", 9, 9, 9),
    "ui.edit.EditKeyboardRotationTest": ("3.6", 3, 4, 4),
    "ui.edit.EditScreenTest": ("3.6", 4, 4, 4),
    "ui.list.ListFocusTest": ("w3B-carry", 2, 2, 2),
    "data.StoredTextsTest": ("w3B-carry", 1, 1, 1),
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
    failed = sum(v[1] for (dev, _), v in seen.items() if dev == serial)
    if failed:
        bad = 1
    others = sorted(cls for (dev, cls) in seen if dev == serial and cls not in expected)
    print(f"   API {api} ({serial}): {total} tests, {failed} failed; " + ", ".join(f"{p} {r}/{w}" for p, (r, w) in sorted(by_package.items()))
          + (f"; other classes: {', '.join(others)}" if others else ""))
sys.exit(bad)
EOF
}

# skeleton_on VARIANT API...: the skeleton steps on those devices at once, each in its own process.
skeleton_on() {
    local variant=$1 api rc=0 status
    shift
    declare -A pids=()
    for api in "$@"; do
        local shots=()
        [ "$variant" = debug ] && [ "$api" = 37 ] && shots=(--shots "$SHOTS")
        "${BASH_SOURCE[0]}" skeleton "$variant" "${SER[$api]}" ${shots[@]+"${shots[@]}"} > "$LOGS/skeleton-$variant-$api.log" 2>&1 &
        pids[$api]=$!
    done
    for api in "$@"; do
        wait "${pids[$api]}"
        status=$?
        echo "   $variant on API $api:"
        grep -E '^(PASS|FAIL|SKIPPED|SUMMARY|   )' "$LOGS/skeleton-$variant-$api.log" | sed 's/^/     /'
        [ $status -eq 0 ] || rc=1
    done
    return $rc
}

# extras_on API...: the packages' own scripts with the debug build on those devices at once.
extras_on() {
    local api rc=0 status
    declare -A pids=()
    for api in "$@"; do
        (
            bad=0
            "$EMU/session/autolock.sh" "${SER[$api]}" > "$LOGS/autolock-$api.log" 2>&1 || bad=1
            "$EMU/setup/create.sh" "${SER[$api]}" --vault-file > "$LOGS/create-$api.log" 2>&1 || bad=1
            "$EMU/shell/walk.sh" "${SER[$api]}" > "$LOGS/walk-$api.log" 2>&1 || bad=1
            exit $bad
        ) &
        pids[$api]=$!
    done
    for api in "$@"; do
        wait "${pids[$api]}"
        status=$?
        for script in autolock create walk; do
            echo "   API $api, $script.sh: $(grep -E '^SUMMARY' "$LOGS/$script-$api.log" | tail -n 1)"
            grep -E '^(FAIL|SKIPPED)' "$LOGS/$script-$api.log" | sed 's/^/     /'
        done
        [ $status -eq 0 ] || rc=1
    done
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

# ---- arguments --------------------------------------------------------------------------------
LOGS="$REPO/build/emu-checks/3A"
if [ "${1:-}" = skeleton ]; then
    [ $# -ge 3 ] || pv_usage "${BASH_SOURCE[0]}"
    VARIANT=$2
    case "$VARIANT" in debug | staging) ;; *) pv_usage "${BASH_SOURCE[0]}" ;; esac
    serial=$3
    shift 3
    APK=$REPO/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
    SHOT_DIR=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
            --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOT_DIR=$2; shift ;;
            *) pv_usage "${BASH_SOURCE[0]}" ;;
        esac
        shift
    done
    [ "$VARIANT" = debug ] || SHOT_DIR=""
    mkdir -p "$LOGS"
    run_skeleton "$serial"
    exit $?
fi

parts=()
gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        jvm | device) parts+=("$1") ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=(jvm device)
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }

rm -rf "$LOGS"
mkdir -p "$LOGS"
echo "sub-wave 3A integration check (the skeleton check) in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')); logs in ${LOGS#"$REPO"/}"

if wants jvm; then
    echo
    echo "== jvm: packages 3.1 to 3.6"
    pv_step "the six branches are merged" -- branches_merged
    pv_step "tools/check.sh all" -- full_check
    pv_step ":app's tests again, for their reports" -- app_reports
    pv_step "every JVM test class of 3.1 to 3.6 ran and passed" -- package_tests
fi
if wants device; then
    echo
    echo "== device: the integrator's API 24, 28 and 37 devices"
    if pv_step "build the debug and staging APKs" -- build_apks && pv_step "boot.sh --integrator 24, 28 and 37" -- boot_devices; then
        pv_step "connectedDebugAndroidTest on API 24, 28 and 37 (adb.sh --connected)" -- connected_tests
        pv_step "every device-test class of 2.5, 2.7 to 2.9 and 3.3 to 3.6 passed on each device" -- device_classes
        rm -f "$SHOTS"/step*.png
        pv_step "the skeleton steps 1 to 9, debug build, on API 24, 28 and 37" -- skeleton_on debug 24 28 37
        pv_step "the skeleton steps 1 to 9, staging build, on API 24 and 37" -- skeleton_on staging 24 37
        if ls "$SHOTS"/step*.png > /dev/null 2>&1; then
            echo "   screenshots (API 37, debug) in $SHOTS: $(cd "$SHOTS" && ls step*.png | tr '\n' ' ')"
        fi
        pv_step "extra: autolock.sh (3.2), create.sh --vault-file (3.3) and walk.sh (2.5), debug build, on API 24, 28 and 37" -- extras_on 24 28 37
    fi
    pv_step "the devices are stopped" -- stop_devices
fi
echo
PV_SERIAL="" pv_summary
