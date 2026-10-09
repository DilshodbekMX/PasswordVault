package io.github.dilshodbekmx.passwordvault.ui.lock

import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.OlderPasswordChoice

/**
 * What the lock route shows (lock.md §13.2; ux.md §5.9, §5.10): one screen, as texts ([UiText]:
 * resource ids and arguments, never a finished sentence, architecture.md §5.4) and flags. Nothing
 * typed is ever in it: the pad has the number of digits only, the master-password field its own
 * `TextFieldState` in the ViewModel.
 *
 * Every screen of a vault put aside carries its line ([asideLine]) and goes back to the current
 * vault on Back ([goesBack], `LockIntent.GoBack`, lock.md F17 step 4) instead of leaving the app.
 */
sealed interface LockScreenUi {
    /** "Passwords locked on 6 Oct 2026" or "Your passwords from before 7 Oct 2026"; null on the current vault's screens. */
    val asideLine: UiText?

    /** Back returns to the current vault (`LockIntent.GoBack`) instead of leaving the app. */
    val goesBack: Boolean
}

/**
 * The PIN pad (lock.md §13.1 row 5, §13.2 "Pad, idle", "entering", "Opening"; ux.md §5.9): the
 * title "PasswordVault", "Enter your app PIN", [digits] dots filled (never which digits), the status
 * line, the keypad with the fingerprint key when [fingerprintKey], "Opening…" in place of the keypad
 * while [opening], and "Forgot PIN? Use master password" ([buttons]).
 *
 * @property status the status line of lock.md §13.3 "On the pad"; it stays while digits are typed.
 * @property wrongTries the number of wrong PINs answered on this screen so far: each new one shakes
 *   the dots and gives the reject haptic (lock.md F4 step 2).
 * @property testBuild "TEST BUILD: PIN keys are not protected", in debug and staging builds.
 */
data class PadUi(
    override val asideLine: UiText?,
    val digits: Int,
    val status: LockLine?,
    val fingerprintKey: Boolean,
    val opening: Boolean,
    val wrongTries: Int,
    val buttons: List<LockButton>,
    val testBuild: Boolean,
    override val goesBack: Boolean,
) : LockScreenUi

/**
 * Only fingerprint can be used (lock.md §13.1 row 6, §13.2 "Fingerprint only"): the title, the
 * status line (a fingerprint line with "Use your master password"), the line that says why there is
 * no pad ([pinReason]), and the buttons "Use fingerprint" and "Use master password". No pad, no dots.
 */
data class FingerprintOnlyUi(
    override val asideLine: UiText?,
    val status: LockLine?,
    val pinReason: UiText?,
    val buttons: List<LockButton>,
    val testBuild: Boolean,
    override val goesBack: Boolean,
) : LockScreenUi

/**
 * What the master-password screen shows (ux.md §5.10; lock.md §13.2, §13.3).
 *
 * @property asideLine "Passwords locked on 6 Oct 2026" on the screen of a vault put aside.
 * @property title "Type your master password", "Welcome back", or "Your passwords from before …".
 * @property reason the subtitle: why the master password is needed now; null when none applies.
 * @property fingerprintLine fingerprint unlock is on and cannot be used now, with "Use your master
 *   password", under the reason.
 * @property fieldEnabled false while the field waits (lock.md §6.8).
 * @property fieldError the field is marked wrong (the last try was a wrong master password).
 * @property pending under the field: "Your new master password starts working in about N hours."
 * @property lines after the last try, in order: "That's not the right master password. …" with its
 *   additions, or "not enough memory" with "Try again"; then, during a wait, "Wait 30 seconds …".
 * @property countdown the wait's countdown: "Try again in N seconds".
 * @property opening "Opening…" with a spinner in place of "Unlock": the master password is checked.
 * @property canSubmit "Unlock" works once something is typed: nothing is checked and nothing waits.
 * @property buttons the text buttons under "Unlock".
 * @property goesBack the screen of a vault put aside: Back returns to the current vault
 *   (`LockIntent.GoBack`, lock.md F17 step 4) instead of leaving the app.
 * @property testBuild "TEST BUILD: PIN keys are not protected", in debug and staging builds.
 */
