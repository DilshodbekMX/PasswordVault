package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The shape of a button whose label may take several lines (`Button`, `OutlinedButton` or
 * `TextButton` with `shape = LongLabelShape`): Material's fully rounded ends while the button holds
 * one line (up to 56 dp high, a label in `labelLarge` at 200 %), and corners of 16 dp when it is
 * higher.
 *
 * Material's buttons clip their label to their shape, and fully rounded ends are as large as half the
 * button's height: a label of several lines (a long Uzbek or Russian label, or the phone's font at
 * 200 %) reaches into them, and they cut off the corners of its first and last lines, a text button's
 * from three lines on, a filled or outlined button's (more padding) from four (build plan 6.2, review:
 * "Show in big letters" lost the start of its first and last lines at 320 dp and 200 %; the layout
 * walk's `LayoutProbe` finds such a cut). Corners of 16 dp stay clear of a label even with the 4 dp
 * of padding of "Show in big letters". A text button draws no container: only its ripple and its focus
 * outline take the shape.
 */
val LongLabelShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val ends = size.minDimension / 2
        val radius = if (size.height <= with(density) { ONE_LINE.toPx() }) ends else minOf(ends, with(density) { CORNERS.toPx() })
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
    }

    override fun toString(): String = "LongLabelShape"
}

/** The highest a button of one line is: a label of `labelLarge` at 200 % and its padding. */
private val ONE_LINE = 56.dp

/** The corners of a higher button. */
private val CORNERS = 16.dp
