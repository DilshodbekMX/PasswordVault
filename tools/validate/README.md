# tools/validate: independent checks of the plain exports

Checks a CSV, JSON and Excel export of one vault with readers other than our own: Python's `csv` and `json`, a transcription of Chromium's CSV importer, openpyxl and LibreOffice. Used by build-plan package 3.11 (`tools/validate/run.sh core/build/samples`) and by the integration check of wave 5 (the three files exported on the emulator). Brought into the repository by package 0.3 from `prototypes/formats/validate/`; the checks of locked workbooks stay there with `OfficeCrypto.kt` (architecture.md D-16).

## Use

```
tools/validate/setup.sh                      # once; run.sh calls it when the venv is missing or out of date
tools/validate/run.sh DIR                    # DIR holds the files of one export
tools/validate/run.sh FILE...                # or name them
```

Options: `--allow-missing` checks what is there when a format is missing; `--no-cross` skips the comparison of the formats with each other (for files that were not exported together); `--show-values` prints the values that differ, which may be passwords. Output: one `PASS`, `FAIL`, `INFO` or `SKIP` line per check, then `RESULT`. Exit status 0 when every check passed, 1 when one failed, 2 when the files could not be checked (missing, ambiguous, no LibreOffice).

**Which files.** `*.csv` is the CSV export; `NAME-part1.csv`, `NAME-part2.csv`, … are the parts of one export, read in that order. `*.json` is the JSON export. `*.xlsx` is the Excel export when it is a zip package; a locked workbook is reported and skipped. A directory must hold one export of each format, otherwise run.sh refuses it as ambiguous. The app's own names (formats.md §2 rule 5) fit: `passwords-2026-10-06.csv` or its parts, `passwords-2026-10-06.json`, `passwords-2026-10-06.xlsx`.

**Expected values (optional).** A writer's test may put beside the exports what it meant to write; they are then compared exactly:
- `expected-csv-rows.json`: the CSV rows without the header, all parts together, each a list of 5 strings;
- `expected-xlsx-cells.json`: the sheet's rows with the header first, each a list of strings; a cell the writer had to cut at 32,767 characters is `"truncated:"` followed by the first characters it kept.

## What is checked

| Format | Check | Source of the rule |
|---|---|---|
| CSV | The bytes are exactly the layout: UTF-8, no BOM, the header, CRLF after every row, a value quoted when it holds `,` `"` CR or LF or starts or ends with a space or tab, nothing else quoted | formats.md §4.4 |
| CSV | Python's `csv` and the Chromium transcription read the same url, username, password and note from every row; the rows Chrome would refuse are listed (information) | formats.md §4.2 |
| CSV | Each part within 3,000 rows and 1,000 KB; no entry's rows straddle two parts | formats.md §4.4 |
| JSON | UTF-8 without BOM, no CR, final newline, two-space indent; no duplicate key, NaN or unpaired surrogate | formats.md §2 rule 8, §5.2 |
| JSON | The keys and types Bitwarden, Proton Pass and KeePassXC read; `encrypted` false; UUID ids, none twice; at most 5 previous passwords; `deletedDate` null; empty values as null; our `passwordVault` objects | formats.md §5; formats-review P1; architecture.md D-10c, D-10e |
| JSON | The keys of Proton's fixture (`core/src/test/resources/formats/proton/bitwarden.json`) that we lack and those we add (information) | |
| Excel | Exactly the seven parts, deflated, every entry at 1980-01-01 00:00 local with no time field | formats.md §6.1; formats-review P9 |
| Excel | Only shared strings; no formula, inline string, hyperlink or external link; correct string counts; one sheet of 11 columns; frozen header, print titles, filter, bold header, format `@` | formats.md §6.1, §6.2 |
| Excel | LibreOffice (headless, private profile) and openpyxl read the same cells, or the expected cells | formats.md §6.2 |
| All three | Compared with each other: the same titles, usernames and passwords, once per JSON item and Excel row, once per website in the CSV | formats.md §4.4, §5.2, §6.2 |

Two reader quirks are allowed for and reported, not failed: openpyxl does not decode `_xHHHH_` escapes and deletes `x005F_` (formats-review P17.5), so cells holding such text are left to LibreOffice; and LibreOffice 24.2 adds a line feed at a space near the 16,384th UTF-16 unit of a longer text cell.

## Files

| File | What |
|---|---|
| `run.sh`, `setup.sh` | Entry points. The venv lives in `/home/asus/pvlab/validate/venv` (`PV_VALIDATE_HOME` moves it). This machine's Python has no `ensurepip`, so the venv is made without pip and the system's pip installs into it with `--python`; the venv does not see the system's packages |
| `requirements.txt` | openpyxl 3.1.5 and et-xmlfile 2.0.0, pinned with their SHA-256 and installed with `--require-hashes --only-binary=:all:` |
| `validate.py` | Finds the files and runs the checks below |
| `check_csv.py`, `check_json.py`, `check_xlsx.py` | The checks of each format; each also runs alone (`--help`) |
| `lo_convert.py` | The LibreOffice conversion to CSV |
| `compare_cells.py` | LibreOffice's cells against openpyxl's or the expected ones |
| `compare_formats.py` | The three formats against each other |
| `samples/` | Sample exports; see its README |

`check_json.py` carries the fix of formats-review P18: the prototype's script looked up two entries of its own test data by name and crashed with `StopIteration` on any other file, and printed passwords. The prototype's `FormatsTest` part of P18 (`Locale.ROOT` in its test helpers) belongs to package 3.11.

## Tried on 2026-10-06

- On the prototype's samples in `samples/prototype/` (the two CSV parts and `export.xlsx`; their JSON was not rescued, so `--allow-missing`): every check passes except the Excel zip time, which is the prototype's known defect P9 (it wrote 2000-01-01 17:00, noon UTC in Tashkent).
- On a fresh run of the same prototype (`prototypes/formats/build.sh`, then `FormatsTestKt out` with the zone Asia/Tashkent; the plain files came out byte for byte the same as the samples), with its JSON and both expected-value files: the same result. The CSV rows equal `expected-csv-rows.json`; LibreOffice reads all 5,016 × 11 cells as `expected-xlsx-cells.json` says; openpyxl differs only in the 2 cells with control characters; Chrome would refuse 2 rows for a long note and 1 for a missing password, as formats.md §9 reports; the JSON passes. With the prototype's `export-all.json`, written from the same entries as the CSV and the workbook, the three formats agree (5,015 accounts, 5,017 CSV rows).
- On damaged copies (a BOM and one LF row end in a CSV; a repeated id, an empty-string password and an unpaired surrogate in a JSON): each fails where it should.
