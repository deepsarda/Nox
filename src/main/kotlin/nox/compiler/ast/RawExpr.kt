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
sealed class RawExpr(
    val loc: SourceLocation,
)

// Literals

class RawIntLiteralExpr(
    val value: Long,
    loc: SourceLocation,
) : RawExpr(loc)

class RawDoubleLiteralExpr(
    val value: Double,
    loc: SourceLocation,
) : RawExpr(loc)

class RawBoolLiteralExpr(
    val value: Boolean,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * A string literal with escape sequences already resolved.
 * For example, the source `"hello\n"` produces `value = "hello" + newline`.
 */
class RawStringLiteralExpr(
    val value: String,
    loc: SourceLocation,
) : RawExpr(loc)

class RawNullLiteralExpr(
    loc: SourceLocation,
) : RawExpr(loc)

// Template Literals

/**
 * A backtick-delimited template literal: `` `text ${expr} text` ``
 *
 * Decomposed into an ordered list of [RawTemplatePart]s during AST construction.
 */
class RawTemplateLiteralExpr(
    val parts: List<RawTemplatePart>,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * A segment of a template literal, either raw text or an interpolated expression.
 */
sealed interface RawTemplatePart {
    data class Text(
        val value: String,
    ) : RawTemplatePart

    data class Interpolation(
        val expression: RawExpr,
    ) : RawTemplatePart

    data object ErrorPart : RawTemplatePart
}

// Composite Literals

/**
 * Array literal: `[1, 2, 3]`
 *
 * [elementType] is inferred by the semantic analyzer from the element expressions.
 */
class RawArrayLiteralExpr(
    val elements: List<RawExpr>,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * Struct literal: `{ x: 1, y: 2 }`
 *
 * [structType] is inferred from assignment context during semantic analysis.
 */
class RawStructLiteralExpr(
    val fields: List<RawFieldInit>,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * A single field initializer within a struct literal.
 */
data class RawFieldInit(
    val name: String,
    val value: RawExpr,
    val loc: SourceLocation,
)

// Operators

/** Binary expression: `left op right` */
class RawBinaryExpr(
    val left: RawExpr,
    val op: BinaryOp,
    val right: RawExpr,
    loc: SourceLocation,
) : RawExpr(loc)

/** Unary prefix expression: `op operand` (e.g. `-x`, `!flag`) */
class RawUnaryExpr(
    val op: UnaryOp,
    val operand: RawExpr,
    loc: SourceLocation,
) : RawExpr(loc)

/** Postfix expression: `operand++` or `operand--` */
class RawPostfixExpr(
    val operand: RawExpr,
    val op: PostfixOp,
    loc: SourceLocation,
) : RawExpr(loc)

/** Cast expression: `expr as Type` */
class RawCastExpr(
    val operand: RawExpr,
    val targetType: TypeRef,
    loc: SourceLocation,
) : RawExpr(loc)

// References

/**
 * An identifier reference: a variable, parameter, or global name.
 *
 * [resolvedSymbol] is populated by the semantic analyzer to link
 * this identifier to its declaration.
 */
class RawIdentifierExpr(
    val name: String,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * Function call: `func(args)`
 *
 * [resolvedFunction] links to the AST [RawFuncDef] node after semantic analysis.
 */
class RawFuncCallExpr(
    val name: String,
    val args: List<RawExpr>,
    loc: SourceLocation,
) : RawExpr(loc)

/**
 * Method call: `target.method(args)`
 *
 * Could resolve to a namespace function, built-in type method,
 * plugin type method, or UFCS free function.
 */
class RawMethodCallExpr(
    val target: RawExpr,
    val methodName: String,
    val args: List<RawExpr>,
    loc: SourceLocation,
) : RawExpr(loc) {
    /** How this method call was resolved. */
    enum class Resolution {
        UFCS,
        TYPE_BOUND,
        NAMESPACE,
    }
}

/** Field access: `target.field` */
class RawFieldAccessExpr(
    val target: RawExpr,
    val fieldName: String,
    loc: SourceLocation,
) : RawExpr(loc)

/** Index access: `target[index]` */
class RawIndexAccessExpr(
    val target: RawExpr,
    val index: RawExpr,
    loc: SourceLocation,
) : RawExpr(loc)

/** Placeholder for invalid or un-parseable expressions. */
class RawErrorExpr(
    loc: SourceLocation,
) : RawExpr(loc)
