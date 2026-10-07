package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.ChromeUrl
import io.github.dilshodbekmx.passwordvault.core.formats.ChromeUrl.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What Chrome's importer makes of a url, as far as the summary card of the CSV export needs it
 * (formats.md §4.5, formats-review P15). The expected canonical texts are what Chromium's `GURL`
 * gives for these inputs (`url/url_canon_*.cc`, read on 2026-10-07).
 */
class ChromeUrlTest {
    private fun spec(url: String) = ChromeUrl.webSpec(url)

    // ------------------------------------------------------------------ the four cases of formats-review P15

    @Test
    fun p15_anUpperCaseSchemeIsAccepted() {
        assertEquals("https://mail.ru/", spec("HTTPS://MAIL.RU/"))
        assertEquals(Verdict.OK, ChromeUrl.verdict("HTTPS://MAIL.RU/"))
        assertEquals(Verdict.OK, ChromeUrl.verdict("Http://Mail.Ru"))
    }

    @Test
    fun p15_aCyrillicPathIsMeasuredAfterPunycodeAndPercentEncoding() {
        // 1,018 UTF-16 units as typed; Chrome compares the canonical text, 6,030 characters, with 2,048.
        val url = "https://пример.рф/" + "путь".repeat(250)
        assertEquals(1_018, url.length)
        val canonical = spec(url)!!
        assertEquals("https://xn--e1afmkfd.xn--p1ai/" + "%D0%BF%D1%83%D1%82%D1%8C".repeat(250), canonical)
        assertEquals(6_030, canonical.length)
        assertEquals(Verdict.TOO_LONG, ChromeUrl.verdict(url))
        // 336 Cyrillic letters of path are 2,016 characters: with the 30 before them, 2,046.
        assertEquals(Verdict.OK, ChromeUrl.verdict("https://пример.рф/" + "п".repeat(336)))
        assertEquals(Verdict.TOO_LONG, ChromeUrl.verdict("https://пример.рф/" + "п".repeat(337)))
    }

    @Test
    fun p15_aHostWithACharacterChromeRefusesIsInvalid() {
        assertNull(spec("https://exa mple.com/"))
        assertEquals(Verdict.INVALID, ChromeUrl.verdict("https://exa mple.com/"))
        for (host in listOf("exa%20mple.com", "exa<mple.com", "exa>mple.com", "exa^mple.com", "exa|mple.com", "exa%mple.com",
            "exa%25mple.com", "exa%2Fmple.com", "exa\u0001mple.com", "exa\u007Fmple.com", "пример .рф", "a:b", "[x")) {
            assertEquals(host, Verdict.INVALID, ChromeUrl.verdict("https://$host/"))
        }
    }

    @Test
    fun p15_anAndroidLinkWithoutTheHashAndPackageShapeIsInvalid() {
        assertEquals(Verdict.INVALID, ChromeUrl.verdict("android://com.example.app/"))
        assertEquals(Verdict.OK, ChromeUrl.verdict("android://AbC123==@com.example.app/"))
    }

    // ------------------------------------------------------------------ what is no address of a password

    @Test
    fun anEmptyUrlIsMissingAndTextWithoutAWebSchemeIsInvalid() {
        assertEquals(Verdict.MISSING, ChromeUrl.verdict(""))
        for (text in listOf("gmail.com", "www.gmail.com/login", "my bank", "localhost", "localhost:8080/x", "mailto:a@b.c",
            "ftp://example.com/", "file:///etc/passwd", "chrome://settings", "://example.com", "1http://example.com",
            "ht tp://example.com", "javascript:alert(1)", "androidapp://com.example.app", " ", "https")) {
            assertEquals(text, Verdict.INVALID, ChromeUrl.verdict(text))
        }
    }

