package nox.lsp.conversions

import nox.compiler.types.SourceLocation
import nox.lsp.protocol.*

/**
 * Converts between Nox's [SourceLocation] (1-based line, 0-based column) and LSP [Position]
 * (0-based line, 0-based column).
 */
object Positions {
    fun toLspPosition(loc: SourceLocation): Position = Position((loc.line - 1).coerceAtLeast(0), loc.column)

    /**
     * Build a zero-width LSP range from a compiler location. Widened to [length] chars on the
     * same line, compiler locations currently point at the start of a token, not a span.
     */
    fun toLspRange(
        loc: SourceLocation,
        length: Int = 1,
    ): Range {
        val start = toLspPosition(loc)
        val end = Position(start.line, start.character + length.coerceAtLeast(0))
        return Range(start, end)
    }

    /** LSP (0-based line, 0-based col) to compiler (1-based line, 0-based col). */
    fun fromLspLineCol(
        line: Int,
        column: Int,
    ): Pair<Int, Int> = (line + 1) to column

    /** Does [loc] fall on the LSP position (line, column)? */
    fun contains(
        loc: SourceLocation,
        lspLine: Int,
        lspColumn: Int,
        length: Int = 1,
    ): Boolean {
        val compilerLine = lspLine + 1
        if (loc.line != compilerLine) return false
        return lspColumn >= loc.column && lspColumn < loc.column + length.coerceAtLeast(1)
    }
}
