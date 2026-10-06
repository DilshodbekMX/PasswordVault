# Security judgement: how the user enters the app

Judge: security under the threat model. Written 2026-10-06.

**What I judged.** `design-1.md` (security first), `design-2.md` (everyday use first) and `design-3.md` (failure and recovery first), each read in full, against the seven hard requirements and `lock-facts/FACTS.md`. I also read `platform.md` and `VERIFICATION.md`.

**Tags.**
- **[C]**: confirmed today from the named source.
- **[X]**: my own run today. The scripts are in `scratchpad/design/lock-judges/work/`.
- **[I]**: my inference.
- **[U]**: not confirmed.

**Path shorthand.** AOSP files are on `android17-release`.
- `LF/` = `scratchpad/design/lock-facts/aosp/`
- `LJ/` = `scratchpad/design/lock-judges/aosp/` (fetched today)
- `D1SRC/` = `scratchpad/design/lock-designs/d1src/`

---

## 0. Verdict

| Rank | Design | Score /10 | In one line |
|---|---|---|---|
| 1 | **Design 1** | **7.0** | It has the strongest attempt budget, and it is the only design that stops a person who knows the PIN from adding their own way in. It is missing three things: ageing of the count against power cuts, any limit on wrong master passwords, and a reset wait that adb cannot shorten. |
| 2 | **Design 3** | **6.0** | It has the best counter mechanics: a charge at the first digit, a 1-second floor, a keystore2 usage limit, the strongest blocklist and master-password waits. Its 24-hour, one-factor master-password replacement falls to the same adb trick in minutes, and a correct PIN gives an insider a fresh budget. |
| 3 | **Design 2** | **4.0** | Its counter is clean and model-checked. But a PIN alone replaces the master password at once, and every fingerprint unlock gives an insider 5 new tries. |

**What all three get right.**
- All three meet requirements 1, 2, 5 and 6 as written.
- All three meet requirement 3 for a one-time thief who kills the app, reboots, changes the clock or clears the cache.
- The PIN can never be tested from copied files. Each guess needs a non-exportable Keystore HMAC key, and release builds refuse software keystores.
- None of them deletes vault data on any lock event.

They differ on four things: power cuts, repeated access by someone close to the owner, what a person who saw the PIN can do next, and wrong master passwords typed at the lock screen.

**Findings that decide the ranking:**
1. **New: the reset waits in Designs 1 and 3 can be skipped through adb.** `Settings.Global.BOOT_COUNT` can be changed without a reboot. Designs 1 and 3 credit the whole uptime when it changes, so three `adb shell settings put global boot_count N` commands credit about 150 hours [C, X] (section 2.1).
2. **Design 2: the PIN alone replaces the master password, at once.** That gives a bulk export and locks the owner out (section 2.4).
3. **Who restores the PIN budget.**
   - Design 1: only the master password.
   - Design 3: also a correct PIN.
   - Design 2: also a correct PIN and any fingerprint unlock.
4. **Order of count and check under a power cut.** Designs 2 and 3 age the count before checking. Design 1 counts and then checks at once.
5. **Wrong master passwords at the lock screen.** Design 3 waits, Design 2 pauses 30 s after every 5, and Design 1 has no limit.

---

## 1. Facts I re-checked today

