package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.B64
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The three lock records of one vault as bytes (lock.md §5.3 to §5.6 as revised by its audit):
 * `pin.rec`, `fingerprint.rec` and `state.rec`, one [Format] each.
 *
 * **Framing** (§5.3): `"PVLOCK01"` (8 bytes), `len` (u32, big-endian, 2 to 65,536), `len` bytes of
 * JSON in UTF-8, and SHA-256 of everything before it (32 bytes); nothing may follow. The hash detects
 * damage; it is no defence against someone who can write the app's private files.
 *
 * **The JSON** is one object made by this writer: every key of the record in the order of the
 * examples of §5.4 to §5.6, no white space, numbers written with `Long.toString`, whatever the
 * default locale. Its strings are ids, Base64 and words from fixed lists, so it is ASCII and well
 * under 2 KB ([MAX_RECORD_SIZE]).
 *
 * **Reading** gives three of the five results of §5.3 ([RecordRead]); MISSING and UNREADABLE are
 * the file's, which `FileRecordStore` adds. In this order:
 * 1. the magic, the length and the hash, else DAMAGED;
 * 2. the JSON, read by [StrictJson] (no key twice, at most [MAX_DEPTH] deep), else DAMAGED;
 * 3. `"v"`: an integer above 1 is NEWER (a newer app owns the record; nothing else of it is read),
 *    anything but 1 is DAMAGED;
 * 4. `"kind"` must be this record's, and `"vault_id"` the name of its directory;
 * 5. every key of the record must be there with its type, and no other key, at every level;
 * 6. the field rules of §5.4 to §5.6 ([Format.brokenRule]): ids are lower-case UUIDs of version 4; `row` 0 to 5,
 *    `total` 0 to 10, `step` 0 to 3; a slot holds one or two wraps with different `vk_id`s, each
 *    nonce 12 bytes and each `ct` 48; a salt is 16 bytes; counts, credits and elapsed times are not
 *    negative; `off` is null while a slot is set. Wall-clock times may be any whole number of
 *    milliseconds.
 *
 * A record that breaks any of these is DAMAGED with a [ParseError] that names the rule and an
 * offset, never a piece of the record (architecture.md §5.6). [Format.decode] never throws.
 *
 * **Writing** refuses a record that its own reader would refuse: [Format.encode] runs the same
 * [Format.brokenRule] and throws `IllegalArgumentException` (whose message names the rule, never a value), so
 * a damaged record is never written. That is a bug of the caller; nothing has been written then.
 */
object RecordCodec {
    /** The 8 bytes every record starts with. */
    const val MAGIC = "PVLOCK01"

    /** The only `"v"` this app reads and writes. */
    const val VERSION = 1

    /** The shortest JSON a record may hold (`{}`), and the longest (§5.3). */
    const val MIN_JSON_LENGTH = 2
    const val MAX_JSON_LENGTH = 65_536

    /** The largest record, in bytes, this writer makes: "a record is under 2 KB" (§5.3). */
    const val MAX_RECORD_SIZE = 2_047

    /** The deepest nesting the reader accepts, objects and arrays counted, the record itself at depth 1. */
    const val MAX_DEPTH = 16

    /** `guard.row` is 0 to 5 and `guard.total` 0 to 10 (§5.4, the limits of §6.2). */
    const val ROW_LIMIT = 5
    const val TOTAL_LIMIT = 10

    /** `practice.step` is 0 to 3 (§8.3). */
    const val MAX_STEP = 3

    /** A slot holds one or two wraps (§5.4). */
    const val MAX_WRAPS = 2

    /** The sizes of the Base64 values (§4.2, §5.4, §5.6). */
    const val NONCE_LENGTH = 12
    const val CT_LENGTH = 48
    const val SALT_LENGTH = 16

    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
    private const val LENGTH_FIELD = 4
    private const val HASH_LENGTH = 32
    private const val HEADER_LENGTH = 8 + LENGTH_FIELD // magic and len
    private const val LENGTH_OFFSET = 8L
    private const val JSON_OFFSET = 12L

