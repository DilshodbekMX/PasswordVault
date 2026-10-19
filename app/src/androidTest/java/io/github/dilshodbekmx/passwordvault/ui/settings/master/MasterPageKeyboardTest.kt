package io.github.dilshodbekmx.passwordvault.ui.settings.master

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterField
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterFrame
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterPage
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 6.2, [device]: a step of "Change master password" or of a new master password
 * (`MasterFrame`, `MasterPage`) keeps its master-password field above the keyboard, as the form, the
 * export, the import and the backup screens keep theirs (`imePadding`): with a long text above the
 * field (a long language, the phone's font at 200 % on a small phone) the field and the line under it
 * ("That's not the right master password…") lay under the keyboard, where the owner typed blind and
 * never saw the line.
 *
 * The activity is laid out edge to edge and resized for the keyboard as the app's is (`WindowPolicy`,
 * the manifest's `adjustResize`). The keyboard is the emulator's own, asked for by the field
 * (`FieldKeyboard`); it is seen through the window's insets, from Android 11 on.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class MasterPageKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aMasterPasswordFieldStaysAboveTheKeyboard() {
        instrumentation.setInTouchMode(true)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            // As the manifest has it for MainActivity (deprecated from API 30 in favour of the insets, which the app reads).
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val state = TextFieldState()
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                MasterFrame(title = "Change master password", onBack = {}, backEnabled = true) {
                    MasterPage(heading = "Type your current master password") {
                        // The texts above the field, as long as a long language and a large font make them.
                        Spacer(Modifier.height(ABOVE_THE_FIELD))
                        MasterField(state = state, label = "Current master password", tag = FIELD, onDone = {})
                    }
                }
            }
        }
        compose.waitUntil(KEYBOARD_MS) { keyboardBottom() > 0 }
        // The keyboard slides in and the page follows it: measure once both have settled.
        var settled = keyboardBottom()
        val end = SystemClock.uptimeMillis() + SETTLE_MS * 10
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(SETTLE_MS)
            compose.waitForIdle()
            val now = keyboardBottom()
            if (now == settled) break
            settled = now
        }
        SystemClock.sleep(SETTLE_MS)
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().size.height
        val keyboardTop = root - keyboardBottom()
        val field = compose.onNodeWithTag(FIELD, useUnmergedTree = true).fetchSemanticsNode()
        val fieldMiddle = field.positionInRoot.y + field.size.height / 2
        assertTrue("the keyboard is shown", keyboardBottom() > 0)
        // Compose scrolls the line being typed into view, so the box's lower padding may stay under the keyboard,
        // as on the form's fields: the field's middle, where its line is, lies above the keyboard.
        assertTrue("the field lies above the keyboard: its middle at $fieldMiddle px, the keyboard's top at $keyboardTop px", fieldMiddle < keyboardTop)
    }

    /** How far the keyboard reaches up from the bottom of the window, in pixels; 0 while it is not shown. */
    private fun keyboardBottom(): Int {
        var bottom = 0
        instrumentation.runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        return bottom
    }

    private companion object {
        const val FIELD = "test.master.field"
        val ABOVE_THE_FIELD = 1_200.dp
        const val KEYBOARD_MS = 15_000L
        const val SETTLE_MS = 300L
    }
}
