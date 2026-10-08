package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * The fakes behind the screens of build plan 5.2 in a JVM test: the lock ([lock], a [FakeLockManager]
 * that opens [vault]), the vault ([repository], which records what the backup asked of it), the
 * documents ([documents]), the settings, a session and the process's real [FileTaskStore] over them,
 * whose picker and share sheet are [launcher]. A picker's answer is [answerSaveAs], [answerOpen] or
 * [closePicker].
 */
internal class BackupRig(val scope: TestScope, private val shared: File, vault: Vault = Vault(entries = ENTRIES)) {
    val clock = FakeClock(wallMs = NOW, elapsedMs = 1_000_000L, autoAdvance = false)
    val settings = FakeAppSettings()
    val session = CountingSession()
    val fakeDocuments = FakeDocumentIo()
    val documents = SeeingDocuments(fakeDocuments)
    val vault = FakeVaultRepository(vault, clock = { clock.wallMs() })
    val repository = RecordingRepository(this.vault)
    val fakeLock = FakeLockManager(initial = LockUiState.Unlocked(emptyList(), null), vault = this.vault)
    val lock = RecordingLock(fakeLock)
    val launcher = RecordingLauncher()
    val dispatchers = AppDispatchers.single(StandardTestDispatcher(scope.testScheduler))

    val store: FileTaskStore by lazy {
        FileTaskStore(settings, session, documents, fakeLock.state, scope.backgroundScope, dispatchers, BackupSharing(shared))
            .also { it.attach(launcher) }
    }

    val files: BackupFiles by lazy { BackupFiles(store, documents, dispatchers.io) }

    private val viewModels = ViewModelStore()

    /** How many ViewModels [make] made: each gets a key of its own, as each route's entry has a store of its own. */
    var made = 0

    /** A new ViewModel, made as the route makes it, cleared by [clear]. */
    inline fun <reified T : ViewModel> make(noinline create: () -> T): T {
        val factory = viewModelFactory { initializer { create() } }
        val vm = ViewModelProvider(store(), factory)["vm-${made++}", T::class.java]
        scope.runCurrent()
        return vm
    }

    /** The store of the ViewModels [make] made. */
    fun store(): ViewModelStore = viewModels

    /** The screens are left: every ViewModel is cleared. */
    fun clear() {
        viewModels.clear()
        scope.runCurrent()
    }

    /** "Save as" returned the new, empty document [uri]. */
    fun answerSaveAs(uri: String = DOC) {
        fakeDocuments.files[uri] = ByteArray(0)
        store.returned(FileTaskStore.Action.CREATE, uri)
        scope.runCurrent()
    }

    /** The picker for a restore returned the document [uri], which holds [bytes]. */
    fun answerOpen(bytes: ByteArray, uri: String = FILE) {
        fakeDocuments.files[uri] = bytes
        store.returned(FileTaskStore.Action.OPEN, uri)
        scope.runCurrent()
    }

    /** The picker of [action] was closed without a document. */
    fun closePicker(action: FileTaskStore.Action) {
        store.returned(action, null)
        scope.runCurrent()
    }

    /** A proof of a right master password whose bytes are [bytes]. */
    fun proof(bytes: ByteArray = PASSWORD_BYTES): MasterProof = MasterProof.forFakes(bytes, fakeLock.vaultId)

    /** The answer of the next `openBackup`: the backup opened as [opened]. */
    fun opens(opened: Vault = Vault(entries = ENTRIES), createdMs: Long = MADE_MS) {
        vault.backupResults.add(BackupResult.Opened(opened, createdMs))
    }

    /** A session that counts the external screens. */
    class CountingSession : Session {
        var begun = 0
        var ended = 0

        override fun beginExternal() {
            begun++
        }

        override fun endExternal() {
            ended++
        }

        override fun touched() = Unit

        override fun lockNow() = Unit
    }

    /** The picker and the share sheet, as calls. */
    class RecordingLauncher : PickerLauncher {
        val calls = mutableListOf<String>()
        var shared: File? = null
        var sharedTitle: CharSequence? = null

