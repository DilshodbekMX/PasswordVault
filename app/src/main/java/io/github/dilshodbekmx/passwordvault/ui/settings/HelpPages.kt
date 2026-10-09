package io.github.dilshodbekmx.passwordvault.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay

/**
 * One block of a help page: a text of `strings_help.xml` with its arguments, never a finished
 * sentence, so that a language change reaches it ([UiText]).
 */
sealed interface HelpBlock {
    /** A heading inside the page; TalkBack can move from heading to heading (ux.md §8). */
    data class Heading(val text: UiText) : HelpBlock

    /** A paragraph. */
    data class Paragraph(val text: UiText) : HelpBlock

    /** The head of the Version page: the logo, the app's name ([R.string.app_name]) and [version]. */
    data class AppHead(val version: UiText) : HelpBlock

    /** A button that opens [route], named [text]: another help page, or the licences. */
    data class Open(val route: Route, val text: UiText) : HelpBlock
}

/**
 * What the pages say that is not the same on every phone: the clipboard delay chosen in Settings
 * (the pages say when a copy is removed, not the default's "1 minute"), the build's version, the
 * phone's Android version and whether this is a test build (`BuildConfig.ALLOW_SOFTWARE_KEYSTORE`,
 * the build whose lock screens say "TEST BUILD").
 */
data class HelpFacts(
    val clipDelay: ClipDelay,
    val versionName: String,
    val versionCode: Int,
    val androidRelease: String,
    val testBuild: Boolean,
) {
    companion object {
        /** This build on this phone, with [clipDelay]. */
        fun of(clipDelay: ClipDelay): HelpFacts = HelpFacts(
            clipDelay = clipDelay,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidRelease = Build.VERSION.RELEASE ?: "",
            testBuild = BuildConfig.ALLOW_SOFTWARE_KEYSTORE,
        )
    }
}

/**
 * The help pages of Settings › Help (ux.md §5.22; build plan 7.3): what each one says, from ux.md
 * §7.6 and what lock.md §2.3 and §16.2 item 12 ask Help to say, as the app now is:
 *
 * - **How your passwords are protected.** The clipboard sentences give the delay chosen in Settings
 *   and say that anything else copied in that time may go too: a clear that cannot read the
 *   clipboard clears whatever it holds (Android 10 and later in the background, and Android 9 while
 *   the phone is locked; ux.md §6 step 2, build-plan.md §12 decision 7). A force-stop and "Restricted"
 *   battery use leave the copy until the app is opened (architecture.md D-14). Accessibility: a field
 *   drawn as dots gives every accessibility service only mask characters, on every Android version,
 *   while a password shown on the screen is read by a service the user turned on before Android 14
 *   (lock.md §2.3, §13.5; build plan 3.12). Also from lock.md §2.3: a PIN that is also the phone's
 *   or a bank card's, a rooted phone, a new master password asked for with the PIN (F11) and
 *   "Clear storage" or an uninstall.
 * - **If you lose your phone or move to a new one.** A device-to-device transfer does not carry the
 *   vault (the manifest's extraction rules exclude every domain; architecture.md §7.3, "Android Auto
 *   Backup or device transfer of the vault file"), so the backup file is the way to a new phone; ux.md §7.6's
 *   sentence about "Copy apps and data" is replaced by that and by "Welcome back" (lock.md F14), which
 *   a phone-copy tool or a reinstall that kept the data leads to.
 * - **If you forget your master password** and **If you forget your PIN**: ux.md §7.6 with lock.md
 *   F11, F16b and §13.3 (the PIN limit).
 * - **Version**: the app's name, the version and build, the phone's Android version, and in a test
 *   build a line that says so.
 *
 * The app's name comes from [R.string.app_name] alone (architecture.md D-23), never from a help text.
 */
object HelpPages {
    /** The heading of [page] (the words of its row in Settings). */
    @StringRes
    fun title(page: HelpPage): Int = when (page) {
        HelpPage.PROTECTION -> R.string.help_protection_title
        HelpPage.LOST_PHONE -> R.string.help_lost_title
        HelpPage.FORGOT_MASTER_PASSWORD -> R.string.help_forgot_mp_title
        HelpPage.FORGOT_PIN -> R.string.help_forgot_pin_title
        HelpPage.VERSION -> R.string.help_version_title
    }

