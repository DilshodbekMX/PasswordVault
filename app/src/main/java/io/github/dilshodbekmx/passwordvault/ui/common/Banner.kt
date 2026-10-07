package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/** What a [Banner] is about: the icon and the border colour say it, never the colour alone (ux.md §8). */
enum class BannerKind {
    /** Information or a reminder: an "i" icon, a quiet border. */
    INFO,

    /** Something to act on or a risk: a warning icon and an error-coloured border (ux.md §5.3's warning card). */
    WARNING,
}

/**
 * A banner: an `OutlinedCard` with an icon, an optional [title], the [text] and its buttons (ux.md
 * §5.11: the list shows at most two at a time; §5.12 the entry's banners; §5.3 the warning card).
 * The buttons wrap onto a second line rather than squeeze (i18n.md §4.5 rule 1).
 *
 * @param actions the banner's buttons, usually `TextButton`s, laid out from the end.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    kind: BannerKind = BannerKind.INFO,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val warning = kind == BannerKind.WARNING
    val accent = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painter = painterResource(if (warning) R.drawable.ic_common_warning else R.drawable.ic_common_info),
                contentDescription = null,
                tint = accent,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                if (actions != null) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}
