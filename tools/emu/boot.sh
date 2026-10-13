#!/usr/bin/env bash
# tools/emu/boot.sh - start a private emulator from the read-only base under /home/asus/pvlab.
#
#   tools/emu/boot.sh [--integrator] [--slot-wait SECONDS] [--boot-timeout SECONDS] API
#       API is 24 (Android 7.0), 28 (Android 9) or 37 (Android 17). Starts the base device
#       pvlab_apiAPI with -read-only (nothing it does is kept) on a console port handed out
#       from 5556..5578 through a lock file, at most six package devices at once (waits up to
#       --slot-wait seconds, default 1800, for a free slot). --integrator takes the integrator's
#       fixed port instead: 5580 for API 37, 5582 for 28, 5584 for 24. Waits until the device has
#       booted and the lock screen is dismissed with the device PIN 1111, then prints its serial
#       (emulator-PORT) on stdout; messages go to stderr. Stop it with tools/emu/stop.sh SERIAL.
#       An API 37 device is put in airplane mode once it is ready: with the network on, the Play
#       Store updates its apps within about 20 minutes of a boot and fills the 6 GB /data to 96 %,
#       after which installs fail ("Requested internal only, but not enough space"; build plan 6.1,
#       2026-10-09). PV_BOOT_NETWORK=on keeps the network.
#   tools/emu/boot.sh --list               the devices boot.sh started, for every worktree
#   tools/emu/boot.sh --verify-base [API]  the base's disk images against their sha1 record
#   tools/emu/boot.sh --provision API      (once) make the base again from its system image and
#                                          provision it; refuses while a device of it runs
#
# Exit codes: 0 booted; 1 failed; 2 usage; 3 refused.
set -u
source "$(dirname "${BASH_SOURCE[0]}")/adb.sh"

PV_EMULATOR=$PV_SDK/emulator/emulator
PV_AVD_HOME=$PV_LAB/avd
PV_LOGS=$PV_RUN/logs
PV_APIS="24 28 37"

usage() { pv_usage "$0"; }

pv_marker() { echo "$PV_AVD_HOME/pvlab_api$1.provisioned"; }

pv_sysimg_dir() {
    case "$1" in
        24) echo "$PV_LAB/sysimg/android-24/default/x86_64" ;;
        28) echo "$PV_LAB/sysimg/android-28/default/x86_64" ;;
        37) echo "$PV_LAB/sysimg/android-37.0/google_apis_playstore/x86_64" ;;
    esac
}

# ---- system images ------------------------------------------------------------------------------
# API 24 and 28: the plain AOSP x86_64 images; sha1 sums from docs/lab/old-android.md section 0,
# which match https://dl.google.com/android/repository/sys-img/android/sys-img2-5.xml.
pv_zip_name() { case "$1" in 24) echo x86_64-24_r08.zip ;; 28) echo x86_64-28_r04.zip ;; esac; }
pv_zip_sha1() {
    case "$1" in
        24) echo f6559e1949a5879f31a9662f4f0e50ad60181684 ;;
        28) echo d47a85c8f4e9fd57df97814ad8884eeb0f3a0ef0 ;;
    esac
}

