#!/usr/bin/env bash
# tools/emu/lock/E15.sh - lock.md §15.3 check E15 (Android 9, 17): Android's own backup on the variant
# built only for this check (backupCheck: the manifest allows backup and drops the rules that exclude
# every domain, architecture.md §7.2): `bmgr backupnow`, clear, restore: the vault file comes back and
# no lock record does. With the shipped manifest nothing at all is backed up, so the check would pass
# without testing anything.
#
#   tools/emu/lock/E15.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37: bmgr's local transport; the Android 7 image
#       is not listed for E15). Without --apk the script builds the backupCheck variant itself
#       (tools/gradle.sh :app:assembleBackupCheck) and uses
#       app/build/outputs/apk/backupCheck/app-backupCheck.apk. The backup service's state (enabled or
#       not, the transport chosen) is put back when the script ends.
#
# Steps:
#   - the backupCheck APK built (or given); its manifest says android:allowBackup="true";
#   - the app installed afresh and set up with the PIN: the vault file is under files/vaults/live/ and
#     the three lock records under no_backup/lock/<vault_id>/ (run-as: the variant is debuggable);
#   - the backup service on, the local transport chosen (com.android.localtransport/.LocalTransport
#     on API 37, android/com.android.internal.backup.LocalTransport on API 28), `bmgr backupnow`:
#     Success;
#   - `pm clear`: no vault file, no lock directory;
#   - `bmgr restore` of the package: the vault file is back, byte for byte the one that was backed up;
#     no_backup/ holds no lock record (noBackupFilesDir is never in a backup, lock.md §5.2);
#   - the app started (its process ended first if the restore left it running in restricted mode):
#     "Welcome back" (lock.md F14: a vault without its lock directory), and the master password opens
#     the same vault.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
GIVEN_APK=0
for a in "$@"; do [ "$a" = --apk ] && GIVEN_APK=1; done
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
if [ $GIVEN_APK = 0 ]; then
    set -- "$@" --apk "$REPO/app/build/outputs/apk/backupCheck/app-backupCheck.apk"
fi
# pvl_begin wants the APK to exist; build it first when it is ours to build.
if [ $GIVEN_APK = 0 ]; then
    echo "E15: building the backupCheck variant (tools/gradle.sh :app:assembleBackupCheck)"
    mkdir -p "$REPO/build/emu-lock"
    if ! "$REPO/tools/gradle.sh" :app:assembleBackupCheck > "$REPO/build/emu-lock/E15-build.log" 2>&1; then
        echo "FAIL the backupCheck variant builds (see $REPO/build/emu-lock/E15-build.log)"
        tail -n 20 "$REPO/build/emu-lock/E15-build.log" >&2
        exit 1
    fi
fi
pvl_begin E15 "$@"
VARIANT=backupCheck

LIVE=files/vaults/live
VAULT_ID=""

allows_backup() {
    local aapt2
    aapt2=$(pvl_aapt2)
    "$aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null | grep -q 'android:allowBackup.*=true' \
        || { pv_log "the APK's manifest does not allow backup: is $APK the backupCheck variant?"; return 1; }
}

run_as() { pv_shell "run-as $PV_PACKAGE sh -c '$1'" 2>/dev/null | tr -d '\r'; }

vault_files() { run_as "ls -1 $LIVE 2>/dev/null" | grep '\.pvault$'; }

files_there() {
    local files
    pv_is_debuggable || { pv_log "run-as does not work: the backupCheck variant is debuggable"; return 1; }
    files=$(vault_files)
    [ -n "$files" ] || { pv_log "no vault file under $LIVE"; return 1; }
    pvl_status || return 1
    VAULT_ID=$(pvl_vault_id)
    [ -n "$VAULT_ID" ] || { pv_log "no vault id in the status"; return 1; }
    run_as "ls -1 no_backup/lock/$VAULT_ID" > "$PVL_OUT/lock-files-before.txt"
    grep -qx pin.rec "$PVL_OUT/lock-files-before.txt" && grep -qx state.rec "$PVL_OUT/lock-files-before.txt" \
        || { pv_log "the lock records: $(tr '\n' ' ' < "$PVL_OUT/lock-files-before.txt")"; return 1; }
    run_as "sha256sum $LIVE/*.pvault" > "$PVL_OUT/vault-sha-before.txt"
    pvl_note "E15: vault files $(printf '%s' "$files" | tr '\n' ' '); lock records $(tr '\n' ' ' < "$PVL_OUT/lock-files-before.txt")"
}

TRANSPORT_BEFORE=""
ENABLED_BEFORE=""

backup_on() {
    local local_transport
    ENABLED_BEFORE=$(pv_shell bmgr enabled 2>/dev/null | tr -d '\r')
    TRANSPORT_BEFORE=$(pv_shell bmgr list transports 2>/dev/null | tr -d '\r' | sed -n 's/^ *\* *//p')
    case "$ENABLED_BEFORE" in *disabled*) pvl_undo "bmgr enable false" ;; esac
    [ -n "$TRANSPORT_BEFORE" ] && pvl_undo "bmgr transport $TRANSPORT_BEFORE"
    pv_shell bmgr enable true >/dev/null 2>&1
    local_transport=$(pv_shell bmgr list transports 2>/dev/null | tr -d '\r' | sed 's/^ *\*\{0,1\} *//' | grep -m 1 -i 'LocalTransport')
    [ -n "$local_transport" ] || { pv_log "no local transport: $(pv_shell bmgr list transports | tr -d '\r' | tr '\n' ' ')"; return 1; }
    pv_shell bmgr transport "$local_transport" >/dev/null 2>&1
    pv_sleep 2
    pv_shell bmgr list transports 2>/dev/null | tr -d '\r' | grep -q "^ *\* *$local_transport" \
        || { pv_log "the local transport was not chosen: $(pv_shell bmgr list transports | tr -d '\r' | tr '\n' ' ')"; return 1; }
    pvl_note "E15: backup enabled with $local_transport (was: '$ENABLED_BEFORE', transport '$TRANSPORT_BEFORE')"
}

