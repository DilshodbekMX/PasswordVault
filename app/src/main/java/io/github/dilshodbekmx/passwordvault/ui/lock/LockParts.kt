package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import io.github.dilshodbekmx.passwordvault.ui.common.KeypadColumn
import io.github.dilshodbekmx.passwordvault.ui.common.LongLabelShape
import io.github.dilshodbekmx.passwordvault.ui.common.fadeWhereMore
import io.github.dilshodbekmx.passwordvault.ui.theme.LocalLook
import io.github.dilshodbekmx.passwordvault.ui.theme.LockPanel
import io.github.dilshodbekmx.passwordvault.ui.theme.StatusBarIconsOver

/*
 * The parts the lock screens share: the page with its head (on Bank blue's panel), the app's logo,
 * the line of a vault put aside, the test build's line, the buttons, and texts with their dates
 * formatted in the composition's language.
 */

/** The widest a lock screen's column grows, so that a tablet or a phone in landscape keeps short lines. */
internal val LOCK_COLUMN_MAX = 480.dp

/** The lock panel's lower corners in portrait, as in the mock-up (32 px of a 390 px screen). */
private val PANEL_CORNER = 32.dp

/** The corners of the lock panel as a block beside the keypad (landscape). */
private val PANEL_BLOCK_CORNER = 28.dp

/** What the pad's head keeps below its texts, however short: the dots with their padding and the space above them. */
private val HEAD_DOTS = 42.dp

/** One line of the head's texts at 200 %, the least of them that stays in view. */
private val HEAD_LINE = 40.dp

/** Between the parts of a lock screen's head. */
private val PANEL_SPACING = 12.dp

/**
 * A lock screen's page: a centred column of at most [LOCK_COLUMN_MAX], clear of the system bars and
 * the keyboard, that scrolls when large fonts or a small screen need it (ux.md §5.9 "nothing is cut
 * off"): its [head], then its [content].
 *
 * The head is the top of the PIN pad and of the fingerprint-only screen (the line of a vault put
 * aside, the logo, the name, and on the pad the subtitle and the dots). Where the look has a lock
 * panel (Bank blue, docs/design/looks/README.md "Lock screen"; `Look.lockPanel`), the head lies on
 * it: a panel the width of the screen, from its top edge behind the status bar, whose icons are
 * light while it shows ([StatusBarIconsOver]), with rounded lower corners. Elsewhere the head is the
 * start of the one column.
 */
@Composable
internal fun LockPage(
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    head: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val panel = LocalLook.current.lockPanel
    if (head == null || panel == null) {
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
            ) {
                head?.invoke(this)
                content()
            }
        }
        return
    }
    StatusBarIconsOver(panel.colour)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LockPanelBox(
            panel = panel,
            modifier = Modifier
                .testTag(LockTags.PANEL)
                .fillMaxWidth()
                .background(panel.colour, RoundedCornerShape(bottomStart = PANEL_CORNER, bottomEnd = PANEL_CORNER))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            content = head,
        )
        Column(
            modifier = Modifier
                .padding(start = 24.dp, end = 24.dp, top = spacing, bottom = 32.dp)
                .widthIn(max = LOCK_COLUMN_MAX)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * The PIN pad's upright page (ux.md §5.9): [LockPage]'s head, on the look's lock panel where it has
 * one, with the [dots] at its foot; under it the [status] line, the [keys] and the [foot] (the
 * buttons). A [KeypadColumn] keeps the dots and the whole keypad on the screen together, with the
 * head whole above them where the screen allows: on a short screen, or with the phone's font at
 * 200 %, the buttons go below the first screen, then the keys shrink (never under 64 dp), and only
 * then do the head's texts get the height that is left and scroll there, above the dots (build plan
 * 6.2: at 320 dp and 200 % the keys 7, 8, 9 and 0 lay below the screen's edge). The panel still
 * reaches up behind the status bar, whose icons are light while it shows.
 */
@Composable
internal fun LockPadPage(
    head: @Composable ColumnScope.() -> Unit,
    dots: @Composable ColumnScope.() -> Unit,
    status: @Composable ColumnScope.() -> Unit,
    keys: @Composable () -> Unit,
    foot: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = LocalLook.current.lockPanel
    if (panel != null) StatusBarIconsOver(panel.colour)
    val insets = if (panel == null) WindowInsets.safeDrawing else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val density = LocalDensity.current
    val top = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
    // The head may shrink to its panel's padding, the status bar it reaches behind, the dots and one
    // line of its texts: what is left of its texts then scrolls above the dots.
    val minHead = (if (panel == null) 32.dp else top + 44.dp) + HEAD_DOTS + HEAD_LINE
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(insets)) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            KeypadColumn(
                viewport = viewport,
                minHead = minHead,
                modifier = Modifier.fillMaxWidth(),
                head = { PadHeadBlock(panel, head, dots) },
                dots = {
                    Column(
                        modifier = Modifier.widthIn(max = LOCK_COLUMN_MAX).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = status,
                    )
                },
                keys = { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { keys() } },
                foot = {
                    Column(
                        modifier = Modifier.widthIn(max = LOCK_COLUMN_MAX).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = foot,
                    )
                },
            )
        }
    }
}

