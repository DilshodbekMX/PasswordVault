package io.github.dilshodbekmx.passwordvault.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.SettingsEditor
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordAsk
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The view model of Settings (ux.md §5.18; build plan 4.3): the list's rows as facts and [UiText]s,
 * the dialogs of the lock delay and the clipboard delay, the two choices of "Appearance" (the
 * colours, and light or dark; docs/design/looks/README.md), the backup reminder, and the fingerprint
 * switch.
 *
 * - **What is shown is what is stored.** The delays, the look and the reminder are the settings'
 *   own flows, so the rows show what `AppSettings.writeDefaults` wrote at setup (30 seconds and
 *   1 minute), or the strictest values when nothing was written ("Immediately", 30 seconds; D-7).
 *   A choice is written at once, off the main thread; a write that fails changes nothing and says so.
 * - **The colours offered** are those this phone has ([phoneColours]): "Phone's colours" only from
 *   Android 12.
 * - **The App lock rows** follow `LockManager.status`: "Change PIN" or "Set a PIN" with why the PIN
 *   does not work (lock.md §13.3), and the fingerprint switch with the reasons of lock.md §9.4.
 *   Making the lock stricter needs nothing (lock.md §8.2): turning fingerprint off, "Lock now" and
 *   the delays. Turning fingerprint on adds a way in, so it asks for the master password first
 *   ([fingerprintPassword], `MpPurpose.FINGERPRINT_ON`), then the lock shows the prompt; the proof is
 *   dropped when that ends. "Set a PIN" and "Change PIN" are their own route (`ChangePinViewModel`).
 * - **Lock now** is `Session.lockNow`: our clip cleared, the draft flushed, the app locked.
 * - Writes and the lock's calls run in [work], the app's scope, so that leaving the screen cannot
 *   cut them in half.
 */
