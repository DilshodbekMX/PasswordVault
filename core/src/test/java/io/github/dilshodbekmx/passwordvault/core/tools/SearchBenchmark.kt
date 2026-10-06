package io.github.dilshodbekmx.passwordvault.core.tools

import io.github.dilshodbekmx.passwordvault.core.search.SearchFields
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Time to filter 5,000 entries per keystroke. Run: ./gradlew bench
 * (add -PbenchJvmArgs=-Xint to run without the JIT compiler, a pessimistic bound).
 */
private val NAMES = listOf(
    "Oʻzbekiston Temir Yoʻllari", "Ўзбектелеком", "Telegram", "Сбербанк Онлайн", "Click Uz", "Payme", "Gmail",
    "Gʻalaba fitnes", "Яндекс Почта", "Kapitalbank", "Uzum Bank", "Davlat xizmatlari markazi", "Ипак Йўли банки",
    "Mail.ru", "WhatsApp", "Instagram", "Facebook", "YouTube", "Netflix", "Spotify", "Wildberries", "Ozon",
    "Тошкент шаҳар ҳокимлиги", "Soliq kabineti", "my.gov.uz", "Beeline", "Ucell", "Uzmobile", "Humans",
    "Hamkorbank", "Agrobank", "Xalq banki", "Asaka bank", "Anorbank", "TBC Bank", "Узнацбанк", "Госуслуги",
    "Вконтакте", "Одноклассники", "Avito", "OLX", "Yandex Go", "Steam", "Epic Games", "PlayStation",
    "Microsoft", "Apple ID", "Dropbox", "GitHub", "Stack Overflow", "Coursera", "Udemy", "Duolingo",
    "Qoʻqon kolleji", "Samarqand universiteti", "Buxoro aeroporti", "Хоразм вилояти", "Fargʻona", "Namangan",
    "Andijon bozori", "Ёшлар агентлиги", "Kitob doʻkoni", "Uy Wi-Fi", "Ish pochtasi", "Bolalar bogʻchasi",
    "Klinika", "Dorixona", "Taksi", "Avtoservis", "Kommunal toʻlovlar", "Gaz", "Elektr", "Suv",
)
private val WORDS = listOf(
    "shaxsiy", "ish", "oila", "eski", "yangi", "asosiy", "zaxira", "test", "онлайн", "кабинет", "личный",
    "rabochiy", "2024", "2025", "admin", "user", "mobile", "web", "karta", "hisob",
)
private val DOMAINS = listOf("gmail.com", "mail.ru", "yandex.ru", "inbox.uz", "umail.uz", "outlook.com", "icloud.com")

private fun buildEntries(n: Int, seed: Int): List<SearchFields> {
    val r = Random(seed)
    return List(n) { i ->
        val base = NAMES[r.nextInt(NAMES.size)]
        val title = when (r.nextInt(4)) {
            0 -> base
            1 -> "$base ${WORDS[r.nextInt(WORDS.size)]}"
            2 -> "$base ${r.nextInt(1, 100)}"
            else -> "${WORDS[r.nextInt(WORDS.size)]} $base"
        }
        val user = when (r.nextInt(3)) {
            0 -> "dilshod${r.nextInt(1000)}@${DOMAINS[r.nextInt(DOMAINS.size)]}"
            1 -> "+99890${r.nextInt(1000000, 9999999)}"
            else -> "user_${r.nextInt(100000)}"
        }
        val host = base.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.ifEmpty { "site$i" }
        val sites = List(1 + r.nextInt(2)) { k -> "https://${if (k == 0) "www." else "m."}$host.uz/login?id=$i" }
        val extras = if (r.nextInt(5) == 0) listOf("PIN kod", "Kodli soʻz") else emptyList()
        val notes = if (r.nextInt(10) < 3) {
            "Parolni har 90 kunda almashtirish kerak. Bank kartasi bilan bogʻlangan. Telefon: +99890${r.nextInt(1000000, 9999999)}"
        } else {
            ""
        }
        SearchFields(title, user, sites, extras, notes)
    }
}

private val TYPED = listOf(
    "o'zbek", "telegram", "сбербанк", "ipak yo'li", "gmail.com", "toshkent", "ўзбекистон", "хизмат",
    "bank karta", "zzzz", "a", "dilshod", "+998", "кабинет", "wi-fi",
)

private fun percentile(sorted: LongArray, p: Double): Long = sorted[((sorted.size - 1) * p).toInt()]

