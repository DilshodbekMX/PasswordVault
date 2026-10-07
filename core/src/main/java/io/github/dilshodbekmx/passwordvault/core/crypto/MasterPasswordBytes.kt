package io.github.dilshodbekmx.passwordvault.core.crypto

import java.nio.CharBuffer
import java.text.Normalizer

/**
 * The only way a master password becomes bytes: for the vault, every backup and the pending
 * password of lock.md F11 (architecture.md §4.6, lock.md §8.1). **Frozen before the first release**
 * with the vectors of architecture.md §4.13 and lock.md §4.3: a later change would lock users out.
 *
 * [of] runs these steps, in this order:
 * 1. **NFC** (`java.text.Normalizer`, `Form.NFC`).
 * 2. **White space.** Each of 25 code points becomes U+0020: U+0009 to U+000D, U+0020, U+0085,
 *    U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Then a run of
 *    U+0020 becomes one, and those at the start and the end are removed.
 * 3. **Apostrophes.** Each of 17 code points becomes U+0027: U+0027, U+0060, U+00B4, U+2018,
 *    U+2019, U+201B, U+2032, U+2035, U+02B9, U+02BB, U+02BC, U+02BD, U+02BE, U+02BF, U+A78B,
 *    U+A78C, U+FF07.
 * 4. **UTF-8.** Case is kept.
 *
 * NFC comes first because it turns U+0374, U+1FEF and U+1FFD into members of the apostrophe list;
 * so every canonically equivalent spelling gives the same bytes, and the function applied to its
 * own result changes nothing (U+0020 and U+0027 take part in no composition). Steps 2 and 3 run in
 * one pass here, which gives the same result: neither list contains the other's target.
 *
 * Not in the documents, and frozen with the rest: an unpaired surrogate, which is not a Unicode
 * character, is written as the UTF-8 of U+FFFD (EF BF BD), the same on every phone. Creation never
 * lets one through ([firstUnassigned]).
 *
 * Nothing is kept: every array this object makes is zeroed before it is dropped, and [of] hands its
 * result to the caller, who zeroes it. The NFC step cannot avoid two `String` copies, inside
 * `java.text.Normalizer`, which can only be dropped.
 */
object MasterPasswordBytes {

    /** The bytes of the master password [chars] (steps 1 to 4 above). [chars] is not changed. */
    fun of(chars: CharArray): ByteArray {
        val nfc = Normalizer.normalize(CharBuffer.wrap(chars), Normalizer.Form.NFC)
        val folded = IntArray(nfc.length) // code points after steps 2 and 3; never more than chars
        var count = 0
        try {
            var spaceBefore = false
            var i = 0
            while (i < nfc.length) {
                val c = nfc.codePointAt(i)
                i += Character.charCount(c)
                if (isWhiteSpace(c)) {
                    spaceBefore = count > 0 // a run becomes one space; none at the start
                } else {
                    if (spaceBefore) folded[count++] = SPACE
                    spaceBefore = false
                    folded[count++] = if (isApostrophe(c)) APOSTROPHE else c
                } // a run at the end is dropped: no code point follows it
            }
            var size = 0
            for (k in 0 until count) size += utf8Size(folded[k])
            val bytes = ByteArray(size)
            var at = 0
            for (k in 0 until count) at = putUtf8(folded[k], bytes, at)
            return bytes
        } finally {
            folded.fill(0)
        }
    }

    /**
     * Whether the two typings of a new master password are the same password: [of] of each,
     * compared in constant time; both byte arrays are zeroed before this returns. At creation,
     * restore and change this runs before anything is derived or written (architecture.md §4.6).
     * [first] and [again] are not changed: the caller derives from them next and zeroes them then.
     */
    fun same(first: CharArray, again: CharArray): Boolean {
        val a = of(first)
        try {
            val b = of(again)
            try {
                var diff = a.size xor b.size
                for (i in 0 until maxOf(a.size, b.size)) {
                    val x = if (i < a.size) a[i].toInt() else 0
                    val y = if (i < b.size) b[i].toInt() else 0
                    diff = diff or (x xor y)
                }
                return diff == 0
            } finally {
                b.fill(0)
            }
        } finally {
            a.fill(0)
        }
    }

    /**
     * At creation only: the first code point of [chars] that Unicode has not assigned
     * (`Character.getType` is `UNASSIGNED`, which takes in the noncharacters), or the first unpaired
     * surrogate, as its value; null when there is none. Such a password is refused, because a later
     * phone might normalise it differently [V-C9]. It is checked on the characters as typed: NFC never
     * turns an assigned character into an unassigned one, or the other way round.
     */
    fun firstUnassigned(chars: CharArray): Int? {
        var i = 0
        while (i < chars.size) {
            val c = Character.codePointAt(chars, i) // a surrogate value only when it is unpaired
            if (isSurrogate(c) || Character.getType(c) == Character.UNASSIGNED.toInt()) return c
            i += Character.charCount(c)
        }
        return null
    }

    private const val SPACE = 0x20
    private const val APOSTROPHE = 0x27
    private const val REPLACEMENT = 0xFFFD

    /** Step 2's 25 code points (architecture.md §4.6). */
    private fun isWhiteSpace(c: Int): Boolean =
        c in 0x09..0x0D || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || c in 0x2000..0x200A ||
            c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000

    /** Step 3's 17 code points (lock.md §8.1). */
    private fun isApostrophe(c: Int): Boolean = when (c) {
        0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035,
        0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07 -> true
        else -> false
    }

    private fun isSurrogate(c: Int) = c in 0xD800..0xDFFF

    private fun utf8Size(c: Int): Int = when {
        c < 0x80 -> 1
        c < 0x800 -> 2
        c < 0x10000 -> 3 // an unpaired surrogate is written as U+FFFD, also 3 bytes
        else -> 4
    }

    /** Writes the UTF-8 of [c] into [out] at [at] and returns the index after it. */
    private fun putUtf8(c: Int, out: ByteArray, at: Int): Int {
        val cp = if (isSurrogate(c)) REPLACEMENT else c
        var i = at
        when {
            cp < 0x80 -> out[i++] = cp.toByte()
            cp < 0x800 -> {
                out[i++] = (0xC0 or (cp shr 6)).toByte()
                out[i++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                out[i++] = (0xE0 or (cp shr 12)).toByte()
                out[i++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[i++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                out[i++] = (0xF0 or (cp shr 18)).toByte()
                out[i++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                out[i++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[i++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
        return i
    }
}
