package nox.compiler

import nox.compiler.types.SourceLocation

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
     * Source lines of the file being compiled.
     * Set by [NoxCompiler] so [format] can show the source context.
     */
    var sourceLines: List<String> = emptyList()

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
     * Format all warnings for display with rich diagnostics.
     *
     * ```
     * warning: Unreachable code. This statement will never execute.
     *   --> script.nox:15:4
     *    |
     * 15 |     int x = 0;
     *    |     ^
     *    = help: Remove this code or move the 'return'/'throw'/'break' before it.
     * ```
     */
    fun format(): String = buildString {
        for (w in warnings) {
            appendLine("warning: ${w.message}")
            appendLine("  --> ${w.location}")

            val lineIdx = w.location.line - 1
            if (sourceLines.isNotEmpty() && lineIdx in sourceLines.indices) {
                val sourceLine = sourceLines[lineIdx]
                val lineNum = w.location.line.toString()
                val gutterWidth = lineNum.length

                appendLine("${" ".repeat(gutterWidth + 1)}|")
                appendLine("$lineNum | $sourceLine")

                val col = w.location.column.coerceAtLeast(0)
                val caretPad = " ".repeat(col)
                appendLine("${" ".repeat(gutterWidth + 1)}| $caretPad^")
            }

            if (w.suggestion != null) {
                appendLine("  = help: ${w.suggestion}")
            }
            appendLine()
        }

        if (warnings.isNotEmpty()) {
            val s = if (warnings.size == 1) "" else "s"
            appendLine("Generated ${warnings.size} warning$s.")
        }
    }
}