    /** Why a record was refused: the [ParseError.reason] of a DAMAGED result. [StrictJson.Reason] gives the JSON's. */
    enum class Reason(val code: String) {
        /** The record does not start with [MAGIC]. */
        MAGIC("magic"),

        /** `len` is outside 2 to 65,536, or the file is not exactly as long as `len` says. */
        LENGTH("length"),

        /** The SHA-256 at the end does not match. */
        HASH("hash"),

        /** `"v"` is an integer below 1. */
        VERSION("version"),

        /** `"kind"` is not this record's. */
        KIND("kind"),

        /** `"vault_id"` is not the name of the record's directory. */
        VAULT_ID("vault-id"),

        /** A key of the record is not there. */
        MISSING_KEY("missing-key"),

        /** A key that the record does not have. */
        UNKNOWN_KEY("unknown-key"),

        /** A value of the wrong JSON type, a number that is not a whole number, or text that is not canonical Base64. */
        WRONG_TYPE("wrong-type"),

        /** A word that is not in its list, such as an `off.reason` or a `by`. */
        BAD_VALUE("bad-value"),

        /** An id that is not a lower-case UUID of version 4. */
        BAD_ID("bad-id"),

        /** Base64 of the wrong number of bytes. */
        WRONG_LENGTH("wrong-length"),

        /** A number outside its range. */
        OUT_OF_RANGE("out-of-range"),

        /** A slot without a wrap, with more than two, or with two for one `vk_id`. */
        WRAPS("wraps"),

        /** `off` is set while a slot is set. */
        SLOT_AND_OFF("slot-and-off"),

        /** Something failed inside the codec in a way of its own; its message is never kept. */
        PARSER_FAILURE("parser-failure"),
    }

    /** Every reason a DAMAGED result of this codec can carry: a fixed list, none of it input (§5.6). */
    val REASON_CODES: Set<String> =
        (Reason.entries.map { it.code } + StrictJson.Reason.entries.map { it.code }).toSet()

    /** The three records of a vault's lock directory (lock.md §5.2): `pin.rec`, `fingerprint.rec`, `state.rec`. */
    val FORMATS: List<Format<*>> get() = listOf(Pin, Fingerprint, State)

