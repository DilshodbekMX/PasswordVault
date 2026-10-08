package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.2, [device]: the "Forgot master password?" sheet (ux.md §5.10; lock.md F17 step 1)
 * as drawn over the master-password screen, from `FakeLockManager`: "Forgot master password?" opens
 * it with its title, its sentence and its entries; each entry tells the lock, after the sheet has
 * closed; "Start with an empty app…" asks once more; "Try again" and Back close it; the keyboard goes
 * while it is open and comes back on the field when it closes.
 */
@RunWith(AndroidJUnit4::class)
class ForgotSheetScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources: Resources get() = compose.activity.resources
    private val restores = AtomicInteger()

    private fun show(status: LockStatus): FakeLockManager {
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN))
        lock.status.value = status
        compose.setContent { LockRouteHost(lock, onRestore = { restores.incrementAndGet() }) }
        compose.waitForIdle()
        return lock
    }

    private fun openSheet() {
        compose.onNodeWithTag(LockTags.FORGOT).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.SHEET).assertExists()
    }

    @Test
    fun theSheetShowsItsTextsAndTheEntriesThatApply() {
        LockShots.touchMode()
        val lock = show(LockStatus.NONE.copy(pin = PinStatus.On, aside = listOf(AsideVault(VAULT, ASIDE_AT, null))))
        openSheet()
        compose.onNode(hasText(resources.getString(R.string.lock_sheet_title))).assertExists()
        compose.onNode(hasText(resources.getString(R.string.lock_sheet_body))).assertExists()
        compose.onNode(hasText(resources.getString(R.string.lock_sheet_pin_text))).assertExists()
        compose.onNodeWithTag(LockTags.SHEET_USE_PIN).assertTextEquals(resources.getString(R.string.lock_sheet_use_pin))
        compose.onNodeWithTag(LockTags.SHEET_RESTORE).assertTextEquals(resources.getString(R.string.lock_restore))
        val date = Formatters(AppLanguages.screenLocale(AppLanguages.localesOf(resources.configuration)), hour24 = true).date(ASIDE_AT)
        compose.onNodeWithTag(LockTags.sheetAside(VAULT)).performScrollTo().assertTextEquals(resources.getString(R.string.lock_open_aside, date))
        compose.onNodeWithTag(LockTags.SHEET_TRY_AGAIN).performScrollTo().assertTextEquals(resources.getString(R.string.common_try_again))
        compose.onNodeWithTag(LockTags.SHEET_EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.SHEET_USE_FINGERPRINT).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.SHEET_USE_PIN).performScrollTo()
        compose.waitForIdle()
        assertTrue("the keyboard is put away while the sheet is open", waitForKeyboard(shown = false))
        LockShots.take("forgot-sheet")

        compose.onNodeWithTag(LockTags.SHEET_USE_PIN).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.SHEET).assertDoesNotExist()
        assertEquals(listOf(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.CantRemember), lock.sent)
    }

    @Test
    fun startWithAnEmptyAppIsAskedOnceMore() {
        LockShots.touchMode()
        val lock = show(LockStatus.NONE)
        openSheet()
        compose.onNode(hasText(resources.getString(R.string.lock_sheet_empty_text))).performScrollTo()
        compose.onNodeWithTag(LockTags.SHEET_EMPTY).performScrollTo().assertTextEquals(resources.getString(R.string.lock_sheet_empty)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertExists()
        compose.onNode(hasText(resources.getString(R.string.lock_empty_title))).assertExists()
        compose.onNode(hasText(resources.getString(R.string.lock_empty_body))).assertExists()
        LockShots.take("start-empty-question")
        compose.onNodeWithTag(LockTags.CONFIRM_CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.SHEET).assertExists()
        assertEquals(listOf<LockIntent>(LockIntent.OpenForgotSheet), lock.sent)

        compose.onNodeWithTag(LockTags.SHEET_EMPTY).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.CONFIRM_YES).assertTextEquals(resources.getString(R.string.lock_empty_confirm)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.SHEET).assertDoesNotExist()
        assertEquals(listOf(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.StartEmpty), lock.sent)
    }

    @Test
    fun restoreFromABackupFileOpensTheRestoreScreens() {
        val lock = show(LockStatus.NONE)
        openSheet()
        compose.onNodeWithTag(LockTags.SHEET_RESTORE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.SHEET).assertDoesNotExist()
        assertEquals(1, restores.get())
        assertEquals(listOf(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.RestoreFromFile), lock.sent)
    }

    @Test
    fun tryAgainAndBackCloseTheSheetAndTheFieldTakesTheKeyboardAgain() {
        val lock = show(LockStatus.NONE.copy(pin = PinStatus.On))
        openSheet()
        compose.onNodeWithTag(LockTags.SHEET_TRY_AGAIN).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.SHEET).assertDoesNotExist()
        assertTrue("the field takes the keyboard again", waitForKeyboard(shown = true))

        openSheet()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.SHEET).assertDoesNotExist()
        assertEquals(
            listOf<LockIntent>(LockIntent.OpenForgotSheet, LockIntent.CloseSheet, LockIntent.OpenForgotSheet, LockIntent.CloseSheet),
            lock.sent,
        )
    }

    /** Waits up to 10 s for the on-screen keyboard to be [shown] or not ([keyboardShown]). */
    private fun waitForKeyboard(shown: Boolean): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + 10_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (keyboardShown() == shown) return true
            android.os.SystemClock.sleep(250)
        }
        return keyboardShown() == shown
    }

    private companion object {
        const val ASIDE_AT = 1_791_244_800_000L
        const val VAULT = "00000000-0000-4000-8000-0000000000aa"
    }
}
