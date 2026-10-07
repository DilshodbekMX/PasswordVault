package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Scripted runs of F2, the daily unlock with the PIN, F4, a wrong PIN, and F5, the PIN limit
 * (lock.md §12), through the engine: what is on disk, what the Keystore was asked, what the vault
 * port was asked, and the screen after every step; with the visit bookkeeping of §6.3 (I4, I11).
 */
class PinUnlockFlowTest {
    private val f = LockFixture()
    private val pinFile = "replace " + f.dirOf(VAULT) + "/pin.rec"

    /** A vault with a PIN and a state record that asks for no practice, started and in front. */
    private fun withPin(row: Int = 0, total: Int = 0, fixture: LockFixture = f): FakeVaultPort.FakeVault {
        val vault = fixture.seedVault()
        fixture.plantPin(vault, row = row, total = total)
        fixture.plantState()
        fixture.startInFront()
        return vault
    }

    // ---- F2 ----------------------------------------------------------------------------------------

    @Test
    fun f2_theDailyUnlock() {
        withPin()
        // Step 1: the pad; nothing typed, nothing said, no fingerprint key.
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)

        // Step 3: the first digit is a charge, on disk before anything else.
        val chargedAt = f.clock.elapsedMs()
        f.type("5")
        assertEquals(PinRecord.Guard(charged = true, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(1, f.pad.digits)
        f.type("8291")
        assertEquals(5, f.pad.digits)
        assertEquals(1, f.files.log.count { it == pinFile }) // one write so far
        assertEquals(0, f.keys.hmacCalls.size)

        // Step 4: the sixth digit is checked once the charge is a second old (I4).
        f.type("3")
        val hmac = f.keys.hmacCalls.single()
        assertTrue(hmac.atMs >= chargedAt + 1_000)
        assertEquals(listOf(chargedAt + 1_000), f.clock.waits)

        // Step 5: the vault opens, by cryptography alone (I7); no notice.
        assertEquals(LockState.Unlocked(emptyList(), null), f.state)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(listOf("vaults", "open current"), f.port.calls)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(2, f.files.log.count { it == pinFile })
        assertTrue(f.port.writes.isEmpty())
        assertNull(f.engine.core.visit) // the visit ended with the unlock
    }

    @Test
    fun f2_theListIsShownBeforeTheRecordOfTheRightPinIsWritten() {
        withPin()
        val statesAtPinWrites = ArrayList<LockState>()
        f.files.onBoundary = { _, at -> if (at == "$pinFile: before the temp write") statesAtPinWrites += f.state }
        f.type(PIN)
        assertEquals(2, statesAtPinWrites.size)
        assertTrue(statesAtPinWrites[0] is LockState.Locked) // the charge
        assertTrue(statesAtPinWrites[1] is LockState.Unlocked) // the right PIN's record, after the list (decision 27)
    }

    @Test
    fun f2_aKillBeforeThatWrite_costsOneCountedTryAtTheNextStart() {
        withPin()
        f.files.stopAt(5) // 4 boundaries of the charge, then the first of the write that follows the unlock
        try {
            f.type(PIN)
            fail("the process should have died")
        } catch (e: FakeRecordFiles.Stopped) {
            assertTrue(f.state is LockState.Unlocked) // the app was open this time (§6.10)
        }
        f.restart()
        f.startInFront()
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)
        assertEquals(PadStatus.Interrupted(4), f.pad.status)
    }

