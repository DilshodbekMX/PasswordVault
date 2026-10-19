#!/usr/bin/env bash
# tools/emu/smoke.sh - the smoke test of one build on one private emulator.
#
#   tools/emu/smoke.sh VARIANT SERIAL [--apk FILE]
#       VARIANT is debug or staging (a release APK holds arm code only and cannot run on these
#       x86_64 emulators). SERIAL comes from tools/emu/boot.sh. The APK defaults to this
#       worktree's app/build/outputs/apk/VARIANT/app-VARIANT.apk: build it first with
#       tools/gradle.sh :app:assembleDebug or :app:assembleStaging.
#
# The stages grow with the waves (architecture.md 8.4); each wave adds a stage function below.
#   wave 0: install and start.
#   sub-wave 3A (the skeleton): set up with the suggested words; add an account, whose screen is
#     shown after Save; a rotation there neither locks nor changes the screen; the app killed in the
#     background (am kill; kill -9 through run-as on debug builds or su on the userdebug images where
#     am kill is refused, API 24; SKIPPED when nothing can end it) and started again: locked, and the
#     master password brings the same account's screen back (the saved back stack, under R8 in
#     staging); Back to the list, Lock now: locked; the master password: the list with the account.
#     Driven by resource id through tools/emu/app.sh. Since the merge of round R3 the lock's "Choose
#     a PIN" after setup and after each master-password unlock is answered "Not now", and "Make a
#     backup now?" "Later" (app.sh, pva_settle): the PIN and fingerprint stay off here.
#   sub-wave 3B (the account's screen and the clipboard): the account opened from the list, its
#     password drawn as dots and in no node; "Show password" shows it; Copy: the clip, read from
#     outside the app (clipboard/lib.sh), carries the sensitive flag and a 32-digit token and holds
#     the password, and the status row is shown; Home: the clip is still there 4 s before its time and
#     gone 4 s after it at the latest, with the delay setup wrote (1 minute; the time is the first
#     reading of the clip plus the delay, as clipboard/clear.sh estimates it without run-as).
#   sub-wave 4A (the PIN and the fingerprint): back in front the app is locked; the master password
#     opens it and "Choose a PIN" follows: a PIN, typed again; the fingerprint offer "Use", finger 1 on
#     the prompt; the steps close onto the account's screen, where the app was (ux.md 4, "What survives
#     a lock"); the lock's status (the debug command, also in staging) has both slots and their
#     Keystore keys; Lock now: the prompt opens by itself (on API 24 androidx.biometric's own dialog in
#     the activity, the path R8 broke before build check R-16) and finger 1 opens the list; Lock now,
#     "Use PIN" on the prompt: the pad, and the PIN opens the list with the account.
#   wave 5 (since the device check of R4; build-plan 7, line 8): through the system's picker into and
#     from the phone's Download folder (app.sh, pva_picker_*): a backup; the CSV export, which holds the
#     account; an import of a small CSV pushed with adb, whose two accounts reach the list; then from the
#     lock screen "Forgot master password?" > "Restore from a backup file" with that backup: the list is
#     the backup's again, without the imported accounts.
# Prints PASS, FAIL or SKIPPED per step and a summary line. Exit codes: 0 every step passed or
# was skipped; 1 a step failed; 2 usage; 3 SERIAL refused.
set -u
source "$(dirname "${BASH_SOURCE[0]}")/adb.sh"
source "$(dirname "${BASH_SOURCE[0]}")/app.sh"
source "$(dirname "${BASH_SOURCE[0]}")/clipboard/lib.sh"

usage() { pv_usage "$0"; }

VARIANT=${1:-}
SERIAL=${2:-}
APK=""
[ $# -ge 2 ] || usage
shift 2
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || usage; APK=$2; shift ;;
        *) usage ;;
    esac
    shift
done
case "$VARIANT" in
    debug|staging) ;;
    release) echo "smoke.sh: release APKs hold arm code only; use staging (R8, not debuggable) on the emulators" >&2; exit 2 ;;
    *) usage ;;
esac
REPO=$(cd "$PV_EMU_DIR/../.." && pwd)
[ -n "$APK" ] || APK=$REPO/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk
AAPT2=$(ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)

