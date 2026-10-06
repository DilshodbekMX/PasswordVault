# Lock designs judged for data loss and lockout

Judge: recovery lens (data loss and lockout). Written 2026-10-06.

**What I read.** All of `lock-designs/design-1.md` (security first), `design-2.md` (everyday use first) and `design-3.md` (failure and recovery first); `lock-facts/FACTS.md`; `docs/research/VERIFICATION.md`; `docs/design/ux.md` §5.9, §5.10 and §5.24 (the version saved at 13:33, which is newer than the designs); `docs/design/i18n.md` §2.

**Tags.** [C] confirmed today in the named source: AOSP copies under `lock-facts/aosp/` and `lock-designs/d1src/`, `api-versions.xml` of platform 37, and the lab logs under `lab/biometric/prev-run/logs/`. [X] my own experiment today, in `lock-judges/work/budget.py`. [I] my inference.

**What "stranded" means here.** The vault file still exists but nothing the owner has can open it, or the vault has been deleted. All three designs keep the master-password path free of device state, and none writes the vault on a wrong PIN, a lockout or a Keystore error. The differences are around one situation: an owner who has forgotten the master password. For that owner, losing the PIN and the fingerprint means losing everything. Each design's limits, Keystore error rules and reset path decide how often that happens.

**A change since the designs were written.** At 13:33 the UX spec adopted the critic's item C3. "Start over" became "Start with an empty app", which archives the vault, and no path from the lock screen deletes anything. It also recommends keeping the fingerprint after the PIN limit and a reset wait of at most 24 hours. Design 1 had said nothing about "Start over" while the spec still read "Delete and start over"; I rate that gap as it stands now (D1-6).

---

## 1. Verdict

| Rank | Design | Score (0–10, this lens) | Why |
|---|---|---|---|
| 1 | **3** | **7.0** | Every failure mode in its own table has a defined way back that destroys nothing. The fingerprint survives the PIN limit. A forgotten master password can be replaced with the PIN or the fingerprint after 24 hours. "Start over" archives. **Two majors:** its usage-limited PIN key can run out before the screen says it will (D3-1). Nothing protects a master password that contains Uzbek apostrophes when it is typed on another phone's keyboard (D3-2). |
| 2 | **1** | **5.5** | The most careful Keystore error rules and the reset that is hardest to abuse. **But its PIN wears out.** Every unlock spends a key that a background job must replace, and an empty pool is not handled (D1-2). Ten wrong PINs between two master-password entries turn the PIN off, even during the 72-hour reset, a window in which no usable backup can be made (D1-1). |
| 3 | **2** | **4.5** | The sturdiest PIN key (one key that correct use never consumes) and the safest order for a master-password change. **But** five wrong PINs by anyone also block the fingerprint, and only the PIN can authorise a forgotten-password reset (D2-1). That reset is instant for anyone who has seen the PIN (D2-2). On Android 7 to 11, a passing Keystore error deletes the PIN and fingerprint slots (D2-3). |

---

## 2. Claims checked today

