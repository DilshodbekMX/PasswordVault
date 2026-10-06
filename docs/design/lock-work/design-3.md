# Design 3: the lock, designed from failure and recovery

Date 2026-10-06. Angle: start from every way the user could lose access to their passwords, make none of them lose data, then add the PIN on top. Status: proposal for the three judges.

**Inputs read.** `lock-facts/FACTS.md`; `docs/research/crypto.md` §1, §7, §11; `storage.md` §1, §5; `platform.md` §3.3, §3.10, §4; the Bitwarden and Proton Pass parts of `account-based.md`; `docs/research/VERIFICATION.md` (it overrides the reports); `docs/design/ux.md` §4, §5.1–5.10, §5.18–5.20, which fixes the look and wording of the lock screen and leaves PIN logic to this document.

**Tags.** **[C]** confirmed today from the named primary source. **[X]** my experiment today; scripts are in `scratchpad/design/lock-designs/bench/`. **[L]** preliminary observation in the emulator labs that are still running (`scratchpad/lab/`); their reports must confirm it. **[F]** taken from FACTS.md with its own tag. **[I]** my inference or design judgement. Path shorthand as in FACTS.md: `FB/` = AOSP `platform/frameworks/base`, `KS2/` = `platform/system/security/keystore2/src`, branch `android17-release` unless named.

---

## 0. Decisions in brief

1. **One root, two conveniences.** The master password (MP) is the only secret that opens the vault file and every backup, on any phone. The PIN and the fingerprint each unlock a *device-only* copy of the vault key (VK). Each copy lives in `noBackupFilesDir` and is bound to an Android Keystore key. Losing a copy never costs data, only a re-setup.
2. **PIN slot.** `A = Argon2id(PIN, 16 MiB, t = 3, p = 4)` runs in software. `KEK_pin = HMAC-SHA256` of A under a non-exportable Keystore key, computed in the phone's TEE. `KEK_pin` wraps VK with AES-256-GCM. A copy of the files without the phone's secure hardware cannot test a single PIN (section 4.5).
3. **PIN rules.** Exactly 6 digits. A rule-based blocklist rejects 12.63 % of all PINs, including every one of the 100 most popular 6-digit PINs and every date [X]. It is our own code, so the unlicensed published lists are not shipped.
4. **Attempt limit: 5 consecutive wrong PINs.** At the limit the PIN slot and its Keystore key are destroyed. The user opens the app with the fingerprint (if on) or the MP, then chooses a new PIN. There are no time-based PIN delays, so clocks play no part in the limit.
5. **The count comes before the check.** An attempt is committed to disk (fsync of the file and its directory) when the *first digit* is typed. The step that reveals whether the PIN is right runs only after the commit, and no sooner than 1 s after it returned. An attempt is refunded only if it was abandoned before evaluation. On API 31+ the TEE key also carries a Keystore usage limit of 6 uses (1 to wrap, 5 attempts) and is replaced after each correct PIN. That gives a second, OS-enforced budget that does not depend on our files.
6. **Lock events never touch the vault file.** No wrong PIN, PIN limit, Keystore error or slot change writes, renames or deletes a vault generation, a backup or an export. Device state never enters them.
7. **Keystore errors.** A device slot is removed only on a short list of conclusive signals (section 12). Everything else is temporary: use another method this time and change nothing. Source reading today shows that "`getKey()` returned null" is conclusive only on API 31+. On API 24–30 the old keystore client returns null on *any* error [C]. So this design adds a canary key to tell the two apart (corrects V-C6 for old devices).
8. **When the MP is required.** The first unlock on a phone; after the PIN limit when no fingerprint is set; on a practice schedule (day 1, 3, 7, then every 14 days; "Later" up to 3 times); and to make a backup, export plain text or change the MP. Setting a PIN or turning fingerprint on needs the current PIN or the MP. A reboot does not require it.
9. **Forgotten MP while the PIN or fingerprint still works** is recoverable. The user asks with the PIN or fingerprint, waits 24 hours of measured phone time (reboots and clock changes cannot shorten it), confirms again, and sets a new MP. Meanwhile a cancellable banner warns the owner at every unlock. This is the single most valuable recovery path in a local-only vault (section 11.9).
10. **Fingerprint** uses androidx.biometric **1.1.0**, Class 3 only, with a `CryptoObject`. It is offered on API 28+. On API 24–27 that library reports "no hardware" [L], so those phones use the PIN pad. The 1.4 alpha is not necessary for this design.
11. **The phone's own screen lock** is not offered in v1. The design for API 30+ is ready in section 9 if the user confirms that "home lock" meant it.
12. **Phones whose Keystore keys are not in secure hardware** get no PIN and no fingerprint, only the MP. Release builds refuse software-backed device keys.
13. **"Start over" archives; it never deletes.** It is reachable from the lock screen by anyone holding the phone, so it must not be able to destroy data.

---

## 1. The request and how this design reads it

The user: *"i want no account and everything on the phone, but i can copy or export as file … i must enther the app with home lock like banking account."* Uzbek bank apps open with their own PIN code plus a fingerprint shortcut; no public technical description of how they verify it exists [F §4, U]. This design follows the UX spec: an app-drawn PIN pad, fingerprint as a shortcut, and "Use master password" one tap away. Section 9 covers the other reading, the phone's own lock.

What a bank has that we do not: banks verify the PIN on a server, which counts failures and blocks the device (PowerAuth) [F §4]. A local-only vault has no server half. The nearest substitutes an app can reach are a Keystore key that forces each guess through the TEE, a usage limit enforced by the OS (API 31+), and a counter persisted before each check. Gatekeeper and Weaver, the hardware throttles behind Android's own lock screen, are closed to apps [F §2.3].

---

## 2. Ways to lose access, and what this design does about each

Every row ends with "data lost: no" unless stated. "Works" means "still opens the vault".

| # | Event | What still works | What the user does | Guaranteed by |
|---|---|---|---|---|
| 1 | Forgets the PIN | Fingerprint (if on), MP | "Use master password", then choose a new PIN | 11.4, 11.6 |
| 2 | 5 wrong PINs in a row (owner or someone else) | Fingerprint (if on), MP | Same; a notice lists when the wrong PINs were typed | 7.3, 11.5 |
| 3 | Forgets the MP; PIN or fingerprint works | Daily unlock | "I can't remember it", then after 24 h sets a new MP. Old backups still need the old MP | 11.9 |
| 4 | Forgets the MP **and** no PIN or fingerprint works | Nothing in the app | Restore a backup whose password they remember; the encrypted files stay archived in case the MP comes back | 11.20. Irreducible; reduced by the practice schedule (8), the last-try warning (10.3) and the forgotten-MP path (3) |
| 5 | Fingerprint key invalidated (finger added or all removed, screen lock removed) | PIN, MP | Turn fingerprint on again after unlocking | 11.16 |
| 6 | PIN key missing or permanently invalid | Fingerprint, MP | Choose a PIN again | 11.17 |
| 7 | Keystore failing temporarily (one Xiaomi on Android 15 failed every call until reboot [F, crypto.md §7]) | MP | MP this time; restart the phone; nothing is removed | 11.18 |
| 8 | OS update changes Keystore or biometric behaviour | Same as 5–7 by error class | — | 12 |
| 9 | App update | Everything | — | Versioned lock files (5.3); same signing key for the app's life (platform.md §1.9) |
| 10 | Phone lost, broken or factory-reset | A backup file outside the phone, with the MP | Restore on the new phone | 11.14; backup reminders (ux.md §5.11) |
| 11 | New phone | Backup + MP, or a phone-to-phone transfer of the vault file + MP | Restore, then set a PIN | 11.14, 11.15 |
| 12 | Write interrupted (crash, power loss, full disk) | The last proven vault generation; lock files are atomic and fail closed | — | Storage §5.2 and V-S1/S2; 5.6; crash table 7.7 |
| 13 | Storage full | MP (needs no write to succeed) | Free space; the PIN cannot run because its attempt cannot be recorded | 7.2 step C3 |
| 14 | Low memory, or the native Argon2 library fails to load | Bouncy Castle fallback. The PIN KDF at 16 MiB fits in about 20 MB of Java heap | Close apps or restart | 4.2; V-C2 |
| 15 | Newest vault generation unreadable | An older generation. PIN and fingerprint work if it has the same vault key; otherwise that time's MP | — | 11.19 |
| 16 | MP change interrupted | The old MP, the old PIN and the old fingerprint, until the new file is proven | Repeat the change | 11.8, 7.7 |
| 17 | Clock changed, phone rebooted | Everything | — | The PIN limit uses no time; waits use reboot-safe elapsed-time lower bounds (7.5) |
| 18 | App data cleared in Android Settings, or app uninstalled | Only backups outside the phone | Restore | Outside the app's control. `hasFragileUserData` offers to keep data on uninstall (API 29+) [C platform.md §3.7]; backup reminders. **Data lost if there is no backup** |
| 19 | Phone without hardware-backed Keystore | MP | MP at every unlock | 11.21 |
| 20 | Someone hostile holds the unlocked phone or knows the PIN | Owner's MP; archived copies | — | Cannot delete the vault through the app (11.20); cannot replace the MP without a 24 h window the owner sees (11.9); cannot add their own finger without the PIN or MP (11.10) |

---

## 3. Invariants

Each is a test target (section 14).

- **I1.** No lock event writes, renames or deletes a vault generation, a backup or an export. Lock events are every PIN, MP or fingerprint attempt, the PIN limit, every Keystore error, and creating, rotating or removing a device slot.
- **I2.** Device-bound state lives only in `noBackupFilesDir/lock/` and the AndroidKeyStore. It never enters a vault generation, a backup or an export.
- **I3.** Every vault generation and every backup carries exactly one MP slot. Before the file was reported saved, that slot was opened with the MP the user typed (at creation, at change, at backup).
- **I4.** The step that reveals whether a PIN is right (the AES-GCM tag check of the PIN wrap) never runs unless that attempt was committed, with file and directory fsync, at least 1,000 ms earlier.
- **I5.** `pin_failures` decreases only on (a) a correct PIN, (b) a correct MP, (c) a refund of an attempt that was never evaluated, or (d) the creation of a new PIN slot.
- **I6.** A device slot is removed only on a conclusive signal from section 12, at the PIN limit, at the user's request, or when the MP change or replacement that made it stale has completed.
- **I7.** The vault opens only after an AEAD open of a wrap of VK followed by an AEAD open of a generation body under that VK. No boolean callback unlocks anything (the Cure53 BWN-11-005 lesson, account-based.md).
- **I8.** The wall clock never decides a security question. It is used for display and for the practice schedule only.
- **I9.** Nothing is deleted in response to a wrong MP, a wrong PIN or a Keystore error, except the one device slot that the section 12 rule names.

---

## 4. Key hierarchy

### 4.1 Picture

