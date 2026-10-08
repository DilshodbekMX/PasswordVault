package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.search.matchedRanges
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook

/**
 * What a row's taps, its menu and its TalkBack actions do (ux.md §5.11). Made once per screen.
 * The copy actions take the row's key in the list, so that the check mark shows on the button that
 * was tapped when an account is listed twice (as a favourite and A to Z).
 */
internal class RowActions(
    val open: (EntryRow) -> Unit,
    val copyPassword: (EntryRow, String) -> Unit,
    val copyOldPassword: (EntryRow, String) -> Unit,
    val copyNewPassword: (EntryRow, String) -> Unit,
    val copyUsername: (EntryRow) -> Unit,
    val openWebsite: (EntryRow) -> Unit,
    val bigLetters: (EntryRow) -> Unit,
    val favourite: (EntryRow) -> Unit,
    val edit: (EntryRow) -> Unit,
    val delete: (EntryRow) -> Unit,
)

/**
 * One account of the list (ux.md §5.11, §8): the badge with its first letter, the title (up
 * to two lines), the username or the first website's host (one line, always left to right), a
 * clock while a password change is pending, and the copy button.
 *
 * - A tap opens the entry; a long press opens the menu (Copy username · Copy password · Open
 *   website · Show in big letters · Add to favourites or Remove from favourites · Edit · Delete),
 *   whose items are also the row's TalkBack actions (no gesture-only action, ux.md §8).
 * - While the password change is pending the copy button and the menu offer the old and the new
 *   password instead (ux.md §5.17).
 * - [searched] is the query whose matches are bold, or null outside a search. `matchedRanges` runs
 *   here, so only for the rows on screen (search.md §5).
 *
 * @param key the row's key in the list, which the copy button's tag and [copied] refer to.
 * @param modifier for the row itself, the merged node that TalkBack reads.
 */
@Composable
internal fun EntryRowItem(row: EntryRow, searched: String?, copied: Boolean, key: String, actions: RowActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val title = remember(row.title, searched) { bold(row.title, searched) }
    val detail = remember(row.detail, searched) { bold(row.detail, searched) }
    val labels = RowLabels(
        copyUsername = stringResource(R.string.list_menu_copy_username),
        copyPassword = stringResource(R.string.list_menu_copy_password),
        copyOld = stringResource(R.string.list_menu_copy_old_password),
        copyNew = stringResource(R.string.list_menu_copy_new_password),
        openWebsite = stringResource(R.string.list_menu_open_website),
        bigLetters = stringResource(R.string.list_menu_big_letters),
        favourite = stringResource(if (row.favourite) R.string.list_menu_remove_favourite else R.string.list_menu_add_favourite),
        edit = stringResource(R.string.list_menu_edit),
        delete = stringResource(R.string.common_delete),
    )
    Box {
        ListItem(
            headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (row.detail.isEmpty()) {
                null
            } else {
                {
                    Text(
                        text = detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    )
                }
            },
            leadingContent = { InitialBadge(row) },
            trailingContent = { RowEnd(row, copied, key, actions) },
            // The merged node that TalkBack reads ("Gmail, aziz@gmail.com"), with the row's actions.
            modifier = modifier
                .combinedClickable(onLongClick = { menu = true }, onClick = { actions.open(row) })
                .semantics { customActions = rowActionsFor(row, key, actions, labels) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((label, action) in menuItems(row, key, actions, labels)) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menu = false
                        action()
                    },
                )
            }
        }
    }
}

/** The clock of a pending change and the copy button, with its old-or-new menu while a change is pending (ux.md §5.17). */
@Composable
private fun RowEnd(row: EntryRow, copied: Boolean, key: String, actions: RowActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.changePending) {
            Icon(
                painter = painterResource(R.drawable.ic_list_schedule),
                contentDescription = stringResource(R.string.list_change_pending),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.hasPassword) {
            var choose by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { if (row.changePending) choose = true else actions.copyPassword(row, key) },
                    modifier = Modifier.testTag(ListTags.copy(key)),
                ) {
                    Icon(
                        painter = painterResource(if (copied) R.drawable.ic_common_check else R.drawable.ic_common_copy),
                        contentDescription = if (row.changePending) {
                            stringResource(R.string.list_copy_password_choose, row.title)
                        } else {
                            stringResource(R.string.list_copy_password_for, row.title)
                        },
                    )
                }
                DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.list_menu_copy_old_password)) },
                        onClick = {
                            choose = false
                            actions.copyOldPassword(row, key)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.list_menu_copy_new_password)) },
                        onClick = {
                            choose = false
                            actions.copyNewPassword(row, key)
                        },
                    )
                }
            }
        }
    }
}

/** The texts of a row's menu, resolved in the composition's language. */
private class RowLabels(
    val copyUsername: String,
    val copyPassword: String,
    val copyOld: String,
    val copyNew: String,
    val openWebsite: String,
    val bigLetters: String,
    val favourite: String,
    val edit: String,
    val delete: String,
)

/** The items of a row's menu that apply to it, in the order of ux.md §5.11. */
private fun menuItems(row: EntryRow, key: String, actions: RowActions, labels: RowLabels): List<Pair<String, () -> Unit>> = buildList {
    if (row.hasUsername) add(labels.copyUsername to { actions.copyUsername(row) })
    if (row.hasPassword) {
        if (row.changePending) {
            add(labels.copyOld to { actions.copyOldPassword(row, key) })
            add(labels.copyNew to { actions.copyNewPassword(row, key) })
        } else {
            add(labels.copyPassword to { actions.copyPassword(row, key) })
        }
    }
    if (row.website != null) add(labels.openWebsite to { actions.openWebsite(row) })
    if (row.hasPassword) add(labels.bigLetters to { actions.bigLetters(row) })
    add(labels.favourite to { actions.favourite(row) })
    add(labels.edit to { actions.edit(row) })
    add(labels.delete to { actions.delete(row) })
}

/** The same items as TalkBack's custom actions on the row (ux.md §8). */
private fun rowActionsFor(row: EntryRow, key: String, actions: RowActions, labels: RowLabels): List<CustomAccessibilityAction> =
    menuItems(row, key, actions, labels).map { (label, action) ->
        CustomAccessibilityAction(label) {
            action()
            true
        }
    }

/**
 * The badge with the title's first letter (ux.md §5.11): one of eight tones by a hash of the title.
 * The tones and the badge's shape are the style's (docs/design/looks/README.md "Avatars", read from
 * `LocalLook`): a circle, or a square with rounded corners, and in every tone the letter has a
 * contrast of at least 4.5:1 on the badge. It grows with the font size, and TalkBack does not read
 * it: the title says the same.
 */
@Composable
private fun InitialBadge(row: EntryRow) {
    val size = with(LocalDensity.current) { maxOf(BADGE_SIZE, BADGE_SIZE.value.sp.toDp()) }
    val look = LocalLook.current
    val tone = look.avatarTones[row.tone.mod(look.avatarTones.size)]
    Box(
        modifier = Modifier
            .size(size)
            .clip(look.avatarShape)
            .background(tone.background)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(row.initial, color = tone.letter, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/** [text] with the parts that [query] matched in bold (search.md §2 `matchedRanges`); plain without a query. */
internal fun bold(text: String, query: String?): AnnotatedString {
    if (query.isNullOrBlank() || text.isEmpty()) return AnnotatedString(text)
    val ranges = matchedRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (range in ranges) addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
    }
}

private val BADGE_SIZE = 40.dp
