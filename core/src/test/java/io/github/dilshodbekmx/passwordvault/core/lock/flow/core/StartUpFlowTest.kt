package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of process start, lock.md §5.11, step by step, and of the screen it ends on
 * (§13.1): Welcome, "Your passwords can't be opened", or a lock screen; with F14, a vault that has no
 * lock records.
 */
class StartUpFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    private fun pending(vkId: String, creditedMs: Long = 0L, seenElapsedMs: Long = f.clock.elapsedMs()) = StateRecord.PendingMp(
        requestedMs = f.clock.wallMs(),
        by = Shortcut.PIN,
        creditedMs = creditedMs,
        seenElapsedMs = seenElapsedMs,
        vkId = vkId,
        salt = ByteArray(16) { 1 },
        nonce = ByteArray(12) { 2 },
        ct = ByteArray(48) { 3 },
    )

    private fun replaced(newVaultId: String) =
        StateRecord.Replaced(newVaultId, atMs = f.clock.wallMs(), by = Shortcut.PIN, creditedMs = 0L, seenElapsedMs = f.clock.elapsedMs())

    // ---- the first screen ------------------------------------------------------------------------

    @Test
    fun withoutAVault_welcomeListsTheVaultsPutAside() {
        f.port.seedAside(OTHER_VAULT, bytes("an older password"), putAsideAtMs = 1_790_000_000_000L)
        assertEquals(LockState.Starting, f.state)
        f.startInFront()

        val welcome = f.state as LockState.NoVault
        assertEquals(listOf(OTHER_VAULT), welcome.aside.map { it.vaultId })
        assertEquals(1_790_000_000_000L, welcome.aside.single().putAsideAtMs)
        assertEquals(LockSummary.NONE.copy(aside = welcome.aside), f.engine.summary.value)
        assertTrue(f.port.writes.isEmpty())
    }

    @Test
    fun vaultsThatCannotBeListed_areAStorageError_neverNoVault() {
        f.port.scanFails = true
        f.startInFront()
        assertEquals(LockState.Unreadable(CurrentVault.Why.IO, emptyList()), f.state)

        f.port.scanFails = false // "Try again"
        await { f.engine.refresh() }
        assertEquals(LockState.NoVault(emptyList()), f.state)
    }

    @Test
    fun aVaultNoGenerationOfWhichCanBeRead_cantBeOpened_andTryAgainListsItAgain() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        vault.live.first().state = FakeVaultPort.FileState.UNREADABLE
        f.startInFront()
        assertEquals(LockState.Unreadable(CurrentVault.Why.IO, emptyList()), f.state)

        vault.live.first().state = FakeVaultPort.FileState.OK
        await { f.engine.refresh() }
        assertTrue(f.screen is LockScreen.Pad)
        assertTrue(f.port.writes.isEmpty()) // never the lock, never an offer to delete: nothing was touched
    }

    @Test
    fun aDamagedVault_andOneOfANewerApp_cantBeOpened() {
        val vault = f.seedVault()
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED
        f.startInFront()
        assertEquals(LockState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()), f.state)

        vault.live.first().state = FakeVaultPort.FileState.NEWER
        f.restart()
        f.startInFront()
        assertEquals(LockState.Unreadable(CurrentVault.Why.NEWER, emptyList()), f.state)
    }

    @Test
    fun startRunsOnce_andTheScreenIsThereBeforeTheAppIsVisible() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        await { f.engine.start() }
        assertTrue(f.screen is LockScreen.Pad) // decided, with no visit yet
        val scans = f.port.calls.count { it == "vaults" }
        await { f.engine.start() }
        assertEquals(scans, f.port.calls.count { it == "vaults" })

        f.type("5") // not visible: no visit, so the pad takes no key
        assertEquals(0, f.pad.digits)
        assertFalse(f.pinRecord().guard.charged)
        await { f.engine.appStarted() }
        f.type("5")
        assertEquals(1, f.pad.digits)
        assertTrue(f.pinRecord().guard.charged)
    }

    // ---- step 1 ----------------------------------------------------------------------------------

    @Test
    fun step1_staleTmpFilesAreDeleted() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.files.put(f.dirOf(VAULT), "pin.rec.tmp", byteArrayOf(1, 2, 3))
        f.files.put(f.dirOf(OTHER_VAULT), "state.rec.tmp", byteArrayOf(4))
        f.startInFront()
        assertEquals(listOf("pin.rec"), f.files.names(f.dirOf(VAULT)))
        assertEquals(emptyList<String>(), f.files.names(f.dirOf(OTHER_VAULT)))
    }

    @Test
    fun step1_aFailureToDeleteThemStopsNothing() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.files.fail(FakeRecordFiles.Op.DELETE_STALE_TMP)
        f.startInFront()
        assertTrue(f.screen is LockScreen.Pad)
    }

    // ---- F14: a vault without lock records ---------------------------------------------------------

    @Test
    fun f14_aVaultWithoutLockRecords_welcomeBack_thenItsStateRecordAndThePinOffer() {
        f.seedVault(generations = 3) // as a build without lock records left it: no lock directory at all
        f.startInFront()

        val field = f.field
        assertEquals(MpReason.WelcomeBack, field.reason)
        assertFalse(field.canUsePin)
        assertNull(field.practice) // every unlock is the master password already: practice is not asked
        assertFalse(f.files.hasDir(f.dirOf(VAULT)))
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)

        val outcome = f.unlockWith(PASSWORD)
        assertTrue(outcome is MasterPasswordOutcome.Right)
        val unlocked = f.state as LockState.Unlocked
        // Step 2: the directory and state.rec (§5.3: step 0, last_ok now), then F1 step 5 and the PIN offer with the proof just made.
        val record = f.stateRecord()
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), record.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), record.mpGuard)
        assertEquals(StateRecord.MaxGeneration(3, f.clock.wallMs()), record.maxGeneration)
        assertNull(record.pendingMp)
        assertNull(record.replaced)
        assertTrue(f.keys.canary)
        assertEquals(listOf(ChoosePinReason.NONE_HERE to false), unlocked.notices.map { (it as Notice.ChoosePin).reason to it.needsMasterPassword })
        val proof = (outcome as MasterPasswordOutcome.Right).proof
        assertEquals(VAULT, proof.vaultId)
        assertTrue(proof === unlocked.proof)
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        assertEquals(RecordRead.Missing, f.pinOnDisk()) // pin.rec comes with the PIN (F6)
        assertTrue(f.port.writes.isEmpty())

        // The next start finds its records: no "Welcome back" any more, and no PIN was set.
        f.restart()
        f.startInFront()
        assertNull(f.field.reason)
    }

    @Test
    fun f14_aWrongMasterPasswordThere_isCountedInMemoryAndWrittenWithTheRecord() {
        f.seedVault()
        f.startInFront()
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))
        assertEquals(RecordRead.Missing, f.stateOnDisk()) // nothing is written before a successful unlock (§5.3)
        f.clock.advance(120_000) // not put right at once: it stays unseen
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 1), f.stateRecord().mpGuard)
        assertEquals(1, f.notices.filterIsInstance<Notice.WrongTries>().single().wrongMasterPasswords)
    }

    // ---- step 3: replaced vaults -------------------------------------------------------------------

    @Test
    fun step3_row1_theReplacementWasCarriedOut_theOldVaultLosesItsShortcutsAndKeepsItsMark() {
        f.seedVault() // vault N, current
        f.plantState(VAULT)
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        val pin = f.plantPin(old)
        val finger = f.plantFingerprint(old)
        val mark = replaced(VAULT)
        f.plantState(OTHER_VAULT, pendingMp = pending(old.newestVkId), replaced = mark)
        f.startInFront()

        val pinRec = f.pinRecord(OTHER_VAULT)
        assertNull(pinRec.slot)
        assertEquals(PinRecord.OffReason.REPLACED, pinRec.off!!.reason)
        val fingerRec = f.fingerprintRecord(OTHER_VAULT)
        assertNull(fingerRec.slot)
        assertEquals(FingerprintRecord.OffReason.REPLACED, fingerRec.off!!.reason)
        val state = f.stateRecord(OTHER_VAULT)
        assertNull(state.pendingMp)
        assertEquals(mark.newVaultId, state.replaced!!.newVaultId) // `replaced` stays
        assertEquals(emptyList<String>(), f.keys.aliases)
        assertTrue(PinWrap.alias(pin.id) in f.keys.deleted)
        assertTrue(RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id in f.keys.deleted)
        assertTrue(f.port.writes.isEmpty())

        // The same again at the next start changes nothing more, and writes nothing.
        val before = f.files.log.size
        f.restart()
        f.startInFront()
        assertEquals(emptyList<String>(), f.files.log.drop(before).filter { it.startsWith("replace") })
    }

    @Test
    fun step3_row2_theOwnerWentBack_theSameAndTheMarkGoes() {
        val old = f.seedVault(vaultId = OTHER_VAULT, password = "the old password") // current again
        f.port.seedAside(VAULT, bytes(PASSWORD)) // vault N, put aside by going back
        val pin = f.plantPin(old)
        f.plantFingerprint(old)
        f.plantState(OTHER_VAULT, pendingMp = pending(old.newestVkId), replaced = replaced(VAULT))
        f.startInFront()

        assertNull(f.pinRecord(OTHER_VAULT).slot)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(OTHER_VAULT).off!!.reason)
        assertNull(f.fingerprintRecord(OTHER_VAULT).slot)
        val state = f.stateRecord(OTHER_VAULT)
        assertNull(state.pendingMp)
        assertNull(state.replaced)
        assertEquals(emptyList<String>(), f.keys.aliases)
        assertTrue(PinWrap.alias(pin.id) in f.keys.deleted)
        // Its lock screen: the master password only, as for a vault that never had a PIN.
        assertNull(f.field.reason)
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)
    }

    @Test
    fun step3_row2_aShortcutRecordThatCannotBeReadAtTwoStarts_keepsTheMarkUntilItsSlotIsGone() {
        for (record in listOf("pin.rec", "fingerprint.rec")) {
            val g = LockFixture()
            val old = g.seedVault(vaultId = OTHER_VAULT, password = "the old password") // current again
            g.port.seedAside(VAULT, bytes(PASSWORD)) // vault N, put aside by going back
            g.plantPin(old)
            g.plantFingerprint(old)
            g.plantState(
                OTHER_VAULT,
                replaced = StateRecord.Replaced(VAULT, atMs = g.clock.wallMs(), by = Shortcut.PIN, creditedMs = 0L, seenElapsedMs = g.clock.elapsedMs()),
            )
            // Two starts at which this record of the old vault cannot be read (lock.md §5.3 UNREADABLE: an I/O
            // error that may pass). Its slot cannot be removed then, so the mark must stay: start-up removes
            // what F16b step 5 did not only while the mark is there (§5.11 step 3, row 2).
            g.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/$OTHER_VAULT/$record") }
            g.startInFront()
            assertNotNull("$record, first start: the mark stays while that slot may still be there", g.stateRecord(OTHER_VAULT).replaced)
            g.restart()
            g.startInFront()
            assertNotNull("$record, second start: the mark is still there", g.stateRecord(OTHER_VAULT).replaced)

            // The error passes, and the phone starts again: now both slots go, and then the mark.
            g.files.clearFaults()
            g.restart()
            g.startInFront()
            assertNull("$record: the old PIN slot", g.pinRecord(OTHER_VAULT).slot)
            assertNull("$record: the old fingerprint slot", g.fingerprintRecord(OTHER_VAULT).slot)
            assertNull("$record: the mark", g.stateRecord(OTHER_VAULT).replaced)
            // Whoever replaced the master password knew the old PIN (F11 step 3): it opens nothing here.
            assertTrue("$record: ${g.screen}", g.screen is LockScreen.MasterPassword)
            assertEquals(record, LockSummary.Pin.NotSet, g.engine.summary.value.pin)
            assertEquals(record, emptyList<String>(), g.keys.aliases)
        }
    }

    @Test
    fun step3_row3_theReplacementDidNotTakePlace_onlyTheMarkGoes() {
        val vault = f.seedVault()
        val pin = f.plantPin(vault)
        val finger = f.plantFingerprint(vault)
        val waiting = pending(vault.newestVkId)
        f.plantState(VAULT, pendingMp = waiting, replaced = replaced(THIRD_VAULT)) // vault N was never published
        f.startInFront()

        val state = f.stateRecord()
        assertNull(state.replaced)
        assertEquals(waiting.vkId, state.pendingMp!!.vkId) // kept, so the owner gets in as before
        assertEquals(pin, f.pinRecord().slot)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(2, f.keys.aliases.size)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertTrue(f.screen is LockScreen.Pad)
    }

    @Test
    fun step3_row3_alsoForAVaultPutAside() {
        f.seedVault()
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        val pin = f.plantPin(old)
        f.plantState(OTHER_VAULT, replaced = replaced(THIRD_VAULT))
        f.startInFront()
        assertNull(f.stateRecord(OTHER_VAULT).replaced)
        assertEquals(pin, f.pinRecord(OTHER_VAULT).slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    @Test
    fun step3_aLockDirectoryWhoseVaultIsGone_isLeftAlone() {
        f.seedVault()
        val gone = f.port.seedAside(OTHER_VAULT, bytes("x"))
        val pin = f.plantPin(gone)
        val mark = replaced(VAULT)
        f.plantState(OTHER_VAULT, replaced = mark)
        await { f.port.deleteAside(OTHER_VAULT) } // the vault is no longer on the phone; its lock directory is
        f.restart()
        f.startInFront()

        assertEquals(pin, f.pinRecord(OTHER_VAULT).slot)
        assertEquals(mark.newVaultId, f.stateRecord(OTHER_VAULT).replaced!!.newVaultId)
        assertEquals(listOf(PinWrap.alias(pin.id)), f.keys.aliases) // named by a record: the sweep leaves it too
    }

    @Test
    fun step3_isSkippedWhenStorageCannotSayWhichVaultsAreThere() {
        val current = f.seedVault()
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        val pin = f.plantPin(old)
        f.plantState(OTHER_VAULT, replaced = replaced(VAULT))
        current.live.first().state = FakeVaultPort.FileState.UNREADABLE // is vault N here? it cannot be read now
        f.startInFront()
        assertEquals(pin, f.pinRecord(OTHER_VAULT).slot)
        assertNotNull(f.stateRecord(OTHER_VAULT).replaced)

        f.port.scanFails = true
        f.restart()
        f.startInFront()
        assertEquals(pin, f.pinRecord(OTHER_VAULT).slot)
        assertNotNull(f.stateRecord(OTHER_VAULT).replaced)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    @Test
    fun step3_aWriteThatFailsIsMadeGoodAtTheNextStart_andUntilThenTheLockBehavesAsIfItHadSucceeded() {
        f.seedVault()
        f.plantState(VAULT)
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        val pin = f.plantPin(old)
        f.plantState(OTHER_VAULT, replaced = replaced(VAULT))
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) // the phone's storage is full
        f.startInFront()

        assertEquals(pin, f.pinRecord(OTHER_VAULT).slot) // on disk as before
        assertTrue(PinWrap.alias(pin.id) in f.keys.deleted) // and its key is gone at once (§5.7)

        f.files.clearFaults()
        f.restart()
        f.startInFront()
        assertNull(f.pinRecord(OTHER_VAULT).slot)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(OTHER_VAULT).off!!.reason)
    }

    // ---- step 5: the counter's start-up step -------------------------------------------------------

    @Test
    fun step5_aChargeFoundIsCounted_andThePadSaysTheEntryWasInterrupted() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 1, total = 3, charged = true)
        f.plantState()
        f.startInFront()

        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 4), f.pinRecord().guard)
        assertEquals(PinRecord.Unseen(1, null, true), f.pinRecord().unseen)
        assertEquals(PadStatus.Interrupted(3), f.pad.status)
        assertEquals(0, f.keys.hmacCalls.size) // counted without being checked

        // It is said until a PIN has been typed, also on the next visit.
        f.leaveAndReturn()
        assertEquals(PadStatus.Interrupted(3), f.pad.status)
        f.type(LockFixture.WRONG_PIN)
        assertEquals(PadStatus.WrongPin(2), f.pad.status)
        f.leaveAndReturn()
        assertEquals(PadStatus.TriesLeft(2), f.pad.status)
    }

    @Test
    fun step5_f5_aChargeFoundAtStartThatReachesTheLimitTurnsThePinOff() {
        val vault = f.seedVault()
        val slot = f.plantPin(vault, row = 4, total = 4, charged = true)
        f.plantState()
        f.startInFront()

        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.LIMIT, record.off!!.reason)
        assertEquals(PinRecord.Guard(charged = false, row = 5, total = 5), record.guard)
        assertEquals(listOf(PinWrap.alias(slot.id)), f.keys.deleted)
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.LIMIT), f.engine.summary.value.pin)
    }

    @Test
    fun step5_aStartUpWriteThatFails_noPinInThisProcess_withTheStorageFullReason() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 1, total = 1, charged = true)
        f.plantState()
        f.files.failNextWrite()
        f.startInFront()

        assertEquals(MpReason.Pin(PinReason.STORAGE_FULL), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.STORAGE_FULL), f.engine.summary.value.pin)
        assertTrue(f.pinRecord().guard.charged) // the charge stays on disk and is counted at a later start
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right) // the master password works all the same (I10)
    }

    @Test
    fun step5_isForTheCurrentVaultOnly_aVaultPutAsideKeepsItsChargeUntilItsOwnScreenOpens() {
        f.seedVault()
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        f.plantPin(old, row = 0, total = 0, charged = true)
        f.startInFront()
        assertTrue(f.pinRecord(OTHER_VAULT).guard.charged)
        assertEquals(0, f.pinRecord(OTHER_VAULT).guard.row)
    }

    // ---- step 6: the credits -----------------------------------------------------------------------

    @Test
    fun step6_theCreditOfEveryPendingAndEveryReplacedIsObserved() {
        val vault = f.seedVault()
        f.plantPin(vault)
        val seen = f.clock.elapsedMs()
        f.plantState(VAULT, pendingMp = pending(vault.newestVkId, creditedMs = 1_000, seenElapsedMs = seen))
        val old = f.port.seedAside(OTHER_VAULT, bytes("the old password"))
        f.plantState(OTHER_VAULT, replaced = replaced(VAULT).copy(creditedMs = 5, seenElapsedMs = seen))
        check(old.vaultId == OTHER_VAULT)
        f.clock.advance(60_000)
        f.startInFront()

        assertEquals(61_000L, f.stateRecord(VAULT).pendingMp!!.creditedMs)
        assertEquals(seen + 60_000, f.stateRecord(VAULT).pendingMp!!.seenElapsedMs)
        assertEquals(60_005L, f.stateRecord(OTHER_VAULT).replaced!!.creditedMs)

        // ON_STOP and ON_START observe it too (§6.9).
        f.clock.advance(30_000)
        await { f.engine.appStopped() }
        assertEquals(91_000L, f.stateRecord(VAULT).pendingMp!!.creditedMs)
        f.clock.advance(30_000)
        await { f.engine.appStarted() }
        assertEquals(121_000L, f.stateRecord(VAULT).pendingMp!!.creditedMs)

        // A reboot credits only the new uptime when that is less than the last value seen.
        f.files.reboot()
        f.clock.reboot(offMs = 8 * 3_600_000L, uptimeMs = 5_000)
        f.restart()
        f.startInFront()
        assertEquals(126_000L, f.stateRecord(VAULT).pendingMp!!.creditedMs)
        assertEquals(5_000L, f.stateRecord(VAULT).pendingMp!!.seenElapsedMs)
    }
}
