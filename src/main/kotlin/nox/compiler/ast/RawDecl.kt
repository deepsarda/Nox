package nox.compiler.ast

import nox.compiler.types.*

/*
 *  Declaration AST Nodes
 *
 *  Top-level constructs: type definitions, functions, main entry point,
 *  global variables, and imports.
 *
 *  See docs/compiler/ast.md for the full design rationale.
 */

/**
 * Base class for all top-level declaration nodes.
 *
 * @property loc source position of this declaration
 */
sealed class RawDecl(
    val loc: SourceLocation,
)

/**
 * User-defined struct type: `type Point { int x; int y; }`
 */
class RawTypeDef(
    val name: String,
    val fields: List<RawFieldDecl>,
    loc: SourceLocation,
) : RawDecl(loc)

/**
 * A single field within a [RawTypeDef].
 */
data class RawFieldDecl(
    val type: TypeRef,
    val name: String,
    val loc: SourceLocation,
)

/**
 * User-defined function:
 * ```
 * int add(int a, int b) { return a + b; }
 * ```
 *
 * Mutable [maxPrimitiveRegisters] and [maxReferenceRegisters] are
 * populated by the register allocator to size the call frame.
 */
class RawFuncDef(
    val returnType: TypeRef,
    val name: String,
    val params: List<RawParam>,
    val body: RawBlock,
    loc: SourceLocation,
) : RawDecl(loc) {

}

/**
 * The `main` entry point:
 * ```
 * main(string url) { ... }
 * ```
 *
 * `main` always implicitly returns `string`.
 */
class RawMainDef(
    val params: List<RawParam>,
    val body: RawBlock,
    loc: SourceLocation,
) : RawDecl(loc) {

}

/**
 * Global variable declaration: `int counter = 0;`
 *
 * [initializer] is `null` when the variable is uninitialized (uses type default).
 * [globalSlot] is the index into global memory, offset by the module's `globalBaseOffset`.
 */
class RawGlobalVarDecl(
    val type: TypeRef,
    val name: String,
    val initializer: RawExpr?,
    loc: SourceLocation,
) : RawDecl(loc) {
}

/**
 * Import declaration: `import "path.nox" as namespace;`
 *
 * [resolvedPath] is set by the import resolver to the absolute file path.
 */
class RawImportDecl(
    val path: String,
    val namespace: String,
    loc: SourceLocation,
) : RawDecl(loc) {
}

/**
 * A function parameter.
 *
 * @property type        the declared type
 * @property name        the parameter name
 * @property defaultValue the default value expression, or `null` if required
 * @property isVarargs   whether this is a varargs parameter (`int ...values[]`)
 * @property loc         source position
 */
data class RawParam(
    val type: TypeRef,
    val name: String,
    val defaultValue: RawExpr?,
    val isVarargs: Boolean,
    val loc: SourceLocation,
) {
}

/** Placeholder for invalid or un-parseable declarations. */
class RawErrorDecl(
    loc: SourceLocation,
) : RawDecl(loc)
