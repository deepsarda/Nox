package nox.compiler.semantic

import nox.compiler.ast.typed.*
import nox.compiler.types.*

/**
 * Compile-time constant folding and dead branch elimination.
 *
 * Operates on the typed AST after semantic analysis, before code generation.
 * Rewrites expressions with statically-known values (e.g. `5 + 5` -> `10`)
 * and eliminates dead branches (e.g. `if (true) { A } else { B }` -> `A`).
 *
 * Division by zero is left unfolded to preserve runtime error semantics.
 */
object ConstantFolder {
    fun fold(program: TypedProgram): TypedProgram {
        val newDecls = program.declarations.map { foldDecl(it) }
        if (newDecls === program.declarations || newDecls == program.declarations) return program

        val result = TypedProgram(program.fileName, program.headers, program.imports, newDecls)
        for (decl in newDecls) {
            when (decl) {
                is TypedTypeDef -> result.typesByName[decl.name] = decl
                is TypedFuncDef -> result.functionsByName[decl.name] = decl
                is TypedGlobalVarDecl -> result.globals.add(decl)
                else -> {}
            }
        }
        result.sourceLines.addAll(program.sourceLines)
        return result
    }

    private fun foldDecl(decl: TypedDecl): TypedDecl =
        when (decl) {
            is TypedFuncDef -> {
                val newBody = foldBlock(decl.body)
                if (newBody === decl.body) {
                    decl
                } else {
                    TypedFuncDef(decl.returnType, decl.name, decl.params, newBody, decl.loc).also {
                        it.maxPrimitiveRegisters = decl.maxPrimitiveRegisters
                        it.maxReferenceRegisters = decl.maxReferenceRegisters
                    }
                }
            }
            is TypedMainDef -> {
                val newBody = foldBlock(decl.body)
                if (newBody === decl.body) {
                    decl
                } else {
                    TypedMainDef(decl.returnType, decl.params, newBody, decl.loc).also {
                        it.maxPrimitiveRegisters = decl.maxPrimitiveRegisters
                        it.maxReferenceRegisters = decl.maxReferenceRegisters
                    }
                }
            }
            is TypedGlobalVarDecl -> {
                val init = decl.initializer ?: return decl
                val folded = foldExpr(init)
                if (folded === init) {
                    decl
                } else {
                    TypedGlobalVarDecl(decl.type, decl.name, folded, decl.loc).also {
                        it.globalSlot = decl.globalSlot
                    }
                }
            }
            else -> decl
        }

    private fun foldBlock(block: TypedBlock): TypedBlock {
        val newStmts = foldStmtList(block.statements)
        if (newStmts === block.statements) return block
        return TypedBlock(newStmts, block.loc, block.scopeDepth)
    }

    private fun foldStmtList(stmts: List<TypedStmt>): List<TypedStmt> {
        var changed = false
        val result =
            stmts.flatMap { stmt ->
                val folded = foldStmt(stmt)
                if (folded !== listOf(stmt)) changed = true
                folded
            }
        return if (changed) result else stmts
    }

