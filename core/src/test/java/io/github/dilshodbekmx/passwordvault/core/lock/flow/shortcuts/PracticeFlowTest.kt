package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F10**, practice (lock.md §12, §8.3), through the engine: the practice screen
 * (§13.1 rows 3 and 4), a correct master password (step 2), "Not now: use PIN" and "Not now: use
 * fingerprint" within the grace (step 3: snoozed for 20 hours, then the pad and the fingerprint
 * prompt as on any other visit), and wrong master passwords there (step 5). Each run ends in the
 * state lock.md describes. Step 4, "I can't remember it", is F11, which build plan 4.4 brings.
 */
class PracticeFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val hour = PracticeSchedule.HOUR_MS
    private val day = PracticeSchedule.DAY_MS

    /** In the 14-day rhythm (step 3), [daysAgo] days after the last correct master password: due after 14, required after 21. */
    private fun practice(daysAgo: Long, extraMs: Long = 0L) = StateRecord.Practice(f.clock.wallMs() - daysAgo * day - extraMs, 3, null)

    /** A vault with a PIN and fingerprint unlock (each when asked), whose practice is [practice], started and in front. */
    private fun phone(pin: Boolean = true, fingerprint: Boolean = true, practice: StateRecord.Practice? = practice(15)) {
        val vault = f.seedVault()
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        if (practice != null) f.plantState(practice = practice)
        f.startInFront()
    }

    @Test
    fun f10_step3_notNowUsePin_snoozesForTwentyHours_thenThePad_andThePromptOpensAsOnAnyVisit() {
        phone()
        assertEquals(MpReason.PracticeDue, f.field.reason)
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        await { f.engine.promptIfDue() } // not on a practice screen (§8.3, §9.2 rule 7)
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)

        await { f.engine.usePin() } // "Not now: use PIN"

        val snoozedUntil = f.clock.wallMs() + 20 * hour
        assertEquals(StateRecord.Practice(practice(15).lastOkMs, 3, snoozedUntil), f.stateRecord().practice)
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = true, opening = false, aside = null), f.screen)
        await { f.engine.promptIfDue() } // after "Not now" the prompt opens as on any other visit
        assertEquals(1, f.keys.prompts.size)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(practice(15).lastOkMs, f.stateRecord().practice.lastOkMs) // a fingerprint is not practice

        // Until the snooze ends the lock screen opens on the pad; then practice is asked again.
        f.clock.advance(19 * hour)
        f.lock()
        assertTrue(f.screen is LockScreen.Pad)
        f.clock.advance(2 * hour)
        f.leaveAndReturn()
        assertEquals(MpReason.PracticeDue, f.field.reason)
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
    }

    @Test
    fun f10_step3_notNowUseFingerprint_withoutAPin_theFingerprintOnlyScreen() {
        phone(pin = false)
        assertEquals(Practice(notNow = Shortcut.FINGERPRINT), f.field.practice)
        assertEquals(false, f.field.canUsePin)

        await { f.engine.notNow() } // "Not now: use fingerprint"

        assertEquals(f.clock.wallMs() + 20 * hour, f.stateRecord().practice.snoozedUntilMs)
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f10_pastTheGrace_thereIsNoNotNow_andNeitherButtonSnoozes() {
        phone(practice = practice(22))
        assertEquals(Practice(notNow = null), f.field.practice)
        val before = f.stateRecord()
        await { f.engine.notNow() }
        await { f.engine.usePin() }
        assertEquals(before, f.stateRecord())
        assertEquals(Practice(notNow = null), f.field.practice)
        await { f.engine.promptIfDue() }
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(practice(22).lastOkMs, f.engine.summary.value.practiceOverdueSinceMs) // the standing banner's date (§13.4 item 6)
    }

    @Test
    fun f10_step2_aCorrectMasterPassword_opensTheApp_andCountsAsPractice() {
        phone()
        val outcome = f.unlockWith(PASSWORD)
        assertTrue(outcome is MasterPasswordOutcome.Right)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 3, null), f.stateRecord().practice)
        f.lock()
        assertTrue(f.screen is LockScreen.Pad) // not due again for 14 days
    }

    @Test
    fun f10_step5_wrongMasterPasswords_theMessages_andTheOfferAfterTwo_thenNotNowLeavesNoLine() {
        phone()
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))
        assertEquals(MpError.Wrong(offerForgot = false, replaced = null), f.field.error)
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it either"))
        assertEquals(MpError.Wrong(offerForgot = true, replaced = null), f.field.error) // "Can't remember it? …"
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice) // still within the grace

        await { f.engine.notNow() }
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.useMasterPassword() } // "Forgot PIN? Use master password": a fresh field
        assertNull(f.field.error)
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val practice = f.stateRecord().practice
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 3, null), practice) // a master password clears the snooze
    }

    @Test
    fun f10_withoutAStateRecord_notNowSkipsPracticeForThisVisit_andTheRecordTheUnlockMakesIsSnoozed() {
        phone(fingerprint = false, practice = null) // §5.3: a missing state.rec means due now and still skippable
        assertEquals(RecordRead.Missing, f.stateOnDisk())
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)

        await { f.engine.notNow() }
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        // §5.3: the new record after a PIN unlock: step 0, last_ok one day ago; and the snooze of this visit.
        val now = f.clock.wallMs()
        assertEquals(StateRecord.Practice(now - day, 0, now + 20 * hour), f.stateRecord().practice)

        f.lock()
        assertTrue("asked at most once a day (§8.3)", f.screen is LockScreen.Pad)
        f.clock.advance(21 * hour)
        f.leaveAndReturn()
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice) // due, within its one day of grace
    }

    @Test
    fun f10_withADamagedStateRecord_theSame() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState(practice = practice(1))
        f.files.damage(f.dirOf(VAULT), RecordCodec.State.fileName) { it.also { bytes -> bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() } }
        f.startInFront()
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        await { f.engine.usePin() }
        f.type(PIN)
        val now = f.clock.wallMs()
        assertEquals(StateRecord.Practice(now - day, 0, now + 20 * hour), f.stateRecord().practice)
    }

    @Test
    fun f10_withoutAStateRecord_aMasterPasswordAfterNotNow_isPractice_andNeedsNoSnooze() {
        phone(fingerprint = false, practice = null)
        await { f.engine.notNow() }
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), f.stateRecord().practice)
    }

    @Test
    fun f10_aSnoozeThatCannotBeWritten_holdsForThisProcess() {
        phone(fingerprint = false)
        f.files.failNextWrite()
        await { f.engine.notNow() }
        assertTrue(f.screen is LockScreen.Pad)
        assertNull(f.stateRecord().practice.snoozedUntilMs) // not on disk
        f.type(PIN)
        f.lock()
        assertTrue(f.screen is LockScreen.Pad) // in memory
        f.restart()
        f.startInFront()
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice) // a new process asks again
    }

    @Test
    fun f10_aTapThatComesAfterTheGraceEnded_snoozesNothing() {
        phone(fingerprint = false, practice = practice(20, extraMs = 23 * hour)) // the grace ends in an hour
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        f.clock.advance(2 * hour) // the screen still shows "Not now"
        await { f.engine.notNow() }
        assertNull(f.stateRecord().practice.snoozedUntilMs)
        assertEquals(Practice(notNow = null), f.field.practice) // decided again: required
    }

    @Test
    fun f10_aTapOnAVisitThatHasEnded_doesNothing() {
        phone(fingerprint = false)
        await { f.engine.appStopped() }
        await { f.engine.notNow() }
        assertNull(f.stateRecord().practice.snoozedUntilMs)
        await { f.engine.appStarted() }
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
    }

    @Test
    fun f10_whileANewMasterPasswordIsPending_practiceIsNotAsked_soThereIsNothingToSnooze() {
        val vault = f.seedVault()
        f.plantPin(vault)
        val pending = StateRecord.PendingMp(f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs(), vault.newestVkId, ByteArray(16), ByteArray(12), ByteArray(48))
        f.plantState(practice = practice(30), pendingMp = pending)
        f.startInFront()
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.notNow() } // not a practice screen: nothing
        assertNull(f.stateRecord().practice.snoozedUntilMs)
        assertTrue(f.screen is LockScreen.Pad)
    }

    @Test
    fun f10_aWrongPinAfterNotNow_staysOnThePad_andTheSnoozeHolds() {
        phone(fingerprint = false)
        await { f.engine.notNow() }
        f.type(LockFixture.WRONG_PIN)
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(f.clock.wallMs() + 20 * hour - 2_000, f.stateRecord().practice.snoozedUntilMs) // set before the two charges aged
    }
}
