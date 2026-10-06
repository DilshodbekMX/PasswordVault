# Design 1: how the user enters the app (security first)

Written 2026-10-06 for the three judges. Angle: start from the attacker, make every PIN guess as limited and as expensive as the phone's hardware allows, then make it pleasant to use.

**Tags.** **[C]** confirmed today from the named primary source (I re-opened every source a decision hinges on). **[L]** seen today in this session's emulator-lab logs (path given). **[X]** computed or measured by me today (script given). **[I]** my inference; check it before relying on it. **[S2]** secondary source. `FACTS` = `scratchpad/design/lock-facts/FACTS.md`; `V-xx` = items in `docs/research/VERIFICATION.md`; `LAB` = `scratchpad/lab/`; `D1` = `scratchpad/design/lock-designs/d1src/` (my scripts and the AOSP files I fetched today).

---

## 0. The design on one page

1. **One vault key, three secrets.** A random 256-bit vault key (VK) encrypts the vault. The master password (MP) wraps VK in the portable vault file, exactly as already decided (Argon2id 64 MiB, t = 3, p = 4). The PIN and the fingerprint wrap the same VK in a **device-only file** in `noBackupFilesDir`, using Android Keystore keys that never leave the phone's secure hardware. Nothing about the PIN or fingerprint is ever written into the vault file, a backup or an export.
2. **The PIN is checked by single-use "attempt keys".** The device holds a pool of 5 HMAC-SHA256 keys inside the Keystore. Each key can evaluate exactly one PIN guess: the guess is HMAC'd inside the secure hardware and the output unwraps VK only if the PIN is right. On Android 12+ every attempt key carries `setMaxUsageCount(1)`. That limit is enforced inside the secure hardware on phones that declare `FEATURE_KEYSTORE_SINGLE_USE_KEY`, and by the keystore daemon on the others. A correct PIN refills the pool.
3. **Limits.** 5 wrong PINs in a row, or 10 wrong PINs between two master-password unlocks, turn PIN unlock off. Nothing else changes: fingerprint and MP still work, and no data is touched. There are no waiting times.
4. **Order of writes.** The app writes "this attempt is spent" to disk, with fsync of the file and its directory, **before** the PIN is evaluated. Killing the app, cutting power, rebooting, changing the clock or clearing the cache never yields an extra guess.
5. **PIN rules.** Exactly 6 digits, on a pad drawn by the app, submitted automatically after the sixth digit. A rule-based blocklist covering dates, repeats, sequences and keypad shapes rejects 8.98 % of all PINs, including all of the 100 most common ones.
6. **Hardening.**
   - Attempt keys go into StrongBox when the phone has it (API 31+).
   - On Android 15+ they are usable only while the phone is unlocked.
   - PIN and fingerprint are refused on a phone whose Keystore is not hardware-backed.
7. **Fingerprint.**
   - Class 3 only, through `BiometricPrompt.CryptoObject`.
   - The key is per-use and dies when fingerprints are enrolled or removed.
   - androidx.biometric 1.1.0, offered on Android 9 and newer. On API 24 to 27 that library cannot reach the fingerprint sensor (lab-confirmed), and the PIN covers those phones.
8. **When the MP is required.**
   - Setup.
   - PIN turned off, at the limit or because its keys are lost.
   - Spaced reminders: after 1 day, then 3 days, then 7 days, then every 14 days, each counted from the last time the MP was typed.
   - Every export and backup.
   - Every change that adds a way in or weakens the lock.
   - **Not** after a reboot.
9. **Forgot the MP while the PIN still works.** The user can set a new MP after a **72-hour wait**, measured on the phone's uptime clock so changing the date does not shorten it, using every enabled device secret. A banner on every unlock lets the owner cancel it.
10. **Errors.**
    - A device secret is removed only when the Keystore says it is gone: `KeyPermanentlyInvalidatedException`, or `getKey()` returning null (double-checked on API 24 to 30, where null can also mean "daemon unreachable"). A fingerprint blob that fails its tag is also removed.
    - Every other error is temporary: use the MP this time and change nothing.
    - Vault data is never deleted or rewritten because of a wrong PIN, a lockout or a Keystore error.
11. **The phone's own screen lock** ("unlock with your phone's PIN") is **not offered in version 1** (section 7). The user's sentence most likely means a banking-style app PIN. This is open question 1.
12. **Auto-lock defaults.**
    - 1 minute after leaving the app.
    - 5 minutes without a touch.
    - At once when the screen turns off.
    - Always at process start.

**What one PIN guess costs** (details in 2.6):

| Attacker | PIN guesses they get |
|---|---|
| Copy of every file the app stores, without the phone's secure hardware | **None.** A PIN cannot be tested; they must attack the MP's Argon2id |
| Thief or finder, no root | **5 in a row, 10 at most** between two MP unlocks |
| Phone rooted later, Android 15+, phone locked, no Smart Lock or Class 2 face unlock | **None** until they break the phone's own lock screen, which the hardware throttles |
| Phone rooted later, phone declares hardware single-use keys | **The attempt keys left (at most 5)**, enforced in secure hardware |
| Phone rooted later, every other phone | After defeating the keystore daemon's count (API 31+): one secure-hardware operation per guess, about 30 to 90 per second, so **all 910,180 allowed PINs in about 3 to 8.5 hours** (TEE) **or 3 to 28 hours** (StrongBox) [I]. This is the honest weak spot of any app PIN, and it is why the MP alone protects every copy |

---

## 1. Threat model and goals

| # | Attacker | Has | Must not get |
|---|---|---|---|
| A1 | Copier | Every file the app stores (vault generations, the device file, backups, encrypted exports), for example from a forensic image or a backup, but **not** the phone's secure hardware | Anything without the MP. The PIN must give no offline shortcut (hard requirement 2) |
| A2 | Thief, finder or snoop | The phone, locked or unlocked, no root, bootloader locked; can kill the app, reboot, change the clock, clear the cache, use `adb` if debugging is on | More than a small fixed number of PIN guesses (requirement 3) |
| A3 | Rooted later | A2's phone plus code execution as root through an exploit, with data intact. Unlocking the bootloader wipes user data and changes the key root of trust, so that route yields nothing [I] | As few PIN guesses as the hardware can enforce, and none while the phone is locked where the OS can enforce it |
| A4 | Repeated-access insider | A family member who can pick up the phone on many days | More than 10 guesses between two MP entries, and the owner must find out about the attempts |
| A5 | Coercer or shoulder-surfer | Saw the PIN, or can press the owner's finger to the sensor | Bulk plaintext export or a silent MP takeover |

Out of scope, and the app's help text will say so: a phone compromised while the owner keeps using it (the attacker reads the PIN or MP as it is typed), malicious keyboards, accessibility malware the user enabled, TEE key-extraction exploits on phones without StrongBox, and a weak or reused MP against a stolen file (platform.md section 4).

---

## 2. Key hierarchy

### 2.1 Picture

```
                       vault body (entries JSON)
                                 ^
                    AES-256-GCM(VK), AAD = magic|len|header bytes   [vault file, portable]
                                 |
                       VK  (32 random bytes, id = vk_id)
       ______________________/   |    \_______________________________________
      |                          |                                            |
 Password slot              PIN slot: attempt key i = 1..5             Fingerprint slot
 [vault header,             [device file, noBackupFilesDir]           [device file]
  portable]
 KEK_pw = Argon2id(          t_i = HMAC-SHA256(K_i, pinMsg_i)          K_bio = Keystore AES-256-GCM,
   NFC(MP), salt16,            K_i = Keystore HMAC key, imported,         per-use Class 3 auth,
   64 MiB, t=3, p=4)           single use (API 31+),                      invalidated by enrolment
 wrapped = AES-GCM(KEK_pw,     StrongBox if present (API 31+),          wrapped = AES-GCM(K_bio, VK,
   VK)  (crypto.md)            unlocked-device-required (API 35+)         AAD = PVBIOSLOT1...)
                             wrapped_i = AES-GCM(t_i, VK,
                               AAD = PVPINSLOT1 ...)
 software (any phone)        HMAC inside TEE/StrongBox, GCM in software   GCM inside TEE
```

### 2.2 Master-password slot (unchanged, plus three header fields)

- **As decided.** The slot is the crypto.md section 1 slot: Argon2id v1.3, m = 65,536 KiB, t = 3, p = 4, 16-byte salt, 32-byte KEK, AES-256-GCM wrap of VK, and no stored verifier.
- **Exactly one password slot per file** (V-C4).
- **Header additions this design needs:**
  - `"vault_id"`: 16 random bytes, base64url, created with the vault and constant for its whole life.
  - `"vk_id"`: 16 random bytes, created together with each new VK.
  - `"generation"`: already required by V-C8.
- These fields are covered by the body AAD like the rest of the header. They let the lock tell, **before spending an attempt**, whether a device slot belongs to the vault generation being opened.
- **Change to crypto.md section 1.** The biometric slot moves out of the vault header into the device file. Reasons:
  - V-P6 asks for every device-bound blob to live in `noBackupFilesDir`.
  - Backups and exports already carry only the password slot (crypto.md section 10).
  - Turning fingerprint on or off no longer writes a vault generation.
  - Falling back to an older generation cannot bring back stale device slots.
  - The header is still authenticated: any PIN or fingerprint unlock decrypts the body, whose AAD covers the password slot, so crypto.md's concern 2.4 still holds.

### 2.3 PIN slot: a pool of single-use attempt keys

**Construction.** Each attempt key `i` has a random 16-byte `token_id`. The PIN's epoch is a random 16-byte id that changes whenever a new PIN is set.

```
pinMsg_i  = "PVPIN1" || 0x00 || vault_id(16) || epoch(16) || token_id_i(16) || PIN as 6 ASCII digits     (61 bytes)
t_i       = HMAC-SHA256(K_i, pinMsg_i)                        -- K_i lives only in the Keystore after provisioning
wrapped_i = AES-256-GCM(key = t_i, nonce_i (12 random bytes), plaintext = VK,
                        AAD = "PVPINSLOT1" || 0x00 || vault_id || vk_id || epoch || token_id_i)   (48 bytes)
```

- **Wrong PIN.** A wrong PIN gives a different `t_i`, so the GCM tag fails (`AEADBadTagException`).
- **No extra KDF.** `t_i` is used directly as the AES key. It is already a uniformly distributed 256-bit PRF output under a random single-purpose key, so HKDF would add nothing.
- **Test vector.** Appendix A gives a full vector, cross-checked with the JDK and Python `cryptography` [X].

**Provisioning one attempt key.** This happens at PIN setup, and in the background after each correct PIN, while VK and the typed PIN are in memory.

