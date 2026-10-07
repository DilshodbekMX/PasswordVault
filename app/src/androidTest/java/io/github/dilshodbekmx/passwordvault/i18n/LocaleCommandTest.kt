package io.github.dilshodbekmx.passwordvault.i18n

import android.content.res.Configuration
import android.os.LocaleList
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.MainActivity
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.debug.i18n.LocaleCommand
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.NavViewModel
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.nav.routeTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Build plan 2.9, [device] on API 24, 28 and 37: the debug command `locale` switches the app's
 * language without restarting it. The activity is recreated in the new language, on the same route;
 * the process, its graph (the lock, the settings) and the navigation state go on (lock.md §11,
 * i18n.md §1.4.3). On Android 13 and later through the system's `LocaleManager`, below through
 * AppCompat.
 *
 * Which texts a language gets is Android's choice among the languages the APK has: Russian and
 * Latin Uzbek exist from the start (the AndroidX libraries bring them), Uzbek Cyrillic only with its
 * translations (wave 6). Until then Android shows English for it, and puts English first in the
 * configuration, which the test expects.
 */
@RunWith(AndroidJUnit4::class)
class LocaleCommandTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: PasswordVaultApp = ApplicationProvider.getApplicationContext()

    /** Runs the command as `DebugCommandReceiver` does: on the main thread, where it may suspend. */
    private fun command(vararg args: String): String = runBlocking(Dispatchers.Main) { LocaleCommand.run(app, args.toList()) }

    @After
    fun backToTheSystemLanguage() {
        command("system")
    }

    private fun navOf(activity: MainActivity): NavState = ViewModelProvider(activity)[NavViewModel::class.java].nav

    private fun localesOf(activity: MainActivity): List<Locale> = AppLanguages.localesOf(activity.resources.configuration)

    /** A text of the shell in [tag] alone: the English one when the APK has no texts in [tag]. */
    private fun textIn(tag: String): String {
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return app.createConfigurationContext(configuration).getString(R.string.shell_tab_passwords)
    }

    /** Waits until a new activity, not [before], is shown and [ready] holds for it, and returns it. */
    private fun awaitRecreated(before: MainActivity, ready: (MainActivity) -> Boolean): MainActivity {
        lateinit var now: MainActivity
        compose.waitUntil(TIMEOUT_MS) {
            runCatching {
                val activity = compose.activity
                var done = false
                compose.runOnUiThread { done = activity !== before && ready(activity) }
                if (done) now = activity
                done
            }.getOrDefault(false)
        }
        compose.waitForIdle()
        return now
    }

    @Test
    fun theLocaleCommandSwitchesTheLanguageWithoutRestartingTheApp() {
        // The app as it starts with the shell's lock: Welcome, then one step into setup.
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
        val step = if (nav.top == NavState.Top.SETUP) Route.CreateMasterPassword else Route.Restore
        compose.runOnUiThread { assertTrue(nav.go(step)) }
        compose.onNodeWithTag(routeTag(step)).assertIsDisplayed()
        val stack = nav.visible.toList()
        val graph = app.graph
        val lock = graph.lock
        val lockState = lock.state.value
        val settings = graph.settings
        val pid = Process.myPid()
        val system = AppLanguages.systemLocales(app).first()
        val first = compose.activity

        /** Nothing but the activity was made again, and it shows the same route. */
        fun sameAppSameRoute(activity: MainActivity) {
            assertEquals("the same process", pid, Process.myPid())
            assertSame("the same graph", graph, app.graph)
            assertSame("the same lock", lock, app.graph.lock)
            assertEquals("the lock did not move", lockState, lock.state.value)
            assertSame("the same settings", settings, app.graph.settings)
            compose.runOnUiThread {
                assertSame("the same navigation state", nav, navOf(activity))
                assertEquals("the same route", stack, nav.visible.toList())
            }
            compose.onNodeWithTag(routeTag(step)).assertIsDisplayed()
        }

        // Russian, written as a resource qualifier.
        val russian = command("ru-rRU")
        assertTrue(russian, russian.startsWith("locale: ru-RU (was system)"))
        assertTrue(russian, russian.endsWith("shown: RU"))
        val second = awaitRecreated(first) { localesOf(it).first().language == "ru" }
        assertEquals(AppLanguage.RU, AppLanguages.current(app))
        assertEquals(AppLanguage.RU, AppLanguages.shown(localesOf(second)))
        sameAppSameRoute(second)

        // Uzbek Cyrillic, as the skeleton check writes it: b+uz+Cyrl.
        val uzbekTexts = textIn("uz-Cyrl") != textIn("en")
        val expected = if (uzbekTexts) AppLanguage.UZ_CYRL else AppLanguage.EN
        val cyrillic = command("b+uz+Cyrl")
        assertTrue(cyrillic, cyrillic.startsWith("locale: uz-Cyrl (was ru-RU)"))
        assertTrue(cyrillic, cyrillic.endsWith("shown: ${expected.name}"))
        val third = awaitRecreated(second) { a -> localesOf(a).any { it.language == "uz" && it.script == "Cyrl" } }
        assertEquals(AppLanguage.UZ_CYRL, AppLanguages.current(app))
        assertEquals(expected, AppLanguages.shown(localesOf(third)))
        sameAppSameRoute(third)

        // And back to the phone's language, the same way.
        val back = command("system")
        assertTrue(back, back.startsWith("locale: system (was uz-Cyrl)"))
        val fourth = awaitRecreated(third) { a -> localesOf(a).first() == system && localesOf(a).none { it.script == "Cyrl" } }
        assertEquals(AppLanguage.SYSTEM, AppLanguages.current(app))
        assertEquals(AppLanguages.shown(listOf(system)), AppLanguages.shown(localesOf(fourth)))
        sameAppSameRoute(fourth)
    }

    @Test
    fun theCommandTakesTagsQualifiersAndPseudoLocales() {
        assertEquals("locale: system; shown: ${AppLanguages.systemShown(app).name}", command())
        assertTrue(command("ru").startsWith("locale: ru (was system); shown: RU"))
        assertEquals(AppLanguage.RU, AppLanguages.current(app))
        assertTrue(command("uz").startsWith("locale: uz (was ru); shown: UZ_LATN"))
        assertEquals(AppLanguage.UZ_LATN, AppLanguages.current(app))
        assertTrue(command("en-XA").startsWith("locale: en-XA (was uz); shown: EN"))
        assertTrue(command("default").startsWith("locale: system (was en-XA)"))
        assertEquals(AppLanguage.SYSTEM, AppLanguages.current(app))
        val refused = runCatching { command("not a tag") }.exceptionOrNull()
        assertTrue("$refused", refused is IllegalArgumentException)
        assertEquals(AppLanguage.SYSTEM, AppLanguages.current(app))
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
