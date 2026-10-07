package io.github.dilshodbekmx.passwordvault.ui.edit

import android.content.res.Resources
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.i18n.Formatters

/**
 * The texts that Save of the add and edit form may store (architecture.md §3.7, §5.4), in the
 * language the screen shows: [resources] are the composition's (never the application's), and
 * [formatters] write the time in that language. Of the three texts of [EditTexts], `VaultEdits.add`
 * and `VaultEdits.update` use only [untitled]: "Untitled" and the date and time, for an account saved
 * with a blank name and no website (ux.md §2).
 */
internal class FormTexts(private val resources: Resources, private val formatters: Formatters) : EditTexts {
    override fun untitled(nowMs: Long): String = resources.getString(R.string.edit_untitled, formatters.dateTime(nowMs))

    /** Not a text of this form (a merge's, build plan 3.1 and 5.2): the title as it is. */
    override fun fromBackup(title: String): String = title

    /** Not a text of this form (an import's, build plan 5.4): the title as it is. */
    override fun fromFile(title: String): String = title
}
