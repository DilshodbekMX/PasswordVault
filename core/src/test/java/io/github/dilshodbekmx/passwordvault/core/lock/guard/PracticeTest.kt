package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** lock.md test T11: practice (§8.3), on `PracticeSchedule`. */
class PracticeTest {

    /** A wall-clock time: 2026-10-06 09:00 UTC, so that every test is in the real era of the clock. */
    private val t0 = 1_791_277_200_000L

    @Test
    fun t11_dueOnDays1_3_7_21_35WithTimelyAnswers() {
        var practice = PracticeSchedule.newMasterPassword(t0) // the master password is set on day 0
        val dueDays = ArrayList<Long>()
        repeat(5) {
            val due = practice.dueMs
            assertEquals(Status.NOT_DUE, practice.status(due - 1, false))
            assertEquals(Status.DUE, practice.status(due, false))
            dueDays.add((due - t0) / DAY)
            practice = practice.masterPasswordTyped(due) // answered on the day it is asked
        }
        assertEquals(listOf(1L, 3L, 7L, 21L, 35L), dueDays)
        assertEquals(3, practice.step)
    }

    @Test
    fun t11_theStepGoesUpOnlyWhen20HoursHavePassedSinceTheLastCorrectMasterPassword() {
        val set = PracticeSchedule.newMasterPassword(t0)
        val early = set.masterPasswordTyped(t0 + 20 * HOUR - 1) // for a backup, the same evening
        assertEquals(PracticeSchedule(t0 + 20 * HOUR - 1, 0, null), early)
        assertEquals(t0 + 20 * HOUR - 1 + DAY, early.dueMs) // the next day counts from then
        assertEquals(PracticeSchedule(t0 + 20 * HOUR, 1, null), set.masterPasswordTyped(t0 + 20 * HOUR))
        val top = PracticeSchedule(t0, 3, null).masterPasswordTyped(t0 + 30 * DAY)
        assertEquals(3, top.step) // at most 3
        // A clock set back gives no step either.
        assertEquals(1, PracticeSchedule(t0, 1, null).masterPasswordTyped(t0 - 5 * DAY).step)
    }

    @Test
    fun t11_theGraceIs1_2_4_then7Days() {
        val graces = (0..3).map { step ->
            val practice = PracticeSchedule(t0, step, null)
            val grace = practice.graceEndsMs - practice.dueMs
            assertEquals(Status.DUE, practice.status(practice.graceEndsMs - 1, false))
            assertEquals(Status.REQUIRED, practice.status(practice.graceEndsMs, false))
            assertEquals(Status.REQUIRED, practice.status(practice.graceEndsMs + 365 * DAY, false))
            grace / DAY
        }
        assertEquals(listOf(1L, 2L, 4L, 7L), graces)
        assertEquals(listOf(1L, 2L, 4L, 14L), (0..3).map { PracticeSchedule.intervalMs(it) / DAY })
    }

    @Test
    fun t11_notNowSnoozesFor20Hours() {
        val practice = PracticeSchedule(t0, 3, null) // due on day 14, grace until day 21
        val asked = practice.dueMs + 2 * HOUR
        assertEquals(Status.DUE, practice.status(asked, false))
        val snoozed = practice.notNow(asked)
        assertEquals(asked + 20 * HOUR, snoozed.snoozedUntilMs)
        assertEquals(Status.SNOOZED, snoozed.status(asked, false))
        assertEquals(Status.SNOOZED, snoozed.status(asked + 20 * HOUR - 1, false))
        assertEquals(Status.DUE, snoozed.status(asked + 20 * HOUR, false)) // asked again, at most once a day
        // A correct master password clears the snooze.
        assertNull(snoozed.masterPasswordTyped(asked + HOUR).snoozedUntilMs)
    }

    @Test
    fun t11_aSnoozeNeverOutlastsTheGrace() {
        val practice = PracticeSchedule(t0, 0, null) // due on day 1, grace until day 2
        val late = practice.graceEndsMs - HOUR
        val snoozed = practice.notNow(late)
        assertEquals(Status.SNOOZED, snoozed.status(practice.graceEndsMs - 1, false))
        assertEquals(Status.REQUIRED, snoozed.status(practice.graceEndsMs, false)) // no "Not now" after the grace
    }

    @Test
    fun t11_aClockSetBackByMoreThanAnHourMeansDue() {
        val practice = PracticeSchedule(t0, 3, null) // not due for 14 days
        assertEquals(Status.NOT_DUE, practice.status(t0 - 30 * MINUTE, false))
        assertEquals(Status.NOT_DUE, practice.status(t0 - HOUR, false))
        assertEquals(Status.DUE, practice.status(t0 - HOUR - 1, false))
        assertEquals(Status.DUE, practice.status(t0 - 400 * DAY, false))
        // Due, not required, and "Not now" still holds for 20 hours on the clock as it is now.
        val back = t0 - 3 * DAY
        val snoozed = practice.notNow(back)
        assertEquals(Status.SNOOZED, snoozed.status(back + 19 * HOUR, false))
        assertEquals(Status.DUE, snoozed.status(back + 20 * HOUR, false))
    }

