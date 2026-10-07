package io.github.dilshodbekmx.passwordvault.window

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `VaultSecurity` as the staging and release builds apply it (`secure = true`), which the debug
 * build of the connected tests otherwise never runs: every switch of lock.md §13.5 on the activity's
 * window, FLAG_SECURE included. The dialogs and sheets of such an activity: `DialogSheetPolicyTest`.
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theReleasePolicyOfTheActivityWindow() {
        compose.runOnUiThread {
            val window = compose.activity.window
            assertFalse("a plain test activity starts without the touch filter", window.protections().filtersObscuredTouches)
            VaultSecurity.applyToActivity(compose.activity, secure = true)
            window.protections().assertVaultPolicy("the activity's window with secure = true", secure = true)
        }
    }

    @Test
    fun theDebugBuildLeavesFlagSecureOff() {
        compose.runOnUiThread {
            VaultSecurity.applyToActivity(compose.activity)
            compose.activity.window.protections().assertVaultPolicy(
                "the activity's window with the build's default (API ${Build.VERSION.SDK_INT})",
                secure = BuildConfig.SECURE_WINDOW,
            )
        }
        assertFalse("connected tests run the debug build", BuildConfig.SECURE_WINDOW)
    }
}
