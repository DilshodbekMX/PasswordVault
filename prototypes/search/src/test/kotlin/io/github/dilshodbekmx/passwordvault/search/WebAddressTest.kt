package io.github.dilshodbekmx.passwordvault.search

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class WebAddressTest(private val address: String, private val host: String) {

    @Test
    fun host() = assertEquals(host, WebAddress.hostOf(address))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): List<Array<Any>> = listOf<Array<Any>>(
            arrayOf("https://www.google.com/accounts?x=1", "google.com"),
            arrayOf("https://www.Google.com:443/accounts?x=1", "google.com"),
            arrayOf("http://user:pass@example.com:8080/path", "example.com"),
            arrayOf("google.com", "google.com"),
            arrayOf("www.Google.COM", "google.com"),
            arrayOf("  https://my.gov.uz/  ", "my.gov.uz"),
            arrayOf("https://online.sberbank.ru/CSAFront/index.do", "online.sberbank.ru"),
            arrayOf("androidapp://com.sberbank.online", "com.sberbank.online"),
            arrayOf("android://Xw0Fd5Jx/Ng+K/GtH0==@com.google.android.gm/", "com.google.android.gm"),
            arrayOf("android://hash@com.sberbank.online/", "com.sberbank.online"),
            arrayOf("https://xn--80aswg.xn--p1ai/", "сайт.рф"),
            arrayOf("https://пример.рф/путь", "пример.рф"),
            arrayOf("localhost:3000", "localhost"),
            arrayOf("http://[::1]:8080/", "::1"),
            arrayOf("192.168.1.1:8443/admin", "192.168.1.1"),
            arrayOf("mailto:me@example.com", "example.com"),
            arrayOf("", ""),
            arrayOf("https://www.example.com#top", "example.com"),
            arrayOf("https://example.com?q=1", "example.com"),
            arrayOf("WWW.EXAMPLE.COM.", "example.com"),
            arrayOf("//cdn.example.com/x.js", "cdn.example.com"),
            arrayOf("www", "www"),
            arrayOf("Sberbank Online", "sberbank online"),
        )
    }
}
