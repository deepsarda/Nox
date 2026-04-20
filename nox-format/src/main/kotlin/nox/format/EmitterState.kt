package nox.format

import org.antlr.v4.runtime.Token

/**
 * Mutable whitespace/position tracking shared by [DocBuilder] and [TriviaEmitter]
 * as they walk the parse tree left-to-right. These flags decide whether the next
 * token needs a leading space, how many blank lines may follow, etc.
 */
internal class EmitterState {
    var lastTok: Token? = null
    var atLineStart: Boolean = true
    var suppressNextSpace: Boolean = false
    var braceDepth: Int = 0

    val isTopLevel: Boolean
        get() = braceDepth == 0
}
