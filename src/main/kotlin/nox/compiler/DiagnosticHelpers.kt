package nox.compiler

import nox.compiler.DiagnosticHelpers.MAX_EDIT_DISTANCE
import nox.compiler.types.TypeRef
import kotlin.math.min

/**
 * Diagnostic helper utilities for producing high-quality, actionable
 * compiler error messages.
 *
 * Provides:
 * - **Did-you-mean** suggestions via Levenshtein edit distance
 * - **Conversion hints** based on the type compatibility matrix
 * - **Default value hints** for "use a default instead of null"
 */
object DiagnosticHelpers {
    /**
     * Maximum edit distance at which we consider a candidate a plausible typo.
     * Candidates with distance > [MAX_EDIT_DISTANCE] are not suggested.
     */
    private const val MAX_EDIT_DISTANCE = 2

    /**
     * Find the best "did you mean?" suggestion for [input] among [candidates].
     *
     * Returns the closest candidate (by Levenshtein distance) if the distance
     * is <= [MAX_EDIT_DISTANCE], otherwise `null`.
     *
     * Example:
     * ```
     * didYouMean("pritn", listOf("print", "main", "read"))  // "print"
     * didYouMean("xyz",   listOf("abc", "def"))              // null
     * ```
     */
    fun didYouMean(
        input: String,
        candidates: Iterable<String>,
    ): String? {
        var bestCandidate: String? = null
        var bestDistance = Int.MAX_VALUE

        for (candidate in candidates) {
            if (candidate == input) continue // Skip exact matches
            val distance = levenshtein(input.lowercase(), candidate.lowercase())
            if (distance < bestDistance && distance <= MAX_EDIT_DISTANCE) {
                bestDistance = distance
                bestCandidate = candidate
            }
        }
        return bestCandidate
    }

    /**
     * Compute the Levenshtein edit distance between two strings.
     *
     * Uses the standard dynamic-programming algorithm with O(min(m,n)) space.
     */
    fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // Ensure a is the shorter string for O(min(m,n)) space
        val (short, long) = if (a.length <= b.length) a to b else b to a
        var prev = IntArray(short.length + 1) { it }
        var curr = IntArray(short.length + 1)

        for (i in 1..long.length) {
            curr[0] = i
            for (j in 1..short.length) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                curr[j] =
                    min(
                        min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost,
                    )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[short.length]
    }

    /**
     * Return a conversion suggestion for assigning [from] to [to], or `null`
     * if no simple conversion exists.
     *
     * Based on the Nox Type Compatibility Matrix (docs/language/type-system.md).
     */
    fun conversionHint(
        from: TypeRef?,
        to: TypeRef,
    ): String? {
        if (from == null) return null
        if (from == to) return null

        return when {
            // double to int
            from == TypeRef.DOUBLE && to == TypeRef.INT ->
                "Use '.toInt()' to explicitly truncate the double"

            // int to string
            from == TypeRef.INT && to == TypeRef.STRING ->
                "Use '.toString()' to convert, or use string interpolation: `\${value}`"

            // double to string
            from == TypeRef.DOUBLE && to == TypeRef.STRING ->
                "Use '.toString()' to convert, or use string interpolation: `\${value}`"

            // boolean to string
            from == TypeRef.BOOLEAN && to == TypeRef.STRING ->
                "Use '.toString()' to convert, or use string interpolation: `\${value}`"

            // string to int
            from == TypeRef.STRING && to == TypeRef.INT ->
                "Use '.toInt(defaultValue)' to parse the string as an integer"

            // string to double
            from == TypeRef.STRING && to == TypeRef.DOUBLE ->
                "Use '.toDouble(defaultValue)' to parse the string as a double"

            // int to double (this is actually auto, but just in case.)
            from == TypeRef.INT && to == TypeRef.DOUBLE ->
                "Use '.toDouble()' for explicit widening. (This should have happened automatically! Please raise an issue at https://github.com/DeepSarda/Nox/issues)"

            // struct to json (this should be auto, informational)
            from.isStructType() && to == TypeRef.JSON ->
                "Structs are implicitly convertible to 'json'. (This should have happened automatically! Please raise an issue at https://github.com/DeepSarda/Nox/issues)"

            // json to struct
            from == TypeRef.JSON && to.isStructType() ->
                "Use 'as ${to.name}' to cast json to '${to.name}' at runtime"

            else -> null
        }
    }

    /**
     * Return a sensible default value literal for the given [type].
     *
     * Used in suggestions like: "Use a default value instead of null: `int x = 0;`"
     */
    fun defaultValueHint(type: TypeRef): String =
        when {
            type.isArray -> "[]"
            type == TypeRef.INT -> "0"
            type == TypeRef.DOUBLE -> "0.0"
            type == TypeRef.BOOLEAN -> "false"
            type == TypeRef.STRING -> "\"\""
            type == TypeRef.JSON -> "{}"
            type.isStructType() -> "{ /* ... */ }"
            else -> "/* default */"
        }

    /**
     * Format a "did you mean" suggestion string, or return `null` if
     * no close match was found.
     */
    fun didYouMeanMsg(
        input: String,
        candidates: Iterable<String>,
    ): String? {
        val suggestion = didYouMean(input, candidates) ?: return null
        return "Did you mean '$suggestion'?"
    }
}
