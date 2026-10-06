# Import fixtures: where each file comes from

Test data for the importer (build-plan packages 3.15 and 3.11). These files are used by tests only and are never packaged into the app. Brought into the repository by build-plan package 0.3 on 2026-10-06; from then on a change here is an interface request.

## `proton/`: the real fixtures of formats.md §8.5

Copied unchanged from Proton Pass's import test suite, the same files the formats work recognised and mapped on 2026-10-06 (formats.md §8.5). Downloaded again for this commit from the pinned commit below; each file's git blob id equals the id GitHub lists for that path, and the bytes equal the copies the formats work used.

- **Origin:** repository `ProtonMail/WebClients`, commit `b4be0231837aec0511955b0ba969bcf9b12d10b1` (HEAD on 2026-10-06), `https://github.com/ProtonMail/WebClients/tree/b4be0231837aec0511955b0ba969bcf9b12d10b1/packages/pass/lib/import/providers`.
- **Licence:** GNU General Public License v3.0, the licence of the repository (its `LICENSE`) and of the package `@proton/pass` (`"license": "GPL-3.0"` in `packages/pass/package.json`). The full text, copied from the repository's `LICENSE` at the same commit, is in `proton/LICENSE`. The files are kept unmodified and with that notice.

| File here | Path in the repository | Bytes | Git blob | SHA-256 | Content |
|---|---|---|---|---|---|
| `proton/chrome.csv` | `chromium/mocks/chrome.csv` | 283 | `b5106ef8ef79bc21683ae0af2a6533fdc52b1a91` | `15f8750cb364e0955ed1b328278a86e54a44e39ecae304b827619d1f040d8f08` | Chrome's header `name,url,username,password,note` and 4 rows: a complete one, one without a url, one without a password, one with the broken url `htt:/proton.me/switch`. ASCII, LF |
| `proton/chrome-windows.csv` | `chromium/mocks/chrome-windows.csv` | 228 | `ac98c987f4689b987ceb736978cb99901b2692f4` | `fe4afeb4126ed21175f356cde67b29d3d4033e47e4d0675a4e165c75ada9ae38` | The first 3 rows of `chrome.csv`. Despite its name it has LF line ends, not CRLF |
| `proton/bitwarden.json` | `bitwarden/mocks/bitwarden.json` | 10,127 | `fc9d3bd7ca467faf1a3b03efc2cc6d16a8f8569e` | `81590b3df22fbc81848b161e919c4e01f2e1f77c2d2ff1d1b941e1594ecf145d` | An unencrypted Bitwarden export: 5 folders (nested ones and an empty one), 12 items: 7 logins (several websites, empty fields, a broken URL, custom fields, TOTP, items in nested folders), 2 cards, 1 identity, 1 secure note and 1 SSH key (whose `passwordHistory` and `deletedDate` are null) |
| `proton/LICENSE` | `LICENSE` (repository root) | 32,473 | | `1b3782ccad7b8614100cda30d3faf42fc39f2e97932908c543005053b654ca68` | The GPL-3.0 text |

`tools/validate/check_json.py` also uses `proton/bitwarden.json`: it compares the item keys of our JSON export with the keys of this file's logins.

## `uzbek-cyrillic-headers.csv`: the trigger of formats-review.md P6

- **Origin:** written for this project by build-plan package 0.3 on 2026-10-06. The header line is exactly the trigger of formats-review.md P6, `Номи,Сайт,Фойдаланувчи,Парол,Изоҳ` (Изоҳ ends with U+04B3); the three rows are made up.
- **Licence:** this project's own, like the rest of the repository.
- **Shape:** UTF-8 without a BOM, LF line ends, comma separator, 290 bytes, SHA-256 `89e7fb92400ebffa8b664bf7c4a9e3e0fe7bce162c9cd980962117d9d04ba22c`.

| Row | Номи | Сайт | Фойдаланувчи | Парол | Изоҳ |
|---|---|---|---|---|---|
| 1 | `Почта` | `https://pochta.uz` | `aziz` | `Парол-2026!` | `шахсий` |
| 2 | `Банк` | `bank.uz` | `+998901234567` | `Oʻzbek‘1!` (U+02BB, then U+2018) | two lines, quoted: `карта PIN коди` and `алоҳида дафтарда` |
| 3 | `Davlat xizmatlari` | `https://my.gov.uz` | `AA1234567` | `Toshkent#2026` | empty |

What the importer should make of it once P6 is fixed (formats.md §8.3, §8.4): every column recognised, so the column-mapping screen does not open and no column becomes an extra field; 3 entries with those titles, usernames and notes; the passwords exactly as written, the two apostrophe letters of row 2 included (folding applies to header names and to the master password, never to a stored password); the website of row 2 becomes `https://bank.uz` (P10). Before the fix the prototype recognised only `Сайт` and imported every entry with an empty password.