    /**
     * Returns a list because dead branch elimination can expand a single
     * if-statement into its body statements (inline the block).
     */
    private fun foldStmt(stmt: TypedStmt): List<TypedStmt> =
        when (stmt) {
            is TypedIfStmt -> foldIf(stmt)
            is TypedWhileStmt -> {
                val cond = foldExpr(stmt.condition)
                // while(false) so remove entirely
                if (cond is TypedBoolLiteralExpr && !cond.value) {
                    emptyList()
                } else {
                    val body = foldBlock(stmt.body)
                    if (cond === stmt.condition && body === stmt.body) {
                        listOf(stmt)
                    } else {
                        listOf(TypedWhileStmt(cond, body, stmt.loc))
                    }
                }
            }
            is TypedForStmt -> {
                val init = stmt.init?.let { foldStmt(it).firstOrNull() }
                val cond = stmt.condition?.let { foldExpr(it) }
                val update = stmt.update?.let { foldStmt(it).firstOrNull() }
                val body = foldBlock(stmt.body)
                if (init === stmt.init &&
                    cond === stmt.condition &&
                    update === stmt.update &&
                    body === stmt.body
                ) {
                    listOf(stmt)
                } else {
                    listOf(TypedForStmt(init, cond, update, body, stmt.loc))
                }
            }
            is TypedForEachStmt -> {
                val body = foldBlock(stmt.body)
                if (body === stmt.body) {
                    listOf(stmt)
                } else {
                    listOf(
                        TypedForEachStmt(
                            stmt.elementType,
                            stmt.elementName,
                            stmt.iterable,
                            body,
                            stmt.loc,
                            stmt.resolvedSymbol,
                            stmt.elementRegister,
                        ),
                    )
                }
            }
            is TypedBlock -> {
                val folded = foldBlock(stmt)
                if (folded === stmt) listOf(stmt) else listOf(folded)
            }
            is TypedVarDeclStmt -> {
                val init = foldExpr(stmt.initializer)
                if (init === stmt.initializer) {
                    listOf(stmt)
                } else {
                    listOf(TypedVarDeclStmt(stmt.type, stmt.name, init, stmt.loc, stmt.resolvedSymbol, stmt.register))
                }
            }
            is TypedAssignStmt -> {
                val value = foldExpr(stmt.value)
                if (value ===
                    stmt.value
                ) {
                    listOf(stmt)
                } else {
                    listOf(TypedAssignStmt(stmt.target, stmt.op, value, stmt.loc))
                }
            }
            is TypedReturnStmt -> {
                val value = stmt.value?.let { foldExpr(it) }
                if (value === stmt.value) listOf(stmt) else listOf(TypedReturnStmt(value, stmt.loc))
            }
            is TypedYieldStmt -> {
                val value = foldExpr(stmt.value)
                if (value === stmt.value) listOf(stmt) else listOf(TypedYieldStmt(value, stmt.loc))
            }
            is TypedExprStmt -> {
                val expr = foldExpr(stmt.expression)
                if (expr === stmt.expression) listOf(stmt) else listOf(TypedExprStmt(expr, stmt.loc))
            }
            is TypedThrowStmt -> {
                val value = foldExpr(stmt.value)
                if (value === stmt.value) listOf(stmt) else listOf(TypedThrowStmt(value, stmt.loc))
            }
            is TypedTryCatchStmt -> {
                val tryBlock = foldBlock(stmt.tryBlock)
                val catches =
                    stmt.catchClauses.map { clause ->
                        val body = foldBlock(clause.body)
                        if (body === clause.body) clause else clause.copy(body = body)
                    }
                if (tryBlock === stmt.tryBlock && catches.zip(stmt.catchClauses).all { it.first === it.second }) {
                    listOf(stmt)
                } else {
                    listOf(TypedTryCatchStmt(tryBlock, catches, stmt.loc))
                }
            }
            is TypedBreakStmt, is TypedContinueStmt, is TypedIncrementStmt, is TypedErrorStmt -> listOf(stmt)
        }

    private fun foldIf(stmt: TypedIfStmt): List<TypedStmt> {
        val cond = foldExpr(stmt.condition)

        // Statically-known condition on the top-level if
        if (cond is TypedBoolLiteralExpr) {
            if (cond.value) {
                // if (true) { A } else { B } -> A
                return foldBlock(stmt.thenBlock).statements
            } else {
                // if (false) -> try elseIfs, then else
                return foldElseIfChain(stmt.elseIfs, stmt.elseBlock)
            }
        }

        // Condition not static so fold children and rebuild
        val thenBlock = foldBlock(stmt.thenBlock)
        val elseIfs = foldElseIfs(stmt.elseIfs)
        val elseBlock = stmt.elseBlock?.let { foldBlock(it) }
        if (cond === stmt.condition &&
            thenBlock === stmt.thenBlock &&
            elseIfs === stmt.elseIfs &&
            elseBlock === stmt.elseBlock
        ) {
            return listOf(stmt)
        }
        return listOf(TypedIfStmt(cond, thenBlock, elseIfs, elseBlock, stmt.loc))
    }

