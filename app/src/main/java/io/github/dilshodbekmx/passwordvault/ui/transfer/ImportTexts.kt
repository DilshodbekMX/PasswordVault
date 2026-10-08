package io.github.dilshodbekmx.passwordvault.ui.transfer

import android.content.res.Resources
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.i18n.Formatters

/**
 * The texts an import stores with the accounts it adds (architecture.md §3.7, §5.4), in the language
 * the screen shows: [resources] are the composition's, never the application's, and [formatters]
 * write the time in that language.
 *
 * - [untitled]: an account of the file with no name, no website and no username ("Untitled" and the
 *   time of the import, as the form names one, ux.md §2).
 * - [fromFile]: an account of our own JSON whose id an account here holds with other content
 *   ("Gmail (from file)", formats.md §5.2, formats-review P1).
 * - [fromBackup]: an account of a backup file whose id an account here holds with other content
 *   ("Gmail (from backup)", ux.md §5.21, "Backup file into an existing vault").
 * - [hiddenValue]: the name of a hidden value that came without one ("Hidden value 1", formats.md
 *   §8.4; build-plan §12, decision 11).
 */
internal class ImportTexts(private val resources: Resources, private val formatters: Formatters) : EditTexts {
    override fun untitled(nowMs: Long): String = resources.getString(R.string.import_untitled, formatters.dateTime(nowMs))

    override fun fromBackup(title: String): String = resources.getString(R.string.import_from_backup, title)

    override fun fromFile(title: String): String = resources.getString(R.string.import_from_file, title)

    override fun hiddenValue(number: Int): String = resources.getString(R.string.import_hidden_value, number)
}