1. Generate `k` = 32 bytes from `SecureRandom`. Compute `t_i = HMAC-SHA256(k, pinMsg_i)` in software, then compute `wrapped_i`.
2. Import `k` with `KeyStore.setEntry(alias, SecretKeyEntry(SecretKeySpec(k, "HmacSHA256")), KeyProtection.Builder(PURPOSE_SIGN).setDigests(DIGEST_SHA256)…)`:
   - API 31+: `setMaxUsageCount(1)`.
   - API 31+ and `FEATURE_STRONGBOX_KEYSTORE`: `setIsStrongBoxBacked(true)`. On `StrongBoxUnavailableException`, or any other StrongBox import failure, retry in the TEE and record `level = "tee"`.
   - API 35+: `setUnlockedDeviceRequired(true)`.
   - API levels: `KeyProtection.Builder.setIsStrongBoxBacked` is **API 31**, unlike `KeyGenParameterSpec`'s API 28; `setMaxUsageCount` is 31; `setUnlockedDeviceRequired` is 28, but only used from 35 [C: api-versions.xml, run today].
3. Zero `k` and `t_i`. The copy inside `SecretKeySpec` cannot be wiped; never call `destroy()` (V-C10).
4. Hardware check on every imported key, read through `SecretKeyFactory.getInstance("HmacSHA256", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)`:
   - API 31+: `KeyInfo.getSecurityLevel()` must be `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, `SECURITY_LEVEL_STRONGBOX` or `SECURITY_LEVEL_UNKNOWN_SECURE`.
   - API 24 to 30: `isInsideSecureHardware()` must be true.
   - Otherwise delete the key and report "this phone cannot protect a PIN". Debug builds can override this check for the emulator, whose Keystore is software: `securityLevel=0` [L: `LAB/biometric/logs/bootB_PVBIO_both_libs.log`]. A unit test fails the release build if the override is on.
5. Alias: `"pv.pin." + base64url(epoch) + "." + base64url(token_id)`.

**Why this shape.** Each item is a fact a decision hinges on, re-checked today.

1. **Only a usage-limited key gives a hardware limit.** Apps cannot reach Gatekeeper or Weaver, and KeyMint's rate-limit tags have no SDK setter [C: FACTS 2.3, 1.1].
   - A usage count above 1 is "not expected [to be enforced by] any TEE … must be added to the key characteristics with SecurityLevel::KEYSTORE" [C: FACTS 1.2, `Tag.aidl:313-340`].
   - A count of 1 is KeyMint-enforced when secure storage exists. The reference TA keeps `UsageCountLimit(1)` in the KeyMint characteristics only with `SecureStorage::Available`, for imported keys too (`import_key` passes `secure_storage_available()`) [C: `FACTS/aosp/km_tag.rs:365-376`, `km_keys.rs:326-340`]. It deletes the secure-deletion slot after a successful use (`km_operation.rs:361-372`).
   - Hence one key per guess.
2. **HMAC, not decryption.**
   - A failed AES-GCM decrypt does not decrement the count, and KeyMint may release plaintext from `update()`.
   - HMAC returns nothing before `finish()`; Android's HMAC SPI even throws "Update operation unexpectedly produced output" [C: FACTS 1.2; `FACTS/aosp/AndroidKeyStoreHmacSpi.java:209-224`].
   - keystore2 runs `after_finish()`, the decrement, **before** returning the output, and returns no output if the decrement fails [C: `FACTS/aosp/operation.rs:426-463`].
   - So every PIN evaluation spends a use, and no evaluation happens without one.
3. **Imported, not generated.** To build `wrapped_i` in advance we need `HMAC(K_i, PIN)`. Computing it with a generated single-use key would spend its only use, so the key material must exist briefly in our process. That happens only while the vault is already unlocked and VK is in memory anyway.
4. **No software stretching of the PIN.** It would cost 0.3 to 1 s per unlock, and it helps against neither attacker:
   - The copier cannot test a PIN at all.
   - The rooted attacker is limited by the secure hardware and can precompute any software step on a GPU.
   - After a TEE key extraction, a sub-second KDF over 10^6 PINs falls in minutes.
5. **Iterating several secure-hardware operations per guess** (k operations: k times slower for root, k times slower for the user) was rejected: at most 5 times gain against A3, and it would push StrongBox unlocks past one second.

### 2.4 Fingerprint slot

| Item | Value |
|---|---|
| Key | `KeyGenParameterSpec.Builder("pv.bio." + slot_id, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`, `setKeySize(256)`, GCM, no padding, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)` (V-C7). API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`. API 28 and 29: `setUserAuthenticationValidityDurationSeconds(-1)` (V-P10). TEE, not StrongBox: a TEE compromise can forge the auth tokens StrongBox would accept, so StrongBox adds little here [I] |
| Wrap | `cipher.init(ENCRYPT_MODE, key)` without an IV (caller IVs are rejected [L: `LAB/biometric/logs/bootA_PVBIO_lib1.1.0.log` 11:38:18]). Prompt. Then `cipher.updateAAD("PVBIOSLOT1" ‖ 0x00 ‖ vault_id ‖ vk_id ‖ slot_id)` **after** `onAuthenticationSucceeded`, on `result.cryptoObject.cipher`, because every Keystore update needs the auth token. `wrapped = doFinal(VK)` (48 bytes), `nonce = cipher.iv` |
| Prompt | `BiometricPrompt(FragmentActivity, executor, callback)` built in `onCreate` every time. `setAllowedAuthenticators(BIOMETRIC_STRONG)`. Negative button "Use PIN", or "Use master password" when the PIN is off. **`setConfirmationRequired` left at its default `true`** so Class 3 face (Pixel 8 and later) needs a tap (MASTG-TEST-0329 [C: `FACTS/web/owasp/MASTG-TEST-0329.md`]). Launch only when the activity is RESUMED: the library silently ignores `authenticate()` after `onSaveInstanceState` [L: `LAB/biometric/project/.../lift/BiometricVaultKey.kt:219-231`] |
| Hardware check | Same `KeyInfo` rule as the attempt keys. Refuse fingerprint on a software Keystore |
| API range | androidx.biometric **1.1.0**. On API 24 to 27 it reports `BIOMETRIC_ERROR_NO_HARDWARE` and `authenticate` fails at once, because androidx.core 1.18+ turned `FingerprintManagerCompat` into a no-op [L: `LAB/old-android/logs/bio-api24-A-appcompat.txt`; V-P1]. On API 28 the prompt with a `CryptoObject` works, although `canAuthenticate` says `STATUS_UNKNOWN` (-1) [L: `bio-api28-A-platform.txt`]. So the app **offers fingerprint on API 28+** and treats `STATUS_UNKNOWN` as "offer it, let authentication decide" (V-P1). The 1.4 alpha is **not necessary**: the PIN gives a banking-style unlock on API 24 to 27, and the alpha's context-based API can open its fingerprint activity outside our task without `FLAG_SECURE` (V-P1) |

### 2.5 What runs where

| Computation | Where |
|---|---|
| Argon2id for the MP | App process, native argon2kt with Bouncy Castle fallback (V-C1, V-C2) |
| `HMAC(K_i, pinMsg)` at unlock | Inside the TEE or StrongBox; `K_i` cannot be exported |
| `HMAC(k, pinMsg)` at provisioning | App process, once, while unlocked |
| GCM unwrap of an attempt-key blob | App process (Conscrypt) |
| Single-use enforcement | Secure hardware if the phone declares `FEATURE_KEYSTORE_SINGLE_USE_KEY`. Otherwise keystore2 (OS software) on API 31+, and only our app's `deleteEntry` on API 24 to 30 |
| Unlocked-device check (API 35+) | keystore2: the key blob is super-encrypted with a key that is wiped from memory when the phone locks, unless a weak unlock method is enabled [C: `FACTS/aosp/super_key.rs:107-123, 884-958`]. The lab saw `KeyStoreException: Device locked (-72)` from such a key while the emulator was locked [L: `bootA_PVBIO_lib1.1.0.log` 11:46:41] |
| GCM with `K_bio` and the biometric auth token check | Inside the TEE |
| Attempt counters and limits | App (device file), backed by the spent keys in the Keystore |

### 2.6 What one PIN guess costs

| Situation | Cost of one guess | Total guesses | Basis |
|---|---|---|---|
| **A1** copy of every file the app stores, no secure hardware | Impossible: testing a PIN needs `K_i`, a 256-bit key inside the Keystore | 0. The attacker must attack the MP instead: about 56 Argon2id guesses/s on a 16-thread desktop CPU, about 1,700/s per RTX 4090 | [X] FACTS 3; [S2] crypto.md 2.9 |
| **A2** thief, no root | One attempt key, spent before the result is known | 5 in a row; 10 between MP unlocks. With 10 guesses an attacker finds 5.7 % of unrestricted 6-digit PINs; the blocklist removes the top 100 entirely and 90.3 % of the top 2,000, so 5 guesses do worse [I] | [C] FACTS 6.1; [X] section 4 |
| **A3a** rooted later, Android 15+, phone locked, no weak unlock | Keystore refuses the keys until the phone's own lock screen is passed. That credential is throttled by Gatekeeper or Weaver: on Android 17's reference schedule, no more tries after 20 failures | 0 while locked | [C] FACTS 2.2, `super_key.rs`; [L] DEVICE_LOCKED |
| **A3b** rooted later, phone declares `FEATURE_KEYSTORE_SINGLE_USE_KEY` | One key, deleted by the secure hardware's secure-deletion slot | The keys left: at most 5 | [C] FACTS 1.2 |
| **A3c** rooted later, API 31+, no hardware single-use (the Pixel and Cuttlefish device makefiles declare neither feature [C: FACTS 1.2]) | First defeat keystore2's count by patching keystore2 or rolling back `persistent.sqlite`; then one HMAC per guess, about 11 to 34 ms (TEE) or 12 to 110 ms (StrongBox), so about 30 to 90 guesses/s (TEE) or 9 to 80/s (StrongBox) | Unlimited: 910,180 allowed PINs in about 3 to 8.5 h (TEE) or 3 to 28 h (StrongBox) | [I] from DroidCCT AES p90 timings, FACTS 3 |
| **A3d** rooted later, API 24 to 30 | One HMAC per guess | As A3c, without the keystore2 step | [I] |
| **A3e** TEE key extraction (not StrongBox) | About a microsecond offline | Whole PIN space in seconds | [I]; out of scope |
| A2 who burned the 5 tries, then roots later | The keys are already deleted | 0, unless deleted keystore2 rows can be recovered from flash on phones without secure deletion [I, unverified] | [C] keystore2 deletes count-1 keys after use |

What the owner should be told, in the setup help (section 8.5): *"The PIN protects you from people who pick up your phone. Someone with special tools that break into Android itself may be able to guess a PIN within hours on many phones. Your master password still protects your backups and any copy of your files."*

### 2.7 Alternatives rejected

| Alternative | Why not |
|---|---|
| PIN-derived Argon2id envelope stored on disk (Bitwarden) | Offline: all 10^6 six-digit PINs in about 5 h on this desktop's CPU [X: FACTS 3]. Breaks requirement 2 |
| PIN compared as a screen gate (Proton Pass) | VK stays openable without the PIN, so root reads everything |
| One unlimited TEE HMAC key plus an app counter | Root guesses at TEE speed even on phones whose hardware could stop it, and root can roll the counter file back |
| One key with `setMaxUsageCount(n > 1)` | Always enforced in software by keystore2 [C: `Tag.aidl:313-340`]; never in hardware |
| Single-use AES-GCM decrypt as the attempt | A failed decrypt costs no use, and plaintext may leave before `finish()` [C: FACTS 1.2] |
| Binding the PIN keys to the phone's lock with a time window (`setUserAuthenticationValidityDurationSeconds`) | Fails for owners who unlock with Smart Lock or Class 2 face (no auth token), forcing system prompts on top of the PIN [I] |
| `setUnlockedDeviceRequired` before API 35 | On Android 12 to 14, unlocking with a non-strong biometric (face on many phones) does not re-authorise the key [C: `KeyGenParameterSpec.java:1801-1850`]. The PIN would fail daily for those users |
| `MAX_USES_PER_BOOT` or `ROLLBACK_RESISTANCE` through non-SDK calls | Against platform policy, untested [C: FACTS 1.1] |
| Storing the PIN, encrypted under VK, to rebuild keys without the user (Bitwarden `protectedPin`) | This user reuses secrets; the PIN is likely their card or phone PIN. It is never stored anywhere |
| An intermediate device key between PIN or fingerprint and VK, so an MP change keeps them | Either a key cycle (VK encrypts DK, DK encrypts VK) or the PIN is needed again anyway; one PIN re-entry after a rare MP change is cheaper |
| Wiping the vault or the app after N failures (Bitwarden, Proton) | Permanent data loss for a local-only vault (account-based.md finding 4; requirement 6) |
| Growing delays instead of a hard cap | The cap is already absolute. Delays only add clock state that must survive reboots |

---

## 3. Where every piece of state lives

### 3.1 The vault file (portable, `filesDir/vault/…`)

- **Contents:**
  - magic, header length and header JSON (format, version, `vault_id`, `vk_id`, `generation`, exactly one password slot, KDF parameters);
  - body nonce and body ciphertext (decision and V-C3).
- **Never stored here:** PIN material, fingerprint material, attempt counters, lock settings or reminder state.
- **Backups and exports.** An encrypted backup is a vault-format file with its own random key and one password slot, made from the MP typed at backup time (crypto.md section 10). It carries no `vk_id` of the live vault, and the device file is never exported.

### 3.2 The device file (`noBackupFilesDir/lock/device-lock.json`)

- **Why this directory.** `noBackupFilesDir` is "always excluded even if you try to include them" from cloud backup, device-to-device transfer and cross-platform transfer [C: FACTS 7.4, DAC autobackup].
- **What the file is.** One JSON object, at most 16 KiB, written by the commit routine in 3.6.
- **Encoding.** All binary fields are base64url without padding (`android.util.Base64` with `URL_SAFE|NO_PADDING|NO_WRAP`; `java.util.Base64` is API 26+, V-C12).

```json
{
  "format": "pvault-device-lock",
  "version": 1,
  "seq": 57,
  "vault_id": "AAECAwQFBgcICQoLDA0ODw",
  "pin": {
    "epoch": "ICEiIyQlJicoKSorLC0uLw",
    "vk_id": "EBESExQVFhcYGRobHB0eHw",
    "created": "2026-10-06T07:10:00Z",
    "tokens": [
      {"id": "MDEyMzQ1Njc4OTo7PD0-Pw", "level": "tee",
       "nonce": "gIGCg4SFhoeIiYqL",
       "wrapped": "wzls6cFeyYFqwG5UMvPSERN8tbd44dzGvTyXxnhfJHwKL4hUE6KZi79g4wFVVLC_"}
    ]
  },
  "pin_off": null,
  "bio": {"slot": "kJGSk5SVlpeYmZqbnJ2enw", "vk_id": "EBESExQVFhcYGRobHB0eHw",
          "nonce": "…16 chars…", "wrapped": "…64 chars…", "created": "2026-10-06T07:12:00Z"},
  "counters": {"fail_since_success": 0, "fail_since_mp": 0, "last_fail": null},
  "mp": {"last_unlock": "2026-10-06T07:00:00Z", "reminder_step": 0, "postponed_due": null},
  "reset": null,
  "temp_failures": {"pin": {"count": 0, "boots": []}, "bio": {"count": 0, "boots": []}},
  "settings": {"bg_lock_s": 60, "idle_lock_s": 300, "lock_on_screen_off": true}
}
```

| Field | Rule |
|---|---|
| `format`, `version` | Must equal `"pvault-device-lock"` and `1`. Anything else: use no device secret, show the MP, do **not** modify the file and do **not** clean up keys (a newer app version may own them) |
| `seq` | Increases by 1 on every commit. Used by tests and diagnostics only |
| `vault_id` | Must equal the vault header's. Otherwise the file belongs to another vault: device secrets are off until the user sets them again |
| `pin.tokens` | 0 to 5 entries, attempt-key order (head first). `id` 16 bytes, `nonce` 12 bytes, `wrapped` 48 bytes, `level` one of `strongbox`, `tee` |
| `pin.vk_id`, `bio.vk_id` | Each slot carries its own `vk_id`. A slot is offered only when its `vk_id` equals the header `vk_id` of the generation about to be opened. A mismatch never spends an attempt |
| `pin_off` | `null`, or `{"reason": "limit" | "keys_lost" | "user", "at": RFC 3339}`. Shown to the user after the next unlock |
| `counters` | Integers 0 to 10. `last_fail` is RFC 3339 wall time, for display only |
| `mp.reminder_step` | 0 to 4, indexing the intervals [1, 3, 7, 14, 14] days |
| `reset` | Pending master-password reset (section 6.4): `{"requested": wall time, "boot": BOOT_COUNT, "elapsed_ms": n, "waited_ms": n}` |
| `settings` | Missing or invalid values mean the strictest setting (V-P9) |
| Unknown keys, duplicate keys, trailing bytes | Reject the file: device secrets off, show the MP, rename the file to `device-lock.bad` (keep only the newest bad copy) |

### 3.3 Keystore entries

| Alias | Type | Parameters | Count |
|---|---|---|---|
| `pv.pin.<epoch>.<token_id>` | HMAC-SHA256, 256-bit, imported | `PURPOSE_SIGN`. API 31+: `setMaxUsageCount(1)`, StrongBox when present. API 35+: `setUnlockedDeviceRequired(true)`. No user authentication | 0 to 5 live |
| `pv.bio.<slot>` | AES-256-GCM, generated | See 2.4 | 0 or 1 |

**Clean-up (GC).** After any successful read of a valid device file, and after every commit that removes slots, delete every `pv.pin.*` and `pv.bio.*` alias the file does not reference.

- Never clean up when the device file is unreadable for a reason other than "does not exist".
- Never clean up while provisioning holds the lock mutex.
- Per-app key limit: 50,000 [C: FACTS 1.1]. This design holds at most 6 live keys.

### 3.4 Memory only

- VK, the typed PIN (as a `ByteArray` of ASCII digits), the typed MP and every derived key.
- The time of the last MP verification (`elapsedRealtime`) for the 5-minute re-authentication window.
- All of these are cleared on lock: arrays zeroed, references dropped, `TextFieldState.clearText()` plus `undoState.clearHistory()` (V-C11).

### 3.5 What happens to each piece of state

| Event | Vault file | Device file | Keystore |
|---|---|---|---|
| **MP change** (F7) | New VK, new `vk_id`, new password slot, new generation. Older generations are handled by storage's retention rules (V-S5) | The PIN slot is re-provisioned for the new VK after the user types a PIN twice (it may be the same PIN). The fingerprint slot is re-wrapped with one touch. Until then the old slots carry the old `vk_id` and are not offered | Old attempt keys deleted after the commit; the fingerprint key is kept and re-used |
| **Older generation opened** (damage fallback) | Same `vk_id`: PIN and fingerprint work. Different `vk_id` (it predates an MP change): only the old MP opens it | Untouched; mismatched slots are hidden | Untouched |
| **Export** (plaintext or encrypted backup) | Read only | Never exported | Not involved |
| **Restore a backup on this phone** | New working vault, new VK and `vk_id` (re-keyed, so the live vault does not share the backup's key) | Slots mismatch, so the MP is required; then setup offers PIN and fingerprint again | Old keys cleaned up after the new slots commit |
| **Restore on a new phone** | Same as above | Does not exist there (excluded from every transfer). Created at PIN setup | New keys |
| **Device-to-device transfer, cloud restore** | The vault file arrives only if the backup rules include it | Never transferred | Keystore keys never transfer [C: platform.md 3.4] |
| **Uninstall, "Clear storage", factory reset** | Deleted with the app's data (backups outside the app survive) | Deleted | Deleted [C: platform.md 4] |
| **Phone's screen lock removed** | — | `bio` removed when its key is reported gone | `K_bio` deleted by the system (seen: `getKey` returned null after the screen lock was removed [L: `bootB_PVBIO_both_libs.log` 12:01:10]). Attempt keys have no user authentication and survive; on 15+ unlocked-device-required keys survive too [C: `KeyGenParameterSpec.java` javadoc, bug fixed in 15] |
| **Fingerprint added or removed** | — | `bio` removed at the next use | `K_bio` throws `KeyPermanentlyInvalidatedException` [L: `bootA` 11:48:17] |
| **OS update** | — | — | Keys are expected to survive. If they do not, the error rules apply (section 10) |

### 3.6 Commit routine for the device file

The storage.md section 5.2 routine, adapted. Replacing the old file is intended here, because the file is state, not data.

1. Write `device-lock.json.tmp` and `fsync` it.
2. `Os.rename(tmp, "device-lock.json")`, an atomic replace.
3. `Os.open(dir, O_RDONLY)`, then `Os.fsync` and `Os.close`.
4. **Any failure at any step throws**, including the directory fsync (V-S2). The caller treats the commit as not done.
5. Never use `AtomicFile` or DataStore (V-S7).
6. All device-file access happens under one process-wide `Mutex` (the lock mutex).

---

## 4. PIN rules

1. **Length.** Exactly 6 digits, ASCII `0` to `9`, typed only on the app's own pad, so there are no locale digits, keyboard or autofill.
   - Six follows NIST SP 800-63B-4 "SHOULD … at least six", CDD 17 C-SR-6, and Mandiant's advice to Bitwarden [C: FACTS 6.3].
   - With a fixed length the pad submits after the sixth digit, which CDD C-SR-7 allows only for PINs of 6 or more [C].
   - The length is the same for everyone, so nothing is revealed.
2. **Blocklist.** A PIN is rejected when any rule matches. The rules are our own, so there is no licence question; the published DD lists have no stated licence [C: FACTS 6.2] and are used only to measure coverage. The rules and their user messages:
   - "Too easy: the same digit repeated": five or six digits are the same.
   - "Too easy: digits in a row": the six digits form an arithmetic sequence (mod 10), such as 123456, 654321, 135791 or 246802; or the first five or the last five step by +1 or -1 (123457, 012345).
   - "Too easy: a repeated pattern": ababab, abcabc, aabbcc, aaabbb, abccba.
   - "Too easy: counting": three two-digit numbers counting by 1, 2 or 10 (131415, 102030).
   - "Looks like a date. Don't use birthdays or other dates": DDMMYY, MMDDYY, YYMMDD for any year 00 to 99 with a valid day for the month; YYYYMM and MMYYYY for years 1930 to 2039.
   - "Too easy: a shape on the keypad": two straight lines of three keys on the phone keypad (rows, columns, diagonals, 5-8-0, either direction), plus 16 listed shapes (246810, 135790, 124578, 235689, 784512, 895623, 142536, 415263, 134679, 794613, 159632, 014789, 147896, 172839, 135246, 787898).
3. **Measured coverage** [X: `python3 D1/pinrules.py DD-6-digit-291000.txt iOS-6-digit.txt`; evaluation only]:
   - 89,820 of the 10^6 PINs are blocked (8.98 %).
   - Coverage of the DD six-digit list: 100 % of its top 100, 92.1 % of its top 1,000 and 90.3 % of its top 2,000.
   - Coverage of Apple's iOS six-digit list (2,910 PINs): 75.6 %.
   - Without the date rule, the other rules block only 4,804 PINs. Dates are what targeted guessers (A4) try first, and the most popular choice in studies [C: TOPS 2021, line 137 of `FACTS/web/tops21-6-pin-security.txt`].
   - Markert et al. recommend about 2,000 for untargeted attackers and found that very large lists frustrate users [C: FACTS 6.1]. Blocking dates is a deliberate choice against A4. If the user finds it frustrating, drop the date rule (open question 7).
4. **How the user is told.** The screen says "Choose a 6-digit PIN" with the subtitle "Not your birthday, not your phone's or bank card's PIN." A blocked PIN is rejected on the first entry, before confirmation, with the rule's message, and the dots clear. A mismatch at confirmation says "The PINs don't match. Try again." Help text: "Your PIN opens the vault on this phone only. After 5 wrong tries it turns off and you'll need your master password."
5. Not checked, because we cannot: whether the PIN equals the phone's or a bank card's PIN. The subtitle asks the user to avoid both, because a PIN known from another context defeats the limit (Munyendo et al. [C: FACTS 6.1]).

---

## 5. The attempt protocol

### 5.1 Constants

| Name | Value | Why |
|---|---|---|
| `POOL_MAX` | 5 | PSD2 caps consecutive failures at 5 [C: FACTS 4]; NIST allows at most 10 [C] |
| `FAIL_BUDGET` | 10 | Total wrong PINs between two MP unlocks; bounds A4 (NIST's 10) |
| `MP_REAUTH_WINDOW` | 5 min of `elapsedRealtime` within one unlocked session | Avoids re-asking during a settings session |
| `REMINDER_DAYS` | [1, 3, 7, 14] then every 14 | Section 6.3 |
| `RESET_WAIT` | 72 h of accumulated uptime | Section 6.4 |
| Pause after a wrong PIN | 1 s, UI only | Lets the user read the message; not a security control |

### 5.2 One attempt, exactly

The ViewModel holds the digits as a `ByteArray`, never a `String` or saved state. After the sixth digit the pad is disabled, a progress indicator shows, and `attemptPin(pin)` runs on a background dispatcher.

```kotlin
suspend fun attemptPin(pin: ByteArray): PinResult = lockMutex.withLock {
    val header = vault.peekNewestHeader()               // vault_id, vk_id, read without keys
    var s = deviceFile.read() ?: return Unavailable(NO_DEVICE_STATE)
    val slot = s.pin ?: return Unavailable(PIN_OFF)
    if (s.vaultId != header.vaultId || slot.vkId != header.vkId) return Unavailable(SLOT_MISMATCH)
    while (true) {
        val token = s.pin!!.tokens.first()                     // lockOut() never leaves an empty list
        // (1) Load the key and begin the HMAC operation. Nothing about the PIN is evaluated yet.
        val mac = when (val r = keystore.beginHmac(alias(slot, token))) {
            is Ok -> r.value
            is Gone -> {                                       // not an attempt: nothing was evaluated
                s = s.removeToken(token)
                if (s.pin!!.tokens.isEmpty()) return lockOut(s, reason = "keys_lost")
                deviceFile.commit(s); continue
            }
            is Temporary -> return KeystoreUnavailable(r)      // nothing changed
        }
        // (2) Count the attempt durably BEFORE evaluating it (Gatekeeper's order).
        val counted = s.removeToken(token).countFailure(nowWall())
        try { deviceFile.commit(counted) } catch (e: IOException) { mac.abort(); return StorageError }
        // (3) Evaluate: one HMAC inside the secure hardware. On API 31+ this spends the key.
        val t = when (val r = keystore.finishHmac(mac, pinMsg(s.vaultId, slot, token, pin))) {
            is Ok -> r.value
            else -> {                                          // no output was produced
                runCatching { deviceFile.commit(counted.restoreToken(token).uncountFailure()) }
                return KeystoreUnavailable(r)
            }
        }
        keystore.deleteQuietly(alias(slot, token))             // needed on API 24-30; harmless later
        return try {
            Success(gcmOpen(key = t, token.nonce, token.wrapped, aad(s.vaultId, slot, token)), counted)
        } catch (e: AEADBadTagException) {
            if (counted.pinTokensLeft == 0 || counted.failSinceMp >= FAIL_BUDGET) lockOut(counted, "limit")
            else Wrong(attemptsLeft = counted.pinTokensLeft)
        } finally { t.fill(0) }
    }
}
```

**Commit points, and what a kill or power cut at each point does:**

| Interrupted between | On disk afterwards | Effect |
|---|---|---|
| Typing and step 1 | Nothing new | No attempt was made |
| Step 1 (operation begun) and the step 2 commit | Old state; the attempt key is unused | keystore2 aborts the operation when the client dies; no output means no evaluation [C: Keystore operations decrement only after a successful `finish()`, FACTS 1.2] |
| Step 2 commit and step 3 | Attempt counted, key removed from the list but still in the Keystore | The user loses one attempt without an evaluation (conservative); GC deletes the orphan key |
| After step 3 | Attempt counted, key spent | Evaluated and counted |
| Steps 1 to 3 with a temporary Keystore error | Counted, then restored | Allowed because no HMAC output existed. On API 31+ a key whose output was lost in transit is already spent; restoring the list entry is harmless because the next use finds it gone |

The first three cases follow the Gatekeeper rule "write a failure counter before verifying" [C: SAC Gatekeeper, `gatekeeper.cpp:119-176`].

### 5.3 After a correct PIN

1. Open the vault with VK. The body AEAD verifies the header.
   - If the body fails but the attempt key opened, the vault file is damaged, which is a storage problem, not a PIN problem. Storage tries older generations with the same `vk_id` (storage.md 5.4 and V-S3). The PIN still counts as correct.
2. Show the vault. The target is the 95th percentile under 500 ms from the sixth digit on a mid-range phone [I]. The parts are:
   - one device-file commit (5 to 50 ms);
   - getting the key plus one secure-hardware HMAC (10 to 110 ms, from DroidCCT's AES p90 numbers);
   - reading, decrypting and parsing the vault body (50 to 150 ms at 1,000 entries; storage.md 4 measured 18 ms on a desktop).
   The newest generation's bytes are prefetched when the lock screen appears.
3. In a non-cancellable background job holding the lock mutex, at most 10 s:
   - a. Commit the success: undo this attempt's provisional increments, set `fail_since_success = 0` (keep the old value for the alert), and update `last_fail`.
   - b. Compute `target = min(POOL_MAX, FAIL_BUDGET − fail_since_mp)`.
   - c. Provision `target − tokens.size` new attempt keys under the same epoch (2.3).
   - d. Commit the new list.
   - e. GC aliases.
   - f. Zero the job's copies of the PIN and VK.
   Locking the app while this runs does not cancel it, so the pool cannot shrink with every quick unlock. A key that is imported but not committed when the process dies becomes an orphan and is cleaned up. The next correct PIN refills the pool to its target.
4. If the previous `fail_since_success` was above 0, show after unlocking: "3 wrong PIN attempts since your last unlock (last: today 14:32). If that wasn't you, someone may have tried to open your vault."

### 5.4 Limits and what happens at them

- The number of attempt keys **is** the number of attempts left. Because of the target formula in 5.3, it never exceeds `FAIL_BUDGET − fail_since_mp`.
- Shown to the user:
  - "N attempts left" when fewer than 5 remain after a failure;
  - always when 2 or fewer remain;
  - before the last one: "Last attempt. If this PIN is wrong, PIN unlock turns off and you'll need your master password."
- **At the limit** (no key left, or `fail_since_mp` reaches 10), `lockOut()` does the following:
  1. Commit `pin = null` and `pin_off = {reason: "limit", at: now}`.
  2. Delete any remaining attempt-key aliases.
  3. Show "Too many wrong PINs. PIN unlock is off. Nothing was deleted. Use your fingerprint or master password."
- **Fingerprint stays available.** It has its own hardware lockout (Class 3: a 30 s lockout after 5 false tries is strongly recommended, and fallback to the primary credential is required after at most 20 [C: CDD 17 7.3.10]). Turning it off too would push an owner who has forgotten the MP into data loss.
- Our PIN is not the phone's primary credential, so CDD's rule to disable biometrics once the primary credential's back-off starts does not apply. I chose the data-safe reading [I].
- Setting a PIN again needs the MP (section 6.1), or the reset in 6.4.

### 5.5 What resets what

| Event | Attempt keys | `fail_since_success` | `fail_since_mp` |
|---|---|---|---|
| Wrong PIN | −1 (spent) | +1 | +1 |
| Correct PIN | Refilled to `min(5, 10 − fail_since_mp)` | Reported, then 0 | Unchanged |
| Fingerprint unlock | Unchanged; refilling needs the PIN | Reported, then 0 | Unchanged |
| MP unlock or MP re-authentication | Unchanged; the next correct PIN refills to 5 | Reported, then 0 | **0** |
| New PIN set | New epoch, 5 keys | Unchanged | Unchanged (it was just reset by the MP the user typed) |
| Reboot, kill, clock change, cache clear | Unchanged | Unchanged | Unchanged |

### 5.6 Why no delays

Android's lock screen needs delays because its attempts are unlimited over time [C: FACTS 2.2]. Here the total is capped by spent keys, so a delay would only slow a child pressing buttons, while adding a clock and reboot state machine that requirement 3 would then have to defend. The 1-second pause after a wrong PIN is a UI detail.

### 5.7 Requirement 3, case by case

| Trick | Why it gives nothing |
|---|---|
| Kill or force-stop during an attempt | 5.2 table |
| Cut power | Same as kill. Residual risk [I]: f2fs mounted with `fsync_mode=nobarrier` (V-S8) can lose an fsynced write if power is cut within milliseconds. On phones without hardware secure deletion, keystore2's database write can be lost the same way, which would give back one key. That needs precise power cutting on a sealed phone (IP-Box needed about 40 s per guess on an iPhone [C: FACTS 7.1]) |
| Reboot | Counters live on disk; spent keys stay spent |
| Change the clock | No limit depends on time. The reminder only becomes due sooner (6.3); the reset wait uses uptime (6.4) |
| Clear cache | Nothing is stored in `cacheDir` |
| "Clear storage" or reinstall | Deletes the attempt keys, the device file and the vault: no guesses, no data. It is not a bypass |
| Restore an older device file (needs root) | The keys it lists are spent, so they are gone (A3) |
| Second app instance or a double tap | One `singleTask` activity, the pad is disabled after the 6th digit, and the lock mutex serialises |
| `adb shell input` scripting | Still 5 |

---

## 6. When the master password is required

### 6.1 The list

| Situation | Required? | Risk it addresses | Annoyance |
|---|---|---|---|
| First setup | Yes: create, type twice (V-C5) | — | Once |
| PIN at its limit | Yes, or fingerprint | A2 and A4 guessing | Rare for the owner |
| PIN keys lost or invalid | Yes, or fingerprint | — | Rare |
| Slot does not match the vault (restored backup, older generation, MP changed) | Yes | A stale slot would open nothing | Rare |
| Keystore temporarily failing | MP this time; the PIN is kept | — | Rare; "restart the phone" hint |
| Spaced reminder due (6.3) | Yes, with one "Not now" per due date | Requirement 7: forgetting | About twice a month after the first weeks |
| After a phone reboot | **No** | No cryptographic effect: the keys survive reboots, and Android has no per-boot key an app can use [C: FACTS 1.1] | Would be daily on phones that restart themselves on a schedule [I] |
| After an app or OS update | No (unless keys are lost) | — | — |
| Plaintext export (CSV, JSON, XLSX) | **Yes, typed now; no window** | A5 bulk theft; a seen PIN or a sleeping owner's finger must not allow it (MASWE-0021 [C]) | Rare |
| Encrypted backup | Yes, typed now, verified against the vault; it becomes the backup's password (requirement 1) | — | Rare; good practice |
| Restore a backup | The backup's MP | — | Rare |
| Set or change PIN, turn on fingerprint, lengthen the background or idle lock, turn off lock-on-screen-off | Yes (5-minute window allowed) | A5 or a borrower adding their own way in | Rare |
| Change MP | Current MP typed now | Proves knowledge | Rare |
| Turn off PIN or fingerprint, shorten timeouts, lock now | **No** | Making the lock stricter must be easy, for example under threat | — |
| Show or copy one password | No | — | Daily use |

### 6.2 Re-authentication window

- A correct MP typed in the current unlocked session sets `mpVerifiedAt = elapsedRealtime()`.
- Actions marked "window allowed" skip the prompt for 5 minutes.
- Locking clears the window.

### 6.3 The reminder (requirement 7)

- **When it is due.**
  - Due when `now ≥ mp.last_unlock + REMINDER_DAYS[step]`.
  - Also due when the wall clock is more than 1 h **before** `mp.last_unlock`: a backwards clock change cannot postpone it.
  - A missing value means due (V-P9).
  - Wall time is fine here because an attacker gains nothing by postponing a reminder.
- **On the lock screen.** The MP field shows instead of the PIN pad and no fingerprint prompt appears automatically. Text: "Time to practise your master password. We ask now and then so you never forget it. Without it, nobody can open your vault on a new phone."
- **Correct MP.**
  - Answering a due reminder sets `step = min(step + 1, 4)` and `last_unlock = now`.
  - Any other correct MP (an unlock by choice, a re-authentication, an export) sets only `last_unlock = now`. That counts as practice and postpones the reminder, but it does not lengthen the spacing.
- **"Not now"** is offered once per due date (`postponed_due` records which). It shows the PIN pad and starts the fingerprint prompt. At the next lock screen the MP is required without "Not now".
- **Three wrong MPs** show "Forgot it? You can set a new one" (6.4) next to "Try again".
- **Wrong-MP hint**, because this user types Uzbek Latin and Russian: "That isn't your master password. Check the keyboard language (Latin or Cyrillic) and the ‘ sign in o‘ and g‘."
- **Settings line:** "Master password last typed: 3 days ago."
- **Proposed fix for the password encoding.** NFC does not unify U+2018, U+02BB, U+02BC, U+2019 and U+0027 [X: FACTS 9]. I ask the crypto owner to fold U+0027, U+0060, U+00B4, U+2018, U+2019, U+02BB and U+02BC to U+0027 before NFC, before the first release, with test vectors. Otherwise a different keyboard turns a remembered MP into a "forgotten" one (open question 5).

### 6.4 Forgot the MP but the PIN or fingerprint still works: reset after 72 hours

Without this, a forgetful owner keeps reading passwords with the PIN until the next reminder and then loses everything. With an instant reset, a seen PIN would let anyone take over the vault and export it. The waiting period handles both.

1. On the MP screen, "Forgot your master password?" opens an explanation: "You can set a new master password with your PIN and fingerprint. For your safety this takes 3 days; your passwords stay available meanwhile. Backups made with the old password still need the old password."
2. The user proves **every enabled device secret**: a normal PIN attempt (5.2) and, if fingerprint is on, a prompt. Each one unlocks normally. If neither is enabled, there is no reset: section 8.5 explains this at setup.
3. Commit `reset = {requested, boot = BOOT_COUNT, elapsed_ms = elapsedRealtime(), waited_ms = 0}`.
4. On every app start and resume while pending:
   - same boot: `waited_ms += elapsed − elapsed_ms`;
   - new boot (`BOOT_COUNT` changed): `waited_ms += elapsedRealtime()`;
   - then store the current boot and elapsed time.
   This lower bound on real time cannot be shortened by changing the date. A reboot only makes the wait longer (`Settings.Global.BOOT_COUNT`, API 24 [C: FACTS 7.3]).
5. Banner on every unlock: "A master-password reset was requested on 6 Oct. Ready after about 9 Oct. Not you? [Cancel reset]". Cancelling needs nothing, because it is the safe direction. Any correct MP cancels it too. Reminders are paused while a reset is pending.
6. When `waited_ms ≥ 72 h`, the banner offers "Set new master password". The user proves every enabled device secret again, types a new MP twice, then the MP-change steps of F7 (steps 3 to 9) run.
7. Afterwards: reminder step 0, `fail_since_mp = 0`, and an immediate offer to make a new backup.

---

## 7. The phone's own screen lock ("home lock")

**Decision: not in version 1. Open question 1 asks the user what they meant.**

**Why not:**
- "Like banking account" points to the banking pattern: an app's own PIN that the bank verifies, with biometrics as a shortcut [C: FACTS 4, PowerAuth and PSD2]. How Uzbek bank apps verify their PIN is unknown [U: FACTS 4].
- Anyone who knows the phone's PIN would open the vault. That is often family members and repair shops, the people a separate vault lock exists for (A4, A5).
- Wrong phone PINs typed for our app count towards the phone's own lockout and any "wipe after N failures" policy [C: FACTS 1.3, `CredentialInteractor.kt:75-169`].
- MASWE-0021 limits device-credential fallback to low-risk features [C].
- crypto.md section 12 already rejected it.
- Its one real advantage, hardware throttling (Gatekeeper or Weaver) against A3, is matched for locked phones on Android 15+ by the attempt keys' unlocked-device requirement. There a rooted attacker must first beat the phone's lock anyway.

**How it would work if the user asks for it.** It would be an alternative to the app PIN, never for exports or settings:

| API | Mechanism |
|---|---|
| 30 to 37 | Keystore AES-256-GCM key with `setUserAuthenticationRequired(true)` and `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)`. `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`, no negative button (they are mutually exclusive), and a `CryptoObject`. It wraps VK in the device file. The key is generated only with a secure lock screen and dies when the lock screen is removed [C: FACTS 1.3] |
| 24 to 29 | `DEVICE_CREDENTIAL` with a `CryptoObject` is unsupported [C: platform.md 3.10]. Use a time-bound key (`setUserAuthenticationValidityDurationSeconds(5)`, deprecated in 30) and launch `KeyguardManager.createConfirmDeviceCredentialIntent` (deprecated in 29) before every use. The key is also usable for 5 s after any ordinary phone unlock [C: FACTS 1.3], so against A3 it is weaker than the app PIN there |
| All | The system screen stops our activity, so `ON_STOP` fires and needs the grace rule of V-P3. On Android 16+ with Identity Check outside trusted places, device-credential fallback is dropped [C: FACTS 8] |

---

## 8. The lock screen

### 8.1 Layout (Compose, Material 3)

```
        [app icon]  PasswordVault
        Enter your PIN                   <- title (changes per state)
        ●  ●  ●  ○  ○  ○                 <- six dots, no digits ever shown
        3 attempts left                  <- message line (polite live region)
         1     2     3
         4     5     6
         7     8     9
        [fp]   0    [⌫]                  <- [fp] only when fingerprint is usable
        Forgot PIN? Use master password  <- text button
