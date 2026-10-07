package io.github.dilshodbekmx.passwordvault.ui.entry

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind
import io.github.dilshodbekmx.passwordvault.ui.common.MaskedValue
import io.github.dilshodbekmx.passwordvault.ui.common.RevealToggle

/** What the buttons, the banners and the rows' menus of an account's screen do. Made once per screen. */
internal class EntryActions(
    val setShown: (EntryValue, Boolean) -> Unit,
    val copy: (EntryValue) -> Unit,
    val bigLetters: (EntryValue) -> Unit,
    val openWebsite: (WebsiteRow) -> Unit,
    val edit: () -> Unit,
    val setNotesExpanded: (Boolean) -> Unit,
    val setPreviousExpanded: (Boolean) -> Unit,
    val confirmChange: () -> Unit,
    val revertChange: () -> Unit,
    val checkAgain: () -> Unit,
    val newMasterPassword: () -> Unit,
    val wallMs: () -> Long,
)

/**
 * The content of an account's screen (ux.md §5.12), a `LazyColumn` of `ListItem`s at most 640 dp
 * wide: the whole name; the banners; the username; the password, or the old and the new one while
 * a change is pending; the websites; the notes; the extra fields; "No details yet" for an account
 * with a name only; then, in small type, the dates and "Previous passwords".
 *
 * **A row** shows its label above its value, with the value's buttons at its end. A tap or a long
 * press on the row opens a menu with everything the row can do (copy, show or hide, "Show in big
 * letters", open the website), and the same actions are the row's TalkBack actions, so nothing
 * needs a gesture or a small button (ux.md §8).
 *
 * **Stored secrets are drawn with `Text`** ([MaskedValue] and `SecretText`), never with a
 * read-only text field, which would be an autofill node holding the text [V-P4]; hidden ones are
 * always 8 dots (ux.md §3). The notes can be selected; what is copied from a selection goes through
 * the app's own clipboard (architecture.md D-25).
 *
 * The rows' keys are places in the account, never its texts: a list's keys reach saved state.
 *
 * @param copied the value whose copy button shows a check mark now.
 */
@Composable
internal fun EntryRows(details: EntryDetails, copied: EntryValue?, actions: EntryActions, padding: PaddingValues) {
    val formatters = rememberFormatters()
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().testTag(EntryTags.CONTENT),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "title", contentType = "title") {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() }
                        .testTag(EntryTags.TITLE),
                )
            }
            banners(details, formatters, actions)
            if (details.username.isNotEmpty()) {
                item(key = "username", contentType = "row") { UsernameRow(details.username, copied == EntryValue.Username, actions) }
            }
            items(details.passwords, key = { it.value.field ?: "password" }, contentType = { "row" }) { row ->
                PasswordRow(row, details.changePending, copied == row.value, actions)
            }
            items(details.websites, key = { "website.${it.index}" }, contentType = { "row" }) { row ->
                WebsiteRowItem(row, copied == row.value, actions)
            }
            if (details.notes.isNotEmpty()) {
                item(key = "notes", contentType = "notes") { NotesRow(details.notes, details.notesExpanded, copied == EntryValue.Notes, actions) }
            }
            items(details.fields, key = { "field.${it.index}" }, contentType = { "row" }) { row ->
                FieldRowItem(row, copied == row.value, actions)
            }
            if (details.empty) {
                item(key = "empty", contentType = "empty") { Empty(actions.edit) }
            }
            item(key = "dates", contentType = "dates") { Dates(details, formatters, actions.wallMs) }
            if (details.previousCount > 0) {
                item(key = "previous", contentType = "previous") {
                    PreviousButton(details.previousCount, details.previousExpanded, formatters) { actions.setPreviousExpanded(!details.previousExpanded) }
                }
            }
            items(details.previous, key = { "previous.${it.index}" }, contentType = { "row" }) { row ->
                PreviousRowItem(row, formatters, copied == row.value, actions)
            }
        }
    }
}

