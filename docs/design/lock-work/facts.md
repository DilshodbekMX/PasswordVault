# Fact sheet: a banking-style PIN lock for a local-only vault

Collected 2026-10-06 for the three lock-screen designers and three judges. Every fact names the primary source I opened today. Downloads are under `scratchpad/design/lock-facts/` (`aosp/`, `dac/`, `web/`, `bench/`).

**Tags.** **[C]** confirmed today from the named primary source. **[X]** my own experiment today. **[S2]** secondary source opened today. **[I]** my inference; check it before relying on it. **[U]** could not confirm.

**Path shorthand.** `FB/` = AOSP `platform/frameworks/base`, `KS2/` = `platform/system/security/keystore2/src`, `KM/` = `platform/hardware/interfaces/security/keymint/aidl/android/hardware/security/keymint`, `KMR/` = `platform/system/keymint`, all on branch `android17-release` unless another branch is named. `DAC` = developer.android.com, `SAC` = source.android.com.

---

## 0. The facts most likely to decide the design

1. **An app cannot get hardware throttling for its own PIN.** Gatekeeper and Weaver serve only system processes. KeyMint has rate-limit tags, but the SDK has no setter for them, and nothing added in API 35 to 37 changes this (section 2) [C].
2. **Three hardware-enforced limits are reachable from an app:**
   - the phone's own screen lock, through a key bound to the device credential (Gatekeeper or Weaver throttling);
   - Class 3 biometric lockout;
   - `setMaxUsageCount(1)` keys, but only where the KeyMint has secure storage (`FEATURE_KEYSTORE_SINGLE_USE_KEY`).

   Usage counts above 1 are enforced by the keystore2 daemon in software (section 1) [C].
3. **A usage count drops only after a successful `finish()`.** A failed AES-GCM decrypt or an aborted operation costs nothing. KeyMint may also hand out decrypted bytes from `update()` before `finish()`. Signing and HMAC are the only operations that return nothing before `finish()` (section 1.3) [C].
4. **A PIN wrap that is not bound to the device falls quickly to offline guessing.**
   - I measured 56 Argon2id guesses per second (64 MiB, t=3, p=4) on this desktop's CPU. That covers all 10,000 four-digit PINs in about 3 minutes and all 10^6 six-digit PINs in about 5 hours [X].
   - Binding the PIN to a Keystore key forces every guess through the phone's TEE. That costs roughly 11 to 34 ms per AES operation (90th percentile across about 600 million devices) [C], so about 2 to 6 minutes for 10^4 PINs and 3 to 9 hours for 10^6 [I].
5. **Android 16 QPR2 and 17 introduced a new lock-screen schedule.** Five free tries, then 1 min, 5 min, 15 min, 30 min, 90 min, 4 h, 12 h, 36 h, 4 d, 13 d, 41 d, 123 d, 1 y, 3 y and 9 y, then no more tries after 20 failures. Repeated identical wrong guesses are not counted (section 2) [C].
6. **Four- and six-digit PINs are about equally guessable when tries are limited.**
   - At 10 guesses: 4.6 % of 4-digit and 6.5 % of 6-digit user-chosen PINs fall.
   - At 30 guesses: 7.6 % and 8.9 %.
   - At 100 guesses: 16.2 % and 13.3 %.
   - Six digits help only when the attacker can bypass the limit (section 6) [C].
7. **The standards point the same way.**
   - NIST SP 800-63B-4 treats a local PIN that unlocks a stored key as an "activation secret": at least 4 characters (SHALL), at least 6 (SHOULD), a blocklist (SHOULD), and at most 10 consecutive failures, after which a different authenticator is required [C].
   - Android's CDD 17 strongly recommends device PINs of at least 6 digits [C].
8. **Banking apps verify the PIN on a server.** In PowerAuth, a wrong PIN quietly yields wrong key material, and the server counts failures and blocks the device. EU PSD2 rules cap consecutive failures at 5 and inactivity at 5 minutes. A local-only vault cannot copy the server half (section 4) [C].
9. **Two of the reference apps count a failure only after the check, and one is far later.** Bitwarden runs its key derivation first. Proton Pass waits 2 seconds after a wrong PIN before counting it. Killing the app in that window makes the guess free. The Android Gatekeeper specification requires the opposite order (section 7) [C].
10. **A PIN pad drawn by the app never opens the system keyboard.** `FLAG_SECURE` covers it. Accessibility services can still read its buttons unless they are marked data-sensitive (API 34+, or Compose `isSensitiveData`) (section 8) [C].

---

## 1. Android Keystore features a PIN design could use

### 1.1 Summary table

