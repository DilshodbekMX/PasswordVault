package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.FieldsBlock
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The block of extra fields in our CSV's note column, in both directions: the grammar of
 * architecture.md D-10a with the eight vectors of formats.md §4.4 (the first four are the cases of
 * formats-review P8, 5 to 7 the reserved labels and an escaped backslash, 8 a user's note that would
 * read as a block).
 */
class FieldsBlockTest {
    private fun m(count: Int) = FieldsBlock.marker(count)

    /** Writes [e]'s note column, checks it against [expected], and reads it back. */
    private fun written(e: Entry, expected: String): FieldsBlock.Block {
        val note = FieldsBlock.note(e)
        assertEquals(expected, note)
        return FieldsBlock.read(note)
    }

    @Test
    fun theMarkerIsExactlyTheOneOfTheGrammar() {
        assertEquals("--- PasswordVault (1 lines) ---", m(1))
        assertEquals("--- PasswordVault (12 lines) ---", m(12))
        assertEquals("Title", FieldsBlock.TITLE_LABEL)
        assertEquals("Old password", FieldsBlock.OLD_PASSWORD_LABEL)
    }

    // ------------------------------------------------------------------ the vectors of formats.md §4.4

    /** P8, case 1: a label with ": " in it, and notes that look like a field line. */
    @Test
    fun vector1_aColonInALabelIsEscapedAndTheNotesStayNotes() {
        val e = entry(1, "Bank", urls = listOf("https://bank.uz"), notes = "Branch: Chilonzor",
            fields = listOf(visible("Q: mother's name", "Aziza")))
        val back = written(e, "Branch: Chilonzor\n\n--- PasswordVault (2 lines) ---\nTitle: Bank\nQ\\: mother's name: Aziza")
        assertTrue(back.found)
        assertEquals("Branch: Chilonzor", back.notes)
        assertEquals("Bank", back.title)
        // Not the field "Q" = "mother's name: Aziza", which the prototype read.
        assertEquals(listOf(visible("Q: mother's name", "Aziza")), back.fields)
        assertNull(back.oldPassword)
    }

    /** P8, case 2: a visible field whose name ends in " (hidden)" beside a hidden one. */
    @Test
    fun vector2_aNameThatEndsInHiddenIsNotTheFlag() {
        val e = entry(2, "Bank", urls = listOf("https://bank.uz"),
            fields = listOf(visible("Code (hidden)", "77"), hidden("PIN", "1234")))
        val back = written(e, "\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nCode \\(hidden): 77\nPIN (hidden): 1234")
        assertEquals("", back.notes)
        assertEquals(listOf(visible("Code (hidden)", "77"), hidden("PIN", "1234")), back.fields)
    }

    /** P8, case 3: a field typed with leading spaces is trimmed at input, so it is never a continuation line. */
    @Test
    fun vector3_aNameTypedWithLeadingSpacesIsNeverGluedOntoThePreviousValue() {
        val typed = DraftEntry(title = "Bank", urls = listOf("https://bank.uz"), fields = listOf(visible("A", "1"), visible("  B", "x")))
        val stored = EntryRules.prepare(typed)
        assertEquals(listOf(visible("A", "1"), visible("B", "x")), stored.fields)
        val e = entry(3, stored.title, urls = stored.urls, fields = stored.fields)
        val back = written(e, "\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nA: 1\nB: x")
        assertEquals(listOf(visible("A", "1"), visible("B", "x")), back.fields)

        // A name that reached the vault untrimmed (never through Add or Edit) is written trimmed too.
        val untrimmed = entry(3, "Bank", urls = listOf("https://bank.uz"), fields = listOf(visible("A", "1"), visible("  B ", "x")))
        assertEquals("\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nA: 1\nB: x", FieldsBlock.note(untrimmed))
    }