/** The banners of ux.md §5.12: a pending change, the master password's twin, "left out of the security check". */
private fun LazyListScope.banners(details: EntryDetails, formatters: Formatters, actions: EntryActions) {
    val started = details.changeStartedAtMs
    if (started != null) {
        item(key = "banner.change", contentType = "banner") {
            Banner(
                text = stringResource(R.string.entry_banner_change, formatters.dayMonth(started)),
                modifier = BANNER.testTag(EntryTags.BANNER_CHANGE),
                actions = {
                    TextButton(onClick = actions.revertChange, modifier = Modifier.testTag(EntryTags.BANNER_CHANGE_NO)) {
                        Text(stringResource(R.string.entry_banner_change_no))
                    }
                    TextButton(onClick = actions.confirmChange, modifier = Modifier.testTag(EntryTags.BANNER_CHANGE_YES)) {
                        Text(stringResource(R.string.entry_banner_change_yes))
                    }
                },
            )
        }
    }
    if (details.sameAsMasterPassword) {
        item(key = "banner.master", contentType = "banner") {
            Banner(
                text = stringResource(R.string.entry_banner_master),
                modifier = BANNER.testTag(EntryTags.BANNER_MASTER),
                kind = BannerKind.WARNING,
                actions = {
                    Button(onClick = actions.newMasterPassword, modifier = Modifier.testTag(EntryTags.BANNER_MASTER_CHOOSE)) {
                        Text(stringResource(R.string.entry_banner_master_choose))
                    }
                },
            )
        }
    }
    if (details.checkIgnored) {
        item(key = "banner.leftOut", contentType = "banner") {
            Banner(
                text = stringResource(R.string.entry_banner_left_out),
                modifier = BANNER.testTag(EntryTags.BANNER_LEFT_OUT),
                actions = {
                    TextButton(onClick = actions.checkAgain, modifier = Modifier.testTag(EntryTags.BANNER_CHECK_AGAIN)) {
                        Text(stringResource(R.string.entry_banner_check_again))
                    }
                },
            )
        }
    }
}

/** The username: its text, always left to right (ux.md §3), and "Copy username". */
@Composable
private fun UsernameRow(username: String, copied: Boolean, actions: EntryActions) {
    val value = EntryValue.Username
    val copy = stringResource(R.string.entry_copy_username)
    val big = stringResource(R.string.entry_big_letters)
    ValueRow(
        value = value,
        label = stringResource(R.string.entry_username),
        menu = listOf(RowMenuItem(copy) { actions.copy(value) }, RowMenuItem(big) { actions.bigLetters(value) }),
        trailing = { CopyButton(value, copied, copy, primary = false, actions.copy) },
    ) {
        Text(text = username, style = leftToRight(), modifier = Modifier.testTag(EntryTags.value(value)))
    }
}

/**
 * A password row: 8 dots, "Show password", "Copy password" and, under it, "Show in big letters".
 * While a change is pending there are two, each with its own buttons: the old password, which the
 * website may still use, and the new one, which is not confirmed yet (ux.md §5.12, §5.17).
 */
@Composable
private fun PasswordRow(row: SecretRow, changePending: Boolean, copied: Boolean, actions: EntryActions) {
    val old = row.value == EntryValue.OldPassword
    SecretValueRow(
        value = row.value,
        shown = row.shown,
        label = stringResource(
            when {
                old -> R.string.entry_old_password
                changePending -> R.string.entry_new_password
                else -> R.string.entry_password
            },
        ),
        hiddenDescription = stringResource(
            when {
                old -> R.string.entry_old_password_hidden
                changePending -> R.string.entry_new_password_hidden
                else -> R.string.entry_password_hidden
            },
        ),
        showLabel = stringResource(R.string.common_show_password),
        hideLabel = stringResource(R.string.common_hide_password),
        copyLabel = stringResource(
            when {
                old -> R.string.entry_copy_old_password
                changePending -> R.string.entry_copy_new_password
                else -> R.string.entry_copy_password
            },
        ),
        copied = copied,
        password = true,
        primary = true,
        actions = actions,
    )
}

