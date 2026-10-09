package io.github.dilshodbekmx.passwordvault.ui.shell

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.ClipStatus
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.settings.help.English
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 7.3, [JVM]: the first-copy tip (ux.md §6) shows once (`OneTime.FIRST_COPY_TIP`), after
 * the first copy of a password, and says that a force-stop or "Restricted" battery use leaves the
 * copy until the app is opened (architecture.md D-14), with the clipboard delay chosen in Settings
 * and the sentence for Samsung phones or for the others. [FirstCopyTipModel] over the app's fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstCopyTipTest {
    private val dispatcher = StandardTestDispatcher()

    /**
     * The app's scope, for the flag's write and for the model's watch: on the test dispatcher and not
     * the test's background scope, so that `advanceUntilIdle` runs both; cancelled after each test.
     */
    private val writes = CoroutineScope(SupervisorJob() + dispatcher)
    private val settings = FakeAppSettings().also { it.writeDefaults() }
    private val clipboard = FakeSecretClipboard(elapsedMs = { elapsed })
    private val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
    private var elapsed = 1_000L

    @After
    fun tearDown() = writes.cancel()

    private fun TestScope.model(): FirstCopyTipModel {
        val model = FirstCopyTipModel(settings, clipboard, lock, writes, dispatcher)
        writes.launch { model.watch() }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.copyPassword() {
        elapsed += 1_000L
        clipboard.copy("hunter2", UiText.of(R.string.help_tip_title), sensitive = true)
        advanceUntilIdle()
    }

    @Test
    fun showsAfterTheFirstPasswordCopy_once_andStoresTheFlagAsItOpens() = runTest(dispatcher) {
        val model = model()
        assertFalse("nothing was copied", model.open.value)
        copyPassword()
        assertTrue("the first copy of a password brings the tip", model.open.value)
        assertTrue("the flag is stored as the tip opens", OneTime.FIRST_COPY_TIP in settings.oneTime.value)
        model.close()
        assertFalse(model.open.value)
        copyPassword()
        assertFalse("never a second time", model.open.value)
        // A new process (a new model) reads the stored flag.
        assertFalse(model().open.value)
        copyPassword()
        assertFalse(model.open.value)
    }

    @Test
    fun aUsernameOrWebsiteCopyDoesNotBringIt() = runTest(dispatcher) {
        val model = model()
        clipboard.copy("someone@example.com", UiText.of(R.string.help_tip_title), sensitive = false)
        advanceUntilIdle()
        assertFalse(model.open.value)
        assertFalse(OneTime.FIRST_COPY_TIP in settings.oneTime.value)
        copyPassword()
        assertTrue(model.open.value)
    }

    @Test
    fun notWhenTheFlagIsStoredAlready() = runTest(dispatcher) {
        settings.update { it.oneTime = setOf(OneTime.FIRST_COPY_TIP) }
        val model = model()
        copyPassword()
        assertFalse(model.open.value)
    }

    @Test
    fun aCopyThatIsGoneOrClearedDoesNotBringIt() = runTest(dispatcher) {
        val model = model()
        clipboard.status.value = ClipStatus(UiText.of(R.string.help_tip_title), sensitive = true, clearsAtElapsedMs = 9_000L, delayMs = 60_000L, cleared = true)
        advanceUntilIdle()
        assertFalse(model.open.value)
        clipboard.status.value = null
        advanceUntilIdle()
        assertFalse(model.open.value)
    }

    @Test
    fun aLockClosesIt_andItDoesNotComeBackForThatCopy() = runTest(dispatcher) {
        settings.failWrites = true // so that only "the same copy" keeps it closed after the unlock
        val model = model()
        copyPassword()
        assertTrue(model.open.value)
        lock.state.value = LockUiState.Locked(lock.lockScreen)
        advanceUntilIdle()
        assertFalse("a dialog is not kept across a lock (ux.md §4)", model.open.value)
        lock.state.value = LockUiState.Unlocked(emptyList(), null)
        advanceUntilIdle()
        assertFalse("the copy still on the clipboard does not bring it again", model.open.value)
    }

    @Test
    fun aFailedWriteShowsItAgainAtTheNextCopy_neverTwiceForOneCopy() = runTest(dispatcher) {
        settings.failWrites = true
        val model = model()
        copyPassword()
        assertTrue(model.open.value)
        assertFalse(OneTime.FIRST_COPY_TIP in settings.oneTime.value)
        model.close()
        advanceUntilIdle()
        assertFalse("the same copy does not bring it twice", model.open.value)
        settings.failWrites = false
        copyPassword()
        assertTrue("the flag was not stored, so the next copy shows it", model.open.value)
        assertTrue(OneTime.FIRST_COPY_TIP in settings.oneTime.value)
    }

    @Test
    fun aClipRestoredByANewProcessDoesNotBringIt_aCopyAfterwardsDoes() = runTest(dispatcher) {
        // AndroidSecretClipboard restores a pending clip of ours at start as "Copied text", flagged.
        clipboard.status.value = ClipStatus(UiText.of(R.string.help_tip_title), sensitive = true, clearsAtElapsedMs = 50_000L, delayMs = 40_000L)
        val model = model()
        assertFalse("no copy was made in this process", model.open.value)
        assertFalse(OneTime.FIRST_COPY_TIP in settings.oneTime.value)
        copyPassword()
        assertTrue(model.open.value)
    }

    @Test
    fun notWhileTheAppIsLocked() = runTest(dispatcher) {
        lock.state.value = LockUiState.NoVault(emptyList())
        val model = model()
        copyPassword()
        assertFalse(model.open.value)
    }

    @Test
    fun saysThatAForceStopOrRestrictedBatteryUseLeavesTheCopy() {
        for (samsung in listOf(false, true)) {
            val lines = FirstCopyTipModel.lines(ClipDelay.MINUTE_1, samsung).map(English::text)
            assertTrue(lines.contains("If you force-stop this app, or set its battery use to “Restricted”, a copied password stays on the clipboard until you open the app again."))
            assertEquals("Now open the app or website, touch and hold the password box, and tap Paste.", lines[0])
            assertTrue(lines.contains("Never pin a password in your keyboard’s clipboard: pinned items are never removed."))
            if (samsung) {
                assertEquals("Samsung phones keep a list of copied text. To keep passwords out of it, turn off or clear the clipboard history in Samsung Keyboard settings.", lines.last())
            } else {
                assertEquals("Some keyboards keep their own list of copied text; you can turn it off in the keyboard’s settings.", lines.last())
            }
        }
        assertEquals("How to paste", English.string("help_tip_title"))
    }

    @Test
    fun saysTheClipboardDelayChosenInSettings() {
        val expected = mapOf(
            ClipDelay.SECONDS_30 to "The password is removed from the clipboard after 30 seconds. Anything else you copy in that time may be removed too.",
            ClipDelay.MINUTE_1 to "The password is removed from the clipboard after 1 minute. Anything else you copy in that minute may be removed too.",
            ClipDelay.MINUTES_2 to "The password is removed from the clipboard after 2 minutes. Anything else you copy in that time may be removed too.",
            ClipDelay.MINUTES_5 to "The password is removed from the clipboard after 5 minutes. Anything else you copy in that time may be removed too.",
        )
        assertEquals(ClipDelay.entries.toSet(), expected.keys)
        for ((delay, sentence) in expected) assertEquals(sentence, English.text(FirstCopyTipModel.lines(delay, samsung = false)[1]))
    }

    @Test
    fun samsungIsToldByTheManufacturer() {
        assertTrue(FirstCopyTipModel.isSamsung("samsung"))
        assertTrue(FirstCopyTipModel.isSamsung("Samsung"))
        assertFalse(FirstCopyTipModel.isSamsung("Google"))
        assertFalse(FirstCopyTipModel.isSamsung(null))
    }
}
