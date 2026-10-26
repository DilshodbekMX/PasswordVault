package io.github.dilshodbekmx.passwordvault.ui.common

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * build-plan.md §12, w5-carry step 3 item 2, [device]: [FieldKeyboard] keeps the keyboard on a field
 * after rotations in touch mode, where on API 37 Gboard opened full screen in landscape for the new
 * window's empty editor and closed itself again after the screen's one request (job 4.2b: 17 of 17
 * rotations on the lock screen with the keyboard hidden afterwards; `TypeAgainRotationTest` failed
 * about every fifth run, its own retries stopping at the first sight of the keyboard).
 *
 * The device is put in touch mode first ([Instrumentation.setInTouchMode]), as a phone in the hand
 * is, and again before every rotation. A plain `ComponentActivity` is hosted as `MainActivity` hosts
 * the app (the window policy, `adjustResize`, the theme), with one secret field that asks for its
 * keyboard as `TypeAgainScreen` does; the display is really rotated (`UiAutomation.setRotation`),
 * four times. After each rotation the field has the focus and the keyboard, and the keyboard is
 * still shown after [FieldKeyboard.AGAIN_MS] and a half second more, when nothing asks any longer:
 * the keyboard came back and stayed. The keyboard is read from `dumpsys input_method`
 * (`mInputShown`) and, on API 30 and newer, from the window insets as well.
 */
@RunWith(AndroidJUnit4::class)
class FieldKeyboardTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val field = TextFieldState()
    private val current = AtomicReference<ComponentActivity?>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val host = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass != ComponentActivity::class.java) return
            val component = activity as ComponentActivity
            WindowPolicy.onCreate(component)
            @Suppress("DEPRECATION") // the manifest's windowSoftInputMode of MainActivity
            component.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            component.setContent { Screen() }
            current.set(component)
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before
    fun launch() {
        instrumentation.setInTouchMode(true) // first: a device out of touch mode focuses the field by itself
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        application.registerActivityLifecycleCallbacks(host)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
        application.unregisterActivityLifecycleCallbacks(host)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun inTouchModeTheKeyboardComesBackAfterEachRotationAndStays() {
        awaitKeyboardThatStays("when the screen appears")
        for ((round, rotation) in ROTATIONS.withIndex()) {
            rotateTo(rotation)
            awaitKeyboardThatStays("after rotation ${round + 1} (to ${if (rotation == UiAutomation.ROTATION_FREEZE_90) "landscape" else "portrait"})")
        }
    }

    /** The field as a screen of the app asks for its keyboard (`TypeAgainScreen`). */
    @androidx.compose.runtime.Composable
    private fun Screen() {
        PasswordVaultTheme(ThemeChoice.LIGHT) {
            WindowPolicy.Root {
                val focus = remember { FocusRequester() }
                val fieldKeyboard = rememberFieldKeyboard()
                var focused by remember { mutableStateOf(false) }
                LaunchedEffect(focus, fieldKeyboard) {
                    fieldKeyboard.awaitWindowFocus()
                    focus.requestFocus()
                    fieldKeyboard.show { focused }
                }
                Column(Modifier.padding(24.dp)) {
                    VaultSecureField(
                        state = field,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused }.testTag(FIELD),
                        label = "field",
                        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
                    )
                }
            }
        }
    }

    /** Rotates the display in touch mode and waits for the new instance of the activity, with the window focus. */
    private fun rotateTo(rotation: Int) {
        val before = current.get()
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.setRotation(rotation)
        compose.waitUntil(RECREATE_MS) { current.get().let { it != null && it !== before && onMain { it.hasWindowFocus() } } }
        assertNotSame(before, current.get())
        compose.waitForIdle()
    }

    /** The field has the focus and the keyboard, and the keyboard is still there once nothing asks for it any more. */
    private fun awaitKeyboardThatStays(moment: String) {
        val activity = current.get()!!
        compose.waitUntil(KEYBOARD_MS) { fieldFocused() && keyboardShown(activity) }
        // Past the time FieldKeyboard asks again: a keyboard that closed by itself would be gone by then.
        SystemClock.sleep(FieldKeyboard.AGAIN_MS + STAYS_EXTRA_MS)
        compose.waitForIdle()
        assertTrue("the field has the focus $moment", fieldFocused())
        assertTrue("the keyboard is shown and stays $moment: ${inputMethodState()}", keyboardShown(activity))
    }

    private fun fieldFocused(): Boolean =
        compose.onNodeWithTag(FIELD).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** `mInputShown=true` in `dumpsys input_method`, and on API 30+ the IME's window insets. */
    private fun keyboardShown(activity: Activity): Boolean {
        if (!inputMethodState().contains("mInputShown=true")) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return onMain { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    }

    private fun inputMethodState(): String = shell("dumpsys input_method").lineSequence()
        .filter { "mInputShown" in it || "mImeWindowVis" in it }
        .joinToString(" ") { it.trim() }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
    }

    private companion object {
        const val FIELD = "pv.test.field"

        /** Landscape and back, twice. */
        val ROTATIONS = listOf(
            UiAutomation.ROTATION_FREEZE_90,
            UiAutomation.ROTATION_FREEZE_0,
            UiAutomation.ROTATION_FREEZE_90,
            UiAutomation.ROTATION_FREEZE_0,
        )

        const val RECREATE_MS = 20_000L
        const val KEYBOARD_MS = 15_000L
        const val STAYS_EXTRA_MS = 500L
    }
}
