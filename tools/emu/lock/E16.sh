#!/usr/bin/env bash
# tools/emu/lock/E16.sh - lock.md §15.3 check E16 (Android 7): a small heap (heapsim.sh 128m 256m) with
# the Java Argon2 fallback forced: one derivation at a time succeeds with largeHeap; where it cannot
# fit, "not enough memory" shows, the app does not crash, and nothing is counted [L-old §2].
#
#   tools/emu/lock/E16.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh and runs a userdebug image (su; the Android 7 image, which
#       lock.md lists; API 28 works too). The APK defaults to the debug build. The heap properties are
#       put back when the script ends (tools/emu/lock/heapsim.sh --restore).
#
# The fallback is forced the way it happens on a phone where argon2kt cannot load (its library missing,
# [V-C2]): a copy of the APK without lib/*/libargon2jni.so and libargon2native.so, aligned with
# zipalign and signed with the debug key (~/.android/debug.keystore) by the SDK's apksigner. argon2kt's
# load then throws UnsatisfiedLinkError, and GuardedArgon2id derives with Bouncy Castle when the free
# Java heap is at least 160 MiB below API 28 (96 MiB from API 28; PlatformModule.fallbackNeeds), read
# again after a garbage collection when it looks smaller, and otherwise says KdfUnavailable: "not
# enough memory" (lock.md §6.8 step 3; build-plan.md §12, the owner's decision 22).
# Steps:
#   - the copy without argon2kt's libraries made;
#   - heapsim.sh 128m 256m: a largeHeap app (PasswordVault is one) gets 256 MiB;
#   - the copy installed afresh; setup with the suggested words and the PIN: two derivations through
#     the fallback, one after the other (the vault is made, then opened from disk with the second
#     typing, lock.md F1 step 3): the list. (Until decision 22 this failed on API 24 with "Your phone
#     doesn't have enough free memory to finish setup", while it passed at 320m and 384m; should it
#     fail, the script goes on with a vault made at the image's own heap, 512m, so that the steps
#     below still run);
#   - one derivation at 128m/256m: Lock now, "Forgot PIN? Use master password", the master password:
#     the app opens ("Choose a new PIN" after it, F7, is answered "Not now"); then at once the same
#     again: the second derivation of two close together must succeed as well;
#   - heapsim.sh 64m 128m: 128 MiB, less than the fallback needs;
#   - the app started; the master password: "Your phone doesn't have enough free memory to open your
#     passwords. …" with "Try again"; no crash; `status` counts no wrong master password;
#   - the PIN opens the app (no derivation);
#   - the heap put back.
# Prints PASS, FAIL or SKIPPED per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a
# usage error, 3 for a refused serial.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
pvl_begin E16 "$@"
if ! pv_has_root; then
    echo "E16: heapsim.sh needs a userdebug image (su); $SERIAL (API $API) has none" >&2
    trap - EXIT
    exit 2
fi
pvl_undo_heap() { "$LOCK_DIR/heapsim.sh" "$SERIAL" --restore >/dev/null 2>&1; }
trap 'pvl_undo_heap; pvl_cleanup' EXIT