    @Test
    fun theLimitIs2048CharactersOfTheCanonicalText() {
        val base = "https://example.com/" // 20 characters
        assertEquals(Verdict.OK, ChromeUrl.verdict(base + "a".repeat(2_028)))
        assertEquals(Verdict.TOO_LONG, ChromeUrl.verdict(base + "a".repeat(2_029)))
        // A space in the path counts three; the port of the scheme counts nothing.
        assertEquals(Verdict.OK, ChromeUrl.verdict("https://example.com:443/" + "a".repeat(2_024) + " a"))
        assertEquals(Verdict.TOO_LONG, ChromeUrl.verdict("https://example.com:443/" + "a".repeat(2_025) + " a"))
        // A long text that is no address at all is invalid, not long.
        assertEquals(Verdict.INVALID, ChromeUrl.verdict("x".repeat(5_000)))
    }

    // ------------------------------------------------------------------ the canonical text

    @Test
    fun theSchemeTheSlashesAndThePort() {
        assertEquals("https://mail.ru/", spec("https://mail.ru"))
        assertEquals("https://example.com/", spec("  https://example.com \n"))
        assertEquals("https://example.com/a", spec("ht\ttps://exam\nple.com/a")) // tab, CR and LF are removed anywhere
        assertEquals("https://example.com/", spec("https:example.com"))
        assertEquals("https://example.com/", spec("https:/example.com"))
        assertEquals("https://example.com/a", spec("https:\\\\example.com\\a"))
        assertEquals("https://example.com/", spec("https://///example.com"))
        assertEquals("http://example.com/a/b", spec("http://Example.COM:80/a/b"))
        assertEquals("https://example.com/", spec("https://example.com:443"))
        assertEquals("https://example.com/", spec("https://example.com:0443/"))
        assertEquals("https://example.com/", spec("https://example.com:/"))
        assertEquals("https://example.com:8443/x", spec("https://example.com:8443/x"))
        assertEquals("http://example.com:443/", spec("http://example.com:443"))
        assertEquals("https://example.com:0/", spec("https://example.com:0/"))
        assertEquals("https://example.com:65535/", spec("https://example.com:65535"))
        for (bad in listOf("65536", "123456", "8a", "-1", "+80", " 80")) assertNull(bad, spec("https://example.com:$bad/"))
        assertNull(spec("ftp://example.com/"))
        assertNull(spec("example.com"))
    }

    @Test
    fun theUserNameAndThePassword() {
        assertEquals("https://user@example.com/", spec("https://user@example.com"))
        assertEquals("https://user:pa%20ss@example.com/", spec("https://user:pa ss@example.com/"))
        assertEquals("https://example.com/", spec("https://@example.com"))
        assertEquals("https://example.com/", spec("https://:@example.com"))
        assertEquals("https://:pw@example.com/", spec("https://:pw@example.com"))
        assertEquals("https://user@example.com/", spec("https://user:@example.com"))
        assertEquals("https://a%40b:c%3Ad@example.com/", spec("https://a@b:c:d@example.com/")) // the last @ ends it
        assertEquals("https://%D0%B8%D0%B2%D0%B0%D0%BD@example.com/", spec("https://иван@example.com/"))
    }

    @Test
    fun thePath() {
        fun path(p: String) = spec("https://example.com$p")!!.removePrefix("https://example.com")
        assertEquals("/", path(""))
        assertEquals("/a/c", path("/a/./b/../c"))
        assertEquals("/a/", path("/a/b/.."))
        assertEquals("/a/b/", path("/a/b/."))
        assertEquals("/x", path("/../../x"))
        assertEquals("/b", path("/a/%2e%2E/b"))
        assertEquals("/a/b", path("/a/%2e/b"))
        assertEquals("/.hidden/a.b/..c/d..", path("/.hidden/a.b/..c/d.."))
        assertEquals("/a//b", path("/a//b")) // slashes are not folded
        assertEquals("/a/b", path("/a\\b"))
        assertEquals("/a%20b", path("/a b"))
        assertEquals("/%22%3C%3E%5E%60%7B%7C%7D", path("/\"<>^`{|}"))
        assertEquals("/!$&'()*+,-:;=@[]_~", path("/!$&'()*+,-:;=@[]_~"))
        assertEquals("/%41%zz%", path("/%41%zz%")) // an escape is kept as written, a lone % too
        assertEquals("/%D0%BF%D1%83%D1%82%D1%8C", path("/путь"))
        assertEquals("/o%CA%BBzbek", path("/oʻzbek"))
        assertEquals("/%F0%9F%94%91", path("/🔑"))
        assertEquals("/%EF%BF%BD", path("/\uD800")) // an unpaired surrogate counts as U+FFFD
    }