| # | Claim | Who relies on it | Result | Source |
|---|---|---|---|---|
| 1 | keystore2 decrements a usage count only after KeyMint's `finish()` succeeds, and returns no output if the decrement fails | D1, D3 | **Confirmed.** `after_finish()?` runs before `Outcome::Success` and before the output is returned | `LF/operation.rs:453-456`; `LF/enforcements.rs:320-331, 537-542` [C] |
| 2 | The decrement is an IMMEDIATE SQLite transaction on a database with `synchronous = EXTRA` | D3 | **Confirmed.** It is an fsync to the same `/data` as our own files. On f2fs mounted `nobarrier` (V-S8) it is therefore not a separate durability domain [I] | `LF/database.rs:1206-1207, 2303-2337` [C] |
| 3 | A usage count of 1 is enforced by KeyMint only when secure storage exists, for imported keys too | D1 | **Confirmed** | `LF/km_tag.rs:365-376`; `LF/km_keys.rs:337-341` [C] |
| 3b | …and the spent key is then reliably destroyed in secure hardware (D1's A3b row) | D1 | **Overstated.** The reference KeyMint only *logs* a failed secure deletion and still returns the result | `LF/km_operation.rs:366-372` [C] |
| 4 | An HMAC operation returns nothing before `finish()` | all | **Confirmed.** The Android SPI throws if `update` produces output | `LF/AndroidKeyStoreHmacSpi.java:209-224` [C] |
| 5 | API levels: `KeyProtection.Builder.setIsStrongBoxBacked` 31, `setMaxUsageCount` 31, `setUnlockedDeviceRequired` 28; `Settings.Global.BOOT_COUNT` 24; `KeyStoreException.getRetryPolicy` 33; `PowerManager.isInteractive` 20 | D1, D3 | **Confirmed** | api-versions.xml via `lock-facts/apiv.py` [C] |
| 6 | On keystore2, `getKey()` returns null only for KEY_NOT_FOUND, and an invalidated key surfaces as `UnrecoverableKeyException` **without** a cause | D1 (D2 says the cases cannot be told apart) | **D1 is right.** D2's "always TEMPORARY" is safe but over-cautious | `LF/AndroidKeyStoreSpi.java:123-140`; `D1SRC/AndroidKeyStoreProvider.java:431-450` [C] |
| 7 | On keystore1 (API 24 to 30), `getKey()` returns null on *any* error | D1 and D3 handle it. D2 treats null as GONE on every level | **Confirmed** | `D1SRC/KeyStore_a11.java:290-297`; `D1SRC/AndroidKeyStoreSpi_a11.java:95-104` [C] |
| 8 | Unlocked-device-required keys stay usable while the phone is locked if a weak unlock method (Smart Lock, Class 2 face) is on | D1 states this. D2 §2.5 omits it. D3 uses it to reject the flag | **Confirmed** | `LF/super_key.rs:950-951` [C] |
| 9 | `noBackupFilesDir` is never included in a framework backup | D2, D3 | **Confirmed for the AOSP framework.** OEM backup tools were not checked [U] | `LF/BackupAgent.java:626, 831` [C] |
| 10 | **`BOOT_COUNT` changes only at boot** (implied by D1 §6.4 and D3 §7.5) | D1, D3 | **False.** See the four points below the table | see below [C] |
| 11 | D2's model check: counting at submit and then checking (D1's order) fails if a power cut loses recent writes; a charge that has aged holds | D2 | **Reproduced.** Limit 2, settle 1 gives 9 / 9 / 6 / 2 evaluations for check-then-count, count-then-check, first-digit charge without ageing, and with ageing | [X] `d2work/counter_model.py 2 0 22` and `2 1 22` |

**Why claim 10 is false.** `BOOT_COUNT` can be rewritten without a reboot:
- It is a public, `@Readable` global setting (`LF/Settings.java:19099-19104`).
- `mutateGlobalSetting` checks only `WRITE_SECURE_SETTINGS` and a per-setting list of user restrictions (`LJ/SettingsProvider.java:1654-1667`). `boot_count` is not on that list (`LJ/UserRestrictionsUtils.java:798+`).
- The `adb shell` user holds `WRITE_SECURE_SETTINGS` (`LJ/Shell_AndroidManifest.xml:157`).
- Turning developer options on asks for the phone's own screen lock (`LJ/BuildNumberPreferenceController.java:158-166, 220-232`).

---

## 2. Findings that cut across the designs

### 2.1 BOOT_COUNT can be rewritten through adb (Designs 1 and 3)

**The flaw.** Both designs measure a reboot-safe wait the same way:

> if BOOT_COUNT changed: credit `elapsedRealtime()`; otherwise credit the elapsed delta.

- Design 1 uses it for the 72-hour master-password reset (§6.4 step 4).
- Design 3 uses it for the 24-hour master-password replacement (§11.9) and for every master-password retry wait (§7.5, §7.6).

A BOOT_COUNT change normally means a reboot, which resets uptime, so crediting the uptime is correct. But anyone with adb can change the count **without** rebooting. Every such change followed by bringing the app to the foreground then credits the phone's whole uptime again. On a phone up for two days, three writes over three minutes credit about 150 h [X, `work/bootcount_attack.py`].

