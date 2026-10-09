package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.setup.BodyText
import io.github.dilshodbekmx.passwordvault.ui.setup.BusyCover
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
import io.github.dilshodbekmx.passwordvault.ui.setup.PinStepPage

/**
 * `Route.NewMasterPassword` (ux.md §5.24; lock.md F11): the steps of [NewMasterPasswordViewModel]
 * under one top bar, "New master password". Choosing, writing down and typing again are setup's
 * own screens, the last two with the screen kept on (lock.md §10.1). It moves with [nav] as the
 * ViewModel asks: back when the flow ends, and to `Route.Backup` in its place after "Back up now".
 */
@Composable
fun NewMasterPasswordScreen(vm: NewMasterPasswordViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.leave) {
        val leave = ui.leave ?: return@LaunchedEffect
        vm.left()
        when (leave) {
            Leave.BACKUP -> nav.replace(Route.Backup)
            Leave.BACK, Leave.NEW_MASTER_PASSWORD -> nav.back()
        }
    }
    val busy = ui.proving || ui.finishing || ui.stage == NewStage.REQUESTING
    BackHandler { vm.back() }
    MasterFrame(title = stringResource(R.string.master_new_title), onBack = vm::back, backEnabled = !busy) {
        NewStep(vm, ui)
    }
}

