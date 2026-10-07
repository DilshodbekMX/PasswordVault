package io.github.dilshodbekmx.passwordvault.core.lock.policy

import io.github.dilshodbekmx.passwordvault.core.lock.policy.SessionPolicy.Decision
import io.github.dilshodbekmx.passwordvault.core.lock.policy.SessionPolicy.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic of lock.md test T19 (session, §10.1 to §10.3) on `SessionPolicy`. The rest of T19
 * (a recreation ends no visit, a check that finishes after `ON_STOP` opens nothing, the order of
 * architecture.md §5.5) runs through the real `AutoLock` and the lock engine, in their packages.
 */
class SessionPolicyTest {

    private fun Decision.stays(): SessionPolicy = when (this) {
        is Decision.Stay -> next
        is Decision.Lock -> throw AssertionError("locked for $why")
    }

    private fun Decision.lockedFor(): Why? = (this as? Decision.Lock)?.why

    /** Unlocked at [at], in front; touched last at [at]. */
    private fun open(at: Long = T) = SessionPolicy.unlocked(at)

    // --- leaving and coming back ---

    @Test
    fun t19_theLockDelay() {
        for (delay in listOf(30 * SECOND, MINUTE, 5 * MINUTE)) {
            val away = open().stopped(T + SECOND, screenOn = true, delayMs = delay).stays()
            assertFalse(away.inFront)
            assertNull(away.started(T + SECOND + delay - 1, delay).lockedFor())
            assertEquals(Why.DELAY, away.started(T + SECOND + delay, delay).lockedFor())
            assertEquals(Why.DELAY, away.started(T + SECOND + 3 * delay, delay).lockedFor())
        }
        // E17: away for 31 s with the default of 30 s locks; 10 s does not.
        val away = open().stopped(T, true, 30 * SECOND).stays()
        assertEquals(Why.DELAY, away.started(T + 31 * SECOND, 30 * SECOND).lockedFor())
        assertNull(away.started(T + 10 * SECOND, 30 * SECOND).lockedFor())
    }

    @Test
    fun t19_immediatelyLocksWhenTheAppLeaves() {
        assertEquals(Why.DELAY, open().stopped(T + SECOND, true, 0).lockedFor())
    }

    @Test
    fun t19_aMissingSettingMeansImmediately() {
        assertEquals(0L, SessionPolicy.lockDelayMs(null))
        assertEquals(0L, SessionPolicy.lockDelayMs(-1))
        assertEquals(30 * SECOND, SessionPolicy.lockDelayMs(30 * SECOND))
        assertEquals(Why.DELAY, open().stopped(T + SECOND, true, null).lockedFor())
        // A missing setting at ON_START, after an absence that was covered by a grace or a delay.
        val away = open().stopped(T + SECOND, true, MINUTE).stays()
        assertEquals(Why.DELAY, away.started(T + 2 * SECOND, null).lockedFor())
    }

    // --- the screen ---

    @Test
    fun t19_screenOffLocksAtOnce() {
        assertEquals(Why.SCREEN_OFF, open().screenOff().lockedFor())
        assertEquals(Why.SCREEN_OFF, open().stopped(T + SECOND, screenOn = false, delayMs = 5 * MINUTE).lockedFor())
        val away = open().stopped(T + SECOND, true, 5 * MINUTE).stays()
        assertEquals(Why.SCREEN_OFF, away.screenOff().lockedFor())
        // E17: the file picker and then the screen off: locked.
        val picking = open().beginExternal(T + SECOND).stays()
        assertEquals(Why.SCREEN_OFF, picking.stopped(T + 2 * SECOND, screenOn = false, delayMs = 0).lockedFor())
        assertEquals(Why.SCREEN_OFF, picking.stopped(T + 2 * SECOND, true, 0).stays().screenOff().lockedFor())
    }

    // --- the no-touch timer ---

    @Test
    fun t19_fiveMinutesWithoutATouchInFrontLock() {
        val session = open()
        assertEquals(T + 5 * MINUTE, session.noTouchDeadlineMs)
        assertNull(session.checkTimer(T + 5 * MINUTE - 1).lockedFor())
        assertEquals(Why.NO_TOUCH, session.checkTimer(T + 5 * MINUTE).lockedFor())
        // Every touch starts it again.
        val touched = session.touched(T + 4 * MINUTE).stays().touched(T + 8 * MINUTE).stays()
        assertEquals(T + 13 * MINUTE, touched.noTouchDeadlineMs)
        assertNull(touched.checkTimer(T + 13 * MINUTE - 1).lockedFor())
        assertEquals(Why.NO_TOUCH, touched.checkTimer(T + 13 * MINUTE).lockedFor())
    }

