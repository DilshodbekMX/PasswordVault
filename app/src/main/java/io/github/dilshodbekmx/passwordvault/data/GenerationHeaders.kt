package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.storage.VaultRef
import io.github.dilshodbekmx.passwordvault.core.vaultfile.FileCheck
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultCodec
import java.io.IOException

/**
 * The `created` time of one generation as its header gives it (architecture.md §4.2), for the
 * `createdMs` of `VaultOpen.Opened`, `PasswordOpen.Opened` and `VaultWrite.Done` (§3.4): the date
 * the older-copy notice names ("We opened the copy from 5 October, 18:42", ux.md §5.9).
 *
 * `OpenedVault` (§3.3) does not carry it, so the repository reads the header of the generation file
 * itself, by the names of §4.7: `vault-` and the number in ten ASCII digits and `.pvault`, in
 * `live/` or in the newest `aside-<13 digits>-<vault_id>/` of that vault. This is read only: the
 * file is read with the cap of §4.1, checked with `VaultCodec.check` and nothing else. The answer
 * counts only when the header names the vault, key and generation that were opened; otherwise, or
 * when the file cannot be read now, it is null. Package 3.1 asked for `OpenedVault.createdMs` in its
 * place (an interface request); this class goes when that is merged.
 *
 * Callers hold the vault mutex, as for every look at the vault directories (§5.2).
 */
internal class GenerationHeaders(private val files: FilePrimitives, private val codec: VaultCodec, vaultsDir: String) {
    private val vaultsDir = vaultsDir.trimEnd('/')

    /** The `created` of generation [generation] of [vaultId] under [vkId] in [target]'s directory, or null. */
    fun createdMs(target: VaultRef, vaultId: String, vkId: String, generation: Long): Long? {
        if (generation !in 1..MAX_GENERATION) return null
        val dir = dirOf(target) ?: return null
        val bytes = try {
            files.read("$dir/" + fileName(generation), CAP)
        } catch (e: IOException) {
            return null
        }
        if (bytes.size > CAP) return null
        val header = (codec.check(bytes) as? FileCheck.Ok)?.file?.header ?: return null
        return if (header.vaultId == vaultId && header.vkId == vkId && header.generation == generation) header.createdMs else null
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
        /** The largest vault file, 16,777,216 bytes (§4.1, [V-C4]); a read takes at most one byte more. */
        const val CAP = 16_777_216

        private const val LIVE = "live"
        private const val ASIDE = "aside-"
        private const val MS_DIGITS = 13
        private const val MAX_GENERATION = 9_999_999_999L

        /** `vault-` + [generation] in ten ASCII digits + `.pvault` (§4.7): written by `Long.toString`, never by a locale. */
        fun fileName(generation: Long): String = "vault-" + generation.toString().padStart(10, '0') + ".pvault"

        /** Whether [name] is `aside-` + 13 ASCII digits + `-` + [vaultId]. */
        fun isAsideDirOf(name: String, vaultId: String): Boolean {
            if (name.length != ASIDE.length + MS_DIGITS + 1 + vaultId.length || !name.startsWith(ASIDE) || !name.endsWith("-$vaultId")) return false
            for (i in ASIDE.length until ASIDE.length + MS_DIGITS) if (name[i] !in '0'..'9') return false
            return true
        }
    }
}
