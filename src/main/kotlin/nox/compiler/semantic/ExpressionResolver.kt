package nox.compiler.semantic

import nox.compiler.CompilerErrors
import nox.compiler.DiagnosticHelpers
import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*
import nox.plugin.LibraryRegistry

class ExpressionResolver(
    private val globalScope: SymbolTable,
    private val errors: CompilerErrors,
    private val modules: List<ResolvedModule>,
    private val registry: LibraryRegistry,
) {
    fun resolveExpr(
        scope: SymbolTable,
        expr: RawExpr,
        expectedType: TypeRef? = null,
    ): TypedExpr {
        return when (expr) {
            is RawIntLiteralExpr -> TypedIntLiteralExpr(expr.value, expr.loc, TypeRef.INT)
            is RawDoubleLiteralExpr -> TypedDoubleLiteralExpr(expr.value, expr.loc, TypeRef.DOUBLE)
            is RawBoolLiteralExpr -> TypedBoolLiteralExpr(expr.value, expr.loc, TypeRef.BOOLEAN)
            is RawStringLiteralExpr -> TypedStringLiteralExpr(expr.value, expr.loc, TypeRef.STRING)
            is RawNullLiteralExpr -> {
                val type = if (expectedType != null && expectedType.isNullable()) expectedType else TypeRef.NULL
                TypedNullLiteralExpr(expr.loc, type)
            }
            is RawTemplateLiteralExpr -> resolveTemplate(scope, expr, expectedType)
            is RawArrayLiteralExpr -> resolveArrayLiteral(scope, expr, expectedType)
            is RawStructLiteralExpr -> resolveStructLiteral(scope, expr, expectedType)
            is RawIdentifierExpr -> resolveIdentifier(scope, expr, expectedType)
            is RawFieldAccessExpr -> resolveFieldAccess(scope, expr, expectedType)
            is RawIndexAccessExpr -> resolveIndexAccess(scope, expr, expectedType)
            is RawFuncCallExpr -> resolveFuncCall(scope, expr, expectedType)
            is RawMethodCallExpr -> resolveMethodCall(scope, expr, expectedType)
            is RawBinaryExpr -> resolveBinary(scope, expr, expectedType)
            is RawUnaryExpr -> resolveUnary(scope, expr, expectedType)
            is RawPostfixExpr -> resolvePostfix(scope, expr, expectedType)
            is RawCastExpr -> resolveCast(scope, expr, expectedType)
            is RawErrorExpr -> TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
        }
    }

    private fun resolveTemplate(scope: SymbolTable, expr: RawTemplateLiteralExpr, expectedType: TypeRef?): TypedExpr {
        val typedParts = expr.parts.map { part ->
            when (part) {
                is RawTemplatePart.Text -> TypedTemplatePart.Text(part.value)
                is RawTemplatePart.Interpolation -> TypedTemplatePart.Interpolation(resolveExpr(scope, part.expression))
                is RawTemplatePart.ErrorPart -> TypedTemplatePart.ErrorPart
            }
        }
        return TypedTemplateLiteralExpr(typedParts, expr.loc, TypeRef.STRING)
    }

    private fun resolveArrayLiteral(scope: SymbolTable, expr: RawArrayLiteralExpr, expectedType: TypeRef?): TypedExpr {
        val contextElementType = expectedType?.takeIf { it.isArray }?.elementType()

        if (expr.elements.isEmpty()) {
            val elementType = contextElementType ?: TypeRef.JSON
            return TypedArrayLiteralExpr(emptyList(), expr.loc, elementType.arrayOf(), elementType)
        }

        // 1. Resolve all elements with the context element type if available
        val firstElem = resolveExpr(scope, expr.elements[0], contextElementType)
        if (firstElem is TypedNullLiteralExpr && contextElementType == null) {
            errors.report(expr.elements[0].loc, "First element of array literal cannot be 'null'. The element type cannot be inferred", suggestion = "Provide an explicit typed variable: 'string[] arr = [null];'")
            return TypedErrorExpr(expr.loc, TypeRef.JSON.arrayOf())
        }

        // 2. Determine base element type
        var elementType = contextElementType ?: firstElem.type
        val typedElements = mutableListOf<TypedExpr>()
        typedElements.add(firstElem)

        for (i in 1 until expr.elements.size) {
            val typedElem = resolveExpr(scope, expr.elements[i], elementType)
            val elemType = typedElem.type

            if (elementType.isAssignableFrom(elemType)) {
                typedElements.add(typedElem)
                continue
            }

            // If we have no context, we might need to broaden from the first element's type
            if (contextElementType == null) {
                if (elemType.isAssignableFrom(elementType)) {
                    // Current element is broader (e.g. double vs int, or json vs struct)
                    elementType = elemType
                    typedElements.add(typedElem)
                    continue
                }
                
                // Special case: mixed structs upcast to json
                if (elementType.isStructType() && elemType.isStructType()) {
                    elementType = TypeRef.JSON
                    typedElements.add(typedElem)
                    continue
                }
            }

            errors.report(expr.elements[i].loc, "Type mismatch: Array element ${i + 1} has type '$elemType' which is not compatible with inferred array element type '$elementType'", suggestion = "Ensure all elements have the same type, or declare the variable with an explicit type")
            typedElements.add(typedElem)
        }

        return TypedArrayLiteralExpr(typedElements, expr.loc, elementType.arrayOf(), elementType)
    }

    fun resolveStructLiteral(scope: SymbolTable, expr: RawStructLiteralExpr, expectedType: TypeRef?): TypedExpr {
        val structType = expectedType
        if (structType == null) {
            errors.report(expr.loc, "Struct literal has no type context, we are unable to infer which struct this is", suggestion = "Declare the variable with an explicit type: 'MyStruct s = { ... };'")
            return TypedErrorExpr(expr.loc, TypeRef.JSON)
        }

        if (structType == TypeRef.JSON) {
            val seenKeys = mutableSetOf<String>()
            val typedFields = expr.fields.map { init ->
                if (!seenKeys.add(init.name)) {
                    errors.report(init.loc, "Key '${init.name}' appears more than once in this json literal", suggestion = "Remove the duplicate key or rename it")
                }
                TypedFieldInit(init.name, resolveExpr(scope, init.value), init.loc)
            }
            return TypedStructLiteralExpr(typedFields, expr.loc, TypeRef.JSON)
        }

        val typeSym = globalScope.lookup(structType.name)
        if (typeSym == null || typeSym !is TypeSymbol) {
            errors.report(expr.loc, "Type '${structType.name}' is not defined", suggestion = "Declare it with 'type ${structType.name} { ... }' before using it")
            return TypedErrorExpr(expr.loc, structType)
        }

        val provided = mutableSetOf<String>()
        val typedFields = mutableListOf<TypedFieldInit>()

        for (init in expr.fields) {
            provided.add(init.name)
            val expectedFieldType = typeSym.fields[init.name]
            if (expectedFieldType == null) {
                val suggestion = DiagnosticHelpers.didYouMeanMsg(init.name, typeSym.fields.keys) ?: "Available fields: ${typeSym.fields.keys.joinToString(", ")}"
                errors.report(init.loc, "Struct '${typeSym.name}' has no field '${init.name}'", suggestion = suggestion)
                typedFields.add(TypedFieldInit(init.name, resolveExpr(scope, init.value), init.loc))
                continue
            }

            val actualType = resolveExpr(scope, init.value, expectedFieldType)
            if (!expectedFieldType.isAssignableFrom(actualType.type)) {
                errors.report(init.loc, "Field '${init.name}' expects '$expectedFieldType', but '${actualType.type}' was given", suggestion = DiagnosticHelpers.conversionHint(actualType.type, expectedFieldType))
            }
            typedFields.add(TypedFieldInit(init.name, actualType, init.loc))
        }

        for (fieldName in typeSym.fields.keys) {
            if (fieldName !in provided) {
                val fieldType = typeSym.fields[fieldName]
                errors.report(expr.loc, "Struct '${typeSym.name}' requires field '$fieldName' (type '$fieldType') but it was not provided", suggestion = "Add '$fieldName: ${DiagnosticHelpers.defaultValueHint(fieldType!!)}' to the struct literal")
            }
        }

        return TypedStructLiteralExpr(typedFields, expr.loc, structType)
    }

    private fun resolveIdentifier(scope: SymbolTable, expr: RawIdentifierExpr, expectedType: TypeRef?): TypedExpr {
        val symbol = scope.lookup(expr.name)
        if (symbol == null) {
            val candidates = scope.allNamesInScope { it is VarSymbol || it is ParamSymbol || it is GlobalSymbol }
            val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.name, candidates)
            errors.report(expr.loc, "Variable '${expr.name}' is not declared in this scope", suggestion = suggestion)
            return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
        }
        return TypedIdentifierExpr(expr.name, expr.loc, symbol.type, symbol)
    }

    private fun resolveFieldAccess(scope: SymbolTable, expr: RawFieldAccessExpr, expectedType: TypeRef?): TypedExpr {
        val target = resolveExpr(scope, expr.target)
        val targetType = target.type

        if (targetType == TypeRef.JSON) return TypedFieldAccessExpr(target, expr.fieldName, expr.loc, TypeRef.JSON)

        if (targetType.isStructType() && !targetType.isArray) {
            val structSym = globalScope.lookup(targetType.name)
            if (structSym is TypeSymbol) {
                val fieldType = structSym.fields[expr.fieldName]
                if (fieldType != null) return TypedFieldAccessExpr(target, expr.fieldName, expr.loc, fieldType)

                val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.fieldName, structSym.fields.keys) ?: "Available fields: ${structSym.fields.keys.joinToString(", ")}"
                errors.report(expr.loc, "Struct '${targetType.name}' has no field '${expr.fieldName}'", suggestion = suggestion)
                return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
            }
        }

        val suggestion = if (expr.fieldName == "length" && (targetType == TypeRef.STRING || targetType.isArray)) "Use '.length()' with parentheses, it is a method, not a property" else "Field access '.' is only valid on structs and json types"
        errors.report(expr.loc, "Cannot access field '${expr.fieldName}' on type '$targetType'", suggestion = suggestion)
        return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
    }

    private fun resolveIndexAccess(scope: SymbolTable, expr: RawIndexAccessExpr, expectedType: TypeRef?): TypedExpr {
        val target = resolveExpr(scope, expr.target)
        val index = resolveExpr(scope, expr.index, TypeRef.INT)
        val targetType = target.type
        val indexType = index.type

        if (targetType == TypeRef.JSON) return TypedIndexAccessExpr(target, index, expr.loc, TypeRef.JSON)

        if (targetType.isArray) {
            if (indexType != TypeRef.INT) {
                errors.report(expr.index.loc, "Array index must be 'int', got '$indexType'", suggestion = if (indexType == TypeRef.DOUBLE) "Use '.toInt()' to convert the index" else null)
            }
            return TypedIndexAccessExpr(target, index, expr.loc, targetType.elementType())
        }

        errors.report(expr.loc, "Type '$targetType' does not support '[]' index access, only arrays and json can be indexed")
        return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
    }

    private fun resolveFuncCall(scope: SymbolTable, expr: RawFuncCallExpr, expectedType: TypeRef?): TypedExpr {
        val symbol = scope.lookup(expr.name)
        if (symbol == null || symbol !is FuncSymbol) {
            val candidates = scope.allNamesInScope { it is FuncSymbol }
            val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.name, candidates)
            errors.report(expr.loc, "Function '${expr.name}' is not defined", suggestion = suggestion)
            return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
        }

        val typedArgs = expr.args.map { resolveExpr(scope, it) }
        val tExpr = TypedFuncCallExpr(expr.name, typedArgs, expr.loc, symbol.returnType, symbol)
        validateArgs(expr.loc, expr.name, paramSpecs(symbol.params), typedArgs, scope)
        return tExpr
    }

    private fun resolveMethodCall(scope: SymbolTable, call: RawMethodCallExpr, expectedType: TypeRef?): TypedExpr {
        // Special case: check if target is a namespace name (e.g. Math.sqrt)
        if (call.target is RawIdentifierExpr) {
            val name = call.target.name
            // 1. Check imported modules
            val module = modules.find { it.namespace == name }
            if (module != null) {
                val typedArgs = call.args.map { resolveExpr(scope, it) }
                val importedFunc = module.program.functionsByName[call.methodName]
                if (importedFunc != null) {
                    val callTarget = CallTarget(name = importedFunc.name, params = importedFunc.params.map { NoxParam(it.name, it.type) }, returnType = importedFunc.returnType, astNode = importedFunc)
                    // We still need a typedTarget for the AST structure, even if it's just a dummy placeholder for the namespace
                    val typedTarget = TypedIdentifierExpr(name, call.target.loc, TypeRef.JSON, NamespaceSymbol(name))
                    val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, importedFunc.returnType, TypedMethodCallExpr.Resolution.NAMESPACE, callTarget)
                    validateArgs(call.loc, "$name.${call.methodName}", builtinSpecs(callTarget.params), typedArgs, scope)
                    return tExpr
                }
                val available = module.program.functionsByName.keys
                val suggestion = DiagnosticHelpers.didYouMeanMsg(call.methodName, available) ?: if (available.isNotEmpty()) "Available functions: ${available.joinToString(", ")}" else null
                errors.report(call.loc, "Namespace '$name' does not export a function named '${call.methodName}'", suggestion = suggestion)
                return TypedErrorExpr(call.loc, expectedType ?: TypeRef.JSON)
            }

            // 2. Check built-in namespaces
            if (registry.isBuiltinNamespace(name)) {
                val typedArgs = call.args.map { resolveExpr(scope, it) }
                val builtin = registry.lookupNamespaceFunc(name, call.methodName)
                if (builtin != null) {
                    val typedTarget = TypedIdentifierExpr(name, call.target.loc, TypeRef.JSON, NamespaceSymbol(name))
                    val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, builtin.returnType, TypedMethodCallExpr.Resolution.NAMESPACE, builtin)
                    validateArgs(call.loc, "$name.${call.methodName}", builtinSpecs(builtin.params), typedArgs, scope)
                    return tExpr
                }
            }
        }

        val typedTarget = resolveExpr(scope, call.target)
        val typedArgs = call.args.map { resolveExpr(scope, it) }

        if (typedTarget is TypedIdentifierExpr) {
            val namespaceName = typedTarget.name
            val module = modules.find { it.namespace == namespaceName }
            // ... (rest of logic can stay as fallback or be removed)

            if (registry.isBuiltinNamespace(namespaceName)) {
                val builtin = registry.lookupNamespaceFunc(namespaceName, call.methodName)
                if (builtin != null) {
                    val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, builtin.returnType, TypedMethodCallExpr.Resolution.NAMESPACE, builtin)
                    validateArgs(call.loc, "$namespaceName.${call.methodName}", builtinSpecs(builtin.params), typedArgs, scope)
                    return tExpr
                }
                errors.report(call.loc, "Namespace '$namespaceName' does not export a function named '${call.methodName}'", suggestion = "Check the standard library docs for available '$namespaceName' functions")
                return TypedErrorExpr(call.loc, expectedType ?: TypeRef.JSON)
            }
        }

        val targetType = typedTarget.type
        val builtinMethod = registry.lookupBuiltinMethod(targetType, call.methodName)
        if (builtinMethod != null) {
            val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, builtinMethod.returnType, TypedMethodCallExpr.Resolution.TYPE_BOUND, builtinMethod)
            validateArgs(call.loc, "$targetType.${call.methodName}", builtinSpecs(builtinMethod.params), typedArgs, scope)
            return tExpr
        }

        val typeMethod = registry.lookupTypeMethod(targetType, call.methodName)
        if (typeMethod != null) {
            val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, typeMethod.returnType, TypedMethodCallExpr.Resolution.TYPE_BOUND, typeMethod)
            validateArgs(call.loc, "$targetType.${call.methodName}", builtinSpecs(typeMethod.params), typedArgs, scope)
            return tExpr
        }

        val ufcsFunc = findUFCSFunction(scope, call.methodName, targetType)
        if (ufcsFunc != null) {
            val ufcsTarget = CallTarget(name = ufcsFunc.name, params = ufcsFunc.params.map { NoxParam(it.name, it.type) }, returnType = ufcsFunc.returnType, astNode = ufcsFunc.astNode)
            val tExpr = TypedMethodCallExpr(typedTarget, call.methodName, typedArgs, call.loc, ufcsFunc.returnType, TypedMethodCallExpr.Resolution.UFCS, ufcsTarget)
            val remainingParams = ufcsFunc.params.drop(1)
            validateArgs(call.loc, call.methodName, paramSpecs(remainingParams), typedArgs, scope)
            return tExpr
        }

        val methodCandidates = mutableListOf<String>()
        registry.getBuiltinMethodNames(targetType)?.let { methodCandidates.addAll(it) }
        registry.getTypeMethodNames(targetType)?.let { methodCandidates.addAll(it) }
        val ufcsCandidates = scope.allNamesInScope { sym -> sym is FuncSymbol && sym.params.isNotEmpty() && sym.params[0].type.isAssignableFrom(targetType) }
        methodCandidates.addAll(ufcsCandidates)
        val suggestion = DiagnosticHelpers.didYouMeanMsg(call.methodName, methodCandidates)
        errors.report(call.loc, "Type '$targetType' has no method '${call.methodName}'", suggestion = suggestion)
        return TypedErrorExpr(call.loc, expectedType ?: TypeRef.JSON)
    }

    private fun findUFCSFunction(scope: SymbolTable, funcName: String, targetType: TypeRef): FuncSymbol? {
        val symbol = scope.lookup(funcName) ?: return null
        if (symbol !is FuncSymbol) return null
        if (symbol.params.isEmpty()) return null
        val firstParamType = symbol.params[0].type
        if (firstParamType.isAssignableFrom(targetType)) return symbol
        return null
    }

    private fun resolveBinary(scope: SymbolTable, expr: RawBinaryExpr, expectedType: TypeRef?): TypedExpr {
        val left = resolveExpr(scope, expr.left)
        val right = resolveExpr(scope, expr.right)

        val type = when (expr.op) {
            BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> {
                when {
                    left.type == TypeRef.INT && right.type == TypeRef.INT -> TypeRef.INT
                    left.type.isNumeric() && right.type.isNumeric() -> TypeRef.DOUBLE
                    expr.op == BinaryOp.ADD && left.type == TypeRef.STRING && right.type == TypeRef.STRING -> TypeRef.STRING
                    else -> {
                        val suggestion = when {
                            expr.op == BinaryOp.ADD && (left.type == TypeRef.STRING || right.type == TypeRef.STRING) -> "Use template literals for string concatenation: `\${value1}\${value2}`"
                            !left.type.isNumeric() -> DiagnosticHelpers.conversionHint(left.type, TypeRef.INT) ?: DiagnosticHelpers.conversionHint(left.type, TypeRef.DOUBLE)
                            else -> DiagnosticHelpers.conversionHint(right.type, TypeRef.INT) ?: DiagnosticHelpers.conversionHint(right.type, TypeRef.DOUBLE)
                        }
                        errors.report(expr.loc, "Operator '${expr.op.symbol}' cannot be applied to '${left.type}' and '${right.type}'", suggestion = suggestion)
                        TypeRef.JSON
                    }
                }
            }
            BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
                if (!left.type.isNumeric() || !right.type.isNumeric()) {
                    errors.report(expr.loc, "'${expr.op.symbol}' requires two numeric operands (int or double), got '${left.type}' and '${right.type}'", suggestion = "Use '==' or '!=' for non-numeric comparison")
                }
                TypeRef.BOOLEAN
            }
            BinaryOp.EQ, BinaryOp.NE -> {
                if (!left.type.isComparable(right.type) && !(left is TypedNullLiteralExpr && right is TypedNullLiteralExpr)) {
                    errors.report(expr.loc, "Cannot compare '${left.type}' with '${right.type}' using '${expr.op.symbol}'", suggestion = "Both sides must have the same type, or one may be null for nullable types")
                }
                TypeRef.BOOLEAN
            }
            BinaryOp.AND, BinaryOp.OR -> {
                if (left.type != TypeRef.BOOLEAN) {
                    errors.report(expr.left.loc, "Logical '${expr.op.symbol}' expected 'boolean', got '${left.type}'", suggestion = if (left.type.isNumeric()) "Did you mean a comparison? e.g. '${expr.left} != 0'" else null)
                }
                if (right.type != TypeRef.BOOLEAN) {
                    errors.report(expr.right.loc, "Logical '${expr.op.symbol}' expected 'boolean', got '${right.type}'", suggestion = if (right.type.isNumeric()) "Did you mean a comparison? e.g. '${expr.right} != 0'" else null)
                }
                TypeRef.BOOLEAN
            }
            BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR -> {
                if (left.type != TypeRef.INT) {
                    errors.report(expr.left.loc, "Bitwise '${expr.op.symbol}' requires 'int' operands, got '${left.type}'", suggestion = if (left.type == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null)
                }
                if (right.type != TypeRef.INT) {
                    errors.report(expr.right.loc, "Bitwise '${expr.op.symbol}' requires 'int' operands, got '${right.type}'", suggestion = if (right.type == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null)
                }
                TypeRef.INT
            }
            BinaryOp.SHL, BinaryOp.SHR, BinaryOp.USHR -> {
                if (left.type != TypeRef.INT) {
                    errors.report(expr.left.loc, "Shift '${expr.op.symbol}' requires 'int' operands, got '${left.type}'", suggestion = if (left.type == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null)
                }
                if (right.type != TypeRef.INT) {
                    errors.report(expr.right.loc, "Shift '${expr.op.symbol}' requires 'int' operands, got '${right.type}'", suggestion = if (right.type == TypeRef.DOUBLE) "Use '.toInt()' to convert" else null)
                }
                TypeRef.INT
            }
        }
        return TypedBinaryExpr(left, expr.op, right, expr.loc, type)
    }

    private fun resolveUnary(scope: SymbolTable, expr: RawUnaryExpr, expectedType: TypeRef?): TypedExpr {
        val operand = resolveExpr(scope, expr.operand)
        val operandType = operand.type
        val type = when (expr.op) {
            UnaryOp.NEG -> {
                if (!operandType.isNumeric()) {
                    errors.report(expr.loc, "Unary '-' cannot be applied to '$operandType', only 'int' and 'double' can be negated")
                    TypeRef.JSON
                } else operandType
            }
            UnaryOp.NOT -> {
                if (operandType != TypeRef.BOOLEAN) {
                    errors.report(expr.loc, "Logical '!' requires 'boolean', got '$operandType'", suggestion = if (operandType.isNumeric()) "Did you mean a comparison? e.g. '!(x != 0)'" else null)
                    TypeRef.JSON
                } else TypeRef.BOOLEAN
            }
            UnaryOp.BIT_NOT -> {
                if (operandType != TypeRef.INT) {
                    errors.report(expr.loc, "Bitwise '~' requires 'int', got '$operandType'", suggestion = if (operandType == TypeRef.DOUBLE) "Use '.toInt()' to convert first" else null)
                    TypeRef.JSON
                } else TypeRef.INT
            }
        }
        return TypedUnaryExpr(expr.op, operand, expr.loc, type)
    }

    private fun resolvePostfix(scope: SymbolTable, expr: RawPostfixExpr, expectedType: TypeRef?): TypedExpr {
        val operand = resolveExpr(scope, expr.operand)
        val operandType = operand.type
        if (!operandType.isNumeric()) {
            val opSymbol = if (expr.op == PostfixOp.INCREMENT) "++" else "--"
            errors.report(expr.loc, "'$opSymbol' can only be used on numeric types (int, double), got '$operandType'")
            return TypedErrorExpr(expr.loc, expectedType ?: TypeRef.JSON)
        }
        return TypedPostfixExpr(operand, expr.op, expr.loc, operandType)
    }

    private fun resolveCast(scope: SymbolTable, expr: RawCastExpr, expectedType: TypeRef?): TypedExpr {
        val operand = resolveExpr(scope, expr.operand)
        val sourceType = operand.type
        if (sourceType != TypeRef.JSON) {
            val suggestion = if (sourceType.isStructType()) "Convert via json first: 'json j = value; ${expr.targetType} result = j as ${expr.targetType};'" else "Only 'json' values can be cast to struct types. Use type conversion methods instead (e.g. '.toInt()', '.toString()')"
            errors.report(expr.loc, "Cannot cast from '$sourceType', only 'json' can be cast to a struct type", suggestion = suggestion)
        }

        val targetSym = globalScope.lookup(expr.targetType.name)
        if (targetSym == null || targetSym !is TypeSymbol) {
            val candidates = globalScope.allNamesInScope { it is TypeSymbol }
            val suggestion = DiagnosticHelpers.didYouMeanMsg(expr.targetType.name, candidates) ?: "Declare it with 'type ${expr.targetType} { ... }'"
            errors.report(expr.loc, "Cast target type '${expr.targetType}' is not defined. It must be a struct type", suggestion = suggestion)
        }
        return TypedCastExpr(operand, expr.targetType, expr.loc, expr.targetType)
    }

    private data class ArgSpec(
        val name: String,
        val type: TypeRef,
        val hasDefault: Boolean,
        val isVarargs: Boolean = false,
    )

    private fun validateArgs(callLoc: SourceLocation, funcName: String, params: List<ArgSpec>, args: List<TypedExpr>, scope: SymbolTable) {
        val hasVarargs = params.lastOrNull()?.isVarargs == true
        val requiredCount = params.count { !it.hasDefault && !it.isVarargs }

        if (args.size < requiredCount) {
            val paramSig = params.joinToString(", ") { "${it.type} ${it.name}" }
            errors.report(callLoc, "Too few arguments: '$funcName' requires at least $requiredCount, but ${args.size} ${if (args.size == 1) "was" else "were"} given", suggestion = "Expected signature: $funcName($paramSig)")
        } else if (!hasVarargs && args.size > params.size) {
            val paramSig = params.joinToString(", ") { "${it.type} ${it.name}" }
            errors.report(callLoc, "Too many arguments: '$funcName' accepts at most ${params.size}, but ${args.size} were given", suggestion = "Expected signature: $funcName($paramSig)")
        }

        for (i in args.indices) {
            val argType = args[i].type
            val param = if (hasVarargs && i >= params.size - 1) params.last() else if (i < params.size) params[i] else null

            if (param != null) {
                val expectedType = if (param.isVarargs) {
                    val varargSlotIndex = params.size - 1
                    val argsInVarargSlot = args.size - varargSlotIndex
                    if (argsInVarargSlot == 1 && param.type.isAssignableFrom(argType)) {
                        continue
                    }
                    param.type.elementType()
                } else param.type

                if (!expectedType.isAssignableFrom(argType)) {
                    errors.report(args[i].loc, "Argument ${i + 1} ('${param.name}') of '$funcName': expected '$expectedType', got '$argType'", suggestion = DiagnosticHelpers.conversionHint(argType, expectedType))
                }
            }
        }
    }

    private fun paramSpecs(params: List<ParamSymbol>): List<ArgSpec> = params.map { ArgSpec(it.name, it.type, it.defaultValue != null, it.isVarargs) }
    private fun builtinSpecs(params: List<NoxParam>): List<ArgSpec> = params.map { ArgSpec(it.name, it.type, hasDefault = it.defaultLiteral != null) }
}
