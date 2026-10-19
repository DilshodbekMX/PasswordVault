# Private emulators: `tools/emu/`

Every device check of this project runs on private emulators that `boot.sh` starts from a
read-only base under `/home/asus/pvlab/`. Nobody uses the user's own emulator
(`emulator-5554`, AVD `Medium_Phone_API_37.0`), its files under `~/.android/avd/`, or
`adb kill-server`. Made by build-plan package 0.2; the integrator owns `boot.sh`, `adb.sh`,
`app.sh`, `uidump/`, `smoke.sh` and `checks/` from wave 1 on.

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
| `boot.sh API` | API 24 (Android 7.0), 28 (Android 9) or 37 (Android 17). Hands out a console port, starts the base with `-read-only`, waits for `sys.boot_completed` and the package manager, dismisses the lock screen with the PIN 1111, waits for the launcher, puts an API 37 device in airplane mode (below, "Device facts"; `PV_BOOT_NETWORK=on` keeps the network), prints the serial. `--slot-wait S` (default 1800) is how long it waits for a free slot; `--boot-timeout S` (default 300). A boot that is not ready in time (a guest now and then hangs while booting) is stopped and started once more; after a second failure: exit 1 and no device, entry or overlay left behind. |
| `boot.sh --integrator API` | The integrator's fixed ports: 5580 (API 37), 5582 (API 28), 5584 (API 24). They do not count toward the six. |
| `boot.sh --list` | Every device `boot.sh` started, with its worktree, mode, pid and whether it still runs. |
| `boot.sh --verify-base [API]` | sha1 of each base disk image against the record written at provisioning. |
| `boot.sh --provision API` | Makes the base again from its system image and provisions it (below). Refuses while any device of that base runs. Only needed after `/home/asus/pvlab/` is lost or a base is damaged. |
| `stop.sh SERIAL...` | Stops the devices (`emu kill`, then TERM, then KILL), removes overlays a killed emulator left in `/tmp/android-$USER/`, forgets them. `--mine`: every device of this worktree. `--stale`: forget entries whose emulator is gone. |
| `adb.sh -s SERIAL ARGS...` | `adb -s SERIAL ARGS...` for a device of this worktree; waits up to 60 s for the device to come back when adb lost it. Also `--reboot [S]` (adb reboot, then wait for the new boot, unlock, launcher in front), `--wait-boot [S]`, `--wait`, `--unlock`, and `--step NAME [needs-debuggable] [needs-root] -- COMMAND...` for scripts in other languages. |
| `adb.sh --connected SERIAL... [-- GRADLE-ARGS]` | The connected tests (A §8.3) on exactly these devices: refuses a serial that is not this worktree's, removes the app and its test APK from each, clears the old reports, runs `:app:connectedDebugAndroidTest` with `ANDROID_SERIAL` set (through `tools/gradle.sh`; `./gradlew --no-daemon` with JAVA_HOME and ANDROID_HOME until 0.1 has made it), then checks the report of every serial. An install of AGP's that never ends (API 24, below) is stopped after 120 s and the tests are run again, up to three times. |
| `adb.sh --connected-results DIR SERIAL...` | That last check alone: a JUnit report with at least one test and no failure for each serial, and none for any other device. |
| `app.sh` (a library) | Helpers that drive the app's own screens by resource id, for `smoke.sh`, `checks/` and `shell/walk.sh`: `source tools/emu/adb.sh; source tools/emu/app.sh; pv_use SERIAL; pva_init`. `pva_on ROUTE`, `pva_present ID`, `pva_need ID`, `pva_absent ID`, `pva_text ID`, `pva_reveal ID` and `pva_tap ID` (both scroll to a node below the fold; `pva_tap` closes the keyboard before a tap in the lower two thirds of the screen), `pva_type ID TEXT`, `pva_submit BUTTON THEN` (keyboard closed, button tapped), `pva_keyboard_shown`, `pva_wait_keyboard`, `pva_hide_keyboard`, `pva_rotate 0\|1`, `pva_screen_off`, `pva_away SECONDS`, `pva_kill_in_background`, `pva_install APK` (three tries), `pva_start`, `pva_relaunch`, `pva_set_up` (setup with the suggested words, which become `PVA_PASSWORD`), `pva_unlock [ROUTE]`, `pva_submit_open BUTTON ROUTE` (setup's Next or the lock screen's Unlock, then the app open on ROUTE), `pva_unlocked ROUTE` and `pva_settle ROUTE` (since the merge of round R3 the lock's "Choose a PIN" opens 4.3's PIN steps over the app after setup and after every master-password unlock without a PIN, and "Make a backup now?" may follow: `pva_settle` answers them "Not now", "Finish" and "Later", so the PIN stays off and the lock screen stays the master-password screen; `pva_set_up`, `pva_unlock` and `pva_submit_open` call it), `pva_pin DIGITS` (the pad's keys `pv.pin.key.N`), `pva_pin_unlock ROUTE PIN`; since the device check of 4A the real lock's helpers: `pva_status` (the debug command `status`, one item per line), `pva_status_has REGEX`, `pva_status_lacks REGEX`, `pva_status_soon REGEX [S]`, `pva_prompt_open USE [S]` and `pva_no_prompt [S]` (the lock's own word on its fingerprint prompt), `pva_prompt_launch`, `pva_finger N` (2 s, then `emu finger touch N` and `remove`), `pva_prompt_use_pin` (the prompt's "Use PIN", also the library's "USE PIN" dialog on API 24), `pva_choose_pin PIN` (CreatePin, ConfirmPin), `pva_after_pin use\|notNow [ROUTE]` (the fingerprint offer, "Make backups", then ROUTE settled), `pva_to_create_pin` (past the notices to "Choose a PIN"), `pva_opened [S]` (open, and the status says unlocked), `pva_lock_now`; `pva_add_account NAME LOGIN PASSWORD WEBSITE NOTE`, `pva_row_titles`, `pva_row_with TEXT`, `pva_debug COMMAND` (a debug command through `am broadcast`), `pva_tip_ok` (closes the first-copy tip of build plan 7.3, which opens once per install right after the first copy of a password and hides every other node until it is closed: call it after a copy that may be the first), `pva_screenshot FILE`, `pva_no_crash`; since the device check of R4 the system's picker, which backup, export, import and restore open (`checks/5.sh`, `smoke.sh`): `pva_picker_wait`, `pva_picker_save` (Download, SAVE; tried again while the picker stays in front), `pva_picker_open NAME [pushed]` (a file the picker saved in Downloads, or one adb pushed to Download), `pva_picker_root`, `pva_picker_drawer`, `pva_picker_list_view`, `pva_picker_keys_open`, `pva_push_download FILE [NAME]` (and the media scan from API 30), `pva_in_download REGEX`. Sourced again (as `window/lib.sh` does), it keeps the screen size `pva_init` read. |
| `uidump/PvUiDump.java` | The dumper behind `pv_ui_nodes`: the active window's nodes as `uiautomator dump` reads them, without its wait for an idle window (below, "Device facts"). With the argument `all` (`pv_ui_nodes_all`) it reads every window a user can touch, the top one first, and adds each node's bounds (`...|x|y|left|top|right|bottom`): the status bar, a dialog, the popups of a text selection. `adb.sh` builds it once per worktree into `build/emu/pv-uidump.jar` (the JDK's javac, the SDK's d8) and pushes it to `/data/local/tmp/` on a device when needed. |
| `smoke.sh VARIANT SERIAL [--apk FILE]` | The smoke test (A §8.4). VARIANT is `debug` or `staging`; the APK defaults to `app/build/outputs/apk/VARIANT/app-VARIANT.apk`. Wave 0: checks the APK (package, debuggable flag, x86_64 code), removes an installed copy, installs, starts the launcher activity, checks that it is in front and still running after 5 s without a crash. Sub-wave 3A (the skeleton): sets up a vault with the suggested words, adds an account (its screen is shown after Save), turns the screen (the same screen, no lock), kills the app in the background (`am kill`; `kill -9` through run-as on debug builds or `su` on the userdebug images where `am kill` is refused, API 24; SKIPPED when nothing can end it) and starts it again: locked, and the master password brings the same account's screen back (the saved back stack, under R8 in staging); then Back, Lock now, and the master password opens the list with the account. Sub-wave 3B: opens the account from the list (its password drawn as dots and in no node), shows the password, copies it (read from outside the app with `clipboard/lib.sh`: the sensitive flag, a 32-digit token, the password; the status row is shown), and with the app in the background the clip is still there 4 s before its time and gone 4 s after it at the latest (setup's 1-minute delay; the time is the first reading of the clip plus the delay). Sub-wave 4A: back in front the app is locked; the master password opens it and "Choose a PIN" follows: a PIN, the fingerprint offer "Use" with finger 1 on the prompt; the lock's status has both slots and their keys; Lock now: the prompt opens by itself (on API 24 androidx.biometric's own dialog in the activity) and finger 1 opens the list; Lock now, "Use PIN": the PIN opens it. Wave 5 (since the device check of R4, build-plan 7 line 8): through the system's picker a backup into Download, the CSV export (it holds the account), an import of a small CSV pushed with adb (its two accounts in the list, the file deleted), and from the lock screen "Forgot master password?" > "Restore from a backup file" with that backup: the list is the backup's again. Later waves add stages. |
| `shell/walk.sh SERIAL [--apk FILE] [--locale TAG]` | Build plan 2.5's [script] line: installs the APK afresh (default `app/build/outputs/apk/debug/app-debug.apk`) and walks every route of the app's shell by resource id (`pv.route.<Route>`, `pv.open.<Route>`, `pv.tab.*`, and since sub-wave 3A the ids of the real setup, lock, list and form screens through `app.sh`), so it does not depend on the language: a setup step left by a new process (setup starts again at step 1 with "Setup was interrupted"), setup with the suggested words typed again, an account added through the form with its screen, big letters and its form, an account deleted through its row's menu and Recently deleted, the three tabs and everything below them, Lock now and the master password to the same route, a rotation, Back out of the app. Since the merge of round R3: the PIN steps that "Choose a PIN" opens after setup (a PIN chosen, the fingerprint offer and "Make backups" passed), the real Settings rows (the dialog "Appearance", the Help rows), and the PIN, not the master password, after Lock now. Since the merge of round R4 no placeholder is left: the language dialog of 6.1 on Welcome and in Settings (found by its rows), two accounts with one password and the Security tab's reuse card, its list (Group) and a row's guided change (7.1, 7.2), 5.5's Change master password with "I don't remember it", and 7.3's help pages (`pv.help.<PAGE>`). Every route key of `nav/Route.kt` must be shown except `Starting`, `AppLock` (no screen opens it), `OldPasswords` (only while a vault is put aside) and `ColumnMapping` (only for a picked file without a password column), and the app must not crash. `--apk app-staging.apk` runs it under R8; `--locale TAG` (API 33 and newer) sets the app's language once on Welcome and checks that the shell's own texts change. Exit 0 when no step failed. |
| `session/autolock.sh SERIAL [--apk FILE]` | Build plan 3.2's [script] line. Installs the APK afresh and sets up a vault through the setup and lock screens, found by resource id. Then it checks: the 30-second default in `pv_settings` (run-as; SKIPPED on staging); a rotation recreates the activity and does not lock; away 10 s does not lock; away 31 s locks; the screen off for 3 s locks at once; the screen off while the app is away locks (only the receiver can see it); and, on debug builds with Immediately set, a rotation does not lock while leaving does. It needs the real vault port (3.1 merged). Exit 0 when no step failed. |
| `setup/create.sh SERIAL [--apk FILE] [--vault-file]` | Build plan 3.3's [script] line. On a fresh install it creates a vault through the setup screens with the suggested words, then after `pm clear` with an own password (a weak one refused, the reuse question, a mismatch). It works by resource id only and reads only fresh uiautomator dumps. `--vault-file` also checks `files/vaults/live/vault-0000000001.pvault` with run-as (SKIPPED on staging). Exit 0 when no step failed. |
| `clipboard/clear.sh SERIAL [--apk FILE] [--only CASE[,CASE...]]` | Build plan 3.8's [script] line (A D-14, D-25; U §6). Installs the APK afresh, sets up a vault and adds an account; on a debug build it sets the clipboard delay to 30 s in `pv_settings` with run-as (staging keeps setup's 1 minute). It copies the password with the list row's copy button and watches the clipboard from outside the app as the shell user. The cases, in order: `copy` (no toast from before the tap until 4 s after the clip appears, against a control toast of the shell's; the sensitive flag, a 32-digit token and an empty label; the status row; the token and deadline in `pv_settings`; the alarm, exact below API 31), `background`, `kill` (`am kill`, or `run-as kill -9` on API 24, debug only), `swipe` (the task swiped from Recents), `screenoff` (the lock at screen-off keeps the clip; the clear comes with the screen off and the phone locked), `forcestop` (the clip is still there after its time; bringing the app to the front clears it), `other` (a later copy of another app survives on API 24 and 28, and is cleared too from Android 10), `infront`, `return`, `recopy`, `clearnow`, `locknow`, `entry` (on an account's screen the row stands above the system's bar), `restricted` (API 28 and later, last: battery use Restricted with a 2-minute delay; the clip is gone at the latest once the app is in front). Steps that need run-as print SKIPPED on staging, where the planned clear is estimated from the first reading of the clip. Exit 0 when no step failed; about 16 minutes on a debug build. |
| `clipboard/lib.sh` (a library) and `clipboard/PvClip.java` | The clipboard as another app sees it, for any script that copies in the app (`source tools/emu/adb.sh; source tools/emu/clipboard/lib.sh; pv_use SERIAL`): `pvc_describe` (the clip's description only, with the device's clocks; no "pasted" notice), `pvc_get` (the same with the text), `pvc_field KEY`, `pvc_set TEXT`, `pvc_clear`, `pvc_toast TEXT`, `pvc_toasts`, `pvc_watch_toasts SECONDS [STOPFILE]`, `pvc_alarm` (the app's clear alarm as `dumpsys alarm` lists it). PvClip runs as the shell user through `app_process`, which may read the clipboard on every API level; it is built once per worktree into `build/emu/pv-clip.jar` (the JDK's javac, the SDK's d8) and pushed when needed, as `uidump/` is. |
| `window/all.sh [--apk FILE]... [--only NAME[,NAME...]] SERIAL...` | Build plan 3.12's [script] lines (A §8.4 "Windows and input"), run at every integration check from 3B on as `tools/emu/window/all.sh S24 S28 S37`. The APKs default to `app/build/outputs/apk/debug/app-debug.apk` and `.../staging/app-staging.apk`. On API 26 and newer it runs `keyboard`, `autofill`, `overlay`, `state` and `a11y` (or the `--only` names, which may also be `capture`); on an older device only `a11y-old`. The devices run at the same time, the scripts on one device one after another: about 11 minutes per device and APK on API 28 and 37, under 3 on API 24. Prints each script's output as one block, one line per script, then `SUMMARY window checks`; exit 0 when every script passed; logs under `build/window/all/`. |
| `window/keyboard.sh`, `autofill.sh`, `overlay.sh`, `state.sh`, `a11y.sh` (each `SERIAL [--apk FILE]`, API 26 and newer) | One check each against the real setup, lock and edit screens (since the device check of 4A `keyboard.sh` also the master-password fields of "Set a PIN" and of the backup, and `state.sh` the master password and five PIN digits typed in "Set a PIN"), PASS or FAIL per step, each with its own control on the helper app's platform fields: the keyboard's flags for every field; no fill and no save request (counting only the autofill sessions that began during the run: `dumpsys autofill` keeps older ones); taps through another app's window dropped; nothing typed in the saved-instance state; and in `a11y.sh` no accessibility service (one that is not a tool, one that is, uiautomator) reads a character of a field drawn as dots, the field still reads as a password of as many mask characters, and a screen reader's actions on it work. `KNOWN` marks a secret shown on the screen that a service reads before Android 14 (lock.md §2.3); `PV_A11Y_BEFORE_34=fail` makes it a failure. Records under `build/window/SERIAL/`. |
| `window/a11y-old.sh SERIAL [--apk FILE]` | The masked fields on Android 7 (API 24 and 25), where the helper app cannot run: the node through `uiautomator dump` (its text and its password flag) and the events through `uiautomator events`. |
| `window/capture.sh SERIAL [--apk FILE]` | Content capture (API 29 and newer; every step SKIPPED on API 28), not in `all.sh`'s default list. It sets `hidden_api_policy` to 1 and `device_config content_capture service_explicitly_enabled` to true for its service and puts both back. A step prints SKIPPED when the service got no session of the app, which is the case on these images (below). |
| `window/helper/` | The helper app (`io.github.dilshodbekmx.pvlab.window`, minSdk 26, Java only) as its own Gradle build: the lab's autofill, accessibility (a tool and a non-tool) and content-capture services, the overlay, the saved-state probe (an instrumentation in the app's process) and control screens with platform fields. The scripts build it with `tools/gradle.sh -p tools/emu/window/helper assembleDebug` when it is missing or older than its sources, install it, and put back every device setting they change, except `accelerometer_rotation`, which `app.sh`'s `pva_start` leaves at 0 in every script that uses it. |
| `lock/` | Build plan 4.5: lock.md E1 to E17 as `lock/E1.sh` … `lock/E17.sh` (each `SERIAL [--apk FILE]`), `lock/all.sh` (every check on the levels lock.md gives, or `--only E1,E6 --levels 24,37 --staging SERIAL...`), the library `lock/lib.sh` (`pvl_*`: the debug command `status` read item by item, the pad, the prompt, the PIN steps, the records), `lock/newmp.sh` (a new master password asked for and finished, for E4 and E5), `lock/phone.sh`, `lock/heapsim.sh` (E16's small heap), `lock/lab.sh` and the fingerprint scripts. Its own README, `tools/emu/lock/README.md`, lists what each check does, where it runs, how long it takes, and the runs so far. Steps that need 5.5 or 6.1 (`pvl_later`) run when the APK has their strings; before, they print SKIPPED. |
| `i18n/matrix.sh SERIAL [--apk FILE] [--shots DIR] [PART...]` | Build plan 6.1's [script] line: the device matrix of i18n.md §5 with the debug build, in parts `phone` (the phone's languages set as Settings sets them, the app on "System language"), `switch` (the in-app switch to each language and back, and the pseudo-locales; the same process; where Android keeps the choice), `survives` (force-stop and a reboot), `page` (API 33 and later: Settings › Apps › PasswordVault › Language; Ўзбекча is never marked there, a KNOWN line), `mixing` (API 33 and later: two phone languages, one language on the screen), `formats` (dates against the phone's own ICU) and `prompt` (the fingerprint prompt's texts in the app's language). Without PART the parts of the device's level run. Turns airplane mode on. |
| `i18n/e11.sh SERIAL [--apk FILE] [--shots DIR]` | lock.md E11's language step (build plan 6.1): with the lock delay "Immediately", a language change in Settings keeps the app open on Settings (the same process, the row where it was) and the list re-sorts (Почта before Bankomat in Russian; from API 28 Zebra before Shahar in Oʻzbekcha). |
| `i18n/all.sh [--apk FILE] SERIAL...` | `matrix.sh` then `e11.sh` on each device, the devices at once: about 25 minutes per device. `i18n/lib.sh` (a library; its names start with `pvi_` and `PVI_`, since the device check of R4, so that they cannot be taken for `lock/lib.sh`'s `pvl_*`) with `i18n/PvLocale.java` sets the phone's languages from the shell as Settings does, reads the phone's ICU dates and the app's locales (`cmd locale`), and drives the language dialog. |
| `change/guided.sh SERIAL [--apk FILE] [--test-apk FILE] [--shots DIR] [--retries N]` | Build plan 7.2's [script] line (ux.md §5.17, §12 check 5). On a fresh install the test APK's `GuidedChangeSeed` (`-e pvChangeSeed PASSWORD`) makes a vault and four accounts with fixed ids, then the test APK is removed; the first unlock answers "Choose a PIN" with a PIN, and later unlocks use it. Gmail's change is opened from the Security tab (7.1's reuse card and group list, `pv.security.card.reused.open`, `pv.group.action.<id>`) and checked after a lock and after `am force-stop` at every step, with both passwords copied from the change, the list row's menu and the account's rows and read through `clipboard/lib.sh`. Pictures go to DIR as `7.2-NN-*.png`. It needs `:app:assembleDebugAndroidTest` and takes about 35 minutes. |
| `layout/walk.sh SERIAL [--apk FILE] [--test-apk FILE] [--locales TAG,TAG] [--parts app,states] [--shots DIR] [--width DP] [--font SCALE] [--style NAME]` | Build plan 6.2's [script] line (i18n.md §4.5, §5; ux.md §8): every screen at 320 dp wide and font size 200 %, in `en-XA` and `ar-XB`. It needs the debug build and its test APK (`tools/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest`; the defaults are this worktree's). It sets the density so that the phone is 320 dp wide (`wm density`) and the font scale to 2.0, and puts both back however it ends, with the app's language (`cmd locale set-app-locales` on API 33 and later) and its data, which it leaves cleared. Part `app` walks the real app once per locale from a fresh install (the device test `ui.layout.LayoutWalkTest`, `am instrument -e pvLayout RUN -e pvLayoutLocale TAG`; without `-e pvLayout` the test returns at once, so a connected run counts it as one passing test); part `states` draws the states the real app reaches only with time or a damaged file through each area's pseudo-locale device test (`LayoutHook`), once. `LayoutProbe` checks each screen as it opens and after each stretch that scrolls, and the script prints PASS or FAIL per screen and check (no clipped button, no truncated or clipped text, no text left in plain English, no control under 48 dp), FAIL for a walk step that could not be done or a state test that failed, then `SUMMARY SERIAL: N passed, N failed, N skipped`; exit 0 when nothing failed. `--width 411 --font 1.0` walks a 1080-pixel phone at its own size, `--style EMERALD` or `NIGHT_VAULT` the part `app` in that style, `--locales en` in plain English for pictures (the English check runs only in a pseudo-locale). About 15 minutes per device for both parts; logs and reports under `build/emu-layout/SERIAL/`, pictures under `build/emu-layout/SERIAL/<run>/`. Details and the runs so far: `tools/emu/layout/README.md`. |
| `layout/screens.py SHOTS OUT` | Makes `docs/screens/` (OUT) from a walk's pictures (SHOTS, `build/emu-layout/SERIAL`): one picture per screen in `en-XA` and those that differ right to left in `ar-XB`, scaled to a third and 128 colours (Pillow). `docs/screens/README.md`, which lists them, is written by hand. |
| `checks/` | The integrator's integration checks, `checks/<wave>.sh`, run from the integrator's worktree (main). `checks/0.sh [jvm] [devices]` is wave 0's: the acceptance of packages 0.1 and 0.2 on the integrator's devices (`boot.sh --integrator`). `checks/1.sh` is wave 1's, with no device: the four package branches merged, `tools/check.sh all`, and a passing report for every test class of packages 1.1 to 1.4. `checks/2A.sh [jvm] [device]` is sub-wave 2A's: the six branches merged, `tools/check.sh all`, `VaultRoundTripTest` run (not skipped) and passed, every test class of packages 2.1 to 2.6 passing with at least the tests each package reported; then on the integrator's API 37 device the debug app starts (`smoke.sh debug`) and `shell/walk.sh` walks the shell's routes. `checks/2B.sh [jvm] [device]` is sub-wave 2B's, which closes wave 2: the six branches merged, `tools/check.sh all`, every JVM test class of packages 2.7 and 2.9 to 2.12 passing with at least the tests each package reported; then on the integrator's API 24, 28 and 37 devices, booted together, `adb.sh --connected` and every device-test class of 2.5, 2.7, 2.8 and 2.9 passing on each device, `smoke.sh debug` on all three, the `locale` debug command through `am broadcast` on API 37, `shell/walk.sh` on API 24 and 37, and `smoke.sh staging` on API 24 and 37. `checks/3A.sh [jvm] [device] [--shots DIR]` is sub-wave 3A's, the skeleton check of build-plan.md §5: the six branches merged, `tools/check.sh all`, every JVM test class of packages 3.1 to 3.6 passing with at least the tests each package reported; then on the integrator's three devices `adb.sh --connected` with every device-test class of 2.5, 2.7 to 2.9 and 3.3 to 3.6 passing on each, and the skeleton steps 1 to 9 with the debug build on API 24, 28 and 37 (at once) and with the staging build on API 24 and 37 (at once), the screenshots of steps 1, 2 and 4 on API 37 saved to DIR (default `/home/asus/pvwork/skeleton-shots`). `checks/3A.sh skeleton VARIANT SERIAL [--apk FILE] [--shots DIR]` runs the steps on one device: set up; add an account; the vault files (run-as, SKIPPED on staging); screen off, a wrong and the right master password; force-stop; away for 31 s; rotations on the lock screen and on the form; a generated password kept as a draft across a lock (U §12 check 4); `locale ru` and back with a Cyrillic title added by the debug command `entry add`; `smoke.sh`. `checks/3B.sh [PART...] [--shots DIR] [--keep]` is sub-wave 3B's device check (build-plan.md §12, step 2; the merge ran `tools/check.sh all`), on the integrator's three devices (those of this worktree that run are used, the others booted; stopped at the end unless `--keep`): `merged` (the seven R1 branches in HEAD), `connected` (`adb.sh --connected`; a class that failed runs once more alone on that device and counts as a flake when it passes; then every device-test class of 2.5, 2.7 to 2.9, 3.3 to 3.9 and w3B-carry with its tests on each API level), `skeleton` (`checks/3A.sh skeleton`, debug on 24, 28 and 37, staging on 24 and 37), `clip` (`checks/3B.sh clip SERIAL`, debug, on the three at once: the account's password hidden, shown and in big letters; Copy flagged with a token, cleared 30 s later as planned, the status row counting down in screenshots on the account's screen and the list; the clip gone on time in the background; a note's word and the Add field dialog's value copied with the floating toolbar's Copy, flagged too; screenshots on API 37 in DIR, default `/home/asus/pvwork/shots-3B`), `smoke` (`smoke.sh staging` on 24 and 37), `secure` (`checks/3B.sh secure SERIAL`, staging, on the three: FLAG_SECURE on the app's window, `screencap` gives nothing of the app, the emulator's own screenshot does), `window` (`window/all.sh` on the three) and `extras` (`clipboard/clear.sh`, `shell/walk.sh`, `setup/create.sh --vault-file` and `session/autolock.sh` with the debug build on the three, `walk.sh` with the staging build on 37). Logs in `build/emu-checks/3B/`. `checks/3C.sh [PART...] [--shots DIR] [--keep]` is the device check of round R2 (sub-wave 3C and package 5.1; build-plan.md §12), on the same three devices: `merged` (the heads that R2 merged are in HEAD, those of `w4A-4.2` and `w4A-4.3` are not), `files` (package 5.1 on API 28, where its tests had not run: the platform's MIME table, read on each device with `checks/PvMimeTable.java` as the shell user, is what formats.md §2 rule 4 says, and with it a provider keeps the suggested names; then `adb.sh --connected` with the runner's package argument: the seven classes of `APP-AT/files/`, 50 tests), `connected` (as 3B's, with 5.1's classes in the table: 358 tests on API 24, 359 on 28, 363 on 37; a class that failed runs once more alone, one run per class), `skeleton` (`checks/3A.sh skeleton`, debug on 24, 28 and 37, staging on 24 and 37, each from a fresh install on a device that ran the connected tests; the test APK is removed first should a run have left it; screenshots of steps 1, 2 and 4 on API 37 in DIR, default `/home/asus/pvwork/shots-3C`), `smoke` (`smoke.sh staging` on 24 and 37) and `window` (`window/all.sh` on the three, as A §8.4 asks of every integration check from 3B on). After each part that drives the app it prints a NOTE for every Run that Android Studio started on one of the three devices meanwhile (from Studio's `idea.log`). Logs in `build/emu-checks/3C/`. `checks/4A.sh [PART...] [--shots DIR] [--keep]` is the device check of round R3 (sub-wave 4A with 5.2 to 5.4; build-plan.md §6 "Integration check for 4A" and §12), on the same three devices: `merged`, `connected` (469, 470 and 477 tests at `wave-4A-merged`, counted from the sources with their `@SdkSuppress` filters; a failed class once more alone), `lock` (the plan's line: set up with a PIN and a fingerprint, unlock with each, five wrong PINs, the master password and a new PIN; debug on the three), `e` (lock.md E1 to E3, E6 and E11 without its language step, debug and then staging on the three), `update` (lock.md F14 after `adb install -r` over a vault made by `wave-3C-merged`'s build, which it builds once in a throwaway worktree; debug on the three, staging on 24 and 37), `skeleton` (`checks/3A.sh skeleton`), `smoke` (`smoke.sh staging` on 24 and 37 with its PIN and fingerprint stage), `window` (`window/all.sh`), `back` (API 37: Back with the keyboard up closes only the keyboard on the forms, the lock screen and "Set a PIN"), `looks` (API 37 and 24: the three styles in light and dark and the phone's colours, at once and after a new process, checked by the colour most pixels have; the API 37 screenshots go to DIR, default `/home/asus/pvwork/shots-look`, as `4A-api37-*.png`), `extras` (`shell/walk.sh`, `setup/create.sh --vault-file` and `session/autolock.sh`, which item 4 of the merge of R3 changed, with the debug build on the three, `walk.sh` staging on 37), and, only when named, `wave5` (a first walk of the merged wave-5 screens through the system's picker on API 37). `checks/4A.sh one SERIAL VARIANT CHECK...` runs one of its device checks (`plan`, `e1`, `e2`, `e3`, `e6`, `e11`, `f14`, `back`, `looks`, `wave5`) on one device. Logs in `build/emu-checks/4A/`. `checks/5.sh [PART...] [--levels 24,28,37] [--shots DIR] [--keep]` is the device check of round R4 (waves 4, 5 and 7; build-plan.md §12, "What the device check of waves 4, 5 and 7 runs"), on the same three devices; `--levels` limits every part to those API levels, so that parts can share the devices in any order (a part run on some levels logs to `PART-api-L-L/`): `merged` (R4's eight heads), `connected` (563, 565 and 575 tests in touch mode, the table counted from the sources), `lock` (`lock/all.sh` with the debug build on the levels lock.md gives each check; E4, E5 and E11 must have run their steps of 5.5 and 6.1), `lockstaging` (E1 and E6 with the staging build on 24 and 37, E16 staging on 24), `wave5` (build-plan 7's lines 1 to 7 with U §12 checks 13, 15 and 16 and the owner's decision 21, debug, on 37 and 24; then line 8, `smoke.sh staging` on 24 and 37), `wave7` (API 37: the fifty accounts with the Security tab's dot, cards and a guided change, decision 23 after the next master-password entry, 7.3's walk, `change/guided.sh`, `smoke.sh staging`), `i18n` (`i18n/all.sh`), `extras` (the scripts the merge of R4 changed: `walk.sh`, `smoke.sh debug`, `3B.sh clip`, `clear.sh`, `app.sh` sourced twice, `boot.sh`'s airplane mode), `window` (`window/all.sh`, architecture.md §8.4) and `skeleton` (`checks/3A.sh skeleton`). `checks/5.sh one SERIAL VARIANT CHECK...` runs its one-device checks (`line1` to `line7`, `check12`, `check13`, `check15`, `d21`, `fifty`, `walk73`). Pictures (API 37, debug) go to `/home/asus/pvwork/shots-5/device-check-R4/`. Logs in `build/emu-checks/5/`. |

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
- **A port is held until its scripts are done with it.** An entry keeps its port while its
  emulator runs, while its `boot.sh` runs (also while a failed or retried boot stops its own
  emulator), and while a `stop.sh` that is stopping it runs (the `stopper` key, at most 5
  minutes); every change to an entry is made under the lock file, an entry is never written back
  once it is gone, and a script removes an entry only after checking that it is still the one it
  started or stopped. Before this (seen on 2026-10-07), a `boot.sh` could take a port the moment
  the old emulator exited, the old owner's `stop.sh` then removed the new entry, and the new boot
  failed with "it was started for , not for ...".

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
`pv_focus`, `pv_ui_nodes`, `pv_ui_nodes_all` (every window, with bounds), `pv_ui_tap REGEX`, `pv_ui_wait REGEX SECONDS` (REGEX is matched
against `text|content-desc|resource-id`; write a literal `|` as `[|]`), `pv_is_debuggable`,
`pv_has_root`, `pv_api`. `pv_ui_nodes` reads the nodes with `uidump/` (about 0.1 s a reading, read
again until two readings 0.3 s apart agree); only when that dumper cannot run, or with
`PV_UI_STOCK=1`, it uses `uiautomator dump`, removing the old file before each try and moving the
focus on with Tab after each failed try (not with `PV_UI_NO_TAB=1`). It prints nothing (status 1) when
no dump succeeded.

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
- Secure windows (staging and release builds): `screencap` gives nothing of them. On API 24 it writes
  an empty file and exits 0 (WindowManager logs "Screenshot failure"), on API 28 it writes nothing and
  exits 1, on API 37 it gives a picture that is black where the app is (`checks/3B.sh secure`). Use
  `adb -s SERIAL emu screenrecord screenshot DIR`.
