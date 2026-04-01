package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.semantic.ResolvedModule
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

/**
 * Converts an annotated body (function, init block, or module) into a flat list of 64-bit instructions.
 *
 * The emitter is **stateful**: create one instance per function, call [emitBlock] or
 * [emitStmt], then call [build] to obtain the finished instruction list, labels, and
 * source-line mapping.
 *
 * See docs/compiler/codegen.md for the full design.
 */
class BytecodeEmitter(
    internal val allocator: RegisterAllocator,
    internal val pool: ConstantPool,
    internal val program: Program,
    internal val modules: List<ResolvedModule> = emptyList(),
    private val freeAtNode: Map<Any, List<Symbol>> = emptyMap(),
    private val registry: LibraryRegistry = LibraryRegistry.createDefault(),
) {
    // Sub-emitters

    private val expressions = ExpressionEmitter(this)
    private val statements = StatementEmitter(this, registry)

    // Instruction buffer

    private val instructions = mutableListOf<Long>()

    /** Source-line for each instruction (parallel to [instructions]). `-1` = no annotation. */
    val sourceLines = mutableListOf<Int>()

    /**
     * Timeline of register↔variable name assignments, sorted by instruction index.
     * Built during emission and passed to [FuncMeta] for use by the disassembler.
     */
    val regNameEvents = mutableListOf<RegNameEvent>()

    /** Labels: maps instruction index to label name, used by [NoxcEmitter]. */
    val labels = mutableMapOf<Int, String>()

    /** Label counter for disambiguation (used by [StatementEmitter] for loop labels). */
    internal var labelSeq = 0

    /** Exception entries collected during emission. */
    internal val exceptionEntries = mutableListOf<ExEntry>()

    /** Current program counter (index of next instruction to emit). */
    val pc: Int get() = instructions.size

    /**
     * Records parameter name events at localPC=0. Must be called before emitting
     * any instructions so that parameter names are available from the start.
     */
    fun recordParamNames(params: List<Param>) {
        for (param in params) {
            val isPrim = param.type.isPrimitive()
            val reg =
                if (isPrim) {
                    allocator.primParamRegister(param.name)
                } else {
                    allocator.refParamRegister(param.name)
                }
            if (reg != null) {
                regNameEvents.add(RegNameEvent(0, isPrim, reg, param.name))
            }
        }
    }

    /**
     * Emit all statements in [block], then emit [Opcode.KILL_REF] for
     * every rMem register whose first definition was in this block
     * **unless** the block exits via a `return` statement, in which case
     * [StatementEmitter.emitReturn] has already emitted the KILL_REFs before the [Opcode.RET].
     */
    fun emitBlock(
        block: Block,
        srcLine: Int = -1,
    ) {
        val refsBeforeBlock = mutableSetOf<Int>()
        // snapshot which ref regs existed before this block
        refsBeforeBlock.addAll(allocator.allRefRegs)

        for (stmt in block.statements) {
            statements.emitStmt(stmt)
        }

        // Skip KILL_REF emission if the block exits via return, already emitted
        // by emitReturn before the RET instruction.
        val lastStmt = block.statements.lastOrNull()
        if (lastStmt is ReturnStmt) return

        // Emit KILL_REF for any rMem reg that was first allocated inside this block
        for (reg in allocator.allRefRegs) {
            if (reg !in refsBeforeBlock) {
                emit(Opcode.KILL_REF, 0, reg, 0, 0)
            }
        }
    }

    /** Emit a single statement (delegates to [StatementEmitter]). */
    fun emitStmt(stmt: Stmt) = statements.emitStmt(stmt)

    /** Emit an expression into [dest] register (delegates to [ExpressionEmitter]). */
    fun emitExpr(
        expr: Expr,
        dest: Int,
        srcLine: Int = expr.loc.line,
    ) = expressions.emitExpr(expr, dest, srcLine)

    /** Finalise and return the built instructions. */
    fun build(): List<Long> = instructions.toList()

    /** Returns the built exception table after all emit calls. */
    fun buildExceptionTable(): List<ExEntry> = exceptionEntries.toList()

    // Instruction emission

    /** Emit one instruction; returns its PC (index). */
    fun emit(
        opcode: Int,
        subOp: Int,
        a: Int,
        b: Int,
        c: Int,
        line: Int = -1,
    ): Int {
        val instrPc = instructions.size
        instructions.add(Instruction.encode(opcode, subOp, a, b, c))
        sourceLines.add(line)
        return instrPc
    }

    /**
     * Patch the jump target of an already-emitted instruction at [instrPc].
     * Opcode-aware: JMP targets field A, JIF/JIT targets field B.
     */
    fun patch(
        instrPc: Int,
        newTarget: Int,
    ) {
        val inst = instructions[instrPc]
        val opcode = Instruction.opcode(inst)
        instructions[instrPc] =
            if (opcode == Opcode.JMP) {
                Instruction.patchA(inst, newTarget)
            } else {
                Instruction.patchB(inst, newTarget)
            }
    }

    /** Add a label at the current PC. */
    internal fun addLabel(name: String) {
        labels[pc] = name
    }

    // Register allocation helpers

    internal fun alloc(type: TypeRef): Int = allocator.allocTemp(type)

    internal fun free(
        type: TypeRef,
        reg: Int,
    ) = allocator.freeTemp(type, reg)

    internal fun allocp(): Int = allocator.allocTempPrim()

    internal fun freep(r: Int) = allocator.freeTempPrim(r)

    internal fun allocr(): Int = allocator.allocTempRef()

    internal fun freer(r: Int) = allocator.freeTempRef(r)

    // Resolution helpers

    /**
     * Returns the local/param register backing [expr], or `null` if not a simple reference.
     * This is used for direct-register operations like `IINC`, compound assign, etc.
     */
    internal fun resolveRegister(expr: Expr): Int? =
        when (expr) {
            is IdentifierExpr ->
                when (val sym = expr.resolvedSymbol) {
                    is VarSymbol -> sym.register.takeIf { it >= 0 }
                    is ParamSymbol -> sym.register.takeIf { it >= 0 }
                    is GlobalSymbol -> GLOBAL_FLAG or sym.globalSlot
                    else -> null
                }

            else -> null
        }

    companion object {
        /** Bit 15 flag indicating a global memory operand. */
        const val GLOBAL_FLAG = 0x8000
    }

    internal fun freeNodeRegisters(node: Any) {
        freeAtNode[node]?.forEach { sym ->
            allocator.freeVar(sym)
        }
    }

    // Type descriptor building

    internal fun buildDescriptor(
        typeName: String,
        visited: MutableMap<String, Int> = mutableMapOf(),
    ): Int {
        visited[typeName]?.let { return it }
        pool.getTypeDescriptorId(typeName)?.let { return it }

        // Find the TypeDef (local or imported)
        val typeDef =
            program.typesByName[typeName]
                ?: modules.firstNotNullOfOrNull { it.program.typesByName[typeName] }
                ?: throw IllegalStateException("Type not found: $typeName")

        // Reserve slot
        val placeholder = pool.addPlaceholder()
        visited[typeName] = placeholder

        val fields = LinkedHashMap<String, FieldSpec>()
        for (field in typeDef.fields) {
            fields[field.name] = FieldSpec.from(field.type) { nestedName -> buildDescriptor(nestedName, visited) }
        }

        pool.replace(placeholder, TypeDescriptor(typeName, fields))
        return placeholder
    }
}
