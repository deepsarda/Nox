package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
import nox.compiler.ast.*
import nox.compiler.types.*
import nox.plugin.TempRegistry

/**
 * Resolves the type of every [Expr] node in the AST.
 *
 * This is the core of Pass 2 (Type Resolution). For each expression,
 * [resolveExpr] determines the resulting [TypeRef] and sets
 * `expr.resolvedType`. Identifiers are linked to their declarations
 * via [IdentifierExpr.resolvedSymbol], and method calls are classified
 * into their resolution kind ([MethodCallExpr.Resolution]).
 *
 * See docs/compiler/semantic-analysis.md.
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
    fun resolveExpr(
        scope: SymbolTable,
        expr: Expr,
    ): TypeRef? {
        val type =
            when (expr) {
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

    private fun resolveTemplate(
        scope: SymbolTable,
        expr: TemplateLiteralExpr,
    ): TypeRef {
        for (part in expr.parts) {
            if (part is TemplatePart.Interpolation) {
                resolveExpr(scope, part.expression)
                // Any type can be interpolated, the runtime converts to string
            }
        }
        return TypeRef.STRING
    }

    private fun resolveArrayLiteral(
        scope: SymbolTable,
        expr: ArrayLiteralExpr,
    ): TypeRef? {
        if (expr.elements.isEmpty()) {
            // Empty array literal, type must be inferred from context
            // The caller (resolveVarDecl or resolveAssign) handles this
            return null
        }

        val firstType = resolveExpr(scope, expr.elements[0])
        if (firstType == null) {
            errors.report(
                expr.elements[0].loc,
                "First element of array literal cannot be 'null'. The element type cannot be inferred",
                suggestion = "Provide an explicit typed variable: 'string[] arr = [null];'",
            )
            return null
        }

        var elementType: TypeRef = firstType

        for (i in 1 until expr.elements.size) {
            val elemType = resolveExpr(scope, expr.elements[i]) ?: continue
            if (elemType == elementType) continue

            // Allow int to double widening in arrays
            if (elementType == TypeRef.DOUBLE && elemType == TypeRef.INT) continue

            if (elementType == TypeRef.INT && elemType == TypeRef.DOUBLE) {
                errors.report(
                    expr.elements[i].loc,
                    "Array element ${i + 1} has type 'double' but the array was inferred as 'int[]' from the first element",
                    suggestion = "Ensure all elements have the same type, or declare the variable as 'double[]'",
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
                "Array element ${i + 1} has type '$elemType' but the array was inferred as '$elementType[]' from the first element",
                suggestion = "Ensure all elements have the same type, or use 'json[]' for a mixed array",
            )
        }

        expr.elementType = elementType
        return elementType.arrayOf()
    }

    /**
     * Validates a struct literal against its type definition.
     *
     * The struct type must be set by the caller (e.g. from a `VarDeclStmt` or
     * `AssignStmt`) via `expr.structType` before calling this method.
     */
    fun resolveStructLiteral(
        scope: SymbolTable,
        expr: StructLiteralExpr,
    ): TypeRef? {
        if (expr.structType == null) {
            errors.report(
                expr.loc,
                "Struct literal has no type context, we are unable to infer which struct this is",
                suggestion = "Declare the variable with an explicit type: 'MyStruct s = { ... };'",
            )
            return null
        }

        // json literal: {key: value, ...} with no struct validation
        // json is dynamic so any field names and value types are valid
        if (expr.structType == TypeRef.JSON) {
            val seenKeys = mutableSetOf<String>()
            for (init in expr.fields) {
                if (!seenKeys.add(init.name)) {
                    errors.report(
                        init.loc,
                        "Key '${init.name}' appears more than once in this json literal",
                        suggestion = "Remove the duplicate key or rename it",
                    )
                }
                resolveExpr(scope, init.value)
            }
            return TypeRef.JSON
        }

        val typeSym = globalScope.lookup(expr.structType!!.name)

        if (typeSym == null || typeSym !is TypeSymbol) {
            errors.report(
                expr.loc,
                "Type '${expr.structType!!.name}' is not defined",
                suggestion = "Declare it with 'type ${expr.structType!!.name} { ... }' before using it",
            )
            return null
        }

        // Check for unknown/mistyped fields
        val provided = mutableSetOf<String>()

        for (init in expr.fields) {
            provided.add(init.name)
            val expectedFieldType = typeSym.fields[init.name]
            if (expectedFieldType == null) {
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(init.name, typeSym.fields.keys)
                        ?: "Available fields: ${typeSym.fields.keys.joinToString(", ")}"
                errors.report(
                    init.loc,
                    "Struct '${typeSym.name}' has no field '${init.name}'",
                    suggestion = suggestion,
                )
                continue
            }

            // Propagate struct type into nested struct literal values
            if (init.value is StructLiteralExpr &&
                (expectedFieldType.isStructType() || expectedFieldType == TypeRef.JSON)
            ) {
                init.value.structType = expectedFieldType
            }

            val actualType = resolveExpr(scope, init.value)
            if (!expectedFieldType.isAssignableFrom(actualType)) {
                errors.report(
                    init.loc,
                    "Field '${init.name}' expects '$expectedFieldType', but '${actualType ?: "null"}' was given",
                    suggestion = DiagnosticHelpers.conversionHint(actualType, expectedFieldType),
                )
            }
        }

        // Check for missing required fields
        for (fieldName in typeSym.fields.keys) {
            if (fieldName !in provided) {
                val fieldType = typeSym.fields[fieldName]
                errors.report(
                    expr.loc,
                    "Struct '${typeSym.name}' requires field '$fieldName' (type '$fieldType') but it was not provided",
                    suggestion = "Add '$fieldName: ${DiagnosticHelpers.defaultValueHint(
                        fieldType!!,
                    )}' to the struct literal",
                )
            }
        }

        return expr.structType
    }

    private fun resolveIdentifier(
        scope: SymbolTable,
        expr: IdentifierExpr,
    ): TypeRef? {
        val symbol = scope.lookup(expr.name)
        if (symbol == null) {
            val candidates = scope.allNamesInScope { it is VarSymbol || it is ParamSymbol || it is GlobalSymbol }
            val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.name, candidates)
            errors.report(
                expr.loc,
                "Variable '${expr.name}' is not declared in this scope",
                suggestion = suggestion,
            )
            return null
        }
        expr.resolvedSymbol = symbol
        return symbol.type
    }

    private fun resolveFieldAccess(
        scope: SymbolTable,
        expr: FieldAccessExpr,
    ): TypeRef? {
        val targetType = resolveExpr(scope, expr.target) ?: return null

        // json.someField is dynamic property access (type is json)
        if (targetType == TypeRef.JSON) return TypeRef.JSON

        // struct.field is look up field in struct definition
        if (targetType.isStructType() && !targetType.isArray) {
            val structSym = globalScope.lookup(targetType.name)
            if (structSym is TypeSymbol) {
                val fieldType = structSym.fields[expr.fieldName]
                if (fieldType != null) return fieldType

                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(expr.fieldName, structSym.fields.keys)
                        ?: "Available fields: ${structSym.fields.keys.joinToString(", ")}"
                errors.report(
                    expr.loc,
                    "Struct '${targetType.name}' has no field '${expr.fieldName}'",
                    suggestion = suggestion,
                )
                return null
            }
        }

        val suggestion =
            if (expr.fieldName == "length" && (targetType == TypeRef.STRING || targetType.isArray)) {
                "Use '.length()' with parentheses, it is a method, not a property"
            } else {
                "Field access '.' is only valid on structs and json types"
            }
        errors.report(
            expr.loc,
            "Cannot access field '${expr.fieldName}' on type '$targetType'",
            suggestion = suggestion,
        )
        return null
    }

    private fun resolveIndexAccess(
        scope: SymbolTable,
        expr: IndexAccessExpr,
    ): TypeRef? {
        val targetType = resolveExpr(scope, expr.target) ?: return null
        val indexType = resolveExpr(scope, expr.index) ?: return null

        // json[index] to json (dynamic)
        if (targetType == TypeRef.JSON) return TypeRef.JSON

        // array[int] to element type
        if (targetType.isArray) {
            if (indexType != TypeRef.INT) {
                errors.report(
                    expr.index.loc,
                    "Array index must be 'int', got '$indexType'",
                    suggestion = if (indexType == TypeRef.DOUBLE) "Use '.toInt()' to convert the index" else null,
                )
            }
            return targetType.elementType()
        }

        errors.report(
            expr.loc,
            "Type '$targetType' does not support '[]' index access, only arrays and json can be indexed",
        )
        return null
    }

    private fun resolveFuncCall(
        scope: SymbolTable,
        expr: FuncCallExpr,
    ): TypeRef? {
        val symbol = scope.lookup(expr.name)
        if (symbol == null || symbol !is FuncSymbol) {
            val candidates = scope.allNamesInScope { it is FuncSymbol }
            val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.name, candidates)
            errors.report(
                expr.loc,
                "Function '${expr.name}' is not defined",
                suggestion = suggestion,
            )
            return null
        }

        expr.resolvedFunction = symbol.astNode

        for (arg in expr.args) {
            resolveExpr(scope, arg)
        }

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
    private fun resolveMethodCall(
        scope: SymbolTable,
        call: MethodCallExpr,
    ): TypeRef? {
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
                    val callTarget =
                        CallTarget(
                            name = importedFunc.name,
                            params = importedFunc.params.map { it.name to it.type },
                            returnType = importedFunc.returnType,
                            astNode = importedFunc,
                        )
                    call.resolution = MethodCallExpr.Resolution.NAMESPACE
                    call.resolvedTarget = callTarget

                    validateArgs(
                        call.loc,
                        "$namespaceName.${call.methodName}",
                        builtinSpecs(callTarget.params),
                        call.args,
                        scope,
                    )
                    return importedFunc.returnType
                }
                val available = module.program.functionsByName.keys
                val suggestion =
                    DiagnosticHelpers.didYouMeanMsg(call.methodName, available)
                        ?: if (available.isNotEmpty()) "Available functions: ${available.joinToString(", ")}" else null
                errors.report(
                    call.loc,
                    "Namespace '$namespaceName' does not export a function named '${call.methodName}'",
                    suggestion = suggestion,
                )
                return null
            }

            // Step 2: Tier 0/1 built-in namespace
            if (TempRegistry.isBuiltinNamespace(namespaceName)) {
                val builtin = TempRegistry.lookupNamespaceFunc(namespaceName, call.methodName)
                if (builtin != null) {
                    call.resolution = MethodCallExpr.Resolution.NAMESPACE
                    call.resolvedTarget = builtin

                    validateArgs(
                        call.loc,
                        "$namespaceName.${call.methodName}",
                        builtinSpecs(builtin.params),
                        call.args,
                        scope,
                    )
                    return builtin.returnType
                }
                errors.report(
                    call.loc,
                    "Namespace '$namespaceName' does not export a function named '${call.methodName}'",
                    suggestion = "Check the standard library docs for available '$namespaceName' functions",
                )
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
            validateArgs(
                call.loc,
                "$targetType.${call.methodName}",
                builtinSpecs(builtinMethod.params),
                call.args,
                scope,
            )
            return builtinMethod.returnType
        }

        // Step 4: Type-bound conversion method
        val typeMethod = TempRegistry.lookupTypeMethod(targetType, call.methodName)
        if (typeMethod != null) {
            call.resolution = MethodCallExpr.Resolution.TYPE_BOUND
            call.resolvedTarget = typeMethod
            validateArgs(
                call.loc,
                "$targetType.${call.methodName}",
                builtinSpecs(typeMethod.params),
                call.args,
                scope,
            )
            return typeMethod.returnType
        }

        // Step 5: UFCS, look for global function whose first param type matches
        val ufcsFunc = findUFCSFunction(scope, call.methodName, targetType)
        if (ufcsFunc != null) {
            val ufcsTarget =
                CallTarget(
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

        // Step 6: Error, try to suggest available methods
        val methodCandidates = mutableListOf<String>()
        // Collect built-in type methods and conversion methods
        TempRegistry.getBuiltinMethodNames(targetType)?.let { methodCandidates.addAll(it) }
        TempRegistry.getTypeMethodNames(targetType)?.let { methodCandidates.addAll(it) }
        // Collect UFCS candidates
        val ufcsCandidates =
            scope.allNamesInScope { sym ->
                sym is FuncSymbol && sym.params.isNotEmpty() && sym.params[0].type.isAssignableFrom(targetType)
            }
        methodCandidates.addAll(ufcsCandidates)
        val suggestion = DiagnosticHelpers.didYouMeanMsg(call.methodName, methodCandidates)
        errors.report(
            call.loc,
            "Type '$targetType' has no method '${call.methodName}'",
            suggestion = suggestion,
        )
        return null
    }

    /**
     * Look up a global function suitable for UFCS:
     * the function must have at least one parameter, and its first parameter
     * type must be assignable from [targetType].
     */
    private fun findUFCSFunction(
        scope: SymbolTable,
        funcName: String,
        targetType: TypeRef,
    ): FuncSymbol? {
        val symbol = scope.lookup(funcName) ?: return null
        if (symbol !is FuncSymbol) return null
        if (symbol.params.isEmpty()) return null
        val firstParamType = symbol.params[0].type
        if (firstParamType.isAssignableFrom(targetType)) return symbol
        return null
    }

    private fun resolveBinary(
        scope: SymbolTable,
        expr: BinaryExpr,
    ): TypeRef? {
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
                        val suggestion =
                            when {
                                // string + non-string: suggest interpolation
                                expr.op == BinaryOp.ADD && (left == TypeRef.STRING || right == TypeRef.STRING) ->
                                    "Use template literals for string concatenation: `\${value1}\${value2}`"
                                // non-numeric: suggest conversion
                                !left.isNumeric() ->
                                    DiagnosticHelpers.conversionHint(left, TypeRef.INT)
                                        ?: DiagnosticHelpers.conversionHint(left, TypeRef.DOUBLE)
                                else ->
                                    DiagnosticHelpers.conversionHint(right, TypeRef.INT)
                                        ?: DiagnosticHelpers.conversionHint(right, TypeRef.DOUBLE)
                            }
                        errors.report(
                            expr.loc,
                            "Operator '${expr.op.symbol}' cannot be applied to '$left' and '$right'",
                            suggestion = suggestion,
                        )
                        null
                    }
                }
            }

            // Comparison: numeric×numeric→boolean
            BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
                if (left == null || right == null) return null
                if (!left.isNumeric() || !right.isNumeric()) {
                    errors.report(
                        expr.loc,
                        "'${expr.op.symbol}' requires two numeric operands (int or double), " +
                            "got '$left' and '$right'",
                        suggestion = "Use '==' or '!=' for non-numeric comparison",
                    )
                }
                TypeRef.BOOLEAN
            }

            // Equality: same type or null comparison→boolean
            BinaryOp.EQ, BinaryOp.NE -> {
                if (left == null && right == null) return TypeRef.BOOLEAN // null == null
                if (left != null &&
                    !left.isComparable(right)
                ) {
                    errors.report(
                        expr.loc,
                        "Cannot compare '$left' with '${right ?: "null"}' using '${expr.op.symbol}'",
                        suggestion = "Both sides must have the same type, or one may be null for nullable types",
                    )
                }
                if (right != null && !right.isComparable(left)) {
                    errors.report(
                        expr.loc,
                        "Cannot compare '${left ?: "null"}' with '$right' using '${expr.op.symbol}'",
                        suggestion = "Both sides must have the same type, or one may be null for nullable types",
                    )
                }
                TypeRef.BOOLEAN
            }

            // Logical: boolean×boolean→boolean
            BinaryOp.AND, BinaryOp.OR -> {
                if (left != null && left != TypeRef.BOOLEAN) {
                    errors.report(
                        expr.left.loc,
                        "Logical '${expr.op.symbol}' expected 'boolean', got '$left'",
                        suggestion =
                            if (left.isNumeric()) {
                                "Did you mean a comparison? e.g. '${expr.left} != 0'"
                            } else {
                                null
                            },
                    )
                }
                if (right != null && right != TypeRef.BOOLEAN) {
                    errors.report(
                        expr.right.loc,
                        "Logical '${expr.op.symbol}' expected 'boolean', got '$right'",
                        suggestion =
                            if (right.isNumeric()) {
                                "Did you mean a comparison? e.g. '${expr.right} != 0'"
                            } else {
                                null
                            },
                    )
                }
                TypeRef.BOOLEAN
            }

            // Bitwise: int×int→int
            BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR -> {
                if (left != null && left != TypeRef.INT) {
                    errors.report(
                        expr.left.loc,
                        "Bitwise '${expr.op.symbol}' requires 'int' operands, got '$left'",
                        suggestion = if (left == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null,
                    )
                }
                if (right != null && right != TypeRef.INT) {
                    errors.report(
                        expr.right.loc,
                        "Bitwise '${expr.op.symbol}' requires 'int' operands, got '$right'",
                        suggestion = if (right == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null,
                    )
                }
                TypeRef.INT
            }

            // Shift: int×int→int
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR -> {
                if (left != null && left != TypeRef.INT) {
                    errors.report(
                        expr.left.loc,
                        "Shift '${expr.op.symbol}' requires 'int' operands, got '$left'",
                        suggestion = if (left == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null,
                    )
                }
                if (right != null && right != TypeRef.INT) {
                    errors.report(
                        expr.right.loc,
                        "Shift '${expr.op.symbol}' requires 'int' operands, got '$right'",
                        suggestion = if (right == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null,
                    )
                }
                TypeRef.INT
            }
        }
    }

    private fun resolveUnary(
        scope: SymbolTable,
        expr: UnaryExpr,
    ): TypeRef? {
        val operandType = resolveExpr(scope, expr.operand) ?: return null

        return when (expr.op) {
            UnaryOp.NEG -> {
                if (!operandType.isNumeric()) {
                    errors.report(
                        expr.loc,
                        "Unary '-' cannot be applied to '$operandType', only 'int' and 'double' can be negated",
                    )
                    return null
                }
                operandType // -int to int, -double to double
            }

            UnaryOp.NOT -> {
                if (operandType != TypeRef.BOOLEAN) {
                    errors.report(
                        expr.loc,
                        "Logical '!' requires 'boolean', got '$operandType'",
                        suggestion =
                            if (operandType.isNumeric()) {
                                "Did you mean a comparison? e.g. '!(x != 0)'"
                            } else {
                                null
                            },
                    )
                    return null
                }
                TypeRef.BOOLEAN
            }

            UnaryOp.BIT_NOT -> {
                if (operandType != TypeRef.INT) {
                    errors.report(
                        expr.loc,
                        "Bitwise '~' requires 'int', got '$operandType'",
                        suggestion = if (operandType == TypeRef.DOUBLE) "Use '.toInt()' to convert first" else null,
                    )
                    return null
                }
                TypeRef.INT
            }
        }
    }

    private fun resolvePostfix(
        scope: SymbolTable,
        expr: PostfixExpr,
    ): TypeRef? {
        val operandType = resolveExpr(scope, expr.operand) ?: return null
        if (!operandType.isNumeric()) {
            val opSymbol = if (expr.op == PostfixOp.INCREMENT) "++" else "--"
            errors.report(
                expr.loc,
                "'$opSymbol' can only be used on numeric types (int, double), got '$operandType'",
            )
            return null
        }
        return operandType
    }

    private fun resolveCast(
        scope: SymbolTable,
        expr: CastExpr,
    ): TypeRef {
        val sourceType = resolveExpr(scope, expr.operand)

        // Only json to struct casts are allowed
        if (sourceType != null && sourceType != TypeRef.JSON) {
            val suggestion =
                if (sourceType.isStructType()) {
                    "Convert via json first: 'json j = value; ${expr.targetType} result = j as ${expr.targetType};'"
                } else {
                    "Only 'json' values can be cast to struct types. Use type conversion methods instead (e.g. '.toInt()', '.toString()')"
                }
            errors.report(
                expr.loc,
                "Cannot cast from '$sourceType', only 'json' can be cast to a struct type",
                suggestion = suggestion,
            )
        }

        // Target must be a known struct type
        val targetSym = globalScope.lookup(expr.targetType.name)
        if (targetSym == null || targetSym !is TypeSymbol) {
            val candidates = globalScope.allNamesInScope { it is TypeSymbol }
            val suggestion =
                DiagnosticHelpers.didYouMeanMsg(expr.targetType.name, candidates)
                    ?: "Declare it with 'type ${expr.targetType} { ... }'"
            errors.report(
                expr.loc,
                "Cast target type '${expr.targetType}' is not defined. It must be a struct type",
                suggestion = suggestion,
            )
        }

        // Actual validation happens at runtime (CastError if fields mismatch)
        return expr.targetType
    }

    /**
     * Lightweight param descriptor for unified argument validation.
     * Both user-defined [ParamSymbol] and built-in `Pair<String, TypeRef>` params
     * convert to this via extension helpers below.
     */
    private data class ArgSpec(
        val name: String,
        val type: TypeRef,
        val hasDefault: Boolean,
        val isVarargs: Boolean = false,
    )

    /**
     * Validate call arguments against a parameter list.
     * Handles default values (optional params), varargs, and type checking.
     * Works for both user-defined functions and built-in methods.
     */
    private fun validateArgs(
        callLoc: SourceLocation,
        funcName: String,
        params: List<ArgSpec>,
        args: List<Expr>,
        scope: SymbolTable,
    ) {
        val hasVarargs = params.lastOrNull()?.isVarargs == true
        val requiredCount = params.count { !it.hasDefault && !it.isVarargs }

        if (args.size < requiredCount) {
            val paramSig = params.joinToString(", ") { "${it.type} ${it.name}" }
            errors.report(
                callLoc,
                "Too few arguments: '$funcName' requires at least $requiredCount, but ${args.size} ${if (args.size == 1) "was" else "were"} given",
                suggestion = "Expected signature: $funcName($paramSig)",
            )
        } else if (!hasVarargs && args.size > params.size) {
            val paramSig = params.joinToString(", ") { "${it.type} ${it.name}" }
            errors.report(
                callLoc,
                "Too many arguments: '$funcName' accepts at most ${params.size}, but ${args.size} were given",
                suggestion = "Expected signature: $funcName($paramSig)",
            )
        }

        for (i in args.indices) {
            val argType =
                args[i].resolvedType ?: resolveExpr(
                    scope,
                    args[i],
                ) // It should have already been resolved, doesn't hurt to make sure
            val param =
                if (hasVarargs && i >= params.size - 1) {
                    params.last()
                } else if (i < params.size) {
                    params[i]
                } else {
                    null
                }

            if (param != null) {
                val expectedType =
                    if (param.isVarargs) {
                        // Allow direct array passing: sum([1,2,3]) for int ...vals[]
                        // Check if exactly one arg maps to the varargs slot and it matches the array type
                        val varargSlotIndex = params.size - 1
                        val argsInVarargSlot = args.size - varargSlotIndex
                        if (argsInVarargSlot == 1 && param.type.isAssignableFrom(argType)) {
                            continue
                        }
                        // For individual varargs elements, expect the element type
                        param.type.elementType()
                    } else {
                        param.type
                    }

                if (!expectedType.isAssignableFrom(argType)) {
                    errors.report(
                        args[i].loc,
                        "Argument ${i + 1} ('${param.name}') of '$funcName': expected '$expectedType', got '${argType ?: "null"}'",
                        suggestion = DiagnosticHelpers.conversionHint(argType, expectedType),
                    )
                }
            }
        }
    }

    /** Convert user-defined params to [ArgSpec]. */
    private fun paramSpecs(params: List<ParamSymbol>): List<ArgSpec> =
        params.map { ArgSpec(it.name, it.type, it.defaultValue != null, it.isVarargs) }

    /** Convert built-in params to [ArgSpec] (all required). */
    private fun builtinSpecs(params: List<Pair<String, TypeRef>>): List<ArgSpec> =
        params.map { (name, type) -> ArgSpec(name, type, hasDefault = false) }
}
