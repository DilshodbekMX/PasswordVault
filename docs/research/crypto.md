# Cryptographic design for the local vault

Research date 2026-10-06. Tags: **[C]** confirmed today from the named primary source; **[M]** from memory; **[I]** my inference or arithmetic. Code is cited as `repo:path` at today's HEAD; bare Aegis paths are relative to `app/src/main/java/com/beemdevelopment/aegis/`. Bracketed keys resolve under Sources.

## 1. Recommended design

| Element | Choice |
|---|---|
| KDF | Argon2id v1.3 with m = 65,536 KiB (64 MiB), t = 3, p = 4, a 16-byte random salt and 32-byte output. Fixed constants, no per-device calibration |
| Password bytes | UTF-8 of the NFC-normalised password, nothing trimmed |
| Argon2 code | `com.lambdapioneer.argon2kt:argon2kt:1.6.0` (native). Bouncy Castle (BC) `org.bouncycastle:bcprov-jdk18on:1.86` `Argon2BytesGenerator` as runtime fallback and JVM test oracle |
| AEAD | AES-256-GCM through the platform JCA, `"AES/GCM/NoPadding"` with no provider name, 12-byte nonce from `SecureRandom()`, 16-byte tag, for wraps and body |
| Keys | The password derives a key-encryption key (KEK), which wraps a random 256-bit vault key (VK). An optional biometric slot wraps the same VK under an Android Keystore AES-256-GCM key. A password change makes a new VK. An export gets its own key |
| Binding | The body's associated data (AAD) is every stored byte before the body nonce. Wraps use no AAD |
| Password check | The 48-byte wrap decrypts or it does not. No stored verifier |
| Biometrics | `androidx.biometric:biometric:1.1.0`, `BiometricPrompt` with `CryptoObject(Cipher)`, Class 3 only. The password always works alone and is demanded every 14 days |

```
"PVAULT01"    8 bytes   magic; a framing change gets a new magic
header_len    u32 BE    reject > 65,536
header        UTF-8 JSON; the stored bytes are authoritative
body_nonce    12 bytes
body_ct       AES-256-GCM(VK, body_nonce, body_json, AAD = all bytes above), tag appended

header = {"format":"passwordvault","version":1,"cipher":"AES-256-GCM",
  "created":"<RFC 3339>","generator":"<applicationId versionName>",
  "slots":[
   {"type":"password","id":"<uuid>","kdf":"argon2id","argon2_version":19,
    "m_kib":65536,"t":3,"p":4,"salt":"<b64 16 B>","nonce":"<b64 12 B>","wrapped_key":"<b64 48 B>"},
   {"type":"biometric","id":"<uuid>","nonce":"<b64 12 B>","wrapped_key":"<b64 48 B>"}]}
body_json = {"schema":1,"entries":[...]}
```

The Keystore alias is `"vault_bio_" + id`. Before any KDF or cipher runs, a reader rejects an unknown `format`, `version`, `cipher`, `kdf` or slot type; more than one slot per type; a salt that is not 16 bytes, a nonce that is not 12, a `wrapped_key` that is not 48; `m_kib` outside 65,536 to 262,144, `t` outside 3 to 10, `p` outside 1 to 8. Writers use compiled-in constants only.

Test vector [C: computed today, identical from BC 1.86 and the Password Hashing Competition (PHC) reference C code]: Argon2id("correct horse battery staple", salt 00 01 … 0f, parameters above) = `853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e`. BC also reproduces the RFC 9106 §5.3 Argon2id tag.

## 2. Where your leaning is wrong, fragile or over-built

