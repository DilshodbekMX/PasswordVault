package io.github.dilshodbekmx.passwordvault.testing.storage

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.B64
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyResult
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultFile
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * A [VaultCodec] for the storage tests, which must not wait for the real one (build plan 2.2) nor
 * spend an Argon2id per test.
 *
 * **What is real:** the framing of architecture.md §4.1 (magic, `header_len`, the header, the body
 * nonce, the body with a 16-byte tag, the SHA-256 trailer), the canonical header of §4.2 with its
 * constants, rules R1 to R7 as far as storage depends on them (NOT OURS, `PVAULTnn` NEWER with a
 * good trailer and DAMAGED without, the version of R5 and R6, the full header, `body_len` against the
 * length, the cap), the body padded to whole blocks of 4,096 bytes, and fresh nonces from [random]
 * and `created` from [clock] at every [seal].
 *
 * **What is fake:** the cryptography (a keyed SHA-256 stream and tag in place of AES-256-GCM: wrong
 * keys and changed bytes still fail, and the AAD is the header as stored) and the body (a binary
 * form of every field of [Vault], read back exactly, unpaired surrogates included; `schema` above 1
 * reads as NEWER). [kek] stands for Argon2id.
 *
 * [headerVersion] above 1 writes a header of a newer app (R6), and [sealHook] changes what [seal]
 * returns, to stand for a writer bug that a store must catch before it writes.
 */
