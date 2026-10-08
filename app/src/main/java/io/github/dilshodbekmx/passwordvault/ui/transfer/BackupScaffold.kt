package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import kotlinx.coroutines.flow.first

/**
 * The frame of the backup, restore and old-passwords screens: a top bar with Back and the [title],
 * then the content in one scrolling column, at most 640 dp wide (ux.md §3 "Layout"). The screen's
 * root carries [title] as its pane title, and its test tags are exposed as resource ids.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun BackupScaffold(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    KeyboardFirstOnBack()
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(BackupTags.BACK)) {
                        Icon(BackArrow, contentDescription = stringResource(R.string.backup_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding() // the field's step scrolls above the keyboard, its buttons too
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/**
 * Back while the keyboard is up closes the keyboard, and does nothing else. On Android 13 and newer
 * Back reaches the app's own callbacks before the keyboard's when the app registered its callback
 * after the keyboard was shown, which happens when a screen opens over a field that kept the
 * keyboard (the master password of an unlock, then a paused backup: measured on Android 17, where
 * one Back gave the paused backup up). The screen's own Back, which may give a task up, then waits
 * for a second Back. Composed after the screen's own BackHandler, so that it comes first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardFirstOnBack() {
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(enabled = WindowInsets.isImeVisible) { keyboard?.hide() }
}

/** A paragraph of these screens; [alert] draws it in the error colour (a message about something that went wrong). */
@Composable
fun BackupLine(text: UiText, tag: String, modifier: Modifier = Modifier, alert: Boolean = false, formatters: Formatters = rememberFormatters()) {
    Text(
        text = text.withDates(formatters).resolve(),
        style = MaterialTheme.typography.bodyLarge,
        color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (alert) liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
    )
}

/** A step that runs: a spinner and its [text], announced once. */
@Composable
fun BackupProgress(text: UiText, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(BackupTags.PROGRESS),
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(text.resolve(), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A master-password field of these screens (`VaultSecureField`: masked, autofill off, sensitive,
 * the keyboard's Done submits). It takes the keyboard when it is shown and whenever it can be typed
 * into again ([enabled]), once the window has focus, so that the request reaches the keyboard; a
 * rotation shows it again, and Android 17 no longer brings the keyboard back by itself ([V-P7]).
 */
@Composable
fun BackupPasswordField(state: TextFieldState, label: String, enabled: Boolean, isError: Boolean, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focusRequester.requestFocus()
        keyboard?.show()
    }
    VaultSecureField(
        state = state,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester).testTag(BackupTags.FIELD),
        label = { Text(label) },
        enabled = enabled,
        isError = isError,
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
        onKeyboardAction = { onSubmit() },
    )
}

/** The top bar's back arrow (Material's "arrow back"), mirrored in a right-to-left language. */
private val BackArrow: ImageVector = ImageVector.Builder(
    name = "BackupBack",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = true,
).path(fill = SolidColor(Color.Black)) {
    moveTo(20f, 11f)
    horizontalLineTo(7.83f)
    lineToRelative(5.59f, -5.59f)
    lineTo(12f, 4f)
    lineToRelative(-8f, 8f)
    lineToRelative(8f, 8f)
    lineToRelative(1.41f, -1.41f)
    lineTo(7.83f, 13f)
    horizontalLineTo(20f)
    close()
}.build()
