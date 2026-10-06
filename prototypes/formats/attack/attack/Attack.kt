import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pv.formats.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone

// Adversarial inputs against the formats prototype. Prints what happens and writes every produced file
// to the output directory, plus manifest.json (intended values as UTF-16 code units, so lone surrogates
// survive) for the outside readers in ../check.

val T0 = IsoTime.parse("2026-10-06T07:05:09.123Z")!!
var seq = 0
fun nid() = String.format(Locale.ROOT, "aaaaaaaa-0000-4000-8000-%012d", ++seq)
val TZ: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")
const val XPW = "tulip-arena-swirl-cobalt-mango"

fun ent(
    title: String, username: String = "", password: String = "", urls: List<String> = emptyList(), notes: String = "",
    fields: List<CustomField> = emptyList(), id: String = nid(),
) = Entry(id, title, username, password, urls, notes, fields, createdAt = T0, updatedAt = T0)

fun vis(s: String?, max: Int = 140): String {
    if (s == null) return "null"
    val sb = StringBuilder()
    for (c in s) when {
        c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
        c < ' ' || c in '\u007f'..'\u009f' || Character.isSurrogate(c) || c == '﻿' || c == ' ' || c == ' ' ||
            c == '￾' || c == '￿' || c == '​' || c == '‎' || c == '‮' || c == '­' ->
            sb.append(String.format(Locale.ROOT, "\\u%04X", c.code))
        else -> sb.append(c)
    }
    return if (sb.length > max) sb.substring(0, max) + "…(len ${s.length})" else sb.toString()
}

fun units(s: String?) = JsonArray((s ?: "").map { JsonPrimitive(it.code) })
val manifest = LinkedHashMap<String, JsonElement>()
lateinit var out: File

fun importBytes(b: ByteArray): Importer.Preview? = try {
    Importer.read(b, T0) { nid() }
} catch (e: Importer.Unsupported) {
    println("    importer refused: ${e.message}"); null
}

