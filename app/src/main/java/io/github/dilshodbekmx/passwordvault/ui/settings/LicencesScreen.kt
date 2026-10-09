package io.github.dilshodbekmx.passwordvault.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Open-source licences" (ux.md §5.22; build plan 7.3): every part of [LicencesCatalog] under its
 * heading, each on a card with what it does in the app, its copyright lines, its licence with the
 * licence's address, where it comes from and what was changed, and "Show licence", which shows the
 * licence's full text under the card. A word list's heading has "Show where the words come from",
 * which shows its SOURCES.md. The texts are read off the main thread ([LicencesViewModel]) and
 * shown as paragraphs that wrap to the screen ([LicenceTexts]).
 *
 * A web address opens in the phone's browser, as an account's website does (the app itself has no
 * internet permission); without a browser a short message says so. Test tags: [LicencesTags].
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun LicencesScreen(vm: LicencesViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val open by vm.open.collectAsStateWithLifecycle()
    val title = stringResource(R.string.help_licences_title)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.help_licences_no_browser)
    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(noBrowser) }
        }
    }
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { BackArrow(nav) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(LicencesTags.LIST),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.help_licences_intro, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
            for (group in LicencesCatalog.groups) {
                item(key = "heading-${group.key}") {
                    Text(
                        stringResource(group.title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                            .semantics { heading() }
                            .testTag(LicencesTags.heading(group.key)),
                    )
                }
                group.intro?.let { intro ->
                    item(key = "intro-${group.key}") {
                        Text(
                            stringResource(intro),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                }
                for (part in group.parts) {
                    val key = LicencesViewModel.licenceKey(part)
                    item(key = "part-${part.key}") {
                        PartCard(part, shown = key in open, onLicence = { vm.toggleLicence(part) }, openLink = openLink)
                    }
                    shownText(key, open[key], open.containsKey(key), LicencesTags.licenceText(part.key))
                }
                group.details?.let {
                    val key = LicencesViewModel.detailsKey(group)
                    item(key = "details-${group.key}") {
                        TextButton(
                            onClick = { vm.toggleDetails(group) },
                            modifier = Modifier.padding(horizontal = 8.dp).testTag(LicencesTags.details(group.key)),
                        ) {
                            Text(stringResource(if (key in open) R.string.help_licences_hide_details else R.string.help_licences_show_details))
                        }
                    }
                    shownText(key, open[key], open.containsKey(key), LicencesTags.detailsText(group.key))
                }
            }
        }
    }
}

/** A part's card: its name and version, what it does, its copyright lines, its licence and addresses, what was changed. */
@Composable
private fun PartCard(part: LicencedPart, shown: Boolean, onLicence: () -> Unit, openLink: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(LicencesTags.part(part.key))) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (part.text == null) 12.dp else 4.dp)) {
            val name = when (val partName = part.name) {
                is PartName.Fixed -> partName.text
                is PartName.Res -> stringResource(partName.id)
            }
            Text(
                if (part.version == null) name else "$name ${part.version}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(part.use), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            for (holder in part.holders) {
                Text(holder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            part.licence?.let { licence ->
                Text(
                    stringResource(R.string.help_licences_licence, licence.title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for (url in licence.urls) Link(url, openLink)
            }
            part.source?.let { Link(it, openLink) }
            part.change?.let { change ->
                Text(stringResource(change), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (part.text != null) {
                TextButton(onClick = onLicence, modifier = Modifier.testTag(LicencesTags.licence(part.key))) {
                    Text(stringResource(if (shown) R.string.help_licences_hide_licence else R.string.help_licences_show_licence))
                }
            }
        }
    }
}

/** A web address as a link, which TalkBack reads as one; a tap opens it with [openLink]. */
@Composable
private fun Link(url: String, openLink: (String) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val text = remember(url, color) {
        buildAnnotatedString {
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                ) { openLink(url) },
            ) { append(url) }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

/**
 * The paragraphs of a shown text under its card or button: "Loading…" while it is read ([text] null
 * while [isOpen]), and a line that says it cannot be shown when it could not be read (an empty list).
 * The first paragraph carries [tag].
 */
private fun LazyListScope.shownText(key: String, text: List<TextBlock>?, isOpen: Boolean, tag: String) {
    if (!isOpen) return
    when {
        text == null -> item(key = "$key-loading") { Paragraph(TextBlock(""), tag, loading = true) }
        text.isEmpty() -> item(key = "$key-none") { Paragraph(TextBlock(""), tag, unreadable = true) }
        else -> itemsIndexed(text, key = { index, _ -> "$key-$index" }) { index, block -> Paragraph(block, if (index == 0) tag else null) }
    }
}

@Composable
private fun Paragraph(block: TextBlock, tag: String?, loading: Boolean = false, unreadable: Boolean = false) {
    val shown = when {
        loading -> stringResource(R.string.help_licences_loading)
        unreadable -> stringResource(R.string.help_licences_unreadable)
        else -> block.text
    }
    Text(
        shown,
        style = if (block.heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
        color = if (block.heading) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = if (block.heading) 12.dp else 4.dp, bottom = 4.dp)
            .then(if (block.heading) Modifier.semantics { heading() } else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/** Where a text of the licences page is read from. */
sealed interface TextSource {
    /** A licence text in `res/raw`. */
    data class Raw(@RawRes val id: Int) : TextSource

    /** A file of the app's Java resources: `:core`'s `wordlists/SOURCES.md` and `strength/SOURCES.md`. */
    data class Classpath(val path: String) : TextSource
}

/**
 * Reads the texts of the licences page from the APK. Licence texts are not translated, so the
 * application's [resources] serve; the call blocks, so it runs on the IO dispatcher.
 */
class LicenceTextReader(private val resources: Resources) {
    fun read(source: TextSource): String = when (source) {
        is TextSource.Raw -> resources.openRawResource(source.id).bufferedReader(Charsets.UTF_8).use { it.readText() }
        is TextSource.Classpath -> {
            val stream = LicenceTextReader::class.java.classLoader?.getResourceAsStream(source.path)
                ?: throw IllegalStateException("not in the APK: ${source.path}")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

/**
 * The texts shown open on the licences page, kept across a rotation: a part's licence text under
 * its key ([licenceKey]) or a word list's description ([detailsKey]). [open] maps each shown key to
 * its paragraphs, null while they are read on [io], and to an empty list when the text could not be
 * read. Nothing here is secret.
 */
class LicencesViewModel(private val read: (TextSource) -> String, private val io: CoroutineDispatcher) : ViewModel() {
    private val shown = MutableStateFlow<Map<String, List<TextBlock>?>>(emptyMap())
    val open: StateFlow<Map<String, List<TextBlock>?>> = shown.asStateFlow()

    /** "Show licence" and "Hide licence" of [part]. */
    fun toggleLicence(part: LicencedPart) {
        val text = part.text ?: return
        toggle(licenceKey(part), TextSource.Raw(text), markdown = false)
    }

    /** "Show where the words come from" and its "Hide" of [group]. */
    fun toggleDetails(group: LicenceGroup) {
        val path = group.details ?: return
        toggle(detailsKey(group), TextSource.Classpath(path), markdown = true)
    }

    private fun toggle(key: String, source: TextSource, markdown: Boolean) {
        if (shown.value.containsKey(key)) {
            shown.value = shown.value - key
            return
        }
        shown.value = shown.value + (key to null)
        viewModelScope.launch {
            val paragraphs = try {
                withContext(io) { LicenceTexts.paragraphs(read(source), markdown) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (shown.value.containsKey(key)) shown.value = shown.value + (key to paragraphs)
        }
    }

    companion object {
        fun licenceKey(part: LicencedPart): String = "licence.${part.key}"

        fun detailsKey(group: LicenceGroup): String = "details.${group.key}"
    }
}

/** The test tags of the licences page, for tests and the emulator scripts, whatever the language. */
object LicencesTags {
    const val LIST = "pv.licences.list"

    /** The heading of the group [key]: passphrase, strength, libraries, icons. */
    fun heading(key: String): String = "pv.licences.heading.$key"

    /** The card of the part [key] ([LicencedPart.key]). */
    fun part(key: String): String = "pv.licences.$key"

    /** Its "Show licence" button. */
    fun licence(key: String): String = "pv.licences.$key.licence"

    /** The first paragraph of its shown licence text. */
    fun licenceText(key: String): String = "pv.licences.$key.text"

    /** The "Show where the words come from" button of the group [key]. */
    fun details(key: String): String = "pv.licences.details.$key"

    /** The first paragraph of that description. */
    fun detailsText(key: String): String = "pv.licences.details.$key.text"
}
