package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.Wordlist
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeStage
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangedUnderLock
import io.github.dilshodbekmx.passwordvault.ui.settings.Leave
import io.github.dilshodbekmx.passwordvault.ui.settings.PlaceState
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsNotice
import io.github.dilshodbekmx.passwordvault.ui.settings.digitsOf
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.BANK
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.CURRENT
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.NEW_BACKUP
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.OLD_BACKUP
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.OWN_NEW
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.PIN
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.WORD_INDICES
import io.github.dilshodbekmx.passwordvault.ui.settings.type
import io.github.dilshodbekmx.passwordvault.ui.setup.CreateView
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 5.5, [JVM]: Settings › App lock › "Change master password" (ux.md §5.18; lock.md F8)
 * against `FakeLockManager`: the current master password first (`MpPurpose.CHANGE_MASTER_PASSWORD`)
 * and "I don't remember it"; setup's three steps, whose two typings reach the lock; the PIN step
 * (only while a PIN is usable; a wrong PIN asks again with the tries left; "Not now"); every answer
 * of `changeMasterPassword`; "Choose a PIN" after a change that did not keep the PIN; the new backup
 * with the proof of the new master password; the list of saved backups when the old master password
 * is an account's password, with "Delete"; Back; and a lock, which ends the flow.
 * `MasterPasswordFlowsTest` runs the same steps over the real lock manager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeMasterPasswordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val clock = FakeClock(elapsedMs = 5_000_000L, autoAdvance = false)
    private val random = FakeRandom(seed = 3L)
    private val offered = mutableListOf<MasterProof>()
    private val deleted = mutableListOf<FileRef>()
    private val deleteAnswers = ArrayDeque<NoteDeleted>()
    private var models = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        SettingsNotice.take()
        ChangedUnderLock.clear()
    }

    @After
    fun tearDown() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
        SettingsNotice.take()
        ChangedUnderLock.clear()
    }

    /** The open app with the PIN [pin], fingerprint [fingerprint], and a vault of [vault]. */
    private fun lockWith(pin: PinStatus = PinStatus.On, fingerprint: FingerprintStatus = FingerprintStatus.Off): MasterLock {
        val lock = MasterLock()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        lock.fake.status.value = lock.fake.status.value.copy(pin = pin, fingerprint = fingerprint)
        return lock
    }

    /** How often "Later" put the backup reminder off (decisions 15 and 21). */
    private var snoozes = 0

    private fun TestScope.model(lock: MasterLock, vault: Vault = Vault(entries = listOf(GMAIL, BANK))): ChangeMasterPasswordViewModel {
        val repository = FakeVaultRepository(vault)
        val factory = viewModelFactory {
            initializer {
                ChangeMasterPasswordViewModel(
                    lock = lock,
                    repository = repository,
                    clock = clock,
                    random = random,
                    dispatchers = AppDispatchers.single(dispatcher),
                    work = work,
                    offerBackup = { offered += it },
                    deleteBackup = { ref ->
                        deleted += ref
                        deleteAnswers.removeFirstOrNull() ?: NoteDeleted.DELETED
                    },
                    snoozeReminder = { snoozes++ },
                )
            }
        }
        val vm = ViewModelProvider(store, factory)["change-${models++}", ChangeMasterPasswordViewModel::class.java]
        advanceUntilIdle()
        return vm
    }

    /** Step 1 with the right current master password. */
    private fun TestScope.typeCurrent(vm: ChangeMasterPasswordViewModel) {
        vm.current.field.type(CURRENT)
        vm.submitCurrent()
        advanceUntilIdle()
    }

    /** Step 2 with the suggested words; returns them as typed. */
    private fun TestScope.chooseWords(vm: ChangeMasterPasswordViewModel): String {
        random.queueInts(*WORD_INDICES)
        vm.choice.showOtherWords()
        advanceUntilIdle()
        val words = WORD_INDICES.map { Wordlist.effShort[it] }.joinToString("-")
        assertTrue(vm.choice.useTheseWords())
        vm.chosen()
        assertEquals(ChangeStage.WRITE_DOWN, vm.ui.value.stage)
        vm.wroteItDown()
        assertEquals(ChangeStage.TYPE_AGAIN, vm.ui.value.stage)
        return words
    }

    /** Steps 1 and 2, the words typed again: on the PIN step, or saving when no PIN is usable. */
    private fun TestScope.upToThePin(vm: ChangeMasterPasswordViewModel): String {
        typeCurrent(vm)
        val words = chooseWords(vm)
        vm.choice.again.type(words)
        vm.submitAgain()
        return words
    }

    private fun ChangeMasterPasswordViewModel.typePin(pin: String) = digitsOf(pin).forEach(::digit)

    // ---- step 1: the current master password --------------------------------------------------------

    @Test
    fun theCurrentMasterPasswordComesFirst_andARightOneLeadsToTheChoice() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        assertEquals(ChangeStage.CURRENT, vm.ui.value.stage)

        lock.fake.mpResults += MpResult.Wrong
        vm.current.field.type("not it")
        vm.submitCurrent()
        advanceUntilIdle()
        assertEquals(ChangeStage.CURRENT, vm.ui.value.stage)
        assertEquals("the field is emptied at every try", "", vm.current.field.text.toString())
        assertEquals(PinLine(UiText.of(R.string.pin_mp_wrong)), vm.current.ui.value.line)

        typeCurrent(vm)
        assertEquals(listOf(MpPurpose.CHANGE_MASTER_PASSWORD, MpPurpose.CHANGE_MASTER_PASSWORD), lock.fake.purposes)
        assertEquals(ChangeStage.CHOOSE, vm.ui.value.stage)
        assertNotNull("the suggested words are shown", vm.choice.state.value.passphrase)
    }

    @Test
    fun aWaitOnTheCurrentMasterPassword_countsDown() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        lock.fake.mpResults += MpResult.Wait(untilElapsedMs = clock.elapsedMs() + 30_000L)
        vm.current.field.type("wrong again")
        vm.submitCurrent()
        advanceUntilIdle()
        assertEquals(30, vm.current.ui.value.secondsLeft)
        assertFalse("the field waits", vm.current.ui.value.enabled)
    }

    @Test
    fun aCheckThatEndsAfterTheRouteClosed_dropsTheProofItMade() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        val made = lock.fake.proof()
        lock.fake.mpResults += MpResult.Right(made)
        vm.current.field.type(CURRENT)
        vm.submitCurrent() // "Checking…": the check runs in the app's scope
        vm.back() // Back while it runs: the route closes
        assertEquals(Leave.BACK, vm.ui.value.leave)
        store.clear() // the route left the back stack: its ViewModel is cleared
        advanceUntilIdle() // now the check ends, with a right master password
        assertThrows(
            "lock.md §5.8: a check whose screen closed while it ran has ended its flow, and its proof goes as soon as it returns",
            IllegalStateException::class.java,
        ) { made.useBytes { } }
    }

    @Test
    fun iDontRememberIt_opensTheNewMasterPassword_overThisStep_withNothingKept() = runTest(dispatcher) {
        val vm = model(lockWith())
        vm.current.field.type("half typed")
        vm.dontRemember()
        assertEquals(Leave.NEW_MASTER_PASSWORD, vm.ui.value.leave)
        assertEquals("", vm.current.field.text.toString())
        vm.left()
        assertNull(vm.ui.value.leave)
        assertEquals("Back from there finds step 1", ChangeStage.CURRENT, vm.ui.value.stage)
    }

    // ---- step 2 and 3: the new one, the PIN ---------------------------------------------------------

    @Test
    fun withAPin_theTwoTypingsAndThePinGoToTheLock_withTheProofOfStep1() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
        val vm = model(lock)
        val words = upToThePin(vm)
        assertEquals("a PIN is usable: \"Enter your app PIN to keep using it\"", ChangeStage.PIN, vm.ui.value.stage)

        vm.typePin(PIN)
        assertEquals(ChangeStage.SAVING, vm.ui.value.stage)
        assertTrue("\"Touch the sensor to keep fingerprint unlock on.\"", vm.ui.value.fingerprintOn)
        advanceUntilIdle()

        val change = lock.changes.single()
        assertEquals(words, change.first)
        assertEquals(words, change.again)
        assertEquals(PIN, change.pin)
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertFalse("the PIN was kept: no \"Choose a PIN\"", vm.ui.value.choosePin)
        assertThrows("the proof of step 1 is dropped once the change is done", IllegalStateException::class.java) { change.proof.useBytes { } }
        assertEquals(UiText.of(R.string.master_changed), SettingsNotice.take())
    }

    @Test
    fun aWrongPin_changesNothingYet_andAsksAgainWithTheTriesLeft() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        upToThePin(vm)
        lock.fake.changeResults += ChangeResult.PinWrong(left = 3)
        vm.typePin("143637")
        advanceUntilIdle()
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        assertEquals(UiText.plural(R.plurals.master_pin_wrong, 3), vm.ui.value.line)
        assertEquals(1, vm.ui.value.shakes)

        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals("the same typings, the PIN typed again", 2, lock.changes.size)
        assertEquals(lock.changes[0].first, lock.changes[1].first)
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
    }

    @Test
    fun notNow_changesWithoutThePin_andChooseAPinFollows() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        upToThePin(vm)
        vm.notNow()
        advanceUntilIdle()
        assertNull("no PIN given", lock.changes.single().pin)
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertTrue("lock.md F8 step 3: the vault would end up without a PIN: \"Choose a PIN\"", vm.ui.value.choosePin)
        vm.pinStepsOpened()
        assertFalse("opened once", vm.ui.value.choosePin)
    }

    @Test
    fun withoutAUsablePin_thereIsNoPinStep_andNoChooseAPinAfterwards() = runTest(dispatcher) {
        for (pin in listOf(PinStatus.NotSet, PinStatus.NoSecureHardware)) {
            val lock = lockWith(pin = pin)
            val vm = model(lock)
            upToThePin(vm)
            assertEquals("$pin", ChangeStage.SAVING, vm.ui.value.stage)
            advanceUntilIdle()
            assertNull("$pin", lock.changes.single().pin)
            assertEquals("$pin", ChangeStage.BACKUP, vm.ui.value.stage)
            assertFalse("$pin: no PIN worked before", vm.ui.value.choosePin)
        }
    }

    @Test
    fun aPinThatCannotBeCheckedNow_isNotAsked_andIsChosenAgainAfterTheChange() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.CannotCheck)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        assertNull("lock.md F8 step 3: as after \"Not now\"", lock.changes.single().pin)
        assertTrue(vm.ui.value.choosePin)
    }

    @Test
    fun aMismatch_saysSo_andShowMyPasswordAgainFromTheThird() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        typeCurrent(vm)
        val words = chooseWords(vm)
        repeat(3) {
            vm.choice.again.type("$words-x")
            vm.submitAgain()
        }
        assertEquals(ChangeStage.TYPE_AGAIN, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.setup_again_mismatch), vm.choice.state.value.againMessage)
        assertTrue(vm.choice.state.value.canShowAgain)
        assertEquals("", vm.choice.again.text.toString())
        vm.showAgain()
        assertEquals(ChangeStage.WRITE_DOWN, vm.ui.value.stage)
        assertTrue(lock.changes.isEmpty())
    }

    @Test
    fun aPasswordOfOnesOwn_goesThroughTheChecklistAndTheReuseQuestion() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        typeCurrent(vm)
        vm.choice.typeOwn()
        assertEquals(CreateView.OWN, vm.choice.state.value.view)
        vm.choice.own.type("short")
        vm.choice.submitOwn()
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.setup_own_missing_length), vm.choice.state.value.ownMessage)
        vm.choice.own.type(OWN_NEW)
        vm.choice.submitOwn()
        advanceUntilIdle()
        assertTrue("the checklist passes: \"Do you use this password for any other account?\"", vm.choice.state.value.askReuse)
        assertFalse("\"Yes\" keeps the user at the field", vm.choice.answerReuse(usedElsewhere = true))
        vm.choice.submitOwn()
        advanceUntilIdle()
        assertTrue(vm.choice.answerReuse(usedElsewhere = false))
        vm.chosen()
        vm.wroteItDown()
        vm.choice.again.type(OWN_NEW)
        vm.submitAgain()
        advanceUntilIdle()
        assertEquals(OWN_NEW, lock.changes.single().first)
        assertEquals(OWN_NEW, lock.changes.single().again)
    }

    @Test
    fun aProofThatWentMeanwhile_asksForTheCurrentMasterPasswordAgain_andKeepsTheNewOne() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.On)
        val vm = model(lock)
        val stepOne = lock.fake.proof()
        lock.fake.mpResults += MpResult.Right(stepOne)
        val words = upToThePin(vm)
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        stepOne.drop() // the app stopped being visible: the lock drops every proof it made (lock.md §5.8)
        lock.fake.purposes.clear()

        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(ChangeStage.CURRENT, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_current_again), vm.ui.value.line)
        assertTrue("nothing was tried with a proof that went", lock.changes.isEmpty())

        typeCurrent(vm)
        assertEquals(listOf(MpPurpose.CHANGE_MASTER_PASSWORD), lock.fake.purposes)
        assertEquals("the PIN again, then the change", ChangeStage.PIN, vm.ui.value.stage)
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertEquals("the new master password chosen before is the one changed to", words, lock.changes.single().first)
    }

    // ---- what the change came to --------------------------------------------------------------------

    @Test
    fun eachAnswerOfTheChange_saysWhatItShould() = runTest(dispatcher) {
        val cases = listOf(
            ChangeResult.NothingChanged to ChangeStage.NOT_CHANGED,
            ChangeResult.Unclear to ChangeStage.UNCLEAR,
            ChangeResult.NoMemory to ChangeStage.NO_MEMORY,
            ChangeResult.Mismatch to ChangeStage.TYPE_AGAIN,
            ChangeResult.Unassigned(0x0378) to ChangeStage.CHOOSE,
        )
        for ((result, stage) in cases) {
            val lock = lockWith(pin = PinStatus.NotSet)
            val vm = model(lock)
            lock.fake.changeResults += result
            upToThePin(vm)
            advanceUntilIdle()
            assertEquals("$result", stage, vm.ui.value.stage)
            if (result == ChangeResult.Unassigned(0x0378)) assertEquals(UiText.of(R.string.setup_own_unassigned), vm.choice.state.value.ownMessage)
            if (result == ChangeResult.Mismatch) assertEquals(UiText.of(R.string.setup_again_mismatch), vm.choice.state.value.againMessage)
            assertNull("$result: nothing changed, so nothing is said in Settings", SettingsNotice.take())
        }
    }

    @Test
    fun tooLittleMemory_tryAgain_asksForThePinAgainWhenOneIsUsable() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.On)
        val vm = model(lock)
        upToThePin(vm)
        lock.fake.changeResults += ChangeResult.NoMemory
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(ChangeStage.NO_MEMORY, vm.ui.value.stage)
        vm.retry()
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        vm.typePin(PIN)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertEquals(2, lock.changes.size)
    }

    @Test
    fun nothingChanged_ok_closesTheRoute() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        lock.fake.changeResults += ChangeResult.NothingChanged
        upToThePin(vm)
        advanceUntilIdle()
        assertThrows("the proof of step 1 went with the flow", IllegalStateException::class.java) { lock.changes.single().proof.useBytes { } }
        vm.close()
        assertEquals(Leave.BACK, vm.ui.value.leave)
    }

    // ---- the new backup (F8 step 9) -----------------------------------------------------------------

    @Test
    fun backUpNow_handsTheNewMasterPasswordsProofToTheBackup() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        val newProof = lock.fake.proof()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), newProof) // as the lock leaves it after the change
        vm.backUpNow()
        assertSame("the backup screen does not ask for the master password again", newProof, offered.single())
        assertEquals(Leave.BACKUP, vm.ui.value.leave)
    }

    @Test
    fun later_closesTheRoute_withoutABackup() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        vm.close()
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertTrue(offered.isEmpty())
    }

    @Test
    fun anOldMasterPasswordUsedForAnAccount_listsTheSavedBackups_withDelete() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vault = Vault(entries = listOf(GMAIL, BANK), backups = listOf(OLD_BACKUP, NEW_BACKUP), masterPasswordMatches = listOf(GMAIL.id))
        val vm = model(lock, vault)
        upToThePin(vm)
        advanceUntilIdle()
        assertTrue(vm.ui.value.usedElsewhere)
        assertEquals("newest first", listOf(NEW_BACKUP, OLD_BACKUP), vm.ui.value.places.map { it.ref })
        assertTrue(vm.ui.value.places.all { it.state == PlaceState.LISTED })

        deleteAnswers += NoteDeleted.NOT_DELETABLE
        vm.deletePlace(NEW_BACKUP)
        advanceUntilIdle()
        vm.deletePlace(OLD_BACKUP)
        advanceUntilIdle()
        assertEquals(listOf(NEW_BACKUP, OLD_BACKUP), deleted)
        assertEquals(listOf(PlaceState.NOT_DELETABLE, PlaceState.DELETED), vm.ui.value.places.map { it.state })
        vm.deletePlace(OLD_BACKUP)
        advanceUntilIdle()
        assertEquals("a deleted one is not deleted twice", 2, deleted.size)
    }

    @Test
    fun anOldMasterPasswordUsedForAnAccount_withoutSavedBackups_stillSaysSo() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock, Vault(entries = listOf(GMAIL), masterPasswordMatches = listOf(GMAIL.id)))
        upToThePin(vm)
        advanceUntilIdle()
        assertTrue("\"Also delete copies you sent to Telegram or by email.\" stands", vm.ui.value.usedElsewhere)
        assertTrue(vm.ui.value.places.isEmpty())
    }

    @Test
    fun anOldMasterPasswordUsedNowhereElse_listsNoBackups() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock, Vault(entries = listOf(BANK), backups = listOf(OLD_BACKUP)))
        upToThePin(vm)
        advanceUntilIdle()
        assertFalse(vm.ui.value.usedElsewhere)
        assertTrue(vm.ui.value.places.isEmpty())
    }

    // ---- Back and the lock --------------------------------------------------------------------------

    @Test
    fun back_goesStepByStep_andClosesFromTheFirstTwo() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        upToThePin(vm)
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        vm.back()
        assertEquals(ChangeStage.TYPE_AGAIN, vm.ui.value.stage)
        vm.back()
        assertEquals(ChangeStage.WRITE_DOWN, vm.ui.value.stage)
        vm.back()
        assertEquals(ChangeStage.CHOOSE, vm.ui.value.stage)
        assertNull("the chosen password went", vm.choice.state.value.chosen)
        assertNotNull("the words stay for another try", vm.choice.state.value.passphrase)
        vm.back()
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertTrue(lock.changes.isEmpty())
    }

    @Test
    fun backIsIgnoredWhileTheChangeRuns() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        assertEquals(ChangeStage.SAVING, vm.ui.value.stage)
        vm.back()
        vm.close()
        assertNull(vm.ui.value.leave)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
    }

    /**
     * Decision 21 (build-plan.md §12) when the change ended a moment before the lock reached the
     * route (the screen already off): the backup step, not answered yet, is kept for the unlock and
     * is not replaced by step 1; its "Later" puts the reminder off. A step answered before the lock
     * ("Back up now" already leaving) is not kept.
     */
    @Test
    fun aBackupStepNotAnsweredWhenTheAppLocks_isShownAgainAfterTheUnlock() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertFalse(vm.ui.value.afterLock)
        lock.fake.lock(LockReason.SCREEN_OFF)
        advanceUntilIdle()
        assertEquals("nothing is shown under the lock", ChangeStage.CURRENT, vm.ui.value.stage)
        assertEquals("the step waits for the unlock", lock.fake.vaultId, ChangedUnderLock.step.value?.vaultId)
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        advanceUntilIdle()
        assertEquals("shown after the unlock", ChangeStage.BACKUP, vm.ui.value.stage)
        assertTrue(vm.ui.value.afterLock)
        assertFalse(vm.ui.value.choosePin)
        assertTrue("the lock's own backup offer stays away while the step shows (build plan 8.1)", ChangedUnderLock.coversNow())
        vm.close()
        assertEquals(1, snoozes)
        assertEquals(Leave.BACK, vm.ui.value.leave)
        assertEquals("its answer, for the lock's offer", ChangedUnderLock.Answer.LATER, ChangedUnderLock.answer.value)
        assertFalse(ChangedUnderLock.coversNow())
    }

    /** Build plan 8.1: "Back up now" on the step shown after the unlock is the answer the lock's own offer gets too. */
    @Test
    fun backUpNowOnTheStepShownAfterTheUnlock_isTheLocksOfferAnswerToo() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        lock.fake.lock(LockReason.SCREEN_OFF)
        store.clear()
        advanceUntilIdle()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        val next = model(lock)
        assertTrue(next.ui.value.afterLock)
        assertTrue(ChangedUnderLock.coversNow())
        next.backUpNow()
        assertEquals(Leave.BACKUP, next.ui.value.leave)
        assertEquals(ChangedUnderLock.Answer.BACK_UP_NOW, ChangedUnderLock.answer.value)
        assertFalse(ChangedUnderLock.coversNow())
    }

    /** Build plan 8.1: the step of a vault put aside since is dropped and hides nothing of the lock's. */
    @Test
    fun aStepOfAVaultPutAsideIsDropped_andCoversNothing() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        ChangedUnderLock.leave("00000000-0000-4000-8000-0000000000aa", usedElsewhere = false)
        lock.fake.status.value = lock.fake.status.value.copy(aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", 1_000L, null)))
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        assertEquals(ChangeStage.CURRENT, model(lock).ui.value.stage)
        assertNull(ChangedUnderLock.step.value)
        assertFalse(ChangedUnderLock.coversNow())
    }

    /**
     * The same as a phone runs it: the lock clears the route's ViewModel (architecture.md §5.5 step 5),
     * and the route gets a new one after the unlock, which shows the step (decision 21; the device check
     * of R4). A vault put aside meanwhile drops it: the step was another vault's.
     */
    @Test
    fun aBackupStepNotAnsweredWhenTheAppLocks_isShownByTheRoutesNextViewModel_onThatVaultOnly() = runTest(dispatcher) {
        val lock = lockWith(pin = PinStatus.NotSet)
        val vm = model(lock)
        upToThePin(vm)
        advanceUntilIdle()
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        lock.fake.lock(LockReason.SCREEN_OFF)
        store.clear() // the lock screen replaces the unlocked screens
        advanceUntilIdle()
        lock.fake.state.value = LockUiState.Unlocked(emptyList(), null)
        val next = model(lock)
        assertEquals(ChangeStage.BACKUP, next.ui.value.stage)
        assertTrue(next.ui.value.afterLock)
        assertNull("taken once", ChangedUnderLock.step.value)
        assertEquals("a second ViewModel of the route starts at step 1", ChangeStage.CURRENT, model(lock).ui.value.stage)

        // The step of a vault that has been put aside since is not shown on the vault that is open now.
        ChangedUnderLock.leave("00000000-0000-4000-8000-0000000000aa", usedElsewhere = false)
        lock.fake.status.value = lock.fake.status.value.copy(aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", 1_000L, null)))
        assertEquals(ChangeStage.CURRENT, model(lock).ui.value.stage)
        assertNull("dropped", ChangedUnderLock.step.value)
    }

    @Test
    fun aLockEndsTheFlow_andDropsWhatWasTypedAndProved() = runTest(dispatcher) {
        val lock = lockWith()
        val vm = model(lock)
        typeCurrent(vm)
        chooseWords(vm)
        vm.choice.again.type("half")
        lock.fake.lock(LockReason.SCREEN_OFF)
        advanceUntilIdle()
        assertEquals(ChangeStage.CURRENT, vm.ui.value.stage)
        assertNull(vm.choice.state.value.chosen)
        assertEquals("", vm.choice.again.text.toString())
        assertTrue(lock.changes.isEmpty())
    }
}
