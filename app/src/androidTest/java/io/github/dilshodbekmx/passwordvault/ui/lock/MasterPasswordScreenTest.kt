package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.4, [device]: the master-password screen as drawn, over `FakeLockManager` (ux.md
 * §5.10; lock.md §13.2, §13.3): "Unlock" works only with something typed, it and the keyboard's
 * Done key send the field and empty it, the answer is shown under the field, no node ever holds
 * the typed password, every line and button of a busy state is drawn with its text and date, the
 * wait disables the field and counts down, and Back on the screen of a vault put aside goes back to
 * the current vault instead of leaving the app.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources: Resources get() = compose.activity.resources

    private fun show(lock: FakeLockManager): LockViewModel {
        val vm = AtomicReference<LockViewModel>()
        compose.setContent { MasterPasswordHost(lock) { vm.set(it) } }
        compose.waitForIdle()
        return vm.get()
    }

    @Test
    fun unlockWorksOnlyWithSomethingTypedAndSendsAndEmptiesTheField() {
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN))
        lock.mpResults += MpResult.Wrong
        val vm = show(lock)
        compose.onNodeWithTag(LockTags.FIELD).assertIsFocused()
        compose.onNodeWithTag(LockTags.UNLOCK).assertIsNotEnabled()

        compose.onNodeWithTag(LockTags.FIELD).performTextInput(SECRET)
        compose.onNodeWithTag(LockTags.UNLOCK).assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MpPurpose.UNLOCK), lock.purposes)
        assertEquals("", vm.password.text.toString())
        compose.onNodeWithTag(LockTags.line(0)).assertTextEquals(resources.getString(R.string.lock_mp_wrong))
        compose.onNodeWithTag(LockTags.UNLOCK).assertIsNotEnabled()
        compose.onNodeWithTag(LockTags.FIELD).assertIsFocused()
    }

    @Test
    fun theKeyboardsDoneKeySubmitsAndARightPasswordUnlocks() {
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN))
        val vm = show(lock)
        compose.onNodeWithTag(LockTags.FIELD).performTextInput(SECRET)
        compose.onNodeWithTag(LockTags.FIELD).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf(MpPurpose.UNLOCK), lock.purposes)
        assertTrue(lock.state.value is LockUiState.Unlocked)
        assertEquals("", vm.password.text.toString())
    }

    /**
     * The field's own node holds what was typed (its editable text), marked as a password and as
     * sensitive data, which is what keeps it from accessibility services (lock.md §13.5); no other
     * text or label of the screen repeats any of it.
     */
    @Test
    fun noTextOrLabelHoldsTheTypedPasswordAndTheFieldIsMarked() {
        show(FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN.copy(error = MpError.Wrong(true, null)))))
        compose.onNodeWithTag(LockTags.FIELD).performTextInput(SECRET)
        compose.waitForIdle()
        val field = compose.onNodeWithTag(LockTags.FIELD).fetchSemanticsNode().config
        assertTrue("the field is a password field", SemanticsProperties.Password in field)
        assertEquals(true, field.getOrNull(SemanticsProperties.IsSensitiveData))
        val shown = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { shown += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { shown += it }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { shown += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        assertTrue("the screen's texts are read", shown.size >= 5)
        for (text in shown) {
            for (part in SECRET.windowed(4)) assertFalse("\"$text\" shows \"$part\" of the typed password", part in text)
        }
    }

    @Test
    fun aBusyScreenDrawsEveryLineAndButtonWithItsTextAndDates() {
        val replacedAt = 1_791_331_200_000L
        val asideAt = 1_791_244_800_000L
        val lock = FakeLockManager(
            initial = LockUiState.Locked(
                PLAIN_SCREEN.copy(
                    reason = MpReason.PracticeDue,
                    canUsePin = true,
                    practice = Practice(Shortcut.PIN),
                    error = MpError.Wrong(offerForgot = true, replaced = ReplacedLine(replacedAt, Shortcut.PIN, OLD_VAULT)),
                    pendingReadyInMs = 20 * 3_600_000L,
                    aside = AsideLine.LockedOn(asideAt),
                    fingerprintLine = FingerprintLine.NoneSaved,
                ),
            ),
        )
        lock.status.value = LockStatus.NONE.copy(pin = PinStatus.On)
        show(lock)
        val locale = AppLanguages.screenLocale(AppLanguages.localesOf(resources.configuration))
        val formatters = Formatters(locale, hour24 = true)
        fun text(id: Int, vararg args: Any) = UiText.of(id, *args).resolve(resources)
        compose.onNodeWithTag(LockTags.ASIDE).assertTextEquals(text(R.string.lock_aside_locked_on, formatters.date(asideAt)))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(text(R.string.lock_mp_title))
        compose.onNodeWithTag(LockTags.REASON).assertTextEquals(text(R.string.lock_mp_reason_practice))
        compose.onNodeWithTag(LockTags.FINGERPRINT).assertTextEquals(text(R.string.lock_fingerprint_none_saved_master))
        compose.onNodeWithTag(LockTags.PENDING, useUnmergedTree = true)
            .assertTextEquals(resources.getQuantityString(R.plurals.lock_mp_pending_hours, 20, 20))
        compose.onNodeWithTag(LockTags.line(0)).assertTextEquals(text(R.string.lock_mp_wrong))
        compose.onNodeWithTag(LockTags.line(1)).assertTextEquals(text(R.string.lock_mp_wrong_forgot_pin))
        compose.onNodeWithTag(LockTags.line(2))
            .assertTextEquals(text(R.string.lock_mp_wrong_replaced_pin, formatters.dayMonth(replacedAt)))
        compose.onNodeWithTag(LockTags.I_FORGOT).assertTextEquals(text(R.string.lock_mp_i_forgot))
        compose.onNodeWithTag(LockTags.FROM_BEFORE).assertTextEquals(text(R.string.lock_mp_open_from_before, formatters.dayMonth(replacedAt)))
        compose.onNodeWithTag(LockTags.USE_PIN).assertTextEquals(text(R.string.lock_mp_not_now_pin))
        compose.onNodeWithTag(LockTags.CANT_REMEMBER).assertTextEquals(text(R.string.lock_mp_cant_remember))
        compose.onNodeWithTag(LockTags.FORGOT).assertTextEquals(text(R.string.lock_mp_forgot))

        compose.onNodeWithTag(LockTags.FROM_BEFORE).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.USE_PIN).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(LockIntent.OpenAside(OLD_VAULT), LockIntent.UsePin), lock.sent)
    }

    @Test
    fun theWaitDisablesTheFieldAndCountsDown() {
        val until = SystemClock.elapsedRealtime() + 3_000
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN.copy(waitUntilElapsedMs = until)))
        show(lock)
        compose.onNodeWithTag(LockTags.FIELD).assertIsNotEnabled()
        compose.onNodeWithTag(LockTags.line(0)).assertTextEquals(resources.getString(R.string.lock_mp_wait))
        compose.onNodeWithTag(LockTags.COUNTDOWN).assertExists()
        compose.waitUntil(10_000) { SystemClock.elapsedRealtime() >= until }
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(LockTags.COUNTDOWN) == 0 }
        compose.onNodeWithTag(LockTags.FIELD).assertIsEnabled()
        compose.waitUntil(5_000) { isFocused(LockTags.FIELD) }
    }

    @Test
    fun backOnTheScreenOfAVaultPutAsideGoesBackToTheCurrentVault() {
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN.copy(aside = AsideLine.LockedOn(1_791_244_800_000L))))
        show(lock)
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.sent)
        assertFalse("the app was not left", compose.activity.isFinishing)
    }

    private fun isFocused(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).assertIsFocused()
        true
    } catch (_: AssertionError) {
        false
    }

    private fun ComposeTestRule.onAllNodesWithTagCount(tag: String): Int = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        /** Typed into the field; no four letters of it are in any text of the screen. */
        const val SECRET = "zqXvKwJpQzYx"
        const val OLD_VAULT = "00000000-0000-4000-8000-0000000000aa"
    }
}
