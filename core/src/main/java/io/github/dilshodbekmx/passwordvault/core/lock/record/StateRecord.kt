package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * `state.rec` of one vault (lock.md §5.6 as revised by its audit: `mp_guard.unseen`, `mp_change`,
 * and `pending_mp` and `replaced` with `credited_ms` and `seen_elapsed_ms`). `"v": 1` and
 * `"kind": "state"` are the codec's. Wall-clock times end in `Ms`; elapsed times say so.
 *
 * `credited_ms` and `seen_elapsed_ms` are the two values of the credit of lock.md §6.9, which only
 * the helper of that section advances.
 */
data class StateRecord(
    /** `vault_id`: equals the name of the record's directory. */
    val vaultId: String,
    val practice: Practice,
    /** `mp_guard` (lock.md §6.8). */
    val mpGuard: MpGuard,
    /** `mp_change`: a master-password change (F8) has started and its new copy is not proven yet. */
    val mpChange: MpChange?,
    /** `pending_mp`: a new master password is waiting for its 24 credited hours (F11). */
    val pendingMp: PendingMp?,
    /** `replaced`: this vault's master password was forgotten and vault [Replaced.newVaultId] was made from it (F11 step 15). */
    val replaced: Replaced?,
    /** `max_generation`: the highest generation this phone has opened of this vault, and when [V-C8]. */
    val maxGeneration: MaxGeneration,
) {
    /** `practice` (lock.md §8.3): `last_ok` (the last correct master password), `step` 0 to 3, `snoozed_until` (set by "Not now"). */
    data class Practice(val lastOkMs: Long, val step: Int, val snoozedUntilMs: Long?)

    /**
     * `mp_guard` (lock.md §6.8): wrong master passwords in a row, `elapsedRealtime()` at the last
     * one (`last_fail_elapsed_ms`), and how many nobody has been shown yet (`unseen`).
     */
    data class MpGuard(val failures: Int, val lastFailElapsedMs: Long?, val unseen: Int)

    /** `mp_change`: `{"to_vk_id": …, "at": <ms>}`. */
    data class MpChange(val toVkId: String, val atMs: Long)

    /**
     * `pending_mp` (lock.md F11 step 5): when and by which shortcut it was asked for, its credit,
     * and the wrap of VK under `KEK_pending`: the `vk_id` it wraps, the 16-byte salt, the 12-byte
     * nonce and the 48-byte ciphertext. Equal when the contents are.
     */
    class PendingMp(
        val requestedMs: Long,
        val by: Shortcut,
        val creditedMs: Long,
        val seenElapsedMs: Long,
        val vkId: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ct: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is PendingMp && requestedMs == other.requestedMs && by == other.by && creditedMs == other.creditedMs &&
                seenElapsedMs == other.seenElapsedMs && vkId == other.vkId && salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) && ct.contentEquals(other.ct)

        override fun hashCode(): Int {
            var h = requestedMs.hashCode()
            h = h * 31 + by.hashCode()
            h = h * 31 + creditedMs.hashCode()
            h = h * 31 + seenElapsedMs.hashCode()
            h = h * 31 + vkId.hashCode()
            h = h * 31 + salt.contentHashCode()
            h = h * 31 + nonce.contentHashCode()
            return h * 31 + ct.contentHashCode()
        }

        override fun toString(): String =
            "PendingMp(requestedMs=$requestedMs, by=$by, creditedMs=$creditedMs, seenElapsedMs=$seenElapsedMs, " +
                "vkId=$vkId, salt=${Bytes.hex(salt)}, nonce=${Bytes.hex(nonce)}, ct=${ct.size} bytes)"
    }

    /** `replaced` (lock.md F11 step 15): the vault made from this one, when, by which shortcut, and the credit that times the notice of §13.4. */
    data class Replaced(val newVaultId: String, val atMs: Long, val by: Shortcut, val creditedMs: Long, val seenElapsedMs: Long)

    /** `max_generation`: `{"n": …, "at": <ms>}`. */
    data class MaxGeneration(val n: Long, val atMs: Long)
}

/**
 * The two shortcuts of the lock (lock.md §1.1): the PIN and the fingerprint. In `state.rec` it is
 * the `by` of `pending_mp` and of `replaced` ([stored] is the text there); the screens and notices
 * of `core.lock.flow` use it too ("using the PIN", "Not now: use fingerprint").
 */
enum class Shortcut(val stored: String) {
    PIN("pin"),
    FINGERPRINT("fingerprint"),
}
