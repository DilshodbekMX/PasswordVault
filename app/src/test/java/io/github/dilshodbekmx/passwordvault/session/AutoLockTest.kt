package io.github.dilshodbekmx.passwordvault.session

import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.settings.FakePrefsFile
import io.github.dilshodbekmx.passwordvault.settings.FakeSharedPreferences
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.2, [JVM]: lock.md T19 ("Session") through the real `AutoLock`, with a fake process
 * lifecycle and a fake clock, on the real master-password gate over an in-memory vault
 * ([SessionRig]): the delay arithmetic; "Immediately"; screen off; the no-touch timer, which stands
 * still while the app is stopped or an external screen is pending (6 minutes in a file picker do
 * not lock, 11 do); the external grace and its 10-minute cap; a missing setting means "Immediately";
 * a recreation ends no visit; a check that finishes after `ON_STOP` opens nothing. The order of
 * architecture.md §5.5 for each trigger is [LockOrderTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockTest {
    private val minute = 60_000L

    // ---- the lock delay ----------------------------------------------------------------------

    @Test
    fun t19_theDefaultWrittenBySetup_awayLessThan30SecondsStaysOpen_30SecondsLock() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        assertEquals("setup wrote the default of lock.md §10.1", LockDelay.SECONDS_30, s.settings.lockDelay.value)

        s.awayFor(29_999)
        assertTrue(s.unlocked)
        s.awayFor(10_000)
        assertTrue(s.unlocked)
        assertEquals(emptyList<LockReason>(), s.reasons)

        s.awayFor(30_000)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    @Test
    fun t19_delayArithmetic_forEveryChoice() = runTest {
        for (delay in LockDelay.entries - LockDelay.IMMEDIATELY) {
            val s = SessionRig(this).start()
            s.setUp()
            assertTrue(s.settings.update { it.lockDelay = delay })

            s.awayFor(delay.ms - 1)
            assertTrue("$delay: away 1 ms less than the delay", s.unlocked)
            s.awayFor(delay.ms)
            assertTrue("$delay: away exactly the delay", s.locked)
            assertEquals(listOf(LockReason.LEFT), s.reasons)
        }
    }

    @Test
    fun t19_theTimeAwayCountsFromOnStop_notFromTheLastTouch() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.idle(4 * minute) // in front, untouched: within the 5 minutes
        s.awayFor(29_000)
        assertTrue("4 minutes in front and 29 s away is not 30 s away", s.unlocked)
    }

    @Test
    fun t19_immediately_locksAtOnStop() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        assertTrue(s.settings.update { it.lockDelay = LockDelay.IMMEDIATELY })

        s.process.away()
        s.settle()

        assertTrue("0.7 s after leaving, which is when ON_STOP comes (lock.md §10.2)", s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    @Test
    fun t19_aMissingSettingMeansImmediately() = runTest {
        // A vault on the phone and no settings file: the real settings read the strictest value.
        val s = SessionRig(this, PrefsAppSettings(FakeSharedPreferences(FakePrefsFile()))).apply { vaultOnThePhone() }.start()
        s.unlockExisting()
        assertEquals(LockDelay.IMMEDIATELY, s.settings.lockDelay.value)

        s.process.away()
        s.settle()

        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    @Test
    fun t19_anUnreadableSettingMeansImmediately() = runTest {
        val file = FakePrefsFile(mapOf(PrefsAppSettings.LOCK_DELAY to "forever"))
        val s = SessionRig(this, PrefsAppSettings(FakeSharedPreferences(file))).apply { vaultOnThePhone() }.start()
        s.unlockExisting()

        s.process.away()
        s.settle()

        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    // ---- screen off --------------------------------------------------------------------------

    @Test
    fun t19_screenOffLocksAtOnce_whateverTheDelay() = runTest {
        for (delay in LockDelay.entries) {
            val s = SessionRig(this).start()
            s.setUp()
            assertTrue(s.settings.update { it.lockDelay = delay })

            s.autoLock.screenTurnedOff() // the receiver, about 700 ms before ON_STOP
            s.settle()
            assertTrue("$delay", s.locked)
            s.screenOn = false
            s.process.away()
            s.settle()
            assertEquals("ON_STOP after the lock adds nothing", listOf(LockReason.SCREEN_OFF), s.reasons)
        }
    }

    @Test
    fun t19_theScreenOffWhenTheAppStops_locksAtOnStop() = runTest {
        // The broadcast has not arrived (or is held back): PowerManager.isInteractive() decides.
        val s = SessionRig(this).start()
        s.setUp()
        s.screenOn = false
        s.process.away()
        s.settle()
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.SCREEN_OFF), s.reasons)
    }

    @Test
    fun t19_aScreenOffThatArrivesWhileAwayOrLate_locks() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.process.away()
        s.settle()
        s.clock.advance(5_000)
        s.autoLock.screenTurnedOff()
        s.settle()
        assertTrue("seen while away, within the delay (lock.md §10.2 rule 2)", s.locked)

        // Android 14+ holds the broadcast while the app is cached: it can come after the return.
        val late = SessionRig(this).start()
        late.setUp()
        late.awayFor(5_000)
        assertTrue(late.unlocked)
        late.autoLock.screenTurnedOff()
        late.settle()
        assertTrue("the app locks then, late", late.locked)
        assertEquals(listOf(LockReason.SCREEN_OFF), late.reasons)
    }

    // ---- the no-touch timer --------------------------------------------------------------------

    @Test
    fun t19_fiveMinutesWithoutATouchInFront_lock() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.idle(5 * minute - 1)
        assertTrue(s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
    }

    @Test
    fun t19_everyTouchOrKeyStartsTheFiveMinutesAgain() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.idle(4 * minute)
        s.autoLock.touched()
        s.idle(4 * minute)
        s.autoLock.touched() // a key press is the same call (MainActivity.dispatchKeyEvent)
        s.idle(5 * minute - 1)
        assertTrue("13 minutes in front, never 5 without a touch", s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
    }

    @Test
    fun t19_theNoTouchTimerStandsStillWhileTheAppIsAway() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        assertTrue(s.settings.update { it.lockDelay = LockDelay.MINUTES_5 })
        s.idle(4 * minute) // 4 minutes of no touch in front
        s.awayFor(4 * minute) // 4 more away: the timer stands still, and 4 minutes is within the delay
        assertTrue(s.unlocked)
        s.idle(5 * minute - 1) // the timer started afresh at ON_START
        assertTrue(s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
    }

    // ---- external screens (lock.md §10.3) --------------------------------------------------------

    @Test
    fun t19_sixMinutesInAFilePickerDoNotLock_elevenDo() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.beginExternal() // just before CreateDocument
        s.process.away()
        s.settle()
        s.clock.advance(6 * minute)
        s.autoLock.endExternal() // the picker's result
        s.process.toFront()
        s.settle()
        assertTrue("6 minutes: neither the 30 s delay nor the no-touch timer applies", s.unlocked)

        s.autoLock.beginExternal()
        s.awayFor(11 * minute)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.EXTERNAL_TIMEOUT), s.reasons)
    }

    @Test
    fun t19_theGraceHoldsWithImmediatelyToo() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        assertTrue(s.settings.update { it.lockDelay = LockDelay.IMMEDIATELY })
        s.autoLock.beginExternal()
        s.process.away()
        s.settle()
        assertTrue("no lock at ON_STOP while the picker is open", s.unlocked)
        s.clock.advance(6 * minute)
        s.process.toFront()
        s.settle()
        assertTrue(s.unlocked)

        // The grace ended when the app came back: leaving now is ordinary leaving.
        s.process.away()
        s.settle()
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    @Test
    fun t19_theExternalGraceIsCappedAt10Minutes() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.beginExternal()
        s.awayFor(10 * minute)
        assertTrue("exactly 10 minutes: not more than 10", s.unlocked)

        s.autoLock.beginExternal()
        s.awayFor(10 * minute + 1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.EXTERNAL_TIMEOUT), s.reasons)
    }

    @Test
    fun t19_theNoTouchTimerStandsStillWhileAnExternalScreenIsPending() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.beginExternal() // the app is not stopped yet
        s.idle(6 * minute)
        assertTrue("6 minutes without a touch, but a screen of our own is pending", s.unlocked)
        s.autoLock.endExternal() // the timer starts afresh now
        s.idle(5 * minute - 1)
        assertTrue(s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
    }

    @Test
    fun t19_aPendingExternalScreenInFront_endsItsGraceAt10Minutes() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.beginExternal()
        s.idle(10 * minute + 5 * minute - 1) // the grace ends at its 10th minute; then the 5 minutes
        assertTrue(s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.NO_TOUCH), s.reasons)
    }

    // ---- visits --------------------------------------------------------------------------------

    @Test
    fun t19_aRecreationEndsNoVisit_andLocksNothing() = runTest {
        // Unlocked, with "Immediately": rotations and language changes do not lock (lock.md §11).
        val s = SessionRig(this).start()
        s.setUp()
        assertTrue(s.settings.update { it.lockDelay = LockDelay.IMMEDIATELY })
        val activity = FakeActivity()
        repeat(3) {
            activity.recreate()
            s.autoLock.touched()
            s.settle()
        }
        assertTrue(s.unlocked)
        assertEquals(emptyList<LockReason>(), s.reasons)

        // Locked, a master password being checked: a recreation meanwhile does not end its visit.
        s.process.away()
        s.settle()
        assertTrue(s.locked)
        s.process.toFront()
        s.settle()
        val hold = CompletableDeferred<Unit>().also { s.vault.hold = it }
        val check = async { s.lock.submitMasterPassword(SessionRig.PASSWORD, MpPurpose.UNLOCK) }
        s.settle()
        activity.recreate()
        s.settle()
        hold.complete(Unit)
        s.settle()
        assertTrue(check.await() is MpResult.Right)
        assertTrue(s.unlocked)
    }

    @Test
    fun t19_aCheckThatFinishesAfterOnStopOpensNothing() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.screenOn = false
        s.process.away() // the screen went off
        s.settle()
        assertTrue(s.locked)
        s.screenOn = true
        s.process.toFront()
        s.settle()

        val hold = CompletableDeferred<Unit>().also { s.vault.hold = it }
        val check = async { s.lock.submitMasterPassword(SessionRig.PASSWORD, MpPurpose.UNLOCK) }
        s.settle()
        s.screenOn = false
        s.process.away() // the screen went off while Argon2id ran
        s.settle()
        hold.complete(Unit)
        s.settle()

        assertEquals(MpResult.TooLate, check.await())
        assertTrue(s.locked)
        assertFalse("what was opened was dropped again", s.vault.isOpen)
        assertEquals("no session started for it", listOf(LockReason.SCREEN_OFF), s.reasons)
        s.screenOn = true
        s.process.toFront()
        s.settle()
        assertTrue(s.lock.state.value is LockUiState.Locked)
    }

    // ---- the session's own edges -----------------------------------------------------------------

    @Test
    fun anUnlockThatFinishesAfterTheAppWasLeft_countsAsLeavingThen() = runTest {
        // Setup's last step, "Type it again", takes about a second; the user leaves meanwhile.
        val s = SessionRig(this).start()
        s.process.toFront()
        s.settle()
        val hold = CompletableDeferred<Unit>().also { s.vault.createHold = it }
        val create = async { s.gate.createVault(SessionRig.PASSWORD, SessionRig.PASSWORD.copyOf(), null) }
        s.settle()
        s.process.away()
        s.settle()
        hold.complete(Unit)
        s.settle()
        create.await()
        assertTrue("left within the default 30 s", s.unlocked)

        s.clock.advance(30_000)
        s.process.toFront()
        s.settle()
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LEFT), s.reasons)
    }

    @Test
    fun anUnlockThatFinishesAfterTheAppWasLeftWithTheScreenOff_locksAtOnce() = runTest {
        val s = SessionRig(this).start()
        s.process.toFront()
        s.settle()
        val hold = CompletableDeferred<Unit>().also { s.vault.createHold = it }
        val create = async { s.gate.createVault(SessionRig.PASSWORD, SessionRig.PASSWORD.copyOf(), null) }
        s.settle()
        s.screenOn = false
        s.process.away()
        s.settle()
        hold.complete(Unit)
        s.settle()
        create.await()
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.SCREEN_OFF), s.reasons)
    }

    @Test
    fun nothingIsDecidedWhileLocked() = runTest {
        val s = SessionRig(this).apply { vaultOnThePhone() }.start()
        s.process.toFront()
        s.settle()
        assertTrue(s.locked)
        val before = s.vault.log.toList()
        s.autoLock.touched()
        s.autoLock.beginExternal()
        s.autoLock.endExternal()
        s.autoLock.screenTurnedOff()
        s.idle(20 * minute)
        s.awayFor(20 * minute)
        s.autoLock.lockNow()
        s.settle()
        assertEquals("nothing flushed or written", before.filterNot { it.startsWith("lock(") }, s.vault.log.filterNot { it.startsWith("lock(") })
        assertEquals("only the screen-off is passed on, for the lock screen's visit (lock.md §6.3)", listOf(LockReason.SCREEN_OFF), s.reasons)
        assertEquals("Lock now still clears our clip", 1, s.clipboard.clears)
    }

    @Test
    fun aScreenOffWhileLocked_isPassedToTheLock_withoutAFlush() = runTest {
        // Decision 2 (build-plan.md §12): it ends the visit of the lock screen shown (lock.md §6.3, decision 29).
        val s = SessionRig(this).start()
        s.setUp()
        s.autoLock.lockNow()
        s.settle()
        assertTrue(s.locked)
        val before = s.vault.log.toList()
        s.autoLock.screenTurnedOff()
        s.settle()
        assertTrue(s.locked)
        assertEquals(listOf(LockReason.LOCK_NOW, LockReason.SCREEN_OFF), s.reasons)
        assertEquals("the lock, and no draft flush", listOf("lock(SCREEN_OFF)"), s.vault.log.drop(before.size))
    }

    @Test
    fun eachUnlockStartsANewSession() = runTest {
        val s = SessionRig(this).start()
        s.setUp()
        s.idle(5 * minute)
        assertTrue(s.locked)
        s.unlock()
        s.idle(5 * minute - 1)
        assertTrue("the timer of the new session runs from the unlock", s.unlocked)
        s.idle(1)
        assertTrue(s.locked)
        s.unlock()
        s.awayFor(30_000)
        assertEquals(listOf(LockReason.NO_TOUCH, LockReason.NO_TOUCH, LockReason.LEFT), s.reasons)
    }
}
