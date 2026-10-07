package io.github.dilshodbekmx.passwordvault.core.search

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
 * What the list shows for what was typed ([SearchIndex.searchOrRetry]): [hits], best first, and
 * the [query] they were found for. When [retried] is true, what was typed found nothing and
 * [query] is the same keys on the other keyboard layout ([KeyboardLayout.otherLayout]): the list
 * then says "Showing results for "[query]"" (ux.md §5.11) and makes bold the ranges of [query]
 * ([matchedRanges]), not those of what was typed.
 */
data class SearchOutcome(val query: String, val hits: List<SearchHit>, val retried: Boolean)

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
 * A word found only in the second tier scores 5 less: there both sides are spelled as on a
 * Russian keyboard (у for ў and oʻ, к for қ and q, х for ҳ and h, е for ё), and also, in a
 * second spelling, with s for ş and c for ç (architecture.md D-11: "besiktas" finds
 * "Beşiktaş", "garcon" finds "Garçon"); see [Spelling]. The entry's score is the sum over
 * query words, plus 15 when the whole query also appears in the title starting at a word.
 * Equal scores keep the order of the input list, so pass the list in its display order
 * (A to Z). An empty query (or one of only spaces and apostrophes) returns every entry in order.
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
        val q = QueryWords.of(query) ?: return List(size) { SearchHit(it, 0, null) }
        val phrase = if (q.size > 1) FoldedField.compactOf(q.key) else ""

        val packed = LongArray(size)
        val bestField = ByteArray(size)
        var count = 0
        entries@ for (e in 0 until size) {
            var total = 0
            var best = -1
            for (w in 0 until q.size) {
                val s = scoreWord(e, q, w)
                if (s < 0) continue@entries
                total += s shr FIELD_BITS
                if (s > best) best = s
            }
            if (phrase.isNotEmpty() && titles[e].firstTierClass(phrase) >= WORD) total += PHRASE_BONUS
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

    /**
     * [search], with the retry of a query typed on the wrong keyboard layout (search.md §2,
     * architecture.md D-11): when [query] finds nothing in either tier, it is searched once more
     * as typed on the other layout ([KeyboardLayout.otherLayout]), so "штые" gives the hits of
     * "inst" and "ghbdtn" those of "привет". The retry is shown only when it differs from
     * [query] and finds something; otherwise the outcome is [query] with no hits.
     */
    fun searchOrRetry(query: String): SearchOutcome {
        val hits = search(query)
        if (hits.isNotEmpty()) return SearchOutcome(query, hits, retried = false)
        val other = KeyboardLayout.otherLayout(query)
        if (other != query) {
            val otherHits = search(other)
            if (otherHits.isNotEmpty()) return SearchOutcome(other, otherHits, retried = true)
        }
        return SearchOutcome(query, hits, retried = false)
    }

    /** Positions of the matching entries, best first. */
    fun searchIndices(query: String): IntArray {
        val hits = search(query)
        return IntArray(hits.size) { hits[it].index }
    }

    /**
     * Best (score shl FIELD_BITS or field ordinal) of query word [w] over entry [e]'s
     * fields, or -1 when no field has it.
     */
    private fun scoreWord(e: Int, q: QueryWords, w: Int): Int {
        val word = q.word(w, Spelling.PRIMARY)
        if (word.isEmpty()) return scorePunctuation(e, q.raw[w])
        val t = fieldScore(titles[e], word, q, w, TITLE_WEIGHT, MatchField.TITLE)
        if (t >= 0) return t // a title match beats any other field
        var best = fieldScore(usernames[e], word, q, w, USERNAME_WEIGHT, MatchField.USERNAME)
        for (site in websites[e]) best = maxOf(best, fieldScore(site, word, q, w, WEBSITE_WEIGHT, MatchField.WEBSITE))
        if (best >= 0) return best
        for (x in extras[e]) best = maxOf(best, fieldScore(x, word, q, w, EXTRA_WEIGHT, MatchField.EXTRA))
        return maxOf(best, fieldScore(notes[e], word, q, w, NOTES_WEIGHT, MatchField.NOTES))
    }

    /** [scoreWord] in one [field]; [word] is query word [w] in the first tier. */
    private fun fieldScore(field: FoldedField, word: String, q: QueryWords, w: Int, weight: Int, f: MatchField): Int {
        val cls = field.firstTierClass(word)
        if (cls > NONE) return pack(weight + cls * POSITION_STEP, f)
        if (field.plain && q.plain(w)) return -1 // the second tier would only repeat the first
        val altCls = field.secondTierClass(q, w)
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
 * A query's search key split at spaces into words, each word in every [Spelling], compact
 * (without spaces and punctuation, [FoldedField.compactOf]). Built once per query. A word that is
 * the same in two spellings is one instance in both, so `===` tells whether comparing it in one
 * spelling would repeat another ([FoldedField.secondTierClass]).
 */
internal class QueryWords private constructor(
    /** The query's search key. */
    val key: String,
    /** The words of [key], before compaction (a word of punctuation only is looked up as it is). */
    val raw: List<String>,
    /** The words in the first tier, [Spelling.PRIMARY]. */
    val primary: Array<String>,
    /** The words in [Spelling.RUSSIAN]. */
    val russian: Array<String>,
    /** The words in [Spelling.ACCENT_FREE]. */
    val accentFree: Array<String>,
) {
    val size: Int get() = raw.size

    /** For each word, true when it is the same in every spelling. */
    private val plainWords = BooleanArray(raw.size) { w -> russian[w] === primary[w] && accentFree[w] === primary[w] }

    /** True when word [w] is the same in every spelling: the second tier has nothing new to compare. */
    fun plain(w: Int): Boolean = plainWords[w]

    /** Word [w] in [spelling]. */
    fun word(w: Int, spelling: Spelling): String = when (spelling) {
        Spelling.PRIMARY -> primary[w]
        Spelling.RUSSIAN -> russian[w]
        Spelling.ACCENT_FREE -> accentFree[w]
    }

    companion object {
        /** The words of [query]; null when its key is empty (only spaces and apostrophes). */
        fun of(query: String): QueryWords? {
            val prepared = SearchText.prepare(query)
            val key = SearchText.foldPrepared(prepared).key
            if (key.isEmpty()) return null
            val raw = key.split(' ')
            val primary = Array(raw.size) { FoldedField.compactOf(raw[it]) }
            val russian = wordsOf(SearchText.russianKeyboard(prepared), prepared, primary)
            val accentPrepared = SearchText.accentFree(prepared)
            val accentFree = if (accentPrepared === prepared) {
                russian // no ş, ș or ç: the same as the Russian-keyboard spelling
            } else {
                wordsOf(SearchText.russianKeyboard(accentPrepared), prepared, primary, russian)
            }
            return QueryWords(key, raw, primary, russian, accentFree)
        }

        /**
         * The compact words of [spelled], a spelling of [prepared]: [primary] when it is
         * [prepared] itself or does not split into as many words; otherwise each word is the
         * instance of [primary] or of an [earlier] spelling when it equals that one.
         */
        private fun wordsOf(
            spelled: String,
            prepared: String,
            primary: Array<String>,
            vararg earlier: Array<String>,
        ): Array<String> {
            if (spelled === prepared) return primary
            val split = SearchText.foldPrepared(spelled).key.split(' ')
            if (split.size != primary.size) return primary
            return Array(primary.size) { w ->
                val word = FoldedField.compactOf(split[w])
                if (word == primary[w]) primary[w] else earlier.firstOrNull { it[w] == word }?.get(w) ?: word
            }
        }
    }
}

/**
 * A field ready for matching: its search key, and for each [Spelling] the key without spaces
 * and punctuation (compact) with a bit set marking where words start. Spellings with the same
 * compact form share one instance.
 */
internal class FoldedField private constructor(
    val key: String,
    private val compact: String,
    private val starts: LongArray,
    private val russianCompact: String,
    private val russianStarts: LongArray,
    private val accentCompact: String,
    private val accentStarts: LongArray,
) {
    /** True when this field reads the same in every spelling: the second tier has nothing new to compare. */
    val plain: Boolean = russianCompact === compact && accentCompact === compact

    /**
     * How [word] (already compact, in [spelling]) matches this field in [spelling]:
     * [SearchIndex.WHOLE], PREFIX, WORD, INSIDE or NONE. A match that runs on into the next
     * word must begin at a word start.
     */
    fun matchClass(word: String, spelling: Spelling): Int = classOf(bestMatch(word, spelling))

    /** [matchClass] in the first tier. */
    fun firstTierClass(word: String): Int = classOf(bestMatchIn(compact, starts, word))

    /**
     * The best [matchClass] of query word [w] in the second tier: each second-tier spelling of the
     * word against the same spelling of this field, without the comparisons that would repeat an
     * earlier one ([repeatsEarlier]). Written out per spelling, as it runs for every field that
     * the first tier misses.
     */
    fun secondTierClass(q: QueryWords, w: Int): Int {
        val word = q.primary[w]
        val russianWord = q.russian[w]
        val accentWord = q.accentFree[w]
        var best = SearchIndex.NONE
        if (russianCompact !== compact || russianWord !== word) {
            best = classOf(bestMatchIn(russianCompact, russianStarts, russianWord))
        }
        if ((accentCompact !== compact || accentWord !== word) &&
            (accentCompact !== russianCompact || accentWord !== russianWord)
        ) {
            best = maxOf(best, classOf(bestMatchIn(accentCompact, accentStarts, accentWord)))
        }
        return best
    }

    /**
     * True when comparing query word [w] with this field in [spelling] repeats the comparison of an
     * earlier spelling: the field and the word are both spelled as there. The rule of
     * [secondTierClass], for one spelling.
     */
    fun repeatsEarlier(q: QueryWords, w: Int, spelling: Spelling): Boolean = when (spelling) {
        Spelling.PRIMARY -> false
        Spelling.RUSSIAN -> russianCompact === compact && q.russian[w] === q.primary[w]
        Spelling.ACCENT_FREE ->
            (accentCompact === compact && q.accentFree[w] === q.primary[w]) ||
                (accentCompact === russianCompact && q.accentFree[w] === q.russian[w])
    }

    /**
     * Where [word] matches best in the compact form of [spelling]: (class shl 32) or
     * position; [NO_MATCH] (class NONE) if absent.
     */
    fun bestMatch(word: String, spelling: Spelling): Long = when (spelling) {
        Spelling.PRIMARY -> bestMatchIn(compact, starts, word)
        Spelling.RUSSIAN -> bestMatchIn(russianCompact, russianStarts, word)
        Spelling.ACCENT_FREE -> bestMatchIn(accentCompact, accentStarts, word)
    }

    /** [bestMatch] in the compact form [c] whose word starts are [bits]. */
    private fun bestMatchIn(c: String, bits: LongArray, word: String): Long {
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
        /** [bestMatch] of no match. */
        const val NO_MATCH = 0L // class NONE

        /** The match class of a [bestMatch] result. */
        fun classOf(match: Long): Int = (match ushr 32).toInt()

        /** The position in the compact form of a [bestMatch] result. */
        fun positionOf(match: Long): Int = (match and 0xFFFFFFFFL).toInt()

        private val NO_STARTS = LongArray(0)
        private val EMPTY = FoldedField("", "", NO_STARTS, "", NO_STARTS, "", NO_STARTS)

        private fun isSet(bits: LongArray, i: Int): Boolean = (bits[i ushr 6] ushr (i and 63)) and 1L != 0L

        private fun noneSet(bits: LongArray, from: Int, to: Int): Boolean {
            for (k in from until to) if (isSet(bits, k)) return false
            return true
        }

        fun of(text: String): FoldedField {
            if (text.isEmpty()) return EMPTY
            val prepared = SearchText.prepare(text)
            val folded = SearchText.foldPrepared(prepared)
            val primary = compactWithStarts(folded)
            val russian = spelled(SearchText.russianKeyboard(prepared), prepared, primary)
            val accentPrepared = SearchText.accentFree(prepared)
            val accent = if (accentPrepared === prepared) {
                russian // no ş, ș or ç: the same as the Russian-keyboard spelling
            } else {
                spelled(SearchText.russianKeyboard(accentPrepared), prepared, primary, russian)
            }
            return FoldedField(
                folded.key, primary.first, primary.second, russian.first, russian.second, accent.first, accent.second,
            )
        }

        /**
         * The compact form of [spelledText], a spelling of [prepared]; one of [earlier] (the
         * same instance) when it is [prepared] itself or has the same compact form.
         */
        private fun spelled(
            spelledText: String,
            prepared: String,
            vararg earlier: Pair<String, LongArray>,
        ): Pair<String, LongArray> {
            if (spelledText === prepared) return earlier[0]
            val result = compactWithStarts(SearchText.foldPrepared(spelledText))
            return earlier.firstOrNull { it.first == result.first } ?: result
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
