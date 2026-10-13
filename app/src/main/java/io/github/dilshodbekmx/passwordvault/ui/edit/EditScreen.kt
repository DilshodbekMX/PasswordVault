package io.github.dilshodbekmx.passwordvault.ui.edit

import android.os.Build
import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.EntryField
import io.github.dilshodbekmx.passwordvault.core.model.EntryProblem
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.StrengthBar
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTextField
import io.github.dilshodbekmx.passwordvault.ui.common.rememberFieldKeyboard

/**
 * Add and edit (ux.md §5.14): a top bar with Close (X), "New account" or "Edit account" and Save,
 * then the form in one scrolling column that the keyboard never covers (`imePadding`), at most
 * 640 dp wide. One-line fields move on with the keyboard's Next key; the last one has Done.
 * [vm] holds the fields; [nav] moves on when [EditUiState.done] says so.
 *
 * The test tags (resource ids, for the emulator scripts and the skeleton check) are in [EditTags].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: EditViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val formatters = rememberFormatters()
    val texts = remember(resources, formatters) { FormTexts(resources, formatters) }
    val snackbar = remember { SnackbarHostState() }
    val focus = rememberFieldFocus(vm)

    BackHandler(enabled = ui.backHandled) { vm.close() }

    LaunchedEffect(ui.done) {
        when (val done = ui.done) {
            null -> return@LaunchedEffect
            is EditDone.Added -> nav.replace(Route.Entry(done.entryId))
            is EditDone.Updated, EditDone.Closed -> nav.back()
        }
        vm.navigated()
    }

    val kept = stringResource(R.string.edit_kept)
    val failed = stringResource(R.string.edit_save_failed)
    val tooLarge = stringResource(R.string.edit_too_large)
    val tryAgain = stringResource(R.string.common_try_again)
    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        when (message) {
            EditMessage.Kept -> snackbar.showSnackbar(kept)
            is EditMessage.SaveFailed -> {
                val large = message.problem == SaveProblem.TOO_LARGE
                val result = snackbar.showSnackbar(
                    message = if (large) tooLarge else failed,
                    actionLabel = if (large) null else tryAgain,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) vm.save(texts)
            }
        }
        vm.messageShown(message)
    }

    LaunchedEffect(ui.focusRequest) {
        val request = ui.focusRequest ?: return@LaunchedEffect
        focus.request(request.key)
        vm.focusRequested()
    }
    focus.RestoreAfterRecreation(inDialog = false)

    val title = stringResource(if (ui.isNew) R.string.edit_title_new else R.string.edit_title_edit)
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::close, modifier = Modifier.testTag(EditTags.CLOSE)) {
                        Icon(painterResource(R.drawable.ic_edit_close), contentDescription = stringResource(R.string.common_close))
                    }
                },
                actions = {
                    if (ui.phase == Phase.FORM || ui.phase == Phase.DONE) SaveButton(ui, onSave = { vm.save(texts) })
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, modifier = Modifier.testTag(EditTags.MESSAGE)) } },
    ) { padding ->
        when (ui.phase) {
            Phase.LOADING -> Unit
            Phase.GONE -> Gone(padding, onClose = vm::close)
            Phase.FORM, Phase.DONE -> Form(vm, ui, padding, focus, formatters)
        }
    }

    Dialogs(vm, ui, texts, formatters, focus)
    GeneratorSheet(vm.generator, onUse = vm::useGenerated, onDismiss = vm::closeGenerator)
}

/** Save, with a small progress in its place once saving has taken 300 ms (ux.md §5.14). */
@Composable
private fun SaveButton(ui: EditUiState, onSave: () -> Unit) {
    val saving = stringResource(R.string.edit_saving)
    TextButton(onClick = onSave, enabled = !ui.saving && ui.phase == Phase.FORM, modifier = Modifier.testTag(EditTags.SAVE)) {
        if (ui.showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).semantics { contentDescription = saving }, strokeWidth = 2.dp)
        } else {
            Text(text = stringResource(R.string.edit_save))
        }
    }
}

/** "This account no longer exists." with Close (ux.md §5.12's state, for the form). */
@Composable
private fun Gone(padding: PaddingValues, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.edit_gone), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClose) { Text(text = stringResource(R.string.common_close)) }
    }
}

