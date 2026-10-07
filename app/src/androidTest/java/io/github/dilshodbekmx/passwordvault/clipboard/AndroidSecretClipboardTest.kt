package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.PendingClip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Build plan 3.8: [AndroidSecretClipboard]'s rules (architecture.md D-14, ux.md §6) against a
 * clipboard, an alarm, settings and a clock in memory ([ClipRig]). The parts that touch the phone
 * have their own tests ([AndroidSystemClipTest], [ClearAlarmDeviceTest]), and the whole of it runs in
 * `tools/emu/clipboard/clear.sh`.
 */
class AndroidSecretClipboardTest {
    private val rig = ClipRig()
    private val password: UiText = UiText.of(R.string.list_copied_password, "Gmail")
    private val username: UiText = UiText.of(R.string.list_copied_username, "Gmail")

    // ---- a copy ------------------------------------------------------------------------------

    @Test
    fun aCopyPutsTheTextWithTheFlagAndARandomToken() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)

        val set = rig.system.sets.single()
        assertEquals("s3cret", set.text)
        assertTrue(set.sensitive)
        assertTrue("32 hex digits: ${set.token}", Regex("[0-9a-f]{32}").matches(set.token))
        assertEquals("128 random bits", listOf(16), rig.random.byteRequests)

        clipboard.copy("aziz", username, sensitive = false)
        val second = rig.system.sets.last()
        assertFalse("a username is not flagged", second.sensitive)
        assertNotEquals("every copy has its own token", set.token, second.token)
    }

    @Test
    fun aCopyPlansItsClearOnAllThreePaths() {
        val clipboard = rig.make()
        rig.settings.clipDelay.value = ClipDelay.MINUTE_1
        val wall = rig.clock.wallMs()
        rig.advance(5_000)
        clipboard.copy("s3cret", password, sensitive = true)

        val token = rig.system.sets.single().token
        assertEquals("the status row", ClipStatus(password, true, clearsAtElapsedMs = 65_000, delayMs = 60_000), clipboard.status.value)
        assertEquals("the alarm", FakeClearAlarm.Scheduled(token, 65_000), rig.alarm.set)
        assertEquals("the settings, on the wall clock", PendingClip(token, wall + 65_000), rig.settings.pendingClip.value)
    }

    @Test
    fun theDelayIsTheChosenOneAndThirtySecondsWhenMissing() {
        val clipboard = rig.make()
        clipboard.copy("a", password, sensitive = true)
        assertEquals("a missing setting is 30 seconds (D-7)", 30_000L, clipboard.status.value?.delayMs)
        for (delay in ClipDelay.entries) {
            rig.settings.clipDelay.value = delay
            clipboard.copy("a", password, sensitive = true)
            assertEquals(delay.ms, clipboard.status.value?.delayMs)
            assertEquals(rig.clock.elapsedMs() + delay.ms, clipboard.status.value?.clearsAtElapsedMs)
        }
    }

    @Test
    fun aRefusedCopyChangesNothingAndReachesTheCaller() {
        val clipboard = rig.make()
        clipboard.copy("first", password, sensitive = true)
        val before = clipboard.status.value
        val firstToken = rig.system.token

        rig.system.failNextSet = true
        try {
            clipboard.copy("second", username, sensitive = false)
            fail("the phone's refusal must reach the caller, which tells the user (ux.md §6 \"Failures\")")
        } catch (expected: SecurityException) {
            // as it should
        }
        assertSame("the row still shows the first copy", before, clipboard.status.value)
        assertEquals("the first clip is still there", firstToken, rig.system.token)
        assertEquals("and still planned", firstToken, rig.alarm.set?.token)
        assertEquals(firstToken, rig.settings.pendingClip.value?.token)
        assertEquals(0, rig.system.clears)

        rig.advance(30_000)
        assertEquals("the first clip is cleared at its own time", 1, rig.system.clears)
    }

    @Test
    fun aRefusedCopyThatWentInAllTheSameIsTakenOut() {
        val clipboard = rig.make()
        rig.system.failNextSet = true
        rig.system.failedSetLeavesTheClip = true
        try {
            clipboard.copy("s3cret", password, sensitive = true)
            fail("the refusal must reach the caller")
        } catch (expected: SecurityException) {
            // as it should
        }
        assertFalse("nothing of ours stays without a clear planned", rig.system.has)
        assertNull(clipboard.status.value)
        assertNull(rig.alarm.set)
        assertNull(rig.settings.pendingClip.value)
    }

    @Test
    fun aCopyWorksWhenNoAlarmCanBeSet() {
        val clipboard = rig.make()
        rig.alarm.failSchedules = true
        clipboard.copy("s3cret", password, sensitive = true)
        assertEquals(1, rig.system.sets.size)
        rig.advance(30_000)
        assertEquals("the timer still clears", 1, rig.system.clears)
    }

    // ---- the clear at its time -----------------------------------------------------------------

    @Test
    fun theTimerClearsAtTheDeadlineAndNotBefore() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.advance(29_999)
        assertEquals(0, rig.system.clears)
        assertTrue(rig.system.has)

        rig.advance(1)
        assertEquals(1, rig.system.clears)
        assertFalse(rig.system.has)
        assertNull("the alarm is taken back", rig.alarm.set)
        assertNull("nothing is left in the settings", rig.settings.pendingClip.value)
    }

    @Test
    fun afterAClearTheRowSaysSoForTwoSecondsAndGoes() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.advance(30_000)
        assertEquals(ClipStatus(password, true, 30_000, 30_000, cleared = true), clipboard.status.value)
        rig.advance(1_999)
        assertEquals(true, clipboard.status.value?.cleared)
        rig.advance(1)
        assertNull(clipboard.status.value)
    }

    @Test
    fun aCopyDuringClipboardClearedShowsTheNewCopy() {
        val clipboard = rig.make()
        clipboard.copy("first", password, sensitive = true)
        rig.advance(30_000)
        rig.advance(1_000)
        clipboard.copy("second", username, sensitive = false)
        val shown = clipboard.status.value
        rig.advance(1_000) // the two seconds of the first clear are over
        assertSame("the new copy's row is not taken away with the old message", shown, clipboard.status.value)
        assertEquals(username, shown?.what)
    }

    @Test
    fun aLaterCopyOfAnotherAppIsLeftAlone() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.system.otherAppCopies()
        rig.advance(30_000)

        assertEquals("nothing is cleared", 0, rig.system.clears)
        assertTrue(rig.system.has)
        assertNull("the row goes without \"Clipboard cleared\"", clipboard.status.value)
        assertNull(rig.alarm.set)
        assertNull(rig.settings.pendingClip.value)
    }

    @Test
    fun aClipboardThatCannotBeReadIsCleared() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.system.readable = false // the app in the background on Android 10 and later
        rig.system.otherAppCopies() // even this goes: the app cannot know
        rig.advance(30_000)
        assertEquals(1, rig.system.clears)
        assertEquals(true, clipboard.status.value?.cleared)
    }

    @Test
    fun aSecondCopyReplacesThePlanOfTheFirst() {
        val clipboard = rig.make()
        clipboard.copy("first", password, sensitive = true)
        val first = rig.system.token
        rig.advance(10_000)
        clipboard.copy("second", password, sensitive = true)
        val second = rig.system.token
        assertEquals(FakeClearAlarm.Scheduled(second!!, 40_000), rig.alarm.set)
        assertEquals(second, rig.settings.pendingClip.value?.token)

        rig.advance(20_000) // the first copy's time
        assertEquals("nothing happens at the first time", 0, rig.system.clears)
        assertEquals(second, rig.system.token)
        assertNotEquals(first, second)

        rig.advance(10_000)
        assertEquals(1, rig.system.clears)
    }

    @Test
    fun aClearThePhoneRefusesIsTriedAgainWhenTheAppComesToTheFront() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.system.failClears = true
        rig.advance(30_000)
        assertTrue("the clip is still there", rig.system.has)
        assertEquals("the row keeps it; past its time it says \"Clearing…\"", false, clipboard.status.value?.cleared)
        assertEquals("the settings keep it", rig.system.token, rig.settings.pendingClip.value?.token)

        rig.system.failClears = false
        clipboard.appCameToFront()
        assertFalse(rig.system.has)
        assertEquals(true, clipboard.status.value?.cleared)
        assertNull(rig.settings.pendingClip.value)
    }

    // ---- "Clear now" and "Lock now" --------------------------------------------------------------

    @Test
    fun clearNowClearsAtOnce() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.advance(3_000)
        clipboard.clearNow()

        assertEquals(1, rig.system.clears)
        assertEquals(true, clipboard.status.value?.cleared)
        assertNull(rig.alarm.set)
        assertNull(rig.settings.pendingClip.value)
        rig.advance(27_000)
        assertEquals("nothing more happens at the old time", 1, rig.system.clears)
    }

    @Test
    fun clearNowLeavesAnotherAppsClipAlone() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.system.otherAppCopies()
        clipboard.clearNow()
        assertEquals(0, rig.system.clears)
        assertTrue(rig.system.has)
        assertNull(clipboard.status.value)
    }

    @Test
    fun withNothingOfOursPendingNothingIsTouchedOrRead() {
        val clipboard = rig.make()
        rig.system.otherAppCopies()
        clipboard.clearNow() // "Lock now" with someone else's text on the clipboard
        clipboard.appCameToFront()
        clipboard.windowFocused()
        assertEquals(0, rig.system.clears)
        assertEquals("the clipboard is not even looked at (ux.md §3)", 0, rig.system.looks)
        assertTrue(rig.system.has)
    }

    // ---- the alarm ---------------------------------------------------------------------------------

    @Test
    fun theAlarmAtTheDeadlineClearsAndThenReportsDone() {
        // The process had ended; Android starts one for the alarm, and the settings name the clip.
        val rig = ClipRig()
        rig.system.has = true
        rig.system.token = "t0"
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs())
        val clipboard = rig.make()
        assertEquals("the clip waits for the receiver, which comes next", 0, rig.system.clears)

        var done = 0
        clipboard.alarmFired("t0", clearAtElapsedMs = rig.clock.elapsedMs()) {
            done++
            assertNull("the settings are written before the broadcast ends", rig.settings.pendingClip.value)
        }
        assertEquals(1, rig.system.clears)
        assertEquals(1, done)
        assertEquals(true, clipboard.status.value?.cleared)
    }

    @Test
    fun theTimerAndTheAlarmTogetherClearOnce() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        val token = rig.system.token!!
        rig.advance(29_990)
        var done = 0
        clipboard.alarmFired(token, clearAtElapsedMs = 30_000) { done++ } // 10 ms early: it waits in the receiver
        assertEquals(0, done)
        rig.advance(10) // the timer and the waiting receiver both wake
        assertEquals("one clear per copy", 1, rig.system.clears)
        assertEquals(1, done)
    }

    @Test
    fun anAlarmThatComesALittleEarlyWaitsInTheReceiver() {
        val rig = ClipRig()
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() + 6_000)
        val clipboard = rig.make() // a process started for the alarm, 6 s before the deadline
        var done = 0
        clipboard.alarmFired("t0", clearAtElapsedMs = 6_000) { done++ }
        assertEquals("not yet", 0, rig.system.clears)
        assertEquals("the broadcast is kept open", 0, done)

        rig.advance(5_999)
        assertEquals(0, done)
        rig.advance(1)
        assertEquals(1, rig.system.clears)
        assertEquals(1, done)
    }

    @Test
    fun anAlarmThatComesFarTooEarlyIsAimedAgain() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        val token = rig.system.token!!
        rig.advance(10_000)
        var done = 0
        clipboard.alarmFired(token, clearAtElapsedMs = 30_000) { done++ } // 20 s early: more than the receiver may wait
        assertEquals(0, rig.system.clears)
        assertEquals("aimed again for the same time", listOf(FakeClearAlarm.Scheduled(token, 30_000), FakeClearAlarm.Scheduled(token, 30_000)), rig.alarm.schedules)
        assertEquals("the broadcast ends at once", 1, done)
    }

    @Test
    fun anAlarmForAClipThatIsNoLongerPendingDoesNothing() {
        val clipboard = rig.make()
        clipboard.copy("first", password, sensitive = true)
        val first = rig.system.token!!
        clipboard.copy("second", password, sensitive = true)
        var done = 0
        clipboard.alarmFired(first, clearAtElapsedMs = 0) { done++ } // the first copy's alarm, already on its way
        assertEquals("the second copy is not cleared early", 0, rig.system.clears)
        assertEquals(1, done)

        rig.advance(30_000) // the timer clears the second
        assertEquals(1, rig.system.clears)
        rig.system.otherAppCopies()
        rig.system.readable = false
        clipboard.alarmFired(rig.system.sets.last().token, clearAtElapsedMs = 30_000) { done++ } // its alarm arrives after the timer
        assertEquals("one clear per copy", 1, rig.system.clears)
        assertEquals(2, done)
    }

    @Test
    fun aProcessStartedForAnAlarmItNeverHeardOfClearsByTheAlarmsToken() {
        // The process ended before the settings were written: only the alarm knows the clip.
        val rig = ClipRig()
        rig.system.has = true
        rig.system.token = "t0"
        val clipboard = rig.make()
        assertNull(clipboard.status.value)
        var done = 0
        clipboard.alarmFired("t0", clearAtElapsedMs = 0) { done++ }
        assertEquals(1, rig.system.clears)
        assertEquals(1, done)

        // And it leaves another app's clip alone where it can see it.
        val other = ClipRig()
        other.system.otherAppCopies()
        other.make().alarmFired("t0", clearAtElapsedMs = 0) { done++ }
        assertEquals(0, other.system.clears)
        assertEquals(2, done)
    }

    // ---- a new process -----------------------------------------------------------------------------

    @Test
    fun aNewProcessTakesUpAClipThatIsStillWaiting() {
        val first = rig.make()
        first.copy("s3cret", password, sensitive = true)
        val token = rig.system.token!!
        rig.advance(12_000)

        val clipboard = rig.make() // the first process is gone; its timer in this test is not
        val status = clipboard.status.value
        assertEquals("what was copied is not in the settings: the row says \"Copied text\"", AndroidSecretClipboard.COPIED_TEXT, status?.what)
        assertEquals("the time that is left", 30_000L, status?.clearsAtElapsedMs)
        assertEquals(18_000L, status?.delayMs)
        assertEquals("the alarm is set again: a force-stop had removed it", FakeClearAlarm.Scheduled(token, 30_000), rig.alarm.schedules.last())
    }

    @Test
    fun aNewProcessClearsAWaitingClipAtItsTime() {
        val rig = ClipRig()
        rig.system.has = true
        rig.system.token = "t0"
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() + 18_000)
        val clipboard = rig.make()
        rig.advance(17_999)
        assertEquals(0, rig.system.clears)
        rig.advance(1)
        assertEquals(1, rig.system.clears)
        assertEquals(true, clipboard.status.value?.cleared)
        assertNull(rig.settings.pendingClip.value)
    }

    @Test
    fun anOverdueClipIsClearedWhenTheAppComesToTheFront() {
        // After a force-stop: no alarm, no timer; the settings know the clip (D-14).
        val rig = ClipRig()
        rig.system.has = true
        rig.system.token = "t0"
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() - 60_000)
        val clipboard = rig.make()
        assertEquals("nothing is cleared before the app is in front", 0, rig.system.clears)
        assertNull("no alarm is set for a time that has passed", rig.alarm.set)
        rig.advance(10_000)
        assertEquals(0, rig.system.clears)

        clipboard.appCameToFront()
        assertEquals(1, rig.system.clears)
        assertNull(rig.settings.pendingClip.value)
    }

    @Test
    fun anOverdueClipThatOnResumeCannotReadWaitsForTheWindowFocus() {
        val rig = ClipRig()
        rig.system.needsFocusToRead = true // Android 10 and later
        rig.system.has = true
        rig.system.token = "t0"
        rig.system.readable = false // onResume comes before the focus
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() - 60_000)
        val clipboard = rig.make()

        clipboard.appCameToFront()
        assertEquals("not yet: the app may be about to see whose clip it is", 0, rig.system.clears)

        rig.system.readable = true
        clipboard.windowFocused()
        assertEquals("ours: cleared", 1, rig.system.clears)
        rig.advance(AndroidSecretClipboard.FOCUS_WAIT_MS)
        assertEquals("the wait is over with the focus", 1, rig.system.clears)
    }

    @Test
    fun withTheFocusALaterCopyOfAnotherAppIsSeenAndKept() {
        val rig = ClipRig()
        rig.system.needsFocusToRead = true
        rig.system.otherAppCopies() // copied after the force-stop
        rig.system.readable = false
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() - 60_000)
        val clipboard = rig.make()

        clipboard.appCameToFront()
        rig.system.readable = true
        clipboard.windowFocused()
        assertEquals(0, rig.system.clears)
        assertTrue(rig.system.has)
        assertNull(clipboard.status.value)
        assertNull(rig.settings.pendingClip.value)
        rig.advance(AndroidSecretClipboard.FOCUS_WAIT_MS)
        assertEquals(0, rig.system.clears)
    }

    @Test
    fun withoutTheFocusAnOverdueClipIsClearedAfterTheWait() {
        val rig = ClipRig()
        rig.system.needsFocusToRead = true
        rig.system.has = true
        rig.system.token = "t0"
        rig.system.readable = false
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() - 60_000)
        val clipboard = rig.make()

        clipboard.appCameToFront()
        clipboard.appCameToFront() // a second onResume starts no second wait
        rig.advance(AndroidSecretClipboard.FOCUS_WAIT_MS - 1)
        assertEquals(0, rig.system.clears)
        rig.advance(1)
        assertEquals("cleared anyway (ux.md §6 item 2)", 1, rig.system.clears)
    }

    @Test
    fun onAndroid9AnEmptyAnswerInOnResumeIsNotWaitedFor() {
        val rig = ClipRig() // needsFocusToRead false: the answer is the truth
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() - 60_000)
        val clipboard = rig.make()
        clipboard.appCameToFront() // the clipboard is empty
        assertEquals("cleared at once, which harms nothing", 1, rig.system.clears)
        assertNull(rig.settings.pendingClip.value)
    }

    @Test
    fun aClockThatWasSetBackNeverPostponesTheClearBeyondTheLongestDelay() {
        val rig = ClipRig()
        rig.system.has = true
        rig.system.token = "t0"
        rig.settings.pendingClip.value = PendingClip("t0", rig.clock.wallMs() + 3_600_000) // the date was moved back an hour
        val clipboard = rig.make()
        assertEquals(ClipDelay.MINUTES_5.ms, clipboard.status.value?.clearsAtElapsedMs)
        rig.advance(ClipDelay.MINUTES_5.ms)
        assertEquals(1, rig.system.clears)
    }

    // ---- back in the app before the time ----------------------------------------------------------

    @Test
    fun backInTheAppWithAnotherClipThereThePlanEnds() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        rig.advance(8_000)
        rig.system.otherAppCopies()
        clipboard.appCameToFront()

        assertNull("the row goes", clipboard.status.value)
        assertNull(rig.alarm.set)
        assertNull(rig.settings.pendingClip.value)
        rig.system.readable = false
        rig.advance(22_000)
        assertEquals("and nothing is cleared at the old time, even unseen", 0, rig.system.clears)
    }

    @Test
    fun backInTheAppWithOurClipStillThereNothingChanges() {
        val clipboard = rig.make()
        clipboard.copy("s3cret", password, sensitive = true)
        val before = clipboard.status.value
        rig.advance(8_000)
        clipboard.appCameToFront()
        clipboard.windowFocused()
        assertSame(before, clipboard.status.value)
        assertEquals(0, rig.system.clears)

        rig.system.readable = false // not the focus yet: nothing can be said, nothing changes
        clipboard.appCameToFront()
        assertSame(before, clipboard.status.value)
        rig.advance(22_000)
        assertEquals(1, rig.system.clears)
    }

    // ---- the settings ---------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theSettingsEndWithTheLastValueWhenWritesOvertakeEachOther() {
        val io = StandardTestDispatcher()
        val rig = ClipRig(io)
        val clipboard = rig.make()
        clipboard.copy("first", password, sensitive = true)
        clipboard.copy("second", password, sensitive = true)
        val second = rig.system.token
        assertNull("nothing is written on the caller's thread (architecture.md §5.3)", rig.settings.pendingClip.value)

        io.scheduler.advanceUntilIdle()
        assertEquals(second, rig.settings.pendingClip.value?.token)

        clipboard.copy("third", password, sensitive = true)
        clipboard.clearNow()
        io.scheduler.advanceUntilIdle()
        assertNull("copy, then clear: the clear is what stays", rig.settings.pendingClip.value)
    }

    @Test
    fun aSettingsWriteThatFailsDoesNotStopTheClear() {
        val clipboard = rig.make()
        rig.settings.failWrites = true
        clipboard.copy("s3cret", password, sensitive = true)
        assertNull(rig.settings.pendingClip.value)
        rig.advance(30_000)
        assertEquals(1, rig.system.clears)
        assertEquals(true, clipboard.status.value?.cleared)
    }
}
