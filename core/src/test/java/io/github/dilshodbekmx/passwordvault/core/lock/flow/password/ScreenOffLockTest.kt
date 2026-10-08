package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The owner's decision of 2026-10-08 on lock.md §6.3 against §6.7 and decision 29 (build plan §12,
 * decision 2): **a screen-off lock while the lock screen shows ends the visit**, so a PIN,
 * fingerprint or master-password result that arrives afterwards opens nothing (I11). Before it, a
 * visit ended only at the process-level `ON_STOP`, about 700 ms after the screen-off, and a right
 * PIN whose one-second wait ended in between opened the vault with the screen off.
 *
 * `LockEngine.lock` takes the reason of the lock for it (`LockCause`); every other reason leaves a
 * locked app as it is.
 */
class ScreenOffLockTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    /** A vault with a PIN and no practice due, started and in front, on a clock the test moves by hand. */
    private fun phone(clock: FakeClock = FakeClock(elapsedMs = 3_600_000L, autoAdvance = false), row: Int = 0, total: Int = 0): LockFixture {
        val f = LockFixture(clock)
        val vault = f.seedVault()
        f.plantPin(vault, row = row, total = total)
        f.plantState()
        f.startInFront()
        return f
    }

    @Test
    fun aRightPinWhoseWaitEndsAfterAScreenOffLock_countsAsRight_andOpensNothing() {
        val f = phone(row = 2, total = 2)
        f.type("58291")
        val typedOn = f.engine.core.visit!!
        val seen = ArrayList<LockState>()
        runBlocking {
            val sixth = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.digit(3) } // the right PIN, waiting for its charge to age
            // The power button: ACTION_SCREEN_OFF reaches the lock at once; ON_STOP would follow 700 ms later.
            val screenOff = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.SCREEN_OFF) }
            assertTrue("the visit ends at once, before the mutex", typedOn.place.hasEnded)
            f.files.onBoundary = { _, _ -> seen += f.state }
            f.clock.advance(1_000)
            sixth.join()
            screenOff.join()
        }

        assertEquals("the try was checked and counted like any other", 1, f.keys.hmacCalls.size)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 2), f.pinRecord().guard)
        assertFalse("storage was never asked to open the vault", f.port.calls.any { it.startsWith("open") })
        assertNull(f.port.openVaultId)
        assertTrue("the app was never unlocked on the way: $seen", seen.none { it is LockState.Unlocked })
        assertTrue(f.state is LockState.Locked)
        // The next visit of the same lock screen has started: the screen may come back on before any ON_STOP.
        val next = f.engine.core.visit!!
        assertNotSame(typedOn, next)
        assertFalse(next.place.hasEnded)
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
    }

    @Test
    fun afterAScreenOffLock_thePadTakesANewEntry_withoutAnyOnStopOrOnStart() {
        val f = phone(FakeClock(elapsedMs = 3_600_000L))
        f.type("582")
        assertTrue(f.pinRecord().guard.charged)

        f.lock(LockCause.SCREEN_OFF)

        assertFalse("the charge of the entry that was given up is refunded (§6.3 A)", f.pinRecord().guard.charged)
        assertEquals(0, f.pad.digits)
        assertEquals(0, f.keys.hmacCalls.size)
        f.type(PIN) // the screen is on again: a whole new entry, with its own charge
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(1, f.keys.hmacCalls.size)
    }

    /** A fingerprint key whose prompt cannot be cancelled: its result arrives whatever the lock did meanwhile. */
    private class Unstoppable(private val inner: FingerprintKey) : FingerprintKey {
        val results = ArrayList<FingerprintResult>()

        override fun availability(): Int = inner.availability()

        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun probe(alias: String, nonce: ByteArray): Ks<Unit> = inner.probe(alias, nonce)

        override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult = inner.seal(alias, vk, use, launch)

        override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
            inner.open(alias, nonce, ct, use, launch).also { results += it }

        override fun cancel() = Unit
    }

    @Test
    fun aFingerprintResultThatArrivesAfterAScreenOffLock_isDropped_andItsKeyWiped() {
        val f = LockFixture()
        val unstoppable = Unstoppable(f.keys.fingerprint)
        f.fingerprintKey = unstoppable
        f.rebuild()
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        f.keys.holdPrompts = true

        runBlocking {
            val prompt = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.promptIfDue() }
            assertEquals(1, f.engine.core.outstandingLaunch)
            f.engine.lock(LockCause.SCREEN_OFF)
            assertEquals("the launch is ended with the visit (§9.2 rule 8)", 0, f.engine.core.outstandingLaunch)
            f.keys.answer(FakeKeys.Prompt.Touch) // the finger was on the sensor all the same
            prompt.join()
        }

        val result = unstoppable.results.single() as FingerprintResult.Ok
        assertTrue("VK of a dropped result is wiped", result.bytes.all { it == 0.toByte() })
        assertTrue(f.state is LockState.Locked)
        assertFalse(f.port.calls.any { it.startsWith("open") })
        assertNull(f.port.openVaultId)
    }

    @Test
    fun aMasterPasswordThatIsBeingCheckedWhenTheScreenGoesOff_countsAsRight_andOpensNothing() {
        val f = phone(FakeClock(elapsedMs = 3_600_000L), row = 3, total = 4)
        await { f.engine.useMasterPassword() }
        var outcome: MasterPasswordOutcome? = null
        runBlocking {
            // "Argon2id runs": the screen goes off while storage derives.
            f.port.beforeCall = { call ->
                if (call.startsWith("openWithPassword")) launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.SCREEN_OFF) }
            }
            outcome = f.engine.unlockWithMasterPassword(PASSWORD.toCharArray())
        }
        f.port.beforeCall = null

        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        assertTrue(f.state is LockState.Locked)
        assertNull("the vault that storage opened is dropped again", f.port.openVaultId)
        assertEquals("a right master password returns both PIN counts to zero all the same (§6.8 step 8)", PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertEquals(0, f.stateRecord().mpGuard.failures)
    }

    @Test
    fun aScreenOffLockOfTheOpenApp_locksItAsAnyLockDoes() {
        val f = phone(FakeClock(elapsedMs = 3_600_000L))
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)

        f.lock(LockCause.SCREEN_OFF)

        assertEquals(listOf("vaults", "open current", "lock", "vaults"), f.port.calls)
        assertNull(f.port.openVaultId)
        assertTrue(f.screen is LockScreen.Pad)
        assertFalse(f.engine.core.visit!!.place.hasEnded)
    }

    @Test
    fun anyOtherLock_whileTheLockScreenShows_changesNothing() {
        for (cause in LockCause.entries - LockCause.SCREEN_OFF) {
            val f = phone(FakeClock(elapsedMs = 3_600_000L))
            f.type("58")
            val visit = f.engine.core.visit

            f.lock(cause)

            assertSame("$cause", visit, f.engine.core.visit)
            assertFalse("$cause", visit!!.place.hasEnded)
            assertEquals("$cause", 2, f.pad.digits)
            assertTrue("$cause: the charge is still held", f.pinRecord().guard.charged)
            f.type("2913")
            assertTrue("$cause: the entry goes on", f.state is LockState.Unlocked)
        }
    }

    @Test
    fun theOnStopThatFollowsAScreenOffLock_endsTheVisitThatLockBegan_andOnStartBeginsTheNext() {
        val f = phone(FakeClock(elapsedMs = 3_600_000L))
        f.type("5")
        val first = f.engine.core.visit!!

        f.lock(LockCause.SCREEN_OFF)
        val second = f.engine.core.visit!!
        await { f.engine.appStopped() } // 700 ms later
        assertTrue(first.place.hasEnded && second.place.hasEnded)
        assertNull(f.engine.core.visit)

        f.lock(LockCause.SCREEN_OFF) // a screen-off that Android delivers late finds no visit to end
        assertNull(f.engine.core.visit)

        await { f.engine.appStarted() }
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
    }

    @Test
    fun onTheLockScreenOfAVaultPutAside_aScreenOffLockEndsItsVisit_andThatScreenStays() {
        val f = LockFixture()
        f.seedVault()
        f.plantState()
        val old: FakeVaultPort.FakeVault = f.port.seedAside(OTHER_VAULT, bytes("the other vault's own words"))
        f.plantPin(old, pin = "905127")
        f.plantState(OTHER_VAULT)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        f.type("9051")
        val visit = f.engine.core.visit!!

        f.lock(LockCause.SCREEN_OFF)

        assertTrue(visit.place.hasEnded)
        assertFalse("that vault's own charge is refunded", f.pinRecord(OTHER_VAULT).guard.charged)
        assertEquals(OTHER_VAULT, f.engine.core.target!!.vaultId)
        assertEquals(0, f.pad.digits)
        assertTrue(f.pad.aside != null)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }
}
