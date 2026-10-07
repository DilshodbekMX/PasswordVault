package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.io.IOException
import java.security.MessageDigest

/**
 * Where a PIN or a master password is typed (lock.md §6.3 "Where a PIN can be typed", §6.8): on a
 * lock screen during a **visit**, or in a **prompt** inside the open app (F6 step 2, F8 step 3, F11
 * step 3). Each has a number, made by the lock engine, which keeps the visit and prompt bookkeeping.
 *
 * A visit starts when a lock screen becomes visible in a locked app and ends when the app is
 * unlocked or stops being visible; a rotation or a language change does not end it (lock.md §11).
 * A prompt ends when it closes, or when the app locks or stops. When either ends, the engine calls
 * [end] at once, from any thread and without the lock mutex, so that a check running under the mutex
 * sees it ([hasEnded], I11); then, under the mutex, it tells the guards (`PinGuard.placeEnded`,
 * `MasterPasswordGuard.placeEnded`), which refund a charge held there and forget its wrong tries.
 *
 * Two places are the same only when they are the same object.
 */
class Place private constructor(
    /** True on a lock screen, false in a prompt inside the open app. */
    val isVisit: Boolean,
    /** The engine's number of this visit or prompt. */
    val number: Long,
) {
    @Volatile private var ended = false

    /** Whether the visit or prompt has ended: a result that belongs to it opens nothing (I11). */
    val hasEnded: Boolean get() = ended

    /** The visit or prompt has ended. Safe from any thread; calling it again does nothing. */
    fun end() {
        ended = true
    }

    override fun toString(): String = (if (isVisit) "visit " else "prompt ") + number + (if (ended) " (ended)" else "")

    companion object {
        /** A lock-screen visit numbered [number]. */
        fun visit(number: Long): Place = Place(true, number)

        /** A prompt inside the open app numbered [number]. */
        fun prompt(number: Long): Place = Place(false, number)
    }
}

/**
 * The byte-level definitions of lock.md §4.3 for the PIN, and the Keystore alias of a PIN slot
 * (§5.4, §5.7). `PinGuard` checks a PIN with them; setting a PIN (F6 steps 5 and 6) and keeping it
 * across a master-password change (F8 step 5) make wraps with them. The vector of §4.3 is test T2.
 *
 * ```
 * pin_msg = ASCII("pvlock-v1:pin-kek")  ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(slot_id) ‖ PIN               (56 bytes)
 * KEK_pin = HMAC-SHA256(K_pin, pin_msg)                                                               (32 bytes)
 * AAD_pin = ASCII("pvlock-v1:pin-wrap") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(vk_id) ‖ uuid16(slot_id)   (67 bytes)
 * wrap    = AES-256-GCM(KEK_pin, 12-byte nonce, VK, AAD_pin)                                          (48 bytes)
 * ```
 *
 * The PIN is six ASCII digits, `0x30` to `0x39`, as the pad produces them (§4.2). Every function
 * that takes ids requires lower-case UUIDs of version 4 (`Uuid.uuid16`); a message holds the PIN, so
 * the caller wipes it after use.
 */
object PinWrap {
    /** The Keystore alias of a PIN slot is this prefix and the slot id; it is not stored (§5.4). */
    const val ALIAS_PREFIX = "pv_pin_"

    /** A PIN has six digits (§7.1). */
    const val PIN_SIZE = 6

    /** The size of `pin_msg`. */
    const val MESSAGE_SIZE = 56

    /** The size of `AAD_pin`. */
    const val AAD_SIZE = 67

    /** The nonce of a wrap. */
    const val NONCE_SIZE = 12

    /** A wrap: 32 bytes of VK and the 16-byte tag. */
    const val WRAP_SIZE = 48

    private val KEK_LABEL = "pvlock-v1:pin-kek".toByteArray(Charsets.US_ASCII)
    private val WRAP_LABEL = "pvlock-v1:pin-wrap".toByteArray(Charsets.US_ASCII)

