package io.github.dilshodbekmx.passwordvault.i18n

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The resource checks of i18n.md §5 item 2, which lint cannot make: lint's `MissingTranslation`
 * ignores `values-b+uz+Cyrl` (i18n.md §5 item 1), where a missing string shows English at run time
 * (§1.2), and no lint check knows our apostrophe and script rules. [ResourcesTest] runs them on the
 * app's resources, one test per language; [ResourceCheckTest] runs them on small made-up trees.
 *
 * For each language it reads every `*.xml` of its `values…` directory and reports:
 * - **missing** (a warning until wave 6, then a failure): a `<string>` or `<plurals>` of `values/`
 *   that is translatable (not `translatable="false"`, not in `donottranslate.xml`) and absent here;
 * - **failures**: a translation of another kind (string against plurals), with other placeholders
 *   (`%1$s`, `%d`: the same multiset, for plurals over all items), a `<plurals>` without the
 *   quantities of its language (Russian one, few, many, other; English and Uzbek one, other), a name
 *   that `values/` does not have or does not translate (except a library string overridden with
 *   `tools:override="true"`, i18n.md §1.4.6), a name defined twice, "..." instead of "…" (every
 *   language); in `values-uz` ' ‘ ’ ` after o, g, O or G or between two letters (only ʻ U+02BB and ʼ
 *   U+02BC are written, §2.4) and the letters ö ğ ş ç of the 2026 reform (§2.4); in
 *   `values-b+uz+Cyrl` a Latin letter outside [LATIN_IN_CYRILLIC]; in `values-ru` `\"` (Russian
 *   quotes are « ») and a capital "Вы" (or Вас, Вам, Ваш…) inside a sentence (§4.4).
 */
object ResourceCheck {
    /** One language's directory and the plural quantities its `<plurals>` must have (i18n.md §4.1). */
    enum class Language(val dir: String, val quantities: Set<String>) {
        EN("values", setOf("one", "other")),
        UZ_LATN("values-uz", setOf("one", "other")),
        UZ_CYRL("values-b+uz+Cyrl", setOf("one", "other")),
        RU("values-ru", setOf("one", "few", "many", "other")),
    }

    /** What the check found in one language's directory. */
    class Report(val language: Language, val checked: Int, val missing: List<String>, val failures: List<String>) {
        override fun toString(): String =
            "${language.dir}: $checked names checked, ${missing.size} missing, ${failures.size} failures"
    }

    /**
     * Latin text allowed in Uzbek Cyrillic (i18n.md §5): PIN, OK, the ranges A–Z and a–z, Latin
     * characters shown as themselves (the generator's look-alike characters, the qwerty key row), and
     * the names that are never translated (§3, end): the app's name, brands, file types. A text that
     * needs another adds it here with the string.
     */
    val LATIN_IN_CYRILLIC: List<String> = listOf(
        "PasswordVault", "Google Password Manager", "Google Drive", "Google", "Chrome", "Excel", "CSV", "JSON",
        "Telegram", "Gmail", "Click", "Payme", "Android", ".pvault", ".xlsx", ".csv", ".json", "PIN", "OK",
        "A–Z", "a–z",
        // Build plan 6.3a: edit_gen_look_alike names the Latin characters the generator leaves out, and
        // setup_own_hint_keyboard the English text's own example of a key row; in Cyrillic letters they
        // would name other characters.
        "I l 1 O 0", "qwerty",
    ).sortedByDescending { it.length }

