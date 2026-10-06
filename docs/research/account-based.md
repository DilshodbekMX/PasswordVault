# Account-based password managers and Google Password Manager: findings for PasswordVault

Researched 2026-10-06; saved at `/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/research/account-based.md`. Source trees read today are under
`R=/tmp/claude-1000/-home-asus-AndroidStudioProjects-PasswordVault/096e95f8-3ae0-41bf-b235-08771bb88852/scratchpad/account-based/src`:
`BW=$R/bitwarden_android` (HEAD, app 2026.9.0), `BWA=$BW/app/src/main/kotlin/com/x8bit/bitwarden`, `SDK=$R/bitwarden_sdk-internal/crates`, `PP=$R/protonpass_android-pass/pass`.

How I know each fact: **[S]** read in source today · **[D]** vendor page or published audit report fetched today · **[2]** secondary source fetched today · **[M]** from memory · **[I]** my inference.

## Findings that change what we build or what we tell the user

1. **None of these is a phone-only vault.** Bitwarden and Proton Pass send a new item to the server before saving it locally, so adding or editing needs an account and a connection [S `$BWA/data/vault/manager/CipherManagerImpl.kt:87-106`; `$PP/data/impl/src/main/kotlin/proton/android/pass/data/impl/repositories/ItemRepositoryImpl.kt:149-178`]. The one exception is Keyguard with a local KeePass file: beta, not open source, and no adding or editing in its Play build without paying [D keyguard.dev/docs/keepass, /docs/premium].
2. **Tell the user plainly: if an account is acceptable, Bitwarden Free is safer than a home-built app** [I]. It has nine third-party security reports for 2025 alone [D bitwarden.com/help/is-bitwarden-audited], unlimited items and devices at no cost [D bitwarden.com/pricing], and the vault survives a lost phone. Our app offers three things instead: no account, full offline use, and a simpler open, find, copy flow. It has no audit, and without an exported backup a dead phone loses everything.
3. **Biometric unlock must decrypt the vault key with a Keystore key through `BiometricPrompt.CryptoObject`.** Cure53 rated Bitwarden's earlier callback-only unlock a Medium bypass (BWN-11-005, March 2024) [D "2024 Mobile Apps and SDK Report", linked from bitwarden.com/help/is-bitwarden-audited]. Today's code does it properly [S]. Proton Pass still uses a callback-only prompt, as a screen gate, not as data protection [S].
4. **Do not copy wipe-on-failure.** Bitwarden deletes the local vault after 5 failed unlocks and on biometric lockout [S `$BWA/data/vault/manager/VaultLockManagerImpl.kt:87,340-352`; `$BWA/ui/auth/feature/vaultunlock/VaultUnlockViewModel.kt:233-235`; `$BWA/data/auth/manager/UserLogoutManagerImpl.kt:131-141`]. Proton Pass signs every user out after 3 wrong PINs [S `$PP/features/auth/src/main/kotlin/proton/android/pass/features/auth/PinConstants.kt`]. That is safe only because a server holds a copy. In a local-only vault it is permanent data loss, so use growing delays [I].
5. **The master password must stay the root of recovery; Keystore-wrapped copies are disposable.** Bitwarden treats a Keystore-protected value it cannot decrypt as missing, and deletes its legacy encrypted-preferences file if that fails to open [S `$BW/data/src/main/kotlin/com/bitwarden/data/datasource/disk/KeystoreEncryptedSharedPreferences.kt:93-103`, `.../di/PreferenceModule.kt:55-84`]; the server copy makes that harmless. Proton's core library returns the plaintext unchanged when its Keystore key cannot be initialised [S github.com/ProtonMail/protoncore_android HEAD, `AndroidKeyStoreCrypto.kt:204-206`; Pass pins core 36.8.0, which I did not check]. We can afford neither.
6. **Encrypt each record as one authenticated message bound to its record id.** Bitwarden encrypts every field separately with no associated data, which let a malicious server swap fields (ETH Zurich Issue 6) and leaves metadata readable (Issue 9) [D "2025 Cryptography Report", same page]. Bitwarden is preparing sealed per-item blobs itself [S `$SDK/bitwarden-vault/src/cipher/README.md`].
7. **Chrome's export header is exactly `name,url,username,password,note`** [S Chromium HEAD]. I could not confirm that the Android export, which runs inside closed-source Google Play services, writes the same header, so the importer must match columns by name.
8. **Licences.** Re-implementing ideas is free. Copying code from Bitwarden (GPL-3.0), Proton Pass (GPL-3.0-or-later), or Vaultwarden, Passbolt, Padloc and AliasVault (AGPL-3.0) obliges us to license our whole app the same way as soon as we hand the APK to anyone [I, not legal advice]. Psono's app is Apache-2.0 and can be copied with attribution. Keyguard and 2FAS Pass are not open source: copy nothing.

