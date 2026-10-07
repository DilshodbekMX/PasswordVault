# Private emulators: `tools/emu/`

Every device check of this project runs on private emulators that `boot.sh` starts from a
read-only base under `/home/asus/pvlab/`. Nobody uses the user's own emulator
(`emulator-5554`, AVD `Medium_Phone_API_37.0`), its files under `~/.android/avd/`, or
`adb kill-server`. Made by build-plan package 0.2; the integrator owns `boot.sh`, `adb.sh`,
`smoke.sh` and `checks/` from wave 1 on.

## Quick use

```sh
S37=$(tools/emu/boot.sh 37)          # prints emulator-PORT once booted and unlocked
S24=$(tools/emu/boot.sh 24)
tools/emu/adb.sh -s "$S37" install -r app/build/outputs/apk/debug/app-debug.apk
tools/emu/adb.sh --connected "$S24" "$S37"   # connectedDebugAndroidTest with ANDROID_SERIAL
tools/emu/smoke.sh staging "$S37"
tools/emu/stop.sh "$S24" "$S37"      # or: tools/emu/stop.sh --mine
```

A boot takes 20 to 60 s. Several devices can boot at once, and several devices of the same
API level can run at once (each is its own read-only instance of the base).

## The scripts

| Script | What it does |
|---|---|
| `boot.sh API` | API 24 (Android 7.0), 28 (Android 9) or 37 (Android 17). Hands out a console port, starts the base with `-read-only`, waits for `sys.boot_completed` and the package manager, dismisses the lock screen with the PIN 1111, waits for the launcher, prints the serial. `--slot-wait S` (default 1800) is how long it waits for a free slot; `--boot-timeout S` (default 300). A boot that is not ready in time (a guest now and then hangs while booting) is stopped and started once more; after a second failure: exit 1 and no device, entry or overlay left behind. |
| `boot.sh --integrator API` | The integrator's fixed ports: 5580 (API 37), 5582 (API 28), 5584 (API 24). They do not count toward the six. |
| `boot.sh --list` | Every device `boot.sh` started, with its worktree, mode, pid and whether it still runs. |
| `boot.sh --verify-base [API]` | sha1 of each base disk image against the record written at provisioning. |
| `boot.sh --provision API` | Makes the base again from its system image and provisions it (below). Refuses while any device of that base runs. Only needed after `/home/asus/pvlab/` is lost or a base is damaged. |
| `stop.sh SERIAL...` | Stops the devices (`emu kill`, then TERM, then KILL), removes overlays a killed emulator left in `/tmp/android-$USER/`, forgets them. `--mine`: every device of this worktree. `--stale`: forget entries whose emulator is gone. |
| `adb.sh -s SERIAL ARGS...` | `adb -s SERIAL ARGS...` for a device of this worktree; waits up to 60 s for the device to come back when adb lost it. Also `--reboot [S]` (adb reboot, then wait for the new boot, unlock, launcher in front), `--wait-boot [S]`, `--wait`, `--unlock`, and `--step NAME [needs-debuggable] [needs-root] -- COMMAND...` for scripts in other languages. |
| `adb.sh --connected SERIAL... [-- GRADLE-ARGS]` | The connected tests (A §8.3) on exactly these devices: refuses a serial that is not this worktree's, removes the app and its test APK from each, clears the old reports, runs `:app:connectedDebugAndroidTest` with `ANDROID_SERIAL` set (through `tools/gradle.sh`; `./gradlew --no-daemon` with JAVA_HOME and ANDROID_HOME until 0.1 has made it), then checks the report of every serial. |
| `adb.sh --connected-results DIR SERIAL...` | That last check alone: a JUnit report with at least one test and no failure for each serial, and none for any other device. |
| `smoke.sh VARIANT SERIAL [--apk FILE]` | The smoke test (A §8.4). VARIANT is `debug` or `staging`; the APK defaults to `app/build/outputs/apk/VARIANT/app-VARIANT.apk`. Wave 0: checks the APK (package, debuggable flag, x86_64 code), removes an installed copy, installs, starts the launcher activity, checks that it is in front and still running after 5 s without a crash. Later waves add stages. |
| `shell/walk.sh SERIAL [--apk FILE] [--locale TAG]` | Build plan 2.5's [script] line: installs the APK afresh (default `app/build/outputs/apk/debug/app-debug.apk`) and walks every placeholder route of the app's shell by resource id (`pv.route.<Route>`, `pv.open.<Route>`, `pv.tab.*`), so it does not depend on the language: a setup step kept in a new process, setup, the PIN steps, the three tabs and everything below them, Lock now and Unlock to the same route, a rotation, Back out of the app. Every route key of `nav/Route.kt` but `Starting` must be shown and the app must not crash. `--apk app-staging.apk` also checks a back stack restored in a new process under R8 (A §7.2); `--locale TAG` (API 33 and newer) sets the app's language once on Welcome and checks that the shell's own texts change. Exit 0 when no step failed. |
| `checks/` | The integrator's integration checks, `checks/<wave>.sh`, run from the integrator's worktree (main). `checks/0.sh [jvm] [devices]` is wave 0's: the acceptance of packages 0.1 and 0.2 on the integrator's devices (`boot.sh --integrator`). `checks/1.sh` is wave 1's, with no device: the four package branches merged, `tools/check.sh all`, and a passing report for every test class of packages 1.1 to 1.4. `checks/2A.sh [jvm] [device]` is sub-wave 2A's: the six branches merged, `tools/check.sh all`, `VaultRoundTripTest` run (not skipped) and passed, every test class of packages 2.1 to 2.6 passing with at least the tests each package reported; then on the integrator's API 37 device the debug app starts (`smoke.sh debug`) and `shell/walk.sh` walks the shell's routes. |

