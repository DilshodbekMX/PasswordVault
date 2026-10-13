package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent

/**
 * "These passwords can't be opened" (lock.md F17 step 4, §13.2, §13.3; build-plan.md §12, decision
 * 6): the lock screen of a vault put aside whose right PIN, fingerprint or master password found
 * that no copy of it opens. Under that vault's line, a warning sign, the title and "Their files on
 * this phone are damaged.", both read out when they take the place of the pad or the field; then
 * "Back", which returns to the current vault, as the phone's Back does. Nothing else: no pad, no
 * field, and no "Restore from a backup file", because the passwords the app opens now are fine.
 */
@Composable
fun CannotOpenScreen(ui: CannotOpenUi, onAction: (LockAction) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = ui.goesBack) { onAction(LockAction.Send(LockIntent.GoBack)) }
    val formatters = rememberFormatters()
    LockPage(modifier.testTag(LockTags.CANNOT_OPEN)) {
        AsideLineText(ui.asideLine, formatters)
        Icon(
            painter = painterResource(R.drawable.ic_common_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = ui.title.show(formatters),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                }
                .testTag(LockTags.TITLE),
        )
        Text(
            text = ui.text.show(formatters),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LockTags.CANNOT_OPEN_TEXT),
        )
        for (button in ui.buttons) LockButtonView(button, formatters, onAction)
    }
}
