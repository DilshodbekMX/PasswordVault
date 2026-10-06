# PasswordVault: export and import formats

Date 2026-10-06. Status: proposal for review. Scope: every file the app writes or reads besides its own live vault: the encrypted backup, CSV, JSON, Excel with and without a password, and imports. The entry model is the version 1 model of `docs/design/ux.md` §2 (tags deferred; `changePending`, `checkIgnored`; history capped at 5; control characters refused on input).

How I know things: **[S]** source code read today at HEAD (path given); **[P]** primary document fetched today; **[X]** my experiment today on this machine; **[I]** inference or design judgement; **[M]** from memory, not re-checked.

Prototype (Kotlin, JVM, no Android types; 1,069 lines without blanks and comments) in the scratchpad at `design/formats/kt/src/pv/formats/`, its test in `kt/test/FormatsTest.kt`, the outside checks in `design/formats/validate/` (`run_all.sh` reruns everything). Build: Android Studio's kotlinc 2.3.20 and kotlinx-serialization-json 1.11.0 from the Gradle cache.

## 0. Decisions in brief

| Format | File | Who can open it | Lost compared with the vault | Version |
|---|---|---|---|---|
| **Backup file** (recommended) | `.pvault`, `application/octet-stream` | only this app, with the master password, on any phone | nothing | **1** |
| **Excel** | `.xlsx`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Excel, LibreOffice, Google Sheets, phone office apps | history, flags, ids; for reading and printing only | **1** |
| **Excel with a password** | `.xlsx`, same type | LibreOffice and Apache POI (tested), Excel (same structure, not tested); **not** Google Sheets | as Excel | **1, if a real Excel opens our sample before release; otherwise 1.1** |
| **CSV** in Google's layout | `.csv`, `text/csv` | Google Password Manager, Chrome, Bitwarden, Proton Pass, 1Password, KeePassXC | favourite, dates, history, flags; extra fields travel inside the note | **1** |
| **JSON** in Bitwarden's layout plus our extension | `.json`, `application/json` | Bitwarden, Proton Pass, KeePassXC 2.7.7+, and this app without loss | nothing for this app; others drop what they lack | **1** |
| Import | CSV (Google/Chrome, Bitwarden, KeePassXC, any CSV with named columns), JSON (Bitwarden, ours), backup | | | **1** |
| KeePass `.kdbx` export; Bitwarden password-protected JSON; Credential Exchange | | | | later |

1. **JSON: Bitwarden's layout, not a schema of our own.** Bitwarden, Proton Pass and KeePassXC read it; unknown keys are ignored by all three (checked in their readers), so one `passwordVault` object per item carries what Bitwarden cannot hold, and the same file round-trips through this app without loss [S][X]. 1Password does **not** read unencrypted Bitwarden JSON [P]; it reads the CSV. This changes ux.md §5.20.3.
2. **CSV: Chrome's exact header and quoting, one row per website**, as Chrome's own exporter writes affiliated sites [S]; extra fields go in a marked block at the end of the note that this app reads back. Rows end in CRLF (ux.md); every importer checked accepts CRLF and LF [S]. No BOM.
3. **Excel: every cell a shared string with text format**, never inline strings: several small readers skip `inlineStr` cells [P: issue trackers]; shared strings are what Excel itself writes [M] and LibreOffice, Apache POI and openpyxl read ours [X]. This changes one line of ux.md §5.20.1.
4. **Excel with a password is feasible and small**: ECMA-376 Agile Encryption plus a Compound File writer is 215 lines with no dependency. LibreOffice 24.2, Apache POI 5.5.1 and msoffcrypto-tool 6.0.0 all open our files with the password, refuse a wrong one and refuse a tampered one [X]. Real Excel was not available here. Its key stretching is ~40 times cheaper to attack than our backup's, so the app generates the file password; the user never chooses it.
5. **Every export needs the master password**; plain ones get the full-screen warning of ux.md §5.20 and only "Save" (no share sheet).
6. **Imports never overwrite**: exact repeats are skipped, a same-site-and-username entry with another password is added beside the old one (ux.md §5.21), and the whole import is one save that can be undone.

