package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The security check (ux.md §5.16, §5.17; architecture.md D-9a, D-9b, §8.1 "strength"). */
class SecurityCheckTest {

    private val strong = "x7#Qm2!pL9@vRt"
    private val shared = "Kuchli-Parol-77"
    private val other = "Boshqa-Parol-55"

    private fun entry(
        id: String,
        title: String,
        password: String = "",
        createdAt: Long = 1_000L,
        username: String = "",
        urls: List<String> = emptyList(),
        favourite: Boolean = false,
        history: List<String> = emptyList(),
        pendingOld: String? = null,
        ignored: Boolean = false,
        deleted: Boolean = false,
    ) = Entry(
        id = id, title = title, username = username, password = password, urls = urls, favourite = favourite,
        changePending = pendingOld != null, checkIgnored = ignored, createdAt = createdAt, updatedAt = createdAt,
        deletedAt = if (deleted) createdAt + 1 else null, pendingOldPassword = pendingOld,
        passwordHistory = history.mapIndexed { i, p -> HistoryItem(p, createdAt + 100 - i) },
    )

    private fun run(vault: Vault) = SecurityCheck.run(vault, easyToGuess = { SecurityCheck.isEasyToGuess(it, Meters.meter) })

    @Test
    fun reuseGroupsAreBiggestFirstAndListEmailThenFavouritesThenAToZ() {
        val vault = Vault(
            entries = listOf(
                entry("a", "Facebook", shared, createdAt = 5),
                entry("b", "Zoo market", shared, createdAt = 4, favourite = true),
                entry("c", "Почта", shared, createdAt = 3),
                entry("d", "Bank", other, createdAt = 2),
                entry("e", "Gmail", shared, createdAt = 6),
                entry("f", "Click", other, createdAt = 1),
                entry("g", "Alone", strong),
            ),
        )
        val report = run(vault)
        assertEquals(2, report.reused.size)
        assertEquals(listOf("e", "c", "b", "a"), report.reused[0].entryIds) // Gmail, Почта (email), Zoo (favourite), Facebook
        assertEquals(listOf("d", "f"), report.reused[1].entryIds)
        assertEquals(listOf(0, 0), report.reused.map { it.changed })
        // Ids are entry ids, the oldest of each group: never anything made from the password.
        assertEquals("c", report.reused[0].id)
        assertEquals("f", report.reused[1].id)
    }

    @Test
    fun aGroupKeepsItsIdWhileItsAccountsLeaveItOneByOne() {
        var vault = Vault(
            entries = listOf(
                entry("old", "Mail.ru", shared, createdAt = 1),
                entry("mid", "Instagram", shared, createdAt = 2),
                entry("new", "Telegram", shared, createdAt = 3),
                entry("x", "OLX", shared, createdAt = 4),
            ),
        )
        val before = run(vault).reused.single()
        assertEquals("old", before.id)
        // The oldest account changes first, through the guided change of package 2.4.
        vault = change(vault, "old", "Yangi-Parol-Mail-2026")
        val pending = run(vault)
        assertEquals("while the change waits, the website may still have the old password", before, pending.reused.single())
        assertEquals(listOf("old"), pending.pending)
        vault = VaultEdits.confirmChange("old")(vault, 10_000L).vault
        val after = run(vault).reused.single()
        assertEquals(before.id, after.id)
        assertEquals(listOf("mid", "x", "new"), after.entryIds) // Instagram, OLX, Telegram
        assertEquals(1, after.changed)
        // A second account leaves; one is put back.
        vault = VaultEdits.confirmChange("mid")(change(vault, "mid", "Yangi-Parol-Insta-2026"), 11_000L).vault
        vault = VaultEdits.revertChange("new")(change(vault, "new", "Yangi-Parol-Tg-2026"), 12_000L).vault
        val last = run(vault).reused.single()
        assertEquals(before.id, last.id)
        assertEquals(listOf("x", "new"), last.entryIds)
        assertEquals(2, last.changed)
    }

    private fun change(vault: Vault, id: String, newPassword: String): Vault {
        val edited = VaultEdits.startChange(id, newPassword)(vault, 9_000L)
        assertTrue(edited.value is EntryResult.Done)
        return edited.vault
    }