| Feature | API | Where enforced | Source |
|---|---|---|---|
| HMAC-SHA256 key (`KeyProperties.KEY_ALGORITHM_HMAC_SHA256`), no user authentication | 23 | Key material inside the TEE or StrongBox when `KeyInfo.getSecurityLevel()` (API 31) says so, or `isInsideSecureHardware()` (API 23, deprecated in 31). KeyMint: `NO_AUTH_REQUIRED` "Must be hardware-enforced". Key size 64 to 512 bits | api-versions.xml; `KM/Tag.aidl:382-388`; `FB/keystore/java/android/security/keystore2/AndroidKeyStoreKeyGeneratorSpi.java:223-226` [C] |
| AES-256-GCM key, no user authentication | 23 | Same as above | api-versions.xml [C] |
| What such a key protects | — | DAC: if the OS is compromised, "the attacker might be able to use any app's Android Keystore keys on the Android device, but it can't extract them from the device". So it stops copied files from being attacked off the phone, not on-phone use | DAC `/privacy-and-security/keystore` [C] |
| Which devices have it in hardware | 24+ | CDD 7.0 §9.11: a device with a secure lock screen "MUST back up the keystore implementation with secure hardware" and have hardware AES and HMAC. Devices launched on an earlier Android are exempt unless they declare fingerprint. `FEATURE_HARDWARE_KEYSTORE` (API 31) version ≥ 40 ("AES, HMAC … where the secret … never leaves secure hardware") is guaranteed only for devices launching with Android 12 | CDD 7.0 §9.11 (copy saved today by a verifier); `FB/core/java/android/content/pm/PackageManager.java:4800-4823` [C] |
| `setMaxUsageCount(n)` (also `KeyProtection.Builder`) | 31 | n = 1: in KeyMint hardware if it has secure storage, otherwise keystore2. n > 1: always keystore2, which is software in the OS. See 1.2 | `KM/Tag.aidl:313-340`; `KMR/common/src/tag.rs:365-376` [C] |
| `setUnlockedDeviceRequired(true)` | 28 | keystore2 enforces it cryptographically: it super-encrypts such keys and wipes the super key when the device locks. KeyMint's hardware enforcement "is now considered deprecated … never enabled by Keystore". Bugs on Android 12 to 14 (1.4) | `FB/.../KeyGenParameterSpec.java:1801-1850`; `KM/Tag.aidl:492-501`; `KM/IKeyMintDevice.aidl` `deviceLocked()` docs; `KS2/super_key.rs:97-123,266-281` [C] |
| Key bound to the device credential: `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL [or AUTH_BIOMETRIC_STRONG])` | 30 | Hardware. `USER_SECURE_ID`, `USER_AUTH_TYPE` and `AUTH_TIMEOUT` "Must be hardware-enforced", and KeyMint checks the auth token's HMAC. The lock-screen credential is checked inside the TEE (CDD 17 §9.11 C-1-3) | `KM/Tag.aidl:349-421`; `FB/.../KeyGenParameterSpec.java:1591-1630`; CDD 17 [C] |
| Time-bound key, the only device-credential route on API 24 to 29: `setUserAuthenticationValidityDurationSeconds(N>0)` plus `KeyguardManager.createConfirmDeviceCredentialIntent` | 23 / 21 (both deprecated: 30 / 29) | Hardware token check. keystore2 also checks the token's age with `BootTime` (`KS2/enforcements.rs:613-640`) | `FB/.../KeyGenParameterSpec.java:1541-1589`; `FB/core/java/android/app/KeyguardManager.java:292-307`; api-versions.xml [C] |
| StrongBox (`setIsStrongBoxBacked`, `FEATURE_STRONGBOX_KEYSTORE`) | 28 | Separate secure chip. Supports AES-128 and AES-256, and HMAC-SHA256 with keys of 8 to 64 bytes. Throws `StrongBoxUnavailableException` when absent or when the algorithm is unsupported | DAC `/privacy-and-security/keystore` [C] |
| Rate-limit or rollback tags: `MAX_USES_PER_BOOT`, `MIN_SECONDS_BETWEEN_OPS`, `ROLLBACK_RESISTANCE`, `APPLICATION_ID` | — | **No SDK setter.** The public `KeyGenParameterSpec.Builder` and `KeyProtection.Builder` in the API 37 `android.jar` have only: algorithm spec, attestation, block modes, certificate fields, digests, paddings, `setInvalidatedByBiometricEnrollment`, `setIsStrongBoxBacked`, `setKeySize`, key validity dates, `setMaxUsageCount`, `setMgf1Digests`, `setRandomizedEncryptionRequired`, `setUnlockedDeviceRequired`, the user-authentication setters, `setUserConfirmationRequired`, `setUserPresenceRequired`. `MIN_SECONDS_BETWEEN_OPS` is "OBSOLETE … KeyMint implementations do not need to support this tag". AOSP's reference KeyMint does support `MAX_USES_PER_BOOT` and `ROLLBACK_RESISTANCE`, so only a non-SDK route to keystore2 could reach them (untested, not recommended) | `javap` on the API 37 `android.jar`; `KM/Tag.aidl:285-311`; `KMR/common/src/tag/info.rs:408-416,483-491` [C]; non-SDK route [I] |
| Keys per app | 37 | 50,000 keys for non-system apps targeting 37 (200,000 otherwise). Exceeding it throws `KeyStoreException`, with `ERROR_TOO_MANY_KEYS` (API 37) | DAC `/about/versions/17/behavior-changes-all`; `KS2/security_level.rs:79-82,561-615` [C] |

### 1.2 `setMaxUsageCount`: exact semantics

- **Javadoc** [C, `FB/.../KeyGenParameterSpec.java:1867-1906`]:
  - "After every use of the key, the use counter will decrease … after successfully encrypting and decrypting data using methods such as `Cipher.doFinal()` … After successfully signing data …".
  - "When the use counter is depleted, the key will be marked for deletion by Android Keystore and any subsequent attempt to use the key will throw `KeyPermanentlyInvalidatedException` … as if the key never existed before."
  - "Some secure hardware may not support this feature at all, in which case it will be enforced in software, some … only with maxUsageCount = 1, and some … with larger value".
- **Feature flags** [C, `FB/.../PackageManager.java:4996-5011`]:
  - `FEATURE_KEYSTORE_SINGLE_USE_KEY` (API 31): hardware enforces only a count of 1.
  - `FEATURE_KEYSTORE_LIMITED_USE_KEY` (API 31): hardware enforces any count.
  - Both XML files exist in `frameworks/native/data/etc`. I found neither declared in the Pixel (zuma, zumapro, gs201, gs101) or Cuttlefish `device.mk` files. **How many phones declare them is unknown** [U].
- **KeyMint contract** [C, `KM/Tag.aidl:313-340`]:
  - "if the caller specifies count > 1, it is not expected that any TEE will be able to enforce this feature in the hardware due to limited resources of secure storage … must be added to the key characteristics with SecurityLevel::KEYSTORE".
  - For count = 1, "some TEEs may have the ability to enforce this feature in the hardware with its secure storage". If not, it is again KEYSTORE level.
  - The reference TA follows this: count 1 is KeyMint-enforced only when secure storage is available [C, `KMR/common/src/tag.rs:365-376`]. "A successful use of a key with UsageCountLimit(1) triggers deletion" of its secure-deletion slot, inside `if result.is_ok()` [C, `KMR/ta/src/operation.rs:361-372`]. Deleting a key marked `ROLLBACK_RESISTANCE` "must render the key permanently unusable" [C, `KM/IKeyMintDevice.aidl:562-583`].
- **keystore2 enforcement** [C]:
  - It does not check the limit at `begin()` ("this is enforced on finish", `KS2/enforcements.rs:537-542`).
  - `after_finish()` runs only after KeyMint's `finish()` returned success (`KS2/operation.rs:426-460`, `KS2/enforcements.rs:316-331`).
  - It then runs SQL `UPDATE … SET data = data - 1` and removes the key when the limit was 1 (`KS2/database.rs:2299-2335`).
  - The count lives in keystore2's database, not in the app's data.
- **What counts as a use** (consequences of the above):
  - A failed operation (for example AES-GCM `VERIFICATION_FAILED`) or an aborted one does not decrement [C from code].
  - KeyMint "It is recommended to return data as early as possible, rather than buffer it". For GCM decryption it processes "all but the tag length" before `finish()`, while "signing returns no data until finish" [C, `KM/IKeyMintOperation.aidl:66-80,166-175`].
  - **An attempt budget must therefore spend an HMAC (sign) operation, never a decrypt** [I].
