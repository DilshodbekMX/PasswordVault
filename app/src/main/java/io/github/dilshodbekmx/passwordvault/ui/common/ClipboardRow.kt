package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.dilshodbekmx.passwordvault.R
import kotlin.math.floor

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
 * Its texts take at most a fifth of the window's height, in whole lines, and scroll beyond it: on a
 * short phone with the font at 200 % they took half the screen and left the list beside them no room
 * (build plan 6.2). "Clear now" stays in view below them.
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
    val textCap = textCap()
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
            // "Clear now" goes under the text when the two do not fit side by side (TextBeside).
            TextBeside(
                modifier = Modifier.weight(1f),
                text = {
                    val scroll = rememberScrollState()
                    // A fade at the edge where there is more: a line cut there reads as "scroll for more".
                    val behind = MaterialTheme.colorScheme.secondaryContainer
                    Box(Modifier.heightIn(max = textCap).fadeWhereMore(scroll, behind).verticalScroll(scroll)) {
                        Column(
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = announcement
                                liveRegion = LiveRegionMode.Polite
                            },
                        ) {
                            if (headline != null) Text(text = headline, style = MaterialTheme.typography.titleSmall)
                            Text(text = text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                action = actionLabel?.let { label ->
                    {
                        TextButton(onClick = onAction) {
                            Text(text = label)
                        }
                    }
                },
            )
        }
    }
}

/** The most the row's texts take: whole lines in a fifth of the window's height, at least two. */
@Composable
private fun textCap(): Dp {
    val window = LocalWindowInfo.current.containerDpSize
    val line = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    if (!window.isSpecified || window.height <= 0.dp || line <= 0.dp) return Dp.Unspecified
    return line * floor(window.height * TEXT_SHARE / line).coerceAtLeast(MIN_TEXT_LINES)
}

private const val TEXT_SHARE = 0.2f
private const val MIN_TEXT_LINES = 2f
