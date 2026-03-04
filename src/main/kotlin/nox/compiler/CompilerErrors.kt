package nox.compiler

import nox.compiler.ast.SourceLocation

/**
 * A single compiler error with source location and optional suggestion.
 *
 * @property loc        where in the source the error occurred
 * @property message    human-readable description of the problem
 * @property suggestion optional hint for fixing the issue
 */
data class CompilerError(
    val loc: SourceLocation,
    val message: String,
    val suggestion: String? = null,
)

/**
 * Collects compiler errors across all phases without failing fast.
 *
 * Each compiler phase (parsing, semantic analysis, codegen) reports
 * errors into this collector. Subsequent phases only run if this
 * collector is empty after the previous phase.
 *
 * This approach gives the developer a **full list** of issues to fix
 * in one pass rather than stopping at the first error.
 */
class CompilerErrors {
    private val errors = mutableListOf<CompilerError>()

    /** Report an error at the given source location. */
    fun report(
        loc: SourceLocation,
        message: String,
        suggestion: String? = null,
    ) {
        errors.add(CompilerError(loc, message, suggestion))
    }

    /** Whether any errors have been recorded. */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /** The number of errors recorded so far. */
    val count: Int get() = errors.size

    /** An unmodifiable view of all recorded errors. */
    fun all(): List<CompilerError> = errors.toList()

    /**
     * Format all errors for human-readable output.
     *
     * Each error is formatted as:
     * ```
     * Error at file.nox:14:25: Type Mismatch: ...
     *   Suggestion: ...
     * ```
     */
    fun formatAll(): String =
        buildString {
            for (error in errors) {
                append("Error at ${error.loc}: ${error.message}")
                if (error.suggestion != null) {
                    append("\n  Suggestion: ${error.suggestion}")
                }
                appendLine()
            }
        }
}
