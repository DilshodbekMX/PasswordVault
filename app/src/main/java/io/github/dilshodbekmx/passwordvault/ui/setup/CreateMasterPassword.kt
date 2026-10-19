package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.Passphrase
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.ui.common.Banner
import io.github.dilshodbekmx.passwordvault.ui.common.BannerKind
import io.github.dilshodbekmx.passwordvault.ui.common.SecretText
import io.github.dilshodbekmx.passwordvault.ui.common.StrengthBar
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultFieldDefaults
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSecureField
import io.github.dilshodbekmx.passwordvault.ui.common.userText

/**
 * "Choose your master password" (ux.md §5.3, §7.1, §7.2; lock.md F1 step 2): what the master
 * password is, the warning card, then six suggested words (the default view) or a password of one's
 * own with its strength meter and checklist. The own password's button is never disabled; when an
 * item is missing it says which, and when all pass it asks whether the password is used for another
 * account. [own] is the field's state, held by [SetupViewModel].
 */
@Composable
fun CreateMasterPasswordScreen(
    state: SetupState,
    own: TextFieldState,
    onUseWords: () -> Unit,
    onOtherWords: () -> Unit,
    onTypeOwn: () -> Unit,
    onShowWords: () -> Unit,
    onUseOwn: () -> Unit,
    onReuseAnswer: (usedElsewhere: Boolean) -> Unit,
    onReuseDismiss: () -> Unit,
) {
    SetupPage(title = stringResource(R.string.setup_create_title)) {
        Text(text = stringResource(R.string.setup_create_body), style = MaterialTheme.typography.bodyLarge)
        WarningCard()
        when (state.view) {
            CreateView.SUGGESTED -> SuggestedWords(state, onUseWords, onOtherWords, onTypeOwn)
            CreateView.OWN -> OwnPassword(state, own, onUseOwn, onShowWords)
        }
    }
    if (state.askReuse) ReuseQuestion(onReuseAnswer, onReuseDismiss)
}

/** The warning card of ux.md §5.3: an error-coloured border and icon, the two paragraphs of §7.2. */
@Composable
private fun WarningCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_common_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.setup_create_warning_reset), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.setup_create_warning_reuse), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The default view: the six words as a numbered list of plain text, the whole password as it is typed, and the three buttons. */
@Composable
private fun ColumnScope.SuggestedWords(state: SetupState, onUseWords: () -> Unit, onOtherWords: () -> Unit, onTypeOwn: () -> Unit) {
    if (state.interrupted) {
        Banner(
            text = stringResource(R.string.setup_create_interrupted),
            kind = BannerKind.WARNING,
            modifier = Modifier.testTag(SetupTags.INTERRUPTED),
        )
    }
    val passphrase = state.passphrase
    if (passphrase == null) {
        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    } else {
        NumberedWords(passphrase.words, Modifier.testTag(SetupTags.WORDS))
        WholePassphrase(passphrase.words)
        Text(text = stringResource(R.string.setup_create_latin), style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onUseWords, enabled = passphrase != null, modifier = Modifier.fillMaxWidth().testTag(SetupTags.USE_WORDS)) {
        Text(stringResource(R.string.setup_create_use_words))
    }
    TextButton(onClick = onOtherWords, modifier = Modifier.fillMaxWidth().testTag(SetupTags.OTHER_WORDS)) {
        Text(stringResource(R.string.setup_create_other_words))
    }
    TextButton(onClick = onTypeOwn, modifier = Modifier.fillMaxWidth().testTag(SetupTags.TYPE_OWN)) {
        Text(stringResource(R.string.setup_create_type_own))
    }
}

/**
 * The suggested words as a numbered list of plain text in large type, not buttons (ux.md §5.3, §8):
 * TalkBack reads "Word 1 of 6: vowel". Left to right in every language (ux.md §3), and hidden from
 * accessibility services that are not accessibility tools ([V-P5]). "Write it down on paper" shows
 * them the same way.
 */
@Composable
internal fun NumberedWords(words: List<String>, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            words.forEachIndexed { index, word ->
                val description = stringResource(R.string.setup_create_word, index + 1, words.size, word)
                Row(
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = description
                        isSensitiveData = true
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 32.dp),
                    )
                    Text(text = word, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.userText())
                }
            }
        }
    }
}