    /** P8, case 4: a block pasted into a note stays a note. */
    @Test
    fun vector4_aPastedBlockWithoutACountOrWithAWrongOneStaysANote() {
        val pasted = "Old notes:\n\n--- PasswordVault ---\nPIN: 9999"
        val e = entry(4, "mail.ru", urls = listOf("https://mail.ru"), notes = pasted)
        // No block is written: it would have no line, because the title equals the host.
        val back = written(e, pasted)
        assertFalse(back.found)
        assertEquals(pasted, back.notes)
        assertTrue(back.fields.isEmpty())
        assertNull(back.title)

        // The same for a marker whose count does not reach the end of the note.
        val short = "Old notes:\n\n" + m(1) + "\nPIN: 9999\nmore text"
        val back2 = written(e.copy(notes = short), short)
        assertFalse(back2.found)
        assertEquals(short, back2.notes)
        assertTrue(back2.fields.isEmpty())
    }

    /** The reserved labels: a user's own fields named "Title" and "Old password". */
    @Test
    fun vector5_theUsersOwnFieldsWithTheReservedNamesReadBackAsFields() {
        val e = entry(5, "Telegram", urls = listOf("https://web.telegram.org"),
            fields = listOf(visible("Title", "Mr"), visible("Old password", "x")))
        val back = written(e, "\n\n--- PasswordVault (3 lines) ---\nTitle: Telegram\n\\Title: Mr\n\\Old password: x")
        assertEquals("Telegram", back.title)
        assertEquals(listOf(visible("Title", "Mr"), visible("Old password", "x")), back.fields)
        assertNull(back.oldPassword)
    }

    /** A pending change: the old password travels in the reserved hidden line (D-10d). */
    @Test
    fun vector6_aPendingChangeKeepsTheOldPassword() {
        val e = entry(6, "Gmail", password = "N3w!pass", urls = listOf("https://mail.google.com"),
            fields = listOf(hidden("PIN", "1234")), changePending = true, pendingOldPassword = "Old#2")
        val back = written(e, "\n\n--- PasswordVault (3 lines) ---\nTitle: Gmail\nPIN (hidden): 1234\nOld password (hidden): Old#2")
        assertEquals("Gmail", back.title)
        assertEquals(listOf(hidden("PIN", "1234")), back.fields)
        assertEquals("Old#2", back.oldPassword)
    }

    /** An escaped backslash and a value of two lines. */
    @Test
    fun vector7_aBackslashInALabelAndAValueOfTwoLines() {
        val e = entry(7, "Server", urls = listOf("https://srv.example"), fields = listOf(visible("C:\\keys", "line 1\nline 2")))
        val back = written(e, "\n\n--- PasswordVault (3 lines) ---\nTitle: Server\nC\\:\\\\keys: line 1\n  line 2")
        assertEquals(listOf(visible("C:\\keys", "line 1\nline 2")), back.fields)
    }

    /** The user's text never becomes fields: notes that would read as a block get one of ours behind them. */
    @Test
    fun vector8_notesThatWouldReadAsABlockGetABlockOfTheWritersOwn() {
        val notes = "Old notes:\n\n" + m(1) + "\nPIN: 9999"
        // Read alone, these notes are notes and a block: that is what must not happen to them.
        assertTrue(FieldsBlock.read(notes).found)
        val e = entry(8, "mail.ru", urls = listOf("https://mail.ru"), notes = notes)
        val back = written(e, notes + "\n\n" + m(1) + "\nTitle: mail.ru")
        assertTrue(back.found)
        assertEquals("mail.ru", back.title)
        assertEquals(notes, back.notes)
        assertTrue(back.fields.isEmpty())

        // Also for an entry without a website, which otherwise gets no Title line.
        val noWebsite = entry(8, "Notes only", notes = notes)
        val back2 = written(noWebsite, notes + "\n\n" + m(1) + "\nTitle: Notes only")
        assertEquals(notes, back2.notes)
        assertEquals("Notes only", back2.title)
        assertTrue(back2.fields.isEmpty())
    }

    // ------------------------------------------------------------------ the order and choice of lines

