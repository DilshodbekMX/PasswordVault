package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.ui.common.SecretText
import io.github.dilshodbekmx.passwordvault.ui.common.StrengthBar
import io.github.dilshodbekmx.passwordvault.ui.common.VaultSheet
import kotlin.math.roundToInt

/**
 * The password generator sheet (ux.md §5.15): a bottom sheet (`VaultSheet`, which predictive back
 * closes) with the made password large, in monospace with coloured digits and symbols, its
 * strength, "Random" or "Words" and their choices, the hint, and "Use this password". There is no
 * copy button. [model] holds everything; [onUse] is "Use this password", [onDismiss] closes the sheet.
 *
 * The test tags (resource ids, for the emulator scripts) start with `pv.gen.`: `preview`, `new`,
 * `random`, `words`, `shorter`, `longer`, `upper`, `lower`, `digits`, `symbols`, `lookAlike`,
 * `fewerWords`, `moreWords`, `capitalize`, `addNumber`, `use`.
 */
@Composable
fun GeneratorSheet(model: GeneratorModel, onUse: () -> Unit, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.open) return
    val formatters = rememberFormatters()
    val options = state.options
    VaultSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.edit_gen_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    val preview = state.preview
                    if (preview != null) {
                        SecretText(value = preview, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(GenTags.PREVIEW))
                    }
                }
                IconButton(onClick = model::again, modifier = Modifier.testTag(GenTags.NEW)) {
                    Icon(painterResource(R.drawable.ic_edit_refresh), contentDescription = stringResource(R.string.edit_gen_new))
                }
            }
            state.strength?.let { StrengthBar(score = it.score) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                KindButton(GeneratorOptions.Kind.RANDOM, options.kind, 0, R.string.edit_gen_random, GenTags.RANDOM, model)
                KindButton(GeneratorOptions.Kind.WORDS, options.kind, 1, R.string.edit_gen_words, GenTags.WORDS, model)
            }
            when (options.kind) {
                GeneratorOptions.Kind.RANDOM -> {
                    Stepper(
                        label = R.string.edit_gen_length,
                        value = options.length,
                        range = GeneratorOptions.MIN_LENGTH..GeneratorOptions.MAX_LENGTH,
                        shown = formatters.number(options.length.toLong()),
                        onValue = model::setLength,
                        minus = Step(R.string.edit_gen_shorter, GenTags.SHORTER, model::shorter),
                        plus = Step(R.string.edit_gen_longer, GenTags.LONGER, model::longer),
                    )
                    SwitchRow(R.string.edit_gen_upper, options.upper, GenTags.UPPER, model::setUpper)
                    SwitchRow(R.string.edit_gen_lower, options.lower, GenTags.LOWER, model::setLower)
                    SwitchRow(R.string.edit_gen_digits, options.digits, GenTags.DIGITS, model::setDigits)
                    SwitchRow(R.string.edit_gen_symbols, options.symbols, GenTags.SYMBOLS, model::setSymbols)
                    if (state.oneKindNeeded) {
                        Text(
                            text = stringResource(R.string.edit_gen_one_kind),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SwitchRow(R.string.edit_gen_look_alike, options.avoidLookAlike, GenTags.LOOK_ALIKE, model::setAvoidLookAlike)
                }
                GeneratorOptions.Kind.WORDS -> {
                    Stepper(
                        label = R.string.edit_gen_word_count,
                        value = options.words,
                        range = GeneratorOptions.MIN_WORDS..GeneratorOptions.MAX_WORDS,
                        shown = formatters.number(options.words.toLong()),
                        onValue = model::setWords,
                        minus = Step(R.string.edit_gen_fewer_words, GenTags.FEWER_WORDS, model::fewerWords),
                        plus = Step(R.string.edit_gen_more_words, GenTags.MORE_WORDS, model::moreWords),
                    )
                    Text(text = stringResource(R.string.edit_gen_separator), style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SEPARATORS.forEachIndexed { index, choice ->
                            val name = stringResource(choice.name)
                            SegmentedButton(
                                selected = options.separator == choice.separator,
                                onClick = { model.setSeparator(choice.separator) },
                                shape = SegmentedButtonDefaults.itemShape(index, SEPARATORS.size),
                                modifier = Modifier.semantics { contentDescription = name }.testTag(GenTags.SEPARATOR + choice.separator.name),
                            ) {
                                Text(text = stringResource(choice.shown))
                            }
                        }
                    }
                    SwitchRow(R.string.edit_gen_capitalize, options.capitalize, GenTags.CAPITALIZE, model::setCapitalize)
                    SwitchRow(R.string.edit_gen_add_number, options.addNumber, GenTags.ADD_NUMBER, model::setAddNumber)
                }
            }
            Text(
                text = stringResource(R.string.edit_gen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onUse, enabled = state.preview != null, modifier = Modifier.fillMaxWidth().testTag(GenTags.USE)) {
                Text(text = stringResource(R.string.edit_gen_use))
            }
        }
    }
}