    /** The title in the bar at the top of [page]: "Help", or "Version" for the Version page, which has no heading of its own. */
    @StringRes
    fun barTitle(page: HelpPage): Int = if (page == HelpPage.VERSION) R.string.help_version_title else R.string.help_title

    /** What [page] shows, in order, under its heading. */
    fun blocks(page: HelpPage, facts: HelpFacts): List<HelpBlock> = when (page) {
        HelpPage.PROTECTION -> listOf(
            text(R.string.help_protection_file),
            text(R.string.help_protection_offline),
            text(R.string.help_protection_screenshots),
            heading(R.string.help_protection_heading_copy),
            HelpBlock.Paragraph(copiedText(facts.clipDelay)),
            text(R.string.help_force_stop),
            text(R.string.help_never_pin),
            heading(R.string.help_protection_heading_pin),
            text(R.string.help_protection_fingerprint),
            text(R.string.help_protection_pin_reuse),
            text(R.string.help_protection_rooted),
            text(R.string.help_protection_new_mp),
            heading(R.string.help_protection_heading_limits),
            text(R.string.help_protection_limits),
            text(R.string.help_protection_accessibility),
            text(R.string.help_protection_apk),
            text(R.string.help_clear_storage),
            text(R.string.help_protection_delete),
        )
        HelpPage.LOST_PHONE -> listOf(
            text(R.string.help_lost_locked),
            HelpBlock.Paragraph(UiText.of(R.string.help_lost_restore, appName())),
            text(R.string.help_lost_transfer),
            HelpBlock.Paragraph(UiText.of(R.string.help_lost_welcome_back, appName())),
            text(R.string.help_lost_backup),
        )
        HelpPage.FORGOT_MASTER_PASSWORD -> listOf(
            text(R.string.help_forgot_mp_nobody),
            text(R.string.help_forgot_mp_new),
            text(R.string.help_forgot_mp_wait),
            text(R.string.help_forgot_mp_backup),
            text(R.string.help_forgot_mp_replaced),
            text(R.string.help_forgot_mp_cant_open),
            open(HelpPage.FORGOT_PIN),
        )
        HelpPage.FORGOT_PIN -> listOf(
            text(R.string.help_forgot_pin_how),
            text(R.string.help_forgot_pin_limit),
            text(R.string.help_clear_storage),
            open(HelpPage.FORGOT_MASTER_PASSWORD),
        )
        HelpPage.VERSION -> buildList {
            add(HelpBlock.AppHead(UiText.of(R.string.help_version_number, facts.versionName, facts.versionCode)))
            add(HelpBlock.Paragraph(UiText.of(R.string.help_version_about, appName())))
            if (facts.androidRelease.isNotEmpty()) add(HelpBlock.Paragraph(UiText.of(R.string.help_version_android, facts.androidRelease)))
            if (facts.testBuild) add(text(R.string.help_version_test_build))
            add(HelpBlock.Open(Route.Licences, UiText.of(R.string.help_licences_title)))
        }
    }

    /**
     * "Passwords you copy are removed from the clipboard after 1 minute. …" for [delay]: in minutes
     * when it is whole minutes, otherwise in seconds.
     */
    fun copiedText(delay: ClipDelay): UiText = if (delay.ms % MINUTE_MS == 0L) {
        UiText.plural(R.plurals.help_protection_copy_minutes, (delay.ms / MINUTE_MS).toInt())
    } else {
        UiText.plural(R.plurals.help_protection_copy_seconds, (delay.ms / SECOND_MS).toInt())
    }

    /** The app's name, from its one resource (architecture.md D-23). */
    fun appName(): UiText = UiText.of(R.string.app_name)

    private fun text(@StringRes id: Int) = HelpBlock.Paragraph(UiText.of(id))

    private fun heading(@StringRes id: Int) = HelpBlock.Heading(UiText.of(id))

    private fun open(page: HelpPage) = HelpBlock.Open(Route.Help(page), UiText.of(title(page)))

    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60_000L
}