**Who can do it.** The attacker needs:
- USB or wireless debugging already on, **or** the phone's screen-lock PIN to turn it on. This user may well use the same PIN for the phone and the app [I];
- the app PIN (Design 3), or the PIN and, if fingerprint is on, the owner's finger (Design 1).

**What happens in Design 3.**
1. Request the replacement with the PIN.
2. Rewrite `boot_count` and bring the app to the foreground until 24 h are credited.
3. Confirm with the PIN and set a new master password.
4. Export everything in plain text, or make a backup under the new password.
5. Change the PIN (the PIN is enough), and decline to re-enrol the fingerprint (§11.8 step 8).

The owner's old password no longer opens the re-keyed vault, the PIN has changed and fingerprint is off. **The owner is locked out**, and the attacker has a full copy. The same trick removes every master-password wait in §7.6. Design 3's test T5 cannot catch this, because it changes the boot count only on real reboots.

**Fix (both designs).** Never use BOOT_COUNT to credit time:

```
gained = if (elapsedNow >= p.elapsed) elapsedNow - p.elapsed else elapsedNow
```

- On the same boot, `elapsedRealtime` is monotonic, so the result is exact.
- After a genuine reboot it credits only the new uptime: a lower bound.
- A forged count gains nothing.

BOOT_COUNT may still be used in the safe direction, to *restart* a wait (as Design 2 §5.6 does). Add a test where the count changes while `elapsed` keeps rising.

### 2.2 Power cut between seeing the result and a durable count

| | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Order | Token marked spent at the 6th digit, HMAC at once (10 to 110 ms later) | Charge written at the 1st digit; check only when it is at least 500 ms old | Charge written at the 1st digit; check only when it is at least 1 s old |
| Model check, limit 2, recent writes lost [X] | **Violated** (9 evaluations) | Holds | Holds; same class with a wider margin |
| Budget held outside app files | keystore2 deletes the spent key on API 31+, but it writes to the same disk *after* the app's commit; on API 24 to 30 the app deletes each used key | None | keystore2 usage count on API 31+ |

Requirement 3 names power cuts. Pixels mount `/data` as `nobarrier` (V-S8), so a successful fsync is not yet on flash. Holding a write for half a second to a second before revealing its result is the only lever an app has. Phones with hardware single-use keys would protect Design 1's order, but none is known (FACTS §10).

### 2.3 Who gets a fresh PIN budget (repeated access)

The attacker here is a family member who picks up the phone on many days.

| Event | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Correct PIN | Refills to min(5, 10 − failures since the last master password) | Resets to 0 | Resets to 0 |
| Fingerprint unlock | Nothing | **Resets to 0** | Nothing |
| Master password | Full reset | Resets to 0 | Resets to 0 |
| Insider's guesses between two master-password entries | **at most 10** | unlimited: 4 before each daily fingerprint or PIN unlock | unlimited: 4 before each owner PIN unlock |

At 100 guesses, 12.5 to 13.3 % of 6-digit PINs fall [FACTS §6.1]. More than a third of people in one study had tried to unlock someone else's phone within a year [FACTS §6.1, Bailey et al.].

Requirement 3 is worded for a thief, but a fixed budget that only the master password restores is the only version of it that also holds for an insider. The after-unlock notice that all three designs show is a useful second line, not a limit.

### 2.4 What a person who saw the PIN can do with the phone

| Action | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Read entries one by one | yes (true of any PIN unlock) | yes | yes |
| Plain export or encrypted backup | master password typed now | master password typed now, but see the next row | master password typed now |
| Replace the master password | PIN, plus fingerprint if on; 72 h; banner. **Minutes with adb** (2.1) | **PIN alone, at once** | PIN alone (fingerprint only when no PIN exists); 24 h; banner. **Minutes with adb** (2.1) |
| Change the PIN | master password | PIN | PIN |
| Turn fingerprint on | master password | PIN | PIN, or within 120 s of a PIN unlock |
| Lengthen the lock delay | master password | nothing | nothing |

Design 1's rule is the right one. Making the lock stricter needs nothing. Adding or changing a way in needs the master password typed at that moment. Replacing a forgotten master password needs every enabled device factor and a long, cancellable wait.

### 2.5 Guessing the master password on the phone

