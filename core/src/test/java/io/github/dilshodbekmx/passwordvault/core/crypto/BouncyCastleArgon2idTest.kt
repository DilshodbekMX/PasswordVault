package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * `BouncyCastleArgon2id` through [CryptoFactory.bouncyCastleArgon2id], beyond the known answers of
 * [Argon2KnownAnswerTest]: what it refuses (as the reference implementation, and so argon2kt, does),
 * the length of its output, its inputs left as they were, and no state kept between calls. Small
 * memory here, so that each derivation takes milliseconds.
 */
class BouncyCastleArgon2idTest {

    private val argon2 = CryptoFactory.bouncyCastleArgon2id()
    private val password = "correct horse battery staple".toByteArray(Charsets.UTF_8)
    private val salt = ByteArray(16) { it.toByte() }
    private val small = Argon2Params(memoryKiB = 256, passes = 1, lanes = 4, outLen = 32)

    private fun refused(what: String, password: ByteArray = this.password, salt: ByteArray = this.salt, params: Argon2Params) {
        try {
            argon2.derive(password, salt, params)
            fail("$what was accepted")
        } catch (e: IllegalArgumentException) {
            assertFalse("the message holds no input", e.message.orEmpty().contains("correct"))
        }
    }

    @Test
    fun itRefusesWhatTheReferenceImplementationRefuses() {
        refused("a salt of 7 bytes", salt = ByteArray(7), params = small)
        refused("an output of 3 bytes", params = small.copy(outLen = 3))
        refused("no pass", params = small.copy(passes = 0))
        refused("no lane", params = small.copy(lanes = 0))
        refused("2^24 lanes", params = Argon2Params(memoryKiB = 1 shl 27, passes = 1, lanes = 1 shl 24, outLen = 32))
        refused("less than 8 KiB per lane", params = Argon2Params(memoryKiB = 31, passes = 1, lanes = 4, outLen = 32))
    }

    @Test
    fun theSmallestOfEachIsAccepted() {
        assertEquals(4, argon2.derive(ByteArray(0), ByteArray(8), Argon2Params(memoryKiB = 8, passes = 1, lanes = 1, outLen = 4)).size)
        assertEquals(32, argon2.derive(password, salt, Argon2Params(memoryKiB = 32, passes = 1, lanes = 4, outLen = 32)).size)
    }

    @Test
    fun itGivesTheLengthAskedFor() {
        for (outLen in listOf(4, 16, 32, 64, 100)) assertEquals(outLen, argon2.derive(password, salt, small.copy(outLen = outLen)).size)
    }

    @Test
    fun theInputsAreNotChanged() {
        val passwordCopy = password.copyOf()
        val saltCopy = salt.copyOf()
        argon2.derive(password, salt, small)
        assertArrayEquals(passwordCopy, password)
        assertArrayEquals(saltCopy, salt)
    }

    @Test
    fun everyInputChangesTheKey() {
        val key = Bytes.hex(argon2.derive(password, salt, small))
        val others = listOf(
            argon2.derive("correct horse battery stapl".toByteArray(Charsets.UTF_8), salt, small),
            argon2.derive(password, ByteArray(16) { (it + 1).toByte() }, small),
            argon2.derive(password, salt, small.copy(memoryKiB = 512)),
            argon2.derive(password, salt, small.copy(passes = 2)),
            argon2.derive(password, salt, small.copy(lanes = 2)),
        ).map { Bytes.hex(it) }
        assertEquals(others.size, others.toSet().size)
        assertFalse(key in others)
    }

    @Test
    fun noStateIsKeptBetweenCalls_orSharedBetweenThreads() {
        val expected = (0 until 16).map { Bytes.hex(argon2.derive(byteArrayOf(it.toByte()), salt, small)) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 16).map { n -> Callable { Bytes.hex(argon2.derive(byteArrayOf(n.toByte()), salt, small)) } }
            val again = pool.invokeAll(tasks).map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(expected, again)
        } finally {
            pool.shutdownNow()
        }
    }
}
