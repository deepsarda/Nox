package nox.lsp.features

import nox.compiler.ast.RawProgram
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

object TypeHierarchyProvider {
    fun prepare(
        raw: RawProgram,
        uri: String,
        position: Position,
    ): List<TypeHierarchyItem> {
        val typeDef = raw.typesByName.values.find {
            val loc = it.loc
            loc.line == position.line + 1 && position.character >= loc.column && position.character <= loc.column + it.name.length
        } ?: return emptyList()

        val range = Positions.toLspRange(typeDef.loc, length = typeDef.name.length)
        return listOf(
            TypeHierarchyItem(
                name = typeDef.name,
                kind = SymbolKind.Struct,
                uri = uri,
                range = range,
                selectionRange = range,
            ),
        )
    }

    fun supertypes(item: TypeHierarchyItem): List<TypeHierarchyItem> = emptyList()
    fun subtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> = emptyList()
}
