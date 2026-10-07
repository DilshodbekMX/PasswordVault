package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Build plan 3.1, [JVM]: `edit` (architecture.md §3.5, §4.9, §5.3). An edit is visible to
 * observers only after the file is on disk; a failing `syncDir` gives `NotSaved` and the old state;
 * two edits at once are applied one after the other; `TOO_LARGE` is reported as such; a save runs to
 * its end when its caller is cancelled.
 */
class EditTest {
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)

    private fun TestScope.repository(): Pair<DefaultVaultRepository, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return phone.process(scope, dispatcher) to scope
    }

    private fun titles(repo: DefaultVaultRepository): List<String> = repo.shown().vault.entries.map { it.title }

    @Test
    fun anEditIsVisibleToObserversOnlyAfterTheFileIsOnDisk() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            // What observers see at every file operation of the save, until the new vault is shown.
            var shownAt: Int? = null
            val seenDuringSave = mutableListOf<Pair<String, VaultState>>()
            files.onBoundary = { _, at -> if (shownAt == null) seenDuringSave += at to repo.state.value }
            var durableWhenShown: Boolean? = null
            val watcher = scope.launch(Dispatchers.Unconfined) {
                repo.state.collect { state ->
                    if (state is VaultState.Unlocked && state.generation == 2L && shownAt == null) {
                        shownAt = seenDuringSave.size
                        // At the moment observers see it: does the new generation survive a power cut now?
                        val afterPowerCut = files.copy().also { it.powerCut() }
                        durableWhenShown = afterPowerCut.bytes(phone.pathOf(2))?.contentEquals(files.bytes(phone.pathOf(2))) == true
                    }
                }
            }
            repo.add(form("gmail"), phone.random)
            files.onBoundary = null
            watcher.cancel()

            assertEquals("shown once the generation survives a power cut", true, durableWhenShown)
            val steps = seenDuringSave.map { it.first }
            assertTrue("the new file was renamed into place before it was shown: $steps", steps.any { it.startsWith("rename") && it.endsWith(phone.pathOf(2)) })
            assertTrue("and its directory synced: $steps", steps.any { it == "syncDir $LIVE" })
            for ((at, state) in seenDuringSave) {
                assertTrue("at '$at' observers saw the vault as it was", state is VaultState.Unlocked && state.generation == 1L && state.vault.entries.isEmpty())
            }
            assertEquals(2L, repo.shown().generation)
            assertEquals(listOf("gmail"), titles(repo))
            assertNull(repo.shown().saveProblem)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailingSyncDirGivesNotSavedAndTheOldState() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            repo.add(form("first"), phone.random)
            files.fail(FakeFiles.Op.SYNC_DIR) { it == LIVE }
            val result = repo.edit(VaultEdits.add(form("second"), TestTexts, phone.random))
            assertEquals(SaveResult.NotSaved(SaveProblem.NOT_DURABLE), result)
            val shown = repo.shown()
            assertEquals("the old generation", 2L, shown.generation)
            assertEquals("the old vault", listOf("first"), titles(repo))
            assertEquals(SaveProblem.NOT_DURABLE, shown.saveProblem)

            // "Try again" starts at step 2: numbered above every name on disk, and shown with no problem.
            repo.add(form("again"), phone.random)
            assertEquals(4L, repo.shown().generation)
            assertEquals(listOf("first", "again"), titles(repo))
            assertNull(repo.shown().saveProblem)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedWriteGivesFailedAndChangesNothing() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            files.fail(FakeFiles.Op.WRITE_NEW)
            assertEquals(SaveResult.NotSaved(SaveProblem.FAILED), repo.edit(VaultEdits.add(form("lost"), TestTexts, phone.random)))
            assertEquals(1L, repo.shown().generation)
            assertEquals(SaveProblem.FAILED, repo.shown().saveProblem)
            assertEquals(listOf(1L), phone.liveNumbers())
            assertEquals("no .tmp left", listOf("vault-0000000001.pvault"), phone.names(LIVE))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun twoEditsAtOnceAreAppliedOneAfterTheOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = phone.process(scope, Dispatchers.IO)
            repo.create()
            // The first save stops inside its write until the test lets it go.
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            files.beforeOp = { op, path ->
                if (op == FakeFiles.Op.WRITE_NEW && path.endsWith(".tmp") && entered.count > 0) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            val first = async(Dispatchers.IO) { repo.edit(VaultEdits.add(form("first"), TestTexts, phone.random)) }
            assertTrue("the first save is writing", entered.await(20, TimeUnit.SECONDS))
            val seenBySecond = AtomicReference<List<String>?>(null)
            val second = async(Dispatchers.IO) {
                repo.edit { vault, nowMs ->
                    seenBySecond.set(vault.entries.map { it.title })
                    VaultEdits.add(form("second"), TestTexts, phone.random)(vault, nowMs)
                }
            }
            delay(300)
            assertNull("the second change has not run while the first save is writing", seenBySecond.get())
            assertFalse(second.isCompleted)
            release.countDown()
            assertTrue(first.await() is SaveResult.Saved)
            assertTrue(second.await() is SaveResult.Saved)
            assertEquals("the second change saw the first one's result", listOf("first"), seenBySecond.get())
            assertEquals(listOf("first", "second"), titles(repo))
            assertEquals(3L, repo.shown().generation)
            assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
        } finally {
            files.beforeOp = null
            scope.cancel()
        }
    }

    @Test
    fun aVaultTooLargeForOneFileIsTooLargeAndNothingIsWritten() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            repo.add(form("small"), phone.random)
            // 17 notes of a million characters: more than the 16,777,216 bytes of one file (§4.1, §4.5 rule 5).
            val huge = (1..17).map { n -> entry(100 + n).copy(notes = "x".repeat(1_000_000)) }
            val result = repo.edit { vault, _ -> Edited(vault.copy(entries = vault.entries + huge), Unit) }
            // Shown as "Your passwords no longer fit in one file. Delete accounts you don't need, or
            // shorten very long notes." (architecture.md §3.5).
            assertEquals(SaveResult.NotSaved(SaveProblem.TOO_LARGE), result)
            assertEquals(SaveProblem.TOO_LARGE, repo.shown().saveProblem)
            assertEquals(listOf("small"), titles(repo))
            assertEquals(2L, repo.shown().generation)
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
            assertEquals("no .tmp left", 2, phone.names(LIVE).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChangeThatChangesNothingWritesNothing() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            val before = repo.state.value
            val result = repo.edit { vault, _ -> Edited(vault, 42) }
            assertEquals(42, (result as SaveResult.Saved).value)
            assertEquals(listOf(1L), phone.liveNumbers())
            assertTrue("nothing published", before === repo.state.value)
            // The same through VaultEdits: restoring an entry that is not deleted.
            val id = repo.add(form("one"), phone.random)
            repo.edit(VaultEdits.restore(id))
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anEditWhileLockedIsNotSaved() = runTest {
        val (repo, scope) = repository()
        try {
            assertEquals(SaveResult.NotSaved(SaveProblem.FAILED), repo.edit(VaultEdits.add(form("x"), TestTexts, phone.random)))
            repo.create()
            repo.lock()
            assertEquals(SaveResult.NotSaved(SaveProblem.FAILED), repo.edit(VaultEdits.add(form("x"), TestTexts, phone.random)))
            assertEquals(listOf(1L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSaveRunsToItsEndWhenItsCallerIsCancelled() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            var caller: Job? = null
            files.beforeOp = { op, path -> if (op == FakeFiles.Op.RENAME && path.endsWith(".tmp")) caller?.cancel() }
            caller = launch { repo.edit(VaultEdits.add(form("kept"), TestTexts, phone.random)) }
            caller.join()
            files.beforeOp = null
            assertTrue("the caller was cancelled during the save", caller.isCancelled)
            advanceUntilIdle()
            assertEquals("the save finished and was shown", listOf("kept"), titles(repo))
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }
}