    /** `"pv_pin_" + slotId`. */
    fun alias(slotId: String): String = ALIAS_PREFIX + slotId

    /** Whether [pin] is six ASCII digits. */
    fun isPin(pin: ByteArray): Boolean = pin.size == PIN_SIZE && pin.all { it in DIGIT_0..DIGIT_9 }

    /** `pin_msg` for [pin] on the slot [slotId] of vault [vaultId]. */
    fun message(vaultId: String, slotId: String, pin: ByteArray): ByteArray {
        require(isPin(pin)) { "a PIN is six ASCII digits" }
        return concat(KEK_LABEL, ZERO, Uuid.uuid16(vaultId), Uuid.uuid16(slotId), pin)
    }

    /** `AAD_pin` of the wrap of the vault key [vkId] in the slot [slotId] of vault [vaultId]. */
    fun aad(vaultId: String, vkId: String, slotId: String): ByteArray =
        concat(WRAP_LABEL, ZERO, Uuid.uuid16(vaultId), Uuid.uuid16(vkId), Uuid.uuid16(slotId))

    /** The wrap of [vk] (32 bytes) under [kek] with [nonce] (12 bytes, fresh from `SecureRandom`), for the key [vkId]. */
    fun seal(aead: Aead, kek: ByteArray, nonce: ByteArray, vk: ByteArray, vaultId: String, vkId: String, slotId: String): KeyWrap {
        require(vk.size == 32) { "VK has 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "a wrap's nonce has 12 bytes" }
        return KeyWrap(vkId, nonce.copyOf(), aead.seal(kek, nonce, vk, aad(vaultId, vkId, slotId)))
    }

    /** VK from [wrap] in the slot [slotId] of vault [vaultId], or null when its tag fails under [kek]. */
    fun open(aead: Aead, kek: ByteArray, wrap: KeyWrap, vaultId: String, slotId: String): ByteArray? =
        try {
            aead.open(kek, wrap.nonce, wrap.ct, aad(vaultId, wrap.vkId, slotId))
        } catch (e: IllegalArgumentException) {
            null // a nonce or key of the wrong size: this wrap can never open
        }

    private const val DIGIT_0: Byte = 0x30
    private const val DIGIT_9: Byte = 0x39
    private val ZERO = byteArrayOf(0)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }
}

/** What the start-up step of lock.md §6.3 S found and did; `PinGuard.startUp` returns it. */
data class PinStartUp(
    /** What reading `pin.rec` gave: the five results of lock.md §5.3. Only OK offers a PIN. */
    val read: RecordRead<PinRecord>,
    /** A try was paid for and never resolved, and was counted now (S.2): the pad says it was interrupted. */
    val interrupted: Boolean,
    /** That count reached a limit, and the PIN was turned off in the same write (§6.5). */
    val limit: Boolean,
    /** The start-up write failed: no PIN in this process, with the "storage is full" reason (S.3). */
    val storageFull: Boolean,
)

/** What the first digit of an entry came to (lock.md §6.3 C); `PinGuard.charge` returns it. */
enum class PinCharge {
    /** The charge is written and held: digits may be typed, and the sixth one is checked. */
    CHARGED,

    /** The charge could not be written: the dots are cleared and the pad says that storage is full (C.3). Nothing was counted. */
    STORAGE_FULL,

    /** No PIN can be typed here now: none is usable, a limit is reached, a charge is held, or the place has ended (C.1). */
    NOT_USABLE,
}

