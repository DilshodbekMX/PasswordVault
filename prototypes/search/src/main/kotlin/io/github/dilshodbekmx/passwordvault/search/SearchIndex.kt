package io.github.dilshodbekmx.passwordvault.search

/**
 * What search reads from one vault entry. It must never hold a password, a previous
 * password, a one-time-code secret or the value of an extra field marked secret.
 *
 * @property websites saved addresses; only their host names are searched ([WebAddress.hostOf]).
 * @property extras names of extra fields, and values of extra fields that are not secret.
 */
data class SearchFields(
    val title: String,
    val username: String = "",
    val websites: List<String> = emptyList(),
    val extras: List<String> = emptyList(),
    val notes: String = "",
)

/** The field in which a query word matched best. */
enum class MatchField { TITLE, USERNAME, WEBSITE, EXTRA, NOTES }

/**
 * One search result: [index] is the entry's position in the list given to [SearchIndex],
 * [score] its rank (higher first), [field] the field of its best match (null for an empty query).
 */
data class SearchHit(val index: Int, val score: Int, val field: MatchField?)

/**
 * An in-memory search index over the entry list, built when the vault is unlocked and
 * dropped when it locks. It holds only search keys of titles, user names, website hosts,
 * extra fields that are not secret and notes; never passwords.
 *
 * Matching: the query's search key ([searchKey]) is split at spaces into words, and every
 * query word must be found in some field of the entry, in any order. A query word is
 * compared with the field ignoring spaces and punctuation, so "clickuz" finds "Click Uz" and
 * "mailru" finds "Mail.ru"; a match may run on into the next word only if it starts at the
 * beginning of a word, so "kuz" does not find "Click Uz".
 *
 * Score of one query word = field weight + position bonus, from its best field:
 * - field: title 300; user name and website host 200; extra fields and notes 100;
 * - position: the whole field 40, start of the field 30, start of a later word 20,
 *   inside a word 10.
 * The field decides first (any title match outranks any user-name match), then the position.
 * A word found only when both sides are spelled as on a Russian keyboard
 * ([SearchText.russianKeyboard]: у for ў and oʻ, к for қ and q, х for ҳ and h, е for ё)
 * scores 5 less. The entry's score is the sum over query words, plus 15 when the whole
 * query also appears in the title starting at a word. Equal scores keep the order of the
 * input list, so pass the list in its display order (A to Z). An empty query (or one of
 * only spaces and apostrophes) returns every entry in order.
 */
class SearchIndex(entries: List<SearchFields>) {

    private val titles = Array(entries.size) { FoldedField.of(entries[it].title) }
    private val usernames = Array(entries.size) { FoldedField.of(entries[it].username) }
    private val websites = Array(entries.size) { i ->
        Array(entries[i].websites.size) { FoldedField.of(WebAddress.hostOf(entries[i].websites[it])) }
    }
    private val extras = Array(entries.size) { i ->
        Array(entries[i].extras.size) { FoldedField.of(entries[i].extras[it]) }
    }
    private val notes = Array(entries.size) { FoldedField.of(entries[it].notes) }

    val size: Int get() = titles.size

    /** Entries matching [query], best first. */
    fun search(query: String): List<SearchHit> {
        val prepared = SearchText.prepare(query)
        val q = SearchText.foldPrepared(prepared)
        if (q.key.isEmpty()) return List(size) { SearchHit(it, 0, null) }

        val rawWords = q.key.split(' ')
        val words = Array(rawWords.size) { FoldedField.compactOf(rawWords[it]) }
        val russian = SearchText.russianKeyboard(prepared)
        val altKey = if (russian === prepared) q.key else SearchText.foldPrepared(russian).key
        val altSplit = altKey.split(' ')
        val altWords = Array(rawWords.size) { w ->
            if (altSplit.size == rawWords.size) FoldedField.compactOf(altSplit[w]) else words[w]
        }
        val phrase = if (rawWords.size > 1) FoldedField.compactOf(q.key) else ""

        val packed = LongArray(size)
        val bestField = ByteArray(size)
        var count = 0
        entries@ for (e in 0 until size) {
            var total = 0
            var best = -1
            for (w in words.indices) {
                val s = scoreWord(e, words[w], altWords[w], rawWords[w])
                if (s < 0) continue@entries
                total += s shr FIELD_BITS
                if (s > best) best = s
            }
            if (phrase.isNotEmpty() && titles[e].matchClass(phrase, alt = false) >= WORD) total += PHRASE_BONUS
            bestField[e] = (best and FIELD_MASK).toByte()
            packed[count++] = (total.toLong() shl 32) or (Int.MAX_VALUE - e).toLong()
        }
        packed.sort(0, count)
        val fields = MatchField.entries
        return List(count) { k ->
            val p = packed[count - 1 - k]
            val e = Int.MAX_VALUE - (p and 0xFFFFFFFFL).toInt()
            SearchHit(e, (p ushr 32).toInt(), fields[bestField[e].toInt()])
        }
    }