    @Test
    fun theTitleLineIsWrittenOnlyWhenTheTitleDiffersFromTheHostOfTheFirstWebsite() {
        fun note(title: String, vararg urls: String) = FieldsBlock.note(entry(1, title, urls = urls.toList()))
        assertEquals("", note("mail.ru", "https://mail.ru"))
        assertEquals("", note("mail.ru", "https://www.mail.ru/login?x=1")) // WebAddress.hostOf drops www. and the path
        assertEquals("", note("mail.google.com", "mail.google.com")) // https:// is added for the row; the host is the same
        assertEquals("\n\n" + m(1) + "\nTitle: Mail.ru", note("Mail.ru", "https://mail.ru")) // compared exactly
        assertEquals("\n\n" + m(1) + "\nTitle: Gmail", note("Gmail", "https://mail.google.com", "https://gmail.com"))
        assertEquals("", note("gmail.com", "  ", "https://gmail.com")) // the first website that is not blank
        // An app link: the host is the package.
        assertEquals("", note("com.example.app", "android://AbC123==@com.example.app/"))
        // Without a website there is no host to differ from: no Title line.
        assertEquals("", note("My bank card"))
        assertEquals("only notes", FieldsBlock.note(entry(1, "My bank card", notes = "only notes")))
    }

    @Test
    fun theLinesComeInTheOrderTitleFieldsOldPassword() {
        val e = entry(1, "Gmail", urls = listOf("https://mail.google.com"), notes = "n",
            fields = listOf(visible("b", "2"), hidden("a", "1")), changePending = true, pendingOldPassword = "old")
        assertEquals("n\n\n" + m(4) + "\nTitle: Gmail\nb: 2\na (hidden): 1\nOld password (hidden): old", FieldsBlock.note(e))
    }

    @Test
    fun theOldPasswordLineIsWrittenOnlyWhileAChangeIsPending() {
        val base = entry(1, "mail.ru", urls = listOf("https://mail.ru"))
        // Pending without a kept password, and a kept password without a pending change: no line.
        assertEquals("", FieldsBlock.note(base.copy(changePending = true, pendingOldPassword = null)))
        assertEquals("", FieldsBlock.note(base.copy(changePending = false, pendingOldPassword = "stale")))
        // The website had no password before: the empty old password is kept as such.
        val empty = FieldsBlock.note(base.copy(changePending = true, pendingOldPassword = ""))
        assertEquals("\n\n" + m(1) + "\nOld password (hidden): ", empty)
        assertEquals("", FieldsBlock.read(empty).oldPassword)
    }

    @Test
    fun theTwoLineFeedsAreWrittenBeforeTheMarkerAlsoWhenTheNotesAreEmptyOrEndInALineFeed() {
        val field = listOf(visible("X", "y"))
        for (notes in listOf("", "\n", "abc", "abc\n", "abc\n\n", "\n\nabc")) {
            val note = FieldsBlock.note(notes, "t", "t", field, null)
            assertEquals(notes + "\n\n" + m(1) + "\nX: y", note)
            val back = FieldsBlock.read(note)
            assertTrue(back.found)
            assertEquals(notes, back.notes)
            assertEquals(field, back.fields)
        }
    }

    // ------------------------------------------------------------------ labels

    @Test
    fun labelsAreEscapedAndReadBack() {
        val names = listOf(
            "plain", "a:b", "a: b", "a :b", ":", ": ", "x:", "(", "()", "a (b)", "(hidden)", "x (hidden)", "x  (hidden)",
            "\\", "\\\\", "a\\b", "a\\: b", "C:\\Users\\(me)", "Title ", "title", "Titles", "Old Password", "Old password!",
            "Title (hidden)", "Old password (hidden)", "o‘zbek: gʻalaba", "Пароль (скрыто)", "🔑: (x)", "--- PasswordVault (1 lines) ---",
        )
        for (name in names) {
            for (secret in listOf(false, true)) {
                val stored = name.trim() // names are trimmed when they are entered or imported
                val field = CustomField(stored, "v", secret)
                val note = FieldsBlock.note("", "t", "t", listOf(field), null)
                val back = FieldsBlock.read(note)
                assertTrue(name, back.found)
                assertEquals(name, listOf(field), back.fields)
                assertNull(name, back.title)
                assertNull(name, back.oldPassword)
            }
        }
    }

