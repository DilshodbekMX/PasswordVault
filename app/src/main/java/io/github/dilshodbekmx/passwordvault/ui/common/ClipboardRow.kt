package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/**
 * The look of the clipboard status row (ux.md §6), in the bottom area above the navigation bar:
 * a `Surface` in the secondary container colour with a key icon, the [text] ("Password for Gmail ·
 * clears in 0:52"), on Android 7 to 12L a [headline] above it ("Password copied"), and a
 * `TextButton` ("Clear now"). The clipboard package (build plan 3.8) gives the texts and the
 * behaviour through `ClipboardRowSlot`.
 *
 * The countdown is for the eyes only: TalkBack does not hear it change every second. It hears
 * [announcement] once, as a polite live region ("Password copied. It will be removed from the
 * clipboard in 1 minute."), and reads it when the row is focused; a new announcement, such as
 * "Clipboard cleared", is read again.
 *
 * @param cleared a check mark instead of the key, for the 2 seconds of "Clipboard cleared".
 */
@Composable
fun ClipboardRow(
    text: String,
    announcement: String,
    modifier: Modifier = Modifier,
    headline: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    cleared: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(if (cleared) R.drawable.ic_common_check else R.drawable.ic_common_key),
                contentDescription = null,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = announcement
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                if (headline != null) Text(text = headline, style = MaterialTheme.typography.titleSmall)
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}
