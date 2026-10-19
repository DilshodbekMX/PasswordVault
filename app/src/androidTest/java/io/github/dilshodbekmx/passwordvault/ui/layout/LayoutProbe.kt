package io.github.dilshodbekmx.passwordvault.ui.layout

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.common.MayTruncate
import io.github.dilshodbekmx.passwordvault.ui.common.UserText
import java.util.Locale

/** The four checks of build plan 6.2's [script] line, made on each screen. */
enum class LayoutCheck(val title: String) {
    /** No control's text (a button's, a row's, a tab's) is cut, clipped or pushed off the window. */
    BUTTONS("no clipped button"),

    /** No other text is cut or clipped: a warning, a line of the screen, a title (i18n.md §4.5 rule 3). */
    TEXTS("no truncated or clipped text"),

    /** No text or label stayed plain English in the pseudo-locale: each comes from the resources. */
    ENGLISH("no text left in plain English"),

    /** Every control a finger can press is at least 48 × 48 dp (ux.md §8 "Touch targets"). */
    TARGETS("no control under 48 dp"),
}

/**
 * The layout checks of build plan 6.2, made in the app's own process on what Compose has laid out:
 * every root of the screen (the activity's window, a dialog, a sheet, a menu), the whole of a column
 * that scrolls (Compose lays it all out), and of a lazy list what is composed now.
 *
 * - A text is *cut* when its own layout overflowed (`TextLayoutResult.hasVisualOverflow`) or ended a
 *   line with an ellipsis, *clipped* when a parent that does not scroll cut part of it off (a bar or a
 *   chip of a fixed height, a dialog too short for its text), and *past the edge* when it reaches out
 *   of its window's left or right side. Only a text marked [MayTruncate] (its full text is reachable
 *   elsewhere) may be cut, and none may be clipped or past the edge. A text inside a control (a node
 *   with a click action that merges its children) counts for [LayoutCheck.BUTTONS], any other for
 *   [LayoutCheck.TEXTS]. What a field holds is not checked: it scrolls by design.
 * - A control is a node with a click or a long-click action. Its touch area (`touchBoundsInRoot`, which
 *   includes the minimum interactive size Material gives) must be 48 dp each way, checked while the
 *   control is wholly on the screen (one half scrolled away is seen again after the scroll).
 * - A text or label is *plain English* in a pseudo-locale when it has no character outside ASCII:
 *   `en-XA` accents every letter of the app's strings and `ar-XB` wraps them in direction marks. Left
 *   out: the walk's own data ([LayoutProbe.data], such as an account's name), secrets on show
 *   (`isSensitiveData`), what a field holds, the app's strings that are the same in every language
 *   (its name, "CSV"), the libraries' own labels (Material's "Close sheet", which 6.3 overrides for
 *   Uzbek Cyrillic), and dates, times and numbers, which the phone's ICU writes for the language.
 */
class LayoutProbe(private val compose: SemanticsNodeInteractionsProvider, private val context: Context) {
    /** The walk's own texts: names, logins, websites and notes it typed, words the screen chose. */
    val data = mutableSetOf<String>()

    /** Whether the screen is drawn in a pseudo-locale, where the English check means something. */
    var checkEnglish = true

    private val density = context.resources.displayMetrics.density
    private val minTarget = 48 * density - 1f

    /** The app's strings that are the same in every language, and the libraries' labels. */
    private val sameEverywhere: Set<String> by lazy { untranslatable(context) + libraryLabels(context) }

    /** What [look] found, by check. */
    class Findings(val byCheck: Map<LayoutCheck, List<String>>) {
        fun of(check: LayoutCheck): List<String> = byCheck[check].orEmpty()

        operator fun plus(other: Findings): Findings =
            Findings(LayoutCheck.entries.associateWith { (of(it) + other.of(it)).distinct() })

        val clean: Boolean get() = byCheck.values.all { it.isEmpty() }
    }

    /** The checks on what is laid out now. */
    fun look(): Findings {
        val unmerged = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        val merged = compose.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes(atLeastOneRootRequired = false)
        val found = LayoutCheck.entries.associateWith { mutableListOf<String>() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (root in merged) targets(root, found)
            for (root in unmerged) {
                if (checkEnglish) english(root, found)
                // The controls of the same window, which may lie over a text that is not theirs.
                val controls = mutableListOf<SemanticsNode>()
                merged.firstOrNull { it.id == root.id }?.let { collectControls(it, controls) }
                texts(root, root.boundsInRoot, controls, found)
            }
        }
        return Findings(found.mapValues { (_, list) -> list.distinct() })
    }

