package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.HeaderCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import java.util.Locale

/**
 * Decides, against the vault, what an import adds (formats.md §8.4, ux.md §5.21), from what
 * [Importer.read] made of the file. Pure: it reads the vault and changes nothing. The preview shows
 * the [ImportPlan]; "Import 45 accounts" then saves `VaultEdits.addImported(plan.entries, random,
 * texts::fromFile)` as one generation, and "Undo this import" puts back the vault as it was before
 * it (`Imported.before`, D-15).
 *
 * In this order:
 * 1. **Rows with the wrong number of values** are left out unless the user confirmed them
 *    ([Choices.includeWrongLength], formats-review P4).
 * 2. **One login on related websites** (ux.md §5.21, D-10b). Chrome writes one row per affiliated
 *    website, so rows with the same username, password, notes and fields are one account with all
 *    their websites when (b) one is an Android app and the other a website, and the app's package
 *    belongs to the site (com.instagram.android ↔ instagram.com), or (c) both websites belong to one
 *    of the fixed sign-on families: Google (google.com, youtube.com, gmail.com), Microsoft (live.com,
 *    outlook.com, hotmail.com, microsoft.com), Yandex (yandex.ru, yandex.uz, yandex.com, ya.ru),
 *    Apple (apple.com, icloud.com). Rule (a), adjacent rows, is the reader's. Nothing else is joined:
 *    Facebook and Instagram stay apart. An account takes at most 10 websites, then a new one starts
 *    (formats-review P5), and a row whose own name is not just its website's is not joined, so that
 *    no name the user gave is lost. Only CSV rows are joined, not Bitwarden's, whose rows are items
 *    with their own lists of websites, and not JSON items. "Keep them separate" turns this off
 *    ([Choices.keepSeparate]).
 * 3. **Tidy names** ([Choices.tidyNames], on by default): [tidyName].
 * 4. **Repeats and conflicts** (formats.md §8.4). An account is *already here* when an account of the
 *    vault outside Recently deleted has the same username, a website host in common (or, when neither
 *    has a website, the same name), the same password, and nothing that the incoming one would add
 *    in its notes, its extra fields or a pending change's old password: it is skipped. The same within
 *    the file: an exact repeat of an account already planned is imported once. An account with the
 *    same website and username as one in the vault but another password is added beside it "so you
 *    can compare" ([ImportPlan.conflicts]); nothing combines the two. An account of our own JSON whose
 *    id an account of the vault holds is added only when it differs from it in this sense, and then
 *    `VaultEdits.addImported` gives it a new id and the name "Gmail (from file)" (formats-review P1).
 * 5. **The size** (architecture.md §3.5): the vault file after the import, estimated from the vault
 *    `VaultEdits.addImported` would make, sealed with the longest header a vault file can have. An
 *    import that would not fit ([ImportPlan.fits] false) is refused with "Your passwords no longer fit
 *    in one file. …".
 *
 * **Speed** (formats-review P16): the vault and the accounts already planned are indexed by
 * (username, host) in hash maps, and each account's hosts are worked out once, so that the time grows
 * with the sizes added, not multiplied.
 */
object ImportPlanner {
    /** The largest vault file (architecture.md §4.1, [V-C4]). */
    const val MAX_FILE_BYTES: Long = 16_777_216L

    /** What the user chose on the preview (ux.md §5.21). */
    class Choices(
        /** The rows with the wrong number of values are imported as they are (formats-review P4). */
        val includeWrongLength: Boolean = false,
        /** "Keep them separate": rules (b) and (c) join nothing. */
        val keepSeparate: Boolean = false,
        /** "Tidy names", on by default. */
        val tidyNames: Boolean = true,
    )

