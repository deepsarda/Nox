package nox.lsp.features

import nox.compiler.ast.*
import nox.compiler.ast.typed.*
import nox.compiler.types.*
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

/**
 * Go-to-definition. Uses [TypedExpr]s' resolved symbol/function links where available and
 * falls back to a targeted raw-AST search for locals/globals/params whose [Symbol]s don't
 * carry a source location.
 */
object DefinitionProvider {
    fun definition(
        typed: TypedProgram,
        raw: RawProgram,
        uri: String,
        lspLine: Int,
        lspColumn: Int,
    ): List<Location> {
        val expr = ExprAtPosition.find(typed, lspLine, lspColumn) ?: return emptyList()
        val loc = resolve(expr, raw) ?: return emptyList()
        return listOf(Location(uri, Positions.toLspRange(loc, length = nameLength(expr))))
    }

    private fun nameLength(expr: TypedExpr): Int =
        when (expr) {
            is TypedIdentifierExpr -> expr.name.length
            is TypedFuncCallExpr -> expr.name.length
            is TypedMethodCallExpr -> expr.methodName.length
            is TypedFieldAccessExpr -> expr.fieldName.length
            else -> 1
        }

    private fun resolve(
        expr: TypedExpr,
        raw: RawProgram,
    ): SourceLocation? =
        when (expr) {
            is TypedIdentifierExpr -> symbolLocation(expr.resolvedSymbol, raw)
            is TypedFuncCallExpr -> expr.resolvedFunction.astNode.loc
            is TypedMethodCallExpr -> expr.resolvedTarget.astNode?.loc
            is TypedFieldAccessExpr -> fieldLoc(expr, raw)
            else -> null
        }

    private fun fieldLoc(
        expr: TypedFieldAccessExpr,
        raw: RawProgram,
    ): SourceLocation? {
        // Target's type name identifies the struct; look up the field by name.
        val structName = expr.target.type.name
        val typeDef = raw.typesByName[structName] ?: return null
        return typeDef.fields.filterIsInstance<RawFieldDeclImpl>().firstOrNull { it.name == expr.fieldName }?.loc
    }

    private fun symbolLocation(
        symbol: Symbol,
        raw: RawProgram,
    ): SourceLocation? =
        when (symbol) {
            is FuncSymbol -> symbol.astNode.loc
            is TypeSymbol -> symbol.astNode.loc
            is GlobalSymbol -> raw.globals.firstOrNull { it.name == symbol.name }?.loc
            is ParamSymbol -> findParamLoc(raw, symbol.name)
            is VarSymbol -> findVarDeclLoc(raw, symbol.name)
            else -> null
        }

    private fun findParamLoc(
        raw: RawProgram,
        name: String,
    ): SourceLocation? {
        // Could belong to any function or main; scan all.
        fun params(fn: RawFuncDef) = fn.params.filterIsInstance<RawParamImpl>().firstOrNull { it.name == name }?.loc
        raw.functionsByName.values.forEach { params(it)?.let { l -> return l } }
        raw.main
            ?.params
            ?.filterIsInstance<RawParamImpl>()
            ?.firstOrNull { it.name == name }
            ?.let { return it.loc }
        return null
    }

    private fun findVarDeclLoc(
        raw: RawProgram,
        name: String,
    ): SourceLocation? {
        var found: SourceLocation? = null
        RawWalker.walkStmts(raw) { stmt ->
            if (found == null && stmt is RawVarDeclStmt && stmt.name == name) found = stmt.loc
        }
        return found
    }

    @Suppress("UNUSED_PARAMETER")
    private fun dummyRange(): Range = Range(Position(0,0), Position(0,0))
}