After the PIN is off, a thief can type master-password guesses through a USB OTG keyboard, or with `adb shell input` if debugging is on. Each guess costs one Argon2id: 0.3 to 1 s on a phone.

| | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Waits | **none** | 30 s after every 5 | none for 1 to 5, 30 s for 6 to 10, then 5 min each |
| Guesses per day [I] | about 10^5 | about 1.3 × 10^4 | about 300 |
| Survives the adb trick of 2.1 | n/a | yes: a changed count restarts the pause | **no** |

The user came with one reused password and may make it the master password. An online guess at about one per second through the phone is then a real path for a thief who cannot copy files. A wait is not a lockout, so requirement 1 holds.

### 2.6 Blocklists against a five-guess attacker

[X, `work/cmp_blocklists.py`, using each design's own script; DD-6-digit list ordered by popularity]

| | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| Share of all 10^6 PINs blocked | 8.98 % | 4.45 % | 12.63 % |
| DD top 100 / 1,000 / 2,000 blocked | 100 / 92.1 / 90.3 % | 97.0 / 79.4 / 73.6 % | 100 / 98.2 / 99.0 % |
| Allowed PINs among the DD top 1,000 | 79 | 206 | 18 |
| An informed attacker's first 5 guesses (DD rank) | 105, 124, 130, 163, 167 | **24 (246810), 74 (135790), 94 (123457)**, 105, 119 (000001) | 163, 167, 177, 178, 188 |

---

## 3. Design 1: defects

| # | Severity | Defect | Fix |
|---|---|---|---|
| 1-1 | **critical** when fingerprint is off (otherwise the attacker also needs the owner's finger) | The 72-hour master-password reset credits the whole uptime whenever BOOT_COUNT changes (§6.4 step 4). Through adb a PIN-holder completes it in minutes, then exports everything and, now knowing the master password, changes the PIN (2.1) | Use the elapsed-only accounting of 2.1. Use the same rule where F13b treats "a later boot" as corroboration; there a forged count only removes a slot (denial of service) |
| 1-2 | major | Count, then check at once (§5.2). A power cut just after "Wrong PIN" can lose both the token commit and keystore2's later key deletion, which hands back the guess. D2's model check fails for this order [X] (2.2) | Mark the head token spent when the **first digit** is typed. Restore it when the entry is abandoned (the protocol already restores it on temporary errors). Call `finishHmac` only once that commit is at least 1 s old. A person typing never waits |
| 1-3 | major | No limit on wrong master passwords at the lock screen: about 10^5 OTG-keyboard guesses a day once the PIN is off (2.5) | Use Design 3's waits (§7.6), measured with the elapsed-only rule and restarted in full after a reboot (D2 §5.6). Never lock out (requirement 1). Also enforce the wait from memory when the counter write fails |
| 1-4 | minor | Section 2.6 A3b ("at most 5, enforced in secure hardware") is overstated: the reference TA returns the result even when secure deletion fails (1, row 3b), and no phone is known to declare the feature. In scope, the five-key pool gives what a counter plus a usage-limited key gives. It costs imported raw key material, an import after every unlock and a pool state machine | State the limit as it is. Make Design 3's single key with `setMaxUsageCount` (API 31+) the default. Keep the pool only if real-device checks (§11.4) find `FEATURE_KEYSTORE_SINGLE_USE_KEY` |
| 1-5 | minor | The refill job keeps VK and the typed PIN in memory for up to 10 s after the app locks (§5.3, §8.3), against the decision that a lock wipes keys | Refill only while unlocked; cancel at lock and let the next correct PIN refill |
| 1-6 | minor | The attempt notice includes the owner's own typos from this lock episode (§5.3 step 4). The owner learns to dismiss the one warning that reveals an insider | Subtract the current episode's failures (D2 §5.7) |
| 1-7 | minor | For 5 minutes after the master password is typed, setting a PIN or turning on fingerprint needs no new proof (§6.2). Someone handed the phone then can add a way in that they control | Require the master password typed at that moment for anything that adds a way in. Keep the window only for other settings |
| 1-8 | minor | There is no archive-style "Start over". A user who forgot the master password and has no working device secret is left with "Clear storage", which deletes a vault the master password might open later | Adopt D3 §11.20: archive, never delete; deleting an archive needs its master password |
| 1-9 | minor | Screen-off locking relies on a context-registered receiver (§8.3). Android 14+ queues it while the app is cached, so a screen-off during the background window can arrive after `ON_START` | Also check `isInteractive()` at `ON_STOP`, and lock when a late screen-off arrives (D3 §10.5) |

**Score 7.0.** Design 1 has the best structure for this lens:
- every attempt spends a Keystore-side resource;
- a 10-guess ceiling between master-password entries;
- the master password is needed to add a way in;
- a multi-factor reset;
- unlocked-device-required keys on API 35+;
- an honest cost table.

Its three serious defects are all cheap to fix, and the first needs fingerprint to be off.

---

## 4. Design 2: defects

| # | Severity | Defect | Fix |
|---|---|---|---|
| 2-1 | **critical** | **The PIN alone replaces the master password, at once** (§6.2, §9.8). Someone who saw the PIN and holds the phone sets their own master password in one sitting. They can then export in plain text or make a backup to carry away. If they also change the PIN (allowed with the PIN, §6.3) and decline fingerprint, the owner is locked out of the re-keyed vault; only the owner's older backups remain. The design names this cost and leaves it to this judge | Delete §6.2, or replace it with D1 §6.4 using the elapsed-only accounting of 2.1: every enabled device factor at request and completion, 72 h, a banner with a Cancel that needs no proof, cancelled by any correct master password |
| 2-2 | major | A fingerprint unlock, like a correct PIN, resets the failure count (§5.1, §8.4, §9.3). An insider gets 4 guesses before each of the owner's daily unlocks, unlimited over time (2.3) | Add D1's second budget: at most 10 wrong PINs between master-password entries, restored only by the master password. Keep the consecutive limit of 5 |
| 2-3 | minor | The whole limit is one app file plus one Kpin key without a usage limit, deleted only at the 5th failure. Any rollback of `lock.rec` before that restores the budget. That needs root, or a tool that snapshots app-private data; none is known without root [U] | Add `setMaxUsageCount(6)` on API 31+ with rotation after a correct PIN (D3 §7.4). One builder call puts a second budget in keystore2, outside app data |
| 2-4 | minor | The PIN alone turns fingerprint on and changes the PIN (§6.3). A PIN-holder whose finger is enrolled on a shared phone gets access that survives the owner's PIN change, or changes the PIN to deny it to the owner | Require the master password for anything that adds or changes a way in (D1 §6.1) |
| 2-5 | minor | The weakest blocklist: 4.45 %. It allows DD ranks 24 (246810), 74 (135790), 94 (123457) and 119 (000001), and blocks DDMMYY but not YYMMDD or year-based PINs (2.6) | Use D3's rules |
| 2-6 | minor | Wrong master passwords pause 30 s after every 5 and never longer (§5.6): about 13,000 guesses a day (2.5) | Add D3's 5-minute step after 10. Still never a lockout |
| 2-7 | minor | Fingerprint is blocked after the PIN limit (§5.1, §9.5). This stops no attacker, since an enrolled finger needs no PIN guesses. But a relative who burns 5 PINs takes both shortcuts from an owner who forgot the master password, who can then only "Start over" | Keep fingerprint, as D1 §5.4 and D3 §4.6 do |
| 2-8 | minor | `getKey()` returning null is GONE on every API level (section 10). On API 24 to 30 null also means a failing daemon, so a transient error deletes Kpin. This fails closed and costs only availability | Use D3's canary or D1's corroboration on API 24 to 30 |
| 2-9 | minor | `pending` is a boolean. If the start-up recount (§5.2 step 1) fails to write and PIN entry is still allowed, the next charge rewrites `pending = true`, and the interrupted attempt merges into it | Allow no PIN entry until the recount is durable |
| 2-10 | minor | §2.5 says rooted guessing on API 35+ works "only while the phone is unlocked". That is false when Smart Lock or Class 2 face is on (row 8) | State the condition, as Design 1 does |
| 2-11 | minor | Lengthening the lock delay needs no proof (§6.3) | Require the master password (D1 §8.3) |

**Score 4.0.** Design 2 has the best-evidenced counter: the model check, a single checksummed record, and a pause that uses BOOT_COUNT in the safe direction. As written, though, a PIN-holder owns the vault outright, and an insider never runs out of guesses.

---

## 5. Design 3: defects

| # | Severity | Defect | Fix |
|---|---|---|---|
| 3-1 | **critical** | `advance()` (§7.5) credits the whole uptime whenever BOOT_COUNT changes, and adb can change it without a reboot. This collapses the 24-hour replacement (§11.9) to minutes for a PIN-holder, and it zeroes every master-password wait (§7.6). Ending: new master password, PIN changed, fingerprint declined, so the owner is locked out (2.1). Its test T5 cannot see it | Use the elapsed-only accounting of 2.1, and add a T5 case where the count changes while `elapsed` keeps rising |
| 3-2 | major | The replacement needs one factor (the PIN, or the fingerprint when no PIN exists) and 24 h (§11.9). The owner's only defence is an English banner noticed within a day | Use D1 §6.4: every enabled factor at request and completion, 72 h. Require the master password to change the PIN afterwards |
| 3-3 | major | A correct PIN resets `pin_failures` (§7.4), so an insider gets 4 guesses before every owner PIN unlock, unlimited over time. On phones without fingerprint (API 24 to 27, or no sensor) the PIN is the daily route. Not resetting on fingerprint is right | Add D1's 10-failure budget between master-password entries |
| 3-4 | minor | Changing the PIN accepts the current PIN, and turning fingerprint on accepts a PIN check from the last 120 s, "already satisfied" after a PIN unlock (§8). Someone handed the phone right after a PIN unlock can turn fingerprint on for a finger enrolled on a shared phone | Require the master password typed at that moment (D1 §6.1) |
| 3-5 | minor | No `setUnlockedDeviceRequired` on API 35+ (§4.6), so a phone rooted later can be guessed through the TEE while locked. The stated cost, a "device locked" error, already falls in section 12's TEMPORARY row | Set it on API 35+ (D1 §2.3) |
| 3-6 | minor | Argon2id at 16 MiB on every PIN unlock (§4.2) adds about 50 to 120 ms (unmeasured on phones) and an out-of-memory or native-load failure to the daily path. It protects only after a TEE key extraction, when the fingerprint slot gives away VK anyway (§4.5 row D) | Drop it, as D1 and D2 do |
| 3-7 | minor | A refund is `pin_failures −= 1`, triggered from several UI events (§7.2, Abandon). A refund that races an evaluation, or runs twice, undoes a counted wrong PIN. Only I5 and T3 guard against it | Keep a separate charge flag that a refund clears (D2 §5.2), so a refund can never touch the failure count |

**Score 6.0.** Design 3 has the best mechanics against power cuts, the strongest blocklist, the only master-password waits that grow, a keystore2-side budget for one builder call, a canary for API 24 to 30, and a state that fails closed. But its recovery path, the design's own centrepiece, is a one-factor takeover once BOOT_COUNT is forged, and a correct PIN restores an insider's budget.

---

## 6. Scores and ranking

| Design | Score | Critical | Major | Minor |
|---|---|---|---|---|
| 1 | **7.0** | 1, only when fingerprint is off | 2 | 6 |
| 3 | **6.0** | 1 | 2 | 4 |
| 2 | **4.0** | 1, needs only the PIN | 1 | 9 |

**Ranking: Design 1, then Design 3, then Design 2.**

---

## 7. What the final design should take

### 7.1 The PIN path (requirements 2 and 3)

- **One evaluation = one Keystore HMAC under a non-exportable key, then a software AES-GCM unwrap** (all three). Never use a Keystore decrypt as the attempt (D1 §2.3). The PIN is offered only when `KeyInfo` reports secure hardware; a debug-only override exists, and a release test fails if it is on (all three).
- **Charge before checking, and age the charge.**
  - Write the charge durably when the **first digit** is typed (D2, D3).
  - Evaluate only once the charge is at least **1 s** old (D3, invariant I4).
  - Keep the charge as a flag that a refund can clear, separate from the failure count (D2).
  - Recount a leftover charge at start, before any lock screen, and allow no PIN entry until that recount is durable (D2, plus fix 2-9).
  - Port D2's `counter_model.py` and D1's crash-at-every-point invariant as tests.
- **Two limits.**
  - 5 consecutive wrong PINs (all three).
  - At most 10 wrong PINs between two master-password entries (D1).
  - Neither a fingerprint unlock nor a correct PIN restores the 10-budget (D1). A fingerprint unlock does not reset the consecutive count either (D1, D3).
  - At either limit, remove the PIN slot and delete its key. Fingerprint stays (D1, D3).
- **A second budget outside app files.**
  - API 31+: `setMaxUsageCount(6)` with rotation after a correct PIN (D3 §7.4), or D1's single-use pool if real phones declare `FEATURE_KEYSTORE_SINGLE_USE_KEY`.
  - API 24 to 30: delete the key at the limit (all three).
- **`setUnlockedDeviceRequired(true)` on API 35+** for the PIN key (D1, D2), described with the weak-unlock caveat.
- **Exactly 6 digits, auto-submit, and D3's blocklist** (all date orders and years, runs of four, keypad snakes; 12.63 %).
- **`vault_id` / `vk_id` in the header**, so a stale slot never costs an attempt (D1, D3).

### 7.2 The master password (requirements 1 and 7)

- **Typed at that moment for:** exports, backups, setting or changing the PIN, turning fingerprint on, and lengthening any timeout. Making the lock stricter needs nothing (D1 §6.1).
- **Wrong-password waits:** D3's schedule (none for 1 to 5, 30 s for 6 to 10, 5 min after).
  - Measured with the elapsed-only rule of 2.1, and restarted in full after a reboot (D2).
  - Never a lockout.
  - Kept in memory as well when the counter write fails.
- **Forgotten master password:** D1 §6.4, corrected as in 2.1.
  - Every enabled device factor at request and at completion.
  - 72 h of credited time.
  - A banner at every unlock with a Cancel that needs no proof; any correct master password cancels it.
  - Translate the banner into Uzbek and Russian. It is the owner's only defence.
- **Practice reminders** on the wall clock are fine: they are a memory aid, not a limit (all three).

### 7.3 State and errors

- **One device-only record in `noBackupFilesDir`** with magic, length and SHA-256. It is never deleted automatically and fails closed when INVALID or UNREADABLE (D2). Design 3's "PIN not offered when the counter state is unreadable" rule follows the same principle.
- **"Gone" versus "temporary":**
  - API 31+: D1's classifier (null means not found; `UnrecoverableKeyException` without a cause means invalidated).
  - API 24 to 30: D3's canary key.
  - Never `containsAlias()` (all three).
- **"Start over" archives and never deletes.** Opening or deleting an archive needs that copy's master password (D3 §11.20, D2 §9.15).
- **Notices:** failed attempts from earlier lock episodes only (D2 §5.7). Tell the user at setup that anyone whose fingerprint is enrolled on the phone can open the vault (D1 §8.5).

### 7.4 Leave out

- Design 2 §6.2, the instant PIN-only reset.
- Design 3's Argon2 on the PIN path.
- Design 2's reset of the PIN count on fingerprint.
- BOOT_COUNT as a way to *credit* time.

---

## 8. Residual risks the final design should state

- **A phone rooted later.** Without hardware single-use keys, root can defeat keystore2's count and guess at TEE speed: all allowed PINs in about 3 to 9 hours, but only while the phone is unlocked on API 35+ when no weak unlock method is on. The master password alone protects every copy (all three say so).
- **A PIN that is also the phone or bank-card PIN.** It defeats any limit. Say so at PIN setup (all three do).
- **Power-cut durability.** Ageing the charge narrows the gap but cannot close it, because only Gatekeeper's RPMB storage is tamper-proof and apps cannot reach it.
- **API 24 to 30 overlays.** `setHideOverlayWindows` needs API 31, so an app with "display over other apps" can draw a fake pad over ours and read the PIN; `filterTouchesWhenObscured` only blocks taps that pass through.
- **API 24 to 33 accessibility.** `isSensitiveData` takes effect only from API 34, so an accessibility service the user enabled can read pad presses. That is out of scope.
- **Shoulder-surfing.** The keypad is fixed. A cheap help is to show no press highlight on the keys and give haptic feedback only [I].
- **Master-password counters when storage is full.** In Designs 2 and 3 they are best effort when storage is full, and must stay so for requirement 1.
