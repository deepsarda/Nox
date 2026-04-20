package nox.format

import nox.parser.NoxLexer
import nox.parser.NoxParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

/**
 * Entry point for the Nox source formatter.
 *
 * Parses [source] with the shared Nox grammar, then walks the ParseTree with
 * [DocBuilder] to emit a Wadler-style [Doc] rendering, which is then
 * formatted according to [FormatterConfig].
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
        val tree = parser.program()
        if (parser.numberOfSyntaxErrors > 0) return source
        tokens.fill()

        val doc = DocBuilder(tokens, config).visit(tree)
        val printed = Printer(config).print(doc)

        var cleaned = printed.split('\n').joinToString("\n") { it.trimEnd() }

        while (cleaned.endsWith("\n\n")) {
            cleaned = cleaned.substring(0, cleaned.length - 1)
        }
        if (config.insertFinalNewline && !cleaned.endsWith("\n")) {
            cleaned += "\n"
        } else if (!config.insertFinalNewline && cleaned.endsWith("\n")) {
            cleaned = cleaned.trimEnd('\n')
        }

        val eol = config.endOfLine.sequence()
        if (eol != "\n") cleaned = cleaned.replace("\n", eol)
        return cleaned
    }
}
