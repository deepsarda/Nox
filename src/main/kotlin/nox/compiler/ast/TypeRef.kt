package nox.compiler.ast

/**
 * A syntactic reference to a type as it appears in source code.
 *
 * This is **not** a resolved type, resolution happens during semantic analysis.
 * `TypeRef` simply captures what the programmer wrote (e.g. `int`, `string[]`,
 * `ApiConfig`).
 *
 * @property name the base type name (e.g. `"int"`, `"string"`, `"Point"`)
 * @property isArray whether this is an array type (e.g. `int[]`)
 */
data class TypeRef(
    val name: String,
    val isArray: Boolean = false,
) {
    companion object {
        val INT = TypeRef("int")
        val DOUBLE = TypeRef("double")
        val BOOLEAN = TypeRef("boolean")
        val STRING = TypeRef("string")
        val JSON = TypeRef("json")
        val VOID = TypeRef("void")
    }

    /**
     * Primitive types are stored directly in the primitive register bank (`pMem`).
     * A type is primitive if it is a non-array `int`, `double`, or `boolean`.
     */
    fun isPrimitive(): Boolean = !isArray && name in PRIMITIVE_NAMES

    fun isPassByValue(): Boolean = !isArray && name in IMMUTABLE_NAMES

    /**
     * Nullable types are reference types that can hold `null`:
     * arrays, strings, json, and user-defined struct types.
     * Primitives (`int`, `double`, `boolean`) are never nullable.
     */
    fun isNullable(): Boolean = isArray || name in NULLABLE_VALUE_NAMES || !isPrimitive()

    override fun toString(): String = if (isArray) "$name[]" else name
}

/** Names of primitive (non-nullable, pMem-stored) types. */
private val PRIMITIVE_NAMES = setOf("int", "double", "boolean")

/** Non-primitive value type names that are inherently nullable. */
private val NULLABLE_VALUE_NAMES = setOf("string", "json")

/** Names of value types that are pass by values. NOTE: string is also immutable to make the interpreter simpler. */
private val IMMUTABLE_NAMES = setOf("int", "double", "boolean", "string")
