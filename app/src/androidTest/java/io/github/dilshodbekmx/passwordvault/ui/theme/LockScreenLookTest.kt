package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockScreens
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockViewModel
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §12, 4.3b, [device]: the lock screen has the chosen style (docs/design/looks/README.md:
 * the choice "is an app setting (not in the vault, not secret), is read before unlock, so the lock
 * screen has it too").
 *
 * The style is stored with the real settings, `PrefsAppSettings` over a `SharedPreferences` file on
 * the device, and read back by a second one made over the same file, as a new process reads it.
 * The app's root theme is drawn with that over a lock that is locked ([FakeLockManager], its vault
 * closed), whose lock screens (`LockScreens`, as the lock route draws them) show the PIN pad. The
 * test then reads the screen's pixels: the background, the logo, the dots and each kind of key,
 * which every style draws in its own way (the README's "PIN keys": filled circles, no fill with a
 * tonal fingerprint key, outlined circles).
 */
@RunWith(AndroidJUnit4::class)
class LockScreenLookTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = FakeVaultRepository(Vault())
    private val lock = FakeLockManager(LockUiState.Locked(LockScreen.Pad(digits = 3, status = null, fingerprintKey = true, opening = false, aside = null)), repository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun prefs() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The settings as a new process reads them from the phone. */
    private fun fromThePhone(): AppSettings = PrefsAppSettings(prefs(), phoneColours = true)

    @Before
    @After
    fun clear() {
        prefs().edit().clear().commit()
        context.deleteSharedPreferences(FILE)
    }

    @After
    fun stopScope() = scope.cancel()

    @Before
    fun lockedAndInTouchMode() {
        // Locked: the vault is closed, as the lock closes it.
        repository.state.value = VaultState.Locked
        enterTouchMode(compose.activity)
    }

    private fun showLocked(settings: AppSettings) {
        compose.setContent {
            WindowPolicy.Root {
                PasswordVaultTheme(settings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LockScreens(viewModel { LockViewModel(lock, AndroidClock, scope, testBuild = false) }, onRestore = {})
                    }
                }
            }
        }
        compose.onNodeWithTag(LockTags.PAD).assertIsDisplayed()
    }

    @Test
    fun theLockScreenHasTheStoredStyleBeforeAnyUnlock() {
        // Stored while the app was open, some time ago.
        assertTrue(fromThePhone().update { it.style = StyleChoice.NIGHT_VAULT; it.theme = ThemeChoice.DARK })
        // A new process, locked: its settings are read from the phone, and nothing is unlocked.
        val settings = fromThePhone()
        assertEquals(StyleChoice.NIGHT_VAULT, settings.style.value)
        showLocked(settings)
        assertLockScreenIs(StyleChoice.NIGHT_VAULT, dark = true)
        assertTrue("the app stayed locked", lock.state.value is LockUiState.Locked)
        assertTrue("the vault stayed closed", repository.state.value is VaultState.Locked)
        assertTrue("no master password was asked for", lock.purposes.isEmpty())
    }

    @Test
    fun everyStyleDrawsTheLockScreenItsOwnWay() {
        val settings = fromThePhone()
        showLocked(settings)
        // A new install: Bank blue, light or dark as the phone is.
        assertLockScreenIs(StyleChoice.BANK_BLUE, dark = phoneIsDark())
        for (dark in listOf(false, true)) {
            for (style in OWN_STYLES) {
                assertTrue(settings.update { it.style = style; it.theme = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT })
                assertLockScreenIs(style, dark)
            }
        }
        assertTrue("the app stayed locked", lock.state.value is LockUiState.Locked)
    }

    /** The lock screen is drawn in [style]: its colours, its logo, its dots and its keys. */
    private fun assertLockScreenIs(style: StyleChoice, dark: Boolean) {
        val look = Looks.of(style, dark)
        val c: ColorScheme = look.colors
        val top = LockTop.of(look)
        val what = "$style ${if (dark) "dark" else "light"}"
        compose.waitForIdle()

        // The screen and the logo's tile.
        compose.awaitPixel(LockTags.PAD, c.background, "$what: the lock screen's background", fx = 0.04f, fy = 0.97f)
        compose.awaitPixel(LockTags.LOGO, top.logoTile, "$what: the logo's tile", fx = 0.5f, fy = 0.08f)
        compose.awaitHasPixel(LockTags.LOGO, top.logoMark, Offset(0.3f, 0.3f), Offset(0.7f, 0.7f), "$what: the logo on its tile")

        // The dots: three filled, three empty with a ring, on the top's colour.
        val dot = DOT / DOTS_WIDTH
        compose.awaitPixel(PinPadTags.DOTS, top.dotFilled, "$what: the first dot, filled", fx = dot / 2, fy = 0.5f)
        compose.awaitPixel(PinPadTags.DOTS, top.background, "$what: the last dot, empty", fx = 1f - dot / 2, fy = 0.5f)
        compose.awaitHasPixel(PinPadTags.DOTS, top.dotRing, Offset(1f - dot, 0.3f), Offset(1f - dot * 0.8f, 0.7f), "$what: the last dot's ring")

        // A digit key: a filled circle, the digit alone, or an outlined circle.
        val key = PinPadTags.key(5)
        when (look.pinKeys) {
            PinKeyLook.FILLED -> {
                compose.awaitPixel(key, c.surfaceContainerHigh, "$what: a digit key's fill", fx = INSIDE, fy = 0.5f)
                assertFalse("$what: a filled key has no ring", ring(key, c))
            }
            PinKeyLook.PLAIN -> {
                compose.awaitPixel(key, c.background, "$what: a digit key has no fill", fx = INSIDE, fy = 0.5f)
                assertFalse("$what: a plain key has no ring", ring(key, c))
            }
            PinKeyLook.OUTLINED -> {
                compose.awaitPixel(key, c.background, "$what: a digit key has no fill", fx = INSIDE, fy = 0.5f)
                assertTrue("$what: an outlined key has a ring", ring(key, c))
            }
        }
        compose.awaitHasPixel(key, c.onSurface, Offset(0.35f, 0.3f), Offset(0.65f, 0.7f), "$what: the digit")

        // The fingerprint key: tonal, or its icon in the primary colour on the screen itself.
        if (look.fingerprintKeyTonal) {
            compose.awaitPixel(PinPadTags.FINGERPRINT, c.secondaryContainer, "$what: the fingerprint key's tonal fill", fx = INSIDE, fy = 0.5f)
            compose.awaitHasPixel(PinPadTags.FINGERPRINT, c.onSecondaryContainer, Offset(0.3f, 0.3f), Offset(0.7f, 0.7f), "$what: the fingerprint icon")
        } else {
            compose.awaitPixel(PinPadTags.FINGERPRINT, c.background, "$what: the fingerprint key has no fill", fx = INSIDE, fy = 0.5f)
            compose.awaitHasPixel(PinPadTags.FINGERPRINT, c.primary, Offset(0.3f, 0.3f), Offset(0.7f, 0.7f), "$what: the fingerprint icon")
        }

        // The delete key: never filled, its icon in the quieter text colour.
        compose.awaitPixel(PinPadTags.DELETE, c.background, "$what: the delete key has no fill", fx = INSIDE, fy = 0.5f)
        compose.awaitHasPixel(PinPadTags.DELETE, c.onSurfaceVariant, Offset(0.3f, 0.3f), Offset(0.7f, 0.7f), "$what: the delete icon")
    }

    /** Whether the key [tag] has a ring of the divider colour at its left edge, where a circle's outline runs. */
    private fun ring(tag: String, c: ColorScheme): Boolean {
        compose.waitForIdle()
        return compose.hasPixel(tag, c.outlineVariant, Offset(0f, 0.48f), Offset(0.06f, 0.52f), tolerance = 6)
    }

    private fun phoneIsDark(): Boolean =
        compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val FILE = "pv_settings_look_test"

        /** A point of a key inside its circle and beside what is drawn on it, as a fraction of its width. */
        const val INSIDE = 0.2f

        /** A dot of the PIN row and the row's width, in dp: six dots of 14 dp, 16 dp apart (`PinPad.kt`). */
        const val DOT = 14f
        const val DOTS_WIDTH = 6 * 14f + 5 * 16f
    }
}
