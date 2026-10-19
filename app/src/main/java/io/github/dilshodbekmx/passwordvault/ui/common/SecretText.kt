package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection

/**
 * A stored secret, shown: a password, a previous password or a secret field's value (ux.md §5.12).
 * Drawn with `Text`, never with a read-only text field, which would be an autofill node holding the
 * text [V-P4]; not selectable (copying goes through the copy button and `SecretClipboard`).
 *  - Monospace, with digits and symbols in their own colours, so that similar characters can be
 *    told apart (ux.md §5.12, architecture.md D-22). Colour is not the only signal: monospace
 *    gives each character its own cell.
 *  - Always laid out left to right (ux.md §3).
 *  - Never hyphenated: a secret too long for its line wraps between two of its own characters, with
 *    no hyphen drawn that is not in it, although the theme hyphenates the app's texts in every
 *    language (i18n.md §4.5 rule 4; `SecretTextLinesTest`).
 *  - `isSensitiveData`: hidden from accessibility services that are not accessibility tools, on
 *    API 34+; TalkBack, an accessibility tool, still reads it (ux.md §3, §8; [V-P5]).
 *
 * Re-hiding after 30 seconds without a touch is the screen's (ux.md §3); [MaskedValue] switches
 * between this and the mask.
 */
@Composable
fun SecretText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = Int.MAX_VALUE,
) {
    val digits = MaterialTheme.colorScheme.primary
    val symbols = MaterialTheme.colorScheme.tertiary
    val text = remember(value, digits, symbols) { coloured(value, digits, symbols) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            modifier = modifier.semantics { isSensitiveData = true },
            style = style.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr, hyphens = Hyphens.None),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/**
 * A stored secret that is hidden until "Show" is tapped (ux.md §3): always 8 dots, whatever its
 * length. While hidden, nothing of the value is in the node: no text, no length; TalkBack reads
 * [hiddenDescription] ("Password, hidden", ux.md §8) with password semantics. When [revealed], it is
 * [SecretText].
 *
 * @param hiddenDescription the screen's own sentence for this field, such as "Password, hidden"; a
 *   sentence of its own in each language, because the word for "hidden" agrees with the field's
 *   name in Russian.
 */
@Composable
fun MaskedValue(
    value: String,
    revealed: Boolean,
    hiddenDescription: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    if (revealed) {
        SecretText(value = value, modifier = modifier, style = style)
    } else {
        Text(
            text = MASK,
            modifier = modifier.clearAndSetSemantics {
                contentDescription = hiddenDescription
                password()
                isSensitiveData = true
            },
            style = style.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
            maxLines = 1,
        )
    }
}

/** The mask of a hidden secret: always 8 dots (ux.md §3). */
private const val MASK = "••••••••"

/** [value] with its digits in [digits] and everything that is neither a letter, a digit nor a space in [symbols]. */
private fun coloured(value: String, digits: Color, symbols: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < value.length) {
        val codePoint = value.codePointAt(i)
        val end = i + Character.charCount(codePoint)
        when {
            Character.isDigit(codePoint) -> withStyle(SpanStyle(color = digits)) { append(value, i, end) }
            Character.isLetter(codePoint) || Character.isSpaceChar(codePoint) -> append(value, i, end)
            else -> withStyle(SpanStyle(color = symbols)) { append(value, i, end) }
        }
        i = end
    }
}
