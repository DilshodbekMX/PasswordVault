package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut

/**
 * What the lock tells the rest of the app while it is open or on Welcome (architecture.md §3.5):
 * Settings › App lock, the list's banners (ux.md §5.11), Welcome and the "Forgot master password?"
 * sheet. Facts and numbers, never a sentence.
 *
 * @property pin the PIN, for "Change PIN" or "Set a PIN".
 * @property fingerprint the fingerprint switch and its reason.
 * @property pending a new master password waiting for its 24 credited hours (lock.md §13.4 item 2).
 * @property replaced the master password was replaced (lock.md §13.4 item 3), while its notice is shown.
 * @property practiceOverdueSinceMs practice is past its grace and no new master password is
 *   pending: the standing banner of lock.md §13.4 item 6, "You have not typed your master password
 *   since 12 Sep." (wall-clock ms of the last correct master password).
 * @property aside the vaults put aside, for Welcome, the sheet and Settings › Old passwords (F17).
 */
data class LockStatus(
    val pin: PinStatus,
    val fingerprint: FingerprintStatus,
    val pending: PendingMasterPassword?,
    val replaced: ReplacedNotice?,
    val practiceOverdueSinceMs: Long?,
    val aside: List<AsideVault>,
) {
    companion object {
        /** No PIN, fingerprint off, nothing pending or put aside: a new vault, and the status before the records are read. */
        val NONE = LockStatus(PinStatus.NotSet, FingerprintStatus.Off, null, null, null, emptyList())
    }
}

/** The PIN of the current vault (ux.md §5.18 "Change PIN" or "Set a PIN"; lock.md F6). */
sealed interface PinStatus {
    /** A usable PIN: "Change PIN". */
    data object On : PinStatus

    /** No PIN: never set, skipped at setup, or removed by a master-password change that did not keep it. "Set a PIN". */
    data object NotSet : PinStatus

    /**
     * The PIN cannot be used, for [reason]: [PinReason.LIMIT], [PinReason.STOPPED_WORKING],
     * [PinReason.NEWER_APP] or [PinReason.STORAGE_FULL]. Never [PinReason.CANNOT_CHECK], which is
     * [CannotCheck]. "Set a PIN" makes a new one.
     */
    data class Off(val reason: PinReason) : PinStatus

    /** The Keystore is not answering, or the record cannot be read right now (lock.md F15c); nothing was removed. */
    data object CannotCheck : PinStatus

    /** This phone cannot keep a key in secure hardware (lock.md F18): the master password only. */
    data object NoSecureHardware : PinStatus
}

/** Settings › App lock › Fingerprint unlock (ux.md §5.18, lock.md §9.4). */
sealed interface FingerprintStatus {
    /** On and usable. */
    data object On : FingerprintStatus

    /** Off; the switch can turn it on. */
    data object Off : FingerprintStatus

    /** A master-password change did not renew it (lock.md F8 step 7): "Fingerprint unlock: turn on again". */
    data object TurnOnAgain : FingerprintStatus

    /**
     * Not offered: `canAuthenticate(BIOMETRIC_STRONG)` gave [canAuthenticate] (11, 12, −2, 1, 15 …),
     * whose reason Settings shows (lock.md §9.4). −1 (unknown) is [Off]: the switch stays enabled.
     */
    data class Unavailable(val canAuthenticate: Int) : FingerprintStatus

    /** "This phone can't keep a fingerprint key in protected hardware." */
    data object NoSecureHardware : FingerprintStatus
}

/**
 * A new master password waiting to start working (lock.md F11, §13.4 item 2): asked for at
 * [requestedAtMs] (wall clock) with [by]; [creditedMs] of the 24 hours are credited (lock.md §6.9),
 * [remainingMs] are left, and it is [ready] once they are.
 */
data class PendingMasterPassword(
    val requestedAtMs: Long,
    val by: Shortcut,
    val creditedMs: Long,
    val remainingMs: Long,
    val ready: Boolean,
)

/**
 * "Your master password was replaced on 7 Oct using the PIN" (lock.md §13.4 item 3, F11 step 20):
 * when ([atMs], wall clock) and by which shortcut; [oldVaultId] is the vault put aside that "It
 * wasn't me" opens (F16b).
 */
data class ReplacedNotice(val atMs: Long, val by: Shortcut, val oldVaultId: String)