@Composable
private fun Form(vm: EditViewModel, ui: EditUiState, padding: PaddingValues, focus: FieldFocus, formatters: Formatters) {
    val form = vm.form
    val lastOneLine = form.fields.lastOrNull { it.secret }?.let { FieldKey.FieldValue(it.id) }
        ?: FieldKey.Website(form.websites.last().id)
    fun ime(key: FieldKey) = if (key == lastOneLine) ImeAction.Done else ImeAction.Next
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VaultTextField(
                state = form.title,
                modifier = Modifier.formField(focus, FieldKey.Title, EditTags.NAME),
                label = { Text(text = stringResource(R.string.edit_name)) },
                placeholder = { Text(text = stringResource(R.string.edit_name_example)) },
                supportingText = support(form.title, EntryRules.TITLE_MAX, problemsOf(ui, vm, FieldKey.Title), formatters),
                isError = problemsOf(ui, vm, FieldKey.Title).isNotEmpty(),
                inputTransformation = vm.inputOf(FieldKey.Title),
                keyboardOptions = VaultFieldDefaults.Text.copy(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            VaultTextField(
                state = form.username,
                modifier = Modifier.formField(focus, FieldKey.Username, EditTags.USERNAME),
                label = { Text(text = stringResource(R.string.edit_username)) },
                supportingText = support(form.username, EntryRules.USERNAME_MAX, problemsOf(ui, vm, FieldKey.Username), formatters),
                isError = problemsOf(ui, vm, FieldKey.Username).isNotEmpty(),
                inputTransformation = vm.inputOf(FieldKey.Username),
                keyboardOptions = VaultFieldDefaults.Username.copy(imeAction = ImeAction.Next),
                lineLimits = TextFieldLineLimits.SingleLine,
                leftToRight = true,
            )
            PasswordPart(vm, ui, focus, formatters)
            form.websites.forEachIndexed { index, row ->
                key(row.id) {
                    val rowKey = FieldKey.Website(row.id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VaultTextField(
                            state = row.state,
                            modifier = Modifier.weight(1f).formField(focus, rowKey, EditTags.website(index)),
                            label = { Text(text = stringResource(R.string.edit_website)) },
                            supportingText = support(row.state, EntryRules.URL_MAX, problemsOf(ui, vm, rowKey), formatters),
                            isError = problemsOf(ui, vm, rowKey).isNotEmpty(),
                            inputTransformation = vm.inputOf(rowKey),
                            keyboardOptions = VaultFieldDefaults.Website.copy(imeAction = ime(rowKey)),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            leftToRight = true,
                        )
                        if (index > 0) {
                            IconButton(onClick = { vm.removeWebsite(row.id) }, modifier = Modifier.testTag(EditTags.removeWebsite(index))) {
                                Icon(painterResource(R.drawable.ic_edit_remove_row), contentDescription = stringResource(R.string.edit_remove_website))
                            }
                        }
                    }
                }
            }
            if (ui.problems.any { it.field == EntryField.WEBSITES }) {
                ErrorLine(pluralStringResource(R.plurals.edit_error_too_many_websites, EntryRules.URLS_MAX, EntryRules.URLS_MAX))
            }
            if (form.websites.size < EntryRules.URLS_MAX) {
                TextButton(onClick = vm::addWebsite, modifier = Modifier.testTag(EditTags.ADD_WEBSITE)) {
                    Text(text = stringResource(R.string.edit_add_website))
                }
            }
            VaultTextField(
                state = form.notes,
                modifier = Modifier.formField(focus, FieldKey.Notes, EditTags.NOTES),
                label = { Text(text = stringResource(R.string.edit_notes)) },
                supportingText = support(form.notes, EntryRules.NOTES_MAX, problemsOf(ui, vm, FieldKey.Notes), formatters),
                isError = problemsOf(ui, vm, FieldKey.Notes).isNotEmpty(),
                inputTransformation = vm.inputOf(FieldKey.Notes),
                keyboardOptions = VaultFieldDefaults.Text,
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
            )
            form.fields.forEachIndexed { index, row ->
                key(row.id) {
                    val rowKey = FieldKey.FieldValue(row.id)
                    val problems = problemsOf(ui, vm, rowKey)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.secret) {
                            VaultSecureField(
                                state = row.value,
                                modifier = Modifier.weight(1f).formField(focus, rowKey, EditTags.field(index)),
                                label = { Text(text = row.name) },
                                supportingText = support(row.value, EntryRules.FIELD_VALUE_MAX, problems, formatters),
                                isError = problems.isNotEmpty(),
                                inputTransformation = vm.inputOf(rowKey),
                                keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ime(rowKey)),
                            )
                        } else {
                            VaultTextField(
                                state = row.value,
                                modifier = Modifier.weight(1f).formField(focus, rowKey, EditTags.field(index)),
                                label = { Text(text = row.name) },
                                supportingText = support(row.value, EntryRules.FIELD_VALUE_MAX, problems, formatters),
                                isError = problems.isNotEmpty(),
                                inputTransformation = vm.inputOf(rowKey),
                                keyboardOptions = VaultFieldDefaults.Text,
                                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                            )
                        }
                        IconButton(onClick = { vm.removeField(row.id) }, modifier = Modifier.testTag(EditTags.removeField(index))) {
                            Icon(painterResource(R.drawable.ic_edit_remove_row), contentDescription = stringResource(R.string.edit_remove_field, row.name))
                        }
                    }
                }
            }
            if (ui.problems.any { it.field == EntryField.FIELDS }) {
                ErrorLine(pluralStringResource(R.plurals.edit_error_too_many_fields, EntryRules.FIELDS_MAX, EntryRules.FIELDS_MAX))
            }
            if (form.fields.size < EntryRules.FIELDS_MAX) {
                TextButton(onClick = vm::openAddField, modifier = Modifier.testTag(EditTags.ADD_FIELD)) {
                    Text(text = stringResource(R.string.edit_add_field))
                }
            }
        }
    }
}

