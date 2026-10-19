package io.github.dilshodbekmx.passwordvault.ui.settings.master

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import io.github.dilshodbekmx.passwordvault.ui.settings.shownTexts
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5 for package 5.5: every `master_` string and plural comes out
 * pseudo-localized in `en-XA` (none is plain English or wrongly marked untranslatable), and both
 * routes, walked in `en-XA` through states that between them show every line, button and step of
 * the package, show no text and no label that stayed plain English, so none is written into the
 * code. Not texts of their own: the keypad's digits, and the suggested words, which are English by
 * design (ux.md §5.3: "The words are English. Type them with Latin letters."). The routes are walked
 * again in `ar-XB`, right to left, for build plan 6.2's layout walk (`LayoutHook`).
 */
@RunWith(AndroidJUnit4::class)
class MasterPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var walk: MasterWalk? = null

    @After
    fun tearDown() {
        walk?.close()
    }

    private fun pseudo(base: Context, tag: String = "en-XA"): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyMasterStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("master_") }
        assertTrue("the strings are found: ${strings.size}", strings.size >= 60)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("master_") }
        assertTrue("the plurals are found: ${plurals.size}", plurals.size >= 2)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 3)) {
                assertNotEquals("plural ${field.name} ($n)", english.resources.getQuantityString(id, n, n), pseudo.resources.getQuantityString(id, n, n))
            }
        }
    }

    @Test
    fun bothRoutesDrawEveryTextFromResources() = bothRoutes("en-XA")

    @Test
    fun bothRoutesDrawEveryTextFromResourcesInArXb() = bothRoutes("ar-XB")

    private fun bothRoutes(tag: String) {
        val pseudo = pseudo(compose.activity, tag)
        val made = MasterWalk(compose) { step -> check(step, tag) }.also { walk = it }
        val rtl = pseudo.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                SettingsTestTheme { made.Content() }
            }
        }
        made.walkChange(pictures = false)
        made.walkChangeFailures()
        made.walkNew(pictures = false)
        made.walkNewStops()
        made.walkFinish(pictures = false)
        made.walkFinish(pictures = true)
    }

    /** Every text shown now has a letter that the pseudo-locale [tag] made: nothing stayed plain English. */
    private fun check(step: String, tag: String) {
        LayoutHook.look(compose, "master", "$step-$tag")
        val texts = compose.shownTexts().filterNot { it.matches(DIGIT) || it.matches(WORDS) }
        assertTrue("$step: the screen shows texts", texts.size >= 2)
        for (text in texts) {
            assertTrue("\"$text\" stayed plain English in $tag ($step)", text.any { it.code > 0x7F })
        }
    }

    private companion object {
        val DIGIT = Regex("\\d")

        /** A suggested word, or the words joined as they are typed: English by design. */
        val WORDS = Regex("[a-z]+(-[a-z]+)*")
    }
}