    /** Positions of the matching entries, best first. */
    fun searchIndices(query: String): IntArray {
        val hits = search(query)
        return IntArray(hits.size) { hits[it].index }
    }

    /**
     * Best (score shl FIELD_BITS or field ordinal) of one query word over the entry's
     * fields, or -1 when no field has it.
     */
    private fun scoreWord(e: Int, word: String, altWord: String, rawWord: String): Int {
        if (word.isEmpty()) return scorePunctuation(e, rawWord)
        val t = fieldScore(titles[e], word, altWord, TITLE_WEIGHT, MatchField.TITLE)
        if (t >= 0) return t // a title match beats any other field
        var best = fieldScore(usernames[e], word, altWord, USERNAME_WEIGHT, MatchField.USERNAME)
        for (site in websites[e]) best = maxOf(best, fieldScore(site, word, altWord, WEBSITE_WEIGHT, MatchField.WEBSITE))
        if (best >= 0) return best
        for (x in extras[e]) best = maxOf(best, fieldScore(x, word, altWord, EXTRA_WEIGHT, MatchField.EXTRA))
        return maxOf(best, fieldScore(notes[e], word, altWord, NOTES_WEIGHT, MatchField.NOTES))
    }

    private fun fieldScore(field: FoldedField, word: String, altWord: String, weight: Int, f: MatchField): Int {
        val cls = field.matchClass(word, alt = false)
        if (cls > NONE) return pack(weight + cls * POSITION_STEP, f)
        if (!field.hasAlt && altWord == word) return -1 // the second tier would repeat the first
        val altCls = field.matchClass(altWord, alt = true)
        if (altCls > NONE) return pack(weight + altCls * POSITION_STEP - ALT_PENALTY, f)
        return -1
    }

    /** A query word made only of punctuation ("@", "-") is looked up literally. */
    private fun scorePunctuation(e: Int, raw: String): Int {
        val inside = INSIDE * POSITION_STEP
        return when {
            titles[e].key.contains(raw) -> pack(TITLE_WEIGHT + inside, MatchField.TITLE)
            usernames[e].key.contains(raw) -> pack(USERNAME_WEIGHT + inside, MatchField.USERNAME)
            websites[e].any { it.key.contains(raw) } -> pack(WEBSITE_WEIGHT + inside, MatchField.WEBSITE)
            extras[e].any { it.key.contains(raw) } -> pack(EXTRA_WEIGHT + inside, MatchField.EXTRA)
            notes[e].key.contains(raw) -> pack(NOTES_WEIGHT + inside, MatchField.NOTES)
            else -> -1
        }
    }

    private fun pack(score: Int, field: MatchField): Int = (score shl FIELD_BITS) or field.ordinal

    companion object {
        const val TITLE_WEIGHT = 300
        const val USERNAME_WEIGHT = 200
        const val WEBSITE_WEIGHT = 200
        const val EXTRA_WEIGHT = 100
        const val NOTES_WEIGHT = 100
        const val POSITION_STEP = 10
        const val ALT_PENALTY = 5
        const val PHRASE_BONUS = 15

        internal const val NONE = 0
        internal const val INSIDE = 1
        internal const val WORD = 2
        internal const val PREFIX = 3
        internal const val WHOLE = 4

        private const val FIELD_BITS = 3
        private const val FIELD_MASK = 7
    }
}

/**
 * A field ready for matching: its search key; the key without spaces and punctuation
 * ([compact]) with a bit set marking where words start; and the same pair for the
 * Russian-keyboard spelling when it differs.
 */