        override fun create(name: String, mime: String) {
            calls += "create $name $mime"
        }

        override fun open(mimeTypes: List<String>) {
            calls += "open ${mimeTypes.joinToString()}"
        }

        override fun share(file: File, title: CharSequence) {
            calls += "share ${file.name}"
            shared = file
            sharedTitle = title
        }
    }

    /** [FakeDocumentIo] whose reads can be scripted: [readAs] replaces what a read of a document returns, [unreadable] makes it fail. */
    class SeeingDocuments(private val fake: FakeDocumentIo) : DocumentIo by fake {
        val readAs = mutableMapOf<String, ByteArray>()
        val unreadable = mutableSetOf<String>()
        val reads = mutableListOf<String>()

        override suspend fun read(doc: DocRef, maxBytes: Int): ByteArray {
            reads += doc.uri
            if (doc.uri in unreadable) throw IOException("cannot be read")
            val scripted = readAs[doc.uri] ?: return fake.read(doc, maxBytes)
            return scripted.copyOf(minOf(scripted.size, maxBytes + 1))
        }
    }

    /** [FakeVaultRepository] that records what the backup asked: the proofs it sealed with and the password bytes it opened with. */
    class RecordingRepository(private val fake: FakeVaultRepository) : VaultRepository by fake {
        val sealedWith = mutableListOf<MasterProof>()
        val openedWith = mutableListOf<Pair<ByteArray, ByteArray>>()

        /** What the next `sealBackup` throws instead of sealing. */
        var sealThrows: Exception? = null

        override suspend fun sealBackup(proof: MasterProof): ByteArray {
            sealedWith += proof
            sealThrows?.let {
                sealThrows = null
                throw it
            }
            proof.useBytes { } // as the repository does: a dropped proof seals nothing
            return fake.sealBackup(proof)
        }

        override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult {
            openedWith += bytes.copyOf() to passwordBytes.copyOf()
            return fake.openBackup(bytes, passwordBytes)
        }
    }

    /** [FakeLockManager] that records what was typed for a vault put aside, and with which vault. */
    class RecordingLock(private val fake: FakeLockManager) : LockManager by fake {
        val asideCalls = mutableListOf<String>()

        override suspend fun openAside(vaultId: String, chars: CharArray): AsideResult {
            asideCalls += "open $vaultId ${String(chars)}"
            return fake.openAside(vaultId, chars)
        }

        override suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult {
            asideCalls += "delete $vaultId ${String(chars)}"
            return fake.deleteAside(vaultId, chars)
        }
    }

    companion object {
        /** 2026-10-08 10:00 UTC. */
        const val NOW = 1_791_453_600_000L

        /** 2026-10-03 10:00 UTC: when the backup of the restore tests was made. */
        const val MADE_MS = 1_791_021_600_000L

        const val DOC = "content://com.android.providers.downloads.documents/document/77"
        const val FILE = "content://com.android.providers.downloads.documents/document/78"
        const val DRIVE_DOC = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D9"

        /** The bytes a backup of the fake vault has (`FakeVaultRepository.backupBytes`). */
        val BACKUP_BYTES = "PVAULT01 a backup".toByteArray()

        /** The master password typed in these tests, and its bytes. */
        const val PASSWORD = "correct horse battery"
        val PASSWORD_BYTES = PASSWORD.toByteArray()

        val ENTRIES = listOf(
            entry("00000000-0000-4000-8000-00000000000a", "Gmail"),
            entry("00000000-0000-4000-8000-00000000000b", "Bank"),
            entry("00000000-0000-4000-8000-00000000000c", "Old", deletedAt = NOW - 86_400_000L),
        )

        fun entry(id: String, title: String, password: String = "pw-$title", deletedAt: Long? = null) =
            Entry(id = id, title = title, password = password, createdAt = NOW - 1_000_000L, updatedAt = NOW - 1_000_000L, deletedAt = deletedAt)
    }
}
