package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.ui.common.LongLabelShape
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar

/** The Security tab's route (ux.md §5.16): its ViewModel over the app's check, and the screen. */
@Composable
fun SecurityRoute(graph: AppGraph, nav: Navigator) {
    val viewModel = viewModel { SecurityViewModel.from(graph) }
    SecurityScreen(viewModel, nav)
}

/**
 * The Security tab (ux.md §5.16): "Why this matters", open on the first visit and folded afterwards;
 * "Checking 120 of 480…" while the first check after unlock runs; then the cards in their order,
 * each with its count and one button, or "all clear", or "no accounts yet".
 *
 * While it is shown (resumed), the results count as seen ([SecurityViewModel.whileShown]): the tab's
 * dot goes. The colours come from the theme, so the tab is right in every style, light and dark.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun SecurityScreen(viewModel: SecurityViewModel, nav: Navigator) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.whileShown() }
    }
    val title = stringResource(R.string.security_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = { VaultTopBar(title = title) },
    ) { padding ->
        val formatters = rememberFormatters()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(SecurityTags.LIST),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val shown = state ?: return@LazyColumn
            item(key = "why") { WhyCard(open = shown.whyOpen, onToggle = viewModel::toggleWhy) }
            val checking = shown.checking
            val results = shown.results
            if (checking != null || results == null) item(key = "checking") { CheckingLine(checking, formatters) }
            when (results) {
                null -> Unit
                SecurityResults.NoAccounts -> item(key = "none") {
                    Text(
                        text = stringResource(R.string.security_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(SecurityTags.NO_ACCOUNTS),
                    )
                }
                is SecurityResults.Cards -> {
                    if (results.allClear) item(key = "clear") { AllClear() }
                    items(results.cards, key = { it.tagName }) { card -> CardOf(card, nav, formatters) }
                }
            }
        }
    }
}

/** "Why this matters" (ux.md §5.16): its heading with a button that unfolds or folds the text. */
@Composable
private fun WhyCard(open: Boolean, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(SecurityTags.WHY)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = if (open) 16.dp else 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_common_info), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.security_why_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onToggle, modifier = Modifier.testTag(SecurityTags.WHY_TOGGLE)) {
                    Icon(
                        painter = painterResource(if (open) R.drawable.ic_entry_collapse else R.drawable.ic_entry_expand),
                        contentDescription = stringResource(if (open) R.string.security_why_hide else R.string.security_why_show),
                    )
                }
            }
            if (open) {
                Text(
                    text = stringResource(R.string.security_why_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 12.dp).testTag(SecurityTags.WHY_TEXT),
                )
            }
        }
    }
}

/**
 * "Checking 120 of 480…" under a bar that fills as the accounts are scored; before the first account
 * is scored ([checking] null) the bar runs without an end and says "Checking your passwords…".
 */
