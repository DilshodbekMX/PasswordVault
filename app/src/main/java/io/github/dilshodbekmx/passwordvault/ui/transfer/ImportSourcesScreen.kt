package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind

/**
 * The first step of the import (ux.md §5.21, "Sources screen"): the four sources, each opening the
 * system file picker for a file of any type, and before it, for Google Password Manager and Chrome,
 * the help that says how to export from Chrome. A chosen file opens the file's step in place of this
 * one (`Route.Import(ImportStep.FILE)`).
 */
@Composable
internal fun ImportSourcesScreen(vm: ImportSourcesViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.watchResults() }
    LaunchedEffect(ui.toFile) {
        if (!ui.toFile) return@LaunchedEffect
        nav.replace(Route.Import(ImportStep.FILE))
        vm.moved()
    }
    BackHandler(enabled = ui.help) { vm.closeHelp() }

    val title = stringResource(if (ui.help) R.string.import_help_title else R.string.import_title)
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = { ImportTopBar(title, onBack = { if (!vm.closeHelp()) nav.back() }) },
    ) { padding ->
        // A page of its own for each step, scrolled from its top: with a large font the sources are longer
        // than the screen, and the help shared their column, opening where the list had been scrolled to,
        // its first steps above the screen (build plan 6.2, review).
        key(ui.help) {
            ImportPage(padding, Modifier.testTag(if (ui.help) ImportTags.HELP else ImportTags.SOURCES)) {
                if (ui.help) GoogleHelp(ui, vm) else Sources(ui, vm)
            }
        }
    }
}

@Composable
private fun Sources(ui: ImportSourcesUi, vm: ImportSourcesViewModel) {
    Text(text = stringResource(R.string.import_sources_intro), style = MaterialTheme.typography.bodyLarge)
    if (ui.noPicker) NoPicker()
    OutlinedCard(Modifier.fillMaxWidth()) {
        SOURCES.forEachIndexed { index, (source, texts) ->
            if (index > 0) HorizontalDivider()
            ListItem(
                headlineContent = { Text(text = stringResource(texts.first)) },
                supportingContent = { Text(text = stringResource(texts.second)) },
                trailingContent = { Icon(painterResource(R.drawable.ic_list_chevron), contentDescription = null) },
                modifier = Modifier
                    .clickable(enabled = !ui.opening, role = Role.Button) { vm.choose(source) }
                    .testTag(ImportTags.source(source)),
            )
        }
    }
    if (ui.opening) PickerOpening()
}

@Composable
private fun GoogleHelp(ui: ImportSourcesUi, vm: ImportSourcesViewModel) {
    Text(text = stringResource(R.string.import_google_steps), style = MaterialTheme.typography.bodyLarge)
    if (ui.moreHelp) {
        Text(
            text = stringResource(R.string.import_google_settings),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(ImportTags.HELP_SETTINGS).semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        TextButton(onClick = vm::showMoreHelp, modifier = Modifier.testTag(ImportTags.HELP_CANT_FIND)) {
            Text(text = stringResource(R.string.import_google_cant_find))
        }
    }
    if (ui.noPicker) NoPicker()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = vm::openPicker,
            enabled = !ui.opening,
            modifier = Modifier.widthIn(min = 200.dp).testTag(ImportTags.CHOOSE_FILE),
        ) {
            Text(text = stringResource(R.string.import_choose_file))
        }
    }
    if (ui.opening) PickerOpening()
}

@Composable
private fun NoPicker() {
    Banner(
        text = stringResource(R.string.import_no_picker),
        kind = BannerKind.WARNING,
        modifier = Modifier.testTag(ImportTags.NO_PICKER).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PickerOpening() {
    val text = stringResource(R.string.import_opening_picker)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = text })
    }
}

/** The sources in the order of ux.md §5.21, with their two lines. */
private val SOURCES: List<Pair<ImportSource, Pair<Int, Int>>> = listOf(
    ImportSource.BACKUP to (R.string.import_source_backup to R.string.import_source_backup_body),
    ImportSource.GOOGLE to (R.string.import_source_google to R.string.import_source_google_body),
    ImportSource.CSV to (R.string.import_source_csv to R.string.import_source_csv_body),
    ImportSource.JSON to (R.string.import_source_json to R.string.import_source_json_body),
)
