# The lock's emulator checks: `tools/emu/lock/`

The checks E1 to E17 of lock.md §15.3, as scripts (build plan 4.5). Each runs unattended on one
private emulator of `tools/emu/boot.sh`, against the APK it is given (the debug build by default),
prints `PASS`, `FAIL` or `SKIPPED` per step (and `NOTE` lines for what lock.md asks to be recorded),
ends with `SUMMARY SERIAL: N passed, N failed, N skipped`, and exits 0 when no step failed, 1
otherwise, 2 for a usage error, 3 for a refused serial. Everything goes through `tools/emu/adb.sh` and
`tools/emu/app.sh` (`pv_use`, `pv_adb`, `pv_step`, `pva_*`): a serial that this worktree's `boot.sh`
did not start is refused, and `emulator-5554` is never touched. Screens are found by their resource
ids (LockTags `pv.lock.*`, PinPadTags `pv.pin.*`, NoticeTags `pv.notice*`, the routes `pv.route.*`);
a text on the screen is compared with the APK's own English string, as the emulators run in English.
The lock's state is read with the debug command `status` of build plan 4.1.

```sh
S37=$(tools/emu/boot.sh 37)
tools/emu/lock/E1.sh "$S37"                                    # the debug build
tools/emu/lock/E1.sh "$S37" --apk app/build/outputs/apk/staging/app-staging.apk
tools/emu/lock/all.sh                                          # every check on 24, 28 and 37 (boots and stops them)
tools/emu/lock/all.sh --staging --only E1,E6 "$S24" "$S37"     # wave 4's staging repeat on given devices
tools/emu/stop.sh "$S37"
```

## The checks and where they run

| Script | lock.md | Levels | Time (debug, one device) | Waits for |
|---|---|---|---|---|
| `E1.sh` | PIN unlock through a Keystore HMAC key; five wrong PINs: slot and alias gone, the master password opens; the time from the sixth digit to the list logged | 24 28 37 | 3 min | — |
| `E2.sh` | `hold-after-charge` + `am force-stop`, `hold-before-hmac` + `kill -9`: one try counted, "interrupted" | 24 28 37 | 3 min | — |
| `E3.sh` | `adb reboot` with a charge held (both holds) | 24 28 37 | 4 min | — |
| `E4.sh` | the clock a year ahead and back: tries left, practice; the pending wait and the "replaced" notice | 37 | 5 min | 5.5 for the last two steps |
| `E5.sh` | `boot_count 99999` three times: the 24-hour wait gains only real minutes | 37 | 4 min | 5.5 (all of it) |
| `E6.sh` | fingerprint with 1.4.0-alpha07: one touch opens; an unknown finger leaves the prompt open; Android 7's own dialog and its "Use PIN" | 24 28 37 | 3 min | — |
| `E7.sh` | (a) a new finger, all fingers deleted, a finger enrolled again; (b) the screen lock removed and set again | (a) 37, (b) 24 28 37 | 8 min | — |
| `E8.sh` | the lockout trap; five wrong fingers lock the phone; a cold boot | 37 | 4 min | — |
| `E9.sh` | `fingerptint_app_enabled 0` and back | 37 | 2 min | — |
| `E10.sh` | `ks-fault` for every row (PIN check, fingerprint probe, prompt, key making, `delete`), `drop-keys`, `pm uninstall -k` and a new install | 24 28 37 | 25 min | — |
| `E11.sh` | rotation with 3 digits, with the prompt open; a language change with "Immediately"; the master-password field across a rotation and the saved state | 24 28 37 | 5 min | 6.1 for the language step through Settings |
| `E12.sh` | the pad for a screen reader, Switch Access and a hardware keyboard; the non-tool accessibility service sees no key press | 28 37 | 4 min | — |
| `E13.sh` | "Device locked": `hold-before-hmac`, the screen off, `release` (TEMPORARY on API 35+; on Android 9 right, nothing opened) | 28 37 | 2 min | — |
| `E14.sh` | /data full: "storage is full", nothing counted, the master password opens; the same with `fail-next-write` | 37 | 4 min | — |
| `E15.sh` | `bmgr backupnow`, clear, restore with the `backupCheck` variant (which it builds): the vault comes back, no lock record does | 28 37 | 3 min | — |
| `E16.sh` | heapsim 128m/256m with argon2kt removed: the Java fallback; 64m/128m: "not enough memory" | 24 (userdebug) | 4 min | — |
| `E17.sh` | away 31 s, "Immediately", screen off, 90 s and 6 min in the file picker, the picker and the screen off (the task resumes), an overlay over the pad; Android 7: the pad | 37 (24: the last item) | 14 min (`--short`: 8) | — |

`all.sh` runs them on these levels, the devices at the same time and the checks of one device one after
another, with E8 last on its device: it leaves the emulator's fingerprint sensor locked out until a
reboot, which it then makes itself. E7 puts the phone's screen lock and fingers back as the base has
them (PIN 1111, fingers 1 and 2), E16 the heap, E9 and E15 the settings they change. Logs:
`build/emu-lock/all/SERIAL-EN.log`; each script keeps what it saw under `build/emu-lock/SERIAL/EN/`
(`status.log` has every `status` answer it read).

Every check runs on the debug build and on the staging build (lock.md §15.3 "Two builds"); on staging
the steps that need `run-as` or root print SKIPPED or use what is there (E2's `kill -9` is `am force-stop`
on staging on API 37, as build plan §12 says). E15 uses the `backupCheck` variant in every case.

## Steps that wait for a later package