1. **The outline holds.** It is the Aegis slot model, and Bitwarden's `PasswordProtectedKeyEnvelope` is the same construction, with 64 MiB / 3 / 4 as its preset outside iOS [C: BW-ENV].
2. **Wrong: "change the password without re-encrypting" is a liability.** Aegis re-wraps the same master key (`ui/fragments/preferences/SecurityPreferencesFragment.java:330`) [C], so an old file plus the old password opens every later file [I]. Our vault is rewritten on every save anyway. Make a new VK on password change.
3. **Wrong by omission: exports must not share VK.** Aegis exports and Android backups reuse the master key (`vault/VaultRepository.java:168`) [C].
4. **Fragile: header as AAD.** Keep it: a biometric unlock never touches the password slot, so nothing else would notice that slot being altered [I]. Aegis leaves this open ("The rest of the file is not" authenticated, `docs/vault.md`); KDBX4 and age MAC their headers [C: KeePassDX `database/src/main/java/com/kunzisoft/keepass/database/file/input/DatabaseInputKDBX.kt:186-194`; AGE]. The AAD must be the stored bytes, never re-serialised JSON, hence the length-prefixed layout.
5. **Fragile: KDF parameters read from the file.** Tampering cannot make a weaker key open anything: RFC 9106 hashes p, m, t and v into H0, and Bitwarden leaves the settings unprotected because "to authenticate the protected header, the settings must be correct" [C]. It can demand gigabytes of memory or 2^32 passes. Bound them first, as Bitwarden (`InsufficientKdfParameters`, `kdf.rs`), KeePassDX (refuses above 3/4 of `availMem`) and age ("SHOULD apply an upper limit to the work factor") do [C]. Age also fixes the wrapped body length "to mitigate partitioning oracle attacks"; our 48-byte rule does the same.
6. **Fragile: the biometric slot.** Keystore keys die and Keystore calls stall (section 7).
7. **Fragile: a single native KDF library**, with a pure-Java fallback limited by the Java heap (sections 3 and 4).
8. **Over-built.** Equally safe without AAD on wraps, calibration, a separate verifier, StrongBox, `setUnlockedDeviceRequired` or a second AEAD.
9. **The master password dominates.** One RTX 4090 tries about 1,700 Argon2id guesses per second at 64 MiB, t = 3, p = 1 [C, secondary: HASHCAT]. A reused human password inside the first 10^9 guesses falls in about 3.4 GPU-days. Five random EFF words (64.6 bits) need about 2.6×10^8 GPU-years [I]. No parameter rescues a reused password, which is this user's habit.

## 3. KDF

| Source | What it says [C] |
|---|---|
| OWASP | Argon2id minimum 19 MiB, t = 2, p = 1, with 46 MiB/1, 12 MiB/3, 9 MiB/4 and 7 MiB/5 as equals. scrypt N = 2^17, r = 8, p = 1, or 2^16 with p = 2, or 2^15 with p = 3. PBKDF2-HMAC-SHA256 600,000; SHA-512 220,000; SHA-1 1,400,000 "legacy only" |
| RFC 9106 §4 | FIRST: t = 1, p = 4, m = 2^21 KiB (2 GiB), 128-bit salt, 256-bit tag. SECOND, when "much less memory is available": t = 3, p = 4, m = 2^16 KiB (64 MiB). §3.1: salt "16 bytes is RECOMMENDED" |
| NIST | Single-factor passwords of at least 15 characters, no composition rules, NFC before hashing, store the scheme and cost factor |

| Product | Default [C] | Where |
|---|---|---|
| Bitwarden account | PBKDF2-SHA256 600,000; Argon2id option 32 MiB, t = 6, p = 4 | BW-HELP; `sdk-internal:crates/bitwarden-crypto/src/keys/kdf.rs` |
| Bitwarden local envelope | Argon2id 64 MiB / 3 / 4, on iOS 32 MiB / 6 / 4; "a static preset based on the target os" | BW-ENV |
| KeePassXC | Argon2d, 64 MiB, p = min(cores, 4), rounds from a benchmark aimed at 1,000 ms | `keepassxc:src/crypto/kdf/Argon2Kdf.h`, `Kdf.h` |
| KeePassDX | AES-KDF, 500,000 rounds; Argon2 option 16 MiB / 3 / 4; 1-second benchmark since 4.5.0 | `KeePassDX:database/src/main/java/com/kunzisoft/keepass/database/crypto/kdf/KdfFactory.kt`, `AesKdf.kt`, `Argon2Kdf.kt`, `KdfEngine.kt`; `CHANGELOG` |
| Aegis | scrypt N = 2^15, r = 8, p = 1 (32 MiB), pure Java, `largeHeap` | `crypto/CryptoUtils.java`, `docs/vault.md` |
| 1Password | PBKDF2, 650,000 iterations, plus a device-held Secret Key | 1P |
| Proton Pass | No local password KDF; a random key wrapped by Keystore | `android-pass:pass/crypto/impl/src/main/kotlin/proton/android/pass/crypto/impl/context/EncryptionContextProviderImpl.kt` |

**Phones.** On a Pixel 3 the argon2kt author measured about 60 ms for 37 MiB × 1 pass, and 2,200 ms for PBKDF2-SHA256 at 310,000 iterations through the platform [C: HUGENROTH]. That puts our setting near 0.3 s there and perhaps 1 s on a 2016 low-end phone, and PBKDF2 at 600,000 near 4.3 s [I]. A 4090 does about 14,800 PBKDF2-600k guesses per second [C: HASHCAT-GIST, scaled]. Argon2id therefore costs the attacker roughly 9 times more and the phone 14 times less [I].