    /**
     * The plan for importing [file] into [vault]. [fromFile] is `EditTexts.fromFile`, the name of an
     * account of our own JSON that is added beside the account holding its id; the size estimate
     * needs it. Never changes [vault].
     */
    fun plan(vault: Vault, file: ImportFile, fromFile: (title: String) -> String, choices: Choices = Choices()): ImportPlan {
        val confirmed = if (choices.includeWrongLength) file.accounts else file.accounts.filter { it.wrongLength == null }
        val joined = if (file.format.isCsv && file.format != Importer.Format.BITWARDEN_CSV && !choices.keepSeparate) {
            Related.join(confirmed)
        } else {
            Related.Joined(confirmed, 0, 0)
        }

        var accounts = joined.accounts
        var tidied = 0
        val examples = ArrayList<TidyExample>(2)
        if (choices.tidyNames) {
            accounts = accounts.map { a ->
                val tidy = tidyName(a.entry.title)
                if (tidy == a.entry.title) {
                    a
                } else {
                    tidied++
                    if (examples.size < 2) examples.add(TidyExample(a.entry.title, tidy))
                    a.renamed(tidy)
                }
            }
        }

        // The vault's accounts outside Recently deleted, by login.
        val live = vault.entries.filter { it.deletedAt == null }
        val inVault = HashMap<String, MutableList<Entry>>(live.size * 2)
        for (e in live) for (k in loginKeys(e)) inVault.getOrPut(k) { ArrayList(1) }.add(e)
        val inFile = HashMap<String, MutableList<Entry>>(accounts.size * 2)
        val added = ArrayList<ImportAccount>(accounts.size)
        val conflicts = ArrayList<ImportConflict>()
        var alreadyHere = 0
        var repeated = 0
        for (a in accounts) {
            val e = a.entry
            val keys = loginKeys(e)
            var here = false
            var other: Entry? = null
            here@ for (k in keys) {
                for (v in inVault[k].orEmpty()) {
                    if (covers(v, e)) {
                        here = true
                        break@here
                    }
                    if (other == null && v.password != e.password) other = v
                }
            }
            if (here) {
                alreadyHere++
                continue
            }
            if (keys.any { k -> inFile[k].orEmpty().any { covers(it, e) } }) {
                repeated++
                continue
            }
            other?.let { conflicts.add(ImportConflict(added.size, it.id)) }
            added.add(a)
            for (k in keys) inFile.getOrPut(k) { ArrayList(1) }.add(e)
        }

        return ImportPlan(
            accounts = added,
            found = accounts.size,
            alreadyHere = alreadyHere,
            repeatedInFile = repeated,
            conflicts = conflicts,
            withoutPassword = added.count { it.entry.password.isEmpty() },
            wrongLengthLeftOut = file.accounts.size - confirmed.size,
            relatedRows = joined.rows,
            relatedAccounts = joined.joinedAccounts,
            tidied = tidied,
            tidyExamples = examples,
            sealedBytes = sealedSize(vault, added.map { it.entry }, fromFile),
        )
    }

    // ------------------------------------------------------------------ tidy names

    private val HOST_LIKE = Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+$")
    private val PREFIXES = listOf("www.", "m.", "mobile.", "accounts.", "account.", "login.", "passport.")
    private val ENDINGS = listOf(".com", ".ru", ".uz", ".org", ".net")

    /**
     * "Tidy names" of ux.md §5.21, for a name that is a host name, as Chrome makes them up: strip
     * "www.", "m.", "mobile.", "accounts.", "account.", "login." and "passport."; then drop ".com",
     * ".ru", ".uz", ".org" or ".net" when one word is left, and capitalise that word. So
     * accounts.google.com is Google, m.facebook.com Facebook and passport.yandex.ru Yandex, while
     * my.gov.uz stays my.gov.uz. Any other name is returned as it is.
     */
    fun tidyName(title: String): String {
        val name = title.trim()
        if (!HOST_LIKE.matches(name)) return title
        var host = name.lowercase(Locale.ROOT)
        var stripped = true
        while (stripped) {
            stripped = false
            for (p in PREFIXES) {
                if (host.startsWith(p) && host.indexOf('.', p.length) > 0) {
                    host = host.substring(p.length)
                    stripped = true
                }
            }
        }
        for (ending in ENDINGS) {
            if (host.endsWith(ending)) {
                val word = host.substring(0, host.length - ending.length)
                if (word.isNotEmpty() && '.' !in word) return word.substring(0, 1).uppercase(Locale.ROOT) + word.substring(1)
            }
        }
        return if (host == name.lowercase(Locale.ROOT)) title else host
    }

    // ------------------------------------------------------------------ repeats and conflicts

    private const val SEPARATOR = '\u0000'
    private const val NO_WEBSITE = '\u0001'

