# prototypes/formats: the locked workbook, kept for version 1.1

What is left of the formats prototype after build-plan package 3.15 (2026-10-08). The plain formats moved into `:core` (`core.formats`: packages 1.4, 3.11 and 3.15) and were removed from here; the prototype as it was is in the history at the tag `wave-3B-merged`.

Excel with a password is not in version 1 (architecture.md D-16, formats.md §7). Its code and its checks stay here for 1.1:

| File | What |
|---|---|
| `src/pv/formats/OfficeCrypto.kt` | ECMA-376 Agile Encryption and the Compound File container, `AgileEncryption.encrypt(xlsxBytes, password)`; JDK only |
| `validate/check_protected.py` | msoffcrypto-tool and olefile: the password opens and the HMAC holds, a wrong password and a flipped byte are refused, the DataSpaces streams equal those of the reference workbook in msoffcrypto-tool's tests |
| `validate/agile_check.py` | formats-review.md's own decryptor, written from the [MS-OFFCRYPTO] text, which also runs msoffcrypto-tool and olefile |
| `validate/lo_read.py` | opens a workbook in a headless LibreOffice through UNO, with a password, and dumps its cells |
| `validate/uno_open.py` | the same for several locked workbooks, a wrong password tried as well |
| `validate/PoiCheck.java` | Apache POI as a third reader |

Each script says how to run it in its first lines. The prototype's locked samples are in `tools/validate/samples/prototype/` (its `README.md` says how they were made); the checks of the plain exports are `tools/validate/`. The attack inputs of formats-review.md, which `attack/` used to write, are now built from each finding's trigger by `core/src/test/java/io/github/dilshodbekmx/passwordvault/core/formats/importer/ImportReviewFindingsTest.kt`.
