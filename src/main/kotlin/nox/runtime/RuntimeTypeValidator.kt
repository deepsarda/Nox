package nox.runtime

import nox.compiler.ast.typed.TypedProgram
import nox.compiler.types.TypeRef
import nox.runtime.json.NoxJsonParser
import nox.runtime.json.NoxJsonLimits
import nox.vm.NoxException
import java.lang.Double.doubleToRawLongBits

/**
 * Validates and coerces user input strings or maps against a Nox [TypeRef].
 */
object RuntimeTypeValidator {

    /**
     * Recursively verifies that [value] conforms to [type].
     * Parses strings into structured objects when necessary.
     *
     * @param value The input value (e.g. from CLI String or a Map).
     * @param type The expected Nox type.
     * @param program Used to look up TypedStructDefs for struct validation.
     * @return A coerced, fully valid value ready for the Nox VM.
     */
    fun validateAndCoerce(value: Any?, type: TypeRef, program: TypedProgram?): Any? {
        if (value == null) {
            // Only primitives have default runtime zeroes; references are actually null if missing,
            // but the validator shouldn't be passed null unless it's a missing arg which NoxRuntime handles.
            throw NoxException(NoxError.TypeError, "Expected value for type '$type' but got null", 0)
        }

        return when {
            type == TypeRef.INT -> {
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: throw typeError(type, value)
                    else -> throw typeError(type, value)
                }
            }
            type == TypeRef.DOUBLE -> {
                when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull() ?: throw typeError(type, value)
                    else -> throw typeError(type, value)
                }
            }
            type == TypeRef.BOOLEAN -> {
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: throw typeError(type, value)
                    else -> throw typeError(type, value)
                }
            }
            type == TypeRef.STRING -> value.toString()
            type == TypeRef.JSON -> {
                when (value) {
                    is String -> parseJson(value)
                    else -> value // Assume already valid Map/List/primitive from interactive prompt
                }
            }
            type.isArray -> {
                val list = when (value) {
                    is String -> parseJson(value) as? List<*> ?: throw typeError(type, value, "Expected a JSON array")
                    is List<*> -> value
                    else -> throw typeError(type, value)
                }
                val elementType = TypeRef(type.name, type.arrayDepth - 1)
                list.map { validateAndCoerce(it, elementType, program) }
            }
            type.isStructType() -> {
                // TODO: Duplicate struct validation logic in RuntimeTypeValidator and NoxVM should be refactored into a shared StructValidator class
                val map = when (value) {
                    is String -> parseJson(value) as? Map<*, *> ?: throw typeError(type, value, "Expected a JSON object")
                    is Map<*, *> -> value
                    else -> throw typeError(type, value)
                }

                val structDef = program?.typesByName?.get(type.name)
                    ?: throw NoxException(NoxError.CompilationError, "Unknown struct type: ${type.name}", 0)

                // The struct map MUST contain all required fields with the correct types
                val validatedMap = LinkedHashMap<String, Any?>()
                for (field in structDef.fields) {
                    val fieldValue = map[field.name]
                    if (fieldValue == null) {
                        throw NoxException(NoxError.TypeError, "Missing required field '${field.name}' of type '${field.type}' for struct '${type.name}'", 0)
                    }
                    validatedMap[field.name] = validateAndCoerce(fieldValue, field.type, program)
                }
                validatedMap
            }
            else -> value
        }
    }

    private fun parseJson(value: String): Any? {
        try {
            return NoxJsonParser(value).parse()
        } catch (e: Exception) {
            throw NoxException(NoxError.TypeError, "Invalid JSON input: ${e.message}", 0)
        }
    }

    private fun typeError(expected: TypeRef, value: Any?, detail: String? = null): NoxException {
        val typeName = value?.let { it::class.simpleName } ?: "null"
        val msg = "Expected $expected, got $typeName: '$value'" + if (detail != null) " ($detail)" else ""
        return NoxException(NoxError.TypeError, msg, 0)
    }
}
