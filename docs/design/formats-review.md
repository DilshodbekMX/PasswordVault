# Break test: export and import formats (formats.md and its prototypes)

Date 2026-10-06. Subject: `docs/design/formats.md` and the JVM prototypes in `scratchpad/design/formats/`. Everything below was re-run from scratch in `scratchpad/design/formats-break/` on copies of the prototype sources. The tools were my own Python venv (openpyxl 3.1.5, msoffcrypto-tool 6.0.0, olefile 0.47, python-calamine 0.8.2, lxml 6.1.3), LibreOffice 24.2.7.2 with a private profile, Apache POI 5.5.1, JDK 25 and kotlinc 2.3.20. `run_all.sh` reproduces every result in about 90 seconds, and its output is in `run_all.log`. formats.md itself was not edited.

**`REPORT.md` was not written.** The task named `formats-break/REPORT.md` as the output, but the subagent harness refused to write it ("Subagents should return findings as text"), so this text exists only in this response. Every script, log and generated file listed under Files is on disk.

## Verdict

**The writers are sound.** I checked every CSV, JSON and Excel file, including the password-protected one, with tools other than the prototype, and they read back exactly for every character class I tried:
- **Locked workbook:** it opens in four independent readers: LibreOffice, Apache POI, msoffcrypto-tool, and a decryptor I wrote from the MS-OFFCRYPTO text. That includes an 8.9 MB workbook that needs DIFAT sectors.
- **Plain workbooks:** they validate against the ECMA-376 Transitional and OPC schemas.
- **Source claims:** the Chromium, Bitwarden, Proton Pass, KeePassXC, MS-CFB and Excel-limit claims match their primary sources.

**The import side has the problems.** They show up with our own files, with damaged or hand-made files, and with the user's own languages. One is critical: re-importing our own JSON after editing an entry puts two entries with the same id into the vault.

**Count:** 1 critical, 5 major, 12 minor.

| # | Severity | Problem | formats.md |
|---|---|---|---|
| P1 | critical | Re-importing our own JSON creates duplicate entry ids | §5.2, §8.4 |
| P2 | major | One invalid byte makes the importer read the whole file as Windows-1251, silently garbling every non-ASCII password (CSV and JSON) | §8.2 |
| P3 | major | A 4 KB JSON file of nested arrays crashes the importer with `StackOverflowError` | §8.2 |
| P4 | major | Rows with more or fewer values than the header are imported silently, with shifted or missing passwords | §8.2 |
| P5 | major | Import drops whole entries, passwords included, when one value is over a model limit; the row merge creates such entries | §8.3, §8.4 |
| P6 | major | Headers in Uzbek Cyrillic, or with oʻ/o‘, are not recognised; with no password column found, the import continues instead of opening the mapping screen | §8.3 |
| P7 | minor | Lone surrogates: rule 2.8 is not implemented; the writers produce three different results | §2.8, §6.2 |
| P8 | minor | The CSV fields block does not round-trip some field names and notes | §4.4 |
| P9 | minor | `.xlsx` bytes depend on the phone's time zone and reveal its UTC offset | §6.1 |
| P10 | minor | Import does not add `https://` to bare hosts | §8.4 |
| P11 | minor | JSON import gaps (no row limit, untitled empty entries, silent drops, lost root extension keys) | §5.2, §8.1 |
| P12 | minor | The BOM decoding branches hide damaged bytes; UTF-16 without a BOM is refused as "binary" | §8.2 |
| P13 | minor | Firefox exports, Excel's `sep=` line and lower-case KeePassXC headers are mishandled | §8.3 |
| P14 | minor | Import changes passwords that contain a tab or another control character | §8.4 |
| P15 | minor | The Google summary card's counts diverge from Chrome's real checks at the edges | §4.5 |
| P16 | minor | The import planner's time grows with vault size times file size | §8.4 |
| P17 | minor | Spec statements that do not match their primary source | §2, §5.1, §6.2, §7.2, §9 |
| P18 | minor | Validation script crash and a locale-dependent test helper | §9 |

## Problems

