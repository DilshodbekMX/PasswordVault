package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/**
 * "Make backups" (ux.md §5.8), the last step of setup: why backups matter (the passwords are only on
 * this phone), what a backup is, where to keep one; the switch "Remind me to make a backup" (on by
 * default, the stored `AppSettings.backupReminder`), and "Finish", which opens the list. No backup is
 * made now: the vault is empty, and the first reminder comes one day after the first account is
 * added (ux.md §5.11).
 */
@Composable
fun BackupsExplainedScreen(remind: Boolean, line: PinLine?, onRemindChange: (Boolean) -> Unit, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    SetupPage(title = stringResource(R.string.pin_backups_title), modifier = modifier.semantics { testTagsAsResourceId = true }) {
        BodyText(stringResource(R.string.pin_backups_only_here))
        BodyText(stringResource(R.string.pin_backups_locked))
        BodyText(stringResource(R.string.pin_backups_outside))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = remind, role = Role.Switch, onValueChange = onRemindChange)
                .padding(vertical = 8.dp)
                .testTag(BackupsExplainedTags.REMIND),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.pin_backups_remind), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = remind, onCheckedChange = null)
        }
        if (line != null) StatusLine(line, BackupsExplainedTags.LINE)
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().testTag(BackupsExplainedTags.FINISH)) {
            Text(stringResource(R.string.pin_backups_finish))
        }
    }
}

/** The resource ids of "Make backups". */
object BackupsExplainedTags {
    const val REMIND = "pv.backupsExplained.remind"
    const val LINE = "pv.backupsExplained.line"

    /** "Finish": the shell walk's `pv.action.finish`. */
    const val FINISH = "pv.action.finish"
}
