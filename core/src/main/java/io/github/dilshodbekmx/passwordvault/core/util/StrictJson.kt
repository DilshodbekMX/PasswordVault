package io.github.dilshodbekmx.passwordvault.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * A strict JSON reader (RFC 8259) for the vault body, the lock records and imported files
 * (architecture.md §2.1, §4.4 R9, §5.6; formats.md §5.2, §8.2; formats-review P3).
 *
 * - **No key twice** in one object, compared after unescaping (`{"a":1,"a":2}` is refused).
 * - **Nesting at most `maxDepth` deep**, objects and arrays both counted, the outermost container
 *   at depth 1: the vault body allows 16 (architecture.md §4.4 R9), an import 32 (formats.md §5.2).
 *   The reader is iterative, so depth costs no stack; a `StackOverflowError` is caught as well.
 * - **Exactly the grammar of RFC 8259**: literals only `true`, `false` and `null`; numbers only
 *   `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`; no control character inside a string; only the
 *   escapes of RFC 8259 §7; no trailing comma; nothing but white space after the value. A byte-order
 *   mark is refused (`BYTE_ORDER_MARK`): a caller that accepts one removes it first.
 * - **A failure is one exception, [Failure]**, with an offset and a [Reason] from a fixed list. Its
 *   message is made of the reason's code and the offset only, never of input text, and it carries
 *   no cause (architecture.md §5.6). Offsets count characters for [parse] and bytes for [parseUtf8].
 *
 * The result is a kotlinx-serialization tree, read in the order of the text: object keys keep their
 * order, and a number keeps its spelling (`1.50` stays `1.50`, so `JsonElement.toString()` writes back
 * exactly the tokens that were read, architecture.md §4.3). A `\u` escape of a lone surrogate is
 * read as it is: the reader enforces no text rule (`TextHygiene` is for input, import and writers).
 */
object StrictJson {
    /** Why a text was refused. [code] is a constant for logs and `ParseError`s; it never holds input. */
    enum class Reason(val code: String) {
        /** The text ends inside a value, or holds no value at all. */
        END_OF_INPUT("end-of-input"),

        /** A character that cannot start or continue a value here. */
        UNEXPECTED_CHARACTER("unexpected-character"),

        /** Something other than white space after the value. */
        TRAILING_CHARACTERS("trailing-characters"),

        /** U+FEFF at the start (RFC 8259 §8.1: a JSON text has no byte-order mark). */
        BYTE_ORDER_MARK("byte-order-mark"),

        /** [parseUtf8]: the bytes are not strict UTF-8. */
        MALFORMED_UTF8("malformed-utf8"),

        /** A number outside RFC 8259's grammar: a leading zero, a missing digit, a lone minus sign. */
        BAD_NUMBER("bad-number"),

        /** A backslash escape that RFC 8259 §7 does not define, or `\u` without four hex digits. */
        BAD_ESCAPE("bad-escape"),

        /** U+0000 to U+001F inside a string, where RFC 8259 requires an escape. */
        CONTROL_CHARACTER("control-character"),

        /** A key that the same object already has. */
        DUPLICATE_KEY("duplicate-key"),

        /** A container deeper than the depth allowed, or a stack that ran out. */
        TOO_DEEP("too-deep"),

        /** [parseObject], [parseUtf8Object]: the value is not an object. */
        NOT_AN_OBJECT("not-an-object"),
    }

    /** The one exception of this reader: where and why, never what (architecture.md §5.6). */
    class Failure(val offset: Long, val reason: Reason) : Exception(reason.code + " at offset " + offset)

    /** Reads one JSON value from [text]. Offsets in a [Failure] are character indices. */
    fun parse(text: String, maxDepth: Int): JsonElement {
        require(maxDepth >= 1) { "maxDepth must be at least 1" }
        val parser = Parser(text, maxDepth)
        return guardStack({ parser.pos }) { parser.run() }
    }

    /** As [parse], and the value must be an object. */
    fun parseObject(text: String, maxDepth: Int): JsonObject =
        parse(text, maxDepth) as? JsonObject ?: throw Failure(firstNonSpace(text).toLong(), Reason.NOT_AN_OBJECT)

    /**
     * Reads one JSON value from strict UTF-8 [bytes]: a malformed sequence, an overlong form, an
     * encoded surrogate or a code point above U+10FFFF is `MALFORMED_UTF8` at the offset of the
     * sequence's first byte, and a byte-order mark is refused. Offsets in a [Failure] are byte
     * offsets into [bytes].
     */
    fun parseUtf8(bytes: ByteArray, maxDepth: Int): JsonElement {
        require(maxDepth >= 1) { "maxDepth must be at least 1" }
        val text = decodeStrictUtf8(bytes)
        try {
            return parse(text, maxDepth)
        } catch (f: Failure) {
            throw Failure(utf8Length(text, f.offset.toInt()), f.reason)
        }
    }