```

**Hardening (platform.md 3.1 and 3.5, V-P5):**
- `FLAG_SECURE` on the window.
- `window.setHideOverlayWindows(true)` on API 31+, with the normal-level `HIDE_OVERLAY_WINDOWS` permission.
- `filterTouchesWhenObscured` on the decor view.
- `semantics { isSensitiveData = true }` on the dots and every pad button (it takes effect on API 34+).
- No text field, so no keyboard, no autofill and no clipboard.
- The MP field is a `SecureTextField` held in `remember { TextFieldState() }`, with `ContentDataType.None`, no personalised learning, and an explicit `textObfuscationMode` (V-C11, V-P4).
- Strings are kept short for a non-native reader, with Uzbek (Latin) and Russian translations later.

### 8.2 States

| State | Shown | Leads to |
|---|---|---|
| `Choosing` (on entry) | Nothing yet: the app reads the device file and the newest vault header, and prefetches the vault bytes | One of the states below |
| `PinPad(n, attemptsLeft, msg?)` | Pad; fingerprint button if usable | `Verifying` on the 6th digit; `FingerprintPrompt`; `MasterPassword(CHOICE)` |
| `FingerprintPrompt` | System prompt over the pad; negative "Use PIN" | `Unlocked`, or back to `PinPad` with a message |
| `Verifying` | Pad disabled, spinner | `Unlocked`, `WrongPin`, `PinOff`, `KeystoreUnavailable`, `StorageError` |
| `WrongPin(left)` | Shake, haptic, "Wrong PIN. N attempts left." for 1 s | `PinPad` |
| `PinOff(reason)` | "Too many wrong PINs. PIN unlock is off. Nothing was deleted." or "PIN unlock stopped working on this phone." | `FingerprintPrompt` if usable, else `MasterPassword` |
| `MasterPassword(reason)` | Field, show/hide, "Unlock". Reasons: CHOICE, REMINDER (with "Not now" once), PIN_OFF, NO_DEVICE_SECRET, SLOT_MISMATCH, KEYSTORE_UNAVAILABLE, STORAGE | `DerivingKey` |
| `DerivingKey` | "Opening…" during Argon2id (about 0.3 to 1 s, unmeasured on phones [C: crypto.md "Not settled"]) | `Unlocked`, or `MasterPassword` with "That isn't your master password…" |
| `KeystoreUnavailable` | "Your phone's security chip did not answer. Try again, or use your master password. Restarting the phone usually fixes this." | `PinPad` (retry) or `MasterPassword` |
| `StorageError` | "Your phone's storage is full or failing, so the PIN can't be checked safely. Free some space or use your master password." | `MasterPassword` |
| `Unlocked` | Vault, plus any one-time notices (failed attempts, slot removed, PIN off, reset pending) | — |

**Choosing, in order:**
1. No vault: onboarding.
2. A reset is pending and ready: banner after unlock, not here.
3. Reminder due: `MasterPassword(REMINDER)`.
4. PIN slot usable (`vault_id` and `vk_id` match, keys > 0): `PinPad`. If fingerprint is also usable, start the prompt **once** per lock-screen appearance, only when RESUMED and focused, and not again in this appearance after the user cancels or the sensor reports lockout. Codes 7 and 9 also leave a message [L: lab saw 7 `ERROR_LOCKOUT`, and 10 when the phone locked itself after five wrong fingers].
5. Only fingerprint usable: the prompt, with negative "Use master password".
6. Otherwise: `MasterPassword(NO_DEVICE_SECRET or reason)`.

### 8.3 Auto-lock

| Setting | Default | Options | Lengthening needs MP |
|---|---|---|---|
| Lock after leaving the app | 1 min | immediately, 30 s, 1 min, 5 min, 15 min | yes |
| Lock after no touch while open | 5 min | 1, 2, 5, 10 min | yes |
| Lock when the screen turns off | on | on, off | turning off: yes |
| Lock at process start | always | — | — |

- **Mechanics (decided):** `ProcessLifecycleOwner` `ON_STOP` stamps `elapsedRealtime`, and `ON_START` compares.
- **Screen off:** an `ACTION_SCREEN_OFF` receiver registered while the process lives. It locks at once and is delivered promptly while our app is on screen, which is the case that matters.
- **Idle:** `Activity.onUserInteraction` resets a foreground idle timer.
- **Our own system screens:** while a file picker or share sheet we launched is open, the background rule is suspended for up to 10 minutes (V-P3). Screen-off still locks.
- **Locking:**
  1. Wipe VK and all session keys.
  2. Cancel any prompt.
  3. Clear `mpVerifiedAt`.
  4. Show `Choosing`.
  The PIN refill job (5.3) keeps its own copies and finishes.
- **Best effort:** VK is also wiped by an in-process timer at the background timeout if the process is still running. The decision at `ON_START` remains the guarantee [C: platform.md 3.3].

### 8.4 Notices after unlocking

One each, dismissible:
- failed attempts since the last unlock (5.3);
- "Fingerprints on this phone changed, so fingerprint unlock was turned off. Turn it on again in Settings.";
- "PIN unlock turned off after 5 wrong attempts. Set a PIN again.";
- "Your PIN was set for another copy of your vault. Set it again.";
- the reset banner (6.4);
- after the 3rd temporary failure of the same secret across 2 or more boots: "Fingerprint unlock keeps failing on this phone. Turn it off?" Only the user removes it.

### 8.5 What setup tells the user (plain words)

- "Your passwords stay only on this phone. No account. If you lose the phone and have no backup, they are gone."
- "Your master password is the only key that works everywhere. Nobody can reset it. Write it down and keep the paper at home."
- "The PIN opens the vault on this phone only, like your banking app. After 5 wrong tries it turns off and you'll need the master password."
- "Anyone whose fingerprint is saved in this phone's settings can open your vault."
- The 2.6 sentence about special tools that break into Android.

---

## 9. Flows

The flow names refer to the states in 8.2. "Commit" always means section 3.6.

### F1. First setup
1. No vault exists. Delete any stale device file and clean up `pv.*` aliases.
2. Welcome text (8.5). The user taps "Create my vault".
3. Create the MP: offer a generated five-word passphrase (EFF list, credited, V-C13) or the user's own. Type twice. Reject unassigned code points (V-C9). Show the folding note for ‘ (6.3).
4. Recovery sheet: show the MP, with the checkbox "I wrote it down".
5. Create the vault: VK, `vault_id`, `vk_id`, generation 1, password slot. Commit, read it back from disk, and unlock it through the full MP path with the typed MP (V-C5). Record `mp.last_unlock = now` and `reminder_step = 0`.
6. PIN: "Open your vault with a 6-digit PIN, like a banking app." [Set PIN] or [Not now].
   - a. Enter the PIN. Run the rules (4.2). Confirm it.
   - b. Provision 5 attempt keys with progress text "Protecting your PIN with your phone's security chip…". This takes 5 imports, about 0.25 to 0.5 s if an import costs what key generation does (43 to 93 ms p90 [C: FACTS 3, DroidCCT]), and up to several seconds on some phones [S2: FACTS 3, AndroidAPS #49].
   - c. If the hardware check fails, delete the keys and say: "This phone can't protect a PIN safely. You'll use your master password." Then go to step 8.
   - d. Commit the device file. Zero the PIN.
7. Fingerprint, offered when API ≥ 28, `KeyguardManager.isDeviceSecure()`, and `canAuthenticate(BIOMETRIC_STRONG)` returns `SUCCESS` or `STATUS_UNKNOWN`. Run F8-on. The MP was verified in step 5, inside the window.
8. Backup: "Make your first backup now?" Run F11b; the user types the MP again, which is good practice.
9. The vault opens (session opened by the MP).

### F2. Daily unlock with the PIN
1. The app comes to the foreground and the lock decision (8.3) says locked: `Choosing`, then `PinPad`.
2. The fingerprint prompt may start (F3). To type the PIN instead, the user taps the prompt's "Use PIN" button or Back (error 13 or 10, section 10). The pad underneath is then live.
3. Six digits, then `attemptPin` (5.2).
4. Success: the vault shows (5.3 step 2). The background job runs (5.3 step 3). A notice appears if there were earlier failures.

### F3. Daily unlock with fingerprint
1. `Choosing` finds the `bio` slot with a matching `vk_id` and the app RESUMED.
2. `getKey("pv.bio.<slot>")`, then `Cipher.init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))`. Errors are classified (section 10).
3. `authenticate(promptInfo, CryptoObject(cipher))`.
4. `onAuthenticationSucceeded`: take `result.cryptoObject.cipher` (never our own reference), `updateAAD(aad)`, `doFinal(wrapped)`. The result must be 32 bytes.
5. Open the vault. Report and reset `fail_since_success`. Attempt keys are not touched.
6. Prompt errors (section 10): back to the pad with a short message. Nothing is changed.

### F4. Wrong PIN
1. In 5.2 step 3 the tag fails. The attempt was committed in step 2.
2. `WrongPin(left)`: shake, haptic, message, and a 1 s pause, then `PinPad`.
3. When 1 key is left, the next pad shows the last-attempt warning (5.4).

### F5. Lockout
1. The last key is spent wrong, or `fail_since_mp` reaches 10: `lockOut()` (5.4).
2. `PinOff(limit)`, then the fingerprint prompt if usable, otherwise `MasterPassword(PIN_OFF)`.
3. After any unlock, a notice offers "Set a PIN again". F6 asks for the MP; if the session was opened by the MP within the window it goes straight on.

### F6. Set or change PIN
1. Settings → Lock → "Change PIN" (or "Set PIN").
2. MP re-authentication unless inside the window.
3. New PIN → rules → confirm.
4. Provision 5 keys under a **new epoch**. Commit `pin` (new) and `pin_off = null`. Clean up the old epoch's aliases. Zero the PIN.

### F7. Change master password
1. Settings → "Change master password". Type the current MP now (no window). Argon2id unwraps VK_old; if wrong, "That isn't your master password".
2. New MP twice, with the 6.3 hints.
3. Create VK_new and `vk_id_new`, a new salt and password slot. Seal the entries under VK_new and commit a new generation. Read it back from disk and unlock it through the full MP path with the re-typed new MP (V-C5). If this fails, nothing else changes: the old generation and the device file still open the vault.
4. "Keep your PIN": the user types a PIN twice (the same PIN is fine). Provision 5 keys for VK_new under a new epoch and commit `pin` with `vk_id_new`. If skipped, the PIN is off until set (6.1).
5. "Keep fingerprint unlock": `Cipher.init(ENCRYPT)` on the existing `K_bio` (create a new key if it is gone), one touch, `updateAAD` with `vk_id_new`, `doFinal(VK_new)`. Commit `bio` with `vk_id_new`. If skipped, fingerprint is off.
6. Clean up the old epoch's attempt keys after the commit in step 4.
7. Set `mp.last_unlock = now`, `reminder_step = 0` and `fail_since_mp = 0`.
8. Offer a new backup: "Old backups still open only with the old master password."
9. Older generations under the old password are retired by storage once the new password has opened the vault from disk at a later start (V-S5).

### F8. Fingerprint on and off
**On:**
1. Preconditions: API ≥ 28, a secure screen lock, and `canAuthenticate(BIOMETRIC_STRONG)` returning `SUCCESS` or `STATUS_UNKNOWN`. Otherwise explain ("Add a fingerprint in your phone's settings first").
2. MP re-authentication unless inside the window.
3. Explain who can open the vault (8.5).
4. Generate `pv.bio.<new slot>` (2.4). If there is no enrolled biometric, it fails with `InvalidAlgorithmParameterException` ("At least one biometric must be enrolled…") [L: `bootA` 11:37:16]: explain that. Run the hardware check.
5. `Cipher.init(ENCRYPT_MODE, key)`, prompt with negative "Cancel", then `updateAAD`, `doFinal(VK)`, and `nonce = cipher.iv`.
6. Commit `bio`. On any failure or cancel, delete the new key; the old state stays.

**Off:** commit `bio = null`, then delete the alias. No MP needed.

### F9. The MP reminder
Covered in 6.3. Numbered:
1. `Choosing` finds the reminder due.
2. `MasterPassword(REMINDER)`.
3. A correct MP advances the step and unlocks.
4. "Not now" shows `PinPad` (once).
5. After three wrong MPs, the "Forgot it?" link is offered (6.4).

### F10. Forgot PIN
1. "Forgot PIN? Use master password" opens `MasterPassword(CHOICE)`.
2. The MP unlocks.
3. A notice offers "Set a new PIN" (F6, inside the window).

### F11. Export or backup
**a. Plaintext export (CSV, JSON, XLSX):**
1. Settings → Export → format.
2. Warning: "Anyone who gets this file can read all your passwords. Delete it after use."
3. The MP is typed now (always, ignoring the window) and verified by unwrapping the vault's password slot.
4. `CreateDocument` → write → read back and verify (V-S6).
5. The device file is not involved.

**b. Encrypted backup:**
1. The MP is typed now and verified against the vault (Argon2id once).
2. A fresh export key and salt; the backup's password slot uses the same MP (requirement 1).
3. Write, read back and verify (V-S6).

### F12. Restore on a new phone (or replace the vault on this phone)
1. Onboarding → "Restore from a backup" → `OpenDocument`.
2. Validate the header bounds before any KDF (V-C4, V-C14).
3. Type the backup's MP → Argon2id → unwrap → decrypt.
4. Create the working vault re-keyed with a new VK, `vk_id` and salt, under the same typed MP (3.5). Commit and verify.
5. Continue at F1 step 6 (PIN), 7 (fingerprint) and 8 (backup). Reminder step 0.

### F13. Keystore failures
**a. Key invalidated.**
- Fingerprint: `KeyPermanentlyInvalidatedException` at `Cipher.init`. Commit `bio = null` and delete the alias. Notice (8.4). Continue with the PIN or MP.
- Attempt key: discard that key without counting an attempt (5.2 step 1). If none remain, commit `pin = null` and `pin_off = keys_lost`.

**b. Key missing.**
- `getKey` returns null. On API 31+ (keystore2) this means `KEY_NOT_FOUND` [C: `D1/AndroidKeyStoreSpi.java:123-140`, `AndroidKeyStoreProvider.java:431-450`]: treat it as gone.
- On API 24 to 30, null also comes back when the keystore daemon is unreachable, because `contains()` returns false on `RemoteException` [C: `D1/KeyStore_a11.java:290-297`, `AndroidKeyStoreSpi_a11.java:95-112`].
- So on those versions "gone" needs **corroboration**: another of our keys loads in the same call, or the same key is missing on a later boot (`BOOT_COUNT` changed).
- Until corroborated it is temporary: hide the secret for this session and change nothing. This refines V-C6, whose rule holds as written only for keystore2.

**c. Keystore temporarily failing.**
- Any other exception (section 10): change nothing. For an attempt, restore the key and undo the count (5.2).
- Show `KeystoreUnavailable` with the MP offered. Increment `temp_failures` with the boot count.
- After 3 or more such failures across 2 or more boots, offer the user the choice to turn that secret off (8.4).
- A Xiaomi that failed every Keystore call until reboot [C: crypto.md 7] loses nothing.

### F14. Lock events
1. `ON_STOP`: stamp `elapsedRealtime`. Start the best-effort wipe timer.
2. `ON_START`: if `elapsed − stamp ≥ background timeout`, a screen-off was seen, or the process is new, lock (wipe, `Choosing`).
3. Idle timer expiry in the foreground: lock.
4. "Lock now" in the toolbar: lock.
5. Process start: always `Choosing`.

### F15. Device events
| Event | Flow |
|---|---|
| Fingerprint enrolled or removed | F13a for `bio` |
| Screen lock removed | `K_bio` gone (F13b). Attempt keys unaffected |
| OS update or app update | Nothing, unless keys report errors (F13) |
| The newest generation is damaged and storage falls back | `Choosing` compares `vk_id`. A match works as usual; a mismatch gives `MasterPassword(SLOT_MISMATCH)` |
| Device file damaged | Rename to `.bad`; `MasterPassword(NO_DEVICE_SECRET)`. After the MP unlock, offer PIN and fingerprint setup |

### F16. Forgot MP: reset after 72 hours
Covered in 6.4, steps 1 to 7.

---

## 10. Error classification

**Rule:** a secret is **gone** only when the platform says so; everything else is **temporary**: use the MP this time and change nothing. Neither class ever touches vault files.

| Where | What happens | Class | Action |
|---|---|---|---|
| `KeyStore.getInstance("AndroidKeyStore").load(null)` | Any exception | Temporary | — |
| `getKey(alias)` | Returns null, API 31+ | **Gone** | Attempt key: discard without counting. Fingerprint: remove the slot |
| `getKey(alias)` | Returns null, API 24 to 30 | Gone only if corroborated (F13b), else Temporary | — |
| `getKey(alias)` | `UnrecoverableKeyException` with **no cause** (the platform's conversion of `KeyPermanentlyInvalidatedException` [C: `AndroidKeyStoreSpi.java:129-130`; android11 `:108-109`]) | **Gone** | As above |
| `getKey(alias)` | `UnrecoverableKeyException` with a `KeyStoreException` cause; any other exception | Temporary | — |
| `Mac.init` / `Cipher.init` | `KeyPermanentlyInvalidatedException`: `KEY_NOT_FOUND` and `KEY_PERMANENTLY_INVALIDATED` map to it [C: `KeyStoreCryptoOperationUtils.java:117-120`]; an enrolment change too [L] | **Gone** | As above |
| `Mac.init` / `Cipher.init` | `UserNotAuthenticatedException`; `InvalidKeyException("Keystore operation failed")`, for example `DEVICE_LOCKED (-72)` [L]; anything else | Temporary | — |
| `Mac.doFinal` (attempt key) | `ProviderException` or any exception: no output | Temporary | Restore the key, undo the count (5.2) |
| GCM open of an attempt-key blob (software) | `AEADBadTagException` | **Wrong PIN** | Counted |
| Fingerprint `doFinal` | `AEADBadTagException`, or a result that is not 32 bytes | **Gone** (the blob does not match the key) [L: lab saw it after tampering] | Remove the slot |
| Fingerprint `doFinal` | `IllegalBlockSizeException` caused by `KEY_USER_NOT_AUTHENTICATED` (seen when the PIN was typed on the prompt's lockout screen first [L]); any other exception | Temporary | — |
| `onAuthenticationError` | 1 HW_UNAVAILABLE, 2, 3 TIMEOUT, 4, 5 CANCELED, 7 LOCKOUT, 8 VENDOR, 9 LOCKOUT_PERMANENT, 10 USER_CANCELED, 11 NO_BIOMETRICS, 12 HW_NOT_PRESENT, 13 NEGATIVE_BUTTON, 14, 15, or any unknown code | Temporary | Show the pad or MP with a message. Never remove. For 11, the next `Cipher.init` reports `KeyPermanentlyInvalidatedException` if the key really died |
| `onAuthenticationFailed` | Wrong finger | — | The prompt stays open by itself |
| `canAuthenticate(BIOMETRIC_STRONG)` | 0 or −1 (`STATUS_UNKNOWN`, API 28 to 29 with 1.1.0 [L]) | Offer | — |
| `canAuthenticate(BIOMETRIC_STRONG)` | Anything else (11, 12, 1, 15, 21 "not enabled for apps" [L]) | Not now | Keep the slot; do not start the prompt automatically |
| Key creation or import | `StrongBoxUnavailableException` | Fall back to TEE | — |
| Key creation or import | Fingerprint `InvalidAlgorithmParameterException` (no enrolled biometric [L]) | Not available | Explain |
| Key creation or import | `KeyInfo` reports a software level | Not supported | Delete the key; explain |
| Key creation or import | Anything else | Temporary | Setup: "try later". Refill: keep the smaller pool |
| Device file | Missing (ENOENT) | No device secrets | MP; clean up aliases |
| Device file | Parse or validation failure | No device secrets | Rename to `.bad`; MP; clean up only after a new file is committed |
| Device file | Commit failure | Abort | No evaluation (5.2); `StorageError` |

```kotlin
sealed interface Ks<out T> {
    data class Ok<T>(val value: T) : Ks<T>
    data class Gone(val why: String) : Ks<Nothing>
    data class Temporary(val why: String, val cause: Throwable?) : Ks<Nothing>
}

