package io.github.dilshodbekmx.passwordvault.core.util

import io.github.dilshodbekmx.passwordvault.core.util.StrictJson.Reason
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {
    @Test
    fun anArrayLimitKeepsTheFirstElementsOfItsArrayAndCountsTheRest() {
        // formats.md §5.2: an import keeps at most 10,000 items and only counts the rest.
        val limit = StrictJson.ArrayLimit("items", 2)
        val root = StrictJson.parseObject("""{"a":1,"items":[1,{"b":[2,3]},3,[4],"5"],"z":[1,2,3]}""", 32, limit)
        assertEquals(5, limit.count)
        assertEquals("""[1,{"b":[2,3]}]""", root["items"].toString())
        assertEquals("the other arrays are whole", """[1,2,3]""", root["z"].toString())
        assertEquals("1", root["a"].toString())

        // The same through parseUtf8Object, at the limit and under it; and an empty array.
        assertEquals("[1,2]", StrictJson.parseUtf8Object("""{"items":[1,2]}""".toByteArray(), 32, limit)["items"].toString())
        assertEquals(2, limit.count)
        StrictJson.parseUtf8Object("""{"items":[]}""".toByteArray(), 32, limit)
        assertEquals(0, limit.count)

        // Only the array at the key of the outermost object: a nested one, a value that is no array, or
        // an outermost array are not limited, and count nothing.
        for (text in listOf("""{"x":{"items":[1,2,3]}}""", """{"items":7}""", """[{"items":[1,2,3]}]""", """{"other":[1,2,3]}""")) {
            assertEquals(text, StrictJson.parse(text, 32), StrictJson.parse(text, 32, limit))
            assertEquals(text, 0, limit.count)
        }

        // The text is still checked to its end, the elements that are not kept included.
        val limited = StrictJson.ArrayLimit("items", 1)
        assertEquals(Reason.TRAILING_CHARACTERS, failure { StrictJson.parse("""{"items":[1,2,3]} x""", 32, limited) }.reason)
        assertEquals(Reason.DUPLICATE_KEY, failure { StrictJson.parse("""{"items":[1,{"k":1,"k":2}]}""", 32, limited) }.reason)
        assertEquals(Reason.DUPLICATE_KEY, failure { StrictJson.parse("""{"items":[1,2],"items":[3]}""", 32, limited) }.reason)
        assertEquals(Reason.TOO_DEEP, failure { StrictJson.parse("""{"items":[1,[[[]]]]}""", 3, limited) }.reason)
        assertThrows(IllegalArgumentException::class.java) { StrictJson.ArrayLimit("items", -1) }
    }

    private fun failure(parse: () -> Unit): StrictJson.Failure =
        try {
            parse()
            fail("accepted")
            error("unreachable")
        } catch (f: StrictJson.Failure) {
            f
        }

    private fun failure(text: String, maxDepth: Int = 32): StrictJson.Failure =
        try {
            StrictJson.parse(text, maxDepth)
            fail("accepted: $text")
            error("unreachable")
        } catch (f: StrictJson.Failure) {
            f
        }

    private fun utf8Failure(bytes: ByteArray, maxDepth: Int = 32): StrictJson.Failure =
        try {
            StrictJson.parseUtf8(bytes, maxDepth)
            fail("accepted")
            error("unreachable")
        } catch (f: StrictJson.Failure) {
            f
        }

    private fun assertFails(text: String, reason: Reason, offset: Long, maxDepth: Int = 32) {
        val f = failure(text, maxDepth)
        assertEquals(text, reason, f.reason)
        assertEquals(text, offset, f.offset)
    }

    @Test
    fun readsEveryKindOfValueAsKotlinxDoes() {
        val text = """ { "s" : "a\"b\\c\/d\b\f\n\r\t\u00e9\u0416🔑" , "n" : [ 0 , -0 , 1.50 , -2e-3 , 1E+400 , 123456789012345678901234567890 ] ,
            "t" : true , "f" : false , "z" : null , "o" : { "e" : { } , "a" : [ ] } } """
        val ours = StrictJson.parse(text, 16)
        assertEquals(Json.parseToJsonElement(text), ours)
        val s = (ours as JsonObject)["s"]!!.jsonPrimitive
        assertTrue(s.isString)
        assertEquals("a\"b\\c/d\b\u000C\n\r\téЖ🔑", s.content)
    }

    @Test
    fun keepsTheOrderOfKeysAndTheSpellingOfNumbers() {
        val text = """{"b":1.50,"a":-0,"c":1E+3,"d":[true,false,null],"e":"x","f":0.0}"""
        val value = StrictJson.parseObject(text, 16)
        assertEquals(listOf("b", "a", "c", "d", "e", "f"), value.keys.toList())
        assertEquals(text, value.toString())
        val n = value["b"] as JsonPrimitive
        assertFalse(n.isString)
        assertEquals("1.50", n.content)
    }

    @Test
    fun vectorBReadsBackToTheSameBytes() {
        // architecture.md §4.13.2: the canonical body. Read with StrictJson and written with
        // kotlinx-serialization's toString(), it must come out byte for byte the same.
        val bytes = VECTOR_B_BODY.toByteArray(Charsets.UTF_8)
        assertEquals(1_448, bytes.size)
        assertEquals("610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7", sha256(bytes))
        val body = StrictJson.parseUtf8Object(bytes, maxDepth = 16)
        assertEquals(VECTOR_B_BODY, body.toString())
        // The padding of architecture.md §4.3 is trailing white space, which JSON allows.
        assertEquals(body, StrictJson.parseUtf8(bytes + ByteArray(2_648) { ' '.code.toByte() }, maxDepth = 16))
    }

    @Test
    fun aKeyIsNeverRepeatedInOneObject() {
        assertFails("""{"a":1,"a":2}""", Reason.DUPLICATE_KEY, 7)
        assertFails("""{"a":1,"\u0061":2}""", Reason.DUPLICATE_KEY, 7) // compared after unescaping
        assertFails("""{"x":{"k":1,"y":2,"k":[]}}""", Reason.DUPLICATE_KEY, 18)
        assertFails("""[{"":0,"":0}]""", Reason.DUPLICATE_KEY, 7)
        // The same key in different objects, or a key equal to a value, is fine.
        StrictJson.parse("""[{"a":1},{"a":1},{"b":{"a":"a"}}]""", 4)
    }

    @Test
    fun depthCountsObjectsAndArraysWithTheOuterContainerAtOne() {
        fun nested(depth: Int, open: (Int) -> String, close: (Int) -> String) =
            (0 until depth).joinToString("") { open(it) } + "1" + (depth - 1 downTo 0).joinToString("") { close(it) }
        val mixed = { i: Int -> if (i % 2 == 0) """{"k":""" else "[" }
        val mixedClose = { i: Int -> if (i % 2 == 0) "}" else "]" }
        for (max in listOf(1, 16, 32)) {
            StrictJson.parse(nested(max, mixed, mixedClose), max)
            StrictJson.parse(nested(max, { "[" }, { "]" }), max)
            val tooDeep = nested(max + 1, { "[" }, { "]" })
            assertFails(tooDeep, Reason.TOO_DEEP, max.toLong(), max)
            val tooDeepMixed = nested(max + 1, mixed, mixedClose)
            assertEquals(Reason.TOO_DEEP, failure(tooDeepMixed, max).reason)
        }
        // An empty container counts as a level too.
        StrictJson.parse("[".repeat(15) + "{}" + "]".repeat(15), 16)
        assertFails("[".repeat(16) + "[]" + "]".repeat(16), Reason.TOO_DEEP, 16, 16)
        // A scalar has no depth.
        StrictJson.parse("\"text\"", 1)
        assertThrows(IllegalArgumentException::class.java) { StrictJson.parse("1", 0) }
    }

    @Test
    fun theGrammarIsRfc8259Exactly() {
        val refused = listOf(
            Triple("", Reason.END_OF_INPUT, 0),
            Triple("  \n", Reason.END_OF_INPUT, 3),
            Triple("{", Reason.END_OF_INPUT, 1),
            Triple("{\"a\"", Reason.END_OF_INPUT, 4),
            Triple("{\"a\":", Reason.END_OF_INPUT, 5),
            Triple("[1", Reason.END_OF_INPUT, 2),
            Triple("\"abc", Reason.END_OF_INPUT, 4),
            Triple("\"ab\\", Reason.END_OF_INPUT, 4),
            Triple("[1,]", Reason.UNEXPECTED_CHARACTER, 3),
            Triple("{\"a\":1,}", Reason.UNEXPECTED_CHARACTER, 7),
            Triple("[,1]", Reason.UNEXPECTED_CHARACTER, 1),
            Triple("[1 2]", Reason.UNEXPECTED_CHARACTER, 3),
            Triple("{\"a\" 1}", Reason.UNEXPECTED_CHARACTER, 5),
            Triple("{\"a\":}", Reason.UNEXPECTED_CHARACTER, 5),
            Triple("{a:1}", Reason.UNEXPECTED_CHARACTER, 1),
            Triple("{1:1}", Reason.UNEXPECTED_CHARACTER, 1),
            Triple("['a']", Reason.UNEXPECTED_CHARACTER, 1),
            Triple("tru", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("nul", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("True", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("NaN", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("Infinity", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("+1", Reason.UNEXPECTED_CHARACTER, 0),
            Triple(".5", Reason.UNEXPECTED_CHARACTER, 0),
            Triple("[01]", Reason.BAD_NUMBER, 1),
            Triple("-", Reason.BAD_NUMBER, 0),
            Triple("-a", Reason.BAD_NUMBER, 0),
            Triple("1.", Reason.BAD_NUMBER, 0),
            Triple("1.e5", Reason.BAD_NUMBER, 0),
            Triple("1e", Reason.BAD_NUMBER, 0),
            Triple("1e+", Reason.BAD_NUMBER, 0),
            Triple("[-01]", Reason.BAD_NUMBER, 1),
            Triple("\"a\tb\"", Reason.CONTROL_CHARACTER, 2),
            Triple("\"a\u0000\"", Reason.CONTROL_CHARACTER, 2),
            Triple("\"\\x\"", Reason.BAD_ESCAPE, 1),
            Triple("\"\\'\"", Reason.BAD_ESCAPE, 1),
            Triple("\"\\u12G4\"", Reason.BAD_ESCAPE, 1),
            Triple("\"\\u12\"", Reason.BAD_ESCAPE, 1),
            Triple("\"\\u١٢٣٤\"", Reason.BAD_ESCAPE, 1), // Arabic-Indic digits are not hex digits
            Triple("1 2", Reason.TRAILING_CHARACTERS, 2),
            Triple("{} x", Reason.TRAILING_CHARACTERS, 3),
            Triple("truex", Reason.TRAILING_CHARACTERS, 4),
            Triple("\uFEFF{}", Reason.BYTE_ORDER_MARK, 0),
            Triple(" \uFEFF{}", Reason.UNEXPECTED_CHARACTER, 1),
            Triple("{\"a\":1}\u0000", Reason.TRAILING_CHARACTERS, 7),
        )
        for ((text, reason, offset) in refused) assertFails(text, reason, offset.toLong())
        val accepted = listOf("0", "-0", "-0.0e-0", "1E400", "[]", "{}", " [ ] ", "\"\"", "\"\\u00E9\\u00e9\"", "\"\\/\"", "null", "\"😀\"")
        for (text in accepted) assertEquals(text, Json.parseToJsonElement(text), StrictJson.parse(text, 1))
    }

    @Test
    fun aLoneSurrogateEscapeIsReadAsItIs() {
        // A reader enforces no text rule (architecture.md §4.3): TextHygiene is for input and writers.
        assertEquals("\uD800x\uDC00", StrictJson.parse("\"\\ud800x\\uDC00\"", 1).jsonPrimitive.content)
    }

    @Test
    fun parseObjectRefusesAnyOtherValue() {
        assertEquals(Reason.NOT_AN_OBJECT, StrictJson.assertObjectFailure("[]").reason)
        assertEquals(2L, StrictJson.assertObjectFailure("  1").offset)
        val bytes = " \n\"x\"".toByteArray()
        val f = assertThrows(StrictJson.Failure::class.java) { StrictJson.parseUtf8Object(bytes, 4) }
        assertEquals(Reason.NOT_AN_OBJECT, f.reason)
        assertEquals(2L, f.offset)
    }

    private fun StrictJson.assertObjectFailure(text: String): StrictJson.Failure =
        assertThrows(StrictJson.Failure::class.java) { parseObject(text, 4) }

    @Test
    fun parseUtf8GivesByteOffsets() {
        val text = """{"ключ":"🔑","ключ":2}"""
        val f = utf8Failure(text.toByteArray())
        assertEquals(Reason.DUPLICATE_KEY, f.reason)
        assertEquals(text.substring(0, text.lastIndexOf("\"ключ\"")).toByteArray().size.toLong(), f.offset)
        assertEquals(19L, f.offset) // { "ключ" : "🔑" , = 1 + 10 + 1 + 6 + 1

        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        val prefix = "[\"Ж".toByteArray() // 4 bytes
        val bad = mapOf(
            "a byte that starts nothing" to bytes(0xFF),
            "a continuation byte alone" to bytes(0x80),
            "an overlong slash" to bytes(0xC0, 0xAF),
            "an overlong NUL" to bytes(0xE0, 0x80, 0x80),
            "an encoded surrogate" to bytes(0xED, 0xA0, 0x80),
            "above U+10FFFF" to bytes(0xF4, 0x90, 0x80, 0x80),
            "a lead byte above F4" to bytes(0xF5, 0x80, 0x80, 0x80),
            "a cut sequence" to bytes(0xE2, 0x82),
        )
        for ((what, seq) in bad) {
            val g = utf8Failure(prefix + seq + "\"]".toByteArray())
            assertEquals(what, Reason.MALFORMED_UTF8, g.reason)
            assertEquals(what, prefix.size.toLong(), g.offset)
        }
        val atEnd = utf8Failure(prefix + bytes(0xF0, 0x9F, 0x94))
        assertEquals(Reason.MALFORMED_UTF8, atEnd.reason)
        assertEquals(4L, atEnd.offset)
        assertEquals(Reason.BYTE_ORDER_MARK, utf8Failure(bytes(0xEF, 0xBB, 0xBF) + "{}".toByteArray()).reason)
        // A four-byte character before the problem counts four bytes.
        val g = utf8Failure("[\"🔑\",01]".toByteArray())
        assertEquals(Reason.BAD_NUMBER, g.reason)
        assertEquals(8L, g.offset)
    }

    @Test
    fun theUtf8DecoderAcceptsExactlyWhatTheJdkAccepts() {
        val random = Random(4_13)
        val pieces = intArrayOf('a'.code, 'Z'.code, ' '.code, 0x7E, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF,
            0xE0, 0xE1, 0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xFF)
        var accepted = 0
        repeat(30_000) {
            val inner = ByteArray(random.nextInt(9)) { pieces[random.nextInt(pieces.size)].toByte() }
            val bytes = byteArrayOf('"'.code.toByte()) + inner + byteArrayOf('"'.code.toByte())
            val jdk = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes)
            val output = CharBuffer.allocate(bytes.size * 2)
            val result = jdk.decode(input, output, true)
            if (result.isError) {
                val f = utf8Failure(bytes, 1)
                assertEquals(Reason.MALFORMED_UTF8, f.reason)
                assertEquals(input.position().toLong(), f.offset)
            } else {
                jdk.flush(output)
                output.flip()
                val text = output.toString()
                assertEquals(text.substring(1, text.length - 1), StrictJson.parseUtf8(bytes, 1).jsonPrimitive.content)
                accepted++
            }
        }
        assertTrue("too few valid samples: $accepted", accepted > 1_000)
    }

    @Test
    fun randomDocumentsReadAsKotlinxReadsThem() {
        val random = Random(20261007)
        repeat(3_000) {
            val text = randomDocument(random, 0)
            assertEquals(text, Json.parseToJsonElement(text), StrictJson.parse(text, 8))
            assertEquals(text, Json.parseToJsonElement(text), StrictJson.parseUtf8(text.toByteArray(), 8))
        }
    }

    @Test
    fun noFailureMessageHoldsInputText() {
        // architecture.md §5.6: an exception message never carries input. Every message is the
        // reason's code and the offset, and nothing else; no failure has a cause.
        val secrets = listOf("S3cr3t", "Пароль", "parolʻ", "🔑", "hunter2", "4321")
        val document = """{"title":"Bank S3cr3t","password":"Пароль-parolʻ-🔑","notes":"hunter2\nPIN 4321",""" +
            """"fields":[{"name":"PIN","value":"4321","secret":true}],"n":4321.5,"ok":true,"none":null}"""
        StrictJson.parse(document, 4)
        val random = Random(5_6)
        val inserts = listOf("\"", "{", "}", "[", "]", ",", ":", "\\", "\\u", "\u0001", "\t", "0", "-", "e", ".", "t", "n", "x", "Пароль", "\uFEFF")
        var failures = 0
        val messages = HashSet<String>()
        repeat(20_000) {
            val sb = StringBuilder(document)
            repeat(1 + random.nextInt(3)) {
                when (random.nextInt(4)) {
                    0 -> if (sb.isNotEmpty()) sb.deleteCharAt(random.nextInt(sb.length))
                    1 -> sb.insert(random.nextInt(sb.length + 1), inserts[random.nextInt(inserts.size)])
                    2 -> sb.setLength(random.nextInt(sb.length + 1))
                    else -> sb.insert(random.nextInt(sb.length + 1), "\"title\":\"S3cr3t\",")
                }
            }
            val text = sb.toString()
            for (attempt in 0..1) {
                val f = try {
                    if (attempt == 0) StrictJson.parse(text, 4) else StrictJson.parseUtf8(text.toByteArray(), 4)
                    null
                } catch (e: StrictJson.Failure) {
                    e
                } ?: continue
                failures++
                assertMessageHoldsNoInput(f, secrets)
                messages.add(f.reason.code)
            }
        }
        assertTrue("only $failures failures", failures > 5_000)
        assertTrue("reasons seen: $messages", messages.size >= 6)
        // And the hand-made failures of every reason.
        for (text in listOf("{\"S3cr3t\":1,\"S3cr3t\":2}", "[\"Пароль", "\"S3cr3t\\q\"", "\"S3cr3t\u0001\"", "[0S3cr3t]", "S3cr3t", "\uFEFFS3cr3t", "1 S3cr3t")) {
            assertMessageHoldsNoInput(failure(text), secrets)
        }
        assertMessageHoldsNoInput(utf8Failure("\"Пароль\u0000".toByteArray() + byteArrayOf(0xFF.toByte())), secrets)
    }

    private fun assertMessageHoldsNoInput(f: StrictJson.Failure, secrets: List<String>) {
        val message = f.message!!
        assertEquals(f.reason.code + " at offset " + f.offset, message)
        assertTrue(message, Regex("[a-z0-9-]+ at offset [0-9]+").matches(message))
        assertEquals(StrictJson.Failure::class.java.name + ": " + message, f.toString())
        assertNull(f.cause)
        assertEquals(0, f.suppressed.size)
        for (s in secrets) assertFalse(message, message.contains(s))
    }

    @Test
    fun aStackOverflowBecomesAFailure() {
        // formats-review P3: the reader does not recurse, but a StackOverflowError that happens
        // inside it anyway (a caller deep in its own stack) comes out as a Failure.
        fun down(n: Int): Int = if (n < 0) n else down(n + 1) + 1
        val f = assertThrows(StrictJson.Failure::class.java) { StrictJson.guardStack({ 42 }) { down(0) } }
        assertEquals(Reason.TOO_DEEP, f.reason)
        assertEquals(42L, f.offset)
        assertNull(f.cause)
    }

    @Test
    fun deepNestingIsRefusedFastOnASmallStack() {
        // 10 MB of '[' on a thread with a 256 KB stack: refused at depth 33 without reading the rest.
        val huge = "[".repeat(10 * 1024 * 1024)
        var result: Throwable? = null
        val thread = Thread(null, {
            result = try {
                StrictJson.parse(huge, 32)
                null
            } catch (t: Throwable) {
                t
            }
        }, "small-stack", 256L * 1024)
        thread.start()
        thread.join()
        val f = result as StrictJson.Failure
        assertEquals(Reason.TOO_DEEP, f.reason)
        assertEquals(32L, f.offset)
    }

    @Test
    fun aLargeVaultSizedDocumentIsReadQuickly() {
        val entry = (StrictJson.parse(VECTOR_B_BODY, 16) as JsonObject)["entries"] as JsonArray
        val text = "{\"schema\":1,\"entries\":[" + (1..5_000).joinToString(",") { entry[0].toString() } + "]}"
        val start = System.nanoTime()
        val value = StrictJson.parseUtf8Object(text.toByteArray(), 16)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000, (value["entries"] as JsonArray).size)
        assertTrue("$ms ms for ${text.length} characters", ms < 10_000)
    }

    private fun randomDocument(random: Random, depth: Int): String {
        fun ws() = listOf("", " ", "\n", "\t", "\r\n ")[random.nextInt(5)]
        fun string(): String {
            val sb = StringBuilder("\"")
            repeat(random.nextInt(6)) {
                sb.append(
                    when (random.nextInt(9)) {
                        0 -> "\\\""
                        1 -> "\\\\"
                        2 -> "\\n"
                        3 -> "\\u%04x".format(java.util.Locale.ROOT, random.nextInt(0x10000))
                        4 -> "Ж"
                        5 -> "🔑"
                        6 -> "oʻ"
                        7 -> "\\/"
                        else -> ('a' + random.nextInt(26)).toString()
                    },
                )
            }
            return sb.append('"').toString()
        }
        fun number(): String {
            val sb = StringBuilder()
            if (random.nextBoolean()) sb.append('-')
            sb.append(if (random.nextInt(4) == 0) "0" else (1 + random.nextInt(9)).toString() + random.nextInt(1_000_000))
            if (random.nextBoolean()) sb.append('.').append(random.nextInt(1000))
            if (random.nextInt(3) == 0) sb.append(listOf("e", "E")[random.nextInt(2)]).append(listOf("", "+", "-")[random.nextInt(3)]).append(random.nextInt(400))
            return sb.toString()
        }
        val kind = if (depth >= 5) random.nextInt(5) else random.nextInt(7)
        return ws() + when (kind) {
            0 -> string()
            1 -> number()
            2 -> listOf("true", "false", "null")[random.nextInt(3)]
            3, 4 -> number()
            5 -> (0 until random.nextInt(4)).joinToString(",", "[", "]") { randomDocument(random, depth + 1) }
            else -> {
                // Keys distinct after unescaping: "a" and "\u0061" are the same key.
                val keys = LinkedHashMap<String, String>()
                repeat(random.nextInt(4)) { string().let { k -> keys.putIfAbsent(Json.parseToJsonElement(k).jsonPrimitive.content, k) } }
                keys.values.joinToString(",", "{", "}") { ws() + it + ws() + ":" + randomDocument(random, depth + 1) }
            }
        } + ws()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private companion object {
        /** architecture.md §4.13.2, from docs/design/vectors/body-b.json (1,448 bytes, SHA-256 610a407a…). */
        const val VECTOR_B_BODY =
            """{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}"""
    }
}