- **Older KeyMaster devices.** On Keymaster 4.1 and older under Android 12+, `USAGE_COUNT_LIMIT` is "not understood by KM 4.1 and older but can be enforced by Keystore" [C, `KS2/km_compat/km_compat.cpp:228-242`].
- **Reading the count.** `KeyInfo.getRemainingUsageCount()` (API 31) gives "a best effort count and need not be accurate" [C, `FB/.../KeyInfo.java`].

### 1.3 Device-credential and time-bound keys

All [C, `FB/.../KeyGenParameterSpec.java:1474-1630` and DAC `/identity/sign-in/biometric-auth`] unless tagged.

- **Generation.** The key "can only be generated if secure lock screen is set up". A per-use key (timeout 0) also needs an enrolled biometric unless `AUTH_DEVICE_CREDENTIAL` is among the allowed types.
- **Invalidation.**
  - The key becomes "irreversibly invalidated once the secure lock screen is disabled … or when the secure lock screen is forcibly reset (e.g., by a Device Administrator)".
  - Per-use biometric keys also die on a new biometric enrolment, "unless … `KeyProperties.AUTH_DEVICE_CREDENTIAL` is specified".
- **Time-bound keys on API 23 to 29.**
  - The deprecated `setUserAuthenticationValidityDurationSeconds(N>0)` maps to `AUTH_DEVICE_CREDENTIAL | AUTH_BIOMETRIC_STRONG`.
  - The key is usable for N seconds after either "the user unlocking the secure lock screen of the Android or … the confirm credential flow". **A recent phone unlock alone therefore authorises it.**
  - KeePassDX uses N = 5 on API 23 to 29.
- **Per-use with the phone credential on API 30+.** Use `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL)` with `BiometricPrompt` allowing `DEVICE_CREDENTIAL` and passing a `CryptoObject`. "DEVICE_CREDENTIAL and BIOMETRIC_STRONG | DEVICE_CREDENTIAL" are not supported on API 29 and lower. `setNegativeButtonText` cannot be combined with `DEVICE_CREDENTIAL`.
- **The system's credential prompt shares the phone's throttle.** It verifies through `lockPatternUtils.verifyCredential`, shows the device's lockout end time, and calls `reportFailedPasswordAttempt`. Wrong phone PINs typed for our app count toward the phone's lockout and any "wipe after N failures" policy [C, `FB/packages/SystemUI/src/com/android/systemui/biometrics/domain/interactor/CredentialInteractor.kt:75-169`].

### 1.4 `setUnlockedDeviceRequired`: known bugs

[C, Javadoc `FB/.../KeyGenParameterSpec.java:1801-1850`]

- On Android 11 and lower, "encryption and verification operations with symmetric keys weren't restricted either".
- **Bugs on Android 12 to 14, "fixed in Android 15":**
  - keys could not be generated, imported or used without a secure lock screen;
  - "When the user's secure lock screen was removed, all of that user's unlocked-device-required keys were automatically deleted";
  - unlocking with a non-strong biometric (face on many phones) did not re-authorise the keys;
  - biometric unlock did not re-authorise them in profiles sharing the parent's lock.
- Google's advice: use it "only on Android 15 and higher".
- `KeyInfo.isUnlockedDeviceRequired()` exists from API 36.1 [C, api-versions.xml].
- **How keystore2 enforces it.**
  - It keeps an "UnlockedDeviceRequired symmetric super key … cleared from memory each time the user locks the device" [C, `KS2/super_key.rs:107-123`].
  - On use it returns `DEVICE_LOCKED` while locked (`KS2/enforcements.rs:588-593`).
  - It is therefore an OS-level guarantee rooted in the lock-screen credential, not in a TEE counter [I from C].

### 1.5 StrongBox speed and availability

- DAC: StrongBox "is slower, more resource-constrained, and supports fewer concurrent operations. For most apps, StrongBox is not necessary" [C].
- Measured numbers are in section 3. In a Google sample of devices from March to May 2024, StrongBox was present on 8.7 % of premium-tier, 7.2 % of mid-tier and 0.2 % of low-tier devices [C, DroidCCT §4.1].

---

## 2. Throttling: what Android's own lock screen has and an app does not

### 2.1 Gatekeeper and Weaver

- **Order of writes.** Gatekeeper runs in the TEE and "must write a failure counter before verifying a user password. If the password verification succeeds, the failure counter should be cleared. This prevents attacks that prevent throttling by disabling the embedded MMC (eMMC) after issuing a verify call." The failure counter should go to secure storage or RPMB [C, SAC `/docs/security/features/authentication/gatekeeper`].
- **The code does the same.** `Verify()` runs `ThrottleRequest`, then `IncrementFailureRecord` (persisted), then `DoVerify`, and clears the record only on a match [C, `system/gatekeeper/gatekeeper.cpp:119-176`].
- **Reboots.** "device was rebooted or timer reset, don't count as new failure but reset timeout" [C, same file, `ThrottleRequest`].
- **Weaver** (secure element) "first increments and persists the failure count for that slot" before comparing. During a timeout it "MUST NOT reveal the actual value … or modify the slot's failure count". After a reboot, without a secure timer, "it must instead implicitly reset every slot's timeout to the starting timeout for its current failure count" [C, `platform/hardware/interfaces/weaver/aidl/android/hardware/weaver/IWeaver.aidl:61-100`].

### 2.2 The schedules

- **AOSP reference Gatekeeper up to `android16-release`.** Each value is the wait after that failure; failures not listed wait nothing.

  | Failure | Wait |
  |---|---|
  | 5th and 10th | 30 s |
  | 11th to 29th | 30 s each |
  | 30th to 139th | doubling every 10 failures |
  | 140th onward | 1 day each |

  [C, `gatekeeper_android16-release.cpp`]. Markert et al. tabulate the result for Android 7 to 10: 100 guesses take 10 h 45 m, 200 take 67 days [C].
- **`android16-qpr2-release` and `android17-release`** use a table [C, `gatekeeper.cpp:300-336`], with no further guesses from 20 failures onward (`INT32_MAX`).

  | Failure | Wait |
  |---|---|
  | 1st to 4th | 0 |
  | 5th | 1 min |
  | 6th | 5 min |
  | 7th | 15 min |
  | 8th | 30 min |
  | 9th | 90 min |
  | 10th | 4 h |
  | 11th | 12 h |
  | 12th | 36 h |
  | 13th | 4 d |
  | 14th | 13 d |
  | 15th | 41 d |
  | 16th | 123 d |
  | 17th | 1 y |
  | 18th | 3 y |
  | 19th | 9 y |
  | 20th onward | no more guesses |

