package io.github.dilshodbekmx.passwordvault.keystore

import android.os.Build
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.fake.FakeLauncher
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import javax.crypto.Mac
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37 (debug build, software keys allowed):
 * [KeystoreAccess] over the real Android Keystore. `aliases()` lists both prefixes and nothing else;
 * a missing canary is made again; `getKey` null with the canary present is GONE, with the canary
 * missing and not makeable TEMPORARY, with the canary made again GONE (lock.md §14.2); a key in
 * software is refused where the build does not allow it (§4.5).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAccessTest {
    private val kit = KeystoreKit()
    private val access = KeystoreAccess(allowSoftwareKeys = true)
    private val pin = KeystorePinKey(access)
    private val fingerprint = fingerprintKey(access)
    private val canary = KeystoreErrors.CANARY_ALIAS

    private fun fingerprintKey(over: KeystoreAccess) =
        KeystoreFingerprintKey(over, FakeLauncher(), kit.context, AppDispatchers.Default) { false }

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() = kit.cleanUp()

    private fun canaryHmac(): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(kit.key(canary)) }.doFinal("the same key?".toByteArray())

    // --- the canary ---------------------------------------------------------------------------------

    @Test
    fun aMissingCanaryIsMadeAndOneThatIsThereIsKept() {
        kit.deleteCanary()
        assertFalse(kit.hasCanary)
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertTrue("made", kit.hasCanary)
        val before = canaryHmac()
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertArrayEquals("a canary that is there is not made again", before, canaryHmac())
        kit.deleteCanary()
        assertEquals("made again after it went missing", KeyCreate.Ok, access.ensureCanary())
        assertFalse("another key now", before.contentEquals(canaryHmac()))
    }

    @Test
    fun theCanaryIsTheKeyOfLockMd42() {
        kit.deleteCanary()
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val info = kit.info(canary)
        assertEquals("HmacSHA256", kit.key(canary)!!.algorithm)
        assertEquals(256, info.keySize)
        assertEquals("for signing only", KeyProperties.PURPOSE_SIGN, info.purposes)
        assertFalse("no user authentication", info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        assertNull("no validity period", info.keyValidityForOriginationEnd)
        assertNull(info.keyValidityForConsumptionEnd)
        if (Build.VERSION.SDK_INT >= 37) assertFalse("no unlocked-device requirement", info.isUnlockedDeviceRequired)
        // Used as an HMAC key it gives 32 bytes, and it is never used on a secret.
        assertEquals(32, canaryHmac().size)
    }

    // --- the alias list -----------------------------------------------------------------------------

    @Test
    fun aliasesListsBothPrefixesAndNothingElse() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val pinAlias = kit.pinAlias()
        val secondPin = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, pin.create(secondPin))
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        // What else an app's Keystore can hold: the key the fingerprint library makes on Android 10
        // (lock.md §5.7), and names that only look like ours.
        val others = listOf("androidxBiometric", "pvtest_other", "pv_pin", "pv_fp", "pv_pinx_1", "xpv_pin_1", "PV_PIN_1", "pv_canary_2")
        for (other in others) kit.makeAesKeyWithoutAuthentication(kit.track(other))

        val listed = access.aliases()
        assertTrue("the three shortcut keys: $listed", listed.containsAll(listOf(pinAlias, secondPin, fingerprintAlias)))
        assertTrue("only the two prefixes: $listed", listed.all { it.startsWith("pv_pin_") || it.startsWith("pv_fp_") })
        assertFalse("not the canary", canary in listed)
        for (other in others) assertFalse(other, other in listed)
        assertEquals("no alias twice", listed.size, listed.toSet().size)
        val inTheKeystore = kit.keyStore().aliases().toList()
        assertTrue("the Keystore itself holds them all", inTheKeystore.containsAll(others + canary))
        assertEquals(inTheKeystore.filter { it.startsWith("pv_pin_") || it.startsWith("pv_fp_") }.toSet(), listed.toSet())
        // everyAlias() is for the debug commands: it lists the others too.
        assertEquals(inTheKeystore.toSet(), access.everyAlias().toSet())

        access.delete(pinAlias)
        assertEquals(listed.toSet() - pinAlias, access.aliases().toSet())
    }

    @Test
    fun aKeystoreThatCannotBeLoadedListsNothingMakesNothingAndDeletesNothing() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))

        assertTrue(access.faults.arm("keystore-load"))
        assertEquals("a failing Keystore makes the sweep delete nothing", emptyList<String>(), access.aliases())
        assertNull("the fault is spent", access.faults.pending)
        assertTrue(alias in access.aliases())

        access.faults.arm("keystore-load")
        access.delete(alias)
        assertTrue("errors of delete are ignored and the key stays", kit.has(alias))

        access.faults.arm("keystore-load")
        assertEquals(KeyCreate.NotNow, access.ensureCanary())
        assertTrue(kit.hasCanary)

        val second = kit.pinAlias()
        access.faults.arm("keystore-load")
        assertEquals(KeyCreate.NotNow, pin.create(second))
        assertFalse(kit.has(second))

        access.faults.arm("keystore-load")
        val loaded = access.load(alias)
        assertTrue("TEMPORARY: $loaded", loaded is Ks.Temporary)
        assertTrue((loaded as Ks.Temporary).cause is java.security.KeyStoreException)
        assertTrue("and afterwards it loads", access.load(alias) is Ks.Ok)
    }

    // --- delete -------------------------------------------------------------------------------------

    @Test
    fun deleteRemovesAShortcutKeyAndNothingElse() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val pinAlias = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        val other = kit.track("androidxBiometric")
        kit.makeAesKeyWithoutAuthentication(other)

        access.delete(pinAlias)
        assertFalse(kit.has(pinAlias))
        assertTrue("the other shortcut stays", kit.has(fingerprintAlias))
        access.delete(pinAlias) // a key that is not there: nothing happens
        access.delete(fingerprintAlias)
        assertFalse(kit.has(fingerprintAlias))

        val canaryBefore = canaryHmac()
        access.delete(canary)
        assertTrue("the app never deletes the canary", kit.hasCanary)
        assertArrayEquals(canaryBefore, canaryHmac())
        access.delete(other)
        assertTrue("nor a key that is not a shortcut's", kit.has(other))
    }

    // --- lock.md §14.2 ------------------------------------------------------------------------------

    @Test
    fun aKeyThatIsThereLoadsWithoutAskingTheCanary() {
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(alias))
        kit.deleteCanary()
        val loaded = access.load(alias)
        assertTrue("$loaded", loaded is Ks.Ok)
        assertEquals("HmacSHA256", (loaded as Ks.Ok).value.algorithm)
        assertFalse("the canary is asked only about a null", kit.hasCanary)
    }

    @Test
    fun nullWithTheCanaryPresentIsGone() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val before = canaryHmac()
        val pinAlias = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(Ks.Gone("not found"), access.load(pinAlias))
        assertEquals(Ks.Gone(KeystoreErrors.GONE_NOT_FOUND), access.load(fingerprintAlias))
        assertArrayEquals("the canary is the one that was there", before, canaryHmac())

        // A key that was there and was deleted, as after the screen lock was removed [L-bio case 6].
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        assertTrue(access.load(fingerprintAlias) is Ks.Ok)
        kit.delete(fingerprintAlias)
        assertEquals(Ks.Gone("not found"), access.load(fingerprintAlias))
    }

    @Test
    fun nullWithTheCanaryMissingAndNotMakeableIsTemporary() {
        val alias = kit.pinAlias()

        // The Keystore cannot make a key either: it is not answering.
        kit.deleteCanary()
        assertTrue(access.faults.arm("create-other"))
        assertEquals(Ks.Temporary(null), access.load(alias))
        assertFalse("the half-made canary was deleted again", kit.hasCanary)
        assertNull(access.faults.pending)

        // Nothing answers at all: null for the key and for the canary, and no key can be made.
        assertEquals(KeyCreate.Ok, pin.create(alias))
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val canaryBefore = canaryHmac()
        assertTrue(access.faults.arm("getkey-nothing-answers"))
        assertEquals(Ks.Temporary(null), access.load(alias))
        assertTrue("nothing was removed", kit.has(alias))
        assertArrayEquals("and nothing was made", canaryBefore, canaryHmac())
        assertTrue("the next time it loads", access.load(alias) is Ks.Ok)

        // A build that refuses software keys, on a phone that has no others: the canary it makes is
        // deleted again, so it "cannot make a key".
        if (kit.keysAreInSoftware()) {
            val missing = kit.pinAlias()
            kit.deleteCanary()
            assertEquals(Ks.Temporary(null), KeystoreAccess(allowSoftwareKeys = false).load(missing))
            assertFalse(kit.hasCanary)
        }
    }

    @Test
    fun nullWithTheCanaryMadeAgainIsGone() {
        val pinAlias = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        kit.deleteCanary()
        assertEquals(Ks.Gone("not found"), access.load(pinAlias))
        assertTrue("the canary was made again", kit.hasCanary)
        val made = canaryHmac()
        assertEquals(Ks.Gone("not found"), access.load(fingerprintAlias))
        assertArrayEquals("once", made, canaryHmac())
    }

    // --- lock.md §4.5 -------------------------------------------------------------------------------

    @Test
    fun theDebugBuildAllowsSoftwareKeysAndAStrictBuildRefusesThem() {
        assertTrue("debug builds allow software keys (architecture.md §7.2)", BuildConfig.ALLOW_SOFTWARE_KEYSTORE)
        val software = kit.keysAreInSoftware()
        // On the test emulators this test must take the "refused" branch below.
        if (kit.isEmulator) assertTrue("emulators report software keys on API 24, 28 and 37", software)
        val strict = KeystoreAccess(allowSoftwareKeys = false)
        val refusedOrOk = if (software) KeyCreate.NoSecureHardware else KeyCreate.Ok

        kit.deleteCanary()
        assertEquals(refusedOrOk, strict.ensureCanary())
        assertEquals("a refused canary is deleted", !software, kit.hasCanary)

        // A canary that was there, made by a build that allows software keys, is judged too, and
        // kept: the app never deletes the canary (lock.md §4.2, §5.7). Only a key that was made
        // just now and refused is deleted again (§4.5).
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertTrue(kit.hasCanary)
        val canaryBefore = canaryHmac()
        assertEquals(refusedOrOk, strict.ensureCanary())
        assertTrue("a canary that was there is kept", kit.hasCanary)
        assertArrayEquals("and is the same key", canaryBefore, canaryHmac())
        assertEquals("judged the same way every time", refusedOrOk, strict.ensureCanary())
        assertTrue(kit.hasCanary)

        val pinAlias = kit.pinAlias()
        assertEquals(refusedOrOk, KeystorePinKey(strict).create(pinAlias))
        assertEquals("a refused key is deleted", !software, kit.has(pinAlias))
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(refusedOrOk, fingerprintKey(strict).create(fingerprintAlias))
        assertEquals(!software, kit.has(fingerprintAlias))

        // The same phone, the build that allows them.
        kit.delete(pinAlias)
        kit.delete(fingerprintAlias)
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        assertTrue(kit.has(pinAlias) && kit.has(fingerprintAlias) && kit.hasCanary)
    }

    @Test
    fun aKeyThatExistsIsNeverMadeAgain() {
        val pinAlias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        val message = ByteArray(56) { it.toByte() }
        val before = (pin.hmac(pinAlias, message) as Ks.Ok).value
        assertEquals("an alias that has a key belongs to a slot", KeyCreate.NotNow, pin.create(pinAlias))
        assertArrayEquals("the key is the one that was there", before, (pin.hmac(pinAlias, message) as Ks.Ok).value)

        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        assertEquals(KeyCreate.NotNow, fingerprint.create(fingerprintAlias))
        assertTrue("and it was not deleted as a half-made key", kit.has(fingerprintAlias))
    }

    // --- drop-keys ----------------------------------------------------------------------------------

    @Test
    fun afterDropAllKeysEveryShortcutIsGoneNotTemporary() {
        assertEquals(KeyCreate.Ok, access.ensureCanary())
        val pinAlias = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, fingerprint.create(fingerprintAlias))
        kit.makeAesKeyWithoutAuthentication(kit.track("androidxBiometric"))

        access.dropAllKeys()
        assertEquals("every alias of the app, the canary included", emptyList<String>(), kit.keyStore().aliases().toList())
        assertEquals(emptyList<String>(), access.everyAlias())
        assertEquals(emptyList<String>(), access.aliases())

        // lock.md §14.2, F15b: a phone that lost every key of the app finds each alias GONE, with
        // the "stopped working" text, instead of "restart your phone" at every unlock.
        assertEquals(Ks.Gone("not found"), pin.hmac(pinAlias, ByteArray(56)))
        assertTrue(kit.hasCanary)
        assertEquals(Ks.Gone("not found"), fingerprint.probe(fingerprintAlias, ByteArray(12)))
    }
}