- A device test meant for some API levels only is filtered with `@SdkSuppress(minSdkVersion = …)`
  (`androidx.test.filters`), never `org.junit.Assume`: AGP 9.4.1 writes a test that ends in
  `AssumptionViolatedException` into the JUnit XML as a failure, so `adb.sh --connected` fails that
  device (seen on API 24 and 28 on 2026-10-07). A suppressed test is left out of the report.
- `uiautomator dump` waits up to 10 s for one second without an accessibility event, then prints
  "ERROR: could not get idle state." with exit status 0 and leaves the old file in place. On API 28
  a focused Compose text field sends `TYPE_WINDOW_CONTENT_CHANGED` every 500 ms (its blinking
  cursor; `uiautomator events` shows it), so no dump succeeds while any field has the focus, and
  Tab cannot leave the form's notes field. `pv_ui_nodes` therefore reads the nodes with `uidump/`.
  Only nodes that are shown are reported: a button below the fold of a short screen (1920 pixels on
  API 24 and 28) must be scrolled to (`pva_reveal`, `pva_tap`).
- `uiautomator events` prints the events of the window in front (its state and its focus) 0.3 to 0.4 s
  after it starts on API 24, as UiAutomation connects; on a loaded machine (2026-10-08, a load of 40)
  one run had printed nothing six seconds after its start. `window/a11y-old.sh` therefore types only
  once its recorder has printed a first line.
