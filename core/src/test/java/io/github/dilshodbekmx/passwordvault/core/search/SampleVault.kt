package io.github.dilshodbekmx.passwordvault.core.search

/** A small vault like the user's: Uzbek Latin and Cyrillic, Russian and English names. */
object SampleVault {
    val entries: List<SearchFields> = listOf(
        SearchFields("O\u02BBzbekiston Temir Yo\u02BBllari", "dilshod", listOf("https://railway.uz/uz/")), // 0
        SearchFields("Ўзбектелеком", "dilshod@mail.uz", listOf("https://uztelecom.uz")), // 1
        SearchFields("Telegram", "+998 90 123 45 67", listOf("https://web.telegram.org")), // 2
        SearchFields("Сбербанк Онлайн", "dilshod.m", listOf("https://online.sberbank.ru/CSAFront/index.do")), // 3
        SearchFields("Click Uz", "998901234567", listOf("https://my.click.uz")), // 4
        SearchFields("Payme", "998901234567", listOf("payme.uz")), // 5
        SearchFields("Gmail", "dilshod@gmail.com", listOf("https://accounts.google.com/")), // 6
        SearchFields("G'alaba fitnes", "dilshod"), // 7
        SearchFields("Яндекс Почта", "dilshod"), // 8
        SearchFields("Kapitalbank", "dilshod", listOf("kapitalbank.uz")), // 9
        SearchFields("Uzum Bank", "dilshod", listOf("uzumbank.uz")), // 10
        SearchFields("Davlat xizmatlari markazi", "AA1234567", listOf("https://my.gov.uz")), // 11
        SearchFields("Ипак Йўли банки", "dilshod", listOf("ipakyulibank.uz")), // 12
        SearchFields("Mail.ru", "dilshod@mail.ru", listOf("mail.ru")), // 13
        SearchFields("微信 WeChat", "dilshod_wx"), // 14
        SearchFields("بنك الراجحي", "dilshod"), // 15
        SearchFields("🔑 Home Wi-Fi", "", notes = "router in the hall"), // 16
        SearchFields("Café Central", "dilshod@example.com", listOf("https://www.cafe-central.at/menu")), // 17
        SearchFields("Hisob 2024", "dilshod"), // 18
        SearchFields("Qo\u02BBqon kolleji", "student"), // 19
        SearchFields("Ёлка club", "dilshod"), // 20
        SearchFields("Узбектелеком кабинет", "dilshod"), // 21: Russian spelling, no ў
        SearchFields("Özbekiston pochtasi", "dilshod"), // 22: 2026 alphabet
        SearchFields("Toşkent metrosi", "dilshod"), // 23: 2026 alphabet
        SearchFields("Bank card", "", extras = listOf("Code word", "Mother's city")), // 24
        SearchFields("PayMe Business", "biz"), // 25
    )

    val index: SearchIndex by lazy { SearchIndex(entries) }

    fun indices(query: String): List<Int> = index.searchIndices(query).toList()
}
