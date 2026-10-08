package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportViewModel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The export screen over the fakes of `APP/fake/`, on a device: a vault with accounts, the real
 * `FileTaskStore` with a [picker] that records what it was asked to open (the system's picker is
 * 5.1's to test), and a [nav] that records where the screen asked to go. Everything runs on the main
 * thread, as the fakes want. [close] stops the work.
 */
class ExportRig(context: Context, vault: Vault = vault(), task: FileTask? = null) {
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dispatchers = AppDispatchers.single(Dispatchers.Main.immediate)
    val repository = FakeVaultRepository(vault)
    val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null), repository)
    val documents = FakeDocumentIo()

    /** The settings, holding [task] (an export cut short, with its document) when one is given. */
    val settings = FakeAppSettings().also { settings ->
        if (task != null) {
            settings.fileTask.value = task
            task.doc?.let { doc ->
                documents.files[doc.uri] = ByteArray(0)
                documents.names[doc.uri] = NAME
            }
        }
    }
    val picker = RecordingPicker()
    val nav = RecordingNav()
    val store = FileTaskStore(
        settings = settings,
        session = FakeSession(work, lock, FakeSecretClipboard(), repository),
        documents = documents,
        lockState = lock.state,
        scope = work,
        dispatchers = dispatchers,
        sharing = BackupSharing(File(context.cacheDir, "pv-export-test-shared")),
    )

    init {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.attach(picker) }
    }

    /** The screen's ViewModel, as the app makes it but over the fakes. */
    fun viewModel(oldAndroid: Boolean = false) = ExportViewModel(
        repository = repository,
        lock = lock,
        store = store,
        documents = documents,
        clock = AndroidClock,
        dispatchers = dispatchers,
        work = work,
        generator = "PasswordVault 1.0",
        oldAndroid = oldAndroid,
    )

    /** The picker returns a new, empty document at [uri], named [name] by its place. */
    fun pick(uri: String = DOC, name: String = NAME) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            documents.files[uri] = ByteArray(0)
            documents.names[uri] = name
            store.returned(FileTaskStore.Action.CREATE, uri)
        }
    }

    fun close() {
        work.cancel()
    }

    /** "Save as", as the activity would open it: recorded, never shown. */
    class RecordingPicker : PickerLauncher {
        val creates: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

        override fun create(name: String, mime: String) {
            creates += name to mime
        }

        override fun open(mimeTypes: List<String>) = Unit

        override fun share(file: File, title: CharSequence) = Unit
    }

    companion object {
        const val DOC = "content://com.android.providers.downloads.documents/document/41"
        const val NAME = "passwords-2026-10-06.csv"
        const val MASTER = "Toshkent-2026-qalam"
        const val PASSWORD_GMAIL = "Gm41l-s3cret!"
        const val DAY_MS = 86_400_000L

        /** Accounts in the four kinds the CSV card counts: complete, without a website, without a password. */
        fun vault(now: Long = System.currentTimeMillis()) = Vault(
            entries = listOf(
                Entry(
                    id = "11111111-1111-4111-8111-111111111111",
                    title = "Gmail",
                    username = "aziz@gmail.com",
                    password = PASSWORD_GMAIL,
                    urls = listOf("https://mail.google.com"),
                    fields = listOf(CustomField("PIN code", "4321", secret = true)),
                    createdAt = now - 9 * DAY_MS,
                    updatedAt = now - 2 * DAY_MS,
                ),
                Entry(
                    id = "22222222-2222-4222-8222-222222222222",
                    title = "Ипотека банк",
                    username = "+998901234567",
                    password = "=1+2",
                    createdAt = now - 9 * DAY_MS,
                    updatedAt = now - 9 * DAY_MS,
                ),
                Entry(
                    id = "33333333-3333-4333-8333-333333333333",
                    title = "Uy Wi-Fi",
                    urls = listOf("https://router.local"),
                    createdAt = now - 9 * DAY_MS,
                    updatedAt = now - 9 * DAY_MS,
                ),
            ),
        )
    }
}

/** A [Navigator] that records where the screen asked to go, and allows it. */
class RecordingNav : Navigator {
    val opened: MutableList<Route> = CopyOnWriteArrayList()
    private val back = AtomicInteger()

    /** How many times the screen went back. */
    val backs: Int get() = back.get()

    override fun go(route: Route): Boolean = opened.add(route)

    override fun back(): Boolean {
        back.incrementAndGet()
        return true
    }

    override fun replace(route: Route): Boolean = opened.add(route)

    override fun tab(root: Route): Boolean = false
}

/** Every text, content description and state description on the screen, in both trees: what a person or a service reads. */
fun SemanticsNodeInteractionsProvider.everyText(): List<String> {
    val texts = mutableListOf<String>()
    fun walk(node: SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { texts += it }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { texts += it }
        for (child in node.children) walk(child)
    }
    for (unmerged in listOf(true, false)) {
        for (root in onAllNodes(isRoot(), useUnmergedTree = unmerged).fetchSemanticsNodes()) walk(root)
    }
    return texts
}

/**
 * Pictures of the export's screens: the shell user's `screencap` writes them to [dir] on the device,
 * where the uninstall after the run leaves them; the package's agent pulls them, those of [USER] into
 * /home/asus/pvwork/shots-5/ for the user (debug build: the window is not secure).
 */
class ExportShots(val dir: String) {
    /** Empties [dir]. */
    fun start() {
        shell("rm -rf $dir")
        shell("mkdir -p $dir")
        shell("chmod 777 $dir")
    }

    /** Saves the screen as [name].png once the composition is idle and the window has settled. */
    fun take(compose: ComposeTestRule, name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
        shell("screencap -p $dir/$name.png")
    }

    /** The names of the pictures in [dir]. */
    fun taken(): List<String> = shell("ls $dir").lines().map { it.trim() }.filter { it.endsWith(".png") }

    companion object {
        /** The pictures for the user. */
        val USER = ExportShots("/data/local/tmp/pv-shots-5.3")

        /** The walk in the pseudo-locale [tag]. */
        fun pseudo(tag: String) = ExportShots("/data/local/tmp/pv-shots-5.3-pseudo/$tag")

        private const val SETTLE_MS = 600L

        /** The output of a shell command, run as the shell user. */
        fun shell(command: String): String {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
        }
    }
}
