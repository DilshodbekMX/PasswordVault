package io.github.dilshodbekmx.passwordvault.core.model

/**
 * One extra field of an entry (ux.md §2): a PIN code, a phone number, a security answer.
 * `{"name": string, "value": string, "secret": boolean}` in the vault body (architecture.md §4.3).
 * A [secret] value is masked until the user taps "Show". [toString] shows neither text (§5.6).
 */
data class CustomField(val name: String, val value: String, val secret: Boolean) {
    override fun toString(): String = "CustomField(secret=$secret, texts hidden)"
}
