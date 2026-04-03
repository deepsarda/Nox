package nox.compiler.parsing

import nox.compiler.CompilerErrors
import nox.compiler.ast.Program
import nox.compiler.types.SourceLocation
import nox.parser.NoxLexer
import nox.parser.NoxParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/**
 * Convenience entry point for parsing `.nox` source code into an AST.
 *
 * Handles ANTLR setup (lexer, token stream, parser) and drives the
 * [ASTBuilder] visitor to produce a [Program].
 */
object NoxParsing {
    /**
     * Parse a `.nox` source string into a [Program] AST.
     *
     * @param source   the raw source code
     * @param fileName the file name to attach to [nox.compiler.types.SourceLocation]s (defaults to `"<input>"`)
     * @param errors   [CompilerErrors] collector used to report syntax and semantic errors
     * @return the parsed [Program]
     */
    fun parse(
        source: String,
        fileName: String = "<input>",
        errors: CompilerErrors = CompilerErrors(),
    ): Program {
        val lexer = NoxLexer(CharStreams.fromString(source, fileName))
        val tokens = CommonTokenStream(lexer)
        val parser = NoxParser(tokens)

        val errorListener =
            object : BaseErrorListener() {
                override fun syntaxError(
                    recognizer: Recognizer<*, *>?,
                    offendingSymbol: Any?,
                    line: Int,
                    charPositionInLine: Int,
                    msg: String,
                    e: RecognitionException?,
                ) {
                    val loc = SourceLocation(fileName, line, charPositionInLine)
                    errors.report(loc, "Syntax Error: $msg")
                }
            }

        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        val tree = parser.program()
        if (errors.hasErrors()) {
            return Program(fileName, emptyList(), emptyList(), emptyList())
        }
        val builder = ASTBuilder(fileName, errors)
        return builder.visitProgram(tree)
    }
}
