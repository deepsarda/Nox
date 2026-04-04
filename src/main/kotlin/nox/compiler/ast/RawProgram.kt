package nox.compiler.ast

import nox.compiler.types.SourceLocation

/**
 * Root of the AST for a single `.nox` file.
 *
 * Contains metadata headers, import declarations, and all top-level
 * declarations (types, functions, globals, main).
 *
 * See docs/compiler/ast.md for the full design rationale.
 *
 * Convenience maps ([typesByName], [functionsByName]) and [globals]
 * are populated during AST construction for fast lookup in later phases.
 *
 * @property fileName     the source file name
 * @property headers      `@tool:` metadata headers
 * @property imports      import declarations
 * @property declarations all top-level declarations
 */
class RawProgram(
    val fileName: String,
    val headers: List<RawHeader>,
    val imports: List<RawImportDecl>,
    val declarations: List<RawDecl>,
) {
    /** Type definitions indexed by name. Populated during AST construction. */
    val typesByName: MutableMap<String, RawTypeDef> = mutableMapOf()

    /** Function definitions indexed by name. Populated during AST construction. */
    val functionsByName: MutableMap<String, RawFuncDef> = mutableMapOf()


    /** All global variable declarations, in order. */
    val globals: MutableList<RawGlobalVarDecl> = mutableListOf()

    /** The main entry point, if any. */
    val main: RawMainDef? get() = declarations.filterIsInstance<RawMainDef>().firstOrNull()

    /** All function definitions, in declaration order (convenience view of [functionsByName]). */
    val functions: Collection<RawFuncDef> get() = functionsByName.values

    /**
     * Original source lines of this file, populated by the parser for use by the disassembler.
     * Empty if the source was not provided.
     */
    val sourceLines: MutableList<String> = mutableListOf()
}

/**
 * A `@tool:` metadata header.
 *
 * The `@tool:` prefix is stripped during AST construction,
 * so [key] contains only the bare name (e.g. `"name"`, `"description"`).
 *
 * @property key   the header key (prefix stripped)
 * @property value the header value string
 * @property loc   source position
 */
data class RawHeader(
    val key: String,
    val value: String,
    val loc: SourceLocation,
)
