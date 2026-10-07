package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.settings.PurgeArm
import io.github.dilshodbekmx.passwordvault.testing.storage.FakeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Build plan 3.1, [JVM]: the purge of Recently deleted, armed and carried out at unlock
 * (architecture.md D-24; ux.md §5.23). An unlock that finds entries deleted 30 days or more ago only
 * arms it; a later unlock, once 24 hours of phone-on time are counted (lock.md §6.9), removes the
 * entries that were due when it was armed, and nothing else, whatever the wall clock says; a switch
 * of vault disarms.
 */
class PurgeTest {
    private val hourMs = 60L * 60 * 1000
    private val dayMs = 24 * hourMs
    private val files = FakeFiles().apply { mkdirs(FILES) }
    private val phone = Phone(files = files)
    private val clock = phone.clock
    private val settings = phone.settings

    private fun TestScope.repository(): Pair<DefaultVaultRepository, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return phone.process(scope, dispatcher) to scope
    }

    private fun ids(repo: DefaultVaultRepository): List<String> = repo.shown().vault.entries.map { it.id }

    /** A vault holding an entry deleted 31 days ago, one deleted a day ago, and one in the list. */
    private suspend fun TestScope.vaultWithDeletedEntries(repo: DefaultVaultRepository): String {
        val created = repo.create()
        val now = clock.wallMs()
        repo.edit { vault, _ ->
            Edited(vault.copy(entries = listOf(entry(1, deletedAt = now - 31 * dayMs), entry(2, deletedAt = now - dayMs), entry(3))), Unit)
        }
        repo.lock()
        testScheduler.advanceUntilIdle()
        return created.vaultId
    }

    private suspend fun TestScope.relock(repo: DefaultVaultRepository) {
        repo.lock()
        repo.unlock()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun anUnlockThatFindsDueEntriesOnlyArmsThePurge() = runTest {
        val (repo, scope) = repository()
        try {
            val vaultId = vaultWithDeletedEntries(repo)
            assertNull(settings.purgeArm.value)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            assertEquals(PurgeArm(vaultId, clock.wallMs(), Credit(0L, clock.elapsedMs())), settings.purgeArm.value)
            assertEquals("nothing removed at the arming", listOf(id(1), id(2), id(3)), ids(repo))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theDueEntriesGoOnceADayOfPhoneOnTimeIsCountedAndOnlyThose() = runTest {
        val (repo, scope) = repository()
        try {
            val vaultId = vaultWithDeletedEntries(repo)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            val armedAt = clock.wallMs()
            assertEquals(armedAt, settings.purgeArm.value!!.armedAtMs)

            // 23 hours on: nothing removed; the arm has counted them.
            clock.advance(23 * hourMs)
            relock(repo)
            assertEquals(listOf(id(1), id(2), id(3)), ids(repo))
            assertEquals(23 * hourMs, settings.purgeArm.value!!.credit.creditedMs)

            // The date a year ahead removes nothing more: no phone-on time passed.
            clock.jumpWall(365 * dayMs)
            relock(repo)
            assertEquals(listOf(id(1), id(2), id(3)), ids(repo))
            assertEquals(armedAt, settings.purgeArm.value!!.armedAtMs)

            // One more hour on: what was due when it was armed goes, and only that. Entry 2 was
            // deleted a day before the arming; the clock says it is due now, so this unlock arms again.
            clock.advance(hourMs)
            relock(repo)
            assertEquals(listOf(id(2), id(3)), ids(repo))
            assertEquals(PurgeArm(vaultId, clock.wallMs(), Credit(0L, clock.elapsedMs())), settings.purgeArm.value)

            // Removed for good: also after a new process.
            repo.lock()
            val again = phone.process(scope, StandardTestDispatcher(testScheduler))
            again.unlock()
            assertEquals(listOf(id(2), id(3)), ids(again))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun withNothingDueAnyMoreThePurgeDisarms() = runTest {
        val (repo, scope) = repository()
        try {
            vaultWithDeletedEntries(repo)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            // The user empties Recently deleted meanwhile.
            repo.edit { vault, _ -> Edited(vault.copy(entries = vault.entries.filter { it.deletedAt == null }), Unit) }
            clock.advance(25 * hourMs)
            relock(repo)
            assertNull("disarmed", settings.purgeArm.value)
            assertEquals(listOf(id(3)), ids(repo))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSwitchOfVaultDisarms() = runTest {
        val (repo, scope) = repository()
        try {
            vaultWithDeletedEntries(repo)
            // An arm of another vault, with more than its 24 hours counted.
            val other = PurgeArm(vaultId(9), clock.wallMs() + 40 * dayMs, Credit(30 * hourMs, clock.elapsedMs()))
            settings.purgeArm.value = other
            repo.unlock()
            testScheduler.advanceUntilIdle()
            assertEquals("nothing purged here for another vault's arm", listOf(id(1), id(2), id(3)), ids(repo))
            // Disarmed, and armed again for this vault, whose entry 1 is due.
            assertEquals(PurgeArm(vaultId(1), clock.wallMs(), Credit(0L, clock.elapsedMs())), settings.purgeArm.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLockCountsThePhoneOnTimeAndARebootCountsTheNewUptime() = runTest {
        val (repo, scope) = repository()
        try {
            vaultWithDeletedEntries(repo)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            clock.advance(5 * hourMs)
            repo.lock()
            testScheduler.advanceUntilIdle()
            assertEquals(5 * hourMs, settings.purgeArm.value!!.credit.creditedMs)
            // Off for a night, on again for an hour: only the hour is counted (lock.md §6.9).
            clock.reboot(offMs = 8 * hourMs, uptimeMs = hourMs)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            assertEquals(6 * hourMs, settings.purgeArm.value!!.credit.creditedMs)
            assertEquals(listOf(id(1), id(2), id(3)), ids(repo))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPurgeThatCannotBeSavedKeepsTheArm() = runTest {
        val (repo, scope) = repository()
        try {
            val vaultId = vaultWithDeletedEntries(repo)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            val armedAt = clock.wallMs()
            clock.advance(25 * hourMs)
            repo.lock()
            files.fail(FakeFiles.Op.WRITE_NEW)
            repo.unlock()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(id(1), id(2), id(3)), ids(repo))
            assertEquals(PurgeArm(vaultId, armedAt, Credit(25 * hourMs, clock.elapsedMs())), settings.purgeArm.value)
            assertNull("an automatic write that failed shows no save problem", repo.shown().saveProblem)
            relock(repo)
            assertEquals(listOf(id(2), id(3)), ids(repo))
        } finally {
            scope.cancel()
        }
    }
}
