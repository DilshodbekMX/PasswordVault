package io.github.dilshodbekmx.passwordvault.files

import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [JVM] What [FileTaskStore] does with a task's write and its end, against the fakes of the
 * settings, the documents and the lock (architecture.md §3.5, "The file tasks"; ux.md §4 item 3).
 * The store's other behaviour, with its picker and share sheet, is in the device tests
 * (`FileTaskStoreTest`, `PickerProcessTest`). A new process is a new store over the same settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTaskStoreJvmTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val settings = FakeAppSettings()
    private val session = CountingSession()
    private val fake = FakeDocumentIo()
    private val documents = SeeingDocuments(fake, settings)
    private val lock = MutableStateFlow<LockUiState>(LockUiState.Unlocked(emptyList(), null))
    private val launcher = RecordingLauncher()
    private val shared by lazy { temp.newFolder("shared") }

    private fun TestScope.newStore(): FileTaskStore {
        val store = FileTaskStore(
            settings, session, documents, lock, backgroundScope, AppDispatchers.single(StandardTestDispatcher(testScheduler)), BackupSharing(shared),
        )
        store.attach(launcher)
        return store
    }

    /** Opens "Save as" for [task], and the picker answers with [DOC], a new empty document. */
    private suspend fun TestScope.picked(store: FileTaskStore, task: FileTask) {
        assertTrue(store.create(task, "passwords-2026-10-08.csv", DocumentTypes.CSV))
        fake.files[DOC] = ByteArray(0)
        store.returned(FileTaskStore.Action.CREATE, DOC)
        runCurrent()
        assertEquals(task.copy(doc = DocRef(DOC), written = false), store.task.value)
    }

    @Test
    fun aWriteIsRecordedBeforeItBegins() = runTest {
        val store = newStore()
        val export = FileTask(FileTask.Kind.EXPORT, 2, null)
        picked(store, export)
        assertTrue(store.write(FileTask.Kind.EXPORT, PLAIN))
        val written = export.copy(doc = DocRef(DOC), written = true)
        assertEquals("recorded when the write came", written, documents.recordedAtWrite)
        assertEquals(written, settings.fileTask.value)
        assertEquals(written, store.task.value)
        assertArrayEquals(PLAIN, fake.files[DOC])

        // A second write of the same task records nothing new.
        val writes = settings.writes
        assertTrue(store.write(FileTask.Kind.EXPORT, PLAIN))
        assertEquals(writes, settings.writes)

        store.finish(FileTask.Kind.EXPORT)
        assertNull(settings.fileTask.value)
        assertArrayEquals("a finished task's document stays", PLAIN, fake.files[DOC])
    }

    @Test
    fun aWriteThatANewProcessCutOffIsRemovedWhenItsTaskIsGivenUp() = runTest {
        // The export was written, then the app locked or its process ended before finish(): the new
        // process's store gives the task up, and the unprotected file is not left behind unnoticed.
        val first = newStore()
        picked(first, FileTask(FileTask.Kind.EXPORT, 2, null))
        assertTrue(first.write(FileTask.Kind.EXPORT, PLAIN))

        val again = newStore()
        assertEquals(true, again.task.value?.written)
        assertEquals(Cleanup.Removed, again.abandon(FileTask.Kind.EXPORT))
        assertFalse("the file is gone", DOC in fake.files)
        assertNull(settings.fileTask.value)
    }

    @Test
    fun aWriteThatFailedHalfWayIsRemovedWithoutTheCallerSayingSo() = runTest {
        val store = newStore()
        picked(store, FileTask(FileTask.Kind.BACKUP, 3, null))
        fake.writeResults.add(false)
        assertFalse(store.write(FileTask.Kind.BACKUP, PLAIN))
        fake.files[DOC] = PLAIN.copyOf(3) // what the provider kept of it
        assertEquals(Cleanup.Removed, store.abandon(FileTask.Kind.BACKUP))
        assertFalse(DOC in fake.files)
    }

    @Test
    fun aFileTheUserChoseToReplaceIsKeptWhenNothingWasWritten() = runTest {
        val store = newStore()
        picked(store, FileTask(FileTask.Kind.EXPORT, 2, null))
        fake.files[DOC] = PLAIN // an existing file of the user's, chosen in "Save as"
        assertEquals(Cleanup.Kept, store.abandon(FileTask.Kind.EXPORT))
        assertArrayEquals(PLAIN, fake.files[DOC])
    }

    @Test
    fun withoutADocumentNothingIsWrittenOrRecorded() = runTest {
        val store = newStore()
        assertFalse("no task", store.write(FileTask.Kind.BACKUP, PLAIN))
        assertTrue(store.create(FileTask(FileTask.Kind.BACKUP, 3, null), "b.pvault", DocumentTypes.BACKUP))
        assertFalse("the picker has not answered", store.write(FileTask.Kind.BACKUP, PLAIN))
        assertFalse("another kind's task", store.write(FileTask.Kind.EXPORT, PLAIN))
        assertEquals(FileTask(FileTask.Kind.BACKUP, 3, null), settings.fileTask.value)
        assertNull(documents.recordedAtWrite)
        try {
            store.write(FileTask.Kind.IMPORT, PLAIN)
            throw AssertionError("an import writes nothing")
        } catch (expected: IllegalArgumentException) {
            // as create() and open() do for the wrong kind
        }
    }

    @Test
    fun aNewTaskStartsWithNoWrite() = runTest {
        val store = newStore()
        assertTrue(store.create(FileTask(FileTask.Kind.EXPORT, 1, DocRef(DOC), written = true), "p.csv", DocumentTypes.CSV))
        assertEquals(FileTask(FileTask.Kind.EXPORT, 1, null), settings.fileTask.value)
    }

    /** The fake documents, and what the settings held when a write came. */
    private class SeeingDocuments(private val fake: FakeDocumentIo, private val settings: AppSettings) : DocumentIo by fake {
        var recordedAtWrite: FileTask? = null

        override suspend fun write(doc: DocRef, bytes: ByteArray): Boolean {
            recordedAtWrite = settings.fileTask.value
            return fake.write(doc, bytes)
        }
    }

    private class CountingSession : Session {
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

    private class RecordingLauncher : PickerLauncher {
        val calls = mutableListOf<String>()
        var shared: File? = null

        override fun create(name: String, mime: String) {
            calls += "create $name"
        }

        override fun open(mimeTypes: List<String>) {
            calls += "open"
        }

        override fun share(file: File, title: CharSequence) {
            calls += "share ${file.name}"
            shared = file
        }
    }

    private companion object {
        const val DOC = "content://com.android.providers.downloads.documents/document/41"
        val PLAIN = "name,url,username,password,note\nBank,https://bank.uz,ali,S3cret!,\n".toByteArray()
    }
}
