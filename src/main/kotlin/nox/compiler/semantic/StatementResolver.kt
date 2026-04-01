package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
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
     * When `true`, the current body being resolved is `main()`.
     * `main` can return any type (the runtime auto-converts to string),
     * so return-type checks are skipped. Set by [TypeResolver.resolveMain].
     */
    var isMainBody: Boolean = false

    /**
     * Resolve a [block] in a new child scope.
     *
     * @param parentScope   the enclosing scope
     * @param block         the block to resolve
     * @param expectedReturn the return type expected by the enclosing function
     */
    fun resolveBlock(
        parentScope: SymbolTable,
        block: Block,
        expectedReturn: TypeRef,
    ) {
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
    fun resolveStmt(
        scope: SymbolTable,
        stmt: Stmt,
        expectedReturn: TypeRef,
    ) {
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

    private fun resolveVarDecl(
        scope: SymbolTable,
        stmt: VarDeclStmt,
    ) {
        if (!stmt.type.isValidAsVariable()) {
            errors.report(
                stmt.loc,
                "Cannot declare variable '${stmt.name}' with type '${stmt.type}'. 'void' is not a valid variable type",
                suggestion = "Use a concrete type: int, double, boolean, string, json, or a struct type",
            )
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
            val elementType = stmt.type.elementType()

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
                "Type mismatch: '${initType ?: "null"}' cannot be assigned to '${stmt.name}' (declared as '${stmt.type}')",
                suggestion = DiagnosticHelpers.conversionHint(initType, stmt.type),
            )
        }

        // Null safety check
        if (stmt.initializer is NullLiteralExpr && !stmt.type.isNullable()) {
            val defaultVal = DiagnosticHelpers.defaultValueHint(stmt.type)
            errors.report(
                stmt.loc,
                "Cannot assign 'null' to '${stmt.name}'. " +
                    "'${stmt.type}' is not nullable (only string, json, structs, and arrays can be null)",
                suggestion = "Use a default value instead: '${stmt.type} ${stmt.name} = $defaultVal;'",
            )
        }

        // Define in scope
        val sym = VarSymbol(stmt.name, stmt.type, scope.depth)
        if (!scope.define(stmt.name, sym)) {
            errors.report(
                stmt.loc,
                "Variable '${stmt.name}' is already declared in this scope",
                suggestion =
                    "Rename this variable or use the existing one. " +
                        "Note: shadowing is allowed in nested '{ }' blocks",
            )
        } else {
            stmt.resolvedSymbol = sym // back-link so codegen can write to sym.register
        }
    }

    private fun resolveAssign(
        scope: SymbolTable,
        stmt: AssignStmt,
    ) {
        validateLValue(stmt.target)

        val targetType = exprResolver.resolveExpr(scope, stmt.target) ?: return
        val valueType = exprResolver.resolveExpr(scope, stmt.value) ?: return

        if (stmt.op == AssignOp.ASSIGN) {
            // Simple assignment
            if (!targetType.isAssignableFrom(valueType)) {
                errors.report(
                    stmt.loc,
                    "Type mismatch: cannot assign '$valueType' to '$targetType'",
                    suggestion = DiagnosticHelpers.conversionHint(valueType, targetType),
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
                if (targetType.isNumeric() && valueType.isNumeric()) {
                    // Prevent int += double (narrowing)
                    if (targetType == TypeRef.INT && valueType == TypeRef.DOUBLE) {
                        errors.report(
                            loc,
                            "'${op.symbol}' would silently narrow 'double' to 'int'",
                            suggestion = "Use an explicit cast: 'x = (x + value).toInt();'",
                        )
                    }
                    return
                }
                val suggestion =
                    when {
                        targetType == TypeRef.STRING || valueType == TypeRef.STRING ->
                            "Use template literals for mixed-type concatenation: `\${var}\${value}`"
                        else -> null
                    }
                errors.report(
                    loc,
                    "'${op.symbol}' requires numeric or string operands, got '$targetType' and '$valueType'",
                    suggestion = suggestion,
                )
            }

            AssignOp.SUB_ASSIGN, AssignOp.MUL_ASSIGN, AssignOp.DIV_ASSIGN, AssignOp.MOD_ASSIGN -> {
                if (!targetType.isNumeric() || !valueType.isNumeric()) {
                    errors.report(
                        loc,
                        "'${op.symbol}' requires numeric operands, got '$targetType' and '$valueType'",
                    )
                } else if (targetType == TypeRef.INT && valueType == TypeRef.DOUBLE) {
                    errors.report(
                        loc,
                        "'${op.symbol}' would silently narrow 'double' to 'int'",
                        suggestion = "Use an explicit cast: 'x = (x ${op.symbol.dropLast(1)} value).toInt();'",
                    )
                }
            }

            AssignOp.ASSIGN -> {} // Already handled above
        }
    }

    private fun resolveIncrement(
        scope: SymbolTable,
        stmt: IncrementStmt,
    ) {
        validateLValue(stmt.target)
        val type = exprResolver.resolveExpr(scope, stmt.target) ?: return
        if (!type.isNumeric()) {
            val opSymbol = if (stmt.op == PostfixOp.INCREMENT) "++" else "--"
            errors.report(
                stmt.loc,
                "Cannot apply '$opSymbol' to '$type'. Only 'int' and 'double' support increment/decrement",
            )
        }
    }

    private fun resolveIf(
        scope: SymbolTable,
        stmt: IfStmt,
        expectedReturn: TypeRef,
    ) {
        requireBoolean(scope, stmt.condition, "if")
        resolveBlock(scope, stmt.thenBlock, expectedReturn)

        for (elseIf in stmt.elseIfs) {
            requireBoolean(scope, elseIf.condition, "else-if")
            resolveBlock(scope, elseIf.body, expectedReturn)
        }

        stmt.elseBlock?.let { resolveBlock(scope, it, expectedReturn) }
    }

    private fun resolveWhile(
        scope: SymbolTable,
        stmt: WhileStmt,
        expectedReturn: TypeRef,
    ) {
        requireBoolean(scope, stmt.condition, "while")
        resolveBlock(scope, stmt.body, expectedReturn)
    }

    private fun resolveFor(
        scope: SymbolTable,
        stmt: ForStmt,
        expectedReturn: TypeRef,
    ) {
        val forScope = scope.child()
        stmt.init?.let { resolveStmt(forScope, it, expectedReturn) }
        stmt.condition?.let { requireBoolean(forScope, it, "for") }
        stmt.update?.let { resolveStmt(forScope, it, expectedReturn) }
        resolveBlock(forScope, stmt.body, expectedReturn)
    }

    private fun resolveForEach(
        scope: SymbolTable,
        stmt: ForEachStmt,
        expectedReturn: TypeRef,
    ) {
        val iterType = exprResolver.resolveExpr(scope, stmt.iterable) ?: return

        if (!iterType.isArray) {
            errors.report(
                stmt.loc,
                "'foreach' requires an array type, but got '$iterType'",
                suggestion = "Wrap the value in an array literal: '[$iterType]', or use a 'for' loop instead",
            )
            return
        }

        if (!stmt.elementType.isValidAsVariable()) {
            errors.report(
                stmt.loc,
                "Invalid type '${stmt.elementType}' for foreach element '${stmt.elementName}'. 'void' is not allowed",
            )
        }

        // Extract element type from array type
        val elemType = iterType.elementType()

        // Check that declared element type matches
        if (!stmt.elementType.isAssignableFrom(elemType)) {
            errors.report(
                stmt.loc,
                "Element type mismatch: declared '${stmt.elementType}' but the array contains '$elemType' elements",
                suggestion = "Change the element type to '$elemType': 'foreach ($elemType ${stmt.elementName} in ...)'",
            )
        }

        val feScope = scope.child()
        if (!feScope.define(stmt.elementName, VarSymbol(stmt.elementName, stmt.elementType, feScope.depth))) {
            errors.report(
                stmt.loc,
                "Variable '${stmt.elementName}' is already declared in this scope",
                suggestion = "Choose a different name for the foreach element variable",
            )
        }
        resolveBlock(feScope, stmt.body, expectedReturn)
    }

    private fun resolveReturn(
        scope: SymbolTable,
        stmt: ReturnStmt,
        expectedReturn: TypeRef,
    ) {
        if (stmt.value != null) {
            // Propagate the expected return type into struct/array literals
            val value = stmt.value
            if (value is StructLiteralExpr && value.structType == null) {
                if (expectedReturn.isStructType() || expectedReturn == TypeRef.JSON) {
                    value.structType = expectedReturn
                }
            }
            // Propagate into struct-literal elements of an array return, e.g.:
            //   Point[] make() { return [{ x: 1, y: 2 }]; }
            if (value is ArrayLiteralExpr && expectedReturn.isArray) {
                val elemType = expectedReturn.elementType()
                for (elem in value.elements) {
                    if (elem is StructLiteralExpr && elem.structType == null) {
                        if (elemType.isStructType() || elemType == TypeRef.JSON) {
                            elem.structType = elemType
                        }
                    }
                }
            }

            val returnType = exprResolver.resolveExpr(scope, stmt.value)
            // main() can return anything as the runtime auto-converts to string.
            // For all other functions (including void), validate the return type.
            if (!isMainBody && !expectedReturn.isAssignableFrom(returnType)) {
                errors.report(
                    stmt.loc,
                    "Return type mismatch: expected '$expectedReturn', got '${returnType ?: "null"}'",
                    suggestion = DiagnosticHelpers.conversionHint(returnType, expectedReturn),
                )
            }
        } else if (expectedReturn != TypeRef.VOID && !isMainBody) {
            errors.report(
                stmt.loc,
                "Function must return '$expectedReturn' but this 'return' has no value",
                suggestion = "Add a return expression: 'return ${DiagnosticHelpers.defaultValueHint(expectedReturn)};'",
            )
        }
    }

    private fun resolveYield(
        scope: SymbolTable,
        stmt: YieldStmt,
    ) {
        // Any type can be yielded, the runtime will handle conversion to string
        exprResolver.resolveExpr(scope, stmt.value)
    }

    private fun resolveThrow(
        scope: SymbolTable,
        stmt: ThrowStmt,
    ) {
        val type = exprResolver.resolveExpr(scope, stmt.value)
        if (type != null && type != TypeRef.STRING) {
            errors.report(
                stmt.loc,
                "'throw' requires a string message, got '$type'",
                suggestion = "Convert to string: 'throw `Error: \${value}`;' or 'throw value.toString();'",
            )
        }
    }

    private fun resolveTryCatch(
        scope: SymbolTable,
        stmt: TryCatchStmt,
        expectedReturn: TypeRef,
    ) {
        resolveBlock(scope, stmt.tryBlock, expectedReturn)

        for (cc in stmt.catchClauses) {
            val catchScope = scope.child()
            val sym = VarSymbol(cc.variableName, TypeRef.STRING, catchScope.depth)
            catchScope.define(cc.variableName, sym)
            cc.resolvedSymbol = sym
            resolveBlock(catchScope, cc.body, expectedReturn)
        }
    }

    /**
     * Resolve an expression and require it to be boolean.
     * Reports an error if the expression resolves to a non-boolean type.
     */
    private fun requireBoolean(
        scope: SymbolTable,
        expr: Expr,
        context: String,
    ) {
        val type = exprResolver.resolveExpr(scope, expr)
        if (type != null && type != TypeRef.BOOLEAN) {
            val suggestion =
                when {
                    type.isNumeric() -> "Did you mean a comparison? e.g. 'value != 0'"
                    type == TypeRef.STRING -> "Did you mean a comparison? e.g. 'value != null' or 'value.length() > 0'"
                    type.isNullable() -> "Did you mean a null check? e.g. 'value != null'"
                    else -> null
                }
            errors.report(
                expr.loc,
                "$context condition must be 'boolean', got '$type'",
                suggestion = suggestion,
            )
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
            else -> {
                val exprKind =
                    when (target) {
                        is IntLiteralExpr, is DoubleLiteralExpr -> "a literal value"
                        is StringLiteralExpr, is TemplateLiteralExpr -> "a string literal"
                        is BoolLiteralExpr -> "a boolean literal"
                        is FuncCallExpr -> "a function call result"
                        is MethodCallExpr -> "a method call result"
                        is BinaryExpr -> "a binary expression"
                        is UnaryExpr -> "a unary expression"
                        else -> "this expression"
                    }
                errors.report(
                    target.loc,
                    "$exprKind is not a valid assignment target",
                    suggestion = "Only variables, field accesses (a.b), and index accesses (a[i]) can be assigned to",
                )
            }
        }
    }
}
