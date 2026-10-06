package io.github.dilshodbekmx.passwordvault

import android.app.Application

/**
 * The process, named by the manifest. Its body, which builds the one `AppGraph` in `onCreate`
 * (architecture.md §3.6), comes with the app's shell (build plan 2.5).
 */
class PasswordVaultApp : Application()
