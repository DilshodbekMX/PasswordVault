package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: each field wrapper sets the content type, the sensitive flag and the
 * window policy (R-13; lock.md §13.5; ux.md §3).
 *  - `VaultTextField` and `VaultSecureField`: `ContentDataType.None` (no autofill session, so no
 *    fill or save request [V-P4]) and `isSensitiveData` [V-P5] on the editable node itself.
 *  - The window's part, inside `WindowPolicy.Root`: the text toolbar has no "Autofill" item, which
 *    would send a manual autofill request [L-win Experiment 2]; and the keyboard gets
 *    `IME_FLAG_NO_PERSONALIZED_LEARNING` [L-win Experiment 3].
 * Each window check has a control that shows what happens without the policy, so that the test
 * cannot pass because it saw nothing: a plain Material field shows "Autofill" (API 26+, where the
 * item exists), and a field outside `WindowPolicy.Root` gets no flag. The checks against a real
 * autofill service and accessibility service are build plan 3.12's.
 */
@RunWith(AndroidJUnit4::class)
class FieldPolicyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun emptyTheClipboard() {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    @Test
    fun bothFieldsOptOutOfAutofillAndAreSensitive() {
        compose.setContent {
            WindowPolicy.Root {
                Column {
                    VaultTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(PLAIN))
                    VaultSecureField(state = remember { TextFieldState() }, modifier = Modifier.testTag(SECURE))
                }
            }
        }
        for (tag in listOf(PLAIN, SECURE)) {
            val config = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            assertNotNull("$tag is the editable node", config.getOrNull(SemanticsActions.SetText))
            assertEquals("$tag content type", ContentDataType.None, config.getOrNull(SemanticsProperties.ContentDataType))
            assertEquals("$tag sensitive", true, config.getOrNull(SemanticsProperties.IsSensitiveData))
        }
    }

    @Test
    fun noAutofillItemInTheTextToolbar() {
        putTextOnTheClipboard()
        val menu = MenuRecorder()
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu) {
                Column {
                    WindowPolicy.Root {
                        Column {
                            VaultTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(PLAIN))
                            VaultSecureField(state = remember { TextFieldState() }, modifier = Modifier.testTag(SECURE))
                        }
                    }
                    OutlinedTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(CONTROL))
                }
            }
        }
        val control = longPressAndRecord(menu, CONTROL)
        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue("a plain field offers Autofill: ${menu.names(control)}", TextContextMenuKeys.AutofillKey in control)
        }
        for (tag in listOf(PLAIN, SECURE)) {
            val items = longPressAndRecord(menu, tag)
            assertTrue("$tag offers Paste, so the toolbar was asked for: ${menu.names(items)}", TextContextMenuKeys.PasteKey in items)
            assertFalse("$tag offers Autofill: ${menu.names(items)}", TextContextMenuKeys.AutofillKey in items)
        }
    }

    @Test
    fun theKeyboardIsAskedNotToLearn() {
        val imeOptions = CopyOnWriteArrayList<Pair<String, Int>>()
        var focusing = ""
        compose.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    val field = focusing
                    nextHandler.startInputMethod(
                        object : PlatformTextInputMethodRequest {
                            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                                val connection = request.createInputConnection(outAttributes)
                                imeOptions += field to outAttributes.imeOptions
                                return connection
                            }
                        },
                    )
                },
            ) {
                Column {
                    WindowPolicy.Root {
                        Column {
                            VaultTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(PLAIN))
                            VaultSecureField(state = remember { TextFieldState() }, modifier = Modifier.testTag(SECURE))
                        }
                    }
                    OutlinedTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(CONTROL))
                }
            }
        }
        for (tag in listOf(PLAIN, SECURE, CONTROL)) {
            focusing = tag
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(IME_MS) { imeOptions.any { it.first == tag } }
        }
        val flag = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        for (tag in listOf(PLAIN, SECURE)) {
            assertTrue("$tag: imeOptions ${imeOptions.filter { it.first == tag }}", imeOptions.filter { it.first == tag }.all { it.second and flag != 0 })
        }
        assertTrue("the control has no flag: $imeOptions", imeOptions.filter { it.first == CONTROL }.all { it.second and flag == 0 })
    }

    /** Long-presses the empty field [tag] and returns the items its toolbar was asked to show. */
    private fun longPressAndRecord(menu: MenuRecorder, tag: String): List<Any> {
        val before = menu.shown.size
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.waitUntil(TOOLBAR_MS) { menu.shown.size > before }
        val items = menu.shown.last()
        compose.onNodeWithTag(tag).performClick() // closes the toolbar
        compose.waitForIdle()
        return items
    }

    /** So that every toolbar has an item of its own ("Paste") besides the one that must be missing. */
    private fun putTextOnTheClipboard() {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", "pasteable"))
        }
    }

    private companion object {
        const val PLAIN = "plain"
        const val SECURE = "secure"
        const val CONTROL = "control"
        const val TOOLBAR_MS = 5_000L
        const val IME_MS = 10_000L
    }
}