    /**
     * One of the three records: its `"kind"`, its file name in the vault's lock directory, and how
     * its fields are read and written.
     */
    sealed class Format<T>(
        /** `"kind"` in the record: `pin`, `fingerprint` or `state`. */
        val kind: String,
    ) {
        /** The file name in `<noBackupFilesDir>/lock/<vault_id>/`: [kind] + `.rec`. */
        val fileName: String = "$kind.rec"

        /**
         * The bytes of [record], framed (§5.3).
         *
         * @throws IllegalArgumentException when [record] breaks a rule of §5.4 to §5.6; the message
         *   names the rule and holds no value. Nothing of it should be written then.
         */
        fun encode(record: T): ByteArray {
            brokenRule(record)?.let { throw IllegalArgumentException("the $kind record breaks the rule '${it.code}'") }
            val out = Out()
            out.raw("{\"v\":")
            out.long(VERSION.toLong())
            out.key("kind")
            out.string(kind)
            out.key("vault_id")
            out.string(vaultIdOf(record))
            write(out, record)
            out.raw("}")
            val bytes = frame(out.ascii())
            require(bytes.size <= MAX_RECORD_SIZE) { "the $kind record is not under 2 KB" }
            return bytes
        }

        /**
         * The record in [bytes], read from the directory of the vault [vaultId] (its `"vault_id"`
         * must be that): [RecordRead.Ok], [RecordRead.Damaged] or [RecordRead.Newer]. Never throws
         * for any input.
         */
        fun decode(bytes: ByteArray, vaultId: String): RecordRead<T> =
            try {
                read(bytes, vaultId)
            } catch (_: Exception) { // a failure of the codec itself, without its message
                damaged(Reason.PARSER_FAILURE, JSON_OFFSET)
            }

        /**
         * The first rule of §5.4 to §5.6 that [record] breaks, or null. The writer refuses such a
         * record and the reader calls it DAMAGED, so the two always agree.
         */
        fun brokenRule(record: T): Reason? = try {
            rule(record)
        } catch (r: Refused) {
            r.reason
        }

        /** The `vault_id` of [record]. */
        abstract fun vaultIdOf(record: T): String

        /** Writes the keys after `vault_id`, each with its leading comma. */
        internal abstract fun write(out: Out, record: T)

        /** Reads the record of the vault [vaultId] from [o], which holds exactly `v`, `kind`, `vault_id` and [keys]. */
        internal abstract fun read(o: Fields, vaultId: String): T

        /** The keys the record has besides `v`, `kind` and `vault_id`, in the writer's order. */
        internal abstract val keys: List<String>

        /** Throws [Refused] for the first rule that [record] breaks. */
        internal abstract fun rule(record: T)

        private fun read(bytes: ByteArray, vaultId: String): RecordRead<T> {
            // 1. Framing.
            if (bytes.size < MAGIC_BYTES.size) return damaged(Reason.MAGIC, 0)
            for (i in MAGIC_BYTES.indices) if (bytes[i] != MAGIC_BYTES[i]) return damaged(Reason.MAGIC, 0)
            if (bytes.size < HEADER_LENGTH) return damaged(Reason.LENGTH, LENGTH_OFFSET)
            val len = u32(bytes, MAGIC_BYTES.size)
            if (len < MIN_JSON_LENGTH || len > MAX_JSON_LENGTH) return damaged(Reason.LENGTH, LENGTH_OFFSET)
            if (bytes.size.toLong() != HEADER_LENGTH + len + HASH_LENGTH) return damaged(Reason.LENGTH, LENGTH_OFFSET)
            val end = HEADER_LENGTH + len.toInt()
            val digest = sha256(bytes, end)
            for (i in 0 until HASH_LENGTH) if (bytes[end + i] != digest[i]) return damaged(Reason.HASH, end.toLong())
            // 2. The JSON.
            val root = try {
                StrictJson.parseUtf8Object(bytes.copyOfRange(HEADER_LENGTH, end), MAX_DEPTH)
            } catch (f: StrictJson.Failure) {
                return RecordRead.Damaged(ParseError(JSON_OFFSET + f.offset, f.reason.code))
            }
            return try {
                // 3. The version.
                val v = integer(root["v"] ?: refuse(Reason.MISSING_KEY))
                if (v > VERSION) return RecordRead.Newer
                if (v != VERSION.toLong()) refuse(Reason.VERSION)
                // 4. Which record, and whose.
                if (string(root["kind"] ?: refuse(Reason.MISSING_KEY)) != kind) refuse(Reason.KIND)
                if (string(root["vault_id"] ?: refuse(Reason.MISSING_KEY)) != vaultId) refuse(Reason.VAULT_ID)
                // 5. Keys and types.
                val record = read(Fields(root, listOf("v", "kind", "vault_id") + keys), vaultId)
                // 6. Field rules.
                rule(record)
                RecordRead.Ok(record)
            } catch (r: Refused) {
                damaged(r.reason, JSON_OFFSET)
            }
        }
    }

    /** `pin.rec` (§5.4). */
    data object Pin : Format<PinRecord>("pin") {
        override val keys = listOf("slot", "guard", "off", "unseen")

        override fun vaultIdOf(record: PinRecord): String = record.vaultId

        override fun write(out: Out, record: PinRecord) {
            out.key("slot")
            writeSlot(out, record.slot)
            out.key("guard")
            out.raw("{")
            out.key("charged", first = true)
            out.boolean(record.guard.charged)
            out.key("row")
            out.long(record.guard.row.toLong())
            out.key("total")
            out.long(record.guard.total.toLong())
            out.raw("}")
            out.key("off")
            val off = record.off
            if (off == null) out.nul() else writeOff(out, off.reason.stored, off.atMs)
            out.key("unseen")
            out.raw("{")
            out.key("count", first = true)
            out.long(record.unseen.count.toLong())
            out.key("last_at")
            out.longOrNull(record.unseen.lastAtMs)
            out.key("interrupted")
            out.boolean(record.unseen.interrupted)
            out.raw("}")
        }