fun classifyGetKey(result: Result<Key?>, api: Int, corroborated: () -> Boolean): Ks<SecretKey> =
    result.fold(
        onSuccess = { k ->
            when {
                k != null -> Ks.Ok(k as SecretKey)
                api >= 31 || corroborated() -> Ks.Gone("not found")
                else -> Ks.Temporary("not found, uncorroborated", null)
            }
        },
        onFailure = { e ->
            if (e is UnrecoverableKeyException && e.cause == null) Ks.Gone("permanently invalidated")
            else Ks.Temporary("getKey", e)
        },
    )

fun classifyInit(e: Exception): Ks<Nothing> =
    if (e is KeyPermanentlyInvalidatedException) Ks.Gone("invalidated") else Ks.Temporary("init", e)
```

- `android.security.KeyStoreException.isTransientFailure()` (API 33+) may be logged but is not used for decisions: the rule above is stricter.
- Manufacturers that throw a bare `InvalidKeyException` on enrolment changes (Proton's comment [C: account-based.md 3]) end up "temporary". The cost is a fingerprint that keeps failing until the user turns it off (8.4), never data.

---

## 11. How to test it

### 11.1 Structure that makes it testable on the JVM
- The lock core is plain Kotlin with no Android types. Its ports:
  - `DeviceFileStore`: read and commit, with an in-memory "disk" that models durability, so only data that went through fsync, rename and the directory fsync survives a simulated crash;
  - `KeystorePort`: `beginHmac`, `finishHmac`, `importAttemptKey`, `delete`, `aliases`, and the biometric cipher operations;
  - `Clock` (wall, elapsed, boot count);
  - `VaultPort` (`peekNewestHeader`, `open(vk)`).
- `FakeKeystore` uses real JCA HMAC and AES-GCM with keys in memory. It is configurable per alias:
  - single-use or not;
  - scripted failures (Gone at getKey, KPIE at init, Temporary at init or finish);
  - "output lost after consumption".

### 11.2 JVM unit tests
| Test | Checks |
|---|---|
| `vector_pinSlot` | Appendix A vector: `pinMsg`, `t`, `wrapped`; a wrong PIN gives `AEADBadTagException` |
| `vector_deviceFile_golden` | The 3.2 example parses; every rule in the field table rejects its counter-example (wrong lengths, unknown keys, duplicate keys, trailing bytes, too many tokens) |
| `rules_blocklist` | Every rule's examples are rejected with the right message. Totals: 89,820 blocked; 100 % of a 100-PIN fixture of common PINs. The DD list is used only in a local, non-shipped test |
| `attempt_crashAtEveryPoint` | Crash injected before and after every commit and Keystore call (5.2 table). Invariant: **number of PIN evaluations ≤ number of attempts committed as spent**, and after restart the attempts left never exceed the uncrashed run's |
| `attempt_limits_property` | 10,000 seeded random sequences of {right PIN, wrong PIN, fingerprint success, MP unlock, kill, reboot, clock jump ±1 year, temporary Keystore error, Gone}. Invariants: (I1) between two MP unlocks, wrong-PIN evaluations ≤ 10; (I2) between two pool refills (a correct PIN, or a new PIN set after the MP), wrong-PIN evaluations ≤ 5; (I3) the MP always unlocks; (I4) temporary errors never change slots (except restoring a key); (I5) Gone removes only the affected slot; (I6) `VaultPort` write methods are never called from the lock core (except F7 and F16) |
| `refill_target` | Refill equals `min(5, 10 − fail_since_mp)`; a quick lock during the refill does not shrink the pool |
| `slot_mismatch_costsNothing` | A `vk_id` or `vault_id` mismatch returns `Unavailable` and commits nothing |
| `classifier_table` | Every row of section 10, including null cause versus `KeyStoreException` cause, and API 30 null with and without corroboration |
| `reminder_schedule` | Due dates for steps 0 to 4; the wall clock moved back 2 h makes it due; "Not now" works once |
| `reset_wait_uptime` | The date moved forward by a year does not shorten the 72 h; reboots only lengthen it; an MP unlock cancels it |
| `mpChange_ordering` | A crash between the vault commit and the device-file commit leaves a mismatch (MP required), never a lost vault |

### 11.3 Emulator tests (instrumented, when an emulator is free)
**Setup notes:**
- The API 37 emulator's Keystore is software (`securityLevel=0`) [L], so these tests run a debug build with the hardware-check override.
- The old-Android lab images (API 24 and 28, PIN 1111, fingerprints enrolled) are at `LAB/old-android` with boot instructions in its `README.txt`.
- Never run `adb kill-server`.

| Test | How | Expect |
|---|---|---|
| E1 single-use is enforced (API 31+) | Import an attempt key with `setMaxUsageCount(1)`; HMAC once; then `getKey` | null (keystore2 deleted it) |
| E2 locked phone (API 35+) | Secure lock screen; start an attempt from a debug hook; press power before `finishHmac` | Temporary (`DEVICE_LOCKED`), key restored, attempt not counted |
| E3 kill mid-attempt | Debug-only `LockDebug.pauseAfterCommit`; `adb shell am force-stop`; restart | Attempts left decreased by exactly 1 |
| E4 reboot | `adb reboot` between wrong PINs | Counts kept |
| E5 clock | Change the date (root shell on a google_apis image) | Same attempts left; reminder due when the clock is moved back |
| E6 cache | `adb shell pm clear --cache-only <pkg>` (or `pm trim-caches`) | Nothing changes |
| E7 fingerprint changes | Enrol another finger (`adb emu finger touch 2` in Settings); remove all fingerprints; remove the screen lock (`adb shell locksettings clear --old 1111`) | Fingerprint slot removed with a notice; attempt keys still work (on API 35+ also after the screen lock is removed) |
| E8 old Android | API 24 and 28 images with the app built on biometric 1.1.0 | 24: fingerprint not offered, PIN works (keys without a usage count, deleted by the app); 28: fingerprint works |
| E9 hardening | `adb shell screencap` during `PinPad`; a test accessibility service without `isAccessibilityTool` (API 34+) | Black screenshot; pad nodes not readable |
| E10 latency (emulator, indicative only) | Timestamps from the 6th digit to the first vault frame | Logged; the real gate is 11.4 |

### 11.4 Real-device acceptance
Cannot be done in the lab:
- A mid-range phone (for example a Samsung A-series) with 1,000 entries: 95th-percentile unlock under 500 ms from the 6th digit; refill under 2 s in the background.
- Record `FEATURE_KEYSTORE_SINGLE_USE_KEY`, `FEATURE_STRONGBOX_KEYSTORE`, each key's security level and the import times on every test phone, including the user's own phone (open question 2).
- Samsung Android 9 with biometric 1.1.0 and a `CryptoObject` (V-P1).

---

## 12. Requirement trace

| Requirement | Where it is met |
|---|---|
| 1. The MP alone always opens the vault and backups | 2.2; the MP path uses no Keystore and no device file and needs no successful write (an unlock does not fail if the counter commit fails); 6.1; F11b |
| 2. Copies need the MP; the PIN gives no shortcut | 2.3, 2.6 A1; the hardware check refuses software Keystores |
| 3. A small fixed number of guesses; no tricks | 5.1 to 5.7 |
| 4. Banking feel, under a second, fingerprint prompts by itself | 8.1, 8.2, 5.3 step 2, F3 |
| 5. API 24 to 37 with biometric 1.1.0 | 2.4, the API gates in 2.3, 11.3 E8 |
| 6. No vault deletion on errors | 5.4, section 10, 11.2 invariant I6 |
| 7. The MP is not forgotten | 6.3, 6.4, MP for exports and settings, 8.5 |

## 13. Changes this design asks of other documents

1. **Vault header:** add `vault_id` and `vk_id` next to `generation`. Exactly one password slot. **Remove the biometric slot from the header** (crypto.md sections 1 and 11 "Enrol biometric", which also needs `updateAAD` after authentication).
2. **Password encoding:** fold apostrophe look-alikes before NFC (6.3), with test vectors, before the first release.
3. **Manifest:** `USE_BIOMETRIC` (merged by the library) and `HIDE_OVERLAY_WINDOWS`. Nothing else.
4. **Storage:** export always from the generation verified in the session (V-S6). The lock core never calls vault write methods outside F7 and F16.
5. **Help text:** the 2.6 limitation sentence; no "Clear storage" to fix lock problems.

---

## Appendix A. Test vector (PIN slot)

Inputs:
- `vault_id = 00 01 … 0f`, `vk_id = 10 … 1f`, `epoch = 20 … 2f`, `token_id = 30 … 3f`;
- attempt key `K = 40 41 … 5f` (32 bytes);
- `VK = 60 … 7f`; `nonce = 80 … 8b`;
- PIN `305841`, which no blocklist rule matches.

Computed with JDK 25 and cross-checked with Python `hmac` and `cryptography` 41.0.7 [X: `D1/PinVector.java`]:

```
pinMsg  (61 B) = 505650494e3100000102030405060708090a0b0c0d0e0f202122232425262728292a2b2c2d2e2f
                 303132333435363738393a3b3c3d3e3f333035383431