| # | Claim | Who relies on it | Result |
|---|---|---|---|
| 1 | On API 24–30, `getKey()` returns null for any Keystore error, not only for a missing key | D1 (corroboration rule), D3 (canary key); D2 assumes the opposite | **Confirmed.** `engineGetKey` returns null when `contains()` is false, and `contains()` is `mBinder.exist(...) == NO_ERROR`, also false on `RemoteException` [C `d1src/AndroidKeyStoreSpi_a11.java:95-103`, `aosp/KeyStore_a11.java:290-297`] |
| 2 | On API 31+, null means only `KEY_NOT_FOUND`. A permanently invalidated key throws `UnrecoverableKeyException` with no cause, and every other failure carries the `KeyStoreException` as cause | D1 relies on it; D2 says the two "cannot be told apart reliably" | **Confirmed**, and keystore1 behaves the same way [C `aosp/AndroidKeyStoreSpi.java:123-140`, `d1src/AndroidKeyStoreProvider.java:431-450`, `AndroidKeyStoreProvider_a11.java:235-250`]. D2's caution is not needed |
| 3 | A key with neither user authentication nor `UnlockedDeviceRequired` is not super-encrypted, so removing the screen lock does not delete it | All three (the PIN key) | **Confirmed**: such keys get `SuperEncryptionType::None` [C `aosp/enforcements.rs:744-778`]. The lab saw the fingerprint key vanish after the screen lock was removed (`getKey returned null, containsAlias=false`) [C `bootB_PVBIO_both_libs.log` 12:01:11]. It did not test a key without authentication |
| 4 | Android 15 fixed two bugs: removing the screen lock deleted unlocked-device-required keys, and such keys could not be used without a screen lock | D1 and D2 (`setUnlockedDeviceRequired` on API 35+) | **Confirmed** [C `aosp/KeyGenParameterSpec.java:1827-1846`] |
| 5 | Every successful `finish()` spends one use of a usage-limited key, whether the PIN was right or wrong. The key is deleted at its last use, and if the decrement fails no output is returned | D1 (pool), D3 (limit of 6) | **Confirmed** [C `aosp/operation.rs:425-458`, `enforcements.rs:316-331`, `database.rs:2303-2337`] |
| 6 | On API 24–30, a missing key at `Mac.init` or `Cipher.init` gives a plain `InvalidKeyException`, not `KeyPermanentlyInvalidatedException` | D3 | **Confirmed** (the default branch) [C `aosp/KeyStore_a11.java:1310-1379`] |
| 7 | `KeyStore.aliases()` returns an empty list when the Keystore fails, so an orphan sweep deletes nothing then | D1, D3 | **Confirmed** [C `aosp/AndroidKeyStoreSpi.java:1138-1150`, `AndroidKeyStoreSpi_a11.java:883-906`] |
| 8 | `KeyStoreException.getRetryPolicy()` says when a reboot will help | D3 §10.3 | **Not for these keys.** `RETRY_AFTER_NEXT_REBOOT` is returned only for remote-key-provisioning states and `OUT_OF_KEYS_REQUIRES_SYSTEM_UPGRADE` [C `aosp/KeyStoreException.java:392-417`] |
| 9 | `isTransientFailure()` must not decide whether a slot is removed | D1 | **Confirmed**: the lab saw `DEVICE_LOCKED` reported with `transient=false` and `retryPolicy=1` (never) [C `bootA_PVBIO_lib1.1.0.log` 11:46:41] |
| 10 | The lock screen's last resort | All three | At 12:21 the UX spec said "Delete and start over". At 13:33 it says "Start with an empty app", which archives, and "No path that starts at the lock screen deletes anything" [C `docs/design/ux.md:470-489`]. D2 and D3 had already chosen archiving; D1 never mentions it (searched) |
| 11 | Uzbek text uses several apostrophe characters | Typing the master password on a new phone | **Confirmed.** U+02BB appears in Android strings added since Android 13, U+2018 in older Android and in Microsoft's guide, and ASCII in KeePassDX and much of Telegram [C `docs/design/i18n.md:216-228`]. NFC does not unify them [FACTS §9] |
| 12 | API levels | All three | `KeyProtection.Builder.setIsStrongBoxBacked` 31, `setMaxUsageCount` 31, `setUnlockedDeviceRequired` 28; `KeyInfo.getRemainingUsageCount` 31; `Settings.Global.BOOT_COUNT` 24; `android:hasFragileUserData` 29 [C `api-versions.xml`] |

---

## 3. Every failure mode, walked through each design

"Stranded" in a cell means the owner can lose all their passwords in that case. Most strandings also need a forgotten master password; the reset paths exist for exactly that combination.

