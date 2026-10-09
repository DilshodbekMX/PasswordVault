package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.ui.theme.Look

/**
 * Where an item lies in a group of items drawn on one card (docs/design/looks/README.md "List and
 * account screen"; `Look.cards`): the card's corners are rounded where it starts and where it ends.
 * The items of a list are drawn one by one, so each draws its own part of the card.
 */
enum class CardPart {
    /** The first of several: rounded above. */
    FIRST,

    /** Between the first and the last: square. */
    MIDDLE,

    /** The last of several: rounded below. */
    LAST,

    /** The only one: rounded above and below. */
    ONLY,
    ;

    /** The card starts here. */
    val starts: Boolean get() = this == FIRST || this == ONLY

    /** The card ends here. */
    val ends: Boolean get() = this == LAST || this == ONLY

    companion object {
        /** The part of the item at [index] of a group of [count] items. */
        fun of(index: Int, count: Int): CardPart {
            require(index in 0 until count) { "item $index of $count" }
            return when {
                count == 1 -> ONLY
                index == 0 -> FIRST
                index == count - 1 -> LAST
                else -> MIDDLE
            }
        }

        /** The parts of a group of [count] items, in order. */
        fun group(count: Int): List<CardPart> = List(count) { of(it, count) }
    }
}

/**
 * This item's [part] of a card in [look] (README "List and account screen"): 12 dp in from the
 * screen's edges, the look's card colour behind the item, corners of 20 dp where the card starts and
 * ends, the look's card outline (Night vault's password block) along the card's edges, and 4 dp of
 * room inside the card above its first item and below its last. A null [part]: nothing, the item
 * lies on the screen.
 */
fun Modifier.cardPart(part: CardPart?, look: Look): Modifier {
    if (part == null) return this
    val colour = look.cardColour
    val outline = look.cardOutline
    val top = if (part.starts) CARD_CORNER else 0.dp
    val bottom = if (part.ends) CARD_CORNER else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    return this
        .padding(horizontal = CARD_MARGIN)
        .drawBehind {
            drawOutline(shape.createOutline(size, layoutDirection, this), colour)
            if (outline != null) {
                // The sides always; the top and the bottom edge only where the card starts and ends:
                // past an edge where the card goes on, the outline is drawn beyond the item and clipped.
                val stroke = CARD_OUTLINE.toPx()
                val above = if (part.starts) 0f else stroke
                val below = if (part.ends) 0f else stroke
                val box = Size(size.width - stroke, size.height - stroke + above + below)
                clipRect {
                    translate(left = stroke / 2, top = stroke / 2 - above) {
                        drawOutline(shape.createOutline(box, layoutDirection, this), outline, style = Stroke(stroke))
                    }
                }
            }
        }
        .padding(top = if (part.starts) CARD_INSET else 0.dp, bottom = if (part.ends) CARD_INSET else 0.dp)
}

/** From the screen's edges to a card's (the mock-ups' 12 px). */
private val CARD_MARGIN = 12.dp

/** A card's corners (README: radius 20 dp). */
private val CARD_CORNER = 20.dp

/** The room inside a card above its first item and below its last (the mock-ups' 4 px). */
private val CARD_INSET = 4.dp

/** Night vault's card outline (the mock-up's 1 px). */
private val CARD_OUTLINE = 1.dp