- A screen that is not laid out around the keyboard keeps reporting the nodes behind it (the list's
  Add button): a tap at such a node types a letter. `pva_tap` closes the keyboard first.
- A key event (`input keyevent`, `input text`) ends touch mode: when the screen then changes, its
  first text field takes the focus and the keyboard stays up. Scripts submit a form by tapping its
  button (`pva_submit`). In touch mode the list's search field refuses the focus that Android 7 and
  8 hand on when a focused field leaves the screen (after an unlock, after the form closes; A §5.4);
  after a key event it still takes the focus on every API level, which is why scripts tap.
- A streamed `adb install` (adb's default) never ends about one time in six on the API 24 device
  (measured on 2026-10-07; seen by package 3.6 too): the package manager reads until the end of its
  input, and now and then that end does not arrive (`dumpsys package installs` shows the session with
  all its bytes, not sealed). `pv_adb install` therefore adds `--no-streaming` (ten of ten went
  through), and `pva_install` and `smoke.sh` still try three times. AGP's own install for the
  connected tests is streamed and has no time limit: `adb.sh --connected` ends an adb client of its
  serials that has been installing for 120 s (`PV_INSTALL_STUCK_S`; never the server) and runs the
  tests again, up to three times.
- Connected tests by hand: `ANDROID_SERIAL=emulator-5556,emulator-5558 tools/gradle.sh
  :app:connectedDebugAndroidTest`, then `tools/emu/adb.sh --connected-results
  app/build/outputs/androidTest-results/connected/debug emulator-5556 emulator-5558`
  (remove the app from an API 24 device first). `adb.sh --connected` does all of it.
- One package's or one class's connected tests: `tools/emu/adb.sh --connected SERIAL... --
  -Pandroid.testInstrumentationRunnerArguments.package=io.github.dilshodbekmx.passwordvault.files`, or
  `...Arguments.class=` with one class. Of a comma-separated class list only the first class ran
  (2026-10-08, API 28: `class=...files.PlacesTest,...files.DocumentNamesTest` ran PlacesTest's 5 tests
  and the run passed), so `checks/3C.sh` runs a failed class again with one call per class.
  `checks/3B.sh` joins the classes of a device with commas: with two failed classes on one device its
  first step would call both flakes after running one, and only its second step would fail.
- After a connected run AGP removes the app and the test APK from every device. While the test APK is
  installed it exports what `app/src/androidTest/AndroidManifest.xml` declares (since build plan 5.1 a
  documents provider "PV test documents", a stand-in picker and a receiver); a run that was cut off
  can leave it there (`pm path io.github.dilshodbekmx.passwordvault.test`; `checks/3C.sh` removes it
  before the skeleton steps).
- Android 17 freezes a cached process ten seconds after it became one (`dumpsys activity`:
  `use_freezer=true`, `freeze_debounce_timeout=10000`; `am isfrozen PROCESS`). The window helper adds
  its overlay from a broadcast receiver, so while the app's windows hide that overlay the helper is
  such a process, and its hidden window still gets an input event at every change of the touch mode,
  which a tap followed by a navigation or typing key is (`pva_type` sends MOVE_END). Frozen, it does
  not answer, and the system ends it: `dumpsys activity exit-info PACKAGE` says `reason=6 (ANR) ...
  bg anr: Input dispatching timed out (... PvWinOverlay is not responding. Waited 5000ms for
  TouchModeEvent(inTouchMode=false))`, and the overlay goes with the process. So `window/overlay.sh`
  lost its overlay when its steps were slow (2026-10-08, API 37, the debug build, a loaded machine);
  it now keeps the helper unfrozen with `am unfreeze --sticky PROCESS` (the command is there from
  Android 14 on). A script's helper that must stay alive with nothing on the screen and no bound
  service needs the same. `exit-info` (Android 11 and newer) is the first place to look when a
  helper or the app is gone for no visible reason: it keeps the reason of each of a package's last
  process ends.
- Android 9's Recents (the AOSP image's Launcher3 Quickstep) gives a task's card, its snapshot and
  its icon no text and no description; after Home, the newest task is the card in the middle.
  Android 17's card has a snapshot node with the app's name; Android 7's stack has a title.
  (`clipboard/clear.sh`, case `swipe`.)
