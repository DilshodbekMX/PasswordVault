package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.NewMasterPasswordAsked
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeStage
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterTags
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordAfterUnlock
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewStage
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.5, [device]: the two routes drawn on a device over the fake lock, used through their
 * controls as a person does (ux.md §5.18, §5.24): a change with the PIN and its new backup, "I don't
 * remember it", the list of saved backups with "Delete", the asking flow of a new master password
 * with and without the pad, Back on its explanation, "Finish now", and the route opened once after the
 * lock-screen route.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var rig: MasterRig? = null

    @After
    fun tearDown() {
        rig?.close()
        NewMasterPasswordAsked.take()
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun changeScreen(made: MasterRig, over: LockManager = made.lock): ChangeMasterPasswordViewModel {
        rig = made
        lateinit var vm: ChangeMasterPasswordViewModel
        compose.setContent {
            SettingsTestTheme {
                vm = viewModel { made.change(over) }
                ChangeMasterPasswordScreen(vm, made.nav)
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun newScreen(made: MasterRig): NewMasterPasswordViewModel {
        rig = made
        lateinit var vm: NewMasterPasswordViewModel
        compose.setContent {
            SettingsTestTheme {
                vm = viewModel { made.newOne() }
                NewMasterPasswordScreen(vm, made.nav)
            }
        }
        compose.waitForIdle()
        return vm
    }

    /** Setup's three steps with the suggested words, through their buttons. */
    private fun chooseTheWords(words: () -> String) {
        compose.awaitThat { compose.onAllNodesWithTagCount(SetupTags.USE_WORDS) > 0 }
        compose.onNodeWithTag(SetupTags.USE_WORDS).performScrollTo().performClick()
        compose.onNodeWithTag(SetupTags.NEXT).performScrollTo().performClick()
        compose.onNodeWithTag(SetupTags.AGAIN_FIELD).performTextInput(words())
        compose.onNodeWithTag(SetupTags.CREATE).performScrollTo().performClick()
    }

    private fun typePin(pin: String) = pin.forEach { compose.onNodeWithTag(PinPadTags.key(it - '0')).performScrollTo().performClick() }

    // ---- Change master password ------------------------------------------------------------------------

    @Test
    fun aChangeWithThePin_endsOnTheNewBackup_whichOpensWithoutAskingAgain() {
        val made = MasterRig(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
        val vm = changeScreen(made)
        compose.onNodeWithText(text(R.string.master_current_heading)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("correct horse battery staple")
        compose.onNodeWithTag(MasterTags.CURRENT_NEXT).performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.CHOOSE }
        assertEquals(listOf(MpPurpose.CHANGE_MASTER_PASSWORD), made.lock.purposes)

        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.PIN }
        compose.onNodeWithText(text(R.string.master_keep_pin_title)).assertIsDisplayed()
        typePin("582913")
        compose.awaitThat { vm.ui.value.stage == ChangeStage.BACKUP }

        compose.onNodeWithText(text(R.string.master_changed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.master_backup_text)).assertIsDisplayed()
        compose.runOnUiThread { made.lock.state.value = LockUiState.Unlocked(emptyList(), made.lock.proof()) }
        compose.onNodeWithTag(MasterTags.BACK_UP_NOW).performScrollTo().performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("replace Backup"), made.nav.steps.toList())
        assertEquals("the backup takes the new master password's proof", 1, made.offered.size)
    }

    @Test
    fun iDontRememberIt_opensTheNewMasterPassword() {
        val made = MasterRig()
        changeScreen(made)
        compose.onNodeWithTag(MasterTags.DONT_REMEMBER).performScrollTo().performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("go NewMasterPassword"), made.nav.steps.toList())
    }

    @Test
    fun anOldMasterPasswordUsedForAnAccount_listsTheSavedBackups_andDeletesOne() {
        val made = MasterRig(pin = PinStatus.NotSet, vault = MasterRig.USED_ELSEWHERE)
        val vm = changeScreen(made)
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("correct horse battery staple")
        compose.onNodeWithTag(MasterTags.CURRENT_NEXT).performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.CHOOSE }
        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.BACKUP }

        compose.onNodeWithText(text(R.string.master_places_heading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.master_places_also)).performScrollTo().assertIsDisplayed()
        made.deleteAnswers += io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted.NOT_DELETABLE
        compose.onNodeWithTag(MasterTags.deletePlace(0)).performScrollTo().performClick()
        compose.awaitThat { compose.onAllNodesWithTagCount("${MasterTags.place(0)}.line") > 0 }
        compose.onNodeWithText(text(R.string.master_place_not_deletable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.deletePlace(1)).performScrollTo().performClick()
        compose.awaitThat { compose.onAllNodesWithTagCount(MasterTags.deletePlace(1)) == 0 }
        compose.onNodeWithText(text(R.string.master_place_deleted)).performScrollTo().assertIsDisplayed()
        assertEquals("newest first", MasterRig.BACKUPS.reversed(), made.deleted.toList())
    }

    @Test
    fun aChangeThatCouldNotBeMade_saysSo_andOkCloses() {
        val made = MasterRig(pin = PinStatus.NotSet)
        made.lock.changeResults += ChangeResult.NothingChanged
        val vm = changeScreen(made)
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("correct horse battery staple")
        compose.onNodeWithTag(MasterTags.CURRENT_NEXT).performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.CHOOSE }
        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.NOT_CHANGED }
        compose.onNodeWithText(text(R.string.master_not_changed)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.OK).performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("back"), made.nav.steps.toList())
    }

    @Test
    fun withoutAUsablePin_savingShowsNoPinStep() {
        // No PIN, fingerprint on: from "Type it again" straight to "Saving…", with the prompt of F8 step 7.
        val made = MasterRig(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On)
        val held = HeldLock(made.lock).apply { holdChange = true }
        val vm = changeScreen(made, held)
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("correct horse battery staple")
        compose.onNodeWithTag(MasterTags.CURRENT_NEXT).performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.CHOOSE }
        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.SAVING }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.master_saving)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.master_touch_sensor)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.master_keep_pin_title), useUnmergedTree = true)
            .assertCountEquals(0) // ux.md §5.18 step 3: "Enter your app PIN to keep using it." only when a PIN is usable
        compose.onAllNodesWithTag(PinPadTags.DOTS, useUnmergedTree = true).assertCountEquals(0) // no PIN was typed: no six dots
        compose.runOnUiThread { held.openChange() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.BACKUP }
    }

    @Test
    fun notNowOnThePin_savingShowsNoPinTyped() {
        val made = MasterRig(pin = PinStatus.On, fingerprint = FingerprintStatus.Off)
        val held = HeldLock(made.lock).apply { holdChange = true }
        val vm = changeScreen(made, held)
        compose.onNodeWithTag(MasterTags.CURRENT_FIELD).performTextInput("correct horse battery staple")
        compose.onNodeWithTag(MasterTags.CURRENT_NEXT).performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.CHOOSE }
        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.PIN }
        compose.onNodeWithTag(MasterTags.PIN_NOT_NOW).performScrollTo().performClick()
        compose.awaitThat { vm.ui.value.stage == ChangeStage.SAVING }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.master_saving)).assertIsDisplayed()
        compose.onAllNodesWithTag(PinPadTags.DOTS, useUnmergedTree = true).assertCountEquals(0) // "Not now": no six dots as if the PIN had been typed
        compose.onAllNodesWithText(text(R.string.master_keep_pin_title), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnUiThread { held.openChange() }
        compose.awaitThat { vm.ui.value.stage == ChangeStage.BACKUP }
    }

    // ---- a new master password -------------------------------------------------------------------------

    @Test
    fun theAskingFlow_withThePinTypedNow_endsOnTheWait() {
        val made = MasterRig(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
        made.lock.proofResults += ProofResult.NeedsPin
        made.lock.proofResults += ProofResult.Proven(Shortcut.PIN)
        val vm = newScreen(made)
        compose.onNodeWithText(text(R.string.master_explain_pin)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.CONTINUE).performScrollTo().performClick()
        compose.awaitThat { vm.ui.value.stage == NewStage.PIN }
        compose.onNodeWithText(text(R.string.master_proof_pin_title)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.FORGOT_PIN_TOO).performScrollTo().assertIsDisplayed()
        typePin("582913")
        compose.awaitThat { vm.ui.value.stage == NewStage.CHOOSE }
        assertEquals(listOf(false, true), made.lock.proofPins.toList())

        chooseTheWords { vm.choice.state.value.chosen!!.text() }
        compose.awaitThat { vm.ui.value.stage == NewStage.WAIT }
        compose.onNodeWithText(text(R.string.master_wait_text)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.DONE).performScrollTo().performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("back"), made.nav.steps.toList())
        assertEquals("a request was made: nothing to leave", 0, made.lock.newMasterPasswordLeft)
    }

    @Test
    fun withoutAPin_theExplanationNamesTheFingerprint() {
        val made = MasterRig(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On)
        newScreen(made)
        compose.onNodeWithText(text(R.string.master_explain_fingerprint)).assertIsDisplayed()
    }

    @Test
    fun backOnTheExplanation_closesTheRoute_andTellsTheLock() {
        val made = MasterRig()
        newScreen(made)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("back"), made.nav.steps.toList())
        assertEquals("the proof of step 3, if any, goes with the flow", 1, made.lock.newMasterPasswordLeft)
    }

    @Test
    fun finishNow_typedOnce_offersTheBackup() {
        val made = MasterRig(pending = MasterRig.READY)
        val vm = newScreen(made)
        compose.onNodeWithText(text(R.string.master_finish_heading)).assertIsDisplayed()
        made.lock.finishResults += FinishResult.Wrong
        compose.onNodeWithTag(MasterTags.FINISH_FIELD).performTextInput("not it")
        compose.onNodeWithTag(MasterTags.FINISH).performScrollTo().performClick()
        compose.awaitThat { vm.ui.value.line != null }
        compose.onNodeWithText(text(R.string.master_wrong)).assertIsDisplayed()

        compose.onNodeWithTag(MasterTags.FINISH_FIELD).performTextInput("the new one")
        compose.onNodeWithTag(MasterTags.FINISH).performScrollTo().performClick()
        compose.awaitThat { vm.ui.value.stage == NewStage.FINISHED }
        compose.onNodeWithText(text(R.string.master_finished)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.BACK_UP_NOW).performScrollTo().performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("replace Backup"), made.nav.steps.toList())
        assertEquals("no PIN and no fingerprint was asked for", 0, made.lock.proofPins.size)
    }

    @Test
    fun aWaitOnFinishNow_turnsTheFieldOff() {
        val made = MasterRig(pending = MasterRig.READY)
        made.lock.finishResults += FinishResult.Wait(untilElapsedMs = android.os.SystemClock.elapsedRealtime() + 30_000L)
        val vm = newScreen(made)
        compose.onNodeWithTag(MasterTags.FINISH_FIELD).performTextInput("typed")
        compose.onNodeWithTag(MasterTags.FINISH).performScrollTo().performClick()
        compose.awaitThat { vm.ui.value.waitSeconds != null }
        compose.onNodeWithTag(MasterTags.COUNTDOWN).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.FINISH).assertIsNotEnabled()
    }

    @Test
    fun laterOnFinishNow_closesTheRoute_andTellsTheLock() {
        // The route opens on its finishing step also after the lock-screen route ("Use my PIN" on the sheet, then the
        // PIN): the proof that entry made goes with the route when it closes (lock.md F11 step 3; build-plan.md §12).
        val made = MasterRig(pending = MasterRig.READY)
        newScreen(made)
        compose.onNodeWithText(text(R.string.master_finish_heading)).assertIsDisplayed()
        compose.onNodeWithTag(MasterTags.LATER).performScrollTo().performClick()
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        assertEquals(listOf("back"), made.nav.steps.toList())
        assertEquals("the proof of step 3, if any, goes with the flow", 1, made.lock.newMasterPasswordLeft)
    }

    @Test
    fun afterTheLockScreenRoute_theRouteOpensOnce() {
        val made = MasterRig().also { rig = it }
        NewMasterPasswordAsked.arm()
        compose.setContent { NewMasterPasswordAfterUnlock(made.nav) }
        compose.awaitThat { made.nav.steps.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("go NewMasterPassword"), made.nav.steps.toList())
        assertTrue("taken once", !NewMasterPasswordAsked.take())
    }

    @Test
    fun withoutTheLockScreenRoute_nothingOpens() {
        val made = MasterRig().also { rig = it }
        compose.setContent { NewMasterPasswordAfterUnlock(made.nav) }
        compose.waitForIdle()
        assertTrue(made.nav.steps.isEmpty())
    }
}

/** How many nodes carry [tag] (in the unmerged tree). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