| Failure mode | Design 1 | Design 2 | Design 3 |
|---|---|---|---|
| **Forgotten PIN** | Master password, then a new PIN; the fingerprint works meanwhile. If the master password is forgotten too, the fingerprint can start a reset that completes after 72 h. Without a fingerprint there is no way back | Master password, then a new PIN. If the master password is forgotten too, the reset needs the PIN and the fingerprint cannot authorise it: **stranded**, though the files are archived | Master password, then a new PIN, or the fingerprint and a reset after 24 h. Without a fingerprint: stranded, files archived |
| **Forgotten master password, PIN works** | A reset after 72 h of measured time, proving every enabled secret, with a banner and Cancel. A PIN lockout during the wait strands an owner without a fingerprint, and the 10-failure budget makes that likely (D1-1) | An instant reset with the PIN. Safe for an honest owner; a hostile person who knows the PIN can lock the owner out (D2-2) | A reset after 24 h of measured time with the PIN or the fingerprint, with a banner, Cancel and a last-try warning. A PIN lockout in that window strands an owner without a fingerprint (D3-7) |
| **Too many wrong PINs** | PIN off; fingerprint and master password stay | PIN off **and the fingerprint blocked** until the master password is typed, so an owner who forgot it is **stranded** (D2-1) | PIN slot destroyed; fingerprint and master password stay |
| **Fingerprint added or removed** | Slot removed on `KeyPermanentlyInvalidatedException`; PIN or master password; turn it on again | Same | Same. Phones that report this as a plain `InvalidKeyException` retry silently forever, with no offer to the owner (D3-6) |
| **Screen lock removed** | Fingerprint key deleted and its slot removed; PIN keys survive (claim 3), and E7 tests it | Same, but no test for the PIN key (D2-8) | Same, but no test for the PIN key (D3-8) |
| **Keystore key invalidated or missing** | Removed only on a definite signal; on API 24–30 a null must be corroborated | A null counts as "gone" on every API. On API 24–30 a passing error **deletes the live PIN and fingerprint keys** (D2-3) | A canary key separates "gone" from "failing" on API 24–30. The usage-limited key can run out earlier than the screen shows (D3-1) |
| **Keystore failing temporarily** | Nothing changes, the text advises a restart, and removal is offered after 3 failures across 2 boots | Nothing changes on API 31+; no restart advice (D2-4) | Nothing changes; generic restart advice; its reboot-specific message never fires (D3-5) |
| **OS update** | Keys are expected to survive, but key imports and single-use keys on every unlock add OS-dependent behaviour | One key for the PIN's whole life: the least exposure | A new key is generated after every correct PIN: more exposure than design 2 |
| **App update** | A device file with an unknown version is not used, not changed, and not used to clean up keys (best) | An unknown version is INVALID; master password, then replaced when a PIN is set | A higher version counts as absent; master password, then rebuilt |
| **App data partly restored** | `vault_id` and `vk_id` are checked before an attempt, and a mismatch costs nothing; lock files never travel | `vk_id` checked; the lock record never travels | `vault_id` and `key_id` checked; lock files never travel |
| **Crash or power cut during a write** | Lock file replaced atomically; the attempt is counted before the check. A master-password change sets up the PIN again only after a PIN-entry screen that follows the vault commit (D1-3). Old keys are deleted right after a commit (D1-5) | Lock record replaced atomically, with a SHA-256 check. A master-password change writes the PIN wrap for the new key **before** the vault commit (best). The old key is deleted right after a PIN change (D2-8) | Two atomic lock files and a crash table (7.7); a failed read-back after a master-password change is quarantined. The old key is deleted right after every rotation, which means after every correct PIN (D3-3) |
| **New phone** | Backup plus master password. Proposes folding apostrophes before NFC | Backup plus master password. A hint only (D2-6) | Backup plus master password. **No apostrophe handling**, and master-password waits of up to 5 min also apply when opening the backup (D3-2) |
| **Vault restored from a backup** | Only from onboarding; on a phone that already has a vault, it relies on the UX flow, which now archives | First run; "Start over" archives first | Welcome or "Old locked copies"; opening a copy archives the current vault |
| **Last resort on the lock screen** | Not defined in design 1 (D1-6); the UX spec now archives | Archives | Archives |

