package nox.lsp.features

import nox.compiler.NoxCompiler
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

/**
 * Compiler suggestions become code actions. Nox attaches a [CompilerError.suggestion]
 * string to many errors; when the suggestion is a single identifier ("did you mean X?"),
 * we apply it as a text replacement at the error site. More structured suggestions fall
 * through as informational actions without an edit.
 */
object CodeActionProvider {
    private val IDENT_SUGGESTION =
        Regex("did you mean\\s+['`\"]?([a-zA-Z_][a-zA-Z0-9_]*)['`\"]?\\??", RegexOption.IGNORE_CASE)

    fun actions(
        result: NoxCompiler.CompilationResult,
        uri: String,
        requestedRange: Range,
    ): List<CodeAction> {
        val out = mutableListOf<CodeAction>()
        result.errors.all().forEach { err ->
            val errRange = Positions.toLspRange(err.loc, length = 1)
            if (!rangesOverlap(errRange, requestedRange)) return@forEach
            val suggestion = err.suggestion ?: return@forEach
            val identMatch = IDENT_SUGGESTION.find(suggestion) ?: return@forEach
            val replacement = identMatch.groupValues[1]
            // Approximate the token length by scanning identifier chars from err location on
            // the current line, compiler errors point at the start of the offending token.
            val tokenRange =
                Range(
                    errRange.start,
                    nox.lsp.protocol.Position(
                        errRange.start.line,
                        errRange.start.character + guessTokenLen(result, err.loc.line, err.loc.column),
                    ),
                )
            val action =
                CodeAction(
                    title = "Replace with '$replacement'",
                    kind = CodeActionKind.QuickFix,
                    diagnostics = listOf(diagnosticFrom(err, errRange)),
                    edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(tokenRange, replacement)))),
                    isPreferred = true,
                )
            out.add(action)
        }
        return out
    }

    private fun diagnosticFrom(
        err: nox.compiler.CompilerError,
        range: Range,
    ): Diagnostic =
        Diagnostic(
            range = range,
            severity = nox.lsp.protocol.DiagnosticSeverity.Error,
            message = err.message,
            source = "nox",
        )

    private fun guessTokenLen(
        result: NoxCompiler.CompilationResult,
        line: Int,
        column: Int,
    ): Int {
        val src = result.program.sourceLines.getOrNull(line - 1) ?: return 1
        if (column >= src.length) return 1
        var end = column
        while (end < src.length && (src[end].isLetterOrDigit() || src[end] == '_')) end++
        return (end - column).coerceAtLeast(1)
    }

    private fun rangesOverlap(
        a: Range,
        b: Range,
    ): Boolean {
        if (a.end.line < b.start.line) return false
        if (a.start.line > b.end.line) return false
        return true
    }
}
