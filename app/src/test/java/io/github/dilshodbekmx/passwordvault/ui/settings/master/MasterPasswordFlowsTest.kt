package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.Wordlist
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockRig
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.PIN
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.NewMasterPasswordAsked
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.lock.asyncOn
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.list.ListBanner
import io.github.dilshodbekmx.passwordvault.ui.list.ListBanners
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeStage
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordChoice
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewStage
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsNotice
import io.github.dilshodbekmx.passwordvault.ui.settings.digitsOf
import io.github.dilshodbekmx.passwordvault.ui.settings.master.MasterData.WORD_INDICES
import io.github.dilshodbekmx.passwordvault.ui.settings.type
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 5.5, [JVM]: F8 and F11 through `LockManager` with fake ports (lock.md F8, F11, F16b;
 * ux.md §5.18, §5.24): the screens' ViewModels over the real `DefaultLockManager` and lock engine
 * of [LockRig] (the lock records, the Keystore, the vaults and the clocks are `:testing`'s fakes).
 *
 * - **F8**: the PIN step (a wrong PIN counted and asked again; the right one keeps the PIN; "Not now"
 *   leaves "Choose a PIN"), the fingerprint step ("Keep fingerprint unlock" touched keeps it; "Not
 *   now" leaves "Fingerprint unlock: turn on again"), and afterwards the new master password, the
 *   PIN and the fingerprint open the vault.
 * - **F11**: the PIN typed in the open app, and the fingerprint where there is no PIN; the
 *   lock-screen route ("I can't remember it", then the PIN), whose proof the screen takes, or drops
 *   when it closes without a request; the banner and its "Cancel it"; "Finish now" after 24 credited
 *   hours in the open app, with the new backup offered on the new master password's proof; and
 *   after it "It wasn't me" and "Open my passwords from before …" (F16b).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterPasswordFlowsTest {
    private val store = ViewModelStore()
    private val offered = mutableListOf<MasterProof>()
    private val random = FakeRandom(seed = 9L)
    private var models = 0

    @Before
    fun clearHolders() {
        NewMasterPasswordAsked.take()
        SettingsNotice.take()
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        NewMasterPasswordAsked.take()
        SettingsNotice.take()
    }

    /** The screens' main thread is the test's scheduler, as the rig's lock is. */
    private fun TestScope.rig(seed: Long = 1L): LockRig {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return LockRig(this, seed)
    }

    private fun TestScope.change(rig: LockRig): ChangeMasterPasswordViewModel {
        val factory = viewModelFactory {
            initializer {
                ChangeMasterPasswordViewModel(
                    lock = rig.manager,
                    repository = rig.vault,
                    clock = rig.clock,
                    random = random,
                    dispatchers = AppDispatchers.single(StandardTestDispatcher(testScheduler)),
                    work = backgroundScope,
                    offerBackup = { offered += it },
                    deleteBackup = { NoteDeleted.DELETED },
                )
            }
        }
        return ViewModelProvider(store, factory)["change-${models++}", ChangeMasterPasswordViewModel::class.java].also { rig.settle() }
    }

    private fun TestScope.newOne(rig: LockRig): NewMasterPasswordViewModel {
        val factory = viewModelFactory {
            initializer {
                NewMasterPasswordViewModel(
                    lock = rig.manager,
                    clock = rig.clock,
                    random = random,
                    dispatchers = AppDispatchers.single(StandardTestDispatcher(testScheduler)),
                    work = backgroundScope,
                    offerBackup = { offered += it },
                )
            }
        }
        return ViewModelProvider(store, factory)["new-${models++}", NewMasterPasswordViewModel::class.java].also { rig.settle() }
    }

    /** The suggested words chosen, written down and typed again on setup's three screens; returns them. */
    private fun choose(rig: LockRig, choice: NewMasterPasswordChoice, chosen: () -> Unit, wrote: () -> Unit, again: () -> Unit): String {
        random.queueInts(*WORD_INDICES)
        choice.showOtherWords()
        rig.settle()
        assertTrue(choice.useTheseWords())
        chosen()
        wrote()
        val words = WORD_INDICES.map { Wordlist.effShort[it] }.joinToString("-")
        choice.again.type(words)
        again()
        rig.settle()
        return words
    }

    private fun digits(rig: LockRig, pin: String, digit: (Int) -> Unit) {
        digitsOf(pin).forEach(digit)
        rig.settle()
    }

    /** The phone as setup left it with a PIN and, with [fingerprint], fingerprint unlock on; its notices answered. */
    private suspend fun phone(rig: LockRig, fingerprint: Boolean) {
        val proof = rig.setUpWithPin()
        if (fingerprint) rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
    }

    // =================================================================================================
    // F8: change the master password
    // =================================================================================================

    /** Change master password up to the PIN step: the current master password, then the words. */
    private fun upToThePin(rig: LockRig, vm: ChangeMasterPasswordViewModel): String {
        vm.current.field.type(PASSWORD)
        vm.submitCurrent()
        rig.settle()
        assertEquals("the current master password was right", ChangeStage.CHOOSE, vm.ui.value.stage)
        return choose(rig, vm.choice, vm::chosen, vm::wroteItDown, vm::submitAgain)
    }

    @Test
    fun f8_thePinStepAndTheFingerprintStep_keepBoth_andTheNewMasterPasswordOpensTheVault() = runTest {
        val rig = rig()
        phone(rig, fingerprint = true)
        val vault = rig.vaultId
        val vm = change(rig)
        val words = upToThePin(rig, vm)
        assertEquals("a PIN is usable: \"Enter your app PIN to keep using it\"", ChangeStage.PIN, vm.ui.value.stage)

        // The PIN step: a wrong PIN is counted, and nothing changes yet.
        digits(rig, WRONG_PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        assertEquals(UiText.plural(R.plurals.master_pin_wrong, 4), vm.ui.value.line)
        assertNull("nothing changed yet", rig.stateRecord().mpChange)

        // The right PIN: the change runs (F8 steps 4 to 8), then the fingerprint step (step 7).
        digits(rig, PIN, vm::digit)
        assertEquals(ChangeStage.SAVING, vm.ui.value.stage)
        assertTrue("\"Touch the sensor to keep fingerprint unlock on.\"", vm.ui.value.fingerprintOn)
        rig.advance(LockRig.CHARGE_AGE_MS) // the PIN's charge ages (lock.md §6.3)
        rig.advance(LockRig.CHARGE_AGE_MS) // the mark of step 5 settles before the vault is written
        assertEquals("\"Keep fingerprint unlock\"", PromptUse.KEEP_AFTER_CHANGE, rig.prompt.use)
        rig.touch()

        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertFalse("the PIN was kept", vm.ui.value.choosePin)
        assertEquals(PinStatus.On, rig.status.pin)
        assertEquals(FingerprintStatus.On, rig.status.fingerprint)
        assertEquals(vault, rig.vaultId)
        assertEquals(UiText.of(R.string.master_changed), SettingsNotice.take())

        // F8 step 9: "Back up now" uses the new master password just typed.
        vm.backUpNow()
        assertEquals(words, offered.single().useBytes { String(it, Charsets.UTF_8) })

        // Afterwards the fingerprint, the PIN and the new master password open the vault; the old one does not.
        rig.lock()
        assertEquals("the prompt opens by itself over the pad", PromptUse.UNLOCK, rig.prompt.use)
        rig.touch()
        assertTrue("the fingerprint kept works with the new key", rig.isUnlocked)
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.promptError(13) // "Use PIN"
        rig.typePin()
        assertTrue("the PIN kept works with the new key", rig.isUnlocked)
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.promptError(13)
        rig.send(LockIntent.UseMasterPassword)
        assertTrue("the old master password opens nothing", rig.unlockWith(PASSWORD) !is MpResult.Right)
        if (rig.screen is LockScreen.OlderPassword) rig.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK))
        assertTrue(rig.unlockWith(words) is MpResult.Right)
    }

    @Test
    fun f8_notNowOnThePin_andOnTheFingerprint_leavesChooseAPin_andTurnOnAgain() = runTest {
        val rig = rig()
        phone(rig, fingerprint = true)
        val vm = change(rig)
        val words = upToThePin(rig, vm)
        vm.notNow()
        rig.settle()
        rig.advance(LockRig.CHARGE_AGE_MS) // the mark of step 5 settles
        assertEquals(PromptUse.KEEP_AFTER_CHANGE, rig.prompt.use)
        rig.promptError(13) // "Not now" on the prompt

        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        assertTrue("lock.md F8 step 3: \"Choose a PIN\" follows", vm.ui.value.choosePin)
        assertEquals(PinStatus.NotSet, rig.status.pin)
        assertEquals("ux.md §5.18: \"Fingerprint unlock: turn on again\"", FingerprintStatus.TurnOnAgain, rig.status.fingerprint)
        val proof = assertNotNullAnd((rig.state as? LockUiState.Unlocked)?.proof)
        assertEquals("4.3's PIN steps take the new master password's proof", words, proof.useBytes { String(it, Charsets.UTF_8) })
    }

    @Test
    fun f8_theAppLeftWhileTheNewOneIsChosen_theCurrentMasterPasswordIsAskedAgain_andTheNewOneKept() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        val vm = change(rig)
        val words = upToThePin(rig, vm)
        rig.away() // ON_STOP drops every proof (lock.md §5.8); the lock delay has not passed
        rig.toFront()
        assertTrue(rig.isUnlocked)
        digits(rig, PIN, vm::digit)
        assertEquals(ChangeStage.CURRENT, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_current_again), vm.ui.value.line)
        assertNull("nothing changed", rig.stateRecord().mpChange)

        vm.current.field.type(PASSWORD)
        vm.submitCurrent()
        rig.settle()
        assertEquals(ChangeStage.PIN, vm.ui.value.stage)
        digits(rig, PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertEquals(ChangeStage.BACKUP, vm.ui.value.stage)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        assertTrue("the words chosen before the app was left are the new master password", rig.unlockWith(words) is MpResult.Right)
    }

    @Test
    fun f8_aChangeThatEndsAfterTheAppLocked_isStillSaidInSettings() = runTest {
        val rig = rig()
        phone(rig, fingerprint = true)
        val vm = change(rig)
        val words = upToThePin(rig, vm)
        digits(rig, PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS) // the PIN's charge ages (lock.md §6.3)
        rig.advance(LockRig.CHARGE_AGE_MS) // the mark of step 5 settles; the new copy is written and proven (step 6)
        assertEquals("\"Touch the sensor to keep fingerprint unlock on.\"", PromptUse.KEEP_AFTER_CHANGE, rig.prompt.use)

        rig.lock(LockReason.SCREEN_OFF) // the screen times out while the prompt of step 7 waits for a finger
        assertFalse(rig.isUnlocked)
        assertEquals("the lock ended the flow on the screen", ChangeStage.CURRENT, vm.ui.value.stage)
        assertEquals(
            "the change was made (F8: from step 6 on it runs to its end), so Settings says so after the unlock",
            UiText.of(R.string.master_changed),
            SettingsNotice.take(),
        )
        rig.send(LockIntent.UseMasterPassword)
        assertTrue("the new master password is the vault's", rig.unlockWith(words) is MpResult.Right)
    }

    // =================================================================================================
    // F11: a new master password while the old one is forgotten
    // =================================================================================================

    /** The asking flow from the explanation on, with the proof given by [prove]; ends on the wait. */
    private fun ask(rig: LockRig, vm: NewMasterPasswordViewModel, prove: () -> Unit): String {
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        prove()
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
        val words = choose(rig, vm.choice, vm::chosen, vm::wroteItDown, vm::submitAgain)
        assertEquals(NewStage.WAIT, vm.ui.value.stage)
        return words
    }

    @Test
    fun f11_thePinTypedInTheOpenApp_isTheProof_andTheBannerCancelsTheRequest() = runTest {
        val rig = rig()
        phone(rig, fingerprint = true)
        val vm = newOne(rig)
        assertEquals("the explanation names the PIN", Shortcut.PIN, vm.ui.value.by)
        ask(rig, vm) {
            vm.proveWithoutPin()
            rig.settle()
            assertEquals("an app that is open already is not enough: the PIN", NewStage.PIN, vm.ui.value.stage)
            assertTrue("\"Forgot your PIN as well? …\"", vm.ui.value.fingerprintOn)
            digits(rig, PIN, vm::digit)
            rig.advance(LockRig.CHARGE_AGE_MS)
        }
        val pending = assertNotNullAnd(rig.status.pending)
        assertEquals(Shortcut.PIN, pending.by)
        assertFalse(pending.ready)

        // The list's banner (ux.md §5.11), and its "Cancel it" (F11 step 8): nothing is needed to cancel.
        val banner = ListBanners.lockBanner(rig.status)
        assertTrue("$banner", banner is ListBanner.PendingMasterPassword)
        assertEquals(24, (banner as ListBanner.PendingMasterPassword).hoursLeft)
        rig.send(LockIntent.CancelPending)
        assertNull(rig.status.pending)
        assertNull(rig.stateRecord().pendingMp)
        assertNull("the banner is gone", ListBanners.lockBanner(rig.status))
    }

    @Test
    fun f11_withoutAPin_theFingerprintConfirms() = runTest {
        val rig = rig()
        val proof = rig.setUp()
        rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
        val vm = newOne(rig)
        assertEquals("the explanation names the fingerprint", Shortcut.FINGERPRINT, vm.ui.value.by)
        ask(rig, vm) {
            vm.proveWithoutPin()
            rig.settle()
            assertTrue(vm.ui.value.fingerprintPrompt)
            assertEquals("\"Confirm with your fingerprint\"", PromptUse.CONFIRM_REPLACEMENT, rig.prompt.use)
            rig.touch()
        }
        assertEquals(Shortcut.FINGERPRINT, rig.status.pending?.by)
    }

    @Test
    fun f11_afterTheLockScreenRoute_theScreenOpensAndTakesThatProof() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember) // "Use my PIN" on the "Forgot master password?" sheet
        rig.typePin() // the PIN opens the app, and is the proof of step 3
        assertTrue(rig.isUnlocked)
        assertTrue("the open app continues with F11 (NewMasterPasswordAfterUnlock)", NewMasterPasswordAsked.take())
        rig.answerNotices(NoticeAnswer.LATER)

        val vm = newOne(rig)
        val words = ask(rig, vm) {
            vm.proveWithoutPin()
            rig.settle()
        }
        assertNotNull(rig.status.pending)
        assertTrue(words.isNotEmpty())
    }

    @Test
    fun f11_theLockScreenRoute_isMarkedOnlyWhenAShortcutOpensTheAppOnThatVisit() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)

        // An unlock without "I can't remember it": nothing follows.
        rig.lock()
        rig.typePin()
        assertFalse(NewMasterPasswordAsked.take())
        rig.answerNotices(NoticeAnswer.LATER)

        // "I can't remember it", then the master password after all: nothing is forgotten.
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.send(LockIntent.UseMasterPassword)
        assertTrue(rig.unlockWith(PASSWORD) is MpResult.Right)
        assertFalse("a master-password unlock is no lock-screen route", NewMasterPasswordAsked.take())
        rig.answerNotices(NoticeAnswer.LATER)

        // "I can't remember it", then the app is left before the PIN: that visit is over.
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.away()
        rig.toFront()
        rig.typePin()
        assertTrue(rig.isUnlocked)
        assertFalse("a new visit, opened by the PIN as any visit", NewMasterPasswordAsked.take())
        rig.answerNotices(NoticeAnswer.LATER)

        // The route itself: armed at the unlock, taken once.
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.typePin()
        assertTrue(NewMasterPasswordAsked.take())
        assertFalse("once", NewMasterPasswordAsked.take())
    }

    @Test
    fun f11_theLockScreenRoutesMark_goesWithALock_andWithTheAppLeft() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.typePin()
        rig.away() // nothing took the mark before the app was left
        rig.toFront()
        assertFalse(NewMasterPasswordAsked.take())

        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.typePin()
        rig.lock()
        assertFalse(NewMasterPasswordAsked.take())
    }

    @Test
    fun f11_theLockScreenRoutesProof_goesWithItsFlow_whenTheScreenClosesWithoutARequest() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.typePin()
        assertTrue(NewMasterPasswordAsked.take())
        rig.answerNotices(NoticeAnswer.LATER)

        val vm = newOne(rig)
        vm.close() // "Cancel" on the explanation
        rig.settle()
        rig.advance(HOUR_MS) // an hour later, in the same session: no lock, no ON_STOP

        assertEquals("no request without the PIN again (build-plan.md §12 item 13)", RequestResult.NotAllowed, request(rig))
        assertEquals(ProofResult.NeedsPin, prove(rig))
    }

    @Test
    fun f11_backFromChoosing_andTheRouteClosedOtherwise_dropTheProofOfTheOpenApp() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        val vm = newOne(rig)
        vm.proveWithoutPin()
        rig.settle()
        digits(rig, PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertEquals(NewStage.CHOOSE, vm.ui.value.stage)
        vm.back() // Back from choosing
        rig.settle()
        assertEquals(NewStage.EXPLAIN, vm.ui.value.stage)
        assertEquals(RequestResult.NotAllowed, request(rig))

        // Proved again, then the route leaves the back stack while the password is chosen.
        val again = newOne(rig)
        again.proveWithoutPin()
        rig.settle()
        digits(rig, PIN, again::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertEquals(NewStage.CHOOSE, again.ui.value.stage)
        store.clear()
        rig.settle()
        assertEquals(RequestResult.NotAllowed, request(rig))
        assertNull(rig.status.pending)
    }

    @Test
    fun f11_theAppLeftWhileThePasswordIsChosen_thePinIsAskedAgain_andTheRequestFollows() = runTest {
        val rig = rig()
        phone(rig, fingerprint = false)
        val vm = newOne(rig)
        vm.proveWithoutPin()
        rig.settle()
        digits(rig, PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        random.queueInts(*WORD_INDICES)
        vm.choice.showOtherWords()
        rig.settle()
        assertTrue(vm.choice.useTheseWords())
        vm.chosen()
        vm.wroteItDown()
        rig.away() // ON_STOP drops the proof (lock.md §5.8); the lock delay has not passed
        rig.toFront()
        assertTrue("not locked", rig.isUnlocked)
        val words = WORD_INDICES.map { Wordlist.effShort[it] }.joinToString("-")
        vm.choice.again.type(words)
        vm.submitAgain()
        rig.settle()
        assertEquals("the PIN again, the password kept", NewStage.PIN, vm.ui.value.stage)
        assertEquals(UiText.of(R.string.master_proof_again), vm.ui.value.line)
        digits(rig, PIN, vm::digit)
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertEquals(NewStage.WAIT, vm.ui.value.stage)
        assertNotNull(rig.status.pending)
    }

    // ---- "Finish now" (decision 14), and F16b afterwards -------------------------------------------------

    /**
     * A new master password asked for on its screen with the PIN, its 24 hours credited, in a new
     * process opened with the PIN, its notices answered: the banner says it is ready.
     */
    private suspend fun TestScope.readyToFinish(rig: LockRig): String {
        phone(rig, fingerprint = false)
        val vm = newOne(rig)
        val words = ask(rig, vm) {
            vm.proveWithoutPin()
            rig.settle()
            digits(rig, PIN, vm::digit)
            rig.advance(LockRig.CHARGE_AGE_MS)
        }
        store.clear()
        val state = rig.stateRecord()
        val p = state.pendingMp!!
        rig.putState(state.copy(pendingMp = StateRecord.PendingMp(p.requestedMs, p.by, DAY_MS, p.seenElapsedMs, p.vkId, p.salt, p.nonce, p.ct)))
        rig.restart()
        rig.toFront()
        rig.typePin()
        assertTrue(rig.isUnlocked)
        rig.answerNotices(NoticeAnswer.LATER)
        assertTrue("\"Your new master password is ready.\"", assertNotNullAnd(rig.status.pending).ready)
        assertEquals(ListBanner.PendingReady, ListBanners.lockBanner(rig.status))
        return words
    }

    /** "Finish now", the new master password typed once on its screen. */
    private fun TestScope.finish(rig: LockRig, password: String): NewMasterPasswordViewModel {
        val vm = newOne(rig)
        assertEquals("the screen shows the finishing step while the new master password is ready", NewStage.FINISH, vm.ui.value.stage)
        vm.finishField.type(password)
        vm.submitFinish()
        rig.settle()
        rig.advance(LockRig.CHARGE_AGE_MS) // the mark of step 15 settles before storage's step
        return vm
    }

    @Test
    fun f11_finishNow_after24CreditedHours_finishesInTheOpenApp_andOffersTheBackup() = runTest {
        val rig = rig()
        val words = readyToFinish(rig)
        val oldVault = rig.vaultId

        val vm = finish(rig, words)

        assertEquals(NewStage.FINISHED, vm.ui.value.stage)
        assertEquals("nothing locked", 0, rig.lockNows)
        assertTrue(rig.isUnlocked)
        assertNotEquals("the app goes on in the new vault", oldVault, rig.vaultId)
        assertNull(rig.status.pending)
        val choosePin = rig.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals("F11 step 19: \"Choose a PIN\" through 4.2's notices", ChoosePinReason.NONE_HERE, choosePin.reason)
        vm.backUpNow()
        assertEquals("the backup uses the new master password", words, offered.single().useBytes { String(it, Charsets.UTF_8) })

        // The new master password opens the new vault.
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        assertTrue(rig.unlockWith(words) is MpResult.Right)
    }

    @Test
    fun f11_finishNow_withTheCurrentMasterPassword_cancelsTheRequest() = runTest {
        val rig = rig()
        readyToFinish(rig)
        val vault = rig.vaultId
        val vm = finish(rig, PASSWORD)
        assertNotNull("the route closes", vm.ui.value.leave)
        assertEquals(vault, rig.vaultId)
        assertNull(rig.status.pending)
        assertTrue("\"You typed your master password, so the new one was cancelled.\"", rig.notices.any { it is Notice.PendingCancelled })
    }

    @Test
    fun f16b_itWasntMe_opensThePasswordsFromBefore_withTheOldMasterPassword() = runTest {
        val rig = rig()
        val words = readyToFinish(rig)
        val oldVault = rig.vaultId
        finish(rig, words)
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        assertTrue(rig.unlockWith(words) is MpResult.Right)

        // Every unlock of the new vault shows the replacement (lock.md §13.4 item 3), the notice and the banner.
        assertTrue(rig.notices.any { it is Notice.Replaced })
        assertEquals(oldVault, rig.status.replaced?.oldVaultId)
        assertTrue(ListBanners.lockBanner(rig.status) is ListBanner.Replaced)

        // "It wasn't me" on the banner: the app locks, and the old vault's own lock screen takes only its master password.
        rig.send(LockIntent.ItWasntMe)
        assertEquals("through the session, as Lock now", 1, rig.lockNows)
        val screen = rig.screen as LockScreen.MasterPassword
        assertTrue("\"Your passwords from before …\"", screen.aside is AsideLine.FromBefore)
        assertFalse("no PIN on a vault from before a replacement", screen.canUsePin)
        rig.unlockWith(PASSWORD)
        assertTrue("\"Open the older set instead?\"", rig.screen is LockScreen.OpenOlderSet)
        rig.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_OLDER_SET))
        assertTrue(rig.isUnlocked)
        assertEquals("the passwords from before are current again", oldVault, rig.vaultId)
        assertTrue(
            "\"Choose a new PIN. Someone else may know the old one.\"",
            rig.notices.filterIsInstance<Notice.ChoosePin>().any { it.reason == ChoosePinReason.SOMEONE_MAY_KNOW },
        )
    }

    @Test
    fun f16b_openMyPasswordsFromBefore_underAWrongMasterPassword_andCancelChangesNothing() = runTest {
        val rig = rig()
        val words = readyToFinish(rig)
        val oldVault = rig.vaultId
        finish(rig, words)
        val newVault = rig.vaultId
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)

        rig.unlockWith("not the new one")
        val error = rig.field.error as MpError.Wrong
        val replaced = assertNotNullAnd(error.replaced)
        assertEquals(oldVault, replaced.oldVaultId)

        // "Open my passwords from before 7 Oct" (4.2's line): the old vault's own lock screen.
        rig.send(LockIntent.OpenAside(oldVault))
        assertTrue((rig.screen as LockScreen.MasterPassword).aside is AsideLine.FromBefore)
        rig.unlockWith(PASSWORD)
        assertTrue(rig.screen is LockScreen.OpenOlderSet)
        rig.send(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.KEEP_NEWER_SET))
        assertFalse("Cancel changes nothing", rig.isUnlocked)
        if ((rig.screen as? LockScreen.MasterPassword)?.aside != null) rig.send(LockIntent.GoBack)
        assertTrue(rig.unlockWith(words) is MpResult.Right)
        assertEquals(newVault, rig.vaultId)
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private suspend fun request(rig: LockRig): RequestResult {
        val result = rig.manager.requestNewMasterPassword(OTHER.toCharArray(), OTHER.toCharArray())
        rig.settle()
        return result
    }

    private fun prove(rig: LockRig): ProofResult {
        val proving = rig.test.backgroundScope.asyncOn { rig.manager.proveForNewMasterPassword(null) }
        rig.settle()
        return proving.getCompleted()
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val OTHER = "Buxoro kechasi 2026 boshqa kalit"
    }
}
