package io.github.dilshodbekmx.passwordvault.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * A button of a [RoutePlaceholder]: its test tag (exposed as its resource id, for the emulator
 * script `tools/emu/shell/walk.sh`), its label and the label's one argument.
 */
class PlaceholderAction(val tag: String, @StringRes val label: Int, val arg: String?, val onClick: () -> Unit)

/** "Open <route>": a button that opens [target] with [nav], tagged `pv.open.<name>`. */
fun openAction(nav: Navigator, target: Route): PlaceholderAction =
    PlaceholderAction("pv.open.${target.name}", R.string.shell_placeholder_open, target.name) { nav.go(target) }

/** A button that does something other than opening a route, tagged `pv.action.<tag>`. */
fun placeholderAction(tag: String, @StringRes label: Int, onClick: () -> Unit): PlaceholderAction =
    PlaceholderAction("pv.action.$tag", label, null, onClick)

/** The entry and group ids the placeholders open: not real entries. */
const val PLACEHOLDER_ENTRY_ID = "00000000-0000-4000-8000-00000000e001"
const val PLACEHOLDER_GROUP_ID = "placeholder-group"

/**
 * The screen of a route whose package has not been built yet (build plan 2.5): the route's key,
 * a line that says so, an optional [detail] (such as the lock's state), [actions] that move to the
 * next routes, and "Back". Each package replaces its route file's placeholders with its screens;
 * the texts are the shell's (`strings_shell.xml`, not translated: no placeholder ships in a release
 * that is handed to the user).
 */
@Composable
fun RoutePlaceholder(route: Route, nav: Navigator, actions: List<PlaceholderAction> = emptyList(), detail: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.shell_placeholder_title, route.toString()),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("pv.title.${route.name}"),
        )
        Text(text = stringResource(R.string.shell_placeholder_body), style = MaterialTheme.typography.bodyMedium)
        if (detail != null) Text(text = stringResource(R.string.shell_placeholder_state, detail), style = MaterialTheme.typography.bodySmall)
        for (action in actions) {
            OutlinedButton(onClick = action.onClick, modifier = Modifier.fillMaxWidth().testTag(action.tag)) {
                Text(if (action.arg == null) stringResource(action.label) else stringResource(action.label, action.arg))
            }
        }
        TextButton(onClick = { nav.back() }, modifier = Modifier.testTag("pv.back")) {
            Text(stringResource(R.string.shell_placeholder_back))
        }
    }
}
