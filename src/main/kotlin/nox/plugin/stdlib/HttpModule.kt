package nox.plugin.stdlib

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.RuntimeContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Nox standard library: `Http` namespace.
 *
 * All operations require explicit permission grants through [RuntimeContext].
 *
 * NSL usage:
 * ```
 * string body = Http.get("https://api.example.com/data");
 * json data = Http.getJson("https://api.example.com/data");
 * string resp = Http.post("https://api.example.com/submit", "{\"key\":\"val\"}");
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "Http")
object HttpModule {
    private val client: HttpClient = HttpClient.newHttpClient()

    @NoxFunction(name = "get")
    @JvmStatic
    suspend fun get(
        ctx: RuntimeContext,
        url: String,
    ): String {
        val grant = requireHttpPermission(ctx, PermissionRequest.Http.Get(url))
        enforceHttpConstraints(grant, url)

        val request = buildRequest(grant, url) { it.GET() }
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return enforceMaxResponseSize(grant, body)
    }

    @NoxFunction(name = "getJson")
    @NoxType("json")
    @JvmStatic
    suspend fun getJson(
        ctx: RuntimeContext,
        url: String,
    ): Any? {
        val grant = requireHttpPermission(ctx, PermissionRequest.Http.Get(url))

        enforceHttpConstraints(grant, url)

        val request = buildRequest(grant, url) { it.GET() }
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return enforceMaxResponseSize(grant, body)
    }

    @NoxFunction(name = "post")
    @JvmStatic
    suspend fun post(
        ctx: RuntimeContext,
        url: String,
        body: String,
    ): String {
        val grant = requireHttpPermission(ctx, PermissionRequest.Http.Post(url))

        enforceHttpConstraints(grant, url)

        val request =
            buildRequest(grant, url) {
                it
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
            }
        val response = client.send(request, HttpResponse.BodyHandlers.ofString()).body()

        return enforceMaxResponseSize(grant, response)
    }

    @NoxFunction(name = "put")
    @JvmStatic
    suspend fun put(
        ctx: RuntimeContext,
        url: String,
        body: String,
    ): String {
        val grant = requireHttpPermission(ctx, PermissionRequest.Http.Put(url))

        enforceHttpConstraints(grant, url)

        val request =
            buildRequest(grant, url) {
                it
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
            }

        val response = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return enforceMaxResponseSize(grant, response)
    }

    @NoxFunction(name = "delete")
    @JvmStatic
    suspend fun delete(
        ctx: RuntimeContext,
        url: String,
    ): String {
        val grant = requireHttpPermission(ctx, PermissionRequest.Http.Delete(url))

        enforceHttpConstraints(grant, url)

        val request = buildRequest(grant, url) { it.DELETE() }
        val response = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return enforceMaxResponseSize(grant, response)
    }

    private suspend fun requireHttpPermission(
        ctx: RuntimeContext,
        request: PermissionRequest,
    ): PermissionResponse.Granted.HttpGrant? =
        when (val response = ctx.requestPermission(request)) {
            is PermissionResponse.Granted.HttpGrant -> response
            is PermissionResponse.Granted -> null
            is PermissionResponse.Denied -> throw SecurityException(
                "Permission denied: ${request::class.simpleName}" +
                    (response.reason?.let { " - $it" } ?: ""),
            )
        }

    private fun enforceHttpConstraints(
        grant: PermissionResponse.Granted.HttpGrant?,
        url: String,
    ) {
        if (grant == null) return
        val uri = URI.create(url)

        // httpsOnly
        if (grant.httpsOnly && uri.scheme != "https") {
            throw SecurityException("Permission denied: only HTTPS URLs are allowed, got '${uri.scheme}'")
        }

        // allowedDomains
        val domains = grant.allowedDomains
        if (domains != null && uri.host !in domains) {
            throw SecurityException("Permission denied: domain '${uri.host}' not in allowed list: $domains")
        }

        // allowedPorts
        val ports = grant.allowedPorts
        if (ports != null) {
            val effectivePort =
                if (uri.port == -1) {
                    if (uri.scheme == "https") 443 else 80
                } else {
                    uri.port
                }
            if (effectivePort !in ports) {
                throw SecurityException("Permission denied: port $effectivePort not in allowed list: $ports")
            }
        }
    }

    private fun buildRequest(
        grant: PermissionResponse.Granted.HttpGrant?,
        url: String,
        configure: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(url))
        if (grant?.timeoutMs != null) {
            builder.timeout(java.time.Duration.ofMillis(grant.timeoutMs))
        }
        return configure(builder).build()
    }

    private fun enforceMaxResponseSize(
        grant: PermissionResponse.Granted.HttpGrant?,
        body: String,
    ): String {
        val max = grant?.maxResponseSize ?: return body
        if (body.length > max) {
            return body.substring(0, max.toInt())
        }
        return body
    }
}