---

## 4. Defects

### 4.1 Design 1 (security first)

| # | Severity | Defect and how it fails | Fix |
|---|---|---|---|
| D1-1 | **Major** | **A 10-failure budget that only a typed master password resets makes the PIN brittle and the forgotten-password reset fragile.** A correct PIN or a fingerprint does not reset `fail_since_mp` (5.5), and refills go only to `min(5, 10 − fail_since_mp)` (5.3). [X] An owner who unlocks by PIN 20 times a day with 3 % typos loses PIN unlock before the next 14-day master-password entry in 37 % of periods; at 10 a day with 5 % typos, in 21 %. During the 72-hour reset, reminders are paused and the budget is not reset. An owner who starts at 8 of 10 loses the PIN before the reset completes in 13 % to 46 % of cases (2 % to 5 % typos). Without a fingerprint (every Android 7 or 8 phone on biometric 1.1.0), 6.4 says "there is no reset", so the vault is stranded. The 72 hours are also 72 hours in which no usable backup can be made (UX §5.24). | Drop `FAIL_BUDGET` and keep the limit of 5 in a row. If the security judge wants a long-run cap, let a correct PIN reset it after 24 h, and never apply it while a reset is pending. Shorten the wait to 24 h (design 3, UX §5.24), and let the reset complete without the PIN (§6 item 4). |
| D1-2 | **Major** | **The PIN wears out through correct use, and an empty pool is not handled.** Every correct PIN spends an attempt key; keystore2 deletes it at its last use [C `database.rs:2328-2331`]. A background job after the vault is shown must import a replacement (5.3 step 3), and on any import error the rule is "Refill: keep the smaller pool" (section 10). After 4 wrong PINs and a right one, one failed or killed refill leaves `pin.tokens = []` with `pin_off = null`. `Choosing` then skips the PIN, because it requires keys > 0 (8.2), and shows the master-password screen with no reason and no notice. `attemptPin` meanwhile assumes that "lockOut() never leaves an empty list" (5.2). Separately, a process death between the step-2 commit and the background success commit records a correct PIN as a failure, which uses up D1-1's budget. | Treat an empty pool as `pin_off = keys_lost`, with the notice and "Set a PIN again". When the pool would fall below 2, refill before showing the vault, and commit the success before showing it. Better: use one PIN key that correct use never consumes (design 2). |
| D1-3 | Minor | **A master-password change puts a PIN-entry screen between the vault commit and setting up the PIN again** (F7 steps 3–4). If the screen turns off there (which locks at once by default) or the app dies, the PIN no longer matches the newest generation and the new master password is required. In the forgotten-password reset, that is a password chosen a minute earlier. | Ask for the PIN before step 3, and write the PIN slot for the new vault key before committing the vault (design 2 9.7 step 4). |
| D1-4 | Minor | **"No vault exists" deletes the device file and every `pv.*` key** (F1 step 1). If storage ever reports an unreadable vault as "no vault", the PIN and fingerprint are destroyed. | Count only ENOENT on the vault directory as "no vault". Send an unreadable vault to the storage error screen (design 3 10.1 step 2), and delete old lock state only after a new vault is committed. |
| D1-5 | Minor | **Replaced keys are deleted straight after a commit** (3.3, "after every commit that removes slots"). Because `/data` can be mounted `nobarrier` (V-S8), a power cut can lose the device-file write but keep the Keystore deletion. The file that survives then points at deleted keys, and the PIN turns off. | Delete replaced keys only in a sweep at a later process start, after the device file that replaced them has been read back from disk. |
| D1-6 | Minor | **The design says nothing about the lock screen's last resort.** 6.4 stops at "If neither is enabled, there is no reset", leaving the sheet to a UX spec that at the time read "Delete and start over". The UX revision of 13:33 now archives, so together they are safe. But F1 step 1 shows that design 1 assumes "no vault" can simply be cleaned up. | State archiving as a lock invariant, as design 3 does (I1, I9, 11.20), and own the archive's lock state (UX §5.10: "owners: storage and lock designs"). |

