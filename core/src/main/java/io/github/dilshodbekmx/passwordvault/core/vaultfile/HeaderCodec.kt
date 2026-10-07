package io.github.dilshodbekmx.passwordvault.core.vaultfile

import io.github.dilshodbekmx.passwordvault.core.util.B64
import io.github.dilshodbekmx.passwordvault.core.util.Uuid

/**
 * The header of a version 1 vault file (architecture.md §4.2) in its one canonical form: rules R5
 * and R7 of §4.4 when reading, and the header of §4.5 rule 1 when writing.
 *
 * The header is ASCII and byte-identical to the template of §4.2 with ten values put in, so a reader
 * needs no JSON parser: one anchored pattern with ten captures, then the numeric and Base64 checks.
 * Duplicate keys, unknown keys, other spellings of a number, other KDF constants and white space are
 * all refused by the same comparison. The stored bytes are the AAD: a reader never writes them again.
 *
 * Every number is written with `Long.toString` and read from ASCII digits only, whatever the default
 * locale (§4, R-11).
 */
internal object HeaderCodec {
    /** The longest header a file may have (R3). */
    const val MAX_LENGTH = 4_096

    /** The highest generation the header can hold: ten digits (§4.2). */
    const val MAX_GENERATION = 9_999_999_999L

    /** The highest `created` the header can hold: sixteen digits (§4.2). */
    const val MAX_CREATED = 9_999_999_999_999_999L

    /** The smallest `body_len`: one block of 4,096 bytes and the tag (§4.2). */
    const val MIN_BODY_LEN = 4_112

    /** What every later version keeps at the start of its header, up to the version number (§4.1, R5). */
    private const val PREFIX = "{\"format\":\"passwordvault\",\"version\":"
    private val PREFIX_BYTES = PREFIX.toByteArray(Charsets.US_ASCII)

    // The template of §4.2, cut at its ten values.
    private const val T0 = PREFIX + "1,\"cipher\":\"AES-256-GCM\",\"vault_id\":\""
    private const val T1 = "\",\"vk_id\":\""
    private const val T2 = "\",\"generation\":"
    private const val T3 = ",\"created\":"
    private const val T4 = ",\"generator\":\""
    private const val T5 = "\",\"writer_version\":"
    private const val T6 = ",\"body_len\":"
    private const val T7 = ",\"slots\":[{\"type\":\"password\",\"kdf\":\"argon2id\",\"argon2_version\":19,\"m_kib\":65536,\"t\":3,\"p\":4,\"salt\":\""
    private const val T8 = "\",\"nonce\":\""
    private const val T9 = "\",\"wrapped_key\":\""
    private const val T10 = "\"}]}"

    private const val UUID = "([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})"
    private const val GENERATOR = "[A-Za-z0-9 ._()-]{1,64}"
    private val GENERATOR_PATTERN = Regex(GENERATOR)

    /** The whole header with its ten captures; the bounds that a pattern cannot say are checked after it. */
    private val HEADER = Regex(
        Regex.escape(T0) + UUID +
            Regex.escape(T1) + UUID +
            Regex.escape(T2) + "([1-9][0-9]{0,9})" +
            Regex.escape(T3) + "(0|[1-9][0-9]{0,15})" +
            Regex.escape(T4) + "($GENERATOR)" +
            Regex.escape(T5) + "([1-9][0-9]{0,9})" +
            Regex.escape(T6) + "(0|[1-9][0-9]{0,9})" +
            Regex.escape(T7) + "([A-Za-z0-9+/=]{24})" +
            Regex.escape(T8) + "([A-Za-z0-9+/=]{16})" +
            Regex.escape(T9) + "([A-Za-z0-9+/=]{64})" +
            Regex.escape(T10),
    )

    /** Whether [generator] may stand in a header (§4.2; the build check R-14 holds the app's own to it). */
    fun isGenerator(generator: String): Boolean = GENERATOR_PATTERN.matches(generator)

    /**
     * R5: the `version` N of a header that starts with `{"format":"passwordvault","version":N,`
     * where N is `[1-9][0-9]{0,8}`, or null when it does not.
     */
    fun version(header: ByteArray): Int? {
        if (header.size < PREFIX_BYTES.size + 2) return null
        for (i in PREFIX_BYTES.indices) if (header[i] != PREFIX_BYTES[i]) return null
        var i = PREFIX_BYTES.size
        if (header[i] !in DIGIT_1..DIGIT_9) return null
        var n = 0
        while (i < header.size && header[i] in DIGIT_0..DIGIT_9 && i - PREFIX_BYTES.size < 9) {
            n = n * 10 + (header[i] - DIGIT_0)
            i++
        }
        return if (i < header.size && header[i] == COMMA) n else null
    }

