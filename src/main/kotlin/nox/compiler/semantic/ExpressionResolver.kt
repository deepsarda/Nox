package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.ast.*
import nox.compiler.types.*

/**
 * Resolves the type of every [Expr] node in the AST.
 *
 * This is the core of Pass 2 (Type Resolution). For each expression,
 * [resolveExpr] determines the resulting [TypeRef] and sets
 * `expr.resolvedType`. Identifiers are linked to their declarations
 * via [IdentifierExpr.resolvedSymbol], and method calls are classified
 * into their resolution kind ([MethodCallExpr.Resolution]).
 *
 * See docs/compiler/semantic-analysis.md § Pass 2: Type Resolution.
 *
 * @property globalScope the top-level symbol table (populated by Pass 1)
 * @property errors      shared error collector
 * @property modules     resolved import modules (for Tier 2 namespace lookup)
 */
class ExpressionResolver(
    private val globalScope: SymbolTable,
    private val errors: CompilerErrors,
    private val modules: List<ResolvedModule>,
) {

    /**
     * Resolve the type of [expr] within the given [scope].
     *
     * Sets `expr.resolvedType` and returns the resolved type,
     * or `null` for null literals (type inferred from context).
     */
    fun resolveExpr(scope: SymbolTable, expr: Expr): TypeRef? {
        val type = when (expr) {
            // Literals
            is IntLiteralExpr -> TypeRef.INT
            is DoubleLiteralExpr -> TypeRef.DOUBLE
            is BoolLiteralExpr -> TypeRef.BOOLEAN
            is StringLiteralExpr -> TypeRef.STRING
            is NullLiteralExpr -> null // Type inferred from context
            is TemplateLiteralExpr -> resolveTemplate(scope, expr)

            // Composites
            is ArrayLiteralExpr -> resolveArrayLiteral(scope, expr)
            is StructLiteralExpr -> resolveStructLiteral(scope, expr)

            // References
            is IdentifierExpr -> resolveIdentifier(scope, expr)
            is FieldAccessExpr -> resolveFieldAccess(scope, expr)
            is IndexAccessExpr -> resolveIndexAccess(scope, expr)

            // Calls
            is FuncCallExpr -> resolveFuncCall(scope, expr)
            is MethodCallExpr -> resolveMethodCall(scope, expr)

            // Operators
            is BinaryExpr -> resolveBinary(scope, expr)
            is UnaryExpr -> resolveUnary(scope, expr)
            is PostfixExpr -> resolvePostfix(scope, expr)
            is CastExpr -> resolveCast(scope, expr)

            // Error placeholder, it has already reported during parsing
            is ErrorExpr -> null
        }

        expr.resolvedType = type
        return type
    }

    private fun resolveTemplate(scope: SymbolTable, expr: TemplateLiteralExpr): TypeRef {
        for (part in expr.parts) {
            if (part is TemplatePart.Interpolation) {
                resolveExpr(scope, part.expression)
                // Any type can be interpolated, the runtime converts to string
            }
        }
        return TypeRef.STRING
    }

    private fun resolveArrayLiteral(scope: SymbolTable, expr: ArrayLiteralExpr): TypeRef? {
        if (expr.elements.isEmpty()) {
            // Empty array literal, type must be inferred from context
            // The caller (resolveVarDecl or resolveAssign) handles this
            return null
        }

        val firstType = resolveExpr(scope, expr.elements[0])
        if (firstType == null) {
            errors.report(expr.elements[0].loc, "Array element cannot be null without a known element type")
            return null
        }

        var elementType: TypeRef = firstType

        for (i in 1 until expr.elements.size) {
            val elemType = resolveExpr(scope, expr.elements[i]) ?: continue
            if (elemType == elementType) continue

            // Allow int to double widening in arrays
            if (elementType == TypeRef.DOUBLE && elemType == TypeRef.INT) continue

            if (elementType == TypeRef.INT && elemType == TypeRef.DOUBLE) {
                // Upgrade element type to double, we would need to re-check,
                // but for simplicity we report an error asking for explicit types
                // TODO: in the future, we should allow this and upgrade the element type to double
                errors.report(
                    expr.elements[i].loc,
                    "Mixed array element types: expected '$elementType', found '$elemType'",
                )
                continue
            }

            // Allow struct to json compatibility in mixed arrays
            // e.g. json[] arr = [{ok: true}, someStruct]
            if (elementType == TypeRef.JSON && elemType.isStructType()) continue
            if (elementType.isStructType() && elemType == TypeRef.JSON) {
                elementType = TypeRef.JSON // upgrade to json
                continue
            }

            errors.report(
                expr.elements[i].loc,
                "Mixed array element types: expected '$elementType', found '$elemType'",
            )
        }

        expr.elementType = elementType
        return TypeRef(elementType.name, isArray = true)
    }

    /**
     * Validates a struct literal against its type definition.
     *
     * The struct type must be set by the caller (e.g. from a `VarDeclStmt` or
     * `AssignStmt`) via `expr.structType` before calling this method.
     */
    fun resolveStructLiteral(scope: SymbolTable, expr: StructLiteralExpr): TypeRef? {
        if (expr.structType == null) {
            errors.report(expr.loc, "Cannot infer struct type from context. Use an explicit type.")
            return null
        }

        // json literal: {key: value, ...} with no struct validation
        // json is dynamic so any field names and value types are valid
        if (expr.structType == TypeRef.JSON) {
            for (init in expr.fields) {
                resolveExpr(scope, init.value)
            }
            return TypeRef.JSON
        }

        val typeSym = globalScope.lookup(expr.structType!!.name)

        if (typeSym == null || typeSym !is TypeSymbol) {
            errors.report(expr.loc, "Unknown struct type '${expr.structType!!.name}'")
            return null
        }

        // Check for unknown/mistyped fields
        val provided = mutableSetOf<String>()

        for (init in expr.fields) {
            provided.add(init.name)
            val expectedFieldType = typeSym.fields[init.name]
            if (expectedFieldType == null) {
                errors.report(init.loc, "Unknown field '${init.name}' in struct '${typeSym.name}'")
                continue
            }

            val actualType = resolveExpr(scope, init.value)
            if (!expectedFieldType.isAssignableFrom(actualType)) {
                errors.report(
                    init.loc,
                    "Type mismatch for field '${init.name}': expected '$expectedFieldType', found '${actualType ?: "null"}'",
                )
            }

        }

        // Check for missing required fields
        for (fieldName in typeSym.fields.keys) {
            if (fieldName !in provided) {
                errors.report(expr.loc, "Missing field '$fieldName' in struct '${typeSym.name}'")
            }
        }

        return expr.structType
    }

    private fun resolveIdentifier(scope: SymbolTable, expr: IdentifierExpr): TypeRef? {
        val symbol = scope.lookup(expr.name)
        if (symbol == null) {
            errors.report(expr.loc, "Undefined variable '${expr.name}'")
            return null
        }
        expr.resolvedSymbol = symbol
        return symbol.type
    }

    private fun resolveFieldAccess(scope: SymbolTable, expr: FieldAccessExpr): TypeRef? {
        val targetType = resolveExpr(scope, expr.target) ?: return null

        // json.someField is dynamic property access (type is json)
        if (targetType == TypeRef.JSON) return TypeRef.JSON

        // struct.field is look up field in struct definition
        if (targetType.isStructType() && !targetType.isArray) {
            val structSym = globalScope.lookup(targetType.name)
            if (structSym is TypeSymbol) {
                val fieldType = structSym.fields[expr.fieldName]
                if (fieldType != null) return fieldType
                errors.report(expr.loc, "Struct '${targetType.name}' has no field '${expr.fieldName}'")
                return null
            }
        }

        // string.length, array.length are built-in properties
        if (expr.fieldName == "length") {
            if (targetType == TypeRef.STRING || targetType.isArray) {
                return TypeRef.INT
            }
        }

        errors.report(expr.loc, "Cannot access field '${expr.fieldName}' on type '$targetType'")
        return null
    }

    private fun resolveIndexAccess(scope: SymbolTable, expr: IndexAccessExpr): TypeRef? {
        val targetType = resolveExpr(scope, expr.target) ?: return null
        val indexType = resolveExpr(scope, expr.index) ?: return null

        // json[index] to json (dynamic)
        if (targetType == TypeRef.JSON) return TypeRef.JSON

        // array[int] to element type
        if (targetType.isArray) {
            if (indexType != TypeRef.INT) {
                errors.report(expr.index.loc, "Array index must be 'int', found '$indexType'")
            }
            return TypeRef(targetType.name)
        }

        errors.report(expr.loc, "Cannot index into type '$targetType'")
        return null
    }

    private fun resolveFuncCall(scope: SymbolTable, expr: FuncCallExpr): TypeRef? {
        val symbol = scope.lookup(expr.name)
        if (symbol == null || symbol !is FuncSymbol) {
            errors.report(expr.loc, "Undefined function '${expr.name}'")
            return null
        }

        expr.resolvedFunction = symbol.astNode
        validateArgs(expr.loc, expr.name, paramSpecs(symbol.params), expr.args, scope)
        return symbol.returnType
    }

    /**
     * Resolves `target.method(args)` through the priority chain:
     *
     * 1. **Tier 2 import namespace:** `SymbolTable` has a `ResolvedModule` matching target name
     * 2. **Tier 0/1 built-in namespace:** `TempRegistry.isBuiltinNamespace`
     * 3. **Built-in type method:** `TempRegistry.lookupBuiltinMethod`
     * 4. **Type-bound conversion method:** `TempRegistry.lookupTypeMethod`
     * 5. **UFCS:** Global function whose first param matches target type
     * 6. **Error**
     */
    private fun resolveMethodCall(scope: SymbolTable, call: MethodCallExpr): TypeRef? {
        val target = call.target

        for (arg in call.args) resolveExpr(scope, arg)

        // Step 1 & 2: Namespace function (import or built-in)
        if (target is IdentifierExpr) {
            val namespaceName = target.name

            // Step 1: Tier 2 import namespace
            val module = modules.find { it.namespace == namespaceName }

            if (module != null) {
                val importedFunc = module.program.functionsByName[call.methodName]
                if (importedFunc != null) {
                    val target = CallTarget(
                        name = importedFunc.name,
                        params = importedFunc.params.map { it.name to it.type },
                        returnType = importedFunc.returnType,
                        astNode = importedFunc,
                    )
                    call.resolution = MethodCallExpr.Resolution.NAMESPACE
                    call.resolvedTarget = target

                    validateArgs(call.loc, "${namespaceName}.${call.methodName}", builtinSpecs(target.params), call.args, scope)
                    return importedFunc.returnType
                }
                errors.report(call.loc, "Namespace '${namespaceName}' has no function '${call.methodName}'")
                return null
            }

            // Step 2: Tier 0/1 built-in namespace
            if (TempRegistry.isBuiltinNamespace(namespaceName)) {
                val builtin = TempRegistry.lookupNamespaceFunc(namespaceName, call.methodName)
                if (builtin != null) {
                    call.resolution = MethodCallExpr.Resolution.NAMESPACE
                    call.resolvedTarget = builtin

                    validateArgs(call.loc, "${namespaceName}.${call.methodName}", builtinSpecs(builtin.params), call.args, scope)
                    return builtin.returnType
                }
                errors.report(call.loc, "Namespace '$namespaceName' has no function '${call.methodName}'")
                return null
            }
        }

        // Resolve target type for steps 3–5
        val targetType = resolveExpr(scope, target) ?: return null

        // Step 3: Built-in type method
        val builtinMethod = TempRegistry.lookupBuiltinMethod(targetType, call.methodName)
        if (builtinMethod != null) {
            call.resolution = MethodCallExpr.Resolution.TYPE_BOUND
            call.resolvedTarget = builtinMethod
            validateArgs(call.loc, "${targetType}.${call.methodName}", builtinSpecs(builtinMethod.params), call.args, scope)
            return builtinMethod.returnType
        }

        // Step 4: Type-bound conversion method
        val typeMethod = TempRegistry.lookupTypeMethod(targetType, call.methodName)
        if (typeMethod != null) {
            call.resolution = MethodCallExpr.Resolution.TYPE_BOUND
            call.resolvedTarget = typeMethod
            validateArgs(call.loc, "${targetType}.${call.methodName}", builtinSpecs(typeMethod.params), call.args, scope)
            return typeMethod.returnType
        }

        // Step 5: UFCS, look for global function whose first param type matches
        val ufcsFunc = findUFCSFunction(scope, call.methodName, targetType)
        if (ufcsFunc != null) {
            val ufcsTarget = CallTarget(
                name = ufcsFunc.name,
                params = ufcsFunc.params.map { it.name to it.type },
                returnType = ufcsFunc.returnType,
                astNode = ufcsFunc.astNode,
            )
            call.resolution = MethodCallExpr.Resolution.UFCS
            call.resolvedTarget = ufcsTarget

            // Validate remaining args (first arg is the target itself)
            val remainingParams = ufcsFunc.params.drop(1)
            validateArgs(call.loc, call.methodName, paramSpecs(remainingParams), call.args, scope)
            return ufcsFunc.returnType
        }

        // Step 6: Error
        errors.report(call.loc, "No method '${call.methodName}' found on type '$targetType'")
        return null
    }

    /**
     * Look up a global function suitable for UFCS:
     * the function must have at least one parameter, and its first parameter
     * type must be assignable from [targetType].
     */
    private fun findUFCSFunction(scope: SymbolTable, funcName: String, targetType: TypeRef): FuncSymbol? {
        val symbol = scope.lookup(funcName) ?: return null
        if (symbol !is FuncSymbol) return null
        if (symbol.params.isEmpty()) return null
        val firstParamType = symbol.params[0].type
        if (firstParamType.isAssignableFrom(targetType)) return symbol
        return null
    }

    private fun resolveBinary(scope: SymbolTable, expr: BinaryExpr): TypeRef? {
        val left = resolveExpr(scope, expr.left)
        val right = resolveExpr(scope, expr.right)

        return when (expr.op) {
            // Arithmetic: int×int→int, double×double→double, int×double→double
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> {
                if (left == null || right == null) return null
                when {
                    left == TypeRef.INT && right == TypeRef.INT -> TypeRef.INT
                    left.isNumeric() && right.isNumeric() -> TypeRef.DOUBLE // Implicit widening
                    // String concatenation: string + string
                    expr.op == BinaryOp.ADD && left == TypeRef.STRING && right == TypeRef.STRING -> TypeRef.STRING
                    else -> {
                        errors.report(
                            expr.loc,
                            "Operator '${expr.op.symbol}' requires numeric operands, got '$left' and '$right'",
                        )
                        null
                    }
                }
            }

            // Comparison: numeric×numeric→boolean
            BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
                if (left == null || right == null) return null
                if (!left.isNumeric() || !right.isNumeric()) {
                    errors.report(expr.loc, "Comparison operator '${expr.op.symbol}' requires numeric operands, got '$left' and '$right'")
                }
                TypeRef.BOOLEAN
            }

            // Equality: same type or null comparison→boolean
            BinaryOp.EQ, BinaryOp.NE -> {
                if (left == null && right == null) return TypeRef.BOOLEAN // null == null
                if (left != null && !left.isComparable(right)) {
                    errors.report(expr.loc, "Equality '${expr.op.symbol}' operator cannot compare '$left' with '${right ?: "null"}'")
                }
                if (right != null && !right.isComparable(left)) {
                    errors.report(expr.loc, "Equality '${expr.op.symbol}' operator cannot compare '${left ?: "null"}' with '$right'")
                }
                TypeRef.BOOLEAN
            }

            // Logical: boolean×boolean→boolean
            BinaryOp.AND, BinaryOp.OR -> {
                if (left != null && left != TypeRef.BOOLEAN) {
                    errors.report(expr.left.loc, "'${expr.op.symbol}' requires 'boolean' operand, got '$left'")
                }
                if (right != null && right != TypeRef.BOOLEAN) {
                    errors.report(expr.right.loc, "'${expr.op.symbol}' requires 'boolean' operand, got '$right'")
                }
                TypeRef.BOOLEAN
            }

            // Bitwise: int×int→int
            BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR -> {
                if (left != null && left != TypeRef.INT) {
                    errors.report(expr.left.loc, "Bitwise operator '${expr.op.symbol}' requires 'int' operands, got '$left'")
                }
                if (right != null && right != TypeRef.INT) {
                    errors.report(expr.right.loc, "Bitwise operator '${expr.op.symbol}' requires 'int' operands, got '$right'")
                }
                TypeRef.INT
            }

            // Shift: int×int→int
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR -> {
                if (left != null && left != TypeRef.INT) {
                    errors.report(expr.left.loc, "Shift operator '${expr.op.symbol}' requires 'int' operands, got '$left'")
                }
                if (right != null && right != TypeRef.INT) {
                    errors.report(expr.right.loc, "Shift operator '${expr.op.symbol}' requires 'int' operands, got '$right'")
                }
                TypeRef.INT
            }
        }
    }

    private fun resolveUnary(scope: SymbolTable, expr: UnaryExpr): TypeRef? {
        val operandType = resolveExpr(scope, expr.operand) ?: return null

        return when (expr.op) {
            UnaryOp.NEG -> {
                if (!operandType.isNumeric()) {
                    errors.report(expr.loc, "Cannot negate non-numeric type '$operandType'")
                    return null
                }
                operandType // -int to int, -double to double
            }

            UnaryOp.NOT -> {
                if (operandType != TypeRef.BOOLEAN) {
                    errors.report(expr.loc, "Logical NOT requires 'boolean', got '$operandType'")
                    return null
                }
                TypeRef.BOOLEAN
            }

            UnaryOp.BIT_NOT -> {
                if (operandType != TypeRef.INT) {
                    errors.report(expr.loc, "Bitwise NOT requires 'int', got '$operandType'")
                    return null
                }
                TypeRef.INT
            }
        }
    }

    private fun resolvePostfix(scope: SymbolTable, expr: PostfixExpr): TypeRef? {
        val operandType = resolveExpr(scope, expr.operand) ?: return null
        if (!operandType.isNumeric()) {
            errors.report(expr.loc, "Cannot increment/decrement non-numeric type '$operandType'")
            return null
        }
        return operandType
    }

    private fun resolveCast(scope: SymbolTable, expr: CastExpr): TypeRef? {
        val sourceType = resolveExpr(scope, expr.operand)

        // Only json to struct casts are allowed
        if (sourceType != null && sourceType != TypeRef.JSON) {
            errors.report(expr.loc, "Cannot cast from '$sourceType'. Only 'json' can be cast")
        }

        // Target must be a known struct type
        val targetSym = globalScope.lookup(expr.targetType.name)
        if (targetSym == null || targetSym !is TypeSymbol) {
            errors.report(expr.loc, "Unknown cast target type '${expr.targetType}'")
        }

        // Actual validation happens at runtime (CastError if fields mismatch)
        return expr.targetType
    }

    /**
     * Lightweight param descriptor for unified argument validation.
     * Both user-defined [ParamSymbol] and built-in `Pair<String, TypeRef>` params
     * convert to this via extension helpers below.
     */
    private data class ArgSpec(val name: String, val type: TypeRef, val hasDefault: Boolean)

    /**
     * Validate call arguments against a parameter list.
     * Handles default values (optional params) and type checking.
     * Works for both user-defined functions and built-in methods.
     */
    private fun validateArgs(
        callLoc: SourceLocation,
        funcName: String,
        params: List<ArgSpec>,
        args: List<Expr>,
        scope: SymbolTable,
    ) {
        val requiredCount = params.count { !it.hasDefault }

        if (args.size < requiredCount) {
            errors.report(callLoc, "'$funcName' expects at least $requiredCount arguments, got ${args.size}")
        } else if (args.size > params.size) {
            errors.report(callLoc, "'$funcName' expects at most ${params.size} arguments, got ${args.size}")
        }

        for (i in args.indices) {
            val argType = resolveExpr(scope, args[i])
            if (i < params.size) {
                val paramType = params[i].type
                if (!paramType.isAssignableFrom(argType)) {
                    errors.report(
                        args[i].loc,
                        "Argument ${i + 1} of '$funcName': expected '$paramType', got '${argType ?: "null"}'",
                    )
                }
            }
        }
    }

    /** Convert user-defined params to [ArgSpec]. */
    private fun paramSpecs(params: List<ParamSymbol>): List<ArgSpec> =
        params.map { ArgSpec(it.name, it.type, it.defaultValue != null) }

    /** Convert built-in params to [ArgSpec] (all required). */
    private fun builtinSpecs(params: List<Pair<String, TypeRef>>): List<ArgSpec> =
        params.map { (name, type) -> ArgSpec(name, type, hasDefault = false) }
}


