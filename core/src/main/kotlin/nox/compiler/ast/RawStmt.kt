package nox.compiler.ast

import nox.compiler.types.*

/**
 * Base class for all statement nodes.
 *
 * See docs/compiler/ast.md for the full design rationale.
 *
 * @property loc source position of this statement
 */
sealed class RawStmt(
    val loc: SourceLocation,
)

// Variable Declaration

/**
 * Variable declaration: `int x = 42;`
 *
 * [register] is assigned by the register allocator.
 */
class RawVarDeclStmt(
    val type: TypeRef,
    val name: String,
    val nameLoc: SourceLocation,
    val initializer: RawExpr,
    loc: SourceLocation,
) : RawStmt(loc)

// Assignment & Mutation

/**
 * Assignment statement: `target = value;` or `target += value;`
 *
 * [target] must be a valid l-value (RawIdentifierExpr, RawFieldAccessExpr, or RawIndexAccessExpr).
 */
class RawAssignStmt(
    val target: RawExpr,
    val op: AssignOp,
    val value: RawExpr,
    loc: SourceLocation,
) : RawStmt(loc)

/**
 * Increment / decrement statement: `i++;` or `i--;`
 */
class RawIncrementStmt(
    val target: RawExpr,
    val op: PostfixOp,
    loc: SourceLocation,
) : RawStmt(loc)

// Control Flow

/**
 * If / else-if / else statement.
 *
 * [elseBlock] is `null` when there is no `else` clause.
 */
class RawIfStmt(
    val condition: RawExpr,
    val thenBlock: RawBlock,
    val elseIfs: List<ElseIf>,
    val elseBlock: RawBlock?,
    loc: SourceLocation,
) : RawStmt(loc) {
    data class ElseIf(
        val condition: RawExpr,
        val body: RawBlock,
        val loc: SourceLocation,
    )
}

/**
 * While loop: `while (cond) { body }`
 */
class RawWhileStmt(
    val condition: RawExpr,
    val body: RawBlock,
    loc: SourceLocation,
) : RawStmt(loc)

/**
 * C-style for loop: `for (init; condition; update) { body }`
 *
 * All three parts (init, condition, update) are optional,
 * allowing infinite loops via `for (;;) { ... }`.
 */
class RawForStmt(
    val init: RawStmt?,
    val condition: RawExpr?,
    val update: RawStmt?,
    val body: RawBlock,
    loc: SourceLocation,
) : RawStmt(loc)

/**
 * Foreach loop: `foreach (Type name in iterable) { body }`
 *
 * [elementRegister] is assigned by the register allocator for the loop variable.
 */
class RawForEachStmt(
    val elementType: TypeRef,
    val elementName: String,
    val iterable: RawExpr,
    val body: RawBlock,
    loc: SourceLocation,
) : RawStmt(loc)

// Jumps

/** `return value;` or bare `return;` (when [value] is `null`). */
class RawReturnStmt(
    val value: RawExpr?,
    loc: SourceLocation,
) : RawStmt(loc)

/** `yield value;` sends streaming output to the host. */
class RawYieldStmt(
    val value: RawExpr,
    loc: SourceLocation,
) : RawStmt(loc)

/** `break;` exits the innermost loop. */
class RawBreakStmt(
    loc: SourceLocation,
) : RawStmt(loc)

/** `continue;` jumps to the next iteration of the innermost loop. */
class RawContinueStmt(
    loc: SourceLocation,
) : RawStmt(loc)

// Exception Handling

/** `throw value;` */
class RawThrowStmt(
    val value: RawExpr,
    loc: SourceLocation,
) : RawStmt(loc)

/**
 * Try-catch statement with one or more catch clauses.
 */
class RawTryCatchStmt(
    val tryBlock: RawBlock,
    val catchClauses: List<RawCatchClause>,
    loc: SourceLocation,
) : RawStmt(loc)

/**
 * A single catch clause.
 *
 * @property exceptionType the error type name to match, or `null` for a catch-all
 * @property variableName  the name bound to the error message string
 */
data class RawCatchClause(
    val exceptionType: String?,
    val variableName: String,
    val body: RawBlock,
    val loc: SourceLocation,
) {
    var resolvedSymbol: Symbol? = null
    var register: Int = -1
}

// Expression Statement

/** An expression used as a statement: `func();` */
class RawExprStmt(
    val expression: RawExpr,
    loc: SourceLocation,
) : RawStmt(loc)

// RawBlock

/**
 * A braced block: `{ stmt; stmt; ... }`
 *
 * [scopeDepth] is assigned by the semantic analyzer to track nested scopes.
 */
class RawBlock(
    val statements: List<RawStmt>,
    loc: SourceLocation,
) : RawStmt(loc)

/** Placeholder for invalid or un-parseable statements. */
class RawErrorStmt(
    loc: SourceLocation,
) : RawStmt(loc)