    /**
     * When the primary if-condition is statically false, walk the else-if chain.
     */
    private fun foldElseIfChain(
        elseIfs: List<TypedIfStmt.ElseIf>,
        elseBlock: TypedBlock?,
    ): List<TypedStmt> {
        for ((i, branch) in elseIfs.withIndex()) {
            val cond = foldExpr(branch.condition)
            if (cond is TypedBoolLiteralExpr) {
                if (cond.value) {
                    return foldBlock(branch.body).statements
                }
                // false then skip this branch, continue to next
            } else {
                // Non-static condition, rebuild from this branch onward as a new if
                val remaining = elseIfs.subList(i + 1, elseIfs.size)
                val foldedBody = foldBlock(branch.body)
                val foldedRemaining = foldElseIfs(remaining)
                val foldedElse = elseBlock?.let { foldBlock(it) }
                return listOf(TypedIfStmt(cond, foldedBody, foldedRemaining, foldedElse, branch.loc))
            }
        }
        // All else-ifs were false
        return if (elseBlock != null) foldBlock(elseBlock).statements else emptyList()
    }

    private fun foldElseIfs(elseIfs: List<TypedIfStmt.ElseIf>): List<TypedIfStmt.ElseIf> {
        var changed = false
        val result =
            elseIfs.map { branch ->
                val cond = foldExpr(branch.condition)
                val body = foldBlock(branch.body)
                if (cond === branch.condition && body === branch.body) {
                    branch
                } else {
                    changed = true
                    TypedIfStmt.ElseIf(cond, body, branch.loc)
                }
            }
        return if (changed) result else elseIfs
    }

    fun foldExpr(expr: TypedExpr): TypedExpr =
        when (expr) {
            is TypedBinaryExpr -> foldBinary(expr)
            is TypedUnaryExpr -> foldUnary(expr)
            else -> expr
        }

    private fun foldBinary(expr: TypedBinaryExpr): TypedExpr {
        val left = foldExpr(expr.left)
        val right = foldExpr(expr.right)

        // Int op Int
        if (left is TypedIntLiteralExpr && right is TypedIntLiteralExpr) {
            foldIntBinary(left.value, expr.op, right.value, expr)?.let { return it }
        }

        // Double op Double
        if (left is TypedDoubleLiteralExpr && right is TypedDoubleLiteralExpr) {
            foldDoubleBinary(left.value, expr.op, right.value, expr)?.let { return it }
        }

        // Int op Double (widening left)
        if (left is TypedIntLiteralExpr && right is TypedDoubleLiteralExpr) {
            foldDoubleBinary(left.value.toDouble(), expr.op, right.value, expr)?.let { return it }
        }

        // Double op Int (widening right)
        if (left is TypedDoubleLiteralExpr && right is TypedIntLiteralExpr) {
            foldDoubleBinary(left.value, expr.op, right.value.toDouble(), expr)?.let { return it }
        }

        // Bool op Bool (&&, ||)
        if (left is TypedBoolLiteralExpr && right is TypedBoolLiteralExpr) {
            foldBoolBinary(left.value, expr.op, right.value, expr)?.let { return it }
        }

        // String + String
        if (left is TypedStringLiteralExpr && right is TypedStringLiteralExpr && expr.op == BinaryOp.ADD) {
            return TypedStringLiteralExpr(left.value + right.value, expr.loc, TypeRef.STRING)
        }

        // String == String, String != String
        if (left is TypedStringLiteralExpr && right is TypedStringLiteralExpr) {
            when (expr.op) {
                BinaryOp.EQ -> return TypedBoolLiteralExpr(left.value == right.value, expr.loc, TypeRef.BOOLEAN)
                BinaryOp.NE -> return TypedBoolLiteralExpr(left.value != right.value, expr.loc, TypeRef.BOOLEAN)
                else -> {}
            }
        }

        // Nothing folded, rebuild if children changed
        if (left === expr.left && right === expr.right) return expr
        return TypedBinaryExpr(left, expr.op, right, expr.loc, expr.type)
    }

