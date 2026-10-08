package io.github.dilshodbekmx.passwordvault.files

import android.content.ActivityNotFoundException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1: what [FileTaskStore] does with a task, against fakes of the settings, the session,
 * the documents and the lock, and a launcher that records what it was asked (ux.md §4 "Unfinished
 * file tasks", lock.md §10.3). The task is recorded before the picker opens and the grace begins
 * first; a returned document is kept and recorded before it is handed out; a result that came while
 * the app was locked waits for the unlock and is handed out again after every unlock until the task
 * is finished; a restore's is not held back; a closed picker clears the record; nothing opens without
 * an activity, without a record, or without a picker app; a document no task waits for is removed
 * only when the picker created it and it is empty. The same flows with the real activity, picker
 * results and lock are in [PickerProcessTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FileTaskStoreTest {
    private val settings = FakeAppSettings()
    private val session = CountingSession()
    private val documents = FakeDocumentIo()
    private val lock = MutableStateFlow<LockUiState>(UNLOCKED)
    private val sharedDir = File(DocumentsRig.app.cacheDir, "pvtest-shared-" + UUID.randomUUID())
    private val launcher = RecordingLauncher(settings, session)

    @After
    fun removeSharedDir() {
        sharedDir.deleteRecursively()
    }

    // The store runs its work in backgroundScope, which advanceUntilIdle() leaves alone: the tests
    // let it run with runCurrent(), which runs every task due now. Nothing here waits for time.
    private fun TestScope.newStore(attached: Boolean = true): FileTaskStore {
        val dispatchers = AppDispatchers.single(StandardTestDispatcher(testScheduler))
        val store = FileTaskStore(settings, session, documents, lock, backgroundScope, dispatchers, BackupSharing(sharedDir))
        if (attached) store.attach(launcher)
        return store
    }

    private fun TestScope.resultsOf(store: FileTaskStore, kind: FileTask.Kind): MutableList<PickResult> {
        val seen = mutableListOf<PickResult>()
        backgroundScope.launch { store.results(kind).collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun theTaskIsRecordedAndTheGraceBegunBeforeThePickerOpens() = runTest {
        val store = newStore()
        val backup = FileTask(FileTask.Kind.BACKUP, 3, null)
        assertTrue(store.create(backup, "PasswordVault-backup-2026-10-08.pvault", DocumentTypes.BACKUP))
        assertEquals(listOf("create PasswordVault-backup-2026-10-08.pvault application/octet-stream"), launcher.calls)
        assertEquals("recorded when the picker opened", backup, launcher.recordedAtLaunch)
        assertTrue("the grace had begun", launcher.externalAtLaunch)
        assertEquals(backup, store.task.value)

        val import = FileTask(FileTask.Kind.IMPORT, 1, null)
        assertTrue(store.open(import))
        assertEquals("open */*", launcher.calls.last())
        assertEquals("the new task replaced the old one", import, launcher.recordedAtLaunch)
        assertTrue(store.open(import, listOf(DocumentTypes.CSV, DocumentTypes.JSON)))
        assertEquals("open text/csv application/json", launcher.calls.last())
    }

    @Test
    fun aPickedDocumentIsKeptAndRecordedBeforeItIsHandedOut() = runTest {
        val store = newStore()
        val export = FileTask(FileTask.Kind.EXPORT, 2, null)
        store.create(export, "passwords-2026-10-08.csv", DocumentTypes.CSV)
        // What the screen sees, and what was recorded and kept by the time it sees it.
        val seen = mutableListOf<Triple<PickResult, FileTask?, Boolean>>()
        backgroundScope.launch {
            store.results(FileTask.Kind.EXPORT).collect { seen += Triple(it, settings.fileTask.value, DOC in documents.kept) }
        }
        runCurrent()
        store.returned(FileTaskStore.Action.CREATE, DOC)
        runCurrent()
        val picked = export.copy(doc = DocRef(DOC))
        assertEquals(listOf(Triple(PickResult.Picked(picked), picked, true)), seen)
        assertEquals(DocRef(DOC), (seen.single().first as PickResult.Picked).doc)
        assertEquals(picked, store.task.value)
        assertEquals("the grace ended with the result", 1, session.ended)
        assertFalse(session.external)
    }

    @Test
    fun aResultThatCameWhileLockedWaitsForTheUnlockAndComesBackAfterEveryUnlock() = runTest {
        val store = newStore()
        val import = FileTask(FileTask.Kind.IMPORT, 1, null)
        store.open(import)
        lock.value = LOCKED // the screen went off in the picker
        val seen = resultsOf(store, FileTask.Kind.IMPORT)
        store.returned(FileTaskStore.Action.OPEN, DOC)
        runCurrent()
        val picked = import.copy(doc = DocRef(DOC))
        assertEquals("recorded while locked", picked, settings.fileTask.value)
        assertEquals("kept while locked", setOf(DOC), documents.kept)
        assertTrue("not handed out while locked", seen.isEmpty())

        lock.value = UNLOCKED
        runCurrent()
        assertEquals(listOf<PickResult>(PickResult.Picked(picked)), seen)
        lock.value = UNLOCKED_WITH_A_NOTICE // the same unlock with new notices: nothing new
        runCurrent()
        assertEquals(1, seen.size)

        lock.value = LOCKED
        runCurrent()
        lock.value = UNLOCKED
        runCurrent()
        assertEquals("the task continues after the next unlock", 2, seen.size)

        store.finish(FileTask.Kind.IMPORT)
        runCurrent()
        assertNull(settings.fileTask.value)
        lock.value = LOCKED
        runCurrent()
        lock.value = UNLOCKED
        runCurrent()
        assertEquals("a finished task is not handed out again", 2, seen.size)
    }

    @Test
    fun aRestoresResultIsNotHeldBackWhileLocked() = runTest {
        val store = newStore()
        lock.value = LockUiState.Starting
        val restore = FileTask(FileTask.Kind.RESTORE, 0, null)
        store.open(restore)
        val seen = resultsOf(store, FileTask.Kind.RESTORE)
        store.returned(FileTaskStore.Action.OPEN, DOC)
        runCurrent()
        assertTrue("not before the lock has decided", seen.isEmpty())
        lock.value = LockUiState.NoVault(emptyList()) // Welcome › Restore from a backup file
        runCurrent()
        assertEquals(listOf<PickResult>(PickResult.Picked(restore.copy(doc = DocRef(DOC)))), seen)
        lock.value = LOCKED // still one that may be handled: nothing new for the same task
        runCurrent()
        assertEquals(1, seen.size)
    }

    @Test
    fun aNewProcessReadsTheRecordedTaskAndHandsItOutAfterTheUnlock() = runTest {
        val first = newStore()
        val backup = FileTask(FileTask.Kind.BACKUP, 3, null)
        first.create(backup, "PasswordVault-backup-2026-10-08.pvault", DocumentTypes.BACKUP)
        // The process dies with the picker open: the next one starts locked, with the record only.
        lock.value = LockUiState.Starting
        val second = newStore(attached = false)
        assertEquals(backup, second.task.value)
        second.attach(launcher)
        second.returned(FileTaskStore.Action.CREATE, DOC)
        lock.value = LOCKED
        val seen = resultsOf(second, FileTask.Kind.BACKUP)
        runCurrent()
        assertEquals(backup.copy(doc = DocRef(DOC)), settings.fileTask.value)
        assertTrue(seen.isEmpty())
        lock.value = UNLOCKED
        runCurrent()
        assertEquals(listOf<PickResult>(PickResult.Picked(backup.copy(doc = DocRef(DOC)))), seen)
    }

    @Test
    fun aClosedPickerClearsTheRecordAndSaysSo() = runTest {
        val store = newStore()
        val export = FileTask(FileTask.Kind.EXPORT, 2, null)
        store.create(export, "passwords-2026-10-08.json", DocumentTypes.JSON)
        val seen = resultsOf(store, FileTask.Kind.EXPORT)
        store.returned(FileTaskStore.Action.CREATE, null)
        runCurrent()
        assertEquals(listOf<PickResult>(PickResult.Cancelled(export)), seen)
        assertNull(settings.fileTask.value)
        assertNull(store.task.value)
        assertEquals(1, session.ended)
        assertTrue("nothing was created, so nothing is deleted", documents.files.isEmpty())
    }

    @Test
    fun withoutAnActivityNothingOpens() = runTest {
        val store = newStore(attached = false)
        assertFalse(store.create(FileTask(FileTask.Kind.BACKUP, 3, null), "b.pvault", DocumentTypes.BACKUP))
        assertEquals("nothing recorded", 0, settings.writes)
        assertEquals("no grace", 0, session.begun)
        assertFalse(store.share(ByteArray(3), "b.pvault", "Send"))
        assertFalse(sharedDir.exists() && sharedDir.list()!!.isNotEmpty())

        val launcherGone = newStore()
        launcherGone.detach(launcher)
        assertFalse(launcherGone.open(FileTask(FileTask.Kind.IMPORT, 1, null)))
        assertTrue(launcher.calls.isEmpty())
    }

    @Test
    fun aTaskThatCannotBeRecordedOpensNoPicker() = runTest {
        val store = newStore()
        settings.failWrites = true
        assertFalse(store.create(FileTask(FileTask.Kind.BACKUP, 3, null), "b.pvault", DocumentTypes.BACKUP))
        assertTrue("the picker did not open", launcher.calls.isEmpty())
        assertNull(store.task.value)
        assertEquals(0, session.begun)
    }

    @Test
    fun withoutAPickerAppTheRecordIsPutBack() = runTest {
        val earlier = FileTask(FileTask.Kind.EXPORT, 4, DocRef(DOC))
        settings.update { it.fileTask = earlier }
        val store = newStore() // reads the earlier task
        assertEquals(earlier, store.task.value)
        launcher.failure = ActivityNotFoundException("no documents UI")
        assertFalse(store.create(FileTask(FileTask.Kind.BACKUP, 3, null), "b.pvault", DocumentTypes.BACKUP))
        assertEquals(earlier, settings.fileTask.value)
        assertEquals(earlier, store.task.value)
        assertEquals("the grace began and ended", 1 to 1, session.begun to session.ended)
    }

    @Test
    fun aDocumentNoTaskWaitsForIsRemovedOnlyWhenThePickerMadeItAndItIsEmpty() = runTest {
        val store = newStore()
        documents.files["$DOC-empty"] = ByteArray(0)
        documents.files["$DOC-full"] = byteArrayOf(1, 2, 3)
        documents.files["$DOC-opened"] = ByteArray(0)
        store.returned(FileTaskStore.Action.CREATE, "$DOC-empty")
        store.returned(FileTaskStore.Action.CREATE, "$DOC-full")
        store.returned(FileTaskStore.Action.OPEN, "$DOC-opened")
        runCurrent()
        assertEquals(setOf("$DOC-full", "$DOC-opened"), documents.files.keys)
        assertNull("nothing recorded", settings.fileTask.value)
        assertTrue("nothing kept", documents.kept.isEmpty())

        // A result of the other picker than the one the task opened is not the task's.
        val backup = FileTask(FileTask.Kind.BACKUP, 3, null)
        store.create(backup, "b.pvault", DocumentTypes.BACKUP)
        store.returned(FileTaskStore.Action.OPEN, "$DOC-opened")
        runCurrent()
        assertEquals(backup, settings.fileTask.value)
    }

    @Test
    fun aTaskOpensThePickerOfItsKind() = runTest {
        val store = newStore()
        for (kind in listOf(FileTask.Kind.IMPORT, FileTask.Kind.RESTORE)) {
            try {
                store.create(FileTask(kind, 0, null), "x", DocumentTypes.BACKUP)
                fail("$kind does not save")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        for (kind in listOf(FileTask.Kind.BACKUP, FileTask.Kind.EXPORT)) {
            try {
                store.open(FileTask(kind, 0, null))
                fail("$kind does not open")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(launcher.calls.isEmpty())
    }

    @Test
    fun finishForgetsOnlyItsOwnKind() = runTest {
        val store = newStore()
        val export = FileTask(FileTask.Kind.EXPORT, 2, null)
        store.create(export, "e.csv", DocumentTypes.CSV)
        store.finish(FileTask.Kind.BACKUP)
        assertEquals(export, settings.fileTask.value)
        store.finish(FileTask.Kind.EXPORT)
        assertNull(settings.fileTask.value)
    }

    @Test
    fun shareWritesTheCopyAndOpensTheShareSheetInTheGrace() = runTest {
        val store = newStore()
        val bytes = byteArrayOf(80, 86, 65, 85, 76, 84, 48, 49)
        assertTrue(store.share(bytes, "PasswordVault-backup-2026-10-08.pvault", "Send to another app"))
        val shared = launcher.shared!!
        assertEquals(File(sharedDir, "PasswordVault-backup-2026-10-08.pvault"), shared)
        assertArrayEquals(bytes, shared.readBytes())
        assertEquals("Send to another app", launcher.shareTitle)
        assertTrue("the grace had begun", launcher.externalAtLaunch)

        launcher.failure = ActivityNotFoundException("no share sheet")
        assertFalse(store.share(bytes, "again.pvault", "Send"))
        assertFalse("the grace ended", session.external)
    }

    @Test
    fun theCopiesOfAnEarlierProcessGoWhenAStoreIsMade() = runTest {
        sharedDir.mkdirs()
        File(sharedDir, "PasswordVault-backup-2026-10-01.pvault").writeBytes(ByteArray(10))
        newStore()
        runCurrent()
        assertTrue(sharedDir.list()!!.isEmpty())
    }

    /** A [Session] that counts the external screens. */
    private class CountingSession : Session {
        var begun = 0
        var ended = 0
        var external = false

        override fun beginExternal() {
            begun++
            external = true
        }

        override fun endExternal() {
            ended++
            external = false
        }

        override fun touched() = Unit

        override fun lockNow() = Unit
    }

    /** Records each call, the task recorded and whether the grace had begun when it came. */
    private class RecordingLauncher(private val settings: AppSettings, private val session: CountingSession) : PickerLauncher {
        val calls = mutableListOf<String>()
        var recordedAtLaunch: FileTask? = null
        var externalAtLaunch = false
        var shared: File? = null
        var shareTitle: CharSequence? = null
        var failure: RuntimeException? = null

        override fun create(name: String, mime: String) = called("create $name $mime")

        override fun open(mimeTypes: List<String>) = called("open " + mimeTypes.joinToString(" "))

        override fun share(file: File, title: CharSequence) {
            called("share ${file.name}")
            shared = file
            shareTitle = title
        }

        private fun called(call: String) {
            failure?.let { throw it }
            calls += call
            recordedAtLaunch = settings.fileTask.value
            externalAtLaunch = session.external
        }
    }

    private companion object {
        const val DOC = "content://io.github.dilshodbekmx.passwordvault.test.documents/document/plain-1"

        val LOCKED = LockUiState.Locked(
            LockScreen.MasterPassword(
                reason = null,
                canUsePin = false,
                practice = null,
                waitUntilElapsedMs = null,
                error = null,
                pendingReadyInMs = null,
                opening = false,
                aside = null,
            ),
        )
        val UNLOCKED = LockUiState.Unlocked(emptyList(), null)
        val UNLOCKED_WITH_A_NOTICE = LockUiState.Unlocked(listOf(Notice.BackupNow(1)), null)
    }
}
