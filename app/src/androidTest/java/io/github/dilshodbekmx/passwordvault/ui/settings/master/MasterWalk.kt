package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.ChangeStage
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.NewMasterPasswordViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.NewStage
import io.github.dilshodbekmx.passwordvault.ui.settings.PlaceState
import io.github.dilshodbekmx.passwordvault.ui.settings.StepNavigator
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A [LockManager] that is [fake], but whose calls of F8 and F11 answer only once their gate is
 * opened ([openChange], [openProof], [openRequest], [openFinish]): until then the screens stay on
 * "Saving…", "Checking…" or "Finishing…", so that a walk can look at that moment.
 */
class HeldLock(val fake: FakeLockManager) : LockManager by fake {
    @Volatile var holdChange = false
    @Volatile var holdProof = false
    @Volatile var holdRequest = false
    @Volatile var holdFinish = false
    private var change = CompletableDeferred<Unit>()
    private var proof = CompletableDeferred<Unit>()
    private var request = CompletableDeferred<Unit>()
    private var finish = CompletableDeferred<Unit>()

    fun openChange() = change.complete(Unit).also { change = CompletableDeferred() }
    fun openProof() = proof.complete(Unit).also { proof = CompletableDeferred() }
    fun openRequest() = request.complete(Unit).also { request = CompletableDeferred() }
    fun openFinish() = finish.complete(Unit).also { finish = CompletableDeferred() }

    override suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult {
        if (holdChange) change.await()
        return fake.changeMasterPassword(proof, first, again, pin)
    }

    override suspend fun proveForNewMasterPassword(pin: ByteArray?): ProofResult {
        if (holdProof) this.proof.await()
        return fake.proveForNewMasterPassword(pin)
    }

    override suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult {
        if (holdRequest) request.await()
        return fake.requestNewMasterPassword(first, again)
    }

    override suspend fun finishNewMasterPassword(chars: CharArray): FinishResult {
        if (holdFinish) finish.await()
        return fake.finishNewMasterPassword(chars)
    }
}

/**
 * The steps of both routes, one after another, over a [HeldLock], for the pseudo-locale walk and the
 * pictures: [content] draws the route shown now; each step function moves the ViewModels on and
 * waits until the step is on the screen; [at] runs the caller's look ([look]) at each step, named.
 */
