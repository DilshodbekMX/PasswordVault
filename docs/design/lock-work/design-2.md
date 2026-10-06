# Design 2: how the user enters the app (everyday use first)

Date 2026-10-06. Status: proposal for the judges. Scope: setup, daily entry, recovery and the cryptography of the three secrets (master password, app PIN, fingerprint). It complements `docs/design/ux.md`, which fixes how the lock screens look; this document fixes what they do and the numbers they show.

**Angle.** The user opens the app many times a day to copy one password, the way they open a bank app. So: one daily secret (a 6-digit PIN) or a finger, an unlock that feels instant, no waiting, no surprises. On top of that, exactly what requirements R1 to R7 demand. Everything else was left out (section 13).

**How each claim is known.**

- **[F §n]**: the lock fact sheet `scratchpad/design/lock-facts/FACTS.md`, section n.
- **[R id]**: `docs/research/*.md` or a correction in `VERIFICATION.md` (V-xx).
- **[L file]**: today's emulator lab logs under `scratchpad/lab/`.
- **[C]**: an AOSP or library source I opened today (file named).
- **[X]**: my own experiment today. The scripts are in `scratchpad/design/lock-designs/d2work/`.
- **[I]**: my inference or judgement.

---

## 0. The design on one page

1. **Three secrets with three different jobs.**
   - **Master password:** the root. It opens the vault and every backup on any phone. It is written on paper and practised on a schedule.
   - **PIN:** 6 digits. It opens the app on this phone only.
   - **Fingerprint:** optional, a shortcut for the PIN, Android 9 and later.
2. **The PIN goes through a Keystore HMAC key that never leaves the phone's secure hardware.** `KEK_pin = HMAC-SHA256_Kpin("PVAULT-PIN-v1" ‖ 0 ‖ salt ‖ PIN)`, computed in the TEE, unwraps the vault key (VK).
   - There is no Argon2 on this path, so the vault opens about 0.1 to 0.3 s after the sixth digit.
   - A copy of the files cannot be used to guess the PIN at all, because the key is not in the copy.
   - The PIN is offered only when that key is hardware-backed.
3. **Five wrong PINs, then the PIN stops working.** The app deletes the PIN key and asks for the master password; after that the user chooses a PIN again.
   - There are no delays between tries, and nothing is ever wiped.
   - The screen always shows how many tries are left.
4. **Each attempt is counted before it is checked, and the count is written early.** It goes to disk when the first digit is typed, and the PIN is checked only once that write has been on disk for 500 ms.
   - A person typing never notices the 500 ms.
   - A model check shows that killing the app, rebooting or cutting power gives no extra guess (section 5.3).
5. **Fingerprint uses a per-use, Class 3, enrolment-invalidated Keystore AES-GCM key that wraps VK, through `BiometricPrompt.CryptoObject`.**
   - The prompt opens by itself, and "Use PIN" is one tap away.
   - It uses androidx.biometric 1.1.0. Fingerprint works there from Android 9 (API 28); on Android 7 and 8 the PIN is the daily route.
6. **Where state lives.**
   - The **vault file** holds only the password slot plus a new `vk_id`.
   - A **lock record** in `noBackupFilesDir` holds the PIN slot, the fingerprint slot, the counters and the practice schedule.
   - The **Keystore** holds two keys.
   - Nothing device-bound ever enters the vault file, a backup or a transfer.
7. **The master password is required:**
   - at setup;
   - on a new phone or after a reinstall;
   - after the PIN limit;
   - on practice days (days 1, 3 and 7 after it is created, then every 14 days, with 3 days of grace);
   - for every backup or export;
   - to change the master password;
   - whenever a device key is gone or failing.

   It is **not** required after a reboot.
8. **Forgetting the master password never locks the user out of a vault that the PIN still opens.**
   - With the PIN, the user can set a new master password and is pushed to make a fresh backup.
   - The price: someone who knows the PIN can do the same (section 6.2).
9. **The phone's own screen lock is not offered in version 1** (section 7). The user's "home lock like banking account" is read as the app's own PIN.
10. **Auto-lock:**
    - 30 s after leaving the app (choices: Immediately, 30 s, 1 min, 5 min);
    - at once when the screen turns off;
    - after 5 minutes without a touch;
    - always on process start.

---

## 1. What the requirements force, in one paragraph each

- **R2 rules out a PIN protected only by a software KDF.** Even at Argon2id 64 MiB with t = 3, this desktop tries 56 PINs per second, which is all 10^6 six-digit PINs in about 5 hours [F §0.4, X there]. The PIN must therefore depend on a key that a copy of the files does not contain: a Keystore key.
- **R3 rules out counting after checking.** Bitwarden and Proton Pass count after the check, and killing the app in that window makes the guess free [F §0.9]. Simply counting first is not enough either when fsync does not reach the flash chip (Pixels mount `/data` with `nobarrier` [R V-S8]). Sections 5.2 and 5.3 give the order that works.
- **R1 and R6 rule out any device-bound element on the master-password path.** That means no Keystore secret in the KDF and no deletion of vault files on any error. The lock module has no code path that writes or deletes vault files (test T13).
- **R4 rules out Argon2 on the daily path.** Argon2id costs about 0.3 to 1 s on a phone [R crypto §3]. It also rules out the system's credential screen as the daily entry, because R4 asks for a pad drawn by the app.
- **R7 rules out a reminder that can be dismissed forever.** It also rules out a reminder that locks out a user whose PIN still works (section 6.1).

---

## 2. Secrets and key hierarchy

### 2.1 Diagram

```
                      ┌──────────────── portable vault file (every generation, every backup) ───────────────┐
master password ─NFC─► Argon2id(m=64 MiB, t=3, p=4, salt 16 B) ─► KEK_mp ─AES-256-GCM─► VK (32 B random, vk_id)
                      └─────────────────────────────────────────────────────────────────────────────────────┘
                                                                                             │
6-digit PIN ─► msg = "PVAULT-PIN-v1"‖0x00‖salt‖PIN ─► HMAC-SHA256 in TEE (key Kpin) ─► KEK_pin ─AES-256-GCM─► VK
                                                                                (lock record, one wrap per vk_id)
fingerprint ─► BiometricPrompt Class 3 ─► authorises one operation of Kbio (AES-256-GCM in TEE) ─► VK
                                                                                (lock record, one wrap per vk_id)
VK ─AES-256-GCM (AAD = stored header bytes)─► vault body (entries)
```

There are no key cycles: Kpin and Kbio never leave the Keystore, KEK_mp is never stored, and VK never encrypts a key that wraps VK [I]. In particular, KEK_pin is not stored under VK; section 13, item 12 explains why.

### 2.2 Components