### P1 (critical): re-importing our own JSON creates duplicate entry ids
- **Trigger.** The vault holds Gmail (id `…0140`) and Bank. Export the JSON, change Gmail's password in the app, then import that JSON. A second trigger: an own-format JSON in which two items share one id.
- **Observed** (`proto/attack/Attack.kt` §11): `ImportPlanner.plan` returns `add=[Gmail/…0140]`, one conflict and one item already in the vault. After the import the vault holds two entries with id `…0140`. With two items sharing an id, both are imported with that id (`ids [0140, 0140]`).
- **Cause.** `BitwardenJson.import` keeps ids for our own format, as §5.2 asks ("ids are kept"). `ImportPlanner` never looks at ids.
- **Why it is critical:**
  - The planner's own comment says "Undo this import removes exactly the added ids", and ux.md §5.21 says Undo "removes exactly those entries". Undo would also delete the user's current Gmail.
  - Routes, edits and deletes are keyed by id (ux.md: "Route keys are UUIDs"), so they would act on the wrong entry or on both.
  - A Compose `LazyColumn` keyed by id throws `IllegalArgumentException` ("Key … was already used") on duplicate keys, so the password list could crash on every unlock. This last point is inference; the UI is not written yet.
- **Fix.**
  - Keep an incoming id only if it is a valid UUID that is in neither the vault nor earlier in the same file.
  - Otherwise, skip the row if its content equals the existing entry; else give it a new UUID and the title "Gmail (from file)". ux.md §5.21 already applies this rule to backups.
  - Implement Undo as "restore the previous vault generation" (§8.4), not "remove these ids".
  - Add a test: export, edit one entry, import.

### P2 (major): one invalid byte switches the whole file to Windows-1251
- **Trigger, CSV.** This valid UTF-8 file, with one stray byte `0xC3` at the end of the last line:
  ```
  name,url,username,password,note
  Pochta,https://pochta.uz,ali,Пароль2026,izoh
  Bank,https://bank.uz,ali,Oʻzbek‘1,"ok"
  Last,https://x.example,u,p,trailing <0xC3>
  ```
- **Trigger, JSON.** Our own JSON export with one byte of a Cyrillic letter in a note changed to `0xFF`.
- **Observed, CSV:** `charset=windows-1251`; passwords `РџР°СЂРѕР»СЊ2026`, `OК»zbekвЂ�1`, `p`; the ASCII titles are unchanged; `problems=[]`.
- **Observed, JSON:** titles `РџРѕС‡С‚Р°, Bank`; passwords `РџР°СЂРѕР»СЊ1, OК»zbekвЂ�1`; `problems=[]`.
- **Why it matters.** The preview hides passwords, and Latin titles look normal. The app then offers "Delete file", so the user can lose the only good copy.
- **Fix.**
  - JSON is UTF-8 by definition (RFC 8259 §8.1): decode it strictly and refuse it on error, with no fallback.
  - For CSV, fall back to 1251 only when the bytes are not mostly UTF-8, for example when no valid multi-byte UTF-8 sequence occurs. Otherwise decode as UTF-8 with U+FFFD, list the affected lines on the preview, and say which encoding was chosen and why.

### P3 (major): nested JSON arrays crash the importer
- **Trigger.** `{"items":` followed by about 2,000 `[`, then the matching `]` and `}`: a 4,076-byte file. It overflows on a thread with a 1 MB or 512 KB stack. A 10 MB nesting overflows on the main thread with a 256 MB heap.
- **Observed** (`proto/attack3/Nest2.kt`): `java.lang.StackOverflowError` in kotlinx-serialization 1.11.0 `JsonTreeReader.read` ← `readArray`. Its deep-recursion path covers objects only; 5,000 nested objects parse fine.
- **Cause.** `Importer.read` catches `Exception`, so the `Error` escapes. On Android, an uncaught error in a coroutine kills the app. Nothing is written before Import, so no data is at risk.
- **Fix.**
  - Before parsing untrusted JSON, scan it once and refuse nesting deeper than about 32 levels (our format needs 5).
  - Also catch `StackOverflowError` around the parse.

### P4 (major): rows whose value count differs from the header are imported silently
- **Trigger.** Under the header `name,url,username,password,note`:
  - `Gmail,https://mail.google.com,aziz,pa,ss,word,my note`: an unquoted comma inside a password, as a hand-made file would have;
  - `Bank,https://bank.uz,ali`: a short row.
- **Observed:**
  - Gmail is imported with password `pa` and note `ss`; `word` and `my note` are dropped.
  - Bank is imported with no password.
  - `problems=[]`.
