package nox.lsp.features

import nox.compiler.ast.typed.*
import nox.lsp.conversions.Positions
import nox.lsp.protocol.*

/**
 * Inlay hints for parameter names at call sites. Renders `name:` before each argument
 * so a reader doesn't have to jump to the function definition.
 */
object InlayHintsProvider {
    fun hintsFor(typed: TypedProgram): List<InlayHint> {
        val out = mutableListOf<InlayHint>()
        TypedWalker.walkProgram(typed, onExpr = { expr ->
            if (expr is TypedFuncCallExpr) {
                emitHints(expr.name, expr.args, expr.resolvedFunction.params.map { it.name }, out)
            }
            if (expr is TypedMethodCallExpr) {
                emitHints(expr.methodName, expr.args, expr.resolvedTarget.params.map { it.name }, out)
            }
        })
        return out
    }

    private fun emitHints(
        funcName: String,
        args: List<TypedExpr>,
        paramNames: List<String>,
        out: MutableList<InlayHint>,
    ) {
        for (i in args.indices) {
            val arg = args[i]
            val name = paramNames.getOrNull(i) ?: continue
            if (!shouldHint(arg)) continue
            val hint =
                InlayHint(position = 
                    Positions.toLspPosition(arg.loc),
                    label = "$name:",
                    kind = InlayHintKind.Parameter,
                    paddingRight = true
                )
            out.add(hint)
        }
    }

    /** Only hint for literals; variable names usually already describe what's passed. */
    private fun shouldHint(arg: TypedExpr): Boolean =
        arg is TypedIntLiteralExpr ||
            arg is TypedDoubleLiteralExpr ||
            arg is TypedStringLiteralExpr ||
            arg is TypedBoolLiteralExpr ||
            arg is TypedNullLiteralExpr
}
