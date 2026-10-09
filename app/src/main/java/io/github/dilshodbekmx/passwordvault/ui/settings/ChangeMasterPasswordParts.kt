package io.github.dilshodbekmx.passwordvault.ui.settings

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// The parts that "Change master password" and the screen of a new master password share (build plan
// 5.5): the frame with its top bar, a page of text and buttons, a master-password field that has the
// focus and the keyboard when it appears and again after a rotation, and the last step's new backup.

/**
 * The resource ids (test tags) of the two routes' own controls, for tests and the emulator scripts,
 * whatever the language. Setup's three steps keep their own (`SetupTags`), the PIN pad its own
 * (`PinPadTags`, and `PinTags` for the page around it).
 */
object MasterTags {
    const val BACK = "pv.master.back"
    const val HEADING = "pv.master.heading"
    const val TEXT = "pv.master.text"
    const val LINE = "pv.master.line"

    /** The field of the current master password (Change master password, step 1). */
    const val CURRENT_FIELD = "pv.master.current"
    const val CURRENT_NEXT = "pv.master.current.next"
    /** "I don't remember it", which opens `Route.NewMasterPassword` (the shell's `pv.open.<Route>` form, which `shell/walk.sh` taps). */
    const val DONT_REMEMBER = "pv.open.NewMasterPassword"
    const val CHECKING = "pv.master.checking"
    const val COUNTDOWN = "pv.master.countdown"

    /** "Not now" on the PIN step of a change. */
    const val PIN_NOT_NOW = "pv.master.pin.notNow"
    const val SAVING = "pv.master.saving"
    const val TOUCH_SENSOR = "pv.master.touchSensor"
    const val RETRY = "pv.master.retry"
    const val OK = "pv.master.ok"

    /** The last step: "Back up now", "Later", the list of backups. */
    const val BACK_UP_NOW = "pv.master.backUpNow"
    const val LATER = "pv.master.later"
    const val PLACES = "pv.master.places"

    /** "Delete" for the backup [index] of the list, from 0. */
    fun deletePlace(index: Int): String = "pv.master.place.$index.delete"

    /** The line of the backup [index] of the list, from 0. */
    fun place(index: Int): String = "pv.master.place.$index"

    /** The explanation of a new master password, with "Continue" and "Cancel". */
    const val EXPLAIN = "pv.master.explain"
    const val CONTINUE = "pv.master.continue"
    const val CANCEL = "pv.master.cancel"
    const val REPLACES = "pv.master.replaces"
    const val FORGOT_PIN_TOO = "pv.master.forgotPinToo"
    const val FINGERPRINT_PROMPT = "pv.master.fingerprintPrompt"
    const val USE_FINGERPRINT = "pv.master.useFingerprint"
    const val REQUESTING = "pv.master.requesting"
    const val WAIT = "pv.master.wait"
    const val DONE = "pv.master.done"

    /** The finishing step ("Finish now"). */
    const val FINISH_FIELD = "pv.master.finish.field"
    const val FINISH = "pv.master.finish"
    const val FINISHING = "pv.master.finishing"
}

/**
 * The frame of both routes, as Settings' pages have it: a top bar with the back arrow ([onBack],
 * the same as the Back key; off while [backEnabled] is false) and [title], which is also the pane
 * title TalkBack announces, and the step in the rest of the window.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
internal fun MasterFrame(title: String, onBack: () -> Unit, backEnabled: Boolean, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.testTag(MasterTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_settings_back), contentDescription = stringResource(R.string.master_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

/**
 * A step without a pad: [heading] and the [content] in one column that scrolls, at most 640 dp wide
 * (ux.md §3 "Layout"), above the keyboard.
 */
@Composable
internal fun MasterPage(heading: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag(MasterTags.HEADING),
            )
            content()
        }
    }
}

/** A paragraph of a step. */
@Composable
internal fun MasterText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

/**
 * What the lock said after the last try, or why the step stopped: in the error colour when [alert],
 * announced by TalkBack when it appears.
 */