@Composable
private fun CheckingLine(checking: CheckProgress?, formatters: Formatters) {
    Column(Modifier.fillMaxWidth().testTag(SecurityTags.CHECKING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (checking == null || checking.total == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.security_checking_start), style = MaterialTheme.typography.bodyMedium)
        } else {
            LinearProgressIndicator(progress = { checking.checked.toFloat() / checking.total }, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.security_checking, formatters.number(checking.checked.toLong()), formatters.number(checking.total.toLong())),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** "All your passwords are different and hard to guess. Well done." */
@Composable
private fun AllClear() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(SecurityTags.ALL_CLEAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_common_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.security_all_clear), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** The card's name in its test tag, also its key in the list. */
private val SecurityCard.tagName: String
    get() = when (this) {
        is SecurityCard.MasterPassword -> SecurityTags.MASTER
        is SecurityCard.Reused -> SecurityTags.REUSED
        is SecurityCard.Weak -> SecurityTags.WEAK
        is SecurityCard.Pending -> SecurityTags.PENDING
        is SecurityCard.LeftOut -> SecurityTags.LEFT_OUT
    }

/** One card of ux.md §5.16, with its text and its button. */
@Composable
private fun CardOf(card: SecurityCard, nav: Navigator, formatters: Formatters) {
    when (card) {
        is SecurityCard.MasterPassword -> CheckCard(SecurityTags.MASTER, stringResource(R.string.security_master_title), warning = true) {
            CardText(
                if (card.count == 1) {
                    stringResource(R.string.security_master_one, card.names.first())
                } else {
                    pluralStringResource(R.plurals.security_master_many, card.count, formatters.number(card.count.toLong()), names(card.names, card.count))
                },
            )
            CardButtons {
                Button(onClick = { nav.go(Route.ChangeMasterPassword) }, modifier = Modifier.testTag(SecurityTags.cardButton(SecurityTags.MASTER)), shape = LongLabelShape) {
                    Text(stringResource(R.string.security_master_choose))
                }
            }
        }
        is SecurityCard.Reused -> CheckCard(SecurityTags.REUSED, stringResource(R.string.security_reused_title)) {
            val biggest = card.biggest
            CardText(pluralStringResource(R.plurals.security_reused_count, biggest.accounts, formatters.number(biggest.accounts.toLong())))
            if (biggest.changed > 0) ReusedProgress(biggest.changed, biggest.accounts, formatters, Modifier)
            CardButtons {
                OpenButton(
                    label = stringResource(R.string.security_show_accounts),
                    tag = SecurityTags.cardButton(SecurityTags.REUSED),
                ) { nav.go(Route.Group(biggest.id)) }
            }
            if (card.others.isNotEmpty()) {
                HorizontalDivider()
                for (other in card.others) OtherGroup(other, formatters) { nav.go(Route.Group(other.id)) }
            }
        }
        is SecurityCard.Weak -> CheckCard(SecurityTags.WEAK, stringResource(R.string.security_weak_title)) {
            CardText(pluralStringResource(R.plurals.security_weak_count, card.count, formatters.number(card.count.toLong())))
            CardButtons {
                OpenButton(
                    label = stringResource(R.string.security_show_accounts),
                    tag = SecurityTags.cardButton(SecurityTags.WEAK),
                ) { nav.go(Route.Group(GroupKind.WEAK_ID)) }
            }
        }
        is SecurityCard.Pending -> CheckCard(SecurityTags.PENDING, stringResource(R.string.security_pending_title)) {
            CardText(pluralStringResource(R.plurals.security_pending_count, card.count, formatters.number(card.count.toLong()), names(card.names, card.count)))
            CardButtons {
                val only = card.onlyEntryId
                OpenButton(
                    label = stringResource(if (only != null) R.string.security_show_account else R.string.security_show_accounts),
                    tag = SecurityTags.cardButton(SecurityTags.PENDING),
                ) { nav.go(if (only != null) Route.Entry(only) else Route.Group(GroupKind.PENDING_ID)) }
            }
        }
        is SecurityCard.LeftOut -> CheckCard(SecurityTags.LEFT_OUT, stringResource(R.string.security_left_out_title)) {
            CardText(pluralStringResource(R.plurals.security_left_out_count, card.count, formatters.number(card.count.toLong())))
            CardButtons {
                OpenButton(
                    label = stringResource(R.string.security_show_accounts),
                    tag = SecurityTags.cardButton(SecurityTags.LEFT_OUT),
                ) { nav.go(Route.Group(GroupKind.LEFT_OUT_ID)) }
            }
        }
    }
}

/**
 * A card of the check (`ElevatedCard`, ux.md §10): its heading and [content]. The card about the
 * master password is a [warning]: a warning icon in the error colour, so that colour is never the only
 * sign (ux.md §8).
 */
@Composable
private fun CheckCard(name: String, title: String, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().testTag(SecurityTags.card(name))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (warning) Icon(painterResource(R.drawable.ic_common_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            }
            content()
        }
    }
}

@Composable
private fun CardText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** A card's buttons, at its end; they wrap onto a second line rather than squeeze (i18n.md §4.5 rule 1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardButtons(buttons: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { buttons() }
}

/**
 * A card's `TextButton` that opens its list. Its label is all TalkBack reads after the card's heading
 * and text, which say what it opens.
 */
@Composable
private fun OpenButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
}

/** Another group of reused passwords on card 2: "3 accounts share another password", a button that opens it. */
@Composable
private fun OtherGroup(group: GroupLine, formatters: Formatters, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(SecurityTags.otherGroup(group.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.security_reused_other, group.accounts, formatters.number(group.accounts.toLong())),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(painterResource(R.drawable.ic_list_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "3 changed, 14 to go" (ux.md §5.16) under a bar that shows the share changed: [changed] accounts left
 * the group, [toGo] still share its password.
 */
@Composable
internal fun ReusedProgress(changed: Int, toGo: Int, formatters: Formatters, modifier: Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress = { changed.toFloat() / (changed + toGo) }, modifier = Modifier.fillMaxWidth(), color = tint)
        Text(
            text = pluralStringResource(R.plurals.security_reused_progress, toGo, formatters.number(changed.toLong()), formatters.number(toGo.toLong())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The account names in a card's text: "Gmail", "Gmail, Facebook", or "Gmail, Facebook…" when [count] has more. */
@Composable
private fun names(names: List<String>, count: Int): String = when {
    names.size < 2 -> names.firstOrNull().orEmpty()
    count > names.size -> stringResource(R.string.security_names_more, names[0], names[1])
    else -> stringResource(R.string.security_names_two, names[0], names[1])
}