    /** The checks for [language] over the resource directory [res] (`app/src/main/res`). */
    fun check(res: File, language: Language): Report {
        val failures = mutableListOf<String>()
        val base = Strings.read(File(res, Language.EN.dir), failures.takeIf { language == Language.EN })
        if (language == Language.EN) {
            for (entry in base.entries.values) {
                failures += quantityProblems(entry, language)
                failures += textProblems(entry, language)
            }
            return Report(language, base.entries.size, emptyList(), failures)
        }
        val here = Strings.read(File(res, language.dir), failures)
        val missing = mutableListOf<String>()
        for ((name, english) in base.entries) {
            if (!english.translatable) continue
            val translated = here.entries[name]
            when {
                translated == null -> missing += name
                translated.kind != english.kind ->
                    failures += "${translated.where}: a ${translated.kind} here, a ${english.kind} in values/"
                else -> {
                    val want = english.placeholders()
                    val got = translated.placeholders()
                    if (want != got) failures += "${translated.where}: placeholders $got, values/ has $want"
                    if (translated.kind == Kind.PLURALS) {
                        val all = english.items.values.flatMap(::placeholdersOf).toSet()
                        for ((quantity, text) in translated.items) {
                            val extra = placeholdersOf(text).toSet() - all
                            if (extra.isNotEmpty()) failures += "${translated.where} ($quantity): placeholders $extra are not in values/"
                        }
                    }
                }
            }
        }
        for ((name, translated) in here.entries) {
            val english = base.entries[name]
            if (translated.override) continue
            if (english == null) failures += "${translated.where}: values/ has no $name"
            else if (!english.translatable) failures += "${translated.where}: $name is not translated (translatable=\"false\" in values/)"
        }
        for (entry in here.entries.values) {
            if (entry.kind == Kind.PLURALS) failures += quantityProblems(entry, language)
            failures += textProblems(entry, language)
        }
        return Report(language, base.entries.count { it.value.translatable }, missing, failures)
    }

    private fun quantityProblems(entry: Entry, language: Language): List<String> {
        if (entry.kind != Kind.PLURALS) return emptyList()
        val lacking = language.quantities - entry.items.keys.filterNotNull().toSet()
        return if (lacking.isEmpty()) emptyList() else listOf("${entry.where}: <plurals> without ${lacking.sorted()}")
    }

    private fun textProblems(entry: Entry, language: Language): List<String> = entry.items.flatMap { (quantity, raw) ->
        val at = entry.where + (quantity?.let { " ($it)" } ?: "")
        val text = unescape(raw)
        buildList {
            if ("..." in text) add("$at: \"...\" instead of \"…\"")
            when (language) {
                Language.UZ_LATN -> {
                    APOSTROPHE_AFTER_O_G.find(text)?.let { add("$at: \"${it.value}\": write oʻ and gʻ with ʻ (U+02BB)") }
                    APOSTROPHE_AS_TUTUQ.find(text)?.let { add("$at: \"${it.value}\": write the tutuq belgisi with ʼ (U+02BC)") }
                    REFORM_LETTERS.find(text)?.let { add("$at: \"${it.value}\": the letters of the 2026 reform are not used yet (i18n.md §2.4)") }
                }
                Language.UZ_CYRL -> {
                    var rest = PLACEHOLDER.replace(text, " ")
                    for (allowed in LATIN_IN_CYRILLIC) rest = rest.replace(allowed, " ")
                    LATIN.find(rest)?.let { add("$at: Latin letters \"${it.value}\" in Uzbek Cyrillic") }
                }
                Language.RU -> {
                    if ("\\\"" in raw) add("$at: \\\" in Russian: use « »")
                    for (match in CAPITAL_VY.findAll(text)) {
                        if (insideSentence(text, match.range.first)) add("$at: \"${match.value}\" inside a sentence: write вы in lower case")
                    }
                }
                Language.EN -> Unit
            }
        }
    }