@Composable
internal fun MasterLine(line: UiText, alert: Boolean = true, tag: String = MasterTags.LINE) {
    Text(
        text = line.resolve(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A spinner and [text] ("Checking…", "Saving…") while the lock works, announced when it appears. */
@Composable
internal fun MasterBusy(text: String, tag: String) {
    Row(
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The countdown of a master-password wait: "Try again in 12 seconds." */
@Composable
internal fun MasterCountdown(seconds: Int) {
    Text(
        text = pluralStringResource(R.plurals.master_wait_seconds, seconds, seconds),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(MasterTags.COUNTDOWN),
    )
}

/**
 * A master-password field ([VaultSecureField], R-13; its state is the ViewModel's) that takes the
 * focus and the keyboard when it appears, and again in every new composition: after a rotation or a
 * language change Android 17 no longer brings the keyboard back by itself [V-P7], so the field asks
 * for it once the window has the window focus and, where the screen can see the keyboard (Android 11
 * and newer), a few more times while it is not shown, as setup's "Type your master password again"
 * does. The keyboard's Done does what [onDone] does.
 */
@Composable
internal fun MasterField(
    state: TextFieldState,
    label: String,
    tag: String,
    onDone: () -> Unit,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    LaunchedEffect(focus, window) {
        snapshotFlow { window.isWindowFocused }.first { it }
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
            return@LaunchedEffect // not laid out any more: the step changed meanwhile
        }
        keyboard?.show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            repeat(KEYBOARD_RETRIES) {
                delay(KEYBOARD_RETRY_MS)
                if (keyboardShown(view)) return@LaunchedEffect
                keyboard?.show()
            }
        }
    }
    VaultSecureField(
        state = state,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(tag),
        label = { Text(label) },
        enabled = enabled,
        isError = isError,
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
        onKeyboardAction = KeyboardActionHandler { onDone() },
    )
}

/** Whether the keyboard is shown over [view]'s window: its insets say so from Android 11 on. */
private fun keyboardShown(view: View): Boolean = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * The last step of a change (lock.md F8 step 9) and of a replacement (F11 step 19): [heading] ("Your
 * master password was changed." or "Your new master password works now."), "Make a new backup now.
 * Your old backups still open only with your old master password." with "Back up now" and "Later",
 * and, when the old master password may be known to others, the backups this app saved with
 * "Delete" ([places]) and "Also delete copies you sent to Telegram or by email."
 */
@Composable
internal fun NewBackupStep(
    heading: String,
    places: List<BackupPlace>,
    onBackUpNow: () -> Unit,
    onLater: () -> Unit,
    onDelete: (FileRef) -> Unit,
) {
    MasterPage(heading = heading) {
        MasterText(stringResource(R.string.master_backup_text), Modifier.testTag(MasterTags.TEXT))
        if (places.isNotEmpty()) Places(places, onDelete)
        Button(onClick = onBackUpNow, modifier = Modifier.fillMaxWidth().testTag(MasterTags.BACK_UP_NOW)) {
            Text(stringResource(R.string.master_back_up_now))
        }
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth().testTag(MasterTags.LATER)) {
            Text(stringResource(R.string.common_later))
        }
    }
}

/** The backups this app saved (ux.md §5.18 step 6, §5.16 card 1), each with "Delete" and what it came to. */
@Composable
private fun Places(places: List<BackupPlace>, onDelete: (FileRef) -> Unit) {
    val formatters = rememberFormatters()
    Column(Modifier.fillMaxWidth().testTag(MasterTags.PLACES), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.master_places_heading),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        places.forEachIndexed { index, place ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.master_place, place.ref.name, formatters.date(place.ref.savedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).testTag(MasterTags.place(index)),
                    )
                    when (place.state) {
                        PlaceState.DELETED -> Text(
                            text = stringResource(R.string.master_place_deleted),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        PlaceState.DELETING -> CircularProgressIndicator(Modifier.size(24.dp))
                        else -> OutlinedButton(onClick = { onDelete(place.ref) }, modifier = Modifier.testTag(MasterTags.deletePlace(index))) {
                            Text(stringResource(R.string.common_delete))
                        }
                    }
                }
                when (place.state) {
                    PlaceState.NOT_DELETABLE -> MasterLine(UiText.of(R.string.master_place_not_deletable), tag = "${MasterTags.place(index)}.line")
                    PlaceState.NOT_NOTED -> MasterLine(UiText.of(R.string.master_place_not_noted), tag = "${MasterTags.place(index)}.line")
                    else -> Unit
                }
            }
        }
        Text(text = stringResource(R.string.master_places_also), style = MaterialTheme.typography.bodyMedium)
    }
}

/** How many more times, and how often, the keyboard is asked for while it is not shown. */
private const val KEYBOARD_RETRIES = 6
private const val KEYBOARD_RETRY_MS = 250L