@Composable
private fun NewStep(vm: NewMasterPasswordViewModel, ui: NewMasterPasswordUi) {
    when (ui.stage) {
        NewStage.EXPLAIN -> Explanation(ui, onContinue = vm::proveWithoutPin, onCancel = vm::close)
        NewStage.PIN -> ProofPad(vm, ui)
        NewStage.PIN_GONE -> MasterPage(heading = stringResource(R.string.master_new_title)) {
            MasterText(stringResource(R.string.master_proof_pin_gone))
            ui.line?.let { MasterLine(it) }
            if (ui.proving) MasterBusy(stringResource(R.string.master_proof_fingerprint), MasterTags.FINGERPRINT_PROMPT)
            Button(onClick = vm::proveWithoutPin, enabled = !ui.proving, modifier = Modifier.fillMaxWidth().testTag(MasterTags.USE_FINGERPRINT)) {
                Text(stringResource(R.string.master_use_fingerprint))
            }
            TextButton(onClick = vm::close, enabled = !ui.proving, modifier = Modifier.fillMaxWidth().testTag(MasterTags.CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
        NewStage.CHOOSE, NewStage.WRITE_DOWN, NewStage.TYPE_AGAIN -> {
            val setup by vm.choice.state.collectAsStateWithLifecycle()
            ChoiceSteps(
                step = when (ui.stage) {
                    NewStage.CHOOSE -> ChoiceStep.CHOOSE
                    NewStage.WRITE_DOWN -> ChoiceStep.WRITE_DOWN
                    else -> ChoiceStep.TYPE_AGAIN
                },
                setup = setup,
                choice = vm.choice,
                onChosen = vm::chosen,
                onWroteItDown = vm::wroteItDown,
                onAgain = vm::submitAgain,
                onShowAgain = vm::showAgain,
            )
        }
        NewStage.REQUESTING -> MasterPage(heading = stringResource(R.string.master_new_title)) {
            MasterBusy(stringResource(R.string.master_requesting), MasterTags.REQUESTING)
        }
        NewStage.REQUEST_FAILED -> MasterPage(heading = stringResource(R.string.master_new_title)) {
            ui.line?.let { MasterLine(it) }
            Button(onClick = vm::retryRequest, modifier = Modifier.fillMaxWidth().testTag(MasterTags.RETRY)) {
                Text(stringResource(R.string.common_try_again))
            }
            TextButton(onClick = vm::close, modifier = Modifier.fillMaxWidth().testTag(MasterTags.LATER)) {
                Text(stringResource(R.string.common_later))
            }
        }
        NewStage.WAIT -> MasterPage(heading = stringResource(R.string.master_wait_heading)) {
            MasterText(stringResource(R.string.master_wait_text), Modifier.testTag(MasterTags.WAIT))
            Button(onClick = vm::close, modifier = Modifier.fillMaxWidth().testTag(MasterTags.DONE)) {
                Text(stringResource(R.string.common_done))
            }
        }
        NewStage.STOPPED -> MasterPage(heading = stringResource(R.string.master_new_title)) {
            ui.line?.let { MasterLine(it) }
            Button(onClick = vm::close, modifier = Modifier.fillMaxWidth().testTag(MasterTags.OK)) {
                Text(stringResource(R.string.common_ok))
            }
        }
        NewStage.FINISH -> FinishStep(vm, ui)
        NewStage.FINISHED -> NewBackupStep(
            heading = stringResource(R.string.master_finished),
            places = emptyList(),
            onBackUpNow = vm::backUpNow,
            onLater = vm::close,
            onDelete = {},
        )
    }
}

/**
 * The explanation (lock.md F11 step 2): "PIN" reads "fingerprint" when no PIN exists; while another
 * new master password waits, that a new one starts its 24 hours again. "Continue" asks for the proof
 * of step 3; while the fingerprint prompt is open it says to touch the sensor.
 */
@Composable
private fun Explanation(ui: NewMasterPasswordUi, onContinue: () -> Unit, onCancel: () -> Unit) {
    MasterPage(heading = stringResource(R.string.master_new_title)) {
        MasterText(
            stringResource(if (ui.by == Shortcut.PIN) R.string.master_explain_pin else R.string.master_explain_fingerprint),
            Modifier.testTag(MasterTags.EXPLAIN),
        )
        if (ui.replacesPending) MasterText(stringResource(R.string.master_explain_replaces), Modifier.testTag(MasterTags.REPLACES))
        ui.line?.let { MasterLine(it) }
        if (ui.proving) {
            val text = if (ui.fingerprintPrompt) R.string.master_proof_fingerprint else R.string.master_checking
            MasterBusy(stringResource(text), if (ui.fingerprintPrompt) MasterTags.FINGERPRINT_PROMPT else MasterTags.CHECKING)
        }
        Button(onClick = onContinue, enabled = !ui.proving, modifier = Modifier.fillMaxWidth().testTag(MasterTags.CONTINUE)) {
            Text(stringResource(R.string.master_continue))
        }
        TextButton(onClick = onCancel, enabled = !ui.proving, modifier = Modifier.fillMaxWidth().testTag(MasterTags.CANCEL)) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

/**
 * "Proof now" with the PIN (lock.md F11 step 3): "Enter your app PIN", why it is asked although the
 * app is open, and, while fingerprint unlock is on, "Forgot your PIN as well? …". The sixth digit
 * sends the PIN, a counted check; "Checking…" stands in place of the keypad meanwhile.
 */
@Composable
private fun ProofPad(vm: NewMasterPasswordViewModel, ui: NewMasterPasswordUi) {
    val checking = stringResource(R.string.master_checking)
    val fingerprint = stringResource(R.string.master_proof_fingerprint)
    PinStepPage(
        title = stringResource(R.string.master_proof_pin_title),
        entered = ui.entered,
        onDigit = vm::digit,
        onDelete = vm::deleteDigit,
        onClear = vm::clearDigits,
        line = ui.line?.let { PinLine(it, alert = it != UiText.of(R.string.master_proof_again)) },
        shakes = ui.shakes,
        cover = if (ui.proving) ({ BusyCover(if (ui.fingerprintPrompt) fingerprint else checking) }) else null,
        body = {
            BodyText(stringResource(R.string.master_proof_pin_text))
            if (ui.fingerprintOn) BodyText(stringResource(R.string.master_proof_pin_forgot), Modifier.testTag(MasterTags.FORGOT_PIN_TOO))
        },
        buttons = {
            TextButton(onClick = vm::close, enabled = !ui.proving, modifier = Modifier.fillMaxWidth().testTag(MasterTags.CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * "Finish now" (lock.md F11 step 13; build-plan.md §12, decision 14): the new master password typed
 * once in the open app, with the lines of the lock's guard (a wrong password, the wait and its
 * countdown, too little memory, "Couldn't finish now. …"). No PIN and no fingerprint is asked for.
 */
@Composable
private fun FinishStep(vm: NewMasterPasswordViewModel, ui: NewMasterPasswordUi) {
    val enabled = !ui.finishing && ui.waitSeconds == null
    MasterPage(heading = stringResource(R.string.master_finish_heading)) {
        MasterText(stringResource(R.string.master_finish_text), Modifier.testTag(MasterTags.TEXT))
        MasterField(
            state = vm.finishField,
            label = stringResource(R.string.master_finish_label),
            tag = MasterTags.FINISH_FIELD,
            onDone = vm::submitFinish,
            enabled = enabled,
            isError = ui.line != null && ui.waitSeconds == null,
        )
        if (ui.finishing) MasterBusy(stringResource(R.string.master_finishing), MasterTags.FINISHING)
        ui.line?.let { MasterLine(it, alert = ui.waitSeconds == null) }
        ui.waitSeconds?.let { MasterCountdown(it) }
        Button(onClick = vm::submitFinish, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(MasterTags.FINISH)) {
            Text(stringResource(R.string.master_finish))
        }
        TextButton(onClick = vm::close, enabled = !ui.finishing, modifier = Modifier.fillMaxWidth().testTag(MasterTags.LATER)) {
            Text(stringResource(R.string.common_later))
        }
    }
}
