package io.github.dilshodbekmx.passwordvault.core.util

/**
 * Small tools for byte arrays (architecture.md §2.1): hex, wiping, and a comparison whose time does
 * not depend on where two arrays differ. Keys and passwords are byte or char arrays that are wiped
 * after use (§5.6); this is the one place that does it.
 */
object Bytes {
    private val DIGITS = "0123456789abcdef".toCharArray()

    /** [bytes] as lower-case hex, two ASCII digits per byte, whatever the default locale. */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt()
            out[2 * i] = DIGITS[(b shr 4) and 0x0f]
            out[2 * i + 1] = DIGITS[b and 0x0f]
        }
        return String(out)
    }

    /**
     * The bytes that [hex] writes as [text]: an even number of hex digits, upper or lower case,
     * nothing else (no spaces). Meant for test vectors and constants.
     *
     * @throws IllegalArgumentException when [text] is not such a string; the message holds no input.
     */
    fun fromHex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex text of odd length" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = digit(text[2 * i])
            val low = digit(text[2 * i + 1])
            require(high >= 0 && low >= 0) { "not a hex digit" }
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * Whether [a] and [b] hold the same bytes. For arrays of equal length the time does not depend
     * on the contents, so it may compare a secret with a guess; the lengths themselves are not
     * treated as secret.
     */
    fun equal(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Fills every array with zeros; null entries are skipped. Best effort on a garbage-collected heap (lock.md §4.2). */
    fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) array?.fill(0)
    }

    /** Fills [chars] with U+0000, for a typed password held as a char array. */
    fun wipe(chars: CharArray?) {
        chars?.fill('\u0000')
    }
}
