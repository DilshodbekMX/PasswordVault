package io.github.dilshodbekmx.passwordvault.ui.security

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.edit.GeneratorSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The guided change of one password (ux.md §5.17): one scrolling screen with a numbered
 * `OutlinedCard` per step, because the person leaves for the website and comes back several times;
 * after "Yes, it's changed", the end of the change in place of the steps. [vm] holds what is shown;
 * [nav] goes back, to the next account ("Next: Facebook", in place of this screen) and to Make a
 * backup.
 *
 * - **Buttons** are stacked one under the other at the full width of their card, so that no text is
 *   squeezed or cut in any language or at a large font (i18n.md §4.5). The steps' main actions are
 *   filled buttons, as ux.md names them; the buttons of steps 4 and 5 wait until a new password was
 *   made, and the screen says so.
 * - **A copy** turns its button's icon into a check mark for 1.5 seconds, with a haptic tick (ux.md
 *   §6 item 3); no snackbar says "copied": the clipboard row at the bottom of the app does.
 * - **A new password** is confirmed in step 3 itself ("Saved. …", and "New password saved." for a
 *   moment after another one), never by a snackbar, which would cover the buttons of steps 4 and 5.
 * - **No password is drawn** on this screen: only the generator sheet of "Password rules" shows the
 *   password it made (ux.md §5.15). Nothing is kept in saved state (architecture.md §5.6).
 *
 * The test tags (resource ids, for the emulator script) are in [GuidedChangeTags].
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun GuidedChangeScreen(vm: GuidedChangeViewModel, nav: Navigator) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // The copy button that shows its check mark for 1.5 s (ux.md §6 item 3).
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPY_CONFIRMATION_MS)
            copied = null
        }
    }

    // "New password saved." in step 3 for a moment after "Create another password", where nothing else
    // on the screen changes. Not a snackbar: it would lie over the buttons of steps 4 and 5, which are
    // the next to be used.
    var retried by remember { mutableIntStateOf(0) }
    LaunchedEffect(retried) {
        if (retried != 0) {
            delay(RETRY_CONFIRMATION_MS)
            retried = 0
        }
    }

    LaunchedEffect(vm, snackbar) {
        vm.events.collect { event ->
            launch {
                when (event) {
                    // The first new password brings "Saved. Your old password is kept until you finish."
                    is GuidedChangeEvent.Created -> if (event.again) retried++
                    GuidedChangeEvent.Reverted -> snackbar.showSnackbar(resources.getString(R.string.change_reverted))
                    GuidedChangeEvent.CopyBlocked ->
                        snackbar.showSnackbar(resources.getString(R.string.change_copy_blocked), duration = SnackbarDuration.Long)
                    is GuidedChangeEvent.NotSaved -> {
                        val retry = event.retry
                        val large = event.problem == SaveProblem.TOO_LARGE
                        val result = snackbar.showSnackbar(
                            message = resources.getString(if (large) R.string.change_too_large else R.string.change_save_failed),
                            actionLabel = if (retry != null) resources.getString(R.string.common_try_again) else null,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
                    }
                }
            }
        }
    }

    val actions = remember(vm, nav) {
        StepActions(
            copyOld = { tag ->
                if (vm.copyOld()) {
                    copied = tag
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            },
            copyNew = {
                if (vm.copyNew()) {
                    copied = GuidedChangeTags.COPY_NEW
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            },
            openWebsite = { address ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (e: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.change_no_browser)) }
                }
            },
            create = vm::create,
            rules = vm::openRules,
            yes = vm::confirm,
            no = vm::revert,
            later = { nav.back() },
            next = { vm.next()?.let { nav.replace(Route.GuidedChange(it)) } ?: nav.back() },
            finish = {
                vm.finish()
                nav.back()
            },
            backUpNow = { nav.go(Route.Backup) },
        )
    }

    val title = stringResource(R.string.change_title)
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }, modifier = Modifier.testTag(GuidedChangeTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_entry_back), contentDescription = stringResource(R.string.change_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, modifier = Modifier.testTag(GuidedChangeTags.MESSAGE)) } },
    ) { padding ->
        when (val now = state) {
            GuidedChangeUiState.Loading -> Unit
            is GuidedChangeUiState.Steps -> Steps(now, copied, retried != 0, actions, padding)
            is GuidedChangeUiState.Done -> Done(now, actions, padding)
            is GuidedChangeUiState.Gone -> Page(padding) {
                Text(
                    text = stringResource(if (now.inRecentlyDeleted) R.string.change_gone_deleted else R.string.change_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(GuidedChangeTags.GONE),
                )
                TextButton(onClick = { nav.back() }, modifier = Modifier.testTag(GuidedChangeTags.GONE_BACK)) {
                    Text(stringResource(R.string.change_back))
                }
            }
        }
    }

    GeneratorSheet(model = vm.generator, onUse = vm::useRules, onDismiss = vm::closeRules)
}

