package nox.format

import nox.parser.NoxLexer
import nox.parser.NoxParser
import nox.parser.NoxParserBaseVisitor
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * ParseTree to [Doc] visitor. Walks the tree left-to-right, tracking
 * whitespace/brace state in [EmitterState] and delegating hidden-channel
 * trivia to [TriviaEmitter]. Spacing policy lives in [SpacingRules].
 */
class DocBuilder(
    private val tokens: CommonTokenStream,
    private val config: FormatterConfig,
) : NoxParserBaseVisitor<Doc>() {
    private val state = EmitterState()
    private val trivia = TriviaEmitter(tokens, state, config)

    private val parenSoftLine: Doc
        get() = Doc.SoftLine(if (config.parenSpacing) " " else "")

    private val structSoftLine: Doc
        get() = Doc.SoftLine(if (config.bracketSpacing) " " else "")

    override fun defaultResult(): Doc = Doc.empty

    override fun aggregateResult(
        aggregate: Doc,
        nextResult: Doc,
    ): Doc {
        if (aggregate === Doc.empty) return nextResult
        if (nextResult === Doc.empty) return aggregate
        return Doc.concat(aggregate, nextResult)
    }

    override fun visitTerminal(node: TerminalNode): Doc {
        val tok = node.symbol
        if (tok.type == Token.EOF) return trivia.emitFinal(tok)

        val leading = trivia.emitLeading(tok)
        if (tok.type == NoxLexer.RBRACE) {
            state.braceDepth = maxOf(0, state.braceDepth - 1)
        }
        val text = emitTokenOnly(tok)
        val trailing = trivia.emitTrailing(tok)
        if (tok.type == NoxLexer.LBRACE) {
            state.braceDepth++
        }
        return Doc.concat(leading, text, trailing)
    }

    private fun emitTokenOnly(tok: Token): Doc {
        val docs = mutableListOf<Doc>()
        val last = state.lastTok
        if (!state.atLineStart && last != null && !state.suppressNextSpace) {
            val isTemplateContent =
                tok.type == NoxLexer.TEMPLATE_TEXT ||
                    tok.type == NoxLexer.TEMPLATE_CLOSE ||
                    last.type == NoxLexer.TEMPLATE_TEXT ||
                    last.type == NoxLexer.BACKTICK ||
                    last.type == NoxLexer.TEMPLATE_EXPR_OPEN
            if (!isTemplateContent && SpacingRules.needsSpace(last, tok)) {
                docs.add(Doc.text(" "))
            }
        }
        state.suppressNextSpace = false
        docs.add(Doc.text(tok.text))
        state.atLineStart = false
        state.lastTok = tok
        return Doc.concat(*docs.toTypedArray())
    }

    private fun emitLine(): Doc {
        state.atLineStart = true
        return Doc.Line
    }

    // ---------- AST overrides for layout/wrapping ----------

    override fun visitBlock(ctx: NoxParser.BlockContext): Doc {
        val lbraceToken = ctx.LBRACE().symbol
        val rbraceToken = ctx.RBRACE().symbol
        val lbrace = visitTerminal(ctx.LBRACE())
        state.suppressNextSpace = true

        val hiddenBetween = tokens.getHiddenTokensToRight(lbraceToken.tokenIndex)
        val hasComments =
            hiddenBetween?.any {
                it.tokenIndex < rbraceToken.tokenIndex &&
                    (it.type == NoxLexer.LINE_COMMENT || it.type == NoxLexer.BLOCK_COMMENT)
            } == true

        if (ctx.childCount == 2) {
            state.suppressNextSpace = true
            val rbraceTrivia = trivia.emitLeading(rbraceToken, stripTrailingNewline = true)
            if (!hasComments) state.suppressNextSpace = true
            state.braceDepth = maxOf(0, state.braceDepth - 1)
            return if (hasComments) {
                state.atLineStart = true
                val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
                Doc.concat(lbrace, Doc.indent(rbraceTrivia), Doc.Line, rbraceText)
            } else {
                val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
                Doc.concat(lbrace, rbraceText)
            }
        }

        var hasNewlines = false
        val next = trivia.getNextVisibleToken(lbraceToken)
        if (next != null && trivia.hasNewlineBetween(lbraceToken, next)) {
            hasNewlines = true
        }

        val docs = mutableListOf<Doc>()
        if (hasNewlines || hasComments) docs.add(emitLine())

        var first = true
        for (i in 1 until ctx.childCount - 1) {
            if (!first && (hasNewlines || hasComments)) {
                if (!state.atLineStart) docs.add(emitLine())
            }
            docs.add(visit(ctx.getChild(i)))
            first = false
        }

        val rbraceTrivia = trivia.emitLeading(rbraceToken, stripTrailingNewline = true)
        val inner = Doc.concat(*docs.toTypedArray())

        return if (hasNewlines || hasComments) {
            val line = if (state.atLineStart) Doc.empty else emitLine()
            state.braceDepth = maxOf(0, state.braceDepth - 1)
            val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
            Doc.concat(lbrace, Doc.indent(inner), rbraceTrivia, line, rbraceText)
        } else {
            state.braceDepth = maxOf(0, state.braceDepth - 1)
            val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
            Doc.concat(lbrace, Doc.SoftLine(" "), inner, Doc.SoftLine(" "), rbraceTrivia, rbraceText)
        }
    }

    override fun visitTypeDefinition(ctx: NoxParser.TypeDefinitionContext): Doc {
        val docs = mutableListOf<Doc>()
        var i = 0
        while (i < ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is TerminalNode && child.symbol.type == NoxLexer.LBRACE) {
                val lbraceToken = child.symbol
                val lbrace = visitTerminal(child)
                state.suppressNextSpace = true
                var hasNewlines = false
                val next = trivia.getNextVisibleToken(lbraceToken)
                if (next != null && trivia.hasNewlineBetween(lbraceToken, next)) {
                    hasNewlines = true
                }

                val innerDocs = mutableListOf<Doc>()
                i++

                if (hasNewlines) innerDocs.add(emitLine())

                var first = true
                while (i < ctx.childCount &&
                    !(
                        ctx.getChild(i) is TerminalNode &&
                            (ctx.getChild(i) as TerminalNode).symbol.type == NoxLexer.RBRACE
                    )
                ) {
                    if (!first && hasNewlines && !state.atLineStart) innerDocs.add(emitLine())
                    innerDocs.add(visit(ctx.getChild(i)))
                    first = false
                    i++
                }

                if (i < ctx.childCount) {
                    val rbraceNode = ctx.getChild(i) as TerminalNode
                    val rbraceToken = rbraceNode.symbol
                    val rbraceTrivia = trivia.emitLeading(rbraceToken, stripTrailingNewline = true)
                    val inner = Doc.concat(*innerDocs.toTypedArray())

                    docs.add(
                        if (hasNewlines) {
                            val line = if (state.atLineStart) Doc.empty else emitLine()
                            state.braceDepth = maxOf(0, state.braceDepth - 1)
                            val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
                            Doc.concat(lbrace, Doc.indent(inner), rbraceTrivia, line, rbraceText)
                        } else {
                            state.braceDepth = maxOf(0, state.braceDepth - 1)
                            val rbraceText = Doc.concat(emitTokenOnly(rbraceToken), trivia.emitTrailing(rbraceToken))
                            Doc.concat(lbrace, Doc.SoftLine(" "), inner, Doc.SoftLine(" "), rbraceTrivia, rbraceText)
                        },
                    )
                } else {
                    val inner = Doc.concat(*innerDocs.toTypedArray())
                    docs.add(Doc.concat(lbrace, Doc.indent(inner)))
                }
            } else {
                docs.add(visit(child))
            }
            i++
        }
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitProgram(ctx: NoxParser.ProgramContext): Doc {
        val docs = mutableListOf<Doc>()
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            docs.add(visit(child))
            if (i < ctx.childCount - 1 && child !is TerminalNode) {
                docs.add(emitLine())
            }
        }
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitFunctionDefinition(ctx: NoxParser.FunctionDefinitionContext): Doc =
        visitParenDelimited(ctx, ctx.parameterList())

    override fun visitMainDefinition(ctx: NoxParser.MainDefinitionContext): Doc =
        visitParenDelimited(ctx, ctx.parameterList())

    override fun visitFuncCallExpr(ctx: NoxParser.FuncCallExprContext): Doc =
        visitParenDelimited(ctx, ctx.argumentList())

    override fun visitMethodCallExpr(ctx: NoxParser.MethodCallExprContext): Doc =
        visitParenDelimited(ctx, ctx.argumentList())

    /**
     * Walks [ctx], emitting children verbatim except at the `(...)` pair:
     * the optional [innerList] is wrapped in a breakable group so arguments
     * or parameters wrap onto their own lines when the line gets long.
     */
    private fun visitParenDelimited(
        ctx: ParserRuleContext,
        innerList: ParserRuleContext?,
    ): Doc {
        val docs = mutableListOf<Doc>()
        var i = 0
        while (i < ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is TerminalNode && child.symbol.type == NoxLexer.LPAREN) {
                docs.add(visitTerminal(child))
                if (innerList != null) {
                    val pDoc = Doc.indent(parenSoftLine, visit(innerList))
                    docs.add(Doc.group(Doc.concat(pDoc, parenSoftLine)))
                    i++
                }
            } else if (child is TerminalNode && child.symbol.type == NoxLexer.RPAREN) {
                state.suppressNextSpace = true
                docs.add(visitTerminal(child))
            } else {
                docs.add(visit(child))
            }
            i++
        }
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitParameterList(ctx: NoxParser.ParameterListContext): Doc = visitCommaSeparated(ctx)

    override fun visitArgumentList(ctx: NoxParser.ArgumentListContext): Doc = visitCommaSeparated(ctx)

    /**
     * Walks a comma-separated list context: each COMMA terminal gets a
     * following `SoftLine(" ")` so the list breaks nicely when wrapped.
     */
    private fun visitCommaSeparated(ctx: ParserRuleContext): Doc {
        val docs = mutableListOf<Doc>()
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is TerminalNode && child.symbol.type == NoxLexer.COMMA) {
                docs.add(visitTerminal(child))
                docs.add(Doc.SoftLine(" "))
                state.suppressNextSpace = true
            } else {
                docs.add(visit(child))
            }
        }
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitTemplateLiteral(ctx: NoxParser.TemplateLiteralContext): Doc {
        val docs = mutableListOf<Doc>()
        docs.add(visitTerminal(ctx.BACKTICK()))
        for (i in 1 until ctx.childCount - 1) {
            docs.add(visit(ctx.getChild(i)))
        }
        state.suppressNextSpace = true
        docs.add(visitTerminal(ctx.TEMPLATE_CLOSE()))
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitTemplateTextPart(ctx: NoxParser.TemplateTextPartContext): Doc {
        state.suppressNextSpace = true
        return visitTerminal(ctx.TEMPLATE_TEXT())
    }

    override fun visitTemplateExprPart(ctx: NoxParser.TemplateExprPartContext): Doc {
        val docs = mutableListOf<Doc>()
        state.suppressNextSpace = true
        docs.add(visitTerminal(ctx.TEMPLATE_EXPR_OPEN()))
        docs.add(visit(ctx.expression()))
        state.suppressNextSpace = true
        docs.add(visitTerminal(ctx.RBRACE()))
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitArrayLiteral(ctx: NoxParser.ArrayLiteralContext): Doc {
        val docs = mutableListOf<Doc>()
        var i = 0
        while (i < ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is TerminalNode && child.symbol.type == NoxLexer.LBRACK) {
                docs.add(visitTerminal(child))
                val innerDocs = mutableListOf<Doc>()
                i++
                while (i < ctx.childCount &&
                    !(
                        ctx.getChild(i) is TerminalNode &&
                            (ctx.getChild(i) as TerminalNode).symbol.type == NoxLexer.RBRACK
                    )
                ) {
                    val innerChild = ctx.getChild(i)
                    if (innerChild is TerminalNode && innerChild.symbol.type == NoxLexer.COMMA) {
                        innerDocs.add(visitTerminal(innerChild))
                        innerDocs.add(Doc.SoftLine(" "))
                        state.suppressNextSpace = true
                    } else {
                        innerDocs.add(visit(innerChild))
                    }
                    i++
                }
                if (innerDocs.isNotEmpty()) {
                    val innerConcat = Doc.concat(*innerDocs.toTypedArray())
                    docs.add(Doc.group(Doc.concat(Doc.indent(parenSoftLine, innerConcat), parenSoftLine)))
                }
                if (i < ctx.childCount) {
                    state.suppressNextSpace = true
                    docs.add(visitTerminal(ctx.getChild(i) as TerminalNode))
                }
            } else {
                docs.add(visit(child))
            }
            i++
        }
        return Doc.concat(*docs.toTypedArray())
    }

    override fun visitStructLiteral(ctx: NoxParser.StructLiteralContext): Doc {
        val lbrace = visitTerminal(ctx.LBRACE())
        state.suppressNextSpace = true
        val docs = mutableListOf<Doc>()
        for (i in 1 until ctx.childCount - 1) {
            val child = ctx.getChild(i)
            if (child is TerminalNode && child.symbol.type == NoxLexer.COMMA) {
                docs.add(visitTerminal(child))
                docs.add(Doc.SoftLine(" "))
                state.suppressNextSpace = true
            } else {
                docs.add(visit(child))
            }
        }
        val inner = Doc.concat(*docs.toTypedArray())
        val rbrace = visitTerminal(ctx.RBRACE())

        return if (docs.isEmpty()) {
            Doc.concat(lbrace, rbrace)
        } else {
            Doc.group(Doc.concat(lbrace, Doc.indent(structSoftLine, inner), structSoftLine, rbrace))
        }
    }
}