Exit codes of all scripts: 0 success, 1 failure, 2 usage, 3 refused serial.

## Rules the scripts enforce (build plan rule 8)

- **Ports.** `boot.sh` hands out the even console ports 5556 to 5578 (the adb port is the next
  odd one) under the lock file `/home/asus/pvlab/run/ports.lock`, and lets at most six package
  devices run at once; a seventh `boot.sh` waits for a slot. A port is skipped while anything
  listens on it or on the port above it. 5554 is the user's; 5580, 5582 and 5584 are the
  integrator's. The adb server only looks for emulators on ports 5554 to 5585, so nothing
  above 5584 would work anyway.
- **Owners.** Each device is registered in `/home/asus/pvlab/run/devices/emulator-PORT` with the
  worktree whose `tools/emu/boot.sh` started it (the git top level of the script, or
  `PV_EMU_OWNER` if set). `adb.sh`, `stop.sh` and `smoke.sh` refuse (exit 3) any serial that is
  not registered to the calling worktree, and always refuse `emulator-5554`.
- **`-s` everywhere.** Every adb call names its device. Connected tests set `ANDROID_SERIAL`
  to the package's own serials, comma-separated (AGP runs on exactly those); never run
  `connectedAndroidTest` without it, because the user's emulator is usually running.
- **A green connected run is not enough.** AGP 9.4.1's test runner prints
  `AndroidTestRunner failed on emulator-N` and still ends with BUILD SUCCESSFUL (exit 0) when it
  cannot install the APK on one device. Seen on API 24 with the app already installed: it
  installs without `-r`, and Android 7 refuses that (`INSTALL_FAILED_ALREADY_EXISTS`; API 28
  and 37 replace silently). That device then has no report at all. `adb.sh --connected`
  removes the app first and fails unless every serial has a passing report.
- **adb server restarts.** Android Studio sometimes restarts the adb server, and a lab saw an
  emulator stay `offline` afterwards. `pv_adb` waits up to 60 s for the device; if it stays
  offline for 10 s it runs `adb -s SERIAL reconnect` (only that device). No script ever runs
  `adb kill-server`. This path cannot be tested by an agent, because no agent may restart the
  server; it was tested only with a device that dropped off by itself (`adb root`).
- **Stop your devices** when done: `tools/emu/stop.sh --mine`. Stopping throws away everything
  done on the device; the next boot starts again from the base. An agent that was cut off
  leaves its devices running; the next agent in the same worktree is their owner and stops them
  the same way. The integrator can stop another worktree's devices with
  `PV_EMU_OWNER=/home/asus/pvwork/<branch> tools/emu/stop.sh --mine`.
- **Crashes and reboots.** An entry whose emulator is gone is forgotten at the next
  `boot.sh` (or `stop.sh --stale`), and the overlays a killed emulator left in
  `/tmp/android-$USER/` are deleted then.

## Steps in scripts: `needs-debuggable`, `needs-root`, SKIPPED

