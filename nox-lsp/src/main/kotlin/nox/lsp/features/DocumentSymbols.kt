package nox.lsp.features

import nox.compiler.ast.*
import nox.compiler.types.SourceLocation
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

/** Top-level outline for the file. */
object DocumentSymbols {
    fun collect(program: RawProgram): List<DocumentSymbol> =
        program.declarations.mapNotNull { decl ->
            when (decl) {
                is RawTypeDef ->
                    symbol(
                        decl.name,
                        SymbolKind.Struct,
                        decl.loc,
                        nested =
                            decl.fields.mapNotNull { f ->
                                if (f is RawFieldDeclImpl) symbol(f.name, SymbolKind.Field, f.loc) else null
                            },
                    )
                is RawFuncDef -> symbol(decl.name, SymbolKind.Function, decl.loc)
                is RawMainDef -> symbol("main", SymbolKind.Function, decl.loc)
                is RawGlobalVarDecl -> symbol(decl.name, SymbolKind.Variable, decl.loc)
                else -> null
            }
        }

    private fun symbol(
        name: String,
        kind: Int,
        loc: SourceLocation,
        nested: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val range: Range = Positions.toLspRange(loc, length = name.length.coerceAtLeast(1))
        return DocumentSymbol(name, kind, range, range).apply {
            if (nested.isNotEmpty()) children = nested
        }
    }
}
