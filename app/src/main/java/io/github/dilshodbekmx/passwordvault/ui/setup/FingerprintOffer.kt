package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/**
 * "Use your fingerprint?" (ux.md §5.7; lock.md §9.4, F9): what fingerprint unlock does, who can open
 * the app with it ("Anyone whose fingerprint is saved in this phone's settings …"), and when it turns
 * itself off; "Use fingerprint", which opens the phone's prompt "Turn on fingerprint unlock" (through
 * the lock), and "Not now". While the prompt is open both buttons wait. A failure says why on [line];
 * setup is never blocked.
 *
 * The screen is shown only when `canAuthenticate(BIOMETRIC_STRONG)` said 0 (lock.md §9.4); the PIN
 * steps decide that before they open it.
 */
@Composable
fun FingerprintOfferScreen(busy: Boolean, line: PinLine?, onUse: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    SetupPage(
        title = stringResource(R.string.pin_fingerprint_title),
        modifier = modifier.semantics { testTagsAsResourceId = true },
        top = {
            Icon(
                painter = painterResource(R.drawable.ic_common_fingerprint),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        },
    ) {
        BodyText(stringResource(R.string.pin_fingerprint_body))
        BodyText(stringResource(R.string.pin_fingerprint_anyone), Modifier.testTag(FingerprintOfferTags.ANYONE))
        Text(
            text = stringResource(R.string.pin_fingerprint_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (line != null) StatusLine(line, FingerprintOfferTags.LINE)
        if (busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        Button(onClick = onUse, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(FingerprintOfferTags.USE)) {
            Text(stringResource(R.string.pin_fingerprint_use))
        }
        TextButton(onClick = onNotNow, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(FingerprintOfferTags.NOT_NOW)) {
            Text(stringResource(R.string.common_not_now))
        }
    }
}

/** The resource ids of the fingerprint offer. */
object FingerprintOfferTags {
    const val ANYONE = "pv.fingerprintOffer.anyone"
    const val LINE = "pv.fingerprintOffer.line"
    const val USE = "pv.fingerprintOffer.use"
    const val NOT_NOW = "pv.fingerprintOffer.notNow"
}
