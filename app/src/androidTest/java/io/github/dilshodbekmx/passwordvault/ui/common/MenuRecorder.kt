package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation

/**
 * A text toolbar that only records what it is asked to show, provided as
 * `LocalTextContextMenuToolbarProvider`: foundation 1.12 hands the platform toolbar the items that
 * remain after every `filterTextContextMenuComponents` above the field, so these are the items the
 * user would see (the window lab saw the "Autofill" item send a manual autofill request).
 */
class MenuRecorder : TextContextMenuProvider {
    /** The item keys of each request to show the toolbar, in order. */
    val shown = CopyOnWriteArrayList<List<Any>>()

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        shown += dataProvider.data().components.map { it.key }
        awaitCancellation()
    }

    /** The names of the known keys of [keys], for messages. */
    fun names(keys: List<Any>): List<String> = keys.map {
        when (it) {
            TextContextMenuKeys.AutofillKey -> "Autofill"
            TextContextMenuKeys.PasteKey -> "Paste"
            TextContextMenuKeys.CopyKey -> "Copy"
            TextContextMenuKeys.CutKey -> "Cut"
            TextContextMenuKeys.SelectAllKey -> "Select all"
            else -> it.toString()
        }
    }
}
