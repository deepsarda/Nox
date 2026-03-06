package nox.compiler.semantic

import nox.compiler.ast.FuncDef
import nox.compiler.ast.TypeRef

/**
 * A resolved function or method that can be called at runtime.
 *
 * Used as the type for [nox.compiler.ast.MethodCallExpr.resolvedTarget] and
 * [nox.compiler.ast.FuncCallExpr.resolvedFunction].
 *
 * @property name       the function/method name
 * @property params     parameter names and types, in declaration order
 * @property returnType the declared return type
 * @property astNode    back-reference to the AST node for user-defined functions,
 *                      `null` for built-in functions and methods
 */
data class CallTarget(
    val name: String,
    val params: List<Pair<String, TypeRef>>,
    val returnType: TypeRef,
    val astNode: FuncDef? = null,
)
