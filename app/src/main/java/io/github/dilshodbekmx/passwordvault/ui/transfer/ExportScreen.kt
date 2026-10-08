package io.github.dilshodbekmx.passwordvault.ui.transfer

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField

/**
 * The export screen (ux.md §5.20): one route, `Export(step)`, whose steps [vm] moves through, under a
 * top bar with Back. Each step is a full screen: the warning is never a dialog (ux.md §5.20). The
 * colours are the theme's roles only, so every style the user can choose, light or dark, draws it
 * (docs/design/looks/README.md).
 *
 * Back, the top bar's or the system's, goes one step back within the export ([ExportViewModel.back])
 * and leaves the screen from the first step and from a result; it is ignored while a file is saved
 * (ux.md §4). "Backup file (recommended)" opens the backup's own screen.
 */
@Composable
fun ExportScreen(vm: ExportViewModel, nav: Navigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    // The Excel file's sheet name and headings, in the language the screen shows now.
    LaunchedEffect(vm, resources) { vm.useLabels(exportLabels(resources)) }
    LaunchedEffect(vm, nav) {
        vm.events.collect { event ->
            when (event) {
                ExportEvent.OpenBackup -> nav.go(Route.Backup)
            }
        }
    }
    BackHandler(enabled = vm.takesBack(state)) { vm.back() }
    val actions = remember(vm, nav) {
        ExportActions(
            choose = vm::choose,
            continueFromChoice = vm::continueFromChoice,
            setUnderstood = vm::setUnderstood,
            toggleWhich = vm::toggleWhich,
            continueFromWarning = vm::continueFromWarning,
            submitPassword = vm::submitPassword,
            cancelPaused = vm::cancelPaused,
            nextPart = vm::nextPart,
            deleteNow = vm::deleteNow,
            tryAgain = vm::tryAgain,
            leave = { nav.back() },
        )
    }
    ExportFrame(state, vm.password, actions, onBack = { if (!vm.back()) nav.back() })
}

/**
 * The export's frame: the top bar with "Export" and Back ([onBack]; off while a file is saved), and
 * the step [state] is under it ([ExportContent]).
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun ExportFrame(state: ExportUiState, password: TextFieldState, actions: ExportActions, onBack: () -> Unit) {
    val title = stringResource(R.string.export_title)
    // Each step is a screen of its own for TalkBack, which says the new pane's title (ux.md §8).
    val pane = when (state) {
        is ExportUiState.Warning -> stringResource(R.string.export_warning_title)
        is ExportUiState.Password -> stringResource(R.string.export_password_title)
        else -> title
    }
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = pane
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = state !is ExportUiState.Saving,
                        modifier = Modifier.testTag(ExportTags.BACK),
                    ) {
                        Icon(ExportIcons.Back, contentDescription = stringResource(R.string.export_back))
                    }
                },
            )
        },
    ) { padding ->
        ExportContent(state, password, actions, padding)
    }
}

/** What the steps' buttons do: [ExportViewModel]'s functions, and [leave], which closes the screen. */
class ExportActions(
    val choose: (ExportChoice) -> Unit = {},
    val continueFromChoice: () -> Unit = {},
    val setUnderstood: (Boolean) -> Unit = {},
    val toggleWhich: () -> Unit = {},
    val continueFromWarning: () -> Unit = {},
    val submitPassword: () -> Unit = {},
    val cancelPaused: () -> Unit = {},
    val nextPart: () -> Unit = {},
    val deleteNow: () -> Unit = {},
    val tryAgain: () -> Unit = {},
    val leave: () -> Unit = {},
)

/**
 * The step that [state] is, in one scrolling column at most 640 dp wide that the keyboard never
 * covers. [password] is the ViewModel's master-password field.
 */
@Composable
fun ExportContent(state: ExportUiState, password: TextFieldState, actions: ExportActions, padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Each step starts at its top.
        key(state.javaClass) {
            Column(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag(ExportTags.CONTENT),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (state) {
                    is ExportUiState.Choose -> ChooseStep(state, actions)
                    is ExportUiState.Warning -> WarningStep(state, actions)
                    is ExportUiState.Password -> PasswordStep(state, password, actions)
                    is ExportUiState.Picking -> Busy(R.string.export_picking, ExportTags.PICKING, progress = false)
                    is ExportUiState.Saving -> Busy(R.string.export_saving, ExportTags.SAVING, progress = true)
                    is ExportUiState.Saved -> SavedStep(state, actions)
                    is ExportUiState.Failed -> FailedStep(state, actions)
                    is ExportUiState.Ended -> EndedStep(state, actions)
                    is ExportUiState.Pending -> PendingStep(state, actions)
                }
            }
        }
    }
}