## 1. Facts

| App (repo) | Licence; open source? | Latest Android release | Stars | Stack | Account needed? | Offline | Self-host | Free tier | Independent audits |
|---|---|---|---|---|---|---|---|---|---|
| Bitwarden (bitwarden/android) | GPL-3.0; SDK is GPL-3.0 or Bitwarden SDK Licence. Yes | 2026.9.1, 2026-10-05 | 9.4k | Kotlin 2.4.20, Compose, Hilt, Room, Rust SDK; minSdk 29 | Yes | Read-only; the mobile offline session expires after 90 days [D bitwarden.com/help/security-faqs] | Yes: official server, or Vaultwarden (AGPL-3.0, 1.37.4, 2026-10-05, 69k stars) | Unlimited items and devices. Authenticator codes, attachments, emergency access need Premium, $19.80 a year | Mobile: Cure53 Mar 2024 (8 issues), Mandiant Dec 2024 (8), Unit 42 Sep 2025 (2, both fixed). Cryptography: ETH Zurich 2025 (10) |
| Keyguard (AChep/keyguard-app) | LICENSE file reads "All Rights Reserved"; README: source "available for personal use only". **No** | v3.2.3, 2026-09-16 | 3.4k | Kotlin Multiplatform, Compose, Rust, SQLDelight on SQLCipher | Bitwarden account, or none with a local KDBX file (beta) | Yes, including edits synced later | Uses Bitwarden-compatible servers | Play build: adding or editing items needs Premium; other builds unlocked | None mentioned on its security page |
| Proton Pass (protonpass/android-pass) | GPL-3.0-or-later. Yes | 1.41.2, 2026-09-28 | 789 | Kotlin 2.3.20, Compose, Hilt, Room, SQLCipher; minSdk 27 | Yes (Proton) | Creating needs a connection [S]; offline reading not verified | No | Unlimited logins and devices; 2 vaults, 10 aliases | Cure53 May–Jun 2023; Recurity Labs Jan–Apr 2026 |
| Passbolt (passbolt/mobile-passbolt-android) | AGPL-3.0. Yes | 3.3.0, 2026-09-23 | 59 (server 6.1k) | Kotlin, Compose, Koin, Room on SQLCipher, GopenPGP; minSdk 30 | Yes, created first in a desktop browser | Not checked | Yes; Community Edition is free | Cloud plans start at 10 users | Cure53 repeatedly since 2021 (mobile Dec 2021); Quarkslab Nov 2025 |
| Psono (gitlab.com/esaqa/psono/psono-app) | Apache-2.0. Yes | v3.3.7, 2026-09-24 | 29 | Flutter, NaCl | Yes | Cache, if the server policy allows | Yes (Docker) | "free up to 10 users" | X41 Jun 2024; Cure53 Mar 2025, Mar 2026 |

