package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar

/** The import screens' top bar: the title and a back arrow that does what Back does there. */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
internal fun ImportTopBar(title: String, onBack: () -> Unit) {
    VaultTopBar(
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag(ImportTags.BACK)) {
                Icon(painterResource(R.drawable.ic_entry_back), contentDescription = stringResource(R.string.import_back))
            }
        },
    )
}

/**
 * The content of an import screen below its top bar: one scrolling column, at most 640 dp wide
 * (ux.md §3, "Layout"), above the keyboard.
 */
@Composable
internal fun ImportPage(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A spinner with a line of text under it, both announced politely: "Reading the file…". */
@Composable
internal fun ImportProgress(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = text })
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The name of an encoding, the same in every language (i18n.md §9, "Do not translate"). */
internal fun importEncodingName(encoding: TextDecoding.Encoding): String = when (encoding) {
    TextDecoding.Encoding.UTF_8 -> "UTF-8"
    TextDecoding.Encoding.UTF_16LE, TextDecoding.Encoding.UTF_16BE -> "UTF-16"
    TextDecoding.Encoding.WINDOWS_1251 -> "Windows-1251"
}
