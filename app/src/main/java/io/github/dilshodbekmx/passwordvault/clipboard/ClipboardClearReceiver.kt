package io.github.dilshodbekmx.passwordvault.clipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Clears our clip when its alarm fires, after checking the clip's token (architecture.md D-14).
 * Not exported. Its body comes with the clipboard package (build plan 3.8).
 */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