data class MasterPasswordUi(
    override val asideLine: UiText?,
    val title: UiText,
    val reason: UiText?,
    val fingerprintLine: UiText?,
    val fieldEnabled: Boolean,
    val fieldError: Boolean,
    val pending: UiText?,
    val lines: List<LockLine>,
    val countdown: UiText?,
    val opening: Boolean,
    val canSubmit: Boolean,
    val buttons: List<LockButton>,
    override val goesBack: Boolean,
    val testBuild: Boolean = false,
) : LockScreenUi

/**
 * "This is an older master password" (lock.md F16b, `LockScreen.OlderPassword`): the typed password
 * opens only a copy from before a change. A change that was proven says so and offers "OK"; one that
 * may not have finished offers "Try again" and "Open my passwords from before the change", which
 * asks once more ([LockConfirm.OpenFromBefore]).
 */
data class OlderPasswordUi(
    override val asideLine: UiText?,
    val text: UiText,
    val buttons: List<LockButton>,
    override val goesBack: Boolean,
) : LockScreenUi

/**
 * "Open the older set instead?" (lock.md F16b step 3, `LockScreen.OpenOlderSet`): the passwords from
 * before a replacement have just opened with their own master password, on their own lock screen,
 * and the app asks before the two sets change places. Nothing has changed yet.
 *
 * @property asideLine the line of a vault put aside when [title] does not say it already.
 * @property title that screen's title, as on its master-password field: "Your passwords from before
 *   7 Oct 2026".
 * @property text the question: "These are your passwords as they were on 7 Oct. Passwords added or
 *   changed after that are in the newer set, which stays on this phone. Open the older set instead?"
 * @property buttons "Open my passwords from 7 Oct" (`OlderPasswordChoice.OPEN_OLDER_SET`) and
 *   "Cancel" (`KEEP_NEWER_SET`: nothing changes). The screen is the question, so neither asks once
 *   more.
 */
data class OlderSetUi(
    override val asideLine: UiText?,
    val title: UiText,
    val text: UiText,
    val buttons: List<LockButton>,
    override val goesBack: Boolean,
) : LockScreenUi

/**
 * The lock screen of a vault put aside whose copies cannot be opened (`LockScreen.CannotOpen`;
 * lock.md F17 step 4, §13.2, §13.3; build-plan.md §12, decision 6): its right PIN, fingerprint or
 * master password found that no copy of it opens, and that vault's screen stays.
 *
 * @property asideLine that vault's line, "Passwords locked on 6 Oct 2026" or "Your passwords from
 *   before 7 Oct 2026", always shown.
 * @property title "These passwords can't be opened".
 * @property text "Their files on this phone are damaged.", whatever storage found.
 * @property buttons "Back" only (`LockIntent.GoBack`), as the phone's Back. Never "Restore from a
 *   backup file": the current vault is fine, and a restore would put it aside.
 */
data class CannotOpenUi(
    override val asideLine: UiText,
    val title: UiText,
    val text: UiText,
    val buttons: List<LockButton>,
) : LockScreenUi {
    override val goesBack: Boolean get() = true
}

/**
 * "Your passwords can't be opened" (lock.md §13.1 row 2; ux.md §5.9 "File damaged"): no generation
 * of the current vault can be read. [body] says why; "Try again" only for an I/O error; "Restore
 * from a backup file"; and "Open my previous passwords (locked on …)" for each vault put aside.
 * Never an offer to delete. A vault put aside says so on its own screen ([CannotOpenUi]).
 */
data class UnreadableUi(
    val body: UiText,
    val buttons: List<LockButton>,
) : LockScreenUi {
    override val asideLine: UiText? get() = null
    override val goesBack: Boolean get() = false
}

/**
 * The "Forgot master password?" sheet (ux.md §5.10; lock.md F17 step 1): its options, only those
 * that apply, in the order of F17 step 1. None deletes anything, and none says "Delete" (U §5.10).
 */
data class ForgotSheetUi(val options: List<SheetOption>)

/** One option of the sheet: the sentence before its button, if any, and the button. */
data class SheetOption(val text: UiText?, val button: LockButton)

/** A question asked once more before it is done (a dialog over the screen or the sheet). */
sealed interface LockConfirm {
    /** "Start with an empty app?" (ux.md §5.10, lock.md F17 step 2). */
    data object StartEmpty : LockConfirm

