package nox.lsp.conversions

import nox.compiler.CompilerError
import nox.compiler.CompilerWarning
import nox.lsp.protocol.*

/**
 * Maps compiler errors and warnings to LSP diagnostics. Nox's compiler keeps the suggestion
 * as a free-form string on the error itself, we append it to the message so users see
 * "did you mean X?" in hover without clicking through to code actions.
 */
object Diagnostics {
    private const val SOURCE = "nox"

    fun fromError(err: CompilerError): Diagnostic =
        Diagnostic(
            range = Positions.toLspRange(err.loc),
            severity = DiagnosticSeverity.Error,
            source = SOURCE,
            message = buildMessage(err.message, err.suggestion),
        )

    fun fromWarning(warn: CompilerWarning): Diagnostic =
        Diagnostic(
            range = Positions.toLspRange(warn.location),
            severity = DiagnosticSeverity.Warning,
            source = SOURCE,
            message = buildMessage(warn.message, warn.suggestion),
        )

    fun fromCompilation(result: nox.compiler.NoxCompiler.CompilationResult): List<Diagnostic> {
        val program = result.program
        val errors = result.errors.all().map { fromError(it) }
        val warnings = result.warnings.all().map { fromWarning(it) }
        return errors + warnings
    }

    private fun buildMessage(
        message: String,
        suggestion: String?,
    ): String =
        if (suggestion.isNullOrBlank()) {
            message
        } else {
            "$message\n\nSuggestion: $suggestion"
        }
}
