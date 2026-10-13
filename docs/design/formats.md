# PasswordVault: export and import formats

Date 2026-10-06. Status: proposal for review. Scope: every file the app writes or reads besides its own live vault: the encrypted backup, CSV, JSON, Excel with and without a password, and imports. The entry model is the version 1 model of `docs/design/ux.md` §2 (tags deferred; `changePending`, `checkIgnored`; history capped at 5; control characters refused on input), stored as architecture.md §4.3 says (with `pendingOldPassword` and `deletedAt`).

**Revised on 2026-10-06** by build-plan package 0.3, so that it follows architecture.md §6 (D-10a to D-10f, D-12, D-15 to D-17) and the findings of [formats-review.md](formats-review.md) (P1 to P17) that it accepted. Where they disagreed, those two win; §14 lists what changed.

How I know things: **[S]** source code read today at HEAD (path given); **[P]** primary document fetched today; **[X]** my experiment today on this machine; **[I]** inference or design judgement; **[M]** from memory, not re-checked.

The prototype (Kotlin, JVM, no Android types; 1,069 lines without blanks and comments) was committed in `prototypes/formats/` (the code in `src/pv/formats/`, its test in `test/FormatsTest.kt`, `build.sh`); build-plan package 3.15 removed all of it except `OfficeCrypto.kt` and the checks of the locked workbook (D-16), and the rest is in the history at the tag `wave-3B-merged`. Build: Android Studio's kotlinc 2.3.20 and kotlinx-serialization-json 1.11.0 from the Gradle cache. The outside checks of the plain files are now `tools/validate/` (`run.sh`, with the fix of FR P18); those of the locked workbook stay in `prototypes/formats/validate/` with `OfficeCrypto.kt` (D-16). The prototype predates this revision: where they differ, this text is the specification.

## 0. Decisions in brief

| Format | File | Who can open it | Lost compared with the vault | Version |
|---|---|---|---|---|
| **Backup file** (recommended) | `.pvault`, `application/octet-stream` | only this app, with the master password, on any phone | nothing | **1** |
| **Excel** | `.xlsx`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Excel, LibreOffice, Google Sheets, phone office apps | history, flags, ids; for reading and printing only | **1** |
| **Excel with a password** | `.xlsx`, same type | LibreOffice and Apache POI (tested), Excel (same structure, not tested); **not** Google Sheets | as Excel | **later**: not in version 1 (architecture.md D-16); first candidate for 1.1 |
| **CSV** in Google's layout | `.csv`, `text/csv` | Google Password Manager, Chrome, Bitwarden, Proton Pass, 1Password, KeePassXC | favourite, dates, history, flags; extra fields travel inside the note | **1** |
| **JSON** in Bitwarden's layout plus our extension | `.json`, `application/json` | Bitwarden, Proton Pass, KeePassXC 2.7.7+, and this app without loss | nothing for this app; others drop what they lack | **1** |
| Import | CSV (Google/Chrome, Bitwarden, KeePassXC, any CSV with named columns), JSON (Bitwarden, ours), backup | | | **1** |
| KeePass `.kdbx` export; Bitwarden password-protected JSON; Credential Exchange | | | | later |

1. **JSON: Bitwarden's layout, not a schema of our own.** Bitwarden, Proton Pass and KeePassXC read it; unknown keys are ignored by all three (checked in their readers), so one `passwordVault` object per item carries what Bitwarden cannot hold, and the same file round-trips through this app without loss [S][X]. 1Password does **not** read unencrypted Bitwarden JSON [P]; it reads the CSV. This changes ux.md §5.20.3.
2. **CSV: Chrome's exact header and quoting, one row per website**, as Chrome's own exporter writes affiliated sites [S]; extra fields go in a marked block at the end of the note that this app reads back. Rows end in CRLF (ux.md); every importer checked accepts CRLF and LF [S]. No BOM.
3. **Excel: every cell a shared string with text format**, never inline strings: several small readers skip `inlineStr` cells [P: issue trackers]; shared strings are what Excel itself writes [M] and LibreOffice, Apache POI and openpyxl read ours [X]. This changes one line of ux.md §5.20.1.
4. **Excel with a password is feasible and small, but not in version 1** (architecture.md D-16): ECMA-376 Agile Encryption plus a Compound File writer is 215 lines with no dependency. LibreOffice 24.2, Apache POI 5.5.1 and msoffcrypto-tool 6.0.0 all open our files with the password, refuse a wrong one and refuse a tampered one [X]. Real Excel was not available here, and the file would be a second password for the user to keep. The code stays in `prototypes/formats/`; it is the first candidate for 1.1, after the user has opened a locked and a plain sample in their own Excel. Its key stretching is ~40 times cheaper to attack than our backup's, so the app would generate the file password; the user would never choose it (§7).
5. **Every export needs the master password** (lock.md F12); plain ones get the full-screen warning of ux.md §5.20 and only "Save", never the share sheet (architecture.md D-12).
6. **Imports never overwrite**: exact repeats are skipped, a same-site-and-username entry with another password is added beside the old one (ux.md §5.21), an incoming id is kept only when it is new (FR P1), and the whole import is one save; "Undo this import" puts back the vault as it was before (D-15).
7. **Plain exports leave out deleted accounts** (D-10e), and during a pending password change they carry the new password and keep the old one (D-10d: `pendingOldPassword` in our JSON, the hidden line `Old password` in the CSV block and in Excel).
8. **The CSV block of extra fields has a grammar** that every later version reads (D-10a, §4.4).

## 1. The request

"i want no account and everything on the phone, but i can copy or export as file like csv, json, xls or other."

- **csv** → Google's layout: their passwords most likely live in Google Password Manager today, and the same file goes back there or into any other manager.
- **json** → a JSON other managers read, which also keeps everything for this app.
- **xls** → `.xlsx`. The 1997 binary `.xls` is costly to write and nothing needs it (ux.md §5.20.1).
- **or other** → the backup file, the only format safe to keep; `.kdbx` later.
- **copy** → copying one field is the copy flow of ux.md §6. Copying the whole vault to the clipboard is not offered: keyboards and clipboard managers keep history [I].

## 2. Rules for every export

