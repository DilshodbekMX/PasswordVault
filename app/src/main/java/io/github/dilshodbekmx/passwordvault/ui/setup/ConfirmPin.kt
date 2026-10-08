package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH

/**
 * "Enter the PIN again" (ux.md §5.6; lock.md §7.3): the dots and the pad, and "The PINs don't match.
 * Try again." after a mismatch (after the third the first entry is chosen again). Once the two
 * entries match, "Saving…" takes the pad's place while the lock saves the PIN, then "Your PIN is
 * set."; if it could not be saved, the line says so with "Try again" and "Not now".
 */
@Composable
fun ConfirmPinScreen(
    entered: Int,
    line: PinLine?,
    shakes: Int,
    saving: Boolean,
    saved: Boolean,
    saveFailed: Boolean,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.pin_confirm_title),
) {
    val savingText = stringResource(R.string.pin_saving)
    val setText = stringResource(R.string.pin_set)
    PinStepPage(
        title = title,
        entered = if (saving || saved) PIN_LENGTH else entered,
        onDigit = onDigit,
        onDelete = onDelete,
        onClear = onClear,
        modifier = modifier,
        line = line,
        shakes = shakes,
        cover = when {
            saving -> {
                { BusyCover(savingText) }
            }
            saved -> {
                { DoneCover(setText) }
            }
            else -> null
        },
        body = { BodyText(stringResource(R.string.pin_confirm_body)) },
        buttons = {
            if (saveFailed) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag(PinTags.RETRY)) {
                    Text(stringResource(R.string.common_try_again))
                }
                TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().testTag(PinTags.NOT_NOW)) {
                    Text(stringResource(R.string.common_not_now))
                }
            }
        },
    )
}