pv_fetch_sysimg() { # API -> makes sure the image is under $PV_LAB/sysimg and was checked
    local api=$1 zip sum dir
    dir=$(pv_sysimg_dir "$api")
    mkdir -p "$PV_LAB/dl" || return 1
    if [ "$api" = 37 ]; then
        # The SDK's own Google Play image, copied so that an SDK update cannot change the base.
        local sdkimg=$PV_SDK/system-images/android-37.0/google_apis_playstore/x86_64
        if [ ! -f "$dir/system.img" ]; then
            [ -f "$sdkimg/system.img" ] || { pv_log "no API 37 image in $sdkimg"; return 1; }
            pv_log "copying $sdkimg to $dir"
            mkdir -p "$(dirname "$dir")" && cp -a "$sdkimg" "$(dirname "$dir")/" || return 1
            (cd "$dir" && find . -type f -print0 | sort -z | xargs -0 sha1sum) > "$PV_LAB/dl/sysimg-37.sha1" || return 1
        fi
        [ -f "$PV_LAB/dl/sysimg-37.sha1" ] || { pv_log "missing $PV_LAB/dl/sysimg-37.sha1"; return 1; }
        (cd "$dir" && sha1sum --quiet -c "$PV_LAB/dl/sysimg-37.sha1") || { pv_log "the API 37 image differs from its sha1 list"; return 1; }
        pv_log "API 37 image: every file matches $PV_LAB/dl/sysimg-37.sha1"
        return 0
    fi
    zip=$PV_LAB/dl/$(pv_zip_name "$api")
    if [ ! -f "$zip" ]; then
        pv_log "downloading $(basename "$zip")"
        curl -sS -L --retry 5 --retry-delay 5 -C - -o "$zip.part" \
            "https://dl.google.com/android/repository/sys-img/android/$(basename "$zip")" || return 1
        mv -f "$zip.part" "$zip"
    fi
    sum=$(sha1sum "$zip" | cut -d' ' -f1)
    if [ "$sum" != "$(pv_zip_sha1 "$api")" ]; then
        pv_log "sha1 of $zip is $sum, expected $(pv_zip_sha1 "$api")"
        return 1
    fi
    pv_log "$(basename "$zip"): sha1 $sum matches docs/lab/old-android.md"
    if [ ! -f "$dir/system.img" ]; then
        mkdir -p "$(dirname "$dir")" && unzip -q -o "$zip" -d "$(dirname "$dir")" || return 1
    fi
    # The unpacked files must be the zip's.
    python3 - "$zip" "$dir" <<'EOF' || return 1
import os, sys, zipfile, zlib
zpath, d = sys.argv[1], sys.argv[2]
for info in zipfile.ZipFile(zpath).infolist():
    if info.is_dir():
        continue
    path = os.path.join(os.path.dirname(d), info.filename)
    crc = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            crc = zlib.crc32(chunk, crc)
    if crc != info.CRC or os.path.getsize(path) != info.file_size:
        sys.exit(f"{path} differs from {zpath}")
EOF
}

# ---- the base AVDs -------------------------------------------------------------------------------
# Modelled on a copy of the user's AVD config (Medium_Phone_API_37.0, read from the copy made by
# the labs, never from ~/.android/avd): medium_phone, 1080 wide, 420 dpi, hardware keyboard.
# Changed for unattended use: 4 cores, swiftshader, no audio, no cameras, no skin or frame,
# cold boot, 6 GB data partition; API 24 and 28 keep the lab's 1080x1920 screen.
pv_write_avd() { # API
    local api=$1 name dir target tagid tagdisp play height ram
    name=pvlab_api$api
    dir=$PV_AVD_HOME/$name.avd
    case "$api" in
        24) target=android-24; tagid=default; tagdisp="Default Android System Image"; play=false; height=1920; ram=2048 ;;
        28) target=android-28; tagid=default; tagdisp="Default Android System Image"; play=false; height=1920; ram=2048 ;;
        37) target=android-37.0; tagid=google_apis_playstore; tagdisp="Google Play"; play=true; height=2400; ram=4096 ;;
    esac
    mkdir -p "$dir" || return 1
    cat > "$PV_AVD_HOME/$name.ini" <<EOF
avd.ini.encoding=UTF-8
path=$dir
target=$target
EOF
    cat > "$dir/config.ini" <<EOF
AvdId=$name
PlayStore.enabled=$play
abi.type=x86_64
avd.ini.displayname=PasswordVault lab API $api
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
hw.accelerometer=yes
hw.arc=false
hw.audioInput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Generic
hw.device.name=medium_phone
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=$height
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=$ram
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=$(pv_sysimg_dir "$api")/
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=no
skin.dynamic=yes
skin.name=1080x$height
skin.path=_no_skin
tag.display=$tagdisp
tag.id=$tagid
target=$target
vm.heapSize=336
EOF
}

pv_disk_files() { # API -> the base's disk images, relative to $PV_AVD_HOME
    find "$PV_AVD_HOME/pvlab_api$1.avd" -maxdepth 1 -type f \( -name '*.img' -o -name '*.qcow2' \) \
        -printf "pvlab_api$1.avd/%f\n" | sort
}

