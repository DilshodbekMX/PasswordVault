package io.github.dilshodbekmx.passwordvault.ui.edit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.platform.SystemRandom
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The add and edit screen in the test activity (`androidx.activity.ComponentActivity`, from
 * ui-test-manifest), set up as `MainActivity` sets up its own: the window policy at creation, then
 * the content inside `WindowPolicy.Root` and the app's theme. Every activity that a recreation makes
 * (a rotation) gets the same content in its `onCreate`, and the [EditViewModel], kept in the
 * activity's ViewModel store, carries the form across the recreation as the route's store does in
 * the app. The vault is a [FakeVaultRepository]; [nav] records where the screen goes.
 */
class EditHost(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    vault: Vault = Vault(),
    private val entryId: String? = null,
) : Application.ActivityLifecycleCallbacks {
    val repository = FakeVaultRepository(vault)
    val settings = FakeAppSettings()
    val nav = RecordingNavigator()

    /** The ViewModel of the form, once the screen is shown. */
    @Volatile
    var vm: EditViewModel? = null
        private set

    private val resumed = CopyOnWriteArrayList<Activity>()
    private val application: Application get() = rule.activity.application

    /** Shows the form in the rule's activity and watches for the activities that recreations make. */
    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        rule.runOnUiThread { WindowPolicy.onCreate(rule.activity) }
        rule.setContent { Content() }
        rule.waitForIdle()
    }

    /** Stops watching; call it at the end of the test. */
    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
    }

    /**
     * Turns the screen to [orientation] (an `ActivityInfo.SCREEN_ORIENTATION_…`) through the
     * activity's requested orientation, as turning the phone does, and waits until a new activity
     * is in front with the form. Returns it.
     */
    fun rotate(orientation: Int): Activity {
        val before = rule.activity
        val count = resumed.size
        rule.runOnUiThread { before.requestedOrientation = orientation }
        val deadline = SystemClock.uptimeMillis() + ROTATION_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val now = resumed.drop(count).lastOrNull { it !== before && !it.isFinishing }
            if (now != null) {
                rule.waitForIdle()
                return now
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("no new activity in front ${ROTATION_MS} ms after asking for orientation $orientation")
    }

    @Composable
    private fun Content() {
        WindowPolicy.Root {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                val form = viewModel {
                    EditViewModel(entryId, repository, settings, SystemRandom(), AndroidClock, AppDispatchers.Default)
                }
                vm = form
                EditScreen(form, nav)
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A recreation of the test activity: the same content, as MainActivity sets its own in onCreate.
        if (activity is ComponentActivity && savedInstanceState != null) {
            WindowPolicy.onCreate(activity)
            activity.setContent { Content() }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        resumed += activity
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val ROTATION_MS = 15_000L
    }
}

/** A [Navigator] that only records what the screen asked for, and allows it. */
class RecordingNavigator : Navigator {
    val calls = CopyOnWriteArrayList<String>()

    override fun go(route: Route): Boolean = true.also { calls += "go $route" }

    override fun back(): Boolean = true.also { calls += "back" }

    override fun replace(route: Route): Boolean = true.also { calls += "replace $route" }

    override fun tab(root: Route): Boolean = true.also { calls += "tab $root" }
}

/**
 * The on-screen keyboard as the input method service reports it: `mInputShown` in
 * `dumpsys input_method` (Android 7, 9 and 17 write it the same way). Read through the
 * instrumentation's shell, which every connected test has.
 */
object Keyboard {
    private val SHOWN = Regex("""\bmInputShown=(true|false)\b""")

    /** Whether the keyboard is shown now; fails when the dump has no such line. */
    fun shown(): Boolean {
        val dump = shell("dumpsys input_method")
        val match = SHOWN.find(dump) ?: throw AssertionError("dumpsys input_method has no mInputShown line")
        return match.groupValues[1] == "true"
    }

    /** Waits up to [timeoutMs] for the keyboard to be [shown]; true when it was. */
    fun waitFor(shown: Boolean, timeoutMs: Long = 10_000L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (shown() == shown) return true
            if (SystemClock.uptimeMillis() > deadline) return false
            SystemClock.sleep(100)
        }
    }

    private fun shell(command: String): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
