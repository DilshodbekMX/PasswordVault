package io.github.dilshodbekmx.passwordvault.clipboard

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.ClipboardRow
import kotlinx.coroutines.delay

/** The test tags of the clipboard row; inside the unlocked screens' bottom area they are resource ids for the emulator scripts. */
object ClipboardTags {
    /** The status row, while a clip of ours is pending or was just cleared. */
    const val ROW = "pv.clip.row"
}

/** What the row says at one moment (ux.md §6 "Clipboard status row"). */
internal sealed interface ClipRowState {
    /** "Password for Gmail · clears in 0:52": [leftMs] until the planned clear. */
    data class Counting(val leftMs: Long) : ClipRowState

    /** The planned time has passed and the clear has not run: "Clearing…", never a negative countdown. */
    data object Clearing : ClipRowState

    /** The clear ran: "Clipboard cleared" for two seconds. */
    data object Cleared : ClipRowState
}

/** The row's state for [status] at [nowElapsedMs] on the elapsed clock. */
internal fun clipRowState(status: ClipStatus, nowElapsedMs: Long): ClipRowState = when {
    status.cleared -> ClipRowState.Cleared
    nowElapsedMs >= status.clearsAtElapsedMs -> ClipRowState.Clearing
    else -> ClipRowState.Counting(status.clearsAtElapsedMs - nowElapsedMs)
}

/**
 * The place of the clipboard status row in the bottom area: nothing while [status] is null,
 * otherwise [ClipStatusRow], counting down once a second while the screen is shown.
 *
 * The row keeps clear of the system's bars. To the sides it always does; below it only when it is
 * the lowest thing on the screen (an account's screen has no navigation bar under it), where its
 * colour goes on behind the system's navigation bar as the app's own bar does. Whether something
 * lies below is read from where the slot was placed, also while it is empty, so the row is right
 * from its first frame.
 *
 * @param elapsedMs `Clock.elapsedMs`, the clock of [ClipStatus.clearsAtElapsedMs].
 */
@Composable
internal fun ClipStatusSlot(status: ClipStatus?, elapsedMs: () -> Long, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var lowest by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
            val bottom = coordinates.positionInRoot().y + coordinates.size.height
            lowest = bottom >= coordinates.findRootCoordinates().size.height - 1
        },
    ) {
        if (status != null) {
            val now by elapsedNow(status, elapsedMs)
            val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
            val insets = if (lowest) bars.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else bars
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).windowInsetsPadding(insets)) {
                ClipStatusRow(status, now, onClear, Modifier.testTag(ClipboardTags.ROW))
            }
        }
    }
}

/**
 * The clipboard status row for [status] at [nowElapsedMs] (ux.md §6): the look is `ClipboardRow`'s
 * (ui/common), the texts are [clipRowTexts]'s.
 */
@Composable
internal fun ClipStatusRow(
    status: ClipStatus,
    nowElapsedMs: Long,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    sdk: Int = Build.VERSION.SDK_INT,
) {
    val texts = clipRowTexts(status, nowElapsedMs, sdk)
    ClipboardRow(
        text = texts.text,
        announcement = texts.announcement,
        modifier = modifier,
        headline = texts.headline,
        actionLabel = texts.action,
        onAction = onClear,
        cleared = texts.cleared,
    )
}

/**
 * What the row shows and says.
 *
 * @property text what was copied and the time left; "Clearing…" when the time is up and the clear
 *   has not run; "Clipboard cleared" after it.
 * @property announcement what TalkBack hears once when the row appears, and again only when it
 *   changes: "Password copied. It will be removed from the clipboard in 1 minute.", then "Clipboard
 *   cleared". It does not change while the row counts down.
 * @property headline on Android 7 to 12, which show no confirmation of their own, a line above
 *   [text]: "Password copied" for a secret of an account, "Copied" for anything else, selected text
 *   included. Null on Android 13 and later, and once the clear has run.
 * @property action "Clear now", until the clear has run.
 * @property cleared the clear has run: a check mark in place of the key.
 */
internal data class ClipRowTexts(
    val text: String,
    val announcement: String,
    val headline: String?,
    val action: String?,
    val cleared: Boolean,
)

/** The texts of the row for [status] at [nowElapsedMs], in the composition's language, on API level [sdk]. */
@Composable
internal fun clipRowTexts(status: ClipStatus, nowElapsedMs: Long, sdk: Int = Build.VERSION.SDK_INT): ClipRowTexts {
    val state = clipRowState(status, nowElapsedMs)
    val text = when (state) {
        is ClipRowState.Counting ->
            stringResource(R.string.clipboard_row_clears_in, status.what.resolve(), rememberFormatters().countdown(state.leftMs))
        ClipRowState.Clearing -> stringResource(R.string.clipboard_row_clearing)
        ClipRowState.Cleared -> stringResource(R.string.clipboard_row_cleared)
    }
    val cleared = state == ClipRowState.Cleared
    // The sensitive flag is all the row knows of what was copied; selected text has it too, and is not a password.
    val secretOfAnAccount = status.sensitive && status.what != AndroidSecretClipboard.COPIED_TEXT
    val copied = stringResource(if (secretOfAnAccount) R.string.clipboard_copied_password else R.string.clipboard_copied_other)
    return ClipRowTexts(
        text = text,
        // A clip that was already overdue when it was found (a new process) has no time left to announce.
        announcement = if (cleared || status.delayMs <= 0L) text else stringResource(R.string.clipboard_announcement, copied, removedIn(status.delayMs)),
        headline = copied.takeIf { sdk < 33 && !cleared },
        action = stringResource(R.string.clipboard_clear_now).takeIf { !cleared },
        cleared = cleared,
    )
}

/** "It will be removed from the clipboard in 1 minute.": whole minutes as minutes, anything else as seconds, rounded up. */
@Composable
private fun removedIn(delayMs: Long): String {
    if (delayMs % MINUTE_MS == 0L) {
        val minutes = (delayMs / MINUTE_MS).toInt()
        return pluralStringResource(R.plurals.clipboard_removed_in_minutes, minutes, minutes)
    }
    val seconds = ((delayMs + SECOND_MS - 1L) / SECOND_MS).toInt()
    return pluralStringResource(R.plurals.clipboard_removed_in_seconds, seconds, seconds)
}

/**
 * The elapsed clock for the row of [status]: read when the row appears, and again each time the
 * shown second changes, while the screen is started (a screen that comes back reads it at once). It
 * stops at the planned clear, after which the row no longer counts.
 */
@Composable
internal fun elapsedNow(status: ClipStatus, elapsedMs: () -> Long): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by rememberUpdatedState(elapsedMs)
    val now = remember(status) { mutableLongStateOf(elapsedMs()) }
    LaunchedEffect(status, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val read = clock()
                now.longValue = read
                val leftMs = status.clearsAtElapsedMs - read
                if (status.cleared || leftMs <= 0L) break
                delay((leftMs - 1L) % SECOND_MS + 1L)
            }
        }
    }
    return now
}

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