/** What the steps' buttons do. [copyOld] takes the tag of the button that was used (step 1 or 4). */
private class StepActions(
    val copyOld: (tag: String) -> Unit,
    val copyNew: () -> Unit,
    val openWebsite: (address: String) -> Unit,
    val create: () -> Unit,
    val rules: () -> Unit,
    val yes: () -> Unit,
    val no: () -> Unit,
    val later: () -> Unit,
    val next: () -> Unit,
    val finish: () -> Unit,
    val backUpNow: () -> Unit,
)

/** A scrolling column at most [CONTENT_MAX_WIDTH] wide, in the middle of a wide screen (ux.md §3 "Layout"). */
@Composable
private fun Page(padding: PaddingValues, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * The account's name and username, "Last one!" when it applies, and the five steps (ux.md §5.17).
 * [retried]: another password was saved a moment ago ("New password saved.").
 */
@Composable
private fun Steps(steps: GuidedChangeUiState.Steps, copied: String?, retried: Boolean, actions: StepActions, padding: PaddingValues) {
    Page(padding, tag = GuidedChangeTags.STEPS) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = steps.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(GuidedChangeTags.ACCOUNT).semantics { heading() },
            )
            if (steps.username.isNotEmpty()) {
                Text(text = steps.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (steps.lastOne) {
            Text(
                text = stringResource(R.string.change_last_one),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp).testTag(GuidedChangeTags.LAST_ONE),
            )
        }
        val saving = steps.busy

        StepCard(1, stringResource(R.string.change_step1, steps.title)) {
            val website = steps.website
            if (website != null) {
                Button(onClick = { actions.openWebsite(website) }, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.OPEN_WEBSITE)) {
                    Text(stringResource(R.string.change_open_website))
                }
            }
            CopyButton(
                text = R.string.change_copy_old,
                tag = GuidedChangeTags.COPY_OLD_FIRST,
                copied = copied == GuidedChangeTags.COPY_OLD_FIRST,
                enabled = steps.canCopyOld,
                filled = false,
            ) { actions.copyOld(GuidedChangeTags.COPY_OLD_FIRST) }
        }

        StepCard(2, stringResource(R.string.change_step2)) {
            Text(text = stringResource(R.string.change_step2_where), style = MaterialTheme.typography.bodyMedium)
        }

        StepCard(3, stringResource(R.string.change_step3)) {
            if (steps.started) {
                Button(
                    onClick = actions.create,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.CREATE_ANOTHER),
                ) {
                    Text(stringResource(R.string.change_create_another))
                }
                if (retried) {
                    Text(
                        text = stringResource(R.string.change_created),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(GuidedChangeTags.CREATED).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Text(
                    text = stringResource(R.string.change_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(GuidedChangeTags.SAVED).semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Button(onClick = actions.create, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.CREATE)) {
                    Text(stringResource(R.string.change_create))
                }
            }
            TextButton(onClick = actions.rules, enabled = !saving, modifier = Modifier.testTag(GuidedChangeTags.RULES)) {
                Text(stringResource(R.string.change_rules))
            }
        }

        StepCard(4, stringResource(R.string.change_step4)) {
            CopyButton(
                text = R.string.change_copy_old,
                tag = GuidedChangeTags.COPY_OLD,
                copied = copied == GuidedChangeTags.COPY_OLD,
                enabled = steps.canCopyOld,
                filled = false,
            ) { actions.copyOld(GuidedChangeTags.COPY_OLD) }
            CopyButton(
                text = R.string.change_copy_new,
                tag = GuidedChangeTags.COPY_NEW,
                copied = copied == GuidedChangeTags.COPY_NEW,
                enabled = steps.canCopyNew,
                filled = true,
                onClick = actions.copyNew,
            )
            if (!steps.started) WaitForStep3()
            Hint(R.string.change_hint_ask)
            Hint(R.string.change_hint_chrome)
        }

        StepCard(5, stringResource(R.string.change_step5)) {
            val answer = steps.started && !saving
            Button(onClick = actions.yes, enabled = answer, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.YES)) {
                Text(stringResource(R.string.change_yes))
            }
            OutlinedButton(onClick = actions.no, enabled = answer, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.NO)) {
                Text(stringResource(R.string.change_no))
            }
            TextButton(onClick = actions.later, enabled = steps.started, modifier = Modifier.testTag(GuidedChangeTags.LATER)) {
                Text(stringResource(R.string.change_later))
            }
            if (!steps.started) WaitForStep3()
        }
        // Room under the last step, so that its last button is not at the very edge of the screen.
        Spacer(Modifier.size(END_SPACE))
    }
}

