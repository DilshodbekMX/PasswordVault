package io.github.dilshodbekmx.passwordvault.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.testing.storage.VaultRoundTrip
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: the body of `VaultRoundTripTest` on the phone's
 * own adapters. [VaultRoundTrip.roundTrip] (the steps that the JVM test runs over `FakeFiles` with
 * Bouncy Castle) runs here over [OsFiles] in a directory of the app's `filesDir`, deriving every key
 * with [NativeArgon2id], with the platform's AES-GCM, [SystemRandom] and [AndroidClock]: create with
 * a password, reopen with the password, edit, reopen with the key, change the password, reopen with
 * both, put aside and swap back.
 */
@RunWith(AndroidJUnit4::class)
class VaultRoundTripDeviceTest {
    private val scratch = Scratch()

    @After
    fun removeScratch() = scratch.remove()

    @Test
    fun theRoundTripOnOsFilesAndNativeArgon2id() {
        val native = NativeArgon2id.load() ?: throw AssertionError("argon2kt does not load on this device")
        val vaultsDir = scratch.path("vaults")
        VaultRoundTrip.roundTrip(OsFiles(), vaultsDir, native, CryptoFactory.aead(), SystemRandom(), AndroidClock)
        val live = File("$vaultsDir/live").list()!!.sorted()
        assertEquals("three generations, nothing else", listOf("vault-0000000001.pvault", "vault-0000000002.pvault", "vault-0000000003.pvault"), live)
    }
}
