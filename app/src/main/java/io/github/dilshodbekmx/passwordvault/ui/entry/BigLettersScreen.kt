package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar

/**
 * "Show in big letters" (ux.md §5.13): a full screen for one password, username or hidden value of
 * up to 128 characters, for reading it aloud or typing it on another device. Each character has
 * its own box, monospace and about 32 sp, with its place number under it; digits and symbols have
 * their own colours (architecture.md D-22); the boxes wrap in rows of 4 to 8, always left to right.
 * A tap on a box marks it and every box before it.
 *
 * - **The screen stays on** while this is open ([KeepScreenOn]).
 * - **It closes** with Close or Back, after 2 minutes without a touch, when the app locks, and when
 *   the screen stops ([OnScreenStopped]); it is never kept or restored (architecture.md D-9d). Every
 *   way out goes through [BigLettersViewModel.close] and leaves once ([BigLettersViewModel.takeClose]).
 *   A rotation keeps it: more boxes fit in a row sideways.
 * - Nothing here is saved state: the value, the mark and the time are in [vm].
 *
 * Back is this screen's own, so that it leaves one step and no more, whatever else closes the
 * screen at the same moment; a full-screen dialog has no predictive back animation (ux.md §4).
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun BigLettersScreen(vm: BigLettersViewModel, nav: Navigator) {
    val ui by vm.state.collectAsStateWithLifecycle()

    fun leave() {
        if (vm.takeClose()) nav.back()
    }

    fun close() {
        vm.close()
        leave()
    }

    KeepScreenOn()
    BackHandler(onBack = ::close)
    OnScreenStopped(whenCovered = false, onStopped = ::close)
    // The 2 minutes passed, the app locked, or there was nothing to show.
    LaunchedEffect(ui.closed) {
        if (ui.closed) leave()
    }

    val title = when (ui.value) {
        EntryValue.Username -> stringResource(R.string.entry_username)
        EntryValue.Password -> stringResource(if (ui.changePending) R.string.entry_big_new_password else R.string.entry_password)
        EntryValue.OldPassword -> stringResource(R.string.entry_big_old_password)
        is EntryValue.Previous -> stringResource(R.string.entry_big_previous)
        is EntryValue.Field -> ui.fieldName.orEmpty()
        // Closed, or a value that is never shown here: the screen is on its way out.
        is EntryValue.Website, EntryValue.Notes, null -> stringResource(R.string.entry_big_letters)
    }
    Scaffold(
        modifier = Modifier
            .semantics {
                paneTitle = title
                testTagsAsResourceId = true
            }
            .onAnyTouch(vm::touched),
        topBar = {
            VaultTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = ::close, modifier = Modifier.testTag(EntryTags.BIG_CLOSE)) {
                        Icon(painterResource(R.drawable.ic_entry_close), contentDescription = stringResource(R.string.common_close))
                    }
                },
            )
        },
    ) { padding ->
        if (ui.closed) return@Scaffold
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val width = minOf(maxWidth, CONTENT_MAX_WIDTH) - CONTENT_PADDING * 2
            val columns = columnsFor(width, with(LocalDensity.current) { CELL_MIN_WIDTH_SP.sp.toDp() })
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(CONTENT_PADDING),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = ui.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.tooLong) {
                    Text(
                        text = stringResource(R.string.entry_big_too_long),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(EntryTags.BIG_TOO_LONG),
                    )
                } else {
                    Cells(ui.cells, columns, ui.markedUpTo, vm::mark)
                    Text(
                        text = stringResource(R.string.entry_big_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The boxes, [columns] to a row, left to right in every language (ux.md §3). Every box of a row
 * has the same width, and the last row keeps that width with empty places.
 */