// ---- Choose a format ----

@Composable
private fun ColumnScope.ChooseStep(state: ExportUiState.Choose, actions: ExportActions) {
    Heading(stringResource(R.string.export_choose_heading), ExportTags.CHOOSE, style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        for (choice in ExportChoice.entries) {
            val selected = choice == state.choice
            // A row of a list of choices (ux.md §5.20): the whole row is the radio button, 48 dp high at least.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { actions.choose(choice) })
                    .padding(vertical = 12.dp)
                    .testTag(ExportTags.choice(choice)),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(choice.label), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(choice.line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Button(onClick = actions.continueFromChoice, modifier = Modifier.fillMaxWidth().testTag(ExportTags.CONTINUE)) {
        Text(stringResource(R.string.export_continue))
    }
}

// ---- The warning ----

@Composable
private fun ColumnScope.WarningStep(state: ExportUiState.Warning, actions: ExportActions) {
    Icon(
        painter = painterResource(R.drawable.ic_common_warning),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(40.dp),
    )
    Heading(stringResource(R.string.export_warning_title), ExportTags.WARNING)
    Text(stringResource(R.string.export_warning_body), style = MaterialTheme.typography.bodyLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bullet(stringResource(R.string.export_warning_control))
        Bullet(stringResource(R.string.export_warning_messengers))
        Bullet(stringResource(R.string.export_warning_delete))
        when (state.format) {
            ExportFormat.CSV -> Bullet(stringResource(R.string.export_warning_csv), Modifier.testTag(ExportTags.WARNING_FORMAT))
            ExportFormat.XLSX -> Bullet(stringResource(R.string.export_warning_xlsx), Modifier.testTag(ExportTags.WARNING_FORMAT))
            ExportFormat.JSON -> Unit // formats.md §2: JSON adds nothing
        }
        if (state.oldAndroid) {
            Bullet(stringResource(R.string.export_warning_old_android), Modifier.testTag(ExportTags.WARNING_OLD_ANDROID))
        }
    }
    if (state.format == ExportFormat.CSV) CsvCard(state.csv, state.showWhich, actions.toggleWhich)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = state.understood, role = Role.Checkbox, onValueChange = actions.setUnderstood)
            .testTag(ExportTags.UNDERSTAND),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = state.understood, onCheckedChange = null)
        Text(stringResource(R.string.export_understand), style = MaterialTheme.typography.bodyLarge)
    }
    Button(
        onClick = actions.continueFromWarning,
        enabled = state.understood,
        modifier = Modifier.fillMaxWidth().testTag(ExportTags.WARNING_CONTINUE),
    ) {
        Text(stringResource(R.string.export_continue))
    }
}

