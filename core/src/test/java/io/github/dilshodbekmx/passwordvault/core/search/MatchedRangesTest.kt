package io.github.dilshodbekmx.passwordvault.core.search

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The characters of the original text that [matchedRanges] marks bold. */
@RunWith(Parameterized::class)
class MatchedRangesTest(private val text: String, private val query: String, private val bold: List<String>) {

    @Test
    fun ranges() = assertEquals(bold, matchedRanges(text, query).map { text.substring(it.first, it.last + 1) })

    companion object {
        private fun c(text: String, query: String, vararg bold: String) = arrayOf<Any>(text, query, bold.toList())

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0} / {1}")
        fun data(): List<Array<Any>> = listOf(
            c("Ўзбектелеком", "o'zbek", "Ўзбек"),
            c("O\u02BBzbekiston Temir", "ozbek", "O\u02BBzbek"),
            c("Click Uz", "click uz", "Click", "Uz"),
            c("Click Uz", "uz click", "Click", "Uz"),
            c("Сбербанк Онлайн", "сбер", "Сбер"),
            c("Сбербанк Онлайн", "bank", "банк"),
            c("Café Central", "cafe", "Café"),
            c("Cafe\u0301 Central", "cafe", "Cafe\u0301"),
            c("Telegram", "телеграм", "Telegram"),
            c("Узбектелеком кабинет", "o'zbek", "Узбек"),
            c("Mail.ru", "mailru", "Mail.ru"),
            c("Toşkent metrosi", "toshkent", "Toşkent"),
            c("Шаҳар", "s", "Ш"),
            c("Qo'qon kolleji", "qo", "Qo'"),
            c("Ипак Йўли", "ipak yo'li", "Ипак", "Йўли"),
            c("Hisob 2024", "2024", "2024"),
            c("Hisob 2024", "zzz"),
            c("Hisob 2024", ""),
            c("", "x"),
            c("🔑 Home Wi-Fi", "wifi", "Wi-Fi"),
        )
    }
}