# A read-only boot never writes the disk images (it keeps its changes in /tmp/android-$USER),
# so their size and time must still be what provisioning recorded.
pv_base_check() { # API
    local api=$1 marker rel size mtime sha cur bad=0
    marker=$(pv_marker "$api")
    if [ ! -f "$marker" ]; then
        pv_log "the base pvlab_api$api is not provisioned: tools/emu/boot.sh --provision $api"
        return 1
    fi
    while IFS='|' read -r rel size mtime sha; do
        cur=$(stat -c '%s|%Y' "$PV_AVD_HOME/$rel" 2>/dev/null)
        if [ "$cur" != "$size|$mtime" ]; then
            pv_log "the base pvlab_api$api changed after provisioning: $rel is ${cur:-missing}, recorded $size|$mtime"
            bad=1
        fi
    done < <(sed -n 's/^disk=//p' "$marker")
    if [ $bad = 1 ]; then
        pv_log "someone booted the base without -read-only; provision it again: tools/emu/boot.sh --provision $api"
        return 1
    fi
}

pv_verify_base() { # API -> full sha1 comparison
    local api=$1 marker rel size mtime sha cur bad=0
    marker=$(pv_marker "$api")
    [ -f "$marker" ] || { echo "pvlab_api$api: not provisioned"; return 1; }
    pv_base_check "$api" || bad=1
    while IFS='|' read -r rel size mtime sha; do
        cur=$(sha1sum "$PV_AVD_HOME/$rel" 2>/dev/null | cut -d' ' -f1)
        if [ "$cur" = "$sha" ]; then echo "OK   $rel $sha"; else echo "DIFF $rel ${cur:-missing} (recorded $sha)"; bad=1; fi
    done < <(sed -n 's/^disk=//p' "$marker")
    [ $bad = 0 ] && echo "pvlab_api$api: base unchanged since $(sed -n 's/^provisioned=//p' "$marker")"
    return $bad
}

# ---- ports and the registry ------------------------------------------------------------------------
pv_port_free() { # PORT -> 0 if neither PORT nor PORT+1 is in use and no entry holds it
    [ ! -f "$PV_REG/emulator-$1" ] || return 1
    [ -z "$(ss -ltnH "( sport = :$1 or sport = :$(($1 + 1)) )" 2>/dev/null)" ]
}

pv_reserve() { # API MODE -> prints the port and writes its entry; runs under pv_locked
    # return 75: no free slot; 76: the base is being provisioned; 77: integrator port in use;
    # 78: (provision only) a device of this base is running
    local api=$1 mode=$2 f n=0 p port="" fmode
    pv_forget_stale
    for f in "$PV_REG"/emulator-*; do
        [ -f "$f" ] || continue
        fmode=$(pv_entry_get "$f" mode)
        if [ "$(pv_entry_get "$f" api)" = "$api" ]; then
            [ "$fmode" = provision ] && return 76
            [ "$mode" = provision ] && return 78
        fi
        case "$fmode" in package|provision) n=$((n + 1)) ;; esac
    done
    if [ "$mode" = integrator ]; then
        port=$(pv_integrator_port "$api")
        pv_port_free "$port" || return 77
    else
        [ $n -lt $PV_MAX_PACKAGE_DEVICES ] || return 75
        for p in $(seq $PV_POOL_FIRST 2 $PV_POOL_LAST); do
            if pv_port_free "$p"; then port=$p; break; fi
        done
        [ -n "$port" ] || return 75
    fi
    cat > "$PV_REG/emulator-$port" <<EOF
serial=emulator-$port
port=$port
api=$api
avd=pvlab_api$api
owner=$(pv_owner)
mode=$mode
pid=0
booter=$$
epoch=$(date +%s)
started=$(date -Iseconds)
log=$PV_LOGS/emulator-$port-$(date +%Y%m%d-%H%M%S).log
tmpfiles=
EOF
    echo "$port"
}

pv_list() {
    local f
    printf '%-15s %-4s %-11s %-8s %-6s %-26s %s\n' SERIAL API MODE PID STATE STARTED OWNER
    for f in "$PV_REG"/emulator-*; do
        [ -f "$f" ] || continue
        printf '%-15s %-4s %-11s %-8s %-6s %-26s %s\n' "$(pv_entry_get "$f" serial)" "$(pv_entry_get "$f" api)" \
            "$(pv_entry_get "$f" mode)" "$(pv_entry_get "$f" pid)" "$(pv_entry_alive "$f" && echo up || echo gone)" \
            "$(pv_entry_get "$f" started)" "$(pv_entry_get "$f" owner)"
    done
}