/** One of the previous passwords: the day it was replaced, dots, show and copy (ux.md §5.12). */
@Composable
private fun PreviousRowItem(row: PreviousRow, formatters: Formatters, copied: Boolean, actions: EntryActions) {
    SecretValueRow(
        value = row.value,
        shown = row.shown,
        label = stringResource(R.string.entry_previous_until, formatters.date(row.replacedAtMs)),
        hiddenDescription = stringResource(R.string.entry_previous_hidden),
        showLabel = stringResource(R.string.common_show_password),
        hideLabel = stringResource(R.string.common_hide_password),
        copyLabel = stringResource(R.string.entry_copy_previous),
        copied = copied,
        password = true,
        primary = false,
        actions = actions,
    )
}

/**
 * An extra field: its label and its value. A hidden one is dots with show and copy, like a
 * password; any other is plain text with copy (ux.md §5.12).
 */
@Composable
private fun FieldRowItem(row: FieldRow, copied: Boolean, actions: EntryActions) {
    val value = row.value
    val copy = stringResource(R.string.entry_copy_named, row.name)
    if (row.secret && !row.empty) {
        SecretValueRow(
            value = value,
            shown = row.shown,
            label = row.name,
            hiddenDescription = stringResource(R.string.entry_field_hidden, row.name),
            showLabel = stringResource(R.string.entry_show_named, row.name),
            hideLabel = stringResource(R.string.entry_hide_named, row.name),
            copyLabel = copy,
            copied = copied,
            password = false,
            primary = false,
            actions = actions,
        )
    } else {
        ValueRow(
            value = value,
            label = row.name,
            menu = if (row.empty) emptyList() else listOf(RowMenuItem(stringResource(R.string.entry_copy)) { actions.copy(value) }),
            trailing = { if (!row.empty) CopyButton(value, copied, copy, primary = false, actions.copy) },
        ) {
            Text(text = row.shown.orEmpty(), modifier = Modifier.testTag(EntryTags.value(value)))
        }
    }
}

/**
 * A website: its host in bold and the whole address under it, both left to right (ux.md §3), with
 * "Open" for an `http` or `https` address and "Copy". An app's address is "App: com.example" with
 * "Copy" only (ux.md §5.12).
 */
@Composable
private fun WebsiteRowItem(row: WebsiteRow, copied: Boolean, actions: EntryActions) {
    val value = row.value
    val web = row.kind == WebsiteKind.WEB
    val app = row.kind == WebsiteKind.APP
    val copy = stringResource(R.string.entry_copy)
    val open = stringResource(R.string.entry_open_website)
    ValueRow(
        value = value,
        label = stringResource(R.string.entry_website),
        menu = buildList {
            if (web) add(RowMenuItem(open) { actions.openWebsite(row) })
            add(RowMenuItem(copy) { actions.copy(value) })
        },
        supporting = if (app || row.address == row.host) {
            null
        } else {
            { Text(text = row.address, style = leftToRight()) }
        },
        trailing = {
            if (web) {
                IconButton(onClick = { actions.openWebsite(row) }, modifier = Modifier.testTag(EntryTags.open(row.index))) {
                    Icon(
                        painter = painterResource(R.drawable.ic_entry_open),
                        contentDescription = stringResource(R.string.entry_open_in_browser, row.host),
                    )
                }
            }
            CopyButton(value, copied, stringResource(R.string.entry_copy_named, row.host), primary = false, actions.copy)
        },
    ) {
        if (app) {
            Text(text = stringResource(R.string.entry_app, row.host), fontWeight = FontWeight.Bold, modifier = Modifier.testTag(EntryTags.value(value)))
        } else {
            Text(text = row.host, fontWeight = FontWeight.Bold, style = leftToRight(), modifier = Modifier.testTag(EntryTags.value(value)))
        }
    }
}

/**
 * The notes: the whole text, which can be selected; more than 12 lines collapse behind "Show more"
 * (ux.md §5.12). Its copy button copies all of it.
 *
 * The selection is held in a `SelectionState` that is only remembered. `SelectionContainer`'s plain
 * form keeps its state with `rememberSaveable`, and that state's saver writes the selected text
 * into the saved instance state (foundation 1.12.1): account data where only route keys and scroll
 * positions may be (architecture.md §5.6).
 */