t              = b51cafe6c637ea19b4d888aa79be2a0a3688bdd0e84d6e060cd55c8f68bcb7b5
aad     (75 B) = 505650494e534c4f543100000102030405060708090a0b0c0d0e0f101112131415161718191a1b
                 1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
wrapped (48 B) = c3396ce9c15ec9816ac06e5432f3d211137cb5b778e1dcc6bd3c97c6785f247c
                 0a2f885413a2998bbf60e3015554b0bf
PIN 305842     -> AEADBadTagException
bio aad (59 B, slot 90…9f) = 505642494f534c4f543100000102030405060708090a0b0c0d0e0f
                 101112131415161718191a1b1c1d1e1f909192939495969798999a9b9c9d9e9f
```

## Appendix B. Blocklist reference implementation

- `D1/pinrules.py` implements every rule of 4.2, including the 16 listed shapes.
- Run: `python3 pinrules.py <DD-6-digit-291000.txt> <iOS-6-digit.txt>`. It prints the blocked total (89,820), the per-rule counts and the coverage figures quoted in 4.3.
- Port it to Kotlin as `PinRules.reasons(pin): List<Reason>` and keep the Python as the test oracle.

## Appendix C. Sources opened today for this design

- **AOSP `android17-release`:**
  - `frameworks/base` `keystore/java/android/security/keystore2/{AndroidKeyStoreSpi,KeyStoreCryptoOperationUtils,AndroidKeyStoreProvider}.java` (in `D1/`);
  - `AndroidKeyStoreHmacSpi.java`, `KeyGenParameterSpec.java`;
  - `system/security/keystore2/src/{operation,super_key,enforcements}.rs`;
  - `system/keymint` `{km_tag,km_operation,km_keys}.rs` (copies in `FACTS/aosp/`).
- **AOSP `android11-release`:** `keystore/java/android/security/{KeyStore.java, keystore/AndroidKeyStoreSpi.java, keystore/AndroidKeyStoreProvider.java}` (in `D1/`).
- **API levels:** `/home/asus/Android/Sdk/platforms/android-37.0/data/api-versions.xml` through `FACTS/apiv.py`.
- **Lab logs:** `LAB/biometric/logs/bootA_PVBIO_lib1.1.0.log`, `bootB_PVBIO_both_libs.log`; `LAB/old-android/logs/bio-api24-A-appcompat.txt`, `bio-api24-C-*.txt`, `bio-api28-A-platform.txt`; `LAB/biometric/project/app/src/main/java/com/example/pvlab/bio/lift/BiometricVaultKey.kt`.
- **OWASP:** `FACTS/web/owasp/MASTG-TEST-0329.md`.
- **PIN data:** `FACTS/web/pins/DD-6-digit-291000.txt`, `iOS-6-digit.txt`; `FACTS/web/tops21-6-pin-security.txt`.
- **Everything else** through FACTS sections 1 to 8 and VERIFICATION.md, as cited inline.
