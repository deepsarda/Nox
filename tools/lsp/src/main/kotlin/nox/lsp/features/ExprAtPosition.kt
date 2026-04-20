package nox.lsp.features

import nox.compiler.ast.typed.*

/**
 * Find the deepest [TypedExpr] in [program] whose token starts at the given LSP position.
 *
 * Nox source locations point at token *start* with no length, so exact column match is the
 * strongest signal we have and is good enough for identifiers and literals which are the only
 * things a user hovers with intent. Nested expressions resolve to their innermost match
 * (e.g. hovering `x` inside `x + 1` returns the identifier, not the binary expression).
 */
object ExprAtPosition {
    fun find(
        program: TypedProgram,
        lspLine: Int,
        lspColumn: Int,
        outermost: Boolean = false,
    ): TypedExpr? {
        val compilerLine = lspLine + 1
        val finder = Finder(compilerLine, lspColumn, outermost)
        program.declarations.forEach { visitDecl(it, finder) }
        return finder.best
    }

    private class Finder(
        val line: Int,
        val column: Int,
        val outermost: Boolean,
    ) {
        var best: TypedExpr? = null

        fun consider(expr: TypedExpr) {
            if (expr.loc.line == line && expr.loc.column <= column) {
                val prev = best
                if (prev == null) {
                    best = expr
                } else if (outermost) {
                    // For completion: we want the expression that starts earliest (outermost parent).
                    // Since parents are visited before children in TypedWalker, the first match
                    // for a given start column is the outermost one. We only replace if we find
                    // one that starts even EARLIER.
                    if (expr.loc.column < prev.loc.column) {
                        best = expr
                    }
                } else {
                    // For hover: we want the deepest/most specific expression.
                    // If start is same, later one (child) wins.
                    if (expr.loc.column >= prev.loc.column) {
                        best = expr
                    }
                }
            }
        }
    }

    private fun visitDecl(
        decl: TypedDecl,
        finder: Finder,
    ) {
        when (decl) {
            is TypedFuncDef -> {
                decl.params.forEach { p -> p.defaultValue?.let { visitExpr(it, finder) } }
                visitBlock(decl.body, finder)
            }
            is TypedMainDef -> {
                decl.params.forEach { p -> p.defaultValue?.let { visitExpr(it, finder) } }
                visitBlock(decl.body, finder)
            }
            is TypedGlobalVarDecl -> decl.initializer?.let { visitExpr(it, finder) }
            else -> Unit
        }
    }

    private fun visitBlock(
        block: TypedBlock,
        finder: Finder,
    ) {
        block.statements.forEach { visitStmt(it, finder) }
    }

    private fun visitStmt(
        stmt: TypedStmt,
        finder: Finder,
    ) {
        when (stmt) {
            is TypedVarDeclStmt -> visitExpr(stmt.initializer, finder)
            is TypedAssignStmt -> {
                visitExpr(stmt.target, finder)
                visitExpr(stmt.value, finder)
            }
            is TypedIncrementStmt -> visitExpr(stmt.target, finder)
            is TypedIfStmt -> {
                visitExpr(stmt.condition, finder)
                visitBlock(stmt.thenBlock, finder)
                stmt.elseIfs.forEach {
                    visitExpr(it.condition, finder)
                    visitBlock(it.body, finder)
                }
                stmt.elseBlock?.let { visitBlock(it, finder) }
            }
            is TypedWhileStmt -> {
                visitExpr(stmt.condition, finder)
                visitBlock(stmt.body, finder)
            }
            is TypedForStmt -> {
                stmt.init?.let { visitStmt(it, finder) }
                stmt.condition?.let { visitExpr(it, finder) }
                stmt.update?.let { visitStmt(it, finder) }
                visitBlock(stmt.body, finder)
            }
            is TypedForEachStmt -> {
                visitExpr(stmt.iterable, finder)
                visitBlock(stmt.body, finder)
            }
            is TypedReturnStmt -> stmt.value?.let { visitExpr(it, finder) }
            is TypedYieldStmt -> visitExpr(stmt.value, finder)
            is TypedThrowStmt -> visitExpr(stmt.value, finder)
            is TypedTryCatchStmt -> {
                visitBlock(stmt.tryBlock, finder)
                stmt.catchClauses.forEach { visitBlock(it.body, finder) }
            }
            is TypedExprStmt -> visitExpr(stmt.expression, finder)
            is TypedBlock -> visitBlock(stmt, finder)
            else -> Unit
        }
    }

    private fun visitExpr(
        expr: TypedExpr,
        finder: Finder,
    ) {
        finder.consider(expr)
        when (expr) {
            is TypedBinaryExpr -> {
                visitExpr(expr.left, finder)
                visitExpr(expr.right, finder)
            }
            is TypedUnaryExpr -> visitExpr(expr.operand, finder)
            is TypedPostfixExpr -> visitExpr(expr.operand, finder)
            is TypedCastExpr -> visitExpr(expr.operand, finder)
            is TypedFuncCallExpr -> expr.args.forEach { visitExpr(it, finder) }
            is TypedMethodCallExpr -> {
                visitExpr(expr.target, finder)
                expr.args.forEach { visitExpr(it, finder) }
            }
            is TypedFieldAccessExpr -> visitExpr(expr.target, finder)
            is TypedIndexAccessExpr -> {
                visitExpr(expr.target, finder)
                visitExpr(expr.index, finder)
            }
            is TypedArrayLiteralExpr -> expr.elements.forEach { visitExpr(it, finder) }
            is TypedStructLiteralExpr -> expr.fields.forEach { visitExpr(it.value, finder) }
            is TypedTemplateLiteralExpr ->
                expr.parts.forEach {
                    if (it is TypedTemplatePart.Interpolation) visitExpr(it.expression, finder)
                }
            else -> Unit
        }
    }
}
