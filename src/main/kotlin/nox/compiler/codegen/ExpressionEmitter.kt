package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.types.GlobalSymbol
import nox.compiler.types.NoxParam
import nox.compiler.types.ParamSymbol
import nox.compiler.types.PostfixOp
import nox.compiler.types.TypeRef
import nox.compiler.types.UnaryOp
import nox.compiler.types.VarSymbol


/**
 * Emits bytecode for all expression AST nodes.
 *
 * Delegates infrastructure (register allocation,
 * instruction encoding, constant pool) back through [ctx].
 *
 * See docs/compiler/codegen.md for the full design.
 */
class ExpressionEmitter(
    private val ctx: BytecodeEmitter,
) {
    fun emitExpr(
        expr: Expr,
        dest: Int,
        srcLine: Int = expr.loc.line,
    ) {
        when (expr) {
            is IntLiteralExpr -> emitIntLiteral(expr, dest)
            is DoubleLiteralExpr -> emitDoubleLiteral(expr, dest)
            is BoolLiteralExpr -> ctx.emit(Opcode.LDI, 0, dest, if (expr.value) 1 else 0, 0, srcLine)
            is StringLiteralExpr -> emitStringLiteral(expr, dest)
            is NullLiteralExpr -> ctx.emit(Opcode.KILL_REF, 0, dest, 0, 0, srcLine)
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
            is ErrorExpr -> { /* skip */ }
        }
        ctx.freeNodeRegisters(expr)
    }

    // Literals

    private fun emitIntLiteral(
        expr: IntLiteralExpr,
        dest: Int,
    ) {
        val v = expr.value
        if (v in 0..0xFFFF) {
            ctx.emit(Opcode.LDI, 0, dest, v.toInt(), 0, expr.loc.line)
        } else {
            val idx = ctx.pool.add(v)
            ctx.emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
        }
    }

    private fun emitDoubleLiteral(
        expr: DoubleLiteralExpr,
        dest: Int,
    ) {
        val idx = ctx.pool.add(expr.value)
        ctx.emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
    }

    private fun emitStringLiteral(
        expr: StringLiteralExpr,
        dest: Int,
    ) {
        val idx = ctx.pool.add(expr.value)
        ctx.emit(Opcode.LDC, 0, dest, idx, 0, expr.loc.line)
    }

    // Template literals

    private fun emitTemplate(
        expr: TemplateLiteralExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        // Build string by progressive concatenation into `dest` (an rMem reg).
        var first = true
        for (part in expr.parts) {
            when (part) {
                is TemplatePart.Text -> {
                    if (part.value.isEmpty()) continue
                    val idx = ctx.pool.add(part.value)
                    val tmp = ctx.allocr()
                    ctx.emit(Opcode.LDC, 0, tmp, idx, 0, line)
                    if (first) {
                        ctx.emit(Opcode.MOVR, 0, dest, tmp, 0, line)
                        first = false
                    } else {
                        ctx.emit(Opcode.SCONCAT, 0, dest, dest, tmp, line)
                    }
                    ctx.freer(tmp)
                }

                is TemplatePart.Interpolation -> {
                    val exprType = part.expression.resolvedType ?: TypeRef.STRING
                    val pResolved = ctx.resolveRegister(part.expression)
                    val tmp =
                        if (pResolved != null) {
                            ctx.freeNodeRegisters(part.expression)
                            pResolved
                        } else {
                            val r = ctx.alloc(exprType)
                            emitExpr(part.expression, r, line)
                            r
                        }
                    // Convert to string if needed
                    val strTmp = ctx.allocr()
                    when {
                        exprType == TypeRef.INT -> ctx.emit(Opcode.I2S, 0, strTmp, tmp, 0, line)
                        exprType == TypeRef.DOUBLE -> ctx.emit(Opcode.D2S, 0, strTmp, tmp, 0, line)
                        exprType == TypeRef.BOOLEAN -> ctx.emit(Opcode.B2S, 0, strTmp, tmp, 0, line)
                        else -> ctx.emit(Opcode.MOVR, 0, strTmp, tmp, 0, line)
                    }
                    if (tmp != pResolved) ctx.free(exprType, tmp)
                    if (first) {
                        ctx.emit(Opcode.MOVR, 0, dest, strTmp, 0, line)
                        first = false
                    } else {
                        ctx.emit(Opcode.SCONCAT, 0, dest, dest, strTmp, line)
                    }
                    ctx.freer(strTmp)
                }

                is TemplatePart.ErrorPart -> { /* skip */ }
            }
        }
        // If all parts were empty / none existed
        if (first) {
            val idx = ctx.pool.add("")
            ctx.emit(Opcode.LDC, 0, dest, idx, 0, line)
        }
    }

    // Binary / Unary / Postfix

    private fun emitBinary(
        expr: BinaryExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val leftType = expr.left.resolvedType ?: TypeRef.INT
        val rightType = expr.right.resolvedType ?: TypeRef.INT
        val resultType = expr.resolvedType ?: TypeRef.INT

        val lResolved = ctx.resolveRegister(expr.left)
        val rResolved = ctx.resolveRegister(expr.right)

        val lNeedsWide = leftType == TypeRef.INT && resultType == TypeRef.DOUBLE
        val rNeedsWide = rightType == TypeRef.INT && resultType == TypeRef.DOUBLE

        val lReg =
            if (lResolved != null && !lNeedsWide) {
                lResolved
            } else {
                val r = ctx.alloc(leftType)
                emitExpr(expr.left, r, line)
                r
            }

        val rReg =
            if (rResolved != null && !rNeedsWide) {
                rResolved
            } else if (lReg != dest && rightType == resultType) {
                emitExpr(expr.right, dest, line)
                dest
            } else {
                val r = ctx.alloc(rightType)
                emitExpr(expr.right, r, line)
                r
            }

        // Implicit widening: int operand in a double operation
        val lWide =
            if (lNeedsWide) {
                val w = ctx.allocp()
                ctx.emit(Opcode.I2D, 0, w, lReg, 0, line)
                w
            } else {
                lReg
            }

        val rWide =
            if (rNeedsWide) {
                val w = ctx.allocp()
                ctx.emit(Opcode.I2D, 0, w, rReg, 0, line)
                w
            } else {
                rReg
            }

        val opcode = OpcodeSelector.binaryOpcode(expr.op, resultType, leftType, rightType)
        ctx.emit(opcode, 0, dest, lWide, rWide, line)

        if (lWide != lReg) ctx.freep(lWide)
        if (rWide != rReg) ctx.freep(rWide)
        if (lReg != lResolved) ctx.free(leftType, lReg)
        if (rReg != rResolved && rReg != dest) ctx.free(rightType, rReg)

        // Always check for variable frees at these nodes AFTER instruction emission
        ctx.freeNodeRegisters(expr.left)
        ctx.freeNodeRegisters(expr.right)
    }

    private fun emitUnary(
        expr: UnaryExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val operandType = expr.operand.resolvedType ?: TypeRef.INT

        val opResolved = ctx.resolveRegister(expr.operand)
        val opReg =
            if (opResolved != null) {
                opResolved
            } else {
                val r = ctx.alloc(operandType)
                emitExpr(expr.operand, r, line)
                r
            }

        when (expr.op) {
            UnaryOp.NEG -> {
                if (operandType == TypeRef.DOUBLE) {
                    ctx.emit(Opcode.DNEG, 0, dest, opReg, 0, line)
                } else {
                    ctx.emit(Opcode.INEG, 0, dest, opReg, 0, line)
                }
            }

            UnaryOp.NOT -> ctx.emit(Opcode.NOT, 0, dest, opReg, 0, line)
            UnaryOp.BIT_NOT -> ctx.emit(Opcode.BNOT, 0, dest, opReg, 0, line)
        }
        if (opReg != opResolved) ctx.free(operandType, opReg)
        ctx.freeNodeRegisters(expr.operand)
    }

    private fun emitPostfix(
        expr: PostfixExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val reg = ctx.resolveRegister(expr.operand) ?: return
        val type = expr.operand.resolvedType ?: TypeRef.INT
        // In expression context, copy current value to dest first
        if (type.isPrimitive()) {
            ctx.emit(Opcode.MOV, 0, dest, reg, 0, line)
        } else {
            ctx.emit(Opcode.MOVR, 0, dest, reg, 0, line)
        }
        val opcode =
            when (expr.op) {
                PostfixOp.INCREMENT -> if (type == TypeRef.DOUBLE) Opcode.DINC else Opcode.IINC
                PostfixOp.DECREMENT -> if (type == TypeRef.DOUBLE) Opcode.DDEC else Opcode.IDEC
            }
        ctx.emit(opcode, 0, reg, 0, 0, line)
    }

    private fun emitCast(
        expr: CastExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val srcType = expr.operand.resolvedType ?: TypeRef.JSON

        val opResolved = ctx.resolveRegister(expr.operand)
        val opReg =
            if (opResolved != null) {
                ctx.freeNodeRegisters(expr.operand)
                opResolved
            } else {
                val r = ctx.alloc(srcType)
                emitExpr(expr.operand, r, line)
                r
            }

        // Build the type descriptor
        val subOp = if (expr.targetType.isArray) 1 else 0
        val baseName = expr.targetType.name
        val descriptorIdx = ctx.buildDescriptor(baseName)

        ctx.emit(Opcode.CAST_STRUCT, subOp, dest, opReg, descriptorIdx, line)

        if (opReg != opResolved) ctx.free(srcType, opReg)
    }

    private fun emitLoad(
        expr: IdentifierExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        when (val sym = expr.resolvedSymbol) {
            is GlobalSymbol -> {
                val gReg = BytecodeEmitter.GLOBAL_FLAG or sym.globalSlot
                if (sym.type.isPrimitive()) {
                    ctx.emit(Opcode.MOV, 0, dest, gReg, 0, line)
                } else {
                    ctx.emit(Opcode.MOVR, 0, dest, gReg, 0, line)
                }
            }

            is VarSymbol -> {
                val reg = sym.register
                if (reg < 0) return
                if (sym.type.isPrimitive()) {
                    if (dest != reg) ctx.emit(Opcode.MOV, 0, dest, reg, 0, line)
                } else {
                    if (dest != reg) ctx.emit(Opcode.MOVR, 0, dest, reg, 0, line)
                }
            }

            is ParamSymbol -> {
                val reg = sym.register
                if (reg < 0) return
                if (sym.type.isPrimitive()) {
                    if (dest != reg) ctx.emit(Opcode.MOV, 0, dest, reg, 0, line)
                } else {
                    if (dest != reg) ctx.emit(Opcode.MOVR, 0, dest, reg, 0, line)
                }
            }

            else -> {
                // TODO: this should be a warning inside the compiler
                println("Unresolved symbol: ${expr.name}")
            }
        }
    }

    private fun emitFieldAccess(
        expr: FieldAccessExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val targetType = expr.target.resolvedType ?: TypeRef.JSON

        // Attempt AGET_PATH collapse for deep json chains
        if (targetType == TypeRef.JSON || targetType.isStructType()) {
            val path = collectFieldPath(expr)
            if (path != null && path.second.contains('.')) {
                // Deep path: AGET_PATH
                val tResolved = ctx.resolveRegister(path.first)
                val tReg =
                    if (tResolved != null) {
                        ctx.freeNodeRegisters(path.first)
                        tResolved
                    } else {
                        val r = ctx.allocr()
                        emitExpr(path.first, r, line)
                        r
                    }

                val pathIdx = ctx.pool.add(path.second)
                ctx.emit(Opcode.AGET_PATH, 0, dest, tReg, pathIdx, line)
                if (tReg != tResolved) ctx.freer(tReg)
                return
            }
        }

        if (targetType == TypeRef.JSON || targetType.isStructType()) {
            val tResolved = ctx.resolveRegister(expr.target)
            val tReg =
                if (tResolved != null) {
                    ctx.freeNodeRegisters(expr.target)
                    tResolved
                } else {
                    val r = ctx.allocr()
                    emitExpr(expr.target, r, line)
                    r
                }
            val fieldType = expr.resolvedType ?: TypeRef.STRING
            val subOp = OpcodeSelector.subOpForGet(fieldType)
            val keyIdx = ctx.pool.add(expr.fieldName)
            ctx.emit(Opcode.HACC, subOp, dest, tReg, keyIdx, line)
            if (tReg != tResolved) ctx.freer(tReg)
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
        if (parts.size < 2) return null // single field: use HACC, not AGET_PATH
        return cur to parts.joinToString(".")
    }

    private fun emitIndexAccess(
        expr: IndexAccessExpr,
        dest: Int,
    ) {
        val line = expr.loc.line

        val targetType = expr.target.resolvedType ?: TypeRef.JSON
        val tResolved = ctx.resolveRegister(expr.target)
        val tReg =
            if (tResolved != null) {
                tResolved
            } else {
                val r = ctx.alloc(targetType)
                emitExpr(expr.target, r, line)
                r
            }

        val idxType = expr.index.resolvedType ?: TypeRef.INT
        val iResolved = ctx.resolveRegister(expr.index)
        val iReg =
            if (iResolved != null) {
                iResolved
            } else {
                val r = ctx.alloc(idxType)
                emitExpr(expr.index, r, line)
                r
            }

        ctx.emit(Opcode.AGET_IDX, 0, dest, tReg, iReg, line)
        if (iReg != iResolved) ctx.free(idxType, iReg)
        if (tReg != tResolved) ctx.free(targetType, tReg)

        ctx.freeNodeRegisters(expr.index)
        ctx.freeNodeRegisters(expr.target)
    }

    private fun emitFuncCall(
        expr: FuncCallExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        val funcDef = expr.resolvedFunction ?: return
        val argStart = ctx.allocator.allocTempPrim() // will track frame start slot
        emitArgs(expr.args, funcDef.params, argStart, line)
        val funcIdx = ctx.pool.add(funcDef.name)
        ctx.emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)
        // result is left in argStart register by the callee's RET
        val retType = funcDef.returnType
        if (retType != TypeRef.VOID) {
            if (retType.isPrimitive()) {
                if (dest != argStart) ctx.emit(Opcode.MOV, 0, dest, argStart, 0, line)
            } else {
                if (dest != argStart) ctx.emit(Opcode.MOVR, 0, dest, argStart, 0, line)
            }
        }
        ctx.allocator.freeTempPrim(argStart)
    }

    private fun emitArgs(
        args: List<Expr>,
        params: List<Param>,
        argStart: Int,
        line: Int,
    ) {
        for ((i, arg) in args.withIndex()) {
            val param = params.getOrNull(i) ?: continue
            val argReg = argStart + i
            emitExpr(arg, argReg, line)
        }
    }

    private fun emitMethodCall(
        expr: MethodCallExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        when (expr.resolution) {
            MethodCallExpr.Resolution.NAMESPACE -> emitNamespaceCall(expr, dest, line)
            MethodCallExpr.Resolution.TYPE_BOUND -> emitTypeBoundCall(expr, dest, line)
            MethodCallExpr.Resolution.UFCS -> emitUfcsCall(expr, dest, line)
            null -> {
                // TODO: this should be a warning inside the compiler. Or error since this should never have happened.
                println("Unresolved method call: ${expr.methodName}")
            }
        }
    }

    private fun emitNamespaceCall(
        expr: MethodCallExpr,
        dest: Int,
        line: Int,
    ) {
        val target = expr.resolvedTarget ?: return
        // check if this is an import namespace (user function) or builtin (SCALL)
        val isImport = ctx.modules.any { m -> m.program.functionsByName.containsKey(target.name) }
        if (isImport) {
            // CALL (user-defined function in imported module)
            val argStart = ctx.allocator.allocTempPrim()
            for ((i, arg) in expr.args.withIndex()) {
                emitExpr(arg, argStart + i, line)
            }
            val funcIdx = ctx.pool.add(target.name)
            ctx.emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)
            if (dest != argStart) {
                if ((expr.resolvedType ?: TypeRef.INT).isPrimitive()) {
                    ctx.emit(Opcode.MOV, 0, dest, argStart, 0, line)
                } else {
                    ctx.emit(Opcode.MOVR, 0, dest, argStart, 0, line)
                }
            }
            ctx.allocator.freeTempPrim(argStart)
        } else {
            // SCALL (native function)
            val argStart = ctx.allocator.allocTempPrim()
            for ((i, arg) in expr.args.withIndex()) {
                emitExpr(arg, argStart + i, line)
            }
            emitDefaultArgs(target.params, expr.args.size, argStart, line)
            val funcIdx = ctx.pool.add(target.name)
            ctx.emit(Opcode.SCALL, 0, dest, funcIdx, argStart, line)
            ctx.allocator.freeTempPrim(argStart)
        }
    }

    private fun emitTypeBoundCall(
        expr: MethodCallExpr,
        dest: Int,
        line: Int,
    ) {
        val target = expr.resolvedTarget ?: return
        val argStart = ctx.allocator.allocTempPrim()
        emitExpr(expr.target, argStart, line)
        for ((i, arg) in expr.args.withIndex()) emitExpr(arg, argStart + i + 1, line)
        // +1 offset because slot 0 is the receiver
        emitDefaultArgs(target.params, expr.args.size, argStart + 1, line)
        val funcIdx = ctx.pool.add(target.name)
        ctx.emit(Opcode.SCALL, 0, dest, funcIdx, argStart, line)
        ctx.allocator.freeTempPrim(argStart)
    }

    private fun emitUfcsCall(
        expr: MethodCallExpr,
        dest: Int,
        line: Int,
    ) {
        val target = expr.resolvedTarget ?: return
        val argStart = ctx.allocator.allocTempPrim()

        // Prepend receiver as first argument
        emitExpr(expr.target, argStart, line)
        for ((i, arg) in expr.args.withIndex()) {
            emitExpr(arg, argStart + i + 1, line)
        }

        val funcIdx = ctx.pool.add(target.name)
        ctx.emit(Opcode.CALL, 0, funcIdx, argStart, 0, line)

        val retType = expr.resolvedType ?: TypeRef.VOID
        if (retType != TypeRef.VOID && dest != argStart) {
            if (retType.isPrimitive()) {
                ctx.emit(Opcode.MOV, 0, dest, argStart, 0, line)
            } else {
                ctx.emit(Opcode.MOVR, 0, dest, argStart, 0, line)
            }
        }

        ctx.allocator.freeTempPrim(argStart)
    }

    // Default argument injection for plugin optional params

    /**
     * Emits default values for any omitted optional arguments in a plugin (SCALL) call.
     *
     * @param params   the full parameter list from the [CallTarget]
     * @param provided number of arguments the caller actually supplied
     * @param argStart register base for arguments
     * @param line     source line for diagnostics
     */
    private fun emitDefaultArgs(
        params: List<NoxParam>,
        provided: Int,
        argStart: Int,
        line: Int,
    ) {
        for (i in provided until params.size) {
            val literal = params[i].defaultLiteral ?: continue
            emitDefaultLiteral(literal, params[i].type, argStart + i, line)
        }
    }

    private fun emitDefaultLiteral(
        literal: String,
        type: TypeRef,
        dest: Int,
        line: Int,
    ) {
        when {
            literal == "true" -> ctx.emit(Opcode.LDI, 0, dest, 1, 0, line)
            literal == "false" -> ctx.emit(Opcode.LDI, 0, dest, 0, 0, line)
            literal == "null" -> ctx.emit(Opcode.KILL_REF, 0, dest, 0, 0, line)
            type == TypeRef.INT || type == TypeRef.BOOLEAN -> {
                val v = literal.toLong()
                if (v in 0..0xFFFF) {
                    ctx.emit(Opcode.LDI, 0, dest, v.toInt(), 0, line)
                } else {
                    val idx = ctx.pool.add(v)
                    ctx.emit(Opcode.LDC, 0, dest, idx, 0, line)
                }
            }
            type == TypeRef.DOUBLE -> {
                val idx = ctx.pool.add(literal.toDouble())
                ctx.emit(Opcode.LDC, 0, dest, idx, 0, line)
            }
            type == TypeRef.STRING -> {
                // Strip surrounding quotes if present
                val str =
                    if (literal.startsWith("\"") && literal.endsWith("\"")) {
                        literal.substring(1, literal.length - 1)
                    } else {
                        literal
                    }
                val idx = ctx.pool.add(str)
                ctx.emit(Opcode.LDC, 0, dest, idx, 0, line)
            }
            else -> ctx.emit(Opcode.KILL_REF, 0, dest, 0, 0, line)
        }
    }

    // Composite literals

    private fun emitArrayLiteral(
        expr: ArrayLiteralExpr,
        dest: Int,
    ) {
        val line = expr.loc.line

        ctx.emit(Opcode.NEW_ARRAY, 0, dest, 0, 0, line)

        val elemType = expr.elementType ?: TypeRef.JSON

        for (elem in expr.elements) {
            val elemResolvedType = elem.resolvedType
            // Widen int to double if the array element type requires it
            if (elemType == TypeRef.DOUBLE && elemResolvedType == TypeRef.INT) {
                val eResolved = ctx.resolveRegister(elem)
                val intTmp =
                    if (eResolved != null) {
                        ctx.freeNodeRegisters(elem)
                        eResolved
                    } else {
                        val r = ctx.allocator.allocTempPrim()
                        emitExpr(elem, r, line)
                        r
                    }

                val dblTmp = ctx.allocator.allocTempPrim()
                ctx.emit(Opcode.I2D, 0, dblTmp, intTmp, 0, line)
                ctx.emit(Opcode.ARR_PUSH, SubOp.SET_DBL, dest, 0, dblTmp, line)

                ctx.allocator.freeTempPrim(dblTmp)
                if (intTmp != eResolved) ctx.allocator.freeTempPrim(intTmp)
                ctx.freeNodeRegisters(elem)
            } else {
                val eResolved = ctx.resolveRegister(elem)

                val tmp =
                    if (eResolved != null) {
                        ctx.freeNodeRegisters(elem)
                        eResolved
                    } else {
                        val r = ctx.alloc(elemType)
                        emitExpr(elem, r, line)
                        r
                    }

                ctx.emit(Opcode.ARR_PUSH, setSubOpFor(elemType), dest, 0, tmp, line)

                if (tmp != eResolved) ctx.free(elemType, tmp)
                ctx.freeNodeRegisters(elem)
            }
        }
    }

    private fun emitStructLiteral(
        expr: StructLiteralExpr,
        dest: Int,
    ) {
        val line = expr.loc.line
        ctx.emit(Opcode.NEW_OBJ, 0, dest, 0, 0, line)

        for (field in expr.fields) {
            val fieldType = field.value.resolvedType ?: TypeRef.JSON
            val fResolved = ctx.resolveRegister(field.value)

            val tmp =
                if (fResolved != null) {
                    fResolved
                } else {
                    val r = ctx.alloc(fieldType)
                    emitExpr(field.value, r, line)
                    r
                }

            val keyIdx = ctx.pool.add(field.name)
            ctx.emit(Opcode.OBJ_SET, setSubOpFor(fieldType), dest, keyIdx, tmp, line)

            if (tmp != fResolved) ctx.free(fieldType, tmp)
            ctx.freeNodeRegisters(field.value)
        }
    }

    companion object {
        /**
         * Map a [TypeRef] to the corresponding `SubOp.SET_*` constant.
         * Used by OBJ_SET and ARR_PUSH to tell the VM which register bank
         * holds the value operand.
         */
        fun setSubOpFor(type: TypeRef): Int =
            when (type.name) {
                "int" -> SubOp.SET_INT
                "double" -> SubOp.SET_DBL
                "string" -> SubOp.SET_STR
                "boolean" -> SubOp.SET_BOOL
                else -> SubOp.SET_OBJ
            }
    }
}