**Memory.** The Android Compatibility Definition (CDD) guarantees a normal-screen app only 48 MB (hdpi), 80 MB (xhdpi) or 128 MB (xxhdpi) of Java heap, in both CDD 7.0 and 16 [C: CDD §3.7]. Aegis documents that raising its Java scrypt runs "into OOM conditions on most devices" [C]. Native allocations fall outside that limit [M].

**Fixed versus calibrated.** KeePassXC and KeePassDX calibrate rounds; Bitwarden does not. I recommend fixed. Calibrated memory can make a backup unopenable on a weaker phone [I]. KeePassDX's benchmark release was followed by "Fix Memory optimization" and "Fix memory limits" releases [C; the link is I]. One more passphrase word is worth 7,776 times.

## 4. Argon2 implementations

All natives cover arm64-v8a, armeabi-v7a, x86 and x86_64. The last column is my own parse of the ELF program headers today.

| Artifact | Latest on Maven | Maintenance, licence | minSdk, natives | 64-bit LOAD `p_align` |
|---|---|---|---|---|
| `com.lambdapioneer.argon2kt:argon2kt` | 1.6.0, 2024-09-06 | Commits 2026-09-01/02 (API 37, AGP 9.4, NDK r27d), unreleased. MIT | 21. `libargon2jni.so` and `libargon2native.so`, 244 KB on arm64 | 0x4000, pass (1.5.0 had 0x1000) |
| `org.signal:argon2` | 13.1, 2020-01-23 | Last commit 2020-12-19. GPL-3.0 | 19. `libargon2.so` | arm64 0x10000 pass; x86_64 0x1000 fail |
| `org.bouncycastle:bcprov-jdk18on` | 1.86, 2026-09-11 | Very active. MIT | Java 8 bytecode, none | not applicable |
| `com.goterl:lazysodium-android` | 5.2.0, 2025-05-31 | Last commit that day. MPL-2.0 | 21. libsodium 1.0.20 plus JNA 5.17.0 | 0x4000, pass, JNA too |
| `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings-android` | 0.9.5, 2025-11-22 | Last commit 2026-06-09. Apache-2.0 | 24. libsodium 1.0.20 plus JNA 5.18.1 | 0x4000, pass, JNA too |

Nothing newer helps: cryptography-kotlin 0.6.0 and Tink 1.23.0 have no Argon2, and `password4j` 1.8.4 is another JVM implementation [C: READMEs, jar listing]. Google Play refuses updates without 16 KB support from 2027-02-01 for apps targeting API 35 or higher [C: PAGE].

- **First choice: argon2kt.** PHC reference C with threads. Warts [C: source, issue page]: the JNI layer never frees its two output buffers, and the encoded string contains the hash, so copy the key out, overwrite `rawHash` and `encodedOutput`, and never store the encoded string. Issue #25 reports `UnsatisfiedLinkError` on Transsion and Huawei devices with 1.5.0; the README suggests ReLinker. 1.6.0 pulls in AppCompat, which androidx.biometric needs anyway.
- **Fallback: Bouncy Castle**, when the native library fails to load. Same output [C]. It is single-threaded (83 ms against 45 ms for threaded C on this desktop) and holds about 64 MiB of `long[]` on the Java heap, so set `android:largeHeap="true"`, as Aegis and KeePassDX do. Aegis's build excludes `META-INF/versions/9/OSGI-INF/MANIFEST.MF` and BC's `pqc` property files [C]. Its speed on a phone is unmeasured.
- **libsodium bindings rejected.** `crypto_pwhash` hard-codes one lane [C: `libsodium:src/libsodium/crypto_pwhash/argon2/pwhash_argon2id.c`], both ship 1.0.20 while upstream is 1.0.22, and they add JNA.
- **If none is acceptable**, compile the PHC reference (CC0 or Apache-2.0) in our own JNI module; this machine has no NDK. Failing that, BC scrypt at N = 2^15, r = 8, p = 3, or PBKDF2-HMAC-SHA256 at 600,000. The platform has `PBKDF2withHmacSHA256` only from API 26 [C: SKF], so API 24 and 25 need BC.

## 5. AEAD

