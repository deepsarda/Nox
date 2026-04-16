package nox.lsp.features

import nox.compiler.ast.RawProgram
import nox.compiler.ast.typed.TypedExpr
import nox.compiler.ast.typed.TypedFieldAccessExpr
import nox.compiler.ast.typed.TypedIdentifierExpr
import nox.compiler.ast.typed.TypedProgram
import nox.compiler.types.Symbol
import nox.compiler.types.VarSymbol
import nox.compiler.types.ParamSymbol
import nox.compiler.types.GlobalSymbol
import nox.compiler.types.FuncSymbol
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

/**
 * Find all references to a symbol.
 */
object ReferencesProvider {
    fun references(
        typed: TypedProgram,
        raw: RawProgram,
        uri: String,
        lspLine: Int,
        lspColumn: Int,
        includeDeclaration: Boolean,
    ): List<Location> {
        val expr = ExprAtPosition.find(typed, lspLine, lspColumn) ?: return emptyList()
        val matcher = Matcher.fromExpr(expr) ?: return emptyList()

        val results = mutableListOf<Location>()

        // Search current file
        results.addAll(findInTyped(uri, typed, matcher, includeDeclaration))

        return results
    }

    private fun findInTyped(
        uri: String,
        program: TypedProgram,
        matcher: Matcher,
        includeDeclaration: Boolean,
    ): List<Location> {
        val out = mutableListOf<Location>()
        TypedWalker.walkProgram(program, onExpr = { expr ->
            if (matcher.matchesUsage(expr)) {
                out.add(Location(uri, Positions.toLspRange(expr.loc, length = matcher.name.length)))
            }
        })
        if (includeDeclaration) {
            matcher.findDeclaration(program)?.let {
                out.add(Location(uri, Positions.toLspRange(it, length = matcher.name.length)))
            }
        }
        return out
    }

    private sealed class Matcher {
        abstract val name: String

        abstract fun matchesUsage(expr: TypedExpr): Boolean

        abstract fun findDeclaration(program: TypedProgram): nox.compiler.types.SourceLocation?

        data class ByName(
            override val name: String,
            val symbol: Symbol,
            val scope: Scope,
        ) : Matcher() {
            enum class Scope { LOCAL, GLOBAL, FUNC }

            override fun matchesUsage(expr: TypedExpr): Boolean = expr is TypedIdentifierExpr && expr.name == name && expr.resolvedSymbol == symbol

            override fun findDeclaration(program: TypedProgram): nox.compiler.types.SourceLocation? = null 
        }

        data class ByField(
            override val name: String,
        ) : Matcher() {
            override fun matchesUsage(expr: TypedExpr): Boolean = expr is TypedFieldAccessExpr && expr.fieldName == name

            override fun findDeclaration(program: TypedProgram): nox.compiler.types.SourceLocation? = null
        }

        data class ByFunc(
            override val name: String,
        ) : Matcher() {
            override fun matchesUsage(expr: TypedExpr): Boolean = expr is nox.compiler.ast.typed.TypedFuncCallExpr && expr.name == name
            override fun findDeclaration(program: TypedProgram): nox.compiler.types.SourceLocation? = program.functionsByName[name]?.loc
        }

        companion object {
            fun fromExpr(expr: TypedExpr): Matcher? =
                when (expr) {
                    is TypedIdentifierExpr -> {
                        val symbol = expr.resolvedSymbol
                        val scope = if (symbol is ParamSymbol || symbol is VarSymbol) ByName.Scope.LOCAL else ByName.Scope.GLOBAL
                        ByName(expr.name, symbol, scope)
                    }
                    is TypedFieldAccessExpr -> {
                        ByField(expr.fieldName)
                    }
                    is nox.compiler.ast.typed.TypedFuncCallExpr -> {
                        ByFunc(expr.name)
                    }
                    else -> null
                }
        }
    }
}
