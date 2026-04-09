package nox.compiler.ast.typed

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
sealed class TypedExpr(
    val loc: SourceLocation,
    val type: TypeRef,
)

// Literals

class TypedIntLiteralExpr(
    val value: Long,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

class TypedDoubleLiteralExpr(
    val value: Double,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

class TypedBoolLiteralExpr(
    val value: Boolean,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/**
 * A string literal with escape sequences already resolved.
 * For example, the source `"hello\n"` produces `value = "hello" + newline`.
 */
class TypedStringLiteralExpr(
    val value: String,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

class TypedNullLiteralExpr(
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

// Template Literals

/**
 * A backtick-delimited template literal: `` `text ${expr} text` ``
 *
 * Decomposed into an ordered list of [TypedTemplatePart]s during AST construction.
 */
class TypedTemplateLiteralExpr(
    val parts: List<TypedTemplatePart>,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/**
 * A segment of a template literal, either raw text or an interpolated expression.
 */
sealed interface TypedTemplatePart {
    data class Text(
        val value: String,
    ) : TypedTemplatePart

    data class Interpolation(
        val expression: TypedExpr,
    ) : TypedTemplatePart

    data object ErrorPart : TypedTemplatePart
}

// Composite Literals

/**
 * Array literal: `[1, 2, 3]`
 *
 * [elementType] is inferred by the semantic analyzer from the element expressions.
 */
class TypedArrayLiteralExpr(
    val elements: List<TypedExpr>,
    loc: SourceLocation,
    type: TypeRef,
    val elementType: TypeRef,
) : TypedExpr(loc, type)

/**
 * Struct literal: `{ x: 1, y: 2 }`
 *
 * [structType] is inferred from assignment context during semantic analysis.
 */
class TypedStructLiteralExpr(
    val fields: List<TypedFieldInit>,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/**
 * A single field initializer within a struct literal.
 */
data class TypedFieldInit(
    val name: String,
    val value: TypedExpr,
    val loc: SourceLocation,
)

// Operators

/** Binary expression: `left op right` */
class TypedBinaryExpr(
    val left: TypedExpr,
    val op: BinaryOp,
    val right: TypedExpr,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/** Unary prefix expression: `op operand` (e.g. `-x`, `!flag`) */
class TypedUnaryExpr(
    val op: UnaryOp,
    val operand: TypedExpr,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/** Postfix expression: `operand++` or `operand--` */
class TypedPostfixExpr(
    val operand: TypedExpr,
    val op: PostfixOp,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/** Cast expression: `expr as Type` */
class TypedCastExpr(
    val operand: TypedExpr,
    val targetType: TypeRef,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

// References

/**
 * An identifier reference: a variable, parameter, or global name.
 *
 * [resolvedSymbol] is populated by the semantic analyzer to link
 * this identifier to its declaration.
 */
class TypedIdentifierExpr(
    val name: String,
    loc: SourceLocation,
    type: TypeRef,
    val resolvedSymbol: Symbol,
) : TypedExpr(loc, type)

/**
 * Function call: `func(args)`
 *
 * [resolvedFunction] links to the AST [TypedFuncDef] node after semantic analysis.
 */
class TypedFuncCallExpr(
    val name: String,
    val args: List<TypedExpr>,
    loc: SourceLocation,
    type: TypeRef,
    val resolvedFunction: FuncSymbol,
) : TypedExpr(loc, type)

/**
 * Method call: `target.method(args)`
 *
 * Could resolve to a namespace function, built-in type method,
 * plugin type method, or UFCS free function.
 */
class TypedMethodCallExpr(
    val target: TypedExpr,
    val methodName: String,
    val args: List<TypedExpr>,
    loc: SourceLocation,
    type: TypeRef,
    val resolution: Resolution,
    val resolvedTarget: CallTarget,
) : TypedExpr(loc, type) {
    /** How this method call was resolved. */
    enum class Resolution {
        UFCS,
        TYPE_BOUND,
        NAMESPACE,
    }
}

/** Field access: `target.field` */
class TypedFieldAccessExpr(
    val target: TypedExpr,
    val fieldName: String,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/** Index access: `target[index]` */
class TypedIndexAccessExpr(
    val target: TypedExpr,
    val index: TypedExpr,
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)

/** Placeholder for invalid or un-parseable expressions. */
class TypedErrorExpr(
    loc: SourceLocation,
    type: TypeRef,
) : TypedExpr(loc, type)