@Composable
private fun Cells(cells: List<BigCell>, columns: Int, markedUpTo: Int, onMark: (Int) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = Modifier.fillMaxWidth().testTag(EntryTags.BIG_CELLS), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            for (rowStart in cells.indices step columns) {
                Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    for (column in 0 until columns) {
                        val index = rowStart + column
                        if (index < cells.size) {
                            Cell(index, cells[index], marked = index <= markedUpTo, onMark = onMark)
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One box: the character large and monospace, in its kind's colour, and its place under it. A
 * space and a line break are written as small words, and an invisible character as its code, so
 * that no box looks empty. TalkBack hears the place, the kind and the character, and whether the
 * box is marked; the node is sensitive (ux.md §3).
 */
@Composable
private fun RowScope.Cell(index: Int, cell: BigCell, marked: Boolean, onMark: (Int) -> Unit) {
    val place = index + 1
    val description = when (cell.kind) {
        BigCell.Kind.UPPER -> stringResource(R.string.entry_big_cell_upper, place, cell.text)
        BigCell.Kind.LOWER -> stringResource(R.string.entry_big_cell_lower, place, cell.text)
        BigCell.Kind.LETTER -> stringResource(R.string.entry_big_cell_letter, place, cell.text)
        BigCell.Kind.DIGIT -> stringResource(R.string.entry_big_cell_digit, place, cell.text)
        BigCell.Kind.SYMBOL -> stringResource(R.string.entry_big_cell_symbol, place, cell.text)
        BigCell.Kind.SPACE -> stringResource(R.string.entry_big_cell_space, place)
        BigCell.Kind.LINE_BREAK -> stringResource(R.string.entry_big_cell_line_break, place)
        BigCell.Kind.INVISIBLE -> stringResource(R.string.entry_big_cell_invisible, place, cell.text)
    }
    val colour = when (cell.kind) {
        BigCell.Kind.DIGIT -> MaterialTheme.colorScheme.primary
        BigCell.Kind.SYMBOL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (marked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = stringResource(R.string.entry_big_mark)) { onMark(index) }
            .testTag(EntryTags.bigCell(index))
            .clearAndSetSemantics {
                contentDescription = description
                isSensitiveData = true
                selected = marked
            }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // As tall as a large character at any font size, whatever the box shows.
        Box(modifier = Modifier.heightIn(min = with(LocalDensity.current) { CELL_TEXT_HEIGHT_SP.sp.toDp() }), contentAlignment = Alignment.Center) {
            when (cell.kind) {
                BigCell.Kind.SPACE -> SmallWord(stringResource(R.string.entry_big_space))
                BigCell.Kind.LINE_BREAK -> SmallWord(stringResource(R.string.entry_big_line_break))
                BigCell.Kind.INVISIBLE -> SmallWord(cell.text)
                else -> Text(text = cell.text, fontFamily = FontFamily.Monospace, fontSize = CELL_TEXT_SP.sp, color = colour, maxLines = 1, softWrap = false)
            }
        }
        Text(
            text = place.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** What a box says in place of a character that leaves no mark. */
@Composable
private fun SmallWord(word: String) {
    Text(
        text = word,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * How many boxes fit in a row [width] wide when a box needs [cellMinWidth]: as many as fit, but
 * never fewer than 4 nor more than 8 (ux.md §5.13).
 */
internal fun columnsFor(width: Dp, cellMinWidth: Dp): Int {
    val fit = ((width + CELL_GAP) / (cellMinWidth + CELL_GAP)).toInt()
    return fit.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
}

private const val MIN_COLUMNS = 4
private const val MAX_COLUMNS = 8

/** The size of a character in its box: "about 32 sp" (ux.md §5.13). */
private const val CELL_TEXT_SP = 32

/** The height kept for the character: a line of [CELL_TEXT_SP] text. */
private const val CELL_TEXT_HEIGHT_SP = 44

/** A box is at least this wide, in sp so that it grows with the font size: room for the widest letter and a margin. */
private const val CELL_MIN_WIDTH_SP = 52

private val CELL_GAP = 8.dp
private val CONTENT_PADDING = 16.dp
