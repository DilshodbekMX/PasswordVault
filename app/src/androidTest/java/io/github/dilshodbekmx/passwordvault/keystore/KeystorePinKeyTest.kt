package io.github.dilshodbekmx.passwordvault.keystore

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinCharge
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.sequence
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.softwareHmac
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37 (debug build, software keys allowed):
 * [KeystorePinKey] over the real Android Keystore. The PIN vector of lock.md §4.3 through a real
 * HMAC key gives a wrap that opens, and through the vector's own key, put into the Keystore, the
 * vector's bytes; the key is made and deleted; and the counter of lock.md §6.3 (`PinGuard`) checks
 * a PIN through it, as check E1 will through the whole app.
 */
@RunWith(AndroidJUnit4::class)
class KeystorePinKeyTest {
    private val kit = KeystoreKit()
    private val access = KeystoreAccess(allowSoftwareKeys = true)
    private val pin = KeystorePinKey(access)
    private val aead = CryptoFactory.aead()

    // The vector of lock.md §4.3.
    private val vaultId = "00000000-0000-4000-8000-000000000001"
    private val vkId = "00000000-0000-4000-8000-000000000002"
    private val slotId = "00000000-0000-4000-8000-000000000003"
    private val vk = sequence(0xa0, 32)
    private val nonce = sequence(0x10, 12)
    private val pin582913 = "582913".toByteArray(Charsets.US_ASCII)
    private val pin582914 = "582914".toByteArray(Charsets.US_ASCII)
    private val pinMessageHex =
        "70766c6f636b2d76313a70696e2d6b656b00" + "00000000000040008000000000000001" + "00000000000040008000000000000003" + "353832393133"

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() = kit.cleanUp()

    private fun kek(alias: String, message: ByteArray): ByteArray {
        val answer = pin.hmac(alias, message)
        if (answer !is Ks.Ok) throw AssertionError("the HMAC of $alias: $answer")
        return answer.value
    }

    @Test
    fun createMakesTheHmacKeyOfLockMd42() {
        val alias = kit.pinAlias()
        assertFalse(kit.has(alias))
        assertEquals(KeyCreate.Ok, pin.create(alias))
        assertEquals("HmacSHA256", kit.key(alias)!!.algorithm)
        val info = kit.info(alias)
        assertEquals(256, info.keySize)
        assertEquals("for signing only", KeyProperties.PURPOSE_SIGN, info.purposes)
        assertFalse("no user authentication: the PIN works after a reboot and after a new finger", info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        assertNull("no validity period", info.keyValidityForOriginationEnd)
        assertNull(info.keyValidityForConsumptionEnd)
        assertNull(info.keyValidityStart)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals("no usage count", KeyProperties.UNRESTRICTED_USAGE_COUNT, info.remainingUsageCount)
        }
        if (Build.VERSION.SDK_INT >= 37) {
            assertTrue("API 35 and newer: usable only while the phone is unlocked", info.isUnlockedDeviceRequired)
        }
        assertTrue(alias in access.aliases())
    }

    @Test
    fun createTakesOnlyAPinAlias() {
        for (alias in listOf("pv_fp_" + kit.newId(), "pv_canary", "pin", "")) {
            try {
                pin.create(alias)
                fail("an alias that is not a PIN's: '$alias'")
            } catch (e: IllegalArgumentException) {
                assertFalse(alias in kit.keyStore().aliases().toList())
            }
        }
    }

