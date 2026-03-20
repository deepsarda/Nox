package nox.plugin.stdlib

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.RuntimeContext

/**
 * Nox standard library: `Env` namespace.
 *
 * All operations require explicit permission grants through [RuntimeContext].
 *
 * NSL usage:
 * ```
 * string apiKey = Env.get("API_KEY");
 * string osName = Env.system("os.name");
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "Env")
object EnvModule {
    @NoxFunction(name = "get")
    @JvmStatic
    suspend fun get(
        ctx: RuntimeContext,
        name: String,
    ): String {
        requirePermission(ctx, PermissionRequest.Env.ReadVar(name))
        return System.getenv(name) ?: ""
    }

    @NoxFunction(name = "system")
    @JvmStatic
    suspend fun system(
        ctx: RuntimeContext,
        property: String,
    ): String {
        requirePermission(ctx, PermissionRequest.Env.SystemInfo(property))
        return System.getProperty(property) ?: ""
    }

    private suspend fun requirePermission(
        ctx: RuntimeContext,
        request: PermissionRequest,
    ) {
        val response = ctx.requestPermission(request)
        if (response is PermissionResponse.Denied) {
            throw SecurityException(
                "Permission denied: ${request::class.simpleName}" +
                    (response.reason?.let { " - $it" } ?: ""),
            )
        }
    }
}
