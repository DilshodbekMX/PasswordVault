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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ux.md §3 "Overlays": a tap that arrives through another app's window
 * (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`) is dropped by the decor view's filter, and the notice
 * shows once per session; an ordinary tap works and hides it; it goes by itself after its time.
 * The taps go through the window's real path: the decor view hands them to the window's callback
 * (the watcher of `WindowPolicy.onCreate`), which hands them back to the decor view's filter.
 */
@RunWith(AndroidJUnit4::class)
class ObscuredTouchNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clicks = AtomicInteger()

    @Before
    fun aNewSession() {
        compose.runOnUiThread {
            ObscuredTouches.reset()
            WindowPolicy.onCreate(compose.activity)
        }
        compose.setContent {
            WindowPolicy.Root {
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { clicks.incrementAndGet() }, modifier = Modifier.align(Alignment.Center).testTag(TARGET)) {
                        Text("Target")
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
    fun anObscuredTapIsDroppedAndTheNoticeShowsOncePerSession() {
        val notice = compose.activity.getString(R.string.common_obscured_touch)
        val target = compose.onNodeWithTag(TARGET).fetchSemanticsNode().boundsInWindow.center

        tap(target, obscured = true)
        compose.waitForIdle()
        assertEquals("an obscured tap must not click", 0, clicks.get())
        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText(notice).assertIsDisplayed()

        tap(target, obscured = false)
        compose.waitForIdle()
        assertEquals("an ordinary tap clicks", 1, clicks.get())
        compose.onNodeWithTag(NOTICE_TAG).assertDoesNotExist()

        tap(target, obscured = true)
        compose.waitForIdle()
        assertEquals(1, clicks.get())
        compose.onNodeWithTag(NOTICE_TAG).assertDoesNotExist()
    }

    @Test
    fun theNoticeGoesByItself() {
        val target = compose.onNodeWithTag(TARGET).fetchSemanticsNode().boundsInWindow.center
        tap(target, obscured = true)
        compose.waitForIdle()
        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        compose.onNodeWithTag(NOTICE_TAG).assertDoesNotExist()
        assertEquals(0, clicks.get())
    }

    /** A tap at [at] (window coordinates), handed to the decor view as the input system would. */
    private fun tap(at: Offset, obscured: Boolean) {
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val down = SystemClock.uptimeMillis()
            val flags = if (obscured) MotionEvent.FLAG_WINDOW_IS_OBSCURED else 0
            for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 40)) {
                val event = touch(down, time, action, at, flags)
                decor.dispatchTouchEvent(event)
                event.recycle()
            }
        }
    }

    private fun touch(downTime: Long, eventTime: Long, action: Int, at: Offset, flags: Int): MotionEvent {
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
        return MotionEvent.obtain(
            downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
    }

    private companion object {
        const val TARGET = "target"
    }
}
