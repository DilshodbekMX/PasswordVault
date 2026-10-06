package io.github.dilshodbekmx.passwordvault.search

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Each query must find the entry at [expected] in [SampleVault]. */
@RunWith(Parameterized::class)
class FindsEntryTest(private val query: String, private val expected: Int) {

    @Test
    fun finds() {
        val found = SampleVault.indices(query)
        assertTrue("\"$query\" should find ${SampleVault.entries[expected].title}, found $found", expected in found)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: \"{0}\" finds #{1}")
        fun data(): List<Array<Any>> = listOf(
            // The user's examples (entry 1 is Ўзбектелеком)
            "o'zbek" to 1, "o\u02BBzbek" to 1, "o\u2018zbek" to 1, "o\u2019zbek" to 1, "o\u02BCzbek" to 1,
            "o`zbek" to 1, "ozbek" to 1, "ўзбек" to 1, "ЎЗБЕК" to 1, "özbek" to 1, "uzbek" to 1,
            "telegram" to 2, "телеграм" to 2, "ТЕЛЕГРАМ" to 2, "Telegram" to 2,
            "сбер" to 3, "sber" to 3, "онлайн" to 3, "onlayn" to 3,
            "click" to 4, "clickuz" to 4, "my.click" to 4,
            "payme" to 5, "998901234567" to 5, "901234567" to 2, "+998 90 123" to 2,
            "gmail.com" to 6, "google" to 6,
            "g'alaba" to 7, "ғалаба" to 7, "галаба" to 7, "galaba" to 7, "ğalaba" to 7,
            "яндекс" to 8, "yandeks" to 8,
            "kapital" to 9, "капитал" to 9,
            "uzum" to 10, "узум" to 10,
            "xizmat" to 11, "хизмат" to 11, "my.gov" to 11, "AA123" to 11,
            "ipak yo'li" to 12, "ипак йўли" to 12, "ипак йули" to 12, "ipak yoli" to 12,
            "mail.ru" to 13,
            "微信" to 14, "wechat" to 14,
            "الراجحي" to 15,
            "🔑" to 16, "wi-fi" to 16, "wifi" to 16, "router" to 16,
            "cafe" to 17, "CAFÉ" to 17, "cafe-central" to 17,
            "2024" to 18, "hisob" to 18, "ҳисоб" to 18, "хисоб" to 18,
            "qo'qon" to 19, "қўқон" to 19, "кукон" to 19, "qoqon" to 19,
            "ёлка" to 20, "елка" to 20, "yolka" to 20,
            "узбек" to 21, "uzbektelekom" to 21,
            "özbekiston" to 22, "o'zbekiston" to 22, "ўзбекистон" to 22,
            "toşkent" to 23, "toshkent" to 23, "тошкент" to 23,
            "code word" to 24,
            "pay me" to 25,
        ).map { arrayOf<Any>(it.first, it.second) }
    }
}
