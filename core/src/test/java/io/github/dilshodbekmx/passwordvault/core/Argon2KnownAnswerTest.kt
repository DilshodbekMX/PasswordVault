package io.github.dilshodbekmx.passwordvault.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Argon2id implementation to known answers, so that a library upgrade can never
 * silently change the key derived from a master password.
 */
class Argon2KnownAnswerTest {

    private fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, passes: Int, lanes: Int): String {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(passes)
            .withParallelism(lanes)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, out)
        return out.joinToString("") { "%02x".format(it) }
    }

    /** The vault's own parameters: 64 MiB, 3 passes, 4 lanes. Reproduced by argon2kt on Android 7, 9 and 17. */
    @Test
    fun vaultParameters() {
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