pv_use "$SERIAL"
# A wedged device must not hang the smoke test: every adb call gets at most 180 s.
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-180}
API=$(pv_api)
echo "smoke.sh $VARIANT on $SERIAL (API $API): $APK"

# ---- stage: install and start (wave 0) ---------------------------------------------------------
check_apk() {
    local badging
    [ -f "$APK" ] || { pv_log "no APK at $APK; build it with tools/gradle.sh :app:assemble${VARIANT^}"; return 1; }
    badging=$("$AAPT2" dump badging "$APK" 2>/dev/null) || { pv_log "aapt2 cannot read $APK"; return 1; }
    echo "$badging" | grep -q "^package: name='$PV_PACKAGE'" \
        || { pv_log "the APK's package is not $PV_PACKAGE"; return 1; }
    if echo "$badging" | grep -q '^application-debuggable'; then
        [ "$VARIANT" = debug ] || { pv_log "a $VARIANT APK must not be debuggable"; return 1; }
    else
        [ "$VARIANT" != debug ] || { pv_log "a debug APK must be debuggable"; return 1; }
    fi
    if echo "$badging" | grep -q '^native-code:'; then
        echo "$badging" | grep '^native-code:' | grep -q "'x86_64'" \
            || { pv_log "the APK has no x86_64 code: $(echo "$badging" | grep '^native-code:')"; return 1; }
    fi
}

remove_old_copy() {
    if pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'; then
        pv_adb uninstall "$PV_PACKAGE" >/dev/null || return 1
    fi
    ! pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'
}

# Without streaming (pv_adb); 90 s per try and up to three tries, should an install still not answer.
install_apk() {
    local out i
    for i in 1 2 3; do
        out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$APK" 2>&1)
        echo "$out" | grep -q '^Success' && return 0
        pv_log "adb install, try $i: ${out:-no answer within 90 s}"
        pv_sleep 5
    done
    return 1
}

installed_matches_variant() {
    if [ "$VARIANT" = debug ]; then pv_is_debuggable; else ! pv_is_debuggable; fi \
        || { pv_log "the installed app's debuggable flag does not match $VARIANT"; return 1; }
}

