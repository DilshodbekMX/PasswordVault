package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.setup.BackupsExplainedScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.ConfirmPinScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.CreatePinScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordPage
import io.github.dilshodbekmx.passwordvault.ui.setup.PerformMoves
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStage
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupViewModel

/**
 * The PIN steps as their routes show them (`PinSetupNav.kt`), in one place for the device tests: the
 * step the view model is at, and its moves performed on [nav] (a [StepNavigator] records them).
 */
@Composable
fun PinStepsHost(vm: PinSetupViewModel, nav: Navigator) {
    LaunchedEffect(vm) { vm.start() }
    for (route in STEPS) {
        key(route) { PerformMoves(vm, route, nav) }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val remind by vm.backupReminder.collectAsStateWithLifecycle()
    when (ui.stage) {
        PinSetupStage.MASTER -> MasterPasswordPage(ask = vm.masterPassword, onNext = vm::submitMasterPassword, onNotNow = vm::notNow)
        PinSetupStage.START, PinSetupStage.CHOOSE -> CreatePinScreen(
            entered = ui.entered,
            line = ui.line,
            shakes = ui.shakes,
            onDigit = { vm.digit(it, Route.CreatePin) },
            onDelete = { vm.delete(Route.CreatePin) },
            onClear = { vm.clearEntry(Route.CreatePin) },
            onNotNow = vm::notNow,
            newPin = ui.newPin,
            someoneMayKnow = ui.someoneMayKnow,
        )
        PinSetupStage.CONFIRM -> ConfirmPinScreen(
            entered = ui.entered,
            line = ui.line,
            shakes = ui.shakes,
            saving = ui.saving,
            saved = ui.saved,
            saveFailed = ui.saveFailed,
            onDigit = { vm.digit(it, Route.ConfirmPin) },
            onDelete = { vm.delete(Route.ConfirmPin) },
            onClear = { vm.clearEntry(Route.ConfirmPin) },
            onRetry = vm::retrySave,
            onNotNow = vm::skipAfterFailure,
        )
        PinSetupStage.FINGERPRINT -> FingerprintOfferScreen(busy = ui.fingerprintBusy, line = ui.fingerprintLine, onUse = vm::useFingerprint, onNotNow = vm::fingerprintNotNow)
        PinSetupStage.BACKUPS, PinSetupStage.DONE ->
            BackupsExplainedScreen(remind = remind, line = ui.backupLine, onRemindChange = vm::setBackupReminder, onFinish = vm::finish)
    }
}

private val STEPS = listOf(Route.CreatePin, Route.ConfirmPin, Route.FingerprintOffer, Route.BackupsExplained)