    @Test
    fun t19_theNoTouchTimerStandsStillWhileTheAppIsAwayAndStartsAfreshOnItsReturn() {
        val away = open().touched(T + 4 * MINUTE).stays().stopped(T + 4 * MINUTE + SECOND, true, 5 * MINUTE).stays()
        assertNull(away.noTouchDeadlineMs)
        assertNull(away.checkTimer(T + 20 * MINUTE).lockedFor()) // no timer while away
        val back = away.started(T + 9 * MINUTE, 5 * MINUTE).stays() // away 4:59, under the delay
        assertTrue(back.inFront)
        assertEquals(T + 14 * MINUTE, back.noTouchDeadlineMs)
        assertNull(back.checkTimer(T + 14 * MINUTE - 1).lockedFor())
        assertEquals(Why.NO_TOUCH, back.checkTimer(T + 14 * MINUTE).lockedFor())
    }

    @Test
    fun t19_aTimerThatRanOutLocksWhicheverEventSeesItFirst() {
        val session = open()
        val late = T + 5 * MINUTE + 3
        assertEquals(Why.NO_TOUCH, session.touched(late).lockedFor())
        assertEquals(Why.NO_TOUCH, session.beginExternal(late).lockedFor())
        assertEquals(Why.NO_TOUCH, session.stopped(late, true, 5 * MINUTE).lockedFor())
    }

    // --- external screens (§10.3) ---

    @Test
    fun t19_sixMinutesInAFilePickerDoNotLock_elevenDo() {
        for (delay in listOf(0L, 30 * SECOND, 5 * MINUTE)) {
            val picking = open().beginExternal(T + SECOND).stays()
            val away = picking.stopped(T + SECOND + 700, true, delay).stays() // Immediately does not lock now
            assertNull(away.started(T + SECOND + 90 * SECOND, delay).lockedFor()) // E17: 90 s
            assertNull(away.started(T + SECOND + 6 * MINUTE, delay).lockedFor())
            assertNull(away.started(T + SECOND + 10 * MINUTE, delay).lockedFor())
            assertEquals(Why.EXTERNAL_TOO_LONG, away.started(T + SECOND + 10 * MINUTE + 1, delay).lockedFor())
            assertEquals(Why.EXTERNAL_TOO_LONG, away.started(T + SECOND + 11 * MINUTE, delay).lockedFor())
        }
    }

    @Test
    fun t19_theNoTouchTimerStandsStillWhileAnExternalScreenIsPendingInFront() {
        // The share sheet covers the app without stopping it.
        val sharing = open().beginExternal(T + MINUTE).stays()
        assertTrue(sharing.inFront)
        assertNull(sharing.checkTimer(T + MINUTE + 6 * MINUTE).lockedFor()) // 6 minutes: nothing
        assertNull(sharing.touched(T + MINUTE + 9 * MINUTE).stays().checkTimer(T + MINUTE + 14 * MINUTE).lockedFor())
        // The grace ends after 10 minutes and the timer starts afresh then.
        assertEquals(T + MINUTE + 15 * MINUTE, sharing.noTouchDeadlineMs)
        assertNull(sharing.checkTimer(T + MINUTE + 15 * MINUTE - 1).lockedFor())
        assertEquals(Why.NO_TOUCH, sharing.checkTimer(T + MINUTE + 15 * MINUTE).lockedFor())
        // A touch after the grace has ended starts the timer again.
        val back = sharing.touched(T + MINUTE + 12 * MINUTE).stays()
        assertEquals(T + MINUTE + 17 * MINUTE, back.noTouchDeadlineMs)
    }

    @Test
    fun t19_theGraceEndsWhenTheAppComesBack() {
        val back = open().beginExternal(T).stays().stopped(T + SECOND, true, 0).stays().started(T + MINUTE, 0).stays()
        assertEquals(T + MINUTE + 5 * MINUTE, back.noTouchDeadlineMs)
        assertEquals(Why.DELAY, back.stopped(T + 2 * MINUTE, true, 0).lockedFor()) // leaving again: Immediately
    }