/**
 * One step: an `OutlinedCard` with "Step 2 of 5" and the step's [title] (a heading for TalkBack), and
 * the step's [content] under it, one item under the other.
 */
@Composable
private fun StepCard(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(GuidedChangeTags.step(number))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = stringResource(R.string.change_step_number, number, GuidedChangeViewModel.STEPS)
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** A copy button (ux.md §6 item 3): its icon is a check mark for 1.5 s after a copy, and TalkBack says "Copied". */
@Composable
private fun CopyButton(@StringRes text: Int, tag: String, copied: Boolean, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val label = stringResource(text)
    val done = stringResource(R.string.change_copied)
    val modifier = Modifier
        .fillMaxWidth()
        .testTag(tag)
        .semantics { if (copied) contentDescription = done }
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_common_check else R.drawable.ic_common_copy),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(label)
        }
    }
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    }
}

/** "First create the new password in step 3.", under the buttons that wait for it. */
@Composable
private fun WaitForStep3() {
    Text(
        text = stringResource(R.string.change_first_step3),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hint(@StringRes text: Int) {
    Text(text = stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The end of the change (ux.md §5.17): "Gmail now has its own password." with "Next: Facebook" or
 * "Done for now", and, once per session while changes are in no backup, the backup line with "Back
 * up now".
 */
@Composable
private fun Done(done: GuidedChangeUiState.Done, actions: StepActions, padding: PaddingValues) {
    Page(padding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_common_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.change_done, done.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .testTag(GuidedChangeTags.DONE)
                    .semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        val nextTitle = done.nextTitle
        if (done.nextId != null && nextTitle != null) {
            Button(onClick = actions.next, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.NEXT)) {
                Text(stringResource(R.string.change_next, nextTitle))
            }
        } else {
            Button(onClick = actions.finish, modifier = Modifier.fillMaxWidth().testTag(GuidedChangeTags.FINISH)) {
                Text(stringResource(R.string.change_done_for_now))
            }
        }
        if (done.backupLine) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.change_backup_only_here, done.title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(GuidedChangeTags.BACKUP_LINE),
                    )
                    TextButton(
                        onClick = actions.backUpNow,
                        modifier = Modifier.align(Alignment.End).testTag(GuidedChangeTags.BACK_UP_NOW),
                    ) {
                        Text(stringResource(R.string.change_back_up_now))
                    }
                }
            }
        }
    }
}

/** How long a copy button shows its check mark (ux.md §6 item 3). */
private const val COPY_CONFIRMATION_MS = 1_500L

/** How long "New password saved." stays in step 3 after another password was saved. */
private const val RETRY_CONFIRMATION_MS = 4_000L

/** The content is at most this wide on large screens (ux.md §3 "Layout"). */
private val CONTENT_MAX_WIDTH = 640.dp

/** The room under the last step. */
private val END_SPACE = 64.dp
