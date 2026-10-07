package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * One vault with a PIN, for the tests of [PinGuard]: the ids, keys and PIN of the vector of lock.md
 * §4.3 (vault …0001, vk …0002, slot …0003, `K_pin` 42 × 32, VK a0 … bf, nonce 10 … 1b, PIN 582913),
 * so that every test also runs the vector through the guard. The clock moves on by itself through
 * every wait ([FakeClock.autoAdvance]).
 */
internal class PinFixture(val clock: FakeClock = FakeClock(elapsedMs = 3_600_000L)) {
    val aead = CryptoFactory.aead()
    val keys = FakeKeys(FakeRandom(5), now = clock::elapsedMs)
    val store = FakeRecordStore<PinRecord>(now = clock::elapsedMs)

    init {
        keys.canary = true
        keys.putPinKey(PinWrap.alias(SLOT), ByteArray(32) { 0x42 })
    }

    /** The slot of the vector: one wrap of VK for [VK_ID] under the PIN [RIGHT]. */
    val slot: ShortcutSlot = ShortcutSlot(SLOT, clock.wallMs(), listOf(wrap(RIGHT, VK_ID, VK)))

    /** The wrap of [vk] for [vkId] under [pin] in the slot [slotId], made by hand as F6 step 5 does. */
    fun wrap(pin: ByteArray, vkId: String, vk: ByteArray, slotId: String = SLOT, nonce: ByteArray = NONCE): KeyWrap {
        val kek = keys.hmacOf(PinWrap.alias(slotId), PinWrap.message(VAULT, slotId, pin))
        return PinWrap.seal(aead, kek, nonce, vk, VAULT, vkId, slotId)
    }

    /** `pin.rec` with [slot] and the given counts. */
    fun record(
        row: Int = 0,
        total: Int = 0,
        charged: Boolean = false,
        unseen: PinRecord.Unseen = PinRecord.Unseen(0, null, false),
        slot: ShortcutSlot? = this.slot,
    ): PinRecord = PinRecord(VAULT, slot, PinRecord.Guard(charged, row, total), off = null, unseen = unseen)

    /** A guard over [store]; [startUp] it first when the test is about an entry. */
    fun guard(): PinGuard = PinGuard(VAULT, store, clock, keys.pin, keys.device, aead, FakeRandom(9))

    /** A guard over a record with the given counts, started up. */
    fun started(row: Int = 0, total: Int = 0, unseen: PinRecord.Unseen = PinRecord.Unseen(0, null, false)): PinGuard {
        store.set(record(row, total, unseen = unseen))
        return guard().also { it.startUp() }
    }

    companion object {
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val VK_ID = "00000000-0000-4000-8000-000000000002"
        const val SLOT = "00000000-0000-4000-8000-000000000003"
        val VK: ByteArray get() = FakeRandom.sequence(0xa0, 32)
        val NONCE: ByteArray get() = FakeRandom.sequence(0x10, 12)
        val RIGHT: ByteArray get() = pin("582913")
        val WRONG: ByteArray get() = pin("582914")

        fun pin(digits: String): ByteArray = digits.toByteArray(Charsets.US_ASCII)
    }
}

/** Charges at [at], then checks [pin] there: one whole PIN entry. */
internal fun PinGuard.enter(pin: ByteArray, at: Place, keptVkIds: Set<String>? = setOf(PinFixture.VK_ID)): PinOutcome {
    check(charge(at) == PinCharge.CHARGED) { "the charge was refused" }
    return runNow { check(pin, keptVkIds) }
}

/**
 * Runs [block] to its end on this thread. It must not suspend: with a [FakeClock] that moves on by
 * itself, and the fakes of :testing, nothing in the guards does.
 */
internal fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "it suspended" }.getOrThrow()
}
