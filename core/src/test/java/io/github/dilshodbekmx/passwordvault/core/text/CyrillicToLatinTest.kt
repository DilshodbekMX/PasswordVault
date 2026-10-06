package io.github.dilshodbekmx.passwordvault.core.text

import io.github.dilshodbekmx.passwordvault.core.text.UzbekLatinAlphabet.OFFICIAL_1995
import io.github.dilshodbekmx.passwordvault.core.text.UzbekLatinAlphabet.REFORM_2026
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Expected Latin uses U+02BB in oʻ/gʻ and U+02BC for the tutuq belgisi, as lex.uz does. */
@RunWith(Parameterized::class)
class CyrillicToLatinTest(
    private val cyrillic: String,
    private val latin: String,
    private val alphabet: UzbekLatinAlphabet,
) {

    @Test
    fun convert() = assertEquals(latin, UzbekScript.cyrillicToLatin(cyrillic, alphabet))

    companion object {
        private fun c(cyr: String, lat: String, a: UzbekLatinAlphabet = OFFICIAL_1995) = arrayOf<Any>(cyr, lat, a)

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0} {2}")
        fun data(): List<Array<Any>> = listOf(
            c("Ўзбекистон", "Oʻzbekiston"),
            c("ЎЗБЕКИСТОН", "OʻZBEKISTON"),
            c("ўзбек", "oʻzbek"),
            c("Шаҳар", "Shahar"),
            c("ШАҲАР", "SHAHAR"),
            c("Тошкент", "Toshkent"),
            c("ТОШКЕНТ", "TOSHKENT"),
            c("Ер", "Yer"),
            c("ЕР", "YER"),
            c("ер ости", "yer osti"),
            c("поезд", "poyezd"),
            c("кеча", "kecha"),
            c("эшик", "eshik"),
            c("Эълон", "Eʼlon"),
            c("маъно", "maʼno"),
            c("Исҳоқ", "Isʼhoq"),
            c("цирк", "sirk"),
            c("Цирк", "Sirk"),
            c("ЦЕНЗУРА", "SENZURA"),
            c("принцип", "prinsip"),
            c("Конституция", "Konstitutsiya"),
            c("процессуал", "protsessual"),
            c("коррупция", "korrupsiya"),
            c("Швейцария", "Shveysariya"),
            c("ғалаба", "gʻalaba"),
            c("Ғулом", "Gʻulom"),
            c("йўл", "yoʻl"),
            c("ёз", "yoz"),
            c("Ёшлар", "Yoshlar"),
            c("ЁШЛАР", "YOSHLAR"),
            c("юлдуз", "yulduz"),
            c("Ялпиз", "Yalpiz"),
            c("қўшиқ", "qoʻshiq"),
            c("Хива", "Xiva"),
            c("ҳаёт", "hayot"),
            c("объект", "obyekt"),
            c("субъект", "subyekt"),
            c("мўъжиза", "moʻjiza"),
            c("анъанавий", "anʼanaviy"),
            c("компьютер", "kompyuter"),
            c("медальон", "medalyon"),
            c("судьялар", "sudyalar"),
            c("Октябрь", "Oktabr"),
            c("сентябрда", "sentabrda"),
            c("июль", "iyul"),
            c("бюджет", "budjet"),
            c("гигиена", "gigiyena"),
            c("умумэътироф", "umumeʼtirof"),
            c("Ч", "Ch"),
            c("ЧОРСУ", "CHORSU"),
            c("Ш.Мирзиёев", "Sh.Mirziyoyev"),
            c("Ўзбекистон Республикаси Конституцияси", "Oʻzbekiston Respublikasi Konstitutsiyasi"),
            c("2023 йил 30 апрель", "2023 yil 30 aprel"),
            c("Telegram ва WhatsApp", "Telegram va WhatsApp"),
            c("щётка", "shyotka"),
            c("Рыбинск", "Ribinsk"),
            c("Нөкис", "Nөkis"), // letters of other alphabets are left for a person to decide
            c("\u0438\u0306\u0443\u0306л", "yoʻl"), // decomposed input
            // The 2026 alphabet (provisional until its spelling rules are published)
            c("Ўзбекистон", "Özbekiston", REFORM_2026),
            c("ЎЗБЕКИСТОН", "ÖZBEKISTON", REFORM_2026),
            c("Шаҳар", "Şahar", REFORM_2026),
            c("ЧОРСУ", "ÇORSU", REFORM_2026),
            c("ғалаба", "ğalaba", REFORM_2026),
            c("Исҳоқ", "Ishoq", REFORM_2026),
            c("маъно", "maʼno", REFORM_2026),
        )
    }
}
