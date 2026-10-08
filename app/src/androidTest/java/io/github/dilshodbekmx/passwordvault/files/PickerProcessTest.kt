package io.github.dilshodbekmx.passwordvault.files

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.Stage
import io.github.dilshodbekmx.passwordvault.MainActivity
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1, [device], on API 24 and 37: the picker's plumbing in the running app, with its
 * real activity, its real lock (the master-password gate of build plan 3.2 until 4.1) and its real
 * settings file, and a picker that is another app's activity: [FakePickerActivity], in the test
 * APK's process, started under the request code of the app's own launcher in place of the system
 * picker, which the test keeps from opening ([DocumentsRig.SystemScreens]).
 *
 * The death of the process cannot happen inside an instrumented test: the test runs in that
 * process, and when it dies the runner force-stops the app, which removes the activity that the
 * result would go to. So a test stands in for it with everything the death changes for the
 * picker's result: the lock is locked ("Lock now"; a new process starts locked), the file task's
 * store is forgotten ([FileTaskStore.forgetForTests]; a new process has only the record in the
 * settings), and the activity is made again from its saved state while the picker is open, so
 * that the result reaches a new activity through the request code its registry restored. The
 * settings are also read back from their file as a new process reads them. The kill itself, with
 * the real screens, is U §12 check 13 at wave 5's integration check.
 */
