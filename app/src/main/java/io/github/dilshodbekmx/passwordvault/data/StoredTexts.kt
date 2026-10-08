package io.github.dilshodbekmx.passwordvault.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import java.util.Locale

/**
 * The texts that the repository's own changes store in the vault (architecture.md §3.7, §5.4):
 * only "(from backup)", which `VaultPort.mergeAside` gives an account of a vault put aside whose
 * id is held by an account with other content (§4.11, ux.md §5.21): `data_from_backup` of
 * `strings_data.xml`.
 *
 * **The language.** A stored text is in the language the screens show when the change is made
 * ([locale]: `ScreenLanguage.locale`). It is made away from any screen, so it is read from [app]'s
 * resources under a configuration of that locale, never in the application context's own language,
 * which on Android 7 to 12 stays the phone's (i18n.md §1.4.3). The resources of the last locale are
 * kept, because a merge asks once per renamed account.
 *
 * [untitled] and [fromFile] are not texts of the repository's changes: the form and the importer
 * store their own (build plan 3.6, 5.4), and nothing here calls these two.
 */
internal class StoredTexts(private val app: Context, private val locale: () -> Locale) : EditTexts {
    private class InLocale(val locale: Locale, val resources: Resources)

    @Volatile private var last: InLocale? = null

    override fun untitled(nowMs: Long): String = "Untitled"

    override fun fromBackup(title: String): String = resources().getString(R.string.data_from_backup, title)

    override fun fromFile(title: String): String = "$title (from file)"

    /** The app's resources in the screens' language now. */
    private fun resources(): Resources {
        val now = locale()
        last?.let { if (it.locale == now) return it.resources }
        return resourcesIn(app, now).also { last = InLocale(now, it) }
    }

    companion object {
        /** [app]'s resources under a configuration of [locale] alone. */
        fun resourcesIn(app: Context, locale: Locale): Resources {
            val configuration = Configuration(app.resources.configuration)
            configuration.setLocale(locale)
            return app.createConfigurationContext(configuration).resources
        }
    }
}