Also in the evidence, not examined further: AliasVault (AGPL-3.0, 0.30.7 on 2026-09-16, account needed, self-hostable); Padloc (AGPL-3.0, last release 2023-04-28, last commit 2023-12-10, so stale); 2FAS Pass (local-first, Business Source License 1.1 so not open source, free up to 200 items). Licences come from each repository's LICENSE file, releases from `releases.atom` or GitLab tags, stars from shields.io; all fetched today.

## 2. Bitwarden in depth

**No account and no server is not possible.** The app offers only bitwarden.com, bitwarden.eu, a government region or a self-hosted URL [S `$BW/data/src/main/kotlin/com/bitwarden/data/repository/model/Environment.kt`].

**Vault cryptography as implemented** [S]

- **KDF.** PBKDF2-HMAC-SHA256, default 600,000 iterations, salt = email; or Argon2id, salt = SHA-256(email), SDK default 32 MiB, 6 iterations, 4 lanes (`$SDK/bitwarden-crypto/src/keys/kdf.rs:12-178`). Argon2id is the default for new "V2" accounts, whose user key is XAES-256-GCM (`.../store/cipher_suite.rs:34-39`, `.../keys/symmetric_crypto_key.rs:89-106`). Accounts created in the Android app get PBKDF2 unless the remote flag `pm-27278-v2-password-registration` is on; its compiled default is off (`$BWA/data/auth/repository/AuthRepositoryImpl.kt:858-873`).
- **Key hierarchy.** Master password → KDF → 256-bit master key → HKDF-SHA256 expand with labels `enc` and `mac` → 512-bit stretched key → unwraps the random 512-bit user key → unwraps a random 512-bit per-item key → item fields (`.../keys/master_key.rs:157-164`, `.../keys/utils.rs:12-19`, `$SDK/bitwarden-vault/src/cipher/cipher.rs:313,936-947`).
- **Field cipher and MAC.** Each field separately: AES-256-CBC with PKCS7, random 16-byte IV, HMAC-SHA256 over IV‖ciphertext, checked in constant time before decrypting, stored as `2.iv|ciphertext|mac`. The file says it "is not AEAD" (`$SDK/bitwarden-crypto/src/hazmat/symmetric_encryption/aes256_cbc_hmac_sha256_ae.rs`).
- **Not encrypted.** Item id, type, folder, collection and organisation ids, favourite and re-prompt flags, creation, revision, deletion and password-revision dates, URI match rule, attachment size (`cipher.rs:305-368`). On the phone a plain Room database, `vault_database`, holds each item's encrypted JSON with type and organisation id as readable columns; there is no SQLCipher (`$BWA/data/vault/datasource/disk/entity/CipherEntity.kt`, `.../di/VaultDiskModule.kt:41-43`).

**Android protections** [S]

