package io.github.dilshodbekmx.passwordvault.files

import io.github.dilshodbekmx.passwordvault.AppGraph

/**
 * Builds [DocumentIo]: [SafDocumentIo] over the Storage Access Framework (build plan 5.1), its
 * streams on the I/O dispatcher. The picker itself and the unfinished file task are the process's
 * [FileTaskStore].
 */
object FilesModule {
    fun create(graph: AppGraph): DocumentIo = SafDocumentIo(graph.app, graph.dispatchers.io)
}
