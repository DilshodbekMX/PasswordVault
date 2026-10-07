package io.github.dilshodbekmx.passwordvault.core.integration

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import io.github.dilshodbekmx.passwordvault.testing.storage.VaultRoundTrip
import org.junit.Assume
import org.junit.Test

/**
 * The vault through the real parts of :core together (build plan 2.3): the store of 2.3, the codec
 * of 2.2, AES-256-GCM and Argon2id of 2.1, `MasterPasswordBytes` of 1.3, on [FakeFiles].
 *
 * Create with a password, reopen with the password, edit, reopen with the key, change the
 * password, reopen with both, put aside and swap back. Every "reopen" is a new store, as a new
 * process would make it, over the same files.
 *
 * The steps are [VaultRoundTrip.roundTrip] in :testing (moved there by build plan 2.7), which takes
 * its ports as arguments so that the same steps run on a phone with `OsFiles` and `NativeArgon2id`
 * (the app's instrumented `platform.VaultRoundTripDeviceTest`). A step that does not hold throws
 * `IllegalStateException`, which fails this test.
 *
 * Skipped while `CryptoFactory` or `VaultCodecs` still throws `NotImplementedError` (its packages
 * are built beside this one); it must pass at the integration check of sub-wave 2A.
 */
class VaultRoundTripTest {
    @Test
    fun createReopenEditChangeAndSwap() {
        val parts = try {
            val aead = CryptoFactory.aead()
            val argon2 = CryptoFactory.bouncyCastleArgon2id()
            Triple(aead, argon2, VaultCodecs.create(aead, FakeRandom(5L), FakeClock(), VaultRoundTrip.BUILD))
        } catch (e: NotImplementedError) {
            Assume.assumeNoException("skipped while a factory still throws (packages 2.1 and 2.2)", e)
            return
        }
        val files = FakeFiles().apply { mkdirs("/data/files") }
        VaultRoundTrip.roundTrip(files, "/data/files/vaults", parts.second, parts.first, FakeRandom(6L), FakeClock())
    }
}