    @Test
    fun aLabelIsWrittenWithExactlyTheThreeEscapesAndTheReservedNamesWithOneMore() {
        fun line(name: String, secret: Boolean = false) =
            FieldsBlock.note("", "t", "t", listOf(CustomField(name, "v", secret)), null).substringAfter(m(1) + "\n")
        assertEquals("a\\\\b\\:c\\(d): v", line("a\\b:c(d)"))
        assertEquals("a)b[c]{d}\"e'f,g;h=i: v", line("a)b[c]{d}\"e'f,g;h=i")) // nothing else is escaped
        assertEquals("\\Title: v", line("Title"))
        assertEquals("\\Title (hidden): v", line("Title", secret = true))
        assertEquals("\\Old password: v", line("Old password"))
        assertEquals("title: v", line("title")) // only the two exact words are reserved
        assertEquals("Old Password: v", line("Old Password"))
        assertEquals("Title x: v", line("Title x"))
        // A line break in a name (never typed: names are one line) becomes a space; the name is trimmed.
        assertEquals("two words: v", line(" two\nwords \n"))
        assertEquals("a b: v", line("a\r\nb"))
    }

    @Test
    fun anEmptyNameAndAnEmptyValueAreLinesToo() {
        val fields = listOf(visible("", ""), hidden("", ""), visible("", "x"), hidden("PIN", ""))
        val note = FieldsBlock.note("n", "t", "t", fields, null)
        assertEquals("n\n\n" + m(4) + "\n: \n (hidden): \n: x\nPIN (hidden): ", note)
        assertEquals(fields, FieldsBlock.read(note).fields)
    }

    // ------------------------------------------------------------------ values

    @Test
    fun laterLinesOfAValueAreIndentedByTwoSpacesAndComeBackWithoutThem() {
        val values = listOf(
            "one line", "line 1\nline 2", "ends with a line feed\n", "\nstarts with one", "a\n\nb", "\n", "\n\n\n",
            "  two leading spaces", "a\n  indented\n    more", "a\n--- PasswordVault (1 lines) ---\nB: c",
            "a\n\n--- PasswordVault (1 lines) ---\nB: c", "x: y\nTitle: z\nOld password (hidden): w", "tab\there", " ",
        )
        for (value in values) {
            val fields = listOf(visible("V", value), hidden("W", value))
            val note = FieldsBlock.note("user notes", "Name", "host", fields, value)
            val back = FieldsBlock.read(note)
            assertTrue(value, back.found)
            assertEquals(value, "user notes", back.notes)
            assertEquals(value, "Name", back.title)
            assertEquals(value, fields, back.fields)
            assertEquals(value, value, back.oldPassword)
            // No line of the block is empty, and the count is the number of lines after the marker.
            val block = note.substringAfter("user notes\n\n")
            val lines = block.split('\n')
            assertEquals(value, m(lines.size - 1), lines[0])
            assertTrue(value, lines.none { it.isEmpty() })
        }
    }

    @Test
    fun aValueOfTwoLinesIsWrittenAsInTheLayoutOfFormatsMd() {
        val note = FieldsBlock.note("Recovery phone below", "Gmail", "mail.google.com",
            listOf(hidden("PIN code", "1234"), visible("Address", "Toshkent, Chilonzor 5\nsecond line of the address")), null)
        assertEquals(
            "Recovery phone below\n\n--- PasswordVault (4 lines) ---\nTitle: Gmail\nPIN code (hidden): 1234\n" +
                "Address: Toshkent, Chilonzor 5\n  second line of the address",
            note,
        )
    }

