package io.github.dilshodbekmx.passwordvault.text

import io.github.dilshodbekmx.passwordvault.search.SearchText
import io.github.dilshodbekmx.passwordvault.search.searchKey
import java.io.File
import java.util.Locale

/**
 * Word-by-word accuracy of [UzbekScript] on parallel texts.
 *
 * Input: pairs of TSV files (paragraph id, text) made by tools/extract_lex.py from the
 * Cyrillic and Latin editions of the same law on lex.uz. Paragraphs pair by id; words
 * pair by position when a paragraph has the same number of words in both editions.
 *
 * Usage: ConverterEval outDir name:cyr.tsv:lat.tsv [name:cyr.tsv:lat.tsv ...]
 */
private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’‘`´][\\p{L}\\p{M}]+)*")

private class Pair2(val cyr: String, val lat: String)

private class Tally {
    var tokens = 0
    var exact = 0
    var caseOnly = 0
    val types = HashMap<String, Boolean>() // distinct word pair -> converted exactly
    val patterns = HashMap<String, Int>()
    val examples = HashMap<String, MutableList<String>>()
    val errors = HashMap<String, Int>() // "source -> ours (ref)" -> count

    fun add(source: String, ours: String, ref: String) {
        tokens++
        val ok = ours == ref
        if (ok) exact++ else if (ours.equals(ref, ignoreCase = true)) caseOnly++
        types[source + "\u0000" + ref] = ok
        if (!ok) {
            val p = diffPattern(ours, ref)
            patterns[p] = (patterns[p] ?: 0) + 1
            val ex = examples.getOrPut(p) { mutableListOf() }
            val line = "$source → $ours (official $ref)"
            if (ex.size < 4 && line !in ex) ex.add(line)
            errors[line] = (errors[line] ?: 0) + 1
        }
    }

    fun typesExact(): Int = types.values.count { it }
}

/** "ours→official" for the differing middle, lower case, or "case" for case-only differences. */
private fun diffPattern(ours: String, ref: String): String {
    if (ours.equals(ref, ignoreCase = true)) return "case"
    val a = ours.lowercase(Locale.ROOT)
    val b = ref.lowercase(Locale.ROOT)
    var p = 0
    while (p < a.length && p < b.length && a[p] == b[p]) p++
    var s = 0
    while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
    return "${a.substring(p, a.length - s)}→${b.substring(p, b.length - s)}"
}

private fun readTsv(path: String): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    File(path).forEachLine(Charsets.UTF_8) { line ->
        val tab = line.indexOf('\t')
        if (tab > 0) m[line.substring(0, tab)] = line.substring(tab + 1)
    }
    return m
}

private fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

/** Uzbek Cyrillic as typed on a Russian keyboard, which has no ў қ ғ ҳ. */
private fun russianKeyboard(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(
        when (c) {
            'ў' -> 'у'; 'Ў' -> 'У'; 'қ' -> 'к'; 'Қ' -> 'К'; 'ғ' -> 'г'; 'Ғ' -> 'Г'; 'ҳ' -> 'х'; 'Ҳ' -> 'Х'
            else -> c
        },
    )
    return sb.toString()
}

/** The Russian-keyboard spelling's key, as the second search tier computes it. */
private fun altKey(text: String): String = SearchText.foldPrepared(SearchText.russianKeyboard(SearchText.prepare(text))).key

private fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else String.format(Locale.ROOT, "%.2f%%", 100.0 * n / d)

/** Glossary rows (number, English, Uzbek Latin, Uzbek Cyrillic) from docs/design/i18n.md §3. */
private fun glossary(path: String) {
    var exact = 0
    var total = 0
    for (line in File(path).readLines(Charsets.UTF_8)) {
        val c = line.split('\t')
        if (c.size < 4) continue
        total++
        val ours = UzbekScript.latinToCyrillic(c[2])
        if (ours == c[3]) exact++ else println("  #${c[0]} ${c[2]} → $ours (glossary ${c[3]})")
    }
    println("glossary: $exact/$total rows exact = ${pct(exact, total)}")
}

fun main(args: Array<String>) {
    if (args.size == 2 && args[0] == "--glossary") return glossary(args[1])
    require(args.size >= 2) { "usage: ConverterEval outDir name:cyr.tsv:lat.tsv ..." }
    val outDir = File(args[0]).apply { mkdirs() }
    val report = StringBuilder()
    fun say(s: String) {
        println(s); report.append(s).append('\n')
    }

    for (spec in args.drop(1)) {
        val (name, cyrPath, latPath) = spec.split(":")
        val cyr = readTsv(cyrPath)
        val lat = readTsv(latPath)
        val pairs = ArrayList<Pair2>()
        var paragraphs = 0
        var skippedParagraphs = 0
        var skippedWords = 0
        for ((id, c) in cyr) {
            val l = lat[id] ?: continue
            paragraphs++
            val cw = words(c)
            val lw = words(l)
            if (cw.size != lw.size) {
                skippedParagraphs++; skippedWords += cw.size; continue
            }
            for (k in cw.indices) pairs.add(Pair2(cw[k], lw[k]))
        }
        // Words with digits or Latin letters inside Cyrillic text (and the reverse) are kept:
        // they are part of the text the converters must handle.
        val c2l = Tally()
        val l2c = Tally()
        val round = Tally()
        var keyEqual = 0
        var keyEqualNoApos = 0
        var rusTier1 = 0
        var rusTier2 = 0
        var yoTier2 = 0
        var yoWords = 0
        val keyMismatch = HashMap<String, Int>()
        val rusMiss = ArrayList<String>()
        for (p in pairs) {
            val ourLat = UzbekScript.cyrillicToLatin(p.cyr)
            val ourCyr = UzbekScript.latinToCyrillic(p.lat, keep = emptySet())
            c2l.add(p.cyr, ourLat, p.lat)
            l2c.add(p.lat, ourCyr, p.cyr)
            round.add(p.cyr, UzbekScript.latinToCyrillic(UzbekScript.cyrillicToLatin(p.cyr), keep = emptySet()), p.cyr)

            val kc = searchKey(p.cyr)
            val kl = searchKey(p.lat)
            if (kc == kl) keyEqual++ else {
                val line = "${p.cyr} → $kc / ${p.lat} → $kl"
                keyMismatch[line] = (keyMismatch[line] ?: 0) + 1
            }
            val latPlain = p.lat.filter { !UzbekScript.isApostrophe(it) }
            if (searchKey(latPlain) == kl) keyEqualNoApos++
            val typed = russianKeyboard(p.cyr)
            val rusKey = searchKey(typed)
            if (rusKey == kl) rusTier1++
            val rusAlt = altKey(typed)
            val officialAlt = altKey(p.lat)
            if (p.cyr.contains('ё') || p.cyr.contains('Ё')) {
                yoWords++
                val typedE = typed.replace('ё', 'е').replace('Ё', 'Е')
                if (searchKey(typedE) == kl || altKey(typedE) == officialAlt) yoTier2++
            }
            if (rusKey == kl || rusAlt == officialAlt) rusTier2++ else if (rusMiss.size < 10) rusMiss.add("${p.cyr} → $rusAlt / ${p.lat} → $officialAlt")
        }

        say("== $name: ${pairs.size} aligned words in $paragraphs paragraphs ($skippedParagraphs paragraphs, $skippedWords words skipped: word counts differ)")
        for ((label, t) in listOf("Cyrillic → Latin" to c2l, "Latin → Cyrillic" to l2c, "round trip Cyrillic → Latin → Cyrillic" to round)) {
            say(
                "  $label: words exact ${t.exact}/${t.tokens} = ${pct(t.exact, t.tokens)}; " +
                    "ignoring case ${pct(t.exact + t.caseOnly, t.tokens)}; " +
                    "distinct word pairs exact ${t.typesExact()}/${t.types.size} = ${pct(t.typesExact(), t.types.size)}",
            )
            for ((pat, n) in t.patterns.entries.sortedByDescending { it.value }.take(25)) {
                say("      ${n.toString().padStart(5)}  $pat   e.g. ${t.examples[pat]!!.take(3).joinToString("; ")}")
            }
        }
        say("  search: searchKey(Cyrillic) == searchKey(official Latin) for ${keyEqual}/${pairs.size} = ${pct(keyEqual, pairs.size)}")
        say("  search: Latin typed without any apostrophe gives the same key for ${pct(keyEqualNoApos, pairs.size)}")
        say(
            "  search: Cyrillic typed on a Russian keyboard (у к г х for ў қ ғ ҳ): same key as official Latin " +
                "${pct(rusTier1, pairs.size)}; found by the Russian-keyboard tier ${pct(rusTier2, pairs.size)}",
        )
        for ((line, n) in keyMismatch.entries.sortedByDescending { it.value }.take(15)) say("      ${n.toString().padStart(5)}  $line")
        say("  search: words with ё also typed with е (Russian habit) on that keyboard: found ${pct(yoTier2, yoWords)} of $yoWords")
        for (line in rusMiss) say("      Russian-keyboard miss: $line")

        for ((label, t) in listOf("c2l" to c2l, "l2c" to l2c)) {
            File(outDir, "$name-$label-errors.tsv").printWriter(Charsets.UTF_8).use { w ->
                for ((line, n) in t.errors.entries.sortedByDescending { it.value }) w.println("$n\t$line")
            }
        }
    }
    File(outDir, "summary.txt").writeText(report.toString(), Charsets.UTF_8)
}