| Topic | What the code does | File (under `$BWA` unless noted) |
|---|---|---|
| Biometric key | AndroidKeyStore AES, CBC with PKCS7, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`. No `setKeySize`, so the AOSP default of 128 bits applies [I]. No StrongBox | `data/platform/manager/BiometricsEncryptionManagerImpl.kt:108-121` |
| Biometric unlock | Prompt allows `BIOMETRIC_STRONG` only and carries `CryptoObject(cipher)`. The returned cipher decrypts the user key, stored with its IV as `userKeyBiometricUnlock` and `biometricInitializationVector` | `ui/platform/manager/biometrics/BiometricsManagerImpl.kt:139-153`; `data/vault/repository/VaultRepositoryImpl.kt:288-349` |
| Enrolment changes | `KeyPermanentlyInvalidatedException` at `cipher.init` → wrapped key, IV and Keystore entry are deleted; the user unlocks another way and re-enables biometrics | `BiometricsEncryptionManagerImpl.kt:178-203,237-240` |
| PIN | Minimum 4 characters. User key sealed with Argon2id (64 MiB, 3 iterations, 4 lanes, random 16-byte salt) and AES-256-GCM. Kept in ordinary SharedPreferences, or only in memory if the user chose "require master password on restart" | `ui/platform/feature/settings/accountsecurity/PinInputDialog.kt:41`; `$SDK/bitwarden-crypto/src/safe/password_protected_key_envelope.rs:440-466`; `data/platform/repository/SettingsRepositoryImpl.kt:619-650` |
| Other secrets | Own wrapper over a Keystore AES-256-GCM key (12-byte IV, 128-bit tag, no user authentication). Jetpack `EncryptedSharedPreferences` is used only to migrate old data | `$BW/core/src/main/kotlin/com/bitwarden/core/data/manager/encryption/KeystoreManagerImpl.kt:107-118` |
| Timeout | Immediately, 1, 5, 15, 30 min, 1 h, 4 h, on app restart, never, custom; default 15 min, action Lock. A coroutine `delay` starts on backgrounding and is re-armed on screen-on using `elapsedRealtime`. A fresh process starts locked, except with Never, which keeps the user key under a Keystore key needing no authentication | `data/platform/repository/model/VaultTimeout.kt`; `SettingsRepositoryImpl.kt:447-476`; `data/vault/manager/VaultLockManagerImpl.kt:537-803` |
| Clipboard | `EXTRA_IS_SENSITIVE` on by default (literal key string below API 33). Auto-clear is a WorkManager unique one-time job delayed 10 s to 5 min; default Never. The worker clears whatever is on the clipboard | `data/platform/manager/clipboard/BitwardenClipboardManagerImpl.kt:42-79`; `ClearClipboardWorker.kt` |
| Screen | `FLAG_SECURE` unless the user allows capture (blocked by default in release builds); `filterTouchesWhenObscured = true` | `MainActivity.kt:114,251-257`; `SettingsRepositoryImpl.kt:45` |
| Backup | `allowBackup="false"` plus rules excluding every domain from both cloud backup and device transfer | `$BW/app/src/main/AndroidManifest.xml:37-40`; `$BW/app/src/main/res/xml/data_extraction_rules.xml` |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, ACCESS_LOCAL_NETWORK, USE_BIOMETRIC, NFC, CAMERA, `horizons.permission.HEADSET_CAMERA`, POST_NOTIFICATIONS, READ_USER_DICTIONARY | `$BW/app/src/main/AndroidManifest.xml:12-20` |

**Self-hosting Vaultwarden** needs an always-on machine, Docker, a domain with HTTPS (the Android app forbids cleartext: `$BW/app/src/main/res/xml/network_security_config.xml:4`), your own backups, and updates: five releases between 2026-07-24 and 2026-10-05 [D Vaultwarden README and releases feed]. Not realistic for this user [I].

## 3. Proton Pass, briefly [S unless tagged]

- **Local encryption.** A random 32-byte key in the file `pass.key`, wrapped by a Keystore AES-256-GCM key that needs no user authentication (`$PP/crypto/impl/src/main/kotlin/proton/android/pass/crypto/impl/context/EncryptionContextProviderImpl.kt:138-168`; key settings in protoncore's `AndroidKeyStoreCrypto.kt`). Titles, notes and content are AES-256-GCM with a 12-byte IV and 128-bit tag (`.../context/EncryptionContextImpl.kt:90-92`). The main Room database is not encrypted as a whole; item type, alias email and timestamps are readable columns (`$PP/data/impl/src/main/kotlin/proton/android/pass/data/impl/db/entities/ItemEntity.kt`). Search uses a separate SQLCipher index that never receives passwords, one-time-code secrets or card numbers (`.../repositories/SearchableContentExtractor.kt:27-36`).
- **Lock.** Off until set up (default type None), then biometrics or a numeric PIN of at least 4 digits. It relocks after a chosen delay from 0 to 4 h (default 2 min), decided on resume from `elapsedRealtime` and the boot count (`$PP/biometry/api/src/main/java/proton/android/pass/biometry/NeedsAuthChecker.kt`). The prompt has no `CryptoObject` (`$PP/biometry/impl/src/main/java/proton/android/pass/biometry/BiometryManagerImpl.kt:140`) and the PIN is stored encrypted and compared, so the lock is a screen gate. A canary Keystore key detects enrolment changes and treats a bare `InvalidKeyException` as a change, because "Some OEM variants throw InvalidKeyException" instead of the specific one (`.../BiometricEnrollmentGuard.kt:59-66`).
- **Clipboard.** Sensitive flag; clear after 60 s (default), 180 s or never, scheduled with `AlarmManager.set(RTC_WAKEUP)`. The receiver clears only if the clipboard still holds the copied value, or if it cannot read it (`$PP/clipboard/impl/src/main/kotlin/proton/android/pass/clipboard/impl/`).
- **Screen and backup.** `FLAG_SECURE` plus `setRecentsScreenshotEnabled(false)` on API 33+, on every activity, unless the user allows screenshots (`$PP/common-ui/api/src/main/java/proton/android/pass/commonui/api/ActivitySecureMode.kt`). Backup is `allowBackup="false"` only, which on some devices does not stop device-to-device transfer for apps targeting API 31 or higher [D developer.android.com/identity/data/autobackup].

## 4. Google Password Manager (not open source)

- **Storage.** Passwords are saved to the Google Account. By default "the encryption key... is safely stored in your Google Account" and Google uses it to decrypt, for example for Password Checkup [D support.google.com/accounts/answer/11350823]. The default mode is therefore not zero-knowledge.
- **On-device encryption (optional).** The key stays with the user and is unlocked by the Google password or an eligible device's screen lock. "Once on-device encryption is set up, it can't be removed", and losing every recovery option loses all passwords [D same page].
- **Strengths.** Already on the phone, free, synced, with checks for compromised, reused and weak passwords; up to 10,000 passwords [D].
- **Weaknesses.** There is no separate master password: on the phone the gate is the screen lock, so anyone who knows the phone PIN can read everything [I from D]. Everything depends on one Google Account.
- **Export on Android** [D support.google.com/chrome/answer/13068232]: Chrome → ⋮ More → Settings → Autofill and passwords → Google Password Manager → Settings → Export passwords & passkeys → Download file. On a computer: Chrome → ⋮ More → Passwords and autofill → Google Password Manager → Settings → Export passwords → Download file [D support.google.com/chrome/answer/95606].
- **CSV written by Chrome** [S chromium `components/password_manager/core/browser/export/password_csv_writer.cc:16-51` and `csv_writer.cc`]: UTF-8, header `name,url,username,password,note`, in that order. A field containing a comma, double quote, CR or LF is wrapped in double quotes with inner quotes doubled. Rows end in LF (CRLF on Windows). Android-app logins carry a `url` beginning `android://`. The file is plaintext, and Google tells users to delete it after use [D].
- **Importer hints.** Chrome's own importer matches headers case-insensitively, requires URL, username and password, and accepts `url|website|origin|hostname|login_uri`, `username|user|login|account|login_username`, `password|login_password`, `note|notes|comment|comments` [S `.../import/csv_password_sequence.cc`]. Google Password Manager on Android can also pass passwords to another app with no file [D]; Bitwarden receives them through `androidx.credentials.providerevents` 1.0.0-alpha06 [S `$BW/gradle/libs.versions.toml`, `$BW/cxf/`].

