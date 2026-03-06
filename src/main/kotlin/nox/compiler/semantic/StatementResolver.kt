package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.types.*

/**
 * Resolves types and validates all [Stmt] nodes in the AST.
 *
 * Each statement is validated in the context of its enclosing scope and
 * expected return type. New variables are defined in the scope, l-values
 * are validated, and type assignability is checked.
 *
 * See docs/compiler/semantic-analysis.md
 *
 * @property exprResolver the expression resolver for resolving sub-expressions
 * @property errors       shared error collector
 */
class StatementResolver(
    private val exprResolver: ExpressionResolver,
    private val errors: CompilerErrors,
) {

    /**
     * Resolve a [block] in a new child scope.
     *
     * @param parentScope   the enclosing scope
     * @param block         the block to resolve
     * @param expectedReturn the return type expected by the enclosing function
     */
    fun resolveBlock(parentScope: SymbolTable, block: Block, expectedReturn: TypeRef) {
        val blockScope = parentScope.child()
        block.scopeDepth = blockScope.depth
        for (stmt in block.statements) {
            resolveStmt(blockScope, stmt, expectedReturn)
        }
    }

    /**
     * Resolve a single statement.
     *
     * @param scope          current scope
     * @param stmt           the statement to resolve
     * @param expectedReturn the return type expected by the enclosing function
     */
    fun resolveStmt(scope: SymbolTable, stmt: Stmt, expectedReturn: TypeRef) {
        when (stmt) {
            is VarDeclStmt -> resolveVarDecl(scope, stmt)
            is AssignStmt -> resolveAssign(scope, stmt)
            is IncrementStmt -> resolveIncrement(scope, stmt)
            is IfStmt -> resolveIf(scope, stmt, expectedReturn)
            is WhileStmt -> resolveWhile(scope, stmt, expectedReturn)
            is ForStmt -> resolveFor(scope, stmt, expectedReturn)
            is ForEachStmt -> resolveForEach(scope, stmt, expectedReturn)
            is ReturnStmt -> resolveReturn(scope, stmt, expectedReturn)
            is YieldStmt -> resolveYield(scope, stmt)
            is ThrowStmt -> resolveThrow(scope, stmt)
            is TryCatchStmt -> resolveTryCatch(scope, stmt, expectedReturn)
            is ExprStmt -> exprResolver.resolveExpr(scope, stmt.expression)
            is Block -> resolveBlock(scope, stmt, expectedReturn)
            is BreakStmt, is ContinueStmt -> {} // Validated in Pass 3
            is ErrorStmt -> {} // Already reported during parsing
        }
    }

    private fun resolveVarDecl(scope: SymbolTable, stmt: VarDeclStmt) {
        if (!stmt.type.isValidAsVariable()) {
            errors.report(stmt.loc, "Invalid type '${stmt.type}' for variable '${stmt.name}'")
        }
        val init = stmt.initializer

        // If the initializer is a struct literal, set its type from the declaration
        // This covers both struct types (e.g. Point p = {...}) and json (json a = {...})
        if (init is StructLiteralExpr) {
            if (stmt.type.isStructType() || stmt.type == TypeRef.JSON) {
                init.structType = stmt.type
            }
        }

        // If the initializer is an array literal, propagate context into elements
        if (init is ArrayLiteralExpr && stmt.type.isArray) {
            val elementType = TypeRef(stmt.type.name)

            // Empty array: infer element type
            if (init.elements.isEmpty()) {
                init.elementType = elementType
            }

            // Propagate struct/json type into struct literal elements
            // e.g. json[] arr = [{ok: true}, p] or Point[] pts = [{x:1, y:2}]
            for (elem in init.elements) {
                if (elem is StructLiteralExpr && elem.structType == null) {
                    elem.structType = elementType
                }
            }
        }

        val initType = exprResolver.resolveExpr(scope, stmt.initializer)

        // Check assignability
        if (!stmt.type.isAssignableFrom(initType)) {
            errors.report(
                stmt.loc,
                "Type mismatch: cannot assign '${initType ?: "null"}' to '${stmt.type}'",
                suggestion = if (initType == TypeRef.INT && stmt.type == TypeRef.STRING) {
                    "Use `.toString()` to convert"
                } else {
                    null
                },
            )
        }

        // Null safety check
        if (stmt.initializer is NullLiteralExpr && !stmt.type.isNullable()) {
            errors.report(
                stmt.loc,
                "Cannot assign null to non-nullable type '${stmt.type}'",
                suggestion = "Use a default value instead",
            )
        }

        // Define in scope
        if (!scope.define(stmt.name, VarSymbol(stmt.name, stmt.type, scope.depth))) {
            errors.report(stmt.loc, "Variable '${stmt.name}' is already declared in this scope")
        }
    }

    private fun resolveAssign(scope: SymbolTable, stmt: AssignStmt) {
        validateLValue(stmt.target)

        val targetType = exprResolver.resolveExpr(scope, stmt.target) ?: return
        val valueType = exprResolver.resolveExpr(scope, stmt.value) ?: return

        if (stmt.op == AssignOp.ASSIGN) {
            // Simple assignment
            if (!targetType.isAssignableFrom(valueType)) {
                errors.report(
                    stmt.loc,
                    "Type mismatch: cannot assign '$valueType' to '$targetType'",
                )
            }
        } else {
            // Compound assignment: +=, -=, *=, /=, %=
            checkCompoundAssign(targetType, valueType, stmt.op, stmt.loc)
        }
    }

    /**
     * Validates compound assignment operators (+=, -=, *=, /=, %=).
     * Both operands must be numeric for -=, *=, /=, %=.
     * For +=, also allows string += string.
     */
    private fun checkCompoundAssign(
        targetType: TypeRef,
        valueType: TypeRef,
        op: AssignOp,
        loc: SourceLocation,
    ) {
        when (op) {
            AssignOp.ADD_ASSIGN -> {
                if (targetType == TypeRef.STRING && valueType == TypeRef.STRING) return
                if (targetType.isNumeric() && valueType.isNumeric()) return
                errors.report(loc, "Operator '${op.symbol}' requires numeric or string operands, got '$targetType' and '$valueType'")
            }

            AssignOp.SUB_ASSIGN, AssignOp.MUL_ASSIGN, AssignOp.DIV_ASSIGN, AssignOp.MOD_ASSIGN -> {
                if (!targetType.isNumeric() || !valueType.isNumeric()) {
                    errors.report(loc, "Operator '${op.symbol}' requires numeric operands, got '$targetType' and '$valueType'")
                }
            }

            AssignOp.ASSIGN -> {} // Already handled above
        }
    }

    private fun resolveIncrement(scope: SymbolTable, stmt: IncrementStmt) {
        validateLValue(stmt.target)
        val type = exprResolver.resolveExpr(scope, stmt.target) ?: return
        if (!type.isNumeric()) {
            errors.report(stmt.loc, "Cannot increment/decrement non-numeric type '$type'")
        }
    }

    private fun resolveIf(scope: SymbolTable, stmt: IfStmt, expectedReturn: TypeRef) {
        requireBoolean(scope, stmt.condition, "if condition")
        resolveBlock(scope, stmt.thenBlock, expectedReturn)

        for (elseIf in stmt.elseIfs) {
            requireBoolean(scope, elseIf.condition, "else-if condition")
            resolveBlock(scope, elseIf.body, expectedReturn)
        }

        stmt.elseBlock?.let { resolveBlock(scope, it, expectedReturn) }
    }

    private fun resolveWhile(scope: SymbolTable, stmt: WhileStmt, expectedReturn: TypeRef) {
        requireBoolean(scope, stmt.condition, "while condition")
        resolveBlock(scope, stmt.body, expectedReturn)
    }

    private fun resolveFor(scope: SymbolTable, stmt: ForStmt, expectedReturn: TypeRef) {
        val forScope = scope.child()
        stmt.init?.let { resolveStmt(forScope, it, expectedReturn) }
        stmt.condition?.let { requireBoolean(forScope, it, "for condition") }
        stmt.update?.let { resolveStmt(forScope, it, expectedReturn) }
        resolveBlock(forScope, stmt.body, expectedReturn)
    }

    private fun resolveForEach(scope: SymbolTable, stmt: ForEachStmt, expectedReturn: TypeRef) {
        val iterType = exprResolver.resolveExpr(scope, stmt.iterable) ?: return

        if (!iterType.isArray) {
            errors.report(stmt.loc, "foreach requires an array, got '$iterType'")
            return
        }

        if (!stmt.elementType.isValidAsVariable()) {
            errors.report(stmt.loc, "Invalid type '${stmt.elementType}' for foreach element '${stmt.elementName}'")
        }

        // Extract element type from array type
        val elemType = TypeRef(iterType.name)

        // Check that declared element type matches
        if (!stmt.elementType.isAssignableFrom(elemType)) {
            errors.report(
                stmt.loc,
                "foreach element type mismatch: declared '${stmt.elementType}', array element is '$elemType'",
            )
        }

        val feScope = scope.child()
        feScope.define(stmt.elementName, VarSymbol(stmt.elementName, stmt.elementType, feScope.depth))
        resolveBlock(feScope, stmt.body, expectedReturn)
    }


    private fun resolveReturn(scope: SymbolTable, stmt: ReturnStmt, expectedReturn: TypeRef) {
        if (stmt.value != null) {
            val returnType = exprResolver.resolveExpr(scope, stmt.value)
            if (!expectedReturn.isAssignableFrom(returnType)) {
                errors.report(
                    stmt.loc,
                    "Return type mismatch: expected '$expectedReturn', got '${returnType ?: "null"}'",
                )
            }
        } else if (expectedReturn != TypeRef.VOID) {
            errors.report(stmt.loc, "Missing return value. Expected '$expectedReturn'")
        }
    }

    private fun resolveYield(scope: SymbolTable, stmt: YieldStmt) {
        // Any type can be yielded, the runtime will handle conversion to string
        exprResolver.resolveExpr(scope, stmt.value)
    }

    private fun resolveThrow(scope: SymbolTable, stmt: ThrowStmt) {
        val type = exprResolver.resolveExpr(scope, stmt.value)
        if (type != null && type != TypeRef.STRING) {
            errors.report(stmt.loc, "throw requires a string message, got '$type'")
        }
    }

    private fun resolveTryCatch(scope: SymbolTable, stmt: TryCatchStmt, expectedReturn: TypeRef) {
        resolveBlock(scope, stmt.tryBlock, expectedReturn)

        for (cc in stmt.catchClauses) {
            val catchScope = scope.child()
            catchScope.define(
                cc.variableName,
                VarSymbol(cc.variableName, TypeRef.STRING, catchScope.depth),
            )
            resolveBlock(catchScope, cc.body, expectedReturn)
        }
    }


    /**
     * Resolve an expression and require it to be boolean.
     * Reports an error if the expression resolves to a non-boolean type.
     */
    private fun requireBoolean(scope: SymbolTable, expr: Expr, context: String) {
        val type = exprResolver.resolveExpr(scope, expr)
        if (type != null && type != TypeRef.BOOLEAN) {
            errors.report(expr.loc, "$context requires 'boolean', got '$type'")
        }
    }

    /**
     * Validate that [target] is a valid l-value (assignment target).
     * Only [IdentifierExpr], [FieldAccessExpr], and [IndexAccessExpr] are valid.
     */
    private fun validateLValue(target: Expr) {
        when (target) {
            is IdentifierExpr -> {} // Variable
            is FieldAccessExpr -> {} // Struct field or json property
            is IndexAccessExpr -> {} // Array element or json index
            else -> errors.report(target.loc, "Invalid assignment target. Expected a variable, field, or index")
        }
    }
}
