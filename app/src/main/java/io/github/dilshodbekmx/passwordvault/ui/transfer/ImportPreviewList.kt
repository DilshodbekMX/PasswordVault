package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.ImportChange
import io.github.dilshodbekmx.passwordvault.core.formats.ImportLeftOut
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind
import io.github.dilshodbekmx.passwordvault.ui.common.LongLabelShape
import io.github.dilshodbekmx.passwordvault.ui.common.userText

/**
 * The preview of ux.md §5.21, before anything is saved: the sample of names and usernames (never a
 * password) with "Text looks wrong? Read as …", the counts, the rows with the wrong number of values
 * and the switch that imports them, what is left out, the logins kept together with "Keep them
 * separate", "Tidy names" with two examples, the columns, what the importer changed in accounts, and
 * the size limit. "Import 45 accounts" is the screen's bottom button ([ImportScreen]).
 */
@Composable
internal fun ImportPreviewList(vm: ImportViewModel, ui: ImportUi, screen: ImportScreen.Preview, padding: PaddingValues, formatters: Formatters) {
    val p = screen.preview
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(ImportTags.PREVIEW),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (screen.planning) item(key = "planning") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (ui.paused || ui.fileName != null) {
                item(key = "header") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ImportHeader(ui) } }
            }
            if (!p.fits) {
                item(key = "tooLarge") {
                    Banner(text = stringResource(R.string.import_too_large), kind = BannerKind.WARNING, modifier = Modifier.testTag(ImportTags.TOO_LARGE))
                }
            }
            item(key = "sample") { Sample(vm, p) }
            item(key = "counts") { Counts(p, formatters) }
            if (p.wrongRows.isNotEmpty()) item(key = "wrong") { WrongRows(vm, p) }
            if (p.leftOut.isNotEmpty()) item(key = "leftOut") { LeftOut(p, formatters) }
            related(vm, p)
            p.tidy?.let { tidy -> item(key = "tidy") { Tidy(vm, tidy) } }
            if (p.format.isCsv) item(key = "columns") { Columns(vm, p) }
            if (p.changes.isNotEmpty()) changes(p)
            if (p.toAdd == 0) {
                item(key = "nothing") {
                    Text(
                        text = stringResource(R.string.import_nothing_to_add),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(ImportTags.NOTHING_NEW),
                    )
                }
            }
        }
    }
}

@Composable
private fun Sample(vm: ImportViewModel, p: ImportPreview) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(ImportTags.SAMPLE)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.import_sample_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            for (row in p.samples) {
                Column {
                    Text(text = row.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.userText())
                    if (row.username.isNotEmpty()) {
                        // Usernames are laid out left to right in every language (ux.md §3).
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = row.username,
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.userText(),
                            )
                        }
                    }
                }
            }
            p.decoding?.let { decoding ->
                Text(
                    text = encodingLine(decoding),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ImportTags.ENCODING),
                )
                if (decoding.damagedLines > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.import_damaged_lines,
                            decoding.damagedLines,
                            decoding.damagedLines,
                            decoding.firstDamagedLine.toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                decoding.alternative?.let { other ->
                    TextButton(onClick = { vm.readAs(other) }, modifier = Modifier.testTag(ImportTags.READ_AS), shape = LongLabelShape) {
                        Text(text = stringResource(R.string.import_read_as, importEncodingName(other)))
                    }
                }
            }
        }
    }
}

@Composable
private fun encodingLine(d: ImportPreview.DecodingLine): String {
    val name = importEncodingName(d.encoding)
    return when (d.why) {
        TextDecoding.Why.BYTE_ORDER_MARK -> stringResource(R.string.import_encoding_mark, name)
        TextDecoding.Why.NUL_BYTES -> stringResource(R.string.import_encoding_nul, name)
        TextDecoding.Why.VALID_UTF8 -> stringResource(R.string.import_encoding_valid, name)
        TextDecoding.Why.UTF8_SEQUENCES -> stringResource(R.string.import_encoding_mostly, name)
        TextDecoding.Why.NO_UTF8_SEQUENCES -> stringResource(R.string.import_encoding_other, name)
        TextDecoding.Why.CHOSEN -> stringResource(R.string.import_encoding_chosen, name)
    }
}

