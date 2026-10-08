package io.github.dilshodbekmx.passwordvault.ui.lock

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * What the lock screens' JVM tests share: the lock's screens with defaults, and a [LockViewModel]
 * as the route makes it, with collectors of its flows as the screen has. Build plan 4.2, [JVM].
 */
internal object LockKit {
    const val START = 5_000_000L
    const val ASIDE_AT = 1_791_244_800_000L
    const val REPLACED_AT = 1_791_331_200_000L
    const val CHANGED_AT = 1_791_158_400_000L
    const val VAULT_A = "00000000-0000-4000-8000-0000000000aa"
    const val VAULT_B = "00000000-0000-4000-8000-0000000000bb"

    /** Every [PadStatus] the lock gives, the fingerprint lines included. */
    val PAD_STATUSES: List<PadStatus> = listOf(
        PadStatus.TriesLeft(2),
        PadStatus.WrongPin(4),
        PadStatus.LastTry,
        PadStatus.LastTryWhilePending,
        PadStatus.SameAgain,
        PadStatus.Interrupted(4),
        PadStatus.StorageFull,
    ) + FINGERPRINT_LINES

    val FINGERPRINT_LINES: List<FingerprintLine>
        get() = listOf(
            FingerprintLine.TurnedOff,
            FingerprintLine.StoppedWorking,
            FingerprintLine.Locked,
            FingerprintLine.DidNotOpen,
            FingerprintLine.NotForApps,
            FingerprintLine.NoneSaved,
            FingerprintLine.UpdateNeeded,
            FingerprintLine.CannotCheck,
        )

    /** Every case of [PadStatus] is in [PAD_STATUSES]: a new case does not compile here. */
    fun covered(status: PadStatus): Unit = when (status) {
        is PadStatus.TriesLeft, is PadStatus.WrongPin, PadStatus.LastTry, PadStatus.LastTryWhilePending, PadStatus.SameAgain,
        is PadStatus.Interrupted, PadStatus.StorageFull, FingerprintLine.TurnedOff, FingerprintLine.StoppedWorking, FingerprintLine.Locked,
        FingerprintLine.DidNotOpen, FingerprintLine.NotForApps, FingerprintLine.NoneSaved, FingerprintLine.UpdateNeeded,
        FingerprintLine.CannotCheck,
        -> Unit
    }

    fun pad(
        digits: Int = 0,
        status: PadStatus? = null,
        fingerprintKey: Boolean = false,
        opening: Boolean = false,
        aside: AsideLine? = null,
    ) = LockScreen.Pad(digits, status, fingerprintKey, opening, aside)

    fun fingerprintOnly(status: PadStatus? = null, pinReason: PinReason? = null, aside: AsideLine? = null) =
        LockScreen.FingerprintOnly(status, pinReason, aside)

    fun masterPassword(
        reason: MpReason? = null,
        canUsePin: Boolean = false,
        practice: Practice? = null,
        error: MpError? = null,
        aside: AsideLine? = null,
        fingerprintLine: FingerprintLine? = null,
        waitUntilElapsedMs: Long? = null,
    ) = LockScreen.MasterPassword(reason, canUsePin, practice, waitUntilElapsedMs, error, null, false, aside, fingerprintLine)

    fun aside(id: String, at: Long) = AsideVault(id, at, null)

    fun text(id: Int, vararg args: Any): UiText = UiText.of(id, *args)

    fun send(intent: LockIntent): LockAction = LockAction.Send(intent)

    /** The route's ViewModel over [lock] in [store], with collectors of its screen, sheet and question as the route has. */
    fun TestScope.lockViewModel(store: ViewModelStore, lock: LockManager, clock: FakeClock, testBuild: Boolean = false): LockViewModel {
        val factory = viewModelFactory { initializer { LockViewModel(lock, clock, backgroundScope, testBuild) } }
        val vm = ViewModelProvider(store, factory)[LockViewModel::class.java]
        backgroundScope.launch { vm.screen.collect {} }
        backgroundScope.launch { vm.masterPassword.collect {} }
        backgroundScope.launch { vm.sheet.collect {} }
        backgroundScope.launch { vm.confirm.collect {} }
        runCurrent()
        return vm
    }
}