    @Test
    fun theQueryAndTheFragment() {
        assertEquals("https://example.com/?q=a%20b&x=%27y%27", spec("https://example.com/?q=a b&x='y'"))
        assertEquals("https://example.com/?%22%3C%3E{|}^`[]\\", spec("https://example.com?\"<>{|}^`[]\\"))
        assertEquals("https://example.com/?a?b", spec("https://example.com/?a?b"))
        assertEquals("https://example.com/?", spec("https://example.com/?"))
        assertEquals("https://example.com/#a%20b%60c%22%3C%3E'{}", spec("https://example.com/#a b`c\"<>'{}"))
        assertEquals("https://example.com/#a#b?c", spec("https://example.com#a#b?c"))
        assertEquals("https://example.com/a?b#c", spec("https://example.com/a?b#c"))
        assertEquals(
            "https://example.com/?%D0%BA%D0%BB%D1%8E%D1%87=1#%D1%8F%D0%BA%D0%BE%D1%80%D1%8C",
            spec("https://example.com/?ключ=1#якорь"),
        )
    }

    @Test
    fun theHost() {
        assertEquals("https://example.com/", spec("https://EXAMPLE.com"))
        assertEquals("https://example.com/", spec("https://ex%41mple.com/"))
        assertEquals("https://ex%2Aample.com/", spec("https://ex*ample.com/"))
        assertEquals("https://a_b.example/", spec("https://a_b.example/"))
        assertEquals("https://example.com./", spec("https://example.com./"))
        assertEquals("https://xn--e1afmkfd.xn--p1ai/", spec("https://ПРИМЕР.РФ"))
        assertEquals("https://xn--e1afmkfd.xn--p1ai/", spec("https://%D0%BF%D1%80%D0%B8%D0%BC%D0%B5%D1%80.%D1%80%D1%84/"))
        assertEquals("https://xn--80a1acny.uz/", spec("https://почта.uz/"))
        assertEquals("https://www.xn--b1aew.xn--p1ai/", spec("https://www.мвд.рф/"))
        for (empty in listOf("https://", "https:///", "https://?x", "https://#x", "https://:80/", "https://user@/")) {
            assertNull(empty, spec(empty))
        }
        assertNull(spec("https://%FF.example/")) // an escape that is not UTF-8
        assertNull(spec("https://ex%4.example/")) // not an escape
    }

    @Test
    fun aHostThatEndsInANumberMustBeAnIpv4Address() {
        assertEquals("http://192.168.1.1/admin", spec("http://192.168.1.1/admin"))
        assertEquals("http://192.168.1.1:8080/", spec("http://192.168.1.1:8080"))
        assertEquals("http://127.0.0.1/", spec("http://0x7f.1/"))
        assertEquals("http://127.0.0.1/", spec("http://0X7F.0.0.1/"))
        assertEquals("http://127.0.0.1/", spec("http://2130706433/"))
        assertEquals("http://1.2.0.3/", spec("http://1.2.3/"))
        assertEquals("http://8.1.1.1/", spec("http://010.1.1.1/"))
        assertEquals("http://1.2.3.4/", spec("http://1.2.3.4./"))
        assertEquals("http://0.0.0.0/", spec("http://0/"))
        assertEquals("http://255.255.255.255/", spec("http://0xffffffff/"))
        for (bad in listOf("1.2.3.4.5", "256.1.1.1", "1.2.3.256", "09.1.1.1", "1..2", "example.1", "4294967296", "0x100000000")) {
            assertNull(bad, spec("http://$bad/"))
        }
        // The last part is not a number: a name.
        assertEquals("http://1.2.3.example/", spec("http://1.2.3.example/"))
        assertEquals("http://1.2.3.4a/", spec("http://1.2.3.4a/"))
        assertEquals("http://0x.example/", spec("http://0x.example/"))
        assertEquals("http://1.2.3.4../", spec("http://1.2.3.4../")) // only one final dot is set aside; the part after the next is empty
    }

