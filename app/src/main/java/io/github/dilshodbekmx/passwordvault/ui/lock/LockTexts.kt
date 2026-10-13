package io.github.dilshodbekmx.passwordvault.ui.lock

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice

/**
 * The English texts of lock.md §13.3 as resources, picked from the lock's facts (`LockScreen`,
 * `PadStatus`, `PinReason`, `MpReason`): the lock holds facts and numbers, never a sentence, so that
 * the language can change under it (i18n.md rule 2). Each function covers every case of its type, so
 * a new case does not compile until it has its text.
 */
internal object LockTexts {

    /** The line of a vault put aside (lock.md §13.2, F17 step 4, F16b). */
    fun asideLine(aside: AsideLine): UiText = when (aside) {
        is AsideLine.LockedOn -> UiText.of(R.string.lock_aside_locked_on, LockDate(aside.atMs, LockDate.Style.DATE))
        is AsideLine.FromBefore -> UiText.of(R.string.lock_from_before, LockDate(aside.atMs, LockDate.Style.DATE))
    }

    // ---- The pad (lock.md §13.3, "On the pad") -------------------------------------------------

    /**
     * The status line of the pad for [status]. Where a text differs with and without fingerprint, it
     * says "fingerprint or master password" when the fingerprint key is shown ([fingerprint],
     * `LockScreen.Pad`'s KDoc). The fingerprint lines say "Use your PIN".
     */
    fun padStatus(status: PadStatus, fingerprint: Boolean): UiText = when (status) {
        is PadStatus.TriesLeft -> UiText.plural(R.plurals.lock_pad_tries_left, status.left)
        is PadStatus.WrongPin -> UiText.plural(R.plurals.lock_pad_wrong_pin, status.left)
        PadStatus.LastTry -> UiText.of(if (fingerprint) R.string.lock_pad_last_try_fingerprint else R.string.lock_pad_last_try)
        PadStatus.LastTryWhilePending -> UiText.of(R.string.lock_pad_last_try_pending)
        PadStatus.SameAgain -> UiText.of(R.string.lock_pad_same_again)
        is PadStatus.Interrupted -> UiText.plural(R.plurals.lock_pad_interrupted, status.left)
        PadStatus.StorageFull -> UiText.of(if (fingerprint) R.string.lock_pad_storage_full_fingerprint else R.string.lock_pad_storage_full)
        is FingerprintLine -> fingerprintWithPin(status)
    }

    /** Whether the status line reports something that went wrong with the PIN (drawn as an alert); the fingerprint lines do not. */
    fun padAlert(status: PadStatus): Boolean = status !is FingerprintLine

    /** A wrong PIN was just answered: the dots shake (lock.md F4 step 2). */
    fun isWrongPin(status: PadStatus?): Boolean = when (status) {
        is PadStatus.WrongPin, PadStatus.LastTry, PadStatus.LastTryWhilePending, PadStatus.SameAgain -> true
        else -> false
    }

    /** A fingerprint line of the pad, which says "Use your PIN" (lock.md §13.3). */
    fun fingerprintWithPin(line: FingerprintLine): UiText = UiText.of(
        when (line) {
            FingerprintLine.TurnedOff -> R.string.lock_fingerprint_turned_off_pin
            FingerprintLine.StoppedWorking -> R.string.lock_fingerprint_stopped_working_pin
            FingerprintLine.Locked -> R.string.lock_fingerprint_locked_pin
            FingerprintLine.DidNotOpen -> R.string.lock_fingerprint_did_not_open_pin
            FingerprintLine.NotForApps -> R.string.lock_fingerprint_not_for_apps_pin
            FingerprintLine.NoneSaved -> R.string.lock_fingerprint_none_saved_pin
            FingerprintLine.UpdateNeeded -> R.string.lock_fingerprint_update_needed_pin
            FingerprintLine.CannotCheck -> R.string.lock_fingerprint_cannot_check_pin
        },
    )

    /**
     * A fingerprint line with "Use your master password" in place of "Use your PIN": the status of
     * the fingerprint-only screen and the line under the reason on the master-password screen
     * (lock.md §13.3).
     */
    fun fingerprintWithMasterPassword(line: FingerprintLine): UiText = UiText.of(
        when (line) {
            FingerprintLine.TurnedOff -> R.string.lock_fingerprint_turned_off_master
            FingerprintLine.StoppedWorking -> R.string.lock_fingerprint_stopped_working_master
            FingerprintLine.Locked -> R.string.lock_fingerprint_locked_master
            FingerprintLine.DidNotOpen -> R.string.lock_fingerprint_did_not_open_master
            FingerprintLine.NotForApps -> R.string.lock_fingerprint_not_for_apps_master
            FingerprintLine.NoneSaved -> R.string.lock_fingerprint_none_saved_master
            FingerprintLine.UpdateNeeded -> R.string.lock_fingerprint_update_needed_master
            FingerprintLine.CannotCheck -> R.string.lock_fingerprint_cannot_check_master
        },
    )

    // ---- The fingerprint-only screen ------------------------------------------------------------

    /**
     * The status of the fingerprint-only screen: one of the fingerprint lines, said with "Use your
     * master password" (`LockScreen.FingerprintOnly`). Any other status, which the lock does not give
     * there, reads as on a pad with the fingerprint key.
     */
    fun fingerprintOnlyStatus(status: PadStatus): UiText =
        if (status is FingerprintLine) fingerprintWithMasterPassword(status) else padStatus(status, fingerprint = true)

