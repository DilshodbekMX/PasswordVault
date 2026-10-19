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
 * The keypad is typed into while the dots are watched, so the two stay on the screen together, whole,
 * and the head's texts say what to type. The page takes the first of these that its height allows:
 *
 * 1. everything on the first screen, the head whole, the keys at their size or smaller, to 64 dp;
 * 2. the head whole and the keys as large as the rest allows (to 64 dp), the [foot] below the first
 *    screen, reached by scrolling the page;
 * 3. the smallest keys, the foot below, and the head given the height that is left, at least
 *    [minHead], scrolling there on its own: so [head] must be a column that scrolls;
 * 4. on a page too short for that (a small window), everything at its least, the page scrolling as a
 *    whole.
 *
 * What the head needs to show whole is its intrinsic height; a head whose parts cannot say (a layout
 * without intrinsic sizes) counts as needing [minHead]. The keys shrink in [PinKeys]
 * ([MIN_KEYPAD_HEIGHT]): the gaps first, to 8 dp, then the keys.
 *
 * Found by build plan 6.2: in `en-XA` on a 1080 × 1920 screen, and at 320 dp and 200 %, the keys 7,
 * 8, 9 and 0 of the PIN steps were below the screen's edge, and scrolling to them took the dots away;
 * then, with the foot kept on the first screen first, the lock pad's "Enter your app PIN" was squeezed
 * out of a 1920-pixel screen at the phone's own font size.
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
        val least = minHead.roundToPx()
        val wanted = heads.sumOf { runCatching { it.maxIntrinsicHeight(width) }.getOrDefault(least) }.coerceAtLeast(least)
        // The height the head, the dots and the keys share on the first screen (null: no limit), and the
        // most the keys may take of it.
        val (first, keysLimit) = when {
            page - footHeight - dotsHeight - wanted >= keypad -> (page - footHeight) to (page - footHeight - dotsHeight - wanted)
            page - dotsHeight - wanted >= keypad -> page to (page - dotsHeight - wanted)
            page - dotsHeight - least >= keypad -> page to keypad
            else -> null to keypad
        }
        val keysPlaced = keyPart.map { it.measure(loose.copy(maxHeight = keysLimit)) }
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
