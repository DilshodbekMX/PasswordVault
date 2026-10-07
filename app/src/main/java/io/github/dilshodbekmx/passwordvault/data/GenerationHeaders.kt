package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef
import java.io.IOException

/**
 * The `created` time of one generation as its header gives it (architecture.md §4.2), for the
 * `createdMs` of `VaultOpen.Opened`, `PasswordOpen.Opened` and `VaultWrite.Done` (§3.4): the date
 * the older-copy notice names ("We opened the copy from 5 October, 18:42", ux.md §5.9).
 *
 * `OpenedVault` (§3.3) does not carry it, so the repository reads it from the file itself, by the
 * names of §4.7: `vault-` and the number in ten ASCII digits and `.pvault`, in `live/` or in the
 * newest `aside-<13 digits>-<vault_id>/` of that vault. Only the start of the file is read (the
 * magic, `header_len` and at most 4,096 bytes of header, §4.1), and the header is version 1's
 * canonical text (§4.2, byte for byte), so the answer is taken only when the header starts with the
 * exact text that names the vault, key and generation that were opened, followed by `"created":`
 * and an integer. Otherwise, or when the file cannot be read now, it is null. Read only; the store
 * has just opened the same file under its key.
 *
 * Package 3.1 asked for `OpenedVault.createdMs` in its place (an interface request); this class goes
 * when that is merged. Callers hold the vault mutex, as for every look at the vault directories (§5.2).
 */
internal class GenerationHeaders(private val files: FilePrimitives, vaultsDir: String) {
    private val vaultsDir = vaultsDir.trimEnd('/')

    /** The `created` of generation [generation] of [vaultId] under [vkId] in [target]'s directory, or null. */
    fun createdMs(target: VaultRef, vaultId: String, vkId: String, generation: Long): Long? {
        if (generation !in 1..MAX_GENERATION) return null
        val dir = dirOf(target) ?: return null
        val start = try {
            files.read("$dir/" + fileName(generation), PREAMBLE + MAX_HEADER)
        } catch (e: IOException) {
            return null
        }
        return createdIn(start, vaultId, vkId, generation)
    }

    private fun dirOf(target: VaultRef): String? = when (target) {
        VaultRef.Current -> "$vaultsDir/$LIVE"
        is VaultRef.Aside -> asideDirOf(target.vaultId)
    }

    /** The newest `aside-<ms>-<vaultId>/`, as the store chooses it: the highest ms, then the name. */
    private fun asideDirOf(vaultId: String): String? {
        val names = try {
            files.list(vaultsDir)
        } catch (e: IOException) {
            null
        } ?: return null
        return names.filter { isAsideDirOf(it, vaultId) }.maxWithOrNull(compareBy<String> { it.substring(ASIDE.length, ASIDE.length + MS_DIGITS) }.thenBy { it })
            ?.let { "$vaultsDir/$it" }
    }

    internal companion object {
        private const val LIVE = "live"
        private const val ASIDE = "aside-"
        private const val MS_DIGITS = 13
        private const val MAX_GENERATION = 9_999_999_999L

        /** The magic and `header_len` (§4.1). */
        private const val PREAMBLE = 12

        /** The longest header (§4.1). */
        private const val MAX_HEADER = 4_096

        private val MAGIC = "PVAULT01".encodeToByteArray()

        /** `vault-` + [generation] in ten ASCII digits + `.pvault` (§4.7): written by `Long.toString`, never by a locale. */
        fun fileName(generation: Long): String = "vault-" + generation.toString().padStart(10, '0') + ".pvault"

        /** Whether [name] is `aside-` + 13 ASCII digits + `-` + [vaultId]. */
        fun isAsideDirOf(name: String, vaultId: String): Boolean {
            if (name.length != ASIDE.length + MS_DIGITS + 1 + vaultId.length || !name.startsWith(ASIDE) || !name.endsWith("-$vaultId")) return false
            for (i in ASIDE.length until ASIDE.length + MS_DIGITS) if (name[i] !in '0'..'9') return false
            return true
        }

        /**
         * The `created` of the canonical version 1 header at the start of [bytes] (§4.1, §4.2) when it
         * names [vaultId], [vkId] and [generation]: `0` or up to 16 ASCII digits without a leading
         * zero. Null for anything else.
         */
        fun createdIn(bytes: ByteArray, vaultId: String, vkId: String, generation: Long): Long? {
            if (bytes.size < PREAMBLE) return null
            for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
            val headerLen = ((bytes[8].toInt() and 0xFF) shl 24) or ((bytes[9].toInt() and 0xFF) shl 16) or
                ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)
            if (headerLen !in 2..MAX_HEADER || bytes.size < PREAMBLE + headerLen) return null
            val header = String(bytes, PREAMBLE, headerLen, Charsets.US_ASCII)
            val prefix = "{\"format\":\"passwordvault\",\"version\":1,\"cipher\":\"AES-256-GCM\",\"vault_id\":\"" + vaultId +
                "\",\"vk_id\":\"" + vkId + "\",\"generation\":" + generation.toString() + ",\"created\":"
            if (!header.startsWith(prefix)) return null
            val end = header.indexOf(',', prefix.length)
            if (end < 0) return null
            val digits = header.substring(prefix.length, end)
            if (digits.isEmpty() || digits.length > 16 || digits.any { it !in '0'..'9' } || (digits.length > 1 && digits[0] == '0')) return null
            return digits.toLong()
        }
    }
}
