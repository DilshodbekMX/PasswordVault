package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText

/**
 * The typed text as characters, without making a `String` of it, for `MasterPasswordBytes`, the
 * lock or an edit. The caller zeroes the array after use (architecture.md §5.6).
 */
fun TextFieldState.readSecret(): CharArray {
    val text = this.text
    return CharArray(text.length) { text[it] }
}

/**
 * Empties a field that held a secret, and its undo history: `clearText()` alone leaves the text
 * there, where the keyboard's undo brings it back [V-C11] (architecture.md §5.6). A ViewModel calls
 * it after reading the secret and when the vault locks.
 */
@OptIn(ExperimentalFoundationApi::class)
fun TextFieldState.clearSecret() {
    clearText()
    undoState.clearHistory()
}