    /** Whether the text before [at] continues a sentence: anything but its start, a line break, or . ! ? … with spaces or quotes. */
    private fun insideSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i].isWhitespace() && text[i] != '\n' || text[i] in "«„“\"'(")) i--
        return i >= 0 && text[i] != '\n' && text[i] !in ".!?…"
    }

    /** The text as Android shows it: `\n`, `\t`, `\uXXXX` and `\x` resolved, the quotes of `"…"` dropped. */
    fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    when {
                        next == 'n' -> out.append('\n')
                        next == 't' -> out.append('\t')
                        next == 'u' && i + 6 <= raw.length && raw.substring(i + 2, i + 6).all { it in HEX } -> {
                            out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(next)
                    }
                    i += 2
                }
                c == '"' -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The format placeholders of [raw], in order: `%1$s`, `%d`, `%2$.1f`; not `%%` or `%n`. */
    fun placeholdersOf(raw: String): List<String> =
        PLACEHOLDER.findAll(raw).map { it.value }.filter { it != "%%" && it != "%n" }.toList()

    private val PLACEHOLDER = Regex("""%(\d+\$)?[-#+0,(<]*\d*(\.\d+)?[a-zA-Z%]""")
    private val APOSTROPHE_AFTER_O_G = Regex("[oOgG]['‘’`]")
    private val APOSTROPHE_AS_TUTUQ = Regex("""[\p{L}&&[^oOgG]]['‘’`]\p{L}""")
    private const val HEX = "0123456789abcdefABCDEF"
    private val REFORM_LETTERS = Regex("[öÖğĞşŞçÇ]")
    private val LATIN = Regex("""\p{IsLatin}+""")
    private val CAPITAL_VY = Regex("""(?<![\p{L}\p{N}])(Вы|Вас|Вам|Вами|Ваш|Ваша|Ваше|Ваши|Вашего|Вашей|Вашему|Вашим|Вашими|Ваших|Вашу)(?![\p{L}\p{N}])""")

    enum class Kind { STRING, PLURALS, ARRAY }

    /**
     * One name of one directory: its kind, where it is, and its items as written in the file (a
     * string has one item with a null quantity; plurals by quantity; an array by position).
     */
    class Entry(
        val name: String,
        val kind: Kind,
        val where: String,
        val translatable: Boolean,
        val override: Boolean,
        val items: Map<String?, String>,
    ) {
        /** Every placeholder of the entry, sorted: a string's, or all of its plural items' together, each once. */
        fun placeholders(): List<String> = when (kind) {
            Kind.PLURALS -> items.values.flatMap(::placeholdersOf).distinct().sorted()
            else -> items.values.flatMap(::placeholdersOf).sorted()
        }
    }

    /** The `<string>`, `<plurals>` and `<string-array>` of one `values…` directory. */
    class Strings(val entries: Map<String, Entry>) {
        companion object {
            /** Reads [dir] (none when it does not exist); a name defined twice goes to [failures]. */
            fun read(dir: File, failures: MutableList<String>?): Strings {
                val entries = LinkedHashMap<String, Entry>()
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
                for (file in files) {
                    val doNotTranslate = file.name == "donottranslate.xml"
                    for (element in elementsOf(file)) {
                        val kind = when (element.tagName) {
                            "string" -> Kind.STRING
                            "plurals" -> Kind.PLURALS
                            "string-array" -> Kind.ARRAY
                            else -> continue
                        }
                        val name = element.getAttribute("name")
                        val items: Map<String?, String> = when (kind) {
                            Kind.STRING -> mapOf(null to element.textContent)
                            Kind.PLURALS -> children(element, "item").associate { it.getAttribute("quantity") to it.textContent }
                            Kind.ARRAY -> children(element, "item").mapIndexed { i, it -> i.toString() to it.textContent }.toMap()
                        }
                        val entry = Entry(
                            name = name,
                            kind = kind,
                            where = "${dir.name}/${file.name} $name",
                            translatable = !doNotTranslate && element.getAttribute("translatable") != "false",
                            override = element.getAttributeNS(TOOLS, "override") == "true",
                            items = items,
                        )
                        if (name in entries) failures?.add("${entry.where}: defined twice in ${dir.name}")
                        entries[name] = entry
                    }
                }
                return Strings(entries)
            }

            private const val TOOLS = "http://schemas.android.com/tools"

            private fun elementsOf(file: File): List<Element> {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                val root = factory.newDocumentBuilder().parse(file).documentElement
                return children(root, null)
            }

            private fun children(parent: Element, tag: String?): List<Element> =
                (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
                    .filterIsInstance<Element>().filter { tag == null || it.tagName == tag }
        }
    }
}