### 4.2 Design 2 (everyday use first)

| # | Severity | Defect and how it fails | Fix |
|---|---|---|---|
| D2-1 | **Critical** | **Five wrong PINs by anyone also block the fingerprint, and only the PIN can authorise a forgotten-password reset.** At the limit, "Fingerprint is not offered until a master-password unlock" (5.1; `need_master` in 8.1). The reset is offered only "whenever a PIN slot is usable" (6.2); otherwise only "try again, restore an older backup, or Start over". Example: an owner who has forgotten the master password and uses a fingerprint lends the phone to a child, who types 5 wrong PINs. The PIN key is deleted, the working fingerprint is blocked, and no reset exists. The same happens after the practice grace for an owner with a fingerprint and no PIN, because the after-grace row of 8.1 comes first. Design 3 rejects this rule for exactly this reason (4.6), and the UX spec now recommends against it (ux.md §5.9, recommendation 1). | Keep the fingerprint usable after the PIN limit (design 1 5.4, design 3 7.3), and let a fingerprint unlock authorise the forgotten-password reset. |
| D2-2 | **Critical** | **The reset is instant with the PIN alone, and the owner is never told.** Anyone who has seen the PIN can do this; design 2 itself notes that family members often know the phone's PIN. They replace the master password (6.2), restart the app and unlock once with the new password, which lets storage retire the generations under the owner's password (9.7 step 9, V-S5). Then they type 5 wrong PINs. `need_master` now blocks the owner's fingerprint too, and the owner's master password opens nothing on the phone. Design 2 states this cost (6.2) but adds no wait, no notice and no retention. | Add design 3's 24-hour wait in measured time, with a Cancel banner at every unlock; any correct old master password cancels it. After a reset (not a normal change), keep the old-password generations for at least 30 days and try the old password against them (V-S3). Show "Your master password was replaced on <date> using the PIN" at the following unlocks. |
| D2-3 | **Major** | **On Android 7 to 11, a passing Keystore error deletes the PIN and fingerprint slots.** Section 10 and 9.2 step 2 treat `getKey() == null` as GONE on every API level, citing only keystore2. On API 24–30, null also means "daemon unreachable" or any error code (claim 1). So a keystore restart or an I/O error at the lock screen writes `pin = null`, deletes the live key (9.13b), and does the same to the fingerprint slot (8.4 step 1). On Android 7 and 8 the PIN is the only device route, since biometric 1.1.0 gives no fingerprint there. An owner who forgot the master password is stranded by a passing fault. | On API 24–30, count a null as "gone" only when corroborated: design 1's rule (another of our keys loads in the same call, or the key is still missing after a reboot) or design 3's canary key. An uncorroborated null is TEMPORARY. |
| D2-4 | Minor | The temporary-failure text (8.2) does not advise a restart. On the Xiaomi phone that fails every Keystore call until it reboots, that advice is what gets an owner who forgot the master password back in. | Add "Restarting the phone usually fixes this." (design 1 8.2; UX §5.9 now has it). |
| D2-5 | Minor | One lock record holds the PIN slot, the fingerprint slot and the counters, so an INVALID record disables both device routes at once. | Split slots from counters (design 3 5.3/5.4), and keep design 2's magic, length and SHA-256 framing on both files. |
| D2-6 | Minor | The Uzbek-apostrophe problem gets only a hint (8.2) and is left as an open question (14.3). | Fold the variants before NFC from the first release (design 1 6.3), and keep the hint. |
| D2-7 | Minor | It is unclear whether a fingerprint unlock may set a new PIN once the PIN key is gone: 6.1 says "then Choose a PIN", while 6.3 lists only "current PIN, or master password". Because of D2-1, this decides whether an owner who forgot the master password can rebuild the PIN the reset needs. | State it, and allow it. |
| D2-8 | Minor | No test checks that the PIN key survives removal of the screen lock; E7 checks only the fingerprint. The old Kpin is deleted straight after the PIN-change write (the same `nobarrier` window as D1-5). | Add the test (design 1 E7), and move the deletion to a sweep at a later start. |

