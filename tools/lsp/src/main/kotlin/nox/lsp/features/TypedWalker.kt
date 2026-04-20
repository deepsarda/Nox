package nox.lsp.features

import nox.compiler.ast.typed.*

/**
 * Visitor utility: traverse every [TypedDecl], [TypedStmt], and [TypedExpr] in a [TypedProgram].
 */
internal object TypedWalker {
    fun walkProgram(
        program: TypedProgram,
        onDecl: ((TypedDecl) -> Unit)? = null,
        onStmt: ((TypedStmt) -> Unit)? = null,
        onExpr: ((TypedExpr) -> Unit)? = null,
    ) {
        program.declarations.forEach { walkDecl(it, onDecl, onStmt, onExpr) }
    }

    fun walkDecls(
        decls: List<TypedDecl>,
        onExpr: (TypedExpr) -> Unit,
    ) {
        decls.forEach { walkDecl(it, onDecl = null, onStmt = null, onExpr = onExpr) }
    }

    private fun walkDecl(
        decl: TypedDecl,
        onDecl: ((TypedDecl) -> Unit)?,
        onStmt: ((TypedStmt) -> Unit)?,
        onExpr: ((TypedExpr) -> Unit)?,
    ) {
        onDecl?.invoke(decl)
        when (decl) {
            is TypedFuncDef -> {
                decl.params.forEach { p -> p.defaultValue?.let { walkExpr(it, onExpr) } }
                walkBlock(decl.body, onStmt, onExpr)
            }
            is TypedMainDef -> {
                decl.params.forEach { p -> p.defaultValue?.let { walkExpr(it, onExpr) } }
                walkBlock(decl.body, onStmt, onExpr)
            }
            is TypedGlobalVarDecl -> decl.initializer?.let { walkExpr(it, onExpr) }
            else -> Unit
        }
    }

    private fun walkBlock(
        block: TypedBlock,
        onStmt: ((TypedStmt) -> Unit)?,
        onExpr: ((TypedExpr) -> Unit)?,
    ) {
        block.statements.forEach { walkStmt(it, onStmt, onExpr) }
    }

    private fun walkStmt(
        stmt: TypedStmt,
        onStmt: ((TypedStmt) -> Unit)?,
        onExpr: ((TypedExpr) -> Unit)?,
    ) {
        onStmt?.invoke(stmt)
        when (stmt) {
            is TypedVarDeclStmt -> walkExpr(stmt.initializer, onExpr)
            is TypedAssignStmt -> {
                walkExpr(stmt.target, onExpr)
                walkExpr(stmt.value, onExpr)
            }
            is TypedIncrementStmt -> walkExpr(stmt.target, onExpr)
            is TypedIfStmt -> {
                walkExpr(stmt.condition, onExpr)
                walkBlock(stmt.thenBlock, onStmt, onExpr)
                stmt.elseIfs.forEach {
                    walkExpr(it.condition, onExpr)
                    walkBlock(it.body, onStmt, onExpr)
                }
                stmt.elseBlock?.let { walkBlock(it, onStmt, onExpr) }
            }
            is TypedWhileStmt -> {
                walkExpr(stmt.condition, onExpr)
                walkBlock(stmt.body, onStmt, onExpr)
            }
            is TypedForStmt -> {
                stmt.init?.let { walkStmt(it, onStmt, onExpr) }
                stmt.condition?.let { walkExpr(it, onExpr) }
                stmt.update?.let { walkStmt(it, onStmt, onExpr) }
                walkBlock(stmt.body, onStmt, onExpr)
            }
            is TypedForEachStmt -> {
                walkExpr(stmt.iterable, onExpr)
                walkBlock(stmt.body, onStmt, onExpr)
            }
            is TypedReturnStmt -> stmt.value?.let { walkExpr(it, onExpr) }
            is TypedYieldStmt -> walkExpr(stmt.value, onExpr)
            is TypedThrowStmt -> walkExpr(stmt.value, onExpr)
            is TypedTryCatchStmt -> {
                walkBlock(stmt.tryBlock, onStmt, onExpr)
                stmt.catchClauses.forEach { walkBlock(it.body, onStmt, onExpr) }
            }
            is TypedExprStmt -> walkExpr(stmt.expression, onExpr)
            is TypedBlock -> walkBlock(stmt, onStmt, onExpr)
            else -> Unit
        }
    }

    private fun walkExpr(
        expr: TypedExpr,
        onExpr: ((TypedExpr) -> Unit)?,
    ) {
        onExpr?.invoke(expr)
        when (expr) {
            is TypedBinaryExpr -> {
                walkExpr(expr.left, onExpr)
                walkExpr(expr.right, onExpr)
            }
            is TypedUnaryExpr -> walkExpr(expr.operand, onExpr)
            is TypedPostfixExpr -> walkExpr(expr.operand, onExpr)
            is TypedCastExpr -> walkExpr(expr.operand, onExpr)
            is TypedFuncCallExpr -> expr.args.forEach { walkExpr(it, onExpr) }
            is TypedMethodCallExpr -> {
                walkExpr(expr.target, onExpr)
                expr.args.forEach { walkExpr(it, onExpr) }
            }
            is TypedFieldAccessExpr -> walkExpr(expr.target, onExpr)
            is TypedIndexAccessExpr -> {
                walkExpr(expr.target, onExpr)
                walkExpr(expr.index, onExpr)
            }
            is TypedArrayLiteralExpr -> expr.elements.forEach { walkExpr(it, onExpr) }
            is TypedStructLiteralExpr -> expr.fields.forEach { walkExpr(it.value, onExpr) }
            is TypedTemplateLiteralExpr ->
                expr.parts.forEach {
                    if (it is TypedTemplatePart.Interpolation) walkExpr(it.expression, onExpr)
                }
            else -> Unit
        }
    }
}
