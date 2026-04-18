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
        val containingFunc = findContainingFunc(raw, lspLine)
        val loc = resolve(expr, raw, containingFunc) ?: return emptyList()
        return listOf(Location(uri, Positions.toLspRange(loc, length = nameLength(expr))))
    }

    private fun findContainingFunc(
        raw: RawProgram,
        lspLine: Int,
    ): RawFuncDef? {
        val compilerLine = lspLine + 1
        return raw.functionsByName.values
            .filter { it.loc.line <= compilerLine }
            .maxByOrNull { it.loc.line }
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
        containingFunc: RawFuncDef?,
    ): SourceLocation? =
        when (expr) {
            is TypedIdentifierExpr -> symbolLocation(expr.resolvedSymbol, raw, containingFunc)
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
        return typeDef.fields
            .filterIsInstance<RawFieldDeclImpl>()
            .firstOrNull { it.name == expr.fieldName }
            ?.loc
    }

    private fun symbolLocation(
        symbol: Symbol,
        raw: RawProgram,
        containingFunc: RawFuncDef?,
    ): SourceLocation? =
        when (symbol) {
            is FuncSymbol -> symbol.astNode.loc
            is TypeSymbol -> symbol.astNode.loc
            is GlobalSymbol -> raw.globals.firstOrNull { it.name == symbol.name }?.loc
            is ParamSymbol -> findParamLoc(raw, symbol.name, containingFunc)
            is VarSymbol -> findVarDeclLoc(raw, symbol.name, containingFunc)
            else -> null
        }

    private fun findParamLoc(
        raw: RawProgram,
        name: String,
        containingFunc: RawFuncDef?,
    ): SourceLocation? {
        if (containingFunc != null) {
            containingFunc.params
                .filterIsInstance<RawParamImpl>()
                .firstOrNull { it.name == name }
                ?.let { return it.loc }
        }
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
        containingFunc: RawFuncDef?,
    ): SourceLocation? {
        val body = containingFunc?.body ?: raw.main?.body ?: return null
        var found: SourceLocation? = null
        RawWalker.walkBlock(body) { stmt ->
            if (found == null && stmt is RawVarDeclStmt && stmt.name == name) found = stmt.loc
        }
        return found
    }
}
