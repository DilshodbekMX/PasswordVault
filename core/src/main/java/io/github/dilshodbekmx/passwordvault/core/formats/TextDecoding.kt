package io.github.dilshodbekmx.passwordvault.core.formats

/**
 * Turns the bytes of an imported file into text without ever switching a file's encoding silently
 * (formats.md §8.2, formats-review P2 and P12).
 *
 * The choice, in this order:
 * 1. **A byte-order mark** names the encoding: UTF-8 (EF BB BF), UTF-16LE (FF FE), UTF-16BE (FE FF).
 *    The rest is decoded in that encoding even when some of it is not valid there: such bytes are
 *    replaced, counted and their lines listed, never decoded leniently in silence.
 * 2. **No mark, but NUL bytes at one parity only** (at least two of them, in at least 1 of 16
 *    characters of the first 8 KB): UTF-16 without a mark, little-endian when the NULs are the odd
 *    bytes. A text in UTF-8 or Windows-1251 has no NUL byte at all.
 * 3. **UTF-8** when every byte is valid UTF-8, and also when some bytes are not but valid multi-byte
 *    UTF-8 sequences occur: the bad bytes then become U+FFFD and their lines are listed.
 * 4. **Windows-1251** (what Russian-language Excel writes for "CSV (comma delimited)") only when
 *    bytes that are not UTF-8 occur and no valid multi-byte UTF-8 sequence occurs at all.
 *
 * [decode] with an encoding is the preview's "Text looks wrong? Read as …": the bytes are decoded as
 * told, and a mark of that encoding (or EF BB BF before Windows-1251) is dropped.
 *
 * Every leading U+FEFF is removed from the text and counted in `byteOrderMarks` (formats-review
 * P11: a file can start with two). Every decoder here reports: each run of bytes that cannot be
 * decoded becomes one U+FFFD and is counted in `replacedBytes` (UTF-8 by the Unicode Standard's
 * "maximal subpart" rule, as WHATWG decoders do). The decoders are written out here, so that the
 * result does not depend on the platform's charsets, which differ between the JVM and Android.
 *
 * JSON must be strict UTF-8 (formats.md §5.2): `isStrictUtf8` says whether the text qualifies.
 */
object TextDecoding {
    enum class Encoding { UTF_8, UTF_16LE, UTF_16BE, WINDOWS_1251 }

    /** Why [Decoded.encoding] was chosen; the preview says it (formats.md §8.2). */
    enum class Why {
        /** A byte-order mark named it. */
        BYTE_ORDER_MARK,

        /** No mark; NUL bytes at one parity only: UTF-16. */
        NUL_BYTES,

        /** Every byte is valid UTF-8 (plain ASCII included). */
        VALID_UTF8,

        /** Some bytes are not UTF-8, but valid multi-byte UTF-8 sequences occur: UTF-8. */
        UTF8_SEQUENCES,

        /** Bytes that are not UTF-8 and no valid multi-byte UTF-8 sequence: Windows-1251. */
        NO_UTF8_SEQUENCES,

        /** The caller chose the encoding ("Read as …"). */
        CHOSEN,
    }

    /** At most this many lines are listed in [Decoded.replacedLines]; `replacedLineCount` counts them all. */
    const val MAX_LISTED_LINES = 1_000

    /** How many bytes the UTF-16 test of rule 2 looks at. */
    private const val SNIFF_BYTES = 8_192

    class Decoded(
        /** The text, without any leading U+FEFF. */
        val text: String,
        val encoding: Encoding,
        val why: Why,
        /** Leading byte-order marks removed: the encoding's own mark and every U+FEFF after it. */
        val byteOrderMarks: Int,
        /** Bytes that could not be decoded; each run of them is one U+FFFD in [text]. */
        val replacedBytes: Int,
        /**
         * The lines of [text] (1-based; CRLF, LF and a lone CR each end a line, as `Csv` counts
         * them) that hold a U+FFFD put there for bad bytes, ascending, at most [MAX_LISTED_LINES].
         */
        val replacedLines: List<Int>,
        /** The number of such lines, all of them. */
        val replacedLineCount: Int,
        /** The offset in the input of the first byte that could not be decoded, or -1. */
        val firstReplacedByte: Long,
    ) {
        /** Valid UTF-8 throughout: what a JSON file must be (formats.md §5.2). */
        val isStrictUtf8: Boolean get() = encoding == Encoding.UTF_8 && replacedBytes == 0
    }