    // ---- cut, clipped, split, covered and past the edge ------------------------------------------------

    private fun texts(node: SemanticsNode, window: Rect, controls: List<SemanticsNode>, found: Map<LayoutCheck, MutableList<String>>) {
        // A lazy list composes and measures the items about to come into view before it places them:
        // their nodes are not on the screen, and their positions mean nothing.
        if (!node.layoutInfo.isPlaced) return
        if (node.config.contains(SemanticsActions.GetTextLayoutResult) && !node.config.contains(SemanticsProperties.EditableText)) {
            val problem = textProblem(node, window) ?: coveredBy(node, controls)
            if (problem != null) {
                val check = if (insideControl(node)) LayoutCheck.BUTTONS else LayoutCheck.TEXTS
                found.getValue(check) += "$problem: ${describe(node)}"
            }
        }
        for (child in node.children) texts(child, window, controls, found)
    }

    private fun textProblem(node: SemanticsNode, window: Rect): String? {
        if (node.size.width == 0 || node.size.height == 0) return null
        val full = Rect(node.positionInRoot, node.size.toSize())
        val horizontal = scroller(node, SemanticsProperties.HorizontalScrollAxisRange)
        if (horizontal == null && (full.left < window.left - 1 || full.right > window.right + 1)) {
            return "past the edge of its window (${dp(full.left)}..${dp(full.right)} of ${dp(window.width)} dp)"
        }
        val vertical = scroller(node, SemanticsProperties.VerticalScrollAxisRange)
        if (vertical == null && (full.bottom > window.bottom + 1 || full.top < window.top - 1)) {
            return "past the ${if (full.bottom > window.bottom + 1) "bottom" else "top"} of a window that does not scroll " +
                "(${dp(full.top)}..${dp(full.bottom)} of ${dp(window.height)} dp)"
        }
        val layout = layoutOf(node)
        val mayCut = markedMayTruncate(node)
        if (layout != null) spillsOutOf(node, layout)?.let { return it }
        if (layout != null && !mayCut) {
            val ellipsis = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            if (ellipsis) return "cut with an ellipsis"
            // The result that semantics gives is laid out again at the widest the text was allowed, so
            // its paragraph can be wider than the text's own box (a centred label): what counts is the
            // width of each line and the height of the lines, against the box the text was given.
            val paragraph = layout.multiParagraph
            val taller = paragraph.height - layout.size.height
            if (paragraph.didExceedMaxLines || taller > 1f) return "cut (overflows its height by ${dp(taller)} dp, ${paragraph.lineCount} lines)"
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            if (widest > layout.size.width + 1f) return "cut (overflows its width by ${dp(widest - layout.size.width)} dp)"
        }
        val shown = node.boundsInRoot
        if (!same(shown, full) && layout != null) {
            // Every list or column that scrolls around the text: one inside another is clipped by both.
            val scrollers = generateSequence(node.parent) { it.parent }.filter {
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange) || it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
            }.toList()
            // Partly scrolled away: seen whole once the walk scrolls it in.
            val scrolled = scrollers.any { !inside(full, it.boundsInRoot) }
            // What counts is where the letters are, not the text's box, which can be wider.
            val cut = inkOf(node, layout).filterNot { inside(it, shown, density) }
            if (!scrolled && cut.isNotEmpty() && !(mayCut && cut.all { it.top >= shown.top - 1 && it.bottom <= shown.bottom + 1 })) {
                val line = cut.first()
                return "clipped by its parent (letters at ${dp(line.left)}..${dp(line.right)} × ${dp(line.top)}..${dp(line.bottom)} dp, " +
                    "the parent shows ${dp(shown.left)}..${dp(shown.right)} × ${dp(shown.top)}..${dp(shown.bottom)} dp)"
            }
        }
        if (layout != null && !secret(node)) splitWord(layout)?.let { return "a word split across two lines (\"$it\"; lines ${lines(layout)})" }
        return null
    }

    /**
     * A word of an ordinary length that a line break cut in two: its box is too narrow for it. A word
     * here is a whole token between ordinary spaces of at most [LONGEST_WORD] characters ("Settings",
     * "[Šéţţîñĝš"). A longer token that cannot break (a file name, an address, a password of words, a
     * date whose parts no-break spaces hold together) may break anywhere, and once one has, the text's
     * other breaks follow from it and are not looked at.
     */
    private fun splitWord(layout: TextLayoutResult): String? {
        val text = layout.layoutInput.text.text
        fun space(c: Char) = c == ' ' || c == '\n' || c == '\t'
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            if (end <= 0 || end >= text.length) continue
            if (space(text[end - 1]) || space(text[end])) continue
            var from = end - 1
            while (from > 0 && !space(text[from - 1])) from--
            var to = end
            while (to < text.length && !space(text[to])) to++
            if (to - from > LONGEST_WORD) return null
            if (text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) return text.substring(from, to)
        }
        return null
    }

    /**
     * A control of the same window that is not the text's own and lies over the text's letters where no
     * scroll can move the text out from under it: the text has no list or column that scrolls, or that
     * list is scrolled to its end and the control lies below the text (or to its start, and above it).
     */
    private fun coveredBy(node: SemanticsNode, controls: List<SemanticsNode>): String? {
        val shown = node.boundsInRoot
        if (shown.width < 1f || shown.height < 1f) return null
        val layout = layoutOf(node) ?: return null
        val inked = inkOf(node, layout).mapNotNull { line -> line.intersect(shown).takeIf { it.width > 0f && it.height > 0f } }
        if (inked.isEmpty()) return null
        val own = generateSequence(node) { it.parent }.map { it.id }.toSet()
        val vertical = scroller(node, SemanticsProperties.VerticalScrollAxisRange)
        val range = vertical?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        for (control in controls) {
            if (control.id in own) continue
            if (generateSequence(control) { it.parent }.any { it.id == node.id }) continue
            val area = control.boundsInRoot
            val over = inked.map { it.intersect(area) }.filter { it.width >= MIN_COVER * density && it.height >= MIN_COVER * density }
            if (over.isEmpty()) continue
            // At its end or at its start: a lazy list's range is where it is and one more while it can
            // move on (not pixels), so only "no further" counts.
            val stuck = when {
                range == null -> true
                range.maxValue() - range.value() < 0.5f && area.center.y > shown.center.y -> true
                range.value() < 0.5f && area.center.y < shown.center.y -> true
                else -> false
            }
            if (stuck) return "covered by ${describe(control)} (${dp(over.maxOf { it.width })} × ${dp(over.maxOf { it.height })} dp)"
        }
        return null
    }

    /**
     * Where each line's letters are, in the root's coordinates. The result that semantics gives is laid
     * out at the widest the text was allowed, so a centred or end-aligned line is moved to where it is
     * in the text's own box.
     */
    private fun inkOf(node: SemanticsNode, layout: TextLayoutResult): List<Rect> {
        val origin = node.positionInRoot
        val laidOut = layout.multiParagraph.width
        val boxWidth = layout.size.width.toFloat()
        return (0 until layout.lineCount).map { line ->
            val left = layout.getLineLeft(line)
            val right = layout.getLineRight(line)
            val width = right - left
            val slack = laidOut - width
            val share = if (slack > 1f) (left / slack).coerceIn(0f, 1f) else 0f
            val x = share * (boxWidth - width).coerceAtLeast(0f)
            Rect(origin.x + x, origin.y + layout.getLineTop(line), origin.x + x + width, origin.y + layout.getLineBottom(line))
        }
    }

    /**
     * A text whose letters reach out of a parent node that does not scroll (a bar or a field too
     * small for it, which does not clip it): they are then drawn over what lies next to that parent,
     * as a top bar's title of three lines in a bar of one line's height was drawn over the screen's
     * first lines. The parents up to the first list or column that scrolls are looked at.
     */
    private fun spillsOutOf(node: SemanticsNode, layout: TextLayoutResult): String? {
        val ink = inkOf(node, layout)
        var parent = node.parent
        while (parent != null && !parent.isRoot) {
            if (parent.config.contains(SemanticsProperties.VerticalScrollAxisRange) || parent.config.contains(SemanticsProperties.HorizontalScrollAxisRange)) return null
            if (parent.size.width > 0 && parent.size.height > 0) {
                val box = Rect(parent.positionInRoot, parent.size.toSize())
                val out = ink.firstOrNull { !inside(it, box.inflate(SPILL_ALLOWED * density)) }
                if (out != null) {
                    return "spills out of ${describe(parent)} (letters at ${dp(out.top)}..${dp(out.bottom)} dp, the parent at ${dp(box.top)}..${dp(box.bottom)} dp)"
                }
            }
            parent = parent.parent
        }
        return null
    }

    /** The controls that float over the content ([FLOATING]): only these can cover a text that is not theirs. */
    private fun collectControls(node: SemanticsNode, into: MutableList<SemanticsNode>) {
        if (node.config.getOrNull(SemanticsProperties.TestTag) in FLOATING) into += node
        for (child in node.children) collectControls(child, into)
    }

    private fun lines(layout: TextLayoutResult): String {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount).joinToString(" / ") { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        return if (action(results)) results.firstOrNull() else null
    }

    /** The nearest ancestor (or the node) that scrolls along [axis]. */
    private fun scroller(node: SemanticsNode, axis: androidx.compose.ui.semantics.SemanticsPropertyKey<*>): SemanticsNode? {
        var at: SemanticsNode? = node
        while (at != null) {
            if (at.config.contains(axis)) return at
            at = at.parent
        }
        return null
    }

    /** Whether [node] shows a secret (`isSensitiveData` on it or above it): laid out as it comes, it may break anywhere. */
    private fun secret(node: SemanticsNode): Boolean = generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.IsSensitiveData) == true }

    private fun markedUserText(node: SemanticsNode): Boolean = generateSequence(node) { it.parent }.any { it.config.getOrNull(UserText) == true }

    private fun markedMayTruncate(node: SemanticsNode): Boolean {
        var at: SemanticsNode? = node
        while (at != null) {
            if (at.config.getOrNull(MayTruncate) == true) return true
            at = at.parent
        }
        return false
    }

    /** Whether [node] is a control's text: an ancestor (or the node) has a click action and merges its children. */
    private fun insideControl(node: SemanticsNode): Boolean {
        var at: SemanticsNode? = node
        while (at != null) {
            if (at.config.contains(SemanticsActions.OnClick) && at.config.isMergingSemanticsOfDescendants) return true
            at = at.parent
        }
        return false
    }

    // ---- touch targets --------------------------------------------------------------------------------

    private fun targets(node: SemanticsNode, found: Map<LayoutCheck, MutableList<String>>) {
        if (!node.layoutInfo.isPlaced) return
        val control = node.config.contains(SemanticsActions.OnClick) || node.config.contains(SemanticsActions.OnLongClick)
        if (control && node.size.width > 0 && node.size.height > 0) {
            val full = Rect(node.positionInRoot, node.size.toSize())
            val window = root(node).boundsInRoot
            if (scroller(node, SemanticsProperties.VerticalScrollAxisRange) == null && full.bottom > window.bottom + 1) {
                found.getValue(LayoutCheck.BUTTONS) += "past the bottom of a window that does not scroll " +
                    "(${dp(full.top)}..${dp(full.bottom)} of ${dp(window.height)} dp): ${describe(node)}"
            }
            if (same(node.boundsInRoot, full)) {
                val touch = node.touchBoundsInRoot
                if (touch.width < minTarget || touch.height < minTarget) {
                    found.getValue(LayoutCheck.TARGETS) += "${dp(touch.width)} × ${dp(touch.height)} dp (touch ${dp(touch.left)},${dp(touch.top)}; " +
                        "node ${dp(full.left)},${dp(full.top)} ${dp(full.width)} × ${dp(full.height)} dp): ${describe(node)}"
                }
            }
        }
        for (child in node.children) targets(child, found)
    }

    // ---- plain English ---------------------------------------------------------------------------------

    /**
     * On the unmerged tree, so that a row's own texts are looked at one by one: the user's own texts in
     * it ([UserText]) are passed over, and its other texts are not.
     */
    private fun english(node: SemanticsNode, found: Map<LayoutCheck, MutableList<String>>) {
        if (!node.layoutInfo.isPlaced) return
        val config = node.config
        if (config.getOrNull(SemanticsProperties.IsSensitiveData) != true && !markedUserText(node)) {
            val shown = mutableListOf<String>()
            config.getOrNull(SemanticsProperties.Text)?.forEach { shown += it.text }
            config.getOrNull(SemanticsProperties.ContentDescription)?.let { shown += it }
            config.getOrNull(SemanticsProperties.StateDescription)?.let { shown += it }
            config.getOrNull(SemanticsProperties.Error)?.let { shown += it }
            // Not the pane title: it repeats the screen's title, which is looked at, and which may be the
            // user's own (an account's name).
            for (text in shown) {
                if (plainEnglish(text)) found.getValue(LayoutCheck.ENGLISH) += "\"${text.take(80)}\" (${tagOf(node) ?: roleOf(node)})"
            }
        }
        for (child in node.children) english(child, found)
    }

    /** Whether [text] reads as English that no pseudo-locale touched (see the class's KDoc). */
    fun plainEnglish(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.code > 0x7F }) return false
        if (trimmed in sameEverywhere || trimmed in data) return false
        // Addresses are data, whatever language the screen is in: web addresses, email addresses, domains.
        var rest = trimmed.replace(ADDRESS, " ")
        for (datum in data.sortedByDescending { it.length }) if (datum.length >= 2) rest = rest.replace(datum, " ")
        for (same in sameEverywhere.filter { it.length >= 3 }.sortedByDescending { it.length }) rest = rest.replace(same, " ")
        // What is left once the data, the dates, times and numbers are taken out.
        val words = Regex("[A-Za-z]{2,}").findAll(rest).map { it.value }.filterNot { it in ICU_WORDS }.toList()
        return words.isNotEmpty()
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private fun root(node: SemanticsNode): SemanticsNode = generateSequence(node) { it.parent }.last()

    private fun same(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= 1f && kotlin.math.abs(a.top - b.top) <= 1f &&
            kotlin.math.abs(a.right - b.right) <= 1f && kotlin.math.abs(a.bottom - b.bottom) <= 1f

    private fun inside(inner: Rect, outer: Rect, slack: Float = 1f): Boolean =
        inner.left >= outer.left - slack && inner.top >= outer.top - slack && inner.right <= outer.right + slack && inner.bottom <= outer.bottom + slack

    private fun dp(px: Float): Int = kotlin.math.round(px / density).toInt()

    private fun tagOf(node: SemanticsNode): String? {
        var at: SemanticsNode? = node
        while (at != null) {
            at.config.getOrNull(SemanticsProperties.TestTag)?.let { return it }
            at = at.parent
        }
        return null
    }

    private fun roleOf(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Role)?.toString() ?: "no tag"

    /** The node for a person: its text, its label, and the nearest test tag. */
    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: ""
        val role = node.config.getOrNull(SemanticsProperties.Role)
        val tag = tagOf(node)
        return buildString {
            append('"').append(text.replace('\n', ' ').take(60)).append('"')
            if (text.isEmpty()) append(" (").append(node.config.joinToString(", ") { it.key.name }).append(')')
            if (role != null && role != Role.Image) append(' ').append(role)
            if (tag != null) append(" in ").append(tag)
        }
    }

    companion object {
        /** The longest word that must not be split across lines (i18n.md §4.5: 99 % of words are at most 16 letters). */
        private const val LONGEST_WORD = 16

        /**
         * The test tags of the controls that float over the screen's content: the list's Add button
         * (ux.md §5.11, §8: the list's bottom padding keeps it off the last row). The app has no other.
         */
        val FLOATING = setOf("pv.list.add")

        /** How far (dp) a text's letters may reach out of a parent, as a letter's accent may. */
        private const val SPILL_ALLOWED = 2

        /** How much of a text (dp, each way) a control must cover to count. */
        private const val MIN_COVER = 4

        /** A web address, an email address or a domain name. */
        private val ADDRESS = Regex("""(?i)\b(?:[a-z][a-z0-9+.-]*://\S+|[\w.+-]+@[\w-]+(?:\.[\w-]+)+|(?:[a-z0-9-]+\.)+[a-z]{2,})\b""")

        /** Words that the phone's ICU writes into dates and times in English (months, AM and PM). */
        private val ICU_WORDS = setOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec",
            "January", "February", "March", "April", "June", "July", "August", "September", "October", "November", "December",
            "AM", "PM", "am", "pm",
        )

        /** The app's strings whose text is the same in `en-XA` as in English: marked untranslatable. */
        fun untranslatable(context: Context): Set<String> {
            val english = inLocale(context, Locale.ENGLISH)
            val pseudo = inLocale(context, Locale.forLanguageTag("en-XA"))
            return R.string::class.java.fields.mapNotNull { field ->
                runCatching {
                    val id = field.getInt(null)
                    english.getString(id).takeIf { it == pseudo.getString(id) }
                }.getOrNull()
            }.toSet()
        }

        /** The labels of the libraries the app draws with, which no pseudo-locale touches. */
        fun libraryLabels(context: Context): Set<String> =
            LIBRARY_STRINGS.mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
                .flatMap { it.fields.toList() }
                .mapNotNull { field -> runCatching { context.getString(field.getInt(null)) }.getOrNull() }
                .toSet()

        fun inLocale(context: Context, locale: Locale): Context =
            context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })

        private val LIBRARY_STRINGS = listOf(
            "androidx.compose.ui.R\$string",
            "androidx.compose.material3.R\$string",
            "androidx.compose.foundation.R\$string",
            "androidx.biometric.R\$string",
            "androidx.appcompat.R\$string",
            "androidx.core.R\$string",
        )
    }
}
