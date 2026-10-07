package io.github.dilshodbekmx.passwordvault.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.window.VaultSecurity
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import io.github.dilshodbekmx.passwordvault.window.protections
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: `VaultDialog` (both forms) and `VaultSheet` set the window policy on
 * their own windows, which the activity's policy does not reach (lock.md §13.5; [L-win] Experiments
 * 2 and 4): the touch filter, the autofill opt-out (API 26+), the accessibility opt-out (34+), the
 * hidden overlays (31+), and FLAG_SECURE whenever the activity has it (staging and release; off in
 * this debug build). In each of those windows the text toolbar has no "Autofill" item. The control
 * is compose's own `Dialog` without the wrapper: no touch filter, and "Autofill" offered (API 26+).
 */
@RunWith(AndroidJUnit4::class)
class DialogSheetPolicyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Shown { NONE, ALERT, CONTENT, SHEET, RAW }

    private var shown by mutableStateOf(Shown.NONE)
    private val windows = ConcurrentHashMap<Shown, Window>()
    private val menu = MenuRecorder()

    @Before
    fun content() {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", "pasteable"))
        }
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu) {
                WindowPolicy.Root {
                    when (shown) {
                        Shown.NONE -> Unit
                        Shown.ALERT -> VaultDialog(
                            onDismissRequest = {},
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                            title = { Text("Alert") },
                            text = {
                                Column {
                                    CaptureWindow(Shown.ALERT)
                                    VaultTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(FIELD))
                                }
                            },
                        )
                        Shown.CONTENT -> VaultDialog(onDismissRequest = {}) {
                            Surface {
                                Column {
                                    CaptureWindow(Shown.CONTENT)
                                    VaultSecureField(state = remember { TextFieldState() }, modifier = Modifier.testTag(FIELD))
                                }
                            }
                        }
                        Shown.SHEET -> VaultSheet(onDismissRequest = {}) {
                            CaptureWindow(Shown.SHEET)
                            VaultTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(FIELD))
                        }
                        Shown.RAW -> Dialog(onDismissRequest = {}) {
                            Surface {
                                Column {
                                    CaptureWindow(Shown.RAW)
                                    OutlinedTextField(state = remember { TextFieldState() }, modifier = Modifier.testTag(FIELD))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun emptyTheClipboard() {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    @Test
    fun eachWrapperGivesItsWindowThePolicy() {
        for (which in listOf(Shown.ALERT, Shown.CONTENT, Shown.SHEET)) {
            val window = show(which)
            compose.runOnUiThread { window.protections().assertVaultPolicy("the window of $which", secure = false) }
            hide()
        }
        val raw = show(Shown.RAW)
        compose.runOnUiThread {
            val protections = raw.protections()
            assertFalse("the control: compose's Dialog alone has no touch filter", protections.filtersObscuredTouches)
            assertTrue("the control: nor hidden overlays", protections.hidesOverlays != true)
        }
        hide()
    }

    @Test
    fun aSecureActivityGivesEveryWrapperFlagSecure() {
        compose.runOnUiThread { VaultSecurity.applyToActivity(compose.activity, secure = true) }
        for (which in listOf(Shown.ALERT, Shown.CONTENT, Shown.SHEET)) {
            val window = show(which)
            compose.runOnUiThread { window.protections().assertVaultPolicy("the window of $which over a secure activity", secure = true) }
            hide()
        }
    }

    @Test
    fun noAutofillItemInTheTextToolbarOfADialogOrSheet() {
        for (which in listOf(Shown.ALERT, Shown.CONTENT, Shown.SHEET)) {
            show(which)
            val items = longPressAndRecord()
            assertTrue("$which offers Paste, so the toolbar was asked for: ${menu.names(items)}", TextContextMenuKeys.PasteKey in items)
            assertFalse("$which offers Autofill: ${menu.names(items)}", TextContextMenuKeys.AutofillKey in items)
            hide()
        }
        show(Shown.RAW)
        val control = longPressAndRecord()
        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue("the control offers Autofill: ${menu.names(control)}", TextContextMenuKeys.AutofillKey in control)
        }
        hide()
    }

    /** Learns the window of the dialog or sheet it is composed in. */
    @Composable
    private fun CaptureWindow(which: Shown) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { windows[which] = it }
        }
    }

    private fun show(which: Shown): Window {
        windows.remove(which)
        compose.runOnUiThread { shown = which }
        compose.waitUntil(SHOW_MS) { windows[which] != null }
        compose.waitForIdle()
        return windows[which] ?: error("no window for $which")
    }

    private fun hide() {
        compose.runOnUiThread { shown = Shown.NONE }
        compose.waitForIdle()
    }

    private fun longPressAndRecord(): List<Any> {
        val before = menu.shown.size
        compose.onNodeWithTag(FIELD).performTouchInput { longClick() }
        compose.waitUntil(SHOW_MS) { menu.shown.size > before }
        val items = menu.shown.last()
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()
        return items
    }

    private companion object {
        const val FIELD = "field"
        const val SHOW_MS = 5_000L
    }
}
