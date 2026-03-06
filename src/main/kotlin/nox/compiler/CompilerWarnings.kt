package nox.compiler

import nox.compiler.ast.SourceLocation

/**
 * A single compiler warning.
 *
 * Warnings are non-fatal diagnostics, they do not prevent compilation
 * from completing but signal potential issues (e.g. dead code after `return`).
 *
 * @property location   source position of the warning
 * @property message    human-readable description
 * @property suggestion optional remediation hint
 */
data class CompilerWarning(
    val location: SourceLocation,
    val message: String,
    val suggestion: String? = null,
)

/**
 * Collects compiler warnings across all compilation phases.
 *
 * Mirrors [CompilerErrors] but for non-fatal diagnostics.
 * Warnings are accumulated and reported after compilation completes.
 *
 * See docs/compiler/overview.md for the error/warning philosophy.
 */
class CompilerWarnings {
    private val warnings = mutableListOf<CompilerWarning>()

    /**
     * Report a warning at the given [location].
     */
    fun report(location: SourceLocation, message: String, suggestion: String? = null) {
        warnings.add(CompilerWarning(location, message, suggestion))
    }

    /** Whether any warnings have been recorded. */
    fun hasWarnings(): Boolean = warnings.isNotEmpty()

    /** All collected warnings. */
    fun all(): List<CompilerWarning> = warnings.toList()

    /**
     * Format all warnings for display.
     *
     * ```
     * warning: Dead code after return statement
     *   --> script.nox:15:4
     * ```
     */
    fun format(): String = buildString {
        for (w in warnings) {
            appendLine("warning: ${w.message}")
            appendLine("  --> ${w.location}")
            if (w.suggestion != null) {
                appendLine("  = suggestion: ${w.suggestion}")
            }
        }
    }
}
