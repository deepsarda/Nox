package nox.runtime

import kotlinx.coroutines.runBlocking
import nox.compiler.NoxCompiler
import nox.plugin.LibraryRegistry
import nox.vm.ExecutionConfig
import nox.vm.NoxVM
import java.time.Duration

/**
 * Public facade for compiling and executing Nox programs.
 *
 * See docs/architecture/overview.md.
 */
class NoxRuntime private constructor(
    private val config: ExecutionConfig,
    private val registry: LibraryRegistry,
    private val permissionHandler: (suspend (PermissionRequest) -> PermissionResponse)?,
    private val resourceHandler: (suspend (ResourceRequest) -> ResourceResponse)?,
) {
    fun execute(
        source: String,
        fileName: String = "script.nox",
    ): NoxResult {
        if (source.isBlank()) {
            return NoxResult.Error(NoxError.CompilationError, "Empty source", emptyList())
        }
        val result = NoxCompiler.compile(source, fileName)
        if (result.errors.hasErrors()) {
            return NoxResult.Error(NoxError.CompilationError, result.errors.formatAll(), emptyList())
        }
        val compiled =
            result.compiledProgram
                ?: return NoxResult.Error(NoxError.CompilationError, "No compiled output", emptyList())

        val ctx =
            object : RuntimeContext {
                override fun yield(data: String) { /* collected by VM */ }

                override fun returnResult(data: String) { /* collected by VM */ }

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                    permissionHandler?.invoke(request)
                        ?: PermissionResponse.Denied("No permission handler configured")

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    resourceHandler?.invoke(request)
                        ?: ResourceResponse.Denied("No resource handler configured")
            }

        val vm = NoxVM(compiled, ctx, registry, config)
        return runBlocking { vm.execute() }
    }

    class Builder {
        private var config = ExecutionConfig()
        private var registry = LibraryRegistry.createDefault()
        private var permissionHandler: (suspend (PermissionRequest) -> PermissionResponse)? = null
        private var resourceHandler: (suspend (ResourceRequest) -> ResourceResponse)? = null

        fun maxInstructions(n: Long) = apply { config = config.copy(maxInstructions = n) }

        fun maxExecutionTime(d: Duration) = apply { config = config.copy(maxExecutionTime = d) }

        fun maxCallDepth(n: Int) = apply { config = config.copy(maxCallDepth = n) }

        fun registerModule(instance: Any) = apply { registry.registerModule(instance) }

        fun setPermissionHandler(handler: suspend (PermissionRequest) -> PermissionResponse) =
            apply { permissionHandler = handler }

        fun setResourceHandler(handler: suspend (ResourceRequest) -> ResourceResponse) =
            apply { resourceHandler = handler }

        fun build() = NoxRuntime(config, registry, permissionHandler, resourceHandler)
    }

    companion object {
        fun builder() = Builder()
    }
}
