package io.github.dilshodbekmx.passwordvault.clipboard

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import kotlinx.coroutines.awaitCancellation

/**
 * A text toolbar that keeps the items it is asked to show and can tap one, provided as
 * `LocalTextContextMenuToolbarProvider`. Foundation 1.12 hands the platform's toolbar these same
 * items, so tapping "Copy" here runs what the user's tap on the real toolbar runs.
 */
internal class ToolbarRecorder : TextContextMenuProvider {
    @Volatile
    var items: List<TextContextMenuItem> = emptyList()
        private set

    /** How many times the toolbar was asked to show. */
    @Volatile
    var shown = 0
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        items = dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
        shown++
        awaitCancellation()
    }

    fun has(key: Any): Boolean = items.any { it.key == key }

    /** Taps the item [key]; call it on the main thread. */
    fun tap(key: Any) {
        val item = items.firstOrNull { it.key == key } ?: error("the toolbar has no such item; it has ${items.map { it.label }}")
        item.onClick(Session)
    }

    private object Session : TextContextMenuSession {
        override fun close() = Unit
    }
}