    @Test
    fun thePinVectorThroughARealHmacKeyGivesAWrapThatOpens() {
        // The alias of the vector's slot, with a key the Keystore made and never shows.
        val alias = kit.pinAlias(slotId)
        assertEquals(PinWrap.alias(slotId), alias)
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertEquals(KeyCreate.Ok, pin.create(alias))

        val message = PinWrap.message(vaultId, slotId, pin582913)
        assertEquals(56, message.size)
        assertEquals(pinMessageHex, Bytes.hex(message))
        val kek = kek(alias, message)
        assertEquals("KEK_pin is used directly as an AES-256 key", 32, kek.size)

        // F6 steps 5 and 6: wrap VK, then compute the HMAC again, open the new wrap and compare.
        val wrap = PinWrap.seal(aead, kek, nonce, vk, vaultId, vkId, slotId)
        assertEquals(48, wrap.ct.size)
        val again = kek(alias, message)
        assertArrayEquals("the same key gives the same KEK", kek, again)
        assertArrayEquals("the wrap opens", vk, PinWrap.open(aead, again, wrap, vaultId, slotId))

        // PIN 582914, or vk_id and slot_id swapped in the AAD: AEADBadTagException.
        val wrongKek = kek(alias, PinWrap.message(vaultId, slotId, pin582914))
        assertFalse(kek.contentEquals(wrongKek))
        assertNull("a wrong PIN gives another key and the tag fails", PinWrap.open(aead, wrongKek, wrap, vaultId, slotId))
        assertNull(aead.open(kek, wrap.nonce, wrap.ct, PinWrap.aad(vaultId, slotId, vkId)))
        assertNotNull(aead.open(kek, wrap.nonce, wrap.ct, PinWrap.aad(vaultId, vkId, slotId)))

        // The key of another slot does not open it: every PIN that is set has a key of its own.
        val other = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(other))
        val otherKek = kek(other, message)
        assertFalse(kek.contentEquals(otherKek))
        assertNull(PinWrap.open(aead, otherKek, wrap, vaultId, slotId))

