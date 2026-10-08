package io.github.dilshodbekmx.passwordvault.ui.list

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nothing is focused when the list opens (ux.md §5.11 "Focus"; build plan 12, w3B-carry item 4).
 *
 * On Android 7, after an unlock with the master password, the search field took the focus and the
 * keyboard stayed open over the list and its Add button; the same after the form was closed. The
 * focused field of the screen that left is cleared by Compose with `View.clearFocus()`, and Android 7
 * then hands the focus to the window's first focusable view in touch mode too (Android 9 and later
 * only outside it): `rootView.requestFocus()`, which Compose passes on to its first focusable node.
 *
 * Here a stand-in for the lock screen holds the focus and the keyboard, as the master-password
 * screen does; the list comes in and is laid out while it is still there, as during the
 * navigation's transition at an unlock, and then it leaves: on Android 7 that is the case itself.
 * On every API level the test also makes the call Android 7 makes (`rootView.requestFocus()`), and
 * a tap must still give the field the focus.
 *
 * Both run in touch mode, where the field refuses that focus; with a keyboard it is Android's to
 * give. Since Android 14 touch mode is one state of the whole display, left by a key that an earlier
 * test pressed, so each test first taps the empty window through the system's input.
 */
@RunWith(AndroidJUnit4::class)
class ListFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    private var rig: ListRig? = null

    @After
    fun closeRig() {
        rig?.close()
    }

    /** A tap in the middle of the activity's empty window, sent as a finger's is: the window is then in touch mode. */
    @Before
    fun enterTouchMode() {
        val decor = compose.activity.window.decorView
        val at = IntArray(2)
        compose.runOnIdle { decor.getLocationOnScreen(at) }
        val x = at[0] + decor.width / 2f
        val y = at[1] + decor.height / 2f
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            ui.injectInputEvent(event, true)
            event.recycle()
        }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!decor.isInTouchMode && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the window is in touch mode", decor.isInTouchMode)
    }

    private val vault = Vault(
        entries = listOf(
            Entry(id = "gm", title = "Gmail", username = "aziz@gmail.com", password = "s3cret", createdAt = now, updatedAt = now),
            Entry(id = "tg", title = "Telegram", password = "tg-pw", createdAt = now, updatedAt = now),
        ),
    )

    @Test
    fun aFocusedFieldThatLeavesHandsNoFocusToTheSearchField() {
        val made = ListRig(vault).also { rig = it }
        made.awaitList()
        var listShown by mutableStateOf(false)
        var lockShown by mutableStateOf(true)
        var vm: PasswordsViewModel? = null
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    if (listShown) {
                        val list = viewModel { made.passwordsViewModel() }.also { vm = it }
                        PasswordsScreen(list, made.nav)
                    }
                    if (lockShown) FocusedField()
                }
            }
        }
        compose.onNodeWithTag(STAND_IN).assertIsFocused()
        compose.waitUntil(KEYBOARD_MS) { keyboardShown() }

        // The unlock, as the navigation shows it: the list comes in and is laid out while the lock
        // screen is still there (the transition), then the lock screen and its focused field leave.
        compose.runOnIdle { listShown = true }
        compose.waitUntil(TIMEOUT_MS) { vm?.state?.value?.body is ListBody.Browse }
        compose.waitForIdle()
        compose.onNodeWithTag(STAND_IN).assertIsFocused()
        compose.runOnIdle { lockShown = false }
        compose.waitForIdle()
        compose.onNodeWithTag(ListTags.SEARCH).assertIsNotFocused()
        assertNoKeyboard()
    }

    @Test
    fun androidSevensRefocusFindsNothingInTouchModeAndATapStillFocusesTheField() {
        val made = ListRig(vault).also { rig = it }
        made.awaitList()
        lateinit var vm: PasswordsViewModel
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { made.passwordsViewModel() }
                PasswordsScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
        compose.runOnIdle { assertTrue("the test runs in touch mode", view.isInTouchMode) }

        // What View.clearFocus() does on Android 7 when it hands the focus on: the root asks for it.
        compose.runOnIdle { view.rootView.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithTag(ListTags.SEARCH).assertIsNotFocused()
        assertNoKeyboard()

        // A tap still focuses the field and opens the keyboard (ux.md §5.11).
        compose.onNodeWithTag(ListTags.SEARCH).performClick()
        compose.onNodeWithTag(ListTags.SEARCH).assertIsFocused()
        compose.waitUntil(KEYBOARD_MS) { keyboardShown() }
    }

    /** A field that takes the focus and the keyboard when it is shown, as the master-password screen's does. */
    @Composable
    private fun FocusedField() {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val state = remember { TextFieldState() }
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
        VaultSecureField(
            state = state,
            modifier = Modifier.focusRequester(focus).testTag(STAND_IN),
            label = { Text("Master password") },
        )
    }

    /** The keyboard stays closed for a while: it is not shown now, nor 1.5 s later. */
    private fun assertNoKeyboard() {
        assertFalse("the keyboard is shown", keyboardShown())
        Thread.sleep(1_500)
        assertFalse("the keyboard is shown", keyboardShown())
    }

    /** Whether the system shows a keyboard now (`dumpsys input_method`: the same on every API level). */
    private fun keyboardShown(): Boolean {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
        val text = ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().use { it.readText() }
        return text.contains("mInputShown=true")
    }

    private companion object {
        const val STAND_IN = "test.standIn"
        const val TIMEOUT_MS = 10_000L
        const val KEYBOARD_MS = 10_000L
    }
}