/** The card about Google Password Manager (ux.md §5.20.2); a small progress circle while it is counted. */
@Composable
private fun CsvCard(summary: ExportCsvSummary?, showWhich: Boolean, toggleWhich: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(ExportTags.CSV_CARD)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.export_csv_card_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (summary == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                return@Column
            }
            if (summary.skipped.isNotEmpty()) {
                Text(stringResource(R.string.export_csv_skip_intro), style = MaterialTheme.typography.bodyMedium)
                for (skipped in summary.skipped) {
                    Bullet(
                        text = pluralStringResource(skipped.reason.plural, skipped.titles.size, skipped.titles.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.export_csv_skip_outro), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = toggleWhich, modifier = Modifier.testTag(ExportTags.SEE_WHICH)) {
                    Text(stringResource(if (showWhich) R.string.export_csv_hide_which else R.string.export_csv_see_which))
                }
                if (showWhich) {
                    Column(Modifier.fillMaxWidth().testTag(ExportTags.WHICH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (skipped in summary.skipped) {
                            Text(
                                text = pluralStringResource(skipped.reason.plural, skipped.titles.size, skipped.titles.size),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            for (title in skipped.titles) {
                                Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
            if (summary.parts > 1) {
                Text(
                    text = pluralStringResource(R.plurals.export_csv_parts, summary.parts, summary.parts),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(ExportTags.CSV_PARTS),
                )
            }
            for (part in summary.partsTooLarge) {
                Text(stringResource(R.string.export_csv_part_too_large, part), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.export_csv_not_kept), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- The master password ----

@Composable
private fun ColumnScope.PasswordStep(state: ExportUiState.Password, password: TextFieldState, actions: ExportActions) {
    if (state.paused) {
        Banner(
            text = state.name?.let { stringResource(R.string.export_paused_body, it) } ?: stringResource(R.string.export_paused_body_file),
            title = stringResource(R.string.export_paused),
            modifier = Modifier.testTag(ExportTags.PAUSED),
        )
    }
    Heading(stringResource(R.string.export_password_title), ExportTags.PASSWORD_TITLE)
    if (!state.paused) Text(stringResource(R.string.export_password_body), style = MaterialTheme.typography.bodyLarge)
    val focus = remember { FocusRequester() }
    VaultSecureField(
        state = password,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ExportTags.PASSWORD),
        label = { Text(stringResource(R.string.export_password_field)) },
        enabled = state.enabled,
        isError = state.error != null && state.error != ExportPasswordError.Wait,
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
        onKeyboardAction = { actions.submitPassword() },
    )
    LaunchedEffect(state.file, state.paused) {
        // The step asks for one thing: the cursor is in the field (ux.md §8, "Focus").
        runCatching { focus.requestFocus() }
    }
    state.error?.let { error ->
        val formatters = rememberFormatters()
        val text = when (error) {
            ExportPasswordError.Wrong -> stringResource(R.string.export_password_wrong)
            ExportPasswordError.Wait -> {
                val seconds = state.waitSecondsLeft ?: 0
                pluralStringResource(R.plurals.export_password_wait, seconds, seconds)
            }
            ExportPasswordError.NoMemory -> stringResource(R.string.export_password_no_memory)
            is ExportPasswordError.Older -> stringResource(R.string.export_password_older, formatters.date(error.changedAtMs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error == ExportPasswordError.Wait) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(ExportTags.PASSWORD_LINE),
        )
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (state.checking) {
            CircularProgressIndicator(Modifier.size(32.dp).testTag(ExportTags.CHECKING), strokeWidth = 3.dp)
        } else {
            Button(
                onClick = actions.submitPassword,
                enabled = state.enabled && password.text.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(ExportTags.SUBMIT),
            ) {
                Text(stringResource(R.string.export_continue))
            }
        }
    }
    if (state.paused) {
        TextButton(
            onClick = actions.cancelPaused,
            enabled = !state.checking,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ExportTags.CANCEL),
        ) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

// ---- The result ----

@Composable
private fun ColumnScope.SavedStep(state: ExportUiState.Saved, actions: ExportActions) {
    Icon(
        painter = painterResource(R.drawable.ic_common_check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
    Text(
        text = if (state.parts > 1) {
            stringResource(R.string.export_saved_part, state.file.part, state.parts, state.name)
        } else {
            stringResource(R.string.export_saved, state.name)
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ExportTags.SAVED),
    )
    if (state.mayUploadLater) Line(stringResource(R.string.export_saved_upload), ExportTags.UPLOAD)
    if (state.replaced > 0) Line(pluralStringResource(R.plurals.export_saved_replaced, state.replaced, state.replaced), ExportTags.REPLACED)
    if (state.truncated > 0) Line(pluralStringResource(R.plurals.export_saved_truncated, state.truncated, state.truncated), ExportTags.TRUNCATED)
    when (state.deletion) {
        ExportDeletion.DELETED -> Line(stringResource(R.string.export_deleted, state.name), ExportTags.DELETION, live = true)
        ExportDeletion.NOT_ALLOWED -> Line(stringResource(R.string.export_cannot_delete), ExportTags.DELETION, live = true)
        ExportDeletion.DELETING, null -> Unit
    }
    // "Done" is the main button: the user just asked for this file (ux.md §5.20).
    when (state.primary) {
        ExportAction.NEXT_PART -> Button(onClick = actions.nextPart, modifier = Modifier.fillMaxWidth().testTag(ExportTags.NEXT_PART)) {
            Text(stringResource(R.string.export_next_part, state.file.part + 1))
        }
        else -> Button(onClick = actions.leave, modifier = Modifier.fillMaxWidth().testTag(ExportTags.DONE)) {
            Text(stringResource(R.string.common_done))
        }
    }
    if (state.secondary == ExportAction.DELETE_NOW) {
        OutlinedButton(
            onClick = actions.deleteNow,
            enabled = state.deletion != ExportDeletion.DELETING,
            modifier = Modifier.fillMaxWidth().testTag(ExportTags.DELETE),
        ) {
            Text(stringResource(R.string.export_delete_now))
        }
    }
}

@Composable
private fun ColumnScope.FailedStep(state: ExportUiState.Failed, actions: ExportActions) {
    Icon(
        painter = painterResource(R.drawable.ic_common_warning),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(40.dp),
    )
    val message = when (state.why) {
        ExportFailure.PICKER -> R.string.export_picker_failed
        ExportFailure.WRITE -> R.string.export_write_failed
        ExportFailure.NOT_NOTED -> R.string.export_note_failed
        ExportFailure.TOO_LARGE -> R.string.export_too_large
    }
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ExportTags.FAILED),
    )
    val cleanup = state.cleanup
    val line = when {
        cleanup == null || state.why == ExportFailure.PICKER -> null
        // The write began: what is left may hold part of the export.
        state.why == ExportFailure.WRITE -> (cleanup as? ExportCleanup.Left)?.let { writeLeftLine(it) }
        else -> cleanupLine(cleanup)
    }
    line?.let { Line(it, ExportTags.CLEANUP) }
    if (state.canRetry) {
        Button(onClick = actions.tryAgain, modifier = Modifier.fillMaxWidth().testTag(ExportTags.TRY_AGAIN)) {
            Text(stringResource(R.string.common_try_again))
        }
    }
    TextButton(onClick = actions.leave, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(ExportTags.CLOSE)) {
        Text(stringResource(R.string.common_close))
    }
}

@Composable
private fun ColumnScope.EndedStep(state: ExportUiState.Ended, actions: ExportActions) {
    val cleanup = state.cleanup
    if (cleanup == null) {
        CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.CenterHorizontally), strokeWidth = 3.dp)
        return
    }
    Text(
        text = cleanupLine(cleanup),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(ExportTags.ENDED),
    )
    Button(onClick = actions.leave, modifier = Modifier.fillMaxWidth().testTag(ExportTags.DONE)) {
        Text(stringResource(R.string.common_done))
    }
}

@Composable
private fun ColumnScope.PendingStep(state: ExportUiState.Pending, actions: ExportActions) {
    Icon(
        painter = painterResource(R.drawable.ic_common_info),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
    Text(
        text = stringResource(R.string.export_pending),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(ExportTags.PENDING),
    )
    state.cleanup?.let { Line(cleanupLine(it), ExportTags.CLEANUP) }
    Button(onClick = actions.leave, modifier = Modifier.fillMaxWidth().testTag(ExportTags.OK)) {
        Text(stringResource(R.string.common_ok))
    }
}

// ---- Parts ----

/** "No file was saved." and what became of the empty file (ux.md §4 item 3). */
@Composable
private fun cleanupLine(cleanup: ExportCleanup): String = when (cleanup) {
    ExportCleanup.Removed -> stringResource(R.string.export_cleanup_removed)
    ExportCleanup.NoneLeft -> stringResource(R.string.export_cleanup_nothing)
    is ExportCleanup.Left -> when (val place = cleanup.place) {
        Place.PhoneStorage -> stringResource(R.string.export_cleanup_left_phone, cleanup.name)
        Place.GoogleDrive -> stringResource(R.string.export_cleanup_left_drive, cleanup.name)
        is Place.Other -> stringResource(R.string.export_cleanup_left_other, cleanup.name, place.name)
    }
}

/** A file that a failed write left behind, which may hold part of the export. */
@Composable
private fun writeLeftLine(left: ExportCleanup.Left): String = when (val place = left.place) {
    Place.PhoneStorage -> stringResource(R.string.export_write_left_phone, left.name)
    Place.GoogleDrive -> stringResource(R.string.export_write_left_drive, left.name)
    is Place.Other -> stringResource(R.string.export_write_left_other, left.name, place.name)
}

@Composable
private fun Heading(text: String, tag: String, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Text(text = text, style = style, modifier = Modifier.semantics { heading() }.testTag(tag))
}

@Composable
private fun Line(text: String, tag: String, live: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (live) liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
    )
}

/** One point of a list, in [style], with a dot drawn in the theme's colour for secondary content. */
@Composable
private fun Bullet(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    // The dot sits in the middle of the first line, whatever the font size.
    val line = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
    val dotTop = if (line.isSpecified) with(LocalDensity.current) { (line.toDp() - 6.dp) / 2 } else 9.dp
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = dotTop.coerceAtLeast(0.dp))
                .size(6.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        Text(text, style = style, modifier = Modifier.weight(1f))
    }
}

/** A step that waits: the file picker is open, or a file is saved. */
@Composable
private fun Busy(@StringRes text: Int, tag: String, progress: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (progress) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The label of [this] row of "Choose a format". */
private val ExportChoice.label: Int
    get() = when (this) {
        ExportChoice.BACKUP -> R.string.export_backup
        ExportChoice.XLSX -> R.string.export_xlsx
        ExportChoice.CSV -> R.string.export_csv
        ExportChoice.JSON -> R.string.export_json
    }

/** The line under [this] row. */
private val ExportChoice.line: Int
    get() = when (this) {
        ExportChoice.BACKUP -> R.string.export_backup_line
        ExportChoice.XLSX -> R.string.export_xlsx_line
        ExportChoice.CSV -> R.string.export_csv_line
        ExportChoice.JSON -> R.string.export_json_line
    }

/** The count of the CSV card for accounts Google refuses for [this] reason. */
private val GoogleCsv.Refusal.plural: Int
    @PluralsRes get() = when (this) {
        GoogleCsv.Refusal.NO_PASSWORD -> R.plurals.export_csv_skip_no_password
        GoogleCsv.Refusal.LONG_PASSWORD -> R.plurals.export_csv_skip_long_password
        GoogleCsv.Refusal.LONG_USERNAME -> R.plurals.export_csv_skip_long_username
        GoogleCsv.Refusal.LONG_NOTE -> R.plurals.export_csv_skip_long_note
        GoogleCsv.Refusal.NO_WEBSITE -> R.plurals.export_csv_skip_no_website
        GoogleCsv.Refusal.BAD_WEBSITE -> R.plurals.export_csv_skip_bad_website
        GoogleCsv.Refusal.LONG_WEBSITE -> R.plurals.export_csv_skip_long_website
    }

/**
 * The Excel file's texts (formats.md §6.1, §6.2) from [resources], the screen's: the sheet's name,
 * the headings, "Yes", "(hidden)", the old password's label and the note of a cut cell, in the
 * language the user reads. The page header is the app's name, the same in every language (D-23).
 */
fun exportLabels(resources: Resources): VaultXlsx.Labels = VaultXlsx.Labels(
    sheet = resources.getString(R.string.export_xlsx_sheet),
    name = resources.getString(R.string.export_xlsx_name),
    username = resources.getString(R.string.export_xlsx_username),
    password = resources.getString(R.string.export_xlsx_password),
    website = resources.getString(R.string.export_xlsx_website),
    otherWebsites = resources.getString(R.string.export_xlsx_other_websites),
    notes = resources.getString(R.string.export_xlsx_notes),
    fields = resources.getString(R.string.export_xlsx_fields),
    favourite = resources.getString(R.string.export_xlsx_favourite),
    added = resources.getString(R.string.export_xlsx_added),
    lastChanged = resources.getString(R.string.export_xlsx_last_changed),
    passwordChanged = resources.getString(R.string.export_xlsx_password_changed),
    yes = resources.getString(R.string.export_xlsx_yes),
    hidden = resources.getString(R.string.export_xlsx_hidden),
    oldPassword = resources.getString(R.string.export_xlsx_old_password),
    app = resources.getString(R.string.export_xlsx_app),
    pageHeader = resources.getString(R.string.app_name),
    truncationNote = { n -> "\n" + resources.getQuantityString(R.plurals.export_xlsx_truncated, n, n) },
)

/** The content's width on a large screen (ux.md §3, "Layout"). */
private val CONTENT_MAX_WIDTH = 640.dp
