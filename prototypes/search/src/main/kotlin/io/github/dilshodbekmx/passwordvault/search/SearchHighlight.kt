package io.github.dilshodbekmx.passwordvault.search

import io.github.dilshodbekmx.passwordvault.text.UzbekScript

/**
 * The parts of [text] that [query] matched, as ranges of UTF-16 indices into [text], for
 * showing matched text in bold. Each query word is placed where [SearchIndex] scores it
 * (its best match, Russian-keyboard tier included); overlapping ranges are merged.
 *
 * "Ўзбектелеком" with query "o'zbek" gives [0..4] (Ўзбек); "Click Uz" with "click uz" gives
 * [0..4, 6..7]. A character that folds to several letters (ш to sh) is bold as a whole.
 *
 * Cost grows with the square of the text length (each prefix is folded once), so call it
 * for visible titles, user names and hosts, not for notes. Texts longer than
 * [MAX_HIGHLIGHT_LENGTH] get no highlight.
 */
fun matchedRanges(text: String, query: String): List<IntRange> {
    if (text.isEmpty() || text.length > MAX_HIGHLIGHT_LENGTH) return emptyList()
    val preparedQuery = SearchText.prepare(query)
    val key = SearchText.foldPrepared(preparedQuery).key
    if (key.isEmpty()) return emptyList()
    val words = key.split(' ').map { FoldedField.compactOf(it) }
    val russian = SearchText.russianKeyboard(preparedQuery)
    val altKey = if (russian === preparedQuery) key else SearchText.foldPrepared(russian).key
    val altWords = altKey.split(' ').map { FoldedField.compactOf(it) }.takeIf { it.size == words.size } ?: words

    val field = FoldedField.of(text)
    val ranges = ArrayList<IntRange>()
    var primaryMap: PrefixMap? = null
    var altMap: PrefixMap? = null
    for (w in words.indices) {
        if (words[w].isEmpty()) continue
        var match = field.bestMatch(words[w], alt = false)
        var length = words[w].length
        val alt = match == 0L
        if (alt) {
            match = field.bestMatch(altWords[w], alt = true)
            length = altWords[w].length
            if (match == 0L) continue
        }
        val map = if (alt) altMap ?: PrefixMap(text, alt = true).also { altMap = it }
        else primaryMap ?: PrefixMap(text, alt = false).also { primaryMap = it }
        val start = (match and 0xFFFFFFFFL).toInt()
        ranges.add(map.sourceRange(start, start + length))
    }
    return merge(ranges)
}

/** Longest text [matchedRanges] works on. */
const val MAX_HIGHLIGHT_LENGTH = 256

/**
 * For each code point boundary of the source text, the length of the compact search key of
 * the text before it. Folding is applied to whole prefixes, so context rules (е after a
 * vowel, ц, stems) and NFKC see the same neighbours as they do for the full text.
 */
private class PrefixMap(private val text: String, alt: Boolean) {
    private val bounds: IntArray
    private val lengths: IntArray

    init {
        val b = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            b.add(i)
            i += Character.charCount(text.codePointAt(i))
        }
        b.add(text.length)
        bounds = b.toIntArray()
        lengths = IntArray(bounds.size) { k ->
            if (k == 0) {
                0
            } else {
                val prepared = SearchText.prepare(text.substring(0, bounds[k]))
                val s = if (alt) SearchText.russianKeyboard(prepared) else prepared
                FoldedField.compactOf(SearchText.foldPrepared(s).key).length
            }
        }
    }

    /**
     * Source range covering compact positions [from, to), widened over what belongs to the
     * last letter: combining marks, joiners, variation selectors, and the mark of oʻ/gʻ.
     */
    fun sourceRange(from: Int, to: Int): IntRange {
        var k = 0
        while (k + 1 < lengths.size && lengths[k + 1] <= from) k++
        var m = k + 1
        while (m < lengths.size - 1 && lengths[m] < to) m++
        var end = bounds[m]
        while (end < text.length) {
            val c = text[end]
            val t = Character.getType(c)
            val prev = Character.toLowerCase(text[end - 1])
            val attached = t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt() || c == '\u200D' || c in '\uFE00'..'\uFE0F' ||
                (UzbekScript.isApostrophe(c) && (prev == 'o' || prev == 'g'))
            if (!attached) break
            end++
        }
        return bounds[k] until end
    }
}

private fun merge(ranges: List<IntRange>): List<IntRange> {
    if (ranges.size < 2) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<IntRange>()
    var cur = sorted[0]
    for (r in sorted.drop(1)) {
        cur = if (r.first <= cur.last + 1) cur.first..maxOf(cur.last, r.last) else {
            out.add(cur); r
        }
    }
    out.add(cur)
    return out
}
