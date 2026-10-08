package io.github.dilshodbekmx.passwordvault.ui.settings

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupViewModel
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The Settings and PIN screens over the fakes of `APP/fake/`, on a device: an unlocked
 * [FakeLockManager] (with the proof of a master password just typed when [withProof]), settings as
 * setup leaves them (`writeDefaults`), an empty vault, and a [StepNavigator]. [close] stops the
 * fakes' coroutines.
 */
class SettingsRig(withProof: Boolean = true) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = FakeVaultRepository(Vault())
    val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
    val settings = FakeAppSettings().also { it.writeDefaults() }
    val files = FakeDocumentIo()
    val session = FakeSession(scope, lock, FakeSecretClipboard(), repository)
    val nav = StepNavigator()

    init {
        if (withProof) lock.state.value = LockUiState.Unlocked(emptyList(), lock.proof())
    }

    fun settingsViewModel() = SettingsViewModel(lock, settings, session, repository, files, AndroidClock, AppDispatchers.Default, scope)

    fun pinSetupViewModel(canAuthenticate: Int = 0) =
        PinSetupViewModel(lock, settings, AndroidClock, { canAuthenticate }, AppDispatchers.Default, scope)

    fun changePinViewModel() = ChangePinViewModel(lock, AndroidClock, scope)

    fun close() = scope.cancel()
}

/** A [Navigator] that records each move the screens asked for, as text ("go ConfirmPin", "back"). */
class StepNavigator : Navigator {
    val steps: MutableList<String> = CopyOnWriteArrayList()

    override fun go(route: Route): Boolean = steps.add("go ${route.name}")

    override fun back(): Boolean = steps.add("back")

    override fun replace(route: Route): Boolean = steps.add("replace ${route.name}")

    override fun tab(root: Route): Boolean = steps.add("tab ${root.name}")
}

/** The window policy and the theme around [content], as the app's root gives them. */
@Composable
fun SettingsTestTheme(theme: ThemeChoice = ThemeChoice.LIGHT, content: @Composable () -> Unit) {
    WindowPolicy.Root {
        PasswordVaultTheme(theme) { content() }
    }
}

/** Every text and label on the screen and in its dialogs, as an accessibility service reads them. */
fun ComposeTestRule.shownTexts(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotBlank()) out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { if (it.isNotBlank()) out += it }
        node.children.forEach(::walk)
    }
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
    return out
}

/** The output of a shell command, run as the shell user. */
fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Screenshots of the screens for the user to look at (build plan 4.3's note): the shell user's
 * `screencap` writes them to [DIR] on the device, where AGP's uninstall after the run leaves them, and
 * the package's agent pulls them. Only on Android 17 (API 37), the user's version; debug builds do
 * not set FLAG_SECURE, so the pictures are not black.
 */
object Shots {
    const val DIR = "/data/local/tmp/pv-shots-4A"

    /** Saves the screen as [name].png in [DIR], once the composition is idle and the window has settled. */
    fun take(compose: ComposeTestRule, name: String) {
        if (Build.VERSION.SDK_INT < SHOTS_API) return
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/$name.png")
    }

    private const val SHOTS_API = 37
    private const val SETTLE_MS = 700L
}
