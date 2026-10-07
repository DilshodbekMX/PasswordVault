package io.github.dilshodbekmx.passwordvault.core.strength.wordlist

import com.nulabinc.zxcvbn.StandardDictionaries
import io.github.dilshodbekmx.passwordvault.core.search.searchKey
import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript
import io.github.dilshodbekmx.passwordvault.core.tools.ProgramRun
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Test

/**
 * Builds the strength word list (build plan 2.12; `core.strength.WordList` reads it) from downloaded
 * sources, and writes it with its `SOURCES.md` into `core/src/main/resources/strength/`. A program,
 * not a check: it runs only when asked,
 *
 *     tools/gradle.sh :core:testDebugUnitTest --tests '*WordListBuilder*' -Ppv.strengthList=<input directory>
 *
 * and prints what it took from each source. The input directory holds (see `SOURCES.md` for the
 * addresses, dates and checksums of the files the committed list was built from):
 * - `geonames-UZ.zip` and `geonames-alternatenames-UZ.zip`: GeoNames' Uzbekistan extract and its
 *   alternate names (`https://download.geonames.org/export/dump/UZ.zip`, `…/dump/alternatenames/UZ.zip`);
 * - `frequencywords-ru_50k.txt`: Hermit Dave's FrequencyWords, `content/2018/ru/ru_50k.txt`;
 * - `enwiktionary-given-names.tsv` and `uzwiki-given-names.tsv`: written by `fetch_lists.py` (this
 *   directory) from the wikis' APIs;
 * - `uzwiki-words.tsv` and `uzwiki-bio-names.tsv`: written by `uzwiki_counts.py` (this directory)
 *   from a dump of the Uzbek Wikipedia, `uzwiki-<date>-pages-articles.xml.bz2`.
 *
 * The list written by hand for this app is `common-seed.txt` in this directory: one entry per line,
 * a language code (`uz`, `ru`, or `xx` for neither), a tab, and the entry's spellings separated by
 * tabs; for `uz` the builder adds the Uzbek Cyrillic of the first spelling, for `ru` its Latin
 * transliterations.
 */
class WordListBuilder {

    @Test
    fun build() {
        val inputs = ProgramRun.file(ProgramRun.arguments("pv.strengthList").single())
        val out = File(SourceTree.root(), "core/src/main/resources/strength")
        out.mkdirs()
        val english = englishWords()
        val freqRu = readFrequencyWords(File(inputs, "frequencywords-ru_50k.txt"))
        val uzWords = readUzwikiWords(File(inputs, "uzwiki-words.tsv"))

        val common = commonList(File(SourceTree.root(), SEED))
        val places = placesList(inputs)
        val namesRu = russianNames(inputs, freqRu)
        val namesUz = uzbekNames(inputs, uzWords, namesRu)
        val wordsUz = uzbekWords(uzWords, english, namesUz, places)
        val wordsRu = russianWords(freqRu, namesRu)

        val files = listOf(
            Output("common.txt", "the passwords people choose most: places, names, words and phrases, written for this app", common),
            Output("places.txt", "regions, cities and districts of Uzbekistan, biggest first", places),
            Output("names-uz.txt", "Uzbek first names, most frequent first", namesUz),
            Output("names-ru.txt", "Russian first names and their short forms, most frequent first", namesRu),
            Output("words-uz.txt", "common Uzbek words, most frequent first", wordsUz),
            Output("words-ru.txt", "common Russian words, most frequent first", wordsRu),
        )
        for (file in files) {
            val text = buildString {
                append("# PasswordVault strength word list: ").append(file.title).append(".\n")
                append("# One entry per line, its spellings separated by tabs; see SOURCES.md.\n")
                for (entry in file.entries) append(entry.spellings.joinToString("\t")).append('\n')
            }
            File(out, file.name).writeText(text, Charsets.UTF_8)
            ProgramRun.say("${file.name}: ${file.entries.size} entries, ${text.toByteArray(Charsets.UTF_8).size} bytes")
            for ((source, count) in file.entries.flatMap { it.sources }.groupingBy { it }.eachCount().toSortedMap()) {
                ProgramRun.say("  from $source: $count")
            }
        }
        val inputSums = listOf(
            "geonames-UZ.zip", "geonames-alternatenames-UZ.zip", "frequencywords-ru_50k.txt",
            "enwiktionary-given-names.tsv", "uzwiki-given-names.tsv", "uzwiki-words.tsv", "uzwiki-bio-names.tsv",
        ).associateWith { sha256(File(inputs, it).readBytes()) }
        for ((name, sum) in inputSums) ProgramRun.say("input $name sha256 $sum")
        for (file in files) ProgramRun.say("output ${file.name} sha256 ${sha256(File(out, file.name).readBytes())}")
    }

