package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderSetQuestion
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OpenVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What the core gives the question of lock.md F16b step 3, "Open the older set instead?": while a
 * visit holds an `OlderSetQuestion`, the lock screen is `LockScreen.OpenOlderSet` and nothing can be
 * typed; the bytes kept for the proof of step 6 go with the visit, like the PIN digits (§5.8). The
 * flow itself (when the question is put up, and what each answer does) is `OlderCopies`, build plan
 * 4.4; these tests put the question on the visit as that flow will.
 */
class OlderSetQuestionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val replacedAt = 1_790_000_000_000L
    private val oldWords = "the words from before"

    /** A copy of the typed bytes, as the flow keeps it for the proof of step 6. */
    private val kept = bytes(oldWords)

    /**
     * The current vault with its PIN; [OTHER_VAULT] put aside and marked as replaced by it; the old
     * vault's own lock screen shown (F16b step 2), and then the question put on its visit.
     */
    private fun askTheQuestion() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes(oldWords), putAsideAtMs = replacedAt)
        f.plantState(OTHER_VAULT, replaced = StateRecord.Replaced(VAULT, replacedAt, Shortcut.PIN, 0L, f.clock.elapsedMs()))
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        assertEquals(AsideLine.FromBefore(replacedAt), f.field.aside)

        val core = f.engine.core
        val newest = old.live.first()
        await {
            core.locked {
                core.visit!!.olderSet = OlderSetQuestion(replacedAt, OpenVault(OTHER_VAULT, newest.number, newest.vkId, newest.createdMs, false), kept)
                core.showLockScreen()
            }
        }
    }

    private val question get() = LockState.Locked(LockScreen.OpenOlderSet(replacedAt, AsideLine.FromBefore(replacedAt)))

    @Test
    fun whileTheQuestionIsOnTheVisit_theLockScreenIsTheQuestion_andNothingCanBeTypedThere() {
        askTheQuestion()
        assertEquals(question, f.state)
        val calls = f.port.calls.size

        // Whatever decides the screen again keeps the question.
        await { f.engine.refresh() }
        assertEquals(question, f.state)
        await { f.engine.useMasterPassword() }
        await { f.engine.usePin() }
        assertEquals(question, f.state)

        // No field, no pad and no fingerprint is behind it.
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(oldWords))
        f.type(PIN)
        await { f.engine.useFingerprint() }
        await { f.engine.promptIfDue() }
        assertEquals(question, f.state)
        assertEquals("nothing was asked of storage", emptyList<String>(), f.port.calls.drop(calls).filter { it.startsWith("open") })
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(0, f.keys.hmacCalls.size)
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures)
        assertTrue("the bytes are kept while the question is shown", kept.contentEquals(bytes(oldWords)))
    }

    @Test
    fun whenTheVisitEnds_theKeptBytesAreZeroed_andTheNextVisitShowsTheFieldAgain() {
        askTheQuestion()

        await { f.engine.appStopped() } // Home while the question is shown

        assertTrue("the bytes kept for the proof are zeroed with the visit", kept.all { it == 0.toByte() })
        assertNull(f.engine.core.visit)
        await { f.engine.appStarted() }
        assertNull(f.engine.core.visit!!.olderSet)
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason) // that vault's own lock screen, as before the question
        assertFalse(f.field.canUsePin)
    }

    @Test
    fun backToTheCurrentVault_endsTheVisit_andZeroesTheKeptBytes() {
        askTheQuestion()

        await { f.engine.goBack() }

        assertTrue(kept.all { it == 0.toByte() })
        assertTrue(f.screen is LockScreen.Pad) // the current vault's lock screen; nothing changed
        assertEquals(VAULT, f.engine.core.target!!.vaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun aFlowThatTakesTheQuestionOffTheVisit_ownsTheBytes_andTheFieldIsShownAgain() {
        askTheQuestion()
        val core = f.engine.core

        await {
            core.locked {
                core.visit!!.olderSet = null // "Cancel": the flow takes it off, and wipes what it took
                core.showLockScreen()
            }
        }

        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        assertTrue("the core does not wipe what a flow took", kept.contentEquals(bytes(oldWords)))
        await { f.engine.appStopped() }
        assertTrue(kept.contentEquals(bytes(oldWords)))
    }
}