/** "You type it like this:" and the words joined as they are typed, in monospace (ux.md §5.3). */
@Composable
internal fun WholePassphrase(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.setup_create_typed_as), style = MaterialTheme.typography.bodyMedium)
        SecretText(
            value = words.joinToString(Passphrase.SEPARATOR.toString()),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag(SetupTags.PASSPHRASE),
        )
    }
}

/**
 * A password of one's own (ux.md §5.3, "Own password view"): the field with show and hide, the
 * strength meter with its hint, the mixed-scripts question, the checklist (judged after folding,
 * architecture.md §4.6), what is missing when "Use this password" is pressed, and the way back to
 * the words.
 */
@Composable
private fun ColumnScope.OwnPassword(state: SetupState, own: TextFieldState, onUseOwn: () -> Unit, onShowWords: () -> Unit) {
    val message = state.ownMessage
    val check = state.check
    VaultSecureField(
        state = own,
        modifier = Modifier.fillMaxWidth().testTag(SetupTags.OWN_FIELD),
        label = { Text(stringResource(R.string.setup_field_label)) },
        keyboardOptions = VaultFieldDefaults.Secret.copy(imeAction = ImeAction.Done),
        onKeyboardAction = KeyboardActionHandler { onUseOwn() },
        isError = message != null,
        supportingText = message?.let { { MessageText(it, SetupTags.OWN_MESSAGE) } },
    )
    if (check != null) {
        StrengthBar(score = check.strength.score, modifier = Modifier.fillMaxWidth())
        state.strengthHint?.let { Text(text = it.resolve(), style = MaterialTheme.typography.bodyMedium) }
        if (check.mixesScripts) {
            Text(
                text = stringResource(R.string.setup_own_mixed_scripts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChecklistItem(R.string.setup_own_rule_length, met = check?.longEnough == true, tag = SetupTags.RULE_LENGTH)
        ChecklistItem(R.string.setup_own_rule_hard, met = check?.hardToGuess == true, tag = SetupTags.RULE_HARD)
    }
    Button(onClick = onUseOwn, modifier = Modifier.fillMaxWidth().testTag(SetupTags.USE_OWN)) {
        Text(stringResource(R.string.setup_own_use))
    }
    TextButton(onClick = onShowWords, modifier = Modifier.fillMaxWidth().testTag(SetupTags.SHOW_WORDS)) {
        Text(stringResource(R.string.setup_create_show_words))
    }
}

/** One item of the checklist: a filled check when it is met, an empty circle when not; TalkBack reads "…: done" or "…: not yet". */
@Composable
private fun ChecklistItem(@StringRes text: Int, met: Boolean, tag: String) {
    val label = stringResource(text)
    val description = stringResource(if (met) R.string.setup_own_rule_met else R.string.setup_own_rule_not_met, label)
    Row(
        modifier = Modifier.testTag(tag).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (met) R.drawable.ic_setup_rule_met else R.drawable.ic_setup_rule_open),
            contentDescription = null,
            tint = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The message under a field (its supporting text), in the error colour, which TalkBack announces when it appears (ux.md §8). */
@Composable
internal fun MessageText(message: UiText, tag: String) {
    Text(
        text = message.resolve(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "Do you use this password for any other account?" with "Yes" and "No, it's new" (ux.md §5.3). */
@Composable
private fun ReuseQuestion(onAnswer: (usedElsewhere: Boolean) -> Unit, onDismiss: () -> Unit) {
    VaultDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(SetupTags.REUSE_NO)) {
                Text(stringResource(R.string.setup_own_reuse_no))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(true) }, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(SetupTags.REUSE_YES)) {
                Text(stringResource(R.string.setup_own_reuse_yes))
            }
        },
        text = { Text(text = stringResource(R.string.setup_own_reuse_question), style = MaterialTheme.typography.bodyLarge) },
    )
}
