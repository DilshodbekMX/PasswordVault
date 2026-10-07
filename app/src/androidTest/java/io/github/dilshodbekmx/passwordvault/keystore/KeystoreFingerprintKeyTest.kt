package io.github.dilshodbekmx.passwordvault.keystore

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.fake.FakeLauncher
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.sequence
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.softwareCipher
import io.github.dilshodbekmx.passwordvault.lock.BiometricLauncher
import io.github.dilshodbekmx.passwordvault.lock.PromptOutcome
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37 (debug build, software keys allowed):
 * [KeystoreFingerprintKey] over the real Android Keystore, with `FakeLauncher` in place of the
 * prompt. The key of lock.md §4.2 is made and deleted; `probe` classifies it by `Cipher.init`
 * before any prompt; what a prompt answered is read by the table of §14.3; and the cipher of the
 * result does the work, never one that was kept.
 *
 * Using the real key needs a finger from the host, which is check E6 in wave 4. Here the real key
 * is taken as far as it goes without one (after a "success" its `doFinal` is refused, which is the
 * row of §9.3), and the rest of `seal` and `open` runs on a key of the test's own that needs no
 * finger.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreFingerprintKeyTest {
    private val kit = KeystoreKit()
    private val access = KeystoreAccess(allowSoftwareKeys = true)
    private val launcher = FakeLauncher()
    private var pinUsable = true
    private val key = KeystoreFingerprintKey(access, launcher, kit.context, AppDispatchers.Default) { pinUsable }
    private val vk = sequence(0xa0, 32)

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() = kit.cleanUp()

    /** An alias with a key like `K_fp` that needs no finger. */
    private fun aliasWithoutAuthentication(): String = kit.fingerprintAlias().also { kit.makeAesKeyWithoutAuthentication(it) }

    private fun wrapped(alias: String): FingerprintResult.Ok = runBlocking {
        launcher.succeed()
        val sealed = key.seal(alias, vk, PromptUse.TURN_ON, 1)
        if (sealed !is FingerprintResult.Ok) throw AssertionError("seal: $sealed")
        sealed
    }

    @Test
    fun availabilityIsWhatTheLibrarySaysAndTheTestDevicesHaveFingers() {
        val asked = BiometricManager.from(kit.context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        assertEquals(asked, key.availability())
        // tools/emu/README.md: fingers 1 and 2 are enrolled on API 24, 28 and 37, and 1.4.0-alpha07
        // sees them on all three (lock.md §9.1).
        assertEquals("BIOMETRIC_SUCCESS on a device with an enrolled finger", BiometricManager.BIOMETRIC_SUCCESS, key.availability())
        assertFalse("no code of the library", KeystoreFingerprintKey.CANNOT_ASK in -2..100)
    }

    @Test
    fun createMakesTheAesKeyOfLockMd42() {
        val alias = kit.fingerprintAlias()
        assertFalse(kit.has(alias))
        assertEquals(KeyCreate.Ok, key.create(alias))
        assertEquals("AES", kit.key(alias)!!.algorithm)
        val info = kit.info(alias)
        assertEquals(256, info.keySize)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, info.purposes)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), info.encryptionPaddings.toList())
        assertTrue("one authentication per use", info.isUserAuthenticationRequired)
        assertTrue("dies when the phone's fingerprints change", info.isInvalidatedByBiometricEnrollment)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals("every use", 0, info.userAuthenticationValidityDurationSeconds)
            assertEquals("Class 3 only", KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        } else {
            assertEquals("the default: authenticate for every use", -1, info.userAuthenticationValidityDurationSeconds)
        }
        if (Build.VERSION.SDK_INT >= 37) assertFalse("never setUnlockedDeviceRequired", info.isUnlockedDeviceRequired)
        assertTrue(alias in access.aliases())

        access.delete(alias)
        assertFalse(kit.has(alias))
        assertFalse(alias in access.aliases())
    }

    @Test
    fun createTakesOnlyAFingerprintAlias() {
        for (alias in listOf("pv_pin_" + kit.newId(), "pv_canary", "androidxBiometric", "")) {
            try {
                key.create(alias)
                fail("an alias that is not a fingerprint's: '$alias'")
            } catch (e: IllegalArgumentException) {
                assertFalse(alias in kit.keyStore().aliases().toList())
            }
        }
    }

    @Test
    fun probeClassifiesTheKeyByCipherInit() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.fingerprintAlias()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        assertEquals("a key that was never made", Ks.Gone("not found"), key.probe(alias, nonce))

        assertEquals(KeyCreate.Ok, key.create(alias))
        // Cipher.init needs no authentication [L-bio case 2]: a live key is OK before any prompt.
        assertEquals(Ks.Ok(Unit), key.probe(alias, nonce))
        assertEquals("as often as it is asked", Ks.Ok(Unit), key.probe(alias, nonce))
        assertEquals("no prompt is shown for a probe", 0, launcher.requests.size)

        // A nonce the Keystore refuses is a doubt, never GONE: only 12 bytes are supported.
        val refused = key.probe(alias, ByteArray(8))
        assertTrue("TEMPORARY: $refused", refused is Ks.Temporary)
        assertTrue("the key stays", kit.has(alias))

        // KeyPermanentlyInvalidatedException at Cipher.init, as after a finger was added: GONE.
        assertTrue(access.faults.arm("init-invalidated"))
        assertEquals(Ks.Gone("invalidated"), key.probe(alias, nonce))
        // Any other InvalidKeyException: TEMPORARY, never GONE [V-C6].
        assertTrue(access.faults.arm("init-invalid-key"))
        val temporary = key.probe(alias, nonce)
        assertTrue("TEMPORARY: $temporary", temporary is Ks.Temporary)
        assertTrue((temporary as Ks.Temporary).cause is java.security.InvalidKeyException)
        assertEquals("the adapter removes nothing; that is the lock's part", Ks.Ok(Unit), key.probe(alias, nonce))

        // The key is deleted, as the system does when the screen lock is removed: null, canary present.
        kit.delete(alias)
        assertEquals(Ks.Gone("not found"), key.probe(alias, nonce))
    }

    @Test
    fun theRealKeyNeedsAFingerSoASuccessWithoutOneIsTemporary() = runBlocking {
        val alias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, key.create(alias))

        // onAuthenticationSucceeded proves nothing by itself (lock.md I7): the fake prompt "succeeds",
        // no finger authorised the operation, and doFinal is refused: "Key user not authenticated".
        launcher.succeed()
        pinUsable = false
        assertEquals(FingerprintResult.Temporary(null), key.seal(alias, vk, PromptUse.TURN_ON, 41))
        launcher.succeed()
        pinUsable = true
        val nonce = ByteArray(12) { 7 }
        assertEquals(FingerprintResult.Temporary(null), key.open(alias, nonce, ByteArray(48), PromptUse.UNLOCK, 42))

        assertEquals(
            "each prompt was asked for with its use, the lock's launch number and the negative button's choice",
            listOf(FakeLauncher.Request(PromptUse.TURN_ON, 41, false), FakeLauncher.Request(PromptUse.UNLOCK, 42, true)),
            launcher.requests,
        )
        assertTrue("a TEMPORARY result removes nothing", kit.has(alias))
        assertEquals(Ks.Ok(Unit), key.probe(alias, nonce))
    }

    @Test
    fun theCipherHandedToThePromptIsInitialisedForItsUse() = runBlocking {
        val alias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, key.create(alias))
        val seen = ArrayList<Cipher>()
        launcher.answers += { cipher -> seen += cipher; PromptOutcome.Error(10) }
        launcher.answers += { cipher -> seen += cipher; PromptOutcome.Error(10) }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        assertEquals(FingerprintResult.Closed(10), key.seal(alias, vk, PromptUse.TURN_ON, 1))
        assertEquals(FingerprintResult.Closed(10), key.open(alias, nonce, ByteArray(48), PromptUse.UNLOCK, 2))

        assertEquals(2, seen.size)
        // Encryption: no IV was given, the Keystore chose 12 bytes (a caller's IV is refused [L-bio case 3]).
        assertEquals(12, seen[0].iv.size)
        assertFalse("a random IV", seen[0].iv.contentEquals(ByteArray(12)))
        // Decryption: the nonce of the wrap.
        assertArrayEquals(nonce, seen[1].iv)
        assertEquals("AES/GCM/NoPadding", seen[0].algorithm)
        assertEquals("AES/GCM/NoPadding", seen[1].algorithm)
    }

    @Test
    fun whatThePromptAnsweredIsReadByTheTable() = runBlocking {
        val alias = aliasWithoutAuthentication()
        val wrap = wrapped(alias)
        var launch = 100

        // 10 user cancelled, 13 negative button, 5 cancelled: CLOSED.
        for (code in listOf(10, 13, 5)) {
            launcher.error(code)
            assertEquals(FingerprintResult.Closed(code), key.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, launch++))
            launcher.error(code)
            assertEquals(FingerprintResult.Closed(code), key.seal(alias, vk, PromptUse.TURN_ON, launch++))
        }
        // 7 and 9 lockout, 1 hardware unavailable, and 11, 12, 2, 3, 4, 8, 14, 15 and any unknown code: TEMPORARY.
        for (code in listOf(7, 9, 1, 11, 12, 2, 3, 4, 8, 14, 15, 0, 6, 16, 99, -1)) {
            launcher.error(code)
            assertEquals(FingerprintResult.Temporary(code), key.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, launch++))
            launcher.error(code)
            assertEquals(FingerprintResult.Temporary(code), key.seal(alias, vk, PromptUse.KEEP_AFTER_CHANGE, launch++))
        }
        // The prompt could not be shown: the activity is not resumed.
        launcher.notShown()
        assertEquals(FingerprintResult.NotShown, key.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, launch++))
        assertEquals("no answer queued is NotShown too", FingerprintResult.NotShown, key.seal(alias, vk, PromptUse.TURN_ON, launch++))
        // onAuthenticationSucceeded without a cipher: TEMPORARY, never "unlocked".
        launcher.answers += { PromptOutcome.Succeeded(null) }
        assertEquals(FingerprintResult.Temporary(null), key.open(alias, wrap.nonce, wrap.bytes, PromptUse.CONFIRM_REPLACEMENT, launch++))
        launcher.answers += { PromptOutcome.Succeeded(null) }
        assertEquals(FingerprintResult.Temporary(null), key.seal(alias, vk, PromptUse.TURN_ON, launch++))

        // Every call asked for one prompt (the first request is the wrap made above).
        assertEquals((100 until launch).toList(), launcher.requests.drop(1).map { it.launch })
        // And after all of it the wrap still opens: nothing was removed or changed.
        launcher.succeed()
        val opened = key.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, launch)
        assertTrue("$opened", opened is FingerprintResult.Ok)
        assertArrayEquals(vk, (opened as FingerprintResult.Ok).bytes)
    }

    @Test
    fun sealAndOpenOnTheRealKeystoreWithAKeyThatNeedsNoFinger() = runBlocking {
        val alias = aliasWithoutAuthentication()

        launcher.succeed()
        val sealed = key.seal(alias, vk, PromptUse.TURN_ON, 1)
        assertTrue("$sealed", sealed is FingerprintResult.Ok)
        sealed as FingerprintResult.Ok
        assertEquals("32 bytes of ciphertext and the 16-byte tag", 48, sealed.bytes.size)
        assertEquals("cipher.iv", 12, sealed.nonce.size)
        assertFalse("not the plain key", sealed.bytes.copyOf(32).contentEquals(vk))

        launcher.succeed()
        val opened = key.open(alias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, 2)
        assertTrue("$opened", opened is FingerprintResult.Ok)
        opened as FingerprintResult.Ok
        assertArrayEquals("the 32 bytes of VK", vk, opened.bytes)
        assertArrayEquals("the nonce it was given", sealed.nonce, opened.nonce)

        // A second wrap of the same key has another IV and another ciphertext.
        launcher.succeed()
        val second = key.seal(alias, vk, PromptUse.KEEP_AFTER_CHANGE, 3) as FingerprintResult.Ok
        assertFalse(second.nonce.contentEquals(sealed.nonce))
        assertFalse(second.bytes.contentEquals(sealed.bytes))
        launcher.succeed()
        assertArrayEquals(vk, (key.open(alias, second.nonce, second.bytes, PromptUse.UNLOCK, 4) as FingerprintResult.Ok).bytes)
    }

    @Test
    fun aWrapThatDoesNotMatchTheKeyIsGoneAsBadWrap() = runBlocking {
        val alias = aliasWithoutAuthentication()
        val wrap = wrapped(alias)
        val badWrap = FingerprintResult.Gone("bad_wrap")

        // AEADBadTagException: an altered wrap [L-bio], another nonce, the wrap of another key.
        for (at in listOf(0, 31, 32, 47)) {
            val altered = wrap.bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            launcher.succeed()
            assertEquals("byte $at altered", badWrap, key.open(alias, wrap.nonce, altered, PromptUse.UNLOCK, 1))
        }
        launcher.succeed()
        val otherNonce = wrap.nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(badWrap, key.open(alias, otherNonce, wrap.bytes, PromptUse.UNLOCK, 1))
        val other = aliasWithoutAuthentication()
        launcher.succeed()
        assertEquals(badWrap, key.open(other, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 1))

        // A result that is not 32 bytes: the wrap of something that is not a vault key.
        for (size in listOf(0, 16, 31, 33, 64)) {
            val (iv, ct) = kit.encryptWith(alias, ByteArray(size) { 0x55 })
            launcher.succeed()
            assertEquals("$size bytes", badWrap, key.open(alias, iv, ct, PromptUse.UNLOCK, 1))
        }

        // The true wrap still opens.
        launcher.succeed()
        assertArrayEquals(vk, (key.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 1) as FingerprintResult.Ok).bytes)
    }

    @Test
    fun sealOfSomethingThatIsNotAVaultKeyGivesNoWrap() = runBlocking {
        val alias = aliasWithoutAuthentication()
        for (size in listOf(0, 16, 31, 33)) {
            launcher.succeed()
            assertEquals("$size bytes would not be a 48-byte wrap", FingerprintResult.Temporary(null), key.seal(alias, ByteArray(size), PromptUse.TURN_ON, 1))
        }
    }

    @Test
    fun theCipherOfTheResultIsUsedNeverTheOneThatWasKept() = runBlocking {
        val alias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, key.create(alias)) // the real key: its own cipher could do nothing without a finger

        // The prompt hands back another cipher than the one it was given, as the library does when a
        // second authenticate() was ignored [L-bio finding e]. Only that one may be used.
        val softwareKey = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(12) { 9 }
        val ct = softwareCipher(Cipher.ENCRYPT_MODE, softwareKey, nonce).doFinal(vk)
        var given: Cipher? = null
        val handedBack = softwareCipher(Cipher.DECRYPT_MODE, softwareKey, nonce)
        launcher.answers += { cipher -> given = cipher; PromptOutcome.Succeeded(handedBack) }
        val opened = key.open(alias, nonce, ct, PromptUse.UNLOCK, 1)
        assertTrue("$opened", opened is FingerprintResult.Ok)
        assertArrayEquals(vk, (opened as FingerprintResult.Ok).bytes)
        assertNotNull(given)
        assertFalse(given === handedBack)

        val forSealing = softwareCipher(Cipher.ENCRYPT_MODE, softwareKey, ByteArray(12) { 4 })
        launcher.answers += { PromptOutcome.Succeeded(forSealing) }
        val sealed = key.seal(alias, vk, PromptUse.TURN_ON, 2)
        assertTrue("$sealed", sealed is FingerprintResult.Ok)
        sealed as FingerprintResult.Ok
        assertArrayEquals("the IV of the cipher that did the work", ByteArray(12) { 4 }, sealed.nonce)
        assertArrayEquals(vk, softwareCipher(Cipher.DECRYPT_MODE, softwareKey, sealed.nonce).doFinal(sealed.bytes))
    }

    @Test
    fun aKeyThatIsGoneIsFoundBeforeAnyPrompt() = runBlocking {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.fingerprintAlias()
        assertEquals(FingerprintResult.Gone("not found"), key.open(alias, ByteArray(12), ByteArray(48), PromptUse.UNLOCK, 1))
        assertEquals(FingerprintResult.Gone("not found"), key.seal(alias, vk, PromptUse.TURN_ON, 2))

        val there = aliasWithoutAuthentication()
        assertTrue(access.faults.arm("init-invalidated"))
        assertEquals(FingerprintResult.Gone("invalidated"), key.open(there, ByteArray(12), ByteArray(48), PromptUse.UNLOCK, 3))
        assertTrue(access.faults.arm("getkey-other"))
        assertEquals("the Keystore is not answering", FingerprintResult.Temporary(null), key.seal(there, vk, PromptUse.TURN_ON, 4))
        assertEquals("the user was not asked for a finger", 0, launcher.requests.size)
    }

    @Test
    fun cancelReachesTheLauncher() {
        key.cancel()
        key.cancel()
        assertEquals(2, launcher.cancels)
    }

    /** A prompt that stays open until the test answers it. */
    private class HeldLauncher : BiometricLauncher {
        val shown = CompletableDeferred<Cipher>()
        val answer = CompletableDeferred<PromptOutcome>()

        override suspend fun authenticate(cipher: Cipher, use: PromptUse, launch: Int, pinUsable: Boolean): PromptOutcome {
            shown.complete(cipher)
            return answer.await()
        }

        override fun cancel() {
            answer.complete(PromptOutcome.Error(5))
        }
    }

    @Test
    fun anOpenPromptIsWaitedForAndCancelClosesItWithCode5() = runBlocking {
        val alias = aliasWithoutAuthentication()
        val wrap = wrapped(alias)
        val held = HeldLauncher()
        val heldKey = KeystoreFingerprintKey(access, held, kit.context, AppDispatchers.Default) { true }
        val call = async(Dispatchers.Default) { heldKey.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 1) }
        withTimeout(10_000) { held.shown.await() }
        assertFalse("no result while the prompt is open", call.isCompleted)
        heldKey.cancel() // a lock, or the end of a visit
        assertEquals(FingerprintResult.Closed(5), withTimeout(10_000) { call.await() })
    }

    @Test
    fun aCallerThatWasCancelledGetsNoKey() = runBlocking {
        val alias = aliasWithoutAuthentication()
        val wrap = wrapped(alias)
        val held = HeldLauncher()
        val heldKey = KeystoreFingerprintKey(access, held, kit.context, AppDispatchers.Default) { true }
        val call = async(Dispatchers.Default) { heldKey.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 1) }
        val cipher = withTimeout(10_000) { held.shown.await() }
        // The visit ends while the prompt is open (lock.md F3 step 4), and the prompt then succeeds.
        call.cancel()
        held.answer.complete(PromptOutcome.Succeeded(cipher))
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        try {
            call.await()
            fail("a cancelled call returns nothing")
        } catch (e: CancellationException) {
            // As it must: nothing opens for a visit that has ended.
        }
    }
}
