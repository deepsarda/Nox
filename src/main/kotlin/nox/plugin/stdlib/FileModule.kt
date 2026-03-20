package nox.plugin.stdlib

import nox.plugin.annotations.NoxFunction
import nox.plugin.annotations.NoxModule
import nox.plugin.annotations.NoxType
import nox.runtime.PermissionRequest
import nox.runtime.PermissionResponse
import nox.runtime.RuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Nox standard library: `File` namespace.
 *
 * All operations require explicit permission grants through [RuntimeContext].
 * Each method calls `context.requestPermission(...)` before performing I/O.
 * If permission is denied, a [SecurityException] is thrown (caught by the VM
 * and converted to a Nox exception).
 *
 * NSL usage:
 * ```
 * string content = File.read("/data/config.json");
 * File.write("/tmp/output.txt", "hello");
 * boolean exists = File.exists("/data/config.json");
 * ```
 *
 * See docs/language/stdlib.md.
 */
@NoxModule(namespace = "File")
object FileModule {
    @NoxFunction(name = "read")
    @JvmStatic
    suspend fun read(
        ctx: RuntimeContext,
        path: String,
    ): String {
        requirePermission(ctx, PermissionRequest.File.Read(path))
        return Path.of(path).readText()
    }

    @NoxFunction(name = "write")
    @JvmStatic
    suspend fun write(
        ctx: RuntimeContext,
        path: String,
        content: String,
    ) {
        requirePermission(ctx, PermissionRequest.File.Write(path))
        Path.of(path).writeText(content)
    }

    @NoxFunction(name = "append")
    @JvmStatic
    suspend fun append(
        ctx: RuntimeContext,
        path: String,
        content: String,
    ) {
        requirePermission(ctx, PermissionRequest.File.Append(path))
        Files.writeString(Path.of(path), content, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

    @NoxFunction(name = "delete")
    @JvmStatic
    suspend fun delete(
        ctx: RuntimeContext,
        path: String,
    ) {
        requirePermission(ctx, PermissionRequest.File.Delete(path))
        Files.deleteIfExists(Path.of(path))
    }

    @NoxFunction(name = "exists")
    @JvmStatic
    suspend fun exists(
        ctx: RuntimeContext,
        path: String,
    ): Boolean {
        requirePermission(ctx, PermissionRequest.File.Metadata(path))
        return Path.of(path).exists()
    }

    @NoxFunction(name = "list")
    @NoxType("string[]")
    @JvmStatic
    suspend fun list(
        ctx: RuntimeContext,
        dir: String,
    ): List<String> {
        requirePermission(ctx, PermissionRequest.File.List(dir))
        return Files.list(Path.of(dir)).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
    }

    @NoxFunction(name = "metadata")
    @NoxType("json")
    @JvmStatic
    suspend fun metadata(
        ctx: RuntimeContext,
        path: String,
    ): Map<String, Any?> {
        requirePermission(ctx, PermissionRequest.File.Metadata(path))
        val p = Path.of(path)
        val attrs = Files.readAttributes(p, "size,lastModifiedTime,creationTime,isDirectory")
        return attrs.mapValues { (_, v) -> v?.toString() }
    }

    @NoxFunction(name = "createDir")
    @JvmStatic
    suspend fun createDir(
        ctx: RuntimeContext,
        path: String,
    ) {
        requirePermission(ctx, PermissionRequest.File.CreateDirectory(path))
        Files.createDirectories(Path.of(path))
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
