package io.github.dilshodbekmx.passwordvault.core.util

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class B64Test {
    // The three Base64 values of the header of architecture.md §4.13 vector A.
    private val salt = ByteArray(16) { it.toByte() } // 00 01 … 0f
    private val slotNonce = ByteArray(12) { (0x20 + it).toByte() } // 20 21 … 2b
    private val wrappedKey = Bytes.fromHex(
        "4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b",
    )

    @Test
    fun theHeaderValuesOfVectorA() {
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", B64.encode(salt))
        assertEquals("ICEiIyQlJicoKSor", B64.encode(slotNonce))
        assertEquals("TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr", B64.encode(wrappedKey))
        // Exactly 24, 16 and 64 characters (architecture.md §4.2).
        assertArrayEquals(salt, B64.decode("AAECAwQFBgcICQoLDA0ODw==", 16))
        assertArrayEquals(slotNonce, B64.decode("ICEiIyQlJicoKSor", 12))
        assertArrayEquals(wrappedKey, B64.decode("TaQ2ZiOElYlVT+MkOjewZKMRc2S0upmTtqfDIHI6gZLrwe2A+48VUWtWHGPeIxcr", 48))
    }

    @Test
    fun theRfc4648Vectors() {
        val vectors = listOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, B64.encode(plain.toByteArray(Charsets.US_ASCII)))
            assertArrayEquals(plain.toByteArray(Charsets.US_ASCII), B64.decode(encoded))
        }
        // Both characters outside the letters and digits of the standard alphabet.
        assertEquals("+/++", B64.encode(byteArrayOf(-5, -1, -66)))
    }

    @Test
    fun everyLengthRoundTrips() {
        val random = Random(11)
        for (size in 0..100) {
            val bytes = ByteArray(size).also(random::nextBytes)
            assertArrayEquals(bytes, B64.decode(B64.encode(bytes)))
            assertArrayEquals(bytes, B64.decode(B64.encode(bytes), size))
        }
    }

    @Test
    fun onlyTheCanonicalTextIsAccepted() {
        val refused = listOf(
            "AAECAwQFBgcICQoLDA0ODx==", // pad bits not zero: decodes, but encodes differently
            "AAECAwQFBgcICQoLDA0ODw", // padding missing
            "AAECAwQFBgcICQoLDA0ODw===", // padding extra
            "AAECAwQFBgcICQoLDA0O Dw==", // a space
            "AAECAwQFBgcICQoL\nDA0ODw==", // a line break
            " AAECAwQFBgcICQoLDA0ODw==",
            "AAECAwQFBgcICQoLDA0ODw==\n",
            "-_-_", // the URL-safe alphabet
            "Zm9v=", "Zm9", "=Zm9", "Z===", "====",
            "Zm9vYmFyǅ", // not ASCII
            "Ｚm9v", // a full-width letter
        )
        for (text in refused) assertNull(text, B64.decode(text))
        assertNull(B64.decode("AAECAwQFBgcICQoLDA0ODw==", 15))
        assertNull(B64.decode("AAECAwQFBgcICQoLDA0ODw==", 17))
    }

    @Test
    fun garbageNeverThrows() {
        val random = Random(3)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=-_ \n\u0000é"
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(30)) { alphabet[random.nextInt(alphabet.length)] })
            val bytes = B64.decode(text)
            if (bytes != null) assertEquals(text, B64.encode(bytes))
        }
    }
}
