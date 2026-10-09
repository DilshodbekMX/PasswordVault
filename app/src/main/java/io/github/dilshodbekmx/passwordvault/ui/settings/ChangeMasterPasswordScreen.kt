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
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.PIN_LENGTH
import io.github.dilshodbekmx.passwordvault.ui.setup.BodyText
import io.github.dilshodbekmx.passwordvault.ui.setup.BusyCover
import io.github.dilshodbekmx.passwordvault.ui.setup.CreateMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordAsk
import io.github.dilshodbekmx.passwordvault.ui.setup.PinLine
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import io.github.dilshodbekmx.passwordvault.ui.setup.PinStepPage
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupState
import io.github.dilshodbekmx.passwordvault.ui.setup.TypeAgainScreen
import io.github.dilshodbekmx.passwordvault.ui.setup.WriteDownScreen

/**
 * Settings › App lock › "Change master password" ([Route.ChangeMasterPassword]; ux.md §5.18; lock.md
 * F8): the steps of [ChangeMasterPasswordViewModel] under one top bar. Choosing, writing down and
 * typing again are setup's own screens; the last two keep the screen on (lock.md §10.1). It moves
 * with [nav] as the ViewModel asks: back when the flow ends, to `Route.NewMasterPassword` over it
 * after "I don't remember it" (Back from there comes back to step 1, emptied), to `Route.Backup` in
 * its place after "Back up now", and to 4.3's
 * PIN steps over it when "Choose a PIN" follows the change (the reason left for them first,
 * `PinSetupStart`; they come back here when they end).
 */
@Composable
fun ChangeMasterPasswordScreen(vm: ChangeMasterPasswordViewModel, nav: Navigator) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.leave) {
        val leave = ui.leave ?: return@LaunchedEffect
        vm.left()
        when (leave) {
            Leave.BACK -> nav.back()
            Leave.NEW_MASTER_PASSWORD -> nav.go(Route.NewMasterPassword)
            Leave.BACKUP -> nav.replace(Route.Backup)
        }
    }
    LaunchedEffect(ui.choosePin) {
        if (!ui.choosePin) return@LaunchedEffect
        PinSetupStart.offer(ChoosePinReason.MASTER_CHANGED)
        if (!nav.go(Route.CreatePin)) PinSetupStart.take() // not opened: nothing is left for a later opening
        vm.pinStepsOpened()
    }
    val saving = ui.stage == ChangeStage.SAVING
    BackHandler { vm.back() }
    MasterFrame(title = stringResource(R.string.master_change_title), onBack = vm::back, backEnabled = !saving) {
        ChangeStep(vm, ui)
    }
}