# ---- starting and waiting ------------------------------------------------------------------------
pv_launch() { # API PORT LOG READONLY(1|0) [EXTRA EMULATOR ARGS...]
    local api=$1 port=$2 log=$3 ro=$4 args
    shift 4
    args=(-avd "pvlab_api$api")
    [ "$ro" = 1 ] && args+=(-read-only)
    args+=(-port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect "$@")
    pv_log "starting: emulator ${args[*]}"
    # Close fd 3 (the caller's stdout, see pv_main) and the lock's fd 9: an emulator holding
    # the write end of "S=$(boot.sh 37)" would keep that command waiting until it exits.
    ANDROID_AVD_HOME=$PV_AVD_HOME nohup setsid "$PV_EMULATOR" "${args[@]}" > "$log" 2>&1 < /dev/null 3>&- 9>&- &
}

pv_wait_pid() { # PORT -> prints the emulator's pid once it exists (60 s)
    local i pid
    for i in $(seq 1 120); do
        pid=$(pv_find_emulator_pid "$1") && { echo "$pid"; return 0; }
        pv_sleep 0.5
    done
    return 1
}

pv_boot_abort() { # EXIT trap while a boot or a provisioning is under way
    local i f started
    [ "${PV_BOOT_DONE:-0}" = 1 ] && return
    [ -n "${PV_BOOT_PORT:-}" ] || return
    pv_log "stopping emulator-$PV_BOOT_PORT: the boot failed or was interrupted"
    f=$PV_REG/emulator-$PV_BOOT_PORT
    if [ "$(pv_entry_get "$f" booter)" = "$$" ]; then
        started=$(pv_entry_get "$f" started)
        # Held by this boot.sh (pid 0) while its emulator exits, so that no other boot.sh takes the
        # port before the entry is removed.
        pv_locked pv_entry_reserve "$f"
        if [ -z "${PV_BOOT_PID:-}" ]; then
            # The launcher may still be starting qemu; give it a moment so that nothing is orphaned.
            for i in 1 2 3 4 5; do PV_BOOT_PID=$(pv_find_emulator_pid "$PV_BOOT_PORT") && break; pv_sleep 1; done
        fi
        pv_kill_emulator "$PV_BOOT_PORT" "${PV_BOOT_PID:-}"
        pv_locked pv_entry_remove_if "$f" "$started" "$$"
    elif [ -n "${PV_BOOT_PID:-}" ] && pv_is_emulator_pid "$PV_BOOT_PID" "$PV_BOOT_PORT"; then
        # The entry is not this boot's any more: stop this boot's own emulator, by its pid only.
        pv_kill_emulator "$PV_BOOT_PORT" "$PV_BOOT_PID"
    fi
    if [ -n "${PV_BOOT_LOG:-}" ] && [ -f "$PV_BOOT_LOG" ]; then
        pv_log "last lines of $PV_BOOT_LOG:"
        tail -n 15 "$PV_BOOT_LOG" >&2
    fi
}

pv_reserve_waiting() { # API MODE SLOT-WAIT -> prints the port
    local api=$1 mode=$2 deadline=$((SECONDS + $3)) next_note=$SECONDS port rc
    while :; do
        port=$(pv_locked pv_reserve "$api" "$mode")
        rc=$?
        case $rc in
            0) echo "$port"; return 0 ;;
            75) [ $SECONDS -lt $deadline ] || { pv_log "no device slot became free in $3 s (tools/emu/boot.sh --list)"; return 1; }
                if [ $SECONDS -ge $next_note ]; then
                    pv_log "six package devices are running; waiting for a free slot"
                    next_note=$((SECONDS + 60))
                fi ;;
            76) [ $SECONDS -lt $deadline ] || { pv_log "pvlab_api$api is still being provisioned"; return 1; }
                if [ $SECONDS -ge $next_note ]; then
                    pv_log "pvlab_api$api is being provisioned; waiting"
                    next_note=$((SECONDS + 60))
                fi ;;
            77) pv_log "the integrator's port $(pv_integrator_port "$api") is in use"; return 1 ;;
            78) pv_log "devices of pvlab_api$api are running; stop them before provisioning (tools/emu/boot.sh --list)"; return 1 ;;
            *) pv_log "could not reserve a port (code $rc)"; return 1 ;;
        esac
        pv_sleep 10
    done
}

