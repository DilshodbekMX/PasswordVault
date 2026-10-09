package io.github.dilshodbekmx.passwordvault.ui.security.change

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.edit.GenTags
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeScreen
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeTags
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeUiState
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeViewModel
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.FACEBOOK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.SHARED
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.facebook
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeRig.Companion.sharing
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 7.2, [device]: the guided change as a person and TalkBack meet it (ux.md §5.17), over
 * the fakes: the five steps and their buttons, a new password saved at once, both passwords copied,
 * the answers, the end with "Next" and the backup line, the generator sheet of "Password rules", and
 * no password anywhere in what an accessibility service can read.
 */
@RunWith(AndroidJUnit4::class)
class GuidedChangeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var rig: ChangeRig
    private lateinit var vm: GuidedChangeViewModel

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.close()
    }

    private fun show(vault: Vault, id: String = GMAIL) {
        rig = ChangeRig(vault)
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { rig.viewModel(id) }
                GuidedChangeScreen(vm, rig.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value !is GuidedChangeUiState.Loading }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** Taps the node [tag], scrolled into view first: a short screen (API 24 and 28) shows only the first steps. */
    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    /** No password of the vault is in any node, merged or not. */
    private fun assertNoPasswordReadable(where: String) {
        val readable = compose.everythingReadable()
        val passwords = rig.repository.stored.entries.flatMap { e -> listOf(e.password) + e.passwordHistory.map { it.password } + listOfNotNull(e.pendingOldPassword) }
        for (password in passwords.filter { it.isNotEmpty() }.toSet() + SHARED) {
            assertFalse("a password is readable $where", readable.contains(password))
        }
    }

    @Test
    fun theStepsAsTheyOpen() {
        show(sharing())
        compose.onNodeWithTag(GuidedChangeTags.ACCOUNT).assertTextEquals("Gmail")
        for (step in 1..GuidedChangeViewModel.STEPS) compose.onNodeWithTag(GuidedChangeTags.step(step)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.change_step1, "Gmail")).assertExists()
        compose.onNodeWithText(text(R.string.change_step_number, 1, 5)).assertExists()
        compose.onNodeWithTag(GuidedChangeTags.OPEN_WEBSITE).assertExists()
        compose.onNodeWithTag(GuidedChangeTags.COPY_OLD_FIRST).assertIsEnabled()
        compose.onNodeWithTag(GuidedChangeTags.CREATE).assertIsEnabled()
        compose.onNodeWithTag(GuidedChangeTags.LAST_ONE).assertDoesNotExist()
        // Steps 4 and 5 wait for a new password, and say so.
        compose.onNodeWithTag(GuidedChangeTags.COPY_NEW).assertIsNotEnabled()
        compose.onNodeWithTag(GuidedChangeTags.YES).assertIsNotEnabled()
        compose.onNodeWithTag(GuidedChangeTags.NO).assertIsNotEnabled()
        compose.onNodeWithTag(GuidedChangeTags.LATER).assertIsNotEnabled()
        compose.onNodeWithTag(GuidedChangeTags.COPY_OLD).assertIsEnabled()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.change_first_step3))).fetchSemanticsNodes().size == 2)
        // Each step's title is a heading for TalkBack, with its number.
        val heading = compose.onNodeWithText(text(R.string.change_step3), useUnmergedTree = false).fetchSemanticsNode()
        assertTrue(heading.config.getOrNull(SemanticsProperties.Heading) != null)
        assertNoPasswordReadable("when the screen opens")
        assertEquals(0, rig.repository.edits)
    }

    @Test
    fun createNewPasswordSavesItAndTheStepsMoveOn() {
        show(sharing())
        tap(GuidedChangeTags.CREATE)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        compose.onNodeWithTag(GuidedChangeTags.SAVED).assertTextEquals(text(R.string.change_saved))
        compose.onNodeWithTag(GuidedChangeTags.CREATE).assertDoesNotExist()
        // No snackbar over steps 4 and 5, which come next; the first password needs no "saved" flash.
        compose.onNodeWithTag(GuidedChangeTags.CREATED).assertDoesNotExist()
        val first = rig.entry()
        assertTrue(first.changePending)
        assertEquals(SHARED, first.pendingOldPassword)

        // Step 4: both passwords, each copied with the sensitive flag.
        tap(GuidedChangeTags.COPY_OLD)
        tap(GuidedChangeTags.COPY_NEW)
        val (old, new) = rig.clipboard.copies
        assertEquals(SHARED, old.value)
        assertEquals(first.password, new.value)
        assertTrue(old.sensitive && new.sensitive)
        // The button says "Copied" to TalkBack for a moment.
        val copied = compose.onNodeWithTag(GuidedChangeTags.COPY_NEW).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf(text(R.string.change_copied)), copied)
        compose.onNodeWithTag(GuidedChangeTags.YES).assertIsEnabled()
        assertNoPasswordReadable("after Create new password")

        // "Create another password": another one, the website's still kept, and "New password saved."
        // for a moment, since nothing else on the screen changes.
        tap(GuidedChangeTags.CREATE_ANOTHER)
        compose.waitUntil(TIMEOUT_MS) { rig.entry().password != first.password }
        assertEquals(SHARED, rig.entry().pendingOldPassword)
        waitFor(GuidedChangeTags.CREATED)
        compose.onNodeWithTag(GuidedChangeTags.CREATED).assertTextEquals(text(R.string.change_created))
        assertNoPasswordReadable("after Create another password")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag(GuidedChangeTags.CREATED)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun yesShowsTheEndWithNextAndTheBackupLine() {
        show(sharing())
        tap(GuidedChangeTags.CREATE)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        tap(GuidedChangeTags.YES)
        waitFor(GuidedChangeTags.DONE)
        compose.onNodeWithTag(GuidedChangeTags.DONE).assertTextEquals(text(R.string.change_done, "Gmail"))
        compose.onNodeWithTag(GuidedChangeTags.NEXT).assertTextEquals(text(R.string.change_next, "Facebook"))
        compose.onNodeWithTag(GuidedChangeTags.BACKUP_LINE).assertTextEquals(text(R.string.change_backup_only_here, "Gmail"))
        assertFalse(rig.entry().changePending)
        assertNoPasswordReadable("at the end")

        tap(GuidedChangeTags.BACK_UP_NOW)
        assertEquals(listOf<Route>(Route.Backup), rig.nav.opened)
        tap(GuidedChangeTags.NEXT)
        assertEquals(listOf<Route>(Route.GuidedChange(FACEBOOK)), rig.nav.replaced)
    }

    @Test
    fun theLastOneEndsWithDoneForNow() {
        val vault = Vault(entries = listOf(gmail(), facebook(password = "Fb-own-9!", history = listOf(HistoryItem(SHARED, System.currentTimeMillis())))))
        show(vault)
        compose.onNodeWithTag(GuidedChangeTags.LAST_ONE).assertTextEquals(text(R.string.change_last_one))
        tap(GuidedChangeTags.CREATE)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        tap(GuidedChangeTags.YES)
        waitFor(GuidedChangeTags.FINISH)
        compose.onNodeWithTag(GuidedChangeTags.NEXT).assertDoesNotExist()
        compose.onNodeWithTag(GuidedChangeTags.FINISH).assertTextEquals(text(R.string.change_done_for_now))
        tap(GuidedChangeTags.FINISH)
        assertEquals(1, rig.nav.backs)
    }

    @Test
    fun noPutsBackTheOldPassword() {
        show(sharing())
        tap(GuidedChangeTags.CREATE)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        tap(GuidedChangeTags.NO)
        waitFor(GuidedChangeTags.CREATE)
        compose.onNodeWithText(text(R.string.change_reverted)).assertExists()
        val entry = rig.entry()
        assertEquals(SHARED, entry.password)
        assertFalse(entry.changePending)
        compose.onNodeWithTag(GuidedChangeTags.YES).assertIsNotEnabled()
    }

    @Test
    fun laterAndBackLeaveTheChangeAsItIs() {
        show(sharing())
        tap(GuidedChangeTags.CREATE)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        val saves = rig.repository.saves
        tap(GuidedChangeTags.LATER)
        assertEquals(1, rig.nav.backs)
        compose.onNodeWithTag(GuidedChangeTags.BACK).performClick()
        compose.waitForIdle()
        assertEquals(2, rig.nav.backs)
        assertEquals(saves, rig.repository.saves)
        assertTrue(rig.entry().changePending)
    }

    @Test
    fun passwordRulesOpenTheGeneratorAndItsPasswordIsSaved() {
        show(sharing())
        tap(GuidedChangeTags.RULES)
        waitFor(GenTags.USE)
        compose.waitUntil(TIMEOUT_MS) { vm.generator.password() != null }
        val made = vm.generator.password()
        compose.onNodeWithTag(GenTags.USE).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.entry().changePending }
        compose.waitForIdle()
        compose.onNodeWithTag(GenTags.USE).assertDoesNotExist()
        assertEquals(made, rig.entry().password)
        assertNotEquals(SHARED, rig.entry().password)
        waitFor(GuidedChangeTags.CREATE_ANOTHER)
        assertNoPasswordReadable("after the sheet closed")
    }

    @Test
    fun anAccountThatIsGone() {
        show(Vault(entries = listOf(facebook())), id = GMAIL)
        compose.onNodeWithTag(GuidedChangeTags.GONE).assertTextEquals(text(R.string.change_gone))
        tap(GuidedChangeTags.GONE_BACK)
        assertEquals(1, rig.nav.backs)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
