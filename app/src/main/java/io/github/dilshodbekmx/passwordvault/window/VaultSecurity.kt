package io.github.dilshodbekmx.passwordvault.window

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
import io.github.dilshodbekmx.passwordvault.BuildConfig

/*
 * Window and input protections (lock.md §13.5, ux.md §3), copied from the window lab's
 * docs/lab/window-input-artifacts/VaultSecurity.kt, the file that was built and run on Android 17
 * with Compose BOM 2026.09.00 [L-win "Final code"]. Changed from the lab's file:
 *  - the decor view's autofill opt-out only from API 26: View.setImportantForAutofill does not exist
 *    on Android 7.0 and 7.1, where the lab's unconditional call ends onCreate with NoSuchMethodError
 *    (lock.md §13.5). Android 7 has no autofill framework, so nothing is lost there;
 *  - FLAG_SECURE only where BuildConfig.SECURE_WINDOW is true, in the staging and release builds;
 *    debug builds leave it off (lock.md §13.5, architecture.md §7.2);
 *  - the lab's top-level functions are the members of [VaultSecurity].
 *
 * Use (WindowPolicy does the first two, the wrappers of ui/common the rest):
 *   Activity.onCreate, before setContent:   VaultSecurity.applyToActivity(activity)
 *   around the activity's content:          NoPersonalizedLearning { VaultWindow { ... } }
 *   the content of every dialog and sheet:  VaultWindow { ... }  (VaultDialog, VaultSheet)
 *   every text field:                       Modifier.vaultTextField()  (VaultTextField, VaultSecureField,
 *                                           VaultSearchField)
 * Manifest: <uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS" /> (R-7).
 */

/** The window-level switches of lock.md §13.5, for the activity's window and for each dialog's and sheet's. */
object VaultSecurity {
    /**
     * The activity's window, called from `onCreate` before `setContent` ([WindowPolicy.onCreate]).
     * Besides [applyToWindow]: on API 33+ no screenshot of the task for Recents, which keeps the
     * Recents card blank even in a debug build without FLAG_SECURE.
     */
    fun applyToActivity(activity: Activity, secure: Boolean = BuildConfig.SECURE_WINDOW) {
        applyToWindow(activity.window, secure)
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
    }

    /**
     * One window: the activity's, or a dialog's or bottom sheet's (each is a separate window with
     * its own decor view, which the activity's settings do not reach [L-win Experiments 2 and 4]).
     *  - [secure]: FLAG_SECURE, so screenshots, screen recording, casting and the Recents thumbnail
     *    show the window blank. It is only ever added here, never cleared: a dialog over a secure
     *    activity also gets it from Compose (`SecureFlagPolicy.Inherit`).
     *  - Touches that arrive through another app's window are dropped. It must be the decor view (or
     *    the ComposeView): on `LocalView.current` it has no effect [L-win Experiment 4].
     *  - API 26+: the decor view's autofill opt-out, a safety net only: a field without
     *    `vaultTextField()` then gives an autofill service an empty structure [V-P4].
     *  - API 34+: the window's nodes are hidden from accessibility services that are not
     *    accessibility tools (TalkBack is one and still reads them) [V-P5].
     *  - API 31+: other apps' overlay windows are hidden while this one shows. The call throws
     *    without HIDE_OVERLAY_WINDOWS, hence the check.
     *  - Every version: [MaskedFieldEvents] on the decor view, through which every accessibility
     *    event of the window's content passes.
     */
    fun applyToWindow(window: Window, secure: Boolean = BuildConfig.SECURE_WINDOW) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val decor = window.decorView
        decor.accessibilityDelegate = MaskedFieldEvents
        decor.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 26) {
            decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= 34) {
            decor.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            window.context.checkSelfPermission(Manifest.permission.HIDE_OVERLAY_WINDOWS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            window.setHideOverlayWindows(true)
        }
    }
}

/** The platform's mask character for password text (`PasswordTransformationMethod`), and Compose's. */
internal const val PASSWORD_MASK_CHARACTER = '\u2022'

/**
 * The decor view's accessibility delegate: an event about a password field that the field reports
 * as masked (its text is mask characters only) gets mask characters for its "before" text too.
 *
 * A `VaultSecureField` drawn as dots reports one mask character per character
 * (`maskedForAccessibility`); shown, its text. When it is hidden again, Compose's accessibility
 * delegate compares the old and the new text of the node and sends a text-changed event whose text
 * is the mask characters and whose "before" text is the text that was shown until then; the
 * platform's own password field sends mask characters only at that moment
 * (tools/emu/window/a11y.sh). Every event of the window's content passes through the decor view on
 * its way to the accessibility services, and this one is rewritten here. An event of a field that is
 * shown, and every other event, passes unchanged.
 */
internal object MaskedFieldEvents : View.AccessibilityDelegate() {
    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
        maskBeforeText(event)
        return super.onRequestSendAccessibilityEvent(host, child, event)
    }

    fun maskBeforeText(event: AccessibilityEvent) {
        if (!event.isPassword) return
        val before = event.beforeText ?: return
        if (before.all { it == PASSWORD_MASK_CHARACTER }) return
        if (event.text.all { text -> text == null || text.all { it == PASSWORD_MASK_CHARACTER } }) {
            event.beforeText = PASSWORD_MASK_CHARACTER.toString().repeat(before.length)
        }
    }
}

/**
 * Wraps the content of every window: the activity's content root ([WindowPolicy.Root]) and the
 * content of each dialog and bottom sheet ([io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog],
 * [io.github.dilshodbekmx.passwordvault.ui.common.VaultSheet]); each is a separate window with its
 * own decor view and layout tree.
 *  - In a dialog's or sheet's window it applies [VaultSecurity.applyToWindow] to that window.
 *  - In every window it removes the "Autofill" item from the text toolbar: that item sends a manual
 *    autofill request even for a field that opted out with `ContentDataType.None` [L-win Experiment 2].
 *
 * The content is laid out as if the wrapper were not there.
 */
@Composable
fun VaultWindow(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { VaultSecurity.applyToWindow(it) }
        onDispose { }
    }
    Box(
        modifier = Modifier.filterTextContextMenuComponents { it.key != TextContextMenuKeys.AutofillKey },
        propagateMinConstraints = true,
    ) {
        content()
    }
}

/**
 * For every text field, plain and secure; `VaultTextField`, `VaultSecureField` and `VaultSearchField`
 * apply it (R-13).
 *  - `ContentDataType.None`: Compose never starts an autofill session for the field, so no fill
 *    request and no save request is made for it [V-P4, L-win Experiment 2].
 *  - `isSensitiveData`: the field and its text-change events are hidden from accessibility services
 *    that are not accessibility tools, on API 34+ [V-P5].
 */
fun Modifier.vaultTextField(): Modifier = this.semantics {
    contentDataType = ContentDataType.None
    isSensitiveData = true
}

/**
 * Adds `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` to every text field composed inside it,
 * including the fields of dialogs and bottom sheets, which inherit composition locals; Compose's
 * `KeyboardOptions` has no switch for it [L-win Experiment 3]. Once, around the activity's content
 * ([WindowPolicy.Root]). A keyboard may ignore the flag (ux.md §3).
 *
 * The flag's constant is from API 26; it is only a bit in `imeOptions`, which a keyboard that does
 * not know it ignores, so it is set on Android 7 too (an updated keyboard there may honour it).
 */
@SuppressLint("InlinedApi")
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