    private class Entry(val spellings: List<String>, val sources: Set<String>)

    private class Output(val name: String, val title: String, val entries: List<Entry>)

    // ---- The list written for this app ----

    private fun commonList(seed: File): List<Entry> {
        val entries = ArrayList<Entry>()
        val taken = HashSet<String>()
        for (line in seed.readLines(Charsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split('\t')
            val lang = fields.first()
            val given = fields.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            require(given.isNotEmpty() && lang in setOf("uz", "ru", "xx")) { "common-seed.txt: bad line" }
            val spellings = ArrayList<String>()
            when (lang) {
                "uz" -> {
                    val latin = normaliseLatin(given.first())
                    spellings += latin
                    spellings += uzbekCyrillic(latin)
                    spellings += given.drop(1).map { if (isCyrillic(it)) it else normaliseLatin(it) }
                }
                "ru" -> {
                    spellings += given.first()
                    spellings += transliterations(given.first())
                    spellings += given.drop(1)
                }
                else -> spellings += given
            }
            val kept = distinctSpellings(spellings, taken)
            if (kept.isNotEmpty()) entries += Entry(kept, setOf("own"))
        }
        return entries
    }

    // ---- Places: GeoNames ----

    private class Place(val id: String, val code: String, val population: Long, val name: String)

    private fun placesList(inputs: File): List<Entry> {
        val places = ArrayList<Place>()
        zipText(File(inputs, "geonames-UZ.zip"), "UZ.txt").lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val f = line.split('\t')
            val featureClass = f[6]
            val code = f[7]
            val population = f[14].toLongOrNull() ?: 0L
            val wanted = (featureClass == "A" && (code == "ADM1" || code == "ADM2")) ||
                (featureClass == "P" && (code == "PPLC" || code == "PPLA" || code == "PPLA2" || (code == "PPL" && population >= MIN_TOWN)))
            if (wanted) places += Place(f[0], code, population, f[1])
        }
        val alternates = HashMap<String, MutableList<Pair<String, String>>>()
        zipText(File(inputs, "geonames-alternatenames-UZ.zip"), "UZ.txt").lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            val f = line.split('\t')
            val historic = f.getOrNull(7) == "1"
            if (!historic && f[2] in setOf("", "uz", "ru", "en")) alternates.getOrPut(f[1]) { ArrayList() } += f[2] to f[3]
        }
        // Biggest first; a region and its capital share most spellings, which the first keeps.
        places.sortWith(compareByDescending<Place> { it.population }.thenBy { it.code }.thenBy { it.id })
        val entries = ArrayList<Entry>()
        val taken = HashSet<String>()
        for (place in places) {
            val names = alternates[place.id].orEmpty()
            val uzLatin = (names.filter { it.first == "uz" }.map { it.second } + place.name)
                .mapNotNull { placeName(it, cyrillic = false) }
                .filter { UZBEK_LATIN.matches(it.lowercase(Locale.ROOT)) && !NOT_UZBEK_LATIN.containsMatchIn(it.lowercase(Locale.ROOT)) }
                .sortedByDescending { name -> name.count { it == UzbekScript.OKINA } } // the spelling with oʻ and gʻ first
            val spellings = ArrayList<String>()
            uzLatin.firstOrNull()?.let { spellings += it; spellings += uzbekCyrillic(it) }
            spellings += names.filter { it.first == "uz" || it.first == "ru" }.mapNotNull { placeName(it.second, cyrillic = true) }
            spellings += names.filter { it.first == "en" || it.first == "" }.mapNotNull { placeName(it.second, cyrillic = isCyrillic(it.second)) }
            spellings += uzLatin.drop(1)
            val kept = distinctSpellings(spellings, taken)
            // "In both scripts" (build plan 2.12): an entry keeps a Latin and a Cyrillic spelling, or goes.
            if (kept.any { isCyrillic(it) } && kept.any { !isCyrillic(it) }) entries += Entry(kept, setOf("geonames"))
        }
        return entries
    }

    /** The bare name in [raw] (a region's or district's name without "viloyati", "tumani", "область"), or null. */
    private fun placeName(raw: String, cyrillic: Boolean): String? {
        var text = raw.replace(Regex("""\s*\(.*?\)"""), "").trim()
        if (text.isEmpty() || text.any { it.isDigit() || it in ",/;:\"«»" }) return null
        val words = text.split(Regex("""\s+""")).filter { it.lowercase(Locale.ROOT) !in ADMIN_WORDS }
        if (words.isEmpty() || words.size > 2) return null
        if (words.any { ADJECTIVE.containsMatchIn(it.lowercase(Locale.ROOT)) }) return null
        text = words.joinToString(" ")
        if (cyrillic != isCyrillic(text)) return null
        if (!cyrillic) {
            text = normaliseLatin(text)
            if (!LATIN_NAME.matches(text)) return null
        } else if (!CYRILLIC_NAME.matches(text)) {
            return null
        }
        return if (text.codePointCount(0, text.length) >= MIN_LENGTH) text else null
    }

    // ---- First names ----

    private fun russianNames(inputs: File, freq: Map<String, Long>): List<Entry> {
        val names = LinkedHashMap<String, String>() // lower case -> as listed
        for (line in File(inputs, "enwiktionary-given-names.tsv").readLines(Charsets.UTF_8)) {
            val (category, title) = line.split('\t')
            if (!category.startsWith("Category:Russian")) continue
            if (!CYRILLIC_NAME.matches(title) || title.contains(' ')) continue
            names.putIfAbsent(title.lowercase(Locale.ROOT), title)
        }
        val ranked = names.entries.sortedWith(compareByDescending<Map.Entry<String, String>> { freq[it.key] ?: 0L }.thenBy { it.key })
        val taken = HashSet<String>()
        return ranked.mapNotNull { (_, title) ->
            val display = capitalise(title)
            val kept = distinctSpellings(listOf(display) + transliterations(display), taken)
            if (kept.firstOrNull() != display) null else Entry(kept, setOf("enwiktionary"))
        }
    }

    private fun uzbekNames(inputs: File, uzWords: Map<String, Long>, russian: List<Entry>): List<Entry> {
        val russianKeys = russian.flatMap { e -> e.spellings.map { searchKey(it) } }.toHashSet()
        val sources = LinkedHashMap<String, MutableSet<String>>() // Uzbek Latin, lower case -> sources
        fun add(name: String, source: String) {
            val latin = if (isCyrillic(name)) UzbekScript.cyrillicToLatin(name) else name
            val clean = normaliseLatin(latin.replace(Regex("""\s*\(.*?\)\s*$"""), "").trim()).lowercase(Locale.ROOT)
            if (!LATIN_WORD.matches(clean) || clean.codePointCount(0, clean.length) < MIN_LENGTH) return
            sources.getOrPut(clean) { LinkedHashSet() } += source
        }
        for (line in File(inputs, "uzwiki-given-names.tsv").readLines(Charsets.UTF_8)) add(line.split('\t')[1], "uzwiki-names")
        for (line in File(inputs, "enwiktionary-given-names.tsv").readLines(Charsets.UTF_8)) {
            val (category, title) = line.split('\t')
            if (category.startsWith("Category:Uzbek")) add(title, "enwiktionary")
        }
        val biographies = HashMap<String, Int>()
        for (line in File(inputs, "uzwiki-bio-names.tsv").readLines(Charsets.UTF_8)) {
            val f = line.split('\t')
            val name = f[0]
            val uzbek = f[2].toInt()
            biographies[name] = uzbek
            val all = f[1].toInt()
            // Mostly in Uzbek biographies, and in two of them or with an ending of Uzbek names.
            val looksUzbek = 2 * uzbek >= all && (uzbek >= 2 || (uzbek == 1 && UZBEK_NAME_END.containsMatchIn(name)))
            if (looksUzbek && !SURNAME_END.containsMatchIn(name) && name !in FOREIGN_NAMES && searchKey(name) !in russianKeys) {
                add(name, "uzwiki-biographies")
            }
        }
        val ranked = sources.keys.sortedWith(
            compareByDescending<String> { uzWords[it] ?: 0L }.thenByDescending { biographies[it] ?: 0 }.thenBy { it },
        )
        val taken = HashSet<String>()
        return ranked.mapNotNull { name ->
            val display = capitalise(name)
            val kept = distinctSpellings(listOf(display, uzbekCyrillic(display)), taken)
            if (kept.isEmpty()) null else Entry(kept, sources.getValue(name))
        }
    }

    // ---- Words ----

    private fun uzbekWords(uzWords: Map<String, Long>, english: Set<String>, names: List<Entry>, places: List<Entry>): List<Entry> {
        val elsewhere = (names + places).flatMap { e -> e.spellings.map { it.lowercase(Locale.ROOT) } }.toHashSet()
        val entries = ArrayList<Entry>()
        val taken = HashSet<String>()
        for (word in uzWords.keys) { // in descending frequency
            if (entries.size >= UZ_WORDS) break
            if (!LATIN_WORD.matches(word) || word.codePointCount(0, word.length) < MIN_LENGTH) continue
            if ('w' in word || NOT_UZBEK_C.containsMatchIn(word) || word.startsWith("th")) continue
            if (word in english || word in elsewhere) continue
            if (rareAndShort(entries.size + 1, word)) continue
            val kept = distinctSpellings(listOf(word, uzbekCyrillic(word)), taken)
            if (kept.size == 2) entries += Entry(kept, setOf("uzwiki-words"))
        }
        return entries
    }

    private fun russianWords(freq: Map<String, Long>, names: List<Entry>): List<Entry> {
        val elsewhere = names.flatMap { e -> e.spellings.map { it.lowercase(Locale.ROOT) } }.toHashSet()
        val entries = ArrayList<Entry>()
        val taken = HashSet<String>()
        for (word in freq.keys) { // in descending frequency
            if (entries.size >= RU_WORDS) break
            if (!RUSSIAN_WORD.matches(word) || word.length < MIN_LENGTH || word in elsewhere) continue
            if (rareAndShort(entries.size + 1, word)) continue
            val kept = distinctSpellings(listOf(word) + transliterations(word).take(1), taken)
            if (kept.isNotEmpty()) entries += Entry(kept, setOf("frequencywords"))
        }
        return entries
    }

    /**
     * zxcvbn's own filter (its `build_frequency_lists.py`): a word whose rank is at least 10 to the
     * power of its length is cheaper to guess letter by letter, so it is left out.
     */
    private fun rareAndShort(rank: Int, word: String): Boolean {
        val length = word.codePointCount(0, word.length)
        return length < 9 && rank >= Math.pow(10.0, length.toDouble())
    }

    // ---- Spellings ----

    /** [spellings] without those seen before in this file ([taken], compared in lower case), in order. */
    private fun distinctSpellings(spellings: List<String>, taken: MutableSet<String>): List<String> {
        val out = ArrayList<String>()
        for (s in spellings) {
            if (s.codePointCount(0, s.length) < MIN_LENGTH) continue
            if (taken.add(s.lowercase(Locale.ROOT))) out += s
        }
        return out
    }

    /** Uzbek Latin with oʻ and gʻ written with U+02BB and every other apostrophe as U+02BC. */
    private fun normaliseLatin(text: String): String {
        val out = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) {
            if (UzbekScript.isApostrophe(c)) {
                val before = if (i > 0) text[i - 1].lowercaseChar() else ' '
                out.append(if (before == 'o' || before == 'g') UzbekScript.OKINA else UzbekScript.TUTUQ)
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun uzbekCyrillic(latin: String): String = UzbekScript.latinToCyrillic(latin, keep = emptySet())

    /** "aleksandr" → "Aleksandr", "oʻlmas" → "Oʻlmas", "александр" → "Александр". */
    private fun capitalise(word: String): String =
        if (word.isEmpty()) word else word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)

    /**
     * The Latin spellings Russian speakers commonly type for [cyrillic]: the first with kh, ts, shch
     * and y (Aleksandr, Dmitriy, Natalya); the second, when it differs, with h, c, sch and i (Mihail,
     * Dmitrii).
     */
    private fun transliterations(cyrillic: String): List<String> {
        val first = transliterate(cyrillic, RU_LATIN)
        val second = transliterate(cyrillic, RU_LATIN + RU_LATIN_INFORMAL)
        return if (second == first) listOf(first) else listOf(first, second)
    }

    private fun transliterate(text: String, table: Map<Char, String>): String {
        val out = StringBuilder()
        for (c in text) {
            val lower = c.lowercaseChar()
            val latin = table[lower] ?: c.toString()
            out.append(if (c != lower && latin.isNotEmpty()) latin.substring(0, 1).uppercase(Locale.ROOT) + latin.substring(1) else latin)
        }
        return out.toString()
    }

    private fun isCyrillic(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    // ---- Inputs ----

    private fun englishWords(): Set<String> =
        StandardDictionaries.ENGLISH_WIKIPEDIA_LOADER.load().frequencies.take(ENGLISH_TOP).toHashSet()

    /** FrequencyWords: "<word> <count>" per line, in descending count. */
    private fun readFrequencyWords(file: File): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in file.readLines(Charsets.UTF_8)) {
            val space = line.lastIndexOf(' ')
            if (space <= 0) continue
            out.putIfAbsent(line.substring(0, space).lowercase(Locale.ROOT), line.substring(space + 1).toLong())
        }
        return out
    }

    /** uzwiki_counts.py: "<word>\t<sentences>\t<articles>" in descending sentences. */
    private fun readUzwikiWords(file: File): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (line in file.readLines(Charsets.UTF_8)) {
            val f = line.split('\t')
            out.putIfAbsent(f[0], f[1].toLong())
        }
        return out
    }

    private fun zipText(zip: File, entry: String): String = ZipFile(zip).use { z ->
        z.getInputStream(z.getEntry(entry)).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val SEED = "core/src/test/java/io/github/dilshodbekmx/passwordvault/core/strength/wordlist/common-seed.txt"
        const val MIN_LENGTH = 3
        const val MIN_TOWN = 10_000L
        const val UZ_WORDS = 6_000
        const val RU_WORDS = 8_000
        const val ENGLISH_TOP = 3_000

        val ADMIN_WORDS = setOf(
            "viloyati", "viloyat", "wiloyati", "oblasti", "oblast", "oblast’", "region", "province", "tumani", "tuman",
            "rayoni", "rayon", "district", "shahri", "shahar", "shaharchasi", "gorod", "city", "respublikasi", "respublika",
            "republic", "of", "the", "область", "район", "город", "республика", "вилояти", "тумани", "шаҳри", "шаҳар",
            "туман", "вилоят", "посёлок", "поселок", "аул", "кишлак", "assr", "ssr", "shaary", "шаары", "qishlogʻi",
            "qishlogi", "posyolok", "poselok",
        )
        val ADJECTIVE = Regex("""(skiy|skaya|skoye|skiye|ский|ская|ское|ские|ный|ная|ное)$""")
        val LATIN_NAME = Regex("""[A-Z][a-zʻʼ]*(?:[- ][A-Za-zʻʼ]+)?""")
        val CYRILLIC_NAME = Regex("""[А-ЯЁЎҚҒҲ][а-яёўқғҳ]+(?:[- ][А-ЯЁЎҚҒҲа-яёўқғҳ]+)?""")
        val LATIN_WORD = Regex("""[a-z]+(?:[ʻʼ][a-z]+)*ʻ?""")
        val RUSSIAN_WORD = Regex("""[а-яё]+""")
        val NOT_UZBEK_C = Regex("""c(?!h)""")
        val UZBEK_LATIN = Regex("""[a-zʻʼ -]+""")

        /** Romanisations of Russian (kh, zh, ts, w) that Uzbek Latin does not use: such a name is no Uzbek Latin. */
        val NOT_UZBEK_LATIN = Regex("""w|c(?!h)|kh|zh|ts|yy|skiy$""")
        val SURNAME_END = Regex("""(ov|ova|ev|eva|yev|yeva)$""")

        /**
         * Given names of foreign sportsmen and others that the rule for biographies lets through (they
         * play for Uzbek clubs), checked by hand in the list of 2026-10-07.
         */
        val FOREIGN_NAMES = setOf(
            "bojan", "darko", "dejan", "elguja", "frane", "ghenadie", "giorgi", "marko", "nenad", "nodar", "shota",
            "tomislav", "zoran", "naum", "aram", "milan",
        )

        /** Endings that mark a name as Uzbek, for names seen in one Uzbek biography only. */
        val UZBEK_NAME_END = Regex(
            """(jon|bek|boy|xon|gul|oy|qul|murod|xoʻja|niyoz|nisa|bonu|iddin|ulla|ulloh|oxun|nazar|berdi|bergan|toʻra|mirzo|xoja)$""",
        )

        val RU_LATIN: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z",
            'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
            'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh",
            'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        val RU_LATIN_INFORMAL: Map<Char, String> = mapOf('х' to "h", 'ц' to "c", 'щ' to "sch", 'й' to "i", 'ё' to "e")
    }
}
