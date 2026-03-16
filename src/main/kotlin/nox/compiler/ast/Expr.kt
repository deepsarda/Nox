package nox.compiler.ast

import nox.compiler.types.*


/**
 * Base class for all expression nodes.
 * Every expression carries a [SourceLocation] and two mutable
 * annotation slots that are populated by later compiler passes:
 *  - resolvedType is set by the SemanticAnalyzer
 *  - register is set by the RegisterAllocator
 *
 * See docs/compiler/ast.md for the full design rationale.
 * @property loc source position of this expression
 */
sealed class Expr(
    val loc: SourceLocation,
) {
    /** Resolved type of this expression. Set by the semantic analyzer. */
    var resolvedType: TypeRef? = null

    /** Assigned register index. Set by the register allocator (`-1` = unassigned). */
    var register: Int = -1
}

// Literals

class IntLiteralExpr(
    val value: Long,
    loc: SourceLocation,
) : Expr(loc)

class DoubleLiteralExpr(
    val value: Double,
    loc: SourceLocation,
) : Expr(loc)

class BoolLiteralExpr(
    val value: Boolean,
    loc: SourceLocation,
) : Expr(loc)

/**
 * A string literal with escape sequences already resolved.
 * For example, the source `"hello\n"` produces `value = "hello" + newline`.
 */
class StringLiteralExpr(
    val value: String,
    loc: SourceLocation,
) : Expr(loc)

class NullLiteralExpr(
    loc: SourceLocation,
) : Expr(loc)

// Template Literals

/**
 * A backtick-delimited template literal: `` `text ${expr} text` ``
 *
 * Decomposed into an ordered list of [TemplatePart]s during AST construction.
 */
class TemplateLiteralExpr(
    val parts: List<TemplatePart>,
    loc: SourceLocation,
) : Expr(loc)

/**
 * A segment of a template literal, either raw text or an interpolated expression.
 */
sealed interface TemplatePart {
    data class Text(
        val value: String,
    ) : TemplatePart

    data class Interpolation(
        val expression: Expr,
    ) : TemplatePart

    data object ErrorPart : TemplatePart
}

// Composite Literals

/**
 * Array literal: `[1, 2, 3]`
 *
 * [elementType] is inferred by the semantic analyzer from the element expressions.
 */
class ArrayLiteralExpr(
    val elements: List<Expr>,
    loc: SourceLocation,
) : Expr(loc) {
    /** Inferred element type. Set by the semantic analyzer. */
    var elementType: TypeRef? = null
}

/**
 * Struct literal: `{ x: 1, y: 2 }`
 *
 * [structType] is inferred from assignment context during semantic analysis.
 */
class StructLiteralExpr(
    val fields: List<FieldInit>,
    loc: SourceLocation,
) : Expr(loc) {
    /** Inferred struct type. Set by the semantic analyzer. */
    var structType: TypeRef? = null
}

/**
 * A single field initializer within a struct literal.
 */
data class FieldInit(
    val name: String,
    val value: Expr,
    val loc: SourceLocation,
)

// Operators

/** Binary expression: `left op right` */
class BinaryExpr(
    val left: Expr,
    val op: BinaryOp,
    val right: Expr,
    loc: SourceLocation,
) : Expr(loc)

/** Unary prefix expression: `op operand` (e.g. `-x`, `!flag`) */
class UnaryExpr(
    val op: UnaryOp,
    val operand: Expr,
    loc: SourceLocation,
) : Expr(loc)

/** Postfix expression: `operand++` or `operand--` */
class PostfixExpr(
    val operand: Expr,
    val op: PostfixOp,
    loc: SourceLocation,
) : Expr(loc)

/** Cast expression: `expr as Type` */
class CastExpr(
    val operand: Expr,
    val targetType: TypeRef,
    loc: SourceLocation,
) : Expr(loc)

// References

/**
 * An identifier reference: a variable, parameter, or global name.
 *
 * [resolvedSymbol] is populated by the semantic analyzer to link
 * this identifier to its declaration.
 */
class IdentifierExpr(
    val name: String,
    loc: SourceLocation,
) : Expr(loc) {
    /** Resolved symbol (VarSymbol, ParamSymbol, GlobalSymbol). Set by semantic analyzer. */
    var resolvedSymbol: Symbol? = null
}

/**
 * Function call: `func(args)`
 *
 * [resolvedFunction] links to the AST [FuncDef] node after semantic analysis.
 */
class FuncCallExpr(
    val name: String,
    val args: List<Expr>,
    loc: SourceLocation,
) : Expr(loc) {
    /** Resolved function definition. Set by semantic analyzer. */
    var resolvedFunction: FuncDef? = null
}

/**
 * Method call: `target.method(args)`
 *
 * Could resolve to a namespace function, built-in type method,
 * plugin type method, or UFCS free function.
 */
class MethodCallExpr(
    val target: Expr,
    val methodName: String,
    val args: List<Expr>,
    loc: SourceLocation,
) : Expr(loc) {
    /** How this method call was resolved. */
    enum class Resolution {
        UFCS,
        TYPE_BOUND,
        NAMESPACE,
    }

    var resolution: Resolution? = null
    var resolvedTarget: CallTarget? = null
}

/** Field access: `target.field` */
class FieldAccessExpr(
    val target: Expr,
    val fieldName: String,
    loc: SourceLocation,
) : Expr(loc)

/** Index access: `target[index]` */
class IndexAccessExpr(
    val target: Expr,
    val index: Expr,
    loc: SourceLocation,
) : Expr(loc)

/** Placeholder for invalid or un-parseable expressions. */
class ErrorExpr(
    loc: SourceLocation,
) : Expr(loc)
