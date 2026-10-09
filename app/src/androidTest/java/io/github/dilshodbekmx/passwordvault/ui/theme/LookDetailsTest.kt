package io.github.dilshodbekmx.passwordvault.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §12, `w5-carry` step 2, [device]: the details that differ between the styles besides
 * colour, as the app's own screens draw them (docs/design/looks/README.md "What differs between
 * styles besides colour", "The icon and the name"). Each test walks every style the phone offers, in
 * light and in dark, through [LookGallery] (one composition, the style changed in the settings), and
 * reads the screen's pixels; each detail is read from the theme, so the screens never ask which
 * style is chosen.
 */
@RunWith(AndroidJUnit4::class)
class LookDetailsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var gallery: LookGallery? = null

    @Before
    fun touchMode() = enterTouchMode(compose.activity)

    @After
    fun tearDown() {
        gallery?.close()
    }

    private fun open(): LookGallery {
        lateinit var made: LookGallery
        compose.runOnUiThread { made = LookGallery().also { gallery = it } }
        made.list.awaitList()
        compose.setContent { LookGalleryHost(made) }
        compose.waitForIdle()
        return made
    }

    /** Every style the phone offers, in light and in dark, with its look on this phone. */
    private fun everyLook(gallery: LookGallery, check: (look: Look, what: String) -> Unit) {
        for (style in StyleChoice.offered(StyleChoice.hasPhoneColours(Build.VERSION.SDK_INT))) {
            for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
                compose.runOnIdle {
                    gallery.settings.update {
                        it.style = style
                        it.theme = theme
                    }
                }
                compose.waitForIdle()
                check(Looks.shown(style, dark = theme == ThemeChoice.DARK, compose.activity), "$style ${theme.name.lowercase()}")
            }
        }
    }

    private fun show(gallery: LookGallery, screen: LookGallery.Screen, tag: String) {
        compose.runOnIdle { gallery.shown = screen }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * The app's logo, the launcher icon's shield on a tile (README "The icon and the name"), on
     * Welcome, on the PIN pad and on the fingerprint-only screen: the tile in the colour of the
     * style (the panel's on Bank blue's pad), the shield in the colour that lies on it.
     */
    @Test
    fun theLogoIsOnWelcomeAndOnTheLockScreensInEveryStyle() {
        val made = open()
        everyLook(made) { look, what ->
            val c = look.colors
            show(made, LookGallery.Screen.WELCOME, SetupTags.LOGO)
            assertLogo(SetupTags.LOGO, c.primary, c.onPrimary, "$what: Welcome")

            compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.PAD) }
            show(made, LookGallery.Screen.LOCK, PinPadTags.DOTS)
            val top = LockTop.of(look)
            assertLogo(LockTags.LOGO, top.logoTile, top.logoMark, "$what: the PIN pad")

            compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.FINGERPRINT_ONLY) }
            show(made, LookGallery.Screen.LOCK, LockTags.FINGERPRINT_ONLY)
            assertLogo(LockTags.LOGO, top.logoTile, top.logoMark, "$what: fingerprint only")
        }
    }

    /** The logo [tag]: its tile at the top, clear of the shield, and the shield's colour inside it. */
    private fun assertLogo(tag: String, tile: Color, mark: Color, what: String) {
        compose.awaitPixel(tag, tile, "$what: the logo's tile", fx = 0.5f, fy = 0.08f)
        compose.awaitHasPixel(tag, mark, Offset(0.35f, 0.3f), Offset(0.65f, 0.6f), "$what: the shield on the tile")
    }
}
