package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.BODY_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.BODY_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.CREATED_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.CREATED_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.HEADER_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.KEK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.NONCE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.NONCE_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SALT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT_NONCE
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_B
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.WRAPPED_KEY_HEX
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.sha256Hex
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.utf8
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.util.Locale
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors A and B of architecture.md §4.13, byte for byte, both ways (§8.1 `vaultfile`): A from its
 * plaintext bytes through the container, B from its [Vault] value through `BodyCodec` and the
 * container, and both read back with `check`, `unwrap` and `open`.
 */
class VaultVectorsTest {
    @Test
    fun theCommittedFilesAreTheVectors() {
        assertEquals(4_667, FILE_A.size)
        assertEquals("3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d", sha256Hex(FILE_A))
        assertEquals(4_667, FILE_B.size)
        assertEquals("1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7", sha256Hex(FILE_B))
    }

    @Test
    fun vectorA_theSlotIsWrappedFromItsInputs() {
        val random = FakeRandom().queueBytes(SLOT_NONCE)
        val slot = codec(random).wrap(VK, KEK, SALT)
        assertArrayEquals(SALT, slot.salt)
        assertArrayEquals(SLOT_NONCE, slot.nonce)
        assertEquals(WRAPPED_KEY_HEX, Bytes.hex(slot.wrappedKey))
        assertEquals("one fresh 12-byte slot nonce", listOf(12), random.byteRequests)
    }

    @Test
    fun vectorA_isMadeFromItsPlaintextThroughTheContainer() {
        val random = FakeRandom().queueBytes(NONCE_A)
        val file = codec(random, FakeClock(wallMs = CREATED_A)).sealJson(utf8(BODY_A), VK, VAULT_ID, VK_ID, 1, SLOT)
        assertArrayEquals(FILE_A, file)
        assertEquals("one fresh 12-byte body nonce", listOf(12), random.byteRequests)
    }

    @Test
    fun vectorA_readsBack() {
        val codec = codec()
        val ok = codec.check(FILE_A) as FileCheck.Ok
        val file = ok.file
        val h = file.header
        assertEquals(VAULT_ID, h.vaultId)
        assertEquals(VK_ID, h.vkId)
        assertEquals(1L, h.generation)
        assertEquals(CREATED_A, h.createdMs)
        assertEquals("PasswordVault 1.0", h.generator)
        assertEquals(1, h.writerVersion)
        assertEquals(4_112, h.bodyLen)
        assertEquals(SLOT, h.slot)
        assertTrue(file.trailerOk)

        assertEquals(499, HEADER_A.length)
        assertArrayEquals(utf8("PVAULT01") + Vectors.u32(499) + utf8(HEADER_A), file.aad)
        assertEquals("adfd3efa701b6e18938f770551cf86b990d3c651fe7016769fccf2f90fce15f0", sha256Hex(file.aad))
        assertArrayEquals(NONCE_A, file.bodyNonce)
        assertEquals(4_112, file.bodyCt.size)
        assertEquals("b725d6eb1c28bcaf9cab305f5a362876", Bytes.hex(file.bodyCt.copyOfRange(4_096, 4_112)))
        assertEquals("bb6953c8aec55e24dfb00e016dbd49268fbcab8ce5df20854416862ba0ce4e2a", Bytes.hex(FILE_A.copyOfRange(4_635, 4_667)))

        val vk = codec.unwrap(h.slot, KEK)
        assertArrayEquals(VK, vk)
        // Every reader must open A: its missing keys have defaults (§4.13.1).
        assertEquals(BodyResult.Opened(Vault()), codec.open(file, VK))
        assertArrayEquals(Vectors.padded(utf8(BODY_A)), Vectors.decryptBody(file))
    }

    @Test
    fun vectorA_theAadWithTheBodyNonceFails() {
        val file = (codec().check(FILE_A) as FileCheck.Ok).file
        val withNonce = VaultFile(file.header, file.aad + file.bodyNonce, file.bodyNonce, file.bodyCt, file.trailerOk)
        // A reader that opens the body that way is wrong [V-C3]: the tag fails, here and in the JDK.
        assertEquals(BodyResult.TagFailed, codec().open(withNonce, VK))
        assertNull(TestAead().open(VK, file.bodyNonce, file.bodyCt, file.aad + file.bodyNonce))
    }

    @Test
    fun vectorB_theBodyIsTheWritersCanonicalForm() {
        val body = BodyCodec.write(VAULT_B)
        assertEquals(1_448, body.size)
        assertEquals("610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7", sha256Hex(body))
        assertArrayEquals(utf8(BODY_B), body)
        // The canonical form is kotlinx-serialization's own compact text of the same tree (§4.3).
        assertEquals(BODY_B, StrictJson.parse(BODY_B, 16).toString())
    }

