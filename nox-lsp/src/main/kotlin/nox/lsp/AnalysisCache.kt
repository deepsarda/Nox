package nox.lsp

import nox.compiler.NoxCompiler
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches [NoxCompiler.CompilationResult]s per (uri, version). Full reparse on every change
 * is fine, since Nox files are small and the compiler is fast.
 */
class AnalysisCache {
    private data class Key(
        val uri: String,
        val version: Int,
    )

    private val cache = ConcurrentHashMap<Key, NoxCompiler.CompilationResult>()

    fun analyze(
        docs: DocumentManager,
        doc: DocumentManager.Document,
    ): NoxCompiler.CompilationResult {
        val key = Key(doc.uri, doc.version)
        cache[key]?.let { return it }

        val basePath = uriToPath(doc.uri)
        val fileName = basePath?.toString() ?: DocumentManager.fileNameFromUri(doc.uri)

        val result =
            NoxCompiler.analyze(
                source = doc.text,
                fileName = fileName,
                basePath = basePath ?: Path.of(".").toAbsolutePath(),
                fileReader = { path ->
                    val uri = path.toUri().toString()
                    docs.get(uri)?.text ?: path.toFile().readText()
                },
            )
        cache[key] = result
        // Evict stale versions for this URI to keep memory bounded.
        cache.keys.removeIf { it.uri == doc.uri && it.version != doc.version }
        return result
    }

    fun invalidate(uri: String) {
        cache.keys.removeIf { it.uri == uri }
    }

    private fun uriToPath(uri: String): Path? =
        runCatching {
            Paths.get(URI.create(uri)).toAbsolutePath()
        }.getOrNull()
}
