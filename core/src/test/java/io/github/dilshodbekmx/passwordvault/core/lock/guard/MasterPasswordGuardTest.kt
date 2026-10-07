package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T9, the master-password guard (§6.8): tries 1 to 5 without a wait; 30 s before each
 * later try, never more; after a simulated reboot the remaining wait is 30 s minus uptime; a failed
 * write does not stop the attempt; a backup file's password never waits; a vault that was put aside
 * uses its own guard; out-of-memory is not counted; a result that arrives after the visit has ended
 * opens nothing. Then what a right password brings (step 5) and the record rules of §5.3.
 */
class MasterPasswordGuardTest {
    private val clock = FakeClock(elapsedMs = 5 * HOUR)
    private val store = FakeRecordStore<StateRecord>(state(), now = clock::elapsedMs)
    private val visit = Place.visit(1)
    private var checks = 0

    private fun guard(store: FakeRecordStore<StateRecord> = this.store, vault: String = VAULT) =
        MasterPasswordGuard(vault, store, clock).also { it.load() }

    private fun MasterPasswordGuard.tryWith(result: MpCheck, at: Place = visit): MpAttempt = runNow {
        attempt(at) {
            checks++
            clock.advance(700) // Argon2id
            result
        }
    }

    private fun MasterPasswordGuard.wrongTimes(n: Int, at: Place = visit) =
        repeat(n) { assertEquals(MpAttempt.Checked(MpCheck.WRONG, false, false), tryWith(MpCheck.WRONG, at)) }

    @Test
    fun t9_triesOneToFiveWithoutAWait() {
        val guard = guard()
        var lastStart = 0L
        repeat(5) {
            assertNull(guard.waitUntilElapsedMs())
            lastStart = clock.elapsedMs()
            assertEquals(MpAttempt.Checked(MpCheck.WRONG, false, false), guard.tryWith(MpCheck.WRONG))
        }
        assertEquals(StateRecord.MpGuard(failures = 5, lastFailElapsedMs = lastStart, unseen = 5), guard.counts)
        assertEquals(guard.counts, store.current!!.mpGuard)
        assertEquals(5, checks)

        // the sixth waits 30 s from the start of the fifth, and nothing runs meanwhile
        assertEquals(lastStart + 30_000, guard.waitUntilElapsedMs())
        assertEquals(MpAttempt.Wait(lastStart + 30_000), guard.tryWith(MpCheck.RIGHT))
        assertEquals(5, checks)
        assertEquals(5, guard.counts.failures)
    }

    @Test
    fun t9_thirtySecondsBeforeEachLaterTryNeverMore() {
        val guard = guard()
        guard.wrongTimes(5)
        repeat(40) { n ->
            val until = guard.waitUntilElapsedMs()!!
            assertEquals(30_000L, until - store.current!!.mpGuard.lastFailElapsedMs!!) // never more than 30 s
            clock.advance(until - clock.elapsedMs() - 1)
            assertTrue(guard.tryWith(MpCheck.WRONG) is MpAttempt.Wait)
            clock.advance(1)
            assertNull(guard.waitUntilElapsedMs())
            assertEquals(MpCheck.WRONG, (guard.tryWith(MpCheck.WRONG) as MpAttempt.Checked).check)
            assertEquals(6 + n, guard.counts.failures)
        }
        clock.advance(HOUR)
        assertNull(guard.waitUntilElapsedMs())
        // about 2,880 tries a day at most: one per 30 s of uptime
        assertEquals(45, checks)
    }

    @Test
    fun t9_afterARebootTheRemainingWaitIs30SecondsMinusUptime() {
        guard().wrongTimes(5)
        clock.reboot(offMs = 10 * MINUTE, uptimeMs = 12_000)
        val afterReboot = guard() // a new process
        assertEquals(30_000L, afterReboot.waitUntilElapsedMs()) // 18 s left of 30
        assertEquals(MpAttempt.Wait(30_000L), afterReboot.tryWith(MpCheck.WRONG))
        clock.advance(18_000)
        assertNull(afterReboot.waitUntilElapsedMs())

        clock.reboot(uptimeMs = 40_000) // up for longer than the wait: nothing left
        assertNull(guard().waitUntilElapsedMs())
    }

