package io.github.dilshodbekmx.passwordvault.ui.shell

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.ClipStatus
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The first-copy tip (ux.md §6; build plan 7.3): an alert with "OK", shown once
 * (`OneTime.FIRST_COPY_TIP`) over the unlocked screens, where [UnlockedScaffold] calls it, right
 * after the first copy of a password. It says how to paste, that the copy is removed after the delay
 * chosen in Settings and that anything else copied in that time may go too, never to pin a password
 * in the keyboard's clipboard, that a force-stop or "Restricted" battery use leaves the copy on the
 * clipboard until the app is opened again (architecture.md D-14), and, on a Samsung phone, how to
 * keep passwords out of Samsung Keyboard's clipboard history ([FirstCopyTipModel] decides when).
 */
@Composable
fun FirstCopyTip(graph: AppGraph) {
    FirstCopyTip(graph.settings, graph.clipboard, graph.lock, graph.appScope, graph.dispatchers.io)
}

/**
 * [FirstCopyTip] over these parts: the tip's state lives in a view model of the activity, so that a
 * rotation or a language change keeps it open; the flag is written in [writes] on [io], so that it is
 * written even if the screen goes. [samsung]: the phone is a Samsung.
 */
@Composable
fun FirstCopyTip(
    settings: AppSettings,
    clipboard: SecretClipboard,
    lock: LockManager,
    writes: CoroutineScope,
    io: CoroutineDispatcher,
    samsung: Boolean = FirstCopyTipModel.isSamsung(Build.MANUFACTURER),
) {
    val vm = viewModel { FirstCopyTipViewModel(FirstCopyTipModel(settings, clipboard, lock, writes, io)) }
    val open by vm.model.open.collectAsStateWithLifecycle()
    val delay by settings.clipDelay.collectAsStateWithLifecycle()
    if (open) FirstCopyTipDialog(FirstCopyTipModel.lines(delay, samsung), onOk = vm.model::close)
}

/**
 * The tip: its title, its paragraphs and "OK". Back and a tap outside close it as "OK" does. On a small
 * screen the title and the paragraphs scroll, "OK" stays (VaultDialog).
 */
@Composable
fun FirstCopyTipDialog(lines: List<UiText>, onOk: () -> Unit) {
    VaultDialog(
        onDismissRequest = onOk,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(FirstCopyTipTags.TIP),
        confirmButton = {
            TextButton(onClick = onOk, modifier = Modifier.testTag(FirstCopyTipTags.OK)) {
                Text(stringResource(R.string.common_ok))
            }
        },
        title = { Text(stringResource(R.string.help_tip_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (line in lines) Text(line.resolve())
            }
        },
    )
}

/**
 * When the first-copy tip shows (ux.md §6):
 *
 * - **After the first copy of a password**, or of another secret that is copied flagged as one (a
 *   previous password, a hidden field's value, text selected in a note: [ClipStatus.sensitive]),
 *   because the tip is about pasting a password; a username or a website does not bring it. It
 *   shows for a copy made while it watches that is still on the clipboard ([SecretClipboard.status],
 *   not yet cleared), while the app is unlocked, as long as `OneTime.FIRST_COPY_TIP` is not stored.
 * - **Once.** The flag is written as the tip opens: the tip has then been shown (the flag's own
 *   meaning, `OneTime`), even if the user leaves the app to paste without answering it. If the write
 *   fails, the next copy shows it again, never the same copy twice.
 * - **It closes** with "OK", Back or a tap outside ([close]), and when the app locks: a dialog is not
 *   kept across a lock (ux.md §4). It does not come back for that copy after the unlock.
 *
 * [watch] runs while the tip's view model lives (the activity, across rotations); [open] is what the
 * screen shows. Nothing here is secret: the clip's text is never read.
 */
class FirstCopyTipModel(
    private val settings: AppSettings,
    private val clipboard: SecretClipboard,
    private val lock: LockManager,
    private val writes: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val shown = MutableStateFlow(false)

    /** Whether the tip is on the screen. */
    val open: StateFlow<Boolean> = shown.asStateFlow()

    /** The copy the tip was last opened for, so that it never opens twice for one copy. */
    private var openedFor: ClipStatus? = null

    /**
     * Follows the copies, the stored flags and the lock until cancelled. Main thread. A clip of ours
     * that is already pending when it starts is not a copy made now: a new process restores one from
     * the settings (`AndroidSecretClipboard`, as "Copied text", whatever was copied), and the tip must
     * not open at the unlock that follows.
     */
    suspend fun watch() {
        if (openedFor == null) openedFor = clipboard.status.value
        combine(clipboard.status, settings.oneTime, lock.state) { status, oneTime, state -> Triple(status, oneTime, state) }
            .collect { (status, oneTime, state) -> update(status, oneTime, state) }
    }

    /** "OK", Back or a tap outside. */
    fun close() {
        shown.value = false
    }

    private fun update(status: ClipStatus?, oneTime: Set<OneTime>, state: LockUiState) {
        if (state !is LockUiState.Unlocked) {
            shown.value = false
            return
        }
        if (shown.value || status == null || !status.sensitive || status.cleared) return
        if (OneTime.FIRST_COPY_TIP in oneTime || status === openedFor) return
        openedFor = status
        shown.value = true
        writes.launch(io) { settings.update { it.oneTime = it.oneTime + OneTime.FIRST_COPY_TIP } }
    }

    companion object {
        /**
         * The tip's paragraphs (ux.md §6): how to paste; when the copy is removed, after [delay]; never
         * pin; a force-stop or "Restricted" (D-14); and Samsung Keyboard's clipboard history when
         * [samsung], or other keyboards' lists otherwise.
         */
        fun lines(delay: ClipDelay, samsung: Boolean): List<UiText> = listOf(
            UiText.of(R.string.help_tip_paste),
            clearedText(delay),
            UiText.of(R.string.help_never_pin),
            UiText.of(R.string.help_force_stop),
            UiText.of(if (samsung) R.string.help_tip_samsung else R.string.help_tip_keyboards),
        )

        /** "The password is removed from the clipboard after 1 minute. …": minutes when [delay] is whole minutes, otherwise seconds. */
        fun clearedText(delay: ClipDelay): UiText = if (delay.ms % MINUTE_MS == 0L) {
            UiText.plural(R.plurals.help_tip_cleared_minutes, (delay.ms / MINUTE_MS).toInt())
        } else {
            UiText.plural(R.plurals.help_tip_cleared_seconds, (delay.ms / SECOND_MS).toInt())
        }

        /** Whether [manufacturer] (`Build.MANUFACTURER`) is Samsung's. */
        fun isSamsung(manufacturer: String?): Boolean = manufacturer.equals("samsung", ignoreCase = true)

        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000L
    }
}

/** Keeps a [FirstCopyTipModel] watching for as long as the activity lives, across rotations. */
class FirstCopyTipViewModel(val model: FirstCopyTipModel) : ViewModel() {
    init {
        viewModelScope.launch { model.watch() }
    }
}

/** The tip's test tags, for tests and the emulator scripts (`app.sh`'s scripts close it by its "OK"). */
object FirstCopyTipTags {
    /** The tip's window. */
    const val TIP = "pv.tip"

    /** Its "OK". */
    const val OK = "pv.tip.ok"
}
