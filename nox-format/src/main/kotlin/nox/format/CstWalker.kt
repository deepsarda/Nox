package nox.format

import nox.parser.NoxLexer
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token

/**
 * Token-stream pretty-printer. Walks visible tokens in order, applies spacing/indent
 * rules, and emits comments from the hidden channel verbatim.
 *
 * Design choices:
 * - Indent depth tracks `{` / `}`. Template literal interpolations push their own indent
 *   context so nested braces inside `${...}` don't misalign outer code.
 * - Inside `for (init; cond; update)` headers, semicolons do not trigger newlines. A
 *   small state machine (`forHeaderParens`) tracks depth.
 * - Inside backtick template literals (BACKTICK..TEMPLATE_CLOSE), everything is emitted
 *   verbatim; template text is semantically whitespace-significant.
 * - Trailing comments on a line are preserved inline; standalone comments keep their
 *   leading blank lines (capped at one blank).
 */
internal class CstWalker(
    private val tokens: CommonTokenStream,
    private val config: FormatterConfig,
) {
    private val out = StringBuilder()
    private var indent = 0
    private var atLineStart = true
    private var forHeaderParens = ArrayDeque<Int>() // depth snapshots when `for(` opens
    private var parenDepth = 0
    private var inTemplate = 0 // BACKTICK nesting level

    fun render(): String {
        val visible = tokens.tokens.filter { it.channel == Token.DEFAULT_CHANNEL && it.type != Token.EOF }
        if (visible.isEmpty()) {
            return emitTrailingTrivia(tokens.tokens)
        }

        for ((i, tok) in visible.withIndex()) {
            emitLeadingTrivia(tok, prev = visible.getOrNull(i - 1))
            emitToken(tok, prev = visible.getOrNull(i - 1), next = visible.getOrNull(i + 1))
        }
        emitFinalTrivia(visible.last())

        // Ensure exactly one trailing newline
        while (out.isNotEmpty() && out.last() == '\n') out.deleteCharAt(out.length - 1)
        out.append('\n')
        return out.toString()
    }

    private fun emitLeadingTrivia(
        tok: Token,
        prev: Token?,
    ) {
        val hidden = tokens.getHiddenTokensToLeft(tok.tokenIndex) ?: return
        if (hidden.isEmpty()) return

        // Count blank lines between prev visible token and this one in the original source.
        // Used to preserve at most one blank line between logical groups.
        val originalBlankLines =
            if (prev == null) 0 else blankLinesBetween(prev, tok)

        var emittedBlank = false
        for (h in hidden) {
            when (h.type) {
                NoxLexer.LINE_COMMENT, NoxLexer.BLOCK_COMMENT -> {
                    if (atLineStart) {
                        if (!emittedBlank && originalBlankLines > 0 && out.isNotEmpty()) {
                            out.append('\n')
                            emittedBlank = true
                        }
                        writeIndent()
                        out.append(h.text.trim())
                        newline()
                    } else {
                        // Trailing comment on same line as previous token
                        out.append(' ')
                        out.append(h.text.trim())
                        if (h.type == NoxLexer.LINE_COMMENT) newline()
                    }
                }
                NoxLexer.WS -> {
                    // absorbed since spacing is our responsibility
                }
            }
        }

        if (atLineStart && originalBlankLines > 0 && !emittedBlank && out.isNotEmpty()) {
            out.append('\n')
        }
    }

    private fun emitTrailingTrivia(all: List<Token>): String {
        // No visible tokens either empty or comments-only source. Emit comments verbatim.
        val triviaLines = mutableListOf<String>()
        for (t in all) {
            if (t.type == NoxLexer.LINE_COMMENT || t.type == NoxLexer.BLOCK_COMMENT) {
                triviaLines += t.text.trim()
            }
        }
        return if (triviaLines.isEmpty()) "" else triviaLines.joinToString("\n", postfix = "\n")
    }

    private fun emitFinalTrivia(lastVisible: Token) {
        // Comments after the last visible token (before EOF)
        val hidden = tokens.getHiddenTokensToRight(lastVisible.tokenIndex) ?: return
        for (h in hidden) {
            if (h.type == NoxLexer.LINE_COMMENT || h.type == NoxLexer.BLOCK_COMMENT) {
                if (!atLineStart) {
                    out.append(' ')
                } else {
                    writeIndent()
                }
                out.append(h.text.trim())
                newline()
            }
        }
    }

    private fun blankLinesBetween(
        a: Token,
        b: Token,
    ): Int {
        // Count newline characters in the trivia between tokens; blank line = 2+ newlines.
        val start = a.stopIndex + 1
        val end = b.startIndex
        if (start >= end) return 0
        val input =
            a.inputStream.getText(
                org.antlr.v4.runtime.misc
                    .Interval(start, end - 1),
            )
        val nl = input.count { it == '\n' }
        return (nl - 1).coerceAtLeast(0)
    }

    private fun emitToken(
        tok: Token,
        prev: Token?,
        next: Token?,
    ) {
        // Inside template literals: emit everything verbatim (including the opening BACKTICK,
        // but spacing BEFORE the BACKTICK follows normal rules).
        if (inTemplate > 0 && tok.type != NoxLexer.TEMPLATE_CLOSE && tok.type != NoxLexer.BACKTICK) {
            // Tokens inside ${...} follow normal rules; raw template text emits verbatim.
            if (tok.type == NoxLexer.TEMPLATE_TEXT) {
                out.append(tok.text)
                atLineStart = false
                return
            }
        }

        when (tok.type) {
            NoxLexer.LBRACE -> {
                spaceBefore(tok, prev)
                out.append('{')
                indent++
                newline()
            }
            NoxLexer.RBRACE -> {
                if (inTemplate > 0) {
                    // Closing `}` of a `${...}` interpolation emit verbatim with no indent.
                    out.append('}')
                    atLineStart = false
                } else {
                    indent = (indent - 1).coerceAtLeast(0)
                    if (!atLineStart) newline()
                    writeIndent()
                    out.append('}')
                    atLineStart = false
                }
            }
            NoxLexer.SEMI -> {
                out.append(';')
                atLineStart = false
                // Don't newline in for-header
                if (!inForHeader()) newline()
            }
            NoxLexer.COMMA -> {
                out.append(',')
                atLineStart = false
                // Single space after comma; no newline in v1 (no auto-wrap)
                if (next != null && next.type != NoxLexer.RPAREN && next.type != NoxLexer.RBRACK) {
                    out.append(' ')
                }
            }
            NoxLexer.LPAREN -> {
                spaceBefore(tok, prev)
                out.append('(')
                atLineStart = false
                parenDepth++
                if (prev?.type == NoxLexer.FOR) {
                    forHeaderParens.addLast(parenDepth)
                }
            }
            NoxLexer.RPAREN -> {
                if (forHeaderParens.isNotEmpty() && forHeaderParens.last() == parenDepth) {
                    forHeaderParens.removeLast()
                }
                parenDepth = (parenDepth - 1).coerceAtLeast(0)
                out.append(')')
                atLineStart = false
            }
            NoxLexer.LBRACK -> {
                out.append('[')
                atLineStart = false
            }
            NoxLexer.RBRACK -> {
                out.append(']')
                atLineStart = false
            }
            NoxLexer.DOT -> {
                out.append('.')
                atLineStart = false
            }
            NoxLexer.ELLIPSIS -> {
                spaceBefore(tok, prev)
                out.append("...")
                atLineStart = false
            }
            NoxLexer.COLON -> {
                out.append(':')
                atLineStart = false
                // space after colon in struct literals
                if (next != null) out.append(' ')
            }
            NoxLexer.BACKTICK -> {
                spaceBefore(tok, prev)
                out.append('`')
                atLineStart = false
                inTemplate++
            }
            NoxLexer.TEMPLATE_CLOSE -> {
                out.append('`')
                atLineStart = false
                inTemplate = (inTemplate - 1).coerceAtLeast(0)
            }
            NoxLexer.TEMPLATE_EXPR_OPEN -> {
                out.append("\${")
                atLineStart = false
            }
            // Unary-prefix operators: no space after
            NoxLexer.BANG, NoxLexer.TILDE -> {
                spaceBefore(tok, prev)
                out.append(tok.text)
                atLineStart = false
            }
            // MINUS is ambiguous (unary vs binary). Treat as binary unless prev is an operator
            // or opener.
            NoxLexer.MINUS -> {
                if (isUnaryContext(prev)) {
                    spaceBefore(tok, prev)
                    out.append('-')
                } else {
                    binaryOp(tok, prev)
                }
                atLineStart = false
            }
            // Postfix ++/-- : no space before when prev is expression
            NoxLexer.PLUS_PLUS, NoxLexer.MINUS_MINUS -> {
                if (isUnaryContext(prev)) {
                    spaceBefore(tok, prev)
                    out.append(tok.text)
                } else {
                    out.append(tok.text)
                }
                atLineStart = false
            }
            // Binary operators
            NoxLexer.PLUS, NoxLexer.STAR, NoxLexer.SLASH, NoxLexer.PERCENT,
            NoxLexer.AMPERSAND, NoxLexer.PIPE, NoxLexer.CARET,
            NoxLexer.SHL, NoxLexer.SHR, NoxLexer.USHR,
            NoxLexer.LT, NoxLexer.LE, NoxLexer.GT, NoxLexer.GE,
            NoxLexer.EQ, NoxLexer.NE,
            NoxLexer.AND, NoxLexer.OR,
            NoxLexer.ASSIGN, NoxLexer.PLUS_ASSIGN, NoxLexer.MINUS_ASSIGN,
            NoxLexer.STAR_ASSIGN, NoxLexer.SLASH_ASSIGN, NoxLexer.PERCENT_ASSIGN,
            -> {
                binaryOp(tok, prev)
                atLineStart = false
            }
            // Keywords
            NoxLexer.IF, NoxLexer.ELSE, NoxLexer.WHILE, NoxLexer.FOR, NoxLexer.FOREACH,
            NoxLexer.RETURN, NoxLexer.YIELD, NoxLexer.BREAK, NoxLexer.CONTINUE,
            NoxLexer.THROW, NoxLexer.TRY, NoxLexer.CATCH,
            NoxLexer.IMPORT, NoxLexer.AS, NoxLexer.IN, NoxLexer.TYPE, NoxLexer.MAIN,
            NoxLexer.NULL, NoxLexer.TRUE, NoxLexer.FALSE,
            NoxLexer.INT, NoxLexer.DOUBLE, NoxLexer.BOOLEAN, NoxLexer.STRING,
            NoxLexer.JSON, NoxLexer.VOID,
            -> {
                spaceBefore(tok, prev)
                out.append(tok.text)
                atLineStart = false
            }
            NoxLexer.HEADER_KEY -> {
                if (!atLineStart) newline()
                writeIndent()
                out.append(tok.text)
                atLineStart = false
            }
            else -> {
                spaceBefore(tok, prev)
                out.append(tok.text)
                atLineStart = false
            }
        }
    }

    private fun inForHeader(): Boolean = forHeaderParens.isNotEmpty()

    private fun isUnaryContext(prev: Token?): Boolean {
        if (prev == null) return true
        return when (prev.type) {
            NoxLexer.LPAREN, NoxLexer.LBRACK, NoxLexer.LBRACE,
            NoxLexer.COMMA, NoxLexer.SEMI, NoxLexer.COLON,
            NoxLexer.ASSIGN, NoxLexer.PLUS_ASSIGN, NoxLexer.MINUS_ASSIGN,
            NoxLexer.STAR_ASSIGN, NoxLexer.SLASH_ASSIGN, NoxLexer.PERCENT_ASSIGN,
            NoxLexer.PLUS, NoxLexer.MINUS, NoxLexer.STAR, NoxLexer.SLASH, NoxLexer.PERCENT,
            NoxLexer.AMPERSAND, NoxLexer.PIPE, NoxLexer.CARET,
            NoxLexer.SHL, NoxLexer.SHR, NoxLexer.USHR,
            NoxLexer.LT, NoxLexer.LE, NoxLexer.GT, NoxLexer.GE,
            NoxLexer.EQ, NoxLexer.NE, NoxLexer.AND, NoxLexer.OR,
            NoxLexer.BANG, NoxLexer.TILDE,
            NoxLexer.RETURN, NoxLexer.YIELD, NoxLexer.THROW, NoxLexer.AS, NoxLexer.IN,
            NoxLexer.TEMPLATE_EXPR_OPEN,
            -> true
            else -> false
        }
    }

    private fun binaryOp(
        tok: Token,
        prev: Token?,
    ) {
        if (prev != null && !atLineStart && !out.endsWith(' ')) out.append(' ')
        out.append(tok.text)
    }

    private fun spaceBefore(
        tok: Token,
        prev: Token?,
    ) {
        if (atLineStart) {
            writeIndent()
            return
        }
        if (prev == null) return
        if (needsSpace(prev, tok)) out.append(' ')
    }

    private fun needsSpace(
        prev: Token,
        next: Token,
    ): Boolean {
        // Never space after these opener-like tokens
        if (prev.type in NO_SPACE_AFTER) return false
        // LPAREN / LBRACK: hug the previous token for calls/indexing (`foo(`, `arr[`), but
        // space after control-flow keywords so `if (x)` not `if(x)`.
        if (next.type == NoxLexer.LPAREN || next.type == NoxLexer.LBRACK) {
            return prev.type !in CALL_HUGS_PREV
        }
        if (next.type in NO_SPACE_BEFORE) return false
        return true
    }

    private fun writeIndent() {
        if (!atLineStart) return
        repeat(indent) { out.append(config.indent.unit) }
        atLineStart = false
    }

    private fun newline() {
        if (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
        out.append('\n')
        atLineStart = true
    }

    companion object {
        private val NO_SPACE_AFTER =
            setOf(
                NoxLexer.LPAREN,
                NoxLexer.LBRACK,
                NoxLexer.DOT,
                NoxLexer.BANG,
                NoxLexer.TILDE,
                NoxLexer.TEMPLATE_EXPR_OPEN,
                NoxLexer.BACKTICK,
            )
        private val NO_SPACE_BEFORE =
            setOf(
                NoxLexer.RPAREN,
                NoxLexer.RBRACK,
                NoxLexer.COMMA,
                NoxLexer.SEMI,
                NoxLexer.DOT,
                NoxLexer.COLON,
                NoxLexer.PLUS_PLUS,
                NoxLexer.MINUS_MINUS, // postfix
            )

        /** Token types that "hug" a following LPAREN/LBRACK: call receivers, index chains. */
        private val CALL_HUGS_PREV =
            setOf(
                NoxLexer.Identifier,
                NoxLexer.RPAREN,
                NoxLexer.RBRACK,
                NoxLexer.MAIN, // main( ... )
            )
    }
}
