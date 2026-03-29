package nox.runtime.json

/**
 * Configurable limits for the JSON parser to prevent abuse.
 *
 * @property maxDepth        maximum nesting depth for objects/arrays (default 64)
 * @property maxStringLength maximum length of a single string value (default 1 MB)
 * @property maxKeys         maximum total number of object keys across the entire document (default 100k)
 */
data class NoxJsonLimits(
    val maxDepth: Int = DEFAULT_DEPTH,
    val maxStringLength: Int = 1_000_000,
    val maxKeys: Int = 100_000,
) {
    companion object {
        const val DEFAULT_DEPTH: Int = 64
    }
}

/**
 * Zero-dependency recursive descent JSON parser that produces exactly
 * the types the Nox VM expects:
 *
 * | JSON          | Kotlin type                   |
 * |---------------|-------------------------------|
 * | integer       | `Long`                        |
 * | decimal/exp   | `Double`                      |
 * | true/false    | `Boolean`                     |
 * | string        | `String`                      |
 * | null          | `null`                        |
 * | object `{}`   | `LinkedHashMap<String, Any?>` |
 * | array  `[]`   | `ArrayList<Any?>`             |
 *
 * Conforms to RFC 8259. Supports full escape sequences including
 * `\uXXXX` surrogate pairs.
 *
 * @param input  the JSON text to parse
 * @param limits configurable safety limits
 */
class NoxJsonParser(
    private val input: String,
    private val limits: NoxJsonLimits = NoxJsonLimits(),
) {
    private var pos: Int = 0
    private var depth: Int = 0
    private var totalKeys: Int = 0

    /**
     * Parse the input and return the top-level value.
     * Throws [IllegalArgumentException] on malformed input or limit violations.
     */
    fun parse(): Any? {
        skipWhitespace()
        if (pos >= input.length) {
            throw error("Empty input")
        }
        val result = parseValue()
        skipWhitespace()
        if (pos < input.length) {
            throw error("Unexpected character '${input[pos]}' after top-level value")
        }
        return result
    }

    private fun parseValue(): Any? {
        if (pos >= input.length) throw error("Unexpected end of input")
        return when (input[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw error("Unexpected character '${input[pos]}'")
        }
    }

    private fun parseObject(): LinkedHashMap<String, Any?> {
        depth++
        if (depth > limits.maxDepth) {
            throw error("Maximum nesting depth (${limits.maxDepth}) exceeded")
        }

        pos++ // consume '{'
        skipWhitespace()

        val map = LinkedHashMap<String, Any?>()

        if (pos < input.length && input[pos] == '}') {
            pos++
            depth--
            return map
        }

        while (true) {
            skipWhitespace()
            if (pos >= input.length || input[pos] != '"') {
                throw error("Expected string key in object")
            }
            val key = parseString()

            totalKeys++
            if (totalKeys > limits.maxKeys) {
                throw error("Maximum number of object keys (${limits.maxKeys}) exceeded")
            }

            skipWhitespace()
            expect(':')
            skipWhitespace()

            val value = parseValue()
            map[key] = value

            skipWhitespace()
            if (pos >= input.length) throw error("Unterminated object")
            when (input[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    depth--
                    return map
                }
                else -> throw error("Expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): ArrayList<Any?> {
        depth++
        if (depth > limits.maxDepth) {
            throw error("Maximum nesting depth (${limits.maxDepth}) exceeded")
        }

        pos++ // consume '['
        skipWhitespace()

        val list = ArrayList<Any?>()

        if (pos < input.length && input[pos] == ']') {
            pos++
            depth--
            return list
        }

        while (true) {
            skipWhitespace()
            list.add(parseValue())

            skipWhitespace()
            if (pos >= input.length) throw error("Unterminated array")
            when (input[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    depth--
                    return list
                }
                else -> throw error("Expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        pos++ // consume opening '"'
        val sb = StringBuilder()

        while (pos < input.length) {
            val ch = input[pos]
            when {
                ch == '"' -> {
                    pos++
                    return sb.toString()
                }
                ch == '\\' -> {
                    pos++
                    if (pos >= input.length) throw error("Unterminated string escape")
                    when (val esc = input[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            pos++
                            val codepoint = parseUnicodeEscape()
                            // Handle surrogate pairs
                            if (codepoint in 0xD800..0xDBFF) {
                                if (pos + 1 < input.length && input[pos] == '\\' && input[pos + 1] == 'u') {
                                    pos++ // consume '\'
                                    pos++ // consume 'u'
                                    val low = parseUnicodeEscape()
                                    if (low in 0xDC00..0xDFFF) {
                                        val combined = 0x10000 + (codepoint - 0xD800) * 0x400 + (low - 0xDC00)
                                        sb.appendCodePoint(combined)
                                    } else {
                                        // Malformed pair, so emit replacement characters
                                        sb.append('\uFFFD')
                                        sb.append(low.toChar())
                                    }
                                } else {
                                    sb.append('\uFFFD')
                                }
                            } else {
                                sb.append(codepoint.toChar())
                            }
                            continue // already advanced past the hex digits
                        }
                        else -> throw error("Invalid escape character '\\$esc'")
                    }
                    pos++
                }
                ch < ' ' -> throw error("Unescaped control character U+${ch.code.toString(16).padStart(4, '0')}")
                else -> {
                    sb.append(ch)
                    pos++
                }
            }

            if (sb.length > limits.maxStringLength) {
                throw error("String length exceeds maximum (${limits.maxStringLength})")
            }
        }
        throw error("Unterminated string")
    }

    private fun parseUnicodeEscape(): Int {
        if (pos + 4 > input.length) {
            throw error("Incomplete unicode escape")
        }
        val hex = input.substring(pos, pos + 4)
        pos += 4
        return try {
            hex.toInt(16)
        } catch (_: NumberFormatException) {
            throw error("Invalid unicode escape '\\u$hex'")
        }
    }

    private fun parseNumber(): Any {
        val start = pos

        // Optional negative sign
        if (pos < input.length && input[pos] == '-') pos++

        // Integer part
        if (pos >= input.length) throw error("Unterminated number")
        if (input[pos] == '0') {
            pos++
        } else if (input[pos] in '1'..'9') {
            pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
        } else {
            throw error("Invalid number")
        }

        var isFloat = false

        // Fractional part
        if (pos < input.length && input[pos] == '.') {
            isFloat = true
            pos++
            if (pos >= input.length || input[pos] !in '0'..'9') {
                throw error("Expected digit after decimal point")
            }
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }

        // Exponent part
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            if (pos >= input.length || input[pos] !in '0'..'9') {
                throw error("Expected digit in exponent")
            }
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }

        val numStr = input.substring(start, pos)

        if (isFloat) {
            return numStr.toDouble()
        }

        // Try Long first, fall back to Double for overflow
        return try {
            numStr.toLong()
        } catch (_: NumberFormatException) {
            numStr.toDouble()
        }
    }

    private fun parseBoolean(): Boolean {
        if (input.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (input.startsWith("false", pos)) {
            pos += 5
            return false
        }
        throw error("Expected 'true' or 'false'")
    }

    private fun parseNull(): Any? {
        if (input.startsWith("null", pos)) {
            pos += 4
            return null
        }
        throw error("Expected 'null'")
    }

    private fun skipWhitespace() {
        while (pos < input.length) {
            when (input[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun expect(ch: Char) {
        if (pos >= input.length || input[pos] != ch) {
            throw error("Expected '$ch'")
        }
        pos++
    }

    private fun error(message: String): IllegalArgumentException = IllegalArgumentException("$message at position $pos")
}
