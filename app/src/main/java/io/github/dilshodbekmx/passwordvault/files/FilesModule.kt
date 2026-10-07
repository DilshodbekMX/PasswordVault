package io.github.dilshodbekmx.passwordvault.files

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo

/** Builds [DocumentIo]: `SafDocumentIo` from build plan 5.1; until then the in-memory fake. */
object FilesModule {
    fun create(graph: AppGraph): DocumentIo = FakeDocumentIo()
}