    /** As [parseUtf8], and the value must be an object. */
    fun parseUtf8Object(bytes: ByteArray, maxDepth: Int): JsonObject {
        val value = parseUtf8(bytes, maxDepth)
        if (value is JsonObject) return value
        var at = 0
        while (at < bytes.size && isJsonSpace(bytes[at].toInt().toChar())) at++
        throw Failure(at.toLong(), Reason.NOT_AN_OBJECT)
    }

    /**
     * Runs [block] and turns a `StackOverflowError` into a [Failure] (`TOO_DEEP`) at [position]
     * (formats-review P3). The reader itself does not recurse; this is for a caller that is already
     * deep in its own stack.
     */
    internal fun <T> guardStack(position: () -> Int, block: () -> T): T =
        try {
            block()
        } catch (e: StackOverflowError) {
            throw Failure(position().toLong(), Reason.TOO_DEEP)
        }

    private fun isJsonSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** An ASCII hex digit's value, or -1 (`Character.digit` would also take other scripts' digits). */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun firstNonSpace(s: String): Int {
        var i = 0
        while (i < s.length && isJsonSpace(s[i])) i++
        return i
    }

    /** The number of UTF-8 bytes of the first [chars] characters of [s] (a surrogate pair counts 4). */
    private fun utf8Length(s: String, chars: Int): Long {
        var bytes = 0L
        var i = 0
        val end = minOf(chars, s.length)
        while (i < end) {
            val c = s[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** Strict UTF-8 (the Unicode Standard's well-formed sequences only), or `MALFORMED_UTF8`. */
    private fun decodeStrictUtf8(bytes: ByteArray): String {
        val out = CharArray(bytes.size) // UTF-16 never needs more units than UTF-8 has bytes
        var o = 0
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                out[o++] = b0.toChar()
                i++
                continue
            }
            val need: Int
            var lower = 0x80
            var upper = 0xBF
            var cp: Int
            when (b0) {
                in 0xC2..0xDF -> { need = 1; cp = b0 and 0x1F }
                0xE0 -> { need = 2; lower = 0xA0; cp = b0 and 0x0F }
                in 0xE1..0xEC, 0xEE, 0xEF -> { need = 2; cp = b0 and 0x0F }
                0xED -> { need = 2; upper = 0x9F; cp = b0 and 0x0F }
                0xF0 -> { need = 3; lower = 0x90; cp = b0 and 0x07 }
                in 0xF1..0xF3 -> { need = 3; cp = b0 and 0x07 }
                0xF4 -> { need = 3; upper = 0x8F; cp = b0 and 0x07 }
                else -> throw Failure(i.toLong(), Reason.MALFORMED_UTF8)
            }
            for (k in 1..need) {
                if (i + k >= n) throw Failure(i.toLong(), Reason.MALFORMED_UTF8)
                val b = bytes[i + k].toInt() and 0xFF
                if (b < lower || b > upper) throw Failure(i.toLong(), Reason.MALFORMED_UTF8)
                lower = 0x80
                upper = 0xBF
                cp = (cp shl 6) or (b and 0x3F)
            }
            if (cp >= 0x10000) {
                out[o++] = Character.highSurrogate(cp)
                out[o++] = Character.lowSurrogate(cp)
            } else {
                out[o++] = cp.toChar()
            }
            i += need + 1
        }
        return String(out, 0, o)
    }

    private val TRUE = JsonPrimitive(true)
    private val FALSE = JsonPrimitive(false)
    private val EMPTY_OBJECT = JsonObject(emptyMap())
    private val EMPTY_ARRAY = JsonArray(emptyList())

    /** One container being read: the members so far and, in an object, the key of the next value. */
    private class Frame(val members: LinkedHashMap<String, JsonElement>?, val items: ArrayList<JsonElement>?) {
        var key: String = ""
    }

    private class Parser(private val s: String, private val maxDepth: Int) {
        var pos = 0

        private fun fail(reason: Reason, at: Int = pos): Nothing = throw Failure(at.toLong(), reason)

        private fun skipSpace() {
            while (pos < s.length && isJsonSpace(s[pos])) pos++
        }

        fun run(): JsonElement {
            if (s.isNotEmpty() && s[0] == '﻿') fail(Reason.BYTE_ORDER_MARK, 0)
            val value = readValue()
            skipSpace()
            if (pos < s.length) fail(Reason.TRAILING_CHARACTERS)
            return value
        }

        /** One value, containers included, with an explicit stack instead of recursion. */
        private fun readValue(): JsonElement {
            val stack = ArrayList<Frame>()
            while (true) {
                skipSpace()
                if (pos >= s.length) fail(Reason.END_OF_INPUT)
                var value: JsonElement
                when (val c = s[pos]) {
                    '{' -> {
                        if (stack.size >= maxDepth) fail(Reason.TOO_DEEP)
                        pos++
                        skipSpace()
                        if (pos < s.length && s[pos] == '}') {
                            pos++
                            value = EMPTY_OBJECT
                        } else {
                            val frame = Frame(LinkedHashMap(), null)
                            stack.add(frame)
                            frame.key = readKey(frame.members!!)
                            continue
                        }
                    }
                    '[' -> {
                        if (stack.size >= maxDepth) fail(Reason.TOO_DEEP)
                        pos++
                        skipSpace()
                        if (pos < s.length && s[pos] == ']') {
                            pos++
                            value = EMPTY_ARRAY
                        } else {
                            stack.add(Frame(null, ArrayList()))
                            continue
                        }
                    }
                    '"' -> value = JsonPrimitive(readString())
                    't' -> value = literal("true", TRUE)
                    'f' -> value = literal("false", FALSE)
                    'n' -> value = literal("null", JsonNull)
                    else -> value = if (c == '-' || c in '0'..'9') readNumber() else fail(Reason.UNEXPECTED_CHARACTER)
                }
                // A value is complete: store it, then close every container that ends after it.
                while (true) {
                    val top = stack.lastOrNull() ?: return value
                    val members = top.members
                    if (members != null) members[top.key] = value else top.items!!.add(value)
                    skipSpace()
                    if (pos >= s.length) fail(Reason.END_OF_INPUT)
                    val c = s[pos]
                    if (c == ',') {
                        pos++
                        if (members != null) {
                            skipSpace()
                            top.key = readKey(members)
                        }
                        break
                    }
                    if (c != (if (members != null) '}' else ']')) fail(Reason.UNEXPECTED_CHARACTER)
                    pos++
                    stack.removeAt(stack.size - 1)
                    value = if (members != null) JsonObject(members) else JsonArray(top.items!!)
                }
            }
        }

        /** At a key: reads it and the colon after it, and refuses a key that [members] already has. */
        private fun readKey(members: Map<String, JsonElement>): String {
            if (pos >= s.length) fail(Reason.END_OF_INPUT)
            if (s[pos] != '"') fail(Reason.UNEXPECTED_CHARACTER)
            val at = pos
            val key = readString()
            if (members.containsKey(key)) fail(Reason.DUPLICATE_KEY, at)
            skipSpace()
            if (pos >= s.length) fail(Reason.END_OF_INPUT)
            if (s[pos] != ':') fail(Reason.UNEXPECTED_CHARACTER)
            pos++
            return key
        }

        private fun literal(word: String, value: JsonPrimitive): JsonPrimitive {
            if (!s.startsWith(word, pos)) fail(Reason.UNEXPECTED_CHARACTER)
            pos += word.length
            return value
        }

        private fun isDigit(i: Int) = i < s.length && s[i] in '0'..'9'

        private fun readNumber(): JsonPrimitive {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) fail(Reason.BAD_NUMBER, start)
            when (s[pos]) {
                '0' -> {
                    pos++
                    if (isDigit(pos)) fail(Reason.BAD_NUMBER, start)
                }
                in '1'..'9' -> while (isDigit(pos)) pos++
                else -> fail(Reason.BAD_NUMBER, start)
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (!isDigit(pos)) fail(Reason.BAD_NUMBER, start)
                while (isDigit(pos)) pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (!isDigit(pos)) fail(Reason.BAD_NUMBER, start)
                while (isDigit(pos)) pos++
            }
            return JsonUnquotedLiteral(s.substring(start, pos))
        }

        /** At the opening quote: the string's value; the position is then after the closing quote. */
        private fun readString(): String {
            val start = pos + 1
            var i = start
            while (i < s.length) { // the common case: no escape
                val c = s[i]
                if (c == '"') {
                    pos = i + 1
                    return s.substring(start, i)
                }
                if (c == '\\') break
                if (c < ' ') fail(Reason.CONTROL_CHARACTER, i)
                i++
            }
            val sb = StringBuilder(i - start + 16)
            sb.append(s, start, i)
            while (true) {
                if (i >= s.length) fail(Reason.END_OF_INPUT, s.length)
                val c = s[i]
                when {
                    c == '"' -> {
                        pos = i + 1
                        return sb.toString()
                    }
                    c == '\\' -> {
                        if (i + 1 >= s.length) fail(Reason.END_OF_INPUT, s.length)
                        when (s[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                var code = 0
                                for (k in 2..5) {
                                    if (i + k >= s.length) fail(Reason.END_OF_INPUT, s.length)
                                    val d = hexValue(s[i + k])
                                    if (d < 0) fail(Reason.BAD_ESCAPE, i)
                                    code = (code shl 4) or d
                                }
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                            else -> fail(Reason.BAD_ESCAPE, i)
                        }
                        i += 2
                    }
                    c < ' ' -> fail(Reason.CONTROL_CHARACTER, i)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
    }
}