pv_boot() { # API MODE SLOT-WAIT BOOT-TIMEOUT -> prints the serial
    local api=$1 mode=$2 port pid tmp attempt
    pv_base_check "$api" || exit 1
    find "$PV_LOGS" -maxdepth 1 -name 'emulator-*.log' -mtime +14 -delete 2>/dev/null
    port=$(pv_reserve_waiting "$api" "$mode" "$3") || exit 1
    PV_BOOT_PORT=$port
    trap pv_boot_abort EXIT
    trap 'exit 130' INT TERM HUP
    PV_BOOT_LOG=$(pv_entry_get "$PV_REG/emulator-$port" log)
    # A guest now and then hangs while booting (seen once on API 37: no "Boot completed" after
    # 12 minutes, adb shell not answering). Such a boot is stopped and started once more.
    for attempt in 1 2; do
        pv_launch "$api" "$port" "$PV_BOOT_LOG" 1
        pid=$(pv_wait_pid "$port") || { pv_log "the emulator did not start"; exit 1; }
        PV_BOOT_PID=$pid
        pv_locked pv_entry_set "$PV_REG/emulator-$port" pid "$pid" || exit 1
        pv_use "emulator-$port"
        pv_log "emulator-$port (API $api, read-only, pid $pid) is booting"
        pv_wait_booted "$4" && break
        [ $attempt = 1 ] || exit 1
        pv_log "emulator-$port did not get ready in $4 s; stopping it and booting once more"
        # Held by this boot.sh (pid 0) while the emulator exits, as at reservation, so that no other
        # boot.sh forgets the entry and takes the port in between.
        pv_locked pv_entry_reserve "$PV_REG/emulator-$port" || exit 1
        pv_kill_emulator "$port" "$pid"
        PV_BOOT_PID=""
        PV_BOOT_LOG=$PV_LOGS/emulator-$port-$(date +%Y%m%d-%H%M%S)-retry.log
        pv_locked pv_entry_set "$PV_REG/emulator-$port" log "$PV_BOOT_LOG" || exit 1
    done
    tmp=$(pv_emulator_tmpfiles "$pid")
    pv_locked pv_entry_set "$PV_REG/emulator-$port" tmpfiles "$tmp" || exit 1
    if [ "$(pv_shell settings get global animator_duration_scale | tr -d '\r')" != 0 ] \
        && [ "$(pv_shell settings get global animator_duration_scale | tr -d '\r')" != 0.0 ]; then
        pv_log "warning: animations are on; the base does not look provisioned"
    fi
    if [ "$api" = 37 ] && [ "${PV_BOOT_NETWORK:-off}" != on ]; then
        # The app needs no network (it never gets the INTERNET permission); only the Play Store's updates do.
        pv_shell cmd connectivity airplane-mode enable >/dev/null 2>&1 \
            || pv_log "warning: airplane mode could not be turned on; long runs may fill /data"
    fi
    PV_BOOT_DONE=1
    pv_log "emulator-$port is ready (API $api)"
    echo "emulator-$port" >&3   # fd 3 is the caller's stdout (pv_main)
}

# ---- provisioning ------------------------------------------------------------------------------------
pv_finger_count() { # total enrolled fingerprints, -1 if unknown
    pv_shell dumpsys fingerprint 2>/dev/null | tr -d '\r' | python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if line.startswith("{") and "\"prints\"" in line:
        print(sum(p.get("count", 0) for p in json.loads(line)["prints"]))
        break
else:
    print(-1)'
}

pv_touch_finger() { # ID -> one touch; "remove" keeps quick touches from merging into one
    pv_adb emu finger touch "$1" >/dev/null
    pv_adb emu finger remove >/dev/null
}

pv_wait_focus() { # REGEX SECONDS
    local deadline=$((SECONDS + $2))
    until pv_focus | grep -q -E "$1"; do
        [ $SECONDS -lt $deadline ] || { pv_log "waited $2 s for $1; focus is $(pv_focus)"; return 1; }
        pv_sleep 1
    done
}

pv_prov_settings() { # API
    pv_shell settings put global window_animation_scale 0 &&
        pv_shell settings put global transition_animation_scale 0 &&
        pv_shell settings put global animator_duration_scale 0 &&
        pv_shell svc power stayon true &&
        pv_shell settings put global verifier_verify_adb_installs 0 &&
        pv_shell settings put global package_verifier_enable 0 &&
        pv_shell settings put secure show_ime_with_hard_keyboard 1 || return 1
    if [ "$1" = 28 ]; then
        # Otherwise the PIN also becomes the full-disk-encryption start-up PIN.
        pv_shell settings put global require_password_to_decrypt 0 || return 1
    fi
}

