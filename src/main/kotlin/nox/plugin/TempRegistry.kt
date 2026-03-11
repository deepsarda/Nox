package nox.plugin

import nox.compiler.types.TypeRef
import nox.compiler.types.CallTarget

/**
 * Compile-time registry for Tier 0 (built-in) and Tier 1 (external plugin)
 * namespace functions and type-bound methods.
 *
 * All type-bound methods resolve to [CallTarget] entries whose [CallTarget.name]
 * is used as the SCALL function key. The VM looks up the native handler by name.
 *
 * **Tier 2 (import) namespaces are NOT registered here.** They are resolved
 * through the [nox.compiler.types.SymbolTable] by the ImportResolver.
 *
 * This registry will eventually be replaced by a proper `LibraryRegistry`
 * populated from plugin annotations (`@NoxModule`, `@NoxTypeMethod`).
 *
 * See docs/language/stdlib.md for the full reference.
 */
object TempRegistry {

    // Namespace functions (Tier 0)

    private val namespaceFunctions: Map<String, Map<String, CallTarget>> = mapOf(
        "Math" to mapOf(
            "sqrt" to target("sqrt", listOf("x" to TypeRef.DOUBLE), TypeRef.DOUBLE),
            "abs" to target("abs", listOf("x" to TypeRef.DOUBLE), TypeRef.DOUBLE),
            "min" to target("min", listOf("a" to TypeRef.DOUBLE, "b" to TypeRef.DOUBLE), TypeRef.DOUBLE),
            "max" to target("max", listOf("a" to TypeRef.DOUBLE, "b" to TypeRef.DOUBLE), TypeRef.DOUBLE),
            "floor" to target("floor", listOf("x" to TypeRef.DOUBLE), TypeRef.INT),
            "ceil" to target("ceil", listOf("x" to TypeRef.DOUBLE), TypeRef.INT),
            "round" to target("round", listOf("x" to TypeRef.DOUBLE), TypeRef.INT),
            "random" to target("random", emptyList(), TypeRef.DOUBLE),
            "pow" to target("pow", listOf("base" to TypeRef.DOUBLE, "exp" to TypeRef.DOUBLE), TypeRef.DOUBLE),
        ),
        "File" to mapOf(
            "read" to target("read", listOf("path" to TypeRef.STRING), TypeRef.STRING),
            "write" to target("write", listOf("path" to TypeRef.STRING, "content" to TypeRef.STRING), TypeRef.VOID),
            "append" to target("append", listOf("path" to TypeRef.STRING, "content" to TypeRef.STRING), TypeRef.VOID),
            "delete" to target("delete", listOf("path" to TypeRef.STRING), TypeRef.VOID),
            "exists" to target("exists", listOf("path" to TypeRef.STRING), TypeRef.BOOLEAN),
            "list" to target("list", listOf("dir" to TypeRef.STRING), TypeRef("string", 1)),
            "metadata" to target("metadata", listOf("path" to TypeRef.STRING), TypeRef.JSON),
            "createDir" to target("createDir", listOf("path" to TypeRef.STRING), TypeRef.VOID),
        ),
        "Http" to mapOf(
            "get" to target("get", listOf("url" to TypeRef.STRING), TypeRef.STRING),
            "getJson" to target("getJson", listOf("url" to TypeRef.STRING), TypeRef.JSON),
            "post" to target("post", listOf("url" to TypeRef.STRING, "body" to TypeRef.STRING), TypeRef.STRING),
            "put" to target("put", listOf("url" to TypeRef.STRING, "body" to TypeRef.STRING), TypeRef.STRING),
            "delete" to target("delete", listOf("url" to TypeRef.STRING), TypeRef.STRING),
        ),
        "Date" to mapOf(
            "now" to target("now", emptyList(), TypeRef.INT),
        ),
        "Env" to mapOf(
            "get" to target("get", listOf("name" to TypeRef.STRING), TypeRef.STRING),
            "system" to target("system", listOf("property" to TypeRef.STRING), TypeRef.STRING),
        ),
    )

    // Built-in type methods (all use SCALL — identified by CallTarget.name)

