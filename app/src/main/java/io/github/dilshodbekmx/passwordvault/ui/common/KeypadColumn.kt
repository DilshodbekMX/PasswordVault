package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The upright page of a keypad (ux.md §5.6, §5.9), inside a column that scrolls and [viewport] high
 * (the height the page shows): [head] (the texts), under it [dots] (the dots and the line under
 * them), the [keys], and [foot] (the buttons), from the top.
 *
 * The keypad is typed into while the dots are watched, so the two stay on the screen together, whole.
 * While the page has room every part has the height it asks for, and the page is as high as the
 * viewport. On a short page, or with the phone's font at 200 %, the keys shrink, to 64 dp
 * ([PinKeys], [MIN_KEYPAD_HEIGHT]), and [head] gets the height that is left, at least [minHead], and
 * scrolls there on its own: so [head] must be a column that scrolls. Where even that leaves no room
 * for [foot], the head, the dots and the keys still fill the first screen and the foot follows below
 * it, reached by scrolling the page; and a page too short for the keys themselves (a small window)
 * is laid out at its full height with the smallest keys, and scrolls as a whole.
 *
 * Found by build plan 6.2: in `en-XA` on a 1080 × 1920 screen, and at 320 dp and 200 %, the keys 7,
 * 8, 9 and 0 of the PIN steps were below the screen's edge, and scrolling to them took the dots away.
 */
@Composable
fun KeypadColumn(
    viewport: Dp,
    head: @Composable () -> Unit,
    dots: @Composable () -> Unit,
    keys: @Composable () -> Unit,
    foot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minHead: Dp = MIN_HEAD,
) {
    Layout(contents = listOf(head, dots, keys, foot), modifier = modifier) { (heads, dotsPart, keyPart, feet), constraints ->
        val width = constraints.maxWidth
        val page = viewport.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = UNLIMITED)
        val footPlaced = feet.map { it.measure(loose) }
        val dotsPlaced = dotsPart.map { it.measure(loose) }
        val footHeight = footPlaced.sumOf { it.height }
        val dotsHeight = dotsPlaced.sumOf { it.height }
        val keypad = MIN_KEYPAD_HEIGHT.roundToPx()
        val head = minHead.roundToPx()
        // The height the head, the dots and the keys share on the first screen: all of it but the
        // foot's while that leaves the keys and the head their least; else all of it, the foot below.
        val first = when {
            page - footHeight - dotsHeight - keypad >= head -> page - footHeight
            page - dotsHeight - keypad >= head -> page
            else -> null
        }
        val keysPlaced = keyPart.map {
            it.measure(loose.copy(maxHeight = if (first != null) (first - dotsHeight - head).coerceAtLeast(0) else keypad))
        }
        val keysHeight = keysPlaced.sumOf { it.height }
        val headConstraints = if (first != null) loose.copy(maxHeight = (first - dotsHeight - keysHeight).coerceAtLeast(0)) else loose
        val headPlaced = heads.map { it.measure(headConstraints) }
        val parts = headPlaced + dotsPlaced + keysPlaced + footPlaced
        val height = if (first == page - footHeight) page.coerceAtLeast(parts.sumOf { it.height }) else parts.sumOf { it.height }
        layout(width, height) {
            var y = 0
            for (placeable in parts) {
                placeable.place((width - placeable.width) / 2, y)
                y += placeable.height
            }
        }
    }
}

/** The least height a keypad page's texts get when the keys shrink: about two lines of its title at 200 %. */
val MIN_HEAD: Dp = 96.dp

/**
 * The most a part of the page is measured with when nothing limits it: finite, because a column that
 * scrolls may not be measured without a limit, and far more than any page.
 */
private const val UNLIMITED = 100_000

/**
 * A fade over the edge where a column that scrolls has more (its texts cut by the edge read as "there
 * is more" rather than as clipped): at the bottom while it can scroll on, at the top while it can scroll
 * back. [color] is what lies behind the column.
 */
fun Modifier.fadeWhereMore(state: ScrollState, color: Color, height: Dp = FADE_HEIGHT): Modifier =
    drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height / 3)
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0f), color), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
            )
        }
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = 0f, endY = fade),
                size = Size(size.width, fade),
            )
        }
    }

private val FADE_HEIGHT = 24.dp
