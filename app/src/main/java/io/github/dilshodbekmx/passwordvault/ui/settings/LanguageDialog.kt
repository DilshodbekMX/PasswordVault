package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguage
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import java.util.Locale

/**
 * A choice of the language dialog (i18n.md §1.4.5; ux.md §5.1, §5.18), in the dialog's fixed order,
 * which is never re-sorted by the current language: "System language", Oʻzbekcha, Ўзбекча, English,
 * Русский, and in debug builds the two pseudo-locales of i18n.md §5, "English (XA)" and "Arabic (XB)".
 *
 * @property language the [AppLanguage] it stands for; null for a pseudo-locale.
 */
enum class LanguageChoice(val language: AppLanguage?, private val ownTag: String? = null) {
    SYSTEM(AppLanguage.SYSTEM),
    UZ_LATN(AppLanguage.UZ_LATN, "uz-Latn"),
    UZ_CYRL(AppLanguage.UZ_CYRL),
    EN(AppLanguage.EN),
    RU(AppLanguage.RU),
    EN_XA(null, "en-XA"),
    AR_XB(null, "ar-XB"),
    ;

    /**
     * The language tags handed to Android ([AppLanguages.setTags]): empty for the system language,
     * [AppLanguage.tag] (the tag of the generated locale config) for Ўзбекча, English and Русский, and
     * `uz-Latn` for Oʻzbekcha. Android 17's own page Settings › Apps › PasswordVault › Language marks
     * `uz-Latn` as the app's language and never the config's `uz` (seen on API 37 on 2026-10-09, with
     * `en`, `ru` and `uz-Latn` marked; it marks no Uzbek Cyrillic tag at all, `uz-Cyrl` nor the
     * `uz-Cyrl-UZ` it sets itself). Both reach `values-uz`, whose likely script is Latin (i18n.md
     * §1.2), and both read back as [AppLanguage.UZ_LATN].
     */
    val tags: String get() = ownTag ?: language?.tag.orEmpty()

    companion object {
        /** The dialog's choices, in their order: the five, and the two pseudo-locales when [pseudo] (debug builds). */
        fun offered(pseudo: Boolean): List<LanguageChoice> = entries.filter { pseudo || it.language != null }

        /**
         * The choice that the app's own locales [appLocales] stand for ([AppLanguages.appLocales]; empty
         * for the system language): a pseudo-locale when [pseudo] (debug builds) and it is the first,
         * otherwise the [AppLanguage] of the first locale ([AppLanguages.of]). A language that is not
         * one of ours ("de", set by other means than this dialog) is shown as the system language,
         * whose texts the app then shows.
         */
        fun of(appLocales: List<Locale>, pseudo: Boolean): LanguageChoice {
            val first = appLocales.firstOrNull() ?: return SYSTEM
            if (pseudo && AppLanguages.isPseudoLocale(first)) return if (first.language == "ar") AR_XB else EN_XA
            val language = AppLanguages.of(first)
            return entries.first { it.language == language }
        }
    }
}

/** The resource ids (test tags) of the language dialog, for tests and the emulator scripts, whatever the language. */
object LanguageTags {
    /** The dialog itself, in its own window: the route's tag `pv.route.Language` is on the screen behind it. */
    const val DIALOG = "pv.language.dialog"

    /** "Cancel": closes the dialog with nothing changed. */
    const val CANCEL = "pv.language.cancel"

    /** The row of [choice]: `pv.language.SYSTEM`, `pv.language.UZ_CYRL`, … */
    fun choice(choice: LanguageChoice): String = "pv.language.${choice.name}"
}

/**
 * The language dialog (build plan 6.1; i18n.md §1.4.5; ux.md §5.1, §5.18): radio buttons for
 * [choices] in their fixed order, [selected] checked. "System language" is in the app's language and
 * has under it the language the app shows when it follows the phone, [systemShown], in that
 * language's own name. Each language is named in its own language, its text marked with that
 * language (`SpanStyle(localeList = …)`), so that it is shaped and broken as that language is.
 *
 * Tapping a choice chooses it ([onChoose]): the caller closes the dialog and applies the language.
 * "Cancel", Back and a tap outside close it with nothing changed ([onDismiss]). Every row is at least
 * 48 dp high and the whole row is the tap target (ux.md §8); TalkBack reads "1 of 5, selected".
 */
@Composable
fun LanguageDialog(
    choices: List<LanguageChoice>,
    selected: LanguageChoice?,
    systemShown: AppLanguage,
    onChoose: (LanguageChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    VaultDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag(LanguageTags.DIALOG),
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(LanguageTags.CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(text = stringResource(R.string.language_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                for (choice in choices) {
                    LanguageRow(
                        label = labelOf(choice),
                        supporting = if (choice == LanguageChoice.SYSTEM) ownName(systemShown) else null,
                        selected = choice == selected,
                        tag = LanguageTags.choice(choice),
                        onClick = { onChoose(choice) },
                    )
                }
            }
        },
    )
}

/** What a row of [choice] says: "System language" in the app's language, a language's own name, a pseudo-locale's name. */
@Composable
private fun labelOf(choice: LanguageChoice): AnnotatedString = when (choice) {
    LanguageChoice.SYSTEM -> AnnotatedString(stringResource(R.string.language_system))
    LanguageChoice.EN_XA -> AnnotatedString(stringResource(R.string.language_pseudo_en_xa))
    LanguageChoice.AR_XB -> AnnotatedString(stringResource(R.string.language_pseudo_ar_xb))
    else -> ownName(requireNotNull(choice.language))
}

/**
 * [language]'s name in its own language ("Ўзбекча"), marked as text of that language. English for
 * [AppLanguage.SYSTEM], which is never shown here: [AppLanguages.shown] never answers it.
 */
@Composable
private fun ownName(language: AppLanguage): AnnotatedString {
    val shown = if (language == AppLanguage.SYSTEM) AppLanguage.EN else language
    val name = stringResource(requireNotNull(shown.ownName))
    return buildAnnotatedString {
        withStyle(SpanStyle(localeList = LocaleList(requireNotNull(shown.tag)))) { append(name) }
    }
}

/** One choice: its radio button, its name and the line under it; the whole row is the tap target. */
@Composable
private fun LanguageRow(
    label: AnnotatedString,
    supporting: AnnotatedString?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag)
            .padding(vertical = if (supporting != null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(text = supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
