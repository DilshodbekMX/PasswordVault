# Judgement: how the user enters the app (usability and implementability)

Date 2026-10-06. Lens: how much code and how many moving parts; whether it works on API 24 to 37 with androidx.biometric 1.1.0 and Compose; how fast the daily unlock feels; how many secrets a non-expert must remember and how often each is typed; how clear the screens and messages are; whether a non-expert can recover without help.

Designs judged, each read in full: `lock-designs/design-1.md` (security first), `design-2.md` (everyday use first), `design-3.md` (failure and recovery first). Measured against the seven hard requirements and `lock-facts/FACTS.md`.

**Tags.**
- **[C]** I confirmed it today in the named source.
- **[L]** From the emulator lab reports in `docs/lab/`. They were published at 13:11, after the designs (13:02 to 13:05), so the designers saw only preliminary lab logs.
- **[X]** My own calculation today (`lock-judges/failbudget.py`).
- **[I]** My judgement.

I started, stopped and used no emulator.

---

## 1. Verdict

| Rank | Design | Score (0 to 10, this lens) | Why |
|---|---|---|---|
| 1 | **Design 2** | **7.5** | Fewest parts and the fastest unlock: one Keystore HMAC key, no key derivation on the PIN path, one lock file. Two things to fix: fingerprint is blocked after the PIN limit, and a Keystore error rule is wrong on API 24 to 30 |
| 2 | **Design 3** | **7.0** | Best recovery paths and the clearest wording. But a PIN key derivation, a second usage-count budget and key rotation add code and delay, and can make "N tries left" false |
| 3 | **Design 1** | **4.5** | Most machinery: a pool of single-use keys refilled after every unlock. Its cumulative budget turns the PIN off for honest heavy users. It has no flow at all for "master password forgotten and nothing else works", so the UX spec's deleting "Start over" would stand |

Section 7 gives the shape I recommend: Design 2's PIN slot and counter, Design 3's recovery paths, Design 1's Keystore error rules and master-password hints.

---

## 2. What I checked beyond reading the designs

- **Lab reports published after the designs** (`docs/lab/biometric.md`, `old-android.md`, `window-input.md`). Four findings matter here:
  - **The phone's own PIN screen inside our prompt.** After failed fingers, the system prompt turns itself into a screen asking for the phone's PIN, and the app gets no callback. Five failed fingers lock the whole phone.
  - **A false fingerprint success.** If the phone PIN is typed on that screen and a finger then touches the sensor, `onAuthenticationSucceeded` arrives but `doFinal` fails. This happened 4 times out of 4.
  - **Typed secrets in saved state.** `rememberTextFieldState()` writes typed secrets into saved state.
  - **TalkBack untested.** No lab checked TalkBack against `isSensitiveData`.
- **androidx.biometric 1.1.0 on Samsung phones with Android 9.** I read the library source [C]:
  - `res/values/values.xml` sets `crypto_fingerprint_fallback_vendors = samsung`.
  - `DeviceUtils.shouldUseFingerprintForCrypto` applies on API 28 only.
  - `BiometricFragment.isUsingFingerprintDialog()` sends Samsung crypto authentication to `showFingerprintDialogForAuthentication()`.
  - That method checks `androidx.core` `FingerprintManagerCompat.isHardwareDetected()` first.
  - From core 1.18 that method is a stub returning false [L old-android §3, javap]. activity 1.13 needs core 1.18 or newer, so pinning an older core does not build [L].
  - Result: error 12, "no hardware", at once. Not run on a Samsung device.
- **Keystore on API 30 and older** (`d1src/KeyStore_a11.java:290-297`, `AndroidKeyStoreSpi_a11.java:95-112`) [C]. `engineGetKey` returns null whenever `contains()` is false. `contains()` returns false on a `RemoteException` ("Cannot connect to keystore").
- **Keystore on Android 17** (`d1src/AndroidKeyStoreSpi.java:121-139`) [C]. `getKey` turns `KeyPermanentlyInvalidatedException` into an `UnrecoverableKeyException` with no cause. Other failures keep their `KeyStoreException` as the cause. So the two can be told apart, as Design 1 says.
- **API levels** in `android-37.0/data/api-versions.xml` [C]:
  - `KeyProtection.Builder.setIsStrongBoxBacked` and `setMaxUsageCount` are 31, and `setUnlockedDeviceRequired` is 28. Design 1 is right.
  - `KeyStoreException.getRetryPolicy` is 33, but `RETRY_AFTER_NEXT_REBOOT` is **34**. Design 3 gates it at 33.
  - `FingerprintManager` is removed in 37.
