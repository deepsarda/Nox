package nox.compiler.ast

/**
 * Represents the source position of an AST node within a `.nox` file.
 *
 * Attached to every AST node to enable precise error reporting
 * across all compiler phases (parsing, semantic analysis, codegen).
 *
 * @property file the source filename (e.g. `"script.nox"`)
 * @property line 1-based line number
 * @property column 0-based column offset within the line
 */
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$file:$line:$column"
}