/**
 * The pad's head on the panel (or on the screen's own background): its texts in a column that
 * scrolls within the height the page leaves it, and the [dots] under them, always shown.
 */
@Composable
private fun PadHeadBlock(panel: LockPanel?, head: @Composable ColumnScope.() -> Unit, dots: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val behind = panel?.colour ?: MaterialTheme.colorScheme.surface
    val texts: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.weight(1f, fill = false).fadeWhereMore(scroll, behind).verticalScroll(scroll).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_SPACING),
            content = head,
        )
        Spacer(Modifier.height(PANEL_SPACING))
        dots()
    }
    if (panel == null) {
        Column(
            modifier = Modifier
                .widthIn(max = LOCK_COLUMN_MAX)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 24.dp, end = 24.dp, top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = texts,
        )
    } else {
        LockPanelBox(
            panel = panel,
            modifier = Modifier
                .testTag(LockTags.PANEL)
                .fillMaxWidth()
                .background(panel.colour, RoundedCornerShape(bottomStart = PANEL_CORNER, bottomEnd = PANEL_CORNER))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            content = texts,
        )
    }
}

/**
 * The head of a lock screen in a column of its own (the pad in landscape): on the look's lock panel,
 * a block with rounded corners, which does not reach the status bar; without a panel, as it is.
 */
@Composable
internal fun LockHead(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val panel = LocalLook.current.lockPanel
    if (panel == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_SPACING),
            content = content,
        )
    } else {
        LockPanelBox(
            panel = panel,
            modifier = modifier
                .testTag(LockTags.PANEL)
                .fillMaxWidth()
                .background(panel.colour, RoundedCornerShape(PANEL_BLOCK_CORNER))
                .padding(16.dp),
            content = content,
        )
    }
}

/**
 * The panel's content in a column of at most [LOCK_COLUMN_MAX], drawn in the panel's colours (the
 * panel itself is the background in [modifier]): its texts in [LockPanel.content], and
 * [LocalLockPanel] for the logo, the line of a vault put aside, the subtitle and the dots.
 */
@Composable
private fun LockPanelBox(panel: LockPanel, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalContentColor provides panel.content, LocalLockPanel provides panel) {
            Column(
                modifier = Modifier.widthIn(max = LOCK_COLUMN_MAX).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PANEL_SPACING),
                content = content,
            )
        }
    }
}

/** The lock panel that what is drawn lies on, set by [LockPage] and [LockHead]; null: the screen's own colours. */
internal val LocalLockPanel = staticCompositionLocalOf<LockPanel?> { null }

/**
 * The app's logo above the title, 64 dp (ux.md §5.9): the launcher icon's shield on a tile, as on
 * Welcome (`AppLogo`, docs/design/looks/README.md "The icon and the name"); on the lock panel in its
 * colours, as the mock-up draws it. It only decorates.
 */
@Composable
internal fun LockMark() {
    val panel = LocalLockPanel.current
    if (panel == null) {
        AppLogo(Modifier.testTag(LockTags.LOGO))
    } else {
        AppLogo(Modifier.testTag(LockTags.LOGO), tile = panel.logoTile, mark = panel.content, ring = panel.logoRing)
    }
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

/** "Passwords locked on 6 Oct 2026" above the title of a vault put aside's screen; on the lock panel in its colour. */
@Composable
internal fun AsideLineText(text: UiText, formatters: Formatters) {
    Text(
        text = text.show(formatters),
        style = MaterialTheme.typography.titleSmall,
        color = LocalLockPanel.current?.content ?: MaterialTheme.colorScheme.primary,
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

/**
 * A button of a lock screen, drawn as its [LockButton.style] says; full width unless it is a text button.
 * Its label may take several lines ("Open my previous passwords (locked on 7 Oct 2026)"): [LongLabelShape].
 */
@Composable
internal fun LockButtonView(button: LockButton, formatters: Formatters, onAction: (LockAction) -> Unit, enabled: Boolean = true) {
    val label = button.label.show(formatters)
    when (button.style) {
        ButtonStyle.FILLED -> Button(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(button.tag), shape = LongLabelShape) {
            Text(label, textAlign = TextAlign.Center)
        }
        ButtonStyle.OUTLINED -> OutlinedButton(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(button.tag), shape = LongLabelShape) {
            Text(label, textAlign = TextAlign.Center)
        }
        ButtonStyle.TEXT -> TextButton(onClick = { onAction(button.action) }, enabled = enabled, modifier = Modifier.testTag(button.tag), shape = LongLabelShape) {
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
