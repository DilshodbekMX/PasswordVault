package io.github.dilshodbekmx.passwordvault.ui.settings

import android.os.Process
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.MainActivity
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.files.AppVault
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.NavViewModel
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.nav.routeTag
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 6.1, [device] on API 24, 28 and 37: the language dialog of the real app (lock.md §11,
 * i18n.md §1.4.3). A choice closes the dialog and Android recreates the activity in that language:
 * the same process, lock state and navigation state, the screen the dialog was opened from shown
 * again, and the choice kept (`AppLanguages.current`); the dialog opened again is in the new language
 * with that choice checked. Through the system's `LocaleManager` from API 33, through AppCompat below.
 *
 * It works with whatever the app holds when it runs: in setup the dialog is opened from Welcome's
 * globe button; with a vault from Settings, after the master password of the connected tests' own
 * vault ([AppVault], the only test that makes one) when the app is locked, and the app is locked
 * again at the end.
 */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: PasswordVaultApp = ApplicationProvider.getApplicationContext()
    private var unlockedHere = false

    @After
    fun backToTheSystemLanguageAndLock() {
        compose.runOnUiThread { AppLanguages.setTags(app, "") }
        compose.waitUntil(TIMEOUT_MS) { AppLanguages.current(app) == AppLanguage.SYSTEM }
        if (unlockedHere && app.graph.lock.state.value is LockUiState.Unlocked) AppVault.lockNow()
    }

    private fun navOf(activity: MainActivity): NavState = ViewModelProvider(activity)[NavViewModel::class.java].nav

    /** The activity's navigation state, once the lock has decided which stack is shown. */
    private fun decidedNav(): NavState {
        lateinit var nav: NavState
        compose.waitUntil(TIMEOUT_MS) {
            val activity = compose.activity
            var decided = false
            compose.runOnUiThread {
                nav = navOf(activity)
                decided = nav.top != NavState.Top.STARTING
            }
            decided
        }
        return nav
    }

    /** Waits until a new activity, not [before], is shown and its configuration is in [language]. */
    private fun awaitRecreatedIn(before: MainActivity, language: AppLanguage): MainActivity {
        lateinit var now: MainActivity
        compose.waitUntil(TIMEOUT_MS) {
            runCatching {
                val activity = compose.activity
                var done = false
                compose.runOnUiThread {
                    done = activity !== before &&
                        AppLanguages.shown(AppLanguages.localesOf(activity.resources.configuration)) == language
                }
                if (done) now = activity
                done
            }.getOrDefault(false)
        }
        compose.waitForIdle()
        return now
    }

    /** The screen the dialog is opened from: Welcome in setup, else Settings (unlocking first if need be). */
    private fun screenWithTheLanguageButton(nav: NavState): Route {
        if (nav.top == NavState.Top.SETUP) return Route.Welcome
        if (nav.top == NavState.Top.LOCKED) {
            AppVault.unlock()
            unlockedHere = true
        }
        compose.waitUntil(TIMEOUT_MS) { nav.top == NavState.Top.UNLOCKED }
        // After a master-password unlock without a PIN the lock's "Choose a PIN" opens the PIN steps
        // over the app (lock.md F1 step 6), and a notice may follow: walked past as a user would,
        // with Back on the PIN steps (ux.md §4: "Not now"; their own buttons can be below the fold
        // of a 1920-pixel screen) and "Later" or "OK" on a notice (app.sh's pva_settle), until
        // nothing is over the app.
        compose.waitUntil(TIMEOUT_MS) {
            if (topOf(nav) in PIN_STEPS) {
                compose.runOnUiThread { nav.back() }
            } else if (!tapIfShown("pv.answer.LATER")) {
                tapIfShown("pv.answer.OK")
            }
            compose.waitForIdle()
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
            topOf(nav) !in PIN_STEPS && !choosePinPending() &&
                compose.onAllNodesWithTag("pv.notice").fetchSemanticsNodes().isEmpty()
        }
        compose.runOnUiThread { assertTrue(nav.tab(Route.Settings)) }
        compose.waitForIdle()
        return Route.Settings
    }

    /** Taps the node with the test tag [tag] when it is shown; whether it was. */
    private fun tapIfShown(tag: String): Boolean {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) return false
        compose.onNodeWithTag(tag).performClick()
        return true
    }

    /** Whether the lock still has its "Choose a PIN" notice for this unlock. */
    private fun choosePinPending(): Boolean =
        (app.graph.lock.state.value as? LockUiState.Unlocked)?.notices.orEmpty().any { it is Notice.ChoosePin }

    private fun topOf(nav: NavState): Route? {
        var top: Route? = null
        compose.runOnUiThread { top = nav.visible.lastOrNull() }
        return top
    }

    private fun openTheDialog(from: Route) {
        compose.onNodeWithTag(routeTag(from)).assertExists()
        if (from == Route.Settings) {
            compose.onNodeWithTag(SettingsTags.LIST).performScrollToNode(hasTestTag(SettingsTags.open(Route.Language)))
            compose.onNodeWithTag(SettingsTags.open(Route.Language)).performClick()
        } else {
            compose.onNodeWithTag(SetupTags.LANGUAGE).performClick()
        }
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(LanguageTags.DIALOG).assertExists() }.isSuccess }
    }

    @Test
    fun aChoiceRecreatesTheActivityInItOnTheSameScreen() {
        val nav = decidedNav()
        val from = screenWithTheLanguageButton(nav)
        val graph = app.graph
        val lockState = graph.lock.state.value
        val pid = Process.myPid()
        val stack = nav.visible.toList()
        assertEquals(from, stack.last())
        // The language the app shows with the phone's languages, where it goes back to at the end.
        val phoneShown = AppLanguages.shown(AppLanguages.localesOf(compose.activity.resources.configuration))

        openTheDialog(from)
        compose.onNodeWithTag(LanguageTags.choice(LanguageChoice.SYSTEM)).assertIsSelected()
        val first = compose.activity
        compose.onNodeWithTag(LanguageTags.choice(LanguageChoice.RU)).performClick()
        val second = awaitRecreatedIn(first, AppLanguage.RU)

        assertEquals("the same process", pid, Process.myPid())
        assertSame("the same graph", graph, app.graph)
        assertEquals("the lock did not move", lockState::class, graph.lock.state.value::class)
        compose.runOnUiThread {
            assertSame("the same navigation state", nav, navOf(second))
            assertEquals("the screen the dialog was opened from", stack, nav.visible.toList())
        }
        assertEquals(AppLanguage.RU, AppLanguages.current(app))
        compose.onNodeWithTag(routeTag(from)).assertExists()
        compose.onNodeWithTag(LanguageTags.DIALOG).assertDoesNotExist()

        // Opened again: in Russian, with Русский checked.
        openTheDialog(from)
        compose.onNode(hasText("Язык")).assertExists()
        compose.onNodeWithTag(LanguageTags.choice(LanguageChoice.RU)).assertIsSelected()

        // And back to the phone's language the same way.
        compose.onNodeWithTag(LanguageTags.choice(LanguageChoice.SYSTEM)).performClick()
        awaitRecreatedIn(second, phoneShown)
        assertEquals(AppLanguage.SYSTEM, AppLanguages.current(app))
        compose.runOnUiThread { assertEquals(stack, nav.visible.toList()) }
        assertEquals(pid, Process.myPid())
        assertEquals("the lock did not move", lockState::class, graph.lock.state.value::class)
    }

    private companion object {
        val PIN_STEPS = setOf(Route.CreatePin, Route.ConfirmPin, Route.FingerprintOffer, Route.BackupsExplained)
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_000L
    }
}
