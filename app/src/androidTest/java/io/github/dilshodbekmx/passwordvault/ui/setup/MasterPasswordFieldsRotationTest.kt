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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.FieldKeyboard
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupPasswordField
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTags
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 8.1 ([device]; the open problem of section 12, R4, built by 6.2 without a test of its
 * own): the two master-password fields that asked for the keyboard once only, the one of "Set a PIN"
 * and "Change PIN" ([MasterPasswordInput]) and the backup's ([BackupPasswordField]), have it again
 * after each rotation, as `FieldKeyboard` makes the other master-password fields do (architecture.md
 * §5.4): Android 17 no longer brings the keyboard back by itself, and in touch mode Gboard may close
 * it again after a rotation (build plan §12, job 4.2b). The harness is [TypeAgainRotationTest]'s: the
 * field's state outlives the activity, the display is really rotated, and the keyboard is read from
 * `dumpsys input_method` and, on API 30 and newer, the window insets. Two rotations: with the keyboard
 * open, then after it was closed by hand.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordFieldsRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val current = AtomicReference<ComponentActivity?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ask = MasterPasswordAsk(FakeLockManager(), AndroidClock, MpPurpose.SET_PIN, scope, scope)
    private val backupField = TextFieldState()
    private var screen: @Composable () -> Unit = {}
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val host = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass != ComponentActivity::class.java) return
            val component = activity as ComponentActivity
            component.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { Column(Modifier.padding(16.dp)) { screen() } } }
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
    fun touchMode() {
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        application.registerActivityLifecycleCallbacks(host)
    }

    @After
    fun close() {
        if (::scenario.isInitialized) scenario.close()
        application.unregisterActivityLifecycleCallbacks(host)
        scope.cancel()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun setAPinsMasterPassword_hasTheKeyboardAgainAfterEachRotation() {
        screen = { MasterPasswordInput(ask, onNext = {}, focusFirst = true) }
        rotationsKeepTheKeyboard(PinTags.MP_FIELD) { ask.field.text.length }
    }

    @Test
    fun theBackupsMasterPassword_hasTheKeyboardAgainAfterEachRotation() {
        screen = { BackupPasswordField(backupField, label = "Master password", enabled = true, isError = false, onSubmit = {}) }
        rotationsKeepTheKeyboard(BackupTags.FIELD) { backupField.text.length }
    }

    private fun rotationsKeepTheKeyboard(tag: String, typed: () -> Int) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        awaitFieldWithKeyboard(tag, "when the field appears")
        compose.onNodeWithTag(tag).performTextInput("vowel-shiny")

        rotateTo(UiAutomation.ROTATION_FREEZE_90)
        awaitFieldWithKeyboard(tag, "after a rotation to landscape")
        assertEquals("what was typed is kept", 11, onMain(typed))

        // Closed by hand once the field has stopped asking (FieldKeyboard asks again for about a second).
        SystemClock.sleep(FieldKeyboard.AGAIN_MS + 300)
        val activity = current.get()!!
        instrumentation.runOnMainSync {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(KEYBOARD_MS) { !keyboardShown(activity) }

        rotateTo(UiAutomation.ROTATION_FREEZE_0)
        awaitFieldWithKeyboard(tag, "after a rotation back to portrait, with the keyboard closed before it")
        assertEquals(11, onMain(typed))
    }

    private fun rotateTo(rotation: Int) {
        val before = current.get()
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.setRotation(rotation)
        compose.waitUntil(RECREATE_MS) { current.get().let { it != null && it !== before && onMain { it.hasWindowFocus() } } }
        assertNotSame(before, current.get())
        compose.waitForIdle()
    }

    private fun awaitFieldWithKeyboard(tag: String, moment: String) {
        val activity = current.get()!!
        compose.waitUntil(KEYBOARD_MS) { focused(tag) && keyboardShown(activity) }
        assertTrue("the field has the focus $moment", focused(tag))
        assertTrue("the keyboard is shown $moment: ${inputMethodState()}", keyboardShown(activity))
    }

    private fun focused(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

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
        const val RECREATE_MS = 20_000L
        const val KEYBOARD_MS = 15_000L
    }
}
