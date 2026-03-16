package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.semantic.ResolvedModule
import nox.compiler.types.*

/**
 * Orchestrates register allocation and bytecode emission for an entire Nox program.
 *
 * See docs/compiler/codegen.md for the full design.
 */
class CodeGenerator(
    private val modules: List<ResolvedModule> = emptyList(),
) {
    // Builder state
    private val pool = ConstantPool()
    private val bytecode = mutableListOf<Long>()
    private val allSrcLines = mutableListOf<Int>()
    private val exTable = mutableListOf<ExEntry>()
    private val funcMetas = mutableListOf<FuncMeta>()
    private val modMetas = mutableListOf<ModuleMeta>()

    /**
     * Generates a [CompiledProgram] from the given [program]. Orchestrates the entire code generation process.
     *
     * @param program The program to generate a compiled version of.
     * @return The compiled program.
     */
    fun generate(program: Program): CompiledProgram {
        // Assign global slots for root module globals
        assignGlobalSlots(program)

        // Total global slots across all modules
        var totalPrim = program.globals.count { it.type.isPrimitive() }
        var totalRef = program.globals.count { !it.type.isPrimitive() }
        for (mod in modules) {
            totalPrim += mod.program.globals.count { it.type.isPrimitive() }
            totalRef += mod.program.globals.count { !it.type.isPrimitive() }
        }

        // Emit imported modules (depth-first order already maintained by ImportResolver)
        for (mod in modules) {
            val initFuncIdx = emitModuleInit(mod.program, mod.namespace, mod.sourcePath)
            val exportedFuncIndices = mutableListOf<Int>()

            // TODO: We should track which functions are actually called and skip unused functions
            for (func in mod.program.functionsByName.values) {
                exportedFuncIndices.add(emitFunction(mod.program, func, mod.sourcePath))
            }
            modMetas.add(
                ModuleMeta(
                    namespace = mod.namespace,
                    sourcePath = mod.sourcePath,
                    globalBaseOffset = mod.globalBaseOffset,
                    globalPrimitiveCount = mod.program.globals.count { it.type.isPrimitive() },
                    globalReferenceCount = mod.program.globals.count { !it.type.isPrimitive() },
                    exportedFunctions = exportedFuncIndices,
                    exportedTypes =
                        mod.program.typesByName.keys
                            .toList(),
                    initFuncIndex = initFuncIdx,
                ),
            )
        }

        // Emit root module
        val rootInitIdx = emitModuleInit(program, "main", program.fileName)
        val rootFuncIndices = mutableListOf<Int>()
        for (func in program.functionsByName.values) {
            rootFuncIndices.add(emitFunction(program, func, program.fileName))
        }
        val mainIndex = program.main?.let { emitMain(program, it, program.fileName) } ?: -1

        modMetas.add(
            ModuleMeta(
                namespace = "main",
                sourcePath = program.fileName,
                globalBaseOffset = 0,
                globalPrimitiveCount = program.globals.count { it.type.isPrimitive() },
                globalReferenceCount = program.globals.count { !it.type.isPrimitive() },
                exportedFunctions = rootFuncIndices,
                exportedTypes = program.typesByName.keys.toList(),
                initFuncIndex = rootInitIdx,
            ),
        )

        return CompiledProgram(
            bytecode = bytecode.toLongArray(),
            constantPool = pool.toArray(),
            exceptionTable = exTable.toTypedArray(),
            functions = funcMetas.toTypedArray(),
            modules = modMetas.toTypedArray(),
            mainFuncIndex = mainIndex,
            totalGlobalPrimitiveSlots = totalPrim,
            totalGlobalReferenceSlots = totalRef,
        )
    }

    private fun assignGlobalSlots(program: Program) {
        var primSlot = 0
        var refSlot = 0
        for (global in program.globals) {
            if (global.globalSlot >= 0) continue // already assigned (imported module)
            if (global.type.isPrimitive()) {
                global.globalSlot = primSlot++
            } else {
                global.globalSlot = refSlot++
            }
        }
    }

    private fun emitModuleInit(
        program: Program,
        ns: String,
        sourcePath: String = "",
    ): Int {
        val needsInit = program.globals.any { it.initializer != null }
        if (!needsInit) return -1

        val entryPc = bytecode.size
        val allocator = RegisterAllocator(emptyList())
        val emitter = BytecodeEmitter(allocator, pool, program, modules)

        for (global in program.globals) {
            val init = global.initializer ?: continue
            val line = global.loc.line
            if (global.type.isPrimitive()) {
                val tmp = allocator.allocTempPrim()
                emitter.emitExpr(init, tmp, line)
                emitter.emit(Opcode.GSTORE, 0, global.globalSlot, tmp, 0, line)
                allocator.freeTempPrim(tmp)
            } else {
                val tmp = allocator.allocTempRef()
                emitter.emitExpr(init, tmp, line)
                emitter.emit(Opcode.GSTORER, 0, global.globalSlot, tmp, 0, line)
                allocator.freeTempRef(tmp)
            }
        }
        emitter.emit(Opcode.RET, 0, 0, 0, 0)

        appendEmitter(entryPc, emitter)

        // Build "gN=name (type)" / "grN=name (ref)" labels for the disassembler
        val globalVarNames =
            program.globals.map { g ->
                val prefix = if (g.type.isPrimitive()) "g" else "gr"
                val typeLabel = if (g.type.isPrimitive()) g.type.name else "ref"
                "$prefix${g.globalSlot}=${g.name} ($typeLabel)"
            }

        val name = if (ns == "main") "<module_init>" else "<module_init:$ns>"
        val metaIdx = funcMetas.size
        funcMetas.add(
            FuncMeta(
                name = name,
                entryPC = entryPc,
                paramCount = 0,
                primitiveFrameSize = allocator.maxPrim,
                referenceFrameSize = allocator.maxRef,
                sourceLines = emitter.sourceLines.toIntArray(),
                labels = emitter.labels.toMap(),
                regNameEvents = emitter.regNameEvents.toList(),
                sourcePath = sourcePath,
                globalVarNames = globalVarNames,
            ),
        )
        return metaIdx
    }

    private fun emitFunction(
        program: Program,
        func: FuncDef,
        sourcePath: String = "",
    ): Int {
        val liveness = LivenessAnalyzer().also { it.analyze(func) }
        return emitFunctionBody(
            name = func.name,
            params = func.params,
            body = func.body,
            program = program,
            sourcePath = sourcePath,
            liveness = liveness,
            implicitVoidReturn = func.returnType == TypeRef.VOID,
        ).also { _ ->
            func.maxPrimitiveRegisters = funcMetas.last().primitiveFrameSize
            func.maxReferenceRegisters = funcMetas.last().referenceFrameSize
        }
    }

    private fun emitMain(
        program: Program,
        main: MainDef,
        sourcePath: String = "",
    ): Int {
        val liveness = LivenessAnalyzer().also { it.analyze(main) }
        return emitFunctionBody(
            name = "main",
            params = main.params,
            body = main.body,
            program = program,
            sourcePath = sourcePath,
            liveness = liveness,
            implicitVoidReturn = false,
        ).also { _ ->
            main.maxPrimitiveRegisters = funcMetas.last().primitiveFrameSize
            main.maxReferenceRegisters = funcMetas.last().referenceFrameSize
        }
    }

    /**
     * Shared implementation for [emitFunction] and [emitMain].
     *
     * Handles liveness-driven register allocation, bytecode emission, and [FuncMeta] recording.
     * The only differences between a named function and `main` are the [name] string, whether
     * an [implicitVoidReturn] is appended, and who reads back [maxPrimitiveRegisters].
     */
    private fun emitFunctionBody(
        name: String,
        params: List<Param>,
        body: Block,
        program: Program,
        sourcePath: String,
        liveness: LivenessAnalyzer,
        implicitVoidReturn: Boolean,
    ): Int {
        val entryPc = bytecode.size
        val allocator = RegisterAllocator(params)
        allocator.setParamSymbols(params)

        val emitter = BytecodeEmitter(allocator, pool, program, modules, liveness.freeAtNode)
        emitter.recordParamNames(params)
        emitter.emitBlock(body)

        if (implicitVoidReturn) {
            emitter.emit(Opcode.RET, 0, 0, 0, 0)
        }

        appendEmitter(entryPc, emitter)

        val metaIdx = funcMetas.size
        funcMetas.add(
            FuncMeta(
                name = name,
                entryPC = entryPc,
                paramCount = params.size,
                primitiveFrameSize = allocator.maxPrim,
                referenceFrameSize = allocator.maxRef,
                sourceLines = emitter.sourceLines.toIntArray(),
                labels = emitter.labels.toMap(),
                regNameEvents = emitter.regNameEvents.toList(),
                sourcePath = sourcePath,
            ),
        )
        return metaIdx
    }

    private fun appendEmitter(
        entryPc: Int,
        emitter: BytecodeEmitter,
    ) {
        val emittedInstructions = emitter.build()
        bytecode.addAll(emittedInstructions)
        allSrcLines.addAll(emitter.sourceLines)

        // Adjust exception-table PC offsets to be absolute
        val base = entryPc
        for (ex in emitter.buildExceptionTable()) {
            exTable.add(
                ex.copy(
                    startPC = ex.startPC + base,
                    endPC = ex.endPC + base,
                    handlerPC = ex.handlerPC + base,
                ),
            )
        }
    }
}