## 1. The request

"i want no account and everything on the phone, but i can copy or export as file like csv, json, xls or other."

- **csv** → Google's layout: their passwords most likely live in Google Password Manager today, and the same file goes back there or into any other manager.
- **json** → a JSON other managers read, which also keeps everything for this app.
- **xls** → `.xlsx`. The 1997 binary `.xls` is costly to write and nothing needs it (ux.md §5.20.1).
- **or other** → the backup file, the only format safe to keep; `.kdbx` later.
- **copy** → copying one field is the copy flow of ux.md §6. Copying the whole vault to the clipboard is not offered: keyboards and clipboard managers keep history [I].

## 2. Rules for every export

1. **Order**: Settings › Export → choose a format (ux.md §5.20 list) → plain formats: full-screen warning and "I understand" → **master password** (not PIN, not fingerprint): the backup needs it to make a new key (crypto.md §11), and a plain export must not be one borrowed unlocked phone away → system "Save as" → write → read back → result screen.
2. **Saving** goes only through the Storage Access Framework: `ActivityResultContracts.CreateDocument(mime)` with a suggested name; no storage permission (platform.md). Write straight into `contentResolver.openOutputStream(uri, "wt")`; never a plaintext temporary file in `cacheDir` or `filesDir`.
3. **Read-back check**: hash (SHA-256) and count the bytes while writing; reopen the document, hash again, compare. On mismatch delete the document and say "Couldn't save to this place. Try another folder." Providers have produced empty files before (KeePassDX #1594, #1620, #1680; offline.md).
4. **MIME type must match the extension.** Android's providers append the MIME type's own extension when the name's extension maps to a different type; `application/octet-stream` is exempt [S: AOSP `FileUtils.splitFileName`, android17-release and android-7.0.0_r1]. The platform maps `csv`↔`text/csv`, `json`↔`application/json`, `xlsx`↔the spreadsheetml type [S: `external/mime-support/mime.types`].
5. **Names** (local date, ASCII digits): `PasswordVault-backup-2026-10-06.pvault`, `passwords-2026-10-06.csv` (`-part1`, `-part2` when split), `passwords-2026-10-06.json`, `passwords-2026-10-06.xlsx`, `passwords-2026-10-06-locked.xlsx`. The name says what is inside so the user finds and deletes it [I].
6. **Sharing**: the share sheet (through a `FileProvider` copy in `cacheDir`, deleted afterwards) only for the backup and the locked `.xlsx`. Plain files: "Save" only (ux.md §5.20).
7. **After a plain export**: ux.md's "Delete it now" (`DocumentsContract.deleteDocument`, API 19) and the banner that stays until the file is deleted.
8. **Text hygiene**: before writing, replace unpaired UTF-16 surrogates with U+FFFD. kotlinx-serialization writes a lone `\uD800` unescaped and the UTF-8 encoder turns it into `?` (0x3F) [X]; the same would happen to the vault body. ux.md's input rules (no control characters) make the rest of this section defence in depth.
9. **Portability on API 24**: no `java.util.Base64` and no `java.time` (both API 26 [S: android-37 `api-versions.xml`]) unless core-library desugaring is on; the prototype uses `kotlin.io.encoding.Base64` and a 53-line ISO 8601 helper. Format numbers and dates with `Locale.ROOT` [I].
10. **Speed**: on this desktop JVM 5,015 entries take 3–17 ms to write as CSV, 47–102 ms to write or read as JSON, 42–80 ms to write as `.xlsx`, and 64–174 ms to encrypt the `.xlsx` (100,000 SHA-512 rounds) [X]. Phones are slower; run on a background dispatcher with "Saving…".

**Warning text** (full screen; ux.md §5.20 wording, translated into Uzbek and Russian):
- Title "This file will not be protected". Body "Anyone who opens this file can read all your passwords. It has no password."
- "Save it only where you control it: your own computer or USB stick." · "Don't send it by Telegram, email or other messengers." · "Delete it as soon as you're done."
- CSV adds: "Don't open this file in Excel: Excel changes phone numbers and codes and can run formulas. Choose Excel export instead." (Evidence in §4.6.)
- JSON adds nothing. Excel adds: "Google Sheets and other apps may keep a copy in their cloud." [I]
- Android 7–10 adds: "On this phone other apps with storage access can read files in Downloads." [I: scoped storage arrived in Android 10/11]

## 3. Backup file (`.pvault`): not prototyped

- **Layout**: the vault container of crypto.md §1 (`PVAULT01`, header length, JSON header, body nonce, AES-256-GCM body with the stored header bytes as associated data) with **exactly one password slot**, a new random vault key, a new 16-byte salt, Argon2id 64 MiB / 3 / 4, and no biometric slot (crypto.md §10). Body: the vault's own `{"schema":1,"entries":[…]}`, so it carries every field and every unknown key.
- **Opens**: only in this app (or with the published decrypt script crypto.md §10 asks for), on any phone, with **the master password in use when the backup was made**. After a password change old backups keep the old password; restore says so (ux.md §5.2).
- **MIME** `application/octet-stream` (no extension appended [S]); import recognises the file by its first 8 bytes, not by name.
- **Lost**: nothing. **Warning**: none about plaintext; the "Backups explained" texts of ux.md §5.8 and §5.19.
- **Restore into an existing vault**: same ids and same content skipped; same id, different content added as "Gmail (from backup)"; nothing overwritten (ux.md §5.21).
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

--- PasswordVault ---
PIN code (hidden): 1234
Address: Toshkent, Chilonzor 5
  second line of the address"␍␊
```
- UTF-8, no BOM, CRLF after every row. Quote a value that contains `,` `"` CR or LF, or starts or ends with a space or tab; double inner quotes. Line breaks inside values are written as stored (LF).
- **One row per website** (an entry with none gets one row with an empty url), in vault order. `name` = title. URLs without a scheme that look like a host get `https://` (Chrome refuses `gmail.com`) [S].
- **note** = the notes, then, if the entry has extra fields, a blank line, the marker `--- PasswordVault ---` and one line per field `Label: value`, with ` (hidden)` after the label of a hidden field and continuation lines of multi-line values indented by two spaces. Marker and "(hidden)" stay in English in every language because the importer reads them.
- **Splitting**: when the file would exceed 3,000 rows or 1,000 KB, write `-part1`, `-part2`, … (one "Save as" per part); an entry's rows never straddle parts [X: 5,015 entries → 3,000 + 2,017 rows].

### 4.5 What is lost; what the user is told first
- Kept for this app: title, username, password, all websites (adjacent rows are merged back), notes, extra fields with their hidden flag [X]. Lost: favourite, the three dates, previous passwords, `changePending`, `checkIgnored`, ids.
- In Google: one credential per website; the fields block shows as note text.
- **Summary card before saving** (counts from `GoogleCsv.report`): accounts without a website and without a password (Google skips them), notes over 1,000 bytes (Google skips the row; the fields block counts), number of files, "Previous passwords and favourites are not in CSV. JSON keeps everything."

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
| Proton Pass | yes; refuses `"encrypted": true`; needs `items` and `folders` arrays; reads name, notes, login, URIs, TOTP, fields; not favourite, dates or history | [S: `providers/bitwarden/bitwarden.reader.ts`] |
| KeePassXC | yes since 2.7.7 (2024-03-09); dates and history since 2.7.11; nested folders 2.7.12; needs the `folders` and `items` keys; hidden fields become protected attributes; favourite becomes the tag "Favorite" | [S: `src/format/BitwardenReader.cpp`, CHANGELOG] |
| 1Password | **no**: "Unencrypted JSON and account restricted exports aren't currently supported"; it takes password-protected Bitwarden JSON or CSV | [P: support.1password.com/import-bitwarden] |
| Keyguard | no file import; works on a Bitwarden account or a KDBX file | [P: keyguard.dev/docs/migrating] |
| KeePassDX | no | §4.3 |

Our own schema would be read by nobody else and adds nothing the backup does not already give. Bitwarden's layout holds every v1 field except `passwordChangedAt`, the two flags and the original Android URLs; those go into a `passwordVault` object, which all three readers ignore because they map known keys only. The importer is needed anyway to bring passwords in from Bitwarden, so the export side costs 49 lines [X].

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
                        "urls": ["https://mail.google.com", "android://<hash>@com.google.android.gm/"]}
    }
  ]
}
```
- Times: ISO 8601 UTC with milliseconds, as Bitwarden writes them. Field `type` 1 = hidden, 0 = text. Empty strings are written as `null`, as Bitwarden does. `folders` is always present and empty (v1 has no folders).
- Android links are written in Bitwarden's form `androidapp://<package>`; the original Chrome form is kept in `passwordVault.urls` only when it differs.
- `passwordVault` in an item also carries, unchanged, any key a newer app version wrote (storage.md's "preserve unknown keys") [X: a `futureField` survived the round trip].
- **Import**: `passwordVault.format` identifies our own file; then ids are kept and the extension wins. In foreign files, ids are new, a folder becomes a visible field "Folder", `login.totp` a hidden field "TOTP" (v1 has no one-time codes), cards, identities and SSH keys become entries whose fields are the item's strings (hidden when the key looks secret), linked fields and trashed items are dropped and listed, and history is cut to the newest 5. `"encrypted": true` is refused with "This Bitwarden file is encrypted. In Bitwarden, export again and choose '.json', not '.json (Encrypted)'."

### 5.3 Lost; warning; size
- This app: nothing [X: 5,011 entries equal after export and import]. Bitwarden: the three extension values. Proton Pass: also favourite, dates, history. KeePassXC: the extension values.
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

No `docProps` (no author or time in the file). Zip entries are deflated with a fixed timestamp, so an unchanged vault gives identical bytes.

### 6.2 Cells
- **Every cell is a shared string** (`t="s"`, style "@"). Inline strings work in Excel [M] and LibreOffice (ux.md's test), but exceljs (#1575), XLSX.jl (#123), akeneo spreadsheet-parser (#46) and others show them empty [P: their issue trackers]; Excel itself writes shared strings [M]. Cost: one map from string to index.
- **No formula risk**: a shared string is never evaluated; the writer never emits `<f>`, `t="str"`, hyperlinks or external relationships. Format "@" keeps a cell text if the user edits it in Excel [M]. Readers saw 0 formula cells and `=HYPERLINK(…)`, `+998901234567`, `-2+3`, `@SUM(A1:A2)` as text [X].
- **Columns** (ux.md §5.20.1, labels localised): Name 24 · Username 26 · Password 22 · Website 30 · Other websites 24 (one per line) · Notes 40 · Extra fields 30 (`Label (hidden): value` per line) · Favourite 10 (localised "Yes") · Added 16 · Last changed 16 · Password changed 16. Widths are in characters. Dates are text `2026-10-06 14:30` in the phone's zone, which sorts correctly. Android links show as `app: com.example`.
- **Escaping** in element content: `&` `<` `>` as entities (`"` only in attributes). Line breaks are LF; CRLF and lone CR become LF.
- **Characters XML 1.0 forbids** even as `&#…;`: U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, U+FFFE, U+FFFF and unpaired surrogates. They are written with OOXML's `_xHHHH_` escape (`_x0001_`), and a literal `_xHHHH_` in the data has its underscore escaped as `_x005F_`, as Excel does [M: ECMA-376 Part 1 ST_Xstring]. LibreOffice and Apache POI decode both; openpyxl 3.1.5 does not decode control characters (its reader only strips `x005F_`) [X; S: `openpyxl/reader/strings.py`]. ux.md refuses control characters on input, so this is defence in depth.
- **Limits** [P: Microsoft "Excel specifications and limits"]: 32,767 characters per cell, 253 line feeds per cell, 255 characters in a header or footer, row height 409 points. Cells longer than 32,767 are cut with `[… 7,233 more characters: Excel shows at most 32,767 in one cell]`; line breaks after the 253rd become ` ↵ `. With ux.md's limits (notes 10,000; fields 20 × 1,000) neither happens; both are guards [X: a 40,000-character note and a 300-line note].
- **Printing** [X: LibreOffice PDF]: landscape A4, header row repeated on every page, grid lines, page numbers, Uzbek, Cyrillic, Hebrew/Arabic (right-aligned) and emoji rendered. A cell taller than one page is cut on paper; the full text stays in the file.

### 6.3 Lost; warning; size
Lost: previous passwords, `changePending`, `checkIgnored`, ids, the hidden flag's protection (values shown in clear). Not meant for importing back (§8 refuses spreadsheets). Warning: §2. Size: 170 lines generic writer + 34 lines vault sheet; no dependency (`java.util.zip`, API 1).

## 7. Excel with a password (ECMA-376 Agile Encryption)

### 7.1 What it is
What Excel's "Encrypt with Password" writes: the finished `.xlsx` bytes encrypted and stored in a Compound File Binary container. Reference: the encrypted workbook in msoffcrypto-tool's tests (`tests/inputs/example_password.xlsx`), whose container carries times on storages only, the pattern of Windows structured storage, so very likely saved by Excel [I]. Its parameters, which this design copies [X]: SHA-512, spin count 100,000, AES-256-CBC, 16-byte salts.

### 7.2 Construction [M: [MS-OFFCRYPTO] 2.3.4.10–2.3.4.15; S: msoffcrypto-tool `method/ecma376_agile.py`; X: the reference workbook]
1. Random: key-data salt (16 B), password salt (16 B), **secret key** (32 B), verifier input (16 B), HMAC key (64 B).
2. **EncryptedPackage** stream: u64 LE plaintext length, then the package in 4,096-byte segments, each AES-256-CBC under the secret key with IV = SHA-512(key-data salt ‖ u32 LE segment number)[0..16); the last segment is zero-padded to 16 bytes.
3. **Data integrity**: HMAC-SHA512 with the HMAC key over the **whole** EncryptedPackage stream including its length field; the HMAC key and value are each AES-CBC-encrypted under the secret key with IVs SHA-512(key-data salt ‖ block key)[0..16), block keys `5fb2ad010cb9e1f6` and `a0677f02b22c8433`.
4. **Password key**: H = SHA-512(password salt ‖ UTF-16LE(password)), then for i = 0…99,999: H = SHA-512(u32 LE i ‖ H); key_b = SHA-512(H ‖ block key_b)[0..32). With IV = the password salt: encrypt the verifier input (block key `fea7d2763b4b9e79`), SHA-512(verifier input) (`d7aa0f6d3061344e`) and the secret key (`146e0be7abacd0d6`).
5. **EncryptionInfo** stream: u16 4, u16 4, u32 0x40, then UTF-8 XML: `<encryption xmlns=…/encryption xmlns:p=…/keyEncryptor/password xmlns:c=…/certificate>` with `<keyData saltSize="16" blockSize="16" keyBits="256" hashSize="64" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC" hashAlgorithm="SHA512" saltValue="…"/>`, `<dataIntegrity encryptedHmacKey="…" encryptedHmacValue="…"/>` and one `<keyEncryptor uri="…/keyEncryptor/password"><p:encryptedKey spinCount="100000" … saltValue encryptedVerifierHashInput encryptedVerifierHashValue encryptedKeyValue/>`; Base64 with padding.
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
Sources screen → file picker (`OpenDocument("*/*")`; providers label CSV as `text/csv`, `text/comma-separated-values` or `application/vnd.ms-excel`, so the content decides) → read in the background → preview → "Import 45 accounts" → one vault save → "Undo this import" → for plain files "Delete file". Limits: 10 MB and 10,000 rows.

### 8.2 Recognising the file
| First bytes or shape | Result |
|---|---|
| `PVAULT01` | backup restore (§3) |
| `PK\x03\x04` or `D0 CF 11 E0` | "This is an Excel file. In Excel choose File › Save As › CSV UTF-8, then import that file." |
| `03 D9 A2 9A` | "This is a KeePass database. In KeePassXC: Database › Export › CSV." |
| text starting with `{` | Bitwarden JSON; ours when `passwordVault.format` is `passwordvault-export` |
| other text | CSV |

**Text decoding**: UTF-8 BOM removed; UTF-16 LE/BE by BOM (Excel's "Unicode text"); strict UTF-8; otherwise Windows-1251, what Russian-language Excel writes for "CSV (comma delimited)" [I], with "Text looks wrong? Read as UTF-8" on the preview. **Delimiter**: whichever of `,` `;` tab splits the header line (outside quotes) into most fields; Excel uses `;` where the list separator is `;` [M]. **Parser**: RFC 4180, CRLF/LF/CR row ends, quoted fields with delimiters, `""` and line breaks; text after a closing quote kept and a quote inside an unquoted field literal (Excel's behaviour); an unclosed quote reported with its line number; blank rows skipped.

### 8.3 Formats and mappings
| Format, recognised by | Mapping |
|---|---|
| **Google/Chrome**, and **our CSV**: header has name, url, username, password | name→title (empty: host of the URL, the app package, or the username), url, username, password, note; adjacent rows equal except url merge into one entry; a trailing `--- PasswordVault ---` block becomes extra fields |
| **Bitwarden CSV**: `login_uri`/`login_username`/`login_password` [S: `bitwarden-csv-importer.ts`; exporter header folder, favorite, type, name, notes, fields, reprompt, archivedDate, login_uri, login_username, login_password, login_totp] | `login_uri` is itself a CSV list; `fields` lines `name: value` split at the last `: `; `type=note` → no username/password; favorite `1`; folder → field "Folder"; `login_totp` → hidden field "TOTP" |
| **KeePassXC CSV**: Group, Title, Username, Password, URL, Notes [S: `CsvExporter.cpp`; all fields quoted, LF, ISO dates] | Group without the root name → field "Folder"; TOTP → hidden field; Last Modified, Created → dates; Icon ignored |
| **Any CSV with named columns** | aliases below; a column with no meaning becomes an extra field named by its header, hidden unless the label is harmless (email, phone, question, folder…) as ux.md §2 asks |
| **Bitwarden JSON**, **our JSON** | §5.2 |

**Header aliases** (case, spaces, `_`, `-`, `.` and BOM ignored); Chromium's list first:
- website: url, website, origin, hostname, login_uri, uri, web site, site, address, link, сайт, адрес, ссылка, веб-сайт, sayt, manzil, havola, veb-sayt
- username: username, user, login, account, login_username, user name, email, e-mail, логин, имя пользователя, пользователь, почта, электронная почта, foydalanuvchi, pochta, elektron pochta
- password: password, login_password, pass, passwd, pwd, пароль, parol
- notes (Chromium's priority note › notes › comment › comments): extra, description, заметка, заметки, примечание, комментарий, описание, izoh, eslatma, qayd, sharh
- title: name, title, item name, название, наименование, nomi, nom, sarlavha
- also: totp, folder/group (папка, группа, papka, guruh), favourite, created, modified.
A second column with a meaning already taken becomes an extra field (Chromium would refuse the file). With no title, website, username, password or notes column, or with an ambiguity the user should see, the column-mapping screen of ux.md §5.21 opens, pre-filled from this table.

### 8.4 Rules
- **Model rules** (ux.md §2): control characters removed and counted ("6 invisible characters were removed"); entries over a limit listed and not imported (for example the 40,000-character note) [X]. Suggestion for the UX owner: import such an entry with its note cut and listed, rather than lose its password.
- **Exact repeats** (same website host, same username, same password, and nothing new in notes or fields): skipped, against the vault ("3 are already here") and within the file.
- **Same website and username, different password**: added beside the existing entry ("added so you can compare", ux.md); the security check later offers to combine. A later "Use the new password" can move the old one into history (`ImportPlanner.replacePassword`).
- **Different websites, same username and password**: kept apart; that is the reuse this app exists to fix.
- **No password**: imported and counted (SMS-login accounts, notes).
- **Websites**: trimmed; `https://` added when missing and host-like (ux.md §2); `android://…` kept and shown as "App: …".
- Nothing is written before "Import"; the import is one generation of the vault file, so "Undo" is exact.

### 8.5 Checked [X]
Real fixtures from Proton's test suite: a Chrome CSV (4 entries), its Windows variant (3) and a Bitwarden JSON with logins, cards, identities and notes (12) were recognised and mapped. Our own files: the 5,015-entry CSV back to 5,014 entries (the 40,000-character note listed), multi-website entries and fields restored; JSON lossless; BOM; LF; Windows-1251 with `;` and Russian headers; Uzbek headers with unknown columns; Bitwarden and KeePassXC CSV samples; encrypted Bitwarden JSON refused.

## 9. Validation summary [X]
| Check | Result |
|---|---|
| Python `csv` reads our CSV | 5,017 rows identical to what was meant |
| Line-by-line Python transcription of Chromium's CSV reader and row checks | 5,017 rows parsed, 0 field differences; 5,014 OK, 2 notes too long, 1 without password (the all-empty entry); file over Chrome's 3,000 rows, so 2 parts |
| Our JSON against Proton's Bitwarden fixture and the Bitwarden/Proton/KeePassXC readers' key lists | all keys present, required arrays present, dates ISO with ms |
| LibreOffice `--convert-to csv` of the `.xlsx` | 55,176 cells identical |
| openpyxl 3.1.5 | identical except the 2 cells holding control characters (openpyxl leaves `_x0001_` undecoded); frozen header, bold header, widths, print titles, filter, "@" format present |
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
| Excel with a password (Agile + CFB) | 225 | — |
| Importer and import planner | 187 | — |
| Backup export and restore | ~80 + screens [I] | vault codec |
| Screens (format list, warning, summary, password sheet, preview, mapping) | ~600 Compose [I] | — |
| Tests | 216 (JVM) + Python/LibreOffice/POI checks | test only: Apache POI 5.5.1 |

## 11. Recommendation

**Version 1**
1. **Backup file** (recommended, first in the list) with share sheet and restore (§3).
2. **CSV** in Google's layout with the Google summary card and automatic parts (§4).
3. **JSON** in Bitwarden's layout with the `passwordVault` extension (§5), described to the user as "Complete data. Bitwarden, Proton Pass and KeePassXC can import it."
4. **Excel** with shared strings (§6).
5. **Excel with a password** (§7) as the first choice under Excel ("Excel, locked with a password — recommended"), **on one condition**: before release, `passwords-…-locked.xlsx` and the plain sample open without a repair prompt in Microsoft Excel (desktop, or the free Excel app on the user's Android phone). If that check cannot be made or fails, ship without it and add it in 1.1; the code is ready either way.
6. **Import** of Google/Chrome CSV, Bitwarden CSV and JSON, KeePassXC CSV, any CSV with named columns (with the mapping screen), our own CSV and JSON, and the backup (§8).

**Later**
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
| §11 password-protected Excel left out | version 1 if a real Excel opens it | 215 lines, three independent readers passed |
| §5.21 over-limit rows skipped | suggest importing with the note cut | the password is not lost |

## 13. Open questions
1. Does Microsoft Excel (Windows, Mac, and Excel for Android) open both `.xlsx` files without a repair prompt? Nothing here could run it.
2. What header and line ending does Google Password Manager's Android export write, and does its Android import accept our file? One real file from the user's phone settles both.
3. Does Google Sheets really refuse password-protected `.xlsx`? Only secondary sources say so.
4. Should the hidden flag of extra fields also hide them in the Excel export (shown as dots), at the cost of an incomplete printout?
5. Should the backup carry a visible "made on" date and entry count in its unencrypted header for the restore screen (ux.md §5.2 shows "52 accounts")? The count is metadata outside the encryption.
6. Is the five-word EFF password acceptable for the locked Excel file for a user whose English is not native, or should it use 16 random letters and digits in groups of four?

## Sources
Chromium `chromium/src` HEAD, `components/password_manager/core/browser/export/{csv_writer.cc,password_csv_writer.cc}`, `.../import/{csv_password_sequence.cc,csv_password_iterator.cc,csv_field_parser.cc,csv_password.cc,password_importer.cc}`, `.../ui/credential_utils.cc`, `core/common/password_manager_constants.h`, `services/csv_password/csv_password_parser_impl.cc`. Bitwarden `bitwarden/clients` HEAD: `libs/importer/src/importers/{chrome-csv-importer.ts,base-importer.ts,bitwarden/bitwarden-csv-importer.ts,bitwarden/bitwarden-json-importer.ts}`, `libs/common/src/models/export/*`, `libs/tools/export-vault-core/src/{types,services}`. Proton `ProtonMail/WebClients` HEAD: `packages/pass/lib/import/{helpers/csv.reader.ts,providers/chromium,providers/bitwarden}` and their test fixtures; `protonpass/android-pass` `ProfileScreen.kt`. KeePassXC 2.8.0-beta1 source: `src/format/{CsvExporter.cpp,BitwardenReader.cpp}`, `src/gui/csvImport/CsvImportWidget.cpp`, `CHANGELOG.md`. KeePassDX 4.5.5 source. msoffcrypto-tool HEAD (`method/ecma376_agile.py`, `method/container/ecma376_encrypted.py`, `tests/inputs/example_password.xlsx`). openpyxl 3.1.5 `reader/strings.py`. AOSP android17-release and android-7.0.0_r1 `core/java/android/os/FileUtils.java`, `external/mime-support/mime.types`, `mime/java-res/android.mime.types`; SDK 37 `api-versions.xml`. Microsoft Learn [MS-CFB] "Red-Black Tree", "Other Directory Entries" ([MS-OFFCRYPTO] itself was not fetched; its steps were taken from msoffcrypto-tool's code and checked against the reference workbook); support.microsoft.com "Excel specifications and limits"; support.google.com/accounts/answer/10500247; bitwarden.com/help/import-data; support.1password.com/import-bitwarden and /import-chrome; keyguard.dev/docs/migrating; owasp.org/www-community/attacks/CSV_Injection; gist.github.com/Chick3nman/32e662a5bb63bc4f51b847bb422222fd; issue trackers of exceljs (#1575), JuliaData/XLSX.jl (#123), akeneo-labs/spreadsheet-parser (#46). Tools run: LibreOffice 24.2.7.2 (headless, private profile, UNO), Apache POI 5.5.1, msoffcrypto-tool 6.0.0, olefile 0.46, openpyxl 3.1.5, Python 3.12, JDK 25, kotlinc 2.3.20.
