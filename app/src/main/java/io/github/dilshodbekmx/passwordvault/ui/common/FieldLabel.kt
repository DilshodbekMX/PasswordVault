package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.isSpecified

/**
 * Where an outlined field's label goes (i18n.md §4.5 rule 2: labels may take two lines). Material
 * lays the label on the field's border and keeps room for one short line there; a label of two lines
 * (a long label in Uzbek or Russian, or the phone's font at 200 %) then reaches up over whatever is
 * above the field. So a field starts with its label on the border, and the first time its label
 * needs more than one line it moves the label above the box ([TextFieldLabelPosition.Above]), where
 * it takes the lines it needs, and keeps it there while the field is shown.
 */
internal class FieldLabel(private val place: LabelPlace, slot: (@Composable () -> Unit)?) {
    /** The label's place for the field. */
    val position: TextFieldLabelPosition
        get() = if (place.above) TextFieldLabelPosition.Above() else TextFieldLabelPosition.Attached()

    /** The label as the field draws it, watched for a second line; null without a label. */
    val label: (@Composable TextFieldLabelScope.() -> Unit)? = slot?.let { content ->
        {
            val style = LocalTextStyle.current
            val density = LocalDensity.current
            val line = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else style.fontSize.toPx() * LINE_OF_FONT }
            Box(Modifier.onSizeChanged { if (!place.above && it.height > line * MORE_THAN_ONE_LINE) place.above = true }) { content() }
        }
    }

    private companion object {
        /** A line's height for a style without one, as a share of the font size. */
        const val LINE_OF_FONT = 1.4f

        /** A label taller than this many lines has more than one. */
        const val MORE_THAN_ONE_LINE = 1.5f
    }
}

/** Whether a field's label has moved above its box: kept while the field is shown. */
@Stable
internal class LabelPlace {
    var above by mutableStateOf(false)
}

/** The [FieldLabel] of a field with the label [slot] (the slot as it is now, the place as it was). */
@Composable
internal fun rememberFieldLabel(slot: (@Composable () -> Unit)?): FieldLabel = FieldLabel(remember { LabelPlace() }, slot)
