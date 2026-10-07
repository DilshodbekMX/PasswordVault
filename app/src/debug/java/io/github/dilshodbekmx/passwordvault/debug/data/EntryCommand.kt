package io.github.dilshodbekmx.passwordvault.debug.data

import android.content.Context
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.debug.DebugCommand

/**
 * The debug command `entry` (the integrator's, for the skeleton check of build plan 3A, step 8): adds
 * an account to the open vault the way Save of the add form does (`VaultEdits.add` through
 * `VaultRepository.edit`), for titles that `adb shell input text` cannot type, such as Cyrillic ones,
 * whose order differs between English and Russian (i18n.md §2.7).
 *
 * ```
 * adb -s <serial> shell am broadcast -a io.github.dilshodbekmx.passwordvault.debug.CMD \
 *     -p io.github.dilshodbekmx.passwordvault --es cmd "'entry add Почта'"
 * ```
 *
 * - `entry add TITLE`: an account with that title and nothing else. TITLE is one word, since the
 *   receiver splits the command at white space.
 * - `entry count`: how many accounts there are outside Recently deleted.
 *
 * It answers `entry add: saved; N accounts`, `entry add: not saved (PROBLEM)`, `entry add: refused`
 * or `entry count: N accounts`, and `locked` while no vault is open. The answer never holds a title.
 */
object EntryCommand : DebugCommand {
    override suspend fun run(context: Context, args: List<String>): String {
        val repository = (context.applicationContext as PasswordVaultApp).graph.data.repository
        val random = (context.applicationContext as PasswordVaultApp).graph.platform.random
        return when {
            args == listOf("count") -> "entry count: " + count(repository.state.value)
            args.size == 2 && args[0] == "add" -> {
                if (repository.state.value !is VaultState.Unlocked) return "entry add: locked"
                when (val result = repository.edit(VaultEdits.add(DraftEntry(title = args[1]), NoTexts, random))) {
                    is SaveResult.NotSaved -> "entry add: not saved (${result.problem})"
                    is SaveResult.Saved -> when (result.value) {
                        is EntryResult.Done -> "entry add: saved; " + count(repository.state.value)
                        else -> "entry add: refused"
                    }
                }
            }
            else -> throw IllegalArgumentException("usage: entry add TITLE | entry count")
        }
    }

    private fun count(state: VaultState): String = when (state) {
        is VaultState.Unlocked -> "${state.vault.entries.count { it.deletedAt == null }} accounts"
        VaultState.Locked -> "locked"
    }

    /** The command always gives a title, so none of these texts is ever stored. */
    private object NoTexts : EditTexts {
        override fun untitled(nowMs: Long): String = "Untitled"

        override fun fromBackup(title: String): String = title

        override fun fromFile(title: String): String = title
    }
}
