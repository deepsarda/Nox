package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*

class StatementResolver(
    private val exprResolver: ExpressionResolver,
    private val errors: CompilerErrors,
) {
    var isMainBody: Boolean = false

    fun resolveBlock(
        parentScope: SymbolTable,
        block: RawBlock,
        expectedReturn: TypeRef,
    ): TypedBlock {
        val blockScope = parentScope.child()
        val typedStmts = block.statements.map { resolveStmt(blockScope, it, expectedReturn) }.filterNotNull()
        return TypedBlock(typedStmts, block.loc)
    }

    fun resolveStmt(
        scope: SymbolTable,
        stmt: RawStmt,
        expectedReturn: TypeRef,
    ): TypedStmt? =
        when (stmt) {
            is RawVarDeclStmt -> resolveVarDecl(scope, stmt)
            is RawAssignStmt -> resolveAssign(scope, stmt)
            is RawIncrementStmt -> resolveIncrement(scope, stmt)
            is RawIfStmt -> resolveIf(scope, stmt, expectedReturn)
            is RawWhileStmt -> resolveWhile(scope, stmt, expectedReturn)
            is RawForStmt -> resolveFor(scope, stmt, expectedReturn)
            is RawForEachStmt -> resolveForEach(scope, stmt, expectedReturn)
            is RawReturnStmt -> resolveReturn(scope, stmt, expectedReturn)
            is RawYieldStmt -> resolveYield(scope, stmt)
            is RawThrowStmt -> resolveThrow(scope, stmt)
            is RawTryCatchStmt -> resolveTryCatch(scope, stmt, expectedReturn)
            is RawExprStmt -> {
                val expr = exprResolver.resolveExpr(scope, stmt.expression)
                TypedExprStmt(expr, stmt.loc)
            }
            is RawBlock -> resolveBlock(scope, stmt, expectedReturn)
            is RawBreakStmt -> TypedBreakStmt(stmt.loc)
            is RawContinueStmt -> TypedContinueStmt(stmt.loc)
            is RawErrorStmt -> TypedErrorStmt(stmt.loc)
        }

    private fun resolveVarDecl(
        scope: SymbolTable,
        stmt: RawVarDeclStmt,
    ): TypedVarDeclStmt? {
        if (!stmt.type.isValidAsVariable()) {
            errors.report(
                stmt.loc,
                "Cannot declare variable '${stmt.name}' with type '${stmt.type}'. 'void' is not a valid variable type",
                suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
            )
        }
        val typedInit = exprResolver.resolveExpr(scope, stmt.initializer, stmt.type)

        if (!stmt.type.isAssignableFrom(typedInit.type)) {
            val note =
                if (typedInit.type == TypeRef.NULL &&
                    !stmt.type.isNullable()
                ) {
                    " ('${stmt.type}' is not nullable)"
                } else {
                    ""
                }
            errors.report(
                stmt.loc,
                "Variable type mismatch: variable '${stmt.name}' is declared as '${stmt.type}' but initializer has type '${typedInit.type}'$note",
                suggestion = DiagnosticHelpers.conversionHint(typedInit.type, stmt.type),
            )
        }

        val symbol = VarSymbol(stmt.name, stmt.type, scope.depth)
        if (!scope.define(stmt.name, symbol)) {
            errors.report(
                stmt.loc,
                "Variable '${stmt.name}' is already declared in this scope",
            )
        }

        return TypedVarDeclStmt(stmt.type, stmt.name, typedInit, stmt.loc, symbol)
    }

    private fun resolveAssign(
        scope: SymbolTable,
        stmt: RawAssignStmt,
    ): TypedAssignStmt? {
        val typedTarget = exprResolver.resolveExpr(scope, stmt.target)
        val typedValue = exprResolver.resolveExpr(scope, stmt.value, typedTarget.type)

        if (typedTarget !is TypedIdentifierExpr &&
            typedTarget !is TypedFieldAccessExpr &&
            typedTarget !is TypedIndexAccessExpr
        ) {
            errors.report(stmt.loc, "Invalid assignment target: expression is not a valid assignment target")
        }

        if (stmt.op != AssignOp.ASSIGN) {
            val binaryOp =
                when (stmt.op) {
                    AssignOp.ADD_ASSIGN -> BinaryOp.ADD
                    AssignOp.SUB_ASSIGN -> BinaryOp.SUB
                    AssignOp.MUL_ASSIGN -> BinaryOp.MUL
                    AssignOp.DIV_ASSIGN -> BinaryOp.DIV
                    AssignOp.MOD_ASSIGN -> BinaryOp.MOD
                }
            // check operators compatibility. exprResolver checks it internally if we resolve a binary expr.
            // But we already have the typedTarget and typedValue.
            // Simple check
            if (!typedTarget.type.isNumeric() || !typedValue.type.isNumeric()) {
                if (stmt.op == AssignOp.ADD_ASSIGN &&
                    typedTarget.type == TypeRef.STRING &&
                    typedValue.type == TypeRef.STRING
                ) {
                    // String concatenation is allowed
                } else {
                    errors.report(stmt.loc, "Operator type mismatch: ${stmt.op} requires numeric or string operands")
                }
            } else {
                // Numeric compound assign: check assignability (e.g. int += double is narrowing)
                if (!typedTarget.type.isAssignableFrom(typedValue.type)) {
                    errors.report(
                        stmt.loc,
                        "Compound assignment type mismatch: cannot narrow '${typedValue.type}' to '${typedTarget.type}' via ${stmt.op}",
                        suggestion = "Use an explicit cast if narrowing is intended",
                    )
                }
            }
        } else {
            if (!typedTarget.type.isAssignableFrom(typedValue.type)) {
                errors.report(
                    stmt.loc,
                    "Variable type mismatch: cannot assign '${typedValue.type}' to '${typedTarget.type}'",
                )
            }
        }
        return TypedAssignStmt(typedTarget, stmt.op, typedValue, stmt.loc)
    }

    private fun resolveIncrement(
        scope: SymbolTable,
        stmt: RawIncrementStmt,
    ): TypedIncrementStmt? {
        val typedTarget = exprResolver.resolveExpr(scope, stmt.target)
        if (!typedTarget.type.isNumeric()) {
            errors.report(stmt.loc, "Increment/decrement requires numeric target")
        }
        return TypedIncrementStmt(typedTarget, stmt.op, stmt.loc)
    }

    private fun resolveIf(
        scope: SymbolTable,
        stmt: RawIfStmt,
        expectedReturn: TypeRef,
    ): TypedIfStmt? {
        val cond = exprResolver.resolveExpr(scope, stmt.condition)
        if (cond.type != TypeRef.BOOLEAN) {
            errors.report(
                stmt.condition.loc,
                "Condition type mismatch: if condition must be 'boolean', got '${cond.type}'",
            )
        }
        val thenBlock = resolveBlock(scope, stmt.thenBlock, expectedReturn)
        val elseIfs =
            stmt.elseIfs.map {
                val eiCond = exprResolver.resolveExpr(scope, it.condition)
                if (eiCond.type != TypeRef.BOOLEAN) {
                    errors.report(
                        it.condition.loc,
                        "Condition type mismatch: if condition must be 'boolean', got '${eiCond.type}'",
                    )
                }
                val eiBody = resolveBlock(scope, it.body, expectedReturn)
                TypedIfStmt.ElseIf(eiCond, eiBody, it.loc)
            }
        val elseBlock = stmt.elseBlock?.let { resolveBlock(scope, it, expectedReturn) }
        return TypedIfStmt(cond, thenBlock, elseIfs, elseBlock, stmt.loc)
    }

    private fun resolveWhile(
        scope: SymbolTable,
        stmt: RawWhileStmt,
        expectedReturn: TypeRef,
    ): TypedWhileStmt? {
        val cond = exprResolver.resolveExpr(scope, stmt.condition)
        if (cond.type != TypeRef.BOOLEAN) {
            errors.report(
                stmt.condition.loc,
                "Condition type mismatch: while condition must be 'boolean', got '${cond.type}'",
            )
        }
        val body = resolveBlock(scope, stmt.body, expectedReturn)
        return TypedWhileStmt(cond, body, stmt.loc)
    }

    private fun resolveFor(
        scope: SymbolTable,
        stmt: RawForStmt,
        expectedReturn: TypeRef,
    ): TypedForStmt? {
        val forScope = scope.child()
        val init = stmt.init?.let { resolveStmt(forScope, it, expectedReturn) }
        val cond = stmt.condition?.let { exprResolver.resolveExpr(forScope, it) }
        if (cond != null && cond.type != TypeRef.BOOLEAN) {
            errors.report(
                cond.loc,
                "Condition type mismatch: for condition must be 'boolean', got '${cond.type}'",
            )
        }
        val update = stmt.update?.let { resolveStmt(forScope, it, expectedReturn) }
        val body = resolveBlock(forScope, stmt.body, expectedReturn)
        return TypedForStmt(init, cond, update, body, stmt.loc)
    }

    private fun resolveForEach(
        scope: SymbolTable,
        stmt: RawForEachStmt,
        expectedReturn: TypeRef,
    ): TypedForEachStmt? {
        val iterable = exprResolver.resolveExpr(scope, stmt.iterable)
        if (!iterable.type.isArray && iterable.type != TypeRef.STRING) {
            errors.report(
                stmt.iterable.loc,
                "Foreach type mismatch: 'foreach' requires an array type or string, got '${iterable.type}'",
            )
        }
        if (stmt.elementType == TypeRef.VOID) {
            errors.report(
                stmt.loc,
                "Foreach type mismatch: Invalid type 'void' for foreach element '${stmt.elementName}'",
            )
        }
        val elemType = if (iterable.type.isArray) iterable.type.elementType() else TypeRef.STRING
        if (!stmt.elementType.isAssignableFrom(elemType)) {
            errors.report(
                stmt.loc,
                "Foreach type mismatch: Element type mismatch: declared '${stmt.elementType}' but the array contains '$elemType'",
            )
        }
        val loopScope = scope.child()
        val sym = VarSymbol(stmt.elementName, stmt.elementType, loopScope.depth)
        loopScope.define(stmt.elementName, sym)
        val body = resolveBlock(loopScope, stmt.body, expectedReturn)
        return TypedForEachStmt(stmt.elementType, stmt.elementName, iterable, body, stmt.loc, sym)
    }

    private fun resolveReturn(
        scope: SymbolTable,
        stmt: RawReturnStmt,
        expectedReturn: TypeRef,
    ): TypedReturnStmt? {
        val value = stmt.value?.let { exprResolver.resolveExpr(scope, it, expectedReturn) }
        val valType = value?.type ?: TypeRef.VOID
        if (!isMainBody && !expectedReturn.isAssignableFrom(valType)) {
            errors.report(stmt.loc, "Return type mismatch: expected $expectedReturn, got $valType")
        }
        return TypedReturnStmt(value, stmt.loc)
    }

    private fun resolveYield(
        scope: SymbolTable,
        stmt: RawYieldStmt,
    ): TypedYieldStmt? {
        val value = exprResolver.resolveExpr(scope, stmt.value)
        return TypedYieldStmt(value, stmt.loc)
    }

    private fun resolveThrow(
        scope: SymbolTable,
        stmt: RawThrowStmt,
    ): TypedThrowStmt? {
        val value = exprResolver.resolveExpr(scope, stmt.value)
        if (value.type != TypeRef.STRING) {
            errors.report(stmt.value.loc, "Throw type mismatch: 'throw' requires a string message, got '${value.type}'")
        }
        return TypedThrowStmt(value, stmt.loc)
    }

    private fun resolveTryCatch(
        scope: SymbolTable,
        stmt: RawTryCatchStmt,
        expectedReturn: TypeRef,
    ): TypedTryCatchStmt? {
        val tryBlock = resolveBlock(scope, stmt.tryBlock, expectedReturn)
        val catchClauses =
            stmt.catchClauses.map {
                val catchScope = scope.child()
                // In Nox, all exceptions are strings currently
                val sym = VarSymbol(it.variableName, TypeRef.STRING, catchScope.depth)
                catchScope.define(it.variableName, sym)
                val cbody = resolveBlock(catchScope, it.body, expectedReturn)
                TypedCatchClause(it.exceptionType, it.variableName, cbody, it.loc, sym)
            }
        return TypedTryCatchStmt(tryBlock, catchClauses, stmt.loc)
    }
}
