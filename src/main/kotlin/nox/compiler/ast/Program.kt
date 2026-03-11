package nox.compiler.ast

import nox.compiler.types.*

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
class Program(
    val fileName: String,
    val headers: List<Header>,
    val imports: List<ImportDecl>,
    val declarations: List<Decl>,
) {
    /** Type definitions indexed by name. Populated during AST construction. */
    val typesByName: MutableMap<String, TypeDef> = mutableMapOf()

    /** Function definitions indexed by name. Populated during AST construction. */
    val functionsByName: MutableMap<String, FuncDef> = mutableMapOf()

    /** The `main` entry point, if present. */
    var main: MainDef? = null

    /** All global variable declarations, in order. */
    val globals: MutableList<GlobalVarDecl> = mutableListOf()

    /** All function definitions, in declaration order (convenience view of [functionsByName]). */
    val functions: Collection<FuncDef> get() = functionsByName.values

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
data class Header(
    val key: String,
    val value: String,
    val loc: SourceLocation,
)
