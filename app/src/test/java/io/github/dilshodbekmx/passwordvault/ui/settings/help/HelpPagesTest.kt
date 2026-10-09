package io.github.dilshodbekmx.passwordvault.ui.settings.help

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpBlock
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpFacts
import io.github.dilshodbekmx.passwordvault.ui.settings.HelpPages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 7.3, [JVM]: the pages of ux.md §5.22 exist, each with its heading and its text from
 * `strings_help.xml` (English until wave 6, build-plan.md §12), and they say what ux.md §7.6,
 * lock.md §2.3 and §16.2 item 12 and architecture.md D-14 ask, as the app now is: the clipboard
 * delay chosen in Settings, a force-stop and "Restricted" battery use, the masked fields and what
 * an accessibility service still reads before Android 14, the 24 hours and how the countdown moves,
 * the passwords from before a replacement, rooted phones, "Clear storage", and the backup file as the
 * only way to a new phone. The app's name comes only from `app_name`. Every button the pages name is
 * quoted as the app shows it.
 */
class HelpPagesTest {
    private val facts = HelpFacts(ClipDelay.MINUTE_1, versionName = "1.0", versionCode = 1, androidRelease = "14", testBuild = false)

    private fun page(page: HelpPage, facts: HelpFacts = this.facts): String =
        HelpPages.blocks(page, facts).joinToString("\n") { block ->
            when (block) {
                is HelpBlock.Heading -> English.text(block.text)
                is HelpBlock.Paragraph -> English.text(block.text)
                is HelpBlock.AppHead -> English.text(block.version)
                is HelpBlock.Open -> English.text(block.text)
            }
        }

    @Test
    fun everyPageOfUx522Exists_withItsHeadingAndText() {
        // ux.md §5.22: four pages, "Open-source licences" (Route.Licences, LicencesCatalogTest) and "Version".
        assertEquals(
            listOf(HelpPage.PROTECTION, HelpPage.LOST_PHONE, HelpPage.FORGOT_MASTER_PASSWORD, HelpPage.FORGOT_PIN, HelpPage.VERSION),
            HelpPage.entries.toList(),
        )
        val headings = mapOf(
            HelpPage.PROTECTION to "How your passwords are protected",
            HelpPage.LOST_PHONE to "If you lose your phone or move to a new one",
            HelpPage.FORGOT_MASTER_PASSWORD to "If you forget your master password",
            HelpPage.FORGOT_PIN to "If you forget your PIN",
            HelpPage.VERSION to "Version",
        )
        for ((page, heading) in headings) {
            assertEquals(heading, English.text(UiText.of(HelpPages.title(page))))
            val blocks = HelpPages.blocks(page, facts)
            assertTrue("$page has text", blocks.count { it is HelpBlock.Paragraph } >= 2)
        }
        // The headings are the words of the Settings rows that open them.
        assertEquals(English.string("settings_help_protection"), English.string("help_protection_title"))
        assertEquals(English.string("settings_help_lost_phone"), English.string("help_lost_title"))
        assertEquals(English.string("settings_help_forgot_master_password"), English.string("help_forgot_mp_title"))
        assertEquals(English.string("settings_help_forgot_pin"), English.string("help_forgot_pin_title"))
        assertEquals(English.string("settings_version"), English.string("help_version_title"))
        assertEquals(English.string("settings_licences"), English.string("help_licences_title"))
    }