    /** Chooses the encoding as the rules above say, and decodes. */
    fun decode(bytes: ByteArray): Decoded {
        when {
            startsWith(bytes, UTF8_BOM) -> return utf8(bytes, 3, 1, Why.BYTE_ORDER_MARK).decoded
            startsWith(bytes, UTF16LE_BOM) -> return utf16(bytes, 2, 1, Why.BYTE_ORDER_MARK, littleEndian = true)
            startsWith(bytes, UTF16BE_BOM) -> return utf16(bytes, 2, 1, Why.BYTE_ORDER_MARK, littleEndian = false)
        }
        utf16WithoutMark(bytes)?.let { littleEndian -> return utf16(bytes, 0, 0, Why.NUL_BYTES, littleEndian) }
        val utf8 = utf8(bytes, 0, 0, Why.VALID_UTF8)
        return when {
            utf8.decoded.replacedBytes == 0 -> utf8.decoded
            utf8.sequences > 0 -> utf8.decoded.because(Why.UTF8_SEQUENCES)
            else -> windows1251(bytes, 0, 0, Why.NO_UTF8_SEQUENCES)
        }
    }

    /** Decodes [bytes] as [encoding], whatever they look like ("Text looks wrong? Read as …"). */
    fun decode(bytes: ByteArray, encoding: Encoding): Decoded = when (encoding) {
        Encoding.UTF_8 -> {
            val mark = if (startsWith(bytes, UTF8_BOM)) 3 else 0
            utf8(bytes, mark, if (mark > 0) 1 else 0, Why.CHOSEN).decoded
        }
        Encoding.UTF_16LE -> {
            val mark = if (startsWith(bytes, UTF16LE_BOM)) 2 else 0
            utf16(bytes, mark, if (mark > 0) 1 else 0, Why.CHOSEN, littleEndian = true)
        }
        Encoding.UTF_16BE -> {
            val mark = if (startsWith(bytes, UTF16BE_BOM)) 2 else 0
            utf16(bytes, mark, if (mark > 0) 1 else 0, Why.CHOSEN, littleEndian = false)
        }
        Encoding.WINDOWS_1251 -> {
            // A UTF-8 mark in front of a Windows-1251 body is a known shape (formats-review P12).
            val mark = if (startsWith(bytes, UTF8_BOM)) 3 else 0
            windows1251(bytes, mark, if (mark > 0) 1 else 0, Why.CHOSEN)
        }
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean {
        if (b.size < prefix.size) return false
        for (i in prefix.indices) if (b[i] != prefix[i]) return false
        return true
    }

    private fun Decoded.because(why: Why) =
        Decoded(text, encoding, why, byteOrderMarks, replacedBytes, replacedLines, replacedLineCount, firstReplacedByte)

    /** Rule 2: true for UTF-16LE, false for UTF-16BE, null when the bytes do not look like UTF-16. */
    private fun utf16WithoutMark(bytes: ByteArray): Boolean? {
        val n = minOf(bytes.size, SNIFF_BYTES) and 1.inv()
        val pairs = n / 2
        var even = 0
        var odd = 0
        for (i in 0 until n) {
            if (bytes[i].toInt() == 0) {
                if (i and 1 == 0) even++ else odd++
            }
        }
        fun clear(nuls: Int, other: Int) = nuls >= 2 && nuls * 16 >= pairs && other * 16 <= nuls
        return when {
            clear(odd, even) -> true
            clear(even, odd) -> false
            else -> null
        }
    }

    /**
     * Builds the text: drops leading U+FEFF, counts lines (CRLF, LF and CR each end one) and
     * records where a U+FFFD stands for bad bytes.
     */
    private class TextSink(capacity: Int, private var marks: Int) {
        private val sb = StringBuilder(capacity)
        private var line = 1
        private var lastWasCr = false
        private var replacedBytes = 0
        private var firstReplaced = -1L
        private val lines = ArrayList<Int>()
        private var lineCount = 0
        private var lastLineCounted = 0

        fun char(c: Char) {
            if (c == '﻿' && sb.isEmpty()) {
                marks++
                return
            }
            sb.append(c)
            when (c) {
                '\r' -> {
                    line++
                    lastWasCr = true
                }
                '\n' -> {
                    if (!lastWasCr) line++
                    lastWasCr = false
                }
                else -> lastWasCr = false
            }
        }

        fun codePoint(cp: Int) {
            if (cp >= 0x10000) {
                sb.append(Character.highSurrogate(cp)).append(Character.lowSurrogate(cp))
                lastWasCr = false
            } else {
                char(cp.toChar())
            }
        }

        /** [count] bytes at [offset] could not be decoded: one U+FFFD. */
        fun replaced(offset: Int, count: Int) {
            sb.append('�')
            lastWasCr = false
            replacedBytes += count
            if (firstReplaced < 0) firstReplaced = offset.toLong()
            if (line != lastLineCounted) {
                lastLineCounted = line
                lineCount++
                if (lines.size < MAX_LISTED_LINES) lines.add(line)
            }
        }

        fun result(encoding: Encoding, why: Why) =
            Decoded(sb.toString(), encoding, why, marks, replacedBytes, lines, lineCount, firstReplaced)
    }

    private class Utf8Result(val decoded: Decoded, val sequences: Int)

    /** UTF-8 from [start], also counting the valid multi-byte sequences (rule 3). */
    private fun utf8(bytes: ByteArray, start: Int, marks: Int, why: Why): Utf8Result {
        val out = TextSink(bytes.size - start, marks)
        var sequences = 0
        var i = start
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                out.char(b0.toChar())
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
                else -> {
                    out.replaced(i, 1)
                    i++
                    continue
                }
            }
            // The maximal subpart: the lead byte and every continuation byte that still fits.
            var j = i + 1
            var complete = true
            for (k in 1..need) {
                val b = if (j < n) bytes[j].toInt() and 0xFF else -1
                if (b < lower || b > upper) {
                    complete = false
                    break
                }
                lower = 0x80
                upper = 0xBF
                cp = (cp shl 6) or (b and 0x3F)
                j++
            }
            if (complete) {
                out.codePoint(cp)
                sequences++
            } else {
                out.replaced(i, j - i)
            }
            i = j
        }
        return Utf8Result(out.result(Encoding.UTF_8, why), sequences)
    }

    private fun utf16(bytes: ByteArray, start: Int, marks: Int, why: Why, littleEndian: Boolean): Decoded {
        val out = TextSink((bytes.size - start) / 2 + 1, marks)
        fun unit(at: Int): Char {
            val a = bytes[at].toInt() and 0xFF
            val b = bytes[at + 1].toInt() and 0xFF
            return (if (littleEndian) (b shl 8) or a else (a shl 8) or b).toChar()
        }
        var i = start
        val n = bytes.size
        while (i + 1 < n) {
            val u = unit(i)
            when {
                Character.isHighSurrogate(u) && i + 3 < n && Character.isLowSurrogate(unit(i + 2)) -> {
                    out.codePoint(Character.toCodePoint(u, unit(i + 2)))
                    i += 4
                }
                Character.isSurrogate(u) -> {
                    out.replaced(i, 2)
                    i += 2
                }
                else -> {
                    out.char(u)
                    i += 2
                }
            }
        }
        if (i < n) out.replaced(i, 1) // an odd last byte
        return out.result(if (littleEndian) Encoding.UTF_16LE else Encoding.UTF_16BE, why)
    }

    private fun windows1251(bytes: ByteArray, start: Int, marks: Int, why: Why): Decoded {
        val out = TextSink(bytes.size - start, marks)
        for (i in start until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> out.char(b.toChar())
                b >= 0xC0 -> out.char((0x0410 + b - 0xC0).toChar()) // А to я
                else -> {
                    val c = CP1251_80_BF[b - 0x80]
                    if (c == '�') out.replaced(i, 1) else out.char(c)
                }
            }
        }
        return out.result(Encoding.WINDOWS_1251, why)
    }

    /** Windows-1251 bytes 0x80 to 0xBF (0x98 is unassigned). Bytes 0xC0 to 0xFF are U+0410 to U+044F. */
    private const val CP1251_80_BF =
        "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏ" +
            "ђ‘’“”•–—�™љ›њќћџ" +
            " ЎўЈ¤Ґ¦§Ё©Є«¬­®Ї" +
            "°±Ііґµ¶·ё№є»јЅѕї"
}
