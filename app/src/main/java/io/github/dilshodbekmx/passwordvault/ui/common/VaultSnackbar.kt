package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The snackbars of every screen ([VaultSnackbar] for each message of [state]); [tag] is the test tag of
 * the snackbar shown.
 */
@Composable
fun VaultSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier, tag: String? = null) {
    SnackbarHost(state, modifier) { data -> VaultSnackbar(data, if (tag != null) Modifier.testTag(tag) else Modifier) }
}

/**
 * Material 3's snackbar, with its action under the message whenever the action would squeeze the
 * message (build plan 8.1; i18n.md §4.5). Material's one-row snackbar measures its action at full
 * width and gives the message only what is left: at 320 dp and a font of 200 % the message column
 * beside "Try again" is a few letters wide in every language, and beside "Қайтариш" (Undo) a word of
 * "Olx ўчириб ташланди" breaks in two. The action goes under the message when, beside it, the message
 * would take more than two lines (Material's own limit for a one-row snackbar) or one of its words
 * would not fit on a line; otherwise the snackbar is Material's one row. A message without an action is
 * Material's snackbar as it is.
 */
@Composable
fun VaultSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val action = data.visuals.actionLabel
    if (action == null) {
        Snackbar(data, modifier)
        return
    }
    BoxWithConstraints {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val messageStyle = MaterialTheme.typography.bodyMedium
        val actionStyle = MaterialTheme.typography.labelLarge
        val message = data.visuals.message
        val width = maxWidth
        val onNewLine = remember(message, action, width, density, messageStyle, actionStyle) {
            actionSqueezes(measurer, density, width, message, messageStyle, action, actionStyle)
        }
        Snackbar(data, modifier, actionOnNewLine = onNewLine)
    }
}

/**
 * Whether the action [action] beside the message [message] squeezes it in a snackbar host [width]
 * wide: the room Material's one-row layout leaves the message (`Snackbar.kt` in material3 1.4.0: 12 dp
 * outside the snackbar on each side, 16 dp before the message, 8 dp after the action and 8 dp between
 * them, at most 600 dp; the action a text button, 12 dp each side of its label and at least 58 dp)
 * holds neither the message in two lines nor each of its words on a line.
 */
internal fun actionSqueezes(
    measurer: TextMeasurer,
    density: Density,
    width: Dp,
    message: String,
    messageStyle: TextStyle,
    action: String,
    actionStyle: TextStyle,
): Boolean = with(density) {
    val container = minOf(width - OUTSIDE * 2, MAX_WIDTH) - MESSAGE_START - ACTION_END
    val label = measurer.measure(action, actionStyle, maxLines = 1, softWrap = false, density = density).size.width
    val button = maxOf(label + (BUTTON_PADDING * 2).roundToPx(), BUTTON_MIN_WIDTH.roundToPx())
    val room = container.roundToPx() - button - BETWEEN.roundToPx()
    if (room <= 0) return true
    val beside = measurer.measure(message, messageStyle, constraints = Constraints(maxWidth = room), density = density)
    if (beside.lineCount > MAX_LINES_BESIDE) return true
    message.split(WHITESPACE).filter { it.isNotEmpty() }.any { word ->
        measurer.measure(word, messageStyle, maxLines = 1, softWrap = false, density = density).size.width > room
    }
}

private val OUTSIDE = 12.dp
private val MAX_WIDTH = 600.dp
private val MESSAGE_START = 16.dp
private val ACTION_END = 8.dp
private val BETWEEN = 8.dp
private val BUTTON_PADDING = 12.dp
private val BUTTON_MIN_WIDTH = 58.dp

/** Material's one-row snackbar holds a message of up to two lines beside its action. */
private const val MAX_LINES_BESIDE = 2

/** Where a message's words part: the spaces a line may break at (a no-break space joins its two words into one). */
private val WHITESPACE = Regex("\\s+")
