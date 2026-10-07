package io.github.dilshodbekmx.passwordvault.vectors

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.platform.NativeArgon2id
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KOZ_BYTES
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KOZ_DECOMPOSED
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.KOZ_KEY
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.UZBEK_BYTES
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.UZBEK_KEY
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.UZBEK_SPELLINGS
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.sequence
import io.github.dilshodbekmx.passwordvault.vectors.DeviceVectors.utf8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: the vectors of lock.md §4.3 on the phone (lock.md
 * A5): the PIN message, `KEK_pin` and the wrap, with the platform's HMAC-SHA256 and the app's
 * AES-GCM; and the keys of the master-password vectors, from the typed characters through
 * `MasterPasswordBytes` (the phone's ICU) and Argon2id, with argon2kt and with Bouncy Castle.
 */
@RunWith(AndroidJUnit4::class)
class LockVectorsDeviceTest {
    private val vaultId = Uuid.uuid16("00000000-0000-4000-8000-000000000001")
    private val vkId = Uuid.uuid16("00000000-0000-4000-8000-000000000002")
    private val slotId = Uuid.uuid16("00000000-0000-4000-8000-000000000003")

    /** The test stand-in for the PIN key: the real one never leaves the Keystore. */
    private val kPin = ByteArray(32) { 0x42 }
    private val vk = sequence(0xa0, 32)
    private val nonce = sequence(0x10, 12)
    private val salt = sequence(0x00, 16)

    private fun pinMessage(pin: String) = utf8("pvlock-v1:pin-kek") + 0x00 + vaultId + slotId + utf8(pin)

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    @Test
    fun thePinWrap() {
        val message = pinMessage("582913")
        assertEquals(56, message.size)
        assertEquals(
            "70766c6f636b2d76313a70696e2d6b656b00" + "00000000000040008000000000000001" + "00000000000040008000000000000003" + "353832393133",
            Bytes.hex(message),
        )
        val kek = hmac(kPin, message)
        assertEquals("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74", Bytes.hex(kek))
        val aad = utf8("pvlock-v1:pin-wrap") + 0x00 + vaultId + vkId + slotId
        assertEquals(67, aad.size)
        assertEquals(
            "70766c6f636b2d76313a70696e2d7772617000" + "00000000000040008000000000000001" + "00000000000040008000000000000002" +
                "00000000000040008000000000000003",
            Bytes.hex(aad),
        )
        val aead = CryptoFactory.aead()
        val wrap = aead.seal(kek, nonce, vk, aad)
        assertEquals(
            "e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02",
            Bytes.hex(wrap),
        )
        assertArrayEquals(vk, aead.open(kek, nonce, wrap, aad))
        // PIN 582914, or vk_id and slot_id swapped in the AAD: the tag fails.
        assertNull(aead.open(hmac(kPin, pinMessage("582914")), nonce, wrap, aad))
        assertNull(aead.open(kek, nonce, wrap, utf8("pvlock-v1:pin-wrap") + 0x00 + vaultId + slotId + vkId))
        val pending = utf8("pvlock-v1:pending-wrap") + 0x00 + vaultId + vkId
        assertEquals("AAD_pending", 55, pending.size)
    }

    private fun keyOf(argon2: Argon2id, typed: String): String {
        val bytes = MasterPasswordBytes.of(typed.toCharArray())
        try {
            return Bytes.hex(argon2.derive(bytes, salt, Argon2Params.V1))
        } finally {
            Bytes.wipe(bytes)
        }
    }

    @Test
    fun theMasterPasswordKeysWithArgon2kt() {
        val native = NativeArgon2id.load() ?: throw AssertionError("argon2kt does not load on this device")
        for (spelling in UZBEK_SPELLINGS) {
            assertEquals(spelling, UZBEK_BYTES, Bytes.hex(MasterPasswordBytes.of(spelling.toCharArray())))
            assertEquals(spelling, UZBEK_KEY, keyOf(native, spelling))
        }
        assertEquals(KOZ_BYTES, Bytes.hex(MasterPasswordBytes.of(KOZ_DECOMPOSED.toCharArray())))
        assertEquals(KOZ_KEY, keyOf(native, KOZ_DECOMPOSED))
    }

    @Test
    fun theMasterPasswordKeysWithBouncyCastle() {
        val bouncyCastle = CryptoFactory.bouncyCastleArgon2id()
        assertEquals(UZBEK_KEY, keyOf(bouncyCastle, UZBEK_SPELLINGS.first()))
        assertEquals("the Greek signs", UZBEK_KEY, keyOf(bouncyCastle, UZBEK_SPELLINGS.last()))
        assertEquals(KOZ_KEY, keyOf(bouncyCastle, KOZ_DECOMPOSED))
    }
}