    @Test
    fun everyTextOfThePagesIsInStringsHelp_andTheAppsNameComesOnlyFromAppName() {
        val helpNames = English.helpNames().toSet()
        for (page in HelpPage.entries) {
            for (text in HelpPages.blocks(page, facts).flatMap(::textsOf)) {
                val name = English.name(text.id())
                assertTrue("$page uses $name, which is not in strings_help.xml", name in helpNames || name == "app_name")
            }
        }
        // No text of the package types the name: the user may rename the app (architecture.md D-23).
        val appName = English.string("app_name")
        val file = File(English.res, "values/strings_help.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("strings_help.xml types the app's name", file.contains(appName, ignoreCase = true))
        // The pages that name the app take it from app_name.
        assertTrue(page(HelpPage.LOST_PHONE).contains("install $appName there"))
        assertTrue(page(HelpPage.LOST_PHONE).contains("If $appName on the new phone says “Welcome back”"))
        assertTrue(page(HelpPage.VERSION).contains("$appName keeps your passwords on this phone only"))
    }

    @Test
    fun protection_saysWhatHelpMustSay() {
        val text = page(HelpPage.PROTECTION)
        // ux.md §7.6, first paragraph.
        assertTrue(text.contains("Your passwords are kept in a locked file on this phone. Only your master password can unlock it."))
        assertTrue(text.contains("The app never connects to the internet; it does not have permission to."))
        assertTrue(text.contains("Screenshots of the app are blocked."))
        // architecture.md D-14: the two "never" cases.
        assertTrue(text.contains("If you force-stop this app, or set its battery use to “Restricted”, a copied password stays on the clipboard until you open the app again."))
        // A clear that cannot read the clipboard clears what it holds (Android 9 locked, 10+ in the background).
        assertTrue(text.contains("Anything else you copy in that minute may be removed too."))
        // lock.md §9.4.
        assertTrue(text.contains("Anyone whose fingerprint is saved in this phone’s settings can open the app."))
        // lock.md §2.3: a PIN known from elsewhere; a rooted phone; a replacement nobody notices; Clear storage.
        assertTrue(text.contains("Don’t use your phone’s unlock PIN or your bank card PIN as the app PIN."))
        assertTrue(text.contains("On a rooted phone, someone can try every PIN in a few hours. Your master password still protects your passwords, so make it strong and never use it anywhere else."))
        assertTrue(text.contains("It starts working only after 24 hours with the phone switched on"))
        assertTrue(text.contains("Never use “Clear storage” to fix a problem with your PIN: it deletes your passwords, and the ones from before a replaced master password with them."))
        assertTrue(text.contains("Only a backup kept outside the phone survives that."))
        // What the app cannot protect from (ux.md §7.6), as build plan 3.12 measured it (lock.md §13.5):
        // a masked field gives every service dots, on every version; a shown password is read before Android 14.
        assertTrue(text.contains("a harmful keyboard app, or an app you allowed to control your phone (accessibility)"))
        assertTrue(text.contains("Passwords shown as dots stay hidden from other apps, even from an app that may control your phone."))
        assertTrue(text.contains("On Android 13 and older, such an app can read a password while you show it on the screen."))
        assertTrue(text.contains("Never install an app file (APK) sent to you in Telegram"))
        assertTrue(text.contains("So it cannot tell you if a website leaked your password."))
        // Its three headings.
        val headings = HelpPages.blocks(HelpPage.PROTECTION, facts).filterIsInstance<HelpBlock.Heading>().map { English.text(it.text) }
        assertEquals(listOf("Copied passwords", "Your PIN and fingerprint", "What the app cannot protect you from"), headings)
    }

    @Test
    fun protection_givesTheClipboardDelayChosenInSettings() {
        val expected = mapOf(
            ClipDelay.SECONDS_30 to "Passwords you copy are removed from the clipboard after 30 seconds. Anything else you copy in that time may be removed too.",
            ClipDelay.MINUTE_1 to "Passwords you copy are removed from the clipboard after 1 minute. Anything else you copy in that minute may be removed too.",
            ClipDelay.MINUTES_2 to "Passwords you copy are removed from the clipboard after 2 minutes. Anything else you copy in that time may be removed too.",
            ClipDelay.MINUTES_5 to "Passwords you copy are removed from the clipboard after 5 minutes. Anything else you copy in that time may be removed too.",
        )
        assertEquals(ClipDelay.entries.toSet(), expected.keys)
        for ((delay, sentence) in expected) {
            assertEquals(sentence, English.text(HelpPages.copiedText(delay)))
            assertTrue(page(HelpPage.PROTECTION, facts.copy(clipDelay = delay)).contains(sentence))
        }
    }

    @Test
    fun lostPhone_theBackupFileIsTheWayToANewPhone() {
        val text = page(HelpPage.LOST_PHONE)
        assertTrue(text.contains("tap “Restore from a backup file”, choose your newest backup and type your master password. Without a backup, your passwords can’t be brought back."))
        // The manifest keeps the vault out of device-to-device transfer (architecture.md §7.3).
        assertTrue(text.contains("Moving your apps to a new phone, for example with “Copy apps and data”, does not bring your passwords."))
        // A phone-copy tool or a reinstall that kept the data: "Welcome back" (lock.md F14).
        assertTrue(text.contains("says “Welcome back”, type your master password once. Then choose a PIN for the new phone."))
        assertTrue(text.contains("After 5 wrong PINs in a row the PIN is turned off"))
    }

    @Test
    fun forgotMasterPassword_saysThe24HoursAndThePasswordsFromBefore() {
        val text = page(HelpPage.FORGOT_MASTER_PASSWORD)
        // lock.md §16.2 item 12: the 24 hours and how the countdown moves.
        assertTrue(text.contains("The new master password starts working after 24 hours with the phone switched on. The countdown stops while the phone is switched off, so open the app in the morning and again in the evening."))
        // lock.md F16b.
        assertTrue(text.contains("If someone else replaced your master password, your own still opens your passwords as they were: “Forgot master password?”, then “Open my passwords from before …”."))
        assertTrue(text.contains("You can also start with an empty app. Your old passwords stay on the phone, locked"))
        assertTrue(text.contains("Settings › App lock › Change master password › I don’t remember it."))
        assertEquals(Route.Help(HelpPage.FORGOT_PIN), HelpPages.blocks(HelpPage.FORGOT_MASTER_PASSWORD, facts).filterIsInstance<HelpBlock.Open>().single().route)
    }

    @Test
    fun forgotPin_saysHowAndNeverClearStorage() {
        val text = page(HelpPage.FORGOT_PIN)
        assertTrue(text.contains("Tap “Forgot PIN? Use master password”, type your master password, and choose a new PIN."))
        assertTrue(text.contains("Never use “Clear storage” to fix a problem with your PIN"))
        assertTrue(text.contains("After 5 wrong PINs in a row, the PIN is turned off. Nothing is deleted"))
        assertEquals(Route.Help(HelpPage.FORGOT_MASTER_PASSWORD), HelpPages.blocks(HelpPage.FORGOT_PIN, facts).filterIsInstance<HelpBlock.Open>().single().route)
    }

    @Test
    fun theButtonsThePagesNameAreQuotedAsTheAppShowsThem() {
        val all = HelpPage.entries.joinToString("\n") { page(it) }
        val quoted = Regex("“([^”]+)”").findAll(all).map { it.groupValues[1] }.toSet()
        // Each quoted name of the app's own screens, with the string that shows it.
        val shown = mapOf(
            "Restore from a backup file" to English.string("setup_welcome_restore"),
            "Welcome back" to English.string("lock_mp_title_welcome_back"),
            "Forgot master password?" to English.string("lock_mp_forgot"),
            "Forgot PIN? Use master password" to English.string("lock_pad_forgot"),
            "Cancel it" to English.string("list_banner_cancel_it"),
        )
        for ((name, string) in shown) {
            assertTrue("the pages quote “$name”", name in quoted)
            assertEquals(name, string)
        }
        assertTrue(English.string("lock_mp_open_from_before").startsWith("Open my passwords from before "))
        assertTrue("Open my passwords from before …" in quoted)
        // The Settings path, with the names of the Settings screens.
        val path = "${English.string("settings_title")} › ${English.string("settings_group_app_lock")} › ${English.string("settings_change_master_password")} ›"
        assertTrue(all.contains(path))
        // The rest are the phone's own words or the app's states: "Restricted", "Clear storage", "Copy apps and data", "rooted".
        assertEquals(setOf("Restricted", "Clear storage", "Copy apps and data", "rooted") + shown.keys + "Open my passwords from before …", quoted)
    }

    @Test
    fun version_namesTheVersionThePhoneAndATestBuild() {
        val release = page(HelpPage.VERSION, facts.copy(versionName = "1.2", versionCode = 7, androidRelease = "9"))
        assertTrue(release.contains("Version 1.2 (7)"))
        assertTrue(release.contains("This phone: Android 9"))
        assertFalse(release.contains("Test build"))
        val blocks = HelpPages.blocks(HelpPage.VERSION, facts)
        assertTrue(blocks.first() is HelpBlock.AppHead)
        assertEquals(Route.Licences, blocks.filterIsInstance<HelpBlock.Open>().single().route)
        assertTrue(page(HelpPage.VERSION, facts.copy(testBuild = true)).contains("Test build: made for checking the app."))
        assertFalse(page(HelpPage.VERSION, facts.copy(androidRelease = "")).contains("This phone"))
        assertEquals(R.string.help_title, HelpPages.barTitle(HelpPage.PROTECTION))
        assertEquals(R.string.help_version_title, HelpPages.barTitle(HelpPage.VERSION))
    }

    @Test
    fun thePagesUsePlainWords() {
        // ux.md §7: no technical words. The licences page names libraries and is left out.
        val words = Regex("""\b(vault|encrypt\w*|decrypt\w*|KDF|Argon\w*|entropy|keystore|hash\w*|token|cipher)\b""", RegexOption.IGNORE_CASE)
        val pageNames = English.helpNames().filter { !it.startsWith("help_licences_") }
        for (name in pageNames) {
            val texts = runCatching { listOf(English.string(name)) }.getOrElse { listOf(English.plural(name, 1), English.plural(name, 2)) }
            for (text in texts) assertFalse("$name: \"$text\"", words.containsMatchIn(text))
        }
    }

    private fun textsOf(block: HelpBlock): List<UiText> = when (block) {
        is HelpBlock.Heading -> listOf(block.text)
        is HelpBlock.Paragraph -> listOf(block.text)
        is HelpBlock.AppHead -> listOf(block.version)
        is HelpBlock.Open -> listOf(block.text)
    }.flatMap { listOf(it) + it.nested() }

    private fun UiText.nested(): List<UiText> = when (this) {
        is UiText.Res -> args.filterIsInstance<UiText>()
        is UiText.Plural -> args.filterIsInstance<UiText>()
    }.flatMap { listOf(it) + it.nested() }

    private fun UiText.id(): Int = when (this) {
        is UiText.Res -> id
        is UiText.Plural -> id
    }
}
