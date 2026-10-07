package io.github.dilshodbekmx.passwordvault.core.crypto

import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [Aead] over the platform's own AES-GCM (architecture.md §3.2): `Cipher.getInstance("AES/GCM/NoPadding")`
 * with no provider name, which is Conscrypt ("AndroidOpenSSL") on a phone [L-old §6] and the JDK's
 * provider in the JVM tests. AES-256 only: a 32-byte key, a 12-byte nonce, and a 16-byte tag at the
 * end of the ciphertext. The vault body, the password slot, and the PIN and pending wraps of
 * lock.md §4.3 all go through it. `CryptoFactory.aead()` makes it.
 *
 * - Every call makes its own `Cipher`: a `Cipher` may not be shared between threads, and the copy of
 *   the key it keeps is dropped with it when the call returns [V-C10].
 * - `destroy()` is never called on the key, which throws `AbstractMethodError` on API 24 and 25
 *   [V-C10]. The caller zeroes its own key array.
 * - [open] gives null for a ciphertext shorter than the tag and for every ciphertext whose tag does
 *   not verify. A failed tag is an `AEADBadTagException` on Conscrypt and in the JDK; it is caught as
 *   its superclass `BadPaddingException`, which is what older providers throw instead.
 * - An empty [aad] is the same as none: GCM authenticates zero bytes of associated data either way.
 * - A key or nonce of another length is a programming error: `IllegalArgumentException`, whose
 *   message holds no input (architecture.md §5.6).
 */
internal class JcaAesGcm : Aead {

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        requireSizes(key, nonce)
        if (ciphertext.size < TAG_BYTES) return null
        val cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: BadPaddingException) {
            null
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?): Cipher {
        requireSizes(key, nonce)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        if (aad != null && aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher
    }

    private fun requireSizes(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_BYTES) { "AES-256-GCM takes a key of 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "AES-256-GCM takes a nonce of 12 bytes" }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
    }
}
