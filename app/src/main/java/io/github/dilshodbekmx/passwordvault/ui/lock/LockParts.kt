package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.AppLogo

/*
 * The parts the lock screens share: the page, the app's logo, the line of a vault put aside, the
 * test build's line, the buttons, and texts with their dates formatted in the composition's language.
 */

/** The widest a lock screen's column grows, so that a tablet or a phone in landscape keeps short lines. */
internal val LOCK_COLUMN_MAX = 480.dp

/**
 * A lock screen's page: a centred column of at most [LOCK_COLUMN_MAX], clear of the system bars and
 * the keyboard, that scrolls when large fonts or a small screen need it (ux.md §5.9 "nothing is cut
 * off").
 */
@Composable
internal fun LockPage(modifier: Modifier = Modifier, spacing: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = LOCK_COLUMN_MAX).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * The app's logo above the title, 64 dp (ux.md §5.9): the launcher icon's shield on a tile, as on
 * Welcome (`AppLogo`, docs/design/looks/README.md "The icon and the name"). It only decorates.
 */
@Composable
internal fun LockMark() {
    AppLogo(Modifier.testTag(LockTags.LOGO))
}

/** The title "PasswordVault" of the pad and the fingerprint-only screen (ux.md §5.9), a heading. */
@Composable
internal fun AppTitle() {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() }.testTag(LockTags.TITLE),
    )
}

/** "Passwords locked on 6 Oct 2026" above the title of a vault put aside's screen. */
@Composable
internal fun AsideLineText(text: UiText, formatters: Formatters) {
    Text(
        text = text.show(formatters),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(LockTags.ASIDE),
    )
}

/** "TEST BUILD: PIN keys are not protected", in red, in debug and staging builds (lock.md §4.5, §13.3). */
@Composable
internal fun TestBuildLine() {
    Text(
        text = stringResource(R.string.lock_test_build),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(LockTags.TEST_BUILD),
    )
}

/**
 * A status line or a line after a try: the error colour for what went wrong, read out by TalkBack
 * when it changes (a polite live region), centred under the pad or full width under a field.
 */
@Composable
internal fun LockLineText(line: LockLine, formatters: Formatters, tag: String, modifier: Modifier = Modifier, centred: Boolean = false) {
    Text(
        text = line.text.show(formatters),
        style = MaterialTheme.typography.bodyMedium,
        color = if (line.alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
    )
}

/** A button of a lock screen, drawn as its [LockButton.style] says; full width unless it is a text button. */
@Composable
internal fun LockButtonView(button: LockButton, formatters: Formatters, onAction: (LockAction) -> Unit, enabled: Boolean = true) {
    val label = button.label.show(formatters)
    when (button.style) {
        ButtonStyle.FILLED -> Button(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(button.tag)) {
            Text(label, textAlign = TextAlign.Center)
        }
        ButtonStyle.OUTLINED -> OutlinedButton(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(button.tag)) {
            Text(label, textAlign = TextAlign.Center)
        }
        ButtonStyle.TEXT -> TextButton(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.testTag(button.tag)) {
            Text(label, textAlign = TextAlign.Center)
        }
    }
}

/** "Opening…" with a spinner, read out once (lock.md §13.2 "Opening"), in place of the keypad or of "Unlock". */
@Composable
internal fun OpeningRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(LockTags.OPENING),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(stringResource(R.string.lock_opening), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** This text in the composition's language, its [LockDate]s formatted by [formatters]. */
@Composable
internal fun UiText.show(formatters: Formatters): String = withDates(formatters).resolve()

/** [this] with every [LockDate] argument, also inside a nested [UiText], replaced by its formatted date. */
internal fun UiText.withDates(formatters: Formatters): UiText = when (this) {
    is UiText.Res -> copy(args = args.map { it.formatted(formatters) })
    is UiText.Plural -> copy(args = args.map { it.formatted(formatters) })
}

private fun Any.formatted(formatters: Formatters): Any = when (this) {
    is LockDate -> when (style) {
        LockDate.Style.DAY_MONTH -> formatters.dayMonth(ms)
        LockDate.Style.DATE -> formatters.date(ms)
        LockDate.Style.DATE_TIME -> formatters.dateTime(ms)
        LockDate.Style.TIME -> formatters.time(ms)
    }
    is UiText -> withDates(formatters)
    else -> this
}
