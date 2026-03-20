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
        val grant = requireFilePermission(ctx, PermissionRequest.File.Read(path))
        val effectivePath = grant?.rewrittenPath ?: path

        enforceDirectoryConstraint(grant, effectivePath)
        enforceExtensionConstraint(grant, effectivePath)

        val content = Path.of(effectivePath).readText()
        if (grant?.maxBytes != null && content.toByteArray().size > grant.maxBytes) {
            return content
                .toByteArray()
                .take(grant.maxBytes.toInt())
                .toByteArray()
                .toString(Charsets.UTF_8)
        }

        return content
    }

    @NoxFunction(name = "write")
    @JvmStatic
    suspend fun write(
        ctx: RuntimeContext,
        path: String,
        content: String,
    ) {
        val grant = requireFilePermission(ctx, PermissionRequest.File.Write(path))
        if (grant?.readOnly == true) {
            throw SecurityException("Permission denied: write blocked by readOnly constraint")
        }

        val effectivePath = grant?.rewrittenPath ?: path

        enforceDirectoryConstraint(grant, effectivePath)
        enforceExtensionConstraint(grant, effectivePath)

        if (grant?.maxBytes != null && content.toByteArray().size > grant.maxBytes) {
            throw SecurityException("Permission denied: content exceeds maxBytes limit of ${grant.maxBytes}")
        }

        Path.of(effectivePath).writeText(content)
    }

    @NoxFunction(name = "append")
    @JvmStatic
    suspend fun append(
        ctx: RuntimeContext,
        path: String,
        content: String,
    ) {
        val grant = requireFilePermission(ctx, PermissionRequest.File.Append(path))
        if (grant?.readOnly == true) {
            throw SecurityException("Permission denied: append blocked by readOnly constraint")
        }

        val effectivePath = grant?.rewrittenPath ?: path

        enforceDirectoryConstraint(grant, effectivePath)
        enforceExtensionConstraint(grant, effectivePath)

        if (grant?.maxBytes != null && content.toByteArray().size > grant.maxBytes) {
            throw SecurityException("Permission denied: content exceeds maxBytes limit of ${grant.maxBytes}")
        }

        Files.writeString(Path.of(effectivePath), content, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

    @NoxFunction(name = "delete")
    @JvmStatic
    suspend fun delete(
        ctx: RuntimeContext,
        path: String,
    ) {
        val grant = requireFilePermission(ctx, PermissionRequest.File.Delete(path))
        if (grant?.readOnly == true) {
            throw SecurityException("Permission denied: delete blocked by readOnly constraint")
        }

        val effectivePath = grant?.rewrittenPath ?: path
        enforceDirectoryConstraint(grant, effectivePath)

        Files.deleteIfExists(Path.of(effectivePath))
    }

    @NoxFunction(name = "exists")
    @JvmStatic
    suspend fun exists(
        ctx: RuntimeContext,
        path: String,
    ): Boolean {
        val grant = requireFilePermission(ctx, PermissionRequest.File.Metadata(path))
        val effectivePath = grant?.rewrittenPath ?: path

        enforceDirectoryConstraint(grant, effectivePath)

        return Path.of(effectivePath).exists()
    }

    @NoxFunction(name = "list")
    @NoxType("string[]")
    @JvmStatic
    suspend fun list(
        ctx: RuntimeContext,
        dir: String,
    ): List<String> {
        val grant = requireFilePermission(ctx, PermissionRequest.File.List(dir))
        val effectiveDir = grant?.rewrittenPath ?: dir

        enforceDirectoryConstraint(grant, effectiveDir)

        return Files.list(Path.of(effectiveDir)).use { stream ->
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
        val grant = requireFilePermission(ctx, PermissionRequest.File.Metadata(path))
        val effectivePath = grant?.rewrittenPath ?: path

        enforceDirectoryConstraint(grant, effectivePath)

        val p = Path.of(effectivePath)
        val attrs = Files.readAttributes(p, "size,lastModifiedTime,creationTime,isDirectory")
        return attrs.mapValues { (_, v) -> v?.toString() }
    }

    @NoxFunction(name = "createDir")
    @JvmStatic
    suspend fun createDir(
        ctx: RuntimeContext,
        path: String,
    ) {
        val grant = requireFilePermission(ctx, PermissionRequest.File.CreateDirectory(path))
        if (grant?.readOnly == true) {
            throw SecurityException("Permission denied: createDir blocked by readOnly constraint")
        }

        val effectivePath = grant?.rewrittenPath ?: path
        enforceDirectoryConstraint(grant, effectivePath)

        Files.createDirectories(Path.of(effectivePath))
    }

    /**
     * Requests permission and returns the [PermissionResponse.Granted.FileGrant] if one
     * was returned, or null if [PermissionResponse.Granted.Unconstrained].
     */
    private suspend fun requireFilePermission(
        ctx: RuntimeContext,
        request: PermissionRequest,
    ): PermissionResponse.Granted.FileGrant? =
        when (val response = ctx.requestPermission(request)) {
            is PermissionResponse.Granted.FileGrant -> response
            is PermissionResponse.Granted -> null // Unconstrained or other grant
            is PermissionResponse.Denied -> throw SecurityException(
                "Permission denied: ${request::class.simpleName}" +
                    (response.reason?.let { " - $it" } ?: ""),
            )
        }

    private fun enforceDirectoryConstraint(
        grant: PermissionResponse.Granted.FileGrant?,
        path: String,
    ) {
        val dirs = grant?.allowedDirectories ?: return
        val normalized =
            Path
                .of(path)
                .toAbsolutePath()
                .normalize()
                .toString()
        if (dirs.none {
                normalized.startsWith(
                    Path
                        .of(it)
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                )
            }
        ) {
            throw SecurityException(
                "Permission denied: path '$path' is outside allowed directories: $dirs",
            )
        }
    }

    private fun enforceExtensionConstraint(
        grant: PermissionResponse.Granted.FileGrant?,
        path: String,
    ) {
        val exts = grant?.allowedExtensions ?: return
        val ext = path.substringAfterLast('.', "")
        if (ext !in exts) {
            throw SecurityException(
                "Permission denied: extension '.$ext' is not in allowed extensions: $exts",
            )
        }
    }
}
