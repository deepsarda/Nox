package nox.runtime

/**
 * Sealed hierarchy returned by the Host in response to a [PermissionRequest].
 *
 * Simple hosts can always return [Granted.Unconstrained]. Sophisticated
 * policy engines can return typed constraints (path rewrites, domain
 * allowlists, byte limits, etc.).
 *
 * See docs/architecture/overview.md.
 */
sealed class PermissionResponse {
    sealed class Granted : PermissionResponse() {
        /** No restrictions, full access approved. */
        data object Unconstrained : Granted()

        /** File operation approved with optional limits. */
        data class FileGrant(
            val maxBytes: Long? = null,
            val rewrittenPath: String? = null,
            val allowedDirectories: List<String>? = null,
            val allowedExtensions: List<String>? = null,
            val readOnly: Boolean = false,
        ) : Granted()

        /** HTTP operation approved with optional limits. */
        data class HttpGrant(
            val maxResponseSize: Long? = null,
            val timeoutMs: Long? = null,
            val allowedDomains: List<String>? = null,
            val allowedPorts: List<Int>? = null,
            val httpsOnly: Boolean = false,
        ) : Granted()

        /** Env operation approved with optional limits. */
        data class EnvGrant(
            val allowedVarNames: List<String>? = null,
        ) : Granted()

        /** Plugin-defined constraints. */
        data class PluginGrant(
            val values: Map<String, Any> = emptyMap(),
        ) : Granted()
    }

    /** Permission denied with optional reason. */
    data class Denied(
        val reason: String? = null,
    ) : PermissionResponse()
}
