package nox.lsp.features

import nox.compiler.ast.*
import nox.lsp.protocol.*

/**
 * Emit a folding range for every braced block. We rely on the AST's source locations to
 * find the opening token, and scan forward in the source text for the matching `}` so that
 * the fold end actually lines up with the closing brace (AST locations point at statement
 * starts, not at closing delimiters).
 */
object FoldingRanges {
    fun collect(
        program: RawProgram,
        source: String,
    ): List<FoldingRange> {
        val offsets = lineStartOffsets(source)
        val ranges = mutableListOf<FoldingRange>()
        program.declarations.forEach { visitDecl(it, source, offsets, ranges) }
        return ranges.filter { it.startLine < it.endLine }
    }

    private fun visitDecl(
        decl: RawDecl,
        src: String,
        offsets: IntArray,
        out: MutableList<FoldingRange>,
    ) {
        when (decl) {
            is RawFuncDef -> visitBlock(decl.body, src, offsets, out)
            is RawMainDef -> visitBlock(decl.body, src, offsets, out)
            else -> Unit
        }
    }

    private fun visitStmt(
        stmt: RawStmt,
        src: String,
        offsets: IntArray,
        out: MutableList<FoldingRange>,
    ) {
        when (stmt) {
            is RawIfStmt -> {
                visitBlock(stmt.thenBlock, src, offsets, out)
                stmt.elseIfs.forEach { visitBlock(it.body, src, offsets, out) }
                stmt.elseBlock?.let { visitBlock(it, src, offsets, out) }
            }
            is RawWhileStmt -> visitBlock(stmt.body, src, offsets, out)
            is RawForStmt -> visitBlock(stmt.body, src, offsets, out)
            is RawForEachStmt -> visitBlock(stmt.body, src, offsets, out)
            is RawTryCatchStmt -> {
                visitBlock(stmt.tryBlock, src, offsets, out)
                stmt.catchClauses.forEach { visitBlock(it.body, src, offsets, out) }
            }
            is RawBlock -> visitBlock(stmt, src, offsets, out)
            else -> Unit
        }
    }

    private fun visitBlock(
        block: RawBlock,
        src: String,
        offsets: IntArray,
        out: MutableList<FoldingRange>,
    ) {
        val startLineIdx = (block.loc.line - 1).coerceAtLeast(0)
        val openOffset = findOpenBrace(src, offsets, startLineIdx) ?: return
        val closeOffset = matchClose(src, openOffset) ?: return
        val startLine = lineOf(closeOffset = openOffset, offsets)
        val endLine = lineOf(closeOffset = closeOffset, offsets)
        if (endLine > startLine) {
            // LSP folds hide lines strictly between start and end; end on the `}` line so
            // it collapses to `{ … }` on a single logical block header.
            val fr = FoldingRange(startLine, endLine - 1, kind = FoldingRangeKind.Region)
            out.add(fr)
        }
        block.statements.forEach { visitStmt(it, src, offsets, out) }
    }

    private fun findOpenBrace(
        src: String,
        offsets: IntArray,
        startLine: Int,
    ): Int? {
        if (startLine >= offsets.size) return null
        val start = offsets[startLine]
        val end = if (startLine + 1 < offsets.size) offsets[startLine + 1] else src.length
        val idx = src.indexOf('{', startIndex = start)
        return if (idx in start until end || (idx != -1 && idx >= start)) idx.takeIf { it != -1 } else null
    }

    private fun matchClose(
        src: String,
        openOffset: Int,
    ): Int? {
        var depth = 0
        var i = openOffset
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun lineOf(
        closeOffset: Int,
        offsets: IntArray,
    ): Int {
        // Binary search would be O(log n); linear is fine for small files.
        var lo = 0
        var hi = offsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = offsets[mid]
            val next = if (mid + 1 < offsets.size) offsets[mid + 1] else Int.MAX_VALUE
            when {
                closeOffset < start -> hi = mid - 1
                closeOffset >= next -> lo = mid + 1
                else -> return mid
            }
        }
        return 0
    }

    private fun lineStartOffsets(src: String): IntArray {
        val result = mutableListOf(0)
        for ((i, c) in src.withIndex()) {
            if (c == '\n') result.add(i + 1)
        }
        return result.toIntArray()
    }
}
