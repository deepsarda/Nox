package nox.compiler.ast.typed

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
sealed class TypedDecl(
    val loc: SourceLocation,
)

/**
 * User-defined struct type: `type Point { int x; int y; }`
 */
class TypedTypeDef(
    val name: String,
    val nameLoc: SourceLocation,
    val fields: List<TypedFieldDecl>,
    loc: SourceLocation,
) : TypedDecl(loc)

/**
 * A single field within a [TypedTypeDef].
 */
data class TypedFieldDecl(
    val type: TypeRef,
    val name: String,
    val nameLoc: SourceLocation,
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
class TypedFuncDef(
    val returnType: TypeRef,
    val name: String,
    val nameLoc: SourceLocation,
    val params: List<TypedParam>,
    val body: TypedBlock,
    loc: SourceLocation,
    var maxPrimitiveRegisters: Int = 0,
    var maxReferenceRegisters: Int = 0,
) : TypedDecl(loc)

/**
 * The `main` entry point:
 * ```
 * main(string url) { ... }
 * ```
 *
 * `main` always implicitly returns `string`.
 */
class TypedMainDef(
    val returnType: TypeRef,
    val params: List<TypedParam>,
    val body: TypedBlock,
    loc: SourceLocation,
    var maxPrimitiveRegisters: Int = 0,
    var maxReferenceRegisters: Int = 0,
) : TypedDecl(loc)

/**
 * Global variable declaration: `int counter = 0;`
 *
 * [initializer] is `null` when the variable is uninitialized (uses type default).
 * [globalSlot] is the index into global memory, offset by the module's `globalBaseOffset`.
 */
class TypedGlobalVarDecl(
    val type: TypeRef,
    val name: String,
    val nameLoc: SourceLocation,
    val initializer: TypedExpr?,
    loc: SourceLocation,
    var globalSlot: Int = -1,
) : TypedDecl(loc)

/**
 * Import declaration: `import "path.nox" as namespace;`
 *
 * [resolvedPath] is set by the import resolver to the absolute file path.
 */
class TypedImportDecl(
    val path: String,
    val namespace: String,
    loc: SourceLocation,
    val resolvedPath: String,
) : TypedDecl(loc)

/**
 * A function parameter.
 *
 * @property type        the declared type
 * @property name        the parameter name
 * @property defaultValue the default value expression, or `null` if required
 * @property isVarargs   whether this is a varargs parameter (`int ...values[]`)
 * @property loc         source position
 */
data class TypedParam(
    val type: TypeRef,
    val name: String,
    val nameLoc: SourceLocation,
    val defaultValue: TypedExpr?,
    val isVarargs: Boolean,
    val loc: SourceLocation,
    val resolvedSymbol: Symbol,
)

/** Placeholder for invalid or un-parseable declarations. */
class TypedErrorDecl(
    loc: SourceLocation,
) : TypedDecl(loc)
