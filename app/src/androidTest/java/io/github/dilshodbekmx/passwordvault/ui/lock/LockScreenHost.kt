package io.github.dilshodbekmx.passwordvault.ui.lock

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The master-password screen as the lock route shows it, for the device tests: its [LockViewModel]
 * from the activity's ViewModel store (so that a recreation keeps it, as the route's entry does),
 * over [lock], the phone's clock and a scope of its own; inside the window policy and the theme.
 * [onViewModel] receives the ViewModel at every composition.
 */
@Composable
fun MasterPasswordHost(lock: LockManager, onViewModel: (LockViewModel) -> Unit = {}) {
    WindowPolicy.Root {
        PasswordVaultTheme(ThemeChoice.LIGHT) {
            val vm = viewModel { LockViewModel(lock, AndroidClock, TEST_SCOPE) }
            onViewModel(vm)
            val ui by vm.masterPassword.collectAsStateWithLifecycle()
            ui?.let { MasterPasswordScreen(vm, it) }
        }
    }
}

/**
 * Every lock screen as the lock route shows it (`LockScreens`, build plan 4.2), for the device tests:
 * its [LockViewModel] from the activity's ViewModel store over [lock], the phone's clock and a scope
 * of its own, inside the window policy, the theme and the background the app's scaffold draws, with
 * the test tags as resource ids, as under the route. [testBuild] is the test build's line;
 * [onRestore] is "Restore from a backup file" opening the restore screens.
 */
@Composable
fun LockRouteHost(
    lock: LockManager,
    testBuild: Boolean = true,
    onRestore: () -> Unit = {},
    onViewModel: (LockViewModel) -> Unit = {},
) {
    WindowPolicy.Root {
        PasswordVaultTheme(ThemeChoice.LIGHT) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val vm = viewModel { LockViewModel(lock, AndroidClock, TEST_SCOPE, testBuild) }
                onViewModel(vm)
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    LockScreens(vm, onRestore)
                }
            }
        }
    }
}

/**
 * [fake] with a pad that counts the keys as the lock does (lock.md §5.8): while the lock shows the
 * pad, a digit adds one to the number of digits (at most six), ⌫ takes one off and a long press
 * clears them. Which digits were pressed stays in [FakeLockManager.sent] only, for the test to check;
 * the screen gets their number.
 */
class PadLock(val fake: FakeLockManager) : LockManager by fake {
    override fun send(intent: LockIntent) {
        fake.send(intent)
        val pad = (fake.state.value as? LockUiState.Locked)?.screen as? LockScreen.Pad ?: return
        val digits = when (intent) {
            is LockIntent.Digit -> minOf(pad.digits + 1, PIN_LENGTH)
            LockIntent.DeleteDigit -> maxOf(pad.digits - 1, 0)
            LockIntent.ClearDigits -> 0
            else -> return
        }
        fake.state.value = LockUiState.Locked(pad.copy(digits = digits))
    }
}

/**
 * Screenshots for the user (build plan 4.2's notes): with the instrumentation argument `pvShots`
 * (`-Pandroid.testInstrumentationRunnerArguments.pvShots=1`), [take] saves the whole screen, dialogs
 * and sheets included, as `/data/local/tmp/shots-4A/<name>.png` through the shell's `screencap`
 * (debug builds have no FLAG_SECURE); without it, it does nothing, so the integration checks write
 * no files.
 */
object LockShots {
    const val DIR = "/data/local/tmp/shots-4A"

    fun take(name: String) {
        if (InstrumentationRegistry.getArguments().getString("pvShots") == null) return
        SystemClock.sleep(700) // the sheet's and the dialog's own windows settle
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/$name-api${android.os.Build.VERSION.SDK_INT}.png")
    }
}

/** The scope the checks run in, as the app's own scope would be. */
private val TEST_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** The master-password screen with nothing to say: the screen the gate of build plan 3.2 shows. */
val PLAIN_SCREEN = LockScreen.MasterPassword(
    reason = null,
    canUsePin = false,
    practice = null,
    waitUntilElapsedMs = null,
    error = null,
    pendingReadyInMs = null,
    opening = false,
    aside = null,
)

/** The output of a shell command, run as the shell user (which may read `dumpsys`). */
fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Whether the on-screen keyboard is shown now: `mInputShown` of `dumpsys input_method`, the value
 * the window lab read on Android 17 (docs/lab/window-input.md), which Android 7 and 9 print too.
 * The emulators show the on-screen keyboard beside their hardware keyboard
 * (`show_ime_with_hard_keyboard`, tools/emu/README.md).
 */
fun keyboardShown(): Boolean {
    val dump = shell("dumpsys input_method")
    val shown = Regex("""\bmInputShown=(true|false)""").findAll(dump).map { it.groupValues[1] }.toList()
    check(shown.isNotEmpty()) { "dumpsys input_method has no mInputShown:\n" + dump.take(4_000) }
    return shown.first() == "true"
}
