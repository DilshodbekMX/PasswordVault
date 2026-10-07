package io.github.dilshodbekmx.passwordvault.window

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: the activity starts on API 24 (no autofill call there; lock.md §13.5)
 * and on 28 and 37, with `WindowPolicy.onCreate`'s policy on its window: FLAG_SECURE exactly where
 * `BuildConfig.SECURE_WINDOW` is true (not in this debug build), the touch filter on the decor
 * view, and the autofill opt-out (API 26+), the accessibility opt-out (34+) and hidden overlays
 * (31+) where the API level has them; and the obscured-touch watch in front of the window's
 * callback.
 */
@RunWith(AndroidJUnit4::class)
class WindowPolicyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theActivityStartsWithItsWindowPolicy() {
        compose.waitForIdle()
        compose.runOnUiThread {
            val activity: ComponentActivity = compose.activity
            assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            activity.window.protections().assertVaultPolicy("MainActivity's window", secure = BuildConfig.SECURE_WINDOW)
            val callback = activity.window.callback
            assertTrue("the window's callback is ${callback?.javaClass?.name}", callback?.javaClass?.name?.endsWith("ObscuredTouches\$Watcher") == true)
        }
    }

    @Test
    fun aRecreatedActivityKeepsItsWindowPolicy() {
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = compose.activity.window
            window.protections().assertVaultPolicy("MainActivity's window after a recreation", secure = BuildConfig.SECURE_WINDOW)
            assertTrue(window.callback?.javaClass?.name?.endsWith("ObscuredTouches\$Watcher") == true)
        }
    }
}