@Composable
private fun Counts(p: ImportPreview, formatters: Formatters) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = pluralStringResource(R.plurals.import_found, p.found, p.found),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(ImportTags.FOUND).semantics { heading() },
        )
        Line(p.alreadyHere, R.plurals.import_already_here, ImportTags.ALREADY_HERE)
        Line(p.repeated, R.plurals.import_repeated)
        Line(p.conflicts, R.plurals.import_conflicts, ImportTags.CONFLICTS)
        Line(p.withoutPassword, R.plurals.import_without_password)
        if (p.notesCut > 0) {
            Text(
                text = pluralStringResource(R.plurals.import_notes_cut, p.notesCut, p.notesCut, formatters.number(EntryRules.NOTES_MAX.toLong())),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Line(p.emptyRows, R.plurals.import_empty_rows)
        Line(p.removedControls, R.plurals.import_removed_controls)
        Line(p.replacedCharacters, R.plurals.import_replaced_characters)
        if (p.unclosedQuoteLine > 0) {
            Text(text = stringResource(R.string.import_unclosed_quote, p.unclosedQuoteLine), style = MaterialTheme.typography.bodyLarge)
        }
        if (p.newerData) Text(text = stringResource(R.string.import_newer_data), style = MaterialTheme.typography.bodyLarge)
    }
}

/** One count's sentence, when the count is not 0. */
@Composable
private fun Line(count: Int, plural: Int, tag: String? = null) {
    if (count <= 0) return
    Text(
        text = pluralStringResource(plural, count, count),
        style = MaterialTheme.typography.bodyLarge,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
    )
}

@Composable
private fun WrongRows(vm: ImportViewModel, p: ImportPreview) {
    val count = p.wrongRows.size
    Banner(
        title = pluralStringResource(if (p.wrongRowsIncluded) R.plurals.import_wrong_rows_included else R.plurals.import_wrong_rows, count, count),
        text = stringResource(R.string.import_wrong_rows_hint),
        kind = BannerKind.WARNING,
        modifier = Modifier.testTag(ImportTags.WRONG_ROWS),
    )
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in p.wrongRows.take(LISTED)) {
            Text(
                text = pluralStringResource(R.plurals.import_wrong_row, row.values, row.line, row.values, row.expected),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        More(count)
        SwitchRow(
            text = stringResource(R.string.import_wrong_rows_include),
            checked = p.wrongRowsIncluded,
            onCheckedChange = vm::includeWrongRows,
            tag = ImportTags.INCLUDE_WRONG,
        )
    }
}