    /** Why there is no pad on the fingerprint-only screen (lock.md §13.3, its second table). */
    fun fingerprintOnlyPinReason(reason: PinReason): UiText = UiText.of(
        when (reason) {
            PinReason.LIMIT -> R.string.lock_fingerprint_only_pin_limit
            PinReason.CANNOT_CHECK -> R.string.lock_fingerprint_only_pin_cannot_check
            PinReason.STOPPED_WORKING -> R.string.lock_fingerprint_only_pin_stopped_working
            PinReason.STORAGE_FULL -> R.string.lock_fingerprint_only_pin_storage_full
            PinReason.NEWER_APP -> R.string.lock_fingerprint_only_pin_newer_app
        },
    )

    // ---- The master-password screen -------------------------------------------------------------

    fun masterPasswordTitle(reason: MpReason?): UiText = when (reason) {
        MpReason.WelcomeBack -> UiText.of(R.string.lock_mp_title_welcome_back)
        is MpReason.FromBefore -> UiText.of(R.string.lock_from_before, LockDate(reason.atMs, LockDate.Style.DATE))
        else -> UiText.of(R.string.lock_mp_title)
    }

    /** The subtitle of the master-password screen (lock.md §13.3, "the reason"). */
    fun masterPasswordReason(reason: MpReason): UiText = when (reason) {
        MpReason.PracticeDue -> UiText.of(R.string.lock_mp_reason_practice)
        MpReason.WelcomeBack -> UiText.of(R.string.lock_mp_reason_welcome_back)
        is MpReason.FromBefore -> UiText.of(R.string.lock_mp_reason_from_before)
        is MpReason.Pin -> UiText.of(
            when (reason.reason) {
                PinReason.LIMIT -> R.string.lock_mp_reason_pin_limit
                PinReason.CANNOT_CHECK -> R.string.lock_mp_reason_pin_cannot_check
                PinReason.STOPPED_WORKING -> R.string.lock_mp_reason_pin_stopped_working
                PinReason.STORAGE_FULL -> R.string.lock_mp_reason_pin_storage_full
                PinReason.NEWER_APP -> R.string.lock_mp_reason_pin_newer_app
            },
        )
        MpReason.MasterChanged -> UiText.of(R.string.lock_mp_reason_master_changed)
        is MpReason.OlderCopy -> UiText.of(R.string.lock_mp_reason_older_copy, LockDate(reason.createdMs, LockDate.Style.DAY_MONTH))
        MpReason.NoSecureHardware -> UiText.of(R.string.lock_mp_reason_no_secure_hardware)
    }

    // ---- "This is an older master password" (F16b) ---------------------------------------------

    /** The proven change ("You changed your master password on 7 Oct. Type the new one.") or the one that may not have finished. */
    fun olderPassword(changedAtMs: Long, unfinishedChange: Boolean): UiText {
        val day = LockDate(changedAtMs, LockDate.Style.DAY_MONTH)
        return UiText.of(if (unfinishedChange) R.string.lock_older_unfinished else R.string.lock_older_proven, day)
    }

    // ---- "Open the older set instead?" (F16b step 3) ---------------------------------------------

    /**
     * The title over the question: "Your passwords from before 7 Oct 2026", as on the master-password
     * field the question follows ([masterPasswordTitle] for `MpReason.FromBefore`); [atMs] is when
     * the master password was replaced.
     */
    fun olderSetTitle(atMs: Long): UiText = UiText.of(R.string.lock_from_before, LockDate(atMs, LockDate.Style.DATE))

    /** "These are your passwords as they were on 7 Oct. … Open the older set instead?" */
    fun olderSet(atMs: Long): UiText = UiText.of(R.string.lock_older_set_text, LockDate(atMs, LockDate.Style.DAY_MONTH))

    /** The button "Open my passwords from 7 Oct". */
    fun olderSetOpen(atMs: Long): UiText = UiText.of(R.string.lock_older_set_open, LockDate(atMs, LockDate.Style.DAY_MONTH))

    /**
     * "Couldn't open your passwords from 7 Oct. Nothing was changed.": that button was tapped and the
     * vaults could not change places (`MpError.OlderSetNotOpened`, lock.md §13.3; build-plan.md §12,
     * decision 17). The date is the button's.
     */
    fun olderSetNotOpened(atMs: Long): UiText = UiText.of(R.string.lock_mp_older_set_not_opened, LockDate(atMs, LockDate.Style.DAY_MONTH))

    // ---- "Your passwords can't be opened" -------------------------------------------------------

    fun unreadable(why: CurrentVault.Why): UiText = UiText.of(
        when (why) {
            CurrentVault.Why.IO -> R.string.lock_unreadable_io
            CurrentVault.Why.DAMAGED -> R.string.lock_unreadable_damaged
            CurrentVault.Why.NEWER -> R.string.lock_unreadable_newer
        },
    )

    // ---- Vaults put aside -----------------------------------------------------------------------

    /**
     * "Open my previous passwords (locked on 6 Oct 2026)" for [vault]; "Open my passwords from before
     * 7 Oct 2026" when it is the vault a replacement put aside (lock.md F17 step 1.3, F16b), which the
     * lock names while its notice is shown ([replaced]).
     */
    fun openAside(vault: AsideVault, replaced: ReplacedNotice?): UiText =
        if (replaced != null && replaced.oldVaultId == vault.vaultId) {
            UiText.of(R.string.lock_mp_open_from_before, LockDate(replaced.atMs, LockDate.Style.DATE))
        } else {
            UiText.of(R.string.lock_open_aside, LockDate(vault.putAsideAtMs, LockDate.Style.DATE))
        }
}