@RunWith(AndroidJUnit4::class)
class PickerProcessTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = AppVault.graph
    private val made = mutableListOf<DocRef>()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun store(): FileTaskStore = FileTaskStore.of(graph)

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        AppVault.unlock()
        forgetTheTask()
    }

    @After
    fun lockTheAppAgain() {
        collectors.cancel()
        DocumentsRig.finishPicker(Activity.RESULT_CANCELED) // a picker a failed test left open
        // Other device tests expect a locked or empty app; a recorded task would follow them.
        if (AppVault.state is LockUiState.Unlocked) AppVault.lockNow()
        forgetTheTask()
        scenario.close()
        made.forEach(DocumentsRig::release)
    }

    private fun forgetTheTask() = runBlocking {
        store().task.value?.let { store().finish(it.kind) }
    }

    private fun currentActivity(): MainActivity {
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        return activity!!
    }

    /** Opens the fake picker in place of the system one, for [key], making the document [id] with [content]. */
    private fun pick(activity: MainActivity, key: String, id: String?, name: String, content: ByteArray? = null) {
        DocumentsRig.openFakePicker(activity, key, id, name, content)
        DocumentsRig.await("the app behind the picker") { activity in DocumentsRig.activities(MainActivity::class.java, Stage.STOPPED) }
    }

    /** What the store hands to a screen that waits for [kind], from now on. */
    private fun handedOut(kind: FileTask.Kind): MutableList<PickResult> {
        val seen = Collections.synchronizedList(mutableListOf<PickResult>())
        val store = store()
        collectors.launch { store.results(kind).collect { seen += it } }
        return seen
    }

    @Test
    fun aTaskRecordedBeforeThePickerOpensIsStillThereAfterTheProcessIsKilled() {
        val export = FileTask(FileTask.Kind.EXPORT, 4, null)
        DocumentsRig.SystemScreens().use { screens ->
            assertTrue(runBlocking { store().create(export, DocumentNames.csv("2026-10-08"), DocumentTypes.CSV) })
            assertEquals("the system picker was asked for", 1, screens.creates)
        }
        assertEquals(export, store().task.value)
        assertEquals("in the settings file, as a new process reads it", export, settingsFromDisk().fileTask.value)
        FileTaskStore.forgetForTests()
        assertEquals("the new process's store has it", export, store().task.value)
    }

    @Test
    fun aPickerResultDeliveredToANewLockedProcessIsRecordedAndHandledAfterTheUnlock() {
        val backup = FileTask(FileTask.Kind.BACKUP, 3, null)
        val name = DocumentNames.backup("2026-10-08")
        val id = DocumentsRig.newId(TestDocuments.PLAIN)
        val doc = DocumentsRig.docOf(id).also { made += it }
        val first = currentActivity()
        DocumentsRig.SystemScreens().use { screens ->
            assertTrue(runBlocking { store().create(backup, name, DocumentTypes.BACKUP) })
            assertEquals(1, screens.creates)
        }
        pick(first, DocumentLaunchers.CREATE_KEY, id, name)
        DocumentsRig.await("the app left for the picker") { processState() == Lifecycle.State.CREATED }
        assertTrue("the grace keeps the app open in the picker", AppVault.state is LockUiState.Unlocked)

        // The process dies while the picker is open: a new one starts locked, with no store in memory,
        // and makes the activity again from its saved state.
        AppVault.lockNow()
        FileTaskStore.forgetForTests()
        DocumentsRig.instrumentation.runOnMainSync { first.recreate() }
        DocumentsRig.await("the activity made again") { newActivity(first) != null }
        val handled = handedOut(FileTask.Kind.BACKUP) // a screen of the new process, waiting for the backup

        // The user picks the place: the result reaches the new activity, while the app is locked.
        assertTrue(DocumentsRig.finishPicker(Activity.RESULT_OK))
        val picked = backup.copy(doc = doc)
        DocumentsRig.await("the result recorded") { store().task.value == picked }
        assertEquals("recorded in the settings file", picked, settingsFromDisk().fileTask.value)
        assertEquals("its permission kept for good", true to true, DocumentsRig.persisted(doc))
        assertTrue("still locked", AppVault.state is LockUiState.Locked)
        Thread.sleep(SETTLE_MS)
        assertTrue("not handed out while locked", handled.isEmpty())

        // The unlock: now the task's screen gets it, and finishes the backup in the chosen place.
        AppVault.unlock()
        DocumentsRig.await("handed out after the unlock") { handled.isNotEmpty() }
        assertEquals(listOf<PickResult>(PickResult.Picked(picked)), handled.toList())
        val bytes = "PVAULT01 a backup".toByteArray()
        assertTrue("written and read back", runBlocking { graph.files.write(doc, bytes) })
        assertArrayEquals(bytes, DocumentsRig.stored(id))
        runBlocking { store().finish(FileTask.Kind.BACKUP) }
        assertNull(store().task.value)
        assertNull(settingsFromDisk().fileTask.value)
    }

    @Test
    fun withImmediatelyAResultInTheOpenAppIsHandledAtOnce() {
        setLockDelay(LockDelay.IMMEDIATELY)
        try {
            val export = FileTask(FileTask.Kind.EXPORT, 4, null)
            val name = DocumentNames.json("2026-10-08")
            val id = DocumentsRig.newId(TestDocuments.PLAIN)
            val doc = DocumentsRig.docOf(id).also { made += it }
            val activity = currentActivity()
            val handled = handedOut(FileTask.Kind.EXPORT)
            DocumentsRig.SystemScreens().use { assertTrue(runBlocking { store().create(export, name, DocumentTypes.JSON) }) }
            pick(activity, DocumentLaunchers.CREATE_KEY, id, name)
            DocumentsRig.await("the app left for the picker") { processState() == Lifecycle.State.CREATED }
            Thread.sleep(SETTLE_MS)
            assertTrue("\"Immediately\" does not lock in the picker (lock.md §10.3)", AppVault.state is LockUiState.Unlocked)

            assertTrue(DocumentsRig.finishPicker(Activity.RESULT_OK))
            DocumentsRig.await("handed out") { handled.isNotEmpty() }
            assertEquals(listOf<PickResult>(PickResult.Picked(export.copy(doc = doc))), handled.toList())
            assertEquals(export.copy(doc = doc), settingsFromDisk().fileTask.value)
            assertEquals(true to true, DocumentsRig.persisted(doc))
            assertTrue(AppVault.state is LockUiState.Unlocked)
            val bytes = "{\"encrypted\":false}".toByteArray()
            assertTrue(runBlocking { graph.files.write(doc, bytes) })
            assertArrayEquals(bytes, DocumentsRig.stored(id))
        } finally {
            setLockDelay(LockDelay.SETUP)
        }
    }

    @Test
    fun aClosedPickerIsReportedAndForgotten() {
        val export = FileTask(FileTask.Kind.EXPORT, 4, null)
        val activity = currentActivity()
        val handled = handedOut(FileTask.Kind.EXPORT)
        DocumentsRig.SystemScreens().use { assertTrue(runBlocking { store().create(export, "passwords.csv", DocumentTypes.CSV) }) }
        pick(activity, DocumentLaunchers.CREATE_KEY, null, "passwords.csv")
        assertTrue(DocumentsRig.finishPicker(Activity.RESULT_CANCELED))
        DocumentsRig.await("the closed picker reported") { handled.isNotEmpty() }
        assertEquals(listOf<PickResult>(PickResult.Cancelled(export)), handled.toList())
        assertNull(store().task.value)
        assertNull(settingsFromDisk().fileTask.value)
    }

    @Test
    fun anImportReadsTheDocumentItOpened() {
        val import = FileTask(FileTask.Kind.IMPORT, 1, null)
        val content = "name,url,username,password,note\n".toByteArray()
        val id = DocumentsRig.newId(TestDocuments.NO_DELETE)
        val doc = DocumentsRig.docOf(id).also { made += it } // the picker's result is the only grant
        val activity = currentActivity()
        val handled = handedOut(FileTask.Kind.IMPORT)
        DocumentsRig.SystemScreens().use { screens ->
            assertTrue(runBlocking { store().open(import) })
            assertEquals(1, screens.opens)
        }
        pick(activity, DocumentLaunchers.OPEN_KEY, id, "chrome.csv", content)
        assertTrue(DocumentsRig.finishPicker(Activity.RESULT_OK))
        DocumentsRig.await("handed out") { handled.isNotEmpty() }
        val picked = handled.single() as PickResult.Picked
        assertEquals(import.copy(doc = doc), picked.task)
        assertArrayEquals(content, runBlocking { graph.files.read(picked.doc, 10_000) })
        assertTrue("kept", DocumentsRig.persisted(doc) != null)
    }

    @Test
    fun aRestoreFromTheLockScreenIsHandedOutWhileLocked() {
        AppVault.lockNow()
        val restore = FileTask(FileTask.Kind.RESTORE, 0, null)
        val id = DocumentsRig.newId(TestDocuments.PLAIN)
        val doc = DocumentsRig.docOf(id).also { made += it }
        val activity = currentActivity()
        val handled = handedOut(FileTask.Kind.RESTORE)
        DocumentsRig.SystemScreens().use { assertTrue(runBlocking { store().open(restore) }) }
        pick(activity, DocumentLaunchers.OPEN_KEY, id, "PasswordVault-backup-2026-10-06.pvault", "PVAULT01".toByteArray())
        assertTrue(DocumentsRig.finishPicker(Activity.RESULT_OK))
        DocumentsRig.await("handed out while locked") { handled.isNotEmpty() }
        assertEquals(listOf<PickResult>(PickResult.Picked(restore.copy(doc = doc))), handled.toList())
        assertTrue(AppVault.state is LockUiState.Locked)
    }

    @Test
    fun aGivenUpBackupRemovesTheEmptyFileOrNamesItsPlace() {
        val backup = FileTask(FileTask.Kind.BACKUP, 3, null)
        val name = DocumentNames.backup("2026-10-08")
        for (behaviour in listOf(TestDocuments.PLAIN, TestDocuments.NO_DELETE)) {
            val id = DocumentsRig.newId(behaviour)
            val doc = DocumentsRig.docOf(id).also { made += it }
            val activity = currentActivity()
            val handled = handedOut(FileTask.Kind.BACKUP)
            DocumentsRig.SystemScreens().use { assertTrue(runBlocking { store().create(backup, name, DocumentTypes.BACKUP) }) }
            pick(activity, DocumentLaunchers.CREATE_KEY, id, name)
            assertTrue(DocumentsRig.finishPicker(Activity.RESULT_OK))
            DocumentsRig.await("handed out") { handled.isNotEmpty() }
            // The user cancels at the master password: no backup, and the empty file goes where it can.
            val cleanup = runBlocking { store().abandon(FileTask.Kind.BACKUP) }
            if (behaviour == TestDocuments.PLAIN) {
                assertEquals(Cleanup.Removed, cleanup)
                assertNull("gone from the place", DocumentsRig.stored(id))
            } else {
                assertEquals(Cleanup.Left(doc, Place.Other("PV test documents")), cleanup)
                assertArrayEquals("still there, empty", ByteArray(0), DocumentsRig.stored(id))
            }
            assertNull(store().task.value)
        }
    }

    @Test
    fun theShareSheetOpensForACopyOnlyTheFileProviderHandsOut() {
        val bytes = "PVAULT01 shared".toByteArray()
        val name = DocumentNames.backup("2026-10-08")
        DocumentsRig.SystemScreens().use { screens ->
            assertTrue(runBlocking { store().share(bytes, name, "Send to another app") })
            assertEquals("the share sheet was asked for", 1, screens.shares)
        }
        val copy = File(File(DocumentsRig.app.cacheDir, BackupSharing.DIR), name)
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test
    fun afterAShareSheetClosedWithoutAChoiceLeavingTheAppLocksAsTheDelaySays() {
        // lock.md §10.3 item 3: the grace ends when the app is visible again, because the share sheet
        // returns no result. The share sheet covers the activity without stopping it, so the process
        // never leaves and never comes back: only the activity's own resume says that it is closed.
        // (When an app is chosen in the sheet, the activity is stopped without being resumed first,
        // on Android 7 and 17 alike, and the grace goes on.)
        setLockDelay(LockDelay.IMMEDIATELY)
        try {
            val activity = currentActivity()
            openTheShareSheet(activity)
            DocumentsRig.backTo(activity, "the app in front again") // the sheet closed without sending anything
            DocumentsRig.pressHome()
            DocumentsRig.await("the app left") { processState() == Lifecycle.State.CREATED }
            DocumentsRig.await("\"Immediately\" locks the app that was left after the share sheet was closed", 5_000) { AppVault.state is LockUiState.Locked }
        } finally {
            setLockDelay(LockDelay.SETUP)
        }
    }

    /** Shares a backup from [activity] through the system's own share sheet, and waits until the sheet is in front of it. */
    private fun openTheShareSheet(activity: MainActivity) {
        DocumentsRig.awaitFocus("the app in front", ofTheApp = true)
        assertTrue(runBlocking { store().share("PVAULT01 shared".toByteArray(), DocumentNames.backup("2026-10-08"), "Send to another app") })
        DocumentsRig.await("the app behind the share sheet") { activity in DocumentsRig.activities(MainActivity::class.java, Stage.PAUSED) }
        DocumentsRig.awaitFocus("the share sheet in front", ofTheApp = false)
    }

    private fun newActivity(old: MainActivity): MainActivity? =
        DocumentsRig.activities(MainActivity::class.java, Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
            .firstOrNull { it !== old }

    private fun processState(): Lifecycle.State = ProcessLifecycleOwner.get().lifecycle.currentState

    private fun setLockDelay(delay: LockDelay) {
        val written = runBlocking { kotlinx.coroutines.withContext(Dispatchers.IO) { graph.settings.update { it.lockDelay = delay } } }
        assertTrue(written)
    }

    /** The app's settings as a new process reads them: the file read afresh by the app's own reader. */
    private fun settingsFromDisk(): AppSettings {
        val context = DocumentsRig.app
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val copy = "pvtest-settings-" + UUID.randomUUID()
        val source = File(dir, PrefsAppSettings.FILE + ".xml")
        assertTrue("the settings file exists", source.isFile)
        source.copyTo(File(dir, "$copy.xml"))
        try {
            return PrefsAppSettings(context.getSharedPreferences(copy, Context.MODE_PRIVATE))
        } finally {
            File(dir, "$copy.xml").delete()
        }
    }

    private companion object {
        /** How long a result that must not come is waited for. */
        const val SETTLE_MS = 1_500L
    }
}