    @Test
    fun carriageReturnsCountAsLineFeeds() {
        // In a value they are written as line feeds, so that the count and the indent hold.
        val note = FieldsBlock.note("", "t", "t", listOf(visible("A", "1\r\n2\r3")), null)
        assertEquals("\n\n" + m(3) + "\nA: 1\n  2\n  3", note)
        // A file that came back with CRLF inside its notes is read all the same; the notes come back with LF.
        val crlf = "first\r\nsecond\r\n\r\n" + m(2) + "\r\nA: 1\r\n  2"
        val back = FieldsBlock.read(crlf)
        assertTrue(back.found)
        assertEquals("first\nsecond", back.notes)
        assertEquals(listOf(visible("A", "1\n2")), back.fields)
        // Without a block the note comes back whole, with line feeds.
        assertEquals("a\nb\nc", FieldsBlock.read("a\r\nb\rc").notes)
    }

    // ------------------------------------------------------------------ recognised only when it is ours

    @Test
    fun theMarkerMustFollowABlankLine() {
        val block = m(1) + "\nPIN: 9999"
        for (note in listOf(block, "\n" + block, "text\n" + block, " \n" + block, "text\n \n" + block, "x\n\n " + block)) {
            val back = FieldsBlock.read(note)
            assertFalse(note, back.found)
            assertEquals(note, back.notes)
        }
        for (notes in listOf("", "text", "text\n", "\n")) {
            val back = FieldsBlock.read(notes + "\n\n" + block)
            assertTrue(back.found)
            assertEquals(notes, back.notes)
            assertEquals(listOf(visible("PIN", "9999")), back.fields)
        }
    }

    @Test
    fun theCountMustBeExactlyTheNumberOfLinesToTheEndOfTheNote() {
        fun found(note: String) = FieldsBlock.read(note).found
        assertTrue(found("n\n\n" + m(1) + "\nA: 1"))
        assertFalse(found("n\n\n" + m(2) + "\nA: 1"))
        assertFalse(found("n\n\n" + m(1) + "\nA: 1\nB: 2"))
        assertFalse(found("n\n\n" + m(1) + "\nA: 1\n")) // a line feed at the end is one more line
        assertFalse(found("n\n\n" + m(1))) // a marker with no line after it
        assertFalse(found("n\n\n" + m(1) + "\n"))
        assertTrue(found("n\n\n" + m(2) + "\nA: 1\n  ")) // the value "1" and an empty second line
        // The count's spelling: ASCII digits, from 1 on, without a leading zero or a sign.
        for (count in listOf("0", "01", "+1", "-1", " 1", "1 ", "١", "1.0", "one", "", "1000000000")) {
            assertFalse(count, found("n\n\n--- PasswordVault ($count lines) ---\nA: 1"))
        }
        // The marker's own text is exact.
        for (marker in listOf("--- PasswordVault (1 line) ---", "--- passwordvault (1 lines) ---", "---PasswordVault (1 lines)---",
            "--- PasswordVault (1 lines) --- ", " --- PasswordVault (1 lines) ---", "--- Password Vault (1 lines) ---")) {
            assertFalse(marker, found("n\n\n$marker\nA: 1"))
        }
    }

    @Test
    fun theLastMarkerThatQualifiesIsTaken() {
        // Two markers whose counts both reach the end: the user wrote the first, the writer the second.
        val notes = "X\n\n" + m(4) + "\nPIN: 9999"
        val note = notes + "\n\n" + m(1) + "\nTitle: t"
        assertFalse(FieldsBlock.read(notes).found) // alone, the user's count of 4 is wrong
        val back = FieldsBlock.read(note)
        assertTrue(back.found)
        assertEquals(notes, back.notes)
        assertEquals("t", back.title)
        assertTrue(back.fields.isEmpty())
        // An earlier marker with a wrong count does not hide a later one that is right.
        val back2 = FieldsBlock.read("a\n\n" + m(7) + "\nb\n\n" + m(1) + "\nK: v")
        assertTrue(back2.found)
        assertEquals("a\n\n" + m(7) + "\nb", back2.notes)
        assertEquals(listOf(visible("K", "v")), back2.fields)
    }

