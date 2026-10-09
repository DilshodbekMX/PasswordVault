package io.github.dilshodbekmx.passwordvault.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.list.ListTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import io.github.dilshodbekmx.passwordvault.ui.shell.TAB_BAR_TAG
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /** The gallery, on the whole window, or in a box of [landscape]'s size. */
    private fun open(landscape: Pair<Float, Float>? = null): LookGallery {
        lateinit var made: LookGallery
        compose.runOnUiThread { made = LookGallery().also { gallery = it } }
        made.list.awaitList()
        compose.setContent {
            if (landscape == null) {
                LookGalleryHost(made)
            } else {
                Box(Modifier.requiredSize(landscape.first.dp, landscape.second.dp)) { LookGalleryHost(made) }
            }
        }
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

    /**
     * Bank blue's lock panel (README "Lock screen"; ux.md §5.9) in portrait: on the PIN pad the logo,
     * the title, the subtitle and the dots lie on a panel of the look's colours across the top, with
     * rounded lower corners, and the keys below it; on the fingerprint-only screen the logo and the
     * title. While it shows, the status bar's icons are light (the panel reaches behind the status
     * bar); after it they are the theme's again. The other styles draw no panel.
     */
    @Test
    fun bankBlueSetsTheTopOfTheLockScreenOnAPanel() {
        val made = open()
        everyLook(made) { look, what ->
            val themesIcons = look.colors.background.luminance() > 0.5f
            compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.PAD) }
            show(made, LookGallery.Screen.LOCK, PinPadTags.DOTS)
            val panel = look.lockPanel
            if (panel == null) {
                assertNoNode(LockTags.PANEL, "$what: no panel")
                assertEquals("$what: the theme's status bar icons", themesIcons, lightStatusBarIcons())
            } else {
                compose.awaitPixel(LockTags.PANEL, panel.colour, "$what: the panel", fx = 0.5f, fy = 0.96f)
                compose.awaitPixel(LockTags.PANEL, look.colors.background, "$what: the panel's rounded lower corner", fx = 0.003f, fy = 0.997f)
                val top = bounds(LockTags.PANEL)
                for (tag in listOf(LockTags.LOGO, LockTags.TITLE, LockTags.SUBTITLE, PinPadTags.DOTS)) {
                    assertTrue("$what: $tag lies on the panel", top.holds(bounds(tag)))
                }
                assertTrue("$what: the keys are below the panel", bounds(PinPadTags.key(1)).top >= top.bottom)
                compose.awaitHasPixel(LockTags.SUBTITLE, panel.quiet, Offset(0f, 0f), Offset(1f, 1f), "$what: the subtitle in the panel's quiet colour")
                compose.awaitHasPixel(LockTags.TITLE, panel.content, Offset(0f, 0f), Offset(1f, 1f), "$what: the title in the panel's colour")
                assertFalse("$what: light status bar icons over the panel", lightStatusBarIcons())

                compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.FINGERPRINT_ONLY) }
                show(made, LookGallery.Screen.LOCK, LockTags.FINGERPRINT_ONLY)
                compose.awaitPixel(LockTags.PANEL, panel.colour, "$what: fingerprint only: the panel", fx = 0.5f, fy = 0.96f)
                for (tag in listOf(LockTags.LOGO, LockTags.TITLE)) assertTrue("$what: fingerprint only: $tag on the panel", bounds(LockTags.PANEL).holds(bounds(tag)))
                assertFalse("$what: fingerprint only: light status bar icons", lightStatusBarIcons())
            }
            // Leaving the lock screen gives the status bar back to the theme.
            show(made, LookGallery.Screen.WELCOME, SetupTags.LOGO)
            assertEquals("$what: the theme's status bar icons after the lock screen", themesIcons, lightStatusBarIcons())
        }
    }

    /**
     * In landscape (ux.md §5.9: the texts and the dots on the left, the keypad on the right) Bank
     * blue's panel is a block with rounded corners around the title, the subtitle and the dots,
     * beside the keypad; it does not reach the status bar, whose icons stay the theme's.
     */
    @Test
    fun inLandscapeThePanelIsABlockBesideTheKeypad() {
        val made = open(landscape = LANDSCAPE)
        everyLook(made) { look, what ->
            compose.runOnIdle { made.lock.state.value = LockUiState.Locked(LookGallery.PAD) }
            show(made, LookGallery.Screen.LOCK, PinPadTags.DOTS)
            val panel = look.lockPanel
            if (panel == null) {
                assertNoNode(LockTags.PANEL, "$what: no panel")
            } else {
                compose.awaitPixel(LockTags.PANEL, panel.colour, "$what: the panel block", fx = 0.5f, fy = 0.96f)
                val block = bounds(LockTags.PANEL)
                assertTrue("$what: the dots on the block", block.holds(bounds(PinPadTags.DOTS)))
                assertTrue("$what: the block beside the keypad", block.right <= bounds(PinPadTags.key(1)).left)
            }
            assertEquals("$what: the theme's status bar icons", look.colors.background.luminance() > 0.5f, lightStatusBarIcons())
        }
    }

    /**
     * The list's "Add" button (README "\"Add\" button"; ux.md §5.11): its colour and its label's, and
     * its corners: fully rounded in Emerald, Material 3's 16 dp elsewhere (a point 8 dp in and 6 dp
     * down from its corner is on the button with 16 dp corners and off a pill of 56 dp).
     */
    @Test
    fun theAddButtonIsDrawnAsTheStyleSays() {
        val made = open()
        everyLook(made) { look, what ->
            show(made, LookGallery.Screen.LIST, ListTags.ADD)
            val add = look.addButton
            val c = look.colors
            compose.awaitPixel(ListTags.ADD, add.container(c), "$what: the Add button", fx = 0.05f, fy = 0.5f)
            compose.awaitHasPixel(ListTags.ADD, add.content(c), Offset(0.1f, 0.3f), Offset(0.95f, 0.7f), "$what: its icon and label")
            val corner = pixelAtDp(ListTags.ADD, 8f, 6f)
            if (add.fullyRounded) {
                assertFalse("$what: a pill has no corner there (${corner.hex()})", near(corner, add.container(c).toArgb(), 6))
            } else {
                assertTrue("$what: 16 dp corners reach there (${corner.hex()})", near(corner, add.container(c).toArgb(), 6))
            }
        }
    }

    /**
     * Emerald's two outlines (README "Where the built schemes differ" and the second step's list;
     * ux.md §5.11): in Emerald's light scheme, where the navigation bar and the search field are
     * white on a nearly white screen, a line of the look's colour along the bar's top edge and around
     * the search field; in every other look neither (no pixel of an outline's colour there).
     */
    @Test
    fun emeraldOutlinesTheBarAndTheSearchFieldInLight() {
        val made = open()
        everyLook(made) { look, what ->
            show(made, LookGallery.Screen.LIST, ListTags.SEARCH)
            val bar = look.barOutline
            val search = look.searchOutline
            // The top dp of the bar and of the field, at their middle.
            val barTop = Offset(0.3f, 0f) to Offset(0.7f, dpOf(TAB_BAR_TAG, 1f))
            val searchTop = Offset(0.3f, 0f) to Offset(0.7f, dpOf(ListTags.SEARCH, 1f))
            if (bar == null) {
                assertFalse("$what: no line along the bar", compose.hasPixel(TAB_BAR_TAG, look.colors.outlineVariant, barTop.first, barTop.second))
            } else {
                compose.awaitHasPixel(TAB_BAR_TAG, bar, barTop.first, barTop.second, "$what: the bar's line")
            }
            if (search == null) {
                assertFalse("$what: no outline around the search field", compose.hasPixel(ListTags.SEARCH, look.colors.outlineVariant, searchTop.first, searchTop.second))
            } else {
                compose.awaitHasPixel(ListTags.SEARCH, search, searchTop.first, searchTop.second, "$what: the search field's outline")
                // Also at its left end, halfway down: an outline, not a line.
                compose.awaitHasPixel(ListTags.SEARCH, search, Offset(0f, 0.4f), Offset(dpOfWidth(ListTags.SEARCH, 1f), 0.6f), "$what: the outline's left end")
            }
            assertEquals("$what: only Emerald in light has the outlines", look === Looks.of(StyleChoice.EMERALD, dark = false), bar != null && search != null)
        }
    }

    /** [dp] as a fraction of the height of the node [tag]. */
    private fun dpOf(tag: String, dp: Float): Float = dp * compose.activity.resources.displayMetrics.density / compose.node(tag).size.height

    /** [dp] as a fraction of the width of the node [tag]. */
    private fun dpOfWidth(tag: String, dp: Float): Float = dp * compose.activity.resources.displayMetrics.density / compose.node(tag).size.width

    /** The colour on the screen [xDp], [yDp] from the top left corner of the node [tag]. */
    private fun pixelAtDp(tag: String, xDp: Float, yDp: Float): Int {
        compose.waitForIdle()
        val node = compose.node(tag)
        val density = compose.activity.resources.displayMetrics.density
        val x = node.positionOnScreen.x + xDp * density
        val y = node.positionOnScreen.y + yDp * density
        return screenshot().getPixel(x.roundToInt(), y.roundToInt())
    }

    /** Whether the status bar draws dark icons (for a light colour behind them) now. */
    private fun lightStatusBarIcons(): Boolean {
        var light = false
        compose.runOnIdle {
            val window = compose.activity.window
            light = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return light
    }

    private fun bounds(tag: String): Rect = compose.node(tag).boundsInRoot

    /** Whether [inner] lies within this rectangle (half a pixel of rounding allowed). */
    private fun Rect.holds(inner: Rect): Boolean =
        inner.left >= left - 0.5f && inner.top >= top - 0.5f && inner.right <= right + 0.5f && inner.bottom <= bottom + 0.5f

    private fun assertNoNode(tag: String, what: String) {
        assertTrue(what, compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** The logo [tag]: its tile at the top, clear of the shield, and the shield's colour inside it. */
    private fun assertLogo(tag: String, tile: Color, mark: Color, what: String) {
        compose.awaitPixel(tag, tile, "$what: the logo's tile", fx = 0.5f, fy = 0.08f)
        compose.awaitHasPixel(tag, mark, Offset(0.35f, 0.3f), Offset(0.65f, 0.6f), "$what: the shield on the tile")
    }

    private companion object {
        /** A phone held sideways, wider than high and lower than 600 dp, within a portrait screen's width. */
        val LANDSCAPE = 400f to 340f
    }
}
