package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import java.util.Locale

/** Shared pieces of the Security tab's JVM tests (build plan 7.1). */
internal object SecurityKit {
    /** 7 October 2026, 10:00 UTC: the tests' wall clock. */
    const val NOW = 1_791_367_200_000L
    const val DAY = 86_400_000L

    /** The passwords [easyToGuess] calls easy, as the bundled meter would (ux.md §12 check 18). */
    val WEAK = setOf("Toshkent2020", "Mustaqillik1991", "123456", "qwerty")

    /** Card 3's verdict without the meter: a password of [WEAK]; the website's password while a change waits. */
    fun easyToGuess(entry: Entry): Boolean {
        val password = if (entry.changePending && entry.pendingOldPassword != null) entry.pendingOldPassword else entry.password
        return password in WEAK
    }

    /** An account; [n] gives its id, its creation day and, unless given, the time of its last change. */
    fun entry(
        n: Int,
        title: String,
        password: String = "Unique-$n-Xq7#pL2v",
        username: String = "",
        urls: List<String> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        pendingOldPassword: String? = null,
        checkIgnored: Boolean = false,
        updatedAt: Long = NOW - 30 * DAY + n,
        deletedAt: Long? = null,
        history: List<String> = emptyList(),
    ): Entry = Entry(
        id = id(n),
        title = title,
        username = username,
        password = password,
        urls = urls,
        favourite = favourite,
        changePending = changePending,
        pendingOldPassword = pendingOldPassword,
        checkIgnored = checkIgnored,
        createdAt = NOW - 60 * DAY + n,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        passwordHistory = history.map { HistoryItem(it, NOW - 10 * DAY) },
    )

    /** The id of account [n]: a version 4 UUID, as the app makes them. */
    fun id(n: Int): String = "00000000-0000-4000-8000-%012d".format(n)

    /**
     * The A–Z of tests: titles without case, then as they are (root locale). The locale "xx" sorts Z to
     * A, so that a change of language shows in the order.
     */
    fun collationOf(locale: Locale): Collation = if (locale.language == "xx") Reversed else Plain

    object Plain : Collation {
        override fun compare(a: String, b: String): Int {
            val byCase = a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
            return if (byCase != 0) byCase else a.compareTo(b)
        }

        override fun header(title: String): String = title.take(1).uppercase(Locale.ROOT)
    }

    object Reversed : Collation {
        override fun compare(a: String, b: String): Int = Plain.compare(b, a)

        override fun header(title: String): String = Plain.header(title)
    }
}
