package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The app's top bar (R-13 makes no rule for it; every screen with a bar uses it): Material 3's small
 * top app bar, the navigation button at the start, the [title] in `titleLarge`, the [actions] at the
 * end, 64 dp high in the surface colour, clear of the status bar, except that its title is never cut
 * (i18n.md §4.5 rules 2 and 3; ux.md §8 "Font scaling"). Uzbek and Russian titles are longer than
 * English ones, and the phone's font can be twice as large:
 *
 * - a title that fits on one line between the buttons is laid out as Material lays it out;
 * - one that fits on two lines there, with no word broken, takes two lines beside the buttons;
 * - any other moves to a row of its own under the buttons, as wide as the screen, and takes the lines
 *   it needs, so that a long word never breaks and nothing is cut off.
 *
 * With [titleMayBeCut] (an account's name, which the screen shows whole as the first line of its
 * content, ux.md §5.12), the title stays on one line with an ellipsis and is marked [MayTruncate];
 * [titleIsUserText] marks a title that is the user's own, such as that name ([UserText]). The title
 * is the screen's heading for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBarDefaults.windowInsets is experimental in material3 1.4.0 (ux.md §10)
@Composable
fun VaultTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    titleMayBeCut: Boolean = false,
    titleIsUserText: Boolean = false,
    titleModifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.titleLarge
    val colours = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    Surface(modifier = modifier.fillMaxWidth(), color = colours.surface, contentColor = colours.onSurface) {
        Layout(
            contents = listOf(
                { if (navigationIcon != null) Box(contentAlignment = Alignment.Center) { navigationIcon() } },
                {
                    Text(
                        text = title,
                        style = style,
                        maxLines = if (titleMayBeCut) 1 else Int.MAX_VALUE,
                        overflow = if (titleMayBeCut) TextOverflow.Ellipsis else TextOverflow.Clip,
                        modifier = titleModifier
                            .semantics { heading() }
                            .then(if (titleMayBeCut) Modifier.mayTruncate() else Modifier)
                            .then(if (titleIsUserText) Modifier.userText() else Modifier),
                    )
                },
                {
                    if (actions != null) {
                        CompositionLocalProvider(LocalContentColor provides colours.onSurfaceVariant) {
                            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                        }
                    }
                },
            ),
            modifier = Modifier
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(horizontal = BAR_PADDING)
                .semantics { isTraversalGroup = true },
        ) { (navigation, titles, end), constraints ->
            val width = constraints.maxWidth
            val row = BAR_HEIGHT.roundToPx()
            val loose = Constraints(maxWidth = width, maxHeight = row)
            val nav = navigation.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
            val acts = end.firstOrNull()?.measure(loose.copy(maxWidth = (width - (nav?.width ?: 0)).coerceAtLeast(0)))?.takeIf { it.width > 0 }
            val inset = TITLE_INSET.roundToPx()
            val start = nav?.width ?: inset
            val beside = (width - start - (acts?.width ?: inset)).coerceAtLeast(0)
            val below = !titleMayBeCut && (nav != null || acts != null) &&
                !fitsBeside(measurer, title, style, beside, this, layoutDirection)
            val titlePlaceable = titles.first().measure(Constraints(maxWidth = if (below) (width - 2 * inset).coerceAtLeast(0) else beside))
            if (below) {
                val height = row + titlePlaceable.height + BELOW_BOTTOM.roundToPx()
                layout(width, height) {
                    nav?.place(0, (row - nav.height) / 2)
                    acts?.place(width - acts.width, (row - acts.height) / 2)
                    titlePlaceable.place(inset, row)
                }
            } else {
                val height = maxOf(row, titlePlaceable.height + 2 * TITLE_PADDING.roundToPx())
                layout(width, height) {
                    nav?.place(0, (height - nav.height) / 2)
                    acts?.place(width - acts.width, (height - acts.height) / 2)
                    titlePlaceable.place(start, (height - titlePlaceable.height) / 2)
                }
            }
        }
    }
}

/**
 * Whether [title] fits beside the buttons in [width] pixels: on one line, or on two lines with each of
 * its words whole.
 */
private fun fitsBeside(measurer: TextMeasurer, title: String, style: TextStyle, width: Int, density: Density, direction: LayoutDirection): Boolean {
    if (width <= 0) return false
    val twoLines = measurer.measure(title, style, maxLines = 2, constraints = Constraints(maxWidth = width), density = density, layoutDirection = direction)
    if (twoLines.multiParagraph.didExceedMaxLines) return false
    if (twoLines.lineCount == 1) return true
    return title.split(' ', ' ').filter { it.isNotEmpty() }.all { word ->
        measurer.measure(word, style, maxLines = 1, softWrap = false, density = density, layoutDirection = direction).size.width <= width
    }
}

/** Material's small top app bar: 64 dp high, 4 dp from the sides, the title 12 dp further in when no button is beside it. */
private val BAR_HEIGHT = 64.dp
private val BAR_PADDING = 4.dp
private val TITLE_INSET = 12.dp

/** Above and below a title of two lines beside the buttons, and below a title on a row of its own. */
private val TITLE_PADDING = 8.dp
private val BELOW_BOTTOM = 12.dp