class SettingsViewModel(
    private val lock: LockManager,
    private val settings: AppSettings,
    private val session: Session,
    repository: VaultRepository,
    private val files: DocumentIo,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val work: CoroutineScope,
    phoneColours: Boolean = StyleChoice.hasPhoneColours(Build.VERSION.SDK_INT),
) : ViewModel() {

    /** The choices of "Colours" on this phone, in the dialog's order. */
    private val styles: List<StyleChoice> = StyleChoice.offered(phoneColours)

    /** The master password asked before fingerprint unlock is turned on (lock.md F9 step 2). */
    val fingerprintPassword = MasterPasswordAsk(lock, clock, MpPurpose.FINGERPRINT_ON, work, viewModelScope)

    private val local = MutableStateFlow(Local())

    /** The newest backup this app saved of the open vault, with its place; null when there is none. */
    private val lastBackup: StateFlow<LastBackup?> = repository.state
        .map { state -> (state as? VaultState.Unlocked)?.vault?.backups?.maxByOrNull { it.savedAt } }
        .distinctUntilChanged()
        .map { ref -> ref?.let { LastBackup(it.savedAt, placeOf(it.uri)) } }
        .flowOn(dispatchers.io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val stored = combine(settings.lockDelay, settings.clipDelay, settings.theme, settings.style, settings.backupReminder) { lockDelay, clipDelay, theme, style, reminder ->
        Stored(lockDelay, clipDelay, theme, style, reminder.on)
    }

    /** What the Settings list and the App lock page show. */
    val ui: StateFlow<SettingsUi> = combine(lock.status, stored, lastBackup, local) { status, stored, backup, local ->
        settingsUi(status, stored, backup, local)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settingsUi(lock.status.value, storedNow(), lastBackup.value, local.value))

    init {
        // A lock closes the dialogs and empties the field (ux.md §4: dialogs are closed after unlock).
        viewModelScope.launch {
            lock.state.collect { state ->
                if (state !is LockUiState.Unlocked) {
                    fingerprintPassword.clear()
                    local.update { it.copy(dialog = null) }
                }
            }
        }
    }

    /** The list is shown (again): a message left by "Change PIN" ([SettingsNotice]) is shown now. */
    fun screenShown() {
        SettingsNotice.take()?.let { text -> local.update { it.copy(message = text) } }
    }

    /** The wall clock now, for "Last backup: 3 days ago". */
    fun nowMs(): Long = clock.wallMs()

    // ---- dialogs ---------------------------------------------------------------------------------

    /** "Lock the app after leaving it": its dialog. */
    fun openLockDelay() = local.update { it.copy(dialog = SettingsDialog.LOCK_DELAY) }

    /** "Clear copied text after": its dialog. */
    fun openClipDelay() = local.update { it.copy(dialog = SettingsDialog.CLIP_DELAY) }

    /** A dialog was dismissed (outside tap, Back, "Cancel"). */
    fun dismissDialog() {
        if (local.value.dialog == SettingsDialog.FINGERPRINT_ON && fingerprintPassword.ui.value.checking) return
        fingerprintPassword.clear()
        local.update { it.copy(dialog = null) }
    }

    /** A choice of the lock-delay dialog: written at once; no proof (lock.md §8.2). */
    fun setLockDelay(delay: LockDelay) {
        local.update { it.copy(dialog = null) }
        write { it.lockDelay = delay }
    }

    /** A choice of the clipboard-delay dialog: written at once. */
    fun setClipDelay(delay: ClipDelay) {
        local.update { it.copy(dialog = null) }
        write { it.clipDelay = delay }
    }

    /** A choice of "Light or dark" in "Appearance": written at once; the app's look follows the stored value. */
    fun setTheme(theme: ThemeChoice) = write { it.theme = theme }

    /** A choice of "Colours" in "Appearance": written at once; the app's look follows the stored value. */
    fun setStyle(style: StyleChoice) {
        if (style in styles) write { it.style = style }
    }

    /** The "Backup reminders" switch. */
    fun setBackupReminder(on: Boolean) = write { it.backupReminder = it.backupReminder.copy(on = on) }

    // ---- fingerprint -----------------------------------------------------------------------------

    /**
     * The fingerprint switch (lock.md F9). On: the master password first, in a dialog, unless the
     * switch is not enabled now. Off: at once, no proof.
     */
    fun setFingerprint(on: Boolean) {
        val now = ui.value
        if (!now.fingerprint.enabled || now.fingerprintBusy) return
        if (on) {
            if (now.fingerprint.checked) return
            fingerprintPassword.clear()
            local.update { it.copy(dialog = SettingsDialog.FINGERPRINT_ON) }
        } else {
            work.launch {
                try {
                    lock.fingerprintOff()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    local.update { it.copy(message = UiText.of(R.string.settings_not_saved)) }
                }
            }
        }
    }

    /**
     * "Turn on" in the fingerprint dialog: a right master password closes the dialog, and the lock
     * makes the key and shows the prompt "Turn on fingerprint unlock" (lock.md F9 steps 3 to 9). What
     * it came to is said in a message; a closed prompt says nothing.
     */
    fun submitFingerprintPassword() {
        if (local.value.dialog != SettingsDialog.FINGERPRINT_ON) return
        fingerprintPassword.submit { proof ->
            if (local.value.dialog != SettingsDialog.FINGERPRINT_ON) {
                proof.drop()
                return@submit
            }
            local.update { it.copy(dialog = null, fingerprintBusy = true) }
            turnOnFingerprint(proof)
        }
    }

    // ---- the rest --------------------------------------------------------------------------------

    /** "Lock now" (ux.md §4): `Session.lockNow`. */
    fun lockNow() = session.lockNow()

    /** The message was shown. */
    fun messageShown() = local.update { it.copy(message = null) }

    override fun onCleared() {
        fingerprintPassword.clear()
    }

    /**
     * The lock cannot say whether fingerprint unlock was turned on (lock.md F9 step 9; build-plan.md
     * §12, decision 4): "Fingerprint unlock may not have been turned on. If your fingerprint is not
     * accepted, turn it on again in this app’s Settings." The switch itself follows the lock's status.
     *
     * Called for the answer of `LockManager.fingerprintOn` that says so (`FingerprintOnResult.MayNotBeOn`,
     * the branch in [turnOnFingerprint]).
     */
    internal fun fingerprintMayNotBeOn() {
        local.update { it.copy(fingerprintBusy = false, message = UiText.of(R.string.pin_fingerprint_may_not_be_on)) }
    }

    private fun turnOnFingerprint(proof: MasterProof) {
        val turnOn = work.async {
            try {
                lock.fingerprintOn(proof)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FingerprintOnResult.Failed
            } finally {
                proof.drop()
            }
        }
        viewModelScope.launch {
            val result = turnOn.await()
            val message = when (result) {
                FingerprintOnResult.On -> UiText.of(R.string.settings_fingerprint_is_on)
                FingerprintOnResult.Cancelled, FingerprintOnResult.NotShown -> null
                FingerprintOnResult.NoneEnrolled -> UiText.of(R.string.pin_fingerprint_add_first)
                FingerprintOnResult.NoSecureHardware -> UiText.of(R.string.pin_fingerprint_no_secure_hardware)
                FingerprintOnResult.Failed -> UiText.of(R.string.settings_fingerprint_failed)
                FingerprintOnResult.MayNotBeOn -> return@launch fingerprintMayNotBeOn()
            }
            local.update { it.copy(fingerprintBusy = false, message = message ?: it.message) }
        }
    }

    private fun write(change: (SettingsEditor) -> Unit) {
        work.launch(dispatchers.io) {
            val written = try {
                settings.update(change)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!written) local.update { it.copy(message = UiText.of(R.string.settings_not_saved)) }
        }
    }

    private fun placeOf(uri: String): Place = try {
        files.place(DocRef(uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Place.PhoneStorage
    }

    private fun storedNow() =
        Stored(settings.lockDelay.value, settings.clipDelay.value, settings.theme.value, settings.style.value, settings.backupReminder.value.on)

    /** What this view model keeps of its own. */
    private data class Local(val dialog: SettingsDialog? = null, val fingerprintBusy: Boolean = false, val message: UiText? = null)

    /** The stored settings the list shows. */
    private data class Stored(val lockDelay: LockDelay, val clipDelay: ClipDelay, val theme: ThemeChoice, val style: StyleChoice, val backupReminder: Boolean)

    private fun settingsUi(status: LockStatus, stored: Stored, backup: LastBackup?, local: Local) = SettingsUi(
        pin = pinRow(status.pin),
        fingerprint = fingerprintRow(status.fingerprint),
        lockDelay = stored.lockDelay,
        clipDelay = stored.clipDelay,
        theme = stored.theme,
        style = stored.style,
        styles = styles,
        backupReminder = stored.backupReminder,
        lastBackup = backup,
        oldPasswords = status.aside.map { it.putAsideAtMs },
        dialog = local.dialog,
        fingerprintBusy = local.fingerprintBusy,
        message = local.message,
    )

    companion object {
        /**
         * The PIN row (ux.md §5.18; lock.md F6, §13.3): "Change PIN" while a PIN works or its check
         * only failed for now; otherwise "Set a PIN", with why the PIN does not work. On a phone that
         * cannot keep keys in secure hardware it is off, with that reason (lock.md F18).
         */
        fun pinRow(pin: PinStatus): PinRow = when (pin) {
            PinStatus.On -> PinRow(UiText.of(R.string.settings_change_pin), null, enabled = true)
            PinStatus.CannotCheck -> PinRow(UiText.of(R.string.settings_change_pin), UiText.of(R.string.settings_pin_cannot_check), enabled = true)
            PinStatus.NotSet -> PinRow(UiText.of(R.string.settings_set_pin), null, enabled = true)
            is PinStatus.Off -> PinRow(
                UiText.of(R.string.settings_set_pin),
                UiText.of(
                    when (pin.reason) {
                        PinReason.LIMIT -> R.string.settings_pin_off_limit
                        PinReason.STOPPED_WORKING -> R.string.settings_pin_stopped_working
                        PinReason.NEWER_APP -> R.string.settings_pin_newer_app
                        PinReason.STORAGE_FULL -> R.string.settings_pin_storage_full
                        PinReason.CANNOT_CHECK -> R.string.settings_pin_cannot_check
                    },
                ),
                enabled = true,
            )
            PinStatus.NoSecureHardware -> PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_no_secure_hardware), enabled = false)
        }

        /**
         * The fingerprint switch (ux.md §5.18, lock.md §9.4). On and off with "Anyone whose
         * fingerprint is saved …"; after a master-password change that did not keep it, "Fingerprint
         * unlock: turn on again". When `canAuthenticate` says it cannot be used, the switch is off
         * and not enabled, with the reason of the table: 11 none enrolled, 12 and −2 no sensor, 1 not
         * for apps, 15 an update needed. 0 and −1 ("unknown", Android 9 and 10) leave it enabled:
         * turning it on decides. A value the table does not name gives the reason of 1, the one that
         * says fingerprint cannot be used in apps right now.
         */
        fun fingerprintRow(fingerprint: FingerprintStatus): FingerprintRow {
            val anyone = UiText.of(R.string.pin_fingerprint_anyone)
            val title = UiText.of(R.string.settings_fingerprint)
            return when (fingerprint) {
                FingerprintStatus.On -> FingerprintRow(title, checked = true, enabled = true, supporting = anyone)
                FingerprintStatus.Off -> FingerprintRow(title, checked = false, enabled = true, supporting = anyone)
                FingerprintStatus.TurnOnAgain -> FingerprintRow(UiText.of(R.string.settings_fingerprint_turn_on_again), checked = false, enabled = true, supporting = anyone)
                FingerprintStatus.NoSecureHardware ->
                    FingerprintRow(title, checked = false, enabled = false, supporting = UiText.of(R.string.pin_fingerprint_no_secure_hardware))
                is FingerprintStatus.Unavailable -> when (val reason = unavailableReason(fingerprint.canAuthenticate)) {
                    null -> FingerprintRow(title, checked = false, enabled = true, supporting = anyone)
                    else -> FingerprintRow(title, checked = false, enabled = false, supporting = UiText.of(reason))
                }
            }
        }

        /** The reason of lock.md §9.4 for [canAuthenticate]; null for 0 and −1, which leave the switch enabled. */
        fun unavailableReason(canAuthenticate: Int): Int? = when (canAuthenticate) {
            BIOMETRIC_SUCCESS, BIOMETRIC_STATUS_UNKNOWN -> null
            BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.settings_fingerprint_none_enrolled
            BIOMETRIC_ERROR_NO_HARDWARE, BIOMETRIC_ERROR_UNSUPPORTED -> R.string.settings_fingerprint_no_sensor
            BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> R.string.settings_fingerprint_update_needed
            else -> R.string.settings_fingerprint_not_for_apps // 1 (hardware unavailable) and every value the table does not name
        }

        /** "Immediately", "After 30 seconds", "After 1 minute", "After 5 minutes" (glossary terms 71 to 73). */
        fun lockDelayText(delay: LockDelay): UiText = when {
            delay.ms == 0L -> UiText.of(R.string.settings_lock_delay_immediately)
            delay.ms < MINUTE_MS -> UiText.plural(R.plurals.settings_lock_delay_seconds, (delay.ms / SECOND_MS).toInt())
            else -> UiText.plural(R.plurals.settings_lock_delay_minutes, (delay.ms / MINUTE_MS).toInt())
        }

        /** "30 seconds", "1 minute", "2 minutes", "5 minutes". */
        fun clipDelayText(delay: ClipDelay): UiText =
            if (delay.ms < MINUTE_MS) UiText.plural(R.plurals.settings_clip_delay_seconds, (delay.ms / SECOND_MS).toInt())
            else UiText.plural(R.plurals.settings_clip_delay_minutes, (delay.ms / MINUTE_MS).toInt())

        /** "System", "Light", "Dark". */
        fun themeText(theme: ThemeChoice): UiText = UiText.of(
            when (theme) {
                ThemeChoice.SYSTEM -> R.string.settings_theme_system
                ThemeChoice.LIGHT -> R.string.settings_theme_light
                ThemeChoice.DARK -> R.string.settings_theme_dark
            },
        )

        /** "Bank blue", "Emerald", "Night vault", "Phone's colours". */
        fun styleText(style: StyleChoice): UiText = UiText.of(
            when (style) {
                StyleChoice.BANK_BLUE -> R.string.settings_style_bank_blue
                StyleChoice.EMERALD -> R.string.settings_style_emerald
                StyleChoice.NIGHT_VAULT -> R.string.settings_style_night_vault
                StyleChoice.PHONE_COLOURS -> R.string.settings_style_phone
            },
        )

        /** The value of the row "Appearance": "Bank blue · System". */
        fun appearanceText(style: StyleChoice, theme: ThemeChoice): UiText =
            UiText.of(R.string.settings_appearance_value, styleText(style), themeText(theme))

        /** The view model over the app's graph. */
        fun from(graph: AppGraph): SettingsViewModel = SettingsViewModel(
            lock = graph.lock,
            settings = graph.settings,
            session = graph.session,
            repository = graph.data.repository,
            files = graph.files,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
            work = graph.appScope,
        )

        // `BiometricManager`'s answers (androidx.biometric), as lock.md §9.4 names them.
        const val BIOMETRIC_SUCCESS = 0
        const val BIOMETRIC_STATUS_UNKNOWN = -1
        const val BIOMETRIC_ERROR_UNSUPPORTED = -2
        const val BIOMETRIC_ERROR_HW_UNAVAILABLE = 1
        const val BIOMETRIC_ERROR_NONE_ENROLLED = 11
        const val BIOMETRIC_ERROR_NO_HARDWARE = 12
        const val BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15

        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000L
    }
}

/**
 * What the Settings list and the App lock page show (ux.md §5.18). Facts and [UiText]s; the dates
 * and relative times are formatted by the screen in its own language.
 *
 * @property theme light or dark: System, Light or Dark.
 * @property style the colours chosen.
 * @property styles the colours this phone offers, in the dialog's order: "Phone's colours" only from
 *   Android 12.
 * @property oldPasswords when each vault that was put aside was put aside (wall clock): one "Old
 *   passwords (locked on …)" row each (lock.md F17).
 * @property dialog the dialog that is open.
 * @property fingerprintBusy the fingerprint prompt is open, or the key is being made.
 * @property message a short message to show once ([SettingsViewModel.messageShown]).
 */
data class SettingsUi(
    val pin: PinRow,
    val fingerprint: FingerprintRow,
    val lockDelay: LockDelay,
    val clipDelay: ClipDelay,
    val theme: ThemeChoice,
    val style: StyleChoice,
    val styles: List<StyleChoice>,
    val backupReminder: Boolean,
    val lastBackup: LastBackup?,
    val oldPasswords: List<Long>,
    val dialog: SettingsDialog?,
    val fingerprintBusy: Boolean,
    val message: UiText?,
) {
    /** The lock delay's row value. */
    val lockDelayText: UiText get() = SettingsViewModel.lockDelayText(lockDelay)

    /** The clipboard delay's row value. */
    val clipDelayText: UiText get() = SettingsViewModel.clipDelayText(clipDelay)

    /** The value of the row "Appearance": the colours and light or dark, "Bank blue · System". */
    val appearanceText: UiText get() = SettingsViewModel.appearanceText(style, theme)
}

/** "Change PIN" or "Set a PIN", and why the PIN does not work now; [enabled] false when no PIN can be set on this phone. */
data class PinRow(val title: UiText, val supporting: UiText?, val enabled: Boolean)

/** The fingerprint switch: its title, whether it is on, whether it can be switched, and the line under it. */
data class FingerprintRow(val title: UiText, val checked: Boolean, val enabled: Boolean, val supporting: UiText)

/** The newest backup this app saved: when (wall clock) and where. */
data class LastBackup(val atMs: Long, val place: Place)

/** The dialogs of Settings. */
enum class SettingsDialog {
    /** "Lock the app after leaving it". */
    LOCK_DELAY,

    /** "Clear copied text after". */
    CLIP_DELAY,

    /** The master password before fingerprint unlock is turned on. */
    FINGERPRINT_ON,
}

/**
 * A message that "Change PIN" leaves for the Settings list ("Your PIN was changed.", "Your PIN is
 * set."): the route that opened it shows it when it is shown again ([SettingsViewModel.screenShown]).
 * In memory only, nothing secret; a new process has none. Main thread only.
 */
object SettingsNotice {
    private var text: UiText? = null

    /** "Change PIN" ended with [message]. */
    fun post(message: UiText) {
        text = message
    }

    /** The message left by [post], once. */
    fun take(): UiText? = text.also { text = null }
}