    @Test
    fun t9_theWaitIsMeasuredByUptimeNotByTheDate() {
        val guard = guard()
        guard.wrongTimes(5)
        val until = guard.waitUntilElapsedMs()
        clock.jumpWall(365 * DAY) // the date set a year ahead
        assertEquals(until, guard.waitUntilElapsedMs())
        clock.jumpWall(-2 * 365 * DAY)
        assertEquals(until, guard.waitUntilElapsedMs())
    }

    @Test
    fun t9_aFailedWriteDoesNotStopTheAttempt() {
        val guard = guard()
        store.failNextWrite()
        assertEquals(MpCheck.WRONG, (guard.tryWith(MpCheck.WRONG) as MpAttempt.Checked).check)
        assertEquals(1, checks)
        assertEquals(1, guard.counts.failures) // kept in memory for this process
        assertEquals(0, store.current!!.mpGuard.failures)

        store.full = true // the phone's storage is full: every try still runs, and the wait runs from memory
        guard.wrongTimes(4)
        assertEquals(5, checks)
        assertTrue(guard.tryWith(MpCheck.RIGHT) is MpAttempt.Wait)
        assertEquals(0, store.current!!.mpGuard.failures)
    }

    @Test
    fun t9_theTryIsCountedBeforeArgon2idRuns() {
        val guard = guard()
        runNow {
            guard.attempt(visit) {
                // a kill here, during the derivation, leaves the try counted on disk
                assertEquals(StateRecord.MpGuard(1, clock.elapsedMs(), 1), store.current!!.mpGuard)
                MpCheck.WRONG
            }
        }
        try {
            runNow { guard.attempt(visit) { throw IOException("the process went away") } }
            throw AssertionError("the exception should have gone on")
        } catch (expected: IOException) {
        }
        assertEquals(2, guard.counts.failures) // a try that did not finish stays counted
    }

    @Test
    fun t9_aBackupFilesPasswordNeverWaits() {
        val outside = MasterPasswordGuard.forOutsideFile()
        assertTrue(outside.isOutsideFile)
        assertNull(outside.vaultId)
        assertEquals(RecordRead.Missing, outside.load())
        repeat(20) {
            assertNull(outside.waitUntilElapsedMs())
            assertEquals(MpAttempt.Checked(MpCheck.WRONG, false, false), outside.tryWith(MpCheck.WRONG))
        }
        assertEquals(20, checks)
        assertEquals(StateRecord.MpGuard(0, null, 0), outside.counts)
        assertTrue(outside.unseenShown(3))
        assertFalse(outside.update { it })
        // and the vault's own guard knows nothing of those tries
        assertEquals(0, store.writes.size)
        assertNull(guard().waitUntilElapsedMs())
    }

    @Test
    fun t9_aVaultPutAsideUsesItsOwnGuard() {
        val asideStore = FakeRecordStore(state(vault = ASIDE), now = clock::elapsedMs)
        val current = guard()
        val aside = guard(asideStore, ASIDE)
        aside.wrongTimes(5)
        assertTrue(aside.tryWith(MpCheck.WRONG) is MpAttempt.Wait)
        assertEquals(5, asideStore.current!!.mpGuard.failures)
        // the current vault is not slowed down by guesses at the other one, and the other way round
        assertNull(current.waitUntilElapsedMs())
        current.wrongTimes(3)
        assertEquals(3, store.current!!.mpGuard.failures)
        assertEquals(5, asideStore.current!!.mpGuard.failures)
        assertEquals(ASIDE, asideStore.current!!.vaultId)
    }

    @Test
    fun t9_outOfMemoryIsNotCounted() {
        val guard = guard()
        guard.wrongTimes(4)
        val counts = guard.counts
        repeat(3) {
            assertEquals(MpAttempt.Checked(MpCheck.NOT_COUNTED, false, false), guard.tryWith(MpCheck.NOT_COUNTED))
            assertEquals(counts, guard.counts)
            assertEquals(counts, store.current!!.mpGuard)
        }
        assertNull(guard.waitUntilElapsedMs()) // still the fifth free try
        guard.wrongTimes(1)
        assertTrue(guard.waitUntilElapsedMs() != null)
    }