        // The vector's own K_pin is 42 × 32; the Keystore's key is random, so the bytes differ.
        assertFalse("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74" == Bytes.hex(kek))
    }

    @Test
    fun thePinVectorThroughItsOwnKeyInTheKeystoreIsTheVectorByteForByte() {
        // "K_pin (test stand-in; the real key never leaves the Keystore) 42 × 32": put that key into
        // the Keystore and the real Keystore HMAC must give the vector's KEK and wrap.
        val alias = kit.pinAlias(slotId)
        kit.importHmacKey(alias, ByteArray(32) { 0x42 })
        val kek = kek(alias, PinWrap.message(vaultId, slotId, pin582913))
        assertEquals("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74", Bytes.hex(kek))
        assertEquals(
            "70766c6f636b2d76313a70696e2d7772617000" + "00000000000040008000000000000001" + "00000000000040008000000000000002" +
                "00000000000040008000000000000003",
            Bytes.hex(PinWrap.aad(vaultId, vkId, slotId)),
        )
        val wrap = PinWrap.seal(aead, kek, nonce, vk, vaultId, vkId, slotId)
        assertEquals(
            "e406013346c5ad2cda2a4ca5c7eef739ee9cc17382092ecae223efc3a79c819cb6aef30c458b67b05303f36055881d02",
            Bytes.hex(wrap.ct),
        )
        assertArrayEquals(vk, PinWrap.open(aead, kek(alias, PinWrap.message(vaultId, slotId, pin582913)), wrap, vaultId, slotId))
        assertNull(PinWrap.open(aead, kek(alias, PinWrap.message(vaultId, slotId, pin582914)), wrap, vaultId, slotId))
    }

    @Test
    fun theKeystoresHmacIsHmacSha256() {
        val alias = kit.pinAlias()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        kit.importHmacKey(alias, key)
        val random = SecureRandom()
        for (size in listOf(0, 1, 31, 55, 56, 63, 64, 65, 127, 128, 1_000, 70_000)) {
            val message = ByteArray(size).also { random.nextBytes(it) }
            assertArrayEquals("a message of $size bytes", softwareHmac(key, message), kek(alias, message))
        }
    }

    @Test
    fun theKeyIsGoneAfterItIsDeleted() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.pinAlias()
        val message = PinWrap.message(vaultId, slotId, pin582913)
        assertEquals("a key that was never made", Ks.Gone("not found"), pin.hmac(alias, message))
        assertEquals(KeyCreate.Ok, pin.create(alias))
        assertEquals(32, kek(alias, message).size)
        access.delete(alias)
        assertFalse(kit.has(alias))
        assertFalse(alias in access.aliases())
        assertEquals(Ks.Gone("not found"), pin.hmac(alias, message))
        // A new PIN gets a new key, under the id of a new slot.
        val next = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(next))
        assertEquals(32, kek(next, message).size)
    }

    // --- with the counter of lock.md §6.3 ------------------------------------------------------------

    private class Counter(val guard: PinGuard, val store: FakeRecordStore<PinRecord>, val alias: String, val slot: String)

    /** F6 steps 4 to 7 with the real key: a PIN is set for the vector's vault. */
    private fun setPin(): Counter {
        val clock = FakeClock()
        val store = FakeRecordStore<PinRecord>(now = clock::elapsedMs)
        val guard = PinGuard(vaultId, store, clock, pin, access, aead, FakeRandom(3))
        guard.startUp()
        val slot = kit.newId()
        val alias = kit.pinAlias(slot)
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertEquals(KeyCreate.Ok, pin.create(alias))
        val kek = kek(alias, PinWrap.message(vaultId, slot, pin582913))
        val wrap = PinWrap.seal(aead, kek, nonce, vk, vaultId, vkId, slot)
        assertArrayEquals(vk, PinWrap.open(aead, kek(alias, PinWrap.message(vaultId, slot, pin582913)), wrap, vaultId, slot))
        assertTrue(guard.setPin(ShortcutSlot(slot, clock.wallMs(), listOf(wrap)), masterPasswordTyped = true))
        return Counter(guard, store, alias, slot)
    }

    private fun digits(pin: Int): ByteArray = pin.toString().padStart(6, '0').toByteArray(Charsets.US_ASCII)

    @Test
    fun theCounterOpensWithTheRightPinAndTheLimitDeletesTheKey() = runBlocking {
        val counter = setPin()
        val guard = counter.guard
        val visit = Place.visit(1)

        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        val right = guard.check(pin582913, setOf(vkId))
        assertTrue("$right", right is PinOutcome.Right)
        assertArrayEquals("the vault key, through the real Keystore HMAC", vk, (right as PinOutcome.Right).keys[vkId])
        assertTrue(guard.flush())

        // Five wrong PINs in a row: the slot is gone and so is its key (lock.md §6.5, check E1).
        for (wrong in 1..4) {
            assertEquals(PinCharge.CHARGED, guard.charge(visit))
            assertEquals(PinOutcome.Wrong(5 - wrong), guard.check(digits(100_000 + wrong), setOf(vkId)))
            assertTrue("the key stays until the limit", kit.has(counter.alias))
        }
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertEquals(PinOutcome.Limit, guard.check(digits(100_005), setOf(vkId)))
        val record = guard.record!!
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.LIMIT, record.off!!.reason)
        assertFalse("the key was deleted at once", kit.has(counter.alias))
        assertFalse(counter.alias in access.aliases())
        assertEquals(PinCharge.NOT_USABLE, guard.charge(visit))
    }

    @Test
    fun theCounterTakesAKeyThatIsGoneForGoneAndAKeystoreFaultForTemporary() = runBlocking {
        val counter = setPin()
        val guard = counter.guard
        val visit = Place.visit(1)

        // TEMPORARY, as "Device locked" is on API 35 and newer: refunded, nothing removed, nothing stored.
        val before = guard.record
        for (fault in listOf("init-invalid-key", "mac-dofinal", "getkey-nothing-answers", "keystore-load")) {
            val place = Place.visit(10L + fault.length)
            assertEquals(PinCharge.CHARGED, guard.charge(place))
            assertTrue(access.faults.arm(fault))
            assertEquals(fault, PinOutcome.CannotCheck, guard.check(pin582913, setOf(vkId)))
            assertEquals("$fault: the record is as it was, the charge given back", before, guard.record)
            assertTrue("$fault: the key stays", kit.has(counter.alias))
            assertTrue("$fault: not offered again on this visit", guard.cannotCheckOn(place))
            guard.placeEnded(place)
        }

        // The next visit tries again, and the right PIN opens.
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertTrue(guard.check(pin582913, setOf(vkId)) is PinOutcome.Right)
        assertTrue(guard.flush())

        // GONE: the key is not there while the canary answers (lock.md §14.2, F15b).
        kit.delete(counter.alias)
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        assertEquals(PinOutcome.KeyGone, guard.check(pin582913, setOf(vkId)))
        val record = guard.record!!
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.KEY_GONE, record.off!!.reason)
        assertEquals("no try was counted", 0, record.guard.row)
        assertFalse(record.guard.charged)
    }
}
