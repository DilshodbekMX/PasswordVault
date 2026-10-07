package io.github.dilshodbekmx.passwordvault.debug

import android.content.Context
import io.github.dilshodbekmx.passwordvault.debug.data.EntryCommand
import io.github.dilshodbekmx.passwordvault.debug.i18n.LocaleCommand

/**
 * One test hook, run by [DebugCommandReceiver] (architecture.md §3.6, lock.md §15.3). Debug and
 * staging builds only; release builds contain neither the commands nor the receiver (build check R-2).
 *
 * A package that needs a command writes its handler in its own file under `debug/<area>/` and asks
 * the integrator for its one line in [DebugCommands.all].
 */
fun interface DebugCommand {
    /**
     * Runs the command with [args], the words that followed its name. Called on the main thread; it
     * may suspend, for at most [DebugCommandReceiver.TIMEOUT_MS]. The text it returns becomes the
     * broadcast's result data, which `am broadcast` prints, and is logged under
     * [DebugCommandReceiver.TAG]. It must never contain a secret: no password, PIN digit, key or
     * entry text.
     */
    suspend fun run(context: Context, args: List<String>): String
}

/** Every debug command, by the name that `cmd` starts with. Kept by the integrator: one line each. */
object DebugCommands {
    val all: Map<String, DebugCommand> = mapOf(
        "entry" to EntryCommand,
        "locale" to LocaleCommand,
    )
}
