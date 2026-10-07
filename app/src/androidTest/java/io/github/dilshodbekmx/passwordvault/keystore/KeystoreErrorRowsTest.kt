package io.github.dilshodbekmx.passwordvault.keystore

import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.fake.FakeLauncher
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreFaults.Row
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.sequence
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37: an injected fault for each row of lock.md §14.3
 * that can be produced gives the listed class, through the real Keystore classes and real keys.
 * [KeystoreFaults] throws, or answers, at the place where the platform would; the exception then
 * takes the way of a real one, through `KeystoreFailureAdapter` and the table of `KeystoreErrors`.
 * `ks-fault <row>` (lock.md §15.3, check E10) arms the same faults by the same names.
 *
 * The rows of §14.3 that are not here are not the Keystore package's: opening the PIN wraps
 * (`PinGuard`), a callback with no launch outstanding and a finger that is not recognised (the
 * launcher, build plan 4.1), `canAuthenticate` at the start of a visit (the lock engine), the
 * records and Argon2id.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreErrorRowsTest {
    private val kit = KeystoreKit()
    private val access = KeystoreAccess(allowSoftwareKeys = true)
    private val faults = access.faults
    private val pin = KeystorePinKey(access)
    private val launcher = FakeLauncher()
    private val fingerprint = KeystoreFingerprintKey(access, launcher, kit.context, AppDispatchers.Default) { true }
    private val message = ByteArray(56) { (it + 1).toByte() }
    private val vk = sequence(0xa0, 32)
    private val nonce = ByteArray(12) { 5 }

    /** What lock.md lists for a row when a key is used. */
    private sealed interface Listed {
        data class Gone(val why: String) : Listed

        /** TEMPORARY, with the exception that was thrown as its cause (null where nothing was thrown). */
        data class Temporary(val cause: KClass<out Throwable>?) : Listed

        /** Not a failure: the key is used as if nothing had happened. */
        data object KeyIsUsed : Listed
    }

    /** The rows of "`load(null)`", "`getKey`" (§14.2) and "`Mac.init`, `Cipher.init`". */
    private val whenAKeyIsUsed: Map<Row, Listed> = mapOf(
        Row.KEYSTORE_LOAD to Listed.Temporary(KeyStoreException::class),
        Row.GET_KEY_NULL to Listed.Gone("not found"),
        Row.GET_KEY_NULL_ONCE to Listed.KeyIsUsed,
        Row.GET_KEY_NOTHING_ANSWERS to Listed.Temporary(null),
        Row.GET_KEY_UNRECOVERABLE to Listed.Gone("invalidated"),
        Row.GET_KEY_UNRECOVERABLE_CAUSE to Listed.Temporary(UnrecoverableKeyException::class),
        Row.GET_KEY_OTHER to Listed.Temporary(ProviderException::class),
        Row.INIT_INVALIDATED to Listed.Gone("invalidated"),
        Row.INIT_NOT_AUTHENTICATED to Listed.Temporary(UserNotAuthenticatedException::class),
        Row.INIT_INVALID_KEY to Listed.Temporary(InvalidKeyException::class),
        Row.INIT_OTHER to Listed.Temporary(ProviderException::class),
    )
    private val pinOnly = setOf(Row.MAC_DO_FINAL)
    private val afterThePrompt = setOf(Row.PROMPT_NO_CIPHER, Row.FINGERPRINT_BAD_TAG, Row.FINGERPRINT_WRONG_SIZE, Row.FINGERPRINT_OTHER)
    private val whenAKeyIsMade = setOf(Row.CREATE_NONE_ENROLLED, Row.CREATE_OTHER, Row.CREATE_SOFTWARE)
    private val whenAKeyIsDeleted = setOf(Row.DELETE)

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() {
        faults.disarm()
        kit.cleanUp()
    }

    private fun arm(row: Row) {
        assertTrue(row.id, faults.arm(row.id))
        assertEquals(row.id, faults.pending)
    }

    private fun <T> assertListed(row: Row, listed: Listed, answer: Ks<T>) {
        when (listed) {
            is Listed.Gone -> assertEquals(row.id, Ks.Gone(listed.why), answer)
            is Listed.Temporary -> {
                assertTrue("${row.id}: TEMPORARY, but $answer", answer is Ks.Temporary)
                val cause = (answer as Ks.Temporary).cause
                if (listed.cause == null) {
                    assertNull(row.id, cause)
                } else {
                    assertNotNull(row.id, cause)
                    assertEquals("${row.id}: the exception that was thrown", listed.cause.java, cause!!.javaClass)
                }
            }
            Listed.KeyIsUsed -> assertTrue("${row.id}: the key is used, but $answer", answer is Ks.Ok)
        }
        assertNull("${row.id}: the fault is spent", faults.pending)
    }

    @Test
    fun everyRowThatCanBeInjectedHasItsTestHere() {
        val tested = whenAKeyIsUsed.keys + pinOnly + afterThePrompt + whenAKeyIsMade + whenAKeyIsDeleted
        assertEquals(Row.entries.toSet(), tested)
        assertEquals(Row.entries.map { it.id }, KeystoreFaults.rows)
        assertEquals("every row has a name of its own", Row.entries.size, KeystoreFaults.rows.toSet().size)
    }

    // --- the PIN key ---------------------------------------------------------------------------------

    @Test
    fun theRowsOfThePinKey() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))
        val kek = (pin.hmac(alias, message) as Ks.Ok).value

        for ((row, listed) in whenAKeyIsUsed) {
            arm(row)
            val answer = pin.hmac(alias, message)
            assertListed(row, listed, answer)
            if (answer is Ks.Ok) assertArrayEquals(row.id, kek, answer.value)
            // The adapter says what happened and removes nothing: acting on GONE is the lock's part.
            assertTrue("${row.id}: the key stays", kit.has(alias))
            assertArrayEquals("${row.id}: and works the next time", kek, (pin.hmac(alias, message) as Ks.Ok).value)
        }

        // Mac.doFinal, any exception: TEMPORARY, no output and no information, so a refund.
        arm(Row.MAC_DO_FINAL)
        assertListed(Row.MAC_DO_FINAL, Listed.Temporary(IllegalStateException::class), pin.hmac(alias, message))
        assertArrayEquals(kek, (pin.hmac(alias, message) as Ks.Ok).value)
    }

    @Test
    fun temporaryFaultsRemoveNothingHoweverOftenTheyCome() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))
        val kek = (pin.hmac(alias, message) as Ks.Ok).value
        val aliasesBefore = access.everyAlias().toSet()
        val temporary = whenAKeyIsUsed.filterValues { it is Listed.Temporary }.keys + Row.MAC_DO_FINAL
        repeat(5) {
            for (row in temporary) {
                arm(row)
                assertTrue(row.id, pin.hmac(alias, message) is Ks.Temporary)
            }
        }
        assertEquals("no key was removed and none was made", aliasesBefore, access.everyAlias().toSet())
        assertArrayEquals("the key is the one that was there", kek, (pin.hmac(alias, message) as Ks.Ok).value)
    }

    // --- the fingerprint key --------------------------------------------------------------------------

    @Test
    fun theRowsOfTheFingerprintProbe() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, fingerprint.create(alias))
        for ((row, listed) in whenAKeyIsUsed) {
            arm(row)
            assertListed(row, listed, fingerprint.probe(alias, nonce))
            assertTrue("${row.id}: the key stays", kit.has(alias))
            assertEquals("${row.id}: and is OK the next time", Ks.Ok(Unit), fingerprint.probe(alias, nonce))
        }
        assertEquals("a probe shows no prompt", 0, launcher.requests.size)
    }

    @Test
    fun theRowsOfSealAndOpenBeforeThePrompt() = runBlocking {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.fingerprintAlias()
        kit.makeAesKeyWithoutAuthentication(alias)
        launcher.succeed()
        val wrap = fingerprint.seal(alias, vk, PromptUse.TURN_ON, 1) as FingerprintResult.Ok
        val shownBefore = launcher.requests.size

        for ((row, listed) in whenAKeyIsUsed) {
            val expected = when (listed) {
                is Listed.Gone -> FingerprintResult.Gone(listed.why)
                is Listed.Temporary -> FingerprintResult.Temporary(null)
                Listed.KeyIsUsed -> null
            }
            for (opening in listOf(true, false)) {
                arm(row)
                if (expected == null) launcher.succeed()
                val answer = if (opening) {
                    fingerprint.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 2)
                } else {
                    fingerprint.seal(alias, vk, PromptUse.TURN_ON, 3)
                }
                if (expected == null) {
                    assertTrue("${row.id}: the key is used, but $answer", answer is FingerprintResult.Ok)
                } else {
                    assertEquals(row.id, expected, answer)
                }
                assertNull("${row.id}: the fault is spent", faults.pending)
                assertTrue("${row.id}: the key stays", kit.has(alias))
            }
        }
        // Only the row that is no failure reached a prompt: a key that cannot be used is found first.
        assertEquals(shownBefore + 2, launcher.requests.size)
    }

    @Test
    fun theRowsOfThePromptAndOfTheFingerprintDoFinal() = runBlocking {
        val alias = kit.fingerprintAlias()
        kit.makeAesKeyWithoutAuthentication(alias)
        launcher.succeed()
        val wrap = fingerprint.seal(alias, vk, PromptUse.TURN_ON, 1) as FingerprintResult.Ok

        suspend fun open(): FingerprintResult {
            launcher.succeed()
            return fingerprint.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 2)
        }
        suspend fun seal(): FingerprintResult {
            launcher.succeed()
            return fingerprint.seal(alias, vk, PromptUse.TURN_ON, 3)
        }

        // onAuthenticationSucceeded without a cipher: TEMPORARY, never "unlocked".
        arm(Row.PROMPT_NO_CIPHER)
        assertEquals(FingerprintResult.Temporary(null), open())
        arm(Row.PROMPT_NO_CIPHER)
        assertEquals(FingerprintResult.Temporary(null), seal())
        // AEADBadTagException: GONE (bad_wrap).
        arm(Row.FINGERPRINT_BAD_TAG)
        assertEquals(FingerprintResult.Gone("bad_wrap"), open())
        // A result that is not 32 bytes: GONE (bad_wrap).
        arm(Row.FINGERPRINT_WRONG_SIZE)
        assertEquals(FingerprintResult.Gone("bad_wrap"), open())
        // Any other exception, such as IllegalBlockSizeException for "Key user not authenticated": TEMPORARY.
        arm(Row.FINGERPRINT_OTHER)
        assertEquals(FingerprintResult.Temporary(null), open())
        arm(Row.FINGERPRINT_OTHER)
        assertEquals(FingerprintResult.Temporary(null), seal())
        assertNull(faults.pending)

        // 32 bytes: the vault key, to open the vault with.
        val opened = open()
        assertTrue("$opened", opened is FingerprintResult.Ok)
        assertArrayEquals("after every fault the wrap still opens", vk, (opened as FingerprintResult.Ok).bytes)
        assertTrue(kit.has(alias))
    }

    @Test
    fun theRowsOfOnAuthenticationError() = runBlocking {
        val alias = kit.fingerprintAlias()
        kit.makeAesKeyWithoutAuthentication(alias)
        launcher.succeed()
        val wrap = fingerprint.seal(alias, vk, PromptUse.TURN_ON, 1) as FingerprintResult.Ok
        val shownBefore = launcher.requests.size

        // prompt-error-<code>: the prompt is not shown and ends with that code.
        for (code in listOf(10, 13, 5)) {
            assertTrue(faults.arm("prompt-error-$code"))
            assertEquals("prompt-error-$code", faults.pending)
            assertEquals("CLOSED", FingerprintResult.Closed(code), fingerprint.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 2))
            assertNull(faults.pending)
        }
        for (code in listOf(7, 9, 1, 11, 12, 2, 3, 4, 8, 14, 15, 1234)) {
            assertTrue(faults.arm("prompt-error-$code"))
            assertEquals("TEMPORARY", FingerprintResult.Temporary(code), fingerprint.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 2))
            assertTrue(faults.arm("prompt-error-$code"))
            assertEquals(FingerprintResult.Temporary(code), fingerprint.seal(alias, vk, PromptUse.TURN_ON, 3))
        }
        assertEquals("no prompt was asked for", shownBefore, launcher.requests.size)
        launcher.succeed()
        assertArrayEquals(vk, (fingerprint.open(alias, wrap.nonce, wrap.bytes, PromptUse.UNLOCK, 2) as FingerprintResult.Ok).bytes)
    }

    // --- making and deleting a key ---------------------------------------------------------------------

    @Test
    fun theRowsOfMakingAKey() {
        // InvalidAlgorithmParameterException: no biometric is enrolled. "Add a fingerprint in your
        // phone's settings first." For a key that needs no finger it is any other failure.
        val fingerprintAlias = kit.fingerprintAlias()
        arm(Row.CREATE_NONE_ENROLLED)
        assertEquals(KeyCreate.NoneEnrolled, fingerprint.create(fingerprintAlias))
        assertFalse(kit.has(fingerprintAlias))
        val pinAlias = kit.pinAlias()
        arm(Row.CREATE_NONE_ENROLLED)
        assertEquals(KeyCreate.NotNow, pin.create(pinAlias))
        assertFalse(kit.has(pinAlias))

        // Any other exception, the 50,000-key limit of API 37 among them: NOT NOW, and the half-made
        // alias is deleted. The fault strikes after the key was generated, so there is one to delete.
        arm(Row.CREATE_OTHER)
        assertEquals(KeyCreate.NotNow, pin.create(pinAlias))
        assertFalse("the half-made alias was deleted", kit.has(pinAlias))
        arm(Row.CREATE_OTHER)
        assertEquals(KeyCreate.NotNow, fingerprint.create(fingerprintAlias))
        assertFalse(kit.has(fingerprintAlias))
        kit.deleteCanary()
        arm(Row.CREATE_OTHER)
        assertEquals(KeyCreate.NotNow, access.ensureCanary())
        assertFalse(kit.hasCanary)

        // KeyInfo says the key is in software, in a build that refuses that: NOT NOW for good, and
        // the key is deleted (F18).
        arm(Row.CREATE_SOFTWARE)
        assertEquals(KeyCreate.NoSecureHardware, access.ensureCanary())
        assertFalse(kit.hasCanary)
        arm(Row.CREATE_SOFTWARE)
        assertEquals(KeyCreate.NoSecureHardware, pin.create(pinAlias))
        assertFalse(kit.has(pinAlias))
        arm(Row.CREATE_SOFTWARE)
        assertEquals(KeyCreate.NoSecureHardware, fingerprint.create(fingerprintAlias))
        assertFalse(kit.has(fingerprintAlias))
        assertNull(faults.pending)
        assertFalse(pinAlias in access.aliases() || fingerprintAlias in access.aliases())

        // Nothing else changed: the same aliases can be made now.
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
    }

    @Test
    fun theRowOfDeleteEntry() {
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))
        arm(Row.DELETE)
        access.delete(alias) // any exception: ignored
        assertTrue("the key stays for the sweep", kit.has(alias))
        assertTrue(alias in access.aliases())
        assertNull(faults.pending)
        access.delete(alias) // the sweep retries
        assertFalse(kit.has(alias))
    }

    // --- the faults themselves -------------------------------------------------------------------------

    @Test
    fun aFaultStrikesOnceAtItsOwnPlaceAndUnknownNamesAreRefused() {
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))
        val kek = (pin.hmac(alias, message) as Ks.Ok).value

        // A fault of another place waits: a fingerprint fault does not touch the PIN.
        arm(Row.FINGERPRINT_BAD_TAG)
        assertArrayEquals(kek, (pin.hmac(alias, message) as Ks.Ok).value)
        assertEquals("still armed", Row.FINGERPRINT_BAD_TAG.id, faults.pending)
        assertEquals(listOf(alias), access.aliases().filter { it == alias })
        assertEquals(Row.FINGERPRINT_BAD_TAG.id, faults.pending)

        // Arming replaces what was armed; disarm forgets it.
        arm(Row.INIT_INVALIDATED)
        faults.disarm()
        assertNull(faults.pending)
        assertArrayEquals(kek, (pin.hmac(alias, message) as Ks.Ok).value)

        // Once: the second call after one arming is not struck.
        arm(Row.INIT_INVALIDATED)
        assertEquals(Ks.Gone("invalidated"), pin.hmac(alias, message))
        assertArrayEquals(kek, (pin.hmac(alias, message) as Ks.Ok).value)

        for (name in listOf("", "nonsense", "INIT_INVALIDATED", "init-invalidated ", "prompt-error-", "prompt-error-x", "prompt-error--1", "prompt-error-12345", "prompt-error-1 ")) {
            assertFalse("'$name'", faults.arm(name))
            assertNull(faults.pending)
        }
        assertTrue(faults.arm("prompt-error-0"))
        faults.disarm()
    }
}
