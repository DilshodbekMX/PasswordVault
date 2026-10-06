package com.example.pvlab.old

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object SensitiveClipboard {
    /** Same string as ClipDescription.EXTRA_IS_SENSITIVE (constant added in API 33). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, text: CharSequence) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        // ClipDescription.setExtras exists since API 24; older releases simply ignore the flag.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }

    fun clear(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) {
            cm.clearPrimaryClip()
        } else {
            // No clearPrimaryClip() before API 28: overwrite with an empty plain-text clip.
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /** What any app reading the clipboard now gets as text (null = no clip at all). */
    fun peek(context: Context): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return "<clip with 0 items>"
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