### 4.3 Design 3 (failure and recovery first)

| # | Severity | Defect and how it fails | Fix |
|---|---|---|---|
| D3-1 | **Major** | **The usage-limited PIN key can run out before the screen says so.** K_pin has `setMaxUsageCount(6)`, and a background job replaces it after each correct PIN (7.4). When that rotation fails or the process dies, "the old slot" stays with fewer uses than the screen implies. Every successful HMAC spends a use, whether the PIN was right or wrong (claim 5). Example: 2 wrong PINs, then a right one whose rotation is interrupted, leave the key 2 uses while the screen will say "5 tries left". Two typos the next day delete the key, and the next entry gets GONE: "PIN unlock stopped working". The last-try warning for a pending master-password replacement (10.3) counts `pin_failures`, so it cannot warn. An owner without a fingerprint who is waiting out the 24-hour replacement is stranded. | Drop the usage count. As design 2 13.4 argues, counts above 1 are enforced by keystore2 in software, which root can patch, and the app's own counter already handles a thief without root. If it is kept, store the uses spent in `state.json`, show `min(5 − pin_failures, uses left)`, and rotate before showing the vault when 2 or fewer uses remain. |
| D3-2 | **Major** | **Nothing protects a master password with Uzbek apostrophes typed on another keyboard.** NFC does not unify U+2018, U+02BB, U+02BC, U+2019 and U+0027 [FACTS §9], and Uzbek text in use mixes them (claim 11). A password such as "Toshkentdagi oʻgʻlim", typed on a new phone's keyboard, derives a different key, and the backup does not open; requirement 1's "on any phone" fails. Design 3 also applies its master-password waits when opening a backup (11.14 step 2), so after 10 wrong tries each try costs 5 minutes. | Fold apostrophe look-alikes to one character before NFC from the first release, with test vectors (design 1 6.3; a crypto change). Show the keyboard and apostrophe hints (designs 1 and 2). Apply no waits when opening a backup file (design 2 5.6). |
| D3-3 | Minor | Rotation deletes the old key straight after writing `slots.json` (7.4 step 4), after every correct PIN. If `/data` is mounted `nobarrier`, a power cut can lose the `slots.json` write but keep the deletion, and the PIN slot becomes GONE. | Leave the old key to the orphan sweep at a later process start. |
| D3-4 | Minor | Master-password waits reach 5 minutes after 11 failures (7.6). This is not a lockout, but an owner confused by keyboard language or apostrophes may give up. | Cap the wait at 30 s (design 2 5.6). |
| D3-5 | Minor | The message "Restart your phone to use your PIN again", keyed on `RETRY_AFTER_NEXT_REBOOT` (10.3), will practically never show (claim 8). | Show the restart advice after repeated temporary failures within one boot. |
| D3-6 | Minor | On API 31+, a manufacturer that reports an invalidated fingerprint key as a plain `InvalidKeyException` leaves the slot TEMPORARY forever. 11.18 only retries at the next lock, and nothing tells the owner to turn fingerprint on again. | Offer to set it up again after failures on 2 or more boots (design 1 8.4, design 2 9.13c). |
| D3-7 | Minor | During the 24-hour replacement, a PIN lockout strands an owner without a fingerprint. The 10.3 warning reduces this risk but does not remove it. | Choose the new master password at request time, and keep it as a device-only pending slot that opens on its own after the wait (§6 item 4). |
| D3-8 | Minor | No test checks that the PIN key survives removal of the screen lock; E6 checks only the fingerprint. | Add the test (design 1 E7). |

---

## 5. Ideas the final design should take

