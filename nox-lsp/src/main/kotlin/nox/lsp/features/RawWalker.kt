package nox.lsp.features

import nox.compiler.ast.*

/** Raw-AST statement walker. Used for finding var-decl locations by name. */
internal object RawWalker {
    fun walkStmts(
        program: RawProgram,
        visit: (RawStmt) -> Unit,
    ) {
        program.declarations.forEach { decl ->
            when (decl) {
                is RawFuncDef -> walkBlock(decl.body, visit)
                is RawMainDef -> walkBlock(decl.body, visit)
                else -> Unit
            }
        }
    }

    private fun walkBlock(
        block: RawBlock,
        visit: (RawStmt) -> Unit,
    ) {
        block.statements.forEach { walkStmt(it, visit) }
    }

    private fun walkStmt(
        stmt: RawStmt,
        visit: (RawStmt) -> Unit,
    ) {
        visit(stmt)
        when (stmt) {
            is RawIfStmt -> {
                walkBlock(stmt.thenBlock, visit)
                stmt.elseIfs.forEach { walkBlock(it.body, visit) }
                stmt.elseBlock?.let { walkBlock(it, visit) }
            }
            is RawWhileStmt -> walkBlock(stmt.body, visit)
            is RawForStmt -> {
                stmt.init?.let { walkStmt(it, visit) }
                stmt.update?.let { walkStmt(it, visit) }
                walkBlock(stmt.body, visit)
            }
            is RawForEachStmt -> walkBlock(stmt.body, visit)
            is RawTryCatchStmt -> {
                walkBlock(stmt.tryBlock, visit)
                stmt.catchClauses.forEach { walkBlock(it.body, visit) }
            }
            is RawBlock -> walkBlock(stmt, visit)
            else -> Unit
        }
    }
}
