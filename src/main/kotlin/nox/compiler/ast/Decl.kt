package nox.compiler.ast

import nox.compiler.types.SourceLocation
import nox.compiler.types.Symbol
import nox.compiler.types.TypeRef

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
sealed class Decl(
    val loc: SourceLocation,
)

/**
 * User-defined struct type: `type Point { int x; int y; }`
 */
class TypeDef(
    val name: String,
    val fields: List<FieldDecl>,
    loc: SourceLocation,
) : Decl(loc)

/**
 * A single field within a [TypeDef].
 */
data class FieldDecl(
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
class FuncDef(
    val returnType: TypeRef,
    val name: String,
    val params: List<Param>,
    val body: Block,
    loc: SourceLocation,
) : Decl(loc) {
    /** Frame size for pMem. Set by register allocator. */
    var maxPrimitiveRegisters: Int = 0

    /** Frame size for rMem. Set by register allocator. */
    var maxReferenceRegisters: Int = 0
}

/**
 * The `main` entry point:
 * ```
 * main(string url) { ... }
 * ```
 *
 * `main` always implicitly returns `string`.
 */
class MainDef(
    val params: List<Param>,
    val body: Block,
    loc: SourceLocation,
) : Decl(loc) {
    /** Frame size for pMem. Set by register allocator. */
    var maxPrimitiveRegisters: Int = 0

    /** Frame size for rMem. Set by register allocator. */
    var maxReferenceRegisters: Int = 0
}

/**
 * Global variable declaration: `int counter = 0;`
 *
 * [initializer] is `null` when the variable is uninitialized (uses type default).
 * [globalSlot] is the index into global memory, offset by the module's `globalBaseOffset`.
 */
class GlobalVarDecl(
    val type: TypeRef,
    val name: String,
    val initializer: Expr?,
    loc: SourceLocation,
) : Decl(loc) {
    /** Index in global memory. Set by semantic analyzer / import resolver. */
    var globalSlot: Int = -1
}

/**
 * Import declaration: `import "path.nox" as namespace;`
 *
 * [resolvedPath] is set by the import resolver to the absolute file path.
 */
class ImportDecl(
    val path: String,
    val namespace: String,
    loc: SourceLocation,
) : Decl(loc) {
    /** Absolute resolved path. Set by import resolver. */
    var resolvedPath: String? = null
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
data class Param(
    val type: TypeRef,
    val name: String,
    val defaultValue: Expr?,
    val isVarargs: Boolean,
    val loc: SourceLocation,
) {
    /** ParamSymbol created by the type resolver. Set during semantic analysis. */
    var resolvedSymbol: Symbol? = null
}

/** Placeholder for invalid or un-parseable declarations. */
class ErrorDecl(
    loc: SourceLocation,
) : Decl(loc)