- A toast from `app_process` as the shell, the control for "no toast is shown": Android 9 refused
  it with SecurityException "Package android not in UID 2000" until the context also named
  `com.android.shell` as its base package and inflated the toast's view itself (`PvClip.toast`).
- Without the exact-alarm permission (API 31 and later), the clipboard's clear alarm can come
  several seconds early when another alarm wakes the phone. The receiver then waits for the
  deadline, and `dumpsys alarm` lists no alarm meanwhile.
- A masked `VaultSecureField` reports one mask character (•) per character to accessibility on
  every API level, so `pva_text` and `pv_ui_nodes` read masks there. To read the text, tap "Show
  password" first (`checks/3A.sh`, `form_password`).
- Compose sends its accessibility text events only while a real accessibility service is on; it
  leaves UiAutomation out, and the API 24 image has no such service. Compose grants
  `ACTION_ACCESSIBILITY_FOCUS` only while touch exploration is on.
- On API 37 "Show passwords" lives in `android.text.ShowSecretsSetting`, not in `Settings.System`
  `show_password`.
- A temporary content capture service captures only its own package (logcat: "Ignoring package ...
  while using temporary service"), and the API 37 image's own service gives the app no session.
- `dumpsys autofill` keeps the request history across apps and runs: after the connected tests on
  API 37 it holds sessions of the app's package from the test host activity. Compare against a
  baseline taken at the start (`window/autofill.sh` does).
- `app.sh`'s `pva_start` (through `pva_rotate`) sets `Settings.System accelerometer_rotation` to 0,
  and nothing sets it back; a fresh API 37 device has 1.
- The floating toolbar of a text selection (Copy, Select all, Translate) is in no accessibility tree
  here: UiAutomation's `getWindows()` lists no window for it on API 24 and 28, and on API 37 the system
  draws it into a `SurfaceView` of the app's popup window whose nodes cannot be reached. Its order
  differs: a read-only selection's toolbar began with Copy on all three; a text field's read Cut, Copy
  on API 28, CUT, COPY, PASTE on API 24 and Translate, Cut, Copy on API 37 (the text classifier's
  suggestion first). `checks/3B.sh` finds its words in screenshots of debug builds (`toolbar_word`).
- From Android 13 on the system shows its own clipboard overlay at the bottom left for a few seconds
  after a copy, over the left part of the app's status row (a sensitive clip is shown as dots in it),
  and `pvc_get` makes Android 12 and later show "Shell pasted from your clipboard" over the row's
  middle: a script that looks at the row's countdown does so before `pvc_get`, and again a few
  seconds later when the overlay hides it (`checks/3B.sh`, `row_counts_down`).
- Android Studio's Run can land on a private emulator: its device list shows every running emulator
  (`pvlab_api37 [emulator-5580]`), and with one of them chosen each Run stops, reinstalls and starts
  the app there (`am force-stop`, a full deploy, `am start -n ... -a android.intent.action.MAIN`), in
  the middle of whatever a script is doing. On 2026-10-08 it did so on emulator-5580 at 14:58:47,
  16:41:45 and 16:42:19 (idea.log: "Full deploy on emulator-5580"); each time the step that was
  running failed (`clipboard/clear.sh`'s case `other`: the force-stop removed the clear alarm; step 8
  of `checks/3B.sh clip`: the app was suddenly locked). On API 37 adbd logs every request, so
  `adb logcat -d -s adbd:I` shows the foreign ones as `shell:am ...` where these scripts send
  `shell,v2,raw:...`; `checks/3B.sh` prints them as a NOTE (`foreign_run`). A step that failed on an
  API 37 device for no reason of its own is checked against that first. Studio's own log has every
  Run on any device, whatever its API level (`~/.cache/Google/AndroidStudio*/log/idea.log`:
  `AndroidRunConfigurationExecutor - Launching on device pvlab_api37 [emulator-5580]`);
  `checks/3C.sh` prints those of its three devices after each part (`studio_runs`). Studio also
  attaches to every debuggable process of every running emulator to learn its name (idea.log:
  `Device 'pvlab_api28 [emulator-5582]' tracking jdwp process`), which stops nothing.

- A device is in keyboard mode after `boot.sh` and during a standard connected run (`dumpsys window`
  prints `mInTouchMode=false`). A tap, or a run with `-e pvShots` (`LockShots.touchMode`), puts it in
  touch mode until the next navigation or typing key. On API 37 in touch mode Gboard hides itself in
  its landscape full-screen mode after a rotation (2026-10-08, job 4.2b: the master-password screen's
  keyboard stayed hidden after 17 of 17 rotations, and `MasterPasswordRotationTest` failed 9 of 18
  runs alone). Since w5-carry step 3 the screens ask again for about a second (`FieldKeyboard` in
  `ui/common`; architecture.md §5.4): on 2026-10-13 4.2b's `rotate-in-app.sh` saw the keyboard back
  after 8 of 8 rotations in touch mode (0 of 6 before), and `FieldKeyboardTest` rotates four times in
  touch mode.
- DocumentsUI on the API 24 image (measured with 5.2's screens on 2026-10-08): its open picker
  ignores `input tap` and `input swipe` on a file, in the grid and in the list view;
  `KEYCODE_DPAD_DOWN` twice and then `KEYCODE_ENTER` opens it. Its "Save as" opens in Recent with
  SAVE disabled until a root such as Downloads is chosen ("Show roots"). Its file-name keyboard
  covers the SAVE bar, which uidump still reports, until the keyboard is closed. Its Downloads root
  lists only DownloadManager's files: a file pushed with adb is reached only through ⋮ › Show
  internal storage › the device › Download (5.4), or through 5.1's test documents provider.
- DocumentsUI on the API 37 image (`com.google.android.documentsui`, measured by the device check of 4A
  on 2026-10-09): "Save as" opens in Downloads with the suggested name and its SAVE button enabled
  (`button1`, text "SAVE"); the open picker lists "Files in Downloads", each file's name in a node with
  the id `title`, and an `input tap` on it opens it. A file pushed with adb is listed there after
  `adb shell content call --uri content://media --method scan_volume --arg external_primary`.
  (`checks/4A.sh`, part `wave5`.)
- The real lock on these images (device check of 4A): fingers 1 and 2 open the app through the
  system's prompt on API 28 and 37 when touched 2 s after the lock says the prompt is open (the
  debug command `status`, `prompt: UNLOCK, launch N`), and through androidx.biometric's own dialog
  in the activity on API 24, whose button reads "USE PIN", also in the staging build (R8, build
  check R-16). After the fifth wrong PIN with fingerprint unlock on, the lock screen is the
  fingerprint-only screen, and the prompt does not open by itself again in that visit.
- On the API 37 image with the network on, the Play Store updates GMS, Gboard, Chrome, Photos and
  others within about 20 minutes of a boot and fills the 6 GB `/data` to 96 % (6.1, 2026-10-09);
  installs then fail with "Requested internal only, but not enough space". `boot.sh` therefore turns
  airplane mode on for API 37 (`cmd connectivity airplane-mode enable`; `PV_BOOT_NETWORK=on` keeps the
  network), and `pm uninstall-system-updates PKG` frees the space on a device that filled up.
- The phone's languages can be set from the shell (`i18n/PvLocale.java`, `pvi_phone_set`). From API 33
  on, `cmd locale get-app-locales` and `set-app-locales` work, and the phone's own App language page
  opens with `am start -a android.settings.APP_LOCALE_SETTINGS -d package:PKG`. Below API 33 AppCompat
  keeps the app's choice in `files/androidx.appcompat.app.AppCompatDelegate.application_locales_record_file`
  and deletes it for the system language.
- Without a PIN, every master-password unlock runs "Choose a PIN" as 4.3's setup flow
  (`ChoosePinReason.NONE_HERE`: the PIN, the fingerprint offer and "Make backups") before the route
  the app was on shows again; a script that unlocks often chooses a PIN first, as `change/guided.sh`
  and `shell/walk.sh` do (7.2). After "Finish now" of a new master password the same steps leave
  "Make backups" out and close onto 5.5's screen, whose backup step follows (merge of R4).
- From Android 13 on, a tap in the lower half of the screen within about 8 s of a copy can land on the
  system's own copy preview at the bottom left (7.2; ux.md §6 notes the overlay).
- After a master-password unlock while the vault has no PIN, the PIN steps open over the screen the
  unlock brought back and close onto it ("Finish" on "Make backups" goes back, since the device check
  of 4A); a form's "Your unsaved changes were kept." shows then, for about 4 s.
- DocumentsUI on the API 24 image, with the app's real screens (the device check of R4, `app.sh`'s
  `pva_picker_*`): "Save as" opens where it was last (Downloads, or Recent with SAVE off); the first tap
  on SAVE after a root is chosen is lost to the name field's keyboard, which comes up a moment later,
  so SAVE is tapped again while the picker stays in front. In "Recent" the list also shows a folder
  named Downloads: a root is tapped among the drawer's nodes only (below `roots_list`). The open
  picker ignores taps on files and on folders alike: in its list view (the grid moves the arrow keys
  by column) `KEYCODE_DPAD_DOWN` moves the focus row by row, `uiautomator dump` marks the focused row
  (`focused="true"`), and `KEYCODE_ENTER` opens it (Android 9's picker, API 28, takes taps on folders and
  files, and there the arrow keys stop in its toolbar and sort row). A file pushed with adb is reached through ⋮ ›
  "Show internal storage", the root "Android SDK built for x86_64" and its Download folder. A second
  file of a name that is already in Download gets " (1)": "PasswordVault-backup-2026-10-19 (1).pvault",
  but after the extension where the platform's table has none for the type (application/json on
  Android 7, formats.md §2 rule 4): "passwords-2026-10-19.json (1)", the name the app then shows
  ("Saved: …"). A file deleted with adb stays listed in the Downloads root (DownloadManager keeps it)
  until something replaces it.
