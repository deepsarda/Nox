package nox.runtime

import kotlinx.coroutines.runBlocking
import nox.compiler.NoxCompiler
import nox.plugin.LibraryRegistry
import nox.vm.ExecutionConfig
import nox.vm.NoxException
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
    private val onYieldCallback: ((String) -> Unit)? = null,
) {
    fun execute(
        source: String,
        fileName: String = "script.nox",
        args: Map<String, Any?> = emptyMap(),
        argProvider: (suspend (String, nox.compiler.types.TypeRef) -> Any?)? = null,
        basePath: java.nio.file.Path? = null,
    ): NoxResult {
        if (source.isBlank()) {
            return NoxResult.Error(NoxError.CompilationError, "Empty source", emptyList())
        }
        val result = NoxCompiler.compile(source, fileName, basePath = basePath, registry = registry)
        if (result.errors.hasErrors()) {
            return NoxResult.Error(NoxError.CompilationError, result.errors.formatAll(), emptyList())
        }
        val compiled =
            result.compiledProgram
                ?: return NoxResult.Error(NoxError.CompilationError, "No compiled output", emptyList())

        val typedMain = result.typedProgram?.main
        val (primArgs, refArgs) =
            try {
                runBlocking { prepareArgs(typedMain, args, result.typedProgram, argProvider) }
            } catch (e: NoxException) {
                return NoxResult.Error(e.type, e.message, emptyList())
            }

        val yields = mutableListOf<String>()

        val ctx =
            object : RuntimeContext {
                override fun yield(data: String) {
                    yields.add(data)
                    onYieldCallback?.invoke(data)
                }

                override fun returnResult(data: String) { /* collected by VM */ }

                override suspend fun requestPermission(request: PermissionRequest): PermissionResponse =
                    permissionHandler?.invoke(request)
                        ?: PermissionResponse.Denied("No permission handler configured")

                override suspend fun requestResourceExtension(request: ResourceRequest): ResourceResponse =
                    resourceHandler?.invoke(request)
                        ?: ResourceResponse.Denied("No resource handler configured")
            }

        val vm = NoxVM(compiled, ctx, registry, config)
        return try {
            val ret = runBlocking { vm.execute(primArgs, refArgs) }
            NoxResult.Success(ret, yields)
        } catch (e: NoxException) {
            NoxResult.Error(e.type, e.message, yields)
        }
    }

    class Builder {
        private var config = ExecutionConfig()
        private var registry = LibraryRegistry.createDefault()
        private var permissionHandler: (suspend (PermissionRequest) -> PermissionResponse)? = null
        private var resourceHandler: (suspend (ResourceRequest) -> ResourceResponse)? = null
        private var onYieldCallback: ((String) -> Unit)? = null

        fun maxInstructions(n: Long) = apply { config = config.copy(maxInstructions = n) }

        fun maxExecutionTime(d: Duration) = apply { config = config.copy(maxExecutionTime = d) }

        fun maxCallDepth(n: Int) = apply { config = config.copy(maxCallDepth = n) }

        fun withRegistry(r: LibraryRegistry) = apply { registry = r }

        fun setPermissionHandler(handler: suspend (PermissionRequest) -> PermissionResponse) =
            apply { permissionHandler = handler }

        fun setResourceHandler(handler: suspend (ResourceRequest) -> ResourceResponse) =
            apply { resourceHandler = handler }

        fun onYield(callback: (String) -> Unit) = apply { onYieldCallback = callback }

        fun build() = NoxRuntime(config, registry, permissionHandler, resourceHandler, onYieldCallback)
    }

    companion object {
        fun builder() = Builder()

        /** Helper to map host arguments to VM registers using the program's main signature. */
        suspend fun prepareArgs(
            typedMain: nox.compiler.ast.typed.TypedMainDef?,
            args: Map<String, Any?>,
            program: nox.compiler.ast.typed.TypedProgram?,
            argProvider: (suspend (String, nox.compiler.types.TypeRef) -> Any?)? = null,
        ): Pair<LongArray, Array<Any?>> {
            if (typedMain == null || typedMain.params.isEmpty()) {
                return Pair(LongArray(0), emptyArray())
            }

            val primCount = typedMain.params.count { it.type.isPrimitive() }
            val refCount = typedMain.params.count { !it.type.isPrimitive() }

            val pOffset = if (typedMain.returnType.isPrimitive()) 1 else 0
            val rOffset =
                if (!typedMain.returnType.isPrimitive() &&
                    typedMain.returnType != nox.compiler.types.TypeRef.VOID
                ) {
                    1
                } else {
                    0
                }

            val primArgs = LongArray(pOffset + primCount)
            val refArgs = arrayOfNulls<Any?>(rOffset + refCount)

            var pIdx = pOffset
            var rIdx = rOffset

            for (param in typedMain.params) {
                var rawValue = args[param.name] ?: argProvider?.invoke(param.name, param.type)

                if (rawValue == null && param.defaultValue != null) {
                    rawValue = extractLiteralValue(param.defaultValue)
                        ?: throw NoxException(
                            NoxError.Error,
                            "Default value for '${param.name}' is not a supported literal",
                            0,
                        )
                }

                if (rawValue != null) {
                    val coerced = RuntimeTypeValidator.validateAndCoerce(rawValue, param.type, program)

                    if (param.type.isPrimitive()) {
                        primArgs[pIdx++] =
                            when (param.type) {
                                nox.compiler.types.TypeRef.DOUBLE ->
                                    java.lang.Double.doubleToRawLongBits(
                                        coerced as Double,
                                    )
                                nox.compiler.types.TypeRef.BOOLEAN -> if (coerced as Boolean) 1L else 0L
                                else -> coerced as Long
                            }
                    } else {
                        refArgs[rIdx++] = coerced
                    }
                } else {
                    throw NoxException(NoxError.Error, "Missing required argument: '${param.name}'", 0)
                }
            }
            return Pair(primArgs, refArgs)
        }

        private fun extractLiteralValue(expr: nox.compiler.ast.typed.TypedExpr): Any? =
            when (expr) {
                is nox.compiler.ast.typed.TypedIntLiteralExpr -> expr.value
                is nox.compiler.ast.typed.TypedDoubleLiteralExpr -> expr.value
                is nox.compiler.ast.typed.TypedBoolLiteralExpr -> expr.value
                is nox.compiler.ast.typed.TypedStringLiteralExpr -> expr.value
                is nox.compiler.ast.typed.TypedNullLiteralExpr -> null
                else -> null
            }
    }
}