    /**
     * "Open your passwords as they were before 7 Oct?" (lock.md F16b, "Open my passwords from before
     * the change", step 1); [date] is the day of the change.
     */
    data class OpenFromBefore(val date: LockDate) : LockConfirm
}

/**
 * A line of a lock screen (lock.md §13.3), with the button that belongs to it. [alert]: what went
 * wrong ("Wrong PIN. …", "That's not the right master password. …"), drawn in the error colour; the
 * lines that add a way out, the waits and the fingerprint lines are not.
 */
data class LockLine(val text: UiText, val button: LockButton? = null, val alert: Boolean = false)

/** How a button is drawn: Material 3's `Button`, `OutlinedButton` or `TextButton` (ux.md §10). */
enum class ButtonStyle { FILLED, OUTLINED, TEXT }

/** A button of a lock screen or of the sheet: its label, what it does, its test tag ([LockTags]) and how it is drawn. */
data class LockButton(val label: UiText, val action: LockAction, val tag: String, val style: ButtonStyle = ButtonStyle.TEXT)

/** What a button of the lock screens does. */
sealed interface LockAction {
    /** Tells the lock ([io.github.dilshodbekmx.passwordvault.lock.LockManager.send]): "Use PIN", "Use fingerprint" and the others. */
    data class Send(val intent: LockIntent) : LockAction

    /** "Try again" after "not enough memory": the line goes and the field takes the keyboard again. */
    data object TryAgain : LockAction

    /** "Forgot master password?" and "I forgot it": the sheet opens, and the lock is told (`LockIntent.OpenForgotSheet`). */
    data object OpenSheet : LockAction

    /** The sheet's "Try again", or the sheet dismissed: it closes, and the lock is told (`LockIntent.CloseSheet`). */
    data object CloseSheet : LockAction

    /**
     * "Restore from a backup file", on the sheet or on "Your passwords can't be opened": the lock is
     * told (`LockIntent.RestoreFromFile`) and the route opens the restore screens (`Route.Restore`).
     */
    data object Restore : LockAction

    /** "Start with an empty app…" on the sheet: [LockConfirm.StartEmpty] asks first. */
    data object AskStartEmpty : LockAction

    /** "Open my passwords from before the change" on "This is an older master password": [LockConfirm.OpenFromBefore] asks first. */
    data object AskOpenFromBefore : LockAction

    /** The confirming button of the question shown ([LockConfirm]): `LockIntent.StartEmpty` or `OlderPasswordAnswer(OPEN_FROM_BEFORE)`. */
    data object Confirm : LockAction

    /** "Cancel", or the question dismissed: nothing is done. */
    data object CancelConfirm : LockAction
}

/** The answer of [LockAction.Send] for an answer on "This is an older master password" or on "Open the older set instead?". */
internal fun olderAnswer(choice: OlderPasswordChoice): LockAction = LockAction.Send(LockIntent.OlderPasswordAnswer(choice))

/**
 * A date or a time in a lock text: the ViewModel keeps the time, and the screen formats it in its
 * own language (`Formatters`, i18n.md §4.6), so that a language switch changes it too.
 */
data class LockDate(val ms: Long, val style: Style) {
    enum class Style {
        /** A day and a month: "Oct 5", "5-okt", "5 окт." (in "An older copy from 5 Oct …"). */
        DAY_MONTH,

        /** A day, a month and the year: "Oct 7, 2026" (in "Your passwords from before 7 Oct 2026"). */
        DATE,

        /** A date and a time of day: "Oct 5, 2026, 18:42" (in the notices after unlock, lock.md §13.4). */
        DATE_TIME,

        /** A time of day: "14:32" (in "last time today at 14:32"). */
        TIME,
    }
}

/**
 * The test tags of the lock screens, exposed as resource ids under the route's root (and under the
 * sheet's and the dialogs' own roots), so that tests and the emulator scripts find each part
 * whatever the language. The pad's keys and dots are `PinPadTags`.
 */
