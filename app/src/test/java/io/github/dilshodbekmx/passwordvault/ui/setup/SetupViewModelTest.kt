package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.Wordlist
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordCheck
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordRules
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthHint
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 3.3, [JVM]: the view model of the setup screens (ux.md §5.3 to §5.5; lock.md F1 steps
 * 1 to 3) against `FakeLockManager`, `FakeAppSettings` and `FakeRandom`, with the real checklist
 * (`MasterPasswordRules` and the strength meter with the bundled word list): the mismatch messages,
 * the Cyrillic-keyboard hint, the checklist applied after folding, "Show other words", and the
 * interrupted-setup text when words were shown. Also the reuse question, the vault made, what each
 * step drops when it leaves the back stack, and a new process without the words.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val lock = FakeLockManager()
    private val settings = FakeAppSettings()
    private val random = FakeRandom(seed = 7L)

    @Before
    fun mainIsTheTestDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    private fun model(rules: (CharArray) -> MasterPasswordCheck = { MasterPasswordRules.check(it) }) =
        SetupViewModel(lock, settings, random, AppDispatchers.single(dispatcher), rules)

    /** The words that the next six draws of [random] pick, after queueing [indices]. */
    private fun queueWords(vararg indices: Int): List<String> {
        require(indices.size == 6)
        random.queueInts(*indices)
        return indices.map { Wordlist.effShort[it] }
    }

    private fun TextFieldState.type(text: String) {
        setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    private fun TestScope.chooseWords(setup: SetupViewModel): String {
        val words = queueWords(10, 20, 30, 40, 50, 60)
        setup.showWords()
        advanceUntilIdle()
        assertTrue(setup.useTheseWords())
        return words.joinToString("-")
    }

    private fun TestScope.chooseOwn(setup: SetupViewModel, password: String) {
        setup.typeOwn()
        setup.own.type(password)
        setup.submitOwn()
        advanceUntilIdle()
        assertTrue("the checklist passes for the test's password: ${setup.state.value}", setup.state.value.askReuse)
        assertTrue(setup.answerReuse(usedElsewhere = false))
    }

    private val mismatch = UiText.of(R.string.setup_again_mismatch)
    private val cyrillicHint = UiText.of(R.string.setup_again_cyrillic)

    // ---- Suggested words ------------------------------------------------------------------------

    @Test
    fun theFirstWordsAreSixOfTheListAndTheirShowingIsRemembered() = runTest(dispatcher) {
        val words = queueWords(0, 1, 2, 3, 1293, 1294)
        val setup = model()
        assertNull("no words before they are asked for", setup.state.value.passphrase)
        setup.showWords()
        advanceUntilIdle()
        assertEquals(words, setup.state.value.passphrase?.words)
        assertFalse(setup.state.value.interrupted)
        assertEquals(setOf(OneTime.WORDS_SHOWN), settings.oneTime.value)
        setup.showWords()
        advanceUntilIdle()
        assertEquals("a second showing keeps the words", words, setup.state.value.passphrase?.words)
        assertEquals(1, settings.writes)
    }

    @Test
    fun showOtherWordsGivesSixNewWords() = runTest(dispatcher) {
        val first = queueWords(5, 6, 7, 8, 9, 10)
        val second = queueWords(100, 200, 300, 400, 500, 600)
        val setup = model()
        setup.showWords()
        advanceUntilIdle()
        assertEquals(first, setup.state.value.passphrase?.words)
        setup.showOtherWords()
        advanceUntilIdle()
        assertEquals(second, setup.state.value.passphrase?.words)
        assertTrue(setup.useTheseWords())
        assertEquals(second, setup.state.value.chosen?.words)
        assertEquals(second.joinToString("-"), setup.state.value.chosen?.text())
        assertEquals("the flag is written once", 1, settings.writes)
    }

    @Test
    fun useTheseWordsDoesNothingBeforeTheWordsAreThere() = runTest(dispatcher) {
        val setup = model()
        assertFalse(setup.useTheseWords())
        assertNull(setup.state.value.chosen)
    }

    // ---- Interrupted setup ----------------------------------------------------------------------

    @Test
    fun wordsShownBeforeThisViewModelGiveTheInterruptedText() = runTest(dispatcher) {
        settings.oneTime.value = setOf(OneTime.WORDS_SHOWN) // a process that showed words and ended
        val setup = model()
        queueWords(1, 2, 3, 4, 5, 6)
        setup.showWords()
        advanceUntilIdle()
        assertTrue(setup.state.value.interrupted)
        assertEquals("the flag is already there: nothing is written", 0, settings.writes)
    }

    @Test
    fun noInterruptedTextOnAFirstSetup() = runTest(dispatcher) {
        val setup = model()
        queueWords(1, 2, 3, 4, 5, 6)
        setup.showWords()
        advanceUntilIdle()
        assertFalse(setup.state.value.interrupted)
    }

    @Test
    fun theVaultMadeClearsTheFlagOfWordsShown() = runTest(dispatcher) {
        settings.oneTime.value = setOf(OneTime.WORDS_SHOWN, OneTime.FIRST_COPY_TIP)
        val setup = model()
        val password = chooseWords(setup)
        setup.again.type(password)
        setup.submitAgain()
        advanceUntilIdle()
        assertTrue(lock.state.value is LockUiState.Unlocked)
        assertEquals(setOf(OneTime.FIRST_COPY_TIP), settings.oneTime.value)
        assertFalse(setup.state.value.interrupted)
    }

    @Test
    fun wordsShownThenSetupLeftForAVaultPutAsideGiveTheInterruptedTextOnReturn() = runTest(dispatcher) {
        val setup = model()
        chooseWords(setup)
        val asideScreen = LockScreen.MasterPassword(
            reason = null, canUsePin = false, practice = null, waitUntilElapsedMs = null, error = null,
            pendingReadyInMs = null, opening = false, aside = AsideLine.LockedOn(1_000L),
        )
        lock.state.value = LockUiState.Locked(asideScreen) // "Open my previous passwords", then Back
        advanceUntilIdle()
        assertNull("leaving setup drops the chosen password", setup.state.value.chosen)
        assertNull("and the words", setup.state.value.passphrase)
        lock.state.value = LockUiState.NoVault(emptyList())
        advanceUntilIdle()
        queueWords(7, 8, 9, 10, 11, 12)
        setup.showWords()
        advanceUntilIdle()
        assertTrue(setup.state.value.interrupted)
        assertTrue("no vault exists: the flag stays", OneTime.WORDS_SHOWN in settings.oneTime.value)
    }

    // ---- "Type your master password again" ----------------------------------------------------

    @Test
    fun mismatchMessagesAndShowMyPasswordAgainAfterThree() = runTest(dispatcher) {
        val setup = model()
        val password = chooseWords(setup)
        for (n in 1..3) {
            setup.again.type("$password-x$n")
            setup.submitAgain()
            advanceUntilIdle()
            val state = setup.state.value
            assertEquals(mismatch, state.againMessage)
            assertEquals(n, state.mismatches)
            assertEquals("Show my password again from the third mismatch on", n >= 3, state.canShowAgain)
            assertNull("no Cyrillic: no hint", state.keyboardHint)
            assertEquals("the field is emptied for the next try", "", setup.again.text.toString())
            assertFalse(state.settingUp)
        }
        assertTrue("nothing was made", lock.state.value is LockUiState.NoVault)
        // "Show my password again" goes back to step 2; leaving step 3 starts its count again.
        setup.stepLeft(SetupStep.TYPE_AGAIN)
        assertEquals(0, setup.state.value.mismatches)
        assertNull(setup.state.value.againMessage)
        assertNotNull("the password chosen in step 1 stays", setup.state.value.chosen)
    }

    @Test
    fun anEmptySecondTypingIsAMismatch() = runTest(dispatcher) {
        val setup = model()
        chooseWords(setup)
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(mismatch, setup.state.value.againMessage)
    }

    @Test
    fun cyrillicLettersInTheSecondTypingGiveTheKeyboardHint() = runTest(dispatcher) {
        val setup = model()
        chooseWords(setup)
        setup.again.type("цщцуд-ырштн-ырудд") // the words typed on a Russian keyboard layout
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(mismatch, setup.state.value.againMessage)
        assertEquals(cyrillicHint, setup.state.value.keyboardHint)
        setup.again.type("vowel-shiny")
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(mismatch, setup.state.value.againMessage)
        assertNull("a Latin mismatch has no hint", setup.state.value.keyboardHint)
    }

    @Test
    fun noKeyboardHintWhenTheMasterPasswordItselfIsCyrillic() = runTest(dispatcher) {
        val setup = model()
        chooseOwn(setup, "Тq7#vЛ9!pЖ2@xШ")
        setup.again.type("Тq7#vЛ9!pЖ2@xЩ")
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(mismatch, setup.state.value.againMessage)
        assertNull(setup.state.value.keyboardHint)
    }

    @Test
    fun theSameTypingMakesTheVaultAndEndsSetup() = runTest(dispatcher) {
        val setup = model()
        val password = chooseWords(setup)
        setup.again.type(password)
        setup.submitAgain()
        advanceUntilIdle()
        val unlocked = lock.state.value
        assertTrue("the lock made the vault and unlocked: $unlocked", unlocked is LockUiState.Unlocked)
        val state = setup.state.value
        assertNull("nothing of the password is kept", state.chosen)
        assertNull(state.passphrase)
        assertEquals("", setup.again.text.toString())
        assertEquals("", setup.own.text.toString())
        assertFalse(state.settingUp)
        assertTrue("the PIN steps after setup end with \"Make backups\" (decision 24)", PinSetupStart.takeNewVault())
    }

    @Test
    fun aSecondTypingWithAnotherApostropheIsTheSamePassword() = runTest(dispatcher) {
        val setup = model()
        chooseOwn(setup, "Tq7#voʻgʻlim!pZ2@x")
        lock.createResults += CreateResult.Created(lock.vaultId) // the fake compares raw characters; the real lock folds
        setup.again.type("Tq7#vo'g'lim!pZ2@x")
        setup.submitAgain()
        advanceUntilIdle()
        assertNull("no mismatch: the typings are compared as MasterPasswordBytes", setup.state.value.againMessage)
        assertTrue(lock.state.value is LockUiState.Unlocked)
    }

    @Test
    fun couldNotSaveAndNoMemoryOfferTryAgainWithTheSameTyping() = runTest(dispatcher) {
        val setup = model()
        val password = chooseWords(setup)
        lock.createResults += CreateResult.NotSaved
        lock.createResults += CreateResult.NoMemory
        setup.again.type(password)
        PinSetupStart.newVault() // as if left by an earlier try
        setup.submitAgain()
        advanceUntilIdle()
        assertFalse("no vault was made: no mark for the PIN steps", PinSetupStart.takeNewVault())
        assertEquals(UiText.of(R.string.setup_again_not_saved), setup.state.value.againMessage)
        assertTrue(setup.state.value.canRetry)
        assertEquals("the typing stays for Try again", password, setup.again.text.toString())
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.setup_again_no_memory), setup.state.value.againMessage)
        assertTrue(setup.state.value.canRetry)
        setup.submitAgain()
        advanceUntilIdle()
        assertTrue(lock.state.value is LockUiState.Unlocked)
    }

    // ---- A password of one's own --------------------------------------------------------------

    @Test
    fun theChecklistIsAppliedAfterFolding() = runTest(dispatcher) {
        val setup = model()
        setup.typeOwn()
        assertEquals(CreateView.OWN, setup.state.value.view)

        // 18 characters as typed; white space folded, 11: "At least 12 characters" is not met.
        setup.own.type("  ab  cd  ef  gh  ")
        advanceUntilIdle()
        assertEquals(false, setup.state.value.check?.longEnough)

        // White space only: no characters at all after folding.
        setup.own.type("              ")
        advanceUntilIdle()
        assertEquals(false, setup.state.value.check?.longEnough)

        // The apostrophe hides the word from zxcvbn as typed; its folded search key does not (ux.md §5.3, §12 check 18).
        setup.own.type("Oʻzbekiston2024")
        advanceUntilIdle()
        val check = setup.state.value.check!!
        assertTrue(check.longEnough)
        assertFalse(check.hardToGuess)
        assertTrue(check.strength.score <= 2)
        assertEquals(UiText.of(R.string.setup_own_hint_names), setup.state.value.strengthHint)

        setup.own.type("Tq7#vL9!pZ2@xW")
        advanceUntilIdle()
        assertTrue(setup.state.value.check!!.passes)
        assertNull(setup.state.value.strengthHint)

        setup.own.type("")
        advanceUntilIdle()
        assertNull("an empty field has no checklist yet", setup.state.value.check)
    }

    @Test
    fun theMixedScriptsQuestion() = runTest(dispatcher) {
        val setup = model()
        setup.typeOwn()
        setup.own.type("Tq7#vЛ9!pZ2@xW")
        advanceUntilIdle()
        assertTrue(setup.state.value.check!!.mixesScripts)
        setup.own.type("Tq7#vL9!pZ2@xW")
        advanceUntilIdle()
        assertFalse(setup.state.value.check!!.mixesScripts)
    }

    @Test
    fun useThisPasswordSaysWhatIsMissing() = runTest(dispatcher) {
        val setup = model()
        setup.typeOwn()
        fun submit(text: String): UiText? {
            setup.own.type(text)
            setup.submitOwn()
            advanceUntilIdle()
            return setup.state.value.ownMessage
        }
        assertEquals(UiText.of(R.string.setup_own_missing_length), submit(""))
        assertEquals(UiText.of(R.string.setup_own_missing_length), submit("short"))
        assertEquals(UiText.of(R.string.setup_own_missing_hard), submit("Samarqand2024!"))
        assertFalse(setup.state.value.askReuse)
        // U+0378 is not assigned by Unicode: refused at creation [V-C9].
        assertEquals(UiText.of(R.string.setup_own_unassigned), submit("Tq7#vL9!pZ2@xW͸"))
        assertNull(submit("Tq7#vL9!pZ2@xW"))
        assertTrue("all pass: the reuse question", setup.state.value.askReuse)
    }

    @Test
    fun anEditClearsTheMessageAndTheSameTextKeepsIt() = runTest(dispatcher) {
        val setup = model()
        setup.typeOwn()
        setup.own.type("short")
        setup.submitOwn()
        advanceUntilIdle()
        assertEquals(UiText.of(R.string.setup_own_missing_length), setup.state.value.ownMessage)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertNotNull("no edit: the message stays", setup.state.value.ownMessage)
        setup.own.type("shorter")
        advanceUntilIdle()
        assertNull(setup.state.value.ownMessage)
    }

    @Test
    fun theReuseQuestion() = runTest(dispatcher) {
        val setup = model()
        setup.typeOwn()
        setup.own.type("Tq7#vL9!pZ2@xW")
        setup.submitOwn()
        advanceUntilIdle()
        assertTrue(setup.state.value.askReuse)

        assertFalse("Yes: stay at the field", setup.answerReuse(usedElsewhere = true))
        assertFalse(setup.state.value.askReuse)
        assertEquals(UiText.of(R.string.setup_own_reuse_refused), setup.state.value.ownMessage)
        assertNull(setup.state.value.chosen)

        setup.submitOwn()
        advanceUntilIdle()
        setup.dismissReuse()
        assertFalse(setup.state.value.askReuse)
        assertFalse("no answer, no choice", setup.answerReuse(usedElsewhere = false))

        setup.submitOwn()
        advanceUntilIdle()
        assertTrue("No, it's new: step 2 follows", setup.answerReuse(usedElsewhere = false))
        val chosen = setup.state.value.chosen!!
        assertNull("a password of one's own has no words", chosen.words)
        assertEquals("Tq7#vL9!pZ2@xW", chosen.text())
    }

    @Test
    fun anOwnPasswordIsCheckedOnTheThirdStep() = runTest(dispatcher) {
        val setup = model()
        chooseOwn(setup, "Tq7#vL9!pZ2@xW")
        setup.again.type("Tq7#vL9!pZ2@xV")
        setup.submitAgain()
        advanceUntilIdle()
        assertEquals(mismatch, setup.state.value.againMessage)
        setup.again.type("Tq7#vL9!pZ2@xW")
        setup.submitAgain()
        advanceUntilIdle()
        assertTrue(lock.state.value is LockUiState.Unlocked)
    }

    // ---- Steps that leave the back stack, and a new process ------------------------------------

    @Test
    fun eachStepDropsWhatItHeldWhenItLeavesTheBackStack() = runTest(dispatcher) {
        val setup = model()
        val password = chooseWords(setup)
        setup.setWroteItDown(true)
        setup.again.type("wrong")
        setup.submitAgain()
        advanceUntilIdle()

        setup.stepLeft(SetupStep.TYPE_AGAIN)
        assertEquals("", setup.again.text.toString())
        assertEquals(0, setup.state.value.mismatches)
        assertEquals(password, setup.state.value.chosen?.text())

        setup.stepLeft(SetupStep.WRITE_DOWN)
        assertNull("Back from step 2: the choice is made again", setup.state.value.chosen)
        assertFalse(setup.state.value.wroteItDown)
        val words = setup.state.value.passphrase?.words
        assertNotNull(words)

        setup.typeOwn()
        setup.own.type("half typed")
        advanceUntilIdle()
        setup.stepLeft(SetupStep.CREATE)
        assertEquals("", setup.own.text.toString())
        assertEquals(CreateView.SUGGESTED, setup.state.value.view)
        assertNull(setup.state.value.check)
        assertEquals("Back to Welcome keeps the words for the next Start", words, setup.state.value.passphrase?.words)
    }

    @Test
    fun aNewProcessHasNoChosenPasswordSoTheLaterStepsGoBack() = runTest(dispatcher) {
        settings.oneTime.value = setOf(OneTime.WORDS_SHOWN) // the old process showed the words
        val setup = model() // made by the restored "Type it again" route
        assertNull(setup.state.value.chosen)
        setup.submitAgain() // nothing to compare with: nothing happens
        advanceUntilIdle()
        assertTrue(lock.state.value is LockUiState.NoVault)
        assertNull(setup.state.value.againMessage)
        queueWords(1, 2, 3, 4, 5, 6)
        setup.showWords()
        advanceUntilIdle()
        assertTrue("step 1 then shows new words as an interrupted setup", setup.state.value.interrupted)
    }

    @Test
    fun theStateNamesNothingOfThePassword() = runTest(dispatcher) {
        val setup = model()
        val password = chooseWords(setup)
        val text = setup.state.value.toString()
        for (word in password.split('-')) assertFalse("$word in $text", text.contains(word))
        assertFalse(setup.state.value.passphrase.toString().contains(password.substringBefore('-')))
        assertNotEquals(password, setup.state.value.chosen.toString())
    }

    // ---- Welcome --------------------------------------------------------------------------------

    @Test
    fun welcomeOffersEachVaultPutAsideOnceNewestFirst() {
        val a = AsideVault("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", putAsideAtMs = 1_000L, newest = null)
        val b = AsideVault("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", putAsideAtMs = 3_000L, newest = null)
        val c = AsideVault("cccccccc-cccc-4ccc-8ccc-cccccccccccc", putAsideAtMs = 2_000L, newest = null)
        val state = LockUiState.NoVault(listOf(a, b))
        val status = LockStatus.NONE.copy(aside = listOf(c, a))
        assertEquals(listOf(b, c, a), asideVaults(state, status))
        assertEquals(emptyList<AsideVault>(), asideVaults(LockUiState.NoVault(emptyList()), LockStatus.NONE))
        assertEquals(listOf(c), asideVaults(LockUiState.Starting, LockStatus.NONE.copy(aside = listOf(c))))
    }

    @Test
    fun theStrengthHints() {
        assertEquals(UiText.of(R.string.setup_own_hint_add_word), strengthHintText(StrengthHint.ADD_WORD))
        assertEquals(UiText.of(R.string.setup_own_hint_names), strengthHintText(StrengthHint.AVOID_NAMES_AND_YEARS))
        assertEquals(UiText.of(R.string.setup_own_hint_keyboard), strengthHintText(StrengthHint.AVOID_KEYBOARD_ROWS))
        val strong = SetupState(check = MasterPasswordCheck(true, Strength(4, null), unassignedCharacter = false, mixesScripts = false))
        assertNull(strong.strengthHint)
    }
}
