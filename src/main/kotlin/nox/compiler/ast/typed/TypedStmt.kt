package nox.compiler.ast.typed

import nox.compiler.types.*

/**
 * Base class for all statement nodes.
 *
 * See docs/compiler/ast.md for the full design rationale.
 *
 * @property loc source position of this statement
 */
sealed class TypedStmt(
    val loc: SourceLocation,
)

// Variable Declaration

/**
 * Variable declaration: `int x = 42;`
 *
 * [register] is assigned by the register allocator.
 */
class TypedVarDeclStmt(
    val type: TypeRef,
    val name: String,
    val initializer: TypedExpr,
    loc: SourceLocation,
    val resolvedSymbol: Symbol,
    var register: Int = -1,
) : TypedStmt(loc)

// Assignment & Mutation

/**
 * Assignment statement: `target = value;` or `target += value;`
 *
 * [target] must be a valid l-value (TypedIdentifierExpr, TypedFieldAccessExpr, or TypedIndexAccessExpr).
 */
class TypedAssignStmt(
    val target: TypedExpr,
    val op: AssignOp,
    val value: TypedExpr,
    loc: SourceLocation,
) : TypedStmt(loc)

/**
 * Increment / decrement statement: `i++;` or `i--;`
 */
class TypedIncrementStmt(
    val target: TypedExpr,
    val op: PostfixOp,
    loc: SourceLocation,
) : TypedStmt(loc)

// Control Flow

/**
 * If / else-if / else statement.
 *
 * [elseBlock] is `null` when there is no `else` clause.
 */
class TypedIfStmt(
    val condition: TypedExpr,
    val thenBlock: TypedBlock,
    val elseIfs: List<ElseIf>,
    val elseBlock: TypedBlock?,
    loc: SourceLocation,
) : TypedStmt(loc) {
    data class ElseIf(
        val condition: TypedExpr,
        val body: TypedBlock,
        val loc: SourceLocation,
    )
}

/**
 * While loop: `while (cond) { body }`
 */
class TypedWhileStmt(
    val condition: TypedExpr,
    val body: TypedBlock,
    loc: SourceLocation,
) : TypedStmt(loc)

/**
 * C-style for loop: `for (init; condition; update) { body }`
 *
 * All three parts (init, condition, update) are optional,
 * allowing infinite loops via `for (;;) { ... }`.
 */
class TypedForStmt(
    val init: TypedStmt?,
    val condition: TypedExpr?,
    val update: TypedStmt?,
    val body: TypedBlock,
    loc: SourceLocation,
) : TypedStmt(loc)

/**
 * Foreach loop: `foreach (Type name in iterable) { body }`
 *
 * [elementRegister] is assigned by the register allocator for the loop variable.
 */
class TypedForEachStmt(
    val elementType: TypeRef,
    val elementName: String,
    val iterable: TypedExpr,
    val body: TypedBlock,
    loc: SourceLocation,
    val resolvedSymbol: Symbol,
    var elementRegister: Int = -1,
) : TypedStmt(loc)

// Jumps

/** `return value;` or bare `return;` (when [value] is `null`). */
class TypedReturnStmt(
    val value: TypedExpr?,
    loc: SourceLocation,
) : TypedStmt(loc)

/** `yield value;` sends streaming output to the host. */
class TypedYieldStmt(
    val value: TypedExpr,
    loc: SourceLocation,
) : TypedStmt(loc)

/** `break;` exits the innermost loop. */
class TypedBreakStmt(
    loc: SourceLocation,
) : TypedStmt(loc)

/** `continue;` jumps to the next iteration of the innermost loop. */
class TypedContinueStmt(
    loc: SourceLocation,
) : TypedStmt(loc)

// Exception Handling

/** `throw value;` */
class TypedThrowStmt(
    val value: TypedExpr,
    loc: SourceLocation,
) : TypedStmt(loc)

/**
 * Try-catch statement with one or more catch clauses.
 */
class TypedTryCatchStmt(
    val tryBlock: TypedBlock,
    val catchClauses: List<TypedCatchClause>,
    loc: SourceLocation,
) : TypedStmt(loc)

/**
 * A single catch clause.
 *
 * @property exceptionType the error type name to match, or `null` for a catch-all
 * @property variableName  the name bound to the error message string
 */
data class TypedCatchClause(
    val exceptionType: String?,
    val variableName: String,
    val body: TypedBlock,
    val loc: SourceLocation,
    val resolvedSymbol: Symbol,
    var register: Int = -1,
)

// Expression Statement

/** An expression used as a statement: `func();` */
class TypedExprStmt(
    val expression: TypedExpr,
    loc: SourceLocation,
) : TypedStmt(loc)

// TypedBlock

/**
 * A braced block: `{ stmt; stmt; ... }`
 *
 * [scopeDepth] is assigned by the semantic analyzer to track nested scopes.
 */
class TypedBlock(
    val statements: List<TypedStmt>,
    loc: SourceLocation,
    val scopeDepth: Int = -1,
) : TypedStmt(loc)

/** Placeholder for invalid or un-parseable statements. */
class TypedErrorStmt(
    loc: SourceLocation,
) : TypedStmt(loc)
