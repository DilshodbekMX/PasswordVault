# Offline and local open-source password managers on Android (research, 2026-10-06)

**How I know each fact.** **[S]** read today in source at HEAD (path given) · **[P]** fetched today from a named primary source · **[X]** my own experiment today · **[I]** my inference · **[M]** from memory, unverified. Stars, licences and dates were fetched today from img.shields.io, GitHub release feeds and repo pages, Maven Central and the F-Droid index (snapshot 2026-10-05). Nothing was run on a phone or emulator. "KDF" is the deliberately slow function that turns a password into a key. Length: about 2,550 words of prose plus about 1,300 inside the four tables.

## 1. Findings that change what we build or what we tell the user

1. **The user could simply install KeePassDX, and we should say so.** It is active (4.5.5, 2026-09-25), free and has no INTERNET permission. The cost for a non-expert: a "database file" to place and back up themselves, a file link that can break, and password copying that is off until the user opts in (section 8). Our app is justified by simplicity, not by better security.
2. **KeePassDX still creates new databases with AES-KDF in KDBX 3.1, not Argon2.** `KdfFactory.defaultKdf = aesKdf`; `DatabaseKDBX.kdfEngine = KdfFactory.aesKdf`; a new database gets `kdbxVersion = FILE_VERSION_31` (`database/src/main/java/com/kunzisoft/keepass/database/crypto/kdf/KdfFactory.kt`, `…/database/element/database/DatabaseKDBX.kt:87,186`). [S] If we recommend it, the user should switch to Argon2id in the database settings.
3. **A timed clipboard clear that first reads the clipboard stops working once the app is in the background.** AOSP `ClipboardService.clipboardAccessAllowed`: reading needs window focus (or being the default keyboard); "Writing is allowed without focus". [P] KeePassDX compares before clearing, so its 20 s clear is skipped after the user switches to the browser, unless its own keyboard is the default [I]; users report the clipboard is not cleared (#2683, #2488) [P]. PocketVault clears when the app leaves the foreground, which removes the password before it can be pasted into another app [I]. We need an unconditional, background-safe clear.
4. **In most small vaults the master password or biometric is only a screen gate.** A keyword scan of 21 small local vaults (a prior agent's script; indicative only) found 13 of 18 `BiometricPrompt` users without `CryptoObject` and 5 with no password KDF. Reading KeyPass (785 stars) and PassVault (120 stars) confirmed both patterns. [S]
5. **Never truncate the live vault before the new ciphertext exists.** KeePassDX produced 0-byte databases in 2023–24 for this reason (#1680). [P]
6. **KDBX works from Kotlin, but I recommend our own small format plus a KDBX export** (section 4). A file written by kotpass 0.13.0 opened in KeePassXC 2.7.12 with all fields intact. [X]
7. **Copying code from KeePassDX, Keepass2Android, 1Key, KeyGo or Monica makes our app GPL-3.0** (section 8). kotpass, KeyPass, PassVault (MIT), PocketVault and KeePassJava2 (Apache-2.0) can be reused with their notices.

## 2. KeePassDX in depth, Keepass2Android briefly

| | KeePassDX | Keepass2Android |
|---|---|---|
| Repo, licence | Kunzisoft/KeePassDX, GPL-3.0 (headers: "or later") | PhilippC/keepass2android, GPL-3.0 |
| Latest release | 4.5.5, 2026-09-25 | v1.15-r4, 2026-09-14 |
| Cadence | 20 stable tags from 4.2.0 (2025-10-15) to 4.5.5; one main maintainer | 7 stable tags, 2025-11 to 2026-09 |
| Stars / open issues | 7.4k / 537 | 6.3k / 1.2k |
| minSdk / targetSdk | 19 / 36 | 21 / 36 |
| Stack, UI | Kotlin (485 files, 83,800 lines) + JNI C; Android Views (90 XML layouts), no Compose | C# on .NET 9 for Android, Views via Xamarin bindings |
| Distribution | F-Droid, Google Play (4.2 stars from 5.04K reviews, 500K+ installs), IzzyOnDroid | Google Play (two apps), GitHub APKs; not in F-Droid or IzzyOnDroid |
| INTERNET | not requested | main app yes (also Wi-Fi state, fine location, accounts); "Offline" build (`keepass2android_nonet`) no |

All [P] or [S] (`app/build.gradle.kts`, manifests, `keepass2android-app.csproj`).

**KeePassDX mechanisms** ([S] unless tagged; `app/…` means `app/src/main/java/com/kunzisoft/keepass/`, `database/…` means `database/src/main/java/com/kunzisoft/keepass/`):

- **Device unlock** (`app/…/biometric/DeviceUnlockManager.kt`, `DeviceUnlockFragment.kt`, `app/…/viewmodels/DeviceUnlockViewModel.kt`; off by default).
  - What is encrypted: **the master password itself** (key file and hardware key are `TODO`). Ciphertext and IV go to Room table `cipher_database`, which `app/src/main/res/xml/backup_rules.xml` excludes from backup (the app is otherwise `allowBackup="true"`).
  - Key: alias `com.kunzisoft.keepass.biometric.key`, AES/CBC/PKCS7, no `setKeySize` (platform default is 128 bits: AOSP `AndroidKeyStoreKeyGeneratorSpi` [P]), `setUserAuthenticationRequired(true)`, StrongBox when available. Device-credential mode: `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL)` on API 30+, a 5 s validity window on API 23–29.
  - Binding: `authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))`.
  - Invalidation: on `KeyPermanentlyInvalidatedException` it deletes the key and every stored credential (`CipherDatabaseAction.deleteAll()`); the user types the password again.
  - The KDF still runs after each biometric unlock. Issue #2486 (CBC instead of GCM) is open. [P]
- **Auto-lock** (`app/…/timeout/TimeoutHelper.kt`, `app/…/utils/BroadcastAction.kt`): 5 min default; an `AlarmManager.setExact` broadcast plus a saved wall-clock timestamp checked on resume; locks 1.5 s after screen-off.
- **Clipboard** (`app/…/timeout/ClipboardHelper.kt`): sets `android.content.extra.IS_SENSITIVE`; after 20 s runs `if (getClipboardText() == valueString) cleanClipboard()`. Copying passwords is off by default (`allow_copy_password_default=false`); the wiki says "It is not recommended to use this method to copy passwords". [P] Current AOSP also clears the clipboard itself after 3,600,000 ms (one hour). [P]
- **Screenshots**: `FLAG_SECURE` on every activity by default (`app/…/activities/stylish/StylishActivity.kt`, `app/…/utils/AppUtil.kt`).
- **Saving** (`database/…/database/element/Database.kt:786`, `app/…/database/action/SaveDatabaseRunnable.kt`, `database/…/utils/UriHelper.kt`): the database is encrypted into `cacheDir/db_*.tmp`, then copied into the system file-picker (SAF) stream opened with mode `"wt"`. This guards against a failed encryption, not a failed copy; it is not an atomic replace. [I]
- **Data-loss history** [P]: 0-byte files with Dropbox (#1594), DAVx5/Nextcloud (#1620) and a cancelled YubiKey save (#1680); maintainer: "opening the outputstream caused a 0byte"; fixed in 4.0.7 (2024-06-17). Entries silently lost when merging after a group move (#2223, 4.2.0; fixed by 4.2.2, 2025-10-28).

## 3. KDBX 4 / 4.1 in brief

Sources: keepass.info "KDBX File Format Specification" [P]; palant.info "Documenting KeePass KDBX4 file format" (2023) [P].

- **Layout**: plaintext outer header, its SHA-256, its HMAC-SHA-256, then the payload in HMAC-protected 1 MiB blocks (encrypt-then-MAC).
- **Not encrypted**: cipher ID, compression flag, master seed, IV, KDF ID and parameters, public custom data. **Encrypted**: attachments and the whole XML (titles, usernames, URLs, notes, passwords, history).
- **Ciphers**: AES-256-CBC or ChaCha20 (without Poly1305). Twofish is an extension (KeePassXC, KeePassDX, kotpass).
- **KDFs**: AES-KDF, Argon2d, Argon2id. Version 4.1 adds group tags, custom-icon names and more timestamps.
- **Defaults**: KeePassDX uses AES-KDF (500,000 rounds, then a 1 s benchmark); if Argon2 is chosen: 16 MiB, 3 iterations, parallelism 4. [S] KeePassXC's GUI uses Argon2d + AES-256, 64 MiB, parallelism up to 4, rounds benchmarked to 1 s (`src/crypto/kdf/Argon2Kdf.h`, `src/gui/dbsettings/DatabaseSettingsWidgetEncryption.cpp`). [S] `keepassxc-cli db-create` gave AES-KDF, 1,000,000 rounds, KDBX 3.1. [X]
- **Criticisms**:
  - Under-documented, and implementations differ (Palant).
  - Authentication is "bolted on" rather than an AEAD (Palant).
  - Seeds are regenerated on every save, so every save re-runs the KDF, "quite wasteful" (Palant; confirmed in `database/…/database/file/output/DatabaseOutputKDBX.kt:306` and kotpass `regenerateVectors` [S]).
  - The "protected" inner stream cipher "has no effect on the cryptographic security" (spec).
  - KDF parameters are read before anything is authenticated, so a hostile file can demand huge memory. [I]
- **CVE-2023-32784** (NVD [P]): in KeePass 2.x before 2.54 the master password, minus its first character, could be recovered from a process dump, swap or hibernation file, even after locking. Lesson: strings created while a password is typed can outlive the session. Keep secrets short-lived, clear them on lock, and state that a memory dump of a running app is out of scope. KeePassDX's equivalent (#2485, master credential held as strings) is still open. [P]

## 4. Could KDBX be our native storage?

| Library | Licence | Latest (Maven Central) | Maintenance | Formats | Argon2 | Known problems |
|---|---|---|---|---|---|---|
| keemobile/kotpass | MIT | 0.13.0, 2025-09-28 | 36 stars, one maintainer, no commits since; maintainer: "the library is feature complete" (#42, 2026-09-03); used by Monica and KeePassVault | KDBX 3.1, 4.0, 4.1; Argon2d and Argon2id; AES, ChaCha20, Twofish | Pure Kotlin port of Bouncy Castle, single-threaded, on the Java heap; replaceable via `KdfProvider` (0.13.0) | #10 refused a database with an unnamed field (fixed 2024-01); #8 crash on a dangling attachment; #38 slower than KeePassDX; `Ver3x.create` defaults to 6,000 AES rounds |
| jorabin/KeePassJava2 | Apache-2.0 | 3.1.0, 2026-10-05 | 287 stars; four releases in four days after a 19-month gap | KDBX 3.1, 4, 4.1; Argon2d only; AES, ChaCha20 | Bouncy Castle, pure Java | Attachments duplicated or lost (#97, #98); KeePassXC could not open new files (#78); needs Java 17, Jackson and Woodstox; 3.0.0 removed the Android-oriented back-end |
| cternes/openkeepass | Apache-2.0 | 0.8.2, 2018-06-07 | last commit 2020-02 | not checked | not checked | abandoned |
| KeePassDX `database` and `crypto` modules | GPL-3.0 | not published | active | full | native C, multi-threaded | GPL |

**Experiments [X]** (desktop i9-14900K, JBR 25):

- Argon2d, 64 MiB, t=3, p=4: kotpass 86 ms, Bouncy Castle 96 ms, KeePassDX's C code 162 ms on one thread and 49 ms on four. Outputs were identical. Speed on a phone was not measured.
- kotpass wrote KDBX 4.1 files (its default Argon2d 32 MiB, t=8, p=2; and Argon2id 64 MiB, t=3, p=2). KeePassXC 2.7.12 CLI opened both, with non-ASCII and XML-special characters intact, and rejected a wrong password. kotpass read a KeePassXC-created file.

**Judgement [I]: our own simple format as the native store, plus "Export to KeePass (.kdbx)" through kotpass.**

- Our own format can wrap one random vault key twice: by Argon2id(master password) and by a Keystore biometric key. KDBX has a single path from the password, which is why KeePassDX stores the password itself and re-runs the KDF on every unlock.
- KDBX re-derives the key and rewrites the whole file on every save.
- Unlocking would not depend on a one-maintainer library with no commits for a year; only export would.
- Writing KDBX is the safe direction, because we control what goes in. The interop bugs above came from editing files made by other apps.
- The cost is format risk of our own. Limit it with a versioned header that stores the KDF parameters, test vectors, and the always-available export.

If live KeePassXC/KeePassDX compatibility is a hard requirement, kotpass with a native Argon2 `KdfProvider` is feasible.

## 5. Map of open-source Android password managers

Status: *active* = stable release within about six months; *slow* = last stable release over a year old, commits within two years; *abandoned* = no commits for over two years. Stack and storage come from language statistics, READMEs and store descriptions unless section 2 or 6 covers the app.

| App (repo) | Licence | Stack | Storage | Latest release | Stars | Status |
|---|---|---|---|---|---|---|
| KeePassDX (Kunzisoft/KeePassDX) | GPL-3.0 | Kotlin, Views | local file (KDBX) | 4.5.5, 2026-09-25 | 7.4k | active |
| Keepass2Android (PhilippC/keepass2android) | GPL-3.0 | C#, .NET | local file, optional cloud | v1.15-r4, 2026-09-14 | 6.3k | active |
| KeePassDroid (bpellin/keepassdroid) | GPL-2.0+ | Java | local file | v2.6.9.2, 2025-04-20 | 1.4k | slow |
| AuthPass (authpass/authpass) | GPL-3.0 | Flutter | local or cloud file | v1.9.11, 2024-02-04 | 2.8k | slow |
| OneKeePass (OneKeePass/mobile) | GPL-3.0 | ClojureScript | local file | 0.23.0 on IzzyOnDroid, 2026-09-02 | 238 | active |
| KeePassVault (aivanovski/keepassvault) | Apache-2.0 | Kotlin, kotpass | local file | 2.1.0, 2026-08-17 | 118 | active |
| Vault (arsvechkarev/Vault) | GPL-3.0 | Kotlin, kotpass | local file | 1.0.4, 2024-03-03 | 50 | abandoned |
| PasswdSafe (jefftharris/passwdsafe) | Artistic-2.0 | Java, Views | local file (.psafe3) | 6.27.2, 2026-06-02 | 24 | active |
| Android Password Store (android-password-store/Android-Password-Store) | GPL-3.0 | Kotlin | GPG files + git | v1.13.5, 2021-07-28 | 2.7k | archived 2024-10-15 |
| Password Store fork (agrahn/Android-Password-Store) | GPL-3.0 | Kotlin | GPG files + git | v2.0.3, 2026-10-02 | 461 | active |
| Monica (Monica-Pass/Monica) | GPL-3.0 | Kotlin, Compose | local database, optional WebDAV | V1.0.316, 2026-10-03 | 1.1k | active |
| KeyPass (yogeshpaliyal/KeyPass) | MIT | Kotlin, Compose | local database | v1.4.42, 2025-09-03 | 785 | slow |
| NewPass (6eero/NewPass) | GPL-3.0 | Java | local database | v1.2.0, 2024-06-17 | 263 | abandoned |
| Passy (GlitterWare/Passy) | GPL-3.0 | Flutter | local, LAN sync | v1.10.2, 2026-08-05 | 191 | active |
| PassVault (jksalcedo/PassVault) | MIT | Kotlin, Views | local database | v2.2.2, 2026-09-21 | 120 | active |
| ANOTHERpass (jenspfahl/ANOTHERpass) | GPL-3.0 in repo (F-Droid lists MIT) | Kotlin, Views | local database | v2.2.6, 2026-06-26 | 92 | active |
| KeyGo (OffRange/KeyGo) | GPL-3.0 | Kotlin, Compose, Rust | local database | 2.0.5, 2026-09-29 | 56 | active |
| 1Key (roufsyed/1Key) | GPL-3.0 | Kotlin, Compose | local database | v1.1.1, 2026-07-15 | 26 | active |
| PocketVault (greyfreedom/PocketVault) | Apache-2.0 | Kotlin, Compose | local encrypted files | v2.6.0, 2026-08-11 | 16 | active |
| Bitwarden (bitwarden/android) | GPL-3.0 | Kotlin, Compose | cloud account or self-hosted | v2026.9.1, 2026-10-05 | 9.4k | active |
| Proton Pass (protonpass/android-pass) | GPL-3.0 | Kotlin | cloud account | 1.41.2, 2026-09-28 | 789 | active |
| Keyguard (AChep/keyguard-app) | "All Rights Reserved" | Kotlin Multiplatform | Bitwarden client | r20260916, 2026-09-16 | 3.4k | active, not open source |
| 2FAS Pass (twofas/2fas-pass-android) | BUSL-1.1 | Kotlin, Compose | local, optional cloud sync | 1.11.1, 2026-09-15 | 284 | active, source-available |
| AliasVault (aliasvault/aliasvault) | AGPL-3.0 | TypeScript, .NET | self-hosted server | 0.30.7, 2026-09-16 | 3.2k | active |
| Padloc (padloc/padloc) | AGPL-3.0 | JavaScript | cloud or self-hosted | v4.3.0, 2023-04-28 | 2.9k | abandoned |
| LibrePass (LibrePass/LibrePass-Android) | GPL-3.0 | Kotlin, Compose | self-hosted server | v1.3.1, 2024-10-19 | 112 | archived 2026-08-24 |
| Buttercup (buttercup/buttercup-mobile) | GPL-3.0 | TypeScript | vault file, local or cloud | v2.6.3, 2024-05-04 | 405 | archived 2025-06-26 |
| Firefox Lockwise (mozilla-lockwise/lockwise-android) | MPL-2.0 | Kotlin | cloud account | v4.0.3, 2020-12-10 | 615 | archived 2021-12-14 |
| PearPass (tetherto/pearpass-app-mobile) | Apache-2.0 | JavaScript | peer-to-peer sync | v2.1.0, 2026-05-20 | 275 | paused (README) |
| LessPass (lesspass/lesspass) | GPL-3.0 | TypeScript | stateless | mobile-v9.8.7, 2025-03-30 | 6.1k | slow |

Also found, not examined: Passbolt (59 stars, active), Nextcloud Passwords (83), Passman (103), PassPony (8), AIPOS (20), Master Password (1.1k, last tag 2021), Password Generator PFA (52), Secret Space Encryptor, Password Master, Spass, VaultX, CodeSpire "Passwords", MA Passwords, rust-keylock, abdal-lockbox, kupass, NowiPass, Passtable, UnCrack, KeepassA, Keyspace (archived 2025-01-25), Passky (2022), Authorizer (2023), TinyKeePass (2020), NC Passwords, UPM, OI Safe, AnyPass. Bitwarden and Keepass2Android are not in the F-Droid main repo. [P]

## 6. The three closest small apps

Source read at HEAD today [S]. Paths: KeyPass `common/…` = `common/src/main/java/com/yogeshpaliyal/common/`, `app/…` = `app/src/main/java/com/yogeshpaliyal/keypass/`; PassVault under `app/src/main/java/com/jksalcedo/passvault/`; PocketVault under `app/src/main/java/com/turisla/hellopocket/`.

| | KeyPass 1.4.42 (MIT) | PassVault 2.2.2 (MIT) | PocketVault 2.6.0 (Apache-2.0) |
|---|---|---|---|
| Key source | SQLCipher passphrase is `UUID.randomUUID().toString()`, kept in `user-settings.json` under a Keystore key (`common/…/di/module/AppModule.kt:46-67`). No KDF. | One Keystore key, `passvault_key_v1`; every call site uses `ensureKeyExists()` with `requireUserAuth = false` (`crypto/Encryption.kt:50-82`). No KDF for the vault. | Random Tink keyset wrapped by PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt (`security/TinkCryptoManager.kt`) |
| Master password | Stored as a string in the same file; login is `if (savedPassword == password)` (`app/…/ui/auth/components/ButtonBar.kt:99`) | PIN (minimum 4) stored encrypted, then decrypted and compared (`ui/auth/UnlockActivity.kt:105-121`) | Not stored; minimum 6 characters |
| Cipher | SQLCipher database; settings file AES/CBC/PKCS7, no `setKeySize` (`common/…/utils/CryptoManager.kt`) | AES-GCM on the password and custom-fields columns only; no `setKeySize` (128-bit default) although the README says AES-256 | AES-256-GCM-HKDF streaming (Tink `AES256_GCM_HKDF_1MB`) |
| Unencrypted | nothing in the database | title, username, e-mail, URL, notes, category (`data/PasswordEntry.kt`) | `vault_v2.json`: hint, salt, KDF parameters, wrapped keyset |
| Biometric | Success callback only; allows `BIOMETRIC_WEAK` (`app/…/ui/auth/components/BiometricPrompt.kt:51-73`) | Success callback only; allows `BIOMETRIC_WEAK` (`ui/auth/BiometricAuthenticator.kt`) | Bound: AES-256-GCM Keystore key, user authentication required, invalidated on enrolment, used through `CryptoObject` (`security/BiometricCipherManager.kt`, `MainActivity.kt:189`); it wraps the vault keyset |
| Clipboard | Plain `ClipData`: no sensitive flag, never cleared | Plain `ClipData`: no sensitive flag, never cleared (`utils/Utility.kt:30`) | Sensitive flag, 60 s clear, and clear when the app goes to background (`utils/ClipboardManagerHelper.kt`, `security/VaultSessionController.kt:62`) |
| Screenshots | `FLAG_SECURE` in release builds | `FLAG_SECURE`, default on | `FLAG_SECURE` |
| allowBackup | false | false | false |
| Export | AES/CBC with a fixed all-zero IV, a 16-character string used as raw key bytes, no MAC (`common/…/dbhelper/EncryptionHelper.kt:30-45`) | Argon2id (t=5, 64 MiB) + AES-GCM with hardcoded parameters and no header; plain JSON/CSV behind a warning | Encrypted backup that needs the original master password |

**Concrete bugs.**

- **KeyPass**: `private val iV = byteArrayOf(0, 0, … 0)` with CBC makes backups under one key deterministic and unauthenticated. The vault itself rests on a Keystore key with `setUserAuthenticationRequired(false)`; the password protects nothing cryptographically.
- **PassVault**: `UnlockActivity` is exported and, after unlock, calls `startActivity(redirectIntent)` on a parcelable taken from the launching intent (`ui/auth/UnlockActivity.kt:148-171`, marked `@SuppressLint("UnsafeIntentLaunch")`). Another app can pass a nested intent that PassVault launches with its own identity, reaching non-exported activities and its `grantUriPermissions` FileProvider. [I]; not exploited; Android 16+ may block it [M].
- **PocketVault**: version 2.5.0 "reported some valid early V2 vaults as damaged" (CHANGELOG; fixed in 2.5.1 the same day). The clipboard is cleared on process `onStop`, so pasting into another app fails. [I]

**Recurring mistakes: a checklist for our code.** Counts are from the keyword scan of 21 apps (indicative).

1. Password or PIN compared instead of deriving the key (KeyPass, PassVault; 5 of 21 have no password KDF).
2. Biometric success callback without `CryptoObject` (13 of 18).
3. Keystore key without `setKeySize(256)`, without user authentication, or in CBC.
4. A key silently created on a decrypt path (PassVault's `ensureKeyExists()`), which makes old data unreadable.
5. Only the password column encrypted.
6. Fixed IV, no MAC, a raw string as key, hardcoded KDF parameters, no format version.
7. Clipboard: no sensitive flag (14 of 20), no clearing (13 of 20), or clearing too early.
8. `allowBackup="true"` (8 of 21); no `FLAG_SECURE` (6 of 21).
9. An exported activity that forwards intents.
10. A format tightened without a migration (PocketVault 2.5.0); a non-atomic save (KeePassDX before 4.0.7).

## 7. Abandoned projects and stateless generators

- **Buttercup**: archived 2025-06-26. The maintainer wrote: "The project is enormous in scope" for "a single dev", and platform rules need "near constant attention to ensure we don't get penalised or booted off a platform (looking at you, Google)". Its 15–25k users were told to migrate. [P, maintainer's gist]
- **Android Password Store**: archived 2024-10-15. Maintainer: "I no longer have time and motivation to dedicate to this project, and in the absence of significant external contributions there is no-one else I can offer the project's stewardship to." A fork (agrahn) continues. [P, discussion #3260]
- **Others** [P]: Lockwise archived 2021; LibrePass archived 2026-08-24; Padloc silent since 2023-12; NewPass since 2024-06; PearPass "paused from active development".
- **Pattern** [I]: one maintainer, and scope growing into autofill, passkeys, sync and store compliance. A first-class export protects users when a project stops.
- **Stateless generators** (LessPass: no vault; each password is recomputed from master password, site and login). Not for our user [I]:
  - they cannot hold the passwords the user already has, nor usernames or notes;
  - per-site rules and the counter after a forced password change are state the user must still remember;
  - a leaked master password exposes every site, with no file needed;
  - the flow is "regenerate", not "open and copy".

## 8. Fit: KeePassDX versus a home-built app

**Gain with KeePassDX**: a maintained app with autofill, its own keyboard, one-time codes and passkeys; a file that also opens on a desktop in KeePassXC; no network permission; crypto-bound biometric unlock.

**Lose, for a non-expert**: the file model (choose a location, back it up, no built-in sync); a default KDF that is not memory-hard; password copy behind an opt-in; no CSV import (#505, open since 2020); a dense settings surface; one main maintainer.

**What users say, 2025–2026** [P: Google Play listing, GitHub issues]:

- Praise: "completely offline and very secure" (2025-07); "Works well. I like the biometric unlock" (2026-08).
- File link breaks: "I have to manually select it in my file manager again" (2026-08); #2573 "periodically loses access to database file" (open since 2026-06).
- Fear of loss: after an update hid groups, one reviewer feared "that all my data was erased somehow" (2025-07).
- Friction: verification added to copying "is annoying and stops me from a quick password copying" (#2321, 2025-12; 4.3.1 added a mode switch); "I can still paste said copied credentials even after the 20s clipboard timeout" (#2488, 2026-04).

**Licences** (an engineering reading, not legal advice): giving anyone an APK that contains GPL-3.0 code obliges us to offer the whole app's source under GPL-3.0. Reading GPL code for ideas and writing our own is fine. Keyguard and 2FAS Pass permit no reuse.

## Lessons for our app

- **Copy PocketVault's key hierarchy**: a random vault key, wrapped by the password KDF and separately by an AES-256-GCM Keystore key used through `CryptoObject`. This avoids storing the master password (KeePassDX) and the screen-gate design (KeyPass, PassVault).
- **Use Argon2id through a native library, with parameters in a versioned header.** PocketVault and PassVault both had to carry legacy KDF paths.
- **On biometric invalidation, delete only the biometric wrap** and fall back to the password. Never create a key while decrypting.
- **Save atomically in app-private storage**: temp file, fsync, rename, keep previous copies. Export to the file picker only from a finished file.
- **Clipboard**: sensitive flag; an unconditional timed `clearPrimaryClip()` that survives backgrounding; no compare-read; no clear-on-background. Tell the user that keyboards keep their own history.
- **Time the lock with `elapsedRealtime` and re-check on return to the foreground** (PocketVault), not with wall-clock time (KeePassDX, PassVault).
- **Encrypt every field**; `FLAG_SECURE` on; `allowBackup="false"`; no INTERNET; no exported activity that forwards intents.
- **Ship encrypted export and KDBX export in version 1.** It is the user's exit if we stop maintaining the app.
- **Keep scope small.** Scope and store compliance ended Buttercup; lost maintainer time ended Android Password Store.
- **Do not paste GPL code.**

## Not settled

- Argon2 timing and memory on real ARM phones (pure Kotlin versus native), especially low-RAM devices at minSdk 24.
- Whether a kotpass-written file opens in KeePassDX on a device and survives being edited there. Only the KeePassXC 2.7.12 CLI was tested.
- Whether a timed `clearPrimaryClip()` runs reliably from a backgrounded or frozen process on Android 10–17 and vendor builds. The KeePassDX and PocketVault clipboard conclusions are code readings.
- kotpass's future (no commits since 2025-09-28), and whether KeePassJava2 3.x runs on Android.
- Whether live KDBX compatibility is a requirement; it would reverse the section 4 recommendation.
- The PassVault intent finding is unexploited, and the Android 16 mitigation is from memory.
- Whether KeePassDX has had a professional audit. I found only #2480, which the maintainer calls AI-generated.
- Storage models of apps outside sections 2 and 6 come from their descriptions, not their code.
- Whether our app will be distributed beyond this user, which decides the licence obligations.