    @Test
    fun aLineThatIsNeitherALineNorAContinuationMakesTheWholeNoteText() {
        val broken = listOf(
            "n\n\n" + m(1) + "\nno separator here",
            "n\n\n" + m(1) + "\nA:1", // a colon without the space
            "n\n\n" + m(1) + "\n  a continuation with no line before it",
            "n\n\n" + m(2) + "\nA: 1\nnot a line",
            "n\n\n" + m(2) + "\nA: 1\n", // the empty last line is no line of the grammar
            "n\n\n" + m(1) + "\nlabel\\: only an escaped separator",
            "n\n\n" + m(1) + "\nends with a backslash\\",
            "n\n\n" + m(3) + "\nA: 1\n\nB: 2", // an empty line inside the block
        )
        for (note in broken) {
            val back = FieldsBlock.read(note)
            assertFalse(note, back.found)
            assertEquals(note, note, back.notes) // nothing of the note is lost to the block
            assertTrue(note, back.fields.isEmpty())
            assertNull(note, back.title)
            assertNull(note, back.oldPassword)
        }
    }

    @Test
    fun aBackslashMakesTheNextCharacterLiteralWhateverItIs() {
        val back = FieldsBlock.read("\n\n" + m(3) + "\n\\a\\b\\ \\): 1\nx\\: y: 2\n\\\\\\\\: 3")
        assertEquals(listOf(visible("ab )", "1"), visible("x: y", "2"), visible("\\\\", "3")), back.fields)
    }

    @Test
    fun theHiddenFlagIsOnlyAnUnescapedSuffixOfTheLabel() {
        fun one(line: String) = FieldsBlock.read("\n\n" + m(1) + "\n" + line).fields.single()
        assertEquals(hidden("PIN", "1"), one("PIN (hidden): 1"))
        assertEquals(visible("PIN (hidden)", "1"), one("PIN \\(hidden): 1"))
        assertEquals(visible("PIN (hidden)", "1"), one("PIN\\ (hidden): 1"))
        assertEquals(visible("PIN (hidden)", "1"), one("PIN (hidden\\): 1"))
        assertEquals(hidden("PIN (hidden)", "1"), one("PIN \\(hidden) (hidden): 1"))
        assertEquals(hidden("PIN (hidden)", "1"), one("PIN (hidden) (hidden): 1")) // a hand-made file
        assertEquals(visible("PIN (Hidden)", "1"), one("PIN (Hidden): 1")) // the flag is exact
        assertEquals(visible("PIN(hidden)", "1"), one("PIN(hidden): 1"))
        assertEquals(visible("PIN (hidden) x", "1"), one("PIN (hidden) x: 1"))
        assertEquals(hidden("", "1"), one(" (hidden): 1"))
        assertEquals(visible("(hidden)", "1"), one("(hidden): 1"))
    }

    @Test
    fun theReservedLabelsAreReservedOnlyWithoutABackslash() {
        fun read(vararg lines: String) = FieldsBlock.read("n\n\n" + m(lines.size) + "\n" + lines.joinToString("\n"))
        val reserved = read("Title: T", "Old password (hidden): P")
        assertEquals("T", reserved.title)
        assertEquals("P", reserved.oldPassword)
        assertTrue(reserved.fields.isEmpty())

        val escaped = read("\\Title: T", "Tit\\le: U", "\\Old password (hidden): P", "Old\\ password: Q")
        assertNull(escaped.title)
        assertNull(escaped.oldPassword)
        assertEquals(listOf(visible("Title", "T"), visible("Title", "U"), hidden("Old password", "P"), visible("Old password", "Q")), escaped.fields)

        // The flag does not decide whether a label is reserved: a hand-made file may leave it out or add it.
        val flags = read("Title (hidden): T", "Old password: P")
        assertEquals("T", flags.title)
        assertEquals("P", flags.oldPassword)

        // A second line with a reserved label is kept as a field, so nothing is dropped.
        val twice = read("Title: first", "Title: second", "Old password (hidden): one", "Old password (hidden): two")
        assertEquals("first", twice.title)
        assertEquals("one", twice.oldPassword)
        assertEquals(listOf(visible("Title", "second"), hidden("Old password", "two")), twice.fields)

        // They may stand anywhere in the block, and their text may have several lines.
        val anywhere = read("A: 1", "Old password (hidden): two", "  lines", "Title: Name", "B: 2")
        assertEquals("Name", anywhere.title)
        assertEquals("two\nlines", anywhere.oldPassword)
        assertEquals(listOf(visible("A", "1"), visible("B", "2")), anywhere.fields)
    }