private fun micros(ns: Long) = String.format(Locale.ROOT, "%.0f µs", ns / 1000.0)
private fun millis(ns: Long) = String.format(Locale.ROOT, "%.2f ms", ns / 1_000_000.0)

fun main() {
    val n = 5000
    val entries = buildEntries(n, seed = 2026)
    val keystrokes = TYPED.flatMap { q -> (1..q.length).map { q.substring(0, it) } }
    val out = StringBuilder()
    fun say(s: String) {
        println(s); out.append(s).append('\n')
    }
    val cpu = runCatching {
        File("/proc/cpuinfo").readLines().first { it.startsWith("model name") }.substringAfter(":").trim()
    }.getOrDefault("unknown CPU")
    say("JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, " +
        "flags ${java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments}, CPU $cpu")
    say("$n entries; ${keystrokes.size} keystrokes per round (${TYPED.size} queries typed letter by letter)")

    // Index build (once per unlock).
    var index = SearchIndex(entries)
    repeat(5) { index = SearchIndex(entries) }
    val builds = LongArray(15) {
        val t = System.nanoTime(); index = SearchIndex(entries); System.nanoTime() - t
    }.sorted()
    say("index build: median ${millis(builds[builds.size / 2])}, max ${millis(builds.last())}")

    // Warm-up, then timed rounds of every keystroke.
    var sink = 0
    repeat(30) { for (k in keystrokes) sink += index.search(k).size }
    val rounds = 40
    val times = LongArray(rounds * keystrokes.size)
    var t = 0
    repeat(rounds) {
        for (k in keystrokes) {
            val s = System.nanoTime()
            sink += index.search(k).size
            times[t++] = System.nanoTime() - s
        }
    }
    times.sort()
    say("search per keystroke (cached index): median ${micros(percentile(times, 0.5))}, " +
        "p95 ${micros(percentile(times, 0.95))}, p99 ${micros(percentile(times, 0.99))}, max ${micros(times.last())}")

    // Worst query class: one letter matches nearly everything and sorts 5,000 hits.
    val one = LongArray(200) {
        val s = System.nanoTime(); sink += index.search("a").size; System.nanoTime() - s
    }.sorted()
    say("one-letter query \"a\" (${index.search("a").size} hits): median ${micros(one[one.size / 2])}")

    // Without a cached index: fold every field on every keystroke.
    val uncached = LongArray(20) {
        val s = System.nanoTime(); sink += SearchIndex(entries).search("telegr").size; System.nanoTime() - s
    }.sorted()
    say("without the cached index (fold all fields per keystroke): median ${millis(uncached[uncached.size / 2])}")

    // Heavy notes: every entry has a 1,000-character note (ux.md allows up to 10,000).
    val noteText = ("Bank kartasi PIN kodi alohida saqlanadi. Telefon raqami oʻzgargan. " +
        "Kodli soʻz onamning tugʻilgan shahri. Почта ящик восстановления указан. ").repeat(8).take(1000)
    val heavy = SearchIndex(entries.map { it.copy(notes = noteText) })
    repeat(10) { for (k in keystrokes) sink += heavy.search(k).size }
    val heavyTimes = LongArray(10 * keystrokes.size)
    var h = 0
    repeat(10) {
        for (k in keystrokes) {
            val s = System.nanoTime(); sink += heavy.search(k).size; heavyTimes[h++] = System.nanoTime() - s
        }
    }
    heavyTimes.sort()
    say("every entry with a 1,000-character note: median ${micros(percentile(heavyTimes, 0.5))}, " +
        "p95 ${micros(percentile(heavyTimes, 0.95))}, max ${micros(heavyTimes.last())}")

    val rt = Runtime.getRuntime()
    repeat(3) { System.gc() }
    val before = rt.totalMemory() - rt.freeMemory()
    var keep: SearchIndex? = SearchIndex(entries)
    repeat(3) { System.gc() }
    val after = rt.totalMemory() - rt.freeMemory()
    say("approximate index size: ${(after - before) / 1024} KiB (${keep!!.size} entries)")
    keep = null
    say("(sink $sink)")
    File("build/bench").mkdirs()
    val flags = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.joinToString("_")
        .replace(Regex("[^A-Za-z0-9_=-]"), "")
    File("build/bench/result-${if (flags.contains("Xint")) "Xint" else "jit"}.txt").writeText(out.toString())
}
