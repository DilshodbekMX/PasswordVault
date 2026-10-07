package io.github.dilshodbekmx.passwordvault.vectors

import android.icu.util.VersionInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KOZ_BYTES
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KOZ_DECOMPOSED
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.UZBEK_BYTES
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.UZBEK_SPELLINGS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: `MasterPasswordBytes` on the phone's ICU (ICU 56
 * on Android 7) gives the bytes that JDK 25 gives (lock.md §4.3, A5; architecture.md §4.6, §4.13,
 * §8.3). A master password typed on one phone must open the vault on every other, so NFC, the 25
 * white-space code points and the 17 apostrophes must come out the same everywhere. Every expected
 * value below was printed by `MasterPasswordBytes.of` itself on JDK 25.0.4.1 (and by the appendix-C
 * script with Python's `unicodedata` 15.0.0); the inputs use only characters of Unicode 8.0, which
 * ICU 56 knows.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordBytesDeviceTest {
    private val where = "API ${Build.VERSION.SDK_INT}, ICU ${VersionInfo.ICU_VERSION}"

    private fun hexOf(typed: String): String = Bytes.hex(MasterPasswordBytes.of(typed.toCharArray()))

    @Test
    fun lockMd43_theUzbekWordAndTheDecomposedSpelling() {
        for (spelling in UZBEK_SPELLINGS) assertEquals("$spelling on $where", UZBEK_BYTES, hexOf(spelling))
        assertEquals(where, KOZ_BYTES, hexOf(KOZ_DECOMPOSED))
        assertEquals(where, KOZ_BYTES, hexOf("Köz-Ўzbek"))
    }

    @Test
    fun a413_whiteSpaceGreekSignsAndWhiteSpaceOnly() {
        assertEquals(where, "636f727265637420686f727365206261747465727920737461706c65", hexOf(" correct  horse battery staple "))
        assertEquals(where, "6f2767276c69276d", hexOf("oʹg`li´m"))
        assertEquals(where, "", hexOf("  \u0009　 "))
    }

    @Test
    fun theSameBytesAsJdk25() {
        val table = listOf(
            "각" to "eab081", // Hangul jamo compose
            "Å" to "c385", // singletons: Angstrom, Ohm, Kelvin signs
            "Ω" to "cea9",
            "K" to "4b",
            "豈" to "e8b188", // a CJK compatibility ideograph
            "ậ" to "e1baad", // combining marks in either order
            "ậ" to "e1baad",
            "क़" to "e0a495e0a4bc", // a composition exclusion stays decomposed
            "Ў" to "d08e", // Ў, Й, ё typed decomposed
            "Й" to "d099",
            "ё" to "d191",
            "İ" to "c4b0", // İ
            "İ" to "c4b0",
            "🔑" to "f09f9491", // a surrogate pair is one character
            "é́" to "c3a9cc81",
            "Å̊" to "c385cc8a",
            "Å" to "c385",
            "ẛ̣" to "e1ba9bcca3",
            "ླཱྀ" to "e0beb3e0bdb1e0be80",
            " x　y\u0085" to "782079", // white space at both ends and inside
            "oʻʻg" to "6f272767",
            "آ" to "d8a2",
            "゙か" to "e38299e3818b", // a voiced mark before its letter does not compose
            "が" to "e3818c",
            "שׁ" to "d7a9d781",
            "Toshkent shahri  !" to "546f73686b656e74207368616872692021",
            "a\uD800b" to "61efbfbd62", // an unpaired surrogate is U+FFFD
            "\uDE11\uD83D" to "efbfbdefbfbd",
        )
        for ((typed, expected) in table) {
            val codePoints = typed.codePoints().toArray().joinToString(" ") { "U+%04X".format(java.util.Locale.ROOT, it) }
            assertEquals("$codePoints on $where", expected, hexOf(typed))
        }
    }

    @Test
    fun eachOfThe25WhiteSpaceCodePointsBecomesOneSpace() {
        val whiteSpace = (0x09..0x0D) + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        assertEquals(25, whiteSpace.size)
        for (c in whiteSpace) {
            val s = String(Character.toChars(c))
            assertEquals("U+%04X on $where".format(java.util.Locale.ROOT, c), "612062", hexOf("a${s}b"))
            assertEquals("U+%04X runs and ends on $where".format(java.util.Locale.ROOT, c), "612062", hexOf("$s$s a $s$s ${s}b$s"))
        }
    }

    @Test
    fun eachOfThe17ApostrophesBecomesU0027() {
        val apostrophes = listOf(
            0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07,
        )
        for (c in apostrophes) {
            assertEquals("U+%04X on $where".format(java.util.Locale.ROOT, c), "6f2767", hexOf("o" + String(Character.toChars(c)) + "g"))
        }
    }
}
