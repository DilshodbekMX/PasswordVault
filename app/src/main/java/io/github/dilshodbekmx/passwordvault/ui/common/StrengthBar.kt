package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/**
 * The strength meter under a password field (ux.md §5.3, §5.14): 4 segments and a word, "Very
 * weak", "Weak", "Good" or "Strong" for zxcvbn's score 0–1, 2, 3 and 4 (`StrengthMeter`). The word
 * and the number of segments say it, not the colour alone (ux.md §8); TalkBack reads "Password
 * strength: Weak".
 *
 * @param score zxcvbn's score, 0 to 4; values outside are clamped.
 */
@Composable
fun StrengthBar(score: Int, modifier: Modifier = Modifier) {
    val level = strengthLevel(score)
    val word = stringResource(strengthWord(level))
    val description = stringResource(R.string.common_strength_description, word)
    val colour = if (level <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(STRENGTH_LEVELS) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < level) colour else off),
                )
            }
        }
        Text(text = word, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

/** The number of segments for zxcvbn's [score]: 0 and 1 are both "Very weak" (ux.md §5.3). */
internal fun strengthLevel(score: Int): Int = score.coerceIn(1, STRENGTH_LEVELS)

@StringRes
private fun strengthWord(level: Int): Int = when (level) {
    1 -> R.string.common_strength_very_weak
    2 -> R.string.common_strength_weak
    3 -> R.string.common_strength_good
    else -> R.string.common_strength_strong
}

private const val STRENGTH_LEVELS = 4
