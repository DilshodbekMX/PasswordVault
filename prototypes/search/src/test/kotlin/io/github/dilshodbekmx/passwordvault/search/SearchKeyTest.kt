package io.github.dilshodbekmx.passwordvault.search

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** searchKey(input) == expected. The build runs these tests with a Turkish default locale. */
@RunWith(Parameterized::class)
class SearchKeyTest(private val label: String, private val input: String, private val expected: String) {

    @Test
    fun key() = assertEquals(label, expected, searchKey(input))

    companion object {
        private fun case(label: String, input: String, expected: String) = arrayOf<Any>(label, input, expected)

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): List<Array<Any>> = listOf(
            // The user's examples: every way of typing oʻzbek gives one key.
            case("ASCII apostrophe U+0027", "o'zbek", "ozbek"),
            case("turned comma U+02BB", "o\u02BBzbek", "ozbek"),
            case("left quote U+2018", "o\u2018zbek", "ozbek"),
            case("right quote U+2019", "o\u2019zbek", "ozbek"),
            case("modifier apostrophe U+02BC", "o\u02BCzbek", "ozbek"),
            case("grave accent U+0060", "o`zbek", "ozbek"),
            case("acute accent U+00B4 (NFKC would make it a space)", "o\u00B4zbek", "ozbek"),
            case("reversed comma U+02BD", "o\u02BDzbek", "ozbek"),
            case("prime U+2032", "o\u2032zbek", "ozbek"),
            case("no apostrophe", "ozbek", "ozbek"),
            case("Cyrillic", "ўзбек", "ozbek"),
            case("Cyrillic capitals", "ЎЗБЕК", "ozbek"),
            case("Cyrillic decomposed (У + breve)", "\u0423\u0306збек", "ozbek"),
            case("2026 alphabet ö", "Özbek", "ozbek"),
            case("whole name, Cyrillic", "Ўзбекистон", "ozbekiston"),
            case("whole name, Latin capitals", "O\u02BBZBEKISTON", "ozbekiston"),
            case("gʻ with U+2018", "G\u2018alaba", "galaba"),
            case("ғ", "Ғалаба", "galaba"),
            case("2026 alphabet ğ", "Ğalaba", "galaba"),
            // Uzbek letters and the official Cyrillic -> Latin rules
            case("sh", "Toshkent", "toshkent"),
            case("ш", "Тошкент", "toshkent"),
            case("2026 alphabet ş", "Toşkent", "toshkent"),
            case("ch", "Choy", "choy"),
            case("2026 alphabet ç", "Çoy", "choy"),
            case("ч", "Чой", "choy"),
            case("q and oʻ", "Qo\u2018qon", "qoqon"),
            case("қ and ў", "Қўқон", "qoqon"),
            case("ҳ is h, ё is yo", "Ҳаёт", "hayot"),
            case("х is x", "Хива", "xiva"),
            case("ж is j", "Жиззах", "jizzax"),
            case("ю is yu", "Юлдуз", "yulduz"),
            case("е at word start is ye", "Ер", "yer"),
            case("е after a vowel is ye", "поезд", "poyezd"),
            case("е after a consonant is e", "кеча", "kecha"),
            case("э is e", "Эшик", "eshik"),
            case("ъ removed", "маълумот", "malumot"),
            case("tutuq removed", "Ma\u2019lumot", "malumot"),
            case("ъ in a Russian loanword", "Объект", "obyekt"),
            case("ъ in capitals", "СЪЕЗД", "syezd"),
            case("сҳ", "Исҳоқ", "ishoq"),
            case("sʼh", "Is\u2019hoq", "ishoq"),
            case("ц after a consonant is s", "Принцип", "prinsip"),
            case("official Latin of принцип", "Prinsip", "prinsip"),
            case("ц after a vowel is ts", "Конституция", "konstitutsiya"),
            case("ц at word start is s", "Цирк", "sirk"),
            case("Russian brand", "Сбербанк", "sberbank"),
            case("Russian brand with е after a consonant", "Яндекс", "yandeks"),
            case("щ is sh", "Щука", "shuka"),
            case("ы is i, ь removed", "Мысль", "misl"),
            case("ьо is yo", "медальон", "medalyon"),
            case("English brand in Cyrillic", "телеграм", "telegram"),
            case("English brand in Cyrillic capitals", "ТЕЛЕГРАМ", "telegram"),
            case("йў", "Йўл", "yol"),
            case("ё", "ёлка", "yolka"),
            case("official month spelling", "Сентябрь", "sentabr"),
            case("ъ after ў", "Мўъжиза", "mojiza"),
            case("ъ after a consonant before и", "Қатъий", "qatiy"),
            case("decomposed й (и + breve) stays й", "\u0438\u0306од", "yod"),
            case("decomposed ё (е + diaeresis) stays ё", "\u0435\u0308лка", "yolka"),
            case("initials", "Ш.Мирзиёев", "sh.mirziyoyev"),
            case("two words", "Ўзбекистон Республикаси", "ozbekiston respublikasi"),
            case("gʻ inside a word", "Қорақалпоғистон", "qoraqalpogiston"),
            // Latin accents and case folding
            case("é", "café", "cafe"),
            case("decomposed é", "cafe\u0301", "cafe"),
            case("capital É", "CAFÉ", "cafe"),
            case("ö (German)", "Köln", "koln"),
            case("ß", "Straße", "strasse"),
            case("capital dotted İ", "İstanbul", "istanbul"),
            case("dotless ı", "ıstanbul", "istanbul"),
            case("I under a Turkish default locale", "TITLE", "title"),
            case("ł and ź", "Łódź", "lodz"),
            case("æ and ø", "Ærø", "aero"),
            case("ñ and ú", "Ñandú", "nandu"),
            case("stacked marks", "Việt Nam", "viet nam"),
            case("title-case digraph ǅ", "\u01C5emal", "dzemal"),
            case("ç is the Uzbek ch (French words pay for it)", "Garçon", "garchon"),
            // Compatibility forms
            case("full-width letters", "Ｔｅｌｅｇｒａｍ", "telegram"),
            case("ligature ﬁ", "\uFB01le", "file"),
            case("Roman numeral sign", "\u216B", "xii"),
            case("superscript digit", "x\u00B2", "x2"),
            case("Arabic-Indic digits", "\u0661\u0662\u0663", "123"),
            case("full-width digits", "１２３", "123"),
            // White space and invisible characters
            case("spaces, tab and newline", "  Click \t Uz\n", "click uz"),
            case("no-break space", "Click\u00A0Uz", "click uz"),
            case("ideographic space", "Click\u3000Uz", "click uz"),
            case("zero-width space", "Tele\u200Bgram", "telegram"),
            case("soft hyphen", "Ўзбек\u00ADтелеком", "ozbektelekom"),
            case("direction marks", "\u200FPayme\u200E", "payme"),
            case("empty", "", ""),
            case("only spaces", "   ", ""),
            case("only apostrophes", "'\u02BB\u2019", ""),
            // Mixed scripts, digits, emoji, other scripts
            case("Latin and Cyrillic in one title", "Click Пэй", "click pey"),
            case("Latin and Cyrillic, Russian word", "Uzum Маркет", "uzum market"),
            case("digits", "Hisob 2024", "hisob 2024"),
            case("emoji kept", "🔑 Bank", "🔑 bank"),
            case("emoji variation selector removed", "❤\uFE0F Love", "❤ love"),
            case("emoji zero-width joiners removed", "👨\u200D👩\u200D👧 Family", "👨👩👧 family"),
            case("Chinese", "微信", "微信"),
            case("Chinese, longer", "中国银行", "中国银行"),
            case("Arabic", "بنك", "بنك"),
            case("Arabic short vowels removed", "\u0628\u064E\u0646\u0652\u0643", "\u0628\u0646\u0643"),
            case("Devanagari vowel signs kept", "नमस्ते", "नमस्ते"),
            case("Greek tonos removed", "Αθήνα", "αθηνα"),
            case("Greek capital sigma", "ΟΔΟΣ", "οδοσ"),
            case("Greek final sigma", "οδος", "οδοσ"),
            case("punctuation kept", "Mail.ru", "mail.ru"),
            case("hyphen kept", "e-mail", "e-mail"),
            case("e-mail address", "User@Example.com", "user@example.com"),
            // Neighbouring Cyrillic alphabets (search only)
            case("Karakalpak ө", "Нөкис", "nokis"),
            case("Karakalpak Latin ó", "Nókis", "nokis"),
            case("Tajik ҷ and ӣ", "Ҷумҳурӣ", "jumhuri"),
            case("Ukrainian ї", "Київ", "kiyiv"),
        )
    }
}
