package io.github.dilshodbekmx.passwordvault.ui.settings.help

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpFacts
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpPages
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LicenceTextReader
import io.github.dilshodbekmx.passwordvault.ui.settings.LicenceTexts
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesCatalog
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.PartName
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import io.github.dilshodbekmx.passwordvault.ui.settings.StepNavigator
import io.github.dilshodbekmx.passwordvault.ui.settings.TextSource
import io.github.dilshodbekmx.passwordvault.ui.settings.shownTexts
import io.github.dilshodbekmx.passwordvault.ui.shell.FirstCopyTip
import io.github.dilshodbekmx.passwordvault.ui.shell.FirstCopyTipTags
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5 for package 7.3: every `help_` string and plural comes out pseudo-localized in
 * `en-XA` (none is plain English or wrongly marked untranslatable), and the help pages, the licences
 * page and the first-copy tip, drawn in `en-XA`, show no text that stayed plain English, so none is
 * written into the code. Not texts of their own, and so left in English on purpose: the app's name
 * (`app_name`, untranslatable), the names, versions and copyright lines of the licensed parts, the
 * licences' names and the web addresses (i18n.md §3, "Fixed spellings").
 */
@RunWith(AndroidJUnit4::class)
class HelpPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun pseudo(base: Context): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("en-XA")) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun everyHelpStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo(english)
        val strings = R.string::class.java.fields.filter { it.name.startsWith("help_") }
        assertTrue("the strings are found: ${strings.size}", strings.size >= 70)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("help_") }
        assertTrue("the plurals are found: ${plurals.size}", plurals.size >= 4)
        for (field in plurals) {
            val id = field.getInt(null)
            for (n in listOf(1, 5)) {
                assertNotEquals("plural ${field.name} ($n)", english.resources.getQuantityString(id, n, n), pseudo.resources.getQuantityString(id, n, n))
            }
        }
    }

    @Test
    fun theScreensDrawEveryTextFromResources() {
        val pseudo = pseudo(compose.activity)
        val nav = StepNavigator()
        val settings = FakeAppSettings().also { it.writeDefaults() }
        val clipboard = FakeSecretClipboard(elapsedMs = { android.os.SystemClock.elapsedRealtime() })
        val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
        val licences = LicencesViewModel(LicenceTextReader(compose.activity.applicationContext.resources)::read, Dispatchers.IO)
        var screen by mutableStateOf<HelpPage?>(HelpPage.PROTECTION)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides pseudo,
                LocalConfiguration provides pseudo.resources.configuration,
                LocalResources provides pseudo.resources,
            ) {
                SettingsTestTheme {
                    Box {
                        val page = screen
                        if (page == null) LicencesScreen(licences, nav) else HelpScreen(page, HelpPages.blocks(page, HelpFacts.of(ClipDelay.MINUTE_1)), nav)
                        FirstCopyTip(settings, clipboard, lock, scope, Dispatchers.Main.immediate, samsung = false)
                    }
                }
            }
        }
        compose.waitForIdle()

        // Every help page, from the top to the bottom.
        for (page in HelpPage.entries) {
            compose.runOnUiThread { screen = page }
            compose.waitForIdle()
            // The page's heading (not on the Version page) and its blocks, one item each.
            val items = HelpPages.blocks(page, HelpFacts.of(ClipDelay.MINUTE_1)).size + if (page == HelpPage.VERSION) 0 else 1
            for (index in 0 until items) {
                compose.onNodeWithTag(HelpTags.page(page)).performScrollToIndex(index)
                check("the help page $page at block $index")
            }
        }

        // The licences page, every card, a licence and a word list's description shown.
        compose.runOnUiThread { screen = null }
        compose.waitForIdle()
        for (part in LicencesCatalog.parts) {
            scrollTo(LicencesTags.part(part.key))
            check("the licences page at ${part.key}")
        }
        scrollTo(LicencesTags.licence("zxcvbn4j"))
        compose.onNodeWithTag(LicencesTags.licence("zxcvbn4j")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(LicencesTags.licenceText("zxcvbn4j"))).fetchSemanticsNodes().isNotEmpty() }
        scrollTo(LicencesTags.licence("zxcvbn4j"))
        check("the licences page with a licence shown", shown = listOf(TextSource.Raw(R.raw.licence_mit_zxcvbn4j) to false))
        scrollTo(LicencesTags.details("strength"))
        compose.onNodeWithTag(LicencesTags.details("strength")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(LicencesTags.detailsText("strength"))).fetchSemanticsNodes().isNotEmpty() }
        scrollTo(LicencesTags.details("strength"))
        val open = listOf(TextSource.Raw(R.raw.licence_mit_zxcvbn4j) to false, TextSource.Classpath("strength/SOURCES.md") to true)
        check("the licences page with a description shown", shown = open)

        // The first-copy tip, over the page.
        compose.runOnUiThread { clipboard.copy("hunter2", UiText.of(R.string.help_tip_title), sensitive = true) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(FirstCopyTipTags.OK)).fetchSemanticsNodes().isNotEmpty() }
        check("the first-copy tip over the licences page", shown = open)
        assertTrue(compose.shownTexts().any { it == pseudo.getString(R.string.help_force_stop) })
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(LicencesTags.LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /**
     * Every text shown now has a letter that the pseudo-locale made, except the fixed spellings that
     * are not translated, and the paragraphs of the licence texts and descriptions [shown] (each with
     * whether it is Markdown), which stay in English.
     */
    private fun check(what: String, shown: List<Pair<TextSource, Boolean>> = emptyList()) {
        val fixed = buildSet {
            add(compose.activity.getString(R.string.app_name))
            for (part in LicencesCatalog.parts) {
                val name = (part.name as? PartName.Fixed)?.text
                if (name != null) add(listOfNotNull(name, part.version).joinToString(" "))
                addAll(part.holders)
                part.licence?.let { addAll(it.urls) }
                part.source?.let { add(it) }
            }
            val reader = LicenceTextReader(compose.activity.applicationContext.resources)
            for ((source, markdown) in shown) addAll(LicenceTexts.paragraphs(reader.read(source), markdown).map { it.text })
        }
        val texts = compose.shownTexts().filterNot { it in fixed }
        assertTrue("$what: the screen shows texts", texts.isNotEmpty())
        for (text in texts) assertTrue("\"$text\" stayed plain English in en-XA ($what)", text.any { it.code > 0x7F })
    }
}
