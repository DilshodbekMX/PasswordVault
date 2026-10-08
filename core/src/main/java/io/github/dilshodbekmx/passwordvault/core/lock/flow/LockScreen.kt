package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut

/**
 * What the lock screen shows while the app is locked (lock.md §13.2; architecture.md §3.5),
 * chosen by `EntryPolicy` (lock.md §13.1). It holds facts and numbers, never a sentence: the lock
 * screens (packages 3.4 and 4.2) pick the English texts of lock.md §13.3, and the plurals, from
 * them, so that the language can change under it (i18n.md rule 2). Every text row of §13.3 is
 * reached by some value of these types.
 */
sealed interface LockScreen {
    /**
     * The line of a vault put aside, whose own lock screen this is (lock.md F17 step 4, F16b); null
     * on the current vault's screens.
     */
    val aside: AsideLine?

    /**
     * The PIN pad (§13.1 row 5): [digits] typed so far (0 to 6; never which), the status line, the
     * fingerprint key when fingerprint is usable, and the progress in place of the keypad while a
     * PIN is checked ([opening]). Where a status text differs with and without fingerprint, the
     * screen says "fingerprint or master password" when [fingerprintKey] is shown.
     */
    data class Pad(
        val digits: Int,
        val status: PadStatus?,
        val fingerprintKey: Boolean,
        val opening: Boolean,
        override val aside: AsideLine?,
    ) : LockScreen

    /**
     * Only fingerprint is usable (§13.1 row 6): no pad and no dots. [status] is one of the
     * [FingerprintLine]s, said with "Use your master password" in place of "Use your PIN";
     * [pinReason] is the line about why there is no pad, null when no PIN was ever set.
     */
    data class FingerprintOnly(val status: PadStatus?, val pinReason: PinReason?, override val aside: AsideLine?) : LockScreen

    /**
     * The master-password screen (§13.1 rows 3, 4 and 7; §13.3).
     *
     * @property reason the subtitle, the highest that applies in the table of §13.3; null when the
     *   user chose this screen or no PIN was ever set.
     * @property canUsePin "Use PIN" ("Not now: use PIN" on a practice day in grace) is offered.
     * @property practice set on a practice screen, which also offers "I can't remember it".
     * @property waitUntilElapsedMs while set, the field is disabled and counts down to this
     *   `elapsedRealtime()` (§6.8): "Wait 30 seconds, then try again. …"
     * @property error the line after the last try.
     * @property pendingReadyInMs a new master password is pending and not ready: "Your new master
     *   password starts working in about N hours." (F11 step 12).
     * @property opening "Opening…" while Argon2id runs.
     * @property fingerprintLine fingerprint unlock is on and cannot be used now: the matching line of
     *   the pad, with "Use your master password", shown under the reason (§13.3).
     */
    data class MasterPassword(
        val reason: MpReason?,
        val canUsePin: Boolean,
        val practice: Practice?,
        val waitUntilElapsedMs: Long?,
        val error: MpError?,
        val pendingReadyInMs: Long?,
        val opening: Boolean,
        override val aside: AsideLine?,
        val fingerprintLine: FingerprintLine? = null,
    ) : LockScreen

    /**
     * "This is an older master password" (lock.md F16b): the typed password opens only a copy from
     * before a change. [changedAtMs] is the date of the change. With [unfinishedChange] (`mp_change`
     * is set) the screen offers "Try again" and "Open my passwords from before the change";
     * otherwise only "OK".
     */
    data class OlderPassword(val changedAtMs: Long, val unfinishedChange: Boolean, override val aside: AsideLine? = null) : LockScreen

    /**
     * "Open the older set instead?" (lock.md F16b step 3, after a replacement): the master password of
     * a vault from before a replacement has just opened it in place, on that vault's own lock screen,
     * and the app asks before the two vaults change places: "These are your passwords as they were
     * on 7 Oct. Passwords added or changed after that are in the newer set, which stays on this
     * phone. Open the older set instead?" The buttons are "Open my passwords from 7 Oct"
     * ([OlderPasswordAnswer.OPEN_OLDER_SET]: steps 4 to 6 follow) and "Cancel"
     * ([OlderPasswordAnswer.KEEP_NEWER_SET]: nothing changes). [atMs] is when the master password was
     * replaced, the date both sentences name. `EntryPolicy` never chooses this screen: it is shown
     * while `LockVisit.olderSet` is set.
     */
    data class OpenOlderSet(val atMs: Long, override val aside: AsideLine?) : LockScreen
}

/** The line on the lock screen of a vault put aside (lock.md §13.2, F17 step 4, F16b). */
sealed interface AsideLine {
    /** "Passwords locked on 6 Oct 2026": [atMs] is when it was put aside. */
    data class LockedOn(val atMs: Long) : AsideLine

    /** "Your passwords from before 7 Oct 2026": a vault marked `replaced`; [atMs] is when it was replaced. */
    data class FromBefore(val atMs: Long) : AsideLine
}

/**
 * The status line of the pad (lock.md §13.3, "On the pad"). A line about a wrong PIN or about tries
 * left stays until the visit ends (§6.4). The [FingerprintLine]s are also the status of the
 * fingerprint-only screen and the line under the reason on the master-password screen.
 */
sealed interface PadStatus {
    /** A fresh pad with 2 or fewer tries left: "2 tries left before the PIN turns off." */
    data class TriesLeft(val left: Int) : PadStatus

    /** A wrong PIN with 2 or more tries left: "Wrong PIN. 4 tries left." */
    data class WrongPin(val left: Int) : PadStatus

