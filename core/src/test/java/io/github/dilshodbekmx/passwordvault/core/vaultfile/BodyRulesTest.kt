package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.FILE_A
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.SLOT
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VAULT_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.VK_ID
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.codec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.retrailer
import io.github.dilshodbekmx.passwordvault.core.vaultfile.Vectors.utf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The reader rules with a key, R8 to R10 of architecture.md §4.4 (`VaultCodec.open`), and what the
 * reader keeps of unknown keys (§4.3). Every body here is sealed into a whole file under its own AAD
 * and goes through `check` and `open`, as storage reads it; each breaks exactly one rule.
 */
class BodyRulesTest {
    private val codec = codec()

    private fun openPlaintext(plaintext: ByteArray): BodyResult {
        val file = codec.sealPadded(plaintext, VK, VAULT_ID, VK_ID, 1, SLOT)
        val ok = codec.check(file) as FileCheck.Ok
        return codec.open(ok.file, VK)
    }

    private fun open(json: ByteArray): BodyResult = openPlaintext(Vectors.padded(json))

    private fun open(json: String): BodyResult = open(utf8(json))

    private fun opened(json: String): Vault {
        val result = open(json)
        if (result !is BodyResult.Opened) fail("expected Opened, got $result")
        return (result as BodyResult.Opened).vault
    }

    private fun damaged(reason: String, json: String, offset: Long? = null) = damaged(reason, utf8(json), offset)

    private fun damaged(reason: String, json: ByteArray, offset: Long? = null) {
        val result = open(json)
        if (result !is BodyResult.Damaged) fail("expected Damaged($reason), got $result for ${String(json, Charsets.UTF_8).take(80)}")
        val error = (result as BodyResult.Damaged).error
        assertEquals(String(json, Charsets.UTF_8).take(80), reason, error.reason)
        if (offset != null) assertEquals(offset, error.offset)
    }

    /** A body with one entry [entry] and the top-level members [more] after `entries`. */
    private fun body(entry: String = ENTRY, more: String = "") = """{"schema":1,"entries":[$entry]$more}"""

    /** The minimal entry: its four required keys. */
    private val minimalEntry = Entry(id = "a", title = "t", createdAt = 1, updatedAt = 2)

    // ------------------------------------------------------------------------------------ R8

    @Test
    fun r8_aWrongKeyDoesNotOpenTheBody() {
        val file = (codec.check(FILE_A) as FileCheck.Ok).file
        assertEquals(BodyResult.TagFailed, codec.open(file, ByteArray(32)))
        val other = VK.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertEquals(BodyResult.TagFailed, codec.open(file, other))
    }

    @Test
    fun r8_aChangedCiphertextDoesNotOpenEvenWithAGoodTrailer() {
        for (at in listOf(12 + 499, 12 + 499 + 12, 2_000, 4_634)) { // the nonce, the body, the tag
            val file = FILE_A.copyOf()
            file[at] = (file[at].toInt() xor 0x01).toByte()
            val read = (codec.check(retrailer(file)) as FileCheck.Ok).file
            assertTrue(read.trailerOk)
            assertEquals("byte $at", BodyResult.TagFailed, codec.open(read, VK))
        }
    }

    @Test
    fun r8_theHeaderIsAuthenticatedThroughTheAad() {
        // A header that is valid but not the one the body was sealed with.
        val text = String(FILE_A, 12, 499, Charsets.US_ASCII).replace("PasswordVault 1.0", "PasswordVault 1.1")
        val file = FILE_A.copyOf()
        utf8(text).copyInto(file, 12)
        val read = (codec.check(retrailer(file)) as FileCheck.Ok).file
        assertEquals("PasswordVault 1.1", read.header.generator)
        assertEquals(BodyResult.TagFailed, codec.open(read, VK))
    }

    // ------------------------------------------------------------------------------------ R9

    @Test
    fun r9_aByteOrderMarkIsRefused() {
        damaged("byte-order-mark", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8(EMPTY), offset = 0)
    }

