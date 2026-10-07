package io.github.dilshodbekmx.passwordvault.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.fake.FakeLauncher
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreKit.Companion.sequence
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PromptOutcome
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.9, [device], on API 24, 28 and 37: what `KeystoreModule.create` builds for the app
 * (architecture.md §3.6), looked at through the app's own graph: the three Keystore classes over one
 * access, software keys allowed exactly as the build says, and the relay that stands in for the
 * prompt until the lock manager package attaches its launcher.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreModuleTest {
    private val kit = KeystoreKit()
    private val keys by lazy { kit.graph.keys }
    private val relay get() = keys.launcher as LauncherRelay

    @Before
    fun theDeviceIsUnlocked() = kit.requireUnlockedDevice()

    @After
    fun cleanUp() {
        relay.attach(null)
        kit.cleanUp()
    }

    @Test
    fun theKeysAreTheKeystoreClassesOverOneAccess() {
        assertTrue(keys.device is KeystoreAccess)
        assertTrue(keys.pin is KeystorePinKey)
        assertTrue(keys.fingerprint is KeystoreFingerprintKey)
        assertTrue(keys.launcher is LauncherRelay)
        assertSame("one Keys for the process", keys, kit.graph.keys)

        // One access behind all three: a fault armed on the device's access strikes the PIN key and
        // the fingerprint key (the `ks-fault` command reaches them through `keys.device`).
        val faults = (keys.device as KeystoreAccess).faults
        val pinAlias = kit.pinAlias()
        val fingerprintAlias = kit.fingerprintAlias()
        assertEquals(KeyCreate.Ok, keys.device.ensureCanary())
        assertEquals(KeyCreate.Ok, keys.pin.create(pinAlias))
        assertEquals(KeyCreate.Ok, keys.fingerprint.create(fingerprintAlias))
        try {
            assertTrue(faults.arm("init-invalidated"))
            assertEquals(Ks.Gone("invalidated"), keys.pin.hmac(pinAlias, ByteArray(56)))
            assertTrue(faults.arm("init-invalidated"))
            assertEquals(Ks.Gone("invalidated"), keys.fingerprint.probe(fingerprintAlias, ByteArray(12)))
        } finally {
            faults.disarm()
        }
        assertTrue(keys.device.aliases().containsAll(listOf(pinAlias, fingerprintAlias)))
        keys.device.delete(pinAlias)
        keys.device.delete(fingerprintAlias)
        assertFalse(kit.has(pinAlias) || kit.has(fingerprintAlias))
    }

    @Test
    fun softwareKeysAreAllowedExactlyAsTheBuildSays() {
        // architecture.md §7.2: true in debug, staging and backupCheck, false in release (build check R-2).
        assertTrue("the connected tests run the debug build", BuildConfig.ALLOW_SOFTWARE_KEYSTORE)
        kit.deleteCanary()
        // On an emulator every key is in software; the debug build keeps them.
        assertEquals(KeyCreate.Ok, keys.device.ensureCanary())
        assertTrue(kit.hasCanary)
        val alias = kit.pinAlias()
        assertEquals(KeyCreate.Ok, keys.pin.create(alias))
        assertTrue(kit.has(alias))
        assertEquals(32, (keys.pin.hmac(alias, ByteArray(56)) as Ks.Ok).value.size)
    }

    @Test
    fun untilALauncherIsAttachedAPromptIsNotShown() = runBlocking {
        assertFalse("no prompt exists on this package's own branch", relay.attached)
        val alias = kit.fingerprintAlias()
        kit.makeAesKeyWithoutAuthentication(alias)
        assertEquals(FingerprintResult.NotShown, keys.fingerprint.seal(alias, sequence(0xa0, 32), PromptUse.TURN_ON, 1))
        assertEquals(FingerprintResult.NotShown, keys.fingerprint.open(alias, ByteArray(12), ByteArray(48), PromptUse.UNLOCK, 2))
        keys.fingerprint.cancel() // nothing to cancel
        assertEquals(PromptOutcome.NotShown, keys.launcher.authenticate(Cipher.getInstance("AES/GCM/NoPadding"), PromptUse.UNLOCK, 3, true))
    }

    @Test
    fun anAttachedLauncherGetsEveryCallOfTheFingerprintKey() = runBlocking {
        val launcher = FakeLauncher()
        relay.attach(launcher)
        assertTrue(relay.attached)
        val alias = kit.fingerprintAlias()
        kit.makeAesKeyWithoutAuthentication(alias)
        val vk = sequence(0xa0, 32)

        launcher.succeed()
        val sealed = keys.fingerprint.seal(alias, vk, PromptUse.TURN_ON, 11)
        assertTrue("$sealed", sealed is FingerprintResult.Ok)
        sealed as FingerprintResult.Ok
        launcher.succeed()
        val opened = keys.fingerprint.open(alias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, 12)
        assertArrayEquals(vk, (opened as FingerprintResult.Ok).bytes)
        launcher.error(13)
        assertEquals(FingerprintResult.Closed(13), keys.fingerprint.open(alias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, 13))
        keys.fingerprint.cancel()
        assertEquals(1, launcher.cancels)

        // The negative button's choice is read from the lock's state when the prompt is shown: "Use
        // PIN" exactly while the pad is shown (lock.md §9.2 rule 5).
        val padIsShown = KeystoreModule.padIsShown(kit.graph.lock.state.value)
        assertEquals(
            listOf(
                FakeLauncher.Request(PromptUse.TURN_ON, 11, padIsShown),
                FakeLauncher.Request(PromptUse.UNLOCK, 12, padIsShown),
                FakeLauncher.Request(PromptUse.UNLOCK, 13, padIsShown),
            ),
            launcher.requests,
        )

        relay.attach(null)
        assertFalse(relay.attached)
        assertEquals(FingerprintResult.NotShown, keys.fingerprint.open(alias, sealed.nonce, sealed.bytes, PromptUse.UNLOCK, 14))
        assertEquals("a detached launcher is not called", 3, launcher.requests.size)
        try {
            relay.attach(relay)
            fail("the relay cannot be its own launcher")
        } catch (e: IllegalArgumentException) {
            assertFalse(relay.attached)
        }
    }

    @Test
    fun useThePinIsOfferedExactlyWhileThePadIsShown() {
        val pad = LockScreen.Pad(digits = 0, status = null, fingerprintKey = true, opening = false, aside = null)
        assertTrue(KeystoreModule.padIsShown(LockUiState.Locked(pad)))
        assertTrue(KeystoreModule.padIsShown(LockUiState.Locked(pad.copy(digits = 3, fingerprintKey = false))))
        val fingerprintOnly = LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null)
        assertFalse("no PIN is usable: \"Use master password\"", KeystoreModule.padIsShown(LockUiState.Locked(fingerprintOnly)))
        val masterPassword = LockScreen.MasterPassword(
            reason = null, canUsePin = true, practice = null, waitUntilElapsedMs = null, error = null,
            pendingReadyInMs = null, opening = false, aside = null,
        )
        assertFalse(KeystoreModule.padIsShown(LockUiState.Locked(masterPassword)))
        assertFalse(KeystoreModule.padIsShown(LockUiState.Starting))
        assertFalse(KeystoreModule.padIsShown(LockUiState.NoVault(emptyList())))
        assertFalse(KeystoreModule.padIsShown(LockUiState.Unlocked(emptyList(), null)))
        assertNull((keys.device as KeystoreAccess).faults.pending)
    }
}