- The platform offers `AES/GCM/NoPadding` from API 10, `updateAAD` from 19, ChaCha20/Poly1305 from 28 with a 96-bit nonce, AES-GCM-SIV from 30, and no XChaCha20. Keystore keys are AES, HMAC, RSA, EC, 3DES or ML-DSA, so the biometric wrap must be AES [C: CIPHER, KEYPROPS].
- XChaCha20-Poly1305 has only an Internet-Draft that expired on 2020-07-13 [C: datatracker]. Bitwarden moved its envelope from it to AES-256-GCM [C: BW-ENV].
- SP 800-38D §8 requires the chance of a repeated key and IV to stay at or below 2^-32, and §8.3 caps random-IV use at 2^32 invocations per key [C]. A million saves under one VK gives about 2^-57 [I]. The 96-bit nonce is not a constraint here.
- Conscrypt takes only 12-byte GCM nonces and defaults to a 128-bit tag [C: AOSP main `OpenSSLAeadCipher.java`].
- Versioning: `version` selects the whole suite. A reader refuses higher versions, as Aegis does (`vault/VaultFile.java:64`). The entry schema is versioned separately inside the body.

## 6. Key hierarchy and Aegis slots

Aegis file [C: `docs/vault.md`, `vault/VaultFile.java`, `vault/slots/Slot.java`]: `{"version":1,"header":{"slots":[…],"params":{"nonce","tag"}},"db":"<base64 ciphertext>"}`. Slot types are 0 raw, 1 password, 2 biometric. Each slot has `uuid`, `key` (the wrapped master key, hex) and `key_params` (nonce and tag, hex). Password slots add `n`, `r`, `p`, a 32-byte `salt`, `repaired` and `is_backup`.

```java
// crypto/CryptoUtils.java
public static final String CRYPTO_AEAD = "AES/GCM/NoPadding";
public static final byte CRYPTO_AEAD_NONCE_SIZE = 12;
public static final int CRYPTO_SCRYPT_N = 1 << 15;
```

The provider generates every nonce, the tag is stored apart from the ciphertext, and no AAD is used. A password attempt derives the key and tries each password slot; a tag failure means wrong password (`ui/tasks/PasswordSlotDecryptTask.java`). That file also holds Aegis's one migration: "a bug introduced in afb9e59 caused passwords longer than 64 bytes to produce a different key", repaired by re-wrapping on unlock and setting `repaired`. Pin our password encoding with test vectors before the first release.

## 7. Biometric unlock

```kotlin
KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)   // KeyProperties constants
  .setKeySize(256).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
  .setUserAuthenticationRequired(true)
  .setInvalidatedByBiometricEnrollment(true)
  .apply { if (SDK_INT >= 30) setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG) }
  .build()
```

- Current AOSP defaults are already per-use, `AUTH_BIOMETRIC_STRONG`, invalidated on enrolment and randomised encryption [C: AOSP `KeyGenParameterSpec.java:964-978`]. With randomised encryption "caller-provided IVs are rejected when encrypting", so initialise without an IV and store `cipher.iv` [C: KGPS].
- User authentication "applies only to secret key and private key operations" [C: KGPS], so wrapping needs a prompt too. `authenticate(info, crypto)` "is incompatible with Class 2 … biometrics and (prior to Android 11) device credential" [C: BPROMPT]. Prompt with `setAllowedAuthenticators(BIOMETRIC_STRONG)` and a negative button, as Bitwarden does.
- The key is "irreversibly invalidated" when the lock screen is disabled or a biometric is enrolled; `cipher.init` then throws `KeyPermanentlyInvalidatedException` [C: KGPS]. Aegis and Proton Pass both note that some devices throw a plain `InvalidKeyException` [C].
- StrongBox is "slower, more resource-constrained", and "For most apps, StrongBox is not necessary" [C: KEYSTORE]. A device with a fingerprint sensor must have a hardware-backed Keystore anyway [C: CDD 7.0 §9.11]. `setUnlockedDeviceRequired` had bugs on Android 12 to 14, including automatic key deletion [C: KGPS]. Skip both.
- Library: the latest stable is 1.1.0 (2021-01-27). The 1.4.0 alphas (alpha07, 2026-04-22) add `registerForAuthenticationResult`, `Strength.Class3(cryptoObject)` and `biometric-compose`, and their API was still changing in alpha05 to alpha07 [C: BIO-REL and the API reference]. With 1.1.0 the activity must extend `FragmentActivity`.

