package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.ui.common.PinDots
import io.github.dilshodbekmx.passwordvault.ui.common.PinKeys

/**
 * The PIN pad (ux.md §5.9; lock.md §13.1 row 5, §13.2): the app's logo, "PasswordVault", "Enter
 * your app PIN", the six dots, the status line, the keypad of `ui/common` with the fingerprint key
 * when fingerprint can be used, "Forgot PIN? Use master password", and the test build's line.
 *
 * - The dots and keys are `PinDots` and `PinKeys` as they are: the keys report presses, the dots
 *   show [PadUi.digits] only, so no node ever holds a digit, and both are marked sensitive. Each new
 *   [PadUi.wrongTries] shakes the dots with the reject haptic (lock.md F4 step 2).
 * - While the PIN is checked ([PadUi.opening]) a spinner and "Opening…" replace the keypad, and Back
 *   is ignored for that second (ux.md §4).
 * - **Portrait:** one column that scrolls with large fonts. **Landscape:** the texts and the dots on
 *   the left, the keypad on the right (ux.md §5.9); each side scrolls on its own.
 * - **Bank blue** (docs/design/looks/README.md "Lock screen"): the head (the line of a vault put
 *   aside, the logo, the title, the subtitle and the dots) lies on the look's lock panel, across the
 *   top in portrait and as a block on the left in landscape ([LockPage], [LockHead]).
 * - On the screen of a vault put aside, Back returns to the current vault (lock.md F17 step 4).
 */
@Composable
fun PadScreen(
    ui: PadUi,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onAction: (LockAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = ui.goesBack && !ui.opening) { onAction(LockAction.Send(LockIntent.GoBack)) }
    BackHandler(enabled = ui.opening) { /* ignored while the PIN is checked (ux.md §4) */ }
    val formatters = rememberFormatters()
    val keys: @Composable () -> Unit = {
        if (ui.opening) {
            OpeningRow(Modifier.heightIn(min = KEYPAD_HEIGHT))
        } else {
            PinKeys(
                onDigit = onDigit,
                onDelete = onDelete,
                onFingerprint = if (ui.fingerprintKey) ({ onAction(LockAction.Send(LockIntent.UseFingerprint)) }) else null,
                onClear = onClear,
                enabled = true,
                modifier = Modifier.widthIn(max = LOCK_COLUMN_MAX),
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag(LockTags.PAD)) {
        if (maxWidth > maxHeight && maxHeight < LANDSCAPE_BELOW) {
            Row(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LockHead { PadHead(ui, formatters, logo = false) }
                    PadFoot(ui, formatters, onAction)
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    keys()
                }
            }
        } else {
            LockPage(head = { PadHead(ui, formatters, logo = true) }) {
                PadFoot(ui, formatters, onAction, keys = keys)
            }
        }
    }
}

/**
 * The pad's head: the line of a vault put aside, the logo (not in landscape), the title, "Enter your
 * app PIN" and the dots; on the look's lock panel in its colours ([LocalLockPanel]).
 */
@Composable
private fun PadHead(ui: PadUi, formatters: Formatters, logo: Boolean) {
    val panel = LocalLockPanel.current
    ui.asideLine?.let { AsideLineText(it, formatters) }
    if (logo) LockMark()
    AppTitle()
    Text(
        text = stringResource(R.string.lock_pad_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = panel?.quiet ?: Color.Unspecified,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(LockTags.SUBTITLE),
    )
    PinDots(
        entered = ui.digits,
        wrongTries = ui.wrongTries,
        modifier = Modifier.padding(vertical = 8.dp),
        filled = panel?.content ?: MaterialTheme.colorScheme.primary,
        empty = panel?.ring ?: MaterialTheme.colorScheme.outline,
    )
}

/**
 * The rest of the pad's column: the status line, then [keys] when they come in the same column,
 * "Forgot PIN? Use master password" and the test build's line.
 */
@Composable
private fun PadFoot(ui: PadUi, formatters: Formatters, onAction: (LockAction) -> Unit, keys: (@Composable () -> Unit)? = null) {
    ui.status?.let { LockLineText(it, formatters, LockTags.STATUS, centred = true) }
    keys?.invoke()
    for (button in ui.buttons) LockButtonView(button, formatters, onAction, enabled = !ui.opening)
    if (ui.testBuild) TestBuildLine()
}

/** About the height of the keypad's four rows, so that "Opening…" takes its place without the page jumping. */
private val KEYPAD_HEIGHT = 320.dp

/** Below this height a landscape window lays the pad out in two columns (ux.md §5.9 "Landscape"). */
private val LANDSCAPE_BELOW = 600.dp
