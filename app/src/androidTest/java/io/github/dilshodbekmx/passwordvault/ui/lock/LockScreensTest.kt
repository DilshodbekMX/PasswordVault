package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.2, [device]: the fingerprint-only screen, "This is an older master password" in both
 * forms with its question, and "Your passwords can't be opened", as drawn over `FakeLockManager`:
 * each shows its texts (dates in the phone's language) and its buttons tell the lock; Back on the
 * screen of a vault put aside goes back to the current vault.
 */
@RunWith(AndroidJUnit4::class)
class LockScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val resources: Resources get() = compose.activity.resources
    private val restores = AtomicInteger()

    private fun show(state: LockUiState): FakeLockManager {
        val lock = FakeLockManager(initial = state)
        compose.setContent { LockRouteHost(lock, onRestore = { restores.incrementAndGet() }) }
        compose.waitForIdle()
        return lock
    }

    private fun formatters(): Formatters = Formatters(AppLanguages.screenLocale(AppLanguages.localesOf(resources.configuration)), hour24 = true)

    @Test
    fun theFingerprintOnlyScreenShowsItsLinesAndItsTwoButtons() {
        LockShots.touchMode()
        val lock = show(LockUiState.Locked(LockScreen.FingerprintOnly(FingerprintLine.NoneSaved, PinReason.LIMIT, null)))
        compose.onNodeWithTag(LockTags.FINGERPRINT_ONLY).assertExists()
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.app_name))
        compose.onNodeWithTag(LockTags.STATUS).assertTextEquals(resources.getString(R.string.lock_fingerprint_none_saved_master))
        compose.onNodeWithTag(LockTags.PIN_REASON).assertTextEquals(resources.getString(R.string.lock_fingerprint_only_pin_limit))
        compose.onNodeWithTag(LockTags.USE_FINGERPRINT).assertTextEquals(resources.getString(R.string.lock_use_fingerprint))
        compose.onNodeWithTag(LockTags.USE_MASTER_PASSWORD).assertTextEquals(resources.getString(R.string.lock_use_master_password))
        compose.onNodeWithTag(LockTags.TEST_BUILD).assertExists()
        compose.onNodeWithTag(LockTags.FIELD).assertDoesNotExist()
        LockShots.take("fingerprint-only")
        compose.onNodeWithTag(LockTags.USE_FINGERPRINT).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.USE_MASTER_PASSWORD).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(LockIntent.UseFingerprint, LockIntent.UseMasterPassword), lock.sent)
    }

    @Test
    fun theFingerprintOnlyScreenOfAVaultPutAsideGoesBack() {
        val lock = show(LockUiState.Locked(LockScreen.FingerprintOnly(null, null, AsideLine.LockedOn(ASIDE_AT))))
        compose.onNodeWithTag(LockTags.ASIDE)
            .assertTextEquals(resources.getString(R.string.lock_aside_locked_on, formatters().date(ASIDE_AT)))
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.sent)
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun aProvenChangeSaysTypeTheNewOne() {
        val lock = show(LockUiState.Locked(LockScreen.OlderPassword(CHANGED_AT, unfinishedChange = false)))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.lock_older_title))
        compose.onNodeWithTag(LockTags.OLDER_TEXT).assertTextEquals(resources.getString(R.string.lock_older_proven, formatters().dayMonth(CHANGED_AT)))
        compose.onNodeWithTag(LockTags.OLDER_FROM_BEFORE).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.OLDER_OK).performClick()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OK)), lock.sent)
    }

    @Test
    fun anUnfinishedChangeAsksBeforeOpeningTheCopyFromBefore() {
        val lock = show(LockUiState.Locked(LockScreen.OlderPassword(CHANGED_AT, unfinishedChange = true)))
        val day = formatters().dayMonth(CHANGED_AT)
        compose.onNodeWithTag(LockTags.OLDER_TEXT).assertTextEquals(resources.getString(R.string.lock_older_unfinished, day))
        compose.onNodeWithTag(LockTags.OLDER_FROM_BEFORE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertExists()
        compose.onNodeWithTag(LockTags.CONFIRM_YES).assertTextEquals(resources.getString(R.string.lock_older_confirm_open))
        compose.onNodeWithTag(LockTags.CONFIRM_CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertDoesNotExist()
        assertEquals(emptyList<LockIntent>(), lock.sent)

        compose.onNodeWithTag(LockTags.OLDER_FROM_BEFORE).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.CONFIRM_YES).performClick()
        compose.onNodeWithTag(LockTags.OLDER_TRY_AGAIN).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            listOf<LockIntent>(
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_FROM_BEFORE),
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.TRY_AGAIN),
            ),
            lock.sent,
        )
    }

    @Test
    fun yourPasswordsCantBeOpenedOffersTryAgainRestoreAndThePreviousPasswords() {
        LockShots.touchMode()
        val aside = AsideVault(VAULT, ASIDE_AT, null)
        val lock = show(LockUiState.Unreadable(CurrentVault.Why.IO, listOf(aside)))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.lock_unreadable_title))
        compose.onNodeWithTag(LockTags.UNREADABLE_TEXT).assertTextEquals(resources.getString(R.string.lock_unreadable_io))
        compose.onNodeWithTag(LockTags.openAside(VAULT))
            .assertTextEquals(resources.getString(R.string.lock_open_aside, formatters().date(ASIDE_AT)))
        compose.onNodeWithTag(LockTags.UNREADABLE_TRY_AGAIN).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.RESTORE).performScrollTo().performClick()
        compose.onNodeWithTag(LockTags.openAside(VAULT)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(LockIntent.UseMasterPassword, LockIntent.RestoreFromFile, LockIntent.OpenAside(VAULT)), lock.sent)
        assertEquals("the restore screens are opened", 1, restores.get())
        LockShots.take("cant-be-opened")

        // A damaged file has no "Try again".
        compose.runOnUiThread { lock.state.value = LockUiState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()) }
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.UNREADABLE_TEXT).assertTextEquals(resources.getString(R.string.lock_unreadable_damaged))
        compose.onNodeWithTag(LockTags.UNREADABLE_TRY_AGAIN).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.RESTORE).assertExists()
    }

    private companion object {
        const val ASIDE_AT = 1_791_244_800_000L
        const val CHANGED_AT = 1_791_158_400_000L
        const val VAULT = "00000000-0000-4000-8000-0000000000aa"
    }
}
