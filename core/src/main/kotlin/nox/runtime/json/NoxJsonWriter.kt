package nox.runtime.json

/**
 * Serializes Nox runtime values to JSON text.
 *
 * Handles exactly the types produced by [NoxJsonParser] and the Nox VM:
 * `Long`, `Double`, `Boolean`, `String`, `null`, `Map<String, Any?>`, `List<Any?>`.
 *
 * @param maxDepth maximum nesting depth to prevent infinite recursion on
 *                 circular references (default 64, matches parser default)
 */
class NoxJsonWriter(
    private val maxDepth: Int = NoxJsonLimits.DEFAULT_DEPTH,
    private val prettyPrint: Boolean = true,
) {
    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value, 0)
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeValue(
        sb: StringBuilder,
        value: Any?,
        depth: Int,
    ) {
        when (value) {
            null -> sb.append("null")
            is Long -> sb.append(value)
            is Int -> sb.append(value.toLong())
            is Double -> writeDouble(sb, value)
            is Boolean -> sb.append(value)
            is String -> writeString(sb, value)
            is Map<*, *> -> writeObject(sb, value as Map<String, Any?>, depth)
            is List<*> -> writeArray(sb, value, depth)
            else -> sb.append("null")
        }
    }

    private fun writeDouble(
        sb: StringBuilder,
        value: Double,
    ) {
        when {
            value.isNaN() || value.isInfinite() -> sb.append("null")
            else -> sb.append(value)
        }
    }

    private fun writeString(
        sb: StringBuilder,
        value: String,
    ) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch < ' ') {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private fun writeObject(
        sb: StringBuilder,
        map: Map<String, Any?>,
        depth: Int,
    ) {
        if (depth >= maxDepth) {
            sb.append("null")
            return
        }
        if (!prettyPrint || map.isEmpty()) {
            sb.append('{')
            var first = true
            for ((key, value) in map) {
                if (!first) sb.append(',')
                first = false
                writeString(sb, key)
                sb.append(':')
                writeValue(sb, value, depth + 1)
            }
            sb.append('}')
        } else {
            sb.append('{')
            var first = true
            for ((key, value) in map) {
                if (!first) sb.append(',')
                first = false
                sb.append('\n')
                indent(sb, depth + 1)
                writeString(sb, key)
                sb.append(": ")
                writeValue(sb, value, depth + 1)
            }
            sb.append('\n')
            indent(sb, depth)
            sb.append('}')
        }
    }

    private fun writeArray(
        sb: StringBuilder,
        list: List<*>,
        depth: Int,
    ) {
        if (depth >= maxDepth) {
            sb.append("null")
            return
        }
        if (!prettyPrint || list.isEmpty()) {
            sb.append('[')
            for (i in list.indices) {
                if (i > 0) sb.append(',')
                writeValue(sb, list[i], depth + 1)
            }
            sb.append(']')
        } else {
            sb.append('[')
            for (i in list.indices) {
                if (i > 0) sb.append(',')
                sb.append('\n')
                indent(sb, depth + 1)
                writeValue(sb, list[i], depth + 1)
            }
            sb.append('\n')
            indent(sb, depth)
            sb.append(']')
        }
    }

    private fun indent(
        sb: StringBuilder,
        depth: Int,
    ) {
        repeat(depth) { sb.append(INDENT) }
    }

    companion object {
        private const val INDENT = "  "
    }
}