@Composable
private fun NotesRow(notes: String, expanded: Boolean, copied: Boolean, actions: EntryActions) {
    var long by remember(notes) { mutableStateOf(false) }
    val selection = remember { SelectionState() }
    ListItem(
        overlineContent = { Text(stringResource(R.string.entry_notes)) },
        headlineContent = {
            Column {
                SelectionContainer(state = selection) {
                    Text(
                        text = notes,
                        maxLines = if (expanded) Int.MAX_VALUE else EntryViewModel.NOTES_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            long = if (expanded) layout.lineCount > EntryViewModel.NOTES_LINES else layout.hasVisualOverflow
                        },
                        modifier = Modifier.testTag(EntryTags.value(EntryValue.Notes)),
                    )
                }
                if (long) {
                    TextButton(onClick = { actions.setNotesExpanded(!expanded) }, modifier = Modifier.testTag(EntryTags.NOTES_MORE)) {
                        Text(stringResource(if (expanded) R.string.entry_show_less else R.string.entry_show_more))
                    }
                }
            }
        },
        trailingContent = { CopyButton(EntryValue.Notes, copied, stringResource(R.string.entry_copy_notes), primary = false, actions.copy) },
        modifier = Modifier.testTag(EntryTags.NOTES),
    )
}

/** "No details yet" and "Edit", for an account with a name and nothing else (ux.md §5.12 "States"). */
@Composable
private fun Empty(onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.entry_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(EntryTags.EMPTY))
        Button(onClick = onEdit, modifier = Modifier.testTag(EntryTags.EMPTY_EDIT)) {
            Text(stringResource(R.string.entry_edit))
        }
    }
}

/** "Password changed 3 days ago", "Added Oct 1, 2026" and "Edited Oct 5, 2026", in small type (ux.md §5.12). */
@Composable
private fun Dates(details: EntryDetails, formatters: Formatters, wallMs: () -> Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag(EntryTags.DATES),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val style = MaterialTheme.typography.bodySmall
        val colour = MaterialTheme.colorScheme.onSurfaceVariant
        details.passwordChangedAtMs?.let { changed ->
            Text(text = stringResource(R.string.entry_password_changed, formatters.relative(changed, wallMs())), style = style, color = colour)
        }
        Text(text = stringResource(R.string.entry_added, formatters.date(details.createdAtMs)), style = style, color = colour)
        details.editedAtMs?.let { edited ->
            Text(text = stringResource(R.string.entry_edited, formatters.date(edited)), style = style, color = colour)
        }
    }
}

