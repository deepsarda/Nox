package nox.lsp.features

import nox.format.Formatter
import nox.format.FormatterConfig
import nox.lsp.protocol.*

/**
 * Document formatting: replace the whole document with [Formatter]'s output.
 * Range formatting returns the same edit but Nox's formatter currently is document-scoped (no
 * stable selection-local reformat yet), so collapsing to full-document is safer than
 * emitting a partial, possibly-invalid edit. // TODO: Support range formatting in formattor
 */
object FormattingProvider {
    fun format(
        source: String,
        config: FormatterConfig = FormatterConfig.DEFAULT,
    ): List<TextEdit> {
        val formatted = Formatter.format(source, config)
        if (formatted == source) return emptyList()
        return listOf(TextEdit(fullRange(source), formatted))
    }

    private fun fullRange(source: String): Range {
        val lines = source.split('\n')
        val endLine = (lines.size - 1).coerceAtLeast(0)
        val endCol = lines.lastOrNull()?.length ?: 0
        return Range(Position(0, 0), Position(endLine, endCol))
    }
}
