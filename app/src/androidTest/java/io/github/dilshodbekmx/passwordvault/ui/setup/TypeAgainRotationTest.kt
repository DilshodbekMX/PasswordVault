package io.github.dilshodbekmx.passwordvault.ui.setup

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.common.FieldKeyboard
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.3, [device]: after a rotation on "Type your master password again" the field has the
 * keyboard again [V-P7] (ux.md §5.5, architecture.md §5.4). Android 17 no longer restores the
 * keyboard after a rotation, and on Android 7 the new window focuses the field before it has the
 * window focus, so the screen asks for both itself (`TypeAgainScreen`).
 *
 * The screen and its [SetupViewModel] (kept by the activity, as `SetupNav.kt` keeps it) run in a
 * plain `ComponentActivity` whose content is set again in every new instance, with the fake lock;
 * the display is really rotated (`UiAutomation.setRotation`), so the activity is recreated as on a
 * phone. The keyboard is seen through `dumpsys input_method` (`mInputShown`), which every API level
 * prints, and on API 30 and newer also through the window insets.
 *
 * Two rotations: the first with the keyboard open, the second after the keyboard was closed by hand
 * (which also shows that the check sees a closed keyboard), so the keyboard that comes back is the
 * screen's doing, not the system's. The device is kept in touch mode, as a phone in the hand is: out
 * of it the window focuses the first focusable view by itself (checked: on API 37 the keyboard then
 * came back even with a screen that asked for nothing; in touch mode it does not).
 */
@RunWith(AndroidJUnit4::class)
class TypeAgainRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val lock = FakeLockManager()
    private val settings = FakeAppSettings()
    private val current = AtomicReference<ComponentActivity?>()
    private val model = AtomicReference<SetupViewModel?>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val host = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass != ComponentActivity::class.java) return
            val component = activity as ComponentActivity
            component.setContent { Screen(component) }
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
        // In touch mode, as with a phone in the hand. Out of it (the emulators have a hardware keyboard,
        // and a test sends no touch) the window gives the first focusable view the focus by itself,
        // and the keyboard would come with it whatever the screen does.
        instrumentation.setInTouchMode(true)
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
    fun afterARotationTheFieldHasTheKeyboardAgain() {
        // The screen opens with the focus in the field and the keyboard (ux.md §5.5).
        awaitFieldWithKeyboard("when the screen appears")
        compose.onNodeWithTag(SetupTags.AGAIN_FIELD).performTextInput("vowel-shiny")
        val setup = model.get()!!

        // A rotation with the keyboard open.
        rotateTo(UiAutomation.ROTATION_FREEZE_90)
        awaitFieldWithKeyboard("after a rotation to landscape")
        assertSame("the activity keeps the view model", setup, model.get())
        assertEquals("the typed text is kept (the field's state is in the view model)", "vowel-shiny", fieldText())

        // The keyboard closed by hand, once the screen has stopped asking for it (FieldKeyboard asks again for
        // about a second after a rotation); the check sees it closed.
        SystemClock.sleep(FieldKeyboard.AGAIN_MS + 300)
        val activity = current.get()!!
        instrumentation.runOnMainSync {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(KEYBOARD_MS) { !keyboardShown(activity) }

        // A rotation with the keyboard closed: the screen asks for it again.
        rotateTo(UiAutomation.ROTATION_FREEZE_0)
        awaitFieldWithKeyboard("after a rotation back to portrait, with the keyboard closed before it")
        assertEquals("vowel-shiny", fieldText())
    }

    /** The screen as `SetupNav.kt` shows it, with the view model kept by [activity]. */
    @Composable
    private fun Screen(activity: ComponentActivity) {
        PasswordVaultTheme(ThemeChoice.LIGHT) {
            val setup = viewModel(viewModelStoreOwner = activity, key = "pv.setup") {
                SetupViewModel(lock, settings, FakeRandom(), AppDispatchers.Default)
            }
            model.set(setup)
            val state by setup.state.collectAsState()
            TypeAgainScreen(state = state, field = setup.again, onNext = setup::submitAgain, onShowAgain = {})
        }
    }

    /** Rotates the display and waits for the new instance of the activity. */
    private fun rotateTo(rotation: Int) {
        val before = current.get()
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.setRotation(rotation)
        compose.waitUntil(RECREATE_MS) { current.get().let { it != null && it !== before && onMain { it.hasWindowFocus() } } }
        assertNotSame(before, current.get())
        compose.waitForIdle()
    }

    private fun awaitFieldWithKeyboard(moment: String) {
        val activity = current.get()!!
        compose.waitUntil(KEYBOARD_MS) { fieldFocused() && keyboardShown(activity) }
        assertTrue("the field has the focus $moment", fieldFocused())
        assertTrue("the keyboard is shown $moment: ${inputMethodState()}", keyboardShown(activity))
    }

    private fun fieldFocused(): Boolean =
        compose.onNodeWithTag(SetupTags.AGAIN_FIELD).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun fieldText(): String = onMain { model.get()!!.again.text.toString() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The keyboard is shown: `mInputShown=true` in `dumpsys input_method`, and on API 30+ the IME's window insets. */
    private fun keyboardShown(activity: Activity): Boolean {
        if (!inputMethodState().contains("mInputShown=true")) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return onMain { ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    }

    /** The lines of `dumpsys input_method` that say whether the keyboard is shown. */
    private fun inputMethodState(): String = shell("dumpsys input_method").lineSequence()
        .filter { "mInputShown" in it || "mImeWindowVis" in it }
        .joinToString(" ") { it.trim() }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
    }

    private companion object {
        /** A rotation recreates the activity; an old emulator may take a few seconds. */
        const val RECREATE_MS = 20_000L

        /** The keyboard's window may take a while to appear on an emulator under load. */
        const val KEYBOARD_MS = 15_000L
    }
}
