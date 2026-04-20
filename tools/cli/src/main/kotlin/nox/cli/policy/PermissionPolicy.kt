package nox.cli.policy

import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import java.nio.file.Path

/**
 * Static permission policy built from CLI flags.
 *
 * Evaluated before interactive prompting. Returns a [PermissionResponse] when
 * the policy covers the request, or `null` to fall through to the interactive prompt.
 */
class PermissionPolicy(
    val allowAll: Boolean = false,
    val noPrompt: Boolean = false,
    // File
    val fileReadPaths: List<Path> = emptyList(),
    val fileWritePaths: List<Path> = emptyList(),
    val fileDeletePaths: List<Path> = emptyList(),
    val fileListPaths: List<Path> = emptyList(),
    val fileAllPaths: List<Path> = emptyList(),
    val fileAllowedExtensions: List<String>? = null,
    val fileMaxBytes: Long? = null,
    val fileReadOnly: Boolean = false,
    // HTTP
    val httpAllDomains: List<String> = emptyList(),
    val httpGetDomains: List<String> = emptyList(),
    val httpPostDomains: List<String> = emptyList(),
    val httpPutDomains: List<String> = emptyList(),
    val httpDeleteDomains: List<String> = emptyList(),
    val httpAllowedPorts: List<Int>? = null,
    val httpsOnly: Boolean = false,
    val httpTimeoutMs: Long? = null,
    val httpMaxResponse: Long? = null,
    // Env
    val envAllowedVars: List<String> = emptyList(),
    val envAllowAll: Boolean = false,
    val sysinfoAllowed: List<String> = emptyList(),
    val sysinfoAllowAll: Boolean = false,
    // Plugin
    val pluginAllowed: List<Pair<String, String>> = emptyList(),
    val pluginCatsAllowed: List<String> = emptyList(),
) {
    fun evaluate(request: PermissionRequest): PermissionResponse? {
        if (allowAll) return PermissionResponse.Granted.Unconstrained

        val result = evaluateSpecific(request)
        if (result != null) return result

        // noPrompt mode: deny anything not explicitly allowed
        if (noPrompt) return PermissionResponse.Denied("Not pre-allowed (--no-prompt)")

        return null // fall through to interactive prompt
    }

    private fun evaluateSpecific(request: PermissionRequest): PermissionResponse? =
        when (request) {
            is PermissionRequest.File -> evaluateFile(request)
            is PermissionRequest.Http -> evaluateHttp(request)
            is PermissionRequest.Env -> evaluateEnv(request)
            is PermissionRequest.Plugin -> evaluatePlugin(request)
        }

    private fun evaluateFile(request: PermissionRequest.File): PermissionResponse? {
        val path =
            when (request) {
                is PermissionRequest.File.Read -> request.path
                is PermissionRequest.File.Write -> request.path
                is PermissionRequest.File.Append -> request.path
                is PermissionRequest.File.Delete -> request.path
                is PermissionRequest.File.List -> request.directory
                is PermissionRequest.File.Metadata -> request.path
                is PermissionRequest.File.CreateDirectory -> request.path
            }
        val canonical = Path.of(path).toAbsolutePath().normalize()

        // Check extension filter
        if (fileAllowedExtensions != null) {
            val ext = canonical.fileName?.toString()?.substringAfterLast('.', "") ?: ""
            if (".$ext" !in fileAllowedExtensions && ext !in fileAllowedExtensions) return null
        }

        // Read-only mode: deny writes
        if (fileReadOnly &&
            request !is PermissionRequest.File.Read &&
            request !is PermissionRequest.File.List &&
            request !is PermissionRequest.File.Metadata
        ) {
            return PermissionResponse.Denied("Read-only mode (--file-read-only)")
        }

        val grant =
            PermissionResponse.Granted.FileGrant(
                maxBytes = fileMaxBytes,
                allowedExtensions = fileAllowedExtensions,
                readOnly = fileReadOnly,
            )

        // Check --allow-file (all ops)
        if (fileAllPaths.any { canonical.startsWith(it) }) return grant

        // Check specific op paths
        val matchPaths =
            when (request) {
                is PermissionRequest.File.Read, is PermissionRequest.File.Metadata ->
                    fileReadPaths

                is PermissionRequest.File.Write, is PermissionRequest.File.Append,
                is PermissionRequest.File.CreateDirectory,
                ->
                    fileWritePaths

                is PermissionRequest.File.Delete -> fileDeletePaths
                is PermissionRequest.File.List -> fileListPaths
            }

        // Read-only flag grants all reads everywhere
        if (fileReadOnly &&
            (
                request is PermissionRequest.File.Read ||
                    request is PermissionRequest.File.List ||
                    request is PermissionRequest.File.Metadata
            )
        ) {
            return grant
        }

        if (matchPaths.any { canonical.startsWith(it) }) return grant

        return null
    }

    private fun evaluateHttp(request: PermissionRequest.Http): PermissionResponse? {
        val url =
            when (request) {
                is PermissionRequest.Http.Get -> request.url
                is PermissionRequest.Http.Post -> request.url
                is PermissionRequest.Http.Put -> request.url
                is PermissionRequest.Http.Delete -> request.url
            }

        val domain = extractDomain(url) ?: return null
        val isHttps = url.startsWith("https://")

        if (httpsOnly && !isHttps) {
            return PermissionResponse.Denied("HTTPS only (--https-only)")
        }

        if (httpAllowedPorts != null) {
            val port = extractPort(url, isHttps)
            if (port !in httpAllowedPorts) return null
        }

        val grant =
            PermissionResponse.Granted.HttpGrant(
                maxResponseSize = httpMaxResponse,
                timeoutMs = httpTimeoutMs,
                allowedPorts = httpAllowedPorts,
                httpsOnly = httpsOnly,
            )

        // Check --allow-http (all methods)
        if (httpAllDomains.any { domain.equals(it, ignoreCase = true) }) return grant

        // Check method-specific
        val matchDomains =
            when (request) {
                is PermissionRequest.Http.Get -> httpGetDomains
                is PermissionRequest.Http.Post -> httpPostDomains
                is PermissionRequest.Http.Put -> httpPutDomains
                is PermissionRequest.Http.Delete -> httpDeleteDomains
            }
        if (matchDomains.any { domain.equals(it, ignoreCase = true) }) return grant

        return null
    }

    private fun evaluateEnv(request: PermissionRequest.Env): PermissionResponse? =
        when (request) {
            is PermissionRequest.Env.ReadVar -> {
                if (envAllowAll || envAllowedVars.any { it == request.name }) {
                    PermissionResponse.Granted.EnvGrant()
                } else {
                    null
                }
            }
            is PermissionRequest.Env.SystemInfo -> {
                if (sysinfoAllowAll || sysinfoAllowed.any { it == request.property }) {
                    PermissionResponse.Granted.EnvGrant()
                } else {
                    null
                }
            }
        }

    private fun evaluatePlugin(request: PermissionRequest.Plugin): PermissionResponse? {
        if (pluginCatsAllowed.any { it == request.category }) {
            return PermissionResponse.Granted.PluginGrant()
        }
        if (pluginAllowed.any { it.first == request.category && it.second == request.action }) {
            return PermissionResponse.Granted.PluginGrant()
        }
        return null
    }

    companion object {
        fun extractDomain(url: String): String? {
            val withoutScheme = url.removePrefix("https://").removePrefix("http://")
            return withoutScheme.substringBefore('/').substringBefore(':').ifEmpty { null }
        }

        fun extractPort(
            url: String,
            isHttps: Boolean,
        ): Int {
            val withoutScheme = url.removePrefix("https://").removePrefix("http://")
            val hostPort = withoutScheme.substringBefore('/')
            return if (':' in hostPort) {
                hostPort.substringAfter(':').toIntOrNull() ?: if (isHttps) 443 else 80
            } else {
                if (isHttps) 443 else 80
            }
        }
    }
}
