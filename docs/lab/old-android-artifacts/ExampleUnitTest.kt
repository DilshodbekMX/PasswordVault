package com.example.pvlab.old

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun serialization_roundTrips() {
        val v = VaultFile(1, listOf(VaultEntry("1", "Mail", "me@example.com", "s3cret", listOf("x"))))
        assertEquals(v, Json.decodeFromString(VaultFile.serializer(), Json.encodeToString(VaultFile.serializer(), v)))
    }

    /** Host-JVM cross-check of the value the device lab compares against. */
    @Test
    fun bouncyCastleArgon2id_labVector() {
        val out = Kdf.bouncy(
            "correct horse battery staple".toByteArray(Charsets.UTF_8),
            ByteArray(16) { it.toByte() },
            Argon2Params(memKiB = 65536, iterations = 3, lanes = 4, outLen = 32),
        )
        assertEquals(Probes.EXPECTED, out.hex())
    }
}
