package nox.lsp

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** In-memory open-file buffer. Keyed by URI; versioned to match LSP didChange contract. */
class DocumentManager {
    data class Document(
        val uri: String,
        val version: Int,
        val text: String,
    )

    private val docs = ConcurrentHashMap<String, Document>()

    fun open(
        uri: String,
        version: Int,
        text: String,
    ) {
        docs[uri] = Document(uri, version, text)
    }

    fun replace(
        uri: String,
        version: Int,
        text: String,
    ) {
        docs[uri] = Document(uri, version, text)
    }

    fun close(uri: String) {
        docs.remove(uri)
    }

    fun get(uri: String): Document? = docs[uri]

    fun all(): Collection<Document> = docs.values

    companion object {
        fun fileNameFromUri(uri: String): String =
            runCatching {
                Paths.get(URI.create(uri)).fileName?.toString() ?: uri
            }.getOrDefault(uri)
    }
}
