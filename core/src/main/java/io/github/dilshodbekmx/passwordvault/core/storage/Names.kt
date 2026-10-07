package io.github.dilshodbekmx.passwordvault.core.storage

/**
 * The names of architecture.md §4.7: the vault directories under `<filesDir>/vaults/` and the
 * generation files inside one of them.
 *
 * ```
 * live/                               the vault
 * aside-<13 digits>-<vault_id>/       a vault that was put aside; the digits are the wall-clock ms (display only)
 * new-<vault_id>/                     a vault being created, restored or made by a replacement
 * vault-<10 digits>.pvault | .tmp | .bad | .aside      a generation file inside one of them
 * ```
 *
 * Every number in a name is written with the ASCII digits 0 to 9 by `Long.toString`, never by a
 * locale (§4, R-11), and only ASCII digits are read back: `Char.isDigit` would also take the
 * Arabic-Indic digits of a Persian locale.
 */
internal object Names {
    const val LIVE = "live"

    const val PVAULT = ".pvault"
    const val TMP = ".tmp"
    const val BAD = ".bad"
    const val ASIDE = ".aside"

    /** The largest generation number a header can carry: `[1-9][0-9]{0,9}` (§4.2). */
    const val MAX_GENERATION = 9_999_999_999L

    private const val PREFIX = "vault-"
    private const val DIGITS = 10
    private const val ASIDE_PREFIX = "aside-"
    private const val MS_DIGITS = 13
    private const val MAX_MS = 9_999_999_999_999L
    private const val NEW_PREFIX = "new-"

    /** A vault id in a directory name: the lower-case UUID v4 of lock.md §5.1, nothing else. */
    private val VAULT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    /** `vault-` + [number] in 10 ASCII digits + [suffix] (one of the four constants above). */
    fun generation(number: Long, suffix: String): String {
        require(number in 0..MAX_GENERATION) { "generation out of range" }
        return PREFIX + digits(number, DIGITS) + suffix
    }

    /**
     * The number of any name that starts with `vault-` and ten ASCII digits, whatever follows
     * (§4.9 step 2 [V-S1]: the next number is above all of them); null for every other name.
     */
    fun numberOf(name: String): Long? {
        if (!name.startsWith(PREFIX) || name.length < PREFIX.length + DIGITS) return null
        return parseDigits(name, PREFIX.length, DIGITS)
    }

    /** The number of a name that is exactly `vault-` + 10 digits + [suffix]; null otherwise. */
    fun numberOf(name: String, suffix: String): Long? =
        if (name.length == PREFIX.length + DIGITS + suffix.length && name.endsWith(suffix)) numberOf(name) else null

    /** `aside-<13 digits>-<vaultId>`: [ms] is the wall clock when it was put aside, held within 13 digits. */
    fun asideDir(ms: Long, vaultId: String): String {
        require(isVaultId(vaultId)) { "not a vault id" }
        return ASIDE_PREFIX + digits(ms.coerceIn(0L, MAX_MS), MS_DIGITS) + "-" + vaultId
    }

    /** A directory name of the form [asideDir] writes, read back; null for any other name. */
    fun parseAsideDir(name: String): AsideDir? {
        val idStart = ASIDE_PREFIX.length + MS_DIGITS + 1
        if (!name.startsWith(ASIDE_PREFIX) || name.length != idStart + 36 || name[idStart - 1] != '-') return null
        val ms = parseDigits(name, ASIDE_PREFIX.length, MS_DIGITS) ?: return null
        val vaultId = name.substring(idStart)
        return if (isVaultId(vaultId)) AsideDir(name, ms, vaultId) else null
    }

    /** `new-<vaultId>`. */
    fun newDir(vaultId: String): String {
        require(isVaultId(vaultId)) { "not a vault id" }
        return NEW_PREFIX + vaultId
    }

    /** Whether [name] is a directory that [newDir] makes: removed at process start (§4.7). */
    fun isNewDir(name: String): Boolean = name.startsWith(NEW_PREFIX) && isVaultId(name.substring(NEW_PREFIX.length))

    fun isVaultId(text: String): Boolean = VAULT_ID.matches(text)

    /** [name] inside [dir]. Paths are absolute strings with `/` (FilePrimitives). */
    fun child(dir: String, name: String): String = "$dir/$name"

    /** The directory that holds [path], or null for a path without one. */
    fun parent(path: String): String? {
        val i = path.lastIndexOf('/')
        return if (i > 0) path.substring(0, i) else if (i == 0 && path.length > 1) "/" else null
    }

    /** The last part of [path]. */
    fun nameOf(path: String): String = path.substring(path.lastIndexOf('/') + 1)

    /** A path with its generation suffix replaced: `…/vault-N.tmp` → `…/vault-N.bad`. */
    fun withSuffix(path: String, from: String, to: String): String {
        require(path.endsWith(from)) { "unexpected suffix" }
        return path.substring(0, path.length - from.length) + to
    }

    /** One directory put aside, as its name gives it. */
    class AsideDir(val name: String, val putAsideAtMs: Long, val vaultId: String)

    private fun digits(value: Long, width: Int): String = value.toString().padStart(width, '0')

    private fun parseDigits(text: String, start: Int, count: Int): Long? {
        var value = 0L
        for (i in start until start + count) {
            val c = text[i]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }
}
