package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.isSpecified

/**
 * Where an outlined field's label goes (i18n.md §4.5 rule 2: labels may take two lines). Material
 * lays the label on the field's border and keeps room for one short line there; a label of two lines
 * (a long label in Uzbek or Russian, or the phone's font at 200 %) then reaches up over whatever is
 * above the field. So a field starts with its label on the border, and the first time its label
 * needs more than one line it moves the label above the box ([TextFieldLabelPosition.Above]), where
 * it takes the lines it needs, and keeps it there while the field is shown.
 *
 * The label, [text], also names the field for accessibility services. Compose gives a part that is
 * out of view no text, so a field scrolled to a sliver at the top of the screen, its label above the
 * view, read with no name (ux.md §12 check 9; build plan 8.1's accessibility walk at 320 dp and
 * 200 %). While none of the label is on the screen the field names itself with it ([name], [named]).
 */
internal class FieldLabel(private val place: LabelPlace, private val text: String?) {
    /** The label's place for the field. */
    val position: TextFieldLabelPosition
        get() = if (place.above) TextFieldLabelPosition.Above() else TextFieldLabelPosition.Attached()

    /** The label as the field draws it, watched for a second line and for being on the screen; null without a label. */
    val label: (@Composable TextFieldLabelScope.() -> Unit)? = text?.let { words ->
        {
            val style = LocalTextStyle.current
            val density = LocalDensity.current
            val line = with(density) { if (style.lineHeight.isSpecified) style.lineHeight.toPx() else style.fontSize.toPx() * LINE_OF_FONT }
            Box(
                Modifier
                    .onSizeChanged { if (!place.above && it.height > line * MORE_THAN_ONE_LINE) place.above = true }
                    .onGloballyPositioned { place.inView = !it.boundsInWindow().isEmpty },
            ) { Text(text = words) }
        }
    }

    /** The field's own name while none of its label is on the screen; null while the label names it, or without a label. */
    val name: String?
        get() = text.takeUnless { place.inView }

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

    /** Whether any of the label is on the screen (its bounds in the window, cut by what clips it, are not empty). */
    var inView by mutableStateOf(true)
}

/** The [FieldLabel] of a field with the label [text] (the text as it is now, the place as it was). */
@Composable
internal fun rememberFieldLabel(text: String?): FieldLabel = FieldLabel(remember { LabelPlace() }, text)

/**
 * A field's own name, [name] (null: none), for accessibility services ([FieldLabel.name]): its content
 * description, which Compose gives the field's node itself (a part of it with the field's bounds), so
 * that TalkBack and Switch Access name the field wherever a sliver of it shows. Only while the label
 * is out of view: where the label names the field, Compose would report a node's description in place
 * of its text and its cursor, so that a screen reader could no longer tell where the cursor is or move
 * it by character (tools/emu/window/a11y.sh), and the name would be read twice. Once the field is
 * scrolled back into view, its label names it again.
 */
internal fun Modifier.named(name: String?): Modifier = if (name == null) this else semantics { contentDescription = name }
