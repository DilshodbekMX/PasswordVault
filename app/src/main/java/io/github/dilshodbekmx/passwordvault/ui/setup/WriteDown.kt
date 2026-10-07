package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.common.SecretText
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import kotlinx.coroutines.delay

/**
 * "Write it down on paper" (ux.md §5.4; lock.md F1 step 2): the chosen password in large
 * monospace, the suggested words numbered as on the first step, "Show in big letters" (one large
 * character per box, ux.md §5.13), the optional "I wrote it down" and "Next".
 *
 * @param keepScreenOn true in a change or a replacement of the master password (lock.md §10.1,
 *   build plan 5.5): a screen that timed out while the words are copied to paper would lock and
 *   throw the flow away. Setup does not keep it on.
 */
@Composable
fun WriteDownScreen(
    chosen: ChosenPassword,
    wroteItDown: Boolean,
    onWroteItDown: (Boolean) -> Unit,
    onNext: () -> Unit,
    keepScreenOn: Boolean = false,
) {
    if (keepScreenOn) KeepScreenOn()
    val text = remember(chosen) { chosen.text() }
    var bigLetters by remember { mutableStateOf(false) }
    SetupPage(title = stringResource(R.string.setup_write_title)) {
        Text(text = stringResource(R.string.setup_write_body), style = MaterialTheme.typography.bodyLarge)
        val words = chosen.words
        if (words != null) {
            NumberedWords(words)
            WholePassphrase(words)
        } else {
            SecretText(value = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(SetupTags.CHOSEN))
        }
        TextButton(onClick = { bigLetters = true }, modifier = Modifier.testTag(SetupTags.BIG_LETTERS)) {
            Text(stringResource(R.string.setup_write_big_letters))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = wroteItDown, role = Role.Checkbox, onValueChange = onWroteItDown)
                .testTag(SetupTags.WROTE)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = wroteItDown, onCheckedChange = null)
            Text(text = stringResource(R.string.setup_write_wrote), style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag(SetupTags.NEXT)) {
            Text(stringResource(R.string.setup_next))
        }
    }
    if (bigLetters) BigLetters(text, onClose = { bigLetters = false })
}

/**
 * "Show in big letters" for the password [text] (ux.md §5.13): a full-screen dialog with each
 * character in its own box, monospace and large, its position under it, digits and symbols in
 * their own colours; tapping a box marks it and every box before it, to keep the place. The screen
 * stays on while it is open, and it closes after 2 minutes without a touch, with Back or with
 * "Close". It is a dialog of this screen only, so it is never kept after the screen stops.
 */
@Composable
internal fun BigLetters(text: String, onClose: () -> Unit) {
    VaultDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepScreenOn()
        var touches by remember { mutableIntStateOf(0) }
        LaunchedEffect(touches) {
            delay(BIG_LETTERS_IDLE_MS)
            onClose()
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touches++
                        }
                    }
                },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag(SetupTags.BIG_LETTERS_CLOSE)) {
                        Text(stringResource(R.string.common_close))
                    }
                }
                val characters = remember(text) { characters(text) }
                if (characters.size > BIG_LETTERS_MAX) {
                    Text(text = stringResource(R.string.setup_big_too_long), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Cells(characters)
                }
            }
        }
    }
}

/** The boxes of [BigLetters], left to right in every language (ux.md §3). */
@Composable
private fun Cells(characters: List<String>) {
    var markedUpTo by remember { mutableIntStateOf(-1) }
    val digits = MaterialTheme.colorScheme.primary
    val symbols = MaterialTheme.colorScheme.tertiary
    val letters = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            characters.forEachIndexed { index, character ->
                val marked = index <= markedUpTo
                val description = stringResource(R.string.setup_big_cell, index + 1, character)
                val first = character.codePointAt(0)
                val colour = when {
                    Character.isDigit(first) -> digits
                    Character.isLetter(first) -> letters
                    else -> symbols
                }
                Column(
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .background(if (marked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { markedUpTo = index }
                        .clearAndSetSemantics {
                            contentDescription = description
                            isSensitiveData = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = character, fontFamily = FontFamily.Monospace, fontSize = 32.sp, color = colour)
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** [text] as one string per character (code point), so that a character outside the BMP keeps one box. */
internal fun characters(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val next = i + Character.charCount(text.codePointAt(i))
        out += text.substring(i, minOf(next, text.length))
        i = next
    }
    return out
}

/** Keeps the screen on while this is in the composition (`FLAG_KEEP_SCREEN_ON` through the view, lock.md §10.1). */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** "Show in big letters" closes after 2 minutes without a touch (ux.md §5.13). */
private const val BIG_LETTERS_IDLE_MS = 120_000L

/** "Show in big letters" shows at most 128 characters (ux.md §5.13). */
private const val BIG_LETTERS_MAX = 128