- **CDD 17 §9.11.**
  - C-0-2, a MUST: at least 30 s between failures for 9 < n < 30, then 30·2^floor((n−30)/10) s or 24 h, whichever is smaller.
  - C-SR-1, new in 17 and strongly recommended: the same table counted in *unique* failures, except that failure 12 waits 24 h, not 36 h.
  - §9.11.1 C-SR-5: "an upper bound of 20 failed primary authentication attempts", with factory reset only if the user opts in [C, SAC `/docs/compatibility/17/android-17-cdd`, fetched by me].
- **CDD 7.0 §9.11:** "MUST rate limit attempts and MUST have an exponential backoff algorithm. Beyond 150 failed attempts, the delay MUST be at least 24 hours per attempt" [C].
- **`SoftwareRateLimiter`, new in Android 16 QPR2 and 17, on by default.**
  - `config_softwareLskfRateLimiterEnforcing` = true [C, `FB/core/res/res/values/config.xml:1543-1549`].
  - It runs in `system_server` beside the hardware limiter, uses the same table, and rejects "duplicate wrong guesses before they reach the hardware rate-limiter". It keeps up to 5 unique wrong guesses, in memory only, for 5 minutes, a value "chosen carefully, and it should not be increased".
  - It also rejects too-short credentials before they count [C, `FB/services/core/java/com/android/server/locksettings/SoftwareRateLimiter.java`; `LockSettingsService.java:750-756`].
- **Biometrics (CDD 17 §7.3.10):**
  - strongly recommended: a 30 s lockout after 5 false trials, with all rate-limiting logic in the TEE;
  - MUST: fall back to the primary credential after no more than 20 false trials;
  - MUST: disable biometrics once the primary credential's backoff has started [C].

### 2.3 Why apps cannot use them, up to API 37

- **gatekeeperd refuses non-system callers.** It checks `android.permission.ACCESS_KEYGUARD_SECURE_STORAGE` on every call (`platform/system/core/gatekeeperd/gatekeeperd.cpp:58,228-229`). That permission is `signature|setup` and "Only allowed for system processes" (`FB/core/res/AndroidManifest.xml:8251-8254`) [C]. Weaver is reached only through `LockSettingsService` (`SyntheticPasswordManager`, `WeaverHidlAdapter`) [C, file listing of `FB/services/core/java/com/android/server/locksettings/`].
- **KeyMint's own rate-limit tags are not exposed** (table 1.1) [C].
- **What API 35 to 37 added in `android.security*`, `android.hardware.biometrics`, `KeyguardManager` and `DevicePolicyManager`** (api-versions.xml, API 37) [C]. None of it gives an app hardware throttling:
  - `KeyStoreManager` key sharing (36);
  - `KeyInfo.isUnlockedDeviceRequired` (36.1);
  - ML-DSA keys (37);
  - `KeyStoreException.ERROR_TOO_MANY_KEYS` (37);
  - `BiometricManager.getLastAuthenticationTime` (35);
  - the `IDENTITY_CHECK` authenticator (36);
  - `BiometricPrompt.Builder.addFallbackOption` (36.1);
  - `LESS_THAN_STRONG` (37);
  - `KeyguardManager.addDeviceLockedStateListener` (36.1), which needs `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE`, `signature|module|role`, so it is not available to us;
  - `AdvancedProtectionManager` (36).

---

## 3. How long one Keystore operation takes on real phones

| Source | Devices | Operation | TEE | StrongBox |
|---|---|---|---|---|
| KeyDroid: Blessing, Anderson, Beresford, arXiv 2507.07927 (2025), mean of 100 runs [C] | Pixel 8 | AES-256-GCM key generation | 21 ms (software keystore: 2 ms) | 71 ms |
| | Pixel 8 | AES-GCM encrypt 0.01 MiB | 30 ± 10 ms | 210 ± 10 ms |
| | Pixel 8 | AES-GCM encrypt 1 MiB | 420 ms | 15.43 s |
| | Pixel 8 | RSA encrypt 256 bits | 6.5 ms | 12.5 ms |
| | Pixel 3 / Galaxy S24 | AES-GCM encrypt 1 MiB | — / 60 ms | 63.43 s / 26.39 s |
| DroidCCT: Moghimi et al. (Google), arXiv 2601.11745 (Jan 2026); about 600 M devices, Mar to May 2024; 90th-percentile wall time, read by me from the Fig. 3 bars (±2 ms) [C] | low / mid / premium tier | AES decrypt (CBC, 128-bit key) | 13 / 12 / 11 ms | 12 / 54 / 12 ms |
| | | AES encrypt | 34 / 29 / 24 ms | 31 / 109 / 29 ms |
| | | HMAC key generation | 93 / 60 / 43 ms | 70 / 73 / 63 ms |
| | | AES key generation | 88 / 56 / 43 ms | 72 / 103 / 62 ms |
| AndroidAPS issue #49, logcat on one phone [S2] | Samsung SM-S918B | AES key generate + delete ("rotation") | 0.4 to 0.9 s (security level not stated) | — |

- **HMAC signing time is not published** in either paper. DroidCCT ran HMAC but plotted no timing for it [U]. AES decrypt is the closest proxy.
- **Our unlock.** One Keystore operation adds roughly 10 to 35 ms in the TEE and 12 to 110 ms on StrongBox [I from C]. Rotating a key on every unlock can add up to about 1 s on some phones [S2].
- **A rooted attacker guessing through the TEE** gets about 30 to 90 operations per second [I]:
  - 10^4 PINs in 2 to 6 minutes, 10^6 in 3 to 9 hours;
  - on a mid-tier StrongBox (about 9 per second), roughly 18 minutes and 30 hours;
  - unless a usage limit or the device lock stops the operations.
- **The same attacker off the phone, against an Argon2id-only PIN wrap** [X, `bench/tput.c`, PHC reference Argon2 "opt" build, i9-14900K while other agents loaded it at about 9]:
  - 125 ms per hash on one thread (8 per second); 44 per second on 8 threads; 56.5 per second on 16 threads (memory-bound).
  - That is about 3 minutes for 10^4 PINs and about 5 hours for 10^6.
  - The median user-chosen PIN needs 1,330 guesses (4-digit) or 39,389 (6-digit) [C, Markert Table VI], so about 24 s and 12 min here.
  - crypto.md cites about 1,700 per second on one RTX 4090 at p = 1 [S2, not re-verified], which would be 6 s and 10 min.

---

## 4. How existing apps implement a PIN