BT=$(ls -d "$PV_SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)
NONATIVE=$PVL_OUT/app-without-argon2kt.apk

no_native_apk() {
    local work=$PVL_OUT/nonative
    rm -rf "$work"; mkdir -p "$work"
    cp "$APK" "$work/in.apk" || return 1
    zip -q -d "$work/in.apk" 'lib/*/libargon2jni.so' 'lib/*/libargon2native.so' || { pv_log "zip -d found nothing to remove"; return 1; }
    ! unzip -l "$work/in.apk" | grep -q 'libargon2' || { pv_log "argon2kt's libraries are still in the copy"; return 1; }
    "$BT/zipalign" -f -P 16 4 "$work/in.apk" "$work/aligned.apk" || return 1
    PATH=/home/asus/.jdks/jdk-25/bin:$PATH "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
        --ks-pass pass:android --key-pass pass:android --out "$NONATIVE" "$work/aligned.apk" || return 1
    pvl_note "E16: $NONATIVE: $(unzip -l "$NONATIVE" | grep -c '\.so$') native libraries left ($(unzip -l "$NONATIVE" | awk '/\.so$/ { print $4 }' | tr '\n' ' '))"
}

heap() { "$LOCK_DIR/heapsim.sh" "$SERIAL" "$1" "$2"; }

install_no_native() { APK=$NONATIVE pva_install "$NONATIVE" && pva_start && pva_on Welcome 30; }

set_up_at_small_heap() {
    local message
    pvl_set_up "$PVL_PIN" - && return 0
    message=$(pva_text pv.setup.againMessage 2>/dev/null)
    pvl_note "E16 on API $API: setup at 128m/256m through the fallback ended with: '${message:-?}'; the app's Dalvik heap: $(pv_shell dumpsys meminfo "$PV_PACKAGE" 2>/dev/null | tr -d '\r' | grep -m 1 'Dalvik Heap' | tr -s ' ')"
    return 1
}

set_up_at_image_heap() {
    "$LOCK_DIR/heapsim.sh" "$SERIAL" --restore || return 1
    install_no_native && pvl_set_up "$PVL_PIN" -
}

# The second of two derivations close together: one at a time, each must succeed (the Java heap that
# the first one used, about 134 MiB on Android 7 [L-old §2], is garbage then). Says what the screen
# says when it does not.
mp_again() {
    pvl_lock_now && pvl_wait_screen 'pad|master-password' 20 || return 1
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock || return 1
    if pva_unlocked Passwords 60; then pva_settle Passwords; return; fi
    pvl_note "E16 on API $API: the second derivation at once: $(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]lock[.]line[.]/ { f = 1 } f && $1 != "" { print $1; exit }')"
    # Recorded, for the cause: 20 s later, when the first derivation's garbage is likely collected,
    # "Try again" (it clears the line) and the master password once more.
    pv_sleep 20
    pva_present pv.lock.tryAgain 3 && pva_tap pv.lock.tryAgain
    pv_sleep 1
    # The field may have kept the password (its mask) or have been emptied by the check.
    [ -n "$(pva_text pv.lock.password 2>/dev/null)" ] || pva_type pv.lock.password "$PVA_PASSWORD"
    pva_hide_keyboard
    if pva_tap pv.lock.unlock && pva_unlocked Passwords 60; then
        pvl_note "E16 on API $API: 20 s later the same master password opened the app"
        pva_settle Passwords
    else
        pvl_note "E16 on API $API: 20 s later the master password did not open the app either: $(pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]lock[.]line[.]/ { f = 1 } f && $1 != "" { print $1; exit }')"
    fi
    return 1
}

mp_opens() {
    pvl_status || return 1
    if [ "$(pvl_lock_state)" = unlocked ]; then pvl_lock_now || return 1; fi
    pvl_wait_screen 'pad|master-password' 20 || return 1
    pvl_unlock_mp
}

no_memory() {
    local i
    pva_start || return 1
    pvl_wait_screen 'pad|master-password' 60 || return 1
    pvl_to_master_password || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock || return 1
    pvl_text_shown string lock_mp_no_memory 60 || return 1
    pva_present pv.lock.tryAgain 5 || { pv_log "no \"Try again\""; return 1; }
    pvl_status || return 1
    [ "$(pvl_state wrong)" = 0 ] || { pv_log "a wrong master password was counted: $(pvl_vault_item state.rec)"; return 1; }
    case "$(pvl_lock_state)" in "locked: "*) ;; *) pv_log "the lock is '$(pvl_lock_state)'"; return 1 ;; esac
    pva_no_crash
}

pin_opens() { pvl_to_pad 20 && pva_pin "$PVL_PIN" && pvl_open_list 30; }

pv_step "a copy of the APK without argon2kt's libraries" -- no_native_apk || pvl_end
pv_step "heapsim.sh 128m 256m: largeHeap gets 256 MiB" -- heap 128m 256m || pvl_end
pv_step "the copy installed afresh: Welcome" -- install_no_native || pvl_end
if ! pv_step "setup at 128m/256m through the Java fallback (two derivations, one after the other): the list" -- set_up_at_small_heap; then
    pv_step "the heap of the image (512m), the copy installed afresh and set up there" -- set_up_at_image_heap || pvl_end
    pv_step "heapsim.sh 128m 256m again" -- heap 128m 256m || pvl_end
    pv_step "the app started again: the lock screen" -- eval 'pva_start && pvl_wait_screen "pad|master-password" 60' || pvl_end
fi
pv_step "one derivation at 128m/256m: Lock now, the master password through the fallback: the list" -- mp_opens
pv_step "the next derivation at once (Lock now, the master password again): the list" -- mp_again
pv_step "heapsim.sh 64m 128m: 128 MiB, less than the fallback needs" -- heap 64m 128m || pvl_end
pv_step "the master password: \"not enough memory\", \"Try again\", nothing counted, no crash" -- no_memory
pv_step "the PIN opens the app" -- pin_opens
pv_step "the heap put back" -- "$LOCK_DIR/heapsim.sh" "$SERIAL" --restore
pvl_end
