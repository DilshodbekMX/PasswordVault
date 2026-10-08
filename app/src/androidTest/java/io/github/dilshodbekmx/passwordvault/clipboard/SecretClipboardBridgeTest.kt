package io.github.dilshodbekmx.passwordvault.clipboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSheet
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTextField
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.8, [device]: text selected and copied in a note carries the sensitive flag and the
 * token, and pasting into a field still works (architecture.md D-25, §8.3; ux.md §6 item 5).
 *
 * The screens here stand inside the real `ClipboardRoot` with the app's own clipboard, so a copy
 * goes all the way to the phone's clipboard and into the app's plan for clearing it. The toolbar is
 * a recorder whose items are the ones the platform's toolbar would show; tapping "Copy" or "Paste"
 * there runs what the user's tap runs.
 */
@RunWith(AndroidJUnit4::class)
class SecretClipboardBridgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val graph get() = ClipDevice.app.graph
    private val toolbar = ToolbarRecorder()

    @After
    fun cleanUp() {
        ClipDevice.onMain { graph.clipboard.clearNow() }
        ClipDevice.empty()
    }

    /** Shows [content] inside the app's `ClipboardRoot`; with [focused], waits until the clipboard answers the activity. */
    private fun show(focused: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                ClipboardRoot(graph) {
                    PasswordVaultTheme(ThemeChoice.LIGHT, content)
                }
            }
        }
        if (focused) ClipDevice.awaitFocus(compose.activity)
    }

    @Test
    fun textSelectedAndCopiedInANoteCarriesTheFlagAndTheTokenAndPastingStillWorks() {
        val field = TextFieldState()
        show {
            Column {
                SelectionContainer { Text(NOTE, Modifier.testTag(NOTE_TAG)) }
                VaultTextField(state = field, modifier = Modifier.testTag(FIELD_TAG))
            }
        }

        // Touch and hold selects the word, and the toolbar offers "Copy".
        compose.onNodeWithTag(NOTE_TAG).performTouchInput { longClick() }
        compose.waitUntil(TOOLBAR_MS) { toolbar.has(TextContextMenuKeys.CopyKey) }
        compose.runOnUiThread { toolbar.tap(TextContextMenuKeys.CopyKey) }
        compose.waitUntil(TOOLBAR_MS) { ClipDevice.token() != null }

        assertEquals("the selected text is on the clipboard", NOTE, ClipDevice.text())
        assertEquals("with the sensitive flag", true, ClipDevice.sensitive())
        val token = ClipDevice.token()
        assertTrue("and a token: $token", Regex("[0-9a-f]{32}").matches(token.orEmpty()))
        assertEquals("the label is empty", "", ClipDevice.description()?.label?.toString().orEmpty())

        // It went through the app's one helper: the row says "Copied text", and the clear is planned.
        val status = graph.clipboard.status.value
        assertNotNull(status)
        assertEquals(AndroidSecretClipboard.COPIED_TEXT, status!!.what)
        assertTrue(status.sensitive)
        ClipDevice.waitFor("the token in the settings") { graph.settings.pendingClip.value?.token == token }

        // Pasting reads the platform's clipboard: the field's toolbar offers "Paste", and it pastes.
        val before = toolbar.shown
        compose.onNodeWithTag(FIELD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD_TAG).performTouchInput { longClick() }
        compose.waitUntil(TOOLBAR_MS) { toolbar.shown > before && toolbar.has(TextContextMenuKeys.PasteKey) }
        compose.runOnUiThread { toolbar.tap(TextContextMenuKeys.PasteKey) }
        compose.waitUntil(TOOLBAR_MS) { field.text.toString() == NOTE }
    }

    @Test
    fun textCopiedOrCutInAFieldIsFlaggedTooAndPastesBack() {
        val field = TextFieldState("account number 8600 1234")
        val other = TextFieldState()
        show {
            Column {
                VaultTextField(state = field, modifier = Modifier.testTag(FIELD_TAG))
                VaultTextField(state = other, modifier = Modifier.testTag(OTHER_TAG))
            }
        }
        compose.onNodeWithTag(FIELD_TAG).performClick()
        compose.runOnUiThread { field.edit { selection = TextRange(0, length) } }
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.CopyText)
        compose.waitUntil(TOOLBAR_MS) { ClipDevice.token() != null }
        assertEquals("account number 8600 1234", ClipDevice.text())
        assertEquals(true, ClipDevice.sensitive())
        val copyToken = ClipDevice.token()

        compose.runOnUiThread { field.edit { selection = TextRange(0, 7) } }
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.CutText)
        compose.waitUntil(TOOLBAR_MS) { ClipDevice.token() != copyToken }
        assertEquals("account", ClipDevice.text())
        assertEquals(true, ClipDevice.sensitive())
        assertEquals(" number 8600 1234", field.text.toString())

        compose.onNodeWithTag(OTHER_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OTHER_TAG).performSemanticsAction(SemanticsActions.PasteText)
        compose.waitUntil(TOOLBAR_MS) { other.text.toString() == "account" }
    }

    @Test
    fun aTextCopiedInAnotherAppPastesIntoAField() {
        val field = TextFieldState()
        show { VaultTextField(state = field, modifier = Modifier.testTag(FIELD_TAG)) }
        ClipDevice.otherCopy("from elsewhere")
        compose.onNodeWithTag(FIELD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.PasteText)
        compose.waitUntil(TOOLBAR_MS) { field.text.toString() == "from elsewhere" }
        assertNull("nothing of the app's is pending for a clip it did not make", graph.clipboard.status.value)
    }

    /**
     * A dialog is drawn by a ComposeView of its own, which provides the platform's clipboard again:
     * the bridge of the content around the dialog does not reach into it. [ClipboardWindow] puts it
     * back. Should the first assertion ever fail, Compose has begun to hand the root's clipboard on,
     * and `ClipboardWindow` is no longer needed in dialogs and sheets.
     */
    @Test
    fun aDialogGetsTheBridgeOnlyThroughClipboardWindow() {
        var plain: Clipboard? = null
        var wrapped: Clipboard? = null
        var root: Clipboard? = null
        show(focused = false) { // the dialog's window has the focus, not the activity's
            root = LocalClipboard.current
            Dialog(onDismissRequest = {}) {
                plain = LocalClipboard.current
                ClipboardWindow { wrapped = LocalClipboard.current }
            }
        }
        compose.waitUntil(TOOLBAR_MS) { wrapped != null }
        assertTrue("the root's content has the bridge", root is SecretClipboardBridge)
        assertFalse("a dialog's content has the platform's clipboard", plain is SecretClipboardBridge)
        assertTrue("inside ClipboardWindow it has the bridge", wrapped is SecretClipboardBridge)
    }

    /**
     * A bottom sheet is a window of its own too, drawn by its own ComposeView: as with a dialog.
     * Material 3's sheet itself, as the dialog above is Compose's own. The app's `VaultDialog` and
     * `VaultSheet` have the bridge because `VaultWindow`, which holds their content, wraps it in
     * [ClipboardWindow] (the two tests below).
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun aSheetGetsTheBridgeOnlyThroughClipboardWindow() {
        var plain: Clipboard? = null
        var wrapped: Clipboard? = null
        show(focused = false) {
            ModalBottomSheet(onDismissRequest = {}) {
                plain = LocalClipboard.current
                ClipboardWindow { wrapped = LocalClipboard.current }
            }
        }
        compose.waitUntil(TOOLBAR_MS) { wrapped != null }
        assertFalse("a sheet's content has the platform's clipboard", plain is SecretClipboardBridge)
        assertTrue("inside ClipboardWindow it has the bridge", wrapped is SecretClipboardBridge)
    }

    /**
     * The same copy as in the activity's window, from a field in one of the app's own dialogs, with
     * nothing around the field but what `VaultDialog` puts there.
     */
    @Test
    fun aFieldInTheAppsDialogCopiesThroughTheBridge() {
        val field = TextFieldState("card 8600 1234")
        show(focused = false) {
            VaultDialog(onDismissRequest = {}) {
                VaultTextField(state = field, modifier = Modifier.testTag(FIELD_TAG))
            }
        }
        copyAllOfTheField(field)
    }

    /** The same from a field in one of the app's own bottom sheets (`VaultSheet`). */
    @Test
    fun aFieldInTheAppsSheetCopiesThroughTheBridge() {
        val field = TextFieldState("card 8600 1234")
        show(focused = false) {
            VaultSheet(onDismissRequest = {}) {
                VaultTextField(state = field, modifier = Modifier.testTag(FIELD_TAG))
            }
        }
        copyAllOfTheField(field)
    }

    /**
     * Selects all of [field], the one tagged [FIELD_TAG], and copies it: it reaches the phone's
     * clipboard with the sensitive flag and a token, and through the app's one helper, whose row
     * says "Copied text".
     */
    private fun copyAllOfTheField(field: TextFieldState) {
        compose.onNodeWithTag(FIELD_TAG).performClick()
        compose.runOnUiThread { field.edit { selection = TextRange(0, length) } }
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD_TAG).performSemanticsAction(SemanticsActions.CopyText)
        compose.waitUntil(TOOLBAR_MS) { ClipDevice.token() != null }
        assertEquals(field.text.toString(), ClipDevice.text())
        assertEquals("with the sensitive flag", true, ClipDevice.sensitive())
        val token = ClipDevice.token()
        assertTrue("and a token: $token", Regex("[0-9a-f]{32}").matches(token.orEmpty()))
        assertEquals(AndroidSecretClipboard.COPIED_TEXT, graph.clipboard.status.value?.what)
    }

    /**
     * A copy the phone refuses (ux.md §6 "Failures") ends in the bridge: the text toolbar has nowhere
     * to say so, and an exception there would end the app inside Compose's coroutine.
     */
    @Test
    fun aCopyThePhoneRefusesFromTheToolbarEndsQuietly() {
        val refusing = RefusingClipboard()
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar, LocalSecretClipboard provides refusing) {
                ClipboardWindow {
                    PasswordVaultTheme(ThemeChoice.LIGHT) {
                        SelectionContainer { Text(NOTE, Modifier.testTag(NOTE_TAG)) }
                    }
                }
            }
        }
        ClipDevice.awaitFocus(compose.activity)
        ClipDevice.otherCopy("copied before")

        compose.onNodeWithTag(NOTE_TAG).performTouchInput { longClick() }
        compose.waitUntil(TOOLBAR_MS) { toolbar.has(TextContextMenuKeys.CopyKey) }
        compose.runOnUiThread { toolbar.tap(TextContextMenuKeys.CopyKey) }
        compose.waitUntil(TOOLBAR_MS) { refusing.copies == 1 }
        compose.waitForIdle()
        assertEquals("the clipboard keeps what it had", "copied before", ClipDevice.text())
    }

    /** A [SecretClipboard] on a phone that refuses every copy. */
    private class RefusingClipboard : SecretClipboard {
        @Volatile
        var copies = 0

        override val status: StateFlow<ClipStatus?> = MutableStateFlow(null)

        override fun copy(value: CharSequence, what: UiText, sensitive: Boolean) {
            copies++
            throw SecurityException("this phone blocked copying")
        }

        override fun clearNow() = Unit

        override fun appCameToFront() = Unit
    }

    private companion object {
        /** One word, so that touch and hold selects all of it. */
        const val NOTE = "Samarqand1991javob"
        const val NOTE_TAG = "note"
        const val FIELD_TAG = "field"
        const val OTHER_TAG = "other"
        const val TOOLBAR_MS = 5_000L
    }
}
