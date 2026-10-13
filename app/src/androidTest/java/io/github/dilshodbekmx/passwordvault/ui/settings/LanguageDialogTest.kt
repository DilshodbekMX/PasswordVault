package io.github.dilshodbekmx.passwordvault.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.os.LocaleList as AndroidLocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 6.1, [device]: the language dialog (i18n.md §1.4.5; ux.md §5.1, §5.18) as its route
 * draws it ([LanguageSwitch]), over a recording navigator, on API 24, 28 and 37.
 *
 * - The choices in their fixed order, each language named in its own language and marked as text of
 *   that language; the debug build's two pseudo-locales after them.
 * - The current choice checked; under "System language" the language the app shows with the phone's
 *   languages, in its own name.
 * - A choice closes the dialog and hands over the language, once, whatever is tapped after it; the
 *   current one only closes it; "Cancel" and Back close it with nothing changed.
 * - The dialog's own texts in each of the four languages (strings_language.xml), and in `en-XA` none
 *   of them plain English (build plan §1 rule 5).
 * - The phone's fonts draw every letter of the four names and of i18n.md §5's sample (oʻ gʻ ʼ, Ўў Ққ
 *   Ғғ Ҳҳ, Ö Ğ Ş Ç), in the normal and the monospace typeface with their fallbacks.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val nav = StepNavigator()
    private val applied = CopyOnWriteArrayList<LanguageChoice>()
    private var current by mutableStateOf(LanguageChoice.SYSTEM)
    private var systemShown by mutableStateOf(AppLanguage.EN)

    /** The dialog as the route draws it, in [language] (a BCP 47 tag; the device's own when null). */
    private fun show(pseudo: Boolean = true, language: String? = null) {
        val base = compose.activity
        val context = language?.let { inLanguage(base, it) } ?: base
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
            ) {
                SettingsTestTheme {
                    LanguageSwitch(
                        choices = LanguageChoice.offered(pseudo),
                        current = current,
                        systemShown = systemShown,
                        nav = nav,
                        apply = { applied += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun inLanguage(base: Context, tag: String): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocales(AndroidLocaleList.forLanguageTags(tag)) }
        return base.createConfigurationContext(configuration)
    }

    private fun row(choice: LanguageChoice) = compose.onNodeWithTag(LanguageTags.choice(choice))

    /** The texts of [choice]'s row, as drawn (each with its spans). */
    private fun textsOf(choice: LanguageChoice): List<AnnotatedString> =
        row(choice).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()

    @Test
    fun theChoicesAreInTheirFixedOrderEachInItsOwnLanguage() {
        show(pseudo = true)
        val choices = LanguageChoice.offered(pseudo = true)
        val tops = choices.map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("top to bottom in the fixed order", tops.sorted(), tops)
        assertEquals(choices.size, tops.toSet().size)

        val names = mapOf(
            LanguageChoice.UZ_LATN to ("Oʻzbekcha" to "uz"),
            LanguageChoice.UZ_CYRL to ("Ўзбекча" to "uz-Cyrl"),
            LanguageChoice.EN to ("English" to "en"),
            LanguageChoice.RU to ("Русский" to "ru"),
        )
        for ((choice, nameAndTag) in names) {
            val (name, tag) = nameAndTag
            val text = textsOf(choice).single()
            assertEquals(name, text.text)
            // Marked as text of its own language, whatever the app's language is.
            assertEquals(name, LocaleList(tag), text.spanStyles.single().item.localeList)
            assertEquals("the whole name is marked", 0 until name.length, text.spanStyles.single().let { it.start until it.end })
        }
        assertEquals(listOf("English (XA)"), textsOf(LanguageChoice.EN_XA).map { it.text })
        assertEquals(listOf("Arabic (XB)"), textsOf(LanguageChoice.AR_XB).map { it.text })
        compose.onNodeWithTag(LanguageTags.CANCEL).assertExists()
    }

    @Test
    fun releaseBuildsOfferNoPseudoLocales() {
        show(pseudo = false)
        for (choice in LanguageChoice.offered(pseudo = false)) row(choice).assertExists()
        row(LanguageChoice.EN_XA).assertDoesNotExist()
        row(LanguageChoice.AR_XB).assertDoesNotExist()
    }

    @Test
    fun theCurrentChoiceIsChecked() {
        show()
        for (now in LanguageChoice.offered(pseudo = true)) {
            compose.runOnIdle { current = now }
            compose.waitForIdle()
            for (choice in LanguageChoice.offered(pseudo = true)) {
                if (choice == now) row(choice).assertIsSelected() else row(choice).assertIsNotSelected()
            }
        }
        assertTrue("checking changed nothing", nav.steps.isEmpty() && applied.isEmpty())
    }

    @Test
    fun underSystemLanguageIsTheLanguageTheAppShowsWithThePhonesLanguages() {
        show()
        val system = compose.activity.getString(R.string.language_system)
        for ((shown, name, tag) in listOf(
            Triple(AppLanguage.EN, "English", "en"),
            Triple(AppLanguage.RU, "Русский", "ru"),
            Triple(AppLanguage.UZ_CYRL, "Ўзбекча", "uz-Cyrl"),
            Triple(AppLanguage.UZ_LATN, "Oʻzbekcha", "uz"),
        )) {
            compose.runOnIdle { systemShown = shown }
            compose.waitForIdle()
            val texts = textsOf(LanguageChoice.SYSTEM)
            assertEquals(listOf(system, name), texts.map { it.text })
            assertEquals(LocaleList(tag), texts[1].spanStyles.single().item.localeList)
        }
    }

    @Test
    fun aChoiceClosesTheDialogAndAppliesItOnce() {
        current = LanguageChoice.EN
        show()
        row(LanguageChoice.RU).performClick()
        compose.waitForIdle()
        assertEquals(listOf("back"), nav.steps)
        assertEquals(listOf(LanguageChoice.RU), applied)
        // A second tap while the dialog closes: nothing more.
        row(LanguageChoice.UZ_CYRL).performClick()
        compose.onNodeWithTag(LanguageTags.CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(listOf("back"), nav.steps)
        assertEquals(listOf(LanguageChoice.RU), applied)
    }

    @Test
    fun theCheckedChoiceOnlyCloses() {
        current = LanguageChoice.UZ_CYRL
        show()
        row(LanguageChoice.UZ_CYRL).performClick()
        compose.waitForIdle()
        assertEquals(listOf("back"), nav.steps)
        assertTrue("the language that is already set is not set again", applied.isEmpty())
    }

    @Test
    fun cancelClosesWithNothingChanged() {
        show()
        compose.onNodeWithTag(LanguageTags.CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(listOf("back"), nav.steps)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun backClosesWithNothingChanged() {
        show()
        compose.onNode(isDialog()).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf("back"), nav.steps)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun theDialogsTextsInEachLanguage() {
        val expected = mapOf(
            "en" to listOf("Language", "System language"),
            "uz-Latn" to listOf("Til", "Tizim tili"),
            "uz" to listOf("Til", "Tizim tili"),
            "uz-Cyrl" to listOf("Тил", "Тизим тили"),
            "ru" to listOf("Язык", "Язык системы"),
        )
        for ((tag, texts) in expected) {
            val context = inLanguage(compose.activity, tag)
            assertEquals(tag, texts, listOf(context.getString(R.string.language_title), context.getString(R.string.language_system)))
        }
        show(language = "uz-Cyrl")
        compose.onNode(hasText("Тил")).assertExists()
        assertEquals("Тизим тили", textsOf(LanguageChoice.SYSTEM).first().text)
        // The names stay as they are in every language.
        assertEquals("Oʻzbekcha", textsOf(LanguageChoice.UZ_LATN).single().text)
        assertEquals("Русский", textsOf(LanguageChoice.RU).single().text)
    }

    @Test
    fun inEnXaNoTextOfTheDialogIsPlainEnglish() {
        // Every translatable string of strings_language.xml is pseudo-localized.
        val english = compose.activity
        val pseudo = inLanguage(english, "en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("language_") && !it.name.startsWith("language_pseudo_") }
        assertEquals("language_title and language_system", 2, strings.size)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals(field.name, english.getString(id), pseudo.getString(id))
        }
        show(language = "en-XA")
        val shown = compose.shownTexts()
        // Names, never translated: the four languages (the System row's line is English here), and
        // the pseudo-locales' own names.
        val names = setOf("Oʻzbekcha", "Ўзбекча", "English", "Русский", "English (XA)", "Arabic (XB)")
        val plain = setOf(
            english.getString(R.string.language_title),
            english.getString(R.string.language_system),
            english.getString(R.string.common_cancel),
        )
        for (text in shown) {
            assertTrue("'$text' is plain English in en-XA", text !in plain)
            assertTrue("'$text' is neither a name nor pseudo-localized", text in names || text.startsWith("["))
        }
        assertTrue(shown.contains(pseudo.getString(R.string.language_title)))
    }

    @Test
    fun thePhonesFontsDrawEveryLetter() {
        val sample = "Oʻzbekcha Ўзбекча Русский oʻ gʻ ʼ Ўў Ққ Ғғ Ҳҳ Ö Ğ Ş Ç"
        for (typeface in listOf(Typeface.DEFAULT, Typeface.MONOSPACE)) {
            val paint = Paint().apply { this.typeface = typeface }
            var i = 0
            while (i < sample.length) {
                val cp = sample.codePointAt(i)
                val letter = String(Character.toChars(cp))
                if (!Character.isWhitespace(cp)) {
                    assertTrue("U+%04X in %s".format(Locale.ROOT, cp, if (typeface == Typeface.DEFAULT) "the normal font" else "the monospace font"), paint.hasGlyph(letter))
                }
                i += Character.charCount(cp)
            }
        }
    }
}
