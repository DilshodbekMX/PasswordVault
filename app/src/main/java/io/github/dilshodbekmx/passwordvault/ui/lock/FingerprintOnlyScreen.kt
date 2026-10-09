package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent

/**
 * The fingerprint-only screen (ux.md §5.9; lock.md §13.1 row 6, §13.2): fingerprint can be used and
 * the PIN cannot. The app's logo, "PasswordVault", a fingerprint that only decorates, the status
 * line (a fingerprint line with "Use your master password"), the line about why there is no pad,
 * "Use fingerprint" and "Use master password", and the test build's line. No pad and no dots. The
 * system's prompt opens over it by itself (the lock decides when, lock.md §9.2 rule 7). On the
 * screen of a vault put aside, Back returns to the current vault (lock.md F17 step 4).
 */
@Composable
fun FingerprintOnlyScreen(ui: FingerprintOnlyUi, onAction: (LockAction) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = ui.goesBack) { onAction(LockAction.Send(LockIntent.GoBack)) }
    val formatters = rememberFormatters()
    LockPage(modifier.testTag(LockTags.FINGERPRINT_ONLY)) {
        ui.asideLine?.let { AsideLineText(it, formatters) }
        LockMark()
        AppTitle()
        Icon(
            painter = painterResource(R.drawable.ic_common_fingerprint),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 16.dp).size(96.dp),
        )
        ui.status?.let { LockLineText(it, formatters, LockTags.STATUS, centred = true) }
        ui.pinReason?.let {
            Text(
                text = it.show(formatters),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(LockTags.PIN_REASON),
            )
        }
        for (button in ui.buttons) LockButtonView(button, formatters, onAction)
        if (ui.testBuild) TestBuildLine()
    }
}