| App | What the PIN is | Stored where | Minimum | On wrong PIN | Order of counter write | Source [C] |
|---|---|---|---|---|---|---|
| **Bitwarden** 2026.9.0 | `PasswordProtectedKeyEnvelope`: Argon2id 64 MiB, 3 iterations, 4 lanes (iOS: 32 MiB, 6, 4), 16-byte salt, sealing the user key. Not bound to the Keystore | Envelope in **plain SharedPreferences** (`putString`, key `pinKeyEncryptedUserKeyEnvelope`). With "Require master password on app restart?" (Yes, or dismissing the dialog) the envelope is **only in memory**. The PIN itself, encrypted under the user key, is persisted as `protectedPin`, so the in-memory envelope can be rebuilt after a master-password unlock | 4 (`MINIMUM_PIN_LENGTH = 4`, digits only when created) | `invalidUnlockAttempts` in preferences, shared by every unlock method. At 5, logout, which deletes local data; the server keeps a copy | **Check first, count after**: `onUnlockError` → `incrementInvalidUnlockCount` runs after the SDK unlock fails | `app/.../ui/platform/feature/settings/accountsecurity/PinInputDialog.kt:41`; `bitwarden-crypto/src/safe/password_protected_key_envelope.rs:440-466`; `data/platform/repository/SettingsRepositoryImpl.kt:619-650`; `data/auth/datasource/disk/AuthDiskSourceImpl.kt:410-473`; `data/vault/manager/VaultLockManagerImpl.kt:87,252-358`; `ui/platform/components/toggle/BitwardenUnlockWithPinSwitch.kt:55-130` |
| Bitwarden audit | Mandiant (Dec 2024) MOB-L-03 "Weak Pin Code Requirements" (Low): PINs could be one digit; "Enforce a minimum PIN length of at least six digits". Bitwarden: "Resolved … Minimum PIN lengths were added" (the code has 4) | | | | | Mandiant report via bitwarden.com/help/is-bitwarden-audited (copy saved today by a verifier) |
| **Proton Pass** 1.41.2 | A screen gate: the PIN is encrypted under a Keystore key that needs no authentication, and compared with `contentEquals` | File in `context.dataDir` | 4 digits (`MIN_PIN_LENGTH = 4`, maximum 100) | **At the auto-lock screen: after 3 wrong PINs, PIN and biometrics are hidden and the Proton account password is required.** When the PIN confirms a lock-settings change: after 3, all users are signed out. 3 wrong account passwords: sign-out | **Wait 2 s, then count**: `delay(WRONG_PIN_DELAY_SECONDS = 2 s)` runs before `setPinAttemptsCount` | `pass/features/auth/.../PinConstants.kt`, `EnterPinViewModel.kt:108-155`, `AuthViewModel.kt:205,380-470,621`; `pass/data/impl/.../usecases/CheckPinImpl.kt:40-48`; `pass/features/profile/.../pinconfig/PinConfigViewModel.kt:110` |
| **KeePassDX** 4.5.5 | No app PIN. "Device credential unlock" uses a Keystore AES key (StrongBox when present) to wrap **the master password itself** (offline.md) | Room table `cipher_database` | the phone's own | Handled by the system | — | `app/.../biometric/DeviceUnlockManager.kt:95-135`, `DeviceUnlockFragment.kt:195-225` |
| KeePassDX key setup | API 30+: `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL)` with `BiometricPrompt` allowing `DEVICE_CREDENTIAL` and a `CryptoObject`. API 23 to 29: a 5-second time-bound key, then `KeyguardManager.createConfirmDeviceCredentialIntent` | | | | | same files |
| **Aegis** 3.4.3 | **No PIN.** Slot types are raw, password, biometric. A "PIN keyboard" option shows a `numberPassword` field when the vault password is all digits; that password is only scrypt-protected (N = 2^15) | — | — | After 3 failures the field switches to a text keyboard. No lockout | — | `vault/slots/Slot.java:29-31`; `ui/AuthActivity.java:82-90,313-326`; `res/values/strings.xml:405-408,527-528` |
| **Keepass2Android** QuickUnlock (HEAD) | While the database is open in memory, unlock with the last N characters of the master password, or a chosen entry's password. Compared in memory | nothing on disk | configurable | **One wrong code closes the database** (`App.Kp2a.Lock(false)`), so the full password is needed. Fingerprint QuickUnlock: 3 failures hide fingerprint if that option is set | in memory | `src/keepass2android-app/QuickUnlock.cs:241-247,290-296,346-404` |
| **Banking apps (PowerAuth, an open protocol for mobile banking)** | The PIN decrypts the "knowledge factor" key "using unauthenticated encryption", so "decryption with an incorrect PIN produces random key material. This is intentional: verification always happens on the server side" | device (PIN-encrypted key) and server | set by the bank | Server-side `FAILED_ATTEMPTS`; reaching `MAX_FAILED_ATTEMPTS` sets the activation to `BLOCKED` | server-side | github.com/wultra/powerauth-crypto `docs/Key-derivation.md:41-51`, `docs/Computing-and-Validating-Authentication-Codes.md:244-296` |
| EU rule banks follow (PSD2 RTS, Reg. (EU) 2018/389) | Art. 4(3)(b): consecutive failed authentication attempts "shall not exceed five within a given period of time". Art. 4(3)(d): inactivity after authentication "shall not exceed 5 minutes". Art. 4(4): "The payer shall be alerted before the block is made permanent." Art. 6: protect knowledge factors from disclosure | | | | | legislation.gov.uk/eur/2018/389/article/4/adopted and /6/adopted (EUR-Lex refused automated access) |

- **Correction to `account-based.md`.** It says Proton Pass "signs every user out after 3 wrong PINs". That is true only for the settings-confirmation PIN. At the normal lock screen, Proton falls back to the account password [C].
- **Uzbek bank apps.** I found no public technical description of how they verify PINs [U].

---

## 5. OWASP guidance (current versions)

- **Versions.** MASVS **v2.1.0** (2024-01-19), MASTG **v2.0.0** (2026-06-30), MASWE **v1.0.0** are the latest releases (`releases.atom`) [C].
- **MASVS-AUTH-2:** "The app performs local authentication securely according to the platform best practices." Its description: "Many apps allow users to authenticate via biometrics or a local PIN code … some apps might not have a remote endpoint, and rely fully on local app authentication."
  - MASVS-AUTH-3: "secures sensitive operations with additional authentication".
  - MASVS-AUTH-1 covers remote protocols.
  - [C, `OWASP/masvs/controls/MASVS-AUTH-{1,2,3}.md`]
- **MASTG v2 Android tests** [C, `OWASP/mastg/tests-beta/android/MASVS-AUTH/`]:
  - 0326, fallback to non-biometric credentials (MASWE-0021, BEST-0031);
  - 0327, event-bound biometrics, meaning no `CryptoObject` and no `setUserAuthenticationRequired(true)` key (MASWE-0020, BEST-0036);
  - 0328, `setInvalidatedByBiometricEnrollment(false)` (MASWE-0022, BEST-0037);
  - 0329, `setConfirmationRequired(false)` (MASWE-0020, BEST-0038);
  - 0330, keys with validity duration > 0 (MASWE-0020, BEST-0036).
  - The v1 tests MASTG-TEST-0017 "Testing Confirm Credentials" and 0018 "Testing Biometric Authentication" are deprecated and "covered_by" the tests above. Knowledge page: MASTG-KNOW-0001 "Biometric Authentication".
  - No v2 test yet targets a custom app PIN.
