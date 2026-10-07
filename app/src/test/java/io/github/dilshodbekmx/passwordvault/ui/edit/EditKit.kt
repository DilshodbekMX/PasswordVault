package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthHint
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What the tests of the add and edit package share: a test dispatcher as the main one and as all
 * three of `AppDispatchers`, the fakes of `APP/fake/` and `:testing`, a meter that needs no word list,
 * and ViewModels kept in a store that [run] clears at the end, as Navigation 3 does when the form
 * leaves the back stack.
 *
 * Typing is `TextFieldState` editing followed by [settle], which sends the snapshot notices that
 * Compose's frame clock would send on the phone, so that the ViewModel's `snapshotFlow`s see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditKit private constructor(val scope: TestScope, private val dispatcher: TestDispatcher) {
    val clock = FakeClock()
    val settings = FakeAppSettings()
    val random = FakeRandom(7)
    val texts = FakeTexts()
    private val store = ViewModelStore()
    private var made = 0

    /** A repository holding [vault], open, whose edits get the [clock]'s wall time. */
    fun repository(vault: Vault = Vault()): FakeVaultRepository = FakeVaultRepository(vault) { clock.wallMs() }

    /** A form of [entryId] (null: a new account) on [repository], loaded. */
    fun form(repository: VaultRepository, entryId: String? = null): EditViewModel {
        val factory = viewModelFactory {
            initializer {
                EditViewModel(entryId, repository, settings, random, clock, AppDispatchers.single(dispatcher), ::meter)
            }
        }
        val vm = ViewModelProvider.create(store, factory)["form-${made++}", EditViewModel::class]
        settle()
        return vm
    }

    /** Sends the snapshot notices and runs everything that is due. */
    fun settle() {
        Snapshot.sendApplyNotifications()
        scope.advanceUntilIdle()
    }

    /** Replaces the whole text of [state] with [text], as a paste over everything would, without [input]'s rules. */
    fun set(state: TextFieldState, text: String) {
        state.setTextAndPlaceCursorAtEnd(text)
        settle()
    }

    /** Types [text] at the end of [state] through [input], as the field's keyboard or a paste does. */
    fun type(state: TextFieldState, input: InputTransformation, text: String) {
        state.edit {
            append(text)
            with(input) { transformInput() }
        }
        settle()
    }

    /** The ViewModels go, as when the form leaves the back stack. */
    fun clear() = store.clear()

    companion object {
        /** Runs [test] with a test dispatcher of its own as the main one; the forms are cleared at the end. */
        fun run(test: suspend EditKit.() -> Unit) {
            val dispatcher = StandardTestDispatcher()
            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val kit = EditKit(this, dispatcher)
                    try {
                        kit.test()
                    } finally {
                        kit.clear()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

        /**
         * A meter without zxcvbn's lists: 4 ("Strong") from 12 characters, else 1 ("Very weak").
         * The real one is `StrengthMeter`'s, tested by package 2.12.
         */
        fun meter(password: CharSequence, @Suppress("UNUSED_PARAMETER") inputs: List<String>): Strength =
            if (password.length >= 12) Strength(4, null) else Strength(1, StrengthHint.ADD_WORD)

        /** An account as the vault holds it. */
        fun entry(id: String, title: String, username: String = "", password: String = "", deletedAt: Long? = null) =
            Entry(id = id, title = title, username = username, password = password, createdAt = 1L, updatedAt = 1L, deletedAt = deletedAt)

        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val BANK = "22222222-2222-4222-8222-222222222222"
        const val OLD = "33333333-3333-4333-8333-333333333333"
    }
}

/** [fake], whose saves take [saveMs] of the test's time, as a write to a slow flash chip would. */
class SlowRepository(val fake: FakeVaultRepository, private val saveMs: Long) : VaultRepository by fake {
    override suspend fun <T> edit(change: (Vault, nowMs: Long) -> Edited<T>): SaveResult<T> {
        delay(saveMs)
        return fake.edit(change)
    }
}

/** [EditTexts] with fixed English words: "Untitled" and the time as a number. */
class FakeTexts : EditTexts {
    override fun untitled(nowMs: Long): String = "Untitled $nowMs"

    override fun fromBackup(title: String): String = "$title (from backup)"

    override fun fromFile(title: String): String = "$title (from file)"
}