## 5. What Android users say, 2025–2026

| App | Google Play today [D play.google.com listings] | Praise | Complaints |
|---|---|---|---|
| Bitwarden | 4.7 from 181K ratings, 5M+ installs | Free, easy to organise, password generator | "Autofill doesn't work... it doesn't reliably find password fields" (Oct 2025). Open issues in github.com/bitwarden/android: biometrics and timeout reset on every start (#4650), random logouts that lose settings (#4939) |
| Proton Pass | 4.8 from 60K, 1M+ | High rating | Autofill "sometimes only fills one field, sometimes fills nothing"; "constantly logs out on my phone" (both May 2026) |
| Keyguard | 4.7 from 615, 10K+ | "It's everything you wish Bitwarden was!" | Autofill regressions (May 2026); editing is paid |
| Google Password Manager | 4.1 from 16K (shortcut app) | Built in | "Once you get locked out... Everything is gone forever" (Jul 2026) |

- **Account and offline.** Bitwarden's offline-editing request was opened on 2018-03-08 and has not shipped [D community.bitwarden.com/t/offline-editing-management-of-writeable-vault-items/107]. Several forum threads ask for a local-only vault [2].
- **Speed of open, find, copy, and free-tier limits.** I found no measurements and no review evidence on limits (the limits are in section 1). Reviews do show that when autofill fails, people open the app and copy by hand, which is the flow we are building [I].

## 6. Fit for a non-expert who wants a simple vault on the phone

- **Bitwarden Free** is the better answer if an account is acceptable. Gain: audits, sync, recovery after a lost phone, autofill. Lose: a connection is needed to add or edit, a forgotten master password cannot be reset [M], and the app is busier. In April 2026 a poisoned copy of its CLI sat on npm for about 90 minutes; Bitwarden reports no evidence that vault data was accessed [D community.bitwarden.com, 2026-04-23]. It teaches the settings in section 2.
- **Proton Pass** is a close second. Lose: the lock is a screen gate and is off until set up. It teaches the search index without secrets and the resume-time lock check.
- **Google Password Manager** costs nothing to keep and is far better than reuse, given a strong screen lock and 2-Step Verification [I].
- **The rest** do not suit this user: paid editing (Keyguard), team tools (Passbolt, Psono), a server to run (Vaultwarden, AliasVault) or stale code (Padloc).
- **A home-built app** is the right answer only for "no account, works offline, and I will keep a backup".

## Lessons for our app

- Copy a Keystore key with user authentication and enrolment invalidation, used through `CryptoObject`. Add `setKeySize(256)` and GCM, and StrongBox where present (Passbolt sets it). Bitwarden's two later mobile audits (Mandiant, Unit 42) raised no biometric finding [D].
- Copy Argon2id at 64 MiB, 3 iterations, 4 lanes: Bitwarden's PIN envelope and 2FAS Pass use it on Android, and Keyguard documents 64 MB and 3 passes.
- Copy `EXTRA_IS_SENSITIVE`, `FLAG_SECURE` by default with a user switch, `setRecentsScreenshotEnabled(false)`, `filterTouchesWhenObscured`, and extraction rules for cloud backup and device transfer, because `allowBackup="false"` alone is not enough.
- Copy Proton's clipboard check (clear only if our value is still there) and its search index that holds no secrets.
- Copy the lock rule: key in memory only, a new process starts locked, timeout judged with `elapsedRealtime`.
- Avoid Jetpack `EncryptedSharedPreferences`, which Bitwarden has left, and everything in findings 4 to 6.
- Avoid a PIN-wrapped key on disk protected only by a software KDF: a 4-digit PIN is 10,000 guesses [I]. Mandiant asked Bitwarden for at least six digits [D].
- Avoid leftovers: when the user erases the vault, delete the SQLite and write-ahead-log files. Recurity Labs rated data left there after sign-out as Medium in Proton Pass [2 cyberinsider.com, 2026-05-13].

## Not settled

- The CSV header written by the Android "Download file" export. Check one real export from the user's phone before fixing the importer.
- Whether a small app can use the file-less credential exchange with Google Password Manager; the library is alpha.
- Bitwarden's biometric key being 128-bit is my reading of its code and of AOSP's `AndroidKeyStoreKeyGeneratorSpi`, not a device test.
- Whether Proton Pass reads cached items offline, and whether its onboarding pushes users to enable the lock.
- minSdk 24: Bitwarden supports 29+, Proton Pass 27+, Passbolt 30+. Several APIs above need fallbacks below API 30 or 33.
- Clipboard-clear timing on real devices: neither a WorkManager job nor an inexact alarm is punctual [M].