- **MASWE-0020 "Local Authentication Can Be Bypassed"** is the one that names custom PINs. Profiles L2, EUDIW; maps to MASVS-AUTH-2 and MASVS-CRYPTO-2 [C, `OWASP/maswe/weaknesses/MASVS-AUTH/MASWE-0020.md`].
  - Modes include "No Brute-Force Resistance: Allowing unlimited attempts against a custom PIN or password because the app does not track failures" and "Custom Credentials Hardcoded: Implementing a custom app PIN or password as a plain comparison in code".
  - Mitigations include "Resist Credential Guessing: Keep each attempt dependent on hardware-backed key material so guesses cannot be parallelized off-device, and combine app-defined credentials with a biometric or device-credential constraint so every attempt also requires an OS-throttled factor".
- **Related weaknesses** [C]:
  - MASWE-0021: "Allow device-credential fallback only for low-risk convenience features, never for the transactions that motivated biometric protection".
  - MASWE-0022: keys not invalidated on new enrolment.
  - MASWE-0024: re-authenticate on return from background, inactivity and absolute timeouts, clear sensitive data when the session ends.

---

## 6. PIN guessability and PIN length

### 6.1 Studies

- **Markert, Bailey, Golla, Dürmuth, Aviv, "This PIN Can Be Easily Guessed", IEEE S&P 2020** (arXiv 2003.04868v3; n = 1,220) [C].

  | Guesses allowed | 4-digit PINs found | 6-digit PINs found |
  |---|---|---|
  | 10 | 4.6 % | 6.5 % |
  | 30 | 7.6 % | 8.9 % |
  | 100 | 16.2 % | 13.3 % |

  - The differences are not significant (p = 0.16, 0.44, 0.19).
  - Six-digit PINs are weaker for the first 40 guesses.
  - A perfect-knowledge attacker gets 3.79 / 7.86 / 16.80 % of 4-digit and 6.23 / 10.30 / 15.72 % of 6-digit PINs within 3, 10 and 30 guesses (Table V).
  - Median guess number: 1,330 (4-digit), 39,389 (6-digit).
  - Most common choices: dates, "memorable" numbers, keypad patterns.
- **Extended version: "On the Security of Smartphone Unlock PINs", ACM TOPS 24(4), 2021** (n = 1,705) [C].
  - 6-digit PINs found at 10 / 30 / 100 guesses: 5.7 / 8.8 / 12.5 %.
  - "for 4-digit PINs a blocklist should contain the 1000 most popular PINs … for 6-digit PINs the 2000 most popular PINs should be blocked". That is about 10 % and 0.2 % of the PIN space.
  - "simply increasing the PIN length is of little benefit" in a throttled scenario; a significant difference appears only at 100+ guesses.
- **Blocklist effects** [C, Table VI]:
  - The iOS lists (274 four-digit, 2,910 six-digit) give "little or no benefit".
  - A 2,740-PIN list left only 1 % guessable after 100 guesses, but entry was more frustrating: 25.4 s to create a PIN versus 7.9 s.
  - A placebo list (always reject the first choice) helps only unthrottled attackers and is "not recommend[ed]".
- **Munyendo et al., "The Same PIN, Just Longer", USENIX Security 2022** (n = 1,010) [C].
  - Upgraded 6-digit PINs are "at best, marginally more secure" against 10 to 30 guesses.
  - An attacker who knows the user's old 4-digit PIN guessed "over 25 % of the PINs in just 10 attempts, and more than 30 % in 30" in the "device upgrade" group.
  - **This matters for a user who reuses secrets: a vault PIN derived from the phone or bank PIN is weak** [I].
- **Bailey et al., EuroUSEC 2023, novice guessers** [C].
  - Within 30 guesses, untrained people guessed 8.1 % of 4-digit and 9.6 % of 6-digit PINs, close to the 7.6 / 8.7 % of the data-driven attacker.
  - "Roughly the PIN of 1 in 8 strangers" was guessed.
  - More than one third of 210 participants admitted trying to unlock someone else's phone in the past year.
- **Bailey, Markert, Aviv, Signal PINs, SOUPS 2021** (n = 235) [C].
  - 44 % could not explain what the app PIN was for.
  - Casual users mostly chose short numeric PINs and reused them.

### 6.2 Published blocklists

All from `this-pin-can-be-easily-guessed.github.io/media/` [C].

- `iOS-4-digit.zip` (274) and `iOS-6-digit.zip` (2,910), extracted from iOS 10.3.
- `DD-4-digit-27`, `DD-4-digit-2740`, `DD-6-digit-29`, `DD-6-digit-291000`, built from Amitay's 204,432 four-digit PINs and RockYou, partly reordered with a Markov model.
- Attack datasets: `Amitay-4-digit`, `RockYou-4-digit` (1,780,587), `RockYou-6-digit` (2,758,490).
- **Ordering.** The small lists are exact prefixes of the large ones, so the recommended top-1000 and top-2000 should be the first 1,000 lines of `DD-4-digit-2740` and the first 2,000 of `DD-6-digit-291000` [C for the prefix property, I for the use].
- **Licence: none stated** for the lists, on the site or in the repository root (no LICENSE file). Only the bundled `iOS_blacklist_heuristic_*digit.py` scripts say "The MIT License (MIT)" [C]. Treat it as unresolved.

### 6.3 Standards on length

- **NIST SP 800-63B-4 §3.2.10 "Activation Secrets"** [C, pages.nist.gov/800-63-4/sp800-63b.html]:
  - "SHALL require the secrets to be at least four characters in length and SHOULD require … at least six"; "MAY be entirely numeric"; "SHOULD implement a blocklist".
  - Failures: "limits the number of consecutive failed activation attempts … to no more than 10 … Once the limit of attempts is reached, the authenticator SHALL be disabled, and a different authenticator SHALL be required".
  - "if a hardware-protected environment is not used, the authenticator SHALL use the activation secret to derive a key used to decrypt the authentication key".
  - "Submitting the activation factor SHALL be a separate operation from unlocking the host device … However, the same activation factor used to unlock the host device MAY be used."
