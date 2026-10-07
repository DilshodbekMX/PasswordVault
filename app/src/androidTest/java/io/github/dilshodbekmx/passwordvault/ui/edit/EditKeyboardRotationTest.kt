package io.github.dilshodbekmx.passwordvault.ui.edit

import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.6, [device]: the keyboard is back after a rotation (architecture.md §5.4: Android 17
 * no longer gives it back by itself [V-P7]). The form is shown as `MainActivity` shows it
 * ([EditHost]); a field gets the focus and the keyboard by a tap; the screen is turned to landscape
 * through the activity's requested orientation, which recreates the activity as turning the phone
 * does; then the same field has the focus, the keyboard is shown, and what was typed is still
 * there. The control: with no field focused, the keyboard stays hidden after the same rotation, so
 * the check of the keyboard cannot pass on its own. Runs on API 24, 28 and 37.
 */
@RunWith(AndroidJUnit4::class)
class EditKeyboardRotationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: EditHost

    @After
    fun tearDown() {
        // The next test's activity asks for no orientation, which turns the screen back.
        if (::host.isInitialized) host.stop()
    }

    @Test
    fun theKeyboardIsBackOnTheNameAfterARotation() = keyboardBackAfterRotation(EditTags.NAME, "Gmail") { it.form.title.text.toString() }

    @Test
    fun theKeyboardIsBackOnThePasswordAfterARotation() =
        keyboardBackAfterRotation(EditTags.PASSWORD, "Secret-123") { it.form.password.text.toString() }

    @Test
    fun theKeyboardIsBackOnTheNotesOfAnAccountBeingEditedAfterARotation() {
        val gmail = Entry(id = GMAIL, title = "Gmail", username = "aziz", createdAt = 1L, updatedAt = 1L)
        keyboardBackAfterRotation(EditTags.NOTES, "work mail", Vault(entries = listOf(gmail)), GMAIL) { it.form.notes.text.toString() }
    }

    @Test
    fun withNoFieldFocusedTheKeyboardStaysHiddenAfterARotation() {
        host = EditHost(compose).also { it.start() }
        compose.onNodeWithTag(EditTags.NAME).performClick()
        assertTrue("a tap on the field shows the keyboard", Keyboard.waitFor(shown = true))
        // Back hides the keyboard but keeps the focus; clear the focus as a tap elsewhere would.
        compose.runOnUiThread { compose.activity.currentFocus?.clearFocus() }
        compose.runOnUiThread { host.vm!!.unfocused(FieldKey.Title) }
        hideKeyboard()
        assertTrue("the keyboard is hidden before the rotation", Keyboard.waitFor(shown = false))

        host.rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        compose.onNodeWithTag(EditTags.NAME).assertIsNotFocused()
        SystemClock.sleep(STAYS_MS)
        compose.waitForIdle()
        assertTrue("nothing asks for the keyboard", Keyboard.waitFor(shown = false, timeoutMs = 3_000L))
    }

    private fun keyboardBackAfterRotation(
        tag: String,
        typed: String,
        vault: Vault = Vault(),
        entryId: String? = null,
        text: (EditViewModel) -> String,
    ) {
        host = EditHost(compose, vault, entryId).also { it.start() }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).performTextInput(typed)
        compose.onNodeWithTag(tag).assertIsFocused()
        assertTrue("a tap on the field shows the keyboard", Keyboard.waitFor(shown = true))
        val before = host.vm

        host.rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        assertTrue("the same ViewModel holds the form", before === host.vm)
        compose.waitUntil(FOCUS_MS) { runCatching { compose.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
        compose.onNodeWithTag(tag).assertIsFocused()
        // Wait with the rule, which runs the screen's coroutines meanwhile (a plain sleep would stop them).
        val shown = runCatching { compose.waitUntil(KEYBOARD_MS) { Keyboard.shown() } }.isSuccess
        assertTrue("the keyboard is shown again", shown)
        // And it stays: nothing hides it again a moment later.
        SystemClock.sleep(STAYS_MS)
        compose.waitForIdle()
        assertTrue("the keyboard is still shown", Keyboard.shown())
        assertTrue("what was typed is still there", text(host.vm!!).endsWith(typed))
        assertEquals("nothing moved the screen", emptyList<String>(), host.nav.calls)
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val activity = compose.activity
            val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
    }

    private companion object {
        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val FOCUS_MS = 10_000L
        const val KEYBOARD_MS = 10_000L
        const val STAYS_MS = 1_500L
    }
}
