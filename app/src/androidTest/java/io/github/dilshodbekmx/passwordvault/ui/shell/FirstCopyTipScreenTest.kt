package io.github.dilshodbekmx.passwordvault.ui.shell

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 7.3 on a device: the first-copy tip (ux.md §6) opens over the screen after the first copy
 * of a password, says how to paste, when the copy is removed and that a force-stop or "Restricted"
 * battery use leaves it until the app is opened (architecture.md D-14), stores
 * `OneTime.FIRST_COPY_TIP`, closes with "OK" or Back, and does not come again; a lock closes it.
 * Over the app's fakes, as [UnlockedScaffold] calls it.
 */
@RunWith(AndroidJUnit4::class)
class FirstCopyTipScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = FakeAppSettings().also { it.writeDefaults() }
    private val clipboard = FakeSecretClipboard(elapsedMs = { SystemClock.elapsedRealtime() })
    private val lock = FakeLockManager(LockUiState.Unlocked(emptyList(), null))

    @After
    fun tearDown() = scope.cancel()

    private fun show(samsung: Boolean = false) {
        compose.setContent {
            SettingsTestTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Passwords")
                    // Main.immediate for the flag's write: the fake settings are single-threaded.
                    FirstCopyTip(settings, clipboard, lock, scope, Dispatchers.Main.immediate, samsung)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun copy(sensitive: Boolean = true) {
        compose.runOnUiThread { clipboard.copy("hunter2", UiText.of(R.string.help_tip_title), sensitive) }
        compose.waitForIdle()
    }

    private fun tipCount(): Int = compose.onAllNodesWithTag(FirstCopyTipTags.TIP).fetchSemanticsNodes().size

    private fun awaitTip() = compose.waitUntil(5_000) { tipCount() == 1 }

    @Test
    fun opensOnceAfterTheFirstPasswordCopy_andSaysWhatItMust() {
        show()
        assertEquals("nothing was copied yet", 0, tipCount())
        copy(sensitive = false)
        assertEquals("a username is not a password", 0, tipCount())
        copy()
        awaitTip()
        val res = compose.activity.resources
        compose.onNodeWithTag(FirstCopyTipTags.TIP).assertIsDisplayed()
        for (line in FirstCopyTipModel.lines(ClipDelay.SETUP, samsung = false)) {
            val text = line.resolve(res)
            compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(FirstCopyTipTags.TIP))).performScrollToNode(hasText(text))
            compose.onNode(hasText(text)).assertIsDisplayed()
        }
        // The two "never" cases of D-14, as the acceptance line asks.
        val forceStop = compose.activity.getString(R.string.help_force_stop)
        assertTrue(forceStop.contains("force-stop") && forceStop.contains("“Restricted”") && forceStop.contains("until you open the app again"))
        // The delay is the one setup wrote: 1 minute.
        assertTrue(FirstCopyTipModel.lines(settings.clipDelay.value, false)[1].resolve(res).contains("1 minute"))
        compose.waitUntil(5_000) { OneTime.FIRST_COPY_TIP in settings.oneTime.value }

        compose.onNodeWithTag(FirstCopyTipTags.OK).performClick()
        compose.waitForIdle()
        assertEquals("OK closes it", 0, tipCount())
        copy()
        compose.waitForIdle()
        assertEquals("never a second time", 0, tipCount())
    }

    @Test
    fun backClosesIt_andOnASamsungPhoneItNamesSamsungKeyboard() {
        show(samsung = true)
        copy()
        awaitTip()
        val samsung = compose.activity.getString(R.string.help_tip_samsung)
        compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(FirstCopyTipTags.TIP))).performScrollToNode(hasText(samsung))
        compose.onNode(hasText(samsung)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText(compose.activity.getString(R.string.help_tip_keyboards))).fetchSemanticsNodes().size)
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals("Back closes it", 0, tipCount())
    }

    @Test
    fun aLockClosesIt() {
        show()
        copy()
        awaitTip()
        compose.runOnUiThread { lock.state.value = LockUiState.Locked(lock.lockScreen) }
        compose.waitForIdle()
        assertEquals(0, tipCount())
        compose.runOnUiThread { lock.state.value = LockUiState.Unlocked(emptyList(), null) }
        compose.waitForIdle()
        compose.onAllNodesWithTag(FirstCopyTipTags.TIP).assertCountEquals(0)
    }
}
