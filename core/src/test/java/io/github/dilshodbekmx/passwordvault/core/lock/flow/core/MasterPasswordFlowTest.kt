package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of F7, forgot the PIN, and of everything else the master-password screen does
 * (lock.md §6.8, §8.3, §13.3), with what every unlock does to the records afterwards (§5.3, §5.4,
 * §5.6, F16c) and the notices of §13.4.
 */
class MasterPasswordFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun withPin(row: Int = 0, total: Int = 0): FakeVaultPort.FakeVault {
        val vault = f.seedVault()
        f.plantPin(vault, row = row, total = total)
        f.plantState()
        f.startInFront()
        return vault
    }

    private fun pending(vkId: String, creditedMs: Long) = StateRecord.PendingMp(
        requestedMs = f.clock.wallMs() - creditedMs,
        by = Shortcut.PIN,
        creditedMs = creditedMs,
        seenElapsedMs = f.clock.elapsedMs(),
        vkId = vkId,
        salt = ByteArray(16) { 1 },
        nonce = ByteArray(12) { 2 },
        ct = ByteArray(48) { 3 },
    )

    // ---- F7 ----------------------------------------------------------------------------------------

    @Test
    fun f7_forgotThePin() {
        withPin(row = 3, total = 7)
        f.type("58")
        assertTrue(f.pinRecord().guard.charged)

        // Step 1: "Forgot PIN? Use master password". The held charge is refunded.
        await { f.engine.useMasterPassword() }
        assertEquals(PinRecord.Guard(charged = false, row = 3, total = 7), f.pinRecord().guard)
        // Step 2: the master-password screen, without a subtitle; the PIN could still be used.
        assertEquals(
            LockScreen.MasterPassword(
                reason = null, canUsePin = true, practice = null, waitUntilElapsedMs = null, error = null,
                pendingReadyInMs = null, opening = false, aside = null,
            ),
            f.screen,
        )

        val outcome = f.unlockWith(PASSWORD)
        // A correct master password opens the app and returns both counts to 0 (§6.4).
        val proof = (outcome as MasterPasswordOutcome.Right).proof
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(listOf("vaults", "openWithPassword current", "retireOldKeys"), f.port.calls)
        assertTrue(f.port.writes.isEmpty())
        // Step 3: "Choose a new PIN", with "Not now"; the proof is the unlock just made.
        val offer = f.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.FORGOTTEN, offer.reason)
        assertFalse(offer.needsMasterPassword)
        assertTrue(proof === (f.state as LockState.Unlocked).proof)
        assertTrue(f.engine.core.holds(proof))
        // Nothing else changes: the old slot stays until a new PIN replaces it.
        assertTrue(f.pinRecord().slot != null)
        assertEquals(1, f.keys.aliases.size)
    }

    @Test
    fun usePin_goesBackToThePad() {
        withPin()
        await { f.engine.useMasterPassword() }
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("no"))
        await { f.engine.usePin() }
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        // A PIN unlock takes nothing off the wrong master passwords: they are reported (§13.4 item 4).
        assertEquals(1, (f.notices.single() as Notice.WrongTries).wrongMasterPasswords)
    }

    @Test
    fun aMasterPasswordSubmittedOnThePad_isNotChecked() {
        withPin()
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
        assertEquals(listOf("vaults"), f.port.calls)
    }

    // ---- §6.8 --------------------------------------------------------------------------------------

    @Test
    fun aWrongMasterPassword_isCountedBeforeItIsChecked_andSaysSo() {
        withPin()
        await { f.engine.useMasterPassword() }
        var guardWhileArgon2idRan: StateRecord.MpGuard? = null
        f.port.beforeCall = { call -> if (call.startsWith("openWithPassword")) guardWhileArgon2idRan = f.stateRecord().mpGuard }
        val at = f.clock.elapsedMs()

        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not the password"))
        assertEquals(StateRecord.MpGuard(failures = 1, lastFailElapsedMs = at, unseen = 1), guardWhileArgon2idRan)
        assertEquals(StateRecord.MpGuard(failures = 1, lastFailElapsedMs = at, unseen = 1), f.stateRecord().mpGuard)
        assertEquals(MpError.Wrong(offerForgot = false, replaced = null), f.field.error)
        assertFalse(f.field.opening)
        assertNull(f.port.openVaultId)

        // After 2 wrong tries, while a shortcut is usable: "Can't remember it? …" (F10 step 5).
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("still not"))
        assertEquals(MpError.Wrong(offerForgot = true, replaced = null), f.field.error)

        // A right one, typed at once, takes its own two slips off `unseen` (§13.4 item 4).
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 0), f.stateRecord().mpGuard)
        assertEquals(emptyList<Notice>(), f.notices.filterIsInstance<Notice.WrongTries>())
    }

    @Test
    fun withoutAUsableShortcut_theForgotLineIsNotAdded() {
        f.seedVault()
        f.plantState()
        f.startInFront()
        f.unlockWith("a")
        f.unlockWith("b")
        assertEquals(MpError.Wrong(offerForgot = false, replaced = null), f.field.error)
    }

    @Test
    fun theSixthTryWaitsThirtySeconds_neverMore_andThereIsNeverALockout() {
        f.seedVault()
        f.plantState()
        f.startInFront()
        repeat(5) { assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("wrong $it")) } // tries 1 to 5 without a wait
        val last = f.stateRecord().mpGuard.lastFailElapsedMs!!
        val calls = f.port.calls.size

        assertEquals(MasterPasswordOutcome.Wait(last + 30_000), f.unlockWith(PASSWORD))
        assertEquals(calls, f.port.calls.size) // nothing was tried
        assertEquals(last + 30_000, f.field.waitUntilElapsedMs)
        assertEquals(5, f.stateRecord().mpGuard.failures)

        f.clock.advance(29_999)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Wait)
        f.clock.advance(1)
        await { f.engine.refresh() } // the wait is over: the field again
        assertNull(f.field.waitUntilElapsedMs)
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("wrong again"))
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Wait) // 30 s before each later try
        f.clock.advance(30_000)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right) // the right one always opens (I10)
        assertEquals(0, f.stateRecord().mpGuard.failures)
    }

    @Test
    fun notEnoughMemory_isNotAWrongPassword_andIsNotCounted() {
        f.seedVault()
        f.plantState()
        f.startInFront()
        f.port.noMemory = true
        assertEquals(MasterPasswordOutcome.NoMemory, f.unlockWith(PASSWORD))
        assertEquals(MpError.NoMemory, f.field.error)
        assertEquals(StateRecord.MpGuard(0, null, 0), f.stateRecord().mpGuard)
        f.port.noMemory = false
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right) // "Try again"
    }

    @Test
    fun i11_aRightMasterPasswordWhoseVisitEnded_updatesTheRecordsAndOpensNothing() {
        val vault = withPin(row = 2, total = 2)
        check(vault.vaultId == VAULT)
        f.plantState(practice = StateRecord.Practice(f.clock.wallMs() - 30 * day, 3, null)) // overdue: the practice screen
        f.restart()
        f.startInFront()
        assertEquals(MpReason.PracticeDue, f.field.reason)
        val outcome = runBlocking {
            f.port.beforeCall = { call ->
                if (call.startsWith("openWithPassword")) launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() }
            }
            f.engine.unlockWithMasterPassword(PASSWORD.toCharArray())
        }
        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId) // the key was dropped
        assertEquals(listOf("openWithPassword current", "lock"), f.port.calls.takeLast(2))
        // §6.8 step 8: steps 5 to 7 update the records as usual.
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(0, f.stateRecord().mpGuard.failures)
        assertEquals(f.clock.wallMs(), f.stateRecord().practice.lastOkMs)
    }

    @Test
    fun aMasterPasswordSubmittedOnAVisitThatHasEnded_isNotCheckedAtAll() {
        f.seedVault()
        f.plantState()
        f.startInFront()
        await { f.engine.appStopped() }
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
        assertEquals(listOf("vaults"), f.port.calls)
        assertEquals(0, f.stateRecord().mpGuard.failures)
    }

    @Test
    fun aRightMasterPasswordAndNoCopyThatCanBeUsed_isNeverWrong() {
        val vault = f.seedVault()
        f.plantState()
        f.startInFront()
        vault.live.first().state = FakeVaultPort.FileState.UNREADABLE // since the screen was drawn
        vault.save().state = FakeVaultPort.FileState.OK
        vault.live.first().state = FakeVaultPort.FileState.UNREADABLE
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
        assertEquals(LockState.Unreadable(CurrentVault.Why.IO, emptyList()), f.state)
        assertEquals(0, f.stateRecord().mpGuard.failures) // the slot opened: not wrong, not counted
        assertTrue(f.port.writes.isEmpty())
    }

    @Test
    fun f16b_aPasswordFromBeforeAChange_opensNothing_staysCounted_andGetsItsScreen() {
        val vault = f.seedVault(password = "the old one")
        vault.rekey(bytes(PASSWORD))
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()

        val outcome = f.unlockWith("the old one")
        val changedAt = vault.generation(2).createdMs
        assertEquals(MasterPasswordOutcome.OlderPassword(changedAt, unfinishedChange = false), outcome)
        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = false), f.screen)
        assertNull(f.port.openVaultId)
        assertEquals(1, f.stateRecord().mpGuard.failures)
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD)) // its screen has no field: it is answered first
    }

    @Test
    fun f16b_whileAChangeIsMarkedUnfinished_theScreenSaysSo() {
        val vault = f.seedVault(password = "the old one")
        val newKey = vault.rekey(bytes(PASSWORD))
        f.plantState(mpChange = StateRecord.MpChange(newKey, f.clock.wallMs()))
        f.startInFront()
        val outcome = f.unlockWith("the old one") as MasterPasswordOutcome.OlderPassword
        assertTrue(outcome.unfinishedChange)
        assertTrue((f.screen as LockScreen.OlderPassword).unfinishedChange)
    }

    @Test
    fun aTypedMasterPasswordThatOpensTheVault_forgetsTheMarkOfAChange() {
        val vault = f.seedVault()
        f.plantState(mpChange = StateRecord.MpChange(vault.newestVkId, f.clock.wallMs()))
        f.startInFront()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord().mpChange)
    }

    // ---- practice (§8.3, §13.1 rows 3 and 4) ---------------------------------------------------------

    @Test
    fun practiceWithinItsGrace_theMasterPasswordWithNotNow_andACorrectOneMovesTheSchedule() {
        val vault = f.seedVault()
        f.plantPin(vault)
        val lastOk = f.clock.wallMs() - 36 * hour // step 0: due after one day, required after two
        f.plantState(practice = StateRecord.Practice(lastOk, 0, null))
        f.startInFront()

        assertEquals(MpReason.PracticeDue, f.field.reason)
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        assertTrue(f.field.canUsePin)
        assertNull(f.engine.summary.value.practiceOverdueSinceMs) // within its grace: no banner

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 1, null), f.stateRecord().practice)
        assertEquals(emptyList<Notice>(), f.notices) // practice is not "Forgot PIN?": no new PIN is offered
        f.lock()
        assertTrue(f.screen is LockScreen.Pad) // not due again for two days
    }

    @Test
    fun practicePastItsGrace_theMasterPasswordIsRequired() {
        val vault = f.seedVault()
        f.plantPin(vault)
        val lastOk = f.clock.wallMs() - 3 * day
        f.plantState(practice = StateRecord.Practice(lastOk, 0, null))
        f.startInFront()

        assertEquals(MpReason.PracticeDue, f.field.reason)
        assertEquals(Practice(notNow = null), f.field.practice)
        assertFalse(f.field.canUsePin)
        await { f.engine.usePin() } // not offered, and it changes nothing
        assertTrue(f.screen is LockScreen.MasterPassword)
        f.type(PIN)
        assertEquals(0, f.keys.hmacCalls.size)

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.engine.summary.value.practiceOverdueSinceMs)
        assertEquals(f.clock.wallMs(), f.stateRecord().practice.lastOkMs)
    }

    @Test
    fun practiceIsNotAskedWhileANewMasterPasswordIsPending() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState(practice = StateRecord.Practice(f.clock.wallMs() - 30 * day, 3, null), pendingMp = pending(vault.newestVkId, 2 * hour))
        f.startInFront()
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.useMasterPassword() }
        assertEquals(22 * hour, f.field.pendingReadyInMs) // "Your new master password starts working in about 22 hours."
    }

    // ---- the notices of §13.4 -------------------------------------------------------------------------

    @Test
    fun item2_aPendingMasterPasswordIsShownAfterEveryUnlock_untilItIsReady() {
        val vault = f.seedVault()
        f.plantPin(vault)
        val waiting = pending(vault.newestVkId, 4 * hour)
        f.plantState(pendingMp = waiting)
        f.startInFront()
        f.type(PIN) // the charge ages for a second, which is credited like any other
        assertEquals(Notice.Pending(f.notices.single().id, waiting.requestedMs, Shortcut.PIN, 20 * hour - 1_000), f.notices.single())
        assertEquals(
            LockSummary.Pending(waiting.requestedMs, Shortcut.PIN, 4 * hour + 1_000, 20 * hour - 1_000, ready = false),
            f.engine.summary.value.pending,
        )

        f.clock.advance(20 * hour - 2_000) // the phone stayed on
        f.lock()
        f.type(PIN)
        assertEquals(Notice.PendingReady(f.notices.single().id, waiting.requestedMs, Shortcut.PIN), f.notices.single())
        assertTrue(f.engine.summary.value.pending!!.ready)
        assertEquals(24 * hour, f.stateRecord().pendingMp!!.creditedMs) // observed at the unlock (§6.9)
    }

    @Test
    fun item2_aCorrectCurrentMasterPasswordCancelsThePendingOne_onTheLockScreenAndInTheOpenApp() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState(pendingMp = pending(vault.newestVkId, 4 * hour))
        f.startInFront()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord().pendingMp)
        assertTrue(f.notices.first() is Notice.PendingCancelled) // "You typed your master password, so the new one was cancelled."
        assertNull(f.engine.summary.value.pending)

        f.plantState(pendingMp = pending(vault.newestVkId, 4 * hour))
        f.restart()
        f.startInFront()
        f.type(PIN)
        assertTrue(f.notices.single() is Notice.Pending)
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord().pendingMp)
        assertTrue(f.notices.last() is Notice.PendingCancelled)
        assertNull(f.engine.summary.value.pending)
    }

    @Test
    fun item3_aReplacedMasterPasswordIsShownUntilSevenDaysAreCredited() {
        f.seedVault()
        f.plantState()
        f.port.seedAside(OTHER_VAULT, bytes("the owner's own"))
        val at = f.clock.wallMs() - day
        f.plantState(OTHER_VAULT, replaced = StateRecord.Replaced(VAULT, at, Shortcut.PIN, creditedMs = 6 * day, seenElapsedMs = f.clock.elapsedMs()))
        f.startInFront()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val notice = f.notices.filterIsInstance<Notice.Replaced>().single()
        assertEquals(Notice.Replaced(notice.id, at, Shortcut.PIN, OTHER_VAULT), notice)
        assertEquals(LockSummary.Replaced(at, Shortcut.PIN, OTHER_VAULT), f.engine.summary.value.replaced)

        f.clock.jumpWall(30 * day) // setting the date forward does not make it go away (I8)
        f.lock()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(1, f.notices.filterIsInstance<Notice.Replaced>().size)

        f.clock.advance(day) // seven credited days
        f.lock()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(0, f.notices.filterIsInstance<Notice.Replaced>().size)
        assertNull(f.engine.summary.value.replaced)
    }

    @Test
    fun aWrongMasterPassword_namesTheReplacement_whileThePasswordsFromBeforeAreOnThePhone() {
        f.seedVault()
        f.plantState()
        f.port.seedAside(OTHER_VAULT, bytes("the owner's own"))
        val at = f.clock.wallMs() - day
        f.plantState(OTHER_VAULT, replaced = StateRecord.Replaced(VAULT, at, Shortcut.FINGERPRINT, 0, f.clock.elapsedMs()))
        f.startInFront()
        f.unlockWith("the owner's own") // the typed password is not tried against the old vault behind the user's back
        assertEquals(MpError.Wrong(offerForgot = false, replaced = ReplacedLine(at, Shortcut.FINGERPRINT, OTHER_VAULT)), f.field.error)
        assertFalse("openWithPassword aside $OTHER_VAULT" in f.port.calls)

        // Replaced more than once: the button opens the list instead.
        f.port.seedAside(THIRD_VAULT, bytes("an even older one"), putAsideAtMs = f.clock.wallMs() - 9 * day)
        f.plantState(THIRD_VAULT, replaced = StateRecord.Replaced(OTHER_VAULT, at - 8 * day, Shortcut.PIN, 0, f.clock.elapsedMs()))
        f.restart()
        f.startInFront()
        f.unlockWith("nope")
        assertEquals(ReplacedLine(at, Shortcut.FINGERPRINT, null), (f.field.error as MpError.Wrong).replaced)
    }

    @Test
    fun item4_afterAFingerprintUnlock_everyWrongTryIsReported_withTheOfferOfANewMasterPassword() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 2, total = 6, unseen = PinRecord.Unseen(3, 1_791_000_000_000L, true))
        f.plantFingerprint(vault)
        // 20 days after the last master password at step 3: due since 6 days, still within its 7 days of grace, and snoozed.
        val lastOk = f.clock.wallMs() - 20 * day
        f.plantState(
            practice = StateRecord.Practice(lastOk, 3, snoozedUntilMs = f.clock.wallMs() + hour),
            mpGuard = StateRecord.MpGuard(failures = 2, lastFailElapsedMs = 5, unseen = 2),
        )
        f.startInFront()
        await { f.engine.promptIfDue() }
        val notice = f.notices.single() as Notice.WrongTries
        assertEquals(3, notice.wrongPins)
        assertEquals(1_791_000_000_000L, notice.lastWrongPinAtMs)
        assertTrue(notice.interrupted)
        assertEquals(4, notice.pinsBeforeOff) // total 6: "After 4 more wrong PINs …"
        assertFalse(notice.offerNewMasterPassword)
        assertEquals(2, notice.wrongMasterPasswords)

        await { f.engine.answerNotice(notice.id, NoticeAnswer.OK) }
        assertEquals(PinRecord.Unseen(0, null, false), f.pinRecord().unseen)
        assertEquals(0, f.stateRecord().mpGuard.unseen)
        assertEquals(2, f.stateRecord().mpGuard.failures) // only `unseen` is taken off
        assertEquals(PinRecord.Guard(false, 2, 6), f.pinRecord().guard)

        // Past the grace as well, and in the app all the same (after "I can't remember it", which skips practice
        // for the visit; package 4.4 sets that): "Can't remember it? Choose a new one."
        f.putPin(f.pinRecord().copy(unseen = PinRecord.Unseen(1, null, false)))
        val overdueSince = f.clock.wallMs() - 22 * day
        f.plantState(practice = StateRecord.Practice(overdueSince, 3, null))
        f.restart()
        f.startInFront()
        assertEquals(Practice(notNow = null), f.field.practice)
        f.engine.core.visit!!.practiceSkipped = true
        await { f.engine.refresh() }
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        val late = f.notices.single() as Notice.WrongTries
        assertEquals(4, late.pinsBeforeOff)
        assertTrue(late.offerNewMasterPassword)
        assertEquals(overdueSince, f.engine.summary.value.practiceOverdueSinceMs) // the standing banner of §13.4 item 6
    }

    @Test
    fun practiceSkippedForAVisit_alsoWithoutARecordThatHoldsItsDates() {
        val vault = f.seedVault()
        f.plantPin(vault) // no state.rec: practice is due now and still skippable (§5.3)
        f.startInFront()
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        f.engine.core.visit!!.practiceSkipped = true
        await { f.engine.refresh() }
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        // §5.3: the new record after a PIN unlock: step 0, last_ok one day ago, so practice stays due with its day of grace.
        assertEquals(StateRecord.Practice(f.clock.wallMs() - day, 0, null), f.stateRecord().practice)
        f.lock()
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
    }

    @Test
    fun item5_aDamagedPinRecord_isNeverRepairedInPlace_andANewPinIsOfferedAfterTheMasterPassword() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.files.damage(f.dirOf(VAULT), "pin.rec") { it.also { bytes -> bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() } }
        val damaged = f.files.bytes(f.dirOf(VAULT), "pin.rec")
        f.startInFront()

        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.STOPPED_WORKING), f.engine.summary.value.pin)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(ChoosePinReason.STOPPED_WORKING, (f.notices.single() as Notice.ChoosePin).reason)
        assertArrayEquals(damaged, f.files.bytes(f.dirOf(VAULT), "pin.rec"))
        assertEquals(1, f.keys.aliases.size) // a damaged record blocks the sweep: its key may be named there
    }

    @Test
    fun item5_aPinRecordOfANewerApp_isNeverRewritten_andNoPinIsOffered() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val newer = reframed(String(json(f.files.bytes(f.dirOf(VAULT), "pin.rec")!!), Charsets.US_ASCII).replace("{\"v\":1,", "{\"v\":2,"))
        f.files.put(f.dirOf(VAULT), "pin.rec", newer)
        f.startInFront()

        assertEquals(RecordRead.Newer, f.pinOnDisk())
        assertEquals(MpReason.Pin(PinReason.NEWER_APP), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.NEWER_APP), f.engine.summary.value.pin)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)
        assertArrayEquals(newer, f.files.bytes(f.dirOf(VAULT), "pin.rec"))
    }

    @Test
    fun item5_aRecordThatCannotBeReadNow_isNotRewritten_andTheMasterPasswordUnlockSaysSo() {
        val vault = f.seedVault()
        val slot = f.plantPin(vault)
        f.plantState()
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/pin.rec") }
        f.startInFront()
        assertEquals(MpReason.Pin(PinReason.CANNOT_CHECK), f.field.reason)
        assertEquals(LockSummary.Pin.CannotCheck, f.engine.summary.value.pin)

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertTrue(f.notices.single() is Notice.StorageUnreadable) // "Part of the app's storage could not be read. Make a backup now."
        f.files.clearFaults()
        assertEquals(slot, f.pinRecord().slot) // it reads fine next time
        assertEquals(listOf(PinWrap.alias(slot.id)), f.keys.aliases)
    }

    @Test
    fun item5_setUpAgain_writesANewStateRecordWhereTheOldOneCouldNotBeRead() {
        f.seedVault(generations = 4)
        f.plantState()
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/state.rec") }
        f.startInFront()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val notice = f.notices.filterIsInstance<Notice.StorageUnreadable>().single()
        f.files.clearFaults()
        val before = f.stateRecord()
        await { f.engine.answerNotice(notice.id, NoticeAnswer.SET_UP_AGAIN) }
        val after = f.stateRecord()
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), after.practice)
        assertEquals(4L, after.maxGeneration.n)
        assertTrue(before != after)
    }

    // ---- what every unlock does to the records ----------------------------------------------------------

    @Test
    fun f16c_aLowerGenerationThanLastTimeIsReported_andAHigherOneIsStored() {
        val vault = f.seedVault(generations = 3)
        val lastTime = f.clock.wallMs() - day
        f.plantState(maxGeneration = StateRecord.MaxGeneration(7, lastTime)) // this phone has opened generation 7
        f.startInFront()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val notice = f.notices.filterIsInstance<Notice.OlderThanLastTime>().single()
        assertEquals(lastTime, notice.lastOpenedAtMs)
        assertEquals(7L, f.stateRecord().maxGeneration.n) // it must not roll back with the vault file

        f.lock() // not answered: it is shown again
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val again = f.notices.filterIsInstance<Notice.OlderThanLastTime>().single()
        await { f.engine.answerNotice(again.id, NoticeAnswer.OK) }
        assertEquals(StateRecord.MaxGeneration(3, f.clock.wallMs()), f.stateRecord().maxGeneration)

        vault.save()
        vault.save()
        f.lock()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices.filterIsInstance<Notice.OlderThanLastTime>())
        assertEquals(5L, f.stateRecord().maxGeneration.n)
    }

    @Test
    fun section54_aMasterPasswordUnlockDropsTheWrapsOfKeysThatAreNoLongerKept() {
        val vault = f.seedVault(password = "the old one")
        val oldKey = vault.newestVkId
        val newKey = vault.rekey(bytes(PASSWORD))
        vault.generation(1).suffix = FakeVaultPort.Suffix.BAD // the generation under the old key is gone from the live ones
        val pin = f.plantPin(vault, vkIds = listOf(oldKey, newKey))
        val finger = f.plantFingerprint(vault, vkIds = listOf(newKey, oldKey))
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)

        assertEquals(listOf(newKey), f.pinRecord().slot!!.wraps.map { it.vkId })
        assertEquals(listOf(newKey), f.fingerprintRecord().slot!!.wraps.map { it.vkId })
        assertEquals(pin.id, f.pinRecord().slot!!.id)
        assertEquals(finger.id, f.fingerprintRecord().slot!!.id)
        assertEquals(2, f.keys.aliases.size)
    }

    @Test
    fun section54_aSlotLeftWithoutAnyWrapIsRemoved_andAPinIsOfferedAgain() {
        val vault = f.seedVault(password = "the old one")
        val oldKey = vault.newestVkId
        vault.rekey(bytes(PASSWORD))
        vault.generation(1).suffix = FakeVaultPort.Suffix.BAD
        val pin = f.plantPin(vault, vkIds = listOf(oldKey)) // the change did not keep the PIN (F8 step 3, "Not now")
        val finger = f.plantFingerprint(vault, vkIds = listOf(oldKey))
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()

        assertEquals(MpReason.MasterChanged, f.field.reason) // "You changed your master password. Type it once, then choose a PIN again."
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.TurnOnAgain, f.engine.summary.value.fingerprint)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)

        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.OffReason.MASTER_CHANGED, f.pinRecord().off!!.reason)
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.MASTER_CHANGED, f.fingerprintRecord().off!!.reason)
        assertEquals(setOf(PinWrap.alias(pin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id), f.keys.deleted.toSet())
        assertEquals(
            listOf(ChoosePinReason.MASTER_CHANGED),
            f.notices.filterIsInstance<Notice.ChoosePin>().map { it.reason },
        )
        assertEquals(1, f.notices.filterIsInstance<Notice.FingerprintAgain>().size)
        assertEquals(LockSummary.Fingerprint.TurnOnAgain, f.engine.summary.value.fingerprint)
    }

    @Test
    fun f8step10_afterTheNewMasterPasswordOpenedTheVaultInALaterProcess_storageRetiresTheOldKeys() {
        val vault = f.seedVault(password = "the old one", generations = 2)
        val oldKey = vault.newestVkId
        val newKey = vault.rekey(bytes(PASSWORD))
        f.plantPin(vault, vkIds = listOf(oldKey, newKey))
        f.plantState(maxGeneration = StateRecord.MaxGeneration(3, f.clock.wallMs()))
        f.startInFront()

        f.type(PIN) // a PIN unlock retires nothing: the new master password has not been typed yet
        assertFalse("retireOldKeys" in f.port.calls)
        assertEquals(3, vault.live.size)
        assertEquals(2, f.pinRecord().slot!!.wraps.size) // both keys are still kept
        f.lock()

        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(listOf(3L), vault.live.map { it.number })
        assertEquals(listOf(newKey), f.pinRecord().slot!!.wraps.map { it.vkId })
        assertEquals(2, f.port.writes.size) // the two generations under the old key, and nothing else
        assertTrue(f.port.writes.all { it.endsWith("retired with its key") })
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun anUnlockThatFindsNoKeptGenerationWithTheNewKey_forgetsTheMarkOfTheChange() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState(mpChange = StateRecord.MpChange("00000000-0000-4000-8000-0000000000ee", f.clock.wallMs()))
        f.startInFront()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertNull(f.stateRecord().mpChange)
    }

    @Test
    fun aRecordWriteThatFailsAfterAnUnlock_isMadeGoodByTheNextOneThatSucceeds() {
        val vault = f.seedVault(generations = 2)
        f.plantPin(vault, row = 1, total = 1)
        f.plantState()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) // the phone's storage is full
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right) // the master-password path needs no write (I10)
        assertEquals(PinRecord.Guard(false, 1, 1), f.pinRecord().guard)
        assertEquals(1L, f.stateRecord().maxGeneration.n)

        f.files.clearFaults()
        f.lock()
        f.type(PIN) // the next write of pin.rec stores the whole record the lock means to have
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        // state.rec has had no write since; what is on disk is the older, safe value, and the next start stores the newer one.
        assertEquals(1L, f.stateRecord().maxGeneration.n)
        f.restart()
        f.startInFront()
        f.type(PIN)
        assertEquals(2L, f.stateRecord().maxGeneration.n)
        assertEquals(emptyList<Notice>(), f.notices)
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** The JSON of a framed record (lock.md §5.3): after the 8-byte magic and the 4-byte length, before the 32-byte hash. */
    private fun json(record: ByteArray): ByteArray = record.copyOfRange(12, record.size - 32)

    /** [json] framed as a record: magic, length, JSON, SHA-256. */
    private fun reframed(json: String): ByteArray {
        val body = json.toByteArray(Charsets.US_ASCII)
        val head = "PVLOCK01".toByteArray(Charsets.US_ASCII) +
            byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
        return head + MessageDigest.getInstance("SHA-256").digest(head)
    }
}
