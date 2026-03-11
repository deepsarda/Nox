package nox.compiler.codegen

import nox.compiler.types.TypeRef

/**
 * Describes the shape of a struct type for runtime validation by `CAST_STRUCT`.
 *
 * Stored in the [ConstantPool] and referenced by pool index from the
 * `CAST_STRUCT` instruction's C operand.
 *
 * @property name   the struct type name (e.g. "ApiConfig", "TreeNode")
 * @property fields ordered map of field name to [FieldSpec]
 */
data class TypeDescriptor(
    val name: String,
    val fields: LinkedHashMap<String, FieldSpec>,
)

/**
 * Describes the expected type of a single struct field for runtime validation.
 *
 * Primitive and built-in types use singleton objects. Nested structs reference
 * another [TypeDescriptor] by its constant-pool index, enabling recursive and
 * mutually-referencing struct types.
 */
sealed class FieldSpec {
    data object INT : FieldSpec()
    data object DOUBLE : FieldSpec()
    data object BOOLEAN : FieldSpec()
    data object STRING : FieldSpec()
    data object JSON : FieldSpec()

    /** A nested struct field; [descriptorIdx] is the pool index of its [TypeDescriptor]. */
    data class Struct(val descriptorIdx: Int) : FieldSpec()

    /** A typed array field; each element must match [element]. */
    data class TypedArray(val element: FieldSpec) : FieldSpec()

    companion object {
        /** Convert a [TypeRef] to the corresponding [FieldSpec], building descriptors as needed. */
        fun from(
            typeRef: TypeRef,
            descriptorBuilder: (String) -> Int,
        ): FieldSpec {
            if (typeRef.isArray) {
                val elemType = typeRef.elementType()
                return TypedArray(from(elemType, descriptorBuilder))
            }
            return when (typeRef.name) {
                "int" -> INT
                "double" -> DOUBLE
                "boolean" -> BOOLEAN
                "string" -> STRING
                "json" -> JSON
                else -> Struct(descriptorBuilder(typeRef.name))
            }
        }
    }
}