    @Test
    fun twoGroupsNeverShareAnId() {
        // "e" is the oldest account of both groups: it shares one password now and shared the other before.
        val vault = Vault(
            entries = listOf(
                entry("e", "Email", other, createdAt = 1, history = listOf(shared)),
                entry("f", "Forum", other, createdAt = 5),
                entry("g", "Game", shared, createdAt = 6),
                entry("h", "Home", shared, createdAt = 7),
            ),
        )
        val groups = run(vault).reused
        assertEquals(2, groups.size)
        assertNotEquals(groups[0].id, groups[1].id)
        assertEquals(setOf("e", "f", "g", "h"), groups.map { it.id }.toSet() + groups.flatMap { it.entryIds })
        assertEquals(1, groups.single { "g" in it.entryIds }.changed)
    }

    @Test
    fun easyToGuessUsesTheBundledListAndTheAccountsOwnWords() {
        val vault = Vault(
            entries = listOf(
                entry("1", "Facebook", "Toshkent2020"),
                entry("2", "Bank", strong),
                entry("3", "SMS login", ""),
                entry("4", "Qwzrtplm Xvbnkj", "QwzrtplmXvbnkj"),
                entry("5", "Telegram", "XvbnkjQwzrtplm", username = "qwzrtplm.xvbnkj@mail.ru"),
                entry("6", "Instagram", "XvbnkjQwzrtplm"),
            ),
        )
        val report = run(vault)
        // 4 and 5 lean on their own name and username; 6 is the same text without that knowledge.
        assertEquals(listOf("1", "4", "5"), report.weak.sorted())
        assertEquals(listOf("qwzrtplm", "xvbnkj", "telegram"), SecurityCheck.userInputs(vault.entries[4]))
        assertFalse(SecurityCheck.userInputs(vault.entries[4]).contains("mail"))
    }

    @Test
    fun leftOutAndDeletedAccounts() {
        val vault = Vault(
            entries = listOf(
                entry("pin", "Bank PIN", "Toshkent2020", ignored = true),
                entry("pin2", "Card PIN", "Toshkent2020", ignored = true),
                entry("same", "Gmail", strong, ignored = true),
                entry("chg", "Yandex", strong, pendingOld = "Toshkent2020", ignored = true),
                entry("gone", "Old", "Toshkent2020", deleted = true),
                entry("gone2", "Old 2", "Toshkent2020", deleted = true),
                entry("ok", "Shop", "Toshkent2020"),
            ),
            masterPasswordMatches = listOf("same", "gone", "nobody"),
        )
        val report = run(vault)
        assertEquals(listOf("ok"), report.weak)
        assertTrue("left out and deleted accounts make no group", report.reused.isEmpty())
        assertEquals(listOf("chg", "pin", "pin2", "same").sorted(), report.leftOut.sorted())
        assertEquals("card 1 still names a left-out account; never a deleted or unknown one", listOf("same"), report.sameAsMasterPassword)
        assertEquals("a change the user started stays on card 4", listOf("chg"), report.pending)
    }