- **CDD 17 §9.11.1** [C]:
  - C-SR-6: "A PIN is STRONGLY RECOMMENDED to have at least 6 digits, or equivalently a 20-bit entropy".
  - C-SR-7: a PIN shorter than 6 digits should not auto-confirm, "to avoid revealing the PIN length".
- **Mandiant to Bitwarden:** at least 6 digits (section 4) [C].

---

## 7. Attacks on the attempt counter

### 7.1 Cutting power or killing the process between a wrong guess and the count

- **IP-Box (MDSec, March 2015).** It worked on an iPhone 5s with iOS 8.1 even with "Erase data after 10 attempts" on, "by … aggressively cutting the power after each failed PIN attempt, but before the attempt has been synchronized to flash memory". Each guess took about 40 s, so up to about 111 h for every 4-digit PIN. MDSec thought it might be CVE-2014-4451 but had not confirmed it [C, mdsec.co.uk/2015/03/apple-ios-hardware-assisted-screenlock-bruteforce/].
- **Android's rule against it.** Gatekeeper and Weaver write and persist the incremented count **before** comparing, clear it only on success, and refuse to evaluate anything during a timeout (section 2.1) [C].
- **Apps can be killed at any moment.** "the system can kill the process at any time" [C, DAC `/guide/components/activities/process-lifecycle`]. A user holding the unlocked phone can also force-stop or swipe the app away [I].
  - Bitwarden counts after its key derivation, and Proton waits 2 s before counting (section 4). In both, killing the app before the count is written makes the guess free [I from C].

### 7.2 Restoring an older copy of the data

- **Hardware.** Skorobogatov (arXiv 1609.04327, 2016) desoldered and mirrored the NAND of an iPhone 5c on iOS 9 and so "bypass[ed] the limit on passcode retry attempts" by restoring the earlier retry counter [C].
- **Android's lock screen** puts the counter in TEE secure storage or RPMB (SAC Gatekeeper), or in Weaver.
- **KeyMint** offers `ROLLBACK_RESISTANCE`, which apps cannot request, and secure deletion for single-use keys (section 1) [C].

### 7.3 Changing the clock

- `System.currentTimeMillis()` "can be set by the user or the phone network … may jump backwards or forwards unpredictably". `elapsedRealtime()` is "the time since the system was booted, and include[s] deep sleep … guaranteed to be monotonic" [C, `FB/core/java/android/os/SystemClock.java:40-75`].
- `elapsedRealtime()` restarts at boot. `Settings.Global.BOOT_COUNT` (API 24) is the "Boot count since the device starts running API level 24" [C, `FB/core/java/android/provider/Settings.java:19098-19104`].
- Gatekeeper and Weaver handle a reboot by **restarting the full current timeout** without counting a new failure (section 2.1) [C].

### 7.4 Where the counter can live

| Place | Survives | Who can roll it back | Source |
|---|---|---|---|
| `filesDir` | Process death, reboot. Included in cloud backup and device transfer **unless the rules exclude it** | Root. Any backup restore that includes it. `adb backup` excludes app data for apps targeting 31+ unless debuggable | DAC `/identity/data/autobackup`; DAC `/about/versions/12/behavior-changes-12` [C] |
| `noBackupFilesDir` (API 21) | Same, and "always excluded even if you try to include them" from cloud backup, device-to-device and cross-platform transfer | Root only | DAC autobackup; `FB/core/java/android/content/Context.java` `getNoBackupFilesDir` [C] |
| Both directories are credential-encrypted storage | Unreadable after a reboot until the user unlocks the phone | — | SAC `/docs/security/features/encryption/file-based` [C] |
| Usage count of a Keystore key | keystore2's database, outside app data and outside backups | n > 1: root, since it is software in keystore2. n = 1 on hardware with secure storage: deleted in the TEE, not rollback-able. Usable from API 31 | section 1.2 [C]; root rollback of the keystore2 database [I] |
| A Keystore key rotated on every counter write | A restored older counter file no longer verifies once its key is deleted | Root can restore keystore2's database too. Costs up to about 0.4 to 0.9 s per rotation on one phone. Per-app key limit 50,000 | AndroidAPS #49 [S2]; DAC behaviour changes 17 [C] |
| Memory only (Keepass2Android QuickUnlock) | Process death wipes it, together with the keys | Nothing to restore | section 4 [C] |

**Write order these sources imply** [I, derived from SAC Gatekeeper, `IWeaver.aidl` and `gatekeeper.cpp`]:

1. Load the state. If a timeout is running, refuse without evaluating the guess.
2. Persist count + 1 together with `elapsedRealtime` and `BOOT_COUNT`, and fsync the file and its directory.
3. Only then run the KDF or the Keystore operation.
4. On success, persist zero.
5. After a reboot (different `BOOT_COUNT`, or a smaller `elapsedRealtime`), restart the full timeout for the current count.
6. If repeated identical wrong PINs are to be forgiven as Android does, keep them only in memory and for 5 minutes at most. Android never stores wrong guesses on disk.

---

## 8. Android 15 to 17 and an app's own lock screen

- **`FLAG_SECURE`.**
  - It blocks screenshots and display on non-secure screens. DAC: "For Android 11 (API 30) and lower FLAG_SECURE is able to help around 70% of the devices reliably. This is because on certain devices keyboard taps can be recorded" [C, DAC `/security/fraud-prevention/activities`]. **A PIN pad drawn by the app never opens the keyboard window, so that leak does not apply** [I].
  - It is not reliable against overlays; use `HIDE_OVERLAY_WINDOWS` (`Window.setHideOverlayWindows`, API 31) and `setFilterTouchesWhenObscured` (API 9) [C, same page; api-versions.xml].
  - At target 37, `setContentCaptureEnabled(false)` "no longer disables Content Capture"; `FLAG_SECURE` is the replacement [C, DAC behaviour changes 17].
- **Screen sharing on Android 15.**
  - "Sensitive content like password input is hidden from remote viewers" automatically.
  - Custom views can be marked with `View.setContentSensitivity` (API 35).
  - "The default system screen recorder is exempted" [C, DAC `/about/versions/15/behavior-changes-all`].
  - A drawn PIN pad is not a password field, so it needs explicit marking or `FLAG_SECURE` [I].
- **Accessibility.**
  - `View.setAccessibilityDataSensitive` (API 34) allows interaction only from services with `isAccessibilityTool` set. Views with `filterTouchesWhenObscured` default to sensitive, and descendants inherit it [C, `FB/core/java/android/view/View.java` `calculateAccessibilityDataSensitive`].
  - Compose UI 1.12.1 has `SemanticsPropertyReceiver.isSensitiveData` [C, `javap` on `ui-android-1.12.1` classes.jar].
  - Without this, an accessibility service can read which digit buttons are pressed [I].
  - Whether TalkBack still works on sensitive views depends on it declaring itself a tool [U].
