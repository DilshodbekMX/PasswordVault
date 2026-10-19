package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind
import io.github.dilshodbekmx.passwordvault.ui.common.MaskedValue
import io.github.dilshodbekmx.passwordvault.ui.common.mayTruncate

/**
 * The column-mapping screen (`Route.ColumnMapping`; ux.md §5.21, formats.md §8.3): each column of the
 * CSV file with its name, its first value and a drop-down list of what it holds (Name · Website ·
 * Username · Password · Notes · Skip, and "Extra field"), then "Continue". It opens by itself when no
 * column is a password column, and once when a column's meaning was taken by another; "Check
 * columns" and "Change columns" on the preview open it too.
 *
 * The first value of the column chosen as the password is drawn as dots and never named to
 * accessibility services; every first value is marked sensitive and laid out left to right.
 */
@Composable
internal fun ColumnMappingScreen(vm: ImportColumnsViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val formatters = rememberFormatters()
    val texts = remember(resources, formatters) { ImportTexts(resources, formatters) }
    LaunchedEffect(vm, texts) { vm.start(texts) }
    LaunchedEffect(ui.leave) {
        if (!ui.leave) return@LaunchedEffect
        nav.back()
        vm.left()
    }
    val title = stringResource(R.string.import_columns_title)
    val screen = ui.screen
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = { ImportTopBar(title, onBack = { nav.back() }) },
        bottomBar = {
            if (screen is ColumnsScreen.Shown) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        // 24 dp under the button keeps its middle above the band where the navigation bar
                        // may be, which the emulator scripts do not tap (tools/emu/app.sh, pva_tap).
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(onClick = vm::confirm, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(ImportTags.MAPPING_CONTINUE)) {
                            Text(text = stringResource(R.string.import_columns_continue))
                        }
                    }
                }
            }
        },
    ) { padding ->
        ImportPage(padding, Modifier.testTag(ImportTags.MAPPING)) {
            when (screen) {
                ColumnsScreen.Reading -> ImportProgress(stringResource(R.string.import_reading))
                is ColumnsScreen.Shown -> Columns(vm, screen)
                ColumnsScreen.Gone, ColumnsScreen.Locked -> Unit
            }
        }
    }
}

@Composable
private fun Columns(vm: ImportColumnsViewModel, screen: ColumnsScreen.Shown) {
    val intro = when (screen.reason) {
        MappingReason.NO_PASSWORD -> R.string.import_columns_no_password
        MappingReason.AMBIGUOUS -> R.string.import_columns_ambiguous
        MappingReason.CHECK -> R.string.import_columns_check
    }
    Text(text = stringResource(intro), style = MaterialTheme.typography.bodyLarge)
    screen.columns.forEachIndexed { index, column -> ColumnCard(index, column, onChoose = { vm.choose(index, it) }) }
    if (!screen.hasPassword) {
        Banner(
            text = stringResource(R.string.import_columns_none_is_password),
            kind = BannerKind.WARNING,
            modifier = Modifier.testTag(ImportTags.MAPPING_NO_PASSWORD),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class) // ExposedDropdownMenuBox (ux.md §10)
@Composable
private fun ColumnCard(index: Int, column: MappingColumn, onChoose: (ColumnMeaning) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = column.header.ifEmpty { stringResource(R.string.import_name_item, index + 1) },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            FirstValue(index, column)
            var open by remember { mutableStateOf(false) }
            val holds = stringResource(R.string.import_columns_holds)
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                // The anchor is not a text field: nothing is typed here, only chosen.
                OutlinedCard(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .testTag(ImportTags.column(index)),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text = holds, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = meaningText(column.meaning), style = MaterialTheme.typography.bodyLarge)
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
                    }
                }
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (meaning in column.options) {
                        DropdownMenuItem(
                            text = { Text(text = meaningText(meaning)) },
                            onClick = {
                                onChoose(meaning)
                                open = false
                            },
                            modifier = Modifier.testTag(ImportTags.meaning(meaning)),
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The column's first value under the words "First value": dots for the password column (and a
 * one-time code key), otherwise the value, left to right as usernames and websites are (ux.md §3) and
 * marked sensitive, since any column may hold a secret.
 */
@Composable
private fun FirstValue(index: Int, column: MappingColumn) {
    val modifier = Modifier.testTag(ImportTags.sample(index))
    if (column.sample.isEmpty()) {
        Text(
            text = stringResource(R.string.import_columns_no_value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column {
        Text(
            text = stringResource(R.string.import_columns_first_value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (column.meaning == ColumnMeaning.PASSWORD || column.meaning == ColumnMeaning.TOTP) {
            MaskedValue(
                value = column.sample,
                revealed = false,
                hiddenDescription = stringResource(R.string.import_columns_hidden_value),
                modifier = modifier,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = column.sample,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // A sample of the file's column, which may be cut: it only shows what the column holds.
                    modifier = modifier.semantics { isSensitiveData = true }.mayTruncate(),
                )
            }
        }
    }
}

/** The name of [meaning] in the drop-down lists. */
@Composable
private fun meaningText(meaning: ColumnMeaning): String = stringResource(
    when (meaning) {
        ColumnMeaning.TITLE -> R.string.import_meaning_title
        ColumnMeaning.WEBSITE -> R.string.import_meaning_website
        ColumnMeaning.USERNAME -> R.string.import_meaning_username
        ColumnMeaning.PASSWORD -> R.string.import_meaning_password
        ColumnMeaning.NOTES -> R.string.import_meaning_notes
        ColumnMeaning.FIELD -> R.string.import_meaning_field
        ColumnMeaning.SKIP -> R.string.import_meaning_skip
        ColumnMeaning.FOLDER -> R.string.import_meaning_folder
        ColumnMeaning.TOTP -> R.string.import_meaning_totp
        ColumnMeaning.FAVOURITE -> R.string.import_meaning_favourite
        ColumnMeaning.CREATED -> R.string.import_meaning_created
        ColumnMeaning.MODIFIED -> R.string.import_meaning_modified
        ColumnMeaning.PASSWORD_CHANGED -> R.string.import_meaning_password_changed
        ColumnMeaning.SECOND_WEBSITE -> R.string.import_meaning_second_website
        ColumnMeaning.BITWARDEN_FIELDS -> R.string.import_meaning_bitwarden_fields
        ColumnMeaning.BITWARDEN_TYPE -> R.string.import_meaning_bitwarden_type
    },
)
