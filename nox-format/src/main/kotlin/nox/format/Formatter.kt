package nox.format

import nox.parser.NoxLexer
import nox.parser.NoxParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/**
 * Entry point for the Nox source formatter.
 *
 * Parses [source] with the shared Nox grammar, then walks the token stream with
 * [CstWalker] to emit a formatted rendering. Comments are preserved via the lexer's
 * HIDDEN channel; whitespace is normalized.
 *
 * Parse errors cause the formatter to return [source] unchanged (duh).
 */
object Formatter {
    fun format(
        source: String,
        config: FormatterConfig = FormatterConfig.DEFAULT,
    ): String {
        if (source.isEmpty()) return ""

        val lexer = NoxLexer(CharStreams.fromString(source))
        val tokens = CommonTokenStream(lexer)
        val parser = NoxParser(tokens)
        parser.removeErrorListeners()
        parser.program()
        if (parser.numberOfSyntaxErrors > 0) return source
        tokens.fill()

        return CstWalker(tokens, config).render()
    }
}