    /** The logins of [e]: its trimmed username with each of its hosts, or with its name when it has no website. */
    private fun loginKeys(e: Entry): List<String> {
        val user = e.username.trim()
        val hosts = ArrayList<String>(e.urls.size)
        for (url in e.urls) {
            val host = siteHost(url)
            if (host.isNotEmpty() && host !in hosts) hosts.add(host)
        }
        if (hosts.isEmpty()) return listOf(user + SEPARATOR + NO_WEBSITE + e.title.trim().lowercase(Locale.ROOT))
        return hosts.map { user + SEPARATOR + it }
    }

    /** The host of a website, or `app:` and the package of an Android app. */
    private fun siteHost(url: String): String {
        val host = WebAddress.hostOf(url)
        return if (Related.isApp(url) && host.isNotEmpty()) "app:$host" else host
    }

    /** [existing] already holds what [incoming] brings: the same password, and its notes, fields and old password. */
    private fun covers(existing: Entry, incoming: Entry): Boolean =
        existing.password == incoming.password &&
            (incoming.notes.isBlank() || existing.notes.contains(incoming.notes.trim())) &&
            existing.fields.containsAll(incoming.fields) &&
            (incoming.pendingOldPassword == null || incoming.pendingOldPassword == existing.pendingOldPassword)

    // ------------------------------------------------------------------ the size

    /** The longest header a vault file can have: every number at its widest (architecture.md §4.2). */
    private val MAX_HEADER: Int by lazy {
        val id = "ffffffff-ffff-4fff-bfff-ffffffffffff"
        HeaderCodec.write(
            VaultHeader(
                vaultId = id,
                vkId = id,
                generation = HeaderCodec.MAX_GENERATION,
                createdMs = HeaderCodec.MAX_CREATED,
                generator = "A".repeat(64),
                writerVersion = Int.MAX_VALUE,
                bodyLen = (MAX_FILE_BYTES.toInt() / HeaderCodec.BLOCK - 1) * HeaderCodec.BLOCK + HeaderCodec.TAG_LENGTH,
                slot = PasswordSlot(ByteArray(HeaderCodec.SALT_LENGTH), ByteArray(HeaderCodec.NONCE_LENGTH), ByteArray(HeaderCodec.WRAPPED_KEY_LENGTH)),
            ),
        ).size
    }

    /**
     * The size of the vault file that saving [vault] with [entries] added would write, at most: the
     * vault `VaultEdits.addImported` makes (new ids are as long as old ones; "(from file)" names are
     * counted), its canonical body padded to whole blocks, and the longest header.
     */
    internal fun sealedSize(vault: Vault, entries: List<Entry>, fromFile: (String) -> String): Long {
        val after = if (entries.isEmpty()) vault else VaultEdits.addImported(entries, SizingIds(), fromFile)(vault, 0L).vault
        return sealedSize(after)
    }

    /** The size of the vault file [vault] would be, at most. */
    internal fun sealedSize(vault: Vault): Long {
        val body = BodyCodec.write(vault)
        try {
            val padded = (body.size.toLong() + HeaderCodec.BLOCK - 1) / HeaderCodec.BLOCK * HeaderCodec.BLOCK
            return HeaderCodec.FIXED_LENGTH + MAX_HEADER + padded + HeaderCodec.TAG_LENGTH
        } finally {
            Bytes.wipe(body)
        }
    }

    /** Ids for the size estimate only: distinct and valid, never random, never a real entry's. */
    private class SizingIds : RandomSource {
        private var next = 0L

        override fun bytes(n: Int): ByteArray = ByteArray(n)

        override fun int(bound: Int): Int = 0

        override fun uuid(): String {
            val hex = (next++).toString(16).padStart(12, '0')
            return "ffffffff-ffff-4fff-bfff-$hex"
        }
    }

    // ------------------------------------------------------------------ rules (b) and (c)

    /** The joins of rules (b) and (c) of ux.md §5.21. */
    internal object Related {
        /** The accounts after joining; [rows] rows were kept together as [joinedAccounts] accounts. */
        class Joined(val accounts: List<ImportAccount>, val rows: Int, val joinedAccounts: Int)

        private val FAMILIES: List<Pair<String, List<String>>> = listOf(
            "google" to listOf("google.com", "youtube.com", "gmail.com"),
            "microsoft" to listOf("live.com", "outlook.com", "hotmail.com", "microsoft.com"),
            "yandex" to listOf("yandex.ru", "yandex.uz", "yandex.com", "ya.ru"),
            "apple" to listOf("apple.com", "icloud.com"),
        )

