package io.github.dilshodbekmx.passwordvault.ui.transfer.`import`

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.BackupSharing
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickerLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportColumnsViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportMemory
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportPreview
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSourcesViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.StoreImportTasks
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What the import's JVM tests share: a test dispatcher as the main one; the app's fakes of the
 * settings, the documents and the vault (`APP/fake/`); the real `FileTaskStore` of build plan 5.1
 * over them, with a picker that records what it was asked and answers when the test says so
 * ([pick]); and the import's ViewModels in a store that [clear] empties, as Navigation 3 does when a
 * screen leaves the back stack or the app locks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ImportKit private constructor(val scope: TestScope, private val dispatcher: TestDispatcher, vault: Vault) {
    val clock = FakeClock(autoAdvance = false)
    val random = FakeRandom(7)
    val settings = FakeAppSettings()
    val fake = FakeDocumentIo()
    val documents = CountingDocuments(fake)
    val lock = MutableStateFlow<LockUiState>(LockUiState.Unlocked(emptyList(), null))
    val launcher = RecordingLauncher()
    val repository = FakeVaultRepository(vault) { clock.wallMs() }
    val memory = ImportMemory()

    /** The app's scope of the store: where it records a picker's answer. */
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The tests' collectors (not `backgroundScope`: `advanceUntilIdle` stops when only background work is left). */
    private val collectors = CoroutineScope(SupervisorJob() + dispatcher)
    private val shared: File = Files.createTempDirectory("pv-import-test").toFile()
    private var store = newStore()
    private val viewModels = ViewModelStore()
    private var made = 0

    /** The import's calls of the store, as the screens make them. */
    val tasks get() = StoreImportTasks(store)

    private fun newStore(): FileTaskStore =
        FileTaskStore(settings, NoSession, documents, lock, appScope, AppDispatchers.single(dispatcher), BackupSharing(shared)).also { it.attach(launcher) }

    /** A new process: a new store over the same settings, and no import memory. */
    fun newProcess(): ImportMemory {
        store = newStore()
        return ImportMemory()
    }

    /** The sources screen, watching the picker's answers as its screen does while shown. */
    fun sources(memory: ImportMemory = this.memory): ImportSourcesViewModel {
        val tasks = tasks
        val factory = viewModelFactory { initializer { ImportSourcesViewModel(tasks, memory) } }
        val vm = ViewModelProvider.create(viewModels, factory)["sources-${made++}", ImportSourcesViewModel::class]
        collectors.launch { vm.watchResults() }
        settle()
        return vm
    }

    /** The file's screen, started with the test's texts. */
    fun file(memory: ImportMemory = this.memory): ImportViewModel {
        val tasks = tasks
        val factory = viewModelFactory {
            initializer { ImportViewModel(tasks, documents, repository, random, clock, AppDispatchers.single(dispatcher), memory) }
        }
        val vm = ViewModelProvider.create(viewModels, factory)["file-${made++}", ImportViewModel::class]
        vm.start(TEXTS)
        settle()
        return vm
    }

    /** The column-mapping screen, started with the test's texts. */
    fun columns(memory: ImportMemory = this.memory): ImportColumnsViewModel {
        val tasks = tasks
        val factory = viewModelFactory {
            initializer { ImportColumnsViewModel(tasks, documents, repository, random, clock, AppDispatchers.single(dispatcher), memory) }
        }
        val vm = ViewModelProvider.create(viewModels, factory)["columns-${made++}", ImportColumnsViewModel::class]
        vm.start(TEXTS)
        settle()
        return vm
    }

    /**
     * The picker that [sources] opened answers with a file named [name] holding [bytes] (put in the
     * fake documents first), as the activity's launcher hands it to the store.
     */
    fun pick(sources: ImportSourcesViewModel, bytes: ByteArray, name: String = "Chrome Passwords.csv", uri: String = DOC): DocRef {
        sources.openPicker()
        settle()
        check(launcher.opened > 0) { "the picker did not open" }
        fake.files[uri] = bytes
        fake.names[uri] = name
        store.returned(FileTaskStore.Action.OPEN, uri)
        settle()
        return DocRef(uri)
    }

    /** The task recorded for a file chosen before this test's screens: as after a lock or in a new process. */
    fun recorded(bytes: ByteArray, name: String = "Chrome Passwords.csv", uri: String = DOC): DocRef {
        fake.files[uri] = bytes
        fake.names[uri] = name
        val task = FileTask(FileTask.Kind.IMPORT, 1, DocRef(uri))
        check(settings.update { it.fileTask = task })
        store = newStore()
        return DocRef(uri)
    }

    /** The open vault now. */
    fun vault(): Vault = (repository.state.value as VaultState.Unlocked).vault

    /** The preview [vm] shows; fails when it shows something else. */
    fun preview(vm: ImportViewModel): ImportPreview =
        (vm.ui.value.screen as? ImportScreen.Preview)?.preview ?: throw AssertionError("the screen shows ${vm.ui.value.screen}, not the preview")

    /** The vault locks, as `VaultPort.lock()` does; the screens' ViewModels go when the activity starts again. */
    fun lockVault() {
        repository.close()
        lock.value = LockUiState.Starting
        settle()
    }

    /** The vault opens again. */
    fun unlockVault() {
        repository.open()
        lock.value = LockUiState.Unlocked(emptyList(), null)
        settle()
    }

    /** Runs everything that is due. */
    fun settle() = scope.advanceUntilIdle()

    /** The screens go, as when they leave the back stack. */
    fun clear() {
        viewModels.clear()
        settle()
    }

    private fun close() {
        viewModels.clear()
        collectors.cancel()
        appScope.cancel()
        shared.deleteRecursively()
    }

    /** The fake documents, counting reads and deletes. */
    class CountingDocuments(private val fake: FakeDocumentIo) : DocumentIo by fake {
        var reads = 0
        val deleted = mutableListOf<String>()

        override suspend fun read(doc: DocRef, maxBytes: Int): ByteArray {
            reads++
            return fake.read(doc, maxBytes)
        }

        override fun delete(doc: DocRef): Boolean {
            val done = fake.delete(doc)
            if (done) deleted += doc.uri
            return done
        }
    }

    /** The activity's side of the picker: counts what it was asked to open. */
    class RecordingLauncher : PickerLauncher {
        var opened = 0
        var mimeTypes: List<String> = emptyList()

        override fun create(name: String, mime: String) = throw AssertionError("the import never saves a document")

        override fun open(mimeTypes: List<String>) {
            opened++
            this.mimeTypes = mimeTypes
        }

        override fun share(file: File, title: CharSequence) = throw AssertionError("the import never shares")
    }

    private object NoSession : Session {
        override fun beginExternal() = Unit

        override fun endExternal() = Unit

        override fun touched() = Unit

        override fun lockNow() = Unit
    }

    companion object {
        const val DOC = "content://com.android.providers.downloads.documents/document/77"
        const val OTHER_DOC = "content://com.android.providers.downloads.documents/document/78"

        /** 2026-10-08 12:00 UTC. */
        const val NOW_MS = 1_791_460_800_000L

        /** The texts an import stores, as the screen's resources give them in English. */
        val TEXTS = object : EditTexts {
            override fun untitled(nowMs: Long): String = "Untitled"

            override fun fromBackup(title: String): String = "$title (from backup)"

            override fun fromFile(title: String): String = "$title (from file)"
        }

        /** Runs [test] with a test dispatcher of its own as the main one, and an open vault [vault]. */
        fun run(vault: Vault = Vault(), test: suspend ImportKit.() -> Unit) {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val kit = ImportKit(this, dispatcher, vault)
                    kit.clock.setWall(NOW_MS)
                    try {
                        kit.test()
                    } finally {
                        kit.close()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

        /** An account of the vault, with [id]. */
        fun account(id: String, title: String, username: String, password: String, url: String) = Entry(
            id = id,
            title = title,
            username = username,
            password = password,
            urls = listOf(url),
            createdAt = NOW_MS - 86_400_000L,
            updatedAt = NOW_MS - 86_400_000L,
        )

        /** [text] as a file's bytes, UTF-8. */
        fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
    }
}
