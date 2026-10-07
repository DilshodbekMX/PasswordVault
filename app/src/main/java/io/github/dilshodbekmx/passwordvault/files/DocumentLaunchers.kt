package io.github.dilshodbekmx.passwordvault.files

import androidx.activity.ComponentActivity
import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * The activity hook that registers the file picker's launchers (`CreateDocument`, `OpenDocument`) in
 * `onCreate`, so that a result reaches a new, locked process and is recorded first (ux.md §4 item 4;
 * architecture.md §3.6). Filled by the file-picker package (build plan 5.1); the stub registers
 * nothing.
 */
object DocumentLaunchers {
    fun onCreate(activity: ComponentActivity, graph: AppGraph) = Unit
}