    @Test
    fun bracketsMustHoldAnIpv6Address() {
        assertEquals("http://[::1]/", spec("http://[::1]/"))
        assertEquals("http://[::1]:8080/", spec("http://[::1]:8080/"))
        assertEquals("http://[::1]/", spec("http://[0:0:0:0:0:0:0:1]/"))
        assertEquals("http://[2001:db8::1]/", spec("http://[2001:DB8::1]/"))
        assertEquals("http://[::ffff:c0a8:1]/", spec("http://[::ffff:192.168.0.1]/"))
        assertEquals("http://[1:2:3:4:5:6:7:8]/", spec("http://[1:2:3:4:5:6:7:8]/"))
        assertEquals("http://[1::]/", spec("http://[1::]/"))
        assertEquals("http://[::]/", spec("http://[::]/"))
        assertEquals("http://[1:0:0:2::3]/", spec("http://[1:0:0:2:0:0:0:3]/"))
        assertEquals("http://[1::2:0:0:3:4]/", spec("http://[1:0:0:2:0:0:3:4]/")) // two runs of the same length: the first
        assertEquals("http://[1:0:2:3:4:5:6:7]/", spec("http://[1:0:2:3:4:5:6:7]/")) // one zero is not contracted
        for (bad in listOf("[::1", "::1", "[1:2:3]", "[::g]", "[1::2::3]", "[1:2:3:4:5:6:7:8:9]", "[12345::]", "[::1.2.3]",
            "[::1.2.3.4.]", "[]", "[::1]x", "x[::1]", "[:1]", "[1:]")) {
            assertNull(bad, spec("http://$bad/"))
        }
    }

    // ------------------------------------------------------------------ Android app links

    @Test
    fun anAndroidAppLinkNeedsABase64HashAPackageAndNothingElse() {
        val hash = "Jzj5T2E45Hb33D-lk-EHZVCrb7a064dEicTwrTYQYGXO99JqE2YERhbMP1qLogwJiy87OsBzC09Gk094Z-U_hg=="
        val ok = listOf(
            "android://$hash@com.netflix.mediaclient/",
            "android://$hash@com.netflix.mediaclient",
            "ANDROID://$hash@com.netflix.mediaclient/",
            "android://${hash.replace("=", "%3D")}@com.netflix.mediaclient/",
            "android://abcdef@com.x/", // padded to abcdef==
            "android://abcd@Com.Example_App2/",
            "android://abcd:@com.x/", // an empty password
            "android://abcd@com.x:/", // an empty port
        )
        for (url in ok) assertEquals(url, Verdict.OK, ChromeUrl.verdict(url))
        val bad = listOf(
            "android://com.example.app/", "android://@com.x/", "android://abcd@/", "android://abcd@", "android://a:b@com.x/",
            "android://abcd@com.x:1/", "android://abcd@com.x/path", "android://abcd@com.x/?q", "android://abcd@com.x/#f",
            "android://abcd@com-x/", "android://abc=d@com.x/", "android://abcde@com.x/", "android://ab+d@com.x/",
            "android://ab/d@com.x/", "android://abcd@com.x//", "android://abcd@ком.пример/",
        )
        for (url in bad) assertEquals(url, Verdict.INVALID, ChromeUrl.verdict(url))
        assertEquals(Verdict.TOO_LONG, ChromeUrl.verdict("android://" + "abcd".repeat(600) + "@com.x/"))
    }
}
