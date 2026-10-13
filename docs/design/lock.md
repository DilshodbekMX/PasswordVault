# The lock: how the user gets into PasswordVault

Final specification. Date 2026-10-06. It replaces the three proposals in `lock-work/` and is the document `ux.md` and `i18n.md` call "the lock design" or "lock.md". It was revised on the same day after an independent audit. The section "Audit" at the end lists what was raised, what changed and what did not.

**What this is built from.**
- Base: `lock-work/design-3.md` (failure and recovery first). The three judges gave it the highest combined score: security 6.0, recovery 7.0, usability 7.0, total 20.0. Design 1 got 17.0 (7.0 / 5.5 / 4.5) and Design 2 got 16.0 (4.0 / 4.5 / 7.5).
- Every critical and major defect the judges found in Design 3 is fixed here (table in 1.2).
- Parts of Designs 1 and 2 that the judges recommended are grafted in (1.3).
- Three things settled after the designs were written are applied: `docs/research/VERIFICATION.md`, the emulator labs in `docs/lab/`, and the user's confirmation that the lock is the app's own PIN.

**How claims are marked.**
- **[V-xx]** an item of `docs/research/VERIFICATION.md`.
- **[L-bio]**, **[L-old]**, **[L-win]**, **[L-sto]** observed in `docs/lab/biometric.md`, `old-android.md`, `window-input.md`, `storage-durability.md`.
- **[F §n]** the fact sheet `lock-work/facts.md`.
- **[J-S]**, **[J-R]**, **[J-U]** the security, recovery and usability judgements in `lock-work/`.
- **[D1]**, **[D2]**, **[D3]** the three designs.
- **[X]** checked or computed by me today; the scripts are reproduced in the appendices, because the scratchpad they ran in does not survive a reboot.
- **[A]** raised by the audit of this document, and checked again when the document was revised.
- **[I]** my judgement. Check it before relying on it.

Short names: **MP** master password, **VK** vault key (32 random bytes that encrypt the vault), **the Keystore** Android Keystore, **TEE** the phone's secure hardware.

---

## 1. Summary

### 1.1 The design on one page

1. **One root, two shortcuts.** The master password alone opens the vault file and every backup, on any phone. The PIN and the fingerprint each unlock a copy of the vault key that exists only on this phone, in `noBackupFilesDir`, bound to a Keystore key. Losing a shortcut never loses data.
2. **PIN.** Exactly 6 digits, typed on a pad the app draws, submitted by the sixth digit. A rule-based blocklist refuses 12.63 % of all PINs: every date, year, repeat, sequence and keypad shape (section 7).
3. **How the PIN opens the vault.** `KEK_pin = HMAC-SHA256` of the PIN under a Keystore key that never leaves the secure hardware; `KEK_pin` unwraps VK. There is no key stretching on this path, so the vault opens about 0.1 to 0.3 s after the sixth digit [I, to be measured]. A copy of the app's files cannot test even one PIN, because the HMAC key is not in the copy.
4. **Two limits, both without waiting times.** The PIN stops working after **5 wrong PINs in a row**, or after **10 wrong PINs that were not forgiven** between two master-password entries. A right PIN on the lock screen forgives the wrong PINs typed in the 60 seconds before it, at most two: the owner's own slips. Only the master password returns the second count to zero.
5. **Count first, check later.** A try is written to disk when the *first* digit is typed. The PIN is checked only when that write is at least 1 second old. Killing the app, cutting power, rebooting, changing the clock or clearing the cache gives no extra try (model-checked, section 6.7).
6. **At the limit** the PIN's Keystore key is deleted and the PIN slot removed. Fingerprint and master password keep working. Nothing else changes, and no vault data is touched.
7. **Fingerprint.** `androidx.biometric` **1.4.0-alpha07**, used only through the classic `BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)` constructor, Class 3 only, with a `CryptoObject`. It worked on Android 7, 9 and 17 in the labs. Version 1.1.0 cannot reach the sensor on Android 7 and 8 and cannot be repaired (section 9.1).
8. **Where state lives.** The vault file carries only the master-password slot plus three header fields (`vault_id`, `vk_id`, `generation`). Three small device-only records per vault hold everything else: `pin.rec`, `fingerprint.rec`, `state.rec` (section 5).
9. **The master password is typed** at setup; on a phone's first unlock; when no shortcut works; on a practice schedule (days 1, 3 and 7, then every 14 days, skippable for a few days); for every backup and plain export; and to add a way in. It is **not** asked after a reboot.
10. **Proof for changes.** Making the lock stricter needs nothing. Changing the PIN needs the current PIN or the master password. Adding a way in (a PIN when none exists, fingerprint) needs the master password typed in the same flow. A fingerprint approves nothing, with one exception: the request of item 11 when no PIN exists.
11. **A forgotten master password can be replaced** while the PIN or the fingerprint still opens the app. The request needs the PIN typed now; the fingerprint is enough only when there is no PIN. The user chooses the new master password at once, and it starts working after **24 hours counted while the phone is on** (6.9). A banner at every unlock lets anyone cancel. Finishing makes a new vault under the new master password and puts the old one aside, whole. The old master password keeps opening the passwords as they were, and nothing else can open or delete them through the app (section 12, F11, F16).
12. **Keystore errors.** A shortcut is removed only on a short list of conclusive signals. Everything else means "use another way this time, change nothing". A reference key (`pv_canary`) tells "this key is gone" from "the Keystore is not answering" on every Android version.
13. **Nothing the lock does can delete the vault.** No wrong PIN, limit, Keystore error or lock-screen button writes, renames or deletes a vault file. "Start with an empty app" puts the vault aside together with its PIN and fingerprint state.
14. **Auto-lock.** 30 s after leaving the app (choices: Immediately, 30 s, 1 min, 5 min); at once when the screen turns off; after 5 minutes without a touch while the app is in front; always at process start. A file picker or share sheet the app opened itself does not lock the app for up to 10 minutes.
15. **The phone's own screen lock is not an unlock option in version 1** (section 2.4).

### 1.2 What changed from Design 3, and why

| Judge's finding in Design 3 | Severity | What this document does |
|---|---|---|
| [J-S 3-1] A waiting time credited the whole uptime whenever `BOOT_COUNT` changed; `adb shell settings put global boot_count N` changes it without a reboot, so a 24-hour wait took minutes | critical | Time is credited from `elapsedRealtime()` alone (6.9). `BOOT_COUNT` is not read at all |
| [J-S 3-2] Replacing a forgotten MP needed one factor and 24 h; the owner's only defence was an English banner | major | Kept at one factor and 24 h, because three of the four voices (the recovery judge, the usability judge and ux.md) want a short wait and "every enabled factor" can be bypassed by turning a factor off first (decision 14). The factor is the PIN whenever a PIN exists, as in Design 3; a fingerprint is enough only when there is none. The rest is met another way: the wait cannot be shortened; the banner is in all four languages and anyone can cancel; adding a way in needs the MP; and finishing puts the old passwords aside as a vault of their own, which the old MP opens and nothing else can open or delete (F11, F16). The audit showed what is left of the defect, and 2.3 states it: an owner who does not open the app during the wait is not warned, and after it the new MP can export |
| [J-S 3-3] A correct PIN reset the count, so someone near the owner got 4 new guesses before every owner unlock | major | A second count: 10 wrong PINs that were not forgiven, between two MP entries (6.4). A right PIN forgives the owner's own slips, that is, the wrong PINs of the last 60 seconds, at most two. That removes the brittleness [J-R D1-1] and [J-U] found in Design 1's version of this budget, and leaves someone near the owner at most two hidden guesses for each hand-over they can stage (6.4, 2.3) |
| [J-R D3-1], [J-U 4.3-1] The Keystore usage limit (6 uses, renewed by key rotation) could run out before the screen said so; "N tries left" could be false | major (twice) | Removed. One long-lived HMAC key with no usage limit [D2]. One counter decides and the screen shows it |
| [J-R D3-2] Uzbek apostrophes: the same password typed on another keyboard gave another key; MP waits also applied when opening a backup | major | Apostrophe-like characters are folded from the first release, after NFC (8.1). No waits when opening a backup file (6.8) |
| [J-S 3-4] PIN or a 120-second window was enough to add fingerprint | minor | Adding a way in needs the MP typed in the same flow; there is no window (8.2) |
| [J-S 3-5] No `setUnlockedDeviceRequired` | minor | Set on the PIN key on API 35+ (4.2) |
| [J-S 3-6], [J-U 4.3-2] Argon2id (16 MiB) on every PIN unlock | minor | Removed |
| [J-S 3-7] A refund was `failures − 1` | minor | A separate `charged` flag; a refund can only clear the flag (6.3) |
| [J-R D3-3] Old keys deleted right after a record write | minor | Replaced keys are deleted only by a sweep at a later process start (5.7) |
| [J-R D3-4] MP waits grew to 5 minutes | minor | At most 30 s (6.8, decision 12) |
| [J-R D3-5], [J-U 4.3-6] The "restart your phone" message keyed on `RETRY_AFTER_NEXT_REBOOT` would never show | minor | Dropped. The temporary-error text always suggests a restart |
| [J-R D3-6], [J-U 4.3-5] A shortcut that fails "temporarily" forever was never offered again | minor | The text for a temporary failure says where the shortcut can be set up again, and Settings can always do it. The app removes nothing and keeps no count (F15) |
| [J-R D3-7] A PIN lockout during the wait stranded an owner without fingerprint | minor | The new MP is chosen when the request is made and works on its own after the wait (F11) |
| [J-R D3-8] No test that the PIN key survives removal of the screen lock | minor | Emulator check E7 |
| [J-U 4.3-3] "Later" counted lock sessions | minor | A time-based grace, asked at most once a day (8.3) |
| [J-U 4.3-4] The banner promised an exact time | minor | It shows the remaining phone-on time, says that the countdown stops while the phone is off, and says how to keep it moving (13.4) |
| [J-U 4.3-7] The largest blocklist | minor | Kept; the judges disagree (decision 6) |
| [J-U S1] Samsung on Android 9 could never turn fingerprint on with 1.1.0 | minor | The 1.4 alpha carries its own fingerprint code (9.1); a real-phone check is listed |
| [J-U S2] Two different "Enter your PIN" screens | minor | The pad says "Enter your app PIN"; setup says "This is not your phone's PIN"; a specific message after that failure (13.3) |
| [J-U S3] "Fingerprint for apps" switched off was never explained | minor | Message in 13.3 |
| [J-U S4] TalkBack with sensitive keys untested | minor | Emulator check E12 with a defined fallback |

### 1.3 Taken from Designs 1 and 2

- **From Design 2:** the PIN slot (one HMAC key, no stretching, no usage limit); the charge flag and the model check; the same wrong PIN typed again is not counted; one PIN wrap per vault key so that a master-password change needs one PIN entry and survives a crash at any point; the record framing (magic, length, SHA-256); the 30-second ceiling on master-password waits; the architecture test that forbids the lock code from touching vault files; the time-based practice grace.
- **From Design 1:** the Keystore error rules (`UnrecoverableKeyException` without a cause means invalidated; `isTransientFailure()` is never used); `vault_id` and `vk_id` checked before a try is spent; the long-run budget of 10 (changed as above); the master password for adding a way in; a record with an unknown version is neither changed nor used for clean-up; apostrophe folding and the keyboard hint; "making the lock stricter needs nothing".
- **From the recovery judge:** the new master password chosen at request time; keeping the passwords as they were under the old master password after a replacement. The judge asked for at least 30 days; here they stay until the old master password deletes them.

### 1.4 Left out on purpose

- Single-use attempt keys, key rotation, usage counts, StrongBox [D1, D3]: two judges rated their effects as major defects, and they defend only against root.
- Growing waits, any wipe, any deletion in response to a failure.
- A time window in which settings need no proof [D1's 5 minutes, D3's 120 seconds].
- Blocking fingerprint after the PIN limit [D2]: it stops no attacker and strands an owner who forgot the master password [J-R D2-1].
- The phone's screen lock as a way in (2.4).
- Three things an earlier draft of this document had, removed after the audit because each cost more rules than it earned [A]: a 5-minute expiry of the remembered wrong PINs (6.6); a count of the boots on which a Keystore key had failed (F15d); and asking Android when the phone was last unlocked, in order to lock after a missed screen-off (10.2).

---

## 2. Scope, threat model and limits

### 2.1 The seven hard requirements and where they are met

| # | Requirement | Met by |
|---|---|---|
| 1 | The master password alone always opens the vault file and any encrypted backup, on any phone | 3 (I2, I3, I10), 4.1, 5.1, 8.1; the master-password path reads no device record and needs no successful write (6.8) |
| 2 | A copy of everything the app stores, without the secure hardware, still needs the master password; the PIN does not help offline guessing | 4.2, 4.6; release builds refuse software-backed keys (4.5) |
| 3 | A thief gets a small fixed number of PIN guesses; kill, power cut, reboot, clock change and cache clearing give no more | 6. The number is per vault (2.3) |
| 4 | Bank-like: app-drawn pad, open well within a second, fingerprint prompt by itself with the pad available | 4.2, 7.1, 9.2, 13, acceptance A1 |
| 5 | Works from API 24 to 37 | 9.1, 9.4, the API gates in 4.2, 4.5 and 13.5, checks E1 to E17 |
| 6 | Never delete or overwrite vault data on a wrong PIN, a lockout or a Keystore error | 3 (I1, I6, I9), 14, tests T13, T14 and T17 |
| 7 | The user must not forget the master password | 8.3, F10, F11, F12, the standing banner of 13.4 |

### 2.2 Who the lock defends against

| # | Attacker | Has | Gets at most |
|---|---|---|---|
| A1 | Copier | Every file the app stores (vault generations, lock records, backups), but not the phone's secure hardware | Nothing without the master password |
| A2 | Thief or finder | The phone, locked or unlocked, no root, bootloader locked; can kill the app, reboot, change the clock, clear the cache, use `adb` if debugging is on | 5 guesses at the vault's PIN. On a phone that also holds vaults that were put aside with a working PIN: 5 at each of those too (2.3) |
| A3 | Someone near the owner | Picks up the phone on many days | 10 PIN guesses between two master-password entries, each of them reported to the owner at the next unlock. Neither counted nor reported: at most two guesses on each occasion on which they get the owner to type the right PIN within a minute, on a screen that says "Wrong PIN. N tries left." (6.4) |
| A4 | Someone who saw the PIN | The PIN and the unlocked phone | What any unlocked session shows. Without a master password: no plain export, no backup, no added fingerprint, no new PIN after it is off. A master password of their own after 24 credited hours, unless someone who opens the app in that time cancels it; from then on everything a master password allows, export and backup included (2.3). They cannot lock the owner out through the app: the passwords as they were stay on the phone as a vault that only the owner's master password opens or deletes (F11, F16b). Android's own "Clear storage" and uninstall delete that vault with everything else (2.3) |
| A5 | Someone whose finger is saved on the phone | A fingerprint the phone accepts, and the phone | What any unlocked session shows. Nothing that needs a typed secret. A new master password only when no PIN exists (F11 step 3). They can bring that about by turning the PIN off with five wrong tries, which the owner is told about; the 24 credited hours and the banner then apply as for A4 |

### 2.3 What the lock does not defend against (say so in Help)

- **A phone rooted later.** Root can skip the app's counter and guess through the secure hardware at about 30 to 90 tries per second [F §3], so every allowed PIN falls in roughly 3 to 9 hours. On API 35+ this works only while the phone is unlocked, unless Smart Lock or a Class 2 face unlock is on [J-S §1 row 8]. After the PIN limit the key no longer exists. The master password still protects every copy. This is the weak spot of any app PIN, because apps cannot reach Gatekeeper or Weaver [F §2.3].
- **A PIN that is also the phone's PIN or a bank-card PIN.** Someone who knows it from elsewhere needs one guess [F §6.1, Munyendo]. Setup asks the user not to do this.
- **Power cuts timed within a second of a try.** `fsync` on storage mounted `nobarrier` is not yet on flash when it returns [V-S8]. Ageing the write by 1 second narrows the gap; only the phone's own lock screen has tamper-proof counter storage [F §2.1].
- **Overlays on Android 7 to 11** (`setHideOverlayWindows` needs API 31) and **accessibility services the user enabled on Android 7 to 13** (`isSensitiveData` takes effect from API 34), which read what is shown on the screen; a password field drawn as dots gives them mask characters only (13.5) [L-win, J-S §8].
- **A compromised phone in daily use, a harmful keyboard, a broken TEE, a weak master password against a stolen backup file** (platform.md §4). StrandHogg 2 on unpatched Android 7 to 10 and TapTrap before the December 2025 patch [V-P8].
- **A replacement that nobody notices** [A]. The banner about a new master password is shown inside the app, after an unlock. An owner who does not open the app during the 24 credited hours is not warned. After that, the person who asked holds a working master password and can export everything or make a backup. The owner's passwords as they were stay on the phone (F16b), but what was copied stays copied. Whether the app should also post an Android notification during the wait is a question for the user (18.16).
- **"Clear storage", uninstalling the app, a factory reset.** Anyone who holds the unlocked phone can do these in Android's settings. They delete everything the app has: the current vault and every vault that was put aside, the passwords from before a replacement among them. The app cannot prevent that. Only a backup outside the phone survives.
- **More than one vault on the phone** [A]. The PIN limit is per vault. A vault that was put aside while it had a working PIN keeps five tries of its own, and it can be reached from the lock screen ("Forgot master password?", F17). An owner who restored a backup over a vault and chose the same PIN again has given a thief ten guesses at that PIN, and the older vault holds mostly the same passwords. A vault put aside by a replacement has no PIN (F11 step 18). Closing the rest needs one counter for the whole phone, which was not designed (18.17).
- **Guesses that the owner's own right PIN hides** [A]. Up to two wrong PINs typed in the minute before the owner types the right one, on the same screen, are neither counted nor reported (6.4). The screen says "Wrong PIN. N tries left." at that moment.
- **A screen-off that arrives late, on Android 14 and newer.** If the screen goes off while the app is in the background, Android may deliver that event only when the app is brought back to the front. The app then locks a moment after it is shown, unless the lock delay had locked it already (10.2).
- **Backup and phone-clone tools made by manufacturers** [A]. If such a tool can put the app's private files back on the same phone, it can put back an older PIN counter while the key stays, and so give new guesses. Android's own backup cannot [J-S §1 claim 9]. The tools of Xiaomi and Samsung are checked before release (D6), and 18.18 says what is added if one of them fails.
- **Force.** Someone who makes the owner unlock sees what the owner sees.
- **A forgotten master password when no PIN or fingerprint works.** Nothing can recover it. The files are kept in case it comes back.

### 2.4 Why not the phone's own screen lock

The user confirmed the app's own PIN. The phone's screen lock is not offered as a way in because it would make the vault exactly as private as the phone: family members, colleagues and repair shops often know the phone's PIN, and those are the people an app lock is for. Wrong tries on the system's credential screen also count toward the phone's own lockout and any "wipe after N failures" policy [F §1.3]. On Android 7 to 10 it could only be built with time-bound keys that any recent phone unlock authorises [F §1.3], and OWASP limits device-credential fallback to low-risk features (MASWE-0021) [F §5]. Nothing in this design prevents adding it later as a fourth record.

---

## 3. Invariants

Each one is a test target (section 15).