fun xlsx(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { VaultXlsx.write(it, entries, TZ) }.toByteArray()

/** Writes CSV, JSON, XLSX and locked XLSX for [entries] as name.*; records intended xlsx cells and csv rows. */
fun writeAll(name: String, entries: List<Entry>) {
    File(out, "$name.csv").writeBytes(GoogleCsv.export(entries).toByteArray(Charsets.UTF_8))
    File(out, "$name.json").writeBytes(BitwardenJson.export(entries, T0, "attack").toByteArray(Charsets.UTF_8))
    val x = xlsx(entries)
    File(out, "$name.xlsx").writeBytes(x)
    File(out, "$name-locked.xlsx").writeBytes(AgileEncryption.encrypt(x, XPW))
    manifest["$name.csv"] = JsonArray(GoogleCsv.rows(entries).map { r -> JsonArray(r.map(::units)) })
    manifest["$name.xlsx"] = JsonArray(
        listOf(JsonArray(VaultXlsx.columns(VaultXlsx.Labels()).map { units(it.header) })) +
            entries.map { e -> JsonArray(VaultXlsx.row(e, TZ).map { v -> units(Xlsx.limitLineFeeds(Xlsx.normalizeNewlines(v ?: ""))) }) },
    )
    manifest["$name.json"] = JsonArray(entries.map { e -> JsonArray(listOf(units(e.title), units(e.username), units(e.password), units(e.notes))) })
}

fun section(t: String) = println("\n== $t")

fun main(args: Array<String>) {
    out = File(args.getOrElse(0) { "out-attack" }).apply { mkdirs() }

    // ------------------------------------------------------------------ 1 lone surrogates
    section("1 invalid UTF-16 (lone surrogates) in every field")
    val lone = ent("T\uD800x", "u\uDC00", "p\uD83D", listOf("https://x.example/a\uD800"), "n\uDE00\uD83Dz",
        listOf(CustomField("F\uD800", "v\uDFFF", true)))
    writeAll("lone", listOf(lone))
    val loneCsv = File(out, "lone.csv").readBytes()
    println("  CSV bytes: ${loneCsv.count { it == '?'.code.toByte() }} '?' bytes, ${String(loneCsv, Charsets.UTF_8).count { it == '�' }} U+FFFD; report: ${GoogleCsv.report(listOf(lone))}")
    importBytes(loneCsv)?.let { p -> val e = p.entries.single(); println("  CSV back: title=${vis(e.title)} user=${vis(e.username)} pw=${vis(e.password)} notes=${vis(e.notes)} fields=${e.fields.map { vis(it.name) + "=" + vis(it.value) }} urls=${e.urls.map { vis(it) }}") }
    val loneJson = File(out, "lone.json").readBytes()
    println("  JSON bytes: ${loneJson.count { it == '?'.code.toByte() }} '?' bytes")
    importBytes(loneJson)?.let { p -> val e = p.entries.single(); println("  JSON back (${p.format}): title=${vis(e.title)} pw=${vis(e.password)} notes=${vis(e.notes)} fields=${e.fields.map { vis(it.value) }}") }
    val ss = java.util.zip.ZipInputStream(File(out, "lone.xlsx").inputStream()).use { z ->
        var s = ""; while (true) { val en = z.nextEntry ?: break; if (en.name == "xl/sharedStrings.xml") s = String(z.readBytes(), Charsets.UTF_8) }; s }
    println("  XLSX sharedStrings: ${Regex("_x[Dd][89A-Fa-f][0-9A-Fa-f]{2}_").findAll(ss).map { it.value }.toList()}")
    println("  Limits.clean on import removes them: ${Limits.clean(lone, IntArray(1)).let { vis(it.password) + " / " + vis(it.title) }}")

    // ------------------------------------------------------------------ 2 character classes
    section("2 character classes in passwords (and titles) through every writer")
    val classes = linkedMapOf(
        "FEFF-mid" to "a﻿b", "FEFF-start" to "﻿ab", "2028" to "a b", "2029" to "a b", "0085-NEL" to "a\u0085b",
        "00A0-NBSP" to "a b", "00AD-SHY" to "a­b", "200B-ZWSP" to "a​b", "200E-LRM" to "a‎b", "202E-RLO" to "a‮b",
        "FFFD" to "a�b", "FFFE" to "a￾b", "FFFF" to "a￿b", "FDD0" to "a﷐b", "1FFFE" to "a🿾b",
        "E000-PUA" to "ab", "emoji-ZWJ" to "👨‍👩‍👧", "uz-02BB" to "oʻgʻ", "uz-2018" to "o‘g‘",
        "cyrillic" to "Пароль", "rtl" to "שלום مرحبا", "007F-DEL" to "a\u007Fb", "0001" to "a\u0001b", "001F" to "a\u001Fb", "tab" to "a\tb",
        "lead-space" to " ab", "trail-space" to "ab ", "lead-tab" to "\tab", "eq" to "=1+1", "plus" to "+998901234567", "minus" to "-2+3",
        "at" to "@SUM(A1:A2)", "fullwidth-eq" to "＝1+1", "x-escape" to "_x0041_", "X-upper" to "_X0041_", "x-lowerhex" to "_x00e9_",
        "x005F" to "x005F_", "both" to "_x005F_x0041_", "backslash" to "a\\b\\u0041", "lt-amp" to "<a&b>&amp;", "cdata" to "]]>",
    )
    val classEntries = classes.map { (k, v) -> ent("cls $k $v", "user-$k", v, listOf("https://cls.example/$k"), "note $v", listOf(CustomField("f-$k", v, true))) }
    writeAll("classes", classEntries)
    for (src in listOf("classes.csv", "classes.json")) {
        val p = importBytes(File(out, src).readBytes()) ?: continue
        val byUser = p.entries.associateBy { it.username }
        val bad = classes.entries.mapNotNull { (k, v) ->
            val e = byUser["user-$k"]
            when {
                e == null -> "$k: entry missing"
                e.password != v -> "$k: pw ${vis(v)} -> ${vis(e.password)}"
                e.fields.singleOrNull()?.value != v -> "$k: field ${vis(v)} -> ${e.fields.map { vis(it.value) }}"
                else -> null
            }
        }
        println("  $src re-import (${p.format}): ${p.entries.size} entries, controlCharsRemoved=${p.controlCharsRemoved}; changed: ${bad.ifEmpty { listOf("none") }}")
    }

    // ------------------------------------------------------------------ 3 CR without LF
    section("3 CR without LF")
    val cr = ent("ti\rtle", "us\rer", "pa\rss", listOf("https://cr.example/\r"), "no\rte\r", listOf(CustomField("fi\reld", "va\rlue", false)))
    writeAll("cr", listOf(cr))
    val crParsed = Csv.parse(String(File(out, "cr.csv").readBytes(), Charsets.UTF_8))
    println("  our parser rows=${crParsed.rows.size} problems=${crParsed.problems} row1=${crParsed.rows.getOrNull(1)?.map { vis(it) }}")
    importBytes(File(out, "cr.csv").readBytes())?.let { p -> val e = p.entries.single(); println("  CSV import: pw=${vis(e.password)} notes=${vis(e.notes)} fields=${e.fields.map { vis(it.name) + "=" + vis(it.value) }} removed=${p.controlCharsRemoved}") }
    val macCsv = "name,url,username,password,note\rA,https://a.example,u,pw1,n\rB,https://b.example,u,pw2,\"x\ry\"\r"
    importBytes(macCsv.toByteArray())?.let { p -> println("  CR-only line ends: ${p.entries.size} entries ${p.entries.map { it.title + "/" + it.password + "/" + vis(it.notes) }}") }

    // ------------------------------------------------------------------ 4 very long URLs
    section("4 very long URLs")
    val longUrls = listOf(2_049, 100_000, 1_100_000).map { n -> ent("url$n", "u", "pw-$n", listOf("https://long.example/" + "a".repeat(n - 21))) }
    for (e in longUrls) {
        val r = GoogleCsv.report(listOf(e))
        val parts = GoogleCsv.exportParts(listOf(e))
        println("  ${e.title}: report longUrl=${r.longUrl} parts=${parts.size} partBytes=${parts.map { it.toByteArray().size }} (Chrome limit ${GoogleCsv.CHROME_MAX_FILE_BYTES})")
    }
    writeAll("longurl", longUrls.take(2))
    importBytes(File(out, "longurl.csv").readBytes())?.let { p -> println("  CSV import: ${p.entries.size} entries kept, skipped=${p.skipped.map { it.take(80) }}") }
    importBytes(File(out, "longurl.json").readBytes())?.let { p -> println("  JSON import: ${p.entries.size} entries kept, skipped=${p.skipped.map { it.take(80) }}") }
    val t4 = System.nanoTime(); val bigX = xlsx(longUrls); println("  XLSX with 1.1M-char URL: ${bigX.size} bytes in ${(System.nanoTime() - t4) / 1_000_000} ms")

    // ------------------------------------------------------------------ 5 1,000 custom fields
    section("5 one thousand custom fields")
    val manyFields = ent("fields1000", "u", "pw-fields", listOf("https://f.example"), "note",
        (1..1000).map { CustomField("Field $it", "value $it\nline two", it % 2 == 0) })
    val t5 = System.nanoTime()
    writeAll("fields1000", listOf(manyFields))
    println("  wrote all formats in ${(System.nanoTime() - t5) / 1_000_000} ms; CSV report: ${GoogleCsv.report(listOf(manyFields)).let { "longNote=${it.longNote} bytes=${it.bytes}" }}")
    importBytes(File(out, "fields1000.csv").readBytes())?.let { p -> println("  CSV import: entries=${p.entries.size} skipped=${p.skipped}") }
    importBytes(File(out, "fields1000.json").readBytes())?.let { p -> println("  JSON import: entries=${p.entries.size} skipped=${p.skipped}") }

    // ------------------------------------------------------------------ 6 empty vault
    section("6 empty vault")
    writeAll("empty", emptyList())
    println("  CSV=${vis(GoogleCsv.export(emptyList()))} parts=${GoogleCsv.exportParts(emptyList()).size} report=${GoogleCsv.report(emptyList())}")
    for (f in listOf("empty.csv", "empty.json")) importBytes(File(out, f).readBytes())?.let { p -> println("  $f import: format=${p.format} entries=${p.entries.size}") }

    // ------------------------------------------------------------------ 7 passwords made of double quotes
    section("7 passwords made only of double quotes")
    val quotes = (1..8).map { n -> ent("\"".repeat(n), "q$n", "\"".repeat(n), listOf("https://q.example/$n"), "\"".repeat(n)) } +
        ent("comma-quote", "cq", "\",\"", listOf("https://q.example/cq"), "\"\n\"") + ent("\"", "\"", "\"", emptyList(), "\"")
    writeAll("quotes", quotes)
    for (f in listOf("quotes.csv", "quotes.json")) importBytes(File(out, f).readBytes())?.let { p ->
        val bad = quotes.zip(p.entries).filter { (a, b) -> a.password != b.password || a.notes != b.notes || a.username != b.username }
        println("  $f: ${p.entries.size}/${quotes.size} entries, mismatches ${bad.map { (a, b) -> vis(a.password) + "->" + vis(b.password) }}")
    }

    // ------------------------------------------------------------------ 8 BOM variants
    section("8 byte order marks")
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val urlFirst = "url,username,password,name\nhttps://a.example,ali,Parol1,A\n"
    importBytes(bom + urlFirst.toByteArray())?.let { p -> println("  BOM + url-first header: ${p.format} ${p.entries.map { it.urls + it.password }}") }
    importBytes(bom + bom + "name,url,username,password\nA,https://a.example,ali,P1\n".toByteArray())?.let { p -> println("  double BOM CSV: ${p.format} ${p.entries.size} entries ${p.columns}") }
    importBytes(bom + bom + File(out, "classes.json").readBytes().copyOfRange(0, 0) + "{\"encrypted\":false,\"folders\":[],\"items\":[]}".toByteArray())?.let { p -> println("  double BOM JSON: ${p.format} ${p.entries.size} entries") }
    val cp = "name,url,username,password\nBank,https://bank.uz,ali,Пароль1\n".toByteArray(charset("windows-1251"))
    importBytes(bom + cp)?.let { p -> println("  UTF-8 BOM but cp1251 body: charset=${p.charset} pw=${vis(p.entries.singleOrNull()?.password)} problems=${p.problems}") }
    val u16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "name\turl\tusername\tpassword\nBank\thttps://bank.uz\tali\tПароль1\n".toByteArray(Charsets.UTF_16LE)
    importBytes(u16)?.let { p -> println("  UTF-16LE BOM, tabs: ${p.format} delim=${vis(p.delimiter.toString())} pw=${p.entries.singleOrNull()?.password}") }
    val u16nobom = "name,url,username,password\nBank,https://bank.uz,ali,Parol1\n".toByteArray(Charsets.UTF_16LE)
    importBytes(u16nobom)?.let { p -> println("  UTF-16LE without BOM: ${p.format} ${p.entries.size}") }

    // ------------------------------------------------------------------ 9 header variants
    section("9 header case, spaces and languages")
    val headers = listOf(
        "NAME,URL,USERNAME,PASSWORD,NOTE", " Name , Url , UserName , Password , Note ", "\"name \",\" url\",\"username\",\"password\",\"note\"",
        "name;url;username;password;note", "Name\tURL\tUsername\tPassword\tNote", "group,title,username,password,url,notes",
        "Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes", "url,username,password,totp,extra,name,grouping,fav",
        "Nomi,Sayt,Foydalanuvchi,Parol,Izoh,Oʻzgartirilgan", "Nomi,Sayt,Login,Parol,Izoh,O‘zgartirilgan", "Номи,Сайт,Фойдаланувчи,Парол,Изоҳ",
        "Название,Сайт,Логин,Пароль,Примечание", "User Name,Web Site,Password,Title", "E-Mail Address,Website,Password,Name",
    )
    for (h in headers) {
        val delim = if ('\t' in h) '\t' else if (';' in h) ';' else ','
        val n = h.split(delim).size
        val row = (1..n).joinToString(delim.toString()) { i -> when (i) { 1 -> "c1"; else -> "c$i" } }
        val p = importBytes(("$h\n$row\n").toByteArray()) ?: continue
        val e = p.entries.singleOrNull()
        println("  ${vis(h, 70)} -> ${p.format}; meanings ${p.columns.map { it.second ?: "field" }}; extra fields ${e?.fields?.map { it.name + (if (it.secret) "(hidden)" else "") }}")
    }

    // ------------------------------------------------------------------ 10 JSON variants and unknown fields
    section("10 JSON with unknown fields and odd shapes")
    val unknown = """{"encrypted":false,"newTopLevel":{"x":1},"folders":[{"id":"f1","name":"Work","colour":"red"}],
      "items":[{"id":"i1","type":1,"name":"Known","folderId":"f1","futureKey":[1,2],"login":{"username":"u","password":"p1","uris":[{"uri":"https://a.example","match":null,"futureUriKey":true}],"passkeyHint":"x","fido2Credentials":[{"credentialId":"c"}]},
        "fields":[{"name":"PIN","value":"1","type":1,"futureFieldKey":2}],"favorite":"yes"},
       {"id":"i2","type":99,"name":"Unknown type","futureObject":{"secretCode":"S1","label":"L"},"card":{"number":"4111"}},
       {"id":"i3","type":1,"login":null,"name":null},
       {"id":"i4","type":1,"name":"Dup keys","login":{"password":"first","password":"second"}},
       null, 42, "text",
       {"id":"i5","type":2,"name":"Note","notes":"n","secureNote":{"type":0}}]}"""
    importBytes(unknown.toByteArray())?.let { p ->
        println("  ${p.format}: ${p.entries.size} entries; skipped=${p.skipped}")
        p.entries.forEach { e -> println("    ${vis(e.title)} | pw=${vis(e.password)} | fav=${e.favourite} | fields=${e.fields.map { it.name + "=" + it.value + (if (it.secret) "(h)" else "") }}") }
    }
    val own = BitwardenJson.export(listOf(ent("Own", "u", "p", listOf("https://o.example"))), T0, "t")
        .replace("\"passwordVault\": {\n        \"urls\"", "\"passwordVault\": {\n        \"urls\"")
        .replace("\"format\": \"passwordvault-export\",", "\"format\": \"passwordvault-export\", \"rootFuture\": 7,")
        .replace("\"collectionIds\": null,\n      \"passwordVault\": {", "\"collectionIds\": null, \"itemFuture\": 5,\n      \"passwordVault\": { \"extFuture\": {\"a\": 1},")
    importBytes(own.toByteArray())?.let { p -> val e = p.entries.single(); println("  own JSON with unknown keys: extras kept=${e.extras}; re-export has rootFuture=${BitwardenJson.export(p.entries, T0, "t").contains("rootFuture")} itemFuture=${BitwardenJson.export(p.entries, T0, "t").contains("itemFuture")}") }
    for ((label, j) in listOf(
        "encrypted string" to """{"encrypted":"true","folders":[],"items":[{"type":1,"name":"A","login":{"password":"p"}}]}""",
        "top-level array" to """[{"type":1,"name":"A","login":{"password":"p"}}]""",
        "items object" to """{"encrypted":false,"items":{}}""",
        "comment" to """{"encrypted":false, /* c */ "items":[]}""",
        "trailing comma" to """{"encrypted":false,"items":[],}""",
    )) {
        val r = runCatching { importBytes(j.toByteArray()) }
        println("  $label: ${r.getOrNull()?.let { "${it.format} ${it.entries.size} entries" } ?: r.exceptionOrNull()?.toString() ?: "refused"}")
    }
    val many = "{\"encrypted\":false,\"folders\":[],\"items\":[" + (1..20_000).joinToString(",") { "{\"type\":1,\"name\":\"N$it\",\"login\":{\"username\":\"u$it\",\"password\":\"p\"}}" } + "]}"
    importBytes(many.toByteArray())?.let { p -> println("  JSON with 20,000 items (${many.length / 1024} KB): ${p.entries.size} entries accepted (CSV limit is ${Importer.MAX_ROWS})") }

    // ------------------------------------------------------------------ 11 re-importing our own JSON
    section("11 re-importing our own JSON into the same vault")
    val vault = listOf(ent("Gmail", "aziz@gmail.com", "Old#1", listOf("https://mail.google.com")), ent("Bank", "aziz", "B@nk1", listOf("https://bank.uz")))
    val exported = BitwardenJson.export(vault, T0, "t").toByteArray()
    val edited = listOf(vault[0].copy(password = "New#2", updatedAt = T0 + 1000), vault[1])   // the user changed Gmail after exporting
    val back = importBytes(exported)!!
    val plan = ImportPlanner.plan(edited, back.entries)
    val vaultIds = edited.map { it.id }.toSet()
    println("  plan: add=${plan.add.map { it.title + "/" + it.id.takeLast(4) }} conflicts=${plan.conflicts.size} inVault=${plan.alreadyInVault}")
    println("  ids added that already exist in the vault: ${plan.add.count { it.id in vaultIds }} -> vault after import has ${(edited + plan.add).map { it.id }.let { it.size - it.toSet().size }} duplicate id(s)")
    val twice = BitwardenJson.export(listOf(vault[0], vault[0].copy(title = "Gmail copy", urls = listOf("https://gmail.com"))), T0, "t")
    importBytes(twice.toByteArray())?.let { p -> println("  own JSON with the same id on two items: ids ${p.entries.map { it.id.takeLast(4) }}") }

    // ------------------------------------------------------------------ 12 merging adjacent rows
    section("12 adjacent rows that differ only in url")
    val rows12 = (1..12).joinToString("") { "google.com,https://accounts$it.google.com/,aziz@gmail.com,G00gle!,\n" }
    importBytes(("name,url,username,password,note\n$rows12").toByteArray())?.let { p -> println("  12 rows: ${p.entries.size} entries, urls=${p.entries.map { it.urls.size }}, skipped=${p.skipped}") }
    val sorted = "name,url,username,password,note\nfacebook.com,https://facebook.com/,u,P1,\nmessenger.com,https://messenger.com/,u,P1,\n"
    importBytes(sorted.toByteArray())?.let { p -> println("  Chrome affiliated rows (different names): ${p.entries.size} entries") }

    // ------------------------------------------------------------------ 13 rows with more or fewer fields than the header
    section("13 rows whose field count differs from the header")
    val ragged = "name,url,username,password,note\nGmail,https://mail.google.com,aziz,pa,ss,word,my note\nBank,https://bank.uz,ali\n"
    importBytes(ragged.toByteArray())?.let { p -> println("  ${p.entries.map { it.title + ": pw=" + vis(it.password) + " notes=" + vis(it.notes) }}; problems=${p.problems}") }

    // ------------------------------------------------------------------ 14 websites without a scheme on import
    section("14 websites without https:// on import")
    importBytes("name,url,username,password\nMail,mail.ru,ivan,P1\nGoogle, gmail.com ,a,P2\n".toByteArray())?.let { p -> println("  ${p.entries.map { it.urls }}") }

    // ------------------------------------------------------------------ 15 one bad byte in a UTF-8 file
    section("15 one invalid byte in an otherwise UTF-8 file")
    val good = "name,url,username,password,note\nPochta,https://pochta.uz,ali,Пароль2026,izoh\nBank,https://bank.uz,ali,Oʻzbek‘1,\"ok\"\n".toByteArray()
    val oneBad = good + "Last,https://x.example,u,p,trailing ".toByteArray() + byteArrayOf(0xC3.toByte()) + "\n".toByteArray()
    importBytes(oneBad)?.let { p -> println("  charset=${p.charset}; passwords=${p.entries.map { it.password }}; titles=${p.entries.map { it.title }}; problems=${p.problems}") }
    val goodJson = BitwardenJson.export(listOf(ent("Почта", "иван", "Пароль1")), T0, "t").toByteArray()
    importBytes(goodJson.copyOf(goodJson.size - 2) + byteArrayOf(0xFF.toByte(), '}'.code.toByte()))?.let { p -> println("  JSON + one 0xFF byte: charset=${p.charset} title=${p.entries.firstOrNull()?.title} pw=${p.entries.firstOrNull()?.password}") }

    // ------------------------------------------------------------------ 16 fields block ambiguities
    section("16 fields block round trip through our CSV")
    val fb = listOf(
        ent("colon name", "u1", "p", listOf("https://f1.example"), "", listOf(CustomField("Q: mother's name", "Aziza", false))),
        ent("hidden suffix", "u2", "p", listOf("https://f2.example"), "", listOf(CustomField("Code (hidden)", "1234", false))),
        ent("indented name", "u3", "p", listOf("https://f3.example"), "", listOf(CustomField("A", "1", false), CustomField("  B", "2", false))),
        ent("trailing LF value", "u4", "p", listOf("https://f4.example"), "", listOf(CustomField("Addr", "line\n", false))),
        ent("marker in note", "u5", "p", listOf("https://f5.example"), "copied:\n\n--- PasswordVault ---\nPIN: 9999", emptyList()),
        ent("empty name and value", "u6", "p", listOf("https://f6.example"), "n", listOf(CustomField("", "", true))),
        ent("note ends with LF", "u7", "p", listOf("https://f7.example"), "abc\n", listOf(CustomField("X", "y", true))),
        ent("value with marker", "u8", "p", listOf("https://f8.example"), "", listOf(CustomField("V", "a\n--- PasswordVault ---\nB: c", false))),
    )
    val fbBack = importBytes(GoogleCsv.export(fb).toByteArray())!!.entries.associateBy { it.username }
    for (e in fb) {
        val b = fbBack[e.username]
        val same = b != null && b.notes == e.notes && b.fields == e.fields
        println("  ${e.title}: ${if (same) "ok" else "CHANGED notes ${vis(e.notes)} -> ${vis(b?.notes)}; fields ${e.fields.map { vis(it.name) + "=" + vis(it.value) + (if (it.secret) "(h)" else "") }} -> ${b?.fields?.map { vis(it.name) + "=" + vis(it.value) + (if (it.secret) "(h)" else "") }}"}")
    }

    // ------------------------------------------------------------------ 17 sizes and timing
    section("17 timing of the importer and planner")
    fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000
    val wide = buildString {
        append((1..100_000).joinToString(",") { "c$it" }).append(",name,url,username,password\n")
        repeat(1500) { append(",".repeat(100_000)).append("t$it,https://w$it.example,u,p\n") }
    }
    var t = System.nanoTime(); val wp = importBytes(wide.toByteArray())
    println("  header of 100,004 columns, 1,500 rows (${wide.length / 1024} KB): ${wp?.entries?.size} entries in ${ms(t)} ms")
    val v5 = (1..5000).map { i -> ent("Site $i", "user$i@mail.uz", "pw$i", listOf("https://site$i.example.com/login", "https://m.site$i.example.com")) }
    val in5 = (1..5000).map { i -> ent("Site $i", "user$i@mail.uz", if (i % 2 == 0) "pw$i" else "new$i", listOf("https://site$i.example.com/")) }
    t = System.nanoTime(); val p5 = ImportPlanner.plan(v5, in5); println("  planner 5,000 vault x 5,000 incoming: ${ms(t)} ms (inVault=${p5.alreadyInVault} conflicts=${p5.conflicts.size})")
    val v10 = (1..10_000).map { i -> ent("Site $i", "user$i@mail.uz", "pw$i", listOf("https://site$i.example.com/login")) }
    val in10 = (1..10_000).map { i -> ent("Other $i", "other$i@mail.uz", "x$i", listOf("https://other$i.example.com/")) }
    t = System.nanoTime(); val p10 = ImportPlanner.plan(v10, in10); println("  planner 10,000 x 10,000 all new: ${ms(t)} ms (add=${p10.add.size})")

    // ------------------------------------------------------------------ 18 container sizes
    section("18 Agile + CFB at boundary sizes (arbitrary payloads)")
    val rnd = java.util.Random(7)
    val sizes = listOf(0, 1, 15, 16, 17, 4079, 4080, 4087, 4088, 4095, 4096, 4097, 8192, 65_536, 7_000_000, 7_200_000, 15_000_000)
    for (n in sizes) {
        val payload = ByteArray(n).also(rnd::nextBytes)
        File(out, "payload-$n.bin").writeBytes(payload)
        File(out, "payload-$n.locked").writeBytes(AgileEncryption.encrypt(payload, XPW))
    }
    println("  wrote ${sizes.size} payloads")

    // ------------------------------------------------------------------ 19 a real workbook over 7 MB (DIFAT)
    section("19 large vault workbook (DIFAT path)")
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789абвгдежзийклмнопрстуфхцчшщ"
    val bigVault = (1..3000).map { i ->
        val note = String(CharArray(3000) { alphabet[rnd.nextInt(alphabet.length)] })
        ent("Entry $i", "user$i", "pw-$i-" + String(CharArray(12) { alphabet[rnd.nextInt(alphabet.length)] }), listOf("https://e$i.example"), note)
    }
    t = System.nanoTime(); val bx = xlsx(bigVault); val bxl = AgileEncryption.encrypt(bx, XPW)
    File(out, "big.xlsx").writeBytes(bx); File(out, "big-locked.xlsx").writeBytes(bxl)
    manifest["big.xlsx"] = JsonArray(listOf(JsonArray(VaultXlsx.columns(VaultXlsx.Labels()).map { units(it.header) })) + bigVault.map { e -> JsonArray(VaultXlsx.row(e, TZ).map { units(it ?: "") }) })
    println("  big.xlsx ${bx.size} bytes, locked ${bxl.size} bytes, ${ms(t)} ms")

    File(out, "manifest.json").writeText(JsonObject(manifest).toString())
    println("\nmanifest written: ${manifest.keys}")
}
