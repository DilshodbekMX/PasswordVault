package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.settings.AppearanceDialog
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsRig
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTags
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsViewModel
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §12, 4.3b, [device]: Settings › Appearance (docs/design/looks/README.md "What the app
 * offers"). The row and the dialog are drawn as the route of "Appearance" draws them
 * (`SettingsNav.kt`): the Settings list with the dialog over it, both on one `SettingsViewModel`,
 * inside the app's root theme, which follows the same settings ([SettingsRig]'s fakes).
 *
 * - The row shows both choices and opens the route.
 * - The dialog has the two choices; a choice is stored and applied at once, to the dialog and to the
 *   list behind it, and the dialog stays open until "Done".
 * - Each style has a small preview in its own colours.
 * - "Phone's colours" is absent below Android 12 (API 31) and offered from there on.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = SettingsRig()
    private var dialog by mutableStateOf(false)
    private lateinit var vm: SettingsViewModel

    @After
    fun tearDown() = rig.close()

    @Before
    fun touchMode() = enterTouchMode(compose.activity)

    private fun show(withDialog: Boolean) {
        rig.settings.theme.value = ThemeChoice.LIGHT
        dialog = withDialog
        compose.setContent {
            WindowPolicy.Root {
                PasswordVaultTheme(rig.settings) {
                    vm = viewModel { rig.settingsViewModel() }
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    Surface(Modifier.fillMaxSize().testTag(PAGE), color = MaterialTheme.colorScheme.background) {
                        SettingsScreen(vm, rig.nav)
                    }
                    if (dialog) {
                        AppearanceDialog(
                            styles = ui.styles,
                            style = ui.style,
                            theme = ui.theme,
                            onStyle = vm::setStyle,
                            onTheme = vm::setTheme,
                            onDone = {
                                rig.nav.back()
                                dialog = false
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    @Test
    fun theRowShowsBothChoicesAndOpensAppearance() {
        show(withDialog = false)
        val row = SettingsTags.open(Route.Theme)
        compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).assertTextContains(text(R.string.settings_appearance))
        compose.onNodeWithTag(row).assertTextContains("Bank blue · Light")
        compose.runOnIdle { rig.settings.update { it.style = StyleChoice.NIGHT_VAULT; it.theme = ThemeChoice.SYSTEM } }
        compose.onNodeWithTag(row).assertTextContains("Night vault · System")
        compose.onNodeWithTag(row).performClick()
        compose.waitForIdle()
        assertEquals(listOf("go ${Route.Theme.name}"), rig.nav.steps)
    }

    @Test
    fun aChoiceIsAppliedAtOnceAndTheDialogStaysOpen() {
        show(withDialog = true)
        // The two choices, as stored: Bank blue, Light.
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.BANK_BLUE)).assertIsSelected()
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.EMERALD)).assertIsNotSelected()
        compose.onNodeWithTag(SettingsTags.theme(ThemeChoice.LIGHT)).assertIsSelected()
        dialogIs(Looks.of(StyleChoice.BANK_BLUE, dark = false))

        // A style: stored, shown as chosen, and the open dialog is drawn in it.
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.EMERALD)).performClick()
        compose.waitUntil(5_000) { rig.settings.style.value == StyleChoice.EMERALD }
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.EMERALD)).assertIsSelected()
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.BANK_BLUE)).assertIsNotSelected()
        dialogIs(Looks.of(StyleChoice.EMERALD, dark = false))

        // Light or dark: the same.
        compose.onNodeWithTag(SettingsTags.theme(ThemeChoice.DARK)).performClick()
        compose.waitUntil(5_000) { rig.settings.theme.value == ThemeChoice.DARK }
        compose.onNodeWithTag(SettingsTags.theme(ThemeChoice.DARK)).assertIsSelected()
        dialogIs(Looks.of(StyleChoice.EMERALD, dark = true))

        compose.onNodeWithTag(SettingsTags.style(StyleChoice.NIGHT_VAULT)).performClick()
        compose.waitUntil(5_000) { rig.settings.style.value == StyleChoice.NIGHT_VAULT }
        dialogIs(Looks.of(StyleChoice.NIGHT_VAULT, dark = true))
        assertEquals("light or dark was left as chosen", ThemeChoice.DARK, rig.settings.theme.value)
        assertTrue("nothing closed the dialog", rig.nav.steps.isEmpty())

        // "Done" closes it; the list behind has the look that was chosen.
        compose.onNodeWithTag(SettingsTags.DIALOG_DONE).performClick()
        compose.waitForIdle()
        assertEquals(listOf("back"), rig.nav.steps)
        compose.onNodeWithTag(SettingsTags.DIALOG_DONE).assertDoesNotExist()
        compose.awaitPixel(PAGE, Looks.of(StyleChoice.NIGHT_VAULT, dark = true).colors.background, "Settings after Done", fx = EDGE, fy = 0.5f)
        assertTrue("the look needs no master password (lock.md §8.2)", rig.lock.purposes.isEmpty())
    }

    /** The open dialog is drawn in [look]: Material 3 draws a dialog on `surfaceContainerHigh`. */
    private fun dialogIs(look: Look) {
        compose.awaitPixel(SettingsTags.DIALOG_DONE, look.colors.surfaceContainerHigh, "the dialog's own colour", fx = 0.5f, fy = -0.2f)
    }

    @Test
    fun everyStyleHasAPreviewInItsOwnColours() {
        show(withDialog = true)
        for (dark in listOf(false, true)) {
            compose.runOnIdle { rig.settings.update { it.theme = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT } }
            val styles = StyleChoice.offered(StyleChoice.hasPhoneColours(Build.VERSION.SDK_INT))
            for (style in styles) {
                val look = Looks.shown(style, dark, compose.activity)
                val preview = SettingsTags.stylePreview(style)
                val what = "the preview of $style, ${if (dark) "dark" else "light"}"
                compose.onNodeWithTag(preview, useUnmergedTree = true).assertIsDisplayed()
                compose.awaitPixel(preview, look.colors.background, "$what: its background", fx = 0.5f, fy = 0.12f)
                compose.awaitPixel(preview, look.colors.primary, "$what: its badge", fx = BADGE, fy = 0.5f)
                compose.awaitPixel(preview, look.colors.primaryContainer, "$what: its dot", fx = DOT, fy = 0.5f)
            }
            // The previews are four different pictures: every style can be told from the others.
            val badges = styles.map { compose.pixel(SettingsTags.stylePreview(it), BADGE, 0.5f) }
            assertEquals("the badges of the previews differ: ${badges.map { it.hex() }}", styles.size, badges.toSet().size)
        }
    }

    @Test
    @SdkSuppress(maxSdkVersion = 30)
    fun phonesColoursIsAbsentBeforeAndroid12() {
        show(withDialog = true)
        for (style in OWN_STYLES) compose.onNodeWithTag(SettingsTags.style(style)).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.style(StyleChoice.PHONE_COLOURS)).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTags.stylePreview(StyleChoice.PHONE_COLOURS), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(text(R.string.settings_style_phone) !in shownTexts())
        compose.runOnIdle { assertEquals(OWN_STYLES, vm.ui.value.styles) }

        // A stored "Phone's colours" is read as Bank blue by the real settings on this phone.
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().clear().putString(PrefsAppSettings.STYLE, StyleChoice.PHONE_COLOURS.stored).commit())
            val settings = PrefsAppSettings(prefs)
            assertEquals(StyleChoice.BANK_BLUE, settings.style.value)
            assertTrue(settings.update { it.style = StyleChoice.PHONE_COLOURS })
            assertEquals("and it cannot be chosen there", StyleChoice.BANK_BLUE, settings.style.value)
        } finally {
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(FILE)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun phonesColoursIsOfferedFromAndroid12() {
        show(withDialog = true)
        val row = SettingsTags.style(StyleChoice.PHONE_COLOURS)
        compose.onNodeWithTag(row).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag(row).assertTextContains(text(R.string.settings_style_phone))
        compose.onNodeWithTag(row).assertTextContains(text(R.string.settings_style_phone_body))
        compose.runOnIdle { assertEquals(StyleChoice.entries, vm.ui.value.styles) }

        compose.onNodeWithTag(row).performClick()
        compose.waitUntil(5_000) { rig.settings.style.value == StyleChoice.PHONE_COLOURS }
        compose.onNodeWithTag(row).assertIsSelected()
        // The wallpaper's scheme, which Material 3 reads from the phone.
        dialogIs(Looks.phone(compose.activity, dark = false))

        // The real settings keep it on this phone.
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().clear().commit())
            assertTrue(PrefsAppSettings(prefs).update { it.style = StyleChoice.PHONE_COLOURS })
            assertEquals(StyleChoice.PHONE_COLOURS, PrefsAppSettings(prefs).style.value)
        } finally {
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(FILE)
        }
    }

    /** Every text on the screen and in the dialog. */
    private fun shownTexts(): List<String> = buildList {
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    }

    private companion object {
        const val PAGE = "probe.page"
        const val FILE = "pv_settings_appearance_test"

        /** A point at the screen's left edge, beside every row. */
        const val EDGE = 0.01f

        /** The badge of a preview, at the left, and its dot, at the right, as fractions of its width (64 dp). */
        const val BADGE = 17f / 64f
        const val DOT = 51f / 64f
    }
}
