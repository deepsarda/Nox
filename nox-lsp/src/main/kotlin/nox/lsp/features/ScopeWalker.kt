package nox.lsp.features

import nox.compiler.ast.typed.*
import nox.compiler.types.SourceLocation
import nox.compiler.types.TypeRef

/**
 * Walks the typed AST to collect all variables visible at a specific cursor location.
 */
object ScopeWalker {
    data class VisibleVariable(
        val name: String,
        val type: TypeRef,
    )

    fun variablesAt(
        program: TypedProgram,
        lspLine: Int,
        lspColumn: Int,
    ): List<VisibleVariable> {
        val compilerLine = lspLine + 1
        val vars = mutableListOf<VisibleVariable>()

        // Add globals
        program.declarations.filterIsInstance<TypedGlobalVarDecl>().forEach {
            vars.add(VisibleVariable(it.name, it.type))
        }

        // Find the declaration containing the cursor
        val decl = program.declarations.findLast { contains(it.loc, compilerLine, lspColumn) } ?: return vars

        when (decl) {
            is TypedFuncDef -> {
                decl.params.forEach { vars.add(VisibleVariable(it.name, it.type)) }
                walkBlock(decl.body, compilerLine, lspColumn, vars)
            }
            is TypedMainDef -> {
                decl.params.forEach { vars.add(VisibleVariable(it.name, it.type)) }
                walkBlock(decl.body, compilerLine, lspColumn, vars)
            }
            else -> {}
        }

        return vars
    }

    private fun walkBlock(
        block: TypedBlock,
        line: Int,
        column: Int,
        vars: MutableList<VisibleVariable>,
    ) {
        if (block.loc.line > line || (block.loc.line == line && block.loc.column > column)) return

        for (stmt in block.statements) {
            if (stmt.loc.line > line || (stmt.loc.line == line && stmt.loc.column > column)) {
                break // Statement is after cursor
            }

            when (stmt) {
                is TypedVarDeclStmt -> vars.add(VisibleVariable(stmt.name, stmt.type))
                is TypedIfStmt -> {
                    walkBlock(stmt.thenBlock, line, column, vars)
                    stmt.elseIfs.forEach { walkBlock(it.body, line, column, vars) }
                    stmt.elseBlock?.let { walkBlock(it, line, column, vars) }
                }
                is TypedWhileStmt -> walkBlock(stmt.body, line, column, vars)
                is TypedForStmt -> {
                    stmt.init?.let {
                        if (it is TypedVarDeclStmt) vars.add(VisibleVariable(it.name, it.type))
                    }
                    walkBlock(stmt.body, line, column, vars)
                }
                is TypedForEachStmt -> {
                    vars.add(VisibleVariable(stmt.elementName, stmt.elementType))
                    walkBlock(stmt.body, line, column, vars)
                }
                is TypedTryCatchStmt -> {
                    walkBlock(stmt.tryBlock, line, column, vars)
                    stmt.catchClauses.forEach { clause ->
                        vars.add(VisibleVariable(clause.variableName, TypeRef("Exception")))
                        walkBlock(clause.body, line, column, vars)
                    }
                }
                is TypedBlock -> walkBlock(stmt, line, column, vars)
                else -> {}
            }
        }
    }

    private fun contains(
        start: SourceLocation,
        line: Int,
        column: Int,
    ): Boolean {
        if (line < start.line) return false
        if (line == start.line && column < start.column) return false
        return true
    }
}
