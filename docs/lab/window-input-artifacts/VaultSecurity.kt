package com.example.pvlab.window

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider

/*
 * Consolidated window / input hardening, as verified on an Android 17 (API 37) emulator with
 * Compose BOM 2026.09.00 (ui/foundation 1.12.1, material3 1.4.0). None of these APIs needed an
 * @OptIn.
 *
 * Use:
 *   Activity.onCreate:   applyVaultActivityPolicy()   // before setContent
 *   setContent {         NoPersonalizedLearning { VaultWindow { App() } } }
 *   Dialog / ModalBottomSheet / AlertDialog-slot content:  VaultWindow { ... }
 *   every text field:    Modifier.vaultTextField()
 * Manifest: <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" />
 */

/** Window-level switches for one window (the activity's, or a Compose Dialog's / sheet's). */
fun Window.applyVaultWindowPolicy() {
    // Screenshots, screen recording, casting and the recents thumbnail show this window blank.
    // Compose Dialog / ModalBottomSheet / Popup windows copy it by default (SecureFlagPolicy.Inherit).
    setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    val decor = decorView
    // Drop touches that arrive through another app's window (tapjacking). Must be the decor view
    // or the ComposeView: on LocalView.current (AndroidComposeView) it has no effect.
    decor.filterTouchesWhenObscured = true
    // Safety net only: a field that forgot Modifier.vaultTextField() then yields an empty
    // structure instead of its contents. (It does not stop requests by itself.)
    decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= 34) {
        // Hides the window's node tree from accessibility services that are not accessibility
        // tools (TalkBack etc. declare isAccessibilityTool and are not affected).
        decor.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
    if (Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(Manifest.permission.HIDE_OVERLAY_WINDOWS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        // Other apps' TYPE_APPLICATION_OVERLAY windows are hidden while this window is visible.
        // Throws SecurityException if the permission is not declared, hence the check.
        setHideOverlayWindows(true)
    }
}

/** Call from Activity.onCreate, before setContent. */
fun Activity.applyVaultActivityPolicy() {
    window.applyVaultWindowPolicy()
    // Redundant while FLAG_SECURE is set; keeps the recents card blank if FLAG_SECURE is ever
    // lifted (e.g. a user-facing "allow screenshots" option).
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
}

/**
 * Wrap the content of every window in this: the activity's setContent root, and the content of
 * each Dialog, ModalBottomSheet and AlertDialog slot (each is a separate window with its own
 * decor view and its own layout tree).
 *  - For dialog windows it applies [applyVaultWindowPolicy] to the dialog's own Window.
 *  - In every window it removes the "Autofill" item from the text toolbar; that item sends a
 *    manual autofill request even when the field opted out with ContentDataType.None.
 */
@Composable
fun VaultWindow(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.applyVaultWindowPolicy()
        onDispose { }
    }
    Box(Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }) {
        content()
    }
}

/**
 * Apply to every text field (plain and secure):
 *  - ContentDataType.None: Compose never starts an autofill session for the field, so no fill
 *    request and no save request is issued for it.
 *  - isSensitiveData: the field and its text-change events are hidden from non-tool
 *    accessibility services.
 */
fun Modifier.vaultTextField(): Modifier = this.semantics {
    contentDataType = ContentDataType.None
    isSensitiveData = true
}

/**
 * Adds EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING to every text field composed inside it,
 * including fields in Dialog / ModalBottomSheet content (they inherit composition locals).
 * Put it once around the activity's content.
 */
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modifiedRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val inputConnection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions =
                        outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return inputConnection
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        },
        content = content,
    )
}