```sh
source tools/emu/adb.sh
pv_use "$SERIAL"                       # exits 3 if the serial is not this worktree's
pv_step "app installs" -- pv_adb install -r "$APK"
pv_step "vault file starts with PVAULT01" needs-debuggable -- check_vault_header
pv_step "smaller Java heap" needs-root -- heapsim 128m 256m
pv_summary                             # SUMMARY emulator-5556: 2 passed, 0 failed, 1 skipped
```

Each step prints `PASS name`, `FAIL name (exit N)` or `SKIPPED name (reason)`, and returns
the command's status (0 when skipped). Scripts use `set -u`, not `set -e`: under `set -e` a
failing command inside `pv_step` would end the script before FAIL is printed.

`needs-debuggable` holds when `run-as` works for the app (`PV_PACKAGE`, default
`io.github.dilshodbekmx.passwordvault`): the debug build, not staging. `needs-root` holds when
the image is a userdebug build (`ro.debuggable=1`): API 24 and 28 here; the API 37 image is a
user build without root. A step whose marker does not hold is not run and prints SKIPPED.
Other helpers: `pv_shell`, `pv_reboot`, `pv_wait_booted`, `pv_unlock`, `pv_keyguard_showing`,
`pv_focus`, `pv_ui_nodes`, `pv_ui_tap REGEX`, `pv_ui_wait REGEX SECONDS` (REGEX is matched
against `text|content-desc|resource-id`; write a literal `|` as `[|]`), `pv_is_debuggable`,
`pv_has_root`, `pv_api`.

A wedged guest can leave `adb shell` waiting for ever. Set `PV_ADB_TIMEOUT=N` and every
`pv_adb` call is killed after N seconds (exit 124); the waiting helpers (`pv_wait_booted`,
`pv_reboot`, `pv_wait_device`) always bound their calls, and `smoke.sh` gives each call 180 s.
Leave it unset around commands that may legitimately run long (`am instrument`, `logcat`
without `-d`).

## The base: `/home/asus/pvlab/`

```
dl/            x86_64-24_r08.zip, x86_64-28_r04.zip, expected.sha1, sys-img2-5.xml (Google's
               index, which lists the same sha1 sums), sysimg-37.sha1 (sha1 of every file of
               the API 37 image)
sysimg/        android-24/default/x86_64/, android-28/default/x86_64/ (unpacked from the zips),
               android-37.0/google_apis_playstore/x86_64/ (copy of the SDK's image, revision 6)
avd/           pvlab_api{24,28,37}.ini, pvlab_api{24,28,37}.avd/, and
               pvlab_api{24,28,37}.provisioned (what was set, the checks, and the size, time
               and sha1 of each disk image)
run/           ports.lock, devices/ (the registry), logs/ (one emulator log per boot)
```

| Base | Image | Build | Screen |
|---|---|---|---|
| `pvlab_api24` | AOSP `x86_64-24_r08.zip`, sha1 `f6559e1949a5879f31a9662f4f0e50ad60181684` | `sdk_phone_x86_64-userdebug 7.0 NYC 4174735` | 1080x1920, 420 dpi |
| `pvlab_api28` | AOSP `x86_64-28_r04.zip`, sha1 `d47a85c8f4e9fd57df97814ad8884eeb0f3a0ef0` | `sdk_phone_x86_64-userdebug 9 PSR1.180720.012` | 1080x1920, 420 dpi |
| `pvlab_api37` | Google Play image `android-37.0` revision 6 | `sdk_gphone64_x86_64 17 CE2A.260420.019 user` | 1080x2400, 420 dpi |

The sha1 sums are those of `docs/lab/old-android.md` §0, and Google's index
(`dl/sys-img2-5.xml`) gives the same. The AVD configs are modelled on a copy of the user's AVD
config (medium_phone, 420 dpi, hardware keyboard) with 4 cores, swiftshader, no audio, no
cameras, cold boot and a 6 GB data partition.

**Provisioned once** by `boot.sh --provision API`, which boots the base writable from a wiped
data partition, then:

- device PIN **1111** (`locksettings set-pin` on API 28 and 37; through Settings on API 24,
  which has no `locksettings`). On API 28, `require_password_to_decrypt` is set to 0 first, so
  the PIN does not also become the start-up password of full-disk encryption;
- **two enrolled fingers**, emulator finger ids **1 and 2** (`adb emu finger touch 1`);
  any other id is "not recognized";
