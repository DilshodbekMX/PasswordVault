import pv.formats.*
fun main() {
    fun e(t: String, url: String) = Entry("id-$t", t, "u", "pw", listOf(url), createdAt = 0, updatedAt = 0)
    val cyr = "https://пример.рф/" + "путь".repeat(250)          // 1,018 UTF-16 units; Chrome canonicalises to punycode + %XX
    val cases = listOf(e("upper", "HTTPS://MAIL.RU/"), e("cyrillic-path", cyr), e("space-in-host", "https://exa mple.com/"), e("android-no-hash", "android://com.example.app/"))
    for (c in cases) {
        val r = GoogleCsv.report(listOf(c))
        println("${c.title}: raw length ${c.urls[0].length}; report badUrl=${r.badUrl} longUrl=${r.longUrl}")
    }
    // What a WHATWG/GURL-style canonicaliser makes of the Cyrillic path: each letter -> %XX%XX (6 characters)
    val path = cyr.substringAfter("рф")
    val encoded = path.toByteArray(Charsets.UTF_8).joinToString("") { b -> val v = b.toInt() and 0xFF; if (v < 0x80) v.toChar().toString() else "%%%02X".format(v) }
    val host = java.net.IDN.toASCII("пример.рф")
    println("canonical form: https://$host$encoded -> length ${("https://" + host + encoded).length} (Chrome's limit 2,048 applies to url.spec())")
}
