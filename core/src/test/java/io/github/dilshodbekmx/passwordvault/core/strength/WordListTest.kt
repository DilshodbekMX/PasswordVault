package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bundled word list (build plan 2.12, "The list"): its format, what it must hold, its size, and
 * `SOURCES.md`, which names each source with its licence and the count taken from it.
 */
class WordListTest {

    private val dir = File(SourceTree.root(), "core/src/main/resources/strength")

    private fun text(file: String) = File(dir, file).readText(Charsets.UTF_8)

    private fun entries(file: String) = WordList.parse(file, text(file))

    private fun isCyrillic(s: String) = s.any { it.code in 0x0400..0x04FF }

    private fun isLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    @Test
    fun everyFileIsWellFormedAndPackaged() {
        for (file in WordList.FILES) {
            assertTrue(file, entries(file).isNotEmpty())
            assertNotNull(file, WordList::class.java.getResource(WordList.DIRECTORY + file))
        }
        assertEquals(WordList.FILES.toSet() + "SOURCES.md", dir.list()!!.toSet())
    }

    @Test
    fun theListIsAtMostOneMegabyte() {
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("the strength directory holds $total bytes", total <= 1_000_000)
    }

    @Test
    fun placesOfUzbekistanInBothScripts() {
        val places = entries("places.txt")
        for (entry in places) assertTrue(entry.first(), entry.any(::isCyrillic) && entry.any(::isLatin))
        // The 14 first-level divisions (Toshkent is both the region and the city) and the regional centres.
        val wanted = listOf(
            "Andijon", "Buxoro", "Fargʻona", "Jizzax", "Xorazm", "Namangan", "Navoiy", "Qashqadaryo", "Samarqand",
            "Sirdaryo", "Surxondaryo", "Toshkent", "Qoraqalpogʻiston", "Nukus", "Termiz", "Qarshi", "Urganch", "Guliston",
            "Ташкент", "Самарқанд", "Фарғона", "Наманган",
        )
        for (name in wanted) assertTrue(name, places.any { name in it })
        assertTrue(places.size >= 200)
    }

    @Test
    fun atLeast500UzbekAnd500RussianFirstNames() {
        val uzbek = entries("names-uz.txt")
        assertTrue("${uzbek.size} Uzbek names", uzbek.size >= 500)
        for (entry in uzbek) assertTrue(entry.first(), entry.size == 2 && isLatin(entry[0]) && isCyrillic(entry[1]))
        val russian = entries("names-ru.txt")
        assertTrue("${russian.size} Russian names", russian.size >= 500)
        for (entry in russian) assertTrue(entry.first(), isCyrillic(entry[0]) && entry.drop(1).all(::isLatin))
    }

    @Test
    fun atLeast2000UzbekWordsInEachScriptAnd2000RussianWords() {
        val uzbek = entries("words-uz.txt")
        assertTrue(uzbek.size >= 2_000)
        for (entry in uzbek) assertTrue(entry.first(), entry.size == 2 && isLatin(entry[0]) && !isCyrillic(entry[0]) && isCyrillic(entry[1]))
        val russian = entries("words-ru.txt")
        assertTrue(russian.size >= 2_000)
        for (entry in russian) assertTrue(entry.first(), isCyrillic(entry[0]))
    }

    @Test
    fun uzbekLatinUsesTheAppsApostrophesAndNoSpellingIsRepeated() {
        for (file in WordList.FILES) {
            val seen = HashSet<String>()
            for (entry in entries(file)) {
                for (spelling in entry) {
                    assertTrue("$file: $spelling twice", seen.add(spelling.lowercase(java.util.Locale.ROOT)))
                    if (file != "common.txt") assertFalse("$file: $spelling", spelling.any { it in "'`‘’´" })
                }
            }
        }
    }

    @Test
    fun sourcesMdDescribesEachFileExactly() {
        val sources = text("SOURCES.md")
        val row = Regex("""^\| `([a-z-]+\.txt)` \| ([0-9,]+) \| ([0-9,]+) \| `([0-9a-f]{64})` \|""", RegexOption.MULTILINE)
        val rows = row.findAll(sources).associate { it.groupValues[1] to it.groupValues.drop(2) }
        assertEquals(WordList.FILES.toSet(), rows.keys)
        for (file in WordList.FILES) {
            val (entries, bytes, sha) = rows.getValue(file)
            val content = File(dir, file).readBytes()
            assertEquals(file, entries(file).size, entries.replace(",", "").toInt())
            assertEquals(file, content.size, bytes.replace(",", "").toInt())
            assertEquals(file, sha, MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { b -> "%02x".format(java.util.Locale.ROOT, b) })
        }
        val total = WordList.FILES.sumOf { File(dir, it).length() }
        assertTrue(sources.contains("Six files, " + "%,d".format(java.util.Locale.ROOT, total) + " bytes."))
    }

    @Test
    fun sourcesMdNamesEverySourceWithItsLicence() {
        val sources = text("SOURCES.md")
        for (needed in listOf(
            "GeoNames", "https://creativecommons.org/licenses/by/4.0/", "Wikipedia", "Wiktionary", "FrequencyWords",
            "OpenSubtitles", "https://creativecommons.org/licenses/by-sa/4.0/", "Written for this app",
            "uzwiki-20261001-pages-articles.xml.bz2", "525f9b560de45753a5ea01069454e72e9aa541c6",
        )) {
            assertTrue(needed, sources.contains(needed))
        }
    }

    @Test
    fun aDamagedFileIsRefusedWithoutItsText() {
        val control = "zeb" + 1.toChar() + "ra\n"
        val damaged = listOf("", "zebra", "zebra\r\n", "zebra\t\n", "ze\n", " zebra\n", "zebra \n", control, "zebra\n\n", "# notes only\n")
        for (text in damaged) {
            try {
                WordList.parse("test.txt", text)
                fail("accepted: ${text.length} characters")
            } catch (e: IllegalStateException) {
                val message = e.message.orEmpty()
                assertTrue(message, message.startsWith("strength word list test.txt"))
                assertFalse(message, message.contains("zeb"))
            }
        }
        assertEquals(listOf(listOf("Toshkent", "Тошкент"), listOf("parol")), WordList.parse("ok.txt", "# a note\nToshkent\tТошкент\nparol\n"))
    }
}