launcher_activity() {
    local a
    a=$(pv_shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PV_PACKAGE" 2>/dev/null \
        | tr -d '\r' | grep "^$PV_PACKAGE/" | tail -n 1)
    echo "${a:-$PV_PACKAGE/.MainActivity}"
}

app_pid() {
    local p
    p=$(pv_shell pidof "$PV_PACKAGE" 2>/dev/null | tr -d '\r')
    [ -n "$p" ] || p=$( { pv_shell ps -A 2>/dev/null || pv_shell ps; } | tr -d '\r' | awk -v p="$PV_PACKAGE" '$NF == p { print $2; exit }')
    echo "$p"
}

start_app() {
    local activity out
    pv_unlock >/dev/null 2>&1
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    activity=$(launcher_activity)
    out=$(pv_shell am start -W -n "$activity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start -W -n $activity: $out"; return 1; }
    pv_log "started $activity: $(echo "$out" | grep -E '^(TotalTime|WaitTime)' | tr '\n' ' ')"
}

app_in_front() {
    local i
    for i in $(seq 1 15); do
        case "$(pv_focus)" in *"$PV_PACKAGE"/*) return 0 ;; esac
        pv_sleep 1
    done
    pv_log "the app is not in front; focus is $(pv_focus)"
    return 1
}

app_still_running() {
    local crash pid
    pv_sleep 5
    pid=$(app_pid)
    [ -n "$pid" ] || { pv_log "the app's process is gone"; return 1; }
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
    case "$(pv_focus)" in *"$PV_PACKAGE"/*) ;; *) pv_log "the app left the front; focus is $(pv_focus)"; return 1 ;; esac
    pv_log "the app runs as pid $pid"
}

stage_install_and_start() {
    pv_step "apk is a $VARIANT build of $PV_PACKAGE" -- check_apk || return 1
    pv_step "remove an installed copy" -- remove_old_copy || return 1
    pv_step "install" -- install_apk || return 1
    pv_step "installed app is $VARIANT (debuggable flag)" -- installed_matches_variant
    pv_step "start the launcher activity" -- start_app || return 1
    pv_step "app is in front" -- app_in_front || return 1
    pv_step "app still running after 5 s, no crash" -- app_still_running
}

# ---- stage: the skeleton (sub-wave 3A) ---------------------------------------------------------
SMOKE_ENTRY=""

smoke_set_up() { pva_set_up && pva_need pv.list.empty 10; }

# An account; Save shows its screen, whose first line is the account's name (pv.entry.title, build plan 3.7).
smoke_add() {
    pva_add_account Smoketest smoke.user@example.uz Hv4Nc8Rt2Wq6Yz3 https://smoke.example/login "Smoke note" || return 1
    SMOKE_ENTRY=$(pva_text pv.entry.title) && [ -n "$SMOKE_ENTRY" ] || return 1
    pv_log "the account's screen: $SMOKE_ENTRY"
}

same_entry() {
    local title
    pva_on Entry 20 || return 1
    title=$(pva_text pv.entry.title) || return 1
    [ "$title" = "$SMOKE_ENTRY" ] || { pv_log "the screen shows '$title', not '$SMOKE_ENTRY'"; return 1; }
}

smoke_rotate() {
    local o
    for o in 1 0; do
        pva_rotate "$o"
        same_entry && pva_absent pv.route.Locked || return 1
    done
}

smoke_back_after_kill() { pva_relaunch && pva_unlock Entry && same_entry; }

smoke_lock_now() {
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords && pva_tap pv.list.lockNow && pva_on Locked
}

smoke_unlock() { pva_unlock Passwords && pva_row_with Smoketest 10; }

stage_skeleton() {
    local same="a new process: locked; the master password brings the same screen back"
    pva_init
    pva_rotate 0
    pv_step "set up with the suggested words: the list, empty" -- smoke_set_up || return 1
    pv_step "add an account: its screen after Save" -- smoke_add || return 1
    pv_step "a rotation keeps the account's screen and does not lock" -- smoke_rotate
    if pva_kill_in_background; then
        pv_report PASS "the app is killed in the background"
        pv_step "$same" -- smoke_back_after_kill
    else
        pv_report SKIPPED "the app is killed in the background" "nothing here could end the app's process on API $API"
        pv_report SKIPPED "$same" "the process was not killed"
        pv_step "back in front: the same screen" -- eval 'pva_relaunch && same_entry'
    fi
    pv_step "Back to the list, Lock now: locked" -- smoke_lock_now
    pv_step "the master password opens the list with the account" -- smoke_unlock
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: the account's screen and the clipboard (sub-wave 3B) --------------------------------
SMOKE_PASSWORD=Hv4Nc8Rt2Wq6Yz3
SMOKE_DELAY_MS=60000 # "Clear copied text after", as setup writes it (D-7)
SMOKE_TOKEN=""
SMOKE_DEADLINE=0

# One reading of the clipboard from outside the app: SMOKE_NOW (the device's clock, ms), SMOKE_HAS,
# SMOKE_CLIP (the app's token; empty when the clip is not the app's) and SMOKE_SENSITIVE.
smoke_clip() {
    local d
    d=$(pvc_describe) || { pv_log "the clipboard could not be read"; return 1; }
    SMOKE_NOW=$(printf '%s\n' "$d" | pvc_field now)
    SMOKE_HAS=$(printf '%s\n' "$d" | pvc_field has)
    SMOKE_CLIP=$(printf '%s\n' "$d" | pvc_field token)
    SMOKE_SENSITIVE=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ -n "$SMOKE_NOW" ]
}

smoke_open_account() {
    local row
    pva_on Passwords 20 || return 1
    row=$(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
    [ -n "$row" ] || { pv_log "no row in the list; ids: $(pva_ids)"; return 1; }
    pva_tap "$row" && same_entry || return 1
    pva_need pv.entry.password.value 10 || return 1
    ! pv_ui_nodes | grep -qF -- "$SMOKE_PASSWORD" || { pv_log "a node holds the password while it is hidden"; return 1; }
}

smoke_show_password() {
    local deadline=$((SECONDS + 10)) text=""
    pva_tap pv.entry.password.show || return 1
    until [ "$text" = "$SMOKE_PASSWORD" ]; do
        [ $SECONDS -lt $deadline ] || { pv_log "pv.entry.password.value reads '$text'"; return 1; }
        pv_sleep 0.5
        text=$(pva_text pv.entry.password.value)
    done
}

smoke_copy() {
    local i text
    pvc_clear || return 1
    pva_tap pv.entry.password.copy || return 1
    for i in $(seq 1 20); do
        smoke_clip || return 1
        [ -n "$SMOKE_CLIP" ] && break
        pv_sleep 0.2
    done
    [ -n "$SMOKE_CLIP" ] || { pv_log "no clip of the app's after Copy (has=$SMOKE_HAS)"; return 1; }
    printf '%s' "$SMOKE_CLIP" | grep -Eq '^[0-9a-f]{32}$' || { pv_log "the token is not 32 hex digits: '$SMOKE_CLIP'"; return 1; }
    [ "$SMOKE_SENSITIVE" = true ] || { pv_log "the sensitive flag is '${SMOKE_SENSITIVE:-absent}'"; return 1; }
    SMOKE_TOKEN=$SMOKE_CLIP
    SMOKE_DEADLINE=$((SMOKE_NOW + SMOKE_DELAY_MS))
    pva_tip_ok # the first copy of this install opens the first-copy tip over the screen (7.3)
    pva_need pv.clip.row 5 || return 1
    text=$(pvc_get | pvc_field text) || return 1
    [ "$text" = "$SMOKE_PASSWORD" ] || { pv_log "the clip does not hold the password (${#text} characters)"; return 1; }
    pv_log "the clip is flagged sensitive, has the token $SMOKE_TOKEN and holds the password; the status row is shown"
}

# smoke_until MS: returns when the device's clock reads MS.
smoke_until() {
    local left
    smoke_clip || return 1
    left=$(($1 - SMOKE_NOW))
    [ "$left" -le 0 ] || pv_sleep "$((left / 1000)).$(printf '%03d' $((left % 1000)))"
}

smoke_clear_in_background() {
    local text
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    smoke_until $((SMOKE_DEADLINE - 4000)) && smoke_clip || return 1
    [ "$SMOKE_CLIP" = "$SMOKE_TOKEN" ] || { pv_log "$((SMOKE_DEADLINE - SMOKE_NOW)) ms before its time the clip is gone already"; return 1; }
    while :; do
        smoke_clip || return 1
        [ "$SMOKE_CLIP" = "$SMOKE_TOKEN" ] || break
        [ "$SMOKE_NOW" -lt $((SMOKE_DEADLINE + 4000)) ] || { pv_log "the clip is still there $((SMOKE_NOW - SMOKE_DEADLINE)) ms after its time"; return 1; }
        pv_sleep 0.2
    done
    pv_log "the clip was gone $((SMOKE_NOW - SMOKE_DEADLINE + SMOKE_DELAY_MS)) ms after the copy was first seen"
    # Nothing is left: no clip from API 28 on; on Android 7, which cannot clear otherwise, an empty clip.
    if [ "$API" -ge 28 ]; then
        [ "$SMOKE_HAS" = false ] || { pv_log "the clipboard is not empty"; return 1; }
    else
        text=$(pvc_get | pvc_field text) || return 1
        [ -z "$SMOKE_CLIP" ] && [ -z "$text" ] || { pv_log "the clipboard is not empty"; return 1; }
    fi
}

stage_clipboard() {
    pv_step "the account from the list: its password drawn as dots, in no node" -- smoke_open_account || return 1
    pv_step "Show password shows it" -- smoke_show_password
    pv_step "Copy: the clip is flagged sensitive, has a token and holds the password; the status row is shown" -- smoke_copy || return 1
    pv_step "with the app in the background the clip is gone at its time (setup's 1-minute delay)" -- smoke_clear_in_background
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: the PIN and the fingerprint (sub-wave 4A) ---------------------------------------------
SMOKE_PIN=582913

# Back in front after the clipboard stage, the app is locked (setup's 30 s delay); the master password
# opens it, and with no PIN "Choose a PIN" opens the PIN steps (lock.md F1 step 6, F7).
smoke_unlock_to_pin_steps() {
    pva_relaunch && pva_on Locked 30 && pva_need pv.lock.password 20 || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && pva_to_create_pin
}

smoke_shortcuts_on() {
    pva_status_has '^pin.rec: OK, slot set' && pva_status_has '^fingerprint.rec: OK, slot set' \
        && pva_status_has '^keystore: .*pv_pin_' && pva_status_has '^keystore: .*pv_fp_'
}

smoke_finger_opens() {
    pva_lock_now && pva_prompt_open UNLOCK 20 || return 1
    if [ "$API" -lt 28 ]; then
        # Android 7: androidx.biometric draws its own dialog inside the activity (lock.md 9.1, E6).
        pv_ui_nodes | grep -q '^USE PIN|' || { pv_log "not the library's dialog: $(pv_ui_nodes | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-300)"; return 1; }
    fi
    pva_finger 1 && pva_opened 20 && pva_no_prompt && pva_settle Passwords 60
}

smoke_pin_opens() {
    pva_lock_now && pva_prompt_open UNLOCK 20 && pva_prompt_use_pin && pva_no_prompt && pva_need pv.lock.pad 20 || return 1
    pva_pin "$SMOKE_PIN" && pva_opened 30 && pva_settle Passwords 60 && pva_row_with Smoketest 10
}

stage_pin_and_fingerprint() {
    pv_step "back in front: locked; the master password opens it, and \"Choose a PIN\" follows" -- smoke_unlock_to_pin_steps || return 1
    pv_step "a PIN is chosen and typed again" -- pva_choose_pin "$SMOKE_PIN" || return 1
    pv_step "the fingerprint offer: Use, finger 1 on the prompt; then the account's screen again, where the app was" -- eval 'pva_after_pin use Entry && same_entry' || return 1
    pv_step "status: a PIN slot and a fingerprint slot with their Keystore keys" -- smoke_shortcuts_on
    pv_step "Back to the list" -- eval 'pv_shell input keyevent KEYCODE_BACK >/dev/null && pva_on Passwords 20' || return 1
    pv_step "Lock now: the prompt opens by itself$([ "$API" -lt 28 ] && echo " (the library's own dialog)"); finger 1 opens the list" -- smoke_finger_opens
    pv_step "Lock now: \"Use PIN\" on the prompt, the pad; the PIN opens the list with the account" -- smoke_pin_opens
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: backup, export, import and restore (wave 5) -------------------------------------------
# Through the system's picker (DocumentsUI; app.sh, pva_picker_*), into and from the phone's Download
# folder: a backup ("Backup saved", the file in Download), the CSV export (the warning's "I
# understand", the master password; the file holds the account), an import of a small CSV pushed with
# adb (the two accounts in the list; the file deleted), and a restore of the backup from the lock
# screen ("Forgot master password?" > "Restore from a backup file"): the list is the backup's again,
# without the two imported accounts, and the restore's PIN steps are walked past.
SMOKE_CSV=pv-smoke-import.csv
SMOKE_BACKUP="" # the backup's name in Download

smoke_settings() {
    local i
    for i in 1 2 3 4; do pva_present pv.tab.Settings 2 && break; pv_shell input keyevent KEYCODE_BACK >/dev/null; pv_sleep 1; done
    pva_tap pv.tab.Settings && pva_on Settings 20
}

smoke_backup() {
    pv_shell "rm -f $PVA_DOWNLOAD/PasswordVault-backup-* $PVA_DOWNLOAD/passwords-* $PVA_DOWNLOAD/$SMOKE_CSV" >/dev/null 2>&1
    smoke_settings && pva_reveal pv.open.Backup && pva_tap pv.open.Backup && pva_on Backup 20 || return 1
    if pva_present pv.backup.continue 3; then pva_tap pv.backup.continue || return 1; fi
    pva_need pv.backup.password 20 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_picker_save && pva_need pv.backup.done 60 || return 1
    pv_log "$(pva_text pv.backup.line.0 2>/dev/null)"
    SMOKE_BACKUP=$(pva_in_download '^PasswordVault-backup-.*\.pvault$' | tail -n 1)
    [ -n "$SMOKE_BACKUP" ] || { pv_log "no backup in Download"; return 1; }
    pva_tap pv.backup.done && pva_on Settings 20
}

smoke_export() {
    local name dir
    smoke_settings && pva_reveal pv.open.Export && pva_tap pv.open.Export && pva_on Export 20 || return 1
    pva_need pv.export.choice.csv 15 && pva_tap pv.export.choice.csv && pva_tap pv.export.continue || return 1
    if pva_present pv.export.understand 10; then pva_tap pv.export.understand || return 1; fi
    if pva_present pv.export.warning.continue 5; then pva_tap pv.export.warning.continue || return 1; fi
    pva_need pv.export.password 20 && pva_type pv.export.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.export.submit && pva_picker_save && pva_need pv.export.saved 60 || return 1
    pv_log "$(pva_text pv.export.saved 2>/dev/null)"
    name=$(pva_in_download '^passwords-.*\.csv$' | tail -n 1)
    [ -n "$name" ] || { pv_log "no CSV in Download"; return 1; }
    dir=$(mktemp -d "${TMPDIR:-/tmp}/pv-smoke.XXXXXX") || return 1
    pv_adb pull "$PVA_DOWNLOAD/$name" "$dir/$name" >/dev/null 2>&1 && grep -q 'smoke.user@example.uz' "$dir/$name" \
        || { pv_log "the export does not hold the account"; rm -rf "$dir"; return 1; }
    rm -rf "$dir"
    pv_shell "rm -f '$PVA_DOWNLOAD/$name'" >/dev/null 2>&1
    pva_tap pv.export.done && pva_on Settings 20
}

smoke_import() {
    local file deadline
    file=$(mktemp "${TMPDIR:-/tmp}/pv-smoke-csv.XXXXXX") || return 1
    printf 'name,url,username,password,note\r\nTashkent Metro,https://metro.uz,dilshod.m,Qx7mVb2Lp9Ws,\r\nKapitalbank,https://kapitalbank.uz,+998901112233,Hn4Rt8Ws2Kz6,\r\n' > "$file"
    pva_push_download "$file" "$SMOKE_CSV" || { rm -f "$file"; return 1; }
    rm -f "$file"
    smoke_settings && pva_reveal pv.open.Import && pva_tap pv.open.Import && pva_on Import 20 || return 1
    pva_need pv.import.source.csv 20 && pva_tap pv.import.source.csv || return 1
    deadline=$((SECONDS + 20))
    until pva_picker_in_front; do
        [ $SECONDS -lt $deadline ] || { pv_log "no picker; ids: $(pva_ids)"; return 1; }
        pva_present pv.import.chooseFile 1 && pva_tap pv.import.chooseFile
        pv_sleep 1
    done
    pva_picker_open "$SMOKE_CSV" pushed && pva_need pv.import.import 60 || return 1
    pva_tap pv.import.import && pva_need pv.import.done 60 || return 1
    pv_log "$(pva_text pv.import.done 2>/dev/null)"
    if pva_present pv.import.deleteFile 5; then pva_tap pv.import.deleteFile && pva_need pv.import.fileOutcome 20 || return 1; fi
    pva_tap pv.import.finish && pva_on Settings 20 && pva_tap pv.tab.Passwords && pva_on Passwords 20 && pva_row_with Kapitalbank 15
}

# Lock now (fingerprint is on: its prompt opens by itself, "Use PIN"); "Forgot PIN? Use master
# password"; "Forgot master password?" > "Restore from a backup file"; the backup, its master password,
# Restore: the app opens on the backup's vault; its PIN steps walked past.
smoke_restore() {
    pva_lock_now || return 1
    if pva_prompt_open UNLOCK 10; then pva_prompt_use_pin || return 1; fi
    pva_need pv.lock.pad 20 && pva_tap pv.lock.forgotPin && pva_need pv.lock.password 20 || return 1
    pva_hide_keyboard
    pva_tap pv.lock.forgot && pva_need pv.lock.sheet.restore 10 && pva_tap pv.lock.sheet.restore || return 1
    pva_on Restore 20 && pva_need pv.backup.choose 20 && pva_tap pv.backup.choose || return 1
    pva_picker_open "$SMOKE_BACKUP" || return 1
    pva_need pv.backup.password 30 && pva_type pv.backup.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.backup.submit && pva_need pv.backup.restore 60 || return 1
    pv_log "$(pva_text pv.backup.summary 2>/dev/null)"
    pva_tap pv.backup.restore && pva_unlocked Passwords 120 && pva_settle Passwords 90
}

smoke_restored() { pva_row_with Smoketest 15 && ! pva_row_titles | grep -qx Kapitalbank; }

stage_files() {
    pv_step "a backup into Download through the system's picker" -- smoke_backup || return 1
    pv_step "the CSV export (\"I understand\", the master password): the file holds the account" -- smoke_export
    pv_step "an import of a small CSV: its accounts in the list, the file deleted" -- smoke_import
    pv_step "a restore of the backup from the lock screen (\"Forgot master password?\")" -- smoke_restore || return 1
    pv_step "the list is the backup's: the account, not the imported ones" -- smoke_restored
    pv_step "no crash" -- pva_no_crash
}

# ---- stage: the app's language (since the device check of waves 6 and 8A) -----------------------------
# Build plan 12, "What the device check of waves 6 and 8A runs": Settings › Language › Русский, then
# Ўзбекча (i18n/lib.sh's language dialog), each time the same process and Settings again, the bottom
# bar's tabs in that language (the APK's own texts for the language, read with aapt2) and not in English;
# in that language the steps the scripts drive by resource id: an account added and opened, Lock now and
# the PIN (chosen once at a master-password unlock when the wave-5 stage's restore left none); then
# "System language": the tabs in English again.
source "$(dirname "${BASH_SOURCE[0]}")/i18n/lib.sh"

# The aapt2 qualifier of a language dialog's choice; empty for the default (English) texts.
smoke_qualifier() {
    case "$1" in
        RU) echo ru ;;
        UZ_CYRL) echo b+uz+Cyrl ;;
        UZ_LATN) echo uz ;;
        *) echo "" ;;
    esac
}

# smoke_tabs_in CHOICE: each tab's label is the APK's text for that language, and no English label is
# shown (unless the language is English).
smoke_tabs_in() {
    local q name want nodes english
    q=$(smoke_qualifier "$1")
    nodes=$(pv_ui_nodes)
    for name in passwords security settings; do
        want=$(pvi_apk_string "$APK" "shell_tab_$name" "$q")
        [ -n "$want" ] || { pv_log "the APK has no shell_tab_$name for '${q:-default}'"; return 1; }
        printf '%s\n' "$nodes" | cut -d'|' -f1 | grep -qxF -- "$want" || { pv_log "no tab label '$want' ($1); texts: $(printf '%s\n' "$nodes" | cut -d'|' -f1 | grep -v '^$' | tr '\n' ';' | cut -c1-400)"; return 1; }
    done
    [ -z "$q" ] && return 0
    english=$(printf '%s\n' "$nodes" | awk -F'|' '$1 == "Passwords" || $1 == "Security" || $1 == "Settings" { print $1 }')
    [ -z "$english" ] || { pv_log "English labels in $1: $english"; return 1; }
}

# smoke_language CHOICE: from Settings, the language dialog and CHOICE's row; Settings again in the same
# process, with the tabs in that language.
smoke_language() {
    local before after
    smoke_settings || return 1
    before=$(pva_pid)
    pvi_open_language && pvi_choose "$1" Settings || return 1
    after=$(pva_pid)
    [ -n "$before" ] && [ "$before" = "$after" ] || { pv_log "the process changed: $before, then $after"; return 1; }
    pva_on Settings 10 && smoke_tabs_in "$1"
}

# smoke_account_in NAME: an account added through the form, its screen after Save; Back, and the list's
# row opens it again.
smoke_account_in() {
    local title row
    pva_tap pv.tab.Passwords && pva_on Passwords 20 || return 1
    pva_add_account "$1" "$1.user@example.uz" Pw7Lq2Mz9Xc4 "https://$1.example" "Note $1" || return 1
    title=$(pva_text pv.entry.title) && [ "$title" = "$1" ] || { pv_log "the account's screen shows '${title:-nothing}'"; return 1; }
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20 && pva_row_with "$1" 10 || return 1
    row=$(pv_ui_nodes | awk -F'|' -v t="$1" '$3 ~ /^pv[.]list[.]row[.]/ { id = $3; next } id != "" && $1 == t { print id; exit } $3 ~ /^pv[.]/ { id = "" }')
    [ -n "$row" ] || { pv_log "no row node for '$1'"; return 1; }
    pva_tap "$row" && pva_on Entry 20 || return 1
    title=$(pva_text pv.entry.title) && [ "$title" = "$1" ] || { pv_log "the row opened '${title:-nothing}'"; return 1; }
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20
}

# The prompt's negative button, found by its resource id in every window (its text is the app's own,
# in the app's language).
smoke_prompt_negative() {
    local i
    for i in 1 2 3 4 5 6; do
        pv_ui_tap '[|](button_negative|negative_button|button2)$' "$(pv_ui_nodes_all 2>/dev/null || pv_ui_nodes)" >/dev/null && return 0
        pv_sleep 1
    done
    pv_log "no negative button on the prompt"
    return 1
}

# smoke_has_pin: the current vault has a PIN slot. The status lists every lock directory: after the
# wave-5 stage's restore the vault put aside keeps its own PIN record, so only the current vault's
# ("vaults: <id> generation N", then "lock <id>" and its pin.rec) counts.
smoke_has_pin() {
    pva_status | awk '
        /^vaults: / { current = $2 }
        /^lock / { here = ($2 == current) }
        here && /^pin[.]rec: OK, slot set/ { found = 1 }
        END { exit found ? 0 : 1 }'
}

# smoke_pin_set: a PIN slot. The wave-5 stage's restore leaves none ("Choose a PIN" was answered "Not
# now" there): Lock now, the master password, and "Choose a PIN" with SMOKE_PIN typed twice; the
# fingerprint offer "Not now".
smoke_pin_set() {
    smoke_has_pin && return 0
    pva_lock_now || return 1
    if pva_prompt_open UNLOCK 5; then smoke_prompt_negative || return 1; fi
    pva_need pv.lock.password 20 && pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock && pva_opened 90 && pva_to_create_pin && pva_choose_pin "$SMOKE_PIN" && pva_after_pin notNow Passwords || return 1
    smoke_has_pin || { pv_log "the current vault has no PIN slot: $(pva_status | tr '\n' ';' | cut -c1-600)"; return 1; }
}

# smoke_lock_pin: Lock now; the pad (under the prompt, when fingerprint unlock is on); the PIN opens the
# list.
smoke_lock_pin() {
    pva_lock_now || return 1
    if pva_prompt_open UNLOCK 5; then smoke_prompt_negative || return 1; fi
    pva_need pv.lock.pad 20 && pva_pin "$SMOKE_PIN" && pva_opened 30 && pva_settle Passwords 60
}

stage_language() {
    local choice name
    for choice in RU UZ_CYRL; do
        name=$(pvi_own_name "$choice")
        pv_step "Settings › Language › $name: the same process, Settings again, the tabs in $name" -- smoke_language "$choice" || return 1
        pv_step "$name: an account added through the form and opened from the list" -- smoke_account_in "$([ "$choice" = RU ] && echo Russkiy || echo Ozbek)"
        [ "$choice" = RU ] && pv_step "$name: the PIN is set (Lock now, the master password, \"Choose a PIN\")" -- smoke_pin_set
        pv_step "$name: Lock now; the PIN opens the list" -- smoke_lock_pin
    done
    pv_step "Settings › Language › System language: the tabs in English again" -- smoke_language SYSTEM
    pv_step "no crash" -- pva_no_crash
}

stage_install_and_start && stage_skeleton && stage_clipboard && stage_pin_and_fingerprint && stage_files && stage_language
pv_summary