@Composable
private fun androidx.compose.material3.SingleChoiceSegmentedButtonRowScope.KindButton(
    kind: GeneratorOptions.Kind,
    chosen: GeneratorOptions.Kind,
    index: Int,
    @StringRes text: Int,
    tag: String,
    model: GeneratorModel,
) {
    SegmentedButton(
        selected = kind == chosen,
        onClick = { model.setKind(kind) },
        shape = SegmentedButtonDefaults.itemShape(index, 2),
        modifier = Modifier.testTag(tag),
    ) {
        Text(text = stringResource(text))
    }
}

/** A − or + button of a [Stepper]. */
private class Step(@StringRes val description: Int, val tag: String, val onClick: () -> Unit)

/**
 * A number with its label, a slider and − and + buttons (ux.md §5.15: sliders alone are hard for
 * some users). [shown] is [value] written for the language.
 */
@Composable
private fun Stepper(@StringRes label: Int, value: Int, range: IntRange, shown: String, onValue: (Int) -> Unit, minus: Step, plus: Step) {
    val labelText = stringResource(label)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = labelText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(text = shown, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(minus, R.drawable.ic_edit_minus, enabled = value > range.first)
            Slider(
                value = value.toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
                modifier = Modifier.weight(1f).semantics { contentDescription = labelText },
            )
            StepButton(plus, R.drawable.ic_edit_add, enabled = value < range.last)
        }
    }
}

@Composable
private fun StepButton(step: Step, @DrawableRes icon: Int, enabled: Boolean) {
    IconButton(onClick = step.onClick, enabled = enabled, modifier = Modifier.testTag(step.tag)) {
        Icon(painterResource(icon), contentDescription = stringResource(step.description))
    }
}

/** A switch with its text, the whole row one toggle for TalkBack and Switch Access (ux.md §8). */
@Composable
internal fun SwitchRow(@StringRes text: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(text), modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A separator choice: what its button shows and the name TalkBack reads. */
private class SeparatorChoice(val separator: GeneratorOptions.Separator, @StringRes val shown: Int, @StringRes val name: Int)

private val SEPARATORS = listOf(
    SeparatorChoice(GeneratorOptions.Separator.HYPHEN, R.string.edit_gen_symbol_hyphen, R.string.edit_gen_separator_hyphen),
    SeparatorChoice(GeneratorOptions.Separator.DOT, R.string.edit_gen_symbol_dot, R.string.edit_gen_separator_dot),
    SeparatorChoice(GeneratorOptions.Separator.UNDERSCORE, R.string.edit_gen_symbol_underscore, R.string.edit_gen_separator_underscore),
    SeparatorChoice(GeneratorOptions.Separator.SPACE, R.string.edit_gen_separator_space, R.string.edit_gen_separator_space),
)

/** The test tags of the generator sheet. */
object GenTags {
    const val PREVIEW = "pv.gen.preview"
    const val NEW = "pv.gen.new"
    const val RANDOM = "pv.gen.random"
    const val WORDS = "pv.gen.words"
    const val SHORTER = "pv.gen.shorter"
    const val LONGER = "pv.gen.longer"
    const val UPPER = "pv.gen.upper"
    const val LOWER = "pv.gen.lower"
    const val DIGITS = "pv.gen.digits"
    const val SYMBOLS = "pv.gen.symbols"
    const val LOOK_ALIKE = "pv.gen.lookAlike"
    const val FEWER_WORDS = "pv.gen.fewerWords"
    const val MORE_WORDS = "pv.gen.moreWords"
    const val SEPARATOR = "pv.gen.separator."
    const val CAPITALIZE = "pv.gen.capitalize"
    const val ADD_NUMBER = "pv.gen.addNumber"
    const val USE = "pv.gen.use"
}