        override fun read(o: Fields, vaultId: String): PinRecord {
            val guard = o.obj("guard", "charged", "row", "total")
            val unseen = o.obj("unseen", "count", "last_at", "interrupted")
            return PinRecord(
                vaultId = vaultId,
                slot = readSlot(o),
                guard = PinRecord.Guard(
                    charged = guard.boolean("charged"),
                    row = guard.int("row"),
                    total = guard.int("total"),
                ),
                off = o.objOrNull("off", "reason", "at")?.let { off ->
                    val stored = off.string("reason")
                    val reason = PinRecord.OffReason.entries.firstOrNull { it.stored == stored } ?: refuse(Reason.BAD_VALUE)
                    PinRecord.Off(reason, off.long("at"))
                },
                unseen = PinRecord.Unseen(
                    count = unseen.int("count"),
                    lastAtMs = unseen.longOrNull("last_at"),
                    interrupted = unseen.boolean("interrupted"),
                ),
            )
        }

        override fun rule(record: PinRecord) {
            id(record.vaultId)
            slotRule(record.slot)
            if (record.slot != null && record.off != null) refuse(Reason.SLOT_AND_OFF)
            within(record.guard.row, 0, ROW_LIMIT)
            within(record.guard.total, 0, TOTAL_LIMIT)
            notNegative(record.unseen.count.toLong())
        }
    }

    /** `fingerprint.rec` (§5.5): the slot and `off` of `pin.rec`, with its own `off` reasons. */
    data object Fingerprint : Format<FingerprintRecord>("fingerprint") {
        override val keys = listOf("slot", "off")

        override fun vaultIdOf(record: FingerprintRecord): String = record.vaultId

        override fun write(out: Out, record: FingerprintRecord) {
            out.key("slot")
            writeSlot(out, record.slot)
            out.key("off")
            val off = record.off
            if (off == null) out.nul() else writeOff(out, off.reason.stored, off.atMs)
        }

        override fun read(o: Fields, vaultId: String): FingerprintRecord = FingerprintRecord(
            vaultId = vaultId,
            slot = readSlot(o),
            off = o.objOrNull("off", "reason", "at")?.let { off ->
                val stored = off.string("reason")
                val reason = FingerprintRecord.OffReason.entries.firstOrNull { it.stored == stored } ?: refuse(Reason.BAD_VALUE)
                FingerprintRecord.Off(reason, off.long("at"))
            },
        )

        override fun rule(record: FingerprintRecord) {
            id(record.vaultId)
            slotRule(record.slot)
            if (record.slot != null && record.off != null) refuse(Reason.SLOT_AND_OFF)
        }
    }

    /** `state.rec` (§5.6). */
    data object State : Format<StateRecord>("state") {
        override val keys = listOf("practice", "mp_guard", "mp_change", "pending_mp", "replaced", "max_generation")

        override fun vaultIdOf(record: StateRecord): String = record.vaultId

