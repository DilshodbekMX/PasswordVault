package io.github.dilshodbekmx.passwordvault.text

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LatinToCyrillicTest(private val label: String, private val latin: String, private val cyrillic: String) {

    @Test
    fun convert() = assertEquals(label, cyrillic, UzbekScript.latinToCyrillic(latin))

    companion object {
        private fun c(label: String, lat: String, cyr: String) = arrayOf<Any>(label, lat, cyr)

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): List<Array<Any>> = listOf(
            // oʻ and gʻ in every apostrophe variant
            c("U+02BB", "O\u02BBzbekiston", "Ўзбекистон"),
            c("ASCII '", "O'zbekiston", "Ўзбекистон"),
            c("U+2018", "O\u2018zbekiston", "Ўзбекистон"),
            c("U+2019", "O\u2019zbekiston", "Ўзбекистон"),
            c("U+02BC", "O\u02BCzbekiston", "Ўзбекистон"),
            c("grave", "O`zbekiston", "Ўзбекистон"),
            c("acute", "O\u00B4zbekiston", "Ўзбекистон"),
            c("capitals Oʻ", "O\u02BBZBEKISTON", "ЎЗБЕКИСТОН"),
            c("gʻ", "g\u02BBalaba", "ғалаба"),
            c("Gʻ with ASCII '", "G'alaba", "Ғалаба"),
            c("gʻ at the end of a word", "tog\u02BB", "тоғ"),
            c("gʻ at the end, U+2018", "bog\u2018", "боғ"),
            // sh, ch, ng, and capitals
            c("Sh", "Shahar", "Шаҳар"),
            c("SH", "SHAHAR", "ШАҲАР"),
            c("Ch", "Choy", "Чой"),
            c("CH", "CHOY", "ЧОЙ"),
            c("ng", "tong", "тонг"),
            c("n + gʻ is not ng", "mang\u02BBit", "манғит"),
            c("sh then h", "mashhur", "машҳур"),
            c("sʼh is с + ҳ", "Is\u02BChoq", "Исҳоқ"),
            c("s'h with ASCII apostrophe", "Is'hoq", "Исҳоқ"),
            // e and ye
            c("e at word start is э", "eshik", "эшик"),
            c("E at word start", "Eshik", "Эшик"),
            c("e after a consonant is е", "kecha", "кеча"),
            c("e after a vowel is э", "poeziya", "поэзия"),
            c("e after a vowel, loanword", "aeroport", "аэропорт"),
            c("ye at word start is е", "yer", "ер"),
            c("Ye at word start", "Yer", "Ер"),
            c("ye after a vowel is е", "poyezd", "поезд"),
            c("ye after i", "gigiyena", "гигиена"),
            c("ye after a consonant is ъе", "obyekt", "объект"),
            c("e after a consonant in a compound (listed)", "umume\u02BCtirof", "умумэътироф"),
            // yo, yu, ya
            c("yo", "yoz", "ёз"),
            c("yu", "yulduz", "юлдуз"),
            c("ya", "yangi", "янги"),
            c("yoʻ is й + ў", "yo\u02BBl", "йўл"),
            c("Yoʻ capital", "Yo\u2018q", "Йўқ"),
            c("yy + o", "tayyor", "тайёр"),
            // tutuq belgisi
            c("tutuq U+02BC", "ma\u02BCno", "маъно"),
            c("tutuq ASCII", "ma'lumot", "маълумот"),
            c("tutuq after e", "e\u02BClon", "эълон"),
            c("tutuq in capitals", "MA\u02BCMURIY", "МАЪМУРИЙ"),
            c("tutuq U+2019", "ta\u2019lim", "таълим"),
            // Other letters
            c("x and h", "Xiva hayoti", "Хива ҳаёти"),
            c("q", "qo\u02BBshiq", "қўшиқ"),
            c("j", "Jizzax", "Жиззах"),
            c("Qoraqalpogʻiston", "Qoraqalpog\u02BBiston", "Қорақалпоғистон"),
            // ц in loanwords
            c("-tsiya", "Konstitutsiya", "Конституция"),
            c("-tsion", "operatsion", "операцион"),
            c("-tsiyent", "koeffitsiyent", "коэффициент"),
            c("-ksiya", "funksiya", "функция"),
            c("-nsiya", "stansiya", "станция"),
            c("-psiya", "korrupsiya", "коррупция"),
            c("-nsiya with с (listed)", "pensiya", "пенсия"),
            c("-rsiya stays с", "versiya", "версия"),
            c("-ssiya stays с", "Rossiya", "Россия"),
            c("listed stem prinsip", "prinsiplariga", "принципларига"),
            c("listed stem litsenz", "Litsenziya", "Лицензия"),
            c("listed stem protsess", "protsessual", "процессуал"),
            c("ts in a native word stays тс", "ketsa", "кетса"),
            c("ts before -siz stays тс", "otsiz", "отсиз"),
            // ь and ъ before я, ю, ё, months
            c("listed sudya", "sudyalar", "судьялар"),
            c("listed kompyuter", "kompyuter", "компьютер"),
            c("month alone keeps ь", "aprel", "апрель"),
            c("month with a suffix has no ь", "aprelda", "апрелда"),
            c("oktabr alone", "oktabr", "октябрь"),
            c("oktabr with a suffix", "oktabrda", "октябрда"),
            c("budjet", "budjetini", "бюджетини"),
            // The 2026 alphabet as input
            c("ö", "Özbekiston", "Ўзбекистон"),
            c("ş", "Şahar", "Шаҳар"),
            c("ç", "Çoy", "Чой"),
            c("ğ", "Ğalaba", "Ғалаба"),
            // What a UI string contains
            c("placeholder kept", "%1\$s ta yozuv topildi", "%1\$s та ёзув топилди"),
            c("number placeholder kept", "%d ta parol", "%d та парол"),
            c("percent sign kept", "100% xavfsiz", "100% хавфсиз"),
            c("escape kept", "Birinchi qator\\nIkkinchi", "Биринчи қатор\\nИккинчи"),
            c("markup kept", "<b>Diqqat:</b> parol", "<b>Диққат:</b> парол"),
            c("ICU argument kept", "Salom, {name}!", "Салом, {name}!"),
            c(
                "ICU plural: syntax kept, messages converted",
                "{count, plural, =1 {# ta ilova} other {# ta ilova}}",
                "{count, plural, =1 {# та илова} other {# та илова}}",
            ),
            c(
                "ICU plural with a nested argument",
                "{count, plural, one {Bir ilova} other {{count} ta ilova}}",
                "{count, plural, one {Бир илова} other {{count} та илова}}",
            ),
            c("brand kept", "Google hisobi", "Google ҳисоби"),
            c("brand with a suffix", "Googleda", "Googleда"),
            c("hyphenated brand kept", "Wi-Fi paroli", "Wi-Fi пароли"),
            c("quotes stay quotes", "\u2018Saqlash\u2019 tugmasini bosing", "\u2018Сақлаш\u2019 тугмасини босинг"),
            c("Roman numeral kept", "XIV bob", "XIV боб"),
            c("letters outside the alphabet kept for review (Latin c)", "Microsoft", "Ми" + "c" + "рософт"),
            c("sentence", "Bekor qilish", "Бекор қилиш"),
            c("sentence 2", "Avtomatik to\u02BBldirish", "Автоматик тўлдириш"),
            c("Eksport", "Eksport", "Экспорт"),
            c("Xavfsizlik", "Xavfsizlik", "Хавфсизлик"),
            c("Sinxronizatsiya", "Sinxronizatsiya", "Синхронизация"),
        )
    }
}