- **I1. Lock code never writes vault data.** No PIN, fingerprint or master-password attempt, no limit, no Keystore error and no change to a shortcut writes, renames or deletes a vault generation, a backup or an export. The only flows that ask storage to write are setup, restore, "change master password", the end of a forgotten-password replacement, and the user's own choices in F16 and F17, and they go through the storage module's API.
- **I2. Device-bound state lives only in `noBackupFilesDir/lock/` and in the Keystore.** It never enters a vault generation, a backup, an export or a transfer.
- **I3. Every vault generation and every backup has exactly one master-password slot.** When a file gets a new slot (setup, restore, a master-password change, a replacement, a backup), that slot was opened from disk with a password the user typed, before the file was reported saved. Every other generation carries, byte for byte, the slot of the generation it was saved from.
- **I4. No PIN is checked without a charge that is durable and at least 1,000 ms old.** "Checked" means the Keystore HMAC is asked for.
- **I5. The counts go down only as stated.** `row` returns to 0 on a correct PIN or a correct master password. `total` goes down by what a right PIN on a lock screen forgives (6.3: that visit's wrong PINs of the last 60 seconds, at most two), and to 0 on a correct master password. A refund clears only the `charged` flag.
- **I6. A shortcut is removed only** on a conclusive signal of section 14, at a limit, at the user's request, when the vault key it wraps is no longer on disk, or when a finished replacement has put its vault aside (F11 step 18). Never because of a temporary error, a record that cannot be read, or a mismatch alone.
- **I7. The vault opens only through cryptography.** An authenticated unwrap of VK, then an authenticated open of a generation under that VK. No boolean result or callback unlocks anything. `onAuthenticationSucceeded` in particular proves nothing by itself [L-bio finding b].
- **I8. The wall clock never decides a security question.** Limits use no time. Waits, the forgiving window and the length of the "replaced" notice use `elapsedRealtime()`. The wall clock is used for display and the practice schedule only.
- **I9. Nothing reachable from the lock screen deletes anything.** The strongest thing it can do is put a vault aside, where it can be made current again: with its shortcuts or, after a replacement, with its master password.
- **I10. The master-password path depends on nothing device-bound.** It reads no lock record to decide whether to try, needs no write to succeed, and is never locked out.
- **I11. A result belongs to the visit that asked for it.** A PIN, fingerprint or master-password check that finishes after its visit has ended updates the counts and opens nothing (6.3, 6.8).

---

## 4. Key hierarchy and parameters

### 4.1 Picture

```
 master password ─NFC, the folds of 8.1, UTF-8─► Argon2id(64 MiB, t=3, p=4, salt_mp)      [app process, 0.3-1 s]
                                                      │ KEK_mp (32 B)
                                                      ▼
 ┌──────────── vault generation file (portable: the same container is used for backups) ────────────┐
 │ "PVAULT01" ‖ header_len ‖ header { vault_id, vk_id, generation, …, exactly one password slot      │
 │                                    = AES-256-GCM(KEK_mp, VK) }                                    │
 │ body = AES-256-GCM(VK, entries, AAD = magic ‖ header_len ‖ header bytes as stored)   [V-C3]       │
 └───────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                      ▲
                          VK: 32 random bytes; a new VK and vk_id with every master-password change
                ┌─────────────────────────────────────┴───────────────────────────────┐
  pin.rec  (noBackupFilesDir, this phone only)                 fingerprint.rec  (this phone only)
  wrap = AES-256-GCM(KEK_pin, VK, AAD_pin)   [app process]     wrap = AES-256-GCM under K_fp   [TEE]
  KEK_pin = HMAC-SHA256(K_pin, pin_msg)      [TEE]             K_fp: Keystore AES key, one Class 3
  K_pin: Keystore HMAC key, no user authentication,                  biometric per use, dies when
         never leaves the secure hardware                            fingerprints change
```

There is no key cycle: `K_pin` and `K_fp` never leave the Keystore, `KEK_mp` and `KEK_pin` are never stored, and VK never encrypts a key that wraps VK.

### 4.2 Algorithms and parameters

| Item | Value | Notes |
|---|---|---|
| VK | 32 bytes from `SecureRandom()`; `vk_id` is a random UUID made together with it | crypto.md §1. A new VK on every master-password change [V-C5, V-S5] |
| Master-password bytes | `UTF-8(fold(NFC(password)))`: NFC first, then the white-space and apostrophe folds (8.1) | [V-C9]; the apostrophe fold was added here [J-R D3-2], the white-space fold in architecture.md §4.6, and the order after the audit [A] |
| Master-password KDF | Argon2id v1.3 (`argon2_version` 19), m = 65,536 KiB, t = 3, p = 4, 16-byte random salt, 32-byte output. Exact constants are required when reading [V-C4] | Decided in crypto.md §1 |
| Password slot | AES-256-GCM(KEK_mp, 12-byte random nonce, VK), no AAD, 48 bytes. Exactly one per file [V-C4] | crypto.md §1 |
| PIN bytes | The six ASCII bytes `0x30`–`0x39`. The pad produces only these; hardware-keyboard and numpad digits are mapped to them; locale digits never occur | Fixed bytes, no locale effect |
| `K_pin` | AndroidKeyStore key generated with `KeyGenerator.getInstance(KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")` and `KeyGenParameterSpec.Builder("pv_pin_" + slot_id, PURPOSE_SIGN).setKeySize(256)`. No user authentication. API 35+: `.setUnlockedDeviceRequired(true)`. Never: StrongBox, `setMaxUsageCount`, a validity period | Every guess must pass through this phone's secure hardware (MASWE-0020 [F §5]). HMAC returns nothing before `finish()` [F §1.2]. A new key for every PIN that is set |
| `KEK_pin` | `Mac.getInstance("HmacSHA256")`, `init(K_pin)`, `doFinal(pin_msg)`: 32 bytes, used directly as an AES-256 key | HMAC under a random key is a PRF; nothing is gained by hashing it again |
| PIN wrap | AES-256-GCM in the app process (platform JCA, no provider name), `KEK_pin`, 12-byte nonce from `SecureRandom()`, plaintext VK, `AAD_pin`: 48 bytes. One wrap per `vk_id`, at most 2 (5.4) | A wrong PIN gives another key and the tag fails |
| `K_fp` | AndroidKeyStore AES: `KeyGenParameterSpec.Builder("pv_fp_" + slot_id, PURPOSE_ENCRYPT or PURPOSE_DECRYPT).setKeySize(256).setBlockModes(BLOCK_MODE_GCM).setEncryptionPaddings(ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)`. API 30+: `.setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`. API 24–29: nothing more; the default validity −1 means "authenticate for every use". Never StrongBox, never `setUnlockedDeviceRequired` | This exact specification ran on the API 24, 28 and 37 emulators [L-bio Experiment 2, L-old §3]; [V-C7, V-P10]. The lab found that unlocked-device-required adds a "Device locked" failure and buys nothing for a key that already needs a fresh fingerprint [L-bio case 7] |
| Fingerprint wrap | AES-256-GCM inside the TEE under `K_fp`. Encrypt with `cipher.init(ENCRYPT_MODE, key)` and **no IV** (a caller IV is refused [L-bio case 3]); store `cipher.iv` (12 bytes) and the 48-byte result. No AAD: this is the call sequence the labs ran. One wrap per `vk_id`, at most 2 (5.4) | [V-C7] |
| `pv_canary` | AndroidKeyStore HMAC-SHA256 key, 256-bit, `PURPOSE_SIGN`, no user authentication, no unlocked-device requirement, alias `pv_canary`. Never used on a secret and never deleted by the app | (a) Setup generates it first to learn whether this phone keeps keys in secure hardware (4.5). (b) It tells "one key is gone" from "the Keystore is not answering"; if it is ever missing itself, 14.2 makes it again [D3, A] |
| Pending master password | Argon2id with the constants above and its own 16-byte salt gives `KEK_pending`; AES-256-GCM(`KEK_pending`, 12-byte random nonce, VK, `AAD_pending`), 48 bytes, in `state.rec` | Exists only while a forgotten master password is being replaced (F11) |
| Random numbers | `SecureRandom()` with no arguments | `getInstanceStrong` does not exist on API 24 [L-old §7] |
| Base64 | `kotlin.io.encoding.Base64`, standard alphabet with padding | `java.util.Base64` does not exist on API 24–25 [V-C12, L-old §7] |
| Wiping | Our own `ByteArray`s are filled with zeros and references dropped. `destroy()` is never called [V-C10] | Best effort on a garbage-collected heap |

### 4.3 Byte-level definitions and test vectors

```
uuid16(u)   = the 16 bytes of UUID u, most significant byte first
pin_msg     = ASCII("pvlock-v1:pin-kek")      ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(slot_id) ‖ PIN        (56 bytes)
KEK_pin     = HMAC-SHA256(K_pin, pin_msg)                                                             (32 bytes)
AAD_pin     = ASCII("pvlock-v1:pin-wrap")     ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(vk_id) ‖ uuid16(slot_id)   (67 bytes)
AAD_pending = ASCII("pvlock-v1:pending-wrap") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(vk_id)                (55 bytes)
wrap        = AES-256-GCM-Seal(key, nonce, VK, AAD)  →  32 bytes of ciphertext ‖ 16-byte tag
```

`slot_id` is a random UUID made for every PIN that is set, so `KEK_pin` differs between PINs, slots and vaults even if a bug ever reused an alias. `vk_id` is in the AAD and not in `pin_msg`, so one HMAC opens whichever wrap matches the vault copy being opened.

**PIN vector** [X: Python `hmac` + `cryptography` 41.0.7 and JDK 25 JCA give the same bytes; appendix C].

```
vault_id 00000000-0000-4000-8000-000000000001     vk_id …0002     slot_id …0003
K_pin (test stand-in; the real key never leaves the Keystore)  42 × 32
VK       a0 a1 … bf          nonce 10 11 … 1b          PIN 582913
pin_msg  70766c6f636b2d76313a70696e2d6b656b00 00000000000040008000000000000001 00000000000040008000000000000003 353832393133
KEK_pin  344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74
AAD_pin  70766c6f636b2d76313a70696e2d7772617000 00000000000040008000000000000001 00000000000040008000000000000002 00000000000040008000000000000003
wrap     e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02
PIN 582914, or vk_id and slot_id swapped in the AAD  →  AEADBadTagException
```

**Master-password vectors** [X: Python `unicodedata` 15.0 and JDK 25 agree, also after the change of order in 8.1; Argon2id from Bouncy Castle 1.86, which also reproduces crypto.md's `853b272a…bab49e`].

```
"oʻgʻlim-Toʻra" typed with U+02BB, with U+2018, with U+0027, with U+2019/U+02BC/U+0060, or with U+00B4/U+FF07/U+2032
    → bytes 6f2767276c696d2d546f277261                                   (all five spellings)
    → Argon2id(salt 00 01 … 0f, 65536 KiB, t 3, p 4) = 35914871c9a019bf3fa9ae69825be6a300a86905bcf69ca6bb84de0a3064b26b
the same word typed with the Greek signs U+0374/U+1FEF/U+1FFD, which NFC turns into U+02B9/U+0060/U+00B4
    → the same bytes and the same key          (folding before NFC would give 6fcab967606c696d2d546fc2b47261) [A]
"Köz-Ўzbek" typed decomposed (o + U+0308, У + U+0306)
    → bytes 4bc3b67a2dd08e7a62656b                                       (same as the composed spelling)
    → Argon2id(same salt and constants) = 9f05463cb3a665fb4e22120cdad867630f76f12dc5ffdf4efaaa589f5baad5fa
```

### 4.4 What runs where

| Step | Where |
|---|---|
| Argon2id; every software AES-GCM seal and open; the counters and all decisions | The app process |
| `HMAC(K_pin, pin_msg)` | The TEE, on phones that pass 4.5 |
| AES-GCM with `K_fp`, and the check that a fresh Class 3 biometric authorised this very operation | The TEE, using the auth token from the biometric hardware |
| "Usable only while the phone is unlocked" for `K_pin` (API 35+) | keystore2, an OS service. It wipes the protecting key when the phone locks, unless a weak unlock method (Smart Lock, Class 2 face) is enabled [J-S §1 row 8] |
| Lockout after wrong fingers | The system's biometric stack. The app counts nothing there |

### 4.5 Only hardware-backed keys

Right after generating any device key (`pv_canary`, `K_pin`, `K_fp`), read its `KeyInfo` with `SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)`.

- API 31+: accept `getSecurityLevel()` equal to `SECURITY_LEVEL_TRUSTED_ENVIRONMENT`, `SECURITY_LEVEL_STRONGBOX` or `SECURITY_LEVEL_UNKNOWN_SECURE`.
- API 24–30: accept `isInsideSecureHardware() == true`.
- Otherwise delete the key. Neither PIN nor fingerprint is offered on this phone (F18). A software Keystore would let a copy of the phone's storage test PINs offline, which requirement 2 forbids.
- **Emulators report software keys** on API 24, 28 and 37 [L-bio case 7, L-old §3]. The `debug` and `staging` builds (architecture.md §7.2) therefore set `BuildConfig.ALLOW_SOFTWARE_KEYSTORE = true` and show a red line "TEST BUILD: PIN keys are not protected" on the lock screen. A check fails the build if the flag is true in a release build (R-2).

### 4.6 What one PIN guess costs

There are 873,663 allowed PINs (section 7).

| Situation | What limits guessing | Cost |
|---|---|---|
| **A1. A copy of everything the app stores**, without the secure hardware | A PIN cannot be tested at all: `KEK_pin` needs `K_pin`, and the records hold no verifier. The attacker must guess the master password | One Argon2id (64 MiB) per guess: about 56 per second on a 16-thread desktop CPU, about 1,700 per second on one RTX 4090 [F §3, V-C13]. The suggested six-word passphrase has 62 bits (ux.md §5.3) |
| **A2. Phone in hand, no root** | The counter in `pin.rec`, which no other app can read or roll back; it is in no backup or transfer that Android makes [F §7.4], and manufacturers' tools are checked in D6 | **5 guesses** at a vault's PIN, then its key is deleted. All of the 100 most popular 6-digit PINs and 99 % of the top 2,000 are refused at creation [X, section 7]. The counter is per vault: a vault that was put aside with a working PIN has five tries of its own (2.3) |
| **A3. Someone near the owner, over many days** | The second count | **10 counted guesses** between two master-password entries, and at most two uncounted ones for each hand-over they can stage (6.4) |
| **Phone rooted later** (outside the model) | The secure hardware: one HMAC per guess on this phone | About 30 to 90 per second [F §3], so every allowed PIN in about 2.7 to 8.1 hours. On API 35+ only while the phone is unlocked (4.4). Nothing after the limit has deleted the key |
| **TEE broken and files copied** (outside the model) | Nothing | Microseconds per PIN. The fingerprint wrap would give VK directly. Shortcuts are conveniences; the master password must be strong |

### 4.7 Constructions that were rejected

| Alternative | Why not |
|---|---|
| PIN protected by Argon2id alone (Bitwarden's envelope [F §4]) | A copy of the files falls to offline guessing: 10^6 PINs in about 5 hours on a desktop [F §0.4]. Breaks requirement 2 |
| PIN as a screen gate over a key stored without it (Proton Pass [F §4]) | No protection at rest |
| Argon2id in front of the HMAC [D3] | 50 to 120 ms and a memory-hungry step on the daily path, where the Bouncy Castle fallback has already crashed a UI thread [L-old §2]. It helps only after the TEE key is extracted, when the fingerprint wrap gives VK anyway [J-S 3-6, J-U] |
| A Keystore usage limit with key rotation [D3], or five single-use keys [D1] | Counts above 1 are enforced by OS software, not hardware [F §1.2]; no phone is known to declare hardware single-use keys [F §10]. The second budget made "tries left" false and let correct use wear the PIN out [J-R D3-1, D1-2; J-U] |
| StrongBox | 12 to 110 ms per operation, on 0.2 to 8.7 % of devices [F §1.5, §3]; no gain against A1 to A3 |
| A Keystore secret mixed into the master-password KDF | Any Keystore loss would destroy the vault. Breaks requirement 1 |
| Shortcut slots inside the vault header (crypto.md §1) | Useless on another phone, a vault rewrite on every Keystore event, and [V-P6] puts them in `noBackupFilesDir` |
| A device key between VK and the shortcuts, so that they survive a master-password change | A key cycle, to save one PIN entry and one touch on a rare event |
| AAD on the fingerprint wrap [D1, D3] | `updateAAD` on a Keystore cipher after authentication was never run in a lab. The record already says which `vk_id` a wrap holds |

---

## 5. Everything that is stored

### 5.1 In the vault file (portable)

The lock needs three fields in the authenticated header of every generation, and one rule. These are requests to the crypto and storage owners (16.3).

| Field | Rule |
|---|---|
| `vault_id` | UUID v4, lower case. Made when a vault is created on a phone: at first setup, when a backup is restored (a restored vault is a new vault on this phone), and when a forgotten master password is replaced (F11 makes a new vault and puts the old one aside). It never changes afterwards, not on a save and not on an ordinary master-password change |
| `vk_id` | UUID v4, made together with every new VK |
| `generation` | The increasing number that [V-C8] already requires |
| Slots | Exactly one, of type `password` [V-C4]. **No biometric slot in the header**; this changes crypto.md §1 |

The lock reads `vault_id` and `vk_id` of the newest readable generation *before* anything is typed. Both are checked against `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$` before use, because `vault_id` becomes a directory name. Until the body has been opened the header is not authenticated; a wrong value can only make the lock ask for the master password.

Nothing else about the lock is ever in a vault generation, a backup or an export.

### 5.2 The lock directory (this phone only)

```
<noBackupFilesDir>/lock/<vault_id>/pin.rec
                                   fingerprint.rec
                                   state.rec
```

- `noBackupFilesDir` is left out of cloud backup, device-to-device transfer and cross-platform transfer whatever the rules say [F §7.4, V-P6]. Today's manifest excludes every other directory too.
- **One directory per vault.** A vault that is put aside (F17) keeps its directory and its Keystore keys, so its PIN and fingerprint work again when it is made current. The slots of a vault that a finished replacement put aside are the one exception (F11 step 18). A directory is deleted only together with its vault, which needs that vault's master password.
- **Three records, so that one damaged file costs one way in** [J-R D2-5]. `pin.rec` is rewritten at every PIN entry; damage to it must not take the fingerprint with it.
- **The lock delay is not kept here.** It is an ordinary setting, stored with the theme and the language in the app's settings (`SharedPreferences` "pv_settings", written with `commit()`; architecture.md D-19). The lock only reads it, and a missing or unreadable value means "Immediately" (10.1). Nothing else of the lock is stored anywhere but in these three records and in the Keystore.

### 5.3 Record format

```
"PVLOCK01"   8 bytes   magic
len          u32, big-endian, 2 ≤ len ≤ 65,536
json         len bytes, UTF-8, one JSON object produced by our own serializer
sha256       32 bytes = SHA-256(magic ‖ len ‖ json)          nothing may follow
```

The hash detects damage. It is not a defence against someone who can write the app's private files; that needs root, which is outside the model.

Rules for every record: `"v"` must be 1; `"kind"` must match the file name; `"vault_id"` must equal the directory name; unknown keys, wrong types and wrong lengths are refused. Times are whole milliseconds since 1970 UTC (`java.time` does not exist on API 24 [L-old §7]). A record is under 2 KB.

**Reading gives one of five results.**

| Result | Meaning | `pin.rec` / `fingerprint.rec` | `state.rec` |
|---|---|---|---|
| OK | — | Used | Used |
| MISSING | The file does not exist (`ENOENT`) | That shortcut is not set up on this phone | Defaults: practice due now and still skippable; counters zero. A new record is written after the next successful unlock: `step` 0, and `last_ok` = now after a master-password unlock, or one day ago after a PIN or fingerprint unlock (so practice stays due, with its one day of grace) |
| DAMAGED | Magic, length, hash, JSON or a field rule is wrong | Not offered. The screen says that it stopped working on this phone, and after a master-password unlock the app offers to set it up again, which writes a new record (13.3, 13.4). Never repaired in place. A damaged `fingerprint.rec` is handled as F15a handles a fingerprint key that is gone, and a damaged `pin.rec` as F15b handles a PIN key that is gone: at that master-password unlock, whatever the answer to the offer, a record without a slot (`off = key_gone`) takes its place, so that it no longer blocks the key sweep (5.7). What the damaged record held cannot be read and goes with it (for `pin.rec`, its counts and the wrong tries nobody was told about); for a PIN, "Choose a new PIN" is then offered after every master-password unlock until one is set, as after F15b. A PIN or fingerprint unlock writes nothing; a write that fails leaves the record damaged, and the next master-password unlock tries again | As MISSING |
| NEWER | Framing and hash are right but `"v"` is above 1 | Not offered, with its own text (13.3). **Never rewritten, and it blocks the key sweep**: a newer app version owns it [D1] | Not used and never rewritten. Practice is not asked; the master-password wait runs from memory |
| UNREADABLE | Any other I/O error | Not offered this time. Not rewritten automatically: it may read fine next time [D2]. After a master-password unlock the app says "Part of the app's storage could not be read. Make a backup now." and offers "Set up again", which writes a new record | As NEWER |

Every result other than OK ends at a screen where the master password works (I10).

### 5.4 `pin.rec`

```json
{
  "v": 1, "kind": "pin", "vault_id": "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70",
  "slot": {
    "id": "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4",
    "created": 1759752000000,
    "wraps": [ { "vk_id": "3a1d…", "nonce": "<base64, 12 bytes>", "ct": "<base64, 48 bytes>" } ]
  },
  "guard": { "charged": false, "row": 0, "total": 0 },
  "off": null,
  "unseen": { "count": 0, "last_at": null, "interrupted": false }
}
```

| Field | Rule |
|---|---|
| `slot` | `null` when no PIN is set. The Keystore alias is always `"pv_pin_" + slot.id`; it is not stored, so it cannot disagree |
| `slot.wraps` | 1 or 2 entries with different `vk_id`s; nonce exactly 12 bytes; `ct` exactly 48 bytes. Which ones: the rule below |
| `guard.charged` | True while a try has been paid for and not yet resolved (6.3) |
| `guard.row` | 0 to 5: wrong PINs since the last right PIN or master password |
| `guard.total` | 0 to 10: wrong PINs not forgiven since the last master password |
| `off` | `null`, or `{"reason": "limit" \| "key_gone" \| "bad_wrap" \| "master_changed" \| "replaced", "at": <ms>}`. Only for the one-time explanation. If `slot` is not null, `off` is null |
| `unseen` | Wrong PINs nobody has been told about yet: how many, when the last one was, and whether one of them was an interrupted entry. A right PIN takes off the ones it forgives (6.3); the notice takes off the number it showed (13.4) |

**Which wraps a slot holds.** One rule for both shortcut records [A]:

- A slot holds the wrap for the `vk_id` of the vault's newest generation and at most one more. That second one is the wrap for the key before it, while storage still keeps a generation under that key; or, during a master-password change, the wrap for the key that is about to be written.
- A master-password change first drops every wrap except the one for the vault's current key, and then adds the new one (F8 step 5 for the PIN, step 7 for the fingerprint). So a third wrap never exists, however often a change is repeated or fails.
- At every successful unlock, by PIN, fingerprint or master password, both records drop the wraps whose `vk_id` no kept generation of the vault carries. Storage reports that set; files in quarantine and files put aside do not count. This is what removes the wrap of a change that never reached the disk. The write is best effort and is repeated at the next unlock.
- A slot left without any wrap is removed (`off = master_changed`). A record with more than two wraps is DAMAGED.

### 5.5 `fingerprint.rec`

```json
{
  "v": 1, "kind": "fingerprint", "vault_id": "0f6b2f0e-…",
  "slot": {
    "id": "c2a4…", "created": 1759752300000,
    "wraps": [ { "vk_id": "3a1d…", "nonce": "<base64, 12 bytes: cipher.iv>", "ct": "<base64, 48 bytes>" } ]
  },
  "off": null
}
```

Same rules as `pin.rec`, the rule for wraps included. The alias is `"pv_fp_" + slot.id`. `off.reason` is one of `key_gone`, `bad_wrap`, `turned_off`, `master_changed`, `replaced`.

### 5.6 `state.rec`

```json
{
  "v": 1, "kind": "state", "vault_id": "0f6b2f0e-…",
  "practice":   { "last_ok": 1759752000000, "step": 0, "snoozed_until": null },
  "mp_guard":   { "failures": 0, "last_fail_elapsed_ms": null, "unseen": 0 },
  "mp_change":  null,
  "pending_mp": null,
  "replaced":   null,
  "max_generation": { "n": 57, "at": 1759752000000 }
}
```

| Field | Rule |
|---|---|
| `practice` | `last_ok`: wall-clock time of the last correct master password. `step` 0 to 3 selects the interval (8.3). `snoozed_until`: set by "Not now" |
| `mp_guard` | `failures`: wrong master passwords in a row. `last_fail_elapsed_ms`: `elapsedRealtime()` at the last one. `unseen`: those not yet shown to the owner (6.8, 13.4) |
| `mp_change` | `null`, or `{"to_vk_id": "…", "at": <ms>}`: a master-password change (F8) has started and its new copy is not proven yet. Set before the vault is written; cleared when the change is proven or undone, whenever a typed master password opens the vault, and at any unlock that finds no kept generation with `to_vk_id` (the change never reached the disk). Only while it is set can the old master password reach "Open my passwords from before the change" (F16b) |
| `pending_mp` | `null`, or `{"requested": <ms>, "by": "pin" \| "fingerprint", "credited_ms": 0, "seen_elapsed_ms": <ms>, "vk_id": "…", "salt": "<base64, 16 bytes>", "nonce": "<base64, 12 bytes>", "ct": "<base64, 48 bytes>"}` (F11) |
| `replaced` | `null`, or `{"new_vault_id": "…", "at": <ms>, "by": "pin" \| "fingerprint", "credited_ms": 0, "seen_elapsed_ms": <ms>}`: this vault's master password was forgotten, and vault `new_vault_id` was made from it (F11 step 15). While it is set and the vault is put aside, the vault offers no PIN, no fingerprint and no pending password. The credit only times the notice of 13.4. The old passwords do not depend on this record: they are safe because they are a vault of their own. If the record is lost, what is lost is the label, the notice and, until start-up has removed the slots (5.11), the block on the old vault's shortcuts, which open nothing that the person who knew them could not open before |
| `max_generation` | The highest generation this phone has opened, and when. If a later open gives a lower number, the rollback notice of ux.md §5.9 is shown [V-C8]. It lives here because it must not travel or roll back with the vault file |

### 5.7 Keystore entries

| Alias | Made | Deleted |
|---|---|---|
| `pv_canary` | Before the first device key of an installation | Never by the app |
| `pv_pin_<slot.id>` | When a PIN is set or changed | **At once** when its slot is *removed* (a limit, section 14 says it is gone, or start-up removes the slots of a replaced vault, 5.11). When its slot is *replaced* by a new one: only by the sweep |
| `pv_fp_<slot.id>` | When fingerprint unlock is turned on | **At once** when its slot is *removed* (the user turns fingerprint off, section 14 says it is gone, or 5.11). When it is *replaced*: only by the sweep |
| `androidxBiometric` | By the fingerprint library, on Android 10 only, each time it is asked whether fingerprint can be used (13.1) | Never by the app. It protects nothing of ours, and the sweep cannot touch it: it deletes only names that start with `pv_pin_` or `pv_fp_` |

**The sweep.** At process start (step 4 of 5.11), under the lock mutex, before any flow can create a key:
1. Read every record in every `lock/*/` directory.
2. If any of them is DAMAGED, NEWER or UNREADABLE, stop.
3. List the Keystore aliases. Delete each alias that starts with `pv_pin_` or `pv_fp_` and is named by no record.

Why this shape:
- A key that a record write has *replaced* is deleted only here, at a later start, after the replacing record has been read back from disk. If it were deleted at once and a power cut then lost the record write, the surviving record would point at a deleted key [J-R D1-5, D2-8, D3-3].
- `aliases()` returns an empty list when the Keystore is failing, so a failing Keystore deletes nothing [J-R claim 7]. Errors from `deleteEntry` are ignored; the next start tries again.
- A damaged `pin.rec` or `fingerprint.rec` does not stop the sweep for good: it goes at the next master-password unlock (5.3), and the key it named, which nobody can know, is then one that no record names.
- A key whose slot is *removed* is deleted at once. That is safe in the other direction. At a limit, if the record write is lost, the surviving record still holds the charge and the next start turns the PIN off again (6.5). A key that section 14 calls gone is dead already.

This design holds two live keys per vault directory, the canary, and for a short time keys that wait for the sweep. The limit on API 37 is 50,000 [F §1.1].

### 5.8 In memory only

- VK, the decrypted entries, every KEK, the six PIN digits (a `ByteArray(6)` in the lock controller), the typed master password.
- The time of the charge; the number of the current visit or prompt; the times of this visit's wrong PINs, for forgiving (6.3); the wrong guesses already checked (6.6); the number of the fingerprint launch that is outstanding (9.2).
- The session clock values of section 10.
- A `MasterProof` object: made by a correct master-password check and handed to the next step of the same flow (8.2). It is dropped when that flow ends, when the app stops being visible, and on lock. A check whose caller has gone while it ran (its screen closed) has ended its flow: the proof it made for that caller is dropped as soon as the check returns, not at the next lock. It is not a time window.

The master-password field is a `TextFieldState` held by a ViewModel, never `rememberTextFieldState()`, which writes the text and its undo history into saved state [V-C11, L-win Experiment 5]. After reading it: `clearText()`, then `undoState.clearHistory()`, or drop the state object.

### 5.9 Writing a record

```kotlin
// Caller holds the lock mutex. I/O runs on Dispatchers.IO. Any exception means: NOT written.
fun replace(dir: File, name: String, bytes: ByteArray) {
    val tmp = File(dir, "$name.tmp")
    FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
    Os.rename(tmp.path, File(dir, name).path)          // replaces the old record atomically
    val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
```

- This is the routine that ran without error on API 24, 28 and 37 [L-old §4, L-sto]. Replacing in place is right here: old and new are both valid states.
- A failed directory `fsync` also counts as not written [V-S2].
- Never `AtomicFile`, DataStore or `Files.move` [V-S7].
- Stale `*.tmp` files are deleted at process start.
- In `:core` this is an interface (`RecordFiles`); the `Os.*` implementation lives in `:app`, and a JVM fake models "handed over" and "durable" separately for the tests.

### 5.10 What happens to each piece of state

| Event | Vault file | `pin.rec` and `fingerprint.rec` | `state.rec` | Keystore |
|---|---|---|---|---|
| Master password changed (F8) | New generation, new VK and `vk_id`, new password slot, proven from disk | Every wrap except the current key's is dropped and a wrap for the new `vk_id` is added: the PIN's before the vault is written, the fingerprint's after. The wrap for the old `vk_id` goes once no generation with it is left (5.4) | `mp_change` set before the write and cleared after it. Practice step 0; counters 0 | Same keys |
| Forgotten master password replaced (F11) | A **new vault** with a new `vault_id`, VK and `vk_id`. The old vault is put aside, whole, and is never retired | The new vault gets a new directory and new slots (F1 steps 5 to 7). The old vault's slots are ignored at once and removed at a later start (5.11) | Old vault: `replaced` set before the swap; `pending_mp` cleared at a later start. New vault: a new record | New keys for the new vault. The old vault's keys are deleted at a later start |
| The owner opens the passwords from before a replacement (F16b) | The two vaults change places. Nothing is deleted | Old vault: both slots removed if they are still there; a new PIN is chosen. The newer vault's records are not touched | Old vault: `replaced` and `pending_mp` cleared | The old vault's former keys deleted; the newer vault's kept |
| An unfinished master-password change is given up (F16b) | Storage puts the newer generations aside, never deleting them | Not touched; the wraps for the abandoned key go by 5.4 | `mp_change` cleared; `max_generation` set to the copy chosen | — |
| An older generation is opened because the newest is damaged | Read only | The same shortcut works if it has a wrap for that `vk_id`; otherwise the master password of that time. Nothing is removed for a mismatch | — | — |
| Encrypted backup; plain export (F12) | A new file outside the app; the vault is not written | Untouched | Practice advanced | — |
| Restore on a new phone (F13) | A new vault with a new `vault_id`, VK and `vk_id` | A new directory; PIN set after the master-password unlock | New; practice step 3 | New keys |
| Vault present, no lock directory (F14) | Untouched | Created at PIN setup | Created after the unlock | New keys |
| Vault put aside, or made current again (F17) | Moved by storage, never deleted | Stay where they are, under their `vault_id` | Stays | Stay |
| PIN limit (F5) | Untouched | `pin.rec`: `slot` null, `off` = limit. `fingerprint.rec` untouched | Untouched | `K_pin` deleted |
| A finger is added | Untouched | `fingerprint.rec`: `slot` null, `off` = key_gone, at the next visit (F15a). `pin.rec` untouched | — | `K_fp` invalid. `K_pin` and the canary are bound to no fingerprint and survive [J-R claim 3] |
| All fingers are deleted, or the screen lock is removed | Untouched | Nothing yet: while the phone says that fingerprint cannot be used, the key is not probed and the slot is left alone; the pad says why (13.1, 13.3). The slot is removed on the first visit after fingerprint can be used again. `pin.rec` untouched | — | `K_fp` invalid or deleted by the system. `K_pin` and the canary survive; with the unlocked-device requirement on API 35+ they survive too since Android 15 [J-R claim 4]. Check E7 |
| App update | Untouched | Read by the version rules of 5.3 | Same | Kept |
| Android update | Untouched | Untouched | — | Expected to survive; if not, section 14 applies |
| The app is reinstalled with its data kept (`hasFragileUserData`, architecture.md D-21) | Untouched | Kept | Kept | Not known whether Android keeps the keys [not run]. If they are gone: F15b for each shortcut, with the canary made again (14.2) |
| "Clear storage", uninstall, factory reset | Deleted by Android | Deleted | Deleted | Deleted. Only backups outside the phone remain. Help says never to use "Clear storage" to fix a lock problem |

### 5.11 What process start does, in order

Under the lock mutex, before any lock screen is drawn and before any flow can make a key:

1. Delete stale `*.tmp` files in the lock directories (5.9).
2. Read every record of every `lock/*/` directory. Ask storage which vaults are on the phone: the current one and those that were put aside, by `vault_id`.
3. **Replaced vaults** (F11, F16b). For every `state.rec` that reads OK and has `replaced` set; N is its `new_vault_id`. If storage could not list the vaults, this step is skipped.

   | This vault is | Vault N is | What happened | The lock |
   |---|---|---|---|
   | put aside | on the phone | The replacement was carried out | removes this vault's two slots (`off = replaced`), sets `pending_mp = null`, and deletes the two aliases. `replaced` stays |
   | current | on the phone, put aside | The owner went back (F16b), and step 5 there did not finish | does the same, and sets `replaced = null` once neither slot can come back: each record was written without its slot, or reads MISSING or DAMAGED. A record that this start cannot read (UNREADABLE) may hold its slot when it reads again, and nothing would remove that slot once the mark was gone, so the mark stays and the next start tries again, as F16b step 5 keeps it |
   | either | not on the phone | The replacement did not take place: the process died after F11 step 15 or inside storage's last step | sets `replaced = null`. Slots, keys and `pending_mp` are kept, so the owner gets in as before |

   A write that fails here is tried again at the next start. Until then the app behaves as if it had succeeded, because the lock controller works from the records it means to have on disk (6.3). The third row cannot be reached by deleting vault N after a replacement that did take place: "Delete" settles the mark first (F17 step 5).
4. **The key sweep** (5.7).
5. **The counter's start-up step** (6.3 S) for the current vault. A vault that was put aside gets it when its own lock screen is opened (F17 step 4).
6. Observe the credit (6.9) of every `pending_mp` and every `replaced`.

A lock directory whose vault is no longer on the phone is left alone.

---

## 6. The attempt counter

### 6.1 Where it lives

`guard` in `pin.rec`, in the same record as the PIN slot, so that "one more failure" and "the slot is gone" are a single atomic write. No other app can read or change the file without root. Android's own backup and transfer never include `noBackupFilesDir`, so no restore made by Android can bring an older count back [F §7.4; J-S §1 claim 9, confirmed for the AOSP framework only]. Backup and phone-clone tools made by phone manufacturers have not been checked; check D6 does that before release, and 2.3 says what follows if one of them fails it. Clearing the cache does not touch `noBackupFilesDir` [F §7.4].

### 6.2 Constants

| Name | Value | Why |
|---|---|---|
| `ROW_LIMIT` | 5 wrong PINs in a row | EU bank rule: at most 5 [F §4]; NIST: at most 10, then another authenticator [F §6.3] |
| `TOTAL_LIMIT` | 10 wrong PINs that were not forgiven, between two master-password entries | Bounds attacker A3 [J-S §2.3]. Counted so that an honest owner does not trip it (6.4) |
| `CHARGE_AGE` | 1,000 ms | The security judge's value. Six digits take a practised user longer than a second (4-digit PINs: 0.92 s at the 25th percentile [J-U §2]), so it is normally invisible |
| `FORGIVE_WINDOW` | 60 s, by `elapsedRealtime()`, in memory | A slip is put right within seconds. A wrong PIN that has stood for a minute is no longer the slip of the person now typing [A] |
| `FORGIVE_MAX` | 2 | Two slips in a row happen; more looks like guessing. It also halves what a staged hand-over can hide (6.4) [I] |
| Same-PIN memory | Every wrong PIN checked since the last right PIN or master password, in memory | 6.6 |
| Waiting times between PIN tries | None | The totals are fixed. A wait would only add clock state that requirement 3 would then have to defend [D1, D2, D3] |

### 6.3 The protocol

All steps run under the one lock mutex. The lock controller keeps the record it means to have on disk in memory, and every write stores that whole record, so a write that failed is made good by the next one that succeeds. The write of a new PIN or fingerprint slot is the one case in which the lock first looks at what is on disk: when that write reports a failure, the record is read back, and the controller goes on with it (F6 step 8, F9 step 9).

**Where a PIN can be typed.**
- **On a lock screen, during a visit.** A visit starts when a lock screen becomes visible in a locked app. It ends when the app is unlocked, when the app stops being visible (the process-level `ON_STOP` of section 10), or at a screen-off lock (10.2), whichever comes first. `ON_STOP` alone would be too late for a screen that is switched off: it comes about 700 ms after the screen-off, and a check that finished in between would open the vault with the screen off. After a screen-off lock the next visit of the same lock screen starts at once, because the screen can be on again before the app was ever stopped. Rotating the phone or changing the language does not end a visit (section 11). Every visit has a number, kept in memory. When a visit starts and when it ends, the digits and the list of this visit's wrong PINs are cleared.
- **In a prompt inside the open app** (F6 step 2, F8 step 3, and F11 step 3 when it starts from the open app). The steps below are the same, with three differences. Nothing is forgiven there: a wrong PIN typed in a prompt never goes on the list that a right PIN forgives, and a right PIN in a prompt forgives nothing. Every wrong PIN typed there is reported at the next unlock (13.4). And the result goes to the flow that asked, which says what happens next (F6, F8, F11). A prompt has a number of its own; if the app locks or stops while it is open, the entry is given up (A).

**S. Process start, before any lock screen is drawn** (step 5 of 5.11).
1. Read `pin.rec`. Any result but OK: no PIN in this process (5.3).
2. If `guard.charged` is true, a try was paid for and never resolved. Write `charged = false`, `row + 1`, `total + 1`, `unseen.count + 1`, `unseen.interrupted = true`. If that reaches a limit, apply 6.5 in the same write.
3. **If this write fails, the PIN is not offered in this process** [J-S 2-9]. The charge stays on disk and is counted at a later start. The reason shown is "storage is full" (13.3).

**C. First digit of an entry.**
1. Allowed only when the PIN is usable (13.1), no limit is reached and no charge is held.
2. Write `pin.rec` with `charged = true`, with the full routine of 5.9. Digits typed meanwhile are buffered and shown as dots.
3. If the write fails: clear the dots and show the "storage is full" state (13.3). Nothing is counted and nothing is checked.
4. When the write has returned, remember `chargedAt = SystemClock.elapsedRealtime()` in memory.

**A. The entry is given up before it is checked.** This happens when the digits are deleted back to none, "Forgot PIN? Use master password" is tapped, the fingerprint key is tapped or a fingerprint succeeds, a prompt is closed, or the visit ends before the sixth digit.
1. Write `charged = false`. This is a **refund**: it clears the flag and never touches a count [J-S 3-7].
2. Forget `chargedAt`; zero the digits.
3. If the write fails or the process dies first, the try stays counted. That is the safe direction.

**E. Sixth digit.** The pad ignores input from here until the result is shown. The check remembers the number of the visit or prompt it belongs to.
1. If the six digits are a wrong guess that was already checked (6.6): refund as in A and show "You already tried that PIN. It was not counted again."
2. Wait until `elapsedRealtime() ≥ chargedAt + 1,000 ms`, showing "Opening…". Only then:
3. `getKey("pv_pin_" + slot.id)`, `Mac.init(key)`, `KEK_pin = Mac.doFinal(pin_msg)`. If the Keystore does not give a result, classify by section 14:
   - **GONE:** refund, remove the slot (`off = key_gone`), go to F15b.
   - **TEMPORARY:** refund, do not offer the PIN again on this visit, go to F15c.

   Nothing about the PIN was learned in either case, so the refund is safe.
4. Try to open every wrap with `KEK_pin` and its `AAD_pin`. Zero `KEK_pin`.
5. **No wrap opens: wrong PIN.**
   - New guard: `charged = false`, `row + 1`, `total + 1`; `unseen.count + 1`, `unseen.last_at = now`.
   - If `row ≥ 5` or `total ≥ 10`: 6.5.
   - Otherwise write it. If the write fails, the charge is still on disk and the next start counts it: the same count either way.
   - In memory: on a lock screen, add the time of this wrong PIN to the visit's list; remember the guess (6.6); forget `chargedAt`; zero the digits.
   - Show "Wrong PIN. N tries left." with N = min(5 − `row`, 10 − `total`). The line stays until the visit ends (13.3).
6. **A wrap opens: right PIN.**
   - `forgiven`: on a lock screen, the number of wrong PINs on the visit's list that are at most 60 s old, but not more than 2. In a prompt: 0.
   - New record: `charged = false`, `row = 0`, `total = max(0, total − forgiven)`, `unseen.count = max(0, unseen.count − forgiven)`. The wraps are trimmed by the rule of 5.4: one whose `vk_id` no kept generation carries is dropped. So is a wrap whose tag failed under the right PIN: it can never open.
   - In memory: clear the visit's list and the remembered guesses.
   - **If the visit or prompt of this check has ended meanwhile** (the screen was switched off during the wait, the app was left): write the record, zero VK, and stay locked. The counts are as for any right PIN; nothing opens (I11).
   - Otherwise storage opens the vault with the VK of the matching `vk_id` (I7), and **the list is shown as soon as the vault is open**. The record is written in the background, as the next job under the mutex, so that no other step can overtake it. The owner does not wait for a directory `fsync` that nobody has timed on a phone's file system [L-sto, V-S8]. If that write fails or the process dies first, the charge on disk is counted once at the next start, which is rare and on the safe side (6.10).
   - If the body does not open although the PIN was right, the file is damaged. That is storage's flow (ux.md §5.9); the PIN still counts as right.

```kotlin
suspend fun onSixthDigit(pin: ByteArray, at: Place): PinOutcome = lockMutex.withLock {   // Place: a visit or a prompt, with its number
    val c = charge ?: return@withLock PinOutcome.NotCharged              // a UI bug; never checks
    val tag = sha256(processNonce + pin)                                 // 6.6: the PIN itself is not kept
    if (tag in checkedWrong) { refundLocked(); return@withLock PinOutcome.SameAsBefore }
    clock.delayUntilElapsed(c.chargedAt + CHARGE_AGE_MS)
    val kek = when (val r = pinKey.hmac(rec.slot!!.alias, pinMsg(rec.vaultId, rec.slot.id, pin))) {
        is Ks.Ok -> r.value
        is Ks.Gone -> { refundLocked(); removeSlotLocked("key_gone"); return@withLock PinOutcome.KeyGone }
        is Ks.Temporary -> { refundLocked(); pinHiddenThisVisit = true; return@withLock PinOutcome.CannotCheck }
    }
    val opened = rec.slot.wraps.mapNotNull { w -> gcmOpenOrNull(kek, w.nonce, w.ct, aadPin(rec.vaultId, w.vkId, rec.slot.id))?.let { w.vkId to it } }
    kek.fill(0); charge = null
    val now = clock.elapsed()
    if (opened.isEmpty()) {
        val g = rec.guard.copy(charged = false, row = rec.guard.row + 1, total = rec.guard.total + 1)
        checkedWrong.add(tag); if (at.isVisit) visitWrong.add(now)
        if (g.row >= ROW_LIMIT || g.total >= TOTAL_LIMIT) { turnOffLocked(g, "limit"); PinOutcome.Limit }
        else { rec = rec.copy(guard = g).countUnseen(clock.wall()); writeQuietly(rec); PinOutcome.Wrong(min(ROW_LIMIT - g.row, TOTAL_LIMIT - g.total)) }
    } else {
        val forgiven = if (at.isVisit) min(FORGIVE_MAX, visitWrong.count { now - it <= FORGIVE_WINDOW_MS }) else 0
        rec = rec.copy(guard = Guard(charged = false, row = 0, total = max(0, rec.guard.total - forgiven)),
                       unseen = rec.unseen.minus(forgiven)).keepWrapsFor(vault.keptVkIds())
        visitWrong.clear(); checkedWrong.clear()
        if (at.hasEnded()) { writeQuietly(rec); opened.forEach { it.second.fill(0) }; PinOutcome.TooLate }      // stay locked
        else { writeSoon(); PinOutcome.Right(opened.toMap()) }           // writeSoon(): the next job under lockMutex writes `rec`
    }
}
```

### 6.4 What changes the counts

| Event | `charged` | `row` | `total` |
|---|---|---|---|
| First digit | true | — | — |
| Entry given up; Keystore gave no result; same PIN as before | false | — | — |
| Wrong PIN, on a lock screen or in a prompt | false | +1 | +1 |
| A charge found at process start | false | +1 | +1 |
| Right PIN on a lock screen | false | 0 | minus the forgiven ones: this visit's wrong PINs of the last 60 s, at most 2 |
| Right PIN in a prompt inside the open app | false | 0 | — |
| Fingerprint unlock | false | — | — |
| Correct master password, anywhere in the app | false | 0 | 0 |
| A new PIN is set (F6) | false | 0 | 0 if the master password was typed, otherwise unchanged |
| A new vault is made (F1, F11 step 17, F13) | a new record: false | 0 | 0 |
| Reboot, kill, clock change, cache cleared | — | — | — |

- **A fingerprint unlock resets nothing.** Otherwise someone near the owner would get fresh tries after each of the owner's fingerprint unlocks [J-S §2.3, D1, D3]. It hides nothing either: the notice reports every wrong PIN that is still counted (13.4).
- **Why `total` forgives slips.** Design 1 counted every wrong PIN until the next master password. Simulated with its own rules, an owner who unlocks 20 times a day with 3 % typos lost the PIN before the next master-password entry in 37 % of 14-day periods [J-R appendix A; J-U]. With the rule here the same owner loses it in under 0.1 % of periods; even with 5 % typos, a fifth of them never put right, and 21 days between master-password entries, it is 4 % [X, appendix D]. Someone who does not know the PIN cannot put a wrong one right, so the bound of 10 holds for them.
- **Why only a minute, and only two** [A]. This rule first forgave every wrong PIN of the visit and left them out of the notice too. Someone near the owner could then type wrong PINs, hand the phone over ("can you open your passwords?") and have the owner's right PIN wipe them: guesses that were never counted and never reported, on any day they liked. With the window and the cap, the same trick hides at most two guesses, only when the owner types the PIN within a minute, and only while the line "Wrong PIN. N tries left." stands on the screen in the owner's hand (13.3). Every other wrong PIN is counted and reported. The remainder is stated in 2.3.
- **A user who never types the master password** [A]. Only a master password returns `total` to zero. An owner who has forgotten it, and passes the practice screen with "I can't remember it" (F10), keeps every slip that was not put right at once. Simulated at 8 unlocks a day and 3.1 % typos, with no master-password entry at all: if one slip in five is left standing, the PIN turns off within 180 days in 59 % of runs and within a year in over 99 %; if one in ten, in 6 % and 64 % [X, appendix D]. That user is told in time. The notice at `total` ≥ 6 offers a new master password (13.4 item 4), and the standing banner of 13.4 item 6 says on every visit that the master password has not been typed. A new master password (F11) starts the count again.
- **What the screen shows.** "N tries left" with N = min(5 − `row`, 10 − `total`): after every wrong PIN, and on a fresh pad whenever N is 2 or less, so that the PIN never turns off without a warning [F §4, PSD2 Art. 4(4)].

### 6.5 At a limit

1. One write of `pin.rec`: `slot = null`, `off = {"reason": "limit", "at": now}`, the guard as counted.
2. `deleteEntry("pv_pin_" + slot.id)` at once. Errors are ignored; the sweep retries.
3. On a lock screen: the fingerprint-only screen if fingerprint is usable, otherwise the master-password screen (13.1), with the text of 13.3. In a prompt inside the open app: the prompt closes with its message, and the app stays open (F6, F8, F11).
4. Nothing else changes. The fingerprint slot, the vault, backups and the master password are untouched (I1, I6).
5. After the next unlock: the notice (13.4), then "Choose a new PIN" (F6).

If the write in step 1 fails, the PIN is off in memory for this process and the charge is still on disk; the next start counts it and reaches this step again. If the process dies between steps 1 and 2, the sweep deletes the key.

### 6.6 The same wrong PIN twice

The app remembers every wrong PIN it has checked since the last right PIN or master password, in memory only, as `SHA-256(nonce16 ‖ PIN)` with a nonce made at process start. At most 4 can exist, because the fifth wrong PIN in a row turns the PIN off. A matching entry is refused before the Keystore is asked and is refunded. Nothing is learned from it, because it was already checked. Android 17's own lock screen does the same [F §2.2]. The list is cleared by a right PIN, a correct master password, a PIN change and the end of the process. There is no timer: an earlier draft forgot a guess after 5 minutes, but checking a known wrong PIN again can never tell anyone anything, so the expiry only added a clock to test [A]. The list protects the owner who retypes a PIN thinking they slipped, and a child pressing one key.

### 6.7 Why this order

**Model check** [X, appendix B; the protocol part repeats Design 2's check, which the security judge reproduced; run again for this revision with the forgiving rule of 6.3]. Every interleaving of the protocol steps with process kills, power cuts, restarts and waiting was explored. A kill keeps every write that was handed over. A power cut keeps only writes handed over at least one "settle" period earlier, which models a drive cache that `fsync` does not flush [V-S8]. Anyone may type wrong PINs or the right PIN at any moment. The right PIN's own write may be lost like any other, since the list is shown before it.

| Protocol | Most wrong PINs in a row (limit 2) | Most wrong PINs that nothing forgave (limit 3) |
|---|---|---|
| Check, then count (Bitwarden, Proton Pass [F §4]), 22 steps deep | 9, and more with every further step | 9 |
| Charge at the first digit, check at once, 22 steps deep | 6, and more with every further step | 6 |
| **Charge at the first digit, check when the charge has aged (this design), 34 steps deep** | **2** | **3** |

The last row also holds with limits 3 and 5 (34 steps), and with limits 3 and 4 and a settle period of 2 (30 steps) [X]. Two deliberately broken variants of the forgiving rule are found broken by the same check: forgiving wrong PINs older than the window, and keeping the visit's list after the visit has ended [X].

**Requirement 3, trick by trick.**

| Trick | Why it gives nothing |
|---|---|
| Kill or force-stop the app during an entry | The charge is on disk. The next start counts it (S.2) |
| Cut power or pull the battery | The check runs only when the charge is at least 1 s old. What remains: a drive that holds a write for more than a second after `fsync` returns (2.3) |
| Reboot | As a kill. Counts are on disk |
| Change the clock | No limit uses the date. `CHARGE_AGE` and the forgiving window use `elapsedRealtime()` |
| Clear the cache | Nothing of the lock is in `cacheDir` |
| "Clear storage", reinstall | Deletes the keys, the records and the vault together: no guess, no data |
| Restore an older record | Not possible with Android's own backup and transfer: the directory is in neither. Manufacturers' tools: check D6 |
| Double tap, two windows | One activity (`singleTask`), one mutex, the pad disabled from the sixth digit |
| `adb shell input` or an OTG keyboard | Still 5 |
| Switch the screen off while a PIN is being checked | The try is counted like any other, and nothing opens (E.6). The screen-off lock ends the visit at once; it does not wait for `ON_STOP` (6.3) |
| Type wrong PINs into "Change PIN" inside an open app | Counted, never forgiven, reported at the next unlock |
| Type wrong PINs, then hand the phone to the owner | At most two are forgiven, and only when the owner's right PIN follows within 60 seconds on the same visit, with "Wrong PIN. N tries left." on the screen all the while. The rest are counted and reported (6.4) |
| Go on to the PIN of a vault that was put aside | That vault has five tries of its own: the limit is per vault (2.3, F17 step 7) |

### 6.8 Wrong master passwords

There is **never a lockout** (requirement 1, I10).

1. **Wait, if any.** If `mp_guard.failures ≥ 5`, compute `since = if (now ≥ last) now − last else now` from `elapsedRealtime()`. While `since < 30 s` the field is disabled and counts down. After a reboot only the time since boot counts, so a reboot cannot skip the wait, and no stored clock value can be forged [J-S §2.1].
2. **Charge.** Write `failures + 1`, `last_fail_elapsed_ms = now`, `unseen + 1`. This is best effort: if the write fails the attempt still runs, and the values are kept in memory for this process.
3. **Derive.** One Argon2id at a time in the whole process. If the native library is missing and the Java fallback would not fit in the free heap, or memory runs out, show "not enough memory" (13.3): that is not a wrong password and is not counted [V-C2, L-old §2].
4. **Try** the password slot of the newest readable generation, then every other distinct password slot among the generations storage still keeps [V-S3]. In practice that is one slot, two for a while after a change. If none opens and a pending password is ready, try the pending wrap (F11 step 13).
5. **Right** (a password slot opened and the vault is open): `failures = 0`; PIN guard `row = 0`, `total = 0`, `charged = false`; practice updated (8.3); a pending replacement cancelled (F11 step 9); `mp_change = null`. Each write is best effort. `mp_guard.unseen` loses only this password's own slips and what the notice has shown (13.4). If it was the pending wrap that opened, the flow is F11 step 14 and not this step.
6. **An older master password** (the password opens only the slot of an older copy, while a newer copy under another key is intact): F16b. Nothing is opened, and the try stays counted.
7. **Wrong:** the message of 13.3. The password is called wrong only when no slot opened [V-S3].
8. **A result that comes too late.** Argon2id takes 0.3 to 1 s, and longer on the Java fallback. If the visit that asked has ended by then (the screen was switched off, the app was left), steps 5 to 7 update the records as usual, the key is dropped, and the app stays locked (I11).

| Wrong master passwords in a row | Wait before the next try |
|---|---|
| 1 to 5 | none (each try already costs one Argon2id, 0.3 to 1 s) |
| 6 and more | 30 s each |

- This guard covers every check against a vault on this phone. For the current vault: the lock screen and the prompts for backup, export, change and settings. For a vault that was put aside: its own lock screen and "Old passwords" in Settings, with that vault's own `state.rec` (F17). Guesses at a vault that was put aside cost what guesses at the current one cost.
- **It never applies to a file from outside**: a backup being restored or imported. The attacker has that file anyway, and an owner on a new phone must not be slowed down [J-R D3-2].
- **Effect.** Scripted guessing through the screen drops to at most about 2,880 tries a day (86,400 s ÷ 30 s; a little fewer, because every try also costs one Argon2id). A master password that passes the creation rules (ux.md §5.3: zxcvbn score 4, at least 10^10 guesses) would last thousands of years at that rate [I].

### 6.9 Time that a reboot cannot shorten

Two things wait for real time: the 24 hours before a new master password starts working, and the 7 days for which the "replaced" notice is shown (F11). Both use this helper and nothing else:

```kotlin
data class Credit(val creditedMs: Long, val seenElapsedMs: Long)

fun Credit.observe(nowElapsedMs: Long): Credit {
    val gained = if (nowElapsedMs >= seenElapsedMs) nowElapsedMs - seenElapsedMs else nowElapsedMs
    return Credit(creditedMs + gained, nowElapsedMs)
}
```

- It is called at process start (5.11), at every process-level `ON_START` and `ON_STOP`, at every unlock and whenever the banner is drawn, for every `pending_mp` and `replaced` in any lock directory; the result is written to that `state.rec` (best effort).
- On one boot `elapsedRealtime()` is monotonic and includes sleep [F §7.3], so the credit is exact, whether the app was running in between or not. The credit is never more than the time that really passed.
- `BOOT_COUNT` is not consulted: it can be rewritten through `adb` without a reboot [J-S §1 claim 10]. Changing the date does nothing.
- **What is lost** [A]. The helper sees the clock only when the app is looked at, and it cannot ask anyone whether the phone was restarted, because every such answer can be forged. So after a restart it credits the new uptime only if that is *less* than the last value it saw; otherwise it credits just the difference. And the time between the last look and a shut-down is never seen. For a phone that stays on, none of this matters: 24 hours are 24 hours. For a phone that is switched off every night (07:00 to 23:00, times varying by 10 minutes) the 24 hours take [X, appendix F]:

  | The app is opened | 24 hours are credited after about |
  |---|---|
  | In the morning and in the evening | 1.5 days |
  | Once a day, in the evening | 3 days (up to 8) |
  | Once a day, two hours after switching on | 3 weeks |

  That is why the request screen and the banner tell the user to open the app in the morning and again in the evening (F11 step 6, 13.4). Whether 24 credited hours is still the right number for such a phone is a question for the user (18.7).

### 6.10 Crash points of a PIN entry

| The process dies | On disk | Next start |
|---|---|---|
| After the charge, before the check | `charged = true` | Counted as one wrong try, with the notice "interrupted" (13.4). Nothing was learned |
| After a wrong result, before its write | `charged = true` | The same count as if the write had happened |
| After a right result, before its write | `charged = true` | The app was open this time. One try is counted next time, with the same notice. The write runs just after the list is shown, so this needs a kill within a few milliseconds of an unlock |
| At a limit, before the write | `charged = true`, `row` 4 or `total` 9 | S.2 counts it and turns the PIN off |
| At a limit, after the write, before the key is deleted | Slot gone, key still there | The sweep deletes the key |

---

## 7. PIN rules and the blocklist

### 7.1 Rules

- **Exactly 6 digits.** NIST SP 800-63B-4 says at least 4 and preferably 6; Android's CDD and Mandiant's advice to Bitwarden say 6 [F §6.3]. With 5 guesses, 4 and 6 digits are about equally guessable; 6 digits matter when a limit is bypassed, where they mean 100 times the work [F §0.6].
- **Submitted by the sixth digit**, with no OK key. Every PIN has the same length, so nothing is revealed (CDD 17 C-SR-7 is about variable lengths [F §6.3]).
- **Digits only, on the app's own pad.** The pad is never shuffled (ux.md §5.9). There is no text field, so no keyboard app and no autofill service ever sees the PIN [F §8]. A hardware keyboard's digit and numpad keys enter digits, Backspace deletes one, a long press on ⌫ clears all, Enter does nothing.
- **Never stored.** Not in a record, not under the vault key, not in saved state.

### 7.2 The blocklist

It is applied **only when a PIN is chosen**, never at unlock, so changing the rules later cannot affect an existing PIN. It is a pure function of the six digits (reference code: appendix A). The rules are tried in this order, and the first match picks the message. There is no "use it anyway".

| # | Rule | Refuses | Examples | PINs (first match) [X] |
|---|---|---|---|---|
| 1 | Repeat | A digit that occurs 4 or more times | 111111, 000001, 121111 | 12,700 |
| 2 | Pattern | ababab; abcabc; aabbcc; aaabbb; a PIN that reads the same backwards | 121212, 123123, 112233, 111222, 123321 | 2,340 |
| 3 | Sequence | All six digits step by the same amount, counting 9 → 0 as a step; **or** both halves are runs of three stepping +1 or −1 (also across 9 → 0); **or** one of 246810, 108642, 135790, 097531 | 123456, 654321, 890123, 135791, 789456 | 424 |
| 4 | Keypad lines | Both halves are three keys in a straight line on the pad: 123, 456, 789, 147, 258, 369, 580, 159, 357, each in either direction | 147258, 159753, 852456 | 260 |
| 5 | Steps | Digits 1, 3, 5 step by the same amount **and** digits 2, 4, 6 step by the same amount (no wrap) | 102030, 142536, 124578, 172839 | 2,066 |
| 6 | Run of four | Four digits in a row, anywhere, stepping +1 or −1 (no wrap) | 123457, 001234 | 3,904 |
| 7 | Keypad path | Six different keys, each one next to the key before it (beside, above, below or diagonal) | 147896, 741236 | 3,698 |
| 8 | Date | DDMMYY, MMDDYY or YYMMDD with a day 01–31 and a month 01–12 | 250890, 082590, 900825, 071990 | 83,696 |
| 9 | Year | The first four or the last four digits are a year from 1940 to 2039 | 199012, 451987, 131990 | 17,249 |
| | **Refused in total** | | | **126,337 of 1,000,000 (12.63 %)** |
| | **Allowed** | | 582913, 905127, 368014 | **873,663** |

**Measured against the published popularity list** (DD-6-digit, ordered by popularity [F §6.2]; used only to measure, never shipped, because it carries no licence) [X; J-S §2.6 reports the same figures]:

| Slice of the list | Refused |
|---|---|
| The 100 most popular PINs | 100 % |
| The 1,000 most popular | 98.2 % |
| The 2,000 most popular (the size researchers recommend blocking [F §6.1]) | 99.0 % |
| Apple's own 6-digit list (2,910 PINs) | 78.6 % |

The most popular PIN that is still allowed is number 163 on that list. An attacker who knows these rules starts there.

**Why dates and years in every order.** The person most likely to try this app's PIN is someone near the owner, and that person knows the family's birthdays [I]. Dates are among the most common choices [F §6.1]. A date refused in one order would simply be typed in another, so all three written orders are refused.

**The judges disagree here.** The security judge recommends exactly this list. The usability judge calls its size a minor defect: more first choices are refused, and choosing takes longer (25 s against 8 s in a study with a list covering 27 % of 4-digit PINs [F §6.1]). This document keeps the list, because five guesses are the whole defence against the people most likely to try, and a PIN is chosen once per phone. The screen says what is refused *before* the user types, which removes most refusals. If testing with the user shows this is too strict, drop rule 9 first: 109,088 PINs refused (10.91 %), with 92.9 % of the top 1,000 still covered [X].

### 7.3 What the user is told

On "Choose a 6-digit PIN" (ux.md §5.6), before typing:

> "Use this PIN to open the app on this phone, like your bank app. This is not your phone's PIN.
> Don't use a date or a year, or a pattern like 123456 or 112233.
> Don't use your phone's unlock PIN or your bank card PIN. Other people may know them."

Footer: "After 5 wrong tries in a row the PIN turns off. Then you open the app with your fingerprint or master password and choose a new PIN."

After the sixth digit, when a rule matches, the dots clear and the pad stays:

| Rules | Message |
|---|---|
| 8, 9 | "This PIN looks like a date or a year. Choose another." |
| 1, 2, 5 | "This PIN repeats digits or follows a pattern. Choose another." |
| 3, 4, 6, 7 | "This PIN is a sequence or a shape on the keypad. Choose another." |
| Change PIN, new equals current | "Choose a PIN different from your current one." |
| "Enter the PIN again" does not match | "The PINs don't match. Try again." After 3 mismatches, back to the first entry |

Choosing a PIN is not an unlock, so nothing is counted.

---

## 8. Master-password rules

### 8.1 From typed characters to bytes

One function, `MasterPasswordBytes.of(chars)`, is used wherever a master password is turned into a key: the vault, every backup, the pending password of F11. It is frozen with the test vectors of 4.3 and of architecture.md §4.13 **before the first release**; a later change would lock users out [crypto.md §6].

1. **NFC** (`java.text.Normalizer`, `Form.NFC`).
2. **White space,** as architecture.md §4.6 decided it (decision D-6b): each of its 25 white-space code points becomes U+0020, runs of U+0020 become one, and those at the start and the end are removed.
3. **Fold apostrophe-like characters to U+0027.** These 17 code points become `'`: U+0027, U+0060, U+00B4, U+2018, U+2019, U+201B, U+2032, U+2035, U+02B9, U+02BB, U+02BC, U+02BD, U+02BE, U+02BF, U+A78B, U+A78C, U+FF07.
4. **UTF-8.** Case is kept. An unpaired surrogate, which is not a Unicode character, is written as the UTF-8 of U+FFFD (EF BF BD).

- **Why NFC comes first** [A]. NFC turns three characters into members of the apostrophe list: U+0374 into U+02B9, U+1FEF into U+0060, U+1FFD into U+00B4. With the folds first, as this document and architecture.md had it, those three were left unfolded, and two spellings that Unicode treats as the same text gave different keys. With NFC first, every canonically equivalent spelling gives the same bytes; the result is itself NFC, because U+0020 and U+0027 take part in no composition; and applying the function twice changes nothing [X: every code point, and 200,000 random strings over both lists and a set of combining marks, in Python; the vectors again in JDK 25]. The three characters are Greek and no Uzbek, Russian or English keyboard types them, and no existing vector changes. But this function can never be changed after release, so its order is settled now.
- **Why step 3.** Uzbek oʻ and gʻ are typed with different characters on different keyboards, and Uzbek text in use mixes at least U+02BB, U+2018 and U+0027 [J-R claim 11]. NFC does not unify them [F §9]. Without folding, a password such as "Toshkentdagi oʻgʻlim" typed on a new phone gives another key, and the backup does not open, exactly when it is needed [J-R D3-2]. The list is the one search.md §1 uses today; for the master password it never changes, even if search later does.
- **At creation only:** refuse a password that contains a code point whose `Character.getType` is `UNASSIGNED`, or an unpaired surrogate [V-C9]. The refusal of apostrophes in ux.md §5.3 is no longer needed.
- This changes crypto.md §1 ("Password bytes") and the order of the steps in architecture.md §4.6; both are asked of the crypto owner in 16.3.

### 8.2 When it is required, and what each action needs

| Situation | Master password? | Reason |
|---|---|---|
| First setup | Created, written down, typed again | The root of recovery [V-C5] |
| First unlock on this phone (after a restore, or a vault that arrived without its lock directory) | Yes | Nothing device-bound travels |
| No shortcut is usable (PIN off and no fingerprint, keys gone, record damaged, no secure hardware) | Yes | — |
| A shortcut cannot be checked right now | This time only; nothing is changed | — |
| Practice is due (8.3) | Asked; "Not now" for a few days; then required. "I can't remember it" is always there | Requirement 7 |
| After a reboot | **No** | The phone demands its own lock after a reboot before any app can run, the keys survive reboots, and weekly reboots would mean weekly master passwords for nothing [D1, D2, D3] |
| After an app or Android update | No | — |
| Encrypted backup | Typed now, or the entry made a moment ago in the same flow (F12) | Proves the backup opens with a password the user knows today (I3); it is also practice |
| Plain export (CSV, JSON, Excel) | Typed now, always | Everything leaves the phone readable. A seen PIN or a sleeping owner's finger must not be enough [D1, J-S §2.4] |
| Change the master password | The current one, typed now | — |
| **Set a PIN when there is none; turn fingerprint on** | Typed in the same flow | This adds a way in. With less, someone holding the open app could add their own [J-S 3-4] |
| **Change the PIN** | The current PIN typed now (a counted check), or the master password | It adds no way in: whoever can do it already holds that one. It must be easy right after "someone tried your PIN" (decision 10) |
| Turn fingerprint off; "Lock now"; lock delay; clipboard delay | Nothing | Making the lock stricter is free. All four lock delays lie inside limits that cannot be switched off (10.1) |
| Replace a forgotten master password | Not available by definition. The PIN typed now; a fingerprint only when no PIN exists; then 24 credited hours (F11 step 3) | A finger alone must not start a replacement while a PIN exists [J-S 3-2] |
| Restore or import a backup | That file's own master password, without waits | The file is in the user's hands anyway |
| Open a vault that was put aside, or add its accounts (F17) | That vault's own master password, under that vault's own guard (6.8) | Guesses at it must cost what guesses at the current vault cost |
| Make a vault that was put aside current again (F17) | That vault's own PIN, fingerprint or master password. A vault from before a replacement: its master password only (F16b) | It is opened first, and only then swapped in |
| Delete a vault that was put aside | That vault's own master password, never the current vault's | Otherwise anyone could start over, or replace the master password, and then delete the owner's passwords |

- **A fingerprint opens the app and approves nothing.** Not "Change PIN", not "Set a PIN", not an export, not a backup. Only the two secrets the user can type do. The single exception is asking for a new master password when the vault has no PIN (F11 step 3): there the fingerprint is the only thing the owner has left to show.
- **"In the same flow"** means the `MasterProof` object of 5.8: the screen that asked for the master password hands it straight to the next step. Setup (master password just created), "Forgot PIN?" (master password just unlocked the app, then "Choose a new PIN") and "Change master password" all pass it on, so nobody types the master password twice in a row. There is no time window in which settings are open.

### 8.3 Practice

- **Schedule.** `due = practice.last_ok + interval[step]`, with `interval = [1 day, 2 days, 4 days, 14 days]`. Answered on time, the app asks on days 1, 3 and 7 after the master password was set, then every 14 days.
- **What counts.** Every correct master password: at unlock, on the practice screen, for a backup, an export, a change or a setting. It sets `last_ok = now` and clears `snoozed_until`. If at least 20 hours have passed since the previous `last_ok`, `step` goes up by one, to at most 3.
- **Reset.** A new master password (setup, change, replacement) sets `step = 0`. A restore on a new phone sets `step = 3`: the user has just proved they know it.
- **Grace.** `grace = min(interval[step], 7 days)`: 1, 2, 4 and then 7 days. While `now < due + grace`, the practice screen offers "Not now: use PIN" (or "Not now: use fingerprint"). Tapping it sets `snoozed_until = now + 20 hours`; until then the lock screen opens on the pad as usual. So the app asks at most once a day [J-U 4.2-3].
- **After the grace period** the master password is required: no "Not now". "I can't remember it" stays and leads to F11, which opens the app with the PIN or fingerprint. Nobody is locked out of their passwords because the paper is at home. Leaving F11 at that point without choosing a new master password does not end the matter: the standing banner of 13.4 item 6 stays on the list until a master password has been typed [A].
- **Not asked** while a replacement is pending (F11), and on a phone where no shortcut exists (every unlock is the master password already).
- **Fingerprint on a practice screen.** The prompt does not open by itself. After "Not now" the pad appears and the prompt opens as on any other visit.
- **Clock.** The schedule uses the wall clock, which is fine for a memory aid (I8). If the clock is more than an hour *before* `last_ok`, practice is due at once. A missing or damaged `state.rec` means due now and still skippable.
- **Why this grace.** ux.md recommends about 7 days; the usability judge 3 days, asked once a day; Design 1 allowed a single skip. Seven days in the steady rhythm lets a user who is away from the paper for a week carry on. In the first week the grace is as short as the interval, so that the three early repetitions, which are what fix a new password in memory, are not all skipped [I]. The longest gap between two entries is then 21 days.
- This rule replaces "the password … is demanded every 14 days" in crypto.md §1 and step 6 of its biometric-unlock flow.

---

## 9. Fingerprint

### 9.1 Library: `androidx.biometric:biometric:1.4.0-alpha07`

| | 1.1.0 (the last stable release, 2021) | 1.4.0-alpha07 (2026-04-22) |
|---|---|---|
| Android 7.0 (API 24), with the `androidx.core` 1.19.1 the app must use | `canAuthenticate` returns 12 "no hardware" on a phone with an enrolled finger; `authenticate` fails at once with error 12 [L-old §3] | `canAuthenticate` returns 0; the library's own dialog appears; encrypt and decrypt through the `CryptoObject` work [L-old §3] |
| Android 9 (API 28) | `canAuthenticate` returns −1 "unknown"; the prompt works on the emulator. On Samsung phones the library sends `CryptoObject` prompts to its own dialog, which then hits the same dead code: error 12 (read in the source, not run) [J-U §2] | Returns 0; system prompt; works [L-old §3]. The Samsung path uses the library's own fingerprint code (not run on a Samsung) |
| Android 10 (API 29) | Returns −1 on almost every phone [V-P1] | Returns 0 when a fingerprint is enrolled (read today in `BiometricManager.canAuthenticateCompat`; not run) [X] |
| Android 17 (API 37) | Works [L-bio] | Works, with the same callbacks, cancel codes, rotation and Home behaviour. One difference: with "fingerprint for apps" switched off it reports code 1 where 1.1.0 reports 21 and 8 [L-bio] |
| Can the old-Android failure be repaired? | No. 1.1.0 calls `androidx.core`'s `FingerprintManagerCompat`, which is an empty stub from core 1.18 on. `androidx.activity` 1.13 needs core 1.18 or newer, so an older core does not compile [L-old §3] | Not needed: it carries its own `FingerprintManagerCompat` |

**Decision: 1.4.0-alpha07, pinned to that exact version.** Requirement 4 asks for a fingerprint prompt and requirement 5 for Android 7 to 17; 1.1.0 gives no fingerprint on Android 7 and 8 and none on Samsung phones with Android 9, which are common older phones [I]. The alpha also gives one availability rule for every Android version (9.4) instead of special cases for API 28 and 29. All three designs and the usability judge chose 1.1.0, but the designs were written before the lab reports, and their reason was that the alpha's new API can open a separate activity outside our task [V-P1]. That reason is met by these conditions:

1. **Only the classic API:** `BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)`, `BiometricPrompt.PromptInfo.Builder`, `BiometricPrompt.CryptoObject`, and `BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)`. In the 1.4.0-alpha07 sources this constructor still uses `BiometricFragment` and, below API 28, the `FingerprintDialogFragment` inside our own activity; only the new context-based entry points start `FingerprintDialogActivity` with `FLAG_ACTIVITY_NEW_TASK` [X: `BiometricPrompt.java`, `AuthenticationHandlerFingerprintManager.kt:99-100`]. The lab saw the in-activity dialog on API 24 [L-old §3].
2. **A build check** fails if the app's sources mention `AuthenticationRequest`, `registerForAuthenticationResult` or `androidx.biometric.compose` (R-4).
3. **A merged-manifest check**, next to the existing "no INTERNET" check: if `androidx.biometric.internal.ui.FingerprintDialogActivity` is present it must have `android:exported="false"`, as it does in the AAR [X] (R-5).
4. **The host activity keeps a `Theme.AppCompat` theme.** With a platform theme the app crashed on API 24 the moment the dialog opened [L-old §3]. The project's `Theme.PasswordVault` already has the right parent.
5. **The version changes only together with a re-run of checks E6 to E9.** When a stable 1.4.0 exists, move to it after the same checks.
6. **Way back, if the alpha ever has to go:** 1.1.0, no fingerprint below API 28 or on Samsung with API 28 (the PIN covers those phones), and "−1" treated as "offer it" on API 28 and 29.
7. **R8 keeps the library's own fingerprint callbacks.** The callback that the library hands to `FingerprintManager` on Android 6 to 8.1, and on the Samsung phones with Android 9 that it routes there, is an anonymous class (`androidx.biometric.internal.FingerprintManagerCompat$Api23Impl$1` in 1.4.0-alpha07). The `android.jar` of compileSdk 37 has no `android.hardware.fingerprint`, so R8 cannot see that its four methods override the platform's, and the library's own rules only `-dontwarn` the class: R8 removed all four from the `staging` and `release` builds, and turning fingerprint on never finished on Android 7 (E6 with the `staging` build on API 24: the system logged the touch as authenticated, and no result reached the app). A `-keepclassmembers` rule in `src/main/keepRules/rules.keep` keeps them, and build check R-16 (architecture.md §8.5) fails the build of `staging` and `release` when one of them is not in R8's mapping under its own name. The class name is the pinned version's: item 5's re-run includes R-16 [the merge of round R3, build-plan.md §12 item 12].

What the library brings in: the permissions `USE_BIOMETRIC` and `USE_FINGERPRINT` (the app declares none itself) [L-bio case 8]; `fragment` 1.6.2 in place of 1.5.4 as the only other resolved change [L-old §3]. Its built-in texts exist in Uzbek Latin and Russian but not in Uzbek Cyrillic, so the library's own dialog on Android 7 and 8 shows English hints in that language (i18n.md §1.4.6).

### 9.2 Rules for the prompt

1. **Exactly one `BiometricPrompt` per activity, constructed in `onCreate`, every time** (also after a rotation or a language change). Only the last prompt constructed on an activity receives callbacks, and a prompt built lazily delivered its result to a destroyed activity after rotation [L-bio finding a, case 10].
2. **What the prompt is for** (unlock, turn on, keep after a master-password change) is held by the process-wide lock controller, not by the activity.
3. **Launch only when the activity is RESUMED.** After `onSaveInstanceState` the library drops `authenticate()` silently and no callback ever arrives [L-bio case 11]. The launcher then returns false, and the controller tries again at the next resume.
4. **Call `authenticate()` once.** A second call while a prompt is open is ignored [L-bio finding e].
5. **`PromptInfo`:** `setAllowedAuthenticators(BIOMETRIC_STRONG)`; a title and a negative button from the table below; `setConfirmationRequired` is not called, so its default `true` stays. That gives a Class 3 face unlock an explicit tap (OWASP MASTG-TEST-0329 [F §5]) and changes nothing for a fingerprint, which is a touch already. The labs ran with `false`; check E6 runs the default, because that is what the app ships.

   | Use | Title | Negative button |
   |---|---|---|
   | Unlock | "Open PasswordVault" | "Use PIN", or "Use master password" when no PIN is usable |
   | Turn on (F9) | "Turn on fingerprint unlock" | "Cancel" |
   | After a master-password change (F8) | "Keep fingerprint unlock" | "Not now" |
   | Asking for a new master password when no PIN exists (F11 step 3) | "Confirm with your fingerprint" | "Cancel" |
6. **Success is not an unlock.** Take `result.cryptoObject?.cipher` (never a cipher reference kept by the app). If it is null: TEMPORARY. Call `doFinal` on it. Only 32 bytes that then open the vault count (I7). After the phone's own PIN was typed on the prompt's lockout screen, `onAuthenticationSucceeded` arrived and `doFinal` failed, four times out of four [L-bio finding b].
7. **Opening by itself.** Once per visit, when the lock screen shows the pad (or the fingerprint-only screen), fingerprint is usable (13.1), the activity is resumed and has window focus, and no dialog is open. The launch is posted to the main thread and runs one turn after the resume, so that a callback left over from an earlier prompt is delivered first (rule 8). Not again on the same visit after the user closed it or an error came; the fingerprint key on the pad opens it again. Not on a practice screen (8.3).
8. **Every launch has a number.** The lock controller counts its launches and knows which one is outstanding. A callback is accepted only while a launch is outstanding, and it ends that launch; a lock or the end of a visit ends it too. A callback that arrives when none is outstanding is dropped. The reason is a lab result: with 1.4.0-alpha07, the code-10 error of a prompt that the system closed (five wrong fingers lock the phone) reached the app only when the activity came back to the front [L-bio case 4]. That is the moment at which the next visit wants to open its own prompt. Without this rule the old error would be taken for the new prompt's, or the new `authenticate()` would be ignored as a second call (rule 4) [A].

### 9.3 What the labs saw, and what follows

| Seen on the emulator [L-bio] | What the app does |
|---|---|
| A finger that is not enrolled: `onAuthenticationFailed`, the prompt stays open | Nothing. The system counts |
| After several wrong fingers the system prompt turns itself into "Enter your PIN" for the **phone's** PIN, with no callback | The pad is titled "Enter your **app** PIN" so the two are told apart (13.3) |
| The phone's PIN typed there, then a finger: success callback, but `doFinal` fails with "Key user not authenticated" | TEMPORARY. Message: "Fingerprint did not open the app this time. Enter your app PIN." A new prompt works |
| Five wrong fingers in a row lock the whole phone; the app gets code 10, with 1.4.0-alpha07 only when the activity is back in front | The visit ends when the app stops. Nothing is reopened, and the late callback is dropped (9.2 rule 8) |
| A prompt started during a lockout fails at once with code 7; `canAuthenticate` still says 0 | "Fingerprint is locked for now. Use your PIN." Availability cannot be checked beforehand |
| Back or the X: code 10. Negative button: code 13. `cancelAuthentication()`: code 5. A tap outside: nothing | Codes 10, 13 and 5 show the pad |
| Home while the prompt is open: code 10, and no prompt on return | The visit ended; the next visit starts fresh |
| A new finger enrolled: `KeyPermanentlyInvalidatedException` at `Cipher.init`, before any prompt | GONE (F15a) |
| All fingers deleted: the same exception in the lab, and `canAuthenticate` then says 11 | The key is not probed while the phone says that fingerprint cannot be used (13.1). The pad says that no fingerprint is saved; the slot is removed (F15a) on the first visit after a finger is saved again |
| One of two fingers deleted | Nothing happens; the key still works |
| The screen lock removed: the system deletes the fingerprints and the key; `getKey` returns null | The same: a status line while `canAuthenticate` says 11, then GONE through the rule of 14.2 |
| "Use fingerprint to verify it's you in apps" switched off: `canAuthenticate` 1, prompt error 1; the key is not invalidated | No probe and no prompt while `canAuthenticate` says 1; its own status line (13.3). The slot stays, and fingerprint works with the same key when the switch is on again |
| No fingerprint enrolled when the key is made: `InvalidAlgorithmParameterException` | "Add a fingerprint in your phone's settings first." |

Not seen on a real phone: lockout lengths (the emulator's were 1.5 to 8.5 s; real sensors should lock for 30 s or more), code 9, TEE-backed keys, and whether the lockout-screen failure also happens on real devices.

### 9.4 When fingerprint is offered

`BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)`, the same rule on every Android version. It is asked off the main thread, once when the screen opens (13.1):

| Result | First-run offer (ux.md §5.7) | Settings › App lock › Fingerprint unlock |
|---|---|---|
| 0 `BIOMETRIC_SUCCESS` | Offered | Switch enabled |
| −1 `BIOMETRIC_STATUS_UNKNOWN` (possible on API 28 and 29 only: a screen lock, but no enrolled fingerprint the library can see) | Not offered | Switch enabled; turning it on decides [V-P1] |
| 11 none enrolled | Not offered | "Set a screen lock and add a fingerprint in your phone's settings first" |
| 12 no hardware, −2 unsupported | Not offered | "This phone has no fingerprint sensor that apps can use" |
| 1 hardware unavailable | Not offered | "Fingerprint can't be used in apps right now. In your phone's settings, check that fingerprint may be used to verify it's you in apps." |
| 15 security update required | Not offered | "Your phone needs a security update before fingerprint can be used" |

- Only Class 3 ("strong") sensors, because only they can authorise a Keystore key through a `CryptoObject` [crypto.md §7].
- The key must also pass the hardware check of 4.5; otherwise: "This phone can't keep a fingerprint key in protected hardware."
- "Needs Android 9 or newer" (ux.md §5.7, §5.18) is no longer a reason.
- The first-run screen and Help say: "Anyone whose fingerprint is saved in this phone's settings can open the app." [D1, J-S §7.3]

---

## 10. Session, timeouts and the grace window

### 10.1 Rules and defaults

| Rule | Value | Can the user change it? |
|---|---|---|
| Locked at every process start | Always: keys exist only in memory | No |
| Lock after leaving the app | **30 seconds**. Choices: Immediately · 30 seconds · 1 minute · 5 minutes (ux.md §5.18) | Yes, without proof |
| Lock when the screen turns off | At once | No |
| Lock after no touch or key press while the app is in front | 5 minutes. The timer stands still while the app is not visible and while a system screen that the app opened itself is in front (10.2, 10.3) | No |
| "Lock now" | At once | — |
| A system screen the app opened itself (file picker, share sheet) | Does not lock for up to **10 minutes**; screen-off still locks (10.3) | No |
| The "write it down" and "type it again" screens of a master-password change or replacement (F8, F11) | The screen is kept on (`FLAG_KEEP_SCREEN_ON`). ux.md §5.4 does not keep it on, and a screen that times out while six words are being copied to paper would lock at once and throw the flow away [A]. The 5 minutes without a touch still apply; a lock discards the flow, and nothing was changed | No |
| The setting is missing or unreadable | Treated as "Immediately" [V-P9]. Setup writes the default explicitly, on a phone that has no settings yet (a new install, or after "Clear storage"; a restore on a new phone, F13, too). A phone that has them keeps them through a restore, from Welcome or from the lock screen (F17 step 3), and through a new setup: the lock delay, the clipboard delay, the theme, the colours and the language stay as they are (the owner's decision 13 of 2026-10-08). The delay is kept in the app's settings, not in a lock record (5.2) | — |

Why 30 seconds and not "Immediately": copying a password means leaving for the browser and often coming back for the user name. A bank app is not used that way. Screen-off, the strongest sign that the phone was put down, locks at once in every setting.

### 10.2 How it works

- **The unlocked session** (VK, the decrypted entries) lives in one process-wide object created by the `Application`, not in the activity and not in saved state.
- **Leaving and returning** are taken from `ProcessLifecycleOwner`. It reports `ON_STOP` 700 ms after the last activity paused, and nothing at all when an activity is only recreated [platform.md §3.3, V-P3].
- **At `ON_STOP`:** remember `stoppedAt = elapsedRealtime()`. Lock at once if `PowerManager.isInteractive()` is false (the screen is off), or if the setting is "Immediately" and no external screen is pending. A lock-screen visit ends here (6.3).
- **At `ON_START`, lock if either of these is true:**
  1. `elapsedRealtime() − stoppedAt` is at least the lock delay, and no grace applies (10.3);
  2. a screen-off was seen since `stoppedAt`.
- **Screen off.** A receiver for `ACTION_SCREEN_OFF`, registered in code while the process lives, locks at once. The lock is told also while the app is locked already: if a lock screen is showing, this ends its visit (6.3), so that a PIN, fingerprint or master-password check that is still running opens nothing (I11). From Android 14 the system holds this broadcast back while the app is cached, so it can arrive after the user has returned; the app locks then, late [platform.md §3.3, J-S 1-9]. The lock delay applies in every case, and 2.3 states what is left. This is what the security judge asked for. An earlier draft went further and, on API 35+, also locked when the platform's `BiometricManager.getLastAuthenticationTime` was later than `stoppedAt`. That rule was dropped [A]: the value is the time of the last successful strong authentication of any kind [X: android-37.0 `BiometricManager.java:1023-1059`], so a fingerprint prompt in another app moves it. Copying a password, signing in to a bank app with a finger and coming back would have locked the vault, on the newest phones only, which is the round trip the 30-second delay exists for.
- **No touch.** A 5-minute timer in the process, restarted by every touch and key event the activity dispatches. It is stopped at `ON_STOP` and while an external screen is pending (10.3), and started afresh at `ON_START`. While the app is not in front, the lock delay and the 10-minute cap decide, not this timer; otherwise a file picker that is open for six minutes would lock the app in spite of 10.3 [A].
- **Clocks.** Only `elapsedRealtime()`. Nothing is written to disk, so there is nothing to tamper with, and a new process always starts locked.
- "Immediately" therefore means 0.7 s after leaving, and at once when the screen turns off.

### 10.3 Screens the app opens itself

The system file picker and the share sheet stop the app exactly as leaving does [V-P3]. Without a rule, "Immediately" or 30 seconds would lock in the middle of a backup, an export or an import.

1. Just before the app starts one of these screens it calls `session.beginExternal()`, which remembers `externalSince = elapsedRealtime()`. Only three launches do this: `CreateDocument` (backup, export), `OpenDocument` (restore, import) and the share sheet for an encrypted backup.
2. While an external screen is pending, neither the lock delay nor the no-touch timer runs. At `ON_START` the app locks only if more than **10 minutes** have passed since `externalSince`, or a screen-off was seen (rule 2 of 10.2).
3. The grace ends when the picker's result arrives, when the app is visible again (the share sheet returns no result), or after 10 minutes.
4. Screen-off locks at once, as always. A file task cut short by a lock is continued after the next unlock (ux.md §4); a plain export then asks for the master password again, because it is never kept.
5. Opening Android's settings to add a fingerprint, opening a website and switching to another app are ordinary leaving.

What this costs: for up to 10 minutes after the user started a file action, someone who picks up the phone with its screen still on can come back into the open app. Both lock designs and ux.md chose 10 minutes; a Google Drive picker on a slow connection and a "send to Telegram" round trip fit in it. Because unfinished file tasks survive a lock anyway, the number can be lowered later without breaking anything.

### 10.4 What "lock" does

1. Drop the session: references to VK, every KEK and the decrypted entries are released and our own arrays are zeroed [V-C10].
2. Cancel an open fingerprint prompt, drop any `MasterProof`, close dialogs and sheets.
3. Replace the unlocked back stack by the lock screen. The route is kept as ids, so the same screen comes back after unlock with secrets masked (ux.md §4).
4. Keep what ux.md §4 lists: the clipboard, the encrypted draft of an unsaved form, an unfinished file task.
5. Choose the lock screen (13.1). A new visit starts when it is visible.

---

## 11. Language change and activity recreation

i18n.md §1.4.4 asks that recreating the activity neither locks the vault nor loses unsaved input. The activity is recreated when the user changes the language (`AppCompatDelegate.setApplicationLocales`; by the system on API 33+, by AppCompat on API 24–32), and also on rotation, a theme or font-size change and a window resize. `android:configChanges` is not declared (i18n.md rule 1).

**It does not lock, because nothing in the lock listens to the activity.**

1. Locking is decided only by the process-level lifecycle (10.2), an explicit "Lock now", the screen-off signal and the no-touch timer.
2. A recreation destroys the old activity and resumes the new one inside one turn of the main thread. `ProcessLifecycleOwner` posts its 700 ms "stopped" message to that same thread when the old activity pauses and removes it when the new one resumes, so the message cannot run in between, however slow the phone is [I; checked by E11 with the delay set to "Immediately"].
3. The session, the lock controller and the auto-lock clock are process-wide objects. They do not notice the recreation.

**Nothing typed is lost, because nothing typed lives in the activity.**

| What | Where it lives | Across a recreation |
|---|---|---|
| The open vault | Process-wide session | Kept |
| PIN digits typed so far, the charge, the visit and its list of wrong PINs, what an open fingerprint prompt is for and its launch number | Process-wide lock controller | Kept. The dots are redrawn; the same charge is used |
| The master-password field and every other secret field | A `TextFieldState` held by a ViewModel | Kept, and never written to saved state [L-win Experiment 5] |
| An add or edit form | Its ViewModel; an encrypted draft when the app is left (ux.md §5.14) | Kept |
| Which screen is shown | Saved state, as ids only (ux.md §4) | Kept |
| An open fingerprint prompt | The system | Stays open; its result reaches the new activity, because the prompt object is rebuilt in `onCreate` [L-bio case 10] |
| Open dialogs and sheets | — | Closed. They hold no input that matters |

**Rules for the code.**

- `Activity.onPause`, `onStop` and `onDestroy` never lock, never refund a charge, never clear the digits and never cancel a prompt. Only the auto-lock object and the user's own actions do.
- The lock controller hands the screen ids and arguments, never finished sentences; composables resolve them (i18n.md rule 2). "Wrong PIN. 3 tries left." is a plural resource.
- The language is changed from Settings, where the app is open and no secret field is on screen, or from the first-run screen, before a vault exists. The rules above hold everywhere all the same.

**When the activity really goes away** (the system destroys it in the background, or "Don't keep activities" is on) the ViewModels are lost but the process and its session live on. On return the normal lock decision applies; an unsaved form comes back from its encrypted draft; a half-typed PIN or master password is gone, and its charge was refunded when the app stopped being visible. When the process dies, the app starts locked.

---

## 12. Flows

Every step that touches a lock record runs under the lock mutex and uses the routine of 5.9. "A counted PIN check" is the whole protocol of 6.3. "Ask for the master password" is the check of 6.8. Screen texts are in section 13 and in ux.md.

### F1. First setup

1. Welcome, then "Start" (ux.md §5.1). "No vault" means the vault directory does not exist or is empty. A directory that exists but cannot be read is a storage error, never "no vault" [J-R D1-4].
2. Create the master password, write it down, type it again (ux.md §5.3–5.5; 8.1).
3. Create the vault: VK, `vault_id`, `vk_id`, generation 1, one password slot. Storage writes it, reads it back from disk and opens it through the normal master-password path with the bytes of the *second* typing [V-C5]. On failure: "Couldn't save. Your phone's storage may be full." with "Try again". Nothing else exists yet.
4. Create `lock/<vault_id>/` and write `state.rec` (practice step 0, `last_ok` now).
5. **Can this phone hold a key?** Generate `pv_canary` if it is missing and check it (4.5). If generation fails or the key is not in secure hardware: F18, then step 9.
6. **PIN.** "Choose a 6-digit PIN" (7.3), then "Enter the PIN again". "Not now" is allowed. Then F6 steps 4 to 7, using the `MasterProof` from step 3.
7. **Fingerprint** offer when 9.4 says so (ux.md §5.7): F9 steps 3 to 9 with the same proof.
8. "Make backups" (ux.md §5.8).
9. The list, unlocked.

If the app is killed after step 3, the next start finds a vault without `pin.rec`: the master-password screen, then step 5 (ux.md §4).

### F2. Daily unlock with the PIN

1. The app becomes visible and is locked. 13.1 chooses the pad. The newest generation's bytes are read into memory, still encrypted, so that opening is fast.
2. If fingerprint is usable its prompt opens by itself (F3). "Use PIN" or Back closes it.
3. First digit: charge (6.3 C).
4. Sixth digit: check (6.3 E).
5. Right: the vault opens. Notices follow one at a time (13.4).
6. Target: acceptance A1 (15.6).

### F3. Daily unlock with the fingerprint

1. The pad (or the fingerprint-only screen) is shown and fingerprint is usable (13.1); the prompt opens by itself once per visit (9.2 rule 7).
2. Before any prompt: `getKey("pv_fp_" + slot.id)`, then `Cipher.getInstance("AES/GCM/NoPadding").init(DECRYPT_MODE, key, GCMParameterSpec(128, nonce))` with the nonce of the wrap for the newest `vk_id`. Failures are classified by section 14 here, before the user is asked for a finger. This probe is made only while fingerprint is usable, that is, while `canAuthenticate` says 0 or −1: the platform reports a fingerprint key as permanently invalid whenever the phone's list of biometric ids is empty at that moment [X: `KeyStoreCryptoOperationUtils.canUserAuthorizationSucceed` in the android-37.0 sources], so a probe made at another time could remove a slot for nothing.
3. `authenticate(promptInfo, CryptoObject(cipher))`.
4. On success: `result.cryptoObject.cipher.doFinal(ct)` must give 32 bytes; storage opens the vault with them (I7). If the visit ended while the prompt was open, the key is dropped and the app stays locked (6.3).
5. A charge, if one is held, is refunded. No count is reset (6.4), and nothing is forgiven or hidden: the notice after this unlock reports every wrong PIN, those of this visit too (13.4).
6. Notices (13.4).
7. Closed, or an error: the pad with the message of 13.3. Nothing changes except what section 14 says. On the fingerprint-only screen the prompt's "Use master password" (code 13, its negative button there, 9.2 rule 5) opens the master-password screen, as the screen's own "Use master password" does (build-plan §12, decision 19).

### F4. A wrong PIN

1. 6.3 E.5 has counted it before anything is shown.
2. The dots shake (not when animations are off), reject haptic, the dots clear, "Wrong PIN. N tries left." (13.3).
3. The pad and the fingerprint key stay. The same wrong PIN typed again costs nothing (6.6).

### F5. The PIN limit

1. The fifth wrong PIN in a row, the tenth that was not forgiven, or a charge found at start that reaches either: 6.5. It can happen on the pad or in a prompt inside the open app (F6, F8, F11).
2. The user opens the app with the fingerprint or the master password. If the app was already open, it stays open.
3. The notice says how many wrong PINs were typed and when (13.4).
4. After a master-password unlock: "Choose a new PIN" straight away, with "Not now" (F6 from step 3; the proof is the unlock just made). After a fingerprint unlock: "PIN unlock is off. To choose a new PIN you need your master password." with "Choose a new PIN" (asks for it) and "Later".

### F6. Set or change the PIN

1. From Settings › App lock › "Change PIN" or "Set a PIN"; from the offers in F5, F14, F15 and F16; from setup.
2. **Proof.**
   - Change: "Enter your current PIN", a counted PIN check in a prompt inside the open app (6.3) that must yield the open vault's key, or "Use master password instead". What the prompt can end with:
     - *Wrong:* "Wrong PIN. N tries left." The try is counted, is never forgiven, and is reported at the next unlock (13.4).
     - *A limit:* the PIN is turned off as in 6.5. The prompt closes with "PIN unlock was turned off after too many wrong tries. Nothing was deleted. To choose a new PIN, type your master password." Buttons "Type master password" (the "Set" branch below) and "Later". The app stays open.
     - *The PIN cannot be checked* (TEMPORARY, or the charge cannot be written): the try is refunded. "Your PIN can't be checked right now. Use your master password instead, or try again later."
     - *Back or "Cancel":* a held charge is refunded. Nothing changes.
   - Set (no PIN exists): the `MasterProof` of the flow, otherwise ask for the master password.
3. The new PIN (7.2; if the current PIN was typed in step 2 the new one must differ), then once more.
4. Make sure `pv_canary` exists. Generate `pv_pin_<new id>` (4.2) and check it (4.5).
5. `KEK_pin = HMAC(new key, pin_msg)`; wrap the open vault's VK for its `vk_id` with a fresh nonce.
6. **Test it:** compute the HMAC again, open the new wrap and compare with VK.
7. Write `pin.rec` with the new slot, `off = null`, and the guard of 6.4. Zero the digits and `KEK_pin`. An old key is left to the sweep (5.7). "Your PIN is set." or "Your PIN was changed."
8. If anything in steps 4 to 6 fails, or the flow has ended meanwhile (a lock or `ON_STOP`): delete the new alias (no record names it), keep the old record. "Couldn't save the new PIN. Your old PIN still works." or "The PIN can't be set up right now. Try again later."

   If the record write of step 7 reports a failure, the lock reads `pin.rec` back and goes on with what is on disk. Such a write may still have replaced the record, because a failed directory sync counts as not written (5.9) while the rename is already on disk.
   - *The record read back is the old one* (or there is none, as before the write): nothing was saved, and the two sentences above apply.
   - *It is the new one:* the lock works from the new record from now on, so the new PIN is the one that opens the app, in this process as well. Whether the rename survives a power cut is not known, so the screen promises nothing: "The new PIN may not have been saved. Next time try the new PIN first. If it is not accepted, use the old PIN or your master password."
   - *It cannot be read back:* the lock keeps the record it had, and the screen says the same sentence.

   In all three cases the new alias is left to the sweep (5.7), like the old one: either record can be the one on disk after a power cut, and a record must never name a deleted key. At the next start the sweep deletes the key that no record names.

Version 1 has no switch to turn the PIN off. It can be skipped at setup and set later.

### F7. Forgot the PIN

1. "Forgot PIN? Use master password" on the pad. A held charge is refunded.
2. The master-password screen, without a subtitle. A correct master password opens the app and returns both counts to 0 (6.4).
3. "Choose a new PIN", with "Not now" (F6 from step 3; the proof is the unlock just made). The new slot replaces the old one. Nothing else changes.

### F8. Change the master password

1. Settings › App lock › Change master password. Ask for the current one. "I don't remember it" leads to F11.
2. The new master password: chosen, written down, typed again (ux.md §5.3–5.5). The screen is kept on for the last two (10.1).
3. **PIN.** If a PIN slot is usable: "Enter your app PIN to keep using it." This is a counted PIN check in a prompt (6.3); the right PIN yields `KEK_pin`, which is kept in memory until step 5. "Not now" is allowed. If the PIN cannot be checked right now, the flow goes on as after "Not now". If a limit is reached, the PIN is turned off (6.5) and the flow goes on without it. Whenever the vault would end up without a PIN for its new key: "Choose a PIN", skippable, done by F6 after step 6.
4. Make VK2 and `vk_id2`.
5. **Before the vault is written:**
   - write `state.rec` with `mp_change = {to_vk_id: vk_id2, at: now}` (5.6);
   - in `pin.rec`, drop every wrap except the one for the vault's current `vk_id`, add a wrap of VK2 for `vk_id2` under the same `KEK_pin`, and write. Zero `KEK_pin`. [D2 §9.7, J-R §6.6]
6. Storage writes a generation numbered above every file on disk [V-S1], sealed under VK2 with the new password slot and `vk_id2`; reads it back from disk; opens it through the normal master-password path with the bytes of the second typing [V-C5].
   - It fails before the new file is in place: nothing has changed. `mp_change = null`.
   - The file is in place but does not prove: storage moves it to a quarantine name, never deletes it, and syncs the directory. `mp_change = null`. "Couldn't change your master password. Nothing was changed. Your old master password, PIN and fingerprint still work." [D3]
   - Even that move fails: `mp_change` stays set. "Saving may not have finished. Next time, try your new master password first, then your old one." F16b keeps the second half of that promise.
7. **Fingerprint**, if a slot exists: "Touch the sensor to keep fingerprint unlock on." `Cipher.init(ENCRYPT_MODE, K_fp)` on the existing key, the prompt, `doFinal(VK2)`. Then, in `fingerprint.rec`, keep the wrap for `vk_id1`, drop any other, add the wrap for `vk_id2`, and write.
   - "Not now", a closed prompt or a TEMPORARY error: the slot keeps only its old wrap, and Settings shows "Fingerprint unlock: turn on again".
   - GONE (the key was invalidated or is missing): the slot is removed, as in F15a or F15b.
8. `state.rec`: practice step 0, `last_ok` now, counters zero, `mp_change = null`. `pin.rec`: guard zero.
9. "Make a new backup now. Your old backups still open only with your old master password." "Back up now" uses the master password just typed (F12).
   - **The app locked while the change ran** (the screen went off during "Saving…" or while the prompt of step 7 waited): the change runs to its end all the same, as a save does, and the next unlock shows "Your master password was changed." with this offer, "Back up now" and "Later". "Later" there puts the backup reminder off as "Later" on the list's banner and on "Make a backup now?" do (3 days; build-plan §12, decisions 15 and 21). "Back up now" uses the master password of that unlock when it was typed; after a PIN or fingerprint unlock the backup asks for it (F12). A PIN that was not kept is offered by that unlock (13.4 item 5).
10. **Later.** Storage retires the generations under the old password once the new one, typed, has opened the vault from disk at a later app start [V-C5, V-S5]. The wraps for `vk_id1` then go by the rule of 5.4: every unlock drops the wraps whose `vk_id` no kept generation carries. A slot left without any wrap is removed (`off = master_changed`).

| The process dies, or the change fails | Afterwards |
|---|---|
| Before step 5 | Nothing changed |
| After step 5, before the new file is in place | The newest copy is still `vk_id1`. The PIN opens it with its first wrap, and the unused wrap for `vk_id2` is dropped at that unlock (5.4). The old master password opens it and clears `mp_change` |
| The new file is in place, and the process dies before step 8 | The newest copy is `vk_id2`. The new master password and the PIN open it, and a typed master password that opens it clears `mp_change`. If the new master password does not open it, the old one reaches F16b with "Open my passwords from before the change". Unless step 7 had finished, the fingerprint key is hidden until fingerprint is turned on again |
| After step 6, PIN step skipped | The new master password once, then "Choose a PIN" |
| Step 6 failed and the change is started again | Step 5 drops the unused wrap of the first try before it adds the new one. A slot never holds more than two wraps |
| The password is changed twice before the app is restarted | Step 5 of the second change drops the wrap for the first key. Should both newer copies ever be lost, the copy under the first key needs the master password of that time (F16a) |

The PIN never opens an older copy while a newer one is intact.

### F9. Turn fingerprint unlock on or off

**On.**
1. Settings › App lock › Fingerprint unlock, or the first-run offer. Whether it is offered: 9.4.
2. Proof: the `MasterProof` of the flow, otherwise ask for the master password (8.2).
3. Say who gets in: "Anyone whose fingerprint is saved in this phone's settings can open the app."
4. Make sure `pv_canary` exists. Generate `pv_fp_<new id>` (4.2). `InvalidAlgorithmParameterException` here means no finger is enrolled: "Add a fingerprint in your phone's settings first." Check the key (4.5).
5. `Cipher.init(ENCRYPT_MODE, key)`, with no IV.
6. Prompt: "Turn on fingerprint unlock", negative button "Cancel".
7. On success: `ct = result.cryptoObject.cipher.doFinal(VK)` (48 bytes) and `nonce = cipher.iv` (12 bytes).
8. Write `fingerprint.rec` with the new slot and one wrap for the current `vk_id`. An earlier key is left to the sweep.
9. On any error or cancel in steps 4 to 7: delete the new alias and keep the earlier state. "Fingerprint unlock could not be turned on. You can try again later in Settings." If the record write of step 8 reports a failure, the lock reads `fingerprint.rec` back and goes on with what is on disk, as in F6 step 8. With the earlier record, the same sentence. With the new record, or with one that cannot be read back: "Fingerprint unlock may not have been turned on. If your fingerprint is not accepted, turn it on again in Settings." The new alias is left to the sweep in every case. Setup is never blocked.

There is no second touch to test the wrap. A wrap that does not match shows at its first use and is removed then (section 14).

**Off.** Write `fingerprint.rec` with `slot = null` and `off = turned_off`, then delete the alias at once. No proof.

### F10. Practice

1. 13.1 opens the master-password screen with the practice subtitle (ux.md §5.10).
2. Correct: 6.8 step 5, and the app opens. If a backup is due, the app offers "Make a backup now?" on this same entry (F12).
3. "Not now: use PIN", during the grace period: snooze for 20 hours, then the pad, and the fingerprint prompt as usual.
4. "I can't remember it": F11. A user who then leaves F11 without choosing a new master password is inside the open app, and practice is still overdue. The standing banner of 13.4 item 6 says so on the list until a master password has been typed or a new one chosen. Without it, a required practice could be skipped for ever with three taps, and requirement 7 would be met on paper only [A].
5. Wrong: the messages of 13.3 and the waits of 6.8. After 2 wrong tries, when a shortcut is usable: "Can't remember it? Open the app with your PIN, then choose a new master password." (ux.md §5.10).

### F11. The master password is forgotten, but the PIN or fingerprint still works

Without this route every backup becomes unopenable, and the next PIN lockout or lost phone loses everything (ux.md §5.24).

**Asking.**
1. From "I can't remember it" on the practice screen, from the first option of the "Forgot master password?" sheet, from the notices of 13.4 items 4 and 6, or from Settings › App lock › Change master password › "I don't remember it".
2. Explanation: "You can choose a new master password because your PIN still opens the app on this phone. For your safety it starts working after 24 hours with the phone switched on. Until then nothing changes. Backups you made before will still need your old master password." Buttons "Continue" and "Cancel". ("PIN" reads "fingerprint" when no PIN exists.)
3. **Proof now.** An app that is already open is not enough.
   - **The PIN, whenever there is one.** If `pin.rec` holds a slot with a wrap for the vault's current key, or cannot be read at the moment (UNREADABLE), the proof is the PIN typed now: a counted PIN check. On the lock screen it is an ordinary entry of that visit and also opens the app; inside the open app it is a prompt (6.3). A fingerprint is not accepted in its place. A sleeping owner's finger, or the finger of a second person saved on the phone, must not be enough to start a replacement [J-S 3-2].
     - *Wrong:* counted as always.
     - *A limit:* the PIN is turned off (6.5). The slot is then gone, and the next bullet applies. This is also the way for an owner who has forgotten the PIN as well and still has a fingerprint, so when fingerprint is usable the prompt says: "Forgot your PIN as well? After 5 wrong tries the PIN is turned off, and you can then continue with your fingerprint."
     - *The PIN cannot be checked right now* (TEMPORARY, storage full, UNREADABLE): "Your PIN can't be checked right now. Try again later." The flow stops. It does not fall back to the fingerprint, because filling the phone's storage would then be a way round the PIN.
   - **A fingerprint, only when there is no PIN.** `pin.rec` is MISSING, DAMAGED or NEWER, has no slot (never set, turned off at a limit, key gone), or has a slot that the owner's own master-password change left without a wrap for the current key (F8 step 3, "Not now"); and fingerprint is usable: the fingerprint unlock of F3. None of these states can be brought about without the master password, except the limit, which the owner is told about. This keeps the owner who has forgotten the master password and the PIN from being shut out; what it costs is in 2.3. A PIN cannot be made to disappear quietly: there is no switch for it, and turning it off with wrong tries leaves the notice of 13.4.
   - Neither: the route is not offered.

   **The proof goes with its flow** (5.8). The request of step 5 uses it up. When the flow ends without a request (Cancel on the explanation, Back from choosing, its screens closed any other way, in the open app or after the lock-screen route), the screens tell the lock, and the proof is dropped; a lock and the app leaving the screen drop it as well. A request later in the same session needs the PIN typed again, or the fingerprint where there is no PIN: otherwise the PIN typed once could start a replacement an hour later, which is what "an app that is already open is not enough" rules out [the review of build plan 4.4].
4. Choose the new master password, write it down, type it again (ux.md §5.3–5.5). The screen is kept on for the last two (10.1).
5. Derive `KEK_pending` with a new salt, wrap the open vault's VK (4.2), and write `state.rec` with `pending_mp = {requested: now, by, credited_ms: 0, seen_elapsed_ms: elapsedRealtime(), vk_id, salt, nonce, ct}`. Zero everything. An earlier request is replaced, and its wait starts again.
6. "Your new master password starts working after about 24 hours with the phone switched on. Keep the paper. Until then, open the app with your PIN or fingerprint as usual. The countdown stops while the phone is switched off. To keep it moving, open this app in the morning and again in the evening."

**Waiting.**
7. Time is credited by 6.9.
8. After every unlock a banner says who asked, when, and how long is left, with "Cancel it" (13.4). Cancelling needs no proof and deletes `pending_mp`.
9. Any correct current master password, typed anywhere, also deletes it: "You typed your master password, so the new one was cancelled."
10. Practice is not asked. A backup cannot be made (ux.md §5.19): "You can make a backup when your new master password has started working."
11. Before the last PIN try the warning says what is at stake (13.3).
12. The new password is not tried before its time. Under the master-password field: "Your new master password starts working in about N hours."

**Finishing**, when 24 hours are credited.
13. The banner becomes "Your new master password is ready. Type it to finish." with "Finish now". "Finish now", on the banner and on the notice of 13.4, opens a screen of the open app where the new master password is typed once, under the guards of 6.8; steps 14 to 19 follow there and nothing locks (the owner's decision 14 of 2026-10-08). The lock screen's master-password field accepts it as well: when no password slot of the vault opens and a pending password is ready, the pending wrap is tried (one more Argon2id). No PIN and no fingerprint is asked for here, on purpose: an owner who lost both during the wait must still get in [J-R D3-7].
14. The pending wrap opens and yields VK. If the app was locked, storage opens the vault with it. The wrap's tag proves that the bytes just typed are the password that was chosen and typed twice in step 4.
15. **Mark first.** The lock makes a new `vault_id`, here called N, and writes this vault's `state.rec` with `replaced = {new_vault_id: N, at: now, by, credited_ms: 0, seen_elapsed_ms: elapsedRealtime()}`. `pending_mp` stays. If the write fails: "Couldn't finish now. Nothing was changed. Try again later.", and the flow stops.
16. **A new vault, not a new key in the old one.** Storage builds vault N from the open entries: a new VK, a new `vk_id`, a new salt, a password slot made from the typed bytes, generation 1. It writes N beside the current vault, reads it back from disk and opens it with the typed bytes [V-C5]. Only then does it put the old vault aside, whole, and make N current, in one step that survives a crash. A restore over an existing vault uses the same storage call (F17 step 3).
    - It fails, before that step or inside it: the lock writes `replaced = null`. "Couldn't finish now. Nothing was changed. Try again later." The pending password stays ready, and PIN and fingerprint work as before. Should the failure leave no vault current, Welcome lists the old one (F17), as after a kill at that point.
17. The lock creates `lock/N/` with a `state.rec` (practice step 0, `last_ok` now, counters zero). The session continues on vault N. Steps 15 to 17 run to the end once they have started, also when the app is locked or left in the meantime, as a save does.
18. **The old vault is now a vault that was put aside (F17), marked `replaced`.** From this moment it offers no PIN, no fingerprint and no pending password. It opens only with its own master password, and only that password can delete it (F17 step 5). Nothing in its lock directory is written or deleted now: its two slots, their keys and its `pending_mp` are removed by the start-up step of 5.11, at a later process start that finds vault N on disk. The reason is the one the key sweep has (5.7).
19. Vault N gets its ways in as a restored vault does: F1 steps 5 to 7 (choose a PIN, the fingerprint offer), with the `MasterProof` of step 14. Then: "Make a new backup now. Your old backups still open only with your old master password." (F12).
20. Until 7 days are credited on the old vault's `replaced` record (6.9), every unlock of vault N is followed by "Your master password was replaced on 7 Oct using the PIN." with "It wasn't me" (13.4).

| The process dies | Afterwards |
|---|---|
| Before step 15 | Nothing changed. The new master password finishes at the next try |
| After step 15, before storage's last step | The old vault is current and N does not exist. Start-up (5.11) clears `replaced`. PIN, fingerprint and the pending password work as before; storage removes its unfinished copy of N |
| Inside storage's last step | It can happen that no vault is current. Welcome then lists the old vault (F17). Start-up has cleared its `replaced`, so its PIN, fingerprint, master password and pending password open it and make it current again |
| After that step, before step 17 | N is current and has no lock directory: "Welcome back" (F14) asks for the new master password and then offers a PIN. The old vault is put aside and marked |
| After step 17 | As planned. What step 19 did not reach is offered after the next unlock |

**Why it is shaped this way.**

| Concern | Answer |
|---|---|
| The owner forgot the new one as well | "Cancel it", then ask again |
| PIN and fingerprint are both lost during the wait | Nothing is lost. When the time is up the new master password opens the app by itself (step 13) [J-R D3-7] |
| Someone who saw the PIN asks for a new master password | They wait 24 credited hours that `adb`, the date setting and reboots cannot shorten [J-S 3-1]. Whoever opens the app in that time sees the banner, in their language, and cancels with one tap. Changing the PIN afterwards is within the owner's reach (8.2). An owner who does not open the app in that time is not warned (2.3) |
| The owner did not notice in time | The replacer then holds a working master password and can export and back up (2.3). They cannot lock the owner out through the app. The passwords as they were are a vault of their own: the owner's master password opens it (F16b), and the new master password can neither open nor delete it. No shortcut opens it either, so the PIN the replacer knew is worth nothing there |
| Someone who has only a finger saved on the phone | Cannot ask while a PIN exists. They would first have to turn the PIN off with five wrong tries, and the owner is told about those (13.4) |
| Why a new vault, and not a new key in the same vault as in F8 | The old passwords are then protected by the swap itself and not by a second write that a kill could prevent; a second replacement cannot overwrite the record of the first, because every old vault carries its own; and going back needs nothing that F17 does not have already [A] |
| Why not 72 hours and every enabled shortcut, as the security judge asked | Decision 14 |
| A copy of the app's files now also opens with the pending password | It is a master password like any other: chosen under the same rules and protected by the same Argon2id. Copying the files needs root in the first place |

### F12. Backup and export

1. Settings › Backup & transfer › "Make a backup", or "Export" and a format (ux.md §5.19, §5.20).
2. **Master password.** Typed now and checked against the vault's own password slot (6.8). A typo can therefore never produce a backup that the real password does not open (I3). It counts as practice.
   - **Encrypted backup only:** when the user typed the master password a moment ago in the same flow (practice, a master-password unlock, a change, the end of F11) and a backup is due, the app offers "Make a backup now?" and uses that entry. The typed bytes are kept only until the offer is answered. After a master-password unlock the offer follows the backup banner's snooze (ux.md §5.11): "Later" on it puts the offer and the banner off for 3 days, as the banner's "Later" does, and it comes at most once a day, answered or not (the owner's decision 15 of 2026-10-08).
   - **Plain export:** always typed now.
   - Neither is possible while a new master password is pending (F11 step 10).
3. Backup: a new container with its own key, salt and one password slot made from the typed password (formats.md §3). Plain export: formats.md.
4. **A backup is made before the file picker opens:** the new container is sealed with the typed password and opened once with it, and the password is dropped. The picker makes the app stop being visible, and that drops every proof (5.8), so the typed bytes are gone when the file comes back (the owner's decision 5 of 2026-10-08, §18 item 20). Then `session.beginExternal()` (10.3), the file picker, the write, then the read-back check before "Saved" [V-S6]: for a backup, what is read back must be, by length and SHA-256, what was sealed and opened.
5. Zero the password and keys. PIN and fingerprint are never used here, and no lock record changes except the practice dates and the counters.

### F13. Restore on a new phone

1. Welcome › "Restore from a backup file" (ux.md §5.2). Pick the file.
2. Type the master password the backup was made with. The header is bounds-checked before any key derivation [V-C4, V-C14]. No waits (6.8).
3. The backup opens and is checked. Summary, then "Restore".
4. Storage writes it as this phone's vault with a new `vault_id`, a new VK and `vk_id`, a new salt and a password slot from the password just typed; reads it back from disk and opens it with those bytes. The password was proved by opening the backup, so a typo is not possible here.
5. A new lock directory; `state.rec` with practice step 3.
6. Continue at F1 step 5.

### F14. A vault without lock records ("Welcome back")

This happens when a vault is on the phone without its lock directory: the app died between writing a vault and creating its directory (F1 steps 3 and 4, F11 steps 16 and 17); a manufacturer's phone-copy tool brought the vault files but not `noBackupFilesDir` (not checked, D6); or the directory was lost. A reinstall that keeps the app's data keeps the lock directory as well. What can be missing then is the keys, and that is F15b.
1. 13.1 opens the master-password screen titled "Welcome back" (ux.md §5.9).
2. After a correct master password: create the directory and `state.rec`, then F1 step 5 with the proof just made.

### F15. Keystore failures

**a. The fingerprint key is invalid** because the phone's fingerprints changed: `KeyPermanentlyInvalidatedException` at `Cipher.init` (F3 step 2, F8 step 7).
1. Write `fingerprint.rec` with `slot = null`, `off = key_gone`; delete the alias.
2. The pad says: "Fingerprint unlock was turned off because this phone's fingerprints or screen lock changed. Use your PIN. You can turn it on again in Settings." Without a PIN: the master-password screen with the same first sentence.
3. After unlock: "Turn fingerprint unlock on again?" leads to F9.

**b. A key is gone** (14.2 says GONE for `K_pin` or `K_fp`). A reinstall that kept the app's data looks like this too, if Android did not keep the keys: the records are there, every alias is missing, and the canary can be made again (14.2) [architecture.md D-21; not run].
1. Remove that slot (`off = key_gone`); delete the alias.
2. Fall back by 13.1, with the text of 13.3.
3. After unlock, offer to set it up again (F6 or F9).

**c. The Keystore is not answering** (TEMPORARY in section 14).
1. PIN: the charge is refunded (6.3 E.3), and the PIN is not offered again on this visit. Fingerprint: its key on the pad is hidden for this visit.
2. **Change nothing else.** Slots, keys, records and the vault stay as they are. Nothing is stored about the failure.
3. Text of 13.3. It suggests restarting the phone and says where the shortcut can be set up again. The next visit tries again.

**d. It keeps failing.** The app never removes a shortcut for a failure it cannot classify: some phones report a dead key as a passing error [V-C6], and a passing error must not cost a shortcut [J-R D2-3]. The user can replace it at any time. Settings › App lock › "Change PIN" › "Use master password instead" makes a new PIN key; turning fingerprint unlock off and on makes a new fingerprint key. The TEMPORARY texts say so, which is what [J-R D3-6] asked for. An earlier draft counted the boots on which a key had failed in order to time an offer; that state was removed, because the route above needs none [A]. A phone that failed every Keystore call until it was restarted [crypto.md §7] costs its user a master-password entry per unlock until the restart, and nothing else.

### F16. Older copies and older master passwords

**a. The newest copy is damaged and storage opens an older one.**
- It has a `vk_id` for which the shortcut holds a wrap: PIN and fingerprint work. After unlock, ux.md's "older copy" notice.
- It has another `vk_id`: the master-password screen says "The newest copy of your passwords can't be read. An older copy from 5 Oct opens with the master password you used then." The shortcuts are kept (I6).

**b. A master password from before.** Two different things go by that name, and they are handled apart.

*After an ordinary change (F8), until the old copies are retired.* The typed master password opens no slot of the newest copy, but opens the slot of an older copy of the same vault. Nothing is opened. The screen is titled "This is an older master password".
- **The change was proven** (`mp_change` is null): "You changed your master password on 7 Oct. Type the new one." Only "OK". The date is that of the first copy made with the new password, which storage reports. An old master password has to stop working when the user changes it; that is usually why they changed it.
- **The change did not finish** (`mp_change` is set: the process died between steps 5 and 8 of F8, or step 6 ended with "Saving may not have finished"): "Your master password change on 7 Oct may not have finished. Try your new master password first. If it does not work, you can open your passwords as they were before the change." Buttons "Try again" and "Open my passwords from before the change". This branch is taken only when all of these hold; otherwise the screen is the one for a proven change, with "OK" only:
  - the copy that the change made (the key `mp_change` names) is the newest copy on disk, for that is the copy the offer gives up. A mark whose change never reached the disk leaves nothing to go back from: the newest copy is the one from before, and its own master password opens it;
  - the kept copies are under exactly two keys, the one the change made and the one it started from, so that the typed password is the master password from directly before the change. With a third key kept (a second change before the copies of the first were retired, F8 step 10) storage cannot say which older key opened, and putting the newer copies aside would also undo an earlier change that was proven: a master password the owner changed away from must not come back;
  - every live copy could be read, and the screen is the current vault's: storage puts newer copies aside in the current vault only, so on the lock screen of a vault put aside an unfinished change can be given up only after that vault is made current.

  The second button:
  1. asks "Open your passwords as they were before 7 Oct? The copy made during the change stays on this phone but is no longer used." with "Open" and "Cancel";
  2. storage puts the generations above the copy that opened aside, never deleting them, so that this copy is the newest. The step can be repeated after an interruption;
  3. the vault opens with the VK just unwrapped. `state.rec` gets `mp_change = null`, `max_generation` = this copy, and what every correct master password brings (6.8). No shortcut is removed: the person typed the vault's master password as it now stands. The wraps for the abandoned key go by the rule of 5.4.

  If the process dies after step 2, the old master password simply opens the vault at the next start.

*After a replacement (F11).* The passwords as they were are not an older copy of this vault. They are a vault of their own that was put aside and marked `replaced` (F11 step 18).
1. **How the owner gets there.** A wrong master password on the current vault's screen adds: "The master password was replaced on 7 Oct using the PIN. If you didn't do that, open your passwords from before." with the button "Open my passwords from before 7 Oct". The same entry is on the "Forgot master password?" sheet (F17 step 1) and behind "It wasn't me" on the notice of 13.4. The typed password is not tried against the old vault behind the user's back.
2. **The old vault's own lock screen** (F17 step 4): "Your passwords from before 7 Oct 2026. Type the master password you used then." It has only the master-password field, because a `replaced` vault has no shortcuts. Its own `mp_guard` applies (6.8).
3. The master password opens it in place. Then the app asks: "These are your passwords as they were on 7 Oct. Passwords added or changed after that are in the newer set, which stays on this phone. Open the older set instead?" Buttons "Open my passwords from 7 Oct" and "Cancel". Cancel changes nothing.
4. Storage puts the current vault aside and makes the old one current (the swap of F17 step 4). The lock touches nothing of the vault that is now put aside: it keeps its records and keys, and its own PIN, fingerprint or master password opens it again from the sheet. If storage cannot make the swap (a storage error), nothing changed: that vault's own master-password field comes back, saying "Couldn't open your passwords from 7 Oct. Nothing was changed." (13.3), and typing its master password again asks the question of step 3 again.
5. The lock writes the old vault's records: both slots removed if start-up has not done so yet (`off = replaced`, aliases deleted), `pending_mp = null`, `replaced = null`. A write that fails is made good by start-up (5.11).
6. "Choose a new PIN. Someone else may know the old one." (F6 from step 3; the proof is the master password just typed), then the fingerprint offer of F9 with its sentence about whose fingers are saved on the phone.

| The process dies | Afterwards |
|---|---|
| Before step 4 | Nothing changed |
| Inside the swap | It can happen that no vault is current; Welcome then lists both (F17) |
| After step 4, before step 5 | The old vault is current and still marked. Start-up sees that the vault named in `replaced` is put aside, removes what step 5 would have removed and clears the mark (5.11) |

An owner who replaced the master password themselves and comes this way by mistake loses nothing: step 3 asks first, and the newer set comes back through "Open my previous passwords" (F17).

**c. Rollback.** After every open the lock compares the generation number with `state.rec`'s `max_generation`. Lower: ux.md's "older than last time" notice [V-C8]. Higher: store it. After "Open my passwords from before the change" the stored value is set to the copy the user chose. A vault made by F11 or F13 starts its own count.

### F17. Start with an empty app, and previous passwords

1. **The "Forgot master password?" sheet** (ux.md §5.10) lists, in this order and only where they apply:
   1. "Use my PIN" or "Use my fingerprint" → F11, whichever F11 step 3 accepts as proof;
   2. "Restore from a backup file";
   3. "Open my previous passwords (locked on 6 Oct 2026)", one entry for each vault that was put aside. For a vault put aside by a replacement the entry reads "Open my passwords from before 7 Oct 2026" (F16b);
   4. "Start with an empty app…", when no shortcut is usable now;
   5. "Try again".
2. **"Start with an empty app".** Storage moves the vault's files to an archive name with one rename and syncs (ux.md §5.10). **The lock does nothing:** `lock/<vault_id>/` and its Keystore keys stay where they are. Welcome follows (F1); the new vault gets a new `vault_id` and its own lock directory.
3. **"Restore from a backup file" on a phone that has a vault.** The backup is opened and checked first. Storage then writes it as a new vault beside the current one and proves it (F13 step 4). Only then does it put the current vault aside and make the new one current, in one step that survives a crash. F13 steps 5 and 6 follow. F11 step 16 uses the same storage call.
4. **"Open my previous passwords".** The app shows *that* vault's own lock screen, with the line "Passwords locked on 6 Oct 2026": its pad and fingerprint if their records and keys still work, otherwise its master-password field. A vault marked `replaced` shows only its master-password field (F16b). Its own records and counts apply, for the PIN and for the master password (6.8); practice is not asked there. Only when it has been opened does storage put the current vault aside and make this one current. Back returns to the current vault and changes nothing, so a stray tap cannot swap the vaults. If its right PIN, fingerprint or master password finds that no copy of it opens (all damaged, an I/O error, or made by a newer version of the app), that vault's screen stays and says "These passwords can't be opened. Their files on this phone are damaged." with Back only (13.2, 13.3; build-plan §12, decision 6). "Your passwords can't be opened" (13.1 row 2) is never shown for it: that is the current vault's screen, and its "Restore from a backup file" would put the current vault aside. Nothing is deleted, and the screen lasts until the visit ends.
5. **Settings › Backup & transfer › "Old passwords (locked on …)".** "Open" adds its accounts to the current vault and asks for that vault's master password, under that vault's own guard (6.8). "Delete" asks for that vault's master password, and nothing else will do: not the current vault's master password, not a pending password (F11), and not the passing of time. If another vault's `replaced` names the vault that is being deleted, the lock first does for that other vault what start-up would do (5.11 step 3, first row). Then storage deletes the files, and the lock deletes `lock/<its vault_id>/` and its keys.
6. Vaults that were put aside are never removed automatically. One whose master password is forgotten simply stays; it is small (ux.md §5.10).
7. **Every vault with a PIN has its own five tries.** A phone normally holds one vault. Each vault that was put aside while it had a working PIN adds five guesses for someone who holds the phone, and where the owner chose the same PIN again they are guesses at the same PIN (2.3).

**Why the lock state stays with the vault.** Suppose the owner has forgotten the master password, the PIN still works, and they restore an older backup from the lock screen. If putting the vault aside deleted its PIN, the newer passwords would be lost for good at that moment. The same holds for a vault put aside while the Keystore was failing: after a restart its PIN works again. This is invariant I9. The one exception is a vault that a finished replacement put aside (F11 step 18): whoever replaced the master password knew its PIN or had a finger on the phone, so that vault opens with its master password and with nothing else.

### F18. A phone that cannot keep keys in secure hardware

1. The check of F1 step 5 fails (4.5).
2. "This phone can't keep a PIN or fingerprint key in protected hardware, so the app opens with your master password only." Settings show PIN and fingerprint as unavailable, with that reason.
3. Every unlock is the master password. Practice is not asked.

---

## 13. The lock screen: states and messages

The look (layout, key sizes, haptics, landscape) is in ux.md §5.9 and §5.10. This section fixes what is shown when, and the English wording. All texts go into string resources in four languages (i18n.md); numbers use plural resources.

### 13.1 Which screen appears

`EntryPolicy.decide(...)` is a pure function. It runs whenever the app is locked and becomes visible. The first matching row wins.

| # | Condition | Screen |
|---|---|---|
| 1 | The vault directory does not exist or is empty | Welcome (F1). It lists vaults that were put aside (F17) |
| 2 | No generation can be read (all damaged, an I/O error, or made by a newer app version) | "Your passwords can't be opened" (ux.md §5.9). Never the lock, never an offer to delete |
| 3 | Practice is past its grace period, a shortcut is usable, and no new master password is pending | Master password, required. "I can't remember it" |
| 4 | Practice is due, within grace, not snoozed, a shortcut is usable, and no new master password is pending | Master password, with "Not now: use PIN" (or "…use fingerprint") and "I can't remember it" |
| 5 | The PIN is usable | **PIN pad.** With the fingerprint key and the automatic prompt if fingerprint is usable |
| 6 | Only fingerprint is usable | The fingerprint-only screen (13.2): the prompt opens by itself; behind it "Use fingerprint" and "Use master password"; no pad |
| 7 | Otherwise | Master password, with the first reason that applies (13.3) |

- **The PIN is usable** when `pin.rec` reads OK, has a slot with a wrap for the `vk_id` of the newest readable generation, `row < 5`, `total < 10`, the start-up step of 6.3 succeeded, and the Keystore has not failed to check it on this visit (F15c).
- **Fingerprint is usable** when `fingerprint.rec` reads OK, has a slot with a wrap for that `vk_id`, `canAuthenticate(BIOMETRIC_STRONG)` is 0 or −1, and it was not hidden on this visit.
- **`canAuthenticate` is asked once per visit, off the main thread,** and its answer is an input of `decide`. On Android 10 the library makes a Keystore key under the alias `androidxBiometric` every time it is asked [X: `BiometricManager.canAuthenticateWithStrongBiometricOnApi29` and `CryptoObjectUtils.createFakeCryptoObject` in 1.4.0-alpha07]. Until the answer is there, the pad is shown without the fingerprint key.
- **A fingerprint slot that cannot be used now is left alone.** When `canAuthenticate` gives any other value, the key is not probed and the slot is not removed; the status line says why (13.3). If the key did die, the first visit on which fingerprint can be used again finds that out (F3 step 2, F15a).
- **A vault that was put aside and is marked `replaced`** (5.6) has neither a PIN nor a fingerprint: master password only.
- For a vault that was put aside and is being opened from the sheet (F17 step 4), the same function runs on that vault's records, without rows 1 to 4. When a right entry there finds that no copy of it opens, its own screen says so (13.2, "A vault put aside that cannot be opened"); row 2 is never shown for it.
- The lock screen never says anything about what is inside, such as how many accounts there are (ux.md §5.9).

### 13.2 States

| State | Shown | Leaves to |
|---|---|---|
| Pad, idle | Title "PasswordVault", subtitle **"Enter your app PIN"**, 6 empty dots, the status line, the keypad, the fingerprint key if usable, "Forgot PIN? Use master password" | Entering, fingerprint prompt, master password |
| Pad, entering | Dots fill. No digit is ever shown. The status line stays | Opening, idle |
| Opening | The keypad is replaced by a progress indicator and "Opening…" | Unlocked, wrong, cannot check |
| Fingerprint only | Title "PasswordVault", the status line, the buttons "Use fingerprint" and "Use master password". No pad and no dots | Fingerprint prompt, master password |
| Fingerprint prompt | The system's prompt over the pad or over the fingerprint-only screen (9.2) | Unlocked, the screen behind it |
| Master password, idle | Title "Type your master password", the reason as subtitle, the field, "Unlock", and the buttons of 13.3 | Opening, pad, forgot sheet |
| Master password, opening | "Opening…" while Argon2id runs (0.3 to 1 s) | Unlocked, wrong, older password, the question of F16b step 3 |
| Master password, waiting | Field disabled, countdown from 0:30 | Idle |
| Older master password | F16b | Idle, unlocked |
| Open the older set? | On the lock screen of a vault from before a replacement, once its master password has opened it (F16b step 3): the title "Your passwords from before 7 Oct 2026", the question of F16b step 3, "Open my passwords from 7 Oct" and "Cancel". No field and no pad | Unlocked, with the two vaults changing places; that vault's master-password field again ("Cancel", or a swap that failed, with its line in 13.3); back to the current vault |
| Forgot sheet | F17 step 1 | — |
| A vault that was put aside | That vault's own pad, fingerprint-only or master-password screen, with its line (F17 step 4, F16b) | Unlocked, with the two vaults changing places; a vault put aside that cannot be opened; or back to the current vault |
| A vault put aside that cannot be opened | On the lock screen of a vault put aside, once a right PIN, fingerprint or master password has found that no copy of it opens (F17 step 4; build-plan §12, decision 6): its line, the title "These passwords can't be opened", "Their files on this phone are damaged." and "Back". No field, no pad, and never "Restore from a backup file". It lasts until the visit ends | Back to the current vault (Welcome, or "Your passwords can't be opened", when there is none that opens); at the next visit that vault's own screen again |

### 13.3 Wording

**On the pad (status line).** A line about a wrong PIN or about tries left stays until the visit ends. Typing or deleting digits does not remove it, so that someone who is handed the phone sees it (6.4).

| When | Text |
|---|---|
| Fresh pad, 2 or fewer tries left | "2 tries left before the PIN turns off." |
| Wrong PIN, 2 or more left | "Wrong PIN. 4 tries left." |
| Wrong PIN, 1 left | "Wrong PIN. 1 try left. After that you'll need your fingerprint or master password." (without fingerprint: "…your master password.") |
| The same, while a new master password is not yet working (F11) | "Careful: your new master password is not working yet. If the next PIN is wrong, the PIN turns off." |
| The same wrong PIN again | "You already tried that PIN. It was not counted again." |
| A try was counted at start (6.3 S) | "Your last PIN entry was interrupted and counted as a try. 4 tries left." |
| The charge could not be written | "Your phone's storage is full, so the PIN can't be checked now. Free some space or use your master password." |
| Fingerprint key invalid (F15a) | "Fingerprint unlock was turned off because this phone's fingerprints or screen lock changed. Use your PIN. You can turn it on again in Settings." |
| `fingerprint.rec` is damaged (5.3) | "Fingerprint unlock stopped working on this phone. Use your PIN. You can turn it on again in Settings." |
| Fingerprint locked by the system (codes 7, 9) | "Fingerprint is locked for now. Use your PIN." |
| Fingerprint succeeded but did not open (9.3) | "Fingerprint did not open the app this time. Enter your app PIN." |
| Fingerprint unlock is on, and `canAuthenticate` says 1, or the prompt ends with error 1 (switched off for apps) | "Fingerprint can't be used in apps right now. Use your PIN. In your phone's settings, check that fingerprint may be used to verify it's you in apps." |
| Fingerprint unlock is on, and `canAuthenticate` says 11 (no finger saved, or no screen lock) | "No fingerprint is saved on this phone now. Use your PIN. To use fingerprint unlock again, add a fingerprint in your phone's settings." |
| Fingerprint unlock is on, and `canAuthenticate` says 15 | "Your phone needs a security update before fingerprint can be used. Use your PIN." |
| Any other fingerprint error, or any other `canAuthenticate` value | The fingerprint key is hidden until the next visit. "Fingerprint can't be checked right now. Use your PIN. If this keeps happening, turn fingerprint unlock off and on again in Settings." |
| Debug and staging builds only | "TEST BUILD: PIN keys are not protected" |

There is no "Try again in 0:30" for the PIN: the PIN has no waits. There is never a message about wiping.

**On the fingerprint-only screen (status line).** The fingerprint lines above, with "Use your master password" in place of "Use your PIN", and one line about the PIN:

| Why there is no pad | Text |
|---|---|
| The PIN was turned off at a limit | "PIN unlock was turned off after too many wrong tries. Nothing was deleted. Use your fingerprint or master password, then choose a new PIN." |
| The PIN cannot be checked (the Keystore is not answering, or its record cannot be read right now) | "Your PIN can't be checked right now. Use your fingerprint or master password. Restarting the phone usually fixes this. If it keeps happening, set your PIN up again in Settings." |
| The PIN key is gone, or its record is damaged | "Your PIN stopped working on this phone. Use your fingerprint or master password, then choose a PIN again." |
| The start-up write of 6.3 S failed | "Your phone's storage is full, so the PIN can't be checked now. Free some space, or use your fingerprint or master password." |
| The PIN record was written by a newer version of the app | "Your PIN was set up by a newer version of this app. Use your fingerprint or master password, and update the app." |
| No PIN was ever set | none |

**On the master-password screen (subtitle = the reason).** When several reasons apply, the one highest in this table is shown.

| Reason | Text |
|---|---|
| The user chose it ("Forgot PIN?", "Use master password") | none |
| Practice | "Time to practise your master password. We ask now and then so you don't forget it. You need it to open a backup and to move to a new phone." |
| No lock records for this vault (F14) | Title "Welcome back". "Your PIN and fingerprint don't work here yet. This happens after you move to a new phone or install the app again. Type your master password once, then choose a PIN for this phone." |
| A vault from before a replacement (F16b) | Title "Your passwords from before 7 Oct 2026". "Type the master password you used then." |
| PIN turned off at a limit, no fingerprint | "Your PIN was entered wrong too many times, so it was turned off. Nothing was deleted. Type your master password, then choose a new PIN." |
| PIN key gone, or its record is damaged | "Your PIN stopped working on this phone. Type your master password, then choose a PIN again." |
| The PIN record was written by a newer version of the app | "Your PIN was set up by a newer version of this app. Use your master password, and update the app." |
| PIN cannot be checked (the Keystore is not answering, or its record cannot be read right now) | "Your PIN can't be checked right now. Use your master password this time. Restarting the phone usually fixes this. If it keeps happening, set your PIN up again in Settings." |
| The start-up write of 6.3 S failed | "Your phone's storage is full, so the PIN can't be checked now. Free some space, or use your master password." |
| The master password was changed and the PIN was not kept | "You changed your master password. Type it once, then choose a PIN again." |
| An older copy with another key (F16a) | "The newest copy of your passwords can't be read. An older copy from 5 Oct opens with the master password you used then." |
| No secure hardware (F18) | First time only: "This phone can't keep a PIN or fingerprint key in protected hardware, so the app opens with your master password only." |
| None of the above: no PIN is set (it was skipped at setup) | none |

| When | Text |
|---|---|
| Wrong | "That's not the right master password. Check the keyboard language (Latin or Cyrillic) and capital letters." [D1, J-U] |
| Wrong twice, a shortcut usable | adds "Can't remember it? Open the app with your PIN, then choose a new master password." with "I forgot it" (ux.md §5.10) |
| Wrong, and the passwords from before a replacement are on the phone (a vault marked `replaced`) | adds "The master password was replaced on 7 Oct using the PIN. If you didn't do that, open your passwords from before." with "Open my passwords from before 7 Oct" (F16b). If the master password was replaced more than once, the button opens the list of the sheet (F17 step 1), which has one entry for each |
| A new master password is pending and not ready | under the field: "Your new master password starts working in about 20 hours." |
| Waiting | "Wait 30 seconds, then try again. Take your time and look at your paper." |
| Not enough memory | "Your phone doesn't have enough free memory to open your passwords. Close other apps or restart the phone, then try again." with "Try again" |
| "Open my passwords from 7 Oct" was chosen and the vaults could not change places (F16b step 4, a storage error) | "Couldn't open your passwords from 7 Oct. Nothing was changed." on that vault's master-password field. The date is the one the question named, when the master password was replaced |

If fingerprint unlock is on and cannot be used now, the matching fingerprint line of the pad's table is shown under the reason, with "Use your master password".

Buttons: "Unlock"; "Use PIN" when the PIN is usable ("Not now: use PIN" on a practice day in grace); "I can't remember it" on practice screens; "Forgot master password?" always.

**On the screen of a vault put aside that cannot be opened** (13.2; build-plan §12, decision 6). Under that vault's line, the owner's two sentences: "These passwords can't be opened." as the title (without its full stop, as titles are) and "Their files on this phone are damaged." under it. The same words whether its copies are damaged, cannot be read now, or were made by a newer version of the app. One button, "Back".

### 13.4 Notices after unlock

Shown one at a time, in this order.

1. **Storage's notices:** an older copy was opened; older than last time (ux.md §5.9, F16).
2. **A new master password is pending:** "A new master password was chosen on 6 Oct, 14:02, using the PIN. It starts working in about 20 hours. The countdown stops while the phone is switched off; open this app in the morning and again in the evening to keep it moving. Not you? Cancel it, then change your PIN." Buttons "Cancel it" and "OK". When ready: "Your new master password is ready. Type it to finish." Buttons "Finish now", "Later", "Cancel it".
3. **A master password was replaced.** Shown until 7 days are credited on the old vault's `replaced` record (6.9), so that setting the date forward does not make it go away (I8): "Your master password was replaced on 7 Oct using the PIN. If you didn't do this, your passwords from before are still on this phone." Buttons "OK" and "It wasn't me" (F16b).
4. **Wrong tries nobody has been told about.** `unseen.count` holds them. A right PIN has already taken off the ones it forgave, which the person at the screen has just watched happen (6.3); a fingerprint or master-password unlock took nothing off. If the count is above 0: "A wrong PIN was typed 3 times before you opened the app (last time today at 14:32). If it wasn't you, keep your phone with you and change your PIN." Buttons "Change PIN" and "OK". When the user answers, the number that was shown is taken off `unseen`. A notice that was never shown takes nothing off.
   - If one of them was an interrupted entry: adds "One entry was interrupted and counted as a try."
   - If `total` is 6 or more: adds "After 4 more wrong PINs that are not put right at once, the PIN turns off. Type your master password once to start the count again." with "Type master password". If practice is also past its grace period, which is a sign that the user may not know the master password any more, it adds "Can't remember it? Choose a new one." with "Choose a new master password" (F11) [A].
   - Wrong master passwords are reported the same way, from `mp_guard.unseen`: "A wrong master password was typed 2 times before you opened the app." A right master password takes off its own slips by the rule the PIN has: those typed on the same visit in the 60 seconds before it, at most two. A PIN or fingerprint unlock takes nothing off, so that someone who tried master passwords and then handed the phone over is reported.
5. **A shortcut needs attention:** "Choose a new PIN" (F5, F7, F14, F16b, and after a master-password unlock that found `pin.rec` damaged, 5.3); "Turn fingerprint unlock on again?" (F15a, and after a master-password unlock that found `fingerprint.rec` damaged, 5.3); "Part of the app's storage could not be read. Make a backup now." (5.3).
6. **Practice is overdue.** Not a dialog but a standing banner at the top of the list, whenever the app is open, practice is past its grace period and no new master password is pending: "You have not typed your master password since 12 Sep. Without it you cannot open a backup or move to a new phone." Buttons "Type it now" and "I forgot it" (F11). It goes when a master password has been typed (F10 step 4) [A].
7. **"Make a backup now?"** when the master password was just typed and a backup is due (F12).

### 13.5 The pad and the windows

These are the settings that ran in the window lab on Android 17 [L-win, "Final code"]; `VaultSecurity.kt` in `docs/lab/window-input-artifacts/` is the code to copy, with one gate added (next item).

- **Activity window,** before `setContent`: `FLAG_SECURE` (release and staging builds; debug builds leave it off, as the project does today); `decorView.filterTouchesWhenObscured = true`; **on API 26+** `decorView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` as a safety net; on API 34+ `decorView.setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES)`; on API 31+ `setHideOverlayWindows(true)`, only after checking that `HIDE_OVERLAY_WINDOWS` is granted (it throws otherwise); on API 33+ `setRecentsScreenshotEnabled(false)`.
- **The autofill setter does not exist on Android 7.0 and 7.1.** `View.setImportantForAutofill` was added in API 26 [X: `api-versions.xml`]. Line 51 of the lab's `VaultSecurity.kt` calls it without a check, and that file ran only on Android 17; copied as it is, it ends `onCreate` with `NoSuchMethodError` on API 24 and 25 [A]. Android 7 has no autofill framework, so nothing is lost by skipping the call there.
- **Every dialog and bottom sheet** (they are separate windows) wraps its content in `VaultWindow { }`, which applies the same policy to its own window and removes the "Autofill" item from the text toolbar.
- **Every text field** has `Modifier.vaultTextField()` (`contentDataType = ContentDataType.None`, `isSensitiveData = true`), inside one `NoPersonalizedLearning { }` around the app's content.
- **The touch filter belongs on the decor view or the ComposeView.** On `LocalView.current` it does nothing [L-win Experiment 4].
- **The pad has no text field.** Its dots and keys carry `semantics { isSensitiveData = true }` (effective from API 34 [V-P5]). The dots are read as "PIN, 3 of 6 digits entered". If TalkBack cannot press keys that carry the flag (check E12), the keys drop it and the dots keep it [D2].
- **The master-password field** is an `OutlinedSecureTextField` with an explicit `textObfuscationMode` and ViewModel-held state (5.8). Like every password field (`VaultSecureField`), while it is drawn as dots its accessibility node holds one mask character per character instead of the text, on every version of Android: Compose's own text field reports the plain text there, unlike the platform's password field, so before Android 14, where `isSensitiveData` has no effect, any accessibility service the user turned on read the typed master password (`tools/emu/window/a11y.sh` on API 28, build plan 3.12). No accessibility service, an accessibility tool or not, then reads any character of it, in the node or in an event; the field still reads as a password of as many characters as it holds, and a screen reader still focuses it, types, deletes and moves the cursor. Shown ("Show password"), it reports its text, which is then on the screen (2.3); the decor view's accessibility delegate gives the event that hiding it sends mask characters as its "before" text.
- **Never a Toast with anything secret:** the system draws toasts and they are captured in full despite `FLAG_SECURE` [L-win Experiment 1].
- The activity is `singleTask`. Back on the lock screen leaves the app.
- On Android 7 and 8 the library draws the fingerprint dialog itself, as an AppCompat dialog in our activity [L-old §3]. It shows a title, an icon and one button, nothing secret.

---

## 14. Error classification

### 14.1 Classes

| Class | Meaning | What the app does |
|---|---|---|
| **GONE** | The platform states that this key, or this wrap, can never work again | Remove that one slot, delete its alias, fall back to the next way in |
| **TEMPORARY** | Anything else that stopped a shortcut | Use another way this time. Change nothing and store nothing, except a refund |
| **WRONG** | The PIN was checked and is wrong | Counted (6.3) |
| **CLOSED** | The user or the system closed the prompt | Show the pad; on the fingerprint-only screen, its negative button "Use master password" (13) shows the master-password screen (decision 19) |
| **NOT NOW** | A shortcut cannot be set up at this moment | Say why; nothing changes |

Removing a slot never costs data by itself. But for an owner who has forgotten the master password it can cost everything, so GONE is reserved for what the platform says outright, and every doubt is TEMPORARY [J-R D2-3].

### 14.2 Is the key gone, or is the Keystore not answering?

```kotlin
fun load(alias: String): Ks<SecretKey> {
    val first = try { keyStore.getKey(alias, null) }
        catch (e: UnrecoverableKeyException) { return if (e.cause == null) Ks.Gone("invalidated") else Ks.Temporary(e) }
        catch (e: Exception) { return Ks.Temporary(e) }
    if (first != null) return Ks.Ok(first as SecretKey)
    // null. Ask the Keystore something whose answer is known.
    var canary = try { keyStore.getKey("pv_canary", null) } catch (e: Exception) { return Ks.Temporary(e) }
    if (canary == null) {                                  // nothing answers, or every key of this app is gone
        if (!makeCanary()) return Ks.Temporary(null)       // it cannot make a key either: the Keystore is not answering
        canary = try { keyStore.getKey("pv_canary", null) } catch (e: Exception) { return Ks.Temporary(e) }
        if (canary == null) return Ks.Temporary(null)
    }
    val again = try { keyStore.getKey(alias, null) } catch (e: Exception) { return Ks.Temporary(e) }
    return if (again != null) Ks.Ok(again as SecretKey) else Ks.Gone("not found")
}
```

- **`null` is believed only when the canary loads in the same breath.** On API 24 to 30, `getKey()` returns null for *any* Keystore error, including an unreachable service [J-R claim 1, J-S claim 7]. On API 31+ null means only "not found" [J-S claim 6; X: in the android-37.0 sources, `AndroidKeyStoreSpi.engineGetKey` and `AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore` give null for `KEY_NOT_FOUND`, and otherwise only for an entry that holds nothing but a certificate, which these aliases never are], but one rule for every version is simpler and can only err towards TEMPORARY.
- **When the canary is missing too, the app tries to make it** [A]. `makeCanary()` is the generation of 4.2 with the hardware check of 4.5; anything but a hardware-backed key that then loads counts as "not answering". If the Keystore can make and load a new key and still has no entry for the alias, the alias is gone. Without this step, a phone that has lost every key of the app (a reinstall that kept the data but not the keys, a Keystore that was reset) would say "Restarting the phone usually fixes this" at every unlock for ever. On API 31+ the conclusion is certain. On API 24 to 30 it is a judgement: a Keystore that answers for new keys and transiently returns null for one old key is not known to exist [I].
- **`UnrecoverableKeyException` without a cause** is how the platform reports a permanently invalidated key from `getKey()` on old and new versions; with a `KeyStoreException` as cause it is any other failure [J-R claim 2, J-U §2].
- `KeyStore.containsAlias()` is never used: it is false on any error [V-C6].
- `KeyStoreException.isTransientFailure()` and `getRetryPolicy()` are never used for a decision: "Device locked" reports itself as not transient, retry never [J-R claim 9].
- This is the tested lab class `docs/lab/biometric-artifacts/BiometricVaultKey.kt` with two changes: `UnrecoverableKeyException` is GONE only without a cause (the lab never saw this exception and classed it GONE always), and null goes through the canary.

### 14.3 The table

| Where | What happens | Class | Notes |
|---|---|---|---|
| `KeyStore.getInstance("AndroidKeyStore").load(null)` | Any exception | TEMPORARY | — |
| `getKey` | 14.2 | | |
| `Mac.init`, `Cipher.init` | `KeyPermanentlyInvalidatedException` | **GONE** | Seen after a finger was added and after all fingers were deleted, in both cipher modes [L-bio case 6] |
| | `UserNotAuthenticatedException` | TEMPORARY | Not expected for these keys |
| | Any other `InvalidKeyException`, for example "Keystore operation failed" caused by "Device locked" for the PIN key on API 35+ [L-bio case 7] | TEMPORARY | Never GONE [V-C6] |
| | Any other exception | TEMPORARY | — |
| `Mac.doFinal` (PIN) | Any exception | TEMPORARY | Refund: no output, no information |
| Opening the PIN wraps in the app | Every tag fails | **WRONG** | Counted |
| | One opens, another fails | — | The PIN is right; the failing wrap is dropped |
| `onAuthenticationSucceeded` | `cryptoObject` or its cipher is null | TEMPORARY | Never "unlocked" |
| Fingerprint `doFinal` | 32 bytes | — | Open the vault |
| | `AEADBadTagException` | **GONE** (`bad_wrap`) | The stored wrap does not match the key; seen with an altered wrap [L-bio] |
| | A result that is not 32 bytes | **GONE** (`bad_wrap`) | — |
| | Any other exception, for example `IllegalBlockSizeException` caused by "Key user not authenticated" | TEMPORARY | Seen after the phone's PIN was typed on the prompt's lockout screen [L-bio finding b] |
| `onAuthenticationError` | 10 user cancelled, 13 negative button, 5 cancelled | CLOSED | 13 on the fingerprint-only screen: the master-password screen (F3 step 7) |
| | 7 lockout, 9 permanent lockout | TEMPORARY | Lockout text |
| | 1 hardware unavailable | TEMPORARY | The "fingerprint for apps" text [L-bio finding d] |
| | 11 none enrolled, 12 no hardware, 2, 3, 4, 8, 14, 15, any unknown code | TEMPORARY | If the key really died, the next `Cipher.init` says so |
| Any prompt callback | No launch is outstanding | — | Dropped (9.2 rule 8) |
| `canAuthenticate` at the start of a visit | Not 0 or −1 while a fingerprint slot exists | — | The key is not probed and the slot stays; the status line says why (13.1, 13.3) |
| `onAuthenticationFailed` | A finger that is not recognised | — | The prompt stays open by itself |
| Making a key | `InvalidAlgorithmParameterException` because no biometric is enrolled [L-bio case 12] | NOT NOW | "Add a fingerprint in your phone's settings first." |
| | Any other exception, including the 50,000-key limit of API 37 | NOT NOW | Delete the half-made alias; nothing else changes |
| | `KeyInfo` says the key is in software (release builds) | NOT NOW, for good | Delete the key; F18 |
| `deleteEntry` | Any exception | — | Ignored; the sweep retries |
| Reading a record | 5.3 | | |
| Writing a record | Any exception | Not written | A failed charge means no check (6.3 C.3) |
| Argon2id | `UnsatisfiedLinkError` (catch `Throwable` [V-C2]), `OutOfMemoryError` | — | The Java fallback after a free-heap check, otherwise "not enough memory". Never "wrong password", never counted |

No row deletes or rewrites vault data (I1).

**For the code.** The table lives in `:core` as a pure function over a small description of the failure (`NullKey`, `UnrecoverableNoCause`, `UnrecoverableWithCause`, `PermanentlyInvalidated`, `UserNotAuthenticated`, `OtherInvalidKey`, `BadTag`, `Other`), so that it is tested on the JVM. A short adapter in `:app` turns real exceptions into that description; check E10 exercises it.

---

## 15. Test plan

### 15.1 How the code is split so that it can be tested

`:core` (no Android calls, tests on the JVM) holds every decision: `PinBlocklist`, `MasterPasswordBytes`, the record codec, `PinGuard`, the master-password guard, `Credit`, the practice schedule, `EntryPolicy`, the error table and the flow logic. It reaches the outside only through interfaces:

| Interface | Real implementation in `:app` | Fake for JVM tests |
|---|---|---|
| `RecordFiles` | `Os.*` (5.9) | Keeps "handed over" and "durable" copies apart; `kill()`, `powerCut(settleMs)`, `failNextWrite()`, stop at any boundary of the write |
| `Clock` | `elapsedRealtime()`, wall clock | Both settable; the wall clock can jump either way |
| `PinKey`, `FingerprintKey` | Android Keystore, `BiometricPrompt` | Software HMAC and AES-GCM with scripted results: null from `getKey`, each exception of 14.3, a prompt result, a bad tag, a canary that is missing and can or cannot be made |
| `VaultPort` | The storage module | The vaults on the phone, current and put aside, each a list of generations with `vk_id` and damage flags; a record of every write call; a stop at every boundary of "make a new vault and put the old one aside" |
| `Kdf` | Argon2id | Bouncy Castle |

### 15.2 JVM unit tests

| ID | Test | Passes when |
|---|---|---|
| T1 | Blocklist over all 10^6 PINs | Exactly 126,337 refused; `rule()` gives 12,700 / 2,340 / 424 / 260 / 2,066 / 3,904 / 3,698 / 83,696 / 17,249 for rules 1 to 9; the SHA-256 of appendix A; every example of 7.2 lands on its rule; 582913, 143637, 905127, 368014 and 321299 are allowed |
| T2 | The vectors of 4.3 | `pin_msg`, `KEK_pin`, the wrap; PIN 582914 and swapped ids fail the tag; the six apostrophe spellings, the Greek signs among them, and the decomposed spelling give the listed bytes and Argon2id outputs; for 10,000 random strings over the two fold lists and combining marks, `of(of(x)) = of(x)` and `of(NFD(x)) = of(NFC(x))` |
| T3 | Record codec | The examples of 5.4 to 5.6 round-trip; every framing, hash, length and field violation is DAMAGED, a third wrap among them; a valid record with `"v": 2` is NEWER; an I/O error is UNREADABLE; a missing file is MISSING; random bytes never throw past the codec |
| T4 | `PinGuard`, 100,000 random sequences of: digit, delete, sixth digit (wrong or right) on a lock screen or in a prompt, fingerprint success, master password, kill, power cut with a 1,000 ms settle time, reboot, clock jump of ±1 year, wait 0 to 90 s, the end of a visit (also while a check is running), Keystore temporary, Keystore gone, write failure | (a) no HMAC without a durable charge at least 1,000 ms old (I4); (b) wrong PINs since the last right PIN or master password ≤ 5; (c) wrong PINs since the last master password that nothing was allowed to forgive ≤ 10, where a right PIN may forgive only wrong PINs typed on a lock screen in the same visit, at most 60 s before it, and at most 2; (d) the slot is removed and the key deleted exactly when a limit is reached; (e) a refund never changes a count; (f) a fingerprint success changes no count and no `unseen` (I5); (g) a check whose visit has ended opens nothing (I11) |
| T5 | The model of appendix B, ported | Holds for limits 2 and 3 with settle 1, forgiving 2 within a window of 3, 34 steps deep; the four broken variants are found broken |
| T6 | Start-up step | A charge found is counted and marked interrupted; reaching a limit turns the PIN off; a failed start-up write leaves the PIN unusable in that process, with the "storage is full" reason |
| T7 | The same PIN twice | Not checked, not counted, refunded, also an hour later in the same process. Forgotten after a restart, after a right PIN and after a master password |
| T8 | Forgiving | On one visit: wrong, wrong, right within 60 s leaves `total` and `unseen` as before; wrong three times, then right, leaves both one higher; wrong, 61 s, right leaves both one higher; wrong, the visit ends, right leaves both one higher. In a prompt: wrong, right leaves both one higher, and the next right PIN on the lock screen does not forgive it. A fingerprint or master-password unlock on the same visit changes no `unseen`. A master password gives 0 and 0 |
| T9 | Master-password guard | Tries 1 to 5 without a wait; 30 s before each later try, never more; after a simulated reboot the remaining wait is 30 s minus uptime; a failed write does not stop the attempt; a backup file's password never waits; a vault that was put aside uses its own guard; out-of-memory is not counted; a result that arrives after the visit has ended opens nothing |
| T10 | `Credit` | Over random observations and reboots the credit never exceeds the true elapsed time and is exact on one boot. The function takes no boot count and no date, so neither can change it [J-S §2.1]. The three rows of 6.9 come out of the scenarios of appendix F |
| T11 | Practice | Due on days 1, 3, 7, 21, 35 with timely answers; the 20-hour rule; grace of 1, 2, 4, 7 days; snooze of 20 hours; clock set back by more than an hour means due; restore gives step 3; not asked while a new master password is pending; the standing banner is due exactly while practice is past its grace and no new master password is pending |
| T12 | `EntryPolicy` | The table of 13.1 for every combination of the five read results per record, slot and wrap matches, counts, practice states, a pending password, every `canAuthenticate` value, a PIN hidden after a TEMPORARY result, and a vault marked `replaced`; every row of the three reason tables of 13.3 is reached by some input |
| T13 | Error table | Every row of 14.3; null with the canary present is GONE; null with the canary missing and not makeable is TEMPORARY; null with the canary missing, then made and loaded, is GONE; `UnrecoverableKeyException` with and without a cause; a callback with no launch outstanding is dropped |
| T14 | Crash injection: the fake file system and the fake storage stop at every boundary (before the temp write, after the file sync, after the rename, after the directory sync; before and after every storage step) in F5, F6, F8, F9, F11 (asking and finishing), F13, F14, both branches of F16b, F17 steps 3 and 4, and the start-up steps of 5.11 | After the "restart" every vault on the phone opens with its own master password; I1 to I6 hold; no key named by a durable record was deleted; the PIN never opens an older copy while a newer one is intact; from the moment a replacement is finished, the old vault has no usable shortcut at any stopping point |
| T15 | Master-password change killed after every step | The table in F8, with a failed step 6 that is started again and with two changes in one process; a slot never holds more than two wraps; `mp_change` is set exactly while the new copy is unproven; only then does the old password reach "Open my passwords from before the change" |
| T16 | Replacement (F11, F16b) | It cannot be asked for with a fingerprint while a PIN slot for the current key exists, nor while the PIN cannot be checked. It cannot finish before 24 hours are credited, under kills and reboots. It is cancelled by the button and by a correct master password. Finishing writes `replaced` before storage is called; a kill at any later point leaves either the old vault as it was, or the new vault current and the old one put aside and marked. A second replacement leaves two marked vaults, each with its own record. The new master password neither opens nor deletes a marked vault. The old one opens it and, after the question, makes it current; it then has no slots and no mark. The notice is shown until 7 days are credited, whatever the wall clock says |
| T17 | **The lock never writes vault data** | A spy `VaultPort` sees no write in 1,000 random sequences of lock events outside F1, F8, F11, F13, F16b and F17; an architecture test forbids the lock package from importing storage's writer [D2 T13] |
| T18 | The sweep | Never deletes an alias named by any record of any vault directory; does nothing when any record is DAMAGED, NEWER or UNREADABLE, or when `aliases()` is empty; deletes real orphans; never touches `androidxBiometric` |
| T19 | Session | Delay arithmetic; "Immediately"; screen off; the no-touch timer, which stands still while the app is stopped or an external screen is pending (6 minutes in a file picker do not lock, 11 do); the external grace and its 10-minute cap; a missing setting means "Immediately"; a recreation ends no visit; a check that finishes after `ON_STOP` opens nothing |
| T20 | The simulation of appendix D | Under 1 % of 14-day periods lose the PIN at 20 unlocks a day and 3 % typos |

### 15.3 Emulator checks

**Set-up.**
- **Two builds.** Every check runs on the `debug` build and again on the `staging` build of architecture.md §7.2: R8 on, not debuggable, `ALLOW_SOFTWARE_KEYSTORE` true, with the debug receiver, never given to anyone. Release builds refuse software keys (4.5), so on an emulator a release build can never set a PIN or a fingerprint. Without `staging`, the lock's optimised code would run for the first time on a real phone, and the build file already records one start-up crash that only R8 produced [A].
- The emulators are started read-only, on their own ports. **Never run `adb kill-server`.** They are the private devices of `tools/emu/boot.sh` (API 24, 28 and 37, from the bases under `/home/asus/pvlab/`); `tools/emu/README.md` describes them and the scripts, and every `adb` call goes through `tools/emu/adb.sh` or its library (`pv_use`, `pv_adb`, `pv_step`). On all three: the device PIN is **1111**, emulator fingers **1 and 2** are enrolled (any other id is "not recognized"), animations are off and the screen stays awake. API 28 has no start-up (full-disk-encryption) PIN. A reboot (E3) is `tools/emu/adb.sh -s SERIAL --reboot`, which waits for the new boot, dismisses the lock screen and keeps what the read-only device holds (the app and its files) until the emulator exits; after `KEYCODE_SLEEP` the lock screen is dismissed with `tools/emu/adb.sh -s SERIAL --unlock`.
- Scripts from `docs/lab/biometric-artifacts/`, used as they are:
  - `enroll_fingerprint.sh PORT [PIN] [FINGER]` sets the phone's PIN and enrols a finger (exit 0 enrolled, 1 not confirmed, 2 no device, 3 PIN problem); it knows only the API 37 Settings screens (on API 28 it waits at NEXT until it times out), and the bases already have fingers 1 and 2;
  - `delete_all_fingerprints.sh PORT [PIN]`;
  - `uitap.py PORT REGEX` taps a node by its text or description, which is how the pad keys "1" to "0" and the buttons are pressed;
  - `uidump.sh PORT` prints the screen's nodes.
- `lab.sh PORT CMD` sends a command and prints the log lines it caused. For the app it needs its broadcast action and log tag changed (two lines). The `debug` and `staging` source sets, which release builds do not contain, register a receiver for `adb shell am broadcast -a io.github.dilshodbekmx.passwordvault.debug.CMD -p <package> --es cmd <name>` and log under the tag `PVLOCK`. Commands: `status` (the inputs and result of 13.1, the counts, the aliases present), `hold-after-charge`, `hold-before-hmac`, `release`, `fail-next-write`, `ks-fault <row of 14.3>`, `drop-keys` (deletes every alias of the app, the canary included).
- A finger is `adb -s emulator-PORT emu finger touch N` followed by `emu finger remove`; touches sent together are merged into one [L-bio Experiment 1].
- A secure window is photographed with `adb -s emulator-PORT emu screenrecord screenshot DIR`; `screencap` shows nothing of it: a black picture on API 37 [L-bio], and no picture at all on API 24 (an empty file, exit 0) and API 28 (exit 1), as the 3B device check found (`tools/emu/checks/3B.sh secure`).
- These lab scripts take a console PORT and call `adb -s` themselves, so they skip the serial checks of `tools/emu/adb.sh`; the lock's scripts (build plan 4.5) wrap or adapt them. The lab's own Android 7.0 and 9 set-up in `docs/lab/old-android-artifacts/README.txt` (`labenv.sh`, `biotest.sh`, `heapsim.sh`) described devices under `/tmp` that no longer exist (there API 24 had fingers 1 to 3, and API 28's PIN was also its start-up password); the lock's scripts adapt `heapsim.sh` for E16 to the `boot.sh` devices.
- The debug receiver (`DebugCommandReceiver`, build plan 0.1) answers every command in the broadcast's result, which `am broadcast` prints as `result=<code>, data="<text>"` (1 done, 2 unknown command, 3 failed or timed out, 0 no receiver), and logs that text under the tag `PVDEBUG`; the lock's commands may log their own lines under `PVLOCK`.
- **E8 is run last on an emulator, or the emulator is cold-booted after it.** It leaves the emulator's sensor locked out until a reboot [L-bio case 4].

| ID | Check | Android |
|---|---|---|
| E1 | Set up, unlock with the PIN through a real Keystore HMAC key; five wrong PINs: slot gone, alias gone (`status`), master password still opens. Log the time from the sixth digit to the list | 7, 9, 17 |
| E2 | Kill during an entry: `hold-after-charge`, then `adb shell am force-stop`, restart: one try counted, "interrupted" shown. The same with `hold-before-hmac` and `kill -9` | 7, 9, 17 |
| E3 | `adb reboot` with a charge held: counted after the reboot | 7, 9, 17 |
| E4 | Clock: automatic time off, `adb shell cmd alarm set-time` a year ahead and back: tries left unchanged; a pending 24-hour wait unchanged; the "replaced" notice still shown; practice due after the clock went back | 17 |
| E5 | `adb shell settings put global boot_count 99999`, three times, bringing the app forward each time: the 24-hour wait gains only the real minutes [J-S §2.1] | 17 |
| E6 | Fingerprint with 1.4.0-alpha07, with `setConfirmationRequired` left at its default as the app ships it: enrol, turn on, unlock with `finger touch 1` and no further tap; `touch 2` leaves the prompt open. On Android 7 the library's own dialog appears inside the activity, and "Use PIN" returns to the pad | 7, 9, 17 |
| E7 | Phone settings against keys. **(a) Fingers.** Enrol another finger: at the next visit fingerprint is GONE with its text, and **the PIN still works**. Turn fingerprint on again and delete all fingers: the pad says that no fingerprint is saved, nothing is probed and the slot stays; enrol a finger: GONE with its text. **(b) Screen lock.** Remove it (`adb shell locksettings clear --old 1111`; through Settings on Android 7): **`K_pin` and the canary still load** and the PIN opens the app [J-R D3-8]; the pad shows the line for `canAuthenticate` 11. Set a screen lock and a finger again: fingerprint is GONE through 14.2. Part (b) was never tried on the Android 7 and 9 images: record what happens there | (a) 17. (b) 7, 9, 17 |
| E8 | The lockout trap: wrong fingers until the system shows its own PIN screen; type the phone's PIN; touch: "Fingerprint did not open the app this time…", nothing removed. Five wrong fingers lock the phone: on return the pad shows, the late code-10 callback is dropped, no prompt loop starts, and after a cold boot the fingerprint key opens a working prompt | 17 |
| E9 | `adb shell settings put secure fingerptint_app_enabled 0` (the setting's name has this typo [L-bio finding d]): the fingerprint key is hidden, no prompt opens, and the pad shows the line for `canAuthenticate` 1; set to 1: fingerprint works with the same key | 17 |
| E10 | `ks-fault` for every row of 14.3 through the real adapter: the listed class; temporary faults remove nothing and store nothing, however often they come. `drop-keys`: at the next visit each shortcut is GONE with the "stopped working" text, not "restart your phone" (14.2). `adb shell pm uninstall -k`, then install again: note whether the keys survived; the master password opens the vault either way (architecture.md D-21) | 7, 9, 17 |
| E11 | Recreation: rotate with 3 digits typed (dots and charge kept); rotate with the prompt open (result reaches the new activity); change the language in Settings with the lock delay "Immediately" (still open, same screen); the master-password field keeps its text across rotation and nothing of it is in the saved-state bundle (method of `window-input-artifacts/e5_state.py`) | 7, 9, 17 |
| E12 | TalkBack, Switch Access and a hardware keyboard on the pad with `isSensitiveData`; the non-tool accessibility service of the window lab (`LabA11yService.kt`) sees no key press | 17 |
| E13 | "Device locked": `hold-before-hmac`, `input keyevent KEYCODE_SLEEP`, `release`: TEMPORARY, refunded, nothing removed and nothing opened; after unlocking the phone the PIN works. On Android 9, where the PIN key has no such requirement, the same steps with the right PIN: the PIN counts as right, and the app is still locked when the screen comes back on (I11) | 17; the second part 9 |
| E14 | Storage full: fill `/data` until the charge cannot be written: the "storage is full" text; the master password still opens | 17 |
| E15 | On a variant built only for this check, whose manifest overlay sets `allowBackup="true"`: `adb shell bmgr backupnow <package>`, clear, restore: the vault file comes back and no lock record does. With the shipped manifest nothing at all is backed up, so the check would pass without testing anything [A] | 9, 17 |
| E16 | Small heap (`heapsim.sh PORT 128m 256m`), Java Argon2 fallback forced: one derivation at a time succeeds with `largeHeap`; where it cannot fit, "not enough memory" shows, the app does not crash, nothing is counted [L-old §2] | 7 |
| E17 | Session: away for 31 s: locked; "Immediately"; screen off: locked at once; 90 s in the file picker with "Immediately": still open; 6 minutes in the file picker: still open; picker plus screen off: locked, and the task resumes after unlock; overlay taps dropped (method of `window-input-artifacts/e4_overlay.py`). On Android 7 the app starts and shows the pad: the autofill call of 13.5 is skipped there | 17; the last item 7 |

What the emulators cannot show: invalidation by a new finger on the Android 7 and 9 images, whose sensor never changes its id [L-old §3]; hardware-backed keys; real lockout times; what a manufacturer's backup tool does; API 25–27, 29–36.

### 15.4 Real phones, before release

| ID | Check |
|---|---|
| D1 | A mid-range phone (for example a Samsung A-series and a Xiaomi Redmi), 1,000 entries, 200 of them typed by script in under one second: acceptance A1; HMAC time over 100 runs; `K_pin` is in the TEE; Argon2id time; the time of the record write after a right PIN on the phone's own file system (f2fs where it has one), which nobody has measured [L-sto] |
| D2 | Fingerprint invalidation on a Samsung, a Xiaomi and one phone on Android 11 or older: add a finger, delete all fingers, remove the screen lock. Which exception and which `canAuthenticate` value each one gives (part (a) of E7 cannot be shown on the old emulator images) |
| D3 | A Samsung on Android 9, and any phone on Android 7 or 8: turn fingerprint on and unlock with 1.4.0-alpha07 |
| D4 | An Android 15+ phone with face unlock or Smart Lock: the PIN works right after such an unlock (the unlocked-device requirement) |
| D5 | Whether the lockout-screen failure of 9.3 occurs, and how long real lockouts last |
| D6 | Manufacturers' backup tools [A]. On a Xiaomi (the local backup in Settings, and Mi Mover back to the same phone) and on a Samsung (Smart Switch): set a PIN, type 3 wrong PINs, back up, type the right PIN, restore, and read the counts with `status`. Passes when the lock records are not restored at all, or the counts are not lower than before the right PIN. Also note whether the vault file comes back without its lock directory (F14). If a tool restores `pin.rec`: 18.18 |

### 15.5 Build checks

| ID | Check |
|---|---|
| R-1 | The merged manifest has no `INTERNET` permission (exists today) |
| R-2 | `ALLOW_SOFTWARE_KEYSTORE` is false in release, and the debug receiver class is not in the release APK |
| R-3 | The lock directory resolves under `getNoBackupFilesDir()` |
| R-4 | The app's sources do not mention the context-based biometric API (9.1) |
| R-5 | `FingerprintDialogActivity`, if merged in, is not exported |
| R-6 | The lock package in `:core` contains no logging call at all: no `println`, `System.out`, `System.err` or `java.util.logging`, and `:core` cannot reach `android.util.Log`. In `:app`, lock classes log only from the `debug` and `staging` source sets. The earlier wording, "no PIN, key or master password is passed to a log call", is not something a machine can check [A] |
| R-7 | `HIDE_OVERLAY_WINDOWS` is declared |

### 15.6 Acceptance

- **A1.** PIN unlock takes at most 500 ms at the 95th percentile from the sixth digit to the list, over 800 entries that took at least one second from the first digit to the sixth, on a mid-range phone (D1). For 200 entries typed faster than that, the list is shown at most 1,500 ms after the *first* digit: the one-second age of the charge is then the only extra wait. Scripted taps that are all slower than a second would never meet the charge age, and the measurement would prove less than it seems [A].
- **A2.** After 5 wrong PINs in a row, or 10 that were not forgiven, the slot and its key are gone, whatever kills, reboots and clock changes came in between (T4, E2 to E4).
- **A3.** Every crash point of T14 and T15 leaves every vault on the phone openable with its own master password.
- **A4.** No lock event writes the vault (T17).
- **A5.** The vectors of 4.3 pass on the JVM and on a device.
- **A6.** No Keystore fault removes anything except through a GONE row (T13, E10).
- **A7.** A backup or a plain export cannot start without a master password verified now, or, for a backup, a moment ago in the same flow.
- **A8.** A language change or a rotation never locks and never loses typed input (E11).
- **A9.** A 24-hour wait cannot be finished early through `adb`, the date setting or reboots (T10, E4, E5).
- **A10.** After a finished replacement, at every crash point of T16, the old master password opens the passwords as they were, and the new one can neither open nor delete them.

---

## 16. Answers to ux.md, and what other documents must change

### 16.1 The open questions of ux.md §14 that concern the lock

| ux.md | Question | Answer |
|---|---|---|
| §14.1 | The app's own PIN, or the phone's screen lock? | Settled by the user: the app's own PIN. The phone's lock is not a way in (2.4) |
| §14.4 | PIN length and attempts | Exactly 6 digits. The PIN turns off after 5 wrong PINs in a row, or 10 that were not forgiven between two master-password entries. No waiting times: the "Waiting" row of §5.9 can go (6.2, 6.4) |
| §14.4, §5.9 rec. 1 | Fingerprint after the PIN is turned off | It stays usable. Only the PIN slot is removed (6.5) |
| §14.4, §5.9 rec. 2 | Practice grace | 7 days once the 14-day rhythm is reached; 1, 2 and 4 days for the three asks of the first week; asked at most once a day (8.3) |
| §14.4, §5.9 rec. 3, §5.24 | A waiting time before a new master password | 24 hours, counted while the phone is on (6.9). The new master password is chosen *before* the wait and typed once more to finish (F11). Whether 24 is the right number for a phone that is switched off at night is a question for the user (18.7) |
| §14.4 | May a fingerprint touch approve "Change PIN"? | No. "Change PIN" takes the current PIN typed now, or the master password. A fingerprint approves nothing but opening the app, with one exception: asking for a new master password when no PIN exists (8.2) |
| §14.6 (c) | Folding apostrophe-like characters in the master password | Yes, from the first release; 17 code points, folded after NFC (8.1). The refusal in §5.3 is dropped |
| §14.6 (e) | Backups without retyping when the master password was typed in the last 14 days | Not in version 1. A backup keeps its own key (formats.md §3), and typing the password is what proves, at that moment, that the user can open it. The paper problem is met another way: whenever the master password has just been typed and a backup is due, the app offers the backup on that same entry (F12) |
| §14.6 (f) | Restating crypto.md §11 step 6 as the practice rule | Yes: 8.3 replaces it |
| §14.7 | 30 seconds or "Immediately" as the default lock delay | 30 seconds (10.1) |
| §14.8 | Archives; the data behind the rollback notice | The lock state stays with a vault that is put aside (F17), except after a replacement (F11 step 18). The highest generation seen is kept in `state.rec` (5.6, F16c) |
| §14.9 | The 10-minute grace for system screens | Accepted, with its cost stated (10.3) |
| §5.7, §5.18 | When fingerprint is offered | By 9.4, from Android 7. "Needs Android 9 or newer" is no longer a reason |
| §5.19 | No backup while the master password is forgotten | Kept: none until the new one has started working (F11 step 10) |

§14.6 (a), (b) and (d) belong to the crypto owner. (b), folding white space, was decided in architecture.md §4.6 and is step 2 of 8.1.

### 16.2 Changes to ux.md

1. **§5.9:** the pad's subtitle becomes "Enter your app PIN"; the "Waiting" row goes; add the fresh-pad line "2 tries left…", the "interrupted" line, "Fingerprint did not open the app this time…", the three lines for a fingerprint that cannot be used now, and the fingerprint-only screen (13.2, 13.3). A line about wrong PINs stays until the visit ends. "That's the PIN you just tried." becomes "You already tried that PIN. It was not counted again."
2. **§5.6:** the body text of 7.3; three refusal messages in place of "This PIN is too easy to guess."
3. **§5.7, §5.18:** availability by 9.4; add "Anyone whose fingerprint is saved in this phone's settings can open the app."
4. **§5.10:** one neutral hint after a wrong master password; "Not now" snoozes for 20 hours; the grace of 8.3; the line and the button about a replaced master password; the reasons for a damaged record, a newer record and full storage (13.3). The sheet gains "Open my previous passwords" and "Open my passwords from before …", and "Start with an empty app" shows whenever no shortcut is usable now. The lock screen of a vault that was put aside, and "Old passwords" › "Open", use that vault's own waits (6.8), not "without waits" (§5.21).
5. **§5.24:** the order of F11 (choose first, wait, type it again to finish). Step 2 there allows the fingerprint "if the lock design allows it": only when no PIN exists. The texts of F11 step 6 and 13.4, which give phone-on hours, no promised date, and the advice to open the app in the morning and in the evening. Step 5 there ("Keep the other ways in") changes: the new vault gets a newly chosen PIN and a new fingerprint key (F11 step 19). The "replaced" notice with "It wasn't me", and "Open my passwords from before 7 Oct" with its question (F16b).
6. **§5.1:** "Open my previous passwords" shows that vault's own lock screen, so its PIN or fingerprint can open it as well as its master password; the vaults are swapped only after it has opened (F17).
7. **§5.18:** "Change PIN" takes the current PIN or the master password; "Set a PIN" needs the master password; there is no switch to turn the PIN off.
8. **§5.19:** the master-password step is always shown, except in the offer that follows a master-password entry.
9. **§5.3:** apostrophes are allowed.
10. **§5.4, §5.5:** in a master-password change and a replacement, these two screens keep the screen on (10.1).
11. **§5.11:** the standing banner "You have not typed your master password since …" (13.4 item 6).
12. **§7.6 Help:** the 24 hours and how the countdown moves; "If someone else replaced your master password, your own still opens your passwords as they were: 'Forgot master password?', then 'Open my passwords from before …'"; the sentence about rooted phones (2.3); "Never use 'Clear storage' to fix a problem with your PIN: it deletes your passwords, and the ones from before a replaced master password with them."

### 16.3 Requests to the other owners

**Crypto.**
- Header fields `vault_id` and `vk_id` beside `generation`; exactly one password slot and no biometric slot (5.1).
- `MasterPasswordBytes` in the order of 8.1: NFC, white space, apostrophes, UTF-8. architecture.md §4.6 puts NFC third; it has to come first. The vectors of 4.3 and of architecture.md §4.13 do not change; the Greek line of 4.3 is new.
- One key derivation at a time in the process; `android:largeHeap="true"`; the free-heap check before the Java fallback [V-C2, L-old §2].

**Storage.**
- Never write on a lock event (I1). Report "no vault" only for a missing or empty directory.
- Before unlock: the newest readable generation's `vault_id`, `vk_id` and number; the set of `vk_id`s among the kept generations (files in quarantine and files put aside do not count); the vaults that were put aside, by `vault_id`.
- Open with a map from `vk_id` to VK, and say which generation opened.
- For a master password: try every distinct password slot among the kept generations and say which one opened [V-S3].
- Keep the old-password generations until the new password, typed, has opened the vault at a later start [V-C5, V-S5]. Nothing more: the `keepNewestOf` argument that architecture.md §4.11 gave `retireOldKeys` for the 30 days of the earlier F11 is no longer needed.
- Quarantine, never delete, a new generation that fails its proof.
- **Make a new vault from the open one and put the old one aside** (F11 step 16). This is the call a restore over an existing vault uses, with the open vault's entries as the source and a `vault_id` given by the lock, so that the lock can write `replaced` first. Write, read back, prove; then put aside and make current, as near to one step as the file system allows. A start that finds no current vault offers the vaults that were put aside.
- Put a whole vault aside and make one current again; read the header of a vault that is put aside and open it in place (F17 step 4, F16b). Never prune what was put aside.
- Put the generations newer than a chosen one aside. Still needed, but only for a master-password change that did not finish (F16b); a replacement no longer uses it.
- A restore creates a new `vault_id`, VK and `vk_id`.

**Architecture.** architecture.md was written from this document as it stood before the audit, and says that this document wins for the lock. What changes there:
- `Clock.bootCount()` goes. `PinRecord` and `FingerprintRecord` lose `trouble_boots`, and `PinRecord` loses `udr`. `StateRecord` gains `mp_change`, and `replaced` has the fields of 5.6.
- `LockScreen.OlderPassword` carries "the change did not finish" in place of `Replaced`. There is a fingerprint-only screen and the screen of a vault that was put aside (13.2).
- `FingerprintKey.availability()` is asked once per visit, off the main thread (13.1). `PinKey` or `KeystoreAccess` can make the canary again (14.2).
- `VaultPort`: the storage items above. `openAside` and `deleteAside` run under that vault's own guard (6.8).
- §4.6: the order of 8.1. §4.7 and §4.11: `.aside` generations come only from an unfinished change.
- D-21 (a reinstall that keeps the data) is answered in F15b and 14.2, and checked in E10.

**Build.**
- `androidx.biometric:biometric:1.4.0-alpha07` in the version catalog.
- Manifest: `HIDE_OVERLAY_WINDOWS`; `android:launchMode="singleTask"`; `android:largeHeap="true"`. Consider `android:hasFragileUserData="true"` (API 29+), which makes Android offer to keep the data on uninstall [J-R §7].
- `BuildConfig.ALLOW_SOFTWARE_KEYSTORE`, true in `debug` and `staging` only (architecture.md §7.2); the test receiver in those two source sets; a manifest overlay with `allowBackup="true"` for check E15 and nothing else, which also removes `android:fullBackupContent` and `android:dataExtractionRules` (`tools:remove`), because the shipped rules exclude every domain and E15 would then back up nothing; checks R-2 to R-7.
- When `docs/lab/window-input-artifacts/VaultSecurity.kt` is copied into the app, it gets the API 26 gate of 13.5.

**i18n.** New glossary terms: "app PIN"; "tries left" (term 58); the banner and notice texts of 13.4; the status lines and reasons of 13.3 that are new; the texts of F11 step 6 and of F16b. Term 59 ("Try again in N seconds") is used only for the master-password wait.

---

## 17. Decisions

| # | Decision | Choice | Reason | Rejected |
|---|---|---|---|---|
| 1 | Base design | Design 3 | Highest combined score (20.0 against 17.0 and 16.0); first for recovery, second in the other two lenses | Design 1 (most machinery, PIN wears out), Design 2 (instant PIN-only takeover, fingerprint blocked after the limit) |
| 2 | How the PIN protects the key | A Keystore HMAC key that never leaves secure hardware; its output unwraps VK. No stretching, no usage limit | Copied files cannot test a PIN (requirement 2); fastest unlock (requirement 4); fewest parts [J-R, J-U] | Argon2id alone (offline guessing in hours); a screen gate; Argon2id plus HMAC [D3]; usage-limited or single-use keys [D3, D1]; StrongBox |
| 3 | Limits | 5 wrong in a row, and 10 that were not forgiven between two master-password entries. A right PIN on the lock screen forgives the wrong PINs of the last 60 s of its visit, at most 2. No waits | 5 meets requirement 3 for a thief. The second count bounds someone near the owner [J-S 3-3] without tripping an honest owner, whose own slips are forgiven [J-R D1-1, J-U; appendix D]. The window and the cap keep a staged hand-over from hiding more than two guesses [A] | Only "5 in a row" (unlimited tries over time for an insider); Design 1's budget (37 % of heavy users lose the PIN in two weeks); forgiving, and leaving out of the notice, every wrong PIN of the visit (the first version of this document); growing waits |
| 4 | When a try is counted | A flag written at the first digit; the check only when that write is 1,000 ms old; counted at start if left behind | The only order that survived the model check with power cuts (6.7) | Check then count (Bitwarden, Proton); count at submit [D1]; 500 ms [D2, J-U] (the security judge asked for 1 s, and six digits take longer than that) |
| 5 | PIN length | Exactly 6 digits, submitted by the sixth | NIST, CDD and audit advice; 100 times the work if a limit is ever bypassed; no OK key | 4 digits; variable length |
| 6 | Blocklist | Design 3's nine rules: 12.63 % refused; all dates and years | Five guesses are the whole defence against people who know the owner's dates; 99 % of the 2,000 most popular PINs are refused [J-S §2.6] | Design 2's list (4.45 %; allows 246810, 135790, 123457) as the usability judge preferred; a published top-2,000 list (no licence) |
| 7 | Fingerprint and the PIN limit | Fingerprint stays usable after the limit; a fingerprint unlock resets no count | Blocking it stops no attacker and strands an owner who forgot the master password [J-R D2-1]; resetting would refill an insider's tries [J-S §2.3] | Design 2's block; a reset of `row` on fingerprint, which the recovery judge left to the security judge |
| 8 | Fingerprint library | `androidx.biometric` 1.4.0-alpha07, classic constructor only | 1.1.0 cannot reach the sensor on Android 7 and 8 and cannot be fixed; the alpha worked on Android 7, 9 and 17 in the labs and gives one availability rule (9.1) | 1.1.0 (all three designs, written before the lab reports); the alpha's new context-based API (opens an activity outside our task) |
| 9 | Practice | Days 1, 3, 7, then every 14; grace = min(interval, 7 days); asked once a day; then required, with "I can't remember it" | Requirement 7; ux.md's 7 days in the steady rhythm; the early repetitions are kept [I] | One skip [D1]; 3 "Later"s per lock session [D3]; 3 days [D2, J-U]; skippable for ever |
| 10 | Proof for changes | Stricter: nothing. Change PIN: the current PIN or the master password. Add a way in: the master password in the same flow. No time window. A fingerprint approves nothing, except the request of decision 14 when no PIN exists | A PIN-holder gains nothing by changing a PIN they already hold, and changing it must be easy after an alert [J-U, ux.md]; adding a finger or a PIN is how a visitor would keep access [J-S 3-4] | The master password for a PIN change too [J-S]; the PIN for adding fingerprint [D2, D3]; 5-minute and 120-second windows [D1, D3] |
| 11 | Backups and exports | The master password typed now; a backup may use the entry made a moment ago | The backup opens with a password known today (I3); a seen PIN cannot export [J-S]; the paper is needed once for practice and backup together | Skipping it for 14 days after an entry (ux.md §5.19): a backup made by someone who has forgotten the password is worthless and looks safe |
| 12 | Wrong master passwords | 5 free, then 30 s each; never more; never for a file from outside; a vault that was put aside has the same guard of its own | At most about 2,880 scripted guesses a day, against a password that must have 10^10 guesses of strength; a confused owner is never made to wait 5 minutes [J-R D3-4] | No wait [D1]; 30 s per 5 tries [D2] (13,000 a day); 5 minutes after 10 [D3, J-S] |
| 13 | Master-password bytes | NFC, the white-space fold of architecture.md, 17 apostrophe-like characters folded, UTF-8 | The same password must work on every keyboard [J-R D3-2]. NFC first, so that every equivalent spelling gives the same key and the function is stable when applied twice [A] | A hint only [D2]; refusing apostrophes (ux.md §5.3); folding before NFC (the first version of this document) |
| 14 | Forgotten master password | The PIN typed now, or a fingerprint when no PIN exists; the new password chosen at once; it works after 24 hours counted while the phone is on; a banner with a free "Cancel"; finishing makes a new vault and puts the old one aside, where only the old master password opens or deletes it | A short wait matters because no backup is possible meanwhile [ux.md, J-R, J-U]. "Every enabled shortcut" is bypassed by switching one off first, which must stay free, and it shuts out the fingerprint user who has forgotten the PIN too. The owner cannot be locked out through the app by someone else's replacement, at any crash point and after any number of replacements [A] | 72 hours and every shortcut at both ends [D1, J-S]; instant with the PIN [D2]; choosing the password only after the wait [D3, ux.md §5.24]; a fingerprint alone while a PIN exists; a new key in the same vault with a 30-day record of the old one (the first version of this document: a kill, a second replacement, or "Delete" with the new password defeated it) |
| 15 | Measuring a wait | `elapsedRealtime()` only | `BOOT_COUNT` can be rewritten through `adb` [J-S 3-1]; the date can be set | Crediting uptime when the boot count changes [D1, D3]; the wall clock |
| 16 | Where lock state lives | Three framed records per vault in `noBackupFilesDir/lock/<vault_id>/` | Never in a backup or transfer that Android makes [V-P6]; one damaged file costs one way in [J-R]; the PIN slot and its count change in one atomic write | One record for everything [D2]; slots and counters in separate files [D3]; slots in the vault header (crypto.md) |
| 17 | Keystore errors | GONE only on the platform's own word; null believed only when the canary loads, and the canary is made again when it is missing; everything else TEMPORARY; no count of failures | A passing error must never cost a shortcut [J-R D2-3]. A phone that has lost every key must not say "restart your phone" for ever [A]. A dead key reported as "temporary" does not stay hidden: the message says where to set the shortcut up again [J-R D3-6] | Null means gone everywhere [D2]; removal after a later boot [D3]; `isTransientFailure()`; an offer after trouble on 2 boots (the first version of this document) |
| 18 | Deleting keys | At once when a slot is removed; only by the start-up sweep when a slot is replaced | A lost record write must never leave a record pointing at a deleted key [J-R D3-3] | Deleting the old key right after writing the new record [D1, D2, D3] |
| 19 | Unlocked-device requirement | On the PIN key from API 35; never on the fingerprint key. Open again with the security owner (18.2) | A phone rooted later cannot use the PIN key while locked [J-S 3-5]; the error it adds is already TEMPORARY. Before Android 15 it had bugs [F §1.4]. On the fingerprint key it buys nothing [L-bio case 7] | Not at all [D3]; from API 28 |
| 20 | Order of a master-password change | A mark in `state.rec`, then the PIN's wrap for the new key, then the vault, then the fingerprint; never more than two wraps in a slot (5.4); quarantine after a failed proof | An interrupted change always opens with the PIN and with one of the two passwords [J-R §6.6]. The old password opens the copy from before only when the mark says that the change did not finish, not for a day after every change [A] | Re-arming the PIN after the vault write [D1, D3]; offering the copy from before whenever old generations are still on disk |
| 21 | A vault that is put aside | It keeps its lock records and keys; it is made current again by opening it with its own PIN, fingerprint or master password; deleting needs its own master password. A vault put aside by a replacement keeps only its master password | Restoring an old backup from the lock screen must not destroy a PIN that still opens newer passwords (I9). After a replacement, the PIN is what the replacer knew | Deleting the lock state with "Start over" [D2, D3] |
| 22 | Session | 30 s after leaving; screen-off at once; 5 minutes without a touch while the app is in front; 10 minutes of grace for the app's own pickers | ux.md §5.18; [V-P3] | "Immediately" as default; 1 minute, a 15-minute option and a switch for the screen-off lock [D1]; locking on the phone's last authentication time, which another app's fingerprint prompt also moves [A] |
| 23 | Recreation | Nothing of the lock lives in the activity; locking follows the process lifecycle | i18n.md §1.4.4 rule 3 | Locking in `onStop`; `android:configChanges` |
| 24 | The phone's screen lock | Not a way in | The user's choice; 2.4 | A third device slot on API 30+ |
| 25 | A phone without secure hardware for keys | Master password only | Requirement 2 | A software-backed PIN |
| 26 | After a reboot | No master password | Nothing is gained; the phone asks for its own lock first | Bitwarden-style "master password on restart" |
| 27 | When the list is shown after a right PIN | As soon as the vault is open; the record is written right after, in the background, under the mutex | The owner does not wait for a directory `fsync` that nobody has timed on a phone's file system. A lost write costs the owner one counted try (6.10) [A] | Showing the list only when the write has returned |
| 28 | PIN prompts inside the open app | Counted, never forgiven, always reported | Otherwise "Change PIN" in an open app is a place to guess unseen [A] | Treating them as part of a lock-screen visit |
| 29 | A result that comes after its visit ended | The counts are updated; nothing opens. A visit ends at `ON_STOP` or at a screen-off lock, whichever comes first (6.3; decided on 2026-10-08) | Otherwise a PIN typed just before the power button opens the vault with the screen off (I11) [A]. With `ON_STOP` alone it still did, for the 700 ms between the screen-off and `ON_STOP` | Opening whenever the check succeeds; ending a visit at `ON_STOP` only |
| 30 | The PIN limit when a phone holds several vaults | Per vault, and stated as a limit (2.3) | One counter for the whole phone needs rules for what a right PIN or master password of one vault resets in another, which nobody designed. Taking the pad away from vaults that were put aside would undo decision 21 for an owner without a fingerprint | A shared counter; no pad on a vault that was put aside (the two ways to close it that the audit names) |

---

## 18. Open questions

1. **Speed on real phones.** The time of one HMAC in a TEE is not published [F §10], and Argon2id has not been timed on a phone. Neither has the record write on f2fs. Acceptance A1 must be measured (D1).
2. **The unlocked-device requirement on the PIN key. For the security owner.** Two things are open. Does the PIN key work right after a face or Smart Lock unlock on manufacturers' Android 15+, and does it survive removal of the screen lock (D4, E7, E13)? And does the flag earn its place at all? It defends only against a phone rooted later, which is the reason 1.4 gives for leaving out StrongBox and usage counts; the security judge asked for it [J-S 3-5]; the audit lists it among the parts that could go. Until the security owner decides, it stays. Dropping it is one line; a key made earlier keeps the flag until its PIN is set again.
3. **`CHARGE_AGE` = 1 s is a judgement.** No source says how long phone storage holds a write after `fsync` when mounted `nobarrier` [V-S8].
4. **TalkBack with sensitive pad keys** (E12). The fallback is defined in 13.5.
5. **The alpha library on real phones:** a Samsung on Android 9 and a phone on Android 7 or 8 (D3). Move to the stable 1.4.0 when it exists.
6. **Is the blocklist too strict for this user?** Watch the first people who set a PIN. The first rule to relax is rule 9 (7.2).
7. **Is "24 hours with the phone switched on" the right number? For the user.** On a phone that stays on it is 24 hours. On a phone that is switched off every night it is about a day and a half if the app is opened in the morning and in the evening, about 3 days if it is opened once in the evening, and about 3 weeks if it is opened once, shortly after switching on (6.9, appendix F). No backup can be made in that time. The choices: keep 24 and rely on the advice in the banner; count fewer hours, for example 12; or let the app look at the clock by itself while a request is pending. That last one means a silent alarm every hour or two: it needs no permission, but it adds a background receiver to an app that has none, and after a restart it does not run until the app has been opened once. Not checked: whether an app can read the kernel's boot id on Android 7 to 17, which would let the helper recognise a restart without trusting anything that can be forged.
8. **Crypto owner:** accept the order and the 17 code points of 8.1 before the first release. Afterwards `MasterPasswordBytes` can never change. (White space is decided: architecture.md §4.6.)
9. **Storage owner:** accept the requests of 16.3, in particular "make a new vault from the open one and put the old one aside" with a `vault_id` given by the lock (F11 step 16).
10. **Does the lockout-screen failure of 9.3 happen on real phones,** and how long do real fingerprint lockouts last (D5)?
11. **Should the 10-minute grace be shorter** (10.3)? Unfinished file tasks survive a lock, so it can be lowered without breaking anything.
12. **A switch to turn the PIN off** was left out. Add it if users ask. It would have to need the master password: F11 step 3 relies on a PIN not being removable quietly.
13. **How many phones in use keep Keystore keys in software** (F18)? Android has required hardware-backed keys on phones with a lock screen since 7.0, so it should be rare [F §1.1].
14. **`android:hasFragileUserData`.** architecture.md has decided to set it (D-21). Does the "keep app data" question on uninstall help this user or confuse them, and does Android keep the Keystore keys then (E10)?
15. **Native-speaker review** of the limit, banner and "older master password" texts in Uzbek and Russian (ux.md §14.3). The banner is the owner's defence in F11; it must be understood at a glance.
16. **A notification during the wait for a new master password? For the user.** Today the warning is a banner inside the app, so an owner who does not open the app for a day is not warned (2.3). An Android notification would reach them without that. It needs the notification permission on Android 13 and newer, which the app does not have today (the build check R-9 of architecture.md would have to allow it); it needs no network; and its text must give nothing away to a bystander.
17. **One PIN counter for the whole phone?** Today every vault that was put aside with a working PIN adds five guesses (2.3, decision 30). It takes two vaults with PINs on one phone, which happens after a restore over an existing vault.
18. **If a manufacturer's tool can restore `pin.rec`** (D6): keep a second count in the Keystore, where no file restore reaches. One marker alias per wrong PIN, deleted on a right PIN or a master password; the count that is used is the larger of the two. Not designed further until D6 says it is needed.
19. **A new PIN or fingerprint slot whose record write reports a failure after its rename (F6 step 8, F9 step 9). Decided on 2026-10-08.** The screen used to say "Couldn't save the new PIN. Your old PIN still works." In that process it held: the lock worked from the old record (6.3), and its next write of `pin.rec` put the old record back. But if the process ended before that write and the rename had reached the disk, the next start read the new record: the new PIN was the one that worked, and the old one counted as wrong. It takes a failed directory `fsync`, which is rare, and the master password opens the app either way. The choices were: accept it; write the old record once more right after the failure, best effort; or read the record back and go on with what is on disk, with a sentence that promises nothing the app cannot know. The owner chose the third, and F6 step 8 and F9 step 9 now say so. Found by the review of build plan 3.13.
20. **The backup's read-back check against 5.8 (F12 step 4, architecture.md §4.12). Decided on 2026-10-08 (build-plan §12, decision 5): the first choice below. F12 step 4 says it; build plan 5.2 built it (`BackupViewModel`).** Step 4 opened the read-back copy of a backup "with the typed password", after the file picker. The picker makes the app stop being visible, and 5.8 drops every proof then, so the typed bytes are gone when the file comes back. The choices: seal the backup and open it once with the proof's bytes before the picker opens, and afterwards compare the read-back with what was sealed (SHA-256), which leaves 5.8 as it is and is what build plan 5.2 is asked to do until this is decided; keep the backup's own key, not the password, across the picker for the second open; or let this one proof outlive the picker. Found by build plan 3.14 and its review.
21. **A vault that was put aside and cannot be read (F17 step 4, 13.1). For the design owner.** On that vault's own lock screen a right PIN, fingerprint or master password can find that no copy of it opens. The app then shows "Your passwords can't be opened" (13.1 row 2), which is the current vault's screen: the current vault is fine, and "Restore from a backup file" there would put it aside. Nothing is deleted. The choices: stay on that vault's lock screen with a line that says its copies cannot be opened now, which needs wording for 13.3 and ux.md; or return to the current vault's lock screen, as Back does. Found by the review of build plan 3.14. **Decided on 2026-10-08 (build-plan §12, decision 6): the first choice.** That vault's screen stays and says "These passwords can't be opened. Their files on this phone are damaged." with Back only (F17 step 4, 13.2, 13.3); "Restore from a backup file" is never offered there. Built by `w5-carry` (`LockScreen.CannotOpen`, architecture.md §3.5).

---

## Appendix A. Reference blocklist

Pure Kotlin for `:core`. The same logic, run as Java and as Python over all 10^6 PINs, gives 126,337 refusals with the per-rule counts of 7.2, and agrees PIN for PIN with Design 3's reference script [X]. `rule()` returns the number of the first rule that refuses a PIN, so that test T1 can check each count of 7.2; `reason()` gives the message group. This Kotlin was compiled (Kotlin 2.4.20) and run over all 10^6 PINs for this revision, with the nine counts of 7.2 and the hash below as the result [X]. Compact oracle for test T1: write "1" for a refused PIN and "0" for an allowed one, for 000000 to 999999 in order; the SHA-256 of those 10^6 characters is `c6aa9b8f6e1124aae56ed5fcb4b495bb2c0cae4a792ec9ce6c5b22b64848241b`.

```kotlin
object PinBlocklist {
    enum class Reason { REPEAT, PATTERN, SEQUENCE, KEYPAD, STEPS, DATE }

    // Row and column of every key on the pad:   1 2 3 / 4 5 6 / 7 8 9 / . 0 .      (index = digit)
    private val row = intArrayOf(3, 0, 0, 0, 1, 1, 1, 2, 2, 2)
    private val col = intArrayOf(1, 0, 1, 2, 0, 1, 2, 0, 1, 2)
    private val lines: Set<String> =
        listOf("123", "456", "789", "147", "258", "369", "580", "159", "357").flatMap { listOf(it, it.reversed()) }.toSet()
    private val listed = setOf("246810", "108642", "135790", "097531")

    /** [pin] is exactly six ASCII digits. Returns 0 when the PIN is allowed, otherwise the number (1 to 9) of the first rule of 7.2 that refuses it. */
    fun rule(pin: String): Int {
        require(pin.length == 6 && pin.all { it in '0'..'9' })
        val d = IntArray(6) { pin[it] - '0' }
        val a = pin.substring(0, 3)
        val b = pin.substring(3)
        // 1 repeat
        if ((0..9).any { digit -> d.count { it == digit } >= 4 }) return 1
        // 2 pattern
        if (pin == pin.substring(0, 2).repeat(3) || a == b) return 2
        if (d[0] == d[1] && d[2] == d[3] && d[4] == d[5]) return 2
        if (d[0] == d[1] && d[1] == d[2] && d[3] == d[4] && d[4] == d[5]) return 2
        if (pin == pin.reversed()) return 2
        // 3 sequence
        val step = (d[1] - d[0]).mod(10)
        if ((0 until 5).all { (d[it + 1] - d[it]).mod(10) == step }) return 3
        if (isRun3(d, 0) && isRun3(d, 3)) return 3
        if (pin in listed) return 3
        // 4 keypad lines
        if (a in lines && b in lines) return 4
        // 5 steps
        if (d[2] - d[0] == d[4] - d[2] && d[3] - d[1] == d[5] - d[3]) return 5
        // 6 run of four
        for (i in 0..2) {
            val t = d[i + 1] - d[i]
            if ((t == 1 || t == -1) && d[i + 2] - d[i + 1] == t && d[i + 3] - d[i + 2] == t) return 6
        }
        // 7 keypad path
        if (d.distinct().size == 6 && (0 until 5).all { neighbours(d[it], d[it + 1]) }) return 7
        // 8 date
        val x = d[0] * 10 + d[1]
        val y = d[2] * 10 + d[3]
        val z = d[4] * 10 + d[5]
        if (dayMonth(x, y) || dayMonth(y, x) || dayMonth(z, y)) return 8
        // 9 year
        if (x * 100 + y in 1940..2039 || y * 100 + z in 1940..2039) return 9
        return 0
    }

    /** The message group of 7.3, or null when the PIN is allowed. */
    fun reason(pin: String): Reason? = when (rule(pin)) {
        0 -> null
        1 -> Reason.REPEAT
        2 -> Reason.PATTERN
        3, 6 -> Reason.SEQUENCE
        4, 7 -> Reason.KEYPAD
        5 -> Reason.STEPS
        else -> Reason.DATE
    }

    private fun isRun3(d: IntArray, i: Int): Boolean {
        val t = (d[i + 1] - d[i]).mod(10)
        return (t == 1 || t == 9) && (d[i + 2] - d[i + 1]).mod(10) == t
    }
    private fun neighbours(p: Int, q: Int) =
        p != q && kotlin.math.abs(row[p] - row[q]) <= 1 && kotlin.math.abs(col[p] - col[q]) <= 1
    private fun dayMonth(day: Int, month: Int) = day in 1..31 && month in 1..12
}
```

Messages: `REPEAT`, `PATTERN`, `STEPS` → "…repeats digits or follows a pattern"; `SEQUENCE`, `KEYPAD` → "…a sequence or a shape on the keypad"; `DATE` → "…a date or a year" (7.3).

## Appendix B. The model check of the counter

Python 3, run today [X]. The arguments are the two limits, the settle period, the depth, how many wrong PINs a right PIN may forgive, and the forgiving window in settle periods. `python3 guard_model.py 2 3 1 34 2 3` prints 2 and 3. A seventh argument selects a broken variant, which prints a violation with its witness: `noage` and `countafter` break the order of charge and check; `nowindow` forgives wrong PINs of any age; `stale` keeps the visit's list after the visit has ended. Port it as test T5.

```python
"""Record on disk: (row, total, charged, off). Each write replaces the record.
   kill      : every handed write survives, memory is lost
   power cut : only writes handed at least SETTLE ago survive (a drive cache that fsync does not flush)
   Anyone may type: 'wrong' = a check with a wrong PIN, 'right' = with the right PIN. No master password.
   A right PIN may forgive at most FMAX wrong checks of the same visit that are at most WINDOW old (the truth);
   the app forgives what its own list in memory says. The right PIN's write may be lost like any other.
   P1  wrong checks since the last right PIN                 <= ROW
   P2  wrong checks that no right PIN was allowed to forgive <= TOT"""
import sys
from collections import deque
ROW, TOT, SETTLE, MAXDEPTH, FMAX, WINDOW = (int(x) for x in sys.argv[1:7])
VARIANT = sys.argv[7] if len(sys.argv) > 7 else 'aged'          # aged (the spec) | noage | countafter | nowindow | stale
FLOOR = -max(SETTLE, WINDOW) - 1

def explore():
    # state: t, W=((t_handed,row,total,charged,off),...), mem, consec, unforg, vtrue
    # vw (in mem) and vtrue are the times of this visit's last FMAX wrong checks: what the app remembers, and the truth
    start = (0, ((-99, 0, 0, False, False),), ('idle', ()), 0, 0, ())
    q, seen, w1, w2 = deque([(start, ())]), set(), (0, ()), (0, ())
    while q:
        (t, W, mem, consec, unforg, vtrue), path = q.popleft()
        if consec > w1[0]: w1 = (consec, path)
        if unforg > w2[0]: w2 = (unforg, path)
        if len(path) >= MAXDEPTH: continue
        _, row, total, charged, off = W[-1]
        nxt = []
        wr = lambda rec: W + ((t,) + rec,)
        limit = lambda r, tt: r >= ROW or tt >= TOT
        fresh = lambda times: sum(1 for x in times if t - x <= WINDOW)        # at most FMAX entries are kept
        app_fresh = (lambda times: len(times)) if VARIANT == 'nowindow' else fresh    # what the app forgives
        add = lambda times: (times + (t,))[-FMAX:] if FMAX else ()
        if mem is not None:
            kind = mem[0]
            if kind == 'idle' and not off and not limit(row, total):
                vw = mem[1]
                if VARIANT == 'countafter':
                    nxt.append(('submit wrong', (t, W, ('resW', add(vw)), consec + 1, unforg + 1, add(vtrue))))
                    nxt.append(('submit right', (t, W, ('resR', app_fresh(vw)), 0, unforg - fresh(vtrue), ())))
                else:
                    nxt.append(('digit1: charge', (t, wr((row, total, True, False)), ('charged', t, vw), consec, unforg, vtrue)))
            if kind == 'charged':
                tc, vw = mem[1], mem[2]
                if VARIANT == 'noage' or t - tc >= SETTLE:
                    nxt.append(('digit6 wrong', (t, W, ('resW', add(vw)), consec + 1, unforg + 1, add(vtrue))))
                    nxt.append(('digit6 right', (t, W, ('resR', app_fresh(vw)), 0, unforg - fresh(vtrue), ())))
                nxt.append(('given up: refund', (t, wr((row, total, False, False)), ('idle', vw), consec, unforg, vtrue)))
                nxt.append(('visit ends: refund', (t, wr((row, total, False, False)), ('idle', vw if VARIANT == 'stale' else ()), consec, unforg, ())))
            if kind == 'resW':
                r2, t2 = row + 1, total + 1
                nxt.append(('write wrong', (t, wr((r2, t2, False, limit(r2, t2))), ('idle', mem[1]), consec, unforg, vtrue)))
            if kind == 'resR':      # the list is already open; this write runs in the background
                nxt.append(('write right', (t, wr((0, max(0, total - mem[1]), False, False)), ('idle', ()), consec, unforg, vtrue)))
            if kind == 'idle' and mem[1]:
                nxt.append(('visit ends', (t, W, ('idle', mem[1] if VARIANT == 'stale' else ()), consec, unforg, ())))
            nxt.append(('kill', (t, W, None, consec, unforg, ())))
            durable = [w for w in W if t - w[0] >= SETTLE] or [W[0]]
            nxt.append(('power cut', (t, (durable[-1],), None, consec, unforg, ())))
        elif charged:
            r2, t2 = row + 1, total + 1
            nxt.append(('start: charge counted', (t, wr((r2, t2, False, off or limit(r2, t2))), ('idle', ()), consec, unforg, ())))
        else:
            nxt.append(('start', (t, W, ('idle', ()), consec, unforg, ())))
        nxt.append(('wait', (t + 1, W, mem, consec, unforg, vtrue)))
        for name, (tt, WW, m, c, u, v) in nxt:
            keep = [i for i, w in enumerate(WW) if tt - w[0] >= SETTLE]
            if keep: WW = WW[keep[-1]:]
            norm = lambda x: max(x - tt, FLOOR)
            nt = lambda times: tuple(norm(x) for x in times)
            if m is None: mk = None
            elif m[0] == 'charged': mk = ('charged', norm(m[1]), nt(m[2]))
            elif m[0] == 'resR': mk = m
            else: mk = (m[0], nt(m[1]))
            key = (tuple((norm(w[0]),) + w[1:] for w in WW), mk, c, u, nt(v))
            if key not in seen:
                seen.add(key); q.append(((tt, WW, m, c, u, v), path + (name,)))
    return w1, w2

w1, w2 = explore()
print('wrong in a row:', w1[0], 'OK' if w1[0] <= ROW else 'VIOLATED: ' + ' > '.join(w1[1]))
print('not forgiven  :', w2[0], 'OK' if w2[0] <= TOT else 'VIOLATED: ' + ' > '.join(w2[1]))
```

| Run | Wrong in a row | Not forgiven |
|---|---|---|
| `2 3 1 34 2 3` (this design) | 2 | 3 |
| `3 5 1 34 2 3` | 3 | 5 |
| `3 4 2 30 2 4` | 3 | 4 |
| `2 3 1 22 2 3 noage` (checked without ageing the charge) | 6: a power cut loses the fresh charge | 6 |
| `2 3 1 22 2 3 countafter` (checked before counting) | 9: a kill before the count is written | 9 |
| `2 3 1 30 2 3 nowindow` (forgives wrong PINs older than the window) | 2 | 4: an old wrong PIN is wiped by a later right one |
| `2 3 1 30 2 3 stale` (the list outlives the visit) | 2 | 4: a wrong PIN of an earlier visit is wiped |

## Appendix C. The vectors of 4.3

Python 3 with `cryptography` 41.0.7; a JDK 25 program using `Mac` "HmacSHA256", `Cipher` "AES/GCM/NoPadding" with `updateAAD`, and Bouncy Castle 1.86 for Argon2id gave the same bytes [X]. `mp_bytes` is the function of 8.1 in its final order; a JDK 25 program with `java.text.Normalizer` gives the same bytes for every spelling of 4.3 and for the white-space vector of architecture.md §4.13 [X].

```python
import hmac, hashlib, uuid, unicodedata
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
vault, vk, slot = (uuid.UUID('00000000-0000-4000-8000-00000000000%d' % i).bytes for i in (1, 2, 3))
kpin, VK, nonce = bytes([0x42]) * 32, bytes(range(0xa0, 0xc0)), bytes(range(0x10, 0x1c))
msg = b'pvlock-v1:pin-kek' + b'\x00' + vault + slot + b'582913'
kek = hmac.new(kpin, msg, hashlib.sha256).digest()
aad = b'pvlock-v1:pin-wrap' + b'\x00' + vault + vk + slot
print(kek.hex(), AESGCM(kek).encrypt(nonce, VK, aad).hex())

APOS = [0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07]
WS = [*range(0x09, 0x0E), 0x20, 0x85, 0xA0, 0x1680, *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
def mp_bytes(s):
    s = unicodedata.normalize('NFC', s)                                                      # 1 NFC
    s = ' '.join(w for w in ''.join(' ' if ord(c) in WS else c for c in s).split(' ') if w)   # 2 white space
    s = ''.join("'" if ord(c) in APOS else c for c in s)                                     # 3 apostrophes
    return s.encode('utf-8')                                                                 # 4 UTF-8
print(mp_bytes('oʻgʻlim-Toʻra').hex(), mp_bytes('oʹg`lim-To´ra').hex(), mp_bytes('Köz-Ўzbek').hex())
```

## Appendix D. How often an honest owner loses the PIN

Monte Carlo, 20,000 runs per cell, the same shape as the recovery judge's simulation [J-R appendix A]. The owner unlocks with the PIN `U` times a day and mistypes each entry with probability `p`. After a typo they give up the visit with probability `quit` (they use the fingerprint, or put the phone down); otherwise they retype at once, which is well inside the 60-second window. The master password is typed every `days` days [X].

```python
import random
FORGIVE_MAX = 2
def run(U, p, quit, days, design, trials=20000, seed=1):
    rnd, off = random.Random(seed), 0
    for _ in range(trials):
        row = total = 0; dead = False
        for _ in range(days * U):
            visit = 0
            while True:
                if rnd.random() < p:
                    row += 1; total += 1; visit += 1
                    if row >= 5 or total >= 10: dead = True; break
                    if rnd.random() < quit: break            # given up: stays counted
                else:
                    if design == 'final': total -= min(FORGIVE_MAX, visit)   # retyped at once: within the 60 s window
                    row = 0; break
            if dead: break
        off += dead
    return off / trials
```

Share of periods in which the PIN turns off before the next master-password entry:

| Days | Unlocks a day | Typos | Given up | Every wrong PIN counts until the master password (Design 1) | This design |
|---|---|---|---|---|---|
| 14 | 8 | 3.1 % | 0 % | 0.5 % | 0.0 % |
| 14 | 10 | 5 % | 0 % | 21.5 % | 0.0 % |
| 14 | 20 | 3 % | 0 % | 37.1 % | 0.0 % |
| 14 | 20 | 5 % | 20 % | 91.4 % | 0.4 % |
| 21 | 20 | 3 % | 20 % | 82.7 % | 0.1 % |
| 21 | 20 | 5 % | 20 % | 99.8 % | 4.4 % |

And for an owner who never types the master password at all (`days` is then the whole time), at 8 unlocks a day and 3.1 % typos [A, re-run]:

| Given up | PIN off within 90 days | 180 days | 365 days |
|---|---|---|---|
| 20 % | 4.8 % | 58.8 % | 99.5 % |
| 10 % | 0.1 % | 6.3 % | 63.9 % |
| 0 % | 0.0 % | 0.0 % | 0.0 % |

The typo rates are assumptions; 3.1 % is a field measurement for practised phone PINs [J-U §2]. The point is the shape: forgiving the owner's own slips keeps the bound of 10 for someone who does not know the PIN and takes it away from the owner, as long as the owner types the master password now and then. The second table is why 13.4 offers a new master password to a user who does not.

## Appendix E. What was read and run for this document

- **Read in full today:** `lock-work/facts.md`, `design-1.md`, `design-2.md`, `design-3.md`, `judge-security.md`, `judge-recovery.md`, `judge-usability.md`; `docs/research/VERIFICATION.md`; `docs/lab/biometric.md`, `old-android.md`, `window-input.md`, `storage-durability.md`.
- **Read in part:** `ux.md` §0–§5.10, §5.18–§5.20, §5.24, §7–§15; `i18n.md` §1.4, §3 C, §5–§6; `formats.md` §1–§3; `search.md` §0–§1; `crypto.md` §1–§2, §6–§12; `storage.md` §5; `platform.md` §3.3–§3.8; the project's Gradle files, manifest, backup rules and `MainActivity.kt`.
- **Checked in sources [X]:** androidx.biometric 1.4.0-alpha07 (`BiometricPrompt.java` constructors and `init`, `BiometricManager.canAuthenticateCompat`, `DeviceUtils.shouldUseFingerprintForCrypto`, `AuthenticationHandlerFingerprintManager.kt`, the AAR manifest); the API levels of every platform call named here, in `android-37.0/data/api-versions.xml` (among them `setUnlockedDeviceRequired` 28, `setUserAuthenticationParameters` 30, `KeyInfo.getSecurityLevel` 31, `Os.rename`/`fsync`/`open` 21, `setHideOverlayWindows` 31, `setAccessibilityDataSensitive` 34, `View.setImportantForAutofill` 26, `Math.floorMod` 24).
- **Run today [X]:** the blocklist in Python and Java against Design 3's reference; blocklist variants against the published lists; the vectors in Python and Java; the model check; the simulation.
- **For the revision after the audit.** Read: the audit's 21 findings; `architecture.md` §3.3–§3.4, §4.6–§4.13, §7.2, §8 and its decisions D-19 and D-21; the passages of the three judgements and of the lab reports that the findings cite; `ux.md` §2, §5.4, §5.5, §5.10 and §5.24. Checked in sources: `BiometricManager.java` (the meaning of the last authentication time), `KeyStoreCryptoOperationUtils.java`, `AndroidKeyStoreSpi.java` and `AndroidKeyStoreProvider.java` in the android-37.0 sources; `BiometricManager.java` and `CryptoObjectUtils.java` of androidx.biometric 1.4.0-alpha07; line 51 of the lab's `VaultSecurity.kt`. Run: what the section "Audit" lists.
- **Not done:** no emulator was started and no phone was used. Everything about devices comes from the lab reports.

## Appendix F. How long 24 credited hours take

Python 3, run for the revision [X]. It applies the helper of 6.9 to a phone that is switched off every night.

```python
import random
H = 3_600_000
def observe(credited, seen, now):                                   # the helper of 6.9
    return credited + (now - seen if now >= seen else now), now
def days(opens, need=24 * H, trials=2000, seed=1):
    """The phone is on from 07:00 to 23:00, the app is opened at the hours in `opens` for one minute;
       every time varies by up to 10 minutes. Returns the median and the longest number of days."""
    rnd, out = random.Random(seed), []
    jitter = lambda: rnd.uniform(-10, 10) * 60_000
    for _ in range(trials):
        credited, seen, day, done = 0, None, 0, None
        while done is None:
            boot = 7 * H + jitter()
            for o in opens:
                t = o * H + jitter()
                for now in (t - boot, t - boot + 60_000):           # ON_START and ON_STOP of that visit
                    if seen is None: seen = now                     # the request is made at the first open
                    else: credited, seen = observe(credited, seen, now)
                    if done is None and credited >= need: done = day + (o - opens[0]) / 24
            day += 1
        out.append(done)
    out.sort()
    return out[len(out) // 2], out[-1]
for name, opens in (('morning and evening', [9, 21]), ('once, in the evening', [21]), ('once, two hours after switching on', [9])):
    print('%-36s median %4.1f days, longest %4.1f days' % (name, *days(opens)))
```

| The app is opened | Median | Longest of 2,000 runs |
|---|---|---|
| In the morning and in the evening | 1.5 days | 1.5 days |
| Once a day, in the evening | 3 days | 8 days |
| Once a day, two hours after switching on | 22 days | 33 days |

The spread in the last two rows has one cause. After a restart the helper credits the new uptime only when that is less than the last value it saw before the restart. An app that is opened once a day at about the same hour meets that condition on some days and misses it on others, and on a day that misses, almost nothing is credited.

---

## Audit

An independent audit of this document, made on 2026-10-06, judged the daily path fit to build from (the PIN slot, the counter, the Keystore error rules, the fingerprint rules, the session rules) and found the parts added after the judges had finished not ready: finishing a replacement, the 30-day copy and "Go back". It raised 21 findings, 5 major and 16 minor. Each was checked again before anything was changed; all 21 hold, in whole or in part. Where the text above is marked [A], the audit is the source.

| # | Raised | What was done |
|---|---|---|
| 1 | **Major.** The 30-day take-back could be defeated: a kill between two writes, a second replacement overwriting the one record, or "Delete" with the new password after a restore | **Changed, by the audit's simpler alternative.** Finishing a replacement now makes a new vault and puts the old one aside, whole (F11 steps 15 to 18). The old passwords are protected by that swap, not by a later write; every old vault carries its own `replaced` record, written before the swap and settled at the next start (5.11); only the old master password can delete the old vault (F17 step 5). There is no 30-day end any more. 2.2, 2.3 and Help say that "Clear storage" and uninstall delete it. T14, T16, A10 |
| 2 | **Major.** One-factor replacement only partly answered; the A4 row promised too much | **Changed.** The proof is the PIN whenever a PIN exists; a fingerprint only when there is none, and never as a fallback when the PIN cannot be checked (F11 step 3, 8.2). Rows A4 and A5 of 2.2 and a new item in 2.3 say what remains. **Kept on purpose:** finishing asks for no PIN or finger, so that an owner who lost both during the wait still gets in [J-R D3-7]. **Left to the user:** a notification during the wait (18.16) |
| 3 | **Major.** "Go back" was not a flow: no order, no crash handling, no way back, two copies with one `vault_id` | **Changed.** It is now the swap of F17 step 4, numbered in F16b, with a question first and a crash table. The newer passwords are a vault of their own with its own id, listed, opened and deleted like any other. No shortcut can open the old passwords. The button is "Open my passwords from before 7 Oct" |
| 4 | **Major.** A slot could need three wraps where the format allows two | **Changed.** One rule in 5.4: a change first drops every wrap except the current key's; every unlock drops wraps that no kept generation carries. F8's table and T15 cover a failed change started again and two changes in one process |
| 5 | **Major.** Forgiving the visit's wrong PINs and leaving them out of the notice gave someone near the owner uncounted, unreported guesses | **Changed.** A right PIN forgives only the wrong PINs of the last 60 seconds of its visit. This revision adds a cap of two, which the audit did not ask for. A fingerprint or master-password unlock hides nothing; `unseen` loses only what was shown; the list is cleared when a visit starts and when it ends; the "Wrong PIN" line stays for the whole visit (6.3, 6.4, 13.3, 13.4). What remains is in 2.2 and 2.3. The model check was run again with the new rule and finds two broken variants of it (appendix B) |
| 6 | Minor. PIN checks inside the open app were outside the definition of a visit | **Changed.** They are prompts: counted, never forgiven, always reported; F6, F8 and F11 say what happens on a limit, a temporary failure and a cancel (6.3, 6.5) |
| 7 | Minor. A check that finished after the screen went off opened the session | **Changed.** A result belongs to its visit (I11): counts are updated, nothing opens (6.3 E.6, 6.8 step 8, E13, T19) |
| 8 | Minor. Entry policy and message gaps (seven items) | **Changed.** A fingerprint slot that cannot be used now is left alone and the pad says why; E7 and E9 expect that. Texts for a damaged and a newer record, a default row, a storage-full reason, the fingerprint-only screen, the "not failing on this visit" clause (13.1 to 13.3). Items 6 and 7 are gone with the count of failing boots (see 21) |
| 9 | Minor. Session rules: two contradictions, three gaps | **Changed.** The no-touch timer stands still while the app is stopped or an external screen is pending; the last-authentication rule is dropped; the "replaced" notice runs on credited time; the lock delay's home is named (5.2); the write-down and type-again screens keep the screen on (10.1, 10.2) |
| 10 | Minor. The autofill call crashes on Android 7; the library makes a key on Android 10 at every `canAuthenticate` | **Changed.** API 26 gate (13.5, checked in E17); `canAuthenticate` once per visit, off the main thread (13.1); the alias is listed in 5.7 |
| 11 | Minor. Eight test-plan items could not run as written | **Changed,** all eight: the `staging` build of architecture.md; E7 split; E15 with a backup-on overlay; E8 last; `rule()` returns the rule number and the example 071990 moved to rule 8; T14's list; R-6; A1 with fast entries (15, appendix A) |
| 12 | Minor. The 24-hour wait credits far less than phone-on time | **Changed, and it is worse than the audit says** for one habit: opened once a day, two hours after switching on, the wait takes about three weeks (appendix F). 6.9 states the rule and the numbers; the request screen and the banner tell the user to open the app morning and evening. **Left to the user:** whether 24 is still the right number (18.7) |
| 13 | Minor. "5 guesses" is per vault | **Stated** in 2.2, 2.3, 4.6, 6.7 and F17 step 7. **Partly closed:** a vault put aside by a replacement has no PIN. **Not closed:** a counter for the whole phone was not designed, and the audit's other way would strand an owner without a fingerprint (decision 30, 18.17) |
| 14 | Minor. F8 promised that the old password would work after a failed save; F16b refused it | **Changed, more narrowly than proposed.** A mark in `state.rec` (`mp_change`) says that a change did not finish; only then does the old password offer "Open my passwords from before the change" (F8, F16b). The audit's condition ("while the new password has not yet opened the vault at a later start") holds after every change, for a day or more when the user unlocks with the PIN. In that time the old password would have gone on opening the passwords, which is what the user changed it to stop |
| 15 | Minor. Folding before NFC leaves three characters unfolded | **Changed.** NFC first (8.1); a vector line for the three characters (4.3); architecture.md §4.6 has to follow (16.3). Checked in Python and JDK 25 |
| 16 | Minor. Three Keystore and prompt corner cases | **Changed.** A missing canary is made again, and then null means gone (14.2); every launch has a number and the automatic launch is posted (9.2 rules 7 and 8); E6 runs the default confirmation |
| 17 | Minor. "No restore of an older counter without root" rests on AOSP only | **Could not be settled here.** The claim is worded as what it is (6.1, 6.7, 2.3); check D6 is added; 18.18 says what follows if it fails |
| 18 | Minor. Required practice could be skipped for ever, and `total` only grows for that user | **Changed.** The notice offers a new master password; a standing banner stays until a master password is typed (F10, 13.4 items 4 and 6); 6.4 gives the figures, re-run |
| 19 | Minor. The record write after a right PIN was on the critical path, unmeasured on f2fs | **Changed.** The list is shown when the vault is open; the write follows in the background under the mutex (6.3 E.6, decision 27); D1 measures it |
| 20 | Minor. Seven small corrections | **Changed,** all seven: I3, I6, 8.2, F14, "about 2,880" (6.8, decision 12), the code sketch of 6.3, and F8 step 7 on GONE |
| 21 | Minor. Six parts could go | **Removed:** the 5-minute expiry of remembered wrong PINs (6.6); the count of failing boots and `BOOT_COUNT` (F15); `slot.udr` (5.4); the last-authentication rule (10.2); the `replaced` bookkeeping in its old form and "put newer generations aside" for replacements (see 1 and 3). **Not decided here:** the unlocked-device flag on the PIN key stays until the security owner decides (18.2) |

**Changes that go beyond the audit,** made so that the text stays in one piece: the cap of two in finding 5, and the same forgiving rule for wrong master passwords in the notice (13.4); the white-space step that architecture.md §4.6 decided and asked this document to add (8.1); the answer to architecture.md's D-21, a reinstall that keeps the data (F15b, 14.2, E10); master-password checks on a vault that was put aside run under that vault's own guard instead of "without waits" (6.8, F17); invariant I11, decisions 27 to 30 and acceptance A10.

**Run again for this revision** [X]: the blocklist in Python and as the Kotlin of appendix A (compiled with Kotlin 2.4.20, run on JDK 25); the fold order in Python and JDK 25; the model of appendix B with the forgiving rule and its four broken variants; the simulation of appendix D with and without master-password entries; the scenarios of appendix F. **Not done:** no emulator and no phone was used, and the project was not built. Open for others: 18.2 (security owner), 18.7 and 18.16 (the user), 18.8 and 18.9 (crypto and storage owners), D6.