    /**
     * Methods available on built-in types (e.g. `string.upper()`, `string.length()`).
     * Key = base type name, value = methods by name.
     */
    private val builtinMethods: Map<String, Map<String, CallTarget>> = mapOf(
        "string" to mapOf(
            "upper" to target("__str_upper", emptyList(), TypeRef.STRING),
            "lower" to target("__str_lower", emptyList(), TypeRef.STRING),
            "contains" to target("__str_contains", listOf("sub" to TypeRef.STRING), TypeRef.BOOLEAN),
            "split" to target("__str_split", listOf("delim" to TypeRef.STRING), TypeRef("string", 1)),
            "length" to target("__str_length", emptyList(), TypeRef.INT),
        ),
        "json" to mapOf(
            "getString" to target("__json_getString", listOf("key" to TypeRef.STRING, "default" to TypeRef.STRING), TypeRef.STRING),
            "getInt" to target("__json_getInt", listOf("key" to TypeRef.STRING, "default" to TypeRef.INT), TypeRef.INT),
            "getBool" to target("__json_getBool", listOf("key" to TypeRef.STRING, "default" to TypeRef.BOOLEAN), TypeRef.BOOLEAN),
            "getDouble" to target("__json_getDouble", listOf("key" to TypeRef.STRING, "default" to TypeRef.DOUBLE), TypeRef.DOUBLE),
            "getJSON" to target("__json_getJSON", listOf("key" to TypeRef.STRING, "default" to TypeRef.JSON), TypeRef.JSON),
            "has" to target("__json_has", listOf("key" to TypeRef.STRING), TypeRef.BOOLEAN),
            "keys" to target("__json_keys", emptyList(), TypeRef("string", 1)),
            "size" to target("__json_size", emptyList(), TypeRef.INT),
            "getIntArray" to target("__json_getIntArray", listOf("key" to TypeRef.STRING, "default" to TypeRef("int", 1)), TypeRef("int", 1)),
            "getStringArray" to target("__json_getStringArray", listOf("key" to TypeRef.STRING, "default" to TypeRef("string", 1)), TypeRef("string", 1)),
            "getDoubleArray" to target("__json_getDoubleArray", listOf("key" to TypeRef.STRING, "default" to TypeRef("double", 1)), TypeRef("double", 1)),
        ),
    )

    /**
     * Array methods are handled specially because they work on any `T[]`.
     * `push` accepts a single element whose type matches the array element type.
     * `pop` returns the array element type.
     * `length` returns the array length.
     */
    private val arrayMethods = setOf("push", "pop", "length")

    /**
     * Type-bound conversion methods (e.g. `int.toDouble()`).
     * All use SCALL — identified by [CallTarget.name].
     */
    private val typeMethods: Map<String, Map<String, CallTarget>> = mapOf(
        "int" to mapOf(
            "toDouble" to target("__int_toDouble", emptyList(), TypeRef.DOUBLE),
            "toString" to target("__int_toString", emptyList(), TypeRef.STRING),
        ),
        "double" to mapOf(
            "toInt" to target("__dbl_toInt", emptyList(), TypeRef.INT),
            "toString" to target("__dbl_toString", emptyList(), TypeRef.STRING),
        ),
        "boolean" to mapOf(
            "toString" to target("__bool_toString", emptyList(), TypeRef.STRING),
        ),
        "string" to mapOf(
            "toInt" to target("__str_toInt", listOf("default" to TypeRef.INT), TypeRef.INT),
            "toDouble" to target("__str_toDouble", listOf("default" to TypeRef.DOUBLE), TypeRef.DOUBLE),
        ),
    )

    /**
     * Whether [name] is a built-in (Tier 0) or external plugin (Tier 1) namespace.
     *
     * Does **not** check import namespaces (Tier 2); those are in the [nox.compiler.types.SymbolTable].
     */
    fun isBuiltinNamespace(name: String): Boolean = name in namespaceFunctions

    /**
     * Look up a namespace function by [namespace] and [funcName].
     *
     * @return the call target, or `null` if not found
     */
    fun lookupNamespaceFunc(namespace: String, funcName: String): CallTarget? =
        namespaceFunctions[namespace]?.get(funcName)

    /**
     * Look up a built-in method on the given [targetType] (e.g. `string.upper()`, `arr.length()`).
     *
     * For array types, recognizes `push`, `pop`, and `length`.
     * For scalar types, checks the built-in method table.
     *
     * @return the call target, or `null` if not found
     */
    fun lookupBuiltinMethod(targetType: TypeRef, methodName: String): CallTarget? {
        // Array methods
        if (targetType.isArray && methodName in arrayMethods) {
            return when (methodName) {
                "push" -> target("__arr_push", listOf("item" to targetType.elementType()), TypeRef.VOID)
                "pop" -> target("__arr_pop", emptyList(), targetType.elementType())
                "length" -> target("__arr_length", emptyList(), TypeRef.INT)
                else -> null
            }
        }
        // Direct type match (e.g. string.upper(), json.size())
        builtinMethods[targetType.name]?.get(methodName)?.let { return it }
        // Struct types can call json methods (implicit upcast)
        if (targetType.isStructType()) {
            builtinMethods["json"]?.get(methodName)?.let { return it }
        }
        return null
    }

    /**
     * Look up a type-bound conversion method (e.g. `int.toDouble()`).
     *
     * @return the call target, or `null` if not found
     */
    fun lookupTypeMethod(targetType: TypeRef, methodName: String): CallTarget? =
        typeMethods[targetType.name]?.get(methodName)

    private fun target(name: String, params: List<Pair<String, TypeRef>>, returnType: TypeRef) =
        CallTarget(name, params, returnType)
}
