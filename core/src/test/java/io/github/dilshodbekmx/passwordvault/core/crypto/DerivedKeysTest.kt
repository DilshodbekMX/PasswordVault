package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys that typed master passwords give: the two derived keys of lock.md §4.3 (T2's "Argon2id
 * outputs") and the KEK of the vault vectors of architecture.md §4.13, each from the characters
 * through `MasterPasswordBytes.of` and Argon2id with [Argon2Params.V1] and the salt `00 01 … 0f`.
 * The last test runs the chain an unlock runs on a phone without argon2kt: [MasterKdf] with its
 * gate, the guard taking the fallback, Bouncy Castle, and then the password slot of vector A opened
 * with the key.
 */
class DerivedKeysTest {

    private val salt = FakeRandom.sequence(0x00, 16)
    private val argon2 = CryptoFactory.bouncyCastleArgon2id()

    private fun keyOf(typed: String): String {
        val bytes = MasterPasswordBytes.of(typed.toCharArray())
        try {
            return Bytes.hex(argon2.derive(bytes, salt, Argon2Params.V1))
        } finally {
            Bytes.wipe(bytes)
        }
    }

    @Test
    fun lockMd43_everySpellingOfTheUzbekWordGivesTheListedKey() {
        // "oʻgʻlim-Toʻra"; every spelling gives the bytes 6f2767276c696d2d546f277261 (MasterPasswordBytesTest).
        val spellings = listOf(
            "o\u02BBg\u02BBlim-To\u02BBra", // U+02BB, the Uzbek letter
            "o\u2018g\u2018lim-To\u2018ra", // U+2018
            "o\u0027g\u0027lim-To\u0027ra", // U+0027
            "o\u2019g\u02BClim-To\u0060ra", // U+2019, U+02BC, U+0060
            "o\u00B4g\uFF07lim-To\u2032ra", // U+00B4, U+FF07, U+2032
            "o\u0374g\u1FEFlim-To\u1FFDra", // the Greek signs, which NFC turns into U+02B9, U+0060, U+00B4
        )
        for (spelling in spellings) assertEquals(spelling, UZBEK_WORD_KEY, keyOf(spelling))
    }

    @Test
    fun lockMd43_theDecomposedSpelling() {
        // "Köz-Ўzbek" typed decomposed: o + U+0308, U+0423 + U+0306.
        assertEquals("9f05463cb3a665fb4e22120cdad867630f76f12dc5ffdf4efaaa589f5baad5fa", keyOf("Ko\u0308z-\u0423\u0306zbek"))
    }

    @Test
    fun a413_fromTheTypedCharactersToTheVaultKey_withoutArgon2kt() = runBlocking {
        // A leading space, two spaces, a no-break space and a trailing space (architecture.md §4.13).
        val typed = " correct  horse\u00A0battery staple ".toCharArray()
        val kdf = CryptoFactory.masterKdf(
            CryptoFactory.guardedArgon2id(
                native = null,
                fallback = CryptoFactory.bouncyCastleArgon2id(),
                freeHeap = { Long.MAX_VALUE },
                fallbackNeeds = 96L * 1024 * 1024,
                collect = {},
            ),
            Dispatchers.Default,
        )
        val bytes = MasterPasswordBytes.of(typed)
        val kek = try {
            kdf.kek(bytes, salt)
        } finally {
            Bytes.wipe(bytes)
        }
        assertEquals("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e", Bytes.hex(kek))

        val wrappedKey = Bytes.fromHex(
            "4da4366623849589554fe3243a37b064a3117364b4ba9993b6a7c320723a8192ebc1ed80fb8f15516b561c63de23172b",
        )
        val vk = CryptoFactory.aead().open(kek, FakeRandom.sequence(0x20, 12), wrappedKey, null)
        assertArrayEquals(FakeRandom.sequence(0xa0, 32), vk)
    }

    private companion object {
        const val UZBEK_WORD_KEY = "35914871c9a019bf3fa9ae69825be6a300a86905bcf69ca6bb84de0a3064b26b"
    }
}