/**
 * The attempt counter of the PIN of one vault: lock.md §6 (the protocol of §6.3 with §6.4 to §6.6
 * and §6.10), over that vault's `pin.rec`.
 *
 * **What it holds.** The record it means to have on disk ([record]), which every write stores
 * whole, so that a write that failed is made good by the next one that succeeds; and what lock.md
 * §5.8 keeps in memory only: the charge and its time, the wrong PINs of each visit (for forgiving),
 * the wrong PINs already checked (as `SHA-256(nonce16 ‖ PIN)`, never the PIN, §6.6), and the place
 * where the Keystore could not check the PIN (F15c). Nothing else of the PIN is kept: a PIN passed
 * in is the caller's, who wipes it.
 *
 * **Who calls it.** The lock engine, one `PinGuard` per vault for the life of the process (the
 * current vault and a vault put aside each have their own record and their own five tries, F17
 * step 7), always under the lock mutex (architecture.md §5.2), with I/O on `Dispatchers.IO`. It is
 * not thread-safe by itself; only [Place.end] may happen meanwhile. [check] suspends for the age of
 * the charge with the mutex held: "the pad ignores input from here until the result is shown".
 *
 * **The order of a PIN entry.** [startUp] once, at process start (§5.11 step 5) or when a vault put
 * aside shows its own lock screen; then for each entry [charge] at the first digit, and either
 * [giveUp] (the digits are deleted back to none, "Forgot PIN?", the fingerprint, a closed prompt) or
 * [check] at the sixth; after a right PIN that opened the vault, [flush] as the next job under the
 * mutex (§6.3 E.6, decision 27); [placeEnded] when the visit or prompt ends.
 *
 * **What else changes the record** goes through it too, so that the copy in memory stays the one on
 * disk: a correct master password ([masterPasswordCorrect], §6.4, §6.8 step 5), a new PIN
 * ([setPin], F6 step 7), the wraps kept at an unlock ([keepWrapsFor], §5.4), the wrap of a
 * master-password change ([changeWraps], F8 step 5), a slot removed for another reason
 * ([removeSlot]: F15b, §5.11 step 3, F16b step 5), and the notice of wrong tries
 * ([unseenShown], §13.4 item 4).
 *
 * **Records it never writes:** a `pin.rec` that read DAMAGED, NEWER or UNREADABLE is not used and
 * not rewritten (lock.md §5.3); only a new PIN ([setPin], "set it up again") replaces a DAMAGED or
 * UNREADABLE one, and nothing replaces a NEWER one.
 *
 * It deletes a PIN key only where lock.md says "at once": when its slot is removed (at a limit,
 * GONE, a slot left without wraps, [removeSlot]). A key whose slot is *replaced* by [setPin] is left
 * to the sweep (§5.7).
 *
 * @param vaultId the vault whose `pin.rec` this is; [store] reads and writes that record.
 * @param random makes the nonce of §6.6 once, when the guard is made.
 */
