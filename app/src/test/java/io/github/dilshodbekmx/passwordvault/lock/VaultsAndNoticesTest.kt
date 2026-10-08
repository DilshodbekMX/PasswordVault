package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: the vaults, the notices and the banners' intents through
 * [DefaultLockManager] with fake ports. "Start with an empty app" and "Open my previous passwords"
 * with Back (lock.md F17: `StartEmpty`, `OpenAside`, `GoBack`); the lock screen of a vault put aside
 * (§13.2); the answers to the notices (§13.4: `NoticeAnswered`), the older-copy answer among them;
 * Settings › Old passwords under that vault's own guard (`openAside`, `deleteAside`); a pending and a
 * replaced master password as the status shows them, with `CancelPending`, `FinishPending` and
 * `ItWasntMe`; the flows of F8 and F11 that go straight to the engine; and a phone without secure
 * hardware (F18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultsAndNoticesTest {

    /** A phone with a vault and no PIN, locked, in front: the master-password screen, no shortcut usable. */
    private suspend fun lockedWithoutShortcut(rig: LockRig) {
        rig.setUp()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        assertFalse(rig.field.canUsePin)
    }

    // ---- "Start with an empty app" and "Open my previous passwords" (lock.md F17) ----------------------

    @Test
    fun startEmptyPutsTheVaultAside_andWelcomeListsIt() = runTest {
        val rig = LockRig(this)
        lockedWithoutShortcut(rig)
        val id = rig.vaultId
        rig.send(LockIntent.OpenForgotSheet)
        rig.send(LockIntent.CloseSheet) // an option of the sheet closes it first
        rig.send(LockIntent.StartEmpty)

        val welcome = rig.state as LockUiState.NoVault
        assertEquals(listOf(id), welcome.aside.map { it.vaultId })
        assertNull(rig.port.current)
        assertEquals("moved, never deleted (I9)", listOf(id), rig.port.asideVaults.map { it.vaultId })
        assertTrue("its lock records stay where they are (lock.md F17 step 2)", rig.files.hasDir(rig.dirOf(id)))
    }

    @Test
    fun startEmptyIsRefusedWhileAShortcutCanOpenTheVault() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.StartEmpty)
        assertTrue("the PIN is the way on (lock.md F17 step 1)", rig.state is LockUiState.Locked)
        assertNotNull(rig.port.current)
        assertFalse(rig.port.calls.any { it.startsWith("putAside") })
    }

    @Test
    fun aVaultPutAsideHasItsOwnLockScreen_backChangesNothing_andItsMasterPasswordMakesItCurrent() = runTest {
        val rig = LockRig(this)
        lockedWithoutShortcut(rig)
        val id = rig.vaultId
        rig.send(LockIntent.StartEmpty)
        val putAsideAt = (rig.state as LockUiState.NoVault).aside.single().putAsideAtMs

        rig.send(LockIntent.OpenAside(id)) // "Open my previous passwords (locked on …)" on Welcome
        val field = rig.field
        assertEquals("a vault that was put aside: its own screen, with its line (lock.md §13.2)", AsideLine.LockedOn(putAsideAt), field.aside)
        assertNull("practice is not asked there", field.practice)

        rig.send(LockIntent.GoBack)
        assertTrue("Back returns to where the current vault stands, and nothing changed", rig.state is LockUiState.NoVault)
        assertFalse(rig.port.calls.any { it.startsWith("swapIn") })

        rig.send(LockIntent.OpenAside(id))
        assertEquals(MpResult.Wrong, rig.unlockWith("not its password"))
        assertEquals("that vault's own guard counted it (lock.md §6.8)", 1, rig.stateRecord(id).mpGuard.failures)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        assertTrue(rig.isUnlocked)
        assertEquals("only when it has opened is it made current", id, rig.port.current?.vaultId)
        assertEquals(emptyList<String>(), rig.port.asideVaults.map { it.vaultId })
    }

    @Test
    fun openAsideForAVaultThatIsNotThere_changesNothing() = runTest {
        val rig = LockRig(this)
        lockedWithoutShortcut(rig)
        val before = rig.state
        rig.send(LockIntent.OpenAside("00000000-0000-4000-8000-0000000000ff"))
        assertEquals(before, rig.state)
        rig.send(LockIntent.GoBack) // nothing to go back from
        assertEquals(before, rig.state)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    // ---- the notices (lock.md §13.4) ----------------------------------------------------------------------

    @Test
    fun anAnswerTakesItsNoticeOff_andAnIdThatIsNotShownDoesNothing() = runTest {
        val rig = LockRig(this)
        rig.setUp()
        val offer = rig.notices.single()
        rig.send(LockIntent.NoticeAnswered(offer.id + 100, NoticeAnswer.OK))
        assertEquals(listOf(offer), rig.notices)
        rig.send(LockIntent.NoticeAnswered(offer.id, NoticeAnswer.LATER))
        assertEquals(emptyList<Notice>(), rig.notices)
        assertEquals("only the answer to an older copy concerns the vault", 0, rig.olderCopyAnswers)
    }

    @Test
    fun theAnswerToAnOlderCopyLetsTheVaultsAutomaticWritesRunAgain() = runTest {
        val rig = LockRig(this)
        val vault = rig.port.seed(VAULT, LockRig.PASSWORD.toByteArray(), generations = 2)
        vault.generation(2).state = FakeVaultPort.FileState.DAMAGED // the newest copy cannot be read
        rig.startInFront()
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        val first = rig.notices.first()
        assertTrue("storage's notice comes first (lock.md §13.4 item 1)", first is Notice.OlderCopy)
        assertEquals(0, rig.olderCopyAnswers)

        rig.send(LockIntent.NoticeAnswered(first.id, NoticeAnswer.OK))

        assertEquals("architecture.md §5.5: automatic writes waited for this answer", 1, rig.olderCopyAnswers)
        assertFalse(rig.notices.any { it is Notice.OlderCopy })
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(1, rig.olderCopyAnswers)
    }

    @Test
    fun theNoticeAboutWrongTriesTakesOffWhatItShowed() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.typePin("582914")
        rig.away()
        rig.toFront()
        rig.typePin() // a right PIN on a later visit forgives nothing
        val tries = rig.notices.single() as Notice.WrongTries
        assertEquals(1, tries.wrongPins)
        assertEquals(1, rig.pinRecord().unseen.count)

        rig.send(LockIntent.NoticeAnswered(tries.id, NoticeAnswer.OK))

        assertEquals(0, rig.pinRecord().unseen.count)
    }

    // ---- Settings › Old passwords (lock.md F17 step 5) -----------------------------------------------------

    /** A phone whose first vault (with a PIN) was put aside by a restore, and whose current vault is open. */
    private suspend fun withAnOldVault(rig: LockRig): String {
        rig.setUpWithPin()
        val old = rig.vaultId
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        val restored = rig.manager.createVault(LockRig.OTHER_PASSWORD.toCharArray(), LockRig.OTHER_PASSWORD.toCharArray(), Vault())
        rig.settle()
        assertTrue(restored is CreateResult.Created)
        rig.answerNotices(NoticeAnswer.LATER)
        return old
    }

    @Test
    fun oldPasswordsOpenOnlyWithThatVaultsOwnMasterPassword_underItsOwnGuard() = runTest {
        val rig = LockRig(this)
        val old = withAnOldVault(rig)

        assertEquals("not the current vault's master password", AsideResult.Wrong, rig.manager.openAside(old, LockRig.OTHER_PASSWORD.toCharArray()))
        assertEquals(1, rig.stateRecord(old).mpGuard.failures)
        assertEquals("the current vault's own count is untouched", 0, rig.stateRecord().mpGuard.failures)

        assertEquals(AsideResult.Done, rig.manager.openAside(old, LockRig.PASSWORD.toCharArray()))
        rig.settle()
        assertTrue("its accounts joined the current vault", rig.port.calls.any { it.startsWith("mergeAside") })
        assertEquals("the vault put aside stays", listOf(old), rig.status.aside.map { it.vaultId })
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun deletingOldPasswordsNeedsTheirOwnMasterPassword_andTakesTheirLockStateAlong() = runTest {
        val rig = LockRig(this)
        val old = withAnOldVault(rig)
        val oldKeys = rig.keys.aliases.toSet()
        assertEquals(1, oldKeys.size)

        assertEquals(AsideResult.Wrong, rig.manager.deleteAside(old, LockRig.OTHER_PASSWORD.toCharArray()))
        assertEquals(listOf(old), rig.port.asideVaults.map { it.vaultId })

        val delete = backgroundScope.asyncOn { rig.manager.deleteAside(old, LockRig.PASSWORD.toCharArray()) }
        rig.settle()
        rig.advance(LockRig.CHARGE_AGE_MS) // the keys go once the deletion of the records has settled
        rig.advance(LockRig.CHARGE_AGE_MS)

        assertEquals(AsideResult.Done, delete.getCompleted())
        assertEquals(emptyList<String>(), rig.port.asideVaults.map { it.vaultId })
        assertFalse("its lock directory went with it", rig.files.hasDir(rig.dirOf(old)))
        assertEquals("and the key its PIN record named", emptySet<String>(), rig.keys.aliases.toSet() intersect oldKeys)
        assertEquals(emptyList<String>(), rig.status.aside.map { it.vaultId })
        assertTrue("the open app stays open", rig.isUnlocked)
    }

    @Test
    fun oldPasswordsCannotBeReachedWhileTheAppIsLocked() = runTest {
        val rig = LockRig(this)
        val old = withAnOldVault(rig)
        rig.lock()
        assertEquals(AsideResult.NotSaved, rig.manager.openAside(old, LockRig.PASSWORD.toCharArray()))
        assertEquals(AsideResult.NotSaved, rig.manager.deleteAside(old, LockRig.PASSWORD.toCharArray()))
        assertEquals(listOf(old), rig.port.asideVaults.map { it.vaultId })
    }

    // ---- a pending new master password (lock.md F11, §13.4 item 2) -------------------------------------------

    /**
     * A phone with a PIN (and fingerprint unlock when [fingerprint]) whose `state.rec` holds a
     * request for a new master password with [creditedMs] of its 24 hours credited, in a new process,
     * in front, on the pad.
     */
    private suspend fun withAPendingPassword(rig: LockRig, creditedMs: Long, fingerprint: Boolean = false) {
        val proof = rig.setUpWithPin()
        if (fingerprint) rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
        val pending = StateRecord.PendingMp(
            requestedMs = rig.clock.wallMs(),
            by = Shortcut.PIN,
            creditedMs = creditedMs,
            seenElapsedMs = rig.clock.elapsedMs(),
            vkId = rig.port.current!!.newestVkId,
            salt = ByteArray(16) { 1 },
            nonce = ByteArray(12) { 2 },
            ct = ByteArray(48) { 3 },
        )
        rig.putState(rig.stateRecord().copy(pendingMp = pending))
        rig.restart()
        rig.toFront()
    }

    @Test
    fun aPendingPasswordIsInTheStatus_andBecomesReadyWhenItsHoursAreCredited() = runTest {
        val rig = LockRig(this)
        withAPendingPassword(rig, creditedMs = DAY_MS - HOUR_MS)
        rig.typePin()

        val pending = rig.status.pending!!
        assertFalse(pending.ready)
        assertEquals("the second the PIN's charge aged is credited too (lock.md §6.9)", HOUR_MS - LockRig.CHARGE_AGE_MS, pending.remainingMs)
        assertEquals(Shortcut.PIN, pending.by)
        assertTrue("after the unlock: who asked, when, and how long is left", rig.notices.single() is Notice.Pending)

        rig.send(LockIntent.FinishPending)
        assertTrue("\"Finish now\" before its time changes nothing", rig.isUnlocked)
        assertEquals("and asks for no lock", 0, rig.lockNows)

        rig.advance(pending.remainingMs - 1)
        assertFalse(rig.status.pending!!.ready)
        rig.advance(1) // the manager asks the engine again at the moment the hours are credited
        assertTrue("the banner becomes \"Your new master password is ready\"", rig.status.pending!!.ready)
    }

    @Test
    fun whileTheAppIsNotVisibleNothingWakesForIt_andTheReturnBringsTheStatusUpToDate() = runTest {
        val rig = LockRig(this)
        withAPendingPassword(rig, creditedMs = DAY_MS - HOUR_MS)
        rig.typePin()
        val writes = rig.files.log.size
        rig.away() // left within the lock delay: the app stays open
        val afterLeaving = rig.files.log.size
        assertTrue("leaving observes the credit once (lock.md §6.9)", afterLeaving >= writes)

        rig.advance(2 * HOUR_MS)
        assertFalse("nobody looked: the status is as it was left", rig.status.pending!!.ready)
        assertEquals("and no record was written meanwhile", afterLeaving, rig.files.log.size)

        rig.toFront()
        assertTrue(rig.isUnlocked)
        assertTrue("the return asks the engine again", rig.status.pending!!.ready)
    }

    @Test
    fun finishNowAsksForTheMasterPasswordOnTheLockScreen_withNoFingerprintPromptOverIt() = runTest {
        val rig = LockRig(this)
        withAPendingPassword(rig, creditedMs = DAY_MS, fingerprint = true)
        rig.promptError(10) // this visit's own prompt
        rig.typePin()
        assertTrue(rig.status.pending!!.ready)
        assertTrue(rig.notices.single() is Notice.PendingReady)
        val promptsBefore = rig.window.shows.count { it.use == PromptUse.UNLOCK }

        rig.send(LockIntent.FinishPending) // "Finish now" on the list's banner

        assertEquals("the lock is the session's, as \"Lock now\" (architecture.md §5.5)", 1, rig.lockNows)
        // lock.md F11 step 13: "The lock screen's master-password field accepts it as well."
        val field = rig.field
        assertNull(field.reason)
        assertNull("it is ready: no line about hours left", field.pendingReadyInMs)
        assertTrue("the PIN leads back as on any lock screen", field.canUsePin)
        assertEquals("no fingerprint prompt opens over the field", promptsBefore, rig.window.shows.count { it.use == PromptUse.UNLOCK })

        val typed = try {
            rig.unlockWith(NEW_PASSWORD)
        } catch (e: NotImplementedError) {
            assertTrue("PasswordFlows.typedPending is package 4.4's: the entry reached it", LockRig.isStubOf44(e, "F11 step 13"))
            return@runTest
        }
        // The planted wrap is no real one, so this password does not open it: it is wrong, and counted as such.
        assertEquals(MpResult.Wrong, typed)
        rig.send(LockIntent.UsePin)
        assertTrue(rig.screen is LockScreen.Pad)
    }

    @Test
    fun cancelItGoesToTheEngine_fromTheBannerAndFromSettings() = runTest {
        val rig = LockRig(this)
        withAPendingPassword(rig, creditedMs = 0)
        rig.typePin()
        assertNotNull(rig.status.pending)

        rig.send(LockIntent.CancelPending)

        if (rig.reached44("F11 step 8")) { // PasswordFlows.cancelPending is package 4.4's: the intent reached it
            rig.manager.cancelNewMasterPassword()
            rig.settle()
            assertTrue("and so does LockManager.cancelNewMasterPassword", rig.reached44("F11 step 8"))
            return@runTest
        }
        assertNull("cancelling needs no proof and deletes the request (lock.md F11 step 8)", rig.status.pending)
        assertNull(rig.stateRecord().pendingMp)
        rig.manager.cancelNewMasterPassword() // nothing left to cancel
        rig.settle()
        assertNull(rig.status.pending)
    }

    // ---- a replaced master password (lock.md F11 step 20, F16b) -----------------------------------------------

    @Test
    fun itWasntMe_opensTheLockScreenOfThePasswordsFromBefore_andDoesNothingWithoutAReplacement() = runTest {
        val rig = LockRig(this)
        rig.port.seedAside(OLD_VAULT, "the owner's password".toByteArray(), putAsideAtMs = rig.clock.wallMs())
        rig.port.seed(VAULT, LockRig.PASSWORD.toByteArray())
        val replacedAt = rig.clock.wallMs()
        rig.putState(
            StateRecord(
                vaultId = OLD_VAULT,
                practice = StateRecord.Practice(replacedAt, 3, null),
                mpGuard = StateRecord.MpGuard(0, null, 0),
                mpChange = null,
                pendingMp = null,
                replaced = StateRecord.Replaced(VAULT, replacedAt, Shortcut.PIN, creditedMs = 0, seenElapsedMs = rig.clock.elapsedMs()),
                maxGeneration = StateRecord.MaxGeneration(1, replacedAt),
            ),
        )
        rig.startInFront()
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)

        val replaced = rig.status.replaced!!
        assertEquals(OLD_VAULT, replaced.oldVaultId)
        assertEquals(Shortcut.PIN, replaced.by)
        assertTrue("\"Your master password was replaced on …\" (lock.md §13.4 item 3)", rig.notices.any { it is Notice.Replaced })

        rig.send(LockIntent.ItWasntMe) // "It wasn't me" on the list's banner

        if (rig.reached44("F16b")) return@runTest // OlderCopies.itWasntMe is package 4.4's: the intent reached it
        val field = rig.field
        assertEquals("the old vault's own lock screen (lock.md F16b step 2)", AsideLine.FromBefore(replacedAt), field.aside)
        assertEquals(MpReason.FromBefore(replacedAt), field.reason)
    }

    @Test
    fun itWasntMeWithoutAReplacementDoesNothing() = runTest {
        val rig = LockRig(this)
        rig.setUp()
        assertNull(rig.status.replaced)
        val before = rig.state
        rig.send(LockIntent.ItWasntMe)
        assertEquals(before, rig.state)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    // ---- the flows that go straight to the engine (lock.md F8, F11 steps 3 to 5) ----------------------------

    @Test
    fun changingAndRequestingAMasterPassword_compareTheTwoTypingsFirst() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUpWithPin()
        val writes = rig.port.writingCalls.size

        val change = try {
            rig.manager.changeMasterPassword(proof, "a new master password".toCharArray(), "a new master passwort".toCharArray(), null)
        } catch (e: NotImplementedError) {
            assertTrue("PasswordFlows.changeMasterPassword is package 4.4's: the call reached it", LockRig.isStubOf44(e, "F8"))
            null
        }
        if (change != null) assertEquals("Mismatch before anything is derived", ChangeResult.Mismatch, change)

        val request = try {
            rig.manager.requestNewMasterPassword("another new password".toCharArray(), "another new passwort".toCharArray())
        } catch (e: NotImplementedError) {
            assertTrue("PasswordFlows.requestNewMasterPassword is package 4.4's: the call reached it", LockRig.isStubOf44(e, "F11"))
            null
        }
        if (request != null) assertEquals(RequestResult.Mismatch, request)

        assertEquals("nothing was written", writes, rig.port.writingCalls.size)
        assertTrue(rig.isUnlocked)
    }

    // ---- a phone that cannot keep keys in secure hardware (lock.md F18) -----------------------------------

    @Test
    fun withoutSecureHardware_noPinIsOffered_andItsReasonIsGivenTheFirstTimeOnly() = runTest {
        val rig = LockRig(this)
        rig.keys.softwareOnly = true // a release build on a phone whose keys would be in software
        rig.setUp()
        assertEquals("F18: setup offers no PIN", emptyList<Notice>(), rig.notices)
        assertEquals(PinStatus.NoSecureHardware, rig.status.pin)
        assertEquals(FingerprintStatus.NoSecureHardware, rig.status.fingerprint)

        rig.lock()
        assertEquals("the first time: \"This phone can't keep a PIN or fingerprint key in protected hardware …\"", MpReason.NoSecureHardware, rig.field.reason)
        assertFalse(OneTime.NO_SECURE_HARDWARE_TOLD in rig.settings.oneTime.value)

        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        assertTrue("it was on the screen that this unlock followed: kept for later processes", OneTime.NO_SECURE_HARDWARE_TOLD in rig.settings.oneTime.value)
        rig.lock()
        assertNull("not again", rig.field.reason)

        // A new process knows it from the settings.
        rig.restart()
        rig.toFront()
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right) // this process finds out about the hardware here
        assertEquals(PinStatus.NoSecureHardware, rig.status.pin)
        rig.lock()
        assertNull(rig.field.reason)
    }

    @Test
    fun whenThatCannotBeKept_theReasonIsGivenOnceMoreInTheNextProcess() = runTest {
        val rig = LockRig(this)
        rig.keys.softwareOnly = true
        rig.setUp()
        rig.lock()
        rig.baseSettings.failWrites = true // the settings cannot be written
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        rig.lock()
        assertNull("this process has said it", rig.field.reason)

        rig.restart()
        rig.toFront()
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        rig.lock()
        assertEquals(MpReason.NoSecureHardware, rig.field.reason)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    private companion object {
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val OLD_VAULT = "00000000-0000-4000-8000-0000000000a1"
        const val NEW_PASSWORD = "yangi parol 2026 kuz fasli"
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
