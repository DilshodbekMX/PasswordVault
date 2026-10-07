package io.github.dilshodbekmx.passwordvault.settings

import android.content.Context
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * Builds [AppSettings] (architecture.md §3.6): [PrefsAppSettings] over the app's private
 * `SharedPreferences` file "pv_settings" (build plan 2.9). Android backup and device transfer leave
 * it out, as they leave out every file of the app (the manifest's rules exclude every domain).
 */
object SettingsModule {
    fun create(graph: AppGraph): AppSettings =
        PrefsAppSettings(graph.app.getSharedPreferences(PrefsAppSettings.FILE, Context.MODE_PRIVATE))
}
