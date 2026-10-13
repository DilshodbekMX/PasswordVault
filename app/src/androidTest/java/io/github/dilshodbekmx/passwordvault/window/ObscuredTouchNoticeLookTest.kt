package io.github.dilshodbekmx.passwordvault.window

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.Looks
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * build-plan.md §12, w5-carry step 3 item 5, [device]: the notice of an obscured touch (ux.md §3
 * "Overlays") is drawn in the style and the light or dark the user chose, not always in Bank blue.
 * The content is built as `AppRoot` builds it, the theme of the settings around `WindowPolicy.Root`;
 * for each of the three styles in light and in dark, an obscured tap shows the notice, whose colour
 * (the one most of its pixels have; its text is a small part) must be the inverse surface of that
 * style's scheme. From API 26, where a node can be captured as an image.
 */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class ObscuredTouchNoticeLookTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val settings = FakeAppSettings()

    @Before
    fun host() {
        compose.runOnUiThread {
            ObscuredTouches.reset()
            WindowPolicy.onCreate(compose.activity)
        }
        compose.setContent {
            PasswordVaultTheme(settings) {
                WindowPolicy.Root {
                    Box(Modifier.fillMaxSize()) {
                        Button(onClick = {}, modifier = Modifier.align(Alignment.Center).testTag(TARGET)) { Text("Target") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun forgetTheSession() {
        compose.runOnUiThread { ObscuredTouches.reset() }
    }

    @Test
    fun theNoticeHasTheChosenStyleInLightAndDark() {
        for (style in listOf(StyleChoice.BANK_BLUE, StyleChoice.EMERALD, StyleChoice.NIGHT_VAULT)) {
            for (dark in listOf(false, true)) {
                compose.runOnUiThread {
                    settings.style.value = style
                    settings.theme.value = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT
                    ObscuredTouches.reset() // once per session: a new one for each look
                }
                compose.waitForIdle()
                val target = compose.onNodeWithTag(TARGET).fetchSemanticsNode().boundsInWindow.center
                tap(target, obscured = true)
                compose.waitForIdle()
                val expected = Looks.of(style, dark).colors.inverseSurface
                assertEquals("$style, dark $dark", hex(expected), hex(mostCommonColour()))
                tap(target, obscured = false) // an ordinary tap hides it
                compose.waitForIdle()
                compose.onNodeWithTag(NOTICE_TAG).assertDoesNotExist()
            }
        }
    }

    /** The colour most pixels of the notice have. */
    private fun mostCommonColour(): Color {
        val pixels = compose.onNodeWithTag(NOTICE_TAG).captureToImage().toPixelMap()
        val counts = HashMap<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) counts.merge(pixels[x, y].toArgb(), 1, Int::plus)
        return Color(counts.maxBy { it.value }.key)
    }

    private fun hex(colour: Color) = "#%08X".format(colour.toArgb())

    /** A tap at [at] (window coordinates), handed to the decor view as the input system would (as in [ObscuredTouchNoticeTest]). */
    private fun tap(at: Offset, obscured: Boolean) {
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val down = SystemClock.uptimeMillis()
            val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_OBSCURED else 0
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 40)) {
                val properties = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
                val coords = MotionEvent.PointerCoords().apply {
                    x = at.x
                    y = at.y
                    pressure = 1f
                    size = 1f
                }
                val event = MotionEvent.obtain(
                    down, time, action, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
                )
                decor.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    private companion object {
        const val TARGET = "target"
    }
}
