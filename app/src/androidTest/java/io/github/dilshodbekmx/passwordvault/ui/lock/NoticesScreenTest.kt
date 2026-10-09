package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.2, [device]: the notices after unlock (lock.md §13.4; ux.md §5.9) as drawn over an
 * unlocked app, from `FakeLockManager`: a notice shows its texts and its answers, one at a time; an
 * answer goes to the lock and opens its screen; Back gives the answer that changes nothing; "Choose a
 * PIN" with the proof of the unlock opens "Choose a new PIN" without a dialog and is answered once
 * the PIN screens are left; "Type master password" checks in a prompt whose field reports only mask
 * characters.
 */
@RunWith(AndroidJUnit4::class)
class NoticesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources: Resources get() = compose.activity.resources

    /** A [Navigator] that records what was opened; the unlocked stack takes every route. */
    private class Recorder : Navigator {
        val opened = CopyOnWriteArrayList<Route>()

        override fun go(route: Route): Boolean = opened.add(route)

        override fun back(): Boolean = false

        override fun replace(route: Route): Boolean = false

        override fun tab(root: Route): Boolean = false
    }

    @Composable
    private fun Host(lock: LockManager, nav: Navigator) {
        WindowPolicy.Root {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm = viewModel { NoticesViewModel(lock, AndroidClock, SCOPE) }
                    NoticesHost(vm, nav)
                }
            }
        }
    }

    private fun show(vararg notices: Notice, nav: Navigator = Recorder()): FakeLockManager {
        val lock = FakeLockManager(initial = LockUiState.Unlocked(notices.toList(), null))
        compose.setContent { Host(lock, nav) }
        compose.waitForIdle()
        return lock
    }

    @Test
    fun aNoticeShowsItsTextsAndEachAnswerGoesToTheLockAndOpensItsScreen() {
        LockShots.touchMode()
        val nav = Recorder()
        val lock = show(
            Notice.WrongTries(1, wrongPins = 3, lastWrongPinAtMs = null, interrupted = true, pinsBeforeOff = null, offerNewMasterPassword = false, wrongMasterPasswords = 0),
            Notice.BackupNow(2),
            nav = nav,
        )
        compose.onNodeWithTag(NoticeTags.NOTICE).assertExists()
        compose.onNodeWithTag(NoticeTags.line(0)).assertTextEquals(resources.getQuantityString(R.plurals.lock_notice_wrong_pins, 3, 3))
        compose.onNodeWithTag(NoticeTags.line(1)).assertTextEquals(resources.getString(R.string.lock_notice_interrupted))
        compose.onNodeWithTag(NoticeTags.answer(NoticeAnswer.OK)).assertTextEquals(resources.getString(R.string.common_ok))
        LockShots.take("notice-wrong-pins")
        compose.onNodeWithTag(NoticeTags.answer(NoticeAnswer.CHANGE_PIN)).assertTextEquals(resources.getString(R.string.lock_notice_change_pin)).performClick()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(1, NoticeAnswer.CHANGE_PIN)), lock.sent)
        assertEquals(listOf<Route>(Route.ChangePin), nav.opened)

        // The next notice, one at a time.
        compose.onNodeWithTag(NoticeTags.TITLE).assertTextEquals(resources.getString(R.string.lock_notice_backup_title))
        compose.onNodeWithTag(NoticeTags.answer(NoticeAnswer.BACK_UP_NOW)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.NOTICE).assertDoesNotExist()
        assertEquals(listOf<Route>(Route.ChangePin, Route.Backup), nav.opened)
    }

    @Test
    fun backGivesTheAnswerThatChangesNothing() {
        val lock = show(Notice.PendingReady(4, 1_791_400_000_000L, io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut.PIN))
        compose.onNodeWithTag(NoticeTags.answer(NoticeAnswer.FINISH_NOW)).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(4, NoticeAnswer.LATER)), lock.sent)
        compose.onNodeWithTag(NoticeTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun choosePinWithAProofOpensChooseANewPinAndIsAnsweredWhenItIsLeft() {
        val nav = NavState()
        val notices = listOf(Notice.ChoosePin(5, ChoosePinReason.FORGOTTEN, needsMasterPassword = false), Notice.BackupNow(6))
        compose.runOnUiThread { nav.follow(LockUiState.Unlocked(notices, null)) }
        val lock = show(*notices.toTypedArray(), nav = nav)
        compose.waitUntil(5_000) { nav.visible.lastOrNull() == Route.CreatePin }
        compose.onNodeWithTag(NoticeTags.NOTICE).assertDoesNotExist()
        assertTrue("not answered while Choose a new PIN is shown", lock.sent.isEmpty())
        // The notice's reason was left for the PIN steps (merge of R3, item 2). No PIN screen of this
        // rig takes it, so it is taken here: left in the process, it would reach the PIN steps of a
        // later test (PinStepsScreenTest would show "Choose a new PIN").
        assertEquals(ChoosePinReason.FORGOTTEN, PinSetupStart.take())

        // "Not now": back to the list. The notice is answered, and the backup offer comes next.
        compose.runOnUiThread { nav.back() }
        compose.waitUntil(5_000) { lock.sent.isNotEmpty() }
        assertEquals(listOf<LockIntent>(LockIntent.NoticeAnswered(5, NoticeAnswer.LATER)), lock.sent)
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.TITLE).assertTextEquals(resources.getString(R.string.lock_notice_backup_title))
    }

    @Test
    fun typeMasterPasswordChecksInAPromptWhoseFieldShowsOnlyMasks() {
        val lock = show(Notice.WrongTries(7, 6, null, false, pinsBeforeOff = 4, offerNewMasterPassword = false, wrongMasterPasswords = 0))
        lock.mpResults += MpResult.Wrong
        compose.onNodeWithTag(NoticeTags.line(1)).assertTextEquals(resources.getQuantityString(R.plurals.lock_notice_pins_before_off, 4, 4))
        compose.onNodeWithTag(NoticeTags.answer(NoticeAnswer.TYPE_MASTER_PASSWORD)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.PROMPT).assertExists()
        compose.onNodeWithTag(NoticeTags.PROMPT_FIELD).performTextInput(SECRET)
        compose.waitForIdle()
        val field = compose.onNodeWithTag(NoticeTags.PROMPT_FIELD).fetchSemanticsNode().config
        assertEquals("•".repeat(SECRET.length), field.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals(true, field.getOrNull(SemanticsProperties.IsSensitiveData))
        compose.onNodeWithTag(NoticeTags.PROMPT_CHECK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.promptLine(0)).assertTextEquals(resources.getString(R.string.lock_mp_wrong))
        assertEquals(listOf(MpPurpose.PRACTICE), lock.purposes)

        // A right one closes it.
        compose.onNodeWithTag(NoticeTags.PROMPT_FIELD).performTextInput(SECRET)
        compose.onNodeWithTag(NoticeTags.PROMPT_CHECK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(NoticeTags.PROMPT).assertDoesNotExist()
        assertEquals(listOf(MpPurpose.PRACTICE, MpPurpose.PRACTICE), lock.purposes)
    }

    private companion object {
        const val SECRET = "zqXvKwJpQzYx"
        val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
