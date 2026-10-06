package com.example.pvlab.window

import android.os.Build
import android.util.Log
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
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.DialogWindowProvider

const val TAG = "PvLab"

/**
 * Adds EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING to the EditorInfo of every Compose text field
 * composed below it (including fields inside Dialog / ModalBottomSheet content, because those
 * sub-compositions inherit composition locals). KeyboardOptions has no switch for this flag.
 */
@Composable
fun LabNoPersonalizedLearning(logTag: String? = TAG, content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modifiedRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    // Let Compose fill in the EditorInfo first, then add the flag.
                    val inputConnection = request.createInputConnection(outAttributes)
                    val before = outAttributes.imeOptions
                    outAttributes.imeOptions =
                        outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    if (logTag != null) {
                        Log.i(
                            logTag,
                            "EditorInfo intercepted: inputType=0x${Integer.toHexString(outAttributes.inputType)} " +
                                "imeOptions 0x${Integer.toHexString(before)} -> 0x${Integer.toHexString(outAttributes.imeOptions)}",
                        )
                    }
                    return inputConnection
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        },
        content = content,
    )
}

/** Options the lab can switch on, read from the launch intent. */
data class LabConfig(
    val secure: Boolean = false,
    val recentsOff: Boolean = false,
    /** none | activity | all : importantForAutofill=NO_EXCLUDE_DESCENDANTS on the decor view(s). */
    val afDecor: String = "none",
    /** Modifier.semantics { contentDataType = ContentDataType.None } on every field. */
    val afCtNone: Boolean = false,
    /** Remove the "Autofill" item from the text toolbar of every field. */
    val afNoMenu: Boolean = false,
    /** Call LocalAutofillManager.current?.cancel() before leaving the screen or finishing. */
    val afCancel: Boolean = false,
    /** Add IME_FLAG_NO_PERSONALIZED_LEARNING through InterceptPlatformTextInput. */
    val imeFlag: Boolean = false,
    /** none | decor | composeview | localview | all : where filterTouchesWhenObscured is set. */
    val filter: String = "none",
    val hideOverlays: Boolean = false,
    /** View.setAccessibilityDataSensitive on the decor view(s) + semantics isSensitiveData. */
    val a11y: Boolean = false,
    /** Only View.setAccessibilityDataSensitive on the decor view(s). */
    val a11yDecorOnly: Boolean = false,
    /** Only Modifier.semantics { isSensitiveData = true } on the fields. */
    val a11ySemOnly: Boolean = false,
    /** none | alert | dialog | sheet | menu | snackbar | tooltip | toast */
    val show: String = "none",
)

/**
 * Window-level switches. Called for the activity window before setContent, and for each Compose
 * Dialog / ModalBottomSheet window from inside its content (see [WindowRoot]).
 * FLAG_SECURE is deliberately NOT set here for dialogs: Compose copies it from the parent window
 * (SecureFlagPolicy.Inherit) and the lab checks that.
 */
fun hardenWindow(window: Window, cfg: LabConfig, label: String) {
    val decor = window.decorView
    val isActivity = label == "activity"
    if (cfg.afDecor == "all" || (cfg.afDecor == "activity" && isActivity)) {
        decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    if (cfg.filter == "all" || (cfg.filter == "decor" && isActivity)) {
        decor.filterTouchesWhenObscured = true
    }
    if (cfg.hideOverlays && Build.VERSION.SDK_INT >= 31) {
        try {
            window.setHideOverlayWindows(true)
            Log.i(TAG, "$label setHideOverlayWindows(true) OK")
        } catch (e: SecurityException) {
            Log.e(TAG, "$label setHideOverlayWindows(true) threw $e")
        }
    }
    if ((cfg.a11y || cfg.a11yDecorOnly) && Build.VERSION.SDK_INT >= 34) {
        decor.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
    val flags = window.attributes.flags
    Log.i(
        TAG,
        "$label window: flags=0x${Integer.toHexString(flags)} " +
            "FLAG_SECURE=${flags and WindowManager.LayoutParams.FLAG_SECURE != 0} " +
            "decor.importantForAutofill=${decor.importantForAutofill} " +
            "decor.filterTouchesWhenObscured=${decor.filterTouchesWhenObscured}" +
            (if (Build.VERSION.SDK_INT >= 34) " decor.isAccessibilityDataSensitive=${decor.isAccessibilityDataSensitive}" else ""),
    )
}

/**
 * Wrap the content of every window (activity root, Dialog, AlertDialog slot, ModalBottomSheet)
 * in this. For dialogs it finds the dialog's Window through DialogWindowProvider and applies
 * [hardenWindow]; for every window it can remove the "Autofill" text-toolbar item.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WindowRoot(cfg: LabConfig, label: String, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        if (label != "activity") {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) hardenWindow(window, cfg, label)
            else Log.w(TAG, "$label: parent ${view.parent?.javaClass?.name} is not a DialogWindowProvider")
        } else if (cfg.filter == "localview") {
            view.filterTouchesWhenObscured = true
            Log.i(TAG, "LocalView ${view.javaClass.name} filterTouchesWhenObscured=true")
        }
        onDispose { }
    }
    val menuFilter =
        if (cfg.afNoMenu) {
            Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey }
        } else {
            Modifier
        }
    // testTagsAsResourceId only for the lab's uiautomator driving; each window has its own
    // semantics root, so it has to be set in every window.
    Box(menuFilter.semantics { testTagsAsResourceId = true }) { content() }
}
