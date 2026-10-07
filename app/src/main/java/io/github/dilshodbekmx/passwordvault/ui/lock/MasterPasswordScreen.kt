package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import kotlinx.coroutines.flow.first

/**
 * The master-password lock screen (ux.md §5.10; lock.md §13.2, §13.3), shown by the lock route
 * while the lock shows `LockScreen.MasterPassword`: [ui] from [vm].
 *
 * The field takes the keyboard whenever this screen is composed and whenever it can be typed into
 * again after a wait: at the first show, after a rotation, which builds the screen again and where
 * Android 17 no longer brings the keyboard back by itself (architecture.md §5.4, [V-P7]), and on
 * "Try again". It asks once the window has focus, so that the request reaches the keyboard. On the
 * screen of a vault put aside, Back returns to the current vault (lock.md F17 step 4).
 */
@Composable
fun MasterPasswordScreen(vm: LockViewModel, ui: MasterPasswordUi, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(ui.fieldEnabled) {
        if (!ui.fieldEnabled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler(enabled = ui.goesBack) { vm.onAction(MpAction.Send(LockIntent.GoBack)) }

    MasterPasswordContent(
        ui = ui,
        password = vm.password,
        onSubmit = vm::submit,
        onAction = { action ->
            vm.onAction(action)
            if (action == MpAction.TryAgain) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        },
        modifier = modifier,
        fieldModifier = Modifier.focusRequester(focusRequester),
    )
}

/**
 * The screen itself, without its ViewModel: the line of a vault put aside, the title, the reason,
 * the fingerprint line, the field (with a new master password's wait under it), the lines after
 * the last try with their buttons, the countdown of a wait, "Unlock" or "Opening…", and the text
 * buttons. Every text comes from [ui] and the string resources (R-13); dates are formatted here, in
 * the composition's language.
 */
@Composable
fun MasterPasswordContent(
    ui: MasterPasswordUi,
    password: TextFieldState,
    onSubmit: () -> Unit,
    onAction: (MpAction) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
) {
    val formatters = rememberFormatters()
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ui.asideLine?.let {
                Text(
                    text = it.show(formatters),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(LockTags.ASIDE),
                )
            }
            Text(
                text = ui.title.show(formatters),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
            )
            ui.reason?.let {
                Text(
                    text = it.show(formatters),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(LockTags.REASON),
                )
            }
            ui.fingerprintLine?.let {
                Text(
                    text = it.show(formatters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(LockTags.FINGERPRINT),
                )
            }
            VaultSecureField(
                state = password,
                modifier = fieldModifier.fillMaxWidth().testTag(LockTags.FIELD),
                label = { Text(stringResource(R.string.lock_mp_field)) },
                enabled = ui.fieldEnabled,
                supportingText = ui.pending?.let { pending -> { Text(pending.show(formatters), Modifier.testTag(LockTags.PENDING)) } },
                isError = ui.fieldError,
                keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
                onKeyboardAction = { onSubmit() },
            )
            ui.lines.forEachIndexed { index, line ->
                Text(
                    text = line.text.show(formatters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (line.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(LockTags.line(index)),
                )
                line.button?.let { button -> LockTextButton(button, formatters, enabled = !ui.opening, onAction = onAction) }
            }
            ui.countdown?.let {
                Text(
                    text = it.show(formatters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(LockTags.COUNTDOWN),
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                if (ui.opening) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                            .testTag(LockTags.OPENING),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.lock_opening), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Button(
                        onClick = onSubmit,
                        enabled = ui.canSubmit && password.text.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag(LockTags.UNLOCK),
                    ) {
                        Text(stringResource(R.string.lock_mp_unlock))
                    }
                }
            }
            for (button in ui.buttons) LockTextButton(button, formatters, enabled = !ui.opening, onAction = onAction)
        }
    }
}

@Composable
private fun LockTextButton(button: MpButton, formatters: Formatters, enabled: Boolean, onAction: (MpAction) -> Unit) {
    TextButton(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.testTag(button.tag)) {
        Text(button.label.show(formatters), textAlign = TextAlign.Center)
    }
}

/** This text in the composition's language, its [LockDate]s formatted by [formatters]. */
@Composable
private fun UiText.show(formatters: Formatters): String = withDates(formatters).resolve()

/** [this] with every [LockDate] argument, also inside a nested [UiText], replaced by its formatted date. */
internal fun UiText.withDates(formatters: Formatters): UiText = when (this) {
    is UiText.Res -> copy(args = args.map { it.formatted(formatters) })
    is UiText.Plural -> copy(args = args.map { it.formatted(formatters) })
}

private fun Any.formatted(formatters: Formatters): Any = when (this) {
    is LockDate -> when (style) {
        LockDate.Style.DAY_MONTH -> formatters.dayMonth(ms)
        LockDate.Style.DATE -> formatters.date(ms)
    }
    is UiText -> withDates(formatters)
    else -> this
}
