package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer

/**
 * Everything the lock screens, Welcome and the notices tell the lock (architecture.md §3.5),
 * through `LockManager.send` on the main thread. The lock answers through `LockManager.state`.
 */
sealed interface LockIntent {
    /**
     * A digit key of the pad, 0 to 9. The lock appends it to the PIN it holds as ASCII bytes (lock.md
     * §5.8); the first digit takes the charge (§6.3 C), the sixth starts the check (§6.3 E).
     * [toString] never shows the digit.
     */
    data class Digit(val digit: Int) : LockIntent {
        override fun toString(): String = "Digit"
    }

    /** ⌫: removes the last digit. */
    data object DeleteDigit : LockIntent

    /** Clears every digit typed so far. */
    data object ClearDigits : LockIntent

    /** "Use PIN", or "Not now: use PIN" on a practice screen within its grace (lock.md §8.3). */
    data object UsePin : LockIntent

    /**
     * "Forgot PIN? Use master password", "Use master password" (lock.md F7, §13.2). Also "Try again"
     * on "Your passwords can't be opened" (`LockUiState.Unreadable` with `CurrentVault.Why.IO`): the
     * lock lists the vaults again (`LockEngine.refresh`), as `DefaultLockManager` does.
     */
    data object UseMasterPassword : LockIntent

    /** The fingerprint key, or "Use fingerprint" on the fingerprint-only screen: opens the prompt again (lock.md §9.2 rule 7). */
    data object UseFingerprint : LockIntent

    /** "Not now: use fingerprint" on a practice screen within its grace. */
    data object NotNow : LockIntent

    /**
     * "I can't remember it" on a practice screen, and "Use my PIN" or "Use my fingerprint" on the
     * "Forgot master password?" sheet (lock.md F10 step 4, F11 step 1, F17 step 1): F11 from the lock
     * screen (`LockEngine.cantRemember`).
     */
    data object CantRemember : LockIntent

    /**
     * "Forgot master password?" (ux.md §5.10, lock.md F17 step 1), "I forgot it", or "Open my
     * passwords from before …" when the master password was replaced more than once
     * (`ReplacedLine.oldVaultId` is null). The sheet belongs to the screen: `LockUiState` has no field
     * for it. This intent and [CloseSheet] tell the lock when the sheet covers the lock screen, so
     * that no fingerprint prompt opens by itself under it (lock.md §9.2 rule 7).
     */
    data object OpenForgotSheet : LockIntent

    /**
     * The sheet's "Try again", the sheet dismissed, or the sheet closing because one of its options
     * was chosen; in that case it is sent before the option's own intent.
     */
    data object CloseSheet : LockIntent

    /** "Open my previous passwords (locked on …)" for the vault [vaultId] put aside: its own lock screen (lock.md F17 step 4). */
    data class OpenAside(val vaultId: String) : LockIntent

    /** "Start with an empty app" confirmed (lock.md F17 step 2). */
    data object StartEmpty : LockIntent

    /**
     * "Restore from a backup file" on the sheet (lock.md F17 step 3) or on "Your passwords can't be
     * opened"; the screen that sent it then opens the restore screens (`Route.Restore`).
     */
    data object RestoreFromFile : LockIntent

    /**
     * An answer on "This is an older master password" (lock.md F16b, `LockScreen.OlderPassword`), or
     * on "Open the older set instead?" (F16b step 3, `LockScreen.OpenOlderSet`).
     */
    data class OlderPasswordAnswer(val choice: OlderPasswordChoice) : LockIntent

    /** The answer [answer] to the notice [id] (lock.md §13.4); [answer] is one the notice offers. */
    data class NoticeAnswered(val id: Long, val answer: NoticeAnswer) : LockIntent

    /** "Cancel it" on the banner of a pending new master password (lock.md F11 step 9). */
    data object CancelPending : LockIntent

    /** "Finish now" when the new master password is ready (lock.md F11 step 13). */
    data object FinishPending : LockIntent

    /** "It wasn't me" on the "replaced" notice (lock.md F16b). */
    data object ItWasntMe : LockIntent

    /** Back on the lock screen of a vault put aside: returns to the current vault and changes nothing (lock.md F17 step 4). */
    data object GoBack : LockIntent
}

/**
 * The buttons of "This is an older master password" (lock.md F16b, `LockScreen.OlderPassword`), and
 * of the question before the passwords from before a replacement are opened
 * (`LockScreen.OpenOlderSet`, F16b step 3). The engine's side is `OlderPasswordAnswer`, value for value.
 */
enum class OlderPasswordChoice {
    /** The change did not finish: "Try again" with the new master password. */
    TRY_AGAIN,

    /** The change did not finish: "Open my passwords from before the change" (asked once more, then F16b steps 1 to 3). */
    OPEN_FROM_BEFORE,

    /** The change was proven: "OK", back to the field. */
    OK,

    /** On `LockScreen.OpenOlderSet`: "Open my passwords from 7 Oct". The two vaults change places (F16b steps 4 to 6). */
    OPEN_OLDER_SET,

    /** On `LockScreen.OpenOlderSet`: "Cancel". Nothing changes. */
    KEEP_NEWER_SET,
}
