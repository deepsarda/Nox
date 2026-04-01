package nox.compiler.ast

import nox.compiler.types.*

/**
 * Base class for all statement nodes.
 *
 * See docs/compiler/ast.md for the full design rationale.
 *
 * @property loc source position of this statement
 */
sealed class Stmt(
    val loc: SourceLocation,
)

// Variable Declaration

/**
 * Variable declaration: `int x = 42;`
 *
 * [register] is assigned by the register allocator.
 */
class VarDeclStmt(
    val type: TypeRef,
    val name: String,
    val initializer: Expr,
    loc: SourceLocation,
) : Stmt(loc) {
    /** Register assigned to this local variable. Set by register allocator. */
    var register: Int = -1

    /** Back-link to the VarSymbol created in the type resolver. Set during semantic analysis. */
    var resolvedSymbol: Symbol? = null
}

// Assignment & Mutation

/**
 * Assignment statement: `target = value;` or `target += value;`
 *
 * [target] must be a valid l-value (IdentifierExpr, FieldAccessExpr, or IndexAccessExpr).
 */
class AssignStmt(
    val target: Expr,
    val op: AssignOp,
    val value: Expr,
    loc: SourceLocation,
) : Stmt(loc)

/**
 * Increment / decrement statement: `i++;` or `i--;`
 */
class IncrementStmt(
    val target: Expr,
    val op: PostfixOp,
    loc: SourceLocation,
) : Stmt(loc)

// Control Flow

/**
 * If / else-if / else statement.
 *
 * [elseBlock] is `null` when there is no `else` clause.
 */
class IfStmt(
    val condition: Expr,
    val thenBlock: Block,
    val elseIfs: List<ElseIf>,
    val elseBlock: Block?,
    loc: SourceLocation,
) : Stmt(loc) {
    data class ElseIf(
        val condition: Expr,
        val body: Block,
        val loc: SourceLocation,
    )
}

/**
 * While loop: `while (cond) { body }`
 */
class WhileStmt(
    val condition: Expr,
    val body: Block,
    loc: SourceLocation,
) : Stmt(loc)

/**
 * C-style for loop: `for (init; condition; update) { body }`
 *
 * All three parts (init, condition, update) are optional,
 * allowing infinite loops via `for (;;) { ... }`.
 */
class ForStmt(
    val init: Stmt?,
    val condition: Expr?,
    val update: Stmt?,
    val body: Block,
    loc: SourceLocation,
) : Stmt(loc)

/**
 * Foreach loop: `foreach (Type name in iterable) { body }`
 *
 * [elementRegister] is assigned by the register allocator for the loop variable.
 */
class ForEachStmt(
    val elementType: TypeRef,
    val elementName: String,
    val iterable: Expr,
    val body: Block,
    loc: SourceLocation,
) : Stmt(loc) {
    /** Register for the loop variable. Set by register allocator. */
    var elementRegister: Int = -1
}

// Jumps

/** `return value;` or bare `return;` (when [value] is `null`). */
class ReturnStmt(
    val value: Expr?,
    loc: SourceLocation,
) : Stmt(loc)

/** `yield value;` sends streaming output to the host. */
class YieldStmt(
    val value: Expr,
    loc: SourceLocation,
) : Stmt(loc)

/** `break;` exits the innermost loop. */
class BreakStmt(
    loc: SourceLocation,
) : Stmt(loc)

/** `continue;` jumps to the next iteration of the innermost loop. */
class ContinueStmt(
    loc: SourceLocation,
) : Stmt(loc)

// Exception Handling

/** `throw value;` */
class ThrowStmt(
    val value: Expr,
    loc: SourceLocation,
) : Stmt(loc)

/**
 * Try-catch statement with one or more catch clauses.
 */
class TryCatchStmt(
    val tryBlock: Block,
    val catchClauses: List<CatchClause>,
    loc: SourceLocation,
) : Stmt(loc)

/**
 * A single catch clause.
 *
 * @property exceptionType the error type name to match, or `null` for a catch-all
 * @property variableName  the name bound to the error message string
 */
data class CatchClause(
    val exceptionType: String?,
    val variableName: String,
    val body: Block,
    val loc: SourceLocation,
) {
    var resolvedSymbol: Symbol? = null
    var register: Int = -1
}

// Expression Statement

/** An expression used as a statement: `func();` */
class ExprStmt(
    val expression: Expr,
    loc: SourceLocation,
) : Stmt(loc)

// Block

/**
 * A braced block: `{ stmt; stmt; ... }`
 *
 * [scopeDepth] is assigned by the semantic analyzer to track nested scopes.
 */
class Block(
    val statements: List<Stmt>,
    loc: SourceLocation,
) : Stmt(loc) {
    /** Nesting depth of this scope. Set by semantic analyzer. */
    var scopeDepth: Int = -1
}

/** Placeholder for invalid or un-parseable statements. */
class ErrorStmt(
    loc: SourceLocation,
) : Stmt(loc)