    @Test
    fun t19_theGraceEndsWithThePickersResult() {
        val done = open().beginExternal(T).stays().endExternal(T + 2 * MINUTE).stays()
        assertEquals(T + 7 * MINUTE, done.noTouchDeadlineMs) // the timer starts afresh
        assertEquals(Why.DELAY, done.stopped(T + 3 * MINUTE, true, 0).lockedFor())
        assertEquals(T + 5 * MINUTE, open().endExternal(T + MINUTE).stays().noTouchDeadlineMs) // nothing was pending
    }

    @Test
    fun t19_theGraceEndsTenMinutesAfterItStarted() {
        val sharing = open().beginExternal(T).stays()
        assertEquals(Why.DELAY, sharing.stopped(T + 10 * MINUTE + 1, true, 0).lockedFor())
        assertNull(sharing.stopped(T + 10 * MINUTE, true, 0).lockedFor())
        // A second picker starts its own 10 minutes.
        val again = sharing.beginExternal(T + 8 * MINUTE).stays()
        val away = again.stopped(T + 8 * MINUTE + SECOND, true, 0).stays()
        assertNull(away.started(T + 17 * MINUTE, 0).lockedFor())
        assertEquals(Why.EXTERNAL_TOO_LONG, away.started(T + 18 * MINUTE + 1, 0).lockedFor())
    }

    @Test
    fun t19_aResultThatArrivesBeforeOnStartKeepsTheGraceOfThisAbsence() {
        // Which of the two the activity sees first is not ours to choose; the answer must not depend on it.
        val away = open().beginExternal(T).stays().stopped(T + SECOND, true, 0).stays()
        val resultFirst = away.endExternal(T + 3 * MINUTE).stays()
        assertNull(resultFirst.started(T + 3 * MINUTE, 0).lockedFor())
        assertEquals(Why.EXTERNAL_TOO_LONG, resultFirst.started(T + 11 * MINUTE, 0).lockedFor())
        val startFirst = away.started(T + 3 * MINUTE, 0).stays().endExternal(T + 3 * MINUTE).stays()
        assertEquals(T + 8 * MINUTE, startFirst.noTouchDeadlineMs)
    }

    @Test
    fun t19_theExternalCapAppliesWhateverTheDelay() {
        // Five minutes of delay do not stretch the 10 minutes, and the grace replaces the delay.
        val away = open().beginExternal(T).stays().stopped(T + SECOND, true, 5 * MINUTE).stays()
        assertEquals(Why.EXTERNAL_TOO_LONG, away.started(T + 12 * MINUTE, 5 * MINUTE).lockedFor())
        assertNull(away.started(T + 9 * MINUTE, 30 * SECOND).lockedFor())
    }

    // --- odd inputs ---

    @Test
    fun t19_aRepeatedEventChangesNothing() {
        val session = open()
        assertEquals(session.noTouchDeadlineMs, session.started(T + MINUTE, 0).stays().noTouchDeadlineMs)
        val away = session.stopped(T + SECOND, true, MINUTE).stays()
        assertNull(away.stopped(T + 2 * SECOND, true, 0).stays().started(T + 30 * SECOND, MINUTE).lockedFor())
        assertNull(away.touched(T + 2 * SECOND).stays().beginExternal(T + 3 * SECOND).stays().started(T + 4 * SECOND, MINUTE).lockedFor())
    }

    @Test
    fun t19_aTimeThatRunsBackwardsCountsAsRunOut() {
        val away = open().stopped(T + MINUTE, true, 5 * MINUTE).stays()
        assertEquals(Why.DELAY, away.started(T, 5 * MINUTE).lockedFor())
        assertEquals(Why.NO_TOUCH, open().checkTimer(T - 1).lockedFor())
        val picking = open().beginExternal(T).stays().stopped(T + SECOND, true, 0).stays()
        assertEquals(Why.EXTERNAL_TOO_LONG, picking.started(T - SECOND, 0).lockedFor())
    }

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60 * SECOND

        /** elapsedRealtime() at the unlock: three hours after the boot. */
        const val T = 3 * 60 * MINUTE
    }
}