        override fun write(out: Out, record: StateRecord) {
            out.key("practice")
            out.raw("{")
            out.key("last_ok", first = true)
            out.long(record.practice.lastOkMs)
            out.key("step")
            out.long(record.practice.step.toLong())
            out.key("snoozed_until")
            out.longOrNull(record.practice.snoozedUntilMs)
            out.raw("}")
            out.key("mp_guard")
            out.raw("{")
            out.key("failures", first = true)
            out.long(record.mpGuard.failures.toLong())
            out.key("last_fail_elapsed_ms")
            out.longOrNull(record.mpGuard.lastFailElapsedMs)
            out.key("unseen")
            out.long(record.mpGuard.unseen.toLong())
            out.raw("}")
            out.key("mp_change")
            val change = record.mpChange
            if (change == null) {
                out.nul()
            } else {
                out.raw("{")
                out.key("to_vk_id", first = true)
                out.string(change.toVkId)
                out.key("at")
                out.long(change.atMs)
                out.raw("}")
            }
            out.key("pending_mp")
            val pending = record.pendingMp
            if (pending == null) {
                out.nul()
            } else {
                out.raw("{")
                out.key("requested", first = true)
                out.long(pending.requestedMs)
                out.key("by")
                out.string(pending.by.stored)
                out.key("credited_ms")
                out.long(pending.creditedMs)
                out.key("seen_elapsed_ms")
                out.long(pending.seenElapsedMs)
                out.key("vk_id")
                out.string(pending.vkId)
                out.key("salt")
                out.string(B64.encode(pending.salt))
                out.key("nonce")
                out.string(B64.encode(pending.nonce))
                out.key("ct")
                out.string(B64.encode(pending.ct))
                out.raw("}")
            }
            out.key("replaced")
            val replaced = record.replaced
            if (replaced == null) {
                out.nul()
            } else {
                out.raw("{")
                out.key("new_vault_id", first = true)
                out.string(replaced.newVaultId)
                out.key("at")
                out.long(replaced.atMs)
                out.key("by")
                out.string(replaced.by.stored)
                out.key("credited_ms")
                out.long(replaced.creditedMs)
                out.key("seen_elapsed_ms")
                out.long(replaced.seenElapsedMs)
                out.raw("}")
            }
            out.key("max_generation")
            out.raw("{")
            out.key("n", first = true)
            out.long(record.maxGeneration.n)
            out.key("at")
            out.long(record.maxGeneration.atMs)
            out.raw("}")
        }

        override fun read(o: Fields, vaultId: String): StateRecord {
            val practice = o.obj("practice", "last_ok", "step", "snoozed_until")
            val guard = o.obj("mp_guard", "failures", "last_fail_elapsed_ms", "unseen")
            val maxGeneration = o.obj("max_generation", "n", "at")
            return StateRecord(
                vaultId = vaultId,
                practice = StateRecord.Practice(
                    lastOkMs = practice.long("last_ok"),
                    step = practice.int("step"),
                    snoozedUntilMs = practice.longOrNull("snoozed_until"),
                ),
                mpGuard = StateRecord.MpGuard(
                    failures = guard.int("failures"),
                    lastFailElapsedMs = guard.longOrNull("last_fail_elapsed_ms"),
                    unseen = guard.int("unseen"),
                ),
                mpChange = o.objOrNull("mp_change", "to_vk_id", "at")?.let {
                    StateRecord.MpChange(it.string("to_vk_id"), it.long("at"))
                },
                pendingMp = o.objOrNull(
                    "pending_mp", "requested", "by", "credited_ms", "seen_elapsed_ms", "vk_id", "salt", "nonce", "ct",
                )?.let {
                    StateRecord.PendingMp(
                        requestedMs = it.long("requested"),
                        by = shortcut(it.string("by")),
                        creditedMs = it.long("credited_ms"),
                        seenElapsedMs = it.long("seen_elapsed_ms"),
                        vkId = it.string("vk_id"),
                        salt = it.base64("salt"),
                        nonce = it.base64("nonce"),
                        ct = it.base64("ct"),
                    )
                },
                replaced = o.objOrNull("replaced", "new_vault_id", "at", "by", "credited_ms", "seen_elapsed_ms")?.let {
                    StateRecord.Replaced(
                        newVaultId = it.string("new_vault_id"),
                        atMs = it.long("at"),
                        by = shortcut(it.string("by")),
                        creditedMs = it.long("credited_ms"),
                        seenElapsedMs = it.long("seen_elapsed_ms"),
                    )
                },
                maxGeneration = StateRecord.MaxGeneration(maxGeneration.long("n"), maxGeneration.long("at")),
            )
        }

        override fun rule(record: StateRecord) {
            id(record.vaultId)
            within(record.practice.step, 0, MAX_STEP)
            notNegative(record.mpGuard.failures.toLong())
            record.mpGuard.lastFailElapsedMs?.let(::notNegative)
            notNegative(record.mpGuard.unseen.toLong())
            record.mpChange?.let { id(it.toVkId) }
            record.pendingMp?.let {
                id(it.vkId)
                notNegative(it.creditedMs)
                notNegative(it.seenElapsedMs)
                size(it.salt, SALT_LENGTH)
                size(it.nonce, NONCE_LENGTH)
                size(it.ct, CT_LENGTH)
            }
            record.replaced?.let {
                id(it.newVaultId)
                notNegative(it.creditedMs)
                notNegative(it.seenElapsedMs)
            }
            notNegative(record.maxGeneration.n)
        }

