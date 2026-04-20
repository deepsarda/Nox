package nox.compiler.types

import nox.compiler.ast.RawFuncDef

/**
 * A single parameter in a [CallTarget] signature.
 *
 * @property name            parameter name (for diagnostics)
 * @property type            the Nox type
 * @property defaultLiteral  if non-null, this parameter is optional; the string is a literal
 *                           value the compiler injects at the call site when the argument is
 *                           omitted (e.g. `"true"`, `"42"`, `"null"`)
 */
data class NoxParam(
    val name: String,
    val type: TypeRef,
    val defaultLiteral: String? = null,
)

/**
 * A resolved function or method that can be called at runtime.
 *
 * Used as the type for [nox.compiler.ast.MethodCallExpr.resolvedTarget] and
 * [nox.compiler.ast.FuncCallExpr.resolvedFunction].
 *
 * @property name       the function/method name (used as the SCALL key in the constant pool)
 * @property params     parameter names and types, in declaration order
 * @property returnType the declared return type
 * @property astNode    back-reference to the AST node for user-defined functions,
 *                      `null` for built-in functions and methods
 */
data class CallTarget(
    val name: String,
    val params: List<NoxParam>,
    val returnType: TypeRef,
    val astNode: RawFuncDef? = null,
)
