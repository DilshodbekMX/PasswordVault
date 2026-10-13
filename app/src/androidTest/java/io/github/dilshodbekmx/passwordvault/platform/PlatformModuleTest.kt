package io.github.dilshodbekmx.passwordvault.platform

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2id
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: what `PlatformModule.create` builds for the app
 * (architecture.md §3.6). The vault directory is under `filesDir` and the lock directory under
 * `noBackupFilesDir` (build check R-3); the app's one KDF derives the vault vector's key from the
 * typed password; and a forced failure to load argon2kt selects the Bouncy Castle fallback.
 */
@RunWith(AndroidJUnit4::class)
class PlatformModuleTest {
    private val context = appContext()

    /** The `Platform` of the running app, as `AppGraph.platform` made it. */
    private val platform by lazy { appGraph().platform }

    private val salt = ByteArray(16) { it.toByte() }

    /** The KEK of architecture.md §4.13 and `Argon2KnownAnswerTest`. */
    private val kek = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"

    @Test
    fun r3_theVaultIsUnderFilesDirAndTheLockRecordsUnderNoBackupFilesDir() {
        val filesDir = context.filesDir.canonicalFile
        val noBackup = context.noBackupFilesDir.canonicalFile
        val vaults = File(platform.vaultsDir).canonicalFile
        val lock = File(platform.lockRoot).canonicalFile
        assertEquals(File(filesDir, "vaults"), vaults)
        assertEquals(File(noBackup, "lock"), lock)
        assertTrue("R-3: ${platform.lockRoot} resolves under getNoBackupFilesDir()", lock.path.startsWith(noBackup.path + "/"))
        assertTrue(vaults.path.startsWith(filesDir.path + "/"))
        assertTrue("the vault is not in no_backup", !vaults.path.startsWith(noBackup.path + "/"))
        assertTrue("the records are not in files", !lock.path.startsWith(filesDir.path + "/"))
        assertTrue("absolute, as the store's paths must be", platform.vaultsDir.startsWith("/") && platform.lockRoot.startsWith("/"))
    }

    @Test
    fun thePlatformIsMadeOfTheAndroidAdapters() {
        assertTrue(platform.files is OsFiles)
        assertSame("one OsFiles for the vault and the records", platform.files, platform.records)
        assertSame(AndroidClock, platform.clock)
        assertTrue(platform.random is SystemRandom)
        assertSame("one Platform for the process", platform, appGraph().platform)
    }

    @Test
    fun theAppsKdfDerivesTheKeyOfTheTypedPassword() = runBlocking {
        // A leading space, two spaces, a no-break space and a trailing space (architecture.md §4.13).
        val bytes = MasterPasswordBytes.of(" correct  horse battery staple ".toCharArray())
        val key = try {
            platform.kdf.kek(bytes, salt)
        } finally {
            Bytes.wipe(bytes)
        }
        assertEquals(kek, Bytes.hex(key))
    }

    @Test
    fun theFallbackNeedsWhatWasMeasured() {
        for (sdk in 24..27) assertEquals("API $sdk", 160L * 1024 * 1024, PlatformModule.fallbackNeeds(sdk))
        for (sdk in 28..37) assertEquals("API $sdk", 96L * 1024 * 1024, PlatformModule.fallbackNeeds(sdk))
    }

    /** Bouncy Castle, counting its derivations. */
    private class CountingFallback : Argon2id {
        val calls = AtomicInteger()
        private val bouncyCastle = CryptoFactory.bouncyCastleArgon2id()

        override fun derive(password: ByteArray, salt: ByteArray, params: Argon2Params): ByteArray {
            calls.incrementAndGet()
            return bouncyCastle.derive(password, salt, params)
        }
    }

    private fun deriveVector(argon2: Argon2id): String =
        Bytes.hex(argon2.derive("correct horse battery staple".toByteArray(Charsets.UTF_8), salt, Argon2Params.V1))

    @Test
    fun aForcedLoadFailureSelectsTheFallback() {
        // Whatever loading throws [V-C2]: argon2kt is not there, and the fallback derives.
        val failures = listOf(
            UnsatisfiedLinkError("forced"),
            SecurityException("forced"),
            IllegalStateException("forced"),
            OutOfMemoryError("forced"),
            ExceptionInInitializerError("forced"),
        )
        for (failure in failures) {
            var loads = 0
            val fallback = CountingFallback()
            val argon2 = PlatformModule.argon2id(NativeArgon2id.loadedOnFirstUse { loads++; throw failure }, fallback)
            assertEquals(failure.toString(), kek, deriveVector(argon2))
            assertEquals(failure.toString(), 1, fallback.calls.get())
            assertEquals(failure.toString(), kek, deriveVector(argon2))
            assertEquals("the fallback for the rest of the process", 2, fallback.calls.get())
            assertEquals("loaded once", 1, loads)
            assertEquals(null, NativeArgon2id.load { throw failure })
        }
    }

    @Test
    fun withTheLibraryLoadedTheFallbackIsNotUsed() {
        val fallback = CountingFallback()
        val argon2 = PlatformModule.argon2id(NativeArgon2id.loadedOnFirstUse(), fallback)
        assertEquals(kek, deriveVector(argon2))
        assertEquals(0, fallback.calls.get())
        assertNotNull("argon2kt loads on this device", NativeArgon2id.load())
    }

    @Test
    fun withoutTheLibraryAndWithoutEnoughHeapItIsKdfUnavailable() {
        val fallback = CountingFallback()
        val argon2 = CryptoFactory.guardedArgon2id(null, fallback, freeHeap = { 0L }, fallbackNeeds = PlatformModule.fallbackNeeds(Build.VERSION.SDK_INT), collect = {})
        try {
            deriveVector(argon2)
            fail("KdfUnavailable expected")
        } catch (e: KdfUnavailable) {
            assertEquals("the fallback is not even tried", 0, fallback.calls.get())
        }
        // The app's own free heap is enough for the fallback on these devices.
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        assertTrue("free heap $free", free >= PlatformModule.fallbackNeeds(Build.VERSION.SDK_INT))
        assertEquals(kek, deriveVector(PlatformModule.argon2id(null, fallback)))
        assertEquals(1, fallback.calls.get())
    }
}
