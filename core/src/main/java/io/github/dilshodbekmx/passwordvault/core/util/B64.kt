package io.github.dilshodbekmx.passwordvault.core.util

import kotlin.io.encoding.Base64

/**
 * Base64 with the standard alphabet and padding (RFC 4648 §4), over `kotlin.io.encoding.Base64`:
 * `java.util.Base64` does not exist on API 24 and 25 (V-C12, lock.md §4.2).
 *
 * [decode] accepts only the canonical text, the one [encode] writes: decoding and encoding again
 * must give the same text (architecture.md §4.2). So white space, line breaks, the URL-safe
 * alphabet, missing or extra padding and non-zero pad bits are all refused. It never throws and
 * never puts the text it was given into a message: the text comes from a file (§5.6).
 */
object B64 {
    /** [bytes] as standard Base64 with padding. */
    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

    /** The bytes of [text], or null when [text] is not canonical standard Base64 with padding. */
    fun decode(text: String): ByteArray? {
        val bytes = try {
            Base64.Default.decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (encode(bytes) == text) bytes else null
    }

    /** As [decode], and null also when the bytes are not exactly [size] long. */
    fun decode(text: String, size: Int): ByteArray? = decode(text)?.takeIf { it.size == size }
}
