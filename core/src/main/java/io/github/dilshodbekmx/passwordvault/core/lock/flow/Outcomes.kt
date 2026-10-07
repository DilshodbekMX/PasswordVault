package io.github.dilshodbekmx.passwordvault.core.lock.flow

/**
 * What the check of a PIN at its sixth digit came to (lock.md §6.3 E, the code sketch there;
 * architecture.md §2.1). `PinGuard` (package 2.11) returns it, the lock engine acts on it. The
 * counts and the record are already written, or queued, when it is returned.
 */
sealed interface PinOutcome {
    /** No charge was held: a fault of the caller. Nothing was checked. */
    data object NotCharged : PinOutcome

    /** The same wrong PIN as before (§6.6): refunded, not checked again. */
    data object SameAsBefore : PinOutcome

    /** The Keystore said GONE: refunded, the slot removed with `off = key_gone` (F15b). */
    data object KeyGone : PinOutcome

    /** The Keystore said TEMPORARY: refunded; the PIN is not offered again on this visit (F15c). */
    data object CannotCheck : PinOutcome

    /** The fifth wrong PIN in a row, or the tenth not forgiven: the PIN was turned off (§6.5). */
    data object Limit : PinOutcome

    /** Wrong, and counted: "Wrong PIN. [left] tries left.", with left = min(5 − row, 10 − total). */
    data class Wrong(val left: Int) : PinOutcome

    /** Right, but the visit or prompt it belonged to has ended: counted as right, nothing opens (I11). */
    data object TooLate : PinOutcome

    /**
     * Right: the VK of every wrap that opened, by `vk_id`. Storage opens the vault with them (I7);
     * the receiver wipes them.
     */
    class Right(val keys: Map<String, ByteArray>) : PinOutcome
}