| App [C] | Keystore key | What it wraps | Library |
|---|---|---|---|
| Aegis `crypto/KeyStoreHandle.java` | AES-256-GCM, auth required, alias = slot UUID | master key | 1.1.0 |
| Bitwarden `app/src/main/kotlin/com/x8bit/bitwarden/data/platform/manager/BiometricsEncryptionManagerImpl.kt` | AES/CBC/PKCS7, auth required, invalidated on enrolment | user key | 1.2.0-alpha05 |
| KeePassDX `app/src/main/java/com/kunzisoft/keepass/biometric/DeviceUnlockManager.kt` | AES/CBC/PKCS7, StrongBox when present | the master password itself | 1.1.0 |
| Proton Pass `pass/biometry/impl/src/main/java/proton/android/pass/biometry/BiometricEnrollmentGuard.kt` | AES-GCM guard key | nothing; the prompt has no `CryptoObject` | 1.2.0-alpha05 |

Copy Aegis. Do not store the password, and do not use CBC.

**Why Keystore is never the only route.** Its keys are device-bound and die on enrolment change or lock-screen removal [C], and on uninstall or restore [M]. A Xiaomi on Android 15 returned `SECURE_HW_COMMUNICATION_FAILED` for every Keystore call until reboot, and Bitwarden's recovery path, still at HEAD, then deleted its key and preferences [C: BW-7436, a user report; `android:data/src/main/kotlin/com/bitwarden/data/datasource/disk/di/PreferenceModule.kt:57-83`]. For a local-only vault that would be total loss. Treat invalidation as "remove the slot", anything else as "use the password, change nothing", and never delete vault data on a Keystore error. Aegis asks for the password every two weeks by default so that it is not forgotten [C: `Preferences.java:123`].

## 8. Secrets in memory

- Achievable: keep the password, KEK and VK out of saved state, logs and files; zero our own arrays; drop references on lock.
- Not achievable: wiping what we do not own. `SecretKeySpec` copies the key and `Destroyable.destroy()` throws by default [C: Android reference]. `rememberTextFieldState` saves its text through `rememberSaveable` [C: COMPOSE]. Use `remember { TextFieldState() }` with a secure text field, read the characters once, then `clearText()`.
- Theatre: Proton Pass XORs its in-memory key with the constant 0xDE [C: same `EncryptionContextProviderImpl.kt`].
- Practice [C]: Aegis reads a `char[]` from the field and does not zero it; lock sets a reference to null. KeePassDX zeroes its arrays. Bitwarden holds the password as a `String` but marks it `@IgnoredOnParcel`.

## 9. Password generation

Use `SecureRandom()` with `nextInt(bound)`, which rejects "values that would result in an uneven distribution" [C: java.util.Random reference]. Never use `% n`. Bitwarden and KeePassDX both default to 14 characters on Android [C]. I suggest 20 characters from letters and digits (119 bits) for entries [I].

EFF long list [C: file and article]: 7,776 words, 12.9 bits each, 3 to 9 letters, none a prefix of another, CC BY 4.0. EFF recommends six words (77 bits). Four entries contain a hyphen; Bitwarden filters them out because hyphen is its separator. Offer a five-word passphrase as the default master password.

## 10. Portable backup

Use the same container with exactly one password slot, its own key and salt, and no biometric slot (Aegis strips those in `vault/slots/SlotList.java`, `exportable()`). The header already names the format, version, cipher, KDF, every parameter, creation time and generator. Publish the format and a short decrypt script, as Aegis does with `docs/decrypt.py`.

## 11. Flows

**Create.** (1) Password to NFC, UTF-8, direct `ByteBuffer`. (2) 16-byte salt; KEK = Argon2id. (3) VK = 32 random bytes. (4) Wrap VK with a fresh nonce. (5) Build the header; encrypt the body with the header bytes as AAD. (6) Write atomically, read back and decrypt. (7) Zero the password and KEK.

**Unlock.** (1) Read the file and apply the reader rules. (2) Derive the KEK. (3) Unwrap; failure is "wrong password". (4) Decrypt the body; failure is "file damaged", a different message. (5) If a later release has raised the constants, re-derive with a new salt, re-wrap and save. (6) Remove a biometric slot whose alias is missing or invalidated. (7) Record the time.

**Enrol biometric.** (1) Vault unlocked; `canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`. (2) Generate the Keystore key; `cipher.init(ENCRYPT_MODE, key)`. (3) Prompt with the `CryptoObject`. (4) `wrapped_key = cipher.doFinal(VK)`, `nonce = cipher.iv`. (5) Add the slot, re-encrypt the body, write. (6) On error or cancel, delete the key.