- **How often people mistype a PIN.** Harbach, De Luca and Egelman, "The Anatomy of Smartphone Unlocking", CHI 2016 [C]:
  - "PIN users (891 of 28,376 attempts, 3.1 %)" failed.
  - Average PIN length was 4.25 digits, and participants were "well trained".
  - Successful PIN entry took 0.92 / 1.20 / 1.73 / 2.38 s at the 25th, 50th, 75th and 90th percentile.

  A new 6-digit app PIN that may not be a date will be mistyped at least this often [I].
- **Model of Design 1's cumulative budget** [X, `failbudget.py`]. Exact binomial over a 14-day window between master-password entries.

---

## 3. Side by side

| | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Work after the 6th digit | Durable count commit (file and directory fsync), then an HMAC in StrongBox or the TEE, GCM and vault open | (Charge already written at the 1st digit) TEE HMAC, GCM and vault open | (Charge written at the 1st digit and at least 1 s old) Argon2id 16 MiB, TEE HMAC, GCM and vault open |
| Estimated 6th digit to list, mid-range phone, 1,000 entries [I] | 0.15 to 0.45 s | 0.1 to 0.3 s | 0.2 to 0.5 s (more on low-end phones; Argon2 is unmeasured on phones) |
| Background work after each PIN unlock | Commit, import one new Keystore key, commit, clean up aliases | One record write | Commit; on API 31+ generate a new key, HMAC, write and delete (rotation); orphan sweep |
| Keystore keys at steady state | Up to 6 (5 single-use attempt keys and the fingerprint key) | 2 | 3 (PIN, fingerprint, canary) |
| Lock files | 1 | 1 | 2 |
| Lock-specific API branches | 24–27, 24–30, 28–29, 31+, 35+ | 24–27, 24–30, 28–29, 31+, 35+ | 24–27, 24–30, 28–29, 31+, 34+ |
| Master password typed when | Setup. Practice on days 1, 3 and 7, then every 14 days, with **one** "Not now". Every export and backup. Setting or changing the PIN, turning fingerprint on, lengthening timeouts (5-minute window) | Setup. Practice on days 1, 3, 7, 21, 35 …, with **3 days** of "Use PIN this time". Every export and backup. After the PIN limit | Setup. Practice on days 1, 3 and 7, then every 14 days, with "Later" up to **3 times** in a row. Every export and backup. Setting a first PIN |
| After 5 wrong PINs | Fingerprint or master password | **Master password only** (fingerprint blocked) | Fingerprint or master password |
| Forgot master password, PIN works | Reset after **72 h** of uptime; prove PIN *and* fingerprint, twice | Reset **at once** with the PIN | Reset after **24 h** of uptime; PIN *or* fingerprint |
| Forgot master password, nothing works | **No flow** (the UX spec's "Delete and start over" stands) | Start over **archives** the vault | Start over archives; "Old locked copies" open only with that copy's master password |
| `getKey()` null on API 24–30 | Temporary until corroborated | **Treated as gone**: PIN removed | Canary key decides |
| Persistent "temporary" Keystore error | After 3 failures across 2 boots, offers to turn it off | After 2 boots, offers to set it up again | **Retries every session; nothing offered** |
| Blocklist refuses | 8.98 % of all 6-digit PINs | 4.45 % | 12.63 % |
| Relative code size [I] | Largest; the attempt-key pool has no precedent in the reference apps | Smallest | Large; mostly conventional parts plus extra features |

---

## 4. Defects

Severity: **critical** loses data, leaks secrets or locks the user out; **major** is a real weakness or a broken flow; **minor** is anything else. Shared defects are in 4.4 and also appear in each design's score list.

### 4.1 Design 1

1. **Critical. No flow when the master password is forgotten and no PIN or fingerprint works, so the UX spec's deleting "Start over" stands.**
   - Design 1 never mentions Start over, archiving or ux.md (searched today: no match). F12 restores a backup only from onboarding, which appears only when no vault exists.
   - ux.md §5.10 puts "Start over…" behind "Forgot master password?" on the lock screen: one checkbox, then "Delete and start over".
   - *What happens:*
     - A child or a thief opens the sheet, ticks the box, and the vault is deleted.
     - An owner who forgot the master password starts over, later remembers it, and the data is gone.
     - If the sheet is simply left out, the owner is stuck on the master-password screen with no way to restore a backup.
   - *Fix:* Design 3 §11.20 or Design 2 §9.15: archive, never delete, and "Old locked copies" that open or delete only with that copy's master password. Add "Restore a backup" to the sheet.

2. **Major. The budget of 10 wrong PINs between master-password entries turns the PIN off for honest heavy users.**
   - How it works (§5.1, 5.3, 5.4):
     - `FAIL_BUDGET = 10`, and the pool target is `min(5, 10 − fail_since_mp)`.
     - Typos still count when the next PIN is right; only a master-password entry resets the count, about every 14 days.
     - "N attempts left" is shown "always when 2 or fewer remain".
   - *What happens* [X, 14-day window, user who only types the PIN]:

     | PIN entries per day | Typo rate | Pool silently below 5 | "2 attempts left" on a fresh screen | PIN turned off |
     |---|---|---|---|---|
     | 4 | 5 % | 6.0 % of windows | 0.6 % | 0.0 % |
     | 8 | 3.1 % | 13.6 % | 2.4 % | 0.3 % |
     | 8 | 5 % | 49.1 % | 19.8 % | 5.4 % |
     | 8 | 8 % | 89.2 % | 68.1 % | 40.7 % |

     - This hits people who only type the PIN: Android 7 and 8, Samsung on Android 9 (4.4 S1), and phones without a sensor.
     - A master-password unlock does not refill the pool (§5.5). After 4 typos and a master-password unlock, the next lock screen says "Last attempt", and one typo turns the PIN off.
   - *Fix:*
     - Keep only "5 wrong in a row", as Designs 2 and 3 do.
     - If a long-term budget is wanted, count only lock episodes that end without a correct PIN.
     - Never show a reduced count without a recent failure.

3. **Major. The practice gate allows one "Not now", then demands the master password at the very next lock screen. The way out is hidden and starts a 72-hour reset.**
   - §6.3 and F9 say this. With the 1-minute auto-lock, "the next lock screen" can be minutes later.
   - §6.3 and F9 show "Forgot it? You can set a new one" only after three wrong master passwords. §6.4 step 1 implies a permanent link, and the §8.2 master-password state lists none, so the spec contradicts itself.
   - *What happens:* the user is in a shop, needs a password, and the paper is at home. Either they are blocked, or they type three wrong passwords to reveal the link, prove PIN and fingerprint, and file a reset they did not want. They cancel it later by typing the master password.
   - *Fix:* Design 2's time-based grace, asking at most once a day, then Design 3's always-visible "I can't remember it".

4. **Major (implementability). The attempt-key pool is the largest and most novel mechanism of the three, built for an attacker outside the threat model.**
   - The parts:
     - 5 imported single-use HMAC keys per PIN epoch;
     - an import after every PIN unlock, in a non-cancellable job that holds the lock mutex;
     - clean-up of unused aliases;
     - StrongBox import with a TEE fallback;
     - manual deletion on API 24 to 30, a usage count on 31+, unlocked-device keys on 35+.

     None of the reference apps does this.
   - Its gain over Design 2's counter is only against root, which platform.md §4 puts out of scope. Its hardware-enforced case needs `FEATURE_KEYSTORE_SINGLE_USE_KEY`, which an unknown number of phones declare (none of the Pixel or Cuttlefish makefiles, FACTS §1.2).
   - *What happens:* refills that fail (a Keystore hiccup, or the process killed during the refill) drain the pool.
     - At 0 keys with `pin` still set, "Choosing" step 4 (which needs keys > 0) falls to `MasterPassword(NO_DEVICE_SECRET)`. No reason is shown, and only the master password can repair it.
     - The code comment "lockOut() never leaves an empty list" does not cover a correct PIN on the last key followed by a failed refill.
   - *Fix:* one HMAC key and an app counter (Design 2). If a Keystore-side limit is kept, use a single usage-limited key whose count the screen reads (see 4.3 item 1).

5. **Minor. The reset waits 72 h of observed uptime and needs every device secret at both ends.**
   - Uptime is added only at app start and resume, so phone-off time and the gap between the last app use and a shutdown are lost.
   - A user who switches the phone off at night and opens the app once each morning gains about 2 h a day, so "about 9 Oct" becomes weeks [I].
   - *Fix:*
     - Wait 24 h (Design 3).
     - Show the remaining phone-on time: "about 20 hours left; the countdown pauses while the phone is off".
     - Accept the PIN *or* fingerprint as proof.

6. **Minor. It departs from the agreed UX spec without listing the changes.**
   - Auto-lock default is 1 min (ux.md §5.18 says 30 s).
   - Lock on screen-off can be switched off (ux.md says always).
   - It adds a 15-minute option and its own pad layout and strings.
   - *Fix:* follow ux.md, or give the UX owner a change list.

7. **Minor. The master-password field lives in `remember { TextFieldState() }`** (§8.1). It is safe from saved state, but the typed password is lost on rotation [L window-input, experiment 5].
   - *Fix:* a ViewModel-held `TextFieldState`, cleared on lock.

8. **Minor. Shared S1** (Samsung Android 9 fingerprint) and **S2** (two different "Enter your PIN" screens).

### 4.2 Design 2

1. **Major. Fingerprint is blocked after the PIN limit.**
   - §5.1 ("At the limit", item 3), §8.1 `need_master` and §9.5: fingerprint is not offered until a master-password unlock.
   - A fingerprint cannot be guessed, so this adds nothing against the PIN guesser; Design 3 §4.6 rejects it for that reason.
   - *What happens:*
     - Any five presses (a child, a curious friend; there are no delays, so it takes seconds) force the owner to type the master password.
     - For an owner who has forgotten the master password, the PIN-based reset goes too, because §6.2 needs a usable PIN. The only way left is Start over, which locks the data away in the archive.
   - *Fix:* keep fingerprint usable after the limit, as Designs 1 and 3 do. Setting a new PIN still needs the master password or the replacement path.

2. **Minor. `getKey()` returning null is treated as GONE on API 24 to 30.**
   - Section 10 cites only the API 31+ source. On API 30 and older, null also means "keystore daemon unreachable" (section 2) [C].
   - *What happens:* the keystore daemon restarts on an Android 9 phone just as the lock screen calls `getKey`. The user sees "Your PIN stopped working on this phone. Type your master password, then choose a PIN again", and Kpin is deleted.
   - *Fix:* before treating a key as gone on API below 31, use Design 3's canary key or Design 1's corroboration rule. On 31+, use Design 1's rule: an `UnrecoverableKeyException` with no cause means gone.

3. **Minor. Practice nags at every unlock for 3 days, then the only way past without the paper is an immediate, permanent replacement.**
   - During grace (§6.1, 9.10), every lock episode starts on the master-password screen with no automatic fingerprint prompt.
   - After grace, a user without the paper must type the master password or replace it at once (§6.2).
   - The new master password then lives alongside the old one, which still opens old backups.
   - Anyone who has seen the PIN can do the same and then export; that is the security judge's call.
   - *Fix:* during grace, ask at most once a day. After grace, use Design 3's cancellable request (24 h, with a banner) instead of an instant replacement.

4. **Minor. The wrong-master-password hint appears only if Cyrillic letters were typed** (ux.md wording). It misses a user whose master password is Cyrillic and who typed it on a Latin layout.
   - *Fix:* Design 1's neutral hint on every wrong master password, plus the apostrophe folding that Design 1 asks the crypto owner for (FACTS §9).

5. **Minor. The rules for an unreadable lock record contradict each other.** §6.1 says "then Choose a PIN for this phone"; §8.1 says "no set-up offer and no rewrite".
   - If the I/O error persists, the user types the master password at every unlock and nothing is offered.
   - *Fix:* after the same error on 2 boots, offer to set the PIN up again under a new file name.

6. **Minor. Shared S1 and S2.**

### 4.3 Design 3

1. **Major. Two attempt budgets can disagree, so "N tries left" can be false.**
   - `pin_failures` returns to 0 on a correct master password (§7.4, §8). But K_pin's usage count in the keystore daemon (6 = 1 wrap + 5 tries) is renewed only by rotation after a correct PIN.
   - A failed rotation leaves a partly used key (§7.4 says so). A refunded attempt whose HMAC finished but whose result was lost has still spent a use.
   - *What happens:*
     1. The owner types 4 wrong PINs and gives up.
     2. They unlock with the master password: the screen is back to 5 tries, but the key has 1 use left.
     3. Next day, one typo: "Wrong PIN. 4 tries left."
     4. The next attempt hits `KeyPermanentlyInvalidatedException`, and the screen says "PIN unlock stopped working on this phone. Type your master password, then choose a PIN again."
   - *Fix:* drop the usage limit and rotation. They only bound root, which is out of scope, and dropping them also removes a key generation per unlock. If they are kept, do not reset `pin_failures` on a master-password unlock, and show the smaller of the two counts.

2. **Minor. A PIN key derivation sits on the daily path for an attacker outside the scope.**
   - Every PIN unlock runs Argon2id (16 MiB, t 3, p 4) behind a 1,000 ms charge-age floor. That makes it the slowest daily path of the three: 50 to 120 ms on mid-range phones, unmeasured, and more on low-end ones.
   - It puts a native library and a memory dependency on the daily path; the old-Android lab saw a Bouncy Castle out-of-memory error take down the UI thread at 64 MiB [L]. It also adds slot fields and test vectors.
   - It helps only after the TEE key has been extracted, which Design 3 itself places beyond the threat model (§4.5, row D).
   - The 1 s floor rarely binds (a practised 4-digit PIN takes 0.92 s at the 25th percentile [C]), but fast typists wait.
   - *Fix:* Design 2's construction (no PIN key derivation) and its 500 ms charge age.

3. **Minor. "Later" counts lock sessions** (3 in a row). With the 30 s auto-lock, all three can be used up within an hour.
   - *Fix:* Design 2's time-based grace; keep "I can't remember it" visible.

4. **Minor. The replacement banner promises an exact time** ("after 7 October, 14:02"), but the proven-time rule counts only observed uptime. Phone-off time and the gap before a shutdown are lost.
   - *Fix:* show the remaining phone-on time, and say that the countdown pauses while the phone is off.

5. **Minor. No offer to set up again a secret that fails "temporarily" at every unlock** (§11.18 just retries next session).
   - A phone maker whose Keystore reports fingerprint invalidation as a plain `InvalidKeyException` (V-C6) leaves fingerprint silently hidden for good.
   - A PIN key that keeps failing means the master password at every unlock.
   - *Fix:* Design 2's `trouble_boots`: offer to set it up again after 2 boots, never automatically.

6. **Minor. `RETRY_AFTER_NEXT_REBOOT` is gated at API 33** (§10.3), but the constant exists only from API 34 [C].
   - *Fix:* gate it at 34.

7. **Minor. The largest blocklist** (12.63 % of the space, 99.0 % of the 10,000 most popular PINs).
   - More first ideas are refused, so creating a PIN takes longer (FACTS §6.1: 25.4 s against 7.9 s with a list covering 27 % of the 4-digit space), and the PIN finally chosen is less memorable.
   - *Fix:* Design 2's DD.MM.YY-only date rule plus the pattern rules, or drop the "year at either end" rule.

8. **Minor. Shared S1 and S2.**

### 4.4 Shared by all three

- **S1 Minor. Samsung on Android 9: fingerprint can never be turned on with biometric 1.1.0, yet every design offers it on API 28.**
  - Mechanism: section 2 [C]; not run on a device.
  - *What happens* [I]: on a phone whose last update was Android 9 (for example a Galaxy S8, Note 8 or A8 2018), the setting offers fingerprint. Turning it on fails at once with error 12, and the message says "You can try again later", which is false.
  - *Fix:* on API 28, when `Build.MANUFACTURER` equals "samsung" (ignoring case), do not offer fingerprint, and say "Fingerprint unlock isn't available on this phone's Android version. Use your PIN." Keep 1.1.0: the PIN covers these phones, so the 1.4 alpha is still not needed.
- **S2 Minor. Two different "Enter your PIN" screens.**
  - After failed fingers, the system prompt changes itself into "Enter your PIN — PIN is required to recover biometrics…", asking for the *phone's* PIN [L biometric, case 4]. The app's pad also says "Enter your PIN", and all three designs tell the user to choose a different PIN.
  - *What happens:*
    - The app PIN typed into the system screen counts as a wrong phone PIN, towards the phone's lockout and any "wipe after N failures" policy.
    - The phone PIN typed into our pad uses up app tries.
    - The phone PIN followed by a finger gives a success animation, then `Key user not authenticated` [L, finding b]. The designs answer with a generic message.
  - *Fix:*
    - Title the pad "Enter your app PIN" (and the Uzbek and Russian equivalents).
    - At PIN setup, say "This is not your phone's PIN".
    - After that specific failure, say "Fingerprint did not open the app this time. Enter your app PIN."
- **S3 Minor. "Use fingerprint to verify it's you in apps" switched off is never explained.** With 1.1.0 this gives `canAuthenticate` 21 and prompt error 8 [L].
  - Design 1 even suggests turning fingerprint off after repeated failures. Design 3 hides the fingerprint key silently.
  - *Fix:* map 21 and 8 to "Fingerprint for apps is turned off in your phone's settings."
- **S4 Minor. TalkBack with `isSensitiveData` on the pad keys is untested** [L window-input]. Only Design 2 plans a fallback.
  - *Fix:* test with TalkBack. If TalkBack cannot press the keys, keep the flag on the dots and drop it from the keys (Design 2 §8.3).

---

## 5. Sub-scores

| Criterion | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Code and moving parts | 3 | 8 | 5 |
| API 24 to 37 with biometric 1.1.0 and Compose | 7 | 6.5 | 7 |
| Speed of the daily unlock | 8 | 9 | 6.5 |
| Secrets to remember, how often typed, how often blocked | 4 | 7 | 7 |
| Clarity of screens and messages | 5.5 | 7.5 | 7.5 |
| Recovery without help | 2.5 | 6.5 | 8 |
| Mean | 5.0 | 7.4 | 6.8 |
| **Score** (rounded to the nearest half; Design 1 loses 0.5 for its critical defect) | **4.5** | **7.5** | **7.0** |

All three meet requirements 1 to 7 as written. On this lens, no design fails a hard requirement outright.
- Requirement 4 (unlock well within a second) holds for all three on a mid-range phone [I]. Design 3 has the least margin.
- Requirement 5 holds for all three, with the Samsung API 28 gap (S1).

---

## 6. Ideas the final design should take

**From Design 1**
- **Keystore error rules.**
  - On API 31+: `getKey()` null, or an `UnrecoverableKeyException` with no cause, means gone.
  - On API 24 to 30: remove nothing until another of our keys loads in the same call, or the key is still missing after a reboot.
- **Master-password hints for this user.**
  - On every wrong master password: "Check the keyboard language (Latin or Cyrillic) and the ‘ sign in o‘ and g‘."
  - Ask the crypto owner to fold the apostrophe look-alikes before NFC, with test vectors, so a remembered password still works on another keyboard.
- **Making the lock stricter never needs the master password:** turning PIN or fingerprint off, shortening timeouts, "Lock now".
- **A last-try warning that says what happens next:** "Last attempt. If this PIN is wrong, PIN unlock turns off and you'll need your master password."
- **Plain export needs the master password typed now, with no re-authentication window,** with the stated reason: a seen PIN or a sleeping owner's finger must not be enough.
- **"Keeps failing" rule:** after 3 temporary failures across 2 boots, offer to turn the factor off. Only the user removes it.
- **Crash-injection test:** the number of PIN evaluations never exceeds the attempts committed as spent.

**From Design 2**
- **The PIN slot.** One non-exportable Keystore HMAC key, no key derivation on the PIN path, no usage limit. The vault opens about 0.1 to 0.3 s after the sixth digit.
- **The counter protocol.**
  - Charge at the first digit; evaluate only once the charge is at least 500 ms old; refund when the entry is abandoned.
  - The protocol is model-checked; port `counter_model.py` as a test.
- **The same wrong PIN again within 5 minutes is not counted** ("That's the PIN you just tried"); it is kept in memory only.
- **PIN wraps per vault key** (at most 2), so changing the master password needs one PIN entry and an interrupted change still opens with the PIN.
- **Time-based practice grace** instead of a count. Every correct master-password entry counts as practice (with the 20-hour rule).
- **Changing the PIN or turning fingerprint on accepts the current PIN or the master password.**
- **A small blocklist that explains itself:** DD.MM.YY dates (the local format), repeats, sequences and keypad shapes. It refuses 4.45 % of PINs, including all of the top 10, with one message per rule and no way to click through.
- **Master-password pause:** 30 s after every 5 wrong entries, never growing.
- **The pad:**
  - hardware-keyboard digits;
  - "PIN, 3 of 6 digits entered" for screen readers;
  - a TalkBack test with a fallback;
  - the "interrupted" notice;
  - an "unseen failures" notice that leaves out the current episode.

**From Design 3**
- **Fingerprint stays usable after the PIN limit.**
- **Forgotten master password while a PIN or fingerprint works.**
  - A cancellable replacement request with a wait that reboots cannot shorten (24 h).
  - A banner at every unlock that anyone can cancel; any correct master password also cancels it.
  - "I can't remember it" is always visible on the practice screen.
- **Start over archives and never deletes.** "Old locked copies" open or delete only with that copy's master password; archives are never pruned automatically.
- **A canary key** tells "this alias is gone" from "the Keystore is failing" on API 24 to 30.
- **The "ways to lose access" table (§2) and invariants I1 to I9** as the acceptance checklist.
- **Wording that separates the app PIN from the phone's PIN** ("…unlock your phone with its PIN, pattern or password. Use your app PIN.").
  - The last-try warning that mentions a pending replacement.
  - The attempt notice with times and a "Change PIN" button.
- **`RETRY_AFTER_NEXT_REBOOT` (API 34+)** shows "Restart your phone to use your PIN again."
- **Master-password waits that never block** (none for tries 1 to 5, then 30 s, then 5 min), using the reboot-proof elapsed-time helper (§7.5).
- **The `setMaxUsageCount` pattern** (try once more without it) if any usage-limited key survives.

---

## 7. The shape I recommend from this lens

1. **PIN path:** Design 2, unchanged:
   - one HMAC key, no PIN key derivation, no usage limit;
   - charge at the first digit with a 500 ms age;
   - refunds when an entry is abandoned;
   - the same wrong PIN forgiven for 5 minutes.
2. **Limit:** 5 wrong in a row, with no cumulative budget (drop Design 1 item 2). At the limit the PIN key is deleted and **fingerprint stays**. A new PIN needs the master password or the replacement path.
3. **Keystore errors:** Design 1's classifier, plus Design 3's canary (or Design 1's corroboration) on API 24 to 30, plus Design 2's offer to set up again after 2 boots.
4. **Practice:** days 1, 3 and 7, then every 14 days.
   - A 3-day grace that asks at most once a day.
   - Then the master password, with an always-visible "I can't remember it" that opens with the PIN or fingerprint and files Design 3's cancellable 24-hour request.
   - Never an instant, permanent replacement.
5. **Start over:** Design 3's archive. "Restore a backup" on the same sheet.
6. **Fingerprint:** biometric 1.1.0; offered on API 28+ except Samsung on API 28; prompt built once in `onCreate`; S2 and S3 wording.
7. **Master-password field:** a ViewModel-held `TextFieldState`; Design 1's keyboard and apostrophe hint; apostrophe folding agreed with the crypto owner before the first release.

The open question stays the same in all three designs: did the user mean the app's own PIN or the phone's screen lock? On this lens, the phone's lock would mean one fewer secret to remember. All three designs keep an API 30+ plan ready, and Design 2's framing ("an extra way in; nothing else changes") is the cheapest to adopt.

---

## 8. Sources opened today

- **Designs:** `scratchpad/design/lock-designs/design-{1,2,3}.md`.
- **Fact sheet:** `scratchpad/design/lock-facts/FACTS.md`.
- **UX spec:** `docs/design/ux.md` §5.6 to 5.10 and §5.18.
- **Lab reports:** `docs/lab/biometric.md`, `docs/lab/old-android.md`, `docs/lab/window-input.md`.
- **androidx.biometric 1.1.0:** `scratchpad/lab/biometric/libsrc/aar-1.1.0/res/values/values.xml` and `src-1.1.0/androidx/biometric/{DeviceUtils,BiometricFragment}.java`.
- **AOSP keystore client:**
  - Android 11: `scratchpad/design/lock-designs/d1src/{KeyStore_a11,AndroidKeyStoreSpi_a11}.java`;
  - Android 17: `d1src/AndroidKeyStoreSpi.java`.
- **API levels:** `/home/asus/Android/Sdk/platforms/android-37.0/data/api-versions.xml`.
- **Field study:** M. Harbach, A. De Luca, S. Egelman, "The Anatomy of Smartphone Unlocking: A Field Study of Android Lock Screens", CHI 2016. https://research.google.com/pubs/archive/44675.pdf (copy at `lock-judges/harbach-chi2016-anatomy.pdf`).
- **My calculation:** `lock-judges/failbudget.py`.
