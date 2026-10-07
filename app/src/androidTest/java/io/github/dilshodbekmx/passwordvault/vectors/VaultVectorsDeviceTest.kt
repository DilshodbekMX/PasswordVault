package io.github.dilshodbekmx.passwordvault.vectors

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultFile
import io.github.dilshodbekmx.passwordvault.platform.NativeArgon2id
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.AAD_A_SHA256
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.BODY_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.BODY_B_SHA256
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.BUILD
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.CREATED_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.CREATED_B
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.FILE_A_SHA256
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.FILE_B_SHA256
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.FILE_SIZE
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.HEADER_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KEK
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KEK_HEX
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.NONCE_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.NONCE_B
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.SALT
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.SLOT
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.SLOT_NONCE
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.TAG_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.TAG_B
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.TRAILER_A
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.TRAILER_B
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.VAULT_B
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.VK
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.VK_ID
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.WRAPPED_KEY_HEX
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.padded
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.sha256Hex
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.u32
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.utf8
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: the vault vectors of architecture.md §4.13 on
 * the phone (§8.3; lock.md A5), through the real codec of :core over the phone's own AES-GCM
 * (Conscrypt) and SHA-256.
 *
 * - **Vector A** is reproduced: its slot by the codec's `wrap`, and the whole file from its
 *   plaintext bytes, put together as the appendix script does it (the codec writes only `Vault`
 *   values, and A's body is not what a writer produces), which must be the committed file, byte for
 *   byte (its SHA-256). Then it is opened through the codec, also from the typed password with
 *   `NativeArgon2id` and with Bouncy Castle.
 * - **Vector B** is made by the codec from its `Vault` value, byte for byte, and opened again.
 */
@RunWith(AndroidJUnit4::class)
class VaultVectorsDeviceTest {
    private val aead = CryptoFactory.aead()

    private fun codec(random: FakeRandom = FakeRandom(), clock: FakeClock = FakeClock()): VaultCodec =
        VaultCodecs.create(aead, random, clock, BUILD)

    /** Vector A from its inputs: magic ‖ header_len ‖ header ‖ nonce ‖ AES-256-GCM(VK, nonce, plaintext, AAD) ‖ SHA-256. */
    private fun fileA(): ByteArray {
        val header = utf8(HEADER_A)
        val aad = utf8("PVAULT01") + u32(header.size) + header
        val withoutTrailer = aad + NONCE_A + aead.seal(VK, NONCE_A, padded(utf8(BODY_A)), aad)
        return withoutTrailer + MessageDigest.getInstance("SHA-256").digest(withoutTrailer)
    }

    /** Vector B, made by the codec from its `Vault` value. */
    private fun fileB(): ByteArray {
        val random = FakeRandom().queueBytes(NONCE_B)
        val file = codec(random, FakeClock(wallMs = CREATED_B)).seal(VAULT_B, VK, VAULT_ID, VK_ID, 7, SLOT)
        assertEquals("one fresh 12-byte body nonce", listOf(12), random.byteRequests)
        return file
    }

    @Test
    fun vectorA_theSlotIsWrappedFromItsInputs() {
        val random = FakeRandom().queueBytes(SLOT_NONCE)
        val slot = codec(random).wrap(VK, KEK, SALT)
        assertArrayEquals(SALT, slot.salt)
        assertArrayEquals(SLOT_NONCE, slot.nonce)
        assertEquals(WRAPPED_KEY_HEX, Bytes.hex(slot.wrappedKey))
    }

    @Test
    fun vectorA_isReproducedByteForByte() {
        val file = fileA()
        assertEquals(FILE_SIZE, file.size)
        assertEquals(FILE_A_SHA256, sha256Hex(file))
    }

    @Test
    fun vectorA_opensThroughTheCodec() {
        val bytes = fileA()
        val codec = codec()
        val file = (codec.check(bytes) as FileCheck.Ok).file
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
        assertEquals(AAD_A_SHA256, sha256Hex(file.aad))
        assertArrayEquals(NONCE_A, file.bodyNonce)
        assertEquals(TAG_A, Bytes.hex(file.bodyCt.copyOfRange(4_096, 4_112)))
        assertEquals(TRAILER_A, Bytes.hex(bytes.copyOfRange(4_635, 4_667)))
        assertArrayEquals(VK, codec.unwrap(h.slot, KEK))
        // Every reader must open A: its missing keys have defaults (§4.13.1).
        assertEquals(BodyResult.Opened(Vault()), codec.open(file, VK))
    }

    @Test
    fun vectorA_theAadWithTheBodyNonceFails() {
        val file = (codec().check(fileA()) as FileCheck.Ok).file
        val withNonce = VaultFile(file.header, file.aad + file.bodyNonce, file.bodyNonce, file.bodyCt, file.trailerOk)
        assertEquals(BodyResult.TagFailed, codec().open(withNonce, VK))
        assertNull(aead.open(VK, file.bodyNonce, file.bodyCt, file.aad + file.bodyNonce))
    }

    @Test
    fun vectorA_opensFromTheTypedPassword() {
        // A leading space, two spaces, a no-break space and a trailing space: the 28 bytes of
        // "correct horse battery staple", whose key opens A's slot (§4.13).
        val passwordBytes = MasterPasswordBytes.of(" correct  horse battery staple ".toCharArray())
        assertEquals("636f727265637420686f727365206261747465727920737461706c65", Bytes.hex(passwordBytes))
        val native = NativeArgon2id.load() ?: throw AssertionError("argon2kt does not load on this device")
        val file = (codec().check(fileA()) as FileCheck.Ok).file
        for ((name, argon2) in listOf("argon2kt" to native, "Bouncy Castle" to CryptoFactory.bouncyCastleArgon2id())) {
            val kek = argon2.derive(passwordBytes, file.header.slot.salt, Argon2Params.V1)
            assertEquals(name, KEK_HEX, Bytes.hex(kek))
            val vk = codec().unwrap(file.header.slot, kek)
            assertArrayEquals(name, VK, vk)
            assertEquals(name, BodyResult.Opened(Vault()), codec().open(file, vk!!))
        }
    }

    @Test
    fun vectorB_isMadeFromItsVaultValueByteForByte() {
        val file = fileB()
        assertEquals(FILE_SIZE, file.size)
        assertEquals(FILE_B_SHA256, sha256Hex(file))
    }

    @Test
    fun vectorB_opensThroughTheCodec() {
        val bytes = fileB()
        val codec = codec()
        val file = (codec.check(bytes) as FileCheck.Ok).file
        val h = file.header
        assertEquals(7L, h.generation)
        assertEquals(CREATED_B, h.createdMs)
        assertEquals(4_112, h.bodyLen)
        assertEquals(SLOT, h.slot)
        assertTrue(file.trailerOk)
        assertEquals(
            HEADER_A.replace("\"generation\":1,", "\"generation\":7,").replace("1791244800000", "1791331200000"),
            String(file.aad, 12, 499, Charsets.US_ASCII),
        )
        assertEquals(TAG_B, Bytes.hex(file.bodyCt.copyOfRange(4_096, 4_112)))
        assertEquals(TRAILER_B, Bytes.hex(bytes.copyOfRange(4_635, 4_667)))
        assertArrayEquals(VK, codec.unwrap(h.slot, KEK))
        val opened = codec.open(file, VK) as BodyResult.Opened
        assertEquals(VAULT_B, opened.vault)
        assertEquals("order matters to the writer", listOf("futureTopKey"), opened.vault.extras.keys.toList())
        assertEquals("""{"n":1.50,"list":[true,null,"x"]}""", opened.vault.extras["futureTopKey"])
        assertEquals("\"kept\"", opened.vault.entries[0].extras["futureEntryKey"])
        val plaintext = aead.open(VK, file.bodyNonce, file.bodyCt, file.aad)!!
        assertEquals(4_096, plaintext.size)
        assertEquals(BODY_B_SHA256, sha256Hex(plaintext.copyOf(1_448)))
        assertTrue("2,648 spaces of padding", plaintext.copyOfRange(1_448, 4_096).all { it == 0x20.toByte() })
    }

    @Test
    fun readingBAndWritingItAgainGivesTheSameBytes() {
        val vault = (codec().open((codec().check(fileB()) as FileCheck.Ok).file, VK) as BodyResult.Opened).vault
        val again = codec(FakeRandom().queueBytes(NONCE_B), FakeClock(wallMs = CREATED_B)).seal(vault, VK, VAULT_ID, VK_ID, 7, SLOT)
        assertEquals(FILE_B_SHA256, sha256Hex(again))
    }

    @Test
    fun theVectorsDoNotDependOnTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("tr-TR", "fa-IR", "ar-SA", "th-TH-u-nu-thai", "uz-Cyrl-UZ", "uz-Latn-UZ", "ru-RU")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, FILE_B_SHA256, sha256Hex(fileB()))
                assertEquals(tag, FILE_A_SHA256, sha256Hex(fileA()))
                assertEquals(tag, VAULT_B, (codec().open((codec().check(fileB()) as FileCheck.Ok).file, VK) as BodyResult.Opened).vault)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
