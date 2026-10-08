package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderCopies
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.ROOT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Typed master passwords and keys are wiped on every path of F8, F11 and F16b (lock.md §5.8, §4.2;
 * build plan 4.4): every array that holds a password's bytes, a KEK or a vault key, and that the
 * engine hands to a port or gets back from one, is all zeros once the flow has ended, whether it
 * ended as planned, failed, or was cut short. The ports are wrapped to see those arrays: the vault
 * port (passwords, VK2 from `newKey`, the keys handed to `open`), the KDF (the password in, the KEK
 * out), the PIN key (`KEK_pin`), the fingerprint key (the VK sealed and the VK a prompt gives) and the
 * AEAD (every wrap opened). The bytes kept for "Open my passwords from before the change" are looked
 * at where `OlderCopies` keeps them.
 */
class WipingTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    /** Every secret array the ports saw, by what it was. */
    private class Watch {
        private val seen = ArrayList<Pair<String, ByteArray>>()

        fun <T : ByteArray?> keep(what: String, bytes: T): T {
            if (bytes != null) synchronized(seen) { seen += what to bytes }
            return bytes
        }

        val count: Int get() = synchronized(seen) { seen.size }

        /** What was seen and is not all zeros now. */
        fun notWiped(): List<String> = synchronized(seen) { seen.filter { (_, b) -> b.any { it != 0.toByte() } }.map { it.first } }

        fun kinds(): Set<String> = synchronized(seen) { seen.map { it.first }.toSet() }
    }

    private val f = LockFixture()
    private val watch = Watch()

    private val port = object : VaultPort by f.port {
        override suspend fun openWithPassword(target: VaultRef, passwordBytes: ByteArray): PasswordOpen =
            f.port.openWithPassword(target, watch.keep("password to openWithPassword", passwordBytes))

        override suspend fun checkPassword(passwordBytes: ByteArray): Boolean? = f.port.checkPassword(watch.keep("password to checkPassword", passwordBytes))

        override suspend fun changePassword(first: ByteArray, again: ByteArray, vkId: String, vk: ByteArray): VaultWrite =
            f.port.changePassword(watch.keep("first typing", first), watch.keep("second typing", again), vkId, watch.keep("VK2 to changePassword", vk))

        override suspend fun replaceVault(newVaultId: String, passwordBytes: ByteArray): VaultWrite =
            f.port.replaceVault(newVaultId, watch.keep("password to replaceVault", passwordBytes))

        override suspend fun open(target: VaultRef, keys: Map<String, ByteArray>): VaultOpen {
            keys.values.forEach { watch.keep("VK to open", it) }
            return f.port.open(target, keys)
        }

        override suspend fun newKey(): Pair<String, ByteArray> = f.port.newKey().also { watch.keep("VK2 from newKey", it.second) }
    }

    private val kdf = object : MasterKdf {
        override suspend fun kek(passwordBytes: ByteArray, salt: ByteArray): ByteArray =
            watch.keep("KEK", f.kdf.kek(watch.keep("password to the KDF", passwordBytes), salt))
    }

    private val pinKey = object : PinKey {
        override fun create(alias: String): KeyCreate = f.keys.pin.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> =
            f.keys.pin.hmac(alias, message).also { if (it is Ks.Ok) watch.keep("KEK_pin", it.value) }
    }

    private val fingerprintKey = object : FingerprintKey by f.keys.fingerprint {
        override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
            f.keys.fingerprint.seal(alias, watch.keep("VK to seal", vk), use, launch)

        override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
            f.keys.fingerprint.open(alias, nonce, ct, use, launch).also { if (it is FingerprintResult.Ok) watch.keep("VK from a prompt", it.bytes) }
    }

    private val aead = object : Aead by f.aead {
        override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray? =
            watch.keep("an opened wrap", f.aead.open(key, nonce, ciphertext, aad))
    }

    private var engine = newEngine()

    private fun newEngine() =
        LockEngine(f.files, ROOT, f.clock, FakeRandom(77), pinKey, fingerprintKey, f.keys.device, port, kdf, aead, Dispatchers.Unconfined)

    private val state: LockState get() = engine.state.value

    private fun start() {
        await { engine.start() }
        await { engine.appStarted() }
    }

    private fun type(pin: String) = pin.forEach { digit -> await { engine.digit(digit - '0') } }

    private fun unlockWith(password: String) = await { engine.unlockWithMasterPassword(password.toCharArray()) }

    private fun lock(cause: LockCause = LockCause.NO_TOUCH) = await { engine.lock(cause) }

    private fun proof() = (await { engine.checkMasterPassword(PASSWORD.toCharArray()) } as MasterPasswordOutcome.Right).proof

    private fun change(pin: String?) = await {
        engine.changeMasterPassword(proof(), NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray(), pin?.let(::digits))
    }

    private fun phone(fingerprint: Boolean = true, pin: Boolean = true) {
        val vault = f.seedVault(generations = 2)
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        start()
        if (pin) type(PIN) else await { engine.promptIfDue() }
        assertTrue(state is LockState.Unlocked)
    }

    private fun assertWiped(where: String, mustHaveSeen: Set<String>) {
        assertEquals("$where: not wiped", emptyList<String>(), watch.notWiped())
        assertTrue("$where: the watch saw ${watch.kinds()}", watch.kinds().containsAll(mustHaveSeen))
    }

    // ---------------------------------------------------------------------------------------------
    // F8

    @Test
    fun f8_theWholeChange_wipesThePasswordsVk2KekPinAndEveryKeyItOpened() {
        phone()
        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), change(PIN))
        lock()
        assertWiped(
            "F8",
            setOf("password to checkPassword", "first typing", "second typing", "VK2 from newKey", "VK2 to changePassword", "KEK_pin", "an opened wrap", "VK to seal"),
        )
    }

    @Test
    fun f8_everyWayItFails_wipesTheSame() {
        phone()
        // Each way, with what the ports must have seen on it.
        val ways: List<Triple<String, () -> Unit, Set<String>>> = listOf(
            Triple("a wrong PIN", { }, setOf("KEK_pin")),
            Triple("nothing changed", { f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED) }, setOf("first typing", "VK2 to changePassword")),
            Triple("quarantined", { f.port.failWrites(FakeVaultPort.WriteFault.QUARANTINED) }, setOf("second typing")),
            Triple("not enough memory", { f.port.noMemory = true }, setOf("first typing")),
            Triple("the mark not written", { f.files.fail(FakeRecordFiles.Op.REPLACE, 1) { it.endsWith("/state.rec") } }, setOf("VK2 from newKey")),
            Triple("saving may not have finished", { f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR) }, setOf("VK2 to changePassword")),
        )
        for ((way, arrange, seen) in ways) {
            val proof = proof() // step 1, before anything is made to fail
            arrange()
            val pin = if (way == "a wrong PIN") "000000" else PIN
            val outcome = await { engine.changeMasterPassword(proof, NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray(), digits(pin)) }
            f.port.noMemory = false
            assertTrue("$way: $outcome", outcome !is ChangeOutcome.Changed)
            assertWiped(way, seen)
        }
    }

    @Test
    fun f8_aLockWhileTheFingerprintPromptIsOpen_wipesTheCopyOfVk2() {
        phone()
        f.keys.scriptPrompt(io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys.Prompt.Close(10))
        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), change(PIN))
        assertWiped("closed", setOf("VK to seal"))
    }

    // ---------------------------------------------------------------------------------------------
    // F11

    @Test
    fun f11_proofRequestAndFinishNow_wipeTheirKeysAndPasswords() {
        phone()
        assertEquals(ProofOutcome.Proven(io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut.PIN), await { engine.proveForNewMasterPassword(digits(PIN)) })
        assertTrue(await { engine.requestNewMasterPassword(NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()) } is RequestOutcome.Requested)
        assertWiped("asking", setOf("KEK_pin", "an opened wrap", "password to the KDF", "KEK"))
        f.clock.advance(24 * HOUR)
        await { engine.refresh() }
        assertEquals(MasterPasswordOutcome.Wrong, await { engine.finishPending("not it".toCharArray()) })
        assertTrue(await { engine.finishPending(NEW_PASSWORD.toCharArray()) } is MasterPasswordOutcome.Right)
        lock()
        assertWiped("finishing", setOf("password to replaceVault", "password to checkPassword"))
    }

    @Test
    fun f11_finishingOnTheLockScreen_andEachWayItFails_wipeTheSame() {
        phone()
        await { engine.proveForNewMasterPassword(digits(PIN)) }
        await { engine.requestNewMasterPassword(NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()) }
        f.clock.advance(24 * HOUR)
        lock()
        await { engine.useMasterPassword() }
        f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
        assertEquals(MasterPasswordOutcome.NotFinished, unlockWith(NEW_PASSWORD))
        f.port.noMemory = true
        assertEquals(MasterPasswordOutcome.NoMemory, unlockWith(NEW_PASSWORD))
        f.port.noMemory = false
        assertWiped("failed", setOf("VK to open", "KEK"))
        assertTrue(unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        lock()
        assertWiped("finished", setOf("password to replaceVault"))
    }

    @Test
    fun f11_theFingerprintThatConfirms_isWiped() {
        phone(pin = false)
        assertEquals(ProofOutcome.Proven(io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut.FINGERPRINT), await { engine.proveForNewMasterPassword(null) })
        f.keys.scriptPrompt(io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys.Prompt.Close(13))
        await { engine.proveForNewMasterPassword(null) }
        lock()
        assertWiped("confirmed", setOf("VK from a prompt"))
    }

    // ---------------------------------------------------------------------------------------------
    // F16b

    /** The bytes `OlderCopies` keeps for "Open my passwords from before the change", or null. */
    private fun keptTyped(): ByteArray? {
        val kept = OlderCopies::class.java.getDeclaredField("kept").apply { isAccessible = true }.get(engine.core.olderCopies) ?: return null
        return kept.javaClass.getDeclaredField("typed").apply { isAccessible = true }.get(kept) as ByteArray?
    }

    private fun afterAChange() {
        val vault = f.seedVault(generations = 2)
        val vkId1 = vault.newestVkId
        val vkId2 = vault.rekey(bytes(NEW_PASSWORD))
        f.plantPin(vault, vkIds = listOf(vkId1, vkId2))
        f.plantState(mpChange = StateRecord.MpChange(vkId2, f.clock.wallMs()), maxGeneration = StateRecord.MaxGeneration(3, f.clock.wallMs()))
        start()
        await { engine.useMasterPassword() }
        assertTrue(unlockWith(PASSWORD) is MasterPasswordOutcome.OlderPassword)
    }

    @Test
    fun f16b_theBytesKeptForOpenMyPasswordsFromBefore_areWipedByEveryAnswerAndByTheEndOfTheVisit() {
        afterAChange()
        val kept = keptTyped()!!
        assertTrue(kept.contentEquals(bytes(PASSWORD)))
        await { engine.olderPasswordAnswer(OlderPasswordAnswer.TRY_AGAIN) }
        assertTrue("TRY_AGAIN", kept.all { it == 0.toByte() })

        assertTrue(unlockWith(PASSWORD) is MasterPasswordOutcome.OlderPassword)
        val again = keptTyped()!!
        await { engine.appStopped() }
        assertTrue("the end of the visit", again.all { it == 0.toByte() })
        await { engine.appStarted() }

        await { engine.useMasterPassword() }
        assertTrue(unlockWith(PASSWORD) is MasterPasswordOutcome.OlderPassword)
        val third = keptTyped()!!
        lock(LockCause.SCREEN_OFF)
        assertTrue("a screen-off lock", third.all { it == 0.toByte() })

        await { engine.useMasterPassword() }
        assertTrue(unlockWith(PASSWORD) is MasterPasswordOutcome.OlderPassword)
        val fourth = keptTyped()!!
        await { engine.olderPasswordAnswer(OlderPasswordAnswer.OPEN_FROM_BEFORE) }
        assertTrue(state is LockState.Unlocked)
        assertTrue("OPEN_FROM_BEFORE", fourth.all { it == 0.toByte() })
        assertEquals(null, keptTyped())
        lock()
        assertWiped("F16b", setOf("password to openWithPassword"))
    }

    @Test
    fun f16b_afterAReplacement_theQuestionsBytes_andTheVaultOpenedInPlace_goWithEveryAnswer() {
        phone()
        await { engine.proveForNewMasterPassword(digits(PIN)) }
        await { engine.requestNewMasterPassword(NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()) }
        f.clock.advance(24 * HOUR)
        assertTrue(await { engine.finishPending(NEW_PASSWORD.toCharArray()) } is MasterPasswordOutcome.Right)
        await { engine.itWasntMe(LockFixture.VAULT) }
        assertEquals(MasterPasswordOutcome.TooLate, unlockWith(PASSWORD))
        val question = engine.core.visit!!.olderSet!!
        val proofBytes = question.proofBytes!!
        await { engine.olderPasswordAnswer(OlderPasswordAnswer.KEEP_NEWER_SET) }
        assertTrue("Cancel", proofBytes.all { it == 0.toByte() })
        assertEquals(null, f.port.openAsideId)

        assertEquals(MasterPasswordOutcome.TooLate, unlockWith(PASSWORD))
        val second = engine.core.visit!!.olderSet!!.proofBytes!!
        await { engine.olderPasswordAnswer(OlderPasswordAnswer.OPEN_OLDER_SET) }
        assertTrue(state is LockState.Unlocked)
        assertTrue("Open my passwords from 7 Oct", second.all { it == 0.toByte() })
        lock()
        assertNotNull(watch.count)
        assertWiped("F16b after a replacement", setOf("password to openWithPassword", "password to replaceVault"))
        assertTrue(engine.state.value.let { it is LockState.Locked && it.screen !is LockScreen.OpenOlderSet })
    }
}
