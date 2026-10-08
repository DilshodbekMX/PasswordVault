package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupFiles
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldPasswordsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreViewModel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The screens of build plan 5.2 over the fakes of `APP/fake/` on a device: the lock ([lock], a
 * [FakeLockManager] that opens [vault]), the documents, the settings, and a real [FileTaskStore]
 * whose picker and share sheet are [launcher]. [nav] records where the screens asked to go.
 */
class BackupScreensRig(vault: Vault = Vault(entries = ENTRIES), state: LockUiState = LockUiState.Unlocked(emptyList(), null)) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    val settings = FakeAppSettings().apply { oneTime.value = setOf(OneTime.BACKUP_EXPLAINED) }
    val documents = FakeDocumentIo()
    val vault = FakeVaultRepository(vault).apply { backupBytes = BACKUP_BYTES }
    val lock = FakeLockManager(initial = state, vault = this.vault)
    val launcher = RecordingLauncher()
    val nav = RecordingNav()
    private val dispatchers = AppDispatchers.Default

    val store: FileTaskStore = FileTaskStore(
        settings, NoSession, documents, lock.state, scope, dispatchers, BackupSharing(File(context.cacheDir, "pv-backup-test-shared")),
    ).also { store -> onMain { store.attach(launcher) } }

    val files = BackupFiles(store, documents, dispatchers.io)

    fun backup(offered: MasterProof? = null) = onMain { BackupViewModel(lock, vault, files, settings, AndroidClock, dispatchers, offered) }

    fun restore() = onMain { RestoreViewModel(lock, vault, files) }

    fun old() = onMain { OldPasswordsViewModel(lock, AndroidClock) }

    /** The next `openBackup` opens a backup of [opened], made at [createdMs]. */
    fun opens(opened: Vault = Vault(entries = ENTRIES), createdMs: Long = MADE_MS) {
        vault.backupResults.add(BackupResult.Opened(opened, createdMs))
    }

    /** "Save as" returned the new, empty document [uri]. */
    fun answerSaveAs(uri: String = DOC) {
        documents.files[uri] = ByteArray(0)
        onMain { store.returned(FileTaskStore.Action.CREATE, uri) }
    }

    /** The picker of a restore returned [uri], holding [bytes]. */
    fun answerOpen(bytes: ByteArray = BACKUP_BYTES, uri: String = FILE) {
        documents.files[uri] = bytes
        onMain { store.returned(FileTaskStore.Action.OPEN, uri) }
    }

    /** Puts two vaults aside, for the old-passwords screen. */
    fun putAside() {
        lock.status.value = lock.status.value.copy(aside = listOf(ASIDE_OLDER, ASIDE_NEWER))
    }

    override fun close() {
        scope.cancel()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** The picker and the share sheet, as calls. */
    class RecordingLauncher : PickerLauncher {
        val calls: MutableList<String> = CopyOnWriteArrayList()

        override fun create(name: String, mime: String) {
            calls += "create $name"
        }

        override fun open(mimeTypes: List<String>) {
            calls += "open"
        }

        override fun share(file: File, title: CharSequence) {
            calls += "share ${file.name}"
        }
    }

    /** Where the screens asked to go. */
    class RecordingNav : Navigator {
        val calls: MutableList<String> = CopyOnWriteArrayList()

        override fun go(route: Route): Boolean {
            calls += "go ${route.name}"
            return true
        }

        override fun back(): Boolean {
            calls += "back"
            return true
        }

        override fun replace(route: Route): Boolean {
            calls += "replace ${route.name}"
            return true
        }

        override fun tab(root: Route): Boolean {
            calls += "tab ${root.name}"
            return true
        }
    }

    private object NoSession : Session {
        override fun beginExternal() = Unit

        override fun endExternal() = Unit

        override fun touched() = Unit

        override fun lockNow() = Unit
    }

    companion object {
        const val DOC = "content://com.android.providers.downloads.documents/document/77"
        const val FILE = "content://com.android.providers.downloads.documents/document/78"
        const val PASSWORD = "correct horse battery"
        const val NOW = 1_791_453_600_000L
        const val MADE_MS = 1_791_021_600_000L
        val BACKUP_BYTES = "PVAULT01 a backup".toByteArray()

        val ENTRIES: List<Entry> = listOf("Gmail", "Bank", "Telegram", "Payme").mapIndexed { i, title ->
            Entry(id = "00000000-0000-4000-8000-00000000000$i", title = title, password = "pw-$title", createdAt = NOW, updatedAt = NOW)
        }

        val ASIDE_OLDER = AsideVault("11111111-1111-4111-8111-111111111111", NOW - 30 * 86_400_000L, GenerationInfo(4, "k1", NOW - 31 * 86_400_000L))
        val ASIDE_NEWER = AsideVault("22222222-2222-4222-8222-222222222222", NOW - 2 * 86_400_000L, null)
    }
}
