package io.github.dilshodbekmx.passwordvault.settings

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings

/** Builds [AppSettings]: `PrefsAppSettings` from build plan 2.9; until then the in-memory fake. */
object SettingsModule {
    fun create(graph: AppGraph): AppSettings = FakeAppSettings()
}
