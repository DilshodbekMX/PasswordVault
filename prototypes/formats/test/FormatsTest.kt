import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import pv.formats.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone

var failures = 0
fun check(cond: Boolean, msg: String) { if (!cond) { failures++; println("FAIL: $msg") } }

val T0 = IsoTime.parse("2026-10-06T07:05:09.123Z")!!
var idCounter = 0
fun id() = String.format("00000000-0000-4000-8000-%012d", ++idCounter)
const val DAY = 86_400_000L

/** The awkward cases the brief lists, one entry each. Some break ux.md's input rules on purpose. */
fun specialEntries(): List<Entry> {
    val longNote = buildString {
        var i = 0
        while (length < 40_000) { append("Qator $i: o‘zbek matni, русский текст, emoji 😀. "); i++ }
    }.substring(0, 40_000)
    return listOf(
        Entry(id(), "O‘zbekiston pochtasi (U+2018)", "g‘alaba@pochta.uz", "o‘zbek‘2026", listOf("https://pochta.uz"),
            "Izoh: g‘alaba, o‘zbek", createdAt = T0, updatedAt = T0),
        Entry(id(), "Oʻzbekiston banki (U+02BB)", "gʻalaba", "oʻzbekʻ2026", listOf("https://bank.uz"), "Oʻzbek tili: gʻalaba",
            createdAt = T0, updatedAt = T0),
        Entry(id(), "Почта России", "иван.петров@mail.ru", "Пароль123", listOf("https://mail.ru/login"), "Заметка: «важно»",
            createdAt = T0, updatedAt = T0),
        Entry(id(), "Games 🎮", "player🙂", "p@ss🔑word", listOf("https://games.example"), "👨‍👩‍👧 family plan", createdAt = T0, updatedAt = T0),
        Entry(id(), "בנק הפועלים / البنك", "مستخدم", "كلمة123", listOf("https://bank.example.il"), "RTL: שלום world مرحبا 123",
            createdAt = T0, updatedAt = T0),
        Entry(id(), "Smith, John \"JJ\"", "john,smith", "a,b\"c\"\"d", listOf("https://comma.example/?a=1,2"), "He said \"hi\", then left",
            createdAt = T0, updatedAt = T0),
        Entry(id(), "Multi-line notes", "user", " spaced password ", listOf("https://lines.example"), "line1\nline2\r\nline3\rline4\n",
            createdAt = T0, updatedAt = T0),
        Entry(id(), "=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "-2+3", listOf("https://formula.example"),
            "@SUM(A1:A2)", listOf(CustomField("calc", "=1+1", false)), createdAt = T0, updatedAt = T0),
        Entry(id(), "Tab case", "tab\tuser", "tab\there", listOf("https://tab.example"), "\tleading tab", createdAt = T0, updatedAt = T0),
        Entry(id(), "Control characters", "ctrl", "ctrl\u0001char", listOf("https://ctrl.example"),
            "bell\u0007 unit\u001F nonchar\uFFFE literal _x0041_ and _x005F_", createdAt = T0, updatedAt = T0),
        Entry(id(), "Long note", "longnote", "pw-long", listOf("https://long.example"), longNote, createdAt = T0, updatedAt = T0),
        Entry(id(), "Empty fields", createdAt = T0, updatedAt = T0),
        Entry(id(), "Full entry", "full@example.com", "Current#1", listOf("https://a.example/login", "https://b.example", "android://AbC123==@com.example.app/"),
            "Everything set", listOf(CustomField("PIN code", "4321", true), CustomField("Security question", "First school?", false),
                CustomField("Address", "line one\nline two", false)),
            favourite = true, createdAt = T0 - DAY * 400, updatedAt = T0, passwordChangedAt = T0 - DAY * 30,
            history = listOf(HistoryItem("Old#2", T0 - DAY * 30), HistoryItem("Older#3", T0 - DAY * 200)),
            changePending = true, checkIgnored = true),
        Entry(id(), "No scheme", "me", "pw", listOf("gmail.com"), "", createdAt = T0, updatedAt = T0),
        Entry(id(), "Many lines", "lines", "pw300", listOf("https://many.example"), (1..300).joinToString("\n") { "recovery code $it" },
            createdAt = T0, updatedAt = T0),
    )
}

