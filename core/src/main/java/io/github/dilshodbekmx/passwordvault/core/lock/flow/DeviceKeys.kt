package io.github.dilshodbekmx.passwordvault.core.lock.flow

/**
 * The Keystore as a whole (architecture.md §3.4): the canary `pv_canary`, the list of the app's
 * aliases, and deletion. `KeystoreAccess` (package 3.9) is the implementation.
 */
interface DeviceKeys {
    /**
     * Makes `pv_canary` if it is missing and checks it (lock.md §4.5): F1 step 5, F6 step 4, F9
     * step 4, and §14.2 when the canary is missing.
     */
    fun ensureCanary(): KeyCreate

    /** Every alias that starts with `pv_pin_` or `pv_fp_`; empty when the Keystore is not answering, so a failing Keystore makes the sweep delete nothing. */
    fun aliases(): List<String>

    /** Deletes [alias]. Errors are ignored; the sweep retries (lock.md §5.7). */
    fun delete(alias: String)
}
