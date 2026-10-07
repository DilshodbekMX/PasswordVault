package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.utf8
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The writer's canonical form of architecture.md §4.3: one byte sequence for a [Vault], every key in
 * the order of the tables, kept unknown keys after the known ones, the escapes of §4.3, the padding,
 * and what the writer refuses. Vector B pins one body; these tests pin the rules for any body.
 */
class CanonicalFormTest {
    private fun text(s: String) = CanonicalJson().apply { string(s) }.text()

    private fun utf8Of(s: String) = CanonicalJson().apply { string(s) }.utf8()

    private fun written(vault: Vault) = String(BodyCodec.write(vault), Charsets.UTF_8)

    @Test
    fun stringsAreEscapedAsSection43Says() {
        val expected = mapOf(
            "\"" to "\"\\\"\"",
            "\\" to "\"\\\\\"",
            "/" to "\"/\"",
            "\b" to "\"\\b\"",
            "\t" to "\"\\t\"",
            "\n" to "\"\\n\"",
            "\u000C" to "\"\\f\"",
            "\r" to "\"\\r\"",
            "\u0000" to "\"\\u0000\"",
            "\u0001" to "\"\\u0001\"",
            "\u000B" to "\"\\u000b\"",
            "\u001B" to "\"\\u001b\"",
            "\u001F" to "\"\\u001f\"",
            "\u007F" to "\"\u007F\"",
            "\u2028" to "\"\u2028\"",
            "\uFEFF" to "\"\uFEFF\"",
            "O\u02BBzbekiston \u040E\u0437\u0431\u0435\u043A" to "\"O\u02BBzbekiston \u040E\u0437\u0431\u0435\u043A\"",
            "\uD83D\uDD11" to "\"\uD83D\uDD11\"",
        )
        for ((value, json) in expected) assertEquals(Bytes.hex(utf8(value)), json, text(value))
    }

    @Test
    fun everyCharacterIsWrittenAsKotlinxSerializationWritesIt() {
        // §4.3: the canonical form is what kotlinx-serialization 1.11.0's JsonElement.toString() writes.
        for (code in 0..0xFFFF) {
            val s = "a" + code.toChar() + "b"
            assertEquals("U+" + Integer.toHexString(code), JsonPrimitive(s).toString(), text(s))
        }
    }

    @Test
    fun utf8WritesUnpairedSurrogatesAsTheReplacementCharacter() {
        val replacement = "efbfbd"
        assertEquals("22" + replacement + "22", Bytes.hex(utf8Of("\uD800")))
        assertEquals("22" + replacement + "22", Bytes.hex(utf8Of("\uDFFF")))
        assertEquals("22" + replacement + replacement + "22", Bytes.hex(utf8Of("\uDD11\uD83D"))) // a pair in the wrong order
        assertEquals("2261" + replacement + "6222", Bytes.hex(utf8Of("a\uD83Db")))
        assertEquals("22f09f949122", Bytes.hex(utf8Of("\uD83D\uDD11")))
        // Everything else is the JDK's UTF-8.
        val random = RandomVaults(7)
        repeat(5_000) {
            val s = random.string(10)
            assertArrayEquals(text(s).toByteArray(Charsets.UTF_8), utf8Of(s))
        }
    }

    @Test
    fun everyKeyIsWrittenInTheOrderOfTheTablesEvenAtItsDefault() {
        assertEquals(
            """{"schema":1,"entries":[],"draft":null,"plainExports":[],"backups":[],"masterPasswordMatches":[],"securitySeenAt":null}""",
            written(Vault()),
        )
        assertEquals(
            """{"schema":1,"entries":[{"id":"i","title":"","username":"","password":"","notes":"","urls":[],"fields":[],""" +
                """"favourite":false,"changePending":false,"checkIgnored":false,"createdAt":-9223372036854775808,""" +
                """"updatedAt":9223372036854775807,"passwordChangedAt":null,"deletedAt":null,"pendingOldPassword":null,""" +
                """"passwordHistory":[]}],"draft":null,"plainExports":[],"backups":[],"masterPasswordMatches":[],"securitySeenAt":null}""",
            written(Vault(entries = listOf(Entry(id = "i", title = "", createdAt = Long.MIN_VALUE, updatedAt = Long.MAX_VALUE)))),
        )
    }

    @Test
    fun keptValuesAreWrittenInTheirCompactForm() {
        val vault = Vault(extras = linkedMapOf("b" to " [ 1.50 , { \"x\" : null } ] ", "a" to "\"\\u0041\""))
        assertTrue(written(vault).endsWith(""","securitySeenAt":null,"b":[1.50,{"x":null}],"a":"A"}"""))
    }

