package io.github.dilshodbekmx.passwordvault.ui.lock

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
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