    @Test
    fun t11_aSnoozeFromBeforeTheClockWentBackDoesNotHold() {
        val practice = PracticeSchedule(t0, 0, null)
        val snoozed = practice.notNow(practice.dueMs + HOUR) // snoozed on day 1
        // The clock is set back two days: the snooze would otherwise last two days and twenty hours.
        assertEquals(Status.DUE, snoozed.status(practice.dueMs + HOUR - 2 * DAY, false))
        // Within an hour of the moment it was set, it still holds.
        assertEquals(Status.SNOOZED, snoozed.status(practice.dueMs + HOUR - 59 * MINUTE, false))
    }

    @Test
    fun t11_aClockSetAheadMakesItRequired() {
        // The wall clock decides no security question (lock.md I8); a year ahead only asks for practice.
        val practice = PracticeSchedule.newMasterPassword(t0)
        assertEquals(Status.REQUIRED, practice.status(t0 + 365 * DAY, false))
    }

    @Test
    fun t11_aRestoreGivesStep3() {
        val restored = PracticeSchedule.restored(t0)
        assertEquals(PracticeSchedule(t0, 3, null), restored)
        assertEquals(t0 + 14 * DAY, restored.dueMs)
        assertEquals(t0 + 21 * DAY, restored.graceEndsMs)
    }

    @Test
    fun t11_aNewMasterPasswordStartsAgainAtStep0() {
        val practice = PracticeSchedule(t0, 3, t0 + HOUR)
        assertEquals(PracticeSchedule(t0 + 10 * DAY, 0, null), PracticeSchedule.newMasterPassword(t0 + 10 * DAY))
        assertEquals(3, practice.step)
    }

    @Test
    fun t11_notAskedWhileANewMasterPasswordIsPending() {
        for (step in 0..3) {
            val practice = PracticeSchedule(t0, step, null)
            for (now in listOf(t0, practice.dueMs, practice.graceEndsMs, practice.graceEndsMs + 100 * DAY, t0 - 2 * DAY)) {
                assertEquals(Status.NOT_ASKED, practice.status(now, true))
                assertNull(practice.bannerSinceMs(now, true))
            }
        }
    }

    @Test
    fun t11_theStandingBannerIsDueExactlyWhilePastTheGraceAndNothingIsPending() {
        for (step in 0..3) {
            for (snooze in listOf(null, t0 + 15 * DAY)) {
                val practice = PracticeSchedule(t0, step, snooze)
                var now = t0 - 3 * DAY
                while (now < t0 + 40 * DAY) {
                    for (pending in listOf(false, true)) {
                        val pastGrace = now >= practice.graceEndsMs
                        val expected = if (pastGrace && !pending) t0 else null
                        assertEquals("step $step, at ${now - t0} ms, pending $pending", expected, practice.bannerSinceMs(now, pending))
                        assertEquals(pastGrace && !pending, practice.status(now, pending) == Status.REQUIRED)
                    }
                    now += 7 * HOUR + 13 * MINUTE
                }
            }
        }
    }

    @Test
    fun t11_aMissingOrDamagedRecordMeansDueNowAndStillSkippable() {
        assertEquals(Status.DUE, PracticeSchedule.STATUS_WITHOUT_RECORD)
        // The record written after the next unlock: after a master password, practice starts afresh;
        // after a PIN or fingerprint, it stays due, with its one day of grace.
        val byPassword = PracticeSchedule.afterMissingRecord(t0, byMasterPassword = true)
        assertEquals(PracticeSchedule(t0, 0, null), byPassword)
        assertEquals(Status.NOT_DUE, byPassword.status(t0, false))
        val byPin = PracticeSchedule.afterMissingRecord(t0, byMasterPassword = false)
        assertEquals(PracticeSchedule(t0 - DAY, 0, null), byPin)
        assertEquals(Status.DUE, byPin.status(t0, false))
        assertEquals(Status.DUE, byPin.status(t0 + DAY - 1, false))
        assertEquals(Status.REQUIRED, byPin.status(t0 + DAY, false))
    }

    @Test
    fun aStepOutsideTheRangeCountsAs0() {
        for (step in listOf(-1, 4, 99)) {
            val practice = PracticeSchedule(t0, step, null)
            assertEquals(t0 + DAY, practice.dueMs)
            assertEquals(t0 + 2 * DAY, practice.graceEndsMs)
            assertEquals(1, practice.masterPasswordTyped(t0 + DAY).step)
        }
        assertEquals(DAY, PracticeSchedule.intervalMs(7))
    }

    @Test
    fun timesAtTheEndsOfLongDoNotWrapRound() {
        val far = PracticeSchedule(Long.MAX_VALUE - HOUR, 3, null)
        assertEquals(Long.MAX_VALUE, far.dueMs)
        assertEquals(Status.DUE, far.status(t0, false)) // the clock is long before last_ok
        assertEquals(Status.DUE, PracticeSchedule(t0, 0, Long.MAX_VALUE).status(t0 + DAY, false)) // a snooze from beyond
        val past = PracticeSchedule(Long.MIN_VALUE + HOUR, 0, null)
        assertEquals(Status.REQUIRED, past.status(t0, false))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
