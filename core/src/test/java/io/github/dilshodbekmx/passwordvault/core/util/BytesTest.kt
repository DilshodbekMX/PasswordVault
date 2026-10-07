package io.github.dilshodbekmx.passwordvault.core.util

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BytesTest {
    @Test
    fun hexIsLowerCaseAsciiTwoDigitsPerByte() {
        assertEquals("", Bytes.hex(ByteArray(0)))
        assertEquals("00017f80feff", Bytes.hex(byteArrayOf(0, 1, 0x7f, -0x80, -2, -1)))
        // The digits are ASCII under every default locale (the tests also run under tr-TR and fa-IR).
        val all = Bytes.hex(ByteArray(256) { it.toByte() })
        assertEquals(512, all.length)
        assertTrue(all.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun fromHexReadsWhatHexWrites() {
        val random = Random(7)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
            assertArrayEquals(bytes, Bytes.fromHex(Bytes.hex(bytes)))
        }
        assertArrayEquals(byteArrayOf(-85, -51, -17), Bytes.fromHex("ABcdEF"))
        assertArrayEquals(ByteArray(0), Bytes.fromHex(""))
    }

    @Test
    fun fromHexRefusesAnythingElseWithoutQuotingIt() {
        for (bad in listOf("abc", "0g", "zz", "00 11", "0x00", "１２", "--")) {
            try {
                Bytes.fromHex(bad)
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertFalse("message quotes the input: ${e.message}", e.message.orEmpty().contains(bad))
            }
        }
    }

    @Test
    fun equalComparesContents() {
        assertTrue(Bytes.equal(ByteArray(0), ByteArray(0)))
        assertTrue(Bytes.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Bytes.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Bytes.equal(byteArrayOf(1, 2, 3), byteArrayOf(0, 2, 3)))
        assertFalse(Bytes.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(Bytes.equal(byteArrayOf(), byteArrayOf(0)))
        // Every single-bit difference in a 32-byte key is seen.
        val key = ByteArray(32) { (it * 37).toByte() }
        for (i in 0 until 32 * 8) {
            val other = key.copyOf()
            other[i / 8] = (other[i / 8].toInt() xor (1 shl (i % 8))).toByte()
            assertFalse(Bytes.equal(key, other))
        }
    }

    @Test
    fun wipeZeroesEveryArrayAndSkipsNull() {
        val a = byteArrayOf(1, 2, 3)
        val b = ByteArray(40) { -1 }
        Bytes.wipe(a, null, b)
        assertArrayEquals(ByteArray(3), a)
        assertArrayEquals(ByteArray(40), b)
        Bytes.wipe()
        val chars = "correct horse".toCharArray()
        Bytes.wipe(chars)
        assertTrue(chars.all { it == '\u0000' })
        Bytes.wipe(null as CharArray?)
    }
}
