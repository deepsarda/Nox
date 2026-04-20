package nox.format

import nox.parser.NoxLexer
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval

/**
 * Emits hidden-channel trivia (whitespace + comments) around default-channel
 * tokens. Mutates [state] as it walks so callers can see whether we've just
 * emitted a newline, how deep we are in braces, etc.
 */
internal class TriviaEmitter(
    private val tokens: CommonTokenStream,
    private val state: EmitterState,
    private val config: FormatterConfig,
) {
    fun emitLeading(
        tok: Token,
        stripTrailingNewline: Boolean = false,
    ): Doc {
        val docs = mutableListOf<Doc>()
        val hidden = tokens.getHiddenTokensToLeft(tok.tokenIndex)
        val maxBlanks = config.maxBlankLines.forDepth(state.isTopLevel)

        val filteredHidden = filterLeadingHidden(hidden)

        if (filteredHidden.isEmpty()) {
            val last = state.lastTok
            if (last != null) {
                val blanks = blankLinesBetween(last, tok)
                if (blanks > 0) {
                    if (!state.atLineStart) docs.add(Doc.Line)
                    repeat(minOf(blanks, maxBlanks)) { docs.add(Doc.Line) }
                    state.atLineStart = true
                }
            }
        } else {
            var prev: Token? = state.lastTok
            for (h in filteredHidden) {
                if (h.type == NoxLexer.LINE_COMMENT || h.type == NoxLexer.BLOCK_COMMENT) {
                    val blanksBefore = if (prev == null) 0 else blankLinesBetween(prev, h)
                    if (!state.atLineStart) {
                        docs.add(Doc.Line)
                        state.atLineStart = true
                    }
                    if (blanksBefore > 0) {
                        repeat(minOf(blanksBefore, maxBlanks)) { docs.add(Doc.Line) }
                    }
                    docs.add(Doc.text(h.text.trim()))
                    docs.add(Doc.Line)
                    state.atLineStart = true
                    prev = h
                }
            }
            val blanksAfter = if (prev == null) 0 else blankLinesBetween(prev, tok)
            if (blanksAfter > 0 && !stripTrailingNewline) {
                if (!state.atLineStart) docs.add(Doc.Line)
                repeat(minOf(blanksAfter, maxBlanks)) { docs.add(Doc.Line) }
                state.atLineStart = true
            }
        }

        if (stripTrailingNewline && docs.isNotEmpty() && docs.last() == Doc.Line) {
            docs.removeAt(docs.size - 1)
        }

        return Doc.concat(*docs.toTypedArray())
    }

    fun emitTrailing(tok: Token): Doc {
        val docs = mutableListOf<Doc>()
        val hidden = tokens.getHiddenTokensToRight(tok.tokenIndex) ?: return Doc.empty
        var prev: Token? = tok
        for (h in hidden) {
            if (hasNewlineBetween(prev!!, h)) break
            when (h.type) {
                NoxLexer.LINE_COMMENT, NoxLexer.BLOCK_COMMENT -> {
                    if (!state.atLineStart) docs.add(Doc.text(" "))
                    docs.add(Doc.text(h.text.trim()))
                    if (h.type == NoxLexer.LINE_COMMENT) {
                        docs.add(Doc.Line)
                        state.atLineStart = true
                    }
                    prev = h
                }
                NoxLexer.WS -> if (h.text.contains('\n')) return Doc.concat(*docs.toTypedArray())
            }
        }
        return Doc.concat(*docs.toTypedArray())
    }

    fun emitFinal(eof: Token): Doc {
        val docs = mutableListOf<Doc>()
        val hidden = tokens.getHiddenTokensToLeft(eof.tokenIndex) ?: return Doc.empty

        val filteredHidden = filterLeadingHidden(hidden)

        var prev: Token? = state.lastTok
        for (h in filteredHidden) {
            if (h.type == NoxLexer.LINE_COMMENT || h.type == NoxLexer.BLOCK_COMMENT) {
                val hasNewline = if (prev == null) false else hasNewlineBetween(prev, h)
                if (hasNewline && !state.atLineStart) {
                    docs.add(Doc.Line)
                    state.atLineStart = true
                }
                if (!state.atLineStart) docs.add(Doc.text(" "))
                docs.add(Doc.text(h.text.trim()))
                docs.add(Doc.Line)
                state.atLineStart = true
                prev = h
            }
        }
        return Doc.concat(*docs.toTypedArray())
    }

    fun getNextVisibleToken(tok: Token): Token? {
        val all = tokens.tokens
        for (i in tok.tokenIndex + 1 until all.size) {
            val t = all[i]
            if (t.channel == Token.DEFAULT_CHANNEL) return t
        }
        return null
    }

    fun hasNewlineBetween(
        a: Token,
        b: Token,
    ): Boolean {
        val start = a.stopIndex + 1
        val end = b.startIndex
        if (start >= end) return false
        return a.inputStream.getText(Interval(start, end - 1)).contains('\n')
    }

    private fun blankLinesBetween(
        a: Token,
        b: Token,
    ): Int {
        val start = a.stopIndex + 1
        val end = b.startIndex
        if (start >= end) return 0
        val input = a.inputStream.getText(Interval(start, end - 1))
        var blankLines = 0
        var inNewline = false
        for (c in input) {
            if (c == '\n') {
                if (inNewline) blankLines++
                inNewline = true
            } else if (c != ' ' && c != '\t' && c != '\r') {
                inNewline = false
            }
        }
        return blankLines
    }

    /**
     * Drops any hidden tokens that precede the first newline after [state.lastTok],
     * because they belong to the trailing trivia of the previous token and were
     * already emitted there.
     */
    private fun filterLeadingHidden(hidden: List<Token>?): List<Token> {
        if (hidden == null) return emptyList()
        if (state.lastTok == null) return hidden.toList()

        val out = mutableListOf<Token>()
        var seenNewline = false
        var prev: Token? = state.lastTok
        for (h in hidden) {
            if (!seenNewline && prev != null && hasNewlineBetween(prev, h)) {
                seenNewline = true
            }
            if (seenNewline) {
                out.add(h)
            } else if (h.type == NoxLexer.WS && h.text.contains('\n')) {
                seenNewline = true
            }
            prev = h
        }
        return out
    }
}
