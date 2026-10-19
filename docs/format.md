# PasswordVault backup files

## Open a backup without the phone (read this first)

A backup file of PasswordVault holds all your accounts, locked with the master password you had when you made the backup. Its name starts with `PasswordVault-backup` and ends in `.pvault`, for example `PasswordVault-backup-2026-10-07.pvault`. If the phone and the app are gone, the script **`pvault_decrypt.py`** (in the folder `tools` of the PasswordVault project) opens it on any computer and writes your accounts into a new file that other password apps can import.

**What to install, once**

1. **Python 3**, version 3.9 or newer, from <https://www.python.org/downloads/>. On Windows, tick "Add python.exe to PATH" on the installer's first screen.
2. **One package: `cryptography`, version 44 or newer.** Version 44.0.0 is the first that can do Argon2id, which every backup needs, so an older version cannot open a backup. Open a terminal (on Windows: press the Windows key, type `cmd` and press Enter) and type:
   - on Windows: `py -m pip install --upgrade cryptography`
   - on macOS or Linux: `python3 -m pip install --upgrade cryptography`

   On some Linux systems pip refuses to install for the system's own Python ("externally managed"); the script's message then says how to make a small environment of its own for it.
3. Check that the computer has everything: `py pvault_decrypt.py --check` (on macOS and Linux, here and below: `python3` instead of `py`). Its last line says "Everything is ready".

**How to run it**

1. Put `pvault_decrypt.py` and your backup file in one folder.
2. Open a terminal in that folder. On Windows: click the address bar of File Explorer, type `cmd` and press Enter. On a Mac: open Terminal (in Applications › Utilities), type `cd` and a space, drag the folder into the window and press Enter.
3. Type, with the name of your own backup file:

   ```
   py pvault_decrypt.py PasswordVault-backup-2026-10-07.pvault passwords.csv
   ```

   You can also double-click `pvault_decrypt.py`: it then asks for the backup file (drag it into the window) and for the name of the new file.
4. Type the master password you had when you made the backup. Nothing is shown while you type; that is normal. The script says how many accounts the backup holds and waits until you type `yes` before it writes anything.

The end of the new file's name chooses what the script writes:

- **`passwords.csv`**: Google Password Manager, Chrome, Bitwarden, Proton Pass, 1Password and KeePassXC can import it.
- **`passwords.json`**: Bitwarden, Proton Pass and KeePassXC can import it, and PasswordVault itself reads it back with everything, previous passwords included.

Accounts in Recently deleted are not written, as in the app's own export.

**The new file is not protected.** Anyone who opens it can read all your passwords: it has no password. Save it only where you control it: your own computer or USB stick. Don't send it by Telegram, email or other messengers. Delete it as soon as you're done, and empty the Recycle Bin or Trash. Don't open the CSV file in Excel: Excel changes phone numbers and codes and can run formulas. The script never writes over a file that already exists.

Keep a copy of `pvault_decrypt.py` and of this page next to your backups, for example on the same USB stick.

**What the script may say**

| The script says | What to do |
|---|---|
| This password does not open this backup. Try the master password you used when you made it. | Type the master password you had on the day of the backup. After a change of the master password, older backups still open with the old one. A letter such as oʻ or gʻ may be typed with any apostrophe: ʻ, ‘, ’ and ' all count as the same. After five tries the script stops; run it again. |
| This file may be damaged, or the password is wrong. | Try the password once more. If it still does not open, try another backup file. |
| This backup file is damaged and cannot be opened. | Try another backup file. |
| This is not a PasswordVault backup. | Choose the file whose name starts with `PasswordVault-backup`. |
| This backup was made by a newer version of PasswordVault. | Get the newest `pvault_decrypt.py` from the PasswordVault project. |
| This script needs the package "cryptography", version 44 or newer. | Install it as described above, then run the script again. |
| … already exists. | Give the new file another name, or move the old file away first. |

---

## The format (for programmers)