pv_prov_pin_api24() { # Android 7.0 has no "locksettings" command: go through Settings.
    pv_shell am start -a android.app.action.SET_NEW_PASSWORD >/dev/null
    pv_wait_focus 'ChooseLockGeneric' 20 || return 1
    pv_ui_tap '[|]lock_pin$' || { pv_log "no PIN choice on the screen-lock screen"; return 1; }
    pv_wait_focus 'ChooseLockPassword' 20 && pv_ui_wait '[|]password_entry$' 10 || return 1
    pv_shell input text "$PV_DEVICE_PIN"
    pv_ui_wait '^CONTINUE[|]' 10 && pv_ui_tap '^CONTINUE[|]' || { pv_log "no CONTINUE"; return 1; }
    pv_ui_wait '^Confirm your PIN[|]' 15 || return 1
    pv_shell input text "$PV_DEVICE_PIN"
    pv_ui_wait '^OK[|]' 10 && pv_ui_tap '^OK[|]' || { pv_log "no OK"; return 1; }
    pv_wait_focus 'RedactionInterstitial' 20 || return 1
    pv_ui_tap '^DONE[|]' || return 1
    pv_wait_focus '[Ll]auncher|[Ss]ettings' 20
}

pv_prov_pin() { # API
    local out
    if [ "$1" = 24 ]; then pv_prov_pin_api24; return; fi
    out=$(pv_shell locksettings set-pin "$PV_DEVICE_PIN" 2>&1 | tr -d '\r')
    pv_log "locksettings set-pin: $out"
    echo "$out" | grep -q 'set to'
}

# Enrols emulator fingers 1 and 2 through Settings. The screens differ: API 37 has MORE and
# I AGREE, API 28 NEXT, API 24 "Continue" and a NEXT on "Find the sensor". Screens are found by the
# focused activity and the UI tree, never by fixed coordinates.
pv_prov_fingers() { # API
    local api=$1 finger=1 deadline=$((SECONDS + 300)) focus nodes count last_pin=-100 touches=0 i
    if [ "$api" = 24 ]; then
        pv_shell am start -a android.settings.SECURITY_SETTINGS >/dev/null
        pv_ui_wait '^Fingerprint[|]' 20 && pv_ui_tap '^Fingerprint[|]' || { pv_log "no Fingerprint entry in Security"; return 1; }
    else
        pv_shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
    fi
    pv_sleep 2
    while [ $SECONDS -lt $deadline ]; do
        focus=$(pv_focus)
        nodes=$(pv_ui_nodes)
        if printf '%s\n' "$nodes" | grep -q '|password_entry|'; then
            if [ $((SECONDS - last_pin)) -ge 6 ]; then
                pv_log "PIN screen: entering the device PIN"
                pv_shell input text "$PV_DEVICE_PIN"
                pv_shell input keyevent 66
                last_pin=$SECONDS
            fi
            pv_sleep 2
            continue
        fi
        case "$focus" in
            *FingerprintEnrollFinish*)
                count=$(pv_finger_count)
                pv_log "fingerprint added ($count enrolled)"
                # The screen's buttons can be missing from a dump taken just as it opens.
                for i in 1 2 3 4 5 6; do
                    if [ "$count" -ge 2 ]; then
                        pv_ui_tap '^(DONE|Done)[|]' "$nodes" && break
                    else
                        pv_ui_tap '^(ADD ANOTHER|Add another)[|]' "$nodes" && break
                    fi
                    pv_sleep 1
                    nodes=$(pv_ui_nodes)
                done
                [ "$i" -lt 6 ] || { pv_log "no ADD ANOTHER or DONE button on the finish screen"; return 1; }
                pv_sleep 2
                [ "$count" -ge 2 ] && return 0
                finger=$((finger + 1)) ;;
            *FingerprintEnrollEnrolling*)
                pv_touch_finger "$finger"; touches=$((touches + 1)); pv_sleep 1.2 ;;
            *FingerprintEnrollFindSensor*)
                if ! pv_ui_tap '^(NEXT|Next)[|]' "$nodes"; then
                    pv_touch_finger "$finger"; touches=$((touches + 1))
                fi
                pv_sleep 1.2 ;;
            *Keyguard*|*NotificationShade*|*StatusBar*)
                pv_unlock ;;
            *)
                pv_ui_tap '^(I AGREE|I agree|AGREE|Agree|MORE|More|NEXT|Next|CONTINUE|Continue)[|]' "$nodes" || true
                pv_sleep 1.5 ;;
        esac
        [ $touches -le 40 ] || { pv_log "too many touches without finishing"; return 1; }
    done
    pv_log "fingerprint enrolment did not finish (focus $(pv_focus))"
    return 1
}

