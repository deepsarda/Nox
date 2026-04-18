package nox.lsp.features

import nox.compiler.ast.typed.*
import nox.compiler.types.FuncSymbol
import nox.lsp.protocol.*

/**
 * LSP hover: find the expression at cursor and render its type in a code block.
 * For identifiers that resolve to functions, include the signature.
 */
object HoverProvider {
    fun hover(
        program: TypedProgram,
        lspLine: Int,
        lspColumn: Int,
    ): Hover? {
        val expr = ExprAtPosition.find(program, lspLine, lspColumn) ?: return null
        val body = renderBody(expr) ?: return null
        return Hover(MarkupContent(MarkupKind.MARKDOWN, body))
    }

    private fun renderBody(expr: TypedExpr): String? {
        val signature = renderSignature(expr)
        val typeLine = "_type_: `${expr.type}`"
        return if (signature != null) "```nox\n$signature\n```\n\n$typeLine" else "```nox\n${expr.type}\n```"
    }

    private fun renderSignature(expr: TypedExpr): String? =
        when (expr) {
            is TypedFuncCallExpr -> renderFunction(expr.resolvedFunction)
            is TypedIdentifierExpr ->
                (expr.resolvedSymbol as? FuncSymbol)?.let { renderFunction(it) }
                    ?: "${expr.type} ${expr.name}"
            is TypedMethodCallExpr -> "${expr.type} ${expr.methodName}(…)"
            is TypedFieldAccessExpr -> "${expr.type} ${expr.fieldName}"
            else -> null
        }

    private fun renderFunction(sym: FuncSymbol): String {
        val params =
            sym.params.joinToString(", ") { p ->
                val prefix = if (p.isVarargs) "..." else ""
                "$prefix${p.type} ${p.name}"
            }
        return "${sym.returnType} ${sym.name}($params)"
    }
}
