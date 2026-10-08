package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog

/**
 * Settings › Appearance & language › Theme (ux.md §5.18): System, Light or Dark, as radio buttons. A
 * choice is applied and the dialog closes; "Cancel" changes nothing. The app's theme follows the
 * stored choice at once (`PasswordVaultTheme(settings)`).
 */
@Composable
fun ThemeDialog(current: ThemeChoice, onChoose: (ThemeChoice) -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog(
        title = stringResource(R.string.settings_theme),
        options = ThemeChoice.entries,
        selected = current,
        label = { SettingsViewModel.themeText(it).resolve() },
        tagOf = { SettingsTags.theme(it) },
        onChoose = onChoose,
        onDismiss = onDismiss,
    )
}

/**
 * A dialog that offers one choice among [options], as radio buttons (ux.md §5.18: the theme, the
 * lock delay, the clipboard delay; Material 3's `RadioButton` in a selectable group, so that TalkBack
 * reads "1 of 4, selected"). Tapping a choice chooses it ([onChoose]); the caller closes the dialog.
 * "Cancel", Back and a tap outside close it with nothing changed. [note] is a line under the choices.
 * Every choice's row is at least 48 dp high (ux.md §8).
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    tagOf: (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    VaultDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTags.DIALOG_CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    for (option in options) {
                        val chosen = option == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = chosen, role = Role.RadioButton, onClick = { onChoose(option) })
                                .testTag(tagOf(option)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen, onClick = null)
                            Text(text = label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
    )
}
