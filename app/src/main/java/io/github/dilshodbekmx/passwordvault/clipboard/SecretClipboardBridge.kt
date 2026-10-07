package io.github.dilshodbekmx.passwordvault.clipboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.nativeClipboardManager

/**
 * The Compose clipboard of the app's windows, provided as `LocalClipboard` by [ClipboardWindow]
 * (architecture.md D-25; ux.md §6 item 5). Text that Compose copies or cuts (a selection in a note,
 * or in one of the app's own text fields) goes to [SecretClipboard.copy] with the sensitive flag and
 * the timed clear, as "Copied text"; a paste reads the [platform]'s clipboard unchanged, so pasting
 * into the app's fields, such as a password into the Add form, still works.
 *
 * It is an [AndroidClipboard], not just a [Clipboard]: before it offers "Paste", a text field asks
 * the clipboard's `nativeClipboardManager` whether there is text to paste (it reads the description
 * only, so no "pasted from your clipboard" notice appears), and that extension refuses a clipboard
 * that is not an `AndroidClipboard`. Compose marks the interface as visible for testing; it is the
 * only way to stand in for the platform's clipboard without breaking that question.
 *
 * @param platform the clipboard that the window's own ComposeView provides.
 */
@SuppressLint("VisibleForTests")
internal class SecretClipboardBridge(
    private val platform: Clipboard,
    private val secret: SecretClipboard,
) : AndroidClipboard {

    override val clipboardManager: ClipboardManager get() = platform.nativeClipboardManager

    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    /**
     * Text goes to [SecretClipboard.copy] as plain text (Compose's own style marks are left out).
     * Null, which asks for an empty clipboard, clears a clip of ours and nothing else. A clip
     * without text, which no screen of the app makes, is handed to the platform as it is.
     */
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry == null) {
            secret.clearNow()
            return
        }
        val text = textOf(clipEntry.clipData)
        if (text == null) platform.setClipEntry(clipEntry) else secret.copy(text, AndroidSecretClipboard.COPIED_TEXT, sensitive = true)
    }

    private fun textOf(clip: ClipData): String? {
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i)?.text
            if (text != null) return text.toString()
        }
        return null
    }
}