internal class FoldedField private constructor(
    val key: String,
    private val compact: String,
    private val starts: LongArray,
    private val altCompact: String,
    private val altStarts: LongArray,
) {
    /** True when the Russian-keyboard spelling of this field differs from the key. */
    val hasAlt: Boolean get() = altCompact !== compact

    /**
     * How [word] (already compact) matches this field: [SearchIndex.WHOLE], PREFIX, WORD,
     * INSIDE or NONE. A match that runs on into the next word must begin at a word start.
     */
    fun matchClass(word: String, alt: Boolean): Int = (bestMatch(word, alt) ushr 32).toInt()

    /** Where [word] matches best in the compact form: (class shl 32) or position; class NONE if absent. */
    fun bestMatch(word: String, alt: Boolean): Long {
        val c = if (alt) altCompact else compact
        val bits = if (alt) altStarts else starts
        if (word.isEmpty() || word.length > c.length) return NO_MATCH
        var best = NO_MATCH
        var from = 0
        while (true) {
            val i = c.indexOf(word, from)
            if (i < 0) return best
            val start = i == 0 || isSet(bits, i)
            if (start || noneSet(bits, i + 1, i + word.length)) {
                val cls = when {
                    i == 0 && word.length == c.length -> SearchIndex.WHOLE
                    i == 0 -> SearchIndex.PREFIX
                    start -> SearchIndex.WORD
                    else -> SearchIndex.INSIDE
                }
                val packed = (cls.toLong() shl 32) or i.toLong()
                if (cls >= SearchIndex.WORD) return packed // later matches start further right: no better
                if (best == NO_MATCH) best = packed
            }
            from = i + 1
        }
    }

    companion object {
        private const val NO_MATCH = 0L // class NONE
        private val NO_STARTS = LongArray(0)
        private val EMPTY = FoldedField("", "", NO_STARTS, "", NO_STARTS)

        private fun isSet(bits: LongArray, i: Int): Boolean = (bits[i ushr 6] ushr (i and 63)) and 1L != 0L

        private fun noneSet(bits: LongArray, from: Int, to: Int): Boolean {
            for (k in from until to) if (isSet(bits, k)) return false
            return true
        }

        fun of(text: String): FoldedField {
            if (text.isEmpty()) return EMPTY
            val prepared = SearchText.prepare(text)
            val folded = SearchText.foldPrepared(prepared)
            val (compact, starts) = compactWithStarts(folded)
            val russian = SearchText.russianKeyboard(prepared)
            if (russian === prepared) return FoldedField(folded.key, compact, starts, compact, starts)
            val (altCompact, altStarts) = compactWithStarts(SearchText.foldPrepared(russian))
            if (altCompact == compact) return FoldedField(folded.key, compact, starts, compact, starts)
            return FoldedField(folded.key, compact, starts, altCompact, altStarts)
        }

        private fun compactWithStarts(folded: SearchText.Folded): Pair<String, LongArray> {
            val key = folded.key
            val compact = StringBuilder(key.length)
            val bits = LongArray((key.length + 63) ushr 6)
            var n = 0
            for (k in key.indices) {
                if (isSeparator(key[k])) continue
                if (folded.wordStart[k]) bits[n ushr 6] = bits[n ushr 6] or (1L shl (n and 63))
                compact.append(key[k])
                n++
            }
            val words = (n + 63) ushr 6
            return compact.toString() to (if (words == bits.size) bits else bits.copyOf(words))
        }

        /** [text] (a search key) without spaces and punctuation. */
        fun compactOf(text: String): String {
            var k = 0
            while (k < text.length && !isSeparator(text[k])) k++
            if (k == text.length) return text
            val sb = StringBuilder(text.length)
            for (c in text) if (!isSeparator(c)) sb.append(c)
            return sb.toString()
        }

        private fun isSeparator(c: Char): Boolean {
            if (c == ' ') return true
            if (c in 'a'..'z' || c in '0'..'9') return false
            return isPunctuationType(Character.getType(c))
        }

        private fun isPunctuationType(t: Int): Boolean =
            t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() ||
                t == Character.START_PUNCTUATION.toInt() || t == Character.END_PUNCTUATION.toInt() ||
                t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                t == Character.OTHER_PUNCTUATION.toInt()
    }
}
