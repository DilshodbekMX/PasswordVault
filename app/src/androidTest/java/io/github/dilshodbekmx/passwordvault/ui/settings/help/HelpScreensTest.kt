package io.github.dilshodbekmx.passwordvault.ui.settings.help

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpBlock
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpFacts
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpPages
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LicenceTextReader
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesCatalog
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.PartName
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTags
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import io.github.dilshodbekmx.passwordvault.ui.settings.StepNavigator
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 7.3 on a device: every help page of ux.md §5.22 shows its heading and every block of
 * text, its buttons open the pages they name and its arrow goes back; the licences page shows every
 * part of [LicencesCatalog] on its card, the EFF list with its licence's address and the change made
 * to it, and every licence text and both word lists' descriptions read from the APK (`res/raw` and
 * `:core`'s Java resources).
 */
@RunWith(AndroidJUnit4::class)
class HelpScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val nav = StepNavigator()
    private val facts = HelpFacts.of(ClipDelay.MINUTE_1)

    private fun text(text: UiText): String = text.resolve(compose.activity.resources)

    private fun blockText(block: HelpBlock): String = when (block) {
        is HelpBlock.Heading -> text(block.text)
        is HelpBlock.Paragraph -> text(block.text)
        is HelpBlock.AppHead -> text(block.version)
        is HelpBlock.Open -> text(block.text)
    }

    @Test
    fun everyHelpPageShowsItsHeadingAndEveryBlock() {
        var shown by mutableStateOf(HelpPage.PROTECTION)
        compose.setContent { SettingsTestTheme { HelpScreen(shown, HelpPages.blocks(shown, facts), nav) } }
        for (page in HelpPage.entries) {
            compose.runOnUiThread { shown = page }
            compose.waitForIdle()
            val column = compose.onNodeWithTag(HelpTags.page(page))
            column.assertExists()
            if (page != HelpPage.VERSION) {
                column.performScrollToNode(hasTestTag(HelpTags.TITLE))
                compose.onNodeWithTag(HelpTags.TITLE).assertTextEquals(compose.activity.getString(HelpPages.title(page)))
            }
            val blocks = HelpPages.blocks(page, facts)
            for (block in blocks) {
                val expected = blockText(block)
                column.performScrollToNode(hasText(expected))
                compose.onNode(hasText(expected) and hasAnyAncestor(hasTestTag(HelpTags.page(page)))).assertIsDisplayed()
            }
        }
        // The Version page's head: the app's name and the version of this build.
        compose.onNodeWithTag(HelpTags.page(HelpPage.VERSION)).performScrollToNode(hasTestTag(HelpTags.VERSION))
        compose.onNodeWithTag(HelpTags.VERSION).assertTextEquals(text(UiText.of(R.string.help_version_number, facts.versionName, facts.versionCode)))
    }

    @Test
    fun theButtonsOpenThePagesTheyName_andTheArrowGoesBack() {
        var shown by mutableStateOf(HelpPage.FORGOT_PIN)
        compose.setContent { SettingsTestTheme { HelpScreen(shown, HelpPages.blocks(shown, facts), nav) } }
        compose.waitForIdle()
        tap(HelpPage.FORGOT_PIN, SettingsTags.help(HelpPage.FORGOT_MASTER_PASSWORD))
        compose.runOnUiThread { shown = HelpPage.FORGOT_MASTER_PASSWORD }
        compose.waitForIdle()
        tap(HelpPage.FORGOT_MASTER_PASSWORD, SettingsTags.help(HelpPage.FORGOT_PIN))
        compose.runOnUiThread { shown = HelpPage.VERSION }
        compose.waitForIdle()
        tap(HelpPage.VERSION, SettingsTags.open(Route.Licences))
        compose.onNodeWithTag(SettingsTags.BACK).performClick()
        compose.waitForIdle()
        assertEquals(listOf("go Help", "go Help", "go ${Route.Licences.name}", "back"), nav.steps)
    }

    private fun tap(page: HelpPage, tag: String) {
        compose.onNodeWithTag(HelpTags.page(page)).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theLicencesPageNamesEveryPart_withTheEffListsLicenceLinkAndChange() {
        showLicences()
        for (group in LicencesCatalog.groups) {
            scrollTo(LicencesTags.heading(group.key))
            compose.onNodeWithTag(LicencesTags.heading(group.key)).assertTextEquals(compose.activity.getString(group.title))
            for (part in group.parts) {
                scrollTo(LicencesTags.part(part.key))
                val name = when (val n = part.name) {
                    is PartName.Fixed -> n.text
                    is PartName.Res -> compose.activity.getString(n.id)
                }
                inCard(part.key, listOfNotNull(name, part.version).joinToString(" "))
                inCard(part.key, compose.activity.getString(part.use))
                for (holder in part.holders) inCard(part.key, holder)
                part.licence?.let { licence ->
                    inCard(part.key, compose.activity.getString(R.string.help_licences_licence, licence.title))
                    for (url in licence.urls) inCard(part.key, url)
                }
                part.source?.let { inCard(part.key, it) }
            }
        }
        scrollTo(LicencesTags.part("eff"))
        inCard("eff", "https://creativecommons.org/licenses/by/4.0/")
        inCard("eff", compose.activity.getString(R.string.help_licences_eff_change))
    }

    @Test
    fun everyLicenceTextAndBothDescriptionsAreReadFromTheApk() {
        showLicences()
        val firstLines = mapOf(
            "zxcvbn4j" to "The MIT License (MIT)",
            "argon2kt" to "MIT License",
            "bouncycastle" to "MIT License (https://opensource.org/licenses/MIT)",
            "eff" to "Attribution 4.0 International",
            "uzwiki" to "Attribution-ShareAlike 4.0 International",
            "androidx" to "Apache License Version 2.0, January 2004 http://www.apache.org/licenses/",
        )
        for (part in LicencesCatalog.parts.filter { it.text != null }) {
            scrollTo(LicencesTags.licence(part.key))
            compose.onNodeWithTag(LicencesTags.licence(part.key)).assertTextEquals(compose.activity.getString(R.string.help_licences_show_licence))
            compose.onNodeWithTag(LicencesTags.licence(part.key)).performClick()
            awaitTag(LicencesTags.licenceText(part.key))
            val first = compose.onNodeWithTag(LicencesTags.licenceText(part.key)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
            assertTrue("${part.key}: its licence text could not be read", first != compose.activity.getString(R.string.help_licences_unreadable))
            firstLines[part.key]?.let { assertEquals(part.key, it, first) }
            scrollTo(LicencesTags.licence(part.key))
            compose.onNodeWithTag(LicencesTags.licence(part.key)).assertTextEquals(compose.activity.getString(R.string.help_licences_hide_licence))
            compose.onNodeWithTag(LicencesTags.licence(part.key)).performClick()
            compose.waitForIdle()
        }
        val headings = mapOf(
            "passphrase" to "Word list: where it comes from, its licence and the change made to it",
            "strength" to "Strength word list: sources, licences and counts",
        )
        for ((group, heading) in headings) {
            scrollTo(LicencesTags.details(group))
            compose.onNodeWithTag(LicencesTags.details(group)).performClick()
            awaitTag(LicencesTags.detailsText(group))
            compose.onNodeWithTag(LicencesTags.detailsText(group)).assertTextEquals(heading)
            scrollTo(LicencesTags.details(group))
            compose.onNodeWithTag(LicencesTags.details(group)).assertTextEquals(compose.activity.getString(R.string.help_licences_hide_details))
        }
        // The EFF list's description says what was changed.
        scrollTo(LicencesTags.detailsText("passphrase"))
        compose.onNodeWithTag(LicencesTags.LIST).performScrollToNode(hasText("yo-yo", substring = true))
    }

    private fun showLicences() {
        val vm = LicencesViewModel(LicenceTextReader(compose.activity.applicationContext.resources)::read, Dispatchers.IO)
        compose.setContent { SettingsTestTheme { LicencesScreen(vm, nav) } }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(LicencesTags.LIST).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun inCard(key: String, text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(LicencesTags.part(key))), useUnmergedTree = true).assertExists()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        scrollTo(tag)
        compose.waitUntil(10_000) {
            val node = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().firstOrNull() ?: return@waitUntil false
            node.config[SemanticsProperties.Text].joinToString("") != compose.activity.getString(R.string.help_licences_loading)
        }
    }
}
