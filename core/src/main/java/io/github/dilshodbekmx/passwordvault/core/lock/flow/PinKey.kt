package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks

/**
 * The PIN's Keystore key, `K_pin` (lock.md §4.2; architecture.md §3.4): an HMAC-SHA256 key that
 * never leaves the secure hardware. `KeystorePinKey` (package 3.9) is the implementation;
 * `FakeKeys` of :testing (package 2.11) computes a software HMAC with scripted failures.
 */
interface PinKey {
    /** Makes the key under [alias] (`"pv_pin_" + slot id`) as lock.md §4.2 specifies and checks it by §4.5. */
    fun create(alias: String): KeyCreate

    /** `KEK_pin` = HMAC-SHA256 of [message] (`pin_msg`, lock.md §4.3): 32 bytes. The only thing the key ever does. */
    fun hmac(alias: String, message: ByteArray): Ks<ByteArray>
}
