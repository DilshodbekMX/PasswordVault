package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build plan 3.1, [JVM]: `holdDraft` and `flushDraft` (architecture.md §3.5, §5.5 step 2; ux.md
 * §5.14). `flushDraft` writes only a held draft that differs from the stored one; it waits at most
 * two seconds for a save that is running; once started it finishes; a lock drops the held draft.
 */
class DraftTest {
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)

    private val generated = Draft(entryId = null, savedAt = 100L, entry = DraftEntry(title = "New account", password = "gen-🔑-Xq7"))

    private fun TestScope.repository(): Pair<DefaultVaultRepository, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return phone.process(scope, dispatcher) to scope
    }

    @Test
    fun flushDraftWritesOnlyAChangedDraft() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            repo.flushDraft()
            assertEquals("nothing held, nothing written", listOf(1L), phone.liveNumbers())

            repo.holdDraft(generated)
            assertNull("held in memory only", repo.shown().vault.draft)
            repo.flushDraft()
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
            assertEquals(generated, repo.shown().vault.draft)

            repo.flushDraft()
            assertEquals("the same draft again", listOf(1L, 2L), phone.liveNumbers())

            repo.holdDraft(generated.copy(savedAt = 200L))
            repo.flushDraft()
            assertEquals("only the time differs", listOf(1L, 2L), phone.liveNumbers())

            val changed = generated.copy(savedAt = 300L, entry = generated.entry.copy(username = "aziz"))
            repo.holdDraft(changed)
            repo.flushDraft()
            assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
            assertEquals(changed, repo.shown().vault.draft)

            repo.holdDraft(null)
            repo.flushDraft()
            assertEquals("no form is dirty", listOf(1L, 2L, 3L), phone.liveNumbers())
            assertEquals("the stored draft stays", changed, repo.shown().vault.draft)

            // It survives a lock and a new process (ux.md §12 check 4).
            repo.lock()
            val again = phone.process(scope, StandardTestDispatcher(testScheduler))
            again.unlock()
            assertEquals(changed, again.shown().vault.draft)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theNewestHeldFormWins() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            val newer = generated.copy(entry = generated.entry.copy(title = "Newer"))
            repo.holdDraft(generated)
            repo.holdDraft(newer)
            repo.flushDraft()
            assertEquals(newer, repo.shown().vault.draft)
            // Discard (ux.md §5.14): nothing held, and the stored draft cleared by an edit.
            repo.holdDraft(null)
            repo.edit(VaultEdits.clearDraft())
            repo.flushDraft()
            assertNull(repo.shown().vault.draft)
            assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLockDropsTheHeldDraftAndOneHeldWhileLockedIsIgnored() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            repo.holdDraft(generated)
            repo.lock()
            repo.unlock()
            repo.flushDraft()
            assertEquals("dropped at the lock", listOf(1L), phone.liveNumbers())
            repo.lock()
            repo.holdDraft(generated)
            repo.flushDraft()
            repo.unlock()
            repo.flushDraft()
            assertEquals("held while locked: ignored", listOf(1L), phone.liveNumbers())
            assertNull(repo.shown().vault.draft)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFlushWaitsAtMostTwoSecondsForASaveThatIsRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = phone.process(scope, Dispatchers.IO)
            repo.create()
            repo.holdDraft(generated)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            files.beforeOp = { op, path ->
                if (op == FakeFiles.Op.WRITE_NEW && path.endsWith(".tmp") && entered.count > 0) {
                    entered.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            val running = async(Dispatchers.IO) { repo.edit(VaultEdits.add(form("slow"), TestTexts, phone.random)) }
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            val started = System.nanoTime()
            repo.flushDraft()
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("gave up after about 2 s, not $waitedMs ms", waitedMs in 1_900L..10_000L)
            release.countDown()
            assertTrue(running.await() is SaveResult.Saved)
            assertNull("the draft was skipped", repo.shown().vault.draft)
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
            // With nothing running it is written at once.
            repo.flushDraft()
            assertEquals(generated, repo.shown().vault.draft)
            assertEquals(listOf(1L, 2L, 3L), phone.liveNumbers())
        } finally {
            files.beforeOp = null
            scope.cancel()
        }
    }

    @Test
    fun aFlushThatHasStartedFinishesWhenItsCallerIsCancelled() = runTest {
        val (repo, scope) = repository()
        try {
            repo.create()
            repo.holdDraft(generated)
            var caller: Job? = null
            files.beforeOp = { op, path -> if (op == FakeFiles.Op.RENAME && path.endsWith(".tmp")) caller?.cancel() }
            caller = launch { repo.flushDraft() }
            caller.join()
            files.beforeOp = null
            assertTrue(caller.isCancelled)
            advanceUntilIdle()
            assertEquals(generated, repo.shown().vault.draft)
            assertEquals(listOf(1L, 2L), phone.liveNumbers())
        } finally {
            scope.cancel()
        }
    }
}
