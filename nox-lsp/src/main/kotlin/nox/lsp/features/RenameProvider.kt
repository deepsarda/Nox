package nox.lsp.features

import nox.compiler.ast.RawProgram
import nox.compiler.ast.typed.TypedProgram
import nox.lsp.protocol.*

object RenameProvider {
    private val KEYWORDS = setOf("type", "main", "if", "else", "while", "for", "foreach", "in", "return", "yield", "break", "continue", "try", "catch", "throw", "import", "null", "true", "false", "int", "double", "boolean", "string", "json", "void", "as")
    private val IDENT = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    fun rename(
        typed: TypedProgram,
        raw: RawProgram,
        uri: String,
        lspLine: Int,
        lspColumn: Int,
        newName: String,
    ): WorkspaceEdit? {
        if (!IDENT.matches(newName) || newName in KEYWORDS) return null

        val locations: List<Location> = ReferencesProvider.references(
            typed = typed,
            raw = raw,
            uri = uri,
            lspLine = lspLine,
            lspColumn = lspColumn,
            includeDeclaration = true,
        )
        if (locations.isEmpty()) return null

        val changes = mutableMapOf<String, MutableList<TextEdit>>()
        locations.forEach { loc ->
            changes.getOrPut(loc.uri) { mutableListOf() }.add(TextEdit(loc.range, newName))
        }
        return WorkspaceEdit(changes)
    }
}
