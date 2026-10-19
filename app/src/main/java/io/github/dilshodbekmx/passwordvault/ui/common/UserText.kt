package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/**
 * Marks a text that the user typed or brought in (an account's name, a login, a website, the notes, an
 * extra field's own label, a file's name): it is shown as it is in every language, never taken from
 * the resources.
 *
 * The mark changes nothing on the screen and nothing an accessibility service reads. It is there for
 * the layout checks of build plan 6.2 (`LayoutProbe` in the device tests), whose check "no text left in
 * plain English" in a pseudo-locale passes over a text so marked: any other text without a letter
 * that the pseudo-locale made was not taken from the resources.
 */
val UserText = SemanticsPropertyKey<Boolean>("PvUserText")

/** This text is the user's own ([UserText]). */
fun Modifier.userText(): Modifier = semantics { set(UserText, true) }