- **Keyboard and autofill.**
  - A pad made of buttons has no text field, so no keyboard sees the PIN and autofill has no field to fill or save [I].
  - If a Compose text field is used instead (for the master password), Compose 1.12.1 marks password-type fields with `ContentType.Password` without being asked, and they need an explicit opt-out [S2, platform.md, from the AAR].
  - Android 17 at target 37 hides all password characters when a physical keyboard is used (`show_passwords_physical`) and no longer restores keyboard visibility after an unhandled rotation [C, DAC behaviour changes 17].
- **Biometric prompt changes.**
  - `Authenticators.IDENTITY_CHECK` (API 36): when Identity Check is on and the phone is outside trusted places, "only BIOMETRIC_STRONG sensors will be eligible … and device credential fallback will be dropped" [C, `FB/core/java/android/hardware/biometrics/BiometricManager.java:387-405`].
  - `BiometricPrompt.Builder.addFallbackOption` (API 36.1, up to 4 options) can put an app's own "Use PIN" or "Use master password" inside the system prompt. Options are hidden if `setNegativeButton` is set [C, `BiometricPrompt.java:499-530`].
- **`BiometricManager.getLastAuthenticationTime(DEVICE_CREDENTIAL | BIOMETRIC_STRONG)`** (API 35, `USE_BIOMETRIC`) returns the `elapsedRealtime` of the last phone authentication. It "may return successful authentication times even if the device is currently locked" [C, `BiometricManager.java:1023-1059`].
- **Android 17 features.** Advanced Protection Mode can be detected through `AdvancedProtectionManager` (API 36) "to automatically adopt a hardened security posture" [C, DAC `/about/versions/17/features`]. The per-app limit of 50,000 Keystore keys is described in section 1.1 [C].
- **Auto-confirm.** CDD 17 C-SR-7 discourages submitting a PIN shorter than 6 digits automatically, because that reveals its length (section 6.3) [C].

---

## 9. Unicode note for the master-password fallback on the lock screen

NFC and NFKC do **not** unify U+2018 (‘), U+02BB (ʻ), U+02BC (ʼ), U+2019 (’) and U+0027 (') [X, Python `unicodedata` 15.0]. So "o‘zbek" and "oʻzbek" yield different keys under the agreed NFC → UTF-8 encoding.

Which character Gboard's or Samsung's Uzbek layout produces for o‘ and g‘ is unknown [U]. A password containing these letters can fail to match when it is typed later on another keyboard [I].

---

## 10. Not settled

- How many phones declare `FEATURE_KEYSTORE_SINGLE_USE_KEY` or `FEATURE_KEYSTORE_LIMITED_USE_KEY`. None in the Pixel or Cuttlefish makefiles I read, and I may not use an emulator.
- How long one HMAC-SHA256 signing operation takes in the TEE or StrongBox. Not published.
- Whether vendors' own Gatekeeper and Weaver implementations adopted the new Android 17 table. CDD 17 only strongly recommends it, and it says 24 h where AOSP says 36 h at the 12th failure.
- Whether reaching keystore2 through non-SDK interfaces to set `MAX_USES_PER_BOOT` or `ROLLBACK_RESISTANCE` works on real devices. Untested, and against platform policy.
- The licence of the published PIN blocklists.
- How Uzbek bank apps verify PINs.
- Argon2id time on real phones (also open in crypto.md), and GPU Argon2id throughput (secondary figure only).
- Which apostrophe Uzbek keyboards emit.
- Whether TalkBack can operate views marked accessibility-data-sensitive.

## 11. Sources not linked inline

- **AOSP:** `https://android.googlesource.com/<project>/+/refs/heads/android17-release/<path>`. Projects: `platform/frameworks/base`, `platform/system/security`, `platform/hardware/interfaces`, `platform/system/keymint`, `platform/system/gatekeeper`, `platform/system/core`, `platform/frameworks/native` (`data/etc`), `device/google/{zuma,zumapro,gs201,gs101,cuttlefish}` (main). Gatekeeper also from `android14/15/16/16-qpr2-release` and `main`.
- **API levels:** `/home/asus/Android/Sdk/platforms/android-37.0/data/api-versions.xml` (`apiv.py`); method lists from `javap` on `android.jar`.
- **developer.android.com** (fetched today, `dac/`): `/privacy-and-security/keystore`; `/identity/sign-in/biometric-auth`; `/security/fraud-prevention/activities`; `/about/versions/{15,16,17}/behavior-changes-all`; `/about/versions/17/behavior-changes-17`; `/about/versions/{15,16,17}/features`; `/about/versions/12/behavior-changes-12`; `/identity/data/autobackup`; `/guide/components/activities/process-lifecycle`; `/reference/android/security/keystore/KeyGenParameterSpec.Builder`.
- **source.android.com:** `/docs/security/features/authentication/gatekeeper`; `/docs/security/features/encryption/file-based`; `/docs/compatibility/17/android-17-cdd`. CDD 7.0 is from the verifier's copy saved today: `scratchpad/verify-crypto-docs/cdd7.0.txt`.
- **Papers:**
  - arxiv.org/abs/2003.04868;
  - philipp-markert.com/assets/papers/tops21-6-pin-security.pdf, ssym22-233-the-same-pin.pdf, eurousec23-46-novice-pin-guessing.pdf, soups21-102-signal-pins.pdf;
  - arxiv.org/abs/2507.07927 (KeyDroid); arxiv.org/abs/2601.11745 (DroidCCT); arxiv.org/abs/1609.04327 (NAND mirroring).
- **OWASP:** `raw.githubusercontent.com/OWASP/masvs/master/controls/MASVS-AUTH-*.md`; `OWASP/mastg/master/tests-beta/android/MASVS-AUTH/MASTG-TEST-032[6-9].md`, `-0330.md`; `tests/android/MASVS-AUTH/MASTG-TEST-001[78].md`; `best-practices/MASTG-BEST-00{31,36,37,38}.md`; `OWASP/maswe/main/weaknesses/MASVS-AUTH/MASWE-00{20..25}.md`.
- **App source** (read today):
  - Bitwarden and Proton: `scratchpad/account-based/src/`;
  - KeePassDX: `scratchpad/offline/src/Kunzisoft_KeePassDX`;
  - Aegis: `scratchpad/verify-crypto-code/aegis-v343`;
  - Keepass2Android: `raw.githubusercontent.com/PhilippC/keepass2android/HEAD/src/keepass2android-app/QuickUnlock.cs`.
- **Secondary:** github.com/Baz00k/AndroidAPS/issues/49; crypto.md (GPU Argon2id rate); platform.md (Compose autofill defaults).
