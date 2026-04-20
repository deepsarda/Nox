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

        /**
         * File operation approved with optional limits.
         *
         * **Null semantics:** For [allowedDirectories] and [allowedExtensions],
         * `null` means "no restriction" (any directory/extension is allowed).
         * A non-null list enforces the constraint. An empty list means "deny all".
         *
         * @property maxBytes          maximum bytes the operation may read/write; must be > 0 when set
         * @property rewrittenPath     redirect the path to a safe location
         * @property allowedDirectories restrict access to specific directories; null = unrestricted
         * @property allowedExtensions  restrict to specific file extensions; null = unrestricted
         * @property readOnly          deny any write/append/delete attempt
         */
        data class FileGrant(
            val maxBytes: Long? = null,
            val rewrittenPath: String? = null,
            val allowedDirectories: List<String>? = null,
            val allowedExtensions: List<String>? = null,
            val readOnly: Boolean = false,
        ) : Granted() {
            init {
                require(maxBytes == null || maxBytes > 0) {
                    "maxBytes must be > 0 when set, got $maxBytes"
                }
            }
        }

        /**
         * HTTP operation approved with optional limits.
         *
         * **Null semantics:** For [allowedDomains] and [allowedPorts],
         * `null` means "no restriction" (any domain/port is allowed).
         * A non-null list enforces the constraint. An empty list means "deny all".
         *
         * @property maxResponseSize maximum response body size in bytes; must be > 0 when set
         * @property timeoutMs       maximum time to wait for a response in ms; must be > 0 when set
         * @property allowedDomains  restrict requests to specific domains; null = unrestricted
         * @property allowedPorts    restrict to specific ports (each must be 0–65535); null = unrestricted
         * @property httpsOnly       deny any plain HTTP request
         */
        data class HttpGrant(
            val maxResponseSize: Long? = null,
            val timeoutMs: Long? = null,
            val allowedDomains: List<String>? = null,
            val allowedPorts: List<Int>? = null,
            val httpsOnly: Boolean = false,
        ) : Granted() {
            init {
                require(maxResponseSize == null || maxResponseSize > 0) {
                    "maxResponseSize must be > 0 when set, got $maxResponseSize"
                }
                require(timeoutMs == null || timeoutMs > 0) {
                    "timeoutMs must be > 0 when set, got $timeoutMs"
                }
                allowedPorts?.forEach { port ->
                    require(port in 0..65535) {
                        "allowedPorts values must be in 0..65535, got $port"
                    }
                }
            }
        }

        /** Env operation approved with optional limits. */
        data class EnvGrant(
            val allowedVarNames: List<String>? = null,
        ) : Granted()

        /** Plugin-defined constraints. */
        data class PluginGrant(
            val values: Map<String, PluginValue> = emptyMap(),
        ) : Granted()
    }

    /** Permission denied with optional reason. */
    data class Denied(
        val reason: String? = null,
    ) : PermissionResponse()
}