```
  master password ──NFC, UTF-8──► Argon2id(64 MiB, t=3, p=4, salt_mp)            [software, ~0.3-1 s]
                                        │ KEK_mp (32 B)
                                        ▼
  ┌──────────────── vault generation file (portable: copied to backups and to new phones) ────────────────┐
  │ "PVAULT01" ‖ len ‖ header { vault_id, key_id, generation, …, MP slot = AES-256-GCM(KEK_mp, VK) }       │
  │ body = AES-256-GCM(VK, entries JSON, AAD = magic ‖ len ‖ header bytes as stored)  (crypto.md, V-C3)    │
  └────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                        ▲ VK: 32 random bytes; replaced on every MP change (key_id changes with it)
              ┌─────────────────────────┴──────────────────────────────┐
   PIN slot (noBackupFilesDir/lock/slots.json)          fingerprint slot (same file)
   wrapped = AES-256-GCM(KEK_pin, VK, AAD_pin)          wrapped = AES-256-GCM in the TEE under K_fp, AAD_fp
   KEK_pin = HMAC-SHA256_{K_pin}(info_pin ‖ A)  [TEE]   K_fp: per-use Class 3 biometric, invalidated by enrolment
   A = Argon2id(PIN, 16 MiB, t=3, p=4, salt_pin) [sw]
   K_pin: Keystore HMAC key, no user auth; API 31+: usage limit 6, replaced after each correct PIN
```

### 4.2 Algorithms and parameters

| Item | Value | Reason |
|---|---|---|
| VK | 32 random bytes from `SecureRandom()` | crypto.md §1 |
| MP KDF | Argon2id v1.3, m = 65,536 KiB, t = 3, p = 4, 16-byte salt, 32-byte output; NFC, then UTF-8 | Decided (crypto.md §1, V-C9) |
| PIN encoding | The six ASCII bytes `'0'`–`'9'` (0x30–0x39). The pad emits only these; hardware-keyboard digits are mapped to them; locale digits are never used | Fixed bytes, no locale effects |
| PIN KDF | Argon2id v1.3, **m = 16,384 KiB, t = 3, p = 4**, 16-byte salt per slot, 32-byte output | Above OWASP's 12 MiB / t = 3 line (crypto.md §3). About a quarter of the MP's cost for the daily unlock. Fits the Bouncy Castle fallback's heap (V-C2 measured about 80 MB of heap for 64 MiB). The TEE step, not this KDF, is the main barrier (4.5). Device-only, so it can change later without touching any portable file |
| K_pin | AndroidKeyStore `HmacSHA256`, 256-bit, `PURPOSE_SIGN`, no user authentication, no StrongBox, no `setUnlockedDeviceRequired`. API 31+: `setMaxUsageCount(6)` | Each guess must pass through this phone's TEE (MASWE-0020 mitigation [F §5]). An HMAC returns nothing before `finish()`, so the usage count is spent before anything is revealed [F §1.2] |
| KEK_pin | `HMAC-SHA256(K_pin, info_pin)`, 32 bytes, used as an AES-256 key | HMAC under a random key is a PRF |
| PIN wrap | AES-256-GCM in software, 12-byte nonce from `SecureRandom()`, 16-byte tag, AAD_pin; 48 bytes out | Same primitive as the MP slot |
| K_fp | AndroidKeyStore AES-256-GCM, `PURPOSE_ENCRYPT \| PURPOSE_DECRYPT`, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)`, `setKeySize(256)`. API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`. API 28–29: per-use is the default once authentication is required [C `FB/…/KeyGenParameterSpec.java:1546-1569`] | crypto.md §7 with V-C7 and V-P10. The nonce comes from `cipher.iv` (a caller IV is refused [C, L]) |
| Fingerprint wrap | AES-256-GCM inside the TEE under K_fp, AAD_fp, 48 bytes out | — |
| Canary | AndroidKeyStore AES-128, `PURPOSE_ENCRYPT`, no authentication, alias `pv_canary`; never used for data | Tells "this alias is gone" from "the Keystore is failing" on API 24–30 (section 12) |

### 4.3 Byte-level definitions and test vector

```
uuid16(u)  = the 16 bytes of UUID u, most significant first
info_pin   = ASCII("pvlock-v1:pin-kek") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(key_id) ‖ uuid16(slot_id) ‖ A     (98 bytes)
KEK_pin    = Mac("HmacSHA256", K_pin).doFinal(info_pin)                                                    (TEE)
AAD_pin    = ASCII("pvlock-v1:pin-wrap") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(key_id) ‖ uuid16(slot_id)       (67 bytes)
AAD_fp     = ASCII("pvlock-v1:fp-wrap")  ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(key_id) ‖ uuid16(slot_id)
wrapped    = AES-256-GCM-Seal(key, nonce, VK, AAD)   → 32 bytes of ciphertext ‖ 16-byte tag
```

Binding vault_id, key_id and slot_id into the KEK and the AAD means a wrap cannot be moved to another slot, another vault or another vault key without failing the tag.

**Test vector** [X, `bench/PinVector.java` (Bouncy Castle 1.86 plus JCA) and `bench/vec.c` (PHC reference C) agree on A]. Inputs: PIN `582913`; `salt_pin` = 00 01 … 0f; vault_id `00000000-0000-4000-8000-000000000001`, key_id `…0002`, slot_id `…0003`; a stand-in for K_pin of 32 bytes of 0x42 (the real key never leaves the TEE); VK = a0 a1 … bf; nonce = 10 11 … 1b.

```
A        = 46777c4da91d0a6bc67f21040cfe4c2b0f72dfedae22b4da99d2532d3a6702b5
KEK_pin  = e9981bddfb57c6dd71294540f7558077b077eb6dac29d04c7910d158b605a1bd
wrapped  = cd1591232d24ea79f80092bc3582c64e8e998653de72f8d201f6d5349dbcae72c3c63cf9db8d56025243241f4bc65460
PIN 582914 with the same inputs → AEADBadTagException
```

The same build reproduces crypto.md's 64 MiB vector `853b272a…bab49e` [X], which checks the setup.

### 4.4 What runs where

| Step | Where |
|---|---|
| Argon2id for the MP and the PIN; every software AES-GCM open and seal; counters and policy | Our process (software) |
| HMAC with K_pin | KeyMint in the TEE, on phones that pass the hardware check (5.5) |
| AES-GCM with K_fp, and the check that a fresh Class 3 biometric authorised this exact operation | KeyMint in the TEE, with the auth token from the biometric TEE [F §1.1] |
| Usage limit 6 on K_pin (API 31+) | keystore2, an OS daemon (software). It decrements in an IMMEDIATE transaction on a database opened with `synchronous = EXTRA`, after KeyMint's `finish()` succeeds and before the output reaches the app. If the decrement fails, the output is not returned [C `KS2/operation.rs:426-460`, `KS2/database.rs:1206-1207, 2303-2337`]. KeyMint is "not expected" to enforce counts above 1 in hardware; only phones declaring `FEATURE_KEYSTORE_LIMITED_USE_KEY` do, and how many do is unknown [F §1.2] |
| Biometric lockout after failed touches | The biometric stack (CDD 17 §7.3.10) [F §2.2] |

### 4.5 What one PIN guess costs an attacker

The PIN space is 10^6. The blocklist leaves 873,663 PINs (section 6).

| Situation | What limits guessing | Cost per guess, and total |
|---|---|---|
| **A. Copy of everything the app stores** (app-private files, backups, exports), without the phone's secure hardware | No guess at the PIN is possible: KEK_pin needs K_pin, which never leaves the TEE ("can't extract them from the device" [F §1.1]). The PIN files hold no verifier. So the attacker must attack the MP slot | One Argon2id(64 MiB) per MP guess: 55 per second on 8 threads here [X], about 1,700 per second on one RTX 4090 [S2, crypto.md §2.9]. The UX's six-word passphrase (62.0 bits, ux.md §5.3) needs on average 2^61 guesses, about 4.3 × 10^7 GPU-years [I] |
| **B. Phone stolen or found, not rooted, bootloader locked** (locked or unlocked phone) | Our counter in `noBackupFilesDir` (unreachable without root), committed before every check | **5 PIN guesses in total**, then the PIN slot and K_pin are destroyed. Speed is irrelevant; the budget is fixed. The top 100 PINs are all blocked [X]. After that, MP guesses go through the UI at about 1 per second plus waits (7.6), about 300 per day at most |
| **C. Phone rooted later** (exploit; bootloader still locked) | The TEE: each guess needs one HMAC with K_pin on this phone. Argon2id(16 MiB) can be precomputed elsewhere | **API 31+:** at most 5 TEE uses before keystore2 deletes K_pin, unless root also defeats keystore2, which is OS software. **API 24–30, or if keystore2 is defeated:** about 30–90 TEE operations per second [F §3], so all allowed PINs in about 2.7–8.1 h. If the 5 UI guesses were already spent, K_pin no longer exists and only the MP remains. The fingerprint slot needs a fresh Class 3 auth token, so the owner's finger |
| D. (Beyond the threat model) K_pin extracted from a broken TEE, plus the files | Argon2id(16 MiB) alone | 251 per second on 8 threads here [X] (55 per second at 64 MiB), so about 58 min for all allowed PINs. A GPU is faster. **The fingerprint slot would yield VK directly.** This is why device slots are conveniences and the MP must be strong (platform.md §4: root and a compromised OS are out of scope) |

### 4.6 Alternatives considered and rejected