- A lock clears the ViewModels of the unlocked screens (Navigation 3; architecture.md §5.5 step 5), so
  what a screen must show after the unlock waits in a one-time holder (architecture.md §3.6), as
  7.2's `GuidedChangeMemory` and, since the device check of R4, `ChangedUnderLock` (decision 21) do.
- `FieldKeyboard` (w5-carry) asks for the keyboard again for about a second after a field takes the
  focus or the screen turns: a script that closes the keyboard by hand right after a screen appears
  waits that second out first (`checks/5.sh`), as the device tests do (`FieldKeyboard.AGAIN_MS`).
- The list's backup banner shows only when the first account was added a day ago or more (or the
  newest backup is 7 days old; `ListBanners.backupDue`). Imported accounts keep their dates (decision
  7), so `checks/5.sh` imports accounts dated three days back (`add_old_two`) to see the banner.
- "Start with an empty app" is offered on the "Forgot master password?" sheet only while no PIN,
  fingerprint or practice can be used now (`LockViewModel.sheetUi`). With a PIN that is the case while
  its record cannot be read (`PinStatus.CannotCheck`): `checks/5.sh` line 7 makes `pin.rec`
  unreadable with `run-as … chmod 000` for one process, and readable again after.
- On API 24 `cmd alarm set-time` does not exist; with root, `su 0 date -u MMDDhhmmYYYY.ss` sets the
  clock (`-u`: the string is UTC; without it the device reads it in its own zone, 5 hours off here).
- Android 17's App language page (Settings › Apps › the app › Language) lists the app's languages first
  and adds the app's current one, checked, under "Suggested" a moment later; on a busy machine a dump
  taken as soon as "All languages" shows has nothing checked (`i18n/matrix.sh` reads it again for up to
  about 12 s since the device check of R4).
