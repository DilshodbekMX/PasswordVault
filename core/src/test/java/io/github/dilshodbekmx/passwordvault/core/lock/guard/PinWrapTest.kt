package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.NONCE
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.RIGHT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.SLOT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.VK_ID
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.WRONG
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.pin
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T2, the PIN part (build plan 2.11): the PIN vector of §4.3, `pin_msg`, `KEK_pin` and
 * the wrap, byte for byte, by [PinWrap], by plain JCA, through `FakeKeys` and through [PinGuard];
 * PIN 582914 and the ids swapped in the AAD fail the tag. (The master-password part of T2 is
 * `MasterPasswordBytesTest`'s, package 1.3.)
 */
class PinWrapTest {
    private val f = PinFixture()

    @Test
    fun t2_pinMessageIsTheVector() {
        val message = PinWrap.message(VAULT, SLOT, RIGHT)
        assertEquals(PIN_MSG, Bytes.hex(message))
        assertEquals(PinWrap.MESSAGE_SIZE, message.size)
        assertEquals(56, message.size)
    }

    @Test
    fun t2_kekPinIsTheVector() {
        val message = PinWrap.message(VAULT, SLOT, RIGHT)
        // plain JCA, as the Keystore's HMAC key computes it inside the secure hardware
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32) { 0x42 }, "HmacSHA256"))
        assertEquals(KEK_PIN, Bytes.hex(mac.doFinal(message)))
        // the fake Keystore of :testing
        val viaFake = f.keys.pin.hmac(PinWrap.alias(SLOT), message)
        assertTrue(viaFake is Ks.Ok)
        assertEquals(KEK_PIN, Bytes.hex((viaFake as Ks.Ok).value))
    }

    @Test
    fun t2_aadPinIsTheVector() {
        val aad = PinWrap.aad(VAULT, VK_ID, SLOT)
        assertEquals(AAD_PIN, Bytes.hex(aad))
        assertEquals(PinWrap.AAD_SIZE, aad.size)
        assertEquals(67, aad.size)
    }

    @Test
    fun t2_wrapIsTheVector() {
        val kek = Bytes.fromHex(KEK_PIN)
        val wrap = PinWrap.seal(f.aead, kek, NONCE, VK, VAULT, VK_ID, SLOT)
        assertEquals(WRAP, Bytes.hex(wrap.ct))
        assertEquals(PinWrap.WRAP_SIZE, wrap.ct.size)
        assertEquals(VK_ID, wrap.vkId)
        assertArrayEquals(NONCE, wrap.nonce)
        // and with plain JCA, as lock.md appendix C did
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, NONCE))
        cipher.updateAAD(Bytes.fromHex(AAD_PIN))
        assertEquals(WRAP, Bytes.hex(cipher.doFinal(VK)))
        // the fixture's slot, made through the fake Keystore, holds the same bytes
        assertEquals(WRAP, Bytes.hex(f.slot.wraps.single().ct))
    }

    @Test
    fun t2_theWrapOpensWithTheRightPinOnly() {
        val kek = Bytes.fromHex(KEK_PIN)
        val wrap = KeyWrap(VK_ID, NONCE, Bytes.fromHex(WRAP))
        assertArrayEquals(VK, PinWrap.open(f.aead, kek, wrap, VAULT, SLOT))

        // PIN 582914 gives another KEK_pin, and the tag fails
        val other = f.keys.hmacOf(PinWrap.alias(SLOT), PinWrap.message(VAULT, SLOT, WRONG))
        assertFalse(Bytes.equal(kek, other))
        assertNull(PinWrap.open(f.aead, other, wrap, VAULT, SLOT))

        // vk_id and slot_id swapped in the AAD: AEADBadTagException in plain JCA, null here
        val swapped = Bytes.fromHex(AAD_PIN_SWAPPED)
        assertArrayEquals(swapped, PinWrap.aad(VAULT, SLOT, VK_ID))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, NONCE))
        cipher.updateAAD(swapped)
        try {
            cipher.doFinal(Bytes.fromHex(WRAP))
            throw AssertionError("the tag should have failed")
        } catch (expected: AEADBadTagException) {
        }
        assertNull(f.aead.open(kek, NONCE, Bytes.fromHex(WRAP), swapped))
        assertNull(PinWrap.open(f.aead, kek, KeyWrap(SLOT, NONCE, Bytes.fromHex(WRAP)), VAULT, VK_ID))
    }

    @Test
    fun t2_throughTheGuardTheRightPinYieldsVkAnd582914IsWrong() {
        val guard = f.started()
        val wrong = guard.enter(WRONG, Place.visit(1))
        assertEquals(PinOutcome.Wrong(4), wrong)

        val right = guard.enter(RIGHT, Place.visit(1))
        assertTrue(right is PinOutcome.Right)
        val keys = (right as PinOutcome.Right).keys
        assertEquals(setOf(VK_ID), keys.keys)
        assertArrayEquals(VK, keys.getValue(VK_ID))
        // both checks asked the Keystore for the message of the vector's slot
        val calls = f.keys.hmacCalls
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.alias == "pv_pin_$SLOT" })
        assertEquals(PIN_MSG, Bytes.hex(calls[1].message))
    }

    @Test
    fun t2_onlySixAsciiDigitsAreAPin() {
        assertTrue(PinWrap.isPin(pin("000000")))
        assertTrue(PinWrap.isPin(pin("999999")))
        for (bad in listOf("58291", "5829130", "58291a", "58291 ", "５８２９１３")) {
            assertFalse(bad, PinWrap.isPin(bad.toByteArray(Charsets.UTF_8)))
        }
        assertFalse(PinWrap.isPin(byteArrayOf(0x35, 0x38, 0x32, 0x39, 0x31, 0x2f))) // "/" is just below "0"
        assertFalse(PinWrap.isPin(byteArrayOf(0x35, 0x38, 0x32, 0x39, 0x31, 0x3a))) // ":" is just above "9"
        try {
            PinWrap.message(VAULT, SLOT, pin("12345"))
            throw AssertionError("a five-digit PIN was accepted")
        } catch (e: IllegalArgumentException) {
            assertFalse("the message holds no input", e.message.orEmpty().contains("12345"))
        }
        assertEquals("pv_pin_$SLOT", PinWrap.alias(SLOT))
    }

    private companion object {
        // lock.md §4.3, the PIN vector
        const val PIN_MSG = "70766c6f636b2d76313a70696e2d6b656b00" + "00000000000040008000000000000001" +
            "00000000000040008000000000000003" + "353832393133"
        const val KEK_PIN = "344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74"
        const val AAD_PIN = "70766c6f636b2d76313a70696e2d7772617000" + "00000000000040008000000000000001" +
            "00000000000040008000000000000002" + "00000000000040008000000000000003"
        const val AAD_PIN_SWAPPED = "70766c6f636b2d76313a70696e2d7772617000" + "00000000000040008000000000000001" +
            "00000000000040008000000000000003" + "00000000000040008000000000000002"
        const val WRAP = "e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02"
    }
}