@Composable
private fun ChangeStep(vm: ChangeMasterPasswordViewModel, ui: ChangeMasterPasswordUi) {
    when (ui.stage) {
        ChangeStage.CURRENT -> CurrentStep(vm.current, onNext = vm::submitCurrent, onDontRemember = vm::dontRemember)
        ChangeStage.CHOOSE, ChangeStage.WRITE_DOWN, ChangeStage.TYPE_AGAIN -> {
            val setup by vm.choice.state.collectAsStateWithLifecycle()
            ChoiceSteps(
                step = when (ui.stage) {
                    ChangeStage.CHOOSE -> ChoiceStep.CHOOSE
                    ChangeStage.WRITE_DOWN -> ChoiceStep.WRITE_DOWN
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
        ChangeStage.PIN, ChangeStage.SAVING -> {
            val saving = ui.stage == ChangeStage.SAVING
            val savingText = stringResource(R.string.master_saving)
            PinStepPage(
                title = stringResource(R.string.master_keep_pin_title),
                entered = if (saving) PIN_LENGTH else ui.entered,
                onDigit = vm::digit,
                onDelete = vm::deleteDigit,
                onClear = vm::clearDigits,
                line = ui.line?.let { PinLine(it) },
                shakes = ui.shakes,
                cover = if (saving) ({ BusyCover(savingText) }) else null,
                body = {
                    if (saving) {
                        if (ui.fingerprintOn) BodyText(stringResource(R.string.master_touch_sensor), Modifier.testTag(MasterTags.TOUCH_SENSOR))
                    } else {
                        BodyText(stringResource(R.string.master_keep_pin_text))
                    }
                },
                buttons = {
                    if (!saving) {
                        TextButton(onClick = vm::notNow, modifier = Modifier.fillMaxWidth().testTag(MasterTags.PIN_NOT_NOW)) {
                            Text(stringResource(R.string.common_not_now))
                        }
                    }
                },
            )
        }
        ChangeStage.NO_MEMORY -> MasterPage(heading = stringResource(R.string.master_change_title)) {
            MasterLine(UiText.of(R.string.master_change_no_memory))
            Button(onClick = vm::retry, modifier = Modifier.fillMaxWidth().testTag(MasterTags.RETRY)) {
                Text(stringResource(R.string.common_try_again))
            }
            TextButton(onClick = vm::close, modifier = Modifier.fillMaxWidth().testTag(MasterTags.LATER)) {
                Text(stringResource(R.string.common_later))
            }
        }
        ChangeStage.NOT_CHANGED -> Outcome(UiText.of(R.string.master_not_changed), vm::close)
        ChangeStage.UNCLEAR -> Outcome(UiText.of(R.string.master_unclear), vm::close)
        ChangeStage.BACKUP -> NewBackupStep(
            heading = stringResource(R.string.master_changed),
            places = ui.places,
            onBackUpNow = vm::backUpNow,
            onLater = vm::close,
            onDelete = vm::deletePlace,
        )
    }
}

/**
 * Step 1 (lock.md F8 step 1): "Type your current master password", the field with its lines (a wrong
 * one, the wait, too little memory: [MasterPasswordAsk.ui]), "Next" and "I don't remember it".
 */
@Composable
private fun CurrentStep(ask: MasterPasswordAsk, onNext: () -> Unit, onDontRemember: () -> Unit) {
    val ui by ask.ui.collectAsStateWithLifecycle()
    MasterPage(heading = stringResource(R.string.master_current_heading)) {
        MasterText(stringResource(R.string.master_current_text))
        MasterField(
            state = ask.field,
            label = stringResource(R.string.master_current_label),
            tag = MasterTags.CURRENT_FIELD,
            onDone = onNext,
            enabled = ui.enabled,
            isError = ui.line?.alert == true,
        )
        if (ui.checking) MasterBusy(stringResource(R.string.master_checking), MasterTags.CHECKING)
        ui.line?.let { MasterLine(it.text, alert = it.alert) }
        ui.secondsLeft?.let { MasterCountdown(it) }
        Button(onClick = onNext, enabled = ui.enabled, modifier = Modifier.fillMaxWidth().testTag(MasterTags.CURRENT_NEXT)) {
            Text(stringResource(R.string.master_next))
        }
        TextButton(onClick = onDontRemember, enabled = !ui.checking, modifier = Modifier.fillMaxWidth().testTag(MasterTags.DONT_REMEMBER)) {
            Text(stringResource(R.string.master_dont_remember))
        }
    }
}

/**
 * Choosing the new master password with setup's three screens (ux.md §5.3 to §5.5): "Write it down
 * on paper" and "Type your master password again" keep the screen on (lock.md §10.1). Shared by both
 * routes.
 */
@Composable
internal fun ChoiceSteps(
    step: ChoiceStep,
    setup: SetupState,
    choice: NewMasterPasswordChoice,
    onChosen: () -> Unit,
    onWroteItDown: () -> Unit,
    onAgain: () -> Unit,
    onShowAgain: () -> Unit,
) {
    LaunchedEffect(choice) { choice.showWords() }
    val chosen = setup.chosen
    when {
        step == ChoiceStep.CHOOSE || chosen == null -> CreateMasterPasswordScreen(
            state = setup,
            own = choice.own,
            onUseWords = { if (choice.useTheseWords()) onChosen() },
            onOtherWords = choice::showOtherWords,
            onTypeOwn = choice::typeOwn,
            onShowWords = choice::showSuggested,
            onUseOwn = choice::submitOwn,
            onReuseAnswer = { usedElsewhere -> if (choice.answerReuse(usedElsewhere)) onChosen() },
            onReuseDismiss = choice::dismissReuse,
        )
        step == ChoiceStep.WRITE_DOWN -> WriteDownScreen(
            chosen = chosen,
            wroteItDown = setup.wroteItDown,
            onWroteItDown = choice::setWroteItDown,
            onNext = onWroteItDown,
            keepScreenOn = true,
        )
        else -> TypeAgainScreen(state = setup, field = choice.again, onNext = onAgain, onShowAgain = onShowAgain, keepScreenOn = true)
    }
}

/** The three steps of choosing a new master password (ux.md §5.3 to §5.5). */
internal enum class ChoiceStep { CHOOSE, WRITE_DOWN, TYPE_AGAIN }

/** The end of a change that did not go through: what the lock said, and "OK". */
@Composable
private fun Outcome(text: UiText, onOk: () -> Unit) {
    MasterPage(heading = stringResource(R.string.master_change_title)) {
        MasterLine(text)
        Button(onClick = onOk, modifier = Modifier.fillMaxWidth().testTag(MasterTags.OK)) {
            Text(stringResource(R.string.common_ok))
        }
    }
}
