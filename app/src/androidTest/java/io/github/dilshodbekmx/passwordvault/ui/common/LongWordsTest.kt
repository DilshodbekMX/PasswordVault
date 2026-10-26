package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.BreakIterator
import android.icu.util.ULocale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.theme.typographyFor
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 8.1 (the open problems of section 12, R6: long Uzbek words, "PasswordVault-backup"),
 * [device], on any phone: every text of the app in its language, laid out as the app lays it out
 * (the theme's typography for that language, `bodyLarge`) in the app's full-width column, 320 dp
 * less 16 dp each side, with the font at 200 % (the layout walk, `tools/emu/layout/walk.sh`, looks at
 * the narrower places of each screen, but passes over words of more than 16 letters):
 * - no word (the stretch between two places where a line may break, by ICU's line-break rules for
 *   the language) is wider than the column: Android cannot hyphenate Uzbek, so such a word would be
 *   cut between two letters (i18n.md §4.5 rule 4);
 * - every line ends where a line may break (a space, after a hyphen or a soft hyphen, …), never
 *   between two letters of a word that fits on a line: with hyphenation off Android does not break
 *   after a hyphen ("PasswordVault-backu / p");
 * - "PasswordVault-backup", the start of every backup's name (`backup_restore_intro`), is cut, if at
 *   all, right after its hyphen, in the four languages.
 * English, Uzbek Latin and Uzbek Cyrillic. Russian text is hyphenated by Android's patterns where it
 * needs to be (i18n.md §4.5 rule 4), so its lines may end inside a word by design: only the third check
 * covers it. Names that are not words (a web address, a library's name) may be cut where Android
 * chooses: a text with a digit, a dot, a slash, a colon or an @ inside is passed over.
 */
@RunWith(AndroidJUnit4::class)
class LongWordsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val metrics = context.resources.displayMetrics
    private val density = Density(metrics.density, fontScale = FONT_SCALE)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
    private val column = (COLUMN_DP * metrics.density).toInt()

    /** The app's texts in [tag]: every string, and every plural's items for 1, 2 and 5 (one, few, many, other). */
    private fun texts(tag: String): List<Pair<String, String>> {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val res: Resources = context.createConfigurationContext(configuration).resources
        val strings = R.string::class.java.fields.filter { field -> AREAS.any { field.name.startsWith(it) } }
            .map { it.name to res.getString(it.getInt(null)) }
        val plurals = R.plurals::class.java.fields.filter { field -> AREAS.any { field.name.startsWith(it) } }
            .flatMap { field -> listOf(1, 2, 5).map { n -> "${field.name}[$n]" to res.getQuantityText(field.getInt(null), n).toString() } }
        return (strings + plurals).distinctBy { it.second }
    }

    private fun style(tag: String): TextStyle = typographyFor(Locale.forLanguageTag(tag).language).bodyLarge.copy(localeList = LocaleList(tag))

    private fun layout(text: String, style: TextStyle): TextLayoutResult =
        measurer.measure(text, style, constraints = Constraints(maxWidth = column), density = density)

    /** The stretches of [text] between the places where ICU lets a line break, with their start offsets. */
    private fun words(text: String, tag: String): List<Pair<Int, String>> {
        val breaks = BreakIterator.getLineInstance(ULocale.forLanguageTag(tag))
        breaks.setText(text)
        val out = mutableListOf<Pair<Int, String>>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            out += start to text.substring(start, end).trimEnd()
            start = end
            end = breaks.next()
        }
        return out
    }

    /**
     * Whether [word] is a name rather than a word: a web address, a file or a library's name, or a
     * placeholder; the punctuation around a word ("фаолсизлантирилди." at a sentence's end) does not
     * make it one.
     */
    private fun isName(word: String): Boolean {
        val core = word.trimStart { it in OPENING }.trimEnd { it in CLOSING }
        return core.any { it.isDigit() || it in NAME_SIGNS } || core.contains("%")
    }

    @Test
    fun noWordIsTooWideForTheColumn_andNoLineEndsInsideAWord() {
        val problems = mutableListOf<String>()
        for (tag in listOf("en", "uz", "uz-Cyrl")) {
            val style = style(tag)
            for ((name, text) in texts(tag)) {
                val words = words(text, tag)
                val tooWide = mutableSetOf<IntRange>()
                for ((start, word) in words) {
                    if (word.isEmpty() || isName(word)) continue
                    val width = measurer.measure(word, style, maxLines = 1, softWrap = false, density = density).size.width
                    if (width > column) {
                        val waiting = word.trimStart { it in OPENING }.trimEnd { it in CLOSING }.lowercase() in WAITING_FOR_THE_OWNER
                        if (!waiting) problems += "$tag $name: \"$word\" is ${width * 100 / column} % of the column"
                        tooWide += start until start + word.length
                    }
                }
                val laidOut = layout(text, style)
                val boundaries = words.map { it.first }.toSet()
                for (line in 0 until laidOut.lineCount - 1) {
                    val end = laidOut.getLineEnd(line)
                    if (end <= 0 || end >= text.length || end in boundaries || text[end - 1].isWhitespace()) continue
                    val word = words.last { it.first <= end }
                    if (isName(word.second) || tooWide.any { end in it }) continue
                    problems += "$tag $name: a line ends inside \"${word.second}\" (\"${text.substring(maxOf(0, end - 12), end)}\" / \"${text.substring(end, minOf(text.length, end + 12))}\")"
                }
            }
        }
        assertTrue("${problems.size} problems:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theBackupNamePartsOnlyAtItsHyphen_inTheFourLanguages() {
        for (tag in listOf("en", "uz", "uz-Cyrl", "ru")) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val text = context.createConfigurationContext(configuration).getString(R.string.backup_restore_intro)
            val start = text.indexOf(BACKUP_NAME)
            assertTrue("$tag: the text names $BACKUP_NAME: $text", start >= 0)
            val laidOut = layout(text, style(tag))
            val inside = (0 until laidOut.lineCount - 1).map { laidOut.getLineEnd(it) }.filter { it > start && it < start + BACKUP_NAME.length }
            val allowed = start + BACKUP_NAME.indexOf('-') + 1
            assertTrue("$tag: \"$BACKUP_NAME\" is cut at ${inside.map { it - start }} (allowed: after its hyphen, ${allowed - start})", inside.all { it == allowed })
        }
        assertEquals(14, BACKUP_NAME.indexOf('-') + 1)
    }

    private companion object {
        const val FONT_SCALE = 2f

        /** 320 dp less 16 dp each side: the text of a screen that spans its width (the list, an account, Help). */
        const val COLUMN_DP = 288

        /**
         * Words too wide for the column that wait for an answer of the owner, none now: the last were
         * glossary 135's "faolsizlantirildi" forms, which answer B of build plan §12's R6 question 2
         * replaced with "faolsiz qilindi".
         */
        val WAITING_FOR_THE_OWNER = emptySet<String>()

        const val BACKUP_NAME = "PasswordVault-backup"

        /** Signs inside a name that is not a word. */
        val NAME_SIGNS = setOf('.', '/', ':', '@', '\\', '_')

        /** Punctuation before and after a word in a sentence. */
        const val OPENING = "«“„\"'(["
        const val CLOSING = ".,;:!?…»”“\"')]"

        /** The prefixes of the app's string names, one per screen area (build plan §1 rule 5). */
        val AREAS = listOf(
            "common_", "setup_", "lock_", "pin_", "master_", "clipboard_", "shell_", "data_", "list_", "edit_", "entry_",
            "settings_", "backup_", "export_", "import_", "security_", "change_", "help_", "language_",
        )
    }
}