/** The password field with its dice button, and under it the generator button, the meter and the reuse note. */
@Composable
private fun PasswordPart(vm: EditViewModel, ui: EditUiState, focus: FieldFocus, formatters: Formatters) {
    val form = vm.form
    val problems = problemsOf(ui, vm, FieldKey.Password)
    val generate = stringResource(R.string.edit_generate)
    VaultSecureField(
        state = form.password,
        modifier = Modifier.formField(focus, FieldKey.Password, EditTags.PASSWORD),
        label = { Text(text = stringResource(R.string.edit_password)) },
        trailingIcon = {
            IconButton(onClick = vm::openGenerator, modifier = Modifier.testTag(EditTags.GENERATE)) {
                Icon(painterResource(R.drawable.ic_edit_dice), contentDescription = generate)
            }
        },
        supportingText = support(form.password, EntryRules.PASSWORD_MAX, problems, formatters),
        isError = problems.isNotEmpty(),
        inputTransformation = vm.inputOf(FieldKey.Password),
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Next),
    )
    if (form.password.text.isEmpty()) {
        TextButton(onClick = vm::openGenerator, modifier = Modifier.testTag(EditTags.GENERATE_BUTTON)) { Text(text = generate) }
    } else {
        ui.strength?.let { StrengthBar(score = it.score, modifier = Modifier.fillMaxWidth().testTag(EditTags.STRENGTH)) }
        if (ui.reuse > 0) {
            Text(
                text = pluralStringResource(R.plurals.edit_reuse, ui.reuse, ui.reuse),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(EditTags.REUSE),
            )
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** The problems Save found for [key]'s field. */
private fun problemsOf(ui: EditUiState, vm: EditViewModel, key: FieldKey): List<EntryProblem> =
    if (ui.problems.isEmpty()) emptyList() else vm.problemsOf(ui.problems, key)

/**
 * The line under a field: what Save found wrong, and the counter once the field is nearly full
 * ("10,000 / 10,000", ux.md §5.14). Null when there is neither.
 */
@Composable
private fun support(state: TextFieldState, max: Int, problems: List<EntryProblem>, formatters: Formatters): (@Composable () -> Unit)? {
    val counter = Counter.of(state.text, max)
    val error = problems.firstOrNull()?.let { problemText(it, formatters) }
    if (counter == null && error == null) return null
    val count = counter?.let { stringResource(R.string.edit_counter, formatters.number(it.count.toLong()), formatters.number(it.max.toLong())) }
    return {
        Column(Modifier.fillMaxWidth()) {
            if (error != null) Text(text = error)
            if (count != null) Text(text = count, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun problemText(problem: EntryProblem, formatters: Formatters): String = when (problem.kind) {
    EntryProblem.Kind.TOO_LONG -> stringResource(R.string.edit_error_too_long, formatters.number(problem.limit.toLong()))
    EntryProblem.Kind.CONTROL_CHARACTER -> stringResource(R.string.edit_error_control)
    EntryProblem.Kind.UNPAIRED_SURROGATE -> stringResource(R.string.edit_error_broken)
    EntryProblem.Kind.BLANK -> stringResource(R.string.edit_error_label)
    EntryProblem.Kind.TOO_MANY -> pluralStringResource(
        if (problem.field == EntryField.WEBSITES) R.plurals.edit_error_too_many_websites else R.plurals.edit_error_too_many_fields,
        problem.limit,
        problem.limit,
    )
}

/** The form's questions (ux.md §4, §5.14, §5.15) and the "Add field" dialog. */
@Composable
private fun Dialogs(vm: EditViewModel, ui: EditUiState, texts: FormTexts, formatters: Formatters, focus: FieldFocus) {
    when (val dialog = ui.dialog) {
        null -> Unit
        EditDialog.SaveChanges -> VaultDialog(
            onDismissRequest = vm::dismissDialog,
            modifier = DIALOG_TAGS,
            title = { Text(text = stringResource(R.string.edit_unsaved_title)) },
            confirmButton = {
                TextButton(onClick = { vm.save(texts) }, modifier = Modifier.testTag(EditTags.DIALOG_SAVE)) {
                    Text(text = stringResource(R.string.edit_unsaved_save))
                }
            },
            dismissButton = {
                FlowRow(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = vm::dismissDialog, modifier = Modifier.testTag(EditTags.DIALOG_KEEP)) {
                        Text(text = stringResource(R.string.edit_unsaved_keep))
                    }
                    TextButton(onClick = vm::discard, modifier = Modifier.testTag(EditTags.DIALOG_DISCARD)) {
                        Text(text = stringResource(R.string.edit_unsaved_discard))
                    }
                }
            },
        )
        is EditDialog.SameLogin -> Question(
            text = stringResource(R.string.edit_duplicate, dialog.title),
            yes = stringResource(R.string.edit_duplicate_save),
            yesTag = EditTags.DIALOG_SAVE_ANYWAY,
            onYes = { vm.saveAnyway(texts) },
            no = stringResource(R.string.common_cancel),
            onNo = vm::dismissDialog,
            onDismiss = vm::dismissDialog,
        )
        EditDialog.ReplacePassword -> Question(
            text = stringResource(R.string.edit_replace),
            yes = stringResource(R.string.edit_replace_yes),
            yesTag = EditTags.DIALOG_REPLACE,
            onYes = vm::replacePassword,
            no = stringResource(R.string.common_cancel),
            onNo = vm::dismissDialog,
            onDismiss = vm::dismissDialog,
        )
        is EditDialog.OldDraft -> {
            val date = formatters.dayMonth(dialog.savedAt)
            Question(
                text = dialog.title?.let { stringResource(R.string.edit_old_draft, it, date) } ?: stringResource(R.string.edit_old_draft_new, date),
                yes = stringResource(R.string.edit_old_draft_open),
                yesTag = EditTags.DIALOG_OPEN_DRAFT,
                onYes = vm::openOldDraft,
                no = stringResource(R.string.edit_old_draft_discard),
                onNo = vm::discardOldDraft,
                // Neither answer: the draft stays in the vault and is offered the next time.
                onDismiss = vm::dismissDialog,
            )
        }
        EditDialog.AddField -> AddFieldDialog(vm, focus, formatters)
    }
}

@Composable
private fun Question(text: String, yes: String, yesTag: String, onYes: () -> Unit, no: String, onNo: () -> Unit, onDismiss: () -> Unit) {
    VaultDialog(
        onDismissRequest = onDismiss,
        modifier = DIALOG_TAGS,
        text = { Text(text = text) },
        confirmButton = { TextButton(onClick = onYes, modifier = Modifier.testTag(yesTag)) { Text(text = yes) } },
        dismissButton = { TextButton(onClick = onNo, modifier = Modifier.testTag(EditTags.DIALOG_NO)) { Text(text = no) } },
    )
}

/**
 * "Add field" (ux.md §5.14): the label chips "PIN code", "Phone number", "Recovery email",
 * "Security question", "Answer", "Code word" and "Other…" (a label of one's own), the value, and
 * "Hide this value" (on, except for a phone number or an email).
 */
@Composable
private fun AddFieldDialog(vm: EditViewModel, focus: FieldFocus, formatters: Formatters) {
    val add = vm.addField
    val chosen = add.label?.let { stringResource(it.text) }
    VaultDialog(
        onDismissRequest = vm::dismissDialog,
        modifier = DIALOG_TAGS,
        title = { Text(text = stringResource(R.string.edit_field_title)) },
        text = {
            focus.RestoreAfterRecreation(inDialog = true)
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (label in FieldLabel.entries) {
                        FilterChip(
                            selected = add.label == label,
                            onClick = { vm.chooseLabel(label) },
                            label = { Text(text = stringResource(label.text)) },
                            modifier = Modifier.testTag(EditTags.chip(label)),
                        )
                    }
                }
                if (add.label == FieldLabel.OTHER) {
                    VaultTextField(
                        state = add.name,
                        modifier = Modifier.formField(focus, FieldKey.AddFieldName, EditTags.FIELD_LABEL),
                        label = { Text(text = stringResource(R.string.edit_field_label)) },
                        supportingText = labelSupport(add, formatters),
                        isError = add.labelMissing,
                        inputTransformation = vm.inputOf(FieldKey.AddFieldName),
                        keyboardOptions = VaultFieldDefaults.Text.copy(imeAction = ImeAction.Next),
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                } else if (add.labelMissing) {
                    ErrorLine(stringResource(R.string.edit_error_label))
                }
                if (add.secret) {
                    VaultSecureField(
                        state = add.value,
                        modifier = Modifier.formField(focus, FieldKey.AddFieldValue, EditTags.FIELD_VALUE),
                        label = { Text(text = stringResource(R.string.edit_field_value)) },
                        supportingText = support(add.value, EntryRules.FIELD_VALUE_MAX, emptyList(), formatters),
                        inputTransformation = vm.inputOf(FieldKey.AddFieldValue),
                    )
                } else {
                    VaultTextField(
                        state = add.value,
                        modifier = Modifier.formField(focus, FieldKey.AddFieldValue, EditTags.FIELD_VALUE),
                        label = { Text(text = stringResource(R.string.edit_field_value)) },
                        supportingText = support(add.value, EntryRules.FIELD_VALUE_MAX, emptyList(), formatters),
                        inputTransformation = vm.inputOf(FieldKey.AddFieldValue),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                    )
                }
                SwitchRow(R.string.edit_field_hide, add.secret, EditTags.FIELD_HIDE, vm::setFieldSecret)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.confirmAddField(chosen.orEmpty()) }, modifier = Modifier.testTag(EditTags.FIELD_ADD)) {
                Text(text = stringResource(R.string.edit_field_add))
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissDialog, modifier = Modifier.testTag(EditTags.DIALOG_NO)) {
                Text(text = stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun labelSupport(add: AddFieldForm, formatters: Formatters): (@Composable () -> Unit)? {
    if (!add.labelMissing) return support(add.name, EntryRules.FIELD_NAME_MAX, emptyList(), formatters)
    val text = stringResource(R.string.edit_error_label)
    return { Text(text = text) }
}

/**
 * The focus of the form's fields (architecture.md §5.4): each field tells the ViewModel when it
 * gets or loses the focus, so that a screen made again after a rotation can give it back with the
 * keyboard, which Android 17 no longer does itself [V-P7]. A loss is told only for a field that had
 * the focus in this composition and while the screen is started: the fields of a screen that goes
 * away with its activity, or that are just being made, change nothing.
 */
@Stable
private class FieldFocus(private val vm: EditViewModel, private val lifecycle: Lifecycle) {
    private val requesters = HashMap<FieldKey, FocusRequester>()
    private var focusedHere: FieldKey? = null

    /** The field to give back, read once when this screen is made, before any field reports. */
    val restore: FieldKey? = vm.focusToRestore()

    fun requester(key: FieldKey): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The field [key] got or lost the focus. */
    fun changed(key: FieldKey, focused: Boolean) {
        if (focused) {
            focusedHere = key
            vm.focused(key)
        } else if (focusedHere == key && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            focusedHere = null
            vm.unfocused(key)
        }
    }

    /** Gives [key] the focus, which also opens the keyboard; false when no such field is shown. */
    fun request(key: FieldKey): Boolean = try {
        requester(key).requestFocus()
        true
    } catch (_: IllegalStateException) {
        false // the field is not in this composition (yet, or any more: a row that is gone, or a dialog's)
    }

    /**
     * After a recreation: gives the field that had the focus the focus and the keyboard again.
     * [inDialog] picks the fields of the "Add field" dialog, which lives in a window of its own, or
     * those of the form.
     *
     * The keyboard is asked for at once, before the new window has the focus, as Android 17's
     * behaviour change says to ("request the keyboard in onCreate()"): when a window that asks for
     * nothing gets the focus, Android 17 hides the keyboard (`HIDE_UNSPECIFIED_WINDOW`), and a
     * request that comes while it does so is dropped (`PHASE_SERVER_ABORT_SHOW_IME_POST_LAYOUT`,
     * seen on the emulator). Once the window has the focus the keyboard is asked for once more if
     * it is not seen, for the versions that act only on a request from a focused window, and then
     * again for about a second while the field keeps the focus and the keyboard is not shown
     * (`FieldKeyboard`: on API 37 in landscape Gboard can close again by itself).
     */
    @Composable
    fun RestoreAfterRecreation(inDialog: Boolean) {
        val key = restore ?: return
        val dialogKey = key == FieldKey.AddFieldName || key == FieldKey.AddFieldValue
        if (dialogKey != inDialog) return
        val fieldKeyboard = rememberFieldKeyboard()
        val view = LocalView.current
        LaunchedEffect(Unit) {
            // The field may be laid out a frame later (the Scaffold places its content while measuring).
            var focused = request(key)
            var frames = 0
            while (!focused && frames++ < FOCUS_FRAMES) {
                withFrameNanos { }
                focused = request(key)
            }
            if (!focused) return@LaunchedEffect
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.windowInsetsController?.show(WindowInsets.Type.ime())
            fieldKeyboard.awaitWindowFocus()
            if (focusedHere == key) fieldKeyboard.show { focusedHere == key }
        }
    }
}

/** A field of the form: full width, its focus told to [focus] as [key], and its test tag [tag]. */
private fun Modifier.formField(focus: FieldFocus, key: FieldKey, tag: String): Modifier = this
    .fillMaxWidth()
    .focusRequester(focus.requester(key))
    .onFocusChanged { focus.changed(key, it.isFocused) }
    .testTag(tag)

/**
 * A dialog is a window of its own: its test tags are exposed as resource ids from its root, as the
 * screen's are (the emulator scripts find the buttons by them).
 */
private val DIALOG_TAGS = Modifier.semantics { testTagsAsResourceId = true }

/** How many frames a field to give the focus back to may take to be laid out. */
private const val FOCUS_FRAMES = 5

@Composable
private fun rememberFieldFocus(vm: EditViewModel): FieldFocus {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(vm, lifecycle) { FieldFocus(vm, lifecycle) }
}

/**
 * The test tags of the form, exposed as resource ids (the emulator scripts and the skeleton check
 * find the fields by them, whatever the language). Rows are numbered from 0 in the order shown.
 */
object EditTags {
    const val CLOSE = "pv.edit.close"
    const val SAVE = "pv.edit.save"
    const val NAME = "pv.edit.name"
    const val USERNAME = "pv.edit.username"
    const val PASSWORD = "pv.edit.password"

    /** The dice button in the password field. */
    const val GENERATE = "pv.edit.generate"

    /** "Create a strong password" under the empty password field. */
    const val GENERATE_BUTTON = "pv.edit.generateButton"
    const val STRENGTH = "pv.edit.strength"
    const val REUSE = "pv.edit.reuse"
    const val ADD_WEBSITE = "pv.edit.addWebsite"
    const val NOTES = "pv.edit.notes"
    const val ADD_FIELD = "pv.edit.addField"

    /** The short message at the bottom ("Your unsaved changes were kept.", "Couldn't save. …"). */
    const val MESSAGE = "pv.edit.message"
    const val DIALOG_SAVE = "pv.edit.dialog.save"
    const val DIALOG_DISCARD = "pv.edit.dialog.discard"
    const val DIALOG_KEEP = "pv.edit.dialog.keep"
    const val DIALOG_SAVE_ANYWAY = "pv.edit.dialog.saveAnyway"
    const val DIALOG_REPLACE = "pv.edit.dialog.replace"
    const val DIALOG_OPEN_DRAFT = "pv.edit.dialog.openDraft"

    /** The second button of a question: Cancel, or Discard for an old draft. */
    const val DIALOG_NO = "pv.edit.dialog.no"
    const val FIELD_LABEL = "pv.edit.addField.label"
    const val FIELD_VALUE = "pv.edit.addField.value"
    const val FIELD_HIDE = "pv.edit.addField.hide"
    const val FIELD_ADD = "pv.edit.addField.add"

    fun website(index: Int) = "pv.edit.website.$index"

    fun removeWebsite(index: Int) = "pv.edit.removeWebsite.$index"

    fun field(index: Int) = "pv.edit.field.$index"

    fun removeField(index: Int) = "pv.edit.removeField.$index"

    fun chip(label: FieldLabel) = "pv.edit.addField.chip.${label.name}"
}
