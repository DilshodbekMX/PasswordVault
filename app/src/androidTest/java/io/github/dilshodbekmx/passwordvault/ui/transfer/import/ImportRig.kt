package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportColumnsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportMemory
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.StoreImportTasks
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The import's screens on a device with the app's fakes (`APP/fake/`) and the real `FileTaskStore`
 * of build plan 5.1 over them: the settings, the documents and the vault are in memory, the picker
 * records what it was asked and answers when the test says so ([answer]), and [nav] records the
 * screens' moves. The ViewModels live in a store that [close] clears.
 */
class ImportRig(vault: Vault = Vault()) {
    val settings = FakeAppSettings()
    val files = FakeDocumentIo()
    val repository = FakeVaultRepository(vault)
    val clock = FakeClock(wallMs = System.currentTimeMillis())
    val lock = MutableStateFlow<LockUiState>(LockUiState.Unlocked(emptyList(), null))
    val launcher = Launcher()
    val nav = RecordingNav()
    internal val memory = ImportMemory()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shared = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "import-test-shared")
    private val viewModels = ViewModelStore()
    private var made = 0
    private var store = newStore()

    private fun newStore(): FileTaskStore = FileTaskStore(settings, NoSession, files, lock, scope, AppDispatchers.Default, BackupSharing(shared))

    /** Attaches the picker; on the main thread, as the activity does. */
    fun attach() = store.attach(launcher)

    /** A file chosen before the screens opened: the task recorded with its document, as after a lock. */
    fun recorded(bytes: ByteArray, name: String = "Chrome Passwords.csv", uri: String = DOC): DocRef {
        files.files[uri] = bytes
        files.names[uri] = name
        check(settings.update { it.fileTask = FileTask(FileTask.Kind.IMPORT, 1, DocRef(uri)) })
        store = newStore()
        return DocRef(uri)
    }

    /** The picker answers with [uri] holding [bytes] (null: closed without a file). On the main thread. */
    fun answer(uri: String?, bytes: ByteArray? = null) {
        if (uri != null && bytes != null) {
            files.files[uri] = bytes
            files.names[uri] = "Chrome Passwords.csv"
        }
        store.returned(FileTaskStore.Action.OPEN, uri)
    }

    internal fun sources(): ImportSourcesViewModel = make { ImportSourcesViewModel(StoreImportTasks(store), memory) }

    internal fun file(): ImportViewModel =
        make { ImportViewModel(StoreImportTasks(store), files, repository, FakeRandom(3), clock, AppDispatchers.Default, memory) }

    internal fun columns(): ImportColumnsViewModel =
        make { ImportColumnsViewModel(StoreImportTasks(store), files, repository, FakeRandom(4), clock, AppDispatchers.Default, memory) }

    private inline fun <reified T : ViewModel> make(crossinline create: () -> T): T {
        val factory = viewModelFactory { initializer { create() } }
        return ViewModelProvider.create(viewModels, factory)["vm-${made++}", T::class]
    }

    fun close() {
        viewModels.clear()
        scope.cancel()
        shared.deleteRecursively()
    }

    /** The activity's side of the picker. */
    class Launcher : PickerLauncher {
        @Volatile var opened = 0

        override fun create(name: String, mime: String) = throw AssertionError("the import never saves a document")

        override fun open(mimeTypes: List<String>) {
            opened++
        }

        override fun share(file: File, title: CharSequence) = throw AssertionError("the import never shares")
    }

    /** A [Navigator] that records the moves and makes none. */
    class RecordingNav : Navigator {
        val moves = mutableListOf<String>()

        override fun go(route: Route): Boolean = moves.add("go $route")

        override fun back(): Boolean = moves.add("back")

        override fun replace(route: Route): Boolean = moves.add("replace $route")

        override fun tab(root: Route): Boolean = moves.add("tab $root")
    }

    private object NoSession : Session {
        override fun beginExternal() = Unit

        override fun endExternal() = Unit

        override fun touched() = Unit

        override fun lockNow() = Unit
    }

    companion object {
        const val DOC = "content://com.android.providers.downloads.documents/document/1201"
        private const val DAY_MS = 86_400_000L

        /** An account of the vault. */
        fun account(id: String, title: String, username: String, password: String, url: String) = Entry(
            id = id,
            title = title,
            username = username,
            password = password,
            urls = listOf(url),
            createdAt = System.currentTimeMillis() - DAY_MS,
            updatedAt = System.currentTimeMillis() - DAY_MS,
        )

        /** A Chrome export the user might have: Latin and Cyrillic Uzbek, Russian, a name Chrome made up. */
        const val CHROME_CSV = "name,url,username,password,note\n" +
            "accounts.google.com,https://accounts.google.com,aziz.karimov@gmail.com,Pw-Gmail-7731,\n" +
            "Kapitalbank,https://kapitalbank.uz,aziz.karimov,Pw-Bank-5521,\n" +
            "Mening hisobim,https://my.gov.uz,AA1234567,Pw-MyGov-001,\n" +
            "Почта,https://mail.yandex.ru,азиз,Пароль-Яндекс-91,\n" +
            "Uzum Market,https://uzum.uz,+998901234567,Pw-Uzum-4410,\n" +
            "Shop,https://shop.uz,buyer,Pw-Half-One,Pw-Half-Two,\n"

        /** Every password of [CHROME_CSV], and the halves of the one an unquoted comma split. */
        val CHROME_PASSWORDS = listOf("Pw-Gmail-7731", "Pw-Bank-5521", "Pw-MyGov-001", "Пароль-Яндекс-91", "Pw-Uzum-4410", "Pw-Half-One", "Pw-Half-Two")
    }
}

/** The window policy and the theme around [content], as the app's root gives them. */
@Composable
fun ImportTestTheme(theme: ThemeChoice = ThemeChoice.LIGHT, content: @Composable () -> Unit) {
    WindowPolicy.Root {
        PasswordVaultTheme(theme) { content() }
    }
}

/** Every text and label on the screen and in its windows, as an accessibility service reads them. */
fun SemanticsNodeInteractionsProvider.shownTexts(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotBlank()) out += it.text }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { if (it.text.isNotBlank()) out += it.text }
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
 * Screenshots of the import's screens for the user (the round's note): the shell user's `screencap`
 * writes them to [DIR] on the device, where the uninstall after the run leaves them, and the
 * package's agent pulls them into `/home/asus/pvwork/shots-5/`. Only on Android 17 (API 37), the
 * user's version; debug builds do not set FLAG_SECURE, so the pictures are not black.
 */
object ImportShots {
    const val DIR = "/data/local/tmp/pv-shots-5.4"

    /**
     * Saves the screen as [name].png in [DIR], once the composition is idle and the window has
     * settled; on Android 17 only, unless [everyApi] (the pseudo-locale pictures, for a person to look
     * for cut text).
     */
    fun take(compose: ComposeTestRule, name: String, everyApi: Boolean = false) {
        if (Build.VERSION.SDK_INT < SHOTS_API && !everyApi) return
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        shell("mkdir -p $DIR")
        shell("screencap -p $DIR/$name.png")
    }

    private const val SHOTS_API = 37
    private const val SETTLE_MS = 700L
}