class FakeCodec(
    private val random: RandomSource = FakeRandom(7L),
    private val clock: Clock = FakeClock(),
    @Volatile var build: BuildInfo = BuildInfo("PasswordVault 1.0", 1),
) : VaultCodec {
    /** The `version` that [seal] writes into the header: 1, or higher for a file of a newer app. */
    @Volatile var headerVersion: Int = 1

    /** Applied to every file [seal] makes. */
    @Volatile var sealHook: ((ByteArray) -> ByteArray)? = null

    /** How many files [seal] made and how many bodies [open] was asked to open. */
    @Volatile var seals: Int = 0
        private set

    @Volatile var opens: Int = 0
        private set

    override fun check(bytes: ByteArray): FileCheck = try {
        checkOrThrow(bytes)
    } catch (e: RuntimeException) {
        FileCheck.Damaged(7)
    }

    private fun checkOrThrow(bytes: ByteArray): FileCheck {
        // R1
        if (bytes.size < MAGIC.size || !bytes.copyOf(FAMILY).contentEquals(MAGIC.copyOf(FAMILY))) return FileCheck.NotOurs
        val tens = bytes[FAMILY] - '0'.code.toByte()
        val units = bytes[FAMILY + 1] - '0'.code.toByte()
        if (tens !in 0..9 || units !in 0..9) return FileCheck.NotOurs
        if (tens != 0 || units != 1) return if (bytes.size >= 40 && trailerOk(bytes)) FileCheck.Newer(tens * 10 + units) else FileCheck.Damaged(1)
        // R2
        if (bytes.size < 74 || bytes.size > CAP) return FileCheck.Damaged(2)
        // R3
        val headerLen = ((bytes[8].toLong() and 0xff) shl 24) or ((bytes[9].toLong() and 0xff) shl 16) or
            ((bytes[10].toLong() and 0xff) shl 8) or (bytes[11].toLong() and 0xff)
        if (headerLen < 2 || headerLen > 4096 || 56 + headerLen + 16 > bytes.size) return FileCheck.Damaged(3)
        val aadLength = 12 + headerLen.toInt()
        // R4
        val trailerOk = trailerOk(bytes)
        // R5, R6
        val header = String(bytes, 12, headerLen.toInt(), Charsets.ISO_8859_1)
        val version = VERSION.find(header)?.groupValues?.get(1)?.toInt() ?: return FileCheck.Damaged(5)
        if (version != 1) return if (trailerOk) FileCheck.Newer(version) else FileCheck.Damaged(6)
        // R7
        val m = HEADER.matchEntire(header) ?: return FileCheck.Damaged(7)
        val g = m.groupValues
        val generation = g[3].toLong()
        val writerVersion = g[6].toLong()
        val bodyLen = g[7].toLong()
        if (generation > 9_999_999_999L || writerVersion > Int.MAX_VALUE || bodyLen != bytes.size - 56L - headerLen ||
            bodyLen < 4_112 || (bodyLen - 16) % 4_096 != 0L
        ) {
            return FileCheck.Damaged(7)
        }
        val salt = B64.decode(g[8], 16) ?: return FileCheck.Damaged(7)
        val nonce = B64.decode(g[9], 12) ?: return FileCheck.Damaged(7)
        val wrapped = B64.decode(g[10], 48) ?: return FileCheck.Damaged(7)
        val values = VaultHeader(g[1], g[2], generation, g[4].toLong(), g[5], writerVersion.toInt(), bodyLen.toInt(), PasswordSlot(salt, nonce, wrapped))
        return FileCheck.Ok(
            VaultFile(
                header = values,
                aad = bytes.copyOfRange(0, aadLength),
                bodyNonce = bytes.copyOfRange(aadLength, aadLength + 12),
                bodyCt = bytes.copyOfRange(aadLength + 12, aadLength + 12 + bodyLen.toInt()),
                trailerOk = trailerOk,
            ),
        )
    }

    override fun unwrap(slot: PasswordSlot, kek: ByteArray): ByteArray? {
        if (kek.size != 32 || slot.nonce.size != 12 || slot.wrappedKey.size != 48) return null
        val ct = slot.wrappedKey.copyOf(32)
        val tag = slot.wrappedKey.copyOfRange(32, 48)
        if (!sha256(WRAP_TAG, kek, slot.nonce, ct).copyOf(16).contentEquals(tag)) return null
        return xor(ct, sha256(WRAP, kek, slot.nonce))
    }

    override fun open(file: VaultFile, vk: ByteArray): BodyResult {
        opens++
        if (vk.size != 32 || file.bodyNonce.size != 12 || file.bodyCt.size < 16) return BodyResult.TagFailed
        val ct = file.bodyCt.copyOf(file.bodyCt.size - 16)
        val tag = file.bodyCt.copyOfRange(file.bodyCt.size - 16, file.bodyCt.size)
        if (!sha256(BODY_TAG, vk, file.bodyNonce, file.aad, ct).copyOf(16).contentEquals(tag)) return BodyResult.TagFailed
        return readBody(xor(ct, stream(vk, file.bodyNonce, ct.size)))
    }

    override fun seal(vault: Vault, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray {
        require(vk.size == 32) { "a VK is 32 bytes" }
        seals++
        val plaintext = writeBody(vault)
        val header = headerText(vaultId, vkId, generation, clock.wallMs().coerceIn(0L, 9_999_999_999_999_999L), plaintext.size + 16, slot)
            .toByteArray(Charsets.ISO_8859_1)
        val aad = MAGIC + byteArrayOf((header.size ushr 24).toByte(), (header.size ushr 16).toByte(), (header.size ushr 8).toByte(), header.size.toByte()) + header
        val nonce = random.bytes(12)
        val ct = xor(plaintext, stream(vk, nonce, plaintext.size))
        val before = aad + nonce + ct + sha256(BODY_TAG, vk, nonce, aad, ct).copyOf(16)
        val file = before + sha256(before)
        return sealHook?.invoke(file) ?: file
    }

    override fun wrap(vk: ByteArray, kek: ByteArray, salt: ByteArray): PasswordSlot {
        require(vk.size == 32 && kek.size == 32 && salt.size == 16) { "sizes" }
        val nonce = random.bytes(12)
        val ct = xor(vk, sha256(WRAP, kek, nonce))
        return PasswordSlot(salt.copyOf(), nonce, ct + sha256(WRAP_TAG, kek, nonce, ct).copyOf(16))
    }

    private fun headerText(vaultId: String, vkId: String, generation: Long, created: Long, bodyLen: Int, slot: PasswordSlot): String =
        "{\"format\":\"passwordvault\",\"version\":" + headerVersion + ",\"cipher\":\"AES-256-GCM\",\"vault_id\":\"" + vaultId +
            "\",\"vk_id\":\"" + vkId + "\",\"generation\":" + generation + ",\"created\":" + created + ",\"generator\":\"" +
            build.generator + "\",\"writer_version\":" + build.writerVersion + ",\"body_len\":" + bodyLen +
            ",\"slots\":[{\"type\":\"password\",\"kdf\":\"argon2id\",\"argon2_version\":19,\"m_kib\":65536,\"t\":3,\"p\":4,\"salt\":\"" +
            B64.encode(slot.salt) + "\",\"nonce\":\"" + B64.encode(slot.nonce) + "\",\"wrapped_key\":\"" + B64.encode(slot.wrappedKey) + "\"}]}"

    // ---------------------------------------------------------------------------------------------
    // The body: every field of Vault, strings as UTF-16 code units, then zeros to whole blocks.

    private fun writeBody(vault: Vault): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeLong(vault.schema)
            out.writeInt(vault.entries.size)
            for (e in vault.entries) {
                out.str(e.id)
                out.str(e.title)
                out.str(e.username)
                out.str(e.password)
                out.str(e.notes)
                out.strings(e.urls)
                out.fields(e.fields)
                out.writeBoolean(e.favourite)
                out.writeBoolean(e.changePending)
                out.writeBoolean(e.checkIgnored)
                out.writeLong(e.createdAt)
                out.writeLong(e.updatedAt)
                out.long(e.passwordChangedAt)
                out.long(e.deletedAt)
                out.nullable(e.pendingOldPassword)
                out.writeInt(e.passwordHistory.size)
                for (h in e.passwordHistory) {
                    out.str(h.password)
                    out.writeLong(h.replacedAt)
                }
                out.extras(e.extras)
            }
            val draft = vault.draft
            out.writeBoolean(draft != null)
            if (draft != null) {
                out.nullable(draft.entryId)
                out.writeLong(draft.savedAt)
                val f = draft.entry
                out.str(f.title)
                out.str(f.username)
                out.str(f.password)
                out.str(f.notes)
                out.strings(f.urls)
                out.fields(f.fields)
                out.writeBoolean(f.favourite)
            }
            out.refs(vault.plainExports)
            out.refs(vault.backups)
            out.strings(vault.masterPasswordMatches)
            out.long(vault.securitySeenAt)
            out.extras(vault.extras)
        }
        val data = bytes.toByteArray()
        val length = 4 + data.size
        val padded = ByteArray((length + BLOCK - 1) / BLOCK * BLOCK)
        padded[0] = (data.size ushr 24).toByte()
        padded[1] = (data.size ushr 16).toByte()
        padded[2] = (data.size ushr 8).toByte()
        padded[3] = data.size.toByte()
        data.copyInto(padded, 4)
        return padded
    }

    private fun readBody(plaintext: ByteArray): BodyResult = try {
        val size = ((plaintext[0].toInt() and 0xff) shl 24) or ((plaintext[1].toInt() and 0xff) shl 16) or
            ((plaintext[2].toInt() and 0xff) shl 8) or (plaintext[3].toInt() and 0xff)
        require(size >= 0 && size <= plaintext.size - 4) { "length" }
        val input = DataInputStream(ByteArrayInputStream(plaintext, 4, size))
        val schema = input.readLong()
        if (schema > 1) {
            BodyResult.Newer(schema)
        } else {
            val entries = List(input.count(size)) {
                Entry(
                    id = input.str(),
                    title = input.str(),
                    username = input.str(),
                    password = input.str(),
                    notes = input.str(),
                    urls = input.strings(),
                    fields = input.fields(),
                    favourite = input.readBoolean(),
                    changePending = input.readBoolean(),
                    checkIgnored = input.readBoolean(),
                    createdAt = input.readLong(),
                    updatedAt = input.readLong(),
                    passwordChangedAt = input.long(),
                    deletedAt = input.long(),
                    pendingOldPassword = input.nullable(),
                    passwordHistory = List(input.count(size)) { HistoryItem(input.str(), input.readLong()) },
                    extras = input.extras(),
                )
            }
            val draft = if (input.readBoolean()) {
                Draft(
                    entryId = input.nullable(),
                    savedAt = input.readLong(),
                    entry = DraftEntry(input.str(), input.str(), input.str(), input.str(), input.strings(), input.fields(), input.readBoolean()),
                )
            } else {
                null
            }
            val vault = Vault(
                schema = schema,
                entries = entries,
                draft = draft,
                plainExports = input.refs(),
                backups = input.refs(),
                masterPasswordMatches = input.strings(),
                securitySeenAt = input.long(),
                extras = input.extras(),
            )
            require(input.available() == 0) { "trailing bytes" }
            BodyResult.Opened(vault)
        }
    } catch (e: Exception) {
        BodyResult.Damaged(ParseError(0L, "fake body"))
    }

    private fun DataOutputStream.str(s: String) {
        writeInt(s.length)
        writeChars(s)
    }

    private fun DataOutputStream.nullable(s: String?) {
        writeBoolean(s != null)
        if (s != null) str(s)
    }

    private fun DataOutputStream.long(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.strings(list: List<String>) {
        writeInt(list.size)
        list.forEach { str(it) }
    }

    private fun DataOutputStream.fields(list: List<CustomField>) {
        writeInt(list.size)
        for (f in list) {
            str(f.name)
            str(f.value)
            writeBoolean(f.secret)
        }
    }

    private fun DataOutputStream.refs(list: List<FileRef>) {
        writeInt(list.size)
        for (r in list) {
            str(r.uri)
            str(r.name)
            writeLong(r.savedAt)
        }
    }

    private fun DataOutputStream.extras(map: Map<String, String>) {
        writeInt(map.size)
        for ((k, v) in map) {
            str(k)
            str(v)
        }
    }

    private fun DataInputStream.count(limit: Int): Int = readInt().also { require(it in 0..limit) { "count" } }

    private fun DataInputStream.str(): String {
        val length = readInt()
        require(length >= 0 && length <= available() / 2) { "string length" }
        val chars = CharArray(length) { readChar() }
        return String(chars)
    }

    private fun DataInputStream.nullable(): String? = if (readBoolean()) str() else null

    private fun DataInputStream.long(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.strings(): List<String> = List(count(available())) { str() }

    private fun DataInputStream.fields(): List<CustomField> = List(count(available())) { CustomField(str(), str(), readBoolean()) }

    private fun DataInputStream.refs(): List<FileRef> = List(count(available())) { FileRef(str(), str(), readLong()) }

    private fun DataInputStream.extras(): Map<String, String> {
        val n = count(available())
        val map = LinkedHashMap<String, String>()
        repeat(n) { map[str()] = str() }
        return map
    }

    companion object {
        /** The cap of §4.1 [V-C4]. */
        const val CAP = 16_777_216

        private const val FAMILY = 6
        private const val BLOCK = 4_096
        private val MAGIC = "PVAULT01".toByteArray(Charsets.US_ASCII)
        private val WRAP = "wrap".toByteArray(Charsets.US_ASCII)
        private val WRAP_TAG = "wrap-tag".toByteArray(Charsets.US_ASCII)
        private val BODY_TAG = "body-tag".toByteArray(Charsets.US_ASCII)
        private val STREAM = "stream".toByteArray(Charsets.US_ASCII)
        private val KEK = "kek".toByteArray(Charsets.US_ASCII)

        private val VERSION = Regex("^\\{\"format\":\"passwordvault\",\"version\":([1-9][0-9]{0,8}),")
        private const val ID = "([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})"
        private val HEADER = Regex(
            "\\{\"format\":\"passwordvault\",\"version\":1,\"cipher\":\"AES-256-GCM\",\"vault_id\":\"$ID\",\"vk_id\":\"$ID\"," +
                "\"generation\":([1-9][0-9]{0,9}),\"created\":(0|[1-9][0-9]{0,15}),\"generator\":\"([A-Za-z0-9 ._()-]{1,64})\"," +
                "\"writer_version\":([1-9][0-9]{0,9}),\"body_len\":([1-9][0-9]{0,9}),\"slots\":\\[\\{\"type\":\"password\"," +
                "\"kdf\":\"argon2id\",\"argon2_version\":19,\"m_kib\":65536,\"t\":3,\"p\":4,\"salt\":\"([A-Za-z0-9+/=]{24})\"," +
                "\"nonce\":\"([A-Za-z0-9+/=]{16})\",\"wrapped_key\":\"([A-Za-z0-9+/=]{64})\"\\}\\]\\}",
        )

        /** The stand-in for Argon2id in these tests: a KEK from [password] and [salt], the same every time. */
        fun kek(password: String, salt: ByteArray): ByteArray = sha256(KEK, password.toByteArray(Charsets.UTF_8), salt)

        /**
         * A file of a later framing (`PVAULT02`, R1) with a good trailer, so a reader of version 1
         * must call it NEWER; with [goodTrailer] false it is DAMAGED.
         */
        fun laterFraming(goodTrailer: Boolean = true): ByteArray {
            val before = "PVAULT02".toByteArray(Charsets.US_ASCII) + ByteArray(64) { it.toByte() }
            val trailer = sha256(before)
            if (!goodTrailer) trailer[0] = (trailer[0].toInt() xor 1).toByte()
            return before + trailer
        }

        private fun trailerOk(bytes: ByteArray): Boolean {
            if (bytes.size < 32) return false
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes, 0, bytes.size - 32)
            return digest.digest().contentEquals(bytes.copyOfRange(bytes.size - 32, bytes.size))
        }

        private fun sha256(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (p in parts) digest.update(p)
            return digest.digest()
        }

        private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

        /** [length] bytes of a stream that only [vk] and [nonce] give. */
        private fun stream(vk: ByteArray, nonce: ByteArray, length: Int): ByteArray {
            val seed = sha256(STREAM, vk, nonce)
            var state = 0L
            for (i in 0 until 8) state = (state shl 8) or (seed[i].toLong() and 0xff)
            val out = ByteArray(length)
            java.util.Random(state).nextBytes(out)
            return out
        }
    }
}