    @Test
    fun anEmptyVaultAndAnAllClearVault() {
        assertEquals(SecurityReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), run(Vault()))
        val clear = run(Vault(entries = listOf(entry("1", "A", strong), entry("2", "B", "Q9!vLx2#mZ8@kR"))))
        assertEquals(SecurityReport(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), clear)
    }

    @Test
    fun cardOneComesFromTheStoredMatchesInListOrder() {
        val vault = Vault(
            entries = listOf(entry("f", "Facebook", strong), entry("g", "Gmail", strong), entry("a", "Avito", strong, favourite = true)),
            masterPasswordMatches = listOf("f", "a", "g", "g"),
        )
        assertEquals(listOf("g", "a", "f"), run(vault).sameAsMasterPassword)
    }

    @Test
    fun progressCountsTheScoredAccountsAndCanStopTheCheck() {
        val vault = Vault(entries = listOf(entry("1", "A", strong), entry("2", "B", ""), entry("3", "C", "Toshkent2020"), entry("4", "D", strong, ignored = true)))
        val seen = ArrayList<Pair<Int, Int>>()
        SecurityCheck.run(vault, easyToGuess = { false }, progress = { done, total -> seen += done to total })
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
        try {
            SecurityCheck.run(vault, easyToGuess = { false }, progress = { done, _ -> if (done == 1) throw IllegalStateException("stop") })
            fail("the exception passes through")
        } catch (e: IllegalStateException) {
            assertEquals("stop", e.message)
        }
    }

    @Test
    fun aRunnerMayRememberAnswersByIdAndUpdatedAt() {
        val vault = Vault(entries = listOf(entry("1", "A", "Toshkent2020"), entry("2", "B", strong)))
        val memo = HashMap<Pair<String, Long>, Boolean>()
        var scored = 0
        val remembering = { e: Entry -> memo.getOrPut(e.id to e.updatedAt) { scored++; SecurityCheck.isEasyToGuess(e, Meters.meter) } }
        val first = SecurityCheck.run(vault, easyToGuess = remembering)
        val second = SecurityCheck.run(vault, easyToGuess = remembering)
        assertEquals(first, second)
        assertEquals(2, scored)
    }

    @Test
    fun d9b_theMasterPasswordIsComparedThroughMasterPasswordBytes() {
        val master = MasterPasswordBytes.of("Oʻgʻlim Toshkentda 1991".toCharArray())
        val vault = Vault(
            entries = listOf(
                entry("folded", "A", "O'g'lim  Toshkentda 1991"), // another apostrophe, two spaces: the same bytes
                entry("case", "B", "oʻgʻlim toshkentda 1991"), // case is kept: not the master password
                entry("pending", "C", "Yangi-Parol-2026", pendingOld = "Oʻgʻlim Toshkentda 1991"),
                entry("gone", "D", "Oʻgʻlim Toshkentda 1991", deleted = true),
                entry("empty", "E", ""),
                entry("escaped", "F", "O\u02BBg\u2018lim\u3000Toshkentda 1991"), // ʻ, ‘ and an ideographic space
            ),
        )
        assertEquals(setOf("folded", "pending", "escaped"), SecurityCheck.matchesMasterPassword(vault, master))
        assertEquals(emptySet<String>(), SecurityCheck.matchesMasterPassword(vault, MasterPasswordBytes.of("boshqa parol".toCharArray())))
        // The stored list then drives card 1.
        val stored = VaultEdits.setMasterPasswordMatches(SecurityCheck.matchesMasterPassword(vault, master))(vault, 1L).vault
        assertEquals(setOf("folded", "pending", "escaped"), run(stored).sameAsMasterPassword.toSet())
    }

    @Test
    fun theReuseNoteOfTheEditForm() {
        val vault = Vault(
            entries = listOf(
                entry("1", "A", shared),
                entry("2", "B", shared, ignored = true),
                entry("3", "C", shared, deleted = true),
                entry("4", "D", "Yangi", pendingOld = shared),
                entry("5", "E", other),
            ),
        )
        assertEquals(3, SecurityCheck.otherAccountsWith(vault, shared, null))
        assertEquals(2, SecurityCheck.otherAccountsWith(vault, shared, "1"))
        assertEquals(0, SecurityCheck.otherAccountsWith(vault, "Yangi", null))
        assertEquals(0, SecurityCheck.otherAccountsWith(vault, "", null))
        assertEquals(0, SecurityCheck.otherAccountsWith(vault, other, "5"))
    }

    @Test
    fun emailLikeAccounts() {
        for (title in listOf("Gmail", "Google", "Mail.ru", "Yandex", "Outlook", "Hotmail", "iCloud", "Yahoo", "Pochta", "Почта", "ПОЧТА.uz")) {
            assertTrue(title, SecurityCheck.isEmailLike(entry("1", title)))
        }
        assertTrue(SecurityCheck.isEmailLike(entry("1", "Work", urls = listOf("https://mail.google.com"))))
        assertFalse(SecurityCheck.isEmailLike(entry("1", "Facebook", urls = listOf("https://facebook.com"))))
    }

    @Test
    fun theAToZPartFollowsTheLanguageWhenGiven() {
        val entries = listOf(entry("1", "Ёлка", shared), entry("2", "Ehtiyot", shared), entry("3", "Zebra", shared))
        val byCode = SecurityCheck.run(Vault(entries = entries), easyToGuess = { false }).reused.single().entryIds
        assertEquals(listOf("2", "1", "3"), byCode) // search keys: ehtiyot, yolka, zebra
        val reversed = SecurityCheck.run(Vault(entries = entries), easyToGuess = { false }, titleOrder = reverseOrder<String>()).reused.single().entryIds
        assertEquals(listOf("1", "3", "2"), reversed) // titles in reverse code point order: Ёлка, Zebra, Ehtiyot
    }

    @Test
    fun theReportHoldsNoPassword() {
        val vault = Vault(entries = listOf(entry("1", "A", shared), entry("2", "B", shared), entry("3", "C", "Toshkent2020")))
        val text = run(vault).toString()
        for (secret in listOf(shared, "Toshkent2020")) assertFalse(text.contains(secret))
    }
}
