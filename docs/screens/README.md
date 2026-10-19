# Screens under long text

Pictures of every screen of PasswordVault in the pseudo-locales, kept by build plan 6.2 ("Layout under
long text"). Each was taken by `tools/emu/layout/walk.sh` with the debug build on an API 37 emulator set
to **320 dp wide** (`wm density`) with the **font at 200 %** (`font_scale`), the hardest case the app
must handle, and each passed the walk's four checks there: no clipped button, no truncated or clipped
text, no text left in plain English, no control under 48 dp.

- `en-XA/`: every screen in `en-XA`, the pseudo-locale that accents every letter of the app's strings
  and makes them about 40 % longer (`[Þåššŵöŕðš one two]`), longer than Uzbek or Russian will be.
- `ar-XB/`: the screens whose layout differs in `ar-XB`, the right-to-left pseudo-locale: the rows,
  fields, bars and keypad page mirror; secrets, logins, websites, the PIN dots and the keypad stay left
  to right (ux.md §3).

Each picture is named after its route (`nav/Route.kt`), shows the screen as it opens (its top), and is
scaled to a third and reduced to a palette to keep it small. Made with:

```sh
tools/emu/layout/walk.sh SERIAL                       # pictures under build/emu-layout/SERIAL/
python3 tools/emu/layout/screens.py build/emu-layout/SERIAL docs/screens
```

| Route | en-XA | ar-XB | What it shows |
|---|---|---|---|
| Welcome | [en-XA](en-XA/Welcome.png) | [ar-XB](ar-XB/Welcome.png) | First start: the logo, the promise, Start and Restore |
| Language | [en-XA](en-XA/Language.png) | | The language dialog over Welcome |
| Restore | [en-XA](en-XA/Restore.png) | | Restore from a backup file, before a file is chosen; the long title wraps under the back arrow |
| CreateMasterPassword | [en-XA](en-XA/CreateMasterPassword.png) | | Setup step 1: the suggested words |
| WriteDown | [en-XA](en-XA/WriteDown.png) | | Setup step 2: write it down on paper |
| TypeAgain | [en-XA](en-XA/TypeAgain.png) | | Setup step 3: the words typed again, with the keyboard |
| CreatePin | [en-XA](en-XA/CreatePin.png) | [ar-XB](ar-XB/CreatePin.png) | Choose a PIN: the dots and the whole keypad on the screen, the texts scrolling above them |
| ConfirmPin | [en-XA](en-XA/ConfirmPin.png) | | The same PIN again |
| FingerprintOffer | [en-XA](en-XA/FingerprintOffer.png) | | The fingerprint offer after the PIN |
| BackupsExplained | [en-XA](en-XA/BackupsExplained.png) | | "Make backups", the last setup step |
| Locked | [en-XA](en-XA/Locked.png) | [ar-XB](ar-XB/Locked.png) | The lock screen's pad (Bank blue's panel, the dots, the keypad) |
| Locked (master password) | [en-XA](en-XA/Locked-master-password.png) | | The lock screen's master password, with the keyboard |
| Passwords | [en-XA](en-XA/Passwords.png) | [ar-XB](ar-XB/Passwords.png) | The list with the search field, Add, the bottom bar and the clipboard row |
| Passwords (empty) | [en-XA](en-XA/Passwords-empty.png) | | The list before the first account |
| RecentlyDeleted | [en-XA](en-XA/RecentlyDeleted.png) | | Recently deleted, its title under the back arrow and Empty |
| Entry | [en-XA](en-XA/Entry.png) | [ar-XB](ar-XB/Entry.png) | An account's screen |
| BigLetters | [en-XA](en-XA/BigLetters.png) | | Show in big letters |
| Edit | [en-XA](en-XA/Edit.png) | [ar-XB](ar-XB/Edit.png) | The form of a new account; labels of two lines go above their boxes |
| Edit (generator) | [en-XA](en-XA/Edit-generator.png) | | The password generator's sheet |
| Security | [en-XA](en-XA/Security.png) | [ar-XB](ar-XB/Security.png) | The Security tab's cards |
| Group | [en-XA](en-XA/Group.png) | [ar-XB](ar-XB/Group.png) | The accounts that share one password |
| GuidedChange | [en-XA](en-XA/GuidedChange.png) | | The guided change of one password |
| Settings | [en-XA](en-XA/Settings.png) | [ar-XB](ar-XB/Settings.png) | Settings |
| Theme | [en-XA](en-XA/Theme.png) | | The dialog "Appearance" |
| ChangePin | [en-XA](en-XA/ChangePin.png) | | Change PIN, the current PIN first |
| ChangeMasterPassword | [en-XA](en-XA/ChangeMasterPassword.png) | | Change master password, step 1 |
| NewMasterPassword | [en-XA](en-XA/NewMasterPassword.png) | | Choose a new master password when the old one is forgotten |
| Backup | [en-XA](en-XA/Backup.png) | | Make a backup: what a backup is |
| OldPasswords | [en-XA](en-XA/OldPasswords.png) | | Old passwords put aside (drawn with the device tests' fakes) |
| Export | [en-XA](en-XA/Export.png) | | Export: the choice of a format |
| Import | [en-XA](en-XA/Import.png) | | Import: where the passwords are now |
| ColumnMapping | [en-XA](en-XA/ColumnMapping.png) | | What each column of a file holds (drawn with the device tests' fakes) |
| Help | [en-XA](en-XA/Help.png) | | A help page ("How your passwords are protected") |
| Licences | [en-XA](en-XA/Licences.png) | | Open-source licences |

No picture: `Starting` (shown only while the lock reads its records at a start) and `AppLock` (no screen
opens it; its rows are part of Settings). The walk itself looks at many more states than these pictures
show (every step, question and result of each screen, the lock's other screens, the notices); its run
keeps a picture of each under `build/emu-layout/SERIAL/`.