        /** Second-level labels under which a country's names are registered (`my.gov.uz`, `bbc.co.uk`). */
        private val GENERIC = setOf("com", "co", "org", "net", "gov", "edu", "ac", "mil")

        fun isApp(url: String): Boolean = url.startsWith("android://", ignoreCase = true) || url.startsWith("androidapp://", ignoreCase = true)

        private fun isWeb(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        /** The site an app's package names: com.instagram.android → instagram.com, uz.gov.my → my.gov.uz. */
        fun siteOfPackage(pkg: String): String? {
            val labels = pkg.split('.').filter { it.isNotEmpty() }
            if (labels.size < 2) return null
            return if (labels.size >= 3 && labels[1] in GENERIC) labels[2] + "." + labels[1] + "." + labels[0] else labels[1] + "." + labels[0]
        }

        /** The registered name of a host: mail.google.com → google.com, my.gov.uz → my.gov.uz. */
        fun siteOfHost(host: String): String {
            val labels = host.split('.')
            val n = labels.size
            if (n < 2) return host
            return if (n >= 3 && labels[n - 2] in GENERIC) labels.subList(n - 3, n).joinToString(".") else labels[n - 2] + "." + labels[n - 1]
        }

        private fun familyOf(host: String): String? =
            FAMILIES.firstOrNull { (_, domains) -> domains.any { host == it || host.endsWith(".$it") } }?.first

        /** What makes two accounts related: `app:`, `web:` and `fam:` tokens of their websites. */
        fun tokens(e: Entry): Set<String> {
            val out = HashSet<String>()
            for (url in e.urls) {
                if (isApp(url)) {
                    val site = siteOfPackage(WebAddress.hostOf(url)) ?: continue
                    out.add("app:$site")
                    familyOf(site)?.let { out.add("fam:$it") }
                } else if (isWeb(url)) {
                    val host = WebAddress.hostOf(url)
                    if (host.isEmpty()) continue
                    out.add("web:" + siteOfHost(host))
                    familyOf(host)?.let { out.add("fam:$it") }
                }
            }
            return out
        }

        /** The tokens of another account that make it related to one with [token]: (b) app ↔ web, (c) the family. */
        private fun partner(token: String): String = when {
            token.startsWith("app:") -> "web:" + token.substring(4)
            token.startsWith("web:") -> "app:" + token.substring(4)
            else -> token
        }

        /** The names an account's websites would give it: the host, with "www.", tidied, and an app's package and site. */
        private fun siteNames(e: Entry): Set<String> {
            val names = HashSet<String>()
            for (url in e.urls) {
                val host = WebAddress.hostOf(url)
                if (host.isEmpty()) continue
                names.add(host)
                names.add("www.$host")
                names.add(tidyName(host).lowercase(Locale.ROOT))
                if (isApp(url)) siteOfPackage(host)?.let { names.add(it); names.add(tidyName(it).lowercase(Locale.ROOT)) }
            }
            return names
        }

        /** Whether [a]'s name may give way to another's: the importer made it, or it is only its website's. */
        private fun nameIsOnlyItsSite(a: ImportAccount): Boolean =
            !a.titleFromFile || a.entry.title.trim().lowercase(Locale.ROOT) in siteNames(a.entry)

        private fun group(e: Entry): String {
            val sb = StringBuilder()
            sb.append(e.username.trim()).append(SEPARATOR).append(e.password).append(SEPARATOR).append(e.notes).append(SEPARATOR)
            for (f in e.fields) sb.append(f.name).append(NO_WEBSITE).append(f.secret).append(NO_WEBSITE).append(f.value).append(SEPARATOR)
            sb.append(SEPARATOR).append(e.pendingOldPassword ?: "")
            return sb.toString()
        }

        private class Open(var account: ImportAccount, val tokens: MutableSet<String>, var absorbed: Int = 0)

        fun join(accounts: List<ImportAccount>): Joined {
            val open = ArrayList<Open>(accounts.size)
            // group + token → the open accounts holding that token, in order
            val index = HashMap<String, MutableList<Int>>()
            for (a in accounts) {
                val e = a.entry
                val tokens = if (e.password.isEmpty() || a.wrongLength != null) emptySet() else tokens(e)
                if (tokens.isEmpty()) {
                    open.add(Open(a, HashSet()))
                    continue
                }
                val g = group(e)
                var target = -1
                for (t in tokens) {
                    for (i in index[g + SEPARATOR + partner(t)].orEmpty()) {
                        if (target in 0..i) break
                        if (canJoin(open[i].account, a)) {
                            target = i
                            break
                        }
                    }
                }
                if (target < 0) {
                    open.add(Open(a, tokens.toMutableSet()))
                    val at = open.size - 1
                    for (t in tokens) index.getOrPut(g + SEPARATOR + t) { ArrayList(1) }.add(at)
                } else {
                    val o = open[target]
                    o.account = o.account.joinedWith(a)
                    o.absorbed++
                    for (t in tokens) {
                        if (o.tokens.add(t)) {
                            val list = index.getOrPut(g + SEPARATOR + t) { ArrayList(1) }
                            // keep each list in the order of the accounts
                            val pos = list.binarySearch(target).let { if (it < 0) -it - 1 else it }
                            if (pos >= list.size || list[pos] != target) list.add(pos, target)
                        }
                    }
                }
            }
            var rows = 0
            var joinedAccounts = 0
            for (o in open) {
                if (o.absorbed > 0) {
                    joinedAccounts++
                    rows += o.account.rows
                }
            }
            return Joined(open.map { it.account }, rows, joinedAccounts)
        }

        /** [a] can take [b]'s websites: room for them within 10, and [b]'s name is the same or only its website's. */
        private fun canJoin(a: ImportAccount, b: ImportAccount): Boolean {
            val fresh = b.entry.urls.count { it !in a.entry.urls }
            if (a.entry.urls.size + fresh > EntryRules.URLS_MAX) return false
            return b.entry.title.trim().equals(a.entry.title.trim(), ignoreCase = true) || nameIsOnlyItsSite(b)
        }
    }
}

/** An account of the plan with the same website and username as [existingId] in the vault, but another password. */
class ImportConflict(
    /** Its place in [ImportPlan.accounts]. */
    val index: Int,
    /** The id of the account of the vault it is added beside. */
    val existingId: String,
) {
    override fun toString(): String = "ImportConflict(index=$index, existingId=$existingId)"
}

/** One of the two names "Tidy names" shows before and after. */
class TidyExample(val before: String, val after: String) {
    override fun toString(): String = "TidyExample(texts hidden)"
}

/** What an import would do (ux.md §5.21's preview); [ImportPlanner.plan] makes it. */
class ImportPlan(
    /** The accounts to add, in the order of the file. */
    val accounts: List<ImportAccount>,
    /** "Found 48 accounts.": the accounts of the file after rows were joined, the unconfirmed rows of the wrong length left out. */
    val found: Int,
    /** "3 are already here (same website, username and password) and will be skipped." */
    val alreadyHere: Int,
    /** Accounts that repeat an earlier account of the file exactly: imported once. */
    val repeatedInFile: Int,
    /** "2 have the same website and username but a different password; they will be added so you can compare." */
    val conflicts: List<ImportConflict>,
    /** "1 row has no password.": accounts to add without one. */
    val withoutPassword: Int,
    /** Rows with the wrong number of values, left out because the user has not confirmed them. */
    val wrongLengthLeftOut: Int,
    /** "6 rows were the same login on related websites and were kept together as 2 accounts.": the 6. */
    val relatedRows: Int,
    /** … the 2. */
    val relatedAccounts: Int,
    /** How many names "Tidy names" changed. */
    val tidied: Int,
    /** The first two names it changed, before and after. */
    val tidyExamples: List<TidyExample>,
    /** The vault file after the import, at most this many bytes (architecture.md §3.5). */
    val sealedBytes: Long,
) {
    /** What `VaultEdits.addImported` takes. */
    val entries: List<Entry> get() = accounts.map { it.entry }

    /**
     * The vault still fits in one file after the import. When false, the import is refused: "Your
     * passwords no longer fit in one file. Delete accounts you don't need, or shorten very long notes."
     */
    val fits: Boolean get() = sealedBytes <= ImportPlanner.MAX_FILE_BYTES

    override fun toString(): String =
        "ImportPlan(add=${accounts.size}, found=$found, alreadyHere=$alreadyHere, repeatedInFile=$repeatedInFile, " +
            "conflicts=${conflicts.size}, withoutPassword=$withoutPassword, wrongLengthLeftOut=$wrongLengthLeftOut, " +
            "related=$relatedRows/$relatedAccounts, tidied=$tidied, sealedBytes=$sealedBytes)"
}
