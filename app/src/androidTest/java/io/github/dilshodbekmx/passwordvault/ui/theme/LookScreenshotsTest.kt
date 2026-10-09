package io.github.dilshodbekmx.passwordvault.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryTags
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryValue
import io.github.dilshodbekmx.passwordvault.ui.list.ListTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTags
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the looks for the user (build plan §12, 4.3b and `w5-carry`): the lock screen's PIN
 * pad, the list, an account's screen and Settings, and the dialog "Appearance", in each style the
 * phone offers, light and dark.
 * [LookShots] saves each into `/data/local/tmp/pv-shots-look/` on the device as
 * `look-api<N>-<style>-<light|dark>-<screen>.png`; the package's agent pulls them into
 * /home/asus/pvwork/shots-look/. The same walk is a test of its own: one composition is kept
 * through every look, so every screen changes its look without being made again, and the
 * background of each picture is checked against the scheme it should show.
 */
@RunWith(AndroidJUnit4::class)
class LookScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var gallery: LookGallery? = null

    @After
    fun tearDown() {
        gallery?.close()
    }

    @Before
    fun touchMode() = enterTouchMode(compose.activity)

    @Test
    fun theScreensInEveryLook() {
        lateinit var made: LookGallery
        compose.runOnUiThread { made = LookGallery().also { gallery = it } }
        made.list.awaitList()
        compose.setContent { LookGalleryHost(made) }
        compose.waitForIdle()
        LookShots.start()

        val styles = StyleChoice.offered(StyleChoice.hasPhoneColours(Build.VERSION.SDK_INT))
        val expected = mutableListOf<String>()
        for (style in styles) {
            for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
                compose.runOnIdle {
                    made.settings.update {
                        it.style = style
                        it.theme = theme
                    }
                }
                val look = Looks.shown(style, dark = theme == ThemeChoice.DARK, compose.activity)
                val prefix = "look-api${Build.VERSION.SDK_INT}-${NAMES.getValue(style)}-${theme.name.lowercase()}"

                show(made, LookGallery.Screen.LOCK, PinPadTags.DOTS)
                compose.awaitPixel(LockTags.PAD, look.colors.background, "$prefix: the pad's screen", fx = 0.04f, fy = 0.97f)
                expected += shot("$prefix-pad")

                show(made, LookGallery.Screen.LIST, ListTags.LIST)
                compose.awaitPixel(ListTags.LIST, look.colors.background, "$prefix: the list", fx = 0.97f, fy = 0.02f)
                expected += shot("$prefix-list")

                show(made, LookGallery.Screen.ACCOUNT, EntryTags.CONTENT)
                showThePassword()
                compose.awaitPixel(EntryTags.CONTENT, look.colors.background, "$prefix: the account's screen", fx = 0.985f, fy = 0.01f)
                expected += shot("$prefix-account")

                show(made, LookGallery.Screen.SETTINGS, SettingsTags.LIST)
                compose.awaitPixel(SettingsTags.LIST, look.colors.background, "$prefix: Settings", fx = 0.985f, fy = 0.01f)
                expected += shot("$prefix-settings")

                show(made, LookGallery.Screen.APPEARANCE, SettingsTags.style(style))
                compose.awaitPixel(SettingsTags.DIALOG_DONE, look.colors.surfaceContainerHigh, "$prefix: the dialog Appearance", fx = 0.5f, fy = -0.2f)
                expected += shot("$prefix-appearance")
            }
        }
        assertEquals(styles.size * 2 * 5, expected.size)
        val taken = LookShots.taken()
        assertTrue("pictures missing: ${expected - taken.toSet()}", taken.containsAll(expected))
    }

    private fun show(gallery: LookGallery, screen: LookGallery.Screen, tag: String) {
        compose.runOnIdle { gallery.shown = screen }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Shows the account's password, which hides itself again after a while. */
    private fun showThePassword() {
        val value = EntryTags.value(EntryValue.Password)
        fun shown() = compose.onNodeWithTag(value, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == EntryRig.PASSWORD }
        if (!shown()) compose.onNodeWithTag(EntryTags.show(EntryValue.Password), useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { shown() }
    }

    private fun shot(name: String): String {
        LookShots.take(compose, name)
        return "$name.png"
    }

    private companion object {
        val NAMES = mapOf(
            StyleChoice.BANK_BLUE to "bank-blue",
            StyleChoice.EMERALD to "emerald",
            StyleChoice.NIGHT_VAULT to "night-vault",
            StyleChoice.PHONE_COLOURS to "phone-colours",
        )
    }
}
