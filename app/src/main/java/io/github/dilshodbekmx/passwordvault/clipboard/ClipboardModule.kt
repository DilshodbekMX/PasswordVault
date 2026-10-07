package io.github.dilshodbekmx.passwordvault.clipboard

import android.content.ClipboardManager
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * Builds [SecretClipboard] (architecture.md §3.6): [AndroidSecretClipboard] (build plan 3.8) over the
 * phone's clipboard and alarms, the settings, and the app's clock, random source and scope. Called
 * once per process, on the main thread, when `PasswordVaultApp` builds the session; a clip of ours
 * that an earlier process left in the settings is pending again from then on.
 */
object ClipboardModule {
    fun create(graph: AppGraph): SecretClipboard = AndroidSecretClipboard(
        system = AndroidSystemClip(graph.app.getSystemService(ClipboardManager::class.java)),
        alarm = AlarmManagerClearAlarm(graph.app),
        settings = graph.settings,
        clock = graph.platform.clock,
        random = graph.platform.random,
        scope = graph.appScope,
        io = graph.dispatchers.io,
    )
}