    /** A wrong PIN with 1 try left: "Wrong PIN. 1 try left. After that you'll need your fingerprint or master password." */
    data object LastTry : PadStatus

    /** The same, while a new master password is not working yet (F11 step 11): "Careful: your new master password is not working yet. …" */
    data object LastTryWhilePending : PadStatus

    /** The same wrong PIN again (§6.6): "You already tried that PIN. It was not counted again." */
    data object SameAgain : PadStatus

    /** A try was counted at start (§6.3 S): "Your last PIN entry was interrupted and counted as a try. 4 tries left." */
    data class Interrupted(val left: Int) : PadStatus

    /** The charge could not be written (§6.3 C.3): "Your phone's storage is full, so the PIN can't be checked now. …" */
    data object StorageFull : PadStatus
}

/** The fingerprint lines of lock.md §13.3; each also says "Use your PIN" or "Use your master password". */
sealed interface FingerprintLine : PadStatus {
    /** The key was invalidated (F15a): "Fingerprint unlock was turned off because this phone's fingerprints or screen lock changed. …" */
    data object TurnedOff : FingerprintLine

    /**
     * `fingerprint.rec` reads DAMAGED (§5.3; build-plan.md §12, decision 1): "Fingerprint unlock
     * stopped working on this phone. … You can turn it on again in Settings." Which key it named
     * cannot be known, so the phone's fingerprints are not blamed, as [TurnedOff] does.
     */
    data object StoppedWorking : FingerprintLine

    /** Locked by the system (codes 7, 9): "Fingerprint is locked for now." */
    data object Locked : FingerprintLine

    /** The prompt succeeded but did not open (§9.3): "Fingerprint did not open the app this time. Enter your app PIN." */
    data object DidNotOpen : FingerprintLine

    /** `canAuthenticate` 1, or the prompt ended with error 1: "Fingerprint can't be used in apps right now. …" */
    data object NotForApps : FingerprintLine

    /** `canAuthenticate` 11: "No fingerprint is saved on this phone now. …" */
    data object NoneSaved : FingerprintLine

    /** `canAuthenticate` 15: "Your phone needs a security update before fingerprint can be used." */
    data object UpdateNeeded : FingerprintLine

    /** Any other fingerprint error or `canAuthenticate` value; the key is hidden until the next visit: "Fingerprint can't be checked right now. …" */
    data object CannotCheck : FingerprintLine
}

/**
 * Why the PIN cannot be used now (lock.md §13.3): the line of the fingerprint-only screen, and,
 * through [MpReason.Pin], the reasons of the master-password screen.
 */
enum class PinReason {
    /** Turned off after too many wrong tries (§6.5). */
    LIMIT,

    /** The Keystore is not answering, or the record cannot be read right now (F15c, §5.3 UNREADABLE). */
    CANNOT_CHECK,

    /** The key is gone, or the record is damaged (F15b, §5.3 DAMAGED). */
    STOPPED_WORKING,

    /** The start-up write of §6.3 S failed. */
    STORAGE_FULL,

    /** The record was written by a newer version of the app (§5.3 NEWER). */
    NEWER_APP,
}

/** The subtitle of the master-password screen (lock.md §13.3), listed from the highest to the lowest. */
sealed interface MpReason {
    /** "Time to practise your master password. …" */
    data object PracticeDue : MpReason

    /** No lock records for this vault (F14): the title "Welcome back". */
    data object WelcomeBack : MpReason

    /** A vault from before a replacement (F16b): the title "Your passwords from before 7 Oct 2026"; [atMs] is the date. */
    data class FromBefore(val atMs: Long) : MpReason

    /** The PIN states: turned off with no fingerprint, stopped working, set up by a newer app, cannot be checked, storage full. */
    data class Pin(val reason: PinReason) : MpReason

    /** The master password was changed and the PIN was not kept: "You changed your master password. …" */
    data object MasterChanged : MpReason

    /** An older copy with another key (F16a): "… An older copy from 5 Oct opens with the master password you used then."; [createdMs] is its date. */
    data class OlderCopy(val createdMs: Long) : MpReason

    /** No secure hardware (F18), the first time only. */
    data object NoSecureHardware : MpReason
}

/**
 * A practice screen (lock.md §8.3, F10). [notNow] is the shortcut of "Not now: use PIN" (or "…use
 * fingerprint") while practice is within its grace; null when it is past its grace and the master
 * password is required.
 */
data class Practice(val notNow: Shortcut?)

/** The line after a master-password try (lock.md §13.3, "When"). */
sealed interface MpError {
    /**
     * "That's not the right master password. Check the keyboard language …". [offerForgot]: wrong
     * twice while a shortcut is usable, so it adds "Can't remember it? …" with "I forgot it".
     * [replaced]: the passwords from before a replacement are on the phone.
     */
    data class Wrong(val offerForgot: Boolean, val replaced: ReplacedLine?) : MpError

    /** "Your phone doesn't have enough free memory to open your passwords. …" with "Try again". */
    data object NoMemory : MpError
}

/**
 * "The master password was replaced on 7 Oct using the PIN. If you didn't do that, open your
 * passwords from before." with "Open my passwords from before 7 Oct" (lock.md F16b). [oldVaultId]
 * is the vault that button opens, or null when the master password was replaced more than once and
 * the button opens the list of the "Forgot master password?" sheet; [atMs] and [by] are those of
 * the latest replacement.
 */
data class ReplacedLine(val atMs: Long, val by: Shortcut, val oldVaultId: String?)
