package io.github.dilshodbekmx.passwordvault.platform

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lambdapioneer.argon2kt.Argon2Exception
import io.github.dilshodbekmx.passwordvault.core.crypto.Argon2Params
import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.nio.ByteBuffer
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], on API 24, 28 and 37: [NativeArgon2id] gives the two Argon2id vectors
 * of `Argon2KnownAnswerTest`, and the same bytes as `CryptoFactory.bouncyCastleArgon2id()` for a
 * 3-byte and a 200-byte non-ASCII password: argon2kt hashes the whole capacity of its buffers, so
 * this holds only with buffers of exactly the input's size [V-C1]. Also: the key is copied once and
 * the returned buffers are zeroed; a load that throws anything gives null [V-C2]; argon2kt's
 * out-of-memory errors are `KdfUnavailable`, never a wrong password.
 */
@RunWith(AndroidJUnit4::class)
class NativeArgon2idTest {
    private val native: NativeArgon2id by lazy { NativeArgon2id.load() ?: throw AssertionError("argon2kt does not load on this device") }
    private val bouncyCastle = CryptoFactory.bouncyCastleArgon2id()

    private fun hex(password: ByteArray, salt: ByteArray, params: Argon2Params) = Bytes.hex(native.derive(password, salt, params))

    @Test
    fun theVaultParametersVector() {
        assertEquals(
            "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e",
            hex("correct horse battery staple".toByteArray(Charsets.UTF_8), ByteArray(16) { it.toByte() }, Argon2Params.V1),
        )
    }

    @Test
    fun theReferenceImplementationVector() {
        // phc-winner-argon2, test.c, version 0x13.
        assertEquals(
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            hex("password".toByteArray(Charsets.UTF_8), "somesalt".toByteArray(Charsets.UTF_8), Argon2Params(65_536, 2, 1, 32)),
        )
    }

    @Test
    fun theSameBytesAsBouncyCastleForA3ByteAndA200ByteNonAsciiPassword() {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val passwords = listOf(
            "Ўz", // d0 8e 7a
            "Ўзбекистон".repeat(10), // 100 Cyrillic letters, 200 bytes
        ).map { it.toByteArray(Charsets.UTF_8) }
        assertEquals(listOf(3, 200), passwords.map { it.size })
        for (password in passwords) {
            val fromNative = native.derive(password, salt, Argon2Params.V1)
            val fromBouncyCastle = bouncyCastle.derive(password, salt, Argon2Params.V1)
            assertArrayEquals("${password.size} bytes", fromBouncyCastle, fromNative)
        }
    }

    @Test
    fun theSameBytesAsBouncyCastleWhateverTheLengths() {
        // Small work areas, so that many lengths of password and salt take little time.
        val params = Argon2Params(memoryKiB = 256, passes = 2, lanes = 4, outLen = 32)
        val random = SecureRandom()
        for (passwordSize in listOf(0, 1, 2, 3, 7, 8, 15, 16, 17, 31, 32, 33, 64, 127, 200, 1_000)) {
            for (saltSize in listOf(8, 16, 17, 32)) {
                val password = ByteArray(passwordSize).also { random.nextBytes(it) }
                val salt = ByteArray(saltSize).also { random.nextBytes(it) }
                assertArrayEquals("password $passwordSize, salt $saltSize", bouncyCastle.derive(password, salt, params), native.derive(password, salt, params))
            }
        }
    }

    @Test
    fun theKnownAnswerOfTheLoadIsBouncyCastles() {
        val params = NativeArgon2id.SELF_TEST_PARAMS
        assertArrayEquals(NativeArgon2id.SELF_TEST_KEY, bouncyCastle.derive(NativeArgon2id.SELF_TEST_PASSWORD, NativeArgon2id.SELF_TEST_SALT, params))
        assertArrayEquals(NativeArgon2id.SELF_TEST_KEY, native.derive(NativeArgon2id.SELF_TEST_PASSWORD, NativeArgon2id.SELF_TEST_SALT, params))
    }