    @Test
    fun vectorB_isMadeFromItsVaultValueThroughTheContainer() {
        val random = FakeRandom().queueBytes(NONCE_B)
        val file = codec(random, FakeClock(wallMs = CREATED_B)).seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
        assertArrayEquals(FILE_B, file)
        assertEquals(listOf(12), random.byteRequests)
    }

    @Test
    fun vectorB_readsBack() {
        val codec = codec()
        val file = (codec.check(FILE_B) as FileCheck.Ok).file
        val h = file.header
        assertEquals(7L, h.generation)
        assertEquals(CREATED_B, h.createdMs)
        assertEquals(4_112, h.bodyLen)
        assertEquals(SLOT, h.slot)
        assertTrue(file.trailerOk)
        assertEquals(HEADER_A.replace("\"generation\":1,", "\"generation\":7,").replace("1791244800000", "1791331200000"), String(file.aad, 12, 499, Charsets.US_ASCII))
        assertEquals("5e76cebd4bd31f0794d5da6ef151ef7b", Bytes.hex(file.bodyCt.copyOfRange(4_096, 4_112)))
        assertEquals("a12f31da424719de8cb33cbbad1accaae5ec6e4553dd2b2db1bc7748643afd3d", Bytes.hex(FILE_B.copyOfRange(4_635, 4_667)))

        assertArrayEquals(VK, codec.unwrap(h.slot, KEK))
        val opened = codec.open(file, VK) as BodyResult.Opened
        assertEquals(VAULT_B, opened.vault)
        // Order matters to the writer, and Map equality ignores it.
        assertEquals(listOf("futureTopKey"), opened.vault.extras.keys.toList())
        assertEquals("""{"n":1.50,"list":[true,null,"x"]}""", opened.vault.extras["futureTopKey"])
        assertEquals("\"kept\"", opened.vault.entries[0].extras["futureEntryKey"])
        val plaintext = Vectors.decryptBody(file)!!
        assertEquals(4_096, plaintext.size)
        assertEquals("610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7", sha256Hex(plaintext.copyOf(1_448)))
        assertTrue("2,648 spaces of padding", plaintext.copyOfRange(1_448, 4_096).all { it == 0x20.toByte() })
    }

    @Test
    fun readingAndWritingAgainGivesTheSameBytes() {
        val codec = codec()
        val vault = (codec.open((codec.check(FILE_B) as FileCheck.Ok).file, VK) as BodyResult.Opened).vault
        val again = codec(FakeRandom().queueBytes(NONCE_B), FakeClock(wallMs = CREATED_B)).seal(vault, VK, VAULT_ID, VK_ID, 7, SLOT)
        assertArrayEquals(FILE_B, again)
    }

    @Test
    fun theVectorsDoNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("tr-TR", "fa-IR", "ar-SA", "th-TH-u-nu-thai", "uz-Cyrl-UZ", "ru-RU")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val b = codec(FakeRandom().queueBytes(NONCE_B), FakeClock(wallMs = CREATED_B)).seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
                assertArrayEquals(tag, FILE_B, b)
                val a = codec(FakeRandom().queueBytes(NONCE_A), FakeClock(wallMs = CREATED_A)).sealJson(utf8(BODY_A), VK, VAULT_ID, VK_ID, 1, SLOT)
                assertArrayEquals(tag, FILE_A, a)
                assertEquals(tag, VAULT_B, (codec().open((codec().check(FILE_B) as FileCheck.Ok).file, VK) as BodyResult.Opened).vault)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    /**
     * The white-space vector of §4.13 gives the 28 bytes of "correct horse battery staple", whose
     * Argon2id KEK with salt 00 … 0f opens vector A's slot: the whole way from a typed password to
     * VK. The KDF is Bouncy Castle's, as in `Argon2KnownAnswerTest` (package 2.1 is built beside this).
     */
    @Test
    fun vectorA_opensWithItsMasterPassword() {
        val typed = " correct  horse battery staple ".toCharArray()
        val passwordBytes = MasterPasswordBytes.of(typed)
        assertEquals("636f727265637420686f727365206261747465727920737461706c65", Bytes.hex(passwordBytes))
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(SALT)
            .withMemoryAsKB(65_536)
            .withIterations(3)
            .withParallelism(4)
            .build()
        val kek = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passwordBytes, kek)
        assertArrayEquals(KEK, kek)
        val file = (codec().check(FILE_A) as FileCheck.Ok).file
        assertArrayEquals(VK, codec().unwrap(file.header.slot, kek))
    }
}