    @Test
    fun t9_aResultThatArrivesAfterTheVisitEndedOpensNothing() {
        val guard = guard()
        val ended = runNow {
            guard.attempt(visit) {
                visit.end() // the screen went off while Argon2id ran
                MpCheck.RIGHT
            }
        }
        assertEquals(MpAttempt.Checked(MpCheck.RIGHT, tooLate = true, pendingCancelled = false), ended)
        assertEquals(0, store.current!!.mpGuard.failures) // the records are updated as usual
        val prompt = Place.prompt(2)
        val wrong = runNow { guard.attempt(prompt) { prompt.end(); MpCheck.WRONG } }
        assertEquals(MpAttempt.Checked(MpCheck.WRONG, tooLate = true, pendingCancelled = false), wrong)
        assertEquals(1, store.current!!.mpGuard.failures)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 5: what a right password brings

    @Test
    fun aRightPasswordStartsTheCountAgainAndBringsStep5() {
        store.set(state(failures = 7, last = 1_000L, unseen = 4, practice = StateRecord.Practice(clock.wallMs() - DAY, 1, clock.wallMs() + HOUR), pending = pending(), mpChange = StateRecord.MpChange(VK2, 5L)))
        val guard = guard()
        clock.advance(HOUR) // past the wait
        val right = guard.tryWith(MpCheck.RIGHT, Place.prompt(3))
        assertEquals(MpAttempt.Checked(MpCheck.RIGHT, tooLate = false, pendingCancelled = true), right)
        val r = store.current!!
        assertEquals(StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 4), r.mpGuard)
        assertEquals(StateRecord.Practice(clock.wallMs(), 2, null), r.practice) // last_ok now, step up after 20 h, no snooze
        assertNull(r.pendingMp) // F11 step 9
        assertNull(r.mpChange)
        assertEquals(state().maxGeneration, r.maxGeneration)
    }

    @Test
    fun aRightPasswordTakesOffOnlyItsOwnSlipsOfTheLastMinuteAtMostTwo() {
        val guard = guard()
        guard.wrongTimes(3) // within a few seconds, on this visit
        assertEquals(3, guard.counts.unseen)
        guard.tryWith(MpCheck.RIGHT)
        assertEquals(1, guard.counts.unseen)

        val other = guard(FakeRecordStore(state(), now = clock::elapsedMs))
        val first = Place.visit(7)
        other.wrongTimes(1, first)
        other.placeEnded(first) // the visit ends: its slip is not its own any more
        other.tryWith(MpCheck.RIGHT, Place.visit(8))
        assertEquals(1, other.counts.unseen)

        val late = guard(FakeRecordStore(state(), now = clock::elapsedMs))
        late.wrongTimes(1)
        clock.advance(61_000)
        late.tryWith(MpCheck.RIGHT)
        assertEquals(1, late.counts.unseen)

        val inPrompt = guard(FakeRecordStore(state(), now = clock::elapsedMs))
        val prompt = Place.prompt(9)
        inPrompt.wrongTimes(1, prompt)
        inPrompt.tryWith(MpCheck.RIGHT, prompt)
        assertEquals(1, inPrompt.counts.unseen) // nothing is forgiven in a prompt
    }

    @Test
    fun theNoticeTakesOffWhatItShowed() {
        val guard = guard()
        guard.wrongTimes(3)
        assertTrue(guard.unseenShown(2))
        assertEquals(1, store.current!!.mpGuard.unseen)
        assertEquals(3, store.current!!.mpGuard.failures)
        assertTrue(guard.unseenShown(5))
        assertEquals(0, store.current!!.mpGuard.unseen)
    }

    // ---------------------------------------------------------------------------------------------
    // The record (lock.md §5.3)

