package io.github.dilshodbekmx.passwordvault.core.util

/**
 * The ids of the app (architecture.md §2.1): `vault_id`, `vk_id`, the slot ids of lock.md §4.3 and
 * the entry ids are random UUIDs of version 4 in lower case. [make] writes one, [isValid] checks
 * one, [uuid16] gives the 16 bytes that lock.md §4.3 puts into its messages and AADs.
 */
object Uuid {
    /**
     * The form every id must have before it is used (lock.md §5.1, architecture.md §4.2): a
     * `vault_id` becomes a directory name, so nothing else is accepted, not even upper case.
     */
    val PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    /**
     * A UUID of version 4 from 16 random bytes (RFC 9562 §5.4): the version bits and the variant
     * bits are set, the other 122 bits are taken from [random16], which is left unchanged.
     */
    fun make(random16: ByteArray): String {
        require(random16.size == 16) { "a UUID takes 16 random bytes" }
        val b = random16.copyOf()
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = Bytes.hex(b)
        Bytes.wipe(b)
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
            hex.substring(16, 20) + "-" + hex.substring(20, 32)
    }

    /** Whether [text] is a UUID of version 4 in lower case, with nothing before or after it. */
    fun isValid(text: String): Boolean = PATTERN.matches(text)

    /**
     * `uuid16(u)` of lock.md §4.3: the 16 bytes of [uuid], most significant byte first.
     *
     * @throws IllegalArgumentException when [uuid] is not [isValid]; the message holds no input.
     */
    fun uuid16(uuid: String): ByteArray {
        require(isValid(uuid)) { "not a lower-case UUID of version 4" }
        return Bytes.fromHex(uuid.replace("-", ""))
    }
}
