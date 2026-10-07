package io.github.dilshodbekmx.passwordvault

import android.app.Application

/**
 * The process, named by the manifest. It builds the one [AppGraph] in [onCreate] (architecture.md
 * §3.6, §5.1), and starts the two process-wide objects that must run from the start: the lock,
 * which reads its records at process start (lock.md §5.11), and the session, which watches the
 * process lifecycle (lock.md §10.2). Everything else is made when it is first used.
 */
class PasswordVaultApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.lock
        graph.session
    }
}
