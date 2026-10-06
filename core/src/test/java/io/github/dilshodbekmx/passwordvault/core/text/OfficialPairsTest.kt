package io.github.dilshodbekmx.passwordvault.core.text

import io.github.dilshodbekmx.passwordvault.core.search.searchKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Words taken from the two official editions of the Constitution of Uzbekistan on lex.uz
 * (Cyrillic: lex.uz/docs/6445145, Latin: lex.uz/docs/-6445145). Each pair is checked three
 * ways: Cyrillic to Latin, Latin to Cyrillic, and equal search keys.
 */
@RunWith(Parameterized::class)
class OfficialPairsTest(private val cyrillic: String, private val latin: String, private val check: String) {

    @Test
    fun pair() = when (check) {
        "cyrillicToLatin" -> assertEquals(latin, UzbekScript.cyrillicToLatin(cyrillic))
        "latinToCyrillic" -> assertEquals(cyrillic, UzbekScript.latinToCyrillic(latin, keep = emptySet()))
        else -> assertEquals(searchKey(latin), searchKey(cyrillic))
    }

    companion object {
        private val PAIRS = listOf(
            "Ўзбекистон" to "Oʻzbekiston",
            "Республикаси" to "Respublikasi",
            "Конституциявий" to "Konstitutsiyaviy",
            "принципларига" to "prinsiplariga",
            "коррупцияга" to "korrupsiyaga",
            "Цензурага" to "Senzuraga",
            "процессуал" to "protsessual",
            "цивилизациясига" to "sivilizatsiyasiga",
            "фракциялари" to "fraksiyalari",
            "денонсация" to "denonsatsiya",
            "маъмурий" to "maʼmuriy",
            "эълон" to "eʼlon",
            "аъзолари" to "aʼzolari",
            "истеъфога" to "isteʼfoga",
            "умумэътироф" to "umumeʼtirof",
            "объектларини" to "obyektlarini",
            "субъектидир" to "subyektidir",
            "анъанавий" to "anʼanaviy",
            "судьялар" to "sudyalar",
            "июль" to "iyul",
            "октябрь" to "oktabr",
            "гигиена" to "gigiyena",
            "бюджетини" to "budjetini",
            "Қорақалпоғистон" to "Qoraqalpogʻiston",
            "ҳуқуқлари" to "huquqlari",
            "ЎЗБЕКИСТОН" to "OʻZBEKISTON",
            "БЎЛИМ" to "BOʻLIM",
            "ТЎРТИНЧИ" to "TOʻRTINCHI",
            "ИККИНЧИ" to "IKKINCHI",
            "эркинликлари" to "erkinliklari",
            "ижтимоий" to "ijtimoiy",
            "ҳокимиятчилиги" to "hokimiyatchiligi",
            "мавқеидан" to "mavqeyidan",
            "қатъий" to "qatʼiy",
            "таъминлайди" to "taʼminlaydi",
            "масъулиятимизни" to "masʼuliyatimizni",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0} / {1} {2}")
        fun data(): List<Array<Any>> = PAIRS.flatMap { (c, l) ->
            listOf("cyrillicToLatin", "latinToCyrillic", "searchKey").map { arrayOf<Any>(c, l, it) }
        }
    }
}