@Composable
private fun LeftOut(p: ImportPreview, formatters: Formatters) {
    Column(Modifier.testTag(ImportTags.LEFT_OUT), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((kind, rows) in p.leftOut.groupBy { it.kind }) {
            val n = rows.size
            val heading = when (kind) {
                ImportLeftOut.Kind.PASSWORD_TOO_LONG ->
                    pluralStringResource(R.plurals.import_left_out_long, n, n, formatters.number(EntryRules.PASSWORD_MAX.toLong()))
                ImportLeftOut.Kind.NOT_AN_ITEM -> pluralStringResource(R.plurals.import_left_out_not_item, n, n)
                ImportLeftOut.Kind.EMPTY_ITEM -> pluralStringResource(R.plurals.import_left_out_empty, n, n)
                ImportLeftOut.Kind.IN_TRASH -> pluralStringResource(R.plurals.import_left_out_trash, n, n)
                ImportLeftOut.Kind.UNKNOWN_DATA -> null // the line about a newer version's data says it
            } ?: continue
            Text(text = heading, style = MaterialTheme.typography.bodyLarge)
            for (row in rows.take(LISTED)) {
                Text(text = leftOutName(row), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
            }
            More(n)
        }
    }
}

/** A left-out row by its name (a JSON item's), its line (a CSV row's) or its place. */
@Composable
private fun leftOutName(row: ImportPreview.LeftOutRow): String = when {
    row.name != null -> row.name
    row.line > 0 -> stringResource(R.string.import_name_line, row.line)
    else -> stringResource(R.string.import_name_item, row.position)
}

private fun LazyListScope.related(vm: ImportViewModel, p: ImportPreview) {
    val related = p.related
    if (related == null && !p.keepSeparate) return
    item(key = "related") {
        Column(Modifier.testTag(ImportTags.RELATED), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (related != null) {
                Text(
                    text = pluralStringResource(R.plurals.import_related, related.accounts, related.rows, related.accounts),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = { vm.keepSeparate(true) }, modifier = Modifier.testTag(ImportTags.KEEP_SEPARATE)) {
                    Text(text = stringResource(R.string.import_keep_separate))
                }
            } else {
                Text(text = stringResource(R.string.import_kept_separate), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { vm.keepSeparate(false) }, modifier = Modifier.testTag(ImportTags.KEEP_SEPARATE)) {
                    Text(text = stringResource(R.string.import_keep_together))
                }
            }
        }
    }
}

@Composable
private fun Tidy(vm: ImportViewModel, tidy: ImportPreview.Tidy) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(text = stringResource(R.string.import_tidy_names), checked = tidy.on, onCheckedChange = vm::tidyNames, tag = ImportTags.TIDY)
        Text(
            text = stringResource(R.string.import_tidy_names_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (example in tidy.examples) {
            Text(text = stringResource(R.string.import_tidy_example, example.before, example.after), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Columns(vm: ImportViewModel, p: ImportPreview) {
    if (p.ambiguousColumns > 0 && !p.columnsChosen) {
        Banner(text = stringResource(R.string.import_columns_ambiguous)) {
            TextButton(onClick = vm::openColumns, modifier = Modifier.testTag(ImportTags.COLUMNS)) {
                Text(text = stringResource(R.string.import_check_columns))
            }
        }
    } else {
        TextButton(onClick = vm::openColumns, modifier = Modifier.testTag(ImportTags.COLUMNS)) {
            Text(text = stringResource(R.string.import_change_columns))
        }
    }
}

private fun LazyListScope.changes(p: ImportPreview) {
    item(key = "changes") {
        Column(Modifier.testTag(ImportTags.CHANGES), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in p.changes.take(LISTED)) {
                Text(text = stringResource(R.string.import_change, accountName(row.account), changeText(row)), style = MaterialTheme.typography.bodyMedium)
            }
            More(p.changes.size)
        }
    }
}

@Composable
private fun accountName(name: ImportPreview.AccountName): String = when (name) {
    is ImportPreview.AccountName.Title -> name.title
    is ImportPreview.AccountName.Line -> stringResource(R.string.import_name_line, name.line)
    is ImportPreview.AccountName.Item -> stringResource(R.string.import_name_item, name.position)
}

@Composable
private fun changeText(row: ImportPreview.ChangeRow): String = when (row.kind) {
    ImportChange.Kind.PASSWORD_HAS_TAB -> stringResource(R.string.import_change_tab)
    ImportChange.Kind.PASSWORD_HAS_LINE_BREAK -> stringResource(R.string.import_change_line_break)
    ImportChange.Kind.PASSWORD_HAS_CONTROL -> stringResource(R.string.import_change_control)
    ImportChange.Kind.PASSWORD_CHARACTER_REPLACED -> stringResource(R.string.import_change_replaced)
    ImportChange.Kind.TITLE_CUT -> stringResource(R.string.import_change_title_cut)
    ImportChange.Kind.USERNAME_CUT -> stringResource(R.string.import_change_username_cut)
    ImportChange.Kind.WEBSITES_MOVED -> pluralStringResource(R.plurals.import_change_websites, row.count, row.count)
    ImportChange.Kind.LONG_WEBSITES_MOVED -> pluralStringResource(R.plurals.import_change_long_websites, row.count, row.count)
    ImportChange.Kind.FIELDS_MOVED -> pluralStringResource(R.plurals.import_change_fields, row.count, row.count)
    ImportChange.Kind.LONG_FIELDS_MOVED -> pluralStringResource(R.plurals.import_change_long_fields, row.count, row.count)
    ImportChange.Kind.HIDDEN_VALUES_NOT_IMPORTED -> pluralStringResource(R.plurals.import_change_hidden_not_imported, row.count, row.count)
    ImportChange.Kind.NAMELESS_FIELDS_MOVED -> pluralStringResource(R.plurals.import_change_nameless, row.count, row.count)
    ImportChange.Kind.FIELD_NAMES_CUT -> pluralStringResource(R.plurals.import_change_field_names, row.count, row.count)
    ImportChange.Kind.NOTES_CUT -> stringResource(R.string.import_change_notes_cut)
    ImportChange.Kind.PREVIOUS_PASSWORDS_CUT -> pluralStringResource(R.plurals.import_change_history_cut, EntryRules.HISTORY_MAX, EntryRules.HISTORY_MAX)
    ImportChange.Kind.PASSKEYS_NOT_IMPORTED -> stringResource(R.string.import_change_passkeys)
    ImportChange.Kind.LINKED_FIELDS_NOT_IMPORTED -> stringResource(R.string.import_change_linked)
    ImportChange.Kind.PASSWORD_NOT_IMPORTED -> stringResource(R.string.import_change_password_not_text)
    ImportChange.Kind.UNKNOWN_DATA_NOT_KEPT -> stringResource(R.string.import_change_unknown)
}

/** "and 12 more" under a list cut at [LISTED] lines. */
@Composable
private fun More(total: Int) {
    val more = total - LISTED
    if (more <= 0) return
    Text(
        text = pluralStringResource(R.plurals.import_and_more, more, more),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A switch with its label, the whole row one toggle for TalkBack and Switch Access (ux.md §8). */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opts out of autofill, which Compose offers every toggle to (as RevealToggle does).
            .semantics { contentDataType = ContentDataType.None }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** How many lines of a long list the preview shows. */
private const val LISTED = 20