A step that a package of the same round enables is written now, against that package's design and
ids, and runs through `pvl_later WAVE NAME -- COMMAND`: when the APK has the package's strings (5.5:
`strings_master.xml`, names `master_*`; 6.1: `strings_language.xml`, names `language_*`) it runs as any
other step; until then it prints `SKIPPED NAME (waits for …)`.

| Check | Step | Waits for |
|---|---|---|
| E4 | a pending 24-hour wait unchanged by the clock (its credit read from `state.rec` with run-as) | 5.5's F11 screens (`newmp.sh`, MasterTags `pv.master.*`) |
| E4 | the "replaced" notice still shown a year ahead (the wait made ready by setting `credited_ms` in `state.rec`, a test shortcut for the 24 credited hours an emulator cannot wait) | 5.5 |
| E5 | everything after setup | 5.5 |
| E11 | the language changed in Settings › Language (6.1's dialog: `pv.open.Language`, `pv.language.dialog`, `pv.language.RU`, `pv.language.SYSTEM`) | 6.1 |

E11 runs the lock's part of the language step now through the debug command `locale` (the same
`AppLanguages.setTags` the dialog calls); E17's picker steps run now (5.1 to 5.4 are merged).

## The libraries

| File | What |
|---|---|
| `lib.sh` | `pvl_begin NAME "$@"` (SERIAL [--apk FILE]; sets SERIAL, APK, API, VARIANT, PVL_OUT) and `pvl_end`; `pvl_fresh`, `pvl_set_up PIN\|- fingerprint\|-` (setup with the suggested words, then 4.3's PIN steps), `pvl_pin_steps`, `pvl_lock_now`, `pvl_pad`, `pvl_unlock_pin`, `pvl_unlock_mp`, `pvl_to_master_password`, `pvl_wrong_pins`; `pvl_status` and its readers `pvl_item`, `pvl_pin KEY`, `pvl_fp KEY`, `pvl_state KEY`, `pvl_policy KEY`, `pvl_lock_state`, `pvl_alias_count`, `pvl_expect_pin KEY=VALUE…`; `pvl_screen` (pad, fingerprint-only, master-password, prompt, notice, list, …), `pvl_wait_screen`, `pvl_status_line`, `pvl_notice_has`, `pvl_answer`; `pvl_finger N`, `pvl_prompt_up`, `pvl_prompt_down`, `pvl_prompt_negative`, `pvl_touch_prompt`; `pvl_kill9`, `pvl_force_stop`, `pvl_restart`; `pvl_res`, `pvl_expect`, `pvl_text_is`, `pvl_text_shown` (the APK's own texts); `pvl_record`, `pvl_record_put` (a lock record through run-as, framed as lock.md §5.3); `pvl_later`, `pvl_skip`, `pvl_note`; `pvl_setting_set`, `pvl_undo` (put back at the end) |
| `phone.sh` | the phone's settings: `pvp_enroll FINGER`, `pvp_delete_all_fingers`, `pvp_lock_clear`, `pvp_lock_set`, `pvp_fingers`, `pvp_has_lock`, `pvp_restore_base` |
| `newmp.sh` | F11 through 5.5's screens: `pvn_request`, `pvn_credited_ms`, `pvn_make_ready`, `pvn_finish` |
| `lab.sh SERIAL CMD [WORDS]` | one debug command and the PVLOCK/PVDEBUG lines it caused (from the biometric lab's `lab.sh`) |
| `enroll_fingerprint.sh SERIAL [FINGER]`, `delete_all_fingerprints.sh SERIAL` | the lab's two scripts, on a serial and on API 24, 28 and 37 (`phone.sh`) |
| `heapsim.sh SERIAL GROWTH HEAP` / `--restore` | the old-Android lab's heap simulation on a `boot.sh` device (su, framework restart) |

The biometric lab's `uitap.py` and `uidump.sh` are `adb.sh`'s `pv_ui_tap` and `pv_ui_nodes`.

## Device facts found while writing these

- The fingerprint prompt: on API 37 the system's `BiometricPrompt` window (focus `BiometricPrompt`;
  ids `biometric_icon`, `title`, `indicator`, `button_negative`); on API 28 the system's
  `FingerprintDialogView` (ids `fingerprint_icon`, `title`, `error`, `button2`); on API 24
  androidx.biometric's own dialog inside the app's activity (focus stays the app; ids
  `fingerprint_icon`, `fingerprint_error`, `alertTitle`, `button2`). `pv_ui_nodes` (the active window)
  shows all three; `pv_ui_nodes_all` does not list the system's prompt window on API 28.
- On the 1920-pixel screens (API 24 and 28) the pad's "TEST BUILD" line is below the fold.
- E8 on API 37: the first five wrong fingers of a fresh boot lock the whole phone (adaptive
  authentication) before the prompt turns into its own "Enter your PIN" screen; after the phone is
  unlocked the app's new visit opens one prompt by itself and keeps it open, and two more wrong
  fingers then bring the system's PIN screen (the emulator's sensor never resets its failure count).
- `pm uninstall -k` and a new install on API 37: the PIN and fingerprint keys survived (E10 prints the
  result on each level).
- After `bmgr restore` on API 28 the app's process stays alive in restricted mode (android.app.Application
  in place of PasswordVaultApp); an activity started in it fails with ClassCastException. A restore on a
  new phone does not leave the app so; E15 stops the process before it starts the app, and notes it.
- The API 24 image sets `dalvik.vm.heapsize=512m` and no `heapgrowthlimit`; `heapsim.sh` saves an unset
  limit as the heap size.
- `cmd alarm set-time` moves the phone's wall clock with automatic time off (E4 puts the clock back to
  the host's time when it ends).
