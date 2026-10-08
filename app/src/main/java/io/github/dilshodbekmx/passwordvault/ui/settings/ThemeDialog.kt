package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.theme.Look
import io.github.dilshodbekmx.passwordvault.ui.theme.isDark
import io.github.dilshodbekmx.passwordvault.ui.theme.rememberLook

/**
 * Settings › Appearance (ux.md §5.18; docs/design/looks/README.md "What the app offers"): the app's
 * look, in two choices of radio buttons.
 *
 * - **Colours**: the [styles] this phone offers (Bank blue, Emerald, Night vault, and from Android 12
 *   "Phone's colours"), each with a small preview in its own colours ([StylePreview]).
 * - **Light or dark**: System, Light or Dark.
 *
 * A choice is applied at once ([onStyle], [onTheme]: the app's theme follows the stored values) and
 * the dialog stays open, so the user sees the new look in the dialog and behind it and can go on
 * choosing. "Done", Back and a tap outside close it ([onDone]); there is nothing to cancel.
 */
@Composable
fun AppearanceDialog(
    styles: List<StyleChoice>,
    style: StyleChoice,
    theme: ThemeChoice,
    onStyle: (StyleChoice) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onDone: () -> Unit,
) {
    val dark = isDark(theme)
    VaultDialog(
        onDismissRequest = onDone,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        confirmButton = {
            TextButton(onClick = onDone, modifier = Modifier.testTag(SettingsTags.DIALOG_DONE)) {
                Text(stringResource(R.string.common_done))
            }
        },
        title = { Text(text = stringResource(R.string.settings_appearance), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChoiceHeading(R.string.settings_colours)
                Column(Modifier.selectableGroup()) {
                    for (option in styles) {
                        key(option) {
                            ChoiceRow(
                                label = SettingsViewModel.styleText(option).resolve(),
                                supporting = if (option == StyleChoice.PHONE_COLOURS) stringResource(R.string.settings_style_phone_body) else null,
                                selected = option == style,
                                tag = SettingsTags.style(option),
                                onClick = { onStyle(option) },
                                trailing = { StylePreview(rememberLook(option, dark), SettingsTags.stylePreview(option)) },
                            )
                        }
                    }
                }
                ChoiceHeading(R.string.settings_light_or_dark, Modifier.padding(top = 8.dp))
                Column(Modifier.selectableGroup()) {
                    for (option in ThemeChoice.entries) {
                        ChoiceRow(
                            label = SettingsViewModel.themeText(option).resolve(),
                            selected = option == theme,
                            tag = SettingsTags.theme(option),
                            onClick = { onTheme(option) },
                        )
                    }
                }
            }
        },
    )
}

/**
 * A style in small, in its own colours, for its row in "Appearance": a piece of the list as that
 * style draws it. The badge has the style's shape and its primary colour, the two lines are its two
 * text colours, the dot is its primary container, and all lie on its background. A picture only:
 * TalkBack reads the row's name and skips this.
 */
@Composable
private fun StylePreview(look: Look, tag: String) {
    val colours = look.colors
    val frame = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(frame)
            .background(colours.background)
            // The frame is the dialog's own, so that a preview shows on whatever colours are chosen now.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, frame)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { testTag = tag },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(18.dp).clip(look.avatarShape).background(colours.primary))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colours.onSurface))
            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(CircleShape).background(colours.onSurfaceVariant))
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(colours.primaryContainer))
    }
}

/**
 * A dialog that offers one choice among [options], as radio buttons (ux.md §5.18: the lock delay, the
 * clipboard delay; Material 3's `RadioButton` in a selectable group, so that TalkBack reads "1 of 4,
 * selected"). Tapping a choice chooses it ([onChoose]); the caller closes the dialog. "Cancel", Back
 * and a tap outside close it with nothing changed. [note] is a line under the choices. Every choice's
 * row is at least 48 dp high (ux.md §8).
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
                        ChoiceRow(label = label(option), selected = option == selected, tag = tagOf(option), onClick = { onChoose(option) })
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

/**
 * One choice of a group of radio buttons: the whole row is the tap target, at least 48 dp high
 * (ux.md §8). [supporting] is a line under the label, [trailing] a picture at the row's end.
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag)
            .padding(vertical = if (trailing != null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 12.dp)) { trailing() }
        }
    }
}

/** The heading of one choice of "Appearance": a heading for TalkBack (ux.md §8 "Screen structure"). */
@Composable
private fun ChoiceHeading(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 8.dp).semantics { heading() },
    )
}