    @Test
    fun theKeyIsCopiedOnceAndBothReturnedBuffersAreZeroed() {
        val hash = ByteArray(32) { (it + 1).toByte() }
        val rawHash = ByteBuffer.allocateDirect(32).put(hash)
        val encoded = ByteBuffer.allocateDirect(90).put(ByteArray(90) { 0x41 })
        val key = NativeArgon2id.takeKey(rawHash, encoded, 32)
        assertArrayEquals(hash, key)
        assertTrue("the hash buffer is zeroed", (0 until 32).all { rawHash.get(it) == 0.toByte() })
        assertTrue("the encoded buffer is zeroed", (0 until 90).all { encoded.get(it) == 0.toByte() })

        val wrongLength = ByteBuffer.allocateDirect(16).put(ByteArray(16) { 7 })
        val encoded2 = ByteBuffer.allocateDirect(10).put(ByteArray(10) { 7 })
        try {
            NativeArgon2id.takeKey(wrongLength, encoded2, 32)
            fail("a hash of another length")
        } catch (e: IllegalStateException) {
            assertTrue("zeroed all the same", (0 until 16).all { wrongLength.get(it) == 0.toByte() } && (0 until 10).all { encoded2.get(it) == 0.toByte() })
        }
    }

    @Test
    fun theInputsAreLeftAsTheyWere() {
        val password = "Ўз".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16) { it.toByte() }
        native.derive(password, salt, Argon2Params(64, 1, 4, 32))
        assertArrayEquals("Ўз".toByteArray(Charsets.UTF_8), password)
        assertArrayEquals(ByteArray(16) { it.toByte() }, salt)
    }

    @Test
    fun aLoadThatThrowsAnythingGivesNull() {
        for (failure in listOf(UnsatisfiedLinkError("forced"), SecurityException("forced"), RuntimeException("forced"), OutOfMemoryError("forced"), StackOverflowError("forced"))) {
            assertNull(failure.toString(), NativeArgon2id.load { throw failure })
        }
        var asked: String? = null
        assertNotNull(NativeArgon2id.load { asked = it; System.loadLibrary(it) })
        assertEquals("argon2jni", asked)
    }

    @Test
    fun argon2ktsOutOfMemoryErrorsAreKdfUnavailable() {
        // argon2kt's own exceptions, made from the codes its native layer returns.
        for (code in listOf(-22, -33, 1003)) { // ARGON2_MEMORY_ALLOCATION_ERROR, ARGON2_THREAD_FAIL, ARGON2JNI_MALLOC_FAILED
            val error = Argon2Exception.fromErrorCode(code)
            val mapped = NativeArgon2id.failure(error)
            assertTrue("$code: $mapped", mapped is KdfUnavailable && mapped.cause === error)
        }
        for (code in listOf(-6, -14, -15, -35, 1000)) { // salt too short, memory too little or too much, …
            val error = Argon2Exception.fromErrorCode(code)
            assertTrue("$code passes unchanged", NativeArgon2id.failure(error) === error)
        }
    }

    /**
     * 2 TiB of work area. In a 64-bit process argon2 accepts the size, and scudo, the allocator from
     * Android 11 on, refuses any request above 1 TiB, so argon2's malloc returns null: KdfUnavailable.
     * A 32-bit process refuses the size before allocating (ARGON2_MEMORY_TOO_MUCH), which passes
     * unchanged. Not run below Android 11: the Android 7 and 9 images allocate with jemalloc over a
     * kernel that promises any amount (vm.overcommit_memory 1), so the request succeeds and the
     * low-memory killer ends the process while Argon2 fills it, which no code can catch (seen on both
     * on 2026-10-07). `@SdkSuppress`, not an assumption: AGP 9.4.1 reports a test that ends in an
     * assumption failure as failed.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun aWorkAreaThatCannotBeAllocatedIsKdfUnavailable() {
        try {
            native.derive("x".toByteArray(), ByteArray(16), Argon2Params(Int.MAX_VALUE, 1, 4, 32))
            fail("2 TiB were allocated")
        } catch (e: KdfUnavailable) {
            assertTrue("a 64-bit process", Process.is64Bit())
            assertTrue("caused by argon2kt: ${e.cause}", e.cause is Argon2Exception)
        } catch (e: Argon2Exception) {
            assertTrue("a 32-bit process refuses the size: ${e.message}", !Process.is64Bit() && e.message!!.startsWith("ARGON2_MEMORY_TOO_MUCH"))
        }
        // The process goes on, and so does argon2kt.
        theReferenceImplementationVector()
    }

    @Test
    fun anyOtherArgon2ktErrorPassesUnchanged() {
        try {
            native.derive("x".toByteArray(), ByteArray(4), Argon2Params(64, 1, 4, 32)) // a 4-byte salt
            fail("argon2 takes at least 8 bytes of salt")
        } catch (e: Argon2Exception) {
            assertTrue("a programming error, not KdfUnavailable: ${e.message}", e.message!!.startsWith("ARGON2_SALT_TOO_SHORT"))
        }
    }
}
