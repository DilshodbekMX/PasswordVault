package io.github.dilshodbekmx.passwordvault.ui.lock

import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 4.2, [device]: the fingerprint-only screen, "This is an older master password" in both
 * forms with its question, "Open the older set instead?" (lock.md F16b step 3; build plan 4.2b),
 * and "Your passwords can't be opened", as drawn over `FakeLockManager`: each shows its texts
 * (dates in the phone's language) and its buttons tell the lock; Back on the screen of a vault put
 * aside goes back to the current vault.
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
    fun theOlderSetQuestionShowsItsTextsAndItsTwoButtonsTellTheLock() {
        LockShots.touchMode()
        val lock = show(LockUiState.Locked(LockScreen.OpenOlderSet(REPLACED_AT, AsideLine.FromBefore(REPLACED_AT))))
        val day = formatters().dayMonth(REPLACED_AT)
        compose.onNodeWithTag(LockTags.OLDER_SET).assertExists()
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.lock_from_before, formatters().date(REPLACED_AT)))
        compose.onNodeWithTag(LockTags.OLDER_SET_TEXT).assertTextEquals(resources.getString(R.string.lock_older_set_text, day))
        compose.onNodeWithTag(LockTags.OLDER_SET_OPEN).assertTextEquals(resources.getString(R.string.lock_older_set_open, day))
        compose.onNodeWithTag(LockTags.OLDER_SET_CANCEL).assertTextEquals(resources.getString(R.string.common_cancel))
        // The title says whose passwords these are, so no second line says it; there is nothing to type into.
        compose.onNodeWithTag(LockTags.ASIDE).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.FIELD).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.PAD).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.FORGOT).assertDoesNotExist()
        LockShots.take("4.2b-older-set")

        // Each button goes straight to the lock: the screen is the question, and it is not asked once more.
        compose.onNodeWithTag(LockTags.OLDER_SET_OPEN).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertDoesNotExist()
        assertEquals(listOf<LockIntent>(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_OLDER_SET)), lock.sent)
        compose.onNodeWithTag(LockTags.OLDER_SET_CANCEL).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.CONFIRM).assertDoesNotExist()
        assertEquals(
            listOf<LockIntent>(
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.OPEN_OLDER_SET),
                LockIntent.OlderPasswordAnswer(OlderPasswordChoice.KEEP_NEWER_SET),
            ),
            lock.sent,
        )
    }

    @Test
    fun backOnTheOlderSetQuestionGoesBackToTheCurrentVault() {
        val lock = show(LockUiState.Locked(LockScreen.OpenOlderSet(REPLACED_AT, AsideLine.LockedOn(ASIDE_AT))))
        // A line other than the title's is shown above it, as on every screen of a vault put aside.
        compose.onNodeWithTag(LockTags.ASIDE)
            .assertTextEquals(resources.getString(R.string.lock_aside_locked_on, formatters().date(ASIDE_AT)))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(resources.getString(R.string.lock_from_before, formatters().date(REPLACED_AT)))
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(listOf<LockIntent>(LockIntent.GoBack), lock.sent)
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag(LockTags.OLDER_SET).assertExists()
    }

    /**
     * The question as the lock asks it (iface-89): the right master password of the passwords from
     * before a replacement publishes `LockScreen.OpenOlderSet` and the check ends with
     * `MpResult.TooLate`. The question takes the field's place and the keyboard goes with the field;
     * "Cancel" brings the field back as the lock shows it, empty, with the keyboard.
     */
    @Test
    fun afterTheRightPasswordTheQuestionTakesTheFieldsPlaceAndCancelBringsTheFieldBack() {
        val field = PLAIN_SCREEN.copy(reason = MpReason.FromBefore(REPLACED_AT), aside = AsideLine.FromBefore(REPLACED_AT))
        val question = LockUiState.Locked(LockScreen.OpenOlderSet(REPLACED_AT, AsideLine.FromBefore(REPLACED_AT)))
        val fake = FakeLockManager(initial = LockUiState.Locked(field))
        val lock = object : LockManager by fake {
            override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult {
                fake.purposes += purpose
                fake.state.value = question
                return MpResult.TooLate
            }

            override fun send(intent: LockIntent) {
                fake.send(intent)
                if (intent == LockIntent.OlderPasswordAnswer(OlderPasswordChoice.KEEP_NEWER_SET)) fake.state.value = LockUiState.Locked(field)
            }
        }
        val vm = AtomicReference<LockViewModel>()
        compose.setContent { LockRouteHost(lock) { vm.set(it) } }
        compose.waitForIdle()
        val title = resources.getString(R.string.lock_from_before, formatters().date(REPLACED_AT))
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(title)
        assertTrue("the field has the keyboard", waitForKeyboard(shown = true))

        compose.onNodeWithTag(LockTags.FIELD).performTextInput("the words from before")
        compose.onNodeWithTag(LockTags.UNLOCK).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(MpPurpose.UNLOCK), fake.purposes)
        compose.onNodeWithTag(LockTags.OLDER_SET).assertExists()
        compose.onNodeWithTag(LockTags.TITLE).assertTextEquals(title)
        compose.onNodeWithTag(LockTags.FIELD).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.OPENING).assertDoesNotExist()
        assertEquals("", vm.get().password.text.toString())
        assertTrue("the keyboard goes with the field", waitForKeyboard(shown = false))

        compose.onNodeWithTag(LockTags.OLDER_SET_CANCEL).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LockTags.OLDER_SET).assertDoesNotExist()
        compose.onNodeWithTag(LockTags.FIELD).assertExists()
        compose.onNodeWithTag(LockTags.line(0)).assertDoesNotExist()
        assertEquals("", vm.get().password.text.toString())
        assertTrue("the field takes the keyboard again", waitForKeyboard(shown = true))
        assertEquals(listOf<LockIntent>(LockIntent.OlderPasswordAnswer(OlderPasswordChoice.KEEP_NEWER_SET)), fake.sent)
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

    /** Waits up to 10 s for the on-screen keyboard to be [shown] or not ([keyboardShown]). */
    private fun waitForKeyboard(shown: Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < end) {
            if (keyboardShown() == shown) return true
            SystemClock.sleep(250)
        }
        return keyboardShown() == shown
    }

    private companion object {
        const val ASIDE_AT = 1_791_244_800_000L
        const val CHANGED_AT = 1_791_158_400_000L
        const val REPLACED_AT = 1_791_331_200_000L
        const val VAULT = "00000000-0000-4000-8000-0000000000aa"
    }
}
