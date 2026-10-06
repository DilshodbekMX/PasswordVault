import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import pv.formats.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.TimeZone

// Second set of probes. Usage: Attack2Kt MODE OUTDIR
//   json      own JSON with unknown keys; a bad byte inside a JSON string
//   commas    a 10 MB CSV made of commas (run with a phone-sized heap)
//   nest      a 10 MB JSON of nested arrays (run with a phone-sized heap)
//   tz        writes the same workbook with the JVM's default time zone (run twice with -Duser.timezone)
//   wide      header with many columns within the 10 MB limit (timing)

val Z0 = IsoTime.parse("2026-10-06T07:05:09.123Z")!!
var n2 = 0
fun id2() = String.format(Locale.ROOT, "bbbbbbbb-0000-4000-8000-%012d", ++n2)
fun e2(title: String, user: String = "", pw: String = "", urls: List<String> = emptyList(), notes: String = "") =
    Entry(id2(), title, user, pw, urls, notes, createdAt = Z0, updatedAt = Z0)

fun read2(b: ByteArray): Importer.Preview? = try { Importer.read(b, Z0) { id2() } } catch (e: Importer.Unsupported) { println("  refused: ${e.message?.take(160)}"); null }

fun main(args: Array<String>) {
    val mode = args[0]
    val out = File(args.getOrElse(1) { "out-attack" }).apply { mkdirs() }
    when (mode) {
        "json" -> {
            val text = BitwardenJson.export(listOf(e2("Own", "u", "p", listOf("https://o.example"))), Z0, "t")
            val root = Json.parseToJsonElement(text).jsonObject
            val item = root["items"]!!.jsonArray[0].jsonObject
            val ext = item["passwordVault"]!!.jsonObject
            val newItem = JsonObject(item + ("itemFuture" to JsonPrimitive(5)) + ("passwordVault" to JsonObject(ext + ("extFuture" to Json.parseToJsonElement("{\"a\":1}")))))
            val newRoot = JsonObject(root + ("passwordVault" to JsonObject(root["passwordVault"]!!.jsonObject + ("rootFuture" to JsonPrimitive(7)))) +
                ("items" to kotlinx.serialization.json.JsonArray(listOf(newItem))))
            val p = read2(newRoot.toString().toByteArray())!!
            val again = BitwardenJson.export(p.entries, Z0, "t")
            println("own JSON with unknown keys: extras=${p.entries.single().extras}; re-export keeps extFuture=${again.contains("extFuture")} itemFuture=${again.contains("itemFuture")} rootFuture=${again.contains("rootFuture")}")

            val json = BitwardenJson.export(listOf(e2("Почта", "иван", "Пароль1", notes = "заметка"), e2("Bank", "ali", "Oʻzbek‘1")), Z0, "t").toByteArray()
            val at = String(json, Charsets.UTF_8).indexOf("заметка")
            val atByte = String(json, Charsets.UTF_8).substring(0, at).toByteArray().size
            val bad = json.copyOf().also { it[atByte + 1] = 0xFF.toByte() } // break the second byte of the first Cyrillic letter
            read2(bad)?.let { pr -> println("JSON with one invalid byte inside a note: charset=${pr.charset} titles=${pr.entries.map { it.title }} passwords=${pr.entries.map { it.password }} problems=${pr.problems}") }
        }
        "commas" -> {
            val text = "name,url,username,password\n" + ",".repeat(10 * 1024 * 1024 - 40) + "\n"
            val t = System.nanoTime()
            try {
                val p = read2(text.toByteArray())
                println("10 MB of commas: ${p?.entries?.size} entries in ${(System.nanoTime() - t) / 1_000_000} ms, max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB")
            } catch (e: Throwable) {
                println("10 MB of commas: ${e::class.qualifiedName}: ${e.message} after ${(System.nanoTime() - t) / 1_000_000} ms (max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB) -- not an Exception subclass: ${e !is Exception}")
            }
        }
        "nest" -> {
            val depth = 5 * 1024 * 1024 - 64
            val text = "{\"items\":" + "[".repeat(depth) + "]".repeat(depth) + "}"
            val t = System.nanoTime()
            try {
                val p = read2(text.toByteArray())
                println("nested JSON depth $depth: ${p?.entries?.size} entries in ${(System.nanoTime() - t) / 1_000_000} ms")
            } catch (e: Throwable) {
                println("nested JSON depth $depth: ${e::class.qualifiedName} after ${(System.nanoTime() - t) / 1_000_000} ms (max heap ${Runtime.getRuntime().maxMemory() / 1048576} MB) -- caught by catch(Exception): ${e is Exception}")
            }
        }
        "tz" -> {
            val bytes = ByteArrayOutputStream().also { VaultXlsx.write(it, listOf(e2("A", "u", "p", listOf("https://a.example"))), TimeZone.getTimeZone("Asia/Tashkent")) }.toByteArray()
            val name = "tz-" + TimeZone.getDefault().id.replace('/', '_') + ".xlsx"
            File(out, name).writeBytes(bytes)
            println("wrote $name (${bytes.size} bytes) with default zone ${TimeZone.getDefault().id}")
        }
        "wide" -> {
            for ((cols, rows) in listOf(20_000 to 400, 100_000 to 80)) {
                val sb = StringBuilder()
                sb.append((1..cols).joinToString(",") { "c$it" }).append(",name,url,username,password\n")
                repeat(rows) { sb.append(",".repeat(cols)).append("t$it,https://w$it.example,u,p\n") }
                val bytes = sb.toString().toByteArray()
                val t = System.nanoTime()
                val p = read2(bytes)
                println("header of ${cols + 4} columns, $rows rows (${bytes.size / 1024} KB): ${p?.entries?.size} entries in ${(System.nanoTime() - t) / 1_000_000} ms")
            }
        }
    }
}
