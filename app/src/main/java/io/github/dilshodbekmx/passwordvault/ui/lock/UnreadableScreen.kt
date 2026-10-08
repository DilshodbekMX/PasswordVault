package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters

/**
 * "Your passwords can't be opened" (ux.md §5.9 "File damaged"; lock.md §13.1 row 2): no generation of
 * the vault can be read. A warning sign, the title, why ([UnreadableUi.body]: damaged, not readable
 * just now, or saved by a newer app), "Try again" for an I/O error only, "Restore from a backup
 * file", and "Open my previous passwords (locked on …)" for each vault put aside. Never the lock,
 * and never an offer to delete (ux.md §5.10). Back leaves the app.
 */
@Composable
fun UnreadableScreen(ui: UnreadableUi, onAction: (LockAction) -> Unit, modifier: Modifier = Modifier) {
    val formatters = rememberFormatters()
    LockPage(modifier.testTag(LockTags.UNREADABLE)) {
        Icon(
            painter = painterResource(R.drawable.ic_common_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.lock_unreadable_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
        )
        Text(
            text = ui.body.show(formatters),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(LockTags.UNREADABLE_TEXT),
        )
        for (button in ui.buttons) LockButtonView(button, formatters, onAction)
    }
}