| Alternative | Why not |
|---|---|
| PIN envelope from Argon2id alone, no Keystore (Bitwarden's PIN [F §4]) | Copied files fall to offline guessing: 10^6 PINs in about 5 h on this desktop [F §0.4]. Fails hard requirement 2 |
| PIN as a screen gate over a key stored without the PIN (Proton Pass [F §4]) | No cryptographic protection at rest |
| Mix a Keystore secret into the MP KDF | Any Keystore loss destroys the vault (crypto.md §12). Fails hard requirement 1 |
| Device slots inside the vault header | They are useless on another phone, they would force a vault rewrite on every Keystore event (breaks I1), and V-P6 puts them in `noBackupFilesDir` |
| Single-use "attempt token" keys (`FEATURE_KEYSTORE_SINGLE_USE_KEY`) | Hardware-enforced, but how many phones declare the feature is unknown (none in Pixel or Cuttlefish makefiles [F §1.2, §10]), and it costs N key generations per unlock |
| `setUnlockedDeviceRequired(true)` on K_pin (API 35+) | keystore2 keeps the protecting super key in plaintext while locked whenever a weak unlock method (Class 2 face, trust agents) is on [C `KS2/super_key.rs` `lock_unlocked_device_required_keys`: "Wipe the plaintext copy of the keys, unless a weak unlock method is enabled"]. Bugs on 12–14 [C Javadoc]. It adds a "device locked" error class. Deferred |
| Argon2id(64 MiB) for the PIN | Four times slower daily unlock and more memory pressure. Little gain: guessing is bounded by the TEE (B, C), and in D the fingerprint slot falls anyway |
| Growing PIN delays (Android's schedule) | With a hard limit of 5 they add nothing, and they bring clocks into a security decision (I8) |
| Wipe after N failures | Total loss in a vault with no server copy (account-based.md finding 4). Never |
| Suspend fingerprint after the PIN limit (Proton hides biometrics after 3 wrong PINs [F §4]) | It does not stop finger misuse, which needs no PIN guesses first. It does trap an owner who typed 5 wrong PINs and has forgotten the MP, which loses data |
| Device key DK between VK and the slots, so the slots survive an MP change | Saves one PIN entry and one touch on a rare event. Costs a key cycle (VK wraps DK, DK wraps VK) and more code |
| StrongBox for K_pin | 12–110 ms per operation, present on 0.2–8.7 % of devices [F §1.5, §3]; no gain against B or C |

---

## 5. Where every piece of state lives

### 5.1 Summary

| State | Lives in | Survives | On MP change (new VK) | Older generation used | Backup / export | New phone, restore, reinstall |
|---|---|---|---|---|---|---|
| MP slot (salt, nonce, wrapped VK) | Header of every vault generation | Everything except clearing data | New slot under the new VK and new key_id | Each generation carries its own | The backup has its own MP slot (crypto.md §10); plain exports have none | Travels inside the file |
| `vault_id`, `key_id`, `generation` | Vault header (requested addition, 5.2) | — | `key_id` changes | Read before unlock to pick the device slot | Backup: own `key_id` | Travels |
| PIN slot | `noBackupFilesDir/lock/slots.json` | Reboot, app update. Never in cloud backup, transfer or cross-platform transfer [F §7.4] | Re-armed with the PIN typed during the change, otherwise removed (11.8) | Used only if its `key_id` equals the generation's; never deleted for a mismatch alone (I6) | Never | Absent; the user sets a new PIN |
| Fingerprint slot | Same file | Same; also dies with K_fp | Re-enrolled during the change, otherwise removed | As the PIN slot | Never | Absent |
| K_pin `pv_pin_<slot_id>`, K_fp `pv_fp_<slot_id>`, `pv_canary` | AndroidKeyStore | Not backed up; deleted with app data and on uninstall [C platform.md §4] | Replaced | — | — | Absent |
| Counters, notices, practice schedule, MP-replacement request | `noBackupFilesDir/lock/state.json` | As slots.json | Practice reset to step 0; counters 0 | — | Never | New file |
| VK, A, KEK, the PIN digits, the "fresh proof" time | Memory only | Process lifetime; dropped on lock | — | — | — | — |
| Lock delay setting | Settings store (ux.md §2) | — | — | — | — | — |

### 5.2 Vault header additions (request to the crypto and storage owners)

Inside the authenticated header: `"vault_id"` (UUID v4, set at creation, kept across saves and MP changes, new after "Start over"); `"key_id"` (UUID v4, new with every VK); and `"generation"` (V-C8). The lock reads `vault_id` and `key_id` of the newest readable generation *before* unlock, to choose the right device slot and to show the MP screen at once when the slots are stale. If the owners refuse `key_id`, the MP slot's `id` can serve, provided it changes exactly when VK changes.

### 5.3 `slots.json` (version 1)

```json
{
  "format": "pvlock-slots",
  "version": 1,
  "vault_id": "<uuid>",
  "pin": {
    "id": "<uuid>", "key_id": "<uuid>", "alias": "pv_pin_<id>",
    "kdf": "argon2id", "argon2_version": 19, "m_kib": 16384, "t": 3, "p": 4,
    "salt": "<base64, 16 B>", "nonce": "<base64, 12 B>", "wrapped_key": "<base64, 48 B>",
    "usage_limit": 6,
    "created": "2026-10-06T12:00:00.000Z"
  },
  "fingerprint": {
    "id": "<uuid>", "key_id": "<uuid>", "alias": "pv_fp_<id>",
    "nonce": "<base64, 12 B>", "wrapped_key": "<base64, 48 B>",
    "created": "2026-10-06T12:00:00.000Z"
  }
}
```

`pin` and `fingerprint` may each be `null`. `usage_limit` is 6 or 0 (0 means no limit: API < 31, or generation with the limit failed). The reader is strict, in the spirit of V-C4. It rejects unknown or duplicate keys, a higher `version`, base64 lengths other than those shown, KDF values other than exactly 16384/3/4/19 in version 1, an alias that does not match the id, and trailing bytes. A rejected `slots.json` is treated as absent: the MP is required and the slots are rebuilt after unlock. The file is never "repaired" in place. Base64 uses `android.util.Base64` (V-C12).

### 5.4 `state.json` (version 1)

```json
{
  "format": "pvlock-state",
  "version": 1,
  "vault_id": "<uuid>",
  "pin_failures": 0,
  "pin_failure_times": ["2026-10-06T14:02:11.000Z"],
  "pin_failures_noticed": 0,
  "mp_failures": 0,
  "mp_last_failure": {"boot": 41, "elapsed_ms": 123456789},
  "practice": {"last_ok": "2026-10-06T12:00:00.000Z", "step": 0, "postponed": 0},
  "mp_replacement": null,
  "missing_alias": null
}
```

- `pin_failures` is the security counter. It counts attempts committed and not refunded, so it includes an attempt interrupted before evaluation.
- `pin_failure_times` holds up to 5 wall-clock times of attempts *evaluated* wrong, for display only.
- `mp_replacement` is null or `{"requested": "<RFC 3339>", "by": "pin|fingerprint", "proven_ms": 0, "obs_boot": 41, "obs_elapsed_ms": 123}` (11.9).
- `missing_alias` is null or `{"alias": "...", "boot": 41}` (section 12, API 24–30 only).
- **If `state.json` is missing or rejected while a PIN slot exists, the PIN is not offered** (fail closed): the MP is required once, then a new `state.json` with zero counters is written. The slot itself is kept.

### 5.5 Keystore entries

| Alias | Created | Deleted |
|---|---|---|
| `pv_canary` | Before the first device key (if missing) | Never by us (uninstall or clearing data removes it) |
| `pv_pin_<slot_id>` | PIN set, PIN changed, MP change re-arm, rotation (API 31+) | After the slot that replaces or removes it is committed; at the PIN limit; by keystore2 when its usage count runs out |
| `pv_fp_<slot_id>` | Turning fingerprint on | After its slot is removed or replaced |

**Hardware check, release builds.** Right after generating a device key, read `KeyInfo` through `SecretKeyFactory.getInstance(alg, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)`.
- API 31+: accept `getSecurityLevel()` of `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, `SECURITY_LEVEL_STRONGBOX` or `SECURITY_LEVEL_UNKNOWN_SECURE` (all API 31 [C api-versions]).
- API 24–30: accept `isInsideSecureHardware()` (API 23, deprecated 31).
- Otherwise delete the key and do not offer that slot (11.21).
- **Debug builds** may accept software keys, because emulator Keystores report `SOFTWARE` [L: `securityLevel=0` on the API 37 lab; `insideSecureHardware=false` on API 24 and 28]. The lock screen of such a build then shows a red "TEST BUILD — not secure" line.

**Orphan sweep** (after each successful unlock, in the background, under the lock mutex). List aliases; delete every `pv_pin_*` or `pv_fp_*` not referenced by the `slots.json` read successfully in this process. `aliases()` returns an empty list on Keystore errors [C `AndroidKeyStoreSpi.java:1138-1150`], so a failing Keystore causes no deletion. `deleteEntry` ignores KEY_NOT_FOUND [C `:1126-1136`]. Other delete errors are logged and retried by the next sweep.

### 5.6 Write routine for lock files

```
write(dir = noBackupFilesDir/lock, name, bytes):      // caller holds the lock mutex
  tmp = "$name.tmp"; FileOutputStream(tmp).use { write(bytes); flush(); fd.sync() }
  Os.rename(tmp, name)                                  // API 21; atomic replace of a small state file
  fd = Os.open(dir, O_RDONLY); try { Os.fsync(fd) } finally { Os.close(fd) }
  - ErrnoException EINVAL from the directory fsync: that filesystem does not support it → treat as done (log once)
  - any other failure (including EIO, ENOSPC) → throw: the write is NOT committed (V-S2 analogue)
At process start: delete stale *.tmp in the lock directory.
```

Replacing in place by rename is right for these small files. The old or the new version survives a crash, and both are valid states (7.7). Do not use `AtomicFile` or DataStore here: they swallow sync failures (V-S7).

### 5.7 Lifecycle in the cases the task names

| Case | Vault file | `slots.json` / Keystore | `state.json` |
|---|---|---|---|
| MP change (11.8) | New generation with VK2, key_id2, a new MP slot; proven before old slots are touched | PIN re-armed for key_id2 with the PIN the user typed during the change; fingerprint re-enrolled or removed; old keys deleted after commit | Practice step 0; counters 0 |
| MP replaced after forgetting it (11.9) | As MP change | PIN re-armed with the confirming PIN; fingerprint re-enrolled or removed | As MP change |
| Older generation opened, same key_id | Read only; storage decides what to write next | Slots work | — |
| Older generation, different key_id | Opens only with that time's MP | Slots unused, *kept* (the newer file may be readable later); removed only when a later save proves the old key_id is current | — |
| Encrypted backup made | Untouched; the backup is a new file with one MP slot | Untouched | Practice advanced (the MP was typed) |
| Plain export | Untouched | Untouched | Practice advanced |
| Restore from backup on a new phone | Becomes the vault (keeps the backup's vault_id and key_id) | New, empty; PIN set after the MP unlock | New; practice step 3 (the MP was just recalled) |
| Phone-to-phone transfer or cloud restore of the vault file (if the storage owner allows it) | Arrives without device state | Absent (`noBackupFilesDir` is always excluded [F §7.4]) | Absent; MP first |
| Reinstall, or app data cleared | Gone unless restored | Gone | Gone |

---

## 6. PIN rules

- **Length: exactly 6 digits.** NIST SP 800-63B-4 says at least 4 SHALL and at least 6 SHOULD; CDD 17 strongly recommends 6; Mandiant asked Bitwarden for 6 [F §6.3]. Under a limit of 5 guesses, 4- and 6-digit PINs are about equally guessable. Six digits matter when a limit is bypassed (situation C on API 24–30), where they mean 100 times the work [F §0.6]. A fixed length allows submit on the 6th digit with no OK key, and reveals nothing because every PIN has length 6 (CDD 17 C-SR-7 concerns variable lengths).
- **Digits only, no letters, no shuffled keypad** (ux.md §5.9).
- **Blocklist, applied only when a PIN is chosen** (never at unlock). It is a pure function of the six digits; the first matching rule gives the message:

| Rule | Blocks | Example | PINs blocked (first match) [X] |
|---|---|---|---|
| Repeat | Any digit 4 or more times | 111111, 000001 | 12,700 |
| Pattern | Period 2 or 3 (ababab, abcabc), aabbcc, aaabbb, palindrome abccba | 121212, 112233, 696969 | 2,340 |
| Sequence | All six digits an arithmetic progression mod 10; both halves runs of step ±1; a run of 4 of step ±1 anywhere; 246810, 108642, 135790, 097531 | 123456, 654321, 789456, 123457, 001234 | 4,328 |
| Keypad | Both halves straight 3-key lines on the phone keypad (including the 2-5-8-0 column); or six different keys each next to the previous one (8-neighbourhood) | 159753, 147896, 741236 | 3,958 |
| Steps | Digits 1, 3, 5 and digits 2, 4, 6 each an arithmetic progression | 102030, 142536 | 2,066 |
| Date | DDMMYY, MMDDYY, YYMMDD with day 1–31 and month 1–12; or starts or ends with a year 1940–2039 | 250890, 010190, 199012, 071990 | 100,945 |
| **Total** | | | **126,337 of 1,000,000 (12.63 %)** |

**Measured against the published lists** [X, `bench/blocklist.py`]. The DD-6-digit list is ordered by popularity (Markert et al.; ToPS 2021 recommends blocking its top 2,000 [F §6.1]). Blocked: top 100: 100 %; top 1,000: 98.2 %; top 2,000: 99.0 %; top 10,000: 99.0 %; the iOS 6-digit list: 78.6 %. The most popular PIN that passes is rank #163 (`143637`).

Why block every date, not just a top-N list: the attacker this app meets most is someone near the owner, and that person knows birthdays [I]. Dates are among the most common choices [F §6.1]. The cost is a second try when the user's first idea is a date; about 87 % of all PINs remain. The lists themselves carry no licence [F §6.2], so they are used only to measure, never shipped.

**How the user is told** (strings for the UX owner):
- Create-PIN subtitle: "Choose 6 digits. Not a date or year, and not a pattern like 123456 or 112233. Don't use your phone's PIN or your bank card PIN."
- After the 6th digit, if blocked: "This PIN looks like a date or a year." · "This PIN repeats digits or follows a pattern." · "This PIN is a sequence or a shape on the keypad." Each ends with "Choose another." The dots clear and no attempt is counted (creation is not an unlock).
- Change PIN only: "Choose a PIN different from your current one."
- Create-PIN footer: "If you type a wrong PIN 5 times in a row, the PIN stops working. Then open the app with your fingerprint or master password and choose a new PIN."

---

## 7. The attempt counter

### 7.1 Where it lives

`pin_failures` in `noBackupFilesDir/lock/state.json`. A normal app cannot read or roll back app-private files without root. `noBackupFilesDir` is never in cloud backup or device transfer, so no restore can bring back an older counter. Clearing the cache does not touch it [F §7.4]. On API 31+ a second, independent budget sits in keystore2's database as K_pin's usage count (4.4).

### 7.2 Exact order around each PIN attempt

All of this runs under one process-wide `Mutex` (the lock manager). Keypad input during evaluation is ignored.

**Charge**, when the first digit of an entry is typed:
- C1. Precondition check: a PIN slot exists, its `key_id` equals the newest readable generation's, `pin_failures < 5`, and `state.json` was read successfully.
- C2. `pin_failures += 1` in memory. Write `state.json` with the 5.6 routine.
- C3. If the write throws, the attempt is **not** charged. Keep the old value in memory, disable the pad and show: "The app couldn't record your attempt. Your phone's storage may be full. Free some space, or use your master password." The PIN is never evaluated without a committed charge (I4).
- C4. Remember `t_commit = SystemClock.elapsedRealtime()` (in memory) after the write returned.

**Evaluate**, when the 6th digit is entered and `elapsedRealtime() − t_commit ≥ 1,000 ms` (if not yet, show "Opening…" and wait for the remainder):
- E1. Read the slot. `A = Argon2id(pin, salt, 16384, 3, 4)`.
- E2. `KEK = HMAC_{K_pin}(info_pin)` in the TEE.
  - Removal signal (section 12): remove the PIN slot (11.17). The charge stays; it is moot.
  - Temporary error: **refund**, write `pin_failures −= 1`. This is safe because nothing was revealed. Then go to the MP screen with the temporary-error text.
- E3. `VK = AES-GCM-Open(KEK, nonce, wrapped, AAD_pin)`. This is the revealing step.
- E4. **Wrong** (`AEADBadTagException`):
  - Append the time to `pin_failure_times` and write `state.json`. This write is display-only; if it fails, the charge already counts.
  - If `pin_failures ≥ 5`: the PIN limit (7.3).
  - Otherwise show "Wrong PIN. N tries left." (10.3).
- E5. **Right**:
  - Set `pin_failures = 0`. Keep `pin_failure_times` until the notice is shown (10.4).
  - Write `state.json`. On failure, log; the next correct PIN or MP retries the reset, and the counter stays conservative.
  - Open the vault (I7). Zero `pin`, `KEK`.
  - API 31+ with `usage_limit = 6`: rotate K_pin in the background (7.4); zero A afterwards.

**Abandon** (before E1 started; the entry is cleared to zero digits, the screen is left, "Use master password" is tapped, or fingerprint unlock succeeded): refund `pin_failures −= 1` and write. If the process dies first, the attempt stays counted. That is conservative.

Why the first digit and the 1 s floor: fsync is the only durability primitive an app has. Its durability depends on the storage device, and some phones mount `/data` with `nobarrier` (V-S8). Committing early and evaluating no sooner than 1 s later means a committed attempt is never followed straight away by its result [I]. A person types six digits in more than a second, so there is normally no added wait. Gatekeeper avoids the problem with secure storage or RPMB, which apps cannot use [F §2.1, §2.3].

Contrast: Bitwarden counts after its KDF, and Proton Pass waits 2 s before counting. In both, interrupting the app before the count makes the guess free [F §0.9, §7.1]. Here the count precedes the check.

### 7.3 The limit and what happens at it

**Limit: 5 consecutive wrong PINs.** That is NIST's "no more than 10 … then a different authenticator SHALL be required" [F §6.3] and the EU PSD2 bank rule of at most 5 [F §4]. When `pin_failures` reaches 5 after an evaluated wrong attempt:

- L1. Write `slots.json` without the PIN slot.
- L2. `deleteEntry(pv_pin_<id>)`. On API 31+ keystore2 has already deleted it after its 5th use. Errors are ignored and the sweep retries.
- L3. Keep `pin_failures = 5` until a correct MP or a new PIN slot resets it.
- L4. Lock screen: if the fingerprint slot is usable, show the fingerprint prompt plus "Use master password", with "PIN unlock was turned off after 5 wrong tries." Otherwise show the MP screen with "For your safety, type your master password." (ux.md §5.9). Nothing else changes: the vault, backups, the fingerprint slot and the MP are untouched (I1, I9).
- After the next successful unlock: the notice (10.4), then "Choose a new PIN" (11.6, which needs the MP if the unlock was by fingerprint).

At process start, if `pin_failures ≥ 5` and a PIN slot still exists (a crash between E4 and L1), run L1–L2 first. The steps are idempotent.

### 7.4 What resets it, and rotation (API 31+)

`pin_failures` returns to 0 on a correct PIN, on a correct MP, and when a new PIN slot is created. **A fingerprint unlock does not reset it.** A person nearby who tries a few PINs a day would otherwise get a fresh budget after every owner's fingerprint unlock. The owner sees the notice instead (10.4).

**Rotation after a correct PIN** (only when `usage_limit = 6`):
1. Generate `pv_pin_<new id>` with `setMaxUsageCount(6)`.
2. `KEK' = HMAC` with the new key over info_pin with the new slot id (uses 1 of 6). Reuse A and the salt; seal VK with a new nonce.
3. Write `slots.json` with the new slot.
4. Delete the old alias.

Any failure in steps 1–3 deletes the new alias and keeps the old slot. The old key then has fewer uses left than the UI's count; when it runs out, the next attempt sees `KeyPermanentlyInvalidatedException` → removal (11.17) → MP. That is degraded but safe. Key generation costs 43–93 ms at the 90th percentile [F §3], and it runs after the vault is already on screen.

### 7.5 No PIN delays; reboot-safe waits elsewhere

The PIN limit uses no time at all. Changing the clock or rebooting cannot change what a count of 5 means [I8].

Two waits in this design do use time: the MP retry delay (7.6) and the 24 h before an MP can be replaced (11.9). Both use a **proven elapsed time**, a lower bound that a reboot or a clock change can only shrink:

```
advance(p: Proof, bootNow: Int?, elapsedNow: Long): Proof
  gained = if (bootNow != null && p.boot != null && bootNow != p.boot) elapsedNow     // rebooted: only uptime is provable
           else if (elapsedNow >= p.elapsed) elapsedNow - p.elapsed                     // same boot, or no evidence of reboot
           else elapsedNow                                                              // elapsed went backwards: rebooted
  return Proof(proven = p.proven + gained, boot = bootNow, elapsed = elapsedNow)
```

`bootNow` is `Settings.Global.BOOT_COUNT` (API 24, `@Readable` [C `Settings.java:19098-19104`]), or null if unavailable. Every branch is at most the true elapsed time. If the boot count is missing and a reboot goes unseen, the result only undercounts. `elapsedRealtime()` is monotonic and includes deep sleep [F §7.3]. The effect matches Gatekeeper's: a reboot restarts the current wait rather than skipping it [F §2.1].

### 7.6 Master-password attempts

There is **no lockout, ever** (hard requirement 1). `mp_failures` is committed before each Argon2id. This is best effort: if the write fails, the attempt still runs, because the MP must always work, even on a full disk.

| Consecutive wrong MPs | Wait before the next attempt |
|---|---|
| 1–5 | none (each attempt already costs one Argon2id(64 MiB), about 0.3–1 s) |
| 6–10 | 30 s |
| 11 and more | 5 min |

Waits use 7.5. A correct MP resets everything. A wrong MP is "wrong" only after every distinct MP slot among retained generations has been tried (V-S3).

### 7.7 Crash points (all leave the vault openable with the MP)

| Flow | Crash after | On disk | Next start |
|---|---|---|---|
| PIN attempt | C2 committed, before evaluation | `pin_failures` = f+1 | f+1 used ("N tries left" is one lower). Nothing was revealed |
| | E4 shown, before the log write | f+1 | Same; the notice misses one time |
| | E5 opened, before the reset write | f+1 | Unlocked this time; f+1 shown next time |
| | Limit: E4 done, before L1 | 5, slot present | Startup runs L1–L2 |
| | L1 done, before L2 | Orphan key | Sweep deletes it |
| PIN setup (11.6) | Key generated, before `slots.json` | Orphan key, no slot | MP, then "Choose a PIN"; the sweep deletes the key |
| Rotation (7.4) | Step 3 done, before step 4 | Old alias orphaned | Sweep |
| Fingerprint on (11.10) | Key generated, before `slots.json` | Orphan key | Fingerprint shown as off; sweep |
| MP change (11.8) | Before the new generation's rename | Old vault | Old MP, old PIN, old fingerprint all work |
| | Rename done, before read-back proof | New file is newest | MP screen ("finish the change"): the new MP opens it. The old MP opens the older generation, which is offered with an explanation (11.19) |
| | Proven, before re-arming slots | Slots stale (key_id1) | MP (new), then PIN and fingerprint setup again |
| PIN limit | — | as above | — |
| Start over (11.20) | Before the archive rename | Old vault | Nothing happened |
| | After | Archive + no vault | Welcome; lock files of the old vault removed at setup |

### 7.8 Residual risks (stated, not hidden)

- **Storage write caches.** fsync durability depends on the device (V-S8). Section 7.2 reduces the exposure; it cannot remove it. Only the OS's own lock screen has tamper-resistant counter storage [F §2.1].
- **Root.** Software limits (our file, keystore2's count) are out of scope against a compromised OS (platform.md §4). Section 4.5 gives the costs anyway.
- **Coercion and a sleeping owner's finger.** Out of scope, as for every biometric unlock. The attempt notice and the replacement banner make some misuse visible.

---

## 8. When the master password is required

| Case | Required? | Risk it addresses | Annoyance |
|---|---|---|---|
| First setup | The user creates it, writes it down, types it again (ux.md §5.3–5.5) | Root of recovery | Once |
| First unlock on a phone (restore, transfer, reinstall) | Yes | Device secrets never travel; there is nothing else | Once per phone |
| After 5 wrong PINs | Yes, unless fingerprint is on | NIST: a different authenticator after the limit | Rare |
| PIN key gone or invalid | Yes, unless fingerprint works | The slot is gone | Rare |
| Keystore failing temporarily | This time only; nothing changes | No device factor works | Rare |
| Practice schedule | Due 1, 3 and 7 days after the MP is set or changed, then 14 days after each correct entry. "Later" (use PIN or fingerprint this time) up to 3 times in a row; "I can't remember it" always available | Forgetting the MP is the largest loss risk in a vault with no server (requirement 7). Aegis asks every 2 weeks; Keyguard after 14 days (ux.md §5.10) | About 2 a month after the first week |
| After a reboot | **No** | The phone demands its own credential after boot before any app runs (file-based encryption [F §7.4]); the risk afterwards is unchanged | — |
| After a long absence | No separate rule: a 14-day gap makes the practice check due | — | — |
| Make an encrypted backup | Yes, typed now, verified against the vault's MP slot before the backup is sealed | Proves the backup opens with the MP the user knows *today* (I3), and is practice | Per backup |
| Plain export (CSV, JSON, XLSX) | Yes, typed now and verified | Mass disclosure: knowing the PIN must not mean "can export everything" (ux.md §5.20) | Per export |
| Change the MP | Current MP (or the 11.9 path) | — | Rare |
| Set a PIN when none exists; change the PIN | Current PIN or MP | A session picked up by someone else must not get a PIN its holder knows | Rare |
| Turn fingerprint on | PIN or MP verified within the last 120 s | Stops someone with a grabbed session adding a finger enrolled with the phone's PIN | Rare; after unlocking with the PIN it is already satisfied |
| Turn PIN or fingerprint off; lock delay; clipboard delay | Nothing extra | Less access, or no protective value | — |
| Open or delete an archived copy (11.20) | **That copy's** MP | Someone who started over must not be able to delete the owner's archive | Rare |
| Older generation with a different vault key | The MP of that time | Device slots are bound to `key_id` | Very rare |

The practice schedule uses the wall clock (I8): it is a memory aid. A clock set backwards by more than 5 minutes past `last_ok` makes it due at once. Any correct MP entry (unlock, backup, export, change) counts as practice and advances the step.

---

## 9. The phone's own screen lock ("home lock")

**Decision: not offered in version 1. If the user confirms they meant it, add it as a third device slot on API 30+ only.**

| Topic | Fact | Effect |
|---|---|---|
| API 30+ | Key with `setUserAuthenticationParameters(0, AUTH_DEVICE_CREDENTIAL \| AUTH_BIOMETRIC_STRONG)`; `BiometricPrompt` allowing `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` with a `CryptoObject`; no negative button [F §1.3; platform.md §3.10] | Per-use and hardware-enforced; guesses are throttled by Gatekeeper's schedule [F §2.2]. Works with androidx.biometric 1.1.0 from API 30 |
| API 28–29 | `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` unsupported; device credential with a `CryptoObject` unsupported [platform.md §3.10] | Only the weak route below |
| API 24–29 | Time-bound key (`setUserAuthenticationValidityDurationSeconds(N > 0)`) plus `createConfirmDeviceCredentialIntent` (both deprecated); any phone unlock within N s authorises the key [F §1.3] | Not per-use; not offered |
| Invalidation | Removing or forcibly resetting the screen lock invalidates the key [F §1.3] | Remove slot, MP; no data loss |
| Shared throttle | Wrong phone PINs typed in our prompt count toward the phone's own lockout and any "wipe after N" policy [F §1.3] | Acceptable; the same as at the phone's lock screen |
| Who knows the phone PIN | Often family members (ux.md §5.6) | The vault would be only as private as the phone. MASWE-0021: device-credential fallback "only for low-risk convenience features" [F §5] |

The slot format would mirror the fingerprint slot (`"device_credential": {id, key_id, alias "pv_dc_<id>", nonce, wrapped_key}`), with the same error rules as section 12.

---

## 10. Lock screen, states and auto-lock

### 10.1 Which screen opens (a pure function, `EntryPolicy`)

Inputs: vault files, `slots.json`, `state.json`, platform capabilities, clocks. Evaluated at every lock.

1. No vault file → setup (Welcome).
2. No generation readable (all damaged, or a newer major version) → the storage error screen, not the lock. It never offers deletion.
3. Read `vault_id` and `key_id` of the newest readable generation. A lock file that is missing or rejected, or whose `vault_id` differs, means no device slots. A rejected `state.json` makes the PIN unusable this time (5.4).
4. `pinUsable` = PIN slot ∧ `key_id` matches ∧ `pin_failures < 5` ∧ state readable ∧ hardware check passed at creation (recorded implicitly: release builds never create a software slot).
5. `fpUsable` = fingerprint slot ∧ `key_id` matches ∧ biometric available (11.10 rule) ∧ not hidden for this lock session.
6. Practice due:
   - with `postponed < 3` and (pinUsable ∨ fpUsable) → **MP screen** with "Later";
   - otherwise → **MP screen** without "Later", with "I can't remember it" when pinUsable ∨ fpUsable.
7. Else if pinUsable ∨ fpUsable → **PIN pad**; if fpUsable, the fingerprint prompt opens by itself; if only fpUsable, the pad area shows "Use fingerprint" and "Use master password".
8. Else → **MP screen** with the reason (10.3).

### 10.2 States

| State | Shown when | Content (look from ux.md §5.9–5.10) | Leaves to |
|---|---|---|---|
| PIN pad | 10.1 step 7 | 6 dots, keypad, fingerprint key (if fpUsable), "Use master password" | Fingerprint prompt, Opening, Wrong, MP |
| Fingerprint prompt | Automatically once per lock session when the screen is resumed and focused and no dialog is open; again from the fingerprint key | System prompt: title "Open Password Vault", negative button "Use PIN" (or "Use master password" when there is no PIN) | PIN pad (dismissed or error), Opening (success) |
| Opening | During evaluation | Keypad replaced by a progress indicator and "Opening…" | Unlocked, Wrong, MP |
| Wrong PIN | After E4 | Shake (none if animations are off), reject haptic, dots cleared, status line (10.3) | PIN pad |
| PIN limit | 7.3 | 10.3 text, then MP screen or fingerprint | MP, fingerprint |
| MP screen | 10.1 steps 6 and 8, "Use master password", PIN limit | Field, "Unlock", reason line, "Use PIN" or "Later" when allowed, "Forgot master password?" | Opening, PIN pad, Forgot sheet |
| MP wait | 7.6 | Field disabled, "Try again in 0:30" (live countdown), "Forgot master password?" | MP screen |
| Forgot sheet | "Forgot master password?" or "I can't remember it" | 11.9 when a device factor works, else 11.20 | — |
| Cannot record attempt | C3 | 7.2 C3 text; pad disabled; "Use master password" | MP |

### 10.3 Wording (additions and changes to ux.md, for the UX owner)

- Wrong PIN, with N ≥ 2 left: "Wrong PIN. N tries left."
- Wrong PIN, with 1 left: "Wrong PIN. 1 try left. After that you'll need your fingerprint or master password." If an MP replacement is pending (the user has said they forgot the MP): "Careful: you said you forgot your master password. If the next PIN is wrong, the PIN stops working."
- PIN limit: "PIN unlock was turned off after 5 wrong tries. Use your fingerprint or master password, then choose a new PIN." ux.md's "Waiting … Try again in 0:30" for the PIN is **removed**: the PIN has no waits.
- MP reasons:
  - First on this phone: "Type your master password to open your passwords on this phone."
  - After the limit: "For your safety, type your master password."
  - PIN gone: "PIN unlock stopped working on this phone. Type your master password, then choose a PIN again."
  - Temporary Keystore failure: "Your phone's key storage isn't responding, so the PIN can't be checked now. Use your master password. If this keeps happening, restart your phone." On API 33+, when `KeyStoreException.getRetryPolicy()` is `RETRY_AFTER_NEXT_REBOOT` [C `KeyStoreException.java:392-417`], say "Restart your phone to use your PIN again."
  - Older copy: "The newest copy can't be read. The copy from <date> opens with the master password you used before <date>."
  - No secure hardware: 11.21.
  - Practice: ux.md §5.10.
- MP screen buttons: "Later" ("Use PIN this time" when a PIN exists) and "I can't remember it".

### 10.4 The attempt notice

`state.json` keeps `pin_failure_times`. When a lock session starts, the app remembers how many entries existed. After the next successful unlock by any method, entries from earlier sessions not yet noticed are shown in a dialog: "Someone typed a wrong PIN 3 times (last: today 14:03). If it wasn't you, change your PIN." Buttons: "Change PIN", "OK". Then `pin_failures_noticed` is updated. A correct PIN or MP clears the list after the notice. The user's own typos in the current session are not reported.

### 10.5 Auto-lock and timeouts

| Rule | Default | Notes |
|---|---|---|
| Locked at every process start | Always | Keys only in memory (decided) |
| Lock after leaving the app | **30 s** (choices: Immediately, 30 s, 1 min, 5 min) | ux.md §5.18. A missing setting value means Immediately (V-P9) |
| Lock at once when the screen turns off | Always | Two signals: `PowerManager.isInteractive()` false at `ON_STOP` (API 20), and an `ACTION_SCREEN_OFF` receiver. That broadcast can be queued while the app is cached (platform.md §3.3), so when a late screen-off arrives after the app has resumed, it locks then |
| Lock after 5 min without a touch while open | Always | In-process timer reset on each touch; reliable in the foreground |
| Decision | `ON_STOP` records `elapsedRealtime()` in memory; `ON_START` locks if the delay passed | platform.md §3.3; nothing persisted, so nothing to tamper with |
| Activities the app launches itself (file picker, share sheet, fingerprint enrolment settings) | Grace while the result is pending, up to 10 min | V-P3. The screen-off rule still applies. A pending backup or export that is interrupted by a lock is cancelled and reported after unlock |
| "Lock now" | — | Immediate |
| On lock | Drop VK, A and KEK references; zero our arrays (crypto.md §8; never call `destroy()`, V-C10); discard the unlocked back stack (ux.md §4) | — |

---

## 11. Flows

Every flow runs its lock-file work under the lock mutex. Every "write" means the 5.6 routine. A "PIN check" is the full 7.2 protocol and is counted.

### 11.1 First setup

1. Welcome, then "Start" (ux.md §5.1).
2. Create the MP, write it down, type it again (ux.md §5.3–5.5).
3. Create the vault: VK, `vault_id`, `key_id`, generation 1, MP slot. Write, read back from disk and open with the retyped MP through the normal unlock path (V-C5). On failure: "Couldn't save. Your phone's storage may be full." with "Try again". Nothing else exists yet.
4. Write a new `state.json` (practice step 0, `last_ok` now).
5. Device check: create `pv_canary` and test it.
   - If key generation fails, or (release builds) the key is not in secure hardware → skip to step 9 with the 11.21 text.
6. PIN: choose (6 digits, blocklist), confirm. Arm the PIN slot (11.6 steps 4–8) with the MP just typed as proof.
7. Fingerprint offer (if available, 11.10 rule): ux.md §5.7. Enable as in 11.10 steps 3–9; the PIN just set is the fresh proof.
8. Backups explained (ux.md §5.8).
9. Unlocked.

If the app is killed after step 3, the next start shows the MP screen, then resumes at step 5 (ux.md §4).

### 11.2 Daily unlock with the PIN

1. 10.1 selects the PIN pad. If fpUsable, the prompt opens first; the user taps "Use PIN".
2. First digit: charge (7.2 C1–C4).
3. Sixth digit: evaluate (E1–E5).
4. Right: the vault opens in the newest generation whose `key_id` matches (storage loader). Then:
   - show the notice (10.4) if needed;
   - run the background work: rotation (API 31+), orphan sweep.
5. Target: **≤ 500 ms at the 90th percentile from the 6th digit to the list, with 1,000 entries, on a mid-range phone** (acceptance A1).
   - Estimate [I]: Argon2id(16 MiB) about 50–120 ms. This scales crypto.md's Pixel 3 estimate of about 0.3 s at 64 MiB by roughly 4×; the emulator lab measured 51–79 ms *at 64 MiB* on this desktop's CPU [L], which is not phone speed.
   - Plus 10–35 ms for the TEE HMAC [F §3], and the file read, decrypt and parse.

### 11.3 Daily unlock with the fingerprint

1. 10.1 shows the PIN pad with fpUsable; the prompt opens automatically once.
2. `getKey(pv_fp_<id>)` → `Cipher.getInstance("AES/GCM/NoPadding").init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))`. Errors: section 12.
3. `BiometricPrompt(activity, executor, callback).authenticate(info, CryptoObject(cipher))`.
   - `info`: `setTitle("Open Password Vault")`, `setNegativeButtonText("Use PIN")`, `setAllowedAuthenticators(BIOMETRIC_STRONG)`.
   - The host is the app's single `FragmentActivity` (AppCompat, ux.md §9); 1.1.0 needs it.
4. `onAuthenticationSucceeded`: take `result.cryptoObject.cipher`, call `updateAAD(AAD_fp)` and `doFinal(wrapped)`, and check for exactly 32 bytes. AAD is buffered by the client and sent at `doFinal` [C `AndroidKeyStoreCipherSpiBase.java:621-626`].
5. Open the vault (I7). `pin_failures` is **not** reset (7.4). Show the notice (10.4) if needed.
6. Errors and cancels: section 12. A cancel or "Use PIN" leaves the pad, and the prompt does not reopen until the fingerprint key is tapped or the next lock session.

### 11.4 Wrong PIN

1. E4: the attempt is already committed.
2. The screen shows the 10.3 text with the number of tries left.
3. The pad stays available; the fingerprint key stays if fpUsable.
4. On the 5th: 11.5.

### 11.5 PIN limit (lockout)

1. 7.3 L1–L4: the slot is removed and K_pin deleted. Fingerprint and MP remain.
2. The user opens the app with the fingerprint or the MP.
3. The notice lists the failure times (10.4).
4. A dialog offers "Choose a new PIN": with the MP just typed, go straight to 11.6 step 4; after a fingerprint unlock, the MP is asked first.
5. A correct MP sets `pin_failures = 0`. A new PIN slot also resets it.

### 11.6 Set or change the PIN

1. Settings › Security › "Change PIN" (or "Set a PIN" when none exists).
2. Proof:
   - Change: "Enter your current PIN" (a counted PIN check; it must yield the in-memory VK) or "Use master password instead".
   - Set: the MP, unless it was verified in the last 120 s.
3. New PIN with the blocklist (and different from the current one), then confirm. A mismatch means "The PINs don't match. Try again."
4. Ensure the canary exists. Generate `pv_pin_<new id>`:
   - API 31+ with `setMaxUsageCount(6)`; if generation fails, retry once without it and set `usage_limit = 0`;
   - run the hardware check (5.5).
5. New 16-byte salt; `A = Argon2id(PIN)`; `KEK = HMAC` (1 use); seal VK.
6. Write `state.json` with `pin_failures = 0` (so a new slot never starts with an old count).
7. Write `slots.json` with the new PIN slot.
8. Delete the old alias, if any.

On any failure in steps 4–7: delete the new alias, keep the old slot, and show "Couldn't save the new PIN. Your old PIN still works." (or "…Try again later." when there was none).

### 11.7 Turn the PIN off

Settings › Security › "Use a PIN" off. Write `slots.json` without the PIN slot, then delete the alias. With fingerprint on, daily unlock is by fingerprint, with the MP as fallback; otherwise by the MP.

### 11.8 Change the master password

1. "Type your current master password." Verify it against the newest generation's MP slot (7.6 waits apply). This counts as practice.
2. New MP, write it down, type it again (ux.md §5.3–5.5).
3. If a PIN slot exists: "Type your PIN to keep using it." This is a counted PIN check against the current slot. Keep the six digits in memory until step 7; zero them after. Skipping is allowed.
4. Build the new generation: VK2, `key_id2`, a new salt, KEK2 from the new MP. The body is re-encrypted under VK2. `generation` = 1 + the highest number on disk (V-S1).
5. Write it (storage routine). Read it back from disk and open it through the normal unlock path with the retyped new MP (V-C5).
   - If the read-back fails after the rename: rename the new file to a quarantine name (never delete), fsync the directory, and say "Couldn't change your master password. Nothing was changed. Your old password, PIN and fingerprint still work."
   - If even the quarantine rename fails: "Saving may not have finished. Next time, try your new master password first, then your old one."
6. Only now are the old device slots stale.
7. PIN re-arm for `key_id2` (if step 3 was done): 11.6 steps 4–8 with the kept digits (new key, new salt).
8. Fingerprint: "Turn fingerprint unlock on again?" → 11.10 steps 3–9 for VK2 (the MP just typed is the proof). If declined, remove the old fingerprint slot and key.
9. Remove any slot still bound to `key_id1`; delete its keys.
10. Write `state.json`: practice step 0, `last_ok` now, counters 0.
11. Storage keeps the older generations sealed under the old MP until the new MP is typed correctly at a *later* app start. The day-1 practice check is that moment. Then they are retired (V-C5, V-S5).
12. ux.md text: "Backups you made before still open with your old master password. Make a new backup now?"

### 11.9 Forgotten MP, while the PIN or fingerprint still works

**Request:**
1. From the practice screen's "I can't remember it", or Settings › Security › "Forgot master password?".
2. Sheet: "You can still open the app with your PIN or fingerprint. But without your master password you can't make backups or move to a new phone, and if this phone is lost, your passwords are lost. You can set a new master password. For your safety this takes 24 hours, so that someone who learned your PIN can't take over your passwords at once." Buttons: "Start" and "Not now".
3. "Start" asks for the PIN (a counted check), or the fingerprint when no PIN slot exists. On success, write `state.json` with `mp_replacement = {requested: now, by, proven_ms: 0, obs_boot, obs_elapsed_ms}`.
4. The user continues into the app (unlocked by that factor). While a request is pending, the practice check is suppressed.

**Waiting:**
5. Every time the app comes to the foreground, `advance()` (7.5) updates `proven_ms` and the file is written.
6. A banner shows at every unlock: "On 6 October at 14:02 someone asked to replace your master password using the PIN. It can be replaced after 7 October, 14:02. If this wasn't you, tap Cancel and change your PIN." Button "Cancel" needs no proof; cancelling is harmless.
7. A correct MP typed anywhere cancels the request: the user remembered.

**Completion**, once `proven_ms ≥ 24 h`:
8. The banner becomes "Set your new master password", then:
   - PIN check again (or fingerprint), keeping the digits;
   - then 11.8 steps 2 and 4–12, without step 1 and with step 3 already done.
9. The lock-screen sheet for a forgotten MP (ux.md §5.10) offers this path first whenever pinUsable ∨ fpUsable.

### 11.10 Turn fingerprint on

1. Availability rule (V-P1, [L]):
   - API ≥ 30: `canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`.
   - API 28–29: `SUCCESS` or `BIOMETRIC_STATUS_UNKNOWN` (−1, present in 1.1.0 [C `androidx/biometric/BiometricManager.java:61` in the 1.1.0 sources jar]), with `FEATURE_FINGERPRINT` present and `KeyguardManager.isDeviceSecure()`. The lab saw `STATUS_UNKNOWN` with a working prompt on API 28 [L].
   - API 24–27: not offered, because 1.1.0 returns `BIOMETRIC_ERROR_NO_HARDWARE` [L]. The setting shows why ("not supported on this Android version").
2. Proof: PIN or MP verified within 120 s, else ask for it.
3. Ensure the canary exists. Generate `pv_fp_<new id>` (4.2), then the hardware check.
   - The lab saw `InvalidAlgorithmParameterException` when no biometric is enrolled [L]; show "Add a fingerprint in your phone's settings first."
4. `cipher.init(ENCRYPT_MODE, key)`, with no IV (V-C7).
5. Prompt with a `CryptoObject`: title "Turn on fingerprint unlock", negative button "Cancel".
6. On success: `updateAAD(AAD_fp)`, `wrapped = doFinal(VK)`, `nonce = cipher.iv` (12 bytes).
7. Write `slots.json` with the fingerprint slot.
8. Delete the previous fingerprint alias, if any.
9. On error or cancel at any step: delete the new alias, keep the previous state, and show "Fingerprint unlock could not be turned on. You can try again later in Settings." (ux.md §5.7).

### 11.11 Turn fingerprint off

Write `slots.json` without the slot, then delete the alias. No proof needed.

### 11.12 The master-password practice check

1. 10.1 step 6 shows the MP screen with the practice reason (ux.md §5.10).
2. Correct MP: practice step + 1 (capped at 3), `last_ok` = now, `postponed` = 0, all counters 0. Open the vault.
3. "Later" (only while `postponed < 3`): `postponed` + 1, then the PIN pad or fingerprint.
4. "I can't remember it": 11.9. The app opens with the PIN or fingerprint first.
5. Wrong MP: 7.6. The screen keeps "I can't remember it", and "Later" while allowed.

### 11.13 Encrypted backup and plain exports

1. Settings › Make a backup (or Export › format, ux.md §5.19–5.20).
2. "Type your master password." Verify it against the vault's MP slot (7.6 applies), then advance practice. A typo cannot produce a backup that the user's real MP does not open (I3).
3. Backup: seal a new container with one MP slot (new salt, own key) from the same typed MP (crypto.md §10). Plain export: build the file (ux.md).
4. File picker; write; read back and verify through the provider. For a backup, verify by opening with the typed MP (V-S6).
5. If the app locks while the picker is open (screen off), cancel and say so after unlock (10.5).
6. Zero the MP and KEKs. No lock file changes besides practice and MP counters.

### 11.14 Restore on a new phone

1. Welcome › "Restore from a backup file" (ux.md §5.2).
2. Pick the file; type the MP it was made with; it opens (7.6 waits apply to repeated wrong passwords).
3. The vault is written as this phone's vault (storage), keeping the backup's `vault_id` and `key_id`.
4. Write a new `state.json` with practice step 3 (just recalled).
5. Continue at 11.1 step 5 (device check, PIN, fingerprint, backups).

### 11.15 Vault file present without lock files (transfer, cloud restore, reinstall with data kept)

1. 10.1 step 8 shows the MP screen with "Type your master password to open your passwords on this phone."
2. After the MP: write a new `state.json`; continue at 11.1 step 5.

### 11.16 Keystore: fingerprint key invalidated

1. 11.3 step 2 throws `KeyPermanentlyInvalidatedException` at `init`. This was seen in the lab after an enrolment change [L].
2. Write `slots.json` without the fingerprint slot; delete the alias.
3. The PIN pad shows: "Fingerprint unlock was turned off because fingerprints on this phone changed. Use your PIN. You can turn it on again in Settings." (ux.md §5.9). Without a PIN: the MP screen with the same first sentence.
4. After unlocking: "Turn fingerprint unlock on again?" goes to 11.10. The unlock counts as fresh proof if it was by PIN or MP.

### 11.17 Keystore: key missing or used up (PIN or fingerprint)

1. A removal signal from section 12, for example `getKey()` returning null on API 31+, the canary rule on API 24–30, or `KeyPermanentlyInvalidatedException` on `Mac.init` after the usage count ran out.
2. Remove that slot (write), then delete the alias.
3. Fall back as in 10.1 (fingerprint, else the MP) with the matching text from 10.3.
4. After unlocking, offer to set it up again (11.6 or 11.10).

### 11.18 Keystore: temporarily failing

1. Any temporary class from section 12.
2. PIN: refund the attempt (7.2 E2) and go to the MP screen with the temporary text. Fingerprint: hide it for this lock session and go to the PIN pad (which may fail too) or the MP screen.
3. **Change nothing on disk** besides the refund. Slots, keys and the vault stay as they are.
4. On the next lock session, try the device factors again.

### 11.19 Newest vault copy unreadable

1. The storage loader skips damaged generations (storage.md §5.4).
2. If the opened generation's `key_id` equals the slots' `key_id`: PIN and fingerprint work. After unlock, ux.md's "older copy" dialog appears.
3. If it differs (it predates an MP change): the MP screen with "the copy from <date> opens with the master password you used before <date>". The stale slots are kept (I6).
4. If the user types the *newer* MP and it opens nothing: "This master password belongs to the newest copy, which can't be read right now."
5. Nothing is deleted. Quarantined files stay (V-S4).

### 11.20 Start over (MP forgotten and nothing else works)

1. Forgot sheet › "Start over…". New dialog text: "Your locked passwords will be put aside, not deleted. If you remember your master password later, open them from Settings › Old locked copies." Checkbox "I understand", button "Put aside and start over".
2. Rename `filesDir/vault/` to `filesDir/archive/vault-<UTC yyyyMMdd-HHmmss>/` (same filesystem, so atomic) and fsync `filesDir`.
3. Delete `slots.json`, `state.json` and the `pv_pin_*` and `pv_fp_*` keys. They belonged to the archived vault, whose MP slots are intact.
4. Welcome (11.1).
5. Settings › "Old locked copies" (shown only when one exists):
   - "Open" asks for *that copy's* MP, then makes it the current vault after archiving the current one the same way;
   - "Delete" asks for *that copy's* MP plus a confirmation.
   - Archives are never pruned automatically.

### 11.21 Phone without hardware-backed Keystore

1. The device check (11.1 step 5) fails the hardware rule (5.5).
2. Text: "This phone can't keep a PIN or fingerprint key in protected hardware, so the app opens with your master password only." Settings show PIN and fingerprint as unavailable, with that reason.
3. Every unlock uses the MP; practice checks are redundant and suppressed.

---

## 12. Error classification

Classes: **GONE** (remove that one device slot, delete its alias, and fall back; I9), **TEMPORARY** (use another method this time and change nothing), **WRONG** (a counted wrong PIN), **DISMISSED** (a user or system cancel), **UNAVAILABLE** (cannot be set up now). Removing a slot is always safe: it costs a re-setup, never data.

Source basis [C]:
- android17: `engineGetKey` returns null only for KEY_NOT_FOUND [`FB/keystore/java/android/security/keystore2/AndroidKeyStoreSpi.java:123-140`]. Init errors map KEY_NOT_FOUND and KEY_PERMANENTLY_INVALIDATED to `KeyPermanentlyInvalidatedException`; LOCKED and KEY_USER_NOT_AUTHENTICATED to `UserNotAuthenticatedException`; everything else to a plain `InvalidKeyException` [`KeyStoreCryptoOperationUtils.java` `getInvalidKeyException`].
- HMAC `doFinal` wraps any Keystore error in `ProviderException` [`AndroidKeyStoreHmacSpi.java:228-242`]. Cipher `doFinal` maps VERIFICATION_FAILED to `AEADBadTagException` and others to `IllegalBlockSizeException` [`AndroidKeyStoreCipherSpiBase.java:600-640`].
- **API 23–30 (keystore1):** `engineGetKey` returns null whenever `contains()` is false, and `contains()` is false on any error code or `RemoteException` [android11-release `AndroidKeyStoreSpi.java:95-112`, `KeyStore.java:290-297`; nougat-release `AndroidKeyStoreSpi.java:90-104, 832-838`, `KeyStore.java:198-205`]. KEY_NOT_FOUND at init becomes a plain `InvalidKeyException` there [nougat `KeyStore.java:676-727`]. **So "`getKey()` returned null" is not conclusive on API 24–30**; V-C6's rule holds only on API 31+.

| Operation | Outcome | API | Class | Action |
|---|---|---|---|---|
| `getKey(alias)` | Key | all | — | Continue |
| | null | 31+ | GONE | — |
| | null; then `getKey(pv_canary)` non-null; then `getKey(alias)` null again | 24–30 | GONE | — |
| | null and the canary also null | 24–30 | TEMPORARY | Record `missing_alias {alias, boot}`. If the same holds on a later boot: GONE, then recreate the canary |
| | throws (`UnrecoverableKeyException`, `KeyStoreException`, runtime) | all | TEMPORARY | — |
| `Cipher.init` / `Mac.init` | `KeyPermanentlyInvalidatedException` | all | GONE | Covers enrolment change, screen-lock removal, usage count exhausted [C Javadoc `setMaxUsageCount`; L] |
| | `UserNotAuthenticatedException` | all | TEMPORARY | — |
| | plain `InvalidKeyException` | 31+ | TEMPORARY | V-C6 |
| | plain `InvalidKeyException` | 24–30 | Run the `getKey` rows above; GONE only if they say GONE | — |
| | `InvalidAlgorithmParameterException` (fingerprint decrypt: bad stored nonce) | all | GONE | The slot is corrupt |
| `Mac.doFinal` (PIN) | `ProviderException` | all | TEMPORARY | Refund the attempt. A usage limit exhausted mid-operation surfaces here; the next `init` gives `KeyPermanentlyInvalidatedException` |
| Software AES-GCM open of the PIN wrap | `AEADBadTagException` | all | WRONG | — |
| | Opens, but not 32 bytes | all | GONE | — |
| Fingerprint `doFinal` after success | 32 bytes | all | — | Unlock |
| | `AEADBadTagException` | all | GONE | The stored wrap does not match the key; the lab saw this on a tampered wrap [L] |
| | `IllegalBlockSizeException`, `ProviderException`, `KeyStoreException` | all | TEMPORARY | The lab saw KEY_USER_NOT_AUTHENTICATED once after a success callback that came late [L] |
| | Not 32 bytes | all | GONE | — |
| `onAuthenticationError` | 5 CANCELED, 10 USER_CANCELED, 13 NEGATIVE_BUTTON | 28+ | DISMISSED | Show the pad |
| | 7 LOCKOUT | | TEMPORARY | "Fingerprint is locked for now. Use your PIN." (ux.md §5.9) |
| | 9 LOCKOUT_PERMANENT | | TEMPORARY | "Fingerprint is locked until you unlock your phone with its PIN, pattern or password. Use your app PIN." |
| | 11 NO_BIOMETRICS | | TEMPORARY | The next `init` will throw `KeyPermanentlyInvalidatedException` → GONE |
| | 1, 2, 3, 4, 8, 12, 15 | | TEMPORARY | Short message; keep the slot |
| `onAuthenticationFailed` | Finger not recognised | | — | The system prompt retries; no action |
| Key generation | `InvalidAlgorithmParameterException` with "At least one biometric must be enrolled" [L] | | UNAVAILABLE | "Add a fingerprint in your phone's settings first." |
| | `StrongBoxUnavailableException` | | — | Never requested |
| | `ProviderException` / `KeyStoreException`; `ERROR_TOO_MANY_KEYS` (API 37) | | UNAVAILABLE | Run the sweep and retry once |
| | Fails only with `setMaxUsageCount` | 31+ | — | Retry once without it; `usage_limit = 0` |
| | Hardware check fails | | UNAVAILABLE (release) | 11.21 |
| `deleteEntry` | `KeyStoreException` | | — | Ignore; the sweep retries |
| Argon2 | `UnsatisfiedLinkError` (catch `Throwable`, V-C2) / `OutOfMemoryError` | | — | Fall back to Bouncy Castle after a free-heap check. If still failing: TEMPORARY, refund the attempt, show the memory message (ux.md §5.2) |
| Lock-file read | Missing, parse error, higher version | | — | `slots.json`: absent. `state.json`: fail closed (5.4) |
| Lock-file write | Any failure except EINVAL on the directory fsync | | — | The operation is not applied. A failed charge means no evaluation (7.2 C3) |

**Never**: no exception from any row deletes or rewrites vault data (I1, I9). `containsAlias()` is never used as evidence (V-C6).

---

## 13. Implementation notes

**Package `lock/`.** Pure Kotlin unless marked; Android pieces sit behind interfaces so that JVM tests can replace them.

| Class | Role |
|---|---|
| `LockManager` | Orchestrator; owns the `Mutex`; the only writer of lock files and device keys |
| `LockFiles` (Android: `Os.*`) | 5.6 routine; strict parse and serialise of 5.3 and 5.4 |
| `EntryPolicy` | 10.1 as a pure function |
| `PinGuard` | Charge, evaluate, refund and limit state machine (7.2, 7.3) over `LockFiles` and `Clocks` |
| `PinSlotCrypto` | 4.3 derivations; takes a `TeeMac` interface (real: `Mac` with the Keystore key; test: plain HMAC) |
| `FingerprintSlot` (Android) | Key specs, cipher init, prompt glue; 1.1.0 classic `BiometricPrompt` constructor |
| `KeystoreGateway` (Android) | `getKey`, generate, delete, aliases, `KeyInfo` check, canary. A fake implements keystore2's usage-count behaviour |
| `KeystoreErrorClassifier` | Section 12, with the API level as input |
| `ElapsedProof` | 7.5 |
| `PracticeSchedule` | Section 8 |
| `PinBlocklist` | Section 6 |
| `MpReplacement` | 11.9 |
| `AutoLock` (Android) | 10.5 |

`Clocks` exposes `elapsedRealtime()`, `wallNow()` and `bootCount(): Int?`.

Further rules:
- **Threading.** Argon2id and Keystore calls run on `Dispatchers.Default`; file I/O on `Dispatchers.IO`; UI state comes from a ViewModel. The PIN digits are held in a `ByteArray` in the ViewModel, never in saved state, and zeroed after use (crypto.md §8, ux.md §3).
- **API gating.** `setMaxUsageCount` and `KeyInfo.getSecurityLevel` from 31; `setUserAuthenticationParameters` from 30; `setInvalidatedByBiometricEnrollment` from 24; `Settings.Global.BOOT_COUNT` from 24; `Os.rename` and `Os.fsync` from 21 [C api-versions.xml, checked today].
- **Manifest and platform** (already decided elsewhere): `FLAG_SECURE`; `filterTouchesWhenObscured`; `setHideOverlayWindows(true)` on 31+; `isSensitiveData` on the PIN dots and keys where TalkBack allows (V-P5; window lab); backup rules that never include `noBackupFilesDir` (it is always excluded anyway [F §7.4]).
- **Debug-only test hooks** (stripped from release by R8 through a debug source set): pause after the charge commit, after `E3`, between rotation steps, and during the MP change. They support the emulator tests in 14.2.
- **Limits.** At most 3 keys plus transient ones per install, far below the API 37 cap of 50,000 [F §1.1].

---

## 14. How to test it

### 14.1 JVM unit tests (no Android)

| ID | Test | Pass condition |
|---|---|---|
| T1 | Blocklist, all 10^6 PINs | Exactly 126,337 blocked; reason counts date 100,945, repeat 12,700, sequence 4,328, keypad 3,958, pattern 2,340, steps 2,066. Vectors: 123456, 654321, 789456, 123457, 001234 sequence; 111111, 000001 repeat; 112233, 121212, 696969 pattern; 102030, 142536 steps; 159753, 147896, 741236 keypad; 250890, 010190, 199012, 071990 date; 582913, 143637, 905127, 368014 allowed. Oracle: `bench/blocklist.py` |
| T2 | Derivation vectors (4.3) on BC and on argon2kt (instrumented) | A, KEK, wrapped as listed; PIN 582914 → `AEADBadTagException`; the 64 MiB vector from crypto.md |
| T3 | `PinGuard` property test: random sequences of digit, clear, submit, crash, restart, Keystore temporary, Keystore gone, right and wrong evaluations | (a) No evaluation without a committed charge at least 1,000 ms old (fake clock); (b) evaluated wrong attempts between resets ≤ 5; (c) `pin_failures` never below the number of evaluated wrong attempts since the last reset; (d) the slot is removed exactly at 5 |
| T4 | Crash injection: a fake filesystem that can stop at every write boundary (before the tmp write, after the tmp fsync, after the rename, after the directory fsync) in every flow of 7.7 | After "restart": the MP opens the newest proven generation; I1–I6 and I9 hold; no flow ever deletes or overwrites a vault generation |
| T5 | `ElapsedProof` with simulated reboots (boot count changes, elapsed resets, boot count missing) and wall-clock jumps of ±1 year | `proven ≤` true elapsed time, always; `proven ≥` time since the last boot |
| T6 | `PracticeSchedule` | Due on days 1, 3, 7, 21, 35 after creation with timely entries; clock set back by more than 5 min → due; at most 3 "Later" |
| T7 | `EntryPolicy` table | Every combination of vault, slots, state, key_id match, failures, practice, postponements, replacement, hardware and fingerprint availability gives the mode in 10.1 |
| T8 | `KeystoreErrorClassifier` | Every row of section 12 for API 24, 28, 30, 31, 33, 37 |
| T9 | Lock-file parser | Strict rejections from 5.3; fuzzing never throws past the parser; a rejected state fails closed |
| T10 | Fake keystore with keystore2's semantics (decrement after `finish`, delete at the last use, output withheld if the decrement fails [C `KS2/operation.rs:426-460`]) | Exactly 5 attempts after creation or rotation; rotation failure degrades to MP, never to loss |
| T11 | MP waits | 5 free, 30 s for 6–10, 5 min after; reboots restart the wait; never a lockout |
| T12 | Spy on the vault store during 1,000 random lock-event sequences | Zero vault writes (I1) |

### 14.2 Emulator and instrumented tests

These are for later; this design used no emulator. Run them on API 24, 28, 30, 33, 35 and 37 images, in debug builds with the software-Keystore override (5.5).

| ID | Test | API |
|---|---|---|
| E1 | Real AndroidKeyStore PIN slot: create, unlock, wrong, limit; alias gone after the limit | all |
| E2 | `setMaxUsageCount(6)`: 5 attempts, then `getKey()` null and `KeyPermanentlyInvalidatedException` on init; rotation replaces the alias | 31+ |
| E3 | Process killed at each test hook (`am force-stop`), then relaunched | all: counter persisted; nothing evaluated twice |
| E4 | Reboot during an MP wait; wall clock moved ±1 day | all: the wait restarts; the PIN budget is unchanged |
| E5 | Clear the app's cache (Settings or `pm trim-caches`) during an attempt | all: no effect |
| E6 | Fingerprint (`adb emu finger touch`): enable, unlock, enrol another finger → GONE; remove the screen lock → GONE through the canary rule | 28, 30, 33, 37 (enable not offered on 24) |
| E7 | biometric 1.1.0 availability matrix (11.10) | 24 (not offered), 28 (offered on UNKNOWN), 30+ |
| E8 | Backup transport (`bmgr` where available): lock files never restored | 28+ |
| E9 | Argon2 fallback under a small heap (lab's `heapsim.sh`; Android 17 `am memory-limiter`) at 16 MiB and 64 MiB | 24, 37 |
| E10 | Keystore fault simulation: the `KeystoreGateway` debug flag forces each section 12 exception | all: the classes in section 12 |

### 14.3 Real phones (needed, not available today)

- Timing for acceptance A1 on one mid-range phone (for example a 2023 Galaxy A-series) and one low-end phone.
- One Samsung and one Xiaomi for OEM Keystore behaviour, including whether `setMaxUsageCount` generation succeeds.
- API 28–29 fingerprint with 1.1.0 and a `CryptoObject` (V-P1 notes Samsung exceptions on 28).

### 14.4 Acceptance criteria

- **A1.** PIN unlock ≤ 500 ms at the 90th percentile from the 6th digit to the list (1,000 entries, mid-range phone). If missed, lower the PIN KDF to t = 2 (device-only, so no format change is needed beyond the recorded `t`).
- **A2.** After 5 wrong PINs the PIN slot and alias are gone, whatever kills, reboots or clock changes happened in between (E3, E4, T3).
- **A3.** 100 % of T4 crash points keep the vault openable with the MP.
- **A4.** No lock event writes the vault (T12).
- **A5.** The vectors of T1 and T2 pass on the JVM and on a device.
- **A6.** Every section 12 row behaves as listed (T8, E10); no Keystore fault removes anything but its own slot.
- **A7.** Backup and plain export cannot start without a verified MP.

---

## 15. Requests to other designs

1. **Crypto and storage (vault header):** add `vault_id`, `key_id` and `generation` to the authenticated header (5.2).
2. **Storage:**
   - never write the vault on lock events (I1);
   - expose the newest readable generation's `vault_id` and `key_id` before unlock;
   - report which generation an MP opened (V-S3);
   - keep previous-MP generations until the new MP is typed correctly at a later app start;
   - archive on "Start over" and never prune archives (11.20);
   - recommend including the vault directory, never `noBackupFilesDir`, in `<device-transfer>`; the owner decides.
3. **Crypto:** accept the PIN slot construction (4.2–4.3) and its Argon2id(16 MiB, t = 3, p = 4) device-only parameters.
4. **UX:**
   - remove the PIN "Waiting" state;
   - fingerprint stays available after the PIN limit;
   - add the attempt notice (10.4), the last-try warning, "Later" and "I can't remember it" on the practice screen, the replacement banner and flow (11.9), the archive wording and "Old locked copies" (11.20), and the PIN-rule texts (section 6);
   - PIN length 6 is confirmed.
5. **Platform:** read `BOOT_COUNT`; release builds refuse software-backed device keys; debug override for emulators (5.5).
6. **Labs:** confirm on API 24, 28 and 37 the canary rule, the usage limit, the `KeyPermanentlyInvalidatedException` paths, and E3–E4.

---

## 16. Open questions

1. Did the user mean the app's own PIN (this design) or the phone's screen lock (section 9)? Ask them.
2. Blocking every date removes 10.1 % of PINs. Is the extra choosing effort acceptable for this user?
3. Is a 24-hour wait acceptable for replacing a forgotten MP, and is a PIN or fingerprint enough to request it?
4. Argon2id(16 MiB) timing on real low-end and mid-range phones has not been measured; A1 decides whether t drops to 2.
5. Should the vault file travel in device-to-device transfer (storage owner)? This design works either way.
6. Is the storage write-cache residual risk (7.8) acceptable, given that apps cannot reach Gatekeeper's secure storage?
7. Is 5 the right PIN limit for this user (NIST allows up to 10)? A higher limit means fewer MP prompts and more guesses for someone nearby.
8. Should a fingerprint unlock reset the PIN counter? This design says no (7.4).
9. Is fingerprint on Android 7–8.1 worth adopting biometric 1.4.0-alpha07 with the classic constructor? This design does not need it.
10. Do OEM keystores accept `setMaxUsageCount` on HMAC keys (14.3)? The design degrades to no limit if not.
11. Can TalkBack operate a PIN pad marked `isSensitiveData` (window lab)?

---

## 17. Sources

- **Read today:**
  - FACTS.md (all sections) and its downloads: AOSP files in `lock-facts/aosp/`; Markert et al. and the PIN lists in `lock-facts/web/pins/`; DroidCCT and KeyDroid timing; CDD 7.0 and 17; NIST SP 800-63B-4; OWASP MASWE-0020/0021.
  - crypto.md, storage.md, platform.md, account-based.md; VERIFICATION.md and `verification/crypto_code.md`; ux.md.
- **AOSP re-read or fetched today for this design** (android17-release unless named):
  - `KS2/operation.rs:420-460`; `KS2/enforcements.rs:316-331, 530-545`; `KS2/database.rs:1203-1208, 2303-2337`; `KS2/super_key.rs:95-125, 880-1000`.
  - `FB/keystore/java/android/security/keystore2/AndroidKeyStoreSpi.java:115-185, 1126-1150`; `KeyStoreCryptoOperationUtils.java`; `AndroidKeyStoreHmacSpi.java:94-242`; `AndroidKeyStoreCipherSpiBase.java:600-640`; `FB/keystore/java/android/security/KeyStoreException.java`; `FB/…/KeyGenParameterSpec.java:1541-1906`; `FB/core/java/android/provider/Settings.java:19098-19104`.
  - android11-release `keystore/java/android/security/keystore/AndroidKeyStoreSpi.java`, `keystore/java/android/security/KeyStore.java`; nougat-release same files. Fetched with `lock-facts/gs.sh`.
- **API levels:** `api-versions.xml` of platform 37 through `lock-facts/apiv.py`.
- **Experiments today** (`scratchpad/design/lock-designs/bench/`):
  - `blocklist.py`, `variants.py` (blocklist and coverage);
  - `tputm.c` (Argon2id throughput: 16 MiB vs 64 MiB, 1 and 8 threads, i9-14900K under a load average of about 10);
  - `vec.c`, `PinVector.java` with BC 1.86 (vectors).
- **Lab logs consulted (preliminary, [L]):**
  - `scratchpad/lab/biometric/logs/bootA_PVBIO_lib1.1.0.log` (API 37: key creation without enrolment, caller-IV refusal, `AEADBadTagException` on a tampered wrap, `KeyPermanentlyInvalidatedException` after enrolment change, `DEVICE_LOCKED` as `InvalidKeyException`, a late KEY_USER_NOT_AUTHENTICATED after success, SOFTWARE security level);
  - `scratchpad/lab/old-android/logs/bio-api24-*`, `bio-api28-*`, `all-api24.txt`, `all-api28.txt` (1.1.0: NO_HARDWARE on 24, STATUS_UNKNOWN with a working prompt on 28; 1.4.0-alpha07: SUCCESS on both; emulator keys not in secure hardware; argon2kt 64 MiB 51-79 ms on the emulator).