| Element | Algorithm and parameters | Computed where | Stored where |
|---|---|---|---|
| VK | 32 random bytes from `SecureRandom()`; `vk_id` = random UUID, new whenever VK is new | app process | VK nowhere in the clear; `vk_id` in the vault header (authenticated) |
| Password slot | Argon2id v1.3, 64 MiB / t 3 / p 4, 16-byte salt, 32-byte KEK_mp; AES-256-GCM wrap of VK, 12-byte nonce, 48-byte result | app process (argon2kt, Bouncy Castle fallback) [R crypto §1] | vault header (unchanged from crypto.md) |
| Kpin | Android Keystore HMAC-SHA256, 256-bit, `PURPOSE_SIGN`, no user authentication. `setUnlockedDeviceRequired(true)` on API 35+ only. Not StrongBox. Alias `pv_pin_<uuid>`, new for every PIN set | TEE; key material never leaves | Keystore |
| KEK_pin | `HMAC-SHA256_Kpin("PVAULT-PIN-v1" ‖ 0x00 ‖ salt[16] ‖ PIN as 6 ASCII digits)` = 32 bytes, used directly as an AES-256 key | TEE (the HMAC); the result is returned to the app | never stored; zeroed after use |
| PIN wrap | AES-256-GCM(KEK_pin, random 12-byte nonce, VK), no AAD, 48 bytes; a wrong PIN fails the tag | app process (platform JCA) | lock record, one per `vk_id`, at most 2 |
| Kbio | Keystore AES-256-GCM, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`. API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; API 28 and 29: the default per-use validity (−1). Randomised encryption (default). Not StrongBox, no `setUnlockedDeviceRequired` [R crypto §7, V-C7]. Alias `pv_bio_<uuid>` | TEE, after the biometric TA's auth token | Keystore |
| Fingerprint wrap | Kbio AES-256-GCM of VK; IV from `cipher.iv`; 48 bytes | TEE | lock record, one per `vk_id`, at most 2 |
| Body | AES-256-GCM(VK), AAD = stored header bytes [R V-C3] | app process | vault file |

**Test vector** (pins the PIN encoding; the HMAC key here is a software stand-in) [X `d2work/PinVector.java`, cross-checked with Python `hmac` and `cryptography` 41.0.7]:

```
Kpin (test only) 404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f
salt             000102030405060708090a0b0c0d0e0f        PIN 246813
message (36 B)   505641554c542d50494e2d763100000102030405060708090a0b0c0d0e0f323436383133
KEK_pin          482dcf34a49054791f72d72d2d1c536ae4f2c60b05a3b624896fdad4abe33b8e
VK               808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f
nonce            a0a1a2a3a4a5a6a7a8a9aaab
wrap (48 B)      c3b2da44cd4a122d277a3451050f47420e0ffa50ce3bbd73c6f201cb258937609a26d418cd87726a61015c8e2259b98c
PIN 246814       → AEADBadTagException
```

### 2.3 Why a Keystore HMAC key, and nothing else on the PIN path

- **The HMAC is the cheapest construction that makes every PIN guess need the phone's secure hardware.** Without Kpin, KEK_pin is an unknown 256-bit value, so the wrap in a copied record cannot be attacked offline. That satisfies R2.
- **HMAC, not AES decryption, is the right Keystore operation.** HMAC returns nothing before `finish()` [F §1.2]. If the design later adds usage-limited keys, the same operation can carry the budget (section 13, item 4).
- **Argon2 on top would not slow a rooted attacker.** That attacker pipelines the two steps: Argon2 for every PIN on a GPU (about 10 minutes for 10^6 PINs at 1,700 per second [R crypto §2]), and the TEE call on the phone. The TEE call is the bottleneck either way, and Argon2 would cost the user 0.3 to 1 s on every unlock (R4) [I].
- **The salt is a second guard, not the main one.** Kpin is already new for every PIN set. The 16-byte salt costs nothing and keeps KEK_pin distinct even if a bug ever reused an alias [I].

### 2.4 The PIN needs hardware-backed keys

After generating Kpin, read its `KeyInfo` through `SecretKeyFactory.getInstance("HmacSHA256", "AndroidKeyStore")`, which is registered [C `AndroidKeyStoreProvider.java:136`].

- On API 31+, accept `getSecurityLevel()` ∈ {`TRUSTED_ENVIRONMENT`, `STRONGBOX`, `UNKNOWN_SECURE`}.
- On API 24 to 30, accept `isInsideSecureHardware() == true` (deprecated in 31) [C `api-versions.xml`].
- Anything else means the phone keeps Keystore keys in software, so a root copy of the keystore files would allow offline PIN guessing in seconds.
  - On such a phone the app deletes the key, does not offer a PIN and says: "This phone can't keep a PIN safe. You will open the app with your master password." Fingerprint is not offered there either: Kbio's level is checked the same way.
  - CDD 7.0 requires hardware-backed AES and HMAC on every device with a secure lock screen. Only devices that launched on an earlier Android without fingerprint are exempt [F §1.1], so this case is rare [I].
- **Emulators report software keys** [L `lab/biometric/logs/bootA_PVBIO_lib1.1.0.log`: `securityLevel=0`]. Debug builds therefore have `BuildConfig.ALLOW_SOFTWARE_KEYSTORE = true`. Release builds must have it false (test R-2).

### 2.5 What one PIN guess costs an attacker

| Situation | What the attacker holds | Cost of one PIN guess | Total |
|---|---|---|---|
| **Files copied off the phone** (backup file, device transfer, forensic file-system image, old storage chip) | vault generations, lock record, settings; no Kpin | **Impossible.** The guess needs HMAC under Kpin, which never leaves the TEE [F §1.1 "can't extract them from the device"]. The attacker must guess the master password instead: one Argon2id (64 MiB, t 3, p 4) per guess, about 56 per second on a 32-core desktop [F §3] or about 1,700 per second on an RTX 4090 [R crypto §2, secondary] | The master password alone decides. Six EFF short-list words are 62.0 bits [R ux §5.3], about 4×10^7 GPU-years for half the space [I]. A user-chosen password at zxcvbn score 3 may need only 10^8 guesses: about 16 GPU-hours [I]. That is the master-password policy's problem, not the PIN's |
| **Phone stolen, no root, bootloader locked** (locked or unlocked phone) | the app's screens only; perhaps `adb shell` if USB debugging was on (no access to app files in a release build) | One of **5 charged attempts per PIN** | 5 guesses, then Kpin is deleted. Against a blocklist-aware attacker, a 6-digit PIN chosen under a small enforced blocklist fell 1 to 2 % of the time after 10 guesses (TOPS 2021, Table 7: DD-6-29 1 %, iOS-6 2 %, control 6 %) [C `lock-facts/web/tops21-6-pin-security.txt`]. Ours has 5 guesses and a larger list: I expect about 1 % or less [I]. After that, master password on the phone: Argon2 (0.3 to 1 s) per try plus a 30 s pause every 5 tries |
| **Phone rooted later, data intact** (an exploit, not a bootloader unlock, which wipes) | app files, plus use of Kpin as our uid | One TEE HMAC, about 10 to 35 ms (AES operations, 90th percentile; HMAC timing is unpublished) [F §3], so 30 to 100 guesses per second. The attacker rewrites the lock record to skip the count | All 955,499 allowed PINs in about **3 to 9 hours**; the median user PIN much sooner [I]. **On API 35+ only while the phone is unlocked**, because keystore2 wipes the unlocked-device-required super key when the phone locks [F §1.4]. Impossible once the PIN limit has deleted Kpin. **This is the weakest point of any app PIN without hardware rate limiting** (apps cannot reach Gatekeeper or Weaver [F §2.3]). It is accepted because root is outside the threat model [R platform §4] |

---

## 3. Where every piece of state lives

### 3.1 Overview

| Item | Place | In cloud backup or device transfer | Who can change it |
|---|---|---|---|
| Password slot, `vk_id`, body | vault file `filesDir/vault/vault-NNNNNNNNNN.pwv` (storage design) | as the storage design decides | app (storage module only) |
| PIN slot, fingerprint slot, attempt counters, practice schedule, notices | **lock record** `noBackupFilesDir/lock/lock.rec` | **never**: Android "always" excludes this directory [F §7.4] | app (lock module only); root |
| Kpin, Kbio | Android Keystore (keystore2 database plus TEE key blobs) | never | app; system on invalidation; root can use but not extract |
| Lock delay, clipboard delay (not secret) | settings file (owned by the settings design) | settings design | app |
| Wrong-PIN memory (for "same PIN twice", section 5.4) | process memory only, 5 minutes at most | — | — |

### 3.2 What the lock needs from the vault file

- **A `vk_id` field in the authenticated header.** It is a UUID string, new whenever VK is new and copied unchanged otherwise. The lock screen reads it from the newest readable generation's header before any secret is typed, and picks the wrap for that `vk_id`.
- **The header keeps only the password slot.** crypto.md §1 put a biometric slot in the header. This design moves every device-bound slot to the lock record, for four reasons:
  1. Turning PIN or fingerprint on or off never rewrites the vault, and every vault write is a data-loss risk [R storage §4].
  2. The attempt counter must live in a device-only record that a fall-back to an older generation cannot roll back. The PIN slot belongs in the same atomic record as its counter.
  3. V-P6 asks for every Keystore-wrapped blob and all PIN state to be in `noBackupFilesDir` [R V-P6].
  4. Backups and transfers then carry nothing that is meaningless on another phone, so there is no "strip device slots" step to get wrong [R crypto §10].
- **Reader rule for crypto.md §1.** A version-1 header has exactly one slot, of type `password`. A `biometric` slot is not written by this design.

### 3.3 Lock record format

```
"PVLOCK01"   8 bytes  magic
len          u32 BE   1 ≤ len ≤ 65,536
json         len bytes, UTF-8
sha256       32 bytes = SHA-256(magic ‖ len ‖ json)
```

The reader treats a wrong magic, length, hash, JSON syntax or field rule, or an unknown `v`, as **INVALID**. An I/O error while reading is **UNREADABLE**. Neither is ever deleted automatically: both mean "no PIN and no fingerprint this time; use the master password" (section 8.1). An INVALID record is replaced when the user sets a PIN after a master-password unlock. An UNREADABLE record is left alone, because it may read fine next time.

JSON, version 1 (every field is required; `null` where shown):

```json
{
  "v": 1,
  "pin": {
    "alias": "pv_pin_7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4",
    "salt": "<base64, 16 bytes>",
    "wraps": [ { "vk_id": "<uuid>", "nonce": "<base64, 12 bytes>", "ct": "<base64, 48 bytes>" } ],
    "failures": 0,
    "pending": false,
    "trouble_boots": []
  },
  "pin_off": null,
  "bio": {
    "alias": "pv_bio_<uuid>",
    "wraps": [ { "vk_id": "<uuid>", "nonce": "<base64, 12 bytes>", "ct": "<base64, 48 bytes>" } ],
    "trouble_boots": []
  },
  "bio_off": null,
  "need_master": null,
  "master": { "failures": 0, "pause_boot": -1, "pause_until": 0 },
  "practice": { "last_ok": 1759740000000, "step": 0 },
  "unseen": { "pin": 0, "master": 0, "last_at": null },
  "interrupted": false
}
```

Field rules:

| Field | Rules |
|---|---|
| `pin`, `bio` | `null` when not set |
| `alias` | matches `^pv_(pin\|bio)_[0-9a-f-]{36}$` |
| `wraps` | 1 or 2 entries, distinct `vk_id`, nonce exactly 12 bytes, `ct` exactly 48 bytes |
| `failures` | 0 to 4 (at 5 the PIN block is removed, section 5.1) |
| `pending` | true while an attempt is charged and unresolved (section 5.2) |
| `trouble_boots` | up to 3 distinct `Settings.Global.BOOT_COUNT` values on which this slot hit a temporary Keystore error since its last success (section 9.13) |
| `pin_off`, `bio_off` | `null`, or `{"reason": "limit" \| "gone" \| "bad_wrap" \| "master_changed", "at": <wall ms>}`, used only for the one-time explanation on the lock screen |
| `need_master` | `null`, or `{"reason": "pin_limit", "at": <wall ms>}`. While set, PIN and fingerprint are not offered |
| `master` | consecutive wrong master passwords on the lock screen, and the pause (section 5.6) |
| `practice` | `last_ok` is the wall-clock ms of the last correct master password; `step` is 0 to 3 (section 6.1) |
| `unseen` | wrong attempts not yet shown to the owner (section 5.7) |
| `interrupted` | an attempt was charged when the process died; show "interrupted" once |

### 3.4 Writing the lock record

The record is written with the same durable routine as the vault, except that it replaces one file:

1. Write `lock.rec.tmp`.
2. `fsync` the file.
3. `Os.rename(tmp, lock.rec)`, which replaces atomically.
4. Open the directory with `Os.open(dir, O_RDONLY)` and `Os.fsync` it [R storage §5.2; `Os.*` API 21 C `api-versions.xml`].

A failure at any step throws, and the write counts as not done; this is stricter than storage's rule for the directory fsync, as V-S2 asks. All writes go through one `LockRecordStore` behind a `Mutex` in the single app process.

A stale `lock.rec.tmp` is deleted on start. A record of about 1 KB with two fsyncs costs milliseconds: the storage benchmark sealed, wrote, fsynced and renamed 118 KB in a median 12 ms on this desktop, and a phone may be ten times slower [R storage §4].

### 3.5 What happens to this state when…

| Event | Vault file | Lock record | Keystore |
|---|---|---|---|
| **Master password changes** (new VK, `vk_id2`) | new generation with the new password slot and `vk_id2`. Old generations are kept until the new password is proven from disk, then retired [R V-C5, V-S5] | before the vault commit, a PIN wrap for `vk_id2` is added after one PIN confirmation; after the commit, a fingerprint wrap for `vk_id2` is added after one touch. Wraps for `vk_id1` are removed when storage retires the last `vk_id1` generation | unchanged (same Kpin and Kbio) |
| **An older generation is used** (newest damaged) | storage opens the newest generation it can verify | the PIN unwraps every wrap it has (at most 2) with one HMAC. Fingerprint unwraps only the newest `vk_id`. A generation whose `vk_id` has no wrap needs the master password it was sealed with. **A generation is skipped only because it is damaged, never because a key is missing** | unchanged |
| **Backup (encrypted export)** | a new file with its own key and salt and exactly one password slot [R crypto §10] | not exported | not exported |
| **Plain export** (CSV, JSON, .xlsx) | plaintext by design; outside R2 | not exported | — |
| **Restore on a new phone** | written from the backup with a new VK, new salt and new `vk_id` (the restore already has the typed master password) | none, so the master password was just used; then "Choose a PIN" | new Kpin (and Kbio if chosen) |
| **Device-to-device transfer** (if the storage design lets the vault file transfer) | arrives | does not arrive (`noBackupFilesDir`) | does not arrive |
| **Reinstall, "Clear storage", factory reset** | deleted by Android | deleted | deleted by Android [R platform §4] |
| **PIN changed** | unchanged | new `pin` block with new alias and salt, one wrap for the current `vk_id`, counter 0 | new Kpin; old Kpin deleted afterwards |
| **PIN limit reached** | unchanged | `pin` set to null, `pin_off = limit`, `need_master = pin_limit` | Kpin deleted |

---

## 4. PIN rules

### 4.1 Length and alphabet

**Exactly 6 digits (0 to 9). The PIN is submitted automatically when the sixth digit is typed, with no OK key.**

- **Six digits, not four.** Under a 5-try limit, four and six digits are about equally guessable: 4.6 % against 6.5 % at 10 guesses [F §6.1]. The difference appears when the limit is bypassed, which is exactly the rooted case of section 2.5: 10^4 PINs fall in 2 to 6 minutes, against 3 to 9 hours for six digits. NIST SP 800-63B-4 says SHOULD 6, CDD 17 strongly recommends 6, and Mandiant asked Bitwarden for 6 [F §6.3].
- **Fixed length, not 4 to 12.** A fixed length allows auto-submit, which is the fastest entry and the bank-app feel. CDD 17 C-SR-7 only discourages auto-submit for PINs shorter than 6 [F §6.3]. A variable length would add an OK key and a decision for the user [I].

### 4.2 Blocklist (rules, not a shipped list)

A PIN is refused if any rule matches. Rules are checked in this order, and the first match picks the message.

| Rule | Definition (d1…d6 digits; g1, g2, g3 = the three 2-digit groups) | Examples | Message key |
|---|---|---|---|
| Repeats | first half equals second half (abcabc); or ababab; or aaabbb; or a mirror (abccba); or pairs (aabbcc) | 111111, 121212, 123123, 111222, 123321, 112233 | `repeat` |
| Sequences | every step d(i+1) − d(i) is the same modulo 10; **or** g2 − g1 = g3 − g2 ≠ 0 (this second part also catches keypad pairs such as 12 45 78 and 14 25 36) | 123456, 654321, 890123, 135791, 102030, 010203, 124578, 142536 | `sequence` |
| Keypad shapes | two straight 3-key lines of the phone keypad: rows, columns, the two diagonals and 5-8-0, each in either direction | 147258, 159753, 789456, 123987 | `keypad` |
| Dates | DDMMYY: 01 ≤ MM ≤ 12, 1 ≤ DD ≤ days in month (29 for February) | 150392, 290200 | `date` |

- **Size** [X `d2work/pin_policy.py`, the reference implementation]. 44,501 of 1,000,000 PINs are refused (4.45 %) and 955,499 are allowed. By first-matching rule: date 36,355, sequence 4,886, repeat 2,980, keypad 280. The Kotlin `PinPolicy` must refuse exactly this set (test T1).
- **Coverage.** Measured against the published popularity ranking DD-6-digit-291000 (used as a test oracle only; its licence is unresolved [F §6.2]), the rules refuse the top 10 entirely, 97.0 % of the top 100, 79.4 % of the top 1,000 and 62.2 % of the top 10,000. The highest-ranked allowed PINs are 246810 (rank 24) and 135790 (rank 74) [X].
- **Why dates.** DD.MM.YY is the date format in Uzbekistan and Russia. A thief who takes a bag gets the phone *and* the passport or ID card. The PINFL personal number also begins with the date of birth as DDMMYY [I, common knowledge, not verified today]. One targeted guess must not be enough.
  - **Cost:** about 1 in 22 random choices is refused overall, 1 in 27 because of the date rule alone. The TOPS study found that only very large lists (29 % of the space) hurt creation time: 45 s against 11 s [C TOPS Table 7]. Ours is 4.45 %.
- **Why rules and not the top 2,000.** The published lists have no licence [F §6.2], and RockYou-derived rankings are US-centric (MMDDYY dates). The rules are explainable in one line each, and the reason can be shown [I].

### 4.3 What the user is told

On "Choose a 6-digit PIN" (UX §5.6), before typing:

> "You will use this PIN to open the app on this phone, like a bank app.
> Don't use: birthdays or other dates, the same digit many times, or easy patterns like 123456 or 112233.
> Don't use your phone's unlock PIN or your bank card PIN. Other people may know them."

When a rule refuses the sixth digit (the dots clear and the keypad stays):

| Key | Text |
|---|---|
| `date` | "This looks like a date (day, month, year). Dates such as birthdays are easy to guess. Choose another PIN." |
| `repeat` | "This PIN repeats digits in a pattern. Choose another PIN." |
| `sequence` | "These digits follow a simple sequence. Choose another PIN." |
| `keypad` | "This PIN is a shape on the keypad. Choose another PIN." |
| mismatch on "Enter the PIN again" | "The PINs don't match. Try again." After 3 mismatches the app goes back to the first entry |

There is no click-through: TOPS found that non-enforcing lists gave the attacker 7 % instead of 2 % at 10 guesses (iOS-6-wC against iOS-6-nC) [C TOPS Table 7].

---

## 5. The attempt counter

### 5.1 Limit, delays, resets

| Item | Decision | Reason |
|---|---|---|
| Limit | **5 consecutive wrong PINs** | PSD2 RTS Art. 4(3)(b) caps banks at 5; NIST at 10 or fewer, then another authenticator [F §0.7–0.8]. Proton uses 3 [F §4]. With fingerprint as the daily route, 5 leaves room for typos [I] |
| Delays between tries | **None** | The total is fixed at 5. Delays would only make the owner wait without lowering the attacker's total [I]. The Gatekeeper schedule exists because the phone allows 20 or more tries [F §2.2] |
| What counts | a guess that was evaluated and failed; also a charged attempt whose process died (section 5.2) | R3 |
| What does not count | the same wrong PIN again within 5 minutes (5.4); an attempt that a temporary Keystore or storage error stopped before the HMAC returned; a PIN refused by the blocklist at creation | nothing was learned [I] |
| What resets it to 0 | a correct PIN, a successful fingerprint unlock, or a correct master password | a proven owner; Android and NIST count *consecutive* failures [F §0.7] |
| At the limit | 1. Write the record with `pin` null, `pin_off = {limit}` and `need_master = {pin_limit}`. 2. Delete Kpin. 3. Fingerprint is not offered until a master-password unlock. 4. The lock screen asks for the master password. 5. After unlock: "Choose a new PIN" (the same digits are allowed) | R3; the deleted key means a later root rollback of the record finds nothing to guess against [I]; requiring the master password once is also practice (R7) |
| Shown to the user | "Wrong PIN. 4 tries left." … "Wrong PIN. 1 try left. After that you'll need your master password." If the screen opens with failures above 0: "3 tries left before you need your master password." | PSD2 Art. 4(4): alert before the block [F §4]; fewer surprises [I] |
| Never | wipe, deletion of vault files, or a growing delay for the master password | R6, R1 |

### 5.2 Protocol: charge early, check late

**Rule:** a PIN is evaluated only if an increment of the counter (`pending = true`) has been on disk, written with the full routine of section 3.4, for at least `CHARGE_AGE = 500 ms`. The charge is written when the **first** digit is typed, so for a person typing it is already old enough when the sixth digit arrives.

Numbered steps (the lock engine holds its `Mutex` throughout):

1. **Process start.** Read the record.
   - If `pin.pending`, write `failures + 1`, `pending = false`, `unseen.pin + 1` and `interrupted = true`. If that makes 5, apply the limit (5.1).
   - Do this before any lock screen is drawn.
2. **First digit** (no charge held, and `failures < 5`). Write `pending = true`. When the write returns, store `chargedAt = SystemClock.elapsedRealtime()` in memory.
   - Digits typed meanwhile are buffered and show as dots.
   - If the write fails: clear the digits and show "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your master password." This is temporary, and nothing is counted.
3. **Digits cleared, or the screen is left without a submission** (backspace to empty, "Use master password", the fingerprint key or prompt opening, `ON_STOP` of the lock screen, back). Write `pending = false` (a refund) and drop the charge.
4. **Sixth digit.**
   - a. If the PIN is a recent wrong guess (5.4), refund as in step 3 and show "That's the PIN you just tried."
   - b. Wait until `elapsedRealtime() ≥ chargedAt + 500 ms`. For a person this is 0 ms in practice.
   - c. `Mac.init(Kpin)` and `doFinal(message)` in the Keystore.
     - **Exception before a result:** refund as in step 3 and classify (section 10). TEMPORARY means "use your master password this time". GONE means the PIN slot is removed and the master password is asked.
   - d. Unwrap every wrap with KEK_pin (software AES-GCM), then zero KEK_pin.
   - e. **No tag verifies (wrong PIN).**
     - Write `failures + 1`, `pending = false` and `unseen.pin + 1`.
     - Then show "Wrong PIN. n tries left." Remember the guess in memory (5.4).
     - At 5, apply the limit.
   - f. **A tag verifies (right PIN).** Do two things at once:
     - (i) storage opens the newest generation with the VK of its `vk_id`;
     - (ii) write `failures = 0`, `pending = false`, `trouble_boots = []`.

     Show the list when both are done; the write takes milliseconds. If (ii) fails, show the list anyway. The pending charge then counts at the next start; this is rare and harmless [I].
   - g. **The vault cannot be opened with this VK** (damaged; storage's error flow). The PIN was right, so (ii) still runs.

Kotlin-shaped sketch of the core (names are illustrative):

```kotlin
suspend fun onFirstDigit() = mutex.withLock {
    if (charge != null || rec.pin == null || rec.pin.failures >= LIMIT) return@withLock
    store.write(rec.withPin { it.copy(pending = true) })        // temp + fsync + rename + dir fsync; throws on failure
    charge = Charge(chargedAt = clocks.elapsedRealtime())
}