    @Test
    fun r9_malformedUtf8IsRefused() {
        val start = utf8("""{"schema":1,"entries":[],"x":"""")
        val end = utf8("\"}")
        val sequences = mapOf(
            "a lone continuation byte" to byteArrayOf(0x80.toByte()),
            "a two-byte start without its end" to byteArrayOf(0xC3.toByte(), 0x28),
            "an overlong slash" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            "an encoded surrogate" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "above U+10FFFF" to byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            "a byte that never starts a sequence" to byteArrayOf(0xFF.toByte()),
        )
        for ((what, bad) in sequences) {
            val result = open(start + bad + end)
            assertEquals(what, BodyResult.Damaged(ParseError(start.size.toLong(), "malformed-utf8")), result)
        }
        // A sequence cut short by the padding.
        damaged("malformed-utf8", start + byteArrayOf(0xE2.toByte(), 0x82.toByte()), offset = start.size.toLong())
    }

    @Test
    fun r9_oneObjectAfterTheTrailingSpaces() {
        // A plaintext of spaces only.
        assertEquals(BodyResult.Damaged(ParseError(0, "end-of-input")), openPlaintext(ByteArray(4_096) { 0x20 }))
        damaged("not-an-object", "[]")
        damaged("not-an-object", "\"schema\"")
        damaged("trailing-characters", "$EMPTY$EMPTY")
        damaged("trailing-characters", "$EMPTY x")
        damaged("unexpected-character", """{"schema":1,"entries":[],}""")
        // Padding that is not spaces is something after the object.
        val zeros = utf8(EMPTY).copyOf(4_096)
        assertEquals(BodyResult.Damaged(ParseError(utf8(EMPTY).size.toLong(), "trailing-characters")), openPlaintext(zeros))
        // White space the JSON grammar allows around the object is not a rule of R9.
        assertEquals(Vault(), opened(" \n$EMPTY\n\t"))
    }

    @Test
    fun r9_noKeyTwiceInAnyObject() {
        damaged("duplicate-key", """{"schema":1,"schema":1,"entries":[]}""")
        damaged("duplicate-key", """{"schema":1,"schema":1,"entries":[]}""") // the same key after unescaping
        damaged("duplicate-key", body("""{"id":"a","id":"b","title":"t","createdAt":1,"updatedAt":2}"""))
        damaged("duplicate-key", body(more = ""","x":1,"x":1"""))
        damaged("duplicate-key", body("""{"id":"a","title":"t","createdAt":1,"updatedAt":2,"fields":[{"name":"n","name":"m","value":"v","secret":true}]}"""))
        damaged("duplicate-key", body(more = ""","futureTopKey":{"a":1,"a":2}"""))
    }

    @Test
    fun r9_nestingAtMost16CountedOverObjectsAndArrays() {
        fun arrays(n: Int) = "[".repeat(n) + "]".repeat(n)

        /** [n] containers, objects and arrays in turn, around the number 0. */
        fun mixed(n: Int): String = when {
            n == 0 -> "0"
            n % 2 == 0 -> "[" + mixed(n - 1) + "]"
            else -> "{\"k\":" + mixed(n - 1) + "}"
        }
        // The top object is depth 1; 15 more containers in a top-level value make 16.
        assertEquals(arrays(15), opened(body("", ""","deep":${arrays(15)}""")).extras["deep"])
        damaged("too-deep", body("", ""","deep":${arrays(16)}"""))
        // Inside an entry (depth 3) a value may hold 13 more containers.
        val entry = """{"id":"a","title":"t","createdAt":1,"updatedAt":2,"deep":${arrays(13)}}"""
        assertEquals(arrays(13), opened(body(entry)).entries[0].extras["deep"])
        damaged("too-deep", body("""{"id":"a","title":"t","createdAt":1,"updatedAt":2,"deep":${arrays(14)}}"""))
        // Objects count as much as arrays.
        assertEquals(mixed(15), opened(body("", ""","deep":${mixed(15)}""")).extras["deep"])
        damaged("too-deep", body("", ""","deep":${mixed(16)}"""))
        assertEquals(mixed(14), opened(body("", ""","deep":${mixed(14)}""")).extras["deep"])
        damaged("too-deep", body("", ""","deep":{"k":${mixed(15)}}"""))
    }

    @Test
    fun r9_schemaIsTheIntegerOne() {
        assertEquals(BodyResult.Newer(2), open("""{"schema":2,"entries":[]}"""))
        assertEquals(BodyResult.Newer(Long.MAX_VALUE), open("""{"schema":9223372036854775807}"""))
        // A newer schema is not read further: what it holds may be of other types.
        assertEquals(BodyResult.Newer(3), open("""{"entries":"a newer shape","schema":3,"draft":7}"""))
        damaged("bad-schema", """{"schema":0,"entries":[]}""")
        damaged("bad-schema", """{"schema":-1,"entries":[]}""")
        damaged("missing-key", """{"entries":[]}""")
        for (wrong in listOf("\"1\"", "1.0", "1e0", "true", "null", "[1]", "{}", "12345678901234567890", "9223372036854775808")) {
            damaged("wrong-type", """{"schema":$wrong,"entries":[]}""")
        }
        damaged("bad-number", """{"schema":01,"entries":[]}""") // not JSON at all
    }

    // ------------------------------------------------------------------------------------ R10

    @Test
    fun r10_theRequiredKeysArePresent() {
        damaged("missing-key", """{"schema":1}""")
        for (key in listOf("id", "title", "createdAt", "updatedAt")) {
            damaged("missing-key", body(ENTRY.replace(Regex(""""$key":[^,}]*,?"""), "").replace(",}", "}")))
        }
        // Below the two levels every named key is required (BodyCodec's comment says why).
        val draft = """"draft":{"entryId":null,"savedAt":1,"entry":{"title":"t"}}"""
        assertEquals(Draft(null, 1, DraftEntry(title = "t")), opened(body(more = ",$draft")).draft)
        for (key in listOf("entryId", "savedAt", "entry")) {
            damaged("missing-key", body(more = "," + draft.replace(Regex(""""$key":(null|1|\{"title":"t"}),?"""), "").replace(",}", "}")))
        }
        damaged("missing-key", body(more = ""","draft":{"entryId":null,"savedAt":1,"entry":{"username":"u"}}"""))
        val field = """{"name":"n","value":"v","secret":true}"""
        for (key in listOf("name", "value", "secret")) {
            val broken = field.replace(Regex(""""$key":("[nv]"|true),?"""), "").replace(",}", "}")
            damaged("missing-key", body(ENTRY.replace("}", ""","fields":[$broken]}""")))
        }
        for (broken in listOf("""{"replacedAt":1}""", """{"password":"p"}""")) {
            damaged("missing-key", body(ENTRY.replace("}", ""","passwordHistory":[$broken]}""")))
        }
        for (broken in listOf("""{"name":"n","savedAt":1}""", """{"uri":"u","savedAt":1}""", """{"uri":"u","name":"n"}""")) {
            damaged("missing-key", body(more = ""","plainExports":[$broken]"""))
            damaged("missing-key", body(more = ""","backups":[$broken]"""))
        }
    }

    @Test
    fun r10_missingKeysOfTheTwoLevelsTakeTheirDefaults() {
        assertEquals(Vault(entries = listOf(minimalEntry)), opened(body()))
        val draft = opened(body(more = ""","draft":{"entryId":"a","savedAt":5,"entry":{"title":""}}""")).draft
        assertEquals(Draft("a", 5, DraftEntry()), draft)
    }

    @Test
    fun r10_everyKnownKeyHasItsType() {
        val entryValues = mapOf(
            "id" to listOf("1", "null", "true", "[]"),
            "title" to listOf("1", "null", "{}"),
            "username" to listOf("1", "null", "false"),
            "password" to listOf("1", "null", "[\"p\"]"),
            "notes" to listOf("1", "null"),
            "urls" to listOf("\"https://a\"", "{}", "[1]", "[null]", "null"),
            "fields" to listOf("{}", "[1]", "[[]]", """[{"name":1,"value":"v","secret":true}]""", """[{"name":"n","value":null,"secret":true}]""", """[{"name":"n","value":"v","secret":"true"}]"""),
            "favourite" to listOf("\"true\"", "1", "0", "null"),
            "changePending" to listOf("\"false\"", "1"),
            "checkIgnored" to listOf("0", "null"),
            "createdAt" to listOf("\"1\"", "1.0", "1e3", "1E3", "1.5", "-1.0", "true", "null", "12345678901234567890", "9223372036854775808", "-9223372036854775809"),
            "updatedAt" to listOf("\"2\"", "2.0", "null"),
            "passwordChangedAt" to listOf("\"1\"", "1.5", "true", "[]"),
            "deletedAt" to listOf("1e3", "false"),
            "pendingOldPassword" to listOf("1", "true", "[]"),
            "passwordHistory" to listOf("\"x\"", "[1]", """[{"password":1,"replacedAt":1}]""", """[{"password":"p","replacedAt":"1"}]""", """[{"password":"p","replacedAt":1.0}]"""),
        )
        for ((key, values) in entryValues) {
            for (value in values) {
                val entry = if (ENTRY.contains("\"$key\":")) {
                    ENTRY.replace(Regex(""""$key":[^,}]*"""), "\"$key\":$value")
                } else {
                    ENTRY.replace("}", ""","$key":$value}""")
                }
                damaged("wrong-type", body(entry))
            }
        }
        val topValues = mapOf(
            "entries" to listOf("{}", "\"x\"", "null", "[1]", "[[]]", "[null]"),
            "draft" to listOf("\"x\"", "1", "[]", """{"entryId":1,"savedAt":1,"entry":{"title":"t"}}""", """{"entryId":null,"savedAt":"1","entry":{"title":"t"}}""", """{"entryId":null,"savedAt":1,"entry":[]}""", """{"entryId":null,"savedAt":1,"entry":{"title":"t","favourite":"no"}}"""),
            "plainExports" to listOf("{}", "[1]", """[{"uri":1,"name":"n","savedAt":1}]"""),
            "backups" to listOf("null", """[{"uri":"u","name":"n","savedAt":"1"}]"""),
            "masterPasswordMatches" to listOf("\"x\"", "[1]", "[null]", "{}"),
            "securitySeenAt" to listOf("\"1\"", "1.0", "true", "{}"),
        )
        for ((key, values) in topValues) {
            for (value in values) {
                val json = if (key == "entries") """{"schema":1,"entries":$value}""" else body(more = ""","$key":$value""")
                damaged("wrong-type", json)
            }
        }
    }

    @Test
    fun r10_nullOnlyWhere43AllowsIt() {
        val vault = opened(
            body(
                """{"id":"a","title":"t","createdAt":1,"updatedAt":2,"passwordChangedAt":null,"deletedAt":null,"pendingOldPassword":null}""",
                ""","draft":null,"securitySeenAt":null""",
            ),
        )
        assertEquals(Vault(entries = listOf(minimalEntry)), vault)
        assertEquals(Draft(null, 1, DraftEntry(title = "t")), opened(body(more = ""","draft":{"entryId":null,"savedAt":1,"entry":{"title":"t"}}""")).draft)
    }

    @Test
    fun r10_integersAreThoseOf43() {
        fun createdAt(spelling: String) = opened(body(ENTRY.replace("\"createdAt\":1", "\"createdAt\":$spelling"))).entries[0].createdAt
        assertEquals(0L, createdAt("0"))
        assertEquals(0L, createdAt("-0"))
        assertEquals(-1L, createdAt("-1"))
        assertEquals(1_791_244_800_000L, createdAt("1791244800000"))
        assertEquals(Long.MAX_VALUE, createdAt("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, createdAt("-9223372036854775808"))
    }

    @Test
    fun theReaderEnforcesNoRuleOfInput() {
        // Ids, unique ids, limits and unpaired surrogates are rules for input, import and the
        // write-time check of §4.9 step 4: a stored entry is never refused for them (§4.3).
        val long = "x".repeat(20_000)
        val vault = opened(
            """{"schema":1,"entries":[""" +
                """{"id":"not-an-id","title":"$long","password":"$long","notes":"\ud800 lone","createdAt":-5,"updatedAt":-6},""" +
                """{"id":"not-an-id","title":"","createdAt":1,"updatedAt":1,"urls":["","","","","","","","","","",""]}""" +
                """],"masterPasswordMatches":["nobody"]}""",
        )
        assertEquals(2, vault.entries.size)
        assertEquals(vault.entries[0].id, vault.entries[1].id)
        assertEquals(long, vault.entries[0].title)
        assertEquals("\uD800 lone", vault.entries[0].notes)
        assertEquals(11, vault.entries[1].urls.size)
    }

    // ------------------------------------------------------------------------------- unknown keys

    @Test
    fun unknownKeysAreKeptAtBothLevelsInTheirOrder() {
        val vault = opened(
            """{ "zeta" : [ 1.50 , -0.0 , 1E+2 , 1e-7 ] , "schema":1,"entries":[""" +
                """{"id":"a","later":{"b":[true,false,null]},"title":"t","createdAt":1,"updatedAt":2,"first":"A\/\n"}""" +
                """],"alpha":null,"middle":{"x":{},"y":[]},"id":"a top-level key named like an entry key"}""",
        )
        assertEquals(listOf("zeta", "alpha", "middle", "id"), vault.extras.keys.toList())
        assertEquals("[1.50,-0.0,1E+2,1e-7]", vault.extras["zeta"]) // every number keeps its spelling
        assertEquals("null", vault.extras["alpha"])
        assertEquals("""{"x":{},"y":[]}""", vault.extras["middle"])
        assertEquals("\"a top-level key named like an entry key\"", vault.extras["id"])
        val entry = vault.entries[0]
        assertEquals(listOf("later", "first"), entry.extras.keys.toList())
        assertEquals("""{"b":[true,false,null]}""", entry.extras["later"])
        assertEquals("\"A/\\n\"", entry.extras["first"]) // strings in the canonical escapes
        // Written back after the known keys, in the order they were read.
        val written = String(BodyCodec.write(vault), Charsets.UTF_8)
        assertEquals(
            """{"schema":1,"entries":[{"id":"a","title":"t","username":"","password":"","notes":"","urls":[],"fields":[],""" +
                """"favourite":false,"changePending":false,"checkIgnored":false,"createdAt":1,"updatedAt":2,"passwordChangedAt":null,""" +
                """"deletedAt":null,"pendingOldPassword":null,"passwordHistory":[],"later":{"b":[true,false,null]},"first":"A/\n"}],""" +
                """"draft":null,"plainExports":[],"backups":[],"masterPasswordMatches":[],"securitySeenAt":null,""" +
                """"zeta":[1.50,-0.0,1E+2,1e-7],"alpha":null,"middle":{"x":{},"y":[]},"id":"a top-level key named like an entry key"}""",
            written,
        )
        assertEquals("an entry key named like a top-level key is kept too", "1", opened(body(ENTRY.replace("}", ""","schema":1}"""))).entries[0].extras["schema"])
    }

    @Test
    fun unknownKeysBelowTheTwoLevelsAreDropped() {
        val vault = opened(
            """{"schema":1,"entries":[{"id":"a","title":"t","createdAt":1,"updatedAt":2,""" +
                """"fields":[{"name":"n","value":"v","secret":false,"dropped1":1}],""" +
                """"passwordHistory":[{"password":"p","replacedAt":3,"dropped2":[]}]}],""" +
                """"draft":{"entryId":null,"savedAt":4,"entry":{"title":"d","dropped3":true,"fields":[{"name":"m","value":"w","secret":true,"dropped4":{}}]},"dropped5":"x"},""" +
                """"plainExports":[{"uri":"u1","name":"n1","savedAt":5,"dropped6":null}],""" +
                """"backups":[{"uri":"u2","name":"n2","savedAt":6,"dropped7":0}]}""",
        )
        val expected = Vault(
            entries = listOf(
                minimalEntry.copy(
                    title = "t",
                    fields = listOf(CustomField("n", "v", false)),
                    passwordHistory = listOf(HistoryItem("p", 3)),
                ),
            ),
            draft = Draft(null, 4, DraftEntry(title = "d", fields = listOf(CustomField("m", "w", true)))),
            plainExports = listOf(FileRef("u1", "n1", 5)),
            backups = listOf(FileRef("u2", "n2", 6)),
        )
        assertEquals(expected, vault)
        val written = String(BodyCodec.write(vault), Charsets.UTF_8)
        assertFalse(written, written.contains("dropped"))
    }

    @Test
    fun noParseErrorHoldsInputText() {
        val secret = "S3cret-Marker"
        val bodies = listOf(
            """{"schema":1,"entries":[{"id":"$secret","title":"$secret","createdAt":"$secret","updatedAt":1}]}""",
            """{"schema":1,"entries":[{"id":"$secret","title":"$secret"}]}""",
            """{"schema":"$secret","entries":[]}""",
            """{"schema":1,"entries":[],"$secret":1,"$secret":2}""",
            """{"schema":1,"entries":["$secret"]}""",
            """{"schema":1,"entries":[],"x":"$secret""",
            """{"schema":1,"entries":[],"x":"$secret\q"}""",
            """{"schema":1,"entries":[],"x":$secret}""",
            """{"schema":1,"entries":[],"x":"$secret"}$secret""",
            """[{"$secret":1}]""",
        )
        for (json in bodies) {
            val result = open(json)
            assertTrue("$result", result is BodyResult.Damaged)
            val error = (result as BodyResult.Damaged).error
            assertTrue(error.reason, error.reason in BodyCodec.REASON_CODES)
            assertFalse(result.toString(), result.toString().contains(secret))
            assertFalse(result.toString().contains("S3cret"))
        }
    }

    private companion object {
        const val EMPTY = """{"schema":1,"entries":[]}"""
        const val ENTRY = """{"id":"a","title":"t","createdAt":1,"updatedAt":2}"""
    }
}