    @Test
    fun aMissingOrDamagedRecordIsNotWrittenUntilANewOneIsEstablished() {
        for (result in listOf<RecordRead<StateRecord>>(RecordRead.Missing, RecordRead.Damaged(ParseError(3, "hash")))) {
            val s = FakeRecordStore<StateRecord>(now = clock::elapsedMs)
            if (result is RecordRead.Damaged) s.readAs = result
            val guard = guard(s)
            assertEquals(result, guard.lastRead)
            assertNull(guard.record)
            guard.wrongTimes(5)
            assertTrue(guard.waitUntilElapsedMs() != null) // the wait runs from memory
            assertEquals(0, s.writes.size)
            assertFalse(guard.update { it.copy(mpChange = null) })
            clock.advance(30_000) // past the wait, and within a minute of the last two slips
            guard.tryWith(MpCheck.RIGHT)
            assertEquals(0, s.writes.size)

            s.readAs = null
            val fresh = state(practice = StateRecord.Practice(clock.wallMs(), 0, null))
            assertTrue(guard.establish(fresh))
            assertEquals(fresh.copy(mpGuard = StateRecord.MpGuard(0, null, 3)), s.current) // five wrong, two forgiven
            guard.wrongTimes(1)
            assertEquals(1, s.current!!.mpGuard.failures)
        }
    }

    @Test
    fun aNewerOrUnreadableRecordIsNeverRewritten() {
        for (result in listOf<RecordRead<StateRecord>>(RecordRead.Newer, RecordRead.Unreadable)) {
            val s = FakeRecordStore(state(), now = clock::elapsedMs)
            s.readAs = result
            val guard = guard(s)
            guard.wrongTimes(5)
            assertTrue(guard.waitUntilElapsedMs() != null)
            assertFalse(guard.update { it.copy(mpChange = null) })
            assertTrue(guard.unseenShown(1))
            assertEquals(0, s.writes.size)
        }
        val newer = FakeRecordStore(state(), now = clock::elapsedMs).also { it.readAs = RecordRead.Newer }
        assertFalse(guard(newer).establish(state()))
        assertEquals(0, newer.writes.size)
        val unreadable = FakeRecordStore(state(), now = clock::elapsedMs).also { it.readAs = RecordRead.Unreadable }
        assertTrue(guard(unreadable).establish(state())) // "Set up again"
    }

    @Test
    fun otherFieldsChangeThroughUpdateAndTheCountsDoNot() {
        val guard = guard()
        guard.wrongTimes(2)
        assertTrue(guard.update { it.copy(mpChange = StateRecord.MpChange(VK2, 9L)) })
        assertEquals(StateRecord.MpChange(VK2, 9L), store.current!!.mpChange)
        assertEquals(2, store.current!!.mpGuard.failures)
        try {
            guard.update { it.copy(mpGuard = StateRecord.MpGuard(0, null, 0)) }
            throw AssertionError("the counts were changed")
        } catch (expected: IllegalArgumentException) {
        }
        // a write that must succeed (F11 step 15) leaves the record as it was when it fails
        store.failNextWrite()
        assertFalse(guard.update(bestEffort = false) { it.copy(mpChange = null) })
        assertEquals(StateRecord.MpChange(VK2, 9L), guard.record!!.mpChange)
        // a best-effort one keeps the change for the next write
        store.failNextWrite()
        assertFalse(guard.update { it.copy(mpChange = null) })
        assertNull(guard.record!!.mpChange)
        guard.wrongTimes(1)
        assertNull(store.current!!.mpChange)
    }

    private fun pending() = StateRecord.PendingMp(
        requestedMs = clock.wallMs() - HOUR,
        by = Shortcut.PIN,
        creditedMs = HOUR,
        seenElapsedMs = clock.elapsedMs(),
        vkId = VK2,
        salt = ByteArray(16) { 1 },
        nonce = ByteArray(12) { 2 },
        ct = ByteArray(48) { 3 },
    )

    private fun state(
        vault: String = VAULT,
        failures: Int = 0,
        last: Long? = null,
        unseen: Int = 0,
        practice: StateRecord.Practice = StateRecord.Practice(WALL - 2 * DAY, 1, null),
        pending: StateRecord.PendingMp? = null,
        mpChange: StateRecord.MpChange? = null,
    ) = StateRecord(
        vaultId = vault,
        practice = practice,
        mpGuard = StateRecord.MpGuard(failures, last, unseen),
        mpChange = mpChange,
        pendingMp = pending,
        replaced = null,
        maxGeneration = StateRecord.MaxGeneration(57, WALL - DAY),
    )

    private companion object {
        const val VAULT = "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70"
        const val ASIDE = "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4"
        const val VK2 = "3a1d0000-0000-4000-8000-000000000002"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WALL = 1_791_244_800_000L
    }
}
