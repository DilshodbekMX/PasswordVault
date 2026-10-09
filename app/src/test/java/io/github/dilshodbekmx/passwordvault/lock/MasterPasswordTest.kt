package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.BackupReminder
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: the master password through [DefaultLockManager] with fake ports. The
 * master-password screen's states of lock.md §13.2 (idle, "Opening…", waiting) and its lines; the
 * practice screens with `UsePin`, `NotNow` and `CantRemember` (§8.3, F10); "This is an older master
 * password" (F16b); the checks of the open app with a proof of their own; D-9b after every correct
 * master password; the backup offer (F12 step 2); an automatic lock that drops the proof (§5.8,
 * §10.4); and a result that comes after its visit (I11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterPasswordTest {

    /** A phone with a vault and a PIN, locked, in front, on the master-password screen the user chose. */
    private suspend fun lockedOnTheField(rig: LockRig) {
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.vault.matched.clear()
        assertNull(rig.field.reason)
    }

    /** [call] of the vault port waits until the returned gate is completed, as if Argon2id were still running. */
    private fun holdPort(rig: LockRig, call: String): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        rig.port.beforeCall = { name -> if (name.startsWith(call)) gate.await() }
        return gate
    }

    // ---- the lock screen's field (lock.md §6.8, §13.2, §13.3) -----------------------------------------

    @Test
    fun theRightMasterPasswordOpensWithItsProof_returnsTheCounts_andOffersANewPin() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.typePin("582914")
        rig.typePin("582915")
        rig.away() // the wrong PINs are no longer this visit's: nothing forgives them
        rig.toFront()
        rig.send(LockIntent.UseMasterPassword) // "Forgot PIN? Use master password" (lock.md F7)

        val result = rig.unlockWith(LockRig.PASSWORD)

        val proof = (result as MpResult.Right).proof
        val unlocked = rig.unlocked
        assertSame(proof, unlocked.proof)
        assertEquals(LockRig.PASSWORD, proof.useBytes { String(it, Charsets.UTF_8) })
        val record = rig.pinRecord()
        assertEquals("a correct master password returns both counts to zero (lock.md §6.4)", 0 to 0, record.guard.row to record.guard.total)
        val tries = unlocked.notices.filterIsInstance<Notice.WrongTries>().single()
        assertEquals(2, tries.wrongPins)
        val offer = unlocked.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals("F7 step 3: \"Choose a new PIN\" with the unlock just made as its proof", ChoosePinReason.FORGOTTEN, offer.reason)
        assertFalse(offer.needsMasterPassword)
    }

    @Test
    fun aWrongMasterPasswordShowsItsLine_andTheSecondOneOffersTheWayOut() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        assertEquals(MpResult.Wrong, rig.unlockWith("not the password"))
        assertEquals(MpError.Wrong(offerForgot = false, replaced = null), rig.field.error)
        assertEquals(MpResult.Wrong, rig.unlockWith("not it either"))
        assertEquals("wrong twice while the PIN works: \"Can't remember it? …\" (lock.md §13.3)", MpError.Wrong(offerForgot = true, replaced = null), rig.field.error)
        assertEquals(2, rig.stateRecord().mpGuard.failures)
        assertEquals("a wrong password is not compared with anything (D-9b)", 0, rig.vault.matched.size)
        assertNull(rig.port.openVaultId)
    }

    @Test
    fun openingIsShownWhileTheMasterPasswordIsChecked() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val running = holdPort(rig, "openWithPassword")
        val check = backgroundScope.asyncOn { rig.manager.submitMasterPassword(LockRig.PASSWORD.toCharArray(), MpPurpose.UNLOCK) }
        rig.settle()
        assertTrue("master password, opening (lock.md §13.2)", rig.field.opening)
        running.complete(Unit)
        rig.settle()
        assertTrue(check.getCompleted() is MpResult.Right)
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun afterFiveWrongOnesTheFieldWaitsThirtySeconds_andIsFreeAgainWhenTheyAreOver() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        repeat(5) { assertEquals(MpResult.Wrong, rig.unlockWith("wrong number $it")) }
        val until = rig.clock.elapsedMs() + 30_000L
        assertEquals("master password, waiting: the field counts down to this moment (lock.md §6.8)", until, rig.field.waitUntilElapsedMs)

        val early = rig.unlockWith(LockRig.PASSWORD)
        assertEquals("nothing is tried during the wait, not even the right one", MpResult.Wait(until), early)
        assertTrue(rig.state is LockUiState.Locked)

        rig.advance(29_999)
        assertEquals(until, rig.field.waitUntilElapsedMs)
        rig.advance(1) // the manager asks the engine again when the wait is over
        assertNull("the field is enabled again", rig.field.waitUntilElapsedMs)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
    }

    @Test
    fun notEnoughMemoryIsNeitherWrongNorCounted() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        rig.port.noMemory = true
        assertEquals(MpResult.NoMemory, rig.unlockWith(LockRig.PASSWORD))
        assertEquals(MpError.NoMemory, rig.field.error)
        assertEquals(0, rig.stateRecord().mpGuard.failures)
        rig.port.noMemory = false
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
    }

    @Test
    fun aRightMasterPasswordWhoseCheckEndsAfterTheAppWasLeft_opensNothing() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val running = holdPort(rig, "openWithPassword")
        val check = backgroundScope.asyncOn { rig.manager.submitMasterPassword(LockRig.PASSWORD.toCharArray(), MpPurpose.UNLOCK) }
        rig.settle()

        rig.away() // the visit ends while Argon2id runs
        running.complete(Unit)
        rig.settle()

        assertEquals("lock.md §6.8 step 8", MpResult.TooLate, check.getCompleted())
        assertTrue(rig.state is LockUiState.Locked)
        assertNull("the key was dropped again", rig.port.openVaultId)
        assertEquals("nothing was handed out, so nothing was compared", 0, rig.vault.matched.size)

        rig.toFront()
        rig.send(LockIntent.UseMasterPassword)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
    }

    @Test
    fun aStepOfTheOpenAppWhileTheAppIsLocked_checksNothing() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val calls = rig.port.calls.size
        for (purpose in MpPurpose.entries.filter { it != MpPurpose.UNLOCK }) {
            assertEquals(purpose.name, MpResult.TooLate, rig.submit(LockRig.PASSWORD, purpose))
        }
        assertEquals("the vault port was not asked", calls, rig.port.calls.size)
        assertTrue(rig.state is LockUiState.Locked)
    }

    // ---- practice (lock.md §8.3, F10) -------------------------------------------------------------------

    @Test
    fun onAPracticeDay_notNowUsePinSnoozesItAndShowsThePad() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.advance(25 * HOUR_MS) // the first practice is due a day after the master password was set
        rig.lock()

        val field = rig.field
        assertEquals(MpReason.PracticeDue, field.reason)
        assertEquals("within the grace: \"Not now: use PIN\"", Practice(notNow = Shortcut.PIN), field.practice)

        rig.send(LockIntent.UsePin)

        assertTrue(rig.screen is LockScreen.Pad)
        assertEquals("snoozed for 20 hours (lock.md §8.3)", rig.clock.wallMs() + 20 * HOUR_MS, rig.stateRecord().practice.snoozedUntilMs)
        rig.away()
        rig.toFront()
        assertTrue("asked at most once a day: the next visit opens on the pad", rig.screen is LockScreen.Pad)
    }

    @Test
    fun onAPracticeDayWithFingerprintOnly_notNowLeadsToTheFingerprintScreenAndItsPrompt() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUp()
        rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
        rig.advance(25 * HOUR_MS)
        rig.lock()
        assertEquals(Practice(notNow = Shortcut.FINGERPRINT), rig.field.practice)
        assertEquals("no prompt opens by itself on a practice screen (lock.md §8.3)", 0, rig.window.shows.count { it.use == PromptUse.UNLOCK })

        rig.send(LockIntent.NotNow) // "Not now: use fingerprint"

        assertTrue(rig.screen is LockScreen.FingerprintOnly)
        assertEquals("then the prompt opens as on any other visit", 1, rig.window.shows.count { it.use == PromptUse.UNLOCK })
    }

    @Test
    fun pastTheGraceTheMasterPasswordIsRequired_andTypingItMovesTheScheduleOn() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.advance(49 * HOUR_MS) // one day, and its one day of grace
        rig.lock()

        val field = rig.field
        assertEquals(MpReason.PracticeDue, field.reason)
        assertEquals("no \"Not now\" any more", Practice(notNow = null), field.practice)
        assertFalse(field.canUsePin)
        rig.send(LockIntent.UsePin)
        rig.send(LockIntent.NotNow)
        assertTrue("neither button exists, and neither intent leads past the screen", rig.screen is LockScreen.MasterPassword)

        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        val practice = rig.stateRecord().practice
        assertEquals(1, practice.step)
        assertEquals(rig.clock.wallMs(), practice.lastOkMs)
        assertNull("the standing banner is gone with it (lock.md §13.4 item 6)", rig.status.practiceOverdueSinceMs)
    }

    @Test
    fun practiceThatIsOverdueInTheOpenApp_isInTheStatus() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        val typedAt = rig.clock.wallMs()
        rig.advance(49 * HOUR_MS)
        rig.away()
        rig.toFront() // the app was open all along; the status is brought up to date at the return
        assertEquals("\"You have not typed your master password since …\"", typedAt, rig.status.practiceOverdueSinceMs)
    }

    @Test
    fun iCantRememberItGoesToTheFlowOfAForgottenMasterPassword() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.advance(49 * HOUR_MS)
        rig.lock()
        assertEquals(Practice(notNow = null), rig.field.practice)

        rig.send(LockIntent.CantRemember)

        if (rig.reached44("F10 step 4")) return@runTest // PasswordFlows.cantRemember is package 4.4's: the intent reached it
        // lock.md F10 step 4, F11 step 3: practice is skipped for this visit, and the PIN is typed as on any visit.
        assertTrue(rig.screen is LockScreen.Pad)
        rig.typePin()
        assertTrue(rig.isUnlocked)
    }

    // ---- an older master password (lock.md F16b) --------------------------------------------------------

    @Test
    fun aMasterPasswordFromBeforeAChange_showsItsScreen_andItsAnswerGoesToTheEngine() = runTest {
        val rig = LockRig(this)
        val vault = rig.port.seed(VAULT, "the old password".toByteArray())
        rig.advance(DAY_MS)
        vault.rekey(LockRig.PASSWORD.toByteArray()) // the master password was changed in an earlier process
        rig.startInFront()

        val result = rig.unlockWith("the old password")

        val older = result as MpResult.OlderPassword
        assertFalse("the change was proven: only \"OK\"", older.unfinishedChange)
        val screen = rig.screen as LockScreen.OlderPassword
        assertEquals(older.changedAtMs, screen.changedAtMs)
        assertFalse(screen.unfinishedChange)
        assertNull("nothing was opened", rig.port.openVaultId)

        rig.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK))

        if (rig.reached44("F16b")) return@runTest // OlderCopies.answer is package 4.4's: the intent reached it
        assertTrue("\"OK\": back to the field", rig.screen is LockScreen.MasterPassword)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
    }

    // ---- the checks of the open app (lock.md §6.8, §8.2) --------------------------------------------------

    @Test
    fun aCheckInTheOpenAppGivesAProofOfItsOwn_neverTheOneOfTheUnlock() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val unlock = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof
        assertSame(unlock, rig.unlocked.proof)

        val practice = (rig.submit(LockRig.PASSWORD, MpPurpose.PRACTICE) as MpResult.Right).proof
        val backup = (rig.submit(LockRig.PASSWORD, MpPurpose.BACKUP) as MpResult.Right).proof

        assertNotSame("the practice prompt drops what it gets: it must not be the unlock's proof", unlock, practice)
        assertNotSame(practice, backup)
        practice.drop()
        assertEquals("the unlock's proof still lends its bytes", LockRig.PASSWORD, unlock.useBytes { String(it, Charsets.UTF_8) })
        assertEquals(LockRig.PASSWORD, backup.useBytes { String(it, Charsets.UTF_8) })
        assertSame("and the state still holds the unlock's", unlock, rig.unlocked.proof)
    }

    @Test
    fun aCheckInTheOpenAppIsCountedWhenWrong_andOpensOrChangesNothing() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.typePin()
        assertNull(rig.unlocked.proof)

        for (purpose in MpPurpose.entries.filter { it != MpPurpose.UNLOCK }) {
            assertEquals(purpose.name, MpResult.Wrong, rig.submit("not the password", purpose))
            assertTrue(purpose.name, rig.submit(LockRig.PASSWORD, purpose) is MpResult.Right)
        }
        assertNull("a proof asked for in the open app is the asker's: the state keeps none", rig.unlocked.proof)
        assertTrue(rig.isUnlocked)
        assertEquals("only setup wrote the vault", 1, rig.port.writingCalls.size)
    }

    @Test
    fun aCheckInTheOpenAppThatIsRunningWhenTheAppLocks_provesNothing() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        val running = holdPort(rig, "checkPassword")
        val check = backgroundScope.asyncOn { rig.manager.submitMasterPassword(LockRig.PASSWORD.toCharArray(), MpPurpose.EXPORT) }
        rig.settle()

        val lock = backgroundScope.asyncOn { rig.manager.lock(LockReason.NO_TOUCH) }
        running.complete(Unit)
        rig.settle()

        assertEquals("its prompt ended with the lock (I11)", MpResult.TooLate, check.getCompleted())
        assertTrue(lock.isCompleted)
        assertTrue(rig.state is LockUiState.Locked)
    }

    // ---- an automatic lock drops the proof (lock.md §5.8, §10.4) --------------------------------------------

    @Test
    fun anAutomaticLockDropsEveryProof_whateverItsReason() = runTest {
        for (reason in LockReason.entries) {
            val rig = LockRig(this, seed = 10L + reason.ordinal)
            lockedOnTheField(rig)
            val ofTheUnlock = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof
            val ofAPrompt = (rig.submit(LockRig.PASSWORD, MpPurpose.FINGERPRINT_ON) as MpResult.Right).proof
            assertSame(ofTheUnlock, rig.unlocked.proof)

            rig.lock(reason)

            assertTrue(reason.name, rig.state is LockUiState.Locked)
            assertNull("$reason: the vault's session is dropped", rig.port.openVaultId)
            assertThrows("$reason: the unlock's proof is zeroed", IllegalStateException::class.java) { ofTheUnlock.useBytes { } }
            assertThrows("$reason: and so is a prompt's", IllegalStateException::class.java) { ofAPrompt.useBytes { } }
            assertEquals("$reason: a dropped proof sets no PIN", PinSetResult.NotSet, rig.manager.setPin(LockRig.digits("368014"), ofTheUnlock))
            assertEquals(emptyList<Throwable>(), rig.crashes)
        }
    }

    @Test
    fun leavingTheAppDropsTheProofsButDoesNotLock() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val proof = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof

        rig.away() // ON_STOP: AutoLock decides about the lock, not the lock itself (lock.md §10.2)

        val unlocked = rig.unlocked
        assertNull("the state no longer offers the proof", unlocked.proof)
        assertThrows(IllegalStateException::class.java) { proof.useBytes { } }
        assertEquals(rig.vaultId, rig.port.openVaultId)
    }

    // ---- after every correct master password (architecture.md D-9b) ----------------------------------------

    @Test
    fun everyCorrectMasterPasswordIsComparedWithTheAccounts_beforeItsProofIsHandedBack() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)

        val unlock = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof
        assertEquals("the unlock: once", 1, rig.vault.matched.size)
        assertSame(unlock, rig.vault.matched[0].proof)
        assertTrue(rig.vault.matched[0].bytesLent)

        // The practice prompt drops its proof the moment it has it: the comparison ran before that.
        val result = rig.manager.submitMasterPassword(LockRig.PASSWORD.toCharArray(), MpPurpose.PRACTICE)
        (result as MpResult.Right).proof.drop()
        rig.settle()
        assertEquals(2, rig.vault.matched.size)
        assertSame(result.proof, rig.vault.matched[1].proof)
        assertTrue("it still lent its bytes when the repository asked", rig.vault.matched[1].bytesLent)

        assertEquals(MpResult.Wrong, rig.submit("not the password", MpPurpose.EXPORT))
        rig.send(LockIntent.NoticeAnswered(rig.notices.first().id, NoticeAnswer.LATER)) // the state changes, the proof does not
        assertEquals("nothing is compared twice, and nothing for a wrong password", 2, rig.vault.matched.size)
    }

    @Test
    fun aCheckOfTheOpenAppWhoseScreenIsGoneBeforeItGetsItsProof_hasThatProofDropped() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.typePin() // the app is open, and no proof is in the state
        rig.vault.matched.clear()
        val comparing = CompletableDeferred<Unit>()
        rig.vault.matchRuns = comparing // the comparison of D-9b is still saving when the screen goes

        val backup = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            rig.manager.submitMasterPassword(LockRig.PASSWORD.toCharArray(), MpPurpose.BACKUP)
        }
        rig.settle()
        val proof = rig.vault.matched.single().proof
        assertTrue("D-9b had its bytes", rig.vault.matched.single().bytesLent)
        assertFalse("the proof waits for the comparison", backup.isCompleted)

        backup.cancel() // Back on the backup screen: its ViewModel is cleared, and nobody will receive the proof
        rig.settle()
        comparing.complete(Unit)
        rig.settle()

        assertTrue(rig.isUnlocked)
        assertThrows(
            "lock.md §5.8: a proof is dropped when its flow ends, not only at ON_STOP or a lock",
            IllegalStateException::class.java,
        ) { proof.useBytes { } }
    }

    @Test
    fun setupAndARestoreAreComparedToo_andAFailureThereChangesNoResult() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUp()
        assertEquals(1, rig.vault.matched.size)
        assertSame(proof, rig.vault.matched[0].proof)

        rig.vault.matchFails = IOException("the vault could not be saved")
        assertTrue("the list of matches is the repository's business", rig.submit(LockRig.PASSWORD, MpPurpose.BACKUP) is MpResult.Right)
        assertEquals(2, rig.vault.matched.size)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    // ---- "Make a backup now?" (lock.md F12 step 2, §13.4 item 7) ---------------------------------------------

    private fun accountChangedAt(ms: Long) = Entry(id = ENTRY, title = "mail", createdAt = ms, updatedAt = ms)

    @Test
    fun afterAMasterPasswordOnTheLockScreen_aBackupThatIsDueIsOffered_last() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        rig.vault.show(Vault(entries = listOf(accountChangedAt(rig.clock.wallMs()))))

        val proof = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof

        assertTrue("\"Make a backup now?\" comes last (lock.md §13.4 item 7)", rig.notices.last() is Notice.BackupNow)
        assertEquals(1, rig.notices.count { it is Notice.BackupNow })

        rig.answerNotices(NoticeAnswer.LATER)
        assertNull("\"Later\": the entry is not kept (F12 step 2)", rig.unlocked.proof)
        assertThrows(IllegalStateException::class.java) { proof.useBytes { } }
    }

    @Test
    fun backUpNowKeepsTheEntryForTheBackup() = runTest {
        val rig = LockRig(this)
        lockedOnTheField(rig)
        rig.vault.show(Vault(entries = listOf(accountChangedAt(rig.clock.wallMs()))))
        val proof = (rig.unlockWith(LockRig.PASSWORD) as MpResult.Right).proof
        while (rig.notices.first() !is Notice.BackupNow) rig.send(LockIntent.NoticeAnswered(rig.notices.first().id, NoticeAnswer.LATER))

        rig.send(LockIntent.NoticeAnswered(rig.notices.first().id, NoticeAnswer.BACK_UP_NOW))

        assertSame(proof, rig.unlocked.proof)
        assertEquals(LockRig.PASSWORD, proof.useBytes { String(it, Charsets.UTF_8) })
    }

    @Test
    fun noBackupIsOfferedWhenNoneIsDue_whenRemindersAreOffOrPutOff_orAfterAPin() = runTest {
        suspend fun offered(seed: Long, byPin: Boolean = false, prepare: (LockRig) -> Unit): Boolean {
            val rig = LockRig(this, seed)
            lockedOnTheField(rig)
            prepare(rig)
            if (byPin) {
                rig.send(LockIntent.UsePin)
                rig.typePin()
            } else {
                assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
            }
            return rig.notices.any { it is Notice.BackupNow }
        }
        val changed: (LockRig) -> Vault = { rig -> Vault(entries = listOf(accountChangedAt(rig.clock.wallMs()))) }

        assertTrue("the control: a change that is in no backup", offered(31) { it.vault.show(changed(it)) })
        assertFalse("no account yet", offered(32) { it.vault.show(Vault()) })
        assertFalse("the newest backup holds every change (architecture.md §4.3)", offered(33) { rig ->
            rig.vault.show(changed(rig).copy(backups = listOf(FileRef("content://backups/1", "PasswordVault-backup.pvault", rig.clock.wallMs()))))
        })
        assertTrue("an account changed after the newest backup", offered(34) { rig ->
            rig.vault.show(changed(rig).copy(backups = listOf(FileRef("content://backups/1", "PasswordVault-backup.pvault", rig.clock.wallMs() - 1))))
        })
        assertTrue("an account deleted after the newest backup", offered(35) { rig ->
            val now = rig.clock.wallMs()
            rig.vault.show(Vault(entries = listOf(accountChangedAt(now - 10).copy(deletedAt = now)), backups = listOf(FileRef("content://b", "b.pvault", now - 5))))
        })
        assertFalse("\"Backup reminders\" is off", offered(36) { rig ->
            rig.vault.show(changed(rig))
            rig.baseSettings.backupReminder.value = BackupReminder(on = false)
        })
        assertFalse("\"Later\" put the reminder off", offered(37) { rig ->
            rig.vault.show(changed(rig))
            rig.baseSettings.backupReminder.value = BackupReminder(on = true, snoozedUntilMs = rig.clock.wallMs() + 1)
        })
        assertFalse("a PIN is no master-password entry", offered(38, byPin = true) { it.vault.show(changed(it)) })
    }

    /** Locks, lets [wallMs] pass on the wall clock, and types the master password on the lock screen again: whether the offer came. */
    private suspend fun offeredAgainAfter(rig: LockRig, wallMs: Long): Boolean {
        rig.lock()
        rig.clock.jumpWall(wallMs)
        rig.send(LockIntent.UseMasterPassword)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        return rig.notices.any { it is Notice.BackupNow }
    }

    @Test
    fun laterOnTheOffer_putsTheOfferAndTheBannerOffFor3Days_asTheBannersLaterDoes() = runTest {
        // build-plan.md §12, decision 15; lock.md F12 step 2.
        val rig = LockRig(this)
        lockedOnTheField(rig)
        rig.vault.show(Vault(entries = listOf(accountChangedAt(rig.clock.wallMs()))))
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        assertTrue(rig.notices.last() is Notice.BackupNow)
        val answeredAt = rig.clock.wallMs()

        rig.answerNotices(NoticeAnswer.LATER)

        assertEquals(
            "the snooze of the banner's \"Later\" (ux.md §5.11), which the banner reads too",
            answeredAt + 3 * DAY_MS,
            rig.baseSettings.backupReminder.value.snoozedUntilMs,
        )
        assertFalse("two days later", offeredAgainAfter(rig, 2 * DAY_MS))
        assertTrue("three days after \"Later\"", offeredAgainAfter(rig, DAY_MS))
    }

    @Test
    fun theOfferComesAtMostOnceADay_answeredOrNot_inTheNextProcessToo() = runTest {
        // build-plan.md §12, decision 15: a phone without a PIN types the master password at every unlock.
        val rig = LockRig(this)
        lockedOnTheField(rig)
        val changed = Vault(entries = listOf(accountChangedAt(rig.clock.wallMs())))
        rig.vault.show(changed)
        val first = rig.clock.wallMs()
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        assertTrue(rig.notices.any { it is Notice.BackupNow })
        assertEquals("the time of the offer is kept in the settings", first, rig.baseSettings.backupReminder.value.offeredAtMs)
        assertNull("not answered: nothing is snoozed", rig.baseSettings.backupReminder.value.snoozedUntilMs)

        assertFalse("an hour later, the offer left unanswered", offeredAgainAfter(rig, HOUR_MS))
        rig.restart()
        rig.toFront()
        rig.vault.show(changed)
        rig.send(LockIntent.UseMasterPassword)
        rig.clock.jumpWall(22 * HOUR_MS)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
        assertFalse("a new process, 23 hours after the offer", rig.notices.any { it is Notice.BackupNow })

        assertTrue("a day after the offer", offeredAgainAfter(rig, HOUR_MS))
        assertEquals(first + DAY_MS, rig.baseSettings.backupReminder.value.offeredAtMs)
        assertFalse("and not again the same day", offeredAgainAfter(rig, HOUR_MS))
        assertTrue("a wall clock set back to before the last offer does not hold the next one back", offeredAgainAfter(rig, -2 * DAY_MS))
    }

    private companion object {
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val ENTRY = "00000000-0000-4000-8000-0000000000e1"
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