### From design 1
- **Its Keystore error rules.** An `UnrecoverableKeyException` without a cause means the key is permanently invalidated; I checked this on keystore1 and keystore2. On API 24–30 a null from `getKey()` is "gone" only when corroborated. `isTransientFailure()` is never used for a decision, because `DEVICE_LOCKED` reports itself as non-transient.
- `vault_id` and `vk_id` are checked before an attempt is spent; a mismatch costs nothing and never deletes a slot.
- The reset's waiting time uses `BOOT_COUNT` and `elapsedRealtime`, so a reboot only lengthens it. The reset has a Cancel banner at every unlock, any correct master password cancels it, and reminders pause while it is pending.
- Fold apostrophe look-alikes (U+0027, U+0060, U+00B4, U+2018, U+2019, U+02BB, U+02BC) before NFC from the first release, with test vectors. Add the hint about Latin or Cyrillic keyboards and the ‘ sign.
- The master-password path reads no device state, needs no successful write, and is never locked out.
- A lock file with an unknown format or version is neither changed nor used to clean up keys, because a newer app version may own them.
- Temporary Keystore failures change nothing. After 3 failures across 2 boots, removal is offered to the owner, never forced.
- Test E7: the PIN keys still work after fingerprints change and after the screen lock is removed. Setup text: never use "Clear storage" to fix lock problems.

### From design 2
- **One long-lived Keystore HMAC key per PIN, with no usage count**, so correct use never consumes the PIN.
- **The master-password change order.** The PIN wrap for the new vault key is written before the new generation is committed. Wraps for up to two `vk_id`s are kept until storage retires the old generations.
- The same wrong PIN typed again within 5 minutes is not counted (kept in memory only), which avoids accidental lockouts.
- A fingerprint success resets the count of consecutive failures, giving fewer accidental lockouts. This is subject to the security judge, since design 3 argues against it.
- Lock-record framing (magic, length, SHA-256). An INVALID record is replaced after a master-password unlock; an UNREADABLE record is never rewritten and means "master password this time".
- The master-password pause is at most 30 s and never applies when opening a backup file.
- "Start over" archives, and an archived copy opens or is deleted only with that copy's master password.
- Test T13: an architecture test that forbids the lock package from importing the vault writer. Test E9: backup and restore through `bmgr` never bring the lock record back.
- `trouble_boots`: offer to set a secret up again after temporary failures on two different boots.

### From design 3
- The failure table in section 2 as the acceptance checklist, invariants I1–I9 as tests, and crash injection at every write boundary (T4).
- The fingerprint stays usable after the PIN limit, which never touches the fingerprint slot.
- A forgotten master password can be replaced with the PIN or the fingerprint after 24 hours of measured time. A Cancel banner shows at every unlock, any correct master password cancels the request, practice prompts pause while it is pending, and the last-try wording changes while it is pending.
- "Start over" atomically renames the vault directory to an archive and never deletes. "Old locked copies" are listed, and opening one archives the current vault first.
- A canary key on API 24–30 tells "this key is gone" from "the Keystore is failing".
- Slots and counters live in separate files. A damaged counter file disables only the PIN (it fails closed), and the fingerprint still works.
- If the read-back after a master-password change fails, the new file is quarantined, never deleted, and the user is told the old password still works.
- "No generation readable" leads to a storage error screen, separate from "no vault file", and never to an offer to delete.
- "Wrong master password" is reported only after every retained generation's slot has been tried (V-S3).
- The orphan sweep works only from a lock file read successfully in this process, and an empty `aliases()` deletes nothing.

---

## 6. Recommended recovery shape for the final design