    @Test
    fun randomVaultsRoundTripAndMatchKotlinx() {
        val random = RandomVaults(20_261_007)
        repeat(3_000) { n ->
            val vault = random.vault()
            val bytes = BodyCodec.write(vault)
            val text = String(bytes, Charsets.UTF_8)
            // The same tree printed by kotlinx-serialization gives the same text (§4.3).
            assertEquals("vault $n", text, StrictJson.parse(text, BodyCodec.MAX_DEPTH).toString())
            val read = BodyCodec.read(BodyCodec.pad(bytes))
            assertEquals("vault $n", BodyResult.Opened(vault), read)
            // Equality of maps ignores order; the writer does not.
            val again = (read as BodyResult.Opened).vault
            assertEquals(vault.extras.keys.toList(), again.extras.keys.toList())
            assertArrayEquals("vault $n", bytes, BodyCodec.write(again))
        }
    }

    @Test
    fun randomVaultsRoundTripThroughWholeFiles() {
        val random = RandomVaults(42)
        val codec = codec(FakeRandom(3))
        repeat(300) {
            val vault = random.vault(maxEntries = 30)
            val file = codec.seal(vault, VK, VAULT_ID, VK_ID, 1 + random.random.nextInt(1000).toLong(), SLOT)
            val read = (codec.check(file) as FileCheck.Ok).file
            assertEquals(BodyResult.Opened(vault), codec.open(read, VK))
        }
    }

    @Test
    fun anUnpairedSurrogateIsWrittenAsU_FFFD() {
        val vault = Vault(entries = listOf(Entry(id = "i", title = "a\uD800b", createdAt = 1, updatedAt = 1)))
        val bytes = BodyCodec.write(vault)
        assertTrue(Bytes.hex(bytes).contains("61efbfbd62"))
        val back = (BodyCodec.read(BodyCodec.pad(bytes)) as BodyResult.Opened).vault
        // Not the same vault: storage's write-time check of §4.9 step 4 sees the difference and saves nothing.
        assertEquals("a\uFFFDb", back.entries[0].title)
        // Read and written again, the bytes are stable.
        val random = RandomVaults(11, lone = true)
        repeat(1_000) {
            val first = BodyCodec.write(random.vault())
            val reread = (BodyCodec.read(BodyCodec.pad(first)) as BodyResult.Opened).vault
            assertArrayEquals(first, BodyCodec.write(reread))
        }
    }

    @Test
    fun thePaddingMakesWholeBlocksOfSpaces() {
        fun withNotes(n: Int) = Vault(entries = listOf(Entry(id = "i", title = "t", notes = "x".repeat(n), createdAt = 1, updatedAt = 1)))
        val base = BodyCodec.write(withNotes(0)).size
        val exact = BodyCodec.write(withNotes(4_096 - base))
        assertEquals(4_096, exact.size)
        assertArrayEquals("none when the JSON is already a multiple", exact, BodyCodec.pad(exact))
        val oneMore = BodyCodec.write(withNotes(4_097 - base))
        val padded = BodyCodec.pad(oneMore)
        assertEquals(8_192, padded.size)
        assertTrue(padded.copyOfRange(4_097, 8_192).all { it == 0x20.toByte() })
        assertEquals(4_096, BodyCodec.pad(utf8("{}")).size)
        // Through the container: body_len is the padded size and the tag.
        val codec = codec()
        assertEquals(4_112, (codec.check(codec.seal(withNotes(4_096 - base), VK, VAULT_ID, VK_ID, 1, SLOT)) as FileCheck.Ok).file.header.bodyLen)
        assertEquals(8_208, (codec.check(codec.seal(withNotes(4_097 - base), VK, VAULT_ID, VK_ID, 1, SLOT)) as FileCheck.Ok).file.header.bodyLen)
    }

    @Test
    fun theWriterRefusesWhatHasNoCanonicalForm() {
        val marker = "S3cret-Marker"
        fun refused(vault: Vault) {
            try {
                BodyCodec.write(vault)
                fail("written: $vault")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message, e.message.orEmpty().contains(marker))
            }
        }
        val entry = Entry(id = "i", title = "t", createdAt = 1, updatedAt = 1)
        refused(Vault(schema = 2))
        refused(Vault(extras = mapOf("entries" to "[]")))
        refused(Vault(entries = listOf(entry.copy(extras = mapOf("title" to "\"$marker\"")))))
        for (bad in listOf("{\"$marker\"", "", "1 2", "\"$marker", "{\"a\":1,\"a\":\"$marker\"}", "nul", "'$marker'", "[$marker]")) {
            refused(Vault(extras = mapOf("k" to bad)))
            refused(Vault(entries = listOf(entry.copy(extras = mapOf("k" to bad)))))
        }
        // The depth of R9 where the value stands: 15 more containers at the top, 13 in an entry.
        fun arrays(n: Int) = "[".repeat(n) + "]".repeat(n)
        written(Vault(extras = mapOf("k" to arrays(15))))
        refused(Vault(extras = mapOf("k" to arrays(16))))
        written(Vault(entries = listOf(entry.copy(extras = mapOf("k" to arrays(13))))))
        refused(Vault(entries = listOf(entry.copy(extras = mapOf("k" to arrays(14))))))
    }
}
