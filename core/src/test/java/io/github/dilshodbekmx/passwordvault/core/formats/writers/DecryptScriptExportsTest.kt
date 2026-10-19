package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodecs
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The app's own CSV and JSON exports of the vaults of vectors A and B (architecture.md §4.13), as
 * SHA-256.
 *
 * `tools/pvault_decrypt.py` (build plan 8.3) writes these files again in Python and pins the same four
 * values in its `VECTOR_EXPORTS` (with `VECTOR_EXPORTED_AT` and `VECTOR_GENERATOR`, the arguments used
 * here), which its `--check` compares with what it writes. So changing one means changing the other: a
 * change to [GoogleCsv] or [BitwardenJson] that makes this test fail must change the script's writers
 * and those values too, or the script would quietly stop writing what the app writes. Nothing else in
 * the Gradle build would notice (8.3's interface request, merged in round R5 as `iface-155`).
 */
class DecryptScriptExportsTest {
    @Test
    fun vectorA_theCsvAndTheJsonAreTheBytesTheDecryptScriptPins() {
        val vault = open("v1-container.pvault")
        assertTrue("vector A holds no account", vault.entries.isEmpty())
        assertExports(vault, csv = CSV_A, json = JSON_A)
    }

    @Test
    fun vectorB_theCsvAndTheJsonAreTheBytesTheDecryptScriptPins() {
        val vault = open("v1-body.pvault")
        assertEquals("vector B holds one account", 1, vault.entries.size)
        assertExports(vault, csv = CSV_B, json = JSON_B)
    }

    private fun assertExports(vault: Vault, csv: String, json: String) {
        val parts = GoogleCsv.export(vault.entries).parts
        assertEquals("one CSV file", 1, parts.size)
        assertEquals("the CSV's SHA-256 (tools/pvault_decrypt.py, VECTOR_EXPORTS)", csv, sha256(parts.single()))
        val written = BitwardenJson.export(vault, EXPORTED_AT_MS, GENERATOR)
        assertEquals("the JSON's SHA-256 (tools/pvault_decrypt.py, VECTOR_EXPORTS)", json, sha256(written.bytes))
    }

    /** The vector file [name] of `core/src/test/resources/vectors/`, opened with its VK through the app's codec. */
    private fun open(name: String): Vault {
        val bytes = javaClass.getResourceAsStream("/vectors/$name")!!.use { it.readBytes() }
        val codec = VaultCodecs.create(CryptoFactory.aead(), FakeRandom(), FakeClock(), BuildInfo("PasswordVault 1.0", 1))
        val checked = codec.check(bytes)
        assertTrue("$name passes R1 to R7: $checked", checked is FileCheck.Ok)
        val opened = codec.open((checked as FileCheck.Ok).file, VK)
        assertTrue("$name opens with VK a0..bf: $opened", opened is BodyResult.Opened)
        return (opened as BodyResult.Opened).vault
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private companion object {
        /** VK of §4.13: the 32 bytes a0, a1, …, bf. */
        val VK = ByteArray(32) { (0xa0 + it).toByte() }

        /** `VECTOR_EXPORTED_AT` and `VECTOR_GENERATOR` of tools/pvault_decrypt.py. */
        const val EXPORTED_AT_MS = 1_791_331_200_000L
        const val GENERATOR = "pvault_decrypt.py 1.0"

        /** `VECTOR_EXPORTS` of tools/pvault_decrypt.py: vector A's CSV and JSON, then vector B's. */
        const val CSV_A = "029c9442ca214587f5da8fa3c788d5f69fd6582e5b65a00ad0e019a7addaa795"
        const val JSON_A = "2c55903829b07d9b0cd0ae2b3e1425ace3b881af71c6a9638db9fef8a94a3f57"
        const val CSV_B = "2fdd971031f829451c74f4ec5727eb1b2ed77c2a7eb54566c61322c61c96a13c"
        const val JSON_B = "da2bf44e4e1db467b2174eb8d44d05b55d6d872be572e927996ddd4079b020b1"
    }
}
