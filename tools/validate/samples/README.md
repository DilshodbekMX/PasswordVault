# Sample export files

## `prototype/`: files the export prototype wrote on 2026-10-06

Written by `prototypes/formats/test/FormatsTest.kt` (run `FormatsTestKt out` after `prototypes/formats/build.sh`) in the session's scratchpad, rescued into `docs/holding/formats-samples/`, and moved here by build-plan package 0.3. Nothing in them is real: the entries are the prototype's special cases (Uzbek with U+02BB and U+2018, Cyrillic, emoji, Hebrew and Arabic, commas, quotes, CR/LF/CRLF in notes, values starting with `=` `+` `-` `@`, tab, U+0001, U+0007, U+001F, U+FFFE, a literal `_x0041_`, a 40,000-character note, a 300-line note, an all-empty entry) and 5,000 generated ones. The workbooks were written with the zone Asia/Tashkent.

They predate architecture.md §6: the CSV note block still has the old marker `--- PasswordVault ---` without a line count (D-10a), the zip entries carry the zone-dependent time 2000-01-01 17:00 (formats-review P9), and control characters were written as found. They are not vectors of the version 1 formats; they are inputs for `tools/validate/run.sh`, and the locked files are what formats.md §7 and formats-review.md tested.

| File | What |
|---|---|
| `export-part1.csv`, `export-part2.csv` | All entries in Google's CSV layout, split as formats.md §4.4 says: 3,000 and 2,017 rows |
| `export.xlsx` | All 5,015 entries as a plain workbook, one row each under the header (5,016 × 11 cells); one cell is cut at 32,767 characters |
| `special.xlsx` | The 15 special entries only |
| `special-small.xlsx` | The first three special entries |
| `empty.xlsx` | An empty vault |
| `export-protected.xlsx` | `export.xlsx` locked with the password `tulip-arena-swirl-cobalt-mango` (ECMA-376 Agile Encryption in a compound file) |
| `special-protected-unicode-pw.xlsx` | `special-small.xlsx` locked with `Parol‘ Пароль 2026` (U+2018) |
| `empty-protected.xlsx` | `empty.xlsx` locked with `tulip-arena-swirl-cobalt-mango` |
| `tampered-protected.xlsx` | `export-protected.xlsx` with one bit of the encrypted package flipped (written by `prototypes/formats/validate/check_protected.py`); its integrity check must fail |
| `fixed-*.bin` | The four fixed DataSpaces streams of a locked workbook, byte-identical to those of Excel's own reference file |

The JSON export and the expected-value files of that run (`expected-csv-rows.json`, `expected-xlsx-cells.json`) were not rescued. Running the prototype again writes all of them; the plain files then come out byte for byte the same when the JVM's zone is Asia/Tashkent (checked on 2026-10-06).

`tools/validate/run.sh --allow-missing samples/prototype/export-part1.csv samples/prototype/export-part2.csv samples/prototype/export.xlsx` passes every check except the workbook's zip time (P9), as it should for these files.

The locked workbooks are checked by `prototypes/formats/validate/check_protected.py` and `lo_read.py`, which stay with `OfficeCrypto.kt` in `prototypes/formats/` (architecture.md D-16), not by `tools/validate/`. D-16's release question, whether Microsoft Excel opens a locked and a plain file without a repair prompt, is to be asked again with files from the app's own writer.
