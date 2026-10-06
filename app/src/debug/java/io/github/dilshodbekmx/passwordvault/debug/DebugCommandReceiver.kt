package io.github.dilshodbekmx.passwordvault.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs one of [DebugCommands] for the emulator scripts:
 *
 * ```
 * adb -s <serial> shell am broadcast -a io.github.dilshodbekmx.passwordvault.debug.CMD \
 *     -p io.github.dilshodbekmx.passwordvault --es cmd '<name> [arguments]'
 * ```
 *
 * `am broadcast` prints `result=<code>, data="<text>"`: [RESULT_DONE] with the command's text,
 * [RESULT_UNKNOWN] for a name that is not in the list, [RESULT_FAILED] when the command threw or
 * timed out, and 0 when no receiver answered at all. The same text is logged under [TAG].
 *
 * Declared in the debug, staging and backupCheck manifests only, exported behind
 * `android.permission.DUMP`, which `adb shell` holds and other apps do not (architecture.md §7.2).
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val words = intent.getStringExtra(EXTRA_CMD).orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val name = words.firstOrNull()
        val command = name?.let { DebugCommands.all[it] }
        if (command == null) {
            val known = DebugCommands.all.keys.sorted().joinToString(", ").ifEmpty { "none" }
            answer(RESULT_UNKNOWN, "unknown command '${name.orEmpty()}'; known: $known")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            val (code, text) = try {
                RESULT_DONE to withTimeout(TIMEOUT_MS) { command.run(app, words.drop(1)) }
            } catch (e: TimeoutCancellationException) {
                RESULT_FAILED to "timed out after $TIMEOUT_MS ms"
            } catch (e: Exception) {
                // The class only: an exception's message may quote what it was working on.
                RESULT_FAILED to "failed: ${e.javaClass.name}"
            }
            Log.i(TAG, "$name: $text")
            pending.setResult(code, text, null)
            pending.finish()
        }
    }

    private fun answer(code: Int, text: String) {
        Log.i(TAG, text)
        setResult(code, text, null)
    }

    companion object {
        const val ACTION = "io.github.dilshodbekmx.passwordvault.debug.CMD"
        const val EXTRA_CMD = "cmd"
        const val TAG = "PVDEBUG"
        const val RESULT_DONE = 1
        const val RESULT_UNKNOWN = 2
        const val RESULT_FAILED = 3

        /** Below the 60 seconds after which Android reports a background receiver as not responding. */
        const val TIMEOUT_MS = 20_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
