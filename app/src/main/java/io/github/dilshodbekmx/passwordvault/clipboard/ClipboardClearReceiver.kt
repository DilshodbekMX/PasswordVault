package io.github.dilshodbekmx.passwordvault.clipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp

/**
 * Clears our clip when its alarm fires, after checking the clip's token (architecture.md D-14).
 * Declared in the manifest and not exported: only the `PendingIntent` of [AlarmManagerClearAlarm]
 * reaches it, with the clip's token and its planned clear.
 *
 * Android delivers the alarm whatever state the process is in. A frozen process is thawed for it;
 * for an ended one a new process is started, in which `PasswordVaultApp` has built the app's graph
 * (and with it the clipboard, which found the clip in the settings) before this is called. All the
 * deciding is [AndroidSecretClipboard.alarmFired]'s; the broadcast is held open until it is done,
 * because the process may be frozen or ended as soon as the broadcast finishes.
 */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManagerClearAlarm.ACTION_CLEAR) return
        val token = intent.getStringExtra(AlarmManagerClearAlarm.EXTRA_TOKEN) ?: return
        val clearAt = intent.getLongExtra(AlarmManagerClearAlarm.EXTRA_CLEAR_AT, 0L)
        val app = context.applicationContext as? PasswordVaultApp ?: return
        val clipboard = app.graph.clipboard as? AndroidSecretClipboard ?: return
        val broadcast: PendingResult? = goAsync()
        clipboard.alarmFired(token, clearAt) { broadcast?.finish() }
    }
}