pv_prov_verify() { # API -> prints check.*= lines; returns 1 if one failed
    local api=$1 bad=0 v a b c
    if [ "$api" = 24 ]; then
        pv_adb root >/dev/null 2>&1
        pv_sleep 3
        pv_wait_device "$PV_SERIAL" 60
        v=$(pv_shell "sqlite3 /data/system/locksettings.db \"select value from locksettings where name='lockscreen.password_type' and user=0\"" | tr -d '\r')
        if [ "$v" = 131072 ]; then echo "check.pin=PASS (lockscreen.password_type=131072, a numeric PIN; set through Settings)"
        else echo "check.pin=FAIL (lockscreen.password_type=$v)"; bad=1; fi
    else
        v=$(pv_shell locksettings verify --old "$PV_DEVICE_PIN" 2>&1 | tr -d '\r')
        if echo "$v" | grep -q 'verified successfully'; then echo "check.pin=PASS (locksettings verify --old $PV_DEVICE_PIN: $v)"
        else echo "check.pin=FAIL ($v)"; bad=1; fi
    fi
    pv_shell input keyevent KEYCODE_SLEEP
    pv_sleep 3
    pv_shell input keyevent KEYCODE_WAKEUP
    pv_sleep 2
    if pv_keyguard_showing; then
        if pv_unlock; then echo "check.lock_screen=PASS (shown after screen off and on, dismissed with $PV_DEVICE_PIN)"
        else echo "check.lock_screen=FAIL (the PIN did not dismiss it)"; bad=1; fi
    else
        echo "check.lock_screen=FAIL (no lock screen after screen off and on)"; bad=1
    fi
    v=$(pv_finger_count)
    if [ "$v" = 2 ]; then echo "check.fingers=PASS (2 enrolled: emulator finger ids 1 and 2)"
    else echo "check.fingers=FAIL ($v enrolled)"; bad=1; fi
    a=$(pv_shell settings get global window_animation_scale | tr -d '\r')
    b=$(pv_shell settings get global transition_animation_scale | tr -d '\r')
    c=$(pv_shell settings get global animator_duration_scale | tr -d '\r')
    case "$a $b $c" in
        "0 0 0"|"0.0 0.0 0.0") echo "check.animations=PASS (off: $a $b $c)" ;;
        *) echo "check.animations=FAIL ($a $b $c)"; bad=1 ;;
    esac
    v=$(pv_shell settings get global stay_on_while_plugged_in | tr -d '\r')
    case "$v" in
        ""|0|null) echo "check.stay_awake=FAIL (stay_on_while_plugged_in=$v)"; bad=1 ;;
        *) echo "check.stay_awake=PASS (stay_on_while_plugged_in=$v)" ;;
    esac
    v=$(pv_shell settings get global verifier_verify_adb_installs | tr -d '\r')
    echo "check.adb_install_verifier=$v (0: adb installs are not sent to a verifier)"
    v=$(pv_shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')
    echo "check.show_ime_with_hard_keyboard=$v"
    return $bad
}