backup_now() {
    local out
    out=$(PV_ADB_TIMEOUT=300 pv_shell bmgr backupnow "$PV_PACKAGE" 2>&1 | tr -d '\r')
    printf '%s\n' "$out" > "$PVL_OUT/backupnow.txt"
    printf '%s\n' "$out" | grep -q "Package $PV_PACKAGE with result: Success" || { pv_log "bmgr backupnow: $out"; return 1; }
}

cleared() {
    local out
    out=$(pv_shell pm clear "$PV_PACKAGE" 2>&1 | tr -d '\r')
    printf '%s\n' "$out" | grep -q Success || { pv_log "pm clear: $out"; return 1; }
    [ -z "$(vault_files)" ] || { pv_log "a vault file is still there"; return 1; }
    [ -z "$(run_as 'ls -1 no_backup/lock 2>/dev/null')" ] || { pv_log "the lock directory is still there"; return 1; }
}

restored() {
    local out sets token
    out=$(PV_ADB_TIMEOUT=300 pv_shell bmgr restore "$PV_PACKAGE" 2>&1 | tr -d '\r')
    printf '%s\n' "$out" > "$PVL_OUT/restore.txt"
    if ! printf '%s\n' "$out" | grep -q -i 'restoreFinished: 0\|done'; then
        # Older bmgr: a restore set's token first.
        sets=$(pv_shell bmgr list sets 2>/dev/null | tr -d '\r')
        token=$(printf '%s\n' "$sets" | awk '{ print $1; exit }')
        [ -n "$token" ] || { pv_log "bmgr restore: $out; bmgr list sets: $sets"; return 1; }
        out=$(PV_ADB_TIMEOUT=300 pv_shell bmgr restore "$token" "$PV_PACKAGE" 2>&1 | tr -d '\r')
        printf '%s\n' "$out" >> "$PVL_OUT/restore.txt"
    fi
    pv_sleep 3
    pvl_note "E15: bmgr restore said: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-300)"
}

vault_back_no_record() {
    local after lock
    [ -n "$(vault_files)" ] || { pv_log "no vault file came back"; return 1; }
    run_as "sha256sum $LIVE/*.pvault" > "$PVL_OUT/vault-sha-after.txt"
    cmp -s "$PVL_OUT/vault-sha-before.txt" "$PVL_OUT/vault-sha-after.txt" \
        || { pv_log "the vault file differs: before $(cat "$PVL_OUT/vault-sha-before.txt"), after $(cat "$PVL_OUT/vault-sha-after.txt")"; return 1; }
    lock=$(run_as 'find no_backup -type f 2>/dev/null')
    [ -z "$lock" ] || { pv_log "files under no_backup came back: $lock"; return 1; }
    after=$(run_as 'ls -1R files 2>/dev/null' | tr '\n' ' ')
    pvl_note "E15: after the restore: files: $after; no_backup: nothing"
}

# After `bmgr restore` the app's process may still run as the restore left it: started for the backup
# agent in restricted mode, with android.app.Application in place of PasswordVaultApp. An activity
# started in that process fails at once (ClassCastException in MainActivity; seen with bmgr on API 28).
# A restore on a new phone (setup wizard, cloud) does not leave the app running so, so the process is
# ended first, as that restore and any reboot would end it, and the fact is noted.
welcome_back() {
    local pid
    pid=$(pva_pid)
    if [ -n "$pid" ]; then
        pvl_note "E15 on API $API: after bmgr restore the app's process $pid still runs (restricted mode); it is stopped before the app is started"
        pvl_force_stop || return 1
    fi
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pva_start || return 1
    pvl_wait_screen master-password 60 || return 1
    pvl_text_is pv.lock.title string lock_mp_title_welcome_back || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_submit_open pv.lock.unlock Passwords 120 || return 1
    pvl_status || return 1
    [ "$(pvl_vault_id)" = "$VAULT_ID" ] || { pv_log "another vault: $(pvl_vault_id), was $VAULT_ID"; return 1; }
}

pv_step "the backupCheck APK allows Android backup" -- allows_backup || pvl_end
pv_step "install the app afresh: Welcome" -- pvl_fresh || pvl_end
pv_step "set up with the PIN (fingerprint not now): the list" -- pvl_set_up "$PVL_PIN" - || pvl_end
pv_step "the vault file and the lock records are on the phone" -- files_there || pvl_end
pv_step "the backup service on, with the local transport" -- backup_on || pvl_end
pv_step "bmgr backupnow: Success" -- backup_now || pvl_end
pv_step "pm clear: nothing of the app is left" -- cleared || pvl_end
pv_step "bmgr restore of the package" -- restored
pv_step "the vault file is back as it was; no lock record is" -- vault_back_no_record
pv_step "the app: \"Welcome back\", and the master password opens the same vault" -- welcome_back
pvl_end
