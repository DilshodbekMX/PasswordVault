package io.github.dilshodbekmx.passwordvault.ui.entry

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.bare
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the entry package's screens walked once in the `en-XA` pseudo-locale (the
 * debug build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every
 * `entry_` string comes out pseudo-localized, so none is plain English or marked untranslatable;
 * the screens drawn in the pseudo-locale show the pseudo-localized texts, so none is written into
 * the code; and each screen is kept as a picture in the app's cache directory
 * (`cache/pv-screens/entry-*.png`) for a person to look for clipped text.
 */
@RunWith(AndroidJUnit4::class)
class EntryPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pseudo(tag: String): Context {
        val base = compose.activity
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    @Composable
    private fun InLocale(context: Context, content: @Composable () -> Unit) {
        val configuration = context.resources.configuration
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            // The direction comes from the window, not from these resources: ar-XB is laid out right to left.
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            PasswordVaultTheme(ThemeChoice.LIGHT, content)
        }
    }

    /** Keeps a picture of the whole screen in `cache/pv-screens/`: menus included, which are windows of their own. */
    private fun save(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "pv-screens").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "entry-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyEntryStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("entry_") }
        assertTrue("the entry package's strings are found: ${strings.size}", strings.size >= 70)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        assertTrue("the entry package has no plurals", R.plurals::class.java.fields.none { it.name.startsWith("entry_") })
    }

    @Test
    fun theAccountInEnXa() = account("en-XA")

    @Test
    fun theAccountInArXb() = account("ar-XB")

    @Test
    fun theStatesInEnXa() = states("en-XA")

    @Test
    fun bigLettersInEnXa() = bigLetters("en-XA")

    @Test
    fun bigLettersInArXb() = bigLetters("ar-XB")

    /** The account with a change pending, left out of the check and equal to the master password; its end; a row's menu. */
    private fun account(tag: String) {
        val context = pseudo(tag)
        val rig = EntryRig(Vault(entries = listOf(gmail(changePending = true).copy(checkIgnored = true)), masterPasswordMatches = listOf(GMAIL)))
        lateinit var vm: EntryViewModel
        compose.setContent {
            InLocale(context) {
                vm = viewModel { rig.entryViewModel(justSaved = true) }
                EntryScreen(vm, rig.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value is EntryUiState.Shown }
        val res = context.resources
        fun scrollTo(key: String) {
            compose.onNodeWithTag(EntryTags.CONTENT).performScrollToKey(key)
            compose.waitForIdle()
        }
        // The banners, at the top.
        compose.onNodeWithText(res.getString(R.string.entry_banner_master)).assertExists()
        compose.onNodeWithText(res.getString(R.string.entry_banner_change_yes)).assertExists()
        compose.onNodeWithText(res.getString(R.string.entry_saved)).assertExists()
        save("banners-$tag")
        // "Saved" lies over the bottom of the screen for a few seconds: wait until it is gone.
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTag(EntryTags.MESSAGE).fetchSemanticsNodes().isEmpty() }

        // The rows, with a password shown.
        scrollTo("username")
        compose.onNodeWithTag(EntryTags.show(EntryValue.OldPassword)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.entry_username), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(res.getString(R.string.entry_old_password), useUnmergedTree = true).assertExists()
        // Under each of the two passwords.
        compose.onAllNodesWithText(res.getString(R.string.entry_big_letters), useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText(compose.activity.getString(R.string.entry_username), useUnmergedTree = true).assertDoesNotExist()
        save("rows-$tag")

        scrollTo("notes")
        compose.onNodeWithText(res.getString(R.string.entry_notes), useUnmergedTree = true).assertExists()
        save("notes-$tag")

        // The end: the dates and the previous passwords, open.
        scrollTo("previous")
        compose.onNodeWithTag(EntryTags.PREVIOUS).performClick()
        compose.waitUntil(TIMEOUT_MS) { (vm.state.value as EntryUiState.Shown).details.previous.size == 2 }
        scrollTo("previous.1")
        compose.onNodeWithText(res.getString(R.string.entry_previous, "2"), substring = true, useUnmergedTree = true).assertExists()
        save("dates-$tag")

        // A row's menu, and the top bar's.
        compose.onNodeWithTag(EntryTags.previous(1)).performClick()
        compose.onNodeWithText(res.getString(R.string.entry_copy_previous)).assertExists()
        save("row-menu-$tag")
    }

    /** "No details yet", then an account that waits in Recently deleted. */
    private fun states(tag: String) {
        val context = pseudo(tag)
        val rig = EntryRig(Vault(entries = listOf(bare(), gmail().copy(deletedAt = System.currentTimeMillis()))))
        lateinit var empty: EntryViewModel
        lateinit var gone: EntryViewModel
        var which by mutableStateOf(0)
        compose.setContent {
            InLocale(context) {
                if (which == 0) {
                    empty = viewModel(key = "empty") { rig.entryViewModel(BANK) }
                    EntryScreen(empty, rig.nav)
                } else {
                    gone = viewModel(key = "gone") { rig.entryViewModel(GMAIL) }
                    EntryScreen(gone, rig.nav)
                }
            }
        }
        val res = context.resources
        compose.waitUntil(TIMEOUT_MS) { empty.state.value is EntryUiState.Shown }
        compose.onNodeWithText(res.getString(R.string.entry_empty)).assertExists()
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithText(res.getString(R.string.entry_leave_out)).assertExists()
        save("empty-$tag")

        Espresso.pressBack()
        compose.runOnUiThread { which = 1 }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { gone.state.value is EntryUiState.Gone }
        compose.onNodeWithText(res.getString(R.string.entry_gone_deleted)).assertExists()
        compose.onNodeWithText(res.getString(R.string.entry_open_deleted)).assertExists()
        save("gone-$tag")
    }

    /** "Show in big letters" for the password. */
    private fun bigLetters(tag: String) {
        val context = pseudo(tag)
        val rig = EntryRig(Vault(entries = listOf(gmail())))
        lateinit var vm: BigLettersViewModel
        compose.setContent {
            InLocale(context) {
                vm = viewModel { rig.bigLettersViewModel() }
                BigLettersScreen(vm, rig.nav)
            }
        }
        compose.waitForIdle()
        val res = context.resources
        compose.onNodeWithText(res.getString(R.string.entry_password)).assertExists()
        compose.onNodeWithText(res.getString(R.string.entry_big_hint)).assertExists()
        compose.onNodeWithTag(EntryTags.bigCell(4)).performClick()
        save("big-letters-$tag")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
