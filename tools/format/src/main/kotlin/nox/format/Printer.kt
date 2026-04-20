package nox.format

/**
 * Layout engine that takes a Wadler [Doc] tree and renders it,
 * breaking [Group]s that exceed the [lineWidth].
 */
class Printer(
    private val config: FormatterConfig,
) {
    private val out = StringBuilder()
    private var currentColumn = 0

    fun print(doc: Doc): String {
        out.clear()
        currentColumn = 0
        render(listOf(Triple(0, false, doc)))
        return out.toString()
    }

    // stack of (indent, modeIsBreak, Doc)
    private fun render(initial: List<Triple<Int, Boolean, Doc>>) {
        val stack = ArrayDeque(initial)
        var pendingIndent = -1
        var atLineStart = true
        var consecutiveBlanks = 0

        while (stack.isNotEmpty()) {
            val (ind, mode, doc) = stack.removeFirst()

            when (doc) {
                is Doc.Text -> {
                    if (doc.content.isNotEmpty()) {
                        if (pendingIndent >= 0) {
                            val indentStr = config.indent.unit.repeat(pendingIndent)
                            out.append(indentStr)
                            currentColumn = indentStr.length
                            pendingIndent = -1
                        }
                        out.append(doc.content)
                        currentColumn += doc.content.length
                        atLineStart = false
                        consecutiveBlanks = 0
                    }
                }
                is Doc.Line -> {
                    if (atLineStart) {
                        consecutiveBlanks++
                        val maxBlanks = config.maxBlankLines.forDepth(ind == 0)
                        if (consecutiveBlanks <= maxBlanks) {
                            out.append('\n')
                        }
                    } else {
                        out.append('\n')
                        atLineStart = true
                        consecutiveBlanks = 0
                    }
                    pendingIndent = ind
                    currentColumn = 0
                }
                is Doc.SoftLine -> {
                    if (mode) {
                        if (atLineStart) {
                            consecutiveBlanks++
                            val maxBlanks = config.maxBlankLines.forDepth(ind == 0)
                            if (consecutiveBlanks <= maxBlanks) {
                                out.append('\n')
                            }
                        } else {
                            out.append('\n')
                            atLineStart = true
                            consecutiveBlanks = 0
                        }
                        pendingIndent = ind
                        currentColumn = 0
                    } else {
                        if (pendingIndent >= 0) {
                            val indentStr = config.indent.unit.repeat(pendingIndent)
                            out.append(indentStr)
                            currentColumn = indentStr.length
                            pendingIndent = -1
                        }
                        out.append(doc.fallback)
                        currentColumn += doc.fallback.length
                        if (doc.fallback.isNotEmpty()) {
                            atLineStart = false
                            consecutiveBlanks = 0
                        }
                    }
                }
                is Doc.Indent -> {
                    stack.addFirst(Triple(ind + 1, mode, doc.doc))
                }
                is Doc.Concat -> {
                    for (i in doc.parts.indices.reversed()) {
                        stack.addFirst(Triple(ind, mode, doc.parts[i]))
                    }
                }
                is Doc.Group -> {
                    if (mode || !fits(doc.doc, config.lineWidth - currentColumn)) {
                        stack.addFirst(Triple(ind, true, doc.doc)) // break group
                    } else {
                        stack.addFirst(Triple(ind, false, doc.doc)) // flat group
                    }
                }
            }
        }
    }

    private fun fits(
        doc: Doc,
        width: Int,
    ): Boolean {
        var w = width
        val stack = ArrayDeque(listOf(Triple(0, false, doc)))
        while (stack.isNotEmpty()) {
            if (w < 0) return false
            val (ind, mode, d) = stack.removeFirst()

            when (d) {
                is Doc.Text -> w -= d.content.length
                is Doc.Line -> return true
                is Doc.SoftLine -> {
                    if (mode) return true
                    w -= d.fallback.length
                }
                is Doc.Indent -> stack.addFirst(Triple(ind + 1, mode, d.doc))
                is Doc.Concat -> {
                    for (i in d.parts.indices.reversed()) {
                        stack.addFirst(Triple(ind, mode, d.parts[i]))
                    }
                }
                is Doc.Group -> stack.addFirst(Triple(ind, mode, d.doc))
            }
        }
        return true
    }
}
