package nox.compiler.types

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

    /**
     * Whether this type is numeric (`int` or `double`, non-array).
     */
    fun isNumeric(): Boolean = !isArray && name in NUMERIC_NAMES

    /**
     * Whether this type represents a user-defined struct.
     *
     * A struct type is any non-array type whose name is not a built-in
     * type name (`int`, `double`, `boolean`, `string`, `json`, `void`).
     */
    fun isStructType(): Boolean = !isArray && name !in BUILTIN_TYPE_NAMES

    /**
     * Whether two types can be compared for equality (`==`, `!=`).
     *
     * Comparability rules:
     * - Same type is always comparable.
     * - `null` (represented as `null` TypeRef) is comparable with any nullable type.
     */
    fun isComparable(other: TypeRef?): Boolean {
        if (other == null) return isNullable()
        if (this == other) return true
        return false
    }

    /**
     * Whether a [value] type can be assigned to a variable of this type.
     *
     * Assignability rules (from `semantic-analysis.md`):
     * - Same type: always ok.
     * - `int to double`: implicit widening.
     * - `null to nullable`: ok for reference types.
     * - `struct to json`: implicit upcast.
     * - Arrays: element types must match exactly.
     */
    fun isAssignableFrom(value: TypeRef?): Boolean {
        // null to any nullable type
        if (value == null) return isNullable()

        // Same type always ok
        if (this == value) return true

        // int to double (implicit widening)
        if (this == DOUBLE && value == INT) return true

        // struct to json (implicit upcast), supports both scalar and array forms
        // e.g. Config to json, Config[] to json[]
        // Note: can't use isStructType() here since it checks !isArray
        if (this.name == "json" && value.name !in BUILTIN_TYPE_NAMES && this.isArray == value.isArray) return true

        // Array element type must match exactly
        if (isArray && value.isArray) return name == value.name

        return false
    }

    override fun toString(): String = if (isArray) "$name[]" else name
}


//TODO: This is really ugly, there should be a better way to do this

/** Names of primitive (non-nullable, pMem-stored) types. */
val PRIMITIVE_NAMES = setOf("int", "double", "boolean")

/** Names of numeric types for arithmetic operations. */
val NUMERIC_NAMES = setOf("int", "double")

/** Non-primitive value type names that are inherently nullable. */
val NULLABLE_VALUE_NAMES = setOf("string", "json")

/** Names of value types that are pass by values. NOTE: string is also immutable to make the interpreter simpler. */
val IMMUTABLE_NAMES = setOf("int", "double", "boolean", "string")

/** All built-in type names. Used by [TypeRef.isStructType] to exclude non-struct types. */
val BUILTIN_TYPE_NAMES = setOf("int", "double", "boolean", "string", "json", "void")
