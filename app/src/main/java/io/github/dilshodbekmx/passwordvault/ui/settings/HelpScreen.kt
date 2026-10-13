package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * A help page (ux.md §5.22; [HelpPages] says what it holds): "Help" in the bar with the back arrow,
 * then the page's heading and its blocks in one scrolling column, so that a long title wraps
 * instead of being cut in the bar (i18n.md §4.5 rule 2). The Version page has its own head instead
 * (the logo, the app's name and the version) and "Version" in the bar.
 *
 * Plain text, nothing to type and nothing secret. The page's column carries the test tag
 * `pv.help.<PAGE>` ([HelpTags.page]); a button to another page carries the Settings row's tag of that
 * page (`pv.open.Help.<PAGE>`, `pv.open.Licences`), the shell walk's form.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun HelpScreen(page: HelpPage, blocks: List<HelpBlock>, nav: Navigator, modifier: Modifier = Modifier) {
    val title = stringResource(HelpPages.title(page))
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(HelpPages.barTitle(page)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = { BackArrow(nav) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(HelpTags.page(page)),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        ) {
            if (page != HelpPage.VERSION) {
                item(key = "title") {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() }.testTag(HelpTags.TITLE),
                    )
                }
            }
            itemsIndexed(blocks, key = { index, _ -> index }) { _, block -> HelpBlockItem(block, nav) }
        }
    }
}

@Composable
private fun HelpBlockItem(block: HelpBlock, nav: Navigator) {
    when (block) {
        is HelpBlock.Heading -> Text(
            block.text.resolve(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
        is HelpBlock.Paragraph -> Text(
            block.text.resolve(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        is HelpBlock.AppHead -> AppHead(block)
        is HelpBlock.Open -> TextButton(
            onClick = { nav.go(block.route) },
            modifier = Modifier.padding(top = 12.dp).testTag(HelpTags.open(block.route)),
        ) {
            Text(block.text.resolve())
        }
    }
}

/** The Version page's head: the logo on the primary colour, the app's name and the version. */
@Composable
private fun AppHead(block: HelpBlock.AppHead) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(LOGO_BOX).clip(RoundedCornerShape(LOGO_CORNER)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(LOGO),
            )
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            block.version.resolve(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(HelpTags.VERSION),
        )
    }
}

/** The test tags of the help pages, for tests and the emulator scripts, whatever the language. */
object HelpTags {
    /** The heading at the top of a page (not on the Version page). */
    const val TITLE = "pv.help.title"

    /** The version line of the Version page. */
    const val VERSION = "pv.help.version"

    /** The column of [page]. */
    fun page(page: HelpPage): String = "pv.help.${page.name}"

    /** A button that opens [route]: the Settings row's tag of the same route. */
    fun open(route: Route): String = if (route is Route.Help) SettingsTags.help(route.page) else SettingsTags.open(route)
}

private val LOGO_BOX = 72.dp
private val LOGO = 44.dp
private val LOGO_CORNER = 20.dp
