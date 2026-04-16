package nox.lsp.features

import nox.compiler.ast.typed.*
import nox.compiler.types.FuncSymbol
import nox.compiler.types.GlobalSymbol
import nox.compiler.types.ParamSymbol
import nox.compiler.types.SourceLocation
import nox.lsp.protocol.*

/**
 * Full-document semantic tokens. Nox's lexer already categorizes tokens, but we use the
 * typed AST so we can distinguish `parameter` from `variable`, `function` from
 * `identifier`, etc.
 */
object SemanticTokensProvider {
    enum class TokenType {
        VARIABLE,
        PARAMETER,
        PROPERTY,
        FUNCTION,
        METHOD,
        TYPE,
        NAMESPACE,
        STRING,
        NUMBER,
        ;

        val lspName: String
            get() =
                when (this) {
                    VARIABLE -> "variable"
                    PARAMETER -> "parameter"
                    PROPERTY -> "property"
                    FUNCTION -> "function"
                    METHOD -> "method"
                    TYPE -> "type"
                    NAMESPACE -> "namespace"
                    STRING -> "string"
                    NUMBER -> "number"
                }
    }

    val LEGEND: SemanticTokensLegend = SemanticTokensLegend(TokenType.entries.map { it.lspName }, emptyList())

    private data class Raw(
        val line: Int,
        val col: Int,
        val length: Int,
        val type: TokenType,
    )

    fun fullFile(program: TypedProgram): SemanticTokens {
        val tokens = mutableListOf<Raw>()
        collect(program, tokens)
        tokens.sortWith(compareBy({ it.line }, { it.col }))
        return SemanticTokens(encode(tokens))
    }

    private fun collect(
        program: TypedProgram,
        out: MutableList<Raw>,
    ) {
        TypedWalker.walkProgram(
            program,
            onDecl = { decl ->
                when (decl) {
                    is TypedTypeDef -> {
                        out.emit(decl.loc, decl.name.length, TokenType.TYPE)
                        decl.fields.forEach { f -> out.emit(f.loc, f.name.length, TokenType.PROPERTY) }
                    }
                    is TypedFuncDef -> {
                        out.emit(decl.loc, decl.name.length, TokenType.FUNCTION)
                        decl.params.forEach { p -> out.emit(p.loc, p.name.length, TokenType.PARAMETER) }
                    }
                    is TypedMainDef -> decl.params.forEach { p -> out.emit(p.loc, p.name.length, TokenType.PARAMETER) }
                    is TypedGlobalVarDecl -> out.emit(decl.loc, decl.name.length, TokenType.VARIABLE)
                    else -> Unit
                }
            },
            onStmt = { stmt ->
                if (stmt is TypedVarDeclStmt) {
                    out.emit(stmt.loc, stmt.name.length, TokenType.VARIABLE)
                }
                //TODO: Improve this stuff
            },
            onExpr = { expr -> classify(expr, out) }
        )
    }

    private fun classify(
        expr: TypedExpr,
        out: MutableList<Raw>,
    ) {
        when (expr) {
            // TODO: We can do better.
            is TypedIdentifierExpr ->
                when (expr.resolvedSymbol) {
                    is FuncSymbol -> out.emit(expr.loc, expr.name.length, TokenType.FUNCTION)
                    is ParamSymbol -> out.emit(expr.loc, expr.name.length, TokenType.PARAMETER)
                    is GlobalSymbol -> out.emit(expr.loc, expr.name.length, TokenType.VARIABLE)
                    else -> out.emit(expr.loc, expr.name.length, TokenType.VARIABLE)
                }
            is TypedFuncCallExpr -> {
                // If it's a global call, color the identifier as function
                out.emit(expr.loc, expr.name.length, TokenType.FUNCTION)
            }
            is TypedMethodCallExpr -> {
                // Approximate method name position: target + '.'
                // We'll just push it 1 column past the dot for now, better than target start.
                // In a perfect world, AST would have the methodName loc.
                out.emit(SourceLocation(expr.loc.file, expr.loc.line, expr.loc.column + 1), expr.methodName.length, TokenType.METHOD)
            }
            is TypedFieldAccessExpr -> {
                out.emit(SourceLocation(expr.loc.file, expr.loc.line, expr.loc.column + 1), expr.fieldName.length, TokenType.PROPERTY)
            }
            is TypedStringLiteralExpr -> out.emit(expr.loc, expr.value.length + 2, TokenType.STRING)
            is TypedIntLiteralExpr -> out.emit(expr.loc, expr.value.toString().length, TokenType.NUMBER)
            is TypedDoubleLiteralExpr -> out.emit(expr.loc, expr.value.toString().length, TokenType.NUMBER)
            else -> Unit
        }
    }

    private fun MutableList<Raw>.emit(
        loc: SourceLocation,
        length: Int,
        type: TokenType,
    ) {
        if (length <= 0) return
        add(Raw((loc.line - 1).coerceAtLeast(0), loc.column, length, type))
    }

    private fun encode(tokens: List<Raw>): List<Int> {
        val out = mutableListOf<Int>()
        var prevLine = 0
        var prevCol = 0
        for (t in tokens) {
            val deltaLine = t.line - prevLine
            val deltaCol = if (deltaLine == 0) t.col - prevCol else t.col
            out += deltaLine
            out += deltaCol
            out += t.length
            out += t.type.ordinal
            out += 0
            prevLine = t.line
            prevCol = t.col
        }
        return out
    }
}
