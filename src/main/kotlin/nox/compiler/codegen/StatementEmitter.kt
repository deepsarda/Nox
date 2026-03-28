package nox.compiler.codegen

import nox.compiler.ast.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

/**
 * Emits bytecode for all statement AST nodes.
 *
 * Delegates infrastructure (register allocation,
 * instruction encoding, constant pool) back through [ctx], and expression
 * emission to [ExpressionEmitter] via [ctx.emitExpr].
 *
 * Owns the loop context stack for break/continue back-patching.
 *
 * See docs/compiler/codegen.md for the full design.
 */
class StatementEmitter(
    private val ctx: BytecodeEmitter,
    private val registry: LibraryRegistry,
) {
    // Loop context stack (break/continue backpatching)
    private data class LoopContext(
        val loopStart: Int,
        val continueTarget: Int, // for-loops: update PC; while/foreach: loopStart
        val breakPatches: MutableList<Int> = mutableListOf(),
        val continuePatches: MutableList<Int> = mutableListOf(),
    )

    private val loopStack = ArrayDeque<LoopContext>()

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
                val tmp = ctx.alloc(type)
                ctx.emitExpr(stmt.expression, tmp)
                ctx.free(type, tmp)
            }

            is Block -> ctx.emitBlock(stmt)
            is ErrorStmt -> { // skip
            }
        }
        ctx.freeNodeRegisters(stmt)
    }

    // Declarations & Assignment

    private fun emitVarDecl(stmt: VarDeclStmt) {
        val line = stmt.loc.line
        val reg = ctx.allocator.allocVar(stmt)
        ctx.regNameEvents.add(RegNameEvent(ctx.pc, stmt.type.isPrimitive(), reg, stmt.name))
        ctx.emitExpr(stmt.initializer, reg, line)
    }

    private fun emitAssign(stmt: AssignStmt) {
        val line = stmt.loc.line
        when (stmt.op) {
            AssignOp.ASSIGN -> emitSimpleAssign(stmt, line)
            AssignOp.ADD_ASSIGN,
            AssignOp.SUB_ASSIGN,
            AssignOp.MUL_ASSIGN,
            AssignOp.DIV_ASSIGN,
            AssignOp.MOD_ASSIGN,
            -> emitCompoundAssign(stmt, line)
        }
    }

    private fun emitSimpleAssign(
        stmt: AssignStmt,
        line: Int,
    ) {
        val targetType = stmt.value.resolvedType ?: TypeRef.INT
        when (val target = stmt.target) {
            is IdentifierExpr -> {
                when (val sym = target.resolvedSymbol) {
                    is GlobalSymbol -> {
                        val valResolved = ctx.resolveRegister(stmt.value)
                        val valReg =
                            if (valResolved != null) {
                                ctx.freeNodeRegisters(stmt.value)
                                valResolved
                            } else {
                                val r = ctx.alloc(targetType)
                                ctx.emitExpr(stmt.value, r, line)
                                r
                            }
                        if (sym.type.isPrimitive()) {
                            ctx.emit(Opcode.GSTORE, 0, sym.globalSlot, valReg, 0, line)
                        } else {
                            ctx.emit(Opcode.GSTORER, 0, sym.globalSlot, valReg, 0, line)
                        }
                        if (valReg != valResolved) ctx.free(targetType, valReg)
                    }

                    else -> {
                        val reg = ctx.resolveRegister(target) ?: return
                        ctx.emitExpr(stmt.value, reg, line)
                    }
                }
            }

            is FieldAccessExpr -> {
                val objType = target.target.resolvedType ?: TypeRef.JSON
                val objReg = ctx.alloc(objType)
                ctx.emitExpr(target.target, objReg, line)

                val valType = stmt.value.resolvedType ?: TypeRef.JSON
                val valResolved = ctx.resolveRegister(stmt.value)
                val valReg =
                    if (valResolved != null) {
                        ctx.freeNodeRegisters(stmt.value)
                        valResolved
                    } else {
                        val r = ctx.alloc(valType)
                        ctx.emitExpr(stmt.value, r, line)
                        r
                    }

                val keyIdx = ctx.pool.add(target.fieldName)
                val subOp = OpcodeSelector.subOpForSet(valType)
                ctx.emit(Opcode.HMOD, subOp, objReg, keyIdx, valReg, line)
                if (valReg != valResolved) ctx.free(valType, valReg)
                ctx.free(objType, objReg)
            }

            is IndexAccessExpr -> {
                val targetType2 = target.target.resolvedType ?: TypeRef.JSON
                val tReg = ctx.alloc(targetType2)
                ctx.emitExpr(target.target, tReg, line)
                val idxType = target.index.resolvedType ?: TypeRef.INT
                val iReg = ctx.alloc(idxType)
                ctx.emitExpr(target.index, iReg, line)

                val valType = stmt.value.resolvedType ?: TypeRef.JSON
                val valResolved = ctx.resolveRegister(stmt.value)
                val valReg =
                    if (valResolved != null) {
                        ctx.freeNodeRegisters(stmt.value)
                        valResolved
                    } else {
                        val r = ctx.alloc(valType)
                        ctx.emitExpr(stmt.value, r, line)
                        r
                    }

                ctx.emit(Opcode.ASET_IDX, 0, tReg, iReg, valReg, line)
                if (valReg != valResolved) ctx.free(valType, valReg)
                ctx.free(idxType, iReg)
                ctx.free(targetType2, tReg)
            }

            else -> { // unsupported lvalue
            }
        }
    }

    private fun emitCompoundAssign(
        stmt: AssignStmt,
        line: Int,
    ) {
        val targetType = stmt.target.resolvedType ?: TypeRef.INT
        val reg = ctx.resolveRegister(stmt.target) ?: return
        val valType = stmt.value.resolvedType ?: TypeRef.INT

        val valResolved = ctx.resolveRegister(stmt.value)
        val valReg =
            if (valResolved != null) {
                ctx.freeNodeRegisters(stmt.value)
                valResolved
            } else {
                val r = ctx.alloc(valType)
                ctx.emitExpr(stmt.value, r, line)
                r
            }

        // Optimization: += to IINCN, -= to IDECN
        if (stmt.op == AssignOp.ADD_ASSIGN && targetType == TypeRef.INT) {
            ctx.emit(Opcode.IINCN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.SUB_ASSIGN && targetType == TypeRef.INT) {
            ctx.emit(Opcode.IDECN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.ADD_ASSIGN && targetType == TypeRef.DOUBLE) {
            ctx.emit(Opcode.DINCN, 0, reg, valReg, 0, line)
        } else if (stmt.op == AssignOp.SUB_ASSIGN && targetType == TypeRef.DOUBLE) {
            ctx.emit(Opcode.DDECN, 0, reg, valReg, 0, line)
        } else {
            val opcode = OpcodeSelector.compoundAssignOpcode(stmt.op, targetType)
            ctx.emit(opcode, 0, reg, reg, valReg, line)
        }
        if (valReg != valResolved) ctx.free(valType, valReg)
    }

    private fun emitIncrement(stmt: IncrementStmt) {
        val line = stmt.loc.line
        val reg = ctx.resolveRegister(stmt.target) ?: return
        val type = stmt.target.resolvedType ?: TypeRef.INT
        val opcode =
            when (stmt.op) {
                PostfixOp.INCREMENT -> if (type == TypeRef.DOUBLE) Opcode.DINC else Opcode.IINC
                PostfixOp.DECREMENT -> if (type == TypeRef.DOUBLE) Opcode.DDEC else Opcode.IDEC
            }
        ctx.emit(opcode, 0, reg, 0, 0, line)
    }

    // Control Flow

    private fun emitIf(stmt: IfStmt) {
        val line = stmt.loc.line

        val condResolved = ctx.resolveRegister(stmt.condition)
        val condReg =
            if (condResolved != null) {
                ctx.freeNodeRegisters(stmt.condition)
                condResolved
            } else {
                val r = ctx.allocp()
                ctx.emitExpr(stmt.condition, r, line)
                r
            }
        // patch: jumpToElse target filled in later
        val jumpToElse = ctx.emit(Opcode.JIF, 0, condReg, 0, 0, line)
        if (condReg != condResolved) ctx.freep(condReg)

        ctx.emitBlock(stmt.thenBlock)

        val jumpsToEnd = mutableListOf<Int>()
        // patch: jumpToEnd target filled in later
        var jumpToEnd = ctx.emit(Opcode.JMP, 0, 0, 0, 0)
        jumpsToEnd.add(jumpToEnd)

        ctx.patch(jumpToElse, ctx.pc)

        for (elseIf in stmt.elseIfs) {
            val eiCondResolved = ctx.resolveRegister(elseIf.condition)
            val eiCondReg =
                if (eiCondResolved != null) {
                    ctx.freeNodeRegisters(elseIf.condition)
                    eiCondResolved
                } else {
                    val r = ctx.allocp()
                    ctx.emitExpr(elseIf.condition, r, elseIf.loc.line)
                    r
                }
            // patch: jumpNext target filled in later
            val jumpNext = ctx.emit(Opcode.JIF, 0, eiCondReg, 0, 0, elseIf.loc.line)
            if (eiCondReg != eiCondResolved) ctx.freep(eiCondReg)

            ctx.emitBlock(elseIf.body)
            // patch: jEnd target filled in later
            val jEnd = ctx.emit(Opcode.JMP, 0, 0, 0, 0)
            jumpsToEnd.add(jEnd)
            ctx.patch(jumpNext, ctx.pc)
        }

        stmt.elseBlock?.let { ctx.emitBlock(it) }

        val endPc = ctx.pc
        for (j in jumpsToEnd) ctx.patch(j, endPc)
    }

    private fun emitWhile(stmt: WhileStmt) {
        val line = stmt.loc.line
        val seq = ++ctx.labelSeq
        ctx.addLabel("loop_start_$seq")
        val loopStart = ctx.pc

        val loopCtx = LoopContext(loopStart, loopStart)
        loopStack.addLast(loopCtx)

        val condResolved = ctx.resolveRegister(stmt.condition)
        val condReg =
            if (condResolved != null) {
                ctx.freeNodeRegisters(stmt.condition)
                condResolved
            } else {
                val r = ctx.allocp()
                ctx.emitExpr(stmt.condition, r, line)
                r
            }
        // patch: jumpToExit target filled in later
        val jumpToExit = ctx.emit(Opcode.JIF, 0, condReg, 0, 0, line)
        if (condReg != condResolved) ctx.freep(condReg)

        ctx.emitBlock(stmt.body)
        ctx.emit(Opcode.JMP, 0, loopStart, 0, 0)

        ctx.addLabel("loop_exit_$seq")
        val exitPc = ctx.pc
        ctx.patch(jumpToExit, exitPc)
        loopCtx.breakPatches.forEach { ctx.patch(it, exitPc) }
        loopCtx.continuePatches.forEach { ctx.patch(it, loopStart) }
        loopStack.removeLast()
    }

    private fun emitFor(stmt: ForStmt) {
        val line = stmt.loc.line

        // Init
        stmt.init?.let { emitStmt(it) }

        val seq = ++ctx.labelSeq
        ctx.addLabel("loop_start_$seq")
        val loopStart = ctx.pc

        // Condition
        var jumpToExit: Int? = null
        if (stmt.condition != null) {
            val condResolved = ctx.resolveRegister(stmt.condition)
            val condReg =
                if (condResolved != null) {
                    ctx.freeNodeRegisters(stmt.condition)
                    condResolved
                } else {
                    val r = ctx.allocp()
                    ctx.emitExpr(stmt.condition, r, line)
                    r
                }
            // patch: jumpToExit target filled in later
            jumpToExit = ctx.emit(Opcode.JIF, 0, condReg, 0, 0, line)
            if (condReg != condResolved) ctx.freep(condReg)
        }

        val loopCtx = LoopContext(loopStart, -1) // set after body
        loopStack.addLast(loopCtx)

        ctx.emitBlock(stmt.body)

        // Loop update target (continue jumps here)
        ctx.addLabel("loop_update_$seq")
        val updatePc = ctx.pc
        loopCtx.continuePatches.forEach { ctx.patch(it, updatePc) }

        stmt.update?.let { emitStmt(it) }
        ctx.emit(Opcode.JMP, 0, loopStart, 0, 0)

        ctx.addLabel("loop_exit_$seq")
        val exitPc = ctx.pc
        jumpToExit?.let { ctx.patch(it, exitPc) }
        loopCtx.breakPatches.forEach { ctx.patch(it, exitPc) }
        loopStack.removeLast()
    }

    private fun emitForEach(stmt: ForEachStmt) {
        val line = stmt.loc.line
        val iterType = stmt.iterable.resolvedType ?: TypeRef.JSON

        val arrReg = ctx.alloc(iterType)
        ctx.emitExpr(stmt.iterable, arrReg, line)

        val idxReg = ctx.allocp()
        val lenReg = ctx.allocp()
        val condReg = ctx.allocp()
        val elemReg = ctx.allocator.allocElement(stmt)
        ctx.regNameEvents.add(RegNameEvent(ctx.pc, stmt.elementType.isPrimitive(), elemReg, stmt.elementName))
        ctx.emit(Opcode.LDI, 0, idxReg, 0, 0, line) // idx = 0
        val lenTarget =
            registry.lookupBuiltinMethod(iterType, "length")
                ?: error("No 'length' method found on type '$iterType'")
        val lenArgStart = ctx.allocator.allocTempPrim()
        ctx.emit(Opcode.MOVR, 0, lenArgStart, arrReg, 0, line)
        val lenFuncIdx = ctx.pool.add(lenTarget.name)
        ctx.emit(Opcode.SCALL, 0, lenReg, lenFuncIdx, lenArgStart, line)
        ctx.allocator.freeTempPrim(lenArgStart)

        val seq = ++ctx.labelSeq
        ctx.addLabel("loop_start_$seq")
        val loopStart = ctx.pc

        ctx.emit(Opcode.ILT, 0, condReg, idxReg, lenReg, line) // cond = idx < len
        // patch: jumpToExit target filled in later
        val jumpToExit = ctx.emit(Opcode.JIF, 0, condReg, 0, 0, line)

        ctx.emit(Opcode.AGET_IDX, 0, elemReg, arrReg, idxReg, line) // elem = arr[idx]

        val loopCtx = LoopContext(loopStart, -1)
        loopStack.addLast(loopCtx)
        ctx.emitBlock(stmt.body)

        // continue target: IINC + JMP back
        ctx.addLabel("loop_update_$seq")
        val updatePc = ctx.pc
        loopCtx.continuePatches.forEach { ctx.patch(it, updatePc) }

        ctx.emit(Opcode.KILL_REF, 0, elemReg, 0, 0, line)
        ctx.emit(Opcode.IINC, 0, idxReg, 0, 0, line)
        ctx.emit(Opcode.JMP, 0, loopStart, 0, 0)

        ctx.addLabel("loop_exit_$seq")
        val exitPc = ctx.pc
        ctx.patch(jumpToExit, exitPc)
        loopCtx.breakPatches.forEach { ctx.patch(it, exitPc) }
        loopStack.removeLast()

        ctx.freep(condReg)
        ctx.freep(lenReg)
        ctx.freep(idxReg)
        ctx.free(iterType, arrReg)
    }

    private fun emitBreak() {
        val loopCtx = loopStack.lastOrNull() ?: return
        // patch: jmp target filled in later
        val jmp = ctx.emit(Opcode.JMP, 0, 0, 0, 0)
        loopCtx.breakPatches.add(jmp)
    }

    private fun emitContinue() {
        val loopCtx = loopStack.lastOrNull() ?: return
        if (loopCtx.continueTarget >= 0) {
            ctx.emit(Opcode.JMP, 0, loopCtx.continueTarget, 0, 0)
        } else {
            // patch: jmp target filled in later
            val jmp = ctx.emit(Opcode.JMP, 0, 0, 0, 0)
            loopCtx.continuePatches.add(jmp)
        }
    }

    // Exit / Exception

    private fun emitReturn(stmt: ReturnStmt) {
        val line = stmt.loc.line

        // Emit KILL_REF for all live rMem registers before returning.
        for (reg in ctx.allocator.allRefRegs.sorted()) {
            ctx.emit(Opcode.KILL_REF, 0, reg, 0, 0, line)
        }

        if (stmt.value == null) {
            ctx.emit(Opcode.RET, 0, 0, 0, 0, line)
        } else {
            val type = stmt.value.resolvedType ?: TypeRef.INT
            val reg = ctx.resolveRegister(stmt.value)
            if (reg != null) {
                ctx.freeNodeRegisters(stmt.value)
                ctx.emit(Opcode.RET, 0, reg, 0, 0, line)
            } else {
                val tmp = ctx.alloc(type)
                ctx.emitExpr(stmt.value, tmp, line)
                ctx.emit(Opcode.RET, 0, tmp, 0, 0, line)
                ctx.free(type, tmp)
            }
        }
    }

    private fun emitYield(stmt: YieldStmt) {
        val line = stmt.loc.line
        val type = stmt.value.resolvedType ?: TypeRef.STRING
        val reg = ctx.resolveRegister(stmt.value)
        if (reg != null) {
            ctx.freeNodeRegisters(stmt.value)
            ctx.emit(Opcode.YIELD, 0, reg, 0, 0, line)
        } else {
            val tmp = ctx.alloc(type)
            ctx.emitExpr(stmt.value, tmp, line)
            ctx.emit(Opcode.YIELD, 0, tmp, 0, 0, line)
            ctx.free(type, tmp)
        }
    }

    private fun emitThrow(stmt: ThrowStmt) {
        val line = stmt.loc.line
        val type = stmt.value.resolvedType ?: TypeRef.STRING
        val reg = ctx.resolveRegister(stmt.value)
        if (reg != null) {
            ctx.freeNodeRegisters(stmt.value)
            ctx.emit(Opcode.THROW, 0, reg, 0, 0, line)
        } else {
            val tmp = ctx.alloc(type)
            ctx.emitExpr(stmt.value, tmp, line)
            ctx.emit(Opcode.THROW, 0, tmp, 0, 0, line)
            ctx.free(type, tmp)
        }
    }

    private fun emitTryCatch(stmt: TryCatchStmt) {
        val tryStart = ctx.pc
        ctx.emitBlock(stmt.tryBlock)
        val tryEnd = ctx.pc

        // patch: jumpPastCatches target filled in later
        val jumpPastCatches = ctx.emit(Opcode.JMP, 0, 0, 0, 0)

        val catchEndJumps = mutableListOf<Int>()
        for (clause in stmt.catchClauses) {
            val handlerPc = ctx.pc
            ctx.addLabel(if (clause.exceptionType != null) "catch_${clause.exceptionType}" else "catch_all")
            val msgReg = ctx.allocr()
            ctx.exceptionEntries.add(ExEntry(tryStart, tryEnd, clause.exceptionType, handlerPc, msgReg))

            ctx.emitBlock(clause.body)
            // patch: jEnd target filled in later
            val jEnd = ctx.emit(Opcode.JMP, 0, 0, 0, 0)
            catchEndJumps.add(jEnd)
            ctx.freer(msgReg)
        }

        ctx.addLabel("end")
        val afterCatches = ctx.pc
        ctx.patch(jumpPastCatches, afterCatches)
        catchEndJumps.forEach { ctx.patch(it, afterCatches) }
    }
}
