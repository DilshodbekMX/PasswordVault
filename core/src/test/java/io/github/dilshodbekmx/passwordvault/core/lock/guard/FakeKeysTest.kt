package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The software Keystore of :testing (lock.md §15.1): PinKey, FingerprintKey and DeviceKeys with scripts. */
class FakeKeysTest {
    private var now = 0L
    private val keys = FakeKeys(FakeRandom(1), now = { now })
    private val pinAlias = "pv_pin_00000000-0000-4000-8000-000000000003"
    private val fpAlias = "pv_fp_00000000-0000-4000-8000-000000000005"
    private val vk = FakeRandom.sequence(0xa0, 32)

    @Test
    fun aPinKeyIsAnHmacSha256Key() {
        keys.putPinKey(pinAlias, ByteArray(32) { 0x42 })
        val message = PinWrap.message(PinFixture.VAULT, PinFixture.SLOT, PinFixture.RIGHT)
        now = 77
        val kek = keys.pin.hmac(pinAlias, message) as Ks.Ok
        assertEquals("344f054090df8d29988b680e0a4eaaa2256f5195f6f0d9579bb5bd21972f0d74", Bytes.hex(kek.value))
        assertEquals(77L, keys.hmacCalls.single().atMs)
        assertArrayEquals(message, keys.hmacCalls.single().message)
        assertArrayEquals(kek.value, keys.hmacOf(pinAlias, message))
        assertEquals(1, keys.hmacCalls.size) // hmacOf is not a call

        assertEquals(KeyCreate.Ok, keys.pin.create(pinAlias)) // made again: a new key
        assertFalse(Bytes.equal(kek.value, (keys.pin.hmac(pinAlias, message) as Ks.Ok).value))
    }

    @Test
    fun aMissingKeyIsJudgedByTheCanary() {
        keys.canary = true
        assertEquals(Ks.Gone("not found"), keys.pin.hmac(pinAlias, byteArrayOf(1)))
        keys.canary = false
        assertEquals(Ks.Gone("not found"), keys.pin.hmac(pinAlias, byteArrayOf(1))) // made again, then believed
        assertTrue(keys.canary)
        keys.canary = false
        keys.canaryMakeable = false
        assertEquals(Ks.Temporary(null), keys.pin.hmac(pinAlias, byteArrayOf(1)))
        assertEquals(Ks.Temporary(null), keys.fingerprint.probe(fpAlias, ByteArray(12)))
        assertFalse(keys.canary)
    }

    @Test
    fun scriptsComeFirstInOrder() {
        keys.putPinKey(pinAlias, ByteArray(32))
        keys.scriptHmac(Ks.Temporary(null), Ks.Gone("invalidated"))
        assertEquals(Ks.Temporary(null), keys.pin.hmac(pinAlias, byteArrayOf(1)))
        assertEquals(Ks.Gone("invalidated"), keys.pin.hmac(pinAlias, byteArrayOf(1)))
        assertTrue(keys.pin.hmac(pinAlias, byteArrayOf(1)) is Ks.Ok)
        keys.scriptCreate(KeyCreate.NotNow)
        assertEquals(KeyCreate.NotNow, keys.pin.create("pv_pin_00000000-0000-4000-8000-000000000009"))
        assertFalse("pv_pin_00000000-0000-4000-8000-000000000009" in keys.aliases)
    }

    @Test
    fun aKeystoreThatDoesNotAnswerChangesNothing() {
        keys.putPinKey(pinAlias, ByteArray(32))
        keys.answering = false
        assertEquals(Ks.Temporary(null), keys.pin.hmac(pinAlias, byteArrayOf(1)))
        assertEquals(KeyCreate.NotNow, keys.pin.create("pv_pin_00000000-0000-4000-8000-000000000008"))
        assertEquals(KeyCreate.NotNow, keys.device.ensureCanary())
        assertEquals(emptyList<String>(), keys.device.aliases()) // so the sweep deletes nothing
        keys.device.delete(pinAlias)
        assertTrue(pinAlias in keys.aliases)
        assertEquals(emptyList<String>(), keys.deleted)
        keys.answering = true
        assertEquals(listOf(pinAlias), keys.device.aliases())
    }

    @Test
    fun theCanaryAndSoftwareKeys() {
        assertEquals(KeyCreate.Ok, keys.device.ensureCanary())
        assertTrue(keys.canary)
        assertEquals(emptyList<String>(), keys.device.aliases()) // pv_canary is not listed
        val software = FakeKeys(FakeRandom(2)).also { it.softwareOnly = true }
        assertEquals(KeyCreate.NoSecureHardware, software.device.ensureCanary())
        assertEquals(KeyCreate.NoSecureHardware, software.pin.create(pinAlias))
        assertEquals(KeyCreate.NoSecureHardware, software.fingerprint.create(fpAlias))
        assertEquals(emptyList<String>(), software.aliases)
        val noCanary = FakeKeys(FakeRandom(3)).also { it.canaryMakeable = false }
        assertEquals(KeyCreate.NotNow, noCanary.device.ensureCanary())
    }

