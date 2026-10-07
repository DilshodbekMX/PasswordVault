package io.github.dilshodbekmx.passwordvault.core.lock.record

/**
 * `fingerprint.rec` of one vault (lock.md §5.5 as revised by its audit: no `trouble_boots`). Same
 * rules as `pin.rec`, the rule for wraps included; `"v": 1` and `"kind": "fingerprint"` are the
 * codec's.
 */
data class FingerprintRecord(
    /** `vault_id`: equals the name of the record's directory. */
    val vaultId: String,
    /** `slot`: null when fingerprint unlock is off. Its Keystore alias is always `"pv_fp_" + slot.id`. */
    val slot: ShortcutSlot?,
    /** `off`: why fingerprint unlock was turned off, for the one-time explanation only. */
    val off: Off?,
) {
    /** `off`: `{"reason": …, "at": <ms>}`. */
    data class Off(val reason: OffReason, val atMs: Long)

    /** The values of `off.reason`; [stored] is the text in the record. */
    enum class OffReason(val stored: String) {
        KEY_GONE("key_gone"),
        BAD_WRAP("bad_wrap"),
        TURNED_OFF("turned_off"),
        MASTER_CHANGED("master_changed"),
        REPLACED("replaced"),
    }
}