pv_provision() { # API
    local api=$1 port pid checks build marker rel i
    pv_fetch_sysimg "$api" || pv_die "the system image for API $api is not ready"
    mkdir -p "$PV_AVD_HOME" "$PV_LOGS"
    port=$(pv_reserve_waiting "$api" provision 0) || exit 1
    PV_BOOT_PORT=$port
    trap pv_boot_abort EXIT
    trap 'exit 130' INT TERM HUP
    marker=$(pv_marker "$api")
    rm -f "$marker"
    rm -rf "$PV_AVD_HOME/pvlab_api$api.avd"
    pv_write_avd "$api" || pv_die "could not write the AVD"
    PV_BOOT_LOG=$(pv_entry_get "$PV_REG/emulator-$port" log)
    pv_log "provisioning pvlab_api$api: writable first boot on port $port"
    pv_launch "$api" "$port" "$PV_BOOT_LOG" 0 -wipe-data
    pid=$(pv_wait_pid "$port") || pv_die "the emulator did not start"
    PV_BOOT_PID=$pid
    pv_locked pv_entry_set "$PV_REG/emulator-$port" pid "$pid" || exit 1
    pv_use "emulator-$port"
    pv_wait_booted 600 || pv_die "the first boot failed"
    build=$(pv_getprop ro.build.fingerprint)
    pv_log "booted $build"
    pv_prov_settings "$api" || pv_die "could not change the settings"
    pv_log "setting the device PIN $PV_DEVICE_PIN"
    pv_prov_pin "$api" || pv_die "could not set the device PIN"
    pv_log "enrolling fingers 1 and 2"
    pv_prov_fingers "$api" || pv_die "could not enrol two fingers"
    pv_shell input keyevent KEYCODE_HOME
    checks=$(pv_prov_verify "$api")
    printf '%s\n' "$checks" >&2
    printf '%s\n' "$checks" | grep -q '=FAIL' && pv_die "provisioning checks failed"
    pv_log "powering off"
    # Held by this boot.sh (pid 0) while the device powers off and the record is written.
    pv_locked pv_entry_reserve "$PV_REG/emulator-$port" || exit 1
    pv_shell reboot -p >/dev/null 2>&1
    for i in $(seq 1 120); do pv_is_emulator_pid "$pid" "$port" || break; pv_sleep 1; done
    if pv_is_emulator_pid "$pid" "$port"; then
        pv_log "the device did not power off; syncing and stopping it"
        "$PV_ADB" -s "emulator-$port" shell sync >/dev/null 2>&1
        pv_kill_emulator "$port" "$pid"
    fi
    {
        echo "# The base device pvlab_api$api, made by tools/emu/boot.sh --provision $api."
        echo "# Boots after this one are read-only; disk= lines are path|size|mtime|sha1."
        echo "api=$api"
        echo "avd=pvlab_api$api"
        echo "provisioned=$(date -Iseconds)"
        echo "by=$(pv_owner)"
        echo "image=$(pv_sysimg_dir "$api")"
        echo "build=$build"
        echo "pin=$PV_DEVICE_PIN"
        echo "fingers=1 2"
        printf '%s\n' "$checks"
        for rel in $(pv_disk_files "$api"); do
            echo "disk=$rel|$(stat -c '%s|%Y' "$PV_AVD_HOME/$rel")|$(sha1sum "$PV_AVD_HOME/$rel" | cut -d' ' -f1)"
        done
    } > "$marker.part" && mv -f "$marker.part" "$marker"
    pv_locked pv_entry_remove_if "$PV_REG/emulator-$port" "$(pv_entry_get "$PV_REG/emulator-$port" started)" "$$"
    PV_BOOT_DONE=1
    trap - EXIT INT TERM HUP
    pv_log "pvlab_api$api is provisioned ($marker)"
}

# ---- main ----------------------------------------------------------------------------------------------
pv_main() {
    local mode=package slot_wait=1800 boot_timeout=300 api=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --list) pv_list; exit 0 ;;
            --verify-base)
                shift
                local rc=0 a
                for a in ${1:-$PV_APIS}; do pv_verify_base "$a" || rc=1; done
                exit $rc ;;
            --provision)
                [ $# -eq 2 ] || usage
                case "$2" in 24|28|37) ;; *) usage ;; esac
                pv_provision "$2"
                exit 0 ;;
            --integrator) mode=integrator ;;
            --slot-wait) [ $# -ge 2 ] || usage; slot_wait=$2; shift ;;
            --boot-timeout) [ $# -ge 2 ] || usage; boot_timeout=$2; shift ;;
            24|28|37) api=$1 ;;
            *) usage ;;
        esac
        shift
    done
    [ -n "$api" ] || usage
    # Only the serial goes to stdout, so that S=$(tools/emu/boot.sh 37) works.
    exec 3>&1 1>&2
    pv_boot "$api" "$mode" "$slot_wait" "$boot_timeout"
}

pv_main "$@"