suspend fun submit(pin: ByteArray): PinOutcome = mutex.withLock {
    val c = charge ?: return@withLock PinOutcome.NotCharged      // UI bug: first digit must charge
    if (recentWrong.matches(pin)) { refundLocked(); return@withLock PinOutcome.SameAsBefore }
    clocks.delayUntilElapsed(c.chargedAt + CHARGE_AGE_MS)        // 500 ms; normally already passed
    val kek = try { pinKeys.hmac(rec.pin.alias, pinMessage(rec.pin.salt, pin)) }
              catch (e: Exception) { refundLocked(); return@withLock classifyPinKeyError(e) }
    val vks = rec.pin.wraps.mapNotNull { w -> aesGcmUnwrapOrNull(kek, w.nonce, w.ct)?.let { w.vkId to it } }
    kek.fill(0)
    charge = null
    if (vks.isEmpty()) {
        val f = rec.pin.failures + 1
        if (f >= LIMIT) { store.write(rec.pinLimitReached(clocks.wall())); pinKeys.deleteQuietly(rec.pin.alias); PinOutcome.Limit }
        else { store.write(rec.withPin { it.copy(failures = f, pending = false) }.unseenPin()); recentWrong.add(pin); PinOutcome.Wrong(LIMIT - f) }
    } else PinOutcome.Right(vks)   // caller opens the vault and calls markSuccess() in parallel (step 4f)
}
```

### 5.3 Why this order: model check

`d2work/counter_model.py` explores every interleaving, up to 22 to 26 steps, of these actions:

- the protocol's own steps;
- process kill (all fsynced writes survive);
- power cut (only writes handed over at least one settle period earlier survive; this models a drive cache that `fsync` with `nobarrier` does not flush [R V-S8]);
- restart;
- waiting.

Every action except waiting takes zero time, as scripted input such as `adb shell input text` does. The attacker never knows the PIN. Property: the number of evaluations is at most the limit [X].

| Protocol | Durable fsync (settle 0) | Drive cache needs time (settle 1) |
|---|---|---|
| Check, then count (Bitwarden and Proton order [F §4]) | **violated** by kill (8 evaluations against a limit of 2) | **violated** |
| Count at submit, then check (Gatekeeper order [F §2.1]) | holds | **violated** by power cut (9 against 2) |
| Charge at first digit, check at once | holds | **violated** (6 against 2) |
| **Charge at first digit, check only after the charge has aged (this design)** | holds | **holds** (limits 2 and 3 checked) |

### 5.4 The same wrong PIN twice

Like Android 17's `SoftwareRateLimiter`, which drops duplicate wrong guesses held in memory only, for 5 minutes [F §2.2]:

- The app keeps up to 4 recent wrong guesses as `SHA-256(sessionNonce ‖ PIN)`, where `sessionNonce` is 16 random bytes per process. They are kept for 5 minutes (`elapsedRealtime`) and cleared on success, on PIN change and on process death.
- A matching guess is refused before the Keystore call and is not counted.
- It gives no information, because it was already evaluated [I]. It saves the owner who retypes a PIN thinking they made a typo [I].

### 5.5 What a reset clock, a cleared cache or a reboot does

| Action | Effect |
|---|---|
| **Change the wall clock** | nothing: the charge age uses `elapsedRealtime`, and the count itself uses no clock |
| **Clear cache** | nothing: no lock state is in `cacheDir` |
| **Reboot during an attempt** | the charge is on disk, so step 1 counts it |
| **"Clear storage"** | deletes the vault, the record and the keys together: no guess gained, only the owner's data destroyed, which a factory reset also does |
| **`bmgr` or `adb restore` of an older record** | impossible: `noBackupFilesDir` is never backed up [F §7.4], and `adb backup` excludes app data at target 31+ [F §7.4] |

**Residual risks** [I]:

1. **Root** can rewrite the record (section 2.5).
2. **A drive that keeps a write in a volatile cache for more than 500 ms after fsync returns.** No source gives cache-flush timing, so 500 ms is a judgement; it widens the window about 5 to 15 times over count-then-check, whose window is the 30 to 100 ms of the check itself. Raising `CHARGE_AGE` to 1 s costs nothing for typical typists, only for very fast ones.

### 5.6 Wrong master passwords on the lock screen

- **Charged before Argon2** (`master.failures + 1`) and reset to 0 on success.
- **After every fifth consecutive failure** (5, 10, 15 …) the field is disabled for **30 s**. Text: "Wait 30 seconds, then try again. Take your time and look at your paper."
- **The pause is stored as `pause_boot` (BOOT_COUNT) and `pause_until` (elapsedRealtime).** After a reboot, or when BOOT_COUNT cannot be read, a pending pause restarts in full, as Gatekeeper does [F §2.1].
- **It never grows and never blocks.** R1 holds because the master password always works after at most 30 s [I].
- **Effect:** scripted guessing through the screen drops to about 10 per minute. Score-3 passwords need 10^8 or more guesses, so the screen is not the attack path [I].
- **A failed write does not block the master password** (R1). The pause does not apply to opening a backup file: the attacker has that file anyway.

### 5.7 Notices to the owner

- **Each wrong PIN and wrong master password increments `unseen`** and stamps `last_at`.
- **After any successful unlock,** the app subtracts the failures of the current lock episode, which the user just saw happen. If anything remains, it shows once:
  > "Someone typed a wrong PIN 3 times before you opened the app (last time today at 14:32). If it wasn't you, keep your phone with you and change your PIN."

  Then `unseen` is reset.
- **After an interrupted attempt** (`interrupted`), the PIN screen shows: "Your last PIN entry was interrupted and counted as a try. 4 tries left."

---

## 6. When the master password is required

### 6.1 The cases

| Case | Master password? | Risk addressed | Annoyance |
|---|---|---|---|
| First setup | yes: created, written down, typed again (UX §5.3–5.5) | the root of recovery | once |
| New phone, reinstall, transferred vault, lock record absent, invalid or unreadable | yes, then "Choose a PIN for this phone" | nothing device-bound exists or can be trusted | once per phone |
| PIN limit reached | yes; fingerprint is blocked until then | somebody guessed 5 times | rare |
| Practice due (days 1, 3 and 7 after the password is created or changed, then every 14 days) | the lock screen opens on the master password field. For 3 days "Use PIN this time" is offered; after that it is required, with the escape in 6.2 | R7 | about 2 times a month after the first week |
| Backup (encrypted) | yes, every time | the backup must open with a password the user *knows now*; it also seals the backup with a fresh salt [R crypto §11]; practice | each backup; also counts as practice |
| Plain export (CSV, JSON, .xlsx) | yes, every time (UX §5.20; Bitwarden does the same [R ux]) | bulk plaintext leaving the phone | rare |
| Change master password | the current one (or 6.2) | takeover by someone holding an unlocked app | rare |
| Change PIN, turn fingerprint on | current PIN, or master password instead | someone holding the unlocked app within its 30 s grace | rare |
| Turn fingerprint off, change lock delay, other settings | nothing | these only reduce access or are low-risk | — |
| PIN key gone (invalidated, deleted) | yes, unless fingerprint still works; then "Choose a PIN" | the PIN slot cannot work | rare |
| Fingerprint key gone (new fingerprint enrolled, screen lock removed) | no: the PIN is used; fingerprint can be turned on again with the PIN | the fingerprint slot cannot work | occasional |
| Keystore temporarily failing, storage full | yes, **this time only**, and nothing changes | R1: the master password still works | rare |
| No hardware-backed Keystore | every time | R2 (section 2.4) | only on rare old phones |
| **After a reboot** | **no** | none in particular: our app cannot even start before the user unlocks the phone, because the files are credential-encrypted [F §7.4] | weekly reboots would mean weekly master passwords for nothing |
| After an app update | no | — | — |

**Practice schedule (exact).**

- **Due time.** `due = practice.last_ok + [1 d, 2 d, 4 d, 14 d][step]`, so the asks fall on days 1, 3, 7, 21, 35 and so on after creation.
- **What counts as practice.** Every correct master password counts: unlock, practice, backup, export or change. It sets `last_ok = now`, and `step = min(step + 1, 3)` if at least 20 h have passed since the previous `last_ok`.
- **Reset.** A new master password (setup, change or 6.2) sets `step = 0`. A restore on a new phone sets `step = 3`, because the user just proved they know it.
- **Grace.** While `now < due + 3 d`, the master-password screen offers "Use PIN this time", which leads to the PIN pad with the fingerprint key. There is no automatic fingerprint prompt.
- **After grace.** The PIN and fingerprint shortcuts are gone until the master password is typed, apart from 6.2.
- **Wall clock.** The schedule uses the wall clock. If the clock jumps back more than a day, practice is treated as due, so one ask happens. The schedule is a memory aid, not a guess limit, so clock tricks gain an attacker nothing: the PIN count is clock-free (5.5).
- **Why the master password replaces the PIN on those days** rather than being asked after it: one secret per unlock, typed where people expect to type one [I]. UX §5.10 already has the subtitle: "We ask for it every 2 weeks so you don't forget it. You need it for backups and for a new phone."

### 6.2 Forgotten master password while the PIN still works

**Where it appears.** "Forgot master password?" on the master-password screen, and "I don't remember it" in Settings › Change master password, offer **"Choose a new one with your PIN"** whenever a PIN slot is usable.

**The flow:**

1. Explanation: "You can choose a new master password because your PIN still opens the app on this phone. Backups you made before will still need your old master password."
2. The PIN, typed fresh. This is a counted attempt.
3. The flow continues as in 9.7, steps 2 to 9. It needs no extra PIN confirmation, because KEK_pin was just derived.
4. Then: "Make a backup now. Your old backups need the old master password."

**Trade-off, stated plainly.**

- **What it costs.** Anyone who knows the PIN and holds the phone can replace the master password. They can then export everything, or later lock the owner out by also burning the 5 PIN tries.
- **Why it is still the right default.** Without this path, a user who forgot the master password lives with a vault that dies at the next phone loss or PIN slip. Forgetting is common. A malicious PIN-holder can already read and copy every entry one by one.
- **Who decides.** If the security judge disagrees, deleting this path changes nothing else in the design.

If no PIN is usable, the sheet offers only what UX §5.10 describes: try again, restore an older backup, or "Start over" (9.15).

### 6.3 Which secret each action asks for

| Action | Secret |
|---|---|
| Open the app | PIN, fingerprint or master password (section 8.1 decides which is offered) |
| Copy, view or edit entries | none (unlocked) |
| Make a backup; plain export | master password |
| Change master password | current master password, or the PIN through 6.2 |
| Change PIN | current PIN, or master password |
| Turn fingerprint on | current PIN or master password, then a fingerprint touch |
| Turn fingerprint off; lock delay; clipboard delay | none |
| Import (CSV, JSON) | none; a backup file asks for *its* master password |
| Start over (9.15) | none, but nothing is deleted |
| Delete an archived old vault | that vault's master password |

---

## 7. The phone's own screen lock ("home lock")

**Decision: not offered in version 1.** The lock is the app's own PIN pad, which is what bank apps do and what R4 asks for. The open question is put to the user (section 14).

**Why not** [I unless tagged]:

1. **R4 asks for a pad drawn by the app.** A device-credential unlock shows the system's credential screen, not our pad.
2. **Family members often know the phone's PIN** (UX §5.6). Even a PIN merely derived from a known one is weak: knowing the old PIN let attackers guess more than 25 % of "upgraded" PINs in 10 tries [F §6.1, Munyendo].
3. **Wrong tries count against the phone itself.** The system's credential screen reports them to `LockSettingsService`, so they count toward the phone's lockout and any "wipe after N failures" policy [F §1.3].
4. **On API 24 to 29 only time-bound keys exist.** Any recent phone unlock then authorises the key without any prompt [F §1.3]. OWASP MASTG-TEST-0330 flags validity above 0, and MASWE-0021 limits device-credential fallback to low-risk features [F §5].

**How it would work if the user meant it** (as an *additional* way in on API 30+; the master password stays the root and the app PIN stays):

| API | Mechanism |
|---|---|
| 30–37 | A third device slot: a Keystore AES-256-GCM key with `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)`, wrapping VK and stored in the lock record like the fingerprint wrap. Unlock through `BiometricPrompt` with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` and a `CryptoObject`; no negative button, because it cannot be combined with `DEVICE_CREDENTIAL` [F §1.3; R platform §3.10]. Gatekeeper or Weaver throttle the attempts in hardware [F §2]. The key is invalidated when the screen lock is removed or force-reset [F §1.3]; that is handled as GONE (section 10). On API 36+ with Identity Check outside trusted places, the credential fallback is dropped [F §8] |
| 28–29 | `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is unsupported, and `DEVICE_CREDENTIAL` alone needs API 30 [R platform §3.10]. Only a time-bound key, `setUserAuthenticationValidityDurationSeconds(N)` with `KeyguardManager.createConfirmDeviceCredentialIntent` (deprecated in 29). **Not offered** |
| 24–27 | Same as 28–29. **Not offered** |

---

## 8. Lock screen, states and settings

### 8.1 Which screen appears (first matching row wins)

| Condition | Screen |
|---|---|
| No vault file | Welcome (setup or restore), UX §5.1 |
| Lock record absent or INVALID | Master password. Subtitle "Type your master password." After unlock: "Set up a PIN for this phone?" |
| Lock record UNREADABLE (I/O error) | Master password with the TEMPORARY text "Your PIN can't be checked right now. Use your master password this time." No set-up offer and no rewrite, because the record may read fine next time |
| `need_master` | Master password. Subtitle "Your PIN was entered wrong 5 times, so it was turned off. Type your master password, then choose a new PIN." |
| Practice due, within grace | Master password, "We ask for it every 2 weeks…", `TextButton` "Use PIN this time" |
| Practice due, after grace | Master password, the same subtitle; "Forgot master password?" includes 6.2 |
| PIN usable (block present, `failures < 5`, a wrap for the newest `vk_id`, `getKey` not null) | **PIN pad.** The fingerprint key shows if fingerprint is usable; the prompt opens by itself (8.4) |
| PIN not usable, fingerprint usable | The fingerprint prompt opens by itself; behind it, "Use master password" (no pad) |
| Neither | Master password, with the reason from `pin_off`, `bio_off` or the temporary error |

The lock screen reads only the record, the newest readable generation's header (for `vk_id`) and the generation bytes (prefetched, still encrypted). It never shows anything about the vault, such as the number of accounts (UX §5.9).

### 8.2 States and wording (the look is in UX §5.9 and §5.10)

| State | What the user sees | Notes |
|---|---|---|
| Idle | "Enter your PIN", 6 empty dots, keypad, fingerprint key (if on), "Use master password" | — |
| Entering | dots fill; no digits are ever shown | the charge is written at the first dot |
| Checking | the keypad is replaced by a progress indicator and "Opening…" | normally under 0.3 s |
| Wrong | shake (skipped when animations are off), reject haptic, dots clear, "Wrong PIN. 4 tries left." | the last try adds "After that you'll need your master password." |
| Same PIN again | "That's the PIN you just tried." | not counted |
| Interrupted | "Your last PIN entry was interrupted and counted as a try. 4 tries left." | once |
| PIN off at limit | the master-password screen with the `need_master` subtitle | — |
| PIN can't be checked (temporary) | "Your PIN can't be checked right now. Use your master password this time." | nothing is counted or changed |
| Storage full | "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your master password." | — |
| PIN key gone | "Your PIN stopped working on this phone. Type your master password, then choose a PIN again." | — |
| Fingerprint changed (KPIE) | "Fingerprint unlock was turned off because fingerprints on this phone changed. Use your PIN. You can turn it on again in Settings." | UX §5.9 text |
| Fingerprint locked by the system | "Fingerprint is locked for now. Use your PIN." | ERROR_LOCKOUT or LOCKOUT_PERMANENT |
| Fingerprint unavailable (temporary) | the fingerprint key is hidden for this episode; nothing else | — |
| Master password checking | "Opening…" (Argon2, 0.3 to 1 s) | — |
| Master password wrong | "That's not the right master password." Adds "Your keyboard may be set to Russian." if Cyrillic letters were typed. Adds "If your password has oʻ or gʻ, the apostrophe may be a different character on this keyboard." if any of ' ‘ ’ ʻ ʼ was typed (FACTS §9: NFC does not unify them) | — |
| Master password pause | the field is disabled with a countdown of at most 0:30 | 5.6 |
| Older copy opened | the storage design's dialog (UX §5.9) | — |

### 8.3 The PIN pad

**Input.**

- A 3 × 4 grid of app-drawn keys (UX §5.9): no `TextField`, no IME and no autofill node [F §8], and never shuffled.
- Hardware keyboard: digits and numpad digits enter; Backspace deletes; Enter is ignored.
- Long-press Backspace clears everything, which refunds the charge (5.2, step 3).

**Memory.**

- The digits live in a `ByteArray(6)` in the ViewModel, never in saved state. It is zeroed after submit, clear or lock.
- Rotation keeps the digits and the charge, because the ViewModel survives.

**Protection.**

- `FLAG_SECURE`, `filterTouchesWhenObscured`, and `setHideOverlayWindows(true)` on API 31+ (platform decisions).
- Keys and dots carry Compose `semantics { isSensitiveData = true }`, which has effect on API 34+ [F §8; R V-P5]. This must pass test E12 with TalkBack; if TalkBack cannot operate the pad, the flag is dropped from the keys and kept on the dots.

**Accessibility.** The dots read "PIN, 3 of 6 digits entered". The result is announced through a polite live region (UX §8).

**Speed budget.**

| Step | Time | Source |
|---|---|---|
| Charge-age wait | 0 ms for normal typing | — |
| HMAC | about 10 to 35 ms | [F §3, AES proxy] |
| Unwrap | under 1 ms (a software HMAC plus the unwrap took 7.5 µs on this desktop) | [X] |
| Body decrypt and parse | storage's budget | — |

Target: sixth digit to list in **≤ 500 ms at the 95th percentile on a mid-range phone with 1,000 entries**, measured in test D1. Typical: 0.1 to 0.3 s [I].

### 8.4 The fingerprint prompt

**When it opens.**

- It opens automatically **once per lock episode**, when the lock screen is RESUMED in PIN mode and fingerprint is usable for the newest `vk_id`.
- It does not open automatically:
  - on practice days;
  - with `need_master`;
  - after the user dismissed it in this episode;
  - after ERROR_LOCKOUT in this episode.
- The fingerprint key on the pad opens it again.

**Order.**

1. Call `getKey`.
2. Call `Cipher.init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))`. Errors here are classified (section 10) **before** any prompt is shown.
3. Call `authenticate(promptInfo, CryptoObject(cipher))`:
   - title "Open Password Vault";
   - negative button "Use PIN";
   - `setAllowedAuthenticators(BIOMETRIC_STRONG)`;
   - confirmation left at its default (true; MASTG-TEST-0329 [F §5]).

**Hosting.** The `BiometricPrompt(FragmentActivity, executor, callback)` object is created in `onCreate` of the activity, every time. Created lazily, the lab saw the callback delivered to the destroyed activity after rotation [L `bootA_PVBIO_lib1.1.0.log` 11:41:23–28]. The pending operation (unlock or enrol) lives in a ViewModel.

**On success.** The app takes `result.cryptoObject?.cipher`; if it is null, the result is TEMPORARY. It never treats the success callback as a yes/no answer (Cure53 BWN-11-005 [R account-based]). Then `doFinal(ct)`, which must give 32 bytes, followed by the vault open. A fingerprint success also resets the PIN `failures` to 0.

**Availability (biometric 1.1.0)** [L `old-android/logs`, `biometric/logs`; R V-P1]:

| API | Behaviour |
|---|---|
| 24–27 | `canAuthenticate(BIOMETRIC_STRONG) = ERROR_NO_HARDWARE` and `authenticate` fails at once. Not offered; Settings says "Fingerprint unlock needs Android 9 or newer" |
| 28–29 | `canAuthenticate` returns `STATUS_UNKNOWN`, but the CryptoObject round trip worked on the API 28 emulator. Offered when `FEATURE_FINGERPRINT` is present and the phone is secure; turning it on (9.9) decides |
| 30–37 | normal; worked on API 37 |

**Why 1.1.0 is enough.** The 1.4 alpha would add fingerprint only on Android 7 and 8, where the PIN already gives a bank-like unlock. Its context-based API also opens a separate activity that may land outside our task without `FLAG_SECURE` [R V-P1]. So the alpha is **not necessary** (R5) [I].

### 8.5 Auto-lock

| Setting | Values | Default |
|---|---|---|
| Lock after leaving the app | Immediately · 30 seconds · 1 minute · 5 minutes | **30 seconds** (UX §5.18) |
| Lock when the screen turns off | always | — |
| Lock after inactivity while open | 5 minutes without touch or key input, always | — |
| Lock on process start | always | — |

Mechanics:

- **Leaving the app.** Record `stoppedAt = elapsedRealtime()` at `ProcessLifecycleOwner` `ON_STOP`. At `ON_START`, the app is locked if `elapsedRealtime() − stoppedAt ≥ delay`, or if the process is new [R platform §3.3].
- **Screen off.** At `ON_STOP`, if `PowerManager.isInteractive()` is false (API 20), the app locks at once. A context-registered `ACTION_SCREEN_OFF` receiver does the same while the process lives.
- **Screens the app opens itself** (the file picker, the share sheet) stop the process lifecycle [R V-P3]. While one is open, the delay does not run, for at most 10 minutes; screen-off still locks.
- **A missing or unreadable delay setting** counts as "Immediately" [R V-P9]. The default is written explicitly at setup.
- **On lock:** VK, entries and any KEK are dropped and our arrays are zeroed. The route (UUIDs) is kept for return (UX §4).

---

## 9. Flows

### 9.1 First setup

1. Welcome, then "Start" (UX §5.1).
2. Master password: suggested or own, written down, typed again (UX §5.3–5.5).
   - Argon2id, then VK, `vk_id` and the password slot.
   - Storage writes generation 1, then reads it back and opens it [R V-C5].
   - Vault unlocked in memory; `practice = {last_ok: now, step: 0}`.
3. **Can this phone hold a PIN?**
   - Generate Kpin (alias `pv_pin_<uuid>`) and check its level (2.4).
   - If it is not secure, delete it, show "This phone can't keep a PIN safe. You will open the app with your master password." and go to step 7.
   - If generation throws, show "The PIN can't be set up right now" with "Try again" and "Skip".
4. "Choose a 6-digit PIN": blocklist (4.2), then "Enter the PIN again", which must match.
5. **Create the slot.**
   - Generate a salt.
   - Compute KEK_pin by HMAC in the Keystore.
   - Wrap VK with a random nonce.
   - Write the record `{pin: {alias, salt, wraps: [{vk_id, nonce, ct}], failures: 0, pending: false}, practice}`.
6. **Verify.**
   - Recompute the HMAC from the typed PIN, unwrap, and compare with VK in memory.
   - If the check fails: delete Kpin, write `pin: null`, and show "Couldn't set up the PIN. Try again." Zero the PIN digits and KEK_pin.
7. **Fingerprint offer** (UX §5.7), only if 8.4 allows it. Run 9.9 from step 3; no extra PIN is needed, because the master password was just typed.
8. "Make backups" (UX §5.8), then "Finish", then the list.

If the app dies after step 2, the next start finds a vault and no record. It asks for the master password and resumes at step 3 (UX §4).

### 9.2 Daily unlock with the PIN

1. The app returns to the foreground or starts; 8.5 decides that it is locked.
2. On a process start the lock engine first runs 5.2 step 1. In every case it reads the record and the newest header (`vk_id`), prefetches the generation bytes, and calls `getKey(pin alias)`.
   - null: GONE (9.13b).
   - Exception: TEMPORARY (9.13c).
3. If fingerprint is usable, its prompt opens (9.3). The user can tap "Use PIN" instead.
4. **First digit:** charge (5.2, step 2).
5. **Sixth digit:** duplicate check, the charge age, HMAC, unwrap (5.2, step 4).
6. **Right:** storage opens the newest generation with its VK while the record resets the counter (5.2, step 4f).
7. The list (or the remembered screen) appears. The 5.7 notice appears if any failures from earlier episodes are unseen.

### 9.3 Daily unlock with fingerprint

1. The lock screen is RESUMED with fingerprint usable (8.1); steps 1 and 2 of 8.4 succeed.
2. The prompt appears by itself. The user touches the sensor.
3. `onAuthenticationSucceeded`: `cipher.doFinal(ct)` gives 32 bytes.
4. **The VK opens the newest generation.** Then:
   - write the PIN's `failures = 0` and the fingerprint slot's `trouble_boots = []` (a PIN charge cannot be pending: opening the prompt refunded it, 5.2 step 3);
   - show the list and the 5.7 notice.
5. **On errors:**
   - "Use PIN", back, or cancel: the PIN pad.
   - ERROR_LOCKOUT (7) or ERROR_LOCKOUT_PERMANENT (9): "Fingerprint is locked for now. Use your PIN." The lab saw `canAuthenticate` still return SUCCESS during lockout, so it cannot be checked in advance [L `bootA` 11:53:46].
   - Codes 1, 2, 3, 4, 8, 11, 12, 14 and 15: TEMPORARY; hide the key for this episode.
   - `doFinal` throws `AEADBadTagException`: GONE, reason `bad_wrap` (the lab produced this with a tampered blob [L `bootA` 11:45:57]).
   - Any other exception: TEMPORARY.
6. A non-matching finger (`onAuthenticationFailed`) is handled by the system's own counter: 5 tries, then 30 s [F §2.2]. The app counts nothing.

### 9.4 A wrong PIN

1. Steps 1 to 5 of 9.2. No wrap verifies.
2. The record gets `failures + 1`, `pending = false` and `unseen + 1`, written durably before the message.
3. Shake, reject haptic, dots cleared, "Wrong PIN. n tries left." The guess is remembered in memory for 5 minutes.
4. The next first digit charges again, and so on. Typing the same wrong PIN again gives "That's the PIN you just tried." and costs nothing.

### 9.5 The PIN limit

1. The fifth counted failure, or a pending charge found at start that makes 5.
2. Write the record: `pin = null`, `pin_off = {limit, at}`, `need_master = {pin_limit, at}`. Then delete Kpin (ignore errors; the orphan clean-up in 9.13 retries).
3. The master-password screen with the `need_master` subtitle. The fingerprint prompt is not offered.
4. **On a correct master password:** clear `need_master` and `master.failures`, update practice, and show the 5.7 notice ("Someone typed a wrong PIN 5 times …").
5. "Choose a new PIN" (9.1 steps 3 to 6, which generate a new Kpin). "Not now" is allowed; then the app opens with the master password until a PIN is set (Settings › Security › Set up PIN). Fingerprint becomes usable again once `need_master` is cleared, provided its wrap exists.

### 9.6 Change PIN

1. Settings › Security › Change PIN.
2. "Enter your current PIN": a counted attempt by the 5.2 protocol, with "Use master password instead".
3. Choose a new PIN (blocklist), then confirm it.
4. Generate Kpin2 and salt2, compute KEK2, wrap the **current** VK, write the record with the new `pin` block (`failures 0`), and verify (as 9.1, step 6).
   - Only the current `vk_id` is wrapped. A later fall-back to an older `vk_id` needs the master password; that is rare.
5. Delete the old Kpin. If deletion fails, the orphan clean-up removes it later.
6. "Your PIN was changed."
7. If step 4 fails, the old PIN stays (the record is unchanged) and Kpin2 is deleted.

### 9.7 Change master password

1. Settings › Change master password. Type the current one: Argon2, unwrap, success. This counts as practice.
2. New master password, suggested or own (UX §5.3); write it down; type it again. Argon2 gives `KEK_mp2`.
3. If a PIN slot is usable: "Enter your PIN to keep using it on this phone." This is a counted attempt that gives KEK_pin by unwrapping the existing wrap. Alternatively "I'll choose a new PIN later", after which the PIN stops working once the vault key changes.
4. Generate VK2 and `vk_id2`. **Before** the vault changes, add a PIN wrap for `vk_id2` to the record, keeping the one for `vk_id1`, and write it durably.
5. Storage writes a generation sealed with VK2 and the new password slot. It proves it by reading it from disk and opening it with the retyped password through the normal unlock path [R V-C5].
6. If fingerprint was on: "Touch the sensor to keep fingerprint unlock on." This runs `Cipher.init(ENCRYPT_MODE, Kbio)` and the prompt, then adds a wrap for `vk_id2`. If cancelled, Settings shows "Fingerprint unlock: turn on again".
7. Practice: `step = 0`, `last_ok = now`.
8. "Backups you made before still open only with your old master password. Make a new backup now?" (UX §5.18).
9. When storage retires the last `vk_id1` generation (after the first unlock from disk with the new password [R V-S5]), the lock engine removes the `vk_id1` wraps. A slot left with no wraps is removed together with its key: `bio_off = master_changed` for fingerprint, and `pin_off = master_changed` for a PIN whose confirmation was skipped in step 3.

**If the app dies:**

| Moment | Result |
|---|---|
| Before step 4 | nothing changed |
| After step 4, before 5 | the newest vault is still `vk_id1`; the PIN still opens it (the `vk_id2` wrap is unused and removed at the next unlock) |
| After step 5, before 6 | the PIN opens `vk_id2`; fingerprint asks for re-enabling |
| After step 5, if step 3 was skipped | the PIN has no wrap for `vk_id2`, so the **new** master password is needed once, followed by "Choose a PIN" |

The PIN never opens an older generation while a newer one is intact (3.5).

### 9.8 Forgot master password, PIN still works

This is 6.2.

1. Explanation.
2. The PIN, typed fresh (counted), which gives VK and KEK_pin.
3. 9.7 steps 2 and 4 to 9, with KEK_pin from step 2, so no extra PIN confirmation.
4. Practice `step = 0`. A backup is strongly prompted.

### 9.9 Turn fingerprint on or off

**On:**

1. Settings › Security › Fingerprint unlock. If 8.4 does not allow it, the row says why:
   - "Needs Android 9 or newer";
   - "Set a screen lock and add a fingerprint in your phone's settings first";
   - "This phone's fingerprint sensor isn't secure enough".
2. Confirm with the PIN, or the master password.
3. Generate Kbio (2.2). If generation throws `InvalidAlgorithmParameterException` caused by `IllegalStateException` ("At least one biometric must be enrolled…") [L `bootA` 11:37:16], show "Add a fingerprint in your phone's settings first." Check Kbio's level as in 2.4.
4. Call `Cipher.init(ENCRYPT_MODE, Kbio)` with no IV; Keystore rejects a caller IV [L "Caller-provided IV not permitted"]. Then the prompt: "Turn on fingerprint unlock", negative "Cancel".
5. On success, `doFinal(VK)` gives 48 bytes; store `nonce = cipher.iv` (12 bytes).
6. Write `bio = {alias, wraps: [{vk_id, nonce, ct}]}` and clear `bio_off`.
7. On any error or cancel, delete Kbio and show "Fingerprint unlock could not be turned on. You can try again later." Setup is never blocked.

There is no immediate decrypt check, because that would need a second touch. A bad wrap shows up at the first use and is handled as GONE (9.3, step 5).

**Off:** write `bio = null`, then delete Kbio. No secret is needed.

### 9.10 Master-password practice

1. A lock episode starts on a due day (6.1). The master-password screen shows the subtitle and, within grace, "Use PIN this time".
2. **Correct:** unlock and update practice (`step + 1` under the 20 h rule).
3. **"Use PIN this time"** (within grace): the PIN pad. The next lock episode asks again; the user is not nagged within the same episode.
4. **After grace:** no PIN shortcut. "Forgot master password?" leads to 6.2 (PIN-authorised reset) or to the old-backup and start-over options.
5. **Wrong:** the 8.2 hints and the 5.6 pause. The "Forgot master password?" link is always visible.

### 9.11 Backup and export

1. Settings › Backup & transfer › Make a backup (or Export, then a format, then the warning; UX §5.19–5.20).
2. "Type your master password." Argon2 with the vault's slot verifies it and counts as practice. Five wrong tries give the 30 s pause of 5.6.
3. Backup: a fresh salt and key with the typed password, one password slot [R crypto §11]. Plain export: the formats of UX §5.20.
4. Write through the system file picker. The external-activity grace of 8.5 applies.
5. Read the file back and verify it before "Saved" [R V-S6].
6. The PIN and fingerprint are never used here, and nothing from the lock record is written.

### 9.12 Restore on a new phone, device transfer, reinstall

**Restore from a backup file** (first run, UX §5.2):

1. Pick the file and type its master password; Argon2 opens it.
2. Storage writes the vault with a new VK, `vk_id` and salt, and proves it.
3. Practice is `step 3` with `last_ok = now`.
4. Run 9.1 steps 3 to 8.

**Device-to-device transfer** (if the storage design includes the vault file in `<device-transfer>`):

1. The app starts with a vault and no lock record. The master-password screen says: "Type your master password. Then choose a PIN for this phone."
2. Then "Choose a PIN" (9.1 steps 3 to 7).

The old phone's Kpin and Kbio never travel, and a record never arrives [F §7.4; R V-P6].

**Reinstall or "Clear storage":** everything is gone, so the app starts at Welcome and restores from a backup. Losing the backup means losing the data (UX §7.6).

### 9.13 Keystore failures

**(a) Key invalidated.**

- Typical causes: a new fingerprint was enrolled, all fingerprints were removed, or the screen lock was removed or forced-reset [F §1.3; C `KeyGenParameterSpec.java:1777`].
- What the app sees: `KeyPermanentlyInvalidatedException` at `Cipher.init` [L `bootA` 11:48:17], or `getKey` returns null after the screen lock was removed [L `bootA` 11:51:21].
- What it does:
  1. Write `bio = null` and `bio_off = {reason: gone}`.
  2. Delete the alias.
  3. Show the 8.2 text. The PIN pad stays.

Kpin has no user-authentication binding, so fingerprint changes do not touch it. On API 35+, removing the screen lock does not delete unlocked-device-required keys any more; that bug was fixed in Android 15 [C `KeyGenParameterSpec.java:1801-1850`]. The key is therefore unaffected.

**(b) Key missing (`getKey` returns null, or KPIE at `Mac.init`).**

1. Write `pin = null` and `pin_off = {reason: gone}`.
2. Delete the alias.
3. Show the master-password screen: "Your PIN stopped working on this phone…"
4. After unlock, "Choose a PIN" (9.5, step 5).

The same applies to `bio` with fingerprint.

**(c) Keystore temporarily failing.**

- Examples: an exception from `KeyStore.load`, an `UnrecoverableKeyException` from `getKey`, a plain `InvalidKeyException` (including "Device locked" [L `bootA` 11:46:41]), or any exception from `Mac.doFinal` or `Cipher.doFinal` other than those listed in section 10.
- What the app does:
  1. Refund the charge.
  2. Change no slot.
  3. Add the current BOOT_COUNT to the slot's `trouble_boots`.
  4. Show "Your PIN can't be checked right now. Use your master password this time." The master password opens the vault as usual.
- **If `trouble_boots` holds 2 or more different boots,** the next successful unlock offers: "Your PIN hasn't worked since 4 Oct, even after restarting the phone. Set it up again?" with "Set up again" (a new key and slot) and "Keep trying".
  - The removal is the user's choice, never automatic. The reason is that some phones report invalidation as a plain `InvalidKeyException` [R V-C6].
  - The same offer applies to fingerprint.
- A Xiaomi phone that fails every Keystore call until reboot [R crypto §7] therefore costs that user a master-password entry at each unlock until the reboot, and nothing else.

**(d) Orphaned keys.**

- After a successful master-password unlock and a successful record write, the app enumerates `KeyStore.aliases()`.
- It deletes any `pv_pin_*` or `pv_bio_*` alias that the record does not reference.
- If the enumeration throws, it skips the clean-up. Clean-up never runs while the record is INVALID or UNREADABLE.

### 9.14 Return from background, process death, reboot

- **Return within the delay:** the same screen, with secrets masked again (UX §4). No lock episode starts and no prompt is shown.
- **Return after the delay, or after screen-off:** a new lock episode (9.2 or 9.3).
- **Process death, including a kill during PIN entry:** next start, 5.2 step 1 (a pending charge is counted and `interrupted` is shown), then 9.2.
- **Reboot:** the same as process death. The app's files are unavailable until the phone is unlocked, so the app does not run before that. The PIN and fingerprint work as usual; the master-password pause restarts if one was active (5.6).

### 9.15 Start over (forgot everything)

1. From "Forgot master password?", when no usable PIN exists: "Start over…", a confirmation checkbox, then "Start over" (UX §5.10).
2. **Nothing is deleted.**
   - The vault generations move to `filesDir/vault-archive/<UTC timestamp>/` (the storage design owns the details).
   - The lock record is replaced, and the old aliases are deleted.
   - Setup (9.1) starts.
3. Settings › Backup & transfer then lists "Old passwords from 6 Oct 2026, locked with an old master password", with "Open" (import with that master password) and "Delete" (needs that master password). A person who picks up the phone and taps "Start over" can therefore not destroy the owner's data [I] (R6 spirit).

---

## 10. Error classification

**Classes:**

- **GONE:** the device secret cannot work again. Remove the slot and its alias, write `*_off`, and fall back to the next way in.
- **TEMPORARY:** use the master password (or the PIN, for fingerprint) this time; change nothing except `trouble_boots`; refund any charge.
- **WRONG:** counted.
- **USER:** the user's choice.

The vault is never touched by any class (R6).

| Where | What happens | Class | Source |
|---|---|---|---|
| `KeyStore.getInstance("AndroidKeyStore").load(null)` | any exception | TEMPORARY | — |
| `getKey(alias, null)` | returns **null** | GONE | keystore2 maps `KEY_NOT_FOUND` to null [C `AndroidKeyStoreSpi.java:123-138`, `AndroidKeyStoreProvider.java` `loadAndroidKeyStoreKeyFromKeystore`] |
| `getKey` | throws `UnrecoverableKeyException` | TEMPORARY | keystore2 throws this both for `KEY_PERMANENTLY_INVALIDATED` (message only, no cause) and for any other error (with the `KeyStoreException` as cause) [C same files]. It cannot be told apart reliably, so 9.13c's user-driven re-setup covers the permanent case |
| `getKey` | returns a non-`SecretKey` | GONE | alias collision |
| `Mac.init` / `Cipher.init` | `KeyPermanentlyInvalidatedException` | GONE | raised for both `KEY_PERMANENTLY_INVALIDATED` and `KEY_NOT_FOUND` [C `KeyStoreCryptoOperationUtils.java` `getInvalidKeyException`]; [L `bootA` 11:48:17] |
| `Mac.init` / `Cipher.init` | `UserNotAuthenticatedException` | TEMPORARY (log it; not expected for these keys) | mapped from `KEY_USER_NOT_AUTHENTICATED`, `LOCKED`, `UNINITIALIZED` [C same] |
| `Mac.init` / `Cipher.init` | any other `InvalidKeyException` ("Keystore operation failed", "Device locked" −72) | TEMPORARY | default branch [C same]; [L `bootA` 11:46:41]; V-C6: never GONE [R] |
| `Mac.init` / `Cipher.init` | `InvalidAlgorithmParameterException` | TEMPORARY | the record's nonce was already checked at parse |
| `Mac.doFinal` (PIN) | any exception | TEMPORARY, refund | no result means no information [I] |
| Software AES-GCM unwrap (PIN) | `AEADBadTagException` for every wrap | **WRONG** | [X `PinVector`] |
| `Cipher.doFinal` (fingerprint, after a successful prompt) | `AEADBadTagException` | GONE (`bad_wrap`) | deterministic mismatch between key and stored wrap [L `bootA` 11:45:57] |
| `Cipher.doFinal` (fingerprint) | other exception (for example `IllegalBlockSizeException` caused by `KEY_USER_NOT_AUTHENTICATED`) | TEMPORARY | [L `bootA` 11:38:16] |
| Unwrapped VK | length not 32 | GONE (`bad_wrap`) | — |
| `onAuthenticationError` | 5 CANCELED, 10 USER_CANCELED, 13 NEGATIVE_BUTTON | USER (show the pad) | [C biometric 1.1.0 `BiometricPrompt.java:72-158`] |
| `onAuthenticationError` | 7 LOCKOUT, 9 LOCKOUT_PERMANENT | TEMPORARY with the lockout text | — |
| `onAuthenticationError` | 1, 2, 3, 4, 8, 11, 12, 14, 15 | TEMPORARY | 11 (no biometrics) is followed by KPIE at the next `init` if the key really died |
| `onAuthenticationSucceeded` | `cryptoObject` or `cipher` is null | TEMPORARY, never "unlocked" | [R account-based §1, Cure53] |
| `KeyGenerator.generateKey` (setup or enrol) | any exception | the feature is unavailable now; show the reason | [L `bootA` 11:37:16] |
| KeyInfo level SOFTWARE or UNKNOWN (release build) | — | the feature is not offered | 2.4 |
| Lock record write | `IOException`, `ErrnoException` | TEMPORARY (no charge means no PIN check now) | 3.4 |
| Lock record read | I/O error / bad content | UNREADABLE / INVALID (8.1) | 3.3 |
| `Settings.Global.BOOT_COUNT` unreadable | — | treat as a new boot | 5.6 |

`KeyStore.containsAlias()` is never used. It returns false on *any* error [R V-C6; C `AndroidKeyStoreSpi.java` `engineContainsAlias` uses `getKeyMetadata`, which returns null on every `KeyStoreException`].

---

## 11. Testing

### 11.1 JVM unit tests (no Android)

The design keeps all decisions in pure Kotlin behind these interfaces:

| Interface | JVM fake |
|---|---|
| `Clocks` (elapsedRealtime, wall, bootCount) | `FakeClocks`: all three settable; the wall clock can jump either way |
| `LockFileSystem` | `FakeFs` keeps "handed" and "durable" copies, plus `kill()` and `powerCut(settle)` |
| `PinKeystore` | `FakePinKeystore`: software HMAC with injectable `Gone`, `Temporary(e)` and `Delay`; records deletions |
| `BioKeystore` | `FakeBioKeystore`: software AES-GCM; the scripted prompt result (success, a given error code, null cipher); injectable KPIE and tag failure |
| `VaultOpener` | `FakeVault`: a list of generations with `vk_id`, a damage flag and the set of `vk_id`s on disk |

| ID | Test |
|---|---|
| T1 | `PinPolicy` refuses exactly 44,501 PINs (enumerate 10^6), with first-match counts date 36,355, sequence 4,886, repeat 2,980, keypad 280, and the sample results of `pin_policy.py` (for example 310492 allowed, 290200 date, 890123 sequence) |
| T2 | PIN crypto vector of 2.2 (message bytes, KEK, wrap, wrong-PIN tag failure) |
| T3 | Lock record codec: round trip; every framing, hash, length and field-rule violation is INVALID; `v` 2 is INVALID; a stale `.tmp` is ignored |
| T4 | **Counter property.** 100,000 random sequences of {digit, clear, submit(random wrong PIN), kill, powerCut, reboot, clockJump(±days), clearCache, wait(0–2 s)} against the real `LockEngine` with `FakeFs` at settle = 500 ms. Asserts: evaluations ≤ 5 per PIN life; Kpin deleted at the fifth; `need_master` set. Plus the exhaustive model of `counter_model.py` ported as a test |
| T5 | A pending charge at start is counted, `interrupted` is shown, and the fifth charge triggers the limit |
| T6 | The same wrong PIN within 5 minutes is not evaluated and not counted; at 5 min 1 s it is; process restart forgets |
| T7 | Right PIN, fingerprint success and master-password success each reset `failures`; `unseen` notice arithmetic excludes the current episode |
| T8 | Error classifier: every row of section 10 (exceptions built with the same causes as the lab logs). A `Mac` failure refunds (`pending = false`) and never counts |
| T9 | Master password: a 30 s pause after 5 and 10 failures, never longer; it restarts after a boot-count change; a failed record write still evaluates |
| T10 | Practice: due on days 1, 3, 7, 21, 35; the 20 h step rule; grace 3 days; a clock jump back of more than 1 day means due; restore sets step 3 |
| T11 | Screen decision of 8.1: every row, including a wrap for an old `vk_id` only (no PIN, no charge) |
| T12 | Master-password change: kill after every step of 9.7. In every state the newest intact generation opens with the right secret, and the PIN never opens an older generation while a newer one is intact |
| T13 | **The lock module never writes or deletes vault files.** A `VaultStore` spy records zero calls from the lock package, and an architecture test (Konsist or an ArchUnit-style check) forbids the lock package from importing the vault writer |
| T14 | Auto-lock: delay arithmetic on `elapsedRealtime`, screen-off, the external-activity grace with its 10 min cap, a missing setting meaning Immediately |

### 11.2 Emulator (instrumented) tests

API 24, 28, 30, 34, 35 and 37 images, debug build with `ALLOW_SOFTWARE_KEYSTORE` (emulator keys are software [L]). Emulators are shared, so these run when one is free.

| ID | Test |
|---|---|
| E1 | Setup, then PIN unlock; log the time from sixth digit to list |
| E2 | Kill during typing and at evaluation: a debug hook pauses before `Mac.init`; then `adb shell am force-stop` and `kill -9`. After restart the attempt is counted |
| E3 | `adb reboot` with a charge pending: counted after reboot |
| E4 | Wall-clock jumps (`adb shell cmd alarm set-time`, automatic time off) change neither the count nor the charge age |
| E5 | `adb shell pm trim-caches 999G` and Settings › Clear cache: no change |
| E6 | Power cut approximated by `adb emu kill` right after a wrong result, then a cold boot: the count persists |
| E7 | Fingerprint (API 28+): enrol with `adb emu finger touch 1`. Then: add a finger in Settings, which gives GONE and the 8.2 text; remove the screen lock, which gives `getKey` null and GONE; touch an unknown finger 5 times, which gives the lockout text with the pad usable |
| E8 | API 24: fingerprint row says "Needs Android 9 or newer" [L shows NO_HARDWARE there] |
| E9 | `adb shell bmgr backupnow` / `bmgr restore` (and a D2D test where available): the record never comes back, and the master-password screen appears |
| E10 | Rotation during PIN entry (digits and charge kept) and during the prompt (the callback reaches the live activity) |
| E11 | `screencap` of the lock screen is black (`FLAG_SECURE`); an overlay app's taps do nothing |
| E12 | TalkBack, Switch Access and a hardware keyboard on the pad, with `isSensitiveData` on API 34+ |
| E13 | API 35+: lock the emulator screen while the debug hook holds the check; "Device locked" gives TEMPORARY with a refund |
| E14 | Storage full (fill `/data` with a large file): the first digit shows the storage text; the master password still opens |

### 11.3 Real phones (before release)

| ID | Check |
|---|---|
| D1 | A mid-range phone (for example a Samsung A-series and a Xiaomi Redmi): Kpin level is TEE; HMAC latency over 100 runs; sixth digit to list ≤ 500 ms at p95 with 1,000 entries; Argon2 time |
| D2 | Fingerprint invalidation on Samsung and Xiaomi, recording which exception each throws. A plain `InvalidKeyException` there confirms the need for 9.13c |
| D3 | An Android 9 Samsung with CryptoObject [R V-P1] |

### 11.4 Release checks

| ID | Check |
|---|---|
| R-1 | Merged manifest: no `INTERNET` |
| R-2 | `ALLOW_SOFTWARE_KEYSTORE == false` in the release `BuildConfig` |
| R-3 | The lock record path resolves under `getNoBackupFilesDir()`, which no backup rule can include |
| R-4 | No PIN, KEK or VK in logs (R8 strips logging) |

---

## 12. Requirement check

| Req | Met by | Residual |
|---|---|---|
| R1 master password always opens, any phone | Password slot in every generation and backup (3.2). The lock module never touches vault files (T13). No device secret in the KDF. Master-password pause ≤ 30 s (5.6). Every device error falls back to the master password (section 10) | none known |
| R2 copies need the master password; PIN no help offline | KEK_pin needs Kpin inside the TEE (2.3, 2.5). The PIN is offered only with hardware-backed keys (2.4). The record holds no other secret (3.3) | a TEE extraction attack (out of scope) |
| R3 small fixed number of PIN guesses | 5 (5.1). Charged early, checked late (5.2), model-checked (5.3). Kpin deleted at the limit. Clock, cache, reboot and restore are covered (5.5) | root (2.5); drive caches slower than 500 ms (5.5) |
| R4 bank-like, fast, fingerprint by itself | App-drawn auto-submit pad (8.3). No Argon2 on the PIN path; target ≤ 0.5 s at p95 (D1). Automatic prompt with "Use PIN" (8.4) | HMAC latency on real TEEs is unmeasured (D1) |
| R5 API 24 to 37, biometric 1.1.0 | PIN on every level. Fingerprint on 28+ with 1.1.0; the alpha is not needed (8.4) | no fingerprint on Android 7 and 8 |
| R6 no deletion on wrong PIN, lockout or Keystore error | Only slots and aliases are removed (section 10). Start over archives (9.15) | — |
| R7 the master password is not forgotten | Days 1, 3, 7, then every 14 days; mandatory after 3 days of grace (6.1). Typed for every backup and export. A forgotten one can be replaced while the PIN works (6.2) | a user who never unlocks for months is still asked at the next unlock |

---

## 13. Alternatives rejected

1. **The PIN sealed by Argon2id only (Bitwarden's envelope [F §4]).** All 10^6 PINs fall offline in about 5 hours on this desktop [F §0.4]. That breaks R2.
2. **A PIN screen gate in front of a key wrapped by a Keystore key that needs no authentication (Proton [F §4]).** Root decrypts the vault without any PIN, and the PIN protects nothing cryptographically.
3. **Argon2id combined with the Keystore HMAC on the PIN path.** It costs 0.3 to 1 s per unlock (R4) and buys nothing against a TEE-rate attacker, who pipelines the two steps (2.3).
4. **Usage-limited keys (`setMaxUsageCount`, API 31+).**
   - A count above 1 is enforced in software by keystore2. Against a non-root attacker that gives nothing beyond our record; root can edit the keystore2 database [F §1.2, §7.4].
   - A count of 1 is enforced in hardware only where `FEATURE_KEYSTORE_SINGLE_USE_KEY` is declared. None of the Pixel or Cuttlefish makefiles the fact sheet read declares it, and how many phones do is unknown [F §1.2].
   - It would also need a key rotation on every success, up to about 1 s on one phone [F §3].
   - Possible later hardening: five single-use HMAC keys, one per remaining try, on phones that declare the feature.
5. **The phone's screen lock as the daily entry** (section 7).
6. **Gatekeeper-style delays.** They make the owner wait without lowering a 5-try total (5.1).
7. **Wipe after N tries.** Breaks R6; it is safe only with a server copy [R account-based finding 4].
8. **A Keystore secret mixed into the master-password KDF.** Breaks R1 [R crypto §12].
9. **StrongBox for Kpin.** 12 to 110 ms per operation, present on 0.2 to 8.7 % of devices, and more failure modes [F §1.5, §3]; no gain against the attackers in scope.
10. **A hard practice gate with no way around (Aegis).** It locks the owner out of data the PIN would open. Replaced by grace plus 6.2.
11. **Device slots in the vault header (crypto.md §1).** Moved to the lock record (3.2).
12. **Storing KEK_pin under VK so that a master-password change re-wraps silently.** That creates a key cycle (KEK_pin wraps VK, VK wraps KEK_pin). AES-GCM is not proven secure for key-dependent messages [I]. One PIN confirmation during a rare deliberate change is cheaper than an argument about circular security.
13. **Asking for the master password after every reboot.** No risk is addressed (6.1), and users would be trained to type it in a rush.
14. **A variable PIN length or a 4-digit PIN** (4.1).
15. **A published top-2,000 PIN list.** It has no licence and US-centric dates (4.2).

---

## 14. Open questions and unsettled facts

1. **What did the user mean by "home lock like banking account"?** This design reads it as the app's own PIN. If they meant the phone's screen lock, add section 7's API 30+ slot as an *extra* option; nothing else changes.
2. **Is a PIN-authorised master-password reset acceptable (6.2)?** It saves forgetful users and costs protection against people who know the PIN. Removing it is a one-line decision.
3. **Uzbek apostrophes in a user-chosen master password.** NFC does not unify ‘ ʻ ʼ ’ ' [F §9]. The crypto owner should decide, before the first release, whether to map these characters to one before Argon2. Until then the lock screen only shows the hint of 8.2.
4. **`CHARGE_AGE` = 500 ms is a judgement.** No source gives how long phone storage holds a write after fsync with `nobarrier` [R V-S8]. Decide whether 1 s is better.
5. **HMAC latency in real TEEs is unpublished** [F §3, §10]. D1 must confirm the ≤ 0.5 s target.
6. **Whether TalkBack can operate keys marked `isSensitiveData`** [F §8, §10] (test E12).
7. **The share of Android 7 and 8 phones among the user's contacts in Uzbekistan,** which bears on whether fingerprint there justifies the biometric 1.4 alpha.
8. **How many phones declare `FEATURE_KEYSTORE_SINGLE_USE_KEY`** [F §10], for the later hardening in 13.4.
9. **The PINFL claim of section 4.2** is from memory and unchecked; it is not load-bearing.
10. **Storage-design hand-offs:** add `vk_id` to the header; drop the biometric slot from the header; expose "set of `vk_id`s on disk" and "newest readable header"; archive generations on Start over (9.15).

---

## 15. Evidence produced today

All files are in `scratchpad/design/lock-designs/d2work/`:

| File | What it shows |
|---|---|
| `pin_policy.py` | The reference blocklist (44,501 refused; coverage figures of 4.2) |
| `blocklist_eval.py`, `blocklist_variants.py`, `blocklist_final.py` | The variants considered: patterns only 0.33 %; with DDMMYY 3.97 %; with all three date orders 8.73 % |
| `counter_model.py` | The bounded model check of 5.3. Arguments are limit, settle and depth: `2 0 22` gives the "settle 0" column; `2 1 22` and `3 1 26` give the "settle 1" column |
| `PinVector.java` | The test vector of 2.2 (run with `java PinVector.java`; JDK 25) |

Lab logs read: `scratchpad/lab/biometric/logs/bootA_PVBIO_lib1.1.0.log`, `bootB_PVBIO_lib1.4.0-alpha07.log`, `bootC_PVBIO_two_prompts.log`; `scratchpad/lab/old-android/logs/bio-api24-*.txt`, `bio-api28-*.txt`.

AOSP read: `AndroidKeyStoreSpi.java`, `AndroidKeyStoreProvider.java` and `KeyStoreCryptoOperationUtils.java` (copies under `scratchpad/verify-crypto-code/aosp/`); `KeyGenParameterSpec.java` and `AndroidKeyStoreKeyGeneratorSpi.java` (under `scratchpad/design/lock-facts/aosp/`); androidx.biometric 1.1.0 `BiometricPrompt.java` (under `scratchpad/lab/biometric/libsrc/src-1.1.0/`).

API levels were checked in `android-37.0/data/api-versions.xml`.