- **Context.** Chrome also ignores extra values, but this importer exists to read hand-made and Excel files.
- **Fix.**
  - Report each row whose value count differs from the header's, for example: "Line 2 has 7 values; the header has 5. Is there a comma inside a password? Put that value in quotes."
  - Show those rows on the preview and leave them out unless the user confirms.

### P5 (major): over-limit values drop whole entries, passwords included
**Triggers and observations** (`Attack.kt` §4, §5, §12):
- **Merged rows.** 12 adjacent rows `google.com,https://accountsN.google.com/,aziz@gmail.com,G00gle!,` are merged into one entry with 12 websites. Result: **0 entries**, and `skipped=[google.com: more than 10 websites]`.
- **Long website.** A website of 2,049 characters, in our CSV or JSON, drops the entry ("website longer than 2048"). SSO and redirect URLs saved by other managers reach this length.
- **Many fields.** More than 20 extra fields drop the entry. Bitwarden identities bring 18 strings of their own plus custom fields.
- **Long note.** The 40,000-character note case that §8.4 already lists.

**Context.** §8.4 suggests cutting the note rather than losing the password, but only for notes. ux.md §1 says "Never lose the user's data."

**Fix.**
- Stop merging at 10 websites and start a new entry.
- For any over-limit value, keep the entry: move the overflow into the notes or extra fields, cut what still does not fit, and list it ("Gmail: 3 websites moved to notes").
- Refuse an entry only when its password itself is over 1,000 characters.

### P6 (major): the user's own header languages are missed, and the mapping screen does not open
- **Trigger.** The Uzbek Cyrillic header `Номи,Сайт,Фойдаланувчи,Парол,Изоҳ`.
- **Observed:**
  - Only `Сайт` is recognised. Title, username, password and notes become **hidden extra fields**, and every entry is imported with an empty password.
  - The importer refuses a file only when none of title, website, username, password or notes is recognised, so neither a refusal nor the mapping screen happens.
  - The reuse check, the app's purpose, then sees no passwords.
- **Also missed:**
  - `Oʻzgartirilgan` (U+02BB) and `O‘zgartirilgan` (U+2018): the alias list has only an ASCII `'`, so the column becomes a hidden extra field.
  - `User Name` and `Web Site` written with a no-break space.
  - The KeePassXC header in lower case: it is read as a generic CSV, so its root group is kept as part of the folder name.
- **Fix.**
  - Open the mapping screen whenever no password column is recognised.
  - Add Uzbek Cyrillic aliases: парол, фойдаланувчи, фойдаланувчи номи, логин, сайт, манзил, ҳавола, изоҳ, эслатма, номи, сарлавҳа, папка, гуруҳ, электрон почта.
  - Normalise headers the way ux.md §4 normalises search: drop every apostrophe-like character, and treat every Unicode space as a space.
  - Detect KeePassXC case-insensitively.

### P7 (minor): lone surrogates (rule 2.8 not implemented; the writers disagree)
- **Trigger.** An entry containing `T\uD800x`, `u\uDC00`, `p\uD83D`, notes `n\uDE00\uD83Dz`, a website ending in `\uD800`, and a field `F\uD800` = `v\uDFFF`.
- **Observed, CSV and JSON.** Both write `?` (8 bytes each), and the password re-imports as `p?`.
- **Observed, XLSX.** The writer emits `_xD800_`:
  - POI restores the exact string.
  - LibreOffice cuts the cell at the escape: `T\uD800x` becomes `T`, `n…z` becomes `n`.
  - openpyxl and calamine show `_xD800_` literally.
- **Observed, warnings.** No writer reports anything, and the read-back hash check cannot notice.
- **Reachability.** `Limits.clean` removes surrogates on import, but nothing refuses them on input.
- **Fix.**
  - Refuse unpaired surrogates on input, along with control characters.
  - In one shared function used by every writer, replace them with U+FFFD and count them, as rule 2.8 says. Make the XLSX writer follow the same rule instead of `_xD800_`.

### P8 (minor): the CSV fields block does not round-trip everything
**Observed** (`Attack.kt` §16), each exported to our CSV and imported back:

