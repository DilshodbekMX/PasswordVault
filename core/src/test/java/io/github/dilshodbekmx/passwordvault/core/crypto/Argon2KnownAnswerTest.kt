package io.github.dilshodbekmx.passwordvault.core.crypto

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Argon2id implementation to known answers, so that a library upgrade can never
 * silently change the key derived from a master password (architecture.md §8.7, "Argon2id, two
 * cases"). Both run through [CryptoFactory.bouncyCastleArgon2id], the implementation of the JVM
 * tests and of the fallback on a phone; package 2.7 runs the same two through argon2kt on the
 * devices.
 */
class Argon2KnownAnswerTest {

    private val argon2 = CryptoFactory.bouncyCastleArgon2id()

    private fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, passes: Int, lanes: Int): String =
        Bytes.hex(argon2.derive(password, salt, Argon2Params(memoryKiB, passes, lanes, 32)))

    /**
     * The vault's own parameters, [Argon2Params.V1]: 64 MiB, 3 passes, 4 lanes. Reproduced by
     * argon2kt on Android 7, 9 and 17. It is also the KEK of the vault file vectors
     * (architecture.md §4.13).
     */
    @Test
    fun vaultParameters() {
        assertEquals(Argon2Params(memoryKiB = 65536, passes = 3, lanes = 4, outLen = 32), Argon2Params.V1)
        assertEquals(
            "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e",
            argon2id(
                password = "correct horse battery staple".toByteArray(Charsets.UTF_8),
                salt = ByteArray(16) { it.toByte() },
                memoryKiB = 65536,
                passes = 3,
                lanes = 4,
            ),
        )
    }

    /** From the reference implementation's test suite (phc-winner-argon2, test.c, version 0x13). */
    @Test
    fun referenceImplementationVector() {
        assertEquals(
            "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7",
            argon2id(
                password = "password".toByteArray(Charsets.UTF_8),
                salt = "somesalt".toByteArray(Charsets.UTF_8),
                memoryKiB = 65536,
                passes = 2,
                lanes = 1,
            ),
        )
    }
}
