package io.github.dilshodbekmx.passwordvault.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.MainActivity
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.5, [device]: a recreation of the real `MainActivity` keeps the route
 * (architecture.md §5.1; lock.md §11). It works with whatever lock the app has: it opens one route
 * on the stack that the lock shows, recreates the activity as a rotation or a language change does,
 * and finds the same route and the same [NavState] again.
 */
@RunWith(AndroidJUnit4::class)
class NavRecreationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aRecreationKeepsTheRoute() {
        // The first composition has made the activity's NavViewModel; wait until the lock decides.
        compose.waitForIdle()
        lateinit var nav: NavState
        compose.waitUntil(STARTUP_MS) {
            val activity = compose.activity
            var decided = false
            compose.runOnUiThread {
                nav = navStateOf(activity)
                decided = nav.top != NavState.Top.STARTING
            }
            decided
        }
        // One step on the stack shown: a setup step, Restore over the lock screen, or a Settings page.
        // In setup only the first step: WriteDown and TypeAgain show the password chosen on
        // CreateMasterPassword, and one reached without it goes back to step 1 (ux.md §5.5).
        val (first, second) = when (nav.top) {
            NavState.Top.SETUP -> Route.CreateMasterPassword to null
            NavState.Top.LOCKED -> Route.Restore to null
            else -> Route.Settings to Route.AppLock
        }
        compose.runOnUiThread {
            assertTrue(if (first == Route.Settings) nav.tab(first) else nav.go(first))
            if (second != null) assertTrue(nav.go(second))
        }
        val shown = second ?: first
        compose.onNodeWithTag(routeTag(shown)).assertIsDisplayed()
        val stackBefore = nav.visible.toList()
        val before = compose.activity

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        val after = compose.activity
        assertNotSame(before, after)
        compose.onNodeWithTag(routeTag(shown)).assertIsDisplayed()
        compose.runOnUiThread {
            assertSame(nav, navStateOf(after))
            assertTrue(stackBefore == nav.visible.toList())
        }
    }

    /** The activity's [NavState]; on the main thread, once the first composition has made it. */
    private fun navStateOf(activity: MainActivity): NavState = ViewModelProvider(activity)[NavViewModel::class.java].nav

    private companion object {
        /** How long the lock may take to read its records at process start. */
        const val STARTUP_MS = 20_000L
    }
}