object LockTags {
    const val ASIDE = "pv.lock.aside"
    const val TITLE = "pv.lock.title"
    const val REASON = "pv.lock.reason"
    const val FINGERPRINT = "pv.lock.fingerprint"
    const val FIELD = "pv.lock.password"
    const val PENDING = "pv.lock.pending"
    const val COUNTDOWN = "pv.lock.countdown"
    const val UNLOCK = "pv.lock.unlock"
    const val OPENING = "pv.lock.opening"
    const val USE_PIN = "pv.lock.usePin"
    const val NOT_NOW = "pv.lock.notNow"
    const val CANT_REMEMBER = "pv.lock.cantRemember"
    const val FORGOT = "pv.lock.forgot"
    const val I_FORGOT = "pv.lock.iForgot"
    const val FROM_BEFORE = "pv.lock.fromBefore"
    const val TRY_AGAIN = "pv.lock.tryAgain"

    /** The app's logo on the pad and on the fingerprint-only screen. */
    const val LOGO = "pv.lock.logo"

    /** The pad's screen, its subtitle "Enter your app PIN", its status line and "Forgot PIN? Use master password". */
    const val PAD = "pv.lock.pad"
    const val SUBTITLE = "pv.lock.subtitle"
    const val STATUS = "pv.lock.status"
    const val FORGOT_PIN = "pv.lock.forgotPin"

    /** The fingerprint-only screen, its line about the PIN and its two buttons. */
    const val FINGERPRINT_ONLY = "pv.lock.fingerprintOnly"
    const val PIN_REASON = "pv.lock.pinReason"
    const val USE_FINGERPRINT = "pv.lock.useFingerprint"
    const val USE_MASTER_PASSWORD = "pv.lock.useMasterPassword"

    /** "TEST BUILD: PIN keys are not protected". */
    const val TEST_BUILD = "pv.lock.testBuild"

    /** "This is an older master password": the screen, its text and its buttons. */
    const val OLDER = "pv.lock.older"
    const val OLDER_TEXT = "pv.lock.olderText"
    const val OLDER_OK = "pv.lock.olderOk"
    const val OLDER_TRY_AGAIN = "pv.lock.olderTryAgain"
    const val OLDER_FROM_BEFORE = "pv.lock.olderFromBefore"

    /** "Open the older set instead?" (lock.md F16b step 3): the screen, its question, "Open my passwords from 7 Oct" and "Cancel". */
    const val OLDER_SET = "pv.lock.olderSet"
    const val OLDER_SET_TEXT = "pv.lock.olderSetText"
    const val OLDER_SET_OPEN = "pv.lock.olderSetOpen"
    const val OLDER_SET_CANCEL = "pv.lock.olderSetCancel"

    /** The screen of a vault put aside that cannot be opened (build-plan §12, decision 6): the screen, its text and "Back". */
    const val CANNOT_OPEN = "pv.lock.cannotOpen"
    const val CANNOT_OPEN_TEXT = "pv.lock.cannotOpenText"
    const val BACK = "pv.lock.back"

    /** "Your passwords can't be opened": the screen, its text, "Try again" and "Restore from a backup file". */
    const val UNREADABLE = "pv.lock.unreadable"
    const val UNREADABLE_TEXT = "pv.lock.unreadableText"
    const val UNREADABLE_TRY_AGAIN = "pv.lock.unreadableTryAgain"
    const val RESTORE = "pv.lock.restore"

    /** The "Forgot master password?" sheet and its options. */
    const val SHEET = "pv.lock.sheet"
    const val SHEET_USE_PIN = "pv.lock.sheet.usePin"
    const val SHEET_USE_FINGERPRINT = "pv.lock.sheet.useFingerprint"
    const val SHEET_RESTORE = "pv.lock.sheet.restore"
    const val SHEET_EMPTY = "pv.lock.sheet.empty"
    const val SHEET_TRY_AGAIN = "pv.lock.sheet.tryAgain"

    /** The question asked once more ([LockConfirm]), its confirming button and "Cancel". */
    const val CONFIRM = "pv.lock.confirm"
    const val CONFIRM_YES = "pv.lock.confirmYes"
    const val CONFIRM_CANCEL = "pv.lock.confirmCancel"

    /** The line [index] after a try (`MasterPasswordUi.lines`), from 0. */
    fun line(index: Int): String = "pv.lock.line.$index"

    /** "Open my previous passwords (locked on …)" for the vault [vaultId] on "Your passwords can't be opened". */
    fun openAside(vaultId: String): String = "pv.lock.openAside.$vaultId"

    /** The same entry on the sheet. */
    fun sheetAside(vaultId: String): String = "pv.lock.sheet.aside.$vaultId"
}