class MasterWalk(private val compose: ComposeTestRule, private val look: (String) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val fake = FakeLockManager(LockUiState.Unlocked(emptyList(), null))
    val lock = HeldLock(fake)
    private val nav = StepNavigator()
    private var shown by mutableStateOf<Shown>(Shown.Nothing)

    private sealed interface Shown {
        data object Nothing : Shown

        class Change(val vm: ChangeMasterPasswordViewModel) : Shown

        class New(val vm: NewMasterPasswordViewModel) : Shown
    }

    /** What the walk shows now. */
    @Composable
    fun Content() {
        when (val now = shown) {
            Shown.Nothing -> Unit
            is Shown.Change -> ChangeMasterPasswordScreen(now.vm, nav)
            is Shown.New -> NewMasterPasswordScreen(now.vm, nav)
        }
    }

    private fun change(vault: Vault = MasterRig.USED_ELSEWHERE): ChangeMasterPasswordViewModel =
        ChangeMasterPasswordViewModel(
            lock = lock,
            repository = FakeVaultRepository(vault),
            clock = AndroidClock,
            random = FakeRandom(seed = 31L),
            dispatchers = AppDispatchers.Default,
            work = scope,
            offerBackup = {},
            deleteBackup = { ref -> if (ref == MasterRig.BACKUPS[1]) NoteDeleted.NOT_DELETABLE else NoteDeleted.DELETED },
        )

    private fun newOne(): NewMasterPasswordViewModel =
        NewMasterPasswordViewModel(lock, AndroidClock, FakeRandom(seed = 32L), AppDispatchers.Default, scope, offerBackup = {})

    private fun onMain(action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
    }

    private fun at(name: String, until: () -> Boolean) {
        compose.waitUntil(WAIT_MS, until)
        compose.waitForIdle()
        look(name)
    }

    /** "Change master password" from step 1 to the new backup, with the lines on the way. */
    fun walkChange(pictures: Boolean) {
        onMain {
            fake.status.value = fake.status.value.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.On)
            fake.mpResults += MpResult.Wrong
        }
        val vm = change()
        onMain { shown = Shown.Change(vm) }
        at("01-current") { vm.ui.value.stage == ChangeStage.CURRENT }
        onMain {
            vm.current.field.edit { append("not it") }
            vm.submitCurrent()
        }
        at("02-current-wrong") { vm.current.ui.value.line != null }
        onMain {
            vm.current.field.edit { append("correct horse battery staple") }
            vm.submitCurrent()
        }
        at("03-choose") { vm.ui.value.stage == ChangeStage.CHOOSE && vm.choice.state.value.passphrase != null }
        if (!pictures) {
            onMain { vm.choice.typeOwn() }
            at("03b-choose-own") { true }
            onMain { vm.choice.showSuggested() }
        }
        onMain {
            vm.choice.useTheseWords()
            vm.chosen()
        }
        at("04-write-down") { vm.ui.value.stage == ChangeStage.WRITE_DOWN }
        onMain { vm.wroteItDown() }
        at("05-type-again") { vm.ui.value.stage == ChangeStage.TYPE_AGAIN }
        if (!pictures) {
            onMain {
                vm.choice.again.edit { append("does-not-match") }
                vm.submitAgain()
            }
            at("05b-type-again-mismatch") { vm.choice.state.value.againMessage != null }
        }
        onMain {
            vm.choice.again.edit { append(vm.choice.state.value.chosen!!.text()) }
            vm.submitAgain()
        }
        at("06-keep-pin") { vm.ui.value.stage == ChangeStage.PIN }
        onMain {
            fake.changeResults += ChangeResult.PinWrong(left = 3)
            "143637".forEach { vm.digit(it - '0') }
        }
        at("07-keep-pin-wrong") { vm.ui.value.line != null && vm.ui.value.stage == ChangeStage.PIN }
        onMain {
            lock.holdChange = true
            "582913".forEach { vm.digit(it - '0') }
        }
        at("08-saving") { vm.ui.value.stage == ChangeStage.SAVING }
        onMain {
            lock.openChange()
            lock.holdChange = false
        }
        at("09-changed-backup") { vm.ui.value.stage == ChangeStage.BACKUP }
        onMain {
            vm.deletePlace(MasterRig.BACKUPS[1])
            vm.deletePlace(MasterRig.BACKUPS[0])
        }
        at("10-changed-backups-deleted") { vm.ui.value.places.none { it.state == PlaceState.LISTED || it.state == PlaceState.DELETING } }
    }

    /** The ends of a change that did not go through. */
    fun walkChangeFailures() {
        for ((result, name) in listOf(ChangeResult.NoMemory to "no-memory", ChangeResult.NothingChanged to "not-changed", ChangeResult.Unclear to "unclear")) {
            onMain {
                fake.status.value = fake.status.value.copy(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.Off)
                fake.changeResults += result
            }
            val vm = change(Vault())
            onMain { shown = Shown.Change(vm) }
            onMain {
                vm.current.field.edit { append("correct horse battery staple") }
                vm.submitCurrent()
            }
            compose.waitUntil(WAIT_MS) { vm.ui.value.stage == ChangeStage.CHOOSE && vm.choice.state.value.passphrase != null }
            onMain {
                vm.choice.useTheseWords()
                vm.chosen()
                vm.wroteItDown()
                vm.choice.again.edit { append(vm.choice.state.value.chosen!!.text()) }
                vm.submitAgain()
            }
            at("change-$name") { vm.ui.value.stage != ChangeStage.SAVING && vm.ui.value.stage != ChangeStage.TYPE_AGAIN }
        }
    }

    /** A new master password: the explanation, the PIN, the choice, the request and the wait. */
    fun walkNew(pictures: Boolean) {
        onMain {
            fake.status.value = fake.status.value.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.On, pending = MasterRig.WAITING)
            fake.proofResults += ProofResult.NeedsPin
        }
        val vm = newOne()
        onMain { shown = Shown.New(vm) }
        at("11-explain") { vm.ui.value.stage == NewStage.EXPLAIN }
        onMain { vm.proveWithoutPin() }
        at("12-proof-pin") { vm.ui.value.stage == NewStage.PIN }
        onMain {
            fake.proofResults += ProofResult.PinWrong(left = 4)
            "143637".forEach { vm.digit(it - '0') }
        }
        at("13-proof-pin-wrong") { vm.ui.value.line != null }
        if (!pictures) {
            onMain {
                fake.proofResults += ProofResult.PinGone
                "143637".forEach { vm.digit(it - '0') }
            }
            at("13b-pin-gone") { vm.ui.value.stage == NewStage.PIN_GONE }
            onMain { fake.proofResults += ProofResult.FingerprintFailed(FingerprintLine.Locked) }
            onMain { vm.proveWithoutPin() }
            at("13c-fingerprint-failed") { !vm.ui.value.proving && vm.ui.value.line != null }
        }
        onMain {
            fake.proofResults += ProofResult.Proven(Shortcut.PIN)
            if (vm.ui.value.stage == NewStage.PIN) "582913".forEach { vm.digit(it - '0') } else vm.proveWithoutPin()
        }
        at("14-choose") { vm.ui.value.stage == NewStage.CHOOSE && vm.choice.state.value.passphrase != null }
        onMain {
            vm.choice.useTheseWords()
            vm.chosen()
            vm.wroteItDown()
            lock.holdRequest = true
            vm.choice.again.edit { append(vm.choice.state.value.chosen!!.text()) }
            vm.submitAgain()
        }
        at("15-requesting") { vm.ui.value.stage == NewStage.REQUESTING }
        if (!pictures) {
            onMain {
                fake.requestResults += RequestResult.NotSaved
                lock.openRequest()
            }
            at("15b-request-failed") { vm.ui.value.stage == NewStage.REQUEST_FAILED }
            onMain { vm.retryRequest() }
        }
        onMain {
            lock.openRequest()
            lock.holdRequest = false
        }
        at("16-wait") { vm.ui.value.stage == NewStage.WAIT }
    }

    /** The ends of the asking flow that stop it: the PIN cannot be checked, and neither shortcut works. */
    fun walkNewStops() {
        onMain {
            fake.status.value = fake.status.value.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.Off, pending = null)
            fake.proofResults += ProofResult.CannotCheck
        }
        val vm = newOne()
        onMain { shown = Shown.New(vm) }
        onMain { vm.proveWithoutPin() }
        at("new-cannot-check") { vm.ui.value.stage == NewStage.STOPPED }
        onMain { fake.status.value = fake.status.value.copy(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.Off) }
        val other = newOne()
        onMain { shown = Shown.New(other) }
        at("new-not-offered") { other.ui.value.stage == NewStage.STOPPED }
        onMain { fake.status.value = fake.status.value.copy(pin = PinStatus.NotSet, fingerprint = FingerprintStatus.On) }
        val fingerprint = newOne()
        onMain { shown = Shown.New(fingerprint) }
        at("new-explain-fingerprint") { fingerprint.ui.value.stage == NewStage.EXPLAIN }
        onMain {
            lock.holdProof = true
            fingerprint.proveWithoutPin()
        }
        at("new-fingerprint-prompt") { fingerprint.ui.value.proving }
        onMain {
            fake.proofResults += ProofResult.Cancelled
            lock.openProof()
            lock.holdProof = false
        }
        compose.waitUntil(WAIT_MS) { !fingerprint.ui.value.proving }
    }

    /** "Finish now": the field, a wrong one, the wait, "Finishing…", and the new backup. */
    fun walkFinish(pictures: Boolean) {
        onMain {
            fake.status.value = fake.status.value.copy(pin = PinStatus.On, fingerprint = FingerprintStatus.Off, pending = MasterRig.READY)
            fake.finishResults += FinishResult.Wrong
        }
        val vm = newOne()
        onMain { shown = Shown.New(vm) }
        at("17-finish") { vm.ui.value.stage == NewStage.FINISH }
        onMain {
            vm.finishField.edit { append("not it") }
            vm.submitFinish()
        }
        at("18-finish-wrong") { vm.ui.value.line != null }
        if (!pictures) {
            for (result in listOf(FinishResult.NotFinished, FinishResult.NoMemory, FinishResult.Wait(android.os.SystemClock.elapsedRealtime() + 30_000L))) {
                onMain {
                    fake.finishResults += result
                    vm.finishField.edit { append("typed") }
                    vm.submitFinish()
                }
                at("18-finish-$result") { !vm.ui.value.finishing }
            }
            return
        }
        onMain {
            lock.holdFinish = true
            vm.finishField.edit { append("the new one") }
            vm.submitFinish()
        }
        at("19-finishing") { vm.ui.value.finishing }
        onMain {
            lock.openFinish()
            lock.holdFinish = false
        }
        at("20-finished-backup") { vm.ui.value.stage == NewStage.FINISHED }
    }

    fun close() = scope.cancel()

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
