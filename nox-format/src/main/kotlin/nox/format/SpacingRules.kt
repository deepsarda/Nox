package nox.format

import nox.parser.NoxLexer
import org.antlr.v4.runtime.Token

/**
 * Token-adjacency spacing policy shared by the whole formatter.
 *
 * The sets classify tokens by how they interact with their neighbours;
 * [needsSpace] is the only entry point DocBuilder uses.
 */
internal object SpacingRules {
    private val NO_SPACE_AFTER =
        setOf(
            NoxLexer.LPAREN,
            NoxLexer.LBRACK,
            NoxLexer.DOT,
            NoxLexer.BANG,
            NoxLexer.TILDE,
            NoxLexer.TEMPLATE_EXPR_OPEN,
            NoxLexer.BACKTICK,
            NoxLexer.ELLIPSIS,
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
            NoxLexer.MINUS_MINUS,
            NoxLexer.RBRACE,
            NoxLexer.TEMPLATE_CLOSE,
        )

    private val CALL_HUGS_PREV =
        setOf(
            NoxLexer.Identifier,
            NoxLexer.RPAREN,
            NoxLexer.RBRACK,
            NoxLexer.MAIN,
            NoxLexer.INT,
            NoxLexer.DOUBLE,
            NoxLexer.BOOLEAN,
            NoxLexer.STRING,
            NoxLexer.JSON,
        )

    fun needsSpace(
        prev: Token,
        next: Token,
    ): Boolean {
        if (prev.type in NO_SPACE_AFTER) return false
        if (next.type == NoxLexer.LPAREN || next.type == NoxLexer.LBRACK) {
            return prev.type !in CALL_HUGS_PREV
        }
        if (next.type in NO_SPACE_BEFORE) return false
        return true
    }
}
