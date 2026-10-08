package io.github.dilshodbekmx.passwordvault.ui.theme

import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSheet
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §12, 4.3b, [device]: a change of style reaches an open dialog and an open sheet without
 * a restart (docs/design/looks/README.md: the choice "applies at once, without a restart: to every
 * screen, dialog, sheet and the system bars").
 *
 * The app's root theme, `PasswordVaultTheme(settings)`, is drawn over [FakeAppSettings] with a
 * `VaultDialog` or a `VaultSheet` open over a screen. Each is a window of its own. The stored style
 * and "light or dark" are then changed, as Settings › Appearance changes them, and after each change
 * the test reads the screen's real pixels: the screen behind, the window's own colour (Material 3
 * draws a dialog on `surfaceContainerHigh` and a sheet on `surfaceContainerLow`), and a patch of the
 * primary colour inside the window. The style's details reach the window too ([LocalLook]). Nothing
 * is made again on the way: the activity is the same one, and the window's content was composed
 * once.
 */
@RunWith(AndroidJUnit4::class)
class StyleReachesWindowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings = FakeAppSettings()

    /** How many times the window's content entered the composition: 1 as long as the window stays open. */
    private val made = AtomicInteger()

    private enum class Window { NONE, DIALOG, SHEET }

    private var window by mutableStateOf(Window.NONE)

    @Composable
    private fun Probes() {
        remember { made.incrementAndGet() }
        val look = LocalLook.current
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // A patch of the primary colour, and an empty patch through which the window's own colour shows.
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary).testTag(PRIMARY))
            Box(Modifier.size(56.dp).testTag(CONTAINER).semantics { stateDescription = look.pinKeys.name })
        }
    }

    private fun show(first: Window) {
        settings.style.value = StyleChoice.BANK_BLUE
        settings.theme.value = ThemeChoice.LIGHT
        window = first
        compose.setContent {
            WindowPolicy.Root {
                PasswordVaultTheme(settings) {
                    Surface(Modifier.fillMaxSize().testTag(PAGE), color = MaterialTheme.colorScheme.background) {}
                    when (window) {
                        Window.NONE -> Unit
                        Window.DIALOG -> VaultDialog(
                            onDismissRequest = {},
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                            title = { Text("A dialog") },
                            text = { Probes() },
                        )
                        Window.SHEET -> VaultSheet(onDismissRequest = {}, skipPartiallyExpanded = true) { Probes() }
                    }
                }
            }
        }
        compose.onNodeWithTag(PRIMARY).assertIsDisplayed()
    }

    /** The looks to walk through, each a change of the one before it: the styles in light, then in dark, then the phone's. */
    private fun walk(): List<Pair<StyleChoice, ThemeChoice>> = buildList {
        add(StyleChoice.BANK_BLUE to ThemeChoice.LIGHT)
        add(StyleChoice.EMERALD to ThemeChoice.LIGHT)
        add(StyleChoice.NIGHT_VAULT to ThemeChoice.LIGHT)
        add(StyleChoice.NIGHT_VAULT to ThemeChoice.DARK)
        add(StyleChoice.EMERALD to ThemeChoice.DARK)
        add(StyleChoice.BANK_BLUE to ThemeChoice.DARK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(StyleChoice.PHONE_COLOURS to ThemeChoice.DARK)
            add(StyleChoice.PHONE_COLOURS to ThemeChoice.LIGHT)
        }
        add(StyleChoice.EMERALD to ThemeChoice.LIGHT)
    }

    private fun aChangeOfStyleReaches(open: Window) {
        show(open)
        val activity = compose.activity
        // The open window dims what is behind it. How much is measured once, on the first look's known
        // background, so that the screen behind can be read through it in every other look.
        val shade = shadeOver(Looks.of(StyleChoice.BANK_BLUE, dark = false).colors.background)
        assertTrue("an open ${open.name.lowercase()} dims the screen behind it: $shade", shade > 0.1 && shade < 0.9)
        for ((style, theme) in walk()) {
            // The stored choice changes, as Settings › Appearance changes it.
            compose.runOnIdle {
                settings.update {
                    it.style = style
                    it.theme = theme
                }
            }
            val look = Looks.shown(style, dark = theme == ThemeChoice.DARK, compose.activity)
            val c = look.colors
            val what = "$style $theme with an open ${open.name.lowercase()}"
            compose.awaitPixel(PAGE, shaded(c.background, shade), "$what: the screen behind, through the window's shade", fx = PAGE_X, fy = PAGE_Y)
            compose.awaitPixel(PRIMARY, c.primary, "$what: the primary colour in the window")
            val container = if (open == Window.DIALOG) c.surfaceContainerHigh else c.surfaceContainerLow
            compose.awaitPixel(CONTAINER, container, "$what: the window's own colour")
            assertEquals("$what: the style's details in the window", look.pinKeys.name, compose.node(CONTAINER).config[SemanticsProperties.StateDescription])
        }
        // Without a restart: the same activity, and the open window's content was never composed anew.
        assertSame(activity, compose.activity)
        assertEquals("the window stayed open while the style changed", 1, made.get())
        // With the window closed the screen has the last look's own background.
        compose.runOnIdle { window = Window.NONE }
        compose.awaitPixel(PAGE, Looks.of(StyleChoice.EMERALD, dark = false).colors.background, "the screen once the window is closed", fx = PAGE_X, fy = PAGE_Y)
    }

    /**
     * How strongly the open window shades the screen behind it, 0 to 1: a dialog's window dims what
     * is behind, a sheet draws a scrim. Both are black at one strength, measured here on [background],
     * the colour the screen has now.
     */
    private fun shadeOver(background: Color): Double {
        compose.waitForIdle()
        var shade = 0.0
        // The shade fades in with the window: wait until two readings agree.
        repeat(20) {
            val seen = compose.pixel(PAGE, PAGE_X, PAGE_Y)
            val next = 1.0 - (seen shr 8 and 0xFF) / (background.toArgb() shr 8 and 0xFF).toDouble()
            if (it > 0 && abs(next - shade) < 0.01) return next
            shade = next
            SystemClock.sleep(150)
        }
        return shade
    }

    /** [colour] under a black shade of the strength [shade]. */
    private fun shaded(colour: Color, shade: Double): Color {
        val argb = colour.toArgb()
        fun channel(shift: Int) = ((argb shr shift and 0xFF) * (1.0 - shade)).roundToInt()
        return Color(red = channel(16), green = channel(8), blue = channel(0))
    }

    @Test
    fun aChangeOfStyleReachesAnOpenDialog() = aChangeOfStyleReaches(Window.DIALOG)

    @Test
    fun aChangeOfStyleReachesAnOpenSheet() = aChangeOfStyleReaches(Window.SHEET)

    /** The three styles are told apart on the screen: no two of the six schemes share the colours read here. */
    @Test
    fun theSixSchemesDifferWhereThisTestLooks() {
        val seen = OWN_STYLES.flatMap { style -> listOf(false, true).map { dark -> Looks.of(style, dark).colors } }
            .map { Triple(it.background, it.primary, it.surfaceContainerHigh) }
        assertEquals(6, seen.toSet().size)
    }

    private companion object {
        const val PAGE = "probe.page"

        /** A point of the screen that no dialog and no sheet covers: the middle, below the status bar. */
        const val PAGE_X = 0.5f
        const val PAGE_Y = 0.12f
        const val PRIMARY = "probe.primary"
        const val CONTAINER = "probe.container"
    }
}
