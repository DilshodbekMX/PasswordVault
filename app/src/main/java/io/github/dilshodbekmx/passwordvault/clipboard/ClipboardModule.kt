package io.github.dilshodbekmx.passwordvault.clipboard

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard

/** Builds [SecretClipboard]: `AndroidSecretClipboard` from build plan 3.8; until then the fake, which touches no clipboard. */
object ClipboardModule {
    fun create(graph: AppGraph): SecretClipboard = FakeSecretClipboard()
}
