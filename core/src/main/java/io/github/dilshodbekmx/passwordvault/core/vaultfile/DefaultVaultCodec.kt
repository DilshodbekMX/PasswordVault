package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import java.security.MessageDigest

/**
 * The vault file of architecture.md §4.1 to §4.6: one container for a vault generation on the phone
 * and for a backup file outside it.
 *
 * ```
 * magic "PVAULT01" ‖ header_len (u32, big-endian) ‖ header ‖ body_nonce (12) ‖ body_ct (body_len) ‖ SHA-256 trailer (32)
 * ```
 *
 * The AAD of the body is magic ‖ `header_len` ‖ header exactly as stored, without the body nonce
 * [V-C3]. The trailer detects damage and authenticates nothing: a body that opens under VK is good
 * even when the trailer is wrong [V-S3].
 *
 * [check] is rules R1 to R7 of §4.4 (no key), [open] R8 to R10 (with VK), [seal] and [wrap] the
 * writer rules of §4.5. [HeaderCodec] holds the header's text and [BodyCodec] the body's JSON. Every
 * nonce comes from [random] and the `created` time from [clock]; the AES-256-GCM is [aead].
 * `VaultCodecs.create` makes the one instance; nothing else names this class.
 */
internal class DefaultVaultCodec(
    private val aead: Aead,
    private val random: RandomSource,
    private val clock: Clock,
    private val build: BuildInfo,
) : VaultCodec {
    init {
        require(HeaderCodec.isGenerator(build.generator)) { "BuildInfo.generator does not match [A-Za-z0-9 ._()-]{1,64} (R-14)" }
        require(build.writerVersion >= 1) { "BuildInfo.writerVersion is below 1" }
    }

    override fun check(bytes: ByteArray): FileCheck {
        // R1
        if (bytes.size < MAGIC.size) return FileCheck.NotOurs
        for (i in 0 until FAMILY_LENGTH) if (bytes[i] != MAGIC[i]) return FileCheck.NotOurs
        val tens = digit(bytes[FAMILY_LENGTH])
        val units = digit(bytes[FAMILY_LENGTH + 1])
        if (tens < 0 || units < 0) return FileCheck.NotOurs
        if (bytes[FAMILY_LENGTH] != MAGIC[FAMILY_LENGTH] || bytes[FAMILY_LENGTH + 1] != MAGIC[FAMILY_LENGTH + 1]) {
            // Another framing of the family: newer only when the trailer says nothing was damaged.
            return if (bytes.size >= NEWER_FRAMING_MIN && trailerOk(bytes)) FileCheck.Newer(tens * 10 + units) else FileCheck.Damaged(1)
        }
        // R2
        if (bytes.size < MIN_FILE || bytes.size > MAX_FILE) return FileCheck.Damaged(2)
        // R3
        val headerLen = ((bytes[8].toLong() and 0xFF) shl 24) or ((bytes[9].toLong() and 0xFF) shl 16) or
            ((bytes[10].toLong() and 0xFF) shl 8) or (bytes[11].toLong() and 0xFF)
        if (headerLen < MIN_HEADER || headerLen > HeaderCodec.MAX_LENGTH ||
            HeaderCodec.FIXED_LENGTH + headerLen + HeaderCodec.TAG_LENGTH > bytes.size
        ) {
            return FileCheck.Damaged(3)
        }
        val aadLength = PREAMBLE + headerLen.toInt()
        // R4
        val trailerOk = trailerOk(bytes)
        // R5
        val header = bytes.copyOfRange(PREAMBLE, aadLength)
        val version = HeaderCodec.version(header) ?: return FileCheck.Damaged(5)
        // R6
        if (version != 1) return if (trailerOk) FileCheck.Newer(version) else FileCheck.Damaged(6)
        // R7
        val values = HeaderCodec.read(header, bytes.size) ?: return FileCheck.Damaged(7)
        val bodyStart = aadLength + HeaderCodec.NONCE_LENGTH
        return FileCheck.Ok(
            VaultFile(
                header = values,
                aad = bytes.copyOfRange(0, aadLength),
                bodyNonce = bytes.copyOfRange(aadLength, bodyStart),
                bodyCt = bytes.copyOfRange(bodyStart, bodyStart + values.bodyLen),
                trailerOk = trailerOk,
            ),
        )
    }

    override fun unwrap(slot: PasswordSlot, kek: ByteArray): ByteArray? {
        require(kek.size == KEY_LENGTH) { "a KEK is 32 bytes" }
        if (slot.nonce.size != HeaderCodec.NONCE_LENGTH || slot.wrappedKey.size != HeaderCodec.WRAPPED_KEY_LENGTH) return null
        val vk = aead.open(kek, slot.nonce, slot.wrappedKey, null) ?: return null
        if (vk.size != KEY_LENGTH) {
            Bytes.wipe(vk)
            return null
        }
        return vk
    }

    override fun open(file: VaultFile, vk: ByteArray): BodyResult {
        require(vk.size == KEY_LENGTH) { "a VK is 32 bytes" }
        if (file.bodyNonce.size != HeaderCodec.NONCE_LENGTH || file.bodyCt.size < HeaderCodec.TAG_LENGTH) return BodyResult.TagFailed
        // R8
        val plaintext = aead.open(vk, file.bodyNonce, file.bodyCt, file.aad) ?: return BodyResult.TagFailed
        try {
            // R9, R10
            return BodyCodec.read(plaintext)
        } finally {
            Bytes.wipe(plaintext)
        }
    }

    override fun seal(vault: Vault, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray {
        val json = BodyCodec.write(vault)
        try {
            return sealJson(json, vk, vaultId, vkId, generation, slot)
        } finally {
            Bytes.wipe(json)
        }
    }

    override fun wrap(vk: ByteArray, kek: ByteArray, salt: ByteArray): PasswordSlot {
        require(vk.size == KEY_LENGTH) { "a VK is 32 bytes" }
        require(kek.size == KEY_LENGTH) { "a KEK is 32 bytes" }
        require(salt.size == HeaderCodec.SALT_LENGTH) { "a salt is 16 bytes" }
        val nonce = random.bytes(HeaderCodec.NONCE_LENGTH)
        val wrappedKey = aead.seal(kek, nonce, vk, null)
        check(wrappedKey.size == HeaderCodec.WRAPPED_KEY_LENGTH) { "the AEAD gave a wrapped key that is not 48 bytes" }
        return PasswordSlot(salt.copyOf(), nonce, wrappedKey)
    }

    /**
     * A whole file around the body text [json], padded here (§4.3); [seal] passes the canonical body.
     * The container vector A of §4.13 is made this way from its given plaintext.
     */
    internal fun sealJson(json: ByteArray, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray {
        val plaintext = BodyCodec.pad(json)
        try {
            return sealPadded(plaintext, vk, vaultId, vkId, generation, slot)
        } finally {
            Bytes.wipe(plaintext)
        }
    }

    /**
     * A whole file around [plaintext], which is already a whole number of blocks of 4,096 bytes:
     * the canonical header with `created` from the clock and the generator and writer version from
     * [build]; a fresh body nonce; the body sealed under [vk] with the AAD; the trailer (§4.5).
     *
     * `created` is the wall clock as it is, held within the 0 to 16 digits the header can carry: a
     * clock set before 1970 must not make a file that this reader refuses (it is for display only).
     */
    internal fun sealPadded(plaintext: ByteArray, vk: ByteArray, vaultId: String, vkId: String, generation: Long, slot: PasswordSlot): ByteArray {
        require(vk.size == KEY_LENGTH) { "a VK is 32 bytes" }
        require(plaintext.isNotEmpty() && plaintext.size % BodyCodec.BLOCK == 0) { "the plaintext is not whole blocks of 4,096 bytes" }
        val header = HeaderCodec.write(
            VaultHeader(
                vaultId = vaultId,
                vkId = vkId,
                generation = generation,
                createdMs = clock.wallMs().coerceIn(0L, HeaderCodec.MAX_CREATED),
                generator = build.generator,
                writerVersion = build.writerVersion,
                bodyLen = plaintext.size + HeaderCodec.TAG_LENGTH,
                slot = slot,
            ),
        )
        val aad = ByteArray(PREAMBLE + header.size)
        MAGIC.copyInto(aad)
        aad[8] = (header.size ushr 24).toByte()
        aad[9] = (header.size ushr 16).toByte()
        aad[10] = (header.size ushr 8).toByte()
        aad[11] = header.size.toByte()
        header.copyInto(aad, PREAMBLE)
        val nonce = random.bytes(HeaderCodec.NONCE_LENGTH)
        val bodyCt = aead.seal(vk, nonce, plaintext, aad)
        check(bodyCt.size == plaintext.size + HeaderCodec.TAG_LENGTH) { "the AEAD gave a ciphertext of another length" }
        val file = ByteArray(aad.size + nonce.size + bodyCt.size + TRAILER_LENGTH)
        aad.copyInto(file)
        nonce.copyInto(file, aad.size)
        bodyCt.copyInto(file, aad.size + nonce.size)
        val trailer = sha256(file, file.size - TRAILER_LENGTH)
        trailer.copyInto(file, file.size - TRAILER_LENGTH)
        return file
    }

    private companion object {
        /** `PVAULT01`: the family `PVAULT` and the framing `01` (§4.1). */
        val MAGIC = "PVAULT01".toByteArray(Charsets.US_ASCII)
        const val FAMILY_LENGTH = 6

        /** Magic and `header_len`: where the header starts. */
        const val PREAMBLE = 12
        const val MIN_HEADER = 2
        const val TRAILER_LENGTH = 32
        const val KEY_LENGTH = 32

        /** R2: 56 + a header of 2 bytes + a body of the tag alone; the cap [V-C4]. */
        const val MIN_FILE = 74
        const val MAX_FILE = 16_777_216

        /** R1: a file of another framing must hold at least its magic and a trailer. */
        const val NEWER_FRAMING_MIN = 40

        fun digit(b: Byte): Int = if (b in '0'.code.toByte()..'9'.code.toByte()) b - '0'.code.toByte() else -1

        /** R4: whether the last 32 bytes are the SHA-256 of all the bytes before them. */
        fun trailerOk(bytes: ByteArray): Boolean {
            if (bytes.size < TRAILER_LENGTH) return false
            val end = bytes.size - TRAILER_LENGTH
            val digest = sha256(bytes, end)
            var diff = 0
            for (i in 0 until TRAILER_LENGTH) diff = diff or (digest[i].toInt() xor bytes[end + i].toInt())
            return diff == 0
        }

        fun sha256(bytes: ByteArray, length: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest()
    }
}
