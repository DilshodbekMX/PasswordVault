package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.RIGHT
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinFixture.Companion.pin
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T8, forgiving (§6.3 E.6, §6.4): on one visit a right PIN forgives the wrong PINs of
 * the last 60 seconds, at most two; nothing else forgives, and nothing else takes off `unseen`.
 * "Both" below are `total` and `unseen.count`.
 */
class ForgivingTest {
    private val f = PinFixture()
    private val wrong = listOf(pin("111222"), pin("905127"), pin("368014"), pin("143637"))
    private val startUnseen = PinRecord.Unseen(1, 1_790_000_000_000L, false)

    private fun PinGuard.both(): Pair<Int, Int> = record!!.guard.total to record!!.unseen.count

    private fun PinGuard.right(at: Place): PinOutcome = enter(RIGHT, at).also { assertTrue(it is PinOutcome.Right); flush() }

    @Test
    fun t8_wrongWrongRightWithin60SecondsLeavesBothAsBefore() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        assertEquals(PinOutcome.Wrong(4), guard.enter(wrong[0], visit)) // row 1, total 4: min(4, 6)
        assertEquals(PinOutcome.Wrong(3), guard.enter(wrong[1], visit)) // row 2, total 5: min(3, 5)
        assertEquals(5 to 3, guard.both())
        guard.right(visit)
        assertEquals(3 to 1, guard.both())
        assertEquals(0, guard.record!!.guard.row)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 3), f.store.current!!.guard)
        assertEquals(1, f.store.current!!.unseen.count)
    }

    @Test
    fun t8_wrongThreeTimesThenRightLeavesBothOneHigher() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        for (i in 0..2) assertTrue(guard.enter(wrong[i], visit) is PinOutcome.Wrong)
        guard.right(visit)
        assertEquals(4 to 2, guard.both()) // at most two are forgiven
    }

    @Test
    fun t8_fourWrongThenRightForgivesTwo() {
        val guard = f.started()
        val visit = Place.visit(1)
        for (i in 0..3) assertTrue(guard.enter(wrong[i], visit) is PinOutcome.Wrong)
        assertEquals(4 to 4, guard.both())
        guard.right(visit)
        assertEquals(2 to 2, guard.both())
    }

    @Test
    fun t8_wrong61SecondsRightLeavesBothOneHigher() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        assertTrue(guard.enter(wrong[0], visit) is PinOutcome.Wrong)
        f.clock.advance(60_000) // the right PIN is checked 61 s after the wrong one (the charge ages 1 s)
        guard.right(visit)
        assertEquals(4 to 2, guard.both())
    }

    @Test
    fun t8_theWindowIsAtMost60SecondsBetweenTheChecks() {
        val atSixty = f.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        assertTrue(atSixty.enter(wrong[0], visit) is PinOutcome.Wrong)
        f.clock.advance(59_000) // checked exactly 60 s after the wrong one: still forgiven
        atSixty.right(visit)
        assertEquals(3 to 1, atSixty.both())

        val past = f.started(total = 3, unseen = startUnseen)
        val other = Place.visit(2)
        assertTrue(past.enter(wrong[1], other) is PinOutcome.Wrong)
        f.clock.advance(59_001) // 60.001 s
        past.right(other)
        assertEquals(4 to 2, past.both())
    }

    @Test
    fun t8_wrongTheVisitEndsRightLeavesBothOneHigher() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val first = Place.visit(1)
        assertTrue(guard.enter(wrong[0], first) is PinOutcome.Wrong)
        guard.placeEnded(first) // the app was left, or the screen went off
        guard.right(Place.visit(2)) // a few seconds later, on the next visit
        assertEquals(4 to 2, guard.both())
    }

    @Test
    fun t8_inAPromptNothingIsForgivenNotEvenLaterOnTheLockScreen() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val prompt = Place.prompt(1)
        assertTrue(guard.enter(wrong[0], prompt) is PinOutcome.Wrong)
        guard.right(prompt)
        assertEquals(4 to 2, guard.both())
        guard.placeEnded(prompt)
        // the app locks; the next right PIN on the lock screen does not forgive the prompt's wrong PIN
        guard.right(Place.visit(2))
        assertEquals(4 to 2, guard.both())
    }

    @Test
    fun t8_aFingerprintUnlockOnTheSameVisitChangesNoUnseen() {
        val guard = f.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        assertTrue(guard.enter(wrong[0], visit) is PinOutcome.Wrong)
        assertEquals(PinCharge.CHARGED, guard.charge(visit)) // a digit typed, then the finger
        guard.giveUp()
        guard.placeEnded(visit) // unlocked by fingerprint
        assertEquals(4 to 2, guard.both())
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 4), f.store.current!!.guard)
        assertEquals(2, f.store.current!!.unseen.count)
    }

    @Test
    fun t8_aMasterPasswordGivesZeroAndZeroAndChangesNoUnseen() {
        val guard = f.started(row = 1, total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        assertTrue(guard.enter(wrong[0], visit) is PinOutcome.Wrong)
        assertTrue(guard.enter(wrong[1], visit) is PinOutcome.Wrong)
        guard.masterPasswordCorrect() // "Forgot PIN? Use master password" on the same visit
        guard.placeEnded(visit)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), guard.record!!.guard)
        assertEquals(3, guard.record!!.unseen.count)
        assertEquals(guard.record, f.store.current)
    }

    @Test
    fun t8_aRightPinWhoseVisitEndedDuringTheCheckCountsAsAnyRightPin() = runBlocking {
        val clock = FakeClock(elapsedMs = 3_600_000L)
        val held = PinFixture(clock)
        val guard = held.started(total = 3, unseen = startUnseen)
        val visit = Place.visit(1)
        clock.autoAdvance = true
        assertTrue(guard.enter(wrong[0], visit) is PinOutcome.Wrong)
        clock.autoAdvance = false
        assertEquals(PinCharge.CHARGED, guard.charge(visit))
        val check = async(start = CoroutineStart.UNDISPATCHED) { guard.check(RIGHT, setOf(PinFixture.VK_ID)) }
        visit.end() // the screen goes off while "Opening…" is shown
        clock.advance(1_000)
        yield()
        assertEquals(PinOutcome.TooLate, check.await())
        assertEquals(3 to 1, guard.both()) // counted as right, the visit's wrong PIN forgiven
        assertEquals(guard.record, held.store.current) // written at once
    }

    @Test
    fun t8_whenNothingIsLeftUnseenTheTimeOfTheLastSlipGoesToo() {
        val guard = f.started()
        val visit = Place.visit(1)
        assertTrue(guard.enter(wrong[0], visit) is PinOutcome.Wrong)
        assertEquals(PinRecord.Unseen(1, f.clock.wallMs(), false), guard.record!!.unseen)
        guard.right(visit)
        assertEquals(PinRecord.Unseen(0, null, false), guard.record!!.unseen)
    }
}
