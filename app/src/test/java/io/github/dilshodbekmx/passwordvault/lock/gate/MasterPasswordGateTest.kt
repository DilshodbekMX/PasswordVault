package io.github.dilshodbekmx.passwordvault.lock.gate

import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MasterPasswordGuard
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.2, [JVM]: the master-password gate (`MasterPasswordGate`) over an in-memory vault
 * port, with the process lifecycle and the clocks faked: the three states it knows, `createVault`
 * (both typings compared before anything is derived, then `AppSettings.writeDefaults`), unlocking
 * under the guard of lock.md §6.8, visits (I11), `lock(reason)`, and the flows it leaves to later
 * packages. The session's tests (T19) are in `APP-T/session/`.
 *
 * Since build plan 4.1 the gate is test code, the stand-in lock of the session's tests, and the app's
 * lock is `DefaultLockManager` (`APP-T/lock/`). These tests stay with the gate as long as it is there:
 * they keep the stand-in honest, and the skeleton's check counts them among 3.2's
 * (`tools/emu/checks/3A.sh`, `package_tests`). They go with the gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterPasswordGateTest {
    private val password = "correct horse battery staple".toCharArray()
    private fun bytesOf(chars: CharArray) = MasterPasswordBytes.of(chars)

    private fun LockUiState.field(): LockScreen.MasterPassword = ((this as LockUiState.Locked).screen as LockScreen.MasterPassword)

    private val fresh = LockScreen.MasterPassword(
        reason = null,
        canUsePin = false,
        practice = null,
        waitUntilElapsedMs = null,
        error = null,
        pendingReadyInMs = null,
        opening = false,
        aside = null,
    )

    // ---- start ---------------------------------------------------------------------------------

    @Test
    fun startingUntilTheVaultsAreRead_thenWelcomeWithTheVaultsPutAside() = runTest {
        val rig = GateRig(this)
        val aside = listOf(AsideVault(GateRig.OTHER_ID, 1_790_000_000_000L, null))
        rig.vault.aside = aside
        val gate = rig.start()
        assertEquals("nothing is shown before the vault directories are read (lock.md §5.11)", LockUiState.Starting, gate.state.value)
        rig.settle()
        assertEquals(LockUiState.NoVault(aside), gate.state.value)
        assertEquals(LockStatus.NONE.copy(aside = aside), gate.status.value)
        assertEquals(PinStatus.NotSet, gate.status.value.pin)
        assertEquals(FingerprintStatus.Off, gate.status.value.fingerprint)
        assertEquals(listOf("vaults"), rig.vault.log)
    }

    @Test
    fun aVaultOnThePhoneShowsTheMasterPasswordScreenWithNoReason() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        val gate = rig.start()
        rig.settle()
        assertEquals(LockUiState.Locked(fresh), gate.state.value)
    }

    @Test
    fun vaultsThatCannotBeListedAreUnreadableNeverNoVault_andTryAgainReadsThemAgain() = runTest {
        val rig = GateRig(this)
        rig.vault.scanFails = true
        val gate = rig.start()
        rig.settle()
        assertEquals(LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()), gate.state.value)

        // "Try again" (lock.md §13.1 row 2): still failing, then readable.
        gate.send(LockIntent.UseMasterPassword)
        rig.settle()
        assertEquals(LockUiState.Unreadable(CurrentVault.Why.IO, emptyList()), gate.state.value)
        rig.vault.scanFails = false
        rig.vaultOnThePhone(password)
        gate.send(LockIntent.UseMasterPassword)
        rig.settle()
        assertEquals(LockUiState.Locked(fresh), gate.state.value)
    }

    @Test
    fun aVaultWhoseGenerationsCannotBeReadIsUnreadableWithItsReason() = runTest {
        for (why in CurrentVault.Why.entries) {
            val rig = GateRig(this)
            rig.vault.current = CurrentVault.Unreadable(why)
            val gate = rig.start()
            rig.settle()
            assertEquals(LockUiState.Unreadable(why, emptyList()), gate.state.value)
            assertEquals(MpResult.TooLate, gate.submitMasterPassword(password, MpPurpose.UNLOCK))
            assertFalse("nothing is typed into a vault that cannot be read", "openWithPassword" in rig.vault.log)
        }
    }

    // ---- createVault ---------------------------------------------------------------------------

    @Test
    fun createVault_theTwoTypingsAreComparedBeforeAnythingIsDerived() = runTest {
        val rig = GateRig(this)
        val gate = rig.start()
        rig.settle()
        assertEquals(CreateResult.Mismatch, gate.createVault("one password".toCharArray(), "another one".toCharArray(), null))
        assertEquals("the vault port, which derives, was never called", listOf("vaults"), rig.vault.log)
        assertEquals(LockUiState.NoVault(emptyList()), gate.state.value)
        assertEquals(LockDelay.WHEN_MISSING, rig.settings.lockDelay.value)
    }

    @Test
    fun createVault_comparesTheTypingsAsMasterPasswordBytes() = runTest {
        // Two spellings of one password (lock.md §8.1: white space folded, apostrophes folded, NFC).
        val rig = GateRig(this)
        val gate = rig.start()
        rig.settle()
        val first = "  O‘zbekiston   parol  ".toCharArray()
        val again = "O'zbekiston parol".toCharArray()
        val result = gate.createVault(first, again, null)
        assertTrue(result is CreateResult.Created)
        val (a, b) = rig.vault.created.single()
        assertArrayEquals(bytesOf(again), a)
        assertArrayEquals("the proof is made with the bytes of the second typing", bytesOf(again), b)
        assertEquals("the caller's arrays are left as they were", "  O‘zbekiston   parol  ", String(first))
    }

    @Test
    fun createVault_refusesAnUnassignedCodePointBeforeAnythingIsDerived() = runTest {
        val rig = GateRig(this)
        val gate = rig.start()
        rig.settle()
        val withUnassigned = "abc͸def".toCharArray() // U+0378 is not assigned
        assertEquals(CreateResult.Unassigned(0x378), gate.createVault(withUnassigned, withUnassigned.copyOf(), null))
        assertEquals(listOf("vaults"), rig.vault.log)
    }

    @Test
    fun createVault_writesTheDefaultsOnceTheVaultIsProven_thenUnlocks() = runTest {
        val rig = GateRig(this)
        rig.random.queueUuids(GateRig.VAULT_ID)
        val gate = rig.start()
        rig.settle()
        assertEquals(LockDelay.WHEN_MISSING, rig.settings.lockDelay.value)
        assertEquals(ClipDelay.WHEN_MISSING, rig.settings.clipDelay.value)

        assertEquals(CreateResult.Created(GateRig.VAULT_ID), gate.createVault(password, password.copyOf(), null))

        assertEquals(GateRig.VAULT_ID, rig.vault.createdId)
        assertEquals(
            "the vault is made and proven, then the defaults are written, while setup is still shown",
            listOf("vaults", "createVault", "writeDefaults while NoVault"),
            rig.vault.log,
        )
        assertEquals("lock.md §10.1: setup writes 30 seconds", LockDelay.SETUP, rig.settings.lockDelay.value)
        assertEquals(LockDelay.SECONDS_30, rig.settings.lockDelay.value)
        assertEquals(ClipDelay.MINUTE_1, rig.settings.clipDelay.value)
        assertEquals(LockUiState.Unlocked(emptyList(), null), gate.state.value)
        assertTrue(rig.vault.isOpen)
        val (a, b) = rig.vault.created.single()
        assertArrayEquals(bytesOf(password), a)
        assertArrayEquals(bytesOf(password), b)
    }

    @Test
    fun createVault_restoresFromAVaultItIsGiven() = runTest {
        val rig = GateRig(this)
        val gate = rig.start()
        rig.settle()
        val restored = Vault(
            entries = listOf(Entry(id = "00000000-0000-4000-8000-000000000011", title = "Gmail", createdAt = 1L, updatedAt = 1L)),
        )
        assertTrue(gate.createVault(password, password.copyOf(), restored) is CreateResult.Created)
        assertEquals(restored, rig.vault.vault)
    }

    @Test
    fun createVault_withoutMemoryWritesNothing() = runTest {
        val rig = GateRig(this)
        rig.vault.createFails = KdfUnavailable(null)
        val gate = rig.start()
        rig.settle()
        assertEquals(CreateResult.NoMemory, gate.createVault(password, password.copyOf(), null))
        assertEquals(LockDelay.WHEN_MISSING, rig.settings.lockDelay.value)
        assertEquals(LockUiState.NoVault(emptyList()), gate.state.value)
    }

    @Test
    fun createVault_thatIsNotSavedLeavesSetupAndTheSettingsAsTheyWere() = runTest {
        for (answer in listOf(VaultWrite.NothingChanged, VaultWrite.Quarantined, VaultWrite.Unclear)) {
            val rig = GateRig(this)
            rig.vault.createAnswer = answer
            val gate = rig.start()
            rig.settle()
            assertEquals(CreateResult.NotSaved, gate.createVault(password, password.copyOf(), null))
            assertEquals(LockDelay.WHEN_MISSING, rig.settings.lockDelay.value)
            assertEquals(LockUiState.NoVault(emptyList()), gate.state.value)
            assertFalse(rig.vault.log.any { it.startsWith("writeDefaults") })
        }
    }

    @Test
    fun createVault_onlyWhereThereIsNoVault() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        val gate = rig.start()
        rig.settle()
        assertEquals(CreateResult.NotSaved, gate.createVault(password, password.copyOf(), null))
        assertFalse("createVault" in rig.vault.log)
    }

    // ---- unlocking ------------------------------------------------------------------------------

    @Test
    fun theRightMasterPasswordUnlocks_withAProofOfItsBytes() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()

        val result = gate.submitMasterPassword(password, MpPurpose.UNLOCK)

        assertTrue(result is MpResult.Right)
        val proof = (result as MpResult.Right).proof
        assertEquals(GateRig.VAULT_ID, proof.vaultId)
        assertArrayEquals(bytesOf(password), proof.useBytes { it.copyOf() })
        assertEquals(LockUiState.Unlocked(emptyList(), null), gate.state.value)
        assertArrayEquals("the port gets MasterPasswordBytes of what was typed", bytesOf(password), rig.vault.typed.single())
        assertTrue(rig.vault.isOpen)
        assertEquals("the caller's array is not changed", "correct horse battery staple", String(password))
    }

    @Test
    fun aWrongMasterPasswordShowsItsLine() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()

        assertEquals(MpResult.Wrong, gate.submitMasterPassword("wrong".toCharArray(), MpPurpose.UNLOCK))

        assertEquals(fresh.copy(error = MpError.Wrong(offerForgot = false, replaced = null)), gate.state.value.field())
        assertFalse(rig.vault.isOpen)
    }

    @Test
    fun openingIsShownWhileArgon2idRuns() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val hold = CompletableDeferred<Unit>().also { rig.vault.hold = it }

        val result = async { gate.submitMasterPassword(password, MpPurpose.UNLOCK) }
        rig.settle()
        assertEquals(fresh.copy(opening = true), gate.state.value.field())

        hold.complete(Unit)
        rig.settle()
        assertTrue(result.await() is MpResult.Right)
        assertEquals(LockUiState.Unlocked(emptyList(), null), gate.state.value)
    }

    @Test
    fun fiveWrongInARow_thenEachTryWaits30SecondsOfUptime_neverMore() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val wrong = "wrong".toCharArray()

        repeat(MasterPasswordGuard.FREE_TRIES) {
            assertEquals(MpResult.Wrong, gate.submitMasterPassword(wrong, MpPurpose.UNLOCK))
            rig.clock.advance(1_000)
        }
        // The fifth wrong one was typed at 4 s: the field waits until 34 s (lock.md §6.8 step 1).
        assertEquals(MasterPasswordGuard.FREE_TRIES, rig.vault.typed.size)
        rig.settle()
        val until = 4_000L + MasterPasswordGuard.WAIT_MS
        assertEquals(until, gate.state.value.field().waitUntilElapsedMs)

        assertEquals("the sixth try waits and is not derived", MpResult.Wait(until), gate.submitMasterPassword(password, MpPurpose.UNLOCK))
        assertEquals(MasterPasswordGuard.FREE_TRIES, rig.vault.typed.size)

        rig.clock.advance(until - rig.clock.elapsedMs())
        rig.settle()
        assertNull("the field is enabled again when the wait is over", gate.state.value.field().waitUntilElapsedMs)
        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
    }

    @Test
    fun notEnoughMemoryIsNotWrongAndNotCounted() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        repeat(MasterPasswordGuard.FREE_TRIES + 2) { rig.vault.opens += PasswordOpen.NoMemory }

        repeat(MasterPasswordGuard.FREE_TRIES + 2) {
            assertEquals(MpResult.NoMemory, gate.submitMasterPassword(password, MpPurpose.UNLOCK))
        }
        assertEquals(fresh.copy(error = MpError.NoMemory), gate.state.value.field())
        assertTrue("no wait: none of them was counted", gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
    }

    @Test
    fun anOlderCopyOpensWithItsNotice_whichGoesWhenAnswered() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.vault.olderCopy = true
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()

        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)

        val notice = Notice.OlderCopy(1L, InMemoryVault.CREATED_MS)
        assertEquals(LockUiState.Unlocked(listOf(notice), null), gate.state.value)
        gate.send(LockIntent.NoticeAnswered(notice.id, NoticeAnswer.OK))
        assertEquals(LockUiState.Unlocked(emptyList(), null), gate.state.value)
    }

    @Test
    fun aRightPasswordWhoseVaultCannotBeOpenedShowsCannotBeOpened() = runTest {
        for ((answer, why) in listOf(
            PasswordOpen.Damaged to CurrentVault.Why.DAMAGED,
            PasswordOpen.Newer to CurrentVault.Why.NEWER,
            PasswordOpen.Unreadable to CurrentVault.Why.IO,
        )) {
            val rig = GateRig(this)
            rig.vaultOnThePhone(password)
            rig.vault.opens += answer
            rig.process.toFront()
            val gate = rig.start()
            rig.settle()
            assertEquals(MpResult.TooLate, gate.submitMasterPassword(password, MpPurpose.UNLOCK))
            assertEquals(LockUiState.Unreadable(why, emptyList()), gate.state.value)
        }
    }

    @Test
    fun anOlderMasterPasswordShowsItsScreen_andOkGoesBackToTheField() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.vault.opens += PasswordOpen.OlderPassword(changedAtMs = 1_790_000_000_000L, generation = 3L)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()

        assertEquals(MpResult.OlderPassword(1_790_000_000_000L, unfinishedChange = false), gate.submitMasterPassword(password, MpPurpose.UNLOCK))
        assertEquals(LockUiState.Locked(LockScreen.OlderPassword(1_790_000_000_000L, unfinishedChange = false)), gate.state.value)
        gate.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK))
        assertEquals(LockUiState.Locked(fresh), gate.state.value)
    }

    // ---- visits (I11) ----------------------------------------------------------------------------

    @Test
    fun aCheckThatFinishesAfterOnStopOpensNothing_andTheNextVisitStartsAfresh() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val hold = CompletableDeferred<Unit>().also { rig.vault.hold = it }

        val result = async { gate.submitMasterPassword(password, MpPurpose.UNLOCK) }
        rig.settle()
        rig.process.away() // the screen went off, or the app was left, while Argon2id ran
        hold.complete(Unit)
        rig.settle()

        assertEquals(MpResult.TooLate, result.await())
        assertTrue(gate.state.value is LockUiState.Locked)
        assertFalse("what the port opened was dropped again", rig.vault.isOpen)
        assertEquals(listOf("vaults", "openWithPassword", "VaultPort.lock"), rig.vault.log)

        rig.vault.hold = null
        rig.process.toFront()
        rig.settle()
        assertEquals(LockUiState.Locked(fresh), gate.state.value)
        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
    }

    @Test
    fun aWrongCheckThatFinishesAfterOnStopIsStillCounted() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val wrong = "wrong".toCharArray()
        repeat(MasterPasswordGuard.FREE_TRIES - 1) { assertEquals(MpResult.Wrong, gate.submitMasterPassword(wrong, MpPurpose.UNLOCK)) }
        val hold = CompletableDeferred<Unit>().also { rig.vault.hold = it }
        val fifth = async { gate.submitMasterPassword(wrong, MpPurpose.UNLOCK) }
        rig.settle()
        rig.process.away()
        hold.complete(Unit)
        rig.settle()
        assertEquals(MpResult.Wrong, fifth.await())

        rig.vault.hold = null
        rig.process.toFront()
        rig.settle()
        assertTrue("the fifth wrong try was counted: the next one waits", gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Wait)
    }

    @Test
    fun aPasswordSubmittedWithNoVisitIsNotChecked() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        val gate = rig.start() // the process never came to the front: no lock screen was visible
        rig.settle()
        assertEquals(MpResult.TooLate, gate.submitMasterPassword(password, MpPurpose.UNLOCK))
        assertFalse("openWithPassword" in rig.vault.log)
        assertTrue(gate.state.value is LockUiState.Locked)
    }

    // ---- lock ------------------------------------------------------------------------------------

    @Test
    fun lockDropsTheSessionAndEveryProof_thenShowsTheFieldAfresh() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        assertEquals(MpResult.Wrong, gate.submitMasterPassword("wrong".toCharArray(), MpPurpose.UNLOCK))
        val proof = (gate.submitMasterPassword(password, MpPurpose.UNLOCK) as MpResult.Right).proof
        val inApp = (gate.submitMasterPassword(password, MpPurpose.BACKUP) as MpResult.Right).proof

        gate.lock(LockReason.LOCK_NOW)

        assertEquals("VaultPort.lock", rig.vault.log.last())
        assertFalse(rig.vault.isOpen)
        assertEquals("the line of an earlier try is gone", LockUiState.Locked(fresh), gate.state.value)
        assertThrows(IllegalStateException::class.java) { proof.useBytes { } }
        assertThrows(IllegalStateException::class.java) { inApp.useBytes { } }
        assertTrue("the next visit has begun: the field works at once", gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
    }

    @Test
    fun lockWhileLockedOrWithoutAVaultDoesNothing() = runTest {
        val empty = GateRig(this)
        val welcome = empty.start()
        empty.settle()
        welcome.lock(LockReason.SCREEN_OFF)
        assertEquals(LockUiState.NoVault(emptyList()), welcome.state.value)

        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        val locked = rig.start()
        rig.settle()
        locked.lock(LockReason.LEFT)
        assertEquals(LockUiState.Locked(fresh), locked.state.value)
        assertFalse("VaultPort.lock" in empty.vault.log + rig.vault.log)
    }

    @Test
    fun onStopDropsTheProofsButDoesNotLock() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val proof = (gate.submitMasterPassword(password, MpPurpose.UNLOCK) as MpResult.Right).proof

        rig.process.away()

        assertThrows(IllegalStateException::class.java) { proof.useBytes { } }
        assertTrue("locking is AutoLock's decision, not the gate's", gate.state.value is LockUiState.Unlocked)
    }

    // ---- the open app's checks -------------------------------------------------------------------

    @Test
    fun aStepOfTheOpenAppChecksTheOpenVaultsSlot_andChangesNothing() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
        val unlocked = gate.state.value

        val right = gate.submitMasterPassword(password, MpPurpose.EXPORT)
        assertTrue(right is MpResult.Right)
        assertArrayEquals(bytesOf(password), (right as MpResult.Right).proof.useBytes { it.copyOf() })
        assertEquals(MpResult.Wrong, gate.submitMasterPassword("wrong".toCharArray(), MpPurpose.BACKUP))
        rig.vault.checks += null
        assertEquals(MpResult.NoMemory, gate.submitMasterPassword(password, MpPurpose.PRACTICE))
        assertSame("a check in the open app never changes the state", unlocked, gate.state.value)
        assertEquals(3, rig.vault.log.count { it == "checkPassword" })
    }

    @Test
    fun aCheckOfTheOpenAppThatIsRunningWhenTheAppLocks_opensNothing() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
        val hold = CompletableDeferred<Unit>().also { rig.vault.checkHold = it }

        val check = async { gate.submitMasterPassword(password, MpPurpose.BACKUP) }
        rig.settle()
        val locking = async { gate.lock(LockReason.NO_TOUCH) }
        rig.settle()
        assertFalse("the lock waits for the running check (the lock mutex)", "VaultPort.lock" in rig.vault.log)
        hold.complete(Unit)
        rig.settle()
        locking.await()

        assertEquals("its prompt ended with the lock (lock.md §6.3)", MpResult.TooLate, check.await())
        assertEquals(LockUiState.Locked(fresh), gate.state.value)
        assertEquals(listOf("checkPassword", "VaultPort.lock"), rig.vault.log.takeLast(2))
    }

    @Test
    fun aCheckOfTheOpenAppThatIsRunningAtOnStop_opensNothing() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        assertTrue(gate.submitMasterPassword(password, MpPurpose.UNLOCK) is MpResult.Right)
        val hold = CompletableDeferred<Unit>().also { rig.vault.checkHold = it }

        val check = async { gate.submitMasterPassword(password, MpPurpose.EXPORT) }
        rig.settle()
        rig.process.away()
        hold.complete(Unit)
        rig.settle()

        assertEquals(MpResult.TooLate, check.await())
        assertTrue("whether to lock is AutoLock's decision", gate.state.value is LockUiState.Unlocked)
    }

    @Test
    fun aStepOfTheOpenAppWhileLockedChecksNothing() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        for (purpose in MpPurpose.entries - MpPurpose.UNLOCK) {
            assertEquals(MpResult.TooLate, gate.submitMasterPassword(password, purpose))
        }
        assertFalse(rig.vault.log.any { it == "checkPassword" || it == "openWithPassword" })
    }

    // ---- what the gate leaves to later packages --------------------------------------------------

    @Test
    fun theFlowsOfLaterPackagesChangeNothing() = runTest {
        val rig = GateRig(this)
        rig.vaultOnThePhone(password)
        rig.process.toFront()
        val gate = rig.start()
        rig.settle()
        val proof = (gate.submitMasterPassword(password, MpPurpose.UNLOCK) as MpResult.Right).proof
        val pin = "582913".encodeToByteArray()
        val log = rig.vault.log.toList()

        assertEquals(PinBlocklist.reason("123456".encodeToByteArray()), gate.pinProblem("123456".encodeToByteArray()))
        assertNull(gate.pinProblem(pin))
        assertEquals(PinSetResult.NotSet, gate.setPin(pin, proof))
        assertEquals(PinChange.CannotCheck, gate.changePin(pin, "905127".encodeToByteArray()))
        assertEquals(FingerprintOnResult.Failed, gate.fingerprintOn(proof))
        gate.fingerprintOff()
        assertEquals(ChangeResult.Mismatch, gate.changeMasterPassword(proof, "a new one".toCharArray(), "a new two".toCharArray(), null))
        assertEquals(ChangeResult.NothingChanged, gate.changeMasterPassword(proof, "a new one".toCharArray(), "a new one".toCharArray(), null))
        assertEquals(RequestResult.Mismatch, gate.requestNewMasterPassword("x1".toCharArray(), "x2".toCharArray()))
        assertEquals(RequestResult.NotAllowed, gate.requestNewMasterPassword("x1".toCharArray(), "x1".toCharArray()))
        gate.cancelNewMasterPassword()
        assertEquals(AsideResult.NotSaved, gate.openAside(GateRig.OTHER_ID, password))
        assertEquals(AsideResult.NotSaved, gate.deleteAside(GateRig.OTHER_ID, password))
        for (intent in listOf(
            LockIntent.Digit(1), LockIntent.DeleteDigit, LockIntent.ClearDigits, LockIntent.UsePin, LockIntent.UseFingerprint,
            LockIntent.NotNow, LockIntent.CantRemember, LockIntent.OpenForgotSheet, LockIntent.CloseSheet,
            LockIntent.OpenAside(GateRig.OTHER_ID), LockIntent.StartEmpty, LockIntent.RestoreFromFile, LockIntent.CancelPending,
            LockIntent.FinishPending, LockIntent.ItWasntMe, LockIntent.GoBack,
        )) {
            gate.send(intent)
        }
        rig.settle()

        assertEquals("none of them reached the vault", log, rig.vault.log)
        assertEquals(LockUiState.Unlocked(emptyList(), null), gate.state.value)
        assertEquals(LockStatus.NONE, gate.status.value)
    }
}
