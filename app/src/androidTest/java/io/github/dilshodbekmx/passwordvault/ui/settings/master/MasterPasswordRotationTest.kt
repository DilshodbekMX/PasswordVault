package io.github.dilshodbekmx.passwordvault.ui.settings.master

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeStage
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterTags
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewStage
import io.github.dilshodbekmx.passwordvault.ui.settings.StepNavigator
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Build plan 5.5, [device]: the keyboard is back after a rotation on every master-password field of
 * the two routes (architecture.md §5.4, [V-P7]: Android 17 no longer brings it back by itself, and on
 * Android 7 the new window focuses the field before it has the window focus): the current master
 * password of "Change master password", setup's "Type your master password again" inside the change,
 * and the new master password of "Finish now". The typed text stays (it is the ViewModel's), and the
 * steps "Write it down on paper" and "Type your master password again" keep the screen on in a change
 * (lock.md §10.1).
 *
 * Each screen runs in a plain `ComponentActivity` whose content is set again in every new instance,
 * with its ViewModel kept by the activity (as the route's entry keeps it in the app) over the fake
 * lock; the display is really rotated (`UiAutomation.setRotation`), so the activity is recreated as on
 * a phone. The keyboard is seen through `dumpsys input_method` (`mInputShown`), and on API 30 and newer
 * also through the window insets. As in `TypeAgainRotationTest`, the device is kept in touch mode, and
 * the second rotation comes after the keyboard was closed by hand, so a keyboard that comes back is the
 * screen's doing.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = AtomicReference<ComponentActivity?>()
    private val models = AtomicReference<ViewModel?>()
    private var content: @Composable (ComponentActivity) -> Unit = {}
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val host = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass != ComponentActivity::class.java) return
            val component = activity as ComponentActivity
            component.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { content(component) } }
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
    fun setUp() {
        instrumentation.setInTouchMode(true)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        application.registerActivityLifecycleCallbacks(host)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        application.unregisterActivityLifecycleCallbacks(host)
        scope.cancel()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun launch(screen: @Composable (ComponentActivity) -> Unit) {
        content = screen
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    private fun changeModel(lock: FakeLockManager): ChangeMasterPasswordViewModel = ChangeMasterPasswordViewModel(
        lock = lock,
        repository = FakeVaultRepository(Vault()),
        clock = AndroidClock,
        random = FakeRandom(seed = 11L),
        dispatchers = AppDispatchers.Default,
        work = scope,
        offerBackup = {},
        deleteBackup = { NoteDeleted.DELETED },
    )

    @Test
    fun theCurrentMasterPassword_hasTheKeyboardAgainAfterARotation() {
        val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
        launch { activity ->
            val vm = viewModel(viewModelStoreOwner = activity, key = "pv.test.change") { changeModel(lock) }
            models.set(vm)
            ChangeMasterPasswordScreen(vm, StepNavigator())
        }
        rotationKeepsTheKeyboard(MasterTags.CURRENT_FIELD, "Toshkent") { (models.get() as ChangeMasterPasswordViewModel).current.field.text.toString() }
    }

    @Test
    fun typeItAgain_inAChange_keepsTheScreenOn_andHasTheKeyboardAgainAfterARotation() {
        val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
        lock.status.value = lock.status.value.copy(pin = PinStatus.On)
        launch { activity ->
            val vm = viewModel(viewModelStoreOwner = activity, key = "pv.test.change") { changeModel(lock) }
            models.set(vm)
            ChangeMasterPasswordScreen(vm, StepNavigator())
        }
        awaitFieldWithKeyboard(MasterTags.CURRENT_FIELD, "on step 1")
        val vm = models.get() as ChangeMasterPasswordViewModel
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("the current one")
        instrumentation.runOnMainSync { vm.submitCurrent() }
        compose.waitUntil(WAIT_MS) { vm.ui.value.stage == ChangeStage.CHOOSE && vm.choice.state.value.passphrase != null }
        instrumentation.runOnMainSync {
            vm.choice.useTheseWords()
            vm.chosen()
        }
        compose.waitUntil(WAIT_MS) { vm.ui.value.stage == ChangeStage.WRITE_DOWN }
        compose.waitForIdle()
        assertTrue("\"Write it down on paper\" keeps the screen on in a change (lock.md §10.1)", screenKeptOn())
        instrumentation.runOnMainSync { vm.wroteItDown() }
        rotationKeepsTheKeyboard(SetupTags.AGAIN_FIELD, "vowel") { vm.choice.again.text.toString() }
        assertTrue("\"Type your master password again\" keeps the screen on in a change", screenKeptOn())
        assertEquals(ChangeStage.TYPE_AGAIN, vm.ui.value.stage)
    }

    @Test
    fun finishNow_hasTheKeyboardAgainAfterARotation() {
        val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
        val ready = PendingMasterPassword(requestedAtMs = 0L, by = Shortcut.PIN, creditedMs = DAY_MS, remainingMs = 0L, ready = true)
        lock.status.value = lock.status.value.copy(pin = PinStatus.On, pending = ready)
        launch { activity ->
            val vm = viewModel(viewModelStoreOwner = activity, key = "pv.test.new") {
                NewMasterPasswordViewModel(lock, AndroidClock, FakeRandom(seed = 12L), AppDispatchers.Default, scope, offerBackup = {})
            }
            models.set(vm)
            NewMasterPasswordScreen(vm, StepNavigator())
        }
        assertEquals(NewStage.FINISH, (models.get() as NewMasterPasswordViewModel).ui.value.stage)
        rotationKeepsTheKeyboard(MasterTags.FINISH_FIELD, "Samarqand") { (models.get() as NewMasterPasswordViewModel).finishField.text.toString() }
    }

    /**
     * The field [tag] has the focus and the keyboard; [typed] is typed into it; after a rotation the
     * same ViewModel ([text] reads its field) holds it, with the keyboard back; after the keyboard
     * is closed by hand and the display is rotated back, the keyboard is back again.
     */
    private fun rotationKeepsTheKeyboard(tag: String, typed: String, text: () -> String) {
        awaitFieldWithKeyboard(tag, "when the step appears")
        compose.onNodeWithTag(tag).performTextInput(typed)
        val model = models.get()

        rotateTo(UiAutomation.ROTATION_FREEZE_90)
        awaitFieldWithKeyboard(tag, "after a rotation to landscape")
        assertSame("the activity keeps the view model", model, models.get())
        assertEquals("the typed text is kept (the field's state is in the view model)", typed, onMain(text))

        val activity = current.get()!!
        instrumentation.runOnMainSync {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(KEYBOARD_MS) { !keyboardShown(activity) }

        rotateTo(UiAutomation.ROTATION_FREEZE_0)
        awaitFieldWithKeyboard(tag, "after a rotation back to portrait, with the keyboard closed before it")
        assertEquals(typed, onMain(text))
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

    private fun awaitFieldWithKeyboard(tag: String, moment: String) {
        compose.waitUntil(KEYBOARD_MS) { current.get() != null }
        val activity = current.get()!!
        compose.waitUntil(KEYBOARD_MS) { fieldFocused(tag) && keyboardShown(activity) }
        assertTrue("the field $tag has the focus $moment", fieldFocused(tag))
        assertTrue("the keyboard is shown $moment: ${inputMethodState()}", keyboardShown(activity))
    }

    private fun fieldFocused(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    } catch (e: AssertionError) {
        false // not composed yet
    }

    /** Whether the content view of the activity shown now keeps the screen on (`View.keepScreenOn`). */
    private fun screenKeptOn(): Boolean = onMain {
        val root = current.get()!!.window.decorView
        fun anyKeepsOn(view: View): Boolean {
            if (view.keepScreenOn) return true
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (anyKeepsOn(view.getChildAt(i))) return true
            return false
        }
        anyKeepsOn(root)
    }

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

        const val WAIT_MS = 10_000L
        const val DAY_MS = 86_400_000L
    }
}
