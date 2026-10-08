package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.model.edits.Entries
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BodyCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.HeaderCodec
import io.github.dilshodbekmx.passwordvault.core.vaultfile.PasswordSlot
import io.github.dilshodbekmx.passwordvault.core.vaultfile.VaultHeader
import java.security.MessageDigest
import java.util.IdentityHashMap
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
 *    in its notes, its extra fields or a pending change's old password: it is skipped. The name of an
 *    account without a website is looked up as the file has it and as an import stores it: with "Tidy
 *    names" and without, and with "(from file)", so that the same file imported again adds nothing.
 *    The same within the file: an exact repeat of an account already planned is imported once. An
 *    account with the same website and username as one in the vault but another password is added
 *    beside it "so you can compare" ([ImportPlan.conflicts]); nothing combines the two. An account of
 *    our own JSON whose id an account of the vault holds is added only when it differs from it in this
 *    sense, and then `VaultEdits.addImported` gives it a new id and the name "Gmail (from file)"
 *    (formats-review P1).
 * 5. **The size** (architecture.md §3.5): the vault file after the import, estimated from the vault
 *    `VaultEdits.addImported` would make, sealed with the longest header a vault file can have. An
 *    import that would not fit ([ImportPlan.fits] false) is refused with "Your passwords no longer fit
 *    in one file. …".
 *
 * **Speed** (formats-review P16): the vault and the accounts already planned are indexed by
 * (username, host) in hash maps, and each account's hosts are worked out once, so that the time grows
 * with the sizes added, not multiplied. That holds for a hostile file too, in which thousands of
 * accounts share one login: an account is compared only with those that have its login and its
 * password, an exact copy is found without a comparison, one note is looked for inside another in
 * time that grows with their lengths added, and the comparisons of one plan read at most [WORK_MAX]
 * characters. After that only exact copies still count as repeats, so that an account whose notes
 * merely stand inside another's is added once more; nothing is ever skipped for it.
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
        // The name in the file of each account that "Tidy names" renamed.
        val nameInFile = IdentityHashMap<ImportAccount, String>()
        if (choices.tidyNames) {
            accounts = accounts.map { a ->
                val tidy = tidyName(a.entry.title)
                if (tidy == a.entry.title) {
                    a
                } else {
                    tidied++
                    if (examples.size < 2) examples.add(TidyExample(a.entry.title, tidy))
                    a.renamed(tidy).also { nameInFile[it] = a.entry.title }
                }
            }
        }

        // The vault's accounts outside Recently deleted, and the accounts planned so far, by login.
        val contents = Contents()
        val live = vault.entries.filter { it.deletedAt == null }
        val inVault = Known(live.size)
        for (e in live) inVault.add(loginKeys(e), e, contents.of(e))
        val inFile = Known(accounts.size)
        val work = Work()
        val added = ArrayList<ImportAccount>(accounts.size)
        val conflicts = ArrayList<ImportConflict>()
        var alreadyHere = 0
        var repeated = 0
        for (a in accounts) {
            val e = a.entry
            val keys = loginKeys(e)
            val vaultKeys = keysInVault(e, keys, nameInFile[a], fromFile)
            val incoming = Incoming(e, contents.of(e))
            if (inVault.anyCovers(vaultKeys, incoming, work)) {
                alreadyHere++
                continue
            }
            if (inFile.anyCovers(keys, incoming, work)) {
                repeated++
                continue
            }
            inVault.withAnotherPassword(vaultKeys, e.password)?.let { conflicts.add(ImportConflict(added.size, it.id)) }
            added.add(a)
            inFile.add(keys, e, incoming.digests)
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
        if (hosts.isEmpty()) return listOf(nameKey(user, e.title))
        return hosts.map { user + SEPARATOR + it }
    }

    /** The login of an account without a website: its username and its name. */
    private fun nameKey(user: String, title: String): String = user + SEPARATOR + NO_WEBSITE + title.trim().lowercase(Locale.ROOT)

    /**
     * The logins under which an account of the vault may be the incoming [e]: [keys], and for an account
     * without a website, which only its name tells apart, also the names an import gives it. That is
     * the name it has in the file when "Tidy names" changed it ([nameInFile]; the vault's own export
     * holds that one), the name "Tidy names" gives it when it is off now and was on for an earlier
     * import, and each name with "(from file)", as `VaultEdits.addImported` names an account of our own
     * JSON that it adds beside the one holding its id ([fromFile]).
     */
    private fun keysInVault(e: Entry, keys: List<String>, nameInFile: String?, fromFile: (String) -> String): List<String> {
        if (e.urls.any { siteHost(it).isNotEmpty() }) return keys
        val user = e.username.trim()
        val all = LinkedHashSet<String>(keys)
        for (name in linkedSetOf(e.title, tidyName(e.title)).apply { nameInFile?.let(::add) }) {
            all.add(nameKey(user, name))
            all.add(nameKey(user, Entries.titled(fromFile, name)))
        }
        return all.toList()
    }

    /** The host of a website, or `app:` and the package of an Android app. */
    private fun siteHost(url: String): String {
        val host = WebAddress.hostOf(url)
        return if (Related.isApp(url) && host.isNotEmpty()) "app:$host" else host
    }

    /** [existing] already holds what [incoming] brings: the same password, and its notes, fields and old password. */
    private fun covers(existing: Entry, incoming: Incoming): Boolean {
        val e = incoming.entry
        return existing.password == e.password &&
            holds(existing.notes, incoming) &&
            existing.fields.containsAll(e.fields) &&
            (e.pendingOldPassword == null || e.pendingOldPassword == existing.pendingOldPassword)
    }

    /** Whether the notes [incoming] brings, trimmed, stand somewhere in [notes] (blank ones always do). */
    private fun holds(notes: String, incoming: Incoming): Boolean {
        val part = incoming.notes
        return when {
            part.isEmpty() -> true
            part.length > notes.length -> false
            part.length == notes.length -> part == notes
            else -> {
                val at = indexOf(notes, part, incoming.table)
                at >= 0 && notes.regionMatches(at, part, 0, part.length) // the search's answer, checked
            }
        }
    }

    /**
     * Where [part] first stands in [text], or -1: the search of Knuth, Morris and Pratt, whose time is
     * the two lengths added whatever the texts hold. `String.contains` takes them multiplied when the
     * texts begin alike again and again, which a file from outside can arrange. [table] is [tableOf] [part].
     */
    private fun indexOf(text: String, part: String, table: IntArray): Int {
        var k = 0
        for (i in text.indices) {
            val c = text[i]
            while (k > 0 && c != part[k]) k = table[k - 1]
            if (c == part[k]) k++
            if (k == part.length) return i - k + 1
        }
        return -1
    }

    /** For each start of [part], the length of its longest proper end that is also a start of [part]. */
    private fun tableOf(part: String): IntArray {
        val table = IntArray(part.length)
        var k = 0
        for (i in 1 until part.length) {
            val c = part[i]
            while (k > 0 && c != part[k]) k = table[k - 1]
            if (c == part[k]) k++
            table[i] = k
        }
        return table
    }

    /** An incoming account and what every comparison with it needs, worked out once. */
    private class Incoming(val entry: Entry, /** [Contents.of] it. */ val digests: Digests) {
        /** Its notes as they are looked for in another account's: trimmed. */
        val notes: String = entry.notes.trim()

        /** [tableOf] its notes, made when the first search needs it. */
        val table: IntArray by lazy(LazyThreadSafetyMode.NONE) { tableOf(notes) }

        /** The characters of its extra fields. */
        val fieldsText: Int = fieldsText(entry)
    }

    /** The characters of the extra fields of [e]. */
    private fun fieldsText(e: Entry): Int {
        var n = 0
        for (f in e.fields) n += f.name.length + f.value.length
        return n
    }

    /**
     * What [covers] of [existing] and [incoming] reads at most, in characters, for [Work]: the password
     * (the two are equal), the notes when one may stand in the other, the fields, and a little for the
     * comparison itself.
     */
    private fun costOf(existing: Entry, incoming: Incoming): Long {
        val part = incoming.notes.length
        val notes = if (part == 0 || part > existing.notes.length) 0 else existing.notes.length + part
        return COMPARISON + incoming.entry.password.length + notes + incoming.fieldsText +
            (incoming.entry.fields.size + 1).toLong() * fieldsText(existing)
    }

    /** What one comparison costs besides the text it reads ([costOf]). */
    private const val COMPARISON = 64

    /**
     * The most characters the comparisons of one plan may read ([Work]): 200 million. A file made so
     * that its comparisons read as much as they can reaches it in about 0.3 s on the desktop this was
     * measured on and 0.5 s on its API 37 emulator. The files people make need far fewer, because few
     * accounts share a login and a password.
     */
    internal const val WORK_MAX: Long = 200_000_000L

    /** What the comparisons of one plan may still read; at 0, only exact copies are still found. */
    private class Work(var left: Long = WORK_MAX)

    /**
     * Two short texts that stand for an account in the indexes of [Known], so that no key holds a
     * password: [password] is the SHA-256 of its password, [content] that of everything [covers]
     * compares (the password, the trimmed notes, the extra fields and the pending change's old
     * password). Two accounts have the same [content] exactly when they are equal in all of that, and
     * a key made of them has a hash that a file cannot choose.
     */
    private class Digests(val password: String, val content: String)

    /** Makes the [Digests] of accounts. */
    private class Contents {
        private val sha256 = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(4_096)
        private var used = 0

        /** How much of [buffer] holds text since it was last wiped. */
        private var dirty = 0

        fun of(e: Entry): Digests {
            text(e.password)
            val password = digest()
            text(e.password)
            text(e.notes.trim())
            number(e.fields.size)
            for (f in e.fields) {
                text(f.name)
                text(f.value)
                number(if (f.secret) 1 else 0)
            }
            val old = e.pendingOldPassword
            if (old == null) number(-1) else text(old)
            return Digests(password, digest())
        }

        private fun digest(): String {
            sha256.update(buffer, 0, used)
            buffer.fill(0, 0, maxOf(used, dirty)) // it held a password
            used = 0
            dirty = 0
            return Bytes.hex(sha256.digest())
        }

        /** The length, then every UTF-16 unit as it is: two texts never give the same bytes. */
        private fun text(s: String) {
            number(s.length)
            for (c in s) {
                if (used + 2 > buffer.size) flush()
                buffer[used++] = (c.code shr 8).toByte()
                buffer[used++] = c.code.toByte()
            }
        }

        private fun number(n: Int) {
            if (used + 4 > buffer.size) flush()
            buffer[used++] = (n shr 24).toByte()
            buffer[used++] = (n shr 16).toByte()
            buffer[used++] = (n shr 8).toByte()
            buffer[used++] = n.toByte()
        }

        private fun flush() {
            sha256.update(buffer, 0, used)
            if (used > dirty) dirty = used
            used = 0
        }
    }

    /**
     * The accounts an incoming one is compared with (the vault's, or those of the file planned so far),
     * found by login without looking through the others, also when thousands share one login:
     * - by login and password, in their order: only these can hold what an account with that login and
     *   that password brings;
     * - by login and [Digests.content]: an exact copy, found at once;
     * - by login, the first account and the first with another password than the first's: enough to
     *   name the account that an incoming one with another password is added beside.
     * Every key is a string, which the hash map keeps in a sorted tree when many keys share one hash.
     */
    private class Known(expected: Int) {
        private val byPassword = HashMap<String, MutableList<Entry>>(expected * 2)
        private val byContent = HashMap<String, Entry>(expected * 2)
        private val first = HashMap<String, Entry>(expected * 2)
        private val another = HashMap<String, Entry>()

        /** Adds [e], which has the logins [keys] and the [digests] that [Contents.of] gives for it. */
        fun add(keys: List<String>, e: Entry, digests: Digests) {
            for (k in keys) {
                byPassword.getOrPut(k + SEPARATOR + digests.password) { ArrayList(1) }.add(e)
                byContent.putIfAbsent(k + SEPARATOR + digests.content, e)
                val held = first.putIfAbsent(k, e)
                if (held != null && held.password != e.password) another.putIfAbsent(k, e)
            }
        }

        /** Whether one of these accounts with a login of [keys] already holds what [incoming] brings. */
        fun anyCovers(keys: List<String>, incoming: Incoming, work: Work): Boolean {
            for (k in keys) {
                val copy = byContent[k + SEPARATOR + incoming.digests.content]
                if (copy != null && covers(copy, incoming)) return true
            }
            for (k in keys) {
                for (x in byPassword[k + SEPARATOR + incoming.digests.password].orEmpty()) {
                    if (work.left <= 0) return false
                    work.left -= costOf(x, incoming)
                    if (covers(x, incoming)) return true
                }
            }
            return false
        }

        /** The first of these accounts that has a login of [keys] and another password than [password], if any. */
        fun withAnotherPassword(keys: List<String>, password: String): Entry? {
            for (k in keys) {
                val held = first[k] ?: continue
                if (held.password != password) return held
                another[k]?.let { return it }
            }
            return null
        }
    }

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
        val after = if (entries.isEmpty()) vault else VaultEdits.addImported(entries, SizingIds(vault, entries), fromFile)(vault, 0L).vault
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

    /**
     * Ids for the size estimate only: distinct and valid, never random, and never one that [vault] or
     * [entries] has. A file in our own format can bring any valid id, these among them, and
     * `VaultEdits.addImported` gives up after a hundred ids that are taken.
     */
    private class SizingIds(vault: Vault, entries: List<Entry>) : RandomSource {
        private val taken = HashSet<String>((vault.entries.size + entries.size) * 2)
        private var next = 0L

        init {
            vault.entries.mapTo(taken) { it.id }
            entries.mapTo(taken) { it.id }
        }

        override fun bytes(n: Int): ByteArray = ByteArray(n)

        override fun int(bound: Int): Int = 0

        override fun uuid(): String {
            while (true) {
                val id = "ffffffff-ffff-4fff-bfff-" + (next++).toString(16).padStart(12, '0')
                if (id !in taken) return id
            }
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

        /** An account of the result: the first of its rows, and every row joined to it since. */
        private class Open(var account: ImportAccount, val tokens: MutableSet<String>, val group: String, val name: String) {
            var absorbed = 0
        }

        /**
         * Joins [accounts] in their order: each one goes into the earliest account before it that it is
         * related to, that has the same login and room for its websites, and whose name it may share;
         * otherwise it starts an account of its own. Open accounts are found through two indexes, by
         * (login, token) and by (login, name, token), which keep only the accounts with room, so that a
         * file of thousands of rows of one login is joined in linear time.
         */
        fun join(accounts: List<ImportAccount>): Joined {
            val open = ArrayList<Open>(accounts.size)
            val byToken = HashMap<String, MutableList<Int>>()
            val byNameAndToken = HashMap<String, MutableList<Int>>()

            fun keys(o: Open, token: String) = listOf(
                byToken to o.group + SEPARATOR + token,
                byNameAndToken to o.group + SEPARATOR + o.name + SEPARATOR + token,
            )

            fun register(o: Open, at: Int, tokens: Collection<String>) {
                for (t in tokens) {
                    for ((index, key) in keys(o, t)) {
                        val list = index.getOrPut(key) { ArrayList(1) }
                        val pos = list.binarySearch(at).let { if (it < 0) -it - 1 else it } // each list in the accounts' order
                        if (pos >= list.size || list[pos] != at) list.add(pos, at)
                    }
                }
            }

            fun unregister(o: Open, at: Int) {
                for (t in o.tokens) {
                    for ((index, key) in keys(o, t)) {
                        val list = index[key] ?: continue
                        val pos = list.binarySearch(at)
                        if (pos >= 0) list.removeAt(pos)
                    }
                }
            }

            for (a in accounts) {
                val e = a.entry
                val tokens = if (e.password.isEmpty() || a.wrongLength != null) emptySet() else tokens(e)
                if (tokens.isEmpty()) {
                    open.add(Open(a, HashSet(), "", ""))
                    continue
                }
                val group = group(e)
                val name = e.title.trim().lowercase(Locale.ROOT)
                // A name that is only its website's may give way to any account's; another only to the same name.
                val anyName = nameIsOnlyItsSite(a)
                var target = -1
                for (t in tokens) {
                    val key = if (anyName) group + SEPARATOR + partner(t) else group + SEPARATOR + name + SEPARATOR + partner(t)
                    for (i in (if (anyName) byToken else byNameAndToken)[key].orEmpty()) {
                        if (target in 0..i) break
                        if (hasRoom(open[i].account, a)) {
                            target = i
                            break
                        }
                    }
                }
                if (target < 0) {
                    val o = Open(a, tokens.toMutableSet(), group, name)
                    open.add(o)
                    if (e.urls.size < EntryRules.URLS_MAX) register(o, open.size - 1, tokens)
                } else {
                    val o = open[target]
                    o.account = o.account.joinedWith(a)
                    o.absorbed++
                    val fresh = tokens.filter { o.tokens.add(it) }
                    if (o.account.entry.urls.size >= EntryRules.URLS_MAX) unregister(o, target) else register(o, target, fresh)
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

        /** [a] has room within 10 websites for those of [b] it does not have yet. */
        private fun hasRoom(a: ImportAccount, b: ImportAccount): Boolean =
            a.entry.urls.size + b.entry.urls.count { it !in a.entry.urls } <= EntryRules.URLS_MAX
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

    /** The accounts to add that the importer changed to fit the model: the preview lists them ("Gmail: 3 websites moved to notes"). */
    val changed: List<ImportAccount> get() = accounts.filter { it.changes.isNotEmpty() }

    /** How many accounts to add have a change of [kind]: "1 note was longer than 10,000 characters and was shortened." */
    fun count(kind: ImportChange.Kind): Int = accounts.count { a -> a.changes.any { it.kind == kind } }

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
