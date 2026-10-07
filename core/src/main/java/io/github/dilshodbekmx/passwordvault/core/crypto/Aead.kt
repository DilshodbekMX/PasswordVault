package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * AES-256-GCM with a 12-byte nonce and a 16-byte tag (architecture.md §3.2): the vault body, the
 * password slot, and the PIN and pending wraps of lock.md §4.3. `JcaAesGcm` (package 2.1) is the
 * implementation; `CryptoFactory.aead()` makes it.
 */
interface Aead {
    /** The ciphertext of [plaintext] under [key] (32 bytes) and [nonce] (12 bytes), with the tag at its end. [aad] may be null. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray

    /** The plaintext of [ciphertext], or null when the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray?
}