1. **Base it on design 3's structure:** the failure table, invariants, split lock files, crash table, archive, canary key and quarantine.
2. **The PIN key is design 2's**: one HMAC key that correct use never consumes, with no pool (design 1) and no usage-limited rotation (design 3). If the security judge insists on usage limits, store and display the remaining uses, and replace the key before showing the vault when it is about to run out (D3-1 fix).
3. **PIN limit:** 5 wrong in a row. It never blocks the fingerprint (designs 1 and 3, UX §5.9). There is no budget that only the master password resets (D1-1). An identical wrong PIN within 5 minutes is not counted (design 2).
4. **Forgotten master password:**
   - The PIN or the fingerprint can request the reset, and it waits at most 24 h of measured time, with a Cancel banner; any correct old master password cancels it.
   - The new password is chosen at request time and held as a device-only pending password slot (Argon2id with the vault's parameters, in `noBackupFilesDir`). It opens the vault on its own once the wait is over, so a PIN lockout during the wait cannot strand the owner. Cancelling deletes the slot.
   - After completion, keep the old-password generations for at least 30 days, keep trying the old password against them, and say "replaced on <date> using the PIN". This protects an owner against a hostile reset.
   - While the reset is pending, the last-try warning says so (design 3 10.3).
5. **Keystore errors:** design 1's rules, with design 3's canary as the corroboration on API 24–30. Removal is offered after failures on two boots, never forced.
6. **Master-password change:** design 2's order (device wraps for the new key before the vault commit), and design 3's quarantine if the read-back fails.
7. **Lock files:** design 3's split with design 2's framing, and the INVALID versus UNREADABLE distinction (design 2). A file with an unknown version is never changed or used for clean-up (design 1).
8. **Keystore keys replaced by a lock-file write** are deleted only by a sweep at a later process start, after that file has been read back.
9. **Master-password entry:** fold apostrophe variants before NFC from release 1 (a crypto change, from design 1). Add the Latin/Cyrillic and apostrophe hints. Pause at most 30 s, and never when opening a backup.
10. **Tests:**
    - design 3's T4 crash injection;
    - design 2's T13 architecture test;
    - "the PIN key survives removal of the screen lock" on API 24, 28, 30, 35 and 37;
    - design 2's E9;
    - a fake Keystore that returns null for live keys on API 24–30.

## 7. Risks no design removes

- **A forgotten master password with no working PIN or fingerprint.** This cannot be recovered. The archive keeps the files in case the password comes back, and practice prompts make it rarer.
- **A phone lost or reset** with no backup, or with backups only under a forgotten master password.
- **Uninstalling the app or "Clear storage"** deletes the vault and the keys. From API 29, `hasFragileUserData` offers to keep the data on uninstall; help text should warn about both.
- **No usable backup can be made during a forgotten-password wait** (UX §5.24), so the wait must stay short.
- **`nobarrier` storage can lose a lock-file write.** Atomic replacement limits the result to "old or new", and every design treats a bad lock file as "master password this time".
- **Manufacturer Keystores** that report a live key as missing (API 24–30) or a dead key as a temporary error. With the rules above, both end at the master password and destroy nothing.

---

## Appendix A. Simulation of design 1's failure budget [X]

`lock-judges/work/budget.py` (Monte Carlo, 20,000 runs per cell). The owner unlocks by PIN `U` times a day and mistypes each entry with probability `p`, retyping until right. Design 1's rules apply: the PIN turns off at 5 wrong in a row or at 10 wrong since the last master-password entry. The master password is typed every 14 days.

**Chance that PIN unlock turns off before the next master-password entry (14 days):**

| PIN unlocks a day | 1 % typos | 2 % typos | 3 % typos | 5 % typos |
|---|---|---|---|---|
| 5 | 0.000 | 0.000 | 0.000 | 0.005 |
| 10 | 0.000 | 0.001 | 0.015 | 0.210 |
| 20 | 0.001 | 0.069 | 0.370 | 0.919 |

**Chance that the PIN turns off during the 72-hour reset** (10 PIN unlocks a day; k = failures already counted when the reset starts):

| Typos | k = 0 | k = 3 | k = 6 | k = 8 |
|---|---|---|---|---|
| 2 % | 0.00 | 0.00 | 0.00 | 0.13 |
| 3 % | 0.00 | 0.00 | 0.02 | 0.24 |
| 5 % | 0.00 | 0.00 | 0.08 | 0.46 |

The typo rates are assumptions, not measurements. The point is the shape: a budget that only the master password resets turns the PIN off for honest heavy users, and it is most dangerous exactly while an owner who forgot the master password waits for the reset.
