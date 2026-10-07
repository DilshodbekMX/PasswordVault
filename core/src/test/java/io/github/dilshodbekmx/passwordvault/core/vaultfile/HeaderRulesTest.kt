package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.HEADER_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.assemble
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.padded
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.retrailer
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.sha256
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.u32
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.utf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader rules without a key, R1 to R7 of architecture.md §4.4 (`VaultCodec.check`), each with
 * files that break exactly that rule: every other rule holds (the length field is right, the body
 * is sealed under the file's own AAD and the trailer is good unless the rule is about it).
 */
class HeaderRulesTest {
    private val codec = codec()

    private fun check(bytes: ByteArray) = codec.check(bytes)

    private fun header(edit: (String) -> String): String {
        val edited = edit(HEADER_A)
        assertFalse("the edit changed nothing", edited == HEADER_A)
        return edited
    }

    private fun ok(file: ByteArray): VaultFile {
        val result = check(file)
        assertTrue("expected OK, got $result", result is FileCheck.Ok)
        return (result as FileCheck.Ok).file
    }

    // ------------------------------------------------------------------------------------ R1

    @Test
    fun r1_whatIsNotOfTheFamilyIsNotOurs() {
        val rest = FILE_A.copyOfRange(8, FILE_A.size)
        val notOurs = mapOf(
            "empty" to ByteArray(0),
            "seven bytes of the magic" to utf8("PVAULT0"),
            "zero-filled, the shape of a lost write" to ByteArray(FILE_A.size),
            "another family" to utf8("PVAULX01") + rest,
            "lower case" to utf8("pvault01") + rest,
            "a letter for a digit" to utf8("PVAULTx1") + rest,
            "a digit and a letter" to utf8("PVAULT0x") + rest,
            "Arabic-Indic digits" to utf8("PVAULT٠١") + rest,
            "a JSON file" to utf8("""{"schema":1,"entries":[]}"""),
        )
        for ((what, bytes) in notOurs) assertEquals(what, FileCheck.NotOurs, check(bytes))
    }

    @Test
    fun r1_anotherFramingIsNewerOnlyWithAGoodTrailer() {
        val rest = FILE_A.copyOfRange(8, FILE_A.size)
        assertEquals(FileCheck.Newer(2), check(retrailer(utf8("PVAULT02") + rest)))
        assertEquals("the trailer no longer fits", FileCheck.Damaged(1), check(utf8("PVAULT02") + rest))
        // A new framing is opaque to version 1: its trailer is all that is checked.
        val forty = utf8("PVAULT99") + sha256(utf8("PVAULT99"))
        assertEquals(40, forty.size)
        assertEquals(FileCheck.Newer(99), check(forty))
        assertEquals("under 40 bytes", FileCheck.Damaged(1), check(utf8("PVAULT02") + ByteArray(31)))
        assertEquals("one flipped bit must not lock a vault as newer", FileCheck.Damaged(1), check(utf8("PVAULT03") + FILE_A.copyOfRange(8, FILE_A.size)))
    }

    // ------------------------------------------------------------------------------------ R2

    @Test
    fun r2_lengthIs74ToTheCap() {
        assertEquals(FileCheck.Damaged(2), check(FILE_A.copyOf(73)))
        // 74 bytes: magic, a 2-byte header, the nonce, a body of the tag alone, the trailer. R2 and R3 hold; R5 does not.
        val smallest = Vectors.withTrailer(utf8("PVAULT01") + u32(2) + utf8("{}") + ByteArray(12) + ByteArray(16))
        assertEquals(74, smallest.size)
        assertEquals(FileCheck.Damaged(5), check(smallest))
        val cap = 16_777_216
        val over = utf8("PVAULT01") + u32(499) + ByteArray(cap + 1 - 12)
        assertEquals(cap + 1, over.size)
        assertEquals("a reader reads at most the cap and one byte", FileCheck.Damaged(2), check(over))
        val atCap = utf8("PVAULT01") + u32(499) + ByteArray(cap - 12)
        assertEquals("the cap itself passes R2", FileCheck.Damaged(5), check(atCap))
    }

    // ------------------------------------------------------------------------------------ R3

    @Test
    fun r3_headerLengthIs2To4096AndFitsTheFile() {
        val body = FILE_A.copyOfRange(12, FILE_A.size)
        for (length in listOf(0L, 1L, 4_097L, 0x8000_0000L, 0xFFFF_FFFFL)) {
            assertEquals("header_len $length", FileCheck.Damaged(3), check(utf8("PVAULT01") + u32(length) + body))
        }
        // 56 + header_len + 16 must not exceed the length.
        assertEquals(FileCheck.Damaged(3), check(FILE_A.copyOf(56 + 499 + 15)))
        assertEquals("one byte more passes R3 and fails R7 on body_len", FileCheck.Damaged(7), check(FILE_A.copyOf(56 + 499 + 16)))
        // 4,096 itself passes R3; such a header cannot be canonical, so R5 refuses this one.
        val longHeader = ByteArray(4_096) { 'x'.code.toByte() }
        assertEquals(FileCheck.Damaged(5), check(assemble(longHeader)))
    }

    // ------------------------------------------------------------------------------------ R4

    @Test
    fun r4_aWrongTrailerIsRememberedNotRefused() {
        val file = FILE_A.copyOf()
        file[file.size - 1] = (file[file.size - 1].toInt() xor 0x80).toByte()
        val read = ok(file)
        assertFalse(read.trailerOk)
        // The trailer authenticates nothing: a body that opens under VK is good [V-S3].
        assertEquals(BodyResult.Opened(Vault()), codec.open(read, VK))
        assertTrue(ok(FILE_A).trailerOk)
    }

    // ------------------------------------------------------------------------------------ R5

    @Test
    fun r5_theHeaderStartsWithFormatAndVersion() {
        val broken = listOf(
            header { it.replace("\"version\":1,", "\"version\":01,") },
            header { it.replace("\"version\":1,", "\"version\":0,") },
            header { it.replace("\"version\":1,", "\"version\":1000000000,") }, // ten digits
            header { it.replace("\"version\":1,", "\"version\":-1,") },
            header { it.replace("\"version\":1,", "\"version\":\"1\",") },
            header { it.replace("\"version\":1,", "\"version\":1.0,") },
            header { it.replace("\"version\":1,", "\"version\": 1,") },
            header { it.replace("{\"format\"", "{ \"format\"") },
            header { it.replace("\"passwordvault\"", "\"PasswordVault\"") },
            header { it.replace("{\"format\":\"passwordvault\",\"version\":1,", "{\"version\":1,\"format\":\"passwordvault\",") },
            header { " $it" },
            "{\"format\":\"passwordvault\",\"version\":1",
        )
        for (text in broken) assertEquals(text.take(48), FileCheck.Damaged(5), check(assemble(text)))
    }

    // ------------------------------------------------------------------------------------ R6

    @Test
    fun r6_aHigherVersionIsNewerWithAGoodTrailerAndDamagedWithout() {
        val v2 = header { it.replace("\"version\":1,", "\"version\":2,") }
        assertEquals(FileCheck.Newer(2), check(assemble(v2)))
        assertEquals(FileCheck.Damaged(6), check(assemble(v2, goodTrailer = false)))
        val v9 = header { it.replace("\"version\":1,", "\"version\":999999999,") }
        assertEquals(FileCheck.Newer(999_999_999), check(assemble(v9)))
        // A newer version's header need not follow version 1's template at all.
        assertEquals(FileCheck.Newer(2), check(assemble("""{"format":"passwordvault","version":2,"anything":[true]}""")))
    }

    // ------------------------------------------------------------------------------------ R7

    @Test
    fun r7_theHeaderMatchesTheTemplateInFull() {
        val broken = mapOf(
            "upper-case vault_id" to header { it.replace("000000000001\"", "00000000000A\"") },
            "vault_id of UUID version 3" to header { it.replace("\"vault_id\":\"00000000-0000-4000", "\"vault_id\":\"00000000-0000-3000") },
            "vk_id with variant c" to header { it.replace("4000-8000-000000000002", "4000-c000-000000000002") },
            "vault_id without hyphens" to header { it.replace("00000000-0000-4000-8000-000000000001", "00000000000040008000000000000001") },
            "generation 0" to header { it.replace("\"generation\":1,", "\"generation\":0,") },
            "generation 01" to header { it.replace("\"generation\":1,", "\"generation\":01,") },
            "generation of 11 digits" to header { it.replace("\"generation\":1,", "\"generation\":10000000000,") },
            "generation -1" to header { it.replace("\"generation\":1,", "\"generation\":-1,") },
            "generation as text" to header { it.replace("\"generation\":1,", "\"generation\":\"1\",") },
            "created with a leading zero" to header { it.replace("\"created\":1791244800000,", "\"created\":01791244800000,") },
            "created of 17 digits" to header { it.replace("\"created\":1791244800000,", "\"created\":10000000000000000,") },
            "created -1" to header { it.replace("\"created\":1791244800000,", "\"created\":-1,") },
            "created with a fraction" to header { it.replace("\"created\":1791244800000,", "\"created\":1791244800000.0,") },
            "empty generator" to header { it.replace("\"PasswordVault 1.0\"", "\"\"") },
            "generator with +" to header { it.replace("\"PasswordVault 1.0\"", "\"PasswordVault 1.0+git\"") },
            "generator of 65 characters" to header { it.replace("\"PasswordVault 1.0\"", "\"" + "x".repeat(65) + "\"") },
            "generator with an escaped quote" to header { it.replace("\"PasswordVault 1.0\"", "\"Password\\\"Vault\"") },
            "generator with a letter outside ASCII" to header { it.replace("\"PasswordVault 1.0\"", "\"PasswordVault é\"") },
            "writer_version 0" to header { it.replace("\"writer_version\":1,", "\"writer_version\":0,") },
            "writer_version 2^31" to header { it.replace("\"writer_version\":1,", "\"writer_version\":2147483648,") },
            "writer_version 01" to header { it.replace("\"writer_version\":1,", "\"writer_version\":01,") },
            "body_len that does not fit the file" to header { it.replace("\"body_len\":4112,", "\"body_len\":4128,") },
            "body_len with a leading zero" to header { it.replace("\"body_len\":4112,", "\"body_len\":04112,") },
            "salt with pad bits set" to header { it.replace("AAECAwQFBgcICQoLDA0ODw==", "AAECAwQFBgcICQoLDA0ODx==") },
            "salt without padding" to header { it.replace("AAECAwQFBgcICQoLDA0ODw==", "AAECAwQFBgcICQoLDA0ODw") },
            "salt of 17 bytes" to header { it.replace("AAECAwQFBgcICQoLDA0ODw==", "AAECAwQFBgcICQoLDA0ODxA=") },
            "nonce of 11 bytes" to header { it.replace("ICEiIyQlJicoKSor", "ICEiIyQlJicoKSo=") },
            "wrapped_key in the URL-safe alphabet" to header { it.replace("TaQ2ZiOElYlVT+Mk", "TaQ2ZiOElYlVT-Mk") },
            "wrapped_key of 47 bytes" to header { it.replace("PeIxcr\"", "PeIxc=\"") },
            "argon2_version 16" to header { it.replace("\"argon2_version\":19,", "\"argon2_version\":16,") },
            "m_kib 65537" to header { it.replace("\"m_kib\":65536,", "\"m_kib\":65537,") },
            "t 2" to header { it.replace("\"t\":3,", "\"t\":2,") },
            "p 1" to header { it.replace("\"p\":4,", "\"p\":1,") },
            "kdf argon2i" to header { it.replace("\"argon2id\"", "\"argon2i\"") },
            "a PIN slot" to header { it.replace("\"type\":\"password\"", "\"type\":\"pin\"") },
            "another cipher" to header { it.replace("AES-256-GCM", "AES-128-GCM") },
            "no slot" to header { it.substring(0, it.indexOf("[{")) + "[]}" },
            "two slots" to header { it.replace("}]}", "}," + it.substring(it.indexOf("{\"type\""), it.length - 2) + "]}") },
            "an unknown key at the top" to header { it.replace(",\"slots\"", ",\"extra\":1,\"slots\"") },
            "an unknown key in the slot" to header { it.replace("\"}]}", "\",\"extra\":1}]}") },
            "a key twice" to header { it.replace("\"generation\":1,", "\"generation\":1,\"generation\":1,") },
            "keys in another order" to header { it.replace("\"t\":3,\"p\":4,", "\"p\":4,\"t\":3,") },
            "white space after a colon" to header { it.replace("\"vk_id\":\"", "\"vk_id\": \"") },
            "a space at the end" to header { "$it " },
            "a line feed at the end" to header { it + "\n" },
            "a comma before the bracket" to header { it.replace("}]}", "},]}") },
        )
        for ((what, text) in broken) assertEquals(what, FileCheck.Damaged(7), check(assemble(text)))
    }

    @Test
    fun r7_bodyLenIsAWholeNumberOfBlocksAndTheTag() {
        fun withBody(plaintextSize: Int): ByteArray {
            val bodyLen = plaintextSize + 16
            val text = HEADER_A.replace("\"body_len\":4112,", "\"body_len\":$bodyLen,")
            return assemble(utf8(text), ByteArray(plaintextSize) { 0x20 })
        }
        assertEquals("one byte more than a block", FileCheck.Damaged(7), check(withBody(4_097)))
        assertEquals("the tag alone", FileCheck.Damaged(7), check(withBody(0)))
        assertEquals("one byte less than a block", FileCheck.Damaged(7), check(withBody(4_095)))
        assertEquals(8_208, ok(withBody(8_192)).header.bodyLen)
        // Nothing follows the trailer: a byte after it changes the length that body_len must match.
        assertEquals(FileCheck.Damaged(7), check(retrailer(FILE_A + byteArrayOf(0))))
    }

    @Test
    fun r7_theBoundsThemselvesAreAccepted() {
        val generator64 = "PasswordVault " + "9".repeat(50)
        val accepted = listOf(
            header { it.replace("\"generation\":1,", "\"generation\":9999999999,") },
            header { it.replace("\"created\":1791244800000,", "\"created\":0,") },
            header { it.replace("\"created\":1791244800000,", "\"created\":9999999999999999,") },
            header { it.replace("\"PasswordVault 1.0\"", "\"Az09 ._()-\"") },
            header { it.replace("\"PasswordVault 1.0\"", "\"$generator64\"") },
            header { it.replace("\"PasswordVault 1.0\"", "\" \"") },
            header { it.replace("\"writer_version\":1,", "\"writer_version\":2147483647,") },
            header { it.replace("00000000-0000-4000-8000-000000000001", "ffffffff-ffff-4fff-bfff-ffffffffffff") },
        )
        for (text in accepted) {
            val file = ok(assemble(text))
            assertEquals(BodyResult.Opened(Vault()), codec.open(file, VK))
        }
        val read = ok(assemble(accepted[0])).header
        assertEquals(9_999_999_999L, read.generation)
        assertEquals(0L, ok(assemble(accepted[1])).header.createdMs)
        assertEquals(9_999_999_999_999_999L, ok(assemble(accepted[2])).header.createdMs)
        assertEquals(64, generator64.length)
        assertEquals(generator64, ok(assemble(accepted[4])).header.generator)
        assertEquals(Int.MAX_VALUE, ok(assemble(accepted[6])).header.writerVersion)
    }

    @Test
    fun theHeaderIsReadWithoutTheBody() {
        // check() needs no key: a body that no key opens still passes R1 to R7.
        val file = ok(assemble(utf8(HEADER_A), padded(utf8("not even JSON")), vk = ByteArray(32) { 7 }))
        assertEquals(BodyResult.TagFailed, codec.open(file, VK))
    }
}