| Exported | Imported |
|---|---|
| field `Q: mother's name` = `Aziza` | `Q` = `mother's name: Aziza` |
| visible field named `Code (hidden)` | hidden field `Code` |
| field named `  B` (leading spaces) | glued onto the previous field's value |
| a note containing a pasted `--- PasswordVault ---\nPIN: 9999` block | the note text becomes a field |

**Fix.**
- Escape `: ` and a trailing ` (hidden)` inside labels.
- Trim field names on input.
- Parse the block only when it is ours, for example by putting the field count in the marker: `--- PasswordVault (3 fields) ---`.

### P9 (minor): `.xlsx` bytes depend on the time zone and reveal the UTC offset
- **Trigger.** Write the same workbook with the default zone set to UTC, then Asia/Tashkent, then America/New_York.
- **Observed.** The files differ at byte 12. The local file headers say 12:00, 17:00 and 07:00 on 2000-01-01, because `ZipEntry.setTime` converts with the default zone (Android's libcore does too).
- **Spec.** §6.1's "an unchanged vault gives identical bytes" and the code comment "no time leak" hold only within one zone; files made in Tashkent reveal UTC+5.
- **Fix.** Fix the time in local terms, for example `entry.time = GregorianCalendar(1980, 0, 1).timeInMillis` in the default zone (or `setTimeLocal` on API 26+), so every phone writes the same DOS time.

### P10 (minor): import does not add `https://`
- **Trigger.** The rows `Mail,mail.ru,ivan,P1` and `Google, gmail.com ,a,P2`.
- **Observed.** The websites stay `mail.ru` and `gmail.com`. `FormatsTest` even asserts `urls == ["mail.ru"]`.
- **Spec.** §8.4 and ux.md §2 both say `https://` is added.
- **Fix.** Apply the shared URL input rule (`normalizeUrl`) to every imported website, and fix the test.

### P11 (minor): JSON import gaps
**Observed:**
- **No row limit.** 20,000 items are accepted; §8.1 says 10,000 rows.
- **Untitled empty entries.** An item with `"name": null` and `"login": null` is imported with an empty title and nothing else. ux.md requires a title; the CSV path derives one.
- **Silent drops:**
  - `null`, numbers and strings inside `items` are skipped without a message;
  - Bitwarden passkeys (`login.fido2Credentials`) are dropped without being listed.
- **Lost unknown keys.** Only unknown keys inside an item's `passwordVault` object survive re-export; root-level `passwordVault` keys and item keys outside the extension are lost. A newer app version that adds, say, folders at the root would lose them through this one.
- **Double BOM.** A JSON file that starts with two BOMs is treated as CSV (`NO_KNOWN_COLUMNS`).
- **String `"true"`.** `"encrypted": "true"` is refused, while Bitwarden's `isUnencrypted` (`encrypted !== true`) would import it.

**Fix.**
- Apply `MAX_ROWS` to `items`.
- Derive titles as on the CSV path.
- List everything not imported, including "passkeys are not imported".
- Keep unknown root extension keys.
- Strip every leading U+FEFF.
- Treat only boolean `true` as encrypted.

### P12 (minor): the BOM decoding branches hide damaged bytes
- **Trigger and observed, BOM.** A UTF-8 BOM in front of a Windows-1251 body gives the password `������1`, with `problems=[]`. The BOM branches decode leniently.
- **Trigger and observed, no BOM.** UTF-16LE without a BOM is refused as `BINARY`, a misleading message.
- **Fix.**
  - Use a reporting decoder in every branch and count the replacements.
  - Recognise BOM-less UTF-16 (a NUL in every other byte) and decode it.

### P13 (minor): common real-world CSV shapes
- **Firefox.** Each entry gets five hidden extra fields: `formActionOrigin`, `guid`, `timeCreated`, `timeLastUsed`, `timePasswordChanged`. The dates are lost, because they are epoch milliseconds.
- **Excel's `sep=` line.** A first line `sep=;` gives `NO_KNOWN_COLUMNS`.
- **Fix.**
  - Ignore `guid`, `httpRealm` and `timeLastUsed`.
  - Read `timeCreated` and `timePasswordChanged` as epoch-millisecond dates.
  - Keep `formActionOrigin` as a second website only when its host differs.
  - Honour and skip a leading `sep=X` line.

### P14 (minor): import changes passwords containing control characters
- **Trigger.** Passwords `a\tb`, `a\u0001b` and `\tab` in our CSV or JSON.
- **Observed.** They are imported as `ab`. The preview shows only a total count (`controlCharsRemoved=20` for the character-class file).
- **Conflict.** ux.md §2 says "passwords are stored exactly as typed because websites compare them exactly".
- **Fix.** Never alter a password. Either import it as it is, or leave that entry out and list it by name ("Gmail: the password contains a tab"). Keep the removal for titles, usernames and websites.

### P15 (minor): the Google summary card diverges from Chrome's checks
**Observed** (`proto/attack3/Report.kt`):
- **Uppercase scheme.** `HTTPS://MAIL.RU/` is counted as refused, but GURL lowercases the scheme and Chrome accepts it.
- **Cyrillic path.** A 1,018-character path is counted as fine, but it canonicalises to 6,030 characters, and Chrome compares `url.spec().length()` with 2,048.
- **Other invalid URLs.** `https://exa mple.com/` and `android://com.example.app/` (no certificate hash) are counted as fine, though Chrome refuses both (`IsValidPasswordURL`).
- **Oversized part.** `exportParts` emits a part over 1,000 KB when one entry's rows alone exceed it: a 1.1-million-character URL produced a 1,100,060-byte part. The report has no count for this.

**Fix.**
- Compare the scheme case-insensitively.
- Measure the punycode and percent-encoded length.
- Validate the host characters and the `android://<hash>@<package>/` shape.
- Count any part over the limit.

### P16 (minor): import planning cost grows with vault size times file size
- **Observed on this desktop.** 5,000 × 5,000 took 661 ms; 10,000 × 10,000 (all new) took 4,990 ms. A phone is several times slower.
- **Cause.** `covers` and `sameLogin` recompute hosts on every comparison.
- **Fix.** Index the vault and the accepted rows by (trimmed username, host) in hash maps, and compute each entry's hosts once.

### P17 (minor): statements that do not match the primary source
1. **HMAC key length (§7.2 step 1).** The spec says "HMAC key (64 B)". [MS-OFFCRYPTO] 2.3.4.14 step 2 says the Salt used as the HMAC key has `KeyData.saltSize` bytes, which is 16.
   - The reference workbook `example_password.xlsx` also stores a 64-byte key, and my decryptor verifies its HMAC only with all 64 bytes.
   - So 64 matches observed Office output. The spec should state that it departs from the text and why, and the real-Excel release check should cover it.
2. **Proton and `folders` (§5.1).** The spec says Proton "needs `items` and `folders` arrays". Proton defaults a missing `folders` to `[]`; only a non-array such as `null` fails. This is harmless for us.
3. **MIME mapping (§2 rule 4).** "The platform maps csv↔text/csv, json↔application/json [external/mime-support]" holds from Android 10.
   - On API 24 to 28, the table is libcore `MimeUtils`, which maps `text/comma-separated-values` to csv and has no `application/json` (android-7.0.0_r1).
   - `FileUtils.splitFileName` then keeps the display name unchanged, so the file names still come out right.
4. **The `_x005F_` rule (§6.2).** It is attributed to ST_Xstring. ECMA-376 Part 1 §22.9.2.19 does not state it; §22.4.2.4 does. LibreOffice and POI decode it correctly.
5. **The openpyxl row (§9).** One of its two differing cells also turns the literal `_x005F_` into `_`. openpyxl deletes any `x005F_` substring, so a password `x005F_` reads back empty.
   - `validate/check_xlsx.py` claims the opposite ("openpyxl decodes _xHHHH_ escapes").
6. **The EncryptionInfo XML (§7.2 step 5).** The text omits the `<keyEncryptors>` wrapper that the code (correctly) writes.

### P18 (minor): validation scripts
- **`check_json.py`.** `validate/check_json.py` ends in `StopIteration`. It looks for "Control characters" in `export.json`, which holds only valid entries. The earlier agent's own `lo/run_all.log` shows the same crash.
- **Locale-dependent test helper.** `FormatsTest`'s `id()` and `"%04d".format` use the default locale. Under ar-SA, fa-IR or th-TH-TH the test ids contain Arabic-Indic digits and the outputs differ. The prototype's own formatting is locale-safe: the dates are identical under ar, fa, tr, th and uz.
- **Fix.** Use `Locale.ROOT` in the test helpers, and fix the script.

## What held up (re-checked independently)
- **Prototype suite.** Rebuilt from copies, `FormatsTest` passes: "ALL CHECKS PASSED" (CSV 5,017 rows, 2 parts; JSON 5,011 entries lossless; XLSX 5,015 rows; one cell truncated).
- **Their validations, re-run with my venv:**
  - Python `csv`: 5,017 rows identical.
  - Chromium transcription: 5,014 OK, 2 LONG_NOTE, 1 MISSING_PASSWORD, 0 mismatches.
  - openpyxl: 55,176 cells, 2 differ.
  - msoffcrypto: password and integrity verified; the decrypted bytes equal the `.xlsx`.
  - Wrong password refused; a flipped byte fails integrity.
  - DataSpaces streams byte-identical to the reference workbook.
- **LibreOffice 24.2.7.2, `--convert-to csv` with a private profile.** The prototype's 5,016 × 11 workbook matches in all 55,176 cells. My attack workbooks also match, except the lone-surrogate cells (P7).
- **Writers under attack.** I tried every character class in the spec plus U+FEFF (start and middle), U+2028, U+2029, U+0085, no-break space, soft hyphen, zero-width space, LRM, RLO, U+FFFD, U+FFFE, U+FFFF, U+FDD0, U+1FFFE, private use and emoji ZWJ sequences.
  - Other inputs: full-width `＝`, `_x0041_`, `_X0041_`, `_x00e9_`, `x005F_`, `<a&b>&amp;`, `]]>`, CR without LF, passwords made of 1 to 8 double quotes, 1,000 custom fields, URLs up to 1.1 million characters, and the empty vault.
  - Python `csv` and my Chrome transcription read every CSV back exactly.
  - Python `json` reads every JSON string exactly.
  - LibreOffice and POI read every `.xlsx` cell exactly, except P7. openpyxl and calamine differ only on `_xHHHH_` escapes.
- **Schemas.** All 15 generated plain workbooks validate against the ECMA-376 5th-edition Transitional `sml.xsd` and the OPC schemas, with 0 problems. Only Excel's usual `xml:space` on `<t>` is filtered, and the validator catches element-order and type errors on a deliberately broken copy.
  - ECMA-376 Part 1 §12.3.15 requires exactly one Shared String Table part and §12.3.20 allows at most one Styles part, so the seven-part package is right.
- **Locked workbook.** All four readers decrypt every file to the exact bytes or cells and refuse a wrong password: msoffcrypto-tool 6.0.0, my decryptor written from MS-OFFCRYPTO 2.3.4.11 to 2.3.4.15, Apache POI 5.5.1 and LibreOffice UNO.
  - Sizes tested: payloads of 0, 1, 15, 16, 17, 4,079, 4,080, 4,087, 4,088, 4,095, 4,096, 4,097, 8,192 and 65,536 bytes, 7.0 MB, 7.2 MB (first DIFAT sector) and 15 MB; the 8.9 MB workbook (137 FAT sectors, 1 DIFAT); the empty vault (mini stream).
  - msoffcrypto refuses non-ZIP payloads by design, which is why only my reader was used for the random payloads.
  - LibreOffice opened the 8.9 MB workbook in 12 to 13 seconds.
- **Agile construction against the spec text.** It matches MS-OFFCRYPTO's key generation, IV generation, the three password block keys, the HMAC over the whole EncryptedPackage stream including StreamSize, the two integrity block keys, 4,096-byte segments with a u32 segment index, and "any padding bytes can be used". The only exception is P17.1.
- **MS-CFB.** "The simplest implementation … would be to mark every node as black" is stated in MS-CFB "Red-Black Tree".
- **Chromium HEAD** (fresh copies, byte-identical to the earlier agent's). These §4.1 and §4.2 claims hold:
  - header and quoting; LF, with CRLF only on Windows; one row per affiliated domain, sorted by name, url, username, password, note;
  - the aliases, ASCII trim and lower-case, note priority; a duplicate label fails the whole file; url, username and password are required; `name` is ignored; the BOM is not stripped;
  - the leading `\r \t` trim; row checks in UTF-8 bytes; the URL rules; 1,000 KB and 3,000 rows; duplicate and conflict handling.
- **Bitwarden HEAD.** `isUnencrypted` is `encrypted !== true`; the first 5 history entries are kept; the Chrome CSV mapping includes `android://` → `androidapp://`; the CSV exporter's `login_uri` list and `name: value` fields; the importer splits fields at the last `: `.
- **Proton Pass HEAD.**
  - CSV: requires the name, url, username and password headers; reads with papaparse using `,` and `\n`, trimming headers and removing CR. papaparse treats the CR between a closing quote and the newline as space, so our CRLF files parse.
  - Bitwarden reader: refuses encrypted files; maps fields, TOTP and URLs; does not read favourite, dates or history.
  - The Android app only links to the help page (`ProfileScreen.kt:171`).
- **KeePassXC.**
  - Bitwarden import arrived in 2.7.7, dates and history in 2.7.11, nested folders in 2.7.12. It needs the `folders` and `items` keys; hidden fields become protected attributes; favourite becomes a tag.
  - CSV import matches column names case-insensitively, and the CSV exporter quotes every field.
- **Web sources:**
  - Microsoft "Excel specifications and limits": 32,767 characters per cell, 253 line feeds, 255 in a header or footer, 409 points row height.
  - Google: 3,000 passwords per file, 10,000 per account, headers url, username, password.
  - 1Password: no unencrypted Bitwarden JSON; Chrome CSV in all apps, Android included.
  - Bitwarden: imports in the web app, extension, desktop and CLI; on a phone through a browser or CXP.
  - hashcat mode 9600 on an RTX 4090: 68,356 H/s.
- **Formula risk in the CSV (§4.6), confirmed and larger than shown.** LibreOffice's default CSV import of the prototype's own 5,000-entry CSV produced 69 formula cells: `=HYPERLINK(…)`, plus random passwords starting with `=`, one of which became `=G7*j,2swsj['#REF!`. The username `+998901234567` became a number. The warning is justified; the summary card could count such values.
- **Writers are locale-safe.** Under ar, fa, tr, th, th-TH-TH and uz, the JSON and `.xlsx` date strings are identical.
- **CSV quoting.** A value starting or ending with a space or tab is quoted, and the leading-space password survives Python, the Chrome transcription, LibreOffice and our reader.

## Other notes
- **EFF wordlist.** Four EFF words contain a hyphen (`drop-down`, `felt-tip`, `t-shirt`, `yo-yo`). Joined with hyphens, the word count looks wrong to the reader. Use spaces, or drop those four words.
- **Google's 1,000-byte note limit.** A long note or fields block makes Google skip the whole credential, password included. The summary card says so; an option to cut notes at 1,000 bytes for Google may serve this user better.
- **Excel sheet names.** Excel also forbids a name starting or ending with an apostrophe, and the name "History". `Xlsx.write` checks neither; the labels are app-controlled, so this matters only for translators.

## Files (all under `scratchpad/design/formats-break/`)
- `run_all.sh` and `run_all.log`: reproduce and record everything (about 90 seconds).
- `proto/`: copied prototype sources, `build.sh`, and the attack harnesses:
  - `attack/Attack.kt`: sections 1 to 19;
  - `attack/Attack2.kt`: JSON, memory bombs, time zones, wide headers;
  - `attack3/Nest.kt` and `Nest2.kt`: stack depth;
  - `attack3/Shapes.kt`: real-world CSV shapes;
  - `attack3/Report.kt`: summary-card edges.
- `check/`:
  - `read_plain.py`: Python csv and json, the Chrome transcription, openpyxl and calamine;
  - `lo_compare.py`: LibreOffice CSV conversions against the intended values;
  - `xsd_validate.py`: ECMA-376 and OPC schemas;
  - `agile_check.py`: independent MS-OFFCRYPTO decryptor plus msoffcrypto and olefile;
  - `uno_open.py`: LibreOffice with a password.
- `poi/PoiRead.java`: Apache POI reader.
- `src/`: sources fetched today (Chromium, Bitwarden, Proton Pass, papaparse, ECMA-376 parts 1, 2 and 4 with schemas, Android 7 `MimeUtils`, the hashcat benchmark).
- `out/`: the prototype's own outputs. `out-attack/`: every attack file plus `manifest.json` (intended values as UTF-16 code units). `lo/` and `poi/dump/`: reader dumps.
