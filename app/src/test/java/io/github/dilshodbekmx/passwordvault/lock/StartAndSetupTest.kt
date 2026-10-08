package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: [DefaultLockManager] over the lock engine with fake ports, at process
 * start and through setup: the top-level states of lock.md §13.1 rows 1 and 2, a vault without lock
 * records (F14), `createVault` (F1 steps 2 to 6, F13, F17 step 3) with the defaults of §10.1, and the
 * lock's status.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartAndSetupTest {

    // ---- process start (lock.md §5.11, §13.1) ----------------------------------------------------

    @Test
    fun nothingIsShownUntilTheRecordsAndTheVaultsAreRead_thenWelcome() = runTest {
        val rig = LockRig(this)
        val manager = rig.start()
        assertEquals("before process start has run, no screen is decided", LockUiState.Starting, manager.state.value)
        assertEquals(LockStatus.NONE, manager.status.value)
        rig.settle()
        assertEquals("an empty phone: Welcome (row 1)", LockUiState.NoVault(emptyList()), manager.state.value)
        assertEquals(LockStatus.NONE, manager.status.value)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    @Test
    fun welcomeListsTheVaultsPutAside_andSoDoesTheStatus() = runTest {
        val rig = LockRig(this)
        rig.port.seedAside(OLD_VAULT, LockRig.PASSWORD.toByteArray(), putAsideAtMs = 1_790_000_000_000L)
        rig.startInFront()
        val welcome = rig.state as LockUiState.NoVault
        assertEquals(listOf(OLD_VAULT), welcome.aside.map { it.vaultId })
        assertEquals(1_790_000_000_000L, welcome.aside.single().putAsideAtMs)
        assertEquals(listOf(OLD_VAULT), rig.status.aside.map { it.vaultId })
    }

    @Test
    fun vaultsThatCannotBeListedAreNeverNoVault_andTryAgainListsThemAgain() = runTest {
        val rig = LockRig(this)
        rig.port.scanFails = true
        rig.startInFront()
        assertEquals("a storage error, never Welcome (F1 step 1)", LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()), rig.state)

        rig.send(LockIntent.UseMasterPassword) // "Try again" on "Your passwords can't be opened"
        assertEquals("still unreadable", LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()), rig.state)

        rig.port.scanFails = false
        rig.send(LockIntent.UseMasterPassword)
        assertEquals(LockUiState.NoVault(emptyList()), rig.state)
    }

    @Test
    fun tryAgainThroughTheMasterPasswordCallListsTheVaultsToo_andChecksNothing() = runTest {
        val rig = LockRig(this)
        rig.port.seed(VAULT, LockRig.PASSWORD.toByteArray())
        rig.port.scanFails = true
        rig.startInFront()
        assertTrue(rig.state is LockUiState.Unreadable)

        rig.port.scanFails = false
        assertEquals(MpResult.TooLate, rig.unlockWith(LockRig.PASSWORD))
        assertTrue("the vault is listed again, and its lock screen shown", rig.state is LockUiState.Locked)
        assertFalse("nothing was checked", rig.port.calls.any { it.startsWith("openWithPassword") })
    }

    @Test
    fun aVaultWhoseNewestCopyWasMadeByANewerApp_cannotBeOpened_withItsReason() = runTest {
        val rig = LockRig(this)
        rig.port.seed(VAULT, LockRig.PASSWORD.toByteArray()).generation(1).state = FakeVaultPort.FileState.NEWER
        rig.startInFront()
        assertEquals(LockUiState.Unreadable(CurrentVault.Why.NEWER, emptyList()), rig.state)
    }

    // ---- a vault without lock records: "Welcome back" (lock.md F14) --------------------------------

    @Test
    fun aVaultWithoutALockDirectory_asksForTheMasterPasswordAsWelcomeBack_thenOffersAPin() = runTest {
        val rig = LockRig(this)
        rig.port.seed(VAULT, LockRig.PASSWORD.toByteArray())
        rig.startInFront()
        assertFalse("no lock directory yet", rig.files.hasDir(rig.dirOf(VAULT)))
        val field = rig.field
        assertEquals("the title is \"Welcome back\"", MpReason.WelcomeBack, field.reason)
        assertFalse(field.canUsePin)
        assertNull("practice is not asked where no shortcut exists", field.practice)

        val result = rig.unlockWith(LockRig.PASSWORD)
        assertTrue(result is MpResult.Right)
        val unlocked = rig.unlocked
        assertSame("the proof of this unlock is the one handed on (lock.md §8.2)", (result as MpResult.Right).proof, unlocked.proof)
        val offer = unlocked.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse("the master password was just typed: no second typing", offer.needsMasterPassword)
        assertTrue("F14 step 2: the directory and state.rec are made by this unlock", rig.stateOnDisk(VAULT) is RecordRead.Ok)

        // F6 with that proof: the PIN is set, and the next lock screen is the pad.
        assertTrue(rig.manager.setPin(LockRig.digits(LockRig.PIN), unlocked.proof!!))
        rig.settle()
        assertEquals(PinStatus.On, rig.status.pin)
        rig.lock()
        assertTrue(rig.screen is LockScreen.Pad)
    }

    // ---- createVault (lock.md F1 steps 2 to 6, §10.1) ----------------------------------------------

    @Test
    fun createVault_comparesTheTwoTypingsBeforeAnythingIsDerived() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        val result = rig.manager.createVault("correct horse".toCharArray(), "correct house".toCharArray(), null)
        assertEquals(CreateResult.Mismatch, result)
        assertEquals("the vault port was not asked to write", emptyList<String>(), rig.port.writingCalls)
        assertEquals(0, rig.kdf.derivations)
        assertTrue(rig.state is LockUiState.NoVault)
    }

    @Test
    fun createVault_comparesTheTypingsAsMasterPasswordBytes() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        // U+02BB and U+2019 fold to the same apostrophe (lock.md §8.1): the same password on two keyboards.
        val result = rig.manager.createVault("oʻzbek tili 2026".toCharArray(), "o’zbek tili 2026".toCharArray(), null)
        assertTrue(result is CreateResult.Created)
    }

    @Test
    fun createVault_refusesAnUnassignedCodePoint() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        val typed = "pass͸word 2026".toCharArray()
        assertEquals(CreateResult.Unassigned(0x378), rig.manager.createVault(typed, typed.copyOf(), null))
        assertEquals(emptyList<String>(), rig.port.writingCalls)
    }

    @Test
    fun createVault_writesTheDefaultsOnceTheVaultIsProven_thenUnlocksWithAChoosePinOffer() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        assertEquals("a missing setting is the strictest", LockDelay.IMMEDIATELY, rig.settings.lockDelay.value)

        val result = rig.manager.createVault(LockRig.PASSWORD.toCharArray(), LockRig.PASSWORD.toCharArray(), null)
        rig.settle()
        val created = result as CreateResult.Created
        assertEquals(rig.port.current!!.vaultId, created.vaultId)
        assertEquals(
            "the defaults are written while setup is still shown, before the app counts as unlocked (lock.md §10.1)",
            listOf("writeDefaults while NoVault"),
            rig.log,
        )
        assertEquals(LockDelay.SECONDS_30, rig.settings.lockDelay.value)
        assertEquals(ClipDelay.MINUTE_1, rig.settings.clipDelay.value)

        val unlocked = rig.unlocked
        val offer = unlocked.notices.single() as Notice.ChoosePin
        assertEquals("F1 step 6: \"Choose a PIN\" follows setup", ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
        val proof = assertNotNullProof(unlocked)
        assertEquals(created.vaultId, proof.vaultId)
        assertEquals("the proof lends the bytes of the second typing", LockRig.PASSWORD, proof.useBytes { String(it, Charsets.UTF_8) })
        assertTrue("F1 step 4: state.rec exists", rig.stateOnDisk() is RecordRead.Ok)
        assertEquals("practice starts at step 0", 0, rig.stateRecord().practice.step)
        assertEquals(PinStatus.NotSet, rig.status.pin)
        assertEquals(FingerprintStatus.Off, rig.status.fingerprint)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    @Test
    fun createVault_withoutMemoryWritesNothing() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        rig.port.noMemory = true
        val result = rig.manager.createVault(LockRig.PASSWORD.toCharArray(), LockRig.PASSWORD.toCharArray(), null)
        assertEquals(CreateResult.NoMemory, result)
        assertNull(rig.port.current)
        assertEquals("no defaults for a vault that does not exist", emptyList<String>(), rig.log)
        assertTrue(rig.state is LockUiState.NoVault)
    }

    @Test
    fun createVault_thatIsNotSavedLeavesSetupAndTheSettingsAsTheyWere() = runTest {
        val rig = LockRig(this)
        rig.startInFront()
        rig.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
        val result = rig.manager.createVault(LockRig.PASSWORD.toCharArray(), LockRig.PASSWORD.toCharArray(), null)
        assertEquals(CreateResult.NotSaved, result)
        assertEquals(emptyList<String>(), rig.log)
        assertEquals(LockDelay.IMMEDIATELY, rig.settings.lockDelay.value)
        assertTrue(rig.state is LockUiState.NoVault)
    }

    @Test
    fun createVault_onlyWhereSetupIs_neverOverALockedOrOpenVault() = runTest {
        val rig = LockRig(this)
        rig.setUp()
        val again = rig.manager.createVault(LockRig.OTHER_PASSWORD.toCharArray(), LockRig.OTHER_PASSWORD.toCharArray(), null)
        assertEquals("the app is open", CreateResult.NotSaved, again)
        rig.lock()
        val locked = rig.manager.createVault(LockRig.OTHER_PASSWORD.toCharArray(), LockRig.OTHER_PASSWORD.toCharArray(), null)
        assertEquals("a lock screen is shown", CreateResult.NotSaved, locked)
        assertEquals("one vault was made", 1, rig.port.writingCalls.count { it.startsWith("createVault") })
    }

    @Test
    fun aRestoreFromTheLockScreen_putsTheVaultAsideWithItsPin_andOpensTheRestoredOne() = runTest {
        // lock.md F17 step 3: "Restore from a backup file" on a phone that has a vault.
        val rig = LockRig(this)
        rig.setUpWithPin()
        val oldId = rig.vaultId
        rig.lock()
        assertTrue(rig.screen is LockScreen.Pad)

        val restored = Vault(entries = listOf(Entry(id = ENTRY, title = "mail", createdAt = 1L, updatedAt = 1L)))
        val result = rig.manager.createVault(LockRig.OTHER_PASSWORD.toCharArray(), LockRig.OTHER_PASSWORD.toCharArray(), restored)
        rig.settle()
        val created = result as CreateResult.Created
        assertTrue(created.vaultId != oldId)
        assertEquals("the new vault is the current one", created.vaultId, rig.port.current!!.vaultId)
        assertEquals("the old one is put aside, never deleted", listOf(oldId), rig.port.asideVaults.map { it.vaultId })
        assertNotNull("with its PIN record where it was (lock.md F17, decision 21)", rig.pinRecord(oldId).slot)
        assertEquals("a restored vault starts practice at step 3 (lock.md §8.3)", 3, rig.stateRecord(created.vaultId).practice.step)
        assertEquals(listOf(oldId), rig.status.aside.map { it.vaultId })
        assertTrue(rig.notices.single() is Notice.ChoosePin)

        // Its own lock screen, under its line, with its own pad (F17 step 4).
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.OpenAside(oldId))
        val pad = rig.pad
        assertTrue(pad.aside is AsideLine.LockedOn)
    }

    private fun assertNotNullProof(unlocked: LockUiState.Unlocked) = checkNotNull(unlocked.proof) { "the unlock left no proof" }

    private companion object {
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val OLD_VAULT = "00000000-0000-4000-8000-0000000000a1"
        const val ENTRY = "00000000-0000-4000-8000-0000000000e1"
    }
}