The rest of this page describes version 1 of the vault file for readers outside the project, so that anyone can open a backup with their own code. Inside the project, the same rules are normative in [architecture.md](design/architecture.md) §4 ("A"), with the master password's bytes in [lock.md](design/lock.md) §4 and §8.1; where this page and those differ, those win. Every number written in a file uses the ASCII digits 0 to 9.

### 1. In short

One container serves a backup file and each saved copy ("generation") of the vault on the phone.

```
master password --NFC, white space and apostrophes folded, UTF-8--> bytes
bytes, salt     --Argon2id v1.3, 65,536 KiB, 3 passes, 4 lanes------> KEK (32 bytes)
KEK             --AES-256-GCM, the slot's nonce, no AAD------------> opens the slot: VK (32 bytes)
VK              --AES-256-GCM, the body nonce, AAD = the file's first 12 + header_len bytes--> opens the body: JSON
```

- **VK**, the vault key, is 32 random bytes. Each backup has its own VK (and its own `vk_id`, salt and slot); a vault on the phone gets a new one at every change of the master password.
- **Exactly one password slot** holds VK, encrypted under the KEK. There is no slot for a PIN or a fingerprint: those exist only on the phone that made them.
- **The header is the associated data** of the body, so a changed header makes the body fail.
- **A SHA-256 trailer** at the end detects damage before a password is typed. It authenticates nothing.

### 2. The container

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Magic: the ASCII bytes `PVAULT01` |
| 8 | 4 | `header_len`: unsigned 32-bit, big-endian, 2 to 4,096 |
| 12 | `header_len` | Header: ASCII JSON in the one form of section 3 |
| 12 + `header_len` | 12 | `body_nonce` |
| 24 + `header_len` | `body_len` | `body_ct` = AES-256-GCM(VK, `body_nonce`, plaintext of section 5, AAD), with the 16-byte tag at its end |
| 24 + `header_len` + `body_len` | 32 | Trailer: SHA-256 of every byte before it |

- **AAD** = the first 12 + `header_len` bytes of the file exactly as stored: magic, `header_len` and header. The body nonce is **not** part of it.
- **File length** = 56 + `header_len` + `body_len`, at most **16,777,216 bytes**. Nothing follows the trailer.
- **What every later version keeps**, so that a version 1 reader can tell "newer" from "damaged": a magic of the form `PVAULT` and two ASCII digits; the 4-byte big-endian length after it; a header that starts with `{"format":"passwordvault","version":N,`; and the SHA-256 trailer over everything before it. A change of the framing takes new digits in the magic; a change inside the header or the body takes a new `version`.

### 3. The header

The header is **byte for byte** this text, with nothing added, removed or reordered and no white space, where each `<…>` is replaced as the table says:

```
{"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"<vault_id>","vk_id":"<vk_id>","generation":<generation>,"created":<created>,"generator":"<generator>","writer_version":<writer_version>,"body_len":<body_len>,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"<salt>","nonce":"<nonce>","wrapped_key":"<wrapped_key>"}]}
```

| Value | Form | Meaning |
|---|---|---|
| `vault_id`, `vk_id` | `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}` | A lower-case UUID of version 4. `vault_id` is made when a vault is created, restored or replaced and never changes; `vk_id` is made with every new VK |
| `generation` | `[1-9][0-9]{0,9}` | 1 to 9,999,999,999. In a backup, the number of the vault's generation it was made from |
| `created` | `0` or `[1-9][0-9]{0,15}` | Milliseconds since 1970 UTC when the file was sealed, by the phone's clock. For display only |
| `generator` | `[A-Za-z0-9 ._()-]{1,64}` | For people: `PasswordVault` and the app's version name, such as `PasswordVault 1.0` |
| `writer_version` | `[1-9][0-9]{0,9}`, at most 2,147,483,647 | The app's version code |
| `body_len` | decimal without a leading zero | Equals the file length − 56 − `header_len`; at least 4,112; `body_len` − 16 is a multiple of 4,096 |
| `salt`, `nonce`, `wrapped_key` | Base64, standard alphabet with padding: exactly 24, 16 and 64 characters; decoding and encoding again gives the same text | 16, 12 and 48 bytes: the Argon2id salt, the slot's AES-GCM nonce, and VK encrypted (32 bytes and the 16-byte tag) |

