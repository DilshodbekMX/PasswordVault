package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut

/**
 * A notice after an unlock (lock.md §13.4; ux.md §5.9, §5.24). They are shown one at a time, in the
 * order of §13.4, which is the order of the cases below. Like [LockScreen] a notice holds facts,
 * never a sentence. The standing banner of §13.4 item 6 is not a notice: it is part of the lock's
 * status (`LockStatus.practiceOverdueSinceMs`).
 *
 * [id] is unique among the notices of the process; the screen answers with
 * `LockIntent.NoticeAnswered(id, answer)` and one of the [NoticeAnswer]s that the case lists.
 */
sealed interface Notice {
    val id: Long

    /**
     * 1. The newest copy could not be read and an older one opened: "… We opened the copy from
     * 5 October, 18:42. …" [openedCreatedMs] is that copy's `created`. Answers: BACK_UP_NOW, OK.
     * Automatic writes wait until it is answered (architecture.md §5.5).
     */
    data class OlderCopy(override val id: Long, val openedCreatedMs: Long) : Notice

    /**
     * 1. Older than last time (F16c, [V-C8]): "These passwords are older than the ones this app
     * opened on 6 October, 09:10. …" [lastOpenedAtMs] is `max_generation.at`. Answer: OK.
     */
    data class OlderThanLastTime(override val id: Long, val lastOpenedAtMs: Long) : Notice

    /**
     * 2. A new master password is pending (F11 step 8): "A new master password was chosen on 6 Oct,
     * 14:02, using the PIN. It starts working in about 20 hours. …" [remainingMs] is the phone-on
     * time still to be credited. Answers: CANCEL_IT, OK.
     */
    data class Pending(override val id: Long, val requestedAtMs: Long, val by: Shortcut, val remainingMs: Long) : Notice

    /** 2. The new master password is ready (F11 step 13): "Your new master password is ready. Type it to finish." Answers: FINISH_NOW, LATER, CANCEL_IT. */
    data class PendingReady(override val id: Long, val requestedAtMs: Long, val by: Shortcut) : Notice

    /** 2. A correct current master password cancelled the pending one (F11 step 9): "You typed your master password, so the new one was cancelled." Answer: OK. */
    data class PendingCancelled(override val id: Long) : Notice

    /**
     * 3. The master password was replaced (F11 step 20), shown until 7 days are credited on the old
     * vault's `replaced`: "Your master password was replaced on 7 Oct using the PIN. …"
     * [oldVaultId] is the vault "It wasn't me" opens (F16b). Answers: OK, IT_WASNT_ME.
     */
    data class Replaced(override val id: Long, val atMs: Long, val by: Shortcut, val oldVaultId: String) : Notice

    /**
     * 4. Wrong tries nobody has been told about (`unseen`).
     *
     * @property wrongPins "A wrong PIN was typed 3 times before you opened the app (last time today
     *   at 14:32). …"; 0 when there were none. Answering takes this number off `unseen.count`.
     * @property lastWrongPinAtMs the time in that sentence.
     * @property interrupted adds "One entry was interrupted and counted as a try."
     * @property pinsBeforeOff set when `total` is 6 or more: "After 4 more wrong PINs that are not put
     *   right at once, the PIN turns off. …" with TYPE_MASTER_PASSWORD.
     * @property offerNewMasterPassword practice is also past its grace: "Can't remember it? Choose a
     *   new one." with NEW_MASTER_PASSWORD.
     * @property wrongMasterPasswords "A wrong master password was typed 2 times before you opened the
     *   app."; 0 when there were none.
     *
     * Answers: CHANGE_PIN, OK, and the two above when they apply.
     */
    data class WrongTries(
        override val id: Long,
        val wrongPins: Int,
        val lastWrongPinAtMs: Long?,
        val interrupted: Boolean,
        val pinsBeforeOff: Int?,
        val offerNewMasterPassword: Boolean,
        val wrongMasterPasswords: Int,
    ) : Notice

    /**
     * 5. A PIN should be chosen (F5, F7, F8, F14, F15b, F16b step 6, a damaged `pin.rec`; F1 step 6
     * for a new or restored vault). With [needsMasterPassword] there is no proof from this unlock (a
     * fingerprint or PIN unlock): "PIN unlock is off. To choose a new PIN you need your master
     * password." Otherwise the "Choose a PIN" screen follows with "Not now". Answers: CHOOSE_PIN, LATER.
     */
    data class ChoosePin(override val id: Long, val reason: ChoosePinReason, val needsMasterPassword: Boolean) : Notice

    /** 5. "Turn fingerprint unlock on again?" (F15a, F15b, F8 step 7). Answers: TURN_ON_FINGERPRINT, LATER. */
    data class FingerprintAgain(override val id: Long) : Notice

    /** 5. A lock record could not be read (§5.3 UNREADABLE): "Part of the app's storage could not be read. Make a backup now." Answers: SET_UP_AGAIN, BACK_UP_NOW, LATER. */
    data class StorageUnreadable(override val id: Long) : Notice

    /**
     * 7. "Make a backup now?" when the master password was just typed and a backup is due (F12).
     * Answers: BACK_UP_NOW, LATER. The backup that BACK_UP_NOW starts uses the entry of this unlock
     * (`LockState.Unlocked.proof`); any other answer drops that proof: "The typed bytes are kept only
     * until the offer is answered" (F12 step 2).
     */
    data class BackupNow(override val id: Long) : Notice
}

/** Why [Notice.ChoosePin] asks for a PIN; it picks the words of the offer. */
enum class ChoosePinReason {
    /** Turned off at a limit (F5). */
    TURNED_OFF,

    /** "Forgot PIN? Use master password" (F7). */
    FORGOTTEN,

    /** The key is gone or the record damaged (F15b, §5.3). */
    STOPPED_WORKING,

    /** A master-password change that did not keep the PIN (F8). */
    MASTER_CHANGED,

    /** No PIN on this phone yet: a new, restored or replacing vault, or one without lock records (F1, F13, F14, F11 step 19). */
    NONE_HERE,

    /** After going back to the passwords from before a replacement: "Choose a new PIN. Someone else may know the old one." (F16b step 6). */
    SOMEONE_MAY_KNOW,
}

/** The buttons of the notices; each [Notice] case lists those it offers. */
enum class NoticeAnswer {
    OK,
    LATER,
    CANCEL_IT,
    FINISH_NOW,
    IT_WASNT_ME,
    CHANGE_PIN,
    TYPE_MASTER_PASSWORD,
    NEW_MASTER_PASSWORD,
    CHOOSE_PIN,
    TURN_ON_FINGERPRINT,
    SET_UP_AGAIN,
    BACK_UP_NOW,
}