    /**
     * R7: the values of [header] when it matches §4.2 in full, every bound included, and its
     * `body_len` fits a file of [fileLength] bytes; null otherwise.
     */
    fun read(header: ByteArray, fileLength: Int): VaultHeader? {
        if (header.size > MAX_LENGTH) return null
        // One char per byte: a byte outside ASCII becomes a char that no part of the pattern matches.
        val match = HEADER.matchEntire(String(header, Charsets.ISO_8859_1)) ?: return null
        val v = match.groupValues
        val writerVersion = v[6].toLong()
        if (writerVersion > Int.MAX_VALUE) return null
        val bodyLen = v[7].toLong()
        if (bodyLen != fileLength.toLong() - FIXED_LENGTH - header.size) return null
        if (bodyLen < MIN_BODY_LEN || (bodyLen - TAG_LENGTH) % BLOCK != 0L) return null
        val salt = B64.decode(v[8], SALT_LENGTH) ?: return null
        val nonce = B64.decode(v[9], NONCE_LENGTH) ?: return null
        val wrappedKey = B64.decode(v[10], WRAPPED_KEY_LENGTH) ?: return null
        return VaultHeader(
            vaultId = v[1],
            vkId = v[2],
            generation = v[3].toLong(),
            createdMs = v[4].toLong(),
            generator = v[5],
            writerVersion = writerVersion.toInt(),
            bodyLen = bodyLen.toInt(),
            slot = PasswordSlot(salt, nonce, wrappedKey),
        )
    }

    /**
     * The canonical header of [header] as ASCII bytes (§4.2, §4.5 rule 1).
     *
     * @throws IllegalArgumentException when a value cannot stand in a version 1 header, so that no
     *   file is ever written that this reader would refuse; the message holds no value.
     */
    fun write(header: VaultHeader): ByteArray {
        require(Uuid.isValid(header.vaultId)) { "vault_id is not a lower-case UUID of version 4" }
        require(Uuid.isValid(header.vkId)) { "vk_id is not a lower-case UUID of version 4" }
        require(header.generation in 1..MAX_GENERATION) { "generation is outside 1 to 9,999,999,999" }
        require(header.createdMs in 0..MAX_CREATED) { "created is outside 0 to 9,999,999,999,999,999" }
        require(isGenerator(header.generator)) { "generator does not match [A-Za-z0-9 ._()-]{1,64}" }
        require(header.writerVersion >= 1) { "writer_version is below 1" }
        require(header.bodyLen >= MIN_BODY_LEN && (header.bodyLen - TAG_LENGTH) % BLOCK == 0) {
            "body_len is not 16 bytes more than a multiple of 4,096"
        }
        val slot = header.slot
        require(slot.salt.size == SALT_LENGTH && slot.nonce.size == NONCE_LENGTH && slot.wrappedKey.size == WRAPPED_KEY_LENGTH) {
            "the slot is not 16, 12 and 48 bytes"
        }
        val text = T0 + header.vaultId +
            T1 + header.vkId +
            T2 + header.generation.toString() +
            T3 + header.createdMs.toString() +
            T4 + header.generator +
            T5 + header.writerVersion.toString() +
            T6 + header.bodyLen.toString() +
            T7 + B64.encode(slot.salt) +
            T8 + B64.encode(slot.nonce) +
            T9 + B64.encode(slot.wrappedKey) +
            T10
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= MAX_LENGTH) { "the header is longer than 4,096 bytes" }
        return bytes
    }

    /** Magic, `header_len`, body nonce and trailer: the bytes of a file besides its header and body (§4.1). */
    const val FIXED_LENGTH = 56
    const val BLOCK = 4_096
    const val TAG_LENGTH = 16
    const val SALT_LENGTH = 16
    const val NONCE_LENGTH = 12
    const val WRAPPED_KEY_LENGTH = 48

    private const val DIGIT_0 = '0'.code.toByte()
    private const val DIGIT_1 = '1'.code.toByte()
    private const val DIGIT_9 = '9'.code.toByte()
    private const val COMMA = ','.code.toByte()
}