    @Test
    fun anEmptyTitleLineIsATitleLine() {
        val back = FieldsBlock.read("\n\n" + m(1) + "\nTitle: ")
        assertTrue(back.found)
        assertEquals("", back.title)
    }

    // ------------------------------------------------------------------ the cases of the review's attack (§16)

    @Test
    fun theFieldsOfTheReviewsAttackComeBackAsTheyWere() {
        val cases = listOf(
            entry(1, "colon name", "u1", "p", listOf("https://f1.example"), "", listOf(visible("Q: mother's name", "Aziza"))),
            entry(2, "hidden suffix", "u2", "p", listOf("https://f2.example"), "", listOf(visible("Code (hidden)", "1234"))),
            entry(3, "trailing LF value", "u4", "p", listOf("https://f4.example"), "", listOf(visible("Addr", "line\n"))),
            entry(4, "marker in note", "u5", "p", listOf("https://f5.example"), "copied:\n\n--- PasswordVault ---\nPIN: 9999"),
            entry(5, "empty name and value", "u6", "p", listOf("https://f6.example"), "n", listOf(hidden("", ""))),
            entry(6, "note ends with LF", "u7", "p", listOf("https://f7.example"), "abc\n", listOf(hidden("X", "y"))),
            entry(7, "value with marker", "u8", "p", listOf("https://f8.example"), "", listOf(visible("V", "a\n--- PasswordVault ---\nB: c"))),
            entry(8, "new marker in note", "u9", "p", listOf("https://f9.example"), "copied:\n\n" + m(1) + "\nPIN: 9999"),
            entry(9, "new marker in value", "u10", "p", listOf("https://f10.example"), "", listOf(visible("V", "a\n\n" + m(1) + "\nB: c"))),
        )
        for (e in cases) {
            val back = FieldsBlock.read(FieldsBlock.note(e))
            assertEquals(e.title, e.notes, back.notes)
            assertEquals(e.title, e.fields, back.fields)
            assertEquals(e.title, e.title, back.title) // each title differs from its host
        }
    }

    // ------------------------------------------------------------------ every note comes back

    private val alphabet = listOf(
        "a", "B", "1", " ", "  ", ":", ": ", "\\", "(", ")", " (hidden)", "(hidden)", "\n", "\n\n", "\n  ", "-", "---",
        "--- PasswordVault (", " lines) ---", "Title", "Old password", "Title: ", "Old password (hidden): ", "oʻ", "Ж", "🔑", "\t",
    )

