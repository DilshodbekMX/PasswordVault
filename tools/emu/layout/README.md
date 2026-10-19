# Layout under long text: `tools/emu/layout/`

Build plan 6.2's [script] line: every screen of the app, walked in the pseudo-locales `en-XA` and
`ar-XB` on a phone set to **320 dp wide** and **font size 200 %**, shows no clipped button, no
truncated text (a warning or any other), no text left in plain English and no control under 48 dp.

```sh
tools/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest
S37=$(tools/emu/boot.sh 37)
tools/emu/layout/walk.sh "$S37"                      # both locales, both parts; about 15 minutes
python3 tools/emu/layout/screens.py build/emu-layout/"$S37" docs/screens
tools/emu/stop.sh "$S37"
```

`walk.sh SERIAL [--apk FILE] [--test-apk FILE] [--locales TAG,TAG] [--parts app,states] [--shots DIR]
[--width DP] [--font SCALE] [--style NAME]` needs the debug build (pseudo-locales, screenshots,
`run-as`). It sets the screen's density so that the phone is 320 dp wide (`wm density`, as "Display
size" does) and the font scale to 2.0 (`settings put system font_scale`), and puts both back however it
ends, with the app's language (which Android 13 and later keep outside the app's data) and its data. `--width 411 --font 1.0` walks a 1080-pixel phone at its own size (build plan §12's open problem:
the PIN steps in `en-XA` on a 1080 × 1920 screen). `--style EMERALD` or `NIGHT_VAULT` walks the part
app in that colour style (run `app-<locale>-<style>`); `--locales en` walks it in plain English, for
pictures (the English check is made only in a pseudo-locale).

## The two parts

- **app** (once per locale): the real app, from a fresh install (`pm clear`), driven in its own
  process by the device test `ui.layout.LayoutWalkTest` (`am instrument -e pvLayout <run> -e
  pvLayoutLocale <tag>`): Welcome, the language dialog, Restore, the three setup steps with a mismatch,
  the PIN steps with a mismatch, the fingerprint offer and "Make backups"; the empty list, the form of a
  new account with the generator (both kinds), "Replace the password?" and "Add field", an account's
  screen with its password shown, big letters, its menu, the first-copy tip and the clipboard row, the
  form again with "Save changes?"; the list, search with and without results, a row's menu and big
  letters from it, an account deleted and Recently deleted; the Security tab, a group and the guided
  change; Settings with "Appearance", the language dialog, both delay dialogs; Change PIN; Backup with a
  wrong master password; Export to its warning and master password; Import with the Google help; Change
  master password with a wrong one and "I don't remember it"; every help page and the licences; Lock
  now, the pad, the master password with a wrong one, "Forgot master password?", and any notice after
  the unlock. The walk presses controls by their click actions and moves between tabs through the
  app's navigation state, so it does not depend on where a finger would land.
- **states** (once): the states the real app reaches only with time or a damaged file, drawn with the
  device tests' fakes by each area's pseudo-locale test (`ui.lock.LockPseudoLocaleTest` and those of
  master, entry, list, security, guided change, backup, export and import), in `en-XA` and `ar-XB`:
  the lock's other screens (fingerprint only, an older master password, the older set, can't be
  opened, unreadable), every notice and the prompt, every step and result of a new or changed master
  password, of a backup, an export and an import, the column mapping, the Security tab's cards. Each of
  those tests calls `LayoutHook.look` at each state it keeps a picture of; a test that fails is a FAIL
  line too.

## The checks

`ui.layout.LayoutProbe` reads, in the app's process, every Compose root (the window, a dialog, a sheet,
a menu), at each screen as it opens and again after each stretch of every list or column that scrolls:

| Check | Fails when |
|---|---|
| no clipped button | a control's text (a button's, a row's, a tab's) is cut, clipped, split or covered as below, or a control lies past the bottom of a window that does not scroll, or letters of the text lie outside the control's own rounded shape (a Material button clips its label to its fully rounded ends, which cut the corners off a label of three lines: the shape is read from the control's `Modifier.clip`, the letters' outlines from the text's font) |
| no truncated or clipped text | any other text: cut with an ellipsis or overflowing its box (unless marked `mayTruncate`: its full text is reachable elsewhere, as a list row's name is on the account's screen), clipped by a parent that does not scroll, spilling out of its parent, past the edge of its window, a whole word of up to 16 characters split across two lines (a word: what the line-breaking rules keep whole), or covered by the floating Add button where its list cannot scroll on (the list keeps room for the button below its last row) |
| no text left in plain English | (pseudo-locales only) a text, label or error without a character the pseudo-locale made, unless it is the user's own (marked `userText`: an account's name, a login, a file's column names, the suggested words), a secret on show, an app string that is the same in every language, a library's own label, or a date, time or number written by the phone's ICU |
| no control under 48 dp | a control a finger can press whose touch area is under 48 × 48 dp |

It prints `PASS` or `FAIL` per screen and check (`FAIL` lines carry what was found, and where), `FAIL`
for a step of the walk that could not be done, and a summary; exit 0 when nothing failed. Logs and
reports under `build/emu-layout/SERIAL/`, pictures of every screen and state under
`build/emu-layout/SERIAL/<run>/`.

## Shared parts that keep long texts whole (`ui/common`)

`VaultTopBar` (the top bar of every screen; a title that does not fit beside the buttons moves under
them and wraps), `FittedText` (the bottom bar's labels shrink before a word would break; the search
field's hint stays on one line), `FieldLabel` (a field's label of two lines moves above its box),
`KeypadColumn` and `LockPadPage` (the dots and the whole keypad stay on the screen, the texts above them
scroll), `VaultDialog` (title and text scroll, the buttons stay reachable), `TextBeside` (a button goes
under its text when the two would squeeze it), `ClipboardRow` (its texts take at most a fifth of the
window and scroll), `VaultSheet`'s 48 dp drag handle, `LongLabelShape` (a button whose label may take several lines keeps round ends for one line and 16 dp corners above, which stay clear of its letters); the list's Add button hides while the keyboard is
up and where the list has no room for it. `LongTextLayoutTest` checks them at 320 dp and a font scale of
2 in every connected run, whatever the phone.

## Runs

2026-10-19, branch `w6A-6.2` (the app as at 0af5b03, the probe as at 6b2502e), debug build, 320 dp
wide at 200 %:

| Device | Parts and locales | Result |
|---|---|---|
| API 37, 1080 × 2400 (320 × 711 dp) | app and states, en-XA and ar-XB | 1718 checks passed, 0 failed |
| API 28, 1080 × 1920 (320 × 569 dp) | app and states, en-XA and ar-XB | 1718 checks passed, 0 failed |
| API 37 | app, en-XA and ar-XB, `--style EMERALD` | 537 passed, 0 failed |
| API 37 | app, en-XA and ar-XB, `--style NIGHT_VAULT` | 537 passed, 0 failed |
| API 37 | app, `--locales en` (pictures) | 269 passed, 0 failed |

Both parts take about 15 minutes on one device. The pictures of `docs/screens/` come from the API 37 run.
