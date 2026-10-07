package io.github.dilshodbekmx.passwordvault.core.search

import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript

/**
 * The parts of [text] that [query] matched, as ranges of UTF-16 indices into [text], for
 * showing matched text in bold. Each query word is placed where [SearchIndex] scores it
 * (its best match, the second tier included); overlapping ranges are merged.
 *
 * "Ўзбектелеком" with query "o'zbek" gives [0..4] (Ўзбек); "Click Uz" with "click uz" gives
 * [0..4, 6..7]; "Beşiktaş" with "besiktas" gives all of it. A character that folds to several
 * letters (ш to sh) is bold as a whole. After a retry on the other keyboard layout, pass the
 * retried query ([SearchOutcome.query]).
 *
 * Cost grows with the square of the text length (each prefix is folded once), so call it
 * for visible titles, user names and hosts, not for notes. Texts longer than
 * [MAX_HIGHLIGHT_LENGTH] get no highlight.
 */
fun matchedRanges(text: String, query: String): List<IntRange> {
    if (text.isEmpty() || text.length > MAX_HIGHLIGHT_LENGTH) return emptyList()
    val q = QueryWords.of(query) ?: return emptyList()

    val field = FoldedField.of(text)
    val ranges = ArrayList<IntRange>()
    val maps = HashMap<Spelling, PrefixMap>()
    for (w in 0 until q.size) {
        if (q.word(w, Spelling.PRIMARY).isEmpty()) continue
        var spelling = Spelling.PRIMARY
        var match = field.bestMatch(q.word(w, Spelling.PRIMARY), Spelling.PRIMARY)
        if (match == FoldedField.NO_MATCH) {
            // The second-tier spelling in which the word scores best (the first on a tie).
            for (s in arrayOf(Spelling.RUSSIAN, Spelling.ACCENT_FREE)) {
                if (field.repeatsEarlier(q, w, s)) continue
                val m = field.bestMatch(q.word(w, s), s)
                if (FoldedField.classOf(m) > FoldedField.classOf(match)) {
                    match = m
                    spelling = s
                }
            }
            if (match == FoldedField.NO_MATCH) continue
        }
        val map = maps.getOrPut(spelling) { PrefixMap(text, spelling) }
        val start = FoldedField.positionOf(match)
        ranges.add(map.sourceRange(start, start + q.word(w, spelling).length))
    }
    return merge(ranges)
}

/** Longest text [matchedRanges] works on. */
const val MAX_HIGHLIGHT_LENGTH = 256

/**
 * For each code point boundary of the source text, the length of the compact search key, in
 * [spelling], of the text before it. Folding is applied to whole prefixes, so context rules
 * (е after a vowel, ц, stems) and NFKC see the same neighbours as they do for the full text.
 */
private class PrefixMap(private val text: String, spelling: Spelling) {
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
                FoldedField.compactOf(SearchText.foldPrepared(SearchText.spell(prepared, spelling)).key).length
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