    private fun foldIntBinary(
        l: Long,
        op: BinaryOp,
        r: Long,
        expr: TypedBinaryExpr,
    ): TypedExpr? =
        when (op) {
            BinaryOp.ADD -> TypedIntLiteralExpr(l + r, expr.loc, TypeRef.INT)
            BinaryOp.SUB -> TypedIntLiteralExpr(l - r, expr.loc, TypeRef.INT)
            BinaryOp.MUL -> TypedIntLiteralExpr(l * r, expr.loc, TypeRef.INT)
            BinaryOp.DIV -> if (r == 0L) null else TypedIntLiteralExpr(l / r, expr.loc, TypeRef.INT)
            BinaryOp.MOD -> if (r == 0L) null else TypedIntLiteralExpr(l % r, expr.loc, TypeRef.INT)
            BinaryOp.EQ -> TypedBoolLiteralExpr(l == r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.NE -> TypedBoolLiteralExpr(l != r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.LT -> TypedBoolLiteralExpr(l < r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.LE -> TypedBoolLiteralExpr(l <= r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.GT -> TypedBoolLiteralExpr(l > r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.GE -> TypedBoolLiteralExpr(l >= r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.BIT_AND -> TypedIntLiteralExpr(l and r, expr.loc, TypeRef.INT)
            BinaryOp.BIT_OR -> TypedIntLiteralExpr(l or r, expr.loc, TypeRef.INT)
            BinaryOp.BIT_XOR -> TypedIntLiteralExpr(l xor r, expr.loc, TypeRef.INT)
            BinaryOp.SHL -> TypedIntLiteralExpr(l shl r.toInt(), expr.loc, TypeRef.INT)
            BinaryOp.SHR -> TypedIntLiteralExpr(l shr r.toInt(), expr.loc, TypeRef.INT)
            BinaryOp.USHR -> TypedIntLiteralExpr(l ushr r.toInt(), expr.loc, TypeRef.INT)
            BinaryOp.AND, BinaryOp.OR -> null // logical ops not valid for int
        }

    private fun foldDoubleBinary(
        l: Double,
        op: BinaryOp,
        r: Double,
        expr: TypedBinaryExpr,
    ): TypedExpr? =
        when (op) {
            BinaryOp.ADD -> TypedDoubleLiteralExpr(l + r, expr.loc, TypeRef.DOUBLE)
            BinaryOp.SUB -> TypedDoubleLiteralExpr(l - r, expr.loc, TypeRef.DOUBLE)
            BinaryOp.MUL -> TypedDoubleLiteralExpr(l * r, expr.loc, TypeRef.DOUBLE)
            BinaryOp.DIV -> if (r == 0.0) null else TypedDoubleLiteralExpr(l / r, expr.loc, TypeRef.DOUBLE)
            BinaryOp.MOD -> if (r == 0.0) null else TypedDoubleLiteralExpr(l % r, expr.loc, TypeRef.DOUBLE)
            BinaryOp.EQ -> TypedBoolLiteralExpr(l == r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.NE -> TypedBoolLiteralExpr(l != r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.LT -> TypedBoolLiteralExpr(l < r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.LE -> TypedBoolLiteralExpr(l <= r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.GT -> TypedBoolLiteralExpr(l > r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.GE -> TypedBoolLiteralExpr(l >= r, expr.loc, TypeRef.BOOLEAN)
            else -> null // bitwise/shift/logical not valid for double
        }

    private fun foldBoolBinary(
        l: Boolean,
        op: BinaryOp,
        r: Boolean,
        expr: TypedBinaryExpr,
    ): TypedExpr? =
        when (op) {
            BinaryOp.AND -> TypedBoolLiteralExpr(l && r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.OR -> TypedBoolLiteralExpr(l || r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.EQ -> TypedBoolLiteralExpr(l == r, expr.loc, TypeRef.BOOLEAN)
            BinaryOp.NE -> TypedBoolLiteralExpr(l != r, expr.loc, TypeRef.BOOLEAN)
            else -> null
        }

    private fun foldUnary(expr: TypedUnaryExpr): TypedExpr {
        val operand = foldExpr(expr.operand)

        when (expr.op) {
            UnaryOp.NEG -> {
                if (operand is TypedIntLiteralExpr) {
                    return TypedIntLiteralExpr(-operand.value, expr.loc, TypeRef.INT)
                }
                if (operand is TypedDoubleLiteralExpr) {
                    return TypedDoubleLiteralExpr(-operand.value, expr.loc, TypeRef.DOUBLE)
                }
            }
            UnaryOp.NOT -> {
                if (operand is TypedBoolLiteralExpr) {
                    return TypedBoolLiteralExpr(!operand.value, expr.loc, TypeRef.BOOLEAN)
                }
            }
            UnaryOp.BIT_NOT -> {
                if (operand is TypedIntLiteralExpr) {
                    return TypedIntLiteralExpr(operand.value.inv(), expr.loc, TypeRef.INT)
                }
            }
        }

        if (operand === expr.operand) return expr
        return TypedUnaryExpr(expr.op, operand, expr.loc, expr.type)
    }
}