**Biometric unlock.** (1) No alias: hide the option. (2) `cipher.init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))`; on invalidation delete the key and ask for the password. (3) Prompt. (4) `doFinal`, expect 32 bytes. (5) Decrypt the body. (6) After 14 days without a password unlock, require the password.

**Change password.** (1) Verify the current password by unwrapping. (2) New VK, salt, KEK and password slot. (3) Remove the biometric slot and its key. (4) Re-encrypt, write, read back. (5) Delete older generations, which the old password still opens.

**Export.** (1) Type the backup password. (2) Fresh key, salt and KEK; one slot. (3) Encrypt the body. (4) Write through the document picker, read back and decrypt.

## 12. Rejected

| Alternative | Why |
|---|---|
| XChaCha20-Poly1305 | Third-party code, no standard, unusable for the Keystore wrap, and the nonce budget is not a problem |
| KDF key encrypts the body directly | Leaves no clean biometric path |
| Calibrated parameters, StrongBox, device-credential unlock | More failure modes for little gain; a PIN-authorised key lets the phone PIN open the vault |
| Keystore secret mixed into the KDF | Stops offline guessing, but any Keystore loss destroys the vault |
| Keystore-wrapped password (KeePassDX) | Exposes the password and still pays the KDF |
| Pure-Java Argon2 as primary | Heap-bound, so parameters would have to shrink |
| PBKDF2 or scrypt as primary | Worse exchange rate (section 3) |

## Sources

OWASP cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html (commit 2026-10-04); RFC 9106 rfc-editor.org/rfc/rfc9106; SP 800-38D nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf; NIST pages.nist.gov/800-63-4/sp800-63b.html; BW-ENV `bitwarden/sdk-internal:crates/bitwarden-crypto/src/safe/password_protected_key_envelope.rs`; BW-HELP bitwarden.com/help/kdf-algorithms/; BW-7436 github.com/bitwarden/android/issues/7436; 1P support.1password.com/pbkdf2/; datatracker.ietf.org/doc/draft-irtf-cfrg-xchacha/; AGE github.com/C2SP/C2SP/blob/main/age.md; HUGENROTH danielhugenroth.com/posts/2021_06_password_hashing_on_android/; HASHCAT en.wikipedia.org/wiki/Hashcat (hashcat 7.0.0 sample output); HASHCAT-GIST gist.github.com/Chick3nman/32e662a5bb63bc4f51b847bb422222fd; CDD source.android.com/docs/compatibility/7.0/android-7.0-cdd and /16/android-16-cdd; PAGE developer.android.com/guide/practices/page-sizes; KGPS developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder; KEYSTORE developer.android.com/privacy-and-security/keystore; KEYPROPS, CIPHER, SKF, BPROMPT and COMPOSE are the developer.android.com reference pages for `KeyProperties`, `javax.crypto.Cipher`, `SecretKeyFactory`, `androidx.biometric.BiometricPrompt` and `TextFieldState`; BIO-REL developer.android.com/jetpack/androidx/releases/biometric; AOSP android.googlesource.com `platform/frameworks/base` and `platform/external/conscrypt`; EFF eff.org/files/2016/07/18/eff_large_wordlist.txt, eff.org/deeplinks/2016/07/new-wordlists-random-passphrases, eff.org/copyright; Maven Central and Google Maven metadata, POMs and AARs. Releases read: Aegis v3.4.3, KeePassDX 4.5.5.

## Lessons for our app

- Push a generated five-word passphrase; it outweighs every parameter here.
- A password change makes a new vault key, and an export uses its own.
- Authenticate the stored header bytes, and bound every length and cost before use.
- Biometric unlock is a convenience: classify Keystore errors, never delete on them, and keep the password in the user's memory with a 14-day prompt.
- Keep two Argon2 implementations and cross-check them in tests against the vector above.
- Write parameters from constants only, and fix the password encoding now.

## Not settled

- Argon2id time and memory on real low-end hardware, native and Bouncy Castle. Nothing was measured on a phone.
- Whether argon2kt 1.6.0 survives R8 in a release build under AGP 9.4.1 (its AAR ships no consumer rules, only `@Keep`), and whether a 1.7.0 appears.
- Whether `largeHeap` plus the fallback belongs in v1, or only a clear error.
- androidx.biometric 1.1.0 on API 37. Aegis ships it at target 36 [C], which suggests it works [I].
- Key loss caused specifically by an OS update: I found no primary source.
- Whether to add a printed recovery-key slot, and whether exports may use a separate password.
