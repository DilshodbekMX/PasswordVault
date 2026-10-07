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
    fun lockMd43_theUzbekWord() {
        // "oʻgʻlim-Toʻra" with U+02BB, whose bytes are 6f2767276c696d2d546f277261 (MasterPasswordBytesTest).
        assertEquals(UZBEK_WORD_KEY, keyOf("oʻgʻlim-Toʻra"))
    }

    @Test
    fun lockMd43_theUzbekWordTypedWithTheGreekSigns_givesTheSameKey() {
        // U+0374, U+1FEF and U+1FFD, which NFC turns into members of the apostrophe list.
        assertEquals(UZBEK_WORD_KEY, keyOf("oʹg`lim-To´ra"))
    }

    @Test
    fun lockMd43_theDecomposedSpelling() {
        // "Köz-Ўzbek" typed decomposed: o + U+0308, У + U+0306.
        assertEquals("9f05463cb3a665fb4e22120cdad867630f76f12dc5ffdf4efaaa589f5baad5fa", keyOf("Köz-Ўzbek"))
    }

    @Test
    fun a413_fromTheTypedCharactersToTheVaultKey_withoutArgon2kt() = runBlocking {
        // A leading space, two spaces, a no-break space and a trailing space (architecture.md §4.13).
        val typed = " correct  horse battery staple ".toCharArray()
        val kdf = CryptoFactory.masterKdf(
            CryptoFactory.guardedArgon2id(
                native = null,
                fallback = CryptoFactory.bouncyCastleArgon2id(),
                freeHeap = { Long.MAX_VALUE },
                fallbackNeeds = 96L * 1024 * 1024,
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