fun bulkEntries(n: Int): List<Entry> {
    val rnd = java.util.Random(42)
    val words = listOf("o‘zbek", "gʻalaba", "почта", "банк", "Toshkent", "Samarqand", "магазин", "kino", "🎵", "shop")
    val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%^&*()-_=+[]{};:,.<>/?\"'"
    return List(n) { i ->
        val w = words[i % words.size]
        Entry(id(), "$w ${i + 1}", "user$i@example.com", String(CharArray(16) { chars[rnd.nextInt(chars.length)] }),
            listOf("https://site$i.example.com/"), if (i % 7 == 0) "Note $i: $w\nsecond line, with comma" else "",
            if (i % 11 == 0) listOf(CustomField("PIN", "%04d".format(i % 10000), true)) else emptyList(),
            favourite = i % 13 == 0, createdAt = T0 - i * 60_000L, updatedAt = T0,
            passwordChangedAt = if (i % 3 == 0) T0 - i * 3_600_000L else null,
            history = if (i % 17 == 0) listOf(HistoryItem("old$i", T0 - DAY)) else emptyList())
    }
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "out-files" }).apply { mkdirs() }
    val special = specialEntries()
    val bulk = bulkEntries(5000)
    val all = special + bulk
    val valid = all.filter { Limits.problems(it).isEmpty() && Limits.clean(it, IntArray(1)) == it } // what ux.md's input rules allow
    val zone = TimeZone.getTimeZone("Asia/Tashkent")

    // ---------------- CSV (Google layout)
    val csv = GoogleCsv.export(all)
    File(outDir, "export.csv").writeText(csv, Charsets.UTF_8)
    check(csv.startsWith("name,url,username,password,note\r\n"), "CSV header")
    val rep = GoogleCsv.report(all)
    println("CSV: rows=${rep.rows} bytes=${rep.bytes} noUrl=${rep.noUrl} badUrl=${rep.badUrl} noPassword=${rep.noPassword} longNote=${rep.longNote} " +
        "parts=${rep.parts} entriesWithFields=${rep.entriesWithFields}")
    val expectedCsv = GoogleCsv.rows(all)
    File(outDir, "expected-csv-rows.json").writeText(Json.encodeToString(JsonArray.serializer(),
        JsonArray(expectedCsv.map { r -> JsonArray(r.map(::JsonPrimitive)) })))
    val parsed = Csv.parse(csv)
    check(parsed.problems.isEmpty(), "CSV parse problems ${parsed.problems}")
    check(parsed.rows.drop(1) == expectedCsv, "CSV round trip rows")
    val parts = GoogleCsv.exportParts(all)
    parts.forEachIndexed { i, p -> File(outDir, "export-part${i + 1}.csv").writeText(p, Charsets.UTF_8) }
    check(parts.size == 2 && parts.all { p -> Csv.parse(p).rows.size - 1 <= 3000 && p.toByteArray().size <= 1000 * 1024 } &&
        parts.flatMap { Csv.parse(it).rows.drop(1) } == expectedCsv, "CSV parts for Google: ${parts.map { Csv.parse(it).rows.size - 1 }}")

    // CSV import round trip: what survives, what the model rules remove.
    val csvBack = Importer.read(csv.toByteArray(), T0) { id() }
    check(csvBack.format == Importer.Format.GOOGLE_CSV, "CSV detected as Google")
    val full = csvBack.entries.first { it.title == "Full entry" }
    val fullIn = special.first { it.title == "Full entry" }
    check(full.urls == fullIn.urls, "multi-url merge ${full.urls}")
    check(full.notes == "Everything set" && full.fields == fullIn.fields, "fields block round trip ${full.notes} ${full.fields}")
    check(csvBack.entries.map { it.title }.containsAll(valid.map { it.title }), "valid titles survive CSV")
    val validBack = csvBack.entries.associateBy { it.title }
    check(valid.all { v -> validBack[v.title]?.let { it.username == v.username && it.password == v.password && it.urls == v.urls.map(GoogleCsv::normalizeUrl) && it.fields == v.fields && it.notes == v.notes } == true },
        "valid entries keep username, password, websites, notes and fields through CSV")
    check(validBack["Control characters"]!!.password == "ctrlchar", "U+0001 removed on import")
    check(validBack["Tab case"]!!.password == "tabhere", "tab removed on import (ux.md rule)")
    check(csvBack.skipped.any { it.startsWith("Long note:") }, "40k note listed as over the limit")
    check(validBack["Multi-line notes"]!!.password == " spaced password ", "spaces survive CSV")
    check(validBack["No scheme"]!!.urls == listOf("https://gmail.com"), "scheme added")
    check(validBack["Many lines"]!!.notes.lines().size == 300, "300-line note survives CSV")
    println("CSV import: ${csvBack.entries.size} entries, skipped ${csvBack.skipped}, control characters removed ${csvBack.controlCharsRemoved}")

    // BOM, LF row ends, Windows-1251 with ';', Uzbek headers.
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray()
    check(Importer.read(bom, T0) { id() }.entries.size == csvBack.entries.size, "BOM accepted")
    val lf = Csv.write(GoogleCsv.HEADER, GoogleCsv.rows(special), "\n")
    check(Importer.read(lf.toByteArray(), T0) { id() }.entries.first { it.title == "Почта России" }.password == "Пароль123", "LF file")
    val ru1251 = "Название;Сайт;Логин;Пароль;Примечание\r\nПочта;mail.ru;иван;Пароль1;заметка\r\n".toByteArray(charset("windows-1251"))
    val ruBack = Importer.read(ru1251, T0) { id() }
    check(ruBack.charset == "windows-1251" && ruBack.delimiter == ';' && ruBack.entries.single().password == "Пароль1" &&
        ruBack.entries.single().title == "Почта" && ruBack.entries.single().urls == listOf("mail.ru"), "Russian Excel CSV (cp1251, ';')")
    val uz = "Nomi,Sayt,Login,Parol,Izoh,PIN kod,Telefon\nBank,bank.uz,ali,Parol1,eslatma,1234,+998901234567\n"
    val uzBack = Importer.read(uz.toByteArray(), T0) { id() }
    check(uzBack.entries.single().fields == listOf(CustomField("PIN kod", "1234", true), CustomField("Telefon", "+998901234567", false)),
        "Uzbek header + unknown columns ${uzBack.entries.single().fields}")

    // Bitwarden CSV and KeePassXC CSV samples (headers from their exporters' source).
    val bwCsv = "folder,favorite,type,name,notes,fields,reprompt,archivedDate,login_uri,login_username,login_password,login_totp\n" +
        "Bank,1,login,My bank,\"note, with comma\",\"PIN: 1234\nQ: A: B\",0,,\"https://a.example,https://b.example\",ali,s3cret,JBSWY3DP\n" +
        ",,note,Secure note,just text,,0,,,,,\n"
    val bwBack = Importer.read(bwCsv.toByteArray(), T0) { id() }
    check(bwBack.format == Importer.Format.BITWARDEN_CSV, "Bitwarden CSV detected")
    val bw0 = bwBack.entries[0]
    check(bw0.urls == listOf("https://a.example", "https://b.example") && bw0.favourite &&
        bw0.fields.map { it.name } == listOf("PIN", "Q: A", "TOTP", "Folder"), "Bitwarden CSV mapping ${bw0.fields}")
    check(bwBack.entries[1].password.isEmpty() && bwBack.entries[1].notes == "just text", "Bitwarden note row")
    val kpCsv = "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\",\"Icon\",\"Last Modified\",\"Created\"\n" +
        "\"Root/Internet\",\"Forum\",\"me\",\"p\"\"w\",\"https://forum.example\",\"two\nlines\",\"\",\"0\",\"2024-05-01T10:00:00Z\",\"2023-01-02T03:04:05Z\"\n"
    val kpBack = Importer.read(kpCsv.toByteArray(), T0) { id() }
    val kp0 = kpBack.entries.single()
    check(kpBack.format == Importer.Format.KEEPASSXC_CSV && kp0.fields == listOf(CustomField("Folder", "Internet", false)) && kp0.password == "p\"w" &&
        kp0.createdAt == IsoTime.parse("2023-01-02T03:04:05Z") && kp0.updatedAt == IsoTime.parse("2024-05-01T10:00:00Z"), "KeePassXC CSV")

    // ---------------- JSON (Bitwarden layout + passwordVault extension)
    val withExtras = valid.mapIndexed { i, e -> if (i == 0) e.copy(extras = mapOf("futureField" to "{\"a\":[1,2]}")) else e }
    val json = BitwardenJson.export(withExtras, T0, "PasswordVault prototype")
    File(outDir, "export.json").writeText(json, Charsets.UTF_8)
    val jsonBack = Importer.read(json.toByteArray(), T0) { id() }
    check(jsonBack.format == Importer.Format.OWN_JSON, "own JSON detected")
    check(jsonBack.entries == withExtras, "JSON round trip is lossless" + firstDiff(withExtras, jsonBack.entries))
    File(outDir, "export-all.json").writeText(BitwardenJson.export(all, T0, "PasswordVault prototype"), Charsets.UTF_8)
    // The same file read as a foreign Bitwarden export (extension removed).
    val stripped = Json.parseToJsonElement(json).let { root ->
        val o = root as JsonObject
        JsonObject(o.filterKeys { it != "passwordVault" } + ("items" to JsonArray((o["items"] as JsonArray).map { JsonObject((it as JsonObject).filterKeys { k -> k != "passwordVault" }) })))
    }
    val foreign = Importer.read(stripped.toString().toByteArray(), T0) { id() }
    check(foreign.format == Importer.Format.BITWARDEN_JSON, "foreign Bitwarden JSON detected")
    val ff = foreign.entries.first { it.title == "Full entry" }
    check(ff.passwordChangedAt == null && !ff.changePending && ff.history.size == 2 && ff.fields == fullIn.fields &&
        ff.urls.last() == "androidapp://com.example.app", "foreign JSON mapping ${ff.urls}")
    val enc = "{\"encrypted\": true, \"passwordProtected\": true, \"salt\": \"x\", \"data\": \"y\"}"
    check(runCatching { Importer.read(enc.toByteArray(), T0) }.exceptionOrNull().let { it is Importer.Unsupported && it.reason == Importer.Reason.ENCRYPTED_JSON }, "encrypted JSON refused")
    val lone = BitwardenJson.export(listOf(Entry(id(), "lone \uD800 surrogate", createdAt = T0, updatedAt = T0)), T0, "t")
    val loneLine = lone.lines().first { it.contains("lone") }
    val loneBytes = loneLine.toByteArray(Charsets.UTF_8)
    println("Lone surrogate: kotlinx writes it unescaped (char ${"%04X".format(loneLine[loneLine.indexOf("lone ") + 5].code)}); " +
        "UTF-8 encoding then stores byte 0x${"%02X".format(loneBytes[loneBytes.indexOf('l'.code.toByte()) + 5])} ('?')")

    // ---------------- XLSX
    val xbuf = ByteArrayOutputStream()
    val stats = VaultXlsx.write(xbuf, all, zone)
    val xlsx = xbuf.toByteArray()
    File(outDir, "export.xlsx").writeBytes(xlsx)
    println("XLSX: ${xlsx.size} bytes, rows=${stats.rows}, sharedStrings=${stats.sharedStrings}, truncatedCells=${stats.truncatedCells}")
    check(stats.truncatedCells == 1, "one cell truncated")
    val expectedCells = buildJsonArray {
        add(JsonArray(VaultXlsx.columns(VaultXlsx.Labels()).map { JsonPrimitive(it.header) }))
        all.forEach { e ->
            add(JsonArray(VaultXlsx.row(e, zone).map { v ->
                val t = Xlsx.limitLineFeeds(Xlsx.normalizeNewlines(v ?: ""))
                JsonPrimitive(if (t.length > Xlsx.MAX_CELL_CHARS) "truncated:" + t.substring(0, 1000) else t)
            }))
        }
    }
    File(outDir, "expected-xlsx-cells.json").writeText(expectedCells.toString())
    File(outDir, "special.xlsx").outputStream().use { VaultXlsx.write(it, special, zone) }

    // ---------------- Password-protected XLSX
    val pw = "tulip-arena-swirl-cobalt-mango" // the kind of password the app would generate (5 EFF words)
    val t1 = System.nanoTime()
    val protected = AgileEncryption.encrypt(xlsx, pw, SecureRandom())
    val ms = (System.nanoTime() - t1) / 1_000_000
    File(outDir, "export-protected.xlsx").writeBytes(protected)
    println("Protected XLSX: ${protected.size} bytes, encrypt ${ms} ms (100,000 SHA-512 spins + AES)")
    val small = ByteArrayOutputStream().also { VaultXlsx.write(it, special.take(3), zone) }.toByteArray()
    File(outDir, "special-protected-unicode-pw.xlsx").writeBytes(AgileEncryption.encrypt(small, "Parol‘ Пароль 2026", SecureRandom()))
    File(outDir, "special-small.xlsx").writeBytes(small)
    val tiny = ByteArrayOutputStream().also { VaultXlsx.write(it, emptyList(), zone) }.toByteArray()
    File(outDir, "empty.xlsx").writeBytes(tiny)
    File(outDir, "empty-protected.xlsx").writeBytes(AgileEncryption.encrypt(tiny, pw, SecureRandom()))
    println("Empty-vault xlsx: ${tiny.size} bytes (under 4096, so EncryptedPackage goes to the mini stream)")
    AgileEncryption.fixedStreams().forEach { (k, v) -> File(outDir, "fixed-$k.bin").writeBytes(v) }

    // ---------------- Import planning
    val vault = special.take(5)
    val incoming = listOf(
        special[0].copy(id = id()),                                    // identical -> already in vault
        special[1].copy(id = id(), password = "changed"),              // same site+user, new password -> conflict, added to compare
        special[2].copy(id = id(), notes = ""),                         // same, no notes -> already in vault
        special[3].copy(id = id(), urls = listOf("https://other.example")), // different site -> add
        special[3].copy(id = id(), urls = listOf("https://other.example")), // duplicate inside the file
        Entry(id(), "No password", "u", "", listOf("https://np.example"), createdAt = T0, updatedAt = T0),
    )
    val plan = ImportPlanner.plan(vault, incoming)
    check(plan.alreadyInVault == 2 && plan.conflicts.size == 1 && plan.add.size == 3 && plan.duplicateInFile == 1 && plan.withoutPassword == 1,
        "planner: inVault=${plan.alreadyInVault} conflicts=${plan.conflicts.size} add=${plan.add.size} dupInFile=${plan.duplicateInFile}")
    val replaced = ImportPlanner.replacePassword(plan.conflicts[0].existing, plan.conflicts[0].incoming, T0)
    check(replaced.password == "changed" && replaced.history.first().password == special[1].password, "replace keeps old password in history")

    // ---------------- Timing for 5,000 entries
    fun time(label: String, block: () -> Unit) { val s = System.nanoTime(); block(); println("$label: ${(System.nanoTime() - s) / 1_000_000} ms") }
    time("CSV export ${all.size}") { GoogleCsv.export(all) }
    time("CSV import ${all.size}") { Importer.read(csv.toByteArray(), T0) { "x" } }
    time("JSON export ${valid.size}") { BitwardenJson.export(valid, T0, "t") }
    time("JSON import ${valid.size}") { Importer.read(json.toByteArray(), T0) { "x" } }
    time("XLSX export ${all.size}") { VaultXlsx.write(ByteArrayOutputStream(), all, zone) }

    println(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}

fun firstDiff(a: List<Entry>, b: List<Entry>): String {
    if (a.size != b.size) return " (size ${a.size} vs ${b.size})"
    for (i in a.indices) if (a[i] != b[i]) return " (first difference at $i:\n  ${a[i]}\n  ${b[i]})"
    return ""
}
