package nox.compiler

import nox.compiler.types.SourceLocation

/**
 * A single compiler error with source location, human-readable message,
 * and optional actionable suggestion.
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

    /**
     * Source lines of the file being compiled.
     * Set by [NoxCompiler] after parsing so that [formatAll] can print
     * the offending source line with a caret pointer.
     */
    var sourceLines: List<String> = emptyList()

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
     * Format all errors for human-readable output with rich diagnostics.
     *
     * When [sourceLines] is available, each error is formatted as:
     * ```
     * error: Type Mismatch: cannot assign 'string' to 'int'
     *   --> file.nox:14:5
     *    |
     * 14 |     int x = "hello";
     *    |             ^^^^^^^
     *    = help: Use '.toInt(defaultValue)' to parse the string as an integer
     * ```
     *
     * Falls back to a simple format when source lines are not available.
     */
    fun formatAll(): String =
        buildString {
            for (error in errors) {
                appendLine("error: ${error.message}")
                appendLine("  --> ${error.loc}")

                // Print source line + caret if available
                val lineIdx = error.loc.line - 1
                if (sourceLines.isNotEmpty() && lineIdx in sourceLines.indices) {
                    val sourceLine = sourceLines[lineIdx]
                    val lineNum = error.loc.line.toString()
                    val gutterWidth = lineNum.length

                    appendLine("${" ".repeat(gutterWidth + 1)}|")
                    appendLine("$lineNum | $sourceLine")

                    // Build caret line: spaces up to column, then ^
                    val col = error.loc.column.coerceAtLeast(0)
                    val caretPad = " ".repeat(col)
                    appendLine("${" ".repeat(gutterWidth + 1)}| $caretPad^")
                }

                if (error.suggestion != null) {
                    appendLine("  = help: ${error.suggestion}")
                }
                appendLine()
            }

            // Summary line
            if (errors.isNotEmpty()) {
                val s = if (errors.size == 1) "" else "s"
                appendLine("Found ${errors.size} error$s.")
            }
        }
}