- animations off (the three scales 0), stay awake (`svc power stayon true`);
- adb installs not sent to a verifier (`verifier_verify_adb_installs 0`,
  `package_verifier_enable 0`), the on-screen keyboard shown despite the hardware keyboard
  (`show_ime_with_hard_keyboard 1`);

checks all of it (`check.*` lines in the `.provisioned` file), powers off, and records each disk
image. Every later boot is read-only: the emulator keeps the device's changes in
`/tmp/android-$USER/emulator-*.qcow2` and drops them when it exits, so every boot starts from
the provisioned state, and several boots of one base can run at once. Before each boot,
`boot.sh` checks that the size and time of every disk image are still the recorded ones and
refuses to boot a base that changed. (The image files cannot be made read-only on disk: the
emulator crashes when it cannot open them for writing, even with `-read-only`.)

## Checking package 0.2 again (the wave-0 integration check)

```sh
S24=$(tools/emu/boot.sh 24); S28=$(tools/emu/boot.sh 28); S37=$(tools/emu/boot.sh 37)
tools/emu/boot.sh --list                          # three ports from 5556..5578, -read-only
for s in $S24 $S28 $S37; do tools/emu/adb.sh -s $s install -r app/build/outputs/apk/debug/app-debug.apk; done
SX=$(tools/emu/boot.sh 37)                        # another emulator running
tools/emu/adb.sh --connected $S24 $S28 $S37       # every connected test (since wave 2B: nav.*, platform.*, vectors.*, window.*, ui.common.*, i18n.*) on exactly those three
tools/emu/adb.sh -s $SX shell pm list packages | grep -c passwordvault   # 0
tools/emu/smoke.sh staging $S24; tools/emu/smoke.sh staging $S37
tools/emu/stop.sh --mine; tools/emu/boot.sh --verify-base
```

The user's AVD: list `~/.android/avd/Medium_Phone.avd` with sizes and times before and after.
While the user's emulator runs, it changes its own `userdata-qemu.img.qcow2` and `tmpAdbCmds`;
nothing in `tools/emu` names that directory, and no emulator started here opens it (each log
says `Found systemPath /home/asus/pvlab/sysimg/...`).

## Device facts worth knowing

- The lock screen is up after every boot; `boot.sh` dismisses it. After `KEYCODE_SLEEP` it is
  back: `tools/emu/adb.sh -s SERIAL --unlock` (or `pv_unlock`).
- A reboot of the guest keeps everything done on the device (an installed app, its files):
  the read-only overlay lives as long as the emulator process, so checks such as lock.md E3
  can reboot with `tools/emu/adb.sh -s SERIAL --reboot` (or `pv_reboot`), which waits for a new
  boot id, the boot to complete and the lock screen to be dismissed. Tried with `--reboot` on
  API 24 and 37, and with `adb reboot` then `--wait-boot` on API 28, which comes back without a
  start-up PIN screen.
- Fingerprint: `adb -s SERIAL emu finger touch 1` then `emu finger remove`; touches sent
  together are merged into one (docs/lab/biometric.md). The Android 7 and 9 images never change
  their sensor's authenticator id, so a new finger does not invalidate keys there.
- Root: `adb root` works on API 24 and 28 (userdebug), not on API 37.
- Lab scripts in `docs/lab/biometric-artifacts/` (`enroll_fingerprint.sh`, `uitap.py`, ...)
  take a console PORT and call adb with `-s` themselves, so they skip the checks of `adb.sh`.
  `enroll_fingerprint.sh` knows only the API 37 Settings screens (on API 28 it waits at the
  NEXT button until it times out); `boot.sh --provision` has its own enrolment for all three.
- Secure windows (staging and release builds) give black `screencap` output; use
  `adb -s SERIAL emu screenrecord screenshot DIR`.
- A device test meant for some API levels only is filtered with `@SdkSuppress(minSdkVersion = …)`
  (`androidx.test.filters`), never `org.junit.Assume`: AGP 9.4.1 writes a test that ends in
  `AssumptionViolatedException` into the JUnit XML as a failure, so `adb.sh --connected` fails that
  device (seen on API 24 and 28 on 2026-10-07). A suppressed test is left out of the report.
- Connected tests by hand: `ANDROID_SERIAL=emulator-5556,emulator-5558 tools/gradle.sh
  :app:connectedDebugAndroidTest`, then `tools/emu/adb.sh --connected-results
  app/build/outputs/androidTest-results/connected/debug emulator-5556 emulator-5558`
  (remove the app from an API 24 device first). `adb.sh --connected` does all of it.
