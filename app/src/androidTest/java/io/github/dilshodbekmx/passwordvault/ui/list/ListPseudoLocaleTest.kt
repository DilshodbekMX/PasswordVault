package io.github.dilshodbekmx.passwordvault.ui.list

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.layout.press
import io.github.dilshodbekmx.passwordvault.ui.layout.revealTag
import io.github.dilshodbekmx.passwordvault.ui.layout.revealText
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the list's screens walked once in the `en-XA` pseudo-locale (the debug
 * build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `list_`
 * string and plural comes out pseudo-localized, so none is plain English or marked untranslatable;
 * the screens drawn in the pseudo-locale show the pseudo-localized texts, so none is written into
 * the code; and each screen is kept as a picture in the app's cache directory
 * (`cache/pv-screens/list-*.png`) for a person to look for clipped text.
 */
@RunWith(AndroidJUnit4::class)
class ListPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private var rig: ListRig? = null

    @After
    fun closeRig() {
        rig?.close()
    }

    private fun pseudo(tag: String): Context {
        val base = compose.activity
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    @Composable
    private fun InLocale(context: Context, content: @Composable () -> Unit) {
        val configuration = context.resources.configuration
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            // The direction comes from the window, not from these resources: ar-XB is laid out right to left.
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            PasswordVaultTheme(ThemeChoice.LIGHT, content)
        }
    }

    private fun entry(id: String, title: String, username: String = "", changePending: Boolean = false, favourite: Boolean = false, deletedAt: Long? = null) =
        Entry(
            id = id,
            title = title,
            username = username,
            password = "pw-$id",
            favourite = favourite,
            changePending = changePending,
            pendingOldPassword = if (changePending) "old-$id" else null,
            createdAt = now - 30 * day,
            updatedAt = now - 30 * day,
            deletedAt = deletedAt,
        )

    private val vault = Vault(
        entries = listOf(
            entry("gm", "Gmail", "aziz@gmail.com", favourite = true),
            entry("fb", "Facebook", changePending = true),
            entry("tg", "Telegram", "+998901234567"),
            entry("oz", "Oʻzbekiston pochtasi", "dilshod"),
            entry("old", "Old forum", deletedAt = now - 2 * day),
        ),
        plainExports = listOf(FileRef("content://docs/1", "passwords-2026-10-05.csv", now - 2 * 3_600_000L)),
    )

    /**
     * Keeps a picture of the whole screen in `cache/pv-screens/`: dialogs included, which are windows
     * of their own (`captureToImage` of a dialog's root gave the activity behind it). The debug build
     * does not set FLAG_SECURE, so the screen can be read.
     */
    private fun save(name: String) {
        LayoutHook.look(compose, "list", name)
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "pv-screens").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "list-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyListStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = pseudo("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("list_") }
        assertTrue("the list's strings are found", strings.size >= 60)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("list_") }
        assertTrue("the list's plurals are found", plurals.size >= 5)
        for (field in plurals) {
            val id = field.getInt(null)
            for (count in listOf(1, 5)) {
                val args = arrayOf<Any>(count, count)
                assertNotEquals(
                    "plural ${field.name} for $count",
                    english.resources.getQuantityString(id, count, *args),
                    pseudo.resources.getQuantityString(id, count, *args),
                )
            }
        }
    }

    @Test
    fun passwordsInEnXa() = passwords("en-XA")

    @Test
    fun passwordsInArXb() = passwords("ar-XB")

    @Test
    fun recentlyDeletedInEnXa() = recentlyDeleted("en-XA")

    @Test
    fun recentlyDeletedInArXb() = recentlyDeleted("ar-XB")

    /** Passwords with two banners, a favourite and a pending change; then no results; then "Type it now". */
    private fun passwords(tag: String) {
        val context = pseudo(tag)
        val made = ListRig(vault).also { rig = it }
        made.lock.status.value = LockStatus.NONE.copy(practiceOverdueSinceMs = now - 40 * day)
        made.awaitList()
        lateinit var vm: PasswordsViewModel
        compose.setContent {
            InLocale(context) {
                vm = viewModel { made.passwordsViewModel() }
                PasswordsScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.banners.size == 2 && vm.state.value.body is ListBody.Browse }
        val res = context.resources
        for (text in listOf(
            res.getString(R.string.list_title),
            res.getString(R.string.list_favourites),
            res.getString(R.string.list_banner_type_it_now),
            res.getString(R.string.list_banner_deleted_it_myself),
        )) {
            compose.revealText(text, substring = true).assertExists()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.list_title)).assertDoesNotExist()
        save("passwords-$tag")

        compose.onNodeWithTag(ListTags.SEARCH).performTextInput("gmal")
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.NoResults }
        compose.revealText(res.getString(R.string.list_no_results, "gmal")).assertExists()
        save("no-results-$tag")

        compose.runOnUiThread { vm.clearQuery() }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
        compose.onNodeWithTag(ListTags.bannerButton(vm.state.value.banners.first(), "typeItNow")).press()
        compose.revealText(res.getString(R.string.list_practice_title)).assertExists()
        save("practice-$tag")
    }

    /** Recently deleted with one account, then the question of "Empty". */
    private fun recentlyDeleted(tag: String) {
        val context = pseudo(tag)
        val made = ListRig(vault).also { rig = it }
        lateinit var vm: RecentlyDeletedViewModel
        compose.setContent {
            InLocale(context) {
                vm = viewModel { made.recentlyDeletedViewModel() }
                RecentlyDeletedScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.rows.value?.size == 1 }
        val res = context.resources
        compose.revealText(res.getString(R.string.list_deleted_title)).assertExists()
        compose.revealText(res.getString(R.string.list_deleted_info)).assertExists()
        compose.revealText(res.getString(R.string.list_deleted_restore)).assertExists()
        save("recently-deleted-$tag")
        compose.revealTag(ListTags.DELETED_EMPTY).press()
        // The count as the screen writes it in this language (ar-XB has Arabic-Indic digits on API 24).
        val one = Formatters(res.configuration.locales[0], hour24 = true).number(1)
        compose.revealText(res.getQuantityString(R.plurals.list_deleted_empty_question, 1, one)).assertExists()
        save("recently-deleted-question-$tag")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