        private fun shortcut(stored: String): Shortcut = Shortcut.entries.firstOrNull { it.stored == stored } ?: refuse(Reason.BAD_VALUE)
    }

    // ---------------------------------------------------------------- the parts the two shortcut records share

    private fun writeSlot(out: Out, slot: ShortcutSlot?) {
        if (slot == null) {
            out.nul()
            return
        }
        out.raw("{")
        out.key("id", first = true)
        out.string(slot.id)
        out.key("created")
        out.long(slot.createdMs)
        out.key("wraps")
        out.raw("[")
        slot.wraps.forEachIndexed { i, wrap ->
            if (i > 0) out.raw(",")
            out.raw("{")
            out.key("vk_id", first = true)
            out.string(wrap.vkId)
            out.key("nonce")
            out.string(B64.encode(wrap.nonce))
            out.key("ct")
            out.string(B64.encode(wrap.ct))
            out.raw("}")
        }
        out.raw("]")
        out.raw("}")
    }

    private fun writeOff(out: Out, reason: String, atMs: Long) {
        out.raw("{")
        out.key("reason", first = true)
        out.string(reason)
        out.key("at")
        out.long(atMs)
        out.raw("}")
    }

    private fun readSlot(o: Fields): ShortcutSlot? {
        val slot = o.objOrNull("slot", "id", "created", "wraps") ?: return null
        val wraps = slot.array("wraps").map { element ->
            val wrap = Fields(element as? JsonObject ?: refuse(Reason.WRONG_TYPE), listOf("vk_id", "nonce", "ct"))
            KeyWrap(wrap.string("vk_id"), wrap.base64("nonce"), wrap.base64("ct"))
        }
        return ShortcutSlot(slot.string("id"), slot.long("created"), wraps)
    }

    private fun slotRule(slot: ShortcutSlot?) {
        if (slot == null) return
        id(slot.id)
        if (slot.wraps.isEmpty() || slot.wraps.size > MAX_WRAPS) refuse(Reason.WRAPS)
        for (wrap in slot.wraps) {
            id(wrap.vkId)
            size(wrap.nonce, NONCE_LENGTH)
            size(wrap.ct, CT_LENGTH)
        }
        if (slot.wraps.map { it.vkId }.toSet().size != slot.wraps.size) refuse(Reason.WRAPS)
    }

    private fun id(value: String) {
        if (!Uuid.isValid(value)) refuse(Reason.BAD_ID)
    }

    private fun within(value: Int, low: Int, high: Int) {
        if (value < low || value > high) refuse(Reason.OUT_OF_RANGE)
    }

    private fun notNegative(value: Long) {
        if (value < 0) refuse(Reason.OUT_OF_RANGE)
    }

    private fun size(bytes: ByteArray, length: Int) {
        if (bytes.size != length) refuse(Reason.WRONG_LENGTH)
    }

    // ---------------------------------------------------------------- framing

