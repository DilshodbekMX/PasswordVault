package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import javax.crypto.Cipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: [PromptLauncher], the app's `BiometricLauncher`, against the prompt rules
 * of lock.md §9.2 with a fake activity prompt: launched only on a resumed activity (rule 3), one at
 * a time (rule 4), a callback taken only while a prompt is open (rule 8), the prompt of the newest
 * activity used and a rotation survived (rules 1 and 2), and a cancel from the lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptLauncherTest {
    private val main = TestMain()
    private val launcher = PromptLauncher(main)
    private val window = FakeWindow()

    private fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun TestScope.launch(use: PromptUse = PromptUse.UNLOCK, launch: Int = 1, cipher: Cipher = cipher(), pinUsable: Boolean = true) =
        backgroundScope.asyncOn { launcher.authenticate(cipher, use, launch, pinUsable) }

    // ---- rule 3: only on a resumed activity ------------------------------------------------------------

    @Test
    fun withoutAnActivityNothingIsShown() = runTest {
        assertEquals(PromptOutcome.NotShown, launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true))
        assertFalse(launcher.canOpenByItself)
        assertNull(launcher.open)
    }

    @Test
    fun onAnActivityThatIsNotResumedNothingIsLaunched() = runTest {
        launcher.attach(window)
        window.resumed = false
        assertEquals("the library would drop it without a callback", PromptOutcome.NotShown, launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true))
        assertEquals(0, window.shows.size)
        assertFalse(launcher.canOpenByItself)

        window.resumed = true
        window.focused = false
        assertFalse("a prompt opens by itself only when the activity's window has the focus", launcher.canOpenByItself)
        window.focused = true
        assertTrue(launcher.canOpenByItself)
    }

    // ---- showing and answering ----------------------------------------------------------------------------

    @Test
    fun aPromptIsShownWithItsUse_andItsCallbackEndsIt() = runTest {
        launcher.attach(window)
        val given = cipher()
        val waiting = launch(PromptUse.TURN_ON, launch = 7, cipher = given, pinUsable = false)
        runCurrent()

        val shown = window.shows.single()
        assertSame(given, shown.cipher)
        assertEquals(PromptUse.TURN_ON, shown.use)
        assertFalse(shown.pinUsable)
        assertEquals(7, launcher.open?.launch)
        assertEquals(PromptUse.TURN_ON, launcher.open?.use)
        assertFalse(waiting.isCompleted)

        val handedBack = cipher()
        launcher.succeeded(handedBack) // result.cryptoObject?.cipher
        runCurrent()
        val outcome = waiting.getCompleted() as PromptOutcome.Succeeded
        assertSame("the cipher of the result, never one kept here (lock.md §9.2 rule 6)", handedBack, outcome.cipher)
        assertNull(launcher.open)
    }

    @Test
    fun anErrorAndASuccessWithoutACipherAreHandedOnAsTheyCame() = runTest {
        launcher.attach(window)
        val first = launch()
        runCurrent()
        launcher.failed(13)
        runCurrent()
        assertEquals(PromptOutcome.Error(13), first.getCompleted())

        val second = launch(launch = 2)
        runCurrent()
        launcher.succeeded(null)
        runCurrent()
        assertNull((second.getCompleted() as PromptOutcome.Succeeded).cipher)
    }

    // ---- rule 4: one at a time ----------------------------------------------------------------------------

    @Test
    fun aSecondLaunchWhileAPromptIsOpenIsNotPassedOnToTheLibrary() = runTest {
        launcher.attach(window)
        val first = launch(launch = 1)
        runCurrent()
        assertEquals(PromptOutcome.NotShown, launcher.authenticate(cipher(), PromptUse.UNLOCK, 2, true))
        assertEquals("the library saw one launch", 1, window.shows.size)
        assertEquals(1, launcher.open?.launch)
        assertFalse(first.isCompleted)
    }

    // ---- rule 8: a callback only while a prompt is open --------------------------------------------------

    @Test
    fun aCallbackWithNoPromptOpenIsDropped_andIsNotTakenForTheNextPromptsAnswer() = runTest {
        launcher.attach(window)
        launcher.failed(10) // the late error of a prompt that the system closed while the app was away
        launcher.succeeded(cipher())
        runCurrent()
        assertNull(launcher.open)

        val next = launch()
        runCurrent()
        assertEquals(1, window.shows.size)
        assertFalse("the earlier callbacks did not answer it", next.isCompleted)
        launcher.failed(13)
        runCurrent()
        assertEquals(PromptOutcome.Error(13), next.getCompleted())
    }

    // ---- the lock ends a launch -------------------------------------------------------------------------------

    @Test
    fun cancelEndsTheWaitWithCode5AndClosesThePrompt_andThePromptsOwnCallbackIsThenDropped() = runTest {
        launcher.attach(window)
        val waiting = launch()
        runCurrent()

        launcher.cancel()
        runCurrent()

        assertEquals("as cancelAuthentication() would answer", PromptOutcome.Error(5), waiting.getCompleted())
        assertEquals(1, window.dismissals)
        assertNull(launcher.open)

        launcher.failed(5) // the cancelled prompt's own callback
        val next = launch(launch = 2)
        runCurrent()
        assertEquals("the next launch does not wait: the old prompt has answered", 2, window.shows.size)
        assertFalse(next.isCompleted)
    }

    @Test
    fun cancelWithNothingOpenDoesNothing() = runTest {
        launcher.attach(window)
        launcher.cancel()
        assertEquals(0, window.dismissals)
        val next = launch()
        runCurrent()
        assertEquals("and the next launch is shown at once", 1, window.shows.size)
        assertFalse(next.isCompleted)
    }

    @Test
    fun afterACancelTheNextLaunchGivesTheOldCallbackAMoment_thenGoesAhead() = runTest {
        launcher.attach(window)
        launch(launch = 1)
        runCurrent()
        launcher.cancel()

        val next = launch(launch = 2)
        runCurrent()
        assertEquals("not shown while the cancelled prompt may still answer", 1, window.shows.size)
        advanceTimeBy(PromptLauncher.STALE_WAIT_MS - 1)
        runCurrent()
        assertEquals(1, window.shows.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("it never answered (the activity was stopped): the launch goes ahead", 2, window.shows.size)
        assertEquals(2, launcher.open?.launch)
        assertFalse(next.isCompleted)
    }

    @Test
    fun theOldCallbackThatArrivesDuringThatMomentIsDropped_andTheNewPromptIsShownAtOnce() = runTest {
        launcher.attach(window)
        launch(launch = 1)
        runCurrent()
        launcher.cancel()
        val next = launch(launch = 2)
        runCurrent()
        assertEquals(1, window.shows.size)

        launcher.failed(5) // the cancelled prompt answers
        runCurrent()

        assertEquals(2, window.shows.size)
        assertFalse("its code 5 is not the new prompt's answer", next.isCompleted)
        launcher.succeeded(window.shows.last().cipher)
        runCurrent()
        assertTrue(next.getCompleted() is PromptOutcome.Succeeded)
    }

    @Test
    fun aLaunchThatTheLockEndsBeforeItIsShown_isNotShown() = runTest {
        launcher.attach(window)
        launch(launch = 1)
        runCurrent()
        launcher.cancel()
        val next = launch(launch = 2) // waits for the first prompt's callback
        runCurrent()

        launcher.cancel() // the lock ends launch 2 as well (a lock, the end of the visit)
        advanceTimeBy(PromptLauncher.STALE_WAIT_MS)
        runCurrent()

        assertEquals(PromptOutcome.Error(5), next.getCompleted())
        assertEquals("its prompt never appeared", 1, window.shows.size)
        assertNull(launcher.open)
    }

    @Test
    fun aCallerThatIsCancelledClosesItsPrompt() = runTest {
        launcher.attach(window)
        val job = backgroundScope.launch { launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true) }
        runCurrent()
        assertNotNull(launcher.open)

        job.cancel()
        runCurrent()

        assertNull(launcher.open)
        assertEquals(1, window.dismissals)
    }

    @Test
    fun aLaunchThatTheLibraryRefusesLeavesNothingOpen() = runTest {
        launcher.attach(window)
        window.showFails = IllegalStateException("refused")
        val refused = runCatching { launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true) }
        assertTrue(refused.exceptionOrNull() is IllegalStateException)
        assertNull(launcher.open)

        window.showFails = null
        val next = launch(launch = 2)
        runCurrent()
        assertEquals(2, launcher.open?.launch)
        assertFalse(next.isCompleted)
    }

    // ---- rules 1 and 2: the newest activity's prompt, and a rotation ---------------------------------------

    @Test
    fun aRotationLosesNothing_theNewActivitysPromptAnswersTheCallThatWaits() = runTest {
        launcher.attach(window)
        val waiting = launch(launch = 4)
        runCurrent()

        val recreated = FakeWindow()
        launcher.detach(window, forGood = false)
        launcher.attach(recreated)

        assertEquals("nothing was cancelled", 0, window.dismissals)
        assertEquals("and nothing launched again", 0, recreated.shows.size)
        assertEquals(4, launcher.open?.launch)
        assertFalse(waiting.isCompleted)

        launcher.succeeded(window.shows.single().cipher) // delivered by the new activity's prompt
        runCurrent()
        assertTrue(waiting.getCompleted() is PromptOutcome.Succeeded)

        val next = launch(launch = 5)
        runCurrent()
        assertEquals("from now on the new activity shows the prompts", 1, recreated.shows.size)
        assertFalse(next.isCompleted)
    }

    @Test
    fun theOlderActivitysDestroyDoesNotForgetTheNewerOnesPrompt() = runTest {
        launcher.attach(window)
        val newer = FakeWindow()
        launcher.attach(newer)
        launcher.detach(window, forGood = false) // the old activity is destroyed after the new one was created
        val waiting = launch()
        runCurrent()
        assertEquals(1, newer.shows.size)
        assertFalse(waiting.isCompleted)
    }

    @Test
    fun whenTheActivityIsGoneForGood_thereIsNoPrompt_andOnGoneIsTold() = runTest {
        var gone = 0
        launcher.onGone = { gone++ }
        launcher.attach(window)
        launcher.detach(window, forGood = false)
        assertEquals("a rotation is not \"gone\"", 0, gone)
        launcher.attach(window)
        launcher.detach(window, forGood = true)
        assertEquals(1, gone)
        assertEquals(PromptOutcome.NotShown, launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true))
    }

    // ---- rule 7: "one turn after the resume" ---------------------------------------------------------------

    @Test
    fun readyTellsOnReadyOneTurnLater_andOnlyForTheActivityThatIsThere() = runTest {
        var ready = 0
        launcher.onReady = { ready++ }
        launcher.attach(window)

        launcher.ready(window)
        assertEquals("not in the same turn: a callback left over from an earlier prompt comes first", 0, ready)
        main.runPosted()
        assertEquals(1, ready)

        launcher.ready(FakeWindow()) // an activity that is no longer the one
        main.runPosted()
        assertEquals(1, ready)
    }

    @Test
    fun aCancelFromAnotherThreadClosesThePromptOnTheMainThread() = runTest {
        val offMain = object : MainThread {
            val posted = ArrayDeque<() -> Unit>()
            override val isCurrent: Boolean get() = false
            override fun post(action: () -> Unit) {
                posted += action
            }
        }
        val launcher = PromptLauncher(offMain)
        launcher.attach(window)
        val waiting = backgroundScope.asyncOn { launcher.authenticate(cipher(), PromptUse.UNLOCK, 1, true) }
        runCurrent()

        launcher.cancel() // the lock engine ends a launch from its own thread
        runCurrent()

        assertEquals("the wait ends at once", PromptOutcome.Error(5), waiting.getCompleted())
        assertEquals("cancelAuthentication() is left to the main thread", 0, window.dismissals)
        offMain.posted.removeFirst().invoke()
        assertEquals(1, window.dismissals)
    }
}