    private fun text(random: Random, maxPieces: Int, lineBreaks: Boolean): String {
        val sb = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            val piece = alphabet[random.nextInt(alphabet.size)]
            if (lineBreaks || !piece.contains('\n')) sb.append(piece)
        }
        return sb.toString()
    }

    /** Notes built to look like blocks: pieces of text, markers with right and wrong counts, field lines. */
    private fun trickyNotes(random: Random): String {
        val lines = ArrayList<String>()
        if (random.nextInt(3) == 0) {
            // Notes that end in a well-formed block of their own, as if the user had pasted one.
            repeat(random.nextInt(3)) { lines.add(text(random, 4, lineBreaks = false)) }
            if (lines.isEmpty()) lines.add("")
            lines.add("")
            val count = 1 + random.nextInt(3)
            lines.add(m(count))
            lines.add(if (random.nextBoolean()) "PIN: 9999" else "Title: x")
            repeat(count - 1) { lines.add(if (random.nextBoolean()) "  continued" else "K" + random.nextInt(3) + " (hidden): v") }
            return lines.joinToString("\n")
        }
        repeat(random.nextInt(8)) {
            when (random.nextInt(6)) {
                0 -> lines.add("")
                1 -> lines.add(m(1 + random.nextInt(4)))
                2 -> lines.add("K" + random.nextInt(3) + ": v")
                3 -> lines.add("  continued")
                4 -> lines.add(text(random, 4, lineBreaks = false))
                else -> lines.add("Title: x")
            }
        }
        // Often: make the last marker's count right, whatever the lines after it are.
        val at = lines.indexOfLast { it.startsWith("--- PasswordVault (") }
        if (at >= 0 && at < lines.size - 1 && random.nextBoolean()) lines[at] = m(lines.size - 1 - at)
        return lines.joinToString("\n")
    }

    @Test
    fun whateverIsWrittenIsReadBackExactly() {
        val random = Random(20261007)
        var blocks = 0
        var forced = 0
        repeat(30_000) { round ->
            val notes = if (round % 3 == 0) trickyNotes(random) else text(random, 8, lineBreaks = true)
            val title = text(random, 3, lineBreaks = false).trim()
            val host = when (random.nextInt(3)) {
                0 -> null
                1 -> title
                else -> "host.example"
            }
            val fields = List(random.nextInt(4)) {
                // A stored name is trimmed and has one line; a value is anything.
                CustomField(text(random, 4, lineBreaks = false).trim(), text(random, 5, lineBreaks = true), random.nextBoolean())
            }
            val oldPassword = if (random.nextInt(4) == 0) text(random, 4, lineBreaks = false) else null
            val note = FieldsBlock.note(notes, title, host, fields, oldPassword)
            val back = FieldsBlock.read(note)
            val context = "round $round"
            assertEquals(context, notes, back.notes)
            assertEquals(context, fields, back.fields)
            assertEquals(context, oldPassword, back.oldPassword)
            val nothingToCarry = (host == null || title == host) && fields.isEmpty() && oldPassword == null
            if (nothingToCarry && !FieldsBlock.read(notes).found) {
                // No block was written, and the notes are the whole note.
                assertEquals(context, notes, note)
                assertFalse(context, back.found)
                assertNull(context, back.title)
            } else {
                blocks++
                assertTrue(context, back.found)
                val titleWritten = (host != null && title != host) || nothingToCarry
                if (nothingToCarry) forced++
                assertEquals(context, if (titleWritten) title else null, back.title)
            }
        }
        // The generator reaches both kinds of note often enough to mean something.
        assertTrue("blocks $blocks", blocks > 10_000)
        assertTrue("forced $forced", forced > 100)
    }

    @Test
    fun readingNeverThrowsAndNeverLosesTextWhateverTheNote() {
        val random = Random(7)
        repeat(30_000) { round ->
            val note = if (round % 2 == 0) trickyNotes(random) else text(random, 12, lineBreaks = true)
            val back = FieldsBlock.read(note)
            if (!back.found) {
                assertEquals(note, back.notes)
                assertTrue(back.fields.isEmpty())
            } else {
                // What was read, written again, is the same note: nothing was dropped or invented.
                assertTrue(note.startsWith(back.notes + "\n\n--- PasswordVault ("))
            }
        }
    }

    @Test
    fun aNoteWithThousandsOfMarkersIsReadInLinearTime() {
        // 50,000 marker lines, each after a blank line, none with the right count: about 1.8 MB.
        val sb = StringBuilder()
        repeat(50_000) { sb.append("text\n\n").append(m(3)).append('\n') }
        sb.append("\n").append(m(2)).append("\nA: 1\nB: 2")
        val started = System.nanoTime()
        val back = FieldsBlock.read(sb.toString())
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(back.found)
        assertEquals(listOf(visible("A", "1"), visible("B", "2")), back.fields)
        assertTrue("took $ms ms", ms < 5_000)
    }
}
