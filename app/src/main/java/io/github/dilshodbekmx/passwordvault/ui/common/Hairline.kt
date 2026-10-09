package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A line of 1 dp along the top edge, in [colour], over what is drawn there; nothing when [colour] is
 * null. For the navigation bar of a style whose bar has nearly the colour of the screen above it:
 * Emerald in light, whose bar is white on a nearly white screen (`Look.barOutline`,
 * docs/design/looks/README.md).
 */
fun Modifier.topHairline(colour: Color?): Modifier =
    if (colour == null) {
        this
    } else {
        drawWithContent {
            drawContent()
            drawRect(colour, size = Size(size.width, HAIRLINE.toPx()))
        }
    }

/** One line of the mock-ups: 1 px of a 390 px screen. */
private val HAIRLINE = 1.dp
