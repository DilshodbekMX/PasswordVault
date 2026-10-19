package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/**
 * Marks a text that may be cut with an ellipsis, because its full text is reachable elsewhere
 * (i18n.md §4.5 rule 3): a list row's title, whose account screen shows it whole, or a top bar's
 * title, which the screen repeats in its content (ux.md §8 "Font scaling"). Nothing else may be cut:
 * never a warning, a button or a line of a screen's own text.
 *
 * The mark changes nothing on the screen and nothing an accessibility service reads. It is there for
 * the layout checks of build plan 6.2 (`tools/emu/layout/`, `LayoutProbe` in the device tests),
 * which report every other text that is cut, clipped or pushed past the edge of its window.
 */
val MayTruncate = SemanticsPropertyKey<Boolean>("PvMayTruncate")

/** This text may be cut with an ellipsis: its full text is reachable elsewhere ([MayTruncate]). */
fun Modifier.mayTruncate(): Modifier = semantics { set(MayTruncate, true) }
