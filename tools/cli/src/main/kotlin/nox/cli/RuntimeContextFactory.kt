package nox.cli

import com.github.ajalt.mordant.terminal.Terminal
import nox.cli.policy.PermissionPolicy
import nox.cli.policy.SessionPolicyCache
import nox.cli.prompt.PermissionPrompt
import nox.cli.prompt.ResourcePrompt
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.ResourceRequest
import nox.runtime.ResourceResponse
import nox.runtime.RuntimeContext

/**
 * Creates a [RuntimeContext] implementation wiring the three-layer permission system:
 *
 * ```
 * Request -> PermissionPolicy -> SessionPolicyCache -> PermissionPrompt
 * ```
 */
object RuntimeContextFactory {
    fun create(
        policy: PermissionPolicy,
        terminal: Terminal,
        autoExtend: Boolean,
        onYield: (String) -> Unit,
    ): RuntimeContext {
        val cache = SessionPolicyCache()
        val permissionPrompt = PermissionPrompt(terminal, cache)
        val resourcePrompt = ResourcePrompt(terminal)

        return object : RuntimeContext {
            override fun yield(data: String) {
                onYield(data)
            }

            override fun returnResult(data: String) { /* collected by VM */ }

            override suspend fun requestPermission(request: PermissionRequest): PermissionResponse {
                // Layer 1: Static policy from CLI flags
                policy.evaluate(request)?.let { return it }
                // Layer 2: Cached interactive decisions
                cache.check(request)?.let { return it }
                // Layer 3: Interactive TUI prompt
                return permissionPrompt.prompt(request)
            }

            override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse {
                if (autoExtend) return ResourceResponse.Granted(Long.MAX_VALUE)
                return resourcePrompt.prompt(request)
            }
        }
    }
}
