package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.semantic.ResolvedModule
import nox.compiler.types.*
import nox.plugin.TempRegistry

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
    private val allocator: RegisterAllocator,
    private val pool: ConstantPool,
    private val program: Program,
    private val modules: List<ResolvedModule> = emptyList(),
    private val freeAtNode: Map<Any, List<Symbol>> = emptyMap(),
) {
    // Instruction buffer
    private val instructions = mutableListOf<Long>()

    // Source-line for each instruction (parallel to [instructions]). `-1` = no annotation.
    val sourceLines = mutableListOf<Int>()

    /**
     * Timeline of register↔variable name assignments, sorted by instruction index.
     * Built during emission and passed to [FuncMeta] for use by the disassembler.
     */
    val regNameEvents = mutableListOf<RegNameEvent>()

    /**
     * Records parameter name events at localPC=0. Must be called before emitting
     * any instructions so that parameter names are available from the start.
     */
    fun recordParamNames(params: List<Param>) {
        for (param in params) {
            val isPrim = param.type.isPrimitive()
            val reg = if (isPrim) allocator.primParamRegister(param.name)
            else allocator.refParamRegister(param.name)
            if (reg != null) {
                regNameEvents.add(RegNameEvent(0, isPrim, reg, param.name))
            }
        }
    }

    // Labels: maps instruction index to label name, used by [NoxcEmitter].
    val labels = mutableMapOf<Int, String>()

    // Loop context stack (break/continue backpatching)
    private data class LoopContext(
        val loopStart: Int,
        val continueTarget: Int,           // for-loops: update PC; while/foreach: loopStart
        val breakPatches: MutableList<Int> = mutableListOf(),
        val continuePatches: MutableList<Int> = mutableListOf(),
    )

    private val loopStack = ArrayDeque<LoopContext>()

    // Label counters for disambiguation
    private var labelSeq = 0

    // Stores exceptions
    private val exceptionEntries = mutableListOf<ExEntry>()

    val pc: Int get() = instructions.size

    /** Returns the built exception table after all emit calls. */
    fun buildExceptionTable(): List<ExEntry> = exceptionEntries.toList()
    
    private fun freeNodeRegisters(node: Any) {
        freeAtNode[node]?.forEach { sym ->
            allocator.freeVar(sym)
        }
    }


    /**
     * Finalise and return the built instructions and the source-line table.
     */
    fun build(): List<Long> = instructions.toList()

    // Instruction emission primitives

    /** Emit one instruction; returns its PC (index). */
    fun emit(opcode: Int, subOp: Int, a: Int, b: Int, c: Int, line: Int = -1): Int {
        val instrPc = instructions.size
        instructions.add(Instruction.encode(opcode, subOp, a, b, c))
        sourceLines.add(line)
        return instrPc
    }

    /** Patch the B-operand of an already-emitted instruction at [instrPc]. */
    fun patch(instrPc: Int, newTarget: Int) {
        instructions[instrPc] = Instruction.patchB(instructions[instrPc], newTarget)
    }

    /** Add a label at the current PC. */
    private fun addLabel(name: String) {
        labels[pc] = name
    }

    private fun nextLabel(prefix: String): String {
        labelSeq++
        return "${prefix}_$labelSeq"
    }

    // Temp allocation helpers

    private fun alloc(type: TypeRef): Int = allocator.allocTemp(type)
    private fun free(type: TypeRef, reg: Int) = allocator.freeTemp(type, reg)
    private fun allocp(): Int = allocator.allocTempPrim()
    private fun freep(r: Int) = allocator.freeTempPrim(r)
    private fun allocr(): Int = allocator.allocTempRef()
    private fun freer(r: Int) = allocator.freeTempRef(r)

    /**
     * Emit all statements in [block], then emit [Opcode.KILL_REF] for
     * every rMem register whose first definition was in this block
     * **unless** the block exits via a `return` statement, in which case
     * [emitReturn] has already emitted the KILL_REFs before the [Opcode.RET].
     */
    fun emitBlock(block: Block, srcLine: Int = -1) {
        val refsBeforeBlock = mutableSetOf<Int>()
        // snapshot which ref regs existed before this block
        refsBeforeBlock.addAll(allocator.allRefRegs)

        for (stmt in block.statements) {
            emitStmt(stmt)
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


    fun emitStmt(stmt: Stmt) {
        when (stmt) {
            is VarDeclStmt -> emitVarDecl(stmt)
            is AssignStmt -> emitAssign(stmt)
            is IncrementStmt -> emitIncrement(stmt)
            is IfStmt -> emitIf(stmt)
            is WhileStmt -> emitWhile(stmt)
            is ForStmt -> emitFor(stmt)
            is ForEachStmt -> emitForEach(stmt)
            is ReturnStmt -> emitReturn(stmt)
            is YieldStmt -> emitYield(stmt)
            is BreakStmt -> emitBreak()
            is ContinueStmt -> emitContinue()
            is ThrowStmt -> emitThrow(stmt)
            is TryCatchStmt -> emitTryCatch(stmt)
            is ExprStmt -> {
                val type = stmt.expression.resolvedType ?: TypeRef.INT
                val tmp = alloc(type)
                emitExpr(stmt.expression, tmp)
                free(type, tmp)
            }

            is Block -> emitBlock(stmt)
            is ErrorStmt -> { /* skip */
            }
        }
        freeNodeRegisters(stmt)
    }

    fun emitExpr(expr: Expr, dest: Int, srcLine: Int = expr.loc.line) {
        when (expr) {
            is IntLiteralExpr -> emitIntLiteral(expr, dest)
            is DoubleLiteralExpr -> emitDoubleLiteral(expr, dest)
            is BoolLiteralExpr -> emit(Opcode.LDI, 0, dest, if (expr.value) 1 else 0, 0, srcLine)
            is StringLiteralExpr -> emitStringLiteral(expr, dest)
            is NullLiteralExpr -> emit(Opcode.KILL_REF, 0, dest, 0, 0, srcLine)
            is TemplateLiteralExpr -> emitTemplate(expr, dest)
            is BinaryExpr -> emitBinary(expr, dest)
            is UnaryExpr -> emitUnary(expr, dest)
            is PostfixExpr -> emitPostfix(expr, dest)
            is CastExpr -> emitCast(expr, dest)
            is IdentifierExpr -> emitLoad(expr, dest)
            is FieldAccessExpr -> emitFieldAccess(expr, dest)
            is IndexAccessExpr -> emitIndexAccess(expr, dest)
            is FuncCallExpr -> emitFuncCall(expr, dest)
            is MethodCallExpr -> emitMethodCall(expr, dest)
            is ArrayLiteralExpr -> emitArrayLiteral(expr, dest)
            is StructLiteralExpr -> emitStructLiteral(expr, dest)
            is ErrorExpr -> { /* skip */
            }
        }
        freeNodeRegisters(expr)
    }

    private fun emitIntLiteral(expr: IntLiteralExpr, dest: Int) {
        val v = expr.value
        if (v in 0..0xFFFF) {
            emit(Opcode.LDI, 0, dest, v.toInt(), 0, expr.loc.line)
        } else {
            val idx = pool.add(v)
            emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
        }
    }

    private fun emitDoubleLiteral(expr: DoubleLiteralExpr, dest: Int) {
        val idx = pool.add(expr.value)
        emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
    }

    private fun emitStringLiteral(expr: StringLiteralExpr, dest: Int) {
        val idx = pool.add(expr.value)
        emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
    }


    private fun emitTemplate(expr: TemplateLiteralExpr, dest: Int) {
        val line = expr.loc.line
        // Build string by progressive concatenation into `dest` (an rMem reg).
        var first = true
        for (part in expr.parts) {
            when (part) {
                is TemplatePart.Text -> {
                    if (part.value.isEmpty()) continue
                    val idx = pool.add(part.value)
                    val tmp = allocr()
                    emit(Opcode.LDC, 0, tmp, idx, 0, line)
                    if (first) {
                        emit(Opcode.MOVR, 0, dest, tmp, 0, line)
                        first = false
                    } else {
                        emit(Opcode.SCONCAT, 0, dest, dest, tmp, line)
                    }
                    freer(tmp)
                }

                is TemplatePart.Interpolation -> {
                    val exprType = part.expression.resolvedType ?: TypeRef.STRING
                    val pResolved = resolveRegister(part.expression)
                    val tmp = if (pResolved != null) {
                        freeNodeRegisters(part.expression)
                        pResolved
                    } else {
                        val r = alloc(exprType)
                        emitExpr(part.expression, r, line)
                        r
                    }
                    // Convert to string if needed
                    val strTmp = allocr()
                    when {
                        exprType == TypeRef.INT -> emit(Opcode.I2S, 0, strTmp, tmp, 0, line)
                        exprType == TypeRef.DOUBLE -> emit(Opcode.D2S, 0, strTmp, tmp, 0, line)
                        exprType == TypeRef.BOOLEAN -> emit(Opcode.B2S, 0, strTmp, tmp, 0, line)
                        else -> emit(Opcode.MOVR, 0, strTmp, tmp, 0, line)
                    }
                    if (tmp != pResolved) free(exprType, tmp)
                    if (first) {
                        emit(Opcode.MOVR, 0, dest, strTmp, 0, line)
                        first = false
                    } else {
                        emit(Opcode.SCONCAT, 0, dest, dest, strTmp, line)
                    }
                    freer(strTmp)
                }

                is TemplatePart.ErrorPart -> { /* skip */
                }
            }
        }
        // If all parts were empty / none existed
        if (first) {
            val idx = pool.add("")
            emit(Opcode.LDC, 0, dest, idx, 0, line)
        }
    }

    private fun emitBinary(expr: BinaryExpr, dest: Int) {
        val line = expr.loc.line
        val leftType = expr.left.resolvedType ?: TypeRef.INT
        val rightType = expr.right.resolvedType ?: TypeRef.INT
        val resultType = expr.resolvedType ?: TypeRef.INT

        val lResolved = resolveRegister(expr.left)
        val rResolved = resolveRegister(expr.right)

        val lNeedsWide = leftType == TypeRef.INT && resultType == TypeRef.DOUBLE
        val rNeedsWide = rightType == TypeRef.INT && resultType == TypeRef.DOUBLE

        val lReg = if (lResolved != null && !lNeedsWide) {
            freeNodeRegisters(expr.left)
            lResolved
        } else {
            val r = alloc(leftType)
            emitExpr(expr.left, r, line)
            r
        }

        val rReg = if (rResolved != null && !rNeedsWide) {
            freeNodeRegisters(expr.right)
            rResolved
        } else if (lReg != dest && rightType == resultType) {
            emitExpr(expr.right, dest, line)
            dest
        } else {
            val r = alloc(rightType)
            emitExpr(expr.right, r, line)
            r
        }

        // Implicit widening: int operand in a double operation
        val lWide = if (lNeedsWide) {
            val w = allocp(); emit(Opcode.I2D, 0, w, lReg, 0, line); w
        } else lReg

        val rWide = if (rNeedsWide) {
            val w = allocp(); emit(Opcode.I2D, 0, w, rReg, 0, line); w
        } else rReg

        val opcode = binaryOpcode(expr.op, resultType, leftType, rightType)
        emit(opcode, 0, dest, lWide, rWide, line)

        if (lWide != lReg) freep(lWide)
        if (rWide != rReg) freep(rWide)
        if (lReg != lResolved) free(leftType, lReg)
        if (rReg != rResolved && rReg != dest) free(rightType, rReg)
    }

    private fun binaryOpcode(op: BinaryOp, result: TypeRef, left: TypeRef, right: TypeRef): Int {
        val isInt = result == TypeRef.INT || (result == TypeRef.BOOLEAN && left == TypeRef.INT)
        val isDbl = result == TypeRef.DOUBLE
        val isStr = left == TypeRef.STRING || right == TypeRef.STRING
        return when (op) {
            BinaryOp.ADD -> if (isDbl) Opcode.DADD else Opcode.IADD
            BinaryOp.SUB -> if (isDbl) Opcode.DSUB else Opcode.ISUB
            BinaryOp.MUL -> if (isDbl) Opcode.DMUL else Opcode.IMUL
            BinaryOp.DIV -> if (isDbl) Opcode.DDIV else Opcode.IDIV
            BinaryOp.MOD -> if (isDbl) Opcode.DMOD else Opcode.IMOD
            BinaryOp.EQ -> when {
                left == TypeRef.DOUBLE || right == TypeRef.DOUBLE -> Opcode.DEQ
                left == TypeRef.STRING || right == TypeRef.STRING -> Opcode.SEQ
                left.isNullable() || right.isNullable() -> Opcode.SEQ
                else -> Opcode.IEQ
            }

            BinaryOp.NE -> when {
                left == TypeRef.DOUBLE || right == TypeRef.DOUBLE -> Opcode.DNE
                left == TypeRef.STRING || right == TypeRef.STRING -> Opcode.SNE
                left.isNullable() || right.isNullable() -> Opcode.SNE
                else -> Opcode.INE
            }

            BinaryOp.LT -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DLT else Opcode.ILT
            BinaryOp.LE -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DLE else Opcode.ILE
            BinaryOp.GT -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DGT else Opcode.IGT
            BinaryOp.GE -> if (left == TypeRef.DOUBLE || right == TypeRef.DOUBLE) Opcode.DGE else Opcode.IGE
            BinaryOp.AND -> Opcode.AND
            BinaryOp.OR -> Opcode.OR
            BinaryOp.BIT_AND -> Opcode.BAND
            BinaryOp.BIT_OR -> Opcode.BOR
            BinaryOp.BIT_XOR -> Opcode.BXOR
            BinaryOp.SHL -> Opcode.SHL
            BinaryOp.SHR -> Opcode.SHR
            BinaryOp.USHR -> Opcode.USHR
        }
    }

    private fun emitUnary(expr: UnaryExpr, dest: Int) {
        val line = expr.loc.line
        val operandType = expr.operand.resolvedType ?: TypeRef.INT

        val opResolved = resolveRegister(expr.operand)
        val opReg = if (opResolved != null) {
            freeNodeRegisters(expr.operand)
            opResolved
        } else {
            val r = alloc(operandType)
            emitExpr(expr.operand, r, line)
            r
        }

        when (expr.op) {
            UnaryOp.NEG -> {
                if (operandType == TypeRef.DOUBLE) emit(Opcode.DNEG, 0, dest, opReg, 0, line)
                else emit(Opcode.INEG, 0, dest, opReg, 0, line)
            }

            UnaryOp.NOT -> emit(Opcode.NOT, 0, dest, opReg, 0, line)
            UnaryOp.BIT_NOT -> emit(Opcode.BNOT, 0, dest, opReg, 0, line)
        }
        if (opReg != opResolved) free(operandType, opReg)
    }

    private fun emitPostfix(expr: PostfixExpr, dest: Int) {
        val line = expr.loc.line
        val reg = resolveRegister(expr.operand) ?: return
        val type = expr.operand.resolvedType ?: TypeRef.INT
        // In expression context, copy current value to dest first
        if (type.isPrimitive()) emit(Opcode.MOV, 0, dest, reg, 0, line)
        else emit(Opcode.MOVR, 0, dest, reg, 0, line)
        val opcode = when (expr.op) {
            PostfixOp.INCREMENT -> if (type == TypeRef.DOUBLE) Opcode.DINC else Opcode.IINC
            PostfixOp.DECREMENT -> if (type == TypeRef.DOUBLE) Opcode.DDEC else Opcode.IDEC
        }
        emit(opcode, 0, reg, 0, 0, line)
    }

    private fun emitCast(expr: CastExpr, dest: Int) {
        val line = expr.loc.line
        val srcType = expr.operand.resolvedType ?: TypeRef.JSON

        val opResolved = resolveRegister(expr.operand)
        val opReg = if (opResolved != null) {
            freeNodeRegisters(expr.operand)
            opResolved
        } else {
            val r = alloc(srcType)
            emitExpr(expr.operand, r, line)
            r
        }

        // Build the type descriptor
        val subOp = if (expr.targetType.isArray) 1 else 0
        val baseName = expr.targetType.name
        val descriptorIdx = buildDescriptor(baseName)

        emit(Opcode.CAST_STRUCT, subOp, dest, opReg, descriptorIdx, line)

        if (opReg != opResolved) free(srcType, opReg)
    }

    private fun buildDescriptor(typeName: String, visited: MutableMap<String, Int> = mutableMapOf()): Int {
        visited[typeName]?.let { return it }
        pool.getTypeDescriptorId(typeName)?.let { return it }

        // Find the TypeDef (local or imported)
        val typeDef = program.typesByName[typeName]
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


    private fun emitLoad(expr: IdentifierExpr, dest: Int) {
        val line = expr.loc.line
        when (val sym = expr.resolvedSymbol) {
            is GlobalSymbol -> {
                if (sym.type.isPrimitive())
                    emit(Opcode.GLOAD, 0, dest, sym.globalSlot, 0, line)
                else
                    emit(Opcode.GLOADR, 0, dest, sym.globalSlot, 0, line)
            }

            is VarSymbol -> {
                val reg = sym.register
                if (reg < 0) return
                if (sym.type.isPrimitive()) {
                    if (dest != reg) emit(Opcode.MOV, 0, dest, reg, 0, line)
                } else {
                    if (dest != reg) emit(Opcode.MOVR, 0, dest, reg, 0, line)
                }
            }

            is ParamSymbol -> {
                val reg = sym.register
                if (reg < 0) return
                if (sym.type.isPrimitive()) {
                    if (dest != reg) emit(Opcode.MOV, 0, dest, reg, 0, line)
                } else {
                    if (dest != reg) emit(Opcode.MOVR, 0, dest, reg, 0, line)
                }
            }

            else -> {
                // TODO: this should be a warning inside the compiler
                println("Unresolved symbol: ${expr.name}")
            }
        }
    }

    private fun emitFieldAccess(expr: FieldAccessExpr, dest: Int) {
        val line = expr.loc.line
        val targetType = expr.target.resolvedType ?: TypeRef.JSON

        // Attempt AGET_PATH collapse for deep json chains
        if (targetType == TypeRef.JSON || targetType.isStructType()) {
            val path = collectFieldPath(expr)
            if (path != null && path.second.contains('.')) {
                // Deep path: AGET_PATH
                val tResolved = resolveRegister(path.first)
                val tReg = if (tResolved != null) {
                    freeNodeRegisters(path.first)
                    tResolved
                } else {
                    val r = allocr()
                    emitExpr(path.first, r, line)
                    r
                }

                val pathIdx = pool.add(path.second)
                emit(Opcode.AGET_PATH, 0, dest, tReg, pathIdx, line)
                if (tReg != tResolved) freer(tReg)
                return
            }
        }

        
        if (targetType == TypeRef.JSON || targetType.isStructType()) {
            val tResolved = resolveRegister(expr.target)
            val tReg = if (tResolved != null) {
                freeNodeRegisters(expr.target)
                tResolved
            } else {
                val r = allocr()
                emitExpr(expr.target, r, line)
                r
            }
            val fieldType = expr.resolvedType ?: TypeRef.STRING
            val subOp = when {
                fieldType == TypeRef.INT -> SubOp.GET_INT
                fieldType == TypeRef.DOUBLE -> SubOp.GET_DBL
                fieldType == TypeRef.BOOLEAN -> SubOp.GET_BOOL
                fieldType == TypeRef.STRING -> SubOp.GET_STR
                else -> SubOp.GET_OBJ
            }
            val keyIdx = pool.add(expr.fieldName)
            emit(Opcode.HACC, subOp, dest, tReg, keyIdx, line)
            if (tReg != tResolved) freer(tReg)
        }
    }

    /** Returns (root, "a.b.c") if [expr] is a pure json field chain, else null. */
    private fun collectFieldPath(expr: FieldAccessExpr): Pair<Expr, String>? {
        val parts = mutableListOf(expr.fieldName)
        var cur: Expr = expr.target
        while (cur is FieldAccessExpr) {
            val t = cur.target.resolvedType ?: return null
            if (t != TypeRef.JSON && !t.isStructType()) return null
            parts.add(0, cur.fieldName)
            cur = cur.target
        }
        if (parts.size < 2) return null   // single field: use HACC, not AGET_PATH
        return cur to parts.joinToString(".")
    }

    private fun emitIndexAccess(expr: IndexAccessExpr, dest: Int) {
        val line = expr.loc.line

        val targetType = expr.target.resolvedType ?: TypeRef.JSON
        val tResolved = resolveRegister(expr.target)
        val tReg = if (tResolved != null) {
            freeNodeRegisters(expr.target)
            tResolved
        } else {
            val r = alloc(targetType)
            emitExpr(expr.target, r, line)
            r
        }

        val idxType = expr.index.resolvedType ?: TypeRef.INT
        val iResolved = resolveRegister(expr.index)
        val iReg = if (iResolved != null) {
            freeNodeRegisters(expr.index)
            iResolved
        } else {
            val r = alloc(idxType)
            emitExpr(expr.index, r, line)
            r
        }

        emit(Opcode.AGET_IDX, 0, dest, tReg, iReg, line)
        if (iReg != iResolved) free(idxType, iReg)
        if (tReg != tResolved) free(targetType, tReg)
    }

    private fun emitFuncCall(expr: FuncCallExpr, dest: Int) {
        val line = expr.loc.line
        val funcDef = expr.resolvedFunction ?: return
        val argStart = allocator.allocTempPrim()  // will track frame start slot
        emitArgs(expr.args, funcDef.params, argStart, line)
        val funcIdx = pool.add(funcDef.name)
        emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)
        // result is left in argStart register by the callee's RET
        val retType = funcDef.returnType
        if (retType != TypeRef.VOID) {
            if (retType.isPrimitive()) {
                if (dest != argStart) emit(Opcode.MOV, 0, dest, argStart, 0, line)
            } else {
                if (dest != argStart) emit(Opcode.MOVR, 0, dest, argStart, 0, line)
            }
        }
        allocator.freeTempPrim(argStart)
    }

    private fun emitArgs(args: List<Expr>, params: List<Param>, argStart: Int, line: Int) {
        for ((i, arg) in args.withIndex()) {
            val param = params.getOrNull(i) ?: continue
            val argReg = argStart + i
            emitExpr(arg, argReg, line)
        }
    }

    private fun emitMethodCall(expr: MethodCallExpr, dest: Int) {
        val line = expr.loc.line
        when (expr.resolution) {
            MethodCallExpr.Resolution.NAMESPACE -> emitNamespaceCall(expr, dest, line)
            MethodCallExpr.Resolution.TYPE_BOUND -> emitTypeBoundCall(expr, dest, line)
            MethodCallExpr.Resolution.UFCS -> emitUfcsCall(expr, dest, line)
            null -> {
                // TODO: this should be a warning inside the compiler
                println("Unresolved method call: ${expr.methodName}")
            }
        }
    }

    private fun emitNamespaceCall(expr: MethodCallExpr, dest: Int, line: Int) {
        val target = expr.resolvedTarget ?: return
        // check if this is an import namespace (user function) or builtin (SCALL)
        val isImport = modules.any { m -> m.program.functionsByName.containsKey(target.name) }
        if (isImport) {
            // CALL (user-defined function in imported module)
            val argStart = allocator.allocTempPrim()
            for ((i, arg) in expr.args.withIndex()) {
                emitExpr(arg, argStart + i, line)
            }
            val funcIdx = pool.add(target.name)
            emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)
            if (dest != argStart) {
                if ((expr.resolvedType ?: TypeRef.INT).isPrimitive())
                    emit(Opcode.MOV, 0, dest, argStart, 0, line)
                else
                    emit(Opcode.MOVR, 0, dest, argStart, 0, line)
            }
            allocator.freeTempPrim(argStart)
        } else {
            // SCALL (native function)
            val argStart = allocator.allocTempPrim()
            for ((i, arg) in expr.args.withIndex()) {
                emitExpr(arg, argStart + i, line)
            }
            val funcIdx = pool.add(target.name)
            emit(Opcode.SCALL, 0, dest, funcIdx, argStart, line)
            allocator.freeTempPrim(argStart)
        }
    }

    private fun emitTypeBoundCall(expr: MethodCallExpr, dest: Int, line: Int) {
        val target = expr.resolvedTarget ?: return
        val argStart = allocator.allocTempPrim()
        emitExpr(expr.target, argStart, line)
        for ((i, arg) in expr.args.withIndex()) emitExpr(arg, argStart + i + 1, line)
        val funcIdx = pool.add(target.name)
        emit(Opcode.SCALL, 0, dest, funcIdx, argStart, line)
        allocator.freeTempPrim(argStart)
    }

    private fun emitUfcsCall(expr: MethodCallExpr, dest: Int, line: Int) {
        val target = expr.resolvedTarget ?: return
        val argStart = allocator.allocTempPrim()

        // Prepend receiver as first argument
        emitExpr(expr.target, argStart, line)
        for ((i, arg) in expr.args.withIndex()) {
            emitExpr(arg, argStart + i + 1, line)
        }

        val funcIdx = pool.add(target.name)
        emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)

        val retType = expr.resolvedType ?: TypeRef.VOID
        if (retType != TypeRef.VOID && dest != argStart) {
            if (retType.isPrimitive()) emit(Opcode.MOV, 0, dest, argStart, 0, line)
            else emit(Opcode.MOVR, 0, dest, argStart, 0, line)
        }

        allocator.freeTempPrim(argStart)
    }

    private fun emitArrayLiteral(expr: ArrayLiteralExpr, dest: Int) {
        val line = expr.loc.line

        emit(Opcode.NEW_ARRAY, 0, dest, 0, 0, line)

        val elemType = expr.elementType ?: TypeRef.JSON

        for (elem in expr.elements) {
            val elemResolvedType = elem.resolvedType
            // Widen int to double if the array element type requires it
            if (elemType == TypeRef.DOUBLE && elemResolvedType == TypeRef.INT) {
                val eResolved = resolveRegister(elem)
                val intTmp = if (eResolved != null) {
                    freeNodeRegisters(elem)
                    eResolved
                } else {
                    val r = allocator.allocTempPrim()
                    emitExpr(elem, r, line)
                    r
                }

                val dblTmp = allocator.allocTempPrim()
                emit(Opcode.I2D, 0, dblTmp, intTmp, 0, line)
                emit(Opcode.ARR_PUSH, 0, dest, dblTmp, 0, line)

                allocator.freeTempPrim(dblTmp)
                if (intTmp != eResolved) allocator.freeTempPrim(intTmp)

            } else {
                val eResolved = resolveRegister(elem)

                val tmp = if (eResolved != null) {
                    freeNodeRegisters(elem)
                    eResolved
                } else {
                    val r = alloc(elemType)
                    emitExpr(elem, r, line)
                    r
                }

                emit(Opcode.ARR_PUSH, 0, dest, tmp, 0, line)

                if (tmp != eResolved) free(elemType, tmp)
            }
        }
    }

    private fun emitStructLiteral(expr: StructLiteralExpr, dest: Int) {
        val line = expr.loc.line
        emit(Opcode.NEW_OBJ, 0, dest, 0, 0, line)

        for (field in expr.fields) {
            val fieldType = field.value.resolvedType ?: TypeRef.JSON
            val fResolved = resolveRegister(field.value)

            val tmp = if (fResolved != null) {
                freeNodeRegisters(field.value)
                fResolved
            } else {
                val r = alloc(fieldType)
                emitExpr(field.value, r, line)
                r
            }

            val keyIdx = pool.add(field.name)
            emit(Opcode.OBJ_SET, 0, dest, keyIdx, tmp, line)

            if (tmp != fResolved) free(fieldType, tmp)
        }
    }

    private fun emitVarDecl(stmt: VarDeclStmt) {
        val line = stmt.loc.line
        val reg = allocator.allocVar(stmt)
        regNameEvents.add(RegNameEvent(pc, stmt.type.isPrimitive(), reg, stmt.name))
        emitExpr(stmt.initializer, reg, line)
    }

    private fun emitAssign(stmt: AssignStmt) {
        val line = stmt.loc.line
        when (stmt.op) {
            AssignOp.ASSIGN -> emitSimpleAssign(stmt, line)
            AssignOp.ADD_ASSIGN,
            AssignOp.SUB_ASSIGN,
            AssignOp.MUL_ASSIGN,
            AssignOp.DIV_ASSIGN,
            AssignOp.MOD_ASSIGN -> emitCompoundAssign(stmt, line)
        }
    }

    private fun emitSimpleAssign(stmt: AssignStmt, line: Int) {
        val targetType = stmt.value.resolvedType ?: TypeRef.INT
        when (val target = stmt.target) {
            is IdentifierExpr -> {
                when (val sym = target.resolvedSymbol) {
                    is GlobalSymbol -> {
                        val valResolved = resolveRegister(stmt.value)
                        val valReg = if (valResolved != null) {
                            freeNodeRegisters(stmt.value)
                            valResolved
                        } else {
                            val r = alloc(targetType)
                            emitExpr(stmt.value, r, line)
                            r
                        }
                        if (sym.type.isPrimitive()) emit(Opcode.GSTORE, 0, sym.globalSlot, valReg, 0, line)
                        else emit(Opcode.GSTORER, 0, sym.globalSlot, valReg, 0, line)
                        if (valReg != valResolved) free(targetType, valReg)
                    }

                    else -> {
                        val reg = resolveRegister(target) ?: return
                        emitExpr(stmt.value, reg, line)
                    }
                }
            }

            is FieldAccessExpr -> {
                val objType = target.target.resolvedType ?: TypeRef.JSON
                val objReg = alloc(objType)
                emitExpr(target.target, objReg, line)

                val valType = stmt.value.resolvedType ?: TypeRef.JSON
                val valResolved = resolveRegister(stmt.value)
                val valReg = if (valResolved != null) {
                    freeNodeRegisters(stmt.value)
                    valResolved
                } else {
                    val r = alloc(valType)
                    emitExpr(stmt.value, r, line)
                    r
                }

                val keyIdx = pool.add(target.fieldName)
                val subOp = when {
                    valType == TypeRef.INT -> SubOp.SET_INT
                    valType == TypeRef.DOUBLE -> SubOp.SET_DBL
                    valType == TypeRef.BOOLEAN -> SubOp.SET_BOOL
                    valType == TypeRef.STRING -> SubOp.SET_STR
                    else -> SubOp.SET_OBJ
                }
                emit(Opcode.HMOD, subOp, objReg, keyIdx, valReg, line)
                if (valReg != valResolved) free(valType, valReg)
                free(objType, objReg)
            }

            is IndexAccessExpr -> {
                val targetType2 = target.target.resolvedType ?: TypeRef.JSON
                val tReg = alloc(targetType2)
                emitExpr(target.target, tReg, line)
                val idxType = target.index.resolvedType ?: TypeRef.INT
                val iReg = alloc(idxType)
                emitExpr(target.index, iReg, line)

                val valType = stmt.value.resolvedType ?: TypeRef.JSON
                val valResolved = resolveRegister(stmt.value)
                val valReg = if (valResolved != null) {
                    freeNodeRegisters(stmt.value)
                    valResolved
                } else {
                    val r = alloc(valType)
                    emitExpr(stmt.value, r, line)
                    r
                }

                emit(Opcode.ASET_IDX, 0, tReg, iReg, valReg, line)
                if (valReg != valResolved) free(valType, valReg)
                free(idxType, iReg)
                free(targetType2, tReg)
            }

            else -> { /* unsupported lvalue */
            }
        }
    }

    private fun emitCompoundAssign(stmt: AssignStmt, line: Int) {
        val targetType = stmt.target.resolvedType ?: TypeRef.INT
        val reg = resolveRegister(stmt.target) ?: return
        val valType = stmt.value.resolvedType ?: TypeRef.INT

        val valResolved = resolveRegister(stmt.value)
        val valReg = if (valResolved != null) {
            freeNodeRegisters(stmt.value)
            valResolved
        } else {
            val r = alloc(valType)
            emitExpr(stmt.value, r, line)
            r
        }

        // Optimization: += to IINCN, -= to IDECN
        if (stmt.op == AssignOp.ADD_ASSIGN && targetType == TypeRef.INT) {
            emit(Opcode.IINCN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.SUB_ASSIGN && targetType == TypeRef.INT) {
            emit(Opcode.IDECN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.ADD_ASSIGN && targetType == TypeRef.DOUBLE) {
            emit(Opcode.DINCN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.SUB_ASSIGN && targetType == TypeRef.DOUBLE) {
            emit(Opcode.DDECN, 0, reg, valReg, 0, line)
        } else {
            val opcode = when (stmt.op) {
                AssignOp.ADD_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DADD else Opcode.IADD
                AssignOp.SUB_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DSUB else Opcode.ISUB
                AssignOp.MUL_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DMUL else Opcode.IMUL
                AssignOp.DIV_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DDIV else Opcode.IDIV
                AssignOp.MOD_ASSIGN -> if (targetType == TypeRef.DOUBLE) Opcode.DMOD else Opcode.IMOD
                else -> Opcode.IADD
            }
            emit(opcode, 0, reg, reg, valReg, line)
        }
        if (valReg != valResolved) free(valType, valReg)
    }

    private fun emitIncrement(stmt: IncrementStmt) {
        val line = stmt.loc.line
        val reg = resolveRegister(stmt.target) ?: return
        val type = stmt.target.resolvedType ?: TypeRef.INT
        val opcode = when (stmt.op) {
            PostfixOp.INCREMENT -> if (type == TypeRef.DOUBLE) Opcode.DINC else Opcode.IINC
            PostfixOp.DECREMENT -> if (type == TypeRef.DOUBLE) Opcode.DDEC else Opcode.IDEC
        }
        emit(opcode, 0, reg, 0, 0, line)
    }

    private fun emitIf(stmt: IfStmt) {
        val line = stmt.loc.line

        val condResolved = resolveRegister(stmt.condition)
        val condReg = if (condResolved != null) {
            freeNodeRegisters(stmt.condition)
            condResolved
        } else {
            val r = allocp()
            emitExpr(stmt.condition, r, line)
            r
        }
        val jumpToElse = emit(Opcode.JIF, 0, condReg, 0 /*patch*/, 0, line)
        if (condReg != condResolved) freep(condReg)

        emitBlock(stmt.thenBlock)

        val jumpsToEnd = mutableListOf<Int>()
        var jumpToEnd = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)
        jumpsToEnd.add(jumpToEnd)

        patch(jumpToElse, pc)

        for (elseIf in stmt.elseIfs) {
            val eiCondResolved = resolveRegister(elseIf.condition)
            val eiCondReg = if (eiCondResolved != null) {
                freeNodeRegisters(elseIf.condition)
                eiCondResolved
            } else {
                val r = allocp()
                emitExpr(elseIf.condition, r, elseIf.loc.line)
                r
            }
            val jumpNext = emit(Opcode.JIF, 0, eiCondReg, 0 /*patch*/, 0, elseIf.loc.line)
            if (eiCondReg != eiCondResolved) freep(eiCondReg)

            emitBlock(elseIf.body)
            val jEnd = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)
            jumpsToEnd.add(jEnd)
            patch(jumpNext, pc)
        }

        stmt.elseBlock?.let { emitBlock(it) }

        val endPc = pc
        for (j in jumpsToEnd) patch(j, endPc)
    }

    private fun emitWhile(stmt: WhileStmt) {
        val line = stmt.loc.line
        val seq = ++labelSeq
        addLabel("loop_start_$seq")
        val loopStart = pc

        val ctx = LoopContext(loopStart, loopStart)
        loopStack.addLast(ctx)

        val condResolved = resolveRegister(stmt.condition)
        val condReg = if (condResolved != null) {
            freeNodeRegisters(stmt.condition)
            condResolved
        } else {
            val r = allocp()
            emitExpr(stmt.condition, r, line)
            r
        }
        val jumpToExit = emit(Opcode.JIF, 0, condReg, 0 /*patch*/, 0, line)
        if (condReg != condResolved) freep(condReg)

        emitBlock(stmt.body)
        emit(Opcode.JMP, 0, loopStart, 0, 0)

        addLabel("loop_exit_$seq")
        val exitPc = pc
        patch(jumpToExit, exitPc)
        ctx.breakPatches.forEach { patch(it, exitPc) }
        ctx.continuePatches.forEach { patch(it, loopStart) }
        loopStack.removeLast()
    }

    private fun emitFor(stmt: ForStmt) {
        val line = stmt.loc.line

        // Init
        stmt.init?.let { emitStmt(it) }

        val seq = ++labelSeq
        addLabel("loop_start_$seq")
        val loopStart = pc

        // Condition
        var jumpToExit: Int? = null
        if (stmt.condition != null) {
            val condResolved = resolveRegister(stmt.condition)
            val condReg = if (condResolved != null) {
                freeNodeRegisters(stmt.condition)
                condResolved
            } else {
                val r = allocp()
                emitExpr(stmt.condition, r, line)
                r
            }
            jumpToExit = emit(Opcode.JIF, 0, condReg, 0 /*patch*/, 0, line)
            if (condReg != condResolved) freep(condReg)
        }

        val ctx = LoopContext(loopStart, -1 /* set after body */)
        loopStack.addLast(ctx)

        emitBlock(stmt.body)

        // Loop update target (continue jumps here)
        addLabel("loop_update_$seq")
        val updatePc = pc
        ctx.continuePatches.forEach { patch(it, updatePc) }

        stmt.update?.let { emitStmt(it) }
        emit(Opcode.JMP, 0, loopStart, 0, 0)

        addLabel("loop_exit_$seq")
        val exitPc = pc
        jumpToExit?.let { patch(it, exitPc) }
        ctx.breakPatches.forEach { patch(it, exitPc) }
        loopStack.removeLast()
    }

    private fun emitForEach(stmt: ForEachStmt) {
        val line = stmt.loc.line
        val iterType = stmt.iterable.resolvedType ?: TypeRef.JSON

        val arrReg = alloc(iterType)
        emitExpr(stmt.iterable, arrReg, line)

        val idxReg = allocp()
        val lenReg = allocp()
        val condReg = allocp()
        val elemReg = allocator.allocElement(stmt)
        regNameEvents.add(RegNameEvent(pc, stmt.elementType.isPrimitive(), elemReg, stmt.elementName))
        emit(Opcode.LDI, 0, idxReg, 0, 0, line)     // idx = 0
        val lenTarget = TempRegistry.lookupBuiltinMethod(iterType, "length")
            ?: error("No 'length' method found on type '$iterType'")
        val lenArgStart = allocator.allocTempPrim()
        emit(Opcode.MOVR, 0, lenArgStart, arrReg, 0, line)
        val lenFuncIdx = pool.add(lenTarget.name)
        emit(Opcode.SCALL, 0, lenReg, lenFuncIdx, lenArgStart, line)
        allocator.freeTempPrim(lenArgStart)

        val seq = ++labelSeq
        addLabel("loop_start_$seq")
        val loopStart = pc

        emit(Opcode.ILT, 0, condReg, idxReg, lenReg, line) // cond = idx < len
        val jumpToExit = emit(Opcode.JIF, 0, condReg, 0 /*patch*/, 0, line)

        emit(Opcode.AGET_IDX, 0, elemReg, arrReg, idxReg, line) // elem = arr[idx]

        val ctx = LoopContext(loopStart, -1)
        loopStack.addLast(ctx)
        emitBlock(stmt.body)

        // continue target: IINC + JMP back
        addLabel("loop_update_$seq")
        val updatePc = pc
        ctx.continuePatches.forEach { patch(it, updatePc) }

        emit(Opcode.KILL_REF, 0, elemReg, 0, 0, line)
        emit(Opcode.IINC, 0, idxReg, 0, 0, line)
        emit(Opcode.JMP, 0, loopStart, 0, 0)

        addLabel("loop_exit_$seq")
        val exitPc = pc
        patch(jumpToExit, exitPc)
        ctx.breakPatches.forEach { patch(it, exitPc) }
        loopStack.removeLast()

        freep(condReg); freep(lenReg); freep(idxReg)
        free(iterType, arrReg)
    }

    private fun emitBreak() {
        val ctx = loopStack.lastOrNull() ?: return
        val jmp = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)
        ctx.breakPatches.add(jmp)
    }

    private fun emitContinue() {
        val ctx = loopStack.lastOrNull() ?: return
        if (ctx.continueTarget >= 0) {
            emit(Opcode.JMP, 0, ctx.continueTarget, 0, 0)
        } else {
            val jmp = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)
            ctx.continuePatches.add(jmp)
        }
    }

    private fun emitReturn(stmt: ReturnStmt) {
        val line = stmt.loc.line

        // Emit KILL_REF for all live rMem registers before returning.
        for (reg in allocator.allRefRegs.sorted()) {
            emit(Opcode.KILL_REF, 0, reg, 0, 0, line)
        }

        if (stmt.value == null) {
            emit(Opcode.RET, 0, 0, 0, 0, line)
        } else {
            val type = stmt.value.resolvedType ?: TypeRef.INT
            val reg = resolveRegister(stmt.value)
            if (reg != null) {
                freeNodeRegisters(stmt.value)
                emit(Opcode.RET, 0, reg, 0, 0, line)
            } else {
                val tmp = alloc(type)
                emitExpr(stmt.value, tmp, line)
                emit(Opcode.RET, 0, tmp, 0, 0, line)
                free(type, tmp)
            }
        }
    }

    private fun emitYield(stmt: YieldStmt) {
        val line = stmt.loc.line
        val type = stmt.value.resolvedType ?: TypeRef.STRING
        val reg = resolveRegister(stmt.value)
        if (reg != null) {
            freeNodeRegisters(stmt.value)
            emit(Opcode.YIELD, 0, reg, 0, 0, line)
        } else {
            val tmp = alloc(type)
            emitExpr(stmt.value, tmp, line)
            emit(Opcode.YIELD, 0, tmp, 0, 0, line)
            free(type, tmp)
        }
    }

    private fun emitThrow(stmt: ThrowStmt) {
        val line = stmt.loc.line
        val type = stmt.value.resolvedType ?: TypeRef.STRING
        val reg = resolveRegister(stmt.value)
        if (reg != null) {
            freeNodeRegisters(stmt.value)
            emit(Opcode.THROW, 0, reg, 0, 0, line)
        } else {
            val tmp = alloc(type)
            emitExpr(stmt.value, tmp, line)
            emit(Opcode.THROW, 0, tmp, 0, 0, line)
            free(type, tmp)
        }
    }

    private fun emitTryCatch(stmt: TryCatchStmt) {
        val tryStart = pc
        emitBlock(stmt.tryBlock)
        val tryEnd = pc

        val jumpPastCatches = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)

        val catchEndJumps = mutableListOf<Int>()
        for (clause in stmt.catchClauses) {
            val handlerPc = pc
            addLabel(if (clause.exceptionType != null) "catch_${clause.exceptionType}" else "catch_all")
            val msgReg = allocr()
            exceptionEntries.add(ExEntry(tryStart, tryEnd, clause.exceptionType, handlerPc, msgReg))

            emitBlock(clause.body)
            val jEnd = emit(Opcode.JMP, 0, 0 /*patch*/, 0, 0)
            catchEndJumps.add(jEnd)
            freer(msgReg)
        }

        addLabel("end")
        val afterCatches = pc
        patch(jumpPastCatches, afterCatches)
        catchEndJumps.forEach { patch(it, afterCatches) }
    }

    /**
     * Returns the local/param register backing [expr], or `null` if not a simple reference.
     * This is used for direct-register operations like `IINC`, compound assign, etc.
     */
    private fun resolveRegister(expr: Expr): Int? = when (expr) {
        is IdentifierExpr -> when (val sym = expr.resolvedSymbol) {
            is VarSymbol -> sym.register.takeIf { it >= 0 }
            is ParamSymbol -> sym.register.takeIf { it >= 0 }
            else -> null
        }

        else -> null
    }


}
