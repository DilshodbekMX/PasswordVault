package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * `pin.rec` of one vault (lock.md §5.4 as revised by its audit: no `udr`, no `trouble_boots`;
 * `unseen` with `count`, `last_at` and `interrupted`). The fields are the record's keys; the
 * framing, `"v": 1` and `"kind": "pin"` are written and checked by `RecordCodec` (package 2.10),
 * which also enforces the field rules (lengths, at most two wraps, [off] null while [slot] is set).
 * Wall-clock times end in `Ms`; elapsed times say so.
 */
data class PinRecord(
    /** `vault_id`: equals the name of the record's directory. */
    val vaultId: String,
    /** `slot`: null when no PIN is set. Its Keystore alias is always `"pv_pin_" + slot.id`; the alias is not stored. */
    val slot: ShortcutSlot?,
    val guard: Guard,
    /** `off`: why the PIN was turned off, for the one-time explanation only. */
    val off: Off?,
    val unseen: Unseen,
) {
    /** `guard` (lock.md §6.3, §6.4): the counter, in the same record as the slot so that "one more failure" and "the slot is gone" are one write. */
    data class Guard(
        /** `charged`: a try has been paid for and not yet resolved. */
        val charged: Boolean,
        /** `row`, 0 to 5: wrong PINs since the last right PIN or master password. */
        val row: Int,
        /** `total`, 0 to 10: wrong PINs not forgiven since the last master password. */
        val total: Int,
    )

    /** `off`: `{"reason": …, "at": <ms>}`. */
    data class Off(val reason: OffReason, val atMs: Long)

    /** The values of `off.reason`; [stored] is the text in the record. */
    enum class OffReason(val stored: String) {
        LIMIT("limit"),
        KEY_GONE("key_gone"),
        BAD_WRAP("bad_wrap"),
        MASTER_CHANGED("master_changed"),
        REPLACED("replaced"),
    }

    /**
     * `unseen`: wrong PINs nobody has been told about yet: how many ([count]), when the last one
     * was (`last_at`, wall clock), and whether one of them was an interrupted entry. A right PIN
     * takes off the ones it forgives (lock.md §6.3); the notice takes off the number it showed (§13.4).
     */
    data class Unseen(val count: Int, val lastAtMs: Long?, val interrupted: Boolean)
}

/**
 * The `slot` of `pin.rec` and of `fingerprint.rec` (lock.md §5.4, §5.5): a random id (UUID v4) made
 * for every PIN or fingerprint key that is set, when it was made (wall clock), and one or two
 * [wraps] with different `vk_id`s (the rule of §5.4).
 */
data class ShortcutSlot(val id: String, val createdMs: Long, val wraps: List<KeyWrap>)

/**
 * One wrap of VK in a [ShortcutSlot] (lock.md §4.2, §4.3): `{"vk_id", "nonce", "ct"}`, the nonce
 * 12 bytes (for a fingerprint wrap, `cipher.iv`) and the ciphertext 48 bytes. Two wraps are equal
 * when their contents are.
 */
class KeyWrap(val vkId: String, val nonce: ByteArray, val ct: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is KeyWrap && vkId == other.vkId && nonce.contentEquals(other.nonce) && ct.contentEquals(other.ct)

    override fun hashCode(): Int = (vkId.hashCode() * 31 + nonce.contentHashCode()) * 31 + ct.contentHashCode()

    override fun toString(): String = "KeyWrap(vkId=$vkId, nonce=${Bytes.hex(nonce)}, ct=${ct.size} bytes)"
}
