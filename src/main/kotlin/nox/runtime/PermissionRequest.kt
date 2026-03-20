package nox.runtime

/**
 * Sealed hierarchy representing every type of permission a sandbox can request.
 *
 * The type itself encodes the category and action; constructor parameters
 * carry typed details. The Host pattern-matches on these to evaluate policy.
 *
 * See docs/architecture/overview.md.
 */
sealed class PermissionRequest {
    /** File system operations. */
    sealed class File : PermissionRequest() {
        data class Read(
            val path: String,
        ) : File()

        data class Write(
            val path: String,
        ) : File()

        data class Append(
            val path: String,
        ) : File()

        data class Delete(
            val path: String,
        ) : File()

        data class List(
            val directory: String,
        ) : File()

        data class Metadata(
            val path: String,
        ) : File()

        data class CreateDirectory(
            val path: String,
        ) : File()
    }

    /** Network operations. */
    sealed class Http : PermissionRequest() {
        data class Get(
            val url: String,
        ) : Http()

        data class Post(
            val url: String,
            val contentType: String? = null,
        ) : Http()

        data class Put(
            val url: String,
            val contentType: String? = null,
        ) : Http()

        data class Delete(
            val url: String,
        ) : Http()
    }

    /** Environment and system information. */
    sealed class Env : PermissionRequest() {
        data class ReadVar(
            val name: String,
        ) : Env()

        data class SystemInfo(
            val property: String,
        ) : Env()
    }

    /** Escape hatch for plugin-defined permissions. */
    data class Plugin(
        val category: String,
        val action: String,
        val details: Map<String, Any> = emptyMap(),
    ) : PermissionRequest()
}
