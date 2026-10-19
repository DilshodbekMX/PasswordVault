package io.github.dilshodbekmx.passwordvault.ui.security.change

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeScreen
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeTags
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeUiState
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeViewModel
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.SHARED
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.facebook
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.sharing
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the guided change walked once in the `en-XA` pseudo-locale (the debug build
 * makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `change_` string comes
 * out pseudo-localized, so none is plain English or marked untranslatable; the screen drawn in the
 * pseudo-locale shows the pseudo-localized texts, so none is written into the code; and each state is
 * kept as a picture in the app's cache directory (`cache/pv-screens/change-*.png`) for a person to
 * look for clipped text.
 */
@RunWith(AndroidJUnit4::class)
class GuidedChangePseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig: ChangeRig? = null

    @After
    fun tearDown() {
        rig?.close()
    }

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

    /** Keeps a picture of the whole screen in `cache/pv-screens/`. */
    private fun save(name: String) {
        LayoutHook.look(compose, "change", name)
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "pv-screens").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "change-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyChangeStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("change_") }
        assertTrue("the guided change's strings are found: ${strings.size}", strings.size >= 35)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        assertTrue("the guided change has no plurals", R.plurals::class.java.fields.none { it.name.startsWith("change_") })
    }

    @Test
    fun theStepsInEnXa() = steps("en-XA")

    @Test
    fun theStepsInArXb() = steps("ar-XB")

    @Test
    fun theEndInEnXa() = end("en-XA")

    @Test
    fun theEndInArXb() = end("ar-XB")

    @Test
    fun aGoneAccountInEnXa() = gone("en-XA")

    private fun show(context: Context, vault: Vault): GuidedChangeViewModel {
        val made = ChangeRig(vault).also { rig = it }
        lateinit var vm: GuidedChangeViewModel
        compose.setContent {
            InLocale(context) {
                vm = viewModel { made.viewModel(GMAIL) }
                GuidedChangeScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value !is GuidedChangeUiState.Loading }
        compose.waitForIdle()
        return vm
    }

    /** The steps as they open, with "Last one!"; after "Create new password"; step 5. */
    private fun steps(tag: String) {
        val context = pseudo(tag)
        val res = context.resources
        // Facebook has its own password already: Gmail is the last one of the group.
        val vm = show(context, Vault(entries = listOf(gmail(), facebook(password = "Fb-own-9!", history = listOf(HistoryItem(SHARED, 0L))))))
        compose.onNodeWithText(res.getString(R.string.change_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_last_one)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_step1, "Gmail")).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_open_website)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.change_open_website)).assertDoesNotExist()
        save("steps-top-$tag")

        compose.onNodeWithTag(GuidedChangeTags.CREATE).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(GuidedChangeTags.CREATE_ANOTHER)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GuidedChangeTags.CREATE_ANOTHER).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(GuidedChangeTags.CREATED)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GuidedChangeTags.SAVED).performScrollTo()
        compose.onNodeWithText(res.getString(R.string.change_created)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_saved)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_create_another)).assertExists()
        save("step3-saved-$tag")

        compose.onNodeWithTag(GuidedChangeTags.LATER).performScrollTo()
        compose.onNodeWithText(res.getString(R.string.change_yes)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_no)).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_hint_chrome)).assertExists()
        save("step5-$tag")
        assertTrue(vm.state.value is GuidedChangeUiState.Steps)
    }

    /** The end with "Next" and the backup line. */
    private fun end(tag: String) {
        val context = pseudo(tag)
        val res = context.resources
        val vm = show(context, sharing())
        compose.onNodeWithTag(GuidedChangeTags.CREATE).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(GuidedChangeTags.CREATE_ANOTHER)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GuidedChangeTags.YES).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.state.value is GuidedChangeUiState.Done }
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.change_done, "Gmail")).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_next, "Facebook")).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_backup_only_here, "Gmail")).assertExists()
        compose.onNodeWithText(res.getString(R.string.change_back_up_now)).assertExists()
        save("end-$tag")
    }

    /** An account that is gone. */
    private fun gone(tag: String) {
        val context = pseudo(tag)
        show(context, Vault(entries = listOf(facebook())))
        compose.onNodeWithText(context.resources.getString(R.string.change_gone)).assertExists()
        save("gone-$tag")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