- **The KDF's constants are fixed text**: a file with any other value is not a version 1 file.
- A reader needs no JSON parser for the header: one anchored pattern with ten captures, then the numeric and Base64 checks of the table. Duplicate keys, unknown keys, other spellings of a number and white space are all refused by the same comparison.
- The stored header bytes are the AAD. A reader never writes them out again from what it parsed.

### 4. Keys

**The master password's bytes.** The typed characters become bytes in four steps, the same on every phone and frozen with version 1:

1. **NFC** (Unicode Normalization Form C).
2. **White space.** Each of these 25 code points becomes U+0020: U+0009 to U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Then a run of U+0020 becomes one, and those at the start and at the end are removed.
3. **Apostrophes.** Each of these 17 code points becomes U+0027 (`'`): U+0027, U+0060, U+00B4, U+2018, U+2019, U+201B, U+2032, U+2035, U+02B9, U+02BB, U+02BC, U+02BD, U+02BE, U+02BF, U+A78B, U+A78C, U+FF07. Uzbek oʻ and gʻ are typed with different characters on different keyboards; after this step they give the same key.
4. **UTF-8.** Upper and lower case are kept. An unpaired UTF-16 surrogate, which is not a character, is written as the UTF-8 of U+FFFD (`EF BF BD`); the app never lets a new master password have one.

NFC comes first because it turns U+0374, U+1FEF and U+1FFD into members of the apostrophe list; so every canonically equivalent spelling gives the same bytes, and the function applied to its own result changes nothing.

**KEK** = Argon2id, version 1.3 (`0x13`), memory 65,536 KiB (64 MiB), 3 passes, parallelism 4, the slot's 16-byte salt, a 32-byte output, no secret and no associated data, over the master password's bytes.