    @Test
    fun deletingAndDroppingKeys() {
        assertEquals(KeyCreate.Ok, keys.pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, keys.fingerprint.create(fpAlias))
        keys.canary = true
        assertEquals(listOf(pinAlias, fpAlias), keys.device.aliases())
        keys.device.delete(pinAlias)
        assertEquals(listOf(fpAlias), keys.aliases)
        assertEquals(listOf(pinAlias), keys.deleted)
        assertNull(keys.pinKeyOf(pinAlias))
        keys.dropAllKeys()
        assertEquals(emptyList<String>(), keys.aliases)
        assertFalse(keys.canary)
        try {
            keys.pin.create(fpAlias)
            throw AssertionError("a fingerprint alias made a PIN key")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aFingerprintKeySealsAndOpensBehindAPrompt() = runBlocking {
        assertEquals(KeyCreate.Ok, keys.fingerprint.create(fpAlias))
        val sealed = keys.fingerprint.seal(fpAlias, vk, PromptUse.TURN_ON, launch = 1) as FingerprintResult.Ok
        assertEquals(48, sealed.bytes.size)
        assertEquals(12, sealed.nonce.size)
        assertEquals(Ks.Ok(Unit), keys.fingerprint.probe(fpAlias, sealed.nonce))
        val opened = keys.fingerprint.open(fpAlias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, launch = 2) as FingerprintResult.Ok
        assertArrayEquals(vk, opened.bytes)
        assertEquals(
            listOf(FakeKeys.PromptCall("seal", fpAlias, PromptUse.TURN_ON, 1), FakeKeys.PromptCall("open", fpAlias, PromptUse.UNLOCK, 2)),
            keys.prompts,
        )

        // a wrap that does not fit the key, or holds anything but 32 bytes, is GONE (bad_wrap), §14.3
        val altered = sealed.bytes.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertEquals(FingerprintResult.Gone("bad_wrap"), keys.fingerprint.open(fpAlias, sealed.nonce, altered, PromptUse.UNLOCK, 3))
        val short = keys.fingerprint.seal(fpAlias, ByteArray(16), PromptUse.TURN_ON, 4) as FingerprintResult.Ok
        assertEquals(FingerprintResult.Gone("bad_wrap"), keys.fingerprint.open(fpAlias, short.nonce, short.bytes, PromptUse.UNLOCK, 5))

        // a new finger invalidates the key
        keys.invalidateFingerprintKeys()
        assertEquals(Ks.Gone("invalidated"), keys.fingerprint.probe(fpAlias, sealed.nonce))
        assertEquals(FingerprintResult.Gone("invalidated"), keys.fingerprint.open(fpAlias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, 6))
    }

    @Test
    fun promptsEndAsScripted() = runBlocking {
        assertEquals(KeyCreate.Ok, keys.fingerprint.create(fpAlias))
        keys.scriptPrompt(FakeKeys.Prompt.Close(13), FakeKeys.Prompt.Error(7), FakeKeys.Prompt.SucceededButFailed, FakeKeys.Prompt.NotShown)
        val iv = ByteArray(12)
        val ct = ByteArray(48)
        assertEquals(FingerprintResult.Closed(13), keys.fingerprint.open(fpAlias, iv, ct, PromptUse.UNLOCK, 1))
        assertEquals(FingerprintResult.Temporary(7), keys.fingerprint.open(fpAlias, iv, ct, PromptUse.UNLOCK, 2))
        assertEquals(FingerprintResult.Temporary(null), keys.fingerprint.open(fpAlias, iv, ct, PromptUse.UNLOCK, 3))
        assertEquals(FingerprintResult.NotShown, keys.fingerprint.seal(fpAlias, vk, PromptUse.TURN_ON, 4))
        keys.scriptProbe(Ks.Temporary(null))
        assertEquals(Ks.Temporary(null), keys.fingerprint.probe(fpAlias, iv))
        keys.enrolled = false
        assertEquals(KeyCreate.NoneEnrolled, keys.fingerprint.create("pv_fp_00000000-0000-4000-8000-000000000006"))
        keys.availability = 11
        assertEquals(11, keys.fingerprint.availability())
        assertEquals(1, keys.availabilityCalls)
    }

    @Test
    fun aHeldPromptWaitsForTheFingerOrACancel() = runBlocking {
        assertEquals(KeyCreate.Ok, keys.fingerprint.create(fpAlias))
        keys.holdPrompts = true
        val sealing = async(start = CoroutineStart.UNDISPATCHED) { keys.fingerprint.seal(fpAlias, vk, PromptUse.KEEP_AFTER_CHANGE, 7) }
        assertEquals(FakeKeys.PromptCall("seal", fpAlias, PromptUse.KEEP_AFTER_CHANGE, 7), keys.openPrompt)
        assertFalse(sealing.isCompleted)
        keys.answer(FakeKeys.Prompt.Touch)
        assertTrue(sealing.await() is FingerprintResult.Ok)
        assertNull(keys.openPrompt)

        val opening = async(start = CoroutineStart.UNDISPATCHED) { keys.fingerprint.open(fpAlias, ByteArray(12), ByteArray(48), PromptUse.UNLOCK, 8) }
        yield()
        keys.fingerprint.cancel() // a lock, or the end of the visit
        assertEquals(FingerprintResult.Closed(5), opening.await())
        assertEquals(1, keys.cancels)
        try {
            keys.answer(FakeKeys.Prompt.Touch)
            throw AssertionError("a prompt that is not open was answered")
        } catch (expected: IllegalStateException) {
        }
    }
}
