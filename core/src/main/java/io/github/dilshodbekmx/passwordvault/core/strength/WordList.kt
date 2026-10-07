package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.search.KeyboardLayout
import io.github.dilshodbekmx.passwordvault.core.search.searchKey

/**
 * The bundled Uzbek and Russian word list (ux.md §5.3 "Bundled dictionary", build plan 2.12): the
 * files [FILES], Java resources of :core under [DIRECTORY], which are packaged into the app.
 * `SOURCES.md` beside them names every source, its licence and the count taken from it, for the
 * licences screen. [StrengthMeter] adds the list to zxcvbn's English ones.
 *
 * **Format.** UTF-8, every line ended by LF (no CR). A line that starts with `#` is a note. Every
 * other line is one entry: its spellings, separated by a tab, such as
 * `Toshkent⇥Тошкент⇥Tashkent⇥Ташкент`. Each spelling has at least [MIN_LENGTH] code points, no
 * control character and no white space at either end. The entries of a file are in the order an
 * attacker would try them, the likeliest first: the n-th entry has rank n, and all its spellings
 * share it, as the words of zxcvbn's own lists do (a dictionary word costs as many guesses as its
 * rank). Each file is ranked on its own, like zxcvbn's separate lists; a form found in several files
 * keeps its lowest rank.
 *
 * **Forms.** zxcvbn compares the password in lower case with the words of a list, so [forms] adds
 * each spelling the way a password may contain it: in lower case; as its search key ([searchKey]:
 * Cyrillic in Latin letters by the Uzbek rules, apostrophes removed), which [StrengthMeter] also
 * scores; for Cyrillic, with у к г х for ў қ ғ ҳ as a Russian keyboard types them, and as typed with
 * the keyboard on the wrong layout (ЙЦУКЕН ↔ QWERTY, "gfhjkm" for "пароль"; for the files of
 * [LAYOUT_FILES]); and a phrase without its spaces.
 *
 * [load] reads and checks every file and refuses one that breaks the format, naming the file and the
 * line by its number, never by its text, so that a damaged file fails loudly instead of making the
 * meter quietly weaker.
 */
internal object WordList {

    /** Where the files are among the Java resources of :core. */
    const val DIRECTORY = "/strength/"

    /**
     * The files, each ranked on its own: a short list written for this app of what people put in
     * passwords most, then the places of Uzbekistan, Uzbek and Russian first names, and common Uzbek
     * and Russian words.
     */
    val FILES = listOf("common.txt", "places.txt", "names-uz.txt", "names-ru.txt", "words-uz.txt", "words-ru.txt")

    /**
     * The files whose Cyrillic is also listed as typed on the wrong keyboard layout: Russian, which a
     * phone's Russian keyboard writes, and the short common list. Uzbek Cyrillic is rarely typed on
     * a Russian layout by mistake, and its forms would take more memory than they are worth.
     */
    val LAYOUT_FILES = setOf("common.txt", "names-ru.txt", "words-ru.txt")

    /** The fewest code points a spelling, and a form, may have: shorter words are cheaper to guess one by one. */
    const val MIN_LENGTH = 3

    /** Every form of every spelling of every file, with its rank (see the class comment). */
    fun load(): HashMap<String, Int> {
        val ranks = HashMap<String, Int>(1 shl 17)
        for (file in FILES) {
            val entries = parse(file, read(file))
            val layouts = file in LAYOUT_FILES
            for ((index, spellings) in entries.withIndex()) {
                val rank = index + 1
                for (spelling in spellings) {
                    for (form in forms(spelling, layouts)) {
                        val known = ranks[form]
                        if (known == null || rank < known) ranks[form] = rank
                    }
                }
            }
        }
        return ranks
    }

    private fun read(file: String): String {
        val stream = WordList::class.java.getResourceAsStream(DIRECTORY + file)
            ?: throw IllegalStateException("the strength word list $DIRECTORY$file is missing")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    /**
     * The entries of the file [file] whose text is [text], in rank order, each as its spellings.
     *
     * @throws IllegalStateException naming [file] and the line that breaks the format by its number.
     */
    fun parse(file: String, text: String): List<List<String>> {
        check(text.isNotEmpty() && text.last() == '\n') { "strength word list $file: empty, or the last line has no line end" }
        check(text.indexOf('\r') < 0) { "strength word list $file: a CR in a line end" }
        val entries = ArrayList<List<String>>()
        var number = 0
        for (line in text.substring(0, text.length - 1).split('\n')) {
            number++
            if (line.startsWith("#")) continue
            val spellings = line.split('\t')
            for (spelling in spellings) {
                check(isClean(spelling)) { "strength word list $file line $number: an empty spelling, white space at an end, or a control character" }
                check(spelling.codePointCount(0, spelling.length) >= MIN_LENGTH) {
                    "strength word list $file line $number: a spelling shorter than $MIN_LENGTH characters"
                }
            }
            entries.add(spellings)
        }
        check(entries.isNotEmpty()) { "strength word list $file: no entry" }
        return entries
    }

    private fun isClean(spelling: String): Boolean {
        if (spelling.isEmpty() || Character.isWhitespace(spelling.first()) || Character.isWhitespace(spelling.last())) return false
        for (c in spelling) if (Character.isISOControl(c)) return false
        return true
    }

    /**
     * The forms of [spelling] that the password matcher looks for (see the class comment), with
     * [layouts] the Cyrillic typed on the QWERTY keys too.
     */
    fun forms(spelling: String, layouts: Boolean = true): Set<String> {
        val bases = LinkedHashSet<String>()
        val lower = lowerCase(spelling)
        bases.add(lower)
        val cyrillic = hasCyrillic(lower)
        if (cyrillic) bases.add(russianLetters(lower))
        val out = LinkedHashSet<String>()
        for (base in bases) {
            out.add(base)
            out.add(searchKey(base))
            if (cyrillic && layouts) {
                val swapped = KeyboardLayout.otherLayout(base)
                out.add(swapped)
                out.add(searchKey(swapped))
            }
        }
        for (form in out.toList()) if (form.indexOf(' ') >= 0) out.add(form.replace(" ", ""))
        out.removeAll { it.codePointCount(0, it.length) < MIN_LENGTH }
        return out
    }

    /**
     * [text] in lower case char by char, with `Character.toLowerCase(char)`: the way zxcvbn lowers a
     * password before it looks for words, so that a form and a password meet whatever the default
     * locale ("I" stays "i" under Turkish).
     */
    fun lowerCase(text: CharSequence): String {
        val out = CharArray(text.length)
        for (i in out.indices) out[i] = Character.toLowerCase(text[i])
        return String(out)
    }

    private fun hasCyrillic(text: String): Boolean {
        for (c in text) if (c in '\u0400'..'\u04FF') return true
        return false
    }

    /** ў қ ғ ҳ as a Russian keyboard types them: у к г х. */
    private fun russianLetters(text: String): String =
        text.replace('ў', 'у').replace('қ', 'к').replace('ғ', 'г').replace('ҳ', 'х')
}