**VK** = AES-256-GCM-Open(KEK, the slot's 12-byte nonce, `wrapped_key`, no AAD): 32 bytes. If the tag does not verify, this password does not open this file.

**The body** = AES-256-GCM-Open(VK, `body_nonce`, `body_ct`, AAD). The tag is the last 16 bytes of `body_ct`.

Every seal uses a fresh random 12-byte nonce: one for the body and one for a new slot.

### 5. The body

**Plaintext** = one JSON object in strict UTF-8 (no byte-order mark, no malformed sequence, no encoded surrogate), followed by as many space bytes (0x20) as make its length a multiple of 4,096: 0 to 4,095. The padding hides small changes of size; a reader strips the trailing spaces before it parses.

The JSON is read strictly (RFC 8259): no key twice in one object, nesting at most 16 deep (objects and arrays both count; the top object is depth 1), literals only `true`, `false` and `null`, no control character inside a string, only the escapes of RFC 8259, nothing but white space after the object. Key names are camel case. **All times are integers in plain decimal: milliseconds since 1970 UTC.** An integer matches `-?(0|[1-9][0-9]{0,18})` and fits a signed 64-bit value; a number with a fraction or an exponent where an integer belongs is the wrong type.

| Top-level key | Type | When missing | Meaning |
|---|---|---|---|
| `schema` | integer | required | 1. A higher number means a newer version of the app wrote the file |
| `entries` | array of entry objects | required | The accounts, Recently deleted included |
| `draft` | null or object | null | The one unsaved form: `{"entryId": string or null, "savedAt": integer, "entry": {…}}`, whose `entry` has the keys `title`, `username`, `password`, `notes`, `urls`, `fields` and `favourite` of the entry table (`title` required) |
| `plainExports` | array of `{"uri": string, "name": string, "savedAt": integer}` | `[]` | Unprotected exports the app made and the user has not deleted yet |
| `backups` | array of `{"uri", "name", "savedAt"}`, as above | `[]` | Backups the app saved |
| `masterPasswordMatches` | array of strings | `[]` | Ids of entries whose password equals the master password |
| `securitySeenAt` | integer or null | null | When the app's Security tab was last opened |

| Entry key | Type | When missing | Meaning |
|---|---|---|---|
| `id` | string | required | A UUID |
| `title` | string | required | The account's name |
| `username`, `password`, `notes` | string | `""` | Line breaks are line feeds (U+000A) |
| `urls` | array of strings | `[]` | The websites, as stored |
| `fields` | array of `{"name": string, "value": string, "secret": boolean}` | `[]` | Extra fields; `secret` means hidden |
| `favourite` | boolean | false | |
| `changePending` | boolean | false | A password change was started and not yet confirmed: `password` is the new password, `pendingOldPassword` the one the website still has |
| `checkIgnored` | boolean | false | The security check leaves this account out |
| `createdAt`, `updatedAt` | integer | required | |
| `passwordChangedAt`, `deletedAt` | integer or null | null | `deletedAt` set: the account is in Recently deleted |
| `pendingOldPassword` | string or null | null | See `changePending` |
| `passwordHistory` | array of `{"password": string, "replacedAt": integer}`, newest first | `[]` | Previous passwords |

- **What a reader checks:** a known key with the wrong JSON type, or a required key that is missing, makes the body unreadable. Every key named for the objects inside the two tables (the draft and its `entry`, a field, a history item, an export or backup reference) is required, except the draft's `entry` keys that the entry table gives a default. Nothing else is checked: ids, lengths and text rules are rules for input, so no stored entry is ever refused for them.
- **Unknown keys** at the top level and in each entry are kept and written back after the known keys, as they were read (the same tokens and the same spelling of every number). Anywhere else they are ignored. So a later version that only adds data adds it at one of those two levels; a change that an older app must not ignore raises the header's `version`.
- **The writer's form.** The app writes every key of both tables for every object, in the order of the tables (the draft's `entry` in the order of the entry table), the kept unknown keys after them, no white space outside strings; in strings `"` and `\` escaped with a backslash, U+0008, U+0009, U+000A, U+000C and U+000D as `\b`, `\t`, `\n`, `\f`, `\r`, every other code point below U+0020 as `\u00xx` with lower-case hex digits, and everything else as itself in UTF-8 (the escapes of Python's `json.dumps(…, ensure_ascii=False, separators=(",", ":"))`); integers in plain decimal, and a kept unknown value in the compact form of what was read. Vector B (section 8) is in this form. A reader must not depend on it.

### 6. Reading a file

**Without a key.** The first rule that fails decides.

| # | Rule | If not |
|---|---|---|
| R1 | At least 8 bytes, and the first 8 are `PVAULT01`. If they are `PVAULT` and two ASCII digits other than `01`: NEWER when the file is at least 40 bytes and its last 32 bytes are the SHA-256 of all the bytes before them, otherwise DAMAGED | NOT OURS |
| R2 | The length is 74 to 16,777,216 bytes (a reader never reads more than that and one byte) | DAMAGED |
| R3 | `header_len` is 2 to 4,096, and 56 + `header_len` + 16 ≤ the length | DAMAGED |
| R4 | `trailerOk`: the SHA-256 of all bytes but the last 32 equals the last 32 | (remember it) |
| R5 | The header starts with `{"format":"passwordvault","version":N,` where N is `[1-9][0-9]{0,8}` | DAMAGED |
| R6 | N is 1. If N > 1 and `trailerOk`: NEWER. If N > 1 and not `trailerOk`: DAMAGED | NEWER or DAMAGED |
| R7 | The header matches section 3 in full, every bound included, and `body_len` fits the file's length | DAMAGED |

Until the body has opened, nothing in the header is authenticated.

**With a key.**

| # | Rule | If not |
|---|---|---|
| R8 | The body opens: AES-256-GCM-Open(VK, `body_nonce`, `body_ct`, AAD) verifies | This file does not open under this key |
| R9 | The plaintext is strict UTF-8 without a byte-order mark; after the trailing spaces are stripped it is one JSON object, read strictly as in section 5, at most 16 deep, and `schema` is an integer equal to 1 | `schema` above 1: NEWER. Otherwise DAMAGED |
| R10 | Every known key has the type of section 5 and the required keys are there | DAMAGED |

**What the user is told** about one backup file: the slot does not open and `trailerOk` → the password is wrong. The slot does not open and not `trailerOk` → "This file may be damaged, or the password is wrong.", and another try is allowed: damage confined to the trailer cannot be told from a wrong password. The slot opens and R8 to R10 fail → the file is damaged. NEWER → made by a newer version of the app; such a file is never opened or written over. A body that opens under VK is good even when the trailer is wrong.

### 7. Writing a file, and what a backup holds

- A writer writes version 1 only: the constants and the header form of section 3 and the body of section 5. A file longer than 16,777,216 bytes is never written.
- `created` is the clock's time at sealing, held within 0 to 9,999,999,999,999,999; `generator` and `writer_version` are the app's.
- **A backup** is sealed from the open vault in the phone's memory: the whole vault, Recently deleted included, under its own new VK, `vk_id`, salt and slot, with the vault's `vault_id` and its current generation number. The app opens the sealed backup once with the typed password before it saves it, and after saving reads the file back and compares its length and SHA-256 with what it sealed.
- **On the phone** the same container holds each generation of the vault, as `vault-` followed by ten digits and `.pvault`. Opened with the master password, such a file gives the same accounts; after a change of the master password, the older generations open with the old one.

### 8. Test vectors

Both files are in the project as `core/src/test/resources/vectors/v1-container.pvault` (vector A) and `v1-body.pvault` (vector B), with reference copies and the script that makes them in `docs/design/vectors/`. `pvault_decrypt.py --check` makes both again from the inputs below, compares their SHA-256 and opens them. The master password of both is `correct horse battery staple`; with the salt `00 01 … 0f` its KEK is the one given.

**Vector A: the container**, made from given plaintext bytes. Its body is not what the app writes (the app writes every key), but every reader must open it, since the missing keys have defaults.

```
KEK          853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e
VK           a0 a1 … bf          salt 00 01 … 0f          slot nonce 20 21 … 2b          body nonce 30 31 … 3b
plaintext    {"schema":1,"entries":[]} followed by 4,071 spaces (4,096 bytes)
header       {"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002","generation":1,"created":1791244800000,"generator":"PasswordVault 1.0","writer_version":1,"body_len":4112,"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"ICEiIyQlJicoKSor","wrapped_key":"TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr"}]}
header_len   499
wrapped_key  4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b
SHA-256(AAD) adfd3efa701b6e18938f770551cf86b990d3c651fe7016769fccf2f90fce15f0
body tag     b725d6eb1c28bcaf9cab305f5a362876
trailer      bb6953c8aec55e24dfb00e016dbd49268fbcab8ce5df20854416862ba0ce4e2a
file         4,667 bytes, SHA-256 3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d
```

With the body nonce added to the AAD the tag fails: a reader that opens the body that way is wrong.

**Vector B: the body as the app writes it.** The same key, slot and vault as A; `generation` 7, `created` 1791331200000, body nonce `40 41 … 4b`. The body below is one line, 1,448 bytes of UTF-8 (SHA-256 `610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7`), followed by 2,648 spaces. It holds every key of section 5, Uzbek in both scripts with U+02BB, a character outside the Basic Multilingual Plane, the escapes of section 5, an unknown key at both levels (one with a number spelled `1.50`) and two previous passwords.

```
{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}
```

```
header_len   499 (the header of A with "generation":7 and "created":1791331200000)
body tag     5e76cebd4bd31f0794d5da6ef151ef7b
trailer      a12f31da424719de8cb33cbbad1accaae5ec6e4553dd2b2db1bc7748643afd3d
file         4,667 bytes, SHA-256 1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7
```

**The master password's bytes**, and Argon2id with the salt `00 01 … 0f` and the constants of section 4:

| Typed | Bytes (hex) | KEK |
|---|---|---|
| a space, `correct`, two spaces, `horse`, U+00A0, `battery`, a space, `staple`, a space | `636f727265637420686f727365206261747465727920737461706c65` (`correct horse battery staple`) | `853b272a…bab49e`, as above |
| `o` U+0374 `g` U+1FEF `li` U+1FFD `m` | `6f2767276c69276d` | |
| U+0020 U+00A0 U+0009 U+3000 U+0020 | (none: zero bytes) | |
| `oʻgʻlim-Toʻra` with U+02BB; or with U+2018; or with U+0027; or with U+2019, U+02BC and U+0060; or with U+00B4, U+FF07 and U+2032; or with U+0374, U+1FEF and U+1FFD | `6f2767276c696d2d546f277261` | `35914871c9a019bf3fa9ae69825be6a300a86905bcf69ca6bb84de0a3064b26b` |
| `Köz-Ўzbek` typed decomposed (`o` U+0308, `У` U+0306) | `4bc3b67a2dd08e7a62656b` | `9f05463cb3a665fb4e22120cdad867630f76f12dc5ffdf4efaaa589f5baad5fa` |

### 9. `pvault_decrypt.py` for programmers

```
pvault_decrypt.py BACKUP OUTPUT [--yes] [--password-stdin]
pvault_decrypt.py --check
```

- **Needs** Python 3.7 or newer and `cryptography` 44.0.0 or newer: AES-GCM, and Argon2id, which came in 44.0.0 and needs OpenSSL 3.2 or newer (the package's own wheels include a newer OpenSSL; a build against an older system OpenSSL refuses Argon2id, and the script says so). The newest release on 2026-10-19, 50.0.2, needs Python 3.9 or newer. Tested with Python 3.12 on Linux and with cryptography 44.0.0 and 50.0.2; 43.0.3 has no Argon2id, and the script then says what to install.
- **Reads** the file by the rules of section 6, at most 16,777,217 bytes. It opens any version 1 vault file: a backup, or a generation copied from a phone. It asks for the master password up to five times.
- **Writes** the accounts outside Recently deleted, as the app's own export writes them: `OUTPUT` ending in `.csv` gives the app's CSV (Google's columns `name,url,username,password,note`, one row per website, the extra fields, a name that differs from the website and a pending change's old password in a marked block at the end of the note), cut into `-part1`, `-part2`, … files when it would pass Google's limits of 3,000 rows or 1,000 KB in one file; `.json` gives the app's JSON (Bitwarden's unencrypted layout, with a `passwordVault` object at the top and in each item for what Bitwarden has no key for). Given the same vault, the CSV is byte for byte the app's and the JSON differs only in `exportedAt` and `generator`. The project's design documents describe both formats (`docs/design/formats.md` §4 and §5).
- **Never writes over a file**: it checks before asking for the password and creates each file exclusively (readable by its owner only, where the system allows), reads it back and compares, and removes what it wrote when anything fails. It writes nothing before the warning of the first section has been shown, and waits for `yes` unless given `--yes`.
- `--password-stdin` reads the master password from the first line of standard input, and the answer to the question from the next line; `--check` tests the computer and the script (the vectors of section 8, the extra-field block of the CSV, the vectors' CSV and JSON against what the app writes) and changes nothing.
- **Exit status:** 0 done; 1 the backup did not open, or a file could not be written; 2 wrong use or nothing to work with (no such file, a file that already exists, the package missing); 3 stopped.