1. **Order**: Settings › Export → choose a format (ux.md §5.20 list) → plain formats: full-screen warning and "I understand" → **master password** (not PIN, not fingerprint), checked against the vault's own password slot (lock.md F12): the backup needs it to make a new key (crypto.md §11), and a plain export must not be one borrowed unlocked phone away → system "Save as" → write → read back → result screen. A plain export always has the password typed now; a backup may use the entry typed a moment before in the same flow, when the app offers "Make a backup now?" right after a master-password entry (lock.md F12 step 2). The typed password counts as practice. Neither a backup nor an export can be made while a new master password is pending (lock.md F11 step 10).
2. **Saving** goes only through the Storage Access Framework: `ActivityResultContracts.CreateDocument(mime)` with a suggested name; no storage permission (platform.md). Write straight into `contentResolver.openOutputStream(uri, "wt")`; never a plaintext temporary file in `cacheDir` or `filesDir`.
3. **Read-back check**: hash (SHA-256) and count the bytes while writing; reopen the document, hash again, compare. On mismatch delete the document and say "Couldn't save to this place. Try another folder." Providers have produced empty files before (KeePassDX #1594, #1620, #1680; offline.md).
4. **MIME type must match the extension.** Android's providers append the MIME type's own extension when the name's extension maps to a different type; `application/octet-stream` is exempt [S: AOSP `FileUtils.splitFileName`, android17-release and android-7.0.0_r1]. What the platform's table holds was measured with `MimeTypeMap` on the emulators (build plan 5.1). On Android 17 (image CE2A.260420.019) `.csv` has the type `text/comma-separated-values`, while `text/csv` maps back to `.csv`; `json`↔`application/json` and `xlsx`↔the spreadsheetml type hold both ways; `application/octet-stream` maps to `.bin`, which `splitFileName` exempts. On Android 7.0 and on Android 9 (image PSR1.180720.012, measured by the device check of round R2), where the table is libcore's `MimeUtils` (android-7.0.0_r1, as on API 24 to 28), `.csv` has the type `text/comma-separated-values`, and `text/csv`, `.json` and `application/json` have no mapping; `xlsx`↔the spreadsheetml type holds both ways there too, and `application/octet-stream` has no extension. The names of rule 5 come out right on all three, because `splitFileName` keeps a name when the type's own extension is the name's, or the type has none (FR P17.3); `DocumentNamesTest` checks that on every test device, and `tools/emu/checks/3C.sh files` reads the table again and compares it with this rule.
5. **Names** (local date, ASCII digits): `PasswordVault-backup-2026-10-06.pvault`, `passwords-2026-10-06.csv` (`-part1`, `-part2` when split), `passwords-2026-10-06.json`, `passwords-2026-10-06.xlsx`; later, with the locked workbook, `passwords-2026-10-06-locked.xlsx`. The name says what is inside so the user finds and deletes it [I].
6. **Sharing**: the share sheet (through a `FileProvider` copy in `cacheDir`, deleted afterwards) only for the backup (and, when it comes, the locked `.xlsx`). Plain files: the file picker's "Save" only (ux.md §5.20, architecture.md D-12).
7. **After a plain export**: ux.md's "Delete it now" (`DocumentsContract.deleteDocument`, API 19) and the banner that stays until the file is deleted.
8. **Text hygiene** (FR P7): input refuses unpaired UTF-16 surrogates along with control characters (ux.md §2), and before writing, one shared function used by every writer (`TextHygiene`) replaces any unpaired surrogate that still reaches it with U+FFFD and counts it, so that the export can say so instead of changing text silently. The `.xlsx` writer follows the same rule rather than writing `_xD800_`, which LibreOffice cuts the cell at and openpyxl shows literally. Without it, kotlinx-serialization writes a lone `\uD800` unescaped and the UTF-8 encoder turns it into `?` (0x3F) [X]; the same would happen to the vault body. ux.md's input rules (no control characters) make the rest of this section defence in depth.
9. **Portability on API 24**: no `java.util.Base64` and no `java.time` (both API 26 [S: android-37 `api-versions.xml`]) unless core-library desugaring is on; the prototype uses `kotlin.io.encoding.Base64` and a 53-line ISO 8601 helper. Format numbers and dates with `Locale.ROOT` [I]. A date is read with ASCII digits only, on every platform: a pattern writes `[0-9]`, never `\d`, which Android's engine (ICU) reads as any decimal digit (review of 3.15).
10. **Speed**: on this desktop JVM 5,015 entries take 3–17 ms to write as CSV, 47–102 ms to write or read as JSON, 42–80 ms to write as `.xlsx`, and 64–174 ms to encrypt the `.xlsx` (100,000 SHA-512 rounds) [X]. Phones are slower; run on a background dispatcher with "Saving…".
11. **Deleted accounts** (`deletedAt` set) are left out of CSV, JSON and Excel, so that an account the user deleted cannot come back from a plain file; the encrypted backup holds the whole vault, Recently deleted included (architecture.md D-10e, §4.12).
12. **A pending password change** (`changePending` with `pendingOldPassword`, ux.md §5.17): every plain format writes the new password as the password and keeps the old one: `passwordVault.pendingOldPassword` in our JSON (§5.2), the reserved hidden line `Old password` in the CSV block (§4.4) and in Excel's extra fields (§6.2). Importing our own file restores `pendingOldPassword` and sets `changePending` (architecture.md D-10d), so neither password is lost.

**Warning text** (full screen; ux.md §5.20 wording, translated into Uzbek and Russian):
- Title "This file will not be protected". Body "Anyone who opens this file can read all your passwords. It has no password."
- "Save it only where you control it: your own computer or USB stick." · "Don't send it by Telegram, email or other messengers." · "Delete it as soon as you're done."
- CSV adds: "Don't open this file in Excel: Excel changes phone numbers and codes and can run formulas. Choose Excel export instead." (Evidence in §4.6.)
- JSON adds nothing. Excel adds: "Google Sheets and other apps may keep a copy in their cloud." [I]
- Android 7–10 adds: "On this phone other apps with storage access can read files in Downloads." [I: scoped storage arrived in Android 10/11]

## 3. Backup file (`.pvault`): not prototyped

- **Layout**: the vault file of architecture.md §4, which replaces crypto.md §1 (`PVAULT01`, header length, JSON header, body nonce, AES-256-GCM body with the stored header bytes as associated data, the SHA-256 trailer), with **exactly one password slot** and no biometric slot. architecture.md §4.12 says how it is sealed: from the open vault in memory, with its own vault key, `vk_id`, salt and slot, Argon2id 64 MiB / 3 / 4, the vault's `vault_id` and the current generation number. Body: the whole vault of architecture.md §4.3, Recently deleted included, so it carries every field and every unknown key.
- **Header**: no entry count and nothing else about the contents (architecture.md D-17); the count is metadata for anyone who holds the file. The restore summary shows the count after the file has been opened, and the date from the header's `created`.
- **Opens**: only in this app (or with the decrypt script of build-plan package 8.3, which crypto.md §10 asks for), on any phone, with **the master password in use when the backup was made**. After a password change old backups keep the old password; restore says so (ux.md §5.2).
- **MIME** `application/octet-stream` (no extension appended [S]); import recognises the file by its first 8 bytes, not by name.
- **Lost**: nothing. **Warning**: none about plaintext; the "Backups explained" texts of ux.md §5.8 and §5.19.
- **Restore on a phone without a vault, or over the current one** (lock.md F13, F17 step 3): written as a new vault with a new `vault_id`, key and slot, keeping what architecture.md §4.12 lists.
- **A backup file into an existing vault** (ux.md §5.21): its entries only, by the merge rules of architecture.md §4.11: same id and same content skipped; same id, different content added with the title "Gmail (from backup)" (`EditTexts.fromBackup`); nothing overwritten.
- **Code**: reuses the vault writer and reader; about 80 lines of flow plus the restore screens [I]. No new dependency.

## 4. CSV in Google's layout

### 4.1 What Chrome writes [S: Chromium `components/password_manager/core/browser/export/password_csv_writer.cc`, `csv_writer.cc`]
- Header `name,url,username,password,note`, in that order; `name` is the site's display name, `url` the site (`android://<hash>@<package>/` for Android apps).
- One record **per affiliated domain** of a credential, sorted by name, url, username, password, note.
- A value containing CR, LF, `"` or `,` is wrapped in double quotes with inner quotes doubled; nothing else is quoted. Rows end in CRLF on Windows, LF elsewhere. UTF-8, no BOM.
- Google's Android export runs in closed Play-services code; its header is unconfirmed (account-based.md). Our importer matches columns by name, so it does not depend on it.

### 4.2 What Chrome and Google accept [S: `.../import/csv_password_sequence.cc`, `csv_password_iterator.cc`, `csv_field_parser.cc`, `csv_password.cc`, `password_importer.cc`, `core/common/password_manager_constants.h`, `ui/credential_utils.cc`; P: support.google.com/accounts/answer/10500247]
- **Columns** by header, trimmed and lower-cased: url | website | origin | hostname | login_uri; username | user | login | account | login_username; password | login_password; note | notes | comment | comments (the first in that order wins). **`name` is not read**: Chrome derives names from URLs. url, username and password columns are required.
- **Extra columns** are ignored. **Two columns with the same meaning** (say `url` and `website`) make the whole file fail, except note columns. So we never add columns to this layout.
- **UTF-8 BOM**: not removed anywhere on the import path. It becomes part of the first header name; in Google's layout that name is `name`, which Chrome ignores, so a BOM is harmless there but fatal when the first column is `url` [I from S]. We write none.
- **Line breaks inside quotes** are kept (the line splitter tracks quotes); a CR before the row's LF is dropped; leading spaces, tabs and CRs of every row are trimmed (so the first field must be quoted if it starts with a space; our writer does that); blank lines are skipped.
- **Per-row checks** (a failing row is listed, the rest imported): password not empty and ≤ 1,000 bytes of UTF-8; username ≤ 1,000 bytes; note ≤ 1,000 bytes (about 500 Cyrillic letters); URL valid `http(s)` or Android-app URL, ≤ 2,048. **Per file**: ≤ 1,000 KB and ≤ 3,000 rows, otherwise the whole import is refused. Google's help: "You can import 3,000 passwords at a time … split them into multiple .CSV files" and the account holds 10,000 [P].
- **Duplicates** are compared with what is already saved, not within the file: same site, username and password → counted as imported, not added (a non-empty note is appended to the saved note with a line break, up to 1,000 characters); same site and username, different password → listed as a conflict for the user to replace or skip, keeping the saved note.

### 4.3 Who else reads it
| App | Reads Google CSV? | Details |
|---|---|---|
| Bitwarden | yes, "Chrome (csv)" | name, url, username, password, note; `android://` → `androidapp://`; web vault, desktop, browser extension and CLI only (on a phone: a mobile browser or Credential Exchange) [S: `libs/importer/src/importers/chrome-csv-importer.ts`; P: bitwarden.com/help/import-data] |
| Proton Pass | yes | requires headers name, url, username, password (note optional); papaparse with `,`, trims headers (a BOM goes too), removes every CR inside values [S: WebClients `packages/pass/lib/import/providers/chromium/chromium.reader.ts`, `helpers/csv.reader.ts`]. Web, desktop and extension; the Android app only links to proton.me/support/pass-import [S: android-pass `ProfileScreen.kt:96,171`] |
| 1Password | yes, apps and 1Password.com [P: support.1password.com/import-chrome] | |
| KeePassXC | yes, CSV import dialog | auto-matches Group, Title, Username, Password, URL, Tags, Notes, TOTP, Icon, Last Modified, Created by name; for our file it fills Username, Password and URL, the user picks `name` as Title and `note` as Notes [S: `src/gui/csvImport/CsvImportWidget.cpp`] |
| KeePassDX | **no** | opens only KeePass files; no CSV reader in its source, issue #505 open since 2020 [S; offline.md] |

### 4.4 Our layout (exact)
```
name,url,username,password,note␍␊
Gmail,https://mail.google.com,aziz@gmail.com,"p,ss""word","Recovery phone below

--- PasswordVault (4 lines) ---
Title: Gmail
PIN code (hidden): 1234
Address: Toshkent, Chilonzor 5
  second line of the address"␍␊
```
- UTF-8, no BOM, CRLF after every row. Quote a value that contains `,` `"` CR or LF, or starts or ends with a space or tab; double inner quotes. Line breaks inside values are written as stored (LF).
- **One row per website** (an entry with none gets one row with an empty url), in vault order; deleted accounts have no row (§2 rule 11). `name` = title. URLs without a scheme that look like a host get `https://` (Chrome refuses `gmail.com`) [S].
- **note** = the user's notes, then the block of extra fields, whose grammar is fixed below (architecture.md D-10a, which replaces the unmarked block of the first version and settles FR P8). Every later version must read what 1.0 writes.

**The block** (D-10a):

```
note    = user-notes [ LF LF block ]                 ; written only when the block has at least one line
block   = marker 1*( LF line )
marker  = "--- PasswordVault (" count " lines) ---"  ; count: the lines after the marker, continuation lines included, ASCII digits
line    = label [ " (hidden)" ] ": " text *( LF "  " text )    ; later lines of a value are indented by two spaces
```

- **Order of lines:** `Title: <title>` when the title differs from the host of the first website (ux.md §5.20.2: Google ignores the `name` column); one line per extra field, in order; `Old password (hidden): <pendingOldPassword>` while a change is pending (D-10d). The host is `WebAddress.hostOf` of the first website (search.md §2), compared exactly. An entry without a website has no host to differ from and gets no `Title:` line (except by the rule below that keeps the user's text out of the block); Google refuses such a row anyway.
- **Labels.** Field names are trimmed when they are entered or imported, so a label never starts or ends with a space, and a line that starts with two spaces is always a continuation. A backslash makes the next character literal: a label is written with `\` as `\\`, `:` as `\:` and `(` as `\(`. So the first unescaped `: ` ends the label, and an unescaped ` (hidden)` at its end can only be the flag.
- **Reserved labels:** `Title` and `Old password`, only when written with no backslash. A user's own field with one of these names is written with its first letter escaped (`\Title`), and reads back as an ordinary field.
- **Recognised only when it is ours:** the marker must follow a blank line and `count` must equal exactly the number of lines after it, to the end of the note. Otherwise the whole note is text. A note that contains a pasted block therefore stays a note. The reader takes the last marker line that meets both conditions; if a line after it is neither a `line` nor a continuation, the whole note is text as well, so that nothing of a note is ever lost to the block.
- **The user's text never becomes fields** (architecture.md D-10a, added at the wave-0 integration): if the user notes alone would be read as notes and a block (they end with a marker that meets both conditions, followed only by well-formed lines), the writer appends a block of its own even when it has nothing else to write: then with the `Title:` line, whatever the host, and also for an entry without a website. The reader takes the writer's marker, the last one that qualifies, and the user's lines stay in the notes (vector 8).
- **Empty user notes:** `user-notes` may be empty, and the two LFs are written all the same, so the marker always follows a blank line: the note column of an entry with fields and no notes starts with two line feeds.
- **Reading back:** user notes = everything before the LF LF that precedes the marker; a value's continuation lines are joined to it with LF, without their two spaces; `Title:` gives the entry's title, in place of the `name` column; `Old password` restores `pendingOldPassword` and sets `changePending` (D-10d).
- **What the grammar leaves open,** as version 1's reader settles it (`FieldsBlock`; every later version reads the same): the count is written as a number from 1 without a leading zero, and no other spelling is a marker; CRLF and a lone CR in a note count as LF, so that a file another program saved again still reads, and the notes come back with LF; a reserved label is reserved with or without ` (hidden)` and wherever its line stands, the first `Title` line giving the title and the first `Old password` line the old password, while a second line with the same label is read as an ordinary field, so that nothing is dropped; a label may be empty, and so may a value.
- The marker, `(hidden)` and the two reserved labels stay in English in every language, because the importer reads them.

**Vectors.** The note column the writer produces (`␊` is a line feed) for a stored entry, which reading it back must give again exactly. The first four are the cases of FR P8, 5 to 7 the reserved labels and an escaped backslash, 8 a user's note that would read as a block.

| # | Stored entry | Note column | Reads back as |
|---|---|---|---|
| 1 | Title `Bank`, website `https://bank.uz`, notes `Branch: Chilonzor`, visible field `Q: mother's name` = `Aziza` | `Branch: Chilonzor␊␊--- PasswordVault (2 lines) ---␊Title: Bank␊Q\: mother's name: Aziza` | The field `Q: mother's name` = `Aziza`, not `Q` = `mother's name: Aziza`; the notes' own `Branch: Chilonzor` stays in the notes |
| 2 | Title `Bank`, website `https://bank.uz`, no notes, visible field `Code (hidden)` = `77`, hidden field `PIN` = `1234` | `␊␊--- PasswordVault (3 lines) ---␊Title: Bank␊Code \(hidden): 77␊PIN (hidden): 1234` | A visible field `Code (hidden)` and a hidden field `PIN` |
| 3 | A field typed as `  B` = `x` | stored as `B` (trimmed at input), written `B: x` | `B` = `x`, never glued onto the previous value |
| 4 | Title `mail.ru`, website `https://mail.ru`, notes `Old notes:␊␊--- PasswordVault ---␊PIN: 9999`, no fields | the notes unchanged: no block, because it would have no line (the title equals the host) | Notes only; no field, since `--- PasswordVault ---` has no count. The same for a pasted `--- PasswordVault (1 lines) ---␊PIN: 9999␊more text`, whose count does not reach the end |
| 5 | Title `Telegram`, website `https://web.telegram.org`, visible fields `Title` = `Mr` and `Old password` = `x` | `␊␊--- PasswordVault (3 lines) ---␊Title: Telegram␊\Title: Mr␊\Old password: x` | Title `Telegram`; two ordinary fields named `Title` and `Old password` |
| 6 | Title `Gmail`, website `https://mail.google.com`, password `N3w!pass` with a change pending, `pendingOldPassword` `Old#2`, hidden field `PIN` = `1234`, no notes | `␊␊--- PasswordVault (3 lines) ---␊Title: Gmail␊PIN (hidden): 1234␊Old password (hidden): Old#2`; the password column holds `N3w!pass` | Password `N3w!pass`, `pendingOldPassword` `Old#2`, `changePending` set, the field `PIN` |
| 7 | Title `Server`, website `https://srv.example`, visible field `C:\keys` = `line 1␊line 2` | `␊␊--- PasswordVault (3 lines) ---␊Title: Server␊C\:\\keys: line 1␊  line 2` | The field `C:\keys` with its two lines |
| 8 | Title `mail.ru`, website `https://mail.ru`, notes `Old notes:␊␊--- PasswordVault (1 lines) ---␊PIN: 9999`, no fields | `Old notes:␊␊--- PasswordVault (1 lines) ---␊PIN: 9999␊␊--- PasswordVault (1 lines) ---␊Title: mail.ru`: a block of the writer's own, although the title equals the host, because the notes alone would read as one | Title `mail.ru`; the notes exactly as stored; no field |

- **Splitting**: when the file would exceed 3,000 rows or 1,000 KB, write `-part1`, `-part2`, … (one "Save as" per part); an entry's rows never straddle parts [X: 5,015 entries → 3,000 + 2,017 rows].

### 4.5 What is lost; what the user is told first
- Kept for this app: title, username, password, all websites (adjacent rows are merged back, up to 10, §8.3), notes, extra fields with their hidden flag [X], and a pending change's old password with `changePending` (D-10d). Lost: favourite, the three dates, previous passwords, `checkIgnored`, ids.
- In Google: one credential per website; the fields block shows as note text, and the title survives only in its `Title:` line.
- **Summary card before saving** (counts from `GoogleCsv.report`): accounts without a website and without a password (Google skips them), notes over 1,000 bytes (Google skips the row; the fields block counts), number of files, "Previous passwords and favourites are not in CSV. JSON keeps everything." Its counts follow Chrome's own checks (FR P15): the scheme is compared without regard to case (`HTTPS://MAIL.RU/` is accepted); a URL's length is measured as Chrome measures it, after punycode and percent-encoding (a 1,018-character Cyrillic path is over 2,048); a host with characters Chrome refuses and an `android://` link without the `<hash>@<package>/` shape are counted as refused; and a part that is still over 1,000 KB is counted and named.

### 4.6 Spreadsheet formulas: write values exactly, warn
- In LibreOffice 24.2's default CSV import our row became a live formula (`=HYPERLINK(…)`) and the username `+998901234567` the number 998901234567 [X]. OWASP lists `=` `+` `-` `@` tab CR LF and full-width forms as formula starters, and notes that Excel may drop protective quotes on re-save [P: owasp.org CSV_Injection].
- Prefixing `'` would corrupt the passwords this file exists to carry; Chrome and Bitwarden do not prefix either [S]. So values are written exactly, the warning says not to open the file in Excel, and the Excel export exists for reading [I].
- A CSV without BOM opened by Excel on Windows is also decoded in the ANSI code page, which garbles Cyrillic and oʻ [M].

### 4.7 Size
CSV writer 15 lines, Google layout with split and report 60, fields block 35 [X: prototype counts]. No dependency.

## 5. JSON in Bitwarden's layout with our extension

### 5.1 Why this and not our own schema
| Reader | Unencrypted Bitwarden JSON | Evidence |
|---|---|---|
| Bitwarden | yes; keeps the first 5 previous passwords of each item (we write newest first) | [S: `libs/importer/src/importers/bitwarden/bitwarden-json-importer.ts`, `isUnencrypted()`] |
| Proton Pass | yes; refuses `"encrypted": true`; needs an `items` array, and treats a missing `folders` as empty but refuses a `folders` that is not an array, such as `null` (FR P17.2); reads name, notes, login, URIs, TOTP, fields; not favourite, dates or history | [S: `providers/bitwarden/bitwarden.reader.ts`] |
| KeePassXC | yes since 2.7.7 (2024-03-09); dates and history since 2.7.11; nested folders 2.7.12; needs the `folders` and `items` keys; hidden fields become protected attributes; favourite becomes the tag "Favorite" | [S: `src/format/BitwardenReader.cpp`, CHANGELOG] |
| 1Password | **no**: "Unencrypted JSON and account restricted exports aren't currently supported"; it takes password-protected Bitwarden JSON or CSV | [P: support.1password.com/import-bitwarden] |
| Keyguard | no file import; works on a Bitwarden account or a KDBX file | [P: keyguard.dev/docs/migrating] |
| KeePassDX | no | §4.3 |

Our own schema would be read by nobody else and adds nothing the backup does not already give. Bitwarden's layout holds every v1 field except `passwordChangedAt`, the two flags, a pending change's old password and the original Android URLs; those go into a `passwordVault` object, which all three readers ignore because they map known keys only. The importer is needed anyway to bring passwords in from Bitwarden, so the export side costs 49 lines [X].

### 5.2 Layout (exact)
UTF-8 without BOM, two-space indent, LF, final newline; strings escaped by kotlinx-serialization (`"` `\` and U+0000–U+001F; everything else raw UTF-8) [X].
```json
{
  "encrypted": false,
  "passwordVault": {"format": "passwordvault-export", "version": 1,
                    "exportedAt": "2026-10-06T07:05:09.123Z", "generator": "PasswordVault 1.0"},
  "folders": [],
  "items": [
    {
      "id": "<entry uuid>", "organizationId": null, "folderId": null, "type": 1, "reprompt": 0,
      "name": "Gmail", "notes": "…" | null, "favorite": true,
      "fields": [{"name": "PIN code", "value": "4321", "type": 1, "linkedId": null}],
      "login": {"fido2Credentials": [], "uris": [{"match": null, "uri": "https://mail.google.com"},
                {"match": null, "uri": "androidapp://com.google.android.gm"}],
                "username": "aziz@gmail.com" | null, "password": "…" | null, "totp": null},
      "passwordHistory": [{"lastUsedDate": "2026-09-06T07:05:09.123Z", "password": "Old#2"}] | null,
      "creationDate": "…", "revisionDate": "…", "deletedDate": null, "collectionIds": null,
      "passwordVault": {"passwordChangedAt": "…", "changePending": true, "checkIgnored": true,
                        "pendingOldPassword": "Old#3" | null,
                        "urls": ["https://mail.google.com", "android://<hash>@com.google.android.gm/"]}
    }
  ]
}
```
- Times: ISO 8601 UTC with milliseconds, as Bitwarden writes them. Field `type` 1 = hidden, 0 = text. Empty strings are written as `null`, as Bitwarden does. `folders` is always present and empty (v1 has no folders).
- Android links are written in Bitwarden's form `androidapp://<package>`; the original Chrome form is kept in `passwordVault.urls` only when it differs.
- `passwordVault` in an item also carries, unchanged, any key a newer app version wrote (storage.md's "preserve unknown keys") [X: a `futureField` survived the round trip]. The top-level `passwordVault` object carries the vault's own such keys too when this app exports (FR P11). An import does not take the top-level ones into the vault: the preview lists them, once, as "Some data from a newer version of the app will not be imported." (build-plan §12, decision 9); the keys of an item are kept with its account as before.
- **A pending change** (architecture.md D-10c, D-10d): `login.password` is the new password and `passwordVault.pendingOldPassword` the old one, `null` when no change is pending. **Deleted accounts** are not written (D-10e), so `deletedDate` is always `null` and `deletedAt` is never written.
- **Import**: `passwordVault.format` identifies our own file; then the extension wins, and `pendingOldPassword` comes back with `changePending` (D-10d). **Ids** (FR P1, architecture.md D-15): an incoming id is kept only if it is a valid UUID that is neither in the vault nor earlier in the same file; otherwise the item gets a new id and the rules of §8.4 decide: an exact repeat is skipped, and an entry that differs from the one holding that id is added beside it with the title "Gmail (from file)", as a backup's entries are (§3). In foreign files, ids are new, a folder becomes a visible field "Folder", `login.totp` a hidden field "TOTP" (v1 has no one-time codes), cards, identities and SSH keys become entries whose fields are the item's strings (hidden when the key looks secret), linked fields and trashed items are dropped and listed, and history is cut to the newest 5. `"encrypted": true` is refused with "This Bitwarden file is encrypted. In Bitwarden, export again and choose '.json', not '.json (Encrypted)'."
- **Reading the file** (FR P2, P3, P11): JSON is UTF-8 by definition (RFC 8259 §8.1), so it is decoded strictly and refused on a bad byte, never re-read as Windows-1251; every leading U+FEFF is removed first. It is parsed by `StrictJson`, which refuses a duplicate key and nesting deeper than 32 levels (our format needs 5) before a deep structure can overflow the stack, and catches `StackOverflowError` as well. Only the boolean `true` marks a file as encrypted (`"encrypted": "true"` is not refused, as in Bitwarden's `isUnencrypted`). The 10,000-row limit of §8.1 applies to `items`, while the file is parsed: `StrictJson` keeps the first 10,000 elements of the top-level `items` array and only counts the rest, so a file of millions of tiny items is refused, with its count, without the memory of the tree it would refuse (a 10 MB file of 5,000,000 items took 307 MB on Android 17 before; review of 3.15). An item without a name gets a title as on the CSV path (§8.3). The `login` object is read whatever the item's type, so an edited secure note keeps its username and password (a password is never dropped, §8.4); a password that is neither text nor a number (a list, an object, `true` or `false`) is no password the app could keep: the account is imported without it and listed ("the password in the file is not text and was not imported"). Everything not imported is listed, never dropped silently: by name where there is one, with "passkeys are not imported" for `login.fido2Credentials`, and by position for an element of `items` that is `null`, a number or a string.

### 5.3 Lost; warning; size
- This app: nothing but deleted accounts, which no plain export carries [X: 5,011 entries equal after export and import]. Bitwarden: the extension values. Proton Pass: also favourite, dates, history. KeePassXC: the extension values.
- Warning: §2. Size: export 49 lines, import about 85; dependency kotlinx-serialization-json 1.11.0, which storage.md already chose (platform `org.json` would also do).

## 6. Excel (`.xlsx`)

### 6.1 Parts (all needed; nothing else)
| Part | Content |
|---|---|
| `[Content_Types].xml` | defaults for `rels` and `xml`; overrides for workbook, sheet1, styles, sharedStrings |
| `_rels/.rels` | officeDocument → `xl/workbook.xml` |
| `xl/workbook.xml` | one sheet "Passwords" (localised, ≤ 31 characters, none of `[]:*?/\`); defined names `_xlnm.Print_Titles` = `'Passwords'!$1:$1` (header on every printed page) and hidden `_xlnm._FilterDatabase` for the filter |
| `xl/_rels/workbook.xml.rels` | worksheet, styles, sharedStrings |
| `xl/styles.xml` | 2 fonts (Calibri 11, bold), 3 fills (none and gray125, which Excel expects first [M], then light blue), 1 border, 3 cell formats: default, header (bold, shaded, text), data (text format 49 `@`, wrapped, top-aligned) |
| `xl/worksheets/sheet1.xml` | in schema order: `sheetPr` (fit to page) · `dimension` · `sheetViews` (pane `ySplit="1"` frozen, selection A2) · `sheetFormatPr` · `cols` (widths) · `sheetData` · `autoFilter` · `printOptions` (grid lines) · `pageMargins` · `pageSetup` (A4, landscape, one page wide) · `headerFooter` (`&L` app name `&R&D`, `&C&P / &N`) · `ignoredErrors numberStoredAsText` (no green triangles on codes and phone numbers) |
| `xl/sharedStrings.xml` | `<si><t xml:space="preserve">…</t></si>`, with correct `count` and `uniqueCount` |

No `docProps` (no author or time in the file). Zip entries are deflated with one fixed **local** time, 1980-01-01 00:00 (`entry.time = GregorianCalendar(1980, 0, 1).timeInMillis`, in the default zone; `setTimeLocal` would need `java.time`), and carry no extra field with a time. `ZipEntry.setTime` turns an instant into the DOS time through the default zone, so the prototype's fixed instant came out as 12:00, 17:00 or 07:00 in UTC, Tashkent or New York and revealed the phone's offset (FR P9). With the local time every phone writes the same bytes, and an unchanged vault gives identical files.

### 6.2 Cells
- **Every cell is a shared string** (`t="s"`, style "@"). Inline strings work in Excel [M] and LibreOffice (ux.md's test), but exceljs (#1575), XLSX.jl (#123), akeneo spreadsheet-parser (#46) and others show them empty [P: their issue trackers]; Excel itself writes shared strings [M]. Cost: one map from string to index.
- **No formula risk**: a shared string is never evaluated; the writer never emits `<f>`, `t="str"`, hyperlinks or external relationships. Format "@" keeps a cell text if the user edits it in Excel [M]. Readers saw 0 formula cells and `=HYPERLINK(…)`, `+998901234567`, `-2+3`, `@SUM(A1:A2)` as text [X].
- **Columns** (ux.md §5.20.1, labels localised): Name 24 · Username 26 · Password 22 · Website 30 · Other websites 24 (one per line) · Notes 40 · Extra fields 30 (`Label (hidden): value` per line) · Favourite 10 (localised "Yes") · Added 16 · Last changed 16 · Password changed 16. Widths are in characters. Dates are text `2026-10-06 14:30` in the phone's zone, which sorts correctly. Android links show as `app: com.example`. One row per account; deleted accounts have no row (§2 rule 11).
- **Hidden fields are shown in clear** (architecture.md D-17): the file exists to be read and printed, and a value shown as dots would make the printout incomplete. "(hidden)" after the label still tells which ones are secret.
- **A pending change** (D-10d): the Password column holds the new password, and the Extra fields cell ends with the line `Old password (hidden): <old password>`, its label localised like the others.
- **Escaping** in element content: `&` `<` `>` as entities (`"` only in attributes). Line breaks are LF; CRLF and lone CR become LF.
- **Characters XML 1.0 forbids** even as `&#…;`: U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+FFFE and U+FFFF. They are written with OOXML's `_xHHHH_` escape (`_x0001_`), and a literal `_xHHHH_` in the data has its underscore escaped as `_x005F_`, as Excel does [P, FR P17.4: ECMA-376 Part 1 §22.4.2.4 states the rule; §22.9.2.19 (ST_Xstring), which this text cited first, does not]. Unpaired surrogates never get here: `TextHygiene` has already replaced them with U+FFFD (§2 rule 8, FR P7). LibreOffice and Apache POI decode both escapes. openpyxl 3.1.5 decodes neither: it leaves `_x0001_` as written and deletes every `x005F_` it finds, so a password `x005F_` reads back empty (FR P17.5) [X; S: `openpyxl/reader/strings.py`]. ux.md refuses control characters on input, so this is defence in depth, except for a password imported with one (§8.4).
- **Limits** [P: Microsoft "Excel specifications and limits"]: 32,767 characters per cell, 253 line feeds per cell, 255 characters in a header or footer, row height 409 points. Cells longer than 32,767 are cut with `[… 7,233 more characters: Excel shows at most 32,767 in one cell]`; line breaks after the 253rd become ` ↵ `. With ux.md's limits (notes 10,000; fields 20 × 1,000) neither happens; both are guards [X: a 40,000-character note and a 300-line note].
- **Printing** [X: LibreOffice PDF]: landscape A4, header row repeated on every page, grid lines, page numbers, Uzbek, Cyrillic, Hebrew/Arabic (right-aligned) and emoji rendered. A cell taller than one page is cut on paper; the full text stays in the file.

### 6.3 Lost; warning; size
Lost: previous passwords, `checkIgnored`, ids, the hidden flag's protection (values shown in clear, D-17); a pending change shows only as its `Old password` line. Not meant for importing back (§8 refuses spreadsheets). Warning: §2. Size: 170 lines generic writer + 34 lines vault sheet; no dependency (`java.util.zip`, API 1).

## 7. Excel with a password (ECMA-376 Agile Encryption)

**Not in version 1** (architecture.md D-16). Nothing here could run Microsoft Excel, and the file would be a second password for the user to keep. The code and its checks stay in `prototypes/formats/` (`OfficeCrypto.kt`, `validate/check_protected.py`, `validate/lo_read.py`, `validate/PoiCheck.java`, and formats-review's own `validate/agile_check.py` and `validate/uno_open.py`; `README.md` there lists them); the prototype's locked samples are in `tools/validate/samples/prototype/`. It is the first candidate for 1.1, after the user has opened a locked and a plain sample in their own Excel. This section is kept for that.

### 7.1 What it is
What Excel's "Encrypt with Password" writes: the finished `.xlsx` bytes encrypted and stored in a Compound File Binary container. Reference: the encrypted workbook in msoffcrypto-tool's tests (`tests/inputs/example_password.xlsx`), whose container carries times on storages only, the pattern of Windows structured storage, so very likely saved by Excel [I]. Its parameters, which this design copies [X]: SHA-512, spin count 100,000, AES-256-CBC, 16-byte salts.

### 7.2 Construction [M: [MS-OFFCRYPTO] 2.3.4.10–2.3.4.15; S: msoffcrypto-tool `method/ecma376_agile.py`; X: the reference workbook]
1. Random: key-data salt (16 B), password salt (16 B), **secret key** (32 B), verifier input (16 B), HMAC key (64 B). The 64-byte HMAC key departs from [MS-OFFCRYPTO] 2.3.4.14 step 2, where the salt used as the HMAC key has `KeyData.saltSize` bytes, 16 here (FR P17.1). It follows what Office writes: the reference workbook stores a 64-byte key and its HMAC verifies only with all 64 bytes. The release check in real Excel must cover it.
2. **EncryptedPackage** stream: u64 LE plaintext length, then the package in 4,096-byte segments, each AES-256-CBC under the secret key with IV = SHA-512(key-data salt ‖ u32 LE segment number)[0..16); the last segment is zero-padded to 16 bytes.
3. **Data integrity**: HMAC-SHA512 with the HMAC key over the **whole** EncryptedPackage stream including its length field; the HMAC key and value are each AES-CBC-encrypted under the secret key with IVs SHA-512(key-data salt ‖ block key)[0..16), block keys `5fb2ad010cb9e1f6` and `a0677f02b22c8433`.
4. **Password key**: H = SHA-512(password salt ‖ UTF-16LE(password)), then for i = 0…99,999: H = SHA-512(u32 LE i ‖ H); key_b = SHA-512(H ‖ block key_b)[0..32). With IV = the password salt: encrypt the verifier input (block key `fea7d2763b4b9e79`), SHA-512(verifier input) (`d7aa0f6d3061344e`) and the secret key (`146e0be7abacd0d6`).
5. **EncryptionInfo** stream: u16 4, u16 4, u32 0x40, then UTF-8 XML: `<encryption xmlns=…/encryption xmlns:p=…/keyEncryptor/password xmlns:c=…/certificate>` with `<keyData saltSize="16" blockSize="16" keyBits="256" hashSize="64" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC" hashAlgorithm="SHA512" saltValue="…"/>`, `<dataIntegrity encryptedHmacKey="…" encryptedHmacValue="…"/>` and, inside a `<keyEncryptors>` element (which the code writes and the first version of this text left out, FR P17.6), one `<keyEncryptor uri="…/keyEncryptor/password"><p:encryptedKey spinCount="100000" … saltValue encryptedVerifierHashInput encryptedVerifierHashValue encryptedKeyValue/></keyEncryptor>`; Base64 with padding.
6. **Container** [P: [MS-CFB]]: version 3, 512-byte sectors, 64-byte mini sectors, streams under 4,096 bytes in the mini stream (all but the package; an empty vault's 3,368-byte package too), FAT plus DIFAT for any size, free directory entries zero with sibling and child set to NOSTREAM, siblings as a balanced search tree (shorter name first, then upper-cased UTF-16 compare) all black, which MS-CFB calls "the simplest implementation". Eleven entries, as in the reference workbook: Root Entry › EncryptionInfo, EncryptedPackage, `\x06DataSpaces` › Version, DataSpaceMap, DataSpaceInfo › StrongEncryptionDataSpace, TransformInfo › StrongEncryptionTransform › `\x06Primary`. The four DataSpaces streams are fixed bytes and **byte-identical to the reference workbook's** [X].

### 7.3 Checked [X]
| Reader | Right password | Wrong password | One bit flipped in the package | Cells |
|---|---|---|---|---|
| LibreOffice 24.2 (UNO, private profile) | opens, 2.6–3.9 s for 5,016 rows | refused | refused | 55,176 identical |
| Apache POI 5.5.1 | "agile, spin 100000, aes256, sha512", opens | refused | — | 55,176 identical |
| msoffcrypto-tool 6.0.0 | password and HMAC verified; decrypted bytes equal our `.xlsx` | refused | integrity failure | — |

Also passed: a password with U+2018 and Cyrillic, and the empty vault. **Not checked**: Microsoft Excel on Windows, Mac, Android or iOS; WPS; Apple Numbers. Google Sheets reportedly cannot open password-protected files [secondary sources only].

### 7.4 How strong, how big, how risky
- **Attack cost**: one RTX 4090 tries 68,356 Office-2013 passwords per second (hashcat 9600, 100,000 rounds) [P: Chick3nman benchmark gist], against about 1,700 per second for our backup's Argon2id (crypto.md §2). A reused human password from the first 10⁹ guesses falls in about 4 hours; five random words (64.6 bits) need about 6 million GPU-years on average [I].
- **So the password is generated**: five lowercase EFF words joined by hyphens, ASCII only (Office hashes the exact UTF-16 text, so oʻ typed on a computer may differ from the phone's ‘), shown large with "Copy" and "New words", and offered to save as an entry "Excel file password, 2026-10-06". The user cannot type their own, so the master password is never reused for a 40-times-weaker file.
- **Size**: 94 lines of encryption and 121 of container on top of the Excel writer; `javax.crypto` and `MessageDigest` only, all API 1. Unit tests can decrypt with Apache POI on the JVM (test scope only, Apache-2.0).
- **Risks**: real Excel untested (release check below); a second password for the user to keep; Excel re-saves without a password in one click; not for Google Sheets.

## 8. Import

### 8.1 Flow (ux.md §5.21)
Sources screen → file picker (`OpenDocument("*/*")`; providers label CSV as `text/csv`, `text/comma-separated-values` or `application/vnd.ms-excel`, so the content decides) → read in the background → preview → "Import 45 accounts" → one vault save → "Undo this import" → for plain files "Delete file". Limits: 10 MB and 10,000 rows (for JSON, 10,000 items, FR P11). The preview never shows a password. It estimates the size of the sealed vault after the import and refuses an import that would not fit, with the sentence of architecture.md §3.5 ("Your passwords no longer fit in one file. …"). "Undo this import" saves the vault as it was before the import (architecture.md D-15, FR P1), not "remove the added ids".

### 8.2 Recognising the file
| First bytes or shape | Result |
|---|---|
| `PVAULT01` | backup restore (§3) |
| `PK\x03\x04` or `D0 CF 11 E0` | "This is an Excel file. In Excel choose File › Save As › CSV UTF-8, then import that file." |
| `03 D9 A2 9A` | "This is a KeePass database. In KeePassXC: Database › Export › CSV." |
| text starting with `{` | Bitwarden JSON; ours when `passwordVault.format` is `passwordvault-export` |
| other text | CSV |

**Text decoding** (FR P2, P12), one `TextDecoding` for every file, which never switches a file silently:
- JSON: strict UTF-8 only, refused on a bad byte (§5.2).
- A UTF-8 BOM is removed; UTF-16 LE/BE is recognised by its BOM (Excel's "Unicode text") and, without one, by NUL bytes at one parity only: at least two of them, in at least one of every 16 characters of the first 8 KB, with at most one at the other parity for every 16 at that one (little-endian when the NULs are the odd bytes); a UTF-8 or Windows-1251 text has no NUL byte. Every branch uses a reporting decoder and counts replaced bytes; a BOM in front of bytes that are not valid in its encoding is reported, not decoded leniently.
- CSV without a BOM: UTF-8 when every byte is valid UTF-8, and, when some are not, by proportion (build-plan §12, decision 12): UTF-8 when the valid multi-byte UTF-8 sequences outnumber the bytes that are not UTF-8; the bad bytes then become U+FFFD and the preview lists the affected lines. Otherwise Windows-1251, what Russian-language Excel writes for "CSV (comma delimited)" [I]. One stray byte in a UTF-8 file must never turn every Cyrillic password into mojibake, as it did in the prototype; and a Windows-1251 file must not be read as UTF-8 because a capital letter followed by ё or ў ("Пётр" is CF B8, "Тўлов" starts D2 A2) happens to be a valid two-byte UTF-8 sequence, as the first rule (UTF-8 whenever any valid sequence occurs) did (review of 3.15).
- The preview says which encoding was chosen and why, with "Text looks wrong? Read as UTF-8" (or "… as Windows-1251").

**`sep=` line** (FR P13): a first line `sep=X`, which Excel writes, is honoured as the delimiter and skipped. **Delimiter** otherwise: whichever of `,` `;` tab splits the header line (outside quotes) into most fields; Excel uses `;` where the list separator is `;` [M]. **Parser**: RFC 4180, CRLF/LF/CR row ends, quoted fields with delimiters, `""` and line breaks; text after a closing quote kept and a quote inside an unquoted field literal (Excel's behaviour); an unclosed quote reported with its line number; blank rows skipped. **Row length** (FR P4): every row whose number of values differs from the header's is reported, for example "Line 2 has 7 values; the header has 5. Is there a comma inside a password? Put that value in quotes."; the preview shows those rows, and they are left out unless the user confirms them.

**JSON depth** (FR P3): `StrictJson` refuses nesting deeper than 32 levels before parsing, and `StackOverflowError` is caught as well; a 4 KB file of nested arrays must not crash the app.

### 8.3 Formats and mappings
| Format, recognised by | Mapping |
|---|---|
| **Google/Chrome**, and **our CSV**: header has name, url, username, password | name→title (empty: host of the URL, the app package, or the username), url, username, password, note; adjacent rows equal except url merge into one entry, up to 10 websites, after which a new entry starts (FR P5, architecture.md D-10b: nothing else is joined, and related websites follow ux.md §5.21 rules (a) to (c)); a block of §4.4 at the end of the note, recognised only when it is ours, becomes extra fields, its `Title:` line the title and its `Old password` line the pending change |
| **Bitwarden CSV**: `login_uri`/`login_username`/`login_password` [S: `bitwarden-csv-importer.ts`; exporter header folder, favorite, type, name, notes, fields, reprompt, archivedDate, login_uri, login_username, login_password, login_totp] | `login_uri` is itself a CSV list; `fields` lines `name: value` split at the last `: `; `type=note` rows have empty login columns, and one whose columns are not empty keeps its username and password; favorite `1`; folder → field "Folder"; `login_totp` → hidden field "TOTP" |
| **KeePassXC CSV**: Group, Title, Username, Password, URL, Notes, recognised whatever their case (FR P6) [S: `CsvExporter.cpp`; all fields quoted, LF, ISO dates] | Group without the root name → field "Folder"; TOTP → hidden field; Last Modified, Created → dates; Icon ignored |
| **Firefox CSV**: url, username, password, httpRealm, formActionOrigin, guid, timeCreated, timeLastUsed, timePasswordChanged (FR P13) | guid, httpRealm and timeLastUsed ignored; timeCreated and timePasswordChanged read as dates in epoch milliseconds; formActionOrigin kept as a second website only when its host differs from the url's |
| **Any CSV with named columns** | aliases below; a column with no meaning becomes an extra field named by its header, hidden unless the label is harmless (email, phone, question, folder…) as ux.md §2 asks. A secret word counts wherever it stands inside a word ("EmailPassword", "userpin", "ПочтаПароль" are hidden), and a word that starts with "номер" or "nomer" is a number, not a name ("Номер карты" is hidden) (review of 3.15) |
| **Bitwarden JSON**, **our JSON** | §5.2 |

**Header aliases.** A header is compared after case, spaces, `_`, `-`, `.` and every leading BOM are ignored, every apostrophe-like character (the list of search.md §1 step 1) is dropped, and every Unicode space counts as a space (FR P6): so `Oʻzgartirilgan`, `O‘zgartirilgan` and `O'zgartirilgan` are one header, and `User Name` with a no-break space is `user name`. Chromium's list first:
- website: url, website, origin, hostname, login_uri, uri, web site, site, address, link, сайт, адрес, ссылка, веб-сайт, sayt, manzil, havola, veb-sayt, манзил, ҳавола
- username: username, user, login, account, login_username, user name, email, e-mail, логин, имя пользователя, пользователь, почта, электронная почта, foydalanuvchi, pochta, elektron pochta, фойдаланувчи, фойдаланувчи номи, электрон почта
- password: password, login_password, pass, passwd, pwd, пароль, parol, парол
- notes (Chromium's priority note › notes › comment › comments): extra, description, заметка, заметки, примечание, комментарий, описание, izoh, eslatma, qayd, sharh, изоҳ, эслатма
- title: name, title, item name, название, наименование, nomi, nom, sarlavha, номи, сарлавҳа
- also: totp; folder/group (папка, группа, papka, guruh, гуруҳ); favourite; created (creationdate, creation time, created at, создано, yaratilgan); modified (last modified, revisiondate, updated, lastmodified, изменено, oʻzgartirilgan).

The Uzbek Cyrillic aliases are those of FR P6; with them the header `Номи,Сайт,Фойдаланувчи,Парол,Изоҳ` (`core/src/test/resources/formats/uzbek-cyrillic-headers.csv`) maps every column. A second column with a meaning already taken becomes an extra field (Chromium would refuse the file). **The column-mapping screen** of ux.md §5.21 opens, pre-filled from this table, whenever no password column is recognised (FR P6: the prototype went on and imported every entry without its password), and also with an ambiguity the user should see.

### 8.4 Rules
- **Control characters** (ux.md §2): removed and counted in titles, usernames, websites, notes and fields ("6 invisible characters were removed"). **Never in a password** (FR P14, architecture.md D-15): websites compare passwords exactly, so a password is never altered on import; an entry whose password holds a tab or another control character is imported as it is and listed by name ("Gmail: the password contains a tab").
- **Over a limit** (architecture.md D-10f, FR P5): an over-limit value never drops an entry. A note over the limit is cut, and the entry is imported and listed. Other overflow moves into the notes or the extra fields: websites beyond 10, visible fields beyond 20, a website longer than 2,048 characters; whatever still does not fit is cut, and each case is listed ("Gmail: 3 websites moved to notes"). A hidden value never moves into the notes, where it would show unmasked: a hidden field whose value is over 1,000 characters, and a hidden field beyond the 20 an account holds (of more than 20 fields the hidden ones are kept first), is not imported, and its account lists it ("Gmail: 1 hidden value could not be kept hidden and was not imported"; build-plan §12, decision 20). Only a password over 1,000 characters makes the entry be left out, listed by name.
- **Ids** (FR P1): an incoming id is kept only when it is a valid UUID that is neither in the vault nor earlier in the same file (§5.2); every other entry gets a new one. No import ever leaves two entries with one id.
- **Exact repeats** (same website host, same username, same password, and nothing new in notes or fields): skipped, against the vault ("3 are already here") and within the file.
- **Same website and username, different password**: added beside the existing entry ("added so you can compare", ux.md §5.21). Nothing combines the two afterwards: the security check does not merge accounts (ux.md §5.16, critique M6). A later "Use the new password" can move the old one into history (`ImportPlanner.replacePassword`).
- **Different websites, same username and password**: kept apart; that is the reuse this app exists to fix.
- **No password**: imported and counted (SMS-login accounts, notes).
- **A hidden value without a name** (a hidden field of a JSON item, a hidden line of our CSV block, or a column whose header is empty) is kept as a hidden field named "Hidden value 1", "Hidden value 2", in the file's order within its account (`EditTexts.hiddenValue`, in the user's language; build-plan §12, decision 11). It never moves into the notes for want of a name, where it would show unmasked; a visible value without a name moves into the notes as before. The limits of D-10f still apply to it as to any hidden field: one over a limit is not imported and is listed (above; decision 20), so that it never shows unmasked either.
- **Websites**: the shared URL rule of ux.md §2 (`normalizeUrl`) for every imported website in every format: trimmed, `https://` added when missing and host-like (FR P10: the prototype kept `mail.ru` and ` gmail.com ` as they were); `android://…` kept and shown as "App: …". Host-like means no white space, no `/` or `:` before the first dot, and something after it; white space is Unicode's White_Space (a no-break space included) on every phone and in the tests alike, and the rule is checked in one pass over the text, since an imported value may be megabytes long (review of 3.15).
- **Speed** (FR P16): the planner indexes the vault and the rows already accepted by (trimmed username, host) in hash maps and works out each entry's hosts once, so its time grows with the sizes added, not multiplied: 10,000 rows against a vault of 10,000 take under a second on this machine (build-plan 3.15). The prototype took 5 s, recomputing hosts at every comparison. Accounts of one login are found by login and password, an exact copy by a digest of what is compared, and one note inside another with a linear search; the comparisons of one plan read at most 200 million characters, after which only exact copies still count as repeats, so that a near-repeat is added rather than skipped and nothing is lost (review of 3.15: 800 rows of one login whose notes begin alike took 112 s to plan).
- Nothing is written before "Import"; the import is one generation of the vault file, and "Undo this import" saves the vault as it was before it (D-15), so the undo is exact and cannot touch an entry that was there before.

### 8.5 Checked [X]
Real fixtures from Proton's test suite: a Chrome CSV (4 entries), its Windows variant (3) and a Bitwarden JSON with logins, cards, identities and notes (12) were recognised and mapped. They are committed, with their origin and licence (GPL-3.0), in `core/src/test/resources/formats/proton/`, beside the Uzbek Cyrillic header file of FR P6 (`SOURCES.md` there). formats-review.md then found the import problems P1 to P6 and P10 to P16, which the rules above fix. Our own files: the 5,015-entry CSV back to 5,014 entries (the 40,000-character note listed), multi-website entries and fields restored; JSON lossless; BOM; LF; Windows-1251 with `;` and Russian headers; Uzbek headers with unknown columns; Bitwarden and KeePassXC CSV samples; encrypted Bitwarden JSON refused.

## 9. Validation summary [X]
| Check | Result |
|---|---|
| Python `csv` reads our CSV | 5,017 rows identical to what was meant |
| Line-by-line Python transcription of Chromium's CSV reader and row checks | 5,017 rows parsed, 0 field differences; 5,014 OK, 2 notes too long, 1 without password (the all-empty entry); file over Chrome's 3,000 rows, so 2 parts |
| Our JSON against Proton's Bitwarden fixture and the Bitwarden/Proton/KeePassXC readers' key lists | all keys present, required arrays present, dates ISO with ms |
| LibreOffice `--convert-to csv` of the `.xlsx` | 55,176 cells identical |
| openpyxl 3.1.5 | identical except the 2 cells holding control characters (openpyxl leaves `_x0001_` undecoded, and it also deletes any literal `x005F_`, FR P17.5); frozen header, bold header, widths, print titles, filter, "@" format present |
| `tools/validate/run.sh` (package 0.3, again on the prototype's files) | the same results; and the zip times of the `.xlsx` are 2000-01-01 17:00, the time zone leak of FR P9, which the writer of version 1 removes (§6.1) |
| Apache POI 5.5.1 | `.xlsx` and locked `.xlsx` read, 55,176 cells identical |
| LibreOffice, POI, msoffcrypto-tool on the locked `.xlsx` | §7.3 |
| LibreOffice PDF of the special cases | §6.2 printing |
| Test data | oʻ with U+02BB and U+2018, Cyrillic, emoji, Hebrew and Arabic, commas, quotes, CR/LF/CRLF in notes, values starting with `=` `+` `-` `@`, tab, U+0001, U+0007, U+001F, U+FFFE, literal `_x0041_`, a 40,000-character note, a 300-line note, an all-empty entry, 5,000 generated entries |

## 10. Code and dependencies
| Part | Lines (prototype, no blanks or comments) | Dependencies |
|---|---|---|
| Entry model and ux.md limits | 58 | — |
| ISO 8601 dates | 53 | — |
| CSV reader and writer, text decoding | 104 | — (`windows-1251` is in Android's charsets [M]) |
| Google layout, parts, report, fields block | 95 | — |
| Bitwarden JSON export and import | 143 | kotlinx-serialization-json 1.11.0 (already chosen) |
| Excel writer and vault sheet | 204 | — |
| Excel with a password (Agile + CFB), not in version 1 (D-16) | 225 | — |
| Importer and import planner | 187 | — |
| Backup export and restore | ~80 + screens [I] | vault codec |
| Screens (format list, warning, summary, password sheet, preview, mapping) | ~600 Compose [I] | — |
| Tests | 216 (JVM) + Python/LibreOffice/POI checks (the plain-file checks now in `tools/validate/`) | test only: Apache POI 5.5.1 |

## 11. Recommendation

**Version 1**
1. **Backup file** (recommended, first in the list) with share sheet and restore (§3).
2. **CSV** in Google's layout with the Google summary card and automatic parts (§4).
3. **JSON** in Bitwarden's layout with the `passwordVault` extension (§5), described to the user as "Complete data. Bitwarden, Proton Pass and KeePassXC can import it."
4. **Excel** with shared strings (§6).
5. **Import** of Google/Chrome CSV, Bitwarden CSV and JSON, KeePassXC CSV, Firefox CSV, any CSV with named columns (with the mapping screen), our own CSV and JSON, and the backup (§8), with every fix of FR P1 to P6 and P10 to P16.

**Later**
- **Excel with a password** (§7): not in version 1 (architecture.md D-16). First candidate for 1.1, as the first choice under Excel ("Excel, locked with a password — recommended"), once the user has opened `passwords-…-locked.xlsx` and a plain sample without a repair prompt in Microsoft Excel (desktop, or the free Excel app on their Android phone). The code is ready in `prototypes/formats/`.
- `.kdbx` export through kotpass (offline.md recommends it): the only protected file other offline apps (KeePassDX, KeePassXC, Keyguard, Keepass2Android) open directly. Needs a device test of kotpass with a native Argon2.
- Bitwarden **password-protected** JSON export: the protected way into Bitwarden, 1Password and KeePassXC (Bitwarden's KDF, HKDF to two keys, AES-CBC plus HMAC; KeePassXC's reader shows the steps).
- Credential Exchange (FIDO CXP; `androidx.credentials.providerevents`, alpha): moving passwords from Google Password Manager without a file (account-based.md).
- Excel import, "Use the new password" on import conflicts, PDF or printing from the phone.

## 12. Differences from ux.md (for the UX owner)
| ux.md | This document | Why |
|---|---|---|
| §5.20.3 JSON in our own schema | Bitwarden layout plus `passwordVault` | three managers import it; still lossless for us |
| §5.20.1 inline strings | shared strings | small readers skip inline strings |
| §5.20.2 other websites in the note block | one row per website; only fields in the block | Google gets a working credential for every site, as Chrome itself exports |
| §11 password-protected Excel left out | proposed for version 1 if a real Excel opens it | Settled by architecture.md D-16 as ux.md had it: not in version 1, first candidate for 1.1 |
| §5.21 over-limit rows skipped | import the entry, cut the note and list it | Settled by architecture.md D-10f: an over-limit value never drops an entry; only a password over 1,000 characters does |
| §5.21 "Undo this import" removes the added entries | the vault is saved as it was before the import | Settled by architecture.md D-15 (FR P1): removing ids would also remove an entry that was there before |

## 13. Open questions
1. Does Microsoft Excel (Windows, Mac, and Excel for Android) open our plain `.xlsx` without a repair prompt? Nothing here could run it; the user checks it on their phone (architecture.md §8.6 item 5). For the locked file the same question waits for 1.1 (D-16).
2. What header and line ending does Google Password Manager's Android export write, and does its Android import accept our file? One real file from the user's phone settles both.
3. For 1.1: does Google Sheets really refuse password-protected `.xlsx`? Only secondary sources say so.
4. *Answered by architecture.md D-17:* hidden fields are shown in clear in the Excel export, so that the printout is complete (§6.2).
5. *Answered by architecture.md D-17:* the backup header carries no entry count; the restore summary shows the count after opening and the date from `created` (§3).
6. For 1.1: is the five-word EFF password acceptable for the locked Excel file for a user whose English is not native, or should it use 16 random letters and digits in groups of four?

## 14. Changes made on 2026-10-06 (build-plan package 0.3)
| Where | Change | Source |
|---|---|---|
| §0, §2 rules 5 and 6, §7, §10, §11, §13 | Excel with a password is not in version 1; its code and checks stay in `prototypes/formats/` | architecture.md D-16 |
| §0, §2 rules 11 and 12, §4.4, §4.5, §5.2, §6.2, §6.3 | Deleted accounts in no plain export; a pending change exports the new password and keeps the old one (`pendingOldPassword`, the line `Old password`) | D-10c, D-10d, D-10e |
| §2 rule 1 | The master-password step as lock.md F12 has it; nothing while a new master password is pending | lock.md F12, F11 step 10 |
| §2 rule 4 | MIME mapping on API 24 to 28 | FR P17.3 |
| §2 rule 8, §6.2 | Unpaired surrogates refused at input, replaced and counted by one function at writing, also in `.xlsx` | FR P7 |
| §3 | The backup is the vault file of architecture.md §4, sealed as §4.12 says; no entry count in its header; restore and merge by architecture.md §4.11 and §4.12 | architecture.md §4, D-17 |
| §4.4 | The block's grammar with its vectors, replacing the unmarked block | D-10a, FR P8 |
| §4.4 | The writer appends its own block when the user's notes alone would read as one (vector 8); made at the wave-0 integration, after package 0.3 | D-10a as amended (`iface-10`) |
| §4.5 | The summary card's counts follow Chrome's checks | FR P15 |
| §5.1 | Proton's `folders` | FR P17.2 |
| §5.2, §8.1, §8.2, §8.4 | Ids kept only when new; "Undo" restores the vault; strict UTF-8 and a depth limit for JSON; JSON import gaps | FR P1, P2, P3, P11; D-15 |
| §6.1 | A fixed local zip time | FR P9 |
| §6.2 | The citation of the `_x005F_` rule; what openpyxl does with it; hidden fields in clear | FR P17.4, P17.5; D-17 |
| §7.2 | The 64-byte HMAC key stated as a departure; the `<keyEncryptors>` element | FR P17.1, P17.6 |
| §8.2 | Text decoding that never switches silently; the `sep=` line; rows with the wrong number of values | FR P2, P4, P12, P13 |
| §8.2 | UTF-16 without a mark is recognised by NUL bytes at one parity, not "a NUL in every other byte", which holds only for Latin text: Cyrillic letters in UTF-16 have no NUL byte. Made at the wave-1 integration, after package 1.4 | build-plan 1.4 (`iface-26`) |
| §8.3 | Merging stops at 10 websites; Firefox CSV; KeePassXC in any case; Uzbek Cyrillic aliases and header normalisation; the mapping screen whenever no password column is found | FR P5, P6, P13; D-10b |
| §8.4 | Passwords never altered; over-limit values never drop an entry; `normalizeUrl` on import; the planner's speed | FR P5, P10, P14, P16; D-10f, D-15 |
| §8.5, §9, the introduction | Where the fixtures, the prototype and the checks now are | build-plan 0.3 |

## Sources
Chromium `chromium/src` HEAD, `components/password_manager/core/browser/export/{csv_writer.cc,password_csv_writer.cc}`, `.../import/{csv_password_sequence.cc,csv_password_iterator.cc,csv_field_parser.cc,csv_password.cc,password_importer.cc}`, `.../ui/credential_utils.cc`, `core/common/password_manager_constants.h`, `services/csv_password/csv_password_parser_impl.cc`. Bitwarden `bitwarden/clients` HEAD: `libs/importer/src/importers/{chrome-csv-importer.ts,base-importer.ts,bitwarden/bitwarden-csv-importer.ts,bitwarden/bitwarden-json-importer.ts}`, `libs/common/src/models/export/*`, `libs/tools/export-vault-core/src/{types,services}`. Proton `ProtonMail/WebClients` HEAD: `packages/pass/lib/import/{helpers/csv.reader.ts,providers/chromium,providers/bitwarden}` and their test fixtures; `protonpass/android-pass` `ProfileScreen.kt`. KeePassXC 2.8.0-beta1 source: `src/format/{CsvExporter.cpp,BitwardenReader.cpp}`, `src/gui/csvImport/CsvImportWidget.cpp`, `CHANGELOG.md`. KeePassDX 4.5.5 source. msoffcrypto-tool HEAD (`method/ecma376_agile.py`, `method/container/ecma376_encrypted.py`, `tests/inputs/example_password.xlsx`). openpyxl 3.1.5 `reader/strings.py`. AOSP android17-release and android-7.0.0_r1 `core/java/android/os/FileUtils.java`, `external/mime-support/mime.types`, `mime/java-res/android.mime.types`; SDK 37 `api-versions.xml`. Microsoft Learn [MS-CFB] "Red-Black Tree", "Other Directory Entries" ([MS-OFFCRYPTO] itself was not fetched; its steps were taken from msoffcrypto-tool's code and checked against the reference workbook); support.microsoft.com "Excel specifications and limits"; support.google.com/accounts/answer/10500247; bitwarden.com/help/import-data; support.1password.com/import-bitwarden and /import-chrome; keyguard.dev/docs/migrating; owasp.org/www-community/attacks/CSV_Injection; gist.github.com/Chick3nman/32e662a5bb63bc4f51b847bb422222fd; issue trackers of exceljs (#1575), JuliaData/XLSX.jl (#123), akeneo-labs/spreadsheet-parser (#46). Tools run: LibreOffice 24.2.7.2 (headless, private profile, UNO), Apache POI 5.5.1, msoffcrypto-tool 6.0.0, olefile 0.46, openpyxl 3.1.5, Python 3.12, JDK 25, kotlinc 2.3.20.
