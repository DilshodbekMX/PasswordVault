package io.github.dilshodbekmx.passwordvault.session

import androidx.lifecycle.ProcessLifecycleOwner
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * Builds [Session]: [AutoLock] (build plan 3.2) over the app's lock, repository, clipboard,
 * settings and clock, watching `ProcessLifecycleOwner` and the screen (lock.md §10.2). Called once,
 * on the main thread, when `PasswordVaultApp` starts the process.
 */
object SessionModule {
    fun create(graph: AppGraph): Session {
        val app = graph.app
        val autoLock = AutoLock(
            lock = graph.lock,
            repository = graph.data.repository,
            clipboard = graph.clipboard,
            settings = graph.settings,
            clock = graph.platform.clock,
            scope = graph.appScope,
            screen = ScreenSignals.screenState(app),
        )
        autoLock.start(ProcessLifecycleOwner.get().lifecycle)
        ScreenSignals.onScreenOff(app, autoLock::screenTurnedOff)
        return autoLock
    }
}
