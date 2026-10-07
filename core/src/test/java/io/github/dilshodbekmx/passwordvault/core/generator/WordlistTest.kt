package io.github.dilshodbekmx.passwordvault.core.generator

import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word list of architecture.md D-6a, as build-plan package 2.6 accepts it: 1,295 words, none
 * with a hyphen, EFF's file with only "yo-yo" removed, and a licence note that names the change.
 */
class WordlistTest {

    private val list = Wordlist.effShort

    private fun resource(path: String): ByteArray {
        val stream = Wordlist::class.java.getResourceAsStream(path)
        assertNotNull("the resource $path is on the class path", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray): String = Bytes.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun listText(): String = String(resource(Wordlist.RESOURCE), Charsets.UTF_8)

    @Test
    fun has1295WordsNoneWithAHyphen() {
        assertEquals(1_295, Wordlist.SIZE)
        assertEquals(Wordlist.SIZE, list.size)
        for (i in 0 until list.size) assertFalse("word $i has a hyphen", '-' in list[i])
        assertFalse("yo-yo" in list)
        assertTrue("yoyo, a word of its own, stays", "yoyo" in list)
        assertEquals("acid", list[0])
        assertEquals("zoom", list[list.size - 1])
    }

    @Test
    fun everyWordIs3To5LettersAToZ() {
        val word = Regex("[a-z]{3,5}")
        for (i in 0 until list.size) assertTrue("word $i", word.matches(list[i]))
        val letters = (0 until list.size).sumOf { list[it].length }
        assertEquals("4.5 letters on average (ux.md §5.3)", 4.54, letters.toDouble() / list.size, 0.005)
    }

    @Test
    fun theWordsAscendSoNoneIsThereTwice() {
        for (i in 1 until list.size) assertTrue("word $i after word ${i - 1}", list[i] > list[i - 1])
        assertEquals(list.size, (0 until list.size).map { list[it] }.toSet().size)
        for (i in 0 until list.size) assertEquals(i, list.indexOf(list[i]))
        assertEquals(-1, list.indexOf("Acid"))
        assertEquals(-1, list.indexOf("aardvark"))
    }

    @Test
    fun theResourceIsEffsFileWithOnlyTheLineOfYoYoRemoved() {
        val ours = resource(Wordlist.RESOURCE)
        assertEquals(13_649, ours.size)
        assertEquals(OURS_SHA256, sha256(ours))

        // Put the removed line back where it was: the bytes are then EFF's file of 2016-09-08.
        val text = String(ours, Charsets.UTF_8)
        val next = text.indexOf("6653\tyodel\n")
        assertTrue(next > 0 && text.startsWith("6651\t", text.lastIndexOf('\n', next - 2) + 1))
        val original = (text.substring(0, next) + "6652\tyo-yo\n" + text.substring(next)).toByteArray(Charsets.UTF_8)
        assertEquals(13_660, original.size)
        assertEquals(EFF_SHA256, sha256(original))
    }

    @Test
    fun theLicenceNoteNamesTheOriginTheLicenceAndTheChange() {
        val note = String(resource("/wordlists/SOURCES.md"), Charsets.UTF_8)
        for (needed in listOf(
            "https://www.eff.org/files/2016/09/08/eff_short_wordlist_1.txt",
            "Electronic Frontier Foundation",
            "CC BY 4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            "the line `6652<TAB>yo-yo` was removed",
            "1,295",
            EFF_SHA256,
            OURS_SHA256,
        )) {
            assertTrue("SOURCES.md names $needed", needed in note)
        }
    }

    @Test
    fun theFileReadsBackAsTheSameList() {
        val again = Wordlist.parse(listText())
        assertEquals(list.size, again.size)
        for (i in 0 until list.size) assertEquals(list[i], again[i])
        assertEquals("Wordlist(1295 words)", list.toString())
    }

    @Test
    fun aListThatIsNotExactlyThatOneIsRefused() {
        val text = listText()
        val lines = text.removeSuffix("\n").split('\n')
        fun joined(changed: List<String>) = changed.joinToString("\n", postfix = "\n")
        fun refused(why: String, bad: String, says: String) {
            val e = assertThrows(why, IllegalStateException::class.java) { Wordlist.parse(bad) }
            val message = e.message.orEmpty()
            assertTrue("$why: \"$message\" says $says", says in message)
            // The message names a line by its number, never by its text.
            assertFalse("$why: \"$message\" quotes the file", '\t' in message || Regex("\\b[a-z]{3,5}\\t").containsMatchIn(message))
        }

        val withYoYo = lines.toMutableList().apply { add(indexOf("6653\tyodel"), "6652\tyo-yo") }
        refused("EFF's file as it is", joined(withYoYo), "line 1286")
        assertFalse("yo-yo" in (assertThrows(IllegalStateException::class.java) { Wordlist.parse(joined(withYoYo)) }.message.orEmpty()))
        refused("one word less", joined(lines.drop(1)), "1294 words")
        refused("one word more", joined(lines + "6667\tzzz"), "line 1296")
        refused("CR LF line ends", text.replace("\n", "\r\n"), "line 1:")
        refused("no line end at the end", text.removeSuffix("\n"), "last line")
        refused("a word twice", joined(lines.toMutableList().apply { this[1] = "1112\tacid" }), "line 2: not in ascending order")
        refused("two lines swapped", joined(lines.toMutableList().apply { this[0] = lines[1]; this[1] = lines[0] }), "line 2: not in ascending order")
        refused("a capital letter", joined(lines.toMutableList().apply { this[0] = "1111\tAcid" }), "line 1:")
        refused("a die above six", joined(lines.toMutableList().apply { this[0] = "1117\tacid" }), "line 1:")
        refused("a long word", joined(lines.toMutableList().apply { this[0] = "1111\tacidic" }), "line 1:")
        refused("nothing", "", "0 words")
    }

    private companion object {
        /** EFF's short word list 1 as downloaded on 2026-10-07 (wordlists/SOURCES.md). */
        const val EFF_SHA256 = "8f5ca830b8bffb6fe39c9736c024a00a6a6411adb3f83a9be8bfeeb6e067ae69"

        /** The same less the line of "yo-yo": the resource. */
        const val OURS_SHA256 = "992a27c7ed099b3c7cc444b5137a613ea80665f0ab6dfd78a3c445f33dc3fa79"
    }
}
