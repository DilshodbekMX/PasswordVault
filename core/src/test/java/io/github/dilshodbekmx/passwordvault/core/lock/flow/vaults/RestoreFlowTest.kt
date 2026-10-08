package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F13**, restore on a new phone, and of **F17 step 3**, "Restore from a
 * backup file" on a phone that has a vault (build plan 3.14): the backup's master password becomes
 * the slot of a new vault with a new `vault_id`, proven from disk; its `state.rec` starts at practice
 * step 3; F1 step 5 follows; a vault on the phone is put aside with its lock directory and its keys,
 * never replaced and never deleted; and the backup's password never waits (§6.8).
 */
class RestoreFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    private val day = PracticeSchedule.DAY_MS

    @Test
    fun f13_aBackupRestoredOnANewPhone_isANewVaultAtPracticeStep3_thenF1Step5() {
        f.startInFront()
        assertEquals(LockState.NoVault(emptyList()), f.state)
        var stateWhenCreatedRan: LockState? = null

        val outcome = f.restoreBackup(created = { stateWhenCreatedRan = f.state })

        // Step 4: a new vault with a new vault_id, VK and slot from the password just typed, proven
        // from disk; the vault on the phone, if any, would have gone aside in the same step.
        val id = (outcome as CreateOutcome.Created).vaultId
        assertTrue(Uuid.isValid(id))
        assertEquals(listOf("createVault $id putCurrentAside"), f.port.writingCalls)
        val vault = f.port.current!!
        assertEquals(id, vault.vaultId)
        assertEquals(BACKUP_VAULT, vault.content)
        assertEquals(listOf(1L), vault.live.map { it.number })
        assertArrayEquals(bytes(Secrets.BACKUP), vault.password(vault.newestVkId))
        // Step 5: a new lock directory, with state.rec at practice step 3 and nothing else in it.
        val record = f.stateRecord(id)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 3, null), record.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), record.mpGuard)
        assertEquals(StateRecord.MaxGeneration(1, f.clock.wallMs()), record.maxGeneration)
        assertNull(record.mpChange)
        assertNull(record.pendingMp)
        assertNull(record.replaced)
        assertEquals(listOf("state.rec"), f.files.names(f.dirOf(id)))
        // The app's own defaults are written before the app counts as unlocked (§10.1).
        assertEquals(LockState.NoVault(emptyList()), stateWhenCreatedRan)
        // Step 6, F1 step 5: this phone can hold a key, so "Choose a PIN" follows, with the proof of
        // the password just typed.
        assertTrue(f.keys.canary)
        val unlocked = f.state as LockState.Unlocked
        val offer = unlocked.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
        val proof = unlocked.proof!!
        assertEquals(id, proof.vaultId)
        assertTrue(proof.useBytes { it.contentEquals(bytes(Secrets.BACKUP)) })
        assertTrue(f.engine.core.holds(proof))
        assertEquals(id, f.port.openVaultId)
        assertEquals(LockSummary.NONE, f.engine.summary.value)
        assertEquals(emptyList<String>(), f.keys.aliases)

        // The restored vault keeps the backup's master password.
        f.lock()
        assertNull(f.field.reason) // no PIN was set yet: no reason (§13.3, the last row)
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith(PASSWORD))
        assertTrue(f.unlockWith(Secrets.BACKUP) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f13_step5_practiceStep3_asksAfter14Days_becauseTheUserHasJustProvedThePassword() {
        f.startInFront()
        val id = (f.restoreBackup() as CreateOutcome.Created).vaultId
        // A PIN as F6 would set it (package 3.13), so that practice can be asked at all (§8.3).
        f.plantPin(f.port.current!!)
        f.restart()
        f.clock.advance(13 * day)
        f.startInFront()
        assertTrue("13 days after a restore: the pad", f.screen is LockScreen.Pad)
        f.clock.advance(day + 1)
        f.leaveAndReturn()
        val field = f.field
        assertEquals(MpReason.PracticeDue, field.reason)
        assertNotNull(field.practice)
        assertEquals(3, f.stateRecord(id).practice.step)
    }

    @Test
    fun f13_bothTypingsAreComparedBeforeAnythingIsDerived() {
        f.startInFront()
        val first = Secrets.BACKUP.toCharArray()
        val again = "the words of an old backuq".toCharArray()
        assertEquals(CreateOutcome.Mismatch, await { f.engine.createVault(first, again, restoreFrom = BACKUP_VAULT) })
        assertEquals(emptyList<String>(), f.port.calls.filter { it.startsWith("createVault") })
        assertEquals(0, f.kdf.derivations)
        assertEquals(LockState.NoVault(emptyList()), f.state)
        assertFalse(f.files.hasDir(LockFixture.ROOT))
    }

    @Test
    fun f13_aPasswordWithACodePointUnicodeHasNotAssigned_isRestoredAllTheSame() {
        // Creation refuses such a password (§8.1, F1 step 2). This one has already opened the backup,
        // and refusing it would leave the backup unrestorable on this phone (requirement 1).
        val password = "abc￿def-ghi-jkl"
        f.startInFront()
        val outcome = f.restoreBackup(password)
        assertTrue(outcome is CreateOutcome.Created)
        val vault = f.port.current!!
        assertArrayEquals(MasterPasswordBytes.of(password.toCharArray()), vault.password(vault.newestVkId))
    }

    @Test
    fun f13_notEnoughMemory_nothingIsWritten() {
        f.startInFront()
        f.port.noMemory = true
        assertEquals(CreateOutcome.NoMemory, f.restoreBackup())
        assertEquals(emptyList<String>(), f.port.writes)
        assertEquals(LockState.NoVault(emptyList()), f.state)
        assertFalse(f.files.hasDir(LockFixture.ROOT))
    }

    @Test
    fun f13_couldNotSave_nothingChanged_andTryingAgainWorks() {
        f.startInFront()
        f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
        assertEquals(CreateOutcome.NotSaved, f.restoreBackup())
        assertNull(f.port.current)
        assertFalse(f.files.hasDir(LockFixture.ROOT))
        assertEquals(LockState.NoVault(emptyList()), f.state)

        f.port.failWrites(FakeVaultPort.WriteFault.QUARANTINED) // written, and it did not prove from disk
        assertEquals(CreateOutcome.NotSaved, f.restoreBackup())
        assertNull(f.port.current)
        assertEquals(LockState.NoVault(emptyList()), f.state)

        assertTrue(f.restoreBackup() is CreateOutcome.Created)
    }

    @Test
    fun f13_inPlaceButPerhapsNotDurable_theRestoredVaultIsThere_andItsPasswordOpensIt() {
        f.startInFront()
        f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)
        assertEquals(CreateOutcome.NotSaved, f.restoreBackup())
        assertEquals(MpReason.WelcomeBack, f.field.reason) // a vault, and no lock records (F14)
        assertTrue(f.unlockWith(Secrets.BACKUP) is MasterPasswordOutcome.Right)
    }

    @Test
    fun restore_isRefusedBeforeStartUp_andInTheOpenApp() {
        f.seedVault()
        f.plantState()
        assertEquals(LockState.Starting, f.state)
        assertEquals(CreateOutcome.NotSaved, f.restoreBackup())
        assertEquals(emptyList<String>(), f.port.calls)

        f.startInFront()
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(CreateOutcome.NotSaved, f.restoreBackup()) // Settings imports a backup into the vault (ux.md §5.21); it never restores over it
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.openVaultId)
    }

    @Test
    fun f17step3_aRestoreFromTheLockScreen_putsTheVaultAsideWithItsLockStateAndItsKeys() {
        val vault = f.seedVault(generations = 2)
        val pin = f.plantPin(vault, row = 1, total = 2)
        val finger = f.plantFingerprint(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.useMasterPassword() } // "Forgot PIN? Use master password"; the sheet is on that screen
        val records = listOf("pin.rec", "fingerprint.rec", "state.rec").associateWith { f.files.bytes(f.dirOf(VAULT), it)!!.toList() }
        val generations = filesOf(f, VAULT)

        val id = (f.restoreBackup() as CreateOutcome.Created).vaultId

        // Only now, with the backup written and proven, does the vault go aside, in the same step.
        assertEquals(listOf("createVault $id putCurrentAside"), f.port.writingCalls)
        assertEquals(id, f.port.current!!.vaultId)
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(generations, filesOf(f, VAULT)) // archived, never deleted
        // The lock does nothing to it (F17): its records are as they were, byte for byte, and its keys stay.
        for ((name, bytes) in records) assertEquals(name, bytes, f.files.bytes(f.dirOf(VAULT), name)!!.toList())
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertTrue(PinWrap.alias(pin.id) in f.keys.aliases)
        assertTrue(RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id in f.keys.aliases)
        // F13 steps 5 and 6 follow for the restored vault.
        assertEquals(3, f.stateRecord(id).practice.step)
        assertEquals(ChoosePinReason.NONE_HERE, (f.notices.single() as Notice.ChoosePin).reason)
        assertEquals(listOf(VAULT), f.engine.summary.value.aside.map { it.vaultId })

        // Its own PIN still opens it, from its own lock screen, and it becomes current again (F17 step 4).
        f.answerNotices()
        f.lock()
        await { f.engine.showAside(VAULT) }
        val putAsideAt = f.port.vault(VAULT)!!.putAsideAtMs!!
        assertEquals(AsideLine.LockedOn(putAsideAt), f.pad.aside)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(listOf(id), f.port.asideVaults.map { it.vaultId })
        assertEquals(listOf("createVault $id putCurrentAside", "swapIn $VAULT"), f.port.writingCalls)
    }

    @Test
    fun f17step3_fromYourPasswordsCantBeOpened_theDamagedVaultIsKept() {
        val vault = f.seedVault(generations = 2)
        f.plantState()
        vault.live.forEach { it.state = FakeVaultPort.FileState.DAMAGED }
        f.startInFront()
        assertEquals(LockState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()), f.state)
        val generations = filesOf(f, VAULT)

        val id = (f.restoreBackup() as CreateOutcome.Created).vaultId

        assertEquals(id, f.port.current!!.vaultId)
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(generations, filesOf(f, VAULT)) // its damaged files are kept (ux.md §5.9)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f13_theBackupsPasswordNeverWaits_whileTheFieldOfTheVaultOnThePhoneDoes() {
        f.seedVault()
        val guard = StateRecord.MpGuard(failures = 7, lastFailElapsedMs = f.clock.elapsedMs() - 1_000, unseen = 7)
        f.plantState(mpGuard = guard)
        f.startInFront()
        assertNotNull(f.field.waitUntilElapsedMs) // §6.8: that vault's own field waits
        val waitsBefore = f.clock.waits.size

        val id = (f.restoreBackup() as CreateOutcome.Created).vaultId

        assertEquals(waitsBefore, f.clock.waits.size) // a file from outside is never guarded (F13 step 2)
        assertEquals(id, f.port.current!!.vaultId)
        assertEquals(guard, f.stateRecord(VAULT).mpGuard) // untouched, aside
    }

    @Test
    fun f13_onWelcome_theVaultsPutAsideStayAsTheyAre() {
        f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = 1_790_000_000_000L)
        f.startInFront()
        assertEquals(listOf(OTHER_VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId })
        val generations = filesOf(f, OTHER_VAULT)

        val id = (f.restoreBackup() as CreateOutcome.Created).vaultId

        assertEquals(id, f.port.current!!.vaultId)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(1_790_000_000_000L, f.port.vault(OTHER_VAULT)!!.putAsideAtMs)
        assertEquals(generations, filesOf(f, OTHER_VAULT))
        assertEquals(listOf(OTHER_VAULT), f.engine.summary.value.aside.map { it.vaultId })
    }
}