class PinGuard(
    val vaultId: String,
    private val store: RecordStore<PinRecord>,
    private val clock: Clock,
    private val pinKey: PinKey,
    private val deviceKeys: DeviceKeys,
    private val aead: Aead,
    random: RandomSource,
) {
    private class Charge(val place: Place, val atElapsedMs: Long)

    private class WrongAt(val place: Place, val atElapsedMs: Long)

    private val nonce: ByteArray = random.bytes(NONCE_SIZE)
    private var read: RecordRead<PinRecord> = RecordRead.Missing
    private var rec: PinRecord? = null
    private var startUpFailed = false
    private var charge: Charge? = null
    private val visitWrong = ArrayList<WrongAt>()
    private val checkedWrong = HashSet<String>()
    private var hiddenOn: Place? = null
    private var rightUnwritten = false

    init {
        require(Uuid.isValid(vaultId)) { "not a lower-case UUID of version 4" }
    }

    /** The record this guard means to have on disk; null while there is none it may use (MISSING, DAMAGED, NEWER, UNREADABLE). */
    val record: PinRecord? get() = rec

    /** What reading `pin.rec` gave at [startUp], or the record a later [setPin] wrote. */
    val lastRead: RecordRead<PinRecord> get() = read

    /** The start-up write of §6.3 S failed: no PIN in this process (S.3), until a new PIN is set. */
    val storageFull: Boolean get() = startUpFailed

    /** Where a charge is held now, or null (§5.8: the time of the charge is in memory only). */
    val chargedAt: Place? get() = charge?.place

    /** "N tries left": min(5 − row, 10 − total) of [record]; 0 without one (§6.4, §13.3). */
    val triesLeft: Int get() = rec?.let { left(it.guard) } ?: 0

    /** Whether the Keystore failed to check the PIN at [place] (TEMPORARY): it is not offered again there (F15c, §13.1). */
    fun cannotCheckOn(place: Place): Boolean = hiddenOn === place

    /**
     * Whether a PIN entry may start at [at] (§6.3 C.1, and the parts of "the PIN is usable" in §13.1
     * that belong to the counter): `pin.rec` read OK (or a PIN was set since), it has a slot, no
     * limit is reached, the start-up step succeeded, no charge is held, the Keystore has not failed
     * at [at], and [at] has not ended. Whether the slot has a wrap for the newest generation is the
     * entry policy's part.
     */
    fun canCharge(at: Place): Boolean {
        val r = rec ?: return false
        return r.slot != null && !atLimit(r.guard) && !startUpFailed && charge == null && hiddenOn !== at && !at.hasEnded
    }

    /**
     * lock.md §6.3 S, step 5 of §5.11: reads `pin.rec`; a try that was paid for and never resolved
     * (`charged`) is counted now, as interrupted (`row + 1`, `total + 1`, `unseen.count + 1`,
     * `unseen.interrupted`), and if that reaches a limit the PIN is turned off in the same write
     * (§6.5), its key deleted. If that write fails, no PIN is offered in this process (S.3); the
     * charge stays on disk and is counted at a later start.
     *
     * Everything this guard held in memory is forgotten first: it is a new process, or a vault put
     * aside whose lock screen opens now.
     */
    fun startUp(): PinStartUp {
        forgetMemory()
        startUpFailed = false
        val result = try {
            store.read()
        } catch (e: IOException) {
            RecordRead.Unreadable
        }
        read = result
        val found = (result as? RecordRead.Ok)?.record
        rec = found
        if (found == null || !found.guard.charged) return PinStartUp(result, interrupted = false, limit = false, storageFull = false)

        val guard = counted(found.guard)
        val unseen = found.unseen.copy(count = found.unseen.count + 1, interrupted = true)
        val slot = found.slot
        val turnOff = slot != null && atLimit(guard)
        val next = if (turnOff) {
            found.copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.LIMIT, clock.wallMs()), guard = guard, unseen = unseen)
        } else {
            found.copy(guard = guard, unseen = unseen)
        }
        val written = keep(next)
        startUpFailed = !written
        if (turnOff) deviceKeys.delete(PinWrap.alias(slot!!.id))
        return PinStartUp(result, interrupted = true, limit = turnOff, storageFull = !written)
    }

    /**
     * lock.md §6.3 C, the first digit of an entry at [at]: writes `charged = true` and, once the
     * write has returned, remembers the time (`elapsedRealtime()`). If the write fails, nothing is
     * held, counted or checked ([PinCharge.STORAGE_FULL]).
     */
    fun charge(at: Place): PinCharge {
        if (!canCharge(at)) return PinCharge.NOT_USABLE
        val r = rec!!
        val next = r.copy(guard = r.guard.copy(charged = true))
        if (!write(next)) return PinCharge.STORAGE_FULL
        rec = next
        charge = Charge(at, clock.elapsedMs())
        return PinCharge.CHARGED
    }

    /**
     * lock.md §6.3 A: the entry is given up before it is checked (the digits deleted back to none,
     * "Forgot PIN? Use master password", the fingerprint key or a fingerprint success, a prompt
     * closed). The held charge is refunded: `charged = false` and no count changes. If that write
     * fails, the try stays counted on disk, which is the safe direction. Nothing happens without a
     * charge.
     */
    fun giveUp() {
        if (charge != null) refund()
    }

    /**
     * lock.md §6.3 E, the sixth digit: checks [pin] (six ASCII digits) against the slot, as the code
     * sketch of §6.3 does, and returns what it came to. The record is written, or queued for [flush],
     * before it returns.
     *
     * 1. The same wrong PIN as before (§6.6): refunded, not checked ([PinOutcome.SameAsBefore]).
     * 2. Waits until the charge is [CHARGE_AGE_MS] old (I4). If the charge was given back meanwhile,
     *    nothing is checked ([PinOutcome.NotCharged]).
     * 3. `KEK_pin` from the Keystore. GONE: refunded, the slot removed with `off = key_gone` and its
     *    key deleted ([PinOutcome.KeyGone], F15b). TEMPORARY: refunded, the PIN not offered again at
     *    this place ([PinOutcome.CannotCheck], F15c).
     * 4. Every wrap is opened with `KEK_pin`; `KEK_pin` is wiped.
     * 5. None opens: wrong. `row + 1`, `total + 1`, `unseen.count + 1` and `unseen.last_at`;
     *    [PinOutcome.Wrong] with the tries left, or at a limit the PIN turned off and its key deleted
     *    ([PinOutcome.Limit], §6.5).
     * 6. One opens: right. On a lock screen it forgives this visit's wrong PINs of the last
     *    [FORGIVE_WINDOW_MS], at most [FORGIVE_MAX]; in a prompt nothing. `row = 0`, `total` and
     *    `unseen.count` less the forgiven ones, and the wraps trimmed by §5.4: one whose `vk_id` is
     *    not in [keptVkIds] is dropped, and so is one whose tag failed. If the place has ended
     *    meanwhile, the record is written, the keys wiped and nothing opens ([PinOutcome.TooLate],
     *    I11); otherwise [PinOutcome.Right] with the VK of every wrap that opened, by `vk_id` (the
     *    receiver wipes them), and the record waits for [flush].
     *
     * A wrong PIN, a limit and a right PIN count the same whether or not the place has ended
     * meanwhile (§6.7: switching the screen off during a check).
     *
     * @param keptVkIds the `vk_id`s that the kept generations of this vault carry (storage reports
     *   them, `CurrentVault.Present.keptVkIds`); null when they are not known, and then no wrap that
     *   opened is dropped.
     * @param useKek for F8 step 3 only: after a right PIN at a place that has not ended, it receives
     *   a copy of `KEK_pin`, which it keeps until F8 step 5 and then wipes.
     */
    suspend fun check(pin: ByteArray, keptVkIds: Set<String>?, useKek: ((ByteArray) -> Unit)? = null): PinOutcome {
        require(PinWrap.isPin(pin)) { "a PIN is six ASCII digits" }
        val held = charge ?: return PinOutcome.NotCharged
        val place = held.place
        val tag = tagOf(pin)
        if (tag in checkedWrong) {
            refund()
            return PinOutcome.SameAsBefore
        }

        clock.delayUntilElapsed(held.atElapsedMs + CHARGE_AGE_MS)
        if (charge !== held) return PinOutcome.NotCharged // given back while waiting: no durable charge any more
        val r = rec
        val slot = r?.slot
        if (r == null || slot == null) {
            charge = null
            return PinOutcome.NotCharged
        }

        val alias = PinWrap.alias(slot.id)
        val message = PinWrap.message(vaultId, slot.id, pin)
        val answer = try {
            pinKey.hmac(alias, message)
        } catch (e: RuntimeException) {
            Ks.Temporary(e) // lock.md §14.3: Mac.doFinal, any exception
        } finally {
            Bytes.wipe(message)
        }
        val kek = when (answer) {
            is Ks.Ok -> answer.value
            is Ks.Gone -> {
                charge = null
                keep(r.copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.KEY_GONE, clock.wallMs()), guard = r.guard.copy(charged = false)))
                deviceKeys.delete(alias)
                return PinOutcome.KeyGone
            }
            is Ks.Temporary -> {
                refund()
                hiddenOn = place
                return PinOutcome.CannotCheck
            }
        }
        if (kek.size != KEK_SIZE) { // not an HMAC-SHA256: the Keystore did not give a result (§14.3)
            Bytes.wipe(kek)
            refund()
            hiddenOn = place
            return PinOutcome.CannotCheck
        }

        val opened = LinkedHashMap<String, ByteArray>()
        for (wrap in slot.wraps) {
            PinWrap.open(aead, kek, wrap, vaultId, slot.id)?.let { opened[wrap.vkId] = it }
        }
        charge = null
        val nowElapsed = clock.elapsedMs()
        val nowWall = clock.wallMs()

        if (opened.isEmpty()) {
            Bytes.wipe(kek)
            val guard = counted(r.guard)
            val unseen = r.unseen.copy(count = r.unseen.count + 1, lastAtMs = nowWall)
            checkedWrong += tag
            if (place.isVisit) visitWrong += WrongAt(place, nowElapsed)
            if (atLimit(guard)) {
                keep(r.copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.LIMIT, nowWall), guard = guard, unseen = unseen))
                deviceKeys.delete(alias)
                return PinOutcome.Limit
            }
            keep(r.copy(guard = guard, unseen = unseen))
            return PinOutcome.Wrong(left(guard))
        }

        val forgiven = if (place.isVisit) {
            minOf(FORGIVE_MAX, visitWrong.count { it.place === place && nowElapsed - it.atElapsedMs <= FORGIVE_WINDOW_MS })
        } else {
            0
        }
        val guard = PinRecord.Guard(charged = false, row = 0, total = maxOf(0, r.guard.total - forgiven))
        val unseen = r.unseen.copy(count = maxOf(0, r.unseen.count - forgiven))
        val kept = slot.wraps.filter { it.vkId in opened && (keptVkIds == null || it.vkId in keptVkIds) }
        val next = if (kept.isEmpty()) {
            r.copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.MASTER_CHANGED, nowWall), guard = guard, unseen = unseen)
        } else {
            r.copy(slot = slot.copy(wraps = kept), guard = guard, unseen = unseen)
        }
        visitWrong.clear()
        checkedWrong.clear()
        if (kept.isEmpty()) deviceKeys.delete(alias) // a slot left without a wrap is removed (§5.4)

        if (place.hasEnded) {
            keep(next)
            Bytes.wipe(kek)
            opened.values.forEach { Bytes.wipe(it) }
            return PinOutcome.TooLate
        }
        rec = next
        rightUnwritten = true
        try {
            useKek?.invoke(kek.copyOf())
        } finally {
            Bytes.wipe(kek)
        }
        return PinOutcome.Right(opened)
    }

    /**
     * Writes the record of a right PIN (lock.md §6.3 E.6): the list is shown as soon as the vault is
     * open, and this runs as the next job under the lock mutex, so that no other step overtakes it.
     * If it fails, or the process dies first, the charge on disk is counted once at the next start
     * (§6.10). Returns whether the record is on disk; true when nothing was waiting.
     */
    fun flush(): Boolean {
        if (!rightUnwritten) return true
        val r = rec ?: return true
        return keep(r)
    }

    /**
     * The visit or prompt [place] has ended (lock.md §6.3): marks it ended, refunds a charge held
     * there (A), and forgets its wrong PINs and a Keystore failure there.
     */
    fun placeEnded(place: Place) {
        place.end()
        if (charge?.place === place) refund()
        visitWrong.removeAll { it.place === place }
        if (hiddenOn === place) hiddenOn = null
    }

    /**
     * A correct master password, anywhere in the app (lock.md §6.4, §6.8 step 5): `charged = false`,
     * `row = 0`, `total = 0`; `unseen` is not touched (§13.4 item 4). The wrong PINs remembered in
     * memory are forgotten (§6.6). Best effort: a write that fails leaves the stricter counts on disk.
     */
    fun masterPasswordCorrect() {
        charge = null
        visitWrong.clear()
        checkedWrong.clear()
        val r = rec ?: return
        val zero = PinRecord.Guard(charged = false, row = 0, total = 0)
        if (r.guard == zero && !rightUnwritten) return
        keep(r.copy(guard = zero))
    }

    /**
     * A new PIN is set (lock.md F6 step 7): writes [slot] with `off = null` and the guard of §6.4,
     * `row = 0` and `total = 0` when the master password was typed in this flow, otherwise `total`
     * unchanged; `unseen` is kept. It must reach the disk: when the write fails the old record stays,
     * here and on disk, and it returns false ("Couldn't save the new PIN. Your old PIN still works.";
     * the flow deletes the new alias). The old key is left to the sweep (§5.7). A `pin.rec` that read
     * NEWER is never replaced: false.
     *
     * The slot must hold one or two wraps with different `vk_id`s (§5.4).
     */
    fun setPin(slot: ShortcutSlot, masterPasswordTyped: Boolean): Boolean {
        require(Uuid.isValid(slot.id)) { "not a lower-case UUID of version 4" }
        require(slot.wraps.size in 1..2 && slot.wraps.map { it.vkId }.toSet().size == slot.wraps.size) {
            "a slot holds one or two wraps with different vk_ids"
        }
        if (read is RecordRead.Newer) return false
        val old = rec
        val total = if (masterPasswordTyped) 0 else old?.guard?.total ?: 0
        val next = PinRecord(
            vaultId = vaultId,
            slot = slot,
            guard = PinRecord.Guard(charged = false, row = 0, total = total),
            off = null,
            unseen = old?.unseen ?: PinRecord.Unseen(count = 0, lastAtMs = null, interrupted = false),
        )
        if (!write(next)) return false
        rec = next
        read = RecordRead.Ok(next)
        startUpFailed = false
        forgetMemory()
        return true
    }

    /**
     * The wraps kept at an unlock (lock.md §5.4): drops every wrap whose `vk_id` no kept generation
     * of the vault carries ([keptVkIds], as storage reports it), at every successful unlock by
     * fingerprint or master password (a right PIN does it in [check]). A slot left without a wrap is
     * removed (`off = master_changed`) and its key deleted. Best effort; returns whether the record
     * is on disk.
     */
    fun keepWrapsFor(keptVkIds: Set<String>): Boolean {
        val r = rec ?: return true
        val slot = r.slot ?: return true
        val kept = slot.wraps.filter { it.vkId in keptVkIds }
        if (kept.size == slot.wraps.size) return true
        if (kept.isNotEmpty()) return keep(r.copy(slot = slot.copy(wraps = kept)))
        val written = keep(r.copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.MASTER_CHANGED, clock.wallMs())))
        deviceKeys.delete(PinWrap.alias(slot.id))
        return written
    }

    /**
     * A master-password change keeps the PIN (lock.md F8 step 5): every wrap but the one for the
     * vault's current key [currentVkId] is dropped and [add], the wrap of the new key under the same
     * `KEK_pin`, is added, so that a slot never holds three (§5.4). Best effort: the change stays in
     * memory if the write fails, and the next write stores it. Returns whether the record is on disk;
     * false without a slot.
     */
    fun changeWraps(currentVkId: String, add: KeyWrap): Boolean {
        val r = rec ?: return false
        val slot = r.slot ?: return false
        val kept = slot.wraps.filter { it.vkId == currentVkId && it.vkId != add.vkId }
        return keep(r.copy(slot = slot.copy(wraps = kept + add)))
    }

    /**
     * Removes the slot for [reason] (`off`), refunding a held charge in the same write, and deletes
     * its key at once (lock.md §5.7): a key gone found outside a check (F15b), a vault that a
     * replacement put aside (§5.11 step 3, F16b step 5). Use [setPin] for a new PIN and [check] for a
     * limit. Best effort, as §5.11 step 3 asks: the slot is gone from memory even if the write fails.
     * Returns whether the record is on disk.
     */
    fun removeSlot(reason: PinRecord.OffReason): Boolean {
        val r = rec ?: return true
        val slot = r.slot ?: return true
        charge = null
        val written = keep(r.copy(slot = null, off = PinRecord.Off(reason, clock.wallMs()), guard = r.guard.copy(charged = false)))
        deviceKeys.delete(PinWrap.alias(slot.id))
        return written
    }

    /**
     * The notice of wrong tries was answered (lock.md §13.4 item 4): the [shown] number is taken off
     * `unseen.count`, and with [interruptedShown] the "interrupted" line is done with too. A notice
     * that was never shown takes nothing off. Best effort; returns whether the record is on disk.
     */
    fun unseenShown(shown: Int, interruptedShown: Boolean): Boolean {
        require(shown >= 0) { "a negative count" }
        val r = rec ?: return true
        val count = maxOf(0, r.unseen.count - shown)
        val unseen = PinRecord.Unseen(
            count = count,
            lastAtMs = if (count == 0) null else r.unseen.lastAtMs,
            interrupted = count > 0 && r.unseen.interrupted && !interruptedShown,
        )
        if (unseen == r.unseen) return true
        return keep(r.copy(unseen = unseen))
    }

    private fun refund() {
        charge = null
        val r = rec ?: return
        if (r.guard.charged) keep(r.copy(guard = r.guard.copy(charged = false)))
    }

    /** Makes [next] the record in memory and writes it; returns whether it is on disk. */
    private fun keep(next: PinRecord): Boolean {
        rec = next
        rightUnwritten = false
        return write(next)
    }

    private fun write(record: PinRecord): Boolean =
        try {
            store.write(record)
            true
        } catch (e: IOException) {
            false
        }

    private fun forgetMemory() {
        charge = null
        visitWrong.clear()
        checkedWrong.clear()
        hiddenOn = null
        rightUnwritten = false
    }

    private fun tagOf(pin: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(nonce)
        digest.update(pin)
        return Bytes.hex(digest.digest())
    }

    companion object {
        /** `ROW_LIMIT`: wrong PINs in a row before the PIN turns off (lock.md §6.2). */
        const val ROW_LIMIT = 5

        /** `TOTAL_LIMIT`: wrong PINs not forgiven between two master-password entries. */
        const val TOTAL_LIMIT = 10

        /** `CHARGE_AGE`: how old a charge must be before its PIN is checked (I4). */
        const val CHARGE_AGE_MS = 1_000L

        /** `FORGIVE_WINDOW`: how old a wrong PIN on the same visit may be for a right PIN to forgive it. */
        const val FORGIVE_WINDOW_MS = 60_000L

        /** `FORGIVE_MAX`: how many wrong PINs a right PIN forgives at most. */
        const val FORGIVE_MAX = 2

        private const val NONCE_SIZE = 16
        private const val KEK_SIZE = 32

        /** Whether [guard] has reached a limit: `row ≥ 5` or `total ≥ 10`. */
        fun atLimit(guard: PinRecord.Guard): Boolean = guard.row >= ROW_LIMIT || guard.total >= TOTAL_LIMIT

        /** "N tries left": min(5 − row, 10 − total), never below 0. */
        fun left(guard: PinRecord.Guard): Int = maxOf(0, minOf(ROW_LIMIT - guard.row, TOTAL_LIMIT - guard.total))

        /** One more wrong PIN: `charged = false`, `row + 1`, `total + 1`, held at the limits so a record stays within its rules (§5.4). */
        private fun counted(guard: PinRecord.Guard): PinRecord.Guard =
            PinRecord.Guard(charged = false, row = minOf(guard.row + 1, ROW_LIMIT), total = minOf(guard.total + 1, TOTAL_LIMIT))
    }
}