/** "Previous passwords (2)": opens and closes their rows. */
@Composable
private fun PreviousButton(count: Int, expanded: Boolean, formatters: Formatters, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp).testTag(EntryTags.PREVIOUS)) {
        Text(stringResource(R.string.entry_previous, formatters.number(count.toLong())))
        Icon(
            painter = painterResource(if (expanded) R.drawable.ic_entry_collapse else R.drawable.ic_entry_expand),
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * A row whose value is a secret: [MaskedValue] (8 dots, or the text in monospace with coloured
 * digits and symbols), the show/hide button and the copy button; for a [password] also "Show in big
 * letters" under it. While the value is hidden TalkBack hears [hiddenDescription] for the whole row
 * ("Password, hidden") and never a length; while it is shown, the label, the value and when it will
 * hide again (ux.md §8).
 *
 * @param primary the row's copy is the screen's main action: a filled tonal button (ux.md §5.12).
 */
@Composable
private fun SecretValueRow(
    value: EntryValue,
    shown: String?,
    label: String,
    hiddenDescription: String,
    showLabel: String,
    hideLabel: String,
    copyLabel: String,
    copied: Boolean,
    password: Boolean,
    primary: Boolean,
    actions: EntryActions,
) {
    val isShown = shown != null
    val hint = stringResource(R.string.entry_shown_hint)
    val big = stringResource(R.string.entry_big_letters)
    ValueRow(
        value = value,
        label = label,
        labelSpoken = isShown,
        menu = listOf(
            RowMenuItem(copyLabel) { actions.copy(value) },
            RowMenuItem(if (isShown) hideLabel else showLabel) { actions.setShown(value, !isShown) },
            RowMenuItem(big) { actions.bigLetters(value) },
        ),
        supporting = if (password && primary) {
            {
                TextButton(
                    onClick = { actions.bigLetters(value) },
                    modifier = Modifier.testTag(EntryTags.big(value)),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(big)
                }
            }
        } else {
            null
        },
        trailing = {
            if (password) {
                RevealToggle(revealed = isShown, onRevealedChange = { actions.setShown(value, it) }, modifier = Modifier.testTag(EntryTags.show(value)))
            } else {
                IconToggleButton(checked = isShown, onCheckedChange = { actions.setShown(value, it) }, modifier = Modifier.testTag(EntryTags.show(value))) {
                    Icon(
                        painter = painterResource(if (isShown) R.drawable.ic_common_visibility_off else R.drawable.ic_common_visibility),
                        contentDescription = if (isShown) hideLabel else showLabel,
                    )
                }
            }
            CopyButton(value, copied, copyLabel, primary, actions.copy)
        },
    ) {
        MaskedValue(
            value = shown.orEmpty(),
            revealed = isShown,
            hiddenDescription = hiddenDescription,
            modifier = Modifier.testTag(EntryTags.value(value)).then(if (isShown) Modifier.semantics { stateDescription = hint } else Modifier),
        )
    }
}

/** One item of a row's menu, which is also one of the row's TalkBack actions. */
private class RowMenuItem(val label: String, val action: () -> Unit)

/**
 * A row of the account: the [label] in small type above the value ([content]), an optional
 * [supporting] line, and the buttons at its end ([trailing]). A tap or a long press opens [menu];
 * its items are also the row's TalkBack actions. The row is one node for TalkBack (label, value),
 * and each button is its own.
 *
 * @param labelSpoken false while the value's own description names the row ("Password, hidden").
 */
@Composable
private fun ValueRow(
    value: EntryValue,
    label: String,
    menu: List<RowMenuItem>,
    labelSpoken: Boolean = true,
    supporting: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val options = stringResource(R.string.entry_row_options)
    Box {
        ListItem(
            overlineContent = { Text(text = label, modifier = if (labelSpoken) Modifier else Modifier.clearAndSetSemantics { }) },
            headlineContent = content,
            supportingContent = supporting,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP), content = trailing)
            },
            modifier = Modifier
                .testTag(EntryTags.row(value))
                .then(
                    if (menu.isEmpty()) {
                        Modifier
                    } else {
                        Modifier
                            .combinedClickable(onClickLabel = options, onLongClick = { open = true }, onClick = { open = true })
                            .semantics {
                                customActions = menu.map { item ->
                                    CustomAccessibilityAction(item.label) {
                                        item.action()
                                        true
                                    }
                                }
                            }
                    },
                ),
        )
        // A menu is a window of its own: its items get their resource ids here.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            menu.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        open = false
                        item.action()
                    },
                    modifier = Modifier.testTag(EntryTags.menuItem(value, index)),
                )
            }
        }
    }
}

/**
 * A row's copy button: a check mark for 1.5 seconds after a copy ([copied], ux.md §6 item 3).
 * [description] names what it copies, for TalkBack ("Copy password").
 */
@Composable
private fun CopyButton(value: EntryValue, copied: Boolean, description: String, primary: Boolean, onCopy: (EntryValue) -> Unit) {
    val icon: @Composable () -> Unit = {
        Icon(
            painter = painterResource(if (copied) R.drawable.ic_common_check else R.drawable.ic_common_copy),
            contentDescription = description,
        )
    }
    if (primary) {
        FilledTonalIconButton(onClick = { onCopy(value) }, modifier = Modifier.testTag(EntryTags.copy(value)), content = icon)
    } else {
        IconButton(onClick = { onCopy(value) }, modifier = Modifier.testTag(EntryTags.copy(value)), content = icon)
    }
}

/** The text style of a username or a website: always left to right (ux.md §3). */
@Composable
private fun leftToRight(): TextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

/** A banner's place among the rows. */
private val BANNER = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

/** Between two buttons at the end of a row: 8 dp between touch targets (ux.md §8). */
private val BUTTON_GAP = 8.dp
