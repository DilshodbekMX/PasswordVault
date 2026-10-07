package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of F1, first setup (lock.md §12): steps 1 to 5 and 9, the ways step 3 can fail, a
 * phone that cannot keep keys in secure hardware (F18), and the kill after step 3.
 */
class SetupFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    private fun create(first: String = PASSWORD, again: String = first, created: suspend () -> Unit = {}): CreateOutcome {
        val a = first.toCharArray()
        val b = again.toCharArray()
        return await { f.engine.createVault(a, b, created = created) }
    }

    @Test
    fun f1_fromWelcomeToAnOpenVaultWithItsFirstRecord() {
        f.startInFront()
        assertEquals(LockState.NoVault(emptyList()), f.state) // step 1: the vault directory is empty
        var stateWhenCreatedRan: LockState? = null

        val outcome = create(created = { stateWhenCreatedRan = f.state })

        // Step 3: a vault with generation 1 and one slot, proven with the second typing.
        val vaultId = (outcome as CreateOutcome.Created).vaultId
        val vault = f.port.current!!
        assertEquals(vaultId, vault.vaultId)
        assertEquals(listOf(1L), vault.live.map { it.number })
        assertTrue(vault.password(vault.newestVkId).contentEquals(bytes(PASSWORD)))
        assertEquals(listOf("createVault $vaultId"), f.port.writingCalls)
        // Step 4: lock/<vault_id>/ with state.rec: practice step 0, last_ok now.
        val record = f.stateRecord(vaultId)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), record.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), record.mpGuard)
        assertEquals(StateRecord.MaxGeneration(1, f.clock.wallMs()), record.maxGeneration)
        assertNull(record.mpChange)
        assertNull(record.pendingMp)
        assertNull(record.replaced)
        assertEquals(listOf("state.rec"), f.files.names(f.dirOf(vaultId)))
        // The defaults are written before the app counts as unlocked (§10.1).
        assertEquals(LockState.NoVault(emptyList()), stateWhenCreatedRan)
        // Step 5: this phone can hold a key, so step 6 offers the PIN with the proof of step 3.
        assertTrue(f.keys.canary)
        val unlocked = f.state as LockState.Unlocked
        val offer = unlocked.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
        val proof = unlocked.proof!!
        assertEquals(vaultId, proof.vaultId)
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        assertTrue(f.engine.core.holds(proof))
        // Step 9: the list, unlocked.
        assertEquals(vaultId, f.port.openVaultId)
        assertEquals(LockSummary.NONE, f.engine.summary.value)
        assertEquals(emptyList<String>(), f.keys.aliases) // no shortcut key yet
    }

    @Test
    fun f1_theNextLockShowsTheMasterPasswordScreenWithoutAReason() {
        f.startInFront()
        create()
        f.lock()
        assertNull(f.field.reason) // no PIN was ever set (§13.3, the last row)
        assertNull(f.field.practice)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        // After a master-password unlock without a PIN: "Choose a PIN", skippable (ux.md §4).
        assertEquals(ChoosePinReason.NONE_HERE, (f.notices.single() as Notice.ChoosePin).reason)
    }

    @Test
    fun f1_step2_bothTypingsAreComparedBeforeAnythingIsDerived() {
        f.startInFront()
        assertEquals(CreateOutcome.Mismatch, create(PASSWORD, "correct horse battery stable"))
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(LockState.NoVault(emptyList()), f.state)
        assertFalse(f.files.hasDir(LockFixture.ROOT))
    }

    @Test
    fun f1_step2_spellingsOfOnePasswordAreOneTyping() {
        f.startInFront()
        // U+02BB and U+2018 fold to the apostrophe, and a run of spaces to one (§8.1).
        val outcome = create("oʻgʻlim  Toshkentda", "o‘g‘lim Toshkentda")
        assertTrue(outcome is CreateOutcome.Created)
        val vault = f.port.current!!
        assertTrue(vault.password(vault.newestVkId).contentEquals(bytes("o'g'lim Toshkentda")))
    }

    @Test
    fun f1_step2_aCodePointUnicodeHasNotAssignedIsRefused() {
        f.startInFront()
        assertEquals(CreateOutcome.Unassigned(0xFFFF), create("abc￿def-ghi-jkl"))
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f1_step3_notEnoughMemory() {
        f.startInFront()
        f.port.noMemory = true
        assertEquals(CreateOutcome.NoMemory, create())
        assertEquals(emptyList<String>(), f.port.writes)
        assertEquals(LockState.NoVault(emptyList()), f.state)
    }

    @Test
    fun f1_step3_couldntSave_nothingElseExistsYet() {
        f.startInFront()
        f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
        assertEquals(CreateOutcome.NotSaved, create())
        assertNull(f.port.current)
        assertFalse(f.files.hasDir(LockFixture.ROOT))
        assertFalse(f.keys.canary)
        assertEquals(LockState.NoVault(emptyList()), f.state)

        f.port.failWrites(FakeVaultPort.WriteFault.QUARANTINED) // written, and it did not prove from disk
        assertEquals(CreateOutcome.NotSaved, create())
        assertNull(f.port.current)
        assertEquals(LockState.NoVault(emptyList()), f.state)

        assertTrue(create() is CreateOutcome.Created) // "Try again"
    }

    @Test
    fun f1_step3_inPlaceButPerhapsNotDurable_theVaultIsThereAndLocked() {
        f.startInFront()
        f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)
        assertEquals(CreateOutcome.NotSaved, create())
        assertEquals(MpReason.WelcomeBack, f.field.reason) // a vault, and no lock records
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f1_setupStartsOnWelcomeOnly() {
        f.seedVault()
        f.startInFront()
        assertEquals(CreateOutcome.NotSaved, create())
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f1_step4_aStateRecordThatCannotBeWritten_theVaultOpensAndTheNextStartIsF14() {
        f.startInFront()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1)
        assertTrue(create() is CreateOutcome.Created)
        assertTrue(f.state is LockState.Unlocked)
        f.files.clearFaults()
        f.restart()
        f.startInFront()
        assertEquals(MpReason.WelcomeBack, f.field.reason)
    }

    @Test
    fun f1_killedAfterStep3_theNextStartAsksForTheMasterPasswordThenStep5() {
        f.startInFront()
        f.files.stopAt(1) // the process dies as it begins to write state.rec
        try {
            create()
            fail("the process should have died")
        } catch (e: FakeRecordFiles.Stopped) {
            // dead
        }
        f.restart()
        f.startInFront()

        assertEquals(MpReason.WelcomeBack, f.field.reason) // a vault without pin.rec: the master-password screen
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(ChoosePinReason.NONE_HERE, (f.notices.single() as Notice.ChoosePin).reason)
        assertTrue(f.stateOnDisk(f.port.current!!.vaultId) is RecordRead.Ok)
    }

    // ---- F18: a phone that cannot keep keys in secure hardware -----------------------------------

    @Test
    fun f18_noSecureHardware_masterPasswordOnly_andItsTextTheFirstTimeOnly() {
        f.keys.softwareOnly = true
        f.startInFront()
        assertTrue(create() is CreateOutcome.Created)

        assertEquals(emptyList<Notice>(), f.notices) // no PIN and no fingerprint is offered (F1 step 5, then step 9)
        assertFalse(f.keys.canary)
        assertEquals(LockSummary.Pin.NoSecureHardware, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.NoSecureHardware, f.engine.summary.value.fingerprint)

        f.lock()
        assertEquals(MpReason.NoSecureHardware, f.field.reason)
        assertNull(f.field.practice) // every unlock is the master password; practice is not asked
        await { f.engine.noSecureHardwareShown() }
        assertNull(f.field.reason) // the first time only
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)

        // A later process learns it at the first master-password unlock, and is told that the text was shown.
        f.restart()
        await { f.engine.start(noSecureHardwareTold = true) }
        await { f.engine.appStarted() }
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<Notice>(), f.notices)
        assertEquals(LockSummary.Pin.NoSecureHardware, f.engine.summary.value.pin)
        f.lock()
        assertNull(f.field.reason)
    }

    @Test
    fun f1_step5_aKeystoreThatDoesNotAnswerIsNotF18() {
        f.keys.answering = false
        f.startInFront()
        assertTrue(create() is CreateOutcome.Created)
        assertEquals(emptyList<Notice>(), f.notices) // nothing can be set up right now
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin) // and nothing says "this phone can't"
        f.keys.answering = true
        f.lock()
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(ChoosePinReason.NONE_HERE, (f.notices.single() as Notice.ChoosePin).reason)
    }
}
