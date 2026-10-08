package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent

/**
 * "Open the older set instead?" (lock.md F16b step 3; ux.md §5.24; build plan 4.2b): the passwords
 * from before a replacement have just opened with their own master password, and before the two
 * sets change places the app asks. Under the title of that screen, "Your passwords from before
 * 7 Oct 2026": "These are your passwords as they were on 7 Oct. Passwords added or changed after
 * that are in the newer set, which stays on this phone. Open the older set instead?", then "Open my
 * passwords from 7 Oct" and "Cancel", which changes nothing. The question is read out when it
 * replaces the field. No field and no pad: nothing can be typed until it is answered. Back
 * returns to the current vault, as on every screen of a vault put aside (lock.md F17 step 4).
 */
@Composable
fun OlderSetScreen(ui: OlderSetUi, onAction: (LockAction) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = ui.goesBack) { onAction(LockAction.Send(LockIntent.GoBack)) }
    val formatters = rememberFormatters()
    LockPage(modifier.testTag(LockTags.OLDER_SET)) {
        ui.asideLine?.let { AsideLineText(it, formatters) }
        Text(
            text = ui.title.show(formatters),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
        )
        Text(
            text = ui.text.show(formatters),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(LockTags.OLDER_SET_TEXT),
        )
        for (button in ui.buttons) LockButtonView(button, formatters, onAction)
    }
}
