package io.github.dilshodbekmx.passwordvault.nav

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.5, [device]: a lock keeps the route ids and clears the unlocked ViewModels
 * (architecture.md §5.5 step 5, §5.4; ux.md §4). The app's own navigation display
 * ([NavHostDisplay] with [rememberNavState]) runs with `FakeLockManager` and test screens, each of
 * which makes a ViewModel that records when it is cleared.
 */
@RunWith(AndroidJUnit4::class)
class NavLockTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One screen's ViewModel. */
    class Probe(val screen: String) : ViewModel() {
        @Volatile
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val probes = CopyOnWriteArrayList<Probe>()

    @Composable
    private fun Screen(name: String) {
        viewModel { Probe(name).also { probes += it } }
        Text(name)
    }

    @Test
    fun aLockKeepsTheRouteIdsAndClearsTheUnlockedViewModels() {
        val lock = FakeLockManager(initial = LockUiState.Unlocked(emptyList(), null))
        lateinit var nav: NavState
        compose.setContent {
            nav = rememberNavState(lock)
            NavHostDisplay(nav) {
                entry<Route.Passwords> { Screen("passwords") }
                entry<Route.Entry> { Screen("entry:" + it.id) }
                entry<Route.BigLetters> { Screen("big:" + it.id) }
                entry<Route.Locked> { Screen("locked") }
            }
        }
        compose.onNodeWithTag(routeTag(Route.Passwords)).assertIsDisplayed()
        compose.runOnUiThread { assertTrue(nav.go(Route.Entry(ID))) }
        compose.onNodeWithTag(routeTag(Route.Entry(ID))).assertIsDisplayed()
        compose.runOnUiThread { assertTrue(nav.go(Route.BigLetters(ID, "password"))) }
        compose.onNodeWithTag(routeTag(Route.BigLetters(ID, "password"))).assertIsDisplayed()
        compose.waitForIdle()
        val unlocked = probes.toList()
        assertEquals(listOf("passwords", "entry:$ID", "big:$ID"), unlocked.map { it.screen })
        assertTrue(unlocked.none { it.cleared })

        // Lock: the lock route shows, every unlocked screen's ViewModel is cleared, the ids stay.
        compose.runOnUiThread { lock.state.value = LockUiState.Locked(SCREEN) }
        compose.onNodeWithTag(routeTag(Route.Locked)).assertIsDisplayed()
        compose.waitForIdle()
        compose.onNodeWithTag(routeTag(Route.Entry(ID))).assertDoesNotExist()
        for (probe in unlocked) assertTrue("${probe.screen} was not cleared", probe.cleared)
        compose.runOnUiThread {
            assertEquals(NavState.Top.LOCKED, nav.top)
            assertEquals(listOf(Route.Passwords, Route.Entry(ID)), nav.stack(NavState.Top.UNLOCKED))
        }

        // Unlock: the same route, with a new ViewModel; the lock screen's ViewModel is cleared in turn.
        compose.runOnUiThread { lock.state.value = LockUiState.Unlocked(emptyList(), null) }
        compose.onNodeWithTag(routeTag(Route.Entry(ID))).assertIsDisplayed()
        compose.onNodeWithTag(routeTag(Route.BigLetters(ID, "password"))).assertDoesNotExist()
        compose.waitForIdle()
        val entryProbes = probes.filter { it.screen == "entry:$ID" }
        assertEquals(2, entryProbes.size)
        assertTrue(entryProbes[0].cleared)
        assertFalse(entryProbes[1].cleared)
        assertTrue(probes.single { it.screen == "locked" }.cleared)

        // Back goes to Passwords, whose ViewModel is made again.
        compose.runOnUiThread { assertTrue(nav.back()) }
        compose.onNodeWithTag(routeTag(Route.Passwords)).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(2, probes.count { it.screen == "passwords" })
        assertTrue(entryProbes[1].cleared)
    }

    private companion object {
        const val ID = "33333333-3333-4333-8333-333333333333"
        val SCREEN = LockScreen.MasterPassword(
            reason = null,
            canUsePin = false,
            practice = null,
            waitUntilElapsedMs = null,
            error = null,
            pendingReadyInMs = null,
            opening = false,
            aside = null,
        )
    }
}
