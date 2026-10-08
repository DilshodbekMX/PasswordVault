package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent

/**
 * "This is an older master password" (lock.md F16b, after an ordinary change): the typed password
 * opens only a copy from before a change, and nothing was opened. A proven change says "You changed
 * your master password on 7 Oct. Type the new one." with "OK"; one that may not have finished says
 * so, with "Try again" and "Open my passwords from before the change", which asks once more (the
 * route's question). Both forms come from [ui]. On the screen of a vault put aside, Back returns
 * to the current vault.
 */
@Composable
fun OlderPasswordScreen(ui: OlderPasswordUi, onAction: (LockAction) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = ui.goesBack) { onAction(LockAction.Send(LockIntent.GoBack)) }
    val formatters = rememberFormatters()
    LockPage(modifier.testTag(LockTags.OLDER)) {
        ui.asideLine?.let { AsideLineText(it, formatters) }
        Text(
            text = stringResource(R.string.lock_older_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
        )
        Text(
            text = ui.text.show(formatters),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LockTags.OLDER_TEXT),
        )
        for (button in ui.buttons) LockButtonView(button, formatters, onAction)
    }
}