    @Test
    fun f2_aRecordWriteThatFailsAfterARightPin_doesNotKeepTheListBack() {
        withPin()
        f.type("5")
        f.files.failNextWrite()
        f.type("82913")
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinRecord().guard.charged) // on disk: counted once at the next start, the safe side
    }

    @Test
    fun f2_deletingBackToNone_givesTheEntryUpAndRefundsItsCharge() {
        withPin()
        f.type("58")
        await { f.engine.deleteDigit() }
        assertEquals(1, f.pad.digits)
        assertTrue(f.pinRecord().guard.charged)
        await { f.engine.deleteDigit() }
        assertEquals(0, f.pad.digits)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard) // a refund never touches a count
        await { f.engine.deleteDigit() } // nothing to delete
        assertEquals(0, f.pad.digits)

        f.type("582")
        await { f.engine.clearDigits() } // a long press on the delete key
        assertEquals(0, f.pad.digits)
        assertFalse(f.pinRecord().guard.charged)

        f.type(PIN) // a new entry charges again and opens
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(0, f.pinRecord().guard.row)
    }

    @Test
    fun f2_theChargeThatCannotBeWritten_storageIsFull_nothingCountedNothingChecked() {
        withPin()
        f.files.failNextWrite()
        f.type("5")
        assertEquals(0, f.pad.digits) // the dots are cleared
        assertEquals(PadStatus.StorageFull, f.pad.status)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(0, f.keys.hmacCalls.size)

        f.type(PIN) // space was freed
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f2_keysPressedWhileThePinIsCheckedAreDropped() {
        val clock = FakeClock(elapsedMs = 3_600_000L, autoAdvance = false)
        val g = LockFixture(clock)
        withPin(fixture = g)
        g.type("58291")
        runBlocking {
            val sixth = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.digit(4) } // waits for the age of the charge
            assertEquals(LockScreen.Pad(digits = 6, status = null, fingerprintKey = false, opening = true, aside = null), g.screen)
            val extra = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.digit(7) }
            val delete = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.deleteDigit() }
            assertEquals(0, g.keys.hmacCalls.size) // no HMAC before the charge is a second old (I4)
            clock.advance(999)
            assertEquals(0, g.keys.hmacCalls.size)
            clock.advance(1)
            sixth.join()
            extra.join()
            delete.join()
        }
        assertEquals(1, g.keys.hmacCalls.size)
        assertEquals(LockScreen.Pad(digits = 0, status = PadStatus.WrongPin(4), fingerprintKey = false, opening = false, aside = null), g.screen)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), g.pinRecord().guard) // the dropped key began no new entry
    }

    // ---- F4 ----------------------------------------------------------------------------------------

    @Test
    fun f4_aWrongPin_isCountedBeforeAnythingIsShown() {
        withPin()
        var screenWhenTheCountWasWritten: LockState? = null
        f.type("58291")
        f.files.onBoundary = { _, at -> if (at == "$pinFile: after the directory sync") screenWhenTheCountWasWritten = f.state }
        f.type("4")

        // F4 step 1: the count is on disk while the screen still says "Opening…".
        assertEquals(LockState.Locked(LockScreen.Pad(digits = 6, status = null, fingerprintKey = false, opening = true, aside = null)), screenWhenTheCountWasWritten)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)
        assertEquals(PinRecord.Unseen(1, f.clock.wallMs(), false), f.pinRecord().unseen)
        assertEquals(LockScreen.Pad(digits = 0, status = PadStatus.WrongPin(4), fingerprintKey = false, opening = false, aside = null), f.screen)
        assertNull(f.port.openVaultId)
        assertEquals(listOf("vaults"), f.port.calls) // storage was not asked to open anything

        // The line stays while digits are typed and deleted (§13.3).
        f.type("12")
        assertEquals(PadStatus.WrongPin(4), f.pad.status)
        await { f.engine.clearDigits() }
        assertEquals(PadStatus.WrongPin(4), f.pad.status)

        // The same wrong PIN again costs nothing (§6.6).
        f.type(WRONG_PIN)
        assertEquals(PadStatus.SameAgain, f.pad.status)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)
        assertEquals(1, f.keys.hmacCalls.size)

        // Another wrong one is counted; then the right one opens and forgives both slips of this visit.
        f.type("582915")
        assertEquals(PadStatus.WrongPin(3), f.pad.status)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(0, f.pinRecord().unseen.count)
        assertEquals(emptyList<Notice>(), f.notices) // the person at the screen has just watched it happen
    }

    @Test
    fun f4_aWrongPinThatStoodForMoreThanAMinuteIsNotForgiven_andIsReported() {
        withPin()
        f.type(WRONG_PIN)
        val wrongAt = f.clock.wallMs()
        f.clock.advance(61_000)
        f.type(PIN)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 1), f.pinRecord().guard)
        val notice = f.notices.single() as Notice.WrongTries
        assertEquals(1, notice.wrongPins)
        assertEquals(wrongAt, notice.lastWrongPinAtMs)
        assertFalse(notice.interrupted)
        assertNull(notice.pinsBeforeOff)
        assertEquals(0, notice.wrongMasterPasswords)

        // When the user answers, the number that was shown is taken off `unseen`.
        await { f.engine.answerNotice(notice.id, NoticeAnswer.OK) }
        assertEquals(emptyList<Notice>(), f.notices)
        assertEquals(PinRecord.Unseen(0, null, false), f.pinRecord().unseen)
        assertEquals(1, f.pinRecord().guard.total) // only a master password returns the second count to zero
    }

    @Test
    fun f4_aNoticeThatWasNeverAnsweredTakesNothingOff() {
        withPin()
        f.type(WRONG_PIN)
        f.clock.advance(61_000)
        f.type(PIN)
        f.lock() // locked before the notice was answered
        f.type(PIN)
        assertEquals(1, (f.notices.single() as Notice.WrongTries).wrongPins)
    }

    @Test
    fun f4_theWrongPinLineStaysUntilTheVisitEnds_andAFreshPadWarnsAtTwoTriesLeft() {
        withPin()
        f.type(WRONG_PIN)
        f.leaveAndReturn()
        assertNull(f.pad.status) // 4 tries left: a fresh pad says nothing
        f.type("111112")
        f.type("111113")
        assertEquals(PadStatus.WrongPin(2), f.pad.status)
        f.leaveAndReturn()
        assertEquals(PadStatus.TriesLeft(2), f.pad.status) // so that the PIN never turns off without a warning
        f.type("111114")
        assertEquals(PadStatus.LastTry, f.pad.status)
        f.leaveAndReturn()
        assertEquals(PadStatus.TriesLeft(1), f.pad.status)
    }

    @Test
    fun f4_fromSixNotForgiven_theNoticeSaysHowManyAreLeft() {
        withPin(row = 0, total = 6)
        f.type(WRONG_PIN)
        f.clock.advance(61_000)
        f.type(PIN)
        val notice = f.notices.single() as Notice.WrongTries
        assertEquals(3, notice.pinsBeforeOff) // total is 7: "After 3 more wrong PINs …"
        assertFalse(notice.offerNewMasterPassword) // practice is not past its grace
    }

    // ---- F5 ----------------------------------------------------------------------------------------

    @Test
    fun f5_theFifthWrongPinInARow_turnsThePinOff_andNothingElseChanges() {
        val vault = withPin(row = 4, total = 4)
        val slot = f.pinRecord().slot!!
        assertEquals(PadStatus.TriesLeft(1), f.pad.status)
        f.type(WRONG_PIN)

        // §6.5 step 1: one write with slot = null, off = limit and the guard as counted; step 2: the key at once.
        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.Off(PinRecord.OffReason.LIMIT, f.clock.wallMs()), record.off)
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), record.guard)
        assertEquals(listOf(PinWrap.alias(slot.id)), f.keys.deleted)
        assertEquals(emptyList<String>(), f.keys.aliases)
        // Step 3: without fingerprint, the master-password screen with its reason.
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
        assertFalse(f.field.canUsePin)
        assertEquals(LockSummary.Pin.Off(PinReason.LIMIT), f.engine.summary.value.pin)
        // Step 4: the vault is untouched (I1, I6).
        assertTrue(f.port.writes.isEmpty())
        assertEquals(listOf(1L), vault.generations.map { it.number })
        f.type(PIN) // no pad: keys do nothing
        assertEquals(1, f.keys.hmacCalls.size)

        // F5 steps 2 to 4: the master password opens the app; the notice, then "Choose a new PIN" with the proof of this unlock.
        val outcome = f.unlockWith(PASSWORD)
        val proof = (outcome as MasterPasswordOutcome.Right).proof
        val notices = f.notices
        assertEquals(2, notices.size)
        assertEquals(1, (notices[0] as Notice.WrongTries).wrongPins)
        val offer = notices[1] as Notice.ChoosePin
        assertEquals(ChoosePinReason.TURNED_OFF, offer.reason)
        assertFalse(offer.needsMasterPassword)
        assertTrue(f.engine.core.holds(proof))
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard) // a master password returns both counts
        assertNull(f.pinRecord().slot)
    }

    @Test
    fun f5_theTenthWrongPinNotForgiven_turnsThePinOff() {
        withPin(row = 0, total = 9)
        assertEquals(PadStatus.TriesLeft(1), f.pad.status)
        f.type(WRONG_PIN)
        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 10), f.pinRecord().guard)
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
    }

    @Test
    fun f5_withFingerprint_theFingerprintOnlyScreen_andAfterAFingerprintUnlockThePinStillNeedsTheMasterPassword() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 4, total = 4)
        val finger = f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        assertTrue(f.pad.fingerprintKey)
        f.type(WRONG_PIN)

        // §6.5 step 3: fingerprint is usable, so the fingerprint-only screen; the fingerprint slot is untouched (I6).
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.LIMIT, aside = null), f.screen)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(1, f.keys.aliases.size)

        await { f.engine.useFingerprint() }
        assertTrue(f.state is LockState.Unlocked)
        assertNull((f.state as LockState.Unlocked).proof)
        // A fingerprint unlock resets nothing and hides nothing (§6.4).
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), f.pinRecord().guard)
        assertEquals(1, (f.notices[0] as Notice.WrongTries).wrongPins)
        val offer = f.notices[1] as Notice.ChoosePin
        assertEquals(ChoosePinReason.TURNED_OFF, offer.reason)
        assertTrue(offer.needsMasterPassword) // "PIN unlock is off. To choose a new PIN you need your master password."

        // Not again at the next fingerprint unlock of this process.
        f.lock()
        await { f.engine.useFingerprint() }
        assertEquals(emptyList<Notice>(), f.notices.filterIsInstance<Notice.ChoosePin>())
        assertTrue(f.port.writes.isEmpty())
    }

    @Test
    fun f5_aLimitWriteThatFails_thePinIsOffInMemory_andTheNextStartReachesTheLimitAgain() {
        withPin(row = 4, total = 4)
        val slot = f.pinRecord().slot!!
        f.type("58291")
        f.files.failNextWrite()
        f.type("4")
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason) // off for this process
        assertTrue(f.pinRecord().guard.charged) // the charge is still on disk
        assertEquals(listOf(PinWrap.alias(slot.id)), f.keys.deleted)

        f.restart()
        f.startInFront()
        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.OffReason.LIMIT, f.pinRecord().off!!.reason)
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
    }

    // ---- section 14 on the pad: F15b and F15c ----------------------------------------------------------

    @Test
    fun aKeystoreThatDoesNotAnswer_theTryIsRefunded_andThePinIsNotOfferedAgainOnThisVisit() {
        withPin(row = 2, total = 2)
        val slot = f.pinRecord().slot
        f.keys.scriptHmac(Ks.Temporary(null))
        f.type(PIN)

        assertEquals(MpReason.Pin(PinReason.CANNOT_CHECK), f.field.reason)
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 2), f.pinRecord().guard) // nothing about the PIN was learned
        assertEquals(slot, f.pinRecord().slot) // nothing is removed and nothing stored (F15c)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)

        f.leaveAndReturn() // the next visit tries again
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun aPinKeyThatIsGone_itsSlotIsRemoved_andAfterTheUnlockAPinIsOfferedAgain() {
        withPin()
        val slot = f.pinRecord().slot!!
        f.keys.dropAllKeys() // every key of the app is gone; the Keystore itself answers
        f.type(PIN)

        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.KEY_GONE, record.off!!.reason)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), record.guard)
        assertTrue(PinWrap.alias(slot.id) in f.keys.deleted)
        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.STOPPED_WORKING), f.engine.summary.value.pin)

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        val offer = f.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.STOPPED_WORKING, offer.reason)
        assertFalse(offer.needsMasterPassword)
    }

    // ---- F16a: an older copy ----------------------------------------------------------------------------

    @Test
    fun f16a_theNewestCopyIsDamaged_andTheShortcutHoldsTheOlderCopysKey_thePinOpensIt() {
        val vault = f.seedVault(generations = 3)
        f.plantPin(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED
        f.startInFront()
        f.type(PIN)
        assertEquals(2L, f.port.openGeneration)
        val notice = f.notices.single() as Notice.OlderCopy
        assertEquals(vault.generation(2).createdMs, notice.openedCreatedMs)
        assertTrue(f.port.writes.isEmpty()) // opening never writes, renames or deletes
    }

    @Test
    fun f16a_theOlderCopyHasAnotherKey_theMasterPasswordScreenNamesIt_andTheShortcutIsKept() {
        val vault = f.seedVault(password = "the password of that time")
        val newKey = vault.rekey(LockFixture.bytes(PASSWORD)) // generation 2, under a new key
        val slot = f.plantPin(vault, vkIds = listOf(newKey))
        f.plantState()
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED
        f.startInFront()

        assertEquals(MpReason.OlderCopy(vault.generation(1).createdMs), f.field.reason)
        assertEquals(slot, f.pinRecord().slot) // nothing is removed for a mismatch (I6)
        assertTrue(f.unlockWith("the password of that time") is MasterPasswordOutcome.Right)
        assertEquals(slot, f.pinRecord().slot)
        assertTrue(f.notices.first() is Notice.OlderCopy)
    }

    @Test
    fun aRightPinWhoseCopyDoesNotOpen_isStoragesFlow_andStillCountsAsRight() {
        val vault = f.seedVault()
        val slot = f.plantPin(vault, row = 2, total = 2)
        f.plantState()
        // The wrap opens under the right PIN, and the bytes in it are not this vault's key.
        val alias = PinWrap.alias(slot.id)
        val kek = f.keys.hmacOf(alias, PinWrap.message(VAULT, slot.id, LockFixture.digits(PIN)))
        val wrong: KeyWrap = PinWrap.seal(f.aead, kek, ByteArray(12) { 9 }, ByteArray(32) { 7 }, VAULT, vault.newestVkId, slot.id)
        f.putPin(f.pinRecord().copy(slot = slot.copy(wraps = listOf(wrong))))
        f.startInFront()
        f.type(PIN)

        assertEquals(LockState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()), f.state)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 2), f.pinRecord().guard)
        assertNull(f.port.openVaultId)
        await { f.engine.refresh() } // "Try again"
        assertTrue(f.screen is LockScreen.Pad)
    }

    // ---- visits (§6.3, I11) -----------------------------------------------------------------------------

    @Test
    fun whenTheAppStopsBeingVisible_theVisitEnds_theChargeIsRefundedAndTheDigitsAreGone() {
        withPin()
        f.type("582")
        val first = f.engine.core.visit!!.place
        await { f.engine.appStopped() }
        assertTrue(first.hasEnded)
        assertNull(f.engine.core.visit)
        assertFalse(f.pinRecord().guard.charged)
        assertEquals(0, f.pad.digits)

        await { f.engine.appStarted() }
        val second = f.engine.core.visit!!.place
        assertTrue(second.number > first.number)
        f.type(PIN) // a whole new entry, with its own charge
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(1, f.keys.hmacCalls.size) // one check in all: the entry that was given up was never checked
    }

    @Test
    fun i11_aPinWhoseVisitEndedWhileItWaited_countsAsRight_andOpensNothing() {
        val clock = FakeClock(elapsedMs = 3_600_000L, autoAdvance = false)
        val g = LockFixture(clock)
        withPin(row = 2, total = 2, fixture = g)
        g.type("58291")
        runBlocking {
            val sixth = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.digit(3) } // the right PIN, waiting for its charge to age
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.appStopped() } // the screen is switched off meanwhile
            clock.advance(1_000)
            sixth.join()
            stop.join()
        }
        assertEquals(1, g.keys.hmacCalls.size) // the try was checked and counted like any other
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 2), g.pinRecord().guard)
        assertTrue(g.state is LockState.Locked)
        assertNull(g.port.openVaultId)
        assertFalse("open current" in g.port.calls)
    }

    @Test
    fun i11_aVisitThatEndsWhileStorageOpensTheVault_theVaultIsDroppedAgain() {
        withPin()
        runBlocking {
            f.port.beforeCall = { call ->
                if (call == "open current") launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() }
            }
            f.engine.digit(5)
            for (digit in listOf(8, 2, 9, 1, 3)) f.engine.digit(digit)
        }
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        assertEquals(listOf("vaults", "open current", "lock"), f.port.calls)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
    }

    @Test
    fun aLockWhileTheAppIsLocked_changesNothing() {
        withPin()
        f.type("58")
        val visit = f.engine.core.visit
        f.lock()
        assertTrue(visit === f.engine.core.visit)
        assertEquals(2, f.pad.digits)
        assertFalse("lock" in f.port.calls)
    }

    @Test
    fun aLock_dropsTheSession_listsTheVaultsAgain_andStartsANewVisit() {
        val vault = withPin()
        f.type(PIN)
        vault.save() // the app saved while it was open
        f.lock()
        assertEquals(listOf("vaults", "open current", "lock", "vaults"), f.port.calls)
        assertNull(f.port.openVaultId)
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertEquals(2L, f.port.openGeneration)
        assertEquals(2L, f.stateRecord().maxGeneration.n) // the highest generation this phone has opened (F16c)
    }

    // ---- §5.4: the wraps at a right PIN ------------------------------------------------------------------

    @Test
    fun aRightPinDropsTheWrapWhoseKeyNoKeptGenerationCarries() {
        val vault = f.seedVault()
        val current = vault.newestVkId
        val never = "00000000-0000-4000-8000-0000000000ff" // the key of a change that never reached the disk
        // A slot with two wraps: one for the vault's key, one for a key no generation carries.
        val slotId = "00000000-0000-4000-8000-000000000033"
        val alias = PinWrap.alias(slotId)
        f.keys.canary = true
        f.keys.putPinKey(alias, ByteArray(32) { 0x42 })
        val kek = f.keys.hmacOf(alias, PinWrap.message(VAULT, slotId, LockFixture.digits(PIN)))
        val kept = PinWrap.seal(f.aead, kek, ByteArray(12) { 1 }, vault.vk(current), VAULT, current, slotId)
        val unused = PinWrap.seal(f.aead, kek, ByteArray(12) { 2 }, ByteArray(32) { 5 }, VAULT, never, slotId)
        f.putPin(
            PinRecord(
                VAULT,
                ShortcutSlot(slotId, f.clock.wallMs(), listOf(kept, unused)),
                PinRecord.Guard(false, 0, 0),
                null,
                PinRecord.Unseen(0, null, false),
            ),
        )
        f.plantState(mpChange = StateRecord.MpChange(never, f.clock.wallMs()))
        f.startInFront()
        f.type(PIN)

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(listOf(kept), f.pinRecord().slot!!.wraps)
        assertNull(f.stateRecord().mpChange) // the change never reached the disk (§5.6)
    }
}
