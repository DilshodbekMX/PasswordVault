package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * The one password slot of a vault file (architecture.md §4.2, §4.6): VK wrapped under the KEK of
 * a master password. [salt] is 16 bytes, [nonce] 12, [wrappedKey] 48 (32 bytes of ciphertext and
 * the tag).
 *
 * Two slots are equal when their three byte strings are: that is how storage tells the distinct
 * slots among the kept generations apart (§4.8). None of the three is secret; they are in the
 * header of every file.
 */
class PasswordSlot(val salt: ByteArray, val nonce: ByteArray, val wrappedKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PasswordSlot && salt.contentEquals(other.salt) && nonce.contentEquals(other.nonce) &&
            wrappedKey.contentEquals(other.wrappedKey)

    override fun hashCode(): Int = (salt.contentHashCode() * 31 + nonce.contentHashCode()) * 31 + wrappedKey.contentHashCode()

    override fun toString(): String =
        "PasswordSlot(salt=${Bytes.hex(salt)}, nonce=${Bytes.hex(nonce)}, wrappedKey=${Bytes.hex(wrappedKey)})"
}
