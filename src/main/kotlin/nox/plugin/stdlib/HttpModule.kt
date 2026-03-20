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
        requirePermission(ctx, PermissionRequest.Http.Get(url))
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    @NoxFunction(name = "getJson")
    @NoxType("json")
    @JvmStatic
    suspend fun getJson(
        ctx: RuntimeContext,
        url: String,
    ): Any? {
        requirePermission(ctx, PermissionRequest.Http.Get(url))
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val body = client.send(request, HttpResponse.BodyHandlers.ofString()).body()
        // The VM will parse this JSON string into a NoxObject at runtime.
        // For now, return the raw string; the VM's JSON deserializer handles conversion.
        return body
    }

    @NoxFunction(name = "post")
    @JvmStatic
    suspend fun post(
        ctx: RuntimeContext,
        url: String,
        body: String,
    ): String {
        requirePermission(ctx, PermissionRequest.Http.Post(url))
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    @NoxFunction(name = "put")
    @JvmStatic
    suspend fun put(
        ctx: RuntimeContext,
        url: String,
        body: String,
    ): String {
        requirePermission(ctx, PermissionRequest.Http.Put(url))
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    @NoxFunction(name = "delete")
    @JvmStatic
    suspend fun delete(
        ctx: RuntimeContext,
        url: String,
    ): String {
        requirePermission(ctx, PermissionRequest.Http.Delete(url))
        val request = HttpRequest.newBuilder(URI.create(url)).DELETE().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
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