    /** [json] framed: magic, length, JSON, SHA-256 (§5.3). */
    private fun frame(json: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LENGTH + json.size + HASH_LENGTH)
        MAGIC_BYTES.copyInto(out)
        val len = json.size
        out[8] = (len ushr 24).toByte()
        out[9] = (len ushr 16).toByte()
        out[10] = (len ushr 8).toByte()
        out[11] = len.toByte()
        json.copyInto(out, HEADER_LENGTH)
        sha256(out, HEADER_LENGTH + len).copyInto(out, HEADER_LENGTH + len)
        return out
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
            ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)

    /** SHA-256 of the first [length] bytes of [bytes]. */
    private fun sha256(bytes: ByteArray, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, length)
        return digest.digest()
    }

    private fun <T> damaged(reason: Reason, offset: Long): RecordRead<T> = RecordRead.Damaged(ParseError(offset, reason.code))

    // ---------------------------------------------------------------- reading the tree

    /** A rule failed; caught at the codec's boundary and never seen outside it. */
    internal class Refused(val reason: Reason) : Exception(reason.code)

    internal fun refuse(reason: Reason): Nothing = throw Refused(reason)

    /** The value of an integer key: a number of the form `-?(0|[1-9][0-9]{0,18})` that fits a `Long`. */
    private fun integer(e: JsonElement): Long {
        if (e !is JsonPrimitive || e.isString || e is JsonNull) refuse(Reason.WRONG_TYPE)
        val s = e.content
        val start = if (s.startsWith('-')) 1 else 0
        val digits = s.length - start
        if (digits < 1 || digits > 19 || (s[start] == '0' && digits > 1)) refuse(Reason.WRONG_TYPE)
        for (i in start until s.length) if (s[i] !in '0'..'9') refuse(Reason.WRONG_TYPE)
        return s.toLongOrNull() ?: refuse(Reason.WRONG_TYPE)
    }

    private fun string(e: JsonElement): String =
        if (e is JsonPrimitive && e.isString) e.content else refuse(Reason.WRONG_TYPE)

    /**
     * One JSON object of a record whose keys must be exactly [keys]: a key that is not there is
     * [Reason.MISSING_KEY], any other key [Reason.UNKNOWN_KEY].
     */
    internal class Fields(private val o: JsonObject, keys: List<String>) {
        init {
            for (key in keys) if (!o.containsKey(key)) refuse(Reason.MISSING_KEY)
            if (o.size != keys.size) refuse(Reason.UNKNOWN_KEY)
        }

        private operator fun get(key: String): JsonElement = o[key] ?: refuse(Reason.MISSING_KEY)

        fun string(key: String): String = string(this[key])

        fun long(key: String): Long = integer(this[key])

        fun longOrNull(key: String): Long? = this[key].let { if (it is JsonNull) null else integer(it) }

        fun int(key: String): Int {
            val value = long(key)
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) refuse(Reason.OUT_OF_RANGE)
            return value.toInt()
        }

        fun boolean(key: String): Boolean {
            val e = this[key]
            if (e is JsonPrimitive && !e.isString && e !is JsonNull) {
                when (e.content) {
                    "true" -> return true
                    "false" -> return false
                }
            }
            refuse(Reason.WRONG_TYPE)
        }

        /** Canonical standard Base64 with padding (lock.md §4.2), of any length; the field rules check the length. */
        fun base64(key: String): ByteArray = B64.decode(string(key)) ?: refuse(Reason.WRONG_TYPE)

        fun array(key: String): JsonArray = this[key] as? JsonArray ?: refuse(Reason.WRONG_TYPE)

        fun obj(key: String, vararg keys: String): Fields =
            Fields(this[key] as? JsonObject ?: refuse(Reason.WRONG_TYPE), keys.toList())

        fun objOrNull(key: String, vararg keys: String): Fields? {
            val e = this[key]
            if (e is JsonNull) return null
            return Fields(e as? JsonObject ?: refuse(Reason.WRONG_TYPE), keys.toList())
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * The writer: compact JSON with ASCII output. Strings are escaped as RFC 8259 requires, though
     * the values a record holds (ids, Base64, words) never need it.
     */
    internal class Out {
        private val sb = StringBuilder(512)

        fun raw(text: String) {
            sb.append(text)
        }

        /** `,"key":`, or `"key":` for the first key of an object. */
        fun key(name: String, first: Boolean = false) {
            if (!first) sb.append(',')
            string(name)
            sb.append(':')
        }

        fun string(value: String) {
            sb.append('"')
            for (c in value) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' || c.code > 0x7e -> {
                        sb.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        for (i in hex.length until 4) sb.append('0')
                        sb.append(hex)
                    }
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }

        fun long(value: Long) {
            sb.append(value.toString())
        }

        fun longOrNull(value: Long?) {
            if (value == null) nul() else long(value)
        }

        fun boolean(value: Boolean) {
            sb.append(if (value) "true" else "false")
        }

        fun nul() {
            sb.append("null")
        }

        fun ascii(): ByteArray = sb.toString().toByteArray(Charsets.US_ASCII)
    }
}
